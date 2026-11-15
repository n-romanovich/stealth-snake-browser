package com.nromanovich.stealthsnake

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * Downloads & Offline Pages hub.
 * Displays recent downloads and saved MHTML pages in a [RecyclerView] with Material 3 Expressive
 * cards, and prompts to enable downloads in Settings when disabled.
 */
class DownloadsActivity : AppCompatActivity() {

    private sealed class ListItem {
        data class Header(val title: String, val topDp: Float) : ListItem()
        data class Empty(val message: String) : ListItem()
        data class Download(val entry: DownloadHistoryStore.Entry, val dateText: String) : ListItem()
        data class SavedPage(val page: OfflinePagesManager.SavedPage, val dateText: String) : ListItem()
    }

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
    private lateinit var bannerSettingsBtn: Button
    private lateinit var disabledBanner: LinearLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: DownloadsAdapter
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var loadGeneration: Long = 0L
    private var activeConfirmDialog: AlertDialog? = null

    private fun runIoTask(task: () -> Unit) {
        if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
            task()
        } else {
            runCatching { ioExecutor.execute(task) }
        }
    }

    override fun onPause() {
        activeConfirmDialog?.dismiss()
        activeConfirmDialog = null
        super.onPause()
    }

    override fun onDestroy() {
        activeConfirmDialog?.dismiss()
        activeConfirmDialog = null
        ioExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        SnakeTheme.applyDynamicActivityOverlay(this)
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
                    historyStore.clearWithFiles(this@DownloadsActivity)
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

        disabledBanner = buildDisabledBanner(density).apply {
            val hMargin = (16 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(hMargin, (4 * density).toInt(), hMargin, (8 * density).toInt())
            }
        }
        rootLayout.addView(disabledBanner)

        adapter = DownloadsAdapter()
        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DownloadsActivity)
            this.adapter = this@DownloadsActivity.adapter
            clipToPadding = false
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (24 * density).toInt())
        }
        rootLayout.addView(recyclerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

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
        bannerSettingsBtn.setTextColor(tokens.onPrimaryAccent)
        bannerSettingsBtn.background = SnakeTheme.pillRippleSurface(tokens.primaryAccent, rippleColor, 20f, density)
        adapter.notifyDataSetChanged()
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

        bannerSettingsBtn = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.downloads_open_settings)
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
        banner.addView(bannerSettingsBtn)
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
        disabledBanner.visibility = if (prefs.blockDownloads) View.VISIBLE else View.GONE
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
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val items = buildList {
            add(ListItem.Header(getString(R.string.downloads_section_files), 4f))
            if (downloads.isEmpty()) {
                add(ListItem.Empty(getString(R.string.downloads_empty_files)))
            } else {
                for (entry in downloads) {
                    add(ListItem.Download(entry, fmt.format(Date(entry.timestampMs))))
                }
            }
            add(ListItem.Header(getString(R.string.downloads_section_pages), 16f))
            if (pages.isEmpty()) {
                add(ListItem.Empty(getString(R.string.downloads_empty_pages)))
            } else {
                for (page in pages) {
                    add(ListItem.SavedPage(page, fmt.format(Date(page.savedAtMs))))
                }
            }
        }
        adapter.submitItems(items)
        if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
            recyclerView.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST)
            )
            recyclerView.layout(0, 0, 1080, recyclerView.measuredHeight.coerceAtLeast(1920))
        }
    }

    private fun confirmOpenDownloadedFile(entry: DownloadHistoryStore.Entry) {
        activeConfirmDialog?.dismiss()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.downloads_open_warning_title)
            .setMessage(getString(R.string.downloads_open_warning_message, entry.fileName))
            .setPositiveButton(R.string.action_open) { _, _ ->
                val targetUri = entry.uriString?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                if (targetUri == null) {
                    Toast.makeText(this, R.string.downloads_open_failed, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(targetUri, entry.mimeType.ifBlank { "application/octet-stream" })
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { startActivity(intent) }.onFailure {
                    Toast.makeText(this, R.string.downloads_open_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .setOnDismissListener {
                activeConfirmDialog = null
            }
            .show()
        activeConfirmDialog = dialog
        SnakeTheme.styleExpressiveDialog(dialog, prefs.themePalette)
    }

    private class Holder(val slot: LinearLayout) : RecyclerView.ViewHolder(slot)

    private inner class DownloadsAdapter : RecyclerView.Adapter<Holder>() {
        private var items: List<ListItem> = emptyList()

        fun submitItems(newItems: List<ListItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is ListItem.Header -> 0
            is ListItem.Empty -> 1
            is ListItem.Download -> 2
            is ListItem.SavedPage -> 3
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val container = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            return Holder(container)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val density = resources.displayMetrics.density
            holder.slot.removeAllViews()
            when (val item = items[position]) {
                is ListItem.Header -> holder.slot.addView(sectionHeader(item.title, density, item.topDp))
                is ListItem.Empty -> holder.slot.addView(emptyCard(item.message, density))
                is ListItem.Download -> holder.slot.addView(createDownloadCard(item.entry, item.dateText, density))
                is ListItem.SavedPage -> holder.slot.addView(createSavedPageCard(item.page, item.dateText, density))
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
            orientation = LinearLayout.VERTICAL
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
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
            val baseMeta = getString(R.string.downloads_item_meta, statusLabel, entry.mimeType, dateText)
            text = if (entry.sizeBytes >= 0L) {
                "$baseMeta · ${(entry.sizeBytes / 1024L).coerceAtLeast(1L)} KB"
            } else {
                baseMeta
            }
            textSize = 13f
            setTextColor(if (entry.success) tokens.textSecondary else Color.parseColor("#EF4444"))
            setPadding(0, (3 * density).toInt(), 0, 0)
        }
        col.addView(nameTv)
        col.addView(metaTv)
        topRow.addView(icon)
        topRow.addView(col)
        card.addView(topRow)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (10 * density).toInt(), 0, 0)
        }
        val hPad = (14 * density).toInt()
        if (entry.success && !entry.uriString.isNullOrBlank()) {
            val openBtn = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = getString(R.string.action_open)
                isAllCaps = false
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                minHeight = (34 * density).toInt()
                setTextColor(tokens.onPrimaryAccent)
                background = SnakeTheme.pillRippleSurface(tokens.primaryAccent, rippleColor, 17f, density)
                setPadding(hPad, 0, hPad, 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    (34 * density).toInt()
                ).apply {
                    marginEnd = (8 * density).toInt()
                }
                SnakeTheme.attachExpressivePressAnimation(this, scaleDown = 0.96f)
                setOnClickListener { confirmOpenDownloadedFile(entry) }
            }
            actions.addView(openBtn)
        }
        val deleteBtn = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.action_delete)
            isAllCaps = false
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minHeight = (34 * density).toInt()
            setTextColor(tokens.textSecondary)
            background = SnakeTheme.outlinedPillRippleSurface(tokens.outlineStroke, rippleColor, 17f, density, tokens.cardSurface)
            setPadding(hPad, 0, hPad, 0)
            SnakeTheme.attachExpressivePressAnimation(this, scaleDown = 0.96f)
            setOnClickListener {
                runIoTask {
                    historyStore.deleteWithFile(this@DownloadsActivity, entry.id)
                    mainHandler.post {
                        if (!isDestroyed && !isFinishing) renderContent()
                    }
                }
            }
        }
        actions.addView(deleteBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (34 * density).toInt()))
        card.addView(actions)
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
