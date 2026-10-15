package com.littlekitty.xiaokele.external

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Supplies one fresh foreground/background location fix to the external-data flow. */
interface LocationFixProvider {
    suspend fun currentFix(): LocationFix?
}

/** Persists a fresh fix into the app-owned location snapshot workflow. */
interface LocationSnapshotRefresher {
    suspend fun persistFreshLocation(fix: LocationFix): LocationUpdateReport
}

/**
 * Android implementation using the platform current-location API. It deliberately
 * does not reuse an old last-known location: every refresh asks for a new fix.
 * Providers are tried in order because an enabled GPS provider may have no fix while
 * the network provider can still resolve the emulator or device location.
 */
class AndroidLocationFixProvider(
    private val context: Context,
) : LocationFixProvider {

    override suspend fun currentFix(): LocationFix? {
        if (!hasLocationPermission()) return null

        val locationManager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).filter { provider ->
            runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
        }

        for (provider in providers) {
            requestCurrentLocation(locationManager, provider)?.let { return it }
        }
        return null
    }

    private suspend fun requestCurrentLocation(
        locationManager: LocationManager,
        provider: String,
    ): LocationFix? = withTimeoutOrNull(CURRENT_LOCATION_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation { cancellationSignal.cancel() }
            try {
                locationManager.getCurrentLocation(
                    provider,
                    cancellationSignal,
                    ContextCompat.getMainExecutor(context),
                ) { location ->
                    if (!continuation.isActive) return@getCurrentLocation
                    continuation.resume(location?.toLocationFix())
                }
            } catch (_: SecurityException) {
                if (continuation.isActive) continuation.resume(null)
            } catch (_: RuntimeException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private fun android.location.Location.toLocationFix(): LocationFix? = LocationFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy.toDouble(),
    ).takeIf { fix ->
        fix.latitude in -90.0..90.0 &&
            fix.longitude in -180.0..180.0 &&
            (fix.accuracyMeters ?: 0.0) >= 0.0
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val CURRENT_LOCATION_TIMEOUT_MILLIS = 5_000L
    }
}
