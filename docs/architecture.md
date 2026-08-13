# Architecture

## Goals and non-goals

The app must feel like a normal local browser — pages render natively in a real WebView, not a remote screen or an embedded iframe. The only thing that's different from a stock browser is *where the WebView's network requests go*: through a proxy the owner controls, instead of straight to the internet.

Explicitly out of scope, by design:
- No remote desktop / screenshot streaming / remote Chromium.
- No TLS interception (no SSL Bump, no custom root CA, no certificate injection). The proxy never sees plaintext HTTPS content.
- No system-wide proxy or VPN configuration changes. `androidx.webkit.ProxyController` scopes the override to this app's WebView instances only.
- No fallback to a direct internet connection if the proxy is unavailable.

## Request flow

```
WebView request
   -> androidx.webkit.ProxyController override (app-process only)
   -> Squid (3129, TLS-secured proxy connection; Basic-Auth required)
       -> HTTP: Squid forwards the request and relays the response.
       -> HTTPS: Squid opens a CONNECT tunnel to the destination and
                 relays raw bytes. TLS is negotiated directly between
                 WebView and the destination server -- Squid cannot read
                 or modify it.
   -> Destination website
```

Two separate TLS layers exist and must not be confused:
1. **App ↔ Proxy**: TLS on the *proxy connection itself* (Squid's `https_port`). This protects the Basic-Auth credentials in transit. Configured via the `scheme: "https"` the backend returns in `/device/config`, which the app turns into `androidx.webkit.ProxyConfig.Builder().addProxyRule("https://host:port")`.
2. **App ↔ Destination site**: ordinary HTTPS, exactly as if there were no proxy at all. This is the connection WebView's own certificate validation applies to, and it's why `onReceivedSslError` always calls `handler.cancel()` — a bad cert here means the *destination* is untrustworthy, unrelated to the proxy.

## Why not TLS-intercept (SSL Bump)?

The spec for this project explicitly forbids it, and it would also defeat the purpose: a browser whose proxy operator can read and modify every page's plaintext content is not meaningfully different from a MITM device, and would break certificate pinning, HSTS, and any site the owner cares about interacting with normally (banking, email, etc.). If a future requirement genuinely needs content inspection, the documented escalation path is: try a standard proxy-level control first (SSRF ACL, domain allow/deny list, connection limits), and only consider `ssl_bump` with a real internal CA if that's insufficient -- and even then, it changes the trust model enough that it deserves its own explicit design/approval, not a silent addition.

## "Fail closed" — how it's actually enforced

The requirement isn't just "show an error if the proxy is down" — it's "never send WebView traffic anywhere except the proxy." Two independent mechanisms combine to guarantee this:

1. **No bypass rules.** `ProxyManager` calls `ProxyController.setProxyOverride()` with zero `addBypassRule()` entries. WebView has no configured path around the proxy for any host, including `localhost`-looking ones.
2. **A real connectivity probe before any page loads.** `ProxyManager.applyAndVerify()` doesn't just set the override and hope — it opens a raw socket to the proxy, performs the exact `CONNECT www.google.com:443` + `Proxy-Authorization: Basic ...` handshake WebView itself will use, and only flips the app's state to `ProxyStatus.Ready` on a `200` response. Anything else (`407`, connection refused, timeout) puts the app in `ProxyStatus.Unavailable`, which renders a full-screen blocking message with a Retry button — there is no code path from that screen to loading a page.

This means a misconfigured or dead proxy fails *visibly and immediately* at app startup, rather than silently leaking the first few requests before the user notices.

## Squid configuration highlights

See [`proxy/squid.conf`](../proxy/squid.conf) for the full annotated config. Key points:

- `auth_param basic program /usr/local/bin/proxy-auth-helper.py` — a small Python helper (Squid's standard NCSA-style basic-auth helper protocol) that validates credentials against the backend's `/internal/v1/proxy/authenticate` instead of a static htpasswd file, so credentials can be rotated by editing one env var on the backend.
- `acl blocked_dst_ipv4 dst 10.0.0.0/8` (and the `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`, `127.0.0.0/8`, IPv6 equivalents) — SSRF protection. Squid's `dst` ACL type resolves the hostname *before* matching, which also blocks DNS-rebinding attacks (a public-looking hostname that resolves to a private IP).
- `acl metadata_endpoint dst 169.254.169.254/32` — explicit extra line for the cloud metadata endpoint, since it's the single highest-value SSRF target.
- `ssl_bump none all` — belt-and-suspenders: even if someone added an `https_port` intercept rule by mistake later, this line keeps interception off.
- `cache deny all` — no caching of potentially private response bodies.
- `http_port 3128` (internal-only) + `https_port 3129` (published) — see [deployment docs](deployment-docker.md) for the certificate story.

## Backend

Deliberately has no database. It holds exactly the state that needs to be changeable without rebuilding the APK:

- `GET /api/v1/device/config` (Bearer `DEVICE_TOKEN`) → proxy host/port/scheme/credentials + browser defaults (homepage, search engine, max tabs, downloads enabled).
- `GET /api/v1/app/version` (public) → `{ latestVersion, minimumVersion }`, for a future "please update" banner. No auto-update mechanism is implemented or planned -- that would bypass Android's own install security model.
- `POST /internal/v1/proxy/authenticate` (internal network + shared secret only) → validates Squid's Basic-Auth credentials against the static `PROXY_USERNAME`/`PROXY_PASSWORD` env vars.
- `GET /api/v1/health` → liveness check.

All four routes are implemented with static/env-var comparisons (`timingSafeEqual`), not a database lookup — there's exactly one device, so there's exactly one credential to check against.

## Android app structure

Everything lives in one flat Kotlin package (`com.alphainventor.filemanager`) per project convention. Major pieces:

- `BrowserWebView` — owns exactly one configured `WebView` (settings, `WebViewClient`, `WebChromeClient`, download listener) for one tab.
- `TabManager` — owns the list of tabs and lazily creates/evicts `BrowserWebView` instances, capped at `maxLiveWebViews` (default 4) so a session with many tabs open doesn't accumulate unbounded native WebView memory. Eviction tears down the WebView; revisiting the tab creates a fresh one and reloads its last URL.
- `ProxyManager` — the fail-closed logic described above.
- `BrowserViewModel` — orchestrates config fetch, proxy verification, tab events, history/bookmarks (Room), and screen navigation. Holds no `Activity`/`Context` reference beyond `applicationContext`.
- `BrowserActivityBridge` — everything that genuinely needs an `Activity` (file chooser, permission prompts, launching external intents, fullscreen video) is implemented by `MainActivity` and attached to the ViewModel after creation, so the ViewModel itself never leaks an Activity reference.
- `ProxyAwareDownloader` — downloads are **not** routed through Android's system `DownloadManager`, because that service runs outside the app's process and has no knowledge of the WebView-scoped proxy override — using it would silently bypass the proxy. Instead, downloads go through a manual `HttpURLConnection` configured with the same `java.net.Proxy` and credentials, streaming into `MediaStore.Downloads` (scoped storage, API 29+) or the legacy public Downloads directory (API 26-28).

## Known limitations (and why)

Per the project's engineering-decision policy: document the constraint, explain why, and only escalate architecture if a standard fix genuinely isn't available.

- **WebView state doesn't survive process death.** Full `WebView.saveState()`/`restoreState()` byte persistence across an app process being killed (not just backgrounded) would need Parcel-to-disk marshaling per tab. Given the personal single-device scope, tabs restore by URL/title (via Room) on relaunch, not full navigation history. This is a straightforward addition if ever needed — it doesn't require any architecture change.
- **Website HTTP Basic-Auth isn't auto-handled.** `onReceivedHttpAuthRequest` auto-supplies credentials only when the challenging host matches the configured proxy; for an actual destination site using HTTP Basic Auth (rare in 2026; most sites use cookie/OAuth sessions), the handler currently cancels rather than prompting. A login dialog is a contained addition to `BrowserWebView.InnerWebViewClient` if a real site needs it.
- **No in-page camera/mic (`getUserMedia`).** `onPermissionRequest` denies by default. File uploads still get camera access via the system camera app (a separate, more contained permission grant).
