package com.nromanovich.stealthsnake

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * Downloads & Offline Pages hub.
 * Displays recent downloads and saved MHTML pages in Material 3 Expressive cards,
 * and prompts to enable downloads in Settings when disabled.
 */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var prefs: SnakePreferences
    private lateinit var historyStore: DownloadHistoryStore
    private lateinit var offlineManager: OfflinePagesManager
    private lateinit var tokens: SnakeTheme.MaterialYouTokens
    private var themeSignature: String = ""
    private var rippleColor: Int = Color.TRANSPARENT

    private lateinit var rootLayout: LinearLayout
    private lateinit var backBtn: ImageButton
    private lateinit var titleTv: TextView
    private lateinit var clearBtn: Button
    private lateinit var bannerIcon: ImageView
    private lateinit var bannerMsgTv: TextView
    private lateinit var bannerEnableBtn: Button
    private lateinit var contentList: LinearLayout
    private lateinit var disabledBanner: LinearLayout
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var loadGeneration: Long = 0L

    private fun runIoTask(task: () -> Unit) {
        if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
            task()
        } else {
            runCatching { ioExecutor.execute(task) }
        }
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SnakePreferences(this)
        historyStore = DownloadHistoryStore(this)
        offlineManager = OfflinePagesManager(this)
        tokens = SnakeTheme.resolveTokens(prefs.themePalette, this)
        themeSignature = SnakeTheme.resolveThemeSignature(this)
        rippleColor = resolveRippleColor(tokens)
        val density = resources.displayMetrics.density
        SnakeTheme.applySystemBars(this, prefs.themePalette, tokens.screenBg)

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(tokens.screenBg)
        }

        // Top app bar matching SettingsActivity
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
        }
        backBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings_back)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = getString(R.string.btn_back)
            layoutParams = LinearLayout.LayoutParams((42 * density).toInt(), (42 * density).toInt()).apply {
                marginEnd = (12 * density).toInt()
            }
            SnakeTheme.attachExpressivePressAnimation(this, scaleDown = 0.90f)
            setOnClickListener { finish() }
        }
        titleTv = TextView(this).apply {
            text = getString(R.string.downloads_title)
            textSize = 24f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        clearBtn = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.downloads_clear_history)
            isAllCaps = false
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minHeight = (36 * density).toInt()
            val hPad = (14 * density).toInt()
            setPadding(hPad, 0, hPad, 0)
            SnakeTheme.attachExpressivePressAnimation(this, scaleDown = 0.95f)
            setOnClickListener {
                runIoTask {
                    historyStore.clear()
                    mainHandler.post {
                        if (!isDestroyed && !isFinishing) renderContent()
                    }
                }
            }
        }
        headerRow.addView(backBtn)
        headerRow.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(clearBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (36 * density).toInt()))
        rootLayout.addView(headerRow)

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (24 * density).toInt())
        }
        val scrollBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        disabledBanner = buildDisabledBanner(density)
        scrollBody.addView(disabledBanner)

        contentList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollBody.addView(contentList)
        scroll.addView(scrollBody)
        rootLayout.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        applyChromeTheme(density)
        setContentView(rootLayout)
    }

    private fun resolveRippleColor(t: SnakeTheme.MaterialYouTokens): Int {
        return ColorUtils.setAlphaComponent(if (t.isLight) Color.BLACK else Color.WHITE, if (t.isLight) 30 else 40)
    }

    private fun applyChromeTheme(density: Float) {
        SnakeTheme.applySystemBars(this, prefs.themePalette, tokens.screenBg)
        rootLayout.setBackgroundColor(tokens.screenBg)

        val backCircleBg = if (tokens.isLight) tokens.pillContainer else tokens.elevatedSurface
        val backCircle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(backCircleBg)
        }
        backBtn.background = RippleDrawable(ColorStateList.valueOf(rippleColor), backCircle, null)
        backBtn.setColorFilter(tokens.textPrimary, PorterDuff.Mode.SRC_IN)

        titleTv.setTextColor(tokens.textPrimary)
        clearBtn.setTextColor(tokens.textSecondary)
        clearBtn.background = SnakeTheme.outlinedPillRippleSurface(tokens.outlineStroke, rippleColor, 18f, density, tokens.cardSurface)

        disabledBanner.background = SnakeTheme.roundedSurface(tokens.cardSurface, Color.TRANSPARENT, 20f, density)
        bannerIcon.setColorFilter(tokens.primaryAccent, PorterDuff.Mode.SRC_IN)
        bannerMsgTv.setTextColor(tokens.textPrimary)
        bannerEnableBtn.setTextColor(tokens.onPrimaryAccent)
        bannerEnableBtn.background = SnakeTheme.pillRippleSurface(tokens.primaryAccent, rippleColor, 20f, density)
    }

    private fun buildDisabledBanner(density: Float): LinearLayout {
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (16 * density).toInt()
            }
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bannerIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings_downloads)
            layoutParams = LinearLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt()).apply {
                marginEnd = (12 * density).toInt()
            }
        }
        bannerMsgTv = TextView(this).apply {
            text = getString(R.string.downloads_disabled_notice)
            textSize = 14f
            setLineSpacing(2f * density, 1f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(bannerIcon)
        topRow.addView(bannerMsgTv)

        bannerEnableBtn = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.downloads_enable_in_settings)
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minHeight = (40 * density).toInt()
            val hPad = (20 * density).toInt()
            setPadding(hPad, 0, hPad, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (40 * density).toInt()
            ).apply {
                topMargin = (14 * density).toInt()
            }
            SnakeTheme.attachExpressivePressAnimation(this, scaleDown = 0.96f)
            setOnClickListener {
                startActivity(
                    Intent(this@DownloadsActivity, SettingsActivity::class.java).apply {
                        putExtra(SettingsActivity.EXTRA_SCROLL_TO_SECTION, SettingsActivity.SECTION_DOWNLOADS)
                    }
                )
            }
        }
        banner.addView(topRow)
        banner.addView(bannerEnableBtn)
        return banner
    }

    override fun onResume() {
        super.onResume()
        val latestSignature = SnakeTheme.resolveThemeSignature(this)
        if (latestSignature != themeSignature) {
            themeSignature = latestSignature
            tokens = SnakeTheme.resolveTokens(prefs.themePalette, this)
            rippleColor = resolveRippleColor(tokens)
            applyChromeTheme(resources.displayMetrics.density)
        }
        SnakeTheme.applySystemBars(this, prefs.themePalette, tokens.screenBg)
        renderContent()
    }

    private fun renderContent() {
        disabledBanner.visibility = if (prefs.downloadsEnabled) View.GONE else View.VISIBLE
        val gen = ++loadGeneration
        if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
            renderLoadedContent(historyStore.list(), offlineManager.listPages())
            return
        }
        runIoTask {
            val downloads = historyStore.list()
            val pages = offlineManager.listPages()
            mainHandler.post {
                if (!isDestroyed && !isFinishing && gen == loadGeneration) {
                    renderLoadedContent(downloads, pages)
                }
            }
        }
    }

    private fun renderLoadedContent(
        downloads: List<DownloadHistoryStore.Entry>,
        pages: List<OfflinePagesManager.SavedPage>
    ) {
        val density = resources.displayMetrics.density
        contentList.removeAllViews()

        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

        // Section 1: Recent File Downloads
        contentList.addView(sectionHeader(getString(R.string.downloads_section_files), density))
        if (downloads.isEmpty()) {
            contentList.addView(emptyCard(getString(R.string.downloads_empty_files), density))
        } else {
            for (entry in downloads) {
                contentList.addView(createDownloadCard(entry, fmt.format(Date(entry.timestampMs)), density))
            }
        }

        // Section 2: Saved Offline Pages
        contentList.addView(sectionHeader(getString(R.string.downloads_section_pages), density, topDp = 16f))
        if (pages.isEmpty()) {
            contentList.addView(emptyCard(getString(R.string.downloads_empty_pages), density))
        } else {
            for (page in pages) {
                contentList.addView(createSavedPageCard(page, fmt.format(Date(page.savedAtMs)), density))
            }
        }
    }

    private fun sectionHeader(title: String, density: Float, topDp: Float = 4f): TextView = TextView(this).apply {
        text = title
        textSize = 13.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(tokens.categoryHeader)
        setPadding((4 * density).toInt(), (topDp * density).toInt(), (4 * density).toInt(), (10 * density).toInt())
    }

    private fun emptyCard(text: String, density: Float): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = SnakeTheme.roundedSurface(tokens.cardSurface, Color.TRANSPARENT, 18f, density)
        val pad = (16 * density).toInt()
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = (8 * density).toInt()
        }
        addView(TextView(this@DownloadsActivity).apply {
            this.text = text
            textSize = 13.5f
            setTextColor(tokens.textSecondary)
        })
    }

    private fun createDownloadCard(
        entry: DownloadHistoryStore.Entry,
        dateText: String,
        density: Float
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = SnakeTheme.roundedSurface(tokens.cardSurface, Color.TRANSPARENT, 18f, density)
            val pad = (16 * density).toInt()
            setPadding(pad, (14 * density).toInt(), pad, (14 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings_downloads)
            setColorFilter(if (entry.success) tokens.primaryAccent else Color.parseColor("#EF4444"), PorterDuff.Mode.SRC_IN)
            layoutParams = LinearLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt()).apply {
                marginEnd = (14 * density).toInt()
            }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameTv = TextView(this).apply {
            text = entry.fileName
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(tokens.textPrimary)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val statusLabel = if (entry.success) {
            getString(R.string.downloads_status_saved)
        } else {
            getString(R.string.downloads_status_failed)
        }
        val metaTv = TextView(this).apply {
            text = getString(R.string.downloads_item_meta, statusLabel, entry.mimeType, dateText)
            textSize = 13f
            setTextColor(if (entry.success) tokens.textSecondary else Color.parseColor("#EF4444"))
            setPadding(0, (3 * density).toInt(), 0, 0)
        }
        col.addView(nameTv)
        col.addView(metaTv)
        card.addView(icon)
        card.addView(col)
        return card
    }

    private fun createSavedPageCard(
        page: OfflinePagesManager.SavedPage,
        dateText: String,
        density: Float
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SnakeTheme.roundedSurface(tokens.cardSurface, Color.TRANSPARENT, 18f, density)
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, (14 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
        }
        val titleTv = TextView(this).apply {
            text = page.title
            textSize = 15.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(tokens.textPrimary)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val metaTv = TextView(this).apply {
            val kb = (page.sizeBytes / 1024L).coerceAtLeast(1L)
            text = getString(R.string.downloads_page_meta, page.url, kb, dateText)
            textSize = 13f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(tokens.textSecondary)
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        val hPad = (16 * density).toInt()
        val openBtn = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.downloads_open_offline)
            isAllCaps = false
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minHeight = (36 * density).toInt()
            setTextColor(tokens.onPrimaryAccent)
            background = SnakeTheme.pillRippleSurface(tokens.primaryAccent, rippleColor, 18f, density)
            setPadding(hPad, 0, hPad, 0)
            SnakeTheme.attachExpressivePressAnimation(this, scaleDown = 0.96f)
            setOnClickListener {
                startActivity(
                    Intent(this@DownloadsActivity, OfflineViewerActivity::class.java).apply {
                        putExtra(OfflineViewerActivity.EXTRA_FILE_NAME, page.fileName)
                        putExtra(OfflineViewerActivity.EXTRA_PAGE_TITLE, page.title)
                    }
                )
            }
        }
        val deleteBtn = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.action_delete)
            isAllCaps = false
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minHeight = (36 * density).toInt()
            setTextColor(tokens.textSecondary)
            background = SnakeTheme.outlinedPillRippleSurface(tokens.outlineStroke, rippleColor, 18f, density, tokens.cardSurface)
            setPadding(hPad, 0, hPad, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (36 * density).toInt()
            ).apply {
                marginStart = (8 * density).toInt()
            }
            SnakeTheme.attachExpressivePressAnimation(this, scaleDown = 0.96f)
            setOnClickListener {
                runIoTask {
                    offlineManager.deletePage(page.id)
                    mainHandler.post {
                        if (!isDestroyed && !isFinishing) renderContent()
                    }
                }
            }
        }
        actions.addView(openBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (36 * density).toInt()))
        actions.addView(deleteBtn)

        card.addView(titleTv)
        card.addView(metaTv)
        card.addView(actions)
        return card
    }
}
