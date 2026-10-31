# Direct APK distribution

SEGA V3 Secure is distributed directly as an APK. Google Play services and Play
Integrity are not required. Phones and TV boxes without Google services use the
same installation-key protocol as other supported Android devices.

The hardened variant checks its current signing certificate, package name and
non-debuggable flag before opening the player. It accepts the owner certificate
documented in SECURE_SIGNING.md. Release/preview variants are not covered by this
owner check. An unsigned hardened artifact must still be signed with the existing
owner key before installation; do not generate a replacement key for updates.

This is a local tamper deterrent, not remote attestation. An attacker able to patch
the APK can remove it. The server cannot infer original-app identity from the
package name or certificate fingerprint reported by a client. Manual installation
approval requires checking a known owner-signed copy on the actual device.

Use the panel's installation approval screen to bind the device to its key.
Approved installations must sign every request. Do not enable the global
DEVICE_PROOF_REQUIRED setting before legitimate devices have been enrolled and
tested. This change neither deploys the panel nor changes that setting.

Explicit HTTP 401/403 responses from the panel now stop playback, clear in-memory
catalogues and activation state, and return to the activation screen. Temporary
network/server failures retain the previous behavior. Cached files on disk are
not an authorization mechanism and are not erased by this change.

Remaining validation: sign the built hardened APK with the existing owner key;
test it on both an Android phone and a TV box, including activation, remote-control
navigation and denial during playback. Test a re-signed copy to confirm the local
check rejects it. These tests do not establish resistance to a patched-out check.
