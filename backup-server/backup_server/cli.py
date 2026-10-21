from __future__ import annotations

import argparse
import json
import os
import shutil
import sqlite3
import sys
import tarfile
import tempfile
from contextlib import closing
from pathlib import Path

from .core import Settings, Store
from .chunked import ChunkUploads


def export_archive(store: Store, destination: Path) -> None:
    """Offline archive; callers must stop the service before copying files."""
    findings = store.consistency()
    if findings["missing"] or findings["corrupt"]:
        raise RuntimeError("cannot export inconsistent storage")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as scratch:
        snapshot = Path(scratch) / "metadata.sqlite3"
        with store.connect() as source, closing(sqlite3.connect(snapshot)) as target:
            source.backup(target)
        temporary = destination.with_name(destination.name + ".part")
        try:
            with tarfile.open(temporary, "w:gz") as archive:
                archive.add(snapshot, arcname="metadata.sqlite3")
                archive.add(store.settings.data_dir / "files", arcname="files", recursive=True)
            os.replace(temporary, destination)
        finally:
            temporary.unlink(missing_ok=True)


def restore_archive(archive_path: Path, destination: Path) -> dict:
    """Restore only into a new empty directory; never overwrite a running service."""
    if destination.exists() and any(destination.iterdir()):
        raise ValueError("restore destination must be empty")
    destination.mkdir(parents=True, exist_ok=True, mode=0o700)
    with tarfile.open(archive_path, "r:gz") as archive:
        members = archive.getmembers()
        for member in members:
            path = Path(member.name)
            if member.issym() or member.islnk() or member.isdev() or path.is_absolute() or ".." in path.parts:
                raise ValueError("archive contains unsafe path")
            if path.parts[0] not in ("files", "metadata.sqlite3"):
                raise ValueError("archive contains unexpected path")
        archive.extractall(destination, filter="data")
    store = Store(Settings(data_dir=destination, db_path=destination / "metadata.sqlite3"))
    findings = store.consistency()
    if findings["missing"] or findings["corrupt"]:
        raise RuntimeError("restored backup failed consistency check")
    return findings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Kuguan backup server management")
    sub = parser.add_subparsers(dest="command", required=True)
    create = sub.add_parser("token-create")
    create.add_argument("device_id")
    sub.add_parser("token-list")
    revoke = sub.add_parser("token-revoke")
    revoke.add_argument("digest_prefix")
    delete = sub.add_parser("backup-delete", help="Remove one administrator-selected synthetic test backup")
    delete.add_argument("backup_id")
    sub.add_parser("capacity")
    check = sub.add_parser("check")
    check.add_argument("--repair", action="store_true")
    sub.add_parser("prune")
    sub.add_parser("uploads-clean", help="Remove expired chunk sessions and orphan staging files while API is stopped")
    export = sub.add_parser("export")
    export.add_argument("archive", type=Path)
    restore = sub.add_parser("restore")
    restore.add_argument("archive", type=Path)
    restore.add_argument("destination", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "restore":
            print(json.dumps(restore_archive(args.archive, args.destination)))
            return 0
        store = Store(Settings.from_env())
        if args.command == "token-create":
            print(store.create_token(args.device_id))  # Explicit one-time display.
        elif args.command == "token-list":
            print(json.dumps(store.list_tokens(), indent=2))
        elif args.command == "token-revoke":
            if not store.revoke_token(args.digest_prefix):
                raise ValueError("token digest prefix must match exactly one token")
        elif args.command == "capacity":
            print(json.dumps(store.capacity(), indent=2))
        elif args.command == "backup-delete":
            print(json.dumps(store.delete_backup(args.backup_id)))
        elif args.command == "check":
            print(json.dumps(store.consistency(args.repair), indent=2))
        elif args.command == "prune":
            print(store.prune())
        elif args.command == "uploads-clean":
            print(json.dumps(ChunkUploads(store).cleanup_orphans()))
        elif args.command == "export":
            export_archive(store, args.archive)
        return 0
    except (ValueError, RuntimeError, OSError, sqlite3.Error, tarfile.TarError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
