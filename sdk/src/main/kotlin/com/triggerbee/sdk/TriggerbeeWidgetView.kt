package com.triggerbee.sdk

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.triggerbee.sdk.models.CloseReason
import com.triggerbee.sdk.models.WidgetLayout
import com.triggerbee.sdk.models.WidgetPosition
import kotlinx.coroutines.delay

private const val INITIAL_BOUNDS_TIMEOUT_MS = 3000L

/**
 * Renders a Triggerbee widget in a `WebView` inside Compose. The widget engine inside the
 * WebView reports its rendered bounds (width, height, position, layout) via the JS bridge;
 * this Composable sizes and positions the WebView accordingly so non-fullscreen layouts
 * (Callout, Panel, …) only cover their own area and the rest of the host UI stays
 * interactive.
 *
 * The WebView starts invisible and fades in once the first bounds are reported, preventing a
 * fullscreen flash before a small callout settles into its corner. If the widget engine
 * fails to report bounds within 3 seconds the SDK closes the widget (via `onClosed(null)`)
 * and logs a warning — chosen over a fullscreen fallback because a broken-but-invisible
 * fullscreen WebView would trap the host app (no way to scroll or tap through it).
 *
 * @param widgetId The widget id, typically the `id` from a [com.triggerbee.sdk.models.WidgetCheckResponse].
 * @param modifier Standard Compose modifier; the underlying `Box` fills its allotted bounds.
 *   Apply `Modifier.windowInsetsPadding(WindowInsets.safeDrawing)` here when you want the
 *   widget to respect system bars.
 * @param onClosed Invoked when the visitor closes the widget. The SDK has already recorded
 *   the dismissal via [Triggerbee.closeWidget] — this callback exists so the host can update
 *   its own UI state (e.g. hide the panel).
 * @param onNavigate Invoked when the visitor taps a button in the widget that has a URL.
 *   The host decides what to do (in-app routing, open in browser, etc.). Defaults to launching
 *   `Intent.ACTION_VIEW` which opens the URL in the device's default browser.
 */
