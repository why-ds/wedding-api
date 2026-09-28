#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
base=/var/backups/wedding
[[ -d $base && ! -L $base && $(realpath "$base") == "$base" ]] || exit 1
exec 9>"$base/.backup.lock"
flock -n 9 || exit 0
stamp=$(date -u +%Y%m%dT%H%M%SZ)
temporary="$base/$stamp.partial"
trap 'rm -f -- "$temporary"' EXIT
pg_dump --no-password --format=custom --file="$temporary" wedding
pg_restore --list "$temporary" >/dev/null
mv -- "$temporary" "$base/$stamp.dump"
find "$base" -maxdepth 1 -type f -name '*.dump' -mtime +7 -delete
echo 'Wedding database backup completed.'
