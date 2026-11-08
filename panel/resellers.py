"""Reseller activation ledger: half-point integer balances, atomic transfers and charges."""
from datetime import datetime, timezone, timedelta
from decimal import Decimal
import hashlib
import json
import hmac
import re
import secrets
import sqlite3

from fastapi import Depends, HTTPException, Request
from fastapi.responses import FileResponse
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from pydantic import BaseModel, Field, field_validator
from typing import Literal


def password_hash(value: str, salt: str) -> str:
    return hashlib.pbkdf2_hmac("sha256", value.encode("utf-8"),
                              bytes.fromhex(salt), 240000).hex()


def make_hash(value: str):
    salt = secrets.token_hex(16)
    return salt, password_hash(value, salt)


def valid_hash(value: str, salt: str, digest: str) -> bool:
    return hmac.compare_digest(password_hash(value, salt), digest)


def initialize_resellers(database):
    with database() as db:
        db.executescript("""
        CREATE TABLE IF NOT EXISTS resellers (
            email TEXT PRIMARY KEY COLLATE NOCASE,
            password_salt TEXT NOT NULL,
            password_hash TEXT NOT NULL,
            role TEXT NOT NULL CHECK(role IN ('main','sub')),
            parent_email TEXT REFERENCES resellers(email),
            balance_units INTEGER NOT NULL DEFAULT 0 CHECK(balance_units>=0),
            created_at TEXT NOT NULL,
            CHECK((role='main' AND parent_email IS NULL) OR
                  (role='sub' AND parent_email IS NOT NULL))
        );
        CREATE TABLE IF NOT EXISTS reseller_transfers (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            source_email TEXT REFERENCES resellers(email),
            target_email TEXT NOT NULL REFERENCES resellers(email),
            units INTEGER NOT NULL CHECK(units>0),
            action TEXT NOT NULL CHECK(action IN ('admin_topup','transfer')),
            created_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS reseller_activations (
            request_id TEXT PRIMARY KEY,
            reseller_email TEXT NOT NULL REFERENCES resellers(email),
            device_id TEXT NOT NULL REFERENCES devices(id),
            mac_alias TEXT NOT NULL,
            plan TEXT NOT NULL CHECK(plan IN ('six_months','year','lifetime')),
            cost_units INTEGER NOT NULL CHECK(cost_units>0),
            expires_at TEXT,
            created_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS reseller_subs ON resellers(parent_email);
        CREATE INDEX IF NOT EXISTS activation_history ON reseller_activations(reseller_email,created_at);
        """)


