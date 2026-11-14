package com.nromanovich.stealthsnake

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

/**
 * Copies user-selected `content://` uploads into an isolated temporary directory with a
 * randomized filename, enforces a 50 MB size ceiling, and re-encodes raster images
 * (JPEG/PNG/WebP) to strip EXIF/GPS/XMP/camera metadata before handing the file to WebView.
 */
object UploadSanitizer {

    sealed class SanitizeResult {
        data class Success(val uri: Uri) : SanitizeResult()
        data object TooLarge : SanitizeResult()
        data object Failed : SanitizeResult()
    }

    const val MAX_UPLOAD_BYTES = 50L * 1024L * 1024L
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
                val header = ByteArray(16)
                var headerLen = 0
                while (headerLen < header.size) {
                    val n = input.read(header, headerLen, header.size - headerLen)
                    if (n <= 0) break
                    headerLen += n
                }
                if (headerLen == 0) return@runCatching SanitizeResult.Failed

                val sniffedExt = sniffRasterImageExt(header, headerLen)
                val metaExt = resolveExtension(mime, rawName)
                val ext = sniffedExt ?: metaExt
                val isRasterImage = sniffedExt != null ||
                    mime == "image/jpeg" ||
                    mime == "image/jpg" ||
                    mime == "image/png" ||
                    mime == "image/webp" ||
                    ext in setOf("jpg", "jpeg", "png", "webp")

                val targetFile = File(dir, "upload_${randomStem}.$ext").also { outFile = it }

                if (isRasterImage) {
                    val rawBytes = readBoundedBytes(header, headerLen, input)
                        ?: return@runCatching SanitizeResult.TooLarge
                    val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                        ?: return@runCatching SanitizeResult.Failed
                    val clean = Bitmap.createBitmap(
                        decoded.width.coerceAtLeast(1),
                        decoded.height.coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    try {
                        Canvas(clean).drawBitmap(decoded, 0f, 0f, null)
                        val format = when {
                            ext == "png" || mime == "image/png" -> Bitmap.CompressFormat.PNG
                            ext == "webp" || mime == "image/webp" -> compressFormatWebp()
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
