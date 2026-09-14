package io.github.taxray.core

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TaxCalculatorTest {
    @Test fun knownTaxInclusiveExamples() {
        assertEquals(TaxBreakdown(11_300, 1_300, 10_000, 1_300), TaxCalculator.calculate("113", "13"))
        assertEquals(TaxBreakdown(10_900, 900, 10_000, 900), TaxCalculator.calculate("109.00", "9"))
        assertEquals(TaxBreakdown(10_600, 600, 10_000, 600), TaxCalculator.calculate("106.00", "6"))
        assertEquals(TaxBreakdown(3_500, 1_300, 3_097, 403), TaxCalculator.calculate("35.00", "13"))
    }

    @Test fun zeroAndCustomRatesAreExact() {
        assertEquals(TaxBreakdown(1, 0, 1, 0), TaxCalculator.calculate("0.01", "0"))
        assertEquals(TaxBreakdown(10_625, 625, 10_000, 625), TaxCalculator.calculate("106.25", "6.25"))
        assertEquals(TaxBreakdown(10_001, 1, 10_000, 1), TaxCalculator.calculate("100.01", "0.01"))
        assertEquals(1_000, TaxCalculator.calculate("20", "100").taxCents.toInt())
    }

    @Test fun taxHalfCentRoundsUpAndNetIsConserved() {
        // Both unrounded parts are half a fen. Rounding each would incorrectly total 2 fen.
        val result = TaxCalculator.calculate("0.01", "100")
        assertEquals(1L, result.taxCents)
        assertEquals(0L, result.preTaxCents)
        assertEquals(result.amountCents, result.preTaxCents + result.taxCents)
    }

    @Test fun taxIsComputedBeforeRoundingTheNet() {
        // 0.05 / 1.01 = 0.04950...; tax rounds to zero, so the net remains 0.05.
        val result = TaxCalculator.calculate("0.05", "1")
        assertEquals(0L, result.taxCents)
        assertEquals(5L, result.preTaxCents)
        // At the maximum supported rate, prematurely rounded net would alter the tax.
        val half = TaxCalculator.calculate("0.03", "100")
        assertEquals(2L, half.taxCents)
        assertEquals(1L, half.preTaxCents)
    }

    @Test fun rowRoundingDiffersFromRoundingTheWholeReceipt() {
        val lines = TaxCalculator.calculateItems(List(2) { DraftItem(amount = "0.04", ratePercent = "13") })
        val receipt = Receipt("receipt", "小额消费", 0, lines)
        assertEquals(8L, receipt.totalAmountCents)
        assertEquals(0L, receipt.totalTaxCents)
        assertEquals(1L, TaxCalculator.calculate("0.08", "13").taxCents)
        assertEquals("0.00", receipt.effectiveRatePercent)
    }

    @Test fun mixedRatesAggregateRoundedItemsAndUsePaidAmountAsDenominator() {
        val receipt = Receipt("mixed", "", 0, TaxCalculator.calculateItems(listOf(
            DraftItem(amount = "113", ratePercent = "13"),
            DraftItem(amount = "109", ratePercent = "9"),
            DraftItem(amount = "10", ratePercent = "0"),
        )))
        assertEquals(23_200L, receipt.totalAmountCents)
        assertEquals(2_200L, receipt.totalTaxCents)
        assertEquals(21_000L, receipt.totalPreTaxCents)
        assertEquals("9.48", receipt.effectiveRatePercent)
        assertEquals("11.50", TaxCalculator.effectiveRate(1_300, 11_300))
        assertNotEquals("13.00", TaxCalculator.effectiveRate(1_300, 11_300))
    }

    @Test fun amountsParseFromDecimalTextWithoutBinaryFloatPollution() {
        assertEquals(10L, TaxCalculator.calculate("0.10", "13").amountCents)
        assertEquals(29L, TaxCalculator.calculate("0.29", "13").amountCents)
        assertEquals(267L, TaxCalculator.calculate("2.67", "13").amountCents)
        assertEquals(1_999_999_999L, TaxCalculator.calculate("19999999.99", "13").amountCents)
        assertEquals(TaxCalculator.calculate("1.2", "13.00"), TaxCalculator.calculate(" 01.20 ", "13"))
    }

    @Test fun maximumAmountAndThousandItemsRemainExact() {
        val max = TaxCalculator.calculate("99999999.99", "100.00")
        assertEquals(9_999_999_999L, max.amountCents)
        assertEquals(5_000_000_000L, max.taxCents)
        val receipt = Receipt("max", "", 0, TaxCalculator.calculateItems(List(1_000) {
            DraftItem(amount = "99999999.99", ratePercent = "100")
        }))
        assertEquals(9_999_999_999_000L, receipt.totalAmountCents)
        assertEquals(5_000_000_000_000L, receipt.totalTaxCents)
        assertEquals(receipt.totalAmountCents, receipt.totalPreTaxCents + receipt.totalTaxCents)
        assertEquals(receipt.totalAmountCents, TaxCalculator.parseReceiptTotal("99999999990.00"))
    }

    @Test fun wholeReceiptTotalHasItsOwnBoundAndPreservesDecimalPrecision() {
        assertEquals(1L, TaxCalculator.parseReceiptTotal("0.01"))
        assertEquals(19_999_999_998L, TaxCalculator.parseReceiptTotal("199999999.98"))
        assertEquals(TaxCalculator.MAX_RECEIPT_AMOUNT_CENTS, TaxCalculator.parseReceiptTotal("99999999990.00"))
        assertEquals(29L, TaxCalculator.parseReceiptTotal(" 0000.29 "))
        listOf("0", "0.00", "-1", "+1", "", "1.", ".5", "1.001", "1.230", "1e2", "NaN", "1,000",
            "１２.０", "99999999990.01", "99999999991", "1".repeat(33)).forEach { input ->
            assertThrows("total=$input", IllegalArgumentException::class.java) { TaxCalculator.parseReceiptTotal(input) }
        }
        // Parsing a larger receipt must not weaken the per-item bound.
        assertThrows(IllegalArgumentException::class.java) { TaxCalculator.calculate("199999999.98", "13") }
    }

    @Test fun invalidAmountsAreRejectedInsteadOfRoundedOrCoerced() {
        listOf("", " ", "0", "0.00", "-1", "+1", ".5", "1.", "1.001", "NaN", "Infinity",
            "1e2", "1,234.56", "￥12.00", "１２.００", "100000000", "100000000.00", "1".repeat(33))
            .forEach { amount ->
                assertThrows("amount=$amount", IllegalArgumentException::class.java) {
                    TaxCalculator.calculate(amount, "13")
                }
            }
    }

    @Test fun invalidRatesAreRejectedInsteadOfSilentlyDefaulted() {
        listOf("", "-1", "100.01", "101", "0.001", "13.000", "13%", "NaN", "1e1", "0,13")
            .forEach { rate ->
                assertThrows("rate=$rate", IllegalArgumentException::class.java) {
                    TaxCalculator.calculate("1", rate)
                }
            }
    }

    @Test fun batchValidationPreservesIdsAndExplainsInvalidRow() {
        val input = DraftItem(id = "one", name = "  ", amount = "1", categoryReason = " 人工核对 ")
        val item = TaxCalculator.calculateItems(listOf(input)).single()
        assertEquals("one", item.id)
        assertEquals("消费品目", item.name)
        assertEquals("人工核对", item.categoryReason)
        val error = assertThrows(IllegalArgumentException::class.java) {
            TaxCalculator.calculateItems(listOf(input, DraftItem(amount = "bad")))
        }
        assertTrue(error.message.orEmpty().startsWith("第 2 项："))
        assertThrows(IllegalArgumentException::class.java) { TaxCalculator.calculateItems(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { TaxCalculator.calculateItems(List(1_001) { DraftItem(amount = "1") }) }
        assertThrows(IllegalArgumentException::class.java) { TaxCalculator.calculateItems(listOf(input, input)) }
        assertThrows(IllegalArgumentException::class.java) { TaxCalculator.calculateItems(listOf(input.copy(id = ""))) }
    }

    @Test fun formattingIsStableAcrossLocalesAndHandlesLargeIntegers() {
        val prior = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("0.01", TaxCalculator.formatMoney(1))
            assertEquals("1234.00", TaxCalculator.formatMoney(123_400))
            assertEquals("92233720368547758.07", TaxCalculator.formatMoney(Long.MAX_VALUE))
            assertEquals("13", TaxCalculator.formatRate(1_300))
            assertEquals("6.25", TaxCalculator.formatRate(625))
            assertEquals("0.01", TaxCalculator.formatRate(1))
            assertEquals("0", TaxCalculator.formatRate(0))
            assertEquals("100", TaxCalculator.formatRate(10_000))
            assertEquals("0.00", TaxCalculator.effectiveRate(0, 0))
            assertEquals("50.00", TaxCalculator.effectiveRate(Long.MAX_VALUE / 2, Long.MAX_VALUE))
        } finally {
            Locale.setDefault(prior)
        }
        assertThrows(IllegalArgumentException::class.java) { TaxCalculator.formatRate(-1) }
        assertThrows(IllegalArgumentException::class.java) { TaxCalculator.formatRate(10_001) }
        assertThrows(IllegalArgumentException::class.java) { TaxCalculator.effectiveRate(1, 0) }
        assertThrows(IllegalArgumentException::class.java) { TaxCalculator.effectiveRate(-1, 1) }
        assertThrows(IllegalArgumentException::class.java) { TaxCalculator.effectiveRate(0, -1) }
    }

    @Test fun malformedStoredBreakdownsAndReceiptsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { TaxBreakdown(100, 1_300, 89, 12) }
        assertThrows(IllegalArgumentException::class.java) { TaxBreakdown(0, 0, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { TaxBreakdown(100, 10_001, 50, 50) }
        assertThrows(IllegalArgumentException::class.java) { TaxBreakdown(100, 0, -1, 101) }
        assertThrows(IllegalArgumentException::class.java) { Receipt("one", "", 0, emptyList()) }
    }

    @Test fun randomizedResultsMatchAnIndependentIntegerRationalOracle() {
        val random = Random(0x544158)
        repeat(25_000) {
            val paid = random.nextLong(1, TaxCalculator.MAX_ITEM_AMOUNT_CENTS + 1)
            val bps = random.nextInt(0, 10_001)
            val result = TaxCalculator.calculate(TaxCalculator.formatMoney(paid), TaxCalculator.formatRate(bps))
            // Integer quotient + remainder is independent of production BigDecimal.divide.
            val numerator = BigInteger.valueOf(paid).multiply(BigInteger.valueOf(bps.toLong()))
            val denominator = BigInteger.valueOf(10_000L + bps)
            val (whole, remainder) = numerator.divideAndRemainder(denominator)
            val expectedTax = whole.add(if (remainder.shiftLeft(1) >= denominator) BigInteger.ONE else BigInteger.ZERO).longValueExact()
            assertEquals(expectedTax, result.taxCents)
            assertEquals(paid, result.preTaxCents + result.taxCents)
            assertTrue(result.taxCents in 0..paid)
            assertTrue(result.preTaxCents >= 0)
            val exactTax = BigDecimal(numerator).divide(BigDecimal(denominator), 20, RoundingMode.HALF_UP)
            assertTrue(exactTax.subtract(BigDecimal.valueOf(result.taxCents)).abs() <= BigDecimal("0.5"))
        }
    }

    @Test fun increasingRateNeverDecreasesRoundedTaxForFixedPaidAmount() {
        listOf(1L, 99L, 1_000L, TaxCalculator.MAX_ITEM_AMOUNT_CENTS).forEach { paid ->
            var previous = 0L
            for (bps in 0..10_000) {
                val result = TaxCalculator.calculate(TaxCalculator.formatMoney(paid), TaxCalculator.formatRate(bps))
                assertTrue(result.taxCents >= previous)
                previous = result.taxCents
            }
        }
    }
}
