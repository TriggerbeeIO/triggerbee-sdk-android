package com.triggerbee.sdk.internal

import com.triggerbee.sdk.TriggerbeeConfig
import com.triggerbee.sdk.TriggerbeeException
import com.triggerbee.sdk.models.BatchGoal
import com.triggerbee.sdk.models.BatchIdentify
import com.triggerbee.sdk.models.BatchPageview
import com.triggerbee.sdk.models.BatchPurchase
import com.triggerbee.sdk.models.CloseReason
import com.triggerbee.sdk.models.ClosedWidgetEntry
import com.triggerbee.sdk.models.DeviceInfo
import com.triggerbee.sdk.models.SessionContext
import com.triggerbee.sdk.models.WidgetCheckResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Holds the configured Retrofit client, the persistent [SessionStore], and the in-memory
 * session state. One instance per [com.triggerbee.sdk.Triggerbee.init] call.
 *
 * Public [com.triggerbee.sdk.Triggerbee] methods are thin wrappers around this class so the
 * singleton stays small and testable.
 */
internal class SdkClient(
    private val config: TriggerbeeConfig,
    private val applicationId: String,
    private val sessionStore: SessionStore,
    // Snapshot of the host device taken at init. Static for the process lifetime, so the
    // serialized DTO is computed once and reused on every outgoing widget-check request.
    private val deviceInfo: DeviceInfo,
    // Tests pass their TestScope so the fire-and-forget persistence triggered by
    // closeWidget() runs deterministically. Production callers leave this null and get a
    // process-lifetime IO scope.
    persistenceScope: CoroutineScope? = null,
) {
    private val deviceInfoDto: DeviceInfoDto = DeviceInfoDto(
        platform = deviceInfo.platform,
        type = deviceInfo.type,
        osVersion = deviceInfo.osVersion,
        osApiLevel = deviceInfo.osApiLevel,
        sdkVersion = deviceInfo.sdkVersion,
        manufacturer = deviceInfo.manufacturer,
        model = deviceInfo.model,
        appVersion = deviceInfo.appVersion,
        locale = deviceInfo.locale,
        timeZone = deviceInfo.timeZone,
        screenWidth = deviceInfo.screenWidth,
        screenHeight = deviceInfo.screenHeight,
    )
    private val scope: CoroutineScope = persistenceScope
        ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val logger: com.triggerbee.sdk.TriggerbeeLogger get() = config.logger
    private val api: TriggerbeeApi = buildApi()
    private val mutex = Mutex()

    // sessionState mirrors what's persisted plus in-memory `pageviews` (which intentionally
    // resets on process death — matches the web SDK's sessionStorage semantics).
    private val sessionState = MutableStateFlow(SessionContext(uid = 0L, pageviews = 0, identifier = null))
    private var closedWidgets: List<ClosedWidgetEntry> = emptyList()
    // In-memory mirror of the persisted audience state. Loaded once in ensureLoaded() and
    // updated alongside every persistence write so reads on the hot path (pageload/recheck)
    // don't have to hit DataStore. Tracks visitor state (goals, landing-page params) the
    // server uses for realtime audience matching before the Visitors database has caught up.
    private var audienceState: VisitorAudienceState = VisitorAudienceState()
    private var initialised = false

    val sessionContext: StateFlow<SessionContext> = sessionState.asStateFlow()

    suspend fun start(generate: () -> Long): Long = mutex.withLock {
        ensureLoaded()
        val existing = sessionState.value.uid
        // Self-heal: treat any non-positive stored uid as missing (an earlier SDK build could
        // persist negative values; the backend rejects uid <= 0). Mints a fresh positive id.
        if (existing > 0L) {
            config.logger.debug("start: uid=$existing (persisted)")
            return@withLock existing
        }
        // No network I/O here, so we hold the lock for the whole method — splitting it would
        // race two concurrent start() callers into generating distinct uids and last-writer-wins
        // on the DataStore. Each caller would then return a *different* uid from the one that
        // actually got persisted.
        val fresh = generate()
        sessionStore.setUid(fresh)
        sessionState.update { it.copy(uid = fresh) }
        config.logger.debug("start: uid=$fresh (minted)")
        fresh
    }

    suspend fun pageload(
        page: String,
        title: String,
        secondsOnPage: Int,
    ): List<WidgetCheckResponse> {
        val (request, pageviews) = mutex.withLock {
            ensureLoaded()
            val current = sessionState.value
            require(current.uid != 0L) { "Call Triggerbee.start() before pageload()" }

            val newPageviews = current.pageviews + 1
            sessionState.update { it.copy(pageviews = newPageviews) }

            PageloadRequest(
                pageview = Pageview(path = page, title = title),
                currentVisit = currentVisit(current.uid, page, secondsOnPage, newPageviews),
                closedWidgets = closedWidgets.map(::toDto),
                visitorData = visitorData(),
                device = deviceInfoDto,
            ) to newPageviews
        }
        config.logger.debug("pageload: page=$page title=$title pageviews=$pageviews secondsOnPage=$secondsOnPage")
        val response = request("pageload") { api.pageload(config.siteId, request) }
        val results = response.toResults()
        config.logger.debug("pageload → ${formatResults(results)}")
        return results
    }

    suspend fun recheck(page: String, secondsOnPage: Int): List<WidgetCheckResponse> {
        val (request, pageviews) = mutex.withLock {
            ensureLoaded()
            val current = sessionState.value
            require(current.uid != 0L) { "Call Triggerbee.start() before recheck()" }

            CheckRequest(
                currentVisit = currentVisit(current.uid, page, secondsOnPage, current.pageviews),
                closedWidgets = closedWidgets.map(::toDto),
                visitorData = visitorData(),
                device = deviceInfoDto,
            ) to current.pageviews
        }
        config.logger.debug("recheck: page=$page pageviews=$pageviews secondsOnPage=$secondsOnPage")
        val response = request("recheck") { api.check(config.siteId, request) }
        val results = response.toResults()
        config.logger.debug("recheck → ${formatResults(results)}")
        return results
    }

    private fun formatResults(results: List<WidgetCheckResponse>): String =
        results.joinToString(
            prefix = "${results.size} widgets [",
            separator = ", ",
            postfix = "]",
        ) { "id=${it.id} result=${it.result} openDelay=${it.openDelay}" }

    fun closeWidget(widgetId: Int, reason: CloseReason?): Job {
        config.logger.debug("closeWidget: id=$widgetId reason=$reason")
        // Public API is non-suspend (fire-and-forget intent — caller has nothing to await),
        // but the work must run under the mutex on a loaded state to avoid wiping the
        // persisted set with a singleton entry if no suspend method has run yet. The Job
        // is returned so tests can join it; production callers ignore it.
        return scope.launch {
            mutex.withLock {
                ensureLoaded()
                val entry = ClosedWidgetEntry(
                    widgetId = widgetId,
                    closedTime = System.currentTimeMillis(),
                    reason = reason?.name,
                    pageviews = sessionState.value.pageviews,
                )
                closedWidgets = closedWidgets.filterNot { it.widgetId == widgetId } + entry
                try {
                    sessionStore.setClosedWidgets(closedWidgets)
                    config.logger.debug("closeWidget ✓")
                } catch (e: Exception) {
                    config.logger.warn("closeWidget persistence failed", e)
                }
            }
        }
    }

    suspend fun identify(identifier: String, properties: Map<String, String> = emptyMap()) {
        val uid = mutex.withLock {
            ensureLoaded()
            val current = sessionState.value.uid
            require(current != 0L) { "Call Triggerbee.start() before identify()" }
            current
        }
        // PII: don't log identifier value (typically email/phone) — only its presence + property count.
        config.logger.debug("identify: hasIdentifier=true properties=${properties.size}")
        request("identify") { api.identify(config.siteId, uid, IdentifyRequest(identifier, properties, device = deviceInfoDto)) }
        mutex.withLock {
            sessionStore.setIdentifier(identifier)
            sessionState.update { it.copy(identifier = identifier) }
        }
        config.logger.debug("identify ✓")
    }

    suspend fun logGoal(name: String, revenue: String?) {
        val uid = mutex.withLock {
            ensureLoaded()
            val current = sessionState.value.uid
            require(current != 0L) { "Call Triggerbee.start() before logGoal()" }
            current
        }
        config.logger.debug("logGoal: name=$name revenue=$revenue")
        request("logGoal") { api.goal(config.siteId, uid, GoalRequest(name, revenue, device = deviceInfoDto)) }
        // Persist the goal locally so the next pageload/recheck includes it for realtime
        // audience matching, before the server-side Visitors database has caught up.
        mutex.withLock {
            if (name !in audienceState.goals) {
                audienceState = audienceState.copy(goals = audienceState.goals + name)
                sessionStore.setAudienceState(audienceState)
            }
        }
        config.logger.debug("logGoal ✓")
    }

    suspend fun logPurchase(revenue: String?, couponCode: String?) {
        val uid = mutex.withLock {
            ensureLoaded()
            val current = sessionState.value.uid
            require(current != 0L) { "Call Triggerbee.start() before logPurchase()" }
            current
        }
        config.logger.debug("logPurchase: revenue=$revenue couponCode=$couponCode")
        request("logPurchase") {
            api.purchase(config.siteId, uid, PurchaseRequest(revenue, couponCode, device = deviceInfoDto))
        }
        config.logger.debug("logPurchase ✓")
    }

    suspend fun pageview(page: String, title: String) {
        val uid = mutex.withLock {
            ensureLoaded()
            val current = sessionState.value.uid
            require(current != 0L) { "Call Triggerbee.start() before pageview()" }
            current
        }
        config.logger.debug("pageview: page=$page title=$title")
        request("pageview") {
            api.pageview(config.siteId, uid, PageviewRequest(page, title, device = deviceInfoDto))
        }
        config.logger.debug("pageview ✓")
    }

    /**
     * Replace the persisted set of landing-page query params. Mobile equivalent of the web SDK's
     * URL-derived `landingPageQueryParams` — used by audience rules like "came in via the
     * newsletter campaign". Caller is responsible for parsing a deep-link or push-notification
     * URL into `key=value` strings and feeding them here.
     */
    suspend fun setLandingPageQueryParams(params: List<String>) {
        config.logger.debug("setLandingPageQueryParams: count=${params.size}")
        mutex.withLock {
            ensureLoaded()
            audienceState = audienceState.copy(landingPageQueryParams = params)
            sessionStore.setAudienceState(audienceState)
        }
        config.logger.debug("setLandingPageQueryParams ✓")
    }

    suspend fun batch(
        pageviews: List<BatchPageview>,
        goals: List<BatchGoal>,
        purchases: List<BatchPurchase>,
        identify: BatchIdentify?,
    ) {
        val (uid, body) = mutex.withLock {
            ensureLoaded()
            val current = sessionState.value.uid
            require(current != 0L) { "Call Triggerbee.start() before batch()" }
            current to BatchRequest(
                pageviews = pageviews.takeIf { it.isNotEmpty() }?.map { BatchPageviewDto(it.path, it.title) },
                goals = goals.takeIf { it.isNotEmpty() }?.map { BatchGoalDto(it.name, it.revenue) },
                purchases = purchases.takeIf { it.isNotEmpty() }?.map { BatchPurchaseDto(it.revenue, it.couponCode) },
                identify = identify?.let { BatchIdentifyDto(it.identifier, it.properties) },
                device = deviceInfoDto,
            )
        }

        config.logger.debug(
            "batch: pageviews=${pageviews.size} goals=${goals.size} " +
                "purchases=${purchases.size} hasIdentify=${identify != null}"
        )
        request("batch") { api.batch(config.siteId, uid, body) }

        // Mirror what /identify would do locally so sessionContext stays consistent.
        if (identify != null) {
            mutex.withLock {
                sessionStore.setIdentifier(identify.identifier)
                sessionState.update { it.copy(identifier = identify.identifier) }
            }
        }
        config.logger.debug("batch ✓")
    }

    fun widgetUrl(widgetId: Int): String {
        val uid = sessionState.value.uid
        require(uid != 0L) { "Call Triggerbee.start() before widgetUrl()" }
        val url = "${config.baseUrl}/v1/client/widgets/$widgetId/html" +
            "?siteId=${config.siteId}&uid=$uid&applicationId=${URLEncoder.encode(applicationId, "UTF-8")}"
        config.logger.debug("widgetUrl: id=$widgetId → $url")
        return url
    }

    // Initial load happens lazily under the lock so concurrent first calls don't race the
    // DataStore reads. After the first successful load `initialised` stays true.
    private suspend fun ensureLoaded() {
        if (initialised) return
        val uid = sessionStore.getUid() ?: 0L
        val identifier = sessionStore.getIdentifier()
        closedWidgets = sessionStore.getClosedWidgets()
        audienceState = sessionStore.getAudienceState()
        sessionState.value = SessionContext(uid = uid, pageviews = 0, identifier = identifier)
        initialised = true
    }

    /** Snapshot of identifier + audience state for the next outgoing widget-check request. */
    private fun visitorData(): VisitorData = VisitorData(
        identifier = sessionState.value.identifier,
        goals = audienceState.goals,
        landingPageQueryParams = audienceState.landingPageQueryParams,
    )

    private fun currentVisit(uid: Long, page: String, secondsOnPage: Int, pageviews: Int): CurrentVisit =
        CurrentVisit(
            uid = uid,
            page = page,
            secondsOnPage = secondsOnPage,
            pageviews = pageviews,
            usersTime = LocalDateTime.now().format(LOCAL_DATETIME),
        )

    private fun toDto(entry: ClosedWidgetEntry): ClosedWidget =
        ClosedWidget(
            widgetId = entry.widgetId,
            closedTime = entry.closedTime,
            reason = entry.reason,
            pageviews = entry.pageviews,
        )

    private fun CheckWidgetsResponse.toResults(): List<WidgetCheckResponse> =
        widgets.map {
            WidgetCheckResponse(
                id = it.id,
                result = it.result,
                openDelay = it.openDelay,
            )
        }

    private suspend fun <T> request(operation: String, block: suspend () -> T): T {
        try {
            return block()
        } catch (e: HttpException) {
            val body = e.response()?.errorBody()?.string().orEmpty()
            config.logger.warn("$operation failed: HTTP ${e.code()} — $body")
            throw TriggerbeeException.HttpError(e.code(), body)
        } catch (e: IOException) {
            // Exception class + message inline so a stack-stripped logcat still surfaces the cause.
            config.logger.warn("$operation network error: ${e::class.simpleName}: ${e.message ?: "(no message)"}", e)
            throw TriggerbeeException.NetworkError(e)
        } catch (e: kotlinx.serialization.SerializationException) {
            config.logger.warn("$operation serialization error: ${e::class.simpleName}: ${e.message ?: "(no message)"}", e)
            throw TriggerbeeException.SerializationError(e)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun buildApi(): TriggerbeeApi {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            // Every /v1/client/* endpoint is gated by X-Application-Id on the gateway. Attach the
            // value (auto-detected from Context.packageName at init, or overridden via TriggerbeeConfig.applicationId)
            // to every outgoing request.
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Application-Id", applicationId).build())
            }
            .apply {
                config.userAgent?.let { ua ->
                    addInterceptor { chain ->
                        chain.proceed(chain.request().newBuilder().header("User-Agent", ua).build())
                    }
                }
            }
            .build()
        return Retrofit.Builder()
            .baseUrl(config.baseUrl.trimEnd('/') + "/")
            .client(httpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TriggerbeeApi::class.java)
    }

    private companion object {
        // Visitor wall-clock; matches what mytracker.js sends from a real browser
        // (yyyy-MM-dd'T'HH:mm:ss, no timezone — server treats it as the visitor's local time).
        val LOCAL_DATETIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }
}
