# Phase 2 authentication and transport design

Version 1.0.0 is a breaking device protocol change. Update the client app and custom integration together and sign in again. There is no UUID-only enrollment or plaintext credential migration. Normal HA media-player actions remain unchanged.

## Trust boundary and identity

Home Assistant still authenticates `/api/websocket` using its native OAuth/access-token exchange. HAMediaConnect does not replace that authentication. After `auth_ok`, the app proves possession of an installation-specific P-256 ECDSA private key. Android creates the key in Android Keystore with signing permission and SHA-256; the private key is never exported. Hardware backing depends on the device; StrongBox is not required.

The device ID is the lowercase hexadecimal SHA-256 digest of canonical DER SubjectPublicKeyInfo public-key bytes. Neither a typed device ID nor the HA user ID authorizes a device. The server durably binds the fingerprint, public key, friendly name, and HA user ID. Another installation authenticated as the same HA user can enroll its own key but cannot report state, rename, or subscribe as the existing installation without its private key. Ownership cannot be transferred by registering the same public key under another HA account.

Initial enrollment is trust-on-first-proof within the authenticated HA account, not an out-of-band pairing ceremony. An HA token permits creating a *new* installation identity. It does not grant an existing installation identity. HA administrators and anyone with write access to HA configuration/storage remain trusted.

## Registration, authentication, reconnect

1. HA sends `auth_required`. the Android client app sends its native HA `auth` message. No device protocol messages are accepted before HA authentication.
2. After `auth_ok`, the Android client app sends:

   ```json
   {"id":1,"type":"hamediaconnect/challenge","public_key":"BASE64_DER_P256_SPKI","name":"Kitchen Tablet"}
   ```

3. HA validates the key, quota, owner, and revocation status. It creates one unpredictable 256-bit nonce, bound to that exact HA connection and user, and returns:

   ```json
   {"id":1,"type":"result","success":true,"result":{"device_id":"64_HEX_FINGERPRINT","payload":"HAMediaConnect/v1\n43_CHARACTER_BASE64URL_NONCE\nHA_USER_ID\n64_HEX_FINGERPRINT","expires_in":30}}
   ```

4. Android checks the payload's protocol prefix, structure, nonce length, and its own fingerprint. It signs the exact UTF-8 `payload` bytes using `SHA256withECDSA`. The signature is ASN.1 DER encoded and then standard Base64 encoded:

   ```json
   {"id":2,"type":"hamediaconnect/register","signature":"BASE64_DER_ECDSA_SIGNATURE"}
   ```

5. HA consumes the challenge **before** verification, including failed verification. It checks expiry, connection/user binding, and signature. For initial enrollment it persists the binding before acknowledging success. Later enrollment proofs authenticate the same binding and may update its friendly name. Storage failures fail closed.
6. The successful response contains `device_id`. Android then sends:

   ```json
   {"id":3,"type":"hamediaconnect/subscribe_commands","device_id":"64_HEX_FINGERPRINT"}
   ```

   Only a connection that proved this identity can subscribe or send `hamediaconnect/state`. Only one live command subscriber per device is allowed. Native HA disconnect/unsubscribe callbacks remove subscriptions; a separate cleanup callback also removes the authenticated connection state. Incomplete handshakes are removed after 30 seconds, even if the socket stays open.
7. Reconnect repeats native HA authentication and a fresh device challenge. Challenges and authenticated sessions are never persisted. Captured signatures cannot authenticate another connection or answer a later challenge.

Android permits only the expected handshake sequence and response IDs. Temporary subscription conflicts, rate/session exhaustion, and storage failures retry without granting authorization or adding a second subscriber. HA authentication rejection, device proof rejection, malformed protocol, and invalidated signing keys stop the connection until the user intervenes. A state request must receive its matching acknowledgment within 15 seconds. Expiry closes the socket and triggers reconnect; acknowledgments, explicit shutdown, and failures cancel the deadline. Each later state request gets a new deadline, so a stale timer cannot terminate it. Transient disconnections, missing state acknowledgments, and handshake timeouts use exponential equal-jitter backoff, starting at 2.5–5 seconds and capped at 150–300 seconds. Token refresh rejection requires sign-in; transient refresh failures back off.

## Persistence, revocation, key loss

HA stores public identities in its private, atomically written `.storage/hamediaconnect.identities` store. Owner/key bindings and revoked-key tombstones survive HA restarts; active transport and playback state do not. The integration uses `cryptography`, already supplied by HA core, for P-256 verification. The isolated test environment installs it explicitly.

