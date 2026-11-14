package com.nromanovich.stealthsnake

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinLockSecurityTest {

    private val ctx: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        SnakePreferences(ctx).resetToDefaults()
        PinLockCoordinator.resetForTests()
    }

    @Test
    fun `secondary activities enforce PIN overlay when locked and unlock cleanly`() {
        val prefs = SnakePreferences(ctx)
        PinManager.setPin(prefs, "2580")

        val settingsCtrl = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val settingsAct = settingsCtrl.get()
        ShadowLooper.idleMainLooper()

        val contentRoot = settingsAct.findViewById<ViewGroup>(android.R.id.content)
        val settingsContent = contentRoot.getChildAt(0)
        assertEquals(View.INVISIBLE, settingsContent.visibility)
        assertNotNull(findTextViewWithText(contentRoot, ctx.getString(R.string.pin_title)))

        // Unlock with valid PIN restores Settings content
        "2580".forEach { ch -> findTextViewWithText(contentRoot, ch.toString())!!.performClick() }
        val unlockBtn = findButtonWithText(contentRoot, ctx.getString(R.string.pin_unlock))!!
        unlockBtn.performClick()
        ShadowLooper.idleMainLooper()

        assertEquals(View.VISIBLE, settingsContent.visibility)
        assertNull(findTextViewWithText(contentRoot, ctx.getString(R.string.pin_title)))
        settingsCtrl.pause().stop().destroy()

        // CapturesActivity also locks on cold start while PIN enabled
        PinLockCoordinator.resetForTests()
        val capturesCtrl = Robolectric.buildActivity(CapturesActivity::class.java).setup()
        val capturesContent = capturesCtrl.get().findViewById<ViewGroup>(android.R.id.content)
        assertEquals(View.INVISIBLE, capturesContent.getChildAt(0).visibility)
        assertNotNull(findTextViewWithText(capturesContent, ctx.getString(R.string.pin_title)))
        capturesCtrl.pause().destroy()
    }

    @Test
    fun `intra-app navigation does not false-lock after 5s while true app background locks`() {
        var nowMs = 1_000L
        PinLockCoordinator.resetForTests { nowMs }
        val prefs = SnakePreferences(ctx)
        prefs.onboardingCompleted = true
        PinManager.setPin(prefs, "4321")

        // Launch and unlock MainActivity
        val mainCtrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val mainAct = mainCtrl.get()
        val rootFrame = mainAct.findViewById<ViewGroup>(R.id.rootFrame)
        val pinOverlay = rootFrame.getChildAt(rootFrame.childCount - 1)
        "4321".forEach { ch -> findTextViewWithText(pinOverlay, ch.toString())!!.performClick() }
        findButtonWithText(pinOverlay, ctx.getString(R.string.pin_unlock))!!.performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, mainAct.findViewById<View>(R.id.rootContainer).visibility)

        // Start SettingsActivity while MainActivity is still started -> transition pauses Main, starts Settings
        val settingsCtrl = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        mainCtrl.pause().stop() // MainActivity stops AFTER SettingsActivity started

        // Spend 60s inside Settings, then return to MainActivity -> MUST NOT lock
        nowMs += 60_000L
        mainCtrl.restart().start().resume()
        settingsCtrl.pause().stop().destroy()
        ShadowLooper.idleMainLooper()
        assertFalse(PinLockCoordinator.isLocked(prefs))
        assertEquals(View.VISIBLE, mainAct.findViewById<View>(R.id.rootContainer).visibility)

        // Now truly background the whole app (all activities stopped) for 6 seconds -> MUST lock on return
        mainCtrl.pause().stop()
        nowMs += 6_000L
        mainCtrl.restart().start().resume()
        ShadowLooper.idleMainLooper()

        assertTrue(PinLockCoordinator.isLocked(prefs))
        assertEquals(View.INVISIBLE, mainAct.findViewById<View>(R.id.rootContainer).visibility)
        assertEquals(View.INVISIBLE, mainAct.findViewById<WebView>(R.id.webView).visibility)

        mainCtrl.pause().destroy()
    }

    @Test
    fun `MainActivity defers loading homeUrl until PIN is unlocked`() {
        val prefs = SnakePreferences(ctx)
        prefs.onboardingCompleted = true
        prefs.homeUrl = "https://deferred.example.org/"
        PinManager.setPin(prefs, "9988")

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        ShadowLooper.idleMainLooper()

        val webView = activity.findViewById<WebView>(R.id.webView)
        assertNull("WebView must not load homeUrl before PIN unlock", Shadows.shadowOf(webView).lastLoadedUrl)

        val rootFrame = activity.findViewById<ViewGroup>(R.id.rootFrame)
        "9988".forEach { ch -> findTextViewWithText(rootFrame, ch.toString())!!.performClick() }
        findButtonWithText(rootFrame, ctx.getString(R.string.pin_unlock))!!.performClick()
        ShadowLooper.idleMainLooper()

        assertEquals("https://deferred.example.org/", Shadows.shadowOf(webView).lastLoadedUrl)
        controller.pause().destroy()
    }

    @Test
    fun `PinManager verifyPinAsync executes on background executor and delivers result to main thread`() {
        val prefs = SnakePreferences(ctx)
        PinManager.setPin(prefs, "5678")
        val executor = Executors.newSingleThreadExecutor()
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<PinManager.VerifyResult?>()

        PinManager.verifyPinAsync(ctx, prefs, "5678", executor = executor) { res ->
            resultRef.set(res)
            latch.countDown()
        }

        // Wait for background executor to post onto main looper, then idle main looper
        val deadline = System.currentTimeMillis() + 5_000L
        while (latch.count > 0 && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            latch.await(10, TimeUnit.MILLISECONDS)
        }
        executor.shutdownNow()
        assertEquals(PinManager.VerifyResult.Success, resultRef.get())
    }

    @Test
    fun `settings showSetPinDialog validates confirmation PIN match`() {
        val prefs = SnakePreferences(ctx)
        val ctrl = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val act = ctrl.get()
        val root = (act.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)

        findButtonWithText(root, ctx.getString(R.string.pref_pin_set))!!.performClick()
        ShadowLooper.idleMainLooper()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val edits = findAllEditTexts(dialog.window!!.decorView)
        assertEquals(2, edits.size)

        // Mismatch stays open and does not enable PIN
        edits[0].setText("1234")
        edits[1].setText("9999")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertFalse(prefs.pinLockEnabled)

        // Matching pair enables PIN cleanly
        edits[1].setText("1234")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(prefs.pinLockEnabled)
        assertTrue(PinManager.verifyPin(prefs, "1234") is PinManager.VerifyResult.Success)

        ctrl.pause().destroy()
    }

    private fun findAllEditTexts(view: View): List<EditText> = buildList {
        if (view is EditText) add(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) addAll(findAllEditTexts(view.getChildAt(i)))
        }
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

    private fun findTextViewWithText(view: View, text: String): TextView? {
        if (view is TextView && view.text?.toString() == text) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findTextViewWithText(view.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }
}
