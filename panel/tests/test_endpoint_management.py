import base64
import json
import sqlite3
from contextlib import contextmanager

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from fastapi import FastAPI, HTTPException, Request
from fastapi.testclient import TestClient
from endpoint_management import install


def test_endpoint_management(tmp_path, monkeypatch):
    app = FastAPI()

    @contextmanager
    def database():
        db = sqlite3.connect(tmp_path / "endpoints.db")
        db.row_factory = sqlite3.Row
        try:
            yield db
            db.commit()
        finally:
            db.close()

    def admin_only(request: Request):
        if request.headers.get("X-Test-Admin") != "yes":
            raise HTTPException(403)
        return True

    install(app, database, admin_only)
    client = TestClient(app)
    admin = {"X-Test-Admin": "yes"}
    path = "/admin/api/endpoints"
    monkeypatch.delenv("SEGA_ENDPOINT_SIGNING_PRIVATE_KEY", raising=False)
    assert client.get(path).status_code == 403
    assert client.get("/api/v1/endpoints").status_code == 503
    assert client.get(path, headers=admin).json()["signing_enabled"] is False
    update = {"primary": "https://new.example.com", "backup": "https://tchach.online"}
    assert client.put(path, json=update, headers=admin).status_code == 503

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    raw = key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    monkeypatch.setenv("SEGA_ENDPOINT_SIGNING_PRIVATE_KEY", base64.b64encode(raw).decode())

    def verify():
        response = client.get("/api/v1/endpoints")
        assert response.status_code == 200
        envelope = response.json()
        payload = base64.b64decode(envelope["payload"])
        key.public_key().verify(base64.b64decode(envelope["signature"]), payload, padding.PKCS1v15(), hashes.SHA256())
        return json.loads(payload)

    assert verify() == {"schema": "sega-endpoints-v1", "revision": 1,
                        "primary": "https://segaplayer.site", "backup": "https://tchach.online"}
    assert client.put(path, json=update).status_code == 403
    assert client.put(path, json={"primary": "http://example.com", "backup": update["backup"]}, headers=admin).status_code == 422
    assert client.put(path, json={"primary": update["backup"], "backup": update["backup"]}, headers=admin).status_code == 422
    assert client.put(path, json=update, headers=admin).status_code == 200
    assert verify()["revision"] == 2
    assert verify()["primary"] == update["primary"]
    assert client.put(path, json=update, headers=admin).status_code == 200
    assert verify()["revision"] == 2
