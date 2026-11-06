"""Check exact resolved Android runtime versions against OSV; errors fail closed."""
from __future__ import annotations

import json
from pathlib import Path
import sys
from urllib.request import Request, urlopen

MAX_RESPONSE = 5 * 1024 * 1024


def query_osv(queries: list) -> dict:
    request = Request("https://api.osv.dev/v1/querybatch",
                      data=json.dumps({"queries": queries}).encode(),
                      headers={"Content-Type": "application/json"}, method="POST")
    with urlopen(request, timeout=30) as response:
        body = response.read(MAX_RESPONSE + 1)
    if len(body) > MAX_RESPONSE:
        raise ValueError("Advisory response too large")
    return json.loads(body)


def audit(queries: object, lookup=query_osv) -> list[str]:
    if not isinstance(queries, list) or not 0 < len(queries) <= 1000:
        raise ValueError("Resolved inventory required")
    for query in queries:
        if (not isinstance(query, dict) or not isinstance(query.get("package"), dict)
                or query["package"].get("ecosystem") != "Maven"
                or not isinstance(query["package"].get("name"), str)
                or not isinstance(query.get("version"), str) or not query["version"]):
            raise ValueError("Invalid resolved dependency")
    response = lookup(queries)
    results = response.get("results") if isinstance(response, dict) else None
    if not isinstance(results, list) or len(results) != len(queries):
        raise ValueError("Incomplete advisory response")
    findings = []
    for query, result in zip(queries, results, strict=True):
        if not isinstance(result, dict) or result.get("error"):
            raise ValueError("Advisory query failed")
        vulns = result.get("vulns", [])
        if not isinstance(vulns, list):
            raise ValueError("Malformed advisory response")
        for vuln in vulns:
            if not isinstance(vuln, dict) or not isinstance(vuln.get("id"), str) or not vuln["id"]:
                raise ValueError("Malformed advisory identifier")
            findings.append(f"{query['package']['name']}:{query['version']} — {vuln['id']}")
    return findings


def main() -> int:
    try:
        queries = json.loads(Path(sys.argv[1]).read_text())
        findings = audit(queries)
    except Exception:
        print("Runtime dependency audit unavailable or invalid; refusing to pass.", file=sys.stderr)
        return 1
    if findings:
        print("Affected runtime dependencies:", *findings, sep="\n", file=sys.stderr)
        return 1
    print(f"No OSV advisories matched {len(queries)} resolved runtime modules.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
