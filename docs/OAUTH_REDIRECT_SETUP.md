# Fixing "Invalid redirect URI"

Home Assistant uses IndieAuth-style OAuth client identification. A native app's custom-scheme redirect must be advertised by the public HTTPS page used as its client ID.

## Exact configuration
- Client ID: `https://emarrero-personal.github.io/HARemoteMediaPlayer/`
- Android redirect: `haandroidmediaplayer://oauth/callback`
- HTML declaration in `oauth-site/index.html`:
  `<link rel="redirect_uri" href="haandroidmediaplayer://oauth/callback">`
- Android manifest callback filter must match the URI above exactly.

## Publish the page
1. In GitHub repository **Settings → Pages**, select **GitHub Actions** as the Build and deployment source.
2. Under **Actions**, run **Publish OAuth client ID page** if it has not run automatically. The workflow publishes `oauth-site/` from the feature branch.
3. Verify `https://emarrero-personal.github.io/HARemoteMediaPlayer/` opens **without signing into GitHub** and returns HTTP 200, not a redirect.
4. Inspect the returned HTML source and verify the `link rel="redirect_uri"` is present within the first 10 KB.
5. Rebuild/reinstall the Android feature branch, then sign in again via the HTTPS Nabu Casa URL. Restart the authorization flow rather than refreshing the prior invalid request.

**Do not attempt OAuth until the public URL works.** A committed HTML file or successful Android build alone is not enough. GitHub Pages availability depends on repository/account settings and workflow deployment permissions.

## Security
The Android manifest excludes `oauth_secrets.xml` and `ha_android_media_player.xml` from cloud backup and device transfer. OAuth tokens use Android Keystore AES-GCM, and the authorization callback validates the expected state and a 10-minute timeout. Existing v0.1 manually entered tokens are still supported as a migration fallback; remove that path before production.

## Remaining validation
- GitHub Pages public availability has not been verified.
- Home Assistant login, authorization code exchange, refresh and websocket permission behavior remain untested.
- Build and emulator tests have not been executed for this branch.
