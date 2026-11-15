package com.nromanovich.stealthsnake

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
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
import android.os.Message
import android.os.PersistableBundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.Browser
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
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.ProfileStore
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

    private lateinit var webViewContainer: View
    private lateinit var errorOverlay: LinearLayout
    private lateinit var errorOverlayIcon: ImageView
    private lateinit var errorOverlayTitle: TextView
    private lateinit var errorOverlayBody: TextView
    private lateinit var fullscreenContainer: FrameLayout

    private lateinit var prefs: SnakePreferences
    private lateinit var builtinBlocklist: BlocklistEngine
    @Volatile
    private lateinit var activeBlocklist: BlocklistEngine
    private lateinit var snakeTemplate: String
    private lateinit var captureManager: CaptureManager
    private lateinit var offlinePagesManager: OfflinePagesManager
    private lateinit var downloadHelper: DownloadHelper
    private lateinit var encryptedSessionStore: EncryptedSessionStore
    private lateinit var microJitter: MicroJitterController

    private val sessions = mutableListOf<BrowserSession>()
    @Volatile
    private lateinit var activeSession: BrowserSession
    @Volatile
    private var activeSessionCookieManager: CookieManager? = null

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
    private var pendingRestoreAfterPinUrl: String? = null
    private var pendingExternalNewSession: Boolean = false
    private var lastErrorUrl: String?
        get() = activeSession.errorUrl
        set(value) { activeSession.errorUrl = value }
    private var pendingFileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var fileUploadExecutor: java.util.concurrent.ExecutorService? = null
    private val fileUploadGeneration = AtomicLong(0L)
    private var activePopupDialog: AlertDialog? = null
    private var activePopupWebView: WebView? = null
    private val activePopupExtractors = mutableSetOf<WebView>()
    private var customFullscreenView: View? = null
    private var customFullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenRotateButton: ImageButton? = null
    private var frozenPortraitSize: Pair<Int, Int>? = null
    private var lastImeBottomPx: Int = 0

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleFileChooserResult(result.resultCode, result.data)
    }

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
        encryptedSessionStore = EncryptedSessionStore(this)
        downloadHelper = DownloadHelper(
            context = this,
            vpnKillSwitchEnabled = { prefs.vpnKillSwitchEnabled },
            isVpnActive = { VpnGuard.isVpnActive(this) },
            isUrlBlocked = { url ->
                val policy = resolveSessionPolicy(activeSession)
                val enabled = policy.builtinBlocklistEnabled || policy.customBlocklistRaw.isNotBlank()
                enabled && buildSessionBlocklist(policy).isBlocked(android.net.Uri.parse(url), false)
            },
            cookieProvider = { url ->
                val cm = activeSessionCookieManager ?: CookieManager.getInstance()
                runCatching { cm.getCookie(url) }.getOrNull()
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
                } else if (errorOverlay.visibility == View.VISIBLE) {
                    val canBack = webView.canGoBack()
                    hideErrorOverlay(updateStartCard = !canBack)
                    if (canBack) {
                        webView.goBack()
                    } else {
                        activeSession.currentUrl = DEFAULT_START_URL
                        updateUrlDisplay(DEFAULT_START_URL, forceUpdateText = true)
                        updateStartPageCardVisibility(DEFAULT_START_URL)
                    }
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        lastLoadedHomeUrl = prefs.homeUrl
        val incomingExternalUrl = if (savedInstanceState == null) extractExternalViewUrl(intent) else null
        val inMemoryRestore = savedInstanceState?.getString(STATE_RESTORE_TOKEN)
            ?.let { ephemeralRestoreStates.remove(it) }
        val startAfterWipe = {
            val restoredState = inMemoryRestore
                ?: if (!prefs.autoWipeOnExit) encryptedSessionStore.loadTabs(autoWipeOnExit = false) else null
            if (restoredState != null && restoredState.tabs.isNotEmpty()) {
                applyRestoredSessionState(restoredState)
                if (incomingExternalUrl != null) {
                    createNewSession(loadHome = false, switchTo = true)
                    Toast.makeText(this, R.string.external_link_opened_new_session, Toast.LENGTH_SHORT).show()
                    if (PinLockCoordinator.isLocked(prefs)) {
                        pendingInitialLoad = true
                        pendingRestoreAfterPinUrl = incomingExternalUrl
                        showPinLockOverlay()
                    } else {
                        safeLoadUrl(incomingExternalUrl)
                    }
                } else {
                    val targetUrl = activeSession.currentUrl
                    if (PinLockCoordinator.isLocked(prefs)) {
                        pendingInitialLoad = true
                        pendingRestoreAfterPinUrl = targetUrl
                        showPinLockOverlay()
                    } else if (targetUrl.isNotBlank() && targetUrl != DEFAULT_START_URL) {
                        safeLoadUrl(targetUrl)
                    } else {
                        loadInitialHomePage()
                    }
                }
            } else if (incomingExternalUrl != null) {
                Toast.makeText(this, R.string.external_link_opened_new_session, Toast.LENGTH_SHORT).show()
                if (PinLockCoordinator.isLocked(prefs)) {
                    pendingInitialLoad = true
                    pendingRestoreAfterPinUrl = incomingExternalUrl
                    showPinLockOverlay()
                } else {
                    safeLoadUrl(incomingExternalUrl)
                }
            } else if (PinLockCoordinator.isLocked(prefs)) {
                pendingInitialLoad = true
                showPinLockOverlay()
            } else {
                loadInitialHomePage()
            }
        }
        if (prefs.autoWipeOnExit && prefs.sessionDirty && inMemoryRestore == null) {
            wipeSession(reloadBlank = false, reseed = true) {
                startAfterWipe()
            }
        } else {
            startAfterWipe()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val externalUrl = extractExternalViewUrl(intent) ?: return
        if (isPinLocked || PinLockCoordinator.isLocked(prefs)) {
            pendingInitialLoad = true
            pendingRestoreAfterPinUrl = externalUrl
            pendingExternalNewSession = true
            if (!isPinLocked) showPinLockOverlay()
        } else {
            createNewSession(loadHome = false, switchTo = true)
            Toast.makeText(this, R.string.external_link_opened_new_session, Toast.LENGTH_SHORT).show()
            safeLoadUrl(externalUrl)
        }
    }

    private fun extractExternalViewUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val data = intent.data ?: return null
        val scheme = data.scheme?.lowercase(java.util.Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        // Drop caller-supplied HTTP headers/extras so external apps cannot inject custom request headers.
        intent.removeExtra(Browser.EXTRA_HEADERS)
        val normalized = SnakePreferences.normalizeUrl(data.toString()) ?: return null
        val normalizedScheme = runCatching { Uri.parse(normalized).scheme?.lowercase(java.util.Locale.ROOT) }.getOrNull()
        return normalized.takeIf { normalizedScheme == "http" || normalizedScheme == "https" }
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        urlPillContainer = findViewById(R.id.urlPillContainer)
        webViewContainer = findViewById(R.id.webViewContainer)
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

        errorOverlay = findViewById(R.id.errorOverlay)
        errorOverlayIcon = findViewById(R.id.errorOverlayIcon)
        errorOverlayTitle = findViewById(R.id.errorOverlayTitle)
        errorOverlayBody = findViewById(R.id.errorOverlayBody)

        fullscreenContainer = findViewById(R.id.fullscreenContainer)

        val chromeElevationPx = 8f * resources.displayMetrics.density
        toolbar.translationZ = chromeElevationPx
        findBar.translationZ = chromeElevationPx
        statusRow.translationZ = chromeElevationPx
        progressBar.translationZ = chromeElevationPx

        val rootFrame = findViewById<View>(R.id.rootFrame)
        ViewCompat.setOnApplyWindowInsetsListener(rootFrame) { v, insets ->
            lastImeBottomPx = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            updateWebViewImeTranslation()
            ViewCompat.onApplyWindowInsets(v, insets)
        }
    }

    private fun hasBlockingDialogOpen(): Boolean =
        activeSecurityDialog?.isShowing == true ||
            activePopupDialog?.isShowing == true ||
            activeSessionsSheet?.isShowing == true ||
            onboardingDialog?.isShowing == true ||
            transientDialogs.any { it.isShowing }

    private fun updateWebViewImeTranslation() {
        val shouldShift = lastImeBottomPx > 0 &&
            !urlInput.hasFocus() &&
            !findInput.hasFocus() &&
            !isPinLocked &&
            customFullscreenView == null &&
            !hasBlockingDialogOpen()
        if (shouldShift) {
            val containerH = webViewContainer.height
            val maxShift = if (containerH > 0) (containerH * 0.45f) else (180f * resources.displayMetrics.density)
            webViewContainer.translationY = -lastImeBottomPx.toFloat().coerceAtMost(maxShift)
        } else {
            webViewContainer.translationY = 0f
        }
    }

    private fun applySessionWebViewProfile(targetView: WebView, session: BrowserSession) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            runCatching {
                WebViewCompat.setProfile(targetView, session.webViewProfileName)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(session: BrowserSession = activeSession) {
        val targetView = session.webView
        applySessionWebViewProfile(targetView, session)
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
            setSupportMultipleWindows(true)
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
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            runCatching {
                val profile = ProfileStore.getInstance().getOrCreateProfile(session.webViewProfileName)
                runCatching {
                    profile.serviceWorkerController.serviceWorkerWebSettings.apply {
                        allowContentAccess = false
                        allowFileAccess = false
                        blockNetworkLoads = true
                    }
                }
                runCatching { profile.geolocationPermissions.clearAll() }
                profile.cookieManager.apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(targetView, false)
                }
                if (session === activeSession) {
                    activeSessionCookieManager = profile.cookieManager
                }
            }
        }

        val sessionPolicy = resolveSessionPolicy(session)
        val sessionBlocklist = buildSessionBlocklist(sessionPolicy)
        val client = SnakeClient(
            blocklist = sessionBlocklist,
            blocklistEnabled = sessionPolicy.builtinBlocklistEnabled || sessionPolicy.parsedCustomRules().isNotEmpty(),
            blockExternalSchemes = sessionPolicy.blockExternalSchemes,
            vpnKillSwitchEnabled = prefs.vpnKillSwitchEnabled,
            isVpnActive = { VpnGuard.isVpnActive(this) },
            onPageStartedCallback = { url ->
                session.navigationGeneration++
                val keepingErrorOverlay = url == DEFAULT_START_URL && session.errorUrl != null
                if (keepingErrorOverlay) {
                    session.currentUrl = session.errorUrl!!
                    return@SnakeClient
                }
                session.currentUrl = url
                if (url != DEFAULT_START_URL) {
                    session.errorUrl = null
                    session.errorDescription = ""
                    session.errorCode = -1
                    if (session === activeSession) {
                        hideErrorOverlay(updateStartCard = false)
                    }
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
                val keepingErrorOverlay = url == DEFAULT_START_URL && session.errorUrl != null
                if (keepingErrorOverlay) {
                    session.currentUrl = session.errorUrl!!
                    if (session === activeSession) {
                        progressBar.visibility = View.GONE
                        swipeRefresh.isRefreshing = false
                        startPageCard.visibility = View.GONE
                    }
                    return@SnakeClient
                }
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
            },
            onPageErrorCallback = { failingUrl, errorCode, description ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (failingUrl.isBlank() || failingUrl == DEFAULT_START_URL) return@runOnUiThread
                    if (session === activeSession) {
                        showPageErrorOverlay(failingUrl, description, errorCode)
                    } else {
                        session.errorUrl = failingUrl
                        session.errorDescription = description
                        session.errorCode = errorCode
                        session.currentUrl = failingUrl
                        if (session.isWebViewAttached) {
                            session.webView.stopLoading()
                            if (session.webView.url != DEFAULT_START_URL) {
                                session.webView.loadUrl(DEFAULT_START_URL)
                            }
                        }
                    }
                }
            },
            onHttpAuthRequestCallback = { view, handler, host, realm ->
                runOnUiThread {
                    if (session === activeSession && !isFinishing && !isDestroyed && !isPinLocked) {
                        promptHttpAuth(session, view, handler, host, realm)
                    } else {
                        handler?.cancel()
                    }
                }
            }
        )
        session.snakeClient = client
        targetView.webViewClient = client

        val chrome = SnakeChrome(
            suppressDialogs = sessionPolicy.suppressJsDialogs,
            allowFileUploads = sessionPolicy.allowFileUploads,
            allowOAuthPopups = sessionPolicy.allowOAuthPopups,
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
            },
            onOpenFileChooserCallback = { callback, params ->
                if (session === activeSession && !isFinishing && !isDestroyed && !isPinLocked) {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        launchSanitizedFileChooser(callback, params)
                    } else {
                        runOnUiThread { launchSanitizedFileChooser(callback, params) }
                    }
                    true
                } else {
                    false
                }
            },
            onOpenPopupWindowCallback = { isUserGesture, resultMsg, allowPopup ->
                if (isUserGesture && resultMsg != null && session === activeSession && !isFinishing && !isDestroyed && !isPinLocked) {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        handlePopupWindowRequest(session, resultMsg, allowPopup)
                    } else {
                        runOnUiThread { handlePopupWindowRequest(session, resultMsg, allowPopup) }
                    }
                    true
                } else {
                    false
                }
            },
            onCloseWindowCallback = { closedView ->
                runOnUiThread {
                    if (closedView != null && closedView === activePopupWebView) {
                        closeActivePopupWindow()
                    }
                }
            }
        )
        session.snakeChrome = chrome
        targetView.webChromeClient = chrome

        targetView.setOnLongClickListener {
            if (isPinLocked || session !== activeSession) false else handleWebViewLongClick(targetView)
        }

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
        val density = resources.displayMetrics.density
        val sizePx = (44 * density).toInt()
        val marginPx = (16 * density).toInt()
        val rotateBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_rotate)
            setColorFilter(Color.WHITE)
            background = SnakeTheme.roundedSurface(0x99000000.toInt(), Color.TRANSPARENT, 22f, density)
            contentDescription = getString(R.string.fullscreen_rotate_desc)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx, Gravity.TOP or Gravity.END).apply {
                topMargin = marginPx
                marginEnd = marginPx
            }
            setOnClickListener { toggleFullscreenOrientation() }
        }
        fullscreenRotateButton = rotateBtn
        fullscreenContainer.addView(rotateBtn)
        fullscreenContainer.visibility = View.VISIBLE
        updateWebViewImeTranslation()
        syncMicroJitterState()
    }

    @androidx.annotation.VisibleForTesting
    internal fun toggleFullscreenOrientation() {
        val rootContainer = findViewById<View>(R.id.rootContainer)
        if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE || frozenPortraitSize != null) {
            frozenPortraitSize = null
            rootContainer.layoutParams = rootContainer.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            val dm = resources.displayMetrics
            val rawW = if (rootContainer.width > 0) rootContainer.width else dm.widthPixels
            val rawH = if (rootContainer.height > 0) rootContainer.height else dm.heightPixels
            val portraitW = minOf(rawW, rawH).coerceAtLeast(1)
            val portraitH = maxOf(rawW, rawH).coerceAtLeast(1)
            frozenPortraitSize = portraitW to portraitH
            rootContainer.layoutParams = rootContainer.layoutParams.apply {
                width = portraitW
                height = portraitH
            }
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun exitFullscreenVideo() {
        val view = customFullscreenView ?: return
        fullscreenContainer.removeView(view)
        fullscreenRotateButton?.let { fullscreenContainer.removeView(it) }
        fullscreenRotateButton = null
        fullscreenContainer.visibility = View.GONE
        customFullscreenView = null
        customFullscreenCallback?.onCustomViewHidden()
        customFullscreenCallback = null
        if (frozenPortraitSize != null || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            frozenPortraitSize = null
            val rootContainer = findViewById<View>(R.id.rootContainer)
            rootContainer.layoutParams = rootContainer.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        updateWebViewImeTranslation()
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
            !prefs.startHintDismissed &&
            errorOverlay.visibility != View.VISIBLE
        startPageCard.visibility = if (shouldShow) View.VISIBLE else View.GONE
        swipeRefresh.isEnabled = !shouldShow &&
            vpnBlockedView.visibility != View.VISIBLE &&
            errorOverlay.visibility != View.VISIBLE
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

    private fun resolveSessionPolicy(session: BrowserSession = activeSession): SnakePreferences.EffectivePolicy =
        prefs.resolvePolicy(session.presetOverride, session.profileIdOverride)

    private fun buildEffectiveBlocklist(): BlocklistEngine {
        val rules = mutableListOf<String>()
        if (prefs.builtinBlocklistEnabled) {
            rules.addAll(builtinBlocklist.rules)
        }
        rules.addAll(prefs.parsedCustomRules())
        return BlocklistEngine.fromRules(rules)
    }

    private fun buildSessionBlocklist(policy: SnakePreferences.EffectivePolicy): BlocklistEngine {
        if (policy.builtinBlocklistEnabled == prefs.builtinBlocklistEnabled &&
            policy.customBlocklistRaw == prefs.customBlocklistRaw
        ) {
            return activeBlocklist
        }
        val rules = mutableListOf<String>()
        if (policy.builtinBlocklistEnabled) {
            rules.addAll(builtinBlocklist.rules)
        }
        rules.addAll(policy.parsedCustomRules())
        return BlocklistEngine.fromRules(rules)
    }

    /** Memoized per [BlocklistEngine] instance so network callbacks don't re-join every rule on the UI thread. */
    private fun blocklistSignatureFor(engine: BlocklistEngine): String {
        if (engine === activeBlocklist) {
            blocklistSignatureCache?.let { (cached, sig) -> if (cached === engine) return sig }
            val sig = buildString { engine.rules.forEach { append(it).append('\n') } }
            blocklistSignatureCache = engine to sig
            return sig
        }
        return buildString { engine.rules.forEach { append(it).append('\n') } }
    }

    private fun installSnakeCore(session: BrowserSession = activeSession) {
        if (!session.isWebViewAttached) return
        val policy = resolveSessionPolicy(session)
        val sessionBlocklist = buildSessionBlocklist(policy)
        val vpnDownWithKillSwitch = prefs.vpnKillSwitchEnabled && !VpnGuard.isVpnActive(this)
        val flags = policy.toSnakeFeatureFlags().copy(
            blockWebSockets = vpnDownWithKillSwitch,
            blockWebRtcIce = policy.blockWebRtcIce || vpnDownWithKillSwitch
        )
        val signature = buildString {
            append(session.sessionSeed).append('|')
            append(flags).append('|')
            append(blocklistSignatureFor(sessionBlocklist))
        }
        if (signature == session.lastSnakeBuildSignature && session.scriptHandler != null) {
            return
        }
        clearScriptHandler(session)
        val compiled = SnakeScript.build(
            template = snakeTemplate,
            seed = session.sessionSeed,
            blocklistRules = sessionBlocklist.rules,
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

        errorOverlay.setBackgroundColor(colors.bg)
        errorOverlayIcon.setColorFilter(colors.accent)
        errorOverlayTitle.setTextColor(colors.text)
        errorOverlayBody.setTextColor(colors.muted)
        findViewById<TextView>(R.id.btnErrorRetry).setTextColor(colors.accent)

        swipeRefresh.setColorSchemeColors(colors.accent)
        swipeRefresh.setProgressBackgroundColorSchemeColor(colors.card)
        progressBar.progressTintList = ColorStateList.valueOf(colors.accent)
    }

    private fun syncMicroJitterState() {
        val blockedByOverlay = isPinLocked ||
            startPageCard.visibility == View.VISIBLE ||
            vpnBlockedView.visibility == View.VISIBLE ||
            errorOverlay.visibility == View.VISIBLE ||
            readerOverlay != null ||
            customFullscreenView != null
        val jitterEnabled = resolveSessionPolicy(activeSession).microJitterEnabled
        if (jitterEnabled && !blockedByOverlay && !isFinishing && !isDestroyed) {
            microJitter.start()
        } else {
            microJitter.stop()
        }
    }

    private fun applyRuntimePreferences() {
        applyThemePalette()
        statusRow.visibility = if (prefs.showStatusBar) View.VISIBLE else View.GONE
        activeBlocklist = buildEffectiveBlocklist()

        sessions.forEach {
            if (it.isWebViewAttached) {
                applyRuntimePreferencesToSession(it)
            }
        }

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
        if (!session.isWebViewAttached) return
        val targetView = session.webView
        val policy = resolveSessionPolicy(session)
        val sessionBlocklist = buildSessionBlocklist(policy)
        targetView.setBackgroundColor(SnakeTheme.resolveBrowserColors(this, prefs.themePalette).bg)
        targetView.keepScreenOn = prefs.keepScreenOn
        targetView.settings.textZoom = prefs.textZoomPercent
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            runCatching {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(targetView.settings, policy.forceDarkPages)
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

        session.snakeClient.blocklist = sessionBlocklist
        session.snakeClient.blocklistEnabled = policy.builtinBlocklistEnabled || sessionBlocklist.rules.isNotEmpty()
        session.snakeClient.blockExternalSchemes = policy.blockExternalSchemes
        session.snakeClient.vpnKillSwitchEnabled = prefs.vpnKillSwitchEnabled
        session.snakeChrome.suppressDialogs = policy.suppressJsDialogs
        session.snakeChrome.allowFileUploads = policy.allowFileUploads
        session.snakeChrome.allowOAuthPopups = policy.allowOAuthPopups
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
            updateWebViewImeTranslation()
        }

        findInput.setOnFocusChangeListener { _, _ ->
            updateWebViewImeTranslation()
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
            updateStartPageCardVisibility(webView.url)
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
        findViewById<TextView>(R.id.btnErrorRetry).setOnClickListener {
            val target = lastErrorUrl ?: webView.url?.takeIf { it != DEFAULT_START_URL } ?: prefs.homeUrl
            hideErrorOverlay(updateStartCard = false)
            safeLoadUrl(target)
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
        updateWebViewImeTranslation()
    }

    private fun showCustomOverflowMenu(anchor: View) {
        activeMenuPopup?.dismiss()
        val hasLoadedPage = !webView.url.isNullOrBlank() && webView.url != DEFAULT_START_URL
        val currentUrl = webView.url?.takeIf { it.isNotBlank() && it != DEFAULT_START_URL }
            ?: lastErrorUrl?.takeIf { errorOverlay.visibility == View.VISIBLE }
        val hasPage = !currentUrl.isNullOrBlank()
        val isBookmarked = hasPage && encryptedSessionStore.isBookmarked(currentUrl!!)
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
                } else if (errorOverlay.visibility == View.VISIBLE && !lastErrorUrl.isNullOrBlank()) {
                    val target = lastErrorUrl!!
                    hideErrorOverlay(updateStartCard = false)
                    safeLoadUrl(target)
                } else {
                    webView.reload()
                }
            },
            BrowserUiHelper.MenuEntry(R.drawable.ic_content_copy, getString(R.string.btn_copy_url), true) {
                copyCurrentUrl()
            },
            BrowserUiHelper.MenuEntry(R.drawable.ic_menu_share, getString(R.string.menu_share_url), hasPage) {
                shareCurrentUrl()
            },
            BrowserUiHelper.MenuEntry(R.drawable.ic_menu_find, getString(R.string.btn_find_in_page), true) {
                findBar.visibility = View.VISIBLE
                findInput.requestFocus()
            },
            BrowserUiHelper.MenuEntry(R.drawable.ic_menu_bookmarks, getString(R.string.menu_bookmarks), true) {
                openBookmarksSheet()
            },
            BrowserUiHelper.MenuEntry(
                if (isBookmarked) R.drawable.ic_menu_bookmark_added else R.drawable.ic_menu_bookmark_add,
                getString(if (isBookmarked) R.string.menu_bookmark_remove else R.string.menu_bookmark_add),
                hasPage
            ) {
                toggleBookmark()
            },
            BrowserUiHelper.MenuEntry(R.drawable.ic_screenshot, getString(R.string.btn_capture), true) {
                handleCaptureClick()
            },
            BrowserUiHelper.MenuEntry(R.drawable.ic_menu_reader, getString(R.string.btn_reader_mode), hasLoadedPage) {
                openReaderMode()
            },
            BrowserUiHelper.MenuEntry(R.drawable.ic_content_copy, getString(R.string.btn_save_offline), hasLoadedPage) {
                saveCurrentPageOffline()
            },
            BrowserUiHelper.MenuEntry(R.drawable.ic_menu_print_pdf, getString(R.string.menu_print_pdf), hasLoadedPage) {
                printCurrentPage()
            },
            BrowserUiHelper.MenuEntry(R.drawable.ic_settings_downloads, getString(R.string.btn_downloads), true) {
                startActivity(Intent(this, DownloadsActivity::class.java))
            },
            BrowserUiHelper.MenuEntry(R.drawable.ic_menu_captures, getString(R.string.btn_captures_viewer), true) {
                startActivity(Intent(this, CapturesActivity::class.java))
            },
            BrowserUiHelper.MenuEntry(R.drawable.ic_menu_clear_site, getString(R.string.menu_clear_site_data), hasPage) {
                clearCurrentSiteData()
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
        val current = lastErrorUrl?.takeIf { errorOverlay.visibility == View.VISIBLE }
            ?: webView.url?.takeIf { it.isNotBlank() }
            ?: urlInput.text?.toString().orEmpty()
        if (current.isBlank()) return
        copyTextToClipboard("url", current, R.string.url_copied)
    }

    private fun copyTextToClipboard(label: String, text: String, toastResId: Int) {
        if (text.isBlank()) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm?.setPrimaryClip(clip)
        Toast.makeText(this, toastResId, Toast.LENGTH_SHORT).show()
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
        hideErrorOverlay(updateStartCard = false)
        if (target != DEFAULT_START_URL) {
            prefs.sessionDirty = true
        }
        urlInput.clearFocus()
        webView.loadUrl(target)
        updateStartPageCardVisibility(target)
        updateWebViewImeTranslation()
    }

    /**
     * Kill-switch must cover every tab, not just the visible one: otherwise background pages keep
     * their open WebSockets / running JS after the VPN drops. Park them on about:blank and remember
     * the URL so it can be restored once the VPN is back.
     */
    private fun suspendBackgroundSessionsForVpnLoss() {
        sessions.forEach { s ->
            if (s === activeSession || !s.isWebViewAttached) return@forEach
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
            if (s === activeSession || !s.isWebViewAttached) return@forEach
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

    private fun promptHttpNavigation(
        url: String,
        targetSession: BrowserSession = activeSession,
        targetWebView: WebView? = null
    ) {
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
                if (host.isNotEmpty()) targetSession.approvedHttpHosts.add(host)
                targetSession.pageHttpBypassed = true
                if (targetWebView != null) {
                    if (!prefs.vpnKillSwitchEnabled || VpnGuard.isVpnActive(this)) {
                        targetWebView.loadUrl(url)
                    } else {
                        closeActivePopupWindow()
                        handleVpnBlocked(url)
                    }
                } else {
                    if (targetSession === activeSession) {
                        safeLoadUrl(url, bypassHttpPrompt = true)
                    } else if (targetSession.isWebViewAttached) {
                        targetSession.webView.loadUrl(url)
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
            .also { SnakeTheme.styleExpressiveDialog(it, prefs.themePalette) }
    }

    private fun promptSslError(
        error: SslError?,
        handler: SslErrorHandler?,
        targetSession: BrowserSession = activeSession
    ) {
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
                targetSession.pageSslBypassed = true
                targetSession.pageSslBypassedHost = error?.url?.let {
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
        hideErrorOverlay(updateStartCard = false)
        vpnBlockedView.visibility = View.VISIBLE
        swipeRefresh.isEnabled = false
        statusLabel.text = getString(R.string.status_vpn_blocked)
        syncMicroJitterState()
    }

    private fun showPageErrorOverlay(
        failingUrl: String,
        description: String,
        errorCode: Int = -1,
        reloadBlank: Boolean = true
    ) {
        if (isFinishing || isDestroyed) return
        if (vpnBlockedView.visibility == View.VISIBLE) return
        if (failingUrl.isBlank() || failingUrl == DEFAULT_START_URL) return
        activeSession.errorUrl = failingUrl
        activeSession.errorDescription = description
        activeSession.errorCode = errorCode
        activeSession.currentUrl = failingUrl
        val detail = description.takeIf { it.isNotBlank() } ?: getString(R.string.error_page_title)
        errorOverlayBody.text = getString(R.string.error_page_message, detail, errorCode)
        errorOverlay.visibility = View.VISIBLE
        startPageCard.visibility = View.GONE
        progressBar.visibility = View.GONE
        swipeRefresh.isRefreshing = false
        swipeRefresh.isEnabled = false
        updateUrlDisplay(failingUrl, forceUpdateText = false)
        if (reloadBlank) {
            activeSession.webView.stopLoading()
            if (activeSession.webView.url != DEFAULT_START_URL) {
                activeSession.webView.loadUrl(DEFAULT_START_URL)
            }
        }
        syncMicroJitterState()
    }

    private fun hideErrorOverlay(updateStartCard: Boolean = true) {
        activeSession.errorUrl = null
        activeSession.errorDescription = ""
        activeSession.errorCode = -1
        if (errorOverlay.visibility != View.GONE) {
            errorOverlay.visibility = View.GONE
        }
        if (updateStartCard) {
            updateStartPageCardVisibility(activeSession.currentUrl)
        } else {
            swipeRefresh.isEnabled = startPageCard.visibility != View.VISIBLE && vpnBlockedView.visibility != View.VISIBLE
        }
    }

    private fun promptHttpAuth(
        session: BrowserSession,
        view: WebView?,
        handler: HttpAuthHandler?,
        host: String,
        realm: String
    ) {
        val effectiveUrl = view?.url?.takeIf { !it.isNullOrBlank() && it != DEFAULT_START_URL } ?: session.currentUrl
        if (!SnakeClient.isSameHttpsHost(effectiveUrl, host)) {
            handler?.cancel()
            return
        }
        if (isFinishing || isDestroyed || isPinLocked) {
            handler?.cancel()
            return
        }
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val colors = SnakeTheme.resolveDialogColors(prefs.themePalette, this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * density).toInt(), pad, 0)
        }
        val userInput = EditText(this).apply {
            hint = getString(R.string.http_auth_username)
            setTextColor(colors.title)
            setHintTextColor(colors.body)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
        }
        val passInput = EditText(this).apply {
            hint = getString(R.string.http_auth_password)
            setTextColor(colors.title)
            setHintTextColor(colors.body)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        container.addView(userInput)
        container.addView(passInput)

        val displayRealm = realm.ifBlank { "HTTP" }
        val handled = AtomicBoolean(false)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.http_auth_title)
            .setMessage(getString(R.string.http_auth_message, host, displayRealm))
            .setView(container)
            .setPositiveButton(R.string.http_auth_signin) { _, _ ->
                if (handled.compareAndSet(false, true)) {
                    handler?.proceed(userInput.text?.toString().orEmpty(), passInput.text?.toString().orEmpty())
                }
            }
            .setNegativeButton(R.string.action_cancel) { _, _ ->
                if (handled.compareAndSet(false, true)) handler?.cancel()
            }
            .setOnCancelListener {
                if (handled.compareAndSet(false, true)) handler?.cancel()
            }
            .setOnDismissListener {
                if (handled.compareAndSet(false, true)) handler?.cancel()
            }
            .show()
        trackDialog(dialog)
        SnakeTheme.styleExpressiveDialog(dialog, prefs.themePalette)
    }

    private fun launchSanitizedFileChooser(
        filePathCallback: ValueCallback<Array<Uri>>?,
        params: WebChromeClient.FileChooserParams?
    ) {
        if (filePathCallback == null) return
        pendingFileUploadCallback?.onReceiveValue(null)
        pendingFileUploadCallback = filePathCallback
        val pickerIntent = runCatching { params?.createIntent() }.getOrNull()
            ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
        val launched = runCatching {
            fileChooserLauncher.launch(pickerIntent)
            true
        }.getOrDefault(false)
        if (!launched) {
            pendingFileUploadCallback = null
            filePathCallback.onReceiveValue(null)
            Toast.makeText(this, R.string.file_upload_sanitize_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleFileChooserResult(resultCode: Int, data: Intent?) {
        val callback = pendingFileUploadCallback ?: return
        pendingFileUploadCallback = null
        if (resultCode != RESULT_OK || data == null) {
            callback.onReceiveValue(null)
            return
        }
        val rawUris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { rawUris.add(it) }
            }
        }
        if (rawUris.isEmpty()) {
            val parsed = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            if (parsed != null) {
                rawUris.addAll(parsed)
            } else {
                data.data?.let { rawUris.add(it) }
            }
        }
        if (rawUris.isEmpty()) {
            callback.onReceiveValue(null)
            return
        }
        val appCtx = applicationContext
        val uploadGen = fileUploadGeneration.get()
        val executor = fileUploadExecutor ?: Executors.newSingleThreadExecutor().also { fileUploadExecutor = it }
        executor.execute {
            val sanitized = mutableListOf<Uri>()
            var hitTooLarge = false
            var hitFailed = false
            for (uri in rawUris) {
                if (uploadGen != fileUploadGeneration.get()) break
                when (val res = UploadSanitizer.sanitize(appCtx, uri)) {
                    is UploadSanitizer.SanitizeResult.Success -> sanitized.add(res.uri)
                    UploadSanitizer.SanitizeResult.TooLarge -> hitTooLarge = true
                    UploadSanitizer.SanitizeResult.Failed -> hitFailed = true
                }
            }
            mainHandler.post {
                if (isFinishing || isDestroyed || isPinLocked || uploadGen != fileUploadGeneration.get()) {
                    sanitized.forEach { u -> u.path?.let { runCatching { File(it).delete() } } }
                    callback.onReceiveValue(null)
                    return@post
                }
                if (hitTooLarge) {
                    Toast.makeText(this, R.string.file_upload_too_large, Toast.LENGTH_SHORT).show()
                } else if (hitFailed && sanitized.isEmpty()) {
                    Toast.makeText(this, R.string.file_upload_sanitize_failed, Toast.LENGTH_SHORT).show()
                }
                callback.onReceiveValue(if (sanitized.isEmpty()) null else sanitized.toTypedArray())
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun handlePopupWindowRequest(
        parentSession: BrowserSession,
        resultMsg: Message?,
        allowPopup: Boolean
    ) {
        if (resultMsg == null) return
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return
        if (!allowPopup) {
            Toast.makeText(this, R.string.popup_blocked_notice, Toast.LENGTH_SHORT).show()
            val handled = AtomicBoolean(false)
            val extractor = WebView(this)
            activePopupExtractors.add(extractor)
            extractor.settings.apply {
                javaScriptEnabled = false
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                userAgentString = unifiedUserAgent
            }
            val cleanupExtractor = {
                activePopupExtractors.remove(extractor)
                runCatching {
                    extractor.stopLoading()
                    extractor.webViewClient = WebViewClient()
                    extractor.destroy()
                }
            }
            val openExtractedUrl = { rawUrl: String ->
                if (handled.compareAndSet(false, true)) {
                    mainHandler.post { cleanupExtractor() }
                    val safeUrl = SnakePreferences.normalizeUrl(rawUrl)
                    if (safeUrl != null && !isFinishing && !isDestroyed && !isPinLocked) {
                        createNewSession(loadHome = false, switchTo = true)
                        safeLoadUrl(safeUrl)
                    }
                }
            }
            extractor.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                    val u = request?.url?.toString()
                    if (!u.isNullOrBlank()) openExtractedUrl(u)
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    if (!url.isNullOrBlank() && url != DEFAULT_START_URL) {
                        openExtractedUrl(url)
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?
                ): android.webkit.WebResourceResponse = android.webkit.WebResourceResponse(
                    "text/plain",
                    "utf-8",
                    java.io.ByteArrayInputStream(ByteArray(0))
                )
            }
            mainHandler.postDelayed({
                if (handled.compareAndSet(false, true)) cleanupExtractor()
            }, 2500L)
            transport.webView = extractor
            resultMsg.sendToTarget()
            return
        }

        closeActivePopupWindow()
        val popupWebView = WebView(this)
        applySessionWebViewProfile(popupWebView, parentSession)
        popupWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = unifiedUserAgent
        }
        disableRequestedWithHeader(popupWebView.settings)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(popupWebView, false)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            runCatching {
                ProfileStore.getInstance()
                    .getOrCreateProfile(parentSession.webViewProfileName)
                    .cookieManager
                    .apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(popupWebView, false)
                    }
            }
        }

        val policy = resolveSessionPolicy(parentSession)
        val blocklist = buildSessionBlocklist(policy)
        val vpnDownWithKillSwitch = prefs.vpnKillSwitchEnabled && !VpnGuard.isVpnActive(this)
        val flags = policy.toSnakeFeatureFlags().copy(
            blockWebSockets = vpnDownWithKillSwitch,
            blockWebRtcIce = policy.blockWebRtcIce || vpnDownWithKillSwitch
        )
        val compiled = SnakeScript.build(
            template = snakeTemplate,
            seed = parentSession.sessionSeed,
            blocklistRules = blocklist.rules,
            flags = flags,
            chromeMajor = webViewChromeMajor,
            chromeFullVersion = webViewChromeFullVersion
        )
        val popupScriptHandler = SnakeScript.install(popupWebView, compiled)

        popupWebView.webViewClient = SnakeClient(
            blocklist = blocklist,
            blocklistEnabled = policy.builtinBlocklistEnabled || blocklist.rules.isNotEmpty(),
            blockExternalSchemes = policy.blockExternalSchemes,
            vpnKillSwitchEnabled = prefs.vpnKillSwitchEnabled,
            isVpnActive = { VpnGuard.isVpnActive(this) },
            onVpnBlockedCallback = { blockedUrl ->
                runOnUiThread {
                    if (activePopupWebView === popupWebView) {
                        closeActivePopupWindow()
                    }
                    handleVpnBlocked(blockedUrl)
                }
            },
            shouldPromptHttp = { uri ->
                val host = uri.host?.lowercase(java.util.Locale.ROOT).orEmpty()
                host.isEmpty() || host !in parentSession.approvedHttpHosts
            },
            onHttpPromptCallback = { url -> runOnUiThread { promptHttpNavigation(url, parentSession, popupWebView) } },
            onSslErrorCallback = { error, handler -> runOnUiThread { promptSslError(error, handler, parentSession) } },
            onExternalSchemeCallback = { uri -> promptExternalScheme(uri) }
        )
        popupWebView.webChromeClient = SnakeChrome(
            suppressDialogs = policy.suppressJsDialogs,
            allowFileUploads = false,
            allowOAuthPopups = false,
            onCloseWindowCallback = { closed ->
                runOnUiThread {
                    if (closed == null || closed === activePopupWebView) closeActivePopupWindow()
                }
            }
        )

        val containerHeight = (resources.displayMetrics.heightPixels * 0.62f).toInt().coerceAtLeast(360)
        val container = FrameLayout(this).apply {
            addView(popupWebView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, containerHeight))
        }
        val parentHost = runCatching { Uri.parse(parentSession.currentUrl).host }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
        activePopupWebView = popupWebView
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.popup_window_title, parentHost))
            .setView(container)
            .setNegativeButton(R.string.popup_window_close) { _, _ -> closeActivePopupWindow() }
            .setOnDismissListener {
                runCatching {
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        popupScriptHandler?.remove()
                    }
                    popupWebView.stopLoading()
                    popupWebView.webViewClient = WebViewClient()
                    popupWebView.webChromeClient = null
                    (popupWebView.parent as? ViewGroup)?.removeView(popupWebView)
                    popupWebView.destroy()
                }
                if (activePopupWebView === popupWebView) {
                    activePopupWebView = null
                    activePopupDialog = null
                }
            }
            .show()
        activePopupDialog = dialog
        trackDialog(dialog)
        SnakeTheme.styleExpressiveDialog(dialog, prefs.themePalette)

        transport.webView = popupWebView
        resultMsg.sendToTarget()
    }

    private fun closeActivePopupWindow() {
        val dialog = activePopupDialog
        val popup = activePopupWebView
        activePopupDialog = null
        activePopupWebView = null
        runCatching { dialog?.dismiss() }
        if (popup != null) {
            runCatching {
                popup.stopLoading()
                popup.webViewClient = WebViewClient()
                popup.webChromeClient = null
                (popup.parent as? ViewGroup)?.removeView(popup)
                popup.destroy()
            }
        }
    }

    private fun handleWebViewLongClick(targetView: WebView): Boolean {
        val hit = targetView.hitTestResult
        val raw = hit.extra?.trim().orEmpty()
        if (raw.isEmpty()) return false
        val safeHttp = SnakePreferences.normalizeUrl(raw)
        val isImage = hit.type == WebView.HitTestResult.IMAGE_TYPE ||
            hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        val isLink = hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
            hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        if (!isImage && !isLink) return false

        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (safeHttp != null) {
            options.add(getString(R.string.context_open_new_session) to {
                createNewSession(loadHome = false, switchTo = true)
                safeLoadUrl(safeHttp)
            })
            options.add(getString(R.string.context_copy_link) to {
                copyTextToClipboard("url", safeHttp, R.string.url_copied)
            })
            if (prefs.downloadsEnabled) {
                if (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                    options.add(getString(R.string.context_download_link) to {
                        promptDownloadConfirmation(safeHttp, unifiedUserAgent, null, null)
                    })
                } else {
                    options.add(getString(R.string.context_download_image) to {
                        promptDownloadConfirmation(safeHttp, unifiedUserAgent, null, null)
                    })
                }
            }
        } else if (isImage && prefs.downloadsEnabled &&
            (raw.startsWith("data:", ignoreCase = true) || raw.startsWith("blob:", ignoreCase = true))
        ) {
            options.add(getString(R.string.context_download_image) to {
                promptDownloadConfirmation(raw, unifiedUserAgent, null, null)
            })
        }
        if (options.isEmpty()) return false

        val labels = options.map { it.first }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setItems(labels) { _, which -> options.getOrNull(which)?.second?.invoke() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
        trackDialog(dialog)
        SnakeTheme.styleExpressiveDialog(dialog, prefs.themePalette)
        return true
    }

    private fun openBookmarksSheet() {
        val sheet = BrowserUiHelper.showBookmarksSheet(
            activity = this,
            palette = prefs.themePalette,
            bookmarksProvider = { encryptedSessionStore.listBookmarks() },
            onSelectBookmark = { bookmark -> safeLoadUrl(bookmark.url) },
            onDeleteBookmark = { bookmark -> encryptedSessionStore.removeBookmark(bookmark.url) }
        )
        trackDialog(sheet)
    }

    private fun toggleBookmark() {
        val current = webView.url?.takeIf { it.isNotBlank() && it != DEFAULT_START_URL }
            ?: lastErrorUrl?.takeIf { errorOverlay.visibility == View.VISIBLE }
            ?: return
        if (encryptedSessionStore.isBookmarked(current)) {
            encryptedSessionStore.removeBookmark(current)
            Toast.makeText(this, R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
        } else {
            val title = if (errorOverlay.visibility == View.VISIBLE) "" else webView.title.orEmpty()
            encryptedSessionStore.addBookmark(current, title)
            Toast.makeText(this, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareCurrentUrl() {
        val current = webView.url?.takeIf { it.isNotBlank() && it != DEFAULT_START_URL }
            ?: lastErrorUrl?.takeIf { errorOverlay.visibility == View.VISIBLE }
            ?: return
        val safe = SnakePreferences.normalizeUrl(current) ?: return
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, safe)
        }
        runCatching {
            startActivity(Intent.createChooser(sendIntent, getString(R.string.menu_share_url)))
        }
    }

    private fun printCurrentPage() {
        val current = webView.url?.takeIf { it.isNotBlank() && it != DEFAULT_START_URL } ?: return
        if (current.isBlank()) return
        val pm = getSystemService(PRINT_SERVICE) as? PrintManager
        if (pm == null) {
            Toast.makeText(this, R.string.print_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val jobName = getString(R.string.print_job_name)
        runCatching {
            val adapter = webView.createPrintDocumentAdapter(jobName)
            pm.print(jobName, adapter, PrintAttributes.Builder().build())
        }.onFailure {
            Toast.makeText(this, R.string.print_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearCurrentSiteData() {
        val current = webView.url?.takeIf { it.isNotBlank() && it != DEFAULT_START_URL }
            ?: lastErrorUrl?.takeIf { errorOverlay.visibility == View.VISIBLE }
            ?: return
        val parsed = runCatching { Uri.parse(current) }.getOrNull() ?: return
        val origin = if (!parsed.scheme.isNullOrBlank() && !parsed.authority.isNullOrBlank()) {
            "${parsed.scheme}://${parsed.authority}"
        } else {
            return
        }
        WebStorage.getInstance().deleteOrigin(origin)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            runCatching {
                ProfileStore.getInstance()
                    .getProfile(activeSession.webViewProfileName)
                    ?.webStorage
                    ?.deleteOrigin(origin)
            }
        }
        val host = parsed.host
        val pathPrefixes = buildList {
            add("/")
            val segs = parsed.pathSegments.orEmpty()
            var acc = ""
            for (seg in segs) {
                if (seg.isNotBlank()) {
                    acc += "/$seg"
                    add(acc)
                    add("$acc/")
                }
            }
        }.distinct()
        val domainVariants = buildList {
            if (!host.isNullOrBlank()) {
                val labels = host.split('.').filter { it.isNotEmpty() }.takeLast(4)
                for (i in 0 until (labels.size - 1).coerceAtLeast(0)) {
                    val dom = labels.subList(i, labels.size).joinToString(".")
                    add("; Domain=$dom")
                    add("; Domain=.$dom")
                }
            }
        }.distinct()
        val hosts = listOfNotNull(origin, host?.let { "https://$it" }, host?.let { "http://$it" }).distinct()
        val clearCookiesForManager = { cm: CookieManager ->
            val rawCookies = runCatching { cm.getCookie(current) }.getOrNull().orEmpty()
            if (rawCookies.isNotBlank()) {
                rawCookies.split(';').forEach { pair ->
                    val name = pair.substringBefore('=').trim()
                    if (name.isNotEmpty()) {
                        val forceSecure = name.startsWith("__Secure-") || name.startsWith("__Host-")
                        hosts.forEach { target ->
                            val secureAttr = if (target.startsWith("https://", ignoreCase = true) || forceSecure) {
                                "; Secure"
                            } else {
                                ""
                            }
                            pathPrefixes.forEach { p ->
                                cm.setCookie(target, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=$p")
                                if (secureAttr.isNotEmpty()) {
                                    cm.setCookie(
                                        target,
                                        "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=$p$secureAttr"
                                    )
                                }
                                if (!name.startsWith("__Host-")) {
                                    domainVariants.forEach { dom ->
                                        cm.setCookie(target, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=$p$dom")
                                        if (secureAttr.isNotEmpty()) {
                                            cm.setCookie(
                                                target,
                                                "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=$p$dom$secureAttr"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                cm.flush()
            }
        }
        clearCookiesForManager(CookieManager.getInstance())
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            runCatching {
                ProfileStore.getInstance()
                    .getProfile(activeSession.webViewProfileName)
                    ?.cookieManager
                    ?.let { clearCookiesForManager(it) }
            }
        }
        val displayHost = host?.takeIf { it.isNotBlank() } ?: origin
        Toast.makeText(this, getString(R.string.site_data_cleared, displayHost), Toast.LENGTH_SHORT).show()
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
            hideErrorOverlay(updateStartCard = false)
            closeReaderMode()
            exitFullscreenVideo()
            closeActivePopupWindow()
        }
        val liveUrl = (if (crashedSession.isWebViewAttached) crashedSession.webView.url else null)
            ?.takeIf { it.isNotBlank() && it != DEFAULT_START_URL }
        val crashUrl = (liveUrl ?: crashedSession.currentUrl).ifBlank { DEFAULT_START_URL }
        val preservedSeed = crashedSession.sessionSeed
        val prevCrashAt = crashedSession.lastCrashRecoveryAtMs
        val prevCrashUrl = crashedSession.lastCrashUrl
        val now = System.currentTimeMillis()
        val isCrashLoop = crashUrl != DEFAULT_START_URL &&
            crashUrl == prevCrashUrl &&
            (now - prevCrashAt) in 0L..CRASH_LOOP_GUARD_MS

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
        crashedSession.isWebViewAttached = true
        crashedSession.lastActiveAtMs = now
        crashedSession.resetSessionState()
        crashedSession.sessionSeed = preservedSeed
        crashedSession.lastCrashRecoveryAtMs = now
        crashedSession.lastCrashUrl = crashUrl

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

        val isHttpOrHttps = crashUrl.startsWith("https://", ignoreCase = true) ||
            crashUrl.startsWith("http://", ignoreCase = true)
        val canReload = !isCrashLoop && isHttpOrHttps
        if (canReload && prefs.vpnKillSwitchEnabled && !VpnGuard.isVpnActive(this)) {
            crashedSession.pendingVpnUrl = crashUrl
            crashedSession.currentUrl = DEFAULT_START_URL
            replacement.loadUrl(DEFAULT_START_URL)
            if (isCurrent) {
                updateStartPageCardVisibility(DEFAULT_START_URL)
                updateUrlDisplay(DEFAULT_START_URL, forceUpdateText = true)
            }
        } else if (canReload) {
            crashedSession.currentUrl = crashUrl
            replacement.loadUrl(crashUrl)
            if (isCurrent) {
                updateStartPageCardVisibility(crashUrl)
                updateUrlDisplay(crashUrl, forceUpdateText = true)
            }
        } else {
            crashedSession.currentUrl = DEFAULT_START_URL
            replacement.loadUrl(DEFAULT_START_URL)
            if (isCurrent) {
                updateStartPageCardVisibility(DEFAULT_START_URL)
                updateUrlDisplay(DEFAULT_START_URL, forceUpdateText = true)
            }
        }
        if (isCurrent) {
            syncMicroJitterState()
            statusLabel.text = getString(R.string.renderer_crashed_notice)
        }
    }

    private fun evaluateVpnState() {
        sessions.forEach {
            if (it.isWebViewAttached) installSnakeCore(it)
        }
        if (isPinLocked) {
            if (prefs.vpnKillSwitchEnabled && !VpnGuard.isVpnActive(this)) {
                suspendBackgroundSessionsForVpnLoss()
                closeActivePopupWindow()
            }
            return
        }
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
            closeActivePopupWindow()
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

    fun createNewSession(
        loadHome: Boolean = true,
        switchTo: Boolean = true,
        explicitId: Long? = null,
        attachWebView: Boolean = true
    ): BrowserSession {
        performHaptic()
        val newWebView = if (attachWebView) createSessionWebView() else null
        val newSession = if (explicitId != null) {
            BrowserSession(id = explicitId, webView = newWebView)
        } else {
            BrowserSession(webView = newWebView)
        }.apply {
            lastActiveAtMs = System.currentTimeMillis()
        }
        if (attachWebView) {
            configureWebView(newSession)
            installSnakeCore(newSession)
            applyRuntimePreferencesToSession(newSession)
        }
        sessions.add(newSession)
        updateSessionBadge()
        if (switchTo) {
            switchToSession(newSession)
            if (loadHome) {
                newSession.lastLoadedHomeUrl = prefs.homeUrl
                safeLoadUrl(newSession.lastLoadedHomeUrl)
            }
            statusLabel.text = getString(R.string.status_new_session)
        } else {
            if (loadHome && attachWebView && newWebView != null) {
                newSession.lastLoadedHomeUrl = prefs.homeUrl
                newWebView.loadUrl(newSession.lastLoadedHomeUrl)
            }
            evictLruBackgroundSessionsIfNeeded()
        }
        return newSession
    }

    fun switchToSession(target: BrowserSession) {
        if (target !in sessions) return
        val switching = target !== activeSession
        if (switching) {
            closeFindBar()
            closeReaderMode()
            exitFullscreenVideo()
            closeActivePopupWindow()
            if (errorOverlay.visibility != View.GONE) {
                errorOverlay.visibility = View.GONE
            }
            pendingSslHandler?.cancel()
            pendingSslHandler = null
            activeSecurityDialog?.dismiss()
            activeSecurityDialog = null

            val outgoing = activeSession
            microJitter.stop()
            if (outgoing.isWebViewAttached) {
                outgoing.webView.id = View.NO_ID
                swipeRefresh.removeView(outgoing.webView)
            }

            activeSession = target
        }

        val wasDetached = !target.isWebViewAttached
        ensureSessionWebViewAttached(target)
        target.lastActiveAtMs = System.currentTimeMillis()

        if (switching || wasDetached) {
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

        evictLruBackgroundSessionsIfNeeded()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            activeSessionCookieManager = runCatching {
                ProfileStore.getInstance().getProfile(target.webViewProfileName)?.cookieManager
            }.getOrNull()
        }

        val deferredRestore = target.pendingRestoreUrl
        if (deferredRestore != null) {
            target.pendingRestoreUrl = null
            if (deferredRestore != DEFAULT_START_URL) {
                safeLoadUrl(deferredRestore)
            }
        }

        val targetErrorUrl = target.errorUrl
        val currentUrl = deferredRestore
            ?: targetErrorUrl
            ?: (if (target.isWebViewAttached) webView.url else null)
            ?: target.currentUrl
        updateUrlDisplay(currentUrl, forceUpdateText = true)
        progressBar.visibility = View.GONE
        swipeRefresh.isRefreshing = false
        applyRuntimePreferencesToSession(target)

        if (prefs.vpnKillSwitchEnabled && !VpnGuard.isVpnActive(this) && target.pendingVpnUrl != null) {
            vpnBlockedView.visibility = View.VISIBLE
            swipeRefresh.isEnabled = false
        } else if (targetErrorUrl != null) {
            vpnBlockedView.visibility = View.GONE
            showPageErrorOverlay(
                failingUrl = targetErrorUrl,
                description = target.errorDescription,
                errorCode = target.errorCode,
                reloadBlank = false
            )
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

    private fun ensureSessionWebViewAttached(session: BrowserSession) {
        if (session.isWebViewAttached) return
        val restoredUrl = session.currentUrl
        val rebuilt = createSessionWebView()
        session.webView = rebuilt
        session.isWebViewAttached = true
        session.downloadListenerAttached = false
        configureWebView(session)
        installSnakeCore(session)
        applyRuntimePreferencesToSession(session)
        if (restoredUrl.isNotBlank() && restoredUrl != DEFAULT_START_URL && session.errorUrl == null) {
            if (isPinLocked) {
                session.pendingRestoreUrl = restoredUrl
            } else if (prefs.vpnKillSwitchEnabled && !VpnGuard.isVpnActive(this)) {
                session.pendingVpnUrl = restoredUrl
                rebuilt.loadUrl(DEFAULT_START_URL)
            } else if (restoredUrl.startsWith("http://", ignoreCase = true) && session === activeSession) {
                val host = runCatching { Uri.parse(restoredUrl).host?.lowercase(java.util.Locale.ROOT) }.getOrNull().orEmpty()
                if (host.isEmpty() || host !in session.approvedHttpHosts) {
                    rebuilt.loadUrl(DEFAULT_START_URL)
                    promptHttpNavigation(restoredUrl, session)
                } else {
                    rebuilt.loadUrl(restoredUrl)
                }
            } else {
                rebuilt.loadUrl(restoredUrl)
            }
        }
    }

    private fun evictLruBackgroundSessionsIfNeeded() {
        var attachedCount = sessions.count { it.isWebViewAttached }
        while (attachedCount > MAX_LIVE_TABS) {
            val victim = sessions
                .filter { it !== activeSession && it.isWebViewAttached }
                .minByOrNull { it.lastActiveAtMs }
                ?: break
            victim.currentUrl = victim.webView.url?.takeIf { it.isNotBlank() } ?: victim.currentUrl
            victim.currentTitle = victim.webView.title?.trim()?.takeIf { it.isNotEmpty() } ?: victim.currentTitle
            destroySessionWebView(victim, clearData = false, deleteProfile = false)
            attachedCount--
        }
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
        destroySessionWebView(target, clearData = true, deleteProfile = true)
        updateSessionBadge()
        statusLabel.text = getString(R.string.status_closed_session)
    }

    private fun destroySessionWebView(
        session: BrowserSession,
        clearData: Boolean = false,
        deleteProfile: Boolean = false
    ) {
        clearScriptHandler(session)
        session.downloadListenerAttached = false
        if (session.isWebViewAttached) {
            session.isWebViewAttached = false
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
        if (deleteProfile && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            runCatching {
                ProfileStore.getInstance().deleteProfile(session.webViewProfileName)
            }
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
        val trimmedUrl = url.trim()
        val isDataUri = trimmedUrl.startsWith("data:", ignoreCase = true)
        val isBlobUri = trimmedUrl.startsWith("blob:", ignoreCase = true)
        val rawSuggestedName = if (isDataUri) {
            if (!contentDisposition.isNullOrBlank()) {
                android.webkit.URLUtil.guessFileName("https://local.invalid/", contentDisposition, mimetype)
            } else {
                android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimetype)
                    ?.let { "download.$it" }
                    ?: "download.bin"
            }
        } else {
            android.webkit.URLUtil.guessFileName(trimmedUrl, contentDisposition, mimetype)
        }
        val name = DownloadHelper.sanitizeFileName(rawSuggestedName)
        val host = when {
            isDataUri -> "data:"
            isBlobUri -> Uri.parse(trimmedUrl.substringAfter("blob:", "")).host.orEmpty().ifBlank { "blob:" }
            else -> Uri.parse(trimmedUrl).host.orEmpty().ifBlank { trimmedUrl }
        }
        val baseMessage = getString(R.string.download_confirm_message, name, host)
        val message = if (trimmedUrl.startsWith("http://", ignoreCase = true)) {
            baseMessage + getString(R.string.download_confirm_http_warning)
        } else {
            baseMessage
        }
        val sessionAtConfirm = activeSession
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.download_confirm_title)
            .setMessage(message)
            .setPositiveButton(R.string.download_confirm_action) { _, _ ->
                Toast.makeText(this, getString(R.string.download_started, name), Toast.LENGTH_SHORT).show()
                val onResult: (Result<String>) -> Unit = { result ->
                    if (!isFinishing && !isDestroyed) {
                        result.onSuccess { savedName ->
                            Toast.makeText(this, getString(R.string.download_saved, savedName), Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                when {
                    isDataUri -> downloadHelper.downloadDataUri(trimmedUrl, contentDisposition, mimetype, onResult)
                    isBlobUri -> {
                        if (!sessionAtConfirm.isWebViewAttached) {
                            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
                        } else {
                            downloadHelper.downloadBlobUrl(
                                sessionAtConfirm.webView,
                                trimmedUrl,
                                contentDisposition,
                                mimetype,
                                onResult
                            )
                        }
                    }
                    else -> downloadHelper.download(trimmedUrl, contentDisposition, mimetype, userAgent, onResult)
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
            scriptProtectionActive = activeSession.scriptHandler != null,
            sessionPresetOverride = activeSession.presetOverride,
            sessionProfileIdOverride = activeSession.profileIdOverride
        )
        val sheet = SiteInfoSheet.show(
            activity = this,
            prefs = prefs,
            state = state,
            onProfileOrBlocklistChanged = { reloadPage ->
                applyRuntimePreferences()
                installSnakeCore()
                if (reloadPage && webView.url != null && webView.url != DEFAULT_START_URL) {
                    webView.reload()
                }
            },
            onSessionProfileSelected = { chosenPreset, chosenProfileId ->
                activeSession.presetOverride = chosenPreset
                activeSession.profileIdOverride = chosenProfileId
                applyRuntimePreferencesToSession(activeSession)
                installSnakeCore(activeSession)
                syncMicroJitterState()
                val switchedLabel = when {
                    chosenProfileId != null -> prefs.listProfiles().firstOrNull { it.id == chosenProfileId }?.name
                        ?: chosenProfileId
                    chosenPreset == ProtectionPreset.MAX_STEALTH.key -> getString(R.string.preset_max_stealth)
                    chosenPreset == ProtectionPreset.COMPATIBILITY.key -> getString(R.string.preset_compatibility)
                    else -> {
                        val globalName = SiteInfoSheet.resolveGlobalProfileName(this, prefs)
                        getString(R.string.session_profile_default, globalName)
                    }
                }
                Toast.makeText(this, getString(R.string.session_profile_switched, switchedLabel), Toast.LENGTH_SHORT).show()
                if (webView.url != null && webView.url != DEFAULT_START_URL) {
                    webView.reload()
                }
            }
        )
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
        if (isFinishing || isDestroyed || isPinLocked) return
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
        updateWebViewImeTranslation()
        microJitter.stop()
        activeMenuPopup?.dismiss()
        activeMenuPopup = null
        activeSessionsSheet?.dismiss()
        activeSessionsSheet = null
        onboardingDialog?.dismiss()
        onboardingDialog = null
        transientDialogs.toList().forEach { runCatching { it.dismiss() } }
        transientDialogs.clear()
        closeReaderMode()
        closeFindBar()
        exitFullscreenVideo()
        closeActivePopupWindow()
        pendingSslHandler?.cancel()
        pendingSslHandler = null
        activeSecurityDialog?.dismiss()
        activeSecurityDialog = null
        if (activeSession.isWebViewAttached) {
            activeSession.webView.stopLoading()
        }
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
            applyRuntimePreferences()
            if (pendingInitialLoad) {
                pendingInitialLoad = false
                val restoredUrl = pendingRestoreAfterPinUrl
                pendingRestoreAfterPinUrl = null
                if (pendingExternalNewSession) {
                    pendingExternalNewSession = false
                    createNewSession(loadHome = false, switchTo = true)
                    Toast.makeText(this, R.string.external_link_opened_new_session, Toast.LENGTH_SHORT).show()
                }
                if (!restoredUrl.isNullOrBlank() && restoredUrl != DEFAULT_START_URL) {
                    safeLoadUrl(restoredUrl)
                } else {
                    loadInitialHomePage()
                }
            }
            showOnboardingIfNeeded()
        }
        updateWebViewImeTranslation()
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
        onboardingDialog?.dismiss()
        onboardingDialog = null
        transientDialogs.toList().forEach { runCatching { it.dismiss() } }
        transientDialogs.clear()
        closeFindBar()
        closeReaderMode()
        exitFullscreenVideo()
        closeActivePopupWindow()
        hideErrorOverlay(updateStartCard = false)
        fileUploadGeneration.incrementAndGet()
        pendingFileUploadCallback?.onReceiveValue(null)
        pendingFileUploadCallback = null
        UploadSanitizer.clearTempUploads(this)

        // Destroy all background sessions so no hidden tabs or renderers survive a wipe.
        val extraSessions = sessions.filter { it !== activeSession }
        extraSessions.forEach { extra ->
            sessions.remove(extra)
            destroySessionWebView(extra, clearData = true, deleteProfile = true)
        }

        downloadHelper.cancelAll()
        encryptedSessionStore.cryptoEraseAll()
        ephemeralRestoreStates.clear()
        pendingRestoreAfterPinUrl = null
        pendingExternalNewSession = false

        activeSession.presetOverride = null
        activeSession.profileIdOverride = null
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
            DownloadHistoryStore(this).clearWithFiles(this)
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

        PinLockCoordinator.wipeMultiProfileStorage(preserveProfileName = activeSession.webViewProfileName)
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
        if (!isChangingConfigurations && !isFinishing) {
            persistSessionsIfAllowed()
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Store only an opaque in-memory UUID token (never put URLs into the Binder/Parcel Bundle).
        val token = UUID.randomUUID().toString()
        ephemeralRestoreStates[token] = captureSavedSessionState()
        outState.putString(STATE_RESTORE_TOKEN, token)
        if (ephemeralRestoreStates.size > 8) {
            ephemeralRestoreStates.keys.take(ephemeralRestoreStates.size - 8).forEach {
                ephemeralRestoreStates.remove(it)
            }
        }
        persistSessionsIfAllowed()
    }

    private fun captureSavedSessionState(): EncryptedSessionStore.SavedSessionState {
        val tabs = sessions.map { s ->
            val url = s.pendingRestoreUrl
                ?: if (s.isWebViewAttached) {
                    s.webView.url?.takeIf { it.isNotBlank() } ?: s.currentUrl
                } else {
                    s.currentUrl
                }
            EncryptedSessionStore.SavedTab(
                id = s.id,
                url = url.ifBlank { DEFAULT_START_URL },
                title = s.currentTitle,
                sessionSeed = s.sessionSeed,
                presetOverride = s.presetOverride,
                profileIdOverride = s.profileIdOverride
            )
        }
        val activeIdx = sessions.indexOf(activeSession).coerceAtLeast(0)
        return EncryptedSessionStore.SavedSessionState(tabs = tabs, activeIndex = activeIdx)
    }

    private fun persistSessionsIfAllowed() {
        if (!::encryptedSessionStore.isInitialized) return
        if (prefs.autoWipeOnExit) {
            encryptedSessionStore.clearTabs()
        } else {
            val state = captureSavedSessionState()
            encryptedSessionStore.saveTabs(state.tabs, state.activeIndex, autoWipeOnExit = false)
        }
    }

    private fun applyRestoredSessionState(state: EncryptedSessionStore.SavedSessionState) {
        if (state.tabs.isEmpty()) return
        BrowserSession.ensureNextIdAtLeast((state.tabs.maxOfOrNull { it.id } ?: 1L) + 1L)
        val first = state.tabs.first()
        if (first.id != activeSession.id) {
            val outgoing = activeSession
            val replacementWebView = createSessionWebView()
            val replacement = BrowserSession(
                id = first.id,
                webView = replacementWebView,
                sessionSeed = first.sessionSeed
            ).apply {
                lastActiveAtMs = System.currentTimeMillis()
            }
            if (outgoing.isWebViewAttached) {
                outgoing.webView.id = View.NO_ID
                swipeRefresh.removeView(outgoing.webView)
            }
            sessions[0] = replacement
            activeSession = replacement
            webView = replacementWebView
            webView.id = R.id.webView
            swipeRefresh.addView(webView)
            microJitter.stop()
            microJitter = MicroJitterController(
                targetView = webView,
                minIdleMs = prefs.microJitterMinSec * 1000L,
                maxIdleMs = prefs.microJitterMaxSec * 1000L
            )
            destroySessionWebView(outgoing, clearData = true, deleteProfile = true)
            configureWebView(activeSession)
        } else {
            activeSession.sessionSeed = first.sessionSeed
        }
        activeSession.presetOverride = first.presetOverride
        activeSession.profileIdOverride = first.profileIdOverride
        activeSession.currentUrl = first.url
        activeSession.currentTitle = first.title
        applyRuntimePreferencesToSession(activeSession)
        installSnakeCore(activeSession)

        val targetIdx = state.activeIndex.coerceIn(0, state.tabs.lastIndex)
        val attachSlots = (listOf(0, targetIdx) + (1 until state.tabs.size).reversed())
            .distinct()
            .take(MAX_LIVE_TABS)
            .toSet()

        for (i in 1 until state.tabs.size) {
            val saved = state.tabs[i]
            val created = createNewSession(
                loadHome = false,
                switchTo = false,
                explicitId = saved.id,
                attachWebView = (i in attachSlots)
            )
            created.sessionSeed = saved.sessionSeed
            created.presetOverride = saved.presetOverride
            created.profileIdOverride = saved.profileIdOverride
            created.currentUrl = saved.url
            created.currentTitle = saved.title
            if (created.isWebViewAttached) {
                applyRuntimePreferencesToSession(created)
                installSnakeCore(created)
            }
        }

        sessions.forEachIndexed { idx, s ->
            if (idx != targetIdx && s.currentUrl != DEFAULT_START_URL) {
                s.pendingRestoreUrl = s.currentUrl
            }
        }
        if (targetIdx != 0) {
            val activeTarget = sessions[targetIdx]
            activeTarget.pendingRestoreUrl = null
            switchToSession(activeTarget)
        } else {
            updateUrlDisplay(activeSession.currentUrl, forceUpdateText = true)
            updateStartPageCardVisibility(activeSession.currentUrl)
        }
        updateSessionBadge()
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
        // Enforce before applyRuntimePreferences() / showOnboardingIfNeeded(),
        // since onActivityResumed dispatches after onResume().
        PinLockCoordinator.enforceLockIfNeeded(this)
        if (!isPinLocked) {
            applyRuntimePreferences()
            showOnboardingIfNeeded()
        }
    }

    override fun onPause() {
        activeMenuPopup?.dismiss()
        microJitter.stop()
        super.onPause()
        // Intentionally avoid webView.onPause() / pauseTimers() to minimize background timing leaks.
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        frozenPortraitSize?.let { (w, h) ->
            val rootContainer = findViewById<View>(R.id.rootContainer)
            rootContainer.layoutParams = rootContainer.layoutParams.apply {
                width = w
                height = h
            }
        }
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
        errorOverlayTitle.text = getString(R.string.error_page_title)
        findViewById<TextView>(R.id.btnErrorRetry).text = getString(R.string.error_page_retry)
    }

    override fun onDestroy() {
        activeMenuPopup?.dismiss()
        activeSessionsSheet?.dismiss()
        onboardingDialog?.dismiss()
        activePopupExtractors.toList().forEach { wv ->
            runCatching {
                wv.stopLoading()
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            }
        }
        activePopupExtractors.clear()
        closeActivePopupWindow()
        pendingFileUploadCallback?.onReceiveValue(null)
        pendingFileUploadCallback = null
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
        UploadSanitizer.clearTempUploads(this)
        fileUploadExecutor?.shutdownNow()
        fileUploadExecutor = null
        captureManager.shutdown()
        downloadHelper.shutdown()
        val deleteProfilesOnDestroy = prefs.autoWipeOnExit && !isChangingConfigurations
        sessions.toList().forEach { session ->
            if (session.isWebViewAttached || deleteProfilesOnDestroy) {
                destroySessionWebView(session, deleteProfile = deleteProfilesOnDestroy)
            }
        }
        sessions.clear()
        super.onDestroy()
    }

    companion object {
        const val DEFAULT_START_URL = "about:blank"
        private const val MAX_LIVE_TABS = 4
        private const val CRASH_LOOP_GUARD_MS = 10_000L
        private const val STATE_RESTORE_TOKEN = "stealth_snake_restore_token"
        private val ephemeralRestoreStates = ConcurrentHashMap<String, EncryptedSessionStore.SavedSessionState>()
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
