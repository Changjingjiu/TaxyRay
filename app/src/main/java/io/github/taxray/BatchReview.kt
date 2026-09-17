package io.github.taxray

import io.github.taxray.core.Receipt
import io.github.taxray.core.ReceiptDuplicates
import io.github.taxray.core.ReceiptItemDuplicates
import io.github.taxray.data.remote.PaymentStatus

data class BatchReviewEligibleItem(val billId: String, val settledDraft: ReceiptDraft, val hasAllocatedDiscount: Boolean)
data class BatchReviewIneligibleItem(val billId: String, val draft: ReceiptDraft, val reason: String)
data class BatchReviewSummary(
    val eligible: List<BatchReviewEligibleItem>,
    val ineligible: List<BatchReviewIneligibleItem>,
) {
    val totalEligibleCents: Long
        get() = eligible.sumOf { it.settledDraft.calculated().sumOf { item -> item.breakdown.amountCents } }
    val withDiscountsCount: Int
        get() = eligible.count { it.hasAllocatedDiscount }
}

/** Which pending bills a one-tap review may book. Pure decision logic, so it stays unit-testable. */
object BatchReview {
    fun plan(pendingBills: List<ScannedBill>, existingReceipts: List<Receipt>): BatchReviewSummary {
        val eligible = mutableListOf<BatchReviewEligibleItem>()
        val ineligible = mutableListOf<BatchReviewIneligibleItem>()
        val accepted = mutableListOf<Receipt>()

        for (bill in pendingBills) {
            val draft = bill.draft
            if (draft.paymentStatus != PaymentStatus.PAID && !draft.paymentConfirmed) {
                val reason = if (draft.paymentStatus == PaymentStatus.UNPAID) "待付款订单需人工核实" else "付款状态不明确需人工核实"
                ineligible += BatchReviewIneligibleItem(bill.id, draft, reason)
                continue
            }
            if (ReceiptItemDuplicates.find(draft.items).isNotEmpty()) {
                ineligible += BatchReviewIneligibleItem(bill.id, draft, "含疑似重复商品需确认")
                continue
            }
            val needsAllocation = draft.validationMessage() != null
            val settledDraft = if (needsAllocation) allocatePaidTotal(draft) else draft
            if (settledDraft == null) {
                ineligible += BatchReviewIneligibleItem(bill.id, draft, draft.validationMessage() ?: "账单金额未确认")
                continue
            }
            val candidate = Receipt(bill.id, settledDraft.storeName, settledDraft.timestamp, settledDraft.calculated())
            if (ReceiptDuplicates.find(candidate, existingReceipts).isNotEmpty()) {
                ineligible += BatchReviewIneligibleItem(bill.id, draft, "疑似与已有账单重复")
                continue
            }
            // Bills of one session are compared with each other as well: the ledger cannot catch
            // two copies of the same order that this single batch would write together.
            if (ReceiptDuplicates.find(candidate, accepted).isNotEmpty()) {
                ineligible += BatchReviewIneligibleItem(bill.id, draft, "与本次识别的其他账单疑似重复")
                continue
            }
            accepted += candidate
            eligible += BatchReviewEligibleItem(bill.id, settledDraft, needsAllocation)
        }
        return BatchReviewSummary(eligible, ineligible)
    }

    /** The paid total is allocated across the rows only when the draft cannot be booked as entered. */
    private fun allocatePaidTotal(draft: ReceiptDraft): ReceiptDraft? {
        if (draft.declaredTotal.isBlank()) return null
        val allocated = runCatching { draft.allocateDiscount() }.getOrNull() ?: return null
        return allocated.takeIf { it.validationMessage() == null }
    }
}
