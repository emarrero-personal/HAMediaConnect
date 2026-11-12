# Google Play foreground services and reviewer access

Prepared November 11, 2026. These declarations describe the updated Android client.
Manifest declarations and copy are ready; Play Console submission and demonstration
videos are still outstanding. No app has been submitted by preparing this document.
Other store forms and Data safety are deferred until these tasks are complete.

## Foreground-service declarations

Enter both types in Play Console's App content foreground-service declaration.
Use the following text for the corresponding functionality and impact fields.
Google requires a demonstration video for each declared type; upload actual
recordings and paste their URLs in the Console. Do not submit placeholder URLs.

### Media playback — `FOREGROUND_SERVICE_MEDIA_PLAYBACK`

Functionality:

> HAMediaConnect turns the user's Android device into a Home Assistant media player. After the user connects their Home Assistant instance, they can start music or announcements through Home Assistant or their configured automations. The mediaPlayback foreground service continues audio when the app is in the background or the screen is locked. It exposes playback controls through Android's media notification and MediaSession.

Impact of deferral or interruption:

> Deferring the service delays playback requested by the user or their configured Home Assistant automation. Interrupting it cuts off music or announcements and can prevent interrupted music from resuming after an announcement. Playback must continue for the duration of the active media request, subject to the user's Stop and pause controls and Android's service lifecycle.

Use case: Media playback / background audio playback.

Recording sequence:

1. Show the app connected to a test Home Assistant instance.
2. In Home Assistant, explicitly start a bundled or licensed test audio clip on the device.
3. Background the app and lock the screen; demonstrate audible playback and media controls.
4. Pause/resume through system controls; send an announcement and demonstrate resume.
5. Stop playback and show the resulting state. Use test data only.

### Persistent command reception — `FOREGROUND_SERVICE_SPECIAL_USE`

Functionality:

> After the user selects Save and Connect, HAMediaConnect maintains an authenticated HTTPS/WSS connection to the user's chosen Home Assistant instance. It keeps the Android media player reachable for immediate remote playback, volume, mute, and announcement commands, including between playback requests. Connection status is shown in a persistent notification. The user can terminate the connection and playback with Disconnect in the app, disable start-on-boot, or forget credentials. If start-on-boot is enabled, it reconnects after reboot. The current setting defaults to enabled and is visible in Settings.

Impact of deferral or interruption:

> If the connection is deferred or suspended while idle, Home Assistant cannot immediately deliver music or time-sensitive announcements to the device. The device would appear unavailable or miss the requested playback until the connection returns. A connection is needed while the user wants this device available as a Home Assistant player, including when no audio is currently playing. The app reconnects after transient network interruptions.

Use case: Special use / other — persistent, user-enabled Home Assistant media-player command reception.

Explanation of service type and alternatives:

> This service receives commands between playback requests; actual audio playback uses a separate mediaPlayback service. The user's self-hosted Home Assistant instance delivers commands through its authenticated WebSocket API. Scheduled jobs cannot provide continuous command reception or immediate delivery. The app does not operate a developer push relay. We request review of specialUse for maintaining this user-enabled media-player availability, with visible connection status and an in-app Disconnect control.

Recording sequence:

1. Show Settings, including start-on-boot, then Save and Connect.
2. Show the connection notification and Connected state with no audio playing.
3. Background the app; initiate playback and an announcement from Home Assistant.
4. Show immediate reception, then return to the app and press Disconnect.
5. Show the connection stopping and the player becoming unavailable in Home Assistant.
6. Show how to turn off start-on-boot and forget credentials.

The manifest includes a specific `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` explanation.
Special use remains subject to Google's review; this document does not establish
approval. Keep the submitted description and recordings consistent with the build.

Sources: [Google's declaration requirements](https://support.google.com/googleplay/android-developer/answer/13392821),
[foreground-service policy](https://support.google.com/googleplay/android-developer/answer/16559646).

## Reviewer instance

Recommended setup: a separate Home Assistant Container instance on a Linux VM,
with persistent `/config` storage and an HTTPS endpoint with a valid certificate.
Install the matching HAMediaConnect integration and enable it. Do not connect the
instance to a real home, personal media library, production accounts, or devices.
Use an isolated test account and licensed/bundled sample audio, and prepare a
dashboard for playback, volume/mute, announcement, and identify actions. Verify the
dashboard can target the reviewer's newly enrolled player rather than a fixed old
entity ID. Reviewers must be able to exercise the features without maintainer
intervention. Test the instructions from a fresh app installation on an external
network before providing them in Play Console.

Provide the HTTPS URL, reusable test sign-in, and English instructions in Play
Console's App access section, never in this public repository. Keep the instance
and credentials available for initial and subsequent reviews. Limit the account's
privileges to what review needs. Use a separate administrator account for server
maintenance. Ordinary test access should not require MFA, invitations, or a
maintainer to approve each sign-in. Do not change the app's authentication or
device-possession checks for reviewers.

Hosting options checked against provider documentation:

| Option | Suitability |
| --- | --- |
| Oracle Cloud Always Free VM | Feasible free Linux host with persistent storage. The documented Arm allocation is currently 2 OCPUs / 12 GB total; provision only resources marked eligible and stay within all quotas. Capacity is not guaranteed, and Oracle may reclaim idle VMs after a seven-day low-usage period. A lightly used review instance is therefore not guaranteed to remain available. |
| Render free web service | Poor fit: services sleep after inactivity and the free tier has no persistent disk. Home Assistant needs persistent configuration and device identity records. |
| Existing spare hardware running a separate instance | No additional VM rental if hardware and a suitable HTTPS endpoint already exist; uptime, isolation, and external reachability are your responsibility. Never expose a production home for review. |

Oracle is a practical free candidate, with an uptime caveat. A small paid VM is
more predictable if free-tier reclamation or capacity prevents reliable reviewer
access. No hosting account, paid resource, or reviewer instance has been created.

Sources: [Oracle Always Free resources and reclamation](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm),
[Render free-service limits](https://render.com/docs/free),
[Home Assistant Container installation](https://www.home-assistant.io/installation/linux),
[Google reviewer access requirements](https://support.google.com/googleplay/android-developer/answer/15748846).

## Android Auto

The client does not declare Android Auto support. Keep
`com.google.android.gms.car.application` and the automotive descriptor out of the
release manifest until car support has been implemented and validated separately.
Normal trusted Android media controls remain supported.
