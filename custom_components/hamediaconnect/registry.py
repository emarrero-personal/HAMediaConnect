"""Persistent public-key identities and bounded, connection-scoped transport."""
from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import math
import secrets
import time
from collections import deque
from dataclasses import dataclass, field, replace
from typing import Any, Callable

import voluptuous as vol

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from homeassistant.core import HomeAssistant, callback

from .const import DOMAIN, FEATURE_REPORT, FEATURE_TRANSFER
from .transfer import CHUNK_SIZE, MAX_TRANSFER_BYTES, TransferStore

MAX_MESSAGE = 16384
MAX_DEVICES = 512
MAX_SESSIONS = 256
SESSION_KEY = "hamediaconnect_identity"
FEATURES = (FEATURE_TRANSFER, FEATURE_REPORT)
PHASES = ("received", "retrieving", "buffering", "ready", "playing", "completed", "failed", "suppressed")
REASONS = ("unreachable", "dns", "tls", "timeout", "access_denied", "missing", "server_error", "redirect",
           "content_type", "decode", "integrity", "too_large", "interrupted", "superseded", "stopped",
           "dnd", "unavailable", "other")
REQUEST_ID = r"^[A-Za-z0-9_-]{16}$"


COMMAND_SCHEMA = vol.Schema({
    vol.Required("action"): vol.In(["play", "pause", "stop", "play_media", "volume_set", "volume_mute"]),
    vol.Optional("media_content_id"): vol.All(str, vol.Length(min=1, max=2048)),
    vol.Optional("media_title"): vol.All(str, vol.Length(min=1, max=512)),
    vol.Optional("media_content_type"): vol.All(str, vol.Length(min=1, max=128)),
    vol.Optional("volume_level"): vol.All(vol.Any(int, float), vol.Range(min=0, max=1)),
    vol.Optional("is_volume_muted"): bool,
    vol.Optional("announce"): bool,
    vol.Optional("request_id"): vol.All(str, vol.Match(REQUEST_ID)),
    vol.Optional("transfer_id"): vol.All(str, vol.Match(r"^[A-Za-z0-9_-]{22}$")),
    vol.Optional("media_size"): vol.All(int, vol.Range(min=1, max=MAX_TRANSFER_BYTES)),
    vol.Optional("media_sha256"): vol.All(str, vol.Match(r"^[a-f0-9]{64}$")),
    vol.Optional("chunk_size"): vol.All(int, vol.In([CHUNK_SIZE])),
}, extra=vol.PREVENT_EXTRA)
TRANSFER_FIELDS = {"transfer_id", "media_size", "media_sha256", "chunk_size"}


def valid_command(command):
    try:
        COMMAND_SCHEMA(command)
        volume = command.get("volume_level", 0)
        if isinstance(volume, bool) or not math.isfinite(volume):
            return False
        transfer = TRANSFER_FIELDS & command.keys()
        if command["action"] == "play_media":
            # Exactly one delivery method: a URL or a complete in-band transfer offer.
            if transfer:
                return transfer == TRANSFER_FIELDS and "media_content_id" not in command \
                    and not isinstance(command["media_size"], bool)
            return "media_content_id" in command
        if transfer or "request_id" in command:
            return False
        required = {"volume_set": "volume_level", "volume_mute": "is_volume_muted"}
        field = required.get(command["action"])
        return field is None or field in command
    except (vol.Invalid, TypeError):
        return False


def public_identity(encoded):
    """Accept only canonical DER SubjectPublicKeyInfo P-256 keys."""
    raw = base64.b64decode(encoded, validate=True)
    if base64.b64encode(raw).decode() != encoded:
        raise ValueError("Noncanonical encoding")
    key = serialization.load_der_public_key(raw)
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError("P-256 key required")
    if raw != key.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo):
        raise ValueError("Noncanonical key")
    return hashlib.sha256(raw).hexdigest(), key


@dataclass
class Player:
    device_id: str
    name: str
    owner_id: str
    public_key: str = ""
    revoked: bool = False
    state: dict[str, Any] = field(default_factory=dict)
    send_command: Callable[[dict[str, Any]], None] | None = None
    requests: deque = field(default_factory=lambda: deque(maxlen=8))
    delivery: dict[str, Any] = field(default_factory=dict)

    def supports(self, feature: str) -> bool:
        """Features are negotiated by, and live only as long as, the command subscription."""
        return feature in getattr(self.send_command, "features", ())


