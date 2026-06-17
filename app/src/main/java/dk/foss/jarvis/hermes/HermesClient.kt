package dk.foss.jarvis.hermes

import dk.foss.jarvis.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * Talks to a Hermes `api_server`. This is the ONLY coupling to Hermes:
 * OpenAI-compatible `/v1/chat/completions` (streamed via SSE) plus `/v1/models`
 * for a connection test. Bearer auth; session continuity via X-Hermes-Session-Id.
 */
class HermesClient(
    private val baseUrl: String,
    private val apiKey: String,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    interface StreamCallbacks {
        fun onDelta(textDelta: String)
        fun onSessionId(id: String) {}
        fun onComplete() {}
        fun onError(message: String) {}
    }

    fun streamChat(
        messages: List<ChatMessage>,
        model: String,
        sessionId: String?,
        cb: StreamCallbacks,
    ): EventSource {
        val body = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(model = model, messages = messages, stream = true),
        )
        val builder = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON_MEDIA))
        if (!sessionId.isNullOrEmpty()) builder.addHeader("X-Hermes-Session-Id", sessionId)

        // The stream signals end twice (the "[DONE]" event AND onClosed) — make sure
        // the terminal callback fires exactly once.
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                response.header("X-Hermes-Session-Id")?.let { cb.onSessionId(it) }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data.isBlank() || data == "[DONE]") {
                    if (data == "[DONE]" && finished.compareAndSet(false, true)) cb.onComplete()
                    return
                }
                try {
                    val chunk = json.decodeFromString(StreamChunk.serializer(), data)
                    val delta = chunk.choices.firstOrNull()?.delta?.content
                    if (!delta.isNullOrEmpty()) cb.onDelta(delta)
                } catch (_: Exception) {
                    // keep-alive comment or non-JSON line — ignore
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (finished.compareAndSet(false, true)) cb.onComplete()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (!finished.compareAndSet(false, true)) return
                val msg = when {
                    response != null && !response.isSuccessful -> {
                        val detail = runCatching { response.body?.string() }.getOrNull()?.take(300)
                        "HTTP ${response.code}${if (!detail.isNullOrBlank()) ": $detail" else ""}"
                    }
                    t != null -> t.message ?: "Connection failed"
                    else -> "Connection failed"
                }
                cb.onError(msg)
            }
        }
        return EventSources.createFactory(Http.streaming).newEventSource(builder.build(), listener)
    }

    /** GET /v1/models — returns model ids on success, or a failure with the reason. */
    suspend fun testConnection(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/v1/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            Http.base.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw RuntimeException("HTTP ${resp.code}: ${text.take(200).ifBlank { resp.message }}")
                }
                json.decodeFromString(ModelsResponse.serializer(), text).data.map { it.id }
            }
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
