package dk.foss.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dk.foss.jarvis.net.Http
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Locale

/** Speech output. Implementations: [AndroidTts] (free, on-device) and [ElevenLabsTts]. */
interface TtsEngine {
    fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit)
    fun stop()
    fun shutdown()
}

/** Android's built-in TextToSpeech. Free, offline, available everywhere. */
class AndroidTts(context: Context, private val languageTag: String?) : TtsEngine {

    private var ready = false
    private var initFailed = false
    private var pending: Triple<String, () -> Unit, (String) -> Unit>? = null

    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        initFailed = !ready
        if (ready && !languageTag.isNullOrEmpty()) {
            runCatching { engine?.language = Locale.forLanguageTag(languageTag) }
        }
        val p = pending; pending = null
        if (p != null) {
            if (ready) speak(p.first, p.second, p.third)
            else p.third("TTS unavailable") // don't re-queue forever -> would stall the pump
        }
    }

    private val engine: TextToSpeech? get() = tts

    override fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit) {
        if (initFailed) { onError("TTS unavailable"); return }
        if (!ready) { pending = Triple(text, onDone, onError); return }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = onDone()
            @Deprecated("deprecated") override fun onError(utteranceId: String?) = onError("TTS error")
            override fun onError(utteranceId: String?, errorCode: Int) = onError("TTS error $errorCode")
        })
        val res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        if (res == TextToSpeech.ERROR) onError("TTS failed to start")
    }

    override fun stop() { runCatching { tts.stop() } }
    override fun shutdown() { runCatching { tts.stop(); tts.shutdown() } }
}

/**
 * ElevenLabs streaming TTS. Downloads the synthesized mp3 then plays it via
 * MediaPlayer. Used only when the user has set an ElevenLabs key.
 */
class ElevenLabsTts(
    context: Context,
    private val apiKey: String,
    private val voiceId: String,
    // Low-latency model for real-time assistant replies (multilingual, high quality).
    private val modelId: String = "eleven_turbo_v2_5",
) : TtsEngine {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val json = Json { encodeDefaults = true }

    @Serializable
    private data class TtsRequest(val text: String, val model_id: String)

    @Volatile private var player: MediaPlayer? = null
    @Volatile private var cancelled = false

    override fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit) {
        cancelled = false
        Thread {
            try {
                val body = json.encodeToString(TtsRequest.serializer(), TtsRequest(text, modelId))
                val req = Request.Builder()
                    .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId/stream?output_format=mp3_44100_128&optimize_streaming_latency=3")
                    .addHeader("xi-api-key", apiKey)
                    .addHeader("Accept", "audio/mpeg")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                Http.base.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        main.post { onError("ElevenLabs HTTP ${resp.code}") }
                        return@Thread
                    }
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (cancelled) return@Thread
                    val file = File(appContext.cacheDir, "tts_${nextId()}.mp3")
                    file.writeBytes(bytes)
                    main.post { playFile(file, onDone, onError) }
                }
            } catch (e: Exception) {
                main.post { onError(e.message ?: "ElevenLabs error") }
            }
        }.start()
    }

    private fun playFile(file: File, onDone: () -> Unit, onError: (String) -> Unit) {
        if (cancelled) { runCatching { file.delete() }; onDone(); return }
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
            mp.setOnCompletionListener { runCatching { file.delete() }; onDone() }
            mp.setOnErrorListener { _, what, _ -> onError("Playback error $what"); true }
            mp.setOnPreparedListener { if (!cancelled) it.start() else onDone() }
            mp.prepareAsync() // non-blocking; avoids blocking the main thread per sentence
        } catch (e: Exception) {
            runCatching { mp.release() } // don't leak the native player on setup failure
            player = null
            runCatching { file.delete() }
            onError(e.message ?: "Playback failed")
        }
    }

    override fun stop() {
        cancelled = true
        runCatching { player?.stop() }
        runCatching { player?.release() } // separate so a throwing stop() can't skip release
        player = null
    }

    override fun shutdown() = stop()

    private fun nextId(): Long = counter.incrementAndGet()

    private companion object {
        val counter = java.util.concurrent.atomic.AtomicLong(0)
    }
}
