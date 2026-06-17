package dk.foss.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import dk.foss.jarvis.net.Http
import okhttp3.Request
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Plays ElevenLabs voice preview mp3s. Downloads to a temp file first, then
 * plays via MediaPlayer — avoids MediaPlayer's native HTTP stack hanging.
 * Only one preview at a time — calling [play] automatically stops any previous preview.
 */
class VoicePreviewPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var generation = 0L

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
        // Claim this generation as current; stop()/a later play() bumps it to
        // invalidate in-flight callbacks. Without this assignment every callback
        // sees generation != myGen and bails, leaving isLoading stuck forever.
        val myGen = nextGeneration()
        generation = myGen
        isLoading = true
        currentVoiceId = voiceId
        notifyChanged()

        Thread {
            try {
                val req = Request.Builder().url(url).build()
                Http.base.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        main.post {
                            if (generation != myGen) return@post
                            isLoading = false
                            isPlaying = false
                            currentVoiceId = null
                            notifyChanged()
                        }
                        return@Thread
                    }
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (generation != myGen) return@Thread
                    val file = File(appContext.cacheDir, "preview_${nextId()}.mp3")
                    file.writeBytes(bytes)
                    main.post {
                        if (generation != myGen) {
                            runCatching { file.delete() }
                            return@post
                        }
                        playFile(file, myGen)
                    }
                }
            } catch (_: Exception) {
                main.post {
                    if (generation != myGen) return@post
                    isLoading = false
                    isPlaying = false
                    currentVoiceId = null
                    notifyChanged()
                }
            }
        }.start()
    }

    private fun playFile(file: File, myGen: Long) {
        if (generation != myGen) { runCatching { file.delete() }; return }
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener {
                runCatching {
                    if (player === mp && generation == myGen) {
                        isLoading = false
                        isPlaying = true
                        notifyChanged()
                        it.start()
                    } else {
                        it.release()
                        runCatching { file.delete() }
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
                    file.delete()
                }
            }
            mp.setOnErrorListener { _, _, _ ->
                runCatching {
                    isLoading = false
                    isPlaying = false
                    if (player === mp) { player = null; currentVoiceId = null }
                    notifyChanged()
                    mp.release()
                    file.delete()
                }
                true
            }
            mp.prepareAsync()
        } catch (_: Exception) {
            runCatching { mp.release() }
            player = null
            isLoading = false
            isPlaying = false
            currentVoiceId = null
            notifyChanged()
            runCatching { file.delete() }
        }
    }

    fun stop() {
        generation = nextGeneration()
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

    private fun nextGeneration(): Long = genCounter.incrementAndGet()

    private fun nextId(): Long = idCounter.incrementAndGet()

    private companion object {
        val genCounter = AtomicLong(0)
        val idCounter = AtomicLong(0)
    }
}
