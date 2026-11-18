# Authentication, registration, and transport security

Here’s how authentication works in version **1.0.8** of the client app and Home Assistant integration. The app signs in to HA, proves that it holds its installation’s private key, and subscribes to commands over HTTPS/WSS. It repeats those steps every time it reconnects.

## Trust boundary and identity

The app signs in to `/api/websocket` with HA’s normal access-token authentication. After `auth_ok`, it proves that it holds a P-256 ECDSA private key created for this installation. Android Keystore creates and holds the key, with signing permission and SHA-256 support. The private key never leaves the Keystore. Hardware protection depends on the device; StrongBox isn’t required.

The device ID is the SHA-256 hash of the public key’s canonical DER SubjectPublicKeyInfo bytes, written as lowercase hex. HA saves that fingerprint along with the public key, friendly name, and HA user ID. Knowing either ID isn’t enough to control the device: the app must also prove it holds the private key. Another installation signed in as the same user can register its own key, but it can’t report state, rename, or subscribe as this installation. Registering the same public key under another HA account doesn’t transfer ownership.

On first registration, HA accepts a new installation key once the signed-in app proves it holds the matching private key. There’s no separate pairing code or approval step. An HA token can therefore register a new installation, but can’t take over an existing one. This design trusts HA administrators and anyone who can write to HA configuration or storage.

## Home Assistant sign-in

The user configures an HTTPS Home Assistant origin and chooses OAuth or a long-lived access token in Android Settings. Both methods provide a bearer token to sign in to the HA WebSocket. The app must still prove it holds the installation key before it can act as a device.

For OAuth, the app checks same-origin `/.well-known/oauth-authorization-server` discovery for S256 PKCE support, then opens `/auth/authorize` in a browser Custom Tab. The request carries random state, an S256 code challenge, `haandroidmediaplayer://oauth/callback` as the redirect URI, and `<HA_ORIGIN>/api/hamediaconnect/oauth-client` as the client ID. The integration serves that public client-identification page with a fixed redirect declaration and no user data. It must be reachable by HA as well as the app.

The app validates the callback against an encrypted, single-use pending login and exchanges the code and PKCE verifier at `/auth/token` off the UI thread, checking discovery again before exchange. It validates and encrypts the returned credentials, binds them to the configured origin, and starts the foreground connection service. With a long-lived access token, **Save & connect** encrypts the entered token with the same origin binding. The app does not refresh long-lived tokens.

Home Assistant 2026.10.0 or newer is required. Unsupported or unknown Core versions fail integration setup and raise a Repairs error.

## Device registration and command subscription

Before reading credentials, `ConnectionService` validates the configured HTTPS origin. It checks that the credentials belong to that origin, refreshes OAuth credentials when needed, loads or creates the installation key, and opens the origin’s `/api/websocket` endpoint over WSS. Here, “origin” means the scheme, host, and port.

1. HA sends `auth_required`. The Android client app sends its native HA `auth` message:

   ```json
   {"type":"auth","access_token":"REDACTED"}
   ```

   No device protocol messages are accepted before HA authentication.
2. After `auth_ok`, the Android client app sends:

   ```json
   {"id":1,"type":"hamediaconnect/challenge","public_key":"BASE64_DER_P256_SPKI","name":"Kitchen Tablet"}
   ```

3. HA checks the P-256 public key, request rate, available connection slots, owner, and whether the key has been revoked. It also checks the device-count limits before saving a new registration. HA creates a random 256-bit nonce for this connection and user, then returns:

   ```json
   {"id":1,"type":"result","success":true,"result":{"device_id":"64_HEX_FINGERPRINT","payload":"HAMediaConnect/v1\n43_CHARACTER_BASE64URL_NONCE\nHA_USER_ID\n64_HEX_FINGERPRINT","expires_in":30}}
   ```

4. Android checks the payload's protocol prefix, structure, nonce length, and its own fingerprint. It signs the exact UTF-8 `payload` bytes using `SHA256withECDSA`. The signature is ASN.1 DER encoded and then standard Base64 encoded:

   ```json
   {"id":2,"type":"hamediaconnect/register","signature":"BASE64_DER_ECDSA_SIGNATURE"}
   ```

