@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.taxray

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.taxray.core.Receipt
import io.github.taxray.data.remote.ReceiptImageBatch
import io.github.taxray.data.remote.CameraCaptureStore
import io.github.taxray.data.update.UpdateSource
import io.github.taxray.ui.components.*
import io.github.taxray.ui.screens.*
import io.github.taxray.ui.theme.TaxyRayTheme
import kotlinx.coroutines.*
import kotlin.math.ceil

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TaxyRayTheme { TaxyRayApp() } }
    }
}

@Composable
fun TaxyRayApp(vm: TaxyRayViewModel = viewModel()) {
    val receipts by vm.receipts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val shareHelper = remember(context) { ShareCardHelper(context) }
    val cameraStore = remember(context) { CameraCaptureStore(context) }
    val snackbarHost = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var cardId by rememberSaveable { mutableStateOf<String?>(null) }
    var shareAll by rememberSaveable { mutableStateOf(false) }
    var deletionIds by remember { mutableStateOf(emptyList<String>()) }
    var showScanner by rememberSaveable { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }
    var showPolicy by remember { mutableStateOf(false) }
    var showUpdate by rememberSaveable { mutableStateOf(false) }
    var imageUris by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraPreparing by remember { mutableStateOf(false) }
    var scannerGeneration by rememberSaveable { mutableLongStateOf(0L) }
    var cameraGeneration by rememberSaveable { mutableLongStateOf(0L) }
    var pickerGeneration by rememberSaveable { mutableLongStateOf(0L) }
    var pendingImagePath by rememberSaveable { mutableStateOf<String?>(null) }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let { uri -> vm.export(uri, "json") } }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let { uri -> vm.export(uri, "csv") } }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::previewImport) }
    val saveImage = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val path = pendingImagePath
        pendingImagePath = null
        scope.launch {
            try {
                if (uri != null) {
                    withContext(Dispatchers.IO) {
                        val source = path?.let { java.io.File(it) }?.takeIf { it.isFile && it.length() > 0 }
                            ?: error("待保存图片已失效 请重新生成贡献卡")
                        context.contentResolver.openOutputStream(uri, "wt")?.use { target -> source.inputStream().use { it.copyTo(target) } }
                            ?: error("无法写入图片 请选择其他保存位置")
                    }
                    vm.notify("贡献卡图片已保存")
                }
            } catch (e: Exception) { vm.notify(e.message ?: "保存失败 请重试") }
            finally { withContext(Dispatchers.IO) { path?.let { java.io.File(it).delete() } } }
        }
    }
    fun addImages(incoming: List<String>) {
        val combined = (imageUris + incoming).distinct()
        if (combined.size > ReceiptImageBatch.MAX_IMAGES) {
            vm.notify("每次最多 ${ReceiptImageBatch.MAX_IMAGES} 张 还可添加 ${ReceiptImageBatch.MAX_IMAGES - imageUris.size} 张")
        } else {
            if (incoming.isNotEmpty() && combined.size == imageUris.size) vm.notify("这张图片已经选过了")
            imageUris = ArrayList(combined)
        }
    }
    fun discardImages(uris: List<Uri>) {
        scope.launch {
            try { cameraStore.discard(uris) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { vm.notify("临时照片清理失败 请稍后重试") }
        }
    }
    fun closeImages() {
        val discarded = imageUris.map(Uri::parse)
        scannerGeneration++
        showScanner = false
        imageUris = arrayListOf()
        discardImages(discarded)
        vm.cancelPhotoSelection()
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ReceiptImageBatch.MAX_IMAGES)) { uris ->
        if (showScanner && pickerGeneration == scannerGeneration) addImages(uris.map { it.toString() })
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraUri
        cameraUri = null
        if (success && uri != null && showScanner && cameraGeneration == scannerGeneration && imageUris.isEmpty()) {
            showScanner = false
            vm.recognize(listOf(Uri.parse(uri)))
        }
        else if (uri != null) discardImages(listOf(Uri.parse(uri)))
    }
    fun launchCamera() {
        if (cameraPreparing || cameraUri != null || imageUris.isNotEmpty()) return
        cameraPreparing = true
        val generation = scannerGeneration
        scope.launch {
            var created: Uri? = null
            var launched = false
            try {
                created = cameraStore.create()
                if (showScanner && generation == scannerGeneration) {
                    cameraUri = created.toString()
                    cameraGeneration = generation
                    takePhoto.launch(created)
                    launched = true
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { vm.notify("相机无法打开 请从相册选择") }
            finally {
                cameraPreparing = false
                if (!launched) {
                    cameraUri = null
                    withContext(NonCancellable) {
                        try { created?.let { cameraStore.discard(listOf(it)) } }
                        catch (_: Exception) { vm.notify("临时照片清理失败 请稍后重试") }
                    }
                }
            }
        }
    }
    fun continueWithImages(supplementId: String? = null) {
        vm.prepareNextScanRound(supplementId)
        scannerGeneration++
        showScanner = true
    }
    LaunchedEffect(vm) { vm.messages.collect { snackbarHost.showSnackbar(it) } }
    BackHandler(enabled = tab != 0 && detailId == null && cardId == null && !shareAll && vm.editor == null) { tab = 0 }
    val destinations = listOf("总览" to Icons.Outlined.Dashboard, "账本" to Icons.Outlined.ReceiptLong, "设置" to Icons.Outlined.Tune)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Row {
            if (wide) NavigationRail(Modifier.fillMaxHeight().statusBarsPadding(), containerColor = MaterialTheme.colorScheme.surface) {
                Spacer(Modifier.height(24.dp))
                destinations.forEachIndexed { index, pair -> NavigationRailItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(pair.second, pair.first) }, label = { Text(pair.first) }) }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    if (tab != 0) TopAppBar(title = { Text(destinations[tab].first, style = MaterialTheme.typography.titleLarge) }, actions = {
                        if (tab == 1) IconButton(onClick = vm::newReceipt, enabled = !vm.busy) { Icon(Icons.Outlined.Add, "新增账单") }
                    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
                },
                bottomBar = {
                    if (!wide) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        destinations.forEachIndexed { index, pair -> NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(pair.second, null) }, label = { Text(pair.first) }) }
                    }
                },
                snackbarHost = { SnackbarHost(snackbarHost) }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                        if (vm.busy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(vm.activityLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                if (vm.activityLabel.contains("识别") || vm.activityLabel.contains("连接") || vm.activityLabel.contains("图片")) TextButton(onClick = vm::cancelWork) { Text("取消") }
                            }
                        }
                        when (tab) {
                            0, 1 -> DashboardScreen(receipts, tab == 1, vm.busy, vm.loadError, vm::newReceipt, {
                                if (vm.settings.modelName.isBlank() || vm.settings.apiKey.isBlank() || vm.settingsError != null) showSetup = true
                                else { vm.beginScan(); scannerGeneration++; showScanner = true }
                            }, { detailId = it.id }, { tab = 1 }, onShareAll = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                shareAll = true
                            }, onDeleteSelection = { deletionIds = it.map { receipt -> receipt.id } })
                            2 -> SettingsScreen(vm.settings, vm.settingsError, vm.busy, vm::saveSettings, vm::testConnection, vm::resetSettings,
                                { format -> if (format == "json") exportJson.launch("TaxyRay-backup.json") else exportCsv.launch("TaxyRay-backup.csv") },
                                { importFile.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, vm::eraseEverything, { showPolicy = true }, onRepository = {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${UpdateSource.REPOSITORY}"))) }
                                        .onFailure { vm.notify("没有可用的浏览器") }
                                }, onCheckUpdates = { showUpdate = true })
                        }
                    }
                }
            }
        }
    }
    vm.editor?.let { draft -> ScannerReviewSheet(draft, vm.busy, vm::updateDraft, vm::closeEditor, vm::saveReceipt,
        returnToBatch = vm.editingScanBillId != null) }
    receipts.find { it.id == detailId }?.let { receipt ->
        ReceiptDetailSheet(receipt, vm.busy, { detailId = null }, { detailId = null; vm.edit(receipt) }, { detailId = null; vm.delete(receipt) }, {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            detailId = null
            cardId = receipt.id
        })
    }
    val cardReceipts = if (shareAll) receipts else receipts.filter { it.id == cardId }
    if (cardReceipts.isNotEmpty() && (shareAll || cardId != null)) {
        ContributionSheet(cardReceipts, shareAll, { cardId = null; shareAll = false }, onShare = { bitmap -> shareHelper.share(bitmap) }, onSave = { bitmap ->
            if (Build.VERSION.SDK_INT >= 29) { shareHelper.saveToGallery(bitmap); vm.notify("贡献卡已保存到相册 Pictures/TaxyRay") }
            else {
                pendingImagePath = withContext(Dispatchers.IO) {
                    val directory = context.cacheDir.resolve("share").apply { mkdirs() }
                    val file = directory.resolve("pending-${java.util.UUID.randomUUID()}.png")
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "图片编码失败" } }
                    file.absolutePath
                }
                saveImage.launch("TaxyRay-contribution.png")
            }
        }, onError = vm::notify)
    }
    if (showUpdate) UpdateDialog(onDismiss = { showUpdate = false })
    if (showSetup) AlertDialog(onDismissRequest = { showSetup = false }, icon = { Icon(Icons.Outlined.Key, null) }, title = { Text("配置你的账单识别服务") }, text = { Text("填写自己的 API Key 与支持图片识别的模型\n也可使用离线手动记账") }, confirmButton = { TextButton(onClick = { showSetup = false; tab = 2 }) { Text("前往设置") } }, dismissButton = { TextButton(onClick = { showSetup = false; vm.newReceipt() }) { Text("手动记账") } })
    if (showScanner) ReceiptImagesSheet(imageUris, vm.settings.baseUrl, vm.settings.modelName,
        cameraBusy = cameraPreparing || cameraUri != null,
        previousItemCount = vm.scanBills.find { it.id == vm.supplementBillId }?.draft?.items?.size ?: 0,
        onImagesChanged = { updated ->
            val removed = (imageUris - updated.toSet()).map(Uri::parse)
            imageUris = ArrayList(updated)
            discardImages(removed)
        },
        onPick = { pickerGeneration = scannerGeneration; pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onCamera = ::launchCamera, onSend = {
            val images = imageUris.map(Uri::parse)
            showScanner = false
            imageUris = arrayListOf()
            vm.recognize(images)
        }, onDismiss = ::closeImages)
    if (vm.scanReviewReady && !vm.busy && vm.editor == null && vm.scanDuplicateReview == null && vm.scanError == null) {
        BillBatchReviewSheet(vm.scanBills, vm.scanWarnings, vm.busy,
            onReview = vm::reviewScanBill, onSkip = vm::skipScanBill, onRestore = vm::restoreScanBill,
            onSupplement = { continueWithImages(it) }, onAddImages = { continueWithImages() }, onFinish = vm::discardScan,
            onBatchReview = vm::prepareBatchReview)
    }
    vm.batchReviewSummary?.let { summary ->
        BatchReviewSummaryDialog(summary, vm.busy, vm::confirmBatchReview, vm::dismissBatchReview)
    }
    if (!vm.busy) vm.scanError?.let { message ->
        ScanRecognitionFailureDialog(message, vm.scanBills.isNotEmpty(),
            onRetry = { continueWithImages(vm.supplementBillId) }, onFinish = vm::returnToScanReview, onDiscard = vm::discardScan)
    }
    vm.scanDuplicateReview?.let { review ->
        val group = review.groups[review.groupIndex]
        DuplicateItemsDialog(group.items, review.groupIndex, review.groups.size,
            onKeepOne = { vm.resolveScanDuplicate(group.items.first().id, it) },
            onKeepAll = { vm.resolveScanDuplicate(group.items.first().id) })
    }
    vm.duplicateReview?.let { DuplicateReceiptDialog(it, vm.busy, vm::dismissDuplicateReview, vm::confirmDuplicateReceipt, vm::keepExistingReceipt) }
    val deletionReceipts = receipts.filter { it.id in deletionIds }
    if (deletionReceipts.isNotEmpty()) DeleteReceiptsDialog(deletionReceipts, vm.busy,
        onDismiss = { deletionIds = emptyList() },
        onConfirm = { vm.deleteReceipts(deletionReceipts); deletionIds = emptyList() })
    vm.pendingImport?.let { incoming ->
        AlertDialog(onDismissRequest = vm::dismissImport, title = { Text("导入 ${incoming.size} 笔账单？") }, text = { Text("备份已通过结构和逐项计税校验 实付合计 ${money(incoming.sumOf { it.totalAmountCents })} \n\n将合并到本机账本 相同记录跳过 ID 相同但内容不同则整批停止 保留现有账本 ") }, confirmButton = { TextButton(onClick = vm::confirmImport, enabled = !vm.busy) { Text("确认导入") } }, dismissButton = { TextButton(onClick = vm::dismissImport, enabled = !vm.busy) { Text("取消") } })
    }
    if (showPolicy) ModalBottomSheet(onDismissRequest = { showPolicy = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("算法与税率口径", style = MaterialTheme.typography.titleLarge)
            Text("金额以整数分存储\n单项税额 = 实付 × 税率 ÷ (1 + 税率)\n逐项四舍五入到分 再汇总\n不含税金额 = 实付 − 税额")
            Text("例如 实付 ¥35.00 税率 13%\n税额 ¥4.03 不含税 ¥30.97\n税额占实付 11.51%")
            TaxRateGuide()
            Text("小票通常不能确认商户税务身份与税收优惠资格\nAI 按商品类别建议税率 品名不清楚时会标明\n可按票据修改金额和税率\n特殊减征规则不能直接用优惠比例替代税率")
            Text("不含税金额用实付减去已舍入税额\n避免重复舍入产生一分差额\n整单优惠或抹零可按实付分摊\n按商品金额比例分配 尾差补齐到分 再逐项计税")
            Text("政策核验 2026-09-15\n依据增值税法 实施条例与 2026 年第 9 号和第 10 号公告\n完整来源与计算案例见开源项目文档", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            InformationNote("仅供个人记账与估算\n贡献卡不是发票 完税证明或申报依据")
            TextButton(onClick = { showPolicy = false }) { Text("我知道了") }
        }
    }
}

@Composable
private fun ContributionSheet(receipts: List<Receipt>, cumulative: Boolean, onDismiss: () -> Unit, onShare: suspend (Bitmap) -> Unit, onSave: suspend (Bitmap) -> Unit, onError: (String) -> Unit) {
    val graphicsLayer = rememberGraphicsLayer()
    val exportLayer = rememberGraphicsLayer()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    var template by remember { mutableStateOf(CardTemplate.PAPER) }
    var exporting by remember { mutableStateOf(false) }
    var drawn by remember { mutableStateOf(false) }
    val currentExporting by rememberUpdatedState(exporting)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target -> target != SheetValue.Hidden || !currentExporting }
    )
    val ready = drawn
    suspend fun export(action: suspend (Bitmap) -> Unit) {
        exporting = true
        try {
            val sourceSize = graphicsLayer.size
            check(sourceSize.width > 0 && sourceSize.height > 0) { "贡献卡尚未绘制 请稍后重试" }
            val exportWidth = sourceSize.width.coerceAtLeast(1080)
            val scaleFactor = exportWidth.toDouble() / sourceSize.width
            val exportSize = IntSize(exportWidth, ceil(sourceSize.height * scaleFactor).toInt())
            // Replay the card's drawing commands at export resolution before rasterization.
            // The preview layer stays at its measured size; the sibling celebration is excluded.
            exportLayer.record(density, layoutDirection, exportSize) {
                scale(scaleFactor.toFloat(), pivot = Offset.Zero) { drawLayer(graphicsLayer) }
            }
            action(exportLayer.toImageBitmap().asAndroidBitmap())
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { onError(e.message ?: "图片导出失败 请重试") }
        finally { exporting = false }
    }
    ModalBottomSheet(
        onDismissRequest = { if (!exporting) onDismiss() },
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false)
    ) {
        BackHandler { if (!exporting) onDismiss() }
        Box(Modifier.fillMaxWidth().fillMaxHeight(.94f)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (cumulative) "累计贡献卡" else "贡献卡", style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss, enabled = !exporting) { Icon(Icons.Outlined.Close, "关闭贡献卡") }
                }
                Row(Modifier.padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(selected = template == CardTemplate.PAPER, enabled = !exporting, onClick = { if (template != CardTemplate.PAPER) { drawn = false; template = CardTemplate.PAPER } }, label = { Text("纸本") })
                    FilterChip(selected = template == CardTemplate.FOREST, enabled = !exporting, onClick = { if (template != CardTemplate.FOREST) { drawn = false; template = CardTemplate.FOREST } }, label = { Text("松石绿") })
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(18.dp)) {
                    val cardModifier = Modifier.fillMaxWidth().drawWithContent {
                        graphicsLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(graphicsLayer)
                        drawn = true
                    }
                    if (cumulative) CumulativeTaxContributionCard(receipts, modifier = cardModifier, template = template)
                    else TaxContributionCard(receipts.single(), modifier = cardModifier, template = template)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { scope.launch { export(onSave) } }, enabled = ready && !exporting, modifier = Modifier.weight(1f)) { Text(if (exporting) "正在导出…" else "保存图片") }
                    Button(onClick = { scope.launch { export(onShare) } }, enabled = ready && !exporting, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Share, null); Spacer(Modifier.width(8.dp)); Text("系统分享") }
                }
            }
            CelebrationOverlay(eventId = if (cumulative) "all-receipts" else receipts.single().id, modifier = Modifier.matchParentSize())
        }
    }
}
