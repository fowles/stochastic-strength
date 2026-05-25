package io.github.fowles.stochastic_strength.location

sealed class LocationResult {
    /** GPS unavailable or permission denied — fall back to all equipment. */
    data object Unavailable : LocationResult()

    /** Got a GPS fix but no known location within 100 m — prompt user to set one up. */
    data class Unknown(val latitude: Double, val longitude: Double) : LocationResult()

    /** Matched a known location. */
    data class Known(val locationId: Long) : LocationResult()
}
