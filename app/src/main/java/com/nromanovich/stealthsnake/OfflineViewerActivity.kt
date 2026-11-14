package com.nromanovich.stealthsnake

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayInputStream

/**
 * Isolated offline MHTML archive viewer.
 * Runs with JavaScript disabled and all network loads blocked so saved archives
 * cannot phone home or execute untrusted scripts.
 */
class OfflineViewerActivity : AppCompatActivity() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var toolbarView: LinearLayout
    private lateinit var backBtn: ImageButton
    private lateinit var titleTv: TextView
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val palette = SnakeTheme.resolveBrowserColors(this)
        val density = resources.displayMetrics.density
        SnakeTheme.applySystemBars(this, palette, palette.toolbar)

        val fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()
        val pageTitle = intent.getStringExtra(EXTRA_PAGE_TITLE).orEmpty()
        val manager = OfflinePagesManager(this)
        val targetFile = manager.resolvePageFile(fileName)

        if (targetFile == null || !targetFile.isFile) {
            finish()
            return
        }
        val targetCanonicalPath = runCatching { targetFile.canonicalPath }.getOrNull()
        if (targetCanonicalPath == null) {
            finish()
            return
        }

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.bg)
        }

        toolbarView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(palette.toolbar)
            setPadding((8 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
        }
        backBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_menu_back)
            setColorFilter(palette.text)
            background = null
            contentDescription = getString(R.string.btn_back)
            setOnClickListener { finish() }
        }
        titleTv = TextView(this).apply {
            text = pageTitle.ifBlank { targetFile.name }
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.text)
            setPadding((8 * density).toInt(), 0, 0, 0)
        }
        toolbarView.addView(backBtn, LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt()))
        toolbarView.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        rootLayout.addView(toolbarView)

        webView = WebView(this).apply {
            setBackgroundColor(palette.bg)
            settings.apply {
                javaScriptEnabled = false
                blockNetworkLoads = true
                allowFileAccess = true
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                builtInZoomControls = true
                displayZoomControls = false
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val uri = request?.url
                    val scheme = uri?.scheme?.lowercase(java.util.Locale.ROOT)
                    val allowedFile = scheme == "file" && runCatching {
                        uri.path?.let { java.io.File(it).canonicalPath } == targetCanonicalPath
                    }.getOrDefault(false)
                    if (!allowedFile && scheme != "cid" && scheme != "data") {
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            403,
                            "Offline viewer blocks network",
                            emptyMap(),
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    return null
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = true
            }
        }
        rootLayout.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(rootLayout)

        webView.loadUrl(Uri.fromFile(targetFile).toString())
    }

    override fun onResume() {
        super.onResume()
        val palette = SnakeTheme.resolveBrowserColors(this)
        SnakeTheme.applySystemBars(this, palette, palette.toolbar)
        if (::rootLayout.isInitialized) {
            rootLayout.setBackgroundColor(palette.bg)
            toolbarView.setBackgroundColor(palette.toolbar)
            backBtn.setColorFilter(palette.text)
            titleTv.setTextColor(palette.text)
            if (::webView.isInitialized) {
                webView.setBackgroundColor(palette.bg)
            }
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FILE_NAME = "extra_offline_file_name"
        const val EXTRA_PAGE_TITLE = "extra_offline_page_title"
    }
}
