"""Shared SQLite rate limits; no device keys, customer URLs or raw IPs are stored."""
import hashlib
import sqlite3
import time
from pathlib import Path

from fastapi import HTTPException


class RateLimiter:
    def __init__(self, path):
        self.path = str(path)
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with sqlite3.connect(self.path) as db:
            db.execute('PRAGMA journal_mode=WAL')
            db.execute('CREATE TABLE IF NOT EXISTS limits (bucket TEXT PRIMARY KEY, started INTEGER NOT NULL, hits INTEGER NOT NULL)')

    def check(self, scope, identity, limit, seconds=60, now=None):
        now = int(time.time() if now is None else now)
        bucket = hashlib.sha256((scope + ':' + identity).encode()).hexdigest()
        with sqlite3.connect(self.path, timeout=5) as db:
            db.execute('BEGIN IMMEDIATE')
            db.execute('DELETE FROM limits WHERE started <= ?', (now - 3600,))
            row = db.execute('SELECT started,hits FROM limits WHERE bucket=?', (bucket,)).fetchone()
            if row and now < row[0] + seconds:
                if row[1] >= limit:
                    raise HTTPException(429, 'Too many requests', headers={'Retry-After': str(max(1, row[0] + seconds - now))})
                db.execute('UPDATE limits SET hits=hits+1 WHERE bucket=?', (bucket,))
            else:
                db.execute('INSERT OR REPLACE INTO limits VALUES(?,?,1)', (bucket, now))
