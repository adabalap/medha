# Medha v0.22.0 — Save a notification to Quicksilver

`versionCode 53`, `versionName 0.22.0`. Extract over your checkout, run
`verify.sh`, build on CI.

Ships on top of v0.21.0. Needs the matching hg_agent and hg_ui to do
anything visible: Medha keeps a save until Quicksilver says it has it, so
installing this alone loses nothing — saves wait.

---

## 1. What a capture can become

A notification on the brief used to be a dead end. You could read it,
dismiss it, or close the sheet. A grocery list someone sent on WhatsApp had
nowhere to go: the shade forgets it when it is swiped, and WhatsApp will not
keep it for you either.

The detail sheet now has **Save**. It hands the notification to Quicksilver
exactly as it arrived, and nothing else — no type is chosen, no title is
rewritten, no list is split. That happens in Quicksilver, under **Saved from
Medha**, where each save waits for you to choose: note, checklist, reminder,
or task. Or discard.

Both of those were your calls when this was designed: decide in Quicksilver
afterwards, and keep it verbatim. The second one is enforced, not just
intended. The note you create is marked so that the agent's polishing pass
may tag and index it but may not rewrite its body, and choosing "note" for a
list stops the agent pulling six tasks out of it afterwards — which would be
it deciding again, after you already had.

### Only for notifications

Save appears on lines Medha read off the shade. Everything else on the brief
was *posted* by a client and already lives in that client: "Get the groceries
today" came from Quicksilver as a task it holds. Saving it would send
Quicksilver a copy of its own row. The button would work, make a duplicate,
and teach you that Save makes clutter.

### The buttons

Material gives a dialog three slots and this one now wants four actions, so
one had to give. It is Close, because back and a tap outside both close a
Material dialog — it is the only action with another way to do it — and it
keeps its slot whenever there is room:

| Line | Positive | Negative | Neutral |
|---|---|---|---|
| posted by a client | Close | Dismiss | Open, when there is one |
| from the shade, no link | **Save** | Dismiss | Close |
| from the shade, with a link | **Save** | Dismiss | Open |

**Not verified on a device.** Nothing Android renders in this environment.
The shape is reasoned about, not seen.

---

## 2. The save outbox is not the capture log

They look alike — append-only, cursored, pulled by the agent — and differ in
the one place that matters: what happens when nobody collects them.

A capture is an *observation*. Medha noticed something; nobody asked for it.
After 14 days it ages out, and that is right.

A save is an *instruction*. Someone pressed a button to say "keep this."
Aging that out is losing work they did, with no way for them to know. So a
save leaves the outbox when Quicksilver acknowledges it, not when it gets
old. `SavePolicy.RETAIN_DAYS` is 90 and exists only as a backstop against an
agent that never comes back. `MAX_ROWS` is 500, two orders of magnitude below
the capture log's, because this fills at the speed of a thumb.

### Never lost, never duplicated

The order is the guarantee: the agent reads a page, commits it, and **only
then** acks it. Reverse those and a crash between them loses the save
forever. In this order a crash means Medha sends the same rows again, and the
fingerprint — the inbox's primary key — absorbs them. At-least-once delivery,
exactly-once effect.

