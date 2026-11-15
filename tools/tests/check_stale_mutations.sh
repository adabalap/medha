#!/usr/bin/env bash
# Mutation test for tools/check_stale.py. Every case runs against a real
# copy of the tree, not a fixture: the bug class this guards is "a file is
# on disk that should not be", which a fixture cannot represent honestly.
set -uo pipefail
SRC="$(cd "$(dirname "$0")/../.." && pwd)"
W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT
cp -r "$SRC" "$W/t"
T="$W/t"

# An orphan the manifest catches and removed.txt says nothing about: this is
# the case local.txt is allowed to claim.
UNKNOWN="app/src/main/java/com/adabala/medha/legacy/OldHelper.kt"
UNKNOWN_DIR="app/src/main/java/com/adabala/medha/legacy/"
# A removal Medha recorded on purpose: local.txt must NOT be able to claim it.
RECORDED="app/src/main/java/com/adabala/medha/connectors/SmsConnector.kt"

pass=0; fail=0
run() { (cd "$T" && python3 tools/check_stale.py 2>&1); }
code() { (cd "$T" && python3 tools/check_stale.py >/dev/null 2>&1; echo $?); }

check() { # name expected_code must_contain
  local name="$1" want="$2" needle="${3:-}"
  local got out
  got="$(code)"; out="$(run)"
  if [ "$got" != "$want" ]; then
    echo "FAIL  $name: exit $got, wanted $want"; fail=$((fail+1)); return
  fi
  if [ -n "$needle" ] && ! grep -qF "$needle" <<<"$out"; then
    echo "FAIL  $name: output missing '$needle'"; fail=$((fail+1)); return
  fi
  echo "ok    $name"; pass=$((pass+1))
}
absent() { # name needle -- must NOT appear in the report
  if run | grep -qF "$2"; then echo "FAIL  $1"; fail=$((fail+1));
  else echo "ok    $1"; pass=$((pass+1)); fi
}
reset_local() { grep '^#' "$SRC/tools/local.txt" > "$T/tools/local.txt"; }
plant() { mkdir -p "$(dirname "$T/$1")"; echo "// planted" > "$T/$1"; }

echo "== 1. clean tree =="
check "clean tree passes" 0 "nothing outside the manifest"

echo "== 2. an unrecorded orphan =="
plant "$UNKNOWN"
check "unrecorded orphan fails" 1 "$UNKNOWN"
check "it gets a git rm" 1 "git rm"
check "it offers local.txt" 1 "list them in tools/local.txt"

echo "== 3. claimed by exact path =="
reset_local; echo "$UNKNOWN" >> "$T/tools/local.txt"
check "exact path claim passes" 0 "1 local path(s) claimed"

echo "== 4. claimed by directory prefix =="
reset_local; echo "$UNKNOWN_DIR" >> "$T/tools/local.txt"
check "directory claim passes" 0 "local path(s) claimed"

echo "== 5. a near-miss prefix must NOT claim it =="
reset_local; echo "app/src/main/java/com/adabala/medha/legac/" >> "$T/tools/local.txt"
check "sibling dir does not claim" 1 "$UNKNOWN"

echo "== 6. a bare path must not act as a prefix =="
reset_local; echo "app/src/main/java/com/adabala/medha/legacy" >> "$T/tools/local.txt"
check "bare dir name does not claim children" 1 "$UNKNOWN"

echo "== 7. one claimed, one not =="
reset_local; echo "$UNKNOWN" >> "$T/tools/local.txt"
echo "stale" > "$T/docs/old-plan.md"
check "unclaimed orphan still fails" 1 "docs/old-plan.md"
absent "claimed file stays out of the report" "$UNKNOWN"
rm "$T/docs/old-plan.md" "$T/$UNKNOWN"

echo "== 8. a consumer's own file =="
reset_local
echo "my notes" > "$T/docs/my-device-notes.md"
check "consumer file flagged when unclaimed" 1 "docs/my-device-notes.md"
echo "docs/my-device-notes.md" >> "$T/tools/local.txt"
check "consumer file passes when claimed" 0 "nothing outside the manifest"
rm "$T/docs/my-device-notes.md"

echo "== 9. claiming a file the release ships =="
reset_local; echo "docs/TESTING.md" >> "$T/tools/local.txt"
check "pointless claim is a note, not a failure" 0 "which this release ships"

echo "== 10. a RECORDED removal cannot be claimed =="
reset_local; plant "$RECORDED"
check "recorded removal fails unclaimed" 1 "$RECORDED"
check "it carries its recorded reason" 1 "removed in v0.9.0"
echo "$RECORDED" >> "$T/tools/local.txt"
check "recorded removal still fails when claimed" 1 "$RECORDED"
check "and says why the claim was ignored" 1 "deliberately ignored"
reset_local
echo "app/src/main/java/com/adabala/medha/connectors/" >> "$T/tools/local.txt"
check "a directory claim cannot launder it either" 1 "$RECORDED"
rm -f "$T/$RECORDED"

