"""Bounded, device-bound storage for in-band delivery of HA-generated TTS audio.

Audio is produced in-process by Home Assistant's TTS helper, never fetched from a
caller-supplied URL. A transfer is readable only by an authenticated connection
that proved the owning device identity, in fixed-size chunks, until it expires.
"""
from __future__ import annotations

import hashlib
import re
import secrets
import time
from dataclasses import dataclass

CHUNK_SIZE = 48 * 1024  # 64 KiB of Base64 per chunk result.
MAX_TRANSFER_BYTES = 4 * 1024 * 1024
MAX_TOTAL_BYTES = 16 * 1024 * 1024
MAX_TRANSFERS = 8
TRANSFER_TTL = 120.0
TRANSFER_ID = re.compile(r"^[A-Za-z0-9_-]{22}$")
AUDIO_MIME = re.compile(r"^audio/[a-z0-9.+-]{1,64}$")


class TransferUnavailable(LookupError):
    """Unknown, expired, foreign or out-of-range transfer request."""


@dataclass(frozen=True)
class Transfer:
    transfer_id: str
    device_id: str
    data: bytes
    mime: str
    sha256: str
    expires: float

    def offer(self) -> dict:
        """Command fields announced to the owning device."""
        return {
            "transfer_id": self.transfer_id,
            "media_size": len(self.data),
            "media_sha256": self.sha256,
            "chunk_size": CHUNK_SIZE,
            "media_content_type": self.mime,
        }


class TransferStore:
    """At most one live transfer per device, bounded in count, bytes and time."""

    def __init__(self, clock=time.monotonic) -> None:
        self.clock = clock
        self.transfers: dict[str, Transfer] = {}

    def _prune(self) -> None:
        now = self.clock()
        self.transfers = {k: v for k, v in self.transfers.items() if v.expires > now}

    def offer(self, device_id: str, data: bytes, mime: str) -> Transfer:
        if not isinstance(data, (bytes, bytearray)) or not 0 < len(data) <= MAX_TRANSFER_BYTES:
            raise TransferUnavailable("Unsupported audio size")
        if not isinstance(mime, str) or not AUDIO_MIME.match(mime):
            raise TransferUnavailable("Unsupported audio type")
        self._prune()
        self.drop_device(device_id)  # A newer announcement supersedes the old one.
        if (len(self.transfers) >= MAX_TRANSFERS
                or sum(len(t.data) for t in self.transfers.values()) + len(data) > MAX_TOTAL_BYTES):
            raise TransferUnavailable("Transfer capacity exhausted")
        data = bytes(data)
        transfer = Transfer(secrets.token_urlsafe(16), device_id, data, mime,
                            hashlib.sha256(data).hexdigest(), self.clock() + TRANSFER_TTL)
        self.transfers[transfer.transfer_id] = transfer
        return transfer

    def _get(self, device_id: str, transfer_id: str) -> Transfer:
        self._prune()
        transfer = self.transfers.get(transfer_id) if TRANSFER_ID.match(transfer_id or "") else None
        # Another device's transfer is indistinguishable from a missing one.
        if transfer is None or not secrets.compare_digest(transfer.device_id, device_id):
            raise TransferUnavailable("Transfer unavailable")
        return transfer

    def chunk(self, device_id: str, transfer_id: str, offset: int) -> bytes:
        transfer = self._get(device_id, transfer_id)
        if isinstance(offset, bool) or not isinstance(offset, int) or offset < 0 \
                or offset % CHUNK_SIZE or offset >= len(transfer.data):
            raise TransferUnavailable("Invalid offset")
        return transfer.data[offset:offset + CHUNK_SIZE]

    def release(self, device_id: str, transfer_id: str) -> None:
        transfer = self._get(device_id, transfer_id)  # Prunes, replacing the dict; pop afterward.
        self.transfers.pop(transfer.transfer_id, None)

    def drop_device(self, device_id: str) -> None:
        self.transfers = {k: v for k, v in self.transfers.items() if v.device_id != device_id}
