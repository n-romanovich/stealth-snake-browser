package com.nromanovich.stealthsnake

import android.content.Context
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Manages offline-saved MHTML web archives inside the app-private sandbox (`filesDir/offline_pages`).
 */
class OfflinePagesManager(context: Context) {

    data class SavedPage(
        val id: String,
        val title: String,
        val url: String,
        val fileName: String,
        val savedAtMs: Long,
        val sizeBytes: Long
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("title", title)
            put("url", url)
            put("fileName", fileName)
            put("savedAtMs", savedAtMs)
            put("sizeBytes", sizeBytes)
        }

        companion object {
            fun fromJson(obj: JSONObject): SavedPage? {
                val id = obj.optString("id", "").trim()
                val fileName = obj.optString("fileName", "").trim()
                if (id.isEmpty() || fileName.isEmpty()) return null
                return SavedPage(
                    id = id,
                    title = obj.optString("title", "").ifBlank { obj.optString("url", fileName) },
                    url = obj.optString("url", ""),
                    fileName = fileName,
                    savedAtMs = obj.optLong("savedAtMs", 0L),
                    sizeBytes = obj.optLong("sizeBytes", 0L)
                )
            }
        }
    }

    private val pagesDir = File(context.applicationContext.filesDir, PAGES_DIR_NAME)
    private val indexFile = File(pagesDir, INDEX_FILE_NAME)

    fun saveCurrentPage(
        webView: WebView,
        title: String?,
        url: String?,
        onComplete: (Result<SavedPage>) -> Unit
    ) {
        val effectiveUrl = url?.trim().orEmpty()
        if (effectiveUrl.isEmpty() || effectiveUrl == MainActivity.DEFAULT_START_URL) {
            onComplete(Result.failure(IllegalStateException("Nothing to save")))
            return
        }
        if (!pagesDir.exists() && !pagesDir.mkdirs()) {
            onComplete(Result.failure(IllegalStateException("Failed to create offline pages directory")))
            return
        }
        val id = UUID.randomUUID().toString()
        val safeTitle = (title?.trim().takeIf { !it.isNullOrEmpty() } ?: effectiveUrl).take(80)
        val fileName = "page_${System.currentTimeMillis()}_${id.take(8)}.mht"
        val destFile = File(pagesDir, fileName)

        webView.saveWebArchive(destFile.absolutePath, false) { savedPath ->
            if (savedPath == null || !destFile.isFile || destFile.length() <= 0L) {
                destFile.delete()
                onComplete(Result.failure(IllegalStateException("WebView.saveWebArchive failed")))
            } else {
                val entry = SavedPage(
                    id = id,
                    title = safeTitle,
                    url = effectiveUrl,
                    fileName = fileName,
                    savedAtMs = System.currentTimeMillis(),
                    sizeBytes = destFile.length()
                )
                addToIndex(entry)
                onComplete(Result.success(entry))
            }
        }
    }

    @Synchronized
    fun registerSavedFile(title: String, url: String, file: File): SavedPage? {
        if (!isInsidePagesDir(file) || !file.isFile) return null
        val entry = SavedPage(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { url }.take(80),
            url = url,
            fileName = file.name,
            savedAtMs = System.currentTimeMillis(),
            sizeBytes = file.length()
        )
        addToIndex(entry)
        return entry
    }

    fun listPages(): List<SavedPage> = synchronized(indexLock) {
        if (!indexFile.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText(Charsets.UTF_8))
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val page = SavedPage.fromJson(obj) ?: continue
                    val f = resolvePageFile(page.fileName)
                    if (f != null && f.isFile) {
                        add(page)
                    }
                }
            }.sortedByDescending { it.savedAtMs }
        }.getOrDefault(emptyList())
    }

    fun resolvePageFile(fileName: String): File? {
        if (fileName.isBlank() || fileName.contains('/') || fileName.contains('\\') || fileName.contains("..")) {
            return null
        }
        val candidate = File(pagesDir, fileName)
        return if (isInsidePagesDir(candidate)) candidate else null
    }

    fun deletePage(id: String): Boolean = synchronized(indexLock) {
        val current = listPages()
        val target = current.firstOrNull { it.id == id } ?: return false
        resolvePageFile(target.fileName)?.delete()
        saveIndex(current.filterNot { it.id == id })
        true
    }

    fun clearAll(): Int = synchronized(indexLock) {
        val current = listPages()
        var count = 0
        for (page in current) {
            if (resolvePageFile(page.fileName)?.delete() == true) {
                count++
            }
        }
        pagesDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name != INDEX_FILE_NAME && file.delete()) {
                count++
            }
        }
        if (indexFile.exists()) indexFile.delete()
        count
    }

    private fun isInsidePagesDir(file: File): Boolean =
        runCatching {
            file.canonicalFile.parentFile?.canonicalPath == pagesDir.canonicalPath
        }.getOrDefault(false)

    private fun addToIndex(entry: SavedPage) = synchronized(indexLock) {
        val updated = listOf(entry) + listPages()
        saveIndex(updated)
    }

    private fun saveIndex(entries: List<SavedPage>) {
        if (!pagesDir.exists()) pagesDir.mkdirs()
        val arr = JSONArray()
        for (e in entries) {
            arr.put(e.toJson())
        }
        val payload = arr.toString()
        val tmpFile = File(pagesDir, "$INDEX_FILE_NAME.tmp")
        tmpFile.writeText(payload, Charsets.UTF_8)
        if (!tmpFile.renameTo(indexFile)) {
            indexFile.writeText(payload, Charsets.UTF_8)
            tmpFile.delete()
        }
    }

    companion object {
        const val PAGES_DIR_NAME = "offline_pages"
        private const val INDEX_FILE_NAME = "index.json"
        private val indexLock = Any()
    }
}
