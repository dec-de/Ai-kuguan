import hashlib
import os
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi.testclient import TestClient

from backup_server.api import create_app
from backup_server.chunked import ChunkUploads
from backup_server.core import Settings


@pytest.fixture
def kit(tmp_path):
    settings = Settings(tmp_path, tmp_path / "db.sqlite3", max_upload_bytes=12,
                        quota_bytes=1000, rate_limit_per_minute=0, max_request_bytes=4,
                        upload_ttl_seconds=60)
    app = create_app(settings)
    token = app.state.store.create_token("test-device")
    with TestClient(app) as client:
        yield client, app, {"Authorization": "Bearer " + token}


def put(client, auth, backup_id, index, data, whole, overrides=None):
    headers = auth | {"Content-Type": "application/octet-stream",
                      "Content-Length": str(len(data)),
                      "X-Chunk-SHA256": hashlib.sha256(data).hexdigest(),
                      "X-Upload-Length": str(len(whole)),
                      "X-Upload-SHA256": hashlib.sha256(whole).hexdigest()}
    return client.put(f"/api/v1/uploads/{backup_id}/chunks/{index}", content=data,
                      headers=headers | (overrides or {}))


def test_chunk_protocol_resume_and_complete(kit):
    client, app, auth = kit
    backup_id = str(uuid.uuid4())
    whole = b"abcdefghijkl"  # Three fixed 4-byte chunks.
    caps = client.get("/api/v1/capabilities", headers=auth).json()
    assert caps["maxRequestBytes"] == 4 and caps["chunkUpload"] is True
    assert client.get(f"/api/v1/uploads/{backup_id}", headers=auth).status_code == 404
    assert put(client, auth, backup_id, 0, b"abcd", whole).status_code == 201
    assert put(client, auth, backup_id, 0, b"abcd", whole).status_code == 200
    status = client.get(f"/api/v1/uploads/{backup_id}", headers=auth).json()
    assert status["completed"] is False and status["receivedChunks"] == [0]
    missing = client.post(f"/api/v1/uploads/{backup_id}/complete", headers=auth)
    assert missing.status_code == 409 and missing.json()["code"] == "MISSING_CHUNKS"
    assert put(client, auth, backup_id, 2, b"ijkl", whole).status_code == 201
    assert put(client, auth, backup_id, 1, b"efgh", whole).status_code == 201
    finished = client.post(f"/api/v1/uploads/{backup_id}/complete", headers=auth)
    assert finished.status_code == 201, finished.text
    assert finished.json()["sha256"] == hashlib.sha256(whole).hexdigest()
    assert client.post(f"/api/v1/uploads/{backup_id}/complete", headers=auth).status_code == 200
    status = client.get(f"/api/v1/uploads/{backup_id}", headers=auth).json()
    assert status["completed"] is True and status["backup"]["sizeBytes"] == len(whole)
    assert status["receivedChunks"] == [0, 1, 2]
    assert put(client, auth, backup_id, 0, b"abcd", whole).status_code == 200
    assert client.get(f"/api/v1/backups/{backup_id}", headers=auth).content == whole
    assert app.state.store.capacity()["count"] == 1


def test_chunk_conflicts_and_hashes(kit):
    client, app, auth = kit
    backup_id = str(uuid.uuid4())
    whole = b"abcdefgh"
    assert put(client, auth, backup_id, 0, b"abcd", whole).status_code == 201
    assert put(client, auth, backup_id, 0, b"xxxx", whole).status_code == 409
    assert put(client, auth, backup_id, 1, b"efgh", b"different").status_code == 409
    assert put(client, auth, backup_id, 1, b"efgh", whole,
               {"X-Chunk-SHA256": "0" * 64}).status_code == 422
    assert put(client, auth, backup_id, 1, b"xxxx", whole).status_code == 201
    response = client.post(f"/api/v1/uploads/{backup_id}/complete", headers=auth)
    assert response.status_code == 422 and response.json()["code"] == "SHA256_MISMATCH"
    assert app.state.store.get(backup_id) is None
    assert client.get(f"/api/v1/uploads/{backup_id}", headers=auth).json()["completed"] is False


def test_chunk_lengths_and_limits(kit):
    client, _, auth = kit
    backup_id = str(uuid.uuid4())
    whole = b"abcdef"
    assert put(client, auth, backup_id, 0, b"abc", whole).status_code == 400
    assert put(client, auth, backup_id, 0, b"abcde", whole).status_code == 413
    assert put(client, auth, backup_id, 2, b"xx", whole).status_code == 400
    assert put(client, auth, backup_id, 0, b"abcd", b"x" * 13).status_code == 413
    assert put(client, auth, backup_id, "01", b"abcd", whole).status_code == 400
    assert put(client, auth, backup_id, 0, b"abcd", whole,
               {"X-Upload-SHA256": "bad"}).status_code == 400
    assert put(client, auth, backup_id, 0, b"abcd", whole,
               {"Content-Type": "application/json"}).status_code == 400