echo "== 11. all eight of the v0.18.2 orphans are now recorded =="
reset_local
for p in app/src/full/AndroidManifest.xml \
         app/src/main/res/drawable/ic_sms.xml \
         docs/apply-minimal-fix.sh \
         docs/verification-harness.kt.txt \
         docs/verification-harness-rag.kt.txt \
         docs/verification-harness-sched.kt.txt \
         tools/REDACTION.md tools/redact.py; do plant "$p"; done
out="$(run)"
miss=0
for p in app/src/full/AndroidManifest.xml \
         app/src/main/res/drawable/ic_sms.xml \
         docs/apply-minimal-fix.sh \
         docs/verification-harness.kt.txt \
         docs/verification-harness-rag.kt.txt \
         docs/verification-harness-sched.kt.txt \
         tools/REDACTION.md tools/redact.py; do
  grep -qF "$p" <<<"$out" || { echo "   missing: $p"; miss=1; }
done
if [ "$miss" -eq 0 ] && grep -qF "unrecorded release" <<<"$out"; then
  echo "ok    all eight named, with reasons"; pass=$((pass+1))
else
  echo "FAIL  not all eight named with reasons"; fail=$((fail+1))
fi
# A file recorded in removed.txt is ALSO absent from the manifest. It must be
# reported once, under its reason -- not once there and again as "not shipped".
dup="$(sed -n '/Delete them:/,/^check_stale:/p' <<<"$out" \
       | grep -oE '[A-Za-z0-9_./-]+\.(kt|xml|sh|txt|md|py)' | sort | uniq -d)"
if [ -z "$dup" ]; then
  echo "ok    no path is listed twice in the git rm"; pass=$((pass+1))
else
  echo "FAIL  duplicated in git rm: $dup"; fail=$((fail+1))
fi
if grep -qE '^check_stale: 8 stale file\(s\)$' <<<"$out"; then
  echo "ok    the count matches the number of files"; pass=$((pass+1))
else
  echo "FAIL  wrong count: $(grep '^check_stale:' <<<"$out")"; fail=$((fail+1))
fi
if ! grep -qF "not shipped by this release:" <<<"$out"; then
  echo "ok    recorded removals do not also appear as unexplained orphans"; pass=$((pass+1))
else
  echo "FAIL  recorded removals repeated under 'not shipped'"; fail=$((fail+1))
fi
for p in app/src/full/AndroidManifest.xml app/src/main/res/drawable/ic_sms.xml \
         docs/apply-minimal-fix.sh docs/verification-harness.kt.txt \
         docs/verification-harness-rag.kt.txt docs/verification-harness-sched.kt.txt \
         tools/REDACTION.md tools/redact.py; do rm -f "$T/$p"; done
rmdir "$T/app/src/full" 2>/dev/null

echo "== 12. no manifest at all =="
reset_local; mv "$T/tools/manifest.txt" "$W/held"
check "missing manifest degrades, does not crash" 0 "known removal(s) absent"
mv "$W/held" "$T/tools/manifest.txt"

echo "== 13. TRACKED is shared, not duplicated =="
reset_local
if (cd "$T" && python3 -c "
import sys; sys.path.insert(0,'tools')
import make_manifest as m, check_stale as c
assert c.TRACKED is m.TRACKED, 'TRACKED not shared'
assert c.IGNORE_PARTS is m.IGNORE_PARTS, 'IGNORE_PARTS not shared'
assert c.IGNORE_SUFFIX is m.IGNORE_SUFFIX, 'IGNORE_SUFFIX not shared'" >/dev/null 2>&1); then
  echo "ok    check_stale imports the roots rather than copying them"; pass=$((pass+1))
else
  echo "FAIL  root lists are duplicated again"; fail=$((fail+1))
fi

echo "== 14. manifest describes the tree exactly =="
reset_local
if (cd "$T" && python3 -c "
import sys, pathlib; sys.path.insert(0,'tools')
import make_manifest as m
disk = set(m.tracked_files())
mf = {l.strip() for l in pathlib.Path('tools/manifest.txt').read_text().splitlines()
      if l.strip() and not l.startswith('#')}
assert not disk - mf, f'on disk, not in manifest: {sorted(disk-mf)[:5]}'
assert not mf - disk, f'in manifest, not on disk: {sorted(mf-disk)[:5]}'" >/dev/null 2>&1); then
  echo "ok    manifest and tree agree in both directions"; pass=$((pass+1))
else
  echo "FAIL  manifest has drifted from the tree"; fail=$((fail+1))
fi

echo
echo "mutation: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
