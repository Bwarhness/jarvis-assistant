package dk.foss.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Thin wrapper over Android's on-device [SpeechRecognizer]. All methods must be
 * called on the main thread (SpeechRecognizer requirement).
 */
class SpeechInput(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    interface Listener {
        fun onReady() {}
        fun onPartial(text: String) {}
        fun onFinal(text: String) {}
        fun onError(message: String) {}
        fun onEnd() {}
    }

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(languageTag: String?, listener: Listener) {
        stop()
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.onReady()
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = listener.onEnd()
            override fun onError(error: Int) = listener.onError(errorText(error))
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                listener.onFinal(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotEmpty()) listener.onPartial(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // NOTE: we intentionally do NOT force EXTRA_PREFER_OFFLINE — forcing
            // offline yields ERROR_LANGUAGE_NOT_SUPPORTED (12) when the device has
            // no offline model for the language. Let the recognizer choose.
            if (!languageTag.isNullOrEmpty()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        }
        sr.startListening(intent)
    }

    fun stop() {
        recognizer?.let { r ->
            runCatching { r.stopListening() }
            runCatching { r.cancel() }
            runCatching { r.destroy() }
        }
        recognizer = null
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
}
