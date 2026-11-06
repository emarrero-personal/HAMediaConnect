# Public repository settings review

Reviewed on 2026-11-05 against GitHub's live API and `origin/main`.
Repository: [emarrero-personal/HAMediaConnect](https://github.com/emarrero-personal/HAMediaConnect).

## Enabled and verified

- Public visibility, useful description/topics, Issues, GPL license, README, and
  deletion of merged branches were already configured. Wiki is disabled.
- Enabled Dependabot alerts and security updates. The dependency graph is active
  (GitHub also ran `update-pip-graph`).
- Enabled secret scanning, secret push protection, and private vulnerability
  reporting. Non-provider patterns and validity checks still report disabled;
  GitHub did not apply those additional options.
- Actions default token permissions are read-only, and workflows cannot approve
  pull requests. Changed fork workflow approval from first-time contributors to
  all external contributors.
- CodeQL default setup is active for Actions and Python, and both setup analyses
  passed. Kotlin autobuild failed; GitHub automatically removed Java/Kotlin from
  the configured languages. Kotlin is not currently covered by code scanning.
- Release tag protection is active for `refs/tags/v*`, with administrator and
  integration bypass actors. No release or signing settings were changed.

## Changes prepared in this branch

- Weekly Dependabot version updates for GitHub Actions, Gradle, and Python,
  with bounded PR counts and grouping of routine updates.
- Security reporting policy, contribution guide, bug-report form, and PR template.
- Detekt in the app build, `check`, and Android CI, with archived reports and an
  initial baseline of existing findings. Test coverage thresholds remain 85%.
- Immutable action SHAs and checkout credential persistence disabled in ordinary
  validation workflows. Release/signing workflows retain their existing settings.
- All validation workflows run on every PR, including metadata-only changes, so
  their checks can become mandatory without skipped workflows blocking merges.
- Ignore local environment files, signing material, and Kotlin build caches.

Dependabot version updates and the new CI behavior take effect after these files
are merged into the default branch. Dependabot security updates are enabled now.

## Remaining protection gaps

1. Fixed the active `Main` ruleset's empty branch inclusion list; it now selects
   `refs/heads/main`. Raised its coverage rule from 80% to 85% and required resolved
   review threads, preserving all other existing rules and bypass actors. Its
   required-status-check list is still empty. GitHub initially rejected the REST
   update because the response included a null `max_coverage_drop`; omitting that
   optional parameter allowed the update.
2. After the workflow changes land and their check names are confirmed, configure
   the ruleset to require `python-checks`, `android-debug-apk`,
   `Keystore and service instrumentation`, `HACS repository validation`, and
   `Home Assistant integration validation`. Require the branch to be up to date
   and review bypass actors. CodeQL checks should become required after successful
   setup has been verified.
3. Classic main protection requires one approving review and blocks force pushes
   and deletion, but allows administrator bypass. It also has `lock_branch: true`.
   Confirm whether locking the branch was intentional before changing it; a locked
   branch is read-only for normal updates. No lock or bypass settings were changed.
4. The `production-signing` environment has branch policies but no required
   reviewer, and administrators can bypass its protection. This does not meet the
   maintainer handbook's manual signing-approval requirement. Configuring a signing
   reviewer requires explicit authorization under AGENTS.md and was not changed.
5. Release workflows still reference mutable action versions. Review and pin these
   separately with authorization for changes to release/signing configuration.
   Do not enforce repository-wide SHA pinning until every workflow is compatible.
6. Kotlin CodeQL requires advanced setup with the project's Android SDK and build
   steps. Prepare an all-language advanced workflow, then switch off default setup
   when that workflow lands; default setup prevents advanced CodeQL uploads. Keep
   Actions/Python scanning active until the replacement is ready.

These findings concern repository settings and build configuration. This review
does not establish absence of vulnerabilities or replace real Home Assistant and
Android device acceptance testing.

References: [GitHub security settings](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/enabling-features-for-your-repository/managing-security-and-analysis-settings-for-your-repository),
[Dependabot security updates](https://docs.github.com/en/code-security/how-tos/secure-your-supply-chain/secure-your-dependencies/configure-security-updates),
[Detekt compatibility](https://detekt.dev/docs/introduction/compatibility/).

## Validation performed

- Android: `detekt testDebugUnitTest appCodeCoverageReport verifyAppCodeCoverage
  securityCodeCoverageReport verifySecurityCodeCoverage assembleDebug` passed.
  All 113 JVM tests passed; application line coverage was 97.78% and security line
  coverage was 93.27%.
- A temporary source with an invalid package location failed Detekt as expected.
  It was removed, and a clean Detekt run passed. `check --dry-run` confirmed that
  Gradle includes Detekt in the check lifecycle.
- Actionlint 1.7.12 passed on all four changed validation workflows. Ruby Psych
  parsed all nine YAML files. Python compileall and `git diff --check` passed.
- Python pytest, Android instrumentation, real-device smoke tests, and new remote
  CI runs for these local file changes were not performed. No production signing
  or release workflow was invoked.
