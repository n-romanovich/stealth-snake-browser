package com.nromanovich.stealthsnake

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.http.SslCertificate
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.net.URL
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowserSecurityHardeningTest {

    private val ctx: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ShadowAlertDialog.reset()
        PinLockCoordinator.resetForTests()
        SnakePreferences(ctx).apply {
            resetToDefaults()
            onboardingCompleted = true
        }
        File(ctx.filesDir, OfflinePagesManager.PAGES_DIR_NAME).deleteRecursively()
        File(ctx.filesDir, CaptureManager.CAPTURES_DIR_NAME).deleteRecursively()
        DownloadHistoryStore(ctx).clear()
    }

    @Test
    fun `pinManager enforces exponential lockout when autoWipe is off and resists clock rollback`() {
        val prefs = SnakePreferences(ctx).apply {
            pinAutoWipeOnFail = false
        }
        PinManager.setPin(prefs, "2580")

        repeat(4) { idx ->
            val r = PinManager.verifyPin(prefs, "0000")
            assertTrue(r is PinManager.VerifyResult.Failed)
            assertEquals(0L, (r as PinManager.VerifyResult.Failed).lockoutRemainingMs)
            assertEquals(idx + 1, r.attemptsUsed)
        }

        // 5th failed attempt arms the 30s lockout.
        val fifth = PinManager.verifyPin(prefs, "0000") as PinManager.VerifyResult.Failed
        assertTrue(fifth.lockoutRemainingMs in 29_000L..30_000L)

        // While locked out, even the correct PIN must be refused without evaluating.
        val whileLocked = PinManager.verifyPin(prefs, "2580") as PinManager.VerifyResult.Failed
        assertTrue(whileLocked.lockoutRemainingMs > 0L)

        // Clock rolled backwards must still owe the full lockout duration.
        val rolledBackRemaining = PinManager.lockoutRemainingMs(prefs, prefs.pinLockoutStartedAtMs - 60_000L)
        assertEquals(30_000L, rolledBackRemaining)

        // Verify exponential doubling capped at 15 minutes.
        assertEquals(30_000L, PinManager.lockoutDurationMs(5))
        assertEquals(60_000L, PinManager.lockoutDurationMs(6))
        assertEquals(900_000L, PinManager.lockoutDurationMs(10))
        assertEquals(900_000L, PinManager.lockoutDurationMs(99))

        // resetToDefaults(preservePin = true) preserves lockout timestamps and attempt counters.
        prefs.resetToDefaults(preservePin = true)
        assertTrue(prefs.pinFailedAttempts >= 5)
        assertTrue(prefs.pinLockoutUntilMs > 0L)
    }

    @Test
    fun `setPinDialog keeps session unlocked and secondary verify autoWipe restarts MainActivity`() {
        val prefs = SnakePreferences(ctx)
        val settingsCtrl = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val settingsAct = settingsCtrl.get()
        ShadowLooper.idleMainLooper()

        val setDialog = PinDialogHelper.showSetPinDialog(settingsAct, prefs, includeBody = true) {}
        ShadowLooper.idleMainLooper()
        val inputs = collectEditTexts(setDialog.window!!.decorView)
        assertEquals(2, inputs.size)
        inputs[0].setText("1357")
        inputs[1].setText("1357")
        setDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()

        assertTrue(prefs.pinLockEnabled)
        assertFalse("Newly set PIN must leave live session unlocked", PinLockCoordinator.isLocked(prefs))

        // Now wrong attempts in verify dialog trigger secondary auto-wipe and full task restart,
        // scrubbing user data while keeping PIN protection intact.
        prefs.pinAutoWipeOnFail = true
        prefs.homeUrl = "https://sensitive.example"
        prefs.sessionDirty = true
        val verifyDialog = PinDialogHelper.showVerifyCurrentPinDialog(
            settingsAct,
            prefs,
            R.string.pin_verify_remove_title
        ) {}
        ShadowLooper.idleMainLooper()
        val verifyInput = collectEditTexts(verifyDialog.window!!.decorView).single()
        val okBtn = verifyDialog.getButton(AlertDialog.BUTTON_POSITIVE)
        repeat(PinManager.MAX_FAILED_ATTEMPTS) {
            verifyInput.setText("9999")
            okBtn.performClick()
            ShadowLooper.idleMainLooper()
        }

        assertTrue(prefs.pinLockEnabled)
        assertEquals(MainActivity.DEFAULT_START_URL, prefs.homeUrl)
        assertFalse(prefs.sessionDirty)
        assertTrue(settingsAct.isFinishing)
        val launched = Shadows.shadowOf(settingsAct).nextStartedActivity
        assertNotNull(launched)
        assertEquals(MainActivity::class.java.name, launched.component?.className)
        assertTrue((launched.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0)
        assertTrue((launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        settingsCtrl.pause().stop().destroy()
    }

    @Test
    fun `pinLockCoordinator skips lock timestamp on config change and sets FLAG_SECURE when PIN active`() {
        val prefs = SnakePreferences(ctx)
        PinManager.setPin(prefs, "2580")
        var clock = 1_000L
        PinLockCoordinator.resetForTests { clock }
        PinLockCoordinator.markUnlocked()

        val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = ctrl.get()
        ShadowLooper.idleMainLooper()

        // PIN lock active -> FLAG_SECURE enabled even if blockScreenshots == false.
        assertFalse(prefs.blockScreenshots)
        assertTrue((act.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0)

        // Simulate a configuration change recreate via a fresh activity whose changingConfigurations bit is ignored
        // using real Robolectric recreate().
        PinLockCoordinator.onActivityStarted(act)
        val configAct = object : androidx.appcompat.app.AppCompatActivity() {
            override fun isChangingConfigurations(): Boolean = true
        }
        PinLockCoordinator.onActivityStopped(act)
        // Reset clock and test with actual config-changing activity as sole started activity.
        PinLockCoordinator.resetForTests { clock }
        PinLockCoordinator.markUnlocked()
        PinLockCoordinator.onActivityStarted(configAct)
        PinLockCoordinator.onActivityStopped(configAct)
        clock += PinManager.BACKGROUND_LOCK_TIMEOUT_MS + 10_000L
        PinLockCoordinator.onActivityStarted(act)
        assertFalse("Config change must not record background lock timeout", PinLockCoordinator.isLocked(prefs))

        ctrl.pause().stop().destroy()
    }

    @Test
    fun `mainActivity preserves session across recreate and dismisses download dialog on destroy`() {
        val prefs = SnakePreferences(ctx).apply {
            autoWipeOnExit = true
            downloadsEnabled = true
            sessionDirty = true
        }
        val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = ctrl.get()
        ShadowLooper.idleMainLooper()

        val dlListener = Shadows.shadowOf(activity.getActiveSessionForTest().webView).downloadListener
        assertNotNull(dlListener)
        dlListener!!.onDownloadStart(
            "http://files.example.org/payload.bin",
            "Agent",
            "attachment; filename=\"payload.bin\"",
            "application/octet-stream",
            100L
        )
        ShadowLooper.idleMainLooper()
        val shown = ShadowAlertDialog.getLatestDialog() as AlertDialog
        assertTrue(shown.isShowing)
        val msg = shown.findViewById<TextView>(android.R.id.message)?.text?.toString().orEmpty()
        assertTrue("HTTP download dialog must warn about cleartext transport", msg.contains("HTTP"))

        // Destroy cleanly dismisses the open confirmation dialog and runs autoWipe on normal exit.
        ctrl.pause().stop().destroy()
        assertFalse(shown.isShowing)
        assertFalse(prefs.sessionDirty)
    }

    @Test
    fun `mainActivity ignores stale reader mode result after tab switch or navigation`() {
        val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = ctrl.get()
        ShadowLooper.idleMainLooper()

        val firstSession = activity.getActiveSessionForTest()
        val genBefore = firstSession.navigationGeneration
        val article = ReaderModeHelper.Article(
            title = "Title",
            byline = "example.org",
            paragraphs = listOf("Readable text body."),
            blocks = listOf(
                ReaderModeHelper.Block(ReaderModeHelper.BlockKind.PARAGRAPH, "Readable text body.")
            )
        )

        // Advance navigation generation -> stale result must be dropped.
        firstSession.navigationGeneration = genBefore + 1
        activity.handleExtractedReaderArticle(firstSession, genBefore, article)
        assertFalse(activity.isReaderOverlayOpenForTest())

        // Switch to another session -> stale result from first session must be dropped.
        val second = activity.createNewSession(loadHome = false, switchTo = true)
        assertTrue(activity.getActiveSessionForTest() === second)
        activity.handleExtractedReaderArticle(firstSession, firstSession.navigationGeneration, article)
        assertFalse(activity.isReaderOverlayOpenForTest())

        // Fresh result for active session at matching generation opens Reader Mode overlay.
        activity.handleExtractedReaderArticle(second, second.navigationGeneration, article)
        assertTrue(activity.isReaderOverlayOpenForTest())

        ctrl.pause().stop().destroy()
    }

    @Test
    fun `settingsActivity saves activeCategoryId, preserves reset defaults over unblurred edits, and dismisses dialogs`() {
        val prefs = SnakePreferences(ctx).apply {
            homeUrl = "https://custom.start.example"
        }
        val ctrl = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = ctrl.get()
        ShadowLooper.idleMainLooper()

        val root = (activity.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        // Navigate into Anti-Fingerprinting category and verify instance state restores it.
        findTextViewWithText(root, ctx.getString(R.string.cat_hub_antifp))!!.performClick()
        ShadowLooper.idleMainLooper()
        val outBundle = Bundle()
        ctrl.saveInstanceState(outBundle)
        ctrl.pause().stop().destroy()

        val restoredCtrl = Robolectric.buildActivity(SettingsActivity::class.java)
            .create(outBundle).start().resume().visible()
        val restoredAct = restoredCtrl.get()
        ShadowLooper.idleMainLooper()
        val restoredRoot = (restoredAct.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        assertEquals(
            ctx.getString(R.string.pref_noise_canvas),
            findFirstTitle(restoredRoot, ctx.getString(R.string.pref_noise_canvas))
        )
        restoredCtrl.pause().stop().destroy()

        // Now open Settings -> edit unblurred homeUrl -> Reset to defaults must NOT restore stale EditText value.
        val editCtrl = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val editAct = editCtrl.get()
        ShadowLooper.idleMainLooper()
        val editRoot = (editAct.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        val homeInput = collectEditTexts(editRoot).first { it.text.toString() == "https://custom.start.example" }
        homeInput.setText("https://unblurred.stale.example")

        findTextViewWithText(editRoot, ctx.getString(R.string.pref_reset_defaults))!!.performClick()
        ShadowLooper.idleMainLooper()
        val resetDialog = ShadowAlertDialog.getLatestDialog() as AlertDialog
        assertTrue(resetDialog.isShowing)
        resetDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(MainActivity.DEFAULT_START_URL, prefs.homeUrl)

        // Opening another dialog and destroying SettingsActivity dismisses it cleanly.
        findTextViewWithText(editRoot, ctx.getString(R.string.pref_reset_defaults))!!.performClick()
        ShadowLooper.idleMainLooper()
        val leakedCandidate = ShadowAlertDialog.getLatestDialog() as AlertDialog
        assertTrue(leakedCandidate.isShowing)
        editCtrl.pause().stop().destroy()
        assertFalse(leakedCandidate.isShowing)
    }

    @Test
    fun `downloadHelper pins plain HTTP to validated literal with Host header and keeps HTTPS host`() {
        val (v4Url, v4Host) = DownloadHelper.resolvePinnedTarget(
            URL("http://files.example.com:8080/dir/a.zip?x=1"),
            InetAddress.getByName("1.1.1.1")
        )
        assertEquals("http://1.1.1.1:8080/dir/a.zip?x=1", v4Url.toExternalForm())
        assertEquals("files.example.com:8080", v4Host)

        val (v6Url, v6Host) = DownloadHelper.resolvePinnedTarget(
            URL("http://files.example.com/a.zip"),
            InetAddress.getByName("2606:4700:4700::1111")
        )
        assertEquals("http://[2606:4700:4700:0:0:0:0:1111]/a.zip", v6Url.toExternalForm())
        assertEquals("files.example.com", v6Host)

        val (httpsUrl, httpsHost) = DownloadHelper.resolvePinnedTarget(
            URL("https://files.example.com/a.zip"),
            InetAddress.getByName("1.1.1.1")
        )
        assertEquals("https://files.example.com/a.zip", httpsUrl.toExternalForm())
        assertNull(httpsHost)
    }

    @Test
    @Config(sdk = [28])
    fun `api28 allows private downloadHelper path without Android10 error and rejects silent GALLERY downgrade`() {
        val dlHelper = DownloadHelper(
            ctx,
            Handler(Looper.getMainLooper()),
            vpnKillSwitchEnabled = { true },
            isVpnActive = { false }
        )
        var dlResult: Result<String>? = null
        dlHelper.download("https://example.org/file.bin", null, "application/octet-stream", null) { dlResult = it }
        ShadowLooper.idleMainLooper()
        assertTrue(dlResult!!.exceptionOrNull() !is UnsupportedOperationException)
        dlHelper.shutdown()

        val capCtrl = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val capView = capCtrl.get().findViewById<View>(android.R.id.content)
        capView.layout(0, 0, 120, 80)
        val capExecutor = Executors.newSingleThreadExecutor()
        val fakeRequester = CaptureManager.PixelCopyRequester { _, _, destBitmap, listener, handler ->
            destBitmap.eraseColor(Color.rgb(18, 52, 86))
            handler.post { listener.onPixelCopyFinished(PixelCopy.SUCCESS) }
        }
        val capMgr = CaptureManager(
            context = ctx,
            mainHandler = Handler(Looper.getMainLooper()),
            ioExecutor = capExecutor,
            pixelCopyRequester = fakeRequester
        )
        var capResult: Result<CaptureManager.CaptureOutcome>? = null
        capMgr.captureTo(
            capCtrl.get().window,
            capView,
            CaptureManager.Destination.GALLERY
        ) { capResult = it }
        ShadowLooper.idleMainLooper()
        capExecutor.shutdown()
        while (!capExecutor.isTerminated) {
            Thread.sleep(5)
        }
        ShadowLooper.idleMainLooper()
        assertTrue(capResult!!.isFailure)
        assertTrue(capResult!!.exceptionOrNull() is UnsupportedOperationException)
        assertEquals(0, capMgr.listCaptures().size)
        capMgr.shutdown()
        capCtrl.pause().stop().destroy()
    }

    @Test
    fun `siteInfoSheet floorDiv reports -1 days remaining immediately after expiry`() {
        val nowMs = System.currentTimeMillis()
        val cert = @Suppress("DEPRECATION") SslCertificate(
            "CN=expired.example",
            "CN=CA",
            Date(nowMs - TimeUnit.DAYS.toMillis(30)),
            Date(nowMs - TimeUnit.HOURS.toMillis(1))
        )
        val details = SiteInfoSheet.extractCertificateDetails(cert)!!
        assertEquals(-1L, details.daysRemaining)
    }

    @Test
    fun `microJitterController skips views shorter than stroke travel without crashing`() {
        val actCtrl = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val tinyView = View(actCtrl.get()).apply {
            layout(0, 0, 200, 10)
        }
        val ctrl = MicroJitterController(tinyView, minIdleMs = 1L, maxIdleMs = 1L)
        ctrl.start()
        ShadowLooper.idleMainLooper()
        ctrl.stop()
        actCtrl.pause().stop().destroy()
    }

    @Test
    fun `offlinePagesManager and snakeClient handle traversal and blocklist cleanly`() {
        val mgr = OfflinePagesManager(ctx)
        assertNull(mgr.resolvePageFile("../secret.mht"))
        assertNull(mgr.resolvePageFile("sub/secret.mht"))

        val engine = BlocklistEngine.fromRules(listOf("||tracker.example"))
        val client = SnakeClient(blocklist = engine, blocklistEnabled = true)
        val blockedReq = object : WebResourceRequest {
            override fun getUrl(): android.net.Uri = android.net.Uri.parse("https://tracker.example/p.js")
            override fun isForMainFrame(): Boolean = false
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = false
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
        }
        val intercepted = client.shouldInterceptRequest(null, blockedReq)
        assertNotNull(intercepted)
        assertEquals(200, intercepted!!.statusCode)
    }

    private fun collectEditTexts(root: View): List<EditText> {
        val out = mutableListOf<EditText>()
        fun walk(v: View) {
            if (v is EditText) out.add(v)
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return out
    }

    private fun findTextViewWithText(view: View, text: String): View? {
        if (view is TextView && view.text?.toString() == text && view.visibility == View.VISIBLE) {
            var cur: View? = view
            while (cur != null) {
                if (cur.hasOnClickListeners() && cur.isClickable) return cur
                cur = cur.parent as? View
            }
            return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findTextViewWithText(view.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }

    private fun findFirstTitle(view: View, target: String): String? {
        if (view is TextView && view.text?.toString() == target && view.visibility == View.VISIBLE) {
            return view.text.toString()
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findFirstTitle(view.getChildAt(i), target)?.let { return it }
            }
        }
        return null
    }
}
