#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

base=/var/lib/kuguan-offsite
archives="$base/archives"
[[ -d "$archives" && ! -L "$archives" ]] || exit 1
exec 8>"$base/receive.lock"
flock -x 8
command_text="${SSH_ORIGINAL_COMMAND:-}"
[[ "$command_text" =~ ^upload\ (backup-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{32}\.tar\.gpg)$ ]] || exit 1
name="${BASH_REMATCH[1]}"
final="$archives/$name"
marker="$final.sha256"
[[ ! -e "$final" && ! -e "$marker" ]] || exit 1
temporary=$(mktemp "$archives/.incoming.XXXXXXXX")
marker_temporary=$(mktemp "$archives/.incoming-marker.XXXXXXXX")
committed=0
cleanup() {
    rm -f -- "$temporary" "$marker_temporary"
    if (( committed == 0 )); then rm -f -- "$final" "$marker"; fi
}
trap cleanup EXIT
cat >"$temporary"
[[ -s "$temporary" ]] || exit 1

# GPG's integrity check and tar's parser must both reach EOF before publication.
gpg --batch --quiet --decrypt "$temporary" 2>/dev/null | tar -tzf - >/dev/null
digest=$(sha256sum "$temporary" | cut -d ' ' -f 1)
length=$(stat -c %s "$temporary")
printf '%s %s\n' "$digest" "$length" >"$marker_temporary"
chmod 0400 "$temporary"
chmod 0400 "$marker_temporary"
mv -- "$temporary" "$final"
mv -- "$marker_temporary" "$marker"
committed=1
echo "committed $name"

# Keep seven newest successful daily copies; a failed upload never reaches here.
mapfile -t old < <(find "$archives" -maxdepth 1 -type f -name 'backup-*.tar.gpg.sha256' -printf '%f\n' | sort -r | tail -n +8)
for file in "${old[@]}"; do
    rm -f -- "$archives/${file%.sha256}" "$archives/$file" || echo "retention cleanup failed" >&2
done
