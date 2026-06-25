package com.triggerbee.sdk

import android.content.Context
import com.triggerbee.sdk.internal.DataStoreSessionStore
import com.triggerbee.sdk.internal.DeviceInfoCollector
import com.triggerbee.sdk.internal.SdkClient
import com.triggerbee.sdk.models.BatchGoal
import com.triggerbee.sdk.models.BatchIdentify
import com.triggerbee.sdk.models.BatchPageview
import com.triggerbee.sdk.models.BatchPurchase
import com.triggerbee.sdk.models.CloseReason
import com.triggerbee.sdk.models.SessionContext
import com.triggerbee.sdk.models.WidgetCheckResponse
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom

/**
 * Single entry point to the Triggerbee SDK. Call [init] once during app startup, then use
 * the suspend methods from any coroutine scope.
 *
 * ```
 * // Application.onCreate
 * Triggerbee.init(this, TriggerbeeConfig(siteId = YOUR_SITE_ID, baseUrl = "https://api.triggerbee.com"))
 *
 * // Anywhere later
 * lifecycleScope.launch {
 *     Triggerbee.start()
 *     val widgets = Triggerbee.pageload(page = "/profile", title = "Profile")
 *     widgets.firstOrNull { it.result }?.let { openWebView(Triggerbee.widgetUrl(it.id)) }
 * }
 * ```
 */
public object Triggerbee {
    // @Volatile guarantees the SdkClient reference set by init() is visible to other
    // threads' subsequent reads — without it the JMM lets each thread cache its own copy
    // and an init() on the main thread can race a require() on a coroutine worker.
    @Volatile
    private var client: SdkClient? = null

    @Volatile
    private var disabled: Boolean = false

    /**
     * Configure the SDK. Call once, typically in `Application.onCreate`. Calling a second
     * time replaces the configuration (useful for tests; avoid in production code).
     */
    public fun init(context: Context, config: TriggerbeeConfig) {
        val applicationId = config.applicationId ?: context.packageName
        val sessionStore = DataStoreSessionStore(context.applicationContext)
        val deviceInfo = DeviceInfoCollector.collect(context.applicationContext)
        client = SdkClient(config, applicationId, sessionStore, deviceInfo)
    }

    /**
     * Opt the visitor out of all Triggerbee tracking for the rest of the process lifetime.
     * After this call every public method becomes a silent no-op (suspend methods return
     * immediately, `pageload`/`recheck` return an empty list, `widgetUrl` returns an empty
     * string) and no network requests are made.
     *
     * Persisted state (uid, identifier, closed widgets) is left intact so [enable] can
     * resume the same session — host apps wanting full erasure should additionally clear
     * the SDK's DataStore by uninstalling the app or invoking their own GDPR-erasure flow
     * against Triggerbee's backend.
     */
    public fun disable() {
        disabled = true
    }

    /** Re-enable tracking after a prior [disable] call. Idempotent. */
    public fun enable() {
        disabled = false
    }

    /** `true` once [disable] has been called; flips back to `false` after [enable]. */
    public val isDisabled: Boolean
        get() = disabled

    /**
     * Generate and persist a visitor id (uid) on first launch; reuse the existing id on
     * subsequent launches. Idempotent — safe to call every app start. Read the resulting uid
     * from [sessionContext] after this returns.
     *
     * The id is generated client-side with [SecureRandom] (non-zero, uniformly distributed
     * over `[1, 2^53 - 1]` — capped to the JavaScript `Number.MAX_SAFE_INTEGER` so the value
     * survives the WebView's `parseInt(cookie)` round-trip in mytracker.js without IEEE 754
     * precision loss). The backend's `POST /v1/visitors/uid` endpoint still exists but is not
     * called by this SDK.
     */
    public suspend fun start() {
        if (disabled) { return }
        require().start { generateUid() }
    }

    /**
     * Log a pageview and get the widgets that should be considered for display on this page.
     * Replaces a separate `logPageview` + `checkWidgets` pair from the proof-of-concept SDK.
     */
    public suspend fun pageload(
        page: String,
        title: String,
        secondsOnPage: Int = 0,
    ): List<WidgetCheckResponse> {
        if (disabled) { return emptyList() }
        return require().pageload(page, title, secondsOnPage)
    }

    /**
     * Re-evaluate widgets for the current page without logging a new pageview. Use after
     * waiting out an `openDelay` returned from [pageload].
     */
    public suspend fun recheck(page: String, secondsOnPage: Int): List<WidgetCheckResponse> {
        if (disabled) { return emptyList() }
        return require().recheck(page, secondsOnPage)
    }

    /**
     * Mark a widget closed locally; sent on the next pageload/recheck so the backend can
     * apply the matching repetition rule.
     */
    public fun closeWidget(widgetId: Int, reason: CloseReason? = null) {
        if (disabled) { return }
        require().closeWidget(widgetId, reason)
    }

    /**
     * Identify a visitor with custom properties. Optionally with an identifier (e.g. email or
     * phone). Properties are sent as a flat string map.
     *
     * **Concurrency:** two concurrent `identify` calls with different identifiers may reach the
     * backend in either order, and the local [sessionContext] reflects whichever finished last.
     * Await each call before issuing the next when ordering matters.
     */
    public suspend fun identify(identifier: String, properties: Map<String, String> = emptyMap()) {
        if (disabled) { return }
        require().identify(identifier, properties)
    }

