package io.github.taxray.core

/** A read-only summary of confirmed ledger rows, never a new receipt or a tax recalculation. */
class ContributionSummary private constructor(
    val receiptCount: Int,
    val itemCount: Long,
    val firstTimestamp: Long?,
    val lastTimestamp: Long?,
    val totalAmountCents: Long,
    val totalPreTaxCents: Long,
    val totalTaxCents: Long,
    val rateGroups: List<TaxRateContribution>,
) {
    val effectiveRatePercent: String
        get() = TaxCalculator.effectiveRate(totalTaxCents, totalAmountCents)

    companion object {
        fun fromReceipts(receipts: List<Receipt>): ContributionSummary {
            require(receipts.map { it.id }.distinct().size == receipts.size) { "累计卡不能包含重复账单" }
            var paid = 0L
            var preTax = 0L
            var tax = 0L
            var itemCount = 0L
            val rateGroups = mutableMapOf<Int, TaxRateContribution>()
            receipts.forEach { receipt ->
                itemCount = Math.addExact(itemCount, receipt.items.size.toLong())
                receipt.items.forEach { item ->
                    val row = item.breakdown
                    // Keep every saved row's rounded result, including discounted and zero-tax rows.
                    paid = Math.addExact(paid, row.amountCents)
                    preTax = Math.addExact(preTax, row.preTaxCents)
                    tax = Math.addExact(tax, row.taxCents)
                    val previous = rateGroups[row.taxRateBps]
                    rateGroups[row.taxRateBps] = TaxRateContribution(
                        row.taxRateBps,
                        Math.addExact(previous?.amountCents ?: 0L, row.amountCents),
                        Math.addExact(previous?.preTaxCents ?: 0L, row.preTaxCents),
                        Math.addExact(previous?.taxCents ?: 0L, row.taxCents),
                    )
                }
            }
            return ContributionSummary(
                receipts.size, itemCount,
                receipts.minOfOrNull { it.timestamp }, receipts.maxOfOrNull { it.timestamp },
                paid, preTax, tax,
                rateGroups.values.sortedWith(compareByDescending<TaxRateContribution> { it.taxCents }
                    .thenByDescending { it.rateBps }),
            )
        }
    }
}

data class TaxRateContribution(
    val rateBps: Int,
    val amountCents: Long,
    val preTaxCents: Long,
    val taxCents: Long,
)
