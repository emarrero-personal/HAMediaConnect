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
