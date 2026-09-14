package io.github.taxray.data

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taxray.core.DraftItem
import io.github.taxray.core.Receipt
import io.github.taxray.core.TaxCalculator
import io.github.taxray.data.local.AppDatabase
import io.github.taxray.data.local.ReceiptEntity
import io.github.taxray.data.local.ReceiptItemEntity
import java.time.LocalDate
import java.time.ZoneId
import java.io.File
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: ReceiptRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).build()
        repository = ReceiptRepository(db)
    }

    @After fun close() { db.close() }

    private fun draft(id: String, amount: String = "113") = DraftItem(id, "商品$id", amount, "13", "")

    private fun receipt(id: String, amount: String = "113") = Receipt(
        id, "商店$id", 1_700_000_000_000L, TaxCalculator.calculateItems(listOf(draft("item-$id", amount))),
    )

    @Test fun saveEditPreservesTimestampOrderAndExactTotals() = runBlocking {
        val originalDate = 1_600_000_000_000L
        val id = repository.save("  超市  ", listOf(draft("b"), draft("a", "226")), timestamp = originalDate)
        assertEquals(listOf("b", "a"), repository.all().single().items.map { it.id })
        assertEquals(3900L, db.receiptDao().totals().totalTaxCents)
        repository.save("新商户", listOf(draft("new", "113")), id)
        val edited = repository.all().single()
        assertEquals(originalDate, edited.timestamp)
        assertEquals("新商户", edited.storeName)
        assertEquals(1L, db.receiptDao().itemCount())
        assertEquals(1300L, db.receiptDao().totals().totalTaxCents)
        val correctedDate = originalDate - 86_400_000L
        repository.save("新商户", listOf(draft("new", "113")), id, timestamp = correctedDate)
        assertEquals(correctedDate, repository.all().single().timestamp)
    }

    @Test fun importingSameSnapshotIsIdempotentAndConflictsRollbackWholeBatch() = runBlocking {
        val existing = receipt("existing")
        assertEquals(1, repository.importReceipts(listOf(existing)))
        assertEquals(0, repository.importReceipts(listOf(existing)))
        val result = runCatching {
            repository.importReceipts(listOf(receipt("new"), existing.copy(storeName = "冲突商户")))
        }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(listOf(existing), repository.all())
        assertEquals(1L, db.receiptDao().itemCount())
    }

    @Test fun invalidEditLeavesPreviousReceiptUntouched() = runBlocking {
        val id = repository.save("原账单", listOf(draft("one")))
        val before = repository.all()
        assertTrue(runCatching { repository.save("错误账单", listOf(draft("bad", "0")), id) }.isFailure)
        assertEquals(before, repository.all())
    }

    @Test fun deleteCascadesAndReactiveTotalsUpdate() = runBlocking {
        val id = repository.save("商户", listOf(draft("one"), draft("two")))
        val observedExisting = CompletableDeferred<Unit>()
        val emptied = async {
            withTimeout(5_000) {
                repository.receipts.onEach { if (it.isNotEmpty()) observedExisting.complete(Unit) }
                    .first { it.isEmpty() }
            }
        }
        withTimeout(5_000) { observedExisting.await() }
        repository.delete(id)
        assertTrue(emptied.await().isEmpty())
        assertEquals(0L, db.receiptDao().itemCount())
        assertEquals(0L, db.receiptDao().totals().totalTaxCents)
    }

    @Test fun foreignKeyPreventsOrphanItems() = runBlocking {
        val result = runCatching {
            db.receiptDao().insertItems(listOf(ReceiptItemEntity("item", "missing", 0, "商品", 11300, 1300, 10000, 1300, "")))
        }
        assertTrue(result.isFailure)
        assertEquals(0L, db.receiptDao().itemCount())
    }

    @Test fun duplicatePositionCannotEnterDatabase() = runBlocking {
        db.receiptDao().insertReceipt(ReceiptEntity("bill", "商户", 1L, 22600, 20000, 2600))
        val first = ReceiptItemEntity("one", "bill", 0, "商品", 11300, 1300, 10000, 1300, "")
        assertTrue(runCatching { db.receiptDao().insertItems(listOf(first, first.copy(id = "two"))) }.isFailure)
        // Room wraps collection inserts atomically; the first row must roll back too.
        assertEquals(0L, db.receiptDao().itemCount())
    }

    @Test fun clearKeepsDatabaseUsable() = runBlocking {
        repository.save("商户", listOf(draft("one")))
        repository.clear()
        assertTrue(repository.all().isEmpty())
        assertEquals(0L, db.receiptDao().itemCount())
        repository.save("新账单", listOf(draft("two")))
        assertEquals(1, repository.all().size)
    }

    @Test fun ledgerSurvivesClosingAndReopeningAnActualFile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileName = "ledger-test-${UUID.randomUUID()}.db"
        var disk = Room.databaseBuilder(context, AppDatabase::class.java, fileName).build()
        try {
            val expected = receipt("persisted")
            ReceiptRepository(disk).importReceipts(listOf(expected))
            disk.close()
            disk = Room.databaseBuilder(context, AppDatabase::class.java, fileName).build()
            assertEquals(listOf(expected), ReceiptRepository(disk).all())
        } finally {
            disk.close()
            context.deleteDatabase(fileName)
        }
    }

    @Test fun dailyTrendHonorsBothRangeBoundaries() = runBlocking {
        val zone = ZoneId.systemDefault()
        val startDate = LocalDate.of(2025, 1, 1)
        val start = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = startDate.plusDays(30).atStartOfDay(zone).toInstant().toEpochMilli()
        repository.importReceipts(listOf(
            receipt("before").copy(timestamp = start - 1),
            receipt("first").copy(timestamp = start),
            receipt("last").copy(timestamp = end - 1),
            receipt("after").copy(timestamp = end),
        ))
        val days = db.receiptDao().observeLast30Days(start, end).first()
        assertEquals(listOf("2025-01-01", "2025-01-30"), days.map { it.day })
        assertEquals(listOf(11300L, 11300L), days.map { it.totalAmountCents })
        assertEquals(listOf(1300L, 1300L), days.map { it.totalTaxCents })
    }

    @Test fun clearTruncatesTheOnDiskWriteAheadLog() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileName = "ledger-clear-test-${UUID.randomUUID()}.db"
        val disk = Room.databaseBuilder(context, AppDatabase::class.java, fileName)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        try {
            val diskRepository = ReceiptRepository(disk)
            diskRepository.importReceipts(listOf(receipt("sensitive")))
            val wal = File(context.getDatabasePath(fileName).absolutePath + "-wal")
            assertTrue(wal.exists() && wal.length() > 0)
            diskRepository.clear()
            assertEquals(0L, wal.length())
            assertTrue(diskRepository.all().isEmpty())
        } finally {
            disk.close()
            context.deleteDatabase(fileName)
        }
    }

    @Test fun productionFactoryOpensReadsWritesReopensAndClearsInIsolatedDirectory() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "production-db-test-${UUID.randomUUID()}").apply { mkdirs() }
        // Room asks for applicationContext, so the wrapper must retain itself there too.
        // Override both database-open overloads; delegating either could touch the real ledger.
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(directory, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(
                name: String,
                mode: Int,
                factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?,
            ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath, factory, errorHandler)
            override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        }
        var disk = AppDatabase.create(isolated)
        try {
            val expected = receipt("production-factory")
            val repository = ReceiptRepository(disk)
            assertTrue(repository.all().isEmpty()) // Actually opens Room and runs the production callback.
            assertTrue(File(directory, "taxray-ledger.db").isFile)
            disk.openHelper.writableDatabase.query("PRAGMA secure_delete").use { result ->
                assertTrue(result.moveToFirst())
                assertEquals(1, result.getInt(0))
            }
            repository.importReceipts(listOf(expected))
            assertEquals(listOf(expected), repository.all())
            disk.close()
            disk = AppDatabase.create(isolated)
            assertEquals(listOf(expected), ReceiptRepository(disk).all())
            ReceiptRepository(disk).clear()
            assertTrue(ReceiptRepository(disk).all().isEmpty())
        } finally {
            disk.close()
            directory.deleteRecursively()
        }
    }
}
