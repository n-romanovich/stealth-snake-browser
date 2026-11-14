package com.nromanovich.stealthsnake

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Hardware-backed (`AndroidKeyStore`) AES-256-GCM store for persisted tabs (when
 * [SnakePreferences.autoWipeOnExit] is disabled) and private bookmarks.
 *
 * When `autoWipeOnExit` is enabled, tab persistence is strictly refused and any existing
 * encrypted tab snapshot is deleted immediately. Calling [cryptoEraseAll] deletes both
 * encrypted payloads and destroys the KeyStore key material.
 */
class EncryptedSessionStore(context: Context) {

    data class SavedTab(
        val id: Long,
        val url: String,
        val title: String,
        val sessionSeed: String,
        val presetOverride: String? = null,
        val profileIdOverride: String? = null
    )

    data class SavedSessionState(
        val tabs: List<SavedTab>,
        val activeIndex: Int
    )

    data class Bookmark(
        val url: String,
        val title: String,
        val timestampMs: Long
    )

    private val storeDir = File(context.filesDir, STORE_DIR_NAME)
    private val tabsFile = File(storeDir, TABS_FILE_NAME)
    private val bookmarksFile = File(storeDir, BOOKMARKS_FILE_NAME)
    private val random = SecureRandom()

    fun saveTabs(tabs: List<SavedTab>, activeIndex: Int, autoWipeOnExit: Boolean) {
        if (autoWipeOnExit) {
            clearTabs()
            return
        }
        val filtered = tabs.mapNotNull { tab ->
            val sanitizedUrl = sanitizeTabUrl(tab.url) ?: return@mapNotNull null
            tab.copy(
                url = sanitizedUrl,
                title = tab.title.take(MAX_TITLE_CHARS),
                sessionSeed = tab.sessionSeed.ifBlank { SnakeScript.generateSessionSeed() }
            )
        }
        if (filtered.isEmpty()) {
            clearTabs()
            return
        }
        val arr = JSONArray()
        for (tab in filtered) {
            arr.put(JSONObject().apply {
                put("id", tab.id)
                put("url", tab.url)
                put("title", tab.title)
                put("sessionSeed", tab.sessionSeed)
                tab.presetOverride?.takeIf { it.isNotBlank() }?.let { put("presetOverride", it) }
                tab.profileIdOverride?.takeIf { it.isNotBlank() }?.let { put("profileIdOverride", it) }
            })
        }
        val root = JSONObject().apply {
            put("activeIndex", activeIndex.coerceIn(0, filtered.lastIndex))
            put("tabs", arr)
        }
        writeEncryptedJson(tabsFile, root.toString())
    }

