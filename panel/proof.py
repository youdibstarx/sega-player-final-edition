"""Installation key proof, explicitly NOT remote app attestation."""
import base64
import hashlib
import re
import sqlite3
import time
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import HTTPException


def decode_key(value):
    if len(value) > 256:
        raise ValueError('Invalid key')
    der = base64.b64decode(value, validate=True)
    key = serialization.load_der_public_key(der)
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError('Invalid key')
    return key, hashlib.sha256(der).hexdigest()


def canonical(device_id, method, path, body, timestamp, nonce):
    return '\n'.join(('SEGA-PROOF-1', device_id, method, path, hashlib.sha256(body).hexdigest(), timestamp, nonce)).encode()


class DeviceProofs:
    def __init__(self, path):
        self.path = str(path)
        with sqlite3.connect(self.path) as db:
            db.execute('PRAGMA journal_mode=WAL')
            db.executescript('''
              CREATE TABLE IF NOT EXISTS approved(device TEXT PRIMARY KEY, fingerprint TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS pending(device TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, public_key TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS nonces(device TEXT NOT NULL, nonce TEXT NOT NULL, expires INTEGER NOT NULL, PRIMARY KEY(device,nonce));
            ''')

    def verify(self, device_id, method, path, body, headers, required=False, now=None):
        now = int(time.time() if now is None else now)
        with sqlite3.connect(self.path, timeout=5) as db:
            approved = db.execute('SELECT fingerprint FROM approved WHERE device=?', (device_id,)).fetchone()
        values = [headers.get(k, '') for k in ('x-proof-time','x-proof-nonce','x-proof-public-key','x-proof-signature')]
        if not any(values):
            if required or approved:
                raise HTTPException(403, 'Device proof required')
            return
        stamp, nonce, public_key, signature = values
        try:
            if not re.fullmatch(r'[0-9]{1,12}', stamp) or abs(now-int(stamp)) > 120:
                raise ValueError()
            if not re.fullmatch(r'[a-fA-F0-9-]{36}', nonce) or len(signature) > 128:
                raise ValueError()
            key, fingerprint = decode_key(public_key)
            key.verify(base64.b64decode(signature, validate=True), canonical(device_id, method, path, body, stamp, nonce), ec.ECDSA(hashes.SHA256()))
        except Exception:
            raise HTTPException(403, 'Invalid device proof') from None
        with sqlite3.connect(self.path, timeout=5) as db:
            db.execute('BEGIN IMMEDIATE')
            # Re-read under the write lock: approval/revocation cannot race verification.
            approved = db.execute('SELECT fingerprint FROM approved WHERE device=?', (device_id,)).fetchone()
            if approved and approved[0] != fingerprint:
                raise HTTPException(403, 'Unapproved installation key')
            db.execute('DELETE FROM nonces WHERE expires < ?', (now,))
            try:
                db.execute('INSERT INTO nonces VALUES(?,?,?)', (device_id, nonce, now+240))
            except sqlite3.IntegrityError:
                raise HTTPException(403, 'Replayed request') from None
            if not approved:
                db.execute('INSERT OR REPLACE INTO pending VALUES(?,?,?)', (device_id, fingerprint, public_key))
        if required and not approved:
            raise HTTPException(403, 'Installation approval required')

    def pending(self):
        with sqlite3.connect(self.path) as db:
            db.row_factory = sqlite3.Row
            return [dict(r) for r in db.execute('SELECT device,fingerprint FROM pending')]

    def status(self):
        with sqlite3.connect(self.path) as db:
            db.row_factory = sqlite3.Row
            db.execute('BEGIN')
            return {
                'pending': [dict(r) for r in db.execute('SELECT device,fingerprint FROM pending ORDER BY device')],
                'approved': [dict(r) for r in db.execute('SELECT device,fingerprint FROM approved ORDER BY device')],
            }

    def approve(self, device, fingerprint):
        with sqlite3.connect(self.path) as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT fingerprint FROM pending WHERE device=?', (device,)).fetchone()
            if not row or row[0] != fingerprint:
                raise HTTPException(409, 'Pending fingerprint changed or missing')
            # Never silently replace an already approved installation.
            if db.execute('SELECT 1 FROM approved WHERE device=?', (device,)).fetchone():
                raise HTTPException(409, 'Installation already approved')
            db.execute('INSERT INTO approved VALUES(?,?)', (device, fingerprint))
            db.execute('DELETE FROM pending WHERE device=?', (device,))

