package dk.foss.jarvis.location

import dk.foss.jarvis.hermes.ChatMessage
import java.util.Locale

/**
 * Pure formatter for the USER_CONTEXT system message (spec §5.1):
 *
 *   USER_CONTEXT location = <locality>, <country> [(lat, lon)] | accuracy = coarse|precise | fix_age = <s>s
 *
 * Rules:
 * - Coarse mode never sends raw coordinates; it requires a place name,
 *   otherwise returns null (send nothing).
 * - Precise mode includes coordinates (4 decimals); when geocoding failed it
 *   sends coordinates only ("unknown (lat, lon)").
 */
object LocationContextBuilder {

    fun build(
        placeName: String?,
        latitude: Double,
        longitude: Double,
        precise: Boolean,
        fixAgeSeconds: Long,
    ): ChatMessage? {
        val accuracy = if (precise) "precise" else "coarse"
        val location = when {
            placeName != null && precise ->
                "$placeName (${format(latitude)}, ${format(longitude)})"
            placeName != null -> placeName
            precise -> "unknown (${format(latitude)}, ${format(longitude)})"
            else -> return null // coarse + no place name → omit context entirely
        }
        return ChatMessage(
            role = "system",
            content = "USER_CONTEXT location = $location | accuracy = $accuracy | fix_age = ${fixAgeSeconds}s",
        )
    }

    private fun format(v: Double): String = String.format(Locale.US, "%.4f", v)
}
