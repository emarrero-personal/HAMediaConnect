"""Contract tests for the authenticated WebSocket command handlers.

Home Assistant interfaces are stubbed to keep tests fast; these do not replace
full HA runtime integration tests.
"""
import asyncio
import importlib.util
import sys
import types
from pathlib import Path
from unittest.mock import Mock

import pytest

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / "custom_components" / "ha_remote_media_player"


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
    package = types.ModuleType("custom_components.ha_remote_media_player")
    package.__path__ = [str(PACKAGE)]
    monkeypatch.setitem(sys.modules, "custom_components", parent)
    monkeypatch.setitem(sys.modules, "custom_components.ha_remote_media_player", package)

    def load(name):
        spec = importlib.util.spec_from_file_location(
            "custom_components.ha_remote_media_player." + name, PACKAGE / (name + ".py")
        )
        module = importlib.util.module_from_spec(spec)
        monkeypatch.setitem(sys.modules, spec.name, module)
        spec.loader.exec_module(module)
        return module

    load("const")
    load("registry")
    return load("websocket_api")


def test_register_and_owner_enforcement(ws_module):
    hass = Mock()
    hass.data = {ws_module.REGISTRY_KEY: ws_module.PlayerRegistry(hass)}
    first = Mock()
    first.user.id = "user-1"
    second = Mock()
    second.user.id = "user-2"
    msg = {"id": 1, "device_id": "pixel", "name": "Pixel"}
    asyncio.run(ws_module.ws_register(hass, first, msg))
    first.send_result.assert_called_once()
    asyncio.run(ws_module.ws_register(hass, second, msg))
    second.send_error.assert_called_once_with(1, "forbidden", "Device already registered to another user")
    asyncio.run(ws_module.ws_state(hass, second, {"id": 2, "device_id": "pixel", "state": {"state": "playing"}}))
    second.send_error.assert_called_with(2, "not_found", "Device not registered to this user")


def test_subscribe_deliver_and_disconnect(ws_module):
    hass = Mock()
    hass.data = {ws_module.REGISTRY_KEY: ws_module.PlayerRegistry(hass)}
    registry = hass.data[ws_module.REGISTRY_KEY]
    registry.register("pixel", "Pixel", "owner")
    conn = Mock()
    conn.user.id = "owner"
    conn.subscriptions = {}
    asyncio.run(ws_module.ws_subscribe(hass, conn, {"id": 4, "device_id": "pixel"}))
    conn.send_result.assert_called_once_with(4)
    assert registry.dispatch("pixel", {"action": "pause"})
    conn.send_event.assert_called_once_with(4, {"action": "pause"})
    conn.subscriptions[4]()
    assert not registry.dispatch("pixel", {"action": "pause"})
