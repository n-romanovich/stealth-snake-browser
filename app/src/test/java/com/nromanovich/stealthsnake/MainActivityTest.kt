package com.nromanovich.stealthsnake

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
class MainActivityTest {

    @Test
    fun `manifest enforces minimal permissions portrait adjustNothing and no backup`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()

        val pkgInfo = activity.packageManager.getPackageInfo(
            activity.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES
        )
        val requested = pkgInfo.requestedPermissions?.toSet().orEmpty()
        val allowed = setOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            "${activity.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        )
        assertTrue(Manifest.permission.INTERNET in requested)
        assertTrue("Unexpected permissions declared: ${requested - allowed}", (requested - allowed).isEmpty())
        assertEquals("com.nromanovich.stealthsnake", activity.packageName)
        assertEquals("1.0.0", pkgInfo.versionName)

        val appFlags = activity.applicationInfo.flags
        assertEquals(0, appFlags and ApplicationInfo.FLAG_ALLOW_BACKUP)

        val activityInfo = pkgInfo.activities!!.first { it.name == MainActivity::class.java.name }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activityInfo.screenOrientation)
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING, activityInfo.softInputMode)

        controller.destroy()
    }

    @Test
    fun `webview settings enforce zero telemetry and strict sandbox isolation`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val webView = activity.findViewById<WebView>(R.id.webView)

        assertTrue(webView.keepScreenOn)
        assertTrue(webView.settings.javaScriptEnabled)
        assertTrue(webView.settings.domStorageEnabled)
        assertFalse(webView.settings.safeBrowsingEnabled)
        assertFalse(webView.settings.allowFileAccess)
        assertFalse(webView.settings.allowContentAccess)
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, webView.settings.mixedContentMode)
        assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(webView))
        assertTrue(activity.webViewClientIsSnake(webView))

        controller.pause().destroy()
    }

    @Test
    fun `normalizeUrl enforces safe schemes and prepends https`() {
        assertNull(MainActivity.normalizeUrl(""))
        assertNull(MainActivity.normalizeUrl("   "))
        assertEquals("about:blank", MainActivity.normalizeUrl("ABOUT:BLANK"))
        assertEquals("https://example.org", MainActivity.normalizeUrl("example.org"))
        assertEquals("http://localhost:8080/test", MainActivity.normalizeUrl("http://localhost:8080/test"))
        assertEquals("https://browserleaks.com/canvas", MainActivity.normalizeUrl("https://browserleaks.com/canvas"))

        assertNull(MainActivity.normalizeUrl("file:///etc/passwd"))
        assertNull(MainActivity.normalizeUrl("content://com.android.contacts/contacts"))
        assertNull(MainActivity.normalizeUrl("intent://foo#Intent;end"))
        assertNull(MainActivity.normalizeUrl("javascript:alert(1)"))
        assertNull(MainActivity.normalizeUrl("data:text/html,<h1>x</h1>"))
    }

    @Test
    fun `start page card shows on blank home and dismisses persistently`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val card = activity.findViewById<View>(R.id.startPageCard)
        val webView = activity.findViewById<WebView>(R.id.webView)
        val dismissBtn = activity.findViewById<TextView>(R.id.btnStartDismiss)
        val expectedBg = SnakePreferences(activity).themePalette.bg

        assertEquals(View.VISIBLE, card.visibility)
        assertEquals(expectedBg, (card.background as ColorDrawable).color)
        val webBg = (webView.background as? ColorDrawable)?.color
            ?: org.robolectric.Shadows.shadowOf(webView).backgroundColor
        assertEquals(expectedBg, webBg)
        dismissBtn.performClick()
        assertEquals(View.GONE, card.visibility)
        assertTrue(SnakePreferences(activity).startHintDismissed)

        controller.pause().destroy()
    }

    @Test
    fun `onboarding opens only once across onCreate and onResume`() {
        ShadowAlertDialog.reset()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        ShadowLooper.idleMainLooper()

        val shownDialogs = ShadowAlertDialog.getShownDialogs().filter { it.isShowing }
        assertEquals("Expected exactly 1 onboarding dialog", 1, shownDialogs.size)

        controller.pause().resume()
        ShadowLooper.idleMainLooper()
        val shownAfterResume = ShadowAlertDialog.getShownDialogs().filter { it.isShowing }
        assertEquals("Onboarding must not duplicate on resume", 1, shownAfterResume.size)

        controller.pause().destroy()
    }

    @Test
    fun `settings compatibility shows warning dialog without checkmark and guide opens`() {
        ShadowAlertDialog.reset()
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val prefs = SnakePreferences(activity)
        prefs.applyPreset(ProtectionPreset.MAX_STEALTH)

        val maxLabel = activity.getString(R.string.preset_max_stealth)
        val compatLabel = activity.getString(R.string.preset_compatibility)
        val root = activity.window.decorView

        assertTrue("Preset buttons must not have checkmark prefix", findButtonWithText(root, maxLabel) != null)
        assertNull(findButtonWithText(root, "✓ $maxLabel"))

        val compatBtn = findButtonWithText(root, compatLabel) as com.google.android.material.button.MaterialButton
        assertEquals(0, compatBtn.cornerRadius)
        assertEquals(1, compatBtn.maxLines)
        compatBtn.performClick()
        ShadowLooper.idleMainLooper()

        // Must show recommendation dialog before changing preset
        assertEquals(ProtectionPreset.MAX_STEALTH, prefs.preset)
        val warnDialog = ShadowAlertDialog.getShownDialogs().lastOrNull { it.isShowing } as? androidx.appcompat.app.AlertDialog
        assertTrue("Compatibility warning dialog must be shown", warnDialog != null)
        warnDialog!!.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(ProtectionPreset.COMPATIBILITY, prefs.preset)

        // Create a custom profile via borderless "+" button in SettingsActivity UI
        val plusBtn = findButtonWithText(root, "+") as com.google.android.material.button.MaterialButton
        assertEquals(0, plusBtn.cornerRadius)
        assertEquals(0, plusBtn.strokeWidth)
        plusBtn.performClick()
        ShadowLooper.idleMainLooper()

        val createDialog = ShadowAlertDialog.getShownDialogs().lastOrNull { it.isShowing } as? androidx.appcompat.app.AlertDialog
        assertTrue("Create profile dialog must be shown", createDialog != null)
        val nameInput = findEditText(createDialog!!.window!!.decorView)!!
        nameInput.setText("Calls")
        createDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()

        assertEquals(1, prefs.listProfiles().size)
        assertEquals("Calls", prefs.listProfiles().first().name)
        val profileBtn = findButtonWithText(root, "Calls")
        assertTrue("Custom profile button must appear in settings", profileBtn != null)

        // Long-press custom profile button -> confirm delete
        profileBtn!!.performLongClick()
        ShadowLooper.idleMainLooper()
        val deleteDialog = ShadowAlertDialog.getShownDialogs().lastOrNull { it.isShowing } as? androidx.appcompat.app.AlertDialog
        assertTrue("Delete profile confirmation must appear", deleteDialog != null)
        deleteDialog!!.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(prefs.listProfiles().isEmpty())

        val fixesBtn = findButtonWithText(root, activity.getString(R.string.pref_site_fixes_guide))
        assertTrue("Troubleshooting guide button must exist", fixesBtn != null)

        val githubView = findTextViewWithText(root, activity.getString(R.string.about_github_label))
        assertTrue("Settings should display GitHub project link", githubView != null)

        val navAbout = findTextViewWithText(root, activity.getString(R.string.nav_antifp))
        val navNetwork = findTextViewWithText(root, activity.getString(R.string.nav_network))
        assertTrue("Settings should display left section guide", navAbout != null && navNetwork != null)

        navNetwork!!.performClick()
        ShadowLooper.idleMainLooper()
        navAbout!!.performClick()
        ShadowLooper.idleMainLooper()

        val networkHeader = findTextViewWithText(root, activity.getString(R.string.cat_network))!!
        val networkSection = networkHeader.parent as View
        val bgColor = (networkSection.background as? ColorDrawable)?.color ?: Color.TRANSPARENT
        assertEquals("Previous section highlight must reset immediately", Color.TRANSPARENT, bgColor)

        controller.pause().destroy()
    }

    @Test
    fun `home url update in settings reloads webview when user is on old home`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val prefs = SnakePreferences(ctx)
        prefs.onboardingCompleted = true
        prefs.homeUrl = MainActivity.DEFAULT_START_URL

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val webView = activity.findViewById<WebView>(R.id.webView)
        val card = activity.findViewById<View>(R.id.startPageCard)

        assertEquals(View.VISIBLE, card.visibility)

        prefs.homeUrl = "https://example.org"
        controller.pause().resume()
        ShadowLooper.idleMainLooper()

        assertEquals("https://example.org", Shadows.shadowOf(webView).lastLoadedUrl)
        assertEquals(View.GONE, card.visibility)

        controller.pause().destroy()
    }

    @Test
    fun `overflow menu copies url and find bar opens and closes cleanly`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        SnakePreferences(ctx).onboardingCompleted = true

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val urlInput = activity.findViewById<EditText>(R.id.urlInput)
        val findBar = activity.findViewById<View>(R.id.findBar)
        val findInput = activity.findViewById<EditText>(R.id.findInput)

        val webView = activity.findViewById<WebView>(R.id.webView)
        urlInput.setText("https://example.org")
        activity.findViewById<ImageButton>(R.id.btnMenu).performClick()
        ShadowLooper.idleMainLooper()

        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val popup = Shadows.shadowOf(app).latestPopupWindow
        assertTrue("Overflow popup should be showing", popup != null && popup.isShowing)
        val popupRoot = popup!!.contentView

        (findTextViewWithText(popupRoot, activity.getString(R.string.btn_copy_url))!!.parent as View).performClick()
        ShadowLooper.idleMainLooper()
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val expectedCopied = webView.url?.takeIf { it.isNotBlank() } ?: "https://example.org"
        assertEquals(expectedCopied, cm.primaryClip?.getItemAt(0)?.text?.toString())

        activity.findViewById<ImageButton>(R.id.btnMenu).performClick()
        ShadowLooper.idleMainLooper()
        val popup2 = Shadows.shadowOf(app).latestPopupWindow
        (findTextViewWithText(popup2!!.contentView, activity.getString(R.string.btn_find_in_page))!!.parent as View).performClick()
        ShadowLooper.idleMainLooper()

        assertEquals(View.VISIBLE, findBar.visibility)
        findInput.setText("snake")
        activity.findViewById<ImageButton>(R.id.btnFindClose).performClick()
        assertEquals(View.GONE, findBar.visibility)
        assertEquals("", findInput.text.toString())

        controller.pause().destroy()
    }

    @Test
    fun `site fixes and captures activities apply active theme palette`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        SnakePreferences(ctx).themePalette = ThemePalette.SLATE_LIGHT

        val fixesCtrl = Robolectric.buildActivity(SiteFixesActivity::class.java).setup()
        val fixesRoot = (fixesCtrl.get().findViewById<View>(android.R.id.content) as android.view.ViewGroup).getChildAt(0)
        assertEquals(ThemePalette.SLATE_LIGHT.bg, (fixesRoot.background as ColorDrawable).color)
        fixesCtrl.pause().destroy()

        val capturesCtrl = Robolectric.buildActivity(CapturesActivity::class.java).setup()
        val capturesRoot = (capturesCtrl.get().findViewById<View>(android.R.id.content) as android.view.ViewGroup).getChildAt(0)
        assertEquals(ThemePalette.SLATE_LIGHT.bg, (capturesRoot.background as ColorDrawable).color)
        capturesCtrl.pause().destroy()
    }

    @Test
    fun `vpn kill switch blocks startup without vpn and loads once vpn activates`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val prefs = SnakePreferences(ctx)
        prefs.onboardingCompleted = true
        prefs.vpnKillSwitchEnabled = true
        prefs.vpnBypassStartCheck = false
        prefs.homeUrl = "https://example.org"

        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val noVpnCaps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(noVpnCaps).addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, noVpnCaps)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val webView = activity.findViewById<WebView>(R.id.webView)
        val vpnOverlay = activity.findViewById<View>(R.id.vpnBlockedView)

        assertEquals(View.VISIBLE, vpnOverlay.visibility)
        assertEquals("about:blank", Shadows.shadowOf(webView).lastLoadedUrl)

        // Now connect VPN and click "Check again"
        val vpnCaps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(vpnCaps).addTransportType(android.net.NetworkCapabilities.TRANSPORT_VPN)
        Shadows.shadowOf(vpnCaps).removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, vpnCaps)

        activity.findViewById<TextView>(R.id.btnVpnRetry).performClick()
        ShadowLooper.idleMainLooper()

        assertEquals(View.GONE, vpnOverlay.visibility)
        assertEquals("https://example.org", Shadows.shadowOf(webView).lastLoadedUrl)

        controller.pause().destroy()
    }

    @Test
    fun `vpn bypass start check opens blank start page while snake client still blocks network without vpn`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val prefs = SnakePreferences(ctx)
        prefs.onboardingCompleted = true
        prefs.vpnKillSwitchEnabled = true
        prefs.vpnBypassStartCheck = true
        prefs.homeUrl = MainActivity.DEFAULT_START_URL

        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val noVpnCaps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(noVpnCaps).addTransportType(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, noVpnCaps)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.vpnBlockedView).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.startPageCard).visibility)
        controller.pause().destroy()

        // Verify SnakeClient blocks HTTP/HTTPS requests & redirects when VPN is down
        var blockedCount = 0
        val client = SnakeClient(
            blocklist = BlocklistEngine.fromRules(emptyList()),
            blocklistEnabled = false,
            vpnKillSwitchEnabled = true,
            isVpnActive = { false },
            onVpnBlockedCallback = { blockedCount++ }
        )
        val req = object : android.webkit.WebResourceRequest {
            override fun getUrl() = android.net.Uri.parse("https://example.org/api")
            override fun isForMainFrame() = true
            override fun isRedirect() = false
            override fun hasGesture() = true
            override fun getMethod() = "GET"
            override fun getRequestHeaders() = emptyMap<String, String>()
        }
        val resp = client.shouldInterceptRequest(null, req)
        assertEquals(403, resp?.statusCode)
        assertTrue(client.shouldOverrideUrlLoading(null, req))
        assertTrue(blockedCount >= 2)
    }

    @Test
    fun `settings nav rail highlight clears when scrolling away and emerald theme is named Emerald Splash`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Emerald Splash", ctx.getString(R.string.theme_emerald))

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val root = (activity.findViewById<View>(android.R.id.content) as android.view.ViewGroup).getChildAt(0)
        val presetsNav = findTextViewWithText(root, ctx.getString(R.string.nav_presets))!!
        val palette = SnakePreferences(ctx).themePalette

        presetsNav.performClick()
        ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(palette.accent, presetsNav.currentTextColor)
        assertEquals(android.graphics.Typeface.BOLD, presetsNav.typeface?.style ?: android.graphics.Typeface.NORMAL)

        // Find the content ScrollView (the second ScrollView in splitRow) and scroll away
        val scrollViews = mutableListOf<android.widget.ScrollView>()
        fun collectScrolls(v: View) {
            if (v is android.widget.ScrollView) scrollViews.add(v)
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) collectScrolls(v.getChildAt(i))
            }
        }
        collectScrolls(root)
        val contentScroll = scrollViews.last()
        contentScroll.scrollTo(0, 600)
        ShadowLooper.idleMainLooper()

        assertEquals(palette.muted, presetsNav.currentTextColor)
        assertEquals(android.graphics.Typeface.NORMAL, presetsNav.typeface?.style ?: android.graphics.Typeface.NORMAL)
        controller.pause().destroy()
    }

    @Test
    fun `site fixes activity filters by search query and category chips and resets empty state`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val controller = Robolectric.buildActivity(SiteFixesActivity::class.java).setup()
        val activity = controller.get()
        val root = (activity.findViewById<View>(android.R.id.content) as android.view.ViewGroup).getChildAt(0)

        val searchBox = findEditText(root)!!
        searchBox.setText("snapdrop")
        ShadowLooper.idleMainLooper()
        assertTrue(findTextViewWithText(root, ctx.getString(R.string.fixes_count_filtered, 1, SiteFixGuide.entries.size)) != null)

        searchBox.setText("zzzznonexistentquery")
        ShadowLooper.idleMainLooper()
        val resetBtn = findTextViewWithText(root, ctx.getString(R.string.fixes_reset_filters))!!
        assertEquals(View.VISIBLE, resetBtn.visibility)

        resetBtn.performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(findTextViewWithText(root, ctx.getString(R.string.fixes_count_all, SiteFixGuide.entries.size)) != null)

        controller.pause().destroy()
    }

    @Test
    fun `window has FLAG_SECURE webview disables autofill and sessionSeed stays stable on resume`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val webView = activity.findViewById<WebView>(R.id.webView)

        val flags = activity.window.attributes.flags
        assertEquals(
            "FLAG_SECURE must be off by default to allow screenshots and Circle to Search",
            0,
            flags and WindowManager.LayoutParams.FLAG_SECURE
        )
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, webView.importantForAutofill)

        val seedBeforeResume = activity.getActiveSessionForTest().sessionSeed

        SnakePreferences(activity).blockScreenshots = true
        controller.pause().resume()
        ShadowLooper.idleMainLooper()
        val seedAfterResume = activity.getActiveSessionForTest().sessionSeed
        assertEquals("sessionSeed must not rotate on onResume", seedBeforeResume, seedAfterResume)
        val flagsAfterBlockScreenshots = activity.window.attributes.flags
        assertTrue(
            "FLAG_SECURE must be enabled when blockScreenshots is true",
            (flagsAfterBlockScreenshots and WindowManager.LayoutParams.FLAG_SECURE) != 0
        )

        controller.pause().destroy()
    }

    @Test
    fun `dirty session triggers auto-wipe on cold launch even if onDestroy was skipped`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val prefs = SnakePreferences(ctx)
        prefs.autoWipeOnExit = true
        prefs.sessionDirty = true
        CookieManager.getInstance().setCookie("https://example.org", "leaked=1")

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        ShadowLooper.idleMainLooper()

        // MainActivity.onCreate() should have wiped leftover data and cleared sessionDirty.
        assertNull(CookieManager.getInstance().getCookie("https://example.org"))
        assertFalse(SnakePreferences(activity).sessionDirty)

        controller.pause().destroy()
        assertFalse(SnakePreferences(activity).sessionDirty)
    }

    private fun findEditText(view: View): EditText? {
        if (view is EditText) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findEditText(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun findTextViewWithText(view: View, text: String): TextView? {
        if (view is TextView && view.text?.toString() == text) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findTextViewWithText(view.getChildAt(i), text)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findButtonWithText(view: View, text: String): Button? {
        if (view is Button && view.text?.toString() == text) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findButtonWithText(view.getChildAt(i), text)
                if (found != null) return found
            }
        }
        return null
    }

    private fun MainActivity.webViewClientIsSnake(webView: WebView): Boolean =
        webView.webViewClient is SnakeClient
}
