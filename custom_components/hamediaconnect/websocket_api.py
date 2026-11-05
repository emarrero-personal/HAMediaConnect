"""Device proof layered on HA's native authenticated WebSocket transport."""
from __future__ import annotations

import base64
import functools
import json
import math
import voluptuous as vol
from homeassistant.components import websocket_api
from homeassistant.core import HomeAssistant, callback

from .const import DOMAIN
from .registry import FEATURES, MAX_MESSAGE, PHASES, REASONS, REQUEST_ID, RetryableDeviceError
from .transfer import TransferUnavailable

REGISTRY_KEY = f"{DOMAIN}_registry"
def TEXT(maximum):
    return vol.All(str, vol.Length(min=1, max=maximum))


def finite_number(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise vol.Invalid("Finite number required")
    return value

DEVICE = vol.All(TEXT(64), vol.Match(r"^[a-f0-9]{64}$"))
TRANSFER = vol.All(TEXT(22), vol.Match(r"^[A-Za-z0-9_-]{22}$"))


def offset(value):
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise vol.Invalid("Offset required")
    return value
STATE = vol.Schema({
    vol.Optional("state"): vol.In(["off", "idle", "playing", "paused", "buffering", "unavailable"]),
    vol.Optional("volume_level"): vol.All(finite_number, vol.Range(min=0, max=1)),
    vol.Optional("is_volume_muted"): bool,
    vol.Optional("media_title"): TEXT(512),
    vol.Optional("media_content_id"): TEXT(2048),
}, extra=vol.PREVENT_EXTRA)


def command(kind, fields):
    """Validate again inside handlers: bound bytes and reject unexpected keys."""
    schema = vol.Schema({vol.Required("id"): vol.All(int, vol.Range(min=1)),
                         vol.Required("type"): f"hamediaconnect/{kind}", **fields}, extra=vol.PREVENT_EXTRA)
    def decorate(fn):
        @functools.wraps(fn)
        async def handler(hass, connection, msg):
            try:
                registry = hass.data[REGISTRY_KEY]
                registry.budget(connection.user.id, "messages", 2048)
                if len(json.dumps(msg, allow_nan=False).encode()) > MAX_MESSAGE:
                    raise ValueError("Message too large")
                schema(msg)
                await fn(hass, connection, msg)
            except TransferUnavailable:
                # Transfer-scoped: the device fails that delivery, not its connection.
                connection.send_error(msg["id"], "media_unavailable", "Media transfer unavailable")
            except RetryableDeviceError:
                connection.send_error(msg["id"], "temporarily_unavailable", "Retry later")
            except (ValueError, PermissionError, vol.Invalid, RecursionError, TypeError):
                connection.send_error(msg["id"], "device_auth_failed", "Device request rejected")
            except OSError:
                connection.send_error(msg["id"], "storage_failed", "Identity storage unavailable")
        return websocket_api.websocket_command(schema.schema)(websocket_api.async_response(handler))
    return decorate


@command("challenge", {vol.Required("public_key"): TEXT(256), vol.Required("name"): TEXT(128)})
async def ws_challenge(hass, connection, msg):
    result = hass.data[REGISTRY_KEY].challenge(connection, msg["public_key"], msg["name"])
    connection.send_result(msg["id"], result)


@command("register", {vol.Required("signature"): TEXT(128)})
async def ws_register(hass, connection, msg):
    player = await hass.data[REGISTRY_KEY].authenticate(connection, msg["signature"])
    # Older clients ignore unknown result keys; newer clients opt in when subscribing.
    connection.send_result(msg["id"], {"device_id": player.device_id, "features": list(FEATURES)})


@command("state", {vol.Required("device_id"): DEVICE, vol.Required("state"): STATE})
async def ws_state(hass, connection, msg):
    registry = hass.data[REGISTRY_KEY]
    player = registry.owned(connection, msg["device_id"])
    registry.update(player, msg["state"])
    connection.send_result(msg["id"])


@command("subscribe_commands", {vol.Required("device_id"): DEVICE,
                                 vol.Optional("features"): vol.All([TEXT(32)], vol.Length(max=8))})
async def ws_subscribe(hass, connection, msg):
    registry = hass.data[REGISTRY_KEY]
    registry.budget(connection.user.id, "subscribe", 10)
    player = registry.owned(connection, msg["device_id"])
    if player.send_command is not None:
        connection.send_error(msg["id"], "subscription_busy", "Previous connection is closing; retry")
        return

    @callback
    def send(command):
        connection.send_event(msg["id"], command)
    send.connection = connection
    send.features = frozenset(f for f in msg.get("features", ()) if f in FEATURES)

    @callback
    def unsubscribe():
        if player.send_command is send:
            player.send_command = None
            hass.bus.async_fire(f"{DOMAIN}_state", {"device_id": player.device_id})
    player.send_command = send
    connection.subscriptions[msg["id"]] = unsubscribe
    connection.send_result(msg["id"])
    hass.bus.async_fire(f"{DOMAIN}_state", {"device_id": player.device_id})


@command("media_chunk", {vol.Required("device_id"): DEVICE, vol.Required("transfer_id"): TRANSFER,
                         vol.Required("offset"): offset})
async def ws_media_chunk(hass, connection, msg):
    registry = hass.data[REGISTRY_KEY]
    registry.budget(connection.user.id, "chunk", 600)
    player = registry.owned(connection, msg["device_id"])
    data = registry.transfers.chunk(player.device_id, msg["transfer_id"], msg["offset"])
    connection.send_result(msg["id"], {"offset": msg["offset"], "data": base64.b64encode(data).decode()})


@command("media_release", {vol.Required("device_id"): DEVICE, vol.Required("transfer_id"): TRANSFER})
async def ws_media_release(hass, connection, msg):
    registry = hass.data[REGISTRY_KEY]
    player = registry.owned(connection, msg["device_id"])
    registry.transfers.release(player.device_id, msg["transfer_id"])
    connection.send_result(msg["id"])


@command("playback_report", {vol.Required("device_id"): DEVICE,
                             vol.Required("request_id"): vol.All(TEXT(16), vol.Match(REQUEST_ID)),
                             vol.Required("phase"): vol.In(PHASES), vol.Optional("reason"): vol.In(REASONS)})
async def ws_playback_report(hass, connection, msg):
    registry = hass.data[REGISTRY_KEY]
    registry.budget(connection.user.id, "report", 240)
    registry.report(connection, msg["device_id"], msg["request_id"], msg["phase"], msg.get("reason"))
    connection.send_result(msg["id"])


@command("revoke", {vol.Required("device_id"): DEVICE})
async def ws_revoke(hass, connection, msg):
    if not connection.user.is_admin:
        raise PermissionError("Administrator required")
    await hass.data[REGISTRY_KEY].revoke(msg["device_id"])
    connection.send_result(msg["id"])


@callback
def async_register_websocket_commands(hass: HomeAssistant):
    for handler in (ws_challenge, ws_register, ws_state, ws_subscribe, ws_media_chunk,
                    ws_media_release, ws_playback_report, ws_revoke):
        websocket_api.async_register_command(hass, handler)
