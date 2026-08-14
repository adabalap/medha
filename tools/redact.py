#!/usr/bin/env python3
"""
redact.py — strip personal data from bank SMS before sharing them.

Runs entirely offline. No dependencies beyond the Python standard library.

    python3 redact.py drift.txt > safe.txt
    python3 redact.py --verify safe.txt        # check nothing slipped through
    python3 redact.py --selftest               # prove the rules work

WHAT THIS IS FOR

Debugging an SMS parser needs the *shape* of a message — which keywords appear,
in what order, with what separators. It does not need who you paid or how much.
So this replaces values while preserving shape:

    Sent Rs.1358.00 From HDFC Bank A/C *5261 To Mr Gadipudi Khadri On 10/08/26
    Sent Rs.9999.99 From HDFC Bank A/C *9999 To PERSON_a3f1        On 99/99/99

The template is intact; the facts are gone.

CONSISTENT PSEUDONYMS

The same name maps to the same PERSON_xxxx every time, so duplicate detection
and per-merchant grouping still work in the redacted file. The mapping is a
salted hash: with the default random salt it cannot be reversed even by someone
who guesses the name, because the salt is discarded when the process exits.

Use --salt if you need two separate exports to line up. Anything you can
reproduce, an attacker with your file can also reproduce, so only do that when
cross-file consistency actually matters.

WHAT IS DELIBERATELY KEPT

Bank sender IDs (AX-HDFCBK), payment-app handles (@ybl, @okicici), currency
markers, keywords and punctuation. These are not personal, and removing them
would make the sample useless.

HONEST LIMITS

Regex cannot recognise every name. A name in a format not covered here may
survive — read the output before sending it. --verify catches the mechanical
patterns; it cannot catch "paid to Ramesh" written in an unexpected way.
"""

import argparse
import hashlib
import os
import re
import sys
from collections import Counter

SALT = os.urandom(16).hex()
COUNTS = Counter()


def tag(kind: str, value: str, width: int = 4) -> str:
    """Stable pseudonym for a value, unique per run unless --salt is given."""
    h = hashlib.sha256((SALT + kind + value.strip().lower()).encode()).hexdigest()[:width]
    return f"{kind}_{h}"


def sub(pattern, repl, text, kind, flags=0):
    def _r(m):
        COUNTS[kind] += 1
        return repl(m) if callable(repl) else repl
    return re.sub(pattern, _r, text, flags=flags)


# Words that look like names to a regex but are not. Without this list the
# name rules chew through ordinary sentences and the sample becomes unreadable.
NOT_NAMES = {
    "hdfc", "icici", "axis", "sbi", "kotak", "yes", "idfc", "indusind", "rbl",
    "amex", "american", "express", "paytm", "phonepe", "gpay", "google", "play",
    "amazon", "flipkart", "swiggy", "zomato", "uber", "ola", "rapido", "airtel",
    "jio", "vodafone", "idea", "netflix", "spotify", "irctc", "razorpay", "payu",
    "bank", "credit", "debit", "card", "account", "acc", "a/c", "upi", "neft",
    "imps", "rtgs", "atm", "pos", "ref", "refno", "rrn", "txn", "transaction",
    "dear", "customer", "user", "sent", "from", "to", "on", "not", "you", "call",
    "sms", "block", "update", "alert", "low", "balance", "funds", "mandate",
    "payee", "beneficiary", "statement", "generated", "delivered", "please",
    "thank", "your", "the", "for", "and", "has", "have", "been", "will", "is",
    "was", "rs", "inr", "usd", "min", "due", "date", "total", "limit", "ltd",
    "limited", "pvt", "private", "services", "service", "solutions", "india",
    "digital", "technologies", "enterprises", "mr", "mrs", "ms", "dr", "shri",
    "smt", "team", "bill", "pay", "payment", "app", "link", "click", "visit",
    "valid", "till", "off", "get", "now", "new", "old", "yesterday",
    # verbs that begin instruction phrases following "to"
    "track", "view", "know", "check", "enable", "disable", "restore", "avoid",
    "redeem", "reset", "activate", "download", "register", "confirm", "verify",
    "continue", "learn", "report", "unblock", "dispute", "add", "added", "make",
}


