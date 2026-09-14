package io.github.taxray.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class CompressedReceiptImage(val bytes: ByteArray, val width: Int, val height: Int) {
    override fun toString(): String = "CompressedReceiptImage(${width}x$height, ${bytes.size} bytes)"
}

/** Decodes a sampled bitmap; strips original metadata by encoding a new in-memory JPEG. */
class ImageCompressor(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun compress(uri: Uri): CompressedReceiptImage = withContext(Dispatchers.IO) {
        var bitmap: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth in 1..100_000 && bounds.outHeight in 1..100_000) {
                "图片无法读取，请选择清晰的 JPEG、PNG 或相机照片。"
            }
            currentCoroutineContext().ensureActive()
            val orientation = open(uri).use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            bitmap = open(uri).use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw IllegalArgumentException("图片解码失败，请重新选图。")
            currentCoroutineContext().ensureActive()

            withContext(Dispatchers.Default) compression@{
                val transform = orientationMatrix(orientation)
                if (!transform.isIdentity) {
                    val source = requireNotNull(bitmap)
                    val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, transform, true)
                    if (rotated !== source) source.recycle()
                    bitmap = rotated
                }
                // Some image formats do not honor inSampleSize exactly.
                if (max(requireNotNull(bitmap).width, requireNotNull(bitmap).height) > MAX_EDGE) {
                    bitmap = resize(requireNotNull(bitmap), MAX_EDGE)
                }
                // JPEG has no alpha channel: composite transparent screenshots onto receipt-white.
                requireNotNull(bitmap).takeIf { it.hasAlpha() }?.let { source ->
                    val opaque = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                    opaque.eraseColor(Color.WHITE)
                    Canvas(opaque).drawBitmap(source, 0f, 0f, null)
                    source.recycle()
                    bitmap = opaque
                }
                while (true) {
                    val source = requireNotNull(bitmap)
                    for (quality in intArrayOf(88, 76, 64, 52, 40)) {
                        currentCoroutineContext().ensureActive()
                        val bytes = ByteArrayOutputStream().use { output ->
                            check(source.compress(Bitmap.CompressFormat.JPEG, quality, output))
                            output.toByteArray()
                        }
                        if (bytes.size <= MAX_BYTES) {
                            return@compression CompressedReceiptImage(bytes, source.width, source.height)
                        }
                    }
                    val edge = max(source.width, source.height)
                    check(edge > 256) { "图片压缩失败，请裁剪小票后重试。" }
                    bitmap = resize(source, (edge * 0.8).roundToInt().coerceAtLeast(256))
                }
                @Suppress("UNREACHABLE_CODE")
                error("Unreachable")
            }
        } catch (_: OutOfMemoryError) {
            throw IllegalArgumentException("图片过大，内存不足。请裁剪或重新拍摄小票。")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: IllegalArgumentException) {
            throw invalid
        } catch (_: Exception) {
            throw IllegalArgumentException("图片读取或压缩失败，请重新选择图片或拍摄小票。")
        } finally {
            bitmap?.takeUnless { it.isRecycled }?.recycle()
        }
    }

    private fun open(uri: Uri): InputStream {
        require(uri.scheme == "content" || uri.scheme == "file") { "请选择本地图片。" }
        val source = resolver.openInputStream(uri) ?: throw IllegalArgumentException("无法打开图片。")
        return object : FilterInputStream(source) {
            private var readBytes = 0L
            private fun checkLimit(count: Long) {
                if (count > 0) readBytes += count
                require(readBytes <= MAX_SOURCE_BYTES) { "原始图片超过 40 MB，请先裁剪。" }
            }
            override fun read(): Int = super.read().also { if (it >= 0) checkLimit(1) }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                `in`.read(buffer, offset, length).also { checkLimit(it.toLong()) }
            override fun skip(count: Long): Long = super.skip(count).also(::checkLimit)
        }
    }

    private fun resize(source: Bitmap, maxEdge: Int): Bitmap {
        val ratio = maxEdge.toDouble() / max(source.width, source.height)
        val scaled = Bitmap.createScaledBitmap(
            source, (source.width * ratio).roundToInt().coerceAtLeast(1),
            (source.height * ratio).roundToInt().coerceAtLeast(1), true,
        )
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }

    companion object {
        const val MAX_EDGE = 1_920
        const val MAX_BYTES = 1_000_000
        private const val MAX_SOURCE_BYTES = 40_000_000L
    }
}
