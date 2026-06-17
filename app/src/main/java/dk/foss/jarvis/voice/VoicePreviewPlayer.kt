package dk.foss.jarvis.voice

import android.media.AudioAttributes
import android.media.MediaPlayer

/**
 * Plays ElevenLabs voice preview mp3s. Only one preview at a time —
 * calling [play] automatically stops any previous preview.
 */
class VoicePreviewPlayer {

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    var currentVoiceId: String? = null
        private set

    @Volatile
    var isLoading: Boolean = false
        private set

    @Volatile
    var isPlaying: Boolean = false
        private set

    var onStateChanged: (() -> Unit)? = null

    fun play(voiceId: String, url: String) {
        stop()
        isLoading = true
        currentVoiceId = voiceId
        notifyChanged()

        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                runCatching {
                    if (player === mp) {
                        isLoading = false
                        isPlaying = true
                        notifyChanged()
                        it.start()
                    } else {
                        it.release()
                    }
                }
            }
            mp.setOnCompletionListener {
                runCatching {
                    isLoading = false
                    isPlaying = false
                    if (player === mp) { player = null; currentVoiceId = null }
                    notifyChanged()
                    mp.release()
                }
            }
            mp.setOnErrorListener { _, _, _ ->
                runCatching {
                    isLoading = false
                    isPlaying = false
                    if (player === mp) { player = null; currentVoiceId = null }
                    notifyChanged()
                    mp.release()
                }
                true
            }
            mp.prepareAsync()
        } catch (_: Exception) {
            runCatching { mp.release() }
            isLoading = false
            isPlaying = false
            player = null
            currentVoiceId = null
            notifyChanged()
        }
    }

    fun stop() {
        val mp = player
        player = null
        isLoading = false
        isPlaying = false
        currentVoiceId = null
        if (mp != null) {
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
        notifyChanged()
    }

    fun release() = stop()

    private fun notifyChanged() {
        onStateChanged?.invoke()
    }
}
