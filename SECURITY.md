# Security policy

Keep the client app and Home Assistant integration updated together. Security fixes
target the latest release; older releases are not maintained separately.

## Report a vulnerability

Use [GitHub private vulnerability reporting](https://github.com/emarrero-personal/HAMediaConnect/security/advisories/new).
Do not open a public issue with vulnerability details or working exploits.

Include the affected versions, impact, prerequisites, and steps to reproduce with
test credentials. Remove access tokens, authorization codes, private keys, signed
media URLs, and personal information from logs and screenshots. Never send a
production signing key or Android Keystore private key.

The maintainer will review the report and coordinate a fix and disclosure through
the private advisory. No response-time commitment is currently offered.

For the authentication design and security boundaries, see
[the security architecture](docs/SECURITY_PHASE_2.md).
