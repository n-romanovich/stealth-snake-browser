package com.nromanovich.stealthsnake

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.ScrollView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsAndThemeLifecycleTest {

    private val ctx: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ShadowAlertDialog.reset()
        SnakePreferences(ctx).apply {
            resetToDefaults()
            onboardingCompleted = true
        }
    }

    @Test
    fun `SnakePreferences cross-clamps microJitter min and max consistently`() {
        val prefs = SnakePreferences(ctx)
        prefs.microJitterMinSec = 45
        assertEquals(45, prefs.microJitterMinSec)
        assertEquals(45, prefs.microJitterMaxSec)

        prefs.microJitterMaxSec = 10
        assertEquals(45, prefs.microJitterMaxSec)
    }

    @Test
    fun `SettingsActivity saves text on blur exposes jitter steppers SYSTEM locale and preserves scroll on theme change`() {
        val prefs = SnakePreferences(ctx)
        val ctrl = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = ctrl.get()
        val root = (activity.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)

        val editTexts = collectViews(root) { it is EditText }.filterIsInstance<EditText>()
        assertTrue(editTexts.isNotEmpty())
        val homeEdit = editTexts.first()
        homeEdit.requestFocus()
        homeEdit.setText("news.ycombinator.com")
        // Not saved yet mid-typing before blur/pause
        assertEquals(MainActivity.DEFAULT_START_URL, prefs.homeUrl)
        homeEdit.clearFocus()
        assertEquals("https://news.ycombinator.com", prefs.homeUrl)

        val minPlus = findViewWithContentDesc(root, "${ctx.getString(R.string.pref_jitter_min)} +")!!
        val beforeMin = prefs.microJitterMinSec
        minPlus.performClick()
        assertEquals(beforeMin + 1, prefs.microJitterMinSec)

        val systemRadio = collectViews(root) {
            it is RadioButton && it.text?.toString() == ctx.getString(R.string.lang_system)
        }.firstOrNull()
        assertNotNull("SYSTEM locale option must be exposed in Settings", systemRadio)

        val scrolls = collectViews(root) { it is ScrollView }.filterIsInstance<ScrollView>()
        val contentScroll = scrolls.last()
        contentScroll.scrollTo(0, 420)
        assertEquals(420, contentScroll.scrollY)

        val amoledRadio = collectViews(root) {
            it is RadioButton && it.text?.toString() == ctx.getString(R.string.theme_amoled)
        }.firstOrNull() as RadioButton
        amoledRadio.performClick()
        ShadowLooper.idleMainLooper()

        assertEquals(ThemePalette.AMOLED, prefs.themePalette)
        assertEquals(420, contentScroll.scrollY)

        ctrl.pause().destroy()
    }

    @Test
    fun `CaptureManager computeWindowRect clamps coordinates within laid out rootView bounds`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val root = FrameLayout(activity)
        val child = View(activity)
        root.addView(child)
        activity.setContentView(root)
        val decor = child.rootView
        decor.layout(0, 0, 200, 300)
        root.layout(0, 0, 200, 300)
        child.layout(50, 40, 260, 350)

        val rect = CaptureManager.computeWindowRect(child)
        assertEquals(50, rect.left)
        assertEquals(40, rect.top)
        assertEquals(200, rect.right)
        assertEquals(300, rect.bottom)
    }

    @Test
    fun `secondary activities refresh theme palette on resume`() {
        val prefs = SnakePreferences(ctx)
        prefs.themePalette = ThemePalette.OBSIDIAN

        val downloadsCtrl = Robolectric.buildActivity(DownloadsActivity::class.java).setup()
        val capturesCtrl = Robolectric.buildActivity(CapturesActivity::class.java).setup()
        val fixesCtrl = Robolectric.buildActivity(SiteFixesActivity::class.java).setup()

        prefs.themePalette = ThemePalette.SLATE_LIGHT
        downloadsCtrl.pause().resume()
        capturesCtrl.pause().resume()
        fixesCtrl.pause().resume()

        val dRoot = (downloadsCtrl.get().findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        val cRoot = (capturesCtrl.get().findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        val fRoot = (fixesCtrl.get().findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)

        assertEquals(ThemePalette.SLATE_LIGHT.bg, (dRoot.background as ColorDrawable).color)
        assertEquals(ThemePalette.SLATE_LIGHT.bg, (cRoot.background as ColorDrawable).color)
        assertEquals(ThemePalette.SLATE_LIGHT.bg, (fRoot.background as ColorDrawable).color)

        downloadsCtrl.pause().destroy()
        capturesCtrl.pause().destroy()
        fixesCtrl.pause().destroy()

        val offlineDir = File(ctx.filesDir, OfflinePagesManager.PAGES_DIR_NAME).apply { mkdirs() }
        File(offlineDir, "sample.mht").writeText("From: <Saved>")
        val viewerIntent = Intent(ctx, OfflineViewerActivity::class.java).apply {
            putExtra(OfflineViewerActivity.EXTRA_FILE_NAME, "sample.mht")
            putExtra(OfflineViewerActivity.EXTRA_PAGE_TITLE, "Sample")
        }
        prefs.themePalette = ThemePalette.OBSIDIAN
        val viewerCtrl = Robolectric.buildActivity(OfflineViewerActivity::class.java, viewerIntent).setup()
        prefs.themePalette = ThemePalette.MIDNIGHT
        viewerCtrl.pause().resume()
        val vRoot = (viewerCtrl.get().findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        assertEquals(ThemePalette.MIDNIGHT.bg, (vRoot.background as ColorDrawable).color)
        viewerCtrl.pause().destroy()
        offlineDir.deleteRecursively()
    }

    private fun collectViews(root: View, predicate: (View) -> Boolean): List<View> {
        val found = mutableListOf<View>()
        if (predicate(root)) found.add(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                found.addAll(collectViews(root.getChildAt(i), predicate))
            }
        }
        return found
    }

    private fun findViewWithContentDesc(view: View, desc: String): View? {
        if (view.contentDescription?.toString() == desc) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findViewWithContentDesc(view.getChildAt(i), desc)?.let { return it }
            }
        }
        return null
    }
}
