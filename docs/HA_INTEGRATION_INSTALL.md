# Install the Home Assistant integration

HAMediaConnect has one custom integration in `custom_components/hamediaconnect/`. Install it through [Marketplace](../README.md#with-home-assistant-marketplace-202611-or-newer) on Home Assistant 2026.11 or newer, or use [HACS](../README.md#with-hacs-home-assistant-202610) on Home Assistant 2026.10. You can also copy the integration directory manually.

After installing through HACS or manually, restart Home Assistant. With Marketplace, restart if prompted or if **HAMediaConnect** is missing from **Settings → Devices & services → Add integration**. Select **HAMediaConnect** there to finish setup.

The integration and client app work together. For version 1.0.0, update both sides and sign in again if migrating from an installation without device-key enrollment because device enrollment now uses a per-installation Android Keystore key. See the [README](../README.md) for the complete installation, connection, and update steps and the [Phase 2 security design](SECURITY_PHASE_2.md) for enrollment, lost-key recovery, and revocation.

The repository uses the standard HACS layout: root `hacs.json` and a single integration under `custom_components/hamediaconnect/`. Its `content_in_root` value is `false`. HACS validation and Hassfest run in GitHub Actions; official catalog inclusion has not yet been submitted.
