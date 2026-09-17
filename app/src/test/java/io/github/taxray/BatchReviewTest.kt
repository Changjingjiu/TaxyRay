package io.github.taxray

import io.github.taxray.core.DraftItem
import io.github.taxray.core.Receipt
import io.github.taxray.core.ReceiptDuplicates
import io.github.taxray.core.ReceiptItemDuplicates
import io.github.taxray.core.TaxCalculator
import io.github.taxray.data.remote.PaymentStatus
import io.github.taxray.data.remote.ReceiptDiscount
import io.github.taxray.data.remote.VisionReceipt
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class BatchReviewTest {

    @Test
    fun cleanPaidReceiptIsEligible() {
        val draft = ReceiptDraft(
            storeName = "京东商城",
            items = listOf(DraftItem(amount = "39.90", ratePercent = "13", name = "抽纸")),
            declaredTotal = "39.90",
            paymentStatus = PaymentStatus.PAID,
            fromVision = true,
        )
        assertNull(draft.validationMessage())
        assertTrue(ReceiptItemDuplicates.find(draft.items).isEmpty())

        val candidate = Receipt(UUID.randomUUID().toString(), draft.storeName, draft.timestamp, draft.calculated())
        assertTrue(ReceiptDuplicates.find(candidate, emptyList()).isEmpty())
    }

    @Test
    fun autoAllocateDiscountOnRealisticEcommerceBillIsEligible() {
        val b1 = ScannedBill(
            id = "mock-jd-1",
            session = ReceiptScanSession().append(
                VisionReceipt(
                    storeName = "京东自营旗舰店",
                    items = listOf(
                        DraftItem(name = "罗技无线鼠标 MX Master 3S", amount = "599.00"),
                        DraftItem(name = "大号精密锁边鼠标垫", amount = "39.00"),
                    ),
                    declaredTotal = "588.00",
                    receiptDateTime = "2026-09-17T14:30:00",
                    paymentStatus = PaymentStatus.PAID,
                )
            ),
            outcome = ScanBillOutcome.PENDING
        )
        val draft = b1.draft
        val candidate = draft.allocateDiscount()
        assertNull(candidate.validationMessage())
        assertEquals(58800L, candidate.calculated().sumOf { it.breakdown.amountCents })
        assertEquals("552.06", candidate.settledItems()[0].amount)
        assertEquals("35.94", candidate.settledItems()[1].amount)
    }

    @Test
    fun unallocatedDiscountIsAutoAllocatedAndEligible() {
        val draft = ReceiptDraft(
            storeName = "天猫超市",
            items = listOf(
                DraftItem(id = "1", amount = "30.00", ratePercent = "13", name = "洗洁精"),
                DraftItem(id = "2", amount = "20.00", ratePercent = "13", name = "纸巾"),
            ),
            declaredTotal = "40.00",
            receiptDiscount = ReceiptDiscount(1000, "满减 10.00"),
            paymentStatus = PaymentStatus.PAID,
            fromVision = true,
        )
        // Before allocation, validationMessage complains about the difference
        assertNotNull(draft.validationMessage())

        // After allocation via largest remainder method
        val allocated = draft.allocateDiscount()
        assertNull(allocated.validationMessage())
        assertEquals("24.00", allocated.settledItems()[0].amount)
        assertEquals("16.00", allocated.settledItems()[1].amount)
        assertEquals(4000L, allocated.calculated().sumOf { it.breakdown.amountCents })
    }

    @Test
    fun unpaidBillIsFlaggedAsIneligible() {
        val draft = ReceiptDraft(
            storeName = "拼多多",
            items = listOf(DraftItem(amount = "15.00", ratePercent = "13", name = "数据线")),
            declaredTotal = "15.00",
            paymentStatus = PaymentStatus.UNPAID,
            fromVision = true,
        )
        assertNotNull(draft.validationMessage())
        assertTrue(draft.paymentStatus != PaymentStatus.PAID && !draft.paymentConfirmed)
    }

    @Test
    fun unknownPaymentStatusIsFlaggedAsIneligible() {
        val draft = ReceiptDraft(
            storeName = "淘宝",
            items = listOf(DraftItem(amount = "25.00", ratePercent = "13", name = "手机壳")),
            declaredTotal = "25.00",
            paymentStatus = PaymentStatus.UNKNOWN,
            fromVision = true,
        )
        assertTrue(draft.paymentStatus != PaymentStatus.PAID && !draft.paymentConfirmed)
    }

    @Test
    fun duplicateItemsInDraftAreDetected() {
        val draft = ReceiptDraft(
            storeName = "美团外卖",
            items = listOf(
                DraftItem(id = "1", name = "招牌牛肉面大碗", amount = "28.00"),
                DraftItem(id = "2", name = "招牌牛肉面大碗", amount = "28.00"),
            ),
            declaredTotal = "56.00",
            paymentStatus = PaymentStatus.PAID,
            fromVision = true,
        )
        val duplicates = ReceiptItemDuplicates.find(draft.items)
        assertEquals(1, duplicates.size)
        assertEquals(2, duplicates.first().items.size)
    }

    @Test
    fun duplicateAgainstLedgerIsDetected() {
        val draft = ReceiptDraft(
            storeName = "山姆会员商店",
            timestamp = 1700000000000L,
            items = listOf(DraftItem(name = "烘烤牛肉干", amount = "99.00", ratePercent = "13")),
            declaredTotal = "99.00",
            paymentStatus = PaymentStatus.PAID,
            fromVision = true,
        )
        val existing = Receipt(
            id = "existing-1",
            storeName = "山姆会员商店",
            timestamp = 1700000000000L,
            items = draft.calculated(),
        )
        val candidate = Receipt(UUID.randomUUID().toString(), draft.storeName, draft.timestamp, draft.calculated())
        val matches = ReceiptDuplicates.find(candidate, listOf(existing))
        assertEquals(1, matches.size)
        assertEquals("existing-1", matches.first().id)
    }

    @Test
    fun batchReviewSummaryCalculatesTotalsAndCounts() {
        val draft1 = ReceiptDraft(
            storeName = "A",
            items = listOf(DraftItem(amount = "50.00", ratePercent = "13")),
            paymentStatus = PaymentStatus.PAID,
        )
        val draft2 = ReceiptDraft(
            storeName = "B",
            items = listOf(DraftItem(amount = "30.00", ratePercent = "13")),
            paymentStatus = PaymentStatus.PAID,
        )
        val summary = BatchReviewSummary(
            eligible = listOf(
                BatchReviewEligibleItem("b1", draft1, false),
                BatchReviewEligibleItem("b2", draft2, true),
            ),
            ineligible = listOf(
                BatchReviewIneligibleItem("b3", ReceiptDraft(storeName = "C"), "待付款订单需人工核实")
            ),
        )
        assertEquals(8000L, summary.totalEligibleCents)
        assertEquals("80.00", TaxCalculator.formatMoney(summary.totalEligibleCents))
        assertEquals(1, summary.withDiscountsCount)
        assertEquals(2, summary.eligible.size)
        assertEquals(1, summary.ineligible.size)
    }

    @Test
    fun planBooksBalancedPaidBillAndLeavesUnpaidOneForManualReview() {
        val summary = BatchReview.plan(
            pendingBills = listOf(
                scannedBill("paid", storeName = "全家便利店", amount = "12.50"),
                scannedBill("unpaid", storeName = "美团外卖", amount = "28.00", paymentStatus = PaymentStatus.UNPAID),
            ),
            existingReceipts = emptyList(),
        )
        assertEquals(listOf("paid"), summary.eligible.map { it.billId })
        assertEquals(listOf("unpaid"), summary.ineligible.map { it.billId })
        assertEquals("待付款订单需人工核实", summary.ineligible.single().reason)
        assertEquals(1250L, summary.totalEligibleCents)
        assertEquals(0, summary.withDiscountsCount)
    }

    @Test
    fun planRejectsTheSecondCopyOfOneOrderInsideTheSameBatch() {
        val summary = BatchReview.plan(
            pendingBills = listOf(
                scannedBill("copy-1", storeName = "山姆会员商店", amount = "99.00"),
                scannedBill("copy-2", storeName = "山姆会员商店", amount = "99.00"),
            ),
            existingReceipts = emptyList(),
        )
        assertEquals(listOf("copy-1"), summary.eligible.map { it.billId })
        assertEquals(listOf("copy-2"), summary.ineligible.map { it.billId })
        assertEquals("与本次识别的其他账单疑似重复", summary.ineligible.single().reason)
    }

    @Test
    fun planKeepsDifferentOrdersOfOneBatchEligible() {
        val summary = BatchReview.plan(
            pendingBills = listOf(
                scannedBill("a", storeName = "盒马鲜生", amount = "30.00", receiptDateTime = "2026-09-17T09:15:00"),
                scannedBill("b", storeName = "苏宁易购", amount = "88.00", receiptDateTime = "2026-09-17T10:45:00"),
            ),
            existingReceipts = emptyList(),
        )
        assertEquals(listOf("a", "b"), summary.eligible.map { it.billId })
        assertTrue(summary.ineligible.isEmpty())
        assertEquals(11800L, summary.totalEligibleCents)
    }

    @Test
    fun planRejectsBillThatIsAlreadyInTheLedger() {
        val bill = scannedBill("again", storeName = "山姆会员商店", amount = "99.00")
        val existing = Receipt("ledger-1", bill.draft.storeName, bill.draft.timestamp, bill.draft.calculated())

        val summary = BatchReview.plan(listOf(bill), listOf(existing))

        assertTrue(summary.eligible.isEmpty())
        assertEquals("疑似与已有账单重复", summary.ineligible.single().reason)
    }

    @Test
    fun planAllocatesThePaidTotalBeforeBooking() {
        val summary = BatchReview.plan(
            pendingBills = listOf(scannedBill("discount", storeName = "天猫超市", amount = "30.00", declaredTotal = "20.00")),
            existingReceipts = emptyList(),
        )
        val booked = summary.eligible.single()
        assertTrue(booked.hasAllocatedDiscount)
        assertEquals(2000L, booked.settledDraft.calculated().sumOf { it.breakdown.amountCents })
        assertEquals(2000L, summary.totalEligibleCents)
    }

    @Test
    fun planRejectsBillWithDuplicateItemRows() {
        val session = ReceiptScanSession().append(
            VisionReceipt(
                storeName = "美团外卖",
                items = listOf(
                    DraftItem(name = "招牌牛肉面大碗", amount = "28.00"),
                    DraftItem(name = "招牌牛肉面大碗", amount = "28.00"),
                ),
                declaredTotal = "56.00",
                paymentStatus = PaymentStatus.PAID,
            )
        )
        val summary = BatchReview.plan(listOf(ScannedBill(id = "dup-items", session = session)), emptyList())
        assertTrue(summary.eligible.isEmpty())
        assertEquals("含疑似重复商品需确认", summary.ineligible.single().reason)
    }

    @Test
    fun planBooksUnknownPaymentStatusOnlyAfterTheUserConfirms() {
        val session = ReceiptScanSession().append(
            VisionReceipt(
                storeName = "淘宝",
                items = listOf(DraftItem(name = "手机壳", amount = "25.00")),
                declaredTotal = "25.00",
                paymentStatus = PaymentStatus.UNKNOWN,
            )
        )
        val confirmed = ScannedBill(id = "confirmed", session = session, draft = session.draft().copy(paymentConfirmed = true))

        assertEquals(listOf("confirmed"), BatchReview.plan(listOf(confirmed), emptyList()).eligible.map { it.billId })
        assertEquals(listOf("guessed"), BatchReview.plan(listOf(ScannedBill(id = "guessed", session = session)), emptyList()).ineligible.map { it.billId })
    }

    private fun scannedBill(
        id: String,
        storeName: String,
        amount: String,
        declaredTotal: String = amount,
        paymentStatus: PaymentStatus = PaymentStatus.PAID,
        receiptDateTime: String = "2026-09-17T14:30:00",
    ): ScannedBill = ScannedBill(
        id = id,
        session = ReceiptScanSession().append(
            VisionReceipt(
                storeName = storeName,
                items = listOf(DraftItem(name = "$storeName 商品", amount = amount)),
                declaredTotal = declaredTotal,
                receiptDateTime = receiptDateTime,
                paymentStatus = paymentStatus,
            )
        ),
    )
}
