@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.taxray.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.taxray.ScanBillOutcome
import io.github.taxray.ScannedBill
import io.github.taxray.data.remote.PaymentStatus
import io.github.taxray.ui.components.DashedDivider
import io.github.taxray.ui.components.InformationNote

/** A collection of independent drafts, never an aggregated transaction. */
@Composable
fun BillBatchReviewSheet(
    bills: List<ScannedBill>, warnings: List<String>, busy: Boolean,
    onReview: (String) -> Unit, onSkip: (String) -> Unit, onRestore: (String) -> Unit,
    onSupplement: (String) -> Unit, onAddImages: () -> Unit, onFinish: () -> Unit,
    onBatchReview: () -> Unit = {},
) {
    var confirmClose by rememberSaveable { mutableStateOf(false) }
    val pending = bills.count { it.outcome == ScanBillOutcome.PENDING }
    fun close() { if (!busy) { if (pending > 0) confirmClose = true else onFinish() } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { target -> if (target == SheetValue.Hidden) { close(); false } else true })
    ModalBottomSheet(onDismissRequest = ::close, sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false)) {
        BackHandler(onBack = ::close)
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).testTag("billBatchReview")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("识别到 ${bills.size} 笔账单", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = ::close, enabled = !busy) { Icon(Icons.Outlined.Close, "关闭账单复核") }
            }
            Text("待核对 $pending 笔  已入账 ${bills.count { it.outcome == ScanBillOutcome.SAVED }} 笔  已跳过 ${bills.count { it.outcome == ScanBillOutcome.SKIPPED }} 笔",
                Modifier.padding(horizontal = 22.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (pending > 0) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("快速核对", style = MaterialTheme.typography.titleSmall)
                            Text("自动核对已付款、金额明确的账单",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(
                            onClick = onBatchReview,
                            enabled = !busy,
                            modifier = Modifier.testTag("batchReviewButton")
                        ) {
                            Text("一键核对")
                        }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).testTag("billBatchList"), contentPadding = PaddingValues(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { Text("订单分别核对和入账 未付款的订单可跳过",
                    style = MaterialTheme.typography.bodyMedium) }
                if (warnings.isNotEmpty()) item { InformationNote(warnings.joinToString("\n")) }
                itemsIndexed(bills, key = { _, bill -> bill.id }) { index, bill ->
                    val draft = bill.draft
                    Column(Modifier.fillMaxWidth().testTag("scanBill-${bill.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${index + 1}. ${draft.storeName.ifBlank { "商户未识别" }}", style = MaterialTheme.typography.titleMedium)
                        Text(draft.items.joinToString("、") { it.name.ifBlank { "消费品目" } }, maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                        val status = when (bill.outcome) {
                            ScanBillOutcome.SAVED -> "已入账"
                            ScanBillOutcome.SKIPPED -> "已跳过"
                            ScanBillOutcome.PENDING -> when (draft.paymentStatus) {
                                PaymentStatus.PAID -> "已付款 待核对"
                                PaymentStatus.UNPAID -> "未付款"
                                else -> "付款待确认"
                            }
                        }
                        Text("$status · ${draft.items.size} 项 · 实付 ${draft.declaredTotal.takeIf { it.isNotBlank() }?.let { "¥$it" } ?: "待确认"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (draft.paymentStatus == PaymentStatus.UNPAID && bill.outcome == ScanBillOutcome.PENDING)
                                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        draft.paymentEvidence?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        when (bill.outcome) {
                            ScanBillOutcome.PENDING -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onReview(bill.id) }, enabled = !busy,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("reviewBill-${bill.id}")) { Text("核对账单") }
                                TextButton(onClick = { onSkip(bill.id) }, enabled = !busy,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("skipBill-${bill.id}")) { Text("跳过") }
                                if (bill.canSupplement) TextButton(onClick = { onSupplement(bill.id) }, enabled = !busy,
                                    modifier = Modifier.heightIn(min = 48.dp)) { Text("补拍这笔") }
                            }
                            ScanBillOutcome.SKIPPED -> TextButton(onClick = { onRestore(bill.id) }, enabled = !busy,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("恢复核对") }
                            ScanBillOutcome.SAVED -> Unit
                        }
                        DashedDivider()
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onAddImages, enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("addBillImages")) { Text("继续选图") }
                Button(onClick = ::close, enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("finishBillBatch")) { Text("完成") }
            }
        }
    }
    if (confirmClose) AlertDialog(
        onDismissRequest = { confirmClose = false },
        title = { Text("账单未核对") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("还有 $pending 笔账单尚未核对。是否一键核对账单？")
                Text(
                    "已确认入账的账单会自动保留，直接结束将放弃未入账的草稿。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Button(
                    onClick = {
                        confirmClose = false
                        onBatchReview()
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("confirmCloseBatchReview")
                ) {
                    Text("一键核对账单")
                }
                OutlinedButton(
                    onClick = {
                        confirmClose = false
                        onFinish()
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("confirmCloseDiscard")
                ) {
                    Text("结束并放弃草稿", color = MaterialTheme.colorScheme.error)
                }
                TextButton(
                    onClick = { confirmClose = false },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("confirmCloseCancel")
                ) {
                    Text("继续核对")
                }
            }
        },
        dismissButton = null
    )
}
