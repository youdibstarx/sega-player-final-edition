import base64
import importlib
import os
import tempfile
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient


class PaginationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.env = patch.dict(os.environ, {
            'DATABASE_PATH': self.tmp.name + '/app.db',
            'SECURITY_DATABASE_PATH': self.tmp.name + '/limits.db',
            'ADMIN_USERNAME': 'qa', 'ADMIN_PASSWORD': 'test',
        })
        self.env.start()
        import main
        self.m = importlib.reload(main)
        self.client = TestClient(self.m.app)
        self.headers = {'Authorization': 'Basic ' + base64.b64encode(b'qa:test').decode()}
        with self.m.database() as db:
            db.executemany('INSERT INTO devices VALUES(?,?,?,?,?,?,?,?,?)', [
                (f'device-{i:03}', f'AA:BB:CC:DD:EE:{i:03}', 'SECRET', '1234',
                 1, '2000-01-01' if i < 10 else None, 'جهاز خاص 100%_name' if i == 119 else f'Room {i}',
                 '2026-01-01', '2026-01-01') for i in range(120)])
            db.execute("INSERT INTO playlists VALUES(1,'Private','https://example.org/list.m3u',0,'2026-01-01')")
            db.execute("INSERT INTO device_playlists VALUES('device-119',1)")
        self.m.initialize()

    def tearDown(self):
        self.client.close()
        self.env.stop()
        self.tmp.cleanup()

    def get(self, **params):
        result = self.client.get('/admin/api/state', headers=self.headers, params=params)
        self.assertEqual(result.status_code, 200)
        return result.json()

    def test_bounded_pages_stable_ties_and_global_counts(self):
        first = self.get()
        self.assertEqual(len(first['devices']), 50)
        self.assertEqual(first['stats'], {'devices': 120, 'active': 110})
        self.assertEqual(len(first['latest_devices']), 6)
        all_ids = [d['id'] for page in (1, 2, 3) for d in self.get(page=page)['devices']]
        self.assertEqual(len(all_ids), 120)
        self.assertEqual(len(set(all_ids)), 120)
        self.assertEqual(all_ids, sorted(all_ids))
        self.assertEqual(self.get(page=999)['pagination']['page'], 3)
        self.assertNotIn('key_hash', str(first))
        self.assertNotIn('assignments', first)

    def test_literal_search_and_empty_results(self):
        for query in ('100%_', 'جهاز خاص', 'aa:bb:cc:dd:ee:119', 'DEVICE-119'):
            result = self.get(search=query)
            self.assertEqual([d['id'] for d in result['devices']], ['device-119'])
            self.assertEqual(result['stats']['devices'], 120)
        empty = self.get(search="' OR 1=1 --", page=20)
        self.assertEqual(empty['devices'], [])
        self.assertEqual(empty['pagination']['page'], 1)

    def test_details_outside_page_isolated_and_authenticated(self):
        url = '/admin/api/devices/device-119'
        self.assertEqual(self.client.get(url).status_code, 401)
        details = self.client.get(url, headers=self.headers).json()
        self.assertEqual(details['playlist_ids'], [1])
        self.assertEqual(details['device']['id'], 'device-119')
        other = self.client.get('/admin/api/devices/device-001', headers=self.headers).json()
        self.assertEqual(other['playlist_ids'], [])
        self.assertNotIn('key_hash', details['device'])
        self.assertEqual(self.client.get('/admin/api/devices/missing', headers=self.headers).status_code, 404)

    def test_query_limits(self):
        for params in ({'page': 0}, {'page_size': 101}, {'page_size': 0}, {'search': 'x' * 101}):
            self.assertEqual(self.client.get('/admin/api/state', headers=self.headers, params=params).status_code, 422)
