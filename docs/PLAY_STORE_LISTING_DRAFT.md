# HAMediaConnect — Google Play listing draft

**App name:** HAMediaConnect

**Short description (draft):** Turn your Android device into a Home Assistant media player.

## Full description

HAMediaConnect lets you use an Android phone or tablet as a media player controlled by Home Assistant.

Play audio, control playback and volume, and receive Home Assistant text-to-speech announcements. Use Music Assistant with Home Assistant's Media Players provider to select your Android device as a playback destination.

**Setup required:** HAMediaConnect also requires its free Home Assistant custom integration, installed separately. Home Assistant 2026.10 or newer is required. Follow the setup guide at https://github.com/emarrero-personal/HAMediaConnect.

Features:
- Home Assistant media player entity for an Android device
- Playback and volume controls
- TTS announcements with interruption and resumption
- Music Assistant integration through Home Assistant
- OAuth sign-in over HTTPS or advanced local token setup

HAMediaConnect is an independent, open-source project and is not affiliated with or endorsed by the Home Assistant project.

Source code and support: https://github.com/emarrero-personal/HAMediaConnect

## Reviewer notes (draft)

The app requires a Home Assistant server with the HAMediaConnect custom integration installed. Reviewers need an accessible test environment to exercise sign-in and playback; prepare one before submission. Do not publish test credentials in this repository.

## Assets to produce

- App icon, 512 x 512 PNG, based on existing project branding
- Feature graphic, 1024 x 500 PNG
- At least two genuine Android screenshots (connection/setup and media controls)
- Privacy policy hosted at a stable public HTTPS URL
- Support contact email (owner to provide)

**Review:** Verify each feature claim against the actual release build before publication.
