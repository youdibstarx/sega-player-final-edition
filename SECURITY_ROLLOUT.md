# V3 protection rollout (0.5.2)

## What is implemented

Release and hardened-test builds use R8 code/resource optimization, no debuggable
flag, and encoded backend address bytes instead of a full URL BuildConfig string.
This is obfuscation, NOT secret encryption: runtime inspection can recover the URL.
The existing HTTPS certificate and hostname checks remain enabled.

Each hardened/release installation generates a P-256 signing key in Android
Keystore. Requests sign device ID, method, exact path/query, SHA-256 body hash,
timestamp and a random nonce (SHA256withECDSA, DER signature, Base64 encoding).
The private key is not exported. Hardware backing depends on the device; no claim
of hardware attestation is made. The existing MAC, device ID and six-character key
are not changed. A separate installation naturally has its own identity.

The copied panel verifies proofs and records nonces in a separate persistent SQLite
file. Requests older/newer than 120 seconds, modified requests and reused nonces
are rejected. Nonce state must be shared by every worker; multiple hosts require
a shared store. This is time-limited request proof, not a session-token system.
Keep server and device clocks synchronized. Serve the API at /api/v1 without adding
an external URL prefix; any proxy rewrite must preserve the signed target exactly.

## Enrollment must be explicit

The staging backend remains compatible with existing clients until an administrator
approves an installation. Signed requests from a not-yet-approved installation are
verified and recorded as pending, but are NOT automatically trusted. In compatibility
mode their access still depends on the existing device key and activation.

1. Deploy only to isolated staging first (see panel/README_SECURITY.md). No production
   server or database has been modified by this repository change.
2. Use a trusted installation. Settings > viewer settings > diagnostics displays
   its public-key `Installation SHA-256` fingerprint after its first connection.
3. Authenticate to GET /admin/api/proofs to list pending device IDs/fingerprints.
   Independently compare the full fingerprint with that displayed on the trusted
   device, never approve merely because it arrived first. These endpoints use the
   existing admin Basic authentication over HTTPS.
4. POST /admin/api/devices/{device_id}/proof with JSON {"fingerprint":"<64 lowercase hex>"}
   and X-Panel-Request: 1. This binds that device to the approved installation key.
   Subsequent unsigned requests and other keys for that device are rejected even
   while compatibility mode is enabled. Old APKs for that device will stop working.
5. Only after every intended installation is enrolled, set DEVICE_PROOF_REQUIRED=true
   in the server environment to reject all unsigned/unapproved installations.
   Do not set this on production before migration and device testing.

Uninstalling/clearing app data can lose the installation key. No automatic key
replacement or remote reset endpoint is provided. Recovery must use an authenticated,
operator-reviewed process after proving device ownership; never auto-approve a
replacement based solely on the short device key. Preserve device-proofs.db with
consistent backups alongside the activation database. Loss of this file in
compatibility mode would remove key bindings; monitor it and use required mode
after rollout. Restore all state together, not only the customer database.

## Build outputs and remaining production dependencies

- V3 Secure 0.5.2 uses the independent package `com.segaiptv.player.v3.secure`.
  It creates a new device identity and does not update V2 or previous V3 packages.
- Hardened builds remain minified, non-debuggable and include device proofs.
  They no longer use ephemeral CI debug signing. See [SECURE_SIGNING.md](SECURE_SIGNING.md)
  for the persistent owner certificate and required Actions secrets.
- CI produces a clearly marked unsigned artifact until signing secrets are set.
  The first distributed APK is signed outside CI with the new owner key.
- Release (.v3) remains unsigned; debug (.v3.preview) is used only for testing.
  Private R8 mappings remain in CI and must not be distributed to end users.

This does not prove that an APK is original. A cloned/rebranded client can create its
own installation key and request enrollment; manual verification is essential.
Automatic rejection of rebranded APKs needs a trustworthy app/device attestation
service and server validation bound to the final package/signing identity (and a
compatible policy for TV boxes without Google services). No self-reported package
or certificate fingerprint is treated as trusted evidence. APK patching and use of
an already-compromised approved device are not prevented by these changes.

The actual production backend source/schema, persistent production signing identity
and chosen attestation service are still needed to complete automatic anti-rebrand
enforcement. Do not deploy the older V2 panel over production: trial and portal
features differ. No production deployment is part of this workflow.
