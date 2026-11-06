"""Home Assistant integration for Android remote media players."""
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ConfigEntryError, HomeAssistantError
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers.storage import Store

from .compatibility import async_check_ha_version
from .const import DOMAIN, MIN_HA_VERSION
from .oauth_view import OAuthClientView
from .registry import DeviceConnectedError, PlayerRegistry
from .websocket_api import REGISTRY_KEY, async_register_websocket_commands

PLATFORMS = ["media_player"]
CONFIG_SCHEMA = cv.config_entry_only_config_schema(DOMAIN)


async def async_setup(hass: HomeAssistant, config: dict) -> bool:
    """Register OAuth page and authenticated WebSocket commands."""
    if not async_check_ha_version(hass):
        return False
    registry = PlayerRegistry(hass, Store(hass, 1, f"{DOMAIN}.identities", private=True, atomic_writes=True))
    await registry.async_load()
    hass.data[REGISTRY_KEY] = registry
    hass.http.register_view(OAuthClientView())
    async_register_websocket_commands(hass)
    return True


async def async_setup_entry(hass: HomeAssistant, entry) -> bool:
    """Load native media player entities."""
    if not async_check_ha_version(hass):
        raise ConfigEntryError(
            f"HAMediaConnect requires Home Assistant Core {MIN_HA_VERSION} or newer. "
            "Upgrade Home Assistant Core and restart Home Assistant."
        )
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def async_unload_entry(hass: HomeAssistant, entry) -> bool:
    """Unload native entities."""
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)


async def async_remove_config_entry_device(hass: HomeAssistant, entry, device_entry) -> bool:
    """Let an administrator delete a disconnected player device and its entity from the HA UI."""
    registry = hass.data[REGISTRY_KEY]
    for domain, device_id in device_entry.identifiers:
        if domain != DOMAIN:
            continue
        try:
            await registry.forget(device_id)
        except DeviceConnectedError as err:
            raise HomeAssistantError(
                "This device is connected. Disconnect it in the HAMediaConnect app, or revoke it, "
                "then delete it."
            ) from err
    return True
