"""Sonar buttons: lifecycle, real device-bound transfers, and rejection paths."""
import asyncio
import base64
import hashlib
import importlib.util
import sys
import time
import types
from pathlib import Path
from unittest.mock import AsyncMock, Mock

import pytest

from test_registry import connection
from test_websocket_api import ws_module, env, register, msg  # noqa: F401 - fixtures

PACKAGE = Path(__file__).resolve().parents[1] / "custom_components" / "hamediaconnect"
FEATURES = ["media_transfer_v1", "playback_report_v1"]


@pytest.fixture
def button(monkeypatch, ws_module):
    def stub(name):
        module = types.ModuleType(name)
        monkeypatch.setitem(sys.modules, name, module)
        return module

    class Entity:
        def __init__(self):
            self.removers = []

        def async_on_remove(self, remove):
            # The production entity does not need a custom base-class constructor.
            if not hasattr(self, "removers"):
                self.removers = []
            self.removers.append(remove)

        async def async_added_to_hass(self):
            pass

    stub("homeassistant.components.button").ButtonEntity = Entity
    stub("homeassistant.helpers").__path__ = []
    stub("homeassistant.helpers.entity").DeviceInfo = dict
    stub("homeassistant.exceptions").HomeAssistantError = type("HomeAssistantError", (Exception,), {})
    name = "custom_components.hamediaconnect.button"
    spec = importlib.util.spec_from_file_location(name, PACKAGE / "button.py")
    result = importlib.util.module_from_spec(spec)
    monkeypatch.setitem(sys.modules, name, result)
    spec.loader.exec_module(result)
    return result


async def ready(ws, hass, features=FEATURES):
    conn = connection()
    device_id = await register(ws, hass, conn)
    await ws.ws_subscribe(hass, conn, msg("subscribe_commands", device_id, features=features))
    return conn, device_id


def entity_for(button, hass, registry, device_id):
    entity = button.SonarPingButton(registry, device_id)
    entity.hass = hass
    hass.async_add_executor_job = AsyncMock(side_effect=asyncio.to_thread)
    return entity


async def test_button_lifecycle_existing_new_revoked_deleted_and_reenrolled(button, ws_module, env):
    hass, registry = env
    _, existing = await ready(ws_module, hass)
    _, revoked = await ready(ws_module, hass)
    await registry.revoke(revoked)
    listeners = {}
    unsubscribe = Mock()
    hass.bus.async_listen.side_effect = lambda topic, cb: listeners.setdefault(topic, cb) and unsubscribe
    entry, add = Mock(), Mock()
    await button.async_setup_entry(hass, entry, add)
    assert [call.args[0][0].device_id for call in add.call_args_list] == [existing]
    entry.async_on_unload.assert_called_once_with(unsubscribe)
    first = add.call_args.args[0][0]
    assert first._attr_unique_id == f"hamediaconnect_{existing}_sonar_ping"
    assert first.device_info["identifiers"] == {("hamediaconnect", existing)}
    assert first.device_info["name"] == registry.players[existing].name
    assert first._attr_has_entity_name is True and first._attr_should_poll is False
    assert first._attr_translation_key == "sonar_ping"
    _, new = await ready(ws_module, hass)
    registered = listeners["hamediaconnect_registered"]
    for data in ({"device_id": new}, {"device_id": new}, {"device_id": revoked},
                 {"device_id": "unknown"}, {}, {"device_id": []}):
        registered(types.SimpleNamespace(data=data))
    assert add.call_count == 2
    first.removers[0]()
    registered(types.SimpleNamespace(data={"device_id": existing}))
    assert add.call_count == 3
    assert add.call_args.args[0][0]._attr_unique_id == first._attr_unique_id
    del registry.players[existing]
    assert first.device_info is None and first.available is False


