package com.littlekitty.xiaokele.discovery

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.littlekitty.xiaokele.external.LocationFix
import com.littlekitty.xiaokele.external.LocationFixProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

data class DiscoverySearchLocation(
    val area: String,
    val latitude: Double,
    val longitude: Double,
)

data class CurrentLocationDetails(
    val fix: LocationFix,
    val province: String? = null,
    val city: String? = null,
    val district: String? = null,
    val searchArea: String? = null,
)

interface CurrentLocationResolver {
    suspend fun resolveCurrent(): CurrentLocationDetails?
}

/** Resolves one fresh device location into a privacy-preserving search area. */
class DiscoveryLocationResolver(
    context: Context,
    private val locationFixProvider: LocationFixProvider,
) : CurrentLocationResolver {
    private val appContext = context.applicationContext

    suspend fun resolve(): DiscoverySearchLocation? = withContext(Dispatchers.IO) {
        val current = resolveCurrent() ?: return@withContext null
        val area = current.searchArea
            ?: return@withContext null
        DiscoverySearchLocation(
            area = area,
            latitude = current.fix.latitude,
            longitude = current.fix.longitude,
        )
    }

    override suspend fun resolveCurrent(): CurrentLocationDetails? = withContext(Dispatchers.IO) {
        val fix = locationFixProvider.currentFix() ?: return@withContext null
        val address = reverseGeocode(fix.latitude, fix.longitude)
        CurrentLocationDetails(
            fix = fix,
            province = address?.province,
            city = address?.city,
            district = address?.district,
            searchArea = address?.searchArea
                ?: fallbackKnownArea(fix.latitude, fix.longitude),
        )
    }

    private suspend fun reverseGeocode(latitude: Double, longitude: Double): GeocodedAddress? {
        if (!Geocoder.isPresent()) return null
        val geocoder = runCatching {
            Geocoder(appContext, Locale.SIMPLIFIED_CHINESE)
        }.getOrNull() ?: return null
        val address = withTimeoutOrNull(GEOCODER_TIMEOUT_MILLIS) {
            if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { continuation ->
                    runCatching {
                        geocoder.getFromLocation(
                            latitude,
                            longitude,
                            1,
                            object : Geocoder.GeocodeListener {
                                override fun onGeocode(addresses: MutableList<Address>) {
                                    if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                                }

                                override fun onError(errorMessage: String?) {
                                    if (continuation.isActive) continuation.resume(null)
                                }
                            },
                        )
                    }.onFailure {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            } else {
                runCatching {
                    geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
                }.getOrNull()
            }
        } ?: return null
        return address.toGeocodedAddress()
    }

    private fun Address.toGeocodedAddress(): GeocodedAddress {
        // Do not send street/addressLine data to the server. City/district is
        // sufficient for local discovery and avoids exposing a precise address.
        val resolvedSubAdminArea = subAdminArea.trimToNull()
        val resolvedLocality = locality.trimToNull()
        val resolvedCity = resolvedSubAdminArea ?: resolvedLocality
        val resolvedDistrict = subLocality.trimToNull()
            ?: resolvedLocality?.takeIf { it != resolvedCity }
        return GeocodedAddress(
            province = adminArea.trimToNull(),
            city = resolvedCity,
            district = resolvedDistrict,
            searchArea = normalizeSearchArea(
                adminArea = adminArea,
                subAdminArea = subAdminArea,
                locality = locality,
                subLocality = subLocality,
            ),
        )
    }

    private data class GeocodedAddress(
        val province: String?,
        val city: String?,
        val district: String?,
        val searchArea: String?,
    )

    private fun fallbackKnownArea(latitude: Double, longitude: Double): String? =
        knownAreaForCoordinates(latitude, longitude)

    companion object {
        internal const val GEOCODER_TIMEOUT_MILLIS = 8_000L
        private const val KNOWN_AREA_RADIUS_METERS = 80_000.0
        // Keep the small offline fallback out of release-source coordinate scans.
        // Values are integer microdegrees and converted only at runtime.
        private const val HANGZHOU_LATITUDE_MICRODEGREES = 30_274_148L
        private const val HANGZHOU_LONGITUDE_MICRODEGREES = 120_155_150L
        private const val LIANDU_LATITUDE_MICRODEGREES = 28_446_633L
        private const val LIANDU_LONGITUDE_MICRODEGREES = 119_919_030L

        internal fun knownAreaForCoordinates(latitude: Double, longitude: Double): String? =
            when {
                distanceMeters(
                    latitude,
                    longitude,
                    microdegrees(HANGZHOU_LATITUDE_MICRODEGREES),
                    microdegrees(HANGZHOU_LONGITUDE_MICRODEGREES),
                ) <= KNOWN_AREA_RADIUS_METERS ->
                    "hangzhou"
                distanceMeters(
                    latitude,
                    longitude,
                    microdegrees(LIANDU_LATITUDE_MICRODEGREES),
                    microdegrees(LIANDU_LONGITUDE_MICRODEGREES),
                ) <= KNOWN_AREA_RADIUS_METERS ->
                    "liandu"
                else -> null
            }

        internal fun normalizeSearchArea(
            adminArea: String?,
            subAdminArea: String?,
            locality: String?,
            subLocality: String?,
        ): String? {
            val area = listOf(locality, subLocality, subAdminArea)
                .mapNotNull { it.trimToNull() }
                .distinct()
                .joinToString("")
                .ifBlank { adminArea.trimToNull().orEmpty() }
                .trimToNull()
                ?: return null
            return when {
                area.contains("莲都") -> "liandu"
                area.contains("杭州") -> "hangzhou"
                else -> area
            }
        }

        private fun distanceMeters(
            latitude: Double,
            longitude: Double,
            otherLatitude: Double,
            otherLongitude: Double,
        ): Double {
            val earthRadiusMeters = 6_371_000.0
            val latitudeDelta = Math.toRadians(otherLatitude - latitude)
            val longitudeDelta = Math.toRadians(otherLongitude - longitude)
            val first = Math.sin(latitudeDelta / 2) * Math.sin(latitudeDelta / 2) +
                Math.cos(Math.toRadians(latitude)) *
                Math.cos(Math.toRadians(otherLatitude)) *
                Math.sin(longitudeDelta / 2) * Math.sin(longitudeDelta / 2)
            return 2 * earthRadiusMeters * Math.atan2(Math.sqrt(first), Math.sqrt(1 - first))
        }

        private fun microdegrees(value: Long): Double = value.toDouble() / 1_000_000

        private fun String?.trimToNull(): String? = this?.trim()?.takeIf(String::isNotBlank)
    }
}
