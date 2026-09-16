@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.taxray.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.taxray.data.remote.ImageCompressor
import io.github.taxray.data.remote.ReceiptImageBatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local-only collection and preview. Only the explicit send action starts recognition. */
@Composable
fun ReceiptImagesSheet(
    images: List<String>, destination: String, model: String,
    cameraBusy: Boolean = false,
    previousItemCount: Int = 0,
    onImagesChanged: (List<String>) -> Unit, onPick: () -> Unit, onCamera: () -> Unit,
    onSend: () -> Unit, onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val previews by produceState<Map<String, ImageBitmap>>(emptyMap(), images) {
        val loaded = value.filterKeys { it in images }.toMutableMap()
        value = loaded.toMap()
        val compressor = ImageCompressor(context)
        // Sequential compression bounds memory even with five high-resolution photos.
        for (uri in images.filterNot { it in loaded }) {
            try {
                val image = compressor.compress(Uri.parse(uri))
                val preview = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
                }
                if (preview != null) { loaded[uri] = preview; value = loaded.toMap() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The send path reports a recoverable read error. */ }
        }
    }
    fun move(index: Int, target: Int) {
        val reordered = images.toMutableList()
        reordered.add(target, reordered.removeAt(index))
        onImagesChanged(reordered)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("账单图片", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Text("${images.size} / ${ReceiptImageBatch.MAX_IMAGES}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭选图") }
            }
            Text(if (previousItemCount == 0) "支持小票、订单截图和电子账单 每次最多 5 张\n多笔订单会分别核对" else "这笔已有 $previousItemCount 项 仅补拍同一笔账单的剩余部分", Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f).testTag("receiptImages"), contentPadding = PaddingValues(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (images.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("拍照或从相册选择 最多 ${ReceiptImageBatch.MAX_IMAGES} 张", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                itemsIndexed(images, key = { _, uri -> uri }) { index, uri ->
                    Row(Modifier.fillMaxWidth().testTag("receiptImage$index"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(width = 84.dp, height = 112.dp), contentAlignment = Alignment.Center) {
                            val preview = previews[uri]
                            if (preview != null) Image(preview, "第 ${index + 1} 张账单预览", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            else Icon(Icons.Outlined.Image, "第 ${index + 1} 张图片", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("第 ${index + 1} 张", style = MaterialTheme.typography.titleMedium)
                            Row {
                                IconButton(onClick = { move(index, index - 1) }, enabled = index > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "上移第 ${index + 1} 张") }
                                IconButton(onClick = { move(index, index + 1) }, enabled = index < images.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, "下移第 ${index + 1} 张") }
                                IconButton(onClick = { onImagesChanged(images.filterIndexed { position, _ -> position != index }) }) { Icon(Icons.Outlined.DeleteOutline, "移除第 ${index + 1} 张") }
                            }
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onCamera, enabled = !cameraBusy && images.isEmpty(), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("拍照并识别")
                    }
                    OutlinedButton(onClick = onPick, enabled = images.size < ReceiptImageBatch.MAX_IMAGES, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("相册多选")
                    }
                }
                Text("发送至 $destination\n模型 $model\n拍照确认后自动识别 相册选图需点击发送\n图片由该服务处理 可能产生 API 费用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onSend, enabled = images.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sendReceiptImages")) { Text("发送并识别 ${images.size} 张") }
            }
        }
    }
}
