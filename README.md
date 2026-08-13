# File Manager (managed Android browser)

A personal, single-device Android browser whose entire web traffic is routed through a proxy server you control. The app looks and works like an ordinary browser; behind the scenes every request goes through your Squid proxy, and if the proxy is unreachable, the app blocks browsing instead of falling back to a direct connection.

Built for exactly one device/owner. There is no database, no fleet management, and no admin panel — configuration lives in environment variables on the backend, editable without rebuilding the app.

## Architecture

```
Android App (WebView)
   |  androidx.webkit.ProxyController (app-scoped, not system-wide)
   v
Squid (auth required, SSRF-blocked, CONNECT tunnel only)
   |
   v
Destination website  (TLS is end-to-end between WebView and the site --
                       Squid only relays the encrypted CONNECT tunnel)

Android App --(HTTPS, Bearer DEVICE_TOKEN)--> Backend (Fastify)
                                                  |
                                                  v
                                        env vars: proxy host/port/
                                        credentials, homepage,
                                        search engine, max tabs...

Squid's auth helper --(internal network, shared secret)--> Backend
                                        validates PROXY_USERNAME/PASSWORD
```

Three independent pieces:

| Component | What it is | Where |
|---|---|---|
| `android/` | Kotlin/Jetpack Compose browser app | `androidx.webkit.ProxyController` sets the proxy for WebView only, never touches system-wide settings |
| `backend/` | Fastify + TypeScript, no database | Serves `/device/config` (proxy info + browser defaults) and validates Squid's proxy credentials |
| `proxy/` | Squid forward proxy | Basic-auth required, SSRF-blocked, no TLS interception |
| `docker/` | Docker Compose stack | Self-hosted deployment: backend + Squid + Caddy |

See [docs/architecture.md](docs/architecture.md) for the full design rationale, including why there's no TLS interception and how "fail closed" is actually enforced (not just documented).

## Quickstart (local development)

Prerequisites: Node.js 20+, a Squid install (native, WSL, or Docker), JDK 17, Android SDK.

### 1. Backend

```bash
cd backend
cp .env.example .env
# generate secrets:
#   openssl rand -base64 32   (for DEVICE_TOKEN, INTERNAL_API_SECRET)
#   openssl rand -base64 18   (for PROXY_PASSWORD)
# fill in .env with them, then:
npm install
npm run dev            # http://localhost:4000
npm test                # unit + API tests, no external services needed
```

### 2. Proxy (Squid)

The backend's proxy-auth endpoint (`POST /internal/v1/proxy/authenticate`) must be reachable from wherever Squid runs. For local development, run Squid directly (see [docs/deployment-docker.md](docs/deployment-docker.md#local-testing-without-docker) if Docker isn't available on your machine):

```bash
# from proxy/, using system squid + the auth helper
squid -N -f squid.conf   # after pointing squid.conf at your local paths
```

Set these env vars for the auth helper process:
```
BACKEND_INTERNAL_URL=http://127.0.0.1:4000/internal/v1/proxy/authenticate
INTERNAL_API_SECRET=<same value as backend's .env>
```

Verify manually:
```bash
curl -x http://device:<PROXY_PASSWORD>@127.0.0.1:3128 http://example.com -I
curl -x http://device:<PROXY_PASSWORD>@127.0.0.1:3128 https://example.com -I   # CONNECT tunnel
curl -x http://device:wrong-password@127.0.0.1:3128 http://example.com -I     # expect 407
curl -x http://device:<PROXY_PASSWORD>@127.0.0.1:3128 http://169.254.169.254/ -I  # expect blocked
```

### 3. Android app

```bash
cd android
cp local.properties.example local.properties
# fill in sdk.dir, CONFIG_BASE_URL (http://10.0.2.2:4000 for emulator), DEVICE_TOKEN
./gradlew :app:testDebugUnitTest     # unit tests
./gradlew :app:assembleDebug         # builds app/build/outputs/apk/debug/app-debug.apk
```

Install on an emulator or device (`adb install app/build/outputs/apk/debug/app-debug.apk`), or run from Android Studio.

## Production deployment

Two supported paths — pick one:

- **[docs/deployment-railway.md](docs/deployment-railway.md)** — recommended for a single personal device. Railway hosts the backend and exposes Squid via its TCP proxy feature; no server admin required.
- **[docs/deployment-docker.md](docs/deployment-docker.md)** — self-hosted on any VPS with Docker Compose + Caddy for automatic HTTPS.

Either way, the last step is always: put the real `PROXY_HOST`/`PROXY_PORT`/`DEVICE_TOKEN` into `android/local.properties`, build a release APK, and install it.

## Testing

See [docs/testing.md](docs/testing.md) for the full test plan, including the manual browser-compatibility checklist (YouTube, Google search, OAuth login, WebSocket, file upload/download, popups, etc.).

Quick reference:
```bash
cd backend && npm test                       # 15 tests: config auth, proxy-auth endpoint, health
cd android && ./gradlew :app:testDebugUnitTest # URL parsing, search engine logic
```

## Security

See [docs/security-review.md](docs/security-review.md) for the full review. Highlights:

- Squid requires authentication; it is not an open proxy.
- SSRF-blocked: loopback, private ranges (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), link-local (`169.254.0.0/16`, covering the cloud metadata endpoint), and IPv6 equivalents are all denied at the proxy.
- No TLS interception, ever: HTTPS is a CONNECT tunnel end-to-end. WebView validates the destination's certificate itself; a certificate error always blocks the page (`handler.cancel()`, never `proceed()`).
- Fail-closed: the app runs an actual CONNECT+Basic-Auth probe against the proxy before allowing any page to load. If it fails, the app shows a blocking "can't connect to the browsing service" screen — there is no direct-connection fallback anywhere in the code.
- No JavaScript bridges (`addJavascriptInterface`) are added to the WebView.
- Proxy password at rest on-device: `EncryptedSharedPreferences` (AES-256-GCM via Android Keystore), never plain `SharedPreferences`/DataStore.
- **Known limitation**: `DEVICE_TOKEN` is compiled into the APK (`BuildConfig`), like any client-embedded API key. Anyone who decompiles the APK can extract it and call `/device/config` to obtain the live proxy credentials. This is an accepted trade-off for a personal single-device app; if you suspect the APK has leaked, rotate `DEVICE_TOKEN`/`PROXY_PASSWORD` on the backend and rebuild.

## Project layout

```
android/    Kotlin/Compose browser app
backend/    Fastify + TypeScript config/auth service (no database)
proxy/      Squid config, Dockerfile, Python auth helper
docker/     docker-compose.yml + Caddyfile (self-hosted deployment)
docs/       architecture, deployment, testing, security docs
.env.example  Root-level env template for the Docker Compose stack
```

## Status / limitations

- Tab state (scroll position, form field contents, back/forward history) does not survive a full app process death — only the tab's URL and title do. Switching tabs within a live session keeps full state.
- Website HTTP Basic-Auth login prompts aren't shown as a dialog -- the app always answers auth challenges with the proxy's credentials (since virtually all of them are the proxy's own), so a genuine destination-site Basic-Auth prompt (rare in 2026) would just fail rather than ask the user. OAuth/cookie-based logins work normally since they don't use HTTP Basic Auth.
- In-page camera/microphone access (`getUserMedia`) is denied by default; taking a photo for a `<input type=file>` upload still works via the system camera app.
- The proxy's public TLS port (3129) ships with a self-signed certificate generated on first container start; replace it with a real certificate for any deployment reachable over an untrusted network (see [docs/deployment-docker.md](docs/deployment-docker.md)).