An HA administrator deliberately revokes a device over an authenticated HA WebSocket connection:

```json
{"id":20,"type":"hamediaconnect/revoke","device_id":"64_HEX_FINGERPRINT"}
```

The fingerprint is shown in Android Settings and is the device identifier in HA. Revocation is persisted before success, clears live command delivery and authentication sessions, and prevents that public key from re-enrolling. Revocation does not revoke the HA OAuth/access token; revoke that separately in the HA user profile if compromised.

To re-register, revoke the old fingerprint first, then use **Reset device identity** in Android Settings and confirm. The new key creates a new HA player/entity identity. Old entities are deliberately not transferred. An absent previously-created or invalidated Keystore key never triggers fallback to UUID-only or token-only device authorization. Reset is explicit. **Forget Home Assistant credentials** separately disconnects and clears encrypted credentials, pending OAuth, and the encryption key so sign-in can recover from an invalidated credential key without resetting the device identity.

Revoked-key tombstones count against the finite identity quotas. There is no silent tombstone eviction or automatic trust reset. HA backup/configuration integrity matters: restoring an older HA backup can restore older revocation state. Android excludes preferences from backup/device transfer and disables application backup; Keystore private keys are not exportable or transferred.

## Transport limits

| Resource | Limit |
| --- | --- |
| Integration request / Android received or sent JSON | 16 KiB UTF-8 |
| Android JSON nesting | 8 levels |
| Public-key Base64 / signature Base64 | 256 / 128 characters |
| Device fingerprint / name | exactly 64 lowercase hex / 128 characters |
| State title / content URL / command content type | 512 / 2048 / 128 characters |
| HA identities | 512 total, 16 per HA user, including tombstones |
| Incomplete and authenticated device connections | 256 total |
| Challenge / proof / subscription attempts | 10 each per user per minute |
| Integration messages | 2048 per user per minute |
| Rate-limit buckets | 1024 live buckets, expiring after 60 seconds |
| Outbound commands per device / Android inbound commands | 120 per minute |
| Pending challenge | one per connection, expires after 30 seconds |
| Android overall connection/auth handshake | 45 seconds |
| Android state acknowledgment deadline | 15 seconds; reconnect on expiry |
| Android pending state updates | one, at most one update per second; intermediate updates dropped |
| Android WebSocket send buffer | rejects further application sends above 16 KiB |

The integration rejects extra fields and malformed or nonfinite state values. Android rejects binary messages, invalid JSON, unexpected IDs/states, extra command fields, and incorrectly typed or oversized command values. Exceptions and status messages do not include raw messages, URLs, tokens, authorization codes, signatures, or server-provided error bodies. There is no application command queue; HA's native bounded WebSocket send queue handles slow consumers and disconnects them.

The 16 KiB integration limit applies after HA's native WebSocket decoding; it does not change HA's shared WebSocket endpoint or global frame limits. OkHttp also assembles received WebSocket messages before invoking the Android listener. Thus these checks bound accepted application messages, not all transient transport allocations. Stronger preallocation limits require upstream transport support or a different transport implementation. HA's own debug/error logging is outside the integration's control; avoid verbose WebSocket logging with real credentials or signed media URLs.

## MediaSession and internal commands

`onGetSession` and the Media3 connection callback reject controllers unless the verified Binder UID is our UID or Media3 marks the caller trusted. No package-name allowlist is used. Media3/platform trust covers system media controls, privileged media-control apps, and notification listeners explicitly enabled by the user. Such listeners are intentionally trusted to the same limited playback controls; installing an arbitrary app or claiming a system package name does not confer trust.

Accepted controllers receive play/pause, stop, seek within the current item, and read-only metadata/timeline/volume commands. They receive no session/custom commands, no playlist replacement, and no system-volume mutation. Home Assistant uses a distinct process-local random 256-bit capability on explicit internal playback-service intents, so its play-media/volume actions do not depend on external controller permissions. Notification buttons carry immutable PendingIntents restricted to their fixed actions. Raw service intents and raw media-button/custom intents cannot bypass authorization through Media3's unverified fallback path.

