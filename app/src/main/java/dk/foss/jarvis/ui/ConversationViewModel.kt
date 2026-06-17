package dk.foss.jarvis.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.foss.jarvis.data.ConversationRepository
import dk.foss.jarvis.data.JarvisSettings
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.hermes.HermesClient
import dk.foss.jarvis.voice.AndroidTts
import dk.foss.jarvis.voice.ElevenLabsTts
import dk.foss.jarvis.voice.ScribeRecognizer
import dk.foss.jarvis.voice.SpeechInput
import dk.foss.jarvis.voice.TtsEngine
import dk.foss.jarvis.voice.VoiceRecognizer
import dk.foss.jarvis.wake.WakeWordService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.sse.EventSource

enum class ConvState { Idle, Listening, Thinking, Speaking }

class ConversationViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val repo = ConversationRepository.get(app)
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: VoiceRecognizer? = null

    /** Run on the main thread; returns Unit so it fits expression-body callbacks. */
    private fun onMain(block: () -> Unit) { main.post(block) }

    val state = mutableStateOf(ConvState.Idle)
    val transcript = mutableStateOf("")
    val reply = mutableStateOf("")
    val error = mutableStateOf<String?>(null)
    val working = mutableStateOf(false) // Hermes stream still open (response not complete)
    val stalled = mutableStateOf(false) // content paused mid-stream — likely running a tool

    private var settings: JarvisSettings? = null
    private var tts: TtsEngine? = null
    private var androidFallback: AndroidTts? = null
    private var source: EventSource? = null
    private var continuous = true

    // --- streaming-TTS pipeline ---
    private val sentenceBuffer = StringBuilder()
    private val ttsQueue = ArrayDeque<String>()
    private var speaking = false
    private var streamDone = false
    private var turn = 0 // bumped each turn; stale async callbacks check this and bail
    private var retriedThisTurn = false

    // Speak a complete sentence that's been sitting in the buffer once the stream
    // goes quiet (e.g. the agent paused to run a tool), not only when more text arrives.
    private val idleFlush = Runnable { flushPendingSentence() }

    // If content pauses while the stream is still open, the agent is likely running a tool.
    private val stallIndicator = Runnable { if (working.value) stalled.value = true }

    init {
        viewModelScope.launch { ensureReady() }
    }

    private suspend fun ensureReady() {
        val s = settings ?: settingsStore.settings.first().also { settings = it }
        if (tts == null) {
            tts = if (s.useElevenLabs) {
                ElevenLabsTts(getApplication(), s.elevenKey, s.elevenVoiceId)
            } else {
                AndroidTts(getApplication(), languageTag = null)
            }
        }
        if (recognizer == null) {
            // With an ElevenLabs key, use Scribe (far better accuracy); else on-device.
            recognizer = if (s.useElevenLabs) {
                ScribeRecognizer(getApplication(), s.elevenKey, languageCode = null)
            } else {
                SpeechInput(getApplication())
            }
            recognizer?.prewarm()
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
        main.removeCallbacks(idleFlush)
        main.removeCallbacks(stallIndicator)
        working.value = false
        stalled.value = false
        // Stop any in-flight recognition so the next start isn't blocked by
        // AudioCapture's "if (active) return" guard (which silently drops it).
        runCatching { recognizer?.stop() }
        source?.cancel(); source = null
        runCatching { tts?.stop(); androidFallback?.stop() }
        ttsQueue.clear()
        sentenceBuffer.setLength(0)
        speaking = false
        streamDone = false
        transcript.value = ""
        reply.value = ""
        error.value = null
    }

    /**
     * Reset the on-screen display when the voice screen is (re)opened, so it reflects
     * the current conversation rather than a stale previous exchange (the VM is retained
     * across screen visits while the shared conversation may have been replaced).
     */
    fun resetView() {
        turn++ // invalidate any in-flight callbacks from a prior screen visit
        runCatching { recognizer?.stop() }
        source?.cancel(); source = null
        transcript.value = ""
        reply.value = ""
        error.value = null
        working.value = false
        stalled.value = false
        state.value = ConvState.Idle
    }

    private fun startRecognition() {
        val myTurn = turn
        recognizer?.start(languageTag = null, listener = object : VoiceRecognizer.Listener {
            override fun onPartial(text: String) = onMain {
                if (turn == myTurn) transcript.value = text
            }

            override fun onEnd() = onMain {
                // Recording finished — show "Thinking" while Scribe transcribes.
                if (turn == myTurn && state.value == ConvState.Listening) state.value = ConvState.Thinking
            }

            override fun onFinal(text: String) = onMain {
                if (turn != myTurn) return@onMain
                transcript.value = text
                if (text.isBlank()) goIdle() else think(text)
            }

            override fun onError(message: String, transient: Boolean) = onMain {
                if (turn != myTurn) return@onMain
                // Cold-start / mic-handoff hiccup right after a wake — retry once.
                if (transient && !retriedThisTurn) {
                    retriedThisTurn = true
                    main.postDelayed({ if (turn == myTurn) startRecognition() }, 450)
                    return@onMain
                }
                error.value = message
                goIdle()
            }
        })
    }

    private fun think(userText: String) {
        val myTurn = turn
        // Pipeline state was already cleared by beginTurn(); only the stream-status
        // flags are new for this thinking phase.
        state.value = ConvState.Thinking
        working.value = true
        main.postDelayed(stallIndicator, STALL_MS)
        repo.addMessage("user", userText)
        val requestHistory = repo.historyForRequest()

        val s = settings ?: return
        val client = HermesClient(s.baseUrl, s.apiKey)
        source = client.streamChat(requestHistory, s.model, repo.sessionId, object : HermesClient.StreamCallbacks {
            override fun onDelta(textDelta: String) = onMain {
                if (turn == myTurn) onTextDelta(textDelta)
            }

            override fun onSessionId(id: String) { repo.setSessionId(id) }

            override fun onComplete() = onMain {
                if (turn != myTurn) return@onMain
                main.removeCallbacks(idleFlush)
                main.removeCallbacks(stallIndicator)
                working.value = false
                stalled.value = false
                // flush whatever's left as the final sentence
                val rest = sentenceBuffer.toString().trim()
                sentenceBuffer.setLength(0)
                if (rest.isNotEmpty()) enqueueSpeech(rest)
                if (reply.value.isNotBlank()) repo.addMessage("assistant", reply.value)
                viewModelScope.launch { repo.persist() }
                streamDone = true
                pump()
            }

            override fun onError(message: String) = onMain {
                if (turn != myTurn) return@onMain
                main.removeCallbacks(stallIndicator)
                working.value = false
                stalled.value = false
                error.value = message
                goIdle()
            }
        })
    }

    /** A token arrived: show it, and speak as soon as a full sentence is available. */
    private fun onTextDelta(delta: String) {
        reply.value += delta
        sentenceBuffer.append(delta)
        extractSentences()
        // Content is flowing → not stalled. Re-arm both timers.
        stalled.value = false
        main.removeCallbacks(idleFlush)
        main.postDelayed(idleFlush, IDLE_FLUSH_MS)
        main.removeCallbacks(stallIndicator)
        main.postDelayed(stallIndicator, STALL_MS)
    }

    private fun flushPendingSentence() {
        val s = sentenceBuffer.toString().trim()
        if (s.isNotEmpty() && (s.endsWith('.') || s.endsWith('!') || s.endsWith('?'))) {
            sentenceBuffer.setLength(0)
            enqueueSpeech(s)
        }
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
        if (continuous) startListening() else goIdle()
    }

    /**
     * Go idle WITHIN the conversation. We deliberately do NOT resume the wake word
     * here: the conversation screen owns the mic the whole time it is open, and
     * re-arming the wake word on idle created a feedback loop (idle -> wake fires ->
     * assist relaunch -> startListening -> no-speech -> idle -> ...). The wake word
     * is resumed only when the conversation is actually left (stopAll/onCleared).
     * To re-engage after a silence, tap the mic.
     */
    private fun goIdle() {
        state.value = ConvState.Idle
    }

    private fun ensureFallback(): TtsEngine? {
        (tts as? AndroidTts)?.let { return it }
        if (androidFallback == null) androidFallback = AndroidTts(getApplication(), languageTag = null)
        return androidFallback
    }

    fun onMicTap() {
        when (state.value) {
            ConvState.Listening -> { turn++; recognizer?.stop(); goIdle() }
            else -> startListening()
        }
    }

    fun stopAll() {
        continuous = false
        turn++
        main.removeCallbacks(idleFlush)
        main.removeCallbacks(stallIndicator)
        working.value = false
        stalled.value = false
        recognizer?.release()
        recognizer = null
        runCatching { tts?.stop(); androidFallback?.stop() }
        source?.cancel(); source = null
        ttsQueue.clear()
        sentenceBuffer.setLength(0)
        speaking = false
        streamDone = false
        state.value = ConvState.Idle
        // Save the conversation (covers turns that ended in an error/cancel, not just
        // successful replies) before handing the mic back to the wake listener.
        repo.persistAsync()
        WakeWordService.resumeListening()
    }

    override fun onCleared() {
        main.removeCallbacks(idleFlush)
        main.removeCallbacks(stallIndicator)
        recognizer?.release()
        source?.cancel()
        tts?.shutdown()
        androidFallback?.shutdown()
        repo.persistAsync()
        WakeWordService.resumeListening()
        super.onCleared()
    }

    private companion object {
        const val IDLE_FLUSH_MS = 350L
        const val STALL_MS = 800L
    }
}
