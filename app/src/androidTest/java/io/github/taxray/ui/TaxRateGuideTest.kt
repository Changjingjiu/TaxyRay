package io.github.taxray.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taxray.ReceiptDraft
import io.github.taxray.core.DraftItem
import io.github.taxray.ui.components.TaxRateGuide
import io.github.taxray.ui.screens.ScannerReviewSheet
import io.github.taxray.ui.theme.TaxyRayTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory screen tests do not access the user's ledger or network. */
@RunWith(AndroidJUnit4::class)
class TaxRateGuideTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sixPercentDraftStartsInPresetModeAndCustomSixPointFiveStaysEditable() {
        var draft by mutableStateOf(ReceiptDraft(items = listOf(DraftItem(amount = "106.00", ratePercent = "6"))))
        compose.setContent {
            TaxyRayTheme {
                ScannerReviewSheet(draft, busy = false, onChange = { draft = it }, onDismiss = {}, onSave = {})
            }
        }
        compose.onNodeWithTag("rate6_0").performScrollTo().assertIsSelected()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithTag("customRate0").assertDoesNotExist()
        compose.onNodeWithTag("rateCustom_0").performClick()
        compose.onNodeWithTag("customRate0").performScrollTo().performTextReplacement("6")
        compose.onNodeWithTag("customRate0").performTextInput(".5")
        compose.onNodeWithTag("customRate0").assertTextContains("6.5")
        compose.onNodeWithTag("rateCustom_0").assertIsSelected()
        compose.onNodeWithTag("rate6_0").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("customRate0").assertDoesNotExist()
    }

    @Test fun guideRowsOpenRepresentativesAndKeepConditionalRateBoundariesVisible() {
        compose.setContent {
            TaxyRayTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { TaxRateGuide() }
                }
            }
        }
        val expectedText = mapOf(
            "13" to "手机 电脑 机械设备",
            "9" to "不能把所有肉蛋菜都按9%处理",
            "6" to "咨询服务 设计服务",
            "0" to "零税率与免税不同",
        )
        expectedText.forEach { (rate, text) ->
            compose.onNodeWithTag("taxGuide$rate").performScrollTo().assertHasClickAction()
                .assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithText("典型代表").assertExists()
            compose.onNodeWithText(text, substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("知道了").performClick()
            compose.onNodeWithTag("taxGuideDetails").assertDoesNotExist()
        }
    }
}
