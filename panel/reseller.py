"""Isolated reseller license activation and credit ledger (no IPTV subscriptions)."""
import hashlib
import hmac
import os
import re
import secrets
import sqlite3
from datetime import datetime, timezone
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import FileResponse
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from pydantic import BaseModel, Field

def install(app, database, admin_only, limiter, base, add_months, now_iso):
    router = APIRouter()
    basic = HTTPBasic(auto_error=False)

    def hash_password(password, salt=None):
        salt = salt or secrets.token_hex(16)
        digest = hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(salt), 310000)
        return salt, digest.hex()

    def reseller(request: Request, credentials: HTTPBasicCredentials = Depends(basic)):
        client = request.client.host if request.client else "unknown"
        limiter.check("reseller-auth-ip", client, 25, 300)
        if not credentials:
            raise HTTPException(401, "Reseller login required", headers={"WWW-Authenticate": 'Basic realm="SEGA Reseller"'})
        with database() as db:
            row = db.execute("SELECT * FROM resellers WHERE username=?", (credentials.username,)).fetchone()
        # Perform a real KDF even when the user does not exist.
        salt = row["password_salt"] if row else "0" * 32
        _, digest = hash_password(credentials.password, salt)
        if not row or not hmac.compare_digest(row["password_hash"], digest) or not row["enabled"]:
            raise HTTPException(401, "Invalid reseller credentials", headers={"WWW-Authenticate": 'Basic realm="SEGA Reseller"'})
        return dict(row)

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
        device_id: str = Field(min_length=1, max_length=80)

    @router.get("/reseller")
    def reseller_page(_: dict = Depends(reseller)):
        return FileResponse(base / "static" / "reseller.html")

    @router.get("/reseller/assets/{name}")
    def reseller_asset(name: str, _: dict = Depends(reseller)):
        if name not in {"reseller.js", "reseller.css"}:
            raise HTTPException(404)
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
            devices = [dict(r) for r in db.execute("""SELECT d.id,d.mac_alias,d.label,d.active,d.expires_at
                FROM devices d JOIN reseller_devices rd ON rd.device_id=d.id
                WHERE rd.reseller_id=? ORDER BY d.last_seen DESC""",(account["id"],))]
            ledger = [dict(r) for r in db.execute("""SELECT id,delta,reason,created_at
                FROM reseller_ledger WHERE reseller_id=? ORDER BY id DESC LIMIT 100""",(account["id"],))]
        return {"username":account["username"],"credits":account["credits"],"devices":devices,"ledger":ledger}

    @router.post("/reseller/api/activate")
    def reseller_activate(data: Activation, account: dict = Depends(reseller)):
        from datetime import timedelta
        with database() as db:
            db.execute("BEGIN IMMEDIATE")
            owner = db.execute("""SELECT d.id FROM devices d JOIN reseller_devices rd ON d.id=rd.device_id
                WHERE d.id=? AND rd.reseller_id=?""",(data.device_id,account["id"])).fetchone()
            if not owner:
                raise HTTPException(404, "Device not assigned")
            current = db.execute("SELECT credits,enabled FROM resellers WHERE id=?", (account["id"],)).fetchone()
            if not current["enabled"] or current["credits"] < 1:
                raise HTTPException(409, "Insufficient credits or disabled reseller")
            today = datetime.now(timezone.utc).date()
            expiry = (add_months(today,12)-timedelta(days=1)).isoformat()
            db.execute("UPDATE resellers SET credits=credits-1 WHERE id=?", (account["id"],))
            db.execute("UPDATE devices SET active=1,expires_at=? WHERE id=?", (expiry,data.device_id))
            db.execute("""INSERT INTO subscriptions(device_id,trial_used,plan) VALUES(?,1,'year')
                ON CONFLICT(device_id) DO UPDATE SET trial_used=1,plan='year'""",(data.device_id,))
            db.execute("INSERT INTO reseller_ledger(reseller_id,device_id,delta,reason,created_at) VALUES(?,?,-1,?,?)",
                       (account["id"],data.device_id,"12-month player license",now_iso()))
            return {"activated":True,"expires_at":expiry,"credits":current["credits"]-1}

    app.include_router(router)
