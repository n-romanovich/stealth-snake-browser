package com.nromanovich.stealthsnake

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
class DownloadHelperTest {

    private val ctx: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `sanitizeFileName strips path traversal and unsafe characters`() {
        assertEquals("etc_passwd", DownloadHelper.sanitizeFileName("../../etc/passwd"))
        assertEquals("report_2026_1_.pdf", DownloadHelper.sanitizeFileName("report 2026(1).pdf"))
        assertTrue(DownloadHelper.sanitizeFileName("...").startsWith("download_"))
        assertEquals(120, DownloadHelper.sanitizeFileName("a".repeat(200)).length)
    }

    @Test
    fun `download rejects non-http schemes and shutdown executor`() {
        val helper = DownloadHelper(ctx, Handler(Looper.getMainLooper()))
        var result: Result<String>? = null
        helper.download("javascript:alert(1)", null, null, null) { result = it }
        ShadowLooper.idleMainLooper()
        assertTrue(result!!.isFailure)
        assertFalse(result!!.isSuccess)

        helper.shutdown()
        var shutdownResult: Result<String>? = null
        helper.download("https://example.org/file.pdf", null, "application/pdf", null) { shutdownResult = it }
        ShadowLooper.idleMainLooper()
        assertTrue(shutdownResult!!.isFailure)
    }

    @Test
    fun `download enforces VPN kill-switch and blocklist before connecting`() {
        val vpnBlockedHelper = DownloadHelper(
            ctx,
            Handler(Looper.getMainLooper()),
            vpnKillSwitchEnabled = { true },
            isVpnActive = { false },
            isUrlBlocked = { false }
        )
        var vpnResult: Result<String>? = null
        vpnBlockedHelper.download("https://example.org/file.pdf", null, "application/pdf", null) { vpnResult = it }
        ShadowLooper.idleMainLooper()
        assertTrue(vpnResult!!.isFailure)
        assertTrue(vpnResult!!.exceptionOrNull()?.message?.contains("VPN", ignoreCase = true) == true)
        vpnBlockedHelper.shutdown()

        val blocklistHelper = DownloadHelper(
            ctx,
            Handler(Looper.getMainLooper()),
            vpnKillSwitchEnabled = { false },
            isVpnActive = { true },
            isUrlBlocked = { url -> url.contains("tracker.example") }
        )
        var blockResult: Result<String>? = null
        blocklistHelper.download("https://tracker.example/payload.bin", null, null, null) { blockResult = it }
        ShadowLooper.idleMainLooper()
        assertTrue(blockResult!!.isFailure)
        assertTrue(blockResult!!.exceptionOrNull()?.message?.contains("blocklist", ignoreCase = true) == true)
        blocklistHelper.shutdown()
    }

    @Test
    fun `isPublicIpAddress blocks CGNAT IPv6 ULA mapped loopback and permits public IPs`() {
        val blocked = listOf(
            "10.0.0.1",
            "100.64.0.1",
            "100.127.255.254",
            "127.0.0.1",
            "169.254.169.254",
            "172.16.0.1",
            "192.168.1.1",
            "0.0.0.0",
            "224.0.0.1",
            "255.255.255.255",
            "::1",
            "fc00::1",
            "fd12:3456:789a::1",
            "fe80::1",
            "ff02::1",
            "::ffff:127.0.0.1",
            "::ffff:100.64.0.1"
        )
        for (ip in blocked) {
            assertFalse("Expected $ip to be rejected", DownloadHelper.isPublicIpAddress(InetAddress.getByName(ip)))
        }
        // Raw 16-byte ::ffff:169.254.169.254 form (non-canonical v6 wrapper)
        val rawMappedMeta = InetAddress.getByAddress(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, -87, -2, -87, -2)
        )
        assertFalse(DownloadHelper.isPublicIpAddress(rawMappedMeta))

        val allowed = listOf("1.1.1.1", "8.8.8.8", "100.63.255.254", "100.128.0.1", "2606:4700:4700::1111", "::ffff:8.8.8.8")
        for (ip in allowed) {
            assertTrue("Expected $ip to be public", DownloadHelper.isPublicIpAddress(InetAddress.getByName(ip)))
        }
    }

    @Test
    fun `copyWithVpnGuard aborts download stream immediately when VPN drops mid-transfer`() {
        var vpnUp = true
        val helper = DownloadHelper(
            ctx,
            Handler(Looper.getMainLooper()),
            vpnKillSwitchEnabled = { true },
            isVpnActive = { vpnUp }
        )
        val chunkedInput = object : InputStream() {
            private var reads = 0
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                if (reads == 1) {
                    b[off] = 42
                    return 1
                }
                vpnUp = false
                b[off] = 43
                return 1
            }
        }
        val out = ByteArrayOutputStream()
        val ex = try {
            helper.copyWithVpnGuard(chunkedInput, out)
            null
        } catch (t: Throwable) {
            t
        } finally {
            helper.shutdown()
        }
        assertTrue(ex is SecurityException)
        assertEquals(1, out.size())
    }

    @Test(expected = SecurityException::class)
    fun `pinned SSLSocketFactory rejects mismatched or private raw socket addresses`() {
        val factory = DownloadHelper.createPinnedSslSocketFactory(arrayOf(InetAddress.getByName("1.1.1.1")))
        val privateSocket = object : java.net.Socket() {
            override fun getInetAddress(): InetAddress = InetAddress.getByName("127.0.0.1")
        }
        factory.createSocket(privateSocket, "example.org", 443, true)
    }
}
