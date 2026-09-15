package io.github.taxray.data.update

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** No cookies, authentication, interceptors, BYOK settings or automatic redirects. */
class ReleaseUpdateService(private val client: OkHttpClient = newClient()) {
    suspend fun check(): AvailableUpdate = withTimeout(45_000L) {
        val release = withResponse(UpdateSource.latestUrl, api = true) { response ->
            requireSuccess(response)
            UpdateMetadata.release(readText(response, UpdateSource.MAX_RELEASE_BYTES))
        }
        val metadata = release.assets.singleOrNull { it.name == "update.json" }
            ?: error("最新稳定版尚未提供 update.json 无法在线更新")
        require(metadata.size in 1..UpdateSource.MAX_MANIFEST_BYTES.toLong()) { "更新清单大小不合法" }
        val manifest = assetResponse(metadata.url.toHttpUrl()) { response ->
            requireSuccess(response)
            val text = readText(response, UpdateSource.MAX_MANIFEST_BYTES)
            require(text.toByteArray(Charsets.UTF_8).size.toLong() == metadata.size) { "更新清单下载不完整" }
            UpdateMetadata.manifest(text)
        }
        UpdateMetadata.combine(release, manifest)
    }

    suspend fun download(update: AvailableUpdate, directory: File, onProgress: (Long, Long) -> Unit): File {
        UpdateUrlPolicy.asset(update.apkUrl, update.tag, update.manifest.apkAssetName)
        require(update.manifest.apkSize in 1..UpdateSource.MAX_APK_BYTES) { "APK 超出下载限额" }
        return withTimeout(10 * 60 * 1000L) {
            withContext(Dispatchers.IO) {
                check(directory.isDirectory || directory.mkdirs()) { "无法创建更新暂存目录" }
                val partial = File.createTempFile("download-${update.manifest.versionCode}-", ".part", directory)
                val ready = File(directory, "update-${update.manifest.versionCode}.apk")
                ready.delete()
                try {
                    assetResponse(update.apkUrl.toHttpUrl()) { response ->
                        requireSuccess(response)
                        val body = requireNotNull(response.body) { "APK 下载没有内容" }
                        val length = body.contentLength()
                        require(length == -1L || length == update.manifest.apkSize) { "APK 响应大小与清单不一致" }
                        val hash = MessageDigest.getInstance("SHA-256")
                        var count = 0L
                        body.byteStream().use { input -> partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                count += read
                                require(count <= update.manifest.apkSize) { "APK 下载超过声明大小" }
                                hash.update(buffer, 0, read); output.write(buffer, 0, read)
                                onProgress(count, update.manifest.apkSize)
                            }
                            output.fd.sync()
                        } }
                        require(count == update.manifest.apkSize) { "APK 下载不完整" }
                        require(hash.digest().hex() == update.manifest.sha256) { "APK SHA-256 校验失败 已删除下载" }
                    }
                    currentCoroutineContext().ensureActive()
                    check(partial.renameTo(ready)) { "无法保存已校验更新包" }
                    ready
                } finally { partial.delete() }
            }
        }
    }

    private suspend fun <T> assetResponse(original: HttpUrl, consume: suspend (Response) -> T): T {
        var current = original
        repeat(4) { index ->
            var redirect: HttpUrl? = null
            var result: T? = null
            var completed = false
            withResponse(current, api = false) { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    require(index < 3) { "更新下载重定向次数过多" }
                    val target = response.header("Location")?.let { current.resolve(it) } ?: error("更新下载重定向缺少地址")
                    redirect = UpdateUrlPolicy.redirect(original, target)
                } else { result = consume(response); completed = true }
            }
            if (completed) { @Suppress("UNCHECKED_CAST") return result as T }
            current = requireNotNull(redirect)
        }
        error("更新下载重定向失败")
    }

    private suspend fun <T> withResponse(url: HttpUrl, api: Boolean, consume: suspend (Response) -> T): T = coroutineScope {
        val request = Request.Builder().url(url).header("User-Agent", "TaxyRay-Android-Updater")
            .header("Accept", if (api) "application/vnd.github+json" else "application/octet-stream")
            .apply { if (api) header("X-GitHub-Api-Version", UpdateSource.API_VERSION) }.build()
        val call = client.newCall(request)
        if (api) call.timeout().timeout(30, TimeUnit.SECONDS)
        // Keep cancellation attached during body streaming, not just until response headers arrive.
        val cancellation = launch(Dispatchers.Unconfined) { try { awaitCancellation() } finally { call.cancel() } }
        try { withContext(Dispatchers.IO) { call.execute().use { consume(it) } } }
        catch (error: IOException) { currentCoroutineContext().ensureActive(); throw IOException("GitHub 连接或下载中断 请检查网络后重试", error) }
        finally { cancellation.cancel() }
    }

    private fun requireSuccess(response: Response) {
        when (response.code) {
            200 -> Unit
            404 -> error("仓库尚未提供可用的稳定发行资源")
            403, 429 -> error("GitHub 限流或暂时拒绝访问 请稍后手动重试")
            else -> error("GitHub 更新请求失败（HTTP ${response.code}）")
        }
    }
    private fun readText(response: Response, maximum: Int): String {
        val body = requireNotNull(response.body) { "更新响应为空" }
        require(body.contentLength() <= maximum) { "更新元数据超过大小限制" }
        val output = java.io.ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maximum) { "更新元数据超过大小限制" }
                output.write(buffer, 0, count)
            }
        }
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
    }
    companion object {
        fun newClient(): OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).build()
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
