package com.nromanovich.stealthsnake

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Lightweight JSON-backed store for recent downloads recorded by [DownloadHelper].
 */
class DownloadHistoryStore(context: Context) {

    data class Entry(
        val id: String,
        val fileName: String,
        val mimeType: String,
        val timestampMs: Long,
        val success: Boolean
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("fileName", fileName)
            put("mimeType", mimeType)
            put("timestampMs", timestampMs)
            put("success", success)
        }

        companion object {
            fun fromJson(obj: JSONObject): Entry? {
                val id = obj.optString("id", "").trim()
                val fileName = obj.optString("fileName", "").trim()
                if (id.isEmpty() || fileName.isEmpty()) return null
                return Entry(
                    id = id,
                    fileName = fileName,
                    mimeType = obj.optString("mimeType", "application/octet-stream"),
                    timestampMs = obj.optLong("timestampMs", 0L),
                    success = obj.optBoolean("success", true)
                )
            }
        }
    }

    private val historyFile = File(context.applicationContext.filesDir, HISTORY_FILE_NAME)

    fun record(fileName: String, mimeType: String, success: Boolean, timestampMs: Long = System.currentTimeMillis()): Entry =
        synchronized(historyLock) {
            val entry = Entry(
                id = UUID.randomUUID().toString(),
                fileName = fileName,
                mimeType = mimeType.ifBlank { "application/octet-stream" },
                timestampMs = timestampMs,
                success = success
            )
            val updated = (listOf(entry) + list()).take(MAX_ENTRIES)
            save(updated)
            entry
        }

    fun list(): List<Entry> = synchronized(historyLock) {
        if (!historyFile.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(historyFile.readText(Charsets.UTF_8))
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    Entry.fromJson(obj)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun delete(id: String): Boolean = synchronized(historyLock) {
        val current = list()
        val remaining = current.filterNot { it.id == id }
        if (remaining.size == current.size) return false
        save(remaining)
        true
    }

    fun clear() = synchronized(historyLock) {
        if (historyFile.exists()) {
            historyFile.delete()
        }
    }

    private fun save(entries: List<Entry>) {
        val arr = JSONArray()
        for (entry in entries) {
            arr.put(entry.toJson())
        }
        val payload = arr.toString()
        val parent = historyFile.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmpFile = File(parent, "$HISTORY_FILE_NAME.tmp")
        tmpFile.writeText(payload, Charsets.UTF_8)
        if (!tmpFile.renameTo(historyFile)) {
            historyFile.writeText(payload, Charsets.UTF_8)
            tmpFile.delete()
        }
    }

    companion object {
        private const val HISTORY_FILE_NAME = "downloads_history.json"
        private const val MAX_ENTRIES = 100
        private val historyLock = Any()
    }
}
