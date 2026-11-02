"""HA Remote Media Player integration bootstrap.

This initial milestone serves a public, static OAuth client identification page.
Native media_player entities and authenticated WebSocket commands follow later.
"""
from homeassistant.core import HomeAssistant

DOMAIN = "ha_remote_media_player"

async def async_setup(hass: HomeAssistant, config: dict) -> bool:
    """Register the public OAuth client identification endpoint."""
    from .oauth_view import OAuthClientView
    hass.http.register_view(OAuthClientView())
    return True

async def async_setup_entry(hass: HomeAssistant, entry) -> bool:
    """Initialize the configured integration; OAuth view registered by async_setup."""
    return True

async def async_unload_entry(hass: HomeAssistant, entry) -> bool:
    """Unload integration entry (no platforms registered yet)."""
    return True
