# Self-hosted deployment (Docker Compose + Caddy)

For running on your own VPS instead of Railway. Two services (`backend`, `squid`) plus Caddy for automatic HTTPS on the backend's domain.

## 1. Server preparation

- A VPS with Docker Engine + Docker Compose plugin installed (`docker compose version` should print v2.x).
- A domain you control, with two DNS records pointed at the VPS's IP:
  - `api.example.com` → backend (via Caddy)
  - `proxy.example.com` → not a DNS requirement for Squid to function (Squid binds directly to the IP), but useful as the CN for its certificate and for the app's own record-keeping.

## 2. DNS

```
A    api.example.com      -> <vps-ip>
A    proxy.example.com    -> <vps-ip>
```

## 3. Environment variables

```bash
cp .env.example .env
```

Fill in `.env` at the project root:
```
API_DOMAIN=api.example.com
PROXY_PUBLIC_HOST=proxy.example.com
ACME_EMAIL=you@example.com

DEVICE_TOKEN=<openssl rand -base64 32>
INTERNAL_API_SECRET=<openssl rand -base64 32>
PROXY_USERNAME=device
PROXY_PASSWORD=<openssl rand -base64 18>

PROXY_HOST=proxy.example.com
PROXY_PORT=3129
PROXY_SCHEME=https
```

**Never commit `.env`.** It's gitignored; double-check before pushing anywhere.

## 4. Firewall

Only these ports should be reachable from the public internet:

| Port | Service | Why |
|---|---|---|
| 80/tcp | Caddy | ACME HTTP-01 challenge + redirect to HTTPS |
| 443/tcp, 443/udp | Caddy | HTTPS (backend API), HTTP/3 |
| 3129/tcp | Squid | TLS-secured proxy port devices connect to |

Everything else must **not** be exposed:
- The backend's own port `4000` is never published to the host in `docker-compose.yml` (only reachable via Caddy on the `edge` network, and by Squid's auth helper on the `internal` network).
- Squid's plain port `3128` is not published in the production compose file (only in `docker-compose.override.yml`, which is for local dev only — don't deploy that file).
- There is no database in this system, so there's nothing else to lock down.

Example with `ufw`:
```bash
sudo ufw allow 22/tcp     # SSH -- restrict to your IP if possible
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow 443/udp
sudo ufw allow 3129/tcp
sudo ufw enable
```

## 5. Deploy

```bash
docker compose --env-file .env -f docker/docker-compose.yml up -d --build
docker compose --env-file .env -f docker/docker-compose.yml ps
```

Caddy will automatically request a Let's Encrypt certificate for `API_DOMAIN` on first request (make sure ports 80/443 are reachable before starting, or the ACME challenge fails).

## 6. Squid's proxy-port certificate

Squid's `https_port` (3129) needs a certificate too, but Caddy doesn't front it (see [architecture.md](architecture.md) for why: Squid needs to remain a raw CONNECT proxy, and Caddy can't reverse-proxy that protocol without becoming a TLS-terminating MITM, which the proxy explicitly must not be).

By default, `proxy/docker-entrypoint.sh` generates a **self-signed** certificate on first container start (30-day validity, `CN=$PROXY_PUBLIC_HOST`), so the stack works out of the box. For any deployment reachable over an untrusted network, replace it with a real certificate:

```bash
# Using certbot in standalone mode (stop Squid briefly, or use a
# DNS-01 challenge if you'd rather not open port 80 twice):
sudo certbot certonly --standalone -d proxy.example.com

# Concatenate into the format Squid expects and copy into the squid_certs volume:
cat /etc/letsencrypt/live/proxy.example.com/fullchain.pem \
    > /var/lib/docker/volumes/managed-browser_squid_certs/_data/proxy.pem
cp /etc/letsencrypt/live/proxy.example.com/privkey.pem \
   /var/lib/docker/volumes/managed-browser_squid_certs/_data/proxy.key

docker compose -f docker/docker-compose.yml restart squid
```

Set up a renewal hook that repeats the copy + `docker compose restart squid` every renewal.

## 7. Verify

```bash
curl https://api.example.com/api/v1/health
curl -H "Authorization: Bearer $DEVICE_TOKEN" https://api.example.com/api/v1/device/config

curl -x https://device:$PROXY_PASSWORD@proxy.example.com:3129 http://example.com -I
curl -x https://device:$PROXY_PASSWORD@proxy.example.com:3129 https://example.com -I
curl -x https://device:$PROXY_PASSWORD@proxy.example.com:3129 http://169.254.169.254/ -I   # must be blocked
```

Check logs:
```bash
docker compose -f docker/docker-compose.yml logs -f backend
docker compose -f docker/docker-compose.yml logs -f squid
```

## 8. Android build

Same as the Railway path — see [README.md](../README.md#3-android-app) and [testing.md](testing.md#building-a-release-apk). Point `local.properties` at `https://api.example.com` and the `DEVICE_TOKEN` you generated.

## Local testing without Docker

If Docker isn't available on your dev machine (e.g. Docker Desktop won't start on Windows), everything in this repo can still be exercised natively:

1. Install Squid directly (`apt install squid` on Linux/WSL, or the equivalent for your OS).
2. Run the auth helper's dependency (Python 3, already present on most systems) and point `squid.conf`'s `auth_param basic program` line at the absolute path of `proxy/auth-helper/proxy-auth-helper.py`.
3. Export `BACKEND_INTERNAL_URL` and `INTERNAL_API_SECRET` in the shell that starts Squid (the helper is spawned as Squid's child process and inherits its environment).
4. Run the backend with `npm run dev` (see the root README's quickstart) — no container needed, no database to stand up.
5. Point the Android app's `CONFIG_BASE_URL` at your machine's LAN IP (or `10.0.2.2` for the emulator) and `PROXY_HOST`/`PROXY_PORT` at the same.

This is exactly the setup used to develop and test this project.
