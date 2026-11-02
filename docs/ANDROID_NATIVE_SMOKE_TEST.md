# Android native WebSocket smoke test (v0.2)

This feature branch replaces the legacy HA event subscription with the native integration commands. OAuth PKCE and encrypted token storage were not modified.

## Prerequisites

- HA custom integration from the same feature branch installed and restarted.
- HA integration configured in Settings > Devices & services.
- Android app built from this feature branch and signed in via the existing OAuth flow.

## Test

1. In Android Studio, sync and build the app; confirm there are no compiler errors.
2. Launch the app and use the existing OAuth sign-in. Start the connection service.
3. Check its status reaches **Connected** (authenticated, registered, command subscription acknowledged).
4. In HA, find the native media player using the configured Android device name.
5. Verify it is available while Android is connected.
6. From HA, send Play, Pause, and Stop and verify Android responds.
7. Play an HTTP/HTTPS audio URL through HA `media_player.play_media`; verify audio output.
8. Adjust volume through HA and verify Android's music-stream volume changes.
9. Disconnect the Android connection and verify the entity becomes unavailable.
10. Reconnect; verify registration and subscription recover.

## Known gaps

- Mute commands are ignored by the Android client for now.
- HA `media-source://` and TTS URLs are not resolved by the integration; use direct reachable HTTP/HTTPS media for this smoke test.
- Playback-state updates begin when the existing Android playback service publishes a snapshot; initial state may remain idle until then.
- The registry is memory-only; devices must re-register after HA restarts.
- The Android app has not yet been compiled or tested on a device in this development session.
- Do not use an administrator long-lived token for normal operation. The existing OAuth access token is used for native registration.

## Rollback

The feature branch retains the earlier Android implementation in Git history. Keep a copy of your previously installed APK before testing. Do not merge to main until device tests pass.
