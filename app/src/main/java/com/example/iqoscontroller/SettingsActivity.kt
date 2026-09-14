package com.example.iqoscontroller

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.example.iqoscontroller.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_settings)

        val prefs = getSharedPreferences("iqos_app_settings", Context.MODE_PRIVATE)

        // Dark Mode preference
        val isNight = prefs.getBoolean("dark_mode", false)
        binding.swDarkMode.isChecked = isNight
        binding.swDarkMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("dark_mode", isChecked).apply()
            if (isChecked) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }

        // Auto Connect preference
        val autoConnect = prefs.getBoolean("auto_connect", false)
        binding.swAutoConnect.isChecked = autoConnect
        binding.swAutoConnect.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_connect", isChecked).apply()
        }

        // Reset History
        binding.btnResetHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.pref_reset_usage)
                .setMessage(R.string.pref_reset_confirm)
                .setPositiveButton(R.string.dialog_yes) { _, _ ->
                    UsageTracker(this).resetHistory()
                    Toast.makeText(this, R.string.history_reset_success, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.dialog_no, null)
                .show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
