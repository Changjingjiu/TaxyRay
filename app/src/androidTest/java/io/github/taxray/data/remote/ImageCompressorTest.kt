package io.github.taxray.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageCompressorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val generated = mutableListOf<File>()

    @After fun tearDown() { generated.forEach { it.delete() } }

    @Test fun sampledCompressionStaysWithinPixelAndActualByteLimits() = runBlocking {
        val width = 3_000
        val height = 2_200
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        val random = java.util.Random(42)
        for (y in 0 until height) {
            for (x in row.indices) row[x] = random.nextInt() or (0xFF shl 24)
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        val file = imageFile(bitmap)
        bitmap.recycle()
        val result = ImageCompressor(context).compress(Uri.fromFile(file))
        assertTrue(result.width <= 1_920 && result.height <= 1_920)
        assertTrue(result.bytes.isNotEmpty() && result.bytes.size <= 1_000_000)
        val decoded = BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)
        assertEquals(result.width, decoded.width)
        assertEquals(result.height, decoded.height)
        decoded.recycle()
    }

    @Test fun rotationIsAppliedAndGpsMetadataIsNotCopied() = runBlocking {
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        val file = imageFile(bitmap)
        bitmap.recycle()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setLatLong(31.2, 121.5)
            saveAttributes()
        }
        val result = ImageCompressor(context).compress(Uri.fromFile(file))
        assertEquals(40, result.width)
        assertEquals(80, result.height)
        val metadata = ExifInterface(ByteArrayInputStream(result.bytes))
        assertFalse(metadata.getLatLong(FloatArray(2)))
        assertEquals(ExifInterface.ORIENTATION_UNDEFINED,
            metadata.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
    }

    private fun imageFile(bitmap: Bitmap): File = File.createTempFile("taxray-synthetic-test-", ".jpg", context.cacheDir).also { file ->
        generated += file
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 99, it)) }
    }
}
