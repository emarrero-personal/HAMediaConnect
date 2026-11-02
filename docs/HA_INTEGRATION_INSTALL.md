# Home Assistant custom integration — OAuth bootstrap

**Development branch:** `feature/v0.2-oauth-media-player`

This milestone adds only the publicly readable OAuth client identification page and a UI setup flow. No Android changes are required for this installation step, and no media player entity is created yet.

## Install this development version manually

Because this repository is currently private and the integration exists on a feature branch, manual installation is the most reliable test path:

1. From the feature branch, copy the entire `custom_components/ha_remote_media_player` directory into `/config/custom_components/ha_remote_media_player` on Home Assistant. Copy all files, not just `oauth_view.py`.
2. If you previously added `ha_remote_media_player:` to `configuration.yaml`, remove it. UI configuration does not need this YAML entry.
3. Restart Home Assistant.
4. In **Settings → Devices & services → Add integration**, search for **HA Remote Media Player** and complete its single setup screen.
5. Open `https://YOUR-INSTANCE.ui.nabu.casa/api/ha_remote_media_player/oauth-client` in a private browser window. Expect HTTP 200 and an HTML page titled **HA Remote Media Player OAuth Client**, without a Home Assistant login prompt.
6. Inspect page source for `<link rel="redirect_uri" href="haandroidmediaplayer://oauth/callback">`.

The URL is a **public endpoint by design** and contains no secrets. If it returns 404, check HA logs for errors loading `ha_remote_media_player`. If it redirects to authentication, the Nabu Casa routing/public access assumptions need further investigation.

## HACS

The repository contains root-level `hacs.json` and the conventional `custom_components/ha_remote_media_player` layout. Once released on an accessible branch/repository, it can be added to HACS as a custom integration repository. HACS distribution and validation have **not** been tested; do not assume the private development branch is installable through HACS.

## Test scope

This only verifies that the integration loads and serves the OAuth client page. OAuth redirect acceptance and code exchange must be tested separately. The Home Assistant server may need to fetch its own Nabu Casa hostname for client-ID verification; that is not yet proven.

Do not deploy Android changes or replace working v0.1 credentials as part of this milestone.
