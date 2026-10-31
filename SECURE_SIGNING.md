# Independent V3 Secure installation (0.5.2)
Package: `com.segaiptv.player.v3.secure`. Version code: 20.
This is a new installation, not an update to V2 or V3 Preview. It generates its
own device identity and may require activation. Existing application data is untouched.
R8, non-debuggable mode, HTTPS and device proof remain enabled.

## Persistent signing
The owner key was generated outside GitHub. No private key or password belongs
in source control or Actions artifacts. Certificate SHA-256:
`d25ec500c54c69192ed12bd160ea87ecfbed146fb8ba728cac2b9a95397263b4`.

Keep the private signing backup securely. All future updates of this package
must use this same key. Losing it means losing the ability to update this app.

Configure repository Actions secrets:
- SEGA_SIGNING_KEYSTORE_BASE64: base64 of sega-v3-secure.p12.
- SEGA_SIGNING_STORE_PASSWORD: its password.
Alias: sega-v3-secure.

Until both secrets are configured, CI deliberately produces an UNSIGNED artifact.
It cannot be installed until signed outside CI with the owner key. No ephemeral
debug key fallback is permitted. The first distributed 0.5.2 APK is signed outside
CI. The GitHub connector cannot set repository secrets.

CI verifies the expected package/version/non-debuggable flag and, when signing,
the exact certificate fingerprint. Never upload the signing backup to artifacts.
This change does not deploy the panel or alter production subscriptions.