Live lock-screen and Bluetooth controls use the authorized platform session; notification play/pause/stop uses the immutable PendingIntents. Android Auto can connect when Android/Media3 recognizes its verified controller as trusted. There is no new browse/search/catalog support. Unverified cold-start media-button resumption is deliberately not authorized. Validate OEM Bluetooth, lock-screen controls, notification dismissal, and Android Auto on physical devices; Robolectric does not establish those device-specific compatibility claims.

References: [Media3 controller identity and trust](https://developer.android.com/reference/androidx/media3/session/MediaSession.ControllerInfo), [Media3 connection authorization](https://developer.android.com/reference/androidx/media3/session/MediaSession.Callback), and [HA WebSocket subscriptions](https://contributors.home-assistant.io/core/internals/websocket-api/).

## OAuth and credential storage

State and PKCE verifier use `SecureRandom` (256-bit state, 384-bit verifier); PKCE uses S256. A pending login is one encrypted transaction containing origin, state, verifier, and issuance time. Callbacks must exactly match `haandroidmediaplayer://oauth/callback`, including raw authority and path, with no userinfo, port, fragment, duplicate parameters, or unsupported parameters. State comparisons use constant-time byte comparison. A pending transaction expires after ten minutes, rejects clock rollback, and is consumed once on a matching response, including denial. Wrong-state intents do not cancel a legitimate browser login. A transaction epoch prevents an older token exchange from overwriting a later login or explicit credential removal, including across activity instances.

The app keeps the custom URI scheme because verified App Links require control of a domain and hosted association metadata; users' HA origins are arbitrary. State and PKCE protect the flow, but another installed app claiming the scheme can intercept or deny callback delivery. No externally hosted domain/service is introduced. Server PKCE enforcement is a prerequisite for protection against code interception.

OAuth authorization, exchange, and refresh require valid HTTPS origins. Token requests disable redirects (including HTTPS-to-HTTP redirects), have connect/call timeouts, bound response bodies, and validate token type, sizes, and expiry. Token endpoint authorization errors stop retries. Stored credentials are bound to the configured HA origin, preventing an edited URL from silently receiving another server's credentials. Callback codes, tokens, and exception bodies never enter logs or status text. The password field is excluded from saved view state and autofill.

Tokens, temporary OAuth state, and credential origin are AES-256-GCM encrypted using Android Keystore. Each write uses a random IV and authenticates the field name as associated data, preventing ciphertext substitution between fields. Decryption or key errors return no usable credential; writes never fall back to plaintext. Missing credential keys are not regenerated over existing ciphertext. Explicit forgetting permits a new encryption key and fresh sign-in.

## HTTP product behavior and residual risks

**Allow insecure HTTP connections** defaults to disabled, is changed only through explicit settings, and displays an interception warning when enabled. HTTPS/WSS remains available either way; HTTP/WS control requires the saved opt-in. Control URLs reject userinfo, unsupported schemes, invalid ports, whitespace/backslashes, query/fragment, and non-root paths. The supported configuration is the HA origin, including a port; reverse-proxy subpaths are not supported. Control connections do not follow redirects.

HTTP audio URLs, including HA TTS, remain enabled independently of this switch. Media URLs accept only HTTP(S) without embedded credentials or malformed authorities; relative media paths resolve against the configured HA origin. App cleartext capability remains enabled for this purpose. Media3's default HTTP data source does not enable cross-protocol redirects.

HTTP is not made secure by device signatures: an on-path attacker can steal HA bearer tokens, observe/alter traffic, or actively relay a live challenge. The signatures prevent offline replay and unauthorized device claims without possession of the key; TLS is required against active network attackers. Rooted/compromised Android, compromised HA, malicious trusted notification listeners, and a stolen unlocked device remain outside this protection. No certificate pinning, self-signed-certificate bypass, external service, or plaintext migration is introduced.

## Validation

For security test commands, coverage gates, and release checklists, see the [Maintainer handbook](MAINTAINER.md).

### Media delivery diagnostics update

HA-owned media prefers HA's supported Cloud HTTPS endpoint when available, then configured external HTTPS;
raw paths and signatures survive endpoint selection. Public streams keep their
origins. Media uses a separate unauthenticated Media3 OkHttp client with TLS
validation, no redirects, bounded connect/read and buffering deadlines, and fixed
sanitized diagnostics. It never receives HA bearer tokens. Android 17 local access
requires the Local network runtime permission. HTTP audio retains its existing
independent capability. See [TTS investigation](diagnostics/TTS_PLAYBACK.md) for
security tradeoffs, compatible diagnostics proposal, and unverified device cases.
