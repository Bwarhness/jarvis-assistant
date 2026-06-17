package dk.foss.jarvis.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.util.UUID

/**
 * The single source of truth for the *active* conversation, shared by both the
 * text chat and voice modes so messages and the Hermes session id stay unified.
 * Persists to [ConversationStore] so conversations can be reopened and continued.
 */
class ConversationRepository private constructor(private val store: ConversationStore) {

    val messages: SnapshotStateList<UiMessage> = mutableStateListOf()

    var sessionId: String? = null
        private set

    private var activeId: String = UUID.randomUUID().toString()
    private var title: String = ""
    private var createdAt: Long = System.currentTimeMillis()
    private var dirty = false

    fun startNew() {
        activeId = UUID.randomUUID().toString()
        messages.clear()
        sessionId = null
        title = ""
        createdAt = System.currentTimeMillis()
        dirty = false
    }

    suspend fun open(id: String) {
        val c = store.load(id) ?: return
        activeId = c.id
        title = c.title
        createdAt = c.createdAt
        sessionId = c.sessionId
        messages.clear()
        messages.addAll(c.messages.map { UiMessage(it.role, it.text) })
        dirty = false
    }

    fun setSessionId(id: String) {
        if (sessionId != id) { sessionId = id; dirty = true }
    }

    /** Append a message and return its index. */
    fun addMessage(role: String, text: String, isError: Boolean = false): Int {
        if (title.isEmpty() && role == "user" && text.isNotBlank()) title = text.take(60)
        messages.add(UiMessage(role, text, isError))
        dirty = true
        return messages.lastIndex
    }

    fun appendToMessage(index: Int, delta: String) {
        if (index in messages.indices) {
            val cur = messages[index]
            messages[index] = cur.copy(text = cur.text + delta)
            dirty = true
        }
    }

    fun replaceMessage(index: Int, text: String, isError: Boolean = false) {
        if (index in messages.indices) {
            messages[index] = messages[index].copy(text = text, isError = isError)
            dirty = true
        }
    }

    /** History (non-error) as role/text pairs for building a Hermes request. */
    fun historyForRequest(): List<Pair<String, String>> =
        messages.filter { !it.isError }.map { it.role to it.text }

    suspend fun persist() {
        if (!dirty) return
        if (messages.none { !it.isError }) return
        store.save(
            Conversation(
                id = activeId,
                title = title.ifEmpty { "Conversation" },
                createdAt = createdAt,
                updatedAt = System.currentTimeMillis(),
                sessionId = sessionId,
                messages = messages.filter { !it.isError }.map { StoredMessage(it.role, it.text) },
            ),
        )
        dirty = false
    }

    suspend fun list(): List<ConversationMeta> = store.list()

    suspend fun delete(id: String) {
        store.delete(id)
        if (id == activeId) startNew()
    }

    companion object {
        @Volatile private var instance: ConversationRepository? = null
        fun get(context: Context): ConversationRepository =
            instance ?: synchronized(this) {
                instance ?: ConversationRepository(ConversationStore(context)).also { instance = it }
            }
    }
}
