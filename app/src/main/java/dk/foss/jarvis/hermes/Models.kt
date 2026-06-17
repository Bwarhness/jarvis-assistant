package dk.foss.jarvis.hermes

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
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
