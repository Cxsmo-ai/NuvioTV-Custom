/*
 * NuvioTV-Fork - seek-thumbnail workstream (T-series)
 * Copyright (C) 2026 NuvioTV-Fork contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.nuvio.tv.core.player.thumbnail

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Standalone preferences for the seek-thumbnail feature. Deliberately self-contained
 * (own preferences file) so the feature toggles without touching the aggregated
 * PlayerSettings plumbing. Default OFF on every device tier (T-series binding rule).
 */
private val Context.seekThumbnailDataStore by preferencesDataStore(name = "seek_thumbnails")

object SeekThumbnailPreferences {
    private val enabledKey = booleanPreferencesKey("seek_thumbnails_enabled")

    fun enabledFlow(context: Context): Flow<Boolean> =
        context.applicationContext.seekThumbnailDataStore.data.map { prefs ->
            prefs[enabledKey] ?: false
        }

    suspend fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.seekThumbnailDataStore.edit { prefs ->
            prefs[enabledKey] = enabled
        }
    }
}
