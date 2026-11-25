"""Signed server discovery. Only the independent endpoint signing key may authorize new hosts."""
import base64
import json
import os
import re
from urllib.parse import urlsplit

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from fastapi import Depends, HTTPException
from pydantic import BaseModel, Field

DEFAULT_PRIMARY = "https://segaplayer.site"
DEFAULT_BACKUP = "https://tchach.online"
HOST = re.compile(r"(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}")


def normalize(value):
    try:
        parsed = urlsplit(value)
        if (parsed.scheme != "https" or not parsed.hostname or not HOST.fullmatch(parsed.hostname)
                or parsed.netloc != parsed.hostname or parsed.path not in ("", "/")
                or parsed.query or parsed.fragment or "?" in value or "#" in value
                or any(c.isspace() or ord(c) < 32 for c in value)):
            raise ValueError()
        return "https://" + parsed.hostname
    except ValueError:
        raise HTTPException(422, "Use an HTTPS domain without credentials, ports or paths") from None


def signing_key():
    try:
        encoded = os.environ.get("SEGA_ENDPOINT_SIGNING_PRIVATE_KEY", "")
        key = serialization.load_der_private_key(base64.b64decode(encoded, validate=True), password=None)
        if isinstance(key, rsa.RSAPrivateKey) and key.key_size >= 2048:
            return key
    except (ValueError, TypeError):
        pass
    return None


def envelope(row, key):
    payload = json.dumps({"schema": "sega-endpoints-v1", "revision": row["revision"],
                          "primary": row["primary_url"], "backup": row["backup_url"]},
                         separators=(",", ":"), sort_keys=True).encode()
    signature = key.sign(payload, padding.PKCS1v15(), hashes.SHA256())
    return {"payload": base64.b64encode(payload).decode(),
            "signature": base64.b64encode(signature).decode()}


class EndpointInput(BaseModel):
    primary: str = Field(min_length=9, max_length=270)
    backup: str = Field(min_length=9, max_length=270)


def install(app, database, admin_only):
    with database() as db:
        db.execute("""CREATE TABLE IF NOT EXISTS endpoint_settings (
            id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL,
            primary_url TEXT NOT NULL, backup_url TEXT NOT NULL)""")
        db.execute("INSERT OR IGNORE INTO endpoint_settings VALUES(1,1,?,?)",
                   (DEFAULT_PRIMARY, DEFAULT_BACKUP))

    def read():
        with database() as db:
            return dict(db.execute("SELECT * FROM endpoint_settings WHERE id=1").fetchone())

    @app.get("/api/v1/endpoints")
    def public_endpoints():
        key = signing_key()
        if key is None:
            raise HTTPException(503, "Endpoint discovery signing unavailable")
        return envelope(read(), key)

    @app.get("/admin/api/endpoints")
    def admin_endpoints(_: bool = Depends(admin_only)):
        row = read()
        return {"primary": row["primary_url"], "backup": row["backup_url"],
                "revision": row["revision"], "signing_enabled": signing_key() is not None}

    @app.put("/admin/api/endpoints")
    def update_endpoints(payload: EndpointInput, _: bool = Depends(admin_only)):
        if signing_key() is None:
            raise HTTPException(503, "Configure the endpoint signing key before publishing")
        primary, backup = normalize(payload.primary), normalize(payload.backup)
        if primary == backup:
            raise HTTPException(422, "Primary and backup must be different")
        with database() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT * FROM endpoint_settings WHERE id=1").fetchone()
            if (primary, backup) != (row["primary_url"], row["backup_url"]):
                db.execute("UPDATE endpoint_settings SET revision=revision+1, primary_url=?, backup_url=? WHERE id=1",
                           (primary, backup))
        return {"saved": True}
