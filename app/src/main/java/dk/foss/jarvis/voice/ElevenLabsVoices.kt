package dk.foss.jarvis.voice

import dk.foss.jarvis.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request

/** ElevenLabs voice listing (GET /v2/voices). */
class ElevenLabsVoices(private val apiKey: String) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun list(): Result<List<ElevenVoice>> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://api.elevenlabs.io/v2/voices?page_size=100")
                .addHeader("xi-api-key", apiKey)
                .get()
                .build()
            Http.base.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw RuntimeException("Voices HTTP ${resp.code}: ${text.take(160)}")
                json.decodeFromString(VoicesResponse.serializer(), text).voices
            }
        }
    }
}

@Serializable
data class ElevenVoice(
    val voice_id: String = "",
    val name: String = "",
    val category: String = "",
    val preview_url: String? = null,
    val labels: Map<String, String> = emptyMap(),
)

@Serializable
private data class VoicesResponse(val voices: List<ElevenVoice> = emptyList())
