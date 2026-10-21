#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

config=/etc/kuguan-offsite.conf
[[ -r "$config" ]] || { echo "offsite configuration missing" >&2; exit 1; }
# root-owned file contains only endpoint, fingerprint and key paths.
source "$config"
: "${KUGUAN_OFFSITE_RECIPIENT:?}"
: "${KUGUAN_OFFSITE_HOST:?}"
: "${KUGUAN_OFFSITE_SSH_KEY:?}"
[[ "$KUGUAN_OFFSITE_RECIPIENT" =~ ^[A-Fa-f0-9]{40,64}$ ]] || exit 1
[[ "$KUGUAN_OFFSITE_HOST" =~ ^[0-9.]+$ ]] || exit 1
[[ -r "$KUGUAN_OFFSITE_SSH_KEY" ]] || exit 1

exec 9>/run/lock/kuguan-offsite.lock
flock -n 9 || { echo "offsite backup already running" >&2; exit 1; }

service=kuguan-backup.service
data=/var/lib/kuguan-backup
[[ -f "$data/metadata.sqlite3" && -d "$data/files" ]] || exit 1
systemctl is-active --quiet "$service" || { echo "source service is not active" >&2; exit 1; }

name="backup-$(date -u +%Y%m%dT%H%M%SZ)-$(tr -d - </proc/sys/kernel/random/uuid).tar.gpg"
restart_service() {
    status=$?
    trap - EXIT
    if ! systemctl start "$service" || ! systemctl is-active --quiet "$service"; then
        echo "CRITICAL: source service did not restart" >&2
        exit 1
    fi
    exit "$status"
}
trap restart_service EXIT
systemctl stop "$service"

# Ensure the main SQLite file contains all committed transactions before tar.
sudo -u kuguan-backup sqlite3 "$data/metadata.sqlite3" 'PRAGMA wal_checkpoint(TRUNCATE);' >/dev/null
sudo -u kuguan-backup env KUGUAN_ENV_FILE=/etc/kuguan-backup.env \
  /opt/kuguan-backup/.venv/bin/python - <<'PY'
from backup_server.core import Settings, Store
findings = Store(Settings.from_env()).consistency()
if findings["missing"] or findings["corrupt"]:
    raise SystemExit("source storage is inconsistent")
PY

tar -C "$data" -czf - metadata.sqlite3 files \
  | gpg --batch --yes --quiet --trust-model always --recipient "$KUGUAN_OFFSITE_RECIPIENT" \
      --cipher-algo AES256 --compress-algo none --encrypt \
  | ssh -T -i "$KUGUAN_OFFSITE_SSH_KEY" -o BatchMode=yes -o StrictHostKeyChecking=yes \
      -o IdentitiesOnly=yes "kuguan-offsite@$KUGUAN_OFFSITE_HOST" "upload $name"

echo "offsite archive committed: $name"
