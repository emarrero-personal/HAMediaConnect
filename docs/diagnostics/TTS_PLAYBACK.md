# TTS investigation

## Findings before implementation

At the start of this investigation, the reported Pixel failure had not been
reproduced here. Subsequent Android 17 emulator reproduction is recorded below.
Chrome retrieval proves
server reachability for Chrome, not successful loading or decoding by Media3.
No device logs, Android version, HTTP trace, or TTS sample were supplied.

The pipeline is `tts.speak` → HA TTS media source →
`AndroidMediaPlayer.async_play_media` → media-source resolution →
`async_process_play_media_url` → registry dispatch → authenticated command
subscription → `HaWebSocketClient` → `ConnectionService.resolveUrl` →
`PlaybackService` → ExoPlayer → PlayerStateBus → WebSocket state → HA entity.

Source inspection establishes these defects:

* `app/build.gradle.kts` targets SDK 37. The manifest and MainActivity have no
  ACCESS_LOCAL_NETWORK declaration/request. Android 17 blocks LAN sockets for
  this target without that permission. This explains public HTTPS working while
  private HTTP fails on Android 17, even when Chrome works; confirmation on the
  reported Pixel requires its OS and permission state. It does not explain a
  failure on an older OS without local-network protections enabled.
* `PlaybackService.onPlayerError` restores announcements without recording the
  error. Non-announcement errors become `error`, then HaWebSocketClient maps
  them to idle. There is no buffering deadline. This hides the distinction
  between permission, connect, HTTP, content, decoding and timeout failures.
* The resolved MIME type reaches the service intent but is ignored there.
* HA's default media URL processing can select an internal URL. Android retains
  absolute URLs, so an RFC1918 URL cannot work on cellular without a VPN.
* The entity does not advertise HA's MEDIA_ANNOUNCE capability despite handling
  `announce` commands. The implementation now advertises it.
* TTS detection only recognizes `media-source://tts/` before resolution; direct
  HA `/api/tts_proxy/` requests are not identified as announcements.
* Registry session cleanup removes its own entry from `connection.subscriptions`.
  A live iteration over that dictionary raises RuntimeError. The existing test
  copies `.values()` to a list and therefore masks the mutation.

References checked during diagnosis:

* https://developer.android.com/privacy-and-security/local-network-permission
* https://github.com/home-assistant/core/blob/dev/homeassistant/components/media_player/browse_media.py
* https://github.com/home-assistant/core/blob/dev/homeassistant/helpers/network.py
* https://github.com/home-assistant/core/blob/dev/homeassistant/components/tts/media_source.py
* https://github.com/home-assistant/core/blob/dev/homeassistant/components/websocket_api/connection.py

HA-owned URL recognition and URL generation must use HA helpers. TTS proxy paths
are exempt from HA path signing; other HA media may require signatures. An
external URL is configuration, not evidence that a proxy serves a particular
path. Only the client GET/decoder and real-device test can establish delivery.

## Implementation and security rationale

HA resolves media sources and signs media paths through its existing processor.
Only `is_hass_url` matches can select `get_url(allow_internal=False,
require_ssl=True)`, which supports Cloud and configured external HTTPS URLs.
The already-escaped path and query are retained without decoding or rebuilding
query parameters. Public stream URLs are retained. No client-side hostname
replacement, credential copying, new trust anchors, or TLS bypass is introduced.
If no secure external endpoint exists, local-only behavior is retained. Clients
outside that network get a connection/hostname/timeout diagnostic instructing them
to configure external HTTPS. Endpoint configuration does not prove route delivery;
the production Media3 data source checks the real response on Android.

The client keeps its configured control origin, including credential-origin
binding. For roaming, configure the reachable HTTPS origin for control once and
use it on both networks. New media loads use Android's default network; existing
WebSocket reconnection/backoff remains in effect after network loss. In-flight
playback can fail during handover and is bounded by the loading deadline; seamless
continuation across a lost socket is not guaranteed. No LAN probing or automatic
HTTP fallback occurs. Local HTTP audio remains an independent, existing product
capability, explicitly selected by the supplied media URL (not the HTTP-control
opt-in); its audio bytes can be observed or modified on the LAN.

The official Media3 OkHttp adapter uses a separate unauthenticated client, 10-second
connect/read timeouts, normal TLS validation, and no redirects. This deliberately
requires direct public stream URLs, avoiding signature leakage to redirect targets
and HTTPS downgrades. HTML/JSON responses fail before decoding. Resolved audio
MIME types now reach MediaItem; unsupported container types still use sniffing.
Permission, HTTP status, TLS, DNS, connection, decoding, and timeout failures have
fixed diagnostics. No exception messages, headers, bodies, hosts, paths, queries,
or raw Throwables are added to application logs. Existing HA state content IDs
remain URLs as before; avoid verbose upstream transport logs or sharing entity
state dumps containing signed URLs.

A failed announcement restores the original item, position, and play/pause intent.
With no interrupted item, the service returns to idle with the diagnostic as its
media title. The latest sanitized failure remains in Android app status until the
next accepted media request. During restoration the HA state represents the
restored music; it does not persist a structured announcement failure. The ticker
repeats the resulting state, so HA's rate-limited state sender can deliver it.
No new WebSocket fields are sent; registered devices and command schemas remain
compatible. A future compatible extension would negotiate a `playback_diagnostics`
capability before adding a bounded, enum-only `last_playback_error` field to state;
clients must omit that field when talking to older integrations. This extension is
proposed, not implemented.

