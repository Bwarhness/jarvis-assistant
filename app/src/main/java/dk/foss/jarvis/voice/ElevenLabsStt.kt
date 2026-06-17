package dk.foss.jarvis.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/** ElevenLabs Scribe speech-to-text (POST /v1/speech-to-text, multipart). */
class ElevenLabsStt(
    private val apiKey: String,
    private val modelId: String = "scribe_v1",
    private val languageCode: String? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(file: File): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model_id", modelId)
                .apply { if (!languageCode.isNullOrEmpty()) addFormDataPart("language_code", languageCode) }
                .addFormDataPart("file", file.name, file.asRequestBody("audio/wav".toMediaType()))
                .build()
            val req = Request.Builder()
                .url("https://api.elevenlabs.io/v1/speech-to-text")
                .addHeader("xi-api-key", apiKey)
                .post(multipart)
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw RuntimeException("Scribe HTTP ${resp.code}: ${text.take(160)}")
                json.decodeFromString(ScribeResponse.serializer(), text).text.trim()
            }
        }
    }

    @Serializable
    private data class ScribeResponse(val text: String = "")
}
