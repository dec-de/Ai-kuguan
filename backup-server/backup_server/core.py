from __future__ import annotations

import hashlib
import hmac
import os
import secrets
import shutil
import sqlite3
import threading
import time
import uuid
from collections import defaultdict, deque
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

MAX_UPLOAD_BYTES = 500_000_000
MAX_REQUEST_BYTES = 10_000_000
ACCOUNT_ID = "00000000-0000-4000-8000-000000000001"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z")


def token_digest(token: str) -> str:
    return hashlib.sha256(token.encode("ascii")).hexdigest()


@dataclass(frozen=True)
class Settings:
    data_dir: Path
    db_path: Path
    max_upload_bytes: int = MAX_UPLOAD_BYTES
    quota_bytes: int = 20_000_000_000
    retention_count: int = 30
    rate_limit_per_minute: int = 120
    max_request_bytes: int = MAX_REQUEST_BYTES
    upload_ttl_seconds: int = 86_400

    @classmethod
    def from_env(cls) -> "Settings":
        file_path = Path(os.environ.get("KUGUAN_ENV_FILE", "/etc/kuguan-backup.env"))
        values: dict[str, str] = {}
        if file_path.exists():
            for line in file_path.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                key, separator, value = line.partition("=")
                if not separator or not key.startswith("KUGUAN_"):
                    raise ValueError(f"invalid configuration line in {file_path}")
                values[key] = value.strip().strip('"')
        elif "KUGUAN_ENV_FILE" in os.environ:
            raise FileNotFoundError(file_path)
        values.update({key: value for key, value in os.environ.items() if key.startswith("KUGUAN_")})
        data = Path(values.get("KUGUAN_DATA_DIR", "/var/lib/kuguan-backup"))
        return cls(
            data_dir=data,
            db_path=Path(values.get("KUGUAN_DB_PATH", str(data / "metadata.sqlite3"))),
            max_upload_bytes=int(values.get("KUGUAN_MAX_UPLOAD_BYTES", MAX_UPLOAD_BYTES)),
            quota_bytes=int(values.get("KUGUAN_QUOTA_BYTES", 20_000_000_000)),
            retention_count=max(2, int(values.get("KUGUAN_RETENTION_COUNT", 30))),
            rate_limit_per_minute=int(values.get("KUGUAN_RATE_LIMIT_PER_MINUTE", 120)),
            max_request_bytes=int(values.get("KUGUAN_MAX_REQUEST_BYTES", MAX_REQUEST_BYTES)),
            upload_ttl_seconds=int(values.get("KUGUAN_UPLOAD_TTL_SECONDS", 86_400)),
        )