async def test_state_updates_follow_only_own_device_and_listeners_are_removed(button, ws_module, env):
    hass, registry = env
    _, device_id = await ready(ws_module, hass)
    entity = entity_for(button, hass, registry, device_id)
    callbacks, unsubscribes = {}, []
    def listen(topic, callback):
        callbacks[topic] = callback
        remove = Mock()
        unsubscribes.append(remove)
        return remove
    hass.bus.async_listen.side_effect = listen
    entity.async_write_ha_state = Mock()
    await entity.async_added_to_hass()
    for cb in callbacks.values():
        cb(types.SimpleNamespace(data={"device_id": "other"}))
        cb(types.SimpleNamespace(data={"device_id": device_id}))
    assert entity.async_write_ha_state.call_count == 2
    for remove in entity.removers:
        remove()
    assert len(unsubscribes) == 2
    assert all(remove.call_count == 1 for remove in unsubscribes)


@pytest.mark.parametrize("features", [FEATURES, ["media_transfer_v1"]])
async def test_press_transfers_the_actual_bundled_mp3_as_an_announcement(button, ws_module, env, features):
    hass, registry = env
    conn, device_id = await ready(ws_module, hass, features)
    entity = entity_for(button, hass, registry, device_id)
    assert entity.available is True
    await entity.async_press()
    sent = conn.send_event.call_args.args[1]
    audio = button.SONAR_PATH.read_bytes()
    assert sent["action"] == "play_media" and sent["announce"] is True
    assert sent["media_title"] == "Sonar ping" and sent["media_content_type"] == "audio/mpeg"
    assert sent["media_size"] == len(audio) > 0
    assert sent["media_sha256"] == hashlib.sha256(audio).hexdigest()
    assert "media_content_id" not in sent
    assert ("request_id" in sent) == ("playback_report_v1" in features)
    if "request_id" in sent:
        assert len(sent["request_id"]) == 16
        assert registry.players[device_id].delivery["method"] == "direct"
    # Retrieve through the real authenticated WebSocket handler, not just the store.
    await ws_module.ws_media_chunk(hass, conn, msg("media_chunk", device_id,
        transfer_id=sent["transfer_id"], offset=0))
    assert base64.b64decode(conn.send_result.call_args.args[1]["data"]) == audio
    hass.async_add_executor_job.assert_awaited_once_with(button._read_sonar)
    other = connection()
    await ws_module.ws_media_chunk(hass, other, msg("media_chunk", device_id,
        transfer_id=sent["transfer_id"], offset=0))
    other.send_error.assert_called_once_with(3, "device_auth_failed", "Device request rejected")


@pytest.mark.parametrize("condition", ["offline", "revoked", "removed", "legacy"])
async def test_unavailable_devices_do_not_read_audio_or_dispatch(button, ws_module, env, condition):
    hass, registry = env
    conn, device_id = await ready(ws_module, hass, [] if condition == "legacy" else FEATURES)
    entity = entity_for(button, hass, registry, device_id)
    if condition == "offline":
        registry.players[device_id].send_command = None
    elif condition == "revoked":
        await registry.revoke(device_id)
    elif condition == "removed":
        del registry.players[device_id]
    assert entity.available is False
    with pytest.raises(button.HomeAssistantError, match="direct audio transfer"):
        await entity.async_press()
    hass.async_add_executor_job.assert_not_awaited()
    conn.send_event.assert_not_called()
    assert not registry.transfers.transfers


@pytest.mark.parametrize("change", ["disconnect", "revoke", "legacy", "remove"])
async def test_device_is_rechecked_after_file_io(button, ws_module, env, change):
    hass, registry = env
    conn, device_id = await ready(ws_module, hass)
    entity = entity_for(button, hass, registry, device_id)
    async def read_then_change(fn):
        data = await asyncio.to_thread(fn)
        if change == "revoke":
            await registry.revoke(device_id)
        elif change == "disconnect":
            registry.players[device_id].send_command = None
        elif change == "legacy":
            # Simulate a replacement subscription that did not negotiate transfers.
            registry.players[device_id].send_command.features = ()
        else:
            del registry.players[device_id]
        return data
    hass.async_add_executor_job.side_effect = read_then_change
    with pytest.raises(button.HomeAssistantError, match="disconnected"):
        await entity.async_press()
    conn.send_event.assert_not_called()
    assert not registry.transfers.transfers


