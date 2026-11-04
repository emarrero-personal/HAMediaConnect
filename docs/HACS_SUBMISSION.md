# HACS default catalog submission readiness

Status checked 2026-11-04. This document separates repository readiness from the later, maintainer-approved submission to `hacs/default`.

## Official requirements checklist

Sources: [HACS general publish requirements](https://hacs.dev/docs/publish/start/), [HACS integration requirements](https://hacs.dev/docs/publish/integration/), [HACS default inclusion procedure and checks](https://hacs.dev/docs/publish/include/), [HACS validation action](https://hacs.xyz/docs/publish/action/), [Home Assistant Hassfest action](https://github.com/home-assistant/actions#hassfest), and [Home Assistant integration brand image guidance](https://developers.home-assistant.io/docs/core/integration/brand_images/).

| Requirement | Mandatory or recommended | Status | Evidence or action |
| --- | --- | --- | --- |
| Public GitHub repository | Mandatory | Pass | `emarrero-personal/HAMediaConnect` is public. |
| Short repository description | Mandatory | Pass | GitHub description set to “Turn Android phones and tablets into native Home Assistant media players with music playback and TTS announcements.” |
| GitHub topics | Mandatory | Pass | `home-assistant`, `hacs`, `android`, `media-player`, `tts`, `music-assistant`. |
| Issues enabled | Mandatory | Pass | GitHub Issues are enabled. |
| README with usage information | Mandatory | Pass | Root `README.md` documents install, authentication, use, updates, and troubleshooting; `render_readme` is enabled. |
| Root `hacs.json` with a name | Mandatory | Pass | `hacs.json` declares `name: HAMediaConnect`. |
| Standard integration layout | Mandatory | Pass | Exactly one integration is under `custom_components/hamediaconnect/`; `content_in_root: false` correctly describes this structure. Runtime files, translations, and local brand assets are inside the integration directory. |
| Integration manifest | Mandatory | Pass | `manifest.json` keeps domain `hamediaconnect`, includes the HACS-required keys, and passes Hassfest. |
| Repository license | Mandatory for default catalog checks | Pass | Root `LICENSE` is GPL-3.0-or-later, an OSI-approved license. Third-party notices remain separately documented. |
| Not archived; owner/major contributor may submit | Mandatory | Pass | Repository is active and personally owned by `emarrero-personal`. |
| Home Assistant brand images | Mandatory for a branded integration | Pass for HA 2026.3+ | `custom_components/hamediaconnect/brand/icon.png` (256 × 256) and `icon@2x.png` (512 × 512) use the project's mascot. Current Home Assistant guidance says custom integrations ship these in their own `brand/` directory; no external Brands PR is planned. HACS's integration page still mentions the legacy Brands repository, while current Home Assistant guidance and the Brands repository document the newer local method. |
| HACS validator | Mandatory before default-catalog submission | Pass | [PR #19 HACS validation run](https://github.com/emarrero-personal/HAMediaConnect/actions/runs/41838709852). `.github/workflows/hacs.yml` uses the supported `hacs/action@main` with category `integration`. |
| Hassfest validator | Mandatory before default-catalog submission | Pass | [PR #19 Hassfest run](https://github.com/emarrero-personal/HAMediaConnect/actions/runs/41838709996). `.github/workflows/hassfest.yml` uses the official Home Assistant Hassfest action. |
| Published GitHub Release | Recommended, optional | Pass | `v0.10.0` is a published, non-draft, non-prerelease release with the signed APK and SHA-256 checksum. |
| Submission PR to `hacs/default` | Required to enter the default catalog | Not performed | Maintainer approval is still required. No external PR has been opened. |

HACS uses the latest published GitHub Release tag for its remote version when releases exist; publishing a tag without a GitHub Release is insufficient. The current `v0.10.0` release satisfies that discovery requirement. It predates the local icon and documentation changes in this branch; no app or integration runtime version bump is needed for catalog validation. If the maintainer wants the new integration icon included in a tagged archive before catalog listing, the next paired release should be Android `0.10.1` (version code 12) and integration `0.10.1`; do not publish it as part of this work.

## Validation runs

Successful validation runs for this pull request:

- [HACS validation](https://github.com/emarrero-personal/HAMediaConnect/actions/runs/41838709852) — passed.
- [Hassfest](https://github.com/emarrero-personal/HAMediaConnect/actions/runs/41838709996) — passed.
- [Python integration regression workflow](https://github.com/emarrero-personal/HAMediaConnect/actions/runs/41838709912) — passed.
- [Android build and emulator instrumentation workflow](https://github.com/emarrero-personal/HAMediaConnect/actions/runs/41838709932) — passed.

Local regression results: Python 45 tests with 96.85% coverage; Android 32 JVM/Robolectric tests with 97.12% app line coverage and 87.50% security coverage; 3 Android instrumentation tests passed on a disposable API 36 emulator. Both Android coverage gates retain their existing 85% thresholds.

## Release readiness

- [GitHub Release v0.10.0](https://github.com/emarrero-personal/HAMediaConnect/releases/tag/v0.10.0) is published.
- It contains `HAMediaConnect.apk` and `HAMediaConnect.apk.sha256`.
- The release workflow at `.github/workflows/distribution-apk.yml` verifies that the tag points to a commit on `main`, checks Android `versionName` against the tag, requires protected-environment signing secrets, runs `apksigner verify`, creates the SHA-256 file, refuses to overwrite an existing release, and removes the temporary keystore.
- The release assets are APK and checksum only; no signing key, signing password, OAuth token, or other signing material is attached. The release workflow's validation and publish job links will be recorded after checking the existing `v0.10.0` run.
- Android app and integration both declare version `0.10.0`; Android version code is 11.
- [Signed distribution workflow for v0.10.0](https://github.com/emarrero-personal/HAMediaConnect/actions/runs/41764999670) passed. Its [validation job](https://github.com/emarrero-personal/HAMediaConnect/actions/runs/41764999670/job/126361181618) and [sign-and-publish job](https://github.com/emarrero-personal/HAMediaConnect/actions/runs/41764999670/job/126362240156) both succeeded, including `apksigner verify` and removal of the temporary keystore.
- Independently downloaded `HAMediaConnect.apk` passes `apksigner verify`; `sha256sum -c HAMediaConnect.apk.sha256` reports `OK`. An archive/string scan found no keystore or private-key material in the APK.
- No new tag or release is needed for HACS readiness. No release was created or modified during this work.

## Branding

The custom integration icon is bundled in `custom_components/hamediaconnect/brand/`. Home Assistant 2026.3 and newer serves local integration brand assets from this directory. The existing square mascot was reused; the project records its provenance in `THIRD_PARTY_NOTICES.md` and does not claim registered trademark or exclusive rights. There is no external branding submission to prepare under the current Home Assistant procedure. Older Home Assistant versions may continue showing a generic integration icon.

HACS frontend versions may still show a generic repository icon even when the integration includes local artwork; this is a known HACS frontend limitation and does not prevent the integration from shipping its brand files.

## Manual steps after approval

1. Confirm the HACS and Hassfest checks below are green and review any current `hacs/default` contribution requirements.
2. Fork `hacs/default` under the maintainer's personal GitHub account (the HACS documentation requires an editable PR, not a PR from an organization).
3. Create a branch from `master` in that fork.
4. Add `"emarrero-personal/HAMediaConnect"` to the sorted JSON array in the root `integration` file. Do not change any other repository entries.
5. Run the fork's HACS default-repository validation and open a PR from that branch to `hacs/default:master`.
6. Include this repository's passing HACS and Hassfest run links, the release link, and the proposed description below. Wait for HACS maintainers and their checks; do not open duplicate PRs while the first is in the backlog.

### Proposed catalog entry

```json
"emarrero-personal/HAMediaConnect"
```

### Draft HACS submission PR description

```markdown
## HAMediaConnect integration

Adds HAMediaConnect, a Home Assistant integration that pairs with the companion Android app to expose Android phones and tablets as native media players for music playback and TTS announcements.

- Repository: https://github.com/emarrero-personal/HAMediaConnect
- HACS validation: <link to successful HACS action run>
- Hassfest validation: <link to successful Hassfest run>
- Latest release: https://github.com/emarrero-personal/HAMediaConnect/releases/tag/v0.10.0

The repository is public, has Issues enabled, a description and discovery topics, a GPL-3.0-or-later license, a root README, and a single integration in `custom_components/hamediaconnect/`. The integration bundles its local brand images for Home Assistant 2026.3 and newer.
```

No release, tag, or HACS default-catalog submission was performed as part of this readiness work.
