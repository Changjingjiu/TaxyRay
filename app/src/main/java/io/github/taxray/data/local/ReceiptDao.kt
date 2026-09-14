package io.github.taxray.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceiptDao {
    @Transaction
    @Query("SELECT * FROM receipts ORDER BY timestamp DESC, id ASC")
    fun observeReceipts(): Flow<List<ReceiptWithItems>>

    @Transaction
    @Query("SELECT * FROM receipts ORDER BY timestamp DESC, id ASC")
    suspend fun all(): List<ReceiptWithItems>

    @Transaction
    @Query("SELECT * FROM receipts WHERE id = :id")
    suspend fun find(id: String): ReceiptWithItems?

    // ABORT is deliberate: replacement is a checked delete + insert transaction,
    // never SQLite REPLACE, whose implicit delete could hide ID collisions.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReceipt(receipt: ReceiptEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItems(items: List<ReceiptItemEntity>)

    @Query("DELETE FROM receipts WHERE id = :id")
    suspend fun deleteReceipt(id: String)

    @Query("SELECT COUNT(*) FROM receipt_items")
    suspend fun itemCount(): Long

    @Query("""
        SELECT COALESCE(SUM(totalAmountCents), 0) AS totalAmountCents,
               COALESCE(SUM(totalPreTaxCents), 0) AS totalPreTaxCents,
               COALESCE(SUM(totalTaxCents), 0) AS totalTaxCents,
               COUNT(*) AS receiptCount
        FROM receipts
    """)
    fun observeTotals(): Flow<LedgerTotals>

    @Query("""
        SELECT COALESCE(SUM(totalAmountCents), 0) AS totalAmountCents,
               COALESCE(SUM(totalPreTaxCents), 0) AS totalPreTaxCents,
               COALESCE(SUM(totalTaxCents), 0) AS totalTaxCents,
               COUNT(*) AS receiptCount
        FROM receipts
    """)
    suspend fun totals(): LedgerTotals

    /** The caller supplies local start-of-day 29 days ago and the end boundary. */
    @Query("""
        SELECT strftime('%Y-%m-%d', timestamp / 1000, 'unixepoch', 'localtime') AS day,
               SUM(totalAmountCents) AS totalAmountCents,
               SUM(totalTaxCents) AS totalTaxCents
        FROM receipts WHERE timestamp >= :sinceInclusive AND timestamp < :untilExclusive
        GROUP BY day ORDER BY day ASC
    """)
    fun observeLast30Days(sinceInclusive: Long, untilExclusive: Long): Flow<List<DailyTotals>>
}
