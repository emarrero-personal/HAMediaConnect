"""Home Assistant media player entities backed by registered Android devices."""
from __future__ import annotations

from homeassistant.components import media_source
from homeassistant.components.media_player import MediaPlayerEntity, MediaPlayerEntityFeature, MediaType, MediaPlayerState
from homeassistant.components.media_player.browse_media import async_process_play_media_url
from homeassistant.core import callback
from homeassistant.helpers.entity import DeviceInfo

from .const import DOMAIN
from .websocket_api import REGISTRY_KEY

SUPPORTED = (
    MediaPlayerEntityFeature.PLAY
    | MediaPlayerEntityFeature.PAUSE
    | MediaPlayerEntityFeature.STOP
    | MediaPlayerEntityFeature.PLAY_MEDIA
    | MediaPlayerEntityFeature.VOLUME_SET
    | MediaPlayerEntityFeature.VOLUME_MUTE
)


async def async_setup_entry(hass, entry, async_add_entities):
    """Create entities for devices registered during this HA runtime."""
    registry = hass.data[REGISTRY_KEY]
    known = set()

    @callback
    def add_registered(event):
        device_id = event.data["device_id"]
        if device_id not in known:
            known.add(device_id)
            async_add_entities([AndroidMediaPlayer(registry, device_id)])

    unsub = hass.bus.async_listen(f"{DOMAIN}_registered", add_registered)
    entry.async_on_unload(unsub)
    for device_id in registry.players:
        if device_id not in known:
            known.add(device_id)
            async_add_entities([AndroidMediaPlayer(registry, device_id)])


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
        player = self.registry.players[self.device_id]
        return DeviceInfo(
            identifiers={(DOMAIN, self.device_id)},
            name=player.name,
            manufacturer="HARemoteMediaPlayer",
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
        """Resolve HA media sources (including TTS) before sending audio to Android."""
        if media_source.is_media_source_id(media_id):
            item = await media_source.async_resolve_media(
                self.hass, media_id, target_media_player=self.entity_id
            )
            media_id = item.url
            media_type = item.mime_type

        # HA-generated media URLs may be relative (e.g. /api/tts_proxy/...).
        # Android must receive an absolute URL, not a media-source URI.
        media_id = async_process_play_media_url(self.hass, media_id)
        self._send(
            "play_media",
            media_content_type=str(media_type),
            media_content_id=media_id,
            announce=bool(kwargs.get("announce", False)),
        )
