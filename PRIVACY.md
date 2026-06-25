# Privacy

The Triggerbee Android SDK is a thin HTTP client that sends data from your app to
Triggerbee's backend. This document is the canonical list of what the SDK collects,
where it sends it, and how a host app can give visitors a kill-switch.

## What the SDK collects

### Visitor identifier (`uid`)
- A 64-bit positive integer minted by `Triggerbee.start()` using `SecureRandom` on first
  launch and persisted in the app's DataStore. Reused on every subsequent launch.
- The value is generated **client-side**, never round-tripped through the backend, so the
  SDK works offline on first launch.
- Not derived from any device identifier (ANDROID_ID, advertising id, IMEI, etc. are
  never read) and carries no cross-app correlation.

### Caller-supplied event payloads
The host app explicitly passes everything in these calls; the SDK does not read app state
or page contents on its own.

- `pageload(page, title, secondsOnPage)` / `pageview(page, title)` — string path + title.
- `logGoal(name, revenue?)` — goal name + optional revenue string.
- `logPurchase(revenue?, couponCode?)` — both optional.
- `identify(identifier, properties?)` — identifier (typically email or phone) + optional
  flat `Map<String, String>` of custom properties.
- `logEvents([Event(type, title)])` — free-form analytics events.
- `setLandingPageQueryParams(["utm_source=…", …])` — campaign-attribution params.

### Device snapshot (`DeviceInfo`)
Built once at `Triggerbee.init(…)` from `android.os.Build`, `java.util.Locale`, and the
host app's `PackageInfo`. No runtime permission is required for any of these.

| Field | Source | Example |
|---|---|---|
| `platform` | constant | `"android"` |
| `type` | `Configuration.smallestScreenWidthDp` (`<600` → mobile) | `"mobile"` |
| `osVersion` | `Build.VERSION.RELEASE` | `"13"` |
| `osApiLevel` | `Build.VERSION.SDK_INT` | `33` |
| `sdkVersion` | `BuildConfig.SDK_VERSION` | `"0.2.0"` |
| `manufacturer` | `Build.MANUFACTURER` | `"samsung"` |
| `model` | `Build.MODEL` | `"SM-S908B"` |
| `appVersion` | host app's `PackageInfo.versionName` | `"1.4.2"` |
| `locale` | `Locale.getDefault().toLanguageTag()` | `"en-US"` |
| `timeZone` | `TimeZone.getDefault().getID()` | `"Europe/Stockholm"` |
| `screenWidth` / `screenHeight` | `DisplayMetrics` in physical pixels | `1080` / `2400` |

### Server-observed metadata
The backend additionally records the request's IP address (read from `X-Forwarded-For` /
`RemoteIpAddress`) for rate-limiting, geo-resolution, and fraud detection. This is observed
by Triggerbee's edge, not collected by the SDK itself.

## What the SDK does NOT collect

- Device identifiers: `ANDROID_ID`, advertising id, IMEI, MAC address, etc.
- Location (no `ACCESS_*_LOCATION` permission requested).
- Contacts, calendar, photos, microphone, camera, or any other dangerous-permission scope.
- Hardware sensors, accessibility events, foreground app list, installed-app inventory.
- Any data the host app has not explicitly passed to a Triggerbee call.

## What the SDK persists locally

The SDK writes to its own DataStore under the host app's data directory. Cleared when the
user uninstalls the app or clears its data.

- The visitor `uid`.
- The identifier set via `identify()`.
- The list of widgets the visitor has closed (`closedWidgets`).
- Audience state: goals logged this visit, landing-page query params.

## Where data goes

All HTTP requests target the `baseUrl` configured in `TriggerbeeConfig`. For Triggerbee
production deployments this is `https://api.triggerbee.com`.

## Visitor opt-out

Call `Triggerbee.disable()` to silence every subsequent SDK call for the rest of the
process lifetime. All public methods become no-ops; no network requests are made.

```kotlin
// In your consent flow, when the user declines tracking:
Triggerbee.disable()
```

`Triggerbee.enable()` reverses it. The flag is in-memory, so apps that want it to persist
across launches should set it themselves on every cold start based on the user's stored
consent choice.

`disable()` does **not** clear persisted SDK state — it leaves the `uid` and identifier
in place so the same session can resume after `enable()`. For a hard erasure use Android's
"Clear app data" or invoke Triggerbee's backend GDPR-deletion endpoint with the visitor's
identifier.

## Contact

Questions or data-subject requests: <support@triggerbee.com>.
