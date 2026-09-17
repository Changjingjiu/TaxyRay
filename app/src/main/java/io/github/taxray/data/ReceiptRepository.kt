package io.github.taxray.data

import androidx.room.withTransaction
import io.github.taxray.core.CalculatedItem
import io.github.taxray.core.DraftItem
import io.github.taxray.core.Receipt
import io.github.taxray.core.TaxBreakdown
import io.github.taxray.core.TaxCalculator
import io.github.taxray.data.backup.ReceiptValidation
import io.github.taxray.data.local.AppDatabase
import io.github.taxray.data.local.ReceiptEntity
import io.github.taxray.data.local.ReceiptItemEntity
import io.github.taxray.data.local.ReceiptWithItems
import io.github.taxray.ReceiptDraft
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class ReceiptRepository(private val database: AppDatabase) {
    private val dao = database.receiptDao()

    val receipts: Flow<List<Receipt>> = dao.observeReceipts()
        .map { rows -> rows.map { it.toReceipt() } }
        .flowOn(Dispatchers.Default)

    suspend fun save(
        storeName: String,
        items: List<DraftItem>,
        id: String? = null,
        timestamp: Long? = null,
    ): String = database.withTransaction { upsert(id, storeName, items, timestamp) }

    /** One transaction books the whole batch: a rejected draft rolls back every other draft with it. */
    suspend fun saveBatch(drafts: List<ReceiptDraft>): List<String> = database.withTransaction {
        drafts.map { upsert(it.id, it.storeName, it.settledItems(), it.timestamp) }
    }

    /** Caller owns the transaction; single and batch writes share one validation and capacity path. */
    private suspend fun upsert(id: String?, storeName: String, items: List<DraftItem>, timestamp: Long?): String {
        val previous = id?.let { requireNotNull(dao.find(it)) { "此账单已被删除，请重新创建" } }
        val receipt = ReceiptValidation.validate(Receipt(
            id = id ?: UUID.randomUUID().toString(),
            storeName = storeName.trim(),
            // Omission preserves history; an explicit date is a user-requested edit.
            timestamp = timestamp ?: previous?.receipt?.timestamp ?: System.currentTimeMillis(),
            items = TaxCalculator.calculateItems(items),
        ))
        if (previous != null) dao.deleteReceipt(receipt.id)
        checkLedgerCapacity(receipt.totalAmountCents)
        insert(receipt)
        return receipt.id
    }

    suspend fun delete(id: String) = dao.deleteReceipt(id)

    suspend fun deleteAll(ids: List<String>) = database.withTransaction {
        // Stay under SQLite's bound-parameter limit even for a large selection.
        ids.distinct().chunked(500).forEach { dao.deleteReceipts(it) }
    }

    suspend fun all(): List<Receipt> = withContext(Dispatchers.Default) { dao.all().map { it.toReceipt() } }

    suspend fun importReceipts(receipts: List<Receipt>): Int {
        require(receipts.size <= ReceiptValidation.MAX_RECEIPTS) { "单次最多导入 10000 张账单" }
        val validated = withContext(Dispatchers.Default) { receipts.map(ReceiptValidation::validate) }
        return database.withTransaction {
            var imported = 0
            validated.forEach { receipt ->
                val existing = dao.find(receipt.id)?.toReceipt()
                if (existing != null) {
                    require(existing == receipt) { "账单 ID ${receipt.id} 已存在且内容不同，导入已全部撤回" }
                } else {
                    checkLedgerCapacity(receipt.totalAmountCents)
                    insert(receipt)
                    imported++
                }
            }
            imported
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        // Room clearAllTables executes a transaction, WAL FULL checkpoint, then VACUUM.
        // Keep the Room-managed database open so observable queries remain valid.
        database.clearAllTables()
        // VACUUM may itself write WAL pages. Truncate only after Room finishes it.
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { result ->
            check(result.moveToFirst() && result.getInt(0) == 0) {
                "账本已清空，但数据库日志整理尚未完成，请重试清除"
            }
        }
    }

    private suspend fun checkLedgerCapacity(additionalCents: Long) {
        try {
            Math.addExact(dao.totals().totalAmountCents, additionalCents)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("账本累计金额超出安全整数范围")
        }
    }

    private suspend fun insert(receipt: Receipt) {
        dao.insertReceipt(ReceiptEntity(
            receipt.id, receipt.storeName, receipt.timestamp,
            receipt.totalAmountCents, receipt.totalPreTaxCents, receipt.totalTaxCents,
        ))
        dao.insertItems(receipt.items.mapIndexed { position, item ->
            ReceiptItemEntity(
                item.id, receipt.id, position, item.name,
                item.breakdown.amountCents, item.breakdown.taxRateBps,
                item.breakdown.preTaxCents, item.breakdown.taxCents, item.categoryReason,
            )
        })
    }
}

private fun ReceiptWithItems.toReceipt(): Receipt {
    val result = ReceiptValidation.validate(Receipt(
        id = receipt.id,
        storeName = receipt.storeName,
        timestamp = receipt.timestamp,
        items = items.sortedBy { it.position }.map {
            CalculatedItem(
                it.id, it.name,
                TaxBreakdown(it.amountCents, it.taxRateBps, it.preTaxCents, it.taxCents),
                it.categoryReason,
            )
        },
    ))
    check(result.totalAmountCents == receipt.totalAmountCents &&
        result.totalTaxCents == receipt.totalTaxCents &&
        result.totalPreTaxCents == receipt.totalPreTaxCents) { "账本汇总与商品明细不一致" }
    return result
}
