"""Integration setup, config flow and OAuth client endpoint behavior."""
import asyncio
import importlib.util
import sys
import types
from pathlib import Path
from unittest.mock import Mock

import pytest

ROOT = Path(__file__).resolve().parents[1] / "custom_components" / "ha_remote_media_player"
PKG = "custom_components.ha_remote_media_player"


@pytest.fixture
def modules(monkeypatch):
    def stub(name):
        obj = types.ModuleType(name)
        obj.__path__ = []
        monkeypatch.setitem(sys.modules, name, obj)
        return obj

    stub("homeassistant")
    core = stub("homeassistant.core")
    core.HomeAssistant = object
    core.callback = lambda f: f
    entries = stub("homeassistant.config_entries")
    class ConfigFlow:
        def __init_subclass__(cls, **kwargs):
            return super().__init_subclass__()
        async def async_set_unique_id(self, value):
            self.unique_id = value
        def _abort_if_unique_id_configured(self):
            self.checked = True
        def async_create_entry(self, **kwargs):
            return ("entry", kwargs)
        def async_show_form(self, **kwargs):
            return ("form", kwargs)
    entries.ConfigFlow = ConfigFlow
    sys.modules["homeassistant"].config_entries = entries
    stub("homeassistant.components")
    http = stub("homeassistant.components.http")
    http.HomeAssistantView = type("HomeAssistantView", (), {})
    stub("custom_components")
    pkg = stub(PKG)
    pkg.__path__ = [str(ROOT)]
    def load(name):
        spec = importlib.util.spec_from_file_location(f"{PKG}.{name}", ROOT / f"{name}.py")
        mod = importlib.util.module_from_spec(spec)
        monkeypatch.setitem(sys.modules, spec.name, mod)
        spec.loader.exec_module(mod)
        return mod
    load("const")
    load("registry")
    ws = stub(f"{PKG}.websocket_api")
    ws.REGISTRY_KEY = "ha_remote_media_player_registry"
    ws.async_register_websocket_commands = Mock()
    oauth = load("oauth_view")
    config = load("config_flow")
    integration = load("__init__")
    return integration, config, oauth, ws


def test_setup_and_entry_lifecycle(modules):
    integration, _, _, ws = modules
    hass = Mock()
    hass.data = {}
    assert asyncio.run(integration.async_setup(hass, {})) is True
    assert "ha_remote_media_player_registry" in hass.data
    hass.http.register_view.assert_called_once()
    ws.async_register_websocket_commands.assert_called_once_with(hass)
    entry = Mock()
    assert asyncio.run(integration.async_setup_entry(hass, entry)) is True
    hass.config_entries.async_forward_entry_setups.assert_awaited_once_with(entry, ["media_player"])
    hass.config_entries.async_unload_platforms.return_value = True
    assert asyncio.run(integration.async_unload_entry(hass, entry)) is True
    hass.config_entries.async_unload_platforms.assert_awaited_once_with(entry, ["media_player"])


def test_config_flow_form_and_entry(modules):
    _, config, _, _ = modules
    flow = config.HARemoteMediaPlayerConfigFlow()
    assert asyncio.run(flow.async_step_user()) == ("form", {"step_id": "user"})
    assert flow.unique_id == "ha_remote_media_player"
    assert flow.checked is True
    assert asyncio.run(flow.async_step_user({})) == (
        "entry", {"title": "HA Remote Media Player", "data": {}}
    )


def test_oauth_client_document(modules):
    _, _, oauth, _ = modules
    view = oauth.OAuthClientView()
    assert view.requires_auth is False
    assert view.cors_allowed is False
    assert view.url == oauth.CLIENT_PATH
    response = asyncio.run(view.get(Mock()))
    assert response.status == 200
    assert 'rel="redirect_uri"' in response.text
    assert oauth.REDIRECT_URI in response.text
    assert response.content_type == "text/html"
    assert response.headers["X-Content-Type-Options"] == "nosniff"
