package com.triggerbee.sdk

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration passed once to [Triggerbee.init].
 *
 * @property siteId The Triggerbee site id (visible in the dashboard URL).
 * @property baseUrl Root URL of the Triggerbee Public API. Defaults to [DEFAULT_BASE_URL]
 *   (https://api.triggerbee.com). Override to point at a non-production environment.
 * @property applicationId Override for the auto-detected application id. Left null in normal
 *   usage; the SDK reads [Context.packageName][android.content.Context.getPackageName] during
 *   init. Set only when the app needs to identify as a different bundle (white-label rebuilds).
 * @property connectTimeout HTTP connect timeout per request.
 * @property readTimeout HTTP read timeout per request.
 * @property logger Where SDK diagnostic logs go. Defaults to [NoOpLogger] (silent). Wire in
 *   [AndroidLogger] when you want SDK + WebView events surfaced in Logcat.
 * @property userAgent Optional User-Agent override. When null, OkHttp's default is used.
 */
public data class TriggerbeeConfig(
    public val siteId: Long,
    public val baseUrl: String = DEFAULT_BASE_URL,
    public val applicationId: String? = null,
    public val connectTimeout: Duration = 10.seconds,
    public val readTimeout: Duration = 10.seconds,
    public val logger: TriggerbeeLogger = NoOpLogger,
    public val userAgent: String? = null,
) {
    init {
        require(siteId > 0) { "siteId must be > 0, got $siteId" }
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(!baseUrl.endsWith('/')) { "baseUrl must not end with '/', got $baseUrl" }
    }

    public companion object {
        /** Production Public API URL — applied when [baseUrl] isn't specified. */
        public const val DEFAULT_BASE_URL: String = "https://api.triggerbee.com"
    }
}
