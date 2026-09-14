package io.github.taxray.data.update

/** Android extracts identity from the real APK; this policy also has deterministic JVM coverage. */
internal data class ApkIdentity(val packageName: String, val versionCode: Long, val versionName: String, val minSdk: Int, val signers: Set<String>)

internal object UpdateIdentityPolicy {
    fun validate(archive: ApkIdentity, installed: ApkIdentity, manifest: UpdateManifest, sdk: Int) {
        require(archive.packageName == installed.packageName && archive.packageName == manifest.packageName) { "APK 包名与已安装应用不一致" }
        require(archive.versionCode == manifest.versionCode && archive.versionName == manifest.versionName) { "APK 版本与更新清单不一致" }
        require(archive.versionCode > installed.versionCode) { "APK 不是更高版本 禁止降级或重复安装" }
        require(archive.minSdk == manifest.minSdk && archive.minSdk <= sdk) { "APK 最低系统版本不匹配或当前设备不支持" }
        require(archive.signers.isNotEmpty() && archive.signers == installed.signers) { "APK 签名与当前已安装应用不同 已阻止安装" }
    }
}
