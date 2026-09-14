package com.example.iqoscontroller

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Passive protocol forensics recorder.
 *
 * This object does NOT decode, interpret, or guess anything about frame contents. It only
 * observes what already flows through [BleIqosTransport] (both directions) and records it with
 * enough context to analyse patterns later - timestamp, characteristic UUID, direction, raw hex,
 * length, how many times that exact (uuid, hex) pair has been seen, how long since the previous
 * frame, and what the app itself was doing at that moment (connection lifecycle state + the most
 * recent outgoing command, if any, within a short window).
 *
 * SAFETY: this is strictly a read/observe layer. It never sends anything, never changes any
 * existing decode path's return value, and never blocks or delays the real BLE calls it sits
 * next to - [record] is called *after* the real work already happened, exactly like [AppLogger].
 *
 * All statistics/reports here are only ever computed from what was actually captured on a real
 * connected device. If nothing was captured, reports say so - they never fabricate figures.
 */
object FrameForensics {

    data class FrameRecord(
        val seq: Long,
        val timestamp: Long,
        val uuid: String,
        val direction: String,       // "RX" or "TX"
        val hex: String,
        val length: Int,
        val appState: String,        // DeviceState at time of capture
        val commandContext: String?, // most recent outgoing command tag, if sent recently
        val msSincePreviousGlobal: Long?,
        val msSincePreviousSameUuid: Long?,
        val occurrenceForThisUuidHex: Int,
        var classification: String = "UNCLASSIFIED"
    )

    private const val MAX_RECORDS = 4000
    private const val COMMAND_CONTEXT_WINDOW_MS = 4000L

    private val records = CopyOnWriteArrayList<FrameRecord>()
    private val seqCounter = java.util.concurrent.atomic.AtomicLong(0)
    private val lastTimestampGlobal = java.util.concurrent.atomic.AtomicLong(-1)
    private val lastTimestampByUuid = ConcurrentHashMap<String, Long>()
    private val occurrenceCountByUuidHex = ConcurrentHashMap<String, Int>()
    private val classificationByUuidHex = ConcurrentHashMap<String, String>()

    @Volatile var currentAppState: String = "UNKNOWN"

    @Volatile private var lastCommandTag: String? = null
    @Volatile private var lastCommandAt: Long = 0L

    /** Call right before/around sending a known command so RX frames shortly after can be
     * correlated with "what were we asking for". Never used to decide protocol behaviour. */
    fun noteCommandSent(tag: String) {
        lastCommandTag = tag
        lastCommandAt = System.currentTimeMillis()
    }

    /** Records one observed frame. Safe to call from any thread; never throws. */
    fun record(direction: String, uuid: String, bytes: ByteArray) {
        try {
            val now = System.currentTimeMillis()
            val hex = IqosProtocol.toHexString(bytes)
            val key = "$uuid|$hex"

            val occurrence = occurrenceCountByUuidHex.merge(key, 1) { a, b -> a + b } ?: 1

            val prevGlobal = lastTimestampGlobal.getAndSet(now)
            val deltaGlobal = if (prevGlobal >= 0) now - prevGlobal else null

            val prevUuid = lastTimestampByUuid.put(uuid, now)
            val deltaUuid = prevUuid?.let { now - it }

            val cmdContext = lastCommandTag?.takeIf { now - lastCommandAt <= COMMAND_CONTEXT_WINDOW_MS }

            val record = FrameRecord(
                seq = seqCounter.incrementAndGet(),
                timestamp = now,
                uuid = uuid,
                direction = direction,
                hex = hex,
                length = bytes.size,
                appState = currentAppState,
                commandContext = cmdContext,
                msSincePreviousGlobal = deltaGlobal,
                msSincePreviousSameUuid = deltaUuid,
                occurrenceForThisUuidHex = occurrence
            )

            classificationByUuidHex[key]?.let { record.classification = it }

            records.add(record)
            while (records.size > MAX_RECORDS) {
                records.removeAt(0)
            }
        } catch (e: Exception) {
            AppLogger.e("FORENSICS", "record() failed", e)
        }
    }

    /**
     * Attaches a classification to every past AND future record matching this exact (uuid, hex).
     * Classification is derived directly from which decoder path actually ran - never guessed.
     * Typical values: "Firmware", "Telemetry", "BatteryVoltage", "Unknown:SCP_DECODER",
     * "Unknown:SCP_DECODER_MISROUTED", "DEVICE_INFO:<label>".
     */
    fun classify(uuid: String, hex: String, label: String) {
        val key = "$uuid|$hex"
        classificationByUuidHex[key] = label
        // Backfill any already-captured records with the same key so a JSON/text export taken
        // right after connecting is consistent even if classification arrived a moment later.
        for (r in records) {
            if (r.uuid == uuid && r.hex == hex) r.classification = label
        }
    }

    fun snapshot(): List<FrameRecord> = records.toList()

    fun clear() {
        records.clear()
        occurrenceCountByUuidHex.clear()
        classificationByUuidHex.clear()
        lastTimestampByUuid.clear()
        lastTimestampGlobal.set(-1)
        seqCounter.set(0)
    }

    // ---------------------------------------------------------------------------------------
    // Reporting (only ever describes what was actually captured - never invents data)
    // ---------------------------------------------------------------------------------------

    data class UnknownGroup(
        val uuid: String,
        val hex: String,
        val length: Int,
        val count: Int,
        val classification: String,
        val firstSeen: Long,
        val lastSeen: Long
    )

