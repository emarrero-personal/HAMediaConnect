# Native media_player protocol — initial implementation

The Home Assistant integration now exposes three authenticated WebSocket commands. The Android app is **unchanged** and does not yet use them.

## Registration

After the standard Home Assistant WebSocket authentication handshake, send:

```json
{"id": 10, "type": "hamediaconnect/register", "device_id": "android-living-room", "name": "Living Room Android"}
```

The device ID must be stable across app restarts. A different HA user cannot take over an existing device ID.

## Subscribe to HA commands

```json
{"id": 11, "type": "hamediaconnect/subscribe_commands", "device_id": "android-living-room"}
```

After the success result, commands arrive as events with `id: 11`, for example:

```json
{"id": 11, "type": "event", "event": {"action": "pause"}}
```

The subscription is tied to the authenticated connection. On reconnect, register and subscribe again.

## Report state

```json
{"id": 12, "type": "hamediaconnect/state", "device_id": "android-living-room", "state": {"state": "playing", "volume_level": 0.5, "media_title": "Example"}}
```

Allowed state values: off, idle, playing, paused, buffering, unavailable. Only the owner may update the device.

## Commands sent to Android

- `{"action":"play"}`
- `{"action":"pause"}`
- `{"action":"stop"}`
- `{"action":"volume_set","volume_level":0.5}`
- `{"action":"volume_mute","is_volume_muted":true}`
- `{"action":"play_media","media_content_type":"music","media_content_id":"https://..."}`

## Current limitations

This is an initial, untested server implementation. The registry is in-memory and resets on Home Assistant restart; clients must re-register. Device state is not persisted. The Android app does not yet send these commands. Playback URL resolution, TTS media-source support, stream permissions, command acknowledgements, and comprehensive tests are future work. The public OAuth identification endpoint is retained unchanged.

Do not treat a successful OAuth login as proof that native media playback works. Before deployment, run Python compilation and Home Assistant integration tests against the installed HA version.
