FROM ubuntu:22.04

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        squid-openssl \
        python3 \
        openssl \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY squid.conf /etc/squid/squid.conf
COPY auth-helper/proxy-auth-helper.py /usr/local/bin/proxy-auth-helper.py
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod 755 /usr/local/bin/proxy-auth-helper.py /usr/local/bin/docker-entrypoint.sh \
    && mkdir -p /var/log/squid /var/spool/squid /etc/squid/certs \
    && chown -R proxy:proxy /var/log/squid /var/spool/squid /etc/squid/certs

EXPOSE 3128 3129

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD squidclient -h 127.0.0.1 -p 3128 mgr:info >/dev/null 2>&1 || exit 1

USER proxy
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
