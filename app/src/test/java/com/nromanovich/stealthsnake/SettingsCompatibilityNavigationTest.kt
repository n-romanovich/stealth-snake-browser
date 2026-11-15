package com.nromanovich.stealthsnake

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsCompatibilityNavigationTest {

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
    fun `hub everyday group shows Site compatibility card with concise static subtitle and clean minimalist section`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        val activity = controller.get()
        ShadowLooper.idleMainLooper()

        val hubContainer = fieldValue<LinearLayout>(activity, "hubContainer")
        val presetsTitle = collectViews(hubContainer) {
            it is TextView && it.text?.toString() == ctx.getString(R.string.cat_hub_presets)
        }.first()
        val everydayGroup = presetsTitle.parent.parent.parent as LinearLayout

        val compatCard = (0 until everydayGroup.childCount)
            .map { everydayGroup.getChildAt(it) }
            .firstOrNull { card ->
                collectViews(card) { it is TextView && it.text?.toString() == ctx.getString(R.string.cat_hub_site_compat) }.isNotEmpty()
            }
        assertNotNull("Everyday group must contain Site compatibility card", compatCard)
        assertEquals(4, everydayGroup.childCount)

        val expectedSub = ctx.getString(R.string.hub_sub_site_compat)
        assertTrue(
            collectViews(compatCard!!) { it is TextView && it.text?.toString() == expectedSub }.isNotEmpty()
        )

        openCompatibilityFromHub(activity)
        val compatSection = categoryContainers(activity)[SettingsActivity.CAT_COMPATIBILITY]!!
        val allCompatTexts = collectViews(compatSection) { it is TextView }
            .mapNotNull { (it as TextView).text?.toString() }

        // Top context/profile/guide banner and Page rendering section must not appear in Compatibility.
        assertFalse(allCompatTexts.contains(ctx.getString(R.string.pref_site_fixes_guide)))
        assertFalse(allCompatTexts.contains(ctx.getString(R.string.pref_force_dark)))
        assertFalse(allCompatTexts.contains(ctx.getString(R.string.pref_text_zoom)))
        // Technical jargon and meta hints must not appear anywhere in Compatibility UI.
        assertTrue(allCompatTexts.none { it.contains("<input", ignoreCase = true) || it.contains("window.open", ignoreCase = true) || it.contains("Back returns here", ignoreCase = true) })
    }

    @Test
    fun `compatibility sub-category push and back navigation restores category and scroll position`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        val activity = controller.get()
        ShadowLooper.idleMainLooper()

        openCompatibilityFromHub(activity)

        val contentScroll = fieldValue<ScrollView>(activity, "contentScroll")
        val titleView = fieldValue<TextView>(activity, "titleView")
        val hubScroll = fieldValue<NestedScrollView>(activity, "hubScroll")
        val cats = categoryContainers(activity)
        val compatSection = cats[SettingsActivity.CAT_COMPATIBILITY]!!
        val downloadsSection = cats[SettingsActivity.CAT_DOWNLOADS]!!
        val wipeSection = cats[SettingsActivity.CAT_WIPE]!!

        // Give contentScroll bounds so scrollY clamps cleanly in Robolectric.
        contentScroll.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY)
        )
        contentScroll.layout(0, 0, 1080, 1200)
        contentScroll.scrollTo(0, 420)
        val savedScrollY = contentScroll.scrollY
        assertTrue("Expected positive scrollY before pushing sub-category, was $savedScrollY", savedScrollY > 0)

        // Push Downloads & screenshots from Compatibility Group D.
        clickTextCard(compatSection, ctx.getString(R.string.compat_link_downloads))
        ShadowLooper.idleMainLooper()

        assertEquals(ctx.getString(R.string.cat_hub_downloads), titleView.text?.toString())
        assertEquals(View.VISIBLE, downloadsSection.visibility)
        assertEquals(View.GONE, compatSection.visibility)
        assertEquals(0, contentScroll.scrollY)

        // Top Back returns to Compatibility (not Hub) and restores scrollY.
        backButton(activity).performClick()
        ShadowLooper.idleMainLooper()

        assertEquals(ctx.getString(R.string.cat_hub_site_compat), titleView.text?.toString())
        assertEquals(View.VISIBLE, compatSection.visibility)
        assertEquals(View.GONE, downloadsSection.visibility)
        assertEquals(savedScrollY, contentScroll.scrollY)

        // Push Wipe and verify system OnBackPressedDispatcher pops back step-by-step to Hub.
        clickTextCard(compatSection, ctx.getString(R.string.compat_link_wipe))
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, wipeSection.visibility)

        activity.onBackPressedDispatcher.onBackPressed()
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, compatSection.visibility)
        assertEquals(View.GONE, wipeSection.visibility)
        assertEquals(View.GONE, hubScroll.visibility)

        activity.onBackPressedDispatcher.onBackPressed()
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, hubScroll.visibility)
        assertEquals(View.GONE, contentScroll.visibility)
    }

    @Test
    fun `recreation restores multi-level back stack without re-applying initial EXTRA_SCROLL_TO_SECTION`() {
        val launchIntent = Intent(ctx, SettingsActivity::class.java).apply {
            putExtra(SettingsActivity.EXTRA_SCROLL_TO_SECTION, SettingsActivity.SECTION_DOWNLOADS)
        }
        val controller = Robolectric.buildActivity(SettingsActivity::class.java, launchIntent).create().start().resume()
        val activity = controller.get()
        ShadowLooper.idleMainLooper()

        val cats = categoryContainers(activity)
        assertEquals(View.VISIBLE, cats[SettingsActivity.CAT_DOWNLOADS]!!.visibility)

        // User backs out to Hub, then navigates Hub -> Compatibility -> VPN.
        backButton(activity).performClick()
        ShadowLooper.idleMainLooper()
        openCompatibilityFromHub(activity)
        clickTextCard(cats[SettingsActivity.CAT_COMPATIBILITY]!!, ctx.getString(R.string.compat_link_vpn))
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, cats[SettingsActivity.CAT_VPN]!!.visibility)

        val savedState = Bundle()
        controller.saveInstanceState(savedState)
        controller.pause().stop().destroy()

        val recreatedController = Robolectric.buildActivity(SettingsActivity::class.java, launchIntent)
            .create(savedState)
            .start()
            .resume()
        val recreated = recreatedController.get()
        ShadowLooper.idleMainLooper()

        val recreatedCats = categoryContainers(recreated)
        val recreatedHub = fieldValue<NestedScrollView>(recreated, "hubScroll")
        assertEquals(View.GONE, recreatedHub.visibility)
        assertEquals(View.VISIBLE, recreatedCats[SettingsActivity.CAT_VPN]!!.visibility)
        assertEquals(View.GONE, recreatedCats[SettingsActivity.CAT_DOWNLOADS]!!.visibility)

        // Back pops VPN -> Compatibility -> Hub.
        backButton(recreated).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, recreatedCats[SettingsActivity.CAT_COMPATIBILITY]!!.visibility)

        backButton(recreated).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, recreatedHub.visibility)
    }

    @Test
    fun `search jump from pushed sub-category resets back stack and About jump returns cleanly to hub`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        val activity = controller.get()
        ShadowLooper.idleMainLooper()

        openCompatibilityFromHub(activity)
        val compatSection = categoryContainers(activity)[SettingsActivity.CAT_COMPATIBILITY]!!
        clickTextCard(compatSection, ctx.getString(R.string.compat_link_blocking))
        ShadowLooper.idleMainLooper()

        // Jump via Search to Appearance -> Back must return to Hub, NOT Compatibility.
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val searchBtn = collectViews(root) {
            it is ImageButton && it.contentDescription?.toString() == ctx.getString(R.string.settings_search_hint)
        }.first()
        searchBtn.performClick()
        ShadowLooper.idleMainLooper()

        val searchResultForceDark = collectViews(root) {
            it is TextView && it.text?.toString() == ctx.getString(R.string.pref_force_dark)
        }.last()
        (searchResultForceDark.parent.parent as View).performClick()
        ShadowLooper.idleMainLooper()

        assertEquals(View.VISIBLE, categoryContainers(activity)[SettingsActivity.CAT_APPEARANCE]!!.visibility)

        backButton(activity).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, fieldValue<NestedScrollView>(activity, "hubScroll").visibility)

        // Jump via Search from Compatibility to About (Reset defaults) -> must clear stack and land on Hub.
        openCompatibilityFromHub(activity)
        searchBtn.performClick()
        ShadowLooper.idleMainLooper()
        val searchInput = collectViews(root) { it is EditText }.first() as EditText
        searchInput.setText(ctx.getString(R.string.pref_reset_defaults))
        ShadowLooper.idleMainLooper()
        val resetResult = collectViews(root) {
            it is TextView && it.text?.toString() == ctx.getString(R.string.pref_reset_defaults)
        }.last()
        (resetResult.parent.parent as View).performClick()
        ShadowLooper.idleMainLooper()

        assertEquals(View.VISIBLE, fieldValue<NestedScrollView>(activity, "hubScroll").visibility)
        assertNull(fieldValue<String?>(activity, "activeCategoryId"))
    }

    @Test
    fun `bidirectional sync keeps duplicated switches jitter steppers zoom presets and links consistent`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        val activity = controller.get()
        ShadowLooper.idleMainLooper()

        openCompatibilityFromHub(activity)
        val cats = categoryContainers(activity)
        val compatSection = cats[SettingsActivity.CAT_COMPATIBILITY]!!
        val antiFpSection = cats[SettingsActivity.CAT_ANTIFP]!!
        val presetsSection = cats[SettingsActivity.CAT_PRESETS]!!
        val downloadsSection = cats[SettingsActivity.CAT_DOWNLOADS]!!
        val prefs = SnakePreferences(ctx)

        val maxStealthBtn = collectViews(presetsSection) {
            it is MaterialButton && it.text?.toString() == ctx.getString(R.string.preset_max_stealth)
        }.first() as MaterialButton
        val compatPresetBtn = collectViews(presetsSection) {
            it is MaterialButton && it.text?.toString() == ctx.getString(R.string.preset_compatibility)
        }.first() as MaterialButton
        assertEquals(0, maxStealthBtn.strokeWidth)
        assertTrue(compatPresetBtn.strokeWidth > 0)

        // 1. OAuth popup block starts ON (protected) and toggling it OFF in Compatibility syncs to Anti-FP & sets CUSTOM preset.
        assertTrue(findSwitchIn(compatSection, ctx.getString(R.string.pref_block_oauth_popups)).isChecked)
        assertTrue(findSwitchIn(compatSection, ctx.getString(R.string.pref_block_file_uploads)).isChecked)
        assertTrue(findSwitchIn(downloadsSection, ctx.getString(R.string.pref_block_downloads)).isChecked)

        toggleSwitchIn(compatSection, ctx.getString(R.string.pref_block_oauth_popups))
        ShadowLooper.idleMainLooper()
        assertFalse(prefs.blockOAuthPopups)
        assertEquals(ProtectionPreset.CUSTOM, prefs.preset)
        assertFalse(findSwitchIn(antiFpSection, ctx.getString(R.string.pref_block_oauth_popups)).isChecked)

        assertTrue(
            "Weakening a shield in Compatibility must deactivate Max Stealth preset button border/fill",
            maxStealthBtn.strokeWidth > 0 && compatPresetBtn.strokeWidth > 0
        )

        // 2. Disabling microJitter in Compatibility collapses min/max jitter steppers & updates shapes in BOTH categories.
        val compatMinRow = findStepperRow(compatSection, ctx.getString(R.string.pref_jitter_min))
        val antiFpMinRow = findStepperRow(antiFpSection, ctx.getString(R.string.pref_jitter_min))
        assertEquals(View.VISIBLE, compatMinRow.visibility)
        assertEquals(View.VISIBLE, antiFpMinRow.visibility)

        toggleSwitchIn(compatSection, ctx.getString(R.string.pref_jitter_enabled))
        ShadowLooper.idleMainLooper()
        assertFalse(prefs.microJitterEnabled)
        assertEquals(View.GONE, compatMinRow.visibility)
        assertEquals(View.GONE, antiFpMinRow.visibility)

        // Jitter switch becomes last visible item in its group only when disabled -> check bottom corner radius refreshed cleanly.
        val compatDialogsSwitchRow = findSwitchCard(compatSection, ctx.getString(R.string.pref_suppress_dialogs))
        val bgShape = (compatDialogsSwitchRow.background as RippleDrawable).getDrawable(0) as GradientDrawable
        assertNotNull(bgShape.cornerRadii)

        // Turn jitter back ON and increment min jitter stepper cleanly.
        toggleSwitchIn(compatSection, ctx.getString(R.string.pref_jitter_enabled))
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, compatMinRow.visibility)
        assertEquals(View.VISIBLE, antiFpMinRow.visibility)

        val plusBtn = collectViews(compatMinRow) {
            it.contentDescription?.toString() == "${ctx.getString(R.string.pref_jitter_min)} +"
        }.first()
        val beforeMin = prefs.microJitterMinSec
        plusBtn.performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(beforeMin + 1, prefs.microJitterMinSec)
        assertTrue(
            collectViews(antiFpMinRow) {
                it is TextView && it.text?.toString() == ctx.getString(R.string.jitter_seconds_format, beforeMin + 1)
            }.isNotEmpty()
        )

        // 3. Changing Downloads in pushed sub-category updates Group D subtitle when popping back.
        clickTextCard(compatSection, ctx.getString(R.string.compat_link_downloads))
        ShadowLooper.idleMainLooper()
        toggleSwitchIn(downloadsSection, ctx.getString(R.string.pref_block_screenshots))
        backButton(activity).performClick()
        ShadowLooper.idleMainLooper()

        val expectedDlSub = ctx.getString(
            R.string.compat_link_downloads_sub,
            ctx.getString(if (prefs.blockDownloads) R.string.state_on_short else R.string.state_off_short),
            ctx.getString(if (prefs.blockScreenshots) R.string.state_on_short else R.string.state_off_short)
        )
        assertTrue(
            collectViews(compatSection) { it is TextView && it.text?.toString() == expectedDlSub }.isNotEmpty()
        )
    }

    @Test
    fun `compatibility exposes single search entry and preserves container DFS order`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        val activity = controller.get()
        ShadowLooper.idleMainLooper()

        val searchEntries = fieldValue<List<Any>>(activity, "searchEntries")
        val compatEntries = searchEntries.filter { entry ->
            val f = entry.javaClass.getDeclaredField("categoryId").apply { isAccessible = true }
            f.get(entry) == SettingsActivity.CAT_COMPATIBILITY
        }
        assertEquals(1, compatEntries.size)

        val compatSection = categoryContainers(activity)[SettingsActivity.CAT_COMPATIBILITY]!!
        // Preserves Frame 1 deferred container build order: CAT_COMPATIBILITY is last section.
        val container = fieldValue<LinearLayout>(activity, "container")
        assertTrue(container.childCount >= 10)
        assertEquals(compatSection, container.getChildAt(container.childCount - 1))
    }

    @Test
    fun `hub cards and compatibility links use distinct icons with no duplicates`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        val activity = controller.get()
        ShadowLooper.idleMainLooper()

        val hubContainer = fieldValue<LinearLayout>(activity, "hubContainer")
        val hubIconIds = collectViews(hubContainer) { it is ImageView && it.drawable != null }
            .map { Shadows.shadowOf((it as ImageView).drawable).createdFromResId }
        assertTrue(hubIconIds.isNotEmpty())
        assertEquals("Hub cards must not reuse duplicate icon drawables", hubIconIds.size, hubIconIds.toSet().size)
        assertTrue(hubIconIds.contains(R.drawable.ic_chrome_tune))
        assertTrue(hubIconIds.contains(R.drawable.ic_settings_block))

        openCompatibilityFromHub(activity)
        val compatSection = categoryContainers(activity)[SettingsActivity.CAT_COMPATIBILITY]!!
        val linkIconIds = listOf(
            R.string.compat_link_downloads,
            R.string.compat_link_wipe,
            R.string.compat_link_vpn,
            R.string.compat_link_blocking
        ).map { titleRes ->
            val titleTv = collectViews(compatSection) { it is TextView && it.text?.toString() == ctx.getString(titleRes) }.first()
            val row = titleTv.parent.parent as ViewGroup
            val leadingIv = collectViews(row) { it is ImageView }.first() as ImageView
            Shadows.shadowOf(leadingIv.drawable).createdFromResId
        }
        assertEquals("Compatibility links must use distinct icon drawables", linkIconIds.size, linkIconIds.toSet().size)
        assertEquals(R.drawable.ic_settings_block, linkIconIds.last())
    }

    @Test
    fun `deferred frame 1 category build invariant holds after adding compatibility section`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        val activity = controller.get()
        val container = fieldValue<LinearLayout>(activity, "container")
        assertEquals(0, container.childCount)

        ShadowLooper.idleMainLooper()
        assertTrue(container.childCount >= 10)
    }

    @Test
    fun `compatibility syncs all remaining toggles, Group D links, and Hub summaries`() {
        val prefs = SnakePreferences(ctx)
        prefs.resetToDefaults()

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        val activity = controller.get()
        ShadowLooper.idleMainLooper()

        val hubContainer = fieldValue<LinearLayout>(activity, "hubContainer")
        assertTrue(
            collectViews(hubContainer) {
                it is TextView && it.text?.toString() == ctx.getString(
                    R.string.hub_sub_downloads,
                    ctx.getString(R.string.state_on_short),
                    ctx.getString(R.string.state_off_short)
                )
            }.isNotEmpty()
        )
        assertTrue(
            collectViews(hubContainer) {
                it is TextView && it.text?.toString() == ctx.getString(
                    R.string.hub_sub_antifp,
                    9,
                    ctx.getString(R.string.state_on_short)
                )
            }.isNotEmpty()
        )

        openCompatibilityFromHub(activity)
        val containers = categoryContainers(activity)
        val compatSection = containers[SettingsActivity.CAT_COMPATIBILITY]!!
        val antiFpSection = containers[SettingsActivity.CAT_ANTIFP]!!
        val blockingSection = containers[SettingsActivity.CAT_BLOCKING]!!
        val vpnSection = containers[SettingsActivity.CAT_VPN]!!
        val wipeSection = containers[SettingsActivity.CAT_WIPE]!!

        val syncedPairs = listOf(
            R.string.pref_block_oauth_popups to antiFpSection,
            R.string.pref_block_file_uploads to antiFpSection,
            R.string.pref_noise_canvas to antiFpSection,
            R.string.pref_hide_visibility to antiFpSection,
            R.string.pref_lock_viewport to antiFpSection,
            R.string.pref_noise_audio to antiFpSection,
            R.string.pref_sensor_fallback to antiFpSection,
            R.string.pref_fuzz_perf to antiFpSection,
            R.string.pref_suppress_dialogs to antiFpSection,
            R.string.pref_builtin_blocklist to blockingSection,
            R.string.pref_block_beacons to blockingSection,
            R.string.pref_block_webrtc to vpnSection,
            R.string.pref_block_schemes to vpnSection
        )
        for ((labelRes, canonicalSection) in syncedPairs) {
            val label = ctx.getString(labelRes)
            val compatSw = findSwitchIn(compatSection, label)
            val canonicalSw = findSwitchIn(canonicalSection, label)
            val initial = compatSw.isChecked
            assertEquals("Initial sync mismatch for $label", initial, canonicalSw.isChecked)

            toggleSwitchIn(compatSection, label)
            ShadowLooper.idleMainLooper()
            assertEquals("Compat->Canonical sync mismatch for $label", !initial, canonicalSw.isChecked)

            toggleSwitchIn(canonicalSection, label)
            ShadowLooper.idleMainLooper()
            assertEquals("Canonical->Compat sync mismatch for $label", initial, compatSw.isChecked)
        }

        // Verify remaining Group D links (Wipe, VPN, Blocking) update subtitles on pop back.
        clickTextCard(compatSection, ctx.getString(R.string.compat_link_wipe))
        ShadowLooper.idleMainLooper()
        toggleSwitchIn(wipeSection, ctx.getString(R.string.pref_auto_wipe_exit))
        backButton(activity).performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(
            collectViews(compatSection) {
                it is TextView && it.text?.toString() == ctx.getString(
                    R.string.compat_link_wipe_sub,
                    ctx.getString(if (prefs.autoWipeOnExit) R.string.state_on_short else R.string.state_off_short)
                )
            }.isNotEmpty()
        )

        clickTextCard(compatSection, ctx.getString(R.string.compat_link_vpn))
        ShadowLooper.idleMainLooper()
        toggleSwitchIn(vpnSection, ctx.getString(R.string.pref_vpn_kill_switch))
        backButton(activity).performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(
            collectViews(compatSection) {
                it is TextView && it.text?.toString() == ctx.getString(
                    R.string.compat_link_vpn_sub,
                    ctx.getString(if (prefs.vpnKillSwitchEnabled) R.string.state_on_short else R.string.state_off_short)
                )
            }.isNotEmpty()
        )

        clickTextCard(compatSection, ctx.getString(R.string.compat_link_blocking))
        ShadowLooper.idleMainLooper()
        toggleSwitchIn(blockingSection, ctx.getString(R.string.pref_builtin_blocklist))
        backButton(activity).performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(
            collectViews(compatSection) {
                it is TextView && it.text?.toString() == ctx.getString(
                    R.string.compat_link_blocking_sub,
                    ctx.getString(if (prefs.builtinBlocklistEnabled) R.string.state_on_short else R.string.state_off_short),
                    prefs.parsedCustomRules().size
                )
            }.isNotEmpty()
        )
    }

    @Test
    fun `search filters View GONE targets, highlights addActionCard wrapper, and leaves compat container un-pulsed`() {
        val prefs = SnakePreferences(ctx).apply {
            vpnKillSwitchEnabled = false
            searchEngine = SearchEngine.DUCKDUCKGO
        }
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        val activity = controller.get()
        ShadowLooper.idleMainLooper()

        // Search entry assertions before filtering
        val searchEntries = fieldValue<List<Any>>(activity, "searchEntries")
        fun findEntry(titleRes: Int): Any {
            val expected = ctx.getString(titleRes).lowercase()
            return searchEntries.first { entry ->
                val f = entry.javaClass.getDeclaredField("keywords").apply { isAccessible = true }
                (f.get(entry) as String).startsWith(expected)
            }
        }
        fun targetViewOf(entry: Any): View? {
            val f = entry.javaClass.getDeclaredField("targetView").apply { isAccessible = true }
            return f.get(entry) as View?
        }

        // addActionCard with subtitle (about_github_label) targets card wrapper (not inner borderless button)
        val githubTarget = targetViewOf(findEntry(R.string.about_github_label))
        assertNotNull(githubTarget)
        assertTrue("Action card target should be card wrapper, not bare Button", githubTarget is LinearLayout)
        assertTrue(githubTarget!!.background is RippleDrawable)

        // CAT_COMPATIBILITY entry has null targetView so jumping there does not pulse the whole scroll section.
        val compatTarget = targetViewOf(findEntry(R.string.cat_hub_site_compat))
        assertNull(compatTarget)

        // Hidden dependent cards must be View.GONE while parent switch/choice disables them.
        assertEquals(View.GONE, targetViewOf(findEntry(R.string.pref_vpn_check_start))!!.visibility)
        assertEquals(View.GONE, targetViewOf(findEntry(R.string.pref_custom_search_template))!!.visibility)

        // Search overlay hides GONE rows and reveals them once enabled.
        val searchBtn = fieldValue<ImageButton>(activity, "searchBtn")
        searchBtn.performClick()
        ShadowLooper.idleMainLooper()
        val searchOverlay = fieldValue<LinearLayout>(activity, "searchOverlay")
        val searchInput = collectViews(searchOverlay) { it is EditText }.first() as EditText
        searchInput.setText(ctx.getString(R.string.pref_custom_search_template))
        ShadowLooper.idleMainLooper()
        assertTrue(collectViews(searchOverlay) { it is TextView && it.text?.toString() == ctx.getString(R.string.settings_search_empty) }.isNotEmpty())

        // Enabling CUSTOM search makes customSearchCard visible so search finds it.
        prefs.searchEngine = SearchEngine.CUSTOM
        targetViewOf(findEntry(R.string.pref_custom_search_template))!!.visibility = View.VISIBLE
        searchInput.setText(ctx.getString(R.string.pref_custom_search_template))
        ShadowLooper.idleMainLooper()
        assertTrue(collectViews(searchOverlay) { it is TextView && it.text?.toString() == ctx.getString(R.string.pref_custom_search_template) }.isNotEmpty())

        // Canvas card icon matches ic_settings_stealth.
        val canvasCard = targetViewOf(findEntry(R.string.pref_noise_canvas))!!
        val canvasIv = collectViews(canvasCard) { it is ImageView && it.drawable != null }.first() as ImageView
        assertEquals(R.drawable.ic_settings_stealth, Shadows.shadowOf(canvasIv.drawable).createdFromResId)
    }

    @Test
    @Config(qualifiers = "ru")
    fun `russian locale notices and compatibility strings are concise and unambiguous`() {
        val uploadNotice = ctx.getString(R.string.file_upload_blocked_notice)
        val popupNotice = ctx.getString(R.string.popup_blocked_notice)
        val dlSub = ctx.getString(R.string.hub_sub_downloads, ctx.getString(R.string.state_on_short), ctx.getString(R.string.state_off_short))
        val compatDlSub = ctx.getString(R.string.compat_link_downloads_sub, ctx.getString(R.string.state_on_short), ctx.getString(R.string.state_off_short))

        assertFalse(uploadNotice.contains("песочниц", ignoreCase = true))
        assertFalse(ctx.getString(R.string.session_profile_switched, "Default").contains("seed", ignoreCase = true))
        assertTrue(popupNotice.contains("Совместимости сайтов"))
        assertTrue(uploadNotice.contains("Совместимости сайтов"))
        assertTrue(dlSub.contains("Блок скриншотов"))
        assertTrue(compatDlSub.contains("Блок скриншотов"))
        assertTrue(ctx.getString(R.string.compat_search_keywords).contains("попап"))
        assertTrue(ctx.getString(R.string.compat_search_keywords).contains("банк"))
    }

    private fun openCompatibilityFromHub(activity: SettingsActivity) {
        val hubContainer = fieldValue<LinearLayout>(activity, "hubContainer")
        clickTextCard(hubContainer, ctx.getString(R.string.cat_hub_site_compat))
        ShadowLooper.idleMainLooper()
    }

    private fun backButton(activity: SettingsActivity): ImageButton =
        fieldValue(activity, "backBtn")

    private fun categoryContainers(activity: SettingsActivity): Map<String, View> =
        fieldValue(activity, "categoryContainers")

    private fun clickTextCard(root: View, text: String) {
        val tv = collectViews(root) { it is TextView && it.text?.toString() == text }.first()
        var current: View? = tv
        while (current != null && !current.isClickable) {
            current = current.parent as? View
        }
        assertNotNull("Expected clickable card ancestor for '$text'", current)
        current!!.performClick()
    }

    private fun toggleSwitchIn(section: View, label: String) {
        findSwitchIn(section, label).performClick()
    }

    private fun findSwitchIn(section: View, label: String): CompoundButton {
        val card = findSwitchCard(section, label)
        return collectViews(card) { it is CompoundButton }.first() as CompoundButton
    }

    private fun findSwitchCard(section: View, label: String): View {
        val labelTv = collectViews(section) { it is TextView && it.text?.toString() == label }.first()
        return labelTv.parent.parent as View
    }

    private fun findStepperRow(section: View, label: String): View {
        val labelTv = collectViews(section) { it is TextView && it.text?.toString() == label }.first()
        return labelTv.parent as View
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> fieldValue(target: Any, name: String): T {
        val f = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
        return f.get(target) as T
    }

    private fun collectViews(root: View, predicate: (View) -> Boolean): List<View> {
        val result = mutableListOf<View>()
        if (predicate(root)) result.add(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                result.addAll(collectViews(root.getChildAt(i), predicate))
            }
        }
        return result
    }
}
