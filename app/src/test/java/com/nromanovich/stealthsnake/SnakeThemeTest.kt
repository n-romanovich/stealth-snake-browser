package com.nromanovich.stealthsnake

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SnakeThemeTest {

    @Test
    fun `fromKey resolves all palettes and falls back to OBSIDIAN`() {
        for (palette in ThemePalette.entries) {
            assertEquals(palette, ThemePalette.fromKey(palette.key))
        }
        assertEquals(ThemePalette.OBSIDIAN, ThemePalette.fromKey(null))
        assertEquals(ThemePalette.OBSIDIAN, ThemePalette.fromKey("nonexistent"))
    }

    @Test
    fun `only SLATE_LIGHT is marked as light palette`() {
        for (palette in ThemePalette.entries) {
            if (palette == ThemePalette.SLATE_LIGHT) {
                assertTrue(palette.isLight)
            } else {
                assertFalse(palette.isLight)
            }
        }
    }

    @Test
    fun `roundedSurface produces GradientDrawable with requested radius`() {
        val drawable = SnakeTheme.roundedSurface(Color.BLACK, Color.WHITE, 14f, 2f)
        assertEquals(28f, drawable.cornerRadius, 0.01f)
    }

    @Test
    fun `resolveTokens gives distinct M3 tonal palettes for every built-in theme and warm obsidian fallback`() {
        val obsidian = SnakeTheme.resolveTokens(ThemePalette.OBSIDIAN)
        val amoled = SnakeTheme.resolveTokens(ThemePalette.AMOLED)
        val midnight = SnakeTheme.resolveTokens(ThemePalette.MIDNIGHT)
        val emerald = SnakeTheme.resolveTokens(ThemePalette.EMERALD)
        val light = SnakeTheme.resolveTokens(ThemePalette.SLATE_LIGHT)

        // Warm obsidian must use amber/orange tones (higher red than blue), not cold blue
        assertTrue(Color.red(obsidian.primaryAccent) > Color.blue(obsidian.primaryAccent))
        assertTrue(Color.red(obsidian.categoryHeader) > Color.blue(obsidian.categoryHeader))
        // Midnight coral vs Emerald mint vs Obsidian amber all have distinct accents
        assertNotEquals(obsidian.primaryAccent, midnight.primaryAccent)
        assertNotEquals(obsidian.primaryAccent, emerald.primaryAccent)
        assertNotEquals(midnight.primaryAccent, amoled.primaryAccent)
        assertFalse(obsidian.isLight)
        assertTrue(light.isLight)
    }

    @Test
    fun `useDynamicTheme defaults true clears on explicit theme selection and falls back safely in Robolectric`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SnakePreferences(ctx)
        assertTrue(prefs.useDynamicTheme)
        assertFalse(SnakeTheme.isDynamicColorAvailable())
        assertEquals(ThemePalette.OBSIDIAN.bg, SnakeTheme.resolveBrowserColors(ctx).bg)

        prefs.themePalette = ThemePalette.MIDNIGHT
        assertFalse(prefs.useDynamicTheme)
        assertEquals(ThemePalette.MIDNIGHT.bg, SnakeTheme.resolveBrowserColors(ctx).bg)

        prefs.enableDynamicTheme()
        assertTrue(prefs.useDynamicTheme)
        prefs.themePalette = ThemePalette.AMOLED
        prefs.resetToDefaults()
        assertTrue(prefs.useDynamicTheme)
    }

    @Test
    fun `pin dot indicator uses solid dots only without star glyph`() {
        assertEquals(listOf(PinLockCoordinator.PinDotState.FILLED_DOT), PinLockCoordinator.PinDotState.entries)
    }

    @Test
    fun `styleExpressiveDialog applies Material 3 buttonPanel insets and button spacing`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val density = activity.resources.displayMetrics.density
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.compat_warn_title)
            .setMessage(R.string.compat_warn_body)
            .setPositiveButton(R.string.compat_warn_enable, null)
            .setNegativeButton(R.string.action_cancel, null)
            .setNeutralButton(R.string.compat_warn_guide, null)
            .show()

        SnakeTheme.styleExpressiveDialog(dialog, ThemePalette.OBSIDIAN)

        val buttonPanel = dialog.findViewById<ViewGroup>(androidx.appcompat.R.id.buttonPanel)!!
        assertEquals((24 * density).toInt(), buttonPanel.paddingLeft)
        assertEquals((16 * density).toInt(), buttonPanel.paddingTop)
        assertEquals((24 * density).toInt(), buttonPanel.paddingRight)
        assertEquals((20 * density).toInt(), buttonPanel.paddingBottom)
        assertFalse(buttonPanel.clipToPadding)

        val posBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)!!
        val negBtn = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)!!
        val neuBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)!!
        assertEquals((16 * density).toInt(), posBtn.paddingLeft)
        assertEquals((16 * density).toInt(), neuBtn.paddingLeft)

        val buttonBar = posBtn.parent as LinearLayout
        assertEquals(0, buttonBar.paddingLeft)
        assertEquals(0, buttonBar.paddingBottom)
        assertEquals((8 * density).toInt(), (posBtn.layoutParams as ViewGroup.MarginLayoutParams).marginStart)
        assertEquals(0, (negBtn.layoutParams as ViewGroup.MarginLayoutParams).marginStart)

        val messageTv = dialog.findViewById<View>(android.R.id.message)!!
        assertEquals((24 * density).toInt(), messageTv.paddingLeft)
        assertEquals((4 * density).toInt(), messageTv.paddingBottom)

        dialog.dismiss()
    }

    @Test
    fun `siteFixesActivity keeps technical cause collapsed until card is tapped`() {
        val controller = Robolectric.buildActivity(SiteFixesActivity::class.java).setup()
        val activity = controller.get()
        val root = (activity.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0) as ViewGroup

        fun findRecycler(view: View): RecyclerView? {
            if (view is RecyclerView) return view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    findRecycler(view.getChildAt(i))?.let { return it }
                }
            }
            return null
        }

        fun findTextParent(view: View, target: String): View? {
            if (view is TextView && view.text.toString() == target) return view.parent as? View
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    findTextParent(view.getChildAt(i), target)?.let { return it }
                }
            }
            return null
        }

        val recycler = findRecycler(root)!!
        assertTrue(recycler.childCount > 0)
        val firstCardSlot = recycler.getChildAt(0) as ViewGroup
        val firstCard = firstCardSlot.getChildAt(0)
        val causeBox = findTextParent(firstCard, activity.getString(R.string.fixes_label_cause))!!
        assertEquals(View.GONE, causeBox.visibility)

        firstCard.performClick()
        assertEquals(View.VISIBLE, causeBox.visibility)

        firstCard.performClick()
        assertEquals(View.GONE, causeBox.visibility)

        controller.pause().destroy()
    }

    @Test
    fun `pinLockCoordinator unlock button uses Material 3 Expressive pill proportions`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val density = activity.resources.displayMetrics.density
        val prefs = SnakePreferences(activity)
        val overlay = PinLockCoordinator.createPinOverlayView(
            activity = activity,
            prefs = prefs,
            onUnlocked = {},
            onAutoWipe = {}
        )

        fun findMaterialButton(view: View, text: String): MaterialButton? {
            if (view is MaterialButton && view.text.toString() == text) return view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    findMaterialButton(view.getChildAt(i), text)?.let { return it }
                }
            }
            return null
        }

        val unlockBtn = findMaterialButton(overlay, activity.getString(R.string.pin_unlock))!!
        assertEquals((50 * density).toInt(), unlockBtn.layoutParams.height)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, unlockBtn.layoutParams.width)
        assertEquals((180 * density).toInt(), unlockBtn.minimumWidth)
        assertEquals((25 * density).toInt(), unlockBtn.cornerRadius)
        assertEquals(15f, unlockBtn.textSize / activity.resources.displayMetrics.scaledDensity, 0.5f)
    }
}
