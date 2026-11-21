#!/usr/bin/env python3
"""
A worked example: hg_agent posting to Medha's brief.

Drop next to medha_brief.py. Run it from runit/termux-services on a timer,
or call update_brief() from hg_ui when state changes.

    MEDHA_TOKEN=... python3 hg-agent-brief.py

## The editorial rule this is built around

A home-screen widget shows three lines at a time. Anything on it that is
always the same is wallpaper -- and wallpaper trains the eye to skip the
whole surface, including the line that mattered.

So this posts **deltas and exceptions**, not a dashboard:

  - a supervised service that is DOWN is a line; one that is up is not
  - overnight throughput is a line, because it changes
  - "all systems normal" is one short line at most, and only because its
    absence would be ambiguous

The division of labour against notifications is the same distinction:

  - **notify()** interrupts. Use it when something needs a human now: a
    service that would not restart, the model failing to load, storage
    about to run out.
  - **the brief** does not interrupt. Use it for what is worth knowing at a
    glance but never worth a buzz: counts, rates, what happened overnight.

Posting a service outage to the brief and nothing else means the user finds
out next time they happen to look at their home screen. Posting throughput
as a notification means they stop reading notifications.
"""

import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from medha_brief import Medha, MedhaError  # noqa: E402

# runit service names under termux-services. Adjust to yours.
SERVICES = ["hg_agent", "hg_ui", "nginx"]

#: Where a tapped brief line should take you. Your nginx HTTPS origin for
#: hg_ui. Leave blank and the lines open a detail sheet instead.
HG_UI_URL = os.environ.get("HG_UI_URL", "")


def service_down():
    """Names of supervised services that are not up.

    `sv status` is the supervision state, which is the thing worth
    reporting -- a process can be running and still be in a restart loop,
    and runit knows that when a port check does not.
    """
    down = []
    for name in SERVICES:
        try:
            out = subprocess.run(
                ["sv", "status", name],
                capture_output=True, text=True, timeout=5,
            ).stdout.strip()
        except (OSError, subprocess.SubprocessError):
            # sv missing, or the service not defined. Unknown is not the
            # same as down, and claiming an outage that is not happening is
            # how the user learns to distrust the widget.
            continue
        if out and not out.startswith("run:"):
            down.append(name)
    return down


def update_brief(medha, stats):
    """Build and post hg_agent's section.

    `stats` is whatever hg_agent already tracks -- this does not prescribe
    where it comes from. Expected keys, all optional:
        requests, p50_ms, compression_ratio, saved_ms, model
    """
    lines = []

    # Exceptions first: the one line that might make the user act belongs
    # where they will see it without rotating.
    down = service_down()
    if down:
        lines.append({
            "title": f"{'Service' if len(down) == 1 else 'Services'} down",
            "text": ", ".join(down),
        })

    if stats.get("requests"):
        text = f"{stats['requests']} requests"
        if stats.get("p50_ms"):
            text += f" · p50 {stats['p50_ms']}ms"
        line = {"title": "hg_agent today", "text": text}
        # Tapping the line in Medha's brief opens hg_ui. Only the posting
        # app knows where its own line points -- Medha stores a hashed
        # namespace rather than a package and will not guess.
        if HG_UI_URL:
            line["link"] = HG_UI_URL
        lines.append(line)

    # Only worth a line when it is actually doing something. A compression
    # ratio of 1.0x is the absence of a result, not a result.
    ratio = stats.get("compression_ratio")
    if ratio and ratio > 1.05:
        text = f"{ratio:.1f}x"
        if stats.get("saved_ms"):
            text += f" · ~{stats['saved_ms']}ms saved per request"
        lines.append({"title": "Context compression", "text": text})

    if stats.get("model"):
        lines.append({"title": "Model", "text": stats["model"]})

    if not lines:
        # One line rather than none: an empty section is indistinguishable
        # from hg_agent having died, which is the opposite of the message.
        lines = [{"title": "hg_agent", "text": "idle, nothing to report"}]

    # A TTL slightly longer than the posting interval. If this script stops
    # running, the section ages out instead of showing yesterday's numbers
    # as though they were current -- the widget going quiet is itself the
    # signal that something stopped.
    return medha.post_brief(lines, ttl_seconds=90 * 60)


def alert_if_needed(medha, down):
    """Interrupt only for things a human has to act on."""
    if not down:
        # Clear any earlier alert so a resolved outage does not sit in the
        # shade looking current.
        medha.cancel_notification("hg-agent-down")
        return
    medha.notify(
        id="hg-agent-down",
        title="hg_agent service down",
        text=", ".join(down) + " — runit is not holding it up",
        silent=False,   # this one is worth a sound; almost nothing else is
    )


def main():
    token = os.environ.get("MEDHA_TOKEN")
    if not token:
        # Fail loudly at start rather than silently never updating. A
        # widget that is merely empty looks like "nothing happened".
        print("MEDHA_TOKEN is not set.", file=sys.stderr)
        print("Medha -> drawer -> API clients -> Add client, tick "
              "'Post notifications', copy the token.", file=sys.stderr)
        return 2

    medha = Medha(token=token, port=int(os.environ.get("MEDHA_PORT", "8001")))

    stats = {
        # Replace with hg_agent's real counters.
        "requests": 412,
        "p50_ms": 840,
        "compression_ratio": 3.2,
        "saved_ms": 1100,
        "model": "gemma-3n-E2B",
    }

    try:
        r = update_brief(medha, stats)
        print(f"brief: {r.get('items')} line(s) stored, "
              f"{r.get('pageRows')} shown at a time")
        alert_if_needed(medha, service_down())
    except MedhaError as e:
        # Worth distinguishing: a missing capability or a revoked token is a
        # setup problem the user must fix, while an unreachable server just
        # means Medha is not running right now and the next run will work.
        if e.code in ("forbidden", "unauthorized"):
            print(f"setup problem: {e}", file=sys.stderr)
            return 2
        print(f"Medha unavailable, skipping this round: {e}", file=sys.stderr)
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
