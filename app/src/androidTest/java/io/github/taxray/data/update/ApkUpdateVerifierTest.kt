package io.github.taxray.data.update

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApkUpdateVerifierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun productionArchiveParserReadsInstalledApkAndRejectsRepeatedInstallation() {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val file = File(context.applicationInfo.sourceDir)
        val manifest = UpdateManifest(ApkUpdateVerifier.versionCode(info), info.versionName.orEmpty(), context.packageName,
            context.applicationInfo.minSdkVersion, "installed.apk", file.length(), sha256(file))
        val error = assertThrows(IllegalArgumentException::class.java) { ApkUpdateVerifier(context).verify(file, manifest) }
        assertTrue(error.message.orEmpty().contains("不是更高版本"))
        val changedHash = manifest.copy(sha256 = "0".repeat(64))
        assertTrue(assertThrows(IllegalArgumentException::class.java) { ApkUpdateVerifier(context).verify(file, changedHash) }.message.orEmpty().contains("SHA-256"))
    }

    @Test fun installerReceiverIsPrivateAndIgnoresUnrelatedSessions() {
        val receiverInfo = context.packageManager.getReceiverInfo(ComponentName(context, UpdateInstallReceiver::class.java), 0)
        assertFalse(receiverInfo.exported)
        val namespace = "updater-test-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("$namespace-$name", mode)
        }
        try {
            UpdatePackageInstaller.record(isolated, 1234, UpdatePackageInstaller.SUBMITTED, "Synthetic pending")
            val receiver = UpdateInstallReceiver()
            fun event(id: Int) = Intent("${isolated.packageName}.UPDATE_INSTALL_RESULT")
                .putExtra(PackageInstaller.EXTRA_SESSION_ID, id).putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED)
            receiver.onReceive(isolated, event(9999))
            assertEquals(UpdatePackageInstaller.SUBMITTED, UpdatePackageInstaller(isolated).currentEvent().status)
            receiver.onReceive(isolated, event(1234))
            assertEquals(PackageInstaller.STATUS_FAILURE_ABORTED, UpdatePackageInstaller(isolated).currentEvent().status)
            assertTrue(UpdatePackageInstaller(isolated).currentEvent().message.contains("已取消"))
        } finally { context.deleteSharedPreferences("$namespace-update_install_status") }
    }

    private fun sha256(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