def looks_like_name(s: str) -> bool:
    words = [w for w in re.split(r"\s+", s.strip()) if w]
    if not words or len(words) > 6:
        return False
    if any(ch.isdigit() for ch in s):
        return False
    real = [w for w in words if w.lower().strip(".,") not in NOT_NAMES]
    if not real:
        return False
    if not all(re.fullmatch(r"[A-Za-z][A-Za-z.'-]*", w) for w in words):
        return False
    # A payee is a proper noun. "track your application" is an instruction that
    # happens to be three words long, and pseudonymising it destroys the very
    # structure this tool exists to preserve.
    return any(w[:1].isupper() for w in real)


def redact(text: str) -> str:
    t = text

    # ---- highest-risk identifiers first ----
    t = sub(r"\b[\w.+-]+@[\w-]+\.[\w.]{2,}\b", "EMAIL_REDACTED", t, "email")
    t = sub(r"\b[A-Z]{5}\d{4}[A-Z]\b", "PANXXXXXXXXX", t, "pan")
    t = sub(r"\b\d{4}\s?\d{4}\s?\d{4}\b", "AADHAAR_REDACTED", t, "aadhaar")
    t = sub(r"\b[A-Z]{4}0[A-Z0-9]{6}\b", "IFSC0XXXXXX", t, "ifsc")
    # 13-19 digit runs are card numbers; do this before generic long-digit rules
    t = sub(r"\b\d{13,19}\b", lambda m: "9" * len(m.group()), t, "card_or_long_id")

    # ---- UPI handles: keep the PSP, pseudonymise the identity ----
    def _vpa(m):
        handle, psp = m.group(1), m.group(2)
        if re.fullmatch(r"\+?\d{10,13}", handle):
            return f"9999999999@{psp}"
        return f"{tag('VPA', handle)}@{psp}"
    t = sub(r"\b([A-Za-z0-9._-]{2,})@(ybl|okicici|okhdfcbank|oksbi|okaxis|paytm|apl|ibl|axl|upi|hdfcbank|icici|sbi|axisbank)\b",
            _vpa, t, "vpa", flags=re.I)

    # ---- phone numbers (Indian mobile, service numbers, toll-free) ----
    t = sub(r"(?<!\d)(?:\+91[\-\s]?)?[6-9]\d{9}(?!\d)", "9999999999", t, "phone")
    t = sub(r"\b1800[\-\s]?\d{3,8}\b", "1800XXXXXX", t, "helpline")

    # ---- account and card tails: keep the masking style, blank the digits ----
    t = sub(r"\b(a/c|acct|account|ac|card|acc)\s*(no\.?)?\s*[:.]?\s*((?:x|\*){0,4})(\d{3,6})\b",
            lambda m: f"{m.group(1)}{' ' if m.group(2) is None else ' '+m.group(2)+' '}"
                      f"{m.group(3)}{'9'*len(m.group(4))}".replace("  ", " "),
            t, "account", flags=re.I)
    t = sub(r"\b((?:x|\*){1,4})(\d{4,6})\b",
            lambda m: m.group(1) + "9" * len(m.group(2)), t, "masked_tail", flags=re.I)

    # ---- reference / transaction / application ids ----
    t = sub(r"\b(ref(?:no|erence)?|rrn|utr|txn(?:\s*id)?|transaction no\.?|umn|sr|awb|order id|application)\s*[:.\-]?\s*([A-Za-z0-9_@\-]{4,40})",
            lambda m: f"{m.group(1)} " + re.sub(r"[A-Za-z0-9]", "9", m.group(2)),
            t, "reference", flags=re.I)
    t = sub(r"\b(?:IHL|MHL|SR)[_A-Z0-9]{6,}\b", "APPLICATION_ID", t, "application_id")

    # ---- URLs: keep the domain shape, drop the path (paths carry tokens) ----
    t = sub(r"https?://[^\s]+", "https://REDACTED.LINK/x", t, "url")
    t = sub(r"\b(?:[a-z0-9-]+\.)+(?:io|in|com|co|me|bank)\b(?:/[^\s]*)?",
            "REDACTED.LINK/x", t, "short_url", flags=re.I)

    # ---- amounts: preserve the notation, replace the value ----
    def _amt(m):
        return m.group(1) + re.sub(r"\d", "9", m.group(2))
    t = sub(r"((?:INR|Rs\.?|₹)\s*)([\d,]+(?:\.\d{1,2})?)", _amt, t, "amount", flags=re.I)
    t = sub(r"([\d,]+(?:\.\d{1,2})?)(\s*(?:INR|Rs\.?|₹))",
            lambda m: re.sub(r"\d", "9", m.group(1)) + m.group(2), t, "amount", flags=re.I)

    # ---- dates ----
    t = sub(r"\b\d{1,2}[/\-]\d{1,2}[/\-]\d{2,4}\b", "99/99/99", t, "date")
    t = sub(r"\b\d{1,2}[\s\-]?[A-Za-z]{3}[\s\-]?\d{2,4}\b", "99-XXX-99", t, "date")
    t = sub(r"\b\d{1,2}:\d{2}(:\d{2})?\b", "99:99", t, "time")

    # ---- names, last: everything above has already been neutralised ----
    def _named(m):
        prefix, name = m.group(1), m.group(2).rstrip()
        if not looks_like_name(name):
            return m.group(0)
        return f"{prefix}{tag('PERSON', name)}"

    # "To <name>" at end of line or before a keyword — the HDFC UPI shape
    t = sub(r"\b(To\s+)([A-Za-z][A-Za-z\s.'-]{2,40}?)(?=\s*(?:\n|$|On\b|Ref\b|UPI\b))",
            _named, t, "name", flags=re.I)
    t = sub(r"\b(from\s+)([A-Z][A-Za-z\s.'-]{2,40}?)(?=\s*(?:\n|$|on\b|ref\b|\.))",
            _named, t, "name")
    t = sub(r"\b(Dear\s+)([A-Za-z][A-Za-z\s.'-]{2,40}?)(?=\s*[,\n]|$)", _named, t, "name")
    t = sub(r"\b(received by\s+)([A-Za-z][A-Za-z\s.'-]{2,40}?)(?=\s*(?:on\b|at\b|[,.\n]|$))",
            _named, t, "name", flags=re.I)
    t = sub(r"\b(payment of [^\n]{0,30}?\bto\s+)([A-Z][A-Za-z\s.'-]{2,40}?)(?=\s*[,.\n]|$)",
            _named, t, "name")
    # Initials followed by a surname: "N V V ANJANEYULU MUTYALA"
    t = sub(r"\b((?:[A-Z]\s+){1,4}[A-Z]{2,}(?:\s+[A-Z]{2,})*)\b",
            lambda m: tag("PERSON", m.group(1)), t, "name_initials")
    # ALL-CAPS runs of 2+ words are almost always a payee name in these SMS
    t = sub(r"\b([A-Z]{2,}(?:\s+[A-Z]{1,}){1,4})\b",
            lambda m: tag("PERSON", m.group(1)) if looks_like_name(m.group(1)) else m.group(1),
            t, "name_caps")

    return t


