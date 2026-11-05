"""In-band TTS transfer, feature negotiation and delivery-report contract tests.

HA is stubbed; these exercise the integration's real registry, transfer store and
WebSocket handlers, not a running Home Assistant instance.
"""
import asyncio
import base64
import hashlib
import sys
import types
from unittest.mock import Mock

import pytest

from test_websocket_api import ws_module, env, register, msg  # noqa: F401 - fixtures
from test_registry import connection, keypair, proof
from test_media_player import platform  # noqa: F401 - fixture

FEATURES = ["media_transfer_v1", "playback_report_v1"]
AUDIO = bytes(range(256)) * 600  # 153600 bytes: three full chunks and one partial.


def transfer_module():
    return sys.modules["custom_components.hamediaconnect.transfer"]


async def ready(ws, hass, conn, features=FEATURES):
    device_id = await register(ws, hass, conn)
    assert conn.send_result.call_args.args[1]["features"] == FEATURES
    fields = {"features": features} if features is not None else {}
    await ws.ws_subscribe(hass, conn, msg("subscribe_commands", device_id, **fields))
    return device_id


def offer_command(registry, device_id, data=AUDIO, **extra):
    transfer = registry.transfers.offer(device_id, data, "audio/mpeg")
    return transfer, {"action": "play_media", **transfer.offer(), "announce": True,
                      "request_id": "r" * 16, **extra}


async def chunk(ws, hass, conn, device_id, transfer_id, offset, id_=10):
    conn.send_result.reset_mock()
    conn.send_error.reset_mock()
    await ws.ws_media_chunk(hass, conn, {"id": id_, "type": "hamediaconnect/media_chunk", "device_id": device_id,
                                         "transfer_id": transfer_id, "offset": offset})


async def test_complete_transfer_reassembles_exact_audio_with_integrity(ws_module, env):
    hass, registry = env
    conn = connection()
    device_id = await ready(ws_module, hass, conn)
    transfer, command = offer_command(registry, device_id)
    assert registry.dispatch(device_id, command)
    sent = conn.send_event.call_args.args[1]
    assert sent["media_size"] == len(AUDIO) and sent["chunk_size"] == 49152
    assert sent["media_sha256"] == hashlib.sha256(AUDIO).hexdigest()
    assert "media_content_id" not in sent  # No device-reachable URL is required.
    assembled = bytearray()
    for offset in range(0, len(AUDIO), sent["chunk_size"]):
        await chunk(ws_module, hass, conn, device_id, sent["transfer_id"], offset)
        result = conn.send_result.call_args.args[1]
        assert result["offset"] == offset
        encoded = result["data"]
        assert len(encoded) <= 65536
        assembled += base64.b64decode(encoded, validate=True)
    assert bytes(assembled) == AUDIO
    await ws_module.ws_media_release(hass, conn, {"id": 11, "type": "hamediaconnect/media_release",
                                                 "device_id": device_id, "transfer_id": sent["transfer_id"]})
    await chunk(ws_module, hass, conn, device_id, sent["transfer_id"], 0)
    conn.send_error.assert_called_once_with(10, "media_unavailable", "Media transfer unavailable")


@pytest.mark.parametrize("offset", [1, 49151, len(AUDIO), len(AUDIO) + 49152])
async def test_out_of_range_or_unaligned_offsets_are_transfer_errors_only(ws_module, env, offset):
    hass, registry = env
    conn = connection()
    device_id = await ready(ws_module, hass, conn)
    transfer, _ = offer_command(registry, device_id)
    await chunk(ws_module, hass, conn, device_id, transfer.transfer_id, offset)
    conn.send_error.assert_called_once_with(10, "media_unavailable", "Media transfer unavailable")
    assert registry.players[device_id].send_command is not None  # Connection remains authorized.


@pytest.mark.parametrize("offset", [-1, True, 1.5, "0"])
async def test_malformed_offsets_rejected_by_schema(ws_module, env, offset):
    hass, registry = env
    conn = connection()
    device_id = await ready(ws_module, hass, conn)
    transfer, _ = offer_command(registry, device_id)
    await chunk(ws_module, hass, conn, device_id, transfer.transfer_id, offset)
    conn.send_error.assert_called_once_with(10, "device_auth_failed", "Device request rejected")


