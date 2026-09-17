package io.github.taxray.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.taxray.ReceiptDraft
import io.github.taxray.core.DraftItem
import io.github.taxray.core.Receipt
import io.github.taxray.core.TaxCalculator
import io.github.taxray.data.remote.PaymentStatus
import io.github.taxray.ui.screens.ReceiptDetailSheet
import io.github.taxray.ui.screens.ScannerReviewSheet
import io.github.taxray.ui.theme.TaxyRayTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device-rendered README artwork for the review and detail screens. Synthetic data, user ledger untouched. */
@RunWith(AndroidJUnit4::class)
class ReceiptViewCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureDiscountReviewAndReceiptDetail() {
        val review = ReceiptDraft(
            storeName = "演示便民超市",
            items = listOf(
                DraftItem(name = "演示日用品", amount = "45.24", ratePercent = "13"),
                DraftItem(name = "演示蔬果", amount = "14.00", ratePercent = "9"),
            ),
            declaredTotal = "59.20",
            fromVision = true,
            paymentStatus = PaymentStatus.PAID,
        ).allocateDiscount()
        val receipt = Receipt(
            id = "capture-detail",
            storeName = "演示便民超市",
            timestamp = System.currentTimeMillis(),
            items = TaxCalculator.calculateItems(listOf(
                DraftItem(name = "演示日用品", amount = "100.00", ratePercent = "13"),
                DraftItem(name = "演示蔬果", amount = "13.00", ratePercent = "9"),
            )),
        )
        val showDetail = mutableStateOf(false)
        compose.setContent {
            TaxyRayTheme {
                if (showDetail.value) {
                    ReceiptDetailSheet(receipt, busy = false, onDismiss = {}, onEdit = {}, onDelete = {}, onShare = {})
                } else {
                    ScannerReviewSheet(review, busy = false, onChange = {}, onDismiss = {}, onSave = {}, returnToBatch = true)
                }
            }
        }
        compose.onNodeWithText("已分摊优惠 ¥0.04").assertIsDisplayed()
        compose.onNodeWithText("税额 ¥6.36").assertIsDisplayed()
        capture("screen-review.png", compose.onNodeWithTag("saveReceipt"))

        compose.runOnIdle { showDetail.value = true }
        compose.mainClock.advanceTimeBy(1_200)
        compose.waitForIdle()
        compose.onNodeWithText("增值税估算").assertIsDisplayed()
        capture("screen-detail.png", compose.onNodeWithTag("editReceipt"))
    }

    private fun capture(name: String, anchor: SemanticsNodeInteraction) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val nativeView = (anchor.fetchSemanticsNode().root as ViewRootForTest).view
        compose.waitUntil(timeoutMillis = 5_000) {
            var ready = false
            compose.runOnUiThread {
                ready = nativeView.isAttachedToWindow && nativeView.isShown && nativeView.hasWindowFocus()
            }
            ready
        }
        // The test clock does not advance window animation or SurfaceFlinger presentation,
        // so wait for the real frame before capturing the dialog surface.
        instrumentation.uiAutomation.waitForIdle(250, 5_000)
        SystemClock.sleep(600)
        instrumentation.waitForIdleSync()
        val directory = File(instrumentation.targetContext.filesDir, "bill-qa").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Device screenshot unavailable" }
        val output = File(directory, name)
        try {
            output.outputStream().use { assertTrue("PNG encoding failed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue("Screenshot is empty: $name", output.length() > 0)
        } finally {
            bitmap.recycle()
        }
    }
}
