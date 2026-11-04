# Disconnect lifecycle investigation and fix

HA 2026.10 `ActiveConnection.async_handle_close` iterates the live subscription
values and clears the dictionary afterwards. HAMediaConnect's session callback
removed `SESSION_KEY` inside that iteration, reproducing `RuntimeError` in the
next iterator step. `ws_subscribe.unsubscribe` itself only clears its own command
sender and fires an event; it is not the dictionary mutation. Newer Core snapshots
items and deletes each entry after invoking it, so self-removal also violates
that ownership pattern. This is an integration callback defect; no Core patch is
needed.

Session disconnect callbacks now leave subscription removal to Core. Timer expiry
and explicit revocation remove their registrations separately. Cleanup remains
idempotent and only clears the sender owned by that connection. Tests exercise
both Core iteration patterns, identity-preserving reconnect, stale cleanup,
expiry, revocation, and unrelated subscription isolation. The lifecycle changes
are committed separately from media delivery.

Reference: https://github.com/home-assistant/core/blob/2026.10.0/homeassistant/components/websocket_api/connection.py
