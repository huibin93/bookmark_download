package com.localarchive.wechat.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.archiveSettings: DataStore<Preferences> by preferencesDataStore(name = "archive_settings")

class SettingsRepository(private val context: Context) {
    private val lastOpenedUrlKey = stringPreferencesKey("last_opened_url")

    val lastOpenedUrl: Flow<String?> = context.archiveSettings.data.map { prefs ->
        prefs[lastOpenedUrlKey]
    }

    suspend fun setLastOpenedUrl(url: String) {
        context.archiveSettings.edit { prefs ->
            prefs[lastOpenedUrlKey] = url
        }
    }
}
