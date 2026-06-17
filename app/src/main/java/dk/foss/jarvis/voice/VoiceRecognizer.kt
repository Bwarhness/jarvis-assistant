package dk.foss.jarvis.voice

/** Speech-to-text source. Implementations: on-device Android, or ElevenLabs Scribe. */
interface VoiceRecognizer {
    fun isAvailable(): Boolean
    fun prewarm() {}
    fun start(languageTag: String?, listener: Listener)
    fun stop()
    fun release()

    interface Listener {
        fun onReady() {}
        fun onPartial(text: String) {}
        fun onFinal(text: String) {}
        /** [transient] = a hiccup worth retrying (not "you said nothing"). */
        fun onError(message: String, transient: Boolean) {}
        fun onEnd() {}
    }
}
