package com.nromanovich.stealthsnake

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinOfflineReaderAndDownloadsTest {

    private val ctx: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        SnakePreferences(ctx).resetToDefaults()
        File(ctx.filesDir, OfflinePagesManager.PAGES_DIR_NAME).deleteRecursively()
        DownloadHistoryStore(ctx).clear()
    }

    @Test
    fun `PinManager validates formats hashes with salt and enforces auto-wipe threshold`() {
        assertFalse(PinManager.isValidPinFormat("123"))
        assertFalse(PinManager.isValidPinFormat("123456789"))
        assertFalse(PinManager.isValidPinFormat("12a4"))
        assertTrue(PinManager.isValidPinFormat("1234"))
        assertTrue(PinManager.isValidPinFormat("12345678"))

        val prefs = SnakePreferences(ctx)
        PinManager.setPin(prefs, "2580")
        assertTrue(prefs.pinLockEnabled)
        assertTrue(prefs.pinSalt.isNotEmpty())
        assertTrue(prefs.pinHash.isNotEmpty())

        assertTrue(PinManager.verifyPin(prefs, "2580") is PinManager.VerifyResult.Success)

        for (attempt in 1 until PinManager.MAX_FAILED_ATTEMPTS) {
            val res = PinManager.verifyPin(prefs, "0000")
            assertTrue(res is PinManager.VerifyResult.Failed)
            assertEquals(attempt, (res as PinManager.VerifyResult.Failed).attemptsUsed)
            assertEquals(PinManager.MAX_FAILED_ATTEMPTS - attempt, res.remaining)
        }

        val wipeRes = PinManager.verifyPin(prefs, "0000")
        assertTrue(wipeRes is PinManager.VerifyResult.TriggerAutoWipe)
        assertEquals(0, prefs.pinFailedAttempts)

        // When auto-wipe is disabled, 5th failure stays Failed(remaining=0)
        prefs.pinAutoWipeOnFail = false
        repeat(PinManager.MAX_FAILED_ATTEMPTS) {
            val r = PinManager.verifyPin(prefs, "9999")
            assertTrue(r is PinManager.VerifyResult.Failed)
        }

        PinManager.clearPin(prefs)
        assertFalse(prefs.pinLockEnabled)
        assertEquals("", prefs.pinHash)
    }

    @Test
    fun `PinManager background timeout check behaves accurately`() {
        assertFalse(PinManager.shouldLockAfterBackground(false, 0L, 10_000L))
        assertTrue(PinManager.shouldLockAfterBackground(true, 0L, 10_000L))
        assertFalse(PinManager.shouldLockAfterBackground(true, 10_000L, 12_000L))
        assertTrue(PinManager.shouldLockAfterBackground(true, 10_000L, 15_000L))
    }

    @Test
    fun `DownloadHistoryStore records lists deletes and clears entries`() {
        val store = DownloadHistoryStore(ctx)
        assertTrue(store.list().isEmpty())

        val e1 = store.record("doc1.pdf", "application/pdf", true, 1000L)
        val e2 = store.record("img.png", "image/png", false, 2000L)

        val list = store.list()
        assertEquals(2, list.size)
        assertEquals("img.png", list[0].fileName)
        assertFalse(list[0].success)
        assertEquals("doc1.pdf", list[1].fileName)
        assertTrue(list[1].success)

        assertTrue(store.delete(e1.id))
        assertEquals(1, store.list().size)
        assertFalse(store.delete("nonexistent-id"))

        store.clear()
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `OfflinePagesManager registers lists resolves safely and deletes archives`() {
        val manager = OfflinePagesManager(ctx)
        val dir = File(ctx.filesDir, OfflinePagesManager.PAGES_DIR_NAME).apply { mkdirs() }
        val file = File(dir, "sample.mht").apply { writeText("From: <Saved>") }

        val saved = manager.registerSavedFile("Example Article", "https://example.org/post", file)
        assertNotNull(saved)
        val listed = manager.listPages()
        assertEquals(1, listed.size)
        assertEquals("Example Article", listed[0].title)

        // Path traversal must be rejected
        assertNull(manager.resolvePageFile("../secret.txt"))
        assertNull(manager.resolvePageFile("sub/sample.mht"))
        assertNotNull(manager.resolvePageFile("sample.mht"))

        assertTrue(manager.deletePage(saved!!.id))
        assertFalse(file.exists())
        assertTrue(manager.listPages().isEmpty())
    }

    @Test
    fun `ReaderModeHelper parses escaped WebView evaluateJavascript output`() {
        assertNull(ReaderModeHelper.parseEvaluateResult(null))
        assertNull(ReaderModeHelper.parseEvaluateResult("null"))

        val json = """{"title":"Privacy Guide","byline":"example.org","paragraphs":["First paragraph of sufficient length.","Second paragraph of sufficient length."]}"""
        val escaped = org.json.JSONObject.quote(json)
        val article = ReaderModeHelper.parseEvaluateResult(escaped)
        assertNotNull(article)
        assertEquals("Privacy Guide", article!!.title)
        assertEquals("example.org", article.byline)
        assertEquals(2, article.paragraphs.size)
        assertFalse(article.isEmpty)
    }

    @Test
    fun `SiteInfoSheet toggles custom blocklist rules and renders page security state`() {
        val prefs = SnakePreferences(ctx)
        SiteInfoSheet.toggleCustomRule(prefs, "||tracker.example")
        assertTrue("||tracker.example" in prefs.parsedCustomRules())

        SiteInfoSheet.toggleCustomRule(prefs, "||tracker.example")
        assertFalse("||tracker.example" in prefs.parsedCustomRules())

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowAlertDialog.reset()
        val dialog = SiteInfoSheet.show(
            activity = activity,
            prefs = prefs,
            state = SiteInfoSheet.PageSecurityState(
                url = "https://news.example/article",
                vpnKillSwitchEnabled = true,
                vpnActive = true,
                httpBypassed = false,
                sslBypassed = false,
                blockedHosts = mapOf("ads.example" to 3)
            ),
            onProfileOrBlocklistChanged = {}
        )
        assertTrue(dialog.isShowing)
        val decor = dialog.window!!.decorView
        assertNotNull(findTextViewContaining(decor, "news.example"))
        assertNotNull(findTextViewContaining(decor, "ads.example (3)"))
        dialog.dismiss()
    }

    @Test
    fun `DownloadsActivity shows disabled notice and opens Settings scrolled to Downloads`() {
        val prefs = SnakePreferences(ctx)
        prefs.downloadsEnabled = false
        DownloadHistoryStore(ctx).record("archive.zip", "application/zip", true)

        val controller = Robolectric.buildActivity(DownloadsActivity::class.java).setup()
        val activity = controller.get()
        val root = (activity.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)

        assertNotNull(findTextViewWithText(root, ctx.getString(R.string.downloads_disabled_notice)))
        assertNotNull(findTextViewContaining(root, "archive.zip"))

        val enableBtn = findButtonWithText(root, ctx.getString(R.string.downloads_enable_in_settings))!!
        enableBtn.performClick()
        val started = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(SettingsActivity::class.java.name, started.component?.className)
        assertEquals(
            SettingsActivity.SECTION_DOWNLOADS,
            started.getStringExtra(SettingsActivity.EXTRA_SCROLL_TO_SECTION)
        )
        controller.pause().destroy()

        // Verify SettingsActivity handles EXTRA_SCROLL_TO_SECTION cleanly
        val settingsIntent = Intent(ctx, SettingsActivity::class.java).apply {
            putExtra(SettingsActivity.EXTRA_SCROLL_TO_SECTION, SettingsActivity.SECTION_DOWNLOADS)
        }
        val settingsCtrl = Robolectric.buildActivity(SettingsActivity::class.java, settingsIntent).setup()
        ShadowLooper.idleMainLooper()
        settingsCtrl.pause().destroy()
    }

    @Test
    fun `MainActivity enforces PIN lock overlay on launch and wipes session after 5 failed attempts`() {
        val prefs = SnakePreferences(ctx)
        prefs.onboardingCompleted = true
        prefs.pinAutoWipeOnFail = true
        PinManager.setPin(prefs, "4321")
        CookieManager.getInstance().setCookie("https://example.org", "session=secret")

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        ShadowLooper.idleMainLooper()

        val webView = activity.findViewById<WebView>(R.id.webView)
        assertEquals(View.INVISIBLE, webView.visibility)

        val rootFrame = activity.findViewById<ViewGroup>(R.id.rootFrame)
        assertNotNull(findTextViewWithText(rootFrame, ctx.getString(R.string.pin_title)))

        val unlockBtn = findButtonWithText(rootFrame, ctx.getString(R.string.pin_unlock))!!

        // 5 failed attempts trigger auto-wipe and unlock clean session
        repeat(PinManager.MAX_FAILED_ATTEMPTS) {
            "0000".forEach { ch -> findTextViewWithText(rootFrame, ch.toString())!!.performClick() }
            unlockBtn.performClick()
            ShadowLooper.idleMainLooper()
        }

        assertEquals(View.VISIBLE, webView.visibility)
        assertNull(CookieManager.getInstance().getCookie("https://example.org"))

        controller.pause().destroy()
    }

    @Test
    fun `MainActivity 5 wrong PINs also wipe captures offline pages downloads and homeUrl`() {
        val prefs = SnakePreferences(ctx)
        prefs.onboardingCompleted = true
        prefs.pinAutoWipeOnFail = true
        prefs.homeUrl = "https://sensitive.example.org"
        prefs.customBlocklistRaw = "||secret.example.org"
        prefs.autoClearCapturesOnWipe = false
        prefs.autoClearOfflineOnWipe = false
        PinManager.setPin(prefs, "4321")

        val capturesDir = File(ctx.filesDir, "captures").apply { mkdirs() }
        File(capturesDir, "secret.png").writeBytes(byteArrayOf(1, 2, 3))
        val offlineDir = File(ctx.filesDir, OfflinePagesManager.PAGES_DIR_NAME).apply { mkdirs() }
        File(offlineDir, "page.mht").writeText("secret")
        OfflinePagesManager(ctx).registerSavedFile("Title", "https://sensitive.example.org", File(offlineDir, "page.mht"))
        DownloadHistoryStore(ctx).record("secret.pdf", "application/pdf", true)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        ShadowLooper.idleMainLooper()

        // Home URL must NOT load while PIN lock is active
        val webView = activity.findViewById<WebView>(R.id.webView)
        assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.rootContainer).visibility)
        assertEquals(null, webView.url)

        val rootFrame = activity.findViewById<ViewGroup>(R.id.rootFrame)
        val unlockBtn = findButtonWithText(rootFrame, ctx.getString(R.string.pin_unlock))!!
        repeat(PinManager.MAX_FAILED_ATTEMPTS) {
            "0000".forEach { ch -> findTextViewWithText(rootFrame, ch.toString())!!.performClick() }
            unlockBtn.performClick()
            ShadowLooper.idleMainLooper()
        }

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.rootContainer).visibility)
        assertEquals(0, capturesDir.listFiles()?.size ?: 0)
        assertTrue(OfflinePagesManager(ctx).listPages().isEmpty())
        assertTrue(DownloadHistoryStore(ctx).list().isEmpty())
        assertEquals(MainActivity.DEFAULT_START_URL, SnakePreferences(ctx).homeUrl)
        assertEquals("", SnakePreferences(ctx).customBlocklistRaw)

        controller.pause().destroy()
    }

    @Test
    fun `MainActivity siteSecurityIcon opens SiteInfoSheet on click`() {
        val prefs = SnakePreferences(ctx)
        prefs.onboardingCompleted = true
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        ShadowLooper.idleMainLooper()
        ShadowAlertDialog.reset()

        val icon = activity.findViewById<ImageView>(R.id.siteSecurityIcon)
        icon.performClick()
        ShadowLooper.idleMainLooper()

        val shown = ShadowAlertDialog.getShownDialogs().filter { it.isShowing }
        assertEquals(1, shown.size)
        shown.first().dismiss()
        controller.pause().destroy()
    }

    private fun findTextViewWithText(view: View, text: String): TextView? {
        if (view is TextView && view.text?.toString() == text) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findTextViewWithText(view.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }

    private fun findTextViewContaining(view: View, substring: String): TextView? {
        if (view is TextView && view.text?.toString()?.contains(substring) == true) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findTextViewContaining(view.getChildAt(i), substring)?.let { return it }
            }
        }
        return null
    }

    private fun findButtonWithText(view: View, text: String): Button? {
        if (view is Button && view.text?.toString() == text) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findButtonWithText(view.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }

    private fun findEditTextIn(view: View, hint: String): EditText? {
        if (view is EditText && view.hint?.toString() == hint) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findEditTextIn(view.getChildAt(i), hint)?.let { return it }
            }
        }
        return null
    }
}
