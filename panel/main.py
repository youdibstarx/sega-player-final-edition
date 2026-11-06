"""SEGA IPTV Player control panel + device API. Uses only authorized M3U media sources."""
import calendar
import hashlib
import hmac
import os
import re
import sqlite3
from contextlib import contextmanager
from datetime import date, datetime, timezone, timedelta
from pathlib import Path
from typing import Optional, Literal

from fastapi import Depends, FastAPI, HTTPException, Request, Query, status
from fastapi.responses import FileResponse, JSONResponse
from fastapi.exceptions import RequestValidationError
from starlette.concurrency import run_in_threadpool
from security import RateLimiter
from proof import DeviceProofs
from resellers import initialize_resellers, install_reseller_endpoints, make_hash, valid_hash
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from pydantic import BaseModel, Field, field_validator

BASE = Path(__file__).resolve().parent
DB_FILE = Path(os.getenv("DATABASE_PATH", str(BASE / "data" / "segaiptv.db")))
DB_FILE.parent.mkdir(parents=True, exist_ok=True)
APP = FastAPI(title="SEGA IPTV Player Control Panel", docs_url=None, redoc_url=None, openapi_url=None)
security = HTTPBasic()

limiter = RateLimiter(os.getenv("SECURITY_DATABASE_PATH", str(DB_FILE.with_name("security-limits.db"))))

proofs = DeviceProofs(DB_FILE.with_name("device-proofs.db"))


@APP.exception_handler(RequestValidationError)
async def invalid_input(request, exc):
    # Default validation errors can echo submitted device keys and playlist URLs.
    return JSONResponse({"detail": "Invalid request data"}, status_code=422)


@APP.middleware("http")
async def security_headers(request: Request, call_next):
    try:
        path = request.url.path
        if path.startswith("/reseller/api/"):
            client = request.client.host if request.client else "unknown"
            await run_in_threadpool(limiter.check, "reseller-ip", client, 120)
            if request.method not in {"GET", "HEAD", "OPTIONS"}:
                if request.headers.get("X-Reseller-Request") != "1":
                    raise HTTPException(403, "Reseller request header required")
                origin = request.headers.get("Origin")
                if origin and origin != str(request.base_url).rstrip("/"):
                    raise HTTPException(403, "Cross-origin request rejected")
        if path.startswith("/portal/api/"):
            client = request.client.host if request.client else "unknown"
            await run_in_threadpool(limiter.check, "portal-ip", client, 60)
            if request.method not in {"GET", "HEAD", "OPTIONS"}:
                if request.headers.get("X-Portal-Request") != "1":
                    raise HTTPException(403, "Portal request header required")
                origin = request.headers.get("Origin")
                if origin and origin != str(request.base_url).rstrip("/"):
                    raise HTTPException(403, "Cross-origin request rejected")
        if path.startswith(("/api/", "/admin")):
            # Only trust ASGI client set by a correctly configured reverse proxy.
            # Never read an arbitrary X-Forwarded-For header here.
            client = request.client.host if request.client else "unknown"
            scope = "admin-ip" if path.startswith("/admin") else "device-ip"
            await run_in_threadpool(limiter.check, scope, client, 120 if scope == "admin-ip" else 600)
        if path.startswith("/admin/api/") and request.method not in {"GET", "HEAD", "OPTIONS"}:
            # Browser cross-origin forms cannot set this header; do not enable permissive CORS.
            if request.headers.get("X-Panel-Request") != "1":
                raise HTTPException(403, "Panel request header required")
            origin = request.headers.get("Origin")
            if origin and origin != str(request.base_url).rstrip("/"):
                raise HTTPException(403, "Cross-origin request rejected")
        response = await call_next(request)
    except HTTPException as exc:
        response = JSONResponse({"detail": exc.detail}, status_code=exc.status_code, headers=exc.headers)
    response.headers["Cache-Control"] = "no-store"
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "no-referrer"
    return response


@contextmanager
def database():
    con = sqlite3.connect(DB_FILE, timeout=15)
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA foreign_keys=ON")
    con.execute("PRAGMA busy_timeout=5000")
    try:
        yield con
        con.commit()
    finally:
        con.close()


