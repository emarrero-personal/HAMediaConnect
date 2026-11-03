# Self-hosted OAuth client identification (v0.2 prototype)

The Android app uses the Home Assistant instance's own HTTPS origin as its OAuth client ID host. The integration serves a public static page at:

`https://YOUR-INSTANCE.ui.nabu.casa/api/hamediaconnect/oauth-client`

The HTML advertises exactly `haandroidmediaplayer://oauth/callback` using a `<link rel="redirect_uri">` tag. Android derives its client ID from the URL entered on the login screen; there is no GitHub Pages dependency.

## Install the integration

1. Copy `custom_components/hamediaconnect/` into the Home Assistant configuration directory `/config/custom_components/hamediaconnect/`.
2. Add this to `/config/configuration.yaml`:

   ```yaml
   hamediaconnect:
   ```

3. Run **Developer Tools → YAML → Check configuration**, then restart Home Assistant.
4. From an **unauthenticated browser/private window**, open the endpoint URL above. It must return HTTP 200 and display the heading **HAMediaConnect**. View source and verify the `rel="redirect_uri"` link.
5. On the Home Assistant host, check that the *same public URL* can be fetched. The OAuth verifier must be able to reach the client ID URL; public accessibility from your phone alone is insufficient. If your HA instance cannot loop back to its Nabu Casa URL, redirect validation may fail.
6. Pull and rebuild the Android feature branch. Enter the exact Nabu Casa HTTPS origin, not a local HTTP URL. Tap **Sign in with Home Assistant (OAuth)** and start a fresh login.

## Why this is a prototype

This is a bootstrap integration registering only the static OAuth endpoint. It does **not** yet provide native media_player entities, a config flow, device registration, or WebSocket commands. The Android app still uses the v0.1 event bus after authentication, which may be denied for non-admin users.

The page is intentionally unauthenticated and returns fixed HTML only. It contains no user information or credentials. Do not add dynamic query reflection, secret values, or authenticated API calls to it.

The exact Nabu Casa routing, Home Assistant's ability to fetch its own public URL, and OAuth redirect validation must be tested on your installation. These have **not** been verified from the development environment. If the public URL cannot be fetched from HA itself, use a dedicated public HTTPS client-ID domain instead of disabling validation.

## Verification checklist

- [ ] Integration loads without errors
- [ ] Public endpoint returns HTTP 200 without login
- [ ] Home Assistant server can fetch its own client ID URL
- [ ] HTML source includes exact custom-scheme redirect declaration
- [ ] Android build succeeds
- [ ] OAuth browser authorization succeeds
- [ ] Authorization code exchange succeeds
- [ ] Refresh token works after expiry
- [ ] WebSocket authentication succeeds
- [ ] Non-admin device control works (requires later native integration work)
