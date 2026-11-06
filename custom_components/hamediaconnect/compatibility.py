"""Enforce the supported Home Assistant version, including manual installations."""
from __future__ import annotations

import logging

from awesomeversion import AwesomeVersion, AwesomeVersionException
from homeassistant.const import __version__ as HA_VERSION
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers import issue_registry as ir

from .const import DOMAIN, MIN_HA_VERSION

_LOGGER = logging.getLogger(__name__)
ISSUE_ID = "unsupported_ha_version"


def version_placeholders() -> dict[str, str]:
    """Use the same version details in the setup dialog and the repair banner."""
    return {"current_version": HA_VERSION, "required_version": MIN_HA_VERSION}


@callback
def async_check_ha_version(hass: HomeAssistant) -> bool:
    """Fail closed before registering any endpoint or loading player entities."""
    try:
        supported = AwesomeVersion(HA_VERSION) >= AwesomeVersion(MIN_HA_VERSION)
    except AwesomeVersionException:
        supported = False
    if supported:
        ir.async_delete_issue(hass, DOMAIN, ISSUE_ID)
        return True
    _LOGGER.error(
        "HAMediaConnect requires Home Assistant Core %s or newer; this server is running %s. "
        "Upgrade Home Assistant Core and restart Home Assistant. Integration setup is blocked.",
        MIN_HA_VERSION, HA_VERSION,
    )
    ir.async_create_issue(
        hass, DOMAIN, ISSUE_ID,
        is_fixable=False,
        severity=ir.IssueSeverity.ERROR,
        translation_key=ISSUE_ID,
        translation_placeholders=version_placeholders(),
        learn_more_url="https://github.com/emarrero-personal/HAMediaConnect#install-home-assistant-integration",
    )
    return False
