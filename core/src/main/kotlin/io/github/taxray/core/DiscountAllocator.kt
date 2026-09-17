package io.github.taxray.core

import java.math.BigInteger

/** An explicit allocation of the final paid total, never an inferred OCR correction. */
data class DiscountAllocation(
    val originalCents: List<Long>,
    val paidCents: List<Long>,
) {
    val originalTotalCents: Long get() = originalCents.sum()
    val paidTotalCents: Long get() = paidCents.sum()
    val discountCents: Long get() = originalTotalCents - paidTotalCents
}

/** Largest remainder method, applied to the paid amount in integer fen before tax. */
object DiscountAllocator {
    fun allocate(originalCents: List<Long>, paidTotalCents: Long): DiscountAllocation {
        require(originalCents.size in 1..TaxCalculator.MAX_ITEMS) { "每张账单需要 1–1000 个商品项" }
        require(originalCents.all { it in 0..TaxCalculator.MAX_ITEM_AMOUNT_CENTS }) { "商品金额超出支持范围" }
        val original = originalCents.toList()
        val total = original.fold(0L, Math::addExact)
        require(paidTotalCents in 0..total) { "实付不能高于商品合计 请检查漏项或附加费用" }
        if (total == 0L) return DiscountAllocation(original, original)

        // At the supported bounds a_i * paidTotal can exceed Long.MAX_VALUE.
        val denominator = BigInteger.valueOf(total)
        val numerators = original.map { BigInteger.valueOf(it).multiply(BigInteger.valueOf(paidTotalCents)) }
        val quotientRemainder = numerators.map { it.divideAndRemainder(denominator) }
        // Every quotient is at most paidTotal, so it always fits a Long and toLong() stays exact.
        val paid = quotientRemainder.map { it[0].toLong() }.toMutableList()
        val remaining = Math.toIntExact(paidTotalCents - paid.sum())
        // Equal remainders use ticket order. No random tie-breaks or last-row dumping.
        original.indices.sortedWith(
            compareByDescending<Int> { quotientRemainder[it][1] }.thenBy { it }
        ).take(remaining).forEach { paid[it]++ }
        check(paid.sum() == paidTotalCents && paid.indices.all { paid[it] in 0..original[it] })
        return DiscountAllocation(original, paid.toList())
    }
}
