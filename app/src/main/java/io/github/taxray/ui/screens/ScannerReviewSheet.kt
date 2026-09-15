@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.taxray.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.taxray.ReceiptDraft
import io.github.taxray.core.DraftItem
import io.github.taxray.core.TaxBreakdown
import io.github.taxray.core.TaxCalculator
import io.github.taxray.ui.components.*
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
fun ScannerReviewSheet(draft: ReceiptDraft, busy: Boolean, onChange: (ReceiptDraft) -> Unit, onDismiss: () -> Unit, onSave: (Boolean) -> Unit) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var batchMode by remember { mutableStateOf(false) }
    var saveAttempted by remember(draft.items.firstOrNull()?.id) { mutableStateOf(false) }
    val motionEnabled = rememberReceiptMotionEnabled()
    val calculated = remember(draft) { runCatching { draft.calculated() }.getOrNull() }
    val error = draft.validationMessage()
    val allocated = draft.allocationIsCurrent()
    val paidTotal = runCatching { TaxCalculator.parseReceiptTotal(draft.declaredTotal) }.getOrNull()
    val displayPaidTotal = if (draft.declaredTotal.isBlank()) calculated?.sumOf { it.breakdown.amountCents } else paidTotal
    val currentBusy by rememberUpdatedState(busy)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden) {
                if (!currentBusy) confirmDiscard = true
                false
            } else true
        }
    )
    ModalBottomSheet(
        onDismissRequest = { if (!busy) confirmDiscard = true },
        sheetState = sheetState,
        // Material 3 1.3.1's default Back path animates hide() before consulting
        // confirmValueChange. Handle Back in the dialog to keep the draft visible.
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false)
    ) {
        BackHandler { if (!busy) confirmDiscard = true }
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).imePadding().receiptUnfold(motionEnabled)) {
            Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (draft.fromVision) "核对小票" else if (draft.id != null) "编辑账单" else "记一笔消费", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = { confirmDiscard = true }, enabled = !busy) { Icon(Icons.Outlined.Close, "关闭录入") }
                }
                Text(if (draft.fromVision) "AI 结果仅供参考 核对后入账" else "输入折后实付金额 逐项拆分价与税", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.weight(1f).clipToBounds(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (draft.warnings.isNotEmpty()) item {
                    InformationNote(draft.warnings.take(5).joinToString("\n"))
                }
                item {
                    OutlinedTextField(draft.storeName, { if (it.length <= 120) onChange(draft.copy(storeName = it)) }, enabled = !busy, label = { Text("商户名称 选填") }, modifier = Modifier.fillMaxWidth().testTag("storeName"), singleLine = true)
                    TextButton(onClick = { showDatePicker = true }, enabled = !busy, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Outlined.CalendarToday, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("消费时间 ${dateText(draft.timestamp, true)}")
                    }
                }
                item { ReceiptSettlementSection(draft, busy, onChange) }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("商品明细 · ${draft.items.size} 项", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        if (draft.items.size > 1) TextButton(onClick = { batchMode = !batchMode; selectedIds = emptySet() }, enabled = !busy) { Text(if (batchMode) "取消选择" else "批量选择") }
                    }
                }
                itemsIndexed(draft.items, key = { _, item -> item.id }) { index, item ->
                    Column(Modifier.animateItem().receiptRowEntrance(index, motionEnabled)) {
                        if (batchMode) Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(item.id in selectedIds, { selectedIds = if (it) selectedIds + item.id else selectedIds - item.id }, enabled = !busy)
                            Text("选择第 ${index + 1} 项", style = MaterialTheme.typography.bodySmall)
                        }
                        EditableReceiptItem(item, index, busy, motionEnabled,
                            allocatedAmount = if (allocated) calculated?.getOrNull(index)?.breakdown else null,
                            onChange = { value -> onChange(draft.copy(
                                items = draft.items.map { if (it.id == value.id) value else it },
                                appliedDiscount = if (item.amount == value.amount) draft.appliedDiscount else null,
                            )) },
                            onDelete = { onChange(draft.copy(items = draft.items.filterNot { it.id == item.id }, appliedDiscount = null)) })
                    }
                }
                item {
                    if (batchMode && selectedIds.isNotEmpty()) OutlinedButton(onClick = { onChange(draft.copy(items = draft.items.filterNot { it.id in selectedIds }, appliedDiscount = null)); selectedIds = emptySet(); batchMode = false }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.DeleteOutline, null); Text("删除选中的 ${selectedIds.size} 项")
                    }
                    OutlinedButton(onClick = { onChange(draft.copy(items = draft.items + DraftItem(), appliedDiscount = null)) }, enabled = !busy && draft.items.size < 1000, modifier = Modifier.fillMaxWidth().testTag("addItem")) { Icon(Icons.Outlined.Add, null); Text("添加商品") }
                }
                item { TaxRateGuide() }
            }
            Surface(tonalElevation = 1.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("实付 ${displayPaidTotal?.let(::money) ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                        Text("税额 ${calculated?.takeIf { error == null }?.sumOf { it.breakdown.taxCents }?.let(::money) ?: "—"}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                    }
                    if (error != null && (saveAttempted || draft.items.any { it.amount.isNotEmpty() })) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!draft.fromVision && draft.id == null) OutlinedButton(onClick = { saveAttempted = true; onSave(true) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("保存并继续") }
                        Button(onClick = { saveAttempted = true; onSave(false) }, enabled = !busy, modifier = Modifier.weight(1f).testTag("saveReceipt")) { Text(if (busy) "正在保存…" else "确认入账") }
                    }
                }
            }
        }
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("放弃这次修改？") },
        text = { Text(if (draft.id == null) "当前内容尚未入账\n放弃后不会保存" else "本次修改尚未保存\n放弃后保留原账单") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onDismiss() }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } }
    )
    if (showDatePicker) {
        val localDate = Instant.ofEpochMilli(draft.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = minOf(DatePickerDefaults.YearRange.first, localDate.year)..maxOf(DatePickerDefaults.YearRange.last, localDate.year),
        )
        DatePickerDialog(onDismissRequest = { showDatePicker = false }, confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { selected ->
                    val date = Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate()
                    val time = Instant.ofEpochMilli(draft.timestamp).atZone(ZoneId.systemDefault()).toLocalTime()
                    onChange(draft.copy(timestamp = date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))
                }
                showDatePicker = false
            }) { Text("确定") }
        }, dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }) { DatePicker(state = state) }
    }
}

