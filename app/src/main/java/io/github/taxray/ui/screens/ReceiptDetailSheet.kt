@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.taxray.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.taxray.core.*
import io.github.taxray.ui.components.*

@Composable
fun ReceiptDetailSheet(receipt: Receipt, busy: Boolean, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onShare: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val motionEnabled = rememberReceiptMotionEnabled()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).receiptUnfold(motionEnabled)) {
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, top = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(receipt.storeName.ifBlank { "日常消费" }, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(dateText(receipt.timestamp, true), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onEdit, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("editReceipt").semantics { contentDescription = "编辑账单" }) { Text("编辑") }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭账单") }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("receiptDetailItems"), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        DashedDivider()
                        AmountRow("消费实付", money(receipt.totalAmountCents))
                        AmountRow("不含税金额", money(receipt.totalPreTaxCents))
                        AmountRow("增值税估算", money(receipt.totalTaxCents), true)
                        AmountRow("有效税额占比", "${receipt.effectiveRatePercent}%")
                        DashedDivider()
                    }
                }
                item { Button(onClick = onShare, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.IosShare, null); Spacer(Modifier.width(8.dp)); Text("生成贡献卡") } }
                item { Text("逐项计算 · ${receipt.items.size} 项", style = MaterialTheme.typography.titleMedium) }
                itemsIndexed(receipt.items, key = { _, item -> item.id }) { index, item ->
                    Column(Modifier.receiptRowEntrance(index, motionEnabled), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        AmountRow("实付 / 税率", "${money(item.breakdown.amountCents)} / ${TaxCalculator.formatRate(item.breakdown.taxRateBps)}%")
                        AmountRow("不含税 / 税额", "${money(item.breakdown.preTaxCents)} / ${money(item.breakdown.taxCents)}")
                        val divisor = java.math.BigDecimal.ONE.add(java.math.BigDecimal(item.breakdown.taxRateBps).movePointLeft(4)).stripTrailingZeros().toPlainString()
                        Text("${money(item.breakdown.amountCents)} ÷ $divisor × ${TaxCalculator.formatRate(item.breakdown.taxRateBps)}% ≈ ${money(item.breakdown.taxCents)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (item.categoryReason.isNotBlank()) Text(item.categoryReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        DashedDivider()
                    }
                }
                item {
                    Text("税额逐项四舍五入到分后汇总\n不含税金额 = 实付 − 税额", modifier = Modifier.fillMaxWidth().testTag("receiptRoundingNote"), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { confirmDelete = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp)) { Text("删除账单", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除这笔账单？") }, text = { Text("${receipt.storeName.ifBlank { "日常消费" }}\n实付 ${money(receipt.totalAmountCents)}\n删除后无法撤销 累计金额同步更新") }, confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("确认删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("保留账单") } })
}
