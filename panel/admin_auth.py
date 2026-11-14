"""Revocable browser sessions; explicit HTTP Basic API clients stay compatible."""
import hashlib
import hmac
import os
import re
import secrets
import time

from fastapi import HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse
from pydantic import BaseModel, Field

COOKIE = "sega_admin_session"
SESSION_SECONDS = 12 * 60 * 60


def install(app, database, limiter, base):
    with database() as db:
        db.execute("""CREATE TABLE IF NOT EXISTS admin_sessions (
            token_hash TEXT PRIMARY KEY, credential_tag TEXT NOT NULL,
            expires_at INTEGER NOT NULL
        )""")
        db.execute("CREATE INDEX IF NOT EXISTS admin_session_expiry ON admin_sessions(expires_at)")

    def credentials_match(username, password):
        admin = os.getenv("ADMIN_USERNAME", "")
        secret = os.getenv("ADMIN_PASSWORD", "")
        user_ok = hmac.compare_digest(username.encode(), admin.encode())
        password_ok = hmac.compare_digest(password.encode(), secret.encode())
        return bool(admin and secret and user_ok and password_ok)

    def credential_tag(token):
        # Bind sessions to current env credentials; no reusable password verifier.
        return hmac.new(os.getenv("ADMIN_PASSWORD", "").encode(),
                        (os.getenv("ADMIN_USERNAME", "") + "\0" + token).encode(),
                        hashlib.sha256).hexdigest()

    def current_session(request):
        token = request.cookies.get(COOKIE, "")
        if not re.fullmatch(r"[A-Za-z0-9_-]{64}", token):
            return False
        if not os.getenv("ADMIN_USERNAME") or not os.getenv("ADMIN_PASSWORD"):
            return False
        with database() as db:
            row = db.execute("SELECT credential_tag FROM admin_sessions WHERE token_hash=? AND expires_at>?",
                             (hashlib.sha256(token.encode()).hexdigest(), int(time.time()))).fetchone()
        return bool(row and hmac.compare_digest(row["credential_tag"], credential_tag(token)))

    def authorize(request, credentials):
        if COOKIE in request.cookies:
            if current_session(request):
                return True
            # Do not replace a revoked browser session with cached Basic auth.
            raise HTTPException(401, "Admin login required")
        if credentials and credentials_match(credentials.username, credentials.password):
            return True
        if credentials:
            limiter.check("admin-login", request.client.host if request.client else "unknown", 30)
        # No Basic challenge: browsers use the login form and can log out.
        raise HTTPException(401, "Admin login required")

    class Login(BaseModel):
        model_config = {"extra": "forbid"}
        username: str = Field(min_length=1, max_length=256)
        password: str = Field(min_length=1, max_length=1024)

    @app.get("/admin/login")
    def login_page(request: Request):
        if current_session(request):
            return RedirectResponse("/admin", status_code=303)
        return FileResponse(base / "static" / "admin-login.html")

    @app.get("/admin/login.js")
    def login_script():
        return FileResponse(base / "static" / "admin-login.js")

    @app.post("/admin/api/login")
    def login(payload: Login, request: Request):
        limiter.check("admin-session-login", request.client.host if request.client else "unknown", 30, 300)
        if not credentials_match(payload.username, payload.password):
            raise HTTPException(401, "Invalid admin credentials")
        token = secrets.token_urlsafe(48)
        now = int(time.time())
        with database() as db:
            db.execute("DELETE FROM admin_sessions WHERE expires_at<=?", (now,))
            old_token = request.cookies.get(COOKIE, "")
            db.execute("DELETE FROM admin_sessions WHERE token_hash=?",
                       (hashlib.sha256(old_token.encode()).hexdigest(),))
            db.execute("INSERT INTO admin_sessions VALUES(?,?,?)",
                       (hashlib.sha256(token.encode()).hexdigest(), credential_tag(token), now + SESSION_SECONDS))
        response = JSONResponse({"logged_in": True})
        host = request.url.hostname
        secure = request.url.scheme == "https" or host not in {"testserver", "localhost", "127.0.0.1", "::1"}
        response.set_cookie(COOKIE, token, max_age=SESSION_SECONDS, path="/admin",
                            secure=secure, httponly=True, samesite="strict")
        return response

    @app.post("/admin/api/logout")
    def logout(request: Request):
        token = request.cookies.get(COOKIE, "")
        with database() as db:
            db.execute("DELETE FROM admin_sessions WHERE token_hash=?",
                       (hashlib.sha256(token.encode()).hexdigest(),))
        response = JSONResponse({"logged_out": True})
        response.delete_cookie(COOKIE, path="/admin", httponly=True, samesite="strict")
        return response

    @app.get("/admin")
    def admin_page(request: Request):
        if not current_session(request):
            return RedirectResponse("/admin/login", status_code=303)
        return FileResponse(base / "static" / "admin.html")

    return authorize
