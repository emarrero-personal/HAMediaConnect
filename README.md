<p align="center">
  <img src="branding/HAMediaConnect.svg" alt="HAMediaConnect phone, speaker, and sound waves icon" width="210">
</p>

# HAMediaConnect

<p align="center">
  <a href="https://github.com/emarrero-personal/HAMediaConnect/stargazers"><img src="https://img.shields.io/github/stars/emarrero-personal/HAMediaConnect?style=for-the-badge&amp;label=Stars&amp;color=d6a102" alt="Stars"></a>
  <a href="https://github.com/emarrero-personal/HAMediaConnect/releases"><img src="https://img.shields.io/github/downloads/emarrero-personal/HAMediaConnect/total?style=for-the-badge&amp;label=Downloads&amp;color=e8604c" alt="Downloads"></a>
  <a href="https://github.com/emarrero-personal/HAMediaConnect/releases/latest"><img src="https://img.shields.io/github/v/release/emarrero-personal/HAMediaConnect?style=for-the-badge&amp;label=Version&amp;color=5da3a6" alt="Version"></a>
  <a href="https://github.com/emarrero-personal/HAMediaConnect/actions/workflows/distribution-apk.yml"><img src="https://img.shields.io/github/actions/workflow/status/emarrero-personal/HAMediaConnect/distribution-apk.yml?style=for-the-badge&amp;label=Build&amp;color=3fbf5f" alt="Build"></a>
</p>

Turns an Android phone or tablet into a native Home Assistant media player. Play music and other audio, control playback and volume, and send Home Assistant TTS announcements to the device. Music Assistant can use the player through Home Assistant's Media Players provider.

HAMediaConnect includes an Android app and a Home Assistant custom integration. Both are required. Home Assistant 2026.10 or newer is required. OAuth also requires HTTPS and a reachable `/.well-known/oauth-authorization-server` endpoint advertising S256 PKCE.

# How does it work?
It's an Android app with an accompanying Home Assistant integration. The app connects to Home Assistant and registers with the integration. The integration creates entities you can use to send audio, identify the device, etc.

# Installation
You need both parts for things to work correctly. It's better if you install the HA integration before the app so that the app can register itself when it first connects to HA.
## Install Home Assistant integration

### With Marketplace
1. In Home Assistant, open **Settings → Marketplace**.
2. Find HAMediaConnect and click Install
3. Restart Home Assistant.

### Manual installation

