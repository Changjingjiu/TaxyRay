@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.github.taxray.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.taxray.core.Receipt
import io.github.taxray.core.TaxCalculator
import io.github.taxray.ui.components.*
import io.github.taxray.ui.theme.AmountStyle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun DashboardScreen(receipts: List<Receipt>, historyOnly: Boolean, busy: Boolean, loadError: String?, onAdd: () -> Unit, onScan: () -> Unit, onDetail: (Receipt) -> Unit, onAll: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var recentOnly by rememberSaveable { mutableStateOf(false) }
    val totals = remember(receipts) { receipts.sumOf { it.totalAmountCents } to receipts.sumOf { it.totalTaxCents } }
    val cutoff = LocalDate.now().minusDays(29).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val visible = remember(receipts, query, recentOnly, historyOnly) {
        if (!historyOnly) receipts.take(5) else receipts.filter { (!recentOnly || it.timestamp >= cutoff) && (query.isBlank() || it.displayStoreName().contains(query, true) || it.items.any { line -> line.name.contains(query, true) }) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (loadError != null) item { Text(loadError, color = MaterialTheme.colorScheme.error) }
        if (!historyOnly) {
            item {
                OutlinedCard(shape = RoundedCornerShape(14.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("累计增值税估算", style = MaterialTheme.typography.bodyMedium)
                            Text("CNY", style = AmountStyle.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RollingAmount(totals.second)
                        DashedDivider()
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("累计消费", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(money(totals.first), style = AmountStyle.copy(fontSize = 16.sp))
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("有效税额占比", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${TaxCalculator.effectiveRate(totals.second, totals.first)}%", style = AmountStyle.copy(fontSize = 16.sp))
                            }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onAdd, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(10.dp)) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(19.dp)); Spacer(Modifier.width(6.dp)); Text("记一笔")
                    }
                    OutlinedButton(onClick = onScan, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(10.dp)) {
                        Icon(Icons.Outlined.DocumentScanner, null, Modifier.size(19.dp)); Spacer(Modifier.width(6.dp)); Text("识别小票")
                    }
                }
            }
            if (receipts.isNotEmpty()) item { TrendChart(receipts) }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("最近账单", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onAll) { Text("全部 ${receipts.size} 笔"); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(16.dp)) }
                }
            }
        } else {
            item {
                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("搜索商户或商品") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(selected = !recentOnly, onClick = { recentOnly = false }, label = { Text("全部记录") })
                    FilterChip(selected = recentOnly, onClick = { recentOnly = true }, label = { Text("近 30 天") })
                }
                Text("共 ${visible.size} 笔 · 按消费时间倒序", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (visible.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.ReceiptLong, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                if (receipts.isNotEmpty()) {
                    Text("没有匹配的账单", style = MaterialTheme.typography.titleMedium)
                    Text("试试其他关键词或时间范围", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                if (historyOnly && receipts.isEmpty()) TextButton(onClick = onAdd) { Text("添加第一笔账单") }
            }
        }
        items(visible, key = { it.id }) { receipt ->
            ReceiptSummary(receipt, { onDetail(receipt) }, Modifier.animateItem())
        }
        item {
            Text(
                "按所选税率估算价格中的增值税 不代表商户实际缴税额\n不作为报税或完税依据",
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 20.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ReceiptSummary(receipt: Receipt, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(receipt.displayStoreName(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${dateText(receipt.timestamp)}  ·  ${receipt.items.size} 项", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.ChevronRight, "查看账单明细", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DashedDivider()
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("实付 ${money(receipt.totalAmountCents)}", style = AmountStyle.copy(fontSize = 13.sp))
                Text("税额 ${money(receipt.totalTaxCents)}", style = AmountStyle.copy(fontSize = 13.sp), color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun Receipt.displayStoreName(): String = storeName.ifBlank { "日常消费" }

@Composable
private fun TrendChart(receipts: List<Receipt>) {
    val today = LocalDate.now()
    val values = remember(receipts, today) {
        val grouped = receipts.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).toLocalDate() }
        (29 downTo 0).map { grouped[today.minusDays(it.toLong())].orEmpty().sumOf { r -> r.totalAmountCents } }
    }
    val color = MaterialTheme.colorScheme.primary
    val baseline = MaterialTheme.colorScheme.outlineVariant
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("近 30 天消费", style = MaterialTheme.typography.bodyMedium)
            Text(money(values.sum()), style = AmountStyle.copy(fontSize = 14.sp))
        }
        Canvas(Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = "近30天消费总额${money(values.sum())}，按日展示消费金额。详细账单可在账本中查询。" }) {
            val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
            val step = size.width / 30
            drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            values.forEachIndexed { index, amount ->
                if (amount > 0) {
                    val height = (amount.toDouble() / max * (size.height - 2.dp.toPx())).toFloat().coerceAtLeast(2.dp.toPx())
                    drawRect(color.copy(alpha = if (index == 29) 1f else .55f), Offset(index * step + 1.dp.toPx(), size.height - height), Size((step - 3.dp.toPx()).coerceAtLeast(1f), height))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${today.minusDays(29).monthValue}/${today.minusDays(29).dayOfMonth}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("今天", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