async def test_missing_asset_is_an_actionable_error(button, ws_module, env, monkeypatch, tmp_path):
    hass, registry = env
    conn, device_id = await ready(ws_module, hass)
    entity = entity_for(button, hass, registry, device_id)
    monkeypatch.setattr(button, "SONAR_PATH", tmp_path / "missing.mp3")
    with pytest.raises(button.HomeAssistantError, match="Reinstall HAMediaConnect"):
        await entity.async_press()
    conn.send_event.assert_not_called()
    assert not registry.transfers.transfers


@pytest.mark.parametrize("size", [0, 4 * 1024 * 1024 + 200])
async def test_empty_or_oversized_asset_fails_within_the_existing_limit(button, ws_module, env, monkeypatch, tmp_path, size):
    hass, registry = env
    conn, device_id = await ready(ws_module, hass)
    entity = entity_for(button, hass, registry, device_id)
    path = tmp_path / "audio.mp3"
    path.write_bytes(b"x" * size)
    monkeypatch.setattr(button, "SONAR_PATH", path)
    assert len(button._read_sonar()) == min(size, button.MAX_TRANSFER_BYTES + 1)
    with pytest.raises(button.HomeAssistantError, match="Unable to prepare"):
        await entity.async_press()
    conn.send_event.assert_not_called()
    assert not registry.transfers.transfers


async def test_transfer_capacity_exhaustion_preserves_other_devices(button, ws_module, env):
    hass, registry = env
    conn, device_id = await ready(ws_module, hass)
    entity = entity_for(button, hass, registry, device_id)
    for n in range(8):
        registry.transfers.offer(f"other-{n}", b"fixture", "audio/mpeg")
    with pytest.raises(button.HomeAssistantError, match="Unable to prepare"):
        await entity.async_press()
    conn.send_event.assert_not_called()
    assert len(registry.transfers.transfers) == 8


async def test_command_rate_limit_releases_only_the_unsent_ping(button, ws_module, env):
    hass, registry = env
    conn, device_id = await ready(ws_module, hass)
    entity = entity_for(button, hass, registry, device_id)
    other = registry.transfers.offer("other", b"fixture", "audio/mpeg")
    registry.rates[(device_id, "command")] = (time.monotonic() + 60, 120)
    with pytest.raises(button.HomeAssistantError, match="Unable to send"):
        await entity.async_press()
    conn.send_event.assert_not_called()
    assert set(registry.transfers.transfers) == {other.transfer_id}


@pytest.mark.parametrize("error", [OSError("fixture send failure"), RuntimeError("fixture send failure")])
async def test_transport_failure_cleans_up_the_ping(button, ws_module, env, error):
    hass, registry = env
    conn, device_id = await ready(ws_module, hass)
    entity = entity_for(button, hass, registry, device_id)
    conn.send_event.side_effect = error
    with pytest.raises(button.HomeAssistantError, match="Unable to send"):
        await entity.async_press()
    assert not registry.transfers.transfers


async def test_dispatch_cleanup_does_not_delete_a_newer_transfer(button, ws_module, env):
    hass, registry = env
    _, device_id = await ready(ws_module, hass)
    entity = entity_for(button, hass, registry, device_id)
    newer = []
    def replaced(*args):
        newer.append(registry.transfers.offer(device_id, b"newer announcement", "audio/mpeg"))
        return False
    registry.dispatch = replaced
    with pytest.raises(button.HomeAssistantError, match="Unable to send"):
        await entity.async_press()
    assert registry.transfers.chunk(device_id, newer[0].transfer_id, 0) == b"newer announcement"