def test_expiration_and_cleanup(kit):
    client, app, auth = kit
    backup_id = str(uuid.uuid4())
    assert put(client, auth, backup_id, 0, b"abcd", b"abcdefgh").status_code == 201
    path = app.state.chunks.chunk_path(backup_id, 0)
    assert path.exists()
    with app.state.store.connect() as db:
        db.execute("UPDATE upload_sessions SET expires_at=? WHERE backup_id=?", (time.time() - 1, backup_id))
    assert client.get(f"/api/v1/uploads/{backup_id}", headers=auth).status_code == 404
    assert not path.exists()
    assert put(client, auth, backup_id, 0, b"abcd", b"abcdefgh").status_code == 201
    orphan = app.state.chunks.root / str(uuid.uuid4())
    orphan.mkdir()
    (orphan / "0.chunk").write_bytes(b"orphan")
    old_temp = app.state.store.settings.data_dir / "tmp" / "old.part"
    old_temp.write_bytes(b"old")
    os.utime(old_temp, (time.time() - 1000, time.time() - 1000))
    result = app.state.chunks.cleanup_orphans()
    assert result["orphanDirectories"] == 1 and result["oldTemporaryFiles"] == 1
    assert not orphan.exists() and not old_temp.exists()


def test_missing_or_corrupt_chunk_on_complete(kit):
    client, app, auth = kit
    backup_id = str(uuid.uuid4())
    whole = b"abcdefgh"
    assert put(client, auth, backup_id, 0, b"abcd", whole).status_code == 201
    assert put(client, auth, backup_id, 1, b"efgh", whole).status_code == 201
    app.state.chunks.chunk_path(backup_id, 1).write_bytes(b"XXXX")
    assert client.post(f"/api/v1/uploads/{backup_id}/complete", headers=auth).status_code == 422
    assert app.state.store.get(backup_id) is None
    app.state.chunks.chunk_path(backup_id, 1).unlink()
    assert client.post(f"/api/v1/uploads/{backup_id}/complete", headers=auth).status_code == 409


def test_staging_quota_rejects_new_chunk(tmp_path):
    settings = Settings(tmp_path, tmp_path / "db.sqlite3", max_upload_bytes=12,
                        quota_bytes=5, rate_limit_per_minute=0, max_request_bytes=4)
    app = create_app(settings)
    token = app.state.store.create_token("test-device")
    auth = {"Authorization": "Bearer " + token}
    with TestClient(app) as client:
        first = str(uuid.uuid4())
        second = str(uuid.uuid4())
        assert put(client, auth, first, 0, b"abcd", b"abcdefgh").status_code == 201
        assert put(client, auth, second, 0, b"wxyz", b"wxyz").status_code == 507
        assert client.get(f"/api/v1/uploads/{first}", headers=auth).json()["receivedChunks"] == [0]


def test_concurrent_chunk_and_complete(kit):
    client, app, auth = kit
    backup_id = str(uuid.uuid4())
    whole = b"abcdefgh"
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda n: put(client, auth, backup_id, n, whole[n * 4:(n + 1) * 4], whole).status_code,
                                [0, 1]))
    assert results == [201, 201]
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda _: client.post(f"/api/v1/uploads/{backup_id}/complete", headers=auth).status_code,
                                range(2)))
    assert sorted(results) in ([200, 201], [201, 409])
    assert app.state.store.capacity()["count"] == 1


@pytest.mark.slow
def test_real_500m_as_fifty_10m_chunks(tmp_path):
    settings = Settings(tmp_path, tmp_path / "db.sqlite3", rate_limit_per_minute=0)
    app = create_app(settings)
    token = app.state.store.create_token("boundary")
    auth = {"Authorization": "Bearer " + token}
    block = b"z" * 1_000_000
    chunk = block * 10
    full = hashlib.sha256()
    for _ in range(50):
        full.update(chunk)
    backup_id = str(uuid.uuid4())
    with TestClient(app) as client:
        capabilities = client.get("/api/v1/capabilities", headers=auth).json()
        assert capabilities["maxRequestBytes"] == 10_000_000
        for index in range(50):
            response = client.put(f"/api/v1/uploads/{backup_id}/chunks/{index}", content=chunk,
                                  headers=auth | {"Content-Type": "application/octet-stream",
                                                  "Content-Length": "10000000",
                                                  "X-Chunk-SHA256": hashlib.sha256(chunk).hexdigest(),
                                                  "X-Upload-Length": "500000000",
                                                  "X-Upload-SHA256": full.hexdigest()})
            assert response.status_code == 201, response.text
        complete = client.post(f"/api/v1/uploads/{backup_id}/complete", headers=auth)
        assert complete.status_code == 201, complete.text
        assert complete.json()["sizeBytes"] == 500_000_000
        assert complete.json()["sha256"] == full.hexdigest()
