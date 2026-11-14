package com.nromanovich.stealthsnake

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.webkit.WebResourceRequest
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoreComponentsEdgeCasesTest {

    private val ctx: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        SnakePreferences(ctx).resetToDefaults()
    }

    private fun fakeRequest(url: String, isMainFrame: Boolean): WebResourceRequest {
        val uri = Uri.parse(url)
        return object : WebResourceRequest {
            override fun getUrl(): Uri = uri
            override fun isForMainFrame(): Boolean = isMainFrame
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = false
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
        }
    }

    @Test
    fun `BlocklistEngine handles Uri overload trailing dots case insensitivity and path boundaries`() {
        val engine = BlocklistEngine.fromRules(
            listOf(
                "||Example-Tracker.COM.",
                "path:no-leading-slash",
                "path:/telemetry"
            )
        )
        assertEquals(listOf("||example-tracker.com", "path:/telemetry"), engine.rules)

        val uriWithDot = Uri.parse("https://SUB.EXAMPLE-TRACKER.COM./pixel.gif?a=1")
        assertTrue(engine.isBlocked(uriWithDot, isForMainFrame = false))
        assertFalse(engine.isBlocked(uriWithDot, isForMainFrame = true))
        assertFalse(engine.isBlocked(null as Uri?, isForMainFrame = false))

        assertTrue(engine.isBlocked("https://clean.org/telemetry", isForMainFrame = false))
        assertTrue(engine.isBlocked("https://clean.org/telemetry/v2", isForMainFrame = false))
        assertFalse(engine.isBlocked("https://clean.org/telemetry2", isForMainFrame = false))
    }

    @Test
    fun `SnakeClient respects disabled flags fires page callbacks and passes null on subresource VPN block`() {
        val started = mutableListOf<String>()
        val finished = mutableListOf<String>()
        val vpnBlocked = mutableListOf<String?>()

        val client = SnakeClient(
            blocklist = BlocklistEngine.fromRules(listOf("||tracker.com")),
            blocklistEnabled = false,
            blockExternalSchemes = false,
            vpnKillSwitchEnabled = false,
            onPageStartedCallback = { started.add(it) },
            onPageFinishedCallback = { finished.add(it) },
            onVpnBlockedCallback = { vpnBlocked.add(it) }
        )

        assertNull(client.shouldInterceptRequest(null, fakeRequest("https://tracker.com/a.js", false)))
        assertFalse(client.shouldOverrideUrlLoading(null, fakeRequest("tel:+12345", true)))

        client.onPageStarted(null, "", null)
        client.onPageStarted(null, "https://example.org/", null)
        client.onPageFinished(null, null)
        client.onPageFinished(null, "https://example.org/")
        assertEquals(listOf("https://example.org/"), started)
        assertEquals(listOf("https://example.org/"), finished)

        client.vpnKillSwitchEnabled = true
        client.isVpnActive = { false }
        val subResp = client.shouldInterceptRequest(null, fakeRequest("https://example.org/sub.js", false))
        assertNotNull(subResp)
        assertEquals(403, subResp!!.statusCode)
        assertEquals(listOf<String?>(null), vpnBlocked)

        assertTrue(SnakeClient.isAllowedNavigationUri(Uri.parse("HTTPS://example.org")))
        assertFalse(SnakeClient.isAllowedNavigationUri(Uri.parse("blob:https://example.org/1")))
        assertFalse(SnakeClient.isNetworkUri(Uri.parse("about:blank")))
        assertFalse(SnakeClient.isNetworkUri(Uri.parse("data:text/plain,hi")))
        assertFalse(SnakeClient.isNetworkUri(Uri.parse("blob:https://example.org/1")))
        assertFalse(SnakeClient.isNetworkUri(Uri.parse("javascript:void(0)")))
        assertTrue(SnakeClient.isNetworkUri(Uri.parse("https://example.org")))
    }

    @Test
    fun `SnakeClient prompts on unencrypted http main-frame navigation and delegates SSL errors`() {
        val httpPrompts = mutableListOf<String>()
        var sslCallbackInvoked = false
        val client = SnakeClient(
            blocklist = BlocklistEngine.fromRules(emptyList()),
            shouldPromptHttp = { uri -> uri.host == "insecure.example" },
            onHttpPromptCallback = { httpPrompts.add(it) },
            onSslErrorCallback = { _, _ -> sslCallbackInvoked = true }
        )

        assertTrue(client.shouldOverrideUrlLoading(null, fakeRequest("http://insecure.example/login", true)))
        assertEquals(listOf("http://insecure.example/login"), httpPrompts)

        assertFalse(client.shouldOverrideUrlLoading(null, fakeRequest("http://insecure.example/img.png", false)))
        assertFalse(client.shouldOverrideUrlLoading(null, fakeRequest("https://insecure.example/login", true)))

        client.onReceivedSslError(null, null, null)
        assertTrue(sslCallbackInvoked)
    }

    @Test
    fun `SnakeChrome clamps progress filters empty titles and respects suppressDialogs false`() {
        val progressValues = mutableListOf<Int>()
        val titles = mutableListOf<String>()

        val chrome = SnakeChrome(
            suppressDialogs = false,
            onProgressChangedCallback = { progressValues.add(it) },
            onTitleChangedCallback = { titles.add(it) }
        )

        chrome.onProgressChanged(null, -15)
        chrome.onProgressChanged(null, 42)
        chrome.onProgressChanged(null, 180)
        assertEquals(listOf(0, 42, 100), progressValues)

        chrome.onReceivedTitle(null, null)
        chrome.onReceivedTitle(null, "")
        chrome.onReceivedTitle(null, "Stealth Snake")
        assertEquals(listOf("Stealth Snake"), titles)

        assertFalse(chrome.onJsAlert(null, null, null, null))
        assertFalse(chrome.onJsConfirm(null, null, null, null))
        assertFalse(chrome.onJsPrompt(null, null, null, null, null))
        assertFalse(chrome.onJsBeforeUnload(null, null, null, null))
    }

    @Test(expected = IllegalStateException::class)
    fun `SnakeScript build fails when config token is missing`() {
        SnakeScript.build("const x = 1;", "0123abcd", emptyList())
    }

    @Test
    fun `SnakeScript loadTemplate and FeatureFlags serialize completely`() {
        val template = SnakeScript.loadTemplate(
            ByteArrayInputStream("const CFG = __SS_CONFIG_JSON__;".toByteArray(Charsets.UTF_8))
        )
        val built = SnakeScript.build(
            template = template,
            seed = "0123abcd",
            blocklistRules = listOf("||ads.example"),
            flags = SnakeScript.FeatureFlags(
                noiseCanvasWebgl = false,
                noiseAudio = false,
                hideVisibilityFocus = false,
                lockViewport = false,
                sensorFallback = false,
                fuzzPerformanceNow = false,
                blockWebRtcIce = false,
                blockBeacons = false,
                blockWebSockets = true
            )
        )
        val jsonStr = built.removePrefix("const CFG = ").removeSuffix(";")
        val obj = JSONObject(jsonStr)
        assertEquals("0123abcd", obj.getString("seed"))
        val flags = obj.getJSONObject("flags")
        assertFalse(flags.getBoolean("noiseCanvasWebgl"))
        assertFalse(flags.getBoolean("noiseAudio"))
        assertFalse(flags.getBoolean("hideVisibilityFocus"))
        assertFalse(flags.getBoolean("lockViewport"))
        assertFalse(flags.getBoolean("sensorFallback"))
        assertFalse(flags.getBoolean("fuzzPerformanceNow"))
        assertFalse(flags.getBoolean("blockWebRtcIce"))
        assertFalse(flags.getBoolean("blockBeacons"))
        assertTrue(flags.getBoolean("blockWebSockets"))
    }

    @Test
    fun `MicroJitterController validates ranges and cancels open gesture on stop`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val recordedActions = mutableListOf<Int>()
        val spyView = object : View(activity) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                recordedActions.add(event.actionMasked)
                return true
            }
        }
        activity.setContentView(spyView)
        spyView.layout(0, 0, 300, 600)

        var invalidThrown = false
        try {
            MicroJitterController(spyView, minIdleMs = 0L, maxIdleMs = 100L)
        } catch (_: IllegalArgumentException) {
            invalidThrown = true
        }
        assertTrue(invalidThrown)

        val controller = MicroJitterController(
            targetView = spyView,
            handler = Handler(Looper.getMainLooper()),
            minIdleMs = 50L,
            maxIdleMs = 50L,
            stepDelayMs = 100L
        )
        controller.updateIdleRange(60L, 60L)
        controller.start()

        // Trigger ACTION_DOWN, then stop mid-gesture before ACTION_MOVE fires.
        shadowOf(Looper.getMainLooper()).idleFor(70, TimeUnit.MILLISECONDS)
        assertEquals(listOf(MotionEvent.ACTION_DOWN), recordedActions)

        controller.stop()
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), recordedActions)
        assertFalse(controller.isRunning)
    }

    @Test
    fun `CaptureManager deleteCapture deletes valid file and exportToGallery rejects missing file`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val manager = CaptureManager(activity)
        val capturesDir = File(activity.filesDir, CaptureManager.CAPTURES_DIR_NAME).apply { mkdirs() }
        val validCapture = File(capturesDir, "capture_valid.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertTrue(manager.deleteCapture(validCapture))
        assertFalse(validCapture.exists())
        assertFalse(manager.exportToGallery(File(capturesDir, "nonexistent.png")))

        val view = View(activity)
        activity.setContentView(view)
        view.layout(0, 0, 100, 50)
        val rect = CaptureManager.computeWindowRect(view)
        assertTrue(rect.left >= 0 && rect.top >= 0)
        assertEquals(100, rect.width())
        assertEquals(50, rect.height())

        manager.shutdown()
    }

    @Test
    fun `SnakePreferences enum fallbacks clamping and active profile sync work accurately`() {
        assertEquals(SupportedLocale.EN, SupportedLocale.fromTag(null))
        assertEquals(SupportedLocale.SYSTEM, SupportedLocale.fromTag(SupportedLocale.SYSTEM.tag))
        assertEquals(SupportedLocale.RU, SupportedLocale.fromTag("ru"))

        assertEquals(CaptureMode.ASK, CaptureMode.fromKey("unknown"))
        assertEquals(SearchEngine.DUCKDUCKGO, SearchEngine.fromKey("unknown"))
        assertEquals(ProtectionPreset.MAX_STEALTH, ProtectionPreset.fromKey("unknown"))

        val prefs = SnakePreferences(ctx)
        prefs.textZoomPercent = 50
        assertEquals(85, prefs.textZoomPercent)
        prefs.textZoomPercent = 200
        assertEquals(130, prefs.textZoomPercent)

        prefs.microJitterMinSec = 1
        assertEquals(5, prefs.microJitterMinSec)
        prefs.microJitterMaxSec = 2
        assertEquals(5, prefs.microJitterMaxSec)

        val profile = prefs.createProfileFromCurrent("Work")
        assertEquals(profile.id, prefs.activeProfileId)
        prefs.noiseAudio = false
        val synced = prefs.listProfiles().first { it.id == profile.id }
        assertFalse(synced.noiseAudio)
    }
}
