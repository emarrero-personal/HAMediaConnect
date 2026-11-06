"""Per-device sonar ping delivered over the authenticated media connection."""
from __future__ import annotations

from contextlib import suppress
from pathlib import Path
import secrets

from homeassistant.components.button import ButtonEntity
from homeassistant.core import callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity import DeviceInfo

from .const import DOMAIN, FEATURE_REPORT, FEATURE_TRANSFER
from .transfer import MAX_TRANSFER_BYTES, TransferUnavailable
from .websocket_api import REGISTRY_KEY

SONAR_PATH = Path(__file__).parent / "media" / "SamsterBirdies-Sonar-Ping.mp3"


def _read_sonar() -> bytes:
    """Read only the bundled asset, off the event loop, within the transfer limit."""
    with SONAR_PATH.open("rb") as audio:
        return audio.read(MAX_TRANSFER_BYTES + 1)


async def async_setup_entry(hass, entry, async_add_entities):
    """Create one button for each persisted or newly enrolled player device."""
    registry = hass.data[REGISTRY_KEY]
    known = set()

    def add(device_id):
        if not isinstance(device_id, str):
            return
        player = registry.players.get(device_id)
        if device_id in known or player is None or player.revoked is True:
            return
        known.add(device_id)
        entity = SonarPingButton(registry, device_id)
        entity.async_on_remove(lambda: known.discard(device_id))
        async_add_entities([entity])

    @callback
    def registered(event):
        add(event.data.get("device_id"))

    entry.async_on_unload(hass.bus.async_listen(f"{DOMAIN}_registered", registered))
    for device_id in registry.players:
        add(device_id)


class SonarPingButton(ButtonEntity):
    """Identify a connected media player without changing its volume or mute state."""

    _attr_has_entity_name = True
    _attr_translation_key = "sonar_ping"
    _attr_icon = "mdi:radar"
    _attr_should_poll = False

    def __init__(self, registry, device_id):
        self.registry = registry
        self.device_id = device_id
        self._attr_unique_id = f"{DOMAIN}_{device_id}_sonar_ping"

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
        return (player is not None and player.revoked is not True
                and player.send_command is not None and player.supports(FEATURE_TRANSFER) is True)

    async def async_added_to_hass(self):
        await super().async_added_to_hass()

        @callback
        def changed(event):
            if event.data.get("device_id") == self.device_id:
                self.async_write_ha_state()

        self.async_on_remove(self.hass.bus.async_listen(f"{DOMAIN}_state", changed))
        self.async_on_remove(self.hass.bus.async_listen(f"{DOMAIN}_registered", changed))

    async def async_press(self) -> None:
        """Announce the bundled ping using the existing device-bound transfer protocol."""
        if not self.available:
            raise HomeAssistantError("Connect a client app with direct audio transfer support to play the sonar ping.")
        try:
            data = await self.hass.async_add_executor_job(_read_sonar)
        except OSError as err:
            raise HomeAssistantError("The bundled sonar audio is unavailable. Reinstall HAMediaConnect.") from err
        # File I/O yielded to the event loop: the device may have disconnected or been revoked.
        if not self.available:
            raise HomeAssistantError("The device disconnected before the sonar ping could be sent.")
        player = self.registry.players[self.device_id]
        try:
            transfer = self.registry.transfers.offer(self.device_id, data, "audio/mpeg")
        except TransferUnavailable as err:
            raise HomeAssistantError("Unable to prepare the sonar ping. Retry when audio transfers are available.") from err
        request = {"request_id": secrets.token_urlsafe(12)} if player.supports(FEATURE_REPORT) is True else {}
        try:
            sent = self.registry.dispatch(self.device_id, {
                "action": "play_media", **transfer.offer(), "announce": True,
                "media_title": "Sonar ping", **request})
        except (OSError, RuntimeError):
            sent = False
        if not sent:
            with suppress(TransferUnavailable):
                self.registry.transfers.release(self.device_id, transfer.transfer_id)
            raise HomeAssistantError("Unable to send the sonar ping. Reconnect the device or retry shortly.")
