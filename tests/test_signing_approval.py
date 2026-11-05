"""Negative tests for the production-signing environment preflight."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys

import pytest

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "verify_signing_approval.py"
SPEC = importlib.util.spec_from_file_location("signing_approval", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def environment(kind="User", reviewer_id=123):
    return {"protection_rules": [{"type": "required_reviewers", "reviewers": [
        {"type": kind, "reviewer": {"id": reviewer_id}}]}]}


@pytest.mark.parametrize("payload", [None, [], {}, {"protection_rules": None},
    {"protection_rules": [{}]}, {"protection_rules": [None]},
    {"protection_rules": [{"type": "branch_policy"}]},
    {"protection_rules": [{"type": "required_reviewers", "reviewers": []}]},
    {"protection_rules": [{"type": "required_reviewers", "reviewers": None}]},
    {"protection_rules": [{"type": "required_reviewers", "reviewers": [None]}]},
    environment("Repository"), environment(reviewer_id=True), environment(reviewer_id=0),
    environment(reviewer_id="123")])
def test_missing_or_malformed_reviewer_protection_fails_closed(payload):
    assert not MODULE.has_required_reviewers(payload)


@pytest.mark.parametrize("kind", ["User", "Team"])
def test_configured_reviewers_allow_the_environment_to_gate_signing(kind):
    assert MODULE.has_required_reviewers(environment(kind))


@pytest.mark.parametrize("body", ["not-json", "", json.dumps({"protection_rules": [{"type": "branch_policy"}]})])
def test_command_rejects_invalid_or_branch_only_environment_without_dumping_input(body):
    result = subprocess.run([sys.executable, str(SCRIPT)], input=body, text=True, capture_output=True, check=False)
    assert result.returncode == 1
    assert result.stderr == "Production signing requires a configured required reviewer; refusing to sign.\n"


def test_command_accepts_required_reviewers():
    result = subprocess.run([sys.executable, str(SCRIPT)], input=json.dumps(environment()), text=True, capture_output=True, check=False)
    assert result.returncode == 0
