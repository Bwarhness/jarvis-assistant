package dk.foss.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Speech output. Implementations: [AndroidTts] (free, on-device) and [ElevenLabsTts]. */
interface TtsEngine {
    fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit)
    fun stop()
    fun shutdown()
}

/** Android's built-in TextToSpeech. Free, offline, available everywhere. */
class AndroidTts(context: Context, private val languageTag: String?) : TtsEngine {

    private var ready = false
    private var pending: Triple<String, () -> Unit, (String) -> Unit>? = null

    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready && !languageTag.isNullOrEmpty()) {
            runCatching { engine?.language = Locale.forLanguageTag(languageTag) }
        }
        pending?.let { (t, done, err) -> pending = null; speak(t, done, err) }
    }

    private val engine: TextToSpeech? get() = tts

    override fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit) {
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
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile private var player: MediaPlayer? = null
    @Volatile private var cancelled = false

    override fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit) {
        cancelled = false
        Thread {
            try {
                val body = """{"text":${jsonString(text)},"model_id":${jsonString(modelId)}}"""
                val req = Request.Builder()
                    .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId/stream?output_format=mp3_44100_128&optimize_streaming_latency=3")
                    .addHeader("xi-api-key", apiKey)
                    .addHeader("Accept", "audio/mpeg")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        main.post { onError("ElevenLabs HTTP ${resp.code}") }
                        return@Thread
                    }
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (cancelled) return@Thread
                    val file = File(appContext.cacheDir, "tts_${System.identityHashCode(bytes)}.mp3")
                    file.writeBytes(bytes)
                    main.post { playFile(file, onDone, onError) }
                }
            } catch (e: Exception) {
                main.post { onError(e.message ?: "ElevenLabs error") }
            }
        }.start()
    }

    private fun playFile(file: File, onDone: () -> Unit, onError: (String) -> Unit) {
        if (cancelled) { onDone(); return }
        runCatching {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener { runCatching { file.delete() }; onDone() }
                setOnErrorListener { _, what, _ -> onError("Playback error $what"); true }
                prepare()
                start()
            }
            player = mp
        }.onFailure { onError(it.message ?: "Playback failed") }
    }

    override fun stop() {
        cancelled = true
        runCatching { player?.stop(); player?.release() }
        player = null
    }

    override fun shutdown() = stop()

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.append("\"").toString()
    }
}
