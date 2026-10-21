import hashlib
import json
import os
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi.testclient import TestClient

from backup_server.api import create_app
from backup_server.core import MAX_UPLOAD_BYTES, Settings, Store, token_digest
from backup_server.cli import export_archive, main as cli_main, restore_archive


@pytest.fixture
def environment(tmp_path):
    settings = Settings(tmp_path, tmp_path / "db.sqlite3", max_upload_bytes=64,
                        quota_bytes=1_000, retention_count=30, rate_limit_per_minute=0)
    app = create_app(settings)
    token = app.state.store.create_token("test_device")
    with TestClient(app) as client:
        yield client, app.state.store, token


def headers(token, body):
    return {"Authorization": "Bearer " + token, "Content-Type": "application/json; charset=utf-8",
            "Content-Length": str(len(body)), "X-Content-SHA256": hashlib.sha256(body).hexdigest()}


def upload(client, token, backup_id, body=b"{}", extra=None):
    return client.put("/api/v1/backups/" + backup_id, content=body,
                      headers=headers(token, body) | (extra or {}))


def test_auth_capabilities_and_revoke(environment):
    client, store, token = environment
    assert client.get("/api/v1/capabilities").status_code == 401
    assert client.get("/api/v1/capabilities", headers={"Authorization": "Bearer fake"}).status_code == 401
    other = store.create_token("other_device")
    assert client.get("/api/v1/capabilities", headers={"Authorization": "Bearer " + token}).json()["maxUploadBytes"] == 64
    with store.connect() as db:
        assert db.execute("SELECT COUNT(*) FROM tokens WHERE digest=?", (token_digest(token),)).fetchone()[0] == 1
        assert token not in str(db.execute("SELECT * FROM tokens").fetchall())
    assert store.revoke_token(token_digest(token)[:12])
    assert client.get("/api/v1/capabilities", headers={"Authorization": "Bearer " + token}).status_code == 403
    assert client.get("/api/v1/capabilities", headers={"Authorization": "Bearer " + other}).status_code == 200


def test_upload_download_idempotence_and_conflict(environment):
    client, store, token = environment
    backup_id = str(uuid.uuid4())
    body = b'{"schemaVersion":3}'
    first = upload(client, token, backup_id, body)
    assert first.status_code == 201, first.text
    assert first.json()["sizeBytes"] == len(body)
    assert upload(client, token, backup_id, body).status_code == 200
    assert upload(client, token, backup_id, b"different").status_code == 409
    got = client.get("/api/v1/backups/" + backup_id, headers={"Authorization": "Bearer " + token})
    assert got.status_code == 200 and got.content == body
    assert got.headers["content-length"] == str(len(body))
    assert got.headers["x-content-sha256"] == hashlib.sha256(body).hexdigest()
    assert store.capacity()["count"] == 1


@pytest.mark.parametrize("bad_id", ["../etc/passwd", "A" * 300, str(uuid.uuid1()), "550e8400-e29b-41d4-a716-446655440000/other"])
def test_invalid_id(environment, bad_id):
    client, _, token = environment
    assert upload(client, token, bad_id).status_code in (400, 404)


def test_headers_length_hash_and_boundaries(environment):
    client, store, token = environment
    backup_id = str(uuid.uuid4())
    body = b"x" * 64
    assert upload(client, token, backup_id, body).status_code == 201
    assert upload(client, token, str(uuid.uuid4()), body + b"x").status_code == 413
    assert upload(client, token, str(uuid.uuid4()), b"x" * 63).status_code == 201
    assert upload(client, token, str(uuid.uuid4()), b"a", {"X-Content-SHA256": "0" * 64}).status_code == 422
    assert upload(client, token, str(uuid.uuid4()), b"a", {"Content-Length": "2"}).status_code == 400
    assert upload(client, token, str(uuid.uuid4()), b"a", {"Content-Type": "text/plain"}).status_code == 400
    request = client.build_request("PUT", "/api/v1/backups/" + str(uuid.uuid4()), content=b"a", headers=headers(token, b"a"))
    del request.headers["content-length"]
    assert client.send(request).status_code == 400
    assert list((store.settings.data_dir / "tmp").iterdir()) == []


