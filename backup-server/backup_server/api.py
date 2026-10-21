from __future__ import annotations

import base64
import binascii
import hashlib
import json
import os
import re
import sqlite3
import uuid
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse, StreamingResponse

from .core import ACCOUNT_ID, Settings, Store, utc_now
from .chunked import ChunkError, ChunkUploads

SHA_RE = re.compile(r"^[0-9a-f]{64}$")
CONTENT_TYPE_RE = re.compile(r"^application/json(?:\s*;\s*charset=utf-8)?$", re.I)


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str, headers: dict[str, str] | None = None):
        self.status, self.code, self.message, self.headers = status, code, message, headers or {}


def valid_id(raw: str) -> str:
    try:
        parsed = uuid.UUID(raw)
    except (ValueError, AttributeError):
        raise ApiError(400, "INVALID_BACKUP_ID", "Backup ID must be a canonical UUID v4")
    if parsed.version != 4 or str(parsed) != raw:
        raise ApiError(400, "INVALID_BACKUP_ID", "Backup ID must be a canonical UUID v4")
    return raw


def encode_cursor(value: tuple[str, str]) -> str:
    return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).decode().rstrip("=")


def decode_cursor(raw: str | None) -> tuple[str, str] | None:
    if raw is None:
        return None
    if len(raw) > 512:
        raise ApiError(400, "INVALID_CURSOR", "Invalid cursor")
    try:
        result = json.loads(base64.urlsafe_b64decode(raw + "=" * (-len(raw) % 4)))
        if not isinstance(result, list) or len(result) != 2 or not isinstance(result[0], str):
            raise ValueError()
        valid_id(result[1])
        if len(result[0]) > 40 or not result[0].endswith("Z"):
            raise ValueError()
        return result[0], result[1]
    except (ValueError, binascii.Error, UnicodeDecodeError, TypeError, ApiError):
        raise ApiError(400, "INVALID_CURSOR", "Invalid cursor")


def public_row(row: dict) -> dict:
    return {"backupId": row["backup_id"], "sizeBytes": row["size_bytes"],
            "sha256": row["sha256"], "receivedAt": row["received_at"]}


