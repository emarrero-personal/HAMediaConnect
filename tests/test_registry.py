"""Unit tests for player ownership, state, and command routing.

Run with pytest; these tests stub Home Assistant's core module and do not require
a running HA server. Full HA integration tests are still required.
"""
import importlib.util
import sys
import types
from pathlib import Path
from unittest.mock import Mock, AsyncMock

import pytest

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "custom_components" / "hamediaconnect" / "registry.py"


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
    integration = types.ModuleType("custom_components.hamediaconnect")
    integration.__path__ = [str(MODULE.parent)]
    monkeypatch.setitem(sys.modules, "custom_components", package)
    monkeypatch.setitem(sys.modules, "custom_components.hamediaconnect", integration)
    spec = importlib.util.spec_from_file_location(
        "custom_components.hamediaconnect.registry", MODULE
    )
    module = importlib.util.module_from_spec(spec)
    monkeypatch.setitem(sys.modules, spec.name, module)
    spec.loader.exec_module(module)
    return module


from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa
import base64


def keypair():
    key = ec.generate_private_key(ec.SECP256R1())
    public = base64.b64encode(key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
    return key, public


def proof(key, payload):
    return base64.b64encode(key.sign(payload.encode(), ec.ECDSA(hashes.SHA256()))).decode()


def connection(owner="owner"):
    conn = Mock()
    conn.user.id = owner
    conn.subscriptions = {}
    return conn


@pytest.fixture
def registry(registry_module):
    store = Mock(async_save=AsyncMock(), async_load=AsyncMock(return_value=None))
    return registry_module.PlayerRegistry(Mock(), store)


async def enroll(registry, conn, key=None, public=None):
    if key is None:
        key, public = keypair()
    challenge = registry.challenge(conn, public, "Pixel")
    player = await registry.authenticate(conn, proof(key, challenge["payload"]))
    return player, key, public


async def test_identity_restart_and_same_user_impersonation(registry, registry_module):
    conn = connection()
    player, key, public = await enroll(registry, conn)
    assert len(player.device_id) == 64
    attacker = connection()
    with pytest.raises(PermissionError):
        registry.owned(attacker, player.device_id)
    registry.challenge(attacker, public, "Impostor")
    wrong, _ = keypair()
    with pytest.raises(PermissionError):
        await registry.authenticate(attacker, proof(wrong, registry.sessions[attacker].challenge[1]))
    with pytest.raises(PermissionError):
        registry.challenge(connection("other"), public, "Other owner")
    saved = registry.store.async_save.call_args.args[0]
    registry.store.async_load.return_value = saved
    restarted = registry_module.PlayerRegistry(Mock(), registry.store)
    await restarted.async_load()
    assert not restarted.dispatch(player.device_id, {"action": "play"})
    reconnect = connection()
    again, _, _ = await enroll(restarted, reconnect, key, public)
    assert again.device_id == player.device_id
    assert restarted.owned(reconnect, player.device_id) is again
    assert player.owner_id == "owner"


async def test_challenge_replay_connection_binding_and_expiry(registry, monkeypatch, registry_module):
    conn = connection()
    key, public = keypair()
    challenge = registry.challenge(conn, public, "Pixel")
    signed = proof(key, challenge["payload"])
    with pytest.raises(PermissionError):
        await registry.authenticate(connection(), signed)
    second = connection()
    registry.challenge(second, public, "Pixel")
    with pytest.raises(PermissionError):
        await registry.authenticate(second, signed)
    await registry.authenticate(conn, signed)
    with pytest.raises(PermissionError):
        await registry.authenticate(conn, signed)
    with pytest.raises(ValueError):
        registry.challenge(conn, public, "Pixel")
    expired = connection()
    challenge = registry.challenge(expired, public, "Pixel")
    original = registry_module.time.monotonic()
    monkeypatch.setattr(registry_module.time, "monotonic", lambda: original + 31)
    with pytest.raises(registry_module.RetryableDeviceError):
        await registry.authenticate(expired, proof(key, challenge["payload"]))
    assert registry.sessions[expired].challenge is None
    with pytest.raises(PermissionError):
        await registry.authenticate(expired, proof(key, challenge["payload"]))


async def test_revocation_is_persistent_and_invalidates_live_sessions(registry, registry_module):
    conn = connection()
    player, key, public = await enroll(registry, conn)
    send = Mock()
    send.connection = conn
    player.send_command = send
    await registry.revoke(player.device_id)
    assert conn not in registry.sessions
    assert not registry.dispatch(player.device_id, {"action": "play"})
    with pytest.raises(PermissionError):
        registry.owned(conn, player.device_id)
    with pytest.raises(PermissionError):
        registry.challenge(connection(), public, "Revoked")
    registry.store.async_load.return_value = registry.store.async_save.call_args.args[0]
    restarted = registry_module.PlayerRegistry(Mock(), registry.store)
    await restarted.async_load()
    with pytest.raises(PermissionError):
        restarted.challenge(connection(), public, "Revoked")
    replacement, _, _ = await enroll(restarted, connection())
    assert replacement.device_id != player.device_id
    with pytest.raises(ValueError):
        await registry.revoke("unknown")


async def test_registration_storage_failure_fails_closed(registry):
    registry.store.async_save.side_effect = OSError("disk full")
    with pytest.raises(OSError):
        await enroll(registry, connection())
    assert not registry.players
    assert all(session.device_id is None for session in registry.sessions.values())


async def test_rate_limits_session_quota_and_cleanup(registry, registry_module, monkeypatch):
    conn = connection()
    key, public = keypair()
    registry.challenge(conn, public, "Pixel")
    with pytest.raises(ValueError):
        registry.challenge(conn, public, "Again")
    conn.subscriptions[registry_module.SESSION_KEY]()
    assert not registry.sessions
    assert registry_module.SESSION_KEY in conn.subscriptions
    for _ in range(8):
        c = connection()
        registry.challenge(c, public, "Pixel")
    with pytest.raises(ValueError):
        registry.challenge(connection(), public, "Over quota")
    monkeypatch.setattr(registry_module, "MAX_SESSIONS", len(registry.sessions))
    with pytest.raises(ValueError):
        registry.challenge(connection("different"), public, "Session quota")
    for c in list(registry.sessions):
        c.subscriptions[registry_module.SESSION_KEY]()
    registry.rates = {(str(i), "bucket"): (float("inf"), 1) for i in range(1024)}
    with pytest.raises(ValueError):
        registry.budget("extra", "bucket", 10)


async def test_quotas_bad_keys_and_corrupt_store(registry, registry_module, monkeypatch):
    for public in ("invalid", base64.b64encode(b"nonder").decode()):
        with pytest.raises(ValueError):
            registry_module.public_identity(public)
    for key in (ec.generate_private_key(ec.SECP384R1()), rsa.generate_private_key(65537, 2048)):
        public = base64.b64encode(key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
        with pytest.raises(ValueError):
            registry_module.public_identity(public)
    monkeypatch.setattr(registry_module, "MAX_DEVICES", 0)
    with pytest.raises(ValueError):
        await enroll(registry, connection())
    registry.store.async_load.return_value = {"devices": [{"public_key": keypair()[1], "device_id": "wrong"}]}
    with pytest.raises(ValueError):
        await registry.async_load()


async def test_command_limits_and_state(registry):
    conn = connection()
    player, _, _ = await enroll(registry, conn)
    assert not registry.dispatch("missing", {"action": "stop"})
    assert not registry.dispatch(player.device_id, {"action": "stop"})
    player.send_command = Mock()
    assert not registry.dispatch(player.device_id, {"action": "play_media", "media_content_id": "a" * 20000})
    for _ in range(120):
        assert registry.dispatch(player.device_id, {"action": "pause"})
    assert not registry.dispatch(player.device_id, {"action": "pause"})
    registry.update(player, {"state": "playing"})
    assert player.state["state"] == "playing"


async def test_name_change_requires_proof_and_is_durable(registry):
    conn = connection()
    player, key, public = await enroll(registry, conn)
    other = connection()
    challenge = registry.challenge(other, public, "Renamed")
    assert player.name == "Pixel"
    await registry.authenticate(other, proof(key, challenge["payload"]))
    assert player.name == "Renamed"
    assert registry.store.async_save.call_args.args[0]["devices"][0]["name"] == "Renamed"


def test_incomplete_handshake_timer_cleanup(registry, registry_module, monkeypatch):
    timers = []
    class Loop:
        def call_later(self, seconds, cleanup):
            assert seconds == 30
            timers.append(cleanup)
            return Mock()
    with monkeypatch.context() as patch:
        patch.setattr(registry_module.asyncio, "get_running_loop", lambda: Loop())
        conn = connection()
        registry.challenge(conn, keypair()[1], "Pixel")
    assert len(registry.sessions) == 1
    timers[0]()
    assert not registry.sessions and not conn.subscriptions


async def test_per_user_device_quota(registry, registry_module):
    for index in range(16):
        registry.players[str(index)] = registry_module.Player(str(index), "Existing", "owner")
    with pytest.raises(ValueError):
        await enroll(registry, connection())


@pytest.mark.parametrize("command", [
    {"action": "unknown"}, {"action": "play_media"},
    {"action": "pause", "extra": "data"}, {"action": "volume_set", "volume_level": True},
    {"action": "volume_set", "volume_level": float("nan")},
    {"action": "volume_set", "volume_level": "0.5"},
    {"action": "volume_mute", "is_volume_muted": "true"},
    {"action": "play_media", "media_content_id": "http://ha/audio", "media_content_type": "x" * 129},
])
async def test_outbound_command_schemas(registry, command):
    player, _, _ = await enroll(registry, connection())
    player.send_command = Mock()
    assert not registry.dispatch(player.device_id, command)
    player.send_command.assert_not_called()
