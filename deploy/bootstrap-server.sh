#!/usr/bin/env bash
# Run once on the user's Ubuntu 24.04 x86_64 EC2 instance: sudo bash deploy/bootstrap-server.sh
set -Eeuo pipefail
umask 077
[[ $EUID == 0 ]] || { echo 'Run with sudo.' >&2; exit 1; }
deploy_user=${SUDO_USER:-ubuntu}
[[ $deploy_user =~ ^[a-z_][a-z0-9_-]*$ && $deploy_user != root ]] || exit 1
id "$deploy_user" >/dev/null
[[ $(uname -m) == x86_64 ]] || { echo 'This service template targets x86_64.' >&2; exit 1; }
source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
[[ ! -e /etc/wedding-api.env ]] || { echo 'Already configured; existing credentials were preserved.' >&2; exit 1; }
[[ ! -e /etc/nginx/sites-available/wedding-preview && ! -L /etc/nginx/sites-enabled/wedding-preview ]] || {
    echo 'Existing wedding Nginx config found; inspect it before setup.' >&2; exit 1;
}
for command in psql nginx openssl curl ss; do command -v "$command" >/dev/null; done
nginx -t
if ss -H -ltn '( sport = :8088 or sport = :18081 )' | grep -q .; then
    echo 'Port 8088 or 18081 is already in use; nothing was changed.' >&2; exit 1
fi
role_exists=$(sudo -u postgres psql -X -d postgres -Atc "SELECT count(*) FROM pg_roles WHERE rolname IN ('wedding_app','wedding_migrator')")
db_exists=$(sudo -u postgres psql -X -d postgres -Atc "SELECT count(*) FROM pg_database WHERE datname='wedding'")
[[ $role_exists == 0 && $db_exists == 0 ]] || { echo 'Existing wedding role/database found; refusing to overwrite.' >&2; exit 1; }
extensions=$(sudo -u postgres psql -X -d postgres -Atc "SELECT count(*) FROM pg_available_extensions WHERE name IN ('pg_trgm','btree_gist')")
[[ $extensions == 2 ]] || { echo 'Install the PostgreSQL 16 contrib extensions before retrying.' >&2; exit 1; }

# Adding Java 21 must not change the default used by lunch.
previous_java=$(readlink -f "$(command -v java)")
restore_java() { update-alternatives --set java "$previous_java" >/dev/null; }
if [[ ! -x /usr/lib/jvm/java-21-openjdk-amd64/bin/java ]]; then
    trap restore_java EXIT
    apt-get update
    DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=l apt-get install -y openjdk-21-jre-headless
    restore_java
    trap - EXIT
fi
getent passwd wedding-api >/dev/null || useradd --system --home-dir /var/lib/wedding-api --shell /usr/sbin/nologin wedding-api
getent passwd wedding-migrate >/dev/null || useradd --system --no-create-home --shell /usr/sbin/nologin wedding-migrate
install -d -o wedding-api -g wedding-api -m 0750 /var/lib/wedding-api
install -d -o "$deploy_user" -g wedding-api -m 2750 /opt/wedding-api /opt/wedding-api/releases
install -d -o "$deploy_user" -g www-data -m 2755 /var/www/wedding-web /var/www/wedding-web/releases

db_password=$(openssl rand -hex 32)
migration_password=$(openssl rand -hex 32)
admin_password=$(openssl rand -hex 24)
# Generated hexadecimal values are sent over stdin, never process arguments or logs.
sudo -u postgres psql -X -v ON_ERROR_STOP=1 -d postgres <<SQL
CREATE ROLE wedding_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '$db_password';
CREATE ROLE wedding_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '$migration_password';
CREATE DATABASE wedding OWNER wedding_migrator;
REVOKE CONNECT, TEMPORARY ON DATABASE wedding FROM PUBLIC;
GRANT CONNECT ON DATABASE wedding TO wedding_app;
SQL
sudo -u postgres psql -X -v ON_ERROR_STOP=1 -d wedding -c 'REVOKE CREATE ON SCHEMA public FROM PUBLIC;'
cat > /etc/wedding-migrate.env <<ENV
DB_URL=jdbc:postgresql://127.0.0.1:5432/wedding
DB_USERNAME=wedding_migrator
DB_PASSWORD=$migration_password
WEDDING_MIGRATION_MODE=synthetic-preview
ENV
chmod 600 /etc/wedding-migrate.env
cat > /etc/wedding-api.env <<ENV
SPRING_PROFILES_ACTIVE=postgres,preview
PORT=18081
DB_URL=jdbc:postgresql://127.0.0.1:5432/wedding
DB_USERNAME=wedding_app
DB_PASSWORD=$db_password
DB_MIGRATIONS_ENABLED=false
SESSION_COOKIE_SECURE=false
WEDDING_ADMIN_BOOTSTRAP_ENABLED=true
ADMIN_EMAIL=admin@allaboutwedding.local
ADMIN_PASSWORD=$admin_password
ENV
chmod 600 /etc/wedding-api.env
cat > /etc/wedding-initial-login.txt <<LOGIN
All About Wedding persistent preview administrator
Email: admin@allaboutwedding.local
Password: $admin_password
Use test data only on this temporary HTTP preview.
LOGIN
chmod 600 /etc/wedding-initial-login.txt
unset db_password migration_password admin_password

install -o root -g root -m 0644 "$source_dir/wedding-api.service" /etc/systemd/system/wedding-api.service
install -o root -g root -m 0644 "$source_dir/wedding-migrate.service" /etc/systemd/system/wedding-migrate.service
install -d -o postgres -g postgres -m 0700 /var/backups/wedding
install -d -o root -g root -m 0755 /usr/local/lib/wedding
install -o root -g root -m 0755 "$source_dir/backup-database.sh" /usr/local/lib/wedding/backup-database.sh
install -o root -g root -m 0644 "$source_dir/wedding-backup.service" /etc/systemd/system/wedding-backup.service
install -o root -g root -m 0644 "$source_dir/wedding-backup.timer" /etc/systemd/system/wedding-backup.timer
systemctl daemon-reload
systemctl enable wedding-api.service
systemctl enable --now wedding-backup.timer
sudoers_tmp=$(mktemp)
printf '%s ALL=(root) NOPASSWD: /usr/bin/systemctl restart wedding-api.service, /usr/bin/systemctl stop wedding-api.service, /usr/bin/systemctl start wedding-migrate.service\n' "$deploy_user" > "$sudoers_tmp"
chmod 440 "$sudoers_tmp"
visudo -cf "$sudoers_tmp"
install -o root -g root -m 0440 "$sudoers_tmp" /etc/sudoers.d/wedding-deploy
rm -f "$sudoers_tmp"

install -o root -g root -m 0644 "$source_dir/wedding-preview.nginx.conf" /etc/nginx/sites-available/wedding-preview
ln -s /etc/nginx/sites-available/wedding-preview /etc/nginx/sites-enabled/wedding-preview
if ! nginx -t; then
    unlink /etc/nginx/sites-enabled/wedding-preview
    echo 'New wedding site disabled because Nginx validation failed. Existing sites were not reloaded.' >&2
    exit 1
fi
systemctl reload nginx
echo 'Setup complete. No application artifact has been deployed yet.'
echo 'Next: configure GitHub preview secrets, enable deployment, run each workflow.'
echo 'View the generated admin password locally with: sudo cat /etc/wedding-initial-login.txt'
echo 'Keep port 18081 and PostgreSQL private. Allow port 8088 only from your own IP.'
