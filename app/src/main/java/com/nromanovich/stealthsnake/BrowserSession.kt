package com.nromanovich.stealthsnake

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import java.util.concurrent.atomic.AtomicLong

/**
 * Isolated browsing session owning its own [WebView], per-session fingerprint seed,
 * navigation security state, and SnakeScript bindings.
 */
class BrowserSession(
    val id: Long = nextId.getAndIncrement(),
    webView: WebView? = null,
    var sessionSeed: String = SnakeScript.generateSessionSeed()
) {
    val webViewProfileName: String = "tab_$id"
    lateinit var webView: WebView
    var isWebViewAttached: Boolean = webView != null
    var lastActiveAtMs: Long = System.currentTimeMillis()
    var presetOverride: String? = null
    var profileIdOverride: String? = null
    var lastCrashRecoveryAtMs: Long = 0L
    var lastCrashUrl: String = ""
    var pendingRestoreUrl: String? = null
    var errorUrl: String? = null
    var errorDescription: String = ""
    var errorCode: Int = -1

    init {
        if (webView != null) {
            this.webView = webView
        }
    }

    lateinit var snakeClient: SnakeClient
    lateinit var snakeChrome: SnakeChrome
    var scriptHandler: ScriptHandler? = null
    var lastSnakeBuildSignature: String? = null

    // Written on the UI thread, read from Chromium's IO thread (shouldInterceptRequest callbacks).
    @Volatile
    var navigationGeneration: Long = 0L
    var blockedRequestCount: Int = 0
    val blockedHostsOnPage: LinkedHashMap<String, Int> = linkedMapOf()
    val approvedHttpHosts: MutableSet<String> = mutableSetOf()
    var pageHttpBypassed: Boolean = false
    var pageSslBypassed: Boolean = false
    var pageSslBypassedHost: String? = null
    var pendingVpnUrl: String? = null

    var currentUrl: String = MainActivity.DEFAULT_START_URL
    var currentTitle: String = ""
    var lastLoadedHomeUrl: String = ""
    var downloadListenerAttached: Boolean = false

    fun resetPageSecurityState() {
        blockedHostsOnPage.clear()
        pageHttpBypassed = false
        pageSslBypassed = false
        pageSslBypassedHost = null
    }

    fun resetSessionState() {
        navigationGeneration++
        blockedRequestCount = 0
        pendingVpnUrl = null
        lastCrashRecoveryAtMs = 0L
        lastCrashUrl = ""
        pendingRestoreUrl = null
        errorUrl = null
        errorDescription = ""
        errorCode = -1
        approvedHttpHosts.clear()
        resetPageSecurityState()
        currentUrl = MainActivity.DEFAULT_START_URL
        currentTitle = ""
    }

    companion object {
        private val nextId = AtomicLong(1L)

        fun ensureNextIdAtLeast(minNextId: Long) {
            while (true) {
                val cur = nextId.get()
                if (cur >= minNextId) return
                if (nextId.compareAndSet(cur, minNextId)) return
            }
        }
    }
}
