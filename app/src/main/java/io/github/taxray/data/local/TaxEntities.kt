package io.github.taxray.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/** All amounts are integer CNY cents; rates are basis points (13% = 1300). */
@Entity(tableName = "receipts", indices = [Index("timestamp")])
data class ReceiptEntity(
    @PrimaryKey val id: String,
    val storeName: String,
    val timestamp: Long,
    val totalAmountCents: Long,
    val totalPreTaxCents: Long,
    val totalTaxCents: Long,
)

@Entity(
    tableName = "receipt_items",
    primaryKeys = ["receiptId", "id"],
    foreignKeys = [ForeignKey(
        entity = ReceiptEntity::class,
        parentColumns = ["id"],
        childColumns = ["receiptId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["receiptId", "position"], unique = true)],
)
data class ReceiptItemEntity(
    val id: String,
    val receiptId: String,
    val position: Int,
    val name: String,
    val amountCents: Long,
    val taxRateBps: Int,
    val preTaxCents: Long,
    val taxCents: Long,
    val categoryReason: String,
)

data class ReceiptWithItems(
    @Embedded val receipt: ReceiptEntity,
    @Relation(parentColumn = "id", entityColumn = "receiptId")
    val items: List<ReceiptItemEntity>,
)

data class LedgerTotals(
    val totalAmountCents: Long,
    val totalPreTaxCents: Long,
    val totalTaxCents: Long,
    val receiptCount: Long,
)

/** One local-calendar day, with SQL grouping performed in the device timezone. */
data class DailyTotals(
    val day: String,
    val totalAmountCents: Long,
    val totalTaxCents: Long,
)
