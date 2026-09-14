#!/usr/bin/env bash
set -Eeuo pipefail
: "${SERVER_HOST:?Set preview environment SERVER_HOST}"
: "${SERVER_USER:?Set preview environment SERVER_USER}"
: "${SERVER_KEY:?Set preview environment SERVER_KEY}"
: "${SERVER_KNOWN_HOSTS:?Set preview environment SERVER_KNOWN_HOSTS}"
release=${1:?Missing commit SHA}
[[ $release =~ ^[0-9a-f]{40}$ ]] || exit 1
[[ $SERVER_HOST =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*$ ]] || exit 1
[[ $SERVER_USER =~ ^[a-z_][a-z0-9_-]*$ ]] || exit 1
umask 077
ssh_dir=$(mktemp -d)
cleanup() { rm -f "$ssh_dir/key" "$ssh_dir/known_hosts"; rmdir "$ssh_dir"; }
trap cleanup EXIT
printf '%s\n' "$SERVER_KEY" > "$ssh_dir/key"
printf '%s\n' "$SERVER_KNOWN_HOSTS" > "$ssh_dir/known_hosts"
unset SERVER_KEY SERVER_KNOWN_HOSTS
options=(-i "$ssh_dir/key" -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o "UserKnownHostsFile=$ssh_dir/known_hosts" -o ConnectTimeout=10)
remote="$SERVER_USER@$SERVER_HOST"
upload="wedding-deploy/api/$release"
ssh "${options[@]}" "$remote" "mkdir -p '$upload'"
scp "${options[@]}" artifact/app.jar artifact/app.jar.sha256 deploy/activate-release.sh "$remote:$upload/"
ssh "${options[@]}" "$remote" "bash '$upload/activate-release.sh' '$release'"
