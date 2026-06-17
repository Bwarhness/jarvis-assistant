package dk.foss.jarvis.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.foss.jarvis.data.JarvisSettings
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.hermes.ChatMessage
import dk.foss.jarvis.hermes.HermesClient
import dk.foss.jarvis.voice.AndroidTts
import dk.foss.jarvis.voice.ElevenLabsTts
import dk.foss.jarvis.voice.SpeechInput
import dk.foss.jarvis.voice.TtsEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.sse.EventSource

enum class ConvState { Idle, Listening, Thinking, Speaking }

class ConversationViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val main = Handler(Looper.getMainLooper())
    private val speech = SpeechInput(app)

    val state = mutableStateOf(ConvState.Idle)
    val transcript = mutableStateOf("")   // what the user said
    val reply = mutableStateOf("")        // what Jarvis is saying
    val error = mutableStateOf<String?>(null)

    private var settings: JarvisSettings? = null
    private var tts: TtsEngine? = null
    private val history = mutableListOf<ChatMessage>()
    private var sessionId: String? = null
    private var source: EventSource? = null
    private var continuous = true

    init {
        viewModelScope.launch { ensureReady() }
    }

    private suspend fun ensureReady() {
        if (settings == null) {
            val s = settingsStore.settings.first()
            settings = s
            if (tts == null) {
                tts = if (s.useElevenLabs) {
                    ElevenLabsTts(getApplication(), s.elevenKey, s.elevenVoiceId)
                } else {
                    AndroidTts(getApplication(), languageTag = null)
                }
            }
        }
    }

    /** Begin (or restart) listening for the user. Runs on the main dispatcher. */
    fun startListening() {
        viewModelScope.launch {
            ensureReady()
            if (settings?.isConfigured != true) {
                error.value = "Configure Hermes in Settings first."
                state.value = ConvState.Idle
                return@launch
            }
            stopSpeaking()
            source?.cancel()
            transcript.value = ""
            error.value = null
            state.value = ConvState.Listening
            startRecognition()
        }
    }

    private fun startRecognition() {
        speech.start(languageTag = null, listener = object : SpeechInput.Listener {
            override fun onPartial(text: String) = main.post { transcript.value = text }.let {}
            override fun onFinal(text: String) = main.post {
                transcript.value = text
                if (text.isBlank()) {
                    state.value = ConvState.Idle
                } else {
                    think(text)
                }
            }.let {}
            override fun onError(message: String) = main.post {
                error.value = message
                state.value = ConvState.Idle
            }.let {}
        })
    }

    private fun think(userText: String) {
        state.value = ConvState.Thinking
        reply.value = ""
        history.add(ChatMessage("user", userText))
        val s = settings ?: return
        val client = HermesClient(s.baseUrl, s.apiKey)
        source = client.streamChat(history.toList(), s.model, sessionId, object : HermesClient.StreamCallbacks {
            override fun onDelta(textDelta: String) = main.post { reply.value += textDelta }.let {}
            override fun onSessionId(id: String) { sessionId = id }
            override fun onComplete() = main.post {
                if (state.value == ConvState.Thinking || state.value == ConvState.Speaking) {
                    val finalReply = reply.value
                    if (finalReply.isNotBlank()) {
                        history.add(ChatMessage("assistant", finalReply))
                        speak(finalReply)
                    } else {
                        state.value = ConvState.Idle
                    }
                }
            }.let {}
            override fun onError(message: String) = main.post {
                error.value = message
                state.value = ConvState.Idle
            }.let {}
        })
    }

    private fun speak(text: String) {
        state.value = ConvState.Speaking
        val engine = tts
        if (engine == null) { state.value = ConvState.Idle; return }
        engine.speak(
            text = text,
            onDone = {
                main.post {
                    if (continuous) startListening() else state.value = ConvState.Idle
                }
            },
            onError = { msg ->
                main.post {
                    error.value = msg
                    state.value = ConvState.Idle
                }
            },
        )
    }

    /** Tap behaviour: interrupt whatever is happening and listen again. */
    fun onMicTap() {
        when (state.value) {
            ConvState.Listening -> { speech.stop(); state.value = ConvState.Idle }
            else -> startListening()
        }
    }

    fun setContinuous(value: Boolean) { continuous = value }

    private fun stopSpeaking() { runCatching { tts?.stop() } }

    fun stopAll() {
        continuous = false
        speech.stop()
        stopSpeaking()
        source?.cancel()
        source = null
        state.value = ConvState.Idle
    }

    override fun onCleared() {
        speech.stop()
        source?.cancel()
        tts?.shutdown()
        super.onCleared()
    }
}