# --------------------------------------------------------------------------
# Verification: catch anything the redactor missed.
# --------------------------------------------------------------------------
LEAKS = [
    ("email address", r"\b[\w.+-]+@[\w-]+\.[\w.]{2,}\b"),
    ("phone number", r"(?<!\d)(?:\+91[\-\s]?)?[6-9]\d{9}(?!\d)"),
    ("long digit run", r"(?<!\d)\d{11,}(?!\d)"),
    ("PAN", r"\b[A-Z]{5}\d{4}[A-Z]\b"),
    ("IFSC", r"\b[A-Z]{4}0[A-Z0-9]{6}\b"),
    ("live URL path", r"https?://(?!REDACTED)[^\s]{6,}"),
    ("unmasked amount", r"(?:INR|Rs\.?|₹)\s*\d*[1-8]\d*"),
]

# The redactor writes 9s and fixed placeholders. Those match several leak
# patterns, and reporting them would train the reader to ignore the warnings —
# which is worse than not warning at all.
PLACEHOLDER = re.compile(
    r"^(?:[9X]+|9{4}[\-/]9{2}[\-/]9{2}|REDACTED\S*|EMAIL_REDACTED|AADHAAR_REDACTED"
    r"|PANXXXXXXXXX|IFSC0XXXXXX|APPLICATION_ID|1800XXXXXX)$"
)


def is_placeholder(s: str) -> bool:
    core = s.strip()
    if PLACEHOLDER.match(core):
        return True
    # any run made only of the fill digit, separators and mask characters
    return bool(re.fullmatch(r"[9Xx*_\-/.:,\s]+", core))


def verify(text: str) -> int:
    found = 0
    for name, pat in LEAKS:
        for m in re.finditer(pat, text):
            hit = m.group()
            if is_placeholder(hit):
                continue
            # "Rs.999" is fully redacted; the amount rule can still match the 9s
            if name == "unmasked amount" and not re.search(r"[1-8]", hit):
                continue
            print(f"  POSSIBLE {name}: {hit[:60]}", file=sys.stderr)
            found += 1
    return found


