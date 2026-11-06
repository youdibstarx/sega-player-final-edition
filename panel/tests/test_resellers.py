"""End-to-end tests for reseller role isolation, credit ledger and activation safety."""
import base64
import importlib
import os
import tempfile
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

from fastapi.testclient import TestClient


class ResellerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.env = patch.dict(os.environ, {
            "DATABASE_PATH": self.tmp.name + "/app.db",
            "SECURITY_DATABASE_PATH": self.tmp.name + "/limits.db",
            "ADMIN_USERNAME": "qa",
            "ADMIN_PASSWORD": "admin-test-only",
            "DEVICE_PROOF_REQUIRED": "false"
        })
        self.env.start()
        import main
        self.m = importlib.reload(main)
        self.c = TestClient(self.m.app)
        self.admin = {"X-Panel-Request": "1", "Authorization": self.basic("qa", "admin-test-only")}
        self.main_auth = self.creds("main@example.org", "main-password-123")
        self.sub_auth = self.creds("sub@example.org", "sub-password-123")

    def tearDown(self):
        self.c.close()
        self.env.stop()
        self.tmp.cleanup()

    @staticmethod
    def basic(email, password):
        return "Basic " + base64.b64encode(f"{email}:{password}".encode()).decode()

    def creds(self, email, password):
        return {"X-Reseller-Request": "1", "Authorization": self.basic(email, password)}

    def create_main(self, email="main@example.org", password="main-password-123"):
        return self.c.post("/admin/api/resellers", headers=self.admin,
                           json={"email": email, "password": password})

    def credit(self, email="main@example.org", points=4):
        return self.c.post(f"/admin/api/resellers/{email}/credit", headers=self.admin,
                           json={"points": points})

    def register(self, mac="AA:BB:CC:DD:EE:FF"):
        data = {"device_id": str(uuid.uuid4()), "mac_alias": mac, "device_key": "AB123C"}
        r = self.c.post("/api/v1/register", json=data)
        self.assertEqual(r.status_code, 200)
        return data

    def test_admin_and_single_level_hierarchy_and_transfer(self):
        self.assertEqual(self.c.post("/admin/api/resellers", json={
            "email": "main@example.org", "password": "main-password-123"
        }).status_code, 403)
        self.assertEqual(self.create_main().status_code, 200)
        self.assertEqual(self.create_main().status_code, 409)
        self.assertEqual(self.credit(points=3).status_code, 200)
        self.assertEqual(self.c.get("/reseller/api/account", headers=self.main_auth)
                         .json()["balance_points"], 3)
        self.assertEqual(self.c.post("/reseller/api/subresellers", headers=self.main_auth, json={
            "email": "sub@example.org", "password": "sub-password-123"
        }).status_code, 200)
        self.assertEqual(self.c.post("/admin/api/resellers/sub@example.org/credit",
                                     headers=self.admin, json={"points": 1}).status_code, 403)
        self.assertEqual(self.c.post("/reseller/api/subresellers", headers=self.sub_auth, json={
            "email": "third@example.org", "password": "third-password-123"
        }).status_code, 403)
        self.assertEqual(self.c.post("/reseller/api/transfer", headers=self.sub_auth, json={
            "email": "main@example.org", "points": 0.5
        }).status_code, 403)
        self.assertEqual(self.c.post("/reseller/api/transfer", headers=self.main_auth, json={
            "email": "sub@example.org", "points": 0.3
        }).status_code, 422)
        self.assertEqual(self.c.post("/reseller/api/transfer", headers=self.main_auth, json={
            "email": "sub@example.org", "points": 4
        }).status_code, 409)
        self.assertEqual(self.c.post("/reseller/api/transfer", headers=self.main_auth, json={
            "email": "sub@example.org", "points": 1.5
        }).status_code, 200)
        self.assertEqual(self.c.get("/reseller/api/account", headers=self.main_auth)
                         .json()["balance_points"], 1.5)
        self.assertEqual(self.c.get("/reseller/api/account", headers=self.sub_auth)
                         .json()["balance_points"], 1.5)
        self.assertEqual(self.create_main("other@example.org", "other-password-123").status_code, 200)
        outsider = self.creds("other@example.org", "other-password-123")
        self.assertEqual(self.c.post("/reseller/api/transfer", headers=outsider, json={
            "email": "sub@example.org", "points": 0.5
        }).status_code, 403)
        audit = self.c.get("/admin/api/resellers", headers=self.admin).json()
        self.assertEqual(len(audit["transfers"]), 2)
        self.assertEqual(len(audit["resellers"]), 3)

    def test_pricing_mac_status_renewals_lifetime_and_idempotency(self):
        device = self.register()
        self.assertEqual(self.create_main().status_code, 200)
        self.assertEqual(self.credit(points=4).status_code, 200)
        mac = device["mac_alias"]
        self.assertTrue(self.c.get("/reseller/api/mac/" + mac,
                                   headers=self.main_auth).json()["active"])
        previous_expiry = self.c.get("/reseller/api/mac/" + mac,
                                     headers=self.main_auth).json()["expires_at"]
        for plan, expected_cost, left in [
            ("six_months", 0.5, 3.5), ("year", 1, 2.5), ("lifetime", 2, 0.5)
        ]:
            request_id = str(uuid.uuid4())
            data = {"mac": mac, "plan": plan, "request_id": request_id}
            result = self.c.post("/reseller/api/activate", headers=self.main_auth, json=data)
            self.assertEqual(result.status_code, 200, result.text)
            self.assertEqual(result.json()["points_charged"], expected_cost)
            self.assertFalse(result.json()["already_processed"])
            # Android reads the same activation record immediately, without a separate
            # reseller-only state or PIN/password in the playback API response.
            android = self.c.get("/api/v1/config", params={"device_id": device["device_id"]},
                                 headers={"X-Device-Key": device["device_key"]})
            self.assertEqual(android.status_code, 200)
            self.assertTrue(android.json()["active"])
            self.assertEqual(android.json()["expires_at"], result.json()["expires_at"])
            self.assertNotIn("pin_hash", android.text)
            self.assertNotIn("password_hash", android.text)
            again = self.c.post("/reseller/api/activate", headers=self.main_auth, json=data)
            self.assertEqual(again.status_code, 200)
            self.assertTrue(again.json()["already_processed"])
            balance = self.c.get("/reseller/api/account", headers=self.main_auth).json()
            self.assertEqual(balance["balance_points"], left)
            if plan == "six_months":
                self.assertGreater(result.json()["expires_at"], previous_expiry)
            if plan == "lifetime":
                self.assertIsNone(result.json()["expires_at"])
                self.assertEqual(self.c.get("/reseller/api/mac/" + mac,headers=self.main_auth)
                                 .json()["plan"], "lifetime")
                self.assertEqual(self.c.post("/reseller/api/activate",headers=self.main_auth,json={
                    "mac":mac,"plan":"year","request_id":str(uuid.uuid4())
                }).status_code,409)
        self.assertEqual(self.c.get("/admin/api/resellers",headers=self.admin).json()
                         ["activations"][0]["points"],2)

    def test_ambiguous_and_unknown_mac_do_not_charge(self):
        self.create_main()
        self.credit(points=1)
        self.register("01:02:03:04:05:06")
        self.register("01:02:03:04:05:06")
        mac = "01:02:03:04:05:06"
        self.assertEqual(self.c.get("/reseller/api/mac/"+mac,headers=self.main_auth).status_code,409)
        response=self.c.post("/reseller/api/activate",headers=self.main_auth,json={
            "mac":mac,"plan":"year","request_id":str(uuid.uuid4())
        })
        self.assertEqual(response.status_code,409)
        self.assertEqual(self.c.post("/reseller/api/activate",headers=self.main_auth,json={
            "mac":"AA:AA:AA:AA:AA:AA","plan":"year","request_id":str(uuid.uuid4())
        }).status_code,404)
        self.assertEqual(self.c.get("/reseller/api/account",headers=self.main_auth)
                         .json()["balance_points"],1)

    def test_csrf_and_credentials_and_concurrent_retry(self):
        self.create_main()
        self.credit(points=1)
        device=self.register()
        data={"mac":device["mac_alias"],"plan":"year","request_id":str(uuid.uuid4())}
        self.assertEqual(self.c.post("/reseller/api/activate",headers={
            "Authorization":self.main_auth["Authorization"]
        },json=data).status_code,403)
        self.assertEqual(self.c.post("/reseller/api/activate",headers={
            **self.main_auth,"Origin":"https://evil.example"
        },json=data).status_code,403)
        self.assertEqual(self.c.get("/reseller/api/account",headers=self.creds(
            "main@example.org","bad-password-000")).status_code,401)
        def activate(_):
            return self.c.post("/reseller/api/activate",headers=self.main_auth,json=data)
        with ThreadPoolExecutor(max_workers=2) as pool:
            results=list(pool.map(activate,range(2)))
        self.assertEqual([r.status_code for r in results],[200,200])
        self.assertEqual(sum(not r.json()["already_processed"] for r in results),1)
        self.assertEqual(self.c.get("/reseller/api/account",headers=self.main_auth)
                         .json()["balance_points"],0)
        self.assertEqual(self.c.get("/reseller").status_code,200)
        self.assertEqual(self.c.get("/reseller/assets/reseller.js").status_code,200)
        self.assertEqual(self.c.get("/reseller/assets/main.py").status_code,404)
        self.assertEqual(self.c.get("/admin/resellers").status_code,401)


if __name__ == "__main__":
    unittest.main()
