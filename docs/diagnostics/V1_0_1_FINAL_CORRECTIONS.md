# v1.0.1 device corrections

## Findings and changes

### Reconnection

`ConnectionService.networkChanged` now observes Android's default network, invalidates
old attempts before closing their sockets, cancels scheduled retries, and reloads
configuration and encrypted credentials through `connect`. Manual Settings and
automatic recovery use that same connection path. A bounded OAuth refresh cannot
publish credentials or open a socket after its attempt has been invalidated.
Equal-jitter exponential backoff remains 2.5–5 seconds initially, capped at
150–300 seconds. A changed default network cancels the pending delay and attempts
connection immediately; duplicate notifications for the same network do nothing. Only the saved, credential-bound
HA origin is used; no credentials are transferred to a guessed LAN/remote origin.
Configure a reachable HTTPS origin (normally Cloud) for use on both networks.

A concrete failure path exists in `websocket_api.ws_subscribe`: an old connection
can still own the command subscription after the phone changes networks. Previously
this produced `device_auth_failed`; `HaWebSocketClient.Listener.onMessage` treated
it as a permanent identity rejection, and display-string retry rules stopped
recovery. Those string-based control decisions have been removed. It now returns `subscription_busy`, which retries without allowing a
second command subscriber. Temporary rate/session limits and storage failures also
retry. `ConnectionState.kt` now carries typed failure kind, stage, retryability,
and phase from the socket through the service to the UI. Transport/DNS/TLS errors,
timeouts, unavailable refresh/storage, expired challenges, and unknown valid server
error codes close the socket and retry indefinitely with fresh proof. Malformed
protocol, confirmed challenge/proof rejection, revoked or wrong-owner identity,
unavailable key, configuration errors, and rejected OAuth/HA credentials require
intervention. An expired challenge is consumed before returning a retryable error;
a replay still fails. An older integration's coarse `device_auth_failed` at the
subscription stage retries a complete new handshake because that response can
mean a stale subscriber. If the identity really was revoked, its next challenge
fails permanently; no failed response ever authorizes a session.

Network changes and service/process restarts cannot bypass a confirmed permanent
rejection; the sanitized failure kind is persisted until an explicit connection action. After correcting
credentials or the registration, Save and Connect (or completed OAuth sign-in)
explicitly restarts the connection through `ACTION_RECONNECT`; ordinary network
callbacks cannot clear the block. Refresh results also check that origin, auth
method and refresh token still match before writing, preventing an old refresh
from overwriting corrected credentials. Credentials and the device key are preserved.
Simulated overlapping subscriptions reproduce the server rejection path; this is
source-supported evidence, not a captured Pixel network trace.

### Remote TTS

The pipeline remains `tts.speak` → HA media-source resolution → HA media-path signing
where required → HA endpoint selection → authenticated command → a separate,
unauthenticated Media3 HTTP client. `AndroidMediaPlayer.async_play_media` now passes
`prefer_cloud=True` to HA's supported `get_url`, together with external-only and
HTTPS requirements. Previously a separately configured external URL could take
priority over the working Cloud tunnel. If no Cloud URL is available, HA can select
a configured external HTTPS URL; local-only installations retain their media URL.
The choice is made for every command. Third-party media origins remain unchanged.
The latest correction records HA ownership before making a relative resource
absolute, and uses `async_process_play_media_url(..., allow_relative_url=True)` to
sign first. Previously a default LAN origin not recognized by the subsequent
`is_hass_url` check could skip external selection. A regression fixture forces
that origin mismatch and verifies a Cloud URL with the original escaped token
and query. Local-only fallback still uses HA's standard URL helper.

Android intentionally does not rewrite an arbitrary absolute LAN URL to its
credential-bound HA host based only on `/api/tts_proxy/`. The integration knows
which media belongs to HA; matching the path alone does not prove ownership.
Update both components. If diagnostics still say HTTP/Local while away, verify
that the corrected integration is loaded, Cloud remote access is active and HA
has a reachable external HTTPS URL. A private third-party stream or an installation
with no external endpoint cannot be made cellular-reachable by this app.
Raw escaped paths, repeated query parameters, existing signatures, and opaque TTS
tokens are retained for the actual request.

