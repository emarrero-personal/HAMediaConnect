"""Contract tests for the authenticated WebSocket command handlers.

Home Assistant interfaces are stubbed to keep tests fast; these do not replace
full HA runtime integration tests.
"""
import asyncio
import importlib.util
import sys
import types
from pathlib import Path
from unittest.mock import Mock, AsyncMock

import pytest

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / "custom_components" / "hamediaconnect"


@pytest.fixture
def ws_module(monkeypatch):
    ha = types.ModuleType("homeassistant")
    ha.__path__ = []
    core = types.ModuleType("homeassistant.core")
    core.HomeAssistant = type("HomeAssistant", (), {})
    core.callback = lambda f: f
    components = types.ModuleType("homeassistant.components")
    components.__path__ = []
    websocket = types.ModuleType("homeassistant.components.websocket_api")
    websocket.websocket_command = lambda schema: lambda f: f
    websocket.async_response = lambda f: f
    websocket.async_register_command = Mock()
    components.websocket_api = websocket
    for key, value in {
        "homeassistant": ha, "homeassistant.core": core,
        "homeassistant.components": components,
        "homeassistant.components.websocket_api": websocket,
    }.items():
        monkeypatch.setitem(sys.modules, key, value)
    parent = types.ModuleType("custom_components")
    parent.__path__ = [str(ROOT / "custom_components")]
    package = types.ModuleType("custom_components.hamediaconnect")
    package.__path__ = [str(PACKAGE)]
    monkeypatch.setitem(sys.modules, "custom_components", parent)
    monkeypatch.setitem(sys.modules, "custom_components.hamediaconnect", package)

    def load(name):
        spec = importlib.util.spec_from_file_location(
            "custom_components.hamediaconnect." + name, PACKAGE / (name + ".py")
        )
        module = importlib.util.module_from_spec(spec)
        monkeypatch.setitem(sys.modules, spec.name, module)
        spec.loader.exec_module(module)
        return module

    load("const")
    load("registry")
    return load("websocket_api")


from test_registry import keypair, proof, connection


@pytest.fixture
def env(ws_module):
    registry_class = sys.modules["custom_components.hamediaconnect.registry"].PlayerRegistry
    hass = Mock()
    registry = registry_class(hass, Mock(async_save=AsyncMock()))
    hass.data = {ws_module.REGISTRY_KEY: registry}
    return hass, registry


async def register(ws, hass, conn):
    key, public = keypair()
    await ws.ws_challenge(hass, conn, {"id": 1, "type": "hamediaconnect/challenge", "public_key": public, "name": "Pixel"})
    result = conn.send_result.call_args.args[1]
    await ws.ws_register(hass, conn, {"id": 2, "type": "hamediaconnect/register", "signature": proof(key, result["payload"])})
    return result["device_id"]


def msg(kind, device_id, **fields):
    return {"id": 3, "type": f"hamediaconnect/{kind}", "device_id": device_id, **fields}


async def test_full_protocol_and_disconnect(ws_module, env):
    hass, registry = env
    conn = connection()
    device_id = await register(ws_module, hass, conn)
    await ws_module.ws_subscribe(hass, conn, msg("subscribe_commands", device_id))
    assert registry.dispatch(device_id, {"action": "pause"})
    conn.send_event.assert_called_once_with(3, {"action": "pause"})
    await ws_module.ws_state(hass, conn, msg("state", device_id, state={"state": "playing", "volume_level": .5}))
    assert registry.players[device_id].state["state"] == "playing"
    await ws_module.ws_subscribe(hass, conn, {**msg("subscribe_commands", device_id), "id": 4})
    conn.send_error.assert_called_with(4, "device_auth_failed", "Device request rejected")
    # Native HA invokes all subscription cleanup callbacks on disconnect.
    for cleanup in list(conn.subscriptions.values()):
        cleanup()
    assert not registry.sessions
    assert not registry.dispatch(device_id, {"action": "pause"})


async def test_same_user_and_cross_user_cannot_subscribe_or_report(ws_module, env):
    hass, registry = env
    device_id = await register(ws_module, hass, connection())
    for attacker in (connection(), connection("other")):
        await ws_module.ws_subscribe(hass, attacker, msg("subscribe_commands", device_id))
        await ws_module.ws_state(hass, attacker, msg("state", device_id, state={"state": "playing"}))
        assert attacker.send_error.call_count == 2
        attacker.send_result.assert_not_called()
    assert not registry.players[device_id].state


@pytest.mark.parametrize("fields", [
    {"state": {"extra": "bad"}}, {"extra": "bad", "state": {}},
    {"state": {"volume_level": float("nan")}}, {"state": {"volume_level": float("inf")}},
    {"state": {"volume_level": True}}, {"state": {"volume_level": "0.5"}},
    {"state": {"state": "invalid"}}, {"state": {"media_title": "x" * 513}},
    {"state": {"media_content_id": "x" * 2049}}, {"state": {"is_volume_muted": 1}},
    {"state": {}, "device_id": "x" * 20000}, {"state": {}, "device_id": "spoof"},
])
async def test_strict_state_validation(ws_module, env, fields):
    hass, registry = env
    conn = connection()
    device_id = await register(ws_module, hass, conn)
    conn.send_result.reset_mock()
    await ws_module.ws_state(hass, conn, {**msg("state", device_id), **fields})
    conn.send_error.assert_called_once()
    conn.send_result.assert_not_called()


async def test_revocation_requires_admin_and_storage_error_is_generic(ws_module, env):
    hass, registry = env
    conn = connection()
    device_id = await register(ws_module, hass, conn)
    conn.user.is_admin = False
    await ws_module.ws_revoke(hass, conn, msg("revoke", device_id))
    assert not registry.players[device_id].revoked
    conn.user.is_admin = True
    registry.store.async_save.side_effect = OSError("secret path")
    await ws_module.ws_revoke(hass, conn, msg("revoke", device_id))
    conn.send_error.assert_called_with(3, "storage_failed", "Identity storage unavailable")
    registry.store.async_save.side_effect = None
    await ws_module.ws_revoke(hass, conn, msg("revoke", device_id))
    assert registry.players[device_id].revoked


async def test_subscribe_unsubscribe_identity_and_registration_validation(ws_module, env):
    hass, registry = env
    conn = connection()
    device_id = await register(ws_module, hass, conn)
    await ws_module.ws_subscribe(hass, conn, msg("subscribe_commands", device_id))
    replacement = Mock()
    registry.players[device_id].send_command = replacement
    conn.subscriptions[3]()
    assert registry.players[device_id].send_command is replacement
    await ws_module.ws_challenge(hass, connection(), {"id": 6, "type": "hamediaconnect/challenge", "public_key": "bad", "name": "X"})
    await ws_module.ws_register(hass, conn, {"id": 7, "type": "hamediaconnect/register", "signature": "bad"})
    conn.send_error.assert_called_with(7, "device_auth_failed", "Device request rejected")


def test_handlers(ws_module):
    websocket = sys.modules["homeassistant.components.websocket_api"]
    ws_module.async_register_websocket_commands(Mock())
    assert websocket.async_register_command.call_count == 5