async def test_other_device_or_unproven_connection_cannot_read_transfer(ws_module, env):
    hass, registry = env
    owner = connection()
    device_id = await ready(ws_module, hass, owner)
    transfer, _ = offer_command(registry, device_id)
    same_user = connection()
    other_device = await ready(ws_module, hass, same_user)
    await chunk(ws_module, hass, same_user, other_device, transfer.transfer_id, 0)
    same_user.send_error.assert_called_once_with(10, "media_unavailable", "Media transfer unavailable")
    unproven = connection()
    await chunk(ws_module, hass, unproven, device_id, transfer.transfer_id, 0)
    unproven.send_error.assert_called_once_with(10, "device_auth_failed", "Device request rejected")
    intruder = connection("intruder")
    await chunk(ws_module, hass, intruder, device_id, transfer.transfer_id, 0)
    intruder.send_error.assert_called_once_with(10, "device_auth_failed", "Device request rejected")


async def prove(ws, hass, conn, key, public):
    await ws.ws_challenge(hass, conn, {"id": 1, "type": "hamediaconnect/challenge", "public_key": public, "name": "Pixel"})
    result = conn.send_result.call_args.args[1]
    await ws.ws_register(hass, conn, {"id": 2, "type": "hamediaconnect/register", "signature": proof(key, result["payload"])})
    return result["device_id"]


async def test_transfer_survives_reconnect_with_fresh_proof(ws_module, env):
    hass, registry = env
    key, public = keypair()
    first = connection()
    device_id = await prove(ws_module, hass, first, key, public)
    await ws_module.ws_subscribe(hass, first, msg("subscribe_commands", device_id, features=FEATURES))
    transfer, _ = offer_command(registry, device_id)
    await chunk(ws_module, hass, first, device_id, transfer.transfer_id, 0)
    for cleanup in list(first.subscriptions.values()):
        cleanup()  # Network change: HA tears down the old connection mid-transfer.
    await chunk(ws_module, hass, first, device_id, transfer.transfer_id, 49152)
    first.send_error.assert_called_once_with(10, "device_auth_failed", "Device request rejected")
    second = connection()
    assert await prove(ws_module, hass, second, key, public) == device_id  # Fresh challenge and signature.
    await chunk(ws_module, hass, second, device_id, transfer.transfer_id, 49152)
    assert base64.b64decode(second.send_result.call_args.args[1]["data"]) == AUDIO[49152:98304]


async def test_expiry_newer_offer_and_revocation_drop_transfers(ws_module, env):
    hass, registry = env
    conn = connection()
    device_id = await ready(ws_module, hass, conn)
    now = [100.0]
    registry.transfers.clock = lambda: now[0]
    old, _ = offer_command(registry, device_id)
    newer, _ = offer_command(registry, device_id)
    await chunk(ws_module, hass, conn, device_id, old.transfer_id, 0)
    conn.send_error.assert_called_once()  # Superseded announcement is gone.
    now[0] += 121
    await chunk(ws_module, hass, conn, device_id, newer.transfer_id, 0)
    conn.send_error.assert_called_once_with(10, "media_unavailable", "Media transfer unavailable")
    live, _ = offer_command(registry, device_id)
    admin = connection()
    admin.user.is_admin = True
    await ws_module.ws_revoke(hass, admin, {"id": 20, "type": "hamediaconnect/revoke", "device_id": device_id})
    assert live.transfer_id not in registry.transfers.transfers


def test_store_limits_size_type_count_and_bytes(ws_module):
    module = transfer_module()
    store = module.TransferStore()
    for data, mime in [(b"", "audio/mpeg"), (b"x" * (module.MAX_TRANSFER_BYTES + 1), "audio/mpeg"),
                       (b"x", "text/html"), (b"x", "audio/"), ("x", "audio/mpeg"), (b"x", None)]:
        with pytest.raises(module.TransferUnavailable):
            store.offer("a" * 64, data, mime)
    for index in range(module.MAX_TRANSFERS):
        store.offer(f"{index:064x}", b"x", "audio/mpeg")
    with pytest.raises(module.TransferUnavailable):
        store.offer("f" * 64, b"x", "audio/mpeg")
    bytes_store = module.TransferStore()
    for index in range(4):
        bytes_store.offer(f"{index:064x}", b"x" * module.MAX_TRANSFER_BYTES, "audio/mpeg")
    with pytest.raises(module.TransferUnavailable):
        bytes_store.offer("e" * 64, b"x", "audio/mpeg")
    with pytest.raises(module.TransferUnavailable):
        bytes_store.release("e" * 64, "not-a-valid-id")