[HA network helper](https://github.com/home-assistant/core/blob/dev/homeassistant/helpers/network.py)
and [media URL processing](https://github.com/home-assistant/core/blob/dev/homeassistant/components/media_player/browse_media.py)
document these facilities in source. The supported
[TTS proxy](https://github.com/home-assistant/core/blob/dev/homeassistant/components/tts/__init__.py)
serves an existing stream selected by its opaque token and returns 404 if the token
is absent; no extra Android bearer header or new public endpoint is needed.
The media client follows at most five same-origin redirects (same scheme, host,
and port). It refuses cross-origin redirects, HTTPS downgrades, credentials,
fragments, malformed locations and loops. Existing local HTTP media remains supported.
Raw Location headers and failed URLs never enter application diagnostics. URL selection does **not** establish that a
particular tunnel serves the TTS resource. No production HA credentials, generated
TTS token, or Cloud endpoint was used during this work. The Pixel's actual timeout
root cause remains unverified until a fresh remote TTS request is tested. The new
card identifies the endpoint class and sanitized failure stage to distinguish
incorrect endpoint selection from DNS, TLS, HTTP, decoding, and buffering failures.
Expired resources (404/410) require generating TTS again; credentials are never
attached to a media request to work around a failed resource.

### Playback and Settings

`PlayerStateBus.publish` retains the current snapshot and independently updates the
connection service and foreground Activity. `PlaybackService.publishState` supplies
real player state, title, position, duration, media URL, and a separate sanitized
error. Idle is connection-aware; locally buffered Playing/Paused and failures remain
visible through connection loss and successful reconnection. `MainActivity` no longer reads `playback_error` into connection status.
`PlaybackDiagnostic` renders only the scheme, Local/External/Nabu Casa class, and
an allowlisted route prefix. Arbitrary path identifiers, hostnames, credentials,
fragments, and all query parameters are omitted. URL-shaped metadata titles are
omitted. Long-press the selectable Playback text to copy its redacted summary.

A failed item stays Failed across ticker updates. Stop clears errors and metadata;
a valid replacement clears old errors. Normal completion clears the item and
returns Ready while connected, Waiting for Home Assistant connection while
connecting/reconnecting, or Unavailable while disconnected. Announcement restoration invalidates the old listener before
restoring the saved item, position, and pause/play intent. Restored music has its
own actual playback state and retains a sanitized announcement-failure note until
the next accepted play or Stop. The generation check is repeated after restoration
so the finishing announcement cannot clear restored music.

Settings has a live connection indicator, remains open during OAuth exchange and
device proof, and returns home only on the service's `Connected` result. Browser
return alone does not mark it connected. Errors remain visible in Settings and
Back remains available. Pending device-connection state survives Activity recreation.

### Launcher

The artwork already occupied nearly the full circular-mask height. Enlarging it
clipped the phone outline in the rendered regression test. The final icon uses
white source artwork on a full brand-violet background for more visual weight,
with uniform scaling and centering that preserve the complete phone/house/speaker.
Foreground and monochrome use matching geometry. Native rendering tests check
circle, rounded-square, and square masks, centering, minimum size, and preservation
of at least 99% of artwork. [Mask preview](launcher-masks-v1.0.1.png) includes normal
launcher-size renderings. OEM launcher appearance still needs Pixel verification.

## Automated validation

Results are recorded for this branch, independently of the historical PR #22
results in `TTS_PLAYBACK.md`. Coverage exclusions and 85% thresholds are unchanged.

- Python compileall and dependency installation: completed.
- Python: 64 tests passed; combined line/branch coverage 96.14%.
- Android: 71 JVM/Robolectric tests passed; application line coverage 99.54%, security
  line coverage 88.60%. Both unchanged 85% gates and debug APK assembly passed. The
  typed connection policy is explicitly included in the security report. Security
  coverage is below the aspirational 90%; it is not claimed to cover physical devices.
- Android lint: passed with zero errors and 89 remaining warnings. Required Media3
  opt-in annotations were added to the existing controller/notification/playback
  adapters; no lint baseline or suppression was added.
- Android 16 disposable emulator: all five instrumentation tests passed with a
  generated three-second MP3 served on host loopback. The production player reached
  Playing, decoded the audio, completed the announcement, and restored Ready.
  `securityDeviceCoverageReport` generated separate device coverage data. The default
  run also passed (four tests, one fixture test skipped). This is not a live HA/Cloud test.
- Hosted `validate-ha.yml`, `android-apk.yml`, `hacs.yml`, `hassfest.yml`: not run for
  the latest branch commits. Local Python and Android checks exercise the corresponding
  test/build commands. HACS/Hassfest actions require their hosted/container environment;
  neither Docker nor Podman is installed here. These remain required CI checks before
  release. No PR, merge, tag, signing change, or release is included.

Regression coverage includes service network changes and loss/recovery with the
same credential/key, immediate restoration, duplicate notifications, service restart,
credential correction and refresh failures, stale status suppression, terminal rejection, temporary server
failures, fresh challenge proof, overlapping subscriber cleanup, Cloud URL selection,
signed-path preservation, real same-origin redirect loading and negative redirect cases, HTTP errors (including expired resources), buffering
timeouts, live UI state separation, Settings success/failure navigation, retained
playback failure and Stop recovery, announcement restoration, stale playback
callbacks, redaction, and adaptive-mask rendering. Simulated network identities do
not establish modem/OEM behavior. Existing HTTP data-source tests exercise real
local fixture requests without bearer credentials.

## Pixel 10 acceptance — not yet performed

Use the paired integration and APK from this branch. Keep the current enrolled key
and credentials. Configure the same reachable Cloud HTTPS control origin on both
networks. Generate fresh TTS for each test; never copy raw signed URLs or tokens
into reports. Record app/integration versions, network, sanitized card text, and
audible outcome for each item.

- [ ] On home Wi-Fi, confirm Connected; play a public HTTPS MP3 and locally generated
  TTS. Confirm Buffering → Playing → Ready, title/time updates, audible output, and
  the displayed endpoint class. Verify media volume/mute.
- [ ] While Connected, disable Wi-Fi. Confirm Reconnecting → Connected without
  touching Settings, resetting identity, or signing in. While idle, verify the Playback
  card changes to Waiting for Home Assistant connection and then Ready. Confirm only one HA player
  and one command recipient. Repeat several times without rapid retry loops.
- [ ] On cellular, generate fresh TTS through `tts.speak`. Confirm HTTPS/Nabu Casa
  diagnostics, Playing, audible output, then Ready. A working WebSocket alone is
  insufficient. If it fails, record the fixed DNS/TLS/HTTP/decoding/timeout message
  and endpoint class; investigate the live tunnel/media route separately.
- [ ] Restore Wi-Fi and confirm automatic reconnection. Repeat with music playing.
- [ ] Temporarily remove all network access. Confirm idle says Unavailable, while
  already buffered music may continue with its actual position. Restore access; confirm recovery with
  existing enrollment, including after the access token requires refresh.
- [ ] Test pause → play → stop, lock-screen and Bluetooth controls. Confirm Stop
  clears a prior failure. Test HA restart, Android reboot, and foreground return.
- [ ] Interrupt music with TTS; verify saved position and play/pause intent are
  restored after successful, failed, and repeated announcements. Toggle DND and
  confirm existing announcement suppression/restoration.
- [ ] Use a disposable media endpoint that stalls or returns 401/403/404/410/5xx.
  Confirm a bounded failure in Playback, persistent error text, and an independent
  Connected card. Confirm a new valid request clears the error.
- [ ] Open Settings, connect with OAuth and separately a long-lived token. Confirm
  Connecting until actual device proof/subscription succeeds, then automatic home
  navigation. Test invalid credentials: remain in Settings with an actionable error.
  Confirm manual Back works during failure and Activity recreation is safe.
- [ ] Copy Playback diagnostics; confirm no opaque TTS ID, hostname, signed query,
  bearer token, embedded credential, or raw exception message appears.
- [ ] Check the installed icon at normal size, including themed icons, on the Pixel
  launcher. Compare circular and rounded-square launcher previews and in-app branding.

Release acceptance remains pending the live Cloud/Pixel checks and required hosted CI.


## Files in the latest correction pass

- Networking: `ConnectionState.kt`, `ConnectionService.kt`, `HaWebSocketClient.kt`,
  `TransportPolicy.kt`, `AppPrefs.kt`, and HA `registry.py`.
- Media: HA `media_player.py`, Android `MediaRedirectPolicy.kt` and `PlaybackService.kt`.
- UI: `MainActivity.kt`, `PlaybackDiagnostic.kt`. Lint-only opt-in annotations also
  cover `ControllerPolicy.kt` and `PlaybackNotifications.kt`.
- Android regressions: `ConnectionStateTest`, `ConnectionServiceTest`,
  `HaWebSocketClientTest`, `MediaRedirectPolicyTest`, `MediaHttpTest`,
  `PlaybackDiagnosticTest`, `StatusUiTest`. Python: `test_registry.py`,
  `test_websocket_api.py`, `test_media_player.py`.
- Validation/docs: `app/build.gradle.kts` explicitly measures the new security policy;
  `README.md`, `docs/DEVELOPERS.md`, `docs/SECURITY_PHASE_2.md`, and this report explain
  current behavior, evidence and remaining device acceptance.
