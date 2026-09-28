#!/bin/sh
set -eu

# Install as a Certbot deploy hook; reload only after this certificate renews.
if [ "${RENEWED_LINEAGE:-}" = /etc/letsencrypt/live/allaboutwedding.co.kr ]; then
    /usr/sbin/nginx -t
    /usr/bin/systemctl reload nginx
fi
