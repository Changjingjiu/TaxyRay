package io.github.taxray.data.remote

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Owns only app-created camera captures, never gallery originals or exported contribution cards. */
class CameraCaptureStore(context: Context) {
    private val appContext = context.applicationContext
    private val authority = "${appContext.packageName}.files"
    private val directory = File(appContext.cacheDir, "camera")

    suspend fun create(): Uri {
        var createdFile: File? = null
        try {
            return withContext(Dispatchers.IO) {
                if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                    throw IOException("无法创建临时图片目录")
                }
                val file = File.createTempFile("capture-", ".jpg", directory)
                createdFile = file
                FileProvider.getUriForFile(appContext, authority, file)
            }
        } catch (error: Throwable) {
            // Also handles cancellation during withContext's dispatch back to the caller.
            // The caller either receives the URI or the unclaimed temporary file is removed.
            withContext(NonCancellable + Dispatchers.IO) { createdFile?.delete() }
            throw error
        }
    }

    suspend fun discard(uris: List<Uri>) = withContext(Dispatchers.IO) {
        val cameraDirectory = directory.canonicalFile
        uris.distinct().forEach { uri ->
            if (uri.scheme != "content" || uri.authority != authority) return@forEach
            val segments = uri.pathSegments
            if (segments.size != 2 || segments.first() != "camera_capture") return@forEach
            val name = segments.last()
            if (!name.startsWith("capture-") || !name.endsWith(".jpg")) return@forEach
            val file = File(cameraDirectory, name).canonicalFile
            if (file.parentFile != cameraDirectory || !file.isFile) return@forEach
            if (!file.delete() && file.exists()) throw IOException("临时照片清理失败 请重试")
        }
    }
}
