# HAMediaConnect — Developer Guide

This document describes the implementation and wire protocol in the **v0.2 feature branch**. For installation, see the [README](../README.md).

## Architecture

```text
Home Assistant automation / media_player / TTS
                 |
                 v
custom_components/ha_remote_media_player/
  media_player.py → registry.py → websocket_api.py
                 |
       Home Assistant WebSocket
         /api/websocket (WS/WSS)
                 |
                 v
Android ConnectionService
  HaOAuth + SecureTokenStore
  HaWebSocketClient (register, subscribe, state)
                 |
                 v
Android PlaybackService (Media3 ExoPlayer + MediaSession)
                 |
             Device audio
                 |
          PlayerStateBus
                 |
           ConnectionService → Home Assistant state
```

The companion integration provides the Home Assistant `media_player` entity and resolves `media-source://` URLs. The Android app manages connectivity, decoding incoming commands, playback and state reporting. The Android package and integration domain still use historical names for compatibility.

### Android code map

| File | Responsibility |
| --- | --- |
| `MainActivity.kt` | Dashboard, Settings, Help, OAuth callback and connection controls |
| `AppPrefs.kt` | Non-secret settings and stable device UUID |
| `SecureTokenStore.kt` | Android encrypted credential storage |
| `HaOAuth.kt` | OAuth authorization-code flow with PKCE and refresh |
| `ConnectionService.kt` | Foreground WebSocket connection, reconnect, command routing |
| `HaWebSocketClient.kt` | Native Home Assistant WebSocket protocol |
| `HaCommand.kt` | Parsed command model |
| `PlaybackService.kt` | Media3 playback, announcements, volume, mute and DND |
| `PlayerStateBus.kt` | In-process playback state updates |
| `BootReceiver.kt` | Optional reconnect after reboot |

### Home Assistant code map

| File | Responsibility |
| --- | --- |
| `__init__.py` | Registers custom WebSocket API and config entry |
| `config_flow.py` | Integration setup |
| `media_player.py` | Home Assistant entity and media actions |
| `registry.py` | Connected-device registry and command dispatch |
| `websocket_api.py` | WebSocket registration, subscriptions and state messages |
| `oauth_view.py` | OAuth support endpoint |

## Authentication and connection

The Android app connects to the instance's `/api/websocket` endpoint, using `wss://` for HTTPS and `ws://` for HTTP. It waits for `auth_required`, then sends:

```json
{"type":"auth","access_token":"REDACTED"}
```

OAuth is recommended for HTTPS deployments; long-lived access tokens are supported for trusted local HTTP networks. Never print credentials in logs or include them in bug reports.

After `auth_ok`, the app registers itself:

```json
{"id":1,"type":"ha_remote_media_player/register","device_id":"DEVICE_UUID","name":"Kitchen Tablet"}
```

After successful registration, it subscribes to commands:

```json
{"id":2,"type":"ha_remote_media_player/subscribe_commands","device_id":"DEVICE_UUID"}
```

Home Assistant delivers commands as WebSocket subscription events. The app reports playback state using `ha_remote_media_player/state`. ConnectionService retries on transient network failures; registration is repeated after reconnection.

## Commands sent to the Android app

The **native v0.2** interface uses the companion integration. These are command event payloads dispatched through the custom WebSocket subscription (not direct Home Assistant `fire_event` calls).

| `action` | Additional fields | Behavior |
| --- | --- | --- |
| `play_media` | `media_content_id` (absolute audio URL), optional `media_content_type`, `media_title`, `announce` | Load and play audio |
| `play` | none | Resume |
| `pause` | none | Pause |
| `stop` | none | Stop and clear playback |
| `volume_set` | `volume_level` number 0.0–1.0 | Set Android music stream volume |
| `volume_mute` | `is_volume_muted` boolean | Mute/unmute the app's ExoPlayer, not other Android apps |

Example command event data:

```json
{"action":"play_media","media_content_id":"https://example.com/audio.mp3","media_content_type":"music","media_title":"Sample","announce":false}
```

```json
{"action":"volume_set","volume_level":0.35}
```

```json
{"action":"volume_mute","is_volume_muted":true}
```

**Important:** `PlaybackService` contains a `seek` intent action, but the native WebSocket command decoder currently **does not expose seek**. Do not advertise it as a working remote command until implemented end-to-end. The older event-based MVP protocol mentioned in historical commits is not the current native v0.2 transport.

## Commands from Home Assistant

For normal users and automations, prefer the Home Assistant service/action API instead of sending native WebSocket messages:

```yaml
action: media_player.play_media
target:
  entity_id: media_player.your_android_device
data:
  media_content_type: music
  media_content_id: https://example.com/audio.mp3
```

```yaml
action: media_player.volume_set
target:
  entity_id: media_player.your_android_device
data:
  volume_level: 0.35
```

```yaml
action: media_player.volume_mute
target:
  entity_id: media_player.your_android_device
data:
  is_volume_muted: true
```

Supported Home Assistant entity features currently include PLAY, PAUSE, STOP, PLAY_MEDIA, VOLUME_SET and VOLUME_MUTE.

## State reporting

Android sends a native WebSocket state message resembling:

```json
{
  "id":3,
  "type":"ha_remote_media_player/state",
  "device_id":"DEVICE_UUID",
  "state":{
    "state":"playing",
    "volume_level":0.35,
    "is_volume_muted":false,
    "media_title":"Sample",
    "media_content_id":"https://example.com/audio.mp3"
  }
}
```

The `state` object may omit media metadata when unavailable. Supported playback states include `idle`, `buffering`, `playing` and `paused`; the Android client currently normalizes `error` to `idle` before publishing.

## Announcements, audio focus and DND

When the companion integration receives a TTS media-source URL (`media-source://tts/...`), it resolves it to a playable URL and sets `announce=true`. For an announcement, the Android player saves its own current media item, playback position and playing/paused status, then restores them when the announcement finishes. Audio from another Android app is not directly controlled by this playback service.

The app suppresses announcements when Android's interruption filter indicates Do Not Disturb. If DND activates during an announcement, the service ends it and restores previously interrupted playback. This behavior should be tested on target devices because Android audio policy and OEM behavior vary.

## Development workflow

1. Check out `feature/v0.2-oauth-media-player` for ongoing development. Do not modify `main` until a reviewed merge.
2. Open the project in Android Studio and sync Gradle.
3. Build and run on Android 10+ (minSdk 29). Test both OAuth and local token authentication.
4. Copy the custom integration to a Home Assistant test instance and restart HA.
5. Verify registration, media playback, TTS, mute, DND, reconnect and boot recovery.
6. Run the Python integration tests where the Home Assistant test dependencies are available.
7. Review [third-party notices](../THIRD_PARTY_NOTICES.md) before release.

Do not treat this guide as a security or build certification. The branch still requires a complete compile/test pass, transitive dependency license review, and release packaging.
