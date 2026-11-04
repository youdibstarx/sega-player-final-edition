import base64
import importlib
import os
import tempfile
import unittest
import uuid
from datetime import date
from unittest.mock import patch
from fastapi.testclient import TestClient

class PortalTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.env=patch.dict(os.environ,{'DATABASE_PATH':self.tmp.name+'/app.db','SECURITY_DATABASE_PATH':self.tmp.name+'/limits.db','ADMIN_USERNAME':'qa','ADMIN_PASSWORD':'test','DEVICE_PROOF_REQUIRED':'false'})
        self.env.start()
        import main
        self.m=importlib.reload(main)
        self.c=TestClient(self.m.app)
        self.d={'device_id':str(uuid.uuid4()),'mac_alias':'12:23:34:45:56:67','device_key':'AB123C','trial_token':uuid.uuid4().hex * 2}
        self.h={'X-Device-Mac':self.d['mac_alias'],'X-Device-Key':self.d['device_key'],'X-Portal-Request':'1'}
        self.admin={'X-Panel-Request':'1','Authorization':'Basic '+base64.b64encode(b'qa:test').decode()}
    def tearDown(self):
        self.c.close();self.env.stop();self.tmp.cleanup()
    def register(self):
        return self.c.post('/api/v1/register',json=self.d)
    def config(self):
        return self.c.get('/api/v1/config',params={'device_id':self.d['device_id']},headers={'X-Device-Key':self.d['device_key']})
    def test_trial_once_expiry_and_admin_plans(self):
        self.assertEqual(self.register().status_code,200)
        first=self.config().json();self.assertTrue(first['active'])
        self.register();self.assertEqual(first['expires_at'],self.config().json()['expires_at'])
        with self.m.database() as db:db.execute("UPDATE devices SET expires_at='2000-01-01'")
        self.m.initialize();self.register();self.assertFalse(self.config().json()['active'])
        for plan in ('year','lifetime'):
            r=self.c.put('/admin/api/devices/'+self.d['device_id'],headers=self.admin,json={'active':True,'plan':plan})
            self.assertEqual(r.status_code,200)
            cfg=self.config().json();self.assertTrue(cfg['active'])
            self.assertEqual(cfg['expires_at'] is None,plan=='lifetime')
        self.c.put('/admin/api/devices/'+self.d['device_id'],headers=self.admin,json={'active':False})
        self.register();self.assertFalse(self.config().json()['active'])
    def test_legacy_device_never_gets_new_trial(self):
        d=self.d
        with self.m.database() as db:
            db.execute('INSERT INTO devices VALUES(?,?,?,?,?,?,?,?,?)',(d['device_id'],d['mac_alias'],self.m.key_digest(d['device_key']),'123C',0,None,'legacy','2020-01-01','2020-01-01'))
        self.m.initialize();self.register();self.assertFalse(self.config().json()['active'])
    def test_private_playlist_isolation_update_and_delete(self):
        self.register()
        payload={'name':'Mine','url':'https://media.example.org/private.m3u'}
        self.assertEqual(self.c.put('/portal/api/playlist',headers=self.h,json=payload).status_code,200)
        cfg=self.config().json();self.assertEqual(cfg['playlists'][0]['url'],payload['url'])
        self.assertLess(cfg['playlists'][0]['id'],0)
        other={**self.d,'device_id':str(uuid.uuid4()),'mac_alias':'AA:BB:CC:DD:EE:FF','device_key':'ZZ999Z'}
        self.c.post('/api/v1/register',json=other)
        oh={**self.h,'X-Device-Mac':other['mac_alias'],'X-Device-Key':other['device_key']}
        self.assertIsNone(self.c.get('/portal/api/account',headers=oh).json()['playlist'])
        bad={**self.h,'X-Device-Key':other['device_key']}
        self.assertEqual(self.c.get('/portal/api/account',headers=bad).status_code,403)
        for extra in ({'device_id':other['device_id']},{'is_global':True},{'active':True}):
            self.assertEqual(self.c.put('/portal/api/playlist',headers=self.h,json={**payload,**extra}).status_code,422)
        self.assertEqual(self.c.put('/portal/api/playlist',headers=self.h,json={**payload,'url':'file:///etc/passwd'}).status_code,422)
        self.c.put('/portal/api/playlist',headers=self.h,json={**payload,'name':'Updated'})
        self.assertEqual(self.c.get('/portal/api/account',headers=self.h).json()['playlist']['name'],'Updated')
        self.c.delete('/portal/api/playlist',headers=self.h)
        self.assertEqual(self.config().json()['playlists'],[])
    def test_proof_and_csrf_still_enforced(self):
        with patch.dict(os.environ,{'DEVICE_PROOF_REQUIRED':'true'}):
            self.assertEqual(self.register().status_code,403)
        with self.m.database() as db:self.assertEqual(db.execute('SELECT active FROM devices').fetchone()[0],0)
        self.register()
        payload={'name':'Mine','url':'https://media.example.org/a.m3u'}
        self.assertEqual(self.c.put('/portal/api/playlist',headers={**self.h,'Origin':'https://evil.example'},json=payload).status_code,403)
        self.assertEqual(self.c.put('/portal/api/playlist',headers={'X-Device-Mac':self.d['mac_alias'],'X-Device-Key':self.d['device_key']},json=payload).status_code,403)
        with patch.dict(os.environ,{'DEVICE_PROOF_REQUIRED':'true'}):self.assertEqual(self.config().status_code,403)
    def test_portal_credentials_are_rate_limited(self):
        self.register()
        for _ in range(20):
            self.assertEqual(self.c.get('/portal/api/account',headers={**self.h,'X-Device-Key':'BADKEY'}).status_code,403)
        self.assertEqual(self.c.get('/portal/api/account',headers=self.h).status_code,429)

    def test_public_assets_and_calendar_boundaries(self):
        self.assertEqual(self.c.get('/portal').status_code,200)
        self.assertEqual(self.c.get('/portal/assets/portal.js').status_code,200)
        self.assertEqual(self.c.get('/portal/assets/main.py').status_code,404)
        self.assertEqual(self.m.add_months(date(2024,1,31),1),date(2024,2,29))
        self.assertEqual(self.m.add_months(date(2024,2,29),12),date(2025,2,28))

    def test_reinstallation_cannot_repeat_trial(self):
        self.assertFalse(self.register().json()['activation_required'])
        other={**self.d,'device_id':str(uuid.uuid4()),'mac_alias':'AA:BB:CC:DD:EE:FF'}
        r=self.c.post('/api/v1/register',json=other)
        self.assertEqual(r.status_code,200)
        self.assertTrue(r.json()['activation_required'])

    def test_old_client_pending_then_upgrade_and_existing_access_preserved(self):
        token=self.d.pop('trial_token')
        self.assertTrue(self.register().json()['activation_required'])
        self.d['trial_token']=token
        self.assertFalse(self.register().json()['activation_required'])
        self.d.pop('trial_token')
        self.assertFalse(self.register().json()['activation_required'])

    def test_existing_trial_binds_token_without_extending(self):
        self.register()
        expiry=self.config().json()['expires_at']
        with self.m.database() as db: db.execute('DELETE FROM trial_claims')
        self.register()
        self.assertEqual(self.config().json()['expires_at'],expiry)
        other={**self.d,'device_id':str(uuid.uuid4()),'mac_alias':'AA:BB:CC:DD:EE:FF'}
        self.assertTrue(self.c.post('/api/v1/register',json=other).json()['activation_required'])

    def test_parallel_trial_claims_grant_only_one(self):
        from concurrent.futures import ThreadPoolExecutor
        identities=[{**self.d,'device_id':str(uuid.uuid4()),'mac_alias':f'AA:BB:CC:DD:EE:{i:02X}'} for i in range(4)]
        with ThreadPoolExecutor(max_workers=4) as pool:
            results=list(pool.map(lambda d:self.c.post('/api/v1/register',json=d),identities))
        self.assertTrue(all(r.status_code==200 for r in results))
        self.assertEqual(sum(not r.json()['activation_required'] for r in results),1)

    def test_time_endpoint_and_server_subscription_status(self):
        import time
        r=self.c.get('/api/v1/time')
        self.assertEqual(r.status_code,200)
        self.assertLess(abs(r.json()['unix_seconds']-time.time()),5)
        self.assertEqual(r.headers['cache-control'],'no-store')
        self.register()
        def status():
            return self.c.get('/admin/api/state',headers=self.admin).json()['devices'][0]
        self.assertEqual(status()['status'],'trial')
        with self.m.database() as db: db.execute("UPDATE devices SET expires_at='2000-01-01'")
        self.assertEqual(status()['status'],'expired')
        self.assertFalse(status()['effective_active'])
        with self.m.database() as db: db.execute("UPDATE devices SET active=0")
        self.assertEqual(status()['status'],'disabled')
