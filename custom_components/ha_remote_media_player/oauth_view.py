"""Public OAuth client identification page for the Android native app."""
from aiohttp import web
from homeassistant.components.http import HomeAssistantView

REDIRECT_URI = "haandroidmediaplayer://oauth/callback"
CLIENT_PATH = "/api/ha_remote_media_player/oauth-client"

class OAuthClientView(HomeAssistantView):
    """Expose a fixed, unauthenticated HTML document with no user data."""

    url = CLIENT_PATH
    name = "api:ha_remote_media_player:oauth_client"
    requires_auth = False
    cors_allowed = False

    async def get(self, request: web.Request) -> web.Response:
        """Serve the redirect declaration needed by Home Assistant IndieAuth."""
        html = (
            "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
            "<title>HA Remote Media Player OAuth Client</title>"
            f'<link rel="redirect_uri" href="{REDIRECT_URI}">'
            "</head><body><h1>HA Remote Media Player</h1>"
            "<p>OAuth client identification for the Android media player.</p>"
            "</body></html>"
        )
        return web.Response(
            text=html,
            content_type="text/html",
            charset="utf-8",
            headers={"Cache-Control": "public, max-age=300", "X-Content-Type-Options": "nosniff"},
        )
