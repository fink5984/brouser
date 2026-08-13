# Testing

## Backend

```bash
cd backend
npm test
```

15 tests across two files, no external services required (no database exists in this system):

- `tests/crypto.test.ts` — `timingSafeEqual` correctness (equal strings, differing strings, differing lengths, empty string).
- `tests/api.test.ts` — full HTTP surface via `fastify.inject`:
  - `GET /api/v1/health`
  - `GET /api/v1/device/config` — missing token (401), wrong token (401), correct token (200, correct body shape)
  - `GET /api/v1/app/version` — public, correct shape
  - `POST /internal/v1/proxy/authenticate` — missing internal secret (401), wrong internal secret (401), wrong proxy credentials (200, `{ok:false}`), correct credentials (200, `{ok:true}`), malformed payload (400)
  - Unknown route → 404 with JSON body

Also run before every change lands:
```bash
npm run typecheck
npm run build
```

## Proxy (manual, against a running Squid)

```bash
# HTTP through the proxy
curl -x http://device:<PROXY_PASSWORD>@127.0.0.1:3128 http://example.com -I
# expect: 200 from example.com (via Squid, check `Via` header)

# HTTPS CONNECT tunnel
curl -x http://device:<PROXY_PASSWORD>@127.0.0.1:3128 https://example.com -I
# expect: 200, and note curl negotiated TLS itself (proxy only relayed bytes)

# Wrong password
curl -x http://device:wrong@127.0.0.1:3128 http://example.com -I
# expect: 407 Proxy Authentication Required

# Disabled/wrong username entirely
curl -x http://nobody:whatever@127.0.0.1:3128 http://example.com -I
# expect: 407

# SSRF: cloud metadata endpoint
curl -x http://device:<PROXY_PASSWORD>@127.0.0.1:3128 http://169.254.169.254/ -I
# expect: Squid denial (403/409-style Squid error page), not a proxied response

# SSRF: loopback
curl -x http://device:<PROXY_PASSWORD>@127.0.0.1:3128 http://127.0.0.1:4000/api/v1/health -I
# expect: Squid denial -- must NOT reach the backend

# Unreachable destination / DNS failure
curl -x http://device:<PROXY_PASSWORD>@127.0.0.1:3128 http://this-domain-does-not-exist.invalid -I
# expect: Squid DNS-failure error page, not a hang
```

Automate the essentials with a script if you're iterating on `squid.conf`:
```bash
for path in "http://example.com" "https://example.com" "http://169.254.169.254/"; do
  echo "== $path =="
  curl -s -o /dev/null -w "%{http_code}\n" -x http://device:$PROXY_PASSWORD@127.0.0.1:3128 "$path"
done
```

## Android

```bash
cd android
./gradlew :app:testDebugUnitTest
```

11 tests in `UrlParserTest`:
- Bare domain → `https://` URL (`openai.com` → `https://openai.com`)
- Domain with path/query preserved
- Already-schemed `http://`/`https://` URLs pass through untouched
- Multi-word phrase → search (`best hotels london`)
- Single word with no dot → search (`weather`)
- `localhost:3000` → treated as a URL
- IPv4 address → treated as a URL
- Search query correctly URL-encoded per engine (Google/Bing/DuckDuckGo)
- Blank input doesn't crash, resolves to an empty search
- `SearchEngine.fromId` round-trips, falls back to Google for an unknown id

### Building a release APK

```bash
cd android
# fill in local.properties: RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD,
# RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD (generate a keystore first if
# you don't have one: keytool -genkeypair -v -keystore release.jks
# -keyalg RSA -keysize 2048 -validity 10000 -alias release)
./gradlew :app:assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

Without a signing config filled in, `assembleRelease` still produces an **unsigned** APK (useful for CI to confirm the release build compiles and R8/ProGuard doesn't break anything), but it can't be installed until signed.

### Manual device testing

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat | grep -i "alphainventor\|chromium"   # watch for WebView console errors
```

Confirm the proxy is actually being used (not bypassed): watch Squid's access log while browsing —
```bash
tail -f /var/log/squid/access.log   # or `docker compose logs -f squid`
```
Every request from the app should appear here with the `device` (proxy username) field populated. If you see direct traffic to a destination that *didn't* go through Squid, that's a proxy-bypass bug — stop and investigate before shipping.

## Manual browser-compatibility checklist

Run through this list after any change to `BrowserWebView`, `TabManager`, or the proxy config. Check the Squid access log alongside each step to confirm every request is actually proxied.

- [ ] Static HTML page loads and renders correctly
- [ ] A React SPA loads, routes client-side without a full reload, console has no proxy-related errors
- [ ] A Next.js site (SSR + hydration) loads correctly
- [ ] YouTube: video plays, fullscreen works (`onShowCustomView`/`onHideCustomView`)
- [ ] Google search: results load, search-from-address-bar produces the expected query URL
- [ ] A real login page (email/password) submits and authenticates correctly
- [ ] An OAuth flow (e.g. "Sign in with Google") completes and redirects back correctly — this exercises third-party cookies and cross-origin redirects
- [ ] A WebSocket-based page (e.g. a live chat demo) connects and receives messages
- [ ] `<input type="file">` upload: picking a photo from gallery, picking a document, and taking a new photo (camera) all work and the site receives the file
- [ ] Downloading a file (PDF, image) shows a progress notification and completes; the file appears in the device's Downloads
- [ ] Fullscreen video/exit-fullscreen doesn't leave the UI in a broken state
- [ ] Cookies persist across app restarts (a site you're logged into stays logged in)
- [ ] `localStorage` value set on one visit is still present on a later visit
- [ ] Opening multiple tabs works; switching between them preserves each tab's page; closing a tab works
- [ ] `window.open()` / `target="_blank"` link opens as a new tab, not silently blocked
- [ ] A site with an invalid/self-signed certificate is blocked with the "connection is not private" screen, with no way to click through
- [ ] Turning off the proxy (stop the Squid container) and reloading shows the "can't connect to the browsing service" screen — not a direct connection
