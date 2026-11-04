# HAMediaConnect branding

[`HAMediaConnect.svg`](HAMediaConnect.svg) is the approved source artwork. It was copied unchanged from the maintainer-provided `/home/emarrero/HAMediaConnect.svg`; its transparent 1536 × 1024 canvas and `#336699` fill are preserved. The artwork must not be cropped, stretched, or recolored.

The Android UI uses a vector drawable derived directly from the SVG path. Adaptive launcher icons use the same path, uniformly scaled within the Android safe zone, with a white background and a monochrome derivative for themed icons. The Home Assistant integration PNGs use transparent square canvases with the complete 3:2 source canvas centered inside.

The integration images are installed locally at `custom_components/hamediaconnect/brand/icon.png` (256 × 256) and `icon@2x.png` (512 × 512). Home Assistant 2026.3 and newer loads custom integration artwork from this directory; no external Home Assistant Brands submission is required for those versions. Older versions may show a generic icon.

The source artwork and its derivatives use project artwork and do not include Home Assistant marks. To regenerate the Home Assistant images, render `branding/HAMediaConnect.svg` at 256 × 171 and 512 × 341 with an SVG renderer, then center each transparent result on a transparent square canvas of the corresponding size. Preserve the `#336699` fill and alpha channel.

The compiled app UI was launched on an Android emulator after the icon update; see [the device screenshot](../docs/branding-preview-device.png). A composed adaptive-icon and integration-image preview is available at [branding-preview.png](../docs/branding-preview.png).