class Store:
    def __init__(self, settings: Settings):
        if settings.max_upload_bytes < 1 or settings.quota_bytes < 1:
            raise ValueError("size limits must be positive")
        self.settings = settings
        for path in (settings.data_dir, settings.data_dir / "files" / ACCOUNT_ID,
                     settings.data_dir / "tmp", settings.data_dir / "quarantine"):
            path.mkdir(parents=True, exist_ok=True, mode=0o700)
            if path.is_symlink():
                raise ValueError("data directory must not contain symbolic links")
        settings.db_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        self._init_db()
        self._rate_lock = threading.Lock()
        self._requests: dict[str, deque[float]] = defaultdict(deque)

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.settings.db_path, timeout=30, isolation_level=None)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("PRAGMA busy_timeout=30000")
        try:
            yield db
            if db.in_transaction:
                db.commit()
        except Exception:
            if db.in_transaction:
                db.rollback()
            raise
        finally:
            db.close()

    def _init_db(self) -> None:
        with self.connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript("""
                CREATE TABLE IF NOT EXISTS tokens (
                    digest TEXT PRIMARY KEY, account_id TEXT NOT NULL,
                    device_id TEXT NOT NULL, created_at TEXT NOT NULL,
                    revoked_at TEXT
                );
                CREATE TABLE IF NOT EXISTS backups (
                    account_id TEXT NOT NULL, backup_id TEXT NOT NULL,
                    received_at TEXT NOT NULL, size_bytes INTEGER NOT NULL,
                    sha256 TEXT NOT NULL, device_id TEXT NOT NULL,
                    state TEXT NOT NULL CHECK(state='ready'),
                    PRIMARY KEY(account_id, backup_id)
                );
                CREATE INDEX IF NOT EXISTS backups_order
                    ON backups(account_id, received_at DESC, backup_id DESC);
            """)

    def create_token(self, device_id: str) -> str:
        if not device_id or len(device_id) > 64 or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-" for c in device_id):
            raise ValueError("device ID must be 1-64 ASCII letters, digits, _ or -")
        token = secrets.token_urlsafe(32)
        with self.connect() as db:
            db.execute("INSERT INTO tokens VALUES (?,?,?,?,NULL)",
                       (token_digest(token), ACCOUNT_ID, device_id, utc_now()))
        return token

    def authenticate(self, token: str) -> tuple[str, str] | None:
        if len(token) > 256 or not token.isascii():
            return None
        digest = token_digest(token)
        with self.connect() as db:
            rows = db.execute("SELECT digest,account_id,device_id,revoked_at FROM tokens").fetchall()
        for row in rows:
            if hmac.compare_digest(row["digest"], digest):
                return ("revoked", row["device_id"]) if row["revoked_at"] else (row["account_id"], row["device_id"])
        return None

    def check_rate(self, digest: str) -> int | None:
        limit = self.settings.rate_limit_per_minute
        if limit <= 0:
            return None
        now = time.monotonic()
        with self._rate_lock:
            bucket = self._requests[digest]
            while bucket and bucket[0] <= now - 60:
                bucket.popleft()
            if len(bucket) >= limit:
                return max(1, int(60 - (now - bucket[0])) + 1)
            bucket.append(now)
        return None

    def list_tokens(self) -> list[dict]:
        with self.connect() as db:
            rows = db.execute("SELECT substr(digest,1,12) AS digest_prefix, device_id,created_at,revoked_at FROM tokens ORDER BY created_at").fetchall()
        return [dict(row) for row in rows]

    def revoke_token(self, prefix: str) -> bool:
        if len(prefix) < 12 or any(c not in "0123456789abcdef" for c in prefix):
            raise ValueError("provide at least 12 lowercase hex characters of digest")
        with self.connect() as db:
            rows = db.execute("SELECT digest FROM tokens WHERE digest LIKE ?", (prefix + "%",)).fetchall()
            if len(rows) != 1:
                return False
            db.execute("UPDATE tokens SET revoked_at=? WHERE digest=?", (utc_now(), rows[0]["digest"]))
            return True

    def path_for(self, backup_id: str) -> Path:
        # Only canonical UUID v4 IDs from the API are allowed here.
        parsed = uuid.UUID(backup_id)
        if parsed.version != 4 or str(parsed) != backup_id:
            raise ValueError("invalid backup ID")
        return self.settings.data_dir / "files" / ACCOUNT_ID / (backup_id + ".json")

    def new_temp(self) -> tuple[int, Path]:
        path = self.settings.data_dir / "tmp" / (uuid.uuid4().hex + ".part")
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        return fd, path

    def get(self, backup_id: str) -> dict | None:
        with self.connect() as db:
            row = db.execute("SELECT * FROM backups WHERE account_id=? AND backup_id=? AND state='ready'", (ACCOUNT_ID, backup_id)).fetchone()
        return dict(row) if row else None

    def commit(self, backup_id: str, temp: Path, size: int, digest: str, device_id: str) -> tuple[dict, bool]:
        target = self.path_for(backup_id)
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            existing = db.execute("SELECT * FROM backups WHERE account_id=? AND backup_id=?", (ACCOUNT_ID, backup_id)).fetchone()
            if existing:
                db.rollback()
                if existing["sha256"] == digest and existing["size_bytes"] == size:
                    return dict(existing), False
                raise FileExistsError("backup ID already contains different content")
            used = db.execute("SELECT COALESCE(SUM(size_bytes),0) FROM backups WHERE account_id=?", (ACCOUNT_ID,)).fetchone()[0]
            free = shutil.disk_usage(self.settings.data_dir).free
            if used + size > self.settings.quota_bytes or free < max(size, 1_000_000):
                db.rollback()
                raise OSError("storage quota or disk space exhausted")
            if target.exists() or target.is_symlink():
                db.rollback()
                raise RuntimeError("unregistered backup file exists; run consistency repair")
            os.replace(temp, target)
            os.chmod(target, 0o600)
            if os.name != "nt":
                dir_fd = os.open(target.parent, os.O_RDONLY)
                try:
                    os.fsync(dir_fd)
                finally:
                    os.close(dir_fd)
            received = utc_now()
            db.execute("INSERT INTO backups VALUES (?,?,?,?,?,?, 'ready')",
                       (ACCOUNT_ID, backup_id, received, size, digest, device_id))
            db.commit()
        return self.get(backup_id), True

    def list_backups(self, limit: int, cursor: tuple[str, str] | None) -> tuple[list[dict], tuple[str, str] | None]:
        query = "SELECT * FROM backups WHERE account_id=? AND state='ready'"
        args: list = [ACCOUNT_ID]
        if cursor:
            query += " AND (received_at,backup_id) < (?,?)"
            args.extend(cursor)
        query += " ORDER BY received_at DESC, backup_id DESC LIMIT ?"
        args.append(limit + 1)
        with self.connect() as db:
            rows = [dict(r) for r in db.execute(query, args).fetchall()]
        page = rows[:limit]
        next_cursor = (page[-1]["received_at"], page[-1]["backup_id"]) if len(rows) > limit else None
        return page, next_cursor

    def prune(self) -> int:
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            rows = db.execute("SELECT backup_id FROM backups WHERE account_id=? ORDER BY received_at DESC,backup_id DESC", (ACCOUNT_ID,)).fetchall()
            stale = rows[max(2, self.settings.retention_count):]
            deleted = 0
            for row in stale:
                path = self.path_for(row["backup_id"])
                # Remove metadata first: failure leaves an orphan rather than a downloadable missing file.
                db.execute("DELETE FROM backups WHERE account_id=? AND backup_id=?", (ACCOUNT_ID, row["backup_id"]))
                try:
                    path.unlink(missing_ok=True)
                except OSError:
                    pass  # Consistency repair will quarantine an orphan later.
                deleted += 1
            db.commit()
        return deleted

    def delete_backup(self, backup_id: str) -> dict:
        """Administrator-only removal with an on-disk tombstone for crash recovery."""
        target = self.path_for(backup_id)
        tombstone = self.settings.data_dir / "quarantine" / ("delete-" + backup_id + ".json")
        moved = False
        committed = False
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                row = db.execute("SELECT * FROM backups WHERE account_id=? AND backup_id=? AND state='ready'",
                                 (ACCOUNT_ID, backup_id)).fetchone()
                if row is None:
                    raise ValueError("backup ID is not registered to this account")
                if target.is_symlink() or not target.is_file():
                    raise RuntimeError("backup file is missing or unsafe")
                if tombstone.exists() or tombstone.is_symlink():
                    raise RuntimeError("deletion tombstone already exists; run check --repair")
                actual_size = 0
                digest = hashlib.sha256()
                with target.open("rb") as stream:
                    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                        actual_size += len(chunk)
                        digest.update(chunk)
                if actual_size != row["size_bytes"] or digest.hexdigest() != row["sha256"]:
                    raise RuntimeError("backup file does not match metadata")
                os.replace(target, tombstone)
                moved = True
                db.execute("DELETE FROM backups WHERE account_id=? AND backup_id=?", (ACCOUNT_ID, backup_id))
                db.commit()
                committed = True
            finally:
                if moved and not committed:
                    os.replace(tombstone, target)
        cleanup_pending = False
        try:
            tombstone.unlink()
        except OSError:
            cleanup_pending = True
        return {"backupId": backup_id, "sizeBytes": row["size_bytes"], "cleanupPending": cleanup_pending}

    def _recover_deletions(self) -> None:
        for tombstone in (self.settings.data_dir / "quarantine").glob("delete-*.json"):
            backup_id = tombstone.name.removeprefix("delete-").removesuffix(".json")
            try:
                target = self.path_for(backup_id)
            except ValueError:
                continue
            if tombstone.is_symlink():
                continue
            row = self.get(backup_id)
            if row is None:
                tombstone.unlink()
            elif not target.exists() and not target.is_symlink():
                os.replace(tombstone, target)

    def consistency(self, repair: bool = False) -> dict:
        if repair:
            self._recover_deletions()
        with self.connect() as db:
            rows = [dict(r) for r in db.execute("SELECT * FROM backups WHERE account_id=?", (ACCOUNT_ID,))]
        registered = {r["backup_id"] + ".json" for r in rows}
        missing, corrupt, orphan, temp = [], [], [], []
        for row in rows:
            path = self.path_for(row["backup_id"])
            if not path.is_file() or path.is_symlink():
                missing.append(row["backup_id"])
                continue
            hasher = hashlib.sha256()
            count = 0
            with path.open("rb") as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    count += len(chunk)
                    hasher.update(chunk)
            if count != row["size_bytes"] or hasher.hexdigest() != row["sha256"]:
                corrupt.append(row["backup_id"])
        files = self.settings.data_dir / "files" / ACCOUNT_ID
        for path in files.iterdir():
            if path.name not in registered:
                orphan.append(path.name)
                if repair:
                    os.replace(path, self.settings.data_dir / "quarantine" / (uuid.uuid4().hex + ".orphan"))
        for path in (self.settings.data_dir / "tmp").iterdir():
            temp.append(path.name)
            if repair and path.is_file() and not path.is_symlink():
                path.unlink()
        return {"missing": missing, "corrupt": corrupt, "orphan": orphan, "temporary": temp}

    def capacity(self) -> dict:
        with self.connect() as db:
            count, used = db.execute("SELECT COUNT(*),COALESCE(SUM(size_bytes),0) FROM backups WHERE account_id=?", (ACCOUNT_ID,)).fetchone()
        return {"count": count, "usedBytes": used, "quotaBytes": self.settings.quota_bytes,
                "freeDiskBytes": shutil.disk_usage(self.settings.data_dir).free}
