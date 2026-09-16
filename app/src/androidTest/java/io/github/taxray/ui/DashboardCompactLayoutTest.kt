package io.github.taxray.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taxray.core.DraftItem
import io.github.taxray.core.Receipt
import io.github.taxray.core.TaxCalculator
import io.github.taxray.ui.screens.DashboardScreen
import io.github.taxray.ui.theme.TaxyRayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Compact spacing must not come from clipping content or shrinking the share target. */
@RunWith(AndroidJUnit4::class)
class DashboardCompactLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val receipt = Receipt(
        id = "dashboard-layout-test",
        storeName = "布局测试商户",
        timestamp = 1_750_000_000_000,
        items = TaxCalculator.calculateItems(listOf(DraftItem(name = "测试商品", amount = "107.12"))),
    )

    @Test fun totalsAreCompactAndShareAndScanRemainUsable() {
        var shareCount = 0
        var scanCount = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                TaxyRayTheme {
                    Box(Modifier.requiredWidth(360.dp)) {
                        DashboardScreen(listOf(receipt), historyOnly = false, busy = false, loadError = null,
                            onAdd = {}, onScan = { scanCount++ }, onDetail = {}, onAll = {},
                            onShareAll = { shareCount++ }, onDeleteSelection = {})
                    }
                }
            }
        }

        val bounds = compose.onNodeWithTag("dashboardTotals").getUnclippedBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue("Normal-font summary should be 185–205 dp, was $height", height in 185.dp..205.dp)
        compose.onNodeWithTag("shareAllReceipts").assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("识别账单").assertIsDisplayed().performClick()
        compose.onNodeWithText("识别小票").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, shareCount); assertEquals(1, scanCount) }
    }

    @Test fun largerFontsGrowTheCardAndKeepSummaryContentReadableOnNarrowScreens() {
        val fontScale = mutableFloatStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.floatValue)) {
                TaxyRayTheme {
                    Box(Modifier.requiredWidth(320.dp)) {
                        DashboardScreen(listOf(receipt), historyOnly = false, busy = false, loadError = null,
                            onAdd = {}, onScan = {}, onDetail = {}, onAll = {}, onShareAll = {}, onDeleteSelection = {})
                    }
                }
            }
        }
        val initialBounds = compose.onNodeWithTag("dashboardTotals").getUnclippedBoundsInRoot()
        var previousHeight = initialBounds.bottom - initialBounds.top
        for (scale in listOf(1.3f, 2f)) {
            compose.runOnIdle { fontScale.floatValue = scale }
            val card = compose.onNodeWithTag("dashboardTotals").assertIsDisplayed().getUnclippedBoundsInRoot()
            val height = card.bottom - card.top
            assertTrue("Summary must grow for fontScale $scale", height > previousHeight)
            previousHeight = height
            for (label in listOf("累计增值税估算", "累计消费", "有效税额占比")) {
                val results = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(label).assertIsDisplayed()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                assertTrue("$label must expose its text layout", results.isNotEmpty())
                assertFalse("$label must not overflow at fontScale $scale: ${results.map { "${it.size}, width=${it.didOverflowWidth}, height=${it.didOverflowHeight}" }}",
                    results.any { it.hasVisualOverflow })
                val bounds = compose.onNodeWithText(label).getUnclippedBoundsInRoot()
                assertTrue("$label must remain within the summary", bounds.top >= card.top && bounds.bottom <= card.bottom &&
                    bounds.left >= card.left && bounds.right <= card.right)
            }
            compose.onNodeWithTag("shareAllReceipts").assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            compose.onNodeWithText("识别账单").performScrollTo().assertIsDisplayed()
        }
    }
}
