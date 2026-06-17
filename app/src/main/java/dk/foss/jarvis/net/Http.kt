package dk.foss.jarvis.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttp clients. One process-wide connection/thread pool, reused by all
 * network callers (Hermes chat, ElevenLabs TTS/STT) instead of building a fresh
 * client per request.
 */
object Http {
    /** General-purpose client with bounded timeouts. */
    val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** For long-lived SSE streams (no read timeout); shares base's pools. */
    val streaming: OkHttpClient = base.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
}
