# Home Assistant integration branding

The canonical mascot for the `ha_remote_media_player` integration is [icon.svg](icon.svg), copied from [docs/mascot.svg](../docs/mascot.svg).

The Home Assistant integration picker does **not** load icons directly from this repository. Its official artwork is served by the [Home Assistant Brands repository](https://github.com/home-assistant/brands).

To publish the icon, render this SVG as a **256 × 256 PNG** with its square background preserved and submit it to the Brands repository under:

```
custom_integrations/ha_remote_media_player/icon.png
```

The Brands maintainers must accept the contribution before Home Assistant will show the custom icon. The domain must match `custom_components/ha_remote_media_player/manifest.json`. Until then, Home Assistant may display a generic integration icon.

For HACS users, this repository's `hacs.json` and integration manifest remain separate from the Brands artwork. Do not claim the icon is live before the upstream Brands PR has merged and propagated.
