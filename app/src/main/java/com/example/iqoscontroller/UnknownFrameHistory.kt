package com.example.iqoscontroller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists what [FrameForensics] only keeps in memory for one app session. Every distinct
 * "we don't have a decoder for this" thing the device has ever sent - grouped by a stable key,
 * not by exact bytes, so the *same family* of frame is tracked across days even as its payload
 * changes - survives app restarts in a small JSON file. This is what makes a single "Copy full
 * diagnostic report" button useful even if it's tapped only once in a while: it doesn't just show
 * what happened in the last few minutes, it shows the accumulated picture across every session
 * since this feature was added.
 *
 * Two kinds of keys are recorded:
 *  - `"CHAR:<uuid>"` - a whole characteristic the app has no model for at all
 *    (e.g. `CHAR:77f38a30-2b2c-489a-be71-29e93a04a90a`).
 *  - `"SCP:<byte0> <kind> <header> <register>"` - a family of SCP-characteristic frames whose
 *    header the existing decoder doesn't recognise, grouped by their first 4 bytes so frames that
 *    only differ in their trailing payload (like a telemetry-shaped but undocumented reply) are
 *    tracked as one evolving family instead of dozens of unrelated one-off entries.
 *
 * Nothing here decodes or guesses meaning - it only remembers what was seen, when, and how often,
 * plus a bounded rolling window of the most recent distinct raw values for that key.
 */
class UnknownFrameHistory(context: Context) {

    private val prefs = context.getSharedPreferences("iqos_unknown_frame_history", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ENTRIES = "entries_json"
        private const val MAX_SAMPLES_PER_KEY = 12
    }

    data class Sample(val hex: String, val timestamp: Long)

    data class Entry(
        val key: String,
        val classification: String,
        var firstSeenAt: Long,
        var lastSeenAt: Long,
        var timesSeen: Int,
        val samples: MutableList<Sample>
    )

    @Synchronized
    private fun loadAll(): MutableMap<String, Entry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return mutableMapOf()
        return try {
            val obj = JSONObject(raw)
            val map = mutableMapOf<String, Entry>()
            obj.keys().forEach { key ->
                val o = obj.getJSONObject(key)
                val samplesArr = o.optJSONArray("samples") ?: JSONArray()
                val samples = (0 until samplesArr.length()).map {
                    val s = samplesArr.getJSONObject(it)
                    Sample(s.getString("hex"), s.getLong("ts"))
                }.toMutableList()
                map[key] = Entry(
                    key = key,
                    classification = o.optString("classification", "UNKNOWN"),
                    firstSeenAt = o.optLong("firstSeenAt"),
                    lastSeenAt = o.optLong("lastSeenAt"),
                    timesSeen = o.optInt("timesSeen", 0),
                    samples = samples
                )
            }
            map
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    @Synchronized
    private fun saveAll(map: Map<String, Entry>) {
        val obj = JSONObject()
        map.forEach { (key, entry) ->
            val o = JSONObject()
            o.put("classification", entry.classification)
            o.put("firstSeenAt", entry.firstSeenAt)
            o.put("lastSeenAt", entry.lastSeenAt)
            o.put("timesSeen", entry.timesSeen)
            val samplesArr = JSONArray()
            entry.samples.forEach { s ->
                val so = JSONObject()
                so.put("hex", s.hex)
                so.put("ts", s.timestamp)
                samplesArr.put(so)
            }
            o.put("samples", samplesArr)
            obj.put(key, o)
        }
        prefs.edit().putString(KEY_ENTRIES, obj.toString()).apply()
    }

    /** Records one observation. Safe to call often; never throws. */
    @Synchronized
    fun record(key: String, hex: String, classification: String) {
        try {
            val map = loadAll()
            val now = System.currentTimeMillis()
            val existing = map[key]
            if (existing == null) {
                map[key] = Entry(key, classification, now, now, 1, mutableListOf(Sample(hex, now)))
            } else {
                existing.lastSeenAt = now
                existing.timesSeen += 1
                if (existing.samples.isEmpty() || existing.samples.last().hex != hex) {
                    existing.samples.add(Sample(hex, now))
                    while (existing.samples.size > MAX_SAMPLES_PER_KEY) existing.samples.removeAt(0)
                }
            }
            saveAll(map)
        } catch (e: Exception) {
            AppLogger.e("UNKNOWN_HISTORY", "record() failed", e)
        }
    }

    fun getAll(): List<Entry> = loadAll().values.sortedByDescending { it.lastSeenAt }

    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    /**
     * Builds the human-readable section that gets appended to the full diagnostic export. Written
     * so it can be copy-pasted directly into a chat and understood without extra explanation.
     */
    fun buildReportSection(): String {
        val entries = getAll()
        if (entries.isEmpty()) {
            return "هنوز هیچ مشخصه یا فریم ناشناخته‌ای در طول این نشست‌ها ثبت نشده است."
        }
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.appendLine("تعداد خانواده‌های ناشناخته ثبت‌شده: ${entries.size}")
        sb.appendLine()
        entries.forEach { e ->
            sb.appendLine("### ${e.key}")
            sb.appendLine("طبقه‌بندی: ${e.classification}")
            sb.appendLine("اولین مشاهده: ${df.format(Date(e.firstSeenAt))}")
            sb.appendLine("آخرین مشاهده: ${df.format(Date(e.lastSeenAt))}")
            sb.appendLine("تعداد کل مشاهده: ${e.timesSeen}")
            sb.appendLine("نمونه مقادیر اخیر (تا ${MAX_SAMPLES_PER_KEY} مقدار متفاوت آخر):")
            e.samples.forEach { s ->
                sb.appendLine("  [${df.format(Date(s.timestamp))}]  ${s.hex}")
            }
            sb.appendLine()
        }
        return sb.toString()
    }
}
