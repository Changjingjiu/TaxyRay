package io.github.taxray.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ContributionSummaryTest {
    @Test fun mixedReceiptRatesPreserveAmountsAndShowActualDateRange() {
        val later = receipt("later", 3_000L, "113.00" to "13", "109.00" to "9")
        val earlier = receipt("earlier", 1_000L, "106.00" to "6", "20.00" to "0")
        val summary = ContributionSummary.fromReceipts(listOf(later, earlier))

        assertEquals(2, summary.receiptCount)
        assertEquals(4L, summary.itemCount)
        assertEquals(1_000L, summary.firstTimestamp)
        assertEquals(3_000L, summary.lastTimestamp)
        assertEquals(34_800L, summary.totalAmountCents)
        assertEquals(2_800L, summary.totalTaxCents)
        assertEquals(32_000L, summary.totalPreTaxCents)
        assertEquals("8.05", summary.effectiveRatePercent)
        assertEquals(listOf(1300, 900, 600, 0), summary.rateGroups.map { it.rateBps })
        assertEquals(listOf(1300L, 900L, 600L, 0L), summary.rateGroups.map { it.taxCents })
        assertEquals(2_000L, summary.rateGroups.last().amountCents)
    }

    @Test fun combinesPreviouslyRoundedRowsWithoutRecalculatingTaxFromTheTotal() {
        val receipts = List(3) { receipt("small-$it", it.toLong(), "0.04" to "13") }
        val summary = ContributionSummary.fromReceipts(receipts)

        assertEquals(12L, summary.totalAmountCents)
        assertEquals(0L, summary.totalTaxCents)
        assertEquals(12L, summary.totalPreTaxCents)
        assertEquals(0L, summary.rateGroups.single().taxCents)
        // Recomputing the combined amount would incorrectly introduce one cent of tax.
        assertEquals(1L, TaxCalculator.calculate("0.12", "13").taxCents)
    }

    @Test fun emptyLedgerAndZeroValueRowsHaveNoInventedTaxOrDate() {
        val empty = ContributionSummary.fromReceipts(emptyList())
        assertEquals(0, empty.receiptCount)
        assertEquals(0L, empty.itemCount)
        assertNull(empty.firstTimestamp)
        assertNull(empty.lastTimestamp)
        assertTrue(empty.rateGroups.isEmpty())
        assertEquals("0.00", empty.effectiveRatePercent)

        val zero = ContributionSummary.fromReceipts(listOf(receipt("zero", 10, "0" to "13", "0" to "0")))
        assertEquals(1, zero.receiptCount)
        assertEquals(2L, zero.itemCount)
        assertEquals(0L, zero.totalAmountCents)
        assertEquals(0L, zero.totalTaxCents)
        assertEquals(0L, zero.totalPreTaxCents)
        assertEquals("0.00", zero.effectiveRatePercent)
        assertEquals(listOf(1300, 0), zero.rateGroups.map { it.rateBps })
    }

    @Test fun repeatedReceiptIdCannotDoubleCountButSeparateIdenticalPurchasesCan() {
        val original = receipt("original", 0, "113" to "13")
        assertThrows(IllegalArgumentException::class.java) {
            ContributionSummary.fromReceipts(listOf(original, original))
        }
        val summary = ContributionSummary.fromReceipts(listOf(original, original.copy(id = "second")))
        assertEquals(2, summary.receiptCount)
        assertEquals(2_600L, summary.totalTaxCents)
    }

    @Test fun customRatesAndLargeExactAmountsKeepAllGroupTotals() {
        val receipts = (0..7).map { rate -> receipt("rate-$rate", rate.toLong(), "99999999.99" to "$rate.25") }
        val summary = ContributionSummary.fromReceipts(receipts)
        assertEquals(8, summary.rateGroups.size)
        assertEquals(79_999_999_992L, summary.totalAmountCents)
        assertEquals(receipts.sumOf { it.totalTaxCents }, summary.totalTaxCents)
        assertEquals(summary.totalAmountCents, summary.totalTaxCents + summary.totalPreTaxCents)
        assertEquals(summary.totalAmountCents, summary.rateGroups.sumOf { it.amountCents })
        assertEquals(summary.totalTaxCents, summary.rateGroups.sumOf { it.taxCents })
        assertEquals(summary.totalPreTaxCents, summary.rateGroups.sumOf { it.preTaxCents })
    }

    @Test fun variedSavedRowsPreserveTheLedgerAndEachRateSubtotal() {
        val random = Random(0x544158)
        val receipts = List(60) { index ->
            Receipt("receipt-$index", "", random.nextLong(1000),
                TaxCalculator.calculateItems(List(20) { item ->
                    DraftItem(id = "$index-$item", amount = TaxCalculator.formatMoney(random.nextLong(100_000)),
                        ratePercent = TaxCalculator.formatRate(listOf(0, 125, 600, 900, 1300, 1750, 10_000).random(random)))
                }))
        }
        val summary = ContributionSummary.fromReceipts(receipts)
        val rows = receipts.flatMap { it.items }.map { it.breakdown }
        assertEquals(1_200L, summary.itemCount)
        assertEquals(rows.sumOf { it.amountCents }, summary.totalAmountCents)
        assertEquals(rows.sumOf { it.taxCents }, summary.totalTaxCents)
        assertEquals(rows.sumOf { it.preTaxCents }, summary.totalPreTaxCents)
        summary.rateGroups.forEach { group ->
            val matching = rows.filter { it.taxRateBps == group.rateBps }
            assertEquals(matching.sumOf { it.amountCents }, group.amountCents)
            assertEquals(matching.sumOf { it.taxCents }, group.taxCents)
            assertEquals(matching.sumOf { it.preTaxCents }, group.preTaxCents)
        }
    }

    private fun receipt(id: String, timestamp: Long, vararg rows: Pair<String, String>): Receipt =
        Receipt(id, "测试商户", timestamp, TaxCalculator.calculateItems(rows.mapIndexed { index, (amount, rate) ->
            DraftItem(id = "$id-$index", amount = amount, ratePercent = rate)
        }))
}
