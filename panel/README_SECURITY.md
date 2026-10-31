# Panel copy for V3 staging — not deployed

Source: `youdibstar/SEGA-PLAYER-IPTV2`, revision
`981c8ffdb400b709398dda70d85b02b9d6532d88`, directory `panel/`.
Only source files were copied. No production database, credentials or customer URLs
were imported. V2 and the production panel have not been modified.

## Important compatibility boundary

The V2 source accepts device keys of 30–128 characters and has no automatic trial
or customer portal implementation. The current Android app uses six-character
keys, and the reported production service has additional activation features.
Therefore this source is not verified to match the deployed server. Do not replace
the production service or point existing clients at this staging database.

Before deployment, compare the actual running source and schema with this copy,
including trials, subscription expiry formats, customer portal and registration.
Test against an isolated, access-controlled database copy and preserve all device
IDs, MAC aliases, key hashes, activation flags, expiry dates and playlist mappings.
Take a SQLite-consistent backup (SQLite backup API or stopped service; copying only
the main database while WAL writes continue is not sufficient). Verify restoration
and rollback in staging before scheduling a production change.

## Implemented protections

- Existing SHA-256 key hashes and device IDs stay unchanged. New six-character
  alphanumeric keys and original 30–128-character keys are accepted.
- Registration cannot replace an existing key or activation; concurrent registration
  is serialized. Inactive devices still receive no playlist URLs.
- SQLite rate limits shared across processes using the same security database:
  20 combined register/config requests per device per minute; 600 API requests per
  client IP per minute; 120 admin requests per IP per minute; 30 admin credential
  checks per IP per minute. Limits return HTTP 429 with Retry-After. Fixed windows
  can permit a burst across a window boundary. An attacker who knows a device ID
  can cause a temporary rate limit; these controls do not eliminate denial of service.
- Rate-limit state is kept in a separate `security-limits.db`; the customer schema
  is not migrated. Expired entries are pruned. This is not a distributed limiter
  across different hosts or separate volumes; use an edge/shared limiter there.
- Admin mutations require `X-Panel-Request: 1`; browser Origin, when present,
  must match the serving origin. The included frontend sends the header. Existing
  external admin scripts must add it. Do not enable permissive CORS.
- Validation failures do not echo submitted input. Responses use no-store, nosniff,
  DENY framing and no-referrer headers. Public OpenAPI is disabled.
- Container access logs are disabled to avoid recording device IDs in query strings.
  Reverse-proxy logs also need credential/query redaction; never log request bodies
  or Authorization / X-Device-Key headers.

## Staging and HTTPS

Run `docker compose` from this directory with ADMIN_USERNAME and ADMIN_PASSWORD
provided through the server environment, never committed. The staging project uses
its own named volume and loopback port **18000**, separate from the original panel's
port 8000. It creates an empty test database. No automatic deployment is configured.

Serve it behind an HTTPS reverse proxy with a valid certificate. Public HTTP should
be rejected for API requests, not used to receive credentials before redirecting.
The internal loopback connection is HTTP; TLS is terminated by the proxy. Set body
size/time limits at the edge. Permit access to port 18000 only from the local host.

Set Uvicorn FORWARDED_ALLOW_IPS to only the actual trusted proxy address(es), never
`*` on an exposed service. The proxy must overwrite incoming forwarded headers and
preserve the expected Host. The app does not read X-Forwarded-For directly. Test the
observed client IP and HTTPS scheme before relying on per-IP limits behind Docker.
Also apply edge rate limiting. Do not expose the container port directly.

This is incremental protection, not proof against a copied valid device key or a
modified APK. Strong per-device credentials, session tokens and rotation require a
coordinated protocol migration with the real production backend. No shared secret
has been embedded in the Android APK and no device credentials have been rotated.

## Verification

Python 3.12:

```sh
python -m pip install -r requirements-dev.txt
python -m unittest discover -s tests -v
```

The suite covers the original lifecycle, long and short keys, preserved legacy
activation, failed takeover, sanitized validation errors, rate limits, forged
forwarded headers, CSRF, concurrent registration and atomic shared limiter state.
GitHub Actions also builds the Docker image; it does not deploy anything.
The subsequent 0.5.0 Android proof protocol and rollout are documented in ../SECURITY_ROLLOUT.md.
The protections above remain compatible before explicit per-device proof enrollment.

# Installation approval in the panel

The Arabic admin panel now includes **اعتماد التثبيتات**. It displays pending and
approved installation fingerprints and the effective `DEVICE_PROOF_REQUIRED` mode.
Open **مطابقة واعتماد**, enter the full Installation SHA-256 fingerprint from the
trusted app's diagnostics screen, and confirm that you verified the app source and
device identity. Do not copy the fingerprint from the server table as a substitute
for checking the actual device. Approval does not activate a subscription.

The backend rejects missing/changed pending fingerprints, unknown devices, and
replacement of an approved key. An approved device requires its signed requests
even in compatibility mode. Existing unapproved clients remain compatible unless
the operator explicitly enables `DEVICE_PROOF_REQUIRED=true`. The UI does not
change that environment setting or reset installation keys.

This is manual installation enrollment, **not automatic anti-rebrand attestation**.
A modified APK can generate its own key. Trustworthy remote verification of the
app identity remains a separate integration, dependent on target-device support.
Deploy and test against a staging copy before changing production enforcement.

