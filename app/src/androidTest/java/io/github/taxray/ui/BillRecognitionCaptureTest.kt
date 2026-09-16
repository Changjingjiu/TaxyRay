package io.github.taxray.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.taxray.ReceiptScanSession
import io.github.taxray.ScannedBill
import io.github.taxray.core.DraftItem
import io.github.taxray.core.Receipt
import io.github.taxray.core.TaxCalculator
import io.github.taxray.data.remote.PaymentStatus
import io.github.taxray.data.remote.VisionReceipt
import io.github.taxray.ui.screens.BillBatchReviewSheet
import io.github.taxray.ui.screens.DashboardScreen
import io.github.taxray.ui.screens.ScannerReviewSheet
import io.github.taxray.ui.theme.TaxyRayTheme
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device-rendered QA samples with synthetic data. Does not read or mutate the user's ledger. */
@RunWith(AndroidJUnit4::class)
class BillRecognitionCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private data class Appearance(val name: String, val night: Boolean = false, val fontScale: Float = 1f)

    @Test fun captureDashboardBatchAndPaymentReviewInDayNightAndLargeText() {
        val date = LocalDate.now().atTime(12, 0).atZone(ZoneId.systemDefault())
        val receipts = listOf(
            Receipt(id = "capture-market", storeName = "演示便民超市", timestamp = date.toInstant().toEpochMilli(),
                items = TaxCalculator.calculateItems(listOf(
                    DraftItem(name = "演示日用品", amount = "32.80", ratePercent = "13"),
                    DraftItem(name = "演示蔬果", amount = "16.60", ratePercent = "9"),
                ))),
            Receipt(id = "capture-shop", storeName = "演示数码商店", timestamp = date.minusDays(3).toInstant().toEpochMilli(),
                items = TaxCalculator.calculateItems(listOf(DraftItem(name = "演示配件", amount = "169.00")))),
        )
        val bills = listOf(
            bill("unpaid", "演示清洁用品店", "演示香皂套装", "10.80", "0.00", PaymentStatus.UNPAID,
                "先用后付 实付 ¥0 确认收货后自动付款 ¥10.80"),
            bill("paid-home", "演示家居用品店", "演示收纳用品", "14.30", "14.30", PaymentStatus.PAID,
                "实付 ¥14.30"),
            bill("paid-digital", "演示数码商店", "演示连接配件", "169.00", "169.00", PaymentStatus.PAID,
                "实付 ¥169.00"),
        )
        val appearances = listOf(Appearance("day"), Appearance("night", night = true), Appearance("large-font", fontScale = 1.3f))
        var appearance by mutableStateOf(appearances.first())
        var surface by mutableStateOf("dashboard")
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            val deviceConfiguration = LocalConfiguration.current
            val deviceDensity = LocalDensity.current
            val configuration = Configuration(deviceConfiguration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (appearance.night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(deviceDensity.density, appearance.fontScale),
            ) {
                key(appearance.name, surface) {
                    TaxyRayTheme {
                        val destinations = listOf("总览" to Icons.Outlined.Dashboard, "账本" to Icons.Outlined.ReceiptLong, "设置" to Icons.Outlined.Tune)
                        Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
                            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                                destinations.forEachIndexed { index, pair ->
                                    NavigationBarItem(selected = index == 0, onClick = {}, icon = { Icon(pair.second, null) }, label = { Text(pair.first) })
                                }
                            }
                        }) { padding ->
                            Box(Modifier.fillMaxSize().padding(padding)) {
                                DashboardScreen(receipts, historyOnly = false, busy = false, loadError = null,
                                    onAdd = {}, onScan = {}, onDetail = {}, onAll = {}, onShareAll = {}, onDeleteSelection = {})
                            }
                        }
                        when (surface) {
                            "batch" -> BillBatchReviewSheet(bills, emptyList(), busy = false,
                                onReview = {}, onSkip = {}, onRestore = {}, onSupplement = {}, onAddImages = {}, onFinish = {})
                            "payment" -> ScannerReviewSheet(bills.first().draft, busy = false, onChange = {},
                                onDismiss = {}, onSave = {}, returnToBatch = true)
                        }
                    }
                }
            }
        }

        for (mode in appearances) {
            for (screen in listOf("dashboard", "batch", "payment")) {
                compose.runOnIdle { appearance = mode; surface = screen }
                compose.mainClock.advanceTimeBy(1_200)
                compose.waitForIdle()
                when (screen) {
                    "dashboard" -> {
                        compose.onNodeWithTag("dashboardTotals").assertIsDisplayed()
                        compose.onNodeWithText("识别账单").assertIsDisplayed()
                    }
                    "batch" -> {
                        compose.onNodeWithText("识别到 3 笔账单").assertIsDisplayed()
                        compose.onNodeWithTag("finishBillBatch").assertIsDisplayed()
                    }
                    "payment" -> compose.onNodeWithTag("confirmBillPayment").assertIsDisplayed()
                }
                val anchor = compose.onNodeWithTag(when (screen) {
                    "dashboard" -> "dashboardTotals"
                    "batch" -> "billBatchReview"
                    else -> "confirmBillPayment"
                })
                capture("$screen-${mode.name}.png", anchor)
            }
        }
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
        // Compose's test clock does not advance WindowManager's enter/exit animation
        // or SurfaceFlinger presentation. A fresh semantics tree alone can still
        // capture the old Activity surface or a translucent Dialog fade-in frame.
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

    private fun bill(id: String, merchant: String, product: String, amount: String, paid: String,
        status: PaymentStatus, evidence: String) = ScannedBill(
        id = id,
        session = ReceiptScanSession().append(VisionReceipt(storeName = merchant,
            items = listOf(DraftItem(name = product, amount = amount)), declaredTotal = paid,
            receiptDateTime = "2026-09-16T12:00:00", paymentStatus = status, paymentEvidence = evidence,
            sourceImageIndices = listOf(1))),
    )
}
