package io.github.taxray.core

import java.util.UUID

/** Untrusted form / AI input. Calculation is the validation boundary. */
data class DraftItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val amount: String = "",
    val ratePercent: String = "13",
    val categoryReason: String = "",
)

/** CNY amounts are integer fen; 1 basis point represents 0.01 percentage points. */
data class TaxBreakdown(
    val amountCents: Long,
    val taxRateBps: Int,
    val preTaxCents: Long,
    val taxCents: Long,
) {
    init {
        require(amountCents in 0..TaxCalculator.MAX_ITEM_AMOUNT_CENTS) { "实付金额超出支持范围" }
        require(taxRateBps in 0..10_000) { "税率必须在 0% 至 100% 之间" }
        require(taxCents in 0..amountCents) { "税额超出实付金额范围" }
        require(preTaxCents == amountCents - taxCents) { "税前金额与税额之和必须等于实付金额" }
    }
}

data class CalculatedItem(
    val id: String,
    val name: String,
    val breakdown: TaxBreakdown,
    val categoryReason: String = "",
)

data class Receipt(
    val id: String,
    val storeName: String,
    val timestamp: Long,
    val items: List<CalculatedItem>,
) {
    init {
        require(id.isNotBlank()) { "账单编号不能为空" }
        require(items.isNotEmpty()) { "账单至少需要一个消费品目" }
        require(items.size <= TaxCalculator.MAX_ITEMS) { "每张账单最多支持 1000 项" }
        require(items.all { it.id.isNotBlank() }) { "品目编号不能为空" }
        require(items.map { it.id }.distinct().size == items.size) { "账单内品目编号不能重复" }
    }

    val totalAmountCents: Long
        get() = items.fold(0L) { sum, item -> Math.addExact(sum, item.breakdown.amountCents) }
    val totalTaxCents: Long
        get() = items.fold(0L) { sum, item -> Math.addExact(sum, item.breakdown.taxCents) }
    val totalPreTaxCents: Long
        get() = items.fold(0L) { sum, item -> Math.addExact(sum, item.breakdown.preTaxCents) }
    val effectiveRatePercent: String
        get() = TaxCalculator.effectiveRate(totalTaxCents, totalAmountCents)
}
