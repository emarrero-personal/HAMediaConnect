#!/usr/bin/env bash
set -euo pipefail
VERSION=9.5.0
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl -fL "https://services.gradle.org/distributions/gradle-${VERSION}-bin.zip" -o "$TMP/gradle.zip"
unzip -q "$TMP/gradle.zip" -d "$TMP"
"$TMP/gradle-${VERSION}/bin/gradle" wrapper --gradle-version "$VERSION"
echo "Gradle wrapper installed."
