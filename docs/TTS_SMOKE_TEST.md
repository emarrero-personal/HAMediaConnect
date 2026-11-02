# Text-to-speech smoke test — HA Remote Media Player

This change is **Home Assistant-side only**. The working Android OAuth and native playback code are unchanged.

## Install

1. On the feature branch, run `git pull --ff-only` before copying integration files.
2. Confirm the GitHub Actions validation workflow is green for the latest commit.
3. Back up the installed `/config/custom_components/ha_remote_media_player` directory.
4. Copy the updated integration directory into `/config/custom_components/`.
5. Restart Home Assistant. Reload alone does not guarantee changed Python modules are reimported.
6. Confirm Android reconnects and the native media player becomes available.

## Test TTS

In Developer Tools → Actions, select `tts.speak`, switch to YAML, and adapt the engine and player entity IDs:

```yaml
action: tts.speak
target:
  entity_id: tts.YOUR_TTS_ENGINE
data:
  media_player_entity_id: media_player.YOUR_ANDROID_PLAYER
  message: "This is a test of Home Assistant remote media player."
```

The HA integration resolves the generated `media-source://tts/...` URI, converts the returned relative URL to an absolute URL, and sends it over the existing authenticated WebSocket. The Android player then fetches the audio through Media3.

## Verify

- Spoken audio is audible on Android.
- HA entity reports buffering, then playing.
- Playback returns to idle or stops when finished.
- No `media_source` or `tts` errors in HA logs.

## Limitations

This initial implementation treats TTS as ordinary media playback. It does **not** yet duck/pause and resume existing music when `announce: true` is requested. The generated URL must be reachable from the Android device; HA's URL selection and network access may need adjustment if Android is on another network. Signed TTS URLs may expire. No long-lived access token should be embedded in media URLs.

If the test fails, restore the backed-up integration folder and restart HA. Preserve the working Android APK.