5. HA marks the challenge as used **before** checking the signature, so even a failed attempt can’t reuse it. It checks the expiry, connection, user, and signature. For a new device, HA saves the registration before reporting success. For an existing device, it checks the saved owner and key and updates the friendly name if needed. If saving fails, registration fails too.
6. The successful response contains the verified `device_id` and advertised features:

   ```json
   {"id":2,"type":"result","success":true,"result":{"device_id":"64_HEX_FINGERPRINT","features":["media_transfer_v1","playback_report_v1"]}}
   ```

   Android checks the fingerprint and opts in to supported advertised features when subscribing:

   ```json
   {"id":3,"type":"hamediaconnect/subscribe_commands","device_id":"64_HEX_FINGERPRINT","features":["media_transfer_v1","playback_report_v1"]}
   ```

   The app reports **Connected** only after HA acknowledges the subscription. Only a connection that proved this identity can subscribe, send `hamediaconnect/state`, or access device-bound transfers and playback reports. Only one live command subscriber per device is allowed. Native HA disconnect/unsubscribe callbacks remove subscriptions; a separate cleanup callback also removes the authenticated connection state. Incomplete handshakes are removed after 30 seconds, even if the socket stays open.

## Reconnect and failure recovery

On reconnect, the app signs in to HA again, signs a fresh challenge with the same installation key, and subscribes to commands. HA doesn’t save challenges or authenticated sessions across restarts. A captured signature won’t work on another connection or for another challenge. HA restores saved device entities after restart, but the device needs a live subscription to receive commands.

Before opening a socket, the service refreshes OAuth credentials if a refresh token is present and the access token is missing or expires within 60 seconds. Refresh uses `/auth/token` at the HTTPS origin saved with the credentials. Before saving the response, the service checks that the connection attempt and credential version are still current, and that the origin, sign-in method, and refresh token haven’t changed. A stopped or replaced attempt can’t overwrite newer credentials. Token endpoint authorization errors (HTTP 400, 401, or 403) and redirects stop retries; other refresh failures back off. An HA WebSocket authentication rejection also stops retries and requires the user to check Settings.

Recoverable disconnections, DNS/TLS/transport failures, handshake timeouts, missing state acknowledgments, transient refresh failures, subscription conflicts, and temporary server/storage failures keep retrying with randomized delays that grow after each failure (exponential equal-jitter backoff). The first delay is 2.5–5 seconds; the maximum is 150–300 seconds. Successful subscription resets the retry count. A default-network change cancels the previous connection attempt and scheduled retry. An available replacement network triggers an immediate connection and resets backoff; network loss leaves the service disconnected until a network becomes available. Callbacks from a replaced connection can’t send commands to the player or change connection status.

The service decides whether to retry from the `ConnectionFailure` kind and stage enums. It doesn’t read display text to make that decision. `device_auth_failed` during challenge or proof stops retries; the same code at subscription retries from a fresh connection and proof. `unauthorized` and `invalid_auth` stop retries. `subscription_busy` retries. Unknown nonempty server error codes retry a fresh connection; missing codes, `invalid_format`, `unknown_command`, malformed protocol, unexpected IDs, and messages arriving out of order stop retries. Expired challenges are consumed and rejected as temporary before a fresh attempt. Retry never grants authorization by itself.

For errors that need user attention—invalid settings, an unavailable signing key, rejected authentication, or protocol errors—the service saves an error category without sensitive details. That error survives service and process restarts. Network events, boot, and **Enable audio** do not clear the block. Explicit Settings connection actions such as **Save & connect** or successful OAuth sign-in clear it and start a new attempt after the user corrects the cause.

Android allows 45 seconds for the socket/authentication handshake. Each state request must receive its matching acknowledgment within 15 seconds; expiry closes the socket and triggers reconnect. Acknowledgment, shutdown, and failure cancel that request's deadline. Only one state request is outstanding, with at most one send per second; intermediate updates are dropped. OkHttp sends WebSocket pings every 25 seconds.

When **Start connection after reboot** is enabled and the configured URL and stored credentials are usable, `BootReceiver` starts the foreground connection service after boot or package replacement. The service applies the same origin, refresh, proof, and checks for errors that need user attention. On Android 17, the user must enable background audio through an app or notification action. The audio-enabled state lasts only for the current process. Automatic network reconnection alone does not enable audio; the app's audio control or connection notification's **Enable audio** action activates it without changing credentials or identity.

## Persistence, revocation, key loss

HA saves device public keys and their owners in its private `.storage/hamediaconnect.identities` file, replacing the file atomically on each write. Registrations and records of revoked keys survive HA restarts. Live connections and playback state don’t. The integration uses `cryptography`, already supplied by HA core, for P-256 verification. The isolated test environment installs it explicitly.

