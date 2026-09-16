package io.github.taxray.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.taxray.core.DraftItem

private val ScanDialogProperties = DialogProperties(
    dismissOnBackPress = false,
    dismissOnClickOutside = false,
)

/** groupIndex is zero based. Merely selecting a row never removes any draft item. */
@Composable
fun DuplicateItemsDialog(
    items: List<DraftItem>,
    groupIndex: Int,
    groupCount: Int,
    onKeepOne: (String) -> Unit,
    onKeepAll: () -> Unit,
) {
    val itemIds = items.map { it.id }
    var selectedId by rememberSaveable(groupIndex, itemIds) { mutableStateOf(items.firstOrNull()?.id) }
    AlertDialog(
        onDismissRequest = {},
        properties = ScanDialogProperties,
        title = { Text("发现相似商品") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("duplicateScanItems"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("第 ${groupIndex + 1} 组  共 $groupCount 组",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("可能重复拍到了同一项\n如果实际买了多件 可以全部保留",
                    style = MaterialTheme.typography.bodyMedium)
                Text("只留一项时 选择要保留的商品",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                .selectable(selected = selectedId == item.id, role = Role.RadioButton,
                                    onClick = { selectedId = item.id })
                                .testTag("duplicateScanItem-${item.id}")
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected = selectedId == item.id, onClick = null)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.name.ifBlank { "消费品目" }, style = MaterialTheme.typography.titleSmall)
                                Text("实付 ¥${item.amount}  税率 ${item.ratePercent}%",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onKeepAll, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("keepAllScanItems")) { Text("全部保留") }
                OutlinedButton(onClick = { selectedId?.let(onKeepOne) },
                    enabled = selectedId != null && selectedId in itemIds,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("keepOneScanItem")) { Text("删除重复项") }
            }
        },
    )
}

@Composable
fun ScanRecognitionFailureDialog(
    message: String,
    hasPrevious: Boolean,
    onRetry: () -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
) {
    var discarding by rememberSaveable { mutableStateOf(false) }
    if (discarding) {
        DiscardScanDialog(busy = false, onKeep = { discarding = false }, onDiscard = onDiscard)
        return
    }
    AlertDialog(
        onDismissRequest = {},
        properties = ScanDialogProperties,
        title = { Text("这次未能识别") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (hasPrevious) Text("前面识别的内容还在 可以重新选图或先核对",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("retryScanRound")) { Text("重新选图") }
                if (hasPrevious) OutlinedButton(onClick = onFinish,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reviewPreviousScanRounds")) { Text("核对已有内容") }
                TextButton(onClick = { discarding = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("放弃本次录入") }
            }
        },
    )
}

@Composable
private fun DiscardScanDialog(busy: Boolean, onKeep: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = ScanDialogProperties,
        title = { Text("放弃本次录入？") },
        text = { Text("未入账的识别草稿将被放弃\n已经确认入账的账单会保留") },
        confirmButton = {
            TextButton(onClick = onDiscard, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("确认放弃") }
        },
        dismissButton = {
            TextButton(onClick = onKeep, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("继续录入") }
        },
    )
}
