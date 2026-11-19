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
      tick "Read your notifications"   <- only if you call captures()
      -> copy the token

Put it somewhere the process can read and a repo cannot:

    # ~/.config/hg_agent/env, sourced by the runit run script
    export MEDHA_TOKEN=...

A token made this way has no bound origin, so Medha applies no origin check
to it. Treat it as a credential: it can post to the user's home screen.

"Read your notifications" is a second, larger thing to hold. It is the only
capability an app cannot request through Medha's consent dialog -- it has to
be ticked here, and Medha asks the user to confirm the scope and the 14-day
retention the first time. The log does not exist until something holds it,
and revoking it deletes what was collected, so this token being lost is the
whole notification stream being lost with it. See :meth:`Medha.captures`.
"""

from __future__ import annotations

import json
import re
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
#:
#: MIRRORED, which means they can drift, and this one did. MAX_ITEMS stayed
#: at 5 after BriefPolicy.MAX_ITEMS_PER_SECTION was raised to 20, so this
#: file -- the reference client -- quietly truncated every post to five
#: items and returned success. Nothing failed: the server would have taken
#: all twenty, and the loss happened here, locally, before the request was
#: ever sent.
#:
#: If you raise a cap in BriefPolicy, change it here in the same commit.
MAX_ITEMS = 20
MAX_LINK = 2000
MAX_TITLE = 80
MAX_TEXT = 200

#: BriefJson.MAX_ID_LENGTH. An obligation id is a handle, not a payload: it
#: is stored in preferences and read on every widget redraw.
MAX_ID = 128


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
        """Replace this client's section of the brief, v1 shape.

        Replace, not append: you are stating your whole current
        contribution. Other clients' sections are untouched. An empty list
        withdraws yours, so clearing needs no second call.

        Returns the server's reply, whose ``items`` is what was **kept** --
        which can be fewer than you sent.

        Prefer :meth:`post_obligations` on Medha v0.20.0 and later. This
        shape sends a rendered row, which means Medha cannot tell a
        reschedule from a new meeting, a dismissal does not survive a
        reword, and the only way it can learn something was cancelled is by
        reading the word off the front of your title.
        """
        payload = {"items": _normalise(items)}
        if ttl_seconds is not None:
            payload["ttlSeconds"] = int(ttl_seconds)
        return self._call("PUT", "/widget/content", payload)

    def post_obligations(self, obligations, ttl_seconds: int | None = None) -> dict:
        """Replace this client's section, v2 shape. Needs Medha >= v0.20.0.

        Each obligation is a dict::

            {"id": "card-icici-nov",          # your own stable handle
             "title": "ICICI credit card",
             "text": "payment missed",
             "kind": "bill",
             "due": {"at": 1762022400000, "provenance": "declared"},
             "state": "open",
             "links": ["https://quicksilver.local/n/4312"]}

        Three fields carry the weight, and each is a mistake already made:

        ``id`` -- **send it.** Without one, identity is (client, title,
        dueAt), so a dismissal dissolves the moment you reword a title. An
        item the user had put away came back, and it looked like your bug.
        Changing the id is how you say "different commitment now".

        ``due.provenance`` -- ``declared`` or ``inferred``. Say when you
        guessed. An inferred date sorts, persists and shows on the full
        brief but cannot reach the widget; without this your parsed dates
        compete with the user's real appointments, which is exactly how four
        emails ended up on an agenda.

        ``state`` -- ``open``, ``done`` or ``cancelled``. Only ever demotes.
        ``open`` does not rescue an item whose title begins "Cancelled:".

        The reply's ``onAgenda`` is how many reached the home screen, and
        ``linksDropped`` how many tap targets were refused. Read both: a
        post of five dated obligations that comes back ``"onAgenda": 1`` is
        telling you something about your provenance or your states.

        Convenience only, over :meth:`post_brief`: both are one PUT. Nothing
        here caches, so ``ttl_seconds`` behaves identically.
        """
        payload = {"obligations": _normalise_obligations(obligations)}
        if ttl_seconds is not None:
            payload["ttlSeconds"] = int(ttl_seconds)
        return self._call("PUT", "/widget/content", payload)

    def brief_protocol(self) -> int:
        """The highest brief protocol this Medha understands: 1 or 2.

        Branch on it once at startup rather than on every post. A Medha that
        predates the field reports no ``briefProtocol`` at all, which reads
        as 1 -- so this never claims v2 against a build that would refuse it.
        """
        caps = self.capabilities()
        raw = caps.get("briefProtocol")
        return raw if isinstance(raw, int) and not isinstance(raw, bool) else 1

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
        link: str = None,
    ) -> dict:
        """Post to the notification shade.

        ``ongoing`` with progress is what may be promoted to the One UI 8
        Now Bar. Ask :meth:`capabilities` what this device supports rather
        than guessing from the OS version.

        A returned ``{"posted": False}`` is **not** an error: the call
        worked and Android refused it, which on API 33+ means the user has
        not granted *Medha* the notification permission. The ``reason``
        field says so.

        ``link`` is where a tap goes. Without it the tap opens Medha. Only
        http(s) is accepted -- a custom scheme like ``quicksilver://note/1``
        is **dropped and the notification still posts**, so check
        ``result["tapOpens"]``, which is ``"link"`` or ``"medha"``.

        For your own app to receive the tap rather than a browser, its
        intent filter must declare ``android.intent.category.BROWSABLE``.
        Medha adds that category, and a filter has to match every category
        in the intent -- without it the link goes to the browser with no
        error anywhere.

        Rate limited to 10 in a burst and 30 a minute, per client. Over that
        you get ``MedhaError(code="rate_limited")``.
        """
        if not title:
            raise MedhaError("title is required", "bad_request")
        payload = {
            "id": id,
            "title": str(title)[:200],
            "text": str(text)[:2000],
            "ongoing": bool(ongoing),
            "progressCurrent": int(progress_current),
            "progressMax": int(progress_max),
            "silent": bool(silent),
        }
        if link:
            payload["link"] = str(link)
        return self._call("POST", "/notify", payload)

    def cancel_notification(self, id: str = "default") -> dict:
        return self._call("DELETE", "/notify/" + urllib.parse.quote(id, safe=""))

    def capabilities(self) -> dict:
        """What this device supports. ``nowBrief`` is always False."""
        return self._call("GET", "/notify/capabilities")

    # ----------------------------------------------------------- captures

    def captures(self, since: int = 0, limit: int | None = None) -> dict:
        """One page of the phone's notification stream, raw.

        Needs the ``captures`` capability, which is **not** the same as
        ``notify`` and is not grantable through an app's consent dialog --
        tick "Read your notifications" in Medha's own client editor. Without
        it you get ``MedhaError(code="forbidden")``.

        This is the route that runs the other way. Everything else in this
        class writes *to* Medha; this reads what the phone said, before
        anything interprets it. Medha has the sensors and no memory, so it
        used to guess -- a ``kind`` from the package, a ``dueAt`` scraped out
        of prose -- and those guesses reached the home screen as facts. You
        have the corpus. Read observations and do the thinking here.

        Returns ``{items, cursor, more, head, listening, retainDays,
        maxRows}``. Prefer :meth:`drain_captures`, which handles the cursor
        and the paging; this is the single-page primitive it is built on.
        """
        q = {"since": max(0, int(since))}
        if limit is not None:
            q["limit"] = int(limit)
        return self._call("GET", "/captures?" + urllib.parse.urlencode(q))

    def drain_captures(self, cursor: int = 0, max_pages: int = 50):
        """Every observation after ``cursor``, oldest first.

        Returns ``(items, next_cursor, listening)``.

        **Persist ``next_cursor`` only after the items are durably stored.**
        This is the one way to lose data with this API, and it is easy to get
        backwards: save the cursor first and crash before writing the notes,
        and those notifications are gone -- Medha prunes on its own schedule
        and will not hand them over twice. The right order is always::

            items, cursor, listening = medha.drain_captures(saved_cursor)
            for it in items:
                store(it)          # durable
            save_cursor(cursor)    # only now

        Re-reading a page you already stored is cheap; missing one is
        permanent. If you crash between the two, you get duplicates on the
        next run -- dedupe on ``id``, which is stable and never reused.

        ``listening`` False means nothing is being recorded at all: either
        Android's notification access is off or Medha's own switch is. An
        empty list means the same thing as a quiet phone otherwise, and an
        agent that cannot tell them apart will report a clear day when it
        has simply gone deaf. Surface it.

        ``max_pages`` bounds the walk. The server says ``more`` only when
        there is a known backlog, never merely because something arrived
        while it was answering, so this terminates -- but a bound means a
        protocol change cannot turn this into a spin.

        A ``next_cursor`` LOWER than the one you passed is not an error: the
        capability was revoked, which deletes the log, and the rows your
        cursor pointed past no longer exist. Save it and carry on.
        """
        cursor = max(0, int(cursor))
        items, listening = [], True
        for _ in range(max(1, int(max_pages))):
            page = self.captures(since=cursor)
            items.extend(page.get("items") or [])
            listening = bool(page.get("listening", True))
            nxt = page.get("cursor")
            # Only advance on a cursor the server actually sent. A missing or
            # non-numeric one means something is wrong with the reply, and
            # inventing a position from the rows would skip whatever the
            # server has that this page did not show.
            if not isinstance(nxt, int) or isinstance(nxt, bool):
                break
            cursor = nxt
            if not page.get("more"):
                break
        return items, cursor, listening

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
        # The remedy depends on WHICH capability is missing, and the box the
        # user has to tick is named differently from the wire value. Taking
        # the capability out of Medha's own message rather than guessing from
        # the request: a 403 that tells you to tick the wrong box is worse
        # than one that just says 403, and until v0.20.0 this said 'notify'
        # unconditionally -- advice that would have sent anyone hitting a
        # /captures 403 to the wrong checkbox.
        boxes = {
            "notify": "'Post notifications'",
            "captures": "'Read your notifications'",
            "generate": "'Run the model'",
            "memory": "'Chat memory / sessions'",
            "rag": "'Knowledge (RAG)'",
            "store": "'Store its own data'",
        }
        # Anchored on "capability '<x>'", not on any quoted word in the
        # message. Medha's 403 reads `client 'hg-agent' lacks capability
        # 'captures'` -- two quoted strings, and a client whose id happened
        # to be a capability name would otherwise decide the advice.
        found = re.search(r"capability '([a-z]+)'", msg)
        named = found.group(1) if found and found.group(1) in boxes else None
        if named:
            msg += f" -- tick {boxes[named]} on this client in Medha -> API clients"
        else:
            msg += " -- the client is missing a capability; check its boxes "
            msg += "in Medha -> API clients"
        if named == "captures":
            msg += " (it is not grantable through an app's consent dialog)"
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


def _safe_link(raw) -> str | None:
    """http(s), within length, no whitespace -- or None.

    Shared by both shapes so the two cannot disagree about what a tap target
    is. Medha filters again server-side, because a tap on someone's home
    screen must not be able to launch an ``intent:`` URL.
    """
    link = str(raw or "").strip()
    if (
        link
        and len(link) <= MAX_LINK
        and link.lower().startswith(("http://", "https://"))
        and not any(c.isspace() for c in link)
    ):
        return link
    return None


def _normalise_obligations(obligations) -> list:
    """The v2 shape, trimmed and capped locally.

    Same contract as :func:`_normalise`: loss is applied here, where you can
    see it, rather than silently server-side.

    Two things are deliberately NOT normalised. An unknown ``state`` is sent
    as given, because Medha drops it to "you did not say" and the response
    is how you find out you had a typo -- quietly correcting it here would
    hide that. And ``links`` entries that fail validation are dropped
    locally but still counted by the server's ``linksDropped``, so a client
    sending only a custom scheme sees a non-zero count rather than silence.
    """
    if not isinstance(obligations, (list, tuple)):
        raise MedhaError("obligations must be a list", "bad_request")
    out = []
    for o in obligations:
        if not isinstance(o, dict):
            continue
        title = str(o.get("title") or "").strip()
        if not title:
            continue
        item = {
            "title": title[:MAX_TITLE],
            "text": str(o.get("text") or "").strip()[:MAX_TEXT],
        }
        # Blank is omitted rather than sent as "": an id shared by every
        # item would give them one identical dismissal key, so putting away
        # one row would put away the whole section.
        obl_id = str(o.get("id") or "").strip()
        if obl_id:
            item["id"] = obl_id[:MAX_ID]
        kind = str(o.get("kind") or "").strip()
        if kind:
            item["kind"] = kind
        state = str(o.get("state") or "").strip()
        if state:
            item["state"] = state

        due = o.get("due")
        if isinstance(due, dict):
            at = due.get("at")
            # Positive only. Zero is a real instant -- 1 Jan 1970 -- and an
            # item carrying it renders as decades overdue and sorts above
            # everything the user actually has to do today.
            if isinstance(at, (int, float)) and not isinstance(at, bool) and at > 0:
                block = {"at": int(at)}
                provenance = str(due.get("provenance") or "").strip()
                if provenance:
                    block["provenance"] = provenance
                item["due"] = block

        links = o.get("links")
        if isinstance(links, str):
            links = [links]
        if isinstance(links, (list, tuple)):
            kept = [s for s in (_safe_link(x) for x in links) if s]
            if kept:
                item["links"] = kept

        out.append(item)
        if len(out) == MAX_ITEMS:
            break
    return out


# Imported late so the module docstring stays at the top of the file.
import urllib.parse  # noqa: E402
