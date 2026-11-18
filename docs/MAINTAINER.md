# HAMediaConnect — Maintainer handbook

Use this handbook when testing changes, preparing a release, or maintaining the integration. Installation instructions are in the [README](../README.md#installation). This checklist targets **1.0.8 (unreleased)**. Before releasing, check the versions in the code and the published tag; the version written here doesn’t change the build.

## Release management

1. Review the client app and Home Assistant integration together; check that their version strings match and Android `versionCode` increases.
2. Make sure Python/HA, Android, HACS, Hassfest, security coverage, and device instrumentation checks pass. On a real device, test registration, music, TTS, mute, DND, reconnect, and boot.
3. Confirm the production-signing environment has the four secrets `ANDROID_RELEASE_KEYSTORE_BASE64`, `ANDROID_RELEASE_STORE_PASSWORD`, `ANDROID_RELEASE_KEY_ALIAS`, and `ANDROID_RELEASE_KEY_PASSWORD`. Configure required reviewers to require manual signing approval.
4. After approval, tag the reviewed main commit with `v1.0.8` and push the tag. The workflow `.github/workflows/distribution-apk.yml` checks that the tag belongs to main and matches the build version, runs tests, signs the release, verifies with `apksigner`, and publishes `HAMediaConnect.apk` and its SHA-256 checksum. Do not overwrite an existing release.
5. Check the published APK signature and checksum independently, and verify that no signing keys or secrets were included.

## HACS default catalog submission

The repository already supports custom-repository installation. For official listing, follow the current [Marketplace catalog guide](https://contributors.home-assistant.io/marketplace/guides/catalog/) and the upstream `hacs/default` PR template.

- Check that the repository is public and has its description, topics, Issues, README, and license set up. Check the root `hacs.json`, integration manifest, and directory layout too.
- Run `.github/workflows/hacs.yml` and `.github/workflows/hassfest.yml` on the release candidate and make sure both pass. Check that the latest published GitHub Release points to the approved version.
- Ship complete custom-integration UI translations in `custom_components/hamediaconnect/translations/en.json`; `strings.json` alone is not loaded for custom integrations.
- Check Home Assistant local brand assets under `custom_components/hamediaconnect/brand/` and current Home Assistant brand guidance.
- After approval, fork `hacs/default`, add `"emarrero-personal/HAMediaConnect"` to the alphabetized root `integration` list, and open a PR against its default branch. Include passing validation links and the latest release. Do not open an external submission PR without approval.
- The Android APK is distributed through GitHub Releases, **not** through HACS.

## Development and test checklist

- Android Studio / JDK 17; run `./gradlew detekt testDebugUnitTest appCodeCoverageReport verifyAppCodeCoverage securityCodeCoverageReport verifySecurityCodeCoverage assembleDebug`.
- Python: `python -m pytest tests/ --cov=custom_components/hamediaconnect --cov-branch --cov-fail-under=85`.
- Run `connectedDebugAndroidTest securityDeviceCoverageReport` with a disposable emulator when available. Keystore reset tests delete keys, so never run them on an installation with credentials you need to keep.
- Install the integration on a test HA instance, restart HA, configure it in Devices & services, and connect the paired client app.
- Verify play, pause, stop, volume, mute, HTTP/HTTPS media, TTS announcement/resume, DND, disconnect availability, restart recovery, Bluetooth and lock-screen controls.
- Test OAuth PKCE with a reachable HTTPS client identification endpoint; check state mismatch, expired callback, refresh, token revocation, and no credentials in logs.
- Require HTTPS/WSS for HA control, including long-lived tokens. Verify an old HTTP URL and saved insecure-HTTP preference cannot connect after upgrade. Verify local HTTP media still plays.
- For OAuth client metadata, confirm `/api/hamediaconnect/oauth-client` is reachable from the HA host as well as Android and advertises `haandroidmediaplayer://oauth/callback`.
- Check [security architecture](SECURITY_PHASE_2.md) and [developer guide](DEVELOPERS.md) before protocol/security changes.

## Operational recovery

- Back up the installed integration before changing it; restart Home Assistant after replacing Python modules.
- If the device key is lost, use **Reset device identity** in Android Settings and register again. Revoke the old fingerprint in HA first when appropriate. Revoking the device identity does not revoke the HA user token.
- If the app can’t decrypt its saved credentials, use **Forget Home Assistant credentials**, then sign in again.
- Do not weaken TLS verification, OAuth callback validation, device proof, rate limits, or coverage gates to resolve test failures.
