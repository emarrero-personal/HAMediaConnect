# HACS installation and catalog readiness

HAMediaConnect can be installed as a custom HACS repository; see [installation instructions](HA_INTEGRATION_INSTALL.md). Official catalog inclusion is pending.

HACS installs only `custom_components/hamediaconnect/`. Root `hacs.json` sets `content_in_root` to `false`, and no `zip_release` is configured. Android sources, Gradle files, APKs, signing configuration and development tooling are outside the installed directory. The companion APK is distributed separately through GitHub Releases.

## Current requirements

Use the [Marketplace catalog guide](https://contributors.home-assistant.io/marketplace/guides/catalog/) and the current [hacs/default PR template](https://github.com/hacs/default/blob/master/.github/PULL_REQUEST_TEMPLATE.md). Marketplace and HACS share that catalog. Its default branch was verified as `master` on 2026-11-05; check again before submitting.

The older HACS pages say releases are optional and require an upstream brands entry. For catalog submission, follow the current guide and template: publish a full release after validation succeeds; local `brand/icon.png` is accepted. The [brands repository](https://github.com/home-assistant/brands#image-specification) confirms that custom integrations can ship local images. A separate brands PR is unnecessary here.

Only the owner or a major contributor may submit. The repository must be public, maintained, unarchived, licensed, and have a description, topics, issues enabled, README, valid metadata, a distinct integration domain, and passing HACS and Hassfest actions without ignored checks. There is no documented minimum star count, repository age, or companion APK asset requirement. Manifest categories are optional; their absence is a warning.

## Audit on 2026-11-05

- Public, unarchived, owner `emarrero-personal`; description, topics and Issues enabled.
- GitHub recognizes GPL-3.0; README specifies GPL-3.0-or-later and the full GPLv3 text is present.
- README covers purpose, Android requirements, installation, configuration, updates and troubleshooting.
- Root `hacs.json` and integration manifest contain required metadata. Exactly one integration directory exists.
- Local brand icons are PNG, 256×256 and 512×512, derived from the approved project artwork.
- HACS and Hassfest passed without disabled checks. HACS reports an optional missing-categories warning.
- The latest published release at audit time is [v1.0.4](https://github.com/emarrero-personal/HAMediaConnect/releases/tag/v1.0.4). Its annotated tag resolves to `4530a445c56d732a0324c208e7c2789b274c1952`; the release API's `target_commitish` is not the authoritative tag target. Android and integration versions at that tag both equal 1.0.4.
- The original [v1.0.1](https://github.com/emarrero-personal/HAMediaConnect/releases/tag/v1.0.1) tag resolves to `876e251dca95e662a04833005d5dc5c847155788`, with both versions 1.0.1. It remains unchanged.
- Neither v1.0.1 nor v1.0.4 has APK assets. [PR #26](https://github.com/emarrero-personal/HAMediaConnect/pull/26) is merged. The [later signed-distribution attempt](https://github.com/emarrero-personal/HAMediaConnect/actions/runs/42063177197) passed release tests but failed before any signing-job steps. APK publication is a separate maintainer task; do not weaken signing or overwrite immutable releases.
- No existing HAMediaConnect catalog entry or submission PR was found at audit time; repeat these searches before submission.

## Release dependency

**Do not open the catalog PR yet.** Published v1.0.4 and main only shipped `strings.json`. [Custom integrations must ship finished translations](https://contributors.home-assistant.io/marketplace/guides/integrations/#translations) in `translations/en.json`; Home Assistant does not load their `strings.json` as translations.

The submission preparation adds the English setup and abort messages to the installable integration and prepares coordinated version **1.0.5**, Android versionCode **16**. It changes no protocol, domain, entity identifiers, authentication, or playback logic.

After review and merge, require successful Python, Android (including security coverage and emulator instrumentation), HACS and Hassfest runs on the exact release commit. With separate owner authorization, publish a new full v1.0.5 release after those checks finish. Do not move or replace existing tags/releases. Verify the release archive includes `translations/en.json` and local brand assets. Check the setup text on a real Home Assistant installation; this was not tested locally against a running Home Assistant instance.

## Verified release-source CI evidence

These successful runs are for the existing v1.0.4 tag's exact source commit, not evidence for the unreleased 1.0.5 changes:

| Workflow | Successful run |
|---|---|
| HACS validation (no `ignore`) | [42059920942](https://github.com/emarrero-personal/HAMediaConnect/actions/runs/42059920942) |
| Hassfest validation | [42059921103](https://github.com/emarrero-personal/HAMediaConnect/actions/runs/42059921103) |
| Validate Home Assistant integration | [42059921158](https://github.com/emarrero-personal/HAMediaConnect/actions/runs/42059921158) |
| Build HAMediaConnect APK (JVM coverage and emulator security tests) | [42059920926](https://github.com/emarrero-personal/HAMediaConnect/actions/runs/42059920926) |

These runs finished before the full v1.0.4 release was published at 2026-11-05 16:50:10 UTC. No separate dependency-vulnerability workflow was found; Hassfest validates integration requirements, while Android CI tests security coverage and Keystore/service instrumentation. Do not interpret these as a complete dependency vulnerability audit or real-device playback smoke test.

## Catalog submission after release

1. Recheck catalog, removal lists and all open/closed submission PRs to avoid duplicates.
2. Fork `hacs/default` to the owner's personal account and create a branch from the latest upstream default branch.
3. Add only `emarrero-personal/HAMediaConnect` to the case-insensitively alphabetized root `integration` JSON list. Preserve its formatting.
4. Run upstream JSON/schema and `scripts/is_sorted.py` checks; verify domain conflicts, ownership and repository metadata.
5. Use title **Add HAMediaConnect to the HACS integration catalog**. Copy the current upstream template unchanged, complete its checkboxes only after verification, and supply the newly published release and successful HACS/Hassfest links for its source commit.
6. Explain concisely that this independent community integration exposes Android phones/tablets as native media players, with playback/state controls, authenticated WebSocket communication, OAuth, TTS interruption/restoration, reconnection and diagnostics. HACS installs only the integration; the Android application is separate.
7. Enable maintainer edits. Do not request reviewers. Inspect actual upstream checks, address actionable failures, and await maintainer review. Opening a PR is not acceptance.
