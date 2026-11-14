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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * In-app viewer for screenshots stored in the private sandbox (`filesDir/captures`).
 * Allows inspecting, manually exporting to Gallery, or deleting individual captures.
 */
class CapturesActivity : AppCompatActivity() {

    private lateinit var captureManager: CaptureManager
    private lateinit var rootLayout: LinearLayout
    private lateinit var headerView: TextView
    private lateinit var listContainer: LinearLayout
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

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            addView(listContainer)
        }
        rootLayout.addView(scroll, LinearLayout.LayoutParams(
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

        listContainer.removeAllViews()
        if (files.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.captures_empty)
                textSize = 14f
                setTextColor(palette.muted)
                gravity = Gravity.CENTER
                setPadding(0, 64, 0, 0)
            }
            listContainer.addView(empty)
            return
        }

        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        files.forEachIndexed { index, file ->
            listContainer.addView(createCaptureRow(file, fmt.format(Date(file.lastModified()))))
            if (index < files.lastIndex) {
                listContainer.addView(View(this).apply {
                    setBackgroundColor(palette.stroke)
                    alpha = 0.35f
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                        setMargins(0, 8, 0, 16)
                    }
                })
            }
        }
    }

    private fun createCaptureRow(file: File, dateText: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val cacheKey = "${file.absolutePath}:${file.lastModified()}"
        val preview = ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = (180 * resources.displayMetrics.density).toInt()
            scaleType = ImageView.ScaleType.FIT_CENTER
            tag = cacheKey
        }
        val cached = thumbnailCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            preview.setImageBitmap(cached)
        } else {
            decodeExecutor.execute {
                val bmp = decodeSampledBitmap(file, 640, 640) ?: return@execute
                thumbnailCache.put(cacheKey, bmp)
                mainHandler.post {
                    if (!isDestroyed && !isFinishing && preview.tag == cacheKey && !bmp.isRecycled) {
                        preview.setImageBitmap(bmp)
                    }
                }
            }
        }
        row.addView(preview)

        val meta = TextView(this).apply {
            text = getString(R.string.captures_item_meta, file.name, dateText)
            textSize = 12f
            setTextColor(palette.muted)
            setPadding(0, 10, 0, 6)
        }
        row.addView(meta)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val exportBtn = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.action_export_gallery)
            isAllCaps = false
            setTextColor(palette.accent)
            setOnClickListener {
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
        val deleteBtn = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.action_delete)
            isAllCaps = false
            setTextColor(palette.muted)
            setOnClickListener {
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
        }
        actions.addView(exportBtn)
        actions.addView(deleteBtn)
        row.addView(actions)
        return row
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
        listContainer.removeAllViews()
        thumbnailCache.evictAll()
        captureManager.shutdown()
        super.onDestroy()
    }
}
