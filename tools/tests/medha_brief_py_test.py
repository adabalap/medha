#!/usr/bin/env python3
"""
Exercises samples/medha_brief.py against a REAL local HTTP server.

Unlike every other test in this project, this one makes actual network
calls: a stdlib http.server stands in for Medha on a loopback port, and the
client talks to it over TCP. That means urllib behaviour, header casing,
status handling, timeouts and JSON round-tripping are executed rather than
stubbed -- the strongest check available here, and the one most likely to
catch something a mock would paper over.

Run: python3 tools/tests/medha_brief_py_test.py
"""
import json
import os
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

sys.path.insert(
    0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "samples")
)
from medha_brief import Medha, MedhaError  # noqa: E402

failures = 0
checks = 0


def check(what, ok):
    global checks, failures
    checks += 1
    if ok:
        print(f"  ok    {what}")
    else:
        print(f"  FAIL  {what}")
        failures += 1


def eq(what, actual, expected):
    global checks, failures
    checks += 1
    if actual == expected:
        print(f"  ok    {what}")
    else:
        print(f"  FAIL  {what}\n          expected: {expected!r}\n          actual:   {actual!r}")
        failures += 1


def raises(what, fn, code=None):
    global checks, failures
    checks += 1
    try:
        fn()
    except MedhaError as e:
        if code and e.code != code:
            print(f"  FAIL  {what} (code was {e.code!r}, expected {code!r})")
            failures += 1
        else:
            print(f"  ok    {what}")
    except Exception as e:
        print(f"  FAIL  {what} (raised {type(e).__name__}: {e})")
        failures += 1
    else:
        print(f"  FAIL  {what} (did not raise)")
        failures += 1


# --------------------------------------------------------------- the stub