def test_commands_require_one_delivery_method_and_negotiated_fields(ws_module, env):
    hass, registry = env
    valid = sys.modules["custom_components.hamediaconnect.registry"].valid_command
    transfer = {"transfer_id": "a" * 22, "media_size": 10, "media_sha256": "0" * 64, "chunk_size": 49152}
    assert valid({"action": "play_media", **transfer})
    assert not valid({"action": "play_media", **transfer, "media_content_id": "https://example/a.mp3"})
    assert not valid({"action": "play_media", "transfer_id": "a" * 22})
    assert not valid({"action": "play_media", **transfer, "media_size": True})
    assert not valid({"action": "play_media", **transfer, "media_size": 4 * 1024 * 1024 + 1})
    assert not valid({"action": "play_media", **transfer, "chunk_size": 1024})
    assert not valid({"action": "pause", **transfer})
    assert not valid({"action": "pause", "request_id": "r" * 16})
    assert not valid({"action": "play_media", "media_content_id": "https://example/a.mp3", "request_id": "short"})


async def test_legacy_subscriber_never_receives_unnegotiated_fields(ws_module, env):
    hass, registry = env
    conn = connection()
    device_id = await ready(ws_module, hass, conn, features=None)
    _, command = offer_command(registry, device_id)
    assert not registry.dispatch(device_id, command)
    assert not registry.dispatch(device_id, {"action": "play_media", "media_content_id": "https://e/a.mp3",
                                             "request_id": "r" * 16})
    assert registry.dispatch(device_id, {"action": "play_media", "media_content_id": "https://e/a.mp3"})
    conn.send_event.assert_called_once_with(3, {"action": "play_media", "media_content_id": "https://e/a.mp3"})


async def test_unknown_features_are_ignored_and_bounded(ws_module, env):
    hass, registry = env
    conn = connection()
    device_id = await ready(ws_module, hass, conn, features=["future_v9", "playback_report_v1"])
    player = registry.players[device_id]
    assert player.supports("playback_report_v1") and not player.supports("media_transfer_v1")
    other = connection()
    other_id = await register(ws_module, hass, other)
    await ws_module.ws_subscribe(hass, other, msg("subscribe_commands", other_id, features=["x"] * 9))
    other.send_error.assert_called_with(3, "device_auth_failed", "Device request rejected")


async def test_playback_reports_are_bound_to_dispatched_requests(ws_module, env):
    hass, registry = env
    conn = connection()
    device_id = await ready(ws_module, hass, conn)
    assert registry.dispatch(device_id, {"action": "play_media", "media_content_id": "https://e/a.mp3",
                                         "request_id": "r" * 16})
    assert registry.players[device_id].delivery == {"request_id": "r" * 16, "phase": "dispatched", "method": "url"}
    report = {"id": 30, "type": "hamediaconnect/playback_report", "device_id": device_id, "request_id": "r" * 16}
    await ws_module.ws_playback_report(hass, conn, {**report, "phase": "failed", "reason": "unreachable"})
    conn.send_result.assert_called_with(30)
    assert registry.players[device_id].delivery == {"request_id": "r" * 16, "phase": "failed",
                                                    "method": "url", "reason": "unreachable"}
    hass.bus.async_fire.assert_any_call("hamediaconnect_playback", {
        "device_id": device_id, "request_id": "r" * 16, "phase": "failed", "method": "url", "reason": "unreachable"})
    for bad in [{**report, "request_id": "u" * 16, "phase": "playing"}, {**report, "phase": "exploded"},
                {**report, "phase": "failed", "reason": "https://secret.example/token"}]:
        conn.send_error.reset_mock()
        await ws_module.ws_playback_report(hass, conn, bad)
        conn.send_error.assert_called_once_with(30, "device_auth_failed", "Device request rejected")
    intruder = connection("intruder")
    await ws_module.ws_playback_report(hass, intruder, {**report, "phase": "playing"})
    intruder.send_error.assert_called_once_with(30, "device_auth_failed", "Device request rejected")


async def test_chunk_budget_is_retryable(ws_module, env):
    hass, registry = env
    conn = connection()
    device_id = await ready(ws_module, hass, conn)
    transfer, _ = offer_command(registry, device_id)
    for _ in range(600):
        registry.budget(conn.user.id, "chunk", 600)
    await chunk(ws_module, hass, conn, device_id, transfer.transfer_id, 0)
    conn.send_error.assert_called_once_with(10, "temporarily_unavailable", "Retry later")


# ---- media_player delivery selection -------------------------------------------------------

