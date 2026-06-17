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
import dk.foss.jarvis.wake.WakeWordService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.sse.EventSource

enum class ConvState { Idle, Listening, Thinking, Speaking }

class ConversationViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val main = Handler(Looper.getMainLooper())
    private val speech = SpeechInput(app)

    val state = mutableStateOf(ConvState.Idle)
    val transcript = mutableStateOf("")
    val reply = mutableStateOf("")
    val error = mutableStateOf<String?>(null)

    private var settings: JarvisSettings? = null
    private var tts: TtsEngine? = null
    private var androidFallback: AndroidTts? = null
    private val history = mutableListOf<ChatMessage>()
    private var sessionId: String? = null
    private var source: EventSource? = null
    private var continuous = true

    // --- streaming-TTS pipeline ---
    private val sentenceBuffer = StringBuilder()
    private val ttsQueue = ArrayDeque<String>()
    private var speaking = false
    private var streamDone = false
    private var turn = 0 // bumped each turn; stale async callbacks check this and bail
    private var retriedThisTurn = false

    init {
        speech.prewarm() // bind the recognizer early so the first listen isn't cold
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

    fun startListening() {
        // A conversation auto-continues: after Jarvis speaks it listens again.
        continuous = true
        retriedThisTurn = false
        // Free the mic from the always-on wake listener so STT can record.
        WakeWordService.pauseListening()
        viewModelScope.launch {
            ensureReady()
            if (settings?.isConfigured != true) {
                error.value = "Configure Hermes in Settings first."
                state.value = ConvState.Idle
                return@launch
            }
            beginTurn()
            state.value = ConvState.Listening
            startRecognition()
        }
    }

    /** Start a fresh turn: invalidate in-flight callbacks and clear pipeline state. */
    private fun beginTurn() {
        turn++
        source?.cancel(); source = null
        runCatching { tts?.stop(); androidFallback?.stop() }
        ttsQueue.clear()
        sentenceBuffer.setLength(0)
        speaking = false
        streamDone = false
        transcript.value = ""
        error.value = null
    }

    private fun startRecognition() {
        val myTurn = turn
        speech.start(languageTag = null, listener = object : SpeechInput.Listener {
            override fun onPartial(text: String) = main.post {
                if (turn == myTurn) transcript.value = text
            }.let {}

            override fun onFinal(text: String) = main.post {
                if (turn != myTurn) return@post
                transcript.value = text
                if (text.isBlank()) state.value = ConvState.Idle else think(text)
            }.let {}

            override fun onError(message: String, transient: Boolean) = main.post {
                if (turn != myTurn) return@post
                // Cold-start / mic-handoff hiccup right after a wake — retry once.
                if (transient && !retriedThisTurn) {
                    retriedThisTurn = true
                    main.postDelayed({ if (turn == myTurn) startRecognition() }, 450)
                    return@post
                }
                error.value = message
                state.value = ConvState.Idle
            }.let {}
        })
    }

    private fun think(userText: String) {
        val myTurn = turn
        state.value = ConvState.Thinking
        reply.value = ""
        sentenceBuffer.setLength(0)
        ttsQueue.clear()
        speaking = false
        streamDone = false
        history.add(ChatMessage("user", userText))

        val s = settings ?: return
        val client = HermesClient(s.baseUrl, s.apiKey)
        source = client.streamChat(history.toList(), s.model, sessionId, object : HermesClient.StreamCallbacks {
            override fun onDelta(textDelta: String) = main.post {
                if (turn == myTurn) onTextDelta(textDelta)
            }.let {}

            override fun onSessionId(id: String) { sessionId = id }

            override fun onComplete() = main.post {
                if (turn != myTurn) return@post
                // flush whatever's left as the final sentence
                val rest = sentenceBuffer.toString().trim()
                sentenceBuffer.setLength(0)
                if (rest.isNotEmpty()) enqueueSpeech(rest)
                if (reply.value.isNotBlank()) history.add(ChatMessage("assistant", reply.value))
                streamDone = true
                pump()
            }.let {}

            override fun onError(message: String) = main.post {
                if (turn != myTurn) return@post
                error.value = message
                state.value = ConvState.Idle
            }.let {}
        })
    }

    /** A token arrived: show it, and speak as soon as a full sentence is available. */
    private fun onTextDelta(delta: String) {
        reply.value += delta
        sentenceBuffer.append(delta)
        extractSentences()
    }

    private fun extractSentences() {
        while (true) {
            val s = sentenceBuffer
            var cut = -1
            for (i in s.indices) {
                val c = s[i]
                if (c == '\n') { cut = i; break }
                // sentence end only when we already see the following char is whitespace
                // (so "3.5" or a trailing "." mid-stream isn't split prematurely)
                if ((c == '.' || c == '!' || c == '?') && i + 1 < s.length && s[i + 1].isWhitespace()) {
                    cut = i; break
                }
            }
            if (cut < 0 && s.length > 180) { // soft cap so one long clause still starts early
                val sp = s.lastIndexOf(' ')
                if (sp > 40) cut = sp
            }
            if (cut < 0) break
            val sentence = s.substring(0, cut + 1).trim()
            s.delete(0, cut + 1)
            if (sentence.isNotEmpty()) enqueueSpeech(sentence)
        }
    }

    private fun enqueueSpeech(text: String) {
        ttsQueue.addLast(text)
        pump()
    }

    /** Speak queued sentences one after another (the next synthesizes after the prior plays). */
    private fun pump() {
        if (speaking) return
        val next = ttsQueue.removeFirstOrNull()
        if (next == null) {
            if (streamDone) finishTurn()
            return
        }
        speaking = true
        if (state.value != ConvState.Speaking) state.value = ConvState.Speaking
        val engine = tts ?: ensureFallback()
        if (engine == null) { speaking = false; return }
        val myTurn = turn
        engine.speak(
            text = next,
            onDone = { main.post { if (turn == myTurn) { speaking = false; pump() } } },
            onError = {
                // premium engine failed on this sentence — say it with on-device TTS, then continue
                val fb = ensureFallback()
                if (fb != null && fb !== engine) {
                    fb.speak(
                        text = next,
                        onDone = { main.post { if (turn == myTurn) { speaking = false; pump() } } },
                        onError = { main.post { if (turn == myTurn) { speaking = false; pump() } } },
                    )
                } else {
                    main.post { if (turn == myTurn) { speaking = false; pump() } }
                }
            },
        )
    }

    private fun finishTurn() {
        if (continuous) startListening() else state.value = ConvState.Idle
    }

    private fun ensureFallback(): TtsEngine? {
        (tts as? AndroidTts)?.let { return it }
        if (androidFallback == null) androidFallback = AndroidTts(getApplication(), languageTag = null)
        return androidFallback
    }

    fun onMicTap() {
        when (state.value) {
            ConvState.Listening -> { turn++; speech.stop(); state.value = ConvState.Idle }
            else -> startListening()
        }
    }

    fun setContinuous(value: Boolean) { continuous = value }

    fun stopAll() {
        continuous = false
        turn++
        speech.release()
        runCatching { tts?.stop(); androidFallback?.stop() }
        source?.cancel(); source = null
        ttsQueue.clear()
        sentenceBuffer.setLength(0)
        speaking = false
        streamDone = false
        state.value = ConvState.Idle
        // Hand the mic back to the always-on wake listener.
        WakeWordService.resumeListening()
    }

    override fun onCleared() {
        speech.release()
        source?.cancel()
        tts?.shutdown()
        androidFallback?.shutdown()
        WakeWordService.resumeListening()
        super.onCleared()
    }
}
