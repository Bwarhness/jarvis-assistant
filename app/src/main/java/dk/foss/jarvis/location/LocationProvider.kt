package dk.foss.jarvis.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Generic freshness cache with injectable clock — pure JVM so it's unit-testable.
 * Used by [LocationProvider] to avoid double lookups when a voice turn fires two
 * requests in quick succession (spec §3.2: <= 60 s in-memory cache).
 */
class FreshnessCache<T>(
    private val ttlMs: Long,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private var entry: Pair<T, Long>? = null

    /** Returns the cached value if it is younger than [ttlMs], else null. */
    fun get(): T? {
        val (value, at) = entry ?: return null
        return if (nowMs() - at < ttlMs) value else null
    }

    fun put(value: T) {
        entry = value to nowMs()
    }

    fun clear() {
        entry = null
    }
}

/** A resolved location fix with staleness metadata. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    /** true when ACCESS_FINE_LOCATION produced this fix. */
    val precise: Boolean,
    /** Fix timestamp (device elapsed-realtime based epoch ms best-effort). */
    val timeMs: Long,
) {
    fun ageSeconds(nowMs: Long = System.currentTimeMillis()): Long =
        ((nowMs - timeMs).coerceAtLeast(0L)) / 1000L
}

/**
 * Wraps FusedLocationProviderClient (spec §6): permission check, 60 s cache,
 * fresh-fix request with lastLocation fallback. Never blocks callers longer
 * than the underlying Task resolves — callers wrap in withTimeoutOrNull(5_000).
 */
class LocationProvider(
    private val context: Context,
    private val cacheTtlMs: Long = CACHE_TTL_MS,
) {
    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val cache = FreshnessCache<LocationFix>(cacheTtlMs)

    fun hasCoarsePermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    fun hasFinePermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Resolve the current fix, or null when permission is missing / no fix is
     * available. Served from the 60 s cache when fresh (spec §3.2).
     */
    suspend fun current(): LocationFix? {
        cache.get()?.let { return it }
        if (!hasCoarsePermission() && !hasFinePermission()) return null
        val precise = hasFinePermission()
        val fresh = freshFix(precise) ?: lastFix(precise)
        fresh?.let { cache.put(it) }
        return fresh
    }

    fun clearCache() = cache.clear()

    @Suppress("MissingPermission")
    private suspend fun freshFix(precise: Boolean): LocationFix? =
        suspendCancellableCoroutine { cont ->
            val priority = if (precise) {
                Priority.PRIORITY_HIGH_ACCURACY
            } else {
                Priority.PRIORITY_BALANCED_POWER_ACCURACY
            }
            fused.getCurrentLocation(priority, null)
                .addOnSuccessListener { loc -> cont.resume(loc?.toFix(precise)) }
                .addOnFailureListener { cont.resume(null) }
        }

    @Suppress("MissingPermission")
    private suspend fun lastFix(precise: Boolean): LocationFix? =
        suspendCancellableCoroutine { cont ->
            fused.lastLocation
                .addOnSuccessListener { loc -> cont.resume(loc?.toFix(precise)) }
                .addOnFailureListener { cont.resume(null) }
        }

    private fun Location.toFix(precise: Boolean) = LocationFix(
        latitude = latitude,
        longitude = longitude,
        precise = precise,
        timeMs = time,
    )

    companion object {
        const val CACHE_TTL_MS = 60_000L
        /** Fixes older than this are "stale" (spec §3.2). */
        const val STALE_AFTER_MS = 10 * 60_000L
    }
}
