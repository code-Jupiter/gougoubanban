package com.littlekitty.xiaokele.core.network

/**
 * Marker contract for every future external supplier adapter
 * (MiniMax, server-backed search, QWeather, ShiftService, EmbeddingService).
 *
 * Product rule C13: all supplier calls go through replaceable local Service
 * interfaces; business layers must never depend on vendor response types.
 * Phase 0 defines the boundary only — no real service is implemented.
 */
interface ServiceAdapter {
    val serviceName: String

    /** Stable machine provenance recorded in normalized external payloads. */
    val provenance: String get() = serviceName

    /** Debug fakes override this; release adapters always report false. */
    val isDebugFake: Boolean get() = false
}
