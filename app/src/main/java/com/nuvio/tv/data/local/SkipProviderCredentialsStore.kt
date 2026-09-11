package com.nuvio.tv.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

data class SkipProviderCredentials(
    val publicMetaDbApiKey: String = "",
    val introDbAppApiKey: String = "",
    val theIntroDbApiKey: String = ""
) {
    val configuredCount: Int
        get() = listOf(publicMetaDbApiKey, introDbAppApiKey, theIntroDbApiKey)
            .count { it.isNotBlank() }
}

/**
 * Profile-scoped, device-local provider credentials.
 *
 * Values are encrypted before entering the profile DataStore. They never enter
 * sync payloads, logs, source control, or player settings UI state.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class SkipProviderCredentialsStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "skip_provider_credentials"
        private const val KEY_ALIAS = "com.nuvio.tv.skip.provider.credentials.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }

    private val publicMetaDbKey = stringPreferencesKey("publicmetadb_api_key")
    private val introDbAppKey = stringPreferencesKey("introdb_app_api_key")
    private val theIntroDbKey = stringPreferencesKey("theintrodb_api_key")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    val credentials: Flow<SkipProviderCredentials> =
        profileManager.activeProfileId.flatMapLatest { profileId ->
            factory.get(profileId, FEATURE).data.map { prefs ->
                SkipProviderCredentials(
                    publicMetaDbApiKey = decryptOrEmpty(prefs[publicMetaDbKey]),
                    introDbAppApiKey = decryptOrEmpty(prefs[introDbAppKey]),
                    theIntroDbApiKey = decryptOrEmpty(prefs[theIntroDbKey])
                )
            }
        }

    suspend fun setPublicMetaDbApiKey(value: String) = set(publicMetaDbKey, value)
    suspend fun setIntroDbAppApiKey(value: String) = set(introDbAppKey, value)
    suspend fun setTheIntroDbApiKey(value: String) = set(theIntroDbKey, value)

    private suspend fun set(
        key: androidx.datastore.preferences.core.Preferences.Key<String>,
        value: String
    ) {
        val normalized = value.trim()
        store().edit { prefs ->
            if (normalized.isBlank()) prefs.remove(key)
            else prefs[key] = encrypt(normalized)
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return "${cipher.iv.toBase64()}.${cipher.doFinal(value.toByteArray(Charsets.UTF_8)).toBase64()}"
    }

    private fun decryptOrEmpty(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return runCatching {
            val separator = value.indexOf('.')
            require(separator > 0 && separator < value.lastIndex)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, value.substring(0, separator).fromBase64())
            )
            cipher.doFinal(value.substring(separator + 1).fromBase64()).toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
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
}
