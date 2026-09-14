@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.taxray

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
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
import io.github.taxray.data.update.UpdateSource
import io.github.taxray.ui.components.*
import io.github.taxray.ui.screens.*
import io.github.taxray.ui.theme.TaxLensTheme
import kotlinx.coroutines.*
import kotlin.math.ceil

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TaxLensTheme { TaxLensApp() } }
    }
}

@Composable
fun TaxLensApp(vm: TaxLensViewModel = viewModel()) {
    val receipts by vm.receipts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val shareHelper = remember(context) { ShareCardHelper(context) }
    val snackbarHost = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var cardId by rememberSaveable { mutableStateOf<String?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }
    var showPolicy by remember { mutableStateOf(false) }
    var showUpdate by rememberSaveable { mutableStateOf(false) }
    var imageToSend by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
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
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> imageToSend = uri?.toString() }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) imageToSend = cameraUri else scope.launch(Dispatchers.IO) { context.cacheDir.resolve("camera").deleteRecursively() }
    }
    LaunchedEffect(vm) { vm.messages.collect { snackbarHost.showSnackbar(it) } }
    DisposableEffect(tab) {
        val window = (context as? ComponentActivity)?.window
        if (tab == 2) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    BackHandler(enabled = tab != 0 && detailId == null && cardId == null && vm.editor == null) { tab = 0 }
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
                                if (vm.activityLabel.contains("识别") || vm.activityLabel.contains("连接")) TextButton(onClick = vm::cancelWork) { Text("取消") }
                            }
                        }
                        when (tab) {
                            0, 1 -> DashboardScreen(receipts, tab == 1, vm.busy, vm.loadError, vm::newReceipt, {
                                if (vm.settings.modelName.isBlank() || vm.settings.apiKey.isBlank() || vm.settingsError != null) showSetup = true else showScanner = true
                            }, { detailId = it.id }, { tab = 1 })
                            2 -> SettingsScreen(vm.settings, vm.settingsError, vm.busy, vm::saveSettings, vm::testConnection, vm::resetSettings,
                                { format -> if (format == "json") exportJson.launch("TaxLens-backup.json") else exportCsv.launch("TaxLens-backup.csv") },
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
    vm.editor?.let { draft -> ScannerReviewSheet(draft, vm.busy, vm::updateDraft, vm::closeEditor, vm::saveReceipt) }
    receipts.find { it.id == detailId }?.let { receipt ->
        ReceiptDetailSheet(receipt, vm.busy, { detailId = null }, { detailId = null; vm.edit(receipt) }, { detailId = null; vm.delete(receipt) }, {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            detailId = null
            cardId = receipt.id
        })
    }
    receipts.find { it.id == cardId }?.let { receipt ->
        ContributionSheet(receipt, { cardId = null }, onShare = { bitmap -> shareHelper.share(bitmap) }, onSave = { bitmap ->
            if (Build.VERSION.SDK_INT >= 29) { shareHelper.saveToGallery(bitmap); vm.notify("贡献卡已保存到相册 Pictures/TaxRay") }
            else {
                pendingImagePath = withContext(Dispatchers.IO) {
                    val directory = context.cacheDir.resolve("share").apply { mkdirs() }
                    val file = directory.resolve("pending-${java.util.UUID.randomUUID()}.png")
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "图片编码失败" } }
                    file.absolutePath
                }
                saveImage.launch("TaxLens-contribution.png")
            }
        }, onError = vm::notify)
    }
    if (showUpdate) UpdateDialog(onDismiss = { showUpdate = false })
    if (showSetup) AlertDialog(onDismissRequest = { showSetup = false }, icon = { Icon(Icons.Outlined.Key, null) }, title = { Text("配置你的小票识别服务") }, text = { Text("填写自己的 API Key 与支持图片识别的模型\n也可使用离线手动记账") }, confirmButton = { TextButton(onClick = { showSetup = false; tab = 2 }) { Text("前往设置") } }, dismissButton = { TextButton(onClick = { showSetup = false; vm.newReceipt() }) { Text("手动记账") } })
    if (showScanner) AlertDialog(onDismissRequest = { showScanner = false }, title = { Text("添加小票图片") }, text = { Text("图片先在本机压缩\n确认后才发送给你配置的 API") }, confirmButton = { TextButton(onClick = { showScanner = false; pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("从相册选择") } }, dismissButton = { TextButton(onClick = {
        showScanner = false
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { shareHelper.createCameraUri() } }
                .onSuccess { uri -> cameraUri = uri.toString(); try { takePhoto.launch(uri) } catch (_: Exception) { vm.notify("没有可用的相机应用 请从相册选择") } }
                .onFailure { vm.notify("相机暂存文件创建失败 请重试") }
        }
    }) { Text("拍摄小票") } })
    imageToSend?.let { image ->
        AlertDialog(onDismissRequest = { imageToSend = null; scope.launch(Dispatchers.IO) { context.cacheDir.resolve("camera").deleteRecursively() } }, title = { Text("确认发送这张小票？") }, text = { Text("图片压缩至长边不超过 1920 像素和 1 MB 以内\n发送至\n\n${vm.settings.baseUrl}\n模型：${vm.settings.modelName}\n\n图片中的商户 消费记录和个人信息将交由该服务处理\n可能产生 API 费用\n识别后确认金额与税率再入账") }, confirmButton = { TextButton(onClick = { imageToSend = null; vm.recognize(Uri.parse(image)) }) { Text("发送并识别") } }, dismissButton = { TextButton(onClick = { imageToSend = null; scope.launch(Dispatchers.IO) { context.cacheDir.resolve("camera").deleteRecursively() } }) { Text("取消") } })
    }
    vm.pendingImport?.let { incoming ->
        AlertDialog(onDismissRequest = vm::dismissImport, title = { Text("导入 ${incoming.size} 笔账单？") }, text = { Text("备份已通过结构和逐项计税校验 实付合计 ${money(incoming.sumOf { it.totalAmountCents })} \n\n将合并到本机账本 相同记录跳过 ID 相同但内容不同则整批停止 保留现有账本 ") }, confirmButton = { TextButton(onClick = vm::confirmImport, enabled = !vm.busy) { Text("确认导入") } }, dismissButton = { TextButton(onClick = vm::dismissImport, enabled = !vm.busy) { Text("取消") } })
    }
    if (showPolicy) ModalBottomSheet(onDismissRequest = { showPolicy = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("算法与税率口径", style = MaterialTheme.typography.titleLarge)
            Text("金额以整数分存储\n单项税额 = 实付 × 税率 ÷ (1 + 税率)\n逐项四舍五入到分 再汇总\n不含税金额 = 实付 − 税额")
            Text("例如 实付 ¥35.00 税率 13%\n税额 ¥4.03 不含税 ¥30.97\n税额占实付 11.51%")
            TaxRateGuide()
            Text("小票通常不能确认商户身份与优惠\nAI 按商品类别建议税率 品名不清楚时会标明\n可按票据修改金额和税率\n特殊减征规则不能直接用优惠比例替代税率")
            Text("不含税金额用实付减去已舍入税额\n避免重复舍入产生一分差额\n整单优惠应分配到各项折后实付")
            Text("政策核验 2026-09-15\n依据增值税法 实施条例与 2026 年第 9 号和第 10 号公告\n完整来源与计算案例见开源项目文档", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            InformationNote("仅供个人记账与估算\n贡献卡不是发票 完税证明或申报依据")
            TextButton(onClick = { showPolicy = false }) { Text("我知道了") }
        }
    }
}

