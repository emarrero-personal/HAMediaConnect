# Home Assistant integration branding

The `hamediaconnect` icon uses the project mascot in [icon.svg](icon.svg), also shown in [the README](../README.md). The 256 × 256 and 512 × 512 PNGs shipped with the integration are in `custom_components/hamediaconnect/brand/`.

Home Assistant 2026.3 and newer loads custom integration artwork from that local `brand/` directory. No separate PR to `home-assistant/brands` is required for this custom integration. The `hamediaconnect` directory name matches the integration domain in `manifest.json`.

The mascot was generated for this project and is included here by the project maintainer. Its presence does not claim a registered trademark or exclusive rights. The Home Assistant brand assets must use project artwork and must not use Home Assistant's own marks.

The `icon.png` is 256 × 256 and `icon@2x.png` is 512 × 512. Regenerate them from this SVG with FFmpeg and its SVG renderer:

```sh
ffmpeg -hide_banner -loglevel error -i branding/icon.svg -frames:v 1 -vf scale=256:256 custom_components/hamediaconnect/brand/icon.png
ffmpeg -hide_banner -loglevel error -i branding/icon.svg -frames:v 1 -vf scale=512:512 'custom_components/hamediaconnect/brand/icon@2x.png'
```

HACS may continue to show its generic repository icon while its frontend does not load local integration artwork; this does not affect the integration icon shown by supported Home Assistant versions.
