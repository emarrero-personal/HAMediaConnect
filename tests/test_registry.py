"""Unit tests for player ownership, state, and command routing.

Run with pytest; these tests stub Home Assistant's core module and do not require
a running HA server. Full HA integration tests are still required.
"""
import importlib.util
import sys
import types
from pathlib import Path
from unittest.mock import Mock

import pytest

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "custom_components" / "ha_remote_media_player" / "registry.py"


@pytest.fixture
def registry_module(monkeypatch):
    homeassistant = types.ModuleType("homeassistant")
    core = types.ModuleType("homeassistant.core")
    core.HomeAssistant = type("HomeAssistant", (), {})
    core.callback = lambda function: function
    monkeypatch.setitem(sys.modules, "homeassistant", homeassistant)
    monkeypatch.setitem(sys.modules, "homeassistant.core", core)

    package = types.ModuleType("custom_components")
    package.__path__ = [str(ROOT / "custom_components")]
    integration = types.ModuleType("custom_components.ha_remote_media_player")
    integration.__path__ = [str(MODULE.parent)]
    monkeypatch.setitem(sys.modules, "custom_components", package)
    monkeypatch.setitem(sys.modules, "custom_components.ha_remote_media_player", integration)
    spec = importlib.util.spec_from_file_location(
        "custom_components.ha_remote_media_player.registry", MODULE
    )
    module = importlib.util.module_from_spec(spec)
    monkeypatch.setitem(sys.modules, spec.name, module)
    spec.loader.exec_module(module)
    return module


def test_owner_registration_and_takeover_prevention(registry_module):
    hass = Mock()
    registry = registry_module.PlayerRegistry(hass)
    player = registry.register("pixel", "Pixel", "owner-a")
    assert registry.register("pixel", "New name", "owner-a") is player
    assert player.name == "New name"
    with pytest.raises(PermissionError):
        registry.register("pixel", "Impostor", "owner-b")
    assert player.owner_id == "owner-a"


def test_state_and_dispatch(registry_module):
    registry = registry_module.PlayerRegistry(Mock())
    player = registry.register("pixel", "Pixel", "owner")
    registry.update(player, {"state": "playing", "volume_level": 0.4})
    assert player.state["volume_level"] == 0.4
    assert registry.dispatch("pixel", {"action": "pause"}) is False
    received = []
    player.send_command = received.append
    assert registry.dispatch("pixel", {"action": "pause"}) is True
    assert received == [{"action": "pause"}]
    assert registry.dispatch("unknown", {"action": "stop"}) is False
