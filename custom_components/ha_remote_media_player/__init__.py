"""Home Assistant integration for Android remote media players."""
from homeassistant.core import HomeAssistant

from .const import DOMAIN
from .oauth_view import OAuthClientView
from .registry import PlayerRegistry
from .websocket_api import REGISTRY_KEY, async_register_websocket_commands

PLATFORMS = ["media_player"]


async def async_setup(hass: HomeAssistant, config: dict) -> bool:
    """Register OAuth page and authenticated WebSocket commands."""
    hass.data[REGISTRY_KEY] = PlayerRegistry(hass)
    hass.http.register_view(OAuthClientView())
    async_register_websocket_commands(hass)
    return True


async def async_setup_entry(hass: HomeAssistant, entry) -> bool:
    """Load native media player entities."""
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def async_unload_entry(hass: HomeAssistant, entry) -> bool:
    """Unload native entities."""
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
