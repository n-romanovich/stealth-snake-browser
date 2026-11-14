package com.nromanovich.stealthsnake

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.URLUtil
import androidx.annotation.RequiresApi
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
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * Optional file downloader active only when explicitly enabled in [SnakePreferences].
 * Writes directly to MediaStore.Downloads on Android 10+ without requiring storage permissions.
 */
class DownloadHelper(
    context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val vpnKillSwitchEnabled: () -> Boolean = { false },
    private val isVpnActive: () -> Boolean = { VpnGuard.isVpnActive(context.applicationContext) },
    private val isUrlBlocked: (String) -> Boolean = { false },
    private val dnsResolver: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) }
) {
    private val appContext = context.applicationContext
    private val historyStore = DownloadHistoryStore(appContext)

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

        try {
            executor.execute {
                val result = try {
                    val conn = openGuardedConnection(url, userAgent)
                    val savedName = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            saveStreamToMediaStore(conn.inputStream, fileName, effectiveMime)
                        } else {
                            saveStreamToPrivateDownloads(conn.inputStream, fileName)
                        }
                    } finally {
                        conn.disconnect()
                    }
                    Result.success(savedName)
                } catch (t: Throwable) {
                    Result.failure(t)
                }
                if (result.isSuccess || result.exceptionOrNull() !is SecurityException) {
                    historyStore.record(result.getOrDefault(fileName), effectiveMime, result.isSuccess)
                }
                mainHandler.post { onComplete(result) }
            }
        } catch (_: RejectedExecutionException) {
            mainHandler.post { onComplete(Result.failure(IllegalStateException("Downloader shut down"))) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveStreamToMediaStore(
        input: java.io.InputStream,
        fileName: String,
        effectiveMime: String
    ): String {
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
            resolver.openOutputStream(uri)?.use { out ->
                copyWithVpnGuard(input, out)
                out.flush()
            } ?: throw IOException("Cannot open download stream")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return fileName
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun saveStreamToPrivateDownloads(input: java.io.InputStream, fileName: String): String {
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
        try {
            FileOutputStream(candidate).use { out ->
                copyWithVpnGuard(input, out)
                out.flush()
            }
            return candidate.name
        } catch (t: Throwable) {
            runCatching { candidate.delete() }
            throw t
        }
    }

    internal fun copyWithVpnGuard(input: java.io.InputStream, out: OutputStream) {
        val buffer = ByteArray(8192)
        input.use { stream ->
            while (true) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedIOException("Download interrupted")
                }
                if (vpnKillSwitchEnabled() && !isVpnActive()) {
                    throw SecurityException("Blocked by VPN Kill-Switch")
                }
                val read = stream.read(buffer)
                if (read < 0) break
                if (vpnKillSwitchEnabled() && !isVpnActive()) {
                    throw SecurityException("Blocked by VPN Kill-Switch")
                }
                if (read > 0) {
                    out.write(buffer, 0, read)
                }
            }
        }
    }

    private fun openGuardedConnection(startUrl: String, userAgent: String?): HttpURLConnection {
        var currentUrl = startUrl
        for (hop in 0..MAX_REDIRECTS) {
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
            val conn = (connectUrl.openConnection() as HttpURLConnection).apply {
                if (hostHeader != null) {
                    setRequestProperty("Host", hostHeader)
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

    fun shutdown() {
        executor.shutdown()
    }

    companion object {
        private const val MAX_REDIRECTS = 5

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
            val noTraversal = raw.replace("..", "_")
            val cleaned = noTraversal.replace(Regex("[^a-zA-Z0-9._-]"), "_").trimStart('.', '_')
            return if (cleaned.isBlank()) "download_${System.currentTimeMillis()}" else cleaned.take(120)
        }
    }
}