def initialize():
    with database() as db:
        db.execute("PRAGMA journal_mode=WAL")
        db.executescript("""
        CREATE TABLE IF NOT EXISTS trial_claims (token TEXT PRIMARY KEY, device_id TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS devices (
            id TEXT PRIMARY KEY,
            mac_alias TEXT NOT NULL,
            key_hash TEXT NOT NULL,
            key_tail TEXT NOT NULL,
            active INTEGER NOT NULL DEFAULT 0,
            expires_at TEXT,
            label TEXT NOT NULL DEFAULT '',
            created_at TEXT NOT NULL,
            last_seen TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS playlists (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            url TEXT NOT NULL,
            is_global INTEGER NOT NULL DEFAULT 1,
            created_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS device_playlists (
            device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
            PRIMARY KEY(device_id, playlist_id)
        );
        CREATE TABLE IF NOT EXISTS settings (
            id INTEGER PRIMARY KEY CHECK (id=1),
            app_name TEXT NOT NULL,
            accent_color TEXT NOT NULL,
            background_url TEXT NOT NULL,
            logo_url TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS subscriptions (
            device_id TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
            trial_used INTEGER NOT NULL DEFAULT 1,
            trial_started_at TEXT,
            plan TEXT NOT NULL DEFAULT 'existing'
        );
        CREATE TABLE IF NOT EXISTS personal_playlists (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT NOT NULL UNIQUE REFERENCES devices(id) ON DELETE CASCADE,
            name TEXT NOT NULL,
            url TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS devices_recent ON devices(last_seen DESC, id);
        CREATE INDEX IF NOT EXISTS devices_mac_lookup ON devices(mac_alias);
        INSERT OR IGNORE INTO subscriptions(device_id) SELECT id FROM devices;
        INSERT OR IGNORE INTO settings VALUES
            (1, 'SEGA IPTV Player', '#E5474C', '', '');
        """)
        columns = {r["name"] for r in db.execute("PRAGMA table_info(personal_playlists)")}
        if "pin_salt" not in columns:
            db.execute("ALTER TABLE personal_playlists ADD COLUMN pin_salt TEXT")
        if "pin_hash" not in columns:
            db.execute("ALTER TABLE personal_playlists ADD COLUMN pin_hash TEXT")
    initialize_resellers(database)


initialize()


def admin_only(request: Request, credentials: HTTPBasicCredentials = Depends(security)):
    limiter.check("admin-login", request.client.host if request.client else "unknown", 30)
    admin = os.getenv("ADMIN_USERNAME", "")
    password = os.getenv("ADMIN_PASSWORD", "")
    # Explicit env var required; never ship a default password.
    if not admin or not password or not (
        hmac.compare_digest(credentials.username.encode(), admin.encode())
        and hmac.compare_digest(credentials.password.encode(), password.encode())
    ):
        raise HTTPException(status_code=401, detail="Invalid admin credentials", headers={"WWW-Authenticate": "Basic"})
    return True


def key_digest(key: str) -> str:
    return hashlib.sha256(key.encode("utf-8")).hexdigest()


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


