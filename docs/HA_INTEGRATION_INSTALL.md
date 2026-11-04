# Install the Home Assistant integration

HAMediaConnect has one custom integration in `custom_components/hamediaconnect/`. Install that directory through HACS or copy it manually, restart Home Assistant, then add **HAMediaConnect** from **Settings → Devices & services → Add integration**.

The integration and Android app work together. For version 1.0.0, update both sides and sign in again if migrating from an installation without device-key enrollment because device enrollment now uses a per-installation Android Keystore key. See the [README](../README.md) for the complete installation, connection, and update steps and the [Phase 2 security design](SECURITY_PHASE_2.md) for enrollment, lost-key recovery, and revocation.

The repository uses the standard HACS layout: root `hacs.json` and a single integration under `custom_components/hamediaconnect/`. Its `content_in_root` value is `false`. HACS validation and Hassfest run in GitHub Actions; official catalog inclusion has not yet been submitted.
