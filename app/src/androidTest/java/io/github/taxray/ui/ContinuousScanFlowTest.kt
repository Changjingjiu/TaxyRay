package io.github.taxray.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taxray.MainActivity
import io.github.taxray.ScanBillOutcome
import io.github.taxray.TaxyRayApplication
import io.github.taxray.TaxyRayViewModel
import io.github.taxray.core.DraftItem
import io.github.taxray.core.Receipt
import io.github.taxray.data.remote.PaymentStatus
import io.github.taxray.data.remote.VisionBatch
import io.github.taxray.data.remote.VisionReceipt
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real ViewModel / Room boundaries with synthetic AI drafts; never calls a model or camera. */
@RunWith(AndroidJUnit4::class)
class ContinuousScanFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store = "SYNTHETIC-BATCH-${UUID.randomUUID()}"
    private val receiptDateTime = "2026-09-16T12:00:00"
    private val app get() = ApplicationProvider.getApplicationContext<TaxyRayApplication>()
    private val vm get() = ViewModelProvider(compose.activity)[TaxyRayViewModel::class.java]
    private var baseline = emptyList<Receipt>()

    @Before fun rememberExistingLedger() {
        baseline = runBlocking { app.receipts.all() }
    }

    @After fun removeOnlyThisTestsReceipts() {
        compose.waitUntil(10_000) { !vm.busy }
        compose.runOnIdle {
            vm.discardScan()
            vm.dismissDuplicateReview()
            vm.closeEditor()
        }
        runBlocking {
            app.receipts.all().filter { it.storeName.startsWith(store) }.forEach { app.receipts.delete(it.id) }
            assertEquals(baseline.associateBy { it.id }, app.receipts.all().associateBy { it.id })
        }
    }

    @Test fun ordersWithIdenticalItemsStayIndependentAndSaveOneAtATime() {
        val ids = compose.runOnIdle {
            vm.beginScan()
            vm.acceptScanBatch(VisionBatch(listOf(
                receipt(listOf(item("合成商品甲", "11.30")), "11.30", merchant = "$store-A"),
                receipt(listOf(item("合成商品甲", "11.30")), "11.30", merchant = "$store-B"),
            )))
            val firstIds = vm.scanBills.map { it.id }
            vm.prepareNextScanRound()
            vm.acceptScanBatch(VisionBatch(listOf(
                receipt(listOf(item("合成商品乙", "9.90")), "9.90", merchant = "$store-C"),
            )))
            assertEquals(firstIds, vm.scanBills.take(2).map { it.id })
            assertTrue(vm.scanReviewReady)
            assertNull(vm.editor)
            assertNull(vm.scanDuplicateReview)
            assertEquals(3, vm.scanBills.size)
            vm.scanBills.map { it.id }
        }
        assertTrue(ownReceipts().isEmpty())
        ids.forEachIndexed { index, id ->
            reviewAndAwait(id)
            compose.runOnIdle {
                assertNull(vm.scanDuplicateReview)
                assertEquals(1, vm.editor!!.items.size)
                vm.saveReceipt()
            }
            awaitSavedCount(index + 1)
            compose.runOnIdle {
                assertTrue(vm.scanReviewReady)
                assertEquals(index + 1, vm.scanBills.count { it.outcome == ScanBillOutcome.SAVED })
                assertEquals(2 - index, vm.scanBills.count { it.outcome == ScanBillOutcome.PENDING })
            }
        }
        assertEquals(listOf(990L, 1_130L, 1_130L), ownReceipts().map { it.totalAmountCents }.sorted())
        assertEquals(3, ownReceipts().map { it.storeName }.distinct().size)
    }

    @Test fun unpaidZeroCannotBeSavedEvenAfterPaymentConfirmation() {
        val id = beginWith(receipt(listOf(item("合成待付商品", "11.30")), "0.00", PaymentStatus.UNPAID))
        reviewAndAwait(id)
        compose.runOnIdle {
            assertNotNull(vm.editor!!.validationMessage())
            vm.saveReceipt()
            assertFalse(vm.busy)
            vm.updateDraft(vm.editor!!.copy(paymentConfirmed = true))
            assertTrue(vm.editor!!.validationMessage()!!.contains("大于 0"))
            vm.saveReceipt()
            assertFalse(vm.busy)
        }
        assertTrue(ownReceipts().isEmpty())
        compose.runOnIdle {
            vm.updateDraft(vm.editor!!.copy(declaredTotal = "11.30"))
            assertNull(vm.editor!!.validationMessage())
            vm.saveReceipt()
        }
        awaitSavedCount(1)
        assertEquals(1_130L, ownReceipts().single().totalAmountCents)
    }

    @Test fun unknownPaymentNeedsAnExplicitConfirmationBeforeSaving() {
        val id = beginWith(receipt(listOf(item("合成待确认商品", "11.30")), "11.30", PaymentStatus.UNKNOWN))
        reviewAndAwait(id)
        compose.runOnIdle {
            assertFalse(vm.editor!!.paymentConfirmed)
            vm.saveReceipt()
            assertFalse(vm.busy)
            assertNotNull(vm.editor!!.validationMessage())
        }
        assertTrue(ownReceipts().isEmpty())
        compose.runOnIdle {
            vm.updateDraft(vm.editor!!.copy(paymentConfirmed = true))
            vm.saveReceipt()
        }
        awaitSavedCount(1)
    }

    @Test fun explicitSupplementKeepsEarlierRowsAndOnlyDeduplicatesWithinThatBill() {
        val id = beginWith(receipt(listOf(item("合成商品甲", "11.30", "13"), item("合成商品乙", "10.90", "9")), "32.10"))
        val earlierItems = compose.runOnIdle { vm.scanBills.single().draft.items }
        compose.runOnIdle {
            vm.prepareNextScanRound(id)
            assertFalse(vm.scanReviewReady)
            vm.cancelPhotoSelection()
            assertTrue(vm.scanReviewReady)
            assertEquals(earlierItems, vm.scanBills.single().draft.items)
            vm.prepareNextScanRound(id)
            vm.acceptScanBatch(VisionBatch(listOf(receipt(
                listOf(item("合成商品乙", "10.90", "9"), item("合成商品丙", "9.90", "6")), "32.10",
            ))))
            assertEquals(1, vm.scanBills.size)
            assertEquals(2, vm.scanBills.single().session.rounds.size)
        }
        reviewAndAwait(id)
        compose.runOnIdle {
            assertNull(vm.editor)
            val group = vm.scanDuplicateReview!!.groups.single()
            assertEquals(listOf("合成商品乙", "合成商品乙"), group.items.map { it.name })
            vm.resolveScanDuplicate(group.items.first().id, earlierItems[1].id)
            val draft = requireNotNull(vm.editor)
            assertEquals(listOf("合成商品甲", "合成商品乙", "合成商品丙"), draft.items.map { it.name })
            assertEquals(earlierItems[1].id, draft.items[1].id)
            assertEquals("32.10", draft.declaredTotal)
            assertNull(draft.appliedDiscount)
            assertNull(draft.validationMessage())
        }
        assertTrue(ownReceipts().isEmpty())
        compose.runOnIdle { vm.saveReceipt() }
        awaitSavedCount(1)
        assertEquals(3_210L, ownReceipts().single().totalAmountCents)
        assertEquals(276L, ownReceipts().single().totalTaxCents)
    }

    @Test fun keepingBothSupplementRowsNeverSilentlyRewritesTotalsOrAppliesDiscounts() {
        val id = beginWith(receipt(listOf(item("合成重复商品", "11.30")), "11.30"))
        compose.runOnIdle {
            vm.prepareNextScanRound(id)
            vm.acceptScanBatch(VisionBatch(listOf(receipt(listOf(item("合成重复商品", "11.30")), "11.30"))))
        }
        reviewAndAwait(id)
        compose.runOnIdle {
            vm.resolveScanDuplicate(vm.scanDuplicateReview!!.groups.single().items.first().id)
            val draft = requireNotNull(vm.editor)
            assertEquals(listOf("11.30", "11.30"), draft.items.map { it.amount })
            assertEquals("11.30", draft.declaredTotal)
            assertEquals(2_260L, draft.calculated().sumOf { it.breakdown.amountCents })
            assertNull(draft.appliedDiscount)
            assertNotNull(draft.validationMessage())
            vm.saveReceipt()
            assertFalse(vm.busy)
            assertEquals(draft, vm.editor)
        }
        assertTrue(ownReceipts().isEmpty())
    }

    @Test fun eachSupplementDuplicateGroupRequiresItsOwnDecision() {
        val round = receipt(listOf(item("合成商品甲", "11.30"), item("合成商品乙", "10.90"), item("合成商品丙", "9.90")), "43.00")
        val id = beginWith(round)
        val originalItems = compose.runOnIdle {
            vm.prepareNextScanRound(id)
            vm.acceptScanBatch(VisionBatch(listOf(round)))
            vm.scanBills.single().draft.items
        }
        reviewAndAwait(id)
        compose.runOnIdle {
            val groups = vm.scanDuplicateReview!!.groups
            assertEquals(3, groups.size)
            vm.resolveScanDuplicate(groups[0].items.first().id, originalItems[0].id)
            assertNull(vm.editor)
            assertEquals(1, vm.scanDuplicateReview!!.groupIndex)
            val secondGroupId = groups[1].items.first().id
            vm.resolveScanDuplicate(secondGroupId)
            val afterSecond = vm.scanDuplicateReview
            vm.resolveScanDuplicate(secondGroupId)
            assertEquals(afterSecond, vm.scanDuplicateReview)
            vm.resolveScanDuplicate(groups[2].items.first().id, originalItems[2].id)
            assertNull(vm.scanDuplicateReview)
            assertEquals(listOf(originalItems[0], originalItems[1], originalItems[2], originalItems[4]), vm.editor!!.items)
            assertNull(vm.editor!!.validationMessage())
        }
        assertTrue(ownReceipts().isEmpty())
    }

    @Test fun editsAndSkippedBillsCanBeResumedAndLeavingKeepsAlreadySavedBills() {
        val ids = compose.runOnIdle {
            vm.beginScan()
            vm.acceptScanBatch(VisionBatch(listOf(
                receipt(listOf(item("合成商品甲", "11.30")), "11.30"),
                receipt(listOf(item("合成商品乙", "9.90")), "9.90"),
            )))
            vm.scanBills.map { it.id }
        }
        compose.runOnIdle {
            vm.skipScanBill(ids[1])
            assertEquals(ScanBillOutcome.SKIPPED, vm.scanBills[1].outcome)
            vm.restoreScanBill(ids[1])
            assertEquals(ScanBillOutcome.PENDING, vm.scanBills[1].outcome)
        }
        reviewAndAwait(ids[0])
        val edited = compose.runOnIdle {
            val updated = vm.editor!!.copy(storeName = "$store-EDITED", declaredTotal = "12.30",
                items = vm.editor!!.items.map { it.copy(amount = "12.30") })
            vm.updateDraft(updated)
            vm.closeEditor()
            assertNull(vm.editor)
            assertTrue(vm.scanReviewReady)
            assertEquals(updated, vm.scanBills[0].draft)
            updated
        }
        reviewAndAwait(ids[0])
        compose.runOnIdle { assertEquals(edited, vm.editor); vm.saveReceipt() }
        awaitSavedCount(1)
        val saved = ownReceipts().single()
        compose.runOnIdle {
            assertEquals(ScanBillOutcome.SAVED, vm.scanBills[0].outcome)
            assertEquals(ScanBillOutcome.PENDING, vm.scanBills[1].outcome)
            vm.discardScan()
            assertFalse(vm.scanActive)
            assertTrue(vm.scanBills.isEmpty())
            assertFalse(vm.scanReviewReady)
            assertNull(vm.editor)
        }
        assertEquals(listOf(saved), ownReceipts())
        assertEquals(1_230L, saved.totalAmountCents)
    }

    @Test fun cancellingEmptyPhotographyOrDiscardingDraftsNeverWritesAnything() {
        compose.runOnIdle {
            vm.beginScan()
            vm.cancelPhotoSelection()
            assertFalse(vm.scanActive)
            assertTrue(vm.scanBills.isEmpty())
            vm.prepareNextScanRound()
            assertTrue(vm.scanActive)
            vm.acceptScanBatch(VisionBatch(listOf(receipt(listOf(item("合成新商品", "11.30")), "11.30"))))
            assertTrue(vm.scanReviewReady)
            vm.returnToScanReview()
            assertTrue(vm.scanReviewReady)
            vm.discardScan()
            assertFalse(vm.scanActive)
            assertTrue(vm.scanBills.isEmpty())
            assertNull(vm.scanDuplicateReview)
            assertNull(vm.editor)
        }
        assertTrue(ownReceipts().isEmpty())
    }

    @Test fun keepingExistingReceiptDoesNotCreateOrChangeAnySavedBill() {
        val name = "合成博士地毯清洁剂400ml多功能便携免水干洗布艺沙发清洁"
        val items = listOf(item(name, "11.30"))
        val id = runBlocking {
            app.receipts.save(store, items, timestamp = LocalDateTime.parse(receiptDateTime)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        }
        val original = ownReceipts().single { it.id == id }
        // A cropped merchant plus an OCR typo and repeated specification must still
        // reach explicit duplicate review, rather than silently saving another order.
        val reread = listOf(item(name.replace("博士", "博土") + " 400ml*1瓶", "11.30"))
        reviewAndAwait(beginWith(receipt(reread, "11.30", merchant = "")))
        compose.runOnIdle { vm.saveReceipt() }
        compose.waitUntil(10_000) { !vm.busy && vm.duplicateReview != null }
        compose.runOnIdle {
            assertTrue(vm.duplicateReview!!.matches.any { it.id == original.id })
            vm.keepExistingReceipt()
            vm.keepExistingReceipt()
            assertNull(vm.duplicateReview)
            assertNull(vm.editor)
            assertTrue(vm.scanReviewReady)
        }
        assertEquals(listOf(original), ownReceipts())
    }

    private fun item(name: String, amount: String, rate: String = "13") =
        DraftItem(name = name, amount = amount, ratePercent = rate)

    private fun receipt(items: List<DraftItem>, total: String, status: PaymentStatus = PaymentStatus.PAID,
        merchant: String = store) = VisionReceipt(
        storeName = merchant, items = items, declaredTotal = total, receiptDateTime = receiptDateTime,
        paymentStatus = status, paymentEvidence = when (status) {
            PaymentStatus.PAID -> "合成付款凭证 已付款"
            PaymentStatus.UNPAID -> "合成付款凭证 待付款"
            PaymentStatus.UNKNOWN -> null
        }, sourceImageIndices = listOf(1),
    )

    private fun beginWith(receipt: VisionReceipt): String = compose.runOnIdle {
        vm.beginScan()
        vm.acceptScanBatch(VisionBatch(listOf(receipt)))
        vm.scanBills.single().id
    }

    private fun ownReceipts() = runBlocking { app.receipts.all() }.filter { it.storeName.startsWith(store) }

    private fun reviewAndAwait(id: String) {
        compose.runOnIdle { vm.reviewScanBill(id) }
        compose.waitUntil(10_000) { !vm.busy && (vm.editor != null || vm.scanDuplicateReview != null) }
    }

    private fun awaitSavedCount(count: Int) {
        compose.waitUntil(10_000) { !vm.busy && vm.editor == null && ownReceipts().size == count }
    }
}
