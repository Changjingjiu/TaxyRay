package io.github.taxray.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taxray.MainActivity
import io.github.taxray.TaxyRayApplication
import io.github.taxray.core.DraftItem
import io.github.taxray.core.Receipt
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies selection and confirmation through the real cards, while owning only three fixtures. */
@RunWith(AndroidJUnit4::class)
class BulkReceiptDeleteTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<TaxyRayApplication>()
    private val merchantPrefix = "BULK-UI-${UUID.randomUUID()}"
    private val ownedIds = mutableListOf<String>()
    private var baseline: List<Receipt> = emptyList()

    @Before fun seedOnlyThisRunsReceipts() {
        runBlocking {
            baseline = app.receipts.all()
            repeat(3) { index ->
                ownedIds += app.receipts.save(
                    "$merchantPrefix-$index",
                    listOf(DraftItem(name = "批量删除测试商品 $index", amount = "113.00", ratePercent = "13")),
                    timestamp = System.currentTimeMillis() - index * 60_000L,
                )
            }
        }
        compose.waitForIdle()
    }

    @After fun removeOnlyOwnedFixturesAndPreserveExistingBills() {
        runBlocking {
            ownedIds.forEach { id ->
                assertFalse("Never delete an existing receipt", baseline.any { it.id == id })
                app.receipts.delete(id)
            }
            val remaining = app.receipts.all().associateBy { it.id }
            baseline.forEach { assertEquals("Existing receipt changed", it, remaining[it.id]) }
            ownedIds.forEach { assertFalse(remaining.containsKey(it)) }
        }
    }

    @Test fun longPressSelectsSeveralCardsAndDeletionRequiresConfirmation() {
        val original = runBlocking { app.receipts.all() }.associateBy { it.id }
        compose.onNodeWithText("账本").performClick()
        compose.onNodeWithText("搜索商户或商品").performTextReplacement(merchantPrefix)
        compose.runOnUiThread {
            WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()

        scrollToReceipt(ownedIds[0]).performTouchInput { longClick() }
        compose.onNodeWithTag("deleteSelectedReceipts").assertIsDisplayed().assertTextContains("删除 1 笔")
        scrollToReceipt(ownedIds[1]).performClick()
        compose.onNodeWithTag("deleteSelectedReceipts").assertTextContains("删除 2 笔")

        // Selection controls stay reachable while scrolling farther down the receipt list.
        scrollToReceipt(ownedIds[2]).assertIsDisplayed()
        compose.onNodeWithTag("deleteSelectedReceipts").assertIsDisplayed().performClick()
        compose.onNodeWithText("删除这 2 笔账单？").assertIsDisplayed()
        assertEquals(original, runBlocking { app.receipts.all() }.associateBy { it.id })
        compose.onNodeWithText("保留账单").performClick()
        assertEquals(original, runBlocking { app.receipts.all() }.associateBy { it.id })

        compose.onNodeWithTag("deleteSelectedReceipts").assertIsDisplayed().performClick()
        compose.onNodeWithText("确认删除").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            val remainingIds = runBlocking { app.receipts.all() }.map { it.id }.toSet()
            ownedIds[0] !in remainingIds && ownedIds[1] !in remainingIds &&
                compose.onAllNodesWithTag("deleteSelectedReceipts").fetchSemanticsNodes().isEmpty()
        }
        val remaining = runBlocking { app.receipts.all() }.associateBy { it.id }
        assertEquals(original - ownedIds.take(2).toSet(), remaining)
        assertEquals(original[ownedIds[2]], remaining[ownedIds[2]])
        scrollToReceipt(ownedIds[2]).assertIsDisplayed()
    }

    private fun scrollToReceipt(id: String): SemanticsNodeInteraction {
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasTestTag("receipt-$id"))
        return compose.onNodeWithTag("receipt-$id")
    }
}
