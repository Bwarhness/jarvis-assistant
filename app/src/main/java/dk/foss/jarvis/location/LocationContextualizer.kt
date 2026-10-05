package dk.foss.jarvis.location

import android.content.Context
import dk.foss.jarvis.data.JarvisSettings
import dk.foss.jarvis.hermes.ChatMessage
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shared per-turn location-context pipeline used by both ChatViewModel and
 * ConversationViewModel (spec §6): settings gate → permission gate → fix →
 * place name → USER_CONTEXT system message.
 *
 * Hard rule (spec §8): never delays a turn beyond [TIMEOUT_MS]; on any failure
 * returns null and the turn goes out without context — identical to disabled.
 */
class LocationContextualizer(context: Context) {

    private val appContext = context.applicationContext
    private val provider = LocationProvider(appContext)
    private val resolver = PlaceNameResolver(appContext)

    /** What the agent was last told, for the 📍 transparency chip (spec §7). */
    @Volatile
    var lastSentLabel: String? = null
        private set

    /**
     * Build the USER_CONTEXT system message for this turn, or null when the
     * feature is off, permission is missing/revoked, no fix is available in
     * time, or (coarse mode) no place name could be resolved.
     */
    suspend fun buildContextMessage(s: JarvisSettings): ChatMessage? {
        if (!s.locationSharingEnabled) return null
        if (!provider.hasCoarsePermission() && !provider.hasFinePermission()) {
            lastSentLabel = null
            return null
        }
        val fix = withTimeoutOrNull(TIMEOUT_MS) { provider.current() } ?: return null

        val ageMs = System.currentTimeMillis() - fix.timeMs
        if (ageMs > LocationProvider.STALE_AFTER_MS && !s.locationSendStale) return null

        val placeName = resolver.placeName(fix.latitude, fix.longitude)
        val msg = LocationContextBuilder.build(
            placeName = placeName,
            latitude = fix.latitude,
            longitude = fix.longitude,
            precise = fix.precise,
            fixAgeSeconds = fix.ageSeconds(),
        )
        lastSentLabel = when {
            msg == null -> null
            placeName != null -> placeName
            else -> "unknown"
        }
        return msg
    }

    companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
