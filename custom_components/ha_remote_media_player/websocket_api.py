"""Authenticated WebSocket commands for Android player registration and control."""
from __future__ import annotations

import voluptuous as vol
from homeassistant.components import websocket_api
from homeassistant.core import HomeAssistant, callback

from .const import DOMAIN
from .registry import PlayerRegistry

REGISTRY_KEY = f"{DOMAIN}_registry"

def _registry(hass: HomeAssistant) -> PlayerRegistry:
    return hass.data[REGISTRY_KEY]

def _owned(hass, connection, msg):
    player = _registry(hass).players.get(msg["device_id"])
    if player is None or player.owner_id != connection.user.id:
        connection.send_error(msg["id"], "not_found", "Device not registered to this user")
        return None
    return player


@websocket_api.websocket_command({
    vol.Required("type"): "ha_remote_media_player/register",
    vol.Required("device_id"): vol.All(str, vol.Length(min=1, max=128)),
    vol.Required("name"): vol.All(str, vol.Length(min=1, max=128)),
})
@websocket_api.async_response
async def ws_register(hass, connection, msg):
    """Register the calling user's own Android player."""
    try:
        player = _registry(hass).register(msg["device_id"], msg["name"], connection.user.id)
    except PermissionError:
        connection.send_error(msg["id"], "forbidden", "Device already registered to another user")
        return
    connection.send_result(msg["id"], {"device_id": player.device_id})


@websocket_api.websocket_command({
    vol.Required("type"): "ha_remote_media_player/state",
    vol.Required("device_id"): str,
    vol.Required("state"): vol.Schema({
        vol.Optional("state"): vol.In(["off", "idle", "playing", "paused", "buffering", "unavailable"]),
        vol.Optional("volume_level"): vol.All(vol.Coerce(float), vol.Range(min=0, max=1)),
        vol.Optional("is_volume_muted"): bool,
        vol.Optional("media_title"): vol.All(str, vol.Length(max=512)),
        vol.Optional("media_content_id"): vol.All(str, vol.Length(max=2048)),
    }, extra=vol.PREVENT_EXTRA),
})
@websocket_api.async_response
async def ws_state(hass, connection, msg):
    """Update only the authenticated user's registered device."""
    player = _owned(hass, connection, msg)
    if player is None:
        return
    _registry(hass).update(player, msg["state"])
    connection.send_result(msg["id"])


@websocket_api.websocket_command({
    vol.Required("type"): "ha_remote_media_player/subscribe_commands",
    vol.Required("device_id"): str,
})
@websocket_api.async_response
async def ws_subscribe(hass, connection, msg):
    """Bind a live command stream to the owning Android user's connection."""
    player = _owned(hass, connection, msg)
    if player is None:
        return
    if player.send_command is not None:
        connection.send_error(msg["id"], "already_connected", "Device has an active command subscriber")
        return

    @callback
    def send(command):
        connection.send_event(msg["id"], command)

    @callback
    def unsubscribe():
        if player.send_command is send:
            player.send_command = None
            hass.bus.async_fire(f"{DOMAIN}_state", {"device_id": player.device_id})

    player.send_command = send
    connection.subscriptions[msg["id"]] = unsubscribe
    connection.send_result(msg["id"])
    hass.bus.async_fire(f"{DOMAIN}_state", {"device_id": player.device_id})


@callback
def async_register_websocket_commands(hass: HomeAssistant) -> None:
    """Register commands once per Home Assistant runtime."""
    websocket_api.async_register_command(hass, ws_register)
    websocket_api.async_register_command(hass, ws_state)
    websocket_api.async_register_command(hass, ws_subscribe)
