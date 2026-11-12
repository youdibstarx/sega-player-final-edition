"""Isolated reseller license activation and credit ledger (no IPTV subscriptions)."""
import hashlib
import hmac
import os
import re
import secrets
import sqlite3
import time
from datetime import datetime, timezone
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse
from pydantic import BaseModel, Field, field_validator

def install(app, database, admin_only, limiter, base, add_months, now_iso):
    router = APIRouter()
    session_cookie = "sega_reseller_session"
    session_seconds = 12 * 60 * 60

    # Session tokens are stored as hashes, not plaintext. Existing reseller
    # accounts and credit balances are preserved during this migration.
    with database() as db:
        db.execute("""CREATE TABLE IF NOT EXISTS reseller_sessions (
            token_hash TEXT PRIMARY KEY,
            reseller_id INTEGER NOT NULL REFERENCES resellers(id) ON DELETE CASCADE,
            expires_at INTEGER NOT NULL
        )""")
        db.execute("CREATE INDEX IF NOT EXISTS reseller_session_expiry ON reseller_sessions(expires_at)")

    def hash_password(password, salt=None):
        salt = salt or secrets.token_hex(16)
        digest = hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(salt), 310000)
        return salt, digest.hex()

    def current_account(request: Request):
        token = request.cookies.get(session_cookie, "")
        if not re.fullmatch(r"[A-Za-z0-9_-]{40,128}", token):
            return None
        token_hash = hashlib.sha256(token.encode("ascii")).hexdigest()
        with database() as db:
            row = db.execute("""SELECT r.* FROM reseller_sessions s
                JOIN resellers r ON r.id=s.reseller_id
                WHERE s.token_hash=? AND s.expires_at>? AND r.enabled=1""",
                (token_hash, int(time.time()))).fetchone()
        return dict(row) if row else None

    def reseller(request: Request):
        account = current_account(request)
        if account is None:
            raise HTTPException(401, "Reseller session expired or login required")
        return account

    class ResellerLogin(BaseModel):
        model_config = {"extra": "forbid"}
        username: str = Field(min_length=1, max_length=40)
        password: str = Field(min_length=1, max_length=128)

    class ResellerCreate(BaseModel):
        model_config = {"extra": "forbid"}
        username: str = Field(min_length=3, max_length=40, pattern=r"^[A-Za-z0-9_-]+$")
        password: str = Field(min_length=12, max_length=128)

    class CreditInput(BaseModel):
        model_config = {"extra": "forbid"}
        amount: int = Field(ge=1, le=100000)
        note: str = Field(default="", max_length=160)

    class Assignment(BaseModel):
        model_config = {"extra": "forbid"}
        device_id: str = Field(min_length=1, max_length=80)

    class Activation(BaseModel):
        model_config = {"extra": "forbid"}
        mac: str = Field(min_length=12, max_length=17)

        @field_validator("mac")
        @classmethod
        def normalized_mac(cls, value):
            raw = re.sub(r"[:-]", "", value.strip()).upper()
            if not re.fullmatch(r"[0-9A-F]{12}", raw):
                raise ValueError("Invalid MAC address")
            return ":".join(raw[index:index + 2] for index in range(0, 12, 2))

    @router.get("/reseller/login")
    def reseller_login_page(request: Request):
        if current_account(request) is not None:
            return RedirectResponse("/reseller", status_code=303)
        return FileResponse(base / "static" / "reseller-login.html")

    @router.post("/reseller/api/login")
    def reseller_login(payload: ResellerLogin, request: Request):
        client = request.client.host if request.client else "unknown"
        limiter.check("reseller-login-ip", client, 30, 300)
        limiter.check("reseller-login-account", payload.username.lower(), 12, 300)
        with database() as db:
            row = db.execute("SELECT * FROM resellers WHERE username=?", (payload.username,)).fetchone()
        salt = row["password_salt"] if row else "0" * 32
        _, digest = hash_password(payload.password, salt)
        if not row or not row["enabled"] or not hmac.compare_digest(row["password_hash"], digest):
            raise HTTPException(401, "Invalid username or password")
        token = secrets.token_urlsafe(48)
        expiry = int(time.time()) + session_seconds
        with database() as db:
            db.execute("DELETE FROM reseller_sessions WHERE expires_at<=?", (int(time.time()),))
            db.execute("INSERT INTO reseller_sessions(token_hash,reseller_id,expires_at) VALUES(?,?,?)",
                (hashlib.sha256(token.encode("ascii")).hexdigest(), row["id"], expiry))
        response = JSONResponse({"logged_in": True})
        # Secure is mandatory on public domains, even if TLS ends at a reverse proxy.
        host = request.headers.get("host", "").split(":")[0].lower()
        secure_cookie = request.url.scheme == "https" or host not in {"testserver", "localhost", "127.0.0.1", "::1"}
        response.set_cookie(session_cookie, token, max_age=session_seconds, path="/reseller",
                            secure=secure_cookie, httponly=True, samesite="strict")
        return response

    @router.post("/reseller/api/logout")
    def reseller_logout(request: Request):
        token = request.cookies.get(session_cookie, "")
        if token:
            with database() as db:
                db.execute("DELETE FROM reseller_sessions WHERE token_hash=?",
                           (hashlib.sha256(token.encode("utf-8")).hexdigest(),))
        response = JSONResponse({"logged_out": True})
        response.delete_cookie(session_cookie, path="/reseller", samesite="strict")
        return response

    @router.get("/reseller")
    def reseller_page(request: Request):
        if current_account(request) is None:
            return RedirectResponse("/reseller/login", status_code=303)
        return FileResponse(base / "static" / "reseller.html")

    @router.get("/reseller/assets/{name}")
    def reseller_asset(name: str, request: Request):
        if name not in {"reseller.js", "reseller-login.js", "reseller.css"}:
            raise HTTPException(404)
        if name == "reseller.js" and current_account(request) is None:
            raise HTTPException(401, "Login required")
        return FileResponse(base / "static" / name)

    @router.get("/admin/api/resellers")
    def list_resellers(_: bool = Depends(admin_only)):
        with database() as db:
            return {"resellers": [dict(r) for r in db.execute(
                "SELECT id,username,enabled,credits,created_at FROM resellers ORDER BY id DESC")]}

    @router.post("/admin/api/resellers")
    def create_reseller(data: ResellerCreate, _: bool = Depends(admin_only)):
        salt, digest = hash_password(data.password)
        with database() as db:
            try:
                cur = db.execute("INSERT INTO resellers(username,password_salt,password_hash,created_at) VALUES(?,?,?,?)",
                                 (data.username,salt,digest,now_iso()))
            except sqlite3.IntegrityError as exc:
                # Other legacy-schema constraints are NOT username collisions.
                # Let unexpected database failures reach the server logs.
                if "UNIQUE constraint failed: resellers.username" in str(exc):
                    raise HTTPException(409, "Username already exists") from exc
                raise
            return {"id": cur.lastrowid}

    @router.post("/admin/api/resellers/{reseller_id}/credits")
    def add_credit(reseller_id: int, data: CreditInput, _: bool = Depends(admin_only)):
        with database() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT credits FROM resellers WHERE id=?", (reseller_id,)).fetchone()
            if not row:
                raise HTTPException(404, "Reseller not found")
            db.execute("UPDATE resellers SET credits=credits+? WHERE id=?", (data.amount,reseller_id))
            db.execute("INSERT INTO reseller_ledger(reseller_id,delta,reason,created_at) VALUES(?,?,?,?)",
                       (reseller_id,data.amount,"admin top-up: "+data.note,now_iso()))
            return {"credits": row["credits"]+data.amount}

    @router.post("/admin/api/resellers/{reseller_id}/devices")
    def assign_device(reseller_id: int, data: Assignment, _: bool = Depends(admin_only)):
        with database() as db:
            db.execute("BEGIN IMMEDIATE")
            if not db.execute("SELECT 1 FROM resellers WHERE id=?", (reseller_id,)).fetchone():
                raise HTTPException(404, "Reseller not found")
            if not db.execute("SELECT 1 FROM devices WHERE id=?", (data.device_id,)).fetchone():
                raise HTTPException(404, "Device not found")
            owner = db.execute("SELECT reseller_id FROM reseller_devices WHERE device_id=?", (data.device_id,)).fetchone()
            if owner and owner["reseller_id"] != reseller_id:
                raise HTTPException(409, "Device already belongs to another reseller")
            db.execute("INSERT OR IGNORE INTO reseller_devices(reseller_id,device_id) VALUES(?,?)",
                       (reseller_id,data.device_id))
        return {"assigned": True}

    @router.get("/reseller/api/state")
    def reseller_state(account: dict = Depends(reseller)):
        with database() as db:
            devices = [dict(r) for r in db.execute("""SELECT d.id,d.mac_alias,d.label,d.active,d.expires_at,
                COALESCE(s.plan,'existing') AS plan
                FROM devices d JOIN reseller_devices rd ON rd.device_id=d.id
                LEFT JOIN subscriptions s ON s.device_id=d.id
                WHERE rd.reseller_id=? ORDER BY d.last_seen DESC""",(account["id"],))]
            ledger = [dict(r) for r in db.execute("""SELECT id,delta,reason,created_at
                FROM reseller_ledger WHERE reseller_id=? ORDER BY id DESC LIMIT 100""",(account["id"],))]
        return {"username":account["username"],"credits":account["credits"],"devices":devices,"ledger":ledger}

    @router.post("/reseller/api/activate")
    def reseller_activate(data: Activation, account: dict = Depends(reseller)):
        """Activate a registered, unlicensed player by its MAC, without admin pre-assignment."""
        from datetime import timedelta
        with database() as db:
            # Serialize the eligibility check and balance deduction to prevent
            # double activation/double charges under concurrent requests.
            db.execute("BEGIN IMMEDIATE")
            devices = db.execute("""SELECT d.id,d.active,d.expires_at,
                COALESCE(s.plan,'existing') AS plan
                FROM devices d LEFT JOIN subscriptions s ON s.device_id=d.id
                WHERE d.mac_alias=? COLLATE NOCASE""", (data.mac,)).fetchall()
            if not devices:
                raise HTTPException(404, "MAC not registered. Open SEGA PLAYER on this device first.")
            if len(devices) != 1:
                raise HTTPException(409, "Multiple devices registered with this MAC; contact admin.")

            device = devices[0]
            today = datetime.now(timezone.utc).date()
            # An active free trial can be upgraded; an active paid/lifetime
            # license must not be charged again. Expired licenses are renewable.
            license_current = bool(device["active"]) and (
                device["expires_at"] is None or device["expires_at"] >= today.isoformat()
            )
            if license_current and device["plan"] != "trial":
                raise HTTPException(409, "This MAC already has an active license.")

            debit = db.execute("""UPDATE resellers SET credits=credits-1
                WHERE id=? AND enabled=1 AND credits>=1""", (account["id"],))
            if debit.rowcount != 1:
                raise HTTPException(409, "Insufficient reseller credits or disabled account.")

            expiry = (add_months(today, 12) - timedelta(days=1)).isoformat()
            db.execute("""INSERT INTO reseller_devices(reseller_id,device_id) VALUES(?,?)
                ON CONFLICT(device_id) DO UPDATE SET reseller_id=excluded.reseller_id""",
                (account["id"], device["id"]))
            db.execute("UPDATE devices SET active=1,expires_at=? WHERE id=?",
                       (expiry, device["id"]))
            db.execute("""INSERT INTO subscriptions(device_id,trial_used,plan) VALUES(?,1,'year')
                ON CONFLICT(device_id) DO UPDATE SET trial_used=1,plan='year'""",
                (device["id"],))
            db.execute("""INSERT INTO reseller_ledger(reseller_id,device_id,delta,reason,created_at)
                VALUES(?,?,-1,?,?)""",
                (account["id"], device["id"], "12-month license: " + data.mac, now_iso()))
            balance = db.execute("SELECT credits FROM resellers WHERE id=?",
                                 (account["id"],)).fetchone()["credits"]
            return {"activated": True, "mac": data.mac, "expires_at": expiry,
                    "credits": balance}

    app.include_router(router)
