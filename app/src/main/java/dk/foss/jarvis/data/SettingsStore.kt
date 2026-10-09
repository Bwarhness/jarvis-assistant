package dk.foss.jarvis.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dk.foss.jarvis.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "jarvis_settings")

/** User configuration: how to reach Hermes, and optional premium voice. */
data class JarvisSettings(
    val baseUrl: String,
    val apiKey: String,
    val elevenKey: String,
    val elevenVoiceId: String,
    val wakeEnabled: Boolean,
    val locationSharingEnabled: Boolean,
    val locationPrecise: Boolean,
    val locationSendStale: Boolean,
) {
    val isConfigured: Boolean get() = baseUrl.isNotEmpty() && apiKey.isNotEmpty()
    val useElevenLabs: Boolean get() = elevenKey.isNotEmpty() && elevenVoiceId.isNotEmpty()
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = stringPreferencesKey("api_key")
        val ELEVEN_KEY = stringPreferencesKey("eleven_key")
        val ELEVEN_VOICE = stringPreferencesKey("eleven_voice")
        val WAKE_ENABLED = booleanPreferencesKey("wake_enabled")
        val LOCATION_SHARING = booleanPreferencesKey("location_sharing_enabled")
        val LOCATION_PRECISE = booleanPreferencesKey("location_precise")
        val LOCATION_SEND_STALE = booleanPreferencesKey("location_send_stale")
    }

    val settings: Flow<JarvisSettings> = context.dataStore.data.map { p ->
        // Note: `model` was removed from settings (v0.3.0) — the server picks its own
        // model. A stale `model` key may remain in DataStore from old installs; ignored.
        JarvisSettings(
            // Empty stored values fall back to baked-in defaults (older builds wrote
            // empty strings on first run, which would block the v0.3.0 prefill).
            baseUrl = (p[Keys.BASE_URL] ?: "").takeIf { it.isNotEmpty() } ?: BuildConfig.DEFAULT_BASE_URL,
            apiKey = (p[Keys.API_KEY] ?: "").takeIf { it.isNotEmpty() } ?: BuildConfig.DEFAULT_API_KEY,
            elevenKey = (p[Keys.ELEVEN_KEY] ?: "").ifEmpty { BuildConfig.DEFAULT_ELEVEN_KEY },
            elevenVoiceId = (p[Keys.ELEVEN_VOICE] ?: "")
                .ifEmpty { BuildConfig.DEFAULT_ELEVEN_VOICE.ifEmpty { DEFAULT_ELEVEN_VOICE } },
            wakeEnabled = p[Keys.WAKE_ENABLED] ?: false,
            locationSharingEnabled = p[Keys.LOCATION_SHARING] ?: false,
            locationPrecise = p[Keys.LOCATION_PRECISE] ?: false,
            locationSendStale = p[Keys.LOCATION_SEND_STALE] ?: true,
        )
    }

    suspend fun updateLocationSharing(enabled: Boolean) {
        context.dataStore.edit { p -> p[Keys.LOCATION_SHARING] = enabled }
    }

    suspend fun updateLocationPrecise(precise: Boolean) {
        context.dataStore.edit { p -> p[Keys.LOCATION_PRECISE] = precise }
    }

    suspend fun updateLocationSendStale(sendStale: Boolean) {
        context.dataStore.edit { p -> p[Keys.LOCATION_SEND_STALE] = sendStale }
    }

    suspend fun updateWake(enabled: Boolean) {
        context.dataStore.edit { p -> p[Keys.WAKE_ENABLED] = enabled }
    }

    suspend fun updateConnection(baseUrl: String, apiKey: String) {
        context.dataStore.edit { p ->
            p[Keys.BASE_URL] = baseUrl.trim().trimEnd('/')
            p[Keys.API_KEY] = apiKey.trim()
        }
    }

    suspend fun updateVoice(elevenKey: String, elevenVoiceId: String) {
        context.dataStore.edit { p ->
            p[Keys.ELEVEN_KEY] = elevenKey.trim()
            p[Keys.ELEVEN_VOICE] = elevenVoiceId.trim().ifEmpty { DEFAULT_ELEVEN_VOICE }
        }
    }

    companion object {
        const val DEFAULT_ELEVEN_VOICE = "JBFqnCBsd6RMkjVDRZzb"
    }
}
