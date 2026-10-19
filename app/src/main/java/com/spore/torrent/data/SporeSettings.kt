package com.spore.torrent.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "spore_settings")

data class SporeSettings(
    val downloadLimitKb: Int = 0,
    val uploadLimitKb: Int = 0,
    val listenPort: Int = 6881,
    val dhtEnabled: Boolean = true,
    val encryptionEnabled: Boolean = true,
    val wifiOnly: Boolean = false,
    val pauseOnScreenOff: Boolean = false,
    val downloadPath: String = "",
    val themeMode: String = "SYSTEM",
    val onboarded: Boolean = false
)

object SettingsRepository {
    private val KEY_DL_LIMIT = intPreferencesKey("download_limit_kb")
    private val KEY_UL_LIMIT = intPreferencesKey("upload_limit_kb")
    private val KEY_PORT = intPreferencesKey("listen_port")
    private val KEY_DHT = booleanPreferencesKey("dht_enabled")
    private val KEY_ENCRYPTION = booleanPreferencesKey("encryption_enabled")
    private val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only")
    private val KEY_PAUSE_OFF = booleanPreferencesKey("pause_on_screen_off")
    private val KEY_DOWNLOAD_PATH = stringPreferencesKey("download_path")
    private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    private val KEY_ONBOARDED = booleanPreferencesKey("onboarded")

    fun settingsFlow(context: Context): Flow<SporeSettings> =
        context.dataStore.data.map { prefs ->
            SporeSettings(
                downloadLimitKb = prefs[KEY_DL_LIMIT] ?: 0,
                uploadLimitKb = prefs[KEY_UL_LIMIT] ?: 0,
                listenPort = prefs[KEY_PORT] ?: 6881,
                dhtEnabled = prefs[KEY_DHT] ?: true,
                encryptionEnabled = prefs[KEY_ENCRYPTION] ?: true,
                wifiOnly = prefs[KEY_WIFI_ONLY] ?: false,
                pauseOnScreenOff = prefs[KEY_PAUSE_OFF] ?: false,
                downloadPath = prefs[KEY_DOWNLOAD_PATH] ?: "",
                themeMode = prefs[KEY_THEME_MODE] ?: "SYSTEM",
                onboarded = prefs[KEY_ONBOARDED] ?: false
            )
        }

    suspend fun update(context: Context, block: (SporeSettings) -> SporeSettings) {
        context.dataStore.edit { prefs ->
            val current = SporeSettings(
                downloadLimitKb = prefs[KEY_DL_LIMIT] ?: 0,
                uploadLimitKb = prefs[KEY_UL_LIMIT] ?: 0,
                listenPort = prefs[KEY_PORT] ?: 6881,
                dhtEnabled = prefs[KEY_DHT] ?: true,
                encryptionEnabled = prefs[KEY_ENCRYPTION] ?: true,
                wifiOnly = prefs[KEY_WIFI_ONLY] ?: false,
                pauseOnScreenOff = prefs[KEY_PAUSE_OFF] ?: false,
                downloadPath = prefs[KEY_DOWNLOAD_PATH] ?: "",
                themeMode = prefs[KEY_THEME_MODE] ?: "SYSTEM",
                onboarded = prefs[KEY_ONBOARDED] ?: false
            )
            val next = block(current)
            prefs[KEY_DL_LIMIT] = next.downloadLimitKb
            prefs[KEY_UL_LIMIT] = next.uploadLimitKb
            prefs[KEY_PORT] = next.listenPort
            prefs[KEY_DHT] = next.dhtEnabled
            prefs[KEY_ENCRYPTION] = next.encryptionEnabled
            prefs[KEY_WIFI_ONLY] = next.wifiOnly
            prefs[KEY_PAUSE_OFF] = next.pauseOnScreenOff
            prefs[KEY_DOWNLOAD_PATH] = next.downloadPath
            prefs[KEY_THEME_MODE] = next.themeMode
            prefs[KEY_ONBOARDED] = next.onboarded
        }
    }

    suspend fun updateDownloadLimit(context: Context, kb: Int) =
        update(context) { it.copy(downloadLimitKb = kb) }

    suspend fun updateUploadLimit(context: Context, kb: Int) =
        update(context) { it.copy(uploadLimitKb = kb) }

    suspend fun updatePort(context: Context, port: Int) =
        update(context) { it.copy(listenPort = port) }

    suspend fun updateDht(context: Context, enabled: Boolean) =
        update(context) { it.copy(dhtEnabled = enabled) }

    suspend fun updateEncryption(context: Context, enabled: Boolean) =
        update(context) { it.copy(encryptionEnabled = enabled) }

    suspend fun updateWifiOnly(context: Context, enabled: Boolean) =
        update(context) { it.copy(wifiOnly = enabled) }

    suspend fun updatePauseOnScreenOff(context: Context, enabled: Boolean) =
        update(context) { it.copy(pauseOnScreenOff = enabled) }

    suspend fun updateDownloadPath(context: Context, path: String) =
        update(context) { it.copy(downloadPath = path) }

    suspend fun updateThemeMode(context: Context, mode: String) =
        update(context) { it.copy(themeMode = mode) }

    suspend fun setOnboarded(context: Context) =
        update(context) { it.copy(onboarded = true) }
}
