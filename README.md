# HA Android Media Player

A small standalone Android app that turns an Android phone, tablet, or wall panel into an audio endpoint controlled by Home Assistant.

**Version:** 0.1.0 MVP scaffold

## What works in this MVP

- Connects directly to Home Assistant's `/api/websocket` endpoint.
- Authenticates with a Home Assistant long-lived access token.
- Maintains a persistent foreground connection service with automatic reconnect.
- Subscribes to `ha_android_media_player_command` events.
- Plays arbitrary HTTP/HTTPS audio using AndroidX Media3 / ExoPlayer.
- Supports play, pause, stop, seek, and system media volume.
- Runs playback through a proper Android `MediaSessionService`.
- Publishes playback state through `ha_android_media_player_state` events.
- Can restart its connection service after device reboot.

This deliberately does **not** create a native `media_player` entity yet. The companion Home Assistant custom integration will translate normal `media_player` actions to/from this event protocol. Keeping that adapter out of the Android app makes the transport independently testable.

## Current toolchain

The project targets current stable Android tooling as of August 2026:

- Android Gradle Plugin 9.3.0
- Gradle 9.5.0
- JDK 17
- compileSdk / targetSdk 37
- minSdk 29 (Android 10)
- AndroidX Media3 1.11.0
- Kotlin supplied by AGP 9 built-in Kotlin support

The Gradle wrapper **JAR is not bundled** because the build environment that produced this ZIP had no network access to download it. On a machine with internet access, run:

```bash
./bootstrap-gradle-wrapper.sh
```

That downloads Gradle 9.5.0 once and generates the standard wrapper JAR. If you already have Gradle installed, `gradle wrapper --gradle-version 9.5.0` does the same thing.

## Install the Home Assistant integration

Before using HAMediaConnect as a Home Assistant media player, install its companion custom integration:

1. Download this repository or clone it locally.
2. Copy `custom_components/ha_remote_media_player` into your Home Assistant configuration directory at `/config/custom_components/ha_remote_media_player`.
3. Restart Home Assistant.
4. In **Settings → Devices & services**, add **HA Remote Media Player** if it is offered through the integration's config flow. Otherwise check Home Assistant logs and the integration's setup documentation.
5. In HAMediaConnect on Android, open **Settings**, enter your Home Assistant URL, choose OAuth for HTTPS or a long-lived access token for a trusted local HTTP network, and connect.
6. Find the registered media player entity in Home Assistant.

Do not expose a token-based HTTP connection to the public internet.

## Build

1. Open the project directory in Android Studio.
2. Let Android Studio install Android SDK 37 / Build Tools if requested.
3. Sync Gradle.
4. Run the `app` configuration on an Android 10+ device.

For a dedicated tablet, disable battery optimization for the app after installation. Android's foreground-service rules continue to evolve, so reliability across OEMs is one of the main things this MVP is intended to test.

## Configure

In the app enter:

- **Home Assistant URL:** for example `https://homeassistant.example.com`
- **Long-lived access token:** create one from your Home Assistant user profile.
- **Device name:** e.g. `Kitchen Tablet`
- Optionally enable **Start connection after reboot**.

Press **Save & Start**.

The screen shows a generated device UUID. Commands can target that UUID or `*`.

> Security note: v0.1 stores the token in app-private SharedPreferences. Android sandboxing prevents other normal apps from reading it, but a future release should move the credential to Keystore-backed encrypted storage and replace manual tokens with OAuth/pairing.

## Test from Home Assistant

The Android app listens for this event:

`ha_android_media_player_command`

You can fire it from **Developer Tools → Events**, or from an automation/script using a fire-event action.

### Play media

```yaml
event: ha_android_media_player_command
event_data:
  target: "YOUR-DEVICE-UUID"
  command: play_media
  url: "https://example.com/audio.mp3"
  title: "Test audio"
```

Target all connected Android players with:

```yaml
target: "*"
```

### Pause

```yaml
event: ha_android_media_player_command
event_data:
  target: "YOUR-DEVICE-UUID"
  command: pause
```

### Resume

```yaml
event: ha_android_media_player_command
event_data:
  target: "YOUR-DEVICE-UUID"
  command: play
```

### Stop

```yaml
event: ha_android_media_player_command
event_data:
  target: "YOUR-DEVICE-UUID"
  command: stop
```

### Set volume

Volume is normalized from `0.0` to `1.0` and changes Android's `STREAM_MUSIC` system volume.

```yaml
event: ha_android_media_player_command
event_data:
  target: "YOUR-DEVICE-UUID"
  command: set_volume
  volume: 0.65
```

### Seek

Position is milliseconds.

```yaml
event: ha_android_media_player_command
event_data:
  target: "YOUR-DEVICE-UUID"
  command: seek
  position: 30000
```

## State events

The app publishes `ha_android_media_player_state` events shaped like:

```json
{
  "device_id": "4e2f...",
  "name": "Kitchen Tablet",
  "state": "playing",
  "volume_level": 0.65,
  "position_ms": 12873,
  "duration_ms": 98321,
  "media_title": "Test audio",
  "media_content_id": "https://example.com/audio.mp3",
  "connected": true,
  "app_version": "0.1.0"
}
```

Possible MVP states are `idle`, `buffering`, `playing`, `paused`, and `error`.

## Protocol notes

Home Assistant's WebSocket API is used only with documented commands:

- `auth`
- `subscribe_events`
- `fire_event`

This means the Android transport can be tested without installing custom Home Assistant code.

The next project should be a HACS-compatible custom integration that:

1. Discovers players from their state/hello events.
2. Creates a `media_player` entity per device.
3. Implements `play_media`, play, pause, stop, seek, and volume.
4. Resolves Home Assistant `media-source://` content to an HTTP/signed URL before sending it to Android.
5. Marks devices unavailable when their heartbeat expires.

## Known MVP limitations

- Long-lived token instead of OAuth/pairing.
- Token is app-private but not yet Keystore-encrypted.
- No native HA `media_player` entity yet.
- Connection and playback live in separate services; a future version should harden process/boot recovery further.
- No media browsing, queue, album art, TTS-specific convenience layer, Bluetooth output chooser, or per-app volume.
- `set_volume` controls Android's global music-stream volume.
- Foreground `specialUse` service is practical for sideloading/dedicated panels but would require policy review before Play Store distribution.

## License

Prototype project. Add your preferred OSS license before publishing.
