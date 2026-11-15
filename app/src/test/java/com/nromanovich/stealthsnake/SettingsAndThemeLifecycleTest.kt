package com.nromanovich.stealthsnake

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import java.util.concurrent.TimeUnit

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
        ShadowLooper.idleMainLooper()
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

    @Test
    fun `SettingsActivity hides detail scroll on hub transitions back without window bleed-through and pads for IME`() {
        val info = ctx.packageManager.getActivityInfo(ComponentName(ctx, SettingsActivity::class.java), 0)
        @Suppress("DEPRECATION")
        val expectedAdjust = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        assertEquals(expectedAdjust, info.softInputMode)

        val ctrl = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        ShadowLooper.idleMainLooper()
        val activity = ctrl.get()
        val root = (activity.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)

        val hubScroll = collectViews(root) { it is NestedScrollView }.first() as NestedScrollView
        val contentScroll = collectViews(root) { it is ScrollView }.last() as ScrollView
        val container = contentScroll.getChildAt(0) as ViewGroup

        // Starting on the Settings Hub keeps the 9 hidden detail categories unmeasured (View.GONE).
        assertEquals(View.VISIBLE, hubScroll.visibility)
        assertEquals(View.GONE, contentScroll.visibility)
        val initialBottomPad = container.paddingBottom

        // Opening a category shows contentScroll and hides hubScroll.
        val presetsCardTitle = collectViews(hubScroll) {
            it is TextView && it.text?.toString() == ctx.getString(R.string.cat_hub_presets)
        }.first()
        (presetsCardTitle.parent.parent as View).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(View.GONE, hubScroll.visibility)
        assertEquals(View.VISIBLE, contentScroll.visibility)

        // Pressing Back immediately hides contentScroll and keeps hubScroll opaque (no bleed-through).
        val backBtn = collectViews(root) {
            it is ImageButton && it.contentDescription?.toString() == ctx.getString(R.string.action_cancel)
        }.first()
        backBtn.performClick()
        assertEquals(View.GONE, contentScroll.visibility)
        assertEquals(View.VISIBLE, hubScroll.visibility)
        assertEquals(1f, hubScroll.alpha, 0.001f)
        ShadowLooper.idleMainLooper()

        // Dispatching IME insets adds keyboard height to scrollable container padding.
        val imeInsets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 480))
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, imeInsets)
        assertEquals(initialBottomPad + 480, container.paddingBottom)

        val zeroInsets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 0))
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, zeroInsets)
        assertEquals(initialBottomPad, container.paddingBottom)

        ctrl.pause().destroy()
    }

    @Test
    fun `default locale is English deep-link opens Search and Start page and search highlight brightens without dimming`() {
        val prefs = SnakePreferences(ctx)
        assertEquals(SupportedLocale.EN, prefs.locale)
        prefs.locale = SupportedLocale.RU
        prefs.resetToDefaults()
        assertEquals(SupportedLocale.EN, prefs.locale)

        val deepLinkIntent = Intent(ctx, SettingsActivity::class.java).apply {
            putExtra(SettingsActivity.EXTRA_SCROLL_TO_SECTION, SettingsActivity.SECTION_SEARCH_HOME)
        }
        val ctrl = Robolectric.buildActivity(SettingsActivity::class.java, deepLinkIntent).setup()
        ShadowLooper.idleMainLooper()
        val activity = ctrl.get()
        val root = (activity.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)

        val hubScroll = collectViews(root) { it is NestedScrollView }.first() as NestedScrollView
        val contentScroll = collectViews(root) { it is ScrollView }.last() as ScrollView
        assertEquals(View.GONE, hubScroll.visibility)
        assertEquals(View.VISIBLE, contentScroll.visibility)

        val homeTitle = collectViews(contentScroll) {
            it is TextView && it.text?.toString() == ctx.getString(R.string.pref_home_url)
        }.first()
        val homeCard = homeTitle.parent.parent as View
        // Highlight uses foreground overlay without reducing card alpha.
        assertEquals(1f, homeCard.alpha, 0.001f)

        // Triggering search entry highlights target card with lighter foreground overlay, never alpha dimming.
        val searchBtn = collectViews(root) {
            it is ImageButton && it.contentDescription?.toString() == ctx.getString(R.string.settings_search_hint)
        }.first()
        searchBtn.performClick()
        ShadowLooper.idleMainLooper()

        val searchEntryTitle = collectViews(root) {
            it is TextView && it.text?.toString() == ctx.getString(R.string.pref_force_dark)
        }.last()
        val animField = SettingsActivity::class.java.getDeclaredField("activeHighlightAnimator").apply {
            isAccessible = true
        }
        val animBefore = animField.get(activity)
        (searchEntryTitle.parent.parent as View).performClick()
        ShadowLooper.idleMainLooper()
        val animAfterSearch = animField.get(activity) as? android.animation.ValueAnimator
        assertTrue(
            "Selecting a search result must trigger a fresh highlight pulse",
            animAfterSearch != null && animAfterSearch !== animBefore
        )

        val forceDarkTitle = collectViews(contentScroll) {
            it is TextView && it.text?.toString() == ctx.getString(R.string.pref_force_dark)
        }.first()
        val forceDarkCard = forceDarkTitle.parent.parent as View
        assertEquals(1f, forceDarkCard.alpha, 0.001f)
        assertNull("Completed highlight must restore card foreground", forceDarkCard.foreground)

        // Drive highlight overlay deterministically before Choreographer finishes the frame in Robolectric.
        val pulseMethod = SettingsActivity::class.java.getDeclaredMethod(
            "pulseViewHighlight",
            View::class.java,
            ThemePalette::class.java
        ).apply { isAccessible = true }
        pulseMethod.invoke(activity, forceDarkCard, ThemePalette.OBSIDIAN)

        assertEquals(1f, forceDarkCard.alpha, 0.001f)
        val fg = forceDarkCard.foreground as? GradientDrawable
        assertNotNull("Highlighted setting card must attach rounded foreground overlay", fg)

        val anim = animField.get(activity) as android.animation.ValueAnimator
        anim.currentPlayTime = 225L
        val fgColor = fg!!.color!!.defaultColor
        val fgAlpha = Color.alpha(fgColor)
        assertTrue("Expected luminous positive highlight alpha mid-pulse, was $fgAlpha", fgAlpha > 0)
        val lum = androidx.core.graphics.ColorUtils.calculateLuminance(
            androidx.core.graphics.ColorUtils.setAlphaComponent(fgColor, 255)
        )
        assertTrue("Highlight overlay must use a light luminous tint, was luminance=$lum", lum > 0.45)

        ShadowLooper.idleMainLooper()
        assertNull("Completed highlight must restore card foreground", forceDarkCard.foreground)

        ctrl.pause().destroy()
    }

    @Test
    fun `SettingsActivity defers category sub-screens until after first frame while building on demand immediately`() {
        // Case 1: Initial create/start/resume renders Hub cards immediately, defers 9 hidden categories until Frame 1 settles.
        val ctrl = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        val activity = ctrl.get()
        val root = (activity.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        val hubScroll = collectViews(root) { it is NestedScrollView }.first() as NestedScrollView
        val contentScroll = collectViews(root) { it is ScrollView }.last() as ScrollView
        val hubContainer = hubScroll.getChildAt(0) as ViewGroup
        val container = contentScroll.getChildAt(0) as ViewGroup

        assertEquals(View.VISIBLE, hubScroll.visibility)
        assertEquals(View.GONE, contentScroll.visibility)
        assertTrue("Hub cards must render synchronously on Frame 1", hubContainer.childCount > 0)
        assertEquals("Category containers must defer until after Frame 1", 0, container.childCount)

        ctrl.visible()
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        assertTrue("Deferred category containers must build after Frame 1", container.childCount >= 9)
        ctrl.pause().destroy()

        // Case 2: Tapping Search immediately before Frame 1 completes builds categories synchronously on demand.
        val ctrlSearch = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        val actSearch = ctrlSearch.get()
        val rootSearch = (actSearch.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        val contentScrollSearch = collectViews(rootSearch) { it is ScrollView }.last() as ScrollView
        val containerSearch = contentScrollSearch.getChildAt(0) as ViewGroup
        assertEquals(0, containerSearch.childCount)
        val searchBtn = collectViews(rootSearch) {
            it is ImageButton && it.contentDescription?.toString() == ctx.getString(R.string.settings_search_hint)
        }.first()
        searchBtn.performClick()
        assertTrue("Opening Search must build category index synchronously on demand", containerSearch.childCount >= 9)
        ctrlSearch.pause().destroy()

        // Case 3: Destroying before Frame 1 runs cleanly cancels deferred callbacks with zero crash.
        val ctrlFastClose = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        ctrlFastClose.pause().destroy()
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
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
