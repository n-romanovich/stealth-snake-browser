package com.nromanovich.stealthsnake

import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * Hardened WebChromeClient that suppresses blocking JS dialogs, denies sensitive hardware
 * permissions (geolocation, camera/mic/MIDI/protected-media), isolates file choosers,
 * and blocks popup window creation.
 */
class SnakeChrome(
    @Volatile var suppressDialogs: Boolean = true,
    private val onProgressChangedCallback: (Int) -> Unit = {},
    private val onTitleChangedCallback: (String) -> Unit = {},
    private val onShowCustomViewCallback: (View?, CustomViewCallback?) -> Unit = { _, _ -> },
    private val onHideCustomViewCallback: () -> Unit = {},
    private val onFileChooserBlockedCallback: () -> Unit = {}
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        onProgressChangedCallback(newProgress.coerceIn(0, 100))
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        if (!title.isNullOrEmpty()) {
            onTitleChangedCallback(title)
        }
    }

    override fun onJsAlert(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?
    ): Boolean {
        if (!suppressDialogs) return false
        result?.cancel()
        return true
    }

    override fun onJsConfirm(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?
    ): Boolean {
        if (!suppressDialogs) return false
        result?.cancel()
        return true
    }

    override fun onJsPrompt(
        view: WebView?,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult?
    ): Boolean {
        if (!suppressDialogs) return false
        result?.cancel()
        return true
    }

    override fun onJsBeforeUnload(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?
    ): Boolean {
        if (!suppressDialogs) return false
        result?.cancel()
        return true
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?
    ) {
        callback?.invoke(origin, false, false)
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        request?.deny()
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        onFileChooserBlockedCallback()
        // Must invoke the callback with null when returning true so WebView does not deadlock <input type="file">.
        filePathCallback?.onReceiveValue(null)
        return true
    }

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean = false

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        onShowCustomViewCallback(view, callback)
    }

    override fun onHideCustomView() {
        onHideCustomViewCallback()
    }
}