1. Download the source archive for the [latest release](https://github.com/emarrero-personal/HAMediaConnect/releases/latest) or clone the repository.
2. Copy `custom_components/hamediaconnect/` into `/config/custom_components/hamediaconnect/` on your Home Assistant installation. Keep the directory name `hamediaconnect` and copy the whole folder, including `brand/`.
3. Restart Home Assistant.
4. Add **HAMediaConnect** from **Settings → Devices & services → Add integration**.

## Install Android app
I'd love to publish it in the Google Play store but they require me to provide a running instance of HA for reviewers to use. For now you'll have to side-load a pre-built APK or clone the repo and roll your own to be sure there are no nasty bits buried in there.

**Whatever you do, please don't side load an HAMediaConnect APK you didn't get yourself from the releases section on this repo!**

Download the signed APK from the [latest GitHub Release](https://github.com/emarrero-personal/HAMediaConnect/releases/latest):
- [Download HAMediaConnect.apk](https://github.com/emarrero-personal/HAMediaConnect/releases/latest/download/HAMediaConnect.apk)
- [Download its SHA-256 checksum](https://github.com/emarrero-personal/HAMediaConnect/releases/latest/download/HAMediaConnect.apk.sha256)

On an Android 10 or newer phone or tablet, open the downloaded APK and follow Android's installer prompts. If Android asks, allow your browser or file manager to install apps from that source. Disable that permission again after installation if you do not need it.
## Connect your device
1. Optionally but recommended - create a HA user for HAMediaConnect. The user should be a HA administrator for initial connections but can then be downgraded after the device registers itself.
2. Open HAMediaConnect and enter an HTTPS Home Assistant base URL, such as `https://example.ui.nabu.casa`.
3. Home Assistant control requires HTTPS/WSS with a valid certificate. The easiest way to do that and support HA is to use [Home Assistant Link](https://www.nabucasa.com/link/)
4. Sign in with OAuth or a long lived token.
5. Save the connection and keep the app running until it reports **Connected**. On first connection, Android creates an installation-specific key in Android Keystore and proves possession to Home Assistant. The key is not uploaded. The integration then creates the device's `media_player` entity.

### Enable background audio on Android 17 and newer

Tap **Save & connect** while HAMediaConnect is open to enable announcements when
the screen is locked or another app is open. Leave the connection notification
running. If audio needs enabling, tap **Enable audio** directly in the
HAMediaConnect connection notification. You do not need to open the app. You can
also tap **Enable background audio** in the app's Playback card. Your saved
credentials and existing device identity are preserved.

After reboot, an app update, or Android stopping and recreating the connection
service, background audio must be enabled again. 

**Why it does not survive reboot:** Android 17 restricts background audio to a
service started with user interaction. A service launched automatically at boot
can reconnect to Home Assistant but does not receive the same audio capability.
Saving an "enabled" setting cannot restore that capability; tapping **Enable
audio** provides the user interaction Android requires. **Start connection after
reboot** therefore restores the network connection, not background audio. A
**Connected** status alone does not mean audio is enabled. See
[Android's background audio requirements](https://developer.android.com/about/versions/17/changes/bg-audio).


### Remove a device
To remove a phone or tablet and its `media_player` entity, open **Settings → Devices & services → HAMediaConnect**, select the device, and choose **⋮ → Delete**. Delete works only while the device is disconnected. If it is still connected, Home Assistant shows an error. Before deleting, use **Forget Home Assistant credentials** in the app's Settings, uninstall the app, or revoke the device.

Deleting does not block the device. If the same app connects again with its original key, it enrolls again and gets a new entity. To permanently block a device key, revoke it first (see the [security guide](docs/SECURITY_PHASE_2.md#persistence-revocation-key-loss)), then delete it. A revoked device keeps its block after you delete it. After a Home Assistant restart, a revoked device's leftover entity shows as no longer provided, and you can delete it from the entity's settings.

## Identify a device
Each HAMediaConnect device has a **identify** button alongside its media player.
Open **Settings → Devices & services → HAMediaConnect**, select the device, and
press **identify** to play the bundled sound on that device. You can also add the
button entity to a dashboard or call it from an automation:

```yaml
action: button.press
target:
  entity_id: button.your_android_device_sonar_ping
```

Replace the example entity ID with your device's button. The ping is delivered
over the device's authenticated connection as an announcement, so interrupted
music resumes afterward. It uses the current volume and mute setting and respects
Do Not Disturb. No separate media URL or Internet download is needed to play it.
The button is unavailable while disconnected or if the client app does not support
direct audio transfers; update an older client app before using it. After updating
the integration, restart Home Assistant to create buttons for existing devices.

## Play audio, TTS, and Music Assistant

After the app connects, find its `media_player` entity in Home Assistant. For a direct audio URL, use **Media player → Play media** or an action such as:

```yaml
action: media_player.play_media
target:
  entity_id: media_player.your_android_device
data:
  media_content_type: music
  media_content_id: https://example.com/audio.mp3
```

Replace the example entity and URL with your own. The URL must be reachable from the Android device. Home Assistant media-source content and generated TTS are resolved by the integration; local HTTP media remains available while control uses HTTPS.

To send speech, use your configured Home Assistant text-to-speech provider's **Speak** action and select the HAMediaConnect player as the media player target.

To use Music Assistant, open **Music Assistant → Settings → Player Providers**, add or open **Home Assistant Media Players**, select the HAMediaConnect entity, and save. Music Assistant does not automatically expose every Home Assistant player.

## Updating
Update HAMediaConnect in HACS (or replace the integration directory with the new release), restart Home Assistant, then install the matching signed Android APK from the release page. Keep both sides updated together, especially when a release documents a device protocol change. Back up your current installation before manual file replacement.

## Troubleshooting

- **Home Assistant Core upgrade required:** HAMediaConnect will not start on Core versions older than 2026.10.0, including manual installations. Check **Settings → System → Repairs** for the version error, upgrade Home Assistant Core, and restart it. The repair clears automatically after the upgrade.
- **No HAMediaConnect integration in Add integration:** Confirm the integration is installed under `custom_components/hamediaconnect/`, restart Home Assistant, then refresh the browser.
- **No media player entity:** Check that the integration finished setup and the Android app reports **Connected**. Review Home Assistant logs and the app's connection status.
- **Device registration or reauthentication fails after upgrading:** Update both components to compatible versions, sign in again, and keep the original Android device key. If the key was lost, an administrator must revoke its old registration before the app identity is reset.
- **HTTP connection is refused:** Configure an HTTPS Home Assistant endpoint with a valid certificate, update the saved URL, and sign in again. Plain HTTP control is no longer supported. HTTP audio URLs remain supported.
- **No sound or TTS:** On Android 17+, open HAMediaConnect and tap **Enable background audio** if shown, especially after reboot. Check Android media volume, mute, Do Not Disturb, and the selected output device. For TTS, read the player's `delivery_phase`, `delivery_reason` and `delivery_method` attributes. `suppressed/unavailable` means background audio needs enabling; `received`/`retrieving` followed by `failed` means delivery or playback failed. `playing` then `completed` reports decoder playback, which cannot establish that sound was audible. For non-TTS media, confirm the media URL is reachable from the phone. See [TTS delivery troubleshooting](docs/diagnostics/TTS_DELIVERY.md#troubleshooting-connection-retrieval-or-playback).
- **Player missing from Music Assistant:** Select the entity in Music Assistant's **Home Assistant Media Players** provider settings.

## Documentation

- [Home Assistant integration installation notes](docs/HA_INTEGRATION_INSTALL.md)
- [Developer guide](docs/DEVELOPERS.md)
- [Phase 2 authentication and transport design](docs/SECURITY_PHASE_2.md)
- [HACS submission readiness and checklist](docs/HACS_SUBMISSION.md)
- [Privacy policy](https://emarrero-personal.github.io/HAMediaConnect/) ([source](PRIVACY.md))
- [Third-party licenses and attribution](THIRD_PARTY_NOTICES.md)

## License

HAMediaConnect source code is licensed under **GNU GPL version 3 or any later version** (`GPL-3.0-or-later`). See [LICENSE](LICENSE). Third-party dependencies retain their own license terms; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The approved project icon source is maintained in [branding/HAMediaConnect.svg](branding/HAMediaConnect.svg); generated Android and Home Assistant assets are derivatives of that artwork.

## Local and remote media delivery

On Android 17 or newer, grant **Local network** access when connecting the app.
If denied, enable it in Android's app permissions before using LAN media or LAN
Home Assistant control. Chrome has separate permissions; a working browser GET
does not prove the app can load the same audio.

Home Assistant TTS doesn't need a phone-reachable media URL. When the app and
integration are both this version, the integration renders the speech and the app
downloads it over its existing authenticated connection. Announcements therefore
work wherever the app shows **Connected**, including on cellular, and an in-progress
download resumes after a Wi-Fi/cellular switch. The rest of this section applies to
music and other non-TTS media, and to TTS with older app versions.

For playback away from home, enable Home Assistant Cloud remote access or configure
a valid external **HTTPS** URL in Home Assistant's network settings. Configure the
app's Home Assistant connection with that reachable HTTPS origin and authenticate
there once. Use this same origin on Wi-Fi and cellular. The integration prefers
HA's Cloud HTTPS endpoint when available, then a configured external HTTPS endpoint for HA-owned media, including TTS. It keeps local
media when no external HTTPS endpoint is configured. A local-only HTTP connection
cannot reconnect from cellular; do not expose port 8123 publicly.

The external endpoint must serve the actual `/api/tts_proxy/` and other media
paths. Reverse-proxy login pages, extra proxy authentication, disabled Cloud
remote access, expired media, or missing media routes can prevent playback even
when the WebSocket connects. The Android app reports sanitized playback errors in
its status and `HARemotePlayback` logs, and stops continuous buffering after
30 seconds. Regenerate expired TTS after restoring connectivity. Media redirects
are allowed only within the same origin, up to five hops. Cross-origin redirects
and HTTPS downgrades are refused; use a direct audio endpoint in those cases.
When an announcement fails and music is restored, Home Assistant receives the
restored music state and, separately, the announcement's `failed` delivery report with
a sanitized reason (see the player's `delivery_*` attributes and `hamediaconnect_playback` events).

See [TTS delivery](docs/diagnostics/TTS_DELIVERY.md) for the current architecture and
physical acceptance tests, and the earlier [TTS investigation](docs/diagnostics/TTS_PLAYBACK.md)
for the history.

For the v1.0.1 corrections, sanitized Playback diagnostics, and the pending Pixel 10
acceptance checklist, see [device corrections](docs/diagnostics/V1_0_1_FINAL_CORRECTIONS.md).

Network changes retry immediately with the saved credentials and device key.
Temporary transport, server and subscription failures back off automatically; confirmed
authentication failures require correcting the credentials or registration in Settings,
then Save and Connect. Idle Playback shows whether it is waiting for a connection;
currently playing audio and playback failures remain visible during reconnection.
