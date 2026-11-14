package com.nromanovich.stealthsnake

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.TextViewCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.snackbar.Snackbar

/**
 * Minimal hardened browser host with Chrome-like top bar, pull-to-refresh,
 * palette theming, dynamic onboarding, optional downloads, and isolated capture storage.
 */
class MainActivity : AppCompatActivity(), PinLockCoordinator.PinProtectedHost {

    private lateinit var toolbar: LinearLayout
    private lateinit var urlPillContainer: ViewGroup
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var statusRow: View
    private lateinit var statusLabel: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnClearUrl: ImageButton
    private lateinit var siteSecurityIcon: ImageView
    private lateinit var btnHome: ImageButton
    private lateinit var btnNewTab: ImageButton
    private lateinit var btnTabsContainer: FrameLayout
    private lateinit var btnTabsPlaceholder: TextView
    private lateinit var btnMenu: ImageButton

    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView

    private lateinit var startPageCard: LinearLayout
    private lateinit var startPageIcon: ImageView
    private lateinit var startCardTitle: TextView
    private lateinit var startCardBody: TextView

    private lateinit var vpnBlockedView: LinearLayout
    private lateinit var vpnBlockedIcon: ImageView
    private lateinit var vpnBlockedTitle: TextView
    private lateinit var vpnBlockedBody: TextView

    private lateinit var fullscreenContainer: FrameLayout

    private lateinit var prefs: SnakePreferences
    private lateinit var builtinBlocklist: BlocklistEngine
    @Volatile
    private lateinit var activeBlocklist: BlocklistEngine
    private lateinit var snakeTemplate: String
    private lateinit var captureManager: CaptureManager
    private lateinit var offlinePagesManager: OfflinePagesManager
    private lateinit var downloadHelper: DownloadHelper
    private lateinit var microJitter: MicroJitterController

    private val sessions = mutableListOf<BrowserSession>()
    private lateinit var activeSession: BrowserSession

    private val snakeClient: SnakeClient
        get() = activeSession.snakeClient
    private val snakeChrome: SnakeChrome
        get() = activeSession.snakeChrome
    private var sessionSeed: String
        get() = activeSession.sessionSeed
        set(value) { activeSession.sessionSeed = value }
    private var blockedRequestCount: Int
        get() = activeSession.blockedRequestCount
        set(value) { activeSession.blockedRequestCount = value }
    private var lastLoadedHomeUrl: String
        get() = activeSession.lastLoadedHomeUrl
        set(value) { activeSession.lastLoadedHomeUrl = value }
    private var pendingVpnUrl: String?
        get() = activeSession.pendingVpnUrl
        set(value) { activeSession.pendingVpnUrl = value }
    private val approvedHttpHosts: MutableSet<String>
        get() = activeSession.approvedHttpHosts
    private val blockedHostsOnPage: LinkedHashMap<String, Int>
        get() = activeSession.blockedHostsOnPage
    private var pageHttpBypassed: Boolean
        get() = activeSession.pageHttpBypassed
        set(value) { activeSession.pageHttpBypassed = value }
    private var pageSslBypassed: Boolean
        get() = activeSession.pageSslBypassed
        set(value) { activeSession.pageSslBypassed = value }
    private var pageSslBypassedHost: String?
        get() = activeSession.pageSslBypassedHost
        set(value) { activeSession.pageSslBypassedHost = value }
    private var navigationGeneration: Long
        get() = activeSession.navigationGeneration
        set(value) { activeSession.navigationGeneration = value }

