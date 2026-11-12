import base64
import importlib
import os
import tempfile
import unittest
import uuid
from unittest.mock import patch

from fastapi.testclient import TestClient


class ResellerTests(unittest.TestCase):
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
        self.m = importlib.reload(main)
        self.c = TestClient(self.m.app)
        self.admin = {
            "X-Panel-Request": "1",
            "Authorization": "Basic " + base64.b64encode(b"qa:test").decode(),
        }
        self.r1 = None
        self.r2 = None
        self.device = self.register_device("12:23:34:45:56:67")

    def register_device(self, mac):
        device_id = str(uuid.uuid4())
        response = self.c.post("/api/v1/register", json={
            "device_id": device_id, "mac_alias": mac, "device_key": "AB123C",
        })
        self.assertEqual(response.status_code, 200, response.text)
        return device_id

    def tearDown(self):
        self.c.close()
        self.env.stop()
        self.tmp.cleanup()

    def create(self, username, password):
        response = self.c.post("/admin/api/resellers", headers=self.admin,
                               json={"username": username, "password": password})
        self.assertEqual(response.status_code, 200, response.text)
        return response.json()["id"]

    def login(self, username, password):
        response = self.c.post("/reseller/api/login", headers={"X-Reseller-Request": "1"},
                               json={"username": username, "password": password})
        self.assertEqual(response.status_code, 200, response.text)
        token = response.cookies.get("sega_reseller_session")
        self.assertTrue(token)
        self.assertIn("httponly", response.headers["set-cookie"].lower())
        self.c.cookies.clear()
        return {"X-Reseller-Request": "1", "Cookie": "sega_reseller_session=" + token}

    def test_any_registered_unlicensed_mac_without_admin_assignment(self):
        one = self.create("dealer1", "strong-password-1")
        self.create("dealer2", "strong-password-2")
        self.assertEqual(self.c.get("/reseller/api/state").status_code, 401)
        self.r1 = self.login("dealer1", "strong-password-1")
        self.r2 = self.login("dealer2", "strong-password-2")
        self.assertEqual(self.c.get("/reseller/api/state", headers=self.r1).json()["devices"], [])

        # No admin assignment required. A registered device may have an active
        # free trial; a reseller is permitted to upgrade it to a paid year.
        self.assertEqual(self.c.post("/reseller/api/activate", headers=self.r1,
                                     json={"mac": "12:23:34:45:56:67"}).status_code, 409)
        self.assertEqual(self.c.post(f"/admin/api/resellers/{one}/credits",
                                     headers=self.admin, json={"amount": 2}).status_code, 200)

        self.assertEqual(self.c.post("/reseller/api/activate",
                                     headers={k: v for k, v in self.r1.items()
                                              if k != "X-Reseller-Request"},
                                     json={"mac": "12:23:34:45:56:67"}).status_code, 403)
        self.assertEqual(self.c.post("/reseller/api/activate", headers=self.r1,
                                     json={"mac": "ZZ:ZZ:ZZ:ZZ:ZZ:ZZ"}).status_code, 422)
        self.assertEqual(self.c.post("/reseller/api/activate", headers=self.r1,
                                     json={"mac": "11:22:33:44:55:66"}).status_code, 404)

        result = self.c.post("/reseller/api/activate", headers=self.r1,
                             json={"mac": "122334455667"})
        self.assertEqual(result.status_code, 200, result.text)
        self.assertEqual(result.json()["credits"], 1)
        state = self.c.get("/reseller/api/state", headers=self.r1).json()
        self.assertEqual(len(state["devices"]), 1)
        self.assertEqual(state["devices"][0]["id"], self.device)
        self.assertEqual([x["delta"] for x in state["ledger"]], [-1, 2])

        # Repeating an activation must not double-charge the reseller.
        self.assertEqual(self.c.post("/reseller/api/activate", headers=self.r1,
                                     json={"mac": "12:23:34:45:56:67"}).status_code, 409)
        self.assertEqual(self.c.get("/reseller/api/state", headers=self.r1).json()["credits"], 1)
        self.assertEqual(self.c.get("/reseller/api/state", headers=self.r2).json()["devices"], [])

        # Another reseller may independently activate a different registered MAC.
        self.register_device("AA:BB:CC:DD:EE:FF")
        self.assertEqual(self.c.post("/admin/api/resellers/2/credits",
                                     headers=self.admin, json={"amount": 1}).status_code, 200)
        second = self.c.post("/reseller/api/activate", headers=self.r2,
                             json={"mac": "AA:BB:CC:DD:EE:FF"})
        self.assertEqual(second.status_code, 200, second.text)

    def test_credentials_and_duplicate(self):
        self.create("dealer1", "strong-password-1")
        self.assertEqual(self.c.post("/admin/api/resellers", headers=self.admin,
                                     json={"username": "dealer1",
                                           "password": "strong-password-1"}).status_code, 409)
        denied = self.c.post("/reseller/api/login", headers={"X-Reseller-Request": "1"},
                             json={"username": "dealer1", "password": "wrong-password"})
        self.assertEqual(denied.status_code, 401)
        self.assertNotIn("set-cookie", denied.headers)
        self.r1 = self.login("dealer1", "strong-password-1")
        self.assertEqual(self.c.get("/admin/api/resellers", headers=self.r1).status_code, 401)

    def test_login_page_logout_and_mac_fields(self):
        self.create("dealer1", "strong-password-1")
        page = self.c.get("/reseller/login")
        self.assertEqual(page.status_code, 200)
        self.assertIn('id="login-form"', page.text)
        self.assertEqual(self.c.get("/reseller", follow_redirects=False).status_code, 303)
        self.assertEqual(self.c.get("/reseller/assets/reseller-login.js").status_code, 200)
        self.assertEqual(self.c.get("/reseller/assets/reseller.js").status_code, 401)
        denied = self.c.post("/reseller/api/login",
                             json={"username": "dealer1", "password": "strong-password-1"})
        self.assertEqual(denied.status_code, 403)
        session = self.login("dealer1", "strong-password-1")
        content = self.c.get("/reseller", headers=session)
        self.assertEqual(content.status_code, 200)
        self.assertIn("data-mac-input", content.text)
        self.assertIn('id="logout"', content.text)
        self.assertEqual(self.c.get("/reseller/api/state", headers=session).status_code, 200)
        self.assertIn("data-mac-input", self.c.get("/portal").text)
        self.assertIn("data-mac-input", self.c.get("/site/assets/theme.js").text)
        done = self.c.post("/reseller/api/logout", headers=session)
        self.assertEqual(done.status_code, 200)
        self.assertEqual(self.c.get("/reseller/api/state", headers=session).status_code, 401)



if __name__ == "__main__":
    unittest.main()
