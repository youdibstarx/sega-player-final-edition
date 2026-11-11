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
            "DATABASE_PATH": self.tmp.name+"/app.db",
            "SECURITY_DATABASE_PATH": self.tmp.name+"/limits.db",
            "ADMIN_USERNAME": "qa", "ADMIN_PASSWORD": "test",
            "DEVICE_PROOF_REQUIRED": "false"})
        self.env.start()
        import main
        self.m = importlib.reload(main)
        self.c = TestClient(self.m.app)
        self.admin = {"X-Panel-Request": "1", "Authorization": "Basic "+base64.b64encode(b"qa:test").decode()}
        self.r1 = {"Authorization": "Basic "+base64.b64encode(b"dealer1:strong-password-1").decode(), "X-Reseller-Request": "1"}
        self.r2 = {"Authorization": "Basic "+base64.b64encode(b"dealer2:strong-password-2").decode(), "X-Reseller-Request": "1"}
        self.device = str(uuid.uuid4())
        self.assertEqual(self.c.post("/api/v1/register", json={"device_id":self.device,"mac_alias":"12:23:34:45:56:67","device_key":"AB123C"}).status_code,200)

    def tearDown(self):
        self.c.close()
        self.env.stop()
        self.tmp.cleanup()

    def create(self, username, password):
        response = self.c.post("/admin/api/resellers", headers=self.admin, json={"username":username,"password":password})
        self.assertEqual(response.status_code,200,response.text)
        return response.json()["id"]

    def test_scoped_device_credit_and_ledger(self):
        one=self.create("dealer1","strong-password-1")
        two=self.create("dealer2","strong-password-2")
        self.assertEqual(self.c.get("/reseller/api/state").status_code,401)
        self.assertEqual(self.c.get("/reseller/api/state",headers=self.r1).json()["devices"],[])
        self.assertEqual(self.c.post("/reseller/api/activate",headers=self.r1,json={"device_id":self.device}).status_code,404)
        self.assertEqual(self.c.post(f"/admin/api/resellers/{one}/devices",headers=self.admin,json={"device_id":self.device}).status_code,200)
        self.assertEqual(self.c.post(f"/admin/api/resellers/{two}/devices",headers=self.admin,json={"device_id":self.device}).status_code,409)
        self.assertEqual(self.c.post("/reseller/api/activate",headers=self.r1,json={"device_id":self.device}).status_code,409)
        self.assertEqual(self.c.post(f"/admin/api/resellers/{one}/credits",headers=self.admin,json={"amount":2}).status_code,200)
        self.assertEqual(self.c.post("/reseller/api/activate",headers={k:v for k,v in self.r1.items() if k!="X-Reseller-Request"},json={"device_id":self.device}).status_code,403)
        result=self.c.post("/reseller/api/activate",headers=self.r1,json={"device_id":self.device})
        self.assertEqual(result.status_code,200,result.text)
        self.assertEqual(result.json()["credits"],1)
        state=self.c.get("/reseller/api/state",headers=self.r1).json()
        self.assertEqual(len(state["devices"]),1)
        self.assertEqual([x["delta"] for x in state["ledger"]],[-1,2])
        self.assertEqual(self.c.get("/reseller/api/state",headers=self.r2).json()["devices"],[])
        self.assertEqual(self.c.get("/reseller/api/state",headers=self.r2).json()["ledger"],[])

    def test_credentials_and_duplicate(self):
        self.create("dealer1","strong-password-1")
        self.assertEqual(self.c.post("/admin/api/resellers",headers=self.admin,json={"username":"dealer1","password":"strong-password-1"}).status_code,409)
        bad={"Authorization":"Basic "+base64.b64encode(b"dealer1:wrong-password").decode()}
        self.assertEqual(self.c.get("/reseller/api/state",headers=bad).status_code,401)
        self.assertEqual(self.c.get("/admin/api/resellers",headers=self.r1).status_code,401)

if __name__ == "__main__":
    unittest.main()
