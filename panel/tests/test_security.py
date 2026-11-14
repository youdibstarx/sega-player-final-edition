import base64
import importlib
import os
import tempfile
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch
from fastapi import HTTPException
from fastapi.testclient import TestClient
from security import RateLimiter

class SecurityTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.env = patch.dict(os.environ, {'DATABASE_PATH': self.tmp.name+'/app.db', 'SECURITY_DATABASE_PATH': self.tmp.name+'/limits.db', 'ADMIN_USERNAME':'qa', 'ADMIN_PASSWORD':'test-only'})
        self.env.start()
        import main
        self.main = importlib.reload(main)
        self.client = TestClient(self.main.app)
        self.identity = {'device_id':str(uuid.uuid4()), 'mac_alias':'12:23:34:45:56:67', 'device_key':'AB123C', 'trial_token':uuid.uuid4().hex * 2}
        self.auth = {'Authorization':'Basic '+base64.b64encode(b'qa:test-only').decode()}
        self.headers = {**self.auth, 'X-Panel-Request':'1'}
    def tearDown(self):
        self.client.close()
        self.env.stop()
        self.tmp.cleanup()
    def config(self, key='AB123C'):
        return self.client.get('/api/v1/config', params={'device_id':self.identity['device_id']}, headers={'X-Device-Key':key})
    def test_existing_activation_preserved(self):
        d=self.identity
        with self.main.database() as db:
            db.execute('INSERT INTO devices VALUES(?,?,?,?,?,?,?,?,?)',(d['device_id'],d['mac_alias'],self.main.key_digest(d['device_key']),'123C',1,'2099-01-01','existing','2020-01-01','2020-01-01'))
            before=dict(db.execute('SELECT * FROM devices').fetchone())
        self.assertEqual(self.client.post('/api/v1/register',json=d).status_code,200)
        self.assertTrue(self.config().json()['active'])
        with self.main.database() as db:
            after=dict(db.execute('SELECT * FROM devices').fetchone())
        for key in before.keys()-{'last_seen'}:
            self.assertEqual(before[key],after[key])
        self.assertEqual(self.config().headers['cache-control'],'no-store')
    def test_new_short_key_and_no_takeover(self):
        self.assertEqual(self.client.post('/api/v1/register',json=self.identity).status_code,200)
        self.assertEqual(self.client.post('/api/v1/register',json={**self.identity,'device_key':'ZZ999Z'}).status_code,403)
        self.assertEqual(self.config('ZZ999Z').status_code,403)
        self.assertTrue(self.config().json()['active'])
        self.assertEqual(self.config().json()['playlists'],[])
    def test_validation_no_secret_echo(self):
        r=self.client.post('/api/v1/register',json={**self.identity,'device_key':'sensitive-invalid-key'})
        self.assertEqual(r.status_code,422)
        self.assertNotIn('sensitive',r.text)
    def test_device_limit_shared_between_endpoints(self):
        self.client.post('/api/v1/register',json=self.identity)
        for _ in range(19): self.assertEqual(self.config('ZZ999Z').status_code,403)
        r=self.client.post('/api/v1/register',json=self.identity)
        self.assertEqual(r.status_code,429)
        self.assertGreater(int(r.headers['retry-after']),0)
    def test_forwarded_header_not_trusted(self):
        for n in range(120):
            self.assertEqual(self.client.get('/admin/api/state',headers={'X-Forwarded-For':f'192.0.2.{n}'}).status_code,401)
        self.assertEqual(self.client.get('/admin/api/state',headers={'X-Forwarded-For':'192.0.2.250'}).status_code,429)
    def test_admin_guess_limit(self):
        bad={'Authorization':'Basic '+base64.b64encode(b'qa:wrong').decode()}
        for _ in range(30): self.assertEqual(self.client.get('/admin/api/state',headers=bad).status_code,401)
        self.assertEqual(self.client.get('/admin/api/state',headers=bad).status_code,429)
    def test_successful_admin_requests_do_not_consume_failed_login_budget(self):
        for _ in range(35):
            self.assertEqual(self.client.get('/admin/api/state', headers=self.auth).status_code, 200)
        bad = {'Authorization': 'Basic ' + base64.b64encode(b'qa:wrong').decode()}
        for _ in range(30):
            self.assertEqual(self.client.get('/admin/api/state', headers=bad).status_code, 401)
        self.assertEqual(self.client.get('/admin/api/state', headers=bad).status_code, 429)
        self.assertEqual(self.client.get('/admin/api/state', headers=self.auth).status_code, 200)
    def test_csrf(self):
        payload={'name':'Demo','url':'https://example.org/list.m3u'}
        for headers in (self.auth,{**self.headers,'Origin':'https://evil.example'}):
            self.assertEqual(self.client.post('/admin/api/playlists',json=payload,headers=headers).status_code,403)
        self.assertEqual(self.client.post('/admin/api/playlists',json=payload,headers={**self.headers,'Origin':'http://testserver'}).status_code,200)
        self.assertEqual(self.client.get('/openapi.json').status_code,404)
    def test_limiter_atomic_shared_and_expires(self):
        a,b=RateLimiter(self.tmp.name+'/shared.db'),RateLimiter(self.tmp.name+'/shared.db')
        def attempt(n):
            try:
                (a if n%2 else b).check('test','identity',5,now=100)
                return True
            except HTTPException: return False
        with ThreadPoolExecutor(max_workers=8) as pool:
            self.assertEqual(sum(pool.map(attempt,range(20))),5)
        b.check('test','identity',5,now=161)
    def test_registration_race(self):
        def register(_): return self.client.post('/api/v1/register',json=self.identity).status_code
        with ThreadPoolExecutor(max_workers=4) as pool:
            self.assertEqual(list(pool.map(register,range(4))),[200]*4)
        with self.main.database() as db:
            self.assertEqual(db.execute('SELECT count(*) FROM devices').fetchone()[0],1)
    def test_signed_android_protocol_end_to_end(self):
        import json,time
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec
        from proof import canonical,decode_key
        key=ec.generate_private_key(ec.SECP256R1())
        public=base64.b64encode(key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
        def headers(method,path,body=b''):
            stamp,nonce=str(int(time.time())),str(uuid.uuid4())
            signature=key.sign(canonical(self.identity['device_id'],method,path,body,stamp,nonce),ec.ECDSA(hashes.SHA256()))
            return {'X-Proof-Public-Key':public,'X-Proof-Time':stamp,'X-Proof-Nonce':nonce,'X-Proof-Signature':base64.b64encode(signature).decode(),'X-Device-Key':self.identity['device_key'],'Content-Type':'application/json'}
        body=json.dumps(self.identity,separators=(',',':')).encode()
        path='/api/v1/register'
        self.assertEqual(self.client.post(path,content=body,headers=headers('POST',path,body)).status_code,200)
        fp=decode_key(public)[1]
        status_path='/admin/api/proofs'
        self.assertEqual(self.client.get(status_path).status_code,401)
        proof_state=self.client.get(status_path,headers=self.auth).json()
        self.assertEqual(proof_state['pending'],[{'device':self.identity['device_id'],'fingerprint':fp}])
        self.assertEqual(proof_state['approved'],[])
        self.assertFalse(proof_state['required'])
        path=f"/admin/api/devices/{self.identity['device_id']}/proof"
        self.assertEqual(self.client.post(path,json={'fingerprint':fp},headers=self.auth).status_code,403)
        self.assertEqual(self.client.post(path,json={'fingerprint':'0'*64},headers=self.headers).status_code,409)
        self.assertEqual(self.client.post(path,json={'fingerprint':fp},headers=self.headers).status_code,200)
        proof_state=self.client.get(status_path,headers=self.auth).json()
        self.assertEqual(proof_state['pending'],[])
        self.assertEqual(proof_state['approved'],[{'device':self.identity['device_id'],'fingerprint':fp}])
        self.assertEqual(self.client.post(path,json={'fingerprint':fp},headers=self.headers).status_code,409)
        with patch.dict(os.environ,{'DEVICE_PROOF_REQUIRED':'true'}):
            self.assertTrue(self.client.get(status_path,headers=self.auth).json()['required'])
        self.assertEqual(self.config().status_code,403)
        path='/api/v1/config?device_id='+self.identity['device_id']
        h=headers('GET',path)
        self.assertEqual(self.client.get(path,headers=h).status_code,200)
        self.assertEqual(self.client.get(path,headers=h).status_code,403)

    def test_cannot_approve_unknown_device(self):
        path=f"/admin/api/devices/{self.identity['device_id']}/proof"
        self.assertEqual(self.client.post(path,json={'fingerprint':'0'*64},headers=self.headers).status_code,404)

    def test_public_clock_bootstrap_matches_signed_android_contract(self):
        import time
        response = self.client.get('/api/v1/time')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers['cache-control'], 'no-store')
        clock = response.json()
        self.assertLessEqual(abs(clock['unix_seconds'] - time.time()), 2)
        self.assertEqual(clock['server_time_ms'], clock['unix_seconds'] * 1000)
        self.assertNotIn('device', response.text)
