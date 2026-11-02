"""Isolated media_player platform lifecycle and command dispatch tests."""
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
def platform(monkeypatch):
    def module(name):
        result = types.ModuleType(name)
        monkeypatch.setitem(sys.modules, name, result)
        return result

    module("homeassistant").__path__ = []
    module("homeassistant.core").callback = lambda f: f
    module("homeassistant.components").__path__ = []
    media_source = module("homeassistant.components.media_source")
    media_source.is_media_source_id = lambda value: value.startswith("media-source://")
    media_source.async_resolve_media = Mock()
    media = module("homeassistant.components.media_player")
    media.__path__ = []
    browse = module("homeassistant.components.media_player.browse_media")
    browse.async_process_play_media_url = lambda hass, url: "https://ha.example" + url if url.startswith("/") else url
    from enum import Enum, IntFlag

    class Features(IntFlag):
        PLAY = 1
        PAUSE = 2
        STOP = 4
        PLAY_MEDIA = 8
        VOLUME_SET = 16
        VOLUME_MUTE = 32

    class States(str, Enum):
        IDLE = "idle"

    media.MediaPlayerEntityFeature = Features
    media.MediaPlayerState = States
    media.MediaType = str

    class Entity:
        def async_on_remove(self, callback):
            pass

        async def async_added_to_hass(self):
            pass

    media.MediaPlayerEntity = Entity
    module("homeassistant.helpers").__path__ = []
    entity = module("homeassistant.helpers.entity")
    entity.DeviceInfo = dict
    parent = module("custom_components")
    parent.__path__ = [str(ROOT / "custom_components")]
    package = module("custom_components.ha_remote_media_player")
    package.__path__ = [str(PACKAGE)]
    const = module("custom_components.ha_remote_media_player.const")
    const.DOMAIN = "ha_remote_media_player"
    ws = module("custom_components.ha_remote_media_player.websocket_api")
    ws.REGISTRY_KEY = "ha_remote_media_player_registry"
    spec = importlib.util.spec_from_file_location(
        "custom_components.ha_remote_media_player.media_player", PACKAGE / "media_player.py"
    )
    result = importlib.util.module_from_spec(spec)
    monkeypatch.setitem(sys.modules, spec.name, result)
    spec.loader.exec_module(result)
    return result


def test_entity_commands_and_availability(platform):
    registry = Mock()
    player = Mock()
    player.name = "Living Room"
    player.state = {"state": "playing", "volume_level": 0.6}
    player.send_command = None
    registry.players = {"pixel": player}
    entity = platform.AndroidMediaPlayer(registry, "pixel")
    assert entity.name == "Living Room"
    assert entity.available is False
    player.send_command = Mock()
    assert entity.available is True
    assert entity.volume_level == 0.6
    asyncio.run(entity.async_media_pause())
    registry.dispatch.assert_called_once_with("pixel", {"action": "pause"})


def test_platform_registration_and_listener_cleanup(platform):
    hass = Mock()
    registry = Mock()
    registry.players = {}
    hass.data = {"ha_remote_media_player_registry": registry}
    listeners = {}
    def listen(topic, callback):
        listeners[topic] = callback
        return Mock()
    hass.bus.async_listen.side_effect = listen
    entry = Mock()
    add = Mock()
    asyncio.run(platform.async_setup_entry(hass, entry, add))
    assert "ha_remote_media_player_registered" in listeners
    event = Mock()
    event.data = {"device_id": "pixel"}
    listeners["ha_remote_media_player_registered"](event)
    listeners["ha_remote_media_player_registered"](event)
    add.assert_called_once()
    entry.async_on_unload.assert_called_once()


def test_direct_audio_url(platform):
    registry = Mock()
    registry.players = {"pixel": Mock(name="Pixel", state={})}
    entity = platform.AndroidMediaPlayer(registry, "pixel")
    entity.hass = Mock()
    entity.entity_id = "media_player.pixel"
    asyncio.run(entity.async_play_media("music", "https://example.com/music.mp3"))
    registry.dispatch.assert_called_once_with(
        "pixel", {"action": "play_media", "media_content_type": "music",
                  "media_content_id": "https://example.com/music.mp3"}
    )


def test_tts_media_source_resolved_before_dispatch(platform):
    import sys
    source = sys.modules["homeassistant.components.media_source"]
    async def resolve(hass, media_id, target_media_player):
        assert media_id == "media-source://tts/test"
        assert target_media_player == "media_player.pixel"
        return types.SimpleNamespace(url="/api/tts_proxy/test.mp3", mime_type="audio/mpeg")
    source.async_resolve_media = resolve
    registry = Mock()
    registry.players = {"pixel": Mock(name="Pixel", state={})}
    entity = platform.AndroidMediaPlayer(registry, "pixel")
    entity.hass = Mock()
    entity.entity_id = "media_player.pixel"
    asyncio.run(entity.async_play_media("music", "media-source://tts/test"))
    registry.dispatch.assert_called_once_with(
        "pixel", {"action": "play_media", "media_content_type": "audio/mpeg",
                  "media_content_id": "https://ha.example/api/tts_proxy/test.mp3"}
    )