def test_quota_retention_and_stable_pagination(tmp_path):
    settings = Settings(tmp_path, tmp_path / "db.sqlite3", max_upload_bytes=100, quota_bytes=500,
                        retention_count=3, rate_limit_per_minute=0)
    app = create_app(settings)
    store = app.state.store
    token = store.create_token("device")
    with TestClient(app) as client:
        ids = []
        for i in range(5):
            backup_id = str(uuid.uuid4())
            ids.append(backup_id)
            assert upload(client, token, backup_id, str(i).encode()).status_code == 201
        first = client.get("/api/v1/backups?limit=2", headers={"Authorization": "Bearer " + token}).json()
        assert [x["backupId"] for x in first["items"]] == ids[-1:-3:-1]
        second = client.get("/api/v1/backups?limit=2&cursor=" + first["nextCursor"],
                            headers={"Authorization": "Bearer " + token}).json()
        assert [x["backupId"] for x in second["items"]] == [ids[-3]]
        assert second["nextCursor"] is None
        assert client.get("/api/v1/backups?limit=0", headers={"Authorization": "Bearer " + token}).status_code == 400
        assert client.get("/api/v1/backups?cursor=bad", headers={"Authorization": "Bearer " + token}).status_code == 400
        assert store.capacity()["count"] == 3


def test_quota_failure_keeps_existing(environment):
    client, store, token = environment
    first = str(uuid.uuid4())
    assert upload(client, token, first, b"x" * 40).status_code == 201
    # A quota smaller than current use plus new upload is rejected without altering existing data.
    store.settings = Settings(store.settings.data_dir, store.settings.db_path, 64, 50, 30, 0)
    assert upload(client, token, str(uuid.uuid4()), b"y" * 20).status_code == 507
    assert store.get(first) is not None
    assert list((store.settings.data_dir / "tmp").iterdir()) == []


def test_missing_corrupt_orphan_and_repair(environment):
    client, store, token = environment
    backup_id = str(uuid.uuid4())
    assert upload(client, token, backup_id, b"abc").status_code == 201
    path = store.path_for(backup_id)
    path.write_bytes(b"xyz")
    assert store.consistency()["corrupt"] == [backup_id]
    path.unlink()
    assert client.get("/api/v1/backups/" + backup_id, headers={"Authorization": "Bearer " + token}).status_code == 503
    assert store.consistency()["missing"] == [backup_id]
    orphan = path.parent / (str(uuid.uuid4()) + ".json")
    orphan.write_bytes(b"orphan")
    temp = store.settings.data_dir / "tmp" / "interrupted.part"
    temp.write_bytes(b"partial")
    findings = store.consistency(repair=True)
    assert orphan.name in findings["orphan"] and temp.name in findings["temporary"]
    assert not orphan.exists() and not temp.exists()


def test_concurrent_same_id(tmp_path):
    settings = Settings(tmp_path, tmp_path / "db.sqlite3", max_upload_bytes=64,
                        quota_bytes=1000, rate_limit_per_minute=0)
    app = create_app(settings)
    token = app.state.store.create_token("device")
    backup_id = str(uuid.uuid4())
    with TestClient(app) as client, ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda body: upload(client, token, backup_id, body).status_code, [b"A", b"B"]))
    assert sorted(results) == [201, 409]
    assert app.state.store.capacity()["count"] == 1


def test_rate_limit_and_error_body(tmp_path):
    settings = Settings(tmp_path, tmp_path / "db.sqlite3", rate_limit_per_minute=1)
    app = create_app(settings)
    token = app.state.store.create_token("device")
    with TestClient(app) as client:
        auth = {"Authorization": "Bearer " + token}
        assert client.get("/api/v1/capabilities", headers=auth).status_code == 200
        response = client.get("/api/v1/capabilities", headers=auth)
        assert response.status_code == 429
        assert int(response.headers["retry-after"]) >= 1
        assert set(response.json()) == {"code", "message", "requestId"}
        assert token not in response.text


def test_default_exact_500m_configuration(tmp_path):
    settings = Settings(tmp_path, tmp_path / "db.sqlite3")
    assert settings.max_upload_bytes == MAX_UPLOAD_BYTES == 500_000_000


def test_service_and_cli_read_same_config_file(tmp_path, monkeypatch):
    env_file = tmp_path / "service.env"
    data = tmp_path / "configured-data"
    env_file.write_text(f"KUGUAN_DATA_DIR={data}\nKUGUAN_QUOTA_BYTES=4000000000\n", encoding="utf-8")
    monkeypatch.setenv("KUGUAN_ENV_FILE", str(env_file))
    assert Settings.from_env().data_dir == data
    assert Settings.from_env().quota_bytes == 4_000_000_000
    monkeypatch.setenv("KUGUAN_QUOTA_BYTES", "3000000000")
    assert Settings.from_env().quota_bytes == 3_000_000_000


