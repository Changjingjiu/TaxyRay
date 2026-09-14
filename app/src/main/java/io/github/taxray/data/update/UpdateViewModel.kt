package io.github.taxray.data.update

import android.app.Application
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

enum class UpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, WAITING_PERMISSION, INSTALLING, FINISHED, ERROR }
data class UpdateUiState(val phase: UpdatePhase = UpdatePhase.IDLE, val message: String = "仅连接 GitHub\n不发送账本或 API 配置", val update: AvailableUpdate? = null, val downloaded: Long = 0)

class UpdateViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val service = ReleaseUpdateService()
    private val installer = UpdatePackageInstaller(application)
    private val directory = File(application.cacheDir, "updates")
    private val preferences = UpdatePackageInstaller.preferences(application)
    private val mutable = MutableStateFlow(UpdateUiState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    val currentVersionName: String = application.packageManager.getPackageInfo(application.packageName, 0).versionName.orEmpty()
    private val currentVersionCode: Long = ApkUpdateVerifier.versionCode(application.packageManager.getPackageInfo(application.packageName, 0))

    init {
        val restored = (saved.get<String>("update") ?: preferences.getString("updateMetadata", null))?.let { runCatching { Json.decodeFromString<AvailableUpdate>(it) }.getOrNull() }
        if (restored != null) {
            mutable.value = checkedState(restored)
            if (mutable.value.phase == UpdatePhase.AVAILABLE && apkFile(restored).isFile) {
                mutable.value = UpdateUiState(UpdatePhase.READY, "已恢复暂存更新\n安装前会再次校验", restored)
            }
        }
        if (restored != null && restored.manifest.versionCode > currentVersionCode &&
            preferences.getLong("targetVersion", -1) == restored.manifest.versionCode) {
            saved["session"] = installer.currentEvent().sessionId
            applyInstallEvent(installer.currentEvent())
        }
        viewModelScope.launch { installer.events.collect(::applyInstallEvent) }
    }

    private fun checkedState(update: AvailableUpdate): UpdateUiState = when {
        update.manifest.versionCode == currentVersionCode -> UpdateUiState(UpdatePhase.CURRENT, "已是最新稳定版", update)
        update.manifest.versionCode < currentVersionCode -> UpdateUiState(UpdatePhase.CURRENT, "当前版本比最新稳定版更新\n无需降级", update)
        update.manifest.minSdk > Build.VERSION.SDK_INT -> UpdateUiState(UpdatePhase.ERROR, "当前 Android 系统版本不支持此更新", update)
        else -> UpdateUiState(UpdatePhase.AVAILABLE, "发现新的稳定版本", update)
    }

    fun check() = start {
        mutable.value = UpdateUiState(UpdatePhase.CHECKING, "正在检查 GitHub 稳定发行版…")
        val update = service.check()
        saved["update"] = Json.encodeToString(update)
        preferences.edit().putString("updateMetadata", Json.encodeToString(update)).apply()
        saved.remove<Int>("session")
        mutable.value = checkedState(update)
    }

    fun download() {
        val update = mutable.value.update ?: return
        if (update.manifest.versionCode <= currentVersionCode || update.manifest.minSdk > Build.VERSION.SDK_INT) return
        start {
            mutable.update { it.copy(phase = UpdatePhase.DOWNLOADING, downloaded = 0, message = "正在下载并核对更新包…") }
            val file = service.download(update, directory) { bytes, _ -> mutable.update { it.copy(downloaded = bytes) } }
            try { withContext(Dispatchers.IO) { ApkUpdateVerifier(getApplication()).verify(file, update.manifest) } }
            catch (error: Exception) { file.delete(); throw error }
            mutable.update { it.copy(phase = UpdatePhase.READY, message = "更新包校验通过\n可继续由系统确认安装") }
        }
    }

    fun requiresPermission(): Boolean = !installer.canInstall()
    fun permissionIntent() = installer.permissionIntent()
    fun awaitPermission() {
        saved["permissionRequested"] = true
        mutable.update { it.copy(phase = UpdatePhase.WAITING_PERMISSION, message = "请在系统设置中允许 TaxLens 安装更新\n返回后继续") }
    }
    fun permissionLaunchFailed() {
        saved["permissionRequested"] = false
        mutable.update { it.copy(phase = UpdatePhase.READY, message = "无法打开系统安装来源设置\n请在系统设置中允许 TaxLens 安装应用后重试") }
    }
    fun permissionReturned() {
        if (saved.get<Boolean>("permissionRequested") != true) return
        saved["permissionRequested"] = false
        if (installer.canInstall()) install() else mutable.update { it.copy(phase = UpdatePhase.READY, message = "未获得安装来源授权\n你可稍后再次点击安装") }
    }
    fun install() {
        val update = mutable.value.update ?: return
        if (requiresPermission()) { mutable.update { it.copy(phase = UpdatePhase.READY, message = "请先允许此应用安装更新") }; return }
        start {
            mutable.update { it.copy(phase = UpdatePhase.INSTALLING, message = "正在复核更新包并准备系统安装确认…") }
            val id = installer.install(apkFile(update), update.manifest)
            saved["session"] = id
            // A fast OS callback may arrive before commit returns and before this ID is saved.
            applyInstallEvent(installer.currentEvent())
        }
    }
    private fun applyInstallEvent(event: InstallEvent) {
        if (event.sessionId < 0 || event.sessionId != saved.get<Int>("session")) return
        val phase = when (event.status) {
            PackageInstaller.STATUS_SUCCESS -> UpdatePhase.FINISHED
            PackageInstaller.STATUS_PENDING_USER_ACTION, UpdatePackageInstaller.SUBMITTED -> UpdatePhase.INSTALLING
            UpdatePackageInstaller.IDLE -> return
            else -> UpdatePhase.READY
        }
        mutable.update { it.copy(phase = phase, message = event.message) }
    }
    fun resumed() {
        UpdatePackageInstaller.foreground = true
        runCatching { installer.launchPendingConfirmation() }.onFailure {
            mutable.update { it.copy(phase = UpdatePhase.READY, message = "安装确认已失效\n请重新点击安装") }
        }
        permissionReturned()
    }
    fun paused() { UpdatePackageInstaller.foreground = false }
    fun cancel() {
        operation?.cancel()
        // Retain the job until its finally blocks complete so a retry cannot race file cleanup.
        mutable.update { it.copy(phase = if (it.update == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE, message = "已取消\n当前应用和账本保持不变", downloaded = 0) }
    }
    fun dismiss() { if (mutable.value.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING)) cancel(); paused() }
    private fun apkFile(update: AvailableUpdate): File = File(directory, "update-${update.manifest.versionCode}.apk")
    private fun start(block: suspend () -> Unit) {
        if (operation?.isCompleted == false) return
        operation = viewModelScope.launch {
            try { block() }
            catch (error: TimeoutCancellationException) { mutable.update { it.copy(phase = UpdatePhase.ERROR, message = "更新请求超时\n请检查网络后重试") } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(phase = UpdatePhase.ERROR, message = error.message ?: "更新失败\n请重试") } }
        }
    }
}
