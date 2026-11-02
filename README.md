<p align="center">
  <img src="docs/mascot.svg" alt="HAMediaConnect Android guitarist mascot" width="210">
</p>

<h1 align="center">HAMediaConnect</h1>

<p align="center"><strong>Turn an Android phone or tablet into a Home Assistant audio player and announcement endpoint.</strong></p>

HAMediaConnect consists of an Android app and a companion Home Assistant custom integration. Once connected, the Android device appears as a native `media_player` entity in Home Assistant, so you can play audio, control playback and volume, and deliver text-to-speech (TTS) announcements from scripts, automations, and the Home Assistant interface.

> **Development status:** Android app v0.9.0 on the v0.2 feature branch, under active development. The Android UI and integration are being tested; features and installation steps may change. This repository retains its historical name, `HARemoteMediaPlayer`, for compatibility.

## What it can do

- Play HTTP/HTTPS audio on an Android device using Media3 / ExoPlayer.
- Control play, pause, stop, volume and mute through Home Assistant.
- Play Home Assistant-generated TTS and media-source content through the companion integration.
- Resume app-managed audio at its prior position after an announcement.
- Mix announcements with audio from other Android apps when Android audio behavior permits.
- Suppress Home Assistant announcements while Android Do Not Disturb is enabled.
- Reconnect after temporary network interruptions.
- Authenticate using OAuth over HTTPS (recommended), or a long-lived access token for trusted local HTTP networks.
- Optionally reconnect after device reboot.

## Getting started

### 1. Install the Home Assistant integration

The companion integration is required to expose your Android device as a Home Assistant media player.

#### Option A: HACS (recommended)

1. Install [HACS](https://www.hacs.xyz/docs/use/) if you haven't already.
2. In Home Assistant, open **HACS → three-dot menu → Custom repositories** (the location of this menu can vary with HACS version).
3. Paste the repository URL: `https://github.com/emarrero-personal/HARemoteMediaPlayer`.
4. Select **Integration** as the repository type and add it.
5. Find **HA Remote Media Player** in HACS, open it, and choose **Download**.
6. Restart Home Assistant.
7. Open **Settings → Devices & services → Add integration**, search for **HA Remote Media Player**, and complete the configuration flow.

**Development branch note:** The v0.9 Android changes and current companion integration are on `feature/v0.2-oauth-media-player`, not `main`. HACS custom-repository installs generally track the repository's default branch or published releases; they do **not** automatically install this feature branch. Until the branch is merged or released, use the manual installation below to obtain the matching integration code.

#### Option B: Manual installation (current feature branch)

1. Open the [feature branch](https://github.com/emarrero-personal/HARemoteMediaPlayer/tree/feature/v0.2-oauth-media-player) and download its ZIP, or clone and check out that branch.
2. Copy the **contents** of `custom_components/ha_remote_media_player/` to `/config/custom_components/ha_remote_media_player/` on your Home Assistant instance.
3. Restart Home Assistant.
4. Open **Settings → Devices & services → Add integration**, search for **HA Remote Media Player**, and complete the configuration flow.

If HACS does not recognize the repository or no integration is available for download, use the manual installation until the project is published in a HACS-compatible release.

### 2. Install the Android app (APK sideload)

Once a build artifact is available, download `HAMediaConnect-v0.9.0-debug.apk` from the GitHub Actions run for this branch. **Do not download APKs from unofficial mirrors.** The debug APK is intended for testing, not for Play Store publication.

1. On your Android 10+ phone or tablet, download the APK from the project's GitHub Actions build artifacts (or transfer the APK from your computer).
2. Open the APK from **Files** or **Downloads**. If Android blocks installation, tap **Settings** when prompted and enable **Allow from this source** for the specific app you used to open it (such as Files or your browser).
3. Return to the installer, tap **Install**, and then **Open**.
4. Disable **Allow from this source** again after installation if you don't expect to sideload more apps.
5. Open **HAMediaConnect**, tap the **gear**, and configure your Home Assistant connection.

**Updates:** A debug APK must be signed with the **same signing key** as your previously installed version to update it in place. Different developers' debug APKs usually have different keys. If Android reports an incompatible package or signature, uninstalling the old app will also remove its stored login/settings; plan to reconnect afterward.

**Alternative: build locally.** Open the repository in Android Studio with JDK 17 and the required SDK, sync Gradle, and run `./gradlew assembleDebug`. The APK will be at `app/build/outputs/apk/debug/app-debug.apk`. Install it via Android Studio or `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

**Availability:** The APK download link should only be advertised after a successful build. GitHub Actions artifacts may require GitHub sign-in and expire after their retention period.

### 3. Connect

1. Enter your Home Assistant base URL, for example `https://example.ui.nabu.casa` or `http://homeassistant.local:8123`.
2. Choose one authentication method:
   - **OAuth:** Recommended for an HTTPS-accessible Home Assistant instance. Tap **Sign in with Home Assistant** and authorize the app.
   - **Long-lived access token:** Useful on trusted local networks when your instance is available only over HTTP. Generate a token from your Home Assistant user profile, paste it into the app, and tap **Save & connect**.
3. Set a friendly device name, and optionally enable start on boot.
4. Wait until the dashboard reports **Connected**.

**Security:** HTTP does not encrypt tokens or playback URLs in transit. Never expose a token-based HTTP connection directly to the public internet. Prefer HTTPS whenever possible.

### 4. Play something

In Home Assistant, find the new `media_player` entity under **Settings → Devices & services → HA Remote Media Player**. Use the media player controls, call `media_player.play_media` with a playable audio URL, or target the entity from a TTS automation.

Example Home Assistant action:

```yaml
action: media_player.play_media
target:
  entity_id: media_player.your_android_device
data:
  media_content_type: music
  media_content_id: https://example.com/audio.mp3
```

Replace the example entity ID and URL with your own.

## Troubleshooting

- **No media player entity:** Verify the custom integration is installed, configured, and loaded, and that the Android app reports Connected.
- **OAuth sign-in unavailable:** Check that the URL starts with `https://` and is reachable from the Android device.
- **Local HTTP setup:** Use the long-lived-token method and ensure the device can reach Home Assistant on the LAN.
- **No sound:** Check Android media volume, mute state, Do Not Disturb for announcements, and that the audio URL is accessible from Android.
- **Connection drops:** Check Wi-Fi connectivity and Android battery optimization restrictions. The app attempts reconnection.

## Documentation

- [Developer guide — architecture, WebSocket protocol, commands and state](docs/DEVELOPERS.md)
- [Third-party licenses and attribution review](THIRD_PARTY_NOTICES.md)

## Open-source licensing

The project intends to use **GPL-3.0-or-later** to keep redistributed improvements available to the community. A final license file and complete distribution notices are still being prepared; do not assume the project has been formally licensed until those files are present. Third-party components retain their own licenses.

The HAMediaConnect name and mascot artwork are separate branding assets; their reuse terms should be specified before public release.