@dataclass
class Session:
    owner: str
    challenge: tuple | None = None
    device_id: str | None = None
    cancel: Callable = lambda: None


class RetryableDeviceError(ValueError):
    """Temporary resource exhaustion; device authorization is unchanged."""


class DeviceConnectedError(RuntimeError):
    """A device cannot be forgotten while it is connected; it would re-register at once."""


class PlayerRegistry:
    def __init__(self, hass: HomeAssistant, store=None) -> None:
        self.hass = hass
        self.store = store
        self.players: dict[str, Player] = {}
        self.sessions: dict[Any, Session] = {}
        self.rates: dict[tuple, tuple] = {}
        self.lock = asyncio.Lock()
        self.transfers = TransferStore()

    async def async_load(self):
        data = await self.store.async_load()
        for row in (data or {}).get("devices", []):
            device_id, _ = public_identity(row["public_key"])
            if device_id != row["device_id"] or len(self.players) >= MAX_DEVICES:
                raise ValueError("Invalid identity store")
            self.players[device_id] = Player(**row)

    async def save(self, players):
        if self.store is None:
            raise RuntimeError("Identity persistence unavailable")
        await self.store.async_save({"devices": [
            {k: getattr(p, k) for k in ("device_id", "name", "owner_id", "public_key", "revoked")}
            for p in players.values()
        ]})

    def budget(self, owner, bucket, limit):
        now = time.monotonic()
        self.rates = {k: v for k, v in self.rates.items() if v[0] > now}
        key = (owner, bucket)
        expiry, count = self.rates.get(key, (now + 60, 0))
        if count >= limit or (key not in self.rates and len(self.rates) >= 1024):
            raise RetryableDeviceError("Rate limit")
        self.rates[key] = (expiry, count + 1)

    def session(self, connection):
        if connection in self.sessions:
            return self.sessions[connection]
        if len(self.sessions) >= MAX_SESSIONS:
            raise RetryableDeviceError("Session limit")
        session = self.sessions[connection] = Session(connection.user.id)

        def cleanup():
            session.cancel()
            self.sessions.pop(connection, None)
            if session.device_id:
                player = self.players.get(session.device_id)
                if player and getattr(player.send_command, "connection", None) is connection:
                    player.send_command = None
                    self.hass.bus.async_fire(f"{DOMAIN}_state", {"device_id": player.device_id})
        connection.subscriptions[SESSION_KEY] = cleanup
        def expire():
            # Timer cleanup owns its registration; native disconnect cleanup
            # owns the subscription dictionary while invoking callbacks.
            if self.sessions.get(connection) is session:
                cleanup()
                if connection.subscriptions.get(SESSION_KEY) is cleanup:
                    connection.subscriptions.pop(SESSION_KEY)

        session.cancel = asyncio.get_running_loop().call_later(30, expire).cancel
        return session

    def challenge(self, connection, public_key, name):
        self.budget(connection.user.id, "challenge", 10)
        device_id, _ = public_identity(public_key)
        player = self.players.get(device_id)
        if player and (player.owner_id != connection.user.id or player.revoked):
            raise PermissionError("Identity unavailable")
        session = self.session(connection)
        if session.device_id is not None or session.challenge is not None:
            raise ValueError("Already authenticating")
        payload = f"HAMediaConnect/v1\n{secrets.token_urlsafe(32)}\n{connection.user.id}\n{device_id}"
        session.challenge = (time.monotonic() + 30, payload, device_id, public_key, name)
        return {"payload": payload, "device_id": device_id, "expires_in": 30}

    async def authenticate(self, connection, signature):
        self.budget(connection.user.id, "authenticate", 10)
        session = self.sessions.get(connection)
        if session is None or session.challenge is None:
            raise PermissionError("No challenge")
        challenge, session.challenge = session.challenge, None  # Consume before verification, even on failure.
        expiry, payload, device_id, public_key, name = challenge
        if expiry <= time.monotonic():
            raise RetryableDeviceError("Expired challenge")
        if session.owner != connection.user.id:
            raise PermissionError("Challenge owner mismatch")
        _, key = public_identity(public_key)
        try:
            key.verify(base64.b64decode(signature, validate=True), payload.encode(), ec.ECDSA(hashes.SHA256()))
        except (InvalidSignature, ValueError) as err:
            raise PermissionError("Invalid proof") from err
        async with self.lock:
            player = self.players.get(device_id)
            if player and (player.owner_id != session.owner or player.public_key != public_key or player.revoked):
                raise PermissionError("Identity unavailable")
            if player is None:
                if len(self.players) >= MAX_DEVICES or sum(p.owner_id == session.owner for p in self.players.values()) >= 16:
                    raise ValueError("Device quota")
                player = Player(device_id, name, session.owner, public_key)
                updated = {**self.players, device_id: player}
                await self.save(updated)  # Fail closed: acknowledge only durable enrollment.
                self.players = updated
            elif player.name != name:
                await self.save({**self.players, device_id: replace(player, name=name)})
                player.name = name
            if self.sessions.get(connection) is not session:
                raise RetryableDeviceError("Connection expired")
            session.device_id = device_id
            session.cancel()
        self.hass.bus.async_fire(f"{DOMAIN}_registered", {"device_id": device_id})
        return player

    def owned(self, connection, device_id):
        session = self.sessions.get(connection)
        player = self.players.get(device_id)
        if (session is None or session.device_id != device_id or not player or player.revoked
                or player.owner_id != connection.user.id):
            raise PermissionError("Device proof required")
        return player

    async def revoke(self, device_id):
        async with self.lock:
            player = self.players.get(device_id)
            if player is None:
                raise ValueError("Unknown device")
            updated = {**self.players, device_id: Player(
                device_id, player.name, player.owner_id, player.public_key, True)}
            await self.save(updated)
            self.players = updated
            player.send_command = None
            self.transfers.drop_device(device_id)
            for connection, session in list(self.sessions.items()):
                if session.device_id == device_id:
                    cleanup = connection.subscriptions.pop(SESSION_KEY, None)
                    if cleanup:
                        cleanup()
            self.hass.bus.async_fire(f"{DOMAIN}_state", {"device_id": device_id})

    def connected(self, device_id) -> bool:
        """True while any connection holds a proven session or live subscription for the device."""
        player = self.players.get(device_id)
        return (player is not None and player.send_command is not None) or any(
            session.device_id == device_id for session in self.sessions.values())

    async def forget(self, device_id):
        """Forget a disconnected identity after an administrator deletes its HA device.

        Revoked keys keep their persisted tombstone so they still cannot re-enroll.
        A forgotten, unrevoked key can enroll again by proving possession, which
        creates a fresh owner binding and entity.
        """
        async with self.lock:
            player = self.players.get(device_id)
            if player is None or player.revoked:
                return
            if self.connected(device_id):
                raise DeviceConnectedError("Device is connected")
            updated = {key: value for key, value in self.players.items() if key != device_id}
            await self.save(updated)
            self.players = updated
            self.transfers.drop_device(device_id)

    @callback
    def update(self, player, state):
        player.state.update(state)
        self.hass.bus.async_fire(f"{DOMAIN}_state", {"device_id": player.device_id})

    @callback
    def dispatch(self, device_id, command):
        player = self.players.get(device_id)
        if player is None or player.revoked or player.send_command is None:
            return False
        if not valid_command(command) or len(json.dumps(command).encode()) > MAX_MESSAGE:
            return False
        # Never send fields a client did not negotiate: older apps reject them as protocol errors.
        if ("transfer_id" in command and not player.supports(FEATURE_TRANSFER)) or \
                ("request_id" in command and not player.supports(FEATURE_REPORT)):
            return False
        try:
            self.budget(device_id, "command", 120)
        except ValueError:
            return False
        player.send_command(command)
        if "request_id" in command:
            player.requests.append(command["request_id"])
            player.delivery = {"request_id": command["request_id"], "phase": "dispatched",
                               "method": "direct" if "transfer_id" in command else "url"}
            self.hass.bus.async_fire(f"{DOMAIN}_state", {"device_id": device_id})
        return True

    @callback
    def report(self, connection, device_id, request_id, phase, reason=None):
        """Record a device's playback progress for a request this integration dispatched."""
        player = self.owned(connection, device_id)
        if request_id not in player.requests:
            raise ValueError("Unknown request")
        player.delivery = {"request_id": request_id, "phase": phase, "method": player.delivery.get("method")
                           if player.delivery.get("request_id") == request_id else None}
        if reason is not None:
            player.delivery["reason"] = reason
        self.hass.bus.async_fire(f"{DOMAIN}_playback", {"device_id": device_id, **player.delivery})
        self.hass.bus.async_fire(f"{DOMAIN}_state", {"device_id": device_id})
