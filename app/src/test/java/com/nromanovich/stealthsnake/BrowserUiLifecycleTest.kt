package com.nromanovich.stealthsnake

import android.content.Context
import android.net.Uri
import android.net.http.SslError
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
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
}
