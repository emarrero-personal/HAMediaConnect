# Third-party software attribution

HAMediaConnect uses third-party software under licenses that permit distribution as part of a GPLv3 application. The upstream projects retain their respective copyrights and licenses. The full [Apache License 2.0](licenses/Apache-2.0.txt) is included in this repository. This attribution covers declared direct Android dependencies; the resolved transitive dependency graph and any additional upstream NOTICE requirements still require verification before claiming complete release attribution.

## Direct Android dependencies

The following attributions identify upstream projects, not copyright ownership of every included source file. Preserve upstream copyright notices and any component-specific NOTICE files.

| Dependency | Declared version | Upstream license | Upstream license / notices |
| --- | --- | --- | --- |
| Google / Android Open Source Project — AndroidX Core KTX | 1.18.0 | Apache License 2.0 | https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt |
| Google / Android Open Source Project — AndroidX AppCompat | 1.7.1 | Apache License 2.0 | https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt |
| Google / Android Open Source Project — AndroidX Activity KTX | 1.13.0 | Apache License 2.0 | https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt |
| Google / Android Open Source Project — AndroidX Browser | 1.8.0 | Apache License 2.0 | https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt |
| Google / Android Open Source Project — AndroidX Media3 ExoPlayer | 1.11.0 | Apache License 2.0 | https://github.com/androidx/media/blob/release/LICENSE |
| Google / Android Open Source Project — AndroidX Media3 Session | 1.11.0 | Apache License 2.0 | https://github.com/androidx/media/blob/release/LICENSE |
| Square and contributors — OkHttp | 4.12.0 | Apache License 2.0 | https://github.com/square/okhttp/blob/master/LICENSE.txt |

The Kotlin runtime and transitive dependencies packaged into the APK also have their own licenses. Gradle, the Android Gradle plugin, and the Kotlin compiler are build tools and should be evaluated separately from packaged runtime libraries. Apache-2.0 code can be included in GPLv3 projects; retain copyright, license text, and any upstream NOTICE files as applicable. GPL-3.0-only code cannot necessarily be relicensed as GPL-3.0-or-later.

## Release compliance tasks (owners: repository maintainer)

**Release gate:** Complete and verify the items below before distributing the next signed APK. The project license is already established by [LICENSE](LICENSE); these tasks concern dependency attribution and branding, not whether HAMediaConnect itself is GPL-licensed.

## Release checklist

- [ ] Run `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` against the release commit; retain the resolved dependency inventory with the release evidence. Review license metadata and actual license files for **every** resolved runtime dependency, not just direct dependencies.
- [x] Include the complete Apache-2.0 license text in `licenses/Apache-2.0.txt` and list the declared direct Android dependencies above.
- [ ] Check upstream NOTICE files and other required copyright/attribution notices for each resolved dependency; bundle applicable notices in release documentation or an accessible in-app licenses screen.
- [ ] Review Kotlin runtime and other packaged libraries for license obligations; separately record any relevant build-tool redistribution obligations (Gradle and Android Gradle Plugin are build tools, not necessarily shipped in the APK).
- [x] Add the full GPLv3 license text in repository-root `LICENSE` and declare `GPL-3.0-or-later` in README.
- [ ] Add and verify copyright notices for project-owned source and preserve third-party copyright statements in distributed materials.
- [ ] Document the mascot artwork's provenance and copyright owner; confirm the right to distribute it in the APK and source tree, and specify any separate permissions for logo/branding reuse.
- [ ] Produce an automated license report for the resolved release dependency graph and reconcile it with the notice bundle; archive the report as release evidence.
- [ ] Verify the source repository at the tagged release contains the complete corresponding source, build scripts, GPL text, and required notices; include a source link with the GitHub Release.

**Status:** Direct dependency attribution and full Apache-2.0 text added. Transitive dependencies, component-specific NOTICE files, and distribution-ready completeness are **not yet verified**.
