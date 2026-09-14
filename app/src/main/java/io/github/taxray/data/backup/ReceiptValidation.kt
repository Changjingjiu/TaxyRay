package io.github.taxray.data.backup

import io.github.taxray.core.DraftItem
import io.github.taxray.core.Receipt
import io.github.taxray.core.TaxCalculator

internal object ReceiptValidation {
    const val MAX_RECEIPTS = 10_000
    const val MAX_ITEMS = 1_000
    const val MAX_BACKUP_BYTES = 5_000_000

    fun identifier(value: String, label: String) {
        require(value.isNotBlank() && value.length <= 128 && value.none { it.isISOControl() }) {
            "$label 必须是 1–128 个非控制字符"
        }
    }

    fun validate(receipt: Receipt): Receipt {
        identifier(receipt.id, "账单 ID")
        require(receipt.storeName.length <= 120) { "商户名称不能超过 120 个字符" }
        require(receipt.timestamp in 0..253402300799999L) { "账单时间超出有效范围" }
        require(receipt.items.size in 1..MAX_ITEMS) { "每张账单需要 1–1000 个商品项" }
        require(receipt.items.map { it.id }.distinct().size == receipt.items.size) { "同一账单中商品 ID 重复" }
        receipt.items.forEach {
            identifier(it.id, "商品 ID")
            require(it.name.length <= 200) { "商品名称不能超过 200 个字符" }
            require(it.categoryReason.length <= 500) { "归类理由不能超过 500 个字符" }
        }
        val recalculated = TaxCalculator.calculateItems(receipt.items.map {
            DraftItem(
                id = it.id,
                name = it.name,
                amount = TaxCalculator.formatMoney(it.breakdown.amountCents),
                ratePercent = TaxCalculator.formatRate(it.breakdown.taxRateBps),
                categoryReason = it.categoryReason,
            )
        })
        receipt.items.zip(recalculated).forEach { (provided, calculated) ->
            require(provided.breakdown == calculated.breakdown) {
                "账单 ${receipt.id} 的商品 ${provided.name} 金额与本地重算结果不一致"
            }
            require(provided.name == calculated.name && provided.categoryReason == calculated.categoryReason) {
                "账单 ${receipt.id} 的商品文本格式不规范"
            }
        }
        // Access all totals so checked arithmetic in the domain model is exercised.
        require(Math.addExact(receipt.totalPreTaxCents, receipt.totalTaxCents) == receipt.totalAmountCents) {
            "账单合计不守恒"
        }
        return receipt
    }
}
