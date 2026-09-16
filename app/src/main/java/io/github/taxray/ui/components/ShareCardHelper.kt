package io.github.taxray.ui.components

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ShareCardHelper(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    /** Android 26–28 uses the caller's ACTION_CREATE_DOCUMENT flow with saveToUri. */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun saveToGallery(bitmap: Bitmap): Uri = withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "此系统版本请使用文件保存选项" }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "TaxyRay-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/TaxyRay")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建相册图片")
        try {
            resolver.openOutputStream(uri, "w")?.use { writePng(bitmap, it) }
                ?: throw IOException("无法写入相册图片")
            ensureActive()
            val published = resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            if (published != 1) throw IOException("相册图片保存未完成")
            uri
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                try { resolver.delete(uri, null, null) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            }
            throw error
        }
    }

    suspend fun saveToUri(bitmap: Bitmap, uri: Uri) = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请选择系统文件选择器提供的保存位置" }
        resolver.openOutputStream(uri, "wt")?.use { writePng(bitmap, it) }
            ?: throw IOException("无法写入所选文件")
    }

    /** Opens the chooser only. A recipient and actual sending are always the user's actions. */
    suspend fun share(bitmap: Bitmap) {
        var preparedFile: File? = null
        try {
            val file = withContext(Dispatchers.IO) {
                val destination = File.createTempFile("TaxyRay-", ".png", cacheDirectory("share"))
                preparedFile = destination
                destination.outputStream().use { writePng(bitmap, it) }
                destination
            }
            val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", file)
            withContext(Dispatchers.Main.immediate) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("TaxyRay 消费税额估算卡", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, "分享消费税额估算卡").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newRawUri("TaxyRay 消费税额估算卡", uri)
                }
                appContext.startActivity(chooser)
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { preparedFile?.delete() }
            throw error
        }
    }

    private fun cacheDirectory(name: String): File {
        val directory = File(appContext.cacheDir, name)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建临时图片目录")
        return directory
    }

    private fun writePng(bitmap: Bitmap, output: OutputStream) {
        check(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) { "图片尚未准备完成" }
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("图片编码失败")
        output.flush()
    }
}
