#!/usr/bin/env bash
set -Eeuo pipefail
umask 027
release=${1:?Missing commit SHA}
[[ $release =~ ^[0-9a-f]{40}$ ]] || exit 1
upload=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd "$upload"
sha256sum --check app.jar.sha256
base=/opt/wedding-api
exec 9>"$base/.deploy.lock"
flock -w 180 9
destination="$base/releases/$release"
previous=$(readlink -f "$base/current" || true)
if [[ -e $destination ]]; then
    cmp --silent app.jar "$destination/app.jar" || { echo 'Existing release has different content.' >&2; exit 1; }
else
    mkdir -m 2750 "$destination"
    install -m 0640 app.jar "$destination/app.jar"
fi
rollback() {
    trap - ERR
    if [[ $previous == "$base/releases/"* && -f "$previous/app.jar" ]]; then
        ln -sfn "$previous" "$base/current.next"
        mv -Tf "$base/current.next" "$base/current"
        sudo -n /usr/bin/systemctl restart wedding-api.service || true
    else
        sudo -n /usr/bin/systemctl stop wedding-api.service || true
    fi
    echo 'Deployment failed. Inspect wedding-api.service logs; SQL migrations are not automatically reverted.' >&2
    exit 1
}
trap rollback ERR
ln -sfn "$destination" "$base/current.next"
mv -Tf "$base/current.next" "$base/current"
if [[ -f /etc/systemd/system/wedding-migrate.service ]]; then
    sudo -n /usr/bin/systemctl start wedding-migrate.service
fi
sudo -n /usr/bin/systemctl restart wedding-api.service
healthy=false
for attempt in $(seq 1 45); do
    if curl --silent --fail --max-time 3 http://127.0.0.1:18081/actuator/health | grep -q '"status":"UP"'; then
        healthy=true; break
    fi
    sleep 2
done
[[ $healthy == true ]]
curl --silent --show-error --fail --max-time 15 http://127.0.0.1:18081/api/v1/searches \
    -H 'Content-Type: application/json' \
    --data '{"date":"2027-02-27","time":"12:00","guests":250,"region":"","style":"","query":"","budget":null,"beverages":false,"sort":"price"}' >/dev/null
trap - ERR
python3 "$upload/prune-releases.py" "$base/releases" --protect "$release" --protect "${previous##*/}" || echo 'Release cleanup failed; inspect disk usage.' >&2
python3 "$upload/prune-releases.py" "$(dirname "$upload")" --protect "$release" --protect "${previous##*/}" || echo 'Upload cleanup failed; inspect disk usage.' >&2
echo "API release $release is healthy."
