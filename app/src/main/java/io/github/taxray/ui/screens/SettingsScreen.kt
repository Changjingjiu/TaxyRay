package io.github.taxray.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import io.github.taxray.R
import io.github.taxray.BuildConfig
import io.github.taxray.data.remote.ApiEndpointPolicy
import io.github.taxray.data.security.ApiSettings
import io.github.taxray.ui.components.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(settings: ApiSettings, settingsError: String?, busy: Boolean, onSave: (ApiSettings) -> Unit, onTest: (ApiSettings) -> Unit, onReset: () -> Unit, onExport: (String) -> Unit, onImport: () -> Unit, onErase: () -> Unit, onPolicy: () -> Unit, onRepository: () -> Unit, onCheckUpdates: () -> Unit) {
    var baseUrl by remember(settings) { mutableStateOf(settings.baseUrl) }
    var appendChatCompletions by remember(settings) { mutableStateOf(settings.appendChatCompletions) }
    var model by remember(settings) { mutableStateOf(settings.modelName) }
    // Deliberately not rememberSaveable: never place an API key in Activity saved state.
    var key by remember(settings) { mutableStateOf(settings.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var eraseStep by remember { mutableIntStateOf(0) }
    var eraseText by remember { mutableStateOf("") }
    val value = ApiSettings(baseUrl.trim(), model.trim(), key.trim(), appendChatCompletions)
    val endpoint = remember(baseUrl, appendChatCompletions) {
        runCatching { ApiEndpointPolicy.resolveUrl(baseUrl, appendChatCompletions).toString() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("小票识别AI", style = MaterialTheme.typography.titleMedium)
            Text("推荐使用 DeepSeek V4.1 Flash 模型", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (settingsError != null) Text(settingsError, color = MaterialTheme.colorScheme.error)
            OutlinedTextField(baseUrl, { baseUrl = it.take(2048) }, label = { Text("API 地址") }, placeholder = { Text("https://api.deepseek.com") }, modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), isError = baseUrl.isNotBlank() && endpoint.isFailure)
            Row(Modifier.fillMaxWidth().toggleable(value = appendChatCompletions, enabled = !busy, role = Role.Switch,
                onValueChange = { appendChatCompletions = it }),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("自动追加 /chat/completions", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = appendChatCompletions, onCheckedChange = null, enabled = !busy)
            }
            Text(endpoint.fold({ "最终请求地址\n$it" }, { it.message ?: "请输入有效的 HTTPS 地址" }),
                style = MaterialTheme.typography.bodySmall,
                color = if (endpoint.isSuccess) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            OutlinedTextField(model, { model = it.take(200) }, label = { Text("模型名称") }, placeholder = { Text("deepseek-flash") }, modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true)
            OutlinedTextField(key, { key = it.take(8192) }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showKey) "隐藏密钥" else "显示密钥") }
            })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { showKey = false; onSave(value) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("加密保存") }
                OutlinedButton(onClick = { onTest(value) }, enabled = !busy && endpoint.isSuccess && model.isNotBlank() && key.isNotBlank(), modifier = Modifier.weight(1f)) { Text("测试连接") }
            }
            Text("测试仅发送简短文本 小票经你确认后才会发送\n调用按服务商规则计费", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { confirmReset = true }, enabled = !busy) { Text("清除 API 配置") }
        }
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("账本与备份", style = MaterialTheme.typography.titleMedium)
            Text("备份不含密钥或图片\n导入会校验金额 同 ID 内容冲突时整批停止", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { onExport("json") }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("导出 JSON") }
                OutlinedButton(onClick = { onExport("csv") }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("导出 CSV") }
            }
            OutlinedButton(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("导入 JSON / CSV 备份") }
            Text("备份为明文 保存到云盘时由所选应用同步", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("关于计算", style = MaterialTheme.typography.titleMedium)
            Text("税额 = 含税金额 × 税率 ÷（1 + 税率）\n逐项四舍五入到分 再汇总", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onPolicy) { Text("算法与税率口径") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                    Text("TaxyRay ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onRepository) {
                    Icon(painterResource(R.drawable.ic_github), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("TaxyRay")
                }
                TextButton(onClick = onCheckUpdates) { Text("检查更新") }
            }
        }
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("清空本机数据", style = MaterialTheme.typography.titleMedium)
            Text("删除所有账单 API 配置和临时图片\n已导出的备份与相册图片不受影响", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { eraseStep = 1 }, enabled = !busy, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.DeleteForever, null); Spacer(Modifier.width(8.dp)); Text("清空全部数据") }
        }
        Spacer(Modifier.height(12.dp))
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("清除 API 配置？") }, text = { Text("将删除本机密钥 服务地址 模型配置与其加密密钥\n账本不受影响") }, confirmButton = { TextButton(onClick = { confirmReset = false; key = ""; onReset() }) { Text("确认清除") } }, dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } })
    if (eraseStep == 1) AlertDialog(onDismissRequest = { eraseStep = 0 }, title = { Text("确定清空全部数据？") }, text = { Text("这会永久删除本机所有账单和 API 配置\n建议先导出备份") }, confirmButton = { TextButton(onClick = { eraseStep = 2; eraseText = "" }) { Text("继续") } }, dismissButton = { TextButton(onClick = { eraseStep = 0 }) { Text("取消") } })
    if (eraseStep == 2) AlertDialog(onDismissRequest = { eraseStep = 0 }, title = { Text("最后确认") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("输入 清空 以永久删除本机数据"); OutlinedTextField(eraseText, { eraseText = it }, singleLine = true, label = { Text("输入 清空") }) } }, confirmButton = { TextButton(onClick = { eraseStep = 0; key = ""; onErase() }, enabled = eraseText == "清空") { Text("永久清空", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { eraseStep = 0 }) { Text("取消") } })
}
