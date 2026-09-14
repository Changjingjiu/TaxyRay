package io.github.taxray.data.security

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Dedicated preference file and dedicated Keystore alias; does not inspect user credentials. */
@RunWith(AndroidJUnit4::class)
class SecurePreferencesTest {
    private lateinit var context: Context
    private lateinit var secure: SecurePreferences

    @Before fun setUp() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "${base.packageName}.secure_instrumentation"
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("${name}_secure_instrumentation", mode)
        }
        secure = SecurePreferences(context)
        secure.clear()
    }

    @After fun tearDown() { secure.clear() }

    @Test fun encryptedRoundTripUsesFreshIvAndDoesNotStorePlaintext() {
        val settings = ApiSettings("https://synthetic.example/invoke", "synthetic-vision", "synthetic-secret-not-a-real-key", appendChatCompletions = false)
        secure.save(settings)
        val preferences = context.getSharedPreferences(SecurePreferences.FILE_NAME, Context.MODE_PRIVATE)
        val first = preferences.getString("settings", null)!!
        assertFalse(first.contains(settings.apiKey))
        assertFalse(first.contains(settings.baseUrl))
        assertFalse(first.contains(settings.modelName))
        assertEquals(settings, SecurePreferences(context).read())
        secure.save(settings)
        assertNotEquals(first, preferences.getString("settings", null))
    }

    @Test fun clearDeletesTheEncryptionKeyAndCiphertext() {
        secure.save(ApiSettings(modelName = "synthetic-vision", apiKey = "synthetic-test-key"))
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "${context.packageName}.taxray_api_settings_aes"
        assertTrue(keyStore.containsAlias(alias))
        secure.clear()
        assertFalse(keyStore.containsAlias(alias))
        assertTrue(context.getSharedPreferences(SecurePreferences.FILE_NAME, Context.MODE_PRIVATE).all.isEmpty())
        assertEquals(ApiSettings(), secure.read())
    }

    @Test fun corruptedCiphertextFailsClosedWithoutLeakingContent() {
        val preferences = context.getSharedPreferences(SecurePreferences.FILE_NAME, Context.MODE_PRIVATE)
        preferences.edit().putString("settings", "synthetic-sensitive-corrupted-ciphertext").commit()
        val exception = assertThrows(IllegalStateException::class.java) { secure.read() }
        assertFalse(exception.message.orEmpty().contains("synthetic-sensitive"))
        assertTrue(exception.message.orEmpty().contains("清除 API 设置"))
        assertEquals(null, exception.cause)
    }
}