@Composable
public fun TriggerbeeWidgetView(
    widgetId: Int,
    modifier: Modifier = Modifier,
    onClosed: (CloseReason?) -> Unit = {},
    onNavigate: (String) -> Unit = defaultOnNavigate(LocalContext.current),
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val url = remember(widgetId) { Triggerbee.widgetUrl(widgetId) }
    val webViewRef = remember { arrayOfNulls<WebView>(1) }
    val logger = remember { Triggerbee.logger() }

    var bounds by remember(widgetId) { mutableStateOf<Bounds?>(null) }
    var loadFailed by remember(widgetId) { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> webViewRef[0]?.onResume()
                Lifecycle.Event.ON_PAUSE -> webViewRef[0]?.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Timeout safeguard: if the widget engine never reports bounds (broken script, blocked
    // network), close the widget via the same path as an outright load failure. A fullscreen
    // fallback risks trapping the host app behind a possibly-invisible WebView with no way
    // to dismiss it.
    LaunchedEffect(widgetId) {
        delay(INITIAL_BOUNDS_TIMEOUT_MS)
        if (bounds == null && !loadFailed) {
            logger.warn("setBounds not received within ${INITIAL_BOUNDS_TIMEOUT_MS}ms; closing WebView")
            loadFailed = true
            onClosed(null)
        }
    }

    // Page load failed (4xx/5xx or network error on the main frame). Don't render anything —
    // the empty Box keeps Compose happy until the consumer reacts to onClosed and removes us.
    if (loadFailed) {
        Box(modifier = modifier.fillMaxSize())
        return
    }

    val targetAlpha = if (bounds == null) 0f else 1f
    val animatedAlpha by animateFloatAsState(targetAlpha, label = "TriggerbeeWidget.alpha")

    // animateDpAsState gives smooth transitions between state-change emitted bounds.
    // For first-bounds the change is from default → reported, which is fine.
    val animatedWidth by animateDpAsState((bounds?.width ?: 0).dp, label = "TriggerbeeWidget.width")
    val animatedHeight by animateDpAsState((bounds?.height ?: 0).dp, label = "TriggerbeeWidget.height")

    val effective = bounds
    // contentAlignment on the parent Box positions the AndroidView directly — more reliable
    // than putting Modifier.align(...) on the child, which the Box layout has to read via
    // ParentData and can be silently dropped when the child's size constraints fight the
    // alignment (e.g. animateDpAsState passing through 0.dp during the entry animation).
    val outerAlignment: Alignment = effective?.position?.toAlignment() ?: Alignment.Center
    Box(modifier = modifier.fillMaxSize(), contentAlignment = outerAlignment) {
        val webViewModifier: Modifier = when {
            effective == null || effective.fillScreen -> Modifier.fillMaxSize()
            effective.fillWidth -> Modifier
                .fillMaxWidth()
                .height(animatedHeight)
            else -> Modifier.size(animatedWidth, animatedHeight)
        }

        AndroidView(
            // WebView caches its previous measured height; requestLayout forces a re-measure on bounds change.
            update = { webView -> webView.requestLayout() },
            factory = { context ->
                createWebView(
                    context = context,
                    url = url,
                    webViewRef = webViewRef,
                    logger = logger,
                    onBoundsChanged = { newBounds ->
                        if (newBounds != bounds) bounds = newBounds
                    },
                    onLoadFailed = {
                        loadFailed = true
                        // Notify the consumer with no CloseReason — the widget was never
                        // shown, there's nothing to dismiss server-side (so no closeWidget
                        // call), but the host UI should still drop the openWidgetId state.
                        onClosed(null)
                    },
                    onUpdate = { reason ->
                        val parsed = parseCloseReason(reason)
                        // Always record the event locally — backend reads
                        // `closedWidgets[].reason` on next pageload to apply repetition rules.
                        Triggerbee.closeWidget(widgetId, parsed)
                        // Only tear down the WebView for actual close events. The widget engine
                        // fires `update` on three triggers: Closed (real close — Dismissal /
                        // ContinueShowing), AfterSubmit (Conversion — engine may transition to
                        // a success state, keep the WebView alive), and ButtonClicked
                        // (ClickThrough — button might or might not close; if it does, a
                        // separate Closed event with Dismissal fires after).
                        if (isClosingReason(reason)) {
                            onClosed(parsed)
                        }
                    },
                    onNavigate = onNavigate,
                )
            },
            modifier = webViewModifier.alpha(animatedAlpha),
        )
    }
}

internal data class Bounds(
    val width: Int,
    val height: Int,
    val position: WidgetPosition,
    val layout: WidgetLayout,
) {
    val fillScreen: Boolean
        get() = layout in fillScreenLayouts || position in fillScreenPositions

    val fillWidth: Boolean
        get() = !fillScreen && layout == WidgetLayout.Panel

    companion object {
        private val fillScreenLayouts = setOf(
            WidgetLayout.Fullscreen,
            WidgetLayout.Popup,
            WidgetLayout.Unknown,
        )
        // Positions that have no meaningful sub-screen anchor — treat as fullscreen.
        // `Embedded` / `Fullscreen` / `ProductTour` aren't sized callouts; `Center` would
        // be visually identical to fullscreen for overlay purposes.
        private val fillScreenPositions = setOf(
            WidgetPosition.Center,
            WidgetPosition.Embedded,
            WidgetPosition.Fullscreen,
            WidgetPosition.ProductTour,
            WidgetPosition.Unknown,
        )
    }
}

private fun WidgetPosition.toAlignment(): Alignment = when (this) {
    WidgetPosition.Top -> Alignment.TopCenter
    WidgetPosition.Bottom -> Alignment.BottomCenter
    WidgetPosition.Left -> Alignment.CenterStart
    WidgetPosition.Right -> Alignment.CenterEnd
    WidgetPosition.TopLeft -> Alignment.TopStart
    WidgetPosition.TopRight -> Alignment.TopEnd
    WidgetPosition.BottomLeft -> Alignment.BottomStart
    WidgetPosition.BottomRight -> Alignment.BottomEnd
    // Never reached at runtime — Bounds.fillScreen catches these — but kept exhaustive so
    // the `when` compiles without an `else` branch.
    WidgetPosition.Center,
    WidgetPosition.Embedded,
    WidgetPosition.Fullscreen,
    WidgetPosition.ProductTour,
    WidgetPosition.Unknown -> Alignment.Center
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    context: android.content.Context,
    url: String,
    webViewRef: Array<WebView?>,
    logger: TriggerbeeLogger,
    onBoundsChanged: (Bounds) -> Unit,
    onLoadFailed: () -> Unit,
    onUpdate: (String?) -> Unit,
    onNavigate: (String) -> Unit,
): WebView = WebView(context).apply {
    webViewRef[0] = this
    // Injected under `TriggerbeeEmbedBridge`; embed-app-service.ts forwards calls to this
    // on Android and to window.webkit.messageHandlers.triggerbee on iOS.
    addJavascriptInterface(TriggerbeeEmbedBridge(onUpdate, onBoundsChanged, onLoadFailed, onNavigate, logger), "TriggerbeeEmbedBridge")
    webChromeClient = TriggerbeeWebChromeClient(logger)
    webViewClient = TriggerbeeWebViewClient(logger, onLoadFailed)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    // Widget HTML is dynamic (per-visitor coupons / audience); never serve a stale copy.
    // LOAD_NO_CACHE handles this for *this* WebView. Do NOT call clearCache(true) — per the
    // Android docs the resource cache is per-application, so it would also wipe every other
    // WebView the host app uses.
    settings.cacheMode = WebSettings.LOAD_NO_CACHE
    // Transparent background so the widget controls its own appearance; the SDK's job is
    // sizing + positioning, not rendering a backdrop.
    setBackgroundColor(android.graphics.Color.TRANSPARENT)
    // Without explicit MATCH_PARENT layoutParams, WebView keeps its previous measure between modifier changes.
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    loadUrl(url)
}

private class TriggerbeeEmbedBridge(
    private val onUpdate: (String?) -> Unit,
    private val onBoundsChanged: (Bounds) -> Unit,
    private val onFailed: () -> Unit,
    private val onNavigate: (String) -> Unit,
    private val logger: TriggerbeeLogger,
) {
    @JavascriptInterface
    fun update(reason: String?) {
        mainHandler.post { onUpdate(reason) }
    }

    @JavascriptInterface
    fun setBounds(width: Int, height: Int, position: String?, layout: String?) {
        val parsed = Bounds(
            width = width,
            height = height,
            position = WidgetPosition.fromString(position),
            layout = WidgetLayout.fromString(layout),
        )
        logger.debug("widgetBounds: ${width}x$height @ ${position ?: "(no position)"}")
        mainHandler.post { onBoundsChanged(parsed) }
    }

    @JavascriptInterface
    fun failed(reason: String?) {
        logger.warn("Widget engine reported failure: ${reason ?: "(no reason)"}")
        mainHandler.post { onFailed() }
    }

    @JavascriptInterface
    fun navigate(url: String?) {
        if (url.isNullOrBlank()) {
            return
        }
        logger.debug("navigate: url=$url")
        mainHandler.post { onNavigate(url) }
    }

    // Visit-scoped "has this widget's `open` event already been logged?" queries — bridge to the
    // in-process set on Triggerbee. Web equivalent: sessionStorage `mtr_v.viewedWidgetIds`. Sync
    // return is safe; the backing set is a ConcurrentHashMap.
    @JavascriptInterface
    fun hasLoggedOpen(id: Int): Boolean = Triggerbee.hasLoggedOpen(id)

    @JavascriptInterface
    fun markOpenLogged(id: Int) = Triggerbee.markOpenLogged(id)

    /**
     * Legacy bridge call from the previous SDK version — kept so an older `mytracker.js` cached
     * client-side doesn't crash the bridge. Treated as a no-op; once every consumer is on the
     * `setBounds`-emitting engine release, this can be removed.
     */
    @Suppress("UNUSED_PARAMETER")
    @JavascriptInterface
    fun setHeight(height: Int) {
        // intentionally empty — superseded by setBounds
    }

    private companion object {
        val mainHandler = Handler(Looper.getMainLooper())
    }
}

private class TriggerbeeWebChromeClient(
    private val logger: TriggerbeeLogger,
) : WebChromeClient() {
    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        logger.debug("console: ${message.message()} — ${message.sourceId()}:${message.lineNumber()}")
        return true
    }
}

private class TriggerbeeWebViewClient(
    private val logger: TriggerbeeLogger,
    private val onLoadFailed: () -> Unit,
) : WebViewClient() {

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        view.evaluateJavascript(
            """
            (function() {
                try { location.reload = function() { console.log('[TB] reload suppressed'); }; } catch(e) {}
                window.onerror = function(msg, src, line, col, err) {
                    console.error('[onerror] ' + msg + ' @ ' + src + ':' + line);
                    return false;
                };
                window.addEventListener('unhandledrejection', function(e) {
                    console.error('[unhandledrejection] ' + e.reason);
                });
            })();
            """.trimIndent(),
            null,
        )
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
            logger.error("Main-frame error ${error.errorCode}: ${error.description} — ${request.url}")
            onLoadFailed()
        }
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        if (request.isForMainFrame) {
            logger.error("Main-frame HTTP ${errorResponse.statusCode} for ${request.url}")
            onLoadFailed()
        }
    }
}