To revoke a device, an HA administrator sends this request over an authenticated HA WebSocket connection:

```json
{"id":20,"type":"hamediaconnect/revoke","device_id":"64_HEX_FINGERPRINT"}
```

The fingerprint is shown in Android Settings and is the device identifier in HA. HA saves the revocation before reporting success, removes the device’s command subscription and authenticated sessions, and blocks that public key from registering again. Revocation does not revoke the HA OAuth/access token; revoke that separately in the HA user profile if compromised.

**Deleting** a device from the Home Assistant device page (`async_remove_config_entry_device`) is separate from revocation. Only HA administrators can do it, because the HA device-registry API requires an administrator.

- If the device has a session that has completed device proof or a live command subscription, the integration refuses the delete, so the device cannot re-register at once.
- For a disconnected, unrevoked device, the integration first saves the removal of the device’s registered owner and key, then drops pending transfers. The device and entity are removed only after that succeeds. A storage failure keeps both.
- The same key can enroll again later. It must prove possession with a fresh challenge, and it receives a new owner binding.
- HA keeps the record that blocks a revoked key. Deleting a revoked device removes only its HA device and entity, and the key stays blocked.
- Revoked identities no longer create entities at HA startup.

To re-register, revoke the old fingerprint first, then use **Reset device identity** in Android Settings and confirm. The new key creates a new HA player/entity identity. Entities associated with the other key are not transferred. A missing or invalidated installation key blocks device authentication and requires explicit reset. **Forget Home Assistant credentials** separately disconnects and clears encrypted credentials, pending OAuth, and the encryption key so sign-in can recover from an invalidated credential key without resetting the device identity.

Records of revoked keys count toward the device limits. HA never drops them automatically to make room. Keep backups secure, and remember that restoring an older HA backup can undo revocations made after that backup. Android excludes preferences from backup/device transfer and disables application backup; Keystore private keys are not exportable or transferred.

## Transport limits

| Resource | Limit |
| --- | --- |
| Integration request / Android received or sent JSON | 16 KiB UTF-8 (except chunk results below) |
| Android received chunk result (only for an outstanding chunk request) | 132 KiB raw frame; decoded `data` ≤ 65536 Base64 characters (48 KiB audio) |
| In-band TTS transfer | 4 MiB each; one per device; 8 live and 16 MiB total in HA; 120 s HA expiry; 90 s Android deadline |
| Chunk / report requests | 600 / 240 per user per minute; 4 outstanding chunk requests and 16 auxiliary requests per Android connection |
| Android JSON nesting | 8 levels |
| Public-key Base64 / signature Base64 | 256 / 128 characters |
| Device fingerprint / name | exactly 64 lowercase hex / 128 characters |
| State title / content URL / command content type | 512 / 2048 / 128 characters |
| HA identities | 512 total, 16 per HA user, including revoked keys |
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

The integration rejects extra fields and invalid state values, including NaN and infinity. Android rejects binary messages, invalid JSON, unexpected IDs/states, extra command fields, and incorrectly typed or oversized command values. Exceptions and status messages do not include raw messages, URLs, tokens, authorization codes, signatures, or server-provided error bodies. There is no application command queue; HA’s WebSocket send queue has its own limit and disconnects clients that can’t keep up.

The 16 KiB integration limit applies after HA's native WebSocket decoding; it does not change HA's shared WebSocket endpoint or global frame limits. OkHttp also assembles received WebSocket messages before invoking the Android listener. These checks limit the messages the app and integration accept, but the transport may already have allocated memory to assemble them. HA's own debug/error logging is outside the integration's control; avoid verbose WebSocket logging with real credentials or signed media URLs.

## MediaSession and internal commands

`onGetSession` and the Media3 connection callback reject controllers unless the verified Binder UID is our UID or Media3 marks the caller trusted. No package-name allowlist is used. Media3/platform trust covers system media controls, privileged media-control apps, and notification listeners explicitly enabled by the user. Such listeners are intentionally trusted to the same limited playback controls; installing an arbitrary app or claiming a system package name doesn’t make it trusted.

Accepted controllers receive play/pause, stop, seek within the current item, and read-only metadata/timeline/volume commands. They receive no session/custom commands, no playlist replacement, and no permission to change system volume. Home Assistant commands carry a separate random 256-bit authorization value on explicit internal playback-service intents. That value exists only in the app process, so HA’s playback and volume commands don’t depend on external controller permissions. Notification buttons carry immutable PendingIntents restricted to their fixed actions. Raw service intents and raw media-button/custom intents cannot bypass authorization through Media3's unverified fallback path.

