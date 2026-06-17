package dk.foss.jarvis.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** Persists conversations as one JSON file each under filesDir/conversations/. */
class ConversationStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "conversations").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun save(conversation: Conversation) = withContext(Dispatchers.IO) {
        runCatching {
            // Write to a temp file then atomically rename, so concurrent/torn writes
            // can't corrupt the JSON.
            val target = File(dir, "${conversation.id}.json")
            val tmp = File(dir, "${conversation.id}.json.tmp")
            tmp.writeText(json.encodeToString(Conversation.serializer(), conversation))
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText()); tmp.delete()
            }
        }
        Unit
    }

    suspend fun load(id: String): Conversation? = withContext(Dispatchers.IO) {
        val f = File(dir, "$id.json")
        if (!f.exists()) return@withContext null
        runCatching { json.decodeFromString(Conversation.serializer(), f.readText()) }.getOrNull()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        runCatching { File(dir, "$id.json").delete() }
        Unit
    }

    /** All conversations as lightweight metadata, newest first. */
    suspend fun list(): List<ConversationMeta> = withContext(Dispatchers.IO) {
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { f ->
                runCatching {
                    val c = json.decodeFromString(Conversation.serializer(), f.readText())
                    ConversationMeta(c.id, c.title, c.updatedAt, c.messages.size)
                }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }
    }
}