def create_app(settings: Settings | None = None) -> FastAPI:
    store = Store(settings or Settings.from_env())
    chunks = ChunkUploads(store)
    app = FastAPI(title="Kuguan Backup API", docs_url=None, redoc_url=None, openapi_url=None)
    app.state.store = store
    app.state.chunks = chunks

    @app.middleware("http")
    async def request_id(request: Request, call_next):
        request.state.request_id = uuid.uuid4().hex
        try:
            response = await call_next(request)
        except ApiError as exc:
            response = JSONResponse(status_code=exc.status,
                                    content={"code": exc.code, "message": exc.message,
                                             "requestId": request.state.request_id},
                                    headers=exc.headers)
        except Exception:
            response = JSONResponse(status_code=500,
                                    content={"code": "INTERNAL_ERROR", "message": "Service error",
                                             "requestId": request.state.request_id})
        response.headers["X-Request-ID"] = request.state.request_id
        return response

    @app.exception_handler(HTTPException)
    async def http_error(request: Request, exc: HTTPException):
        status = exc.status_code
        code = "NOT_FOUND" if status == 404 else "BAD_REQUEST"
        return JSONResponse(status_code=status, content={"code": code, "message": str(exc.detail),
                                                       "requestId": request.state.request_id})

    def auth(request: Request) -> tuple[str, str]:
        header = request.headers.get("Authorization", "")
        match = re.fullmatch(r"Bearer ([A-Za-z0-9_-]{1,256})", header)
        if not match:
            raise ApiError(401, "UNAUTHORIZED", "Bearer token required")
        result = store.authenticate(match.group(1))
        if result is None:
            raise ApiError(401, "UNAUTHORIZED", "Invalid token")
        if result[0] == "revoked":
            raise ApiError(403, "TOKEN_REVOKED", "Token revoked")
        retry = store.check_rate(hashlib.sha256(match.group(1).encode()).hexdigest())
        if retry is not None:
            raise ApiError(429, "RATE_LIMITED", "Too many requests", {"Retry-After": str(retry)})
        return result

    @app.get("/healthz")
    def health(request: Request):
        if request.client is None or request.client.host not in ("127.0.0.1", "::1"):
            raise ApiError(404, "NOT_FOUND", "Not found")
        try:
            with store.connect() as db:
                db.execute("SELECT 1").fetchone()
        except sqlite3.Error:
            raise ApiError(503, "UNAVAILABLE", "Service unavailable")
        return {"status": "ok"}

    @app.get("/api/v1/capabilities")
    def capabilities(_identity: tuple[str, str] = Depends(auth)):
        return {"apiVersion": 1, "maxUploadBytes": store.settings.max_upload_bytes,
                "maxRequestBytes": chunks.request_limit, "chunkUpload": True,
                "retentionCount": store.settings.retention_count, "serverTime": utc_now()}

    def chunk_failure(exc: ChunkError):
        mapping = {
            "invalid_size": (400, "INVALID_CHUNK_SIZE", "Invalid chunk index or size"),
            "conflict": (409, "UPLOAD_CONFLICT", "Upload ID or chunk conflicts with stored data"),
            "expired": (404, "UPLOAD_NOT_FOUND", "Upload session expired"),
            "not_found": (404, "UPLOAD_NOT_FOUND", "Upload session not found"),
            "busy": (409, "UPLOAD_IN_PROGRESS", "Upload is being completed"),
            "missing_chunk": (409, "MISSING_CHUNKS", "One or more chunks are missing"),
            "hash_mismatch": (422, "SHA256_MISMATCH", "Upload SHA-256 mismatch"),
            "storage": (507, "INSUFFICIENT_STORAGE", "Storage quota or disk space exhausted"),
            "orphan_chunk": (500, "STORAGE_INCONSISTENT", "Orphan chunk requires cleanup"),
        }
        status, code, message = mapping[exc.kind]
        raise ApiError(status, code, message)

    @app.put("/api/v1/uploads/{backup_id}/chunks/{index}")
    async def put_chunk(backup_id: str, index: str, request: Request,
                        identity: tuple[str, str] = Depends(auth)):
        valid_id(backup_id)
        if not index.isdecimal() or len(index) > 3 or str(int(index)) != index:
            raise ApiError(400, "INVALID_CHUNK_INDEX", "Chunk index must be a zero-based integer")
        index_number = int(index)
        if request.headers.get("content-type", "").lower() != "application/octet-stream":
            raise ApiError(400, "INVALID_CONTENT_TYPE", "Expected application/octet-stream")
        raw_length = request.headers.get("content-length")
        raw_total = request.headers.get("x-upload-length")
        if raw_length is None or not raw_length.isdecimal() or raw_total is None or not raw_total.isdecimal():
            raise ApiError(400, "INVALID_LENGTH", "Content-Length and X-Upload-Length required")
        size, total = int(raw_length), int(raw_total)
        if size > chunks.request_limit or total > store.settings.max_upload_bytes:
            raise ApiError(413, "UPLOAD_TOO_LARGE", "Chunk or total exceeds limit")
        total_sha = request.headers.get("x-upload-sha256", "")
        chunk_sha = request.headers.get("x-chunk-sha256", "")
        if not SHA_RE.fullmatch(total_sha) or not SHA_RE.fullmatch(chunk_sha):
            raise ApiError(400, "INVALID_SHA256", "Lowercase total and chunk SHA-256 required")
        try:
            expected = chunks.expected_size(total, index_number)
        except ChunkError as exc:
            chunk_failure(exc)
        if size != expected:
            raise ApiError(400, "INVALID_CHUNK_SIZE", "Chunk length differs from fixed chunk layout")
        chunks.cleanup_expired()
        try:
            already = chunks.completed(backup_id, total, total_sha)
        except ChunkError as exc:
            chunk_failure(exc)
        if already:
            return {"backupId": backup_id, "chunkIndex": index_number, "sizeBytes": size,
                    "sha256": chunk_sha, "alreadyComplete": True}
        fd, temp = store.new_temp()
        received = 0
        digest = hashlib.sha256()
        try:
            with os.fdopen(fd, "wb") as output:
                async for data in request.stream():
                    received += len(data)
                    if received > chunks.request_limit:
                        raise ApiError(413, "UPLOAD_TOO_LARGE", "Chunk exceeds request limit")
                    if received > size:
                        raise ApiError(400, "LENGTH_MISMATCH", "Chunk exceeds Content-Length")
                    digest.update(data)
                    output.write(data)
                output.flush()
                os.fsync(output.fileno())
            if received != size:
                raise ApiError(400, "LENGTH_MISMATCH", "Chunk length differs from Content-Length")
            if digest.hexdigest() != chunk_sha:
                raise ApiError(422, "SHA256_MISMATCH", "Chunk SHA-256 mismatch")
            try:
                created = chunks.put(backup_id, index_number, temp, size, chunk_sha, total, total_sha, identity[1])
            except ChunkError as exc:
                chunk_failure(exc)
            return JSONResponse(status_code=201 if created else 200,
                                content={"backupId": backup_id, "chunkIndex": index_number,
                                         "sizeBytes": size, "sha256": chunk_sha,
                                         "alreadyComplete": False})
        finally:
            temp.unlink(missing_ok=True)

    @app.get("/api/v1/uploads/{backup_id}")
    def upload_status(backup_id: str, identity: tuple[str, str] = Depends(auth)):
        valid_id(backup_id)
        chunks.cleanup_expired()
        try:
            return chunks.status(backup_id, identity[1])
        except ChunkError as exc:
            chunk_failure(exc)

    @app.post("/api/v1/uploads/{backup_id}/complete")
    def complete_upload(backup_id: str, identity: tuple[str, str] = Depends(auth)):
        valid_id(backup_id)
        chunks.cleanup_expired()
        try:
            row, created = chunks.complete(backup_id, identity[1])
        except ChunkError as exc:
            chunk_failure(exc)
        if created:
            try:
                store.prune()
            except (OSError, sqlite3.Error):
                pass
        return JSONResponse(status_code=201 if created else 200, content=public_row(row))

    @app.put("/api/v1/backups/{backup_id}")
    async def upload(backup_id: str, request: Request, identity: tuple[str, str] = Depends(auth)):
        valid_id(backup_id)
        if not CONTENT_TYPE_RE.fullmatch(request.headers.get("content-type", "")):
            raise ApiError(400, "INVALID_CONTENT_TYPE", "Expected UTF-8 JSON content type")
        content_length = request.headers.get("content-length")
        if content_length is None or not content_length.isdecimal():
            raise ApiError(400, "CONTENT_LENGTH_REQUIRED", "Valid Content-Length required")
        expected_length = int(content_length)
        if expected_length > store.settings.max_upload_bytes:
            raise ApiError(413, "UPLOAD_TOO_LARGE", "Backup exceeds upload limit")
        expected_hash = request.headers.get("x-content-sha256", "")
        if not SHA_RE.fullmatch(expected_hash):
            raise ApiError(400, "INVALID_SHA256", "Expected lowercase SHA-256")
        fd, temp = store.new_temp()
        size = 0
        digest = hashlib.sha256()
        try:
            with os.fdopen(fd, "wb") as output:
                async for chunk in request.stream():
                    size += len(chunk)
                    if size > store.settings.max_upload_bytes:
                        raise ApiError(413, "UPLOAD_TOO_LARGE", "Backup exceeds upload limit")
                    if size > expected_length:
                        raise ApiError(400, "LENGTH_MISMATCH", "Body exceeds Content-Length")
                    digest.update(chunk)
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
            if size != expected_length:
                raise ApiError(400, "LENGTH_MISMATCH", "Body length differs from Content-Length")
            if digest.hexdigest() != expected_hash:
                raise ApiError(422, "SHA256_MISMATCH", "Backup SHA-256 mismatch")
            try:
                row, created = store.commit(backup_id, temp, size, expected_hash, identity[1])
            except FileExistsError:
                raise ApiError(409, "BACKUP_ID_CONFLICT", "Backup ID already contains different data")
            except OSError:
                raise ApiError(507, "INSUFFICIENT_STORAGE", "Storage quota or disk space exhausted")
            except RuntimeError:
                raise ApiError(500, "STORAGE_INCONSISTENT", "Storage consistency check required")
            if created:
                try:
                    store.prune()
                except (OSError, sqlite3.Error):
                    pass  # The new committed backup remains valid; maintenance can retry pruning.
            return JSONResponse(status_code=201 if created else 200, content=public_row(row))
        finally:
            temp.unlink(missing_ok=True)

    @app.get("/api/v1/backups")
    def list_backups(request: Request, _identity: tuple[str, str] = Depends(auth)):
        raw_limit = request.query_params.get("limit", "30")
        if not raw_limit.isdecimal() or not 1 <= int(raw_limit) <= 100:
            raise ApiError(400, "INVALID_LIMIT", "Limit must be 1-100")
        cursor = decode_cursor(request.query_params.get("cursor"))
        rows, next_key = store.list_backups(int(raw_limit), cursor)
        return {"items": [public_row(row) for row in rows],
                "nextCursor": encode_cursor(next_key) if next_key else None}

    @app.get("/api/v1/backups/{backup_id}")
    def download(backup_id: str, _identity: tuple[str, str] = Depends(auth)):
        valid_id(backup_id)
        row = store.get(backup_id)
        if row is None:
            raise ApiError(404, "BACKUP_NOT_FOUND", "Backup not found")
        path = store.path_for(backup_id)
        if path.is_symlink() or not path.is_file():
            raise ApiError(503, "BACKUP_UNAVAILABLE", "Backup file unavailable")
        if path.stat().st_size != row["size_bytes"]:
            raise ApiError(503, "BACKUP_UNAVAILABLE", "Backup file size mismatch")

        def chunks():
            with path.open("rb") as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    yield chunk

        return StreamingResponse(chunks(), media_type="application/json; charset=utf-8",
                                 headers={"Content-Length": str(row["size_bytes"]),
                                          "X-Content-SHA256": row["sha256"]})

    return app


