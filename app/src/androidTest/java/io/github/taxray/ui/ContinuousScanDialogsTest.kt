package io.github.taxray.ui

import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.taxray.core.DraftItem
import io.github.taxray.ui.screens.DuplicateItemsDialog
import io.github.taxray.ui.screens.ScanRecognitionFailureDialog
import io.github.taxray.ui.theme.TaxyRayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Draft-only callbacks: these tests do not launch a camera, call an API or write a ledger. */
@RunWith(AndroidJUnit4::class)
class ContinuousScanDialogsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun duplicateSelectionDoesNotDeleteUntilExplicitlyConfirmedAndNewGroupResetsSelection() {
        var groupIndex by mutableStateOf(0)
        var items by mutableStateOf(listOf(item("first"), item("second")))
        val keptIds = mutableListOf<String>()
        var keptAll = 0
        compose.setContent {
            TaxyRayTheme {
                DuplicateItemsDialog(items, groupIndex, groupCount = 2,
                    onKeepOne = { keptIds += it }, onKeepAll = { keptAll++ })
            }
        }
        assertCannotDismiss("发现相似商品")
        compose.onNodeWithTag("duplicateScanItem-first").assertIsSelected()
        compose.onNodeWithTag("duplicateScanItem-second").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(emptyList<String>(), keptIds); assertEquals(0, keptAll) }
        compose.onNodeWithTag("keepOneScanItem").performClick()
        compose.runOnIdle {
            assertEquals(listOf("second"), keptIds)
            groupIndex = 1
            items = listOf(item("third"), item("fourth"))
        }
        compose.onNodeWithText("第 2 组  共 2 组").assertIsDisplayed()
        compose.onNodeWithTag("duplicateScanItem-third").assertIsSelected()
        compose.onNodeWithTag("keepAllScanItems").performClick()
        compose.runOnIdle {
            assertEquals(listOf("second"), keptIds)
            assertEquals(1, keptAll)
        }
    }

    @Test fun largeDuplicateGroupScrollsWithoutHidingEitherDecision() {
        var kept: String? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                TaxyRayTheme {
                    DuplicateItemsDialog(List(30) { item("item-$it") }, 0, 1,
                        onKeepOne = { kept = it }, onKeepAll = {})
                }
            }
        }
        compose.onNodeWithTag("duplicateScanItem-item-29").performScrollTo().performClick()
        compose.onNodeWithTag("keepAllScanItems").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("keepOneScanItem").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals("item-29", kept) }
    }

    @Test fun recognitionFailureRetainsPreviousDraftAndDoesNotFinishWithoutPreviousItems() {
        var hasPrevious by mutableStateOf(false)
        var retryCount = 0
        var finishCount = 0
        var discardCount = 0
        compose.setContent {
            TaxyRayTheme {
                ScanRecognitionFailureDialog("图片未能读取", hasPrevious,
                    onRetry = { retryCount++ }, onFinish = { finishCount++ }, onDiscard = { discardCount++ })
            }
        }
        compose.onNodeWithTag("reviewPreviousScanRounds").assertDoesNotExist()
        assertCannotDismiss("这次未能识别")
        compose.onNodeWithTag("retryScanRound").performClick()
        compose.runOnIdle {
            assertEquals(1, retryCount)
            assertEquals(0, finishCount + discardCount)
            hasPrevious = true
        }
        compose.onNodeWithTag("reviewPreviousScanRounds").performClick()
        compose.onNodeWithText("放弃本次录入").performClick()
        compose.onNodeWithText("继续录入").performClick()
        compose.onNodeWithTag("reviewPreviousScanRounds").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, finishCount); assertEquals(0, discardCount) }
    }

    private fun assertCannotDismiss(title: String) {
        val dialog = compose.onNode(isDialog() and hasAnyDescendant(hasText(title)))
        waitForNativeDialogFocus(dialog)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText(title).assertIsDisplayed()
        waitForNativeDialogFocus(dialog)
        dialog.performTouchInput { click(Offset(-12f, -12f)) }
        compose.onNodeWithText(title).assertIsDisplayed()
    }

    private fun waitForNativeDialogFocus(dialog: SemanticsNodeInteraction) {
        dialog.assertIsDisplayed()
        // Compose may finish drawing before WindowManager transfers input focus
        // from the host Activity to this Dialog. Raw system Back during that gap
        // would finish the empty test Activity instead of testing this dialog.
        // Resolve the native view from this exact dialog's semantics root, not
        // from an arbitrary focused window that could still be the Activity.
        val view = (dialog.fetchSemanticsNode().root as ViewRootForTest).view
        compose.waitUntil(timeoutMillis = 5_000) {
            var focused = false
            compose.runOnUiThread {
                focused = view.isAttachedToWindow && view.isShown && view.hasWindowFocus()
            }
            focused
        }
    }

    private fun item(id: String) = DraftItem(id = id, name = "牛肉香菜馅饼", amount = "13.99", ratePercent = "13")
}
