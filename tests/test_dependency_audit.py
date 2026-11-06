"""Advisory results must cover the complete resolved inventory and fail closed."""
import importlib.util
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "audit_runtime_dependencies.py"
SPEC = importlib.util.spec_from_file_location("dependency_audit", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
QUERY = [{"package": {"name": "com.google.guava:guava", "ecosystem": "Maven"}, "version": "33.7.2-android"}]


def test_clean_complete_result_passes():
    assert MODULE.audit(QUERY, lambda _: {"results": [{}]}) == []


def test_any_advisory_is_reported_without_severity_exclusions():
    assert MODULE.audit(QUERY, lambda _: {"results": [{"vulns": [{"id": "GHSA-fixture"}]}]}) == [
        "com.google.guava:guava:33.7.2-android — GHSA-fixture"]


@pytest.mark.parametrize("result", [None, {}, {"results": []}, {"results": [None]},
    {"results": [{"error": "unavailable"}]}, {"results": [{"vulns": None}]},
    {"results": [{"vulns": [{}]}]}, {"results": [{"vulns": [None]}]}])
def test_missing_malformed_or_partial_results_fail_closed(result):
    with pytest.raises(ValueError):
        MODULE.audit(QUERY, lambda _: result)


@pytest.mark.parametrize("query", [None, [], [{}], [{"package": {}, "version": "1"}],
    [{"package": {"name": "fixture", "ecosystem": "Maven"}, "version": ""}]])
def test_invalid_or_empty_inventory_fails_closed(query):
    with pytest.raises(ValueError):
        MODULE.audit(query, lambda _: {"results": [{}]})


def test_lookup_failure_propagates_instead_of_passing():
    def unavailable(_):
        raise OSError("fixture unavailable")
    with pytest.raises(OSError):
        MODULE.audit(QUERY, unavailable)
