"""Persistent public-key identities and bounded, connection-scoped transport."""
from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import math
import secrets
import time
from dataclasses import dataclass, field, replace
from typing import Any, Callable

import voluptuous as vol

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from homeassistant.core import HomeAssistant, callback

from .const import DOMAIN

MAX_MESSAGE = 16384
MAX_DEVICES = 512
MAX_SESSIONS = 256
SESSION_KEY = "hamediaconnect_identity"


COMMAND_SCHEMA = vol.Schema({
    vol.Required("action"): vol.In(["play", "pause", "stop", "play_media", "volume_set", "volume_mute"]),
    vol.Optional("media_content_id"): vol.All(str, vol.Length(min=1, max=2048)),
    vol.Optional("media_title"): vol.All(str, vol.Length(min=1, max=512)),
    vol.Optional("media_content_type"): vol.All(str, vol.Length(min=1, max=128)),
    vol.Optional("volume_level"): vol.All(vol.Any(int, float), vol.Range(min=0, max=1)),
    vol.Optional("is_volume_muted"): bool,
    vol.Optional("announce"): bool,
}, extra=vol.PREVENT_EXTRA)


def valid_command(command):
    try:
        COMMAND_SCHEMA(command)
        volume = command.get("volume_level", 0)
        if isinstance(volume, bool) or not math.isfinite(volume):
            return False
        required = {"play_media": "media_content_id", "volume_set": "volume_level", "volume_mute": "is_volume_muted"}
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


@dataclass
class Session:
    owner: str
    challenge: tuple | None = None
    device_id: str | None = None
    cancel: Callable = lambda: None


class RetryableDeviceError(ValueError):
    """Temporary resource exhaustion; device authorization is unchanged."""


class PlayerRegistry:
    def __init__(self, hass: HomeAssistant, store=None) -> None:
        self.hass = hass
        self.store = store
        self.players: dict[str, Player] = {}
        self.sessions: dict[Any, Session] = {}
        self.rates: dict[tuple, tuple] = {}
        self.lock = asyncio.Lock()

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
        if expiry <= time.monotonic() or session.owner != connection.user.id:
            raise PermissionError("Expired challenge")
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
                raise PermissionError("Connection expired")
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
            for connection, session in list(self.sessions.items()):
                if session.device_id == device_id:
                    cleanup = connection.subscriptions.pop(SESSION_KEY, None)
                    if cleanup:
                        cleanup()
            self.hass.bus.async_fire(f"{DOMAIN}_state", {"device_id": device_id})

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
        try:
            self.budget(device_id, "command", 120)
        except ValueError:
            return False
        player.send_command(command)
        return True
