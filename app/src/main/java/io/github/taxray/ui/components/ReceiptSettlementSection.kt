package io.github.taxray.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.taxray.ReceiptDraft
import io.github.taxray.data.remote.PaymentStatus
import io.github.taxray.core.TaxCalculator

@Composable
fun ReceiptSettlementSection(draft: ReceiptDraft, busy: Boolean, onChange: (ReceiptDraft) -> Unit) {
    var showTotal by rememberSaveable { mutableStateOf(draft.fromVision || draft.declaredTotal.isNotBlank()) }
    val source = remember(draft.items) { runCatching { TaxCalculator.calculateItems(draft.items) }.getOrNull() }
    val original = source?.sumOf { it.breakdown.amountCents }
    val paid = runCatching { TaxCalculator.parseReceiptTotal(draft.declaredTotal) }.getOrNull()
    val difference = if (original != null && paid != null) original - paid else null
    val applied = draft.allocationIsCurrent()
    val paymentReady = !draft.fromVision || draft.paymentStatus == PaymentStatus.PAID ||
        (draft.paymentConfirmed && paid != null && (draft.paymentStatus != PaymentStatus.UNPAID || paid > 0L))

    if (!showTotal) {
        TextButton(onClick = { showTotal = true }, enabled = !busy, contentPadding = PaddingValues(0.dp)) {
            Text("整单优惠或抹零")
        }
        return
    }
    Column(Modifier.fillMaxWidth().testTag("settlementSection"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = draft.declaredTotal,
            onValueChange = { if (it.length <= 32) onChange(draft.copy(declaredTotal = it, appliedDiscount = null)) },
            label = { Text(if (draft.fromVision) "账单实付合计" else "整单实际支付") },
            prefix = { Text("¥ ") },
            supportingText = { Text("单品优惠计入商品金额 整单优惠在这里处理") },
            enabled = !busy,
            isError = draft.declaredTotal.isNotBlank() && paid == null || difference != null && difference < 0,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("declaredTotal"),
        )
        original?.let { Text("商品合计 ${money(it)}", style = MaterialTheme.typography.bodyMedium) }
        if (difference != null && difference > 0) {
            Text(
                when {
                    applied -> "已分摊优惠 ${money(difference)}"
                    draft.receiptDiscount?.amountCents == difference -> "票面优惠 ${money(difference)}"
                    else -> "与实付相差 ${money(difference)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("allocationStatus").semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (!applied) {
                draft.receiptDiscount?.takeIf { it.amountCents != difference }?.let { discount ->
                    Text(
                        "识别到票面优惠 ${money(discount.amountCents)} 与差额不同 请先确认金额",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("整单优惠或抹零按商品金额比例分摊\n单品优惠请直接修改对应商品金额",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(
                    onClick = { onChange(draft.allocateDiscount()) }, enabled = !busy && source != null && paymentReady,
                    modifier = Modifier.fillMaxWidth().testTag("allocateDiscount"),
                ) { Text("按实付分摊优惠") }
            } else {
                Text("下方保留分摊前金额 入账采用分摊后实付", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onChange(draft.copy(appliedDiscount = null)) }, enabled = !busy,
                    modifier = Modifier.testTag("undoDiscount"), contentPadding = PaddingValues(0.dp)) { Text("撤销分摊") }
            }
        } else if (difference == 0L) {
            Text("商品合计与实付一致", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        } else if (difference != null && difference < 0) {
            Text("实付多出 ${money(-difference)} 请检查漏项或附加费用", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
}
