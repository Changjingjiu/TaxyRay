package io.github.taxray.core

import java.math.BigInteger
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class DiscountAllocatorTest {
    @Test fun fourFenRoundingHasAnExactAndReproducibleAllocation() {
        val before = listOf(268L, 682L, 520L, 416L, 1588L, 541L, 185L, 333L, 1391L)
        val result = DiscountAllocator.allocate(before, 5920)
        assertEquals(5924L, result.originalTotalCents)
        assertEquals(4L, result.discountCents)
        assertEquals(listOf(268L, 681L, 520L, 416L, 1587L, 540L, 185L, 333L, 1390L), result.paidCents)
        assertEquals(before, result.originalCents)
    }

    @Test fun memberDiscountIsAllocatedBeforeMixedRateTaxCalculation() {
        val paid = DiscountAllocator.allocate(listOf(11300L, 10900L), 20000).paidCents
        assertEquals(listOf(10180L, 9820L), paid)
        val items = TaxCalculator.calculateItems(paid.zip(listOf("13", "9")).map { (cents, rate) ->
            DraftItem(amount = TaxCalculator.formatMoney(cents), ratePercent = rate)
        })
        assertEquals(listOf(1171L, 811L), items.map { it.breakdown.taxCents })
        assertEquals(20000L, items.sumOf { it.breakdown.preTaxCents + it.breakdown.taxCents })
    }

    @Test fun tiesUseReceiptOrderAndGiftRowsStayZero() {
        assertEquals(listOf(1L, 1L, 0L), DiscountAllocator.allocate(listOf(1L, 1L, 1L), 2).paidCents)
        assertEquals(listOf(0L, 1L, 0L), DiscountAllocator.allocate(listOf(0L, 1L, 1L), 1).paidCents)
        assertEquals(listOf(0L, 0L), DiscountAllocator.allocate(listOf(900L, 100L), 0).paidCents)
        assertEquals(listOf(0L, 0L), DiscountAllocator.allocate(listOf(0L, 0L), 0).paidCents)
        assertEquals(listOf(123L, 456L), DiscountAllocator.allocate(listOf(123L, 456L), 579).paidCents)
    }

    @Test fun thousandMaximumValueRowsDoNotOverflowIntermediateProducts() {
        val max = TaxCalculator.MAX_ITEM_AMOUNT_CENTS
        val result = DiscountAllocator.allocate(List(1000) { max }, max * 1000 - 1)
        assertEquals(List(999) { max } + (max - 1), result.paidCents)
        assertEquals(1L, result.discountCents)
    }

    @Test fun invalidInputsAreNeverCoercedIntoADiscount() {
        listOf(emptyList(), listOf(-1L), listOf(TaxCalculator.MAX_ITEM_AMOUNT_CENTS + 1), List(1001) { 1L })
            .forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { DiscountAllocator.allocate(invalid, 0) } }
        assertThrows(IllegalArgumentException::class.java) { DiscountAllocator.allocate(listOf(100L), 101) }
        assertThrows(IllegalArgumentException::class.java) { DiscountAllocator.allocate(listOf(100L), -1) }
    }

    @Test fun randomizedAllocationsConserveMoneyAndRespectEveryRowsExactQuota() {
        val random = Random(5920)
        repeat(3000) {
            val original = List(random.nextInt(1, 50)) { random.nextLong(0, TaxCalculator.MAX_ITEM_AMOUNT_CENTS + 1) }
            val total = original.sum()
            val target = random.nextLong(0, total + 1)
            val result = DiscountAllocator.allocate(original, target)
            assertEquals(target, result.paidCents.sum())
            original.indices.forEach { index ->
                val paid = result.paidCents[index]
                assertTrue(paid in 0..original[index])
                // Each allocation differs from its exact proportional quota by less than one fen.
                val difference = BigInteger.valueOf(paid).multiply(BigInteger.valueOf(total))
                    .subtract(BigInteger.valueOf(original[index]).multiply(BigInteger.valueOf(target))).abs()
                assertTrue(difference < BigInteger.valueOf(total))
            }
        }
    }
}
