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
import io.github.taxray.data.security.ApiSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

data class DuplicateReceiptReview(val draft: ReceiptDraft, val matches: List<Receipt>, val continueAdding: Boolean)

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
    fun recognize(uris: List<Uri>) = work("正在压缩小票图片") {
        try {
            require(uris.size in 1..ReceiptImageBatch.MAX_IMAGES) { "每次请选择 1 至 ${ReceiptImageBatch.MAX_IMAGES} 张图片" }
            val compressor = ImageCompressor(app)
            val images = uris.mapIndexed { index, uri ->
                activityLabel = "正在处理第 ${index + 1} / ${uris.size} 张图片"
                compressor.compress(uri).bytes
            }
            activityLabel = "正在识别 ${images.size} 张小票图片"
            val result = service.parseReceipt(settings, images)
            val timestamp = result.receiptDateTime?.let { LocalDateTime.parse(it).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            editor = ReceiptDraft(storeName = result.storeName, timestamp = timestamp ?: System.currentTimeMillis(),
                items = result.items, declaredTotal = result.declaredTotal.orEmpty(), fromVision = true,
                receiptDiscount = result.discount,
                warnings = result.warnings + if (timestamp == null) listOf("未读到完整消费时间 已用当前时间 可点击修改") else emptyList())
            notify("识别完成 确认金额与税率后即可入账")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            editor = ReceiptDraft(warnings = listOf("识别未完成 ${e.message ?: "请重试"} 可在这里手动录入"))
            notify("识别未完成，可手动补录或重新选图")
        } finally {
            withContext(NonCancellable) { CameraCaptureStore(app).discard(uris) }
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
        notify("本机账本、API 配置和临时图片已清空")
    }
}
