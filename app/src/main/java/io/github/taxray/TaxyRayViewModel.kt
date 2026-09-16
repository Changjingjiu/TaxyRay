package io.github.taxray

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.taxray.core.*
import io.github.taxray.data.backup.BackupCodec
import io.github.taxray.data.remote.ImageCompressor
import io.github.taxray.data.remote.ReceiptImageBatch
import io.github.taxray.data.remote.CameraCaptureStore
import io.github.taxray.data.remote.VisionAgentService
import io.github.taxray.data.remote.VisionReceipt
import io.github.taxray.data.security.ApiSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.UUID

data class DuplicateReceiptReview(val draft: ReceiptDraft, val matches: List<Receipt>, val continueAdding: Boolean)
data class ScanDuplicateReview(val draft: ReceiptDraft, val groups: List<DuplicateItemGroup>, val groupIndex: Int = 0)

class TaxyRayViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TaxyRayApplication
    private val repository = app.receipts
    private val service = VisionAgentService()
    private val notices = Channel<String>(Channel.BUFFERED)
    val messages = notices.receiveAsFlow()
    var loadError by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var activityLabel by mutableStateOf(""); private set
    var editor by mutableStateOf<ReceiptDraft?>(null); private set
    var settings by mutableStateOf(ApiSettings()); private set
    var settingsError by mutableStateOf<String?>(null); private set
    var pendingImport by mutableStateOf<List<Receipt>?>(null); private set
    var duplicateReview by mutableStateOf<DuplicateReceiptReview?>(null); private set
    var scanSession by mutableStateOf<ReceiptScanSession?>(null); private set
    var scanRoundReady by mutableStateOf(false); private set
    var scanError by mutableStateOf<String?>(null); private set
    var scanDuplicateReview by mutableStateOf<ScanDuplicateReview?>(null); private set
    private var activeJob: Job? = null

    val receipts = repository.receipts
        .catch { loadError = "账本读取失败，请保留设备数据并重试。${it.message.orEmpty().take(120)}" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { app.securePreferences.read() } }
                .onSuccess { settings = it }
                .onFailure { settingsError = "加密配置无法读取，请在设置中重置 API 配置后重新填写。" }
        }
    }

    fun notify(message: String) { notices.trySend(message) }
    private fun work(label: String, task: suspend () -> Unit) {
        if (busy) return
        busy = true
        activityLabel = label
        activeJob = viewModelScope.launch {
            try { task() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notify(e.message ?: "操作未完成，请重试") }
            finally { busy = false; activityLabel = "" }
        }
    }
    fun cancelWork() { activeJob?.cancel(); notify("已取消操作") }
    fun newReceipt() { if (!busy) { duplicateReview = null; editor = ReceiptDraft() } }
    fun edit(receipt: Receipt) {
        if (busy) return
        duplicateReview = null
        editor = ReceiptDraft(id = receipt.id, storeName = receipt.storeName, timestamp = receipt.timestamp,
            items = receipt.items.map { DraftItem(it.id, it.name, TaxCalculator.formatMoney(it.breakdown.amountCents), TaxCalculator.formatRate(it.breakdown.taxRateBps), it.categoryReason) })
    }
    fun updateDraft(value: ReceiptDraft) { if (!busy) { duplicateReview = null; editor = value } }
    fun closeEditor() { if (!busy) { duplicateReview = null; editor = null } }
    fun saveReceipt(continueAdding: Boolean = false) {
        val draft = editor ?: return
        val error = draft.validationMessage()
        if (error != null) { notify(error); return }
        work("正在保存账单") {
            if (draft.fromVision && draft.id == null) {
                val candidate = Receipt(UUID.randomUUID().toString(), draft.storeName, draft.timestamp, draft.calculated())
                val matches = withContext(Dispatchers.Default) { ReceiptDuplicates.find(candidate, repository.all()) }
                if (matches.isNotEmpty()) {
                    duplicateReview = DuplicateReceiptReview(draft, matches, continueAdding)
                    return@work
                }
            }
            persistDraft(draft, continueAdding)
        }
    }
    fun dismissDuplicateReview() { if (!busy) duplicateReview = null }
    fun keepExistingReceipt() {
        if (busy) return
        val review = duplicateReview ?: return
        if (editor != review.draft) { duplicateReview = null; return }
        duplicateReview = null
        editor = if (review.continueAdding) ReceiptDraft(storeName = review.draft.storeName) else null
        notify("已保留已有账单 本次未重复入账")
    }
    fun confirmDuplicateReceipt() {
        val review = duplicateReview ?: return
        if (editor != review.draft) { duplicateReview = null; return }
        work("正在保存账单") { persistDraft(review.draft, review.continueAdding) }
    }
    private suspend fun persistDraft(draft: ReceiptDraft, continueAdding: Boolean) {
        repository.save(draft.storeName, draft.settledItems(), draft.id, draft.timestamp)
        duplicateReview = null
        editor = if (continueAdding) ReceiptDraft(storeName = draft.storeName) else null
        notify("已保存到本地账本")
    }
    fun delete(receipt: Receipt) = deleteReceipts(listOf(receipt))
    fun deleteReceipts(selected: List<Receipt>) = work("正在删除账单") {
        repository.deleteAll(selected.map { it.id })
        notify("已删除 ${selected.map { it.id }.distinct().size} 笔账单")
    }
    fun saveSettings(value: ApiSettings) = work("正在加密保存") {
        withContext(Dispatchers.IO) { app.securePreferences.save(value) }
        settings = value; settingsError = null; notify("API 配置已加密保存在本机")
    }
    fun resetSettings() = work("正在重置配置") {
        withContext(Dispatchers.IO) { app.securePreferences.clear() }
        settings = ApiSettings(); settingsError = null; notify("API 配置及其加密密钥已重置")
    }
    fun testConnection(value: ApiSettings) = work("正在测试模型连接") { notify(service.testConnection(value)) }
    fun beginScan() {
        if (busy) return
        scanSession = ReceiptScanSession()
        scanRoundReady = false; scanError = null; scanDuplicateReview = null
    }
    fun prepareNextScanRound() {
        if (busy) return
        if (scanSession == null) {
            scanSession = ReceiptScanSession()
            notify("此前未入账的识别已失效 请重新拍摄")
        }
        scanRoundReady = false; scanError = null
    }
    fun cancelPhotoSelection() {
        if (busy) return
        if (scanSession?.rounds?.isNotEmpty() == true) { scanError = null; scanRoundReady = true }
        else discardScan()
    }
    fun discardScan() {
        if (busy) return
        scanSession = null; scanRoundReady = false; scanError = null; scanDuplicateReview = null
    }
    /** The network result only extends a pending session. Nothing is persisted here. */
    internal fun acceptScanRound(result: VisionReceipt) {
        val session = requireNotNull(scanSession) { "本次录入已结束 请重新拍摄" }
        scanSession = session.append(result)
        scanError = null; scanRoundReady = true
    }
    fun finishScan() {
        if (busy) return
        val session = scanSession ?: return
        if (session.rounds.isEmpty()) return
        work("正在整理识别结果") {
            val draft = session.draft()
            val groups = withContext(Dispatchers.Default) { ReceiptItemDuplicates.find(draft.items) }
            scanRoundReady = false; scanError = null
            if (groups.isEmpty()) { editor = draft; scanSession = null }
            else scanDuplicateReview = ScanDuplicateReview(draft, groups)
        }
    }
    fun resolveScanDuplicate(groupId: String, keepId: String? = null) {
        if (busy) return
        val review = scanDuplicateReview ?: return
        val group = review.groups[review.groupIndex]
        if (group.items.first().id != groupId) return
        if (keepId != null && group.items.none { it.id == keepId }) return
        val removedIds = if (keepId == null) emptySet() else group.items.filter { it.id != keepId }.map { it.id }.toSet()
        val draft = review.draft.copy(items = review.draft.items.filterNot { it.id in removedIds })
        val next = review.groupIndex + 1
        if (next < review.groups.size) scanDuplicateReview = review.copy(draft = draft, groupIndex = next)
        else { editor = draft; scanDuplicateReview = null; scanSession = null }
    }
    fun recognize(uris: List<Uri>) = work("正在压缩小票图片") {
        try {
            requireNotNull(scanSession) { "录入已中断 此前未保存的内容已失效 请重新拍摄" }
            require(uris.size in 1..ReceiptImageBatch.MAX_IMAGES) { "每次请选择 1 至 ${ReceiptImageBatch.MAX_IMAGES} 张图片" }
            val compressor = ImageCompressor(app)
            val images = uris.mapIndexed { index, uri ->
                activityLabel = "正在处理第 ${index + 1} / ${uris.size} 张图片"
                compressor.compress(uri).bytes
            }
            activityLabel = "正在识别 ${images.size} 张小票图片"
            val result = service.parseReceipt(settings, images)
            acceptScanRound(result)
        } catch (e: CancellationException) {
            scanError = "本轮识别已取消 已识别内容仍然保留"
            throw e
        }
        catch (e: Exception) {
            scanError = e.message ?: "本轮识别未完成 请重新拍摄"
        } finally {
            withContext(NonCancellable) {
                try { CameraCaptureStore(app).discard(uris) }
                catch (_: Exception) { notify("临时照片清理失败 请稍后重试") }
            }
        }
    }
    fun export(uri: Uri, format: String) = work("正在导出账本") {
        withContext(Dispatchers.IO) {
            val all = repository.all()
            val content = if (format == "csv") BackupCodec.toCsv(all) else BackupCodec.toJson(all)
            app.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
                ?: error("无法写入所选文件，请换一个保存位置")
        }
        notify("账本已导出，不包含 API 密钥和小票图片")
    }
    fun previewImport(uri: Uri) = work("正在校验备份") {
        pendingImport = withContext(Dispatchers.IO) {
            val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    require(output.size() + count <= 5_000_000) { "备份文件超过 5 MB，请拆分后导入" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("无法读取备份文件")
            val content = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            } catch (_: java.nio.charset.CharacterCodingException) { error("备份不是有效的 UTF-8 文件，请重新导出") }
            if (content.trimStart().startsWith("{")) BackupCodec.fromJson(content) else BackupCodec.fromCsv(content)
        }
    }
    fun dismissImport() { if (!busy) pendingImport = null }
    fun confirmImport() {
        val incoming = pendingImport ?: return
        work("正在原子导入") {
            val count = repository.importReceipts(incoming)
            pendingImport = null
            notify("已导入 $count 笔账单；相同记录已自动跳过")
        }
    }
    fun eraseEverything() = work("正在清空本机数据") {
        withContext(Dispatchers.IO) {
            repository.clear()
            app.securePreferences.clear()
            app.cacheDir.resolve("share").deleteRecursively()
            app.cacheDir.resolve("camera").deleteRecursively()
        }
        settings = ApiSettings(); settingsError = null; editor = null; pendingImport = null; duplicateReview = null
        scanSession = null; scanRoundReady = false; scanError = null; scanDuplicateReview = null
        notify("本机账本、API 配置和临时图片已清空")
    }
}
