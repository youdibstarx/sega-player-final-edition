"""Run: cd panel && python -m unittest discover -s tests -v"""
import base64
import importlib
import os
import sqlite3
import tempfile
from pathlib import Path
import unittest
import uuid

from fastapi.testclient import TestClient


class PanelApiTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        os.environ["DATABASE_PATH"] = os.path.join(cls.tmp.name, "app.db")
        os.environ["ADMIN_USERNAME"] = "qa-admin"
        os.environ["ADMIN_PASSWORD"] = "test-password-only"
        import main
        importlib.reload(main)
        cls.client = TestClient(main.app)
        cls.headers = {
            "X-Panel-Request": "1",
            "Authorization": "Basic " + base64.b64encode(b"qa-admin:test-password-only").decode()
        }

    @classmethod
    def tearDownClass(cls):
        cls.client.close()
        cls.tmp.cleanup()

    def test_full_lifecycle(self):
        identity = {
            "device_id": str(uuid.uuid4()),
            "mac_alias": "12:23:34:45:56:67",
            "device_key": "sample-key-with-more-than-thirty-characters-00001",
            "trial_token": uuid.uuid4().hex * 2
        }
        self.assertEqual(self.client.post("/api/v1/register", json=identity).status_code, 200)
        self.assertEqual(self.client.post("/api/v1/register", json=identity).status_code, 200)
        query = {"device_id": identity["device_id"]}
        device_headers = {"X-Device-Key": identity["device_key"]}
        pending = self.client.get("/api/v1/config", params=query, headers=device_headers).json()
        self.assertTrue(pending["active"])
        self.assertEqual(pending["app_name"], "SEGA IPTV Player")
        self.assertEqual(self.client.get("/admin/api/state").status_code, 401)
        self.assertEqual(self.client.get("/api/v1/config", params=query,
                        headers={"X-Device-Key": "invalid"}).status_code, 403)

        response = self.client.post("/admin/api/playlists", headers=self.headers,
            json={"name": "Demo", "url": "https://media.example.org/lists/legal.m3u", "is_global": True})
        self.assertEqual(response.status_code, 200)
        playlist_id = response.json()["id"]
        response = self.client.put(f"/admin/api/devices/{identity['device_id']}", headers=self.headers,
            json={"active": True, "expires_at": None, "label": "Living room"})
        self.assertEqual(response.status_code, 200)
        active = self.client.get("/api/v1/config", params=query, headers=device_headers).json()
        self.assertTrue(active["active"])
        self.assertEqual(active["playlists"][0]["id"], playlist_id)

        self.assertEqual(self.client.put("/admin/api/settings", headers=self.headers, json={
            "app_name": "Brand Demo", "app_theme": "ruby", "accent_color": "#12AFCC",
            "background_url": "https://media.example.org/background.jpg", "logo_url": ""}).status_code, 200)
        updated = self.client.get("/api/v1/config", params=query, headers=device_headers).json()
        self.assertEqual(updated["app_name"], "Brand Demo")
        self.assertEqual(updated["accent_color"], "#12AFCC")
        self.assertEqual(updated["app_theme"], "ruby")

        self.client.put(f"/admin/api/devices/{identity['device_id']}", headers=self.headers,
            json={"active": False, "expires_at": None, "label": "Living room"})
        revoked = self.client.get("/api/v1/config", params=query, headers=device_headers).json()
        self.assertFalse(revoked["active"])
        self.assertEqual(revoked["playlists"], [])

    def test_theme_setting_migration_preserves_existing_branding(self):
        import main
        legacy_db = Path(self.tmp.name) / "legacy-settings.db"
        with sqlite3.connect(legacy_db) as db:
            db.execute("""CREATE TABLE settings (
                id INTEGER PRIMARY KEY CHECK (id=1), app_name TEXT NOT NULL,
                accent_color TEXT NOT NULL, background_url TEXT NOT NULL, logo_url TEXT NOT NULL
            )""")
            db.execute("INSERT INTO settings VALUES (1, 'Old Brand', '#123456', '', '')")
        original = main.DB_FILE
        try:
            main.DB_FILE = legacy_db
            main.initialize()
            with main.database() as db:
                row = db.execute("SELECT app_name,accent_color,app_theme FROM settings WHERE id=1").fetchone()
            self.assertEqual(tuple(row), ("Old Brand", "#123456", "classic"))
        finally:
            main.DB_FILE = original

    def test_invalid_links(self):
        res = self.client.post("/admin/api/playlists", headers=self.headers,
            json={"name": "Bad", "url": "file:///secret.m3u", "is_global": True})
        self.assertEqual(res.status_code, 422)
        invalid_theme = self.client.put("/admin/api/settings", headers=self.headers, json={
            "app_name": "Brand Demo", "app_theme": "missing", "accent_color": "#12AFCC",
            "background_url": "", "logo_url": ""})
        self.assertEqual(invalid_theme.status_code, 422)


if __name__ == "__main__":
    unittest.main()
