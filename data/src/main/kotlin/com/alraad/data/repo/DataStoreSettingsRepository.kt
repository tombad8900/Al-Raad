package com.alraad.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.alraad.core.common.contracts.AppSettings
import com.alraad.core.common.contracts.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

@Singleton
class DataStoreSettingsRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SettingsRepository {
        private val adBlock = booleanPreferencesKey("ad_block_enabled")
        private val javaScript = booleanPreferencesKey("javascript_enabled")
        private val mixedContent = booleanPreferencesKey("mixed_content_allowed")
        private val homepage = stringPreferencesKey("homepage_url")

        override fun observeSettings(): Flow<AppSettings> =
            context.settingsStore.data.map { prefs ->
                val defaults = AppSettings()
                AppSettings(
                    adBlockEnabled = prefs[adBlock] ?: defaults.adBlockEnabled,
                    javaScriptEnabled = prefs[javaScript] ?: defaults.javaScriptEnabled,
                    mixedContentAllowed = prefs[mixedContent] ?: defaults.mixedContentAllowed,
                    homepageUrl = prefs[homepage] ?: defaults.homepageUrl,
                )
            }

        override suspend fun update(settings: AppSettings) {
            context.settingsStore.edit { prefs ->
                prefs[adBlock] = settings.adBlockEnabled
                prefs[javaScript] = settings.javaScriptEnabled
                prefs[mixedContent] = settings.mixedContentAllowed
                prefs[homepage] = settings.homepageUrl
            }
        }
    }
