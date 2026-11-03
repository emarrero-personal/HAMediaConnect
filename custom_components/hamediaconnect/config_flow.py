"""UI setup for HAMediaConnect."""
from homeassistant import config_entries
from .const import DOMAIN

class HAMediaConnectConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    """Create the single integration entry; devices are added in later versions."""

    VERSION = 1

    async def async_step_user(self, user_input=None):
        """Allow installation from Settings > Devices & services."""
        await self.async_set_unique_id(DOMAIN)
        self._abort_if_unique_id_configured()
        if user_input is not None:
            return self.async_create_entry(title="HAMediaConnect", data={})
        return self.async_show_form(step_id="user")
