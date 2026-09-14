package io.github.taxray.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taxray.MainActivity
import io.github.taxray.TaxLensApplication
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
    private val app get() = ApplicationProvider.getApplicationContext<TaxLensApplication>()
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
        compose.onNodeWithTag("storeName").performScrollTo().performTextReplacement(storeName)
        compose.onNodeWithTag("itemName0").performScrollTo().performTextReplacement("UI 测试商品")
        compose.onNodeWithTag("amount0").performScrollTo().performTextReplacement("113.00")
        compose.onNodeWithTag("rate13_0").performScrollTo().performClick()
        hideKeyboard()
        compose.onNodeWithTag("saveReceipt").performClick()
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

        compose.onNodeWithTag("amount0").performScrollTo().performTextReplacement("226.00")
        hideKeyboard()
        compose.onNodeWithTag("saveReceipt").performClick()
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
        compose.onNodeWithTag("amount0").performScrollTo().performTextReplacement("113.00")
        compose.onNodeWithText("自定义").performScrollTo().performClick()
        listOf("0", "6", "9", "13").forEach { prefix ->
            compose.onNodeWithTag("customRate0").performScrollTo().performTextReplacement(prefix)
            // These prefixes match preset rates, but typing a decimal must leave
            // custom mode and the text-field focus intact.
            compose.onNodeWithTag("customRate0").assertExists().performTextInput(".5")
            compose.onNodeWithTag("customRate0").assertTextContains("$prefix.5")
        }
        hideKeyboard()
        compose.onNodeWithContentDescription("关闭录入").performClick()
        compose.onNodeWithText("放弃修改").performClick()
    }

    private fun waitForSavedAmount(cents: Long) {
        compose.waitUntil(timeoutMillis = 10_000) {
            runBlocking { app.receipts.all() }.any { it.storeName == storeName && it.totalAmountCents == cents } &&
                compose.onAllNodesWithTag("saveReceipt").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
    }

    private fun hideKeyboard() {
        compose.runOnUiThread {
            WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
    }
}
