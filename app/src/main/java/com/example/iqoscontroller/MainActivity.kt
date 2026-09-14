package com.example.iqoscontroller

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.iqoscontroller.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var usageTracker: UsageTracker
    private lateinit var connectionManager: IqosConnectionManager
    private lateinit var deviceRegistry: DeviceRegistry

    private var currentFilter = "TODAY"

    /** Set right before requesting BLE permissions when the user tapped a specific saved device. */
    private var pendingConnectAddress: String? = null

    // ---- SAF (Storage Access Framework) launchers for real, user-chosen backup file access ----
    // Registered as properties so they exist before onCreate/onStart, as required by the API.
    private val createBackupFileLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(usageTracker.exportBackupJson().toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, "فایل پشتیبان ذخیره شد", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "ذخیره فایل ناموفق بود: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }

    private val openBackupFileLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                if (text != null && usageTracker.restoreBackupJson(text)) {
                    renderStats(usageTracker.getStats())
                    Toast.makeText(this, "اطلاعات با موفقیت از فایل بازیابی شد", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "این فایل یک پشتیبان معتبر IQOS نیست", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "خواندن فایل ناموفق بود: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }

    /**
     * Guard for programmatic switch updates. Without it, writing the value we just read back from
     * the device into a switch would fire its listener and send the command all over again.
     */
    private var isSyncingUi = false

    private val commandLog = ArrayDeque<String>()

    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
        private const val NOTIF_PERMISSION_REQUEST_CODE = 102
        private const val COMMAND_LOG_SIZE = 12
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appPrefs = getSharedPreferences("iqos_app_settings", Context.MODE_PRIVATE)
        if (appPrefs.getBoolean("dark_mode", false)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        usageTracker = UsageTracker(this)
        deviceRegistry = DeviceRegistry(this)

        initConnectionManager()
        setupBottomNavigation()
        setupUsageFilters()
        setupDeviceFeatures()
        setupAdvancedTab()
        setupSettingsAndBackup()
        setupConnectionControls()
        setupHomeExtras()
        setupDeviceModeControls()
        requestNotificationPermissionIfNeeded()

        updateUiState(DeviceState.DISCONNECTED, IqosTransport.Type.NONE)
        renderStats(usageTracker.getStats())
        renderSnapshot(connectionManager.snapshot)
        renderDeviceChips()

        if (appPrefs.getBoolean("auto_connect", false)) {
            requestPermissionsAndConnect()
        }

        if (appPrefs.getBoolean("background_sync_enabled", false)) {
            IqosBackgroundService.start(this)
        }
    }

    /**
     * On Android 13+ (API 33+) NotificationManager.notify() is silently dropped unless
     * POST_NOTIFICATIONS is granted at runtime - this was missing before, which is why local
     * usage alerts from NotificationHelper never actually appeared even though that class was
     * already implemented and being called from UsageTracker.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIF_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    private fun initConnectionManager() {
        connectionManager = IqosConnectionManager.getInstance(applicationContext)
        connectionManager.addListener(uiListener)
    }

    private val uiListener = object : IqosConnectionManager.Listener {
            override fun onStateChanged(state: DeviceState, transportType: IqosTransport.Type) {
                runOnUiThread {
                    updateUiState(state, transportType)
                    DebugActivity.diagnosticMap["Device State"] = state.displayNameFa
                    DebugActivity.diagnosticMap["Transport"] = transportType.displayName
                }
            }

            override fun onPuffCountReceived(totalPuffs: Int) {
                runOnUiThread {
                    val stats = usageTracker.recordReading(totalPuffs)
                    renderStats(stats)
                    DebugActivity.diagnosticMap["Total Tera"] = "$totalPuffs"
                }
            }

            override fun onBatteryReceived(batteryPercent: Int, batteryVoltage: Float?) {
                runOnUiThread {
                    val voltText = if (batteryVoltage != null) " (${String.format("%.2fV", batteryVoltage)})" else ""
                    binding.tvBattery.text = "$batteryPercent%$voltText"
                    binding.tvFlexBatteryStatus.text = "آخرین خواندن زنده دستگاه: $batteryPercent%$voltText"
                    DebugActivity.diagnosticMap["Battery"] = "$batteryPercent%$voltText"
                }
            }

            override fun onDaysUsedReceived(daysUsed: Int) {
                runOnUiThread {
                    DebugActivity.diagnosticMap["Days Used"] = "$daysUsed روز"
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    DebugActivity.diagnosticMap["Last Error"] = message
                }
            }

            override fun onDiagnosticInfo(key: String, value: String) {
                runOnUiThread {
                    DebugActivity.diagnosticMap[key] = value
                }
            }

            override fun onRawPacketReceived(tag: String, hex: String) {
                runOnUiThread {
                    DebugActivity.diagnosticMap["Last Raw $tag"] = hex
                }
            }

            override fun onFrameDecoded(response: IqosResponse) {
                runOnUiThread {
                    if (response is IqosResponse.Unknown) {
                        DebugActivity.diagnosticMap["Unknown Frame"] = "${response.hex}  (${response.reason})"
                    }
                }
            }

            override fun onSnapshotUpdated(snapshot: DeviceSnapshot) {
                runOnUiThread { renderSnapshot(snapshot) }
            }

            override fun onCommandResult(result: CommandResult) {
                runOnUiThread { handleCommandResult(result) }
            }

            override fun onGattTreeUpdated(lines: List<String>) {
                runOnUiThread {
                    binding.tvAdvGattTree.text = if (lines.isEmpty()) {
                        getString(R.string.adv_gatt_empty)
                    } else {
                        lines.joinToString("\n")
                    }
                }
            }
        }

    // ---------------------------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------------------------

    /**
     * Switches between the 5 tabs. Kept as a member function (not a local one) so other places -
     * the home screen's "تنظیمات دستگاه" shortcut, the header bell icon, etc. - can jump to a tab
     * too, not just the bottom nav itself.
     */
    private fun selectTab(selectedId: Int) {
        binding.tabHomeView.visibility = if (selectedId == R.id.navHome) View.VISIBLE else View.GONE
        binding.tabDeviceView.visibility = if (selectedId == R.id.navDevice) View.VISIBLE else View.GONE
        binding.tabUsageView.visibility = if (selectedId == R.id.navUsage) View.VISIBLE else View.GONE
        binding.tabSettingsView.visibility = if (selectedId == R.id.navSettings) View.VISIBLE else View.GONE
        binding.tabAdvancedView.visibility = if (selectedId == R.id.navAdvanced) View.VISIBLE else View.GONE

        val onPillColor = ContextCompat.getColor(this, R.color.iqos_dark_bg)
        val mutedColor = ContextCompat.getColor(this, R.color.text_muted)
        val tealColor = ContextCompat.getColor(this, R.color.iqos_teal)

        val bottomItems = listOf(
            Triple(R.id.navHome, binding.navHome, binding.navHomeIcon to binding.navHomeLabel),
            Triple(R.id.navDevice, binding.navDevice, binding.navDeviceIcon to binding.navDeviceLabel),
            Triple(R.id.navUsage, binding.navUsage, binding.navUsageIcon to binding.navUsageLabel),
            Triple(R.id.navSettings, binding.navSettings, binding.navSettingsIcon to binding.navSettingsLabel)
        )

        bottomItems.forEach { (id, container, iconLabel) ->
            val (icon, label) = iconLabel
            val isSelected = id == selectedId
            container.setBackgroundResource(
                if (isSelected) R.drawable.shape_nav_pill_active else R.drawable.shape_nav_pill_inactive
            )
            val color = if (isSelected) onPillColor else mutedColor
            icon.setColorFilter(color)
            label.setTextColor(color)
        }

        // The "پیشرفته" link lives inside the Device tab as a plain row, not in the bottom bar.
        val advColor = if (selectedId == R.id.navAdvanced) tealColor else mutedColor
        binding.navAdvancedIcon.setColorFilter(advColor)
        binding.navAdvancedLabel.setTextColor(advColor)
    }

    private fun setupBottomNavigation() {
        binding.navHome.setOnClickListener { selectTab(R.id.navHome) }
        binding.navDevice.setOnClickListener { selectTab(R.id.navDevice) }
        binding.navUsage.setOnClickListener { selectTab(R.id.navUsage) }
        binding.navSettings.setOnClickListener { selectTab(R.id.navSettings) }
        binding.navAdvanced.setOnClickListener {
            selectTab(R.id.navAdvanced)
            if (connectionManager.isConnected()) connectionManager.refreshFullSnapshot()
        }

        // Apply initial tint/pill state (Home selected by default)
        selectTab(R.id.navHome)
    }

    private fun setupUsageFilters() {
        binding.btnFilterToday.setOnClickListener { currentFilter = "TODAY"; renderStats(usageTracker.getStats()) }
        binding.btnFilterWeek.setOnClickListener { currentFilter = "WEEK"; renderStats(usageTracker.getStats()) }
        binding.btnFilterMonth.setOnClickListener { currentFilter = "MONTH"; renderStats(usageTracker.getStats()) }
        binding.btnFilterYear.setOnClickListener { currentFilter = "YEAR"; renderStats(usageTracker.getStats()) }
    }

    private fun styleFilterPill(pill: TextView, selected: Boolean) {
        pill.setBackgroundResource(
            if (selected) R.drawable.shape_filter_pill_selected else R.drawable.shape_filter_pill_unselected
        )
        pill.setTextColor(
            ContextCompat.getColor(this, if (selected) R.color.iqos_dark_bg else R.color.text_muted)
        )
    }

    // ---------------------------------------------------------------------------------------
    // Home tab extras: header shortcuts, quick-action buttons, multi-device switcher
    // ---------------------------------------------------------------------------------------

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun setupHomeExtras() {
        binding.btnHomeDeviceSettings.setOnClickListener { selectTab(R.id.navDevice) }
        binding.btnHomeRefreshStatus.setOnClickListener { binding.btnHeaderRefresh.performClick() }
        binding.btnHeaderBell.setOnClickListener { selectTab(R.id.navSettings) }
        binding.btnHeaderProfile.setOnClickListener { binding.btnRenameDevice.performClick() }
        binding.btnAddDevice.setOnClickListener { requestPermissionsAndConnect() }
    }

    /** Rebuilds the horizontal row of saved-device chips on the Home tab. */
    private fun renderDeviceChips() {
        val activeAddress = deviceRegistry.getActiveAddress()
        val currentlyConnectedAddress = connectionManager.snapshot.address
        val devices = deviceRegistry.getAll()

        binding.llDeviceChips.removeAllViews()

        devices.forEach { device ->
            val isActive = device.address == activeAddress
            val isConnected = connectionManager.isConnected() && device.address == currentlyConnectedAddress

            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(if (isActive) R.drawable.shape_chip_device_active else R.drawable.shape_chip_device)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44))
                lp.marginEnd = dp(8)
                layoutParams = lp
                isClickable = true
                isFocusable = true
            }

            val dot = View(this).apply {
                val size = dp(8)
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(8) }
                setBackgroundResource(R.drawable.shape_status_indicator_gray)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (isConnected) R.color.status_connected else R.color.text_muted
                    )
                )
            }

            val label = TextView(this).apply {
                text = device.name
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (isActive) R.color.iqos_teal else R.color.text_secondary
                    )
                )
            }

            chip.addView(dot)
            chip.addView(label)

            chip.setOnClickListener { connectToSavedDevice(device.address) }
            chip.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.remove_device_title))
                    .setMessage(getString(R.string.remove_device_confirm))
                    .setPositiveButton(getString(R.string.dialog_yes)) { _, _ ->
                        deviceRegistry.remove(device.address)
                        renderDeviceChips()
                    }
                    .setNegativeButton(getString(R.string.dialog_no), null)
                    .show()
                true
            }

            binding.llDeviceChips.addView(chip)
        }

        // The "افزودن دستگاه" chip is defined in XML - re-attach it as the last child every time.
        (binding.btnAddDevice.parent as? LinearLayout)?.removeView(binding.btnAddDevice)
        binding.llDeviceChips.addView(binding.btnAddDevice)
    }

    /** Connects to one of the user's saved devices directly, requesting BLE permissions if needed. */
    private fun connectToSavedDevice(address: String) {
        deviceRegistry.setActiveAddress(address)
        renderDeviceChips()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scanGranted = checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val connectGranted = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (scanGranted && connectGranted) {
                connectionManager.connectToDevice(address)
            } else {
                pendingConnectAddress = address
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
                    PERMISSION_REQUEST_CODE
                )
            }
        } else {
            val locGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (locGranted) {
                connectionManager.connectToDevice(address)
            } else {
                pendingConnectAddress = address
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.BLUETOOTH,
                        Manifest.permission.BLUETOOTH_ADMIN,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    /** Weekly bar chart on the Usage tab, built from real recorded daily totals. */
    private fun renderWeeklyChart() {
        val bars = usageTracker.getLast7DaysTrend()
        val maxCount = (bars.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)

        binding.llWeeklyChart.removeAllViews()
        bars.forEach { bar ->
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            }

            val barHeight = if (bar.count <= 0) dp(2) else (dp(90) * (bar.count.toFloat() / maxCount)).toInt().coerceAtLeast(dp(4))

            val countLabel = TextView(this).apply {
                text = "${bar.count}"
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
            }

            val barView = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(20), barHeight).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(6)
                }
                setBackgroundResource(if (bar.isToday) R.drawable.shape_bar_chart else R.drawable.shape_bar_chart_muted)
            }

            val dayLabel = TextView(this).apply {
                text = bar.labelFa
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (bar.isToday) R.color.iqos_teal else R.color.text_muted
                    )
                )
            }

            column.addView(countLabel)
            column.addView(barView)
            column.addView(dayLabel)
            column.isClickable = true
            column.isFocusable = true
            column.setBackgroundResource(android.R.drawable.list_selector_background)
            column.setOnClickListener { showHourlyBreakdownDialog(bar.dateKey, bar.labelFa, bar.count) }
            binding.llWeeklyChart.addView(column)
        }
    }

    /** Opens a table dialog with hour-by-hour tera counts for the tapped day of the weekly chart. */
    private fun showHourlyBreakdownDialog(dateKey: String, dayLabelFa: String, dayTotal: Int) {
        val hours = usageTracker.getHourlyBreakdownForDate(dateKey)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        val totalLine = TextView(this).apply {
            text = "$dateKey  ($dayLabelFa)  —  مجموع: $dayTotal ترا"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setPadding(0, 0, 0, dp(12))
        }
        container.addView(totalLine)

        val hasAnyData = hours.any { it.count > 0 }
        if (!hasAnyData) {
            val empty = TextView(this).apply {
                text = "برای این روز هیچ مصرفی ثبت نشده است."
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
            }
            container.addView(empty)
        } else {
            hours.forEach { hc ->
                if (hc.count <= 0) return@forEach
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, dp(6))
                }

                val hourText = TextView(this).apply {
                    text = String.format("%02d:00 - %02d:00", hc.hour, (hc.hour + 1) % 24)
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val countText = TextView(this).apply {
                    text = "${hc.count} ترا"
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.iqos_teal))
                }

                row.addView(hourText)
                row.addView(countText)
                container.addView(row)

                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                    setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider_color))
                }
                container.addView(divider)
            }
        }

        val scroll = android.widget.ScrollView(this).apply {
            addView(container)
        }

        AlertDialog.Builder(this)
            .setTitle("مصرف ساعتی")
            .setView(scroll)
            .setPositiveButton("بستن", null)
            .show()
    }

    /** Today's individual puff events on the Usage tab, newest first. */
    private fun renderTodaySessions() {
        val sessions = usageTracker.getTodaySessions()
        binding.llTodaySessions.removeAllViews()

        if (sessions.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.usage_no_sessions_today)
                textSize = 12f
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
            }
            binding.llTodaySessions.addView(empty)
            return
        }

        sessions.forEach { session ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.shape_card_stat)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(8)
                layoutParams = lp
            }

            val timeLabel = TextView(this).apply {
                text = session.timeStr
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
            }

            val titleColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginStart = dp(12)
                layoutParams = lp
            }

            val titleLabel = TextView(this).apply {
                text = "ترا شماره ${session.sequentialCount}"
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            }

            val subtitleLabel = TextView(this).apply {
                text = connectionManager.snapshot.model.displayName
                textSize = 10f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
            }

            titleColumn.addView(titleLabel)
            titleColumn.addView(subtitleLabel)

            row.addView(timeLabel)
            row.addView(titleColumn)
            binding.llTodaySessions.addView(row)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Device tab: Eco / Performance segmented control (drives the existing FlexBattery switch)
    // ---------------------------------------------------------------------------------------

    private fun setupDeviceModeControls() {
        binding.tvFlexEcoPill.setOnClickListener {
            if (!requireConnection()) {
                binding.swAdvFlexBatteryEco.isChecked = true
                return@setOnClickListener
            }
            binding.swAdvFlexBatteryEco.isChecked = true
        }
        binding.tvFlexPerfPill.setOnClickListener {
            if (!requireConnection()) return@setOnClickListener
            binding.swAdvFlexBatteryEco.isChecked = false
        }
    }

    private fun styleFlexModePills(ecoSelected: Boolean) {
        binding.tvFlexEcoPill.setBackgroundResource(
            if (ecoSelected) R.drawable.shape_filter_pill_selected else R.drawable.shape_filter_pill_unselected
        )
        binding.tvFlexEcoPill.setTextColor(
            ContextCompat.getColor(this, if (ecoSelected) R.color.iqos_dark_bg else R.color.text_muted)
        )
        binding.tvFlexPerfPill.setBackgroundResource(
            if (!ecoSelected) R.drawable.shape_filter_pill_selected else R.drawable.shape_filter_pill_unselected
        )
        binding.tvFlexPerfPill.setTextColor(
            ContextCompat.getColor(this, if (!ecoSelected) R.color.iqos_dark_bg else R.color.text_muted)
        )
    }

    // ---------------------------------------------------------------------------------------
    // Device tab
    // ---------------------------------------------------------------------------------------

    private fun setupDeviceFeatures() {
        val appPrefs = getSharedPreferences("iqos_app_settings", Context.MODE_PRIVATE)

        binding.swSmartGestureCmd.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) return@setOnCheckedChangeListener
            if (!requireConnection()) return@setOnCheckedChangeListener
            connectionManager.setSmartGesture(isChecked)
            noteCommandSent("ژست هوشمند", isChecked)
        }

        binding.swAutostartCmd.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) return@setOnCheckedChangeListener
            if (!requireConnection()) return@setOnCheckedChangeListener
            connectionManager.setAutoStart(isChecked)
            noteCommandSent("روشن شدن خودکار", isChecked)
        }

        binding.swVibrationCmd.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) return@setOnCheckedChangeListener
            if (!requireConnection()) return@setOnCheckedChangeListener
            connectionManager.setVibrationBurst(isChecked)
            binding.tvVibrationStatus.text = if (isChecked) {
                "در حال لرزش (Find My IQOS)…"
            } else {
                getString(R.string.status_off)
            }
        }

        binding.swIlluminationCmd.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) return@setOnCheckedChangeListener
            if (!requireConnection()) return@setOnCheckedChangeListener
            connectionManager.setBrightness(isChecked)
            noteCommandSent("روشنایی LED", isChecked)
        }

        binding.swPauseModeCmd.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) return@setOnCheckedChangeListener
            if (!requireConnection()) return@setOnCheckedChangeListener
            connectionManager.setPauseMode(isChecked)
            noteCommandSent("حالت توقف", isChecked)
        }

        binding.swFlexPuffCmd.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) return@setOnCheckedChangeListener
            if (!requireConnection()) return@setOnCheckedChangeListener
            connectionManager.setFlexPuff(isChecked)
            noteCommandSent("فلکس‌پاف", isChecked)
        }

        binding.btnFlexBatteryRefresh.setOnClickListener {
            if (connectionManager.isConnected()) {
                connectionManager.refreshFullSnapshot()
                Toast.makeText(this, "درخواست خواندن کامل وضعیت از دستگاه ارسال شد…", Toast.LENGTH_SHORT).show()
            } else {
                binding.tvFlexBatteryStatus.text = getString(R.string.flex_battery_desc)
                Toast.makeText(this, "دستگاه متصل نیست", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRenameDevice.setOnClickListener {
            val input = android.widget.EditText(this)
            input.setText(appPrefs.getString("device_display_name", getString(R.string.device_model_title)))
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.rename_device))
                .setView(input)
                .setPositiveButton("ذخیره") { _, _ ->
                    val newName = input.text.toString().trim()
                    if (newName.isNotEmpty()) {
                        appPrefs.edit().putString("device_display_name", newName).apply()
                        binding.tvDeviceTitle.text = newName
                        Toast.makeText(this, "نام دستگاه ذخیره شد", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("انصراف", null)
                .show()
        }

        // Restore saved device name on launch
        appPrefs.getString("device_display_name", null)?.let { savedName ->
            binding.tvDeviceTitle.text = savedName
        }
    }

    // ---------------------------------------------------------------------------------------
    // Advanced tab
    // ---------------------------------------------------------------------------------------

    private fun setupAdvancedTab() {
        binding.btnAdvRefresh.setOnClickListener {
            if (!requireConnection()) return@setOnClickListener
            connectionManager.refreshFullSnapshot()
            Toast.makeText(this, "خواندن کامل وضعیت دستگاه شروع شد…", Toast.LENGTH_SHORT).show()
        }

        binding.btnAdvExplore.setOnClickListener {
            if (!requireConnection()) return@setOnClickListener
            connectionManager.exploreDevice()
            Toast.makeText(this, "کاوش تمام مشخصه‌های دستگاه شروع شد…", Toast.LENGTH_SHORT).show()
        }

        binding.swAdvBrightnessHigh.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) return@setOnCheckedChangeListener
            if (!requireConnection()) return@setOnCheckedChangeListener
            connectionManager.setBrightness(isChecked)
            noteCommandSent("روشنایی LED", isChecked)
        }

        binding.swAdvFlexBatteryEco.setOnCheckedChangeListener { _, isChecked ->
            styleFlexModePills(isChecked)
            if (isSyncingUi) return@setOnCheckedChangeListener
            if (!requireConnection()) return@setOnCheckedChangeListener
            connectionManager.setFlexBattery(isChecked)
            appendCommandLog("حالت فلکس‌باتری → ${if (isChecked) "اکو" else "پرفورمنس"} (ارسال شد)")
        }

        binding.btnAdvLock.setOnClickListener {
            if (!requireConnection()) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.adv_lock))
                .setMessage("دستگاه قفل شود؟ برای بازکردن، دکمه بازکردن قفل را در همین صفحه بزنید.")
                .setPositiveButton(getString(R.string.dialog_yes)) { _, _ -> connectionManager.setDeviceLock(true) }
                .setNegativeButton(getString(R.string.dialog_no), null)
                .show()
        }

        binding.btnAdvUnlock.setOnClickListener {
            if (!requireConnection()) return@setOnClickListener
            connectionManager.setDeviceLock(false)
        }

        binding.btnAdvFindStart.setOnClickListener {
            if (!requireConnection()) return@setOnClickListener
            connectionManager.setVibrationBurst(true)
        }

        binding.btnAdvFindStop.setOnClickListener {
            if (!requireConnection()) return@setOnClickListener
            connectionManager.setVibrationBurst(false)
        }

        binding.btnAdvApplyVibration.setOnClickListener {
            if (!requireConnection()) return@setOnClickListener
            val model = connectionManager.snapshot.model
            connectionManager.setVibrationSettings(
                whenHeatingStart = binding.swAdvVibHeating.isChecked,
                whenStartingToUse = binding.swAdvVibStartUse.isChecked,
                whenPuffEnd = binding.swAdvVibPuffEnd.isChecked,
                whenManuallyTerminated = binding.swAdvVibManualStop.isChecked,
                whenChargeStart = if (model.supportsChargeStartVibration()) binding.swAdvVibChargeStart.isChecked else null
            )
        }

        binding.btnAdvCopy.setOnClickListener {
            val report = connectionManager.snapshot.toPlainText()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("IQOS Report", report))
            Toast.makeText(this, "گزارش فنی کپی شد", Toast.LENGTH_SHORT).show()
        }

        binding.btnAdvCopyFullDiagnostic.setOnClickListener {
            val report = buildFullDiagnosticReport()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("IQOS Full Diagnostic", report))
            Toast.makeText(
                this,
                "گزارش کامل کپی شد - آماده برای چسباندن و ارسال",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Combines everything useful for forensic/protocol analysis into one plain-text block the
     * user can paste directly into a chat: the standard technical report (unchanged), this
     * session's frame-forensics summary, and the persistent cross-session unknown-frame history
     * (built up automatically over every session since [UnknownFrameHistory] was added - not just
     * whatever happened in the last few minutes).
     */
    private fun buildFullDiagnosticReport(): String {
        val sb = StringBuilder()
        sb.appendLine("========================================")
        sb.appendLine("گزارش کامل تشخیصی IQOS - برای تحلیل پروتکل")
        sb.appendLine("تولید‌شده: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine("========================================")
        sb.appendLine()
        sb.appendLine(connectionManager.snapshot.toPlainText())
        sb.appendLine()
        sb.appendLine("========================================")
        sb.appendLine("آمار فریم‌های این نشست (Protocol Forensics)")
        sb.appendLine("========================================")
        sb.appendLine(FrameForensics.summaryText())
        sb.appendLine()
        sb.appendLine("========================================")
        sb.appendLine("تاریخچه دائمی مشخصه‌ها و فریم‌های ناشناخته (همه نشست‌ها)")
        sb.appendLine("========================================")
        sb.appendLine(UnknownFrameHistory(applicationContext).buildReportSection())
        return sb.toString()
    }

    private fun requireConnection(): Boolean {
        if (connectionManager.isConnected()) return true
        Toast.makeText(this, "دستگاه متصل نیست - ابتدا از تب خانه متصل شوید", Toast.LENGTH_SHORT).show()
        syncSwitchesFromSnapshot(connectionManager.snapshot)
        return false
    }

    private fun noteCommandSent(label: String, enabled: Boolean) {
        appendCommandLog("$label → ${if (enabled) "روشن" else "خاموش"} (ارسال شد، در انتظار تأیید دستگاه)")
    }

    private fun handleCommandResult(result: CommandResult) {
        Toast.makeText(this, result.messageFa(), Toast.LENGTH_SHORT).show()
        appendCommandLog("${result.messageFa()} — ${result.detailFa}")
        DebugActivity.diagnosticMap["Cmd ${result.tag}"] = "${result.status.name}: ${result.detailFa}"

        // Whatever the device reports is the truth: put the switches back in sync with it.
        syncSwitchesFromSnapshot(connectionManager.snapshot)
    }

    private fun appendCommandLog(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        commandLog.addFirst("$stamp  $line")
        while (commandLog.size > COMMAND_LOG_SIZE) commandLog.removeLast()
        binding.tvAdvCommandLog.text = commandLog.joinToString("\n")
    }

    // ---------------------------------------------------------------------------------------
    // Rendering real device data
    // ---------------------------------------------------------------------------------------

    private fun renderSnapshot(snapshot: DeviceSnapshot) {
        val unknown = getString(R.string.value_unknown)

        // ---- Home hero card
        val volts = snapshot.batteryVolts()
        binding.tvHomeVoltage.text = if (volts != null) String.format(Locale.US, "%.3f V", volts) else unknown
        binding.tvHomeSignal.text = snapshot.signalQualityFa() ?: unknown
        binding.tvHomeFirmware.text = snapshot.stickFirmware
            ?: snapshot.firmwareRevision
            ?: snapshot.softwareRevision
            ?: unknown

        val percent = snapshot.displayPercent()
        if (percent != null) {
            val voltSuffix = if (volts != null) " (${String.format(Locale.US, "%.2fV", volts)})" else ""
            binding.tvBattery.text = "$percent%$voltSuffix"
        }

        binding.tvHomeModelLine.text = buildString {
            append("مدل شناسایی‌شده: ${snapshot.model.displayName}")
            snapshot.holderFirmware?.let { append(" · فریم‌ور هولدر $it") }
            snapshot.daysUsed?.let { append(" · $it روز در سرویس") }
        }

        // ---- Home stat grid (lifetime puffs / days in service, straight from the device)
        binding.tvHomeLifetimeCount.text = snapshot.lifetimePuffCount?.let { "$it ${getString(R.string.unit_tera)}" } ?: "—"
        binding.tvHomeDaysInService.text = snapshot.daysUsed?.let { "$it ${getString(R.string.unit_day)}" } ?: "—"

        // ---- Device tab: identity card (previously never filled in with real data)
        binding.tvDevModel.text = "مدل: ${snapshot.modelNumber ?: snapshot.model.displayName}"
        binding.tvDevSerial.text = "سریال: ${snapshot.serialNumber ?: unknown}"
        binding.tvDevBtStatus.text = "بلوتوث: ${if (snapshot.transport == IqosTransport.Type.BLE && snapshot.state == DeviceState.READY) "متصل" else snapshot.state.displayNameFa}"
        binding.tvDevUsbStatus.text = "USB: ${if (snapshot.transport == IqosTransport.Type.USB) snapshot.state.displayNameFa else "متصل نیست"}"

        // ---- Device tab: per-feature live state
        binding.tvSmartGestureStatus.text = statusText(snapshot.smartGestureEnabled, IqosCapability.SMART_GESTURE, snapshot)
        binding.tvAutostartStatus.text = statusText(snapshot.autoStartEnabled, IqosCapability.AUTO_START, snapshot)
        binding.tvPauseModeStatus.text = statusText(snapshot.pauseModeEnabled, IqosCapability.PAUSE_MODE, snapshot)
        binding.tvFlexPuffStatus.text = statusText(snapshot.flexPuffEnabled, IqosCapability.FLEX_PUFF, snapshot)
        binding.tvIlluminationStatus.text = when (snapshot.brightnessHigh) {
            true -> "روشنایی: زیاد (خوانده‌شده از دستگاه)"
            false -> "روشنایی: کم (خوانده‌شده از دستگاه)"
            null -> "روشنایی: $unknown"
        }

        val flexBatteryLine = when (snapshot.flexBatteryEco) {
            true -> "حالت: اکو (Eco)"
            false -> "حالت: پرفورمنس (Performance)"
            null -> "حالت: $unknown"
        }
        val batteryLine = percent?.let { "شارژ $it درصد" } ?: "شارژ $unknown"
        val voltLine = volts?.let { String.format(Locale.US, "%.3f V", it) } ?: unknown
        binding.tvFlexBatteryStatus.text = "$flexBatteryLine · $batteryLine · ولتاژ $voltLine"

        // ---- Advanced tab
        binding.tvAdvBrightnessStatus.text = when (snapshot.brightnessHigh) {
            true -> "وضعیت فعلی دستگاه: زیاد"
            false -> "وضعیت فعلی دستگاه: کم"
            null -> "وضعیت فعلی دستگاه: $unknown"
        }
        binding.tvAdvFlexBatteryStatus.text = when (snapshot.flexBatteryEco) {
            true -> "وضعیت فعلی دستگاه: اکو"
            false -> "وضعیت فعلی دستگاه: پرفورمنس"
            null -> "وضعیت فعلی دستگاه: $unknown"
        }

        binding.tvAdvReport.text = snapshot.toReportRows().joinToString("\n") { (key, value) ->
            if (value.isEmpty()) "\n$key" else "$key:  $value"
        }
        binding.tvAdvCapabilities.text = snapshot.capabilityRows().joinToString("\n") { (key, value) ->
            "$key:  $value"
        }

        if (!snapshot.model.supportsChargeStartVibration()) {
            binding.swAdvVibChargeStart.isEnabled = snapshot.model == IqosDeviceModel.UNKNOWN
        } else {
            binding.swAdvVibChargeStart.isEnabled = true
        }

        snapshot.flexBatteryEco?.let { styleFlexModePills(it) }

        syncSwitchesFromSnapshot(snapshot)
    }

    private fun statusText(value: Boolean?, capability: IqosCapability, snapshot: DeviceSnapshot): String = when {
        value == true -> "${getString(R.string.status_on)} (خوانده‌شده از دستگاه)"
        value == false -> "${getString(R.string.status_off)} (خوانده‌شده از دستگاه)"
        snapshot.model != IqosDeviceModel.UNKNOWN && !snapshot.model.supports(capability) ->
            "در این مدل وجود ندارد (${snapshot.model.displayName})"
        else -> getString(R.string.value_unknown)
    }

    /** Pushes device-reported values into the switches without re-triggering their listeners. */
    private fun syncSwitchesFromSnapshot(snapshot: DeviceSnapshot) {
        isSyncingUi = true
        try {
            snapshot.smartGestureEnabled?.let { binding.swSmartGestureCmd.isChecked = it }
            snapshot.autoStartEnabled?.let { binding.swAutostartCmd.isChecked = it }
            snapshot.pauseModeEnabled?.let { binding.swPauseModeCmd.isChecked = it }
            snapshot.flexPuffEnabled?.let { binding.swFlexPuffCmd.isChecked = it }
            snapshot.brightnessHigh?.let {
                binding.swIlluminationCmd.isChecked = it
                binding.swAdvBrightnessHigh.isChecked = it
            }
            snapshot.flexBatteryEco?.let { binding.swAdvFlexBatteryEco.isChecked = it }
            snapshot.vibrateOnHeatingStart?.let { binding.swAdvVibHeating.isChecked = it }
            snapshot.vibrateOnStartingToUse?.let { binding.swAdvVibStartUse.isChecked = it }
            snapshot.vibrateOnPuffEnd?.let { binding.swAdvVibPuffEnd.isChecked = it }
            snapshot.vibrateOnManualStop?.let { binding.swAdvVibManualStop.isChecked = it }
            snapshot.vibrateOnChargeStart?.let { binding.swAdvVibChargeStart.isChecked = it }
        } finally {
            isSyncingUi = false
        }
    }

    // ---------------------------------------------------------------------------------------
    // Settings tab
    // ---------------------------------------------------------------------------------------

    private fun setupSettingsAndBackup() {
        val appPrefs = getSharedPreferences("iqos_app_settings", Context.MODE_PRIVATE)

        binding.swDarkMode.isChecked = appPrefs.getBoolean("dark_mode", false)
        binding.swDarkMode.setOnCheckedChangeListener { _, isChecked ->
            appPrefs.edit().putBoolean("dark_mode", isChecked).apply()
            AppCompatDelegate.setDefaultNightMode(
                if (isChecked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        binding.swAutoConnect.isChecked = appPrefs.getBoolean("auto_connect", false)
        binding.swAutoConnect.setOnCheckedChangeListener { _, isChecked ->
            appPrefs.edit().putBoolean("auto_connect", isChecked).apply()
        }

        binding.swLocalNotif.isChecked = appPrefs.getBoolean("notif_enabled", true)
        binding.swLocalNotif.setOnCheckedChangeListener { _, isChecked ->
            appPrefs.edit().putBoolean("notif_enabled", isChecked).apply()
        }

        binding.swBackgroundSync.isChecked = appPrefs.getBoolean("background_sync_enabled", false)
        binding.swBackgroundSync.setOnCheckedChangeListener { _, isChecked ->
            appPrefs.edit().putBoolean("background_sync_enabled", isChecked).apply()
            if (isChecked) {
                if (deviceRegistry.getActiveAddress() == null) {
                    Toast.makeText(
                        this,
                        "ابتدا یک‌بار دستگاه را از تب خانه متصل کنید تا برای همگام‌سازی پس‌زمینه ذخیره شود",
                        Toast.LENGTH_LONG
                    ).show()
                }
                IqosBackgroundService.start(this)
                Toast.makeText(this, "همگام‌سازی پس‌زمینه فعال شد", Toast.LENGTH_SHORT).show()
            } else {
                IqosBackgroundService.stop(this)
                Toast.makeText(this, "همگام‌سازی پس‌زمینه غیرفعال شد", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnBatteryOptimization.setOnClickListener {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !powerManager.isIgnoringBatteryOptimizations(packageName)
            ) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "امکان باز کردن تنظیمات باتری وجود ندارد", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "این اپ از قبل از محدودیت باتری معاف است", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnBackup.setOnClickListener {
            val fileName = "IQOS_Backup_${SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())}.json"
            createBackupFileLauncher.launch(fileName)
        }

        binding.btnRestore.setOnClickListener {
            openBackupFileLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }

        binding.btnResetHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("بازنشانی تاریخچه")
                .setMessage("آیا از حذف تاریخچه مصرف اطمینان دارید؟")
                .setPositiveButton("بله") { _, _ ->
                    usageTracker.resetHistory()
                    renderStats(usageTracker.getStats())
                    Toast.makeText(this, "تاریخچه پاک شد", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("خیر", null)
                .show()
        }

        binding.btnOpenDebugActivity.setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }
    }

    private fun setupConnectionControls() {
        binding.btnConnect.setOnClickListener {
            if (connectionManager.isConnected()) {
                connectionManager.disconnect()
            } else {
                requestPermissionsAndConnect()
            }
        }

        binding.btnDisconnect.setOnClickListener {
            connectionManager.disconnect()
        }

        val refreshAction = View.OnClickListener {
            if (connectionManager.isConnected()) {
                AppLogger.i("CONNECTION", "User triggered live hardware diagnosis refresh")
                connectionManager.refreshFullSnapshot()
                Toast.makeText(this, "درخواست دریافت داده‌های واقعی ارسال شد…", Toast.LENGTH_SHORT).show()
            } else {
                renderStats(usageTracker.getStats())
                Toast.makeText(this, "دستگاه متصل نیست", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnHeaderRefresh.setOnClickListener(refreshAction)
    }

    private fun updateUiState(state: DeviceState, transportType: IqosTransport.Type) {
        FrameForensics.currentAppState = state.name
        binding.tvStatus.text = "وضعیت: ${state.displayNameFa}"
        binding.tvTransport.text = transportType.displayName

        when (state) {
            DeviceState.DISCONNECTED -> {
                binding.viewStatusIndicator.setBackgroundResource(R.drawable.shape_status_indicator)
                binding.progressBar.visibility = View.GONE
                binding.btnConnect.isEnabled = true
                binding.btnDisconnect.isEnabled = false
                binding.btnConnect.text = getString(R.string.btn_scan_connect)
                binding.tvDeviceName.text = "دستگاه متصل: هیچ"
                binding.tvBattery.text = getString(R.string.battery_unknown)
                binding.tvHomeDiagnosisStatus.text = "وضعیت سلامت: قطع ارتباط"
            }
            DeviceState.SCANNING, DeviceState.CONNECTING, DeviceState.DISCOVERING_SERVICES -> {
                binding.progressBar.visibility = View.VISIBLE
                binding.btnConnect.isEnabled = false
                binding.btnDisconnect.isEnabled = true
                binding.tvHomeDiagnosisStatus.text = "در حال تبادل اطلاعات اولیه با دستگاه…"
            }
            DeviceState.CONNECTED, DeviceState.READY -> {
                binding.progressBar.visibility = View.GONE
                binding.btnConnect.isEnabled = false
                binding.btnDisconnect.isEnabled = true
                val snapshot = connectionManager.snapshot
                binding.tvDeviceName.text = snapshot.deviceName ?: snapshot.model.displayName
                binding.tvHomeDiagnosisStatus.text = if (state == DeviceState.READY) {
                    getString(R.string.home_ready_to_use)
                } else {
                    "متصل شد - در حال بررسی سرویس‌ها"
                }
                snapshot.address?.let { address ->
                    deviceRegistry.upsert(address, snapshot.deviceName, snapshot.model.name)
                    renderDeviceChips()
                }
            }
            DeviceState.BUSY -> {
                binding.progressBar.visibility = View.VISIBLE
            }
            DeviceState.ERROR -> {
                binding.progressBar.visibility = View.GONE
                binding.btnConnect.isEnabled = true
                binding.btnDisconnect.isEnabled = false
                binding.tvHomeDiagnosisStatus.text = "خطا در ارتباط با دستگاه"
            }
        }
    }

    private fun renderStats(stats: UsageTracker.UsageStats) {
        binding.tvHomeTodayCount.text = "${stats.todayPuffs} ترا"
        binding.tvHomeLastTime.text = stats.lastUsedTime
        binding.tvHomeCycleCounter.text = "${stats.totalPuffs} ${getString(R.string.unit_puff)}"
        binding.tvUsageLifetimeTotal.text = if (stats.totalPuffs > 0) "${stats.totalPuffs} ترا" else "—"

        val filterPills = mapOf(
            "TODAY" to binding.btnFilterToday,
            "WEEK" to binding.btnFilterWeek,
            "MONTH" to binding.btnFilterMonth,
            "YEAR" to binding.btnFilterYear
        )
        filterPills.forEach { (key, pill) -> styleFilterPill(pill, key == currentFilter) }

        when (currentFilter) {
            "TODAY" -> {
                binding.tvUsagePeriodLabel.text = "مصرف ثبت‌شده امروز"
                binding.tvUsagePeriodValue.text = "${stats.todayPuffs} ترا"
            }
            "WEEK" -> {
                binding.tvUsagePeriodLabel.text = "مجموع مصرف این هفته"
                binding.tvUsagePeriodValue.text = "${stats.weekPuffs} ترا"
            }
            "MONTH" -> {
                binding.tvUsagePeriodLabel.text = "مجموع مصرف این ماه"
                binding.tvUsagePeriodValue.text = "${stats.monthPuffs} ترا"
            }
            "YEAR" -> {
                binding.tvUsagePeriodLabel.text = "مجموع کل مصرف (سالیانه)"
                binding.tvUsagePeriodValue.text = "${stats.yearPuffs} ترا"
            }
        }

        val diff = stats.todayPuffs - stats.yesterdayPuffs
        binding.tvUsageBaselineText.text = when {
            diff < 0 -> getString(R.string.usage_less_than_yesterday, -diff)
            diff > 0 -> getString(R.string.usage_more_than_yesterday, diff)
            else -> getString(R.string.usage_same_as_yesterday)
        }
        binding.tvUsageTodayVsYesterday.text =
            getString(R.string.usage_today_vs_yesterday, stats.todayPuffs, stats.yesterdayPuffs)

        binding.tvSlotMorning.text = "${stats.timeSlots.morning} ترا"
        binding.tvSlotAfternoon.text = "${stats.timeSlots.afternoon} ترا"
        binding.tvSlotEvening.text = "${stats.timeSlots.evening} ترا"

        val insight = usageTracker.getPeakHourInsight()
        if (insight != null) {
            binding.tvSmartInsight.text = insight
            binding.tvSmartInsight.visibility = View.VISIBLE
        } else {
            binding.tvSmartInsight.visibility = View.GONE
        }

        renderWeeklyChart()
        renderTodaySessions()
    }

    private fun requestPermissionsAndConnect() {
        // Bluetooth permissions are the ONLY hard requirement to connect. Location is requested
        // alongside them (for OEM-skin compatibility and parity with the official IQOS app) but
        // must never block startConnection() - see AndroidManifest.xml comment for why.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scanGranted = checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val connectGranted = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (scanGranted && connectGranted) {
                connectionManager.startConnection()
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
                    PERMISSION_REQUEST_CODE
                )
            }
        } else {
            val locGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (locGranted) {
                connectionManager.startConnection()
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.BLUETOOTH,
                        Manifest.permission.BLUETOOTH_ADMIN,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                val address = pendingConnectAddress
                pendingConnectAddress = null
                if (address != null) {
                    connectionManager.connectToDevice(address)
                } else {
                    connectionManager.startConnection()
                }
            } else {
                pendingConnectAddress = null
                Toast.makeText(this, R.string.error_permissions_denied, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do NOT disconnect here: closing the Activity should not kill the BLE connection when
        // background sync is enabled - IqosBackgroundService (and any other future listener)
        // keeps using the same shared IqosConnectionManager singleton. Only stop listening for
        // UI callbacks, since `binding` is about to become invalid.
        connectionManager.removeListener(uiListener)
    }
}
