# Google Play release readiness

HAMediaConnect consists of **two components**: the Android application and the Home Assistant custom integration. Installing the Play Store app alone does not create a Home Assistant media player; users must also install the integration.

## Verified from Android Gradle configuration (2026-11-08)

- Application ID: `us.marreros.haandroidmediaplayer` (do not change after publishing).
- Android minSdk 29, targetSdk 37, compileSdk 37.
- Current Android versionCode 18, versionName 1.0.7 at review.
- Release signing reads `HAMEDIACONNECT_KEYSTORE_PATH`, `HAMEDIACONNECT_KEYSTORE_PASSWORD`, `HAMEDIACONNECT_KEY_ALIAS`, and `HAMEDIACONNECT_KEY_PASSWORD` from environment.
- Existing signed APK distribution must remain supported.

## Release gates

- [ ] Confirm developer account identity and account type in Play Console.
- [ ] Confirm unique package name and existing Play app registration before first upload.
- [ ] Run `./gradlew :app:bundleRelease` with signing secrets available; inspect `app/build/outputs/bundle/release/`.
- [ ] Validate signing, upload key, Play App Signing enrollment, and certificate compatibility with GitHub-distributed APKs. **Do not assume cross-store updates work**; different signing certificates may require uninstall/reinstall.
- [ ] Confirm versionCode is greater than all previously uploaded versions.
- [ ] Confirm target SDK and Play policy requirements at submission time.
- [ ] Test release AAB on physical Android devices using bundletool / Play internal testing, including login, reconnect, background playback, TTS interruption/resume, notifications, DND, and Music Assistant.
- [ ] Verify app permissions and Android foreground-service declarations against actual manifest and runtime behavior.
- [ ] Review data collection, transmission, retention, encryption, and third-party SDKs before completing Data Safety. Home Assistant URLs, tokens, device identity and media URLs deserve special attention.
- [ ] Publish accessible privacy policy URL and support contact; obtain legal/privacy review of draft.
- [ ] Prepare Play Console app access instructions (self-hosted Home Assistant integration and test server / credentials if reviewers need them).
- [ ] Complete content rating, target audience, ads declaration, data safety and app content forms.
- [ ] Provide screenshots from the actual app, a 512x512 icon and 1024x500 feature graphic; avoid misleading screenshots or unofficial affiliation claims.
- [ ] Complete required testing and production-access steps applicable to the developer account.
- [ ] Establish staged rollout and rollback/incident procedure.

## Automation proposal

1. CI builds and tests an unsigned or CI-signed AAB on PRs without Play credentials.
2. An approved, manually dispatched workflow uploads the signed AAB to **internal** testing only.
3. Use a Google Play service account scoped to this app, encrypted Actions secrets, minimal permissions, and a protected GitHub environment.
4. Production promotion remains manual and gated by Play Console review.

Never commit keystores, passwords, service-account JSON, access tokens or tester credentials. Preserve the signed APK GitHub release path.

## Ownership / decisions

- Developer account: **not yet verified**.
- Privacy-policy publication URL: **TBD**.
- Support email: **TBD**.
- App signing strategy: **TBD after checking current distribution certificate**.
- Store assets and screenshots: **pending**.
- Play API credentials: **not configured**.
