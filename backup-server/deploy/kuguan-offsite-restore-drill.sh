#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
base=/var/lib/kuguan-offsite
archives="$base/archives"
latest_marker=$(find "$archives" -maxdepth 1 -type f -name 'backup-*.tar.gpg.sha256' -printf '%f\n' | sort -r | head -n 1)
latest="${latest_marker%.sha256}"
[[ -n "$latest" ]] || { echo "no committed archive" >&2; exit 1; }
read -r expected_digest expected_length <"$archives/$latest_marker"
[[ "$expected_digest" =~ ^[0-9a-f]{64}$ && "$expected_length" =~ ^[0-9]+$ ]] || exit 1
[[ $(stat -c %s "$archives/$latest") == "$expected_length" ]] || exit 1
[[ $(sha256sum "$archives/$latest" | cut -d ' ' -f 1) == "$expected_digest" ]] || exit 1
mkdir -p "$base/drills"
destination=$(mktemp -d "$base/drills/restore.XXXXXXXX")
trap 'rm -rf -- "$destination"' EXIT
gpg --batch --quiet --decrypt "$archives/$latest" 2>/dev/null \
  | tar -xzf - --no-same-owner --no-same-permissions -C "$destination"
[[ -f "$destination/metadata.sqlite3" && -d "$destination/files" ]] || exit 1
KUGUAN_DATA_DIR="$destination" KUGUAN_DB_PATH="$destination/metadata.sqlite3" \
  PYTHONPATH=/opt/kuguan-offsite-check python3 - <<'PY'
from backup_server.core import Settings, Store
store = Store(Settings.from_env())
findings = store.consistency()
if any(findings.values()):
    raise SystemExit(f"restore drill failed: {findings}")
print(f"restore drill passed: {store.capacity()['count']} registered backup(s), database and files consistent")
PY
