package io.github.taxray.ui

import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taxray.MainActivity
import io.github.taxray.ReceiptDraft
import io.github.taxray.TaxyRayApplication
import io.github.taxray.TaxyRayViewModel
import io.github.taxray.core.DraftItem
import io.github.taxray.core.Receipt
import io.github.taxray.data.remote.PaymentStatus
import io.github.taxray.data.remote.VisionReceiptParser
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Parser -> actual review UI -> ViewModel -> Room. No network or personal receipt fixture. */
@RunWith(AndroidJUnit4::class)
class ReceiptDiscountFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store = "DISCOUNT-TEST-${UUID.randomUUID()}"
    private val app get() = ApplicationProvider.getApplicationContext<TaxyRayApplication>()
    private val vm get() = ViewModelProvider(compose.activity)[TaxyRayViewModel::class.java]
    private var baseline: List<Receipt> = emptyList()

    @Before fun baseline() { baseline = runBlocking { app.receipts.all() } }

    @After fun cleanup() = runBlocking {
        app.receipts.all().filter { it.storeName == store }.forEach { app.receipts.delete(it.id) }
        assertEquals(baseline.associateBy { it.id }, app.receipts.all().associateBy { it.id })
    }

    @Test fun roundingCanBeAppliedUndoneChangedAndSavedWithoutLosingAFen() {
        val amounts = listOf("2.68", "6.82", "5.20", "4.16", "15.88", "5.41", "1.85", "3.33", "13.91")
        val rows = amounts.mapIndexed { index, amount ->
            """{"name":"演示商品${index + 1}","amount":"$amount","category":"agricultural_product","category_evidence":"演示商品","classification_issue":"none","tax_treatment":"standard"}"""
        }.joinToString(",")
        val parsed = VisionReceiptParser.parseArguments("""{
            "receipts":[{
                "store_name":"合成测试商店","items":[$rows],"declared_total":"59.20",
                "receipt_datetime":null,"order_discount":{"amount":"0.04","evidence":"优惠 0.04"},
                "payment_status":"paid","payment_evidence":"实付59.20","source_image_indices":[1],"warnings":[]
            }],"warnings":[]
        }""", sourceImageCount = 1).receipts.single()
        show(ReceiptDraft(storeName = store, items = parsed.items,
            receiptDiscount = parsed.discount, fromVision = true,
            paymentStatus = parsed.paymentStatus, paymentEvidence = parsed.paymentEvidence))
        compose.onNodeWithTag("saveReceipt").performClick()
        val messages = compose.onAllNodesWithText("识别到整单优惠 请先填写最终实付")
        assertTrue(messages.fetchSemanticsNodes().indices.any { messages[it].isDisplayed() })
        assertTrue(runBlocking { app.receipts.all() }.none { it.storeName == store })
        tag("declaredTotal").performTextReplacement(parsed.declaredTotal!!)
        hideKeyboard()
        tag("allocateDiscount").assertIsEnabled()
        compose.onNodeWithTag("saveReceipt").performClick()
        compose.runOnIdle { assertNotNull(vm.editor) }
        assertTrue(runBlocking { app.receipts.all() }.none { it.storeName == store })

        tag("allocateDiscount").performClick()
        tag("allocationStatus").assertTextEquals("已分摊优惠 ¥0.04")
        tag("amount1").assertTextContains("6.82")
        tag("allocatedPaid1").assertTextEquals("分摊后实付 ¥6.81")
        tag("undoDiscount").performClick()
        compose.runOnIdle { assertEquals(amounts, vm.editor!!.items.map { it.amount }) }
        tag("allocateDiscount").performClick()

        tag("declaredTotal").performTextReplacement("59.21")
        hideKeyboard()
        compose.runOnIdle { assertNull(vm.editor!!.appliedDiscount) }
        tag("allocateDiscount").performClick()
        tag("declaredTotal").performTextReplacement("59.20")
        hideKeyboard()
        tag("allocateDiscount").performClick()
        compose.onNodeWithTag("saveReceipt").performClick()
        val saved = awaitSaved(5920)
        assertEquals(listOf(268L, 681L, 520L, 416L, 1587L, 540L, 185L, 333L, 1390L), saved.items.map { it.breakdown.amountCents })
        assertEquals(saved.totalAmountCents, saved.totalTaxCents + saved.totalPreTaxCents)

        compose.runOnIdle { vm.edit(saved) }
        tag("amount1").assertTextContains("6.81")
        compose.runOnIdle { assertNull(vm.editor!!.appliedDiscount) }
        compose.onNodeWithTag("saveReceipt").performClick()
        assertEquals(saved, awaitSaved(5920))
    }

    @Test fun changingAProductClearsAllocationAndSurchargesStayBlocked() {
        show(ReceiptDraft(storeName = store, declaredTotal = "9.99", fromVision = true,
            paymentStatus = PaymentStatus.PAID,
            items = listOf(DraftItem(amount = "10.00"))))
        tag("allocateDiscount").performClick()
        tag("amount0").performTextReplacement("8.00")
        hideKeyboard()
        compose.runOnIdle {
            assertNull(vm.editor!!.appliedDiscount)
            assertTrue(vm.editor!!.validationMessage()!!.contains("漏项"))
        }
        compose.onNodeWithTag("saveReceipt").performClick()
        compose.runOnIdle { assertNotNull(vm.editor) }
        assertTrue(runBlocking { app.receipts.all() }.none { it.storeName == store })
    }

    @Test fun fullDiscountKeepsZeroPriceRowsAndZeroTaxInTheLedger() {
        show(ReceiptDraft(storeName = store, declaredTotal = "0.00", fromVision = true,
            paymentStatus = PaymentStatus.PAID,
            items = listOf(DraftItem(name = "赠品一", amount = "0.01"), DraftItem(name = "赠品二", amount = "0.01"))))
        tag("allocateDiscount").performClick()
        compose.onNodeWithTag("saveReceipt").performClick()
        val saved = awaitSaved(0)
        assertEquals(2, saved.items.size)
        assertEquals(0L, saved.totalTaxCents)
        assertEquals("0.00", saved.effectiveRatePercent)
    }

    private fun show(draft: ReceiptDraft) { compose.runOnIdle { vm.updateDraft(draft) }; compose.waitForIdle() }

    private fun tag(name: String): SemanticsNodeInteraction {
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasTestTag(name))
        return compose.onNodeWithTag(name)
    }

    private fun awaitSaved(cents: Long): Receipt {
        compose.waitUntil(10_000) {
            runBlocking { app.receipts.all() }.any { it.storeName == store && it.totalAmountCents == cents } &&
                compose.onAllNodesWithTag("saveReceipt").fetchSemanticsNodes().isEmpty()
        }
        return runBlocking { app.receipts.all() }.single { it.storeName == store }
    }

    private fun hideKeyboard() {
        val roots = compose.onAllNodes(isFocused()).fetchSemanticsNodes().map { (it.root as ViewRootForTest).view }.distinct()
        val view = compose.runOnIdle { roots.single { it.hasWindowFocus() } }
        compose.runOnUiThread { checkNotNull(ViewCompat.getWindowInsetsController(view)).hide(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(5000) {
            var hidden = false
            compose.runOnUiThread { hidden = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
            hidden
        }
        compose.waitForIdle()
    }
}
