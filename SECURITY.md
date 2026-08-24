# Security Policy

## Reporting a Vulnerability

If you believe you've found a security issue in the Triggerbee Android SDK, please
**do not** open a public GitHub issue. Instead, email **support@triggerbee.com**
with:

- A description of the issue and the impact you observed
- Steps to reproduce (proof-of-concept code is welcome)
- The SDK version, host Android version, and any relevant configuration
- Your name / handle if you'd like credit in the release notes

We aim to acknowledge reports within **2 business days** and ship a fix or
mitigation within **30 days** for confirmed issues, depending on severity.

## Supported Versions

The **latest minor release** receives security fixes. When a new major version
ships, the previous major continues to receive them for 12 months.

| Version  | Supported |
|----------|-----------|
| 1.0.x    | ✓         |
| < 1.0.0  | ✗         |

## Scope

In scope:

- Code in this repository (`sdk/`)
- Build pipeline and published Maven Central artifacts
- Documentation that could mislead consumers into insecure usage

Out of scope (report to the relevant project instead):

- Vulnerabilities in upstream dependencies — please report to OkHttp, Retrofit,
  kotlinx.serialization, etc. directly.
- The Triggerbee backend / dashboard — email **support@triggerbee.com** for those
  as well, but note the SDK repo is not the right tracker.
