# Contributing

Read [AGENTS.md](AGENTS.md), the [developer guide](docs/DEVELOPERS.md), and the
[maintainer handbook](docs/MAINTAINER.md) before making significant changes.
Start a focused branch from current `main` and explain the problem, resulting
behavior, and validation in your pull request.

Report ordinary bugs through GitHub Issues. Include client app and integration
versions, Android and Home Assistant versions, reproduction steps, and expected
and actual behavior. Redact credentials, signed media URLs, and personal data.
Report vulnerabilities privately using [SECURITY.md](SECURITY.md).

Run the checks in AGENTS.md relevant to the change, including the existing 85%
coverage gates. Run `./gradlew detekt` for Kotlin changes. Clearly identify checks
and real-device tests that were not run. Protocol changes require coordinated
client app and Home Assistant updates and negative security tests.

Do not submit signing keys, credentials, local configuration, generated build
outputs, or unrelated formatting changes. Contributions are under the project's
[GPL-3.0-or-later license](LICENSE); third-party code retains its original terms.