class State:
    """Scripted replies and a log of what arrived."""
    script = []
    calls = []
    delay = 0.0


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass  # the test's own output is the log

    def handle_one_request(self):
        # The timeout test hangs up while this handler is still sleeping, so
        # the reply write fails with BrokenPipeError. That is the behaviour
        # under test, not a problem -- swallow it so the traceback does not
        # bury the results.
        try:
            super().handle_one_request()
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True

    def _serve(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        State.calls.append({
            "method": self.command,
            "path": self.path,
            "headers": {k.lower(): v for k, v in self.headers.items()},
            "body": json.loads(raw) if raw else None,
        })

        if State.delay:
            time.sleep(State.delay)

        reply = State.script.pop(0) if State.script else {"status": 200, "body": {}}
        status = reply.get("status", 200)
        payload = reply.get("body")
        blob = b"" if reply.get("no_body") else json.dumps(payload or {}).encode()

        self.send_response(status)
        for k, v in (reply.get("headers") or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(blob)))
        self.end_headers()
        if blob:
            self.wfile.write(blob)

    do_GET = do_PUT = do_POST = do_DELETE = _serve


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def main():
    port = free_port()
    server = HTTPServer(("127.0.0.1", port), Handler)
    # Same reason: a client that times out and disconnects is expected here.
    server.handle_error = lambda *a: None
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()

    m = Medha(token="TOK", port=port, timeout=2.0)

    def script(*replies):
        State.script = list(replies)
        State.calls = []

    try:
        # ------------------------------------------------------ construction
        print("\nmedha_brief.py -- construction")
        # A missing token is a configuration mistake, not a request
        # failure, so it is a plain ValueError at construction rather than
        # a MedhaError on the first call -- it should fail at process start,
        # not at the first widget update hours later.
        try:
            Medha(token="", port=port)
        except ValueError:
            check("an empty token raises ValueError at construction", True)
        except Exception as e:
            check(f"an empty token raises ValueError (got {type(e).__name__})", False)
        else:
            check("an empty token is refused at all", False)

        # ------------------------------------------------------------ brief
        print("\nmedha_brief.py -- post_brief")
        script({"body": {"ok": True, "items": 2, "pageRows": 3, "maxRows": 12}})
        r = m.post_brief(
            [{"title": "hg_agent", "text": "412 requests"}, {"title": "p50", "text": "840ms"}],
            ttl_seconds=3600,
        )
        c = State.calls[0]
        eq("PUTs to /widget/content", (c["method"], c["path"]), ("PUT", "/widget/content"))
        eq("sends the bearer token", c["headers"].get("authorization"), "Bearer TOK")
        eq("sends JSON content type", c["headers"].get("content-type"), "application/json")
        eq("ttlSeconds passed through", c["body"]["ttlSeconds"], 3600)
        eq("two items sent", len(c["body"]["items"]), 2)
        eq("returns the kept count, not the sent count", r["items"], 2)
        eq("and both row numbers", (r["pageRows"], r["maxRows"]), (3, 12))

        print("\nmedha_brief.py -- items are normalised before the request")
        script({"body": {"ok": True, "items": 5}})
        m.post_brief([
            {"title": "  spaced  ", "text": "  padded  "},
            {"title": ""},             # dropped
            {"title": "   "},          # dropped
            None,                      # dropped
            "not a dict",              # dropped
            {"title": "a"}, {"title": "b"}, {"title": "c"},
            {"title": "d"}, {"title": "e"},
        ])
        sent = State.calls[0]["body"]["items"]
        eq("empties and non-dicts dropped", len(sent), 5)
        eq("whitespace trimmed", sent[0], {"title": "spaced", "text": "padded"})
        eq("capped at MAX_ITEMS keeping the first valid five",
           [i["title"] for i in sent], ["spaced", "a", "b", "c", "d"])

        script({"body": {"ok": True, "items": 1}})
        m.post_brief([{"title": "x" * 200, "text": "y" * 500}])
        sent = State.calls[0]["body"]["items"][0]
        eq("title capped at 80", len(sent["title"]), 80)
        eq("text capped at 200", len(sent["text"]), 200)

        script({"body": {"ok": True, "items": 0}})
        m.post_brief([])
        eq("an empty list is a withdrawal, not an error",
           State.calls[0]["body"]["items"], [])

        raises("a non-list is refused locally", lambda: m.post_brief("nope"), "bad_request")

        print("\nmedha_brief.py -- read and clear")
        script({"body": {"pageRows": 3, "maxRows": 12, "contributors": 2, "sections": []}})
        got = m.read_brief()
        eq("GETs /widget/content", State.calls[0]["method"], "GET")
        eq("parses the reply", got["contributors"], 2)

        script({"body": {"cleared": True}})
        m.clear_brief()
        eq("DELETEs /widget/content", State.calls[0]["method"], "DELETE")

        # ---------------------------------------------------- notifications
        print("\nmedha_brief.py -- notify")
        script({"body": {"posted": True, "id": "svc"}})
        m.notify(title="hg_agent down", text="runit restarted it", id="svc")
        b = State.calls[0]["body"]
        eq("POSTs to /notify", State.calls[0]["path"], "/notify")
        eq("id carried", b["id"], "svc")
        eq("silent defaults true", b["silent"], True)
        eq("absent progress sent as -1", (b["progressCurrent"], b["progressMax"]), (-1, -1))

        script({"body": {"posted": True}})
        m.notify(title="Sync", ongoing=True, progress_current=412, progress_max=900)
        b = State.calls[0]["body"]
        eq("ongoing carried", b["ongoing"], True)
        eq("progress carried", (b["progressCurrent"], b["progressMax"]), (412, 900))

        raises("title is required", lambda: m.notify(title=""), "bad_request")

        # posted:false is a SUCCESS carrying bad news.
        script({"body": {"posted": False, "reason": "notifications are disabled for Medha"}})
        r = m.notify(title="x")
        check("posted:false returns rather than raising", r["posted"] is False)
        check("and carries the reason", "disabled" in r["reason"])

        script({"body": {"cancelled": True}})
        m.cancel_notification("a/b")
        eq("cancel id is URL-encoded", State.calls[0]["path"], "/notify/a%2Fb")

        # -------------------------------------------------------- health
        print("\nmedha_brief.py -- health needs no token")
        script({"body": {"status": "ok", "modelLoaded": False, "backend": "GPU"}})
        h = m.health()
        check("no Authorization header sent",
              "authorization" not in State.calls[0]["headers"])
        check("works with modelLoaded false -- the brief needs no model",
              h["modelLoaded"] is False)

        # -------------------------------------------------------- errors
        print("\nmedha_brief.py -- errors name the cause")
        script({"status": 403, "body": {"error": "lacks capability 'notify'", "code": "forbidden"}})
        try:
            m.notify(title="x")
        except MedhaError as e:
            check("403 forbidden keeps its code", e.code == "forbidden")
            check("and says how to fix it", "Post notifications" in str(e))
            check("and carries the status", e.status == 403)
        else:
            check("403 raises at all", False)

        script({"status": 429,
                "body": {"error": "rate limit reached", "code": "rate_limited"},
                "headers": {"Retry-After": "7"}})
        try:
            m.notify(title="x")
        except MedhaError as e:
            check("429 surfaces Retry-After", "retry in 7s" in str(e))
            check("with the rate_limited code", e.code == "rate_limited")
        else:
            check("429 raises at all", False)

        script({"status": 401, "body": {"error": "unauthorized", "code": "unauthorized"}})
        try:
            m.read_brief()
        except MedhaError as e:
            check("401 explains a revoked or wrong token", "revoked" in str(e))
        else:
            check("401 raises at all", False)

        # An error with no JSON body must not mask the status.
        script({"status": 500, "no_body": True})
        try:
            m.read_brief()
        except MedhaError as e:
            check("a bodyless 500 still reports its status", e.status == 500)
            check("and a code derived from it, not a flat 'unknown'",
                  e.code == "http_500")
        else:
            check("500 raises at all", False)

        script({"status": 403, "no_body": True})
        try:
            m.read_brief()
        except MedhaError as e:
            check("a bodyless 403 derives its code too", e.code == "http_403")
        else:
            check("403 raises at all", False)

        # A 200 with a non-JSON body.
        print("\nmedha_brief.py -- a success with no body is not a crash")
        script({"status": 200, "no_body": True})
        eq("empty 200 body parses as {}", m.read_brief(), {})

        # ------------------------------------------------------- timeouts
        print("\nmedha_brief.py -- a slow server does not hang the caller")
        # The whole reason there is a default timeout: a Flask worker blocked
        # on a wedged socket is a worker serving nobody, and urllib waits
        # forever by default.
        slow = Medha(token="TOK", port=port, timeout=0.4)
        State.delay = 1.5
        script({"body": {"ok": True}})
        started = time.monotonic()
        raises("times out rather than blocking", lambda: slow.read_brief(), "unreachable")
        elapsed = time.monotonic() - started
        check(f"returned in {elapsed:.2f}s, well under the 1.5s server delay", elapsed < 1.2)
        State.delay = 0.0

        print("\nmedha_brief.py -- unreachable port")
        dead = Medha(token="TOK", port=free_port(), timeout=1.0)
        raises("a closed port is 'unreachable'", lambda: dead.health(), "unreachable")
        try:
            dead.health()
        except MedhaError as e:
            check("and the message lists the likely causes", "Is Medha running" in str(e))
            check("including the Termux-specific one", "Termux" in str(e))
        else:
            check("a dead port raises at all", False)

        print("\nmedha_brief.py -- post_brief_quietly never raises")
        # The common Flask mistake: letting a widget update turn a working
        # response into a 500.
        check("returns False against a dead server",
              dead.post_brief_quietly([{"title": "x"}]) is False)
        script({"body": {"ok": True, "items": 1}})
        check("and True when it works",
              m.post_brief_quietly([{"title": "x"}]) is True)

    finally:
        server.shutdown()

    print("\n" + "-" * 50)
    if failures == 0:
        print(f"medha_brief_py_test: all {checks} checks passed")
        return 0
    print(f"medha_brief_py_test: {failures} of {checks} checks FAILED")
    return 1


if __name__ == "__main__":
    # An unexpected exception must report as a failure naming the section it
    # died in, not as a bare traceback. Mutation testing produced exactly
    # that: a broken post_brief_quietly killed the run and the output said
    # nothing about which behaviour had regressed.
    try:
        sys.exit(main())
    except Exception as exc:  # noqa: BLE001 -- the point is to catch anything
        print("\n" + "-" * 50)
        print("  FAIL  the suite threw before finishing -- a call that should")
        print("        have succeeded did not. Last completed check above.")
        print(f"        {type(exc).__name__}: {exc}")
        print(f"medha_brief_py_test: ABORTED after {checks} checks "
              f"({failures} already failed)")
        sys.exit(1)
