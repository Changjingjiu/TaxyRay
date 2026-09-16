package io.github.taxray.data.remote

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraCaptureStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = CameraCaptureStore(context)
    private val resolver = context.contentResolver

    @Test fun createsUniqueCapturesAndDiscardOnlyRemovesTheRequestedOne() = runBlocking {
        val owned = mutableListOf<Uri>()
        try {
            val first = store.create().also(owned::add)
            val second = store.create().also(owned::add)
            assertNotEquals(first, second)
            assertEquals("content", first.scheme)
            assertEquals("${context.packageName}.files", first.authority)
            assertEquals("camera_capture", first.pathSegments.first())
            resolver.openOutputStream(first)!!.use { it.write(byteArrayOf(1, 2, 3)) }
            resolver.openOutputStream(second)!!.use { it.write(byteArrayOf(4, 5, 6)) }

            store.discard(listOf(first, first))
            assertTrue(runCatching { resolver.openInputStream(first)?.use { it.read() } }.isFailure)
            assertArrayEquals(byteArrayOf(4, 5, 6), resolver.openInputStream(second)!!.use { it.readBytes() })
            store.discard(listOf(first)) // Already removed captures are safe to discard again.
            assertArrayEquals(byteArrayOf(4, 5, 6), resolver.openInputStream(second)!!.use { it.readBytes() })
        } finally {
            store.discard(owned)
        }
    }

    @Test fun discardIgnoresOriginalsSharedCardsForeignAuthoritiesAndPathEscapes() = runBlocking {
        val marker = UUID.randomUUID().toString()
        val original = File(context.cacheDir, "original-$marker.jpg").apply { writeText("original fixture") }
        val shareDirectory = File(context.cacheDir, "share").apply { mkdirs() }
        val shared = File(shareDirectory, "capture-$marker.jpg").apply { writeText("shared fixture") }
        var camera: Uri? = null
        try {
            val capture = store.create().also { camera = it }
            resolver.openOutputStream(capture)!!.use { it.write(byteArrayOf(7, 8, 9)) }
            val sharedUri = FileProvider.getUriForFile(context, "${context.packageName}.files", shared)
            val traversalUri = Uri.Builder().scheme("content").authority("${context.packageName}.files")
                .appendPath("camera_capture").appendPath("../share/${shared.name}").build()
            val foreign = capture.buildUpon().authority("com.android.providers.media.documents").build()

            store.discard(listOf(Uri.fromFile(original), sharedUri, traversalUri, foreign))
            assertEquals("original fixture", original.readText())
            assertEquals("shared fixture", shared.readText())
            assertArrayEquals(byteArrayOf(7, 8, 9), resolver.openInputStream(capture)!!.use { it.readBytes() })
        } finally {
            store.discard(listOfNotNull(camera))
            original.delete()
            shared.delete()
        }
    }
}
