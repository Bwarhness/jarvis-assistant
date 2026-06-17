package dk.foss.jarvis.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.hermes.ChatMessage
import dk.foss.jarvis.hermes.HermesClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.sse.EventSource

data class UiMessage(val role: String, val text: String, val isError: Boolean = false)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val main = Handler(Looper.getMainLooper())

    val messages = mutableStateListOf<UiMessage>()
    val isStreaming = mutableStateOf(false)
    val notConfigured = mutableStateOf(false)

    private var sessionId: String? = null
    private var currentSource: EventSource? = null

    fun newConversation() {
        cancel()
        messages.clear()
        sessionId = null
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
            if (!s.isConfigured) {
                notConfigured.value = true
                return@launch
            }

            messages.add(UiMessage("user", text))
            messages.add(UiMessage("assistant", ""))
            val assistantIndex = messages.lastIndex
            isStreaming.value = true

            // Full history (excluding the empty assistant placeholder and any error rows).
            val history = messages.dropLast(1)
                .filter { !it.isError }
                .map { ChatMessage(it.role, it.text) }

            val client = HermesClient(s.baseUrl, s.apiKey)
            currentSource = client.streamChat(history, s.model, sessionId, object : HermesClient.StreamCallbacks {
                override fun onDelta(textDelta: String) = main.post {
                    if (assistantIndex <= messages.lastIndex) {
                        val cur = messages[assistantIndex]
                        messages[assistantIndex] = cur.copy(text = cur.text + textDelta)
                    }
                }.let {}

                override fun onSessionId(id: String) { sessionId = id }

                override fun onComplete() = main.post {
                    isStreaming.value = false
                    currentSource = null
                }.let {}

                override fun onError(message: String) = main.post {
                    if (assistantIndex <= messages.lastIndex && messages[assistantIndex].text.isEmpty()) {
                        messages[assistantIndex] = UiMessage("assistant", "⚠️ $message", isError = true)
                    } else {
                        messages.add(UiMessage("assistant", "⚠️ $message", isError = true))
                    }
                    isStreaming.value = false
                    currentSource = null
                }.let {}
            })
        }
    }

    override fun onCleared() {
        cancel()
        super.onCleared()
    }
}
