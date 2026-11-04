"""Isolated media_player platform lifecycle and command dispatch tests."""
import asyncio
import importlib.util
import sys
import types
from pathlib import Path
from unittest.mock import Mock

import pytest

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / "custom_components" / "hamediaconnect"


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
        MEDIA_ANNOUNCE = 64

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
    network = module("homeassistant.helpers.network")
    class NoURLAvailableError(Exception):
        pass
    network.NoURLAvailableError = NoURLAvailableError
    def no_external(*args, **kwargs):
        raise NoURLAvailableError
    network.get_url = no_external
    network.is_hass_url = lambda hass, url: url.startswith("https://ha.example/") or url.startswith("http://10.1.0.195:8123/")
    entity = module("homeassistant.helpers.entity")
    entity.DeviceInfo = dict
    parent = module("custom_components")
    parent.__path__ = [str(ROOT / "custom_components")]
    package = module("custom_components.hamediaconnect")
    package.__path__ = [str(PACKAGE)]
    const = module("custom_components.hamediaconnect.const")
    const.DOMAIN = "hamediaconnect"
    ws = module("custom_components.hamediaconnect.websocket_api")
    ws.REGISTRY_KEY = "hamediaconnect_registry"
    spec = importlib.util.spec_from_file_location(
        "custom_components.hamediaconnect.media_player", PACKAGE / "media_player.py"
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
    assert entity._attr_supported_features & platform.MediaPlayerEntityFeature.MEDIA_ANNOUNCE
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
    registry.players = {"existing": Mock(name="Existing", state={})}
    hass.data = {"hamediaconnect_registry": registry}
    listeners = {}
    def listen(topic, callback):
        listeners[topic] = callback
        return Mock()
    hass.bus.async_listen.side_effect = listen
    entry = Mock()
    add = Mock()
    asyncio.run(platform.async_setup_entry(hass, entry, add))
    assert "hamediaconnect_registered" in listeners
    add.assert_called_once()
    add.reset_mock()
    event = Mock()
    event.data = {"device_id": "pixel"}
    listeners["hamediaconnect_registered"](event)
    listeners["hamediaconnect_registered"](event)
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
                  "media_content_id": "https://example.com/music.mp3", "announce": False}
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
                  "media_content_id": "https://ha.example/api/tts_proxy/test.mp3", "announce": True}
    )


def test_entity_metadata_state_and_transport_commands(platform):
    player = Mock()
    player.name = "Kitchen"
    player.state = {
        "state": "playing", "volume_level": 0.25, "is_volume_muted": True,
        "media_title": "Morning News", "media_content_id": "https://example.com/news.mp3",
    }
    player.send_command = Mock()
    registry = Mock()
    registry.players = {"kitchen": player}
    entity = platform.AndroidMediaPlayer(registry, "kitchen")
    assert entity.device_info["identifiers"] == {("hamediaconnect", "kitchen")}
    assert entity.state == "playing"
    assert entity.is_volume_muted is True
    assert entity.media_title == "Morning News"
    assert entity.media_content_id == "https://example.com/news.mp3"
    asyncio.run(entity.async_media_play())
    asyncio.run(entity.async_media_stop())
    asyncio.run(entity.async_set_volume_level(0.4))
    asyncio.run(entity.async_mute_volume(False))
    assert [call.args[1] for call in registry.dispatch.call_args_list] == [
        {"action": "play"}, {"action": "stop"},
        {"action": "volume_set", "volume_level": 0.4},
        {"action": "volume_mute", "is_volume_muted": False},
    ]
    del registry.players["kitchen"]
    assert entity.name == "kitchen"
    assert entity.state is None


