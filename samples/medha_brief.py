"""
medha_brief.py -- a server-side client for Medha's brief and notifications.

For a Python process on the same device: a Flask app, a runit-supervised
service, a cron script. Standard library only -- no requests, no pip, which
matters in Termux where installing a wheel is rarely a one-liner.

    from medha_brief import Medha, MedhaError

    medha = Medha(token=os.environ["MEDHA_TOKEN"], port=8001)
    medha.post_brief([
        {"title": "hg_agent", "text": "412 requests, p50 840ms"},
        {"title": "Context compression", "text": "3.2x, 1.1s saved/req"},
    ])

## Why server-side rather than the JS client

samples/medha-brief.js pairs a *browser origin* and runs in a page. That is
the right tool for a static PWA. It is the wrong tool for something like
hg_agent, for two reasons:

 1. The interesting state -- requests served, latency, whether a supervised
    service is still up -- lives in the Python process, not in the browser.
 2. A browser-only integration only updates the brief while someone has a
    tab open, which defeats the point of a home-screen widget.

So this talks to Medha directly over loopback. No CORS, no pairing code, no
Private Network Access preflight: all three of those exist to govern
*browsers*, and this is not one.

## Getting a token

Not by pairing -- pairing binds a token to a web origin, and a Python
process has none, so Medha refuses it (`no_origin`). Instead:

    Medha app -> drawer -> API clients -> Add client
      id: hg-agent
      tick "Post notifications"
      -> copy the token

Put it somewhere the process can read and a repo cannot:

    # ~/.config/hg_agent/env, sourced by the runit run script
    export MEDHA_TOKEN=...

A token made this way has no bound origin, so Medha applies no origin check
to it. Treat it as a credential: it can post to the user's home screen.
"""

from __future__ import annotations

import json
import socket
import urllib.error
import urllib.request

__all__ = ["Medha", "MedhaError"]

DEFAULT_PORT = 8001

#: Seconds. Deliberately short and applied to every call.
#:
#: These endpoints are fast -- they touch SharedPreferences, not the model --
#: but a Flask worker blocked on a wedged socket is a worker not serving
#: anyone, and urllib's default is to wait indefinitely. If you also call
#: Medha's /generate from here, give that its own much longer timeout rather
#: than raising this one.
DEFAULT_TIMEOUT = 5.0

#: Server-side caps, mirrored here so overflow is visible locally rather than
#: silently dropped. See BriefPolicy.
MAX_ITEMS = 5
MAX_LINK = 2000
MAX_TITLE = 80
MAX_TEXT = 200


class MedhaError(Exception):
    """Every failure. ``code`` is machine-readable; ``status`` is 0 if the
    request never reached Medha."""

    def __init__(self, message: str, code: str = "unknown", status: int = 0):
        super().__init__(message)
        self.code = code
        self.status = status


