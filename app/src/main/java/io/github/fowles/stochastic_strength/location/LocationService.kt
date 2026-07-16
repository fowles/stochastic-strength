package io.github.fowles.stochastic_strength.location

import android.content.Context
import android.util.Log
import com.google.android.gms.location.LocationServices
import io.github.fowles.stochastic_strength.data.AppDatabase
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.*

class LocationService(context: Context) {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    @Suppress("MissingPermission")
    suspend fun getCurrentCoords(): Pair<Double, Double>? = suspendCancellableCoroutine { cont ->
        fusedLocationClient.lastLocation
            .addOnSuccessListener { loc -> cont.resume(loc?.let { it.latitude to it.longitude }) }
            .addOnFailureListener { cont.resume(null) }
    }

    suspend fun resolveLocation(db: AppDatabase): LocationResult {
        // The play-services lastLocation Task can hang indefinitely (never calling either listener)
        // on some emulators / devices with no cached fix. Never let location block workout start.
        val coords = withTimeoutOrNull(LOCATION_TIMEOUT_MS) { getCurrentCoords() }
        if (coords == null) {
            Log.w(TAG, "Location unavailable (null or timed out after ${LOCATION_TIMEOUT_MS}ms)")
            return LocationResult.Unavailable
        }
        val (lat, lon) = coords
        val match = db.knownLocationDao().getAll()
            .firstOrNull { haversineMeters(lat, lon, it.latitude, it.longitude) <= 100.0 }
        return if (match != null) LocationResult.Known(match.id) else LocationResult.Unknown(lat, lon)
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = (lat2 - lat1).toRadians()
        val dLon = (lon2 - lon1).toRadians()
        val a = sin(dLat / 2).pow(2) +
                cos(lat1.toRadians()) * cos(lat2.toRadians()) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun Double.toRadians() = this * PI / 180

    companion object {
        private const val TAG = "LocationService"
        private const val LOCATION_TIMEOUT_MS = 5_000L
    }
}
