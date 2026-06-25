package com.triggerbee.sdk.models

/**
 * Result of a per-page widget check from the backend. The SDK returns a list of these from
 * [com.triggerbee.sdk.Triggerbee.pageload] and [com.triggerbee.sdk.Triggerbee.recheck];
 * callers typically pick the first `result == true` widget to render.
 *
 * @property id The widget id; pass to `widgetUrl(id)` to load the rendered HTML in a WebView.
 * @property result `true` if the widget should be shown to this visitor right now.
 * @property openDelay Seconds remaining until a time-based trigger fires. Non-zero only when
 *   [result] is false because an AfterXSeconds trigger has not yet elapsed; schedule a
 *   `recheck` after this delay.
 */
public data class WidgetCheckResponse(
    public val id: Int,
    public val result: Boolean,
    public val openDelay: Int,
)

/**
 * Reason a widget was closed by the visitor — sent to the backend so it can apply the
 * matching repetition rule (e.g. "don't show again for 7 days").
 */
public enum class CloseReason {
    Dismissal,
    Conversion,
    ContinueShowing,
}

/**
 * Visual mode of a widget state, reported by the widget engine via the JS bridge so the SDK
 * can size and position the WebView appropriately. `Unknown` is the safe fallback for any
 * value the SDK doesn't yet recognize — the SDK treats unknown layouts as fullscreen.
 */
public enum class WidgetLayout {
    Fullscreen,
    Popup,
    Panel,
    Callout,
    Unknown;

    public companion object {
        public fun fromString(value: String?): WidgetLayout = when (value) {
            "Fullscreen" -> Fullscreen
            "Popup" -> Popup
            "Panel" -> Panel
            "Callout" -> Callout
            else -> Unknown
        }
    }
}

/**
 * Anchor point of a widget within its containing viewport, reported by the widget engine via
 * the JS bridge. Mirrors `Triggerbee.Widgets.Core.Enums.WidgetPosition` on the backend so wire
 * values pass through unchanged. `Center`, `Embedded`, `Fullscreen`, and `ProductTour` are
 * treated as fill-screen by the SDK (they have no meaningful sub-screen anchor); the
 * directional values map onto Compose `Alignment` with RTL-aware Start / End semantics.
 */
public enum class WidgetPosition {
    Center,
    Top,
    Bottom,
    Left,
    Right,
    TopLeft,
    TopRight,
    BottomLeft,
    BottomRight,
    Embedded,
    Fullscreen,
    ProductTour,
    Unknown;

    public companion object {
        public fun fromString(value: String?): WidgetPosition = when (value) {
            "Center" -> Center
            "Top" -> Top
            "Bottom" -> Bottom
            "Left" -> Left
            "Right" -> Right
            "TopLeft" -> TopLeft
            "TopRight" -> TopRight
            "BottomLeft" -> BottomLeft
            "BottomRight" -> BottomRight
            "Embedded" -> Embedded
            "Fullscreen" -> Fullscreen
            "ProductTour" -> ProductTour
            else -> Unknown
        }
    }
}

/**
 * Snapshot of the device the SDK is running on. Built once at [com.triggerbee.sdk.Triggerbee.init]
 * and sent in every pageload/recheck request so audience filters can match on device attributes
 * (analogous to what `UAParser` derives from User-Agent for web sessions).
 *
 * All fields are inferred from [android.os.Build] / [java.util.Locale] / [android.content.Context]
 * — no permissions, no PII, no device identifiers.
 *
 * @property platform Always `"android"` (`"ios"` will appear when the iOS SDK lands).
 * @property type Device class: `"mobile"` or `"tablet"`. Derived from
 *   `Configuration.smallestScreenWidthDp` using the standard sw600dp threshold.
 * @property osVersion Android version string from [android.os.Build.VERSION.RELEASE], e.g. `"13"`.
 * @property osApiLevel Android API level from [android.os.Build.VERSION.SDK_INT], e.g. `33`.
 * @property sdkVersion Triggerbee SDK semver, e.g. `"0.2.0"`.
 * @property manufacturer Device manufacturer from [android.os.Build.MANUFACTURER], e.g. `"samsung"`.
 * @property model Device model from [android.os.Build.MODEL], e.g. `"SM-S908B"`.
 * @property appVersion Host app's [android.content.pm.PackageInfo.versionName], or null if unreadable.
 * @property locale BCP-47 language tag from [java.util.Locale.toLanguageTag], e.g. `"en-US"`.
 * @property timeZone IANA time-zone id from [java.util.TimeZone.getID], e.g. `"Europe/Stockholm"`.
 * @property screenWidth Display width in physical pixels.
 * @property screenHeight Display height in physical pixels.
 */
public data class DeviceInfo(
    public val platform: String,
    public val type: String,
    public val osVersion: String,
    public val osApiLevel: Int,
    public val sdkVersion: String,
    public val manufacturer: String,
    public val model: String,
    public val appVersion: String?,
    public val locale: String,
    public val timeZone: String,
    public val screenWidth: Int,
    public val screenHeight: Int,
)

/** Pageview entry in a [com.triggerbee.sdk.Triggerbee.batch] call. */
public data class BatchPageview(public val path: String, public val title: String? = null)

/** Goal entry in a [com.triggerbee.sdk.Triggerbee.batch] call. */
public data class BatchGoal(public val name: String, public val revenue: String? = null)

/** Purchase entry in a [com.triggerbee.sdk.Triggerbee.batch] call.
 *  `revenue` is a string so the caller controls formatting (e.g. `"199.00"`), matching
 *  [com.triggerbee.sdk.Triggerbee.logPurchase] and the batch endpoint contract. */
public data class BatchPurchase(public val revenue: String? = null, public val couponCode: String? = null)

/** Identify entry in a [com.triggerbee.sdk.Triggerbee.batch] call. */
public data class BatchIdentify(
    public val identifier: String,
    public val properties: Map<String, String> = emptyMap(),
)

/**
 * Read-only snapshot of session state observable via [com.triggerbee.sdk.Triggerbee.sessionContext].
 *
 * @property uid Visitor id assigned by [com.triggerbee.sdk.Triggerbee.start]. `0` before start.
 * @property pageviews How many pageloads have been logged in this process lifetime
 *   (in-memory, resets on process death — same semantics as the web SDK's `sessionStorage`).
 * @property identifier Email or other identifier set by [com.triggerbee.sdk.Triggerbee.identify].
 */
public data class SessionContext(
    public val uid: Long,
    public val pageviews: Int,
    public val identifier: String?,
)
