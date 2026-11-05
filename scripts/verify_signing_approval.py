"""Fail closed unless GitHub reports a required reviewer for production signing."""
from __future__ import annotations

import json
import sys


def has_required_reviewers(environment: object) -> bool:
    """Deployment branch restrictions alone are not manual signing approval."""
    if not isinstance(environment, dict):
        return False
    rules = environment.get("protection_rules")
    if not isinstance(rules, list):
        return False
    for rule in rules:
        if not isinstance(rule, dict) or rule.get("type") != "required_reviewers":
            continue
        reviewers = rule.get("reviewers")
        if not isinstance(reviewers, list):
            continue
        for item in reviewers:
            if not isinstance(item, dict) or item.get("type") not in ("User", "Team"):
                continue
            reviewer = item.get("reviewer")
            if isinstance(reviewer, dict) and type(reviewer.get("id")) is int and reviewer["id"] > 0:
                return True
    return False


def main() -> int:
    try:
        environment = json.load(sys.stdin)
    except (ValueError, TypeError):
        environment = None
    if not has_required_reviewers(environment):
        print("Production signing requires a configured required reviewer; refusing to sign.", file=sys.stderr)
        return 1
    print("Production signing has required reviewer protection.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
