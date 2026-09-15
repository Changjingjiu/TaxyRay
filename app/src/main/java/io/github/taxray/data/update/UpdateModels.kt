package io.github.taxray.data.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

object UpdateSource {
    const val REPOSITORY = "Changjingjiu/TaxyRay"
    const val PROJECT_LABEL = "TaxyRay"
    const val API_VERSION = "2026-03-10"
    const val MAX_APK_BYTES = 128L * 1024 * 1024
    const val MAX_RELEASE_BYTES = 2_000_000
    const val MAX_MANIFEST_BYTES = 64_000
    val latestUrl = "https://api.github.com/repos/$REPOSITORY/releases/latest".toHttpUrl()
}

@Serializable
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val packageName: String,
    val minSdk: Int,
    val apkAssetName: String,
    val apkSize: Long,
    val sha256: String,
)

@Serializable
data class AvailableUpdate(val manifest: UpdateManifest, val apkUrl: String, val tag: String, val publishedAt: String, val notes: String)
data class ReleaseAsset(val name: String, val size: Long, val url: String, val digest: String?)
data class StableRelease(val tag: String, val publishedAt: String, val notes: String, val assets: List<ReleaseAsset>)

object UpdateMetadata {
    private val json = Json
    fun manifest(text: String): UpdateManifest {
        val obj = objectValue(text, UpdateSource.MAX_MANIFEST_BYTES)
        require(obj.keys == setOf("versionCode", "versionName", "packageName", "minSdk", "apkAssetName", "apkSize", "sha256")) { "更新清单字段不完整或格式不受支持" }
        val result = UpdateManifest(obj.integer("versionCode"), obj.string("versionName"), obj.string("packageName"),
            obj.integer("minSdk").also { require(it in 26..10_000) { "更新清单系统版本不合法" } }.toInt(),
            obj.string("apkAssetName"), obj.integer("apkSize"), obj.string("sha256"))
        require(result.versionCode in 1..Int.MAX_VALUE.toLong()) { "更新版本编号不合法" }
        require(result.versionName.isNotBlank() && result.versionName.length <= 80 && result.versionName.none { it.isISOControl() }) { "更新版本名称不合法" }
        require(result.packageName == "io.github.taxray") { "更新包属于其他应用" }
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}\\.apk").matches(result.apkAssetName)) { "APK 资源名称不合法" }
        require(result.apkSize in 1..UpdateSource.MAX_APK_BYTES) { "更新包超过 128 MiB 限额" }
        require(Regex("[a-f0-9]{64}").matches(result.sha256)) { "更新包 SHA-256 不合法" }
        return result
    }

    fun release(text: String): StableRelease {
        val obj = objectValue(text, UpdateSource.MAX_RELEASE_BYTES)
        require(obj["draft"] == JsonPrimitive(false) && obj["prerelease"] == JsonPrimitive(false)) { "该版本不是公开稳定版" }
        val tag = obj.string("tag_name")
        require(tag.isNotBlank() && tag.length <= 100 && tag.none { it.isISOControl() }) { "发行标签不合法" }
        val assets = obj["assets"] as? JsonArray ?: error("发行版缺少资源列表")
        require(assets.size <= 100) { "发行资源列表过大" }
        val parsed = assets.map { value ->
            val asset = value as? JsonObject ?: error("发行资源格式无效")
            require(asset.string("state") == "uploaded") { "发行资源尚未上传完成" }
            val name = asset.string("name")
            val url = asset.string("browser_download_url")
            UpdateUrlPolicy.asset(url, tag, name)
            ReleaseAsset(name, asset.integer("size"), url, (asset["digest"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
        }
        require(parsed.map { it.name }.distinct().size == parsed.size) { "发行资源名称重复" }
        return StableRelease(tag, obj.string("published_at"), (obj["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty().take(8_000), parsed)
    }

    fun combine(release: StableRelease, manifest: UpdateManifest): AvailableUpdate {
        val asset = release.assets.singleOrNull { it.name == manifest.apkAssetName } ?: error("发行版缺少清单指定的 APK")
        require(asset.size == manifest.apkSize) { "APK 资源大小与更新清单不一致" }
        asset.digest?.let { require(it == "sha256:${manifest.sha256}") { "GitHub 资源摘要与更新清单不一致" } }
        return AvailableUpdate(manifest, asset.url, release.tag, release.publishedAt, release.notes)
    }

    private fun objectValue(text: String, limit: Int): JsonObject {
        require(text.length <= limit && text.toByteArray(Charsets.UTF_8).size <= limit) { "更新元数据过大" }
        // Small fixed schemas do not need unbounded nesting. Detect duplicate object keys too.
        StrictUpdateJson.validate(text)
        return json.parseToJsonElement(text) as? JsonObject ?: error("更新元数据必须是 JSON 对象")
    }
    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: error("更新元数据缺少文本字段 $key")
    private fun JsonObject.integer(key: String): Long = (this[key] as? JsonPrimitive)?.takeIf { !it.isString && Regex("[0-9]+").matches(it.content) }?.longOrNull
        ?: error("更新元数据字段 $key 必须为非负整数")
}

object UpdateUrlPolicy {
    fun asset(value: String, tag: String, name: String): HttpUrl {
        val url = value.toHttpUrl()
        val expected = "https://github.com/${UpdateSource.REPOSITORY}/releases/download/".toHttpUrl().newBuilder()
            .addPathSegment(tag).addPathSegment(name).build()
        require(url == expected) { "更新资源不属于指定仓库和发行版" }
        return url
    }
    fun redirect(original: HttpUrl, target: HttpUrl): HttpUrl {
        require(target.scheme == "https" && target.port == 443 && target.username.isEmpty() && target.password.isEmpty() && target.fragment == null) { "更新下载重定向地址不安全" }
        require(target.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "更新下载重定向到不受信任的域名" }
        if (target.host == "github.com") require(target == original) { "更新下载重定向离开原发行资源" }
        return target
    }
}
