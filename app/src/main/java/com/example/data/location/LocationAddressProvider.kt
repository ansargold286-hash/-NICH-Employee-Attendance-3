package com.example.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

data class VerifiedLocationSnapshot(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val coordinatesLabel: String = "",
    val readableAddress: String = "",
    val accuracyMeters: Float? = null,
    val providerName: String = ""
) {
    val hasFix: Boolean
        get() = latitude != null && longitude != null && coordinatesLabel.isNotBlank()
}

interface LocationAddressProvider {
    suspend fun captureCurrentLocation(): VerifiedLocationSnapshot
}

object NoOpLocationAddressProvider : LocationAddressProvider {
    override suspend fun captureCurrentLocation(): VerifiedLocationSnapshot =
        VerifiedLocationSnapshot(
            coordinatesLabel = "Unavailable (Offline/Test)",
            readableAddress = "Location unavailable"
        )
}

class AndroidLocationAddressProvider(
    context: Context
) : LocationAddressProvider {
    private val appContext = context.applicationContext

    override suspend fun captureCurrentLocation(): VerifiedLocationSnapshot = withContext(Dispatchers.IO) {
        val hasFine = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            return@withContext VerifiedLocationSnapshot(
                coordinatesLabel = "Permission Missing",
                readableAddress = "GPS Location permission not granted"
            )
        }

        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext VerifiedLocationSnapshot(
                coordinatesLabel = "GPS Hardware Unavailable",
                readableAddress = "Location service unavailable"
            )

        val freshOrCached = obtainBestLocation(locationManager, hasFine)
            ?: return@withContext VerifiedLocationSnapshot(
                coordinatesLabel = "Acquiring GPS Signal",
                readableAddress = "GPS signal unavailable indoors"
            )

        val lat = freshOrCached.latitude
        val lon = freshOrCached.longitude
        val coordsLabel = formatCoordinates(lat, lon, freshOrCached.accuracy.takeIf { it > 0f })
        val resolvedAddress = reverseGeocodeAddress(lat, lon) ?: "GPS Fix ($coordsLabel)"

        VerifiedLocationSnapshot(
            latitude = lat,
            longitude = lon,
            coordinatesLabel = coordsLabel,
            readableAddress = resolvedAddress,
            accuracyMeters = freshOrCached.accuracy.takeIf { it > 0f },
            providerName = freshOrCached.provider ?: "gps"
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun obtainBestLocation(
        locationManager: LocationManager,
        hasFine: Boolean
    ): Location? {
        val preferredProviders = buildList {
            if (hasFine && isProviderEnabled(locationManager, LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (isProviderEnabled(locationManager, LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }

        for (provider in preferredProviders) {
            val activeFix = withTimeoutOrNull(LOCATION_FIX_TIMEOUT_MS) {
                requestSingleLocation(locationManager, provider)
            }
            if (activeFix != null) {
                return activeFix
            }
        }

        val lastCandidates = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }

        return lastCandidates.maxByOrNull { loc ->
            val recencyBonus = loc.time / 1000L
            val accuracyPenalty = loc.accuracy.toLong().coerceAtMost(500L)
            recencyBonus - accuracyPenalty
        }
    }

    private fun isProviderEnabled(locationManager: LocationManager, provider: String): Boolean {
        return runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleLocation(
        locationManager: LocationManager,
        provider: String
    ): Location? = suspendCancellableCoroutine { continuation ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            runCatching {
                locationManager.getCurrentLocation(
                    provider,
                    signal,
                    ContextCompat.getMainExecutor(appContext)
                ) { location ->
                    if (continuation.isActive) {
                        continuation.resume(location)
                    }
                }
            }.onFailure {
                if (continuation.isActive) continuation.resume(null)
            }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    runCatching { locationManager.removeUpdates(this) }
                    if (continuation.isActive) continuation.resume(location)
                }

                override fun onProviderDisabled(providerName: String) = Unit
                override fun onProviderEnabled(providerName: String) = Unit
            }
            continuation.invokeOnCancellation {
                runCatching { locationManager.removeUpdates(listener) }
            }
            runCatching {
                @Suppress("DEPRECATION")
                locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }.onFailure {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private suspend fun reverseGeocodeAddress(lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(appContext, Locale.getDefault())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    runCatching {
                        geocoder.getFromLocation(lat, lon, 1) { addresses ->
                            if (continuation.isActive) {
                                continuation.resume(addresses.firstOrNull()?.let(::formatAddress))
                            }
                        }
                    }.onFailure {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
        } else {
            runCatching {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()?.let(::formatAddress)
            }.getOrNull()
        }
    }

    companion object {
        private const val LOCATION_FIX_TIMEOUT_MS = 2200L
        private const val GEOCODE_TIMEOUT_MS = 2200L

        internal fun formatCoordinates(lat: Double, lon: Double, accuracyMeters: Float? = null): String {
            val base = String.format(Locale.US, "%.6f, %.6f", lat, lon)
            return if (accuracyMeters != null && accuracyMeters > 0f) {
                "$base (±${accuracyMeters.toInt()}m)"
            } else {
                base
            }
        }

        internal fun formatAddress(address: Address): String? {
            val line0 = runCatching { address.getAddressLine(0) }.getOrNull()?.trim()
            if (!line0.isNullOrBlank()) return line0
            val parts = listOfNotNull(
                address.featureName,
                address.thoroughfare,
                address.subLocality,
                address.locality,
                address.adminArea,
                address.countryName
            ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            return if (parts.isNotEmpty()) parts.joinToString(", ") else null
        }
    }
}
