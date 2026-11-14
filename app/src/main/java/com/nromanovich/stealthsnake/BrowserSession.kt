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
    var webView: WebView,
    var sessionSeed: String = SnakeScript.generateSessionSeed()
) {
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
        approvedHttpHosts.clear()
        resetPageSecurityState()
        currentUrl = MainActivity.DEFAULT_START_URL
        currentTitle = ""
    }

    companion object {
        private val nextId = AtomicLong(1L)
    }
}