def transfer_registry(platform, features=("media_transfer_v1", "playback_report_v1"), offer=None):
    registry = Mock()
    player = Mock()
    player.supports = lambda feature: feature in features
    player.delivery = {}
    registry.players = {"pixel": player}
    transfer = Mock()
    transfer.offer.return_value = {"transfer_id": "t" * 22, "media_size": 3, "media_sha256": "0" * 64,
                                   "chunk_size": 49152, "media_content_type": "audio/mpeg"}
    registry.transfers.offer = offer or Mock(return_value=transfer)
    registry.dispatch.return_value = True
    entity = platform.AndroidMediaPlayer(registry, "pixel")
    entity.hass = Mock()
    entity.entity_id = "media_player.pixel"
    return registry, entity


def install_tts(monkeypatch, audio):
    components = sys.modules["homeassistant.components"]
    tts = types.ModuleType("homeassistant.components.tts")
    calls = []

    async def get_audio(hass, media_id):
        calls.append(media_id)
        if isinstance(audio, BaseException):
            raise audio
        return audio
    tts.async_get_media_source_audio = get_audio
    monkeypatch.setitem(sys.modules, "homeassistant.components.tts", tts)
    monkeypatch.setattr(components, "tts", tts, raising=False)
    return calls


def test_tts_is_transferred_in_band_without_resolving_a_url(platform, monkeypatch):
    calls = install_tts(monkeypatch, ("mp3", b"ID3"))
    source = sys.modules["homeassistant.components.media_source"]
    source.async_resolve_media = Mock(side_effect=AssertionError("URL must not be generated"))
    platform.get_url = Mock(side_effect=AssertionError("No reachable URL is needed"))
    registry, entity = transfer_registry(platform)
    asyncio.run(entity.async_play_media("music", "media-source://tts/cloud?message=private", extra={"title": "Door"}))
    assert calls == ["media-source://tts/cloud?message=private"]
    registry.transfers.offer.assert_called_once_with("pixel", b"ID3", "audio/mpeg")
    command = registry.dispatch.call_args.args[1]
    assert command["transfer_id"] == "t" * 22 and command["announce"] is True
    assert command["media_title"] == "Door" and len(command["request_id"]) == 16
    assert "media_content_id" not in command


@pytest.mark.parametrize("audio", [ValueError("private message"), TimeoutError(), ("txt", b"x"), (None, b"x")])
def test_tts_render_failure_falls_back_to_url_delivery(platform, monkeypatch, audio, caplog):
    install_tts(monkeypatch, audio)
    source = sys.modules["homeassistant.components.media_source"]

    async def resolve(hass, media_id, target_media_player):
        return types.SimpleNamespace(url="/api/tts_proxy/opaque.mp3", mime_type="audio/mpeg")
    source.async_resolve_media = resolve
    platform.get_url = Mock(return_value="https://remote.ui.nabu.casa")
    transfer = transfer_module_or_stub()
    registry, entity = transfer_registry(platform, offer=Mock(side_effect=transfer.TransferUnavailable))
    caplog.set_level("DEBUG")
    asyncio.run(entity.async_play_media("music", "media-source://tts/cloud?message=private"))
    command = registry.dispatch.call_args.args[1]
    assert command["media_content_id"] == "https://remote.ui.nabu.casa/api/tts_proxy/opaque.mp3"
    assert "transfer_id" not in command and len(command["request_id"]) == 16
    assert "private" not in caplog.text and "opaque" not in caplog.text


def transfer_module_or_stub():
    return types.SimpleNamespace(TransferUnavailable=LookupError)


def test_undeliverable_transfer_is_dropped_not_retried_by_url(platform, monkeypatch):
    install_tts(monkeypatch, ("mp3", b"ID3"))
    registry, entity = transfer_registry(platform)
    registry.dispatch.return_value = False
    asyncio.run(entity.async_play_media("music", "media-source://tts/cloud?message=x"))
    registry.dispatch.assert_called_once()
    registry.transfers.drop_device.assert_called_once_with("pixel")


def test_legacy_device_keeps_url_delivery_without_request_ids(platform, monkeypatch):
    calls = install_tts(monkeypatch, ("mp3", b"ID3"))
    registry, entity = transfer_registry(platform, features=())
    asyncio.run(entity.async_play_media("music", "https://example.com/a.mp3"))
    assert registry.dispatch.call_args.args[1] == {"action": "play_media", "media_content_type": "music",
                                                   "media_content_id": "https://example.com/a.mp3", "announce": False}
    assert calls == []


def test_delivery_attributes_are_exposed_without_urls(platform):
    registry, entity = transfer_registry(platform)
    assert entity.extra_state_attributes is None
    registry.players["pixel"].delivery = {"request_id": "r" * 16, "phase": "playing", "method": "direct"}
    assert entity.extra_state_attributes == {"delivery_phase": "playing", "delivery_method": "direct",
                                             "delivery_request_id": "r" * 16}
