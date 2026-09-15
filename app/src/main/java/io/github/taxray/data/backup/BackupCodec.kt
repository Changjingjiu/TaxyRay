package io.github.taxray.data.backup

import io.github.taxray.core.CalculatedItem
import io.github.taxray.core.Receipt
import io.github.taxray.core.TaxBreakdown
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Versioned, key-free ledger backup. No settings, API credentials or photos are exported. */
object BackupCodec {
    private const val VERSION = 1
    private const val FORMAT = "taxray-ledger"
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
    }
    private val csvHeader = listOf(
        "version", "receipt_id", "store_name", "timestamp", "item_id", "position", "name",
        "amount_cents", "tax_rate_bps", "pre_tax_cents", "tax_cents", "category_reason",
        "total_amount_cents", "total_tax_cents", "total_pre_tax_cents",
    )

    fun toJson(receipts: List<Receipt>): String {
        validateExportReceipts(receipts)
        return json.encodeToString(BackupDocument(
            format = FORMAT,
            version = VERSION,
            receipts = receipts.map { BackupReceipt.from(it) },
        )).also(::validateOutputSize)
    }

    fun fromJson(text: String): List<Receipt> {
        validateInputSize(text)
        val normalized = text.removePrefix("\uFEFF")
        checkJsonDepth(normalized)
        val document = try {
            json.decodeFromString<BackupDocument>(normalized)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("JSON 备份格式无效，请选择 TaxyRay 导出的完整备份", error)
        }
        require(document.format == FORMAT) { "不是 TaxyRay 账本备份" }
        require(document.version == VERSION) { "不支持备份版本 ${document.version}，当前仅支持版本 1" }
        require(document.receipts.size <= ReceiptValidation.MAX_RECEIPTS) { "单次最多导入 10000 张账单" }
        return document.receipts.map { it.toReceipt() }.also(::validateDuplicateIds)
    }

    /** RFC 4180, CRLF record separators, UTF-8 BOM, one item per record. */
    fun toCsv(receipts: List<Receipt>): String {
        validateExportReceipts(receipts)
        return buildString {
        append('\uFEFF')
        append(csvHeader.joinToString(","))
        append("\r\n")
        receipts.forEach { receipt ->
            receipt.items.forEachIndexed { position, item ->
                val row = listOf(
                    VERSION.toString(),
                    protectSpreadsheetText(receipt.id),
                    protectSpreadsheetText(receipt.storeName),
                    receipt.timestamp.toString(),
                    protectSpreadsheetText(item.id),
                    position.toString(),
                    protectSpreadsheetText(item.name),
                    item.breakdown.amountCents.toString(),
                    item.breakdown.taxRateBps.toString(),
                    item.breakdown.preTaxCents.toString(),
                    item.breakdown.taxCents.toString(),
                    protectSpreadsheetText(item.categoryReason),
                    receipt.totalAmountCents.toString(),
                    receipt.totalTaxCents.toString(),
                    receipt.totalPreTaxCents.toString(),
                )
                append(row.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" })
                append("\r\n")
            }
        }
        }.also(::validateOutputSize)
    }

    fun fromCsv(text: String): List<Receipt> {
        validateInputSize(text)
        val rows = parseCsv(text.removePrefix("\uFEFF"))
        require(rows.isNotEmpty() && rows.first() == csvHeader) { "CSV 表头不匹配，请选择 TaxyRay 版本 1 备份" }
        val groups = linkedMapOf<String, MutableList<CsvItem>>()
        rows.drop(1).forEachIndexed { index, row ->
            val rowNumber = index + 2
            require(row.size == csvHeader.size) { "CSV 第 $rowNumber 行列数不正确" }
            require(row[0] == VERSION.toString()) { "CSV 第 $rowNumber 行版本不受支持" }
            fun number(column: Int): Long = row[column].toLongOrNull()
                ?: throw IllegalArgumentException("CSV 第 $rowNumber 行的 ${csvHeader[column]} 不是有效整数")
            val rate = number(8)
            val position = number(5)
            require(rate in 0..10000 && position in 0 until ReceiptValidation.MAX_ITEMS.toLong()) {
                "CSV 第 $rowNumber 行税率或顺序超出范围"
            }
            val id = restoreSpreadsheetText(row[1])
            val item = CsvItem(
                storeName = restoreSpreadsheetText(row[2]),
                timestamp = number(3),
                position = position.toInt(),
                item = BackupItem(
                    id = restoreSpreadsheetText(row[4]),
                    name = restoreSpreadsheetText(row[6]),
                    amountCents = number(7),
                    taxRateBps = rate.toInt(),
                    preTaxCents = number(9),
                    taxCents = number(10),
                    categoryReason = restoreSpreadsheetText(row[11]),
                ),
                totalAmountCents = number(12),
                totalTaxCents = number(13),
                totalPreTaxCents = number(14),
            )
            groups.getOrPut(id) { mutableListOf() }.add(item)
            require(groups.size <= ReceiptValidation.MAX_RECEIPTS) { "单次最多导入 10000 张账单" }
            require(groups.getValue(id).size <= ReceiptValidation.MAX_ITEMS) { "每张账单最多 1000 个商品项" }
        }
        return groups.map { (id, rowsForReceipt) ->
            val first = rowsForReceipt.first()
            require(rowsForReceipt.all {
                it.storeName == first.storeName && it.timestamp == first.timestamp &&
                    it.totalAmountCents == first.totalAmountCents && it.totalTaxCents == first.totalTaxCents &&
                    it.totalPreTaxCents == first.totalPreTaxCents
            }) { "账单 $id 的 CSV 元数据或合计互相冲突" }
            val sorted = rowsForReceipt.sortedBy { it.position }
            require(sorted.map { it.position } == sorted.indices.toList()) { "账单 $id 的商品顺序缺失或重复" }
            BackupReceipt(
                id, first.storeName, first.timestamp, first.totalAmountCents, first.totalPreTaxCents,
                first.totalTaxCents, sorted.map { it.item },
            ).toReceipt()
        }
    }

    private fun validateInputSize(text: String) {
        require(text.length <= ReceiptValidation.MAX_BACKUP_BYTES &&
            text.toByteArray(Charsets.UTF_8).size <= ReceiptValidation.MAX_BACKUP_BYTES) { "备份文件不能超过 5 MB" }
    }

    private fun validateExportReceipts(receipts: List<Receipt>) {
        require(receipts.size <= ReceiptValidation.MAX_RECEIPTS) {
            "单个备份最多支持 10000 张账单；当前账本超出限额，无法导出可恢复的备份"
        }
        require(receipts.map { it.id }.distinct().size == receipts.size) { "导出账本中存在重复账单 ID" }
        receipts.forEach(ReceiptValidation::validate)
    }

    private fun validateOutputSize(text: String) {
        require(text.length <= ReceiptValidation.MAX_BACKUP_BYTES &&
            text.toByteArray(Charsets.UTF_8).size <= ReceiptValidation.MAX_BACKUP_BYTES) {
            "备份超过 5 MB（5000000 字节），无法导出可恢复的备份；账本内容尚未写入文件"
        }
    }

    // A ledger has a small, fixed nesting depth. Reject adversarial nesting before decoding.
    private fun checkJsonDepth(text: String) {
        var inString = false
        var escaped = false
        var depth = 0
        text.forEach { char ->
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
            } else when (char) {
                '"' -> inString = true
                '{', '[' -> { depth++; require(depth <= 32) { "JSON 备份嵌套过深" } }
                '}', ']' -> depth--
            }
        }
    }

    private fun validateDuplicateIds(receipts: List<Receipt>) {
        val byId = mutableMapOf<String, Receipt>()
        receipts.forEach { receipt ->
            val previous = byId.putIfAbsent(receipt.id, receipt)
            require(previous == null || previous == receipt) { "备份内账单 ID ${receipt.id} 重复且内容不同" }
        }
    }

    // Escape a leading apostrophe too, so importing our own export is lossless.
    // Tabs/newlines and whitespace before an operator must not bypass formula protection.
    private fun protectSpreadsheetText(value: String): String =
        if (value.startsWith("'") || value.firstOrNull() in listOf('\t', '\r', '\n') ||
            value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value

    private fun restoreSpreadsheetText(value: String): String = value.removePrefix("'")

    /** Small strict RFC 4180 parser; quoted fields may contain commas, CRLF and quotes. */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var afterQuote = false
        var index = 0
        var recordStarted = false
        fun finishField() {
            row.add(field.toString())
            require(row.size <= csvHeader.size) { "CSV 列数过多" }
            field.setLength(0)
            afterQuote = false
        }
        fun finishRow() {
            finishField()
            require(row.size == csvHeader.size) { "CSV 每行应有 ${csvHeader.size} 列" }
            rows.add(row)
            row = mutableListOf()
            recordStarted = false
        }
        while (index < text.length) {
            val char = text[index]
            if (quoted) {
                when {
                    char == '"' && index + 1 < text.length && text[index + 1] == '"' -> { field.append('"'); index++ }
                    char == '"' -> { quoted = false; afterQuote = true }
                    else -> field.append(char)
                }
            } else when (char) {
                '"' -> {
                    require(field.isEmpty() && !afterQuote) { "CSV 引号位置不正确" }
                    quoted = true
                    recordStarted = true
                }
                ',' -> { finishField(); recordStarted = true }
                '\r', '\n' -> {
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    finishRow()
                }
                else -> {
                    require(!afterQuote) { "CSV 引号关闭后存在多余字符" }
                    field.append(char)
                    recordStarted = true
                }
            }
            index++
        }
        require(!quoted) { "CSV 引号未闭合" }
        if (recordStarted || row.isNotEmpty() || field.isNotEmpty()) finishRow()
        return rows
    }

    @Serializable
    private data class BackupDocument(val format: String, val version: Int, val receipts: List<BackupReceipt>)

    @Serializable
    private data class BackupReceipt(
        val id: String,
        val storeName: String,
        val timestamp: Long,
        val totalAmountCents: Long,
        val totalPreTaxCents: Long,
        val totalTaxCents: Long,
        val items: List<BackupItem>,
    ) {
        fun toReceipt(): Receipt {
            val receipt = ReceiptValidation.validate(Receipt(id, storeName, timestamp, items.map { it.toItem() }))
            require(receipt.totalAmountCents == totalAmountCents && receipt.totalPreTaxCents == totalPreTaxCents &&
                receipt.totalTaxCents == totalTaxCents) { "账单 $id 的汇总金额与本地重算结果不一致" }
            return receipt
        }

        companion object {
            fun from(receipt: Receipt) = BackupReceipt(
                receipt.id, receipt.storeName, receipt.timestamp,
                receipt.totalAmountCents, receipt.totalPreTaxCents, receipt.totalTaxCents,
                receipt.items.map { BackupItem.from(it) },
            )
        }
    }

    @Serializable
    private data class BackupItem(
        val id: String,
        val name: String,
        val amountCents: Long,
        val taxRateBps: Int,
        val preTaxCents: Long,
        val taxCents: Long,
        val categoryReason: String,
    ) {
        fun toItem() = CalculatedItem(id, name, TaxBreakdown(amountCents, taxRateBps, preTaxCents, taxCents), categoryReason)
        companion object {
            fun from(item: CalculatedItem) = BackupItem(
                item.id, item.name, item.breakdown.amountCents, item.breakdown.taxRateBps,
                item.breakdown.preTaxCents, item.breakdown.taxCents, item.categoryReason,
            )
        }
    }

    private data class CsvItem(
        val storeName: String,
        val timestamp: Long,
        val position: Int,
        val item: BackupItem,
        val totalAmountCents: Long,
        val totalTaxCents: Long,
        val totalPreTaxCents: Long,
    )
}
