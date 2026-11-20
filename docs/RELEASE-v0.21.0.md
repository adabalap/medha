# Medha v0.21.0 — the brief stops asking for a date

`versionCode 52`, `versionName 0.21.0`. Extract over your checkout, run
`verify.sh`, build on CI.

Ships on top of v0.20.0; read `docs/RELEASE-v0.20.0.md` first if you are
coming from 0.19.x.

---

## 1. "Pick up the dry cleaning" belongs on the brief

The triage rule shipped in v0.19.2 required a date. That was wrong, and it
took a one-line report to see it: picking up the dry cleaning has no date,
never will, and is exactly what a brief is for. The date rule kept it off
the home screen permanently.

It was wrong for an interesting reason. "Dated" was never the real test — it
was standing in for a question it could not answer: **did a person decide
this needs doing?** The 17 undated rows that prompted the rule in the first
place (a shopping list, a webinar invitation, a WhatsApp "Thanq thanq") were
not junk because they lacked dates. They were junk because an app had
mentioned them and nobody had committed to them.

So the rule now asks that directly. Two disqualifiers, then either of two
ways in:

**Out, whatever else is true:** the client said it is settled (`done`,
`cancelled`), or its own words announce a cancellation.

**In, if either:** a person committed to it, *or* it carries a time that was
declared rather than guessed out of prose.

`BriefPolicy.Item.committed` defaults **true**, and the default is the whole
design: a client posting to your brief is asserting it needs you, so nothing
any client does has to change. The notification listener sets it **false** —
it reports what the phone said, not what you decided — so a bare WhatsApp
line still stays off the agenda while a Teams meeting with a real time still
reaches it.

The v2 protocol gained a matching `committed` field for anything a client is
relaying rather than vouching for.

### The test that was asserting the wrong thing

`BriefTriageTest`'s "real screen" fixture already contained both of the
reported examples — "Stalled: Pick up dry cleaner clothes" and "Apply for
Sriram Nagar 3rd Electric Meter" — sitting in the admin section, and
asserted that exactly two rows reached the agenda. The test was encoding the
defect. It now asserts six: the five a client posted, plus the one
notification carrying a real time.

---

## 2. The same obligation, arriving twice

A meeting can reach the brief as a Quicksilver reminder taken from a note
**and** as a Teams notification read off the phone. Neither side can see the
other, so neither can notice. Medha holds every section, so `BriefResolve`
lives there.

Two items are the same thing when their titles share at least two
identifying words, at least 60% of the shorter title, and — when both carry a
time — fall on the same local day. The surviving copy is the better-sourced
one: committed over observed, a declared date over a guessed one, a date over
none, a link over none. Ties keep the section that has been contributing
longest.

Deliberately crude, and deliberately not the embeddings the agent already
has. This runs on every widget redraw and the consequence of a match is that
a row **disappears** from a home screen. The bar is set so that a miss is the
likely failure: a missed duplicate costs one repeated line, a false match
costs an obligation nobody ever sees.

### The order-dependence that mutation testing found

The first implementation walked every pair and dropped the worse one. That is
wrong, because matching is not transitive: A can match B, B match C, and A
not match C. Pairwise, B loses to A and then goes on to eliminate C — and C
vanishes although nothing that survived ever matched it.

Worse, whether it happened depended on the order sections arrived in, which
is just whichever client posted first. Three obligations, six orderings:
**two rows for three of them and one row for the other three.**

It now sorts best-first and lets only a *survivor* eliminate anything, which
makes the result independent of posting order. `BriefResolveTest` asserts
that over every permutation rather than one example, because a single
ordering is exactly what hid it.

### Why dedupe runs before dismissal

`NotificationHub.brief()` is prune → resolve → dismiss, and the order is
load-bearing. You can only dismiss a row you can see, and what you see is the
copy that survived resolution. Run dismissal first and the losing copy is the
only one left for the resolver to consider, so it is kept: the row you just
put away reappears, from a different app, looking identical.

---

## 3. Open items bucketed by app, on the expanded screen only

The leftovers — things an app mentioned that nobody committed to, carrying no
declared time — are now grouped on the full-brief screen by the app that
produced them, each bucket clearable in one action. Biggest bucket first,
because the one worth dismissing wholesale is almost always the noisiest.

Buckets are keyed on the **app**, not the posting client: every
notification-sourced row shares the single client `notifications` while
actually coming from WhatsApp, Teams and Outlook. Bucketing by client would
put all of them in one pile labelled with a registry id nobody was meant to
read.

**The agenda is not bucketed, anywhere.** Splitting what needs doing by which
app mentioned it turns a list of obligations back into an inbox: a ₹2,50,000
instalment and a 17:30 meeting belong next to each other because they are
both today, not in separate piles because one arrived as a bank message and
the other from Teams.

---

## 4. New files

| File | For |
|---|---|
| `app/.../notify/BriefResolve.kt` | cross-section duplicate resolution, pure |
| `tools/tests/BriefResolveTest.kt` | 55 checks, incl. the permutation property |
| `docs/RELEASE-v0.21.0.md` | this file |

---

## 5. Verification

```
tools/tests/run.sh          36 suites green, exit 0
tools/check_*.py            14 checkers green
*_mutations.sh              86 mutation checks across 4 suites
```

Mutation probes on `BriefResolve` caught five defects before they shipped,
three of them in the tests rather than the code: a day check that was never
reached because the fixture's title was one word, an overlap threshold that
could have been zero because every negative case was rejected by a different
guard first, and a same-section rule that could not fire because the fixture
had only one section. The fourth was a genuinely redundant guard, now
removed. The fifth was the order-dependence above.

One fixture in `check_symbols_mutations.sh` asserted "2 call sites" against
the real tree and went red when `BriefActivity` grew a third `toast()`. It
now derives the count, like every other bounds fixture in this project.
