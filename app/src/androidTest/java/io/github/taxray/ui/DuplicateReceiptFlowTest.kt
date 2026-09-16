package io.github.taxray.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taxray.MainActivity
import io.github.taxray.TaxyRayApplication
import io.github.taxray.TaxyRayViewModel
import io.github.taxray.core.DraftItem
import io.github.taxray.core.Receipt
import io.github.taxray.data.remote.PaymentStatus
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Draft confirmation crosses the real Activity, ViewModel and Room boundary without network calls. */
@RunWith(AndroidJUnit4::class)
class DuplicateReceiptFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store = "DUPLICATE-TEST-${UUID.randomUUID()}"
    private val app get() = ApplicationProvider.getApplicationContext<TaxyRayApplication>()
    private val vm get() = ViewModelProvider(compose.activity)[TaxyRayViewModel::class.java]
    private var baseline = emptyList<Receipt>()
    private lateinit var original: Receipt

    @Before fun seedOnlyThisTestsReceipt() {
        baseline = runBlocking { app.receipts.all() }
        val id = runBlocking {
            app.receipts.save(store, items(), timestamp = System.currentTimeMillis() - 60_000)
        }
        original = runBlocking { app.receipts.all() }.single { it.id == id }
    }

    @After fun removeOnlyThisTestsReceipts() {
        compose.waitUntil(10_000) { !vm.busy }
        compose.runOnIdle { vm.dismissDuplicateReview(); vm.closeEditor() }
        runBlocking {
            app.receipts.all().filter { it.storeName == store }.forEach { app.receipts.delete(it.id) }
            assertEquals(baseline.associateBy { it.id }, app.receipts.all().associateBy { it.id })
        }
    }

    @Test fun duplicateNeedsConfirmationAndCancelPreservesAnEditableDraft() {
        openMatchingDraft()
        compose.onNodeWithTag("saveReceipt").performClick()
        awaitDuplicateDialog()
        assertEquals(listOf(original), ownReceipts())

        compose.onNodeWithText("返回核对").performClick()
        compose.runOnIdle {
            assertNull(vm.duplicateReview)
            assertNotNull(vm.editor)
            assertEquals(store, vm.editor!!.storeName)
            assertEquals(items().map { it.amount }, vm.editor!!.items.map { it.amount })
        }
        compose.onNodeWithTag("saveReceipt").assertIsDisplayed().assertIsEnabled()
        assertEquals(listOf(original), ownReceipts())

        compose.onNodeWithTag("saveReceipt").performClick()
        awaitDuplicateDialog()
        compose.onNodeWithText("仍然录入").performClick()
        awaitSavedCount(2)
        val savedIds = ownReceipts().map { it.id }.toSet()
        assertTrue(original.id in savedIds)

        // Even an AI-origin draft with an existing ID is an edit, not a new ledger entry.
        compose.runOnIdle {
            vm.edit(original)
            vm.updateDraft(vm.editor!!.copy(fromVision = true, paymentStatus = PaymentStatus.PAID))
        }
        compose.onNodeWithTag("saveReceipt").performClick()
        awaitSavedCount(2)
        compose.runOnIdle { assertNull(vm.duplicateReview) }
        assertEquals(savedIds, ownReceipts().map { it.id }.toSet())
        assertEquals(original, ownReceipts().single { it.id == original.id })
    }

    @Test fun repeatedConfirmationCreatesOnlyOneAdditionalReceipt() {
        openMatchingDraft()
        compose.onNodeWithTag("saveReceipt").performClick()
        awaitDuplicateDialog()
        // Deliver both events in the same main-thread turn to exercise the busy guard.
        compose.runOnIdle { vm.confirmDuplicateReceipt(); vm.confirmDuplicateReceipt() }
        awaitSavedCount(2)
        compose.runOnIdle { vm.confirmDuplicateReceipt() }
        compose.waitForIdle()
        assertEquals(2, ownReceipts().size)
        assertEquals(2, ownReceipts().map { it.id }.distinct().size)
    }

    private fun items() = listOf(
        DraftItem(name = "测试商品甲", amount = "11.30", ratePercent = "13"),
        DraftItem(name = "测试商品乙", amount = "10.90", ratePercent = "9"),
    )

    private fun openMatchingDraft() = compose.runOnIdle {
        vm.newReceipt()
        vm.updateDraft(vm.editor!!.copy(storeName = store, timestamp = original.timestamp,
            items = items(), fromVision = true, paymentStatus = PaymentStatus.PAID))
    }

    private fun awaitDuplicateDialog() {
        compose.waitUntil(10_000) { vm.duplicateReview != null && !vm.busy }
        compose.onNodeWithText("可能已录入这笔账单").assertIsDisplayed()
        compose.onNodeWithText("仍然录入").assertIsEnabled()
    }

    private fun awaitSavedCount(count: Int) {
        compose.waitUntil(10_000) { !vm.busy && vm.editor == null && ownReceipts().size == count }
    }

    private fun ownReceipts() = runBlocking { app.receipts.all() }.filter { it.storeName == store }
}
