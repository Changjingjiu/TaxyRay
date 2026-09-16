package io.github.taxray

import io.github.taxray.core.DraftItem
import io.github.taxray.data.remote.PaymentStatus
import io.github.taxray.data.remote.ReceiptDiscount
import org.junit.Assert.*
import org.junit.Test

class ReceiptDraftTest {
    @Test fun visionTotalMismatchBlocksConfirmationUntilAmountsAreCorrected() {
        val draft = ReceiptDraft(fromVision = true, paymentStatus = PaymentStatus.PAID, declaredTotal = "200.00", items = listOf(
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

    @Test fun knownUnallocatedDiscountCannotBeLostWhenPaidTotalIsMissingOrCleared() {
        val draft = ReceiptDraft(fromVision = true, paymentStatus = PaymentStatus.PAID, items = listOf(DraftItem(amount = "59.24")),
            receiptDiscount = ReceiptDiscount(4, "优惠 0.04"))
        assertEquals("识别到整单优惠 请先填写最终实付", draft.validationMessage())
        val confirmed = draft.copy(declaredTotal = "59.20").allocateDiscount()
        assertNull(confirmed.validationMessage())
        assertNotNull(confirmed.copy(declaredTotal = "", appliedDiscount = null).validationMessage())
        // An explicit matching total can confirm the line already includes its discount.
        assertNull(draft.copy(declaredTotal = "59.24").validationMessage())
        assertNull(draft.copy(receiptDiscount = ReceiptDiscount(0, "优惠 0.00")).validationMessage())
    }

    @Test fun uncertainVisionPaymentsRequireExplicitConfirmationBeforeSaving() {
        listOf(PaymentStatus.UNKNOWN, null).forEach { status ->
            val draft = ReceiptDraft(fromVision = true, paymentStatus = status,
                items = listOf(DraftItem(amount = "2.46")), declaredTotal = "2.46")
            assertTrue(draft.validationMessage()!!.contains("付款状态不明确"))
            assertNull(draft.copy(paymentConfirmed = true).validationMessage())
        }
    }

    @Test fun confirmedUnknownPaymentsCannotUseItemPricesAsAnImplicitPaidTotal() {
        listOf(PaymentStatus.UNKNOWN, null).forEach { status ->
            val draft = ReceiptDraft(fromVision = true, paymentStatus = status, paymentConfirmed = true,
                items = listOf(DraftItem(amount = "18.79")))
            assertEquals("付款状态不明确 请填写最终实付后入账", draft.validationMessage())
            assertTrue(draft.copy(declaredTotal = "8.79").validationMessage()!!.contains("10.00"))
            assertNull(draft.copy(declaredTotal = "8.79").allocateDiscount().validationMessage())
        }
    }

    @Test fun confirmedUnknownExplicitZeroRequiresMatchingItemsOrAnExplicitFullDiscount() {
        val draft = ReceiptDraft(fromVision = true, paymentStatus = PaymentStatus.UNKNOWN,
            paymentConfirmed = true, declaredTotal = "0", items = listOf(DraftItem(amount = "10.00")))
        assertTrue(draft.validationMessage()!!.contains("10.00"))
        val discounted = draft.allocateDiscount()
        assertNull(discounted.validationMessage())
        assertEquals(0L, discounted.calculated().sumOf { it.breakdown.amountCents })
        assertNull(draft.copy(items = listOf(DraftItem(amount = "0"))).validationMessage())
    }

    @Test fun unpaidOrdersRequirePaymentConfirmationAndAnExplicitPositiveFinalTotal() {
        val unpaid = ReceiptDraft(fromVision = true, paymentStatus = PaymentStatus.UNPAID,
            paymentEvidence = "确认收货后自动付款 ¥10.74", declaredTotal = "10.74",
            items = listOf(DraftItem(amount = "10.74")))
        assertTrue(unpaid.validationMessage()!!.contains("待付款"))
        val confirmed = unpaid.copy(paymentConfirmed = true)
        assertNull(confirmed.validationMessage())
        listOf("", "0", "0.00").forEach { total ->
            assertTrue(confirmed.copy(declaredTotal = total).validationMessage()!!.contains("大于 0"))
        }
        assertTrue(confirmed.copy(declaredTotal = "10.00").validationMessage()!!.contains("0.74"))
        assertNull(confirmed.copy(declaredTotal = "10.00").allocateDiscount().validationMessage())
    }

    @Test fun unpaidZeroIsNeverConvertedIntoAFullDiscountOrSaveableFreeItem() {
        val unpaid = ReceiptDraft(fromVision = true, paymentStatus = PaymentStatus.UNPAID,
            paymentConfirmed = true, declaredTotal = "0", items = listOf(DraftItem(amount = "10.74")))
        val error = assertThrows(IllegalArgumentException::class.java) { unpaid.allocateDiscount() }
        assertTrue(error.message!!.contains("大于 0"))
        assertNotNull(unpaid.copy(items = listOf(DraftItem(amount = "0"))).validationMessage())
        // An allocation created in another state cannot bypass the final payment check.
        val paidAllocation = unpaid.copy(paymentStatus = PaymentStatus.PAID).allocateDiscount()
        assertNotNull(paidAllocation.copy(paymentStatus = PaymentStatus.UNPAID).validationMessage())
    }

    @Test fun paidAndManualBillsDoNotRequireASecondPaymentConfirmation() {
        val manual = ReceiptDraft(items = listOf(DraftItem(amount = "6.72")))
        assertNull(manual.paymentStatus)
        assertFalse(manual.paymentConfirmed)
        assertNull(manual.validationMessage())
        val paid = manual.copy(fromVision = true, paymentStatus = PaymentStatus.PAID)
        assertNull(paid.validationMessage())
        assertNull(paid.copy(declaredTotal = "0").allocateDiscount().validationMessage())
    }

    private fun discountedDraft() = ReceiptDraft(declaredTotal = "200.00", items = listOf(
        DraftItem(id = "one", amount = "113.00", ratePercent = "13"),
        DraftItem(id = "two", amount = "109.00", ratePercent = "9"),
    ))
}
