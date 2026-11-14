package com.nromanovich.stealthsnake

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Network-layer defense for Vanadium WebView:
 *  - Leaves normal HTTP(S) traffic on Vanadium's native BoringSSL/HTTP2 stack (JA3/JA4 intact).
 *  - Intercepts and neutralizes tracking sub-resources matching [blocklist] with empty 200 OK.
 *  - Blocks external/custom URI schemes (`intent://`, `tel:`, `file://`, etc.) to prevent
 *    installed-app enumeration and sandbox escape vectors, or delegates safely when allowed.
 *  - Optionally prompts before unencrypted `http://` main-frame navigations, handles SSL errors,
 *    and recovers cleanly if the WebView renderer process terminates.
 */
class SnakeClient(
    @Volatile var blocklist: BlocklistEngine,
    @Volatile var blocklistEnabled: Boolean = true,
    @Volatile var blockExternalSchemes: Boolean = true,
    @Volatile var vpnKillSwitchEnabled: Boolean = false,
    @Volatile var isVpnActive: () -> Boolean = { true },
    private val onPageStartedCallback: (String) -> Unit = {},
    private val onPageFinishedCallback: (String) -> Unit = {},
    private val onBlockedCallback: (String) -> Unit = {},
    private val onVpnBlockedCallback: (String?) -> Unit = {},
    private val shouldPromptHttp: (Uri) -> Boolean = { false },
    private val onHttpPromptCallback: (String) -> Unit = {},
    private val onSslErrorCallback: ((SslError?, SslErrorHandler?) -> Unit)? = null,
    private val onExternalSchemeCallback: ((Uri) -> Boolean)? = null,
    private val onRenderProcessGoneCallback: ((RenderProcessGoneDetail?) -> Boolean)? = null
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val req = request ?: return null
        val url = req.url ?: return null
        if (vpnKillSwitchEnabled && isNetworkUri(url) && !isVpnActive()) {
            onVpnBlockedCallback(if (req.isForMainFrame) url.toString() else null)
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                403,
                "Blocked by VPN Kill-Switch",
                emptyMap(),
                ByteArrayInputStream(ByteArray(0))
            )
        }
        if (!blocklistEnabled) return null
        if (blocklist.isBlocked(url, req.isForMainFrame)) {
            onBlockedCallback(url.toString())
            return blocklist.createEmptyBlockedResponse()
        }
        return null
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val uri = request?.url ?: return true
        if (vpnKillSwitchEnabled && isNetworkUri(uri) && !isVpnActive()) {
            onVpnBlockedCallback(uri.toString())
            return true
        }
        if (!isAllowedNavigationUri(uri)) {
            if (blockExternalSchemes) return true
            return onExternalSchemeCallback?.invoke(uri) ?: false
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (request.isForMainFrame && scheme == "http" && shouldPromptHttp(uri)) {
            onHttpPromptCallback(uri.toString())
            return true
        }
        return false
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (!url.isNullOrEmpty()) {
            onPageStartedCallback(url)
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (!url.isNullOrEmpty()) {
            onPageFinishedCallback(url)
        }
    }

    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler?,
        error: SslError?
    ) {
        val callback = onSslErrorCallback
        if (callback != null) {
            callback(error, handler)
        } else {
            handler?.cancel()
        }
    }

    override fun onRenderProcessGone(
        view: WebView?,
        detail: RenderProcessGoneDetail?
    ): Boolean {
        return onRenderProcessGoneCallback?.invoke(detail) ?: true
    }

    companion object {
        fun isAllowedNavigationUri(uri: Uri): Boolean {
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
            return scheme == "https" || scheme == "http" || scheme == "about"
        }

        fun isNetworkUri(uri: Uri): Boolean {
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return true
            return scheme != "about" && scheme != "data" && scheme != "blob" && scheme != "javascript"
        }
    }
}
