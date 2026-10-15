package com.littlekitty.xiaokele.external

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Public-edition location model.
 *
 * The companion only ever consumes the user's *current* foreground location for
 * local-life search and weather. There is deliberately no home/hospital/work
 * place concept, no geofence, and no transition/arrival semantics: a location
 * fix is a point in time, not a place the product recognises.
 */

@Serializable
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

@Serializable
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    @SerialName("accuracy_meters")
    val accuracyMeters: Double? = null,
)

@Serializable
data class LocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    @SerialName("accuracy_meters")
    val accuracyMeters: Double? = null,
    @SerialName("observed_at_epoch_millis")
    val observedAtEpochMillis: Long,
    /** Always [LocationSnapshot.CURRENT_PLACE]; kept for cache compatibility. */
    @SerialName("place_type")
    val placeType: String = CURRENT_PLACE,
) {
    companion object {
        /**
         * Single place discriminator for the public edition. It replaces the
         * removed HOME/HOSPITAL values so weather/location cache rows still key
         * on a stable string.
         */
        const val CURRENT_PLACE = "CURRENT"
    }
}

fun LocationFix.toSnapshot(observedAtEpochMillis: Long): LocationSnapshot = LocationSnapshot(
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    observedAtEpochMillis = observedAtEpochMillis,
)

fun LocationSnapshot.toGeoPoint(): GeoPoint = GeoPoint(latitude = latitude, longitude = longitude)
