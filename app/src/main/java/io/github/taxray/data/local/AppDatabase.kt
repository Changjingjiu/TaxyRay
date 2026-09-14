package io.github.taxray.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ReceiptEntity::class, ReceiptItemEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun receiptDao(): ReceiptDao

    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "taxray-ledger.db",
        )
            .addCallback(object : Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    // Overwrite deleted SQLite content where the storage layer permits it.
                    // This cannot promise physical erasure on flash or device backups.
                    // This PRAGMA returns a result row on Android SQLite, even when setting it.
                    db.query("PRAGMA secure_delete = ON").use { result ->
                        check(result.moveToFirst() && result.getInt(0) == 1) {
                            "无法启用数据库安全删除"
                        }
                    }
                }
            })
            .build()
    }
}
