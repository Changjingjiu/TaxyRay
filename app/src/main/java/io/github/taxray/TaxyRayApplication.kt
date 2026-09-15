package io.github.taxray

import android.app.Application
import io.github.taxray.data.ReceiptRepository
import io.github.taxray.data.local.AppDatabase
import io.github.taxray.data.security.SecurePreferences

class TaxyRayApplication : Application() {
    val database by lazy { AppDatabase.create(this) }
    val receipts by lazy { ReceiptRepository(database) }
    val securePreferences by lazy { SecurePreferences(this) }
}
