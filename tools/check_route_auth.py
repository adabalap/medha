#!/usr/bin/env python3
"""
Asserts that every HTTP route in LocalServer.kt is either deliberately
public or actually checks a capability.

Why this exists
---------------
Adding an endpoint is three lines and forgetting the authorization check is
one of them. The failure is silent and total: the route works, the tests
pass, and any process on the device can call it without a token.

Medha has two independent gates, and this checks the second:

  1. The pipeline interceptor rejects anything whose path is not in
     isPublic(). That is a path whitelist, so a NEW route is protected by
     default -- this checker's job is not to re-verify it.
  2. `call.requireCap(...)` inside the handler, which decides WHICH client
     may call it. Forgetting this means any paired client -- including a web
     app granted only notification rights -- can run the model, read other
     collections, or manage Medha.

Gate 2 is the one with no default, hence this file.

Not a parser. It brace-matches route blocks out of the source, which is
enough for a file written in one consistent style and fails closed when it
is not: an unparseable route is an error, never a pass.

Run: python3 -I tools/check_route_auth.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / "app" / "src" / "main" / "java" / "com" / "adabala" / "medha" / "LocalServer.kt"

# A route's path is a literal or a constant (post(PAIR_PATH)).
ROUTE_RE = re.compile(
    r'\b(get|post|put|delete|patch|head)\s*\(\s*(?:"([^"]*)"|([A-Z_][A-Z0-9_]*))\s*\)\s*\{'
)
CONST_RE = re.compile(r'const\s+val\s+([A-Z_][A-Z0-9_]*)\s*=\s*"([^"]*)"')

# Tier 1: served with no token at all. An entry here is a decision to let
# any process on the device call it, so each needs a reason.
PUBLIC = {
    "/health": "capability discovery; a client must be able to ask before it has a token",
    "/": "the bundled demo page",
    "/pair": "obtaining a token is the entire point; guarded by a 6-digit code that "
             "burns after five wrong guesses",
}

# Tier 2: gate 1 only. A valid token is required (the interceptor enforces
# that for any path not in isPublic) but no particular capability, so any
# paired client may call it.
#
# Everything here must be READ-ONLY and free of per-client data. A route
# that returns one client's data, or spends real compute, belongs in tier 3
# -- that is exactly what /rag/reindex was doing before v0.11.0, where a
# client paired only for notifications could name another client's
# collection and get its chunk counts back.
TOKEN_ONLY = {
    "/system": "device, thermal and model diagnostics; no per-client data",
    "/metrics": "aggregate counters; no per-client data",
    "/scheduler": "queue and thermal state; no per-client data",
    "/v1/models": "lists the single loaded model, which every client shares",
    "/v1/embeddings": "returns 501 unconditionally; there is nothing to authorize",
}

# Calls that constitute an authorization check.
AUTH_CALLS = ("requireCap(", "requireAdmin(", "requireClient(", "resolveClient(")

# Static assets are served by a helper that the interceptor covers by
# suffix; they carry no capability check and should not.
STATIC_HINT = "serveAsset("


def block_at(src: str, open_brace: int) -> tuple[str, int]:
    """
    Returns the text of the brace-delimited block starting at open_brace,
    and the index just past its close.

    Ignores braces inside string literals and line comments, which a naive
    counter trips over immediately -- LocalServer is full of both.
    """
    depth = 0
    i = open_brace
    n = len(src)
    while i < n:
        c = src[i]
        if c == '"':
            # Triple-quoted or ordinary string; skip to its end.
            if src.startswith('"""', i):
                end = src.find('"""', i + 3)
                i = n if end < 0 else end + 3
                continue
            i += 1
            while i < n and src[i] != '"':
                if src[i] == "\\":
                    i += 1
                i += 1
            i += 1
            continue
        if src.startswith("//", i):
            nl = src.find("\n", i)
            i = n if nl < 0 else nl + 1
            continue
        if src.startswith("/*", i):
            end = src.find("*/", i + 2)
            i = n if end < 0 else end + 2
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return src[open_brace : i + 1], i + 1
        i += 1
    raise ValueError(f"unbalanced braces from offset {open_brace}")


def main() -> int:
    if not SERVER.exists():
        print(f"check_route_auth: FAIL — {SERVER} not found", file=sys.stderr)
        return 1

    src = SERVER.read_text(encoding="utf-8")
    consts = {k: v for k, v in CONST_RE.findall(src)}
    failures: list[str] = []
    checked = 0
    public_seen: set[str] = set()
    token_only_seen: set[str] = set()

    for m in ROUTE_RE.finditer(src):
        method = m.group(1)
        if m.group(2) is not None:
            path = m.group(2)
        else:
            name = m.group(3)
            if name not in consts:
                failures.append(
                    f"route declared as {method}({name}) but no "
                    f"`const val {name} = \"...\"` is in this file — cannot verify its auth"
                )
                continue
            path = consts[name]
        brace = src.index("{", m.end() - 1)
        try:
            body, _ = block_at(src, brace)
        except ValueError as e:
            failures.append(f"{method.upper()} {path}: could not parse the handler ({e})")
            continue

        checked += 1
        line = src.count("\n", 0, m.start()) + 1

        if path in PUBLIC:
            public_seen.add(path)
            continue

        if path in TOKEN_ONLY:
            token_only_seen.add(path)
            # A tier-2 route that HAS grown a capability check is not a
            # failure, but the list is now wrong, and a wrong list is how
            # the next reader concludes the route is unprotected.
            if any(call in body for call in AUTH_CALLS):
                failures.append(
                    f"{SERVER.name}:{line}  {method.upper()} {path} is listed as "
                    f"TOKEN_ONLY but now checks a capability — move it out of that list"
                )
            continue

        if STATIC_HINT in body and len(body) < 400:
            continue

        if not any(call in body for call in AUTH_CALLS):
            failures.append(
                f"{SERVER.name}:{line}  {method.upper()} {path} "
                f"has no {' / '.join(AUTH_CALLS)} — add one, or list the path in "
                f"PUBLIC or TOKEN_ONLY in this checker with a reason"
            )

    # A listed path that no longer exists is stale, and a stale whitelist is
    # how a future route silently inherits an exemption nobody reviewed it
    # for -- rename a route to a path already sitting in PUBLIC and it is
    # served without a token.
    for p in PUBLIC:
        if p not in public_seen:
            failures.append(f"PUBLIC lists '{p}' but no route declares it — remove the entry")
    for p in TOKEN_ONLY:
        if p not in token_only_seen:
            failures.append(f"TOKEN_ONLY lists '{p}' but no route declares it — remove the entry")

    if not src.count("fun isPublic("):
        failures.append("isPublic() is gone from LocalServer — gate 1 may have been removed")

    if failures:
        print("check_route_auth: FAIL")
        for f in failures:
            print(f"  {f}")
        return 1

    scoped = checked - len(public_seen) - len(token_only_seen)
    print(
        f"check_route_auth: OK ({checked} routes — "
        f"{len(public_seen)} public, {len(token_only_seen)} token-only, "
        f"{scoped} capability-checked)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
