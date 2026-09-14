package com.example.iqoscontroller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class UsageTracker(private val context: Context) {

    private val prefs = context.getSharedPreferences("iqos_prefs", Context.MODE_PRIVATE)
    private val historyFile = File(context.filesDir, "daily_history.json")
    private val sessionsFile = File(context.filesDir, "sessions_history.json")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

    data class TimeSlotUsage(
        val morning: Int,   // 05:00 - 12:00
        val afternoon: Int, // 12:00 - 18:00
        val evening: Int    // 18:00 - 00:00
    )

    data class UsageStats(
        val todayPuffs: Int,
        val yesterdayPuffs: Int,
        val weekPuffs: Int,
        val monthPuffs: Int,
        val yearPuffs: Int,
        val totalPuffs: Int,
        val lastUsedTime: String,
        val timeSlots: TimeSlotUsage,
        val userBaselineAvg: Float,
        val consumptionStatus: String // "NORMAL", "HIGH", "LOW"
    )

    @Synchronized
    fun recordReading(currentTotal: Int): UsageStats {
        val todayCalendar = Calendar.getInstance()
        val todayKey = dateFormat.format(todayCalendar.time)
        val nowTimeStr = timeFormat.format(todayCalendar.time)

        val lastStoredTotal = prefs.getInt("last_total_puffs", -1)
        prefs.edit().putInt("last_total_puffs", currentTotal).putString("last_used_time", nowTimeStr).apply()

        val historyJson = loadHistoryJson()
        val startKey = "${todayKey}_start"
        val countKey = "${todayKey}_puffs"

        var todayStartTotal = historyJson.optInt(startKey, -1)
        if (todayStartTotal == -1) {
            todayStartTotal = currentTotal
            historyJson.put(startKey, todayStartTotal)
        }

        val todayPuffs = (currentTotal - todayStartTotal).coerceAtLeast(0)
        historyJson.put(countKey, todayPuffs)
        saveHistoryJson(historyJson)

        // If total increased from previous stored total, record a real session event
        if (lastStoredTotal != -1 && currentTotal > lastStoredTotal) {
            val delta = currentTotal - lastStoredTotal
            recordSessionEvent(System.currentTimeMillis(), delta)
            // Trigger smart local notification
            NotificationHelper.checkAndNotifyUsage(context, todayPuffs, getHistoricalAverage(historyJson, todayKey))
        }

        return getStats()
    }

    @Synchronized
    private fun recordSessionEvent(timestamp: Long, count: Int) {
        try {
            val arr = if (sessionsFile.exists()) JSONArray(sessionsFile.readText()) else JSONArray()
            val obj = JSONObject().apply {
                put("timestamp", timestamp)
                put("count", count)
            }
            arr.put(obj)
            sessionsFile.writeText(arr.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun getStats(): UsageStats {
        val currentTotal = prefs.getInt("last_total_puffs", 0)
        val lastTime = prefs.getString("last_used_time", "--:--") ?: "--:--"
        val todayCalendar = Calendar.getInstance()
        val todayKey = dateFormat.format(todayCalendar.time)
        val historyJson = loadHistoryJson()

        val todayPuffs = historyJson.optInt("${todayKey}_puffs", 0)

        // Calculate time slots from today sessions
        val slots = computeTimeSlots()

        // 1. Yesterday
        val yesterdayCal = (todayCalendar.clone() as Calendar).apply { add(Calendar.DATE, -1) }
        val yesterdayKey = dateFormat.format(yesterdayCal.time)
        val yesterdayPuffs = historyJson.optInt("${yesterdayKey}_puffs", 0)

        // 2. This Week (Monday to today)
        val weekCal = (todayCalendar.clone() as Calendar).apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
        var weeklySum = 0
        val nowCal = (todayCalendar.clone() as Calendar)
        while (!weekCal.after(nowCal)) {
            val dayStr = dateFormat.format(weekCal.time)
            val dayCount = if (dayStr == todayKey) todayPuffs else historyJson.optInt("${dayStr}_puffs", 0)
            weeklySum += dayCount
            weekCal.add(Calendar.DATE, 1)
        }

        // 3. This Month
        val monthCal = (todayCalendar.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
        var monthlySum = 0
        while (!monthCal.after(nowCal)) {
            val dayStr = dateFormat.format(monthCal.time)
            val dayCount = if (dayStr == todayKey) todayPuffs else historyJson.optInt("${dayStr}_puffs", 0)
            monthlySum += dayCount
            monthCal.add(Calendar.DATE, 1)
        }

        // 4. This Year
        val yearCal = (todayCalendar.clone() as Calendar).apply {
            set(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
        var yearlySum = 0
        while (!yearCal.after(nowCal)) {
            val dayStr = dateFormat.format(yearCal.time)
            val dayCount = if (dayStr == todayKey) todayPuffs else historyJson.optInt("${dayStr}_puffs", 0)
            yearlySum += dayCount
            yearCal.add(Calendar.DATE, 1)
        }

        val baseline = getHistoricalAverage(historyJson, todayKey)
        val status = when {
            baseline <= 0f -> "NORMAL"
            todayPuffs > baseline * 1.3f -> "HIGH"
            todayPuffs < baseline * 0.7f && todayPuffs > 0 -> "LOW"
            else -> "NORMAL"
        }

        return UsageStats(
            todayPuffs = todayPuffs,
            yesterdayPuffs = yesterdayPuffs,
            weekPuffs = weeklySum,
            monthPuffs = monthlySum,
            yearPuffs = yearlySum,
            totalPuffs = currentTotal,
            lastUsedTime = lastTime,
            timeSlots = slots,
            userBaselineAvg = baseline,
            consumptionStatus = status
        )
    }

    data class DailyBar(val labelFa: String, val count: Int, val isToday: Boolean, val dateKey: String)

    /** Last 7 calendar days (oldest first) for the "روند مصرف هفته" bar chart on the Usage tab. */
    @Synchronized
    fun getLast7DaysTrend(): List<DailyBar> {
        val dayNamesFa = arrayOf("یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه", "شنبه")
        val historyJson = loadHistoryJson()
        val today = Calendar.getInstance()
        val todayKey = dateFormat.format(today.time)
        val cursor = (today.clone() as Calendar).apply { add(Calendar.DATE, -6) }

        val result = mutableListOf<DailyBar>()
        repeat(7) {
            val key = dateFormat.format(cursor.time)
            val dow = cursor.get(Calendar.DAY_OF_WEEK) // 1=Sunday .. 7=Saturday
            val count = historyJson.optInt("${key}_puffs", 0)
            result.add(DailyBar(dayNamesFa[dow - 1], count, key == todayKey, key))
            cursor.add(Calendar.DATE, 1)
        }
        return result
    }

    data class HourCount(val hour: Int, val count: Int)

    /**
     * Hour-by-hour breakdown (0-23, all hours included even at zero) of puffs recorded on one
     * specific calendar day, read from the real per-puff timestamps in [sessionsFile]. Powers the
     * "tap a day in the weekly chart" table - every number here comes from an actual recorded
     * puff event on that date, nothing estimated or interpolated.
     */
    @Synchronized
    fun getHourlyBreakdownForDate(dateKey: String): List<HourCount> {
        val byHour = IntArray(24)
        if (sessionsFile.exists()) {
            try {
                val arr = JSONArray(sessionsFile.readText())
                val cal = Calendar.getInstance()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val ts = obj.getLong("timestamp")
                    cal.timeInMillis = ts
                    if (dateFormat.format(cal.time) == dateKey) {
                        val hour = cal.get(Calendar.HOUR_OF_DAY)
                        byHour[hour] += obj.optInt("count", 1)
                    }
                }
            } catch (e: Exception) {
                // leave byHour as zeros
            }
        }
        return (0..23).map { HourCount(it, byHour[it]) }
    }

    data class TodaySession(val timeStr: String, val sequentialCount: Int)

    /** Individual puff events recorded today, newest first, numbered by running daily total. */
    @Synchronized
    fun getTodaySessions(): List<TodaySession> {
        if (!sessionsFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(sessionsFile.readText())
            val todayStart = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val todays = (0 until arr.length())
                .map { arr.getJSONObject(it) }
                .filter { it.getLong("timestamp") >= todayStart }
                .sortedBy { it.getLong("timestamp") }

            var running = 0
            val result = mutableListOf<TodaySession>()
            for (obj in todays) {
                running += obj.optInt("count", 1)
                result.add(TodaySession(timeFormat.format(Date(obj.getLong("timestamp"))), running))
            }
            result.reversed()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Simple, honest pattern analysis - NOT a machine-learning model, just arithmetic over the
     * real recorded puff timestamps in [sessionsFile]. Finds which hour-of-day has historically
     * carried the most puffs and returns a one-line Farsi sentence about it, or null if there
     * isn't enough history yet to say anything meaningful (fewer than 10 recorded puffs).
     */
    @Synchronized
    fun getPeakHourInsight(): String? {
        if (!sessionsFile.exists()) return null
        return try {
            val arr = JSONArray(sessionsFile.readText())
            if (arr.length() == 0) return null

            val byHour = IntArray(24)
            var totalCounted = 0
            val cal = Calendar.getInstance()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                cal.timeInMillis = obj.getLong("timestamp")
                val hour = cal.get(Calendar.HOUR_OF_DAY)
                val count = obj.optInt("count", 1)
                byHour[hour] += count
                totalCounted += count
            }
            if (totalCounted < 10) return null

            val peakHour = byHour.indices.maxByOrNull { byHour[it] } ?: return null
            val peakCount = byHour[peakHour]
            if (peakCount <= 0) return null
            val share = (peakCount * 100f / totalCounted).toInt()
            val rangeEnd = (peakHour + 1) % 24
            "بر اساس تاریخچه، ساعت %02d:00 تا %02d:00 پرمصرف‌ترین بازه شماست (%d%% از کل مصرف ثبت‌شده)"
                .format(peakHour, rangeEnd, share)
        } catch (e: Exception) {
            null
        }
    }

    private fun computeTimeSlots(): TimeSlotUsage {
        var m = 0
        var a = 0
        var e = 0
        if (!sessionsFile.exists()) return TimeSlotUsage(0, 0, 0)

        try {
            val arr = JSONArray(sessionsFile.readText())
            val cal = Calendar.getInstance()
            val todayStart = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }.timeInMillis

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val ts = obj.getLong("timestamp")
                if (ts >= todayStart) {
                    cal.timeInMillis = ts
                    val hour = cal.get(Calendar.HOUR_OF_DAY)
                    val count = obj.optInt("count", 1)
                    when (hour) {
                        in 5..11 -> m += count
                        in 12..17 -> a += count
                        in 18..23 -> e += count
                        else -> e += count
                    }
                }
            }
        } catch (_: Exception) {}
        return TimeSlotUsage(m, a, e)
    }

    private fun getHistoricalAverage(historyJson: JSONObject, todayKey: String): Float {
        var total = 0
        var days = 0
        val keys = historyJson.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.endsWith("_puffs") && !k.startsWith(todayKey)) {
                total += historyJson.optInt(k, 0)
                days++
            }
        }
        return if (days > 0) total.toFloat() / days else 0f
    }

    @Synchronized
    fun resetHistory() {
        prefs.edit().remove("last_total_puffs").remove("last_used_time").apply()
        if (historyFile.exists()) historyFile.delete()
        if (sessionsFile.exists()) sessionsFile.delete()
    }

    fun exportBackupJson(): String {
        val root = JSONObject()
        root.put("version", "1.3.3")
        root.put("timestamp", System.currentTimeMillis())
        root.put("history", loadHistoryJson())
        if (sessionsFile.exists()) {
            root.put("sessions", JSONArray(sessionsFile.readText()))
        }
        val pObj = JSONObject()
        prefs.all.forEach { (k, v) -> pObj.put(k, v) }
        root.put("preferences", pObj)
        return root.toString(2)
    }

    fun restoreBackupJson(jsonStr: String): Boolean {
        return try {
            val root = JSONObject(jsonStr)
            if (root.has("history")) {
                saveHistoryJson(root.getJSONObject("history"))
            }
            if (root.has("sessions")) {
                sessionsFile.writeText(root.getJSONArray("sessions").toString())
            }
            if (root.has("preferences")) {
                val p = root.getJSONObject("preferences")
                val edit = prefs.edit()
                val keys = p.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    when (val v = p.get(k)) {
                        is Int -> edit.putInt(k, v)
                        is Boolean -> edit.putBoolean(k, v)
                        is String -> edit.putString(k, v)
                        is Long -> edit.putLong(k, v)
                        is Float -> edit.putFloat(k, v)
                    }
                }
                edit.apply()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun loadHistoryJson(): JSONObject {
        return if (historyFile.exists()) {
            try { JSONObject(historyFile.readText()) } catch (_: Exception) { JSONObject() }
        } else JSONObject()
    }

    private fun saveHistoryJson(json: JSONObject) {
        try { historyFile.writeText(json.toString()) } catch (e: Exception) { e.printStackTrace() }
    }
}
