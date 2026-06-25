# Changelog

All notable changes to the Triggerbee Android SDK are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0]

First public release. Native Android SDK for the Triggerbee platform.

### Features

- **Configure once, use anywhere** — `Triggerbee.init(context, TriggerbeeConfig(siteId = …))`
  initialises the singleton; the rest of the API is reachable as top-level calls.
- **Visitor identifier** — `Triggerbee.start()` mints a `SecureRandom`-derived 64-bit `uid` on
  first launch and persists it in DataStore. Generated client-side, works offline, never
  round-trips through the backend.
- **Page load + widget check** — `Triggerbee.pageload(page, title, secondsOnPage)` logs the
  pageview *and* returns the widgets eligible for this visit in one round-trip.
- **Re-check** — `Triggerbee.recheck(page, secondsOnPage)` re-evaluates widget eligibility
  on the same page without logging a new pageview.
- **Visitor events** — `identify`, `logGoal`, `logPurchase`, `pageview`, plus a `batch` call
  to fold several events into a single request.
- **Widget rendering** — `Triggerbee.widgetUrl(widgetId)` builds the URL ready to feed into a
  `WebView.loadUrl(...)`; `TriggerbeeWidgetView` is a drop-in Compose composable that hosts
  the WebView, handles the JS bridge (bounds, navigation, close), and reports session state
  via a `StateFlow`.
- **Device snapshot** — every event sends `DeviceInfo` (platform, OS, model, manufacturer,
  screen size, locale, timezone, SDK version) so audience rules can match without inferring
  from a User-Agent.
- **Visitor opt-out** — `Triggerbee.disable()` / `enable()` is a kill-switch; while disabled
  every public method is a silent no-op and no network requests are made. See
  [PRIVACY.md](PRIVACY.md).
- **Pluggable logger** — `TriggerbeeLogger` interface; default `NoOpLogger` ships zero log
  output in production. Pass `AndroidLogger()` to forward to logcat under tag `Triggerbee`.
- **Structured errors** — sealed `TriggerbeeException` (`NetworkError`, `HttpError(code,
  body)`, `SerializationError`, `NotInitialized`); no silent catch.

### Requirements

- `minSdk = 24` (Android 7.0, ≈99% device coverage)
- `compileSdk = 35`, JVM target 17
- Kotlin 2.x

### Coordinates

```kotlin
implementation("com.triggerbee:triggerbee-android:0.1.0")
```
