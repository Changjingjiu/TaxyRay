package io.github.taxray.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.taxray.DuplicateReceiptReview
import io.github.taxray.core.Receipt
import io.github.taxray.ui.components.dateText
import io.github.taxray.ui.components.money

@Composable
fun DuplicateReceiptDialog(review: DuplicateReceiptReview, busy: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit, onKeepExisting: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("可能已录入这笔账单") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("账本中有相似记录 是否仍然录入")
            review.matches.take(3).forEach { receipt ->
                Column {
                    Text(receipt.storeName.ifBlank { "日常消费" }, style = MaterialTheme.typography.titleSmall)
                    Text("${dateText(receipt.timestamp, true)}  ${money(receipt.totalAmountCents)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (review.matches.size > 3) Text("还有 ${review.matches.size - 3} 笔相似记录", style = MaterialTheme.typography.bodySmall)
            Text("保留已有会放弃本次重复录入 也可选择仍然录入", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onKeepExisting, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("保留已有账单") }
        }
    }, confirmButton = { TextButton(onClick = onConfirm, enabled = !busy) { Text("仍然录入") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("返回核对") } })
}

@Composable
fun DeleteReceiptsDialog(receipts: List<Receipt>, busy: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("删除这 ${receipts.size} 笔账单？") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            receipts.take(3).forEach { Text("${it.storeName.ifBlank { "日常消费" }}  ${money(it.totalAmountCents)}") }
            if (receipts.size > 3) Text("以及另外 ${receipts.size - 3} 笔")
            Text("删除后无法恢复", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = {
        TextButton(onClick = onConfirm, enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("确认删除") }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("保留账单") } })
}
