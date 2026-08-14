# Sharing SMS samples safely

Bank SMS are among the most sensitive text on a phone: names, account tails,
phone numbers, reference numbers, loan application IDs, amounts. Never paste
them raw into a chat, an issue tracker, or an email.

```bash
python3 tools/redact.py drift.txt > safe.txt   # redact
python3 tools/redact.py --verify safe.txt      # confirm before sending
```

Runs offline. Standard library only. Nothing is uploaded.

## What it does

Replaces values while preserving the message *shape*, because debugging a
parser needs the template, not the facts:

```
Sent Rs.1358.00 From HDFC Bank A/C *5261 To Mr Gadipudi Khadri On 10/08/26
Sent Rs.9999.99 From HDFC Bank A/C *9999 To PERSON_e6d8       On 99/99/99
```

| Redacted | Kept, because it is not personal |
|---|---|
| names, amounts, dates, times | sender IDs (`AX-HDFCBK`) |
| account and card tails | UPI provider suffixes (`@ybl`, `@okicici`) |
| phone numbers, emails | keywords, punctuation, line breaks |
| reference / UTR / UMN / AWB | currency markers |
| PAN, Aadhaar, IFSC | occurrence counts (`x104`) |
| URLs and their paths | the reject reason (`[no-direction]`) |

Amounts become `9999.99` — same notation, no value. Dates become `99/99/99`.
The structure a parser cares about survives intact.

## Consistent pseudonyms

The same name maps to the same `PERSON_xxxx` throughout a file, so duplicate
detection and per-merchant grouping still work after redaction.

By default the salt is random and discarded when the process exits, so two
exports cannot be linked and nobody — including someone who guesses the name —
can reverse the mapping. `--salt <value>` makes it reproducible across runs;
only use that when cross-file consistency actually matters, since anything you
can reproduce, a holder of your file can reproduce too.

## Verifying

`--verify` scans for email addresses, phone numbers, long digit runs, PAN,
IFSC, live URLs and unmasked amounts. It ignores the tool's own placeholders —
otherwise every clean file would report dozens of false alarms and you would
learn to ignore the warnings, which is worse than having none.

```
$ python3 tools/redact.py --verify safe.txt
CLEAN — nothing matched the PII patterns
```

`--selftest` runs the rules against known samples and checks that pseudonyms
are consistent, distinct names stay distinct, and structure is preserved.

## Limits, stated plainly

**Regex cannot recognise every name.** A payee written in a form these rules
do not cover will survive. `--verify` catches mechanical patterns; it cannot
catch "paid to Ramesh" phrased unusually.

**Read the output before you send it.** The tool removes the bulk of the risk
and makes the rest easy to spot; it is not a guarantee.

Amounts are redacted by default. If you are reporting a parsing bug where the
amount itself matters — say a thousands-separator problem — send the shape
(`Rs.9,99,999.99`), which is preserved, rather than restoring the real figure.
