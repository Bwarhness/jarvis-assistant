package dk.foss.jarvis.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "jarvis_settings")

/** User configuration: how to reach Hermes. */
data class JarvisSettings(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
) {
    val isConfigured: Boolean get() = baseUrl.isNotEmpty() && apiKey.isNotEmpty()
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL = stringPreferencesKey("model")
    }

    val settings: Flow<JarvisSettings> = context.dataStore.data.map { p ->
        JarvisSettings(
            baseUrl = p[Keys.BASE_URL] ?: "",
            apiKey = p[Keys.API_KEY] ?: "",
            model = p[Keys.MODEL]?.ifEmpty { DEFAULT_MODEL } ?: DEFAULT_MODEL,
        )
    }

    suspend fun update(baseUrl: String, apiKey: String, model: String) {
        context.dataStore.edit { p ->
            p[Keys.BASE_URL] = baseUrl.trim().trimEnd('/')
            p[Keys.API_KEY] = apiKey.trim()
            p[Keys.MODEL] = model.trim().ifEmpty { DEFAULT_MODEL }
        }
    }

    companion object {
        const val DEFAULT_MODEL = "kimi-for-coding"
    }
}
