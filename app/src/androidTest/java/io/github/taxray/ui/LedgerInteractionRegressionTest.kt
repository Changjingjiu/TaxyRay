package io.github.taxray.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.taxray.ReceiptDraft
import io.github.taxray.core.DraftItem
import io.github.taxray.core.Receipt
import io.github.taxray.core.TaxCalculator
import io.github.taxray.ui.screens.DashboardScreen
import io.github.taxray.ui.screens.ScannerReviewSheet
import io.github.taxray.ui.theme.TaxyRayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

/** Screen-level regressions use in-memory drafts and never read or modify the device ledger. */
@RunWith(AndroidJUnit4::class)
class LedgerInteractionRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun discardConfirmationRequiresAnExplicitChoiceAndKeepsTheEditorUsable() {
        val draft = mutableStateOf(ReceiptDraft(items = listOf(DraftItem(amount = "113.00"))))
        val editorVisible = mutableStateOf(true)
        compose.setContent {
            TaxyRayTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column {
                        Button(onClick = { editorVisible.value = true }) { Text("打开录入") }
                        if (editorVisible.value) ScannerReviewSheet(
                            draft = draft.value,
                            busy = false,
                            onChange = { draft.value = it },
                            onDismiss = { editorVisible.value = false },
                            onSave = {}
                        )
                    }
                }
            }
        }
        scrollToAmount().assertIsDisplayed()

        // This is the system Back path that previously hid the sheet too early.
        pressSystemBack()
        compose.onNodeWithText("放弃这次修改？").assertIsDisplayed()
        pressSystemBack()
        compose.onNodeWithText("放弃这次修改？").assertIsDisplayed()

        // Inject a real tap outside the AlertDialog window, onto the dimmed sheet.
        compose.onNode(isDialog() and hasAnyDescendant(hasText("放弃这次修改？")))
            .performTouchInput { click(Offset(-12f, -12f)) }
        compose.onNodeWithText("放弃这次修改？").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithText("放弃这次修改？").assertDoesNotExist()
        scrollToAmount().assertIsDisplayed().performTextReplacement("226.00")
        compose.runOnIdle { assertEquals("226.00", draft.value.items.single().amount) }

        compose.onNodeWithContentDescription("关闭录入").performClick()
        compose.onNodeWithText("放弃修改").performClick()
        compose.onNodeWithTag("saveReceipt").assertDoesNotExist()

        // A remaining invisible modal window would intercept this tap.
        compose.onNodeWithText("打开录入").performClick()
        scrollToAmount().assertIsDisplayed().assertTextContains("226.00")
        compose.onNodeWithContentDescription("关闭录入").performClick()
        compose.onNodeWithText("放弃修改").performClick()
    }

    @Test fun busyEditorConsumesSystemBackWithoutHidingOrDiscarding() {
        var dismissed = false
        compose.setContent {
            TaxyRayTheme {
                ScannerReviewSheet(
                    draft = ReceiptDraft(items = listOf(DraftItem(amount = "113.00"))),
                    busy = true,
                    onChange = {},
                    onDismiss = { dismissed = true },
                    onSave = {}
                )
            }
        }
        scrollToAmount().assertIsDisplayed()
        pressSystemBack()
        compose.onNodeWithText("放弃这次修改？").assertDoesNotExist()
        scrollToAmount().assertIsDisplayed()
        compose.runOnIdle { assertTrue(!dismissed) }
    }

    @Test fun datePickerSupportsImportedYearBeyondItsDefaultRange() {
        val timestamp = LocalDateTime.of(2125, 11, 29, 18, 42).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val draft = mutableStateOf(ReceiptDraft(timestamp = timestamp, items = listOf(DraftItem(amount = "113.00"))))
        compose.setContent {
            TaxyRayTheme {
                ScannerReviewSheet(draft.value, busy = false, onChange = { draft.value = it }, onDismiss = {}, onSave = {})
            }
        }
        compose.onNodeWithText("消费时间", substring = true).performClick()
        compose.onNode(isDialog() and hasAnyDescendant(hasText("确定")) and
            hasAnyDescendant(hasText("2125", substring = true))).assertExists()
        compose.onNodeWithText("确定").performClick()
        compose.runOnIdle { assertEquals(timestamp, draft.value.timestamp) }
        scrollToAmount().assertIsDisplayed()
    }

    @Test fun searchUsesTheSameDefaultMerchantNameShownOnTheReceipt() {
        showDashboard(
            historyOnly = true,
            receipts = listOf(receipt("empty", ""), receipt("whitespace", "   "), receipt("named", "书店"))
        )
        compose.onNodeWithText("搜索商户或商品").performTextReplacement("日常")
        compose.onNodeWithText("共 2 笔 · 按消费时间倒序").assertExists()
        // LazyColumn may not compose the second card while the IME is visible.
        compose.onAllNodesWithText("日常消费").onFirst().assertExists()
        compose.onNodeWithText("书店").assertDoesNotExist()

        compose.onNodeWithText("搜索商户或商品").performTextReplacement("书店")
        compose.onNodeWithText("共 1 笔 · 按消费时间倒序").assertExists()
        compose.onNode(hasText("书店") and !hasSetTextAction()).assertExists()

        compose.onNodeWithText("搜索商户或商品").performTextReplacement("测试商品")
        compose.onNodeWithText("共 3 笔 · 按消费时间倒序").assertExists()
    }

    @Test fun overviewDisclaimerIsCentered() {
        showDashboard(historyOnly = false)
        assertDisclaimerIsCentered()
    }

    @Test fun historyDisclaimerIsCentered() {
        showDashboard(historyOnly = true)
        assertDisclaimerIsCentered()
    }

    private fun scrollToAmount(): SemanticsNodeInteraction {
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasTestTag("amount0"))
        return compose.onNodeWithTag("amount0")
    }

    private fun showDashboard(historyOnly: Boolean, receipts: List<Receipt> = emptyList()) {
        compose.setContent {
            TaxyRayTheme {
                Surface(Modifier.fillMaxSize()) {
                    DashboardScreen(receipts, historyOnly, busy = false, loadError = null,
                        onAdd = {}, onScan = {}, onDetail = {}, onAll = {})
                }
            }
        }
    }

    private fun assertDisclaimerIsCentered() {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(DISCLAIMER).performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(TextAlign.Center, layouts.single().layoutInput.style.textAlign)
    }

    private fun pressSystemBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    private fun receipt(id: String, storeName: String) = Receipt(
        id = id,
        storeName = storeName,
        timestamp = 1_800_000_000_000L,
        items = TaxCalculator.calculateItems(listOf(DraftItem(name = "测试商品", amount = "113.00")))
    )

    private companion object {
        const val DISCLAIMER = "按所选税率估算价格中的增值税 不代表商户实际缴税额\n不作为报税或完税依据"
    }
}
