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
        assertTrue(draft.validationMessage()!!.contains("实付不一致"))
        assertNull(draft.copy(declaredTotal = "222.00").validationMessage())
        assertEquals(2_200L, draft.calculated().sumOf { it.breakdown.taxCents })
    }

    @Test fun receiptTotalMayExceedSingleItemLimit() {
        val draft = ReceiptDraft(declaredTotal = "199999999.98", items = listOf(
            DraftItem(amount = "99999999.99"), DraftItem(amount = "99999999.99"),
        ))
        assertNull(draft.validationMessage())
    }

    @Test fun emptyZeroNegativeAndOverprecisionNeverProduceSaveableDraft() {
        listOf("", "0", "-1", "2.001").forEach { amount ->
            assertNotNull(ReceiptDraft(items = listOf(DraftItem(amount = amount))).validationMessage())
        }
        assertNotNull(ReceiptDraft(items = emptyList()).validationMessage())
        assertNull(ReceiptDraft(items = listOf(DraftItem(amount = "0.01", ratePercent = "100"))).validationMessage())
    }
}