The fingerprint has to stop a double tap *without* stopping the same grocery
list being saved again next week. The words are identical every week and the
line has no due date, so the only thing that separates them is when the app
posted it. `BriefRender.Line` gained `postedAt` (the row's `updatedAt`) for
exactly this. The separator inside the key is U+001F rather than a space or a
pipe: those appear in notification text, and a key that can be forged by its
own content is not a key.

A double tap is refused by the `UNIQUE` index, not by checking first. Two
taps a few milliseconds apart would both read "absent" and both insert.

### The key is computed from what is stored

Oversized text is clipped at `MAX_TEXT` and the row records that it was.
`SavePolicy.row()` builds the fingerprint from the *clipped* fields, because
the route serves `fp` recomputed from what is stored. Key the original and a
truncated save is stored under one identity and announced under another.
`SavePolicyTest` asserts this against a mutation that keys the original.

---

## 3. Routes

| Route | Capability | Does |
|---|---|---|
| `GET /saved?since=&limit=` | `captures` | pending saves, verbatim, with `fp` |
| `POST /saved/ack?through=` | `captures` | drops everything up to that id |

Same capability as `/captures`: this is notification text, and that is the
grant that governs who reads it. A save is a strict subset of what that client
could already see, so nothing widens, and the agent already holds it.

---

## 4. A checker that could not see `TAG`

Wiring Save into `BriefActivity` used `TAG`, and the file did not declare
one. `check_imports` caught the three missing imports alongside it.
`check_symbols` passed.

It was not a false green. `check_symbols` checks bare *calls* and skips
capitalised names by design; a property read was outside its scope, and
`check_imports` only looks at types. So nothing in the toolchain caught the
single most common symbol added to an Android file. A public `const val TAG`
in `AccessRequestActivity`'s companion made it worse: a module-wide "is it
declared anywhere?" says yes.

General property reads are too noisy to check — the checker's author says so.
`TAG` is the exception that makes a narrow rule safe: every class declares its
own and none is shared, so a file that reads a bare `TAG` must declare one.
`check_symbols` now checks exactly that, without an early return so it cannot
hide a call problem in the same run. Six new mutation fixtures.

### A fixture that matched by luck

`check_symbols_mutations.sh` case 2 derived its expected call count with
`grep -c '^\s*toast('` — calls at the start of a line. That was already a fix
for a hard-coded count, and it was still an approximation of the checker's
rule rather than the rule. `?: return toast(...)` is the first call form to
fall in the gap: the checker counted five, the grep four, and a correct
checker went red. It now counts by the checker's own pattern.

---

## 5. Each thing once, even from one app

Your brief showed "Pick up dry cleaner clothes" three times and the Sriram
Nagar electric meter three times. All from one app — the header said "1 app ·
14 items".

`BriefResolve` deduplicated only *across* apps, on the principle that a
client repeating itself is its own business: two bills from one merchant are
two bills. The principle was sound and the conclusion was too strong. And
there was a second, quieter reason it could never help here: `dedupe()`
returned a one-section brief before looking at a single item. One section is
the common case — Quicksilver alone — so the code that should have caught
this was skipped every time.

Within one app, items now collapse at a **stricter** bar than across apps:
at least three identifying words, 85% of the shorter title, the same day when
both are dated, and **the same numbers**. That last rule is new and specific
to this case. The tokenizer drops anything under three characters, so "Pay
electricity bill for meter 1" and "…meter 2" reduce to identical words — and
they are two meters. Numbers are what tell a flat, a meter or an instalment
apart, and within one app's list they are often the only thing that does.

The survivor follows the existing quality order, so the dry cleaning that
stays is the one with a day on it, in *This week*.

### The fix at the source

Medha is the floor here, not the fix. The duplicates were being *made* by
Quicksilver: one task in one note surfaces as its action item, as the
reminder created when its follow-up was accepted onto a day, and as a
"Stalled:" follow-up — three tables, three keys. The dry-cleaning pair was
caused by v0.21's own "Move to Mon 23 Nov".

hg_agent now collapses those before posting, by identity rather than by
words: same note, same task. Medha's rule remains for what the agent cannot
see — two notes about the same thing, or any other app repeating itself.

---

## 6. New files

| File | For |
|---|---|
| `app/.../connect/SavePolicy.kt` | outbox rules, fingerprint, the row builder, which lines offer Save |
| `tools/tests/SavePolicyTest.kt` | 75 checks |
| `docs/RELEASE-v0.22.0.md` | this file |

Schema version 6 adds the `saves` table. Additive; nothing is dropped.

---

## 7. Verification

```
tools/tests/run.sh          28 Kotlin suites green, exit 0
tools/check_*.py            14 checkers green
*_mutations.sh              92 mutation checks across 4 suites
```

Mutation probes caught every one of: keeping a save no longer than a capture,
a fingerprint without the post time, verbatim text being trimmed, refusing
nothing as unsavable, a key built from the unclipped text — and, for the
dedupe, restoring the one-section early return, ignoring numbers, and
lowering either strict threshold or the within-app day check.
