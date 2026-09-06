package com.nuvio.tv.data.local

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nuvio.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device-local credentials for optional tracker integrations.
 *
 * OAuth tokens are already profile-scoped elsewhere. These are app-client
 * credentials, so they intentionally stay local to the device and are never
 * placed in Nuvio Sync, backup payloads, logs, or the UI state.
 */
@Singleton
class TrackingClientCredentialsStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun traktClientId(): String = read(KEY_TRAKT_ID).ifBlank { BuildConfig.TRAKT_CLIENT_ID.trim() }

    fun traktClientSecret(): String = read(KEY_TRAKT_SECRET).ifBlank { BuildConfig.TRAKT_CLIENT_SECRET.trim() }

    fun simklClientId(): String = read(KEY_SIMKL_ID).ifBlank { BuildConfig.SIMKL_CLIENT_ID.trim() }

    fun saveTrakt(clientId: String, clientSecret: String? = null) {
        write(KEY_TRAKT_ID, clientId)
        if (clientSecret != null) write(KEY_TRAKT_SECRET, clientSecret)
    }

    fun saveSimkl(clientId: String) = write(KEY_SIMKL_ID, clientId)

    private fun read(key: String): String {
        val stored = preferences.getString(key, null) ?: return ""
        return runCatching { decrypt(stored) }
            .onFailure { preferences.edit().remove(key).apply() }
            .getOrDefault("")
            .trim()
    }

    private fun write(key: String, value: String) {
        val normalized = value.trim()
        preferences.edit().apply {
            if (normalized.isBlank()) remove(key) else putString(key, encrypt(normalized))
        }.apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return "${cipher.iv.toBase64()}.${cipher.doFinal(value.toByteArray()).toBase64()}"
    }

    private fun decrypt(value: String): String {
        val separator = value.indexOf('.')
        require(separator > 0 && separator < value.lastIndex)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, value.substring(0, separator).fromBase64())
        )
        return cipher.doFinal(value.substring(separator + 1).fromBase64()).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        const val PREFERENCES_NAME = "nuvio_tracking_client_credentials"
        const val KEY_TRAKT_ID = "trakt_client_id"
        const val KEY_TRAKT_SECRET = "trakt_client_secret"
        const val KEY_SIMKL_ID = "simkl_client_id"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "com.nuvio.tv.tracking.client.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
