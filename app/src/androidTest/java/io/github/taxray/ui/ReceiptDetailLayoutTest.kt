package io.github.taxray.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taxray.core.DraftItem
import io.github.taxray.core.Receipt
import io.github.taxray.core.TaxCalculator
import io.github.taxray.ui.screens.ReceiptDetailSheet
import io.github.taxray.ui.theme.TaxyRayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Long receipts must never make the edit action depend on scrolling to the last item. */
@RunWith(AndroidJUnit4::class)
class ReceiptDetailLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editStaysVisibleAtBothEndsOfALongReceiptWithLargeText() {
        val receipt = Receipt(
            id = "detail-layout-test",
            storeName = "长商户名称测试 江苏吉麦隆超市夏港店",
            timestamp = 1_750_000_000_000,
            items = TaxCalculator.calculateItems(List(100) { DraftItem(name = "测试商品 ${it + 1}", amount = "11.30") }),
        )
        var editCount = 0
        var deleteCount = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                TaxyRayTheme {
                    ReceiptDetailSheet(receipt, busy = false, onDismiss = {},
                        onEdit = { editCount++ }, onDelete = { deleteCount++ }, onShare = {})
                }
            }
        }
        compose.onNodeWithContentDescription("编辑账单").assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        // Jump over the 100 product rows to the footer instead of repeatedly
        // traversing a changing lazy semantics tree during a list-wide search.
        compose.onNodeWithTag("receiptDetailItems").performScrollToIndex(receipt.items.size + 3)
        compose.onNodeWithTag("receiptRoundingNote").assertIsDisplayed().assertWidthIsAtLeast(260.dp)
        compose.onNodeWithContentDescription("编辑账单").assertIsDisplayed().performClick()
        compose.onNodeWithText("删除账单").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("删除账单").performClick()
        compose.runOnIdle { assertEquals(0, deleteCount) }
        compose.onNodeWithText("保留账单").performClick()
        compose.onNodeWithContentDescription("编辑账单").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, editCount); assertEquals(0, deleteCount) }
    }
}
