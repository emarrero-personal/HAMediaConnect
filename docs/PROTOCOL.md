# Event protocol

## Command event

Event type: `ha_android_media_player_command`

Fields:

- `target`: Android-generated device UUID or `*`
- `command`: `play_media`, `play`, `pause`, `stop`, `set_volume`, `seek`
- `url`: required for `play_media`
- `title`: optional for `play_media`
- `content_type`: reserved for future use
- `volume`: float 0.0–1.0 for `set_volume`
- `position`: milliseconds for `seek`

## State event

Event type: `ha_android_media_player_state`

Fields:

- `device_id`
- `name`
- `state`
- `volume_level`
- `position_ms`
- `duration_ms`
- `media_title`
- `media_content_id`
- `connected`
- `app_version`
