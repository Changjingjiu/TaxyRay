package io.github.taxray.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taxray.ReceiptScanSession
import io.github.taxray.ScanBillOutcome
import io.github.taxray.ScannedBill
import io.github.taxray.core.DraftItem
import io.github.taxray.data.remote.PaymentStatus
import io.github.taxray.data.remote.VisionReceipt
import io.github.taxray.ui.screens.BillBatchReviewSheet
import io.github.taxray.ui.screens.ScannerReviewSheet
import io.github.taxray.ui.theme.TaxyRayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic review drafts only; callbacks never launch a camera or write a ledger. */
@RunWith(AndroidJUnit4::class)
class BillBatchReviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun separateOrdersExposePaymentStatusAndActionsTargetTheSelectedBill() {
        var bills by mutableStateOf(listOf(bill("paid"), bill("unpaid", PaymentStatus.UNPAID), bill("unknown", PaymentStatus.UNKNOWN)))
        val reviewed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val restored = mutableListOf<String>()
        val supplemented = mutableListOf<String>()
        compose.setContent {
            TaxyRayTheme {
                BillBatchReviewSheet(bills, emptyList(), busy = false,
                    onReview = { reviewed += it },
                    onSkip = { id -> skipped += id; bills = bills.map { if (it.id == id) it.copy(outcome = ScanBillOutcome.SKIPPED) else it } },
                    onRestore = { id -> restored += id; bills = bills.map { if (it.id == id) it.copy(outcome = ScanBillOutcome.PENDING) else it } },
                    onSupplement = { supplemented += it }, onAddImages = {}, onFinish = {})
            }
        }
        compose.onNodeWithText("识别到 3 笔账单").assertIsDisplayed()
        compose.onNodeWithText("待核对 3 笔  已入账 0 笔  已跳过 0 笔").assertIsDisplayed()
        scrollToBill("paid")
        compose.onNodeWithText("已付款 待核对 · 1 项 · 实付 ¥11.30").assertIsDisplayed()
        compose.onNodeWithTag("reviewBill-paid").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNode(hasText("补拍这笔") and hasAnyAncestor(hasTestTag("scanBill-paid"))).performClick()
        scrollToBill("unpaid")
        compose.onNodeWithText("未付款 · 1 项 · 实付 ¥0.00").assertIsDisplayed()
        compose.onNodeWithTag("skipBill-unpaid").performClick()
        compose.onNodeWithText("待核对 2 笔  已入账 0 笔  已跳过 1 笔").assertIsDisplayed()
        compose.onNode(hasText("恢复核对") and hasAnyAncestor(hasTestTag("scanBill-unpaid"))).performClick()
        compose.onNodeWithText("待核对 3 笔  已入账 0 笔  已跳过 0 笔").assertIsDisplayed()
        scrollToBill("unknown")
        compose.onNodeWithText("付款待确认 · 1 项 · 实付 ¥11.30").assertIsDisplayed()
        compose.onNodeWithTag("reviewBill-unknown").performClick()
        compose.runOnIdle {
            assertEquals(listOf("paid", "unknown"), reviewed)
            assertEquals(listOf("unpaid"), skipped)
            assertEquals(listOf("unpaid"), restored)
            assertEquals(listOf("paid"), supplemented)
        }
    }

    @Test fun busyDisablesBillAndFooterActions() {
        var busy by mutableStateOf(true)
        var actionCount = 0
        compose.setContent {
            TaxyRayTheme {
                BillBatchReviewSheet(listOf(bill("pending")), emptyList(), busy,
                    onReview = { actionCount++ }, onSkip = { actionCount++ }, onRestore = { actionCount++ },
                    onSupplement = { actionCount++ }, onAddImages = { actionCount++ }, onFinish = { actionCount++ })
            }
        }
        compose.onNodeWithTag("reviewBill-pending").assertIsNotEnabled()
        compose.onNodeWithTag("skipBill-pending").assertIsNotEnabled()
        compose.onNodeWithText("补拍这笔").assertIsNotEnabled()
        compose.onNodeWithTag("addBillImages").assertIsNotEnabled()
        compose.onNodeWithTag("finishBillBatch").assertIsNotEnabled()
        compose.onNodeWithContentDescription("关闭账单复核").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, actionCount); busy = false }
        compose.onNodeWithTag("addBillImages").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, actionCount) }
    }

    @Test fun finishingWithPendingDraftsRequiresConfirmationAndCanReturnToReview() {
        var finishCount = 0
        compose.setContent {
            TaxyRayTheme {
                BillBatchReviewSheet(listOf(bill("pending"), bill("saved", outcome = ScanBillOutcome.SAVED)), emptyList(), busy = false,
                    onReview = {}, onSkip = {}, onRestore = {}, onSupplement = {}, onAddImages = {}, onFinish = { finishCount++ })
            }
        }
        compose.onNodeWithTag("finishBillBatch").performClick()
        compose.onNodeWithText("结束本次识别？").assertIsDisplayed()
        compose.onNodeWithText("还有 1 笔未核对 结束后将放弃这些草稿\n已确认入账的账单会保留").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, finishCount) }
        compose.onNodeWithText("继续核对").performClick()
        compose.onNodeWithTag("reviewBill-pending").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭账单复核").performClick()
        compose.onNodeWithText("结束并放弃草稿").performClick()
        compose.runOnIdle { assertEquals(1, finishCount) }
    }

    @Test fun savedBillsHaveNoReviewActionsAndAnAccountedBatchCanFinishDirectly() {
        var finishCount = 0
        compose.setContent {
            TaxyRayTheme {
                BillBatchReviewSheet(listOf(bill("saved", outcome = ScanBillOutcome.SAVED), bill("skipped", outcome = ScanBillOutcome.SKIPPED)),
                    emptyList(), busy = false, onReview = {}, onSkip = {}, onRestore = {}, onSupplement = {}, onAddImages = {},
                    onFinish = { finishCount++ })
            }
        }
        compose.onNodeWithText("待核对 0 笔  已入账 1 笔  已跳过 1 笔").assertIsDisplayed()
        compose.onNodeWithTag("reviewBill-saved").assertDoesNotExist()
        compose.onNodeWithTag("skipBill-saved").assertDoesNotExist()
        compose.onNodeWithTag("finishBillBatch").performClick()
        compose.onNodeWithText("结束本次识别？").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, finishCount) }
    }

    @Test fun largeBatchesScrollAtLargeFontWithoutHidingFooterActions() {
        var reviewed: String? = null
        var addCount = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                TaxyRayTheme {
                    BillBatchReviewSheet(List(30) { bill("order-$it") }, listOf("合成批次提示 请逐笔核对实付"), busy = false,
                        onReview = { reviewed = it }, onSkip = {}, onRestore = {}, onSupplement = {},
                        onAddImages = { addCount++ }, onFinish = {})
                }
            }
        }
        compose.onNodeWithTag("addBillImages").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("finishBillBatch").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        scrollToBill("order-29")
        compose.onNodeWithTag("reviewBill-order-29").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("addBillImages").assertIsDisplayed().performClick()
        compose.onNodeWithTag("finishBillBatch").assertIsDisplayed()
        compose.runOnIdle { assertEquals("order-29", reviewed); assertEquals(1, addCount) }
    }

    @Test fun unpaidZeroCannotBecomeAFullDiscountAndReturningPreservesTheDraft() {
        var draft by mutableStateOf(bill("unpaid", PaymentStatus.UNPAID).draft)
        var returnCount = 0
        compose.setContent {
            TaxyRayTheme {
                ScannerReviewSheet(draft, busy = false, onChange = { draft = it },
                    onDismiss = { returnCount++ }, onSave = {}, returnToBatch = true)
            }
        }
        compose.onNodeWithTag("confirmBillPayment").assertIsOff().assertHeightIsAtLeast(48.dp)
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasTestTag("allocateDiscount"))
        compose.onNodeWithTag("allocateDiscount").assertIsNotEnabled()
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasTestTag("confirmBillPayment"))
        compose.onNodeWithTag("confirmBillPayment").performClick().assertIsOn()
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasTestTag("allocateDiscount"))
        compose.onNodeWithTag("allocateDiscount").assertIsNotEnabled()
        compose.onNodeWithContentDescription("返回待核对账单").performClick()
        compose.onNodeWithText("放弃这次修改？").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, returnCount)
            assertEquals(true, draft.paymentConfirmed)
            assertEquals("0.00", draft.declaredTotal)
            assertEquals(null, draft.appliedDiscount)
        }
    }

    private fun scrollToBill(id: String) {
        compose.onNodeWithTag("billBatchList").performScrollToNode(hasTestTag("scanBill-$id"))
    }

    private fun bill(id: String, status: PaymentStatus = PaymentStatus.PAID,
        outcome: ScanBillOutcome = ScanBillOutcome.PENDING): ScannedBill = ScannedBill(
        id = id,
        session = ReceiptScanSession().append(VisionReceipt(
            storeName = "合成商户 $id", items = listOf(DraftItem(name = "合成测试商品", amount = "11.30")),
            declaredTotal = if (status == PaymentStatus.UNPAID) "0.00" else "11.30",
            receiptDateTime = "2026-09-16T12:00:00", paymentStatus = status,
        )),
        outcome = outcome,
    )
}
