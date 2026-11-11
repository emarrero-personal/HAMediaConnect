# HAMediaConnect Privacy Policy

Effective date: November 11, 2026

## Our commitment

The whole idea behind Home Assistant is to keep your data. We at emarrero-personal don't want your data. We don't want to collect, keep, or sell it.

HAMediaConnect is developed by emarrero-personal. We do not operate an app telemetry or account service, receive your normal app activity, or sell your data. The app has no advertising, analytics, or crash-reporting service. The processing below lets your device work with the Home Assistant instance you choose.

## What stays on your device

The Android client app stores your Home Assistant URL, device name, connection preferences, and connection status locally. Access tokens, refresh tokens, and temporary sign-in information are encrypted with an Android Keystore key. A separate installation key proves your device's identity; its private key is not exported. Android backups and device transfer of app data are disabled. Audio transferred from Home Assistant is cached temporarily for playback and cleaned up after use or during cache maintenance.

## What your device sends

The app sends authentication credentials to your chosen Home Assistant instance to sign in. It sends its public key, device fingerprint, device name (initially the Android model), app version, playback status, volume, position, and media metadata to that instance. The companion integration stores device identity, owner binding, name, and revocation records in your Home Assistant configuration. Commands and audio travel between your instance and your device, including while the app runs in the background to receive playback commands.

Audio URLs are requested from the media hosts you or Home Assistant select. Those hosts receive normal network information such as your IP address and the requested URL. Home Assistant, hosting providers, sign-in pages, and any media or TTS providers you configure operate under their own privacy practices. These requests do not pass through an emarrero-personal service.

## Security and your controls

Updated clients require HTTPS/WSS for Home Assistant sign-in and control and validate server certificates. Older releases offered an optional unencrypted HTTP control mode; upgrade and use HTTPS to protect credentials in transit. HTTP audio URLs remain supported and are unencrypted; URLs may contain media access signatures. The app does not attach your Home Assistant bearer token to media downloads.

You can stop the connection and playback in the app, turn off start-on-boot in Settings, and use Forget Home Assistant credentials to erase saved credentials. Uninstalling removes local app data. Removing app credentials does not delete Home Assistant records or revoke tokens on the server. Revoke tokens in your Home Assistant profile and use the companion integration's device removal/revocation controls to manage server records. Device revocation records are retained in your Home Assistant configuration to prevent re-enrollment of blocked keys; backups may retain older records. You control that instance and its backups. HAMediaConnect does not create a separate developer-hosted user account.

## Policy page and support

This public policy is hosted on GitHub Pages, whose provider may process visitor network information under its own privacy statement (https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).

For privacy questions, open an issue at https://github.com/emarrero-personal/HAMediaConnect/issues. Issues are public: do not include tokens, private URLs, or other sensitive information. If you voluntarily send support information, we receive what you submit; it may remain in GitHub's issue history until removed under GitHub's controls and retention practices. This is separate from normal app operation. Material changes to this policy will be published here with an updated effective date.