@Composable
private fun ContributionSheet(receipt: Receipt, onDismiss: () -> Unit, onShare: suspend (Bitmap) -> Unit, onSave: suspend (Bitmap) -> Unit, onError: (String) -> Unit) {
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
                    Text("贡献卡", style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss, enabled = !exporting) { Icon(Icons.Outlined.Close, "关闭贡献卡") }
                }
                Row(Modifier.padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(selected = template == CardTemplate.PAPER, enabled = !exporting, onClick = { if (template != CardTemplate.PAPER) { drawn = false; template = CardTemplate.PAPER } }, label = { Text("纸本") })
                    FilterChip(selected = template == CardTemplate.FOREST, enabled = !exporting, onClick = { if (template != CardTemplate.FOREST) { drawn = false; template = CardTemplate.FOREST } }, label = { Text("松石绿") })
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(18.dp)) {
                    TaxContributionCard(receipt, modifier = Modifier.fillMaxWidth().drawWithContent {
                        graphicsLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(graphicsLayer)
                        drawn = true
                    }, template = template)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { scope.launch { export(onSave) } }, enabled = ready && !exporting, modifier = Modifier.weight(1f)) { Text(if (exporting) "正在导出…" else "保存图片") }
                    Button(onClick = { scope.launch { export(onShare) } }, enabled = ready && !exporting, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Share, null); Spacer(Modifier.width(8.dp)); Text("系统分享") }
                }
            }
            CelebrationOverlay(eventId = receipt.id, modifier = Modifier.matchParentSize())
        }
    }
}