@Composable
private fun EditableReceiptItem(item: DraftItem, index: Int, busy: Boolean, motionEnabled: Boolean, allocatedAmount: TaxBreakdown?, onChange: (DraftItem) -> Unit, onDelete: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    var customSelected by rememberSaveable(item.id) { mutableStateOf(item.ratePercent !in PresetTaxRates) }
    val amount = allocatedAmount ?: runCatching { TaxCalculator.calculate(item.amount, item.ratePercent) }.getOrNull()
    LaunchedEffect(item.id) { if (item.amount.isBlank()) focusRequester.requestFocus() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}".padStart(2, '0'), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(item.name, { if (it.length <= 200) onChange(item.copy(name = it)) }, enabled = !busy, label = { Text("商品名称 选填") }, singleLine = true, modifier = Modifier.weight(1f).testTag("itemName$index"))
            IconButton(onClick = onDelete, enabled = !busy) { Icon(Icons.Outlined.DeleteOutline, "删除第 ${index + 1} 项") }
        }
        OutlinedTextField(item.amount, { if (it.length <= 14) onChange(item.copy(amount = it)) }, enabled = !busy, label = { Text(if (allocatedAmount != null) "分摊前金额" else "商品金额") }, prefix = { Text("¥ ") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = item.amount.isNotEmpty() && runCatching { TaxCalculator.calculate(item.amount, "0") }.isFailure, modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("amount$index"))
        TaxRateSelector(item.ratePercent, customSelected, index, enabled = !busy, motionEnabled = motionEnabled,
            onPreset = { rate -> customSelected = false; onChange(item.copy(ratePercent = rate)) },
            onCustom = { customSelected = true; onChange(item.copy(ratePercent = "")) })
        if (customSelected) OutlinedTextField(item.ratePercent, { if (it.length <= 6) onChange(item.copy(ratePercent = it)) }, label = { Text("自定义税率 0–100%") }, suffix = { Text("%") }, singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("customRate$index"))
        if (item.categoryReason.isNotBlank()) Text(item.categoryReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (allocatedAmount != null) Text("分摊后实付 ${money(allocatedAmount.amountCents)}", modifier = Modifier.testTag("allocatedPaid$index"),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        amount?.let { Text("不含税 ${money(it.preTaxCents)}   ·   税额 ${money(it.taxCents)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        DashedDivider(Modifier.padding(top = 8.dp))
    }
}
