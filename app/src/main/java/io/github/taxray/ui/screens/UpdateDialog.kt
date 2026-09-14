package io.github.taxray.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.taxray.data.update.*
import java.util.Locale

@Composable
fun UpdateDialog(onDismiss: () -> Unit) {
    val vm: UpdateViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.permissionReturned() }
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) { Lifecycle.Event.ON_RESUME -> vm.resumed(); Lifecycle.Event.ON_PAUSE -> vm.paused(); else -> Unit }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) vm.resumed()
        onDispose { lifecycle.removeObserver(observer); vm.paused() }
    }
    LaunchedEffect(vm) {
        if (vm.state.value.phase in setOf(UpdatePhase.IDLE, UpdatePhase.CURRENT, UpdatePhase.ERROR)) vm.check()
    }
    fun close() { vm.dismiss(); onDismiss() }
    AlertDialog(onDismissRequest = { if (state.phase != UpdatePhase.INSTALLING) close() },
        title = { Text("应用更新") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("当前 ${vm.currentVersionName}")
                Text(state.message, color = if (state.phase == UpdatePhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.phase == UpdatePhase.CHECKING) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.update?.let { update ->
                    HorizontalDivider()
                    Text("稳定版 ${update.manifest.versionName}", style = MaterialTheme.typography.titleMedium)
                    Text("${String.format(Locale.ROOT, "%.1f", update.manifest.apkSize / 1_000_000.0)} MB · ${update.publishedAt.take(10)}", style = MaterialTheme.typography.bodySmall)
                    if (update.notes.isNotBlank()) Text(update.notes, style = MaterialTheme.typography.bodySmall)
                    if (state.phase == UpdatePhase.DOWNLOADING) {
                        LinearProgressIndicator(progress = { (state.downloaded.toDouble() / update.manifest.apkSize).toFloat() }, modifier = Modifier.fillMaxWidth())
                        Text("${(100 * state.downloaded / update.manifest.apkSize).coerceIn(0, 100)}%")
                    }
                }
                Text("仅在你操作后连接 GitHub\n不发送账本或 AI 密钥\n安装由 Android 系统确认", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            when (state.phase) {
                UpdatePhase.IDLE, UpdatePhase.CURRENT, UpdatePhase.ERROR -> TextButton(onClick = vm::check) { Text("检查更新") }
                UpdatePhase.AVAILABLE -> TextButton(onClick = vm::download) { Text("下载更新") }
                UpdatePhase.READY -> TextButton(onClick = {
                    if (vm.requiresPermission()) { vm.awaitPermission(); runCatching { permission.launch(vm.permissionIntent()) }.onFailure { vm.permissionLaunchFailed() } } else vm.install()
                }) { Text("安装更新") }
                UpdatePhase.INSTALLING -> TextButton(onClick = vm::install) { Text("重试系统安装") }
                UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING -> TextButton(onClick = vm::cancel) { Text("取消") }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = ::close) { Text("关闭") } },
    )
}
