package com.triggerbee.sdk.internal

import kotlinx.serialization.Serializable

// Wire-format DTOs. All @Serializable so kotlinx.serialization can round-trip them via Retrofit's
// kotlinx-serialization converter. Names use the camelCase the backend expects.

@Serializable
internal data class PageloadRequest(
    val pageview: Pageview,
    val currentVisit: CurrentVisit,
    val closedWidgets: List<ClosedWidget>,
    val visitorData: VisitorData? = null,
    val device: DeviceInfoDto? = null,
)

@Serializable
internal data class CheckRequest(
    val currentVisit: CurrentVisit,
    val closedWidgets: List<ClosedWidget>,
    val visitorData: VisitorData? = null,
    val device: DeviceInfoDto? = null,
)

/**
 * Wire representation of [com.triggerbee.sdk.models.DeviceInfo]. Same shape; the indirection
 * exists so the public API model stays in `models/` (a stable surface) while the wire DTO can
 * evolve independently if the server protocol drifts.
 */
@Serializable
internal data class DeviceInfoDto(
    val platform: String,
    val type: String,
    val osVersion: String,
    val osApiLevel: Int,
    val sdkVersion: String,
    val manufacturer: String,
    val model: String,
    val appVersion: String? = null,
    val locale: String,
    val timeZone: String,
    val screenWidth: Int,
    val screenHeight: Int,
)

@Serializable
internal data class Pageview(
    val path: String,
    val title: String,
)

@Serializable
internal data class CurrentVisit(
    val uid: Long,
    val page: String,
    val secondsOnPage: Int,
    val pageviews: Int,
    val usersTime: String,
    val ipAddress: String? = null,
)

@Serializable
internal data class ClosedWidget(
    val widgetId: Int,
    val closedTime: Long,
    val reason: String? = null,
    val pageviews: Int,
)

@Serializable
internal data class VisitorData(
    val identifier: String? = null,
    val goals: List<String> = emptyList(),
    val landingPageQueryParams: List<String> = emptyList(),
)

/**
 * Local-only mirror of the visitor's audience-relevant state. Persisted as one JSON blob in
 * DataStore so we can do realtime audience matching on the next pageload without waiting for
 * the data to land in the Visitors database. Identifier lives in [SessionContext]; this only
 * carries fields the server doesn't already echo back to us.
 */
@Serializable
internal data class VisitorAudienceState(
    val goals: List<String> = emptyList(),
    val landingPageQueryParams: List<String> = emptyList(),
)

@Serializable
internal data class CheckWidgetsResponse(
    val widgets: List<WidgetCheckDto> = emptyList(),
)

@Serializable
internal data class WidgetCheckDto(
    val id: Int,
    val result: Boolean,
    val openDelay: Int = 0,
)

@Serializable
internal data class IdentifyRequest(
    val identifier: String,
    val properties: Map<String, String>,
    val device: DeviceInfoDto? = null,
)

@Serializable
internal data class GoalRequest(
    val name: String,
    val revenue: String? = null,
    val device: DeviceInfoDto? = null,
)

@Serializable
internal data class PurchaseRequest(
    val revenue: String?,
    val couponCode: String?,
    val device: DeviceInfoDto? = null,
)

@Serializable
internal data class PageviewRequest(
    val path: String,
    val title: String,
    val device: DeviceInfoDto? = null,
)

// Mirrors PublicApiGateway's EventsBatchRequest. All groups optional; sent as `null` (not `[]`) when
// absent so the backend can short-circuit empty lists. Device lives at the root only — nested
// items intentionally have no device field (server applies the root device to every nested event).
@Serializable
internal data class BatchRequest(
    val pageviews: List<BatchPageviewDto>? = null,
    val goals: List<BatchGoalDto>? = null,
    val purchases: List<BatchPurchaseDto>? = null,
    val identify: BatchIdentifyDto? = null,
    val device: DeviceInfoDto? = null,
)

@Serializable
internal data class BatchPageviewDto(val path: String? = null, val title: String? = null)

@Serializable
internal data class BatchPurchaseDto(val revenue: String? = null, val couponCode: String? = null)

@Serializable
internal data class BatchGoalDto(val name: String, val revenue: String? = null)

@Serializable
internal data class BatchIdentifyDto(
    val identifier: String?,
    val properties: Map<String, String>?,
)