    /**
     * Log a custom goal. `revenue` is a string so the caller controls formatting (e.g. "199.00").
     * The goal name is also persisted locally and replayed on the next [pageload] / [recheck]
     * so audience rules can match in realtime — before the goal lands in the Visitors database.
     */
    public suspend fun logGoal(name: String, revenue: String? = null) {
        if (disabled) { return }
        require().logGoal(name, revenue)
    }

    /**
     * Log a purchase. `revenue` is a string so the caller controls formatting (e.g. "199.00"),
     * mirroring [logGoal]. Hits `POST /v1/events/{uid}/purchase`.
     */
    public suspend fun logPurchase(revenue: String? = null, couponCode: String? = null) {
        if (disabled) { return }
        require().logPurchase(revenue, couponCode)
    }

    /**
     * Log a pageview event without checking widgets. Use this when you only want to record that
     * the visitor saw a page (e.g. for analytics / audience matching) but don't need the widgets
     * that would normally trigger on this page — for that, use [pageload] instead.
     * Hits `POST /v1/events/{uid}/pageview`.
     */
    public suspend fun pageview(page: String, title: String) {
        if (disabled) { return }
        require().pageview(page, title)
    }

    /**
     * Replace the persisted set of landing-page query params, the mobile equivalent of the web
     * SDK's URL-derived `landingPageQueryParams`. Audience rules like "came in via the newsletter
     * campaign" check against these. Caller is responsible for parsing the incoming deep link /
     * push-notification URL and feeding the `key=value` strings:
     *
     * ```
     * val uri = intent?.data ?: return
     * Triggerbee.setLandingPageQueryParams(uri.queryParameterNames.map { "$it=${uri.getQueryParameter(it)}" })
     * ```
     *
     * Persists across launches; pass `emptyList()` to clear.
     */
    public suspend fun setLandingPageQueryParams(params: List<String>) {
        if (disabled) { return }
        require().setLandingPageQueryParams(params)
    }

    /**
     * Log multiple events for the current visitor in a single round-trip. Each list is
     * independently optional — call with only the groups you actually have. [identify], if
     * provided, is applied first so subsequent events in the batch attribute to the new
     * identifier.
     *
     * **Concurrency:** when called concurrently with [identify] or another [batch] carrying an
     * identifier, the final [sessionContext] identifier reflects whichever call's post-request
     * write happened last. Await each call before issuing the next when ordering matters.
     *
     * The SDK itself never calls this method — it's exposed so consumers with their own
     * offline-queueing or batching strategy can send everything in one request.
     */
    public suspend fun batch(
        pageviews: List<BatchPageview> = emptyList(),
        goals: List<BatchGoal> = emptyList(),
        purchases: List<BatchPurchase> = emptyList(),
        identify: BatchIdentify? = null,
    ) {
        if (disabled) { return }
        require().batch(pageviews, goals, purchases, identify)
    }

    /**
     * Build the URL for a widget's rendered HTML — feed this into `WebView.loadUrl(...)`. The
     * URL carries `uid` and `applicationId` so Tracker can authenticate the WebView's tracking
     * calls against the account's AllowedApplicationIds.
     */
    public fun widgetUrl(widgetId: Int): String {
        if (disabled) { return "" }
        return require().widgetUrl(widgetId)
    }

    /**
     * Hot stream of session state. Emits whenever [start], [identify], [pageload], or
     * [closeWidget] mutates the in-memory session.
     *
     * **Throws** [TriggerbeeException.NotInitialized] if accessed before [init]. Compose call
     * sites that collect this should ensure `Triggerbee.init(...)` runs in
     * `Application.onCreate` (or any equally-early entry point) so the first composition has a
     * configured singleton to read from.
     */
    public val sessionContext: StateFlow<SessionContext>
        get() = require().sessionContext

    private fun require(): SdkClient =
        client ?: throw TriggerbeeException.NotInitialized()

    internal fun logger(): TriggerbeeLogger = require().logger

    // In-process dedup set for "this widget has already had its `open` event logged this visit".
    // Native equivalent of the web tracker's sessionStorage `mtr_v.viewedWidgetIds` — same
    // semantic (one open-log per visit, where visit = app process lifetime here vs browser tab on
    // web). Scoped to the `open` event only; close/clickthrough/etc. always log. Cleared implicitly
    // when the OS reclaims the process.
    private val openLoggedWidgetIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    internal fun hasLoggedOpen(id: Int): Boolean = id in openLoggedWidgetIds

    internal fun markOpenLogged(id: Int) {
        openLoggedWidgetIds.add(id)
    }

    private fun generateUid(): Long {
        // Positive 53-bit visitor id, never zero. Capped at Number.MAX_SAFE_INTEGER so the value
        // survives the WebView's `parseInt(cookie)` round-trip in mytracker.js without IEEE 754
        // precision loss. 2^53 - 1 ≈ 9 quadrillion ids is well beyond any collision concern.
        var value: Long
        do {
            val bytes = ByteArray(Long.SIZE_BYTES)
            secureRandom.nextBytes(bytes)
            value = bytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFFL) } and ((1L shl 53) - 1)
        } while (value == 0L)
        return value
    }

    private val secureRandom by lazy { SecureRandom() }
}
