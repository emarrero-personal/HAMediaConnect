"""Integration setup, config flow and OAuth client endpoint behavior."""
import asyncio
import importlib.util
import sys
import types
from pathlib import Path
from unittest.mock import Mock, AsyncMock

import pytest

ROOT = Path(__file__).resolve().parents[1] / "custom_components" / "hamediaconnect"
PKG = "custom_components.hamediaconnect"


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
    constants = stub("homeassistant.const")
    constants.__version__ = "2026.10.0"
    exceptions = stub("homeassistant.exceptions")
    exceptions.HomeAssistantError = type("HomeAssistantError", (Exception,), {})
    exceptions.ConfigEntryError = type("ConfigEntryError", (Exception,), {})
    entries = stub("homeassistant.config_entries")
    class ConfigFlow:
        def __init__(self):
            self.hass = Mock()
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
        def async_abort(self, **kwargs):
            return ("abort", kwargs)
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
    stub("homeassistant.helpers")
    issues = stub("homeassistant.helpers.issue_registry")
    issues.IssueSeverity = types.SimpleNamespace(ERROR="error")
    issues.async_create_issue = Mock()
    issues.async_delete_issue = Mock()
    load("compatibility")
    config_validation = stub("homeassistant.helpers.config_validation")
    config_validation.config_entry_only_config_schema = Mock(return_value="config-entry-only")
    storage = stub("homeassistant.helpers.storage")
    storage.Store = Mock(return_value=types.SimpleNamespace(async_load=AsyncMock(return_value=None), async_save=AsyncMock()))
    load("registry")
    ws = stub(f"{PKG}.websocket_api")
    ws.REGISTRY_KEY = "hamediaconnect_registry"
    ws.async_register_websocket_commands = Mock()
    oauth = load("oauth_view")
    config = load("config_flow")
    integration = load("__init__")
    return integration, config, oauth, ws


def test_setup_and_entry_lifecycle(modules):
    integration, _, _, ws = modules
    assert integration.CONFIG_SCHEMA == "config-entry-only"
    hass = Mock()
    hass.data = {}
    assert asyncio.run(integration.async_setup(hass, {})) is True
    assert "hamediaconnect_registry" in hass.data
    hass.http.register_view.assert_called_once()
    ws.async_register_websocket_commands.assert_called_once_with(hass)
    hass.config_entries.async_forward_entry_setups = AsyncMock()
    hass.config_entries.async_unload_platforms = AsyncMock(return_value=True)
    entry = Mock()
    assert asyncio.run(integration.async_setup_entry(hass, entry)) is True
    hass.config_entries.async_forward_entry_setups.assert_awaited_once_with(entry, ["media_player", "button"])
    hass.config_entries.async_unload_platforms.return_value = True
    assert asyncio.run(integration.async_unload_entry(hass, entry)) is True
    hass.config_entries.async_unload_platforms.assert_awaited_once_with(entry, ["media_player", "button"])


