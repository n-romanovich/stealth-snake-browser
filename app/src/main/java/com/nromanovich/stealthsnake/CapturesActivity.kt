package com.nromanovich.stealthsnake

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * In-app viewer for screenshots stored in the private sandbox (`filesDir/captures`).
 * Allows inspecting, manually exporting to Gallery, or deleting individual captures.
 */
class CapturesActivity : AppCompatActivity() {

    private data class CaptureEntry(
        val file: File,
        val dateText: String,
        val showDivider: Boolean
    )

    private lateinit var captureManager: CaptureManager
    private lateinit var rootLayout: LinearLayout
    private lateinit var headerView: TextView
    private lateinit var emptyView: TextView
    private lateinit var recyclerView: RecyclerView
    private val adapter = CapturesAdapter()
    private lateinit var palette: SnakeTheme.BrowserColors
    private var themeSignature: String = ""
    private var lastRenderedSignature: String? = null
    private var renderGeneration: Long = 0L
    private val decodeExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val thumbnailCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureManager = CaptureManager(this)
        palette = SnakeTheme.resolveBrowserColors(this)
        themeSignature = SnakeTheme.resolveThemeSignature(this)
        SnakeTheme.applySystemBars(this, palette, palette.bg)

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.bg)
            setPadding(24, 24, 24, 24)
        }

        headerView = TextView(this).apply {
            text = getString(R.string.captures_title)
            textSize = 20f
            setTextColor(palette.text)
            setPadding(0, 0, 0, 20)
        }
        rootLayout.addView(headerView)

        emptyView = TextView(this).apply {
            text = getString(R.string.captures_empty)
            textSize = 14f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
            setPadding(0, 64, 0, 0)
            visibility = View.GONE
        }
        rootLayout.addView(emptyView)

        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@CapturesActivity)
            this.adapter = this@CapturesActivity.adapter
            visibility = View.GONE
        }
        rootLayout.addView(recyclerView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        setContentView(rootLayout)
        renderList()
    }

    override fun onResume() {
        super.onResume()
        val latestSignature = SnakeTheme.resolveThemeSignature(this)
        val paletteChanged = latestSignature != themeSignature
        if (paletteChanged) {
            themeSignature = latestSignature
            palette = SnakeTheme.resolveBrowserColors(this)
            rootLayout.setBackgroundColor(palette.bg)
            headerView.setTextColor(palette.text)
            emptyView.setTextColor(palette.muted)
        }
        SnakeTheme.applySystemBars(this, palette, palette.bg)
        renderList(force = paletteChanged)
    }

    private fun renderList(force: Boolean = false) {
        val gen = ++renderGeneration
        if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
            renderFiles(captureManager.listCaptures(), force)
            return
        }
        runCatching {
            decodeExecutor.execute {
                val files = captureManager.listCaptures()
                mainHandler.post {
                    if (!isDestroyed && !isFinishing && gen == renderGeneration) {
                        renderFiles(files, force)
                    }
                }
            }
        }
    }

    private fun renderFiles(files: List<File>, force: Boolean) {
        val signature = "${themeSignature}|${files.joinToString(",") { "${it.name}:${it.lastModified()}" }}"
        if (!force && signature == lastRenderedSignature) return
        lastRenderedSignature = signature

        if (files.isEmpty()) {
            adapter.submitEntries(emptyList())
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
            return
        }

        emptyView.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val entries = files.mapIndexed { index, file ->
            CaptureEntry(
                file = file,
                dateText = fmt.format(Date(file.lastModified())),
                showDivider = index < files.lastIndex
            )
        }
        adapter.submitEntries(entries)
        if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
            recyclerView.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST)
            )
            recyclerView.layout(0, 0, 1080, recyclerView.measuredHeight.coerceAtLeast(1920))
        }
    }

    private class Holder(
        val row: LinearLayout,
        val preview: ImageView,
        val meta: TextView,
        val exportBtn: MaterialButton,
        val deleteBtn: MaterialButton,
        val divider: View,
        var decodeFuture: Future<*>? = null
    ) : RecyclerView.ViewHolder(row)

    private inner class CapturesAdapter : RecyclerView.Adapter<Holder>() {
        private val items = mutableListOf<CaptureEntry>()

        fun submitEntries(newItems: List<CaptureEntry>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(this@CapturesActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val preview = ImageView(this@CapturesActivity).apply {
                adjustViewBounds = true
                maxHeight = (180 * resources.displayMetrics.density).toInt()
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            row.addView(preview)

            val meta = TextView(this@CapturesActivity).apply {
                textSize = 12f
                setPadding(0, 10, 0, 6)
            }
            row.addView(meta)

            val actions = LinearLayout(this@CapturesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val exportBtn = MaterialButton(
                this@CapturesActivity,
                null,
                com.google.android.material.R.attr.borderlessButtonStyle
            ).apply {
                text = getString(R.string.action_export_gallery)
                isAllCaps = false
            }
            val deleteBtn = MaterialButton(
                this@CapturesActivity,
                null,
                com.google.android.material.R.attr.borderlessButtonStyle
            ).apply {
                text = getString(R.string.action_delete)
                isAllCaps = false
            }
            actions.addView(exportBtn)
            actions.addView(deleteBtn)
            row.addView(actions)

            val divider = View(this@CapturesActivity).apply {
                alpha = 0.35f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                    setMargins(0, 8, 0, 16)
                }
            }
            row.addView(divider)
            return Holder(row, preview, meta, exportBtn, deleteBtn, divider)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.decodeFuture?.cancel(true)
            holder.decodeFuture = null
            val entry = items[position]
            val file = entry.file
            val cacheKey = "${file.absolutePath}:${file.lastModified()}"

            holder.preview.tag = cacheKey
            val cached = thumbnailCache.get(cacheKey)
            if (cached != null && !cached.isRecycled) {
                holder.preview.setImageBitmap(cached)
            } else {
                holder.preview.setImageDrawable(null)
                holder.decodeFuture = runCatching {
                    decodeExecutor.submit {
                        val bmp = decodeSampledBitmap(file, 640, 640) ?: return@submit
                        thumbnailCache.put(cacheKey, bmp)
                        mainHandler.post {
                            if (!isDestroyed && !isFinishing && holder.preview.tag == cacheKey && !bmp.isRecycled) {
                                holder.preview.setImageBitmap(bmp)
                            }
                        }
                    }
                }.getOrNull()
            }

            holder.meta.text = getString(R.string.captures_item_meta, file.name, entry.dateText)
            holder.meta.setTextColor(palette.muted)
            holder.exportBtn.setTextColor(palette.accent)
            holder.exportBtn.setOnClickListener {
                runCatching {
                    decodeExecutor.execute {
                        val ok = captureManager.exportToGallery(file)
                        val msg = if (ok) R.string.captures_exported else R.string.captures_export_failed
                        mainHandler.post {
                            if (!isDestroyed && !isFinishing) {
                                Toast.makeText(this@CapturesActivity, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            holder.deleteBtn.setTextColor(palette.muted)
            holder.deleteBtn.setOnClickListener {
                thumbnailCache.remove(cacheKey)
                if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
                    captureManager.deleteCapture(file)
                    renderList(force = true)
                } else {
                    runCatching {
                        decodeExecutor.execute {
                            captureManager.deleteCapture(file)
                            mainHandler.post {
                                if (!isDestroyed && !isFinishing) renderList(force = true)
                            }
                        }
                    }
                }
            }
            holder.divider.setBackgroundColor(palette.stroke)
            holder.divider.visibility = if (entry.showDivider) View.VISIBLE else View.GONE
        }

        override fun onViewRecycled(holder: Holder) {
            holder.decodeFuture?.cancel(true)
            holder.decodeFuture = null
            holder.preview.tag = null
            holder.preview.setImageDrawable(null)
            super.onViewRecycled(holder)
        }
    }

    private fun decodeSampledBitmap(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var inSampleSize = 1
        while ((bounds.outHeight / inSampleSize) > reqHeight && (bounds.outWidth / inSampleSize) > reqWidth) {
            inSampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    override fun onDestroy() {
        decodeExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        recyclerView.adapter = null
        thumbnailCache.evictAll()
        captureManager.shutdown()
        super.onDestroy()
    }
}
