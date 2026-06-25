# Triggerbee SDK for Android

Native Android SDK for the Triggerbee platform. Wraps the public REST API,
generates and persists the visitor id locally, and exposes a small `suspend`-based surface
for pageload tracking, widget checks, identification, and goal logging.

> **Status:** 0.1.0 — pre-1.0. The public API may change in minor releases until 1.0.

## Integration checklist

A copy-paste checklist for getting Triggerbee live in your app. The next section has the
full example; this is the bird's-eye list.

**Prereqs from Triggerbee**
- [ ] Get your `siteId` (numeric) from your Triggerbee account manager
- [ ] Ask us to allow-list your app's `applicationId` (e.g. `com.acme.shop`) on your account

**Build setup** *(in `app/build.gradle.kts`)*
- [ ] `minSdk ≥ 24`, JDK 17 toolchain
- [ ] Add the dependency:
  ```kotlin
  implementation("com.triggerbee:triggerbee-android:0.1.0")
  ```
- [ ] Confirm `mavenCentral()` is in your `settings.gradle.kts` (default for new projects)

**Initialize once** *(in `Application.onCreate()`)*
- [ ] ```kotlin
  Triggerbee.init(this, TriggerbeeConfig(siteId = YOUR_SITE_ID))
  Triggerbee.start()   // safe to call every launch — only mints the UID first time
  ```

**Track screens**
- [ ] On each screen view: `Triggerbee.pageload(page = "/products/42", title = "Product")`
- [ ] Optional: `Triggerbee.recheck(page, secondsOnPage)` after a delay for time-based campaigns

**Identify & convert** *(when applicable)*
- [ ] On login / known visitor: `Triggerbee.identify(identifier = "user@acme.com")`
- [ ] On purchase: `Triggerbee.logPurchase(revenue = "299.00")`
- [ ] Custom goals: `Triggerbee.logGoal(name = "newsletter_signup")`

**Render widgets** *(when a `pageload` result has campaigns)*
- [ ] Compose: drop `TriggerbeeWidgetView(widgetId = hit.id)` into your screen
- [ ] Non-Compose: load `Triggerbee.widgetUrl(hit.id)` into a `WebView`

**Verify**
- [ ] Build + run, perform the steps that should trigger a campaign
- [ ] In the Triggerbee dashboard, confirm the visit + events appear under your `siteId`

## Full quick start

```kotlin
// Application.onCreate
Triggerbee.init(
    context = this,
    config = TriggerbeeConfig(
        siteId = YOUR_SITE_ID,
        baseUrl = "https://api.triggerbee.com",
    ),
)

// Anywhere later (any CoroutineScope)
Triggerbee.start()                                // mints + persists the uid on first launch
val uid = Triggerbee.sessionContext.value.uid     // read back from the StateFlow

val widgets = Triggerbee.pageload(page = "/profile", title = "Profile")
widgets.firstOrNull { it.result }?.let { hit ->
    // In Compose — bundled WebView + bridge + lifecycle + close-recording
    TriggerbeeWidgetView(widgetId = hit.id, onClosed = { /* hide panel */ })

    // Or, if you'd rather build your own WebView
    webView.loadUrl(Triggerbee.widgetUrl(hit.id))
}
```

## What's in the box

| Method | Purpose |
|---|---|
| `Triggerbee.init(context, config)` | Configure once (typically in `Application.onCreate`). |
| `Triggerbee.start()` | Mint a 64-bit visitor id on first launch; reuse it forever after. Client-side `SecureRandom`, no network. Read the uid from `sessionContext.value.uid`. |
| `Triggerbee.pageload(page, title, secondsOnPage)` | Log a pageview + get the widgets eligible to show on this page. |
| `Triggerbee.recheck(page, secondsOnPage)` | Re-evaluate widgets without logging a new pageview (use after waiting out an `openDelay`). |
| `Triggerbee.pageview(page, title)` | Log a pageview without running the widget check. Use when you only need analytics, not widgets. |
| `Triggerbee.closeWidget(widgetId, reason)` | Tell the SDK the visitor closed a widget; replayed to the backend on the next pageload. |
| `Triggerbee.identify(identifier, properties)` | Attach an external identifier (typically email) + custom properties. |
| `Triggerbee.logGoal(name, revenue)` | Log a goal completion. The name is also persisted locally and replayed on the next pageload for realtime audience matching. |
| `Triggerbee.logPurchase(revenue, couponCode)` | Log a purchase. Both args optional. Revenue is a string so the caller controls formatting. |
| `Triggerbee.setLandingPageQueryParams(params)` | Persist campaign-attribution params from a deep link / push-notification URL for audience rules to match against. |
| `Triggerbee.logEvents(events)` | Log one or more free-form tracking events in a single request. |
| `Triggerbee.batch(pageviews, goals, purchases, identify)` | Flush a mixed batch in one round-trip. SDK never calls this itself — exposed for consumer-side queuing. |
| `Triggerbee.widgetUrl(widgetId): String` | Build the widget HTML URL — feed straight into `WebView.loadUrl`. |
| `TriggerbeeWidgetView(widgetId, ...)` | Drop-in Compose Composable: WebView + JS bridge + lifecycle + auto `closeWidget` recording. |
| `Triggerbee.sessionContext: StateFlow<SessionContext>` | Hot stream of session state. |
| `Triggerbee.disable()` / `enable()` / `isDisabled` | Visitor opt-out kill-switch. When disabled, every SDK call is a silent no-op. See [PRIVACY.md](PRIVACY.md). |

## Build & test

```powershell
./gradlew :sdk:assembleRelease
./gradlew :sdk:test
```

## License

Apache 2.0