def test_config_flow_form_and_entry(modules):
    _, config, _, _ = modules
    flow = config.HAMediaConnectConfigFlow()
    assert asyncio.run(flow.async_step_user()) == ("form", {"step_id": "user"})
    assert flow.unique_id == "hamediaconnect"
    assert flow.checked is True
    assert asyncio.run(flow.async_step_user({})) == (
        "entry", {"title": "HAMediaConnect", "data": {}}
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


def test_device_deletion_forgets_disconnected_players(modules):
    integration, _, _, _ = modules
    hass = Mock()
    hass.data = {}
    asyncio.run(integration.async_setup(hass, {}))
    registry = hass.data["hamediaconnect_registry"]
    registry.forget = AsyncMock()
    device = types.SimpleNamespace(identifiers={("other", "x"), ("hamediaconnect", "abc")})
    assert asyncio.run(integration.async_remove_config_entry_device(hass, Mock(), device)) is True
    registry.forget.assert_awaited_once_with("abc")
    registry.forget.side_effect = sys.modules[f"{PKG}.registry"].DeviceConnectedError()
    with pytest.raises(sys.modules["homeassistant.exceptions"].HomeAssistantError, match="connected"):
        asyncio.run(integration.async_remove_config_entry_device(hass, Mock(), device))


@pytest.mark.parametrize("version", ["2025.12.5", "2026.9.9", "2026.10.0b0", "2026.10.0rc1", "unknown"])
def test_unsupported_core_logs_error_raises_repair_and_registers_no_endpoints(modules, monkeypatch, caplog, version):
    integration, _, _, ws = modules
    compatibility = sys.modules[f"{PKG}.compatibility"]
    issues = sys.modules["homeassistant.helpers.issue_registry"]
    monkeypatch.setattr(compatibility, "HA_VERSION", version)
    hass = Mock()
    hass.data = {}
    assert asyncio.run(integration.async_setup(hass, {})) is False
    assert hass.data == {}
    sys.modules["homeassistant.helpers.storage"].Store.assert_not_called()
    hass.http.register_view.assert_not_called()
    ws.async_register_websocket_commands.assert_not_called()
    issues.async_create_issue.assert_called_once_with(
        hass, "hamediaconnect", "unsupported_ha_version", is_fixable=False,
        severity="error", translation_key="unsupported_ha_version",
        translation_placeholders={"current_version": version, "required_version": "2026.10.0"},
        learn_more_url="https://github.com/emarrero-personal/HAMediaConnect#install-home-assistant-integration",
    )
    issues.async_delete_issue.assert_not_called()
    assert any(record.levelname == "ERROR" and version in record.message and "2026.10.0" in record.message
               and "setup is blocked" in record.message for record in caplog.records)


@pytest.mark.parametrize("version", ["2026.10.0", "2026.10.1", "2026.11.0", "2026.11.0.dev0", "2027.1.0"])
def test_supported_core_starts_and_clears_the_version_repair(modules, monkeypatch, version):
    integration, _, _, ws = modules
    compatibility = sys.modules[f"{PKG}.compatibility"]
    issues = sys.modules["homeassistant.helpers.issue_registry"]
    monkeypatch.setattr(compatibility, "HA_VERSION", version)
    hass = Mock()
    hass.data = {}
    assert asyncio.run(integration.async_setup(hass, {})) is True
    issues.async_delete_issue.assert_called_once_with(hass, "hamediaconnect", "unsupported_ha_version")
    issues.async_create_issue.assert_not_called()
    ws.async_register_websocket_commands.assert_called_once_with(hass)


@pytest.mark.parametrize("user_input", [None, {}])
def test_unsupported_core_aborts_both_new_setup_steps_with_upgrade_instructions(modules, monkeypatch, user_input):
    _, config, _, _ = modules
    monkeypatch.setattr(sys.modules[f"{PKG}.compatibility"], "HA_VERSION", "2026.9.3")
    flow = config.HAMediaConnectConfigFlow()
    assert asyncio.run(flow.async_step_user(user_input)) == ("abort", {
        "reason": "unsupported_ha_version",
        "description_placeholders": {"current_version": "2026.9.3", "required_version": "2026.10.0"},
    })
    assert not hasattr(flow, "unique_id")


def test_existing_config_entry_cannot_load_entities_on_unsupported_core(modules, monkeypatch):
    integration, _, _, _ = modules
    monkeypatch.setattr(sys.modules[f"{PKG}.compatibility"], "HA_VERSION", "2026.9.3")
    hass = Mock()
    hass.config_entries.async_forward_entry_setups = AsyncMock()
    with pytest.raises(sys.modules["homeassistant.exceptions"].ConfigEntryError, match="2026.10.0"):
        asyncio.run(integration.async_setup_entry(hass, Mock()))
    hass.config_entries.async_forward_entry_setups.assert_not_awaited()


def test_upgrade_removes_the_existing_repair_and_restores_setup(modules, monkeypatch):
    integration, _, _, _ = modules
    compatibility = sys.modules[f"{PKG}.compatibility"]
    issues = sys.modules["homeassistant.helpers.issue_registry"]
    hass = Mock()
    hass.data = {}
    monkeypatch.setattr(compatibility, "HA_VERSION", "2026.9.3")
    assert asyncio.run(integration.async_setup(hass, {})) is False
    monkeypatch.setattr(compatibility, "HA_VERSION", "2026.10.0")
    assert asyncio.run(integration.async_setup(hass, {})) is True
    issues.async_create_issue.assert_called_once()
    issues.async_delete_issue.assert_called_once_with(hass, "hamediaconnect", "unsupported_ha_version")


def test_version_repair_and_setup_abort_are_translated_and_match_hacs_minimum(modules):
    import json
    compatibility = sys.modules[f"{PKG}.compatibility"]
    strings = json.loads((ROOT / "strings.json").read_text())
    assert strings == json.loads((ROOT / "translations/en.json").read_text())
    issue = strings["issues"]["unsupported_ha_version"]
    assert issue["title"] and "restart" in issue["description"]
    for key in ("current_version", "required_version"):
        assert "{" + key + "}" in issue["description"]
        assert "{" + key + "}" in strings["config"]["abort"]["unsupported_ha_version"]
    assert compatibility.MIN_HA_VERSION == json.loads((ROOT.parents[1] / "hacs.json").read_text())["homeassistant"]
