package com.nromanovich.stealthsnake

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivacyWipeAndUrlTest {

    private val ctx: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        SnakePreferences(ctx).resetToDefaults()
        PinLockCoordinator.resetForTests()
        File(ctx.filesDir, OfflinePagesManager.PAGES_DIR_NAME).deleteRecursively()
        DownloadHistoryStore(ctx).clear()
    }

    @Test
    fun `autoClearOfflineOnWipe controls whether wipeSession keeps or clears offline archives`() {
        val prefs = SnakePreferences(ctx)
        prefs.onboardingCompleted = true

        val offlineDir = File(ctx.filesDir, OfflinePagesManager.PAGES_DIR_NAME).apply { mkdirs() }
        val file1 = File(offlineDir, "saved1.mht").apply { writeText("offline1") }
        val offlineMgr = OfflinePagesManager(ctx)
        offlineMgr.registerSavedFile("Doc 1", "https://example.org/doc1", file1)
        DownloadHistoryStore(ctx).record("report.pdf", "application/pdf", true)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        // Default autoClearOfflineOnWipe=false preserves offline archives & download list on normal wipe
        prefs.autoClearOfflineOnWipe = false
        activity.wipeSession(reloadBlank = false, reseed = false)
        assertEquals(1, offlineMgr.listPages().size)
        assertEquals(1, DownloadHistoryStore(ctx).list().size)

        // When enabled, normal wipe removes both
        prefs.autoClearOfflineOnWipe = true
        activity.wipeSession(reloadBlank = false, reseed = false)
        assertTrue(offlineMgr.listPages().isEmpty())
        assertTrue(DownloadHistoryStore(ctx).list().isEmpty())

        controller.pause().destroy()
    }

    @Test
    fun `URL normalization and search reject non-http schemes and keep localhost with ports`() {
        // Unsafe/external schemes rejected
        assertNull(SnakePreferences.normalizeUrl("mailto:user@example.org"))
        assertNull(SnakePreferences.normalizeUrl("tel:+15551234"))
        assertNull(SnakePreferences.normalizeUrl("vbscript:msgbox(1)"))
        assertNull(SnakePreferences.normalizeUrl("about:config"))
        assertNull(SnakePreferences.normalizeUrl("http:example.org"))
        assertNull(SnakePreferences.normalizeUrl("https://"))

        // buildSearchOrUrl handles schemes vs host:port vs SearchEngine.OFF
        assertNull(SnakePreferences.buildSearchOrUrl("mailto:user@example.org", SearchEngine.DUCKDUCKGO))
        assertNull(SnakePreferences.buildSearchOrUrl("tel:123456", SearchEngine.DUCKDUCKGO))
        assertNull(SnakePreferences.buildSearchOrUrl("about:settings", SearchEngine.DUCKDUCKGO))
        assertNull(SnakePreferences.buildSearchOrUrl("singleword", SearchEngine.OFF))
        assertEquals(
            "https://localhost:8080/api",
            SnakePreferences.buildSearchOrUrl("localhost:8080/api", SearchEngine.OFF)
        )
        assertEquals(
            "https://example.org:8443/app",
            SnakePreferences.buildSearchOrUrl("example.org:8443/app", SearchEngine.OFF)
        )
    }
}
