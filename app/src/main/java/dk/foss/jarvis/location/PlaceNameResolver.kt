package dk.foss.jarvis.location

import android.content.Context
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Coarse reverse-geocoder: lat/lon → "Locality, Country" (spec §3.1).
 * Async Geocoder API on 33+, sync fallback (off main thread) on 29–32.
 * Null-safe when no geocoder backend is present (some AOSP/de-Googled builds).
 */
class PlaceNameResolver(private val context: Context) {

    @Suppress("DEPRECATION")
    suspend fun placeName(latitude: Double, longitude: Double): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context)
        val address = if (Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(latitude, longitude, 1) { results ->
                    cont.resume(results.firstOrNull())
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                runCatching { geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull() }
                    .getOrNull()
            }
        } ?: return null

        val locality = address.locality
            ?: address.subAdminArea
            ?: address.adminArea
            ?: return null
        val country = address.countryName ?: return locality
        return "$locality, $country"
    }
}
