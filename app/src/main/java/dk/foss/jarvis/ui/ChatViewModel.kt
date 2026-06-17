package dk.foss.jarvis.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.foss.jarvis.data.ConversationRepository
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.hermes.HermesClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.sse.EventSource

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val repo = ConversationRepository.get(app)
    private val main = Handler(Looper.getMainLooper())

    /** Run on the main thread; returns Unit so it fits expression-body callbacks. */
    private fun onMain(block: () -> Unit) { main.post(block) }

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
            val history = repo.historyForRequest()
            val assistantIndex = repo.addMessage("assistant", "")
            isStreaming.value = true

            val client = HermesClient(s.baseUrl, s.apiKey)
            currentSource = client.streamChat(history, s.model, repo.sessionId, object : HermesClient.StreamCallbacks {
                override fun onDelta(textDelta: String) = onMain {
                    repo.appendToMessage(assistantIndex, textDelta)
                }

                override fun onSessionId(id: String) { repo.setSessionId(id) }

                override fun onComplete() = onMain {
                    isStreaming.value = false
                    currentSource = null
                    viewModelScope.launch { repo.persist() }
                }

                override fun onError(message: String) = onMain {
                    val cur = messages.getOrNull(assistantIndex)
                    if (cur != null && cur.text.isEmpty()) {
                        repo.replaceMessage(assistantIndex, "⚠️ $message", isError = true)
                    } else {
                        repo.addMessage("assistant", "⚠️ $message", isError = true)
                    }
                    isStreaming.value = false
                    currentSource = null
                    viewModelScope.launch { repo.persist() }
                }
            })
        }
    }

    override fun onCleared() {
        cancel()
        repo.persistAsync() // viewModelScope is already cancelled here
        super.onCleared()
    }
}
