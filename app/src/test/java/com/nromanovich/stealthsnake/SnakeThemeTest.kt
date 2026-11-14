package com.nromanovich.stealthsnake

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
}
