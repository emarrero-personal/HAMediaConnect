# HAMediaConnect — Maintainer handbook

This document is for project maintainers, release engineers, and contributors with repository access. For user installation see [README](../README.md) and [integration installation](HA_INTEGRATION_INSTALL.md). The supported release target for this documentation is **1.0.5 (unreleased)**. Verify the code and published tag before release; documentation alone does not change runtime versions.

## Release management

1. Review the client app and Home Assistant integration together; ensure their version strings agree and Android `versionCode` increases.
2. Require green Python/HA, Android, HACS, Hassfest, security-coverage and device-instrumentation CI. Test real device enrollment, music, TTS, mute, DND, reconnect, and boot.
3. Confirm the production-signing environment has the four secrets `ANDROID_RELEASE_KEYSTORE_BASE64`, `ANDROID_RELEASE_STORE_PASSWORD`, `ANDROID_RELEASE_KEY_ALIAS`, and `ANDROID_RELEASE_KEY_PASSWORD`. Configure required reviewers to require manual signing approval.
4. After approval, tag the reviewed main commit with `v1.0.5` and push the tag. The workflow `.github/workflows/distribution-apk.yml` validates tag ancestry and version, runs tests, signs the release, verifies with `apksigner`, and publishes `HAMediaConnect.apk` and its SHA-256 checksum. Do not overwrite an existing release.
5. Check the published APK signature and checksum independently, and verify that no signing keys or secrets were included.

## HACS default catalog submission

The repository already supports custom-repository installation. For official listing, follow the current [Marketplace catalog guide](https://contributors.home-assistant.io/marketplace/guides/catalog/) and the upstream `hacs/default` PR template. See [submission readiness](HACS_SUBMISSION.md) for the audit and release dependency.

- Verify public repository, description/topics, Issues, README, license, root `hacs.json`, manifest, and integration layout.
- Require passing `.github/workflows/hacs.yml` and `.github/workflows/hassfest.yml` runs on the release candidate. Verify the latest published GitHub Release points to the approved version.
- Ship complete custom-integration UI translations in `custom_components/hamediaconnect/translations/en.json`; `strings.json` alone is not loaded for custom integrations.
- Check Home Assistant local brand assets under `custom_components/hamediaconnect/brand/` and current Home Assistant brand guidance.
- After approval, fork `hacs/default`, add `"emarrero-personal/HAMediaConnect"` to the alphabetized root `integration` list, and open a PR against its default branch. Include passing validation links and the latest release. Do not open an external submission PR without approval.
- The Android APK is distributed through GitHub Releases, **not** through HACS.

### Suggested HACS submission description

HAMediaConnect pairs a Home Assistant custom integration with a client app (currently available for Android), exposing Android phones and tablets as native media players for music and TTS announcements.

Repository: https://github.com/emarrero-personal/HAMediaConnect

## Development and test checklist

- Android Studio / JDK 17; run `./gradlew detekt testDebugUnitTest appCodeCoverageReport verifyAppCodeCoverage securityCodeCoverageReport verifySecurityCodeCoverage assembleDebug`.
- Python: `python -m pytest tests/ --cov=custom_components/hamediaconnect --cov-branch --cov-fail-under=85`.
- Run `connectedDebugAndroidTest securityDeviceCoverageReport` with a disposable emulator when available. Never run destructive Keystore reset tests on a real installation containing credentials.
- Install the integration on a test HA instance, restart HA, configure it in Devices & services, and connect the paired client app.
- Verify play, pause, stop, volume, mute, HTTP/HTTPS media, TTS announcement/resume, DND, disconnect availability, restart recovery, Bluetooth and lock-screen controls.
- Test OAuth PKCE with a reachable HTTPS client identification endpoint; check state mismatch, expired callback, refresh, token revocation, and no credentials in logs.
- For local HTTP-only HA, use the explicitly opt-in insecure HTTP setting with a least-privilege long-lived token on a trusted LAN; never expose it publicly.
- For OAuth client metadata, confirm `/api/hamediaconnect/oauth-client` is reachable from the HA host as well as Android and advertises `haandroidmediaplayer://oauth/callback`.
- Check [security architecture](SECURITY_PHASE_2.md) and [developer guide](DEVELOPERS.md) before protocol/security changes.

## Operational recovery

- Back up the installed integration before changing it; restart Home Assistant after replacing Python modules.
- If identity keys are lost, explicitly reset device identity in Android settings and re-enroll; revoke the old fingerprint in HA first when appropriate. Revoking the device identity does not revoke the HA user token.
- If encrypted credentials are invalidated, use the separate Forget Home Assistant credentials action, then sign in again.
- Do not weaken TLS verification, OAuth callback validation, device proof, rate limits, or coverage gates to resolve test failures.

## Documentation history

Pre-release implementation plans, obsolete event-bus protocols, and old test checkpoints were retired from the current docs tree. They remain available in Git history, but must not be used as current setup instructions. The canonical supported transport is the authenticated native WebSocket protocol documented in [DEVELOPERS.md](DEVELOPERS.md) and [SECURITY_PHASE_2.md](SECURITY_PHASE_2.md).

## Public repository security

See [the repository settings audit](REPOSITORY_SETTINGS.md) for the verified GitHub
security settings, remaining protection gaps, and the order for enabling required CI.
