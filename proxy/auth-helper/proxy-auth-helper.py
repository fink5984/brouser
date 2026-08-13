#!/usr/bin/env python3
"""Squid basic-auth helper that validates per-device proxy credentials
against the backend API instead of a static htpasswd file.

Protocol (Squid "basic" helper, one process per `children` slot):
  stdin  <- "<username> <password>\n"
  stdout -> "OK\n" | "ERR\n"

Never logs the password. Fails closed: any network error, timeout, or
unexpected backend response is treated as a rejected login so a backend
outage cannot turn this into an open proxy.
"""
import json
import os
import sys
import urllib.request
import urllib.error

BACKEND_URL = os.environ.get(
    "BACKEND_INTERNAL_URL", "http://backend:4000/internal/v1/proxy/authenticate"
)
INTERNAL_SECRET = os.environ.get("INTERNAL_API_SECRET", "")
TIMEOUT_SECONDS = 4


def check_credentials(username: str, password: str) -> bool:
    if not INTERNAL_SECRET:
        sys.stderr.write("proxy-auth-helper: INTERNAL_API_SECRET is not set\n")
        return False

    payload = json.dumps({"username": username, "password": password}).encode("utf-8")
    req = urllib.request.Request(
        BACKEND_URL,
        data=payload,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-Internal-Secret": INTERNAL_SECRET,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_SECONDS) as resp:
            if resp.status != 200:
                return False
            body = json.loads(resp.read().decode("utf-8"))
            return bool(body.get("ok") is True)
    except (urllib.error.URLError, TimeoutError, ValueError, json.JSONDecodeError):
        return False


def main() -> None:
    for line in sys.stdin:
        line = line.rstrip("\n")
        if not line:
            sys.stdout.write("ERR\n")
            sys.stdout.flush()
            continue

        parts = line.split(" ", 1)
        if len(parts) != 2:
            sys.stdout.write("ERR\n")
            sys.stdout.flush()
            continue

        username, password = parts[0], parts[1]
        ok = check_credentials(username, password)
        sys.stdout.write("OK\n" if ok else "ERR\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
