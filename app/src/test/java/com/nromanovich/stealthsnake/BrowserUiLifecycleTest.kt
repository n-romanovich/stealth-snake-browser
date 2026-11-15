package com.nromanovich.stealthsnake

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowserUiLifecycleTest {

    private val ctx: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ShadowAlertDialog.reset()
        SnakePreferences(ctx).apply {
            resetToDefaults()
            onboardingCompleted = true
        }
    }

    @Test
    fun `progressBar stays visible during page load even when showStatusBar is false`() {
        val prefs = SnakePreferences(ctx)
        assertFalse(prefs.showStatusBar)

        val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = ctrl.get()
        val statusRow = activity.findViewById<View>(R.id.statusRow)
        val progressBar = activity.findViewById<ProgressBar>(R.id.progressBar)
        val webView = activity.findViewById<WebView>(R.id.webView)

        assertEquals(View.GONE, statusRow.visibility)
        assertNotEquals("progressBar must not be parented inside statusRow", statusRow, progressBar.parent)

        webView.webChromeClient!!.onProgressChanged(webView, 45)
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, progressBar.visibility)
        assertEquals(45, progressBar.progress)

        webView.webChromeClient!!.onProgressChanged(webView, 100)
        ShadowLooper.idleMainLooper()
        assertEquals(View.GONE, progressBar.visibility)

        ctrl.pause().destroy()
    }

    @Test
    fun `urlInput retains user draft when focused during background page callbacks`() {
        val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = ctrl.get()
        val urlInput = activity.findViewById<EditText>(R.id.urlInput)
        val webView = activity.findViewById<WebView>(R.id.webView)

        urlInput.requestFocus()
        assertTrue(urlInput.hasFocus())
        urlInput.setText("duck")

        webView.webViewClient.onPageStarted(webView, "https://redirect.example.org/step", null)
        assertEquals("duck", urlInput.text.toString())

        webView.webViewClient.onPageFinished(webView, "https://redirect.example.org/done")
        assertEquals("duck", urlInput.text.toString())

        urlInput.clearFocus()
        webView.webViewClient.onPageFinished(webView, "https://redirect.example.org/done")
        assertEquals("https://redirect.example.org/done", urlInput.text.toString())

        ctrl.pause().destroy()
    }

    @Test
    fun `ssl bypass state persists for same host and clears on navigation to another host`() {
        val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = ctrl.get()
        val webView = activity.findViewById<WebView>(R.id.webView)
        val lockIcon = activity.findViewById<ImageView>(R.id.siteSecurityIcon)

        val handler = org.robolectric.shadow.api.Shadow.newInstanceOf(SslErrorHandler::class.java)
        @Suppress("DEPRECATION")
        val cert = android.net.http.SslCertificate(
            "CN=expired.example.org",
            "CN=TestCA",
            java.util.Date(0),
            java.util.Date(System.currentTimeMillis() + 86400_000L)
        )
        val sslError = SslError(SslError.SSL_EXPIRED, cert, "https://expired.example.org/login")
        webView.webViewClient.onReceivedSslError(webView, handler, sslError)
        ShadowLooper.idleMainLooper()

        val dialog = ShadowAlertDialog.getShownDialogs().lastOrNull { it.isShowing } as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()

        webView.webViewClient.onPageStarted(webView, "https://expired.example.org/dashboard", null)
        assertEquals(0.45f, lockIcon.alpha, 0.01f)

        webView.webViewClient.onPageStarted(webView, "https://clean.example.org/", null)
        assertEquals(1.0f, lockIcon.alpha, 0.01f)

        ctrl.pause().destroy()
    }

    @Test
    fun `microJitter pauses when startPageCard or vpnOverlay is visible and starts when browsing`() {
        val prefs = SnakePreferences(ctx)
        prefs.microJitterEnabled = true
        prefs.homeUrl = MainActivity.DEFAULT_START_URL
        prefs.startHintDismissed = false

        val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = ctrl.get()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.startPageCard).visibility)
        assertFalse("microJitter must stay stopped while start page card is shown", activity.isMicroJitterRunningForTest())

        activity.findViewById<View>(R.id.btnStartDismiss).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.startPageCard).visibility)
        assertTrue("microJitter must start once start overlay is dismissed", activity.isMicroJitterRunningForTest())

        ctrl.pause().destroy()
    }

    @Test
    fun `SnakeChrome file chooser invokes blocked callback without deadlocking WebView`() {
        var blockedNotified = false
        val chrome = SnakeChrome(onFileChooserBlockedCallback = { blockedNotified = true })
        var received: Array<Uri>? = arrayOf(Uri.parse("file:///tmp/x"))
        val handled = chrome.onShowFileChooser(null, ValueCallback { received = it }, null)

        assertTrue(handled)
        assertTrue(blockedNotified)
        assertNull(received)
    }

    @Test
    fun `UploadSanitizer strips EXIF marker from images and enforces 50MB limit`() {
        assertEquals(UploadSanitizer.SanitizeResult.Failed, UploadSanitizer.sanitize(ctx, Uri.parse("file:///etc/passwd")))

        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val rawJpeg = ByteArrayOutputStream().apply {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, this)
        }.toByteArray()
        val fakeExifPayload = "Exif\u0000\u0000II\u002A\u0000\u0008\u0000\u0000\u0000\u0000\u0000LeicaCamera_GPS_55.75_37.61".toByteArray(Charsets.ISO_8859_1)
        val app1Len = fakeExifPayload.size + 2
        val jpegWithExif = ByteArrayOutputStream().apply {
            write(0xFF)
            write(0xD8)
            write(0xFF)
            write(0xE1)
            write((app1Len ushr 8) and 0xFF)
            write(app1Len and 0xFF)
            write(fakeExifPayload)
            write(rawJpeg.drop(2).toByteArray())
        }.toByteArray()

        val resolver = Shadows.shadowOf(ctx.contentResolver)
        val imgUri = Uri.parse("content://test.provider/secret_camera_photo.jpg")
        resolver.registerInputStream(imgUri, ByteArrayInputStream(jpegWithExif))

        val imgResult = UploadSanitizer.sanitize(ctx, imgUri)
        assertTrue(imgResult is UploadSanitizer.SanitizeResult.Success)
        val outFile = File((imgResult as UploadSanitizer.SanitizeResult.Success).uri.path!!)
        assertTrue(outFile.name.startsWith("upload_"))
        assertTrue(outFile.name.endsWith(".jpg"))
        assertFalse(outFile.name.contains("secret_camera_photo"))
        val outText = String(outFile.readBytes(), Charsets.ISO_8859_1)
        assertFalse("EXIF payload must be stripped", outText.contains("LeicaCamera_GPS"))

        val oversizedUri = Uri.parse("content://test.provider/huge.bin")
        val oversizedStream = object : InputStream() {
            private var remaining = UploadSanitizer.MAX_UPLOAD_BYTES + 4096L
            override fun read(): Int = if (remaining-- > 0) 0x41 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0L) return -1
                val n = minOf(len.toLong(), remaining).toInt()
                java.util.Arrays.fill(b, off, off + n, 0x41.toByte())
                remaining -= n
                return n
            }
        }
        resolver.registerInputStream(oversizedUri, oversizedStream)
        assertEquals(UploadSanitizer.SanitizeResult.TooLarge, UploadSanitizer.sanitize(ctx, oversizedUri))

        UploadSanitizer.clearTempUploads(ctx)
        assertFalse(outFile.exists())
    }

    @Test
    fun `main frame network error displays errorOverlay and preserves across tab switch until retry or back`() {
        val prefs = SnakePreferences(ctx)
        prefs.homeUrl = MainActivity.DEFAULT_START_URL
        prefs.startHintDismissed = false

        val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = ctrl.get()
        val webView = activity.findViewById<WebView>(R.id.webView)
        val errorOverlay = activity.findViewById<View>(R.id.errorOverlay)
        val errorOverlayBody = activity.findViewById<TextView>(R.id.errorOverlayBody)
        val startPageCard = activity.findViewById<View>(R.id.startPageCard)
        val urlInput = activity.findViewById<EditText>(R.id.urlInput)

        val request = object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse("https://down.example/article")
            override fun isForMainFrame(): Boolean = true
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = false
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
        }
        webView.webViewClient.onReceivedError(webView, request, null)
        ShadowLooper.idleMainLooper()

        val expectedFormatted = activity.getString(
            R.string.error_page_message,
            activity.getString(R.string.error_page_title),
            -1
        )
        assertEquals(View.VISIBLE, errorOverlay.visibility)
        assertEquals(View.GONE, startPageCard.visibility)
        assertEquals(expectedFormatted, errorOverlayBody.text.toString())
        assertTrue(urlInput.text.toString().contains("down.example/article"))

        // Switching to a new tab hides errorOverlay; closing it restores tab 0's errorOverlay.
        val secondTab = activity.createNewSession(loadHome = false, switchTo = true)
        ShadowLooper.idleMainLooper()
        assertEquals(View.GONE, errorOverlay.visibility)

        activity.closeSession(secondTab)
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, errorOverlay.visibility)
        assertEquals(expectedFormatted, errorOverlayBody.text.toString())
        assertTrue(urlInput.text.toString().contains("down.example/article"))

        // Retry clears errorOverlay cleanly.
        activity.findViewById<View>(R.id.btnErrorRetry).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(View.GONE, errorOverlay.visibility)

        // Pressing Back dismisses errorOverlay when WebView history is empty.
        webView.webViewClient.onReceivedError(webView, request, null)
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, errorOverlay.visibility)
        activity.onBackPressedDispatcher.onBackPressed()
        ShadowLooper.idleMainLooper()
        assertEquals(View.GONE, errorOverlay.visibility)
        assertEquals(View.VISIBLE, startPageCard.visibility)

        ctrl.pause().destroy()
    }

    @Test
    fun `promptHttpAuth formats 2-arg message with host and realm`() {
        SnakePreferences(ctx).apply {
            homeUrl = MainActivity.DEFAULT_START_URL
            startHintDismissed = true
        }
        val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = ctrl.get()
        val webView = activity.findViewById<WebView>(R.id.webView)

        webView.loadUrl("https://auth.example/admin")
        ShadowLooper.idleMainLooper()

        webView.webViewClient.onReceivedHttpAuthRequest(webView, null, "auth.example", "Restricted Zone")
        ShadowLooper.idleMainLooper()

        val dialog = ShadowAlertDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        val message = dialog.findViewById<TextView>(android.R.id.message)?.text?.toString().orEmpty()
        assertEquals(
            activity.getString(R.string.http_auth_message, "auth.example", "Restricted Zone"),
            message
        )
        assertTrue(message.contains("auth.example"))
        assertTrue(message.contains("Restricted Zone"))

        dialog.dismiss()
        ctrl.pause().destroy()
    }

    @Test
    fun `UploadSanitizer rejects SVG payloads and oversized pixel dimensions`() {
        val resolver = Shadows.shadowOf(ctx.contentResolver)

        val svgBytes = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
            .toByteArray(Charsets.UTF_8)
        val svgUri = Uri.parse("content://test.provider/vector.svg")
        resolver.registerInputStream(svgUri, ByteArrayInputStream(svgBytes))
        assertEquals(UploadSanitizer.SanitizeResult.Failed, UploadSanitizer.sanitize(ctx, svgUri))

        val disguisedSvgUri = Uri.parse("content://test.provider/disguised.png")
        resolver.registerInputStream(disguisedSvgUri, ByteArrayInputStream(svgBytes))
        assertEquals(UploadSanitizer.SanitizeResult.Failed, UploadSanitizer.sanitize(ctx, disguisedSvgUri))

        val bombPng = buildMinimalValidPng(width = 5_001, height = 5_001)
        val bombUri = Uri.parse("content://test.provider/pixel_bomb.png")
        resolver.registerInputStream(bombUri, ByteArrayInputStream(bombPng))
        assertEquals(UploadSanitizer.SanitizeResult.TooLarge, UploadSanitizer.sanitize(ctx, bombUri))

        UploadSanitizer.clearTempUploads(ctx)
    }

    private fun buildMinimalValidPng(width: Int, height: Int): ByteArray {
        val ihdrData = ByteBuffer.allocate(13)
            .putInt(width)
            .putInt(height)
            .put(1.toByte()) // bit depth: 1-bit grayscale
            .put(0.toByte()) // color type: grayscale
            .put(0.toByte()) // compression: deflate
            .put(0.toByte()) // filter: adaptive
            .put(0.toByte()) // interlace: none
            .array()
        val idatBaos = ByteArrayOutputStream()
        DeflaterOutputStream(idatBaos).use { deflater ->
            val row = ByteArray(1 + (width + 7) / 8)
            repeat(height) {
                deflater.write(row)
            }
        }
        val idatData = idatBaos.toByteArray()

        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            writePngChunk(this, "IHDR", ihdrData)
            writePngChunk(this, "IDAT", idatData)
            writePngChunk(this, "IEND", ByteArray(0))
        }.toByteArray()
    }

    private fun writePngChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }.value.toInt()
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        out.write(typeBytes)
        out.write(data)
        out.write(ByteBuffer.allocate(4).putInt(crc).array())
    }

    @Test
    fun `fullscreen video rotate button freezes portrait bounds and restores on exit`() {
        val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = ctrl.get()
        val webView = activity.findViewById<WebView>(R.id.webView)
        val fullscreenContainer = activity.findViewById<FrameLayout>(R.id.fullscreenContainer)
        val rootContainer = activity.findViewById<View>(R.id.rootContainer)

        var customViewHidden = false
        webView.webChromeClient!!.onShowCustomView(
            View(activity),
            android.webkit.WebChromeClient.CustomViewCallback { customViewHidden = true }
        )
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, fullscreenContainer.visibility)

        val rotateDesc = activity.getString(R.string.fullscreen_rotate_desc)
        val rotateBtn = (0 until fullscreenContainer.childCount)
            .map { fullscreenContainer.getChildAt(it) }
            .first { it.contentDescription?.toString() == rotateDesc }
        rotateBtn.performClick()
        ShadowLooper.idleMainLooper()

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        assertTrue(rootContainer.layoutParams.width > 0)
        assertTrue(rootContainer.layoutParams.height >= rootContainer.layoutParams.width)

        webView.webChromeClient!!.onHideCustomView()
        ShadowLooper.idleMainLooper()

        assertTrue(customViewHidden)
        assertEquals(View.GONE, fullscreenContainer.visibility)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, rootContainer.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, rootContainer.layoutParams.height)

        ctrl.pause().destroy()
    }
}
