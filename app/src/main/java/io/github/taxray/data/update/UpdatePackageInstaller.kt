package io.github.taxray.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

data class InstallEvent(val sessionId: Int, val status: Int, val message: String)

class UpdatePackageInstaller(private val context: Context) {
    private val platform get() = context.packageManager.packageInstaller
    val events = callbackFlow {
        val prefs = preferences(context)
        fun publish() { trySend(InstallEvent(prefs.getInt("session", -1), prefs.getInt("status", IDLE), prefs.getString("message", "").orEmpty())) }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> publish() }
        prefs.registerOnSharedPreferenceChangeListener(listener); publish()
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun currentEvent(): InstallEvent = preferences(context).let {
        InstallEvent(it.getInt("session", -1), it.getInt("status", IDLE), it.getString("message", "").orEmpty())
    }
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()
    fun permissionIntent(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    suspend fun install(file: File, manifest: UpdateManifest): Int = withContext(Dispatchers.IO) {
        require(canInstall()) { "尚未允许此应用安装更新" }
        ApkUpdateVerifier(context).verify(file, manifest)
        val old = preferences(context).getInt("session", -1)
        if (old >= 0) runCatching { platform.abandonSession(old) }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = platform.createSession(params)
        preferences(context).edit().putLong("targetVersion", manifest.versionCode).putInt("session", id)
            .putInt("status", SUBMITTED).putString("message", "正在准备系统安装确认").commit()
        try {
            platform.openSession(id).use { session ->
                file.inputStream().use { input -> session.openWrite("base.apk", 0, file.length()).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    session.fsync(output)
                } }
                currentCoroutineContext().ensureActive()
                record(context, id, SUBMITTED, "正在请求 Android 系统安装确认")
                val result = Intent(context, UpdateInstallReceiver::class.java).setAction("${context.packageName}.UPDATE_INSTALL_RESULT")
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val sender = PendingIntent.getBroadcast(context, id, result, flags).intentSender
                session.commit(sender)
            }
            id
        } catch (error: Exception) {
            runCatching { platform.abandonSession(id) }
            record(context, id, PackageInstaller.STATUS_FAILURE, "安装会话失败 请重试")
            throw error
        }
    }

    fun launchPendingConfirmation(): Boolean {
        val intent = pendingConfirmation ?: return false
        pendingConfirmation = null
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    companion object {
        const val IDLE = -100
        const val SUBMITTED = -101
        @Volatile var foreground = false
        @Volatile internal var pendingConfirmation: Intent? = null
        internal fun preferences(context: Context): SharedPreferences = context.getSharedPreferences("update_install_status", Context.MODE_PRIVATE)
        internal fun record(context: Context, id: Int, status: Int, message: String) {
            preferences(context).edit().putInt("session", id).putInt("status", status).putString("message", message).commit()
        }
    }
}

/** Explicit, non-exported receiver. Only our mutable PendingIntent is handed to the OS installer. */
class UpdateInstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "${context.packageName}.UPDATE_INSTALL_RESULT") return
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (id < 0 || id != UpdatePackageInstaller.preferences(context).getInt("session", -1)) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            if (confirmation == null) {
                UpdatePackageInstaller.record(context, id, PackageInstaller.STATUS_FAILURE, "系统未提供安装确认 请重试")
                return
            }
            UpdatePackageInstaller.pendingConfirmation = confirmation
            UpdatePackageInstaller.record(context, id, status, "等待 Android 系统确认 若页面没有打开 请返回后重试安装")
            if (UpdatePackageInstaller.foreground) runCatching { UpdatePackageInstaller(context).launchPendingConfirmation() }
                .onFailure { UpdatePackageInstaller.record(context, id, PackageInstaller.STATUS_FAILURE, "无法打开系统安装确认 请重试") }
        } else {
            UpdatePackageInstaller.pendingConfirmation = null
            val message = when (status) {
                PackageInstaller.STATUS_SUCCESS -> "更新安装成功 请重新打开应用"
                PackageInstaller.STATUS_FAILURE_ABORTED -> "已取消系统安装 当前版本与账本保持不变"
                PackageInstaller.STATUS_FAILURE_STORAGE -> "存储空间不足 系统未安装更新"
                PackageInstaller.STATUS_FAILURE_CONFLICT -> "签名或安装身份冲突 系统未安装更新"
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "设备不兼容 系统未安装更新"
                PackageInstaller.STATUS_FAILURE_BLOCKED -> "系统策略阻止了安装"
                else -> "系统安装失败 请重新下载或稍后重试"
            }
            UpdatePackageInstaller.record(context, id, status, message)
        }
    }
}
