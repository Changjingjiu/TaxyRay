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
import androidx.compose.ui.unit.dp
import io.github.taxray.core.*
import io.github.taxray.ui.components.*

@Composable
fun ReceiptDetailSheet(receipt: Receipt, busy: Boolean, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onShare: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val motionEnabled = rememberReceiptMotionEnabled()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.92f).receiptUnfold(motionEnabled), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(receipt.storeName.ifBlank { "日常消费" }, style = MaterialTheme.typography.titleLarge)
                        Text(dateText(receipt.timestamp, true), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭账单") }
                }
            }
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
                InformationNote("每项税额四舍五入到分后相加\n不含税金额由实付减去税额\n避免重复舍入产生一分差额")
                Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onEdit, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Edit, null); Text("编辑账单") }
                    TextButton(onClick = { confirmDelete = true }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("删除账单", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除这笔账单？") }, text = { Text("${receipt.storeName.ifBlank { "日常消费" }}\n实付 ${money(receipt.totalAmountCents)}\n删除后无法撤销 累计金额同步更新") }, confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("确认删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("保留账单") } })
}
