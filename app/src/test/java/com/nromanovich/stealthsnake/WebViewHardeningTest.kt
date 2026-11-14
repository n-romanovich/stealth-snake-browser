package com.nromanovich.stealthsnake

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
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
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebViewHardeningTest {

    private val ctx: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ShadowAlertDialog.reset()
        SnakePreferences(ctx).resetToDefaults()
    }

    private fun fakeRequest(url: String): WebResourceRequest {
        val uri = Uri.parse(url)
        return object : WebResourceRequest {
            override fun getUrl(): Uri = uri
            override fun isForMainFrame(): Boolean = true
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = true
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
        }
    }

    private fun setVpnConnected(connected: Boolean) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = ShadowNetworkCapabilities.newInstance()
        if (connected) {
            Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            Shadows.shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } else {
            Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        }
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    @Test
    fun `MainActivity confirms safe external schemes when allowed and always blocks javascript scheme`() {
        val prefs = SnakePreferences(ctx)
        prefs.onboardingCompleted = true
        prefs.blockExternalSchemes = false
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val webView = activity.findViewById<WebView>(R.id.webView)
        val client = webView.webViewClient

        assertTrue(client.shouldOverrideUrlLoading(webView, fakeRequest("javascript:alert(1)")))
        ShadowLooper.idleMainLooper()
        assertEquals(null, ShadowAlertDialog.getLatestDialog())

        assertTrue(client.shouldOverrideUrlLoading(webView, fakeRequest("tel:+123456789")))
        ShadowLooper.idleMainLooper()
        val dialog = ShadowAlertDialog.getLatestDialog()
        assertNotNull(dialog)
        assertTrue(dialog!!.isShowing)
        dialog.dismiss()
        controller.pause().destroy()
    }

    @Test
    fun `MainActivity recovers cleanly by recreating WebView when renderer process dies`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val originalWebView = activity.findViewById<WebView>(R.id.webView)
        @Suppress("DEPRECATION")
        val detail = object : RenderProcessGoneDetail() {
            override fun didCrash(): Boolean = true
            override fun rendererPriorityAtExit(): Int = 0
        }

        val handled = originalWebView.webViewClient.onRenderProcessGone(originalWebView, detail)
        ShadowLooper.idleMainLooper()

        assertTrue(handled)
        assertFalse(activity.isFinishing)
        val replacementWebView = activity.findViewById<WebView>(R.id.webView)
        assertNotNull(replacementWebView)
        assertNotSame(originalWebView, replacementWebView)
        assertEquals(
            ctx.getString(R.string.renderer_crashed_notice),
            activity.findViewById<TextView>(R.id.statusLabel).text.toString()
        )
        controller.pause().destroy()
    }

    @Test
    fun `MainActivity with vpnBypassStartCheck updates kill-switch state across VPN transitions`() {
        val prefs = SnakePreferences(ctx)
        prefs.vpnKillSwitchEnabled = true
        prefs.vpnBypassStartCheck = true
        setVpnConnected(false)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val client = activity.findViewById<WebView>(R.id.webView).webViewClient as SnakeClient

        assertFalse(VpnGuard.isVpnActive(ctx))
        assertTrue(client.vpnKillSwitchEnabled)

        setVpnConnected(true)
        controller.pause().resume()
        assertTrue(VpnGuard.isVpnActive(ctx))

        controller.pause().destroy()
    }
}