class DeviceRegistration(BaseModel):
    device_id: str = Field(min_length=30, max_length=80, pattern=r"^[A-Za-z0-9-]+$")
    mac_alias: str = Field(pattern=r"^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
    trial_token: Optional[str] = Field(default=None, pattern=r"^[a-f0-9]{64}$")
    device_key: str = Field(min_length=6, max_length=128)

    @field_validator("device_key")
    @classmethod
    def valid_key(cls, key: str):
        # Accept current six-character keys and preserve original long-key clients.
        if not (re.fullmatch(r"[A-Za-z0-9]{6}", key) or 30 <= len(key) <= 128):
            raise ValueError("Invalid device key")
        return key


class PlaylistInput(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    url: str = Field(min_length=8, max_length=3000)
    is_global: bool = True

    @field_validator("url")
    @classmethod
    def validate_url(cls, url: str):
        if not (url.lower().startswith("http://") or url.lower().startswith("https://")):
            raise ValueError("M3U link must use http:// or https://")
        return url.strip()


class ActivationInput(BaseModel):
    active: bool
    plan: Literal["custom", "year", "lifetime"] = "custom"
    expires_at: Optional[str] = None
    label: str = Field(default="", max_length=100)

    @field_validator("expires_at")
    @classmethod
    def valid_expiry(cls, expires: Optional[str]):
        if expires:
            date.fromisoformat(expires)
        return expires or None


class AssignmentInput(BaseModel):
    playlist_ids: list[int]


class ThemeInput(BaseModel):
    app_name: str = Field(min_length=1, max_length=50)
    accent_color: str = Field(pattern=r"^#[a-fA-F0-9]{6}$")
    background_url: str = Field(default="", max_length=3000)
    logo_url: str = Field(default="", max_length=3000)

    @field_validator("background_url", "logo_url")
    @classmethod
    def validate_image_url(cls, val: str):
        if val and not val.startswith(("https://", "http://")):
            raise ValueError("Image URL must be http(s) or empty")
        return val


def verify_device(device_id: str, request: Request):
    limiter.check("device", device_id, 20)
    key = request.headers.get("X-Device-Key", "")
    if len(key) > 128:
        raise HTTPException(403, "Device not recognized")
    if not key:
        raise HTTPException(status_code=401, detail="Device key required")
    with database() as db:
        row = db.execute("SELECT * FROM devices WHERE id=?", (device_id,)).fetchone()
        if not row or not hmac.compare_digest(row["key_hash"], key_digest(key)):
            raise HTTPException(status_code=403, detail="Device not recognized")
        db.execute("UPDATE devices SET last_seen=? WHERE id=?", (now_iso(), device_id))
        return dict(row)


async def check_proof(device_id: str, request: Request):
    body = await request.body()
    path = request.url.path + (("?" + request.url.query) if request.url.query else "")
    await run_in_threadpool(proofs.verify, device_id, request.method, path, body, request.headers,
                            os.getenv("DEVICE_PROOF_REQUIRED", "false").lower() == "true")


class ProofApproval(BaseModel):
    fingerprint: str = Field(pattern=r"^[a-f0-9]{64}$")


@APP.get("/admin/api/proofs")
def pending_proofs(_: bool = Depends(admin_only)):
    result = proofs.status()
    # Proof rows can refer to devices outside the current admin page.
    with database() as db:
        for entry in result["pending"] + result["approved"]:
            row = db.execute("SELECT label,mac_alias FROM devices WHERE id=?", (entry["device"],)).fetchone()
            if row is not None:
                entry.update(dict(row))
    return {**result, "required": os.getenv("DEVICE_PROOF_REQUIRED", "false").lower() == "true"}


@APP.post("/admin/api/devices/{device_id}/proof")
def approve_proof(device_id: str, payload: ProofApproval, _: bool = Depends(admin_only)):
    with database() as db:
        if not db.execute("SELECT 1 FROM devices WHERE id=?", (device_id,)).fetchone():
            raise HTTPException(404, "Device not found")
    proofs.approve(device_id, payload.fingerprint)
    return {"approved": True}


@APP.post("/api/v1/register")
async def register(device: DeviceRegistration, request: Request):
    limiter.check("device", device.device_id, 20)
    with database() as db:
        db.execute("BEGIN IMMEDIATE")
        existing = db.execute("SELECT key_hash FROM devices WHERE id=?", (device.device_id,)).fetchone()
        hashed = key_digest(device.device_key)
        if existing:
            if not hmac.compare_digest(existing["key_hash"], hashed):
                raise HTTPException(status_code=403, detail="Device credentials mismatch")
            db.execute("UPDATE devices SET last_seen=? WHERE id=?", (now_iso(), device.device_id))
        else:
            db.execute("""INSERT INTO devices(id,mac_alias,key_hash,key_tail,created_at,last_seen)
                          VALUES(?,?,?,?,?,?)""", (
                device.device_id, device.mac_alias, hashed, device.device_key[-4:], now_iso(), now_iso()
            ))
            db.execute("INSERT INTO subscriptions(device_id,trial_used) VALUES(?,0)", (device.device_id,))
    await check_proof(device.device_id, request)
    with database() as db:
        db.execute("BEGIN IMMEDIATE")
        entitlement = db.execute("SELECT trial_used,plan FROM subscriptions WHERE device_id=?", (device.device_id,)).fetchone()
        if entitlement and entitlement["trial_used"] and entitlement["plan"] != "pending" and device.trial_token:
            db.execute("INSERT OR IGNORE INTO trial_claims(token,device_id) VALUES(?,?)",
                       (device.trial_token, device.device_id))
        if entitlement and not entitlement["trial_used"]:
            claimed = False
            if device.trial_token:
                claim = db.execute("INSERT OR IGNORE INTO trial_claims(token,device_id) VALUES(?,?)",
                                   (device.trial_token, device.device_id))
                claimed = claim.rowcount == 1
            if not claimed:
                db.execute("UPDATE subscriptions SET trial_used=?,plan='pending' WHERE device_id=?",
                           (int(bool(device.trial_token)), device.device_id))
            else:
                today = datetime.now(timezone.utc).date()
                expiry = add_months(today, 1) - timedelta(days=1)
                db.execute("UPDATE devices SET active=1,expires_at=? WHERE id=?", (expiry.isoformat(), device.device_id))
                db.execute("UPDATE subscriptions SET trial_used=1,trial_started_at=?,plan='trial' WHERE device_id=?", (now_iso(), device.device_id))
        row = db.execute("SELECT active,expires_at FROM devices WHERE id=?", (device.device_id,)).fetchone()
    return {"registered": True, "activation_required": not enabled_device(row)}


@APP.get("/api/v1/config")
async def device_config(device_id: str, request: Request):
    device = await run_in_threadpool(verify_device, device_id, request)
    await check_proof(device_id, request)
    enabled = enabled_device(device)
    with database() as db:
        settings = dict(db.execute("SELECT * FROM settings WHERE id=1").fetchone())
        if enabled:
            playlist_rows = db.execute("""
                SELECT DISTINCT p.id,p.name,p.url FROM playlists p
                LEFT JOIN device_playlists dp ON dp.playlist_id=p.id
                WHERE p.is_global=1 OR dp.device_id=? ORDER BY p.id DESC
            """, (device_id,)).fetchall()
        else:
            playlist_rows = []
    return {
        "active": enabled, "expires_at": device["expires_at"],
        "app_name": settings["app_name"], "accent_color": settings["accent_color"],
        "background_url": settings["background_url"], "logo_url": settings["logo_url"],
        "playlists": [dict(r) for r in playlist_rows] + (personal_for_config(device_id) if enabled else [])
    }


@APP.get("/admin")
def admin_page(_: bool = Depends(admin_only)):
    return FileResponse(BASE / "static" / "admin.html")


@APP.get("/admin/assets/{file_name}")
def admin_asset(file_name: str, _: bool = Depends(admin_only)):
    if file_name not in {"app.css", "app.js", "reseller-admin.js"}:
        raise HTTPException(404)
    return FileResponse(BASE / "static" / file_name)


DEVICE_COLUMNS = """SELECT d.id, mac_alias, key_tail, active, expires_at, label,
    created_at, last_seen, s.plan, s.trial_started_at
    FROM devices d LEFT JOIN subscriptions s ON s.device_id=d.id"""


def device_status(row):
    device = dict(row)
    device["effective_active"] = enabled_device(device)
    device["status"] = ("pending" if not device["active"] and device["plan"] == "pending"
                        else "disabled" if not device["active"]
                        else "expired" if not device["effective_active"]
                        else "trial" if device["plan"] == "trial" else "active")
    return device


@APP.get("/admin/api/state")
def admin_state(page: int = Query(1, ge=1), page_size: int = Query(50, ge=1, le=100),
                search: str = Query("", max_length=100), _: bool = Depends(admin_only)):
    # instr treats %, _ and quotes literally; never interpolate a search into SQL.
    needle = search.strip().lower()
    where = " WHERE instr(lower(d.id || ' ' || mac_alias || ' ' || label), ?) > 0" if needle else ""
    args = (needle,) if needle else ()
    with database() as db:
        total = db.execute("SELECT count(*) FROM devices d" + where, args).fetchone()[0]
        pages = max(1, (total + page_size - 1) // page_size)
        page = min(page, pages)
        devices = [device_status(row) for row in db.execute(DEVICE_COLUMNS + where +
            " ORDER BY last_seen DESC, d.id LIMIT ? OFFSET ?", (*args, page_size, (page - 1) * page_size))]
        latest = [device_status(row) for row in db.execute(DEVICE_COLUMNS +
            " ORDER BY last_seen DESC, d.id LIMIT 6")]
        counts = db.execute("""SELECT count(*), coalesce(sum(active != 0 AND
            (expires_at IS NULL OR expires_at >= ?)),0) FROM devices""",
            (datetime.now(timezone.utc).date().isoformat(),)).fetchone()
        playlists = [dict(row) for row in db.execute(
            "SELECT id,name,url,is_global,created_at FROM playlists ORDER BY id DESC")]
        settings = dict(db.execute("SELECT * FROM settings WHERE id=1").fetchone())
    return {"devices": devices, "latest_devices": latest, "playlists": playlists,
            "settings": settings, "stats": {"devices": counts[0], "active": counts[1]},
            "pagination": {"page": page, "page_size": page_size, "pages": pages, "total": total}}


@APP.get("/admin/api/devices/{device_id}")
def device_details(device_id: str, _: bool = Depends(admin_only)):
    with database() as db:
        row = db.execute(DEVICE_COLUMNS + " WHERE d.id=?", (device_id,)).fetchone()
        if row is None:
            raise HTTPException(404, "Device not found")
        assigned = [r[0] for r in db.execute(
            "SELECT playlist_id FROM device_playlists WHERE device_id=?", (device_id,))]
    return {"device": device_status(row), "playlist_ids": assigned}


@APP.put("/admin/api/devices/{device_id}")
def set_activation(device_id: str, payload: ActivationInput, _: bool = Depends(admin_only)):
    expiry = payload.expires_at
    if payload.active and payload.plan == "year":
        expiry = (add_months(datetime.now(timezone.utc).date(), 12) - timedelta(days=1)).isoformat()
    elif payload.active and payload.plan == "lifetime":
        expiry = None
    with database() as db:
        cursor = db.execute("UPDATE devices SET active=?,expires_at=?,label=? WHERE id=?", (
            int(payload.active), expiry, payload.label, device_id))
        if not cursor.rowcount:
            raise HTTPException(404, "Device not found")
        db.execute("""INSERT INTO subscriptions(device_id,trial_used,plan) VALUES(?,1,?)
            ON CONFLICT(device_id) DO UPDATE SET trial_used=1,plan=excluded.plan""", (device_id, payload.plan))
    return {"saved": True}


@APP.post("/admin/api/playlists")
def create_playlist(payload: PlaylistInput, _: bool = Depends(admin_only)):
    with database() as db:
        cur = db.execute("INSERT INTO playlists(name,url,is_global,created_at) VALUES(?,?,?,?)", (
            payload.name.strip(), payload.url.strip(), int(payload.is_global), now_iso()))
        ident = cur.lastrowid
    return {"id": ident}


@APP.put("/admin/api/playlists/{playlist_id}")
def edit_playlist(playlist_id: int, payload: PlaylistInput, _: bool = Depends(admin_only)):
    with database() as db:
        cursor = db.execute("UPDATE playlists SET name=?,url=?,is_global=? WHERE id=?", (
            payload.name.strip(), payload.url.strip(), int(payload.is_global), playlist_id))
        if not cursor.rowcount:
            raise HTTPException(404, "Playlist not found")
    return {"saved": True}


@APP.delete("/admin/api/playlists/{playlist_id}")
def remove_playlist(playlist_id: int, _: bool = Depends(admin_only)):
    with database() as db:
        db.execute("DELETE FROM playlists WHERE id=?", (playlist_id,))
    return {"deleted": True}


@APP.put("/admin/api/devices/{device_id}/playlists")
def set_assignments(device_id: str, payload: AssignmentInput, _: bool = Depends(admin_only)):
    with database() as db:
        if not db.execute("SELECT 1 FROM devices WHERE id=?", (device_id,)).fetchone():
            raise HTTPException(404, "Device not found")
        # Prevent invisible/invalid entries from being added to device associations.
        existing = {r[0] for r in db.execute("SELECT id FROM playlists WHERE is_global=0")}
        if not set(payload.playlist_ids).issubset(existing):
            raise HTTPException(400, "Unknown or global playlist")
        db.execute("DELETE FROM device_playlists WHERE device_id=?", (device_id,))
        db.executemany("INSERT INTO device_playlists(device_id,playlist_id) VALUES(?,?)",
                       [(device_id, p) for p in set(payload.playlist_ids)])
    return {"saved": True}


@APP.put("/admin/api/settings")
def set_theme(payload: ThemeInput, _: bool = Depends(admin_only)):
    with database() as db:
        db.execute("""UPDATE settings SET app_name=?,accent_color=?,background_url=?,logo_url=?
                      WHERE id=1""", (payload.app_name.strip(), payload.accent_color,
                                     payload.background_url.strip(), payload.logo_url.strip()))
    return {"saved": True}


def add_months(day: date, months: int) -> date:
    index = day.year * 12 + day.month - 1 + months
    year, month = divmod(index, 12)
    return date(year, month + 1, min(day.day, calendar.monthrange(year, month + 1)[1]))


def enabled_device(device):
    return bool(device["active"] and (not device["expires_at"] or
        date.fromisoformat(device["expires_at"]) >= datetime.now(timezone.utc).date()))


def personal_for_config(device_id):
    with database() as db:
        # Negative IDs cannot collide with administrator playlist IDs.
        return [dict(row) for row in db.execute(
            "SELECT -id AS id,name,url FROM personal_playlists WHERE device_id=?", (device_id,))]


def portal_device(request: Request):
    mac = request.headers.get("X-Device-Mac", "").strip().upper()
    key = request.headers.get("X-Device-Key", "")
    if not re.fullmatch(r"[0-9A-F]{2}(:[0-9A-F]{2}){5}", mac) or not 6 <= len(key) <= 128:
        raise HTTPException(403, "Invalid device credentials")
    limiter.check("portal-mac", mac, 20)
    with database() as db:
        rows = db.execute("SELECT * FROM devices WHERE mac_alias=?", (mac,)).fetchall()
    matched = [dict(row) for row in rows if hmac.compare_digest(row["key_hash"], key_digest(key))]
    if len(matched) != 1:
        raise HTTPException(403, "Invalid device credentials")
    return matched[0]


class PersonalPlaylistInput(PlaylistInput):
    # Device credentials grant view access. PIN is required for creating or modifying a playlist.
    model_config = {"extra": "forbid"}
    is_global: Literal[False] = False
    pin: str = Field(pattern=r"^[0-9]{4,8}$")


class PlaylistPin(BaseModel):
    pin: str = Field(pattern=r"^[0-9]{4,8}$")


@APP.get("/")
@APP.get("/portal")
def portal_page():
    return FileResponse(BASE / "static" / "portal.html")


@APP.get("/portal/assets/{file_name}")
def portal_asset(file_name: str):
    if file_name not in {"portal.js", "portal.css"}:
        raise HTTPException(404)
    return FileResponse(BASE / "static" / file_name)


@APP.get("/portal/api/account")
def portal_account(device=Depends(portal_device)):
    with database() as db:
        playlist = db.execute("SELECT name,url,(pin_hash IS NOT NULL) AS pin_set FROM personal_playlists WHERE device_id=?", (device["id"],)).fetchone()
        subscription = db.execute("SELECT plan FROM subscriptions WHERE device_id=?", (device["id"],)).fetchone()
    return {"active": enabled_device(device), "expires_at": device["expires_at"],
            "plan": subscription["plan"] if subscription else "existing",
            "playlist": dict(playlist) if playlist else None}


@APP.put("/portal/api/playlist")
def portal_save(payload: PersonalPlaylistInput, device=Depends(portal_device)):
    with database() as db:
        db.execute("BEGIN IMMEDIATE")
        existing = db.execute("SELECT pin_salt,pin_hash FROM personal_playlists WHERE device_id=?",
                              (device["id"],)).fetchone()
        if existing and existing["pin_hash"] and not valid_hash(
            payload.pin, existing["pin_salt"], existing["pin_hash"]
        ):
            raise HTTPException(403, "Invalid playlist PIN")
        # A legacy playlist without a PIN gains one on its first authorized edit.
        if existing and existing["pin_hash"]:
            salt, digest = existing["pin_salt"], existing["pin_hash"]
        else:
            salt, digest = make_hash(payload.pin)
        db.execute("""INSERT INTO personal_playlists(device_id,name,url,pin_salt,pin_hash)
            VALUES(?,?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET
            name=excluded.name,url=excluded.url,pin_salt=excluded.pin_salt,
            pin_hash=excluded.pin_hash""",
            (device["id"], payload.name.strip(), payload.url.strip(), salt, digest))
    return {"saved": True}


@APP.delete("/portal/api/playlist")
def portal_delete(payload: PlaylistPin, device=Depends(portal_device)):
    with database() as db:
        db.execute("BEGIN IMMEDIATE")
        existing = db.execute("SELECT pin_salt,pin_hash FROM personal_playlists WHERE device_id=?",
                              (device["id"],)).fetchone()
        if not existing:
            raise HTTPException(404, "Playlist not found")
        if not existing["pin_hash"]:
            raise HTTPException(409, "Set a PIN by editing the legacy playlist first")
        if not valid_hash(payload.pin, existing["pin_salt"], existing["pin_hash"]):
            raise HTTPException(403, "Invalid playlist PIN")
        db.execute("DELETE FROM personal_playlists WHERE device_id=?", (device["id"],))
    return {"deleted": True}


@APP.get("/api/v1/time")
def server_time():
    utc = datetime.now(timezone.utc).replace(microsecond=0)
    unix = int(utc.timestamp())
    return {"unix_seconds": unix, "unix": unix, "timestamp": unix,
            "server_time": unix, "server_time_ms": unix * 1000,
            "utc": utc.isoformat()}


@APP.get("/health")
def health():
    return {"status": "ok"}

install_reseller_endpoints(APP, database, limiter, admin_only, now_iso, add_months)

app = APP




