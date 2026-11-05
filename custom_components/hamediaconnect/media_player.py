"""Home Assistant media player entities backed by registered Android devices."""
from __future__ import annotations

import asyncio
import logging
import mimetypes
import secrets

from homeassistant.components import media_source
from homeassistant.components.media_player import MediaPlayerEntity, MediaPlayerEntityFeature, MediaType, MediaPlayerState
from homeassistant.components.media_player.browse_media import async_process_play_media_url
from urllib.parse import urlsplit

from homeassistant.helpers.network import NoURLAvailableError, get_url, is_hass_url
from homeassistant.core import callback
from homeassistant.helpers.entity import DeviceInfo

from .const import DOMAIN, FEATURE_REPORT, FEATURE_TRANSFER
from .websocket_api import REGISTRY_KEY

_LOGGER = logging.getLogger(__name__)
TTS_TIMEOUT = 30
SUPPORTED = (
    MediaPlayerEntityFeature.PLAY
    | MediaPlayerEntityFeature.PAUSE
    | MediaPlayerEntityFeature.STOP
    | MediaPlayerEntityFeature.PLAY_MEDIA
    | MediaPlayerEntityFeature.MEDIA_ANNOUNCE
    | MediaPlayerEntityFeature.VOLUME_SET
    | MediaPlayerEntityFeature.VOLUME_MUTE
)


async def async_setup_entry(hass, entry, async_add_entities):
    """Create entities for devices registered during this HA runtime."""
    registry = hass.data[REGISTRY_KEY]
    known = set()

    def add(device_id):
        if device_id in known:
            return
        known.add(device_id)
        entity = AndroidMediaPlayer(registry, device_id)
        # A device deleted in the HA UI removes its entity; a later re-enrollment adds it again.
        entity.async_on_remove(lambda: known.discard(device_id))
        async_add_entities([entity])

    @callback
    def add_registered(event):
        add(event.data["device_id"])

    entry.async_on_unload(hass.bus.async_listen(f"{DOMAIN}_registered", add_registered))
    for device_id, player in registry.players.items():
        # Revoked keys can never reconnect; their old entities stay deletable in the HA UI.
        if player.revoked is not True:
            add(device_id)


