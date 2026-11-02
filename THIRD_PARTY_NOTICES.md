# Third-party notices and license review

HAMediaConnect uses third-party software under licenses that permit distribution as part of a GPLv3 application. The upstream projects retain their respective copyrights and licenses. This document is not a substitute for bundling required notices in release artifacts.

## Direct Android dependencies

| Dependency | Declared version | Upstream license | Upstream license / notices |
| --- | --- | --- | --- |
| AndroidX Core KTX | 1.18.0 | Apache License 2.0 | https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt |
| AndroidX AppCompat | 1.7.1 | Apache License 2.0 | https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt |
| AndroidX Activity KTX | 1.13.0 | Apache License 2.0 | https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt |
| AndroidX Browser | 1.8.0 | Apache License 2.0 | https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt |
| AndroidX Media3 ExoPlayer | 1.11.0 | Apache License 2.0 | https://github.com/androidx/media/blob/release/LICENSE |
| AndroidX Media3 Session | 1.11.0 | Apache License 2.0 | https://github.com/androidx/media/blob/release/LICENSE |
| OkHttp | 4.12.0 | Apache License 2.0 | https://github.com/square/okhttp/blob/master/LICENSE.txt |

The Gradle Android plugin, Gradle, Kotlin compiler/runtime, and transitive dependencies also have their own licenses. Apache-2.0 code can be included in GPLv3 projects; retain copyright, license text, and any upstream NOTICE files as applicable. GPL-3.0-only code cannot necessarily be relicensed as GPL-3.0-or-later.

## Release checklist

- [ ] Generate the **resolved** runtime dependency graph from Gradle and review every transitive dependency and its published POM/license metadata.
- [ ] Bundle the complete Apache License 2.0 text and any applicable upstream NOTICE files in release documentation or the app's open-source licenses screen.
- [ ] Confirm Kotlin/Gradle/Android plugin licensing and any other build/distribution obligations.
- [ ] Include the project's complete GPLv3 license text and copyright information before public distribution.
- [ ] Review the provenance, branding rights, and permitted distribution of the Android-inspired guitar mascot separately.
- [ ] Verify the final release artifact's dependency licenses with an automated license-report task.

**Status:** Direct dependency license-family compatibility reviewed; transitive dependency inventory and distribution-ready attribution are **not yet verified**.