Live lock-screen and Bluetooth controls use the authorized platform session; notification play/pause/stop uses the immutable PendingIntents. Android Auto can connect when Android/Media3 recognizes its verified controller as trusted. Browse, search, and catalog operations are not exposed. Unverified cold-start media-button resumption is deliberately not authorized. Validate OEM Bluetooth, lock-screen controls, notification dismissal, and Android Auto on physical devices; Robolectric can’t tell us whether those controls work on a particular device.

References: [Media3 controller identity and trust](https://developer.android.com/reference/androidx/media3/session/MediaSession.ControllerInfo), [Media3 connection authorization](https://developer.android.com/reference/androidx/media3/session/MediaSession.Callback), and [HA WebSocket subscriptions](https://contributors.home-assistant.io/core/internals/websocket-api/).

The connection notification's **Enable audio** action uses an immutable, explicit
foreground-service PendingIntent targeting the non-exported `ConnectionService`.
It only activates audio and re-promotes the existing service; it does not change
credentials, device identity, or clear a permanent authentication failure. It
cannot choose media, a URL, or an arbitrary service action. The audio-enabled state lives
only in memory and clears when the connection service is recreated.

## OAuth and credential storage

The app generates state and the PKCE verifier with `SecureRandom` (256-bit state, 384-bit verifier); PKCE uses S256. The app saves the pending login as one encrypted record containing the origin, state, verifier, and start time. Callbacks must exactly match `haandroidmediaplayer://oauth/callback`, including raw authority and path, with no userinfo, port, fragment, duplicate parameters, or unsupported parameters. State comparisons use constant-time byte comparison. A pending login expires after ten minutes. The app rejects it if the clock has moved backward and uses it only once when a matching response arrives, including a denial. An intent with the wrong state doesn’t cancel a legitimate browser login.

A transaction version prevents an earlier token exchange from overwriting a newer login or restoring credentials the user has removed, even if the activity has been recreated. All credential writes, account replacements, OAuth attempts, refresh saves, and key resets share a lock across the process. Refresh records the credential version before the network request, then checks that version, origin, sign-in method, and refresh token again under the same lock before saving. If the user replaces or forgets credentials during refresh, the app discards the response. The shared lock keeps another store instance from changing credentials between the check and the save.

The callback uses a custom URI scheme because verified App Links need a domain and a hosted association file that we control. Users can host HA at any origin. State and PKCE protect the flow, but another installed app claiming the scheme can intercept or deny callback delivery. HA must enforce PKCE so an app that intercepts the code can’t exchange it without the verifier.

Before browser authorization and again before code exchange, Android fetches same-origin HTTPS OAuth discovery and requires S256 support (Home Assistant 2026.10+). If discovery is missing, redirected, invalid, too large, or doesn’t advertise S256, the app stops sign-in. This check runs off the UI thread and uses the same response-size limits and timeouts as token requests. No authorization code is requested from a server that does not advertise support.

The HA integration checks Core against 2026.10.0 before registering endpoints or loading entities, logs an error, raises a native Repairs error, and rejects setup on unsupported versions. Config flows abort with upgrade instructions. Successful startup clears the version repair. The integration also refuses to start with prereleases below 2026.10.0 or version strings it can’t recognize.

OAuth authorization, exchange, and refresh require valid HTTPS origins. Token requests disable redirects (including HTTPS-to-HTTP redirects), use a 15-second connect timeout and a 25-second call timeout, reject responses over 16 KiB, and check token type, size, and expiry. Token endpoint authorization errors stop retries. The app records which HA origin each credential belongs to. Editing the URL can’t silently send those credentials to a different server. Callback codes, tokens, and exception bodies never enter logs or status text. The password field is excluded from saved view state and autofill.

Tokens, temporary OAuth state, and credential origin are AES-256-GCM encrypted using Android Keystore. Each write uses a random IV and authenticates the field name as associated data, so encrypted data copied from one field can’t be used as another field. If decryption or key access fails, the app returns no usable credential. It never falls back to saving plaintext. If the encryption key is missing while encrypted credentials still exist, it won’t create a replacement automatically. Use **Forget Home Assistant credentials** to clear that data and allow a new key and sign-in.

## HTTPS control, HTTP media, and residual risks

Home Assistant control requires HTTPS/WSS with normal certificate validation, for both OAuth and long-lived tokens. HTTP control URLs are rejected before credentials are read or a WebSocket is opened. Boot and reconnect use the same validation. Credentials stay tied to their configured origin. If you change it, sign in explicitly for the new origin. There is no downgrade or certificate bypass.

Control URLs reject userinfo, unsupported schemes, invalid ports, whitespace/backslashes, query/fragment, and non-root paths. The supported configuration is the HA origin, including a port; reverse-proxy subpaths are not supported. Control connections do not follow redirects.

HTTP audio URLs, including HA TTS, remain supported independently. Media URLs accept only HTTP(S) without embedded credentials or malformed authorities; relative media paths resolve against the configured HTTPS HA origin. App cleartext capability remains enabled for media. The separate media client does not attach HA bearer tokens and follows at most five redirects, all with the same scheme, host, and port. HTTP media content and any URL access signatures remain visible to network observers; prefer HTTPS media when possible.

Device signatures do not encrypt traffic or replace TLS. Rooted/compromised Android, compromised HA, malicious trusted notification listeners, and a stolen unlocked device remain outside this protection. The app uses platform certificate validation without certificate pinning or a self-signed-certificate bypass.


### Runtime dependency advisories

Guava's Android runtime version is constrained to at least 33.7.2-android for GHSA-xxph-c9ww-hj94. The dependency audit checks every dependency in the release runtime against OSV when dependencies change and each night. It fails if OSV reports an advisory, a response is incomplete, a lookup fails, or a dependency can’t be resolved. It doesn’t exclude packages or filter by severity. When it finds an advisory, we still need to check whether the app actually uses the affected code.

### Media delivery and provenance

HA-owned media prefers HA's supported Cloud HTTPS endpoint when available, then configured external HTTPS;
raw paths and signatures survive endpoint selection. Public streams keep their
origins. Media uses a separate unauthenticated Media3 OkHttp client with TLS
validation, at most five redirects within the identical scheme/host/port, timeouts for connecting, reading, and buffering, and fixed
error messages that leave out URLs and credentials. It never receives HA bearer tokens. Android 17 local access
requires the Local network runtime permission. HTTP audio is supported independently of HTTPS control.

HA ownership is recorded before relative resources are made absolute. Only those
resources and origins verified by `is_hass_url` select HA's external endpoint;
Android never guesses ownership from a third-party URL's path. Path signatures,
queries and opaque TTS tokens survive the actual request but are redacted from UI
and logs. The separate media client has no bearer credentials or cookie store.
Redirects with another origin, a downgrade, userinfo, fragments, malformed locations,
or excessive hops are refused. Media delivery uses normal TLS validation.


### In-band TTS transfer

HA-generated TTS audio can be pulled over the authenticated device WebSocket instead
of from a media URL. The audio comes only
from HA's in-process TTS manager or the integration's fixed, bundled sonar-ping
asset; the device can't name a URL, path or foreign
transfer. Every `media_chunk`, `media_release` and `playback_report` request goes
through the same `owned()` proof/owner/revocation check as `state`. Other devices signed in as
the same user, and connections that haven’t completed device proof, can’t read a transfer. Transfer errors
(`media_unavailable`) and rejected reports affect only that transfer; they don’t change
connection authorization. Feature negotiation is two-step (server advertises in the
`register` result, client opts in on `subscribe_commands`), so neither side ever sends
fields its peer would reject as a protocol error. Android validates offsets, lengths
and the SHA-256 digest before playback. Temporary audio lives only under
`cacheDir/transfers/` with unpredictable names and is deleted after use. Playback
accepts only a verified file in that directory. Revocation drops the device's
pending transfers. Transfers use the authenticated WebSocket without a separate HTTP endpoint or credential. Playback
reports carry fixed enums, never URLs or exception text.

The per-device sonar button uses the same device-bound transfer store and command
rate limit. The button is unavailable if the client is disconnected, revoked, or hasn’t agreed to use audio transfers.
HA checks those conditions again after reading the file on a worker thread, and frees
an unsent transfer if command delivery is rejected or the connection fails. Only the fixed package asset
is read; the button accepts no URL, filename, or audio data from service callers.
Its announcement does not override volume, mute, DND, or controller authorization.

## Validation

For security test commands, coverage gates, and release checklists, see the [Maintainer handbook](MAINTAINER.md).
