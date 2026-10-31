import base64
import tempfile
import unittest
import uuid
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import HTTPException
from proof import DeviceProofs, canonical, decode_key

class ProofTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.proofs=DeviceProofs(Path(self.tmp.name)/'proofs.db')
        self.key=ec.generate_private_key(ec.SECP256R1())
        self.path='/api/v1/config?device_id=id'
    def tearDown(self): self.tmp.cleanup()
    def headers(self,key=None,time='100',nonce=None):
        key=key or self.key
        nonce=nonce or str(uuid.uuid4())
        public=base64.b64encode(key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
        sig=key.sign(canonical('id','GET',self.path,b'',time,nonce),ec.ECDSA(hashes.SHA256()))
        return {'x-proof-public-key':public,'x-proof-signature':base64.b64encode(sig).decode(),'x-proof-time':time,'x-proof-nonce':nonce}
    def verify(self,h,required=False,**kwargs):
        self.proofs.verify('id','GET',self.path,kwargs.pop('body',b''),h,required,now=kwargs.pop('now',100))
    def approve(self):
        h=self.headers();self.verify(h)
        self.proofs.approve('id',decode_key(h['x-proof-public-key'])[1])
    def test_wire_format(self):
        self.assertEqual(canonical('id','GET',self.path,b'','100','nonce').decode(),'SEGA-PROOF-1\nid\nGET\n/api/v1/config?device_id=id\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\n100\nnonce')
    def test_legacy_only_before_approval(self):
        self.verify({})
        self.approve()
        with self.assertRaises(HTTPException): self.verify({})
        self.verify(self.headers())
    def test_pending_not_auto_trusted(self):
        with self.assertRaises(HTTPException): self.verify(self.headers(),True)
        self.assertEqual(len(self.proofs.pending()),1)
        self.approve()
        self.verify(self.headers(),True)
    def test_replay_tamper_expiry_and_wrong_key(self):
        self.approve()
        h=self.headers();self.verify(h)
        for bad in (h,self.headers(time='1'),self.headers(ec.generate_private_key(ec.SECP256R1()))):
            with self.assertRaises(HTTPException): self.verify(bad,now=300 if bad['x-proof-time']=='1' else 100)
        with self.assertRaises(HTTPException): self.verify(self.headers(),body=b'tampered')
    def test_approval_does_not_replace_existing_key(self):
        self.approve()
        h=self.headers(ec.generate_private_key(ec.SECP256R1()))
        with self.assertRaises(HTTPException): self.verify(h)
        with self.assertRaises(HTTPException): self.proofs.approve('id',decode_key(h['x-proof-public-key'])[1])
    def test_nonce_survives_restart(self):
        self.approve();h=self.headers();self.verify(h)
        self.proofs=DeviceProofs(Path(self.tmp.name)/'proofs.db')
        with self.assertRaises(HTTPException): self.verify(h)
