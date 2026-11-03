# Home Assistant native media player — safe installation checkpoint

## Status

The integration contains the working OAuth identification page, a config flow, a native media_player platform, an in-memory player registry, and authenticated WebSocket registration/state/command subscription.

Android has **not** been changed in this phase. Its existing OAuth connection will not automatically register a native media player. **Do not expect a new media_player entity yet.** The current integration does not persist devices across HA restarts.

The feature branch now has a Python compilation and isolated registry-test GitHub Actions workflow at `.github/workflows/validate-ha.yml`. Verify the workflow run succeeds before installation. This is not a substitute for Home Assistant runtime tests.

## Safe deployment

1. Back up the existing `/config/custom_components/hamediaconnect` directory.
2. In GitHub, confirm **Validate Home Assistant integration** is green for the feature branch.
3. Copy the feature branch's `custom_components/hamediaconnect` directory over the existing installation.
4. **Do not** change the working Android app, its OAuth credentials, or Nabu Casa settings.
5. Restart Home Assistant and inspect logs for `hamediaconnect` and `websocket_api` exceptions.
6. Confirm **Settings → Devices & services → HAMediaConnect** still loads.
7. In a private browser, confirm `https://YOUR-INSTANCE.ui.nabu.casa/api/hamediaconnect/oauth-client` still responds without login.
8. A native media player entity is created only after an authenticated WebSocket client calls `hamediaconnect/register`. Android does not yet do this.

## Rollback

If Home Assistant reports integration setup errors, restore the backed-up integration folder and restart Home Assistant. The Android app and main branch are unaffected.

## Remaining verification

- Home Assistant integration setup and entity lifecycle in a real HA environment.
- Actual WebSocket command authorization, disconnect cleanup, and event delivery.
- Android registration and command handling.
- Playback/TTS URL resolution and refresh-token behavior.
