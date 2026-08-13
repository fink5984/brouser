# Deploying to Railway

Recommended path for a single personal device: no server administration, automatic HTTPS for the backend, and Railway's TCP proxy feature exposes Squid (which isn't HTTP) on its own `host:port`.

## 1. Generate secrets

```bash
openssl rand -base64 32   # DEVICE_TOKEN
openssl rand -base64 32   # INTERNAL_API_SECRET
openssl rand -base64 18   # PROXY_PASSWORD
```

Keep these somewhere safe — you'll need `DEVICE_TOKEN` again for the Android build.

## 2. Backend service

1. New Railway project → **Deploy from repo**, root directory `backend/`.
2. Railway auto-detects the Dockerfile (`backend/Dockerfile`) — or set the build to Nixpacks with `npm run build` / start command `node dist/server.js`, either works since there's no database migration step to run.
3. Environment variables (Railway → Variables):
   ```
   NODE_ENV=production
   DEVICE_TOKEN=<generated above>
   INTERNAL_API_SECRET=<generated above>
   PROXY_USERNAME=device
   PROXY_PASSWORD=<generated above>
   PROXY_HOST=<squid service's Railway TCP proxy host, from step 3 below>
   PROXY_PORT=<squid service's Railway TCP proxy port, from step 3 below>
   PROXY_SCHEME=http
   HOMEPAGE=https://www.google.com
   SEARCH_ENGINE=google
   MAX_TABS=10
   DOWNLOADS_ENABLED=true
   CORS_ORIGIN=*
   ```
   You'll circle back to fill in `PROXY_HOST`/`PROXY_PORT` once the Squid service exists (step 3) — Railway lets you edit variables and redeploy at any time.
4. Railway gives this service a public HTTPS domain automatically (`<name>.up.railway.app` or a custom domain you attach). That's `CONFIG_BASE_URL` for the Android app.

Why `PROXY_SCHEME=http` here: Railway's TCP proxy terminates nothing for you — it's a raw TCP passthrough to whatever port Squid listens on inside the container. Running Squid's own `https_port` (3129) still works if you want the extra TLS-to-proxy layer (see `proxy/docker-entrypoint.sh`, which self-signs a cert automatically), but for a personal device connecting over normal mobile/Wi-Fi networks, `http` + Railway's private network transport is a reasonable simplification. Use `https` (Squid's 3129) if you want credentials protected even against local network observation.

## 3. Squid (proxy) service

1. Add a second service to the same Railway project, root directory `proxy/` (uses `proxy/Dockerfile`).
2. Environment variables:
   ```
   BACKEND_INTERNAL_URL=http://<backend service's Railway private hostname>:4000/internal/v1/proxy/authenticate
   INTERNAL_API_SECRET=<same value as the backend>
   PROXY_PUBLIC_HOST=<squid service's public TCP proxy hostname, once assigned>
   ```
   Railway services in the same project can reach each other over the private network using `<service-name>.railway.internal` — use that for `BACKEND_INTERNAL_URL` so the auth-check traffic never leaves Railway's network.
3. Under the Squid service's **Networking** tab, enable **TCP Proxy** on port `3128` (or `3129` if you're using the TLS-secured port). Railway assigns a public `host:port` pair — that's your `PROXY_HOST`/`PROXY_PORT`. Go back and set those on the backend service, then redeploy it.

## 4. Verify

```bash
curl https://<backend-domain>/api/v1/health
curl -H "Authorization: Bearer <DEVICE_TOKEN>" https://<backend-domain>/api/v1/device/config

curl -x http://device:<PROXY_PASSWORD>@<railway-tcp-host>:<railway-tcp-port> http://example.com -I
curl -x http://device:<PROXY_PASSWORD>@<railway-tcp-host>:<railway-tcp-port> https://example.com -I
```

## 5. Android build

Edit `android/local.properties` (not committed):
```
CONFIG_BASE_URL=https://<backend-domain>
DEVICE_TOKEN=<generated above>
```

Then:
```bash
cd android
./gradlew :app:assembleRelease   # or assembleDebug for a quick test install
```

For a release build you'll also need signing config (`RELEASE_STORE_FILE` etc. in `local.properties`) — see [testing.md](testing.md#building-a-release-apk).

## Firewall / exposure notes

- The backend's Railway domain is public HTTPS — that's expected, it's how the app fetches config. `DEVICE_TOKEN` is what actually gates access to the proxy credentials.
- Squid's TCP proxy endpoint is also public by nature of the feature — that's fine, because Squid itself refuses unauthenticated connections (see [security-review.md](security-review.md)).
- Nothing else needs to be exposed. There's no database and no admin panel in this deployment.