class Medha:
    def __init__(
        self,
        token: str,
        port: int = DEFAULT_PORT,
        host: str = "127.0.0.1",
        timeout: float = DEFAULT_TIMEOUT,
    ):
        if not token:
            raise ValueError(
                "a token is required -- Medha app -> API clients -> Add client"
            )
        self.token = token
        self.base = f"http://{host}:{port}"
        self.timeout = timeout

    # ------------------------------------------------------------- brief

    def post_brief(self, items, ttl_seconds: int | None = None) -> dict:
        """Replace this client's section of the brief.

        Replace, not append: you are stating your whole current
        contribution. Other clients' sections are untouched. An empty list
        withdraws yours, so clearing needs no second call.

        Returns the server's reply, whose ``items`` is what was **kept** --
        which can be fewer than you sent.
        """
        payload = {"items": _normalise(items)}
        if ttl_seconds is not None:
            payload["ttlSeconds"] = int(ttl_seconds)
        return self._call("PUT", "/widget/content", payload)

    def post_brief_quietly(self, items, ttl_seconds: int | None = None) -> bool:
        """post_brief that never raises.

        For the common case: updating the widget is a side effect of
        handling a request, and a widget that failed to update must not turn
        a working response into a 500. Returns True on success.

        It swallows the error rather than hiding it -- pass a logger-backed
        ``on_error`` if you want it recorded.
        """
        try:
            self.post_brief(items, ttl_seconds)
            return True
        except (MedhaError, Exception):
            return False

    def read_brief(self) -> dict:
        """This client's section. An admin token sees every client's."""
        return self._call("GET", "/widget/content")

    def clear_brief(self) -> dict:
        """Withdraw this client's section."""
        return self._call("DELETE", "/widget/content")

    # ------------------------------------------------------ notifications

    def notify(
        self,
        title: str,
        text: str = "",
        id: str = "default",
        ongoing: bool = False,
        progress_current: int = -1,
        progress_max: int = -1,
        silent: bool = True,
    ) -> dict:
        """Post to the notification shade.

        ``ongoing`` with progress is what may be promoted to the One UI 8
        Now Bar. Ask :meth:`capabilities` what this device supports rather
        than guessing from the OS version.

        A returned ``{"posted": False}`` is **not** an error: the call
        worked and Android refused it, which on API 33+ means the user has
        not granted *Medha* the notification permission. The ``reason``
        field says so.

        Rate limited to 10 in a burst and 30 a minute, per client. Over that
        you get ``MedhaError(code="rate_limited")``.
        """
        if not title:
            raise MedhaError("title is required", "bad_request")
        return self._call(
            "POST",
            "/notify",
            {
                "id": id,
                "title": str(title)[:200],
                "text": str(text)[:2000],
                "ongoing": bool(ongoing),
                "progressCurrent": int(progress_current),
                "progressMax": int(progress_max),
                "silent": bool(silent),
            },
        )

    def cancel_notification(self, id: str = "default") -> dict:
        return self._call("DELETE", "/notify/" + urllib.parse.quote(id, safe=""))

    def capabilities(self) -> dict:
        """What this device supports. ``nowBrief`` is always False."""
        return self._call("GET", "/notify/capabilities")

    # ------------------------------------------------------------- health

    def health(self) -> dict:
        """Is Medha up? Needs no token.

        ``modelLoaded`` is about inference. The brief and notifications work
        with no model loaded, so do not gate on it.
        """
        return self._call("GET", "/health", auth=False)

    # ---------------------------------------------------------- internals

    def _call(self, method: str, path: str, body=None, auth: bool = True) -> dict:
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if auth:
            headers["Authorization"] = "Bearer " + self.token

        req = urllib.request.Request(
            self.base + path, data=data, headers=headers, method=method
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                raw = r.read().decode("utf-8") or "{}"
                return json.loads(raw) if raw.strip() else {}
        except urllib.error.HTTPError as e:
            raise _http_error(e) from None
        except (urllib.error.URLError, socket.timeout, TimeoutError, OSError) as e:
            raise MedhaError(_explain(e), "unreachable") from None
        except json.JSONDecodeError:
            raise MedhaError("Medha returned a non-JSON body", "bad_response") from None


# ----------------------------------------------------------------- helpers


def _http_error(e: "urllib.error.HTTPError") -> MedhaError:
    parsed = {}
    try:
        parsed = json.loads(e.read().decode("utf-8"))
    except Exception:
        pass  # an error response with no JSON body must not mask the status

    code = parsed.get("code") or f"http_{e.code}"
    msg = parsed.get("error") or f"HTTP {e.code}"

    if e.code == 429:
        retry = e.headers.get("Retry-After") if e.headers else None
        if retry:
            msg += f" (retry in {retry}s)"
    elif code == "forbidden":
        msg += " -- the client needs the 'notify' capability; tick "
        msg += "'Post notifications' on it in Medha -> API clients"
    elif e.code == 401:
        msg += " -- token missing, wrong, or revoked"

    return MedhaError(msg, code, e.code)


def _explain(e: Exception) -> str:
    """A bare URLError covers several unrelated causes. Name them."""
    return (
        f"Could not reach Medha ({e}).\n"
        "  - Is Medha running with the server started?\n"
        "  - Is the port right? Medha's status card shows it.\n"
        "  - Is this process on the same device? The server binds 127.0.0.1\n"
        "    only, so nothing off-device can reach it by design.\n"
        "  - Under Termux, Medha must be running as an app -- the server is\n"
        "    not a Termux process and stopping Medha stops the socket."
    )


def _normalise(items) -> list:
    """Trim, cap and drop empties locally, so loss is visible here rather
    than silently applied by the server."""
    if not isinstance(items, (list, tuple)):
        raise MedhaError("items must be a list", "bad_request")
    out = []
    for i in items:
        if not isinstance(i, dict):
            continue
        title = str(i.get("title") or "").strip()
        if not title:
            continue
        item = {
            "title": title[:MAX_TITLE],
            "text": str(i.get("text") or "").strip()[:MAX_TEXT],
        }
        # Optional per-item link: tapping the line in Medha's brief opens
        # it. http(s) only, dropped rather than raised so one bad link does
        # not fail a whole post. Medha filters again server-side, because a
        # tap on someone's home screen must not launch an `intent:` URL.
        link = str(i.get("link") or "").strip()
        if (
            link
            and len(link) <= MAX_LINK
            and link.lower().startswith(("http://", "https://"))
            and not any(c.isspace() for c in link)
        ):
            item["link"] = link
        out.append(item)
        if len(out) == MAX_ITEMS:
            break
    return out


# Imported late so the module docstring stays at the top of the file.
import urllib.parse  # noqa: E402
