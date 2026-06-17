package dk.foss.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Wraps Android's [SpeechRecognizer]. A single recognizer instance is reused
 * across turns — creating/destroying one per listen makes the recognition
 * service drop its binding (ERROR_SERVER_DISCONNECTED). All calls on the main thread.
 */
class SpeechInput(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var current: Listener? = null

    interface Listener {
        fun onReady() {}
        fun onPartial(text: String) {}
        fun onFinal(text: String) {}
        /** [transient] = a cold-start/mic-handoff hiccup worth retrying (not "you said nothing"). */
        fun onError(message: String, transient: Boolean) {}
        fun onEnd() {}
    }

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Bind the recognition service ahead of time so the first listen isn't cold. */
    fun prewarm() {
        if (recognizer == null) createRecognizer()
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { current?.onReady() }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { current?.onEnd() }

        override fun onError(error: Int) {
            // A disconnected binding can't be reused — drop it so the next start() rebuilds.
            if (error == ERROR_SERVER_DISCONNECTED) {
                runCatching { recognizer?.destroy() }
                recognizer = null
            }
            val transient = error == SpeechRecognizer.ERROR_AUDIO ||
                error == SpeechRecognizer.ERROR_CLIENT ||
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == ERROR_SERVER_DISCONNECTED
            current?.onError(errorText(error), transient)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            current?.onFinal(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotEmpty()) current?.onPartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun start(languageTag: String?, listener: Listener) {
        current = listener
        val sr = recognizer ?: createRecognizer() ?: run {
            listener.onError("Speech recognition unavailable", transient = false)
            return
        }
        runCatching { sr.cancel() }
        runCatching { sr.startListening(buildIntent(languageTag)) }
            .onFailure { listener.onError(it.message ?: "Could not start recognition", transient = true) }
    }

    /** Stop the current recognition but keep the recognizer for reuse. */
    fun stop() {
        current = null
        runCatching { recognizer?.cancel() }
    }

    /** Fully tear down (call when leaving conversation / on cleanup). */
    fun release() {
        current = null
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun createRecognizer(): SpeechRecognizer? {
        if (!isAvailable()) return null
        return SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(recognitionListener)
            recognizer = it
        }
    }

    private fun buildIntent(languageTag: String?): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (!languageTag.isNullOrEmpty()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio error"
        SpeechRecognizer.ERROR_CLIENT -> "Recognition client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy"
        SpeechRecognizer.ERROR_SERVER -> "Recognition server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard"
        10 -> "Too many requests — try again"
        11 -> "Speech service disconnected"
        12 -> "Voice language not available — enable a language for voice typing in system settings"
        13 -> "Voice language unavailable"
        14 -> "Can't verify voice-language support"
        else -> "Recognition error ($code)"
    }

    private companion object {
        const val ERROR_SERVER_DISCONNECTED = 11
    }
}
