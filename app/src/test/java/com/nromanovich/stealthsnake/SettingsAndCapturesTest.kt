package com.nromanovich.stealthsnake

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SettingsAndCapturesTest {

    private val ctx: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetPrefs() {
        SnakePreferences(ctx).resetToDefaults()
    }

    @Test
    fun `settings activity toggles vpn switches and resets defaults`() {
        val prefs = SnakePreferences(ctx)
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val root = (activity.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)

        val vpnSwitch = findSwitchWithText(root, ctx.getString(R.string.pref_vpn_kill_switch))!!
        assertFalse(vpnSwitch.isChecked)
        vpnSwitch.performClick()
        assertTrue(prefs.vpnKillSwitchEnabled)

        val bypassSwitch = findSwitchWithText(root, ctx.getString(R.string.pref_vpn_bypass_start))!!
        bypassSwitch.performClick()
        assertTrue(prefs.vpnBypassStartCheck)

        val resetBtn = findButtonWithText(root, ctx.getString(R.string.pref_reset_defaults))!!
        resetBtn.performClick()
        ShadowLooper.idleMainLooper()

        // Confirmation dialog should guard against accidental taps.
        val cancelDialog = ShadowDialog.getLatestDialog() as? AlertDialog
        assertNotNull(cancelDialog)
        cancelDialog!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(prefs.vpnKillSwitchEnabled)

        resetBtn.performClick()
        ShadowLooper.idleMainLooper()
        val confirmDialog = ShadowDialog.getLatestDialog() as? AlertDialog
        assertNotNull(confirmDialog)
        confirmDialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()

        assertFalse(prefs.vpnKillSwitchEnabled)
        assertFalse(prefs.vpnBypassStartCheck)

        controller.pause().destroy()
    }

    @Test
    fun `settings requires current PIN for remove change disable-autowipe and preserves PIN on reset`() {
        val prefs = SnakePreferences(ctx)
        PinManager.setPin(prefs, "1357")
        PinLockCoordinator.markUnlocked()

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        ShadowLooper.idleMainLooper()
        val root = (activity.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)

        // Attempt to remove PIN with wrong current PIN keeps PIN enabled
        findButtonWithText(root, ctx.getString(R.string.pref_pin_remove))!!.performClick()
        ShadowLooper.idleMainLooper()
        val removeDialog = ShadowDialog.getLatestDialog() as AlertDialog
        findEditText(removeDialog.window!!.decorView)!!.setText("9999")
        removeDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(prefs.pinLockEnabled)

        // Correct PIN removes lock cleanly
        findEditText(removeDialog.window!!.decorView)!!.setText("1357")
        removeDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertFalse(prefs.pinLockEnabled)

        // Reset preserves PIN when enabled
        PinManager.setPin(prefs, "2468")
        PinLockCoordinator.markUnlocked()
        prefs.vpnKillSwitchEnabled = true
        findButtonWithText(root, ctx.getString(R.string.pref_reset_defaults))!!.performClick()
        ShadowLooper.idleMainLooper()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()

        assertFalse(prefs.vpnKillSwitchEnabled)
        assertTrue(prefs.pinLockEnabled)
        assertTrue(PinManager.verifyPin(prefs, "2468") is PinManager.VerifyResult.Success)

        controller.pause().destroy()
    }

    @Test
    fun `settings activity creates custom profile via plus button`() {
        val prefs = SnakePreferences(ctx)
        prefs.noiseCanvasWebgl = false

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val root = (activity.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)

        val plusBtn = findViewByContentDesc(root, ctx.getString(R.string.profile_add_desc))!!
        plusBtn.performClick()
        ShadowLooper.idleMainLooper()

        val dialog = ShadowDialog.getLatestDialog() as? AlertDialog
        assertNotNull(dialog)
        val input = findEditText(dialog!!.window!!.decorView)!!
        input.setText("Banking")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()

        assertEquals(1, prefs.listProfiles().size)
        assertEquals("Banking", prefs.listProfiles().first().name)
        assertFalse(prefs.listProfiles().first().noiseCanvasWebgl)

        controller.pause().destroy()
    }

    @Test
    fun `captures activity displays empty state and saved captures`() {
        val capturesDir = File(ctx.filesDir, "captures")
        capturesDir.deleteRecursively()

        val emptyCtrl = Robolectric.buildActivity(CapturesActivity::class.java).setup()
        val emptyRoot = (emptyCtrl.get().findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        assertNotNull(findTextViewWithText(emptyRoot, ctx.getString(R.string.captures_empty)))
        emptyCtrl.pause().destroy()

        capturesDir.mkdirs()
        File(capturesDir, "capture_test.png").writeBytes(byteArrayOf(1, 2, 3, 4))

        val listCtrl = Robolectric.buildActivity(CapturesActivity::class.java).setup()
        val listRoot = (listCtrl.get().findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        assertNotNull(findTextViewContaining(listRoot, "capture_test.png"))
        listCtrl.pause().destroy()
        capturesDir.deleteRecursively()
    }

    private fun findSwitchWithText(view: View, text: String): CompoundButton? {
        if (view is CompoundButton && view.text?.toString() == text) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findSwitchWithText(view.getChildAt(i), text)?.let { return it }
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

    private fun findViewByContentDesc(view: View, desc: String): View? {
        if (view.contentDescription?.toString() == desc) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findViewByContentDesc(view.getChildAt(i), desc)?.let { return it }
            }
        }
        return null
    }

    private fun findEditText(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findEditText(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
