package io.github.taxray

import io.github.taxray.core.DraftItem
import org.junit.Assert.*
import org.junit.Test

class ReceiptDraftTest {
    @Test fun visionTotalMismatchBlocksConfirmationUntilAmountsAreCorrected() {
        val draft = ReceiptDraft(fromVision = true, declaredTotal = "200.00", items = listOf(
            DraftItem(name = "工业品", amount = "113.00", ratePercent = "13"),
            DraftItem(name = "农产品", amount = "109.00", ratePercent = "9"),
        ))
        assertTrue(draft.validationMessage()!!.contains("22.00"))
        assertNull(draft.copy(declaredTotal = "222.00").validationMessage())
        assertEquals(2_200L, draft.calculated().sumOf { it.breakdown.taxCents })
    }

    @Test fun receiptTotalMayExceedSingleItemLimit() {
        val draft = ReceiptDraft(declaredTotal = "199999999.98", items = listOf(
            DraftItem(amount = "99999999.99"), DraftItem(amount = "99999999.99"),
        ))
        assertNull(draft.validationMessage())
    }

    @Test fun emptyNegativeAndOverprecisionNeverProduceSaveableDraft() {
        listOf("", "-1", "2.001").forEach { amount ->
            assertNotNull(ReceiptDraft(items = listOf(DraftItem(amount = amount))).validationMessage())
        }
        assertNotNull(ReceiptDraft(items = emptyList()).validationMessage())
        assertNull(ReceiptDraft(items = listOf(DraftItem(amount = "0.01", ratePercent = "100"))).validationMessage())
    }

    @Test fun explicitDiscountPreservesOriginalsAndRepeatedApplicationNeverCompounds() {
        val original = discountedDraft()
        assertNotNull(original.validationMessage())
        val allocated = original.allocateDiscount()
        assertNull(allocated.validationMessage())
        assertEquals(original.items, allocated.items)
        assertEquals(listOf("101.80", "98.20"), allocated.settledItems().map { it.amount })
        assertEquals(1982L, allocated.calculated().sumOf { it.breakdown.taxCents })
        assertEquals(allocated, allocated.allocateDiscount())
        assertEquals(original.items, allocated.copy(appliedDiscount = null).settledItems())
        // Saving only final amounts makes a reopened draft safe from a second discount.
        val reopened = ReceiptDraft(items = allocated.settledItems())
        assertNull(reopened.validationMessage())
        assertEquals(20000L, reopened.calculated().sumOf { it.breakdown.amountCents })
    }

    @Test fun staleAllocationNeverSurvivesAmountIdOrderOrTotalChanges() {
        val applied = discountedDraft().allocateDiscount()
        listOf(
            applied.copy(declaredTotal = "199.99"),
            applied.copy(declaredTotal = ""),
            applied.copy(items = applied.items.reversed()),
            applied.copy(items = applied.items.drop(1)),
            applied.copy(items = applied.items.map { it.copy(amount = "150.00") }),
            applied.copy(items = applied.items.map { it.copy(id = "changed-${it.id}") }),
        ).forEach { changed ->
            assertFalse(changed.allocationIsCurrent())
            assertEquals("金额已修改 请重新分摊优惠", changed.validationMessage())
        }
        val changedRate = applied.copy(items = applied.items.map { it.copy(ratePercent = "6") })
        assertNull(changedRate.validationMessage())
        assertEquals(20000L, changedRate.calculated().sumOf { it.breakdown.amountCents })
        assertTrue(changedRate.calculated().all { it.breakdown.taxRateBps == 600 })
    }

    @Test fun surchargeCannotBeDisguisedAsDiscountAndFreeItemsAreValid() {
        val extra = discountedDraft().copy(declaredTotal = "230.00")
        assertTrue(extra.validationMessage()!!.contains("漏项"))
        assertThrows(IllegalArgumentException::class.java) { extra.allocateDiscount() }
        val free = discountedDraft().copy(declaredTotal = "0").allocateDiscount()
        assertNull(free.validationMessage())
        assertTrue(free.calculated().all { it.breakdown.amountCents == 0L && it.breakdown.taxCents == 0L })
    }

    private fun discountedDraft() = ReceiptDraft(declaredTotal = "200.00", items = listOf(
        DraftItem(id = "one", amount = "113.00", ratePercent = "13"),
        DraftItem(id = "two", amount = "109.00", ratePercent = "9"),
    ))
}
