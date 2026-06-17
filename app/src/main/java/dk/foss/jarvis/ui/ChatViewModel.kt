package dk.foss.jarvis.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.foss.jarvis.data.ConversationRepository
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.data.UiMessage
import dk.foss.jarvis.hermes.ChatMessage
import dk.foss.jarvis.hermes.HermesClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.sse.EventSource

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val repo = ConversationRepository.get(app)
    private val main = Handler(Looper.getMainLooper())

    val messages get() = repo.messages
    val isStreaming = mutableStateOf(false)
    val notConfigured = mutableStateOf(false)

    private var currentSource: EventSource? = null

    fun newConversation() {
        cancel()
        viewModelScope.launch {
            repo.persist()
            repo.startNew()
        }
    }

    fun cancel() {
        currentSource?.cancel()
        currentSource = null
        isStreaming.value = false
    }

    fun dismissNotConfigured() { notConfigured.value = false }

    fun send(userText: String) {
        val text = userText.trim()
        if (text.isEmpty() || isStreaming.value) return

        viewModelScope.launch {
            val s = settingsStore.settings.first()
            if (!s.isConfigured) { notConfigured.value = true; return@launch }

            repo.addMessage("user", text)
            val history = repo.historyForRequest().map { ChatMessage(it.first, it.second) }
            val assistantIndex = repo.addMessage("assistant", "")
            isStreaming.value = true

            val client = HermesClient(s.baseUrl, s.apiKey)
            currentSource = client.streamChat(history, s.model, repo.sessionId, object : HermesClient.StreamCallbacks {
                override fun onDelta(textDelta: String) = main.post {
                    repo.appendToMessage(assistantIndex, textDelta)
                }.let {}

                override fun onSessionId(id: String) { repo.setSessionId(id) }

                override fun onComplete() = main.post {
                    isStreaming.value = false
                    currentSource = null
                    viewModelScope.launch { repo.persist() }
                }.let {}

                override fun onError(message: String) = main.post {
                    val cur = messages.getOrNull(assistantIndex)
                    if (cur != null && cur.text.isEmpty()) {
                        repo.replaceMessage(assistantIndex, "⚠️ $message", isError = true)
                    } else {
                        repo.addMessage("assistant", "⚠️ $message", isError = true)
                    }
                    isStreaming.value = false
                    currentSource = null
                    viewModelScope.launch { repo.persist() }
                }.let {}
            })
        }
    }

    override fun onCleared() {
        cancel()
        viewModelScope.launch { repo.persist() }
        super.onCleared()
    }
}
