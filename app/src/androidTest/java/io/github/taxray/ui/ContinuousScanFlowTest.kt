package io.github.taxray.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taxray.MainActivity
import io.github.taxray.TaxyRayApplication
import io.github.taxray.TaxyRayViewModel
import io.github.taxray.core.DraftItem
import io.github.taxray.core.Receipt
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

/** Exercises real ViewModel / Room boundaries with parsed AI drafts, without making API requests. */
@RunWith(AndroidJUnit4::class)
class ContinuousScanFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store = "CONTINUOUS-SCAN-${UUID.randomUUID()}"
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
            app.receipts.all().filter { it.storeName == store }.forEach { app.receipts.delete(it.id) }
            assertEquals(baseline.associateBy { it.id }, app.receipts.all().associateBy { it.id })
        }
    }

    @Test fun continuingAndCancellingPhotographyKeepEarlierRowsUntilExplicitDedupAndSave() {
        val firstRound = receipt(
            listOf(item("商品甲", "11.30", "13"), item("商品乙", "10.90", "9")), "32.10",
        )
        compose.runOnIdle {
            vm.beginScan()
            vm.acceptScanRound(firstRound)
            assertTrue(vm.scanRoundReady)
            assertNull(vm.scanError)
            assertNull(vm.editor)
            assertEquals(1, vm.scanSession!!.rounds.size)
        }
        assertTrue(ownReceipts().isEmpty())

        val earlierItems = compose.runOnIdle { vm.scanSession!!.items }
        compose.runOnIdle {
            vm.prepareNextScanRound()
            assertFalse(vm.scanRoundReady)
            assertEquals(earlierItems, vm.scanSession!!.items)
            vm.cancelPhotoSelection()
            assertTrue(vm.scanRoundReady)
            assertEquals(earlierItems, vm.scanSession!!.items)
            assertNull(vm.scanError)
            vm.prepareNextScanRound()
            vm.acceptScanRound(receipt(
                listOf(item("商品乙", "10.90", "9"), item("商品丙", "9.90", "6")), "32.10",
            ))
            assertTrue(vm.scanRoundReady)
            assertEquals(2, vm.scanSession!!.rounds.size)
            assertEquals(earlierItems, vm.scanSession!!.items.take(2))
        }
        finishAndAwaitScanReview()
        compose.runOnIdle {
            assertNull(vm.editor)
            assertFalse(vm.scanRoundReady)
            val review = requireNotNull(vm.scanDuplicateReview)
            assertEquals(1, review.groups.size)
            assertEquals(listOf("商品乙", "商品乙"), review.groups.single().items.map { it.name })
        }
        assertTrue(ownReceipts().isEmpty())

        compose.runOnIdle {
            val groupId = vm.scanDuplicateReview!!.groups.single().items.first().id
            vm.resolveScanDuplicate(groupId = groupId, keepId = earlierItems[1].id)
            assertNull(vm.scanDuplicateReview)
            assertNull(vm.scanSession)
            val draft = requireNotNull(vm.editor)
            assertEquals(listOf("商品甲", "商品乙", "商品丙"), draft.items.map { it.name })
            assertEquals(earlierItems[1].id, draft.items[1].id)
            assertEquals(listOf("11.30", "10.90", "9.90"), draft.items.map { it.amount })
            assertEquals("32.10", draft.declaredTotal)
            assertEquals(3_210L, draft.calculated().sumOf { it.breakdown.amountCents })
            assertNull(draft.validationMessage())
            assertNull(draft.appliedDiscount)
        }
        assertTrue("Even deduplicated recognition is only a draft", ownReceipts().isEmpty())

        compose.runOnIdle { vm.saveReceipt() }
        awaitSavedCount(1)
        val saved = ownReceipts().single()
        assertEquals(3_210L, saved.totalAmountCents)
        assertEquals(276L, saved.totalTaxCents)
        assertEquals(listOf("商品甲", "商品乙", "商品丙"), saved.items.map { it.name })
    }

    @Test fun keepingAllRetainsRealRepeatPurchasesAndEveryOriginalAmount() {
        val originalItems = compose.runOnIdle {
            vm.beginScan()
            vm.acceptScanRound(receipt(listOf(item("原味酸奶", "11.30")), "22.60"))
            vm.prepareNextScanRound()
            vm.acceptScanRound(receipt(listOf(item("原味酸奶", "11.30")), "22.60"))
            vm.scanSession!!.items
        }
        finishAndAwaitScanReview()
        compose.runOnIdle {
            assertNotNull(vm.scanDuplicateReview)
            val groupId = vm.scanDuplicateReview!!.groups.single().items.first().id
            vm.resolveScanDuplicate(groupId = groupId)
            val draft = requireNotNull(vm.editor)
            assertEquals(originalItems, draft.items)
            assertEquals(2, draft.items.map { it.id }.distinct().size)
            assertEquals(listOf("11.30", "11.30"), draft.items.map { it.amount })
            assertEquals("22.60", draft.declaredTotal)
            assertEquals(2_260L, draft.calculated().sumOf { it.breakdown.amountCents })
            assertNull(draft.appliedDiscount)
            assertNull(draft.validationMessage())
        }
        assertTrue(ownReceipts().isEmpty())
        compose.runOnIdle { vm.saveReceipt() }
        awaitSavedCount(1)
        assertEquals(2, ownReceipts().single().items.size)
        assertEquals(2_260L, ownReceipts().single().totalAmountCents)
    }

    @Test fun keepingAllDoesNotSilentlyRewriteTheTicketTotalOrApplyADiscount() {
        compose.runOnIdle {
            vm.beginScan()
            repeat(2) {
                vm.prepareNextScanRound()
                vm.acceptScanRound(receipt(listOf(item("原味酸奶", "11.30")), "11.30"))
            }
        }
        finishAndAwaitScanReview()
        compose.runOnIdle {
            val groupId = vm.scanDuplicateReview!!.groups.single().items.first().id
            vm.resolveScanDuplicate(groupId = groupId)
            val draft = requireNotNull(vm.editor)
            assertEquals(2, draft.items.size)
            assertEquals("11.30", draft.declaredTotal)
            assertEquals(2_260L, draft.calculated().sumOf { it.breakdown.amountCents })
            assertNull(draft.appliedDiscount)
            assertNotNull(draft.validationMessage())
            vm.saveReceipt()
            assertEquals(draft, vm.editor)
            assertFalse(vm.busy)
        }
        assertTrue(ownReceipts().isEmpty())
    }

    @Test fun eachDuplicateGroupNeedsItsOwnDecisionAndEarlierDecisionsRemainApplied() {
        val originalItems = compose.runOnIdle {
            vm.beginScan()
            repeat(2) {
                vm.prepareNextScanRound()
                vm.acceptScanRound(receipt(
                    listOf(item("商品甲", "11.30"), item("商品乙", "10.90"), item("商品丙", "9.90")), "43.00",
                ))
            }
            vm.scanSession!!.items
        }
        finishAndAwaitScanReview()
        compose.runOnIdle {
            val groups = vm.scanDuplicateReview!!.groups
            assertEquals(3, groups.size)
            vm.resolveScanDuplicate(groupId = groups[0].items.first().id, keepId = originalItems[0].id)
            assertNull(vm.editor)
            assertEquals(1, vm.scanDuplicateReview!!.groupIndex)
            assertEquals(5, vm.scanDuplicateReview!!.draft.items.size)

            // A second queued click for the old group must not answer the next group's question.
            val secondGroupId = groups[1].items.first().id
            vm.resolveScanDuplicate(groupId = secondGroupId)
            val afterSecondGroup = vm.scanDuplicateReview
            assertEquals(2, afterSecondGroup!!.groupIndex)
            vm.resolveScanDuplicate(groupId = secondGroupId)
            assertEquals(afterSecondGroup, vm.scanDuplicateReview)
            assertNull(vm.editor)
            assertEquals(5, vm.scanDuplicateReview!!.draft.items.size)

            vm.resolveScanDuplicate(groupId = groups[2].items.first().id, keepId = originalItems[2].id)
            assertNull(vm.scanDuplicateReview)
            val draft = requireNotNull(vm.editor)
            assertEquals(listOf(originalItems[0], originalItems[1], originalItems[2], originalItems[4]), draft.items)
            assertEquals(4_300L, draft.calculated().sumOf { it.breakdown.amountCents })
            assertNull(draft.validationMessage())
        }
        assertTrue(ownReceipts().isEmpty())
    }

    @Test fun cancellingAnEmptySessionOrDiscardingRecognizedRoundsNeverWritesTheLedger() {
        compose.runOnIdle {
            vm.beginScan()
            vm.cancelPhotoSelection()
            assertNull(vm.scanSession)
            assertFalse(vm.scanRoundReady)
            vm.beginScan()
            repeat(2) {
                vm.prepareNextScanRound()
                vm.acceptScanRound(receipt(listOf(item("原味酸奶", "11.30")), "11.30"))
            }
        }
        finishAndAwaitScanReview()
        compose.runOnIdle {
            assertNotNull(vm.scanDuplicateReview)
            vm.discardScan()
            assertNull(vm.scanSession)
            assertNull(vm.scanDuplicateReview)
            assertNull(vm.editor)
            assertNull(vm.scanError)
            assertFalse(vm.scanRoundReady)
        }
        assertTrue(ownReceipts().isEmpty())
    }

    @Test fun keepingAnExistingReceiptClosesTheDraftWithoutCreatingOrChangingAnyBill() {
        val items = listOf(item("原味酸奶", "11.30"))
        val id = runBlocking {
            app.receipts.save(store, items, timestamp = LocalDateTime.parse(receiptDateTime)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        }
        val original = ownReceipts().single { it.id == id }
        compose.runOnIdle {
            vm.beginScan()
            vm.acceptScanRound(receipt(items, "11.30"))
        }
        finishAndAwaitScanReview()
        compose.runOnIdle {
            assertNotNull(vm.editor)
            vm.saveReceipt()
        }
        compose.waitUntil(10_000) { !vm.busy && vm.duplicateReview != null }
        compose.runOnIdle {
            assertTrue(vm.duplicateReview!!.matches.any { it.id == original.id })
            vm.keepExistingReceipt()
            vm.keepExistingReceipt() // A repeated event cannot add a new receipt either.
            assertNull(vm.duplicateReview)
            assertNull(vm.editor)
        }
        assertEquals(listOf(original), ownReceipts())
    }

    @Test fun preparingAfterSessionLossCreatesAFreshSessionThatCanReceiveRecognition() {
        compose.runOnIdle {
            assertNull(vm.scanSession)
            vm.prepareNextScanRound()
            assertNotNull(vm.scanSession)
            assertTrue(vm.scanSession!!.rounds.isEmpty())
            assertFalse(vm.scanRoundReady)
            assertNull(vm.scanError)

            vm.acceptScanRound(receipt(listOf(item("重新拍摄商品", "11.30")), "11.30"))
            assertEquals(1, vm.scanSession!!.rounds.size)
            assertTrue(vm.scanRoundReady)
            assertEquals(listOf("重新拍摄商品"), vm.scanSession!!.items.map { it.name })
            assertNull(vm.editor)
        }
        assertTrue(ownReceipts().isEmpty())
        finishAndAwaitScanReview()
        compose.runOnIdle {
            assertNull(vm.scanSession)
            assertNull(vm.scanDuplicateReview)
            assertNotNull(vm.editor)
            assertEquals("11.30", vm.editor!!.declaredTotal)
            assertNull(vm.editor!!.validationMessage())
        }
        assertTrue(ownReceipts().isEmpty())
    }

    private fun item(name: String, amount: String, rate: String = "13") =
        DraftItem(name = name, amount = amount, ratePercent = rate)

    private fun receipt(items: List<DraftItem>, total: String) = VisionReceipt(
        storeName = store, items = items, declaredTotal = total, receiptDateTime = receiptDateTime,
    )

    private fun ownReceipts() = runBlocking { app.receipts.all() }.filter { it.storeName == store }

    private fun finishAndAwaitScanReview() {
        compose.runOnIdle { vm.finishScan() }
        compose.waitUntil(10_000) {
            !vm.busy && (vm.editor != null || vm.scanDuplicateReview != null)
        }
    }

    private fun awaitSavedCount(count: Int) {
        compose.waitUntil(10_000) { !vm.busy && vm.editor == null && ownReceipts().size == count }
    }
}
