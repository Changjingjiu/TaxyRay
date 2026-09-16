package io.github.taxray.ui

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.taxray.MainActivity
import io.github.taxray.TaxyRayApplication
import io.github.taxray.core.DraftItem
import io.github.taxray.core.Receipt
import io.github.taxray.core.TaxCalculator
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Compose graphics layer and gallery button; does not inject a Bitmap. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ContributionExportTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val runId = UUID.randomUUID().toString().take(8)
    private val storeName = "日常采购"
    private val creationMarker = "$storeName · QA-$runId"
    private var createdReceiptId: String? = null
    private val additionalReceiptIds = mutableListOf<String>()
    private val app get() = ApplicationProvider.getApplicationContext<TaxyRayApplication>()
    private val resolver get() = app.contentResolver
    private var baseline: List<Receipt> = emptyList()
    private var existingImages: Set<Long> = emptySet()
    private val createdImages = mutableListOf<Uri>()
    private val qaDirectory get() = requireNotNull(app.getExternalFilesDir("qa"))

    @Before fun rememberExistingData() {
        baseline = runBlocking { app.receipts.all() }
        assertFalse(baseline.any { it.storeName == creationMarker })
        existingImages = galleryImages().map { it.id }.toSet()
        compose.waitForIdle()
    }

    @After fun removeOnlyThisRunsRecordsAndGalleryFiles() {
        // All selected URIs were individually observed appearing after our explicit save clicks.
        val deleteResults = createdImages.map { uri -> runCatching { resolver.delete(uri, null, null) } }
        runBlocking {
            val originalIds = baseline.map { it.id }.toSet()
            // The UUID marker is only used until the save returns an owned receipt ID.
            // This recovery branch also cleans up when an assertion fails directly after saving.
            val ownedId = createdReceiptId ?: app.receipts.all()
                .singleOrNull { it.storeName == creationMarker && it.id !in originalIds }?.id
            val ownedIds = additionalReceiptIds + listOfNotNull(ownedId)
            ownedIds.forEach { id ->
                assertFalse("Refusing to delete an existing record", id in originalIds)
                app.receipts.delete(id)
            }
            val remaining = app.receipts.all().associateBy { it.id }
            baseline.forEach { original -> assertEquals("Existing record was modified", original, remaining[original.id]) }
            ownedIds.forEach { assertFalse(remaining.containsKey(it)) }
        }
        deleteResults.forEach { assertEquals("Could not remove this test's gallery image", 1, it.getOrThrow()) }
        assertTrue("Existing gallery files were removed", galleryImages().map { it.id }.toSet().containsAll(existingImages))
    }

    @Test fun realReceiptExportsCompletePaperAndForestComposeCards() {
        compose.onNodeWithText("记一笔").performScrollTo().performClick()
        scrollToTag("storeName", 0).performTextReplacement(creationMarker)
        val rows = listOf(Triple("日用品", "113.00", "13"), Triple("粮食", "109.00", "9"), Triple("零税额记录", "20.00", "0"))
        rows.forEachIndexed { index, (name, amount, rate) ->
            if (index > 0) scrollToTag("addItem", 3 + index).performClick()
            scrollToTag("itemName$index", 3 + index).performTextReplacement(name)
            hideKeyboard()
            scrollToTag("amount$index", 3 + index).performTextReplacement(amount)
            hideKeyboard()
            scrollToTag("rate${rate}_$index", 3 + index).performClick()
        }
        hideKeyboard()
        compose.onNodeWithText("实付 ¥242.00").assertIsDisplayed()
        compose.onNodeWithText("税额 ¥22.00").assertIsDisplayed()
        compose.onNodeWithTag("saveReceipt").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            runBlocking { app.receipts.all() }.any { it.storeName == creationMarker } &&
                compose.onAllNodesWithTag("saveReceipt").fetchSemanticsNodes().isEmpty()
        }
        val receipt = runBlocking { app.receipts.all() }.single { it.storeName == creationMarker }
        createdReceiptId = receipt.id
        assertEquals(3, receipt.items.size)
        assertEquals(24_200L, receipt.totalAmountCents)
        assertEquals(2_200L, receipt.totalTaxCents)
        assertEquals(22_000L, receipt.totalPreTaxCents)

        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(0)
        val expectedPaid = baseline.sumOf { it.totalAmountCents } + 24_200
        compose.onAllNodesWithText("¥${TaxCalculator.formatMoney(expectedPaid)}").onFirst().assertIsDisplayed()

        compose.onNodeWithText("账本").performClick()
        compose.onNodeWithText("搜索商户或商品").performTextReplacement(creationMarker)
        hideKeyboard()
        val receiptCard = hasText(creationMarker) and hasClickAction() and !hasSetTextAction()
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToIndex(1)
        compose.onNode(receiptCard).performClick()
        // The detail is now anchored to this exact ID. Rename only our fixture, so exported
        // examples stay clean even when the user's ledger already contains “日常采购”.
        runBlocking {
            app.receipts.save(storeName, receipt.items.map { item ->
                DraftItem(item.id, item.name, TaxCalculator.formatMoney(item.breakdown.amountCents),
                    TaxCalculator.formatRate(item.breakdown.taxRateBps), item.categoryReason)
            }, id = receipt.id)
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            runBlocking { app.receipts.all() }.single { it.id == receipt.id }.storeName == storeName &&
                compose.onAllNodesWithText(storeName).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasText(creationMarker) and !hasSetTextAction()).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("¥242.00").assertIsDisplayed()
        compose.onNodeWithText("¥22.00").assertIsDisplayed()
        compose.onNodeWithTag("receiptDetailItems").performScrollToIndex(1)
        compose.onNodeWithText("生成贡献卡").performClick()
        compose.onNodeWithText("纸本").assertIsSelected()
        compose.onNodeWithText("纳税人公共贡献记录").assertIsDisplayed()
        compose.onNodeWithText("TAXRAY / PERSONAL LEDGER").assertDoesNotExist()
        compose.onNodeWithText("本地").assertDoesNotExist()
        compose.onAllNodesWithText("记录摘要", substring = true).assertCountEquals(0)
        compose.onNodeWithText("¥242.00").assertExists()
        compose.onNodeWithText("¥22.00").assertIsDisplayed()
        saveScreen("contribution-paper")
        val paper = saveAndInspectGalleryCard("paper")

        compose.onNodeWithText("松石绿").performClick().assertIsSelected()
        compose.waitForIdle()
        compose.onNodeWithText("¥242.00").assertExists()
        compose.onNodeWithText("¥22.00").assertIsDisplayed()
        saveScreen("contribution-forest")
        val forest = saveAndInspectGalleryCard("forest")
        assertEquals(paper.width, forest.width)
        assertEquals(paper.height, forest.height)
        assertNotEquals("Template switch must change the captured graphics layer", paper.background, forest.background)
        assertTrue(Color.red(paper.background) > 200 && Color.green(paper.background) > 200)
        assertTrue(Color.green(forest.background) > Color.red(forest.background) + 15)

        File(qaDirectory, "qa-$runId-evidence.txt").writeText(
            "Synthetic receipt: $storeName\nPaid: CNY 242.00\nTax estimate: CNY 22.00\n" +
                "Paper export: ${paper.width}x${paper.height}, ${paper.bytes} bytes\n" +
                "Forest export: ${forest.width}x${forest.height}, ${forest.bytes} bytes\n" +
                "Captured through MainActivity > receipt detail > contribution card > save image.\n" +
                "QA copies remain in this directory; only this test's Room record and MediaStore images are removed.\n",
        )
        compose.onNodeWithContentDescription("关闭贡献卡").performClick()
        compose.onNodeWithText("总览").performClick()
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(0)
        saveScreen("home-real-receipt")
    }

    @Test fun cumulativeCardIncludesAllSavedBillsAndExportsBothTemplates() {
        runBlocking {
            additionalReceiptIds += app.receipts.save("累计卡测试 $runId A", listOf(
                DraftItem(name = "日用品", amount = "113.00", ratePercent = "13"),
                DraftItem(name = "粮食", amount = "109.00", ratePercent = "9"),
            ), timestamp = 1_704_096_000_000L)
            additionalReceiptIds += app.receipts.save("累计卡测试 $runId B", listOf(
                DraftItem(name = "服务", amount = "106.00", ratePercent = "6"),
                DraftItem(name = "零税额记录", amount = "20.00", ratePercent = "0"),
            ), timestamp = 1_735_718_400_000L)
        }
        val expectedPaid = baseline.sumOf { it.totalAmountCents } + 34_800L
        val expectedTax = baseline.sumOf { it.totalTaxCents } + 2_800L
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("shareAllReceipts").fetchSemanticsNodes().isNotEmpty()
        }
        // The cumulative card must include old bills even after filtering the ledger to no matches.
        compose.onNodeWithText("账本").performClick()
        compose.onNodeWithText("搜索商户或商品").performTextReplacement("no-match-$runId")
        hideKeyboard()
        compose.onNodeWithText("近 30 天").performClick()
        compose.onNodeWithText("总览").performClick()
        compose.onNodeWithTag("shareAllReceipts").performScrollTo().performClick()
        val inCard = hasAnyAncestor(isDialog() and hasAnyDescendant(hasText("累计贡献卡")))
        compose.onNode(hasText("累计贡献卡") and inCard).assertIsDisplayed()
        compose.onNode(hasText("全部 ${baseline.size + 2} 笔账单") and inCard).assertIsDisplayed()
        compose.onNode(hasText("累计增值税估算") and inCard).assertExists()
        compose.onNode(hasText("累计消费实付") and inCard).assertExists()
        compose.onNode(hasText("¥${TaxCalculator.formatMoney(expectedPaid)}") and inCard).assertExists()
        compose.onNode(hasText("¥${TaxCalculator.formatMoney(expectedTax)}") and inCard).assertIsDisplayed()

        saveScreen("cumulative-paper")
        val paper = saveAndInspectGalleryCard("cumulative-paper")
        compose.onNodeWithText("松石绿").performClick().assertIsSelected()
        compose.waitForIdle()
        saveScreen("cumulative-forest")
        val forest = saveAndInspectGalleryCard("cumulative-forest")
        assertEquals(paper.width, forest.width)
        assertEquals(paper.height, forest.height)
        assertNotEquals(paper.background, forest.background)
        compose.onNodeWithContentDescription("关闭贡献卡").performClick()
    }

    private fun saveAndInspectGalleryCard(template: String): ImageEvidence {
        val before = galleryImages().map { it.id }.toSet()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText("保存图片") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("保存图片").assertIsEnabled().performClick()
        var observed: GalleryImage? = null
        compose.waitUntil(timeoutMillis = 15_000) {
            val newImages = galleryImages().filter { it.id !in before }
            if (newImages.size == 1) {
                observed = newImages.single()
                if (observed!!.uri !in createdImages) createdImages += observed!!.uri
            }
            newImages.size == 1 && newImages.single().size > 0
        }
        val saved = requireNotNull(observed)
        assertTrue(saved.name.startsWith("TaxyRay-") && saved.name.endsWith(".png"))
        assertEquals("image/png", saved.mimeType)
        val bytes = resolver.openInputStream(saved.uri)!!.use { it.readBytes() }
        assertTrue(bytes.size > 10_000)
        assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), bytes.copyOf(8))
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            assertTrue("HD card export must be at least 1080 px wide: ${bitmap.width}", bitmap.width >= 1080)
            assertTrue("Portrait card was clipped while capturing its scrollable layer", bitmap.height > bitmap.width)
            val colors = mutableSetOf<Int>()
            for (y in 0 until bitmap.height step (bitmap.height / 100).coerceAtLeast(1)) {
                for (x in 0 until bitmap.width step (bitmap.width / 80).coerceAtLeast(1)) colors += bitmap.getPixel(x, y)
            }
            assertTrue("Export is a blank or flat-color bitmap", colors.size > 16)
            assertTrue("Card background unexpectedly transparent", Color.alpha(bitmap.getPixel(0, 0)) == 255)
            File(qaDirectory, "qa-$runId-export-$template.png").writeBytes(bytes)
            return ImageEvidence(bitmap.width, bitmap.height, bytes.size, bitmap.getPixel(bitmap.width / 2, 3))
        } finally {
            bitmap.recycle()
        }
    }

    private fun galleryImages(): List<GalleryImage> {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.SIZE)
        val selection = "${MediaStore.Images.Media.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.IS_PENDING} = 0"
        return resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection,
            arrayOf(app.packageName, "Pictures/TaxyRay/"), null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    add(GalleryImage(id, ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        cursor.getString(1), cursor.getString(2), cursor.getLong(3)))
                }
            }
        }.orEmpty()
    }

    private fun scrollToTag(tag: String, index: Int): SemanticsNodeInteraction {
        // The fixture's row positions are known. Index scrolling avoids Compose 1.7's
        // off-thread iterative measurement racing the native LazyColumn prefetcher.
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToIndex(index)
        return compose.onNodeWithTag(tag).performScrollTo()
    }

    private fun saveScreen(name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(qaDirectory, "qa-$runId-$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun hideKeyboard() {
        compose.runOnUiThread {
            WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
    }

    private data class GalleryImage(val id: Long, val uri: Uri, val name: String, val mimeType: String, val size: Long)
    private data class ImageEvidence(val width: Int, val height: Int, val bytes: Int, val background: Int)
}