## Physical-device acceptance procedure (not yet performed)

Use a test HA instance and a debug installation; do not overwrite an installation
with a different signing key. Keep the enrolled device identity. Record Android
version, app/integration revision and permission grant state, without tokens/URLs.

1. On Wi-Fi, use local HTTP media with Local network permission granted. Start a
   public direct HTTPS MP3, then call `tts.speak` with the HAMediaConnect target.
   Confirm the app actually loads/decodes TTS, HA leaves buffering, and music
   resumes at its saved position. Repeat with paused music and no prior media.
2. On Android 17, deny Local network permission and repeat LAN playback. Confirm
   an actionable error within 30 seconds; grant it in app settings and retry.
   Also verify local HTTP control still requires its separate explicit opt-in.
3. Configure Cloud remote access (or the independent external HTTPS endpoint),
   connect control using that HTTPS origin, and repeat TTS over Wi-Fi. Turn Wi-Fi
   off, wait for authenticated control reconnection, then generate fresh TTS on
   cellular. Verify the actual generated media route serves audio through Cloud
   and separately through the proxy; a working homepage/WebSocket is insufficient.
4. Switch networks while buffering and while playing. Confirm bounded failure
   and restoration if the socket breaks, then successful fresh TTS without editing
   a URL. Test the reverse transition too. Do not expect expired URLs to recover.
5. Generate a missing/expired URL; on a controlled test proxy return 401, 403,
   404, 503, HTML, a redirect, and a stalled response. Confirm actionable fixed
   diagnostics and no indefinite buffering. Check `adb logcat -s HARemotePlayback`
   only; never share full transport logs or real signed media URLs.
6. Enable DND before speech and during speech. Verify suppression/restoration;
   verify volume, mute, stop, notification, lock-screen and Bluetooth controls.
7. Disconnect/reconnect repeatedly, restart HA, reboot the Android device, and
   verify enrollment persists, fresh proof is required, and no subscription
   cleanup exception occurs.

Automated tests cover URL selection, raw signatures, public URL preservation,
command compatibility, HTTP data-source response handling, fixed diagnostics,
announcement restoration, DND, and the buffering deadline. Fake URLs in service
tests verify state transitions, not real HTTPS audio decoding. Real TLS endpoint
routing, cellular handover, OEM behavior and physical decoding remain acceptance
checks. Emulator permission enforcement and Keystore checks are recorded below.


## Validation results

* Python compileall and 53 isolated integration tests pass, with 96.23% combined
  line/branch coverage (85% gate). The tests stub HA interfaces; they do not run
  a live HA instance. Test dependencies were installed/verified in the existing
  `/tmp/hamediaconnect-venv` (Python 3.12).
* 40 Android JVM/Robolectric tests pass; application line coverage is 97.6%,
  security line coverage is 87.5%. Both existing 85% gates pass. Debug APK builds.
* Three existing Keystore/service instrumentation tests pass on a newly created
  disposable Android 17 emulator, with device coverage data/report generated.
* The optional `LocalMediaDeviceTest` was run against a synthetic 3-second MP3
  served on the host loopback interface and accessed via emulator `10.0.2.2`.
  With Local network permission absent, Media3 stayed buffering until the
  30-second watchdog recorded a visible failure; the server saw no request.
  With permission granted, the same URL returned HTTP 200 and reached actual
  production-player `playing` state; the test completed in 0.717 seconds. This
  reproduces the permission mechanism with real decoding on Android 17, not on
  the physical Pixel. No real HA token, TTS text or signed URL was used.
* Gradle was launched with JAVA_HOME pointing to JDK 17; the repository's existing
  `gradle/gradle-daemon-jvm.properties` selects its JDK 25 daemon. That toolchain
  configuration was not changed. No assertion of a pure JDK 17 build is made.
* HACS/Hassfest GitHub actions and Python 3.13 CI were not executed locally.
  Docker and a live HA runtime are unavailable. No push or PR was requested;
  GitHub workflow results for this branch are therefore pending.

To repeat the optional emulator test, create a disposable AVD, generate a short
MP3 (for example with ffmpeg's sine source), and serve it using
`python3 -m http.server 18765 --bind 127.0.0.1 --directory <fixture-directory>`.
Name it `tts.mp3`. Install the debug app and Android-test APKs on that AVD only.
Then run the following twice, with the corresponding permission state:

```bash
# Use the disposable emulator serial, never an enrolled physical device.
adb -s <serial> shell pm revoke us.marreros.haandroidmediaplayer android.permission.ACCESS_LOCAL_NETWORK
adb -s <serial> shell am instrument -w \
  -e class us.marreros.haandroidmediaplayer.LocalMediaDeviceTest \
  -e local_media_test_url http://10.0.2.2:18765/tts.mp3 \
  -e local_media_expected denied \
  us.marreros.haandroidmediaplayer.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> shell pm grant us.marreros.haandroidmediaplayer android.permission.ACCESS_LOCAL_NETWORK
# Repeat the am instrument command with local_media_expected playing.
```

Without the fixture argument the optional test is skipped, so normal CI does not
contact a host endpoint. It must be deliberately enabled for the paired LAN test.