class NewReseller(BaseModel):
    email: str = Field(min_length=5, max_length=254,
                       pattern=r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
    password: str = Field(min_length=12, max_length=128)

    @field_validator("email")
    @classmethod
    def canonical_email(cls, value: str):
        return value.strip().lower()


class CreditAmount(BaseModel):
    points: Decimal = Field(gt=0, le=Decimal("100000"))

    def units(self):
        doubled = self.points * 2
        if doubled != doubled.to_integral_value():
            raise HTTPException(422, "Use multiples of half a point")
        return int(doubled)


class SubTransfer(CreditAmount):
    email: str = Field(min_length=5, max_length=254)

    @field_validator("email")
    @classmethod
    def canonical_email(cls, value):
        return value.strip().lower()


class ActivateDevice(BaseModel):
    request_id: str = Field(pattern=r"^[0-9a-fA-F-]{36}$")
    mac: str = Field(min_length=17, max_length=17)
    plan: Literal["six_months", "year", "lifetime"]

    @field_validator("mac")
    @classmethod
    def normalize_mac(cls, value):
        value = value.strip().upper()
        if not re.fullmatch(r"[0-9A-F]{2}(:[0-9A-F]{2}){5}", value):
            raise ValueError("Invalid MAC")
        return value


def write_audit(db, action, object_type, object_id="", details=None, actor="admin", now_iso=None):
    db.execute(
        "INSERT INTO audit_log(action,actor,object_type,object_id,details,created_at) VALUES(?,?,?,?,?,?)",
        (action, actor, str(object_id), json.dumps(details or {}, ensure_ascii=False), now_iso or "")
    )


def install_reseller_endpoints(APP, database, limiter, admin_only, now_iso, add_months):
    auth = HTTPBasic()
    root = APP

    def reseller_only(request: Request, credentials: HTTPBasicCredentials = Depends(auth)):
        email = credentials.username.strip().lower()
        with database() as db:
            row = db.execute("SELECT * FROM resellers WHERE email=?", (email,)).fetchone()
        if row:
            valid = valid_hash(credentials.password, row["password_salt"], row["password_hash"])
        else:
            # Uniform password hashing effort for unknown identities.
            valid_hash(credentials.password, "00" * 16, "00" * 32)
            valid = False
        if not valid:
            client = request.client.host if request.client else "unknown"
            limiter.check("reseller-bad-ip", client, 20)
            raise HTTPException(401, "Invalid reseller credentials",
                                headers={"WWW-Authenticate": "Basic"})
        return dict(row)

    @root.get("/reseller")
    def reseller_page():
        from main import BASE
        return FileResponse(BASE / "static" / "reseller.html")

    @root.get("/reseller/assets/{file_name}")
    def reseller_asset(file_name: str):
        from main import BASE
        if file_name not in {"reseller.js", "reseller.css"}:
            raise HTTPException(404)
        return FileResponse(BASE / "static" / file_name)

    @root.get("/admin/resellers")
    def reseller_admin_page(_: bool = Depends(admin_only)):
        from main import BASE
        return FileResponse(BASE / "static" / "reseller-admin.html")

    @root.get("/admin/api/resellers")
    def admin_resellers(_: bool = Depends(admin_only)):
        with database() as db:
            rows = db.execute("""SELECT email,role,parent_email,balance_units,created_at
                FROM resellers ORDER BY created_at DESC,email""").fetchall()
            transfers = db.execute("""SELECT id,source_email,target_email,units,action,created_at
                FROM reseller_transfers ORDER BY id DESC LIMIT 100""").fetchall()
            activations = db.execute("""SELECT request_id,reseller_email,mac_alias,plan,cost_units,
                expires_at,created_at FROM reseller_activations ORDER BY rowid DESC LIMIT 100""").fetchall()
        return {"resellers": [{**dict(r), "points": r["balance_units"]/2} for r in rows],
                "transfers": [{**dict(r), "points": r["units"]/2} for r in transfers],
                "activations": [{**dict(r), "points": r["cost_units"]/2} for r in activations]}

    @root.post("/admin/api/resellers")
    def admin_create_main(payload: NewReseller, _: bool = Depends(admin_only)):
        salt, digest = make_hash(payload.password)
        try:
            with database() as db:
                db.execute("""INSERT INTO resellers
                    (email,password_salt,password_hash,role,parent_email,created_at)
                    VALUES(?,?,?,'main',NULL,?)""",
                    (payload.email, salt, digest, now_iso()))
                write_audit(db, "reseller.created", "reseller", payload.email, {"role":"main"}, now_iso=now_iso())
        except sqlite3.IntegrityError:
            raise HTTPException(409, "Account already exists")
        return {"created": True, "email": payload.email}

    @root.post("/admin/api/resellers/{email}/credit")
    def admin_credit(email: str, payload: CreditAmount, _: bool = Depends(admin_only)):
        units = payload.units()
        email = email.lower()
        with database() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT role FROM resellers WHERE email=?", (email,)).fetchone()
            if not row:
                raise HTTPException(404, "Reseller not found")
            if row["role"] != "main":
                raise HTTPException(403, "Only principal resellers may be credited by admin")
            db.execute("UPDATE resellers SET balance_units=balance_units+? WHERE email=?", (units,email))
            created_at = now_iso()
            db.execute("""INSERT INTO reseller_transfers(source_email,target_email,units,action,created_at)
                VALUES(NULL,?,?,'admin_topup',?)""", (email,units,created_at))
            write_audit(db, "reseller.balance_topped_up", "reseller", email, {"points":units/2}, now_iso=created_at)
        return {"credited": True, "points": units/2}

    @root.get("/reseller/api/account")
    def reseller_account(user=Depends(reseller_only)):
        with database() as db:
            current = db.execute("SELECT balance_units FROM resellers WHERE email=?", (user["email"],)).fetchone()
            children = db.execute("""SELECT email,balance_units,created_at FROM resellers
                WHERE parent_email=? ORDER BY created_at DESC""", (user["email"],)).fetchall()
            transactions = db.execute("""SELECT id,source_email,target_email,units,action,created_at
                FROM reseller_transfers WHERE source_email=? OR target_email=? ORDER BY id DESC LIMIT 60""",
                (user["email"],user["email"])).fetchall()
            activations = db.execute("""SELECT request_id,mac_alias,plan,cost_units,expires_at,created_at
                FROM reseller_activations WHERE reseller_email=?
                ORDER BY rowid DESC LIMIT 60""", (user["email"],)).fetchall()
        return {"email":user["email"],"role":user["role"],"balance_points":current["balance_units"]/2,
                "children":[{"email":r["email"],"points":r["balance_units"]/2} for r in children],
                "transfers":[{**dict(r),"points":r["units"]/2} for r in transactions],
                "activations":[{**dict(r),"points":r["cost_units"]/2} for r in activations]}

    @root.post("/reseller/api/subresellers")
    def create_sub(payload: NewReseller, user=Depends(reseller_only)):
        if user["role"] != "main":
            raise HTTPException(403, "Sub-resellers cannot create resellers")
        salt, digest = make_hash(payload.password)
        try:
            with database() as db:
                db.execute("BEGIN IMMEDIATE")
                db.execute("""INSERT INTO resellers
                    (email,password_salt,password_hash,role,parent_email,created_at)
                    VALUES(?,?,?,'sub',?,?)""",
                    (payload.email,salt,digest,user["email"],now_iso()))
                write_audit(db, "reseller.created", "reseller", payload.email, {"role":"sub"}, actor=user["email"], now_iso=now_iso())
        except sqlite3.IntegrityError:
            raise HTTPException(409, "Account already exists")
        return {"created":True,"email":payload.email}

    @root.post("/reseller/api/transfer")
    def transfer_credit(payload: SubTransfer, user=Depends(reseller_only)):
        if user["role"] != "main":
            raise HTTPException(403, "Only principal reseller can transfer credit")
        units = payload.units()
        with database() as db:
            db.execute("BEGIN IMMEDIATE")
            child = db.execute("SELECT role,parent_email FROM resellers WHERE email=?",
                               (payload.email,)).fetchone()
            if not child or child["role"] != "sub" or child["parent_email"] != user["email"]:
                raise HTTPException(403, "Target must be your direct sub-reseller")
            deduction = db.execute("""UPDATE resellers SET balance_units=balance_units-?
                WHERE email=? AND balance_units>=?""", (units,user["email"],units))
            if not deduction.rowcount:
                raise HTTPException(409, "Insufficient balance")
            db.execute("UPDATE resellers SET balance_units=balance_units+? WHERE email=?",
                       (units,payload.email))
            created_at = now_iso()
            db.execute("""INSERT INTO reseller_transfers(source_email,target_email,units,action,created_at)
                VALUES(?,?,?,'transfer',?)""", (user["email"],payload.email,units,created_at))
            write_audit(db, "reseller.balance_transferred", "reseller", payload.email, {"points":units/2}, actor=user["email"], now_iso=created_at)
        return {"transferred":True, "points":units/2}

    def mac_status(mac):
        mac = mac.strip().upper()
        if not re.fullmatch(r"[0-9A-F]{2}(:[0-9A-F]{2}){5}",mac):
            raise HTTPException(422,"Invalid MAC")
        with database() as db:
            devices = db.execute("""SELECT d.id,d.active,d.expires_at,s.plan
                FROM devices d LEFT JOIN subscriptions s ON s.device_id=d.id
                WHERE mac_alias=?""", (mac,)).fetchall()
        if not devices:
            raise HTTPException(404,"MAC not found")
        if len(devices)>1:
            raise HTTPException(409,"MAC is ambiguous; ask admin to resolve duplicate devices")
        d=devices[0]
        today=datetime.now(timezone.utc).date()
        active=bool(d["active"] and (not d["expires_at"] or d["expires_at"]>=today.isoformat()))
        return {"mac":mac, "active":active,"expires_at":d["expires_at"],"plan":d["plan"]}

    @root.get("/reseller/api/mac/{mac}")
    def reseller_mac(mac: str, user=Depends(reseller_only)):
        return mac_status(mac)

    @root.post("/reseller/api/activate")
    def activate(payload: ActivateDevice, user=Depends(reseller_only)):
        cost={"six_months":1,"year":2,"lifetime":4}[payload.plan]
        with database() as db:
            db.execute("BEGIN IMMEDIATE")
            existing=db.execute("SELECT * FROM reseller_activations WHERE request_id=?",
                                (payload.request_id,)).fetchone()
            if existing:
                if (existing["reseller_email"]!=user["email"] or
                    existing["mac_alias"]!=payload.mac or existing["plan"]!=payload.plan):
                    raise HTTPException(409,"Request ID already used")
                return {"activated":True,"already_processed":True,"mac":payload.mac,
                        "points_charged":existing["cost_units"]/2,
                        "expires_at":existing["expires_at"]}
            devices=db.execute("SELECT id,active,expires_at FROM devices WHERE mac_alias=?",
                               (payload.mac,)).fetchall()
            if not devices:
                raise HTTPException(404,"MAC not found")
            if len(devices)!=1:
                raise HTTPException(409,"MAC is ambiguous; ask admin to resolve duplicate devices")
            device=devices[0]
            if device["active"] and device["expires_at"] is None:
                raise HTTPException(409,"Device already has lifetime activation")
            deduction=db.execute("""UPDATE resellers SET balance_units=balance_units-?
                WHERE email=? AND balance_units>=?""",(cost,user["email"],cost))
            if not deduction.rowcount:
                raise HTTPException(409,"Insufficient balance")
            today=datetime.now(timezone.utc).date()
            if payload.plan=="lifetime":
                expiry=None
            else:
                start=today
                if device["active"] and device["expires_at"]:
                    current=datetime.fromisoformat(device["expires_at"]).date()
                    if current>=today:
                        start=current+timedelta(days=1)
                months=6 if payload.plan=="six_months" else 12
                expiry=(add_months(start,months)-timedelta(days=1)).isoformat()
            db.execute("UPDATE devices SET active=1,expires_at=? WHERE id=?",
                       (expiry,device["id"]))
            db.execute("""INSERT INTO subscriptions(device_id,trial_used,plan) VALUES(?,1,?)
                ON CONFLICT(device_id) DO UPDATE SET trial_used=1,plan=excluded.plan""",
                (device["id"],payload.plan))
            created_at = now_iso()
            db.execute("""INSERT INTO reseller_activations
                (request_id,reseller_email,device_id,mac_alias,plan,cost_units,expires_at,created_at)
                VALUES(?,?,?,?,?,?,?,?)""",
                (payload.request_id,user["email"],device["id"],payload.mac,payload.plan,
                 cost,expiry,created_at))
            write_audit(db, "device.activated_by_reseller", "device", device["id"],
                {"mac":payload.mac,"plan":payload.plan,"expires_at":expiry}, actor=user["email"], now_iso=created_at)
        return {"activated":True,"already_processed":False,"mac":payload.mac,
                "points_charged":cost/2,"expires_at":expiry}