def test_admin_delete_only_registered_consistent_file(environment, monkeypatch, capsys):
    client, store, token = environment
    backup_id = str(uuid.uuid4())
    assert upload(client, token, backup_id, b"synthetic").status_code == 201
    env_file = store.settings.data_dir / "service.env"
    env_file.write_text(f"KUGUAN_DATA_DIR={store.settings.data_dir}\nKUGUAN_DB_PATH={store.settings.db_path}\n")
    monkeypatch.setenv("KUGUAN_ENV_FILE", str(env_file))
    assert cli_main(["backup-delete", backup_id]) == 0
    assert json.loads(capsys.readouterr().out)["backupId"] == backup_id
    assert store.get(backup_id) is None and not store.path_for(backup_id).exists()
    assert client.request("DELETE", "/api/v1/backups/" + backup_id,
                          headers={"Authorization": "Bearer " + token}).status_code == 405
    assert cli_main(["backup-delete", backup_id]) == 1
    assert cli_main(["backup-delete", "../escape"]) == 1


def test_admin_delete_rejects_corruption_and_symlink(environment):
    client, store, token = environment
    backup_id = str(uuid.uuid4())
    assert upload(client, token, backup_id, b"synthetic").status_code == 201
    path = store.path_for(backup_id)
    path.write_bytes(b"tampered!")
    with pytest.raises(RuntimeError):
        store.delete_backup(backup_id)
    assert store.get(backup_id) is not None and path.exists()
    path.unlink()
    with pytest.raises(RuntimeError):
        store.delete_backup(backup_id)
    assert store.get(backup_id) is not None


def test_admin_delete_recovers_interrupted_file_move(environment):
    client, store, token = environment
    backup_id = str(uuid.uuid4())
    assert upload(client, token, backup_id, b"synthetic").status_code == 201
    target = store.path_for(backup_id)
    tombstone = store.settings.data_dir / "quarantine" / ("delete-" + backup_id + ".json")
    os.replace(target, tombstone)  # Simulate crash before SQLite commit.
    assert store.get(backup_id) is not None
    store.consistency(repair=True)
    assert target.exists() and not tombstone.exists()
    os.replace(target, tombstone)
    with store.connect() as db:
        db.execute("DELETE FROM backups WHERE backup_id=?", (backup_id,))  # Crash after commit.
    store.consistency(repair=True)
    assert not target.exists() and not tombstone.exists()


def test_minimum_two_retained(tmp_path):
    settings = Settings(tmp_path, tmp_path / "db.sqlite3", max_upload_bytes=10,
                        quota_bytes=1000, retention_count=1, rate_limit_per_minute=0)
    app = create_app(settings)
    token = app.state.store.create_token("device")
    with TestClient(app) as client:
        for _ in range(3):
            assert upload(client, token, str(uuid.uuid4()), b"x").status_code == 201
    assert app.state.store.capacity()["count"] == 2


def test_offsite_archive_restore_drill(environment, tmp_path):
    client, store, token = environment
    backup_id = str(uuid.uuid4())
    assert upload(client, token, backup_id, b"archive content").status_code == 201
    archive = tmp_path.parent / (tmp_path.name + "-offsite.tar.gz")
    export_archive(store, archive)
    restored = tmp_path.parent / (tmp_path.name + "-restored")
    assert restore_archive(archive, restored) == {"missing": [], "corrupt": [], "orphan": [], "temporary": []}
    copied = Store(Settings(restored, restored / "metadata.sqlite3"))
    assert copied.get(backup_id)["sha256"] == hashlib.sha256(b"archive content").hexdigest()
    assert copied.path_for(backup_id).read_bytes() == b"archive content"


@pytest.mark.slow
def test_real_500m_stream_boundary(tmp_path):
    """Full streaming test uses synthetic bytes and about 1 GB of temporary disk."""
    settings = Settings(tmp_path, tmp_path / "db.sqlite3", rate_limit_per_minute=0)
    app = create_app(settings)
    token = app.state.store.create_token("boundary")
    block = b"x" * 1_000_000
    hashes = {}
    for size in (499_999_999, 500_000_000):
        hasher = hashlib.sha256()
        for _ in range(size // len(block)):
            hasher.update(block)
        hasher.update(block[:size % len(block)])
        hashes[size] = hasher.hexdigest()

    def stream(size):
        for _ in range(size // len(block)):
            yield block
        if size % len(block):
            yield block[:size % len(block)]

    with TestClient(app) as client:
        for size in (499_999_999, 500_000_000):
            response = client.put("/api/v1/backups/" + str(uuid.uuid4()), content=stream(size),
                                  headers={"Authorization": "Bearer " + token,
                                           "Content-Type": "application/json; charset=utf-8",
                                           "Content-Length": str(size), "X-Content-SHA256": hashes[size]})
            assert response.status_code == 201, response.text
            assert response.json()["sizeBytes"] == size
        response = client.put("/api/v1/backups/" + str(uuid.uuid4()), content=stream(500_000_001),
                              headers={"Authorization": "Bearer " + token,
                                       "Content-Type": "application/json; charset=utf-8",
                                       "Content-Length": "500000001", "X-Content-SHA256": "0" * 64})
        assert response.status_code == 413
    assert app.state.store.capacity()["count"] == 2