private fun parseCloseReason(raw: String?): CloseReason? = when (raw) {
    "Dismissal" -> CloseReason.Dismissal
    "Conversion" -> CloseReason.Conversion
    "ContinueShowing" -> CloseReason.ContinueShowing
    else -> null
}

/**
 * `true` only for reasons that mean "the widget really closed", as opposed to "a tracking
 * event happened that the backend wants recorded". A conversion (form submitted) leaves the
 * widget alive — the engine may transition to a success state. A click-through fires for
 * *every* button click; if the click also closes the widget, an explicit `Closed` event with
 * `Dismissal` arrives afterwards and triggers the actual tear-down here.
 */
private fun isClosingReason(raw: String?): Boolean = raw == "Dismissal" || raw == "ContinueShowing"


/**
 * Default `onNavigate` for [TriggerbeeWidgetView]: opens the URL in the device default browser
 * via [Intent.ACTION_VIEW]. Suitable when widget buttons link to external pages. Hosts that
 * route in-app should pass their own lambda instead.
 *
 * Logs (rather than crashes) when no activity can handle the URL — e.g. a device with no
 * browser, or a custom scheme nothing claims. Other startActivity failures are propagated.
 */
private fun defaultOnNavigate(context: android.content.Context): (String) -> Unit = { url ->
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Triggerbee.logger().warn("defaultOnNavigate: no activity handles $url", e)
    }
}