class AndroidMediaPlayer(MediaPlayerEntity):
    """A controllable Android audio endpoint."""

    _attr_has_entity_name = False
    _attr_name = None
    _attr_supported_features = SUPPORTED

    def __init__(self, registry, device_id):
        self.registry = registry
        self.device_id = device_id
        self._attr_unique_id = f"{DOMAIN}_{device_id}"

    @property
    def name(self):
        """Expose the registered device's friendly name."""
        player = self.registry.players.get(self.device_id)
        return player.name if player else self.device_id

    @property
    def device_info(self):
        player = self.registry.players.get(self.device_id)
        if player is None:
            return None
        return DeviceInfo(
            identifiers={(DOMAIN, self.device_id)},
            name=player.name,
            manufacturer="HAMediaConnect",
            model="Android Media Player",
        )

    @property
    def available(self):
        player = self.registry.players.get(self.device_id)
        return player is not None and player.send_command is not None

    @property
    def state(self):
        player = self.registry.players.get(self.device_id)
        return player.state.get("state", MediaPlayerState.IDLE) if player else None

    @property
    def volume_level(self):
        return self.registry.players[self.device_id].state.get("volume_level")

    @property
    def is_volume_muted(self):
        return self.registry.players[self.device_id].state.get("is_volume_muted")

    @property
    def media_title(self):
        return self.registry.players[self.device_id].state.get("media_title")

    @property
    def media_content_id(self):
        return self.registry.players[self.device_id].state.get("media_content_id")

    @property
    def extra_state_attributes(self):
        """Last delivery progress reported by the device; never contains URLs or tokens."""
        player = self.registry.players.get(self.device_id)
        delivery = getattr(player, "delivery", None)
        if not isinstance(delivery, dict) or not delivery:
            return None
        return {f"delivery_{key}": delivery[key] for key in ("phase", "reason", "method", "request_id") if key in delivery}

    def _supports(self, feature):
        player = self.registry.players.get(self.device_id)
        return player is not None and player.supports(feature) is True

    async def _async_tts_transfer(self, media_id):
        """Render HA TTS in-process so delivery needs no device-reachable media URL."""
        try:
            from homeassistant.components import tts  # Optional: URL delivery works without TTS.

            async with asyncio.timeout(TTS_TIMEOUT):
                extension, data = await tts.async_get_media_source_audio(self.hass, media_id)
            mime = mimetypes.guess_type(f"audio.{extension}")[0] if isinstance(extension, str) else None
            return self.registry.transfers.offer(self.device_id, data, mime or "")
        except Exception:  # noqa: BLE001 - any failure keeps the established URL delivery.
            # The media ID can contain the spoken message; never log it.
            _LOGGER.debug("Direct TTS delivery unavailable for %s; using media URL", self.entity_id)
            return None

    async def async_added_to_hass(self):
        await super().async_added_to_hass()

        @callback
        def changed(event):
            if event.data.get("device_id") == self.device_id:
                self.async_write_ha_state()

        self.async_on_remove(self.hass.bus.async_listen(f"{DOMAIN}_state", changed))
        self.async_on_remove(self.hass.bus.async_listen(f"{DOMAIN}_registered", changed))

    def _send(self, action, **kwargs):
        self.registry.dispatch(self.device_id, {"action": action, **kwargs})

    async def async_media_play(self):
        self._send("play")

    async def async_media_pause(self):
        self._send("pause")

    async def async_media_stop(self):
        self._send("stop")

    async def async_set_volume_level(self, volume):
        self._send("volume_set", volume_level=volume)

    async def async_mute_volume(self, mute):
        self._send("volume_mute", is_volume_muted=mute)

    async def async_play_media(self, media_type: MediaType | str, media_id: str, **kwargs):
        """Deliver HA TTS in-band when negotiated; otherwise resolve a reachable media URL."""
        is_tts = media_id.startswith('media-source://tts/')
        extra = kwargs.get("extra")
        title = extra.get("title") if isinstance(extra, dict) else None
        metadata = {"media_title": title} if isinstance(title, str) and 0 < len(title) <= 512 else {}
        request = {"request_id": secrets.token_urlsafe(12)} if self._supports(FEATURE_REPORT) else {}
        if is_tts and self._supports(FEATURE_TRANSFER):
            transfer = await self._async_tts_transfer(media_id)
            if transfer is not None:
                # One delivery method per command: the device never races URL and transfer.
                if not self.registry.dispatch(self.device_id, {
                        "action": "play_media", **transfer.offer(), "announce": True, **metadata, **request}):
                    self.registry.transfers.drop_device(self.device_id)
                return
        if media_source.is_media_source_id(media_id):
            item = await media_source.async_resolve_media(
                self.hass, media_id, target_media_player=self.entity_id
            )
            media_id = item.url
            media_type = item.mime_type

        # Remember ownership before a helper chooses a local/default origin. A
        # relative HA resource must not lose its provenance when made absolute.
        parsed = urlsplit(media_id)
        ha_owned = (not parsed.scheme and not parsed.netloc and parsed.path.startswith("/")) or is_hass_url(self.hass, media_id)
        if ha_owned:
            is_tts = is_tts or parsed.path.startswith("/api/tts_proxy/")
            # Sign while still relative, so no private URL has to be guessed or
            # rewritten by Android. TTS proxy paths already contain HA's token.
            media_id = async_process_play_media_url(self.hass, media_id, allow_relative_url=True)
            parsed = urlsplit(media_id)
            try:
                external = get_url(self.hass, allow_internal=False, require_ssl=True, prefer_cloud=True)
            except NoURLAvailableError:
                media_id = async_process_play_media_url(self.hass, media_id)
            else:
                media_id = external.rstrip("/") + parsed.path
                if parsed.query:
                    media_id += "?" + parsed.query
        else:
            media_id = async_process_play_media_url(self.hass, media_id)
        self._send(
            "play_media",
            media_content_type=str(media_type),
            media_content_id=media_id,
            announce=is_tts or bool(kwargs.get("announce", False)),
            **metadata,
            **request,
        )
