package com.nromanovich.stealthsnake

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.View
import android.view.Window
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Captures the visual state of the WebView directly from the OS compositor via [PixelCopy]
 * without altering View visibility, Window focus, or invoking MediaProjection/system overlays.
 *
 * Output is encoded as pure PNG (contains no EXIF metadata) and stored inside the app's
 * encrypted private sandbox (`filesDir/captures`), invisible to MediaStore/Gallery.
 */
class CaptureManager(
    context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val pixelCopyRequester: PixelCopyRequester = DefaultPixelCopyRequester
) {
    enum class Destination {
        PRIVATE,
        GALLERY
    }

    data class CaptureOutcome(
        val destination: Destination,
        val file: File?,
        val displayName: String
    )

    private val appContext = context.applicationContext
    private val capturesDir = File(appContext.filesDir, CAPTURES_DIR_NAME)
    private val sequence = AtomicInteger(0)

    fun interface PixelCopyRequester {
        fun request(
            window: Window,
            srcRect: Rect,
            destBitmap: Bitmap,
            listener: PixelCopy.OnPixelCopyFinishedListener,
            handler: Handler
        )
    }

    fun capture(window: Window, targetView: View, onComplete: (Result<File>) -> Unit) {
        captureTo(window, targetView, Destination.PRIVATE) { result ->
            onComplete(result.map { it.file ?: File(it.displayName) })
        }
    }

    fun captureTo(
        window: Window,
        targetView: View,
        destination: Destination,
        onComplete: (Result<CaptureOutcome>) -> Unit
    ) {
        if (!targetView.isAttachedToWindow || targetView.width <= 0 || targetView.height <= 0) {
            mainHandler.post {
                onComplete(Result.failure(IllegalStateException("Target view is not laid out or attached")))
            }
            return
        }

        val rect = computeWindowRect(targetView)
        if (rect.width() <= 0 || rect.height() <= 0) {
            mainHandler.post {
                onComplete(Result.failure(IllegalStateException("Computed capture rect is empty: $rect")))
            }
            return
        }

        val bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        try {
            pixelCopyRequester.request(
                window,
                rect,
                bitmap,
                { copyResult ->
                    if (copyResult == PixelCopy.SUCCESS) {
                        persistBitmapAsync(bitmap, destination, onComplete)
                    } else {
                        bitmap.recycle()
                        onComplete(Result.failure(IOException("PixelCopy failed with code $copyResult")))
                    }
                },
                mainHandler
            )
        } catch (t: Throwable) {
            bitmap.recycle()
            mainHandler.post { onComplete(Result.failure(t)) }
        }
    }

    private fun persistBitmapAsync(
        bitmap: Bitmap,
        destination: Destination,
        onComplete: (Result<CaptureOutcome>) -> Unit
    ) {
        if (ioExecutor.isShutdown) {
            bitmap.recycle()
            mainHandler.post {
                onComplete(Result.failure(IllegalStateException("CaptureManager has been shut down")))
            }
            return
        }
        try {
            ioExecutor.execute {
                val result = try {
                    val fileName = "capture_${System.currentTimeMillis()}_${sequence.incrementAndGet()}.png"
                    if (destination == Destination.GALLERY) {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            throw UnsupportedOperationException("Gallery export requires Android 10+")
                        }
                        saveBitmapToMediaStore(bitmap, fileName)
                        Result.success(CaptureOutcome(Destination.GALLERY, null, fileName))
                    } else {
                        if (!capturesDir.exists() && !capturesDir.mkdirs()) {
                            throw IOException("Failed to create captures directory: $capturesDir")
                        }
                        val file = File(capturesDir, fileName)
                        FileOutputStream(file).use { out ->
                            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                                throw IOException("Bitmap.compress returned false")
                            }
                            out.flush()
                        }
                        Result.success(CaptureOutcome(Destination.PRIVATE, file, file.name))
                    }
                } catch (t: Throwable) {
                    Result.failure(t)
                } finally {
                    bitmap.recycle()
                }
                mainHandler.post { onComplete(result) }
            }
        } catch (t: Throwable) {
            bitmap.recycle()
            mainHandler.post { onComplete(Result.failure(t)) }
        }
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap, fileName: String) {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/StealthSnake")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert returned null")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IOException("Bitmap.compress returned false")
                }
                out.flush()
            } ?: throw IOException("Failed to open MediaStore output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    fun exportToGallery(file: File): Boolean {
        if (!file.isFile || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/StealthSnake")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            val outStream = resolver.openOutputStream(uri)
            if (outStream == null) {
                runCatching { resolver.delete(uri, null, null) }
                return false
            }
            outStream.use { out ->
                FileInputStream(file).use { input -> input.copyTo(out) }
                out.flush()
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    fun deleteCapture(file: File): Boolean {
        if (!file.isFile) return false
        val inside = runCatching {
            file.canonicalFile.parentFile?.canonicalPath == capturesDir.canonicalPath
        }.getOrDefault(false)
        return inside && file.delete()
    }

    fun listCaptures(): List<File> {
        if (!capturesDir.exists()) return emptyList()
        return capturesDir.listFiles { file -> file.isFile && file.name.endsWith(".png") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun clearCaptures(): Int {
        if (!capturesDir.exists()) return 0
        var deleted = 0
        capturesDir.listFiles()?.forEach { file ->
            if (file.isFile && file.delete()) deleted++
        }
        return deleted
    }

    fun shutdown() {
        ioExecutor.shutdown()
    }

    companion object {
        const val CAPTURES_DIR_NAME = "captures"

        fun computeWindowRect(targetView: View): Rect {
            val location = IntArray(2)
            targetView.getLocationInWindow(location)
            val root = targetView.rootView
            val maxW = if (root != null && root.width > 0) root.width else Int.MAX_VALUE
            val maxH = if (root != null && root.height > 0) root.height else Int.MAX_VALUE
            val left = location[0].coerceIn(0, maxW)
            val top = location[1].coerceIn(0, maxH)
            val right = (location[0] + targetView.width).coerceIn(left, maxW)
            val bottom = (location[1] + targetView.height).coerceIn(top, maxH)
            return Rect(left, top, right, bottom)
        }

        private val DefaultPixelCopyRequester = PixelCopyRequester { window, srcRect, destBitmap, listener, handler ->
            PixelCopy.request(window, srcRect, destBitmap, listener, handler)
        }
    }
}
