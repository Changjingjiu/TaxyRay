package io.github.taxray.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taxray.data.remote.ReceiptImageBatch
import io.github.taxray.ui.screens.ReceiptImagesSheet
import io.github.taxray.ui.theme.TaxyRayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local callback tests only: no provider, API call, real receipt or user ledger is involved. */
@RunWith(AndroidJUnit4::class)
class ReceiptImagesSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptySelectionCannotSendAndAddingImagesDoesNotSendAutomatically() {
        var images by mutableStateOf(emptyList<String>())
        var pickCount = 0
        var cameraCount = 0
        var sendCount = 0
        compose.setContent {
            TaxyRayTheme {
                ReceiptImagesSheet(images, "https://example.test/v1", "synthetic-model",
                    onImagesChanged = { images = it }, onPick = { pickCount++ },
                    onCamera = { cameraCount++ }, onSend = { sendCount++ }, onDismiss = {})
            }
        }
        compose.onNodeWithTag("sendReceiptImages").assertIsNotEnabled()
        compose.onNodeWithText("相册多选").performClick()
        compose.onNodeWithText("拍照并识别").performClick()
        compose.runOnIdle {
            assertEquals(1, pickCount)
            assertEquals(1, cameraCount)
            assertEquals(0, sendCount)
            images = listOf(photo(1))
        }
        compose.onNodeWithTag("sendReceiptImages").assertIsEnabled()
        compose.onNodeWithText("拍照并识别").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, cameraCount) }
        compose.runOnIdle { assertEquals(0, sendCount) }
        compose.onNodeWithTag("sendReceiptImages").performClick()
        compose.runOnIdle { assertEquals(1, sendCount) }
    }

    @Test fun imagesCanBeReorderedAndRemovedWithoutStartingRecognition() {
        var images by mutableStateOf(listOf(photo(1), photo(2), photo(3)))
        var sendCount = 0
        compose.setContent {
            TaxyRayTheme {
                ReceiptImagesSheet(images, "https://example.test/v1", "synthetic-model",
                    onImagesChanged = { images = it }, onPick = {}, onCamera = {},
                    onSend = { sendCount++ }, onDismiss = {})
            }
        }
        compose.onNodeWithContentDescription("上移第 1 张").assertIsNotEnabled()
        compose.onNodeWithContentDescription("下移第 1 张").performClick()
        compose.runOnIdle { assertEquals(listOf(photo(2), photo(1), photo(3)), images) }

        compose.onNodeWithTag("receiptImages").performScrollToIndex(2)
        compose.onNodeWithContentDescription("下移第 3 张").assertIsNotEnabled()
        compose.onNodeWithContentDescription("上移第 3 张").performClick()
        compose.runOnIdle { assertEquals(listOf(photo(2), photo(3), photo(1)), images) }

        compose.onNodeWithTag("receiptImages").performScrollToIndex(1)
        compose.onNodeWithContentDescription("移除第 2 张").performClick()
        compose.runOnIdle {
            assertEquals(listOf(photo(2), photo(1)), images)
            assertEquals(0, sendCount)
        }
    }

    @Test fun cameraRequiresEmptySelectionAndGalleryHonorsFiveImageLimit() {
        var images by mutableStateOf(List(ReceiptImageBatch.MAX_IMAGES) { photo(it + 1) })
        compose.setContent {
            TaxyRayTheme {
                ReceiptImagesSheet(images, "https://example.test/v1", "synthetic-model",
                    onImagesChanged = { images = it }, onPick = {}, onCamera = {},
                    onSend = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("相册多选").assertIsNotEnabled()
        compose.onNodeWithText("拍照并识别").assertIsNotEnabled()
        compose.onNodeWithTag("sendReceiptImages").assertIsEnabled()
        compose.onNodeWithContentDescription("移除第 1 张").performClick()
        compose.runOnIdle { assertEquals(4, images.size) }
        compose.onNodeWithText("相册多选").assertIsEnabled()
        compose.onNodeWithText("拍照并识别").assertIsNotEnabled()
        repeat(4) { compose.onNodeWithContentDescription("移除第 1 张").performClick() }
        compose.onNodeWithText("拍照并识别").assertIsEnabled()
        compose.onNodeWithTag("sendReceiptImages").assertIsNotEnabled()
    }

    // Missing local files intentionally use the recoverable placeholder preview path.
    private fun photo(number: Int) = "file:///synthetic-receipt-$number.jpg"
}
