package dk.foss.jarvis.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Records an utterance on-device (with silence detection) and transcribes it
 * via ElevenLabs Scribe. No live partials — the transcript arrives on onFinal.
 */
class ScribeRecognizer(
    context: Context,
    apiKey: String,
    private val languageCode: String? = null,
) : VoiceRecognizer {

    private val capture = AudioCapture(context)
    private val stt = ElevenLabsStt(apiKey, languageCode = languageCode)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listener: VoiceRecognizer.Listener? = null

    override fun isAvailable(): Boolean = true // network-based

    override fun start(languageTag: String?, listener: VoiceRecognizer.Listener) {
        this.listener = listener
        capture.cancel() // ensure no prior capture blocks this one (active-guard)
        listener.onReady()
        capture.start(
            onSpeechStart = {},
            onResult = { file ->
                listener.onEnd()
                if (file == null) {
                    listener.onError("No speech heard", transient = false)
                } else {
                    scope.launch {
                        val result = stt.transcribe(file)
                        runCatching { file.delete() }
                        result.fold(
                            onSuccess = { text ->
                                if (text.isBlank()) listener.onError("No speech heard", transient = false)
                                else listener.onFinal(text)
                            },
                            onFailure = { listener.onError(it.message ?: "Transcription failed", transient = true) },
                        )
                    }
                }
            },
            onError = { msg -> listener.onError(msg, transient = true) },
        )
    }

    override fun stop() {
        capture.cancel()
        listener = null
    }

    override fun release() {
        capture.cancel()
        runCatching { scope.cancel() }
        listener = null
    }
}
