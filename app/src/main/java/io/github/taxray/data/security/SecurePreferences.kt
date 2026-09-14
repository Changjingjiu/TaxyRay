package io.github.taxray.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Encrypts the entire settings document using a non-exportable Android Keystore key.
 * No plaintext cache, legacy store, remote recovery, or backup is maintained.
 * Call these disk/Keystore operations on Dispatchers.IO.
 */
class SecurePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val associatedData = "${context.packageName}:$FILE_NAME:1".toByteArray(Charsets.UTF_8)
    private val keyAlias = "${context.packageName}.taxray_api_settings_aes"

    fun read(): ApiSettings = synchronized(lock) {
        val encrypted = preferences.getString(ENTRY_NAME, null) ?: return@synchronized ApiSettings()
        try {
            require(encrypted.length <= 48_000)
            val packed = Base64.decode(encrypted, Base64.NO_WRAP)
            require(packed.size > IV_BYTES + 16)
            val key = keyStore().getKey(keyAlias, null) as? SecretKey
                ?: error("Missing encryption key")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, packed.copyOfRange(0, IV_BYTES)))
            cipher.updateAAD(associatedData)
            val clear = cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES)
            try {
                val document = Json.parseToJsonElement(clear.toString(Charsets.UTF_8)).jsonObject
                ApiSettings(
                    baseUrl = document.getValue("base_url").jsonPrimitive.content,
                    modelName = document.getValue("model").jsonPrimitive.content,
                    apiKey = document.getValue("api_key").jsonPrimitive.content,
                    appendChatCompletions = document["append_chat_completions"]?.jsonPrimitive?.let {
                        require(!it.isString)
                        it.boolean
                    } ?: true,
                )
            } finally {
                clear.fill(0)
            }
        } catch (_: Exception) {
            throw IllegalStateException("无法解密 API 设置，请清除 API 设置后重新填写。账本数据不受影响。")
        }
    }

    fun save(settings: ApiSettings) = synchronized(lock) {
        require(settings.baseUrl.length <= 2_048 && settings.modelName.length <= 200 && settings.apiKey.length <= 8_192) {
            "API 设置内容过长。"
        }
        val plaintext = JsonObject(
            mapOf(
                "base_url" to JsonPrimitive(settings.baseUrl.trim()),
                "model" to JsonPrimitive(settings.modelName.trim()),
                "api_key" to JsonPrimitive(settings.apiKey.trim()),
                "append_chat_completions" to JsonPrimitive(settings.appendChatCompletions),
            ),
        ).toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey()) // Provider creates a fresh random IV.
            cipher.updateAAD(associatedData)
            check(cipher.iv.size == IV_BYTES)
            val encrypted = Base64.encodeToString(cipher.iv + cipher.doFinal(plaintext), Base64.NO_WRAP)
            check(preferences.edit().putString(ENTRY_NAME, encrypted).commit())
        } catch (_: Exception) {
            throw IllegalStateException("API 设置未能安全保存，请重试。")
        } finally {
            plaintext.fill(0)
        }
    }

    /** Delete the Keystore key first, so even a subsequent disk failure leaves ciphertext unusable. */
    fun clear() = synchronized(lock) {
        try {
            keyStore().deleteEntry(keyAlias)
            check(preferences.edit().clear().commit())
        } catch (_: Exception) {
            throw IllegalStateException("未能完整清除 API 设置，请重试。")
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        (keyStore().getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    companion object {
        const val FILE_NAME = "taxray_api_encrypted"
        private const val ENTRY_NAME = "settings"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private val lock = Any()
    }
}
