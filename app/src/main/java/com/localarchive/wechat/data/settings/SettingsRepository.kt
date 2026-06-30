package com.localarchive.wechat.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.archiveSettings: DataStore<Preferences> by preferencesDataStore(name = "archive_settings")

class SettingsRepository(private val context: Context) {
    private val lastOpenedUrlKey = stringPreferencesKey("last_opened_url")
    // Gate the one-time clean-slate that drops old dual-copy / readable.html data.
    private val rebuildV2DoneKey = booleanPreferencesKey("rebuild_v2_done")
    // Persisted SAF tree URI of the user-chosen archive folder (single copy lives here).
    private val archiveTreeUriKey = stringPreferencesKey("archive_tree_uri")

    val lastOpenedUrl: Flow<String?> = context.archiveSettings.data.map { prefs ->
        prefs[lastOpenedUrlKey]
    }

    val archiveTreeUri: Flow<String?> = context.archiveSettings.data.map { prefs ->
        prefs[archiveTreeUriKey]
    }

    suspend fun getArchiveTreeUri(): String? =
        context.archiveSettings.data.first()[archiveTreeUriKey]

    suspend fun setArchiveTreeUri(uri: String) {
        context.archiveSettings.edit { prefs -> prefs[archiveTreeUriKey] = uri }
    }

    suspend fun setLastOpenedUrl(url: String) {
        context.archiveSettings.edit { prefs ->
            prefs[lastOpenedUrlKey] = url
        }
    }

    suspend fun isRebuildV2Done(): Boolean =
        context.archiveSettings.data.first()[rebuildV2DoneKey] ?: false

    suspend fun setRebuildV2Done() {
        context.archiveSettings.edit { prefs ->
            prefs[rebuildV2DoneKey] = true
        }
    }
}
