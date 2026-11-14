import hashlib
import importlib
import os
import tempfile
import time
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
from admin_auth import COOKIE


class AdminSessionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.env = patch.dict(os.environ, {
            'DATABASE_PATH': self.tmp.name + '/app.db',
            'SECURITY_DATABASE_PATH': self.tmp.name + '/limits.db',
            'ADMIN_USERNAME': 'qa', 'ADMIN_PASSWORD': 'test-only'})
        self.env.start()
        import main
        self.main = importlib.reload(main)
        self.client = TestClient(self.main.app)
        self.headers = {'X-Panel-Request': '1'}

    def tearDown(self):
        self.client.close()
        self.env.stop()
        self.tmp.cleanup()

    def login(self, **options):
        return self.client.post('/admin/api/login', json={'username': 'qa', 'password': 'test-only'},
                                headers=self.headers, **options)

    def test_login_logout_revokes_token_and_keeps_device_data(self):
        identity = {'device_id': '00000000-0000-4000-8000-000000000001',
                    'mac_alias': '12:23:34:45:56:67', 'device_key': 'AB123C'}
        self.assertEqual(self.client.post('/api/v1/register', json=identity).status_code, 200)
        with self.main.database() as db:
            before = [tuple(r) for r in db.execute('SELECT * FROM devices')]
        self.assertEqual(self.login().status_code, 200)
        token = self.client.cookies.get(COOKIE)
        with self.main.database() as db:
            row = dict(db.execute('SELECT * FROM admin_sessions').fetchone())
        self.assertEqual(row['token_hash'], hashlib.sha256(token.encode()).hexdigest())
        self.assertNotIn(token, str(row))
        self.assertNotIn('test-only', str(row))
        self.assertEqual(self.client.get('/admin/api/state').status_code, 200)
        self.assertEqual(self.client.get('/admin').status_code, 200)
        self.assertEqual(self.client.post('/admin/api/logout', headers=self.headers).status_code, 200)
        self.assertEqual(self.client.get('/admin/api/state').status_code, 401)
        self.client.cookies.set(COOKIE, token, path='/admin')
        self.assertEqual(self.client.get('/admin/api/state').status_code, 401)
        with self.main.database() as db:
            self.assertEqual([tuple(r) for r in db.execute('SELECT * FROM devices')], before)

    def test_expiry_rotation_and_credential_change(self):
        self.login()
        old = self.client.cookies.get(COOKIE)
        self.login()
        self.assertNotEqual(old, self.client.cookies.get(COOKIE))
        with self.main.database() as db:
            self.assertIsNone(db.execute('SELECT * FROM admin_sessions WHERE token_hash=?',
                                        (hashlib.sha256(old.encode()).hexdigest(),)).fetchone())
            db.execute('UPDATE admin_sessions SET expires_at=?', (int(time.time()) - 1,))
        self.assertEqual(self.client.get('/admin/api/state').status_code, 401)
        self.login()
        with patch.dict(os.environ, {'ADMIN_PASSWORD': 'changed'}):
            self.assertEqual(self.client.get('/admin/api/state').status_code, 401)

    def test_csrf_headers_on_login_and_logout(self):
        for path in ['/admin/api/login', '/admin/api/logout']:
            for headers in [{}, {**self.headers, 'Origin': 'https://evil.example'}]:
                self.assertEqual(self.client.post(path, json={'username': 'qa', 'password': 'test-only'},
                                                 headers=headers).status_code, 403)
        self.login()
        self.assertEqual(self.client.post('/admin/api/logout', headers={'X-Panel-Request': '1',
                         'Origin': 'https://evil.example'}).status_code, 403)
        self.assertEqual(self.client.get('/admin/api/state').status_code, 200)

    def test_cookie_secure_behind_proxy_and_localhost(self):
        for base, secure in [('http://panel.example', True), ('https://localhost', True),
                             ('http://127.0.0.1', False)]:
            with TestClient(self.main.app, base_url=base) as client:
                response = client.post('/admin/api/login', json={'username': 'qa', 'password': 'test-only'},
                                       headers=self.headers)
                cookie = response.headers['set-cookie'].lower()
                self.assertEqual('; secure' in cookie, secure)
                for flag in ['httponly', 'samesite=strict', 'path=/admin', 'max-age=43200']:
                    self.assertIn(flag, cookie)

    def test_login_rate_limit_and_no_validation_secret_echo(self):
        payload = {'username': 'qa', 'password': 'bad'}
        for _ in range(30):
            self.assertEqual(self.client.post('/admin/api/login', json=payload, headers=self.headers).status_code, 401)
        self.assertEqual(self.client.post('/admin/api/login', json=payload, headers=self.headers).status_code, 429)
        result = self.client.post('/admin/api/login', json={'password': 'do-not-echo'}, headers=self.headers)
        self.assertEqual(result.status_code, 422)
        self.assertNotIn('do-not-echo', result.text)

    def test_api_basic_compatibility_and_no_session_fallback(self):
        self.assertEqual(self.client.get('/admin/api/state', auth=('qa', 'test-only')).status_code, 200)
        result = self.client.get('/admin', auth=('qa', 'test-only'), follow_redirects=False)
        self.assertEqual(result.status_code, 303)
        self.assertEqual(result.headers['location'], '/admin/login')
        self.client.cookies.set(COOKIE, 'invalid', path='/admin')
        self.assertEqual(self.client.get('/admin/api/state', auth=('qa', 'test-only')).status_code, 401)
        self.assertNotIn('www-authenticate', self.client.get('/admin/api/state').headers)

    def test_login_page_public_assets_and_missing_env_fail_closed(self):
        for path in ['/admin/login', '/admin/login.js']:
            result = self.client.get(path)
            self.assertEqual(result.status_code, 200)
            self.assertEqual(result.headers['cache-control'], 'no-store')
        self.assertEqual(self.client.get('/admin/assets/app.js').status_code, 401)
        with patch.dict(os.environ, {'ADMIN_PASSWORD': ''}):
            self.assertEqual(self.login().status_code, 401)

    def test_initialize_preserves_sessions_across_reload(self):
        self.login()
        self.main = importlib.reload(self.main)
        with TestClient(self.main.app) as client:
            client.cookies.update(self.client.cookies)
            self.assertEqual(client.get('/admin/api/state').status_code, 200)
