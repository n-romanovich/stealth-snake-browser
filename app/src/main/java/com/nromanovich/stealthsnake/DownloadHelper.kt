package com.nromanovich.stealthsnake

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebView
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.Normalizer
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * Optional file downloader active only when explicitly enabled in [SnakePreferences].
 * Writes directly to MediaStore.Downloads on Android 10+ without requiring storage permissions.
 */
class DownloadHelper(
    context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val executor: ExecutorService = Executors.newFixedThreadPool(3),
    private val vpnKillSwitchEnabled: () -> Boolean = { false },
    private val isVpnActive: () -> Boolean = { VpnGuard.isVpnActive(context.applicationContext) },
    private val isUrlBlocked: (String) -> Boolean = { false },
    private val dnsResolver: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
    private val cookieProvider: (String) -> String? = { url ->
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
    }
) {
    private data class SavedResult(
        val fileName: String,
        val uriString: String? = null,
        val filePath: String? = null,
        val sizeBytes: Long = -1L
    )

    private val appContext = context.applicationContext
    private val historyStore = DownloadHistoryStore(appContext)
    private val activeFutures = Collections.synchronizedSet(mutableSetOf<Future<*>>())
    private val activeStreams = Collections.synchronizedSet(mutableSetOf<Closeable>())
    private val activePartFiles = Collections.synchronizedSet(mutableSetOf<File>())
    private val cancellationGeneration = AtomicLong(0L)

    fun download(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        userAgent: String?,
        onComplete: (Result<String>) -> Unit
    ) {
        if (!isHttpOrHttps(url)) {
            mainHandler.post { onComplete(Result.failure(IllegalArgumentException("Unsupported scheme"))) }
            return
        }
        if (vpnKillSwitchEnabled() && !isVpnActive()) {
            mainHandler.post { onComplete(Result.failure(SecurityException("Blocked by VPN Kill-Switch"))) }
            return
        }
        if (isUrlBlocked(url)) {
            mainHandler.post { onComplete(Result.failure(SecurityException("Blocked by blocklist"))) }
            return
        }
        val fileName = sanitizeFileName(URLUtil.guessFileName(url, contentDisposition, mimeType))
        val effectiveMime = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val taskGen = cancellationGeneration.get()

        try {
            var futureRef: Future<*>? = null
            futureRef = executor.submit {
                var savedMeta = SavedResult(fileName)
                val result = try {
                    checkNotCancelled(taskGen)
                    val conn = openGuardedConnection(url, userAgent, taskGen)
                    val saved = try {
                        val stream = conn.inputStream
                        activeStreams.add(stream)
                        try {
                            saveStream(stream, fileName, effectiveMime, taskGen)
                        } finally {
                            activeStreams.remove(stream)
                        }
                    } finally {
                        conn.disconnect()
                    }
                    savedMeta = saved
                    Result.success(saved.fileName)
                } catch (t: Throwable) {
                    Result.failure(t)
                } finally {
                    futureRef?.let { activeFutures.remove(it) }
                }
                if (taskGen == cancellationGeneration.get() &&
                    (result.isSuccess || result.exceptionOrNull() !is SecurityException)
                ) {
                    historyStore.record(
                        fileName = savedMeta.fileName,
                        mimeType = effectiveMime,
                        success = result.isSuccess,
                        uriString = savedMeta.uriString,
                        filePath = savedMeta.filePath,
                        sizeBytes = savedMeta.sizeBytes
                    )
                }
                mainHandler.post { onComplete(result) }
            }
            activeFutures.add(futureRef)
        } catch (_: RejectedExecutionException) {
            mainHandler.post { onComplete(Result.failure(IllegalStateException("Downloader shut down"))) }
        }
    }

    fun downloadDataUri(
        dataUri: String,
        contentDisposition: String?,
        mimeType: String?,
        onComplete: (Result<String>) -> Unit
    ) {
        if (!dataUri.lowercase(Locale.ROOT).startsWith("data:")) {
            mainHandler.post { onComplete(Result.failure(IllegalArgumentException("Unsupported scheme"))) }
            return
        }
        if (vpnKillSwitchEnabled() && !isVpnActive()) {
            mainHandler.post { onComplete(Result.failure(SecurityException("Blocked by VPN Kill-Switch"))) }
            return
        }
        val taskGen = cancellationGeneration.get()
        try {
            var futureRef: Future<*>? = null
            futureRef = executor.submit {
                var effectiveMime = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                var fileName = "download_${System.currentTimeMillis()}"
                var savedMeta = SavedResult(fileName)
                val result = try {
                    checkNotCancelled(taskGen)
                    val (decodedMime, bytes) = decodeDataUri(dataUri, mimeType)
                    effectiveMime = decodedMime
                    val suggested = contentDisposition?.let { URLUtil.guessFileName("https://local.invalid/", it, decodedMime) }
                        ?.takeIf { !it.equals("downloadfile.bin", ignoreCase = true) }
                    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(decodedMime)
                    val fallbackName = if (!ext.isNullOrBlank()) "download_${System.currentTimeMillis()}.$ext" else fileName
                    fileName = sanitizeFileName(suggested ?: fallbackName)
                    savedMeta = SavedResult(fileName)
                    val saved = saveStream(ByteArrayInputStream(bytes), fileName, effectiveMime, taskGen)
                    savedMeta = saved
                    Result.success(saved.fileName)
                } catch (t: Throwable) {
                    Result.failure(t)
                } finally {
                    futureRef?.let { activeFutures.remove(it) }
                }
                if (taskGen == cancellationGeneration.get() &&
                    (result.isSuccess || result.exceptionOrNull() !is SecurityException)
                ) {
                    historyStore.record(
                        fileName = savedMeta.fileName,
                        mimeType = effectiveMime,
                        success = result.isSuccess,
                        uriString = savedMeta.uriString,
                        filePath = savedMeta.filePath,
                        sizeBytes = savedMeta.sizeBytes
                    )
                }
                mainHandler.post { onComplete(result) }
            }
            activeFutures.add(futureRef)
        } catch (_: RejectedExecutionException) {
            mainHandler.post { onComplete(Result.failure(IllegalStateException("Downloader shut down"))) }
        }
    }

    /**
     * Reads a `blob:` download via a one-shot [WebView.evaluateJavascript] call with zero
     * `@JavascriptInterface` bridge, then persists the resulting bytes via [downloadDataUri].
     */
    fun downloadBlobUrl(
        webView: WebView,
        blobUrl: String,
        contentDisposition: String?,
        mimeType: String?,
        onComplete: (Result<String>) -> Unit
    ) {
        if (!blobUrl.lowercase(Locale.ROOT).startsWith("blob:")) {
            mainHandler.post { onComplete(Result.failure(IllegalArgumentException("Unsupported blob scheme"))) }
            return
        }
        if (vpnKillSwitchEnabled() && !isVpnActive()) {
            mainHandler.post { onComplete(Result.failure(SecurityException("Blocked by VPN Kill-Switch"))) }
            return
        }
        val taskGen = cancellationGeneration.get()
        val token = "__ssdl_${UUID.randomUUID().toString().replace("-", "")}"
        val quotedUrl = JSONObject.quote(blobUrl)
        val quotedToken = JSONObject.quote(token)
        val script = """
            (function(){
              try{
                Object.defineProperty(window,$quotedToken,{value:'pending',writable:true,configurable:true});
                fetch($quotedUrl).then(function(r){
                  if(!r.ok)throw new Error('HTTP '+r.status);
                  return r.blob();
                }).then(function(b){
                  if(b.size>${MAX_INLINE_DOWNLOAD_BYTES}){
                    window[$quotedToken]='err:Blob exceeds 50 MB limit';
                    return;
                  }
                  var fr=new FileReader();
                  fr.onloadend=function(){
                    window[$quotedToken]='ok:'+String(fr.result||'');
                  };
                  fr.onerror=function(){
                    window[$quotedToken]='err:Blob read failed';
                  };
                  fr.readAsDataURL(b);
                }).catch(function(e){
                  window[$quotedToken]='err:'+(e&&e.message?e.message:'Blob fetch failed');
                });
              }catch(e){return 'err:'+(e&&e.message?e.message:'Eval failed');}
              return 'started';
            })()
        """.trimIndent()

        mainHandler.post {
            webView.evaluateJavascript(script) { initial ->
                val cleanedInit = initial?.trim()?.trim('"').orEmpty()
                if (cleanedInit.startsWith("err:")) {
                    onComplete(Result.failure(IOException(cleanedInit.removePrefix("err:"))))
                    return@evaluateJavascript
                }
                pollBlobResult(webView, token, contentDisposition, mimeType, taskGen, 0, onComplete)
            }
        }
    }

    private fun pollBlobResult(
        webView: WebView,
        token: String,
        contentDisposition: String?,
        mimeType: String?,
        taskGen: Long,
        attempt: Int,
        onComplete: (Result<String>) -> Unit
    ) {
        if (taskGen != cancellationGeneration.get()) {
            val cleanup = "try{delete window[${JSONObject.quote(token)}]}catch(e){}"
            runCatching { webView.evaluateJavascript(cleanup, null) }
            onComplete(Result.failure(InterruptedIOException("Download cancelled")))
            return
        }
        if (attempt > MAX_BLOB_POLL_ATTEMPTS) {
            val cleanup = "try{delete window[${JSONObject.quote(token)}]}catch(e){}"
            runCatching { webView.evaluateJavascript(cleanup, null) }
            onComplete(Result.failure(IOException("Blob read timed out")))
            return
        }
        val quotedToken = JSONObject.quote(token)
        val pollScript = """
            (function(){
              var v=window[$quotedToken];
              if(!v||v==='pending')return 'pending';
              try{delete window[$quotedToken];}catch(e){}
              return v;
            })()
        """.trimIndent()
        mainHandler.postDelayed({
            if (taskGen != cancellationGeneration.get()) {
                onComplete(Result.failure(InterruptedIOException("Download cancelled")))
                return@postDelayed
            }
            webView.evaluateJavascript(pollScript) { raw ->
                val value = runCatching {
                    JSONObject("""{"v":$raw}""").optString("v", "")
                }.getOrNull() ?: raw?.trim('"').orEmpty()
                when {
                    value == "pending" || value.isEmpty() ->
                        pollBlobResult(webView, token, contentDisposition, mimeType, taskGen, attempt + 1, onComplete)
                    value.startsWith("ok:") ->
                        downloadDataUri(value.removePrefix("ok:"), contentDisposition, mimeType, onComplete)
                    value.startsWith("err:") ->
                        onComplete(Result.failure(IOException(value.removePrefix("err:"))))
                    else ->
                        onComplete(Result.failure(IOException("Unexpected blob result")))
                }
            }
        }, BLOB_POLL_INTERVAL_MS)
    }

    private fun saveStream(
        input: java.io.InputStream,
        fileName: String,
        effectiveMime: String,
        taskGen: Long
    ): SavedResult {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveStreamToMediaStore(input, fileName, effectiveMime, taskGen)
        } else {
            saveStreamToPrivateDownloads(input, fileName, taskGen)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveStreamToMediaStore(
        input: java.io.InputStream,
        fileName: String,
        effectiveMime: String,
        taskGen: Long = cancellationGeneration.get()
    ): SavedResult {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, effectiveMime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/StealthSnake")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert returned null")
        try {
            val sizeBytes = resolver.openOutputStream(uri)?.use { out ->
                activeStreams.add(out)
                try {
                    val count = copyWithVpnGuardCounting(input, out, taskGen)
                    out.flush()
                    count
                } finally {
                    activeStreams.remove(out)
                }
            } ?: throw IOException("Cannot open download stream")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return SavedResult(fileName = fileName, uriString = uri.toString(), sizeBytes = sizeBytes)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun saveStreamToPrivateDownloads(
        input: java.io.InputStream,
        fileName: String,
        taskGen: Long = cancellationGeneration.get()
    ): SavedResult {
        val dir = File(appContext.filesDir, "downloads")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Failed to create downloads directory")
        }
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var candidate = File(dir, fileName)
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(dir, "${stem}_$suffix$ext")
            suffix++
        }
        val partFile = File(dir, "${candidate.name}.part")
        activePartFiles.add(partFile)
        try {
            val sizeBytes = FileOutputStream(partFile).use { out ->
                activeStreams.add(out)
                try {
                    val count = copyWithVpnGuardCounting(input, out, taskGen)
                    out.flush()
                    count
                } finally {
                    activeStreams.remove(out)
                }
            }
            checkNotCancelled(taskGen)
            if (!partFile.renameTo(candidate)) {
                partFile.copyTo(candidate, overwrite = true)
                partFile.delete()
            }
            return SavedResult(fileName = candidate.name, filePath = candidate.absolutePath, sizeBytes = sizeBytes)
        } catch (t: Throwable) {
            runCatching { partFile.delete() }
            runCatching { candidate.delete() }
            throw t
        } finally {
            activePartFiles.remove(partFile)
        }
    }

    internal fun copyWithVpnGuard(input: java.io.InputStream, out: OutputStream) {
        copyWithVpnGuardCounting(input, out, cancellationGeneration.get())
    }

    private fun copyWithVpnGuardCounting(
        input: java.io.InputStream,
        out: OutputStream,
        taskGen: Long
    ): Long {
        val buffer = ByteArray(8192)
        var totalBytes = 0L
        input.use { stream ->
            while (true) {
                checkNotCancelled(taskGen)
                if (vpnKillSwitchEnabled() && !isVpnActive()) {
                    throw SecurityException("Blocked by VPN Kill-Switch")
                }
                val read = stream.read(buffer)
                if (read < 0) break
                checkNotCancelled(taskGen)
                if (vpnKillSwitchEnabled() && !isVpnActive()) {
                    throw SecurityException("Blocked by VPN Kill-Switch")
                }
                if (read > 0) {
                    out.write(buffer, 0, read)
                    totalBytes += read
                }
            }
        }
        return totalBytes
    }

    private fun checkNotCancelled(taskGen: Long) {
        if (Thread.currentThread().isInterrupted || taskGen != cancellationGeneration.get()) {
            throw InterruptedIOException("Download cancelled")
        }
    }

    private fun openGuardedConnection(
        startUrl: String,
        userAgent: String?,
        taskGen: Long = cancellationGeneration.get()
    ): HttpURLConnection {
        var currentUrl = startUrl
        for (hop in 0..MAX_REDIRECTS) {
            checkNotCancelled(taskGen)
            if (!isHttpOrHttps(currentUrl)) {
                throw IllegalArgumentException("Unsupported redirect scheme")
            }
            if (vpnKillSwitchEnabled() && !isVpnActive()) {
                throw SecurityException("Blocked by VPN Kill-Switch")
            }
            if (isUrlBlocked(currentUrl)) {
                throw SecurityException("Blocked by blocklist")
            }
            val parsedUrl = URL(currentUrl)
            val validatedAddrs = resolveAndValidatePublicHost(parsedUrl.host, dnsResolver)
            val (connectUrl, hostHeader) = resolvePinnedTarget(parsedUrl, validatedAddrs[0])
            val cookieHeader = cookieProvider(currentUrl)?.takeIf { it.isNotBlank() }
            val conn = (connectUrl.openConnection() as HttpURLConnection).apply {
                if (hostHeader != null) {
                    setRequestProperty("Host", hostHeader)
                }
                if (cookieHeader != null) {
                    setRequestProperty("Cookie", cookieHeader)
                }
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                if (!userAgent.isNullOrBlank()) {
                    setRequestProperty("User-Agent", userAgent)
                }
                if (this is HttpsURLConnection) {
                    sslSocketFactory = createPinnedSslSocketFactory(
                        pinnedAddresses = validatedAddrs,
                        connectTimeoutMs = connectTimeout
                    )
                }
            }
            val code = try {
                conn.responseCode
            } catch (t: Throwable) {
                conn.disconnect()
                throw t
            }
            // Re-validate DNS on plain HTTP redirects/connections to catch short-TTL rebinding where SocketFactory isn't hookable.
            if (conn !is HttpsURLConnection) {
                runCatching { resolveAndValidatePublicHost(parsedUrl.host, dnsResolver) }
                    .onFailure {
                        conn.disconnect()
                        throw it
                    }
            }
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("Empty redirect location")
                val nextUrl = URL(parsedUrl, location).toString()
                if (currentUrl.lowercase(Locale.ROOT).startsWith("https://") &&
                    nextUrl.lowercase(Locale.ROOT).startsWith("http://")
                ) {
                    throw SecurityException("HTTPS to HTTP downgrade blocked")
                }
                currentUrl = nextUrl
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IOException("HTTP $code")
            }
            return conn
        }
        throw IOException("Too many redirects")
    }

    fun cancelAll() {
        cancellationGeneration.incrementAndGet()
        val futures = synchronized(activeFutures) { activeFutures.toList().also { activeFutures.clear() } }
        for (f in futures) {
            runCatching { f.cancel(true) }
        }
        val streams = synchronized(activeStreams) { activeStreams.toList().also { activeStreams.clear() } }
        for (s in streams) {
            runCatching { s.close() }
        }
        val parts = synchronized(activePartFiles) { activePartFiles.toList().also { activePartFiles.clear() } }
        for (p in parts) {
            runCatching { p.delete() }
        }
    }

    fun shutdown() {
        cancelAll()
        executor.shutdownNow()
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        internal const val MAX_INLINE_DOWNLOAD_BYTES = 50L * 1024L * 1024L
        private const val BLOB_POLL_INTERVAL_MS = 120L
        private const val MAX_BLOB_POLL_ATTEMPTS = 125
        private val BIDI_AND_CONTROL_REGEX = Regex("[\\u202A-\\u202E\\u2066-\\u2069\\u200B-\\u200F\\u061C\\uFEFF\\u0000-\\u001F\\u007F-\\u009F]")

        internal fun decodeDataUri(dataUri: String, fallbackMime: String?): Pair<String, ByteArray> {
            val commaIdx = dataUri.indexOf(',')
            if (commaIdx < 5 || !dataUri.lowercase(Locale.ROOT).startsWith("data:")) {
                throw IllegalArgumentException("Malformed data URI")
            }
            val meta = dataUri.substring(5, commaIdx)
            val payload = dataUri.substring(commaIdx + 1)
            val tokens = meta.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            val isBase64 = tokens.any { it.equals("base64", ignoreCase = true) }
            val headerMime = tokens.firstOrNull { !it.equals("base64", ignoreCase = true) && it.contains('/') }
            val effectiveMime = headerMime?.lowercase(Locale.ROOT)
                ?: fallbackMime?.takeIf { it.isNotBlank() }
                ?: "application/octet-stream"

            val bytes = if (isBase64) {
                val cleanedB64 = if ('%' in payload) decodePercentEncodedBytes(payload).toString(Charsets.UTF_8) else payload
                val estimated = (cleanedB64.length.toLong() * 3L) / 4L
                if (estimated > MAX_INLINE_DOWNLOAD_BYTES + 1024L) {
                    throw IOException("Data URI exceeds 50 MB limit")
                }
                val decoded = Base64.decode(cleanedB64, Base64.DEFAULT)
                if (decoded.size.toLong() > MAX_INLINE_DOWNLOAD_BYTES) {
                    throw IOException("Data URI exceeds 50 MB limit")
                }
                decoded
            } else {
                val decoded = decodePercentEncodedBytes(payload)
                if (decoded.size.toLong() > MAX_INLINE_DOWNLOAD_BYTES) {
                    throw IOException("Data URI exceeds 50 MB limit")
                }
                decoded
            }
            return effectiveMime to bytes
        }

        private fun decodePercentEncodedBytes(input: String): ByteArray {
            val out = ByteArrayOutputStream(input.length)
            var i = 0
            while (i < input.length) {
                val c = input[i]
                if (c == '%' && i + 2 < input.length) {
                    val hi = Character.digit(input[i + 1], 16)
                    val lo = Character.digit(input[i + 2], 16)
                    if (hi >= 0 && lo >= 0) {
                        out.write((hi shl 4) or lo)
                        i += 3
                        continue
                    }
                }
                val chBytes = c.toString().toByteArray(Charsets.UTF_8)
                out.write(chBytes, 0, chBytes.size)
                i++
            }
            return out.toByteArray()
        }

        private fun isHttpOrHttps(url: String): Boolean {
            val lower = url.lowercase(Locale.ROOT)
            return lower.startsWith("https://") || lower.startsWith("http://")
        }

        internal fun resolveAndValidatePublicHost(
            host: String?,
            resolver: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) }
        ): Array<InetAddress> {
            val trimmedHost = host?.trim().orEmpty()
            if (trimmedHost.isEmpty()) throw SecurityException("Missing host")
            val addrs = resolver(trimmedHost)
            if (addrs.isEmpty()) throw SecurityException("Missing host addresses")
            for (addr in addrs) {
                if (!isPublicIpAddress(addr)) {
                    throw SecurityException("Blocked non-public address")
                }
            }
            return addrs
        }

        internal fun isPublicIpAddress(addr: InetAddress): Boolean {
            if (addr.isAnyLocalAddress ||
                addr.isLoopbackAddress ||
                addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress ||
                addr.isMulticastAddress
            ) {
                return false
            }
            val bytes = addr.address
            if (addr is Inet4Address || bytes.size == 4) {
                return isPublicIpv4Bytes(bytes)
            }
            if (addr is Inet6Address || bytes.size == 16) {
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                // ULA fc00::/7
                if ((b0 and 0xFE) == 0xFC) return false
                // Link-local fe80::/10 and site-local fec0::/10
                if (b0 == 0xFE && (b1 and 0x80) == 0x80) return false
                // Multicast ff00::/8
                if (b0 == 0xFF) return false
                // IPv4-mapped (::ffff:0:0/96) and IPv4-compatible (::/96)
                val firstTenZero = (0 until 10).all { bytes[it].toInt() == 0 }
                if (firstTenZero) {
                    val b10 = bytes[10].toInt() and 0xFF
                    val b11 = bytes[11].toInt() and 0xFF
                    if ((b10 == 0xFF && b11 == 0xFF) || (b10 == 0x00 && b11 == 0x00)) {
                        return isPublicIpv4Bytes(bytes.copyOfRange(12, 16))
                    }
                }
                // 6to4 (2002::/16) wraps IPv4 in bytes 2..5
                if (b0 == 0x20 && b1 == 0x02) {
                    if (!isPublicIpv4Bytes(bytes.copyOfRange(2, 6))) return false
                }
                return true
            }
            return false
        }

        private fun isPublicIpv4Bytes(bytes: ByteArray): Boolean {
            if (bytes.size != 4) return false
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val b2 = bytes[2].toInt() and 0xFF
            return when {
                b0 == 0 -> false // 0.0.0.0/8 ("this" network)
                b0 == 10 -> false // 10.0.0.0/8 (RFC1918)
                b0 == 100 && (b1 and 0xC0) == 64 -> false // 100.64.0.0/10 (CGNAT RFC6598)
                b0 == 127 -> false // 127.0.0.0/8 (loopback)
                b0 == 169 && b1 == 254 -> false // 169.254.0.0/16 (link-local / metadata)
                b0 == 172 && b1 in 16..31 -> false // 172.16.0.0/12 (RFC1918)
                b0 == 192 && b1 == 0 && b2 == 0 -> false // 192.0.0.0/24 (IETF protocol assignments)
                b0 == 192 && b1 == 168 -> false // 192.168.0.0/16 (RFC1918)
                b0 == 198 && (b1 == 18 || b1 == 19) -> false // 198.18.0.0/15 (benchmarking)
                b0 >= 224 -> false // 224.0.0.0/4 multicast + 240.0.0.0/4 reserved/broadcast
                else -> true
            }
        }

        internal fun createPinnedSslSocketFactory(
            pinnedAddresses: Array<InetAddress>,
            delegate: SSLSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory(),
            connectTimeoutMs: Int = 15_000
        ): SSLSocketFactory {
            val allowedBytes = pinnedAddresses.map { it.address.copyOf() }
            fun ensureAllowedRemote(addr: InetAddress?) {
                if (addr == null || !isPublicIpAddress(addr) || allowedBytes.none { it.contentEquals(addr.address) }) {
                    throw SecurityException("Blocked unpinned or non-public target address")
                }
            }
            fun connectPinnedSocket(port: Int, localAddress: InetAddress?, localPort: Int): Socket {
                var lastError: IOException? = null
                for (target in pinnedAddresses) {
                    if (!isPublicIpAddress(target)) continue
                    val raw = Socket()
                    try {
                        if (localAddress != null) {
                            raw.bind(InetSocketAddress(localAddress, localPort))
                        }
                        raw.connect(InetSocketAddress(target, port), connectTimeoutMs)
                        ensureAllowedRemote(raw.inetAddress)
                        return raw
                    } catch (e: IOException) {
                        runCatching { raw.close() }
                        lastError = e
                    }
                }
                throw lastError ?: SecurityException("No public pinned address available")
            }

            return object : SSLSocketFactory() {
                override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
                override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

                override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
                    ensureAllowedRemote(s.inetAddress)
                    return delegate.createSocket(s, host, port, autoClose)
                }

                override fun createSocket(host: String, port: Int): Socket {
                    val raw = connectPinnedSocket(port, null, 0)
                    return delegate.createSocket(raw, host, port, true)
                }

                override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
                    val raw = connectPinnedSocket(port, localHost, localPort)
                    return delegate.createSocket(raw, host, port, true)
                }

                override fun createSocket(host: InetAddress, port: Int): Socket {
                    ensureAllowedRemote(host)
                    return delegate.createSocket(host, port)
                }

                override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
                    ensureAllowedRemote(address)
                    return delegate.createSocket(address, port, localAddress, localPort)
                }
            }
        }

        /**
         * For plain HTTP (no SocketFactory hook), connects directly to the validated literal address to
         * prevent DNS-rebinding TOCTOU while keeping the original authority in `Host`. HTTPS keeps the
         * hostname for SNI/certificate validation and pins the address in [createPinnedSslSocketFactory].
         */
        internal fun resolvePinnedTarget(parsedUrl: URL, validatedAddress: InetAddress): Pair<URL, String?> {
            if (!parsedUrl.protocol.equals("http", ignoreCase = true)) {
                return parsedUrl to null
            }
            val literal = validatedAddress.hostAddress.orEmpty().substringBefore('%')
            val hostForUrl = if (literal.contains(':')) "[$literal]" else literal
            val connectUrl = URL(parsedUrl.protocol, hostForUrl, parsedUrl.port, parsedUrl.file)
            val authority = if (parsedUrl.port != -1) "${parsedUrl.host}:${parsedUrl.port}" else parsedUrl.host
            return connectUrl to authority
        }

        fun sanitizeFileName(raw: String): String {
            val stripped = BIDI_AND_CONTROL_REGEX.replace(raw, "")
            val normalized = Normalizer.normalize(stripped, Normalizer.Form.NFC)
            val noTraversal = normalized.replace("..", "_")
            val cleaned = noTraversal.replace(Regex("[^a-zA-Z0-9._-]"), "_").trimStart('.', '_')
            return if (cleaned.isBlank()) "download_${System.currentTimeMillis()}" else cleaned.take(120)
        }
    }
}
