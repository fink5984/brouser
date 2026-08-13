# Security review

Reviewed against the checklist this project was built to: Android security, API authentication, proxy authentication, SSRF, open-proxy exposure, secret management, TLS, injection classes, rate limiting, broken access control.

## Android

| Item | Status | Notes |
|---|---|---|
| WebView debugging | ✅ | `WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)` — off in release (`isDebuggable` defaults to `false` for the `release` build type; only `debug`/`staging` enable it). |
| `file://` restrictions | ✅ | `allowFileAccess`, `allowContentAccess`, `allowFileAccessFromFileURLs`, `allowUniversalAccessFromFileURLs` all `false`. Uploads go through the system file picker (`Intent.createChooser`), not WebView file access. |
| Mixed content | ✅ | `MIXED_CONTENT_NEVER_ALLOW`. |
| Safe Browsing | ✅ | Enabled via `WebSettingsCompat.setSafeBrowsingEnabled` when the WebView feature is available. |
| JavaScript interfaces | ✅ | None added. No `addJavascriptInterface` calls anywhere in the codebase. |
| Intent/URL validation | ✅ | `ExternalIntentResolver` only handles a fixed scheme allowlist (`tel`, `mailto`, `sms`, `smsto`, `geo`, `market`) plus safely-parsed `intent:` (requires `ACTION_VIEW`, catches parse exceptions). Every resolved `Intent` has `component`/`package` explicitly cleared before `resolveActivity` is checked, so a malicious page can't target an internal/exported component directly. User confirmation is required before launch (`BrowserScreen`'s "Open in another app?" dialog) — nothing launches automatically. |
| Certificate validation | ✅ | `onReceivedSslError` always calls `handler.cancel()`. There is no code path that calls `.proceed()` on an SSL error, anywhere. |
| Secret storage | ✅ | Proxy username/password stored in `EncryptedSharedPreferences` (AES-256-GCM, Android Keystore-backed `MasterKey`), not plain `SharedPreferences`/DataStore. Browser settings (homepage, etc. — not secret) use plain DataStore. |
| Backups | ✅ | `android:allowBackup="false"` — the encrypted credential store and Room databases (history/bookmarks) aren't included in Auto Backup, avoiding a bypass of the Keystore binding via a restored backup on a different device. |

## API authentication (backend)