SAMPLES = [
    "Sent Rs.1358.00\nFrom HDFC Bank A/C *5261\nTo Mr Gadipudi Khadri Sri Sa\nOn 10/08/26\nRef 127698382602\nNot You?\nCall 18002586161",
    "Dear ADABALAPHANI KUMAR, Here's the link to pay INR 4910.00 towards your HDFC Bank Credit Card dues. Pay here https://hdfcbk.io/HDFCBK/s/onWdVKlq",
    "Rs.450 debited from a/c XX1234 to VPA ramesh.kumar@ybl on 05-08-25. Ref 512345678901",
    "We have received your ICICI Bank Mortgage Loan application IHL_526482296174136. Track: https://icici.co/ICICIT/j/06fee2",
    "Delivered: Card for ICICI Bank Account XX0570 delivered by Blue Dart on 15-JAN-25 and received by PHANI KUMAR At 13:31.",
    "Rs.500 debited from a/c XX1234 to VPA 9876543210@paytm on 05-08-25",
]


def selftest() -> int:
    failures = 0
    print("Redaction self-test\n" + "=" * 70)
    for s in SAMPLES:
        out = redact(s)
        print("\nBEFORE: " + s.replace("\n", " | ")[:110])
        print("AFTER : " + out.replace("\n", " | ")[:110])
        leaks = verify(out)
        if leaks:
            failures += leaks
            print("  ^^ LEAK DETECTED")

    # consistency: same name -> same pseudonym
    a = redact("To Ramesh Kumar\nOn 01/01/25")
    b = redact("To Ramesh Kumar\nOn 02/02/25")
    ta = re.search(r"PERSON_\w+", a)
    tb = re.search(r"PERSON_\w+", b)
    same = ta and tb and ta.group() == tb.group()
    print(f"\nconsistent pseudonyms: {'OK' if same else 'FAILED'}")
    if not same:
        failures += 1

    # different names must not collide
    c = redact("To Suresh Patel\nOn 01/01/25")
    tc = re.search(r"PERSON_\w+", c)
    distinct = tc and ta and tc.group() != ta.group()
    print(f"distinct names differ: {'OK' if distinct else 'FAILED'}")
    if not distinct:
        failures += 1

    # structure preserved
    keep = redact(SAMPLES[0])
    struct = all(k in keep for k in ("Sent", "From HDFC Bank", "To ", "Ref", "Not You?"))
    print(f"structure preserved  : {'OK' if struct else 'FAILED'}")
    if not struct:
        failures += 1

    print("\n" + "=" * 70)
    print("PASS — safe to share" if failures == 0 else f"FAIL — {failures} issue(s)")
    return 1 if failures else 0


def main():
    global SALT
    ap = argparse.ArgumentParser(description="Redact PII from bank SMS before sharing")
    ap.add_argument("file", nargs="?", help="input file; omit to read stdin")
    ap.add_argument("--salt", help="fixed salt, so pseudonyms match across runs")
    ap.add_argument("--verify", action="store_true", help="scan a file for leftover PII")
    ap.add_argument("--selftest", action="store_true", help="prove the rules work")
    ap.add_argument("--quiet", action="store_true", help="suppress the summary")
    args = ap.parse_args()

    if args.salt:
        SALT = args.salt
    if args.selftest:
        sys.exit(selftest())

    text = open(args.file, encoding="utf-8", errors="replace").read() if args.file else sys.stdin.read()

    if args.verify:
        n = verify(text)
        print(f"\n{'CLEAN' if n == 0 else str(n) + ' possible leak(s)'} — "
              f"{'nothing matched the PII patterns' if n == 0 else 'review the lines above'}",
              file=sys.stderr)
        sys.exit(1 if n else 0)

    out = redact(text)
    sys.stdout.write(out)

    if not args.quiet:
        print("\n" + "-" * 60, file=sys.stderr)
        for k, v in sorted(COUNTS.items(), key=lambda kv: -kv[1]):
            print(f"  {v:5d}  {k}", file=sys.stderr)
        left = verify(out)
        print("-" * 60, file=sys.stderr)
        print("  no PII patterns remain" if left == 0
              else f"  {left} possible leak(s) — READ THE OUTPUT BEFORE SENDING",
              file=sys.stderr)
        print("  Regex cannot catch every name. Skim the output regardless.",
              file=sys.stderr)


if __name__ == "__main__":
    main()
