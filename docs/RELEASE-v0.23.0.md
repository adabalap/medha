# Medha v0.23.0 — How long a task has waited

`versionCode 54`, `versionName 0.23.0`. Build on CI and sideload.

Ships on top of v0.22.0. Works with any client; the change is visible once
the matching hg_agent is running, because that is what sends the new field.

---

## 1. The mark on an undated row

Each brief row has a small mark on the right. For a dated row it is the due
time. For an undated row it was the age of the *section*: when the client
last posted. That is one value for every row the client sends, so a real
brief showed "40m" down a column of thirteen rows, a 48-day-old task among
them. Nobody reads the mark as "when Quicksilver last posted". They read it
as "how long this has been waiting".

v2 obligations now carry an optional `since` (epoch ms): when the obligation
began. The mark uses it when present and falls back to the section age when
absent, so older clients render exactly as before.

`BriefIntake.sinceOrNull` refuses values that cannot be true:

- not positive — as with `dueAt`, `0` means "no value" far more often than
  it means 1970
- more than five minutes ahead of Medha's clock — a task cannot have waited
  a negative time, and "just now" for something that has not begun would be
  quietly wrong. Five minutes of skew is allowed, because phone and server
  clocks disagree by that much routinely.

The field is additive. Medha's JSON decoder ignores unknown keys, and a v0.22
Medha ignores `since` from a newer agent.

## 2. What the agent sends with it (hg_agent, same release)

- **Tasks** carry `since` from the task's `created_at`.
- **Stalled follow-ups** carry the oldest open task's `created_at`, and the
  reason text no longer repeats "Open 48 days", since the mark now says so.
- **Second lines name the note**, not "Tap to open the note": "Pay the
  electricity bill · from *Sriram Nagar flat*". A bill says when it is due
  and whether the message has a pay link.

## 3. Quicksilver, same release (hg_ui, API level 9)

Not part of this APK, listed so the set is in one place:

- Reminders sort soonest first by default, and a bill from a message is one
  line (due day, the heads-up while it is ahead, a link to the bill) instead
  of two lines, each with the SMS pasted under it.
- All-day reminders print a date, not "5:30 AM".
- Tab badges count what needs you today: tasks waiting on a decision, and
  reminders due today or overdue.
- A suggestion to drop a task is never the filled primary button.
- The note view puts tasks and reminders straight under the note, drops the
  summary on short notes, and no longer shows "Approved" or the internal id.
- The dashboard has no Protected tile, and an empty brief says whether the
  agent is stopped or there is simply nothing yet.

---

## 4. Verification

```
tools/tests/run.sh          28 Kotlin suites green
tools/check_*.py            14 checkers green
```

`BriefIntakeTest` covers `sinceOrNull` (zero, negative, within skew, beyond
skew, absent); `BriefJsonTest` round-trips `since` and decodes rows without
it; `BriefRenderTest` checks the mark prefers `since` over the section age.

**Not verified on a device.** Nothing Android renders in this environment.
