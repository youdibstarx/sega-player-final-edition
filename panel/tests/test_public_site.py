import importlib
import os
import tempfile
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient


class PublicSiteTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.env = patch.dict(os.environ, {
            "DATABASE_PATH": self.tmp.name + "/app.db",
            "SECURITY_DATABASE_PATH": self.tmp.name + "/limits.db",
            "ADMIN_USERNAME": "qa",
            "ADMIN_PASSWORD": "test",
            "DEVICE_PROOF_REQUIRED": "false",
        })
        self.env.start()
        import main
        self.module = importlib.reload(main)
        self.client = TestClient(self.module.app)

    def tearDown(self):
        self.client.close()
        self.env.stop()
        self.tmp.cleanup()

    def test_homepage_is_public_and_distinct_from_portal(self):
        home = self.client.get("/")
        self.assertEqual(home.status_code, 200)
        self.assertIn("SEGA PLAYER", home.text)
        self.assertIn('href="/portal"', home.text)
        self.assertIn("لا يوفر التطبيق قنوات", home.text)
        self.assertNotIn('id="login"', home.text)
        portal = self.client.get("/portal")
        self.assertEqual(portal.status_code, 200)
        self.assertIn('id="login"', portal.text)

    def test_assets_and_asset_allowlist(self):
        for path in ("/site/assets/site.css", "/site/assets/theme.js", "/portal/assets/portal.css"):
            self.assertEqual(self.client.get(path).status_code, 200)
        for path in ("/site/assets/main.py", "/site/assets/../../main.py"):
            self.assertNotEqual(self.client.get(path).status_code, 200)

    def test_admin_remains_protected(self):
        response = self.client.get("/admin", follow_redirects=False)
        self.assertEqual(response.status_code, 303)
        self.assertEqual(response.headers['location'], '/admin/login')
        self.assertEqual(self.client.get('/admin/api/state').status_code, 401)


if __name__ == "__main__":
    unittest.main()
