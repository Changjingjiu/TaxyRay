package io.github.taxray

import java.util.UUID

enum class ScanBillOutcome { PENDING, SAVED, SKIPPED }

/** Each order keeps its own amounts, review edits and explicit completion state. */
data class ScannedBill(
    val session: ReceiptScanSession,
    val id: String = UUID.randomUUID().toString(),
    val draft: ReceiptDraft = session.draft(),
    val outcome: ScanBillOutcome = ScanBillOutcome.PENDING,
    val canSupplement: Boolean = true,
)
