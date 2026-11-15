package com.nromanovich.stealthsnake

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Copies user-selected `content://` uploads into an isolated temporary directory with a
 * randomized filename, enforces a 50 MB size ceiling, blocks active/XML image payloads (SVG),
 * and re-encodes raster images to normalize orientation and strip EXIF/GPS/XMP/camera metadata
 * before handing the file to WebView.
 */
object UploadSanitizer {

    sealed class SanitizeResult {
        data class Success(val uri: Uri) : SanitizeResult()
        data object TooLarge : SanitizeResult()
        data object Failed : SanitizeResult()
    }

    const val MAX_UPLOAD_BYTES = 50L * 1024L * 1024L
    internal const val MAX_IMAGE_PIXELS = 25_000_000L
    private const val DOWNSAMPLE_THRESHOLD_PIXELS = 16_000_000L
    private const val HEADER_SNIFF_BYTES = 512
    private const val UPLOAD_DIR = "uploads"

    fun sanitize(context: Context, sourceUri: Uri): SanitizeResult {
        if (!sourceUri.scheme.equals("content", ignoreCase = true)) {
            return SanitizeResult.Failed
        }
        val resolver = context.contentResolver
        val declaredSize = queryDeclaredSize(resolver, sourceUri)
        if (declaredSize != null && declaredSize > MAX_UPLOAD_BYTES) {
            return SanitizeResult.TooLarge
        }

        val mime = resolver.getType(sourceUri)?.lowercase(Locale.ROOT).orEmpty()
        val rawName = queryDisplayName(resolver, sourceUri) ?: sourceUri.lastPathSegment
        val dir = File(context.cacheDir, UPLOAD_DIR).apply { mkdirs() }
        val randomStem = UUID.randomUUID().toString().replace("-", "").take(16)
        var outFile: File? = null

        return runCatching {
            resolver.openInputStream(sourceUri)?.use { input ->
                val header = ByteArray(HEADER_SNIFF_BYTES)
                var headerLen = 0
                while (headerLen < header.size) {
                    val n = input.read(header, headerLen, header.size - headerLen)
                    if (n <= 0) break
                    headerLen += n
                }
                if (headerLen == 0) return@runCatching SanitizeResult.Failed

                if (isSvgUpload(header, headerLen, mime, rawName)) {
                    return@runCatching SanitizeResult.Failed
                }

                val sniffedExt = sniffRasterImageExt(header, headerLen)
                val metaExt = resolveExtension(mime, rawName)
                val isDeclaredImage = mime.startsWith("image/") ||
                    metaExt in IMAGE_EXTENSIONS
                val isRasterImage = sniffedExt != null || isDeclaredImage

                val outputExt = when {
                    sniffedExt == "jpg" || sniffedExt == "png" || sniffedExt == "webp" -> sniffedExt
                    sniffedExt != null -> "png"
                    isRasterImage && (metaExt == "jpg" || metaExt == "jpeg") -> "jpg"
                    isRasterImage && metaExt == "webp" -> "webp"
                    isRasterImage -> "png"
                    else -> metaExt
                }

                val targetFile = File(dir, "upload_${randomStem}.$outputExt").also { outFile = it }

                if (isRasterImage) {
                    val rawBytes = readBoundedBytes(header, headerLen, input)
                        ?: return@runCatching SanitizeResult.TooLarge
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                        return@runCatching SanitizeResult.Failed
                    }
                    val totalPixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
                    if (totalPixels > MAX_IMAGE_PIXELS) {
                        return@runCatching SanitizeResult.TooLarge
                    }
                    var sampleSize = 1
                    if (totalPixels > DOWNSAMPLE_THRESHOLD_PIXELS) {
                        while (((bounds.outWidth / sampleSize).toLong() * (bounds.outHeight / sampleSize).toLong()) >
                            DOWNSAMPLE_THRESHOLD_PIXELS
                        ) {
                            sampleSize *= 2
                        }
                    }
                    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
                        ?: return@runCatching SanitizeResult.Failed

                    val orientation = runCatching {
                        ExifInterface(ByteArrayInputStream(rawBytes)).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
                    val matrix = orientationMatrix(orientation)
                    val dstRect = RectF(0f, 0f, decoded.width.toFloat(), decoded.height.toFloat())
                    matrix.mapRect(dstRect)
                    matrix.postTranslate(-dstRect.left, -dstRect.top)
                    val targetW = dstRect.width().roundToInt().coerceAtLeast(1)
                    val targetH = dstRect.height().roundToInt().coerceAtLeast(1)

                    val clean = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(clean).drawBitmap(decoded, matrix, null)
                        val format = when (outputExt) {
                            "png" -> Bitmap.CompressFormat.PNG
                            "webp" -> compressFormatWebp()
                            else -> Bitmap.CompressFormat.JPEG
                        }
                        FileOutputStream(targetFile).use { out ->
                            if (!clean.compress(format, 92, out)) {
                                targetFile.delete()
                                return@runCatching SanitizeResult.Failed
                            }
                        }
                    } finally {
                        clean.recycle()
                        decoded.recycle()
                    }
                } else {
                    FileOutputStream(targetFile).use { out ->
                        out.write(header, 0, headerLen)
                        val buf = ByteArray(16 * 1024)
                        var total = headerLen.toLong()
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            total += read
                            if (total > MAX_UPLOAD_BYTES) {
                                targetFile.delete()
                                return@runCatching SanitizeResult.TooLarge
                            }
                            out.write(buf, 0, read)
                        }
                    }
                }
                SanitizeResult.Success(Uri.fromFile(targetFile))
            } ?: SanitizeResult.Failed
        }.getOrElse {
            outFile?.delete()
            SanitizeResult.Failed
        }
    }

    fun clearTempUploads(context: Context) {
        runCatching {
            val dir = File(context.cacheDir, UPLOAD_DIR)
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "gif", "bmp", "tif", "tiff"
    )

    private fun isSvgUpload(header: ByteArray, len: Int, mime: String, rawName: String?): Boolean {
        if (mime.contains("svg")) return true
        val lowerExt = rawName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
        if (lowerExt == "svg" || lowerExt == "svgz") return true
        val preview = runCatching {
            String(header, 0, len, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n').lowercase(Locale.ROOT)
        }.getOrNull() ?: return false
        return preview.startsWith("<svg") || (preview.startsWith("<?xml") && preview.contains("<svg"))
    }

    private fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                setRotate(270f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
        }
    }

    private fun sniffRasterImageExt(header: ByteArray, len: Int): String? {
        if (len >= 3 &&
            header[0] == 0xFF.toByte() &&
            header[1] == 0xD8.toByte() &&
            header[2] == 0xFF.toByte()
        ) {
            return "jpg"
        }
        if (len >= 8 &&
            header[0] == 0x89.toByte() &&
            header[1] == 0x50.toByte() &&
            header[2] == 0x4E.toByte() &&
            header[3] == 0x47.toByte() &&
            header[4] == 0x0D.toByte() &&
            header[5] == 0x0A.toByte() &&
            header[6] == 0x1A.toByte() &&
            header[7] == 0x0A.toByte()
        ) {
            return "png"
        }
        if (len >= 12 &&
            header[0] == 'R'.code.toByte() &&
            header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() &&
            header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() &&
            header[9] == 'E'.code.toByte() &&
            header[10] == 'B'.code.toByte() &&
            header[11] == 'P'.code.toByte()
        ) {
            return "webp"
        }
        if (len >= 6 &&
            header[0] == 'G'.code.toByte() &&
            header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() &&
            header[3] == '8'.code.toByte() &&
            (header[4] == '7'.code.toByte() || header[4] == '9'.code.toByte()) &&
            header[5] == 'a'.code.toByte()
        ) {
            return "gif"
        }
        if (len >= 2 && header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte()) {
            return "bmp"
        }
        if (len >= 4 && (
            (header[0] == 'I'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 42.toByte() && header[3] == 0.toByte()) ||
            (header[0] == 'M'.code.toByte() && header[1] == 'M'.code.toByte() && header[2] == 0.toByte() && header[3] == 42.toByte())
        )) {
            return "tiff"
        }
        if (len >= 12 &&
            header[4] == 'f'.code.toByte() &&
            header[5] == 't'.code.toByte() &&
            header[6] == 'y'.code.toByte() &&
            header[7] == 'p'.code.toByte()
        ) {
            val brand = String(header, 8, 4, Charsets.US_ASCII).lowercase(Locale.ROOT)
            if (brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1")) return "heic"
            if (brand == "avif" || brand == "avis") return "avif"
        }
        return null
    }

    private fun readBoundedBytes(header: ByteArray, headerLen: Int, input: java.io.InputStream): ByteArray? {
        val baos = ByteArrayOutputStream()
        baos.write(header, 0, headerLen)
        val buf = ByteArray(16 * 1024)
        var total = headerLen.toLong()
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            total += read
            if (total > MAX_UPLOAD_BYTES) return null
            baos.write(buf, 0, read)
        }
        return baos.toByteArray()
    }

    private fun queryDeclaredSize(resolver: android.content.ContentResolver, uri: Uri): Long? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getLong(idx) else null
            }
        }.getOrNull()

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getString(idx) else null
            }
        }.getOrNull()

    private fun resolveExtension(mime: String, rawName: String?): String {
        val byMime = when (mime) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "application/pdf" -> "pdf"
            "text/plain" -> "txt"
            else -> null
        }
        if (byMime != null) return byMime
        val suffix = rawName?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            ?.filter { it.isLetterOrDigit() }
            ?.take(8)
        return if (!suffix.isNullOrBlank()) suffix else "bin"
    }

    @Suppress("DEPRECATION")
    private fun compressFormatWebp(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
}
