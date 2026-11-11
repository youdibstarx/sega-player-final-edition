import base64
import importlib
import os
import tempfile
import unittest
import uuid
from unittest.mock import patch
from fastapi.testclient import TestClient


class NewPanelFeaturesTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.env = patch.dict(os.environ, {
            "DATABASE_PATH": self.tmp.name + "/app.db",
            "SECURITY_DATABASE_PATH": self.tmp.name + "/limits.db",
            "ADMIN_USERNAME": "qa", "ADMIN_PASSWORD": "test",
            "DEVICE_PROOF_REQUIRED": "false",
        })
        self.env.start()
        import main
        self.m = importlib.reload(main)
        self.c = TestClient(self.m.app)
        self.d = {"device_id": str(uuid.uuid4()), "mac_alias": "12:23:34:45:56:67", "device_key": "AB123C"}
        self.h = {"X-Device-Mac": self.d["mac_alias"], "X-Device-Key": self.d["device_key"], "X-Portal-Request": "1"}
        self.admin = {"X-Panel-Request": "1", "Authorization": "Basic " + base64.b64encode(b"qa:test").decode()}
        self.assertEqual(self.c.post("/api/v1/register", json=self.d).status_code, 200)

    def tearDown(self):
        self.c.close()
        self.env.stop()
        self.tmp.cleanup()

    def test_multiple_lists_and_pin(self):
        a = self.c.post("/portal/api/playlists", headers=self.h, json={"name": "One", "url": "https://example.org/one.m3u"})
        b = self.c.post("/portal/api/playlists", headers=self.h, json={"name": "Two", "url": "https://example.org/two.m3u", "pin": "1234"})
        self.assertEqual((a.status_code, b.status_code), (200, 200))
        aid, bid = a.json()["id"], b.json()["id"]
        self.assertEqual(len(self.c.get("/portal/api/account", headers=self.h).json()["playlists"]), 2)
        self.assertEqual(len(self.c.get("/api/v1/config", params={"device_id": self.d["device_id"]}, headers={"X-Device-Key": self.d["device_key"]}).json()["playlists"]), 2)
        self.assertEqual(self.c.delete(f"/portal/api/playlists/{bid}", headers=self.h).status_code, 403)
        self.assertEqual(self.c.put(f"/portal/api/playlists/{bid}", headers=self.h, json={"name": "Wrong", "url": "https://example.org/x.m3u", "current_pin": "9999"}).status_code, 403)
        self.assertEqual(self.c.put(f"/portal/api/playlists/{bid}", headers=self.h, json={"name": "Two edited", "url": "https://example.org/x.m3u", "current_pin": "1234", "remove_pin": True}).status_code, 200)
        self.assertEqual(self.c.delete(f"/portal/api/playlists/{bid}", headers=self.h).status_code, 200)
        self.assertEqual(self.c.delete(f"/portal/api/playlists/{aid}", headers=self.h).status_code, 200)
        self.assertEqual(self.c.get("/portal/api/account", headers=self.h).json()["playlists"], [])

    def test_offers_are_private_until_published(self):
        payload = {"title": "Year", "price": "100 MAD", "duration": "12 months", "published": False}
        self.assertEqual(self.c.post("/admin/api/offers", json=payload).status_code, 401)
        added = self.c.post("/admin/api/offers", headers=self.admin, json=payload)
        self.assertEqual(added.status_code, 200)
        self.assertEqual(self.c.get("/api/public/offers").json()["offers"], [])
        offer_id = added.json()["id"]
        payload["published"] = True
        self.assertEqual(self.c.put(f"/admin/api/offers/{offer_id}", headers=self.admin, json=payload).status_code, 200)
        self.assertEqual(len(self.c.get("/api/public/offers").json()["offers"]), 1)


if __name__ == "__main__":
    unittest.main()
