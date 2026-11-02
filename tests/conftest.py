"""Minimal HA module stubs for isolated integration unit tests."""
import importlib.util
import pathlib
import sys
import types
from unittest.mock import MagicMock

import pytest

ROOT = pathlib.Path(__file__).resolve().parents[1]
PACKAGE = "custom_components.ha_remote_media_player"


@pytest.fixture
def load_module(monkeypatch):
    def load(name):
        for package in ("custom_components", PACKAGE):
            module = types.ModuleType(package)
            module.__path__ = [str(ROOT / package.replace(".", "/"))]
            monkeypatch.setitem(sys.modules, package, module)
        for name_ in ("homeassistant", "homeassistant.core"):
            module = types.ModuleType(name_)
            module.__path__ = []
            monkeypatch.setitem(sys.modules, name_, module)
        core = sys.modules["homeassistant.core"]
        core.HomeAssistant = object
        core.callback = lambda fn: fn
        spec = importlib.util.spec_from_file_location(
            f"{PACKAGE}.{name}", ROOT / "custom_components" / "ha_remote_media_player" / f"{name}.py"
        )
        module = importlib.util.module_from_spec(spec)
        monkeypatch.setitem(sys.modules, spec.name, module)
        spec.loader.exec_module(module)
        return module
    return load
