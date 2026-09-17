package chat.mural.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ProviderType(val displayName: String) {
    OpenAI("OpenAI"),
    XaiGrok("xAI Grok"),
    Custom("Custom"),
}

data class ProviderConfig(
    val type: ProviderType,
    val apiKey: String,
    val baseURL: String?,
    val model: String?,
) {
    val resolvedBaseURL: String
        get() = when {
            !baseURL.isNullOrBlank() -> baseURL
            else -> when (type) {
                ProviderType.OpenAI -> "https://api.openai.com/v1"
                ProviderType.XaiGrok -> "https://api.x.ai/v1"
                ProviderType.Custom -> ""
            }
        }

    val resolvedModel: String
        get() = when {
            !model.isNullOrBlank() -> model
            else -> when (type) {
                ProviderType.OpenAI -> "gpt-5.6-luna"
                ProviderType.XaiGrok -> "grok-voice-think-fast-1.0"
                ProviderType.Custom -> "gpt-3.5-turbo"
            }
        }
}

/** Stores the API key encrypted by a non-exportable Android Keystore key, plus provider configuration. */
class CredentialStore internal constructor(
    context: Context,
    preferencesName: String,
    private val keyAlias: String,
) {
    constructor(context: Context) : this(context, PREFERENCES, KEY_ALIAS)

    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    val hasKey: Boolean
        get() = read() != null

    /** Reads the stored provider type name, defaulting to openai. */
    fun readProviderType(): ProviderType {
        val raw = preferences.getString(PROVIDER_TYPE, null) ?: return ProviderType.OpenAI
        return try { ProviderType.valueOf(raw) } catch (_: Exception) { ProviderType.OpenAI }
    }

    /** Reads the stored custom base URL, if any. */
    fun readBaseURL(): String? = preferences.getString(BASE_URL, null)

    /** Reads the stored custom model name, if any. */
    fun readModel(): String? = preferences.getString(MODEL, null)

    /** Reads the full provider configuration from storage. */
    fun readConfig(): ProviderConfig {
        return ProviderConfig(
            type = readProviderType(),
            apiKey = read() ?: "",
            baseURL = readBaseURL(),
            model = readModel(),
        )
    }

    @Synchronized
    fun save(key: String) {
        save(key, baseURL = null, providerType = ProviderType.OpenAI, model = null)
    }

    @Synchronized
    fun save(key: String, baseURL: String?, providerType: ProviderType, model: String?) {
        val value = key.trim()
        if (value.length < 4 || value.any(Char::isWhitespace)) {
            throw CredentialException.Invalid
        }

        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val saved = preferences.edit()
                .putString(CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(PROVIDER_TYPE, providerType.name)
                .putString(BASE_URL, baseURL)
                .putString(MODEL, model)
                .commit()
            if (!saved) throw CredentialException.Save
        } catch (error: CredentialException) {
            throw error
        } catch (_: Exception) {
            throw CredentialException.Save
        }
    }

    /** Saves a complete provider configuration. */
    fun saveConfig(config: ProviderConfig) {
        save(config.apiKey, baseURL = config.baseURL, providerType = config.type, model = config.model)
    }

    @Synchronized
    fun read(): String? {
        val encodedCiphertext = preferences.getString(CIPHERTEXT, null) ?: return null
        val encodedIv = preferences.getString(IV, null) ?: return clearUnreadableCredential()
        return try {
            val key = keyStore().getKey(keyAlias, null) as? SecretKey ?: return clearUnreadableCredential()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(encodedIv, Base64.NO_WRAP)),
            )
            cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
                .takeIf { it.length >= 4 && it.none(Char::isWhitespace) }
                ?: clearUnreadableCredential()
        } catch (_: Exception) {
            clearUnreadableCredential()
        }
    }

    @Synchronized
    fun delete() {
        if (!preferences.edit().clear().commit()) throw CredentialException.Remove
        try {
            val store = keyStore()
            if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
        } catch (_: Exception) { }
    }

    private fun encryptionKey(): SecretKey {
        val store = keyStore()
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun clearUnreadableCredential(): Nothing? {
        preferences.edit().clear().commit()
        try {
            val store = keyStore()
            if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
        } catch (_: Exception) {
            // A stale ciphertext is already gone; a later save can retry key replacement.
        }
        return null
    }

    sealed class CredentialException(message: String) : IllegalStateException(message) {
        data object Invalid : CredentialException("Enter a valid API key.")
        data object Save : CredentialException("The key couldn't be saved securely on this device.")
        data object Remove : CredentialException("The key couldn't be removed. Unlock this device and try again.")
    }

    companion object {
        private const val PREFERENCES = "mural_openai_credentials"
        private const val CIPHERTEXT = "ciphertext"
        private const val IV = "iv"
        private const val KEY_ALIAS = "chat.mural.openai.aes"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PROVIDER_TYPE = "provider_type"
        private const val BASE_URL = "base_url"
        private const val MODEL = "model"
    }
}
