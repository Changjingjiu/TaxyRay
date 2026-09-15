package io.github.taxray.data.update

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class UpdateMetadataTest {
    @Test fun `complete manifest and same release assets form update`() {
        val result = UpdateMetadata.combine(UpdateMetadata.release(UpdateFixtures.release()), UpdateMetadata.manifest(UpdateFixtures.manifest()))
        assertEquals(4L, result.manifest.versionCode)
        assertEquals(UpdateFixtures.apkUrl, result.apkUrl)
    }
    @Test fun `strict metadata rejects missing unknown duplicate and wrong type fields`() {
        for (value in listOf(
            UpdateFixtures.manifest().replace("\"versionCode\":4", "\"versionCode\":\"4\""),
            UpdateFixtures.manifest().replace("\"versionCode\":4", "\"versionCode\":4.0"),
            UpdateFixtures.manifest().replace("\"versionCode\":4", "\"versionCode\":0"),
            UpdateFixtures.manifest().replace("\"versionCode\":4", "\"versionCode\":4,\"versionCode\":5"),
            UpdateFixtures.manifest().replace("\"versionCode\":4", "\"versionCode\":4,\"version\\u0043ode\":5"),
            UpdateFixtures.manifest().replace("\"versionName\":\"0.3.0\",", ""),
            UpdateFixtures.manifest().replace("\"versionCode\":4", "\"extra\":true,\"versionCode\":4"),
            UpdateFixtures.manifest().replace("io.github.taxray", "other.app"),
            UpdateFixtures.manifest(size = UpdateSource.MAX_APK_BYTES + 1),
            UpdateFixtures.manifest().replace("release.apk", "../release.apk"),
            UpdateFixtures.manifest(hash = "f".repeat(63)),
            " ".repeat(UpdateSource.MAX_MANIFEST_BYTES) + UpdateFixtures.manifest(),
            "[".repeat(20) + "0" + "]".repeat(20),
        )) assertThrows(Exception::class.java) { UpdateMetadata.manifest(value) }
    }
    @Test fun `only stable uploaded complete same repository releases are accepted`() {
        for (value in listOf(
            UpdateFixtures.release().replace("\"draft\":false", "\"draft\":true"),
            UpdateFixtures.release().replace("\"prerelease\":false", "\"prerelease\":true"),
            UpdateFixtures.release().replace("\"state\":\"uploaded\"", "\"state\":\"new\""),
            UpdateFixtures.release().replace("Changjingjiu/TaxyRay", "other/TaxyRay"),
            UpdateFixtures.release().replace("https://github.com", "http://github.com"),
        )) assertThrows(Exception::class.java) { UpdateMetadata.release(value) }
        val release = UpdateMetadata.release(UpdateFixtures.release())
        assertThrows(Exception::class.java) { UpdateMetadata.combine(release.copy(assets = emptyList()), UpdateMetadata.manifest(UpdateFixtures.manifest())) }
        assertThrows(Exception::class.java) { UpdateMetadata.combine(release, UpdateMetadata.manifest(UpdateFixtures.manifest(size = 6))) }
        assertThrows(Exception::class.java) { UpdateMetadata.combine(release, UpdateMetadata.manifest(UpdateFixtures.manifest(hash = "f".repeat(64)))) }
    }
    @Test fun `redirects accept signed GitHub CDN URLs but refuse other hosts credentials and downgrades`() {
        val original = UpdateFixtures.apkUrl.toHttpUrl()
        assertEquals("release-assets.githubusercontent.com", UpdateUrlPolicy.redirect(original, "https://release-assets.githubusercontent.com/assets/file?sig=synthetic".toHttpUrl()).host)
        for (target in listOf("http://release-assets.githubusercontent.com/file", "https://github.com/other/repo/file", "https://github.com.example.test/file", "https://user@objects.githubusercontent.com/file", "https://objects.githubusercontent.com:444/file", "https://objects.githubusercontent.com/file#fragment")) {
            assertThrows(Exception::class.java) { UpdateUrlPolicy.redirect(original, target.toHttpUrl()) }
        }
    }
    @Test fun `identity verification rejects downgrade wrong package changed manifest unsupported OS and different signer`() {
        val manifest = UpdateMetadata.manifest(UpdateFixtures.manifest())
        val installed = ApkIdentity("io.github.taxray", 3, "0.2.0", 26, setOf("trusted-signer"))
        val archive = installed.copy(versionCode = 4, versionName = "0.3.0")
        UpdateIdentityPolicy.validate(archive, installed, manifest, 35)
        for (bad in listOf(archive.copy(packageName = "other.app"), archive.copy(versionCode = 3), archive.copy(versionName = "wrong"), archive.copy(minSdk = 36), archive.copy(signers = emptySet()), archive.copy(signers = setOf("attacker")), archive.copy(signers = setOf("trusted-signer", "attacker")))) {
            assertThrows(Exception::class.java) { UpdateIdentityPolicy.validate(bad, installed, manifest, 35) }
        }
        assertThrows(Exception::class.java) { UpdateIdentityPolicy.validate(archive, installed, manifest, 25) }
        assertThrows(Exception::class.java) { UpdateIdentityPolicy.validate(archive, installed.copy(versionCode = 4), manifest, 35) }
    }
}

internal object UpdateFixtures {
    val bytes = "abcde".toByteArray()
    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    const val apkUrl = "https://github.com/Changjingjiu/TaxyRay/releases/download/v0.3.0/release.apk"
    const val metadataUrl = "https://github.com/Changjingjiu/TaxyRay/releases/download/v0.3.0/update.json"
    fun manifest(size: Long = bytes.size.toLong(), hash: String = this.hash) = """{"versionCode":4,"versionName":"0.3.0","packageName":"io.github.taxray","minSdk":26,"apkAssetName":"release.apk","apkSize":$size,"sha256":"$hash"}"""
    fun release() = """{"draft":false,"prerelease":false,"tag_name":"v0.3.0","published_at":"2026-09-15T00:00:00Z","body":"Synthetic release","assets":[{"state":"uploaded","name":"update.json","size":${manifest().toByteArray().size},"browser_download_url":"$metadataUrl"},{"state":"uploaded","name":"release.apk","size":${bytes.size},"browser_download_url":"$apkUrl","digest":"sha256:$hash"}]}"""
    fun update() = UpdateMetadata.combine(UpdateMetadata.release(release()), UpdateMetadata.manifest(manifest()))
}