    /** Groups every captured frame whose classification starts with "Unknown" by (uuid, hex). */
    fun unknownGroups(): List<UnknownGroup> {
        val snap = records.filter { it.classification.startsWith("Unknown") }
        val grouped = snap.groupBy { it.uuid to it.hex }
        return grouped.map { (key, list) ->
            UnknownGroup(
                uuid = key.first,
                hex = key.second,
                length = list.first().length,
                count = list.size,
                classification = list.first().classification,
                firstSeen = list.minOf { it.timestamp },
                lastSeen = list.maxOf { it.timestamp }
            )
        }.sortedByDescending { it.count }
    }

    fun totalCaptured(): Int = records.size
    fun totalRx(): Int = records.count { it.direction == "RX" }
    fun totalTx(): Int = records.count { it.direction == "TX" }
    fun totalUnknown(): Int = records.count { it.classification.startsWith("Unknown") }
    fun totalDecoded(): Int = totalRx() - totalUnknown()

    fun distinctUuids(): List<String> = records.map { it.uuid }.distinct()

    /** Short human-readable summary for on-screen display (no file I/O). */
    fun summaryText(): String {
        if (records.isEmpty()) {
            return "هنوز هیچ فریمی ثبت نشده است.\n" +
                "این لایه صرفاً مشاهده‌گر است: تا وقتی دستگاه واقعی متصل نشود و فریم رد و بدل نشود، هیچ داده‌ای برای تحلیل وجود ندارد."
        }
        val sb = StringBuilder()
        sb.appendLine("کل فریم‌های ثبت‌شده (این نشست): ${totalCaptured()}")
        sb.appendLine("  RX: ${totalRx()}   TX: ${totalTx()}")
        sb.appendLine("  ناشناخته: ${totalUnknown()}   شناسایی‌شده: ${totalDecoded()}")
        sb.appendLine("  UUIDهای مشاهده‌شده: ${distinctUuids().size}")
        sb.appendLine()
        val groups = unknownGroups()
        if (groups.isEmpty()) {
            sb.appendLine("هیچ فریم ناشناخته‌ای در این نشست ثبت نشده است.")
        } else {
            sb.appendLine("گروه‌های فریم ناشناخته (بر اساس UUID + محتوای هگزادسیمال یکسان):")
            groups.take(25).forEach { g ->
                sb.appendLine("  UUID ${g.uuid}")
                sb.appendLine("    HEX: ${g.hex}  (طول ${g.length})  تعداد تکرار: ${g.count}  طبقه‌بندی: ${g.classification}")
            }
            if (groups.size > 25) sb.appendLine("  … و ${groups.size - 25} گروه دیگر (در فایل JSON کامل موجود است)")
        }
        return sb.toString()
    }

    /** Full structured JSON export, including prev/next neighbours computed at export time. */
    fun exportAsJson(): String {
        val list = records
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"generatedAt\": ${System.currentTimeMillis()},\n")
        sb.append("  \"totalCaptured\": ${list.size},\n")
        sb.append("  \"totalRx\": ${totalRx()},\n")
        sb.append("  \"totalTx\": ${totalTx()},\n")
        sb.append("  \"totalUnknown\": ${totalUnknown()},\n")
        sb.append("  \"frames\": [\n")
        list.forEachIndexed { index, r ->
            val prev = list.getOrNull(index - 1)
            val next = list.getOrNull(index + 1)
            sb.append("    {\n")
            sb.append("      \"seq\": ${r.seq},\n")
            sb.append("      \"timestamp\": ${r.timestamp},\n")
            sb.append("      \"uuid\": \"${r.uuid}\",\n")
            sb.append("      \"direction\": \"${r.direction}\",\n")
            sb.append("      \"hex\": \"${r.hex}\",\n")
            sb.append("      \"length\": ${r.length},\n")
            sb.append("      \"appState\": \"${r.appState}\",\n")
            sb.append("      \"commandContext\": ${r.commandContext?.let { "\"$it\"" } ?: "null"},\n")
            sb.append("      \"classification\": \"${r.classification}\",\n")
            sb.append("      \"occurrenceForThisUuidHex\": ${r.occurrenceForThisUuidHex},\n")
            sb.append("      \"msSincePreviousGlobal\": ${r.msSincePreviousGlobal ?: "null"},\n")
            sb.append("      \"msSincePreviousSameUuid\": ${r.msSincePreviousSameUuid ?: "null"},\n")
            sb.append("      \"previousFrameHex\": ${prev?.let { "\"${it.hex}\"" } ?: "null"},\n")
            sb.append("      \"nextFrameHex\": ${next?.let { "\"${it.hex}\"" } ?: "null"}\n")
            sb.append("    }")
            sb.append(if (index < list.size - 1) ",\n" else "\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    /**
     * Writes the JSON export to app-private external storage (no permission required) and
     * returns the absolute path, or null on failure. Read-only diagnostic export - never touches
     * the device, never requires any permission beyond normal app storage.
     */
    fun exportToFile(context: Context): String? {
        return try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "iqos_frame_forensics_$stamp.json")
            file.writeText(exportAsJson())
            AppLogger.i("FORENSICS", "Exported ${records.size} frames to ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            AppLogger.e("FORENSICS", "exportToFile() failed", e)
            null
        }
    }
}
