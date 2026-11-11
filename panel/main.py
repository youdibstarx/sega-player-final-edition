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

from fastapi import Depends, FastAPI, HTTPException, Request, status
from fastapi.responses import FileResponse, JSONResponse
from fastapi.exceptions import RequestValidationError
from starlette.concurrency import run_in_threadpool
from security import RateLimiter
from proof import DeviceProofs
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
        CREATE INDEX IF NOT EXISTS devices_mac_lookup ON devices(mac_alias);
        INSERT OR IGNORE INTO subscriptions(device_id) SELECT id FROM devices;
        INSERT OR IGNORE INTO settings VALUES
            (1, 'SEGA IPTV Player', '#E5474C', '', '');
        """)

        # Upgrade legacy single-playlist storage in place without losing entries.
        columns = {r["name"] for r in db.execute("PRAGMA table_info(personal_playlists)")}
        if "pin_hash" not in columns:
            db.execute("ALTER TABLE personal_playlists ADD COLUMN pin_hash TEXT")
        if "pin_salt" not in columns:
            db.execute("ALTER TABLE personal_playlists ADD COLUMN pin_salt TEXT")
        indexes = db.execute("PRAGMA index_list(personal_playlists)").fetchall()
        if any(row["unique"] and [col["name"] for col in db.execute("PRAGMA index_info(" + str(row["name"]).replace('"', '""').join(['"', '"']) + ")")] == ["device_id"] for row in indexes):
            db.execute("""CREATE TABLE personal_playlists_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
                name TEXT NOT NULL, url TEXT NOT NULL,
                pin_hash TEXT, pin_salt TEXT
            )""")
            db.execute("""INSERT INTO personal_playlists_new(id,device_id,name,url,pin_hash,pin_salt)
                SELECT id,device_id,name,url,pin_hash,pin_salt FROM personal_playlists""")
            db.execute("DROP TABLE personal_playlists")
            db.execute("ALTER TABLE personal_playlists_new RENAME TO personal_playlists")
        db.execute("CREATE INDEX IF NOT EXISTS personal_playlists_device ON personal_playlists(device_id)")
        db.execute("""CREATE TABLE IF NOT EXISTS activation_offers (
            id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL,
            price TEXT NOT NULL, duration TEXT NOT NULL,
            published INTEGER NOT NULL DEFAULT 0
        )""")


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
    return {**proofs.status(), "required": os.getenv("DEVICE_PROOF_REQUIRED", "false").lower() == "true"}


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
        entitlement = db.execute("SELECT trial_used FROM subscriptions WHERE device_id=?", (device.device_id,)).fetchone()
        if entitlement and not entitlement["trial_used"]:
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
    if file_name not in {"app.css", "app.js", "offers-admin.js"}:
        raise HTTPException(404)
    return FileResponse(BASE / "static" / file_name)


@APP.get("/admin/api/state")
def admin_state(_: bool = Depends(admin_only)):
    with database() as db:
        devices = [dict(row) for row in db.execute("""
            SELECT d.id, mac_alias, key_tail, active, expires_at, label, created_at, last_seen, s.plan, s.trial_started_at
            FROM devices d LEFT JOIN subscriptions s ON s.device_id=d.id ORDER BY last_seen DESC
        """).fetchall()]
        playlists = [dict(row) for row in db.execute(
            "SELECT id,name,url,is_global,created_at FROM playlists ORDER BY id DESC").fetchall()]
        assignments = [dict(row) for row in db.execute("SELECT device_id,playlist_id FROM device_playlists")]
        settings = dict(db.execute("SELECT * FROM settings WHERE id=1").fetchone())
    return {"devices": devices, "playlists": playlists, "assignments": assignments, "settings": settings}


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
    # Public clients cannot choose assignment, device ID, activation or global scope.
    model_config = {"extra": "forbid"}
    is_global: Literal[False] = False


@APP.get("/")
def home_page():
    return FileResponse(BASE / "static" / "site.html")


@APP.get("/site/assets/{file_name}")
def site_asset(file_name: str):
    if file_name not in {"site.css", "theme.js", "offers.js"}:
        raise HTTPException(404)
    return FileResponse(BASE / "static" / file_name)


@APP.get("/portal")
def portal_page():
    return FileResponse(BASE / "static" / "portal.html")


@APP.get("/portal/assets/{file_name}")
def portal_asset(file_name: str):
    if file_name not in {"portal.js", "portal.css"}:
        raise HTTPException(404)
    return FileResponse(BASE / "static" / file_name)


class PersonalPlaylistCreate(BaseModel):
    model_config = {"extra": "forbid"}
    name: str = Field(min_length=1, max_length=100)
    url: str = Field(min_length=8, max_length=3000)
    pin: Optional[str] = Field(default=None, pattern=r"^[0-9]{4,8}$")

    @field_validator("url")
    @classmethod
    def check_url(cls, value):
        return PlaylistInput.validate_url(value)


class PersonalPlaylistUpdate(PersonalPlaylistCreate):
    current_pin: Optional[str] = Field(default=None, pattern=r"^[0-9]{4,8}$")
    remove_pin: bool = False


class PinCheck(BaseModel):
    pin: str = Field(pattern=r"^[0-9]{4,8}$")


def pin_digest(pin, salt):
    return hashlib.pbkdf2_hmac("sha256", pin.encode(), bytes.fromhex(salt), 250000).hex()


def pin_fields(pin):
    if pin is None:
        return None, None
    salt = os.urandom(16).hex()
    return pin_digest(pin, salt), salt


def playlist_view(row):
    return {"id": row["id"], "name": row["name"], "url": row["url"],
            "pin_enabled": bool(row["pin_hash"])}


def require_playlist(db, device_id, playlist_id):
    row = db.execute("SELECT * FROM personal_playlists WHERE device_id=? AND id=?",
                     (device_id, playlist_id)).fetchone()
    if row is None:
        raise HTTPException(404, "Playlist not found")
    return row


def check_existing_pin(row, pin):
    if row["pin_hash"] and (not pin or not hmac.compare_digest(
            row["pin_hash"], pin_digest(pin, row["pin_salt"]))):
        raise HTTPException(403, "Invalid playlist PIN")


@APP.get("/portal/api/account")
def portal_account(device=Depends(portal_device)):
    with database() as db:
        playlists = db.execute("SELECT * FROM personal_playlists WHERE device_id=? ORDER BY id",
                               (device["id"],)).fetchall()
        subscription = db.execute("SELECT plan FROM subscriptions WHERE device_id=?", (device["id"],)).fetchone()
    return {"active": enabled_device(device), "expires_at": device["expires_at"],
            "plan": subscription["plan"] if subscription else "existing",
            "playlist": {"name": playlists[0]["name"], "url": playlists[0]["url"]} if playlists else None,
            "playlists": [playlist_view(row) for row in playlists]}


# Legacy endpoint retained for existing clients. Changes only the oldest list.
@APP.put("/portal/api/playlist")
def portal_save(payload: PersonalPlaylistInput, device=Depends(portal_device)):
    with database() as db:
        row = db.execute("SELECT id,pin_hash FROM personal_playlists WHERE device_id=? ORDER BY id LIMIT 1",
                         (device["id"],)).fetchone()
        if row:
            if row["pin_hash"]:
                raise HTTPException(403, "PIN-protected playlist: use the new endpoint")
            db.execute("UPDATE personal_playlists SET name=?,url=? WHERE id=?",
                       (payload.name.strip(), payload.url.strip(), row["id"]))
        else:
            db.execute("INSERT INTO personal_playlists(device_id,name,url) VALUES(?,?,?)",
                       (device["id"], payload.name.strip(), payload.url.strip()))
    return {"saved": True}


@APP.delete("/portal/api/playlist")
def portal_delete(device=Depends(portal_device)):
    with database() as db:
        row = db.execute("SELECT id,pin_hash FROM personal_playlists WHERE device_id=? ORDER BY id LIMIT 1",
                         (device["id"],)).fetchone()
        if row:
            if row["pin_hash"]:
                raise HTTPException(403, "PIN-protected playlist: use the new endpoint")
            db.execute("DELETE FROM personal_playlists WHERE id=?", (row["id"],))
    return {"deleted": True}


@APP.post("/portal/api/playlists")
def portal_add(payload: PersonalPlaylistCreate, device=Depends(portal_device)):
    digest, salt = pin_fields(payload.pin)
    with database() as db:
        count = db.execute("SELECT COUNT(*) FROM personal_playlists WHERE device_id=?",
                           (device["id"],)).fetchone()[0]
        if count >= 25:
            raise HTTPException(400, "Playlist limit reached")
        cur = db.execute("""INSERT INTO personal_playlists(device_id,name,url,pin_hash,pin_salt)
                            VALUES(?,?,?,?,?)""",
                         (device["id"], payload.name.strip(), payload.url.strip(), digest, salt))
    return {"id": cur.lastrowid}


@APP.put("/portal/api/playlists/{playlist_id}")
def portal_update(playlist_id: int, payload: PersonalPlaylistUpdate, device=Depends(portal_device)):
    if payload.remove_pin and payload.pin:
        raise HTTPException(422, "Cannot set and remove PIN together")
    with database() as db:
        row = require_playlist(db, device["id"], playlist_id)
        check_existing_pin(row, payload.current_pin)
        digest, salt = (None, None) if payload.remove_pin else (
            pin_fields(payload.pin) if payload.pin else (row["pin_hash"], row["pin_salt"]))
        db.execute("""UPDATE personal_playlists SET name=?,url=?,pin_hash=?,pin_salt=? WHERE id=?""",
                   (payload.name.strip(), payload.url.strip(), digest, salt, playlist_id))
    return {"saved": True}


@APP.delete("/portal/api/playlists/{playlist_id}")
def portal_remove(playlist_id: int, payload: Optional[PinCheck] = None,
                  device=Depends(portal_device)):
    with database() as db:
        row = require_playlist(db, device["id"], playlist_id)
        check_existing_pin(row, payload.pin if payload else None)
        db.execute("DELETE FROM personal_playlists WHERE id=?", (playlist_id,))
    return {"deleted": True}


class OfferInput(BaseModel):
    model_config = {"extra": "forbid"}
    title: str = Field(min_length=1, max_length=100)
    price: str = Field(min_length=1, max_length=60)
    duration: str = Field(min_length=1, max_length=100)
    published: bool = False


@APP.get("/api/public/offers")
def public_offers():
    with database() as db:
        return {"offers": [dict(r) for r in db.execute(
            "SELECT id,title,price,duration FROM activation_offers WHERE published=1 ORDER BY id")]}


@APP.get("/admin/api/offers")
def admin_offers(_: bool = Depends(admin_only)):
    with database() as db:
        return {"offers": [dict(r) for r in db.execute("SELECT * FROM activation_offers ORDER BY id")]}


@APP.post("/admin/api/offers")
def admin_add_offer(payload: OfferInput, _: bool = Depends(admin_only)):
    with database() as db:
        cur = db.execute("INSERT INTO activation_offers(title,price,duration,published) VALUES(?,?,?,?)",
                         (payload.title.strip(), payload.price.strip(), payload.duration.strip(), int(payload.published)))
    return {"id": cur.lastrowid}


@APP.put("/admin/api/offers/{offer_id}")
def admin_edit_offer(offer_id: int, payload: OfferInput, _: bool = Depends(admin_only)):
    with database() as db:
        cur = db.execute("UPDATE activation_offers SET title=?,price=?,duration=?,published=? WHERE id=?",
                         (payload.title.strip(), payload.price.strip(), payload.duration.strip(), int(payload.published), offer_id))
        if not cur.rowcount:
            raise HTTPException(404, "Offer not found")
    return {"saved": True}


@APP.delete("/admin/api/offers/{offer_id}")
def admin_remove_offer(offer_id: int, _: bool = Depends(admin_only)):
    with database() as db:
        db.execute("DELETE FROM activation_offers WHERE id=?", (offer_id,))
    return {"deleted": True}


@APP.get("/health")
def health():
    return {"status": "ok"}

app = APP



