# OAuth implementation testing notes (v0.2 work in progress)

The Android client currently implements browser authorization with PKCE S256, a custom-scheme callback, encrypted access/refresh-token storage using Android Keystore AES-GCM, and refresh before reconnect. It accepts HTTPS Home Assistant addresses, including Nabu Casa cloud URLs.

**Not yet verified against a live Home Assistant instance.** The client identifier currently points to the GitHub repository URL; a conforming public HTTPS client-identifier page with the required redirect metadata must be published and verified before relying on this for production. Confirm that the installed Home Assistant release accepts PKCE and the selected redirect scheme; do not assume a successful exchange until tested.

## Test checklist
1. Install the branch build on an Android test device; enter the `https://...ui.nabu.casa` URL.
2. Tap **Sign in with Home Assistant (OAuth)**. Verify external browser login, return to the app, and success status.
3. Verify OAuth state mismatch, denied authorization, timeout, and invalid callback all fail safely.
4. Verify WebSocket authentication and command subscription as a non-admin account. **The v0.1 event subscription may still return unauthorized** until the custom integration replaces it.
5. Expire the access token and confirm refresh works without logging secrets.
6. Test Wi-Fi-to-cellular transition, HA restart, token revocation, and Android process restart.
7. Validate remote media/TTS URLs independently; the WSS connection alone does not make local HTTP media accessible.

## Known gaps
- Android app still displays the legacy token field and retains v0.1 token fallback for migration.
- OAuth authorization pending state/verifier are stored temporarily in app-private preferences; move them to hardened transient storage if threat model requires it.
- Keystore credentials cannot be restored from Android backup without the key; add explicit backup exclusions.
- HTTPS-only enforcement is currently limited to the OAuth path; v0.1 HTTP compatibility is still enabled.
- No integration-specific WebSocket protocol or device authorization yet.
- No Gradle compile, emulator test, or Home Assistant integration test has been performed for these commits.