def test_entity_state_event_listener_filters_devices(platform):
    registry = Mock()
    registry.players = {"kitchen": Mock(name="Kitchen", state={})}
    entity = platform.AndroidMediaPlayer(registry, "kitchen")
    entity.hass = Mock()
    callbacks = {}
    entity.hass.bus.async_listen.side_effect = lambda topic, cb: callbacks.setdefault(topic, cb) or Mock()
    entity.async_write_ha_state = Mock()
    asyncio.run(entity.async_added_to_hass())
    assert "hamediaconnect_state" in callbacks
    assert "hamediaconnect_registered" in callbacks
    other = Mock()
    other.data = {"device_id": "other"}
    callbacks["hamediaconnect_state"](other)
    entity.async_write_ha_state.assert_not_called()
    own = Mock()
    own.data = {"device_id": "kitchen"}
    callbacks["hamediaconnect_state"](own)
    entity.async_write_ha_state.assert_called_once()


def test_announcement_flag_for_direct_media(platform):
    registry = Mock()
    registry.players = {"kitchen": Mock(name="Kitchen", state={})}
    entity = platform.AndroidMediaPlayer(registry, "kitchen")
    entity.hass = Mock()
    entity.entity_id = "media_player.kitchen"
    asyncio.run(entity.async_play_media("music", "https://example.com/a.mp3", announce=True))
    assert registry.dispatch.call_args.args[1]["announce"] is True


@pytest.mark.parametrize("endpoint", ["https://remote.ui.nabu.casa", "https://external.example"])
@pytest.mark.parametrize("url", ["/api/tts_proxy/a.mp3?sig=a%2Fb&x=1", "http://10.1.0.195:8123/api/tts_proxy/a.mp3?sig=a%2Fb&x=1"])
def test_ha_media_external_https_preserves_raw_path_and_query(platform, endpoint, url):
    platform.get_url = Mock(return_value=endpoint)
    registry = Mock()
    entity = platform.AndroidMediaPlayer(registry, "pixel")
    entity.hass = Mock()
    asyncio.run(entity.async_play_media("audio/mpeg", url))
    sent = registry.dispatch.call_args.args[1]
    assert sent["media_content_id"] == endpoint + "/api/tts_proxy/a.mp3?sig=a%2Fb&x=1"
    assert sent["announce"] is True
    platform.get_url.assert_called_once_with(entity.hass, allow_internal=False, require_ssl=True)


def test_public_media_never_rebased_or_detected_by_path_alone(platform):
    platform.get_url = Mock()
    registry = Mock()
    entity = platform.AndroidMediaPlayer(registry, "pixel")
    entity.hass = Mock()
    url = "https://public.example/api/tts_proxy/audio.mp3?signature=keep"
    asyncio.run(entity.async_play_media("music", url))
    assert registry.dispatch.call_args.args[1]["media_content_id"] == url
    assert registry.dispatch.call_args.args[1]["announce"] is False
    platform.get_url.assert_not_called()


def test_local_only_tts_keeps_http_endpoint(platform):
    registry = Mock()
    entity = platform.AndroidMediaPlayer(registry, "pixel")
    entity.hass = Mock()
    url = "http://10.1.0.195:8123/api/tts_proxy/a.mp3"
    asyncio.run(entity.async_play_media("audio/mpeg", url))
    assert registry.dispatch.call_args.args[1]["media_content_id"] == url
    assert registry.dispatch.call_args.args[1]["announce"] is True


def test_signed_media_retains_escaped_path_and_repeated_query(platform):
    platform.get_url = Mock(return_value="https://external.example")
    registry = Mock()
    entity = platform.AndroidMediaPlayer(registry, "pixel")
    entity.hass = Mock()
    url = "https://ha.example/api/media_source/local/a%20b%2Fc.mp3?authSig=fixture%2Fsignature&x=1&x=2"
    asyncio.run(entity.async_play_media("audio/mpeg", url))
    assert registry.dispatch.call_args.args[1]["media_content_id"] == (
        "https://external.example/api/media_source/local/a%20b%2Fc.mp3?authSig=fixture%2Fsignature&x=1&x=2"
    )
    assert registry.dispatch.call_args.args[1]["announce"] is False
