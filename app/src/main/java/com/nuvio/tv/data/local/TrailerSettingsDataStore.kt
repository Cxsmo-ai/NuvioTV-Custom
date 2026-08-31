package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrailerSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "trailer_settings"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val enabledKey = booleanPreferencesKey("trailer_enabled")
    private val delaySecondsKey = intPreferencesKey("trailer_delay_seconds")
    private val sourceKey = stringPreferencesKey("trailer_source")
    private val detailAudioEnabledKey = booleanPreferencesKey("detail_trailer_audio_enabled")

    val settings: Flow<TrailerSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            TrailerSettings(
                enabled = prefs[enabledKey] ?: false,
                delaySeconds = prefs[delaySecondsKey] ?: 7,
                source = TrailerSource.fromKey(prefs[sourceKey]),
                detailAudioEnabled = prefs[detailAudioEnabledKey] ?: true
            )
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    suspend fun setDelaySeconds(seconds: Int) {
        store().edit { it[delaySecondsKey] = seconds }
    }

    suspend fun setSource(source: TrailerSource) {
        store().edit { it[sourceKey] = source.name }
    }

    suspend fun setDetailAudioEnabled(enabled: Boolean) {
        store().edit { it[detailAudioEnabledKey] = enabled }
    }
}

data class TrailerSettings(
    val enabled: Boolean = false,
    val delaySeconds: Int = 7,
    val source: TrailerSource = TrailerSource.YOUTUBE,
    /** Whether detail-page trailers, including manually opened trailers, play audio. */
    val detailAudioEnabled: Boolean = true
)

/** Which source resolves hero/detail trailers. YOUTUBE is the default; IMDB is opt-in. */
enum class TrailerSource {
    YOUTUBE,
    IMDB;

    companion object {
        fun fromKey(raw: String?): TrailerSource =
            values().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: YOUTUBE
    }
}
