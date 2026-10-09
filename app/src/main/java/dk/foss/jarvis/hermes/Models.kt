package dk.foss.jarvis.hermes

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ChatRequest(
    // Server-side default: `model` is deliberately OMITTED so the Hermes api_server
    // resolves whichever model the server itself is configured for (verified live:
    // omitting the field yields HTTP 200 with the server's active model).
    // Sending an empty string hangs — never do that.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val model: String? = null,
    val messages: List<ChatMessage>,
    // ALWAYS serialize stream explicitly: with encodeDefaults=false the field would be
    // omitted whenever it equals its default (true), and the api_server then answers
    // with plain json (content-type application/json) instead of SSE — okhttp's
    // EventSource rejects that with "Invalid content-type". @EncodeDefault(ALWAYS)
    // forces the field onto the wire in every request.
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val stream: Boolean = true,
)

// --- streaming response (OpenAI chat.completion.chunk) ---

@Serializable
data class StreamChunk(val choices: List<StreamChoice> = emptyList())

@Serializable
data class StreamChoice(
    val delta: Delta = Delta(),
    val finish_reason: String? = null,
)

@Serializable
data class Delta(val role: String? = null, val content: String? = null)

// --- /v1/models (connection test) ---

@Serializable
data class ModelsResponse(val data: List<ModelEntry> = emptyList())

@Serializable
data class ModelEntry(val id: String)
