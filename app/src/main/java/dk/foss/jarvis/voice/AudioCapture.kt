package dk.foss.jarvis.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt

/**
 * Records mic audio to a 16 kHz mono WAV with energy-based voice-activity
 * detection: it waits for speech, then stops after a short trailing silence.
 * Callbacks are delivered on the main thread.
 */
class AudioCapture(private val context: Context) {

    @Volatile private var active = false
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission") // caller ensures RECORD_AUDIO is granted
    fun start(
        onSpeechStart: () -> Unit,
        onResult: (File?) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (active) return
        active = true
        thread = Thread {
            var record: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
                val bufSize = maxOf(minBuf, SAMPLE_RATE / 5 * 2) // ~200ms
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, CHANNEL, ENCODING, bufSize,
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    post { onError("Microphone unavailable") }
                    return@Thread
                }

                val pcm = ByteArrayOutputStream()
                val buf = ShortArray(bufSize / 2)
                record.startRecording()

                var speechStarted = false
                var speechFrames = 0
                var silenceMs = 0
                var elapsedMs = 0
                var notifiedStart = false

                while (active) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val frameMs = n * 1000 / SAMPLE_RATE
                    elapsedMs += frameMs

                    val rms = rms(buf, n)
                    val loud = rms > SPEECH_RMS

                    if (loud) {
                        speechFrames++
                        if (speechFrames >= 2) speechStarted = true
                        silenceMs = 0
                    } else if (speechStarted) {
                        silenceMs += frameMs
                    }

                    if (speechStarted) {
                        if (!notifiedStart) { notifiedStart = true; post { onSpeechStart() } }
                        // append bytes (little-endian PCM16)
                        val bytes = ByteArray(n * 2)
                        for (i in 0 until n) {
                            bytes[i * 2] = (buf[i].toInt() and 0xFF).toByte()
                            bytes[i * 2 + 1] = (buf[i].toInt() shr 8 and 0xFF).toByte()
                        }
                        pcm.write(bytes)
                    }

                    val ended = speechStarted && silenceMs >= END_SILENCE_MS
                    val tooLong = elapsedMs >= MAX_MS
                    val noSpeech = !speechStarted && elapsedMs >= NO_SPEECH_MS
                    if (ended || tooLong || noSpeech) break
                }

                record.stop()

                if (!speechStarted || pcm.size() == 0) {
                    post { onResult(null) }
                } else {
                    val file = writeWav(pcm.toByteArray())
                    post { onResult(file) }
                }
            } catch (e: Exception) {
                post { onError(e.message ?: "Recording failed") }
            } finally {
                active = false
                runCatching { record?.release() }
            }
        }.also { it.start() }
    }

    /** Stop recording; whatever was captured so far is returned via onResult. */
    fun stop() { active = false }

    /** Abort with no result. */
    fun cancel() {
        active = false
        thread = null
    }

    private fun rms(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
        return sqrt(sum / n)
    }

    private fun writeWav(pcm: ByteArray): File {
        val file = File(context.cacheDir, "stt_${pcm.size}.wav")
        RandomAccessFile(file, "rw").use { out ->
            val dataLen = pcm.size
            val totalLen = dataLen + 36
            val byteRate = SAMPLE_RATE * 2
            val header = ByteArray(44)
            fun putInt(off: Int, v: Int) {
                header[off] = (v and 0xFF).toByte()
                header[off + 1] = (v shr 8 and 0xFF).toByte()
                header[off + 2] = (v shr 16 and 0xFF).toByte()
                header[off + 3] = (v shr 24 and 0xFF).toByte()
            }
            fun putShort(off: Int, v: Int) {
                header[off] = (v and 0xFF).toByte()
                header[off + 1] = (v shr 8 and 0xFF).toByte()
            }
            "RIFF".toByteArray().copyInto(header, 0)
            putInt(4, totalLen)
            "WAVE".toByteArray().copyInto(header, 8)
            "fmt ".toByteArray().copyInto(header, 12)
            putInt(16, 16)          // PCM fmt chunk size
            putShort(20, 1)         // audio format = PCM
            putShort(22, 1)         // channels = mono
            putInt(24, SAMPLE_RATE)
            putInt(28, byteRate)
            putShort(32, 2)         // block align
            putShort(34, 16)        // bits per sample
            "data".toByteArray().copyInto(header, 36)
            putInt(40, dataLen)
            out.write(header)
            out.write(pcm)
        }
        return file
    }

    private fun post(block: () -> Unit) = main.post(block)

    private companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val SPEECH_RMS = 700.0      // RMS above this = speech
        const val END_SILENCE_MS = 1000   // trailing silence that ends a turn
        const val NO_SPEECH_MS = 7000     // give up if nobody speaks
        const val MAX_MS = 20000          // hard cap on a single utterance
    }
}
