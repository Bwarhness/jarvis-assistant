package dk.foss.jarvis.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "jarvis_settings")

/** User configuration: how to reach Hermes, and optional premium voice. */
data class JarvisSettings(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val elevenKey: String,
    val elevenVoiceId: String,
) {
    val isConfigured: Boolean get() = baseUrl.isNotEmpty() && apiKey.isNotEmpty()
    val useElevenLabs: Boolean get() = elevenKey.isNotEmpty() && elevenVoiceId.isNotEmpty()
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL = stringPreferencesKey("model")
        val ELEVEN_KEY = stringPreferencesKey("eleven_key")
        val ELEVEN_VOICE = stringPreferencesKey("eleven_voice")
    }

    val settings: Flow<JarvisSettings> = context.dataStore.data.map { p ->
        JarvisSettings(
            baseUrl = p[Keys.BASE_URL] ?: "",
            apiKey = p[Keys.API_KEY] ?: "",
            model = (p[Keys.MODEL] ?: "").ifEmpty { DEFAULT_MODEL },
            elevenKey = p[Keys.ELEVEN_KEY] ?: "",
            elevenVoiceId = (p[Keys.ELEVEN_VOICE] ?: "").ifEmpty { DEFAULT_ELEVEN_VOICE },
        )
    }

    suspend fun updateConnection(baseUrl: String, apiKey: String, model: String) {
        context.dataStore.edit { p ->
            p[Keys.BASE_URL] = baseUrl.trim().trimEnd('/')
            p[Keys.API_KEY] = apiKey.trim()
            p[Keys.MODEL] = model.trim().ifEmpty { DEFAULT_MODEL }
        }
    }

    suspend fun updateVoice(elevenKey: String, elevenVoiceId: String) {
        context.dataStore.edit { p ->
            p[Keys.ELEVEN_KEY] = elevenKey.trim()
            p[Keys.ELEVEN_VOICE] = elevenVoiceId.trim().ifEmpty { DEFAULT_ELEVEN_VOICE }
        }
    }

    companion object {
        const val DEFAULT_MODEL = "kimi-for-coding"
        const val DEFAULT_ELEVEN_VOICE = "JBFqnCBsd6RMkjVDRZzb"
    }
}
