"""In-memory Android player registry and command subscription transport."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable

from homeassistant.core import HomeAssistant, callback

from .const import DOMAIN


@dataclass
class Player:
    """An authenticated Android client and its reported playback state."""

    device_id: str
    name: str
    owner_id: str
    state: dict[str, Any] = field(default_factory=dict)
    send_command: Callable[[dict[str, Any]], None] | None = None


class PlayerRegistry:
    """Keep player state and active WS subscriptions scoped to an HA process."""

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass
        self.players: dict[str, Player] = {}

    @callback
    def register(self, device_id: str, name: str, owner_id: str) -> Player:
        """Register a device, rejecting takeover by another HA user."""
        player = self.players.get(device_id)
        if player is None:
            player = Player(device_id=device_id, name=name, owner_id=owner_id)
            self.players[device_id] = player
        elif player.owner_id != owner_id:
            raise PermissionError("Device belongs to another user")
        else:
            player.name = name
        self.hass.bus.async_fire(f"{DOMAIN}_registered", {"device_id": device_id})
        return player

    @callback
    def update(self, player: Player, state: dict[str, Any]) -> None:
        """Accept only known state fields, never arbitrary HA entity changes."""
        player.state.update(state)
        self.hass.bus.async_fire(f"{DOMAIN}_state", {"device_id": player.device_id})

    @callback
    def dispatch(self, device_id: str, command: dict[str, Any]) -> bool:
        """Send an outbound command only to an actively subscribed device."""
        player = self.players.get(device_id)
        if player is None or player.send_command is None:
            return False
        player.send_command(command)
        return True
