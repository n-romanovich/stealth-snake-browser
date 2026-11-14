package com.nromanovich.stealthsnake

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
@Config(sdk = [33])
class SessionsAndUiTest {

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("stealth_snake_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        SnakePreferences(ctx).apply {
            onboardingCompleted = true
        }
        PinLockCoordinator.markUnlocked()
    }

    @Test
    fun `btnNewTab creates isolated session, updates badge, and switching restores per-session state`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val badge = activity.findViewById<TextView>(R.id.btnTabsPlaceholder)
        val urlInput = activity.findViewById<EditText>(R.id.urlInput)

        assertEquals("1", badge.text.toString())
        val first = activity.getActiveSessionForTest()
        first.webView.loadUrl("https://first.example")
        first.currentUrl = "https://first.example"
        first.blockedRequestCount = 3

        activity.findViewById<ImageButton>(R.id.btnNewTab).performClick()
        ShadowLooper.idleMainLooper()

        assertEquals("2", badge.text.toString())
        assertEquals(2, activity.getSessionsForTest().size)
        val second = activity.getActiveSessionForTest()
        assertNotEquals(first.id, second.id)
        assertNotEquals(first.sessionSeed, second.sessionSeed)
        assertNotEquals(first.webView, second.webView)
        assertEquals(second.webView, activity.findViewById<WebView>(R.id.webView))
        assertEquals("", urlInput.text.toString())

        second.webView.loadUrl("https://second.example")
        second.currentUrl = "https://second.example"
        second.blockedRequestCount = 7

        activity.switchToSession(first)
        ShadowLooper.idleMainLooper()
        assertEquals(first.id, activity.getActiveSessionForTest().id)
        assertEquals(first.webView, activity.findViewById<WebView>(R.id.webView))
        assertEquals("https://first.example", urlInput.text.toString())

        activity.switchToSession(second)
        ShadowLooper.idleMainLooper()
        assertEquals(second.id, activity.getActiveSessionForTest().id)
        assertEquals("https://second.example", urlInput.text.toString())

        // Closing one session drops count to 1 and activates the remaining session
        activity.closeSession(second)
        ShadowLooper.idleMainLooper()
        assertEquals(1, activity.getSessionsForTest().size)
        assertEquals("1", badge.text.toString())
        assertEquals(first.id, activity.getActiveSessionForTest().id)

        // Wipe destroys any extra sessions too
        activity.createNewSession(loadHome = false, switchTo = true)
        assertEquals(2, activity.getSessionsForTest().size)
        val seedBeforeWipe = activity.getActiveSessionForTest().sessionSeed
        activity.wipeSession(reloadBlank = true, reseed = true)
        ShadowLooper.idleMainLooper()
        assertEquals(1, activity.getSessionsForTest().size)
        assertEquals("1", badge.text.toString())
        assertNotEquals(seedBeforeWipe, activity.getActiveSessionForTest().sessionSeed)

        controller.pause().destroy()
    }

    @Test
    fun `tapping sessions badge opens switcher sheet with all active sessions`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.createNewSession(loadHome = false, switchTo = false)
        ShadowLooper.idleMainLooper()

        activity.findViewById<View>(R.id.btnTabsContainer).performClick()
        ShadowLooper.idleMainLooper()

        val dialogs = org.robolectric.shadows.ShadowDialog.getShownDialogs()
        assertTrue("Sessions bottom sheet should be shown", dialogs.any { it.isShowing })
        controller.pause().destroy()
    }

    @Test
    fun `pin overlay keypad enters digits, clears, backspaces, and unlocks`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SnakePreferences(ctx)
        PinManager.setPin(prefs, "2468")
        PinLockCoordinator.onPinConfigured()

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        var unlocked = false
        val overlay = PinLockCoordinator.createPinOverlayView(
            activity = activity,
            prefs = prefs,
            onUnlocked = { unlocked = true },
            onAutoWipe = {}
        )

        assertEquals(null, findFirst<EditText>(overlay))
        val unlockBtn = findButton(overlay, activity.getString(R.string.pin_unlock))!!
        assertTrue(!unlockBtn.isEnabled)

        findTextView(overlay, "2")!!.performClick()
        findTextView(overlay, "4")!!.performClick()
        findTextView(overlay, "9")!!.performClick()
        assertTrue(!unlockBtn.isEnabled)

        findTextView(overlay, "⌫")!!.performClick()
        findTextView(overlay, activity.getString(R.string.pin_clear_key))!!.performClick()
        assertTrue(!unlockBtn.isEnabled)

        "2468".forEach { ch -> findTextView(overlay, ch.toString())!!.performClick() }
        assertTrue(unlockBtn.isEnabled)
        unlockBtn.performClick()
        ShadowLooper.idleMainLooper(600, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertTrue("Correct PIN entered via keypad should unlock", unlocked)
        controller.pause().destroy()
    }

    @Test
    fun `reader mode parses structured blocks and makes text selectable`() {
        val json = """
            {
              "title": "Test Title",
              "byline": "example.org",
              "paragraphs": ["Intro", "A sufficiently long body paragraph for testing.", "Quoted wisdom inside blockquote.", "List entry item one here."],
              "blocks": [
                {"kind": "heading", "text": "Intro"},
                {"kind": "paragraph", "text": "A sufficiently long body paragraph for testing."},
                {"kind": "quote", "text": "Quoted wisdom inside blockquote."},
                {"kind": "list_item", "text": "List entry item one here."}
              ]
            }
        """.trimIndent()
        val article = ReaderModeHelper.parseEvaluateResult(json)!!
        assertEquals(4, article.blocks.size)
        assertEquals(ReaderModeHelper.BlockKind.HEADING, article.blocks[0].kind)
        assertEquals(ReaderModeHelper.BlockKind.QUOTE, article.blocks[2].kind)
        assertEquals(ReaderModeHelper.BlockKind.LIST_ITEM, article.blocks[3].kind)

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val overlay = ReaderModeHelper.buildOverlayView(
            activity = activity,
            palette = SnakePreferences(activity).themePalette,
            article = article,
            onClose = {}
        )
        val bodyTv = findTextView(overlay, "A sufficiently long body paragraph for testing.")!!
        assertTrue(bodyTv.isTextSelectable)
        controller.pause().destroy()
    }

    @Test
    fun `unified user agent uses reduced Chrome major template without device model leak`() {
        val ua = MainActivity.buildUnifiedUserAgent(131)
        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            ua
        )
        val fallback = MainActivity.resolveUnifiedUserAgent(ApplicationProvider.getApplicationContext())
        assertTrue(fallback.contains("Linux; Android 10; K"))
        assertTrue(Regex("""Chrome/\d+\.0\.0\.0""").containsMatchIn(fallback))
    }

    @Test
    fun `pin setup dialog validates mismatch inline without dismissing and saves valid pin`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val prefs = SnakePreferences(activity)

        PinDialogHelper.showSetPinDialog(activity, prefs, includeBody = true) {}
        ShadowLooper.idleMainLooper()

        val dialog = ShadowAlertDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(dialog.isShowing)
        val inputs = collectAll<EditText>(dialog.window!!.decorView)
        assertEquals(2, inputs.size)

        inputs[0].setText("1357")
        inputs[1].setText("9999")
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertTrue("Mismatched PIN should keep dialog open", dialog.isShowing)

        inputs[1].setText("1357")
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertTrue("Valid matching PIN should enable pinLockEnabled", prefs.pinLockEnabled)

        controller.pause().destroy()
    }

    @Test
    fun `reorganized settings renders 7 intuitive sections and scrolls to downloads target`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(ctx, SettingsActivity::class.java).apply {
            putExtra(SettingsActivity.EXTRA_SCROLL_TO_SECTION, SettingsActivity.SECTION_DOWNLOADS)
        }
        val controller = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup()
        val activity = controller.get()
        val root = (activity.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)

        val expectedNavs = listOf(
            R.string.nav_presets,
            R.string.nav_navigation,
            R.string.nav_security,
            R.string.nav_network,
            R.string.nav_antifp,
            R.string.nav_appearance
        )
        expectedNavs.forEach { resId ->
            assertNotNull("Missing nav item for $resId", findTextView(root, ctx.getString(resId)))
        }
        val navNavigation = findTextView(root, ctx.getString(R.string.nav_navigation))!!
        assertEquals(SnakePreferences(ctx).themePalette.accent, navNavigation.currentTextColor)
        assertNotNull(findAnyText(root, ctx.getString(R.string.pref_downloads_enabled)))
        assertNotNull(findTextView(root, ctx.getString(R.string.pin_status_disabled)))

        controller.pause().destroy()
    }

    @Test
    fun `site info sheet inspects certificate without vpn bloat and security icon has press feedback`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SnakePreferences(ctx)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        val securityIcon = activity.findViewById<android.widget.ImageView>(R.id.siteSecurityIcon)
        assertTrue(securityIcon.isClickable)
        assertTrue(securityIcon.isFocusable)
        assertNotNull(securityIcon.background)

        val now = System.currentTimeMillis()
        val from = java.util.Date(now - 10L * 24 * 60 * 60 * 1000)
        val to = java.util.Date(now + 45L * 24 * 60 * 60 * 1000)
        @Suppress("DEPRECATION")
        val cert = android.net.http.SslCertificate("CN=*.example.org, O=Example Org", "CN=Example CA, O=Example Trust", from, to)

        val details = SiteInfoSheet.extractCertificateDetails(cert, now)
        assertNotNull(details)
        assertEquals("*.example.org", details!!.subjectCn)
        assertEquals("Example CA", details.issuerCn)
        assertEquals(45L, details.daysRemaining)

        val dialog = SiteInfoSheet.show(
            activity = activity,
            prefs = prefs,
            state = SiteInfoSheet.PageSecurityState(
                url = "https://example.org/test",
                vpnKillSwitchEnabled = false,
                vpnActive = false,
                httpBypassed = false,
                sslBypassed = false,
                blockedHosts = emptyMap(),
                certificate = cert
            ),
            onProfileOrBlocklistChanged = {}
        )
        val decor = dialog.window!!.decorView
        val allTexts = collectAll<TextView>(decor).mapNotNull { it.text?.toString() }
        assertTrue(allTexts.any { it.contains("*.example.org") })
        assertTrue(allTexts.any { it.contains("Example CA") })
        assertTrue(allTexts.none { it.contains("VPN Kill-Switch", ignoreCase = true) })
        assertTrue(allTexts.none { it == ctx.getString(R.string.btn_settings) })
        assertTrue(allTexts.none { it == ctx.getString(R.string.compat_warn_guide) })
        assertTrue(allTexts.none { it.equals(ctx.getString(R.string.site_info_active_profile), ignoreCase = true) })
        dialog.dismiss()
        controller.pause().destroy()
    }

    @Test
    fun `android 16 settings uses hub navigation asymmetric cards working switches and flat search overlay`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SnakePreferences(ctx)
        prefs.vpnKillSwitchEnabled = false

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val density = activity.resources.displayMetrics.density
        val root = (activity.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)

        // Top-level Settings Hub is visible on start with category cards
        val hubScroll = collectAll<androidx.core.widget.NestedScrollView>(root).first()
        assertEquals(View.VISIBLE, hubScroll.visibility)

        val vpnHubTitle = findTextView(hubScroll, ctx.getString(R.string.cat_hub_vpn))
        assertNotNull("Expected VPN category card on Hub", vpnHubTitle)
        (vpnHubTitle!!.parent.parent as View).performClick()
        ShadowLooper.idleMainLooper()

        // Hub hides and header displays active category title
        assertEquals(View.GONE, hubScroll.visibility)
        assertNotNull(findTextView(root, ctx.getString(R.string.cat_hub_vpn)))

        val switches = collectAll<SettingsActivity.Android16Switch>(root)
        assertTrue("Expected Android16Switch instances in Settings", switches.size >= 10)
        val vpnSwitch = switches.first { it.text?.toString() == ctx.getString(R.string.pref_vpn_kill_switch) }
        val measureZero = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        vpnSwitch.measure(measureZero, measureZero)
        assertEquals((52f * density + 0.5f).toInt(), vpnSwitch.measuredWidth)
        assertEquals((32f * density + 0.5f).toInt(), vpnSwitch.measuredHeight)

        // Single visible card in VPN group initially has 20dp outer corners top & bottom
        val vpnCard = vpnSwitch.parent as View
        val vpnGroup = vpnCard.parent as ViewGroup
        val initialBg = (vpnCard.background as android.graphics.drawable.RippleDrawable).getDrawable(0) as android.graphics.drawable.GradientDrawable
        val initialRadii = initialBg.cornerRadii!!
        assertEquals(SettingsActivity.OUTER_CORNER_DP * density, initialRadii[0], 0.5f)
        assertEquals(SettingsActivity.OUTER_CORNER_DP * density, initialRadii[4], 0.5f)

        // Tapping parent card toggles Android16Switch, updates pref, and updates group card radii
        vpnCard.performClick()
        assertTrue(vpnSwitch.isChecked)
        assertTrue(prefs.vpnKillSwitchEnabled)
        val updatedRadii = ((vpnCard.background as android.graphics.drawable.RippleDrawable).getDrawable(0) as android.graphics.drawable.GradientDrawable).cornerRadii!!
        assertEquals(SettingsActivity.OUTER_CORNER_DP * density, updatedRadii[0], 0.5f)
        assertEquals(SettingsActivity.INNER_CORNER_DP * density, updatedRadii[4], 0.5f)
        val bypassCard = vpnGroup.getChildAt(1)
        assertEquals(View.VISIBLE, bypassCard.visibility)
        val bypassRadii = ((bypassCard.background as android.graphics.drawable.RippleDrawable).getDrawable(0) as android.graphics.drawable.GradientDrawable).cornerRadii!!
        assertEquals(SettingsActivity.INNER_CORNER_DP * density, bypassRadii[0], 0.5f)
        assertEquals(SettingsActivity.OUTER_CORNER_DP * density, bypassRadii[4], 0.5f)

        // Clicking header Back button returns from category sub-screen to Hub
        val backBtn = collectAll<android.widget.ImageButton>(root).first {
            it.contentDescription?.toString() == ctx.getString(R.string.action_cancel)
        }
        backBtn.performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, hubScroll.visibility)

        // Search mode opens via top search button, filters rows, and opens matching category on click
        val searchBtn = collectAll<View>(root).first { it.contentDescription?.toString() == ctx.getString(R.string.settings_search_hint) }
        searchBtn.performClick()
        ShadowLooper.idleMainLooper()

        val searchInput = collectAll<EditText>(root).first { it.hint?.toString() == ctx.getString(R.string.settings_search_hint) }
        searchInput.setText("WebRTC")
        ShadowLooper.idleMainLooper()

        val matchTv = collectAll<TextView>(root).lastOrNull {
            it !is Button && it.text?.toString() == ctx.getString(R.string.pref_block_webrtc)
        }
        assertNotNull("Expected matching flat search row for WebRTC", matchTv)
        (matchTv!!.parent.parent as View).performClick()
        ShadowLooper.idleMainLooper()

        // Search overlay closes and opens matching VPN category sub-screen
        assertTrue(collectAll<EditText>(root).none { it.hint?.toString() == ctx.getString(R.string.settings_search_hint) })
        assertEquals(View.GONE, hubScroll.visibility)
        controller.pause().destroy()
    }

    private inline fun <reified T : View> findFirst(root: View): T? =
        collectByClass(root, T::class.java).firstOrNull()

    private inline fun <reified T : View> collectAll(root: View): List<T> =
        collectByClass(root, T::class.java)

    private fun <T : View> collectByClass(root: View, clazz: Class<T>): List<T> {
        val out = mutableListOf<T>()
        if (clazz.isInstance(root)) {
            out.add(clazz.cast(root)!!)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                out.addAll(collectByClass(root.getChildAt(i), clazz))
            }
        }
        return out
    }

    private fun findAnyText(root: View, text: String): TextView? =
        collectAll<TextView>(root).firstOrNull { it.text?.toString() == text }

    private fun findTextView(root: View, text: String): TextView? =
        collectAll<TextView>(root).firstOrNull { it !is Button && it.text?.toString() == text }

    private fun findButton(root: View, text: String): Button? =
        collectAll<Button>(root).firstOrNull { it.text?.toString() == text }
}
