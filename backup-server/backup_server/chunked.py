"""Crash-recoverable, fixed-size chunk staging for the v1 API."""
from __future__ import annotations

import hashlib
import os
import shutil
import sqlite3
import time
from pathlib import Path

from .core import ACCOUNT_ID, Store


class ChunkError(Exception):
    def __init__(self, kind: str):
        self.kind = kind


class ChunkUploads:
    def __init__(self, store: Store):
        self.store = store
        if store.settings.max_request_bytes < 1:
            raise ValueError("chunk request limit must be positive")
        self.request_limit = min(store.settings.max_request_bytes, store.settings.max_upload_bytes)
        if store.settings.upload_ttl_seconds < 1:
            raise ValueError("upload TTL must be positive")
        self.root = store.settings.data_dir / "uploads" / ACCOUNT_ID
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        if self.root.is_symlink():
            raise ValueError("upload directory must not be a symbolic link")
        with store.connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS upload_sessions (
                    account_id TEXT NOT NULL, backup_id TEXT NOT NULL,
                    total_size INTEGER NOT NULL, sha256 TEXT NOT NULL,
                    device_id TEXT NOT NULL, expires_at REAL NOT NULL,
                    state TEXT NOT NULL CHECK(state IN ('open','completing')),
                    claimed_at REAL,
                    PRIMARY KEY(account_id,backup_id)
                );
                CREATE TABLE IF NOT EXISTS upload_chunks (
                    account_id TEXT NOT NULL, backup_id TEXT NOT NULL,
                    chunk_index INTEGER NOT NULL, size_bytes INTEGER NOT NULL,
                    sha256 TEXT NOT NULL,
                    PRIMARY KEY(account_id,backup_id,chunk_index),
                    FOREIGN KEY(account_id,backup_id) REFERENCES upload_sessions(account_id,backup_id) ON DELETE CASCADE
                );
            """)

    def count(self, total: int) -> int:
        chunk = self.request_limit
        return max(1, (total + chunk - 1) // chunk)

    def expected_size(self, total: int, index: int) -> int:
        if total < 1 or total > self.store.settings.max_upload_bytes or index < 0 or index >= self.count(total):
            raise ChunkError("invalid_size")
        return min(self.request_limit, total - index * self.request_limit)

    def directory(self, backup_id: str) -> Path:
        return self.root / backup_id

    def chunk_path(self, backup_id: str, index: int) -> Path:
        return self.directory(backup_id) / f"{index}.chunk"

    def _session(self, db: sqlite3.Connection, backup_id: str):
        return db.execute("SELECT * FROM upload_sessions WHERE account_id=? AND backup_id=?",
                          (ACCOUNT_ID, backup_id)).fetchone()

    def _validate_session(self, row, total: int, sha: str, device_id: str) -> None:
        if row["total_size"] != total or row["sha256"] != sha or row["device_id"] != device_id:
            raise ChunkError("conflict")
        if row["expires_at"] <= time.time():
            raise ChunkError("expired")
        if row["state"] != "open":
            raise ChunkError("busy")

    def completed(self, backup_id: str, total: int, sha: str) -> dict | None:
        row = self.store.get(backup_id)
        if row is None:
            return None
        if row["size_bytes"] != total or row["sha256"] != sha:
            raise ChunkError("conflict")
        return row

    def put(self, backup_id: str, index: int, temp: Path, size: int, chunk_sha: str,
            total: int, total_sha: str, device_id: str) -> bool:
        if self.completed(backup_id, total, total_sha):
            return False
        if size != self.expected_size(total, index):
            raise ChunkError("invalid_size")
        with self.store.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            # Serialize against a concurrent complete/PUT after the initial quick check.
            if self.completed(backup_id, total, total_sha):
                return False
            session = self._session(db, backup_id)
            if session is None:
                expires = time.time() + self.store.settings.upload_ttl_seconds
                db.execute("INSERT INTO upload_sessions VALUES (?,?,?,?,?,?, 'open',NULL)",
                           (ACCOUNT_ID, backup_id, total, total_sha, device_id, expires))
            else:
                self._validate_session(session, total, total_sha, device_id)
            previous = db.execute("SELECT size_bytes,sha256 FROM upload_chunks WHERE account_id=? AND backup_id=? AND chunk_index=?",
                                  (ACCOUNT_ID, backup_id, index)).fetchone()
            if previous:
                if previous["size_bytes"] == size and previous["sha256"] == chunk_sha:
                    path = self.chunk_path(backup_id, index)
                    if path.is_file() and not path.is_symlink():
                        return False
                    raise ChunkError("missing_chunk")
                raise ChunkError("conflict")
            committed_bytes = db.execute("SELECT COALESCE(SUM(size_bytes),0) FROM backups WHERE account_id=?",
                                         (ACCOUNT_ID,)).fetchone()[0]
            staged_bytes = db.execute("SELECT COALESCE(SUM(size_bytes),0) FROM upload_chunks WHERE account_id=?",
                                      (ACCOUNT_ID,)).fetchone()[0]
            if committed_bytes + staged_bytes + size > self.store.settings.quota_bytes:
                raise ChunkError("storage")
            if shutil.disk_usage(self.store.settings.data_dir).free < max(size, 1_000_000):
                raise ChunkError("storage")
            folder = self.directory(backup_id)
            folder.mkdir(exist_ok=True, mode=0o700)
            if folder.is_symlink():
                raise ChunkError("storage")
            path = self.chunk_path(backup_id, index)
            if path.exists() or path.is_symlink():
                raise ChunkError("orphan_chunk")
            os.replace(temp, path)
            os.chmod(path, 0o600)
            db.execute("INSERT INTO upload_chunks VALUES (?,?,?,?,?)",
                       (ACCOUNT_ID, backup_id, index, size, chunk_sha))
            db.execute("UPDATE upload_sessions SET expires_at=? WHERE account_id=? AND backup_id=?",
                       (time.time() + self.store.settings.upload_ttl_seconds, ACCOUNT_ID, backup_id))
        return True

    def status(self, backup_id: str, device_id: str) -> dict:
        committed = self.store.get(backup_id)
        if committed:
            total = committed["size_bytes"]
            return {"backupId": backup_id, "totalSizeBytes": total, "sha256": committed["sha256"],
                    "chunkCount": self.count(total), "receivedChunks": list(range(self.count(total))),
                    "expiresAt": None, "completed": True,
                    "backup": {"backupId": backup_id, "sizeBytes": total,
                               "sha256": committed["sha256"], "receivedAt": committed["received_at"]}}
        with self.store.connect() as db:
            session = self._session(db, backup_id)
            if session is None or session["expires_at"] <= time.time():
                raise ChunkError("not_found")
            if session["device_id"] != device_id:
                raise ChunkError("conflict")
            indexes = [row[0] for row in db.execute(
                "SELECT chunk_index FROM upload_chunks WHERE account_id=? AND backup_id=? ORDER BY chunk_index",
                (ACCOUNT_ID, backup_id))]
        from datetime import datetime, timezone
        expires = datetime.fromtimestamp(session["expires_at"], timezone.utc).isoformat().replace("+00:00", "Z")
        return {"backupId": backup_id, "totalSizeBytes": session["total_size"], "sha256": session["sha256"],
                "chunkCount": self.count(session["total_size"]), "receivedChunks": indexes,
                "expiresAt": expires, "completed": False, "backup": None}

    def complete(self, backup_id: str, device_id: str) -> tuple[dict, bool]:
        committed = self.store.get(backup_id)
        if committed:
            return committed, False
        with self.store.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            session = self._session(db, backup_id)
            if session is None or session["expires_at"] <= time.time():
                raise ChunkError("not_found")
            if session["device_id"] != device_id:
                raise ChunkError("conflict")
            if session["state"] == "completing":
                if session["claimed_at"] and session["claimed_at"] < time.time() - 600:
                    db.execute("UPDATE upload_sessions SET state='open',claimed_at=NULL WHERE account_id=? AND backup_id=?",
                               (ACCOUNT_ID, backup_id))
                else:
                    raise ChunkError("busy")
            rows = db.execute("SELECT chunk_index,size_bytes,sha256 FROM upload_chunks WHERE account_id=? AND backup_id=? ORDER BY chunk_index",
                              (ACCOUNT_ID, backup_id)).fetchall()
            if [r["chunk_index"] for r in rows] != list(range(self.count(session["total_size"]))):
                raise ChunkError("missing_chunk")
            db.execute("UPDATE upload_sessions SET state='completing',claimed_at=? WHERE account_id=? AND backup_id=?",
                       (time.time(), ACCOUNT_ID, backup_id))
        fd, temp = self.store.new_temp()
        try:
            digest = hashlib.sha256()
            size = 0
            with os.fdopen(fd, "wb") as output:
                for row in rows:
                    path = self.chunk_path(backup_id, row["chunk_index"])
                    if path.is_symlink() or not path.is_file():
                        raise ChunkError("missing_chunk")
                    part_digest = hashlib.sha256()
                    part_size = 0
                    with path.open("rb") as stream:
                        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                            part_size += len(chunk)
                            size += len(chunk)
                            if size > session["total_size"]:
                                raise ChunkError("hash_mismatch")
                            part_digest.update(chunk)
                            digest.update(chunk)
                            output.write(chunk)
                    if part_size != row["size_bytes"] or part_digest.hexdigest() != row["sha256"]:
                        raise ChunkError("hash_mismatch")
                output.flush()
                os.fsync(output.fileno())
            if size != session["total_size"] or digest.hexdigest() != session["sha256"]:
                raise ChunkError("hash_mismatch")
            try:
                result = self.store.commit(backup_id, temp, size, digest.hexdigest(), device_id)
            except FileExistsError:
                raise ChunkError("conflict")
            except OSError:
                raise ChunkError("storage")
            self._remove_session(backup_id)
            return result
        except Exception:
            with self.store.connect() as db:
                db.execute("UPDATE upload_sessions SET state='open',claimed_at=NULL WHERE account_id=? AND backup_id=?",
                           (ACCOUNT_ID, backup_id))
            raise
        finally:
            temp.unlink(missing_ok=True)

    def _remove_session(self, backup_id: str) -> None:
        with self.store.connect() as db:
            db.execute("DELETE FROM upload_sessions WHERE account_id=? AND backup_id=?", (ACCOUNT_ID, backup_id))
        shutil.rmtree(self.directory(backup_id), ignore_errors=True)

    def cleanup_expired(self) -> int:
        now = time.time()
        with self.store.connect() as db:
            rows = db.execute("SELECT backup_id FROM upload_sessions WHERE account_id=? AND expires_at<=?",
                              (ACCOUNT_ID, now)).fetchall()
            db.execute("DELETE FROM upload_sessions WHERE account_id=? AND expires_at<=?", (ACCOUNT_ID, now))
        for row in rows:
            shutil.rmtree(self.directory(row["backup_id"]), ignore_errors=True)
        return len(rows)

    def cleanup_orphans(self) -> dict:
        """Offline maintenance: run only while the API service is stopped."""
        expired = self.cleanup_expired()
        with self.store.connect() as db:
            active = {row[0] for row in db.execute(
                "SELECT backup_id FROM upload_sessions WHERE account_id=?", (ACCOUNT_ID,))}
        orphan_dirs = 0
        for path in self.root.iterdir():
            if path.name not in active and path.is_dir() and not path.is_symlink():
                shutil.rmtree(path)
                orphan_dirs += 1
        old_temps = 0
        cutoff = time.time() - self.store.settings.upload_ttl_seconds
        for path in (self.store.settings.data_dir / "tmp").iterdir():
            if path.is_file() and not path.is_symlink() and path.stat().st_mtime < cutoff:
                path.unlink()
                old_temps += 1
        return {"expiredSessions": expired, "orphanDirectories": orphan_dirs, "oldTemporaryFiles": old_temps}
