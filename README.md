<p align="center">
  <img src="docs/mascot.svg" alt="HAMediaConnect Android guitarist mascot" width="210">
</p>

# HAMediaConnect</h1>

### Turn an Android phone or tablet into a Home Assistant audio player and announcement endpoint.

HAMediaConnect consists of an Android app and a companion Home Assistant custom integration. Once connected, the Android device appears as a native `media_player` entity in Home Assistant, so you can play audio, control playback and volume, and deliver text-to-speech (TTS) announcements from scripts, automations, and the Home Assistant interface.

## What it can do
- Play HTTP/HTTPS audio on an Android device using Media3 / ExoPlayer.
- Control play, pause, stop, volume and mute through Home Assistant.
- Play Home Assistant-generated TTS and media-source content through the companion integration.
- Resume app-managed audio at its prior position after an announcement.
- Mix announcements with audio from other Android apps when Android audio behavior permits.

## Getting started

### 1. Install the Home Assistant integration
The companion integration is required to expose your Android device as a Home Assistant media player.

#### Option A: HACS (recommended)
1. Install [HACS](https://www.hacs.xyz/docs/use/) if you haven't already.
2. In Home Assistant, open **HACS → three-dot menu → Custom repositories** (the location of this menu can vary with HACS version).
3. Paste the repository URL: `https://github.com/emarrero-personal/HAMediaConnect`.
4. Select **Integration** as the repository type and add it.
5. Find **HAMediaConnect** in HACS, open it, and choose **Download**.
6. Restart Home Assistant.
7. Open **Settings → Devices & services → Add integration**, search for **HAMediaConnect**, and complete the configuration flow.

#### Option B: Manual installation (current feature branch)
1. Open the [feature branch](https://github.com/emarrero-personal/HAMediaConnect/tree/feature/v0.2-oauth-media-player) and download its ZIP, or clone and check out that branch.
2. Copy the **contents** of `custom_components/hamediaconnect/` to `/config/custom_components/hamediaconnect/` on your Home Assistant instance.
3. Restart Home Assistant.
4. Open **Settings → Devices & services → Add integration**, search for **HAMediaConnect**, and complete the configuration flow.



### 2. Install the Android app
#### Method 1 - APK Sideload
APK sideloads feel sketchy to me too but I'm a one-man-band with some LLM help and I don't have the time resources to get this into the Play Store.

Download the latest signed distribution APK from here: **[Latest HAMediaConnect.apk](https://github.com/emarrero-personal/HAMediaConnect/releases/latest/download/HAMediaConnect.apk)**
 - [View releases](https://github.com/emarrero-personal/HAMediaConnect/releases) - [SHA-256 checksum](https://github.com/emarrero-personal/HAMediaConnect/releases/latest/download/HAMediaConnect.apk.sha256)

**Do not download APKs from unofficial mirrors.** unless you enjoy an interesting life. The debug APK is intended for testing, not for Play Store publication.
1. On your **Android 10+ phone or tablet**, download the APK from the project's GitHub Actions build artifacts (or transfer the APK from your computer).
2. Open the APK from **Files** or **Downloads**. If Android blocks installation, tap **Settings** when prompted and enable **Allow from this source** for the specific app you used to open it (such as Files or your browser).
3. Return to the installer, tap **Install**, and then **Open**.
4. Disable **Allow from this source** again after installation if you don't expect to sideload more apps.

#### Method 2 - Build it!
Open the repository in Android Studio with JDK 17 and the required SDK, sync Gradle, and run `./gradlew assembleDebug`. The APK will be at `app/build/outputs/apk/debug/app-debug.apk`. Install it via Android Studio or `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

### 3. Connect
1. Enter your Home Assistant base URL, for example `https://example.ui.nabu.casa` or `http://homeassistant.local:8123`.
2. In Settings, leave **Allow insecure HTTP connections** unchecked (the default). If your Home Assistant server has no HTTPS endpoint, explicitly enable it before connecting.
3. Choose one authentication method:
   - **OAuth:** Recommended for an HTTPS-accessible Home Assistant instance. Tap **Sign in with Home Assistant** and authorize the app.
   - **Long-lived access token:** Useful on trusted local networks when your instance is available only over HTTP. Generate a token from your Home Assistant user profile, paste it into the app, and tap **Save & connect**.
4. Set a friendly device name, and optionally enable start on boot.
5. Wait until the dashboard reports **Connected**.

**Security:** HTTP does not encrypt Home Assistant tokens or control traffic in transit. The insecure HTTP switch defaults to disabled; enable it only when you understand the risk and cannot use HTTPS. Never expose an HTTP token connection to the public internet. HTTP media playback is independent of this switch.

### 4. Play something
In Home Assistant, find the new `media_player` entity under **Settings → Devices & services → HAMediaConnect**. Use the media player controls, call `media_player.play_media` with a playable audio URL, or target the entity from a TTS automation.

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

### 5. Use HAMediaConnect with Music Assistant
Music Assistant does not automatically list every Home Assistant `media_player` entity as a playback destination. To expose HAMediaConnect:

1. Open **Music Assistant → Settings → Player Providers**.
2. Add the **Home Assistant Media Players** player provider if it is not already installed.
3. Open that provider's settings and **select the Home Assistant `media_player` entities** you want to expose to Music Assistant (including your HAMediaConnect player).
4. Save the settings. Your selected players should then be available as Music Assistant playback destinations.

If HAMediaConnect works from Home Assistant but is missing in Music Assistant, check the player selections in the **Home Assistant Media Players** provider first.

## Security

**Turn off insecure HTTP** in Android Settings whenever your Home Assistant instance supports HTTPS. This setting is off by default and blocks unencrypted Home Assistant authentication and WebSocket control traffic. Only enable it for a trusted local Home Assistant installation without SSL; anyone able to intercept that connection could capture your long-lived token and control Home Assistant with its permissions. Avoid exposing port 8123 over the internet. Prefer HTTPS/WSS via Home Assistant Cloud or your own properly configured TLS endpoint.

Credentials and the temporary OAuth PKCE verifier are encrypted at rest with Android Keystore AES-256-GCM. Ordinary preferences contain nonsecret settings only. Credential files are excluded from Android backup and device transfer. The app does not display saved access tokens in the settings form; leaving the token field empty retains the previously saved token when using token authentication.

HTTP/HTTPS **media playback** is allowed separately so local audio and TTS URLs continue to work even when insecure Home Assistant control connections are disabled.

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

HAMediaConnect is licensed under the **GNU General Public License, version 3 or (at your option) any later version** (**SPDX: GPL-3.0-or-later**). See the complete [LICENSE](LICENSE) text. You may use, study, modify, and redistribute the covered source code, including commercially, subject to the GPL's terms. Distributors must comply with GPL source-code and notice obligations; commercial use is not prohibited.

The project-owned source code is offered under GPL-3.0-or-later. Third-party components retain their own copyrights and license terms; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the dependency inventory and outstanding release-attribution verification tasks.

**Branding:** The HAMediaConnect name and mascot are not separately licensed by this statement. No trademark rights are granted by the GPL; copyright licensing of artwork included in the source distribution must be established separately before treating it as reusable branding.

### Device authentication and revocation

The Android app and HA integration must be updated together for the possession-based device protocol. Each installation uses an Android Keystore P-256 key; a fresh signature is required after every HA WebSocket login. Device identity bindings and revoked-key records persist across HA restarts. See the [Phase 2 security design](docs/SECURITY_PHASE_2.md) for administrator revocation, explicit device identity reset, credential recovery, HTTP behavior, and validation limits.