    fun loadTabs(autoWipeOnExit: Boolean): SavedSessionState? {
        if (autoWipeOnExit) {
            clearTabs()
            return null
        }
        val raw = readEncryptedJson(tabsFile) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("tabs") ?: return@runCatching null
            val loaded = buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val url = sanitizeTabUrl(item.optString("url", "")) ?: continue
                    val id = item.optLong("id", (i + 1).toLong()).coerceAtLeast(1L)
                    val seed = item.optString("sessionSeed", "").ifBlank { SnakeScript.generateSessionSeed() }
                    val preset = item.optString("presetOverride", "").takeIf { it.isNotBlank() }
                    val profile = item.optString("profileIdOverride", "").takeIf { it.isNotBlank() }
                    add(
                        SavedTab(
                            id = id,
                            url = url,
                            title = item.optString("title", "").take(MAX_TITLE_CHARS),
                            sessionSeed = seed,
                            presetOverride = preset,
                            profileIdOverride = profile
                        )
                    )
                }
            }
            if (loaded.isEmpty()) {
                clearTabs()
                return@runCatching null
            }
            val active = obj.optInt("activeIndex", 0).coerceIn(0, loaded.lastIndex)
            SavedSessionState(tabs = loaded, activeIndex = active)
        }.onFailure {
            clearTabs()
        }.getOrNull()
    }

    fun clearTabs() {
        runCatching {
            tabsFile.delete()
            File(storeDir, "$TABS_FILE_NAME.tmp").delete()
        }
    }

    fun listBookmarks(): List<Bookmark> {
        val raw = readEncryptedJson(bookmarksFile) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val url = sanitizeHttpBookmarkUrl(item.optString("url", "")) ?: continue
                    val title = item.optString("title", "").trim().ifEmpty { url }.take(MAX_TITLE_CHARS)
                    val ts = item.optLong("ts", 0L)
                    add(Bookmark(url = url, title = title, timestampMs = ts))
                }
            }
        }.onFailure {
            clearBookmarks()
        }.getOrDefault(emptyList())
    }

    fun addBookmark(url: String, title: String): List<Bookmark> {
        val safeUrl = sanitizeHttpBookmarkUrl(url) ?: return listBookmarks()
        val existing = listBookmarks().filterNot { it.url == safeUrl }.toMutableList()
        val displayTitle = title.trim().ifEmpty {
            runCatching { Uri.parse(safeUrl).host }.getOrNull().orEmpty().ifEmpty { safeUrl }
        }.take(MAX_TITLE_CHARS)
        existing.add(0, Bookmark(url = safeUrl, title = displayTitle, timestampMs = System.currentTimeMillis()))
        val trimmed = existing.take(MAX_BOOKMARKS)
        writeBookmarks(trimmed)
        return trimmed
    }

    fun removeBookmark(url: String): List<Bookmark> {
        val trimmed = listBookmarks().filterNot { it.url == url }
        if (trimmed.isEmpty()) {
            clearBookmarks()
        } else {
            writeBookmarks(trimmed)
        }
        return trimmed
    }

    fun isBookmarked(url: String): Boolean {
        val safeUrl = sanitizeHttpBookmarkUrl(url) ?: return false
        return listBookmarks().any { it.url == safeUrl }
    }

    fun clearBookmarks() {
        runCatching {
            bookmarksFile.delete()
            File(storeDir, "$BOOKMARKS_FILE_NAME.tmp").delete()
        }
    }

    fun cryptoEraseAll() {
        clearTabs()
        clearBookmarks()
        synchronized(keyLock) {
            fallbackKeyBytes?.fill(0)
            fallbackKeyBytes = null
        }
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) {
                ks.deleteEntry(KEY_ALIAS)
            }
        }
        runCatching {
            storeDir.listFiles()?.forEach { it.delete() }
            storeDir.delete()
        }
    }

    private fun writeBookmarks(bookmarks: List<Bookmark>) {
        val arr = JSONArray()
        for (b in bookmarks) {
            arr.put(JSONObject().apply {
                put("url", b.url)
                put("title", b.title)
                put("ts", b.timestampMs)
            })
        }
        writeEncryptedJson(bookmarksFile, arr.toString())
    }

    private fun writeEncryptedJson(target: File, json: String) {
        synchronized(keyLock) {
            if (!storeDir.exists()) {
                storeDir.mkdirs()
            }
            val key = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(json.toByteArray(Charsets.UTF_8))

            val payload = ByteArray(1 + iv.size + cipherBytes.size)
            payload[0] = iv.size.toByte()
            System.arraycopy(iv, 0, payload, 1, iv.size)
            System.arraycopy(cipherBytes, 0, payload, 1 + iv.size, cipherBytes.size)

            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeBytes(payload)
            if (!tmp.renameTo(target)) {
                target.writeBytes(payload)
                tmp.delete()
            }
        }
    }

    private fun readEncryptedJson(target: File): String? {
        if (!target.exists() || !target.isFile) return null
        return synchronized(keyLock) {
            runCatching {
                val payload = target.readBytes()
                if (payload.size < 2) return@runCatching null
                val ivLen = payload[0].toInt() and 0xFF
                if (ivLen < 12 || payload.size <= 1 + ivLen) return@runCatching null
                val iv = payload.copyOfRange(1, 1 + ivLen)
                val cipherBytes = payload.copyOfRange(1 + ivLen, payload.size)

                val key = getExistingSecretKey() ?: return@runCatching null
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                val plain = cipher.doFinal(cipherBytes)
                String(plain, Charsets.UTF_8)
            }.onFailure {
                target.delete()
            }.getOrNull()
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        getExistingSecretKey()?.let { return it }
        val ksResult = runCatching {
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            kg.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            kg.generateKey()
        }.getOrNull()
        if (ksResult != null) return ksResult

        val bytes = ByteArray(32).also { random.nextBytes(it) }
        fallbackKeyBytes = bytes
        return SecretKeySpec(bytes, "AES")
    }

    private fun getExistingSecretKey(): SecretKey? {
        val ksKey = runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.getKey(KEY_ALIAS, null) as? SecretKey else null
        }.getOrNull()
        if (ksKey != null) return ksKey
        return fallbackKeyBytes?.let { SecretKeySpec(it, "AES") }
    }

    private fun sanitizeTabUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed == MainActivity.DEFAULT_START_URL) return MainActivity.DEFAULT_START_URL
        return sanitizeHttpBookmarkUrl(trimmed)
    }

    private fun sanitizeHttpBookmarkUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_URL_CHARS) return null
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return trimmed
    }

    companion object {
        private const val STORE_DIR_NAME = "secure_store"
        private const val TABS_FILE_NAME = "sessions.enc"
        private const val BOOKMARKS_FILE_NAME = "bookmarks.enc"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "stealth_snake_session_store_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val MAX_BOOKMARKS = 250
        private const val MAX_TITLE_CHARS = 240
        private const val MAX_URL_CHARS = 8192

        private val keyLock = Any()
        @Volatile
        private var fallbackKeyBytes: ByteArray? = null
    }
}
