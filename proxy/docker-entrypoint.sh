#!/bin/sh
set -e

CERT_DIR=/etc/squid/certs
CERT_FILE="$CERT_DIR/proxy.pem"
KEY_FILE="$CERT_DIR/proxy.key"
CN="${PROXY_PUBLIC_HOST:-proxy.local}"

mkdir -p "$CERT_DIR"

if [ ! -s "$CERT_FILE" ] || [ ! -s "$KEY_FILE" ]; then
    echo "proxy-entrypoint: no certificate found at $CERT_FILE, generating a self-signed development certificate for CN=$CN"
    echo "proxy-entrypoint: for production, mount a real certificate+key at these paths instead (see docs/deployment.md)"
    openssl req -x509 -nodes -newkey rsa:2048 \
        -days 30 \
        -keyout "$KEY_FILE" \
        -out "$CERT_FILE" \
        -subj "/CN=$CN" \
        -addext "subjectAltName=DNS:$CN" >/dev/null 2>&1
fi

exec squid -N -f /etc/squid/squid.conf
