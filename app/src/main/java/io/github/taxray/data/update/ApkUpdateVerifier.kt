package io.github.taxray.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

class ApkUpdateVerifier(private val context: Context) {
    @Suppress("DEPRECATION")
    fun verify(file: File, manifest: UpdateManifest) {
        require(file.isFile && file.length() == manifest.apkSize) { "待安装 APK 已丢失或大小发生变化 请重新下载" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        require(digest.digest().hex() == manifest.sha256) { "待安装 APK 的 SHA-256 已变化 请重新下载" }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("文件不是可解析的 Android APK")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        UpdateIdentityPolicy.validate(
            ApkIdentity(archive.packageName, versionCode(archive), archive.versionName.orEmpty(),
                requireNotNull(archive.applicationInfo).minSdkVersion, signers(archive)),
            ApkIdentity(installed.packageName, versionCode(installed), installed.versionName.orEmpty(),
                requireNotNull(installed.applicationInfo).minSdkVersion, signers(installed)),
            manifest, Build.VERSION.SDK_INT,
        )
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
        ?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }?.toSet().orEmpty()

    companion object {
        @Suppress("DEPRECATION")
        fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }
}
