package dk.foss.jarvis.assist

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * A no-op RecognitionService. A `voice-interaction-service` must declare a
 * recognitionService component; Jarvis does its own capture via SpeechRecognizer
 * elsewhere, so this only needs to exist to satisfy the platform.
 */
class JarvisRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}
