package com.nromanovich.stealthsnake

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SnakePreferencesTest {

    private lateinit var prefs: SnakePreferences

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        prefs = SnakePreferences(ctx)
        prefs.resetToDefaults()
    }

    @Test
    fun `defaults match maximum stealth and presets toggle protections cleanly`() {
        assertEquals(ProtectionPreset.MAX_STEALTH, prefs.preset)
        assertEquals(CaptureMode.ASK, prefs.captureMode)
        assertTrue(prefs.noiseCanvasWebgl)
        assertTrue(prefs.microJitterEnabled)

        prefs.applyPreset(ProtectionPreset.COMPATIBILITY)
        assertEquals(ProtectionPreset.COMPATIBILITY, prefs.preset)
        assertFalse(prefs.noiseCanvasWebgl)
        assertFalse(prefs.noiseAudio)
        assertFalse(prefs.microJitterEnabled)
        assertTrue(prefs.builtinBlocklistEnabled)

        prefs.noiseCanvasWebgl = true
        assertEquals(ProtectionPreset.CUSTOM, prefs.preset)

        prefs.resetToDefaults()
        assertEquals(ProtectionPreset.MAX_STEALTH, prefs.preset)
        assertTrue(prefs.noiseCanvasWebgl)
    }

    @Test
    fun `buildSearchOrUrl handles URLs, search queries, and unsafe schemes`() {
        assertEquals(
            "https://duckduckgo.com/?q=what%20is%20my%20ip",
            SnakePreferences.buildSearchOrUrl("what is my ip", SearchEngine.DUCKDUCKGO)
        )
        assertEquals(
            "https://example.org",
            SnakePreferences.buildSearchOrUrl("example.org", SearchEngine.DUCKDUCKGO)
        )
        assertNull(SnakePreferences.buildSearchOrUrl("javascript:alert(1)", SearchEngine.DUCKDUCKGO))
        assertNull(SnakePreferences.buildSearchOrUrl("file:///etc/passwd", SearchEngine.DUCKDUCKGO))
    }

    @Test
    fun `parsedCustomRules filters invalid lines and normalizes domains`() {
        prefs.customBlocklistRaw = """
            # comment
            ||Tracker.Example.COM.
            path:/collect
            invalid_rule
        """.trimIndent()

        assertEquals(
            listOf("||tracker.example.com", "path:/collect"),
            prefs.parsedCustomRules()
        )
    }

    @Test
    fun `theme palettes, downloads default off, and onboarding flags reset cleanly`() {
        assertEquals(ThemePalette.OBSIDIAN, prefs.themePalette)
        assertFalse(prefs.downloadsEnabled)
        assertFalse(prefs.startHintDismissed)
        assertFalse(prefs.onboardingCompleted)

        prefs.themePalette = ThemePalette.SLATE_LIGHT
        assertTrue(prefs.themePalette.isLight)
        prefs.downloadsEnabled = true
        prefs.startHintDismissed = true
        prefs.onboardingCompleted = true

        prefs.resetToDefaults()
        assertEquals(ThemePalette.OBSIDIAN, prefs.themePalette)
        assertFalse(prefs.themePalette.isLight)
        assertFalse(prefs.downloadsEnabled)
        assertFalse(prefs.startHintDismissed)
        assertFalse(prefs.onboardingCompleted)
    }

    @Test
    fun `DownloadHelper sanitizes traversal filenames and rejects unsafe schemes`() {
        val sanitized = DownloadHelper.sanitizeFileName("../../etc/passwd")
        assertFalse(sanitized.contains(".."))
        assertFalse(sanitized.contains("/"))
        assertEquals("etc_passwd", sanitized)
        assertEquals("report_2026.pdf", DownloadHelper.sanitizeFileName("report 2026.pdf"))
        assertTrue(DownloadHelper.sanitizeFileName("...").startsWith("download_"))

        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val helper = DownloadHelper(ctx)
        var result: Result<String>? = null
        helper.download("file:///etc/passwd", null, null, null) { result = it }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(result!!.isFailure)
        helper.shutdown()
    }

    @Test
    fun `custom profiles snapshot, sync edits, switch cleanly, and delete`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SnakePreferences(ctx)
        prefs.resetToDefaults()
        prefs.themePalette = ThemePalette.EMERALD
        prefs.homeUrl = "https://duckduckgo.com"

        // Reject blank profile name
        var threw = false
        try {
            prefs.createProfileFromCurrent("   ")
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)

        // Create profile and tweak specific flags inside it
        val bank = prefs.createProfileFromCurrent("  Bank  ")
        assertEquals("Bank", bank.name)
        assertEquals(bank.id, prefs.activeProfileId)

        prefs.noiseCanvasWebgl = false
        prefs.blockWebRtcIce = false
        prefs.customBlocklistRaw = "||ads.bank"

        // Switch back to MAX_STEALTH
        prefs.applyPreset(ProtectionPreset.MAX_STEALTH)
        assertEquals(null, prefs.activeProfileId)
        assertTrue(prefs.noiseCanvasWebgl)
        assertTrue(prefs.blockWebRtcIce)

        // Re-apply Bank profile: its synced edits must be restored without altering global homeUrl/theme
        assertTrue(prefs.applyProfile(bank.id))
        assertEquals(bank.id, prefs.activeProfileId)
        assertFalse(prefs.noiseCanvasWebgl)
        assertFalse(prefs.blockWebRtcIce)
        assertEquals("||ads.bank", prefs.customBlocklistRaw)
        assertEquals(ThemePalette.EMERALD, prefs.themePalette)
        assertEquals("https://duckduckgo.com", prefs.homeUrl)

        assertFalse(prefs.applyProfile("missing-id"))
        assertTrue(prefs.deleteProfile(bank.id))
        assertEquals(null, prefs.activeProfileId)
        assertTrue(prefs.listProfiles().isEmpty())
    }

    @Test
    fun `profile edge cases - duplicates, newlines, corrupt json, stale id, and non-active deletion`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SnakePreferences(ctx)
        prefs.resetToDefaults()

        // Whitespace/newline normalization & deduplication (case-insensitive)
        val p1 = prefs.createProfileFromCurrent("Bank\n\tProfile")
        assertEquals("Bank Profile", p1.name)
        val p2 = prefs.createProfileFromCurrent("bank profile")
        assertEquals("bank profile (2)", p2.name)
        val p3 = prefs.createProfileFromCurrent("Bank Profile")
        assertEquals("Bank Profile (3)", p3.name)

        // Very long name capped at 32 chars even with dedupe suffix
        val long1 = prefs.createProfileFromCurrent("A".repeat(50))
        val long2 = prefs.createProfileFromCurrent("A".repeat(50))
        assertEquals(32, long1.name.length)
        assertTrue(long2.name.length <= 32)
        assertTrue(long2.name.endsWith("(2)"))

        // Deleting a non-active profile keeps the active one intact
        assertTrue(prefs.applyProfile(p1.id))
        assertTrue(prefs.deleteProfile(p2.id))
        assertEquals(p1.id, prefs.activeProfileId)

        // Force-dark & custom blocklist sync inside active profile
        prefs.forceDarkPages = true
        prefs.applyPreset(ProtectionPreset.MAX_STEALTH)
        prefs.forceDarkPages = false
        assertTrue(prefs.applyProfile(p1.id))
        assertTrue(prefs.forceDarkPages)

        // Corrupt JSON handling
        val rawPrefs = ctx.getSharedPreferences("stealth_snake_prefs", Context.MODE_PRIVATE)
        rawPrefs.edit()
            .putString("custom_profiles_json", "{not_valid_json")
            .putString("active_profile_id", "stale-id")
            .apply()
        assertTrue(prefs.listProfiles().isEmpty())
        prefs.noiseAudio = false // triggers syncActiveProfileIfAny -> clears stale activeProfileId
        assertEquals(null, prefs.activeProfileId)
    }

    @Test
    fun `custom search engine normalizes templates and rejects unsafe schemes`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SnakePreferences(ctx)
        prefs.resetToDefaults()

        prefs.searchEngine = SearchEngine.CUSTOM
        prefs.customSearchTemplate = "https://searx.be/search?q=%s"
        assertEquals(
            "https://searx.be/search?q=privacy%20browser",
            SnakePreferences.buildSearchOrUrl("privacy browser", prefs.searchEngine, prefs.customSearchTemplate)
        )

        prefs.customSearchTemplate = "searx.be/search?q="
        assertEquals("https://searx.be/search?q=", prefs.customSearchTemplate)
        assertEquals(
            "https://searx.be/search?q=privacy",
            SnakePreferences.buildSearchOrUrl("privacy", prefs.searchEngine, prefs.customSearchTemplate)
        )

        prefs.customSearchTemplate = "javascript:alert(1)"
        assertEquals("", prefs.customSearchTemplate)
        assertEquals(null, SnakePreferences.buildSearchOrUrl("test", prefs.searchEngine, prefs.customSearchTemplate))
    }
}
