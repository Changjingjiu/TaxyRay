package io.github.taxray.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Consumer-side VAT estimate for a user-confirmed, tax-inclusive paid amount.
 * This does not determine tax treatment, a seller's liability, or proof of tax payment.
 * See docs/ALGORITHM.md and docs/TAX_POLICY.md for the contract and its limits.
 */
object TaxCalculator {
    const val MAX_ITEM_AMOUNT_CENTS: Long = 9_999_999_999L
    const val MAX_ITEMS: Int = 1_000
    const val MAX_RECEIPT_AMOUNT_CENTS: Long = MAX_ITEM_AMOUNT_CENTS * MAX_ITEMS

    private val decimalInput = Regex("[0-9]+(?:\\.[0-9]{1,2})?")

    /** Rate uses percentage units: pass "13" for 13%, never "0.13" for 13%. */
    fun calculate(amount: String, ratePercent: String): TaxBreakdown {
        val amountCents = parseAmountCents(amount, MAX_ITEM_AMOUNT_CENTS, "单项实付金额")
        val rate = parseDecimal(ratePercent, "税率")
        require(rate <= BigDecimal("100")) { "税率必须在 0% 至 100% 之间" }
        val rateBps = rate.movePointRight(2).intValueExact()

        // In fen: tax = paid × bps / (10000 + bps). Round exactly once, on tax.
        val taxCents = BigDecimal.valueOf(amountCents)
            .multiply(BigDecimal.valueOf(rateBps.toLong()))
            .divide(BigDecimal.valueOf(10_000L + rateBps), 0, RoundingMode.HALF_UP)
            .longValueExact()
        return TaxBreakdown(
            amountCents = amountCents,
            taxRateBps = rateBps,
            preTaxCents = amountCents - taxCents,
            taxCents = taxCents,
        )
    }

    /** Parses a whole receipt's declared paid total, using the 1000-item total bound. */
    fun parseReceiptTotal(amount: String): Long =
        parseAmountCents(amount, MAX_RECEIPT_AMOUNT_CENTS, "票面实付合计")

    /** Every row is validated and rounded before any receipt totals are aggregated. */
    fun calculateItems(items: List<DraftItem>): List<CalculatedItem> {
        require(items.isNotEmpty()) { "请至少添加一个消费品目" }
        require(items.size <= MAX_ITEMS) { "每张账单最多支持 1000 项" }
        require(items.all { it.id.isNotBlank() }) { "品目编号不能为空" }
        require(items.map { it.id }.distinct().size == items.size) { "品目编号不能重复" }
        return items.mapIndexed { index, item ->
            val breakdown = try {
                calculate(item.amount, item.ratePercent)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("第 ${index + 1} 项：${error.message}", error)
            }
            CalculatedItem(
                id = item.id,
                name = item.name.trim().ifEmpty { "消费品目" },
                breakdown = breakdown,
                categoryReason = item.categoryReason.trim(),
            )
        }
    }

    /** Locale-independent numeric value with exactly two decimal places, without ¥. */
    fun formatMoney(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

    /** Locale-independent percentage value, without % and without unnecessary zeroes. */
    fun formatRate(bps: Int): String {
        require(bps in 0..10_000) { "税率必须在 0% 至 100% 之间" }
        return BigDecimal.valueOf(bps.toLong(), 2).stripTrailingZeros().toPlainString()
    }

    /** Uses rounded item tax totals / paid totals, and rounds display only. */
    fun effectiveRate(taxCents: Long, amountCents: Long): String {
        require(amountCents >= 0) { "消费总额不能为负数" }
        require(taxCents in 0..amountCents) { "累计税额超出消费总额范围" }
        if (amountCents == 0L) return "0.00"
        return BigDecimal.valueOf(taxCents)
            .multiply(BigDecimal("100"))
            .divide(BigDecimal.valueOf(amountCents), 2, RoundingMode.HALF_UP)
            .toPlainString()
    }

    private fun parseDecimal(input: String, label: String): BigDecimal {
        // Bound input work before parsing. Long zero prefixes are not a useful input format.
        require(input.length <= 32) { "$label 输入过长" }
        val normalized = input.trim()
        require(decimalInput.matches(normalized)) { "$label 请输入非负数字，最多保留两位小数" }
        return BigDecimal(normalized)
    }

    private fun parseAmountCents(input: String, maxCents: Long, label: String): Long {
        val amount = parseDecimal(input, label)
        require(amount.signum() > 0) { "$label 必须大于 0" }
        require(amount <= BigDecimal.valueOf(maxCents, 2)) {
            "$label 不能超过 ${formatMoney(maxCents)} 元"
        }
        return amount.movePointRight(2).longValueExact()
    }
}
