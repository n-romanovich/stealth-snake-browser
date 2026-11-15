package com.nromanovich.stealthsnake

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import org.junit.Assert.assertNotNull
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

    @Test
    fun `MainActivity shifts webViewContainer only when IME overlaps tapped input while keeping WebView size fixed`() {
        val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        ShadowLooper.idleMainLooper()
        val activity = ctrl.get()
        val webViewContainer = activity.findViewById<FrameLayout>(R.id.webViewContainer)
        val webView = activity.findViewById<WebView>(R.id.webView)
        val urlInput = activity.findViewById<EditText>(R.id.urlInput)

        webViewContainer.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
        )
        webViewContainer.layout(0, 0, 1080, 1000)
        assertEquals(1080, webView.measuredWidth)
        assertEquals(1000, webView.measuredHeight)
        val initWidth = webView.measuredWidth
        val initHeight = webView.measuredHeight

        // Tapping near the top of WebView leaves overlap <= 0: no upward shift that pushes input off-screen.
        activity.onImeInsetChangedForTest(imeBottomPx = 450, touchYInWebView = 120f)
        assertEquals(0f, webViewContainer.translationY, 0.5f)
        assertEquals(initWidth, webView.measuredWidth)
        assertEquals(initHeight, webView.measuredHeight)

        // Tapping near the bottom shifts cleanly past the old 45% cap without resizing the WebView.
        val density = activity.resources.displayMetrics.density
        val expectedMidBottomOverlap = minOf(920f + 56f * density, 1000f) - (1000f - 520f)
        activity.onImeInsetChangedForTest(imeBottomPx = 520, touchYInWebView = 920f)
        assertTrue(expectedMidBottomOverlap > 450f)
        assertEquals(-expectedMidBottomOverlap, webViewContainer.translationY, 0.5f)
        assertEquals(initWidth, webView.measuredWidth)
        assertEquals(initHeight, webView.measuredHeight)

        // Focusing toolbar urlInput resets any translation immediately.
        urlInput.requestFocus()
        activity.onImeInsetChangedForTest(imeBottomPx = 520, touchYInWebView = 920f)
        assertEquals(0f, webViewContainer.translationY, 0.5f)
        urlInput.clearFocus()

        // Closing the IME resets translation back to 0.
        activity.onImeInsetChangedForTest(imeBottomPx = 0, touchYInWebView = 920f)
        assertEquals(0f, webViewContainer.translationY, 0.5f)

        // Tapping at the very bottom via dispatchTouchEvent shifts full clamped IME height (-520px).
        val loc = IntArray(2)
        webViewContainer.getLocationInWindow(loc)
        val tapX = 300f
        val tapY = loc[1] + 960f
        val down = MotionEvent.obtain(100L, 100L, MotionEvent.ACTION_DOWN, tapX, tapY, 0)
        val up = MotionEvent.obtain(100L, 120L, MotionEvent.ACTION_UP, tapX, tapY, 0)
        activity.dispatchTouchEvent(down)
        activity.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
        activity.onImeInsetChangedForTest(imeBottomPx = 520)
        assertEquals(-520f, webViewContainer.translationY, 0.5f)

        // Expressive dialogs keep SOFT_INPUT_ADJUST_RESIZE so dialog EditTexts move above the keyboard.
        val dlg = AlertDialog.Builder(activity).setTitle("Test").create()
        dlg.show()
        SnakeTheme.styleExpressiveDialog(dlg, ThemePalette.OBSIDIAN)
        @Suppress("DEPRECATION")
        val expectedAdjust = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        assertEquals(
            expectedAdjust,
            dlg.window?.attributes?.softInputMode?.and(WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        )
        dlg.dismiss()
        ShadowLooper.idleMainLooper()

        ctrl.pause().destroy()
    }

    @Test
    fun `start configure opens search home section downloads precede find in menu and EditText follows palette`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SnakePreferences(ctx)
        prefs.onboardingCompleted = true
        prefs.themePalette = ThemePalette.AMOLED

        val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        ShadowLooper.idleMainLooper()
        val activity = ctrl.get()

        // Blank page Dismiss button label & Set home page deep-link target.
        val dismissBtn = activity.findViewById<android.widget.TextView>(R.id.btnStartDismiss)
        assertEquals("Dismiss", dismissBtn.text.toString())
        activity.findViewById<View>(R.id.btnStartConfigure).performClick()
        val launched = Shadows.shadowOf(activity).nextStartedActivity
        assertNotNull(launched)
        assertEquals(SettingsActivity::class.java.name, launched.component?.className)
        assertEquals(
            SettingsActivity.SECTION_SEARCH_HOME,
            launched.getStringExtra(SettingsActivity.EXTRA_SCROLL_TO_SECTION)
        )

        // Overflow menu puts Downloads directly above Find in page.
        activity.findViewById<android.widget.ImageButton>(R.id.btnMenu).performClick()
        ShadowLooper.idleMainLooper()
        val popup = Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).latestPopupWindow
        assertNotNull(popup)
        val labels = mutableListOf<String>()
        fun collectText(v: View) {
            if (v is android.widget.TextView && v.text != null) labels.add(v.text.toString())
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) collectText(v.getChildAt(i))
            }
        }
        collectText(popup!!.contentView)
        val dlIdx = labels.indexOf(ctx.getString(R.string.btn_downloads))
        val findIdx = labels.indexOf(ctx.getString(R.string.btn_find_in_page))
        assertTrue("Downloads and Find in page must exist in menu", dlIdx >= 0 && findIdx >= 0)
        assertEquals("Downloads must appear directly above Find in page", dlIdx + 1, findIdx)
        popup.dismiss()

        // Active palette styles urlInput & findInput highlight/cursor cleanly instead of leaving Obsidian orange.
        val expectedHighlight = androidx.core.graphics.ColorUtils.setAlphaComponent(
            SnakeTheme.resolveTokens(ThemePalette.AMOLED, ctx).primaryAccent,
            72
        )
        assertEquals(expectedHighlight, activity.findViewById<EditText>(R.id.urlInput).highlightColor)
        assertEquals(expectedHighlight, activity.findViewById<EditText>(R.id.findInput).highlightColor)

        ctrl.pause().destroy()
    }

    @Test
    fun `launchActivity overflow entries start activity before popup dismisses and clean up silently onStop`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        SnakePreferences(ctx).onboardingCompleted = true
        val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        ShadowLooper.idleMainLooper()
        val activity = ctrl.get()

        activity.findViewById<android.widget.ImageButton>(R.id.btnMenu).performClick()
        ShadowLooper.idleMainLooper()
        val popup = Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).latestPopupWindow
        assertNotNull(popup)
        assertTrue(popup!!.isShowing)

        fun findLabelView(v: View, text: String): View? {
            if (v is android.widget.TextView && v.text?.toString() == text) return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    findLabelView(v.getChildAt(i), text)?.let { return it }
                }
            }
            return null
        }

        val settingsRow = findLabelView(popup.contentView, ctx.getString(R.string.btn_settings))!!.parent as View
        settingsRow.performClick()

        // SettingsActivity must be launched IMMEDIATELY without waiting for popup dismissal animation.
        val launched = Shadows.shadowOf(activity).nextStartedActivity
        assertNotNull(launched)
        assertEquals(SettingsActivity::class.java.name, launched.component?.className)
        assertTrue("Popup remains visually on top until Activity transition starts", popup.isShowing)

        // When MainActivity moves to onStop (covered by SettingsActivity), popup closes silently without exit anim.
        ctrl.pause().stop()
        assertFalse(popup.isShowing)
        assertEquals(0, popup.animationStyle)

        ctrl.destroy()
    }

    @Test
    fun `DOM-aware IME elevation handles bottom pill top box send button offscreen hidden_tiny caret tall and wipe`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        SnakePreferences(ctx).onboardingCompleted = true
        val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        ShadowLooper.idleMainLooper()
        val activity = ctrl.get()

        val webViewContainer = activity.findViewById<View>(R.id.webViewContainer)
        webViewContainer.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
        )
        webViewContainer.layout(0, 120, 1080, 1120)

        // 1. Google AI / Gemini pattern: bottom pill elevates even if tap was near top.
        activity.onImeInsetChangedForTest(imeBottomPx = 520, touchYInWebView = 60f)
        activity.onDomInputBoundsForTest("""{"kind":"input","top":820,"bottom":900,"vh":1000}""")
        assertTrue("Bottom DOM composer pill must elevate above IME", webViewContainer.translationY < -300f)

        // 2. Send button clicked (kind=none) keeps existing negative shift until IME closes!
        val prevShift = webViewContainer.translationY
        activity.onDomInputBoundsForTest("""{"kind":"none","vh":1000}""")
        assertEquals(prevShift, webViewContainer.translationY, 0.5f)

        // 3. IME closes -> translationY resets to 0, then kind=none without prior shift stays 0.
        activity.onImeInsetChangedForTest(imeBottomPx = 0)
        assertEquals(0f, webViewContainer.translationY, 0.5f)
        activity.onImeInsetChangedForTest(imeBottomPx = 520, touchYInWebView = -1f)
        activity.onDomInputBoundsForTest("""{"kind":"none","vh":1000}""")
        assertEquals(0f, webViewContainer.translationY, 0.5f)

        // 4. Top search box never unnecessarily shifts even with stale bottom touchY.
        activity.onImeInsetChangedForTest(imeBottomPx = 520, touchYInWebView = 920f)
        activity.onDomInputBoundsForTest("""{"kind":"input","top":12,"bottom":56,"vh":1000}""")
        assertEquals(0f, webViewContainer.translationY, 0.5f)

        // 5. Top safety clamp: tall-ish box starting near top cannot be clipped above 8dp top edge.
        activity.onDomInputBoundsForTest("""{"kind":"input","top":40,"bottom":900,"vh":1000}""")
        val density = activity.resources.displayMetrics.density
        assertTrue(
            "Input top must not be pushed above 8dp top edge: translationY=${webViewContainer.translationY}",
            40f + webViewContainer.translationY >= (8f * density) - 1f
        )

        // 6. Scrolled offscreen -> resets translationY to 0.
        activity.onDomInputBoundsForTest("""{"kind":"offscreen","vh":1000}""")
        assertEquals(0f, webViewContainer.translationY, 0.5f)

        // 7. Hidden 1x1 trap falls back to lastWebViewTouchY.
        activity.onImeInsetChangedForTest(imeBottomPx = 520, touchYInWebView = 940f)
        activity.onDomInputBoundsForTest("""{"kind":"hidden_tiny","vh":1000}""")
        assertTrue("hidden_tiny must fall back to touchY", webViewContainer.translationY < -300f)

        // 8. Large multiline editor (tall) clamps touchY within container: top line stays 0, bottom line shifts.
        activity.onImeInsetChangedForTest(imeBottomPx = 520, touchYInWebView = 150f)
        activity.onDomInputBoundsForTest("""{"kind":"tall","top":100,"bottom":920,"vh":1000}""")
        assertEquals(0f, webViewContainer.translationY, 0.5f)
        activity.onImeInsetChangedForTest(imeBottomPx = 520, touchYInWebView = 880f)
        activity.onDomInputBoundsForTest("""{"kind":"tall","top":100,"bottom":920,"vh":1000}""")
        assertTrue("Bottom tap inside tall editor must elevate", webViewContainer.translationY < -300f)

        // 9. Caret inside tall editor tracks caret bottom rather than top of container.
        activity.onDomInputBoundsForTest("""{"kind":"caret","top":740,"bottom":765,"containerTop":100,"containerBottom":920,"vh":1000}""")
        assertTrue("Lower caret must elevate cleanly", webViewContainer.translationY < -200f)

        // 10. Wiping session clears DOM/IME state cleanly.
        activity.wipeSession(reloadBlank = false, reseed = false)
        assertEquals(0f, webViewContainer.translationY, 0.5f)

        ctrl.pause().destroy()
    }
}