- Every device-facing endpoint that returns sensitive data (`GET /device/config`) requires `Authorization: Bearer <DEVICE_TOKEN>`, checked with a constant-time comparison (`timingSafeEqual`, SHA-256-then-`crypto.timingSafeEqual` so comparison time doesn't depend on input length either).
- `GET /api/v1/app/version` is intentionally public — it carries no sensitive data (just version numbers).
- The internal proxy-auth endpoint (`POST /internal/v1/proxy/authenticate`) requires a separate shared secret (`X-Internal-Secret` header, also constant-time compared) **in addition to** network isolation (it's only registered under `/internal/v1`, and in the Docker/Railway deployments that path is never routed through the public reverse proxy). Two independent controls, not one.
- No JWTs, sessions, or cookies exist in this system at all — there's one device, one static bearer token. This eliminates an entire class of session-fixation/CSRF concerns by construction (there's no session to fixate or forge).

## Proxy authentication / open-proxy exposure

- Squid: `acl authenticated proxy_auth REQUIRED` + `http_access deny !authenticated` before the final `allow`. Every request must present valid Basic-Auth credentials.
- The auth helper (`proxy/auth-helper/proxy-auth-helper.py`) **fails closed**: any exception, timeout, missing `INTERNAL_API_SECRET`, non-200 response, or unparseable body from the backend results in rejecting the login (`return False` / `ERR`). A backend outage makes the proxy reject everyone, not accept everyone.
- Squid's `authenticate_ttl` (5 minutes) caches successful auth so the backend isn't hit on every single request, without ever caching a *rejection* longer than the same window (rejections are simply not cached as "OK").
- No static htpasswd file — credentials live in one place (backend env vars), so rotating `PROXY_PASSWORD` takes effect for every subsequent auth check without touching the proxy container.

## SSRF

`proxy/squid.conf` denies, before any allow rule is reached:
- `127.0.0.0/8`, `::1/128` (loopback)
- `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16` (RFC1918 private ranges)
- `169.254.0.0/16` including the explicit `169.254.169.254/32` line (cloud metadata endpoint)
- `fc00::/7` (IPv6 ULA), `fe80::/10` (IPv6 link-local)
- `0.0.0.0/8`

Squid's `acl ... dst` type resolves the destination hostname via DNS *before* matching, which is what makes this effective against DNS-rebinding (a hostname that resolves to a public IP at ACL-check time but a private IP at connect time, or vice versa within TTL) rather than only against literal IP addresses in the URL.

**Verified manually** (see [testing.md](testing.md)): `curl` through the proxy to `169.254.169.254` and to `127.0.0.1` both return a Squid denial, not a proxied response.

## Secret management

- `.env` files are gitignored at every level (`backend/.gitignore`, root `.gitignore` if added, `android/.gitignore` for `local.properties`).
- `.env.example` files contain empty/placeholder values only, never real secrets.
- Backend logger redacts `authorization`, `cookie`, `password`, `proxyPassword`, `deviceToken`, `token` fields from all log output (`pino` `redact` config in `lib/logger.ts`).
- Structured JSON logs include a `requestId` per request (Fastify's built-in `genReqId`, honoring an inbound `x-request-id` header) without ever logging the credential values themselves.
- **Accepted limitation**: `DEVICE_TOKEN` is embedded in the compiled APK (`BuildConfig.DEVICE_TOKEN`). This is unavoidable for any app that authenticates to its own backend without a login screen, and is explicitly called out in the README. Mitigation if the APK is believed to have leaked: rotate `DEVICE_TOKEN` and `PROXY_PASSWORD` on the backend and rebuild/redistribute.

## TLS

- App ↔ backend: plain HTTPS, validated by the OS trust store (`network_security_config.xml` sets `cleartextTrafficPermitted="false"` at the base level; the only cleartext exception is `10.0.2.2`/`localhost`, scoped for local emulator development and never matching a real production domain).
- App ↔ proxy: `androidx.webkit.ProxyConfig` `"https"` scheme opens a TLS connection to Squid's `https_port` before speaking the proxy protocol inside it — protects the Basic-Auth credentials in transit. Certificate is either a real one (production, see deployment docs) or a container-generated self-signed dev cert (explicitly documented as dev-only).
- App ↔ destination site: standard WebView TLS validation, never bypassed (see the Android table above).
- No TLS interception anywhere in the chain (`ssl_bump none all` in Squid), so none of the above layers are weakened by a fourth party inspecting them.

## Injection classes

- **SQL injection**: not applicable — there is no database in this system.
- **XSS**: not applicable to the backend — it's a pure JSON API with no HTML rendering. On the Android side, WebView renders whatever the destination site serves, which is the expected/correct behavior for a browser; the app itself injects no user-controlled strings into any WebView-loaded HTML.
- **CSRF**: not applicable — no cookie-based session exists anywhere in this system (Bearer-token auth only, which isn't automatically attached by a browser the way a cookie is).

## Rate limiting

- Global rate limit on the backend via `@fastify/rate-limit` (`RATE_LIMIT_MAX`/`RATE_LIMIT_WINDOW`, defaults 60/min).
- The internal proxy-auth endpoint has its own higher limit (600/min) appropriate for Squid's `authenticate_ttl` cache-miss frequency, still bounded.
- Squid itself limits simultaneous connections per client (`acl per_client_conn_limit maxconn 32`) as a basic abuse guard independent of the backend.

## Broken access control

- Single credential class by design (one device, one token) — there's no privilege hierarchy to get wrong. There is no admin role, no multi-tenant boundary, and no admin panel in this deployment, which removes the most common source of broken-access-control bugs (authorization checks that differ between endpoints) by not having that surface at all.
