package io.github.taxray.ui

import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taxray.MainActivity
import io.github.taxray.TaxyRayApplication
import io.github.taxray.core.Receipt
import io.github.taxray.core.TaxCalculator
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real UI + real Room, with only this run's uniquely named receipt ever deleted. */
@RunWith(AndroidJUnit4::class)
class LedgerFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val storeName = "UI-TEST-${UUID.randomUUID()}"
    private val app get() = ApplicationProvider.getApplicationContext<TaxyRayApplication>()
    private var baseline: List<Receipt> = emptyList()

    @Before fun rememberExistingData() {
        baseline = runBlocking { app.receipts.all() }
        compose.waitForIdle()
    }

    @After fun removeOnlyThisRunsReceipt() {
        runBlocking {
            app.receipts.all().filter { it.storeName == storeName }.forEach { app.receipts.delete(it.id) }
            val remaining = app.receipts.all().associateBy { it.id }
            baseline.forEach { original ->
                assertEquals("Existing receipt ${original.id} was changed by the UI test", original, remaining[original.id])
            }
            assertFalse(remaining.values.any { it.storeName == storeName })
        }
    }

    @Test fun manual13PercentReceiptCanBeSavedReviewedEditedAndDeleted() {
        compose.onNodeWithText("记一笔").performScrollTo().performClick()
        scrollToTag("storeName").performTextReplacement(storeName)
        scrollToTag("itemName0").performTextReplacement("UI 测试商品")
        scrollToTag("amount0").performTextReplacement("113.00")
        scrollToTag("rate13_0").performClick()
        hideKeyboard()
        compose.onNodeWithTag("saveReceipt").assertIsDisplayed().assertIsEnabled().performClick()
        waitForSavedAmount(11_300)

        // The dashboard's spend is displayed in one semantic node; the animated tax
        // digits are independently composed, so compare their source against Room too.
        val expectedPaid = baseline.sumOf { it.totalAmountCents } + 11_300
        compose.onNodeWithText("累计增值税估算").assertIsDisplayed()
        compose.onAllNodesWithText("¥${TaxCalculator.formatMoney(expectedPaid)}").onFirst().assertIsDisplayed()
        val saved = runBlocking { app.receipts.all() }.single { it.storeName == storeName }
        assertEquals(1_300L, saved.totalTaxCents)
        assertEquals(10_000L, saved.totalPreTaxCents)
        assertEquals(baseline.sumOf { it.totalTaxCents } + 1_300,
            runBlocking { app.receipts.all() }.sumOf { it.totalTaxCents })

        // Use the real history search so unrelated / future-dated records do not
        // make this test depend on the test receipt being among the latest five.
        compose.onNodeWithText("账本").performClick()
        compose.onNodeWithText("搜索商户或商品").performTextReplacement(storeName)
        hideKeyboard()
        val receiptCard = hasText(storeName) and hasClickAction() and !hasSetTextAction()
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(receiptCard)
        compose.onNode(receiptCard).performClick()
        compose.onNodeWithText("¥13.00").assertIsDisplayed()
        compose.onNodeWithText("¥100.00").assertIsDisplayed()
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText("编辑账单"))
        compose.onNodeWithText("编辑账单").performClick()

        scrollToTag("amount0").performTextReplacement("226.00")
        compose.onNodeWithTag("amount0").assertTextContains("226.00")
        hideKeyboard()
        compose.onNodeWithTag("saveReceipt").assertIsDisplayed().assertIsEnabled().performClick()
        waitForSavedAmount(22_600)
        val edited = runBlocking { app.receipts.all() }.single { it.storeName == storeName }
        assertEquals(saved.id, edited.id)
        assertEquals(saved.timestamp, edited.timestamp)
        assertEquals(2_600L, edited.totalTaxCents)

        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(receiptCard)
        compose.onNode(receiptCard).performClick()
        compose.onNodeWithText("¥26.00").assertIsDisplayed()
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText("删除账单"))
        compose.onNodeWithText("删除账单").performClick()
        compose.onNodeWithText("确认删除").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            runBlocking { app.receipts.all() }.none { it.storeName == storeName }
        }
    }

    @Test fun customRateInputSurvivesTypingPresetPrefixes() {
        compose.onNodeWithText("记一笔").performScrollTo().performClick()
        scrollToTag("amount0").performTextReplacement("113.00")
        scrollToTag("rateCustom_0").performClick()
        listOf("0", "6", "9", "13").forEach { prefix ->
            scrollToTag("customRate0").performTextReplacement(prefix)
            // These prefixes match preset rates, but typing a decimal must leave
            // custom mode and the text-field focus intact.
            compose.onNodeWithTag("customRate0").assertExists().performTextInput(".5")
            compose.onNodeWithTag("customRate0").assertTextContains("$prefix.5")
        }
        hideKeyboard()
        compose.onNodeWithContentDescription("关闭录入").performClick()
        compose.onNodeWithText("放弃修改").performClick()
    }

    // Lazy items outside a small viewport do not yet have semantics nodes.
    // Ask the form container to find and compose the item before interacting.
    private fun scrollToTag(tag: String): SemanticsNodeInteraction {
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun waitForSavedAmount(cents: Long) {
        compose.waitUntil(timeoutMillis = 10_000) {
            runBlocking { app.receipts.all() }.any { it.storeName == storeName && it.totalAmountCents == cents } &&
                compose.onAllNodesWithTag("saveReceipt").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
    }

    private fun hideKeyboard() {
        // The editor belongs to a Dialog window, while history search belongs to
        // the Activity. Target the focused field's actual root in either case.
        compose.waitForIdle()
        // A field behind the sheet can retain Compose focus in its own root.
        val roots = compose.onAllNodes(isFocused()).fetchSemanticsNodes()
            .map { (it.root as ViewRootForTest).view }.distinct()
        val view = compose.runOnIdle { roots.single { it.hasWindowFocus() } }
        compose.runOnUiThread {
            checkNotNull(ViewCompat.getWindowInsetsController(view)).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            var hidden = false
            compose.runOnUiThread {
                val insets = ViewCompat.getRootWindowInsets(view)
                hidden = insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime()) &&
                    insets.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0
            }
            hidden
        }
        compose.waitForIdle()
    }
}