    private var vpnNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var activeMenuPopup: PopupWindow? = null
    private var activeSessionsSheet: com.google.android.material.bottomsheet.BottomSheetDialog? = null
    private var onboardingDialog: AlertDialog? = null
    private var activeSecurityDialog: AlertDialog? = null
    private val transientDialogs = mutableListOf<android.app.Dialog>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var blocklistSignatureCache: Pair<BlocklistEngine, String>? = null
    private var pendingSslHandler: SslErrorHandler? = null
    private var readerOverlay: View? = null
    private var pinOverlay: View? = null
    private var isPinLocked: Boolean = false
    private var pendingInitialLoad: Boolean = false
    private var customFullscreenView: View? = null
    private var customFullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private val unifiedUserAgent: String by lazy { resolveUnifiedUserAgent(this) }
    private val webViewChromeMajor: Int by lazy {
        UA_CHROME_MAJOR_REGEX.find(unifiedUserAgent)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: FALLBACK_CHROME_MAJOR
    }
    private val webViewChromeFullVersion: String? by lazy {
        runCatching { androidx.webkit.WebViewCompat.getCurrentWebViewPackage(this)?.versionName }.getOrNull()
            ?.takeIf { it.startsWith("$webViewChromeMajor.") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = SnakePreferences(this)
        SnakePreferences.applyLocale(prefs.locale)
        PinLockCoordinator.ensureRegistered(this)
        super.onCreate(savedInstanceState)

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        setContentView(R.layout.activity_main)

        bindViews()

        builtinBlocklist = BlocklistEngine.fromAssets(this)
        activeBlocklist = buildEffectiveBlocklist()
        snakeTemplate = SnakeScript.loadTemplate(this)
        captureManager = CaptureManager(this)
        offlinePagesManager = OfflinePagesManager(this)
        downloadHelper = DownloadHelper(
            context = this,
            vpnKillSwitchEnabled = { prefs.vpnKillSwitchEnabled },
            isVpnActive = { VpnGuard.isVpnActive(this) },
            isUrlBlocked = { url ->
                val enabled = prefs.builtinBlocklistEnabled || prefs.parsedCustomRules().isNotEmpty()
                enabled && activeBlocklist.isBlocked(android.net.Uri.parse(url), false)
            }
        )
        microJitter = MicroJitterController(
            targetView = webView,
            minIdleMs = prefs.microJitterMinSec * 1000L,
            maxIdleMs = prefs.microJitterMaxSec * 1000L
        )

        activeSession = BrowserSession(webView = webView)
        sessions.add(activeSession)

        configureWebView(activeSession)
        installSnakeCore(activeSession)
        bindControls()
        updateSessionBadge()
        applyRuntimePreferences()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isPinLocked) {
                    return
                } else if (readerOverlay != null) {
                    closeReaderMode()
                } else if (customFullscreenView != null) {
                    exitFullscreenVideo()
                } else if (findBar.visibility == View.VISIBLE) {
                    closeFindBar()
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        lastLoadedHomeUrl = prefs.homeUrl
        val startAfterWipe = {
            if (PinLockCoordinator.isLocked(prefs)) {
                pendingInitialLoad = true
                showPinLockOverlay()
            } else {
                loadInitialHomePage()
            }
        }
        if (prefs.autoWipeOnExit && prefs.sessionDirty) {
            wipeSession(reloadBlank = false, reseed = true) {
                startAfterWipe()
            }
        } else {
            startAfterWipe()
        }
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        urlPillContainer = findViewById(R.id.urlPillContainer)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)
        urlInput = findViewById(R.id.urlInput)
        statusRow = findViewById(R.id.statusRow)
        statusLabel = findViewById(R.id.statusLabel)
        progressBar = findViewById(R.id.progressBar)
        btnClearUrl = findViewById(R.id.btnClearUrl)
        siteSecurityIcon = findViewById(R.id.siteSecurityIcon)
        btnHome = findViewById(R.id.btnHome)
        btnNewTab = findViewById(R.id.btnNewTab)
        btnTabsContainer = findViewById(R.id.btnTabsContainer)
        btnTabsPlaceholder = findViewById(R.id.btnTabsPlaceholder)
        btnMenu = findViewById(R.id.btnMenu)

        findBar = findViewById(R.id.findBar)
        findInput = findViewById(R.id.findInput)
        findCount = findViewById(R.id.findCount)

        startPageCard = findViewById(R.id.startPageCard)
        startPageIcon = findViewById(R.id.startPageIcon)
        startCardTitle = findViewById(R.id.startCardTitle)
        startCardBody = findViewById(R.id.startCardBody)

        vpnBlockedView = findViewById(R.id.vpnBlockedView)
        vpnBlockedIcon = findViewById(R.id.vpnBlockedIcon)
        vpnBlockedTitle = findViewById(R.id.vpnBlockedTitle)
        vpnBlockedBody = findViewById(R.id.vpnBlockedBody)

        fullscreenContainer = findViewById(R.id.fullscreenContainer)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(session: BrowserSession = activeSession) {
        val targetView = session.webView
        targetView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            safeBrowsingEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            setGeolocationEnabled(false)
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = unifiedUserAgent
        }

        disableRequestedWithHeader(targetView.settings)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
            runCatching {
                val swSettings = androidx.webkit.ServiceWorkerControllerCompat.getInstance().serviceWorkerWebSettings
                if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_CONTENT_ACCESS)) {
                    swSettings.allowContentAccess = false
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_FILE_ACCESS)) {
                    swSettings.allowFileAccess = false
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BLOCK_NETWORK_LOADS)) {
                    swSettings.blockNetworkLoads = true
                }
            }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(targetView, false)
        }

        val client = SnakeClient(
            blocklist = activeBlocklist,
            blocklistEnabled = prefs.builtinBlocklistEnabled || prefs.parsedCustomRules().isNotEmpty(),
            blockExternalSchemes = prefs.blockExternalSchemes,
            vpnKillSwitchEnabled = prefs.vpnKillSwitchEnabled,
            isVpnActive = { VpnGuard.isVpnActive(this) },
            onPageStartedCallback = { url ->
                session.navigationGeneration++
                session.currentUrl = url
                if (url != DEFAULT_START_URL) {
                    prefs.sessionDirty = true
                    session.blockedHostsOnPage.clear()
                    val startedHost = runCatching { Uri.parse(url).host?.lowercase(java.util.Locale.ROOT) }.getOrNull()
                    session.pageHttpBypassed = url.startsWith("http://", ignoreCase = true)
                    session.pageSslBypassed = !startedHost.isNullOrEmpty() && startedHost == session.pageSslBypassedHost
                    if (!session.pageSslBypassed && !startedHost.isNullOrEmpty() && startedHost != session.pageSslBypassedHost) {
                        session.pageSslBypassedHost = null
                    }
                } else {
                    session.resetPageSecurityState()
                    session.currentTitle = ""
                }
                if (session === activeSession) {
                    updateUrlDisplay(url, forceUpdateText = false)
                    updateStartPageCardVisibility(url)
                }
            },
            onPageFinishedCallback = { url ->
                session.currentUrl = url
                val pageTitle = targetView.title?.trim().orEmpty()
                if (pageTitle.isNotEmpty()) {
                    session.currentTitle = pageTitle
                }
                if (session === activeSession) {
                    updateUrlDisplay(url, forceUpdateText = false)
                    updateStartPageCardVisibility(url)
                    progressBar.visibility = View.GONE
                    swipeRefresh.isRefreshing = false
                }
            },
            onBlockedCallback = { blockedUrl ->
                val expectedGen = session.navigationGeneration
                val blockedHost = runCatching { Uri.parse(blockedUrl).host }.getOrNull()
                runOnUiThread {
                    if (expectedGen != session.navigationGeneration) return@runOnUiThread
                    if (!blockedHost.isNullOrBlank()) {
                        session.blockedHostsOnPage[blockedHost] = (session.blockedHostsOnPage[blockedHost] ?: 0) + 1
                    }
                    session.blockedRequestCount++
                    if (session === activeSession) {
                        statusLabel.text = resources.getQuantityString(
                            R.plurals.status_blocked,
                            session.blockedRequestCount,
                            session.blockedRequestCount
                        )
                    }
                }
            },
            onVpnBlockedCallback = { blockedUrl ->
                runOnUiThread {
                    if (session === activeSession) {
                        handleVpnBlocked(blockedUrl ?: targetView.url)
                    } else {
                        session.pendingVpnUrl = blockedUrl ?: targetView.url
                    }
                }
            },
            shouldPromptHttp = { uri ->
                val host = uri.host?.lowercase(java.util.Locale.ROOT).orEmpty()
                host.isEmpty() || host !in session.approvedHttpHosts
            },
            onHttpPromptCallback = { url ->
                runOnUiThread {
                    if (session === activeSession) {
                        promptHttpNavigation(url)
                    }
                }
            },
            onSslErrorCallback = { error, handler ->
                runOnUiThread {
                    if (session === activeSession) {
                        promptSslError(error, handler)
                    } else {
                        handler?.cancel()
                    }
                }
            },
            onExternalSchemeCallback = { uri ->
                if (session === activeSession) {
                    promptExternalScheme(uri)
                } else {
                    true
                }
            },
            onRenderProcessGoneCallback = { _ ->
                runOnUiThread {
                    recoverFromRendererCrash(session)
                }
                true
            }
        )
        session.snakeClient = client
        targetView.webViewClient = client

        val chrome = SnakeChrome(
            suppressDialogs = prefs.suppressJsDialogs,
            onProgressChangedCallback = { progress ->
                if (session === activeSession) {
                    if (progress in 1..99) {
                        progressBar.visibility = View.VISIBLE
                        progressBar.progress = progress
                    } else {
                        progressBar.visibility = View.GONE
                    }
                }
            },
            onTitleChangedCallback = { title ->
                session.currentTitle = title.trim()
            },
            onShowCustomViewCallback = { view, callback ->
                if (session === activeSession) {
                    enterFullscreenVideo(view, callback)
                } else {
                    callback?.onCustomViewHidden()
                }
            },
            onHideCustomViewCallback = {
                if (session === activeSession) {
                    exitFullscreenVideo()
                }
            },
            onFileChooserBlockedCallback = {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && session === activeSession) {
                        Toast.makeText(this, R.string.file_upload_blocked_notice, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
        session.snakeChrome = chrome
        targetView.webChromeClient = chrome

        targetView.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            if (session === activeSession) {
                val shown = if (numberOfMatches > 0) activeMatchOrdinal + 1 else 0
                findCount.text = getString(R.string.find_count_format, shown, numberOfMatches)
            }
        }
    }

    private fun enterFullscreenVideo(view: View?, callback: WebChromeClient.CustomViewCallback?) {
        if (view == null) return
        if (customFullscreenView != null) {
            callback?.onCustomViewHidden()
            return
        }
        customFullscreenView = view
        customFullscreenCallback = callback
        fullscreenContainer.addView(view)
        fullscreenContainer.visibility = View.VISIBLE
        syncMicroJitterState()
    }

    private fun exitFullscreenVideo() {
        val view = customFullscreenView ?: return
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        customFullscreenView = null
        customFullscreenCallback?.onCustomViewHidden()
        customFullscreenCallback = null
        syncMicroJitterState()
    }

    private fun updateUrlDisplay(url: String, forceUpdateText: Boolean = false) {
        if (!urlInput.hasFocus() || forceUpdateText) {
            urlInput.setText(if (url == DEFAULT_START_URL) "" else url)
        }
        val isHttps = url.startsWith("https://", ignoreCase = true)
        siteSecurityIcon.alpha = if (isHttps && !pageSslBypassed) 1.0f else 0.45f
    }

    private fun updateStartPageCardVisibility(currentUrl: String?) {
        val isBlank = currentUrl.isNullOrBlank() || currentUrl == DEFAULT_START_URL
        val shouldShow = isBlank &&
            prefs.homeUrl == DEFAULT_START_URL &&
            !prefs.startHintDismissed
        startPageCard.visibility = if (shouldShow) View.VISIBLE else View.GONE
        swipeRefresh.isEnabled = !shouldShow && vpnBlockedView.visibility != View.VISIBLE
        syncMicroJitterState()
    }

    @SuppressLint("RestrictedApi")
    private fun disableRequestedWithHeader(settings: WebSettings) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        }
    }

    private fun clearScriptHandler(session: BrowserSession = activeSession) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            session.scriptHandler?.remove()
        }
        session.scriptHandler = null
        session.lastSnakeBuildSignature = null
    }

    private fun buildEffectiveBlocklist(): BlocklistEngine {
        val rules = mutableListOf<String>()
        if (prefs.builtinBlocklistEnabled) {
            rules.addAll(builtinBlocklist.rules)
        }
        rules.addAll(prefs.parsedCustomRules())
        return BlocklistEngine.fromRules(rules)
    }

    /** Memoized per [BlocklistEngine] instance so network callbacks don't re-join every rule on the UI thread. */
    private fun blocklistSignature(): String {
        val engine = activeBlocklist
        blocklistSignatureCache?.let { (cached, sig) -> if (cached === engine) return sig }
        val sig = buildString { engine.rules.forEach { append(it).append('\n') } }
        blocklistSignatureCache = engine to sig
        return sig
    }

    private fun installSnakeCore(session: BrowserSession = activeSession) {
        val vpnDownWithKillSwitch = prefs.vpnKillSwitchEnabled && !VpnGuard.isVpnActive(this)
        val flags = prefs.toSnakeFeatureFlags().copy(
            blockWebSockets = vpnDownWithKillSwitch,
            blockWebRtcIce = prefs.blockWebRtcIce || vpnDownWithKillSwitch
        )
        val signature = buildString {
            append(session.sessionSeed).append('|')
            append(flags).append('|')
            append(blocklistSignature())
        }
        if (signature == session.lastSnakeBuildSignature && session.scriptHandler != null) {
            return
        }
        clearScriptHandler(session)
        val compiled = SnakeScript.build(
            template = snakeTemplate,
            seed = session.sessionSeed,
            blocklistRules = activeBlocklist.rules,
            flags = flags,
            chromeMajor = webViewChromeMajor,
            chromeFullVersion = webViewChromeFullVersion
        )
        session.scriptHandler = SnakeScript.install(session.webView, compiled)
        session.lastSnakeBuildSignature = signature
    }

    private fun applyThemePalette() {
        val palette = prefs.themePalette
        val colors = SnakeTheme.resolveBrowserColors(this, palette)
        val density = resources.displayMetrics.density

        SnakeTheme.applySystemBars(this, colors, colors.toolbar)
        findViewById<View>(R.id.rootFrame).setBackgroundColor(colors.bg)
        toolbar.setBackgroundColor(colors.toolbar)
        statusRow.setBackgroundColor(colors.toolbar)
        findBar.setBackgroundColor(colors.toolbar)
        swipeRefresh.setBackgroundColor(colors.bg)
        webView.setBackgroundColor(colors.bg)

        urlPillContainer.background = SnakeTheme.roundedSurface(colors.pill, Color.TRANSPARENT, 21f, density)
        urlInput.setTextColor(colors.text)
        urlInput.setHintTextColor(colors.muted)
        statusLabel.setTextColor(colors.muted)

        findInput.background = SnakeTheme.roundedSurface(colors.pill, Color.TRANSPARENT, 17f, density)
        findInput.setTextColor(colors.text)
        findInput.setHintTextColor(colors.muted)
        findCount.setTextColor(colors.muted)
        findViewById<ImageButton>(R.id.btnFindPrev).setColorFilter(colors.text)
        findViewById<ImageButton>(R.id.btnFindNext).setColorFilter(colors.text)
        findViewById<ImageButton>(R.id.btnFindClose).setColorFilter(colors.text)

        btnHome.setColorFilter(colors.text)
        btnNewTab.setColorFilter(colors.text)
        btnMenu.setColorFilter(colors.text)
        btnClearUrl.setColorFilter(colors.muted)
        btnTabsPlaceholder.setTextColor(colors.text)
        siteSecurityIcon.setColorFilter(colors.muted)

        startPageCard.setBackgroundColor(colors.bg)
        startPageIcon.setColorFilter(colors.muted)
        startCardTitle.setTextColor(colors.text)
        startCardBody.setTextColor(colors.muted)
        findViewById<TextView>(R.id.btnStartConfigure).setTextColor(colors.accent)
        findViewById<TextView>(R.id.btnStartDismiss).setTextColor(colors.muted)

        vpnBlockedView.setBackgroundColor(colors.bg)
        vpnBlockedIcon.setColorFilter(colors.accent)
        vpnBlockedTitle.setTextColor(colors.text)
        vpnBlockedBody.setTextColor(colors.muted)
        findViewById<TextView>(R.id.btnVpnRetry).setTextColor(colors.accent)
        findViewById<TextView>(R.id.btnVpnSettings).setTextColor(colors.muted)

        swipeRefresh.setColorSchemeColors(colors.accent)
        swipeRefresh.setProgressBackgroundColorSchemeColor(colors.card)
        progressBar.progressTintList = ColorStateList.valueOf(colors.accent)
    }

    private fun syncMicroJitterState() {
        val blockedByOverlay = isPinLocked ||
            startPageCard.visibility == View.VISIBLE ||
            vpnBlockedView.visibility == View.VISIBLE ||
            readerOverlay != null ||
            customFullscreenView != null
        if (prefs.microJitterEnabled && !blockedByOverlay && !isFinishing && !isDestroyed) {
            microJitter.start()
        } else {
            microJitter.stop()
        }
    }

    private fun applyRuntimePreferences() {
        applyThemePalette()
        statusRow.visibility = if (prefs.showStatusBar) View.VISIBLE else View.GONE
        activeBlocklist = buildEffectiveBlocklist()

        sessions.forEach { applyRuntimePreferencesToSession(it) }

        microJitter.updateIdleRange(
            prefs.microJitterMinSec * 1000L,
            prefs.microJitterMaxSec * 1000L
        )
        syncMicroJitterState()
        val newHome = prefs.homeUrl
        if (lastLoadedHomeUrl.isNotEmpty() && newHome != lastLoadedHomeUrl) {
            val current = webView.url
            val isOnOldHome = current.isNullOrBlank() ||
                current.trimEnd('/') == lastLoadedHomeUrl.trimEnd('/')
            lastLoadedHomeUrl = newHome
            if (isOnOldHome) {
                safeLoadUrl(newHome)
            } else {
                updateStartPageCardVisibility(current)
            }
        } else {
            updateStartPageCardVisibility(webView.url)
        }
        evaluateVpnState()
    }

    private fun applyRuntimePreferencesToSession(session: BrowserSession) {
        val targetView = session.webView
        targetView.setBackgroundColor(SnakeTheme.resolveBrowserColors(this, prefs.themePalette).bg)
        targetView.keepScreenOn = prefs.keepScreenOn
        targetView.settings.textZoom = prefs.textZoomPercent
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            runCatching {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(targetView.settings, prefs.forceDarkPages)
            }
        }

        if (prefs.downloadsEnabled) {
            if (!session.downloadListenerAttached) {
                targetView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                    if (isFinishing || isDestroyed || isPinLocked) return@setDownloadListener
                    promptDownloadConfirmation(url, userAgent, contentDisposition, mimetype)
                }
                session.downloadListenerAttached = true
            }
        } else if (session.downloadListenerAttached) {
            targetView.setDownloadListener(null)
            session.downloadListenerAttached = false
        }

        session.snakeClient.blocklist = activeBlocklist
        session.snakeClient.blocklistEnabled = activeBlocklist.rules.isNotEmpty()
        session.snakeClient.blockExternalSchemes = prefs.blockExternalSchemes
        session.snakeClient.vpnKillSwitchEnabled = prefs.vpnKillSwitchEnabled
        session.snakeChrome.suppressDialogs = prefs.suppressJsDialogs
    }

    private fun bindControls() {
        swipeRefresh.setOnRefreshListener {
            if (prefs.vpnKillSwitchEnabled && !VpnGuard.isVpnActive(this)) {
                swipeRefresh.isRefreshing = false
                handleVpnBlocked(webView.url)
            } else {
                webView.reload()
            }
        }
        swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            webView.scrollY > 0
        }

        siteSecurityIcon.setOnClickListener {
            openSiteInfoSheet()
        }

        btnHome.setOnClickListener {
            lastLoadedHomeUrl = prefs.homeUrl
            safeLoadUrl(lastLoadedHomeUrl)
        }

        btnNewTab.setOnClickListener {
            createNewSession(loadHome = true, switchTo = true)
        }

        SnakeTheme.attachExpressivePressAnimation(btnTabsContainer, scaleDown = 0.88f)
        btnTabsContainer.setOnClickListener {
            showSessionsSwitcher()
        }

        btnMenu.setOnClickListener { anchor ->
            showCustomOverflowMenu(anchor)
        }

        btnClearUrl.setOnClickListener {
            urlInput.setText("")
        }

        urlInput.setOnFocusChangeListener { _, hasFocus ->
            btnClearUrl.visibility = if (hasFocus && !urlInput.text.isNullOrEmpty()) View.VISIBLE else View.GONE
        }

        urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                btnClearUrl.visibility = if (urlInput.hasFocus() && !s.isNullOrEmpty()) View.VISIBLE else View.GONE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                navigateTo(urlInput.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }

        // Start page text actions
        findViewById<TextView>(R.id.btnStartConfigure).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.btnStartDismiss).setOnClickListener {
            prefs.startHintDismissed = true
            startPageCard.visibility = View.GONE
            swipeRefresh.isEnabled = true
            syncMicroJitterState()
        }

        findViewById<TextView>(R.id.btnVpnRetry).setOnClickListener {
            evaluateVpnState()
        }
        findViewById<TextView>(R.id.btnVpnSettings).setOnClickListener {
            val opened = runCatching {
                startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                true
            }.getOrDefault(false)
            if (!opened) {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }

        // Find-in-page controls
        findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty()
                if (query.isEmpty()) {
                    webView.clearMatches()
                    findCount.text = ""
                } else {
                    webView.findAllAsync(query)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        findViewById<ImageButton>(R.id.btnFindNext).setOnClickListener { webView.findNext(true) }
        findViewById<ImageButton>(R.id.btnFindPrev).setOnClickListener { webView.findNext(false) }
        findViewById<ImageButton>(R.id.btnFindClose).setOnClickListener { closeFindBar() }
    }

    private fun closeFindBar() {
        findBar.visibility = View.GONE
        findInput.setText("")
        findCount.text = ""
        webView.clearMatches()
        hideKeyboard()
    }

    private fun showCustomOverflowMenu(anchor: View) {
        activeMenuPopup?.dismiss()
        val hasPage = !webView.url.isNullOrBlank() && webView.url != DEFAULT_START_URL
        val items = listOf(
            BrowserUiHelper.MenuEntry(R.drawable.ic_menu_back, getString(R.string.btn_back), webView.canGoBack()) {
                if (webView.canGoBack()) webView.goBack()
            },
            BrowserUiHelper.MenuEntry(R.drawable.ic_menu_forward, getString(R.string.btn_forward), webView.canGoForward()) {
                if (webView.canGoForward()) webView.goForward()
            },
            BrowserUiHelper.MenuEntry(R.drawable.ic_menu_reload, getString(R.string.btn_reload), true) {
                if (prefs.vpnKillSwitchEnabled && !VpnGuard.isVpnActive(this)) {
                    handleVpnBlocked(webView.url)
                } else {
                    webView.reload()
                }
            },
            BrowserUiHelper.MenuEntry(R.drawable.ic_content_copy, getString(R.string.btn_copy_url), true) {
                copyCurrentUrl()
            },
            BrowserUiHelper.MenuEntry(R.drawable.ic_menu_find, getString(R.string.btn_find_in_page), true) {
                findBar.visibility = View.VISIBLE
                findInput.requestFocus()
            },
            BrowserUiHelper.MenuEntry(R.drawable.ic_screenshot, getString(R.string.btn_capture), true) {
                handleCaptureClick()
            },
            BrowserUiHelper.MenuEntry(R.drawable.ic_info_outline, getString(R.string.btn_reader_mode), hasPage) {
                openReaderMode()
            },
            BrowserUiHelper.MenuEntry(R.drawable.ic_content_copy, getString(R.string.btn_save_offline), hasPage) {
                saveCurrentPageOffline()
            },
            BrowserUiHelper.MenuEntry(R.drawable.ic_settings_downloads, getString(R.string.btn_downloads), true) {
                startActivity(Intent(this, DownloadsActivity::class.java))
            },
            BrowserUiHelper.MenuEntry(R.drawable.ic_menu_captures, getString(R.string.btn_captures_viewer), true) {
                startActivity(Intent(this, CapturesActivity::class.java))
            },
            BrowserUiHelper.MenuEntry(R.drawable.ic_menu_wipe, getString(R.string.btn_wipe), true) {
                requestWipeSession()
            },
            BrowserUiHelper.MenuEntry(R.drawable.ic_settings_gear, getString(R.string.btn_settings), true) {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        )
        activeMenuPopup = BrowserUiHelper.showOverflowMenu(this, anchor, prefs.themePalette, items)
    }

    private fun copyCurrentUrl() {
        val current = webView.url?.takeIf { it.isNotBlank() } ?: urlInput.text?.toString().orEmpty()
        if (current.isBlank()) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("url", current)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm?.setPrimaryClip(clip)
        Toast.makeText(this, R.string.url_copied, Toast.LENGTH_SHORT).show()
    }

    private fun loadInitialHomePage() {
        val home = prefs.homeUrl
        if (prefs.vpnKillSwitchEnabled && !prefs.vpnBypassStartCheck && !VpnGuard.isVpnActive(this)) {
            pendingVpnUrl = home
            webView.loadUrl(DEFAULT_START_URL)
            showVpnBlockedOverlay()
            return
        }
        safeLoadUrl(home)
    }

    private fun safeLoadUrl(target: String, bypassHttpPrompt: Boolean = false) {
        if (prefs.vpnKillSwitchEnabled && target != DEFAULT_START_URL && !VpnGuard.isVpnActive(this)) {
            pendingVpnUrl = target
            showVpnBlockedOverlay()
            return
        }
        if (!bypassHttpPrompt && target.startsWith("http://", ignoreCase = true)) {
            val host = runCatching { Uri.parse(target).host?.lowercase(java.util.Locale.ROOT) }.getOrNull().orEmpty()
            if (host.isEmpty() || host !in approvedHttpHosts) {
                promptHttpNavigation(target)
                return
            }
        }
        vpnBlockedView.visibility = View.GONE
        if (target != DEFAULT_START_URL) {
            prefs.sessionDirty = true
        }
        urlInput.clearFocus()
        webView.loadUrl(target)
        updateStartPageCardVisibility(target)
    }

    /**
     * Kill-switch must cover every tab, not just the visible one: otherwise background pages keep
     * their open WebSockets / running JS after the VPN drops. Park them on about:blank and remember
     * the URL so it can be restored once the VPN is back.
     */
    private fun suspendBackgroundSessionsForVpnLoss() {
        sessions.forEach { s ->
            if (s === activeSession) return@forEach
            val url = s.webView.url
            if (url.isNullOrBlank() || url == DEFAULT_START_URL) return@forEach
            s.pendingVpnUrl = url
            runCatching {
                s.webView.stopLoading()
                s.webView.loadUrl(DEFAULT_START_URL)
            }
        }
    }

    private fun resumeBackgroundSessionsAfterVpn() {
        sessions.forEach { s ->
            if (s === activeSession) return@forEach
            val pending = s.pendingVpnUrl ?: return@forEach
            s.pendingVpnUrl = null
            runCatching { s.webView.loadUrl(pending) }
        }
    }

    private fun handleVpnBlocked(blockedUrl: String?) {
        if (!prefs.vpnKillSwitchEnabled) return
        if (!blockedUrl.isNullOrBlank() && blockedUrl != DEFAULT_START_URL) {
            pendingVpnUrl = blockedUrl
        }
        webView.stopLoading()
        if (webView.url != null && webView.url != DEFAULT_START_URL) {
            webView.loadUrl(DEFAULT_START_URL)
        }
        progressBar.visibility = View.GONE
        swipeRefresh.isRefreshing = false
        showVpnBlockedOverlay()
    }

    private fun promptHttpNavigation(url: String) {
        if (isFinishing || isDestroyed || isPinLocked) return
        pendingSslHandler?.cancel()
        pendingSslHandler = null
        activeSecurityDialog?.dismiss()
        val host = runCatching { Uri.parse(url).host?.lowercase(java.util.Locale.ROOT) }.getOrNull().orEmpty()
        val displayHost = host.ifEmpty { url }
        activeSecurityDialog = AlertDialog.Builder(this)
            .setTitle(R.string.http_warn_title)
            .setMessage(getString(R.string.http_warn_body, displayHost))
            .setPositiveButton(R.string.http_warn_proceed) { _, _ ->
                if (host.isNotEmpty()) approvedHttpHosts.add(host)
                pageHttpBypassed = true
                safeLoadUrl(url, bypassHttpPrompt = true)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
            .also { SnakeTheme.styleExpressiveDialog(it, prefs.themePalette) }
    }

    private fun promptSslError(error: SslError?, handler: SslErrorHandler?) {
        if (isFinishing || isDestroyed || isPinLocked) {
            handler?.cancel()
            return
        }
        pendingSslHandler?.cancel()
        pendingSslHandler = null
        activeSecurityDialog?.dismiss()
        pendingSslHandler = handler
        val displayUrl = error?.url?.let { runCatching { Uri.parse(it).host }.getOrNull() ?: it } ?: ""
        activeSecurityDialog = AlertDialog.Builder(this)
            .setTitle(R.string.ssl_warn_title)
            .setMessage(getString(R.string.ssl_warn_body, displayUrl))
            .setPositiveButton(R.string.ssl_warn_proceed) { _, _ ->
                pageSslBypassed = true
                pageSslBypassedHost = error?.url?.let {
                    runCatching { Uri.parse(it).host?.lowercase(java.util.Locale.ROOT) }.getOrNull()
                }
                val current = pendingSslHandler
                pendingSslHandler = null
                current?.proceed()
            }
            .setNegativeButton(R.string.action_cancel) { _, _ ->
                val current = pendingSslHandler
                pendingSslHandler = null
                current?.cancel()
            }
            .setOnCancelListener {
                val current = pendingSslHandler
                pendingSslHandler = null
                current?.cancel()
            }
            .setOnDismissListener {
                val current = pendingSslHandler
                pendingSslHandler = null
                current?.cancel()
            }
            .show()
            .also { SnakeTheme.styleExpressiveDialog(it, prefs.themePalette) }
    }

    private fun showVpnBlockedOverlay() {
        vpnBlockedView.visibility = View.VISIBLE
        swipeRefresh.isEnabled = false
        statusLabel.text = getString(R.string.status_vpn_blocked)
        syncMicroJitterState()
    }

    private fun promptExternalScheme(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT).orEmpty()
        if (scheme.isEmpty() || scheme in BLOCKED_INTERNAL_SCHEMES) {
            return true
        }
        if (isFinishing || isDestroyed || isPinLocked) {
            return true
        }
        runOnUiThread {
            if (isFinishing || isDestroyed || isPinLocked) return@runOnUiThread
            pendingSslHandler?.cancel()
            pendingSslHandler = null
            activeSecurityDialog?.dismiss()
            val preview = buildString {
                append(scheme).append(':')
                val part = uri.host?.takeIf { it.isNotBlank() } ?: uri.schemeSpecificPart.orEmpty().take(36)
                append(part)
            }
            activeSecurityDialog = AlertDialog.Builder(this)
                .setTitle(R.string.external_scheme_title)
                .setMessage(getString(R.string.external_scheme_message, preview))
                .setPositiveButton(R.string.external_scheme_open) { _, _ ->
                    launchSanitizedExternalUri(uri, scheme)
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
                .also { SnakeTheme.styleExpressiveDialog(it, prefs.themePalette) }
        }
        return true
    }

    private fun launchSanitizedExternalUri(uri: Uri, scheme: String) {
        val intent = if (scheme == "intent") {
            runCatching {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
            }.getOrNull()
        } else {
            Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
        }
        if (intent != null) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                val fallback = intent.getStringExtra("browser_fallback_url")
                val safeFallback = fallback?.let { SnakePreferences.normalizeUrl(it) }
                if (safeFallback != null) {
                    safeLoadUrl(safeFallback)
                    return
                }
            } catch (_: SecurityException) {
                // External target rejected intent; fall through to notice.
            }
        }
        Toast.makeText(this, getString(R.string.external_scheme_no_app, scheme), Toast.LENGTH_SHORT).show()
    }

    private fun recoverFromRendererCrash(crashedSession: BrowserSession = activeSession) {
        if (isFinishing || isDestroyed) return
        val isCurrent = crashedSession === activeSession
        if (isCurrent) {
            microJitter.stop()
        }
        clearScriptHandler(crashedSession)
        val crashed = crashedSession.webView
        runCatching {
            crashed.stopLoading()
            swipeRefresh.removeView(crashed)
            crashed.destroy()
        }
        val replacement = createSessionWebView().apply {
            if (isCurrent) {
                id = R.id.webView
            }
        }
        crashedSession.webView = replacement
        crashedSession.resetSessionState()
        if (isCurrent) {
            swipeRefresh.addView(replacement)
            webView = replacement
            microJitter = MicroJitterController(
                targetView = webView,
                minIdleMs = prefs.microJitterMinSec * 1000L,
                maxIdleMs = prefs.microJitterMaxSec * 1000L
            )
        }
        configureWebView(crashedSession)
        installSnakeCore(crashedSession)
        applyRuntimePreferencesToSession(crashedSession)
        replacement.loadUrl(DEFAULT_START_URL)
        if (isCurrent) {
            updateStartPageCardVisibility(DEFAULT_START_URL)
            syncMicroJitterState()
            statusLabel.text = getString(R.string.renderer_crashed_notice)
        }
    }

    private fun evaluateVpnState() {
        sessions.forEach { installSnakeCore(it) }
        if (!prefs.vpnKillSwitchEnabled) {
            if (vpnBlockedView.visibility == View.VISIBLE) {
                vpnBlockedView.visibility = View.GONE
                val pending = pendingVpnUrl
                pendingVpnUrl = null
                if (!pending.isNullOrBlank()) {
                    webView.loadUrl(pending)
                    updateStartPageCardVisibility(pending)
                } else {
                    updateStartPageCardVisibility(webView.url)
                }
            } else {
                syncMicroJitterState()
            }
            return
        }
        val vpnUp = VpnGuard.isVpnActive(this)
        if (!vpnUp) {
            suspendBackgroundSessionsForVpnLoss()
            if (!prefs.vpnBypassStartCheck || (webView.url != null && webView.url != DEFAULT_START_URL)) {
                handleVpnBlocked(webView.url ?: pendingVpnUrl ?: prefs.homeUrl)
            }
        } else {
            resumeBackgroundSessionsAfterVpn()
        }
        if (vpnUp && vpnBlockedView.visibility == View.VISIBLE) {
            vpnBlockedView.visibility = View.GONE
            statusLabel.text = getString(R.string.status_ready)
            val toLoad = pendingVpnUrl ?: prefs.homeUrl
            pendingVpnUrl = null
            if (toLoad != DEFAULT_START_URL) {
                prefs.sessionDirty = true
            }
            webView.loadUrl(toLoad)
            updateStartPageCardVisibility(toLoad)
        }
    }

    private fun navigateTo(rawInput: String) {
        val target = SnakePreferences.buildSearchOrUrl(
            rawInput,
            prefs.searchEngine,
            prefs.customSearchTemplate
        ) ?: return
        hideKeyboard()
        safeLoadUrl(target)
    }

    private fun createSessionWebView(): WebView = WebView(this).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }

    private fun updateSessionBadge() {
        btnTabsPlaceholder.text = sessions.size.toString()
        btnTabsPlaceholder.contentDescription = getString(R.string.sessions_badge_desc, sessions.size)
    }

    fun createNewSession(loadHome: Boolean = true, switchTo: Boolean = true): BrowserSession {
        performHaptic()
        val newWebView = createSessionWebView()
        val newSession = BrowserSession(webView = newWebView)
        configureWebView(newSession)
        installSnakeCore(newSession)
        applyRuntimePreferencesToSession(newSession)
        sessions.add(newSession)
        updateSessionBadge()
        if (switchTo) {
            switchToSession(newSession)
            if (loadHome) {
                newSession.lastLoadedHomeUrl = prefs.homeUrl
                safeLoadUrl(newSession.lastLoadedHomeUrl)
            }
            statusLabel.text = getString(R.string.status_new_session)
        } else if (loadHome) {
            newSession.lastLoadedHomeUrl = prefs.homeUrl
            newWebView.loadUrl(newSession.lastLoadedHomeUrl)
        }
        return newSession
    }

    fun switchToSession(target: BrowserSession) {
        if (target !in sessions) return
        if (target !== activeSession) {
            closeFindBar()
            closeReaderMode()
            exitFullscreenVideo()
            pendingSslHandler?.cancel()
            pendingSslHandler = null
            activeSecurityDialog?.dismiss()
            activeSecurityDialog = null

            val outgoing = activeSession
            microJitter.stop()
            outgoing.webView.id = View.NO_ID
            swipeRefresh.removeView(outgoing.webView)

            activeSession = target
            webView = target.webView
            webView.id = R.id.webView
            if (webView.parent == null) {
                swipeRefresh.addView(webView)
            }
            microJitter = MicroJitterController(
                targetView = webView,
                minIdleMs = prefs.microJitterMinSec * 1000L,
                maxIdleMs = prefs.microJitterMaxSec * 1000L
            )
        }

        val currentUrl = webView.url ?: target.currentUrl
        updateUrlDisplay(currentUrl, forceUpdateText = true)
        progressBar.visibility = View.GONE
        swipeRefresh.isRefreshing = false
        applyRuntimePreferencesToSession(target)

        if (prefs.vpnKillSwitchEnabled && !VpnGuard.isVpnActive(this) && target.pendingVpnUrl != null) {
            vpnBlockedView.visibility = View.VISIBLE
            swipeRefresh.isEnabled = false
        } else {
            vpnBlockedView.visibility = View.GONE
            updateStartPageCardVisibility(currentUrl)
        }

        val idx = sessions.indexOf(target) + 1
        statusLabel.text = if (target.blockedRequestCount > 0) {
            resources.getQuantityString(R.plurals.status_blocked, target.blockedRequestCount, target.blockedRequestCount)
        } else {
            getString(R.string.status_switched_session, idx, sessions.size)
        }
        syncMicroJitterState()
    }

    fun closeSession(target: BrowserSession) {
        if (target !in sessions) return
        if (sessions.size <= 1) {
            wipeSession(reloadBlank = true, reseed = true)
            updateSessionBadge()
            return
        }
        val closingIdx = sessions.indexOf(target)
        if (target === activeSession) {
            val nextIdx = if (closingIdx > 0) closingIdx - 1 else 1
            switchToSession(sessions[nextIdx])
        }
        sessions.remove(target)
        destroySessionWebView(target)
        updateSessionBadge()
        statusLabel.text = getString(R.string.status_closed_session)
    }

    private fun destroySessionWebView(session: BrowserSession, clearData: Boolean = false) {
        clearScriptHandler(session)
        session.downloadListenerAttached = false
        runCatching {
            session.webView.stopLoading()
            // Detach callbacks that capture this Activity so a lingering WebView can't retain it.
            session.webView.webViewClient = android.webkit.WebViewClient()
            session.webView.webChromeClient = null
            session.webView.setDownloadListener(null)
            session.webView.setFindListener(null)
            swipeRefresh.removeView(session.webView)
            if (clearData) {
                session.webView.clearCache(true)
                session.webView.clearHistory()
                session.webView.clearFormData()
                session.webView.clearSslPreferences()
            }
            session.webView.destroy()
        }
    }

    private fun showSessionsSwitcher() {
        activeSessionsSheet?.dismiss()
        activeSessionsSheet = BrowserUiHelper.showSessionsSheet(
            activity = this,
            palette = prefs.themePalette,
            sessionsProvider = { sessions.toList() },
            activeSessionIdProvider = { activeSession.id },
            onSelectSession = { chosen -> switchToSession(chosen) },
            onCloseSession = { target -> closeSession(target) },
            onNewSession = { createNewSession(loadHome = true, switchTo = true) },
            onWipeAll = { requestWipeSession() }
        )
    }

    @androidx.annotation.VisibleForTesting
    internal fun getSessionsForTest(): List<BrowserSession> = sessions.toList()

    @androidx.annotation.VisibleForTesting
    internal fun getActiveSessionForTest(): BrowserSession = activeSession

    private fun handleCaptureClick() {
        when (prefs.captureMode) {
            CaptureMode.ASK -> showCaptureChoiceDialog()
            CaptureMode.PRIVATE -> performSilentCapture(CaptureManager.Destination.PRIVATE)
            CaptureMode.GALLERY -> performSilentCapture(CaptureManager.Destination.GALLERY)
        }
    }

    private fun showCaptureChoiceDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.capture_choice_title)
            .setMessage(R.string.capture_choice_message)
            .setPositiveButton(R.string.capture_choice_private) { _, _ ->
                prefs.captureMode = CaptureMode.PRIVATE
                performSilentCapture(CaptureManager.Destination.PRIVATE)
            }
            .setNegativeButton(R.string.capture_choice_gallery) { _, _ ->
                prefs.captureMode = CaptureMode.GALLERY
                performSilentCapture(CaptureManager.Destination.GALLERY)
            }
            .setNeutralButton(" ") { _, _ ->
                val infoDialog = AlertDialog.Builder(this)
                    .setTitle(R.string.capture_info_title)
                    .setMessage(R.string.capture_info_body)
                    .setPositiveButton(R.string.action_ok) { _, _ -> showCaptureChoiceDialog() }
                    .show()
                trackDialog(infoDialog)
                SnakeTheme.styleExpressiveDialog(infoDialog, prefs.themePalette)
            }
            .create()
        trackDialog(dialog)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
            text = ""
            gravity = Gravity.CENTER
            compoundDrawablePadding = 0
            contentDescription = getString(R.string.pref_why_hidden_storage)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_info_outline, 0, 0, 0)
            TextViewCompat.setCompoundDrawableTintList(
                this,
                ColorStateList.valueOf(SnakeTheme.resolveBrowserColors(this@MainActivity, prefs.themePalette).accent)
            )
        }
        SnakeTheme.styleExpressiveDialog(dialog, prefs.themePalette)
    }

    private fun performSilentCapture(destination: CaptureManager.Destination) {
        performHaptic()
        val capturedWebView = webView
        captureManager.captureTo(window, capturedWebView, destination) { result ->
            if (isFinishing || isDestroyed) return@captureTo
            result.onSuccess { outcome ->
                val msg = if (outcome.destination == CaptureManager.Destination.GALLERY) {
                    getString(R.string.status_captured_gallery, outcome.displayName)
                } else {
                    getString(R.string.status_captured, outcome.displayName)
                }
                statusLabel.text = msg
                if (outcome.destination == CaptureManager.Destination.PRIVATE) {
                    Snackbar.make(findViewById<View>(R.id.rootFrame), msg, Snackbar.LENGTH_SHORT)
                        .setAction(R.string.action_open) {
                            startActivity(Intent(this, CapturesActivity::class.java))
                        }
                        .show()
                }
            }.onFailure { err ->
                statusLabel.text = getString(
                    if (err is UnsupportedOperationException) R.string.status_capture_gallery_requires_q
                    else R.string.status_capture_failed
                )
            }
        }
    }

    private fun trackDialog(dialog: android.app.Dialog) {
        transientDialogs.removeAll { !it.isShowing }
        transientDialogs.add(dialog)
    }

    private fun promptDownloadConfirmation(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?
    ) {
        if (isFinishing || isDestroyed || isPinLocked) return
        val name = DownloadHelper.sanitizeFileName(
            android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
        )
        val host = Uri.parse(url).host.orEmpty().ifBlank { url }
        val baseMessage = getString(R.string.download_confirm_message, name, host)
        val message = if (url.trim().startsWith("http://", ignoreCase = true)) {
            baseMessage + getString(R.string.download_confirm_http_warning)
        } else {
            baseMessage
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.download_confirm_title)
            .setMessage(message)
            .setPositiveButton(R.string.download_confirm_action) { _, _ ->
                Toast.makeText(this, getString(R.string.download_started, name), Toast.LENGTH_SHORT).show()
                downloadHelper.download(url, contentDisposition, mimetype, userAgent) { result ->
                    if (isFinishing || isDestroyed) return@download
                    result.onSuccess { savedName ->
                        Toast.makeText(this, getString(R.string.download_saved, savedName), Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
        trackDialog(dialog)
        SnakeTheme.styleExpressiveDialog(dialog, prefs.themePalette)
    }

    private fun requestWipeSession() {
        if (prefs.confirmBeforeWipe) {
            val d = AlertDialog.Builder(this)
                .setTitle(R.string.wipe_confirm_title)
                .setMessage(R.string.wipe_confirm_message)
                .setPositiveButton(R.string.btn_wipe) { _, _ ->
                    performHaptic()
                    wipeSession(reloadBlank = true)
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
            trackDialog(d)
            SnakeTheme.styleExpressiveDialog(d, prefs.themePalette, destructivePositive = true)
        } else {
            performHaptic()
            wipeSession(reloadBlank = true)
        }
    }

    private fun showOnboardingIfNeeded() {
        if (isPinLocked || prefs.onboardingCompleted || onboardingDialog?.isShowing == true) return
        onboardingDialog = BrowserUiHelper.showOnboardingDialog(this, prefs) { dismissed ->
            if (onboardingDialog === dismissed) {
                onboardingDialog = null
            }
        }
    }

    private fun openSiteInfoSheet() {
        val state = SiteInfoSheet.PageSecurityState(
            url = webView.url ?: DEFAULT_START_URL,
            vpnKillSwitchEnabled = prefs.vpnKillSwitchEnabled,
            vpnActive = VpnGuard.isVpnActive(this),
            httpBypassed = pageHttpBypassed,
            sslBypassed = pageSslBypassed,
            blockedHosts = blockedHostsOnPage.toMap(),
            certificate = webView.certificate,
            scriptProtectionActive = activeSession.scriptHandler != null
        )
        val sheet = SiteInfoSheet.show(this, prefs, state) { reloadPage ->
            applyRuntimePreferences()
            installSnakeCore()
            if (reloadPage && webView.url != null && webView.url != DEFAULT_START_URL) {
                webView.reload()
            }
        }
        trackDialog(sheet)
    }

    private fun openReaderMode() {
        val requestedSession = activeSession
        val requestedGeneration = requestedSession.navigationGeneration
        ReaderModeHelper.extract(requestedSession.webView) { article ->
            handleExtractedReaderArticle(requestedSession, requestedGeneration, article)
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun handleExtractedReaderArticle(
        requestedSession: BrowserSession,
        requestedGeneration: Long,
        article: ReaderModeHelper.Article?
    ) {
        if (isFinishing || isDestroyed) return
        // Drop stale results: user switched tab or navigated while extraction was running.
        if (requestedSession !== activeSession || requestedGeneration != requestedSession.navigationGeneration) {
            return
        }
        if (article == null || article.isEmpty) {
            Toast.makeText(this, R.string.reader_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        showReaderOverlay(article)
    }

    @androidx.annotation.VisibleForTesting
    internal fun isReaderOverlayOpenForTest(): Boolean = readerOverlay != null

    private fun showReaderOverlay(article: ReaderModeHelper.Article) {
        closeReaderMode()
        val overlay = ReaderModeHelper.buildOverlayView(this, prefs.themePalette, article) {
            closeReaderMode()
        }
        readerOverlay = overlay
        findViewById<FrameLayout>(R.id.rootFrame).addView(
            overlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        syncMicroJitterState()
    }

    private fun closeReaderMode() {
        val overlay = readerOverlay ?: return
        readerOverlay = null
        findViewById<FrameLayout>(R.id.rootFrame).removeView(overlay)
        syncMicroJitterState()
    }

    private fun saveCurrentPageOffline() {
        performHaptic()
        val targetWebView = webView
        offlinePagesManager.saveCurrentPage(targetWebView, targetWebView.title, targetWebView.url) { result ->
            if (isFinishing || isDestroyed) return@saveCurrentPage
            result.onSuccess { saved ->
                Toast.makeText(this, getString(R.string.offline_saved, saved.title), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this, R.string.offline_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPinLockRequired() {
        showPinLockOverlay()
    }

    override fun onPinUnlocked() {
        unlockPinOverlay()
    }

    override fun onPinAutoWipeRequested() {
        handlePinAutoWipe()
    }

    private fun handlePinAutoWipe() {
        pendingInitialLoad = false
        wipeSession(reloadBlank = true, reseed = true, forceClearPrivateArtifacts = true)
        prefs.scrubSensitiveDataForPanicWipe()
        approvedHttpHosts.clear()
        lastLoadedHomeUrl = DEFAULT_START_URL
        Toast.makeText(this, R.string.pin_wiped, Toast.LENGTH_LONG).show()
        unlockPinOverlay()
    }

    private fun showPinLockOverlay() {
        if (isPinLocked || !prefs.pinLockEnabled) return
        isPinLocked = true
        microJitter.stop()
        activeMenuPopup?.dismiss()
        pendingSslHandler?.cancel()
        pendingSslHandler = null
        activeSecurityDialog?.dismiss()
        activeSecurityDialog = null
        val rootContainer = findViewById<View>(R.id.rootContainer)
        rootContainer.visibility = View.INVISIBLE
        rootContainer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        webView.visibility = View.INVISIBLE
        PinLockCoordinator.hideKeyboard(this, urlInput)

        val rootFrame = findViewById<FrameLayout>(R.id.rootFrame)
        val overlay = PinLockCoordinator.createPinOverlayView(
            activity = this,
            prefs = prefs,
            onUnlocked = { unlockPinOverlay() },
            onAutoWipe = { handlePinAutoWipe() }
        )
        pinOverlay = overlay
        overlay.alpha = 0f
        rootFrame.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.animate().alpha(1f).setDuration(180L).start()
    }

    private fun unlockPinOverlay() {
        val wasLocked = isPinLocked
        isPinLocked = false
        val rootContainer = findViewById<View>(R.id.rootContainer)
        rootContainer.visibility = View.VISIBLE
        rootContainer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        rootContainer.alpha = 0.85f
        rootContainer.animate().alpha(1f).setDuration(160L).start()
        webView.visibility = View.VISIBLE
        val overlay = pinOverlay
        if (overlay != null) {
            pinOverlay = null
            findViewById<FrameLayout>(R.id.rootFrame).removeView(overlay)
        }
        if (wasLocked) {
            if (pendingInitialLoad) {
                pendingInitialLoad = false
                loadInitialHomePage()
            }
            showOnboardingIfNeeded()
        }
        syncMicroJitterState()
    }

    fun webViewClientIsSnake(view: WebView): Boolean = view.webViewClient is SnakeClient

    @androidx.annotation.VisibleForTesting
    internal fun isMicroJitterRunningForTest(): Boolean =
        ::microJitter.isInitialized && microJitter.isRunning

    fun wipeSession(
        reloadBlank: Boolean,
        reseed: Boolean = true,
        forceClearPrivateArtifacts: Boolean = false,
        onComplete: (() -> Unit)? = null
    ) {
        pendingSslHandler?.cancel()
        pendingSslHandler = null
        activeSecurityDialog?.dismiss()
        activeSecurityDialog = null
        activeSessionsSheet?.dismiss()
        activeSessionsSheet = null
        closeFindBar()
        closeReaderMode()
        exitFullscreenVideo()

        // Destroy all background sessions so no hidden tabs or renderers survive a wipe.
        val extraSessions = sessions.filter { it !== activeSession }
        extraSessions.forEach { extra ->
            sessions.remove(extra)
            destroySessionWebView(extra, clearData = true)
        }

        activeSession.resetSessionState()
        webView.stopLoading()
        webView.clearCache(true)
        webView.clearHistory()
        webView.clearFormData()
        webView.clearSslPreferences()
        WebStorage.getInstance().deleteAllData()
        WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
        if (prefs.autoClearOfflineOnWipe || forceClearPrivateArtifacts) {
            OfflinePagesManager(this).clearAll()
            DownloadHistoryStore(this).clear()
        }
        if (prefs.autoClearCapturesOnWipe || forceClearPrivateArtifacts) {
            captureManager.clearCaptures()
        }
        prefs.sessionDirty = false
        if (reseed) {
            sessionSeed = SnakeScript.generateSessionSeed()
            installSnakeCore(activeSession)
        }
        updateSessionBadge()
        if (reloadBlank) {
            urlInput.clearFocus()
            urlInput.setText("")
            statusLabel.text = getString(R.string.status_wiped)
            webView.loadUrl(DEFAULT_START_URL)
            updateStartPageCardVisibility(DEFAULT_START_URL)
        }

        val cookieManager = CookieManager.getInstance()
        if (onComplete != null) {
            val finished = java.util.concurrent.atomic.AtomicBoolean(false)
            val finishCookieClear = {
                if (finished.compareAndSet(false, true)) {
                    onComplete.invoke()
                }
            }
            cookieManager.removeAllCookies {
                cookieManager.flush()
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    finishCookieClear()
                } else {
                    mainHandler.post { finishCookieClear() }
                }
            }
            cookieManager.flush()
            if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
                finishCookieClear()
            } else {
                mainHandler.postDelayed({ finishCookieClear() }, 150L)
            }
        } else {
            cookieManager.removeAllCookies {
                cookieManager.flush()
            }
            cookieManager.flush()
        }
    }

    private fun performHaptic() {
        if (prefs.hapticFeedbackEnabled) {
            webView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun hideKeyboard() {
        urlInput.clearFocus()
        findInput.clearFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(urlInput.windowToken, 0)
        webView.requestFocus()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!microJitter.isDispatchingSynthetic) {
            microJitter.onRealUserInteraction()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onStart() {
        super.onStart()
        registerVpnNetworkCallback()
    }

    override fun onStop() {
        unregisterVpnNetworkCallback()
        super.onStop()
    }

    private fun registerVpnNetworkCallback() {
        if (vpnNetworkCallback != null) return
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { if (!isFinishing && !isDestroyed) evaluateVpnState() }
            }

            override fun onLost(network: Network) {
                runOnUiThread { if (!isFinishing && !isDestroyed) evaluateVpnState() }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                runOnUiThread { if (!isFinishing && !isDestroyed) evaluateVpnState() }
            }
        }
        runCatching {
            cm.registerDefaultNetworkCallback(cb)
            vpnNetworkCallback = cb
        }
    }

    private fun unregisterVpnNetworkCallback() {
        val cb = vpnNetworkCallback ?: return
        vpnNetworkCallback = null
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
    }

    override fun onResume() {
        super.onResume()
        applyRuntimePreferences()
        // Enforce before showOnboardingIfNeeded(), since onActivityResumed dispatches after onResume().
        PinLockCoordinator.enforceLockIfNeeded(this)
        showOnboardingIfNeeded()
    }

    override fun onPause() {
        activeMenuPopup?.dismiss()
        microJitter.stop()
        super.onPause()
        // Intentionally avoid webView.onPause() / pauseTimers() to minimize background timing leaks.
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyThemePalette()
        urlInput.hint = getString(R.string.url_hint)
        findInput.hint = getString(R.string.find_hint)
        startCardTitle.text = getString(R.string.start_card_title)
        startCardBody.text = getString(R.string.start_card_body)
        findViewById<TextView>(R.id.btnStartConfigure).text = getString(R.string.start_card_configure)
        findViewById<TextView>(R.id.btnStartDismiss).text = getString(R.string.start_card_dismiss)
        vpnBlockedTitle.text = getString(R.string.vpn_blocked_title)
        vpnBlockedBody.text = getString(R.string.vpn_blocked_body)
        findViewById<TextView>(R.id.btnVpnRetry).text = getString(R.string.vpn_retry)
        findViewById<TextView>(R.id.btnVpnSettings).text = getString(R.string.vpn_open_settings)
    }

    override fun onDestroy() {
        activeMenuPopup?.dismiss()
        activeSessionsSheet?.dismiss()
        onboardingDialog?.dismiss()
        pendingSslHandler?.cancel()
        pendingSslHandler = null
        activeSecurityDialog?.dismiss()
        activeSecurityDialog = null
        transientDialogs.toList().forEach { runCatching { it.dismiss() } }
        transientDialogs.clear()
        mainHandler.removeCallbacksAndMessages(null)
        microJitter.stop()
        // Do not wipe on configuration-change recreation (rotation / dynamic palette change).
        if (prefs.autoWipeOnExit && !isChangingConfigurations) {
            wipeSession(reloadBlank = false, reseed = false)
        }
        captureManager.shutdown()
        downloadHelper.shutdown()
        sessions.toList().forEach { session ->
            destroySessionWebView(session)
        }
        sessions.clear()
        super.onDestroy()
    }

    companion object {
        const val DEFAULT_START_URL = "about:blank"
        private const val FALLBACK_CHROME_MAJOR = 124
        const val UNIFIED_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private val BLOCKED_INTERNAL_SCHEMES = setOf("javascript", "data", "file", "content", "blob")
        private val VERSION_MAJOR_REGEX = Regex("""^(\d+)\.""")
        private val UA_CHROME_MAJOR_REGEX = Regex("""Chrome/(\d+)\.""")

        fun buildUnifiedUserAgent(chromeMajor: Int): String =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeMajor.0.0.0 Mobile Safari/537.36"

        fun resolveUnifiedUserAgent(context: android.content.Context): String {
            val pkgVersion = runCatching {
                androidx.webkit.WebViewCompat.getCurrentWebViewPackage(context)?.versionName
            }.getOrNull()
            val pkgMajor = pkgVersion?.let { VERSION_MAJOR_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            if (pkgMajor != null && pkgMajor >= 100) {
                return buildUnifiedUserAgent(pkgMajor)
            }
            val defaultUa = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
            val uaMajor = defaultUa?.let { UA_CHROME_MAJOR_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            if (uaMajor != null && uaMajor >= 100) {
                return buildUnifiedUserAgent(uaMajor)
            }
            return buildUnifiedUserAgent(FALLBACK_CHROME_MAJOR)
        }

        fun normalizeUrl(rawInput: String): String? =
            SnakePreferences.normalizeUrl(rawInput)
    }
}
