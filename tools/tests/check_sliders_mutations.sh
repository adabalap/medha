#!/usr/bin/env bash
# Tests for tools/check_sliders.py.
#
# This checker exists because of the worst bug Medha has shipped: both
# thermal sliders kept a v0.17.0 placeholder range (0..10 step 1) while
# setupScheduler assigned them headroom fractions (0.85, 0.70). A Material
# Slider validates in onDraw, so the bad value was accepted in silence and
# the crash arrived the first time the view was drawn -- which, for a tab
# that starts GONE, is the moment someone taps Settings. Android drops you
# on the launcher when an activity dies, so it read as "Settings just
# minimises the app", and Device Care started reporting Medha as crashing
# frequently. Eleven checkers saw nothing: it compiled, every symbol
# resolved, every resource existed.
#
# Case 2 is that exact bug, restored, and it must fail. Cases 3 and 4 are
# the guard logic in the FIX, and they must pass -- the first draft reported
# both, reading the 0.05f backoff as the value being assigned. A checker
# that fails correct code never makes it into the workflow, so the
# false-positive cases matter as much as the real one.
set -uo pipefail
SRC="$(cd "$(dirname "$0")/../.." && pwd)"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
cp -r "$SRC" "$W/t"; T="$W/t"
L="$T/app/src/main/res/layout/activity_main.xml"
K="$T/app/src/main/java/com/adabala/medha/MainActivity.kt"
pass=0; fail=0

run()  { (cd "$T" && python3 tools/check_sliders.py 2>&1); }
code() { (cd "$T" && python3 tools/check_sliders.py >/dev/null 2>&1; echo $?); }
check() { local name="$1" want="$2" needle="${3:-}" got out
  got="$(code)"; out="$(run)"
  if [ "$got" != "$want" ]; then
    echo "FAIL  $name: exit $got, wanted $want"; fail=$((fail+1)); return; fi
  if [ -n "$needle" ] && ! grep -qF "$needle" <<<"$out"; then
    echo "FAIL  $name: output missing '$needle'"; fail=$((fail+1)); return; fi
  echo "ok    $name"; pass=$((pass+1)); }
reset() { cp "$SRC/app/src/main/res/layout/activity_main.xml" "$L"
          cp "$SRC/app/src/main/java/com/adabala/medha/MainActivity.kt" "$K"; }

# Replace the pauseSlider's range attributes in the layout.
range() { python3 - "$L" "$1" "$2" "$3" <<'PY'
import pathlib, re, sys
p = pathlib.Path(sys.argv[1]); lo, hi, st = sys.argv[2:5]
s = p.read_text()
i = s.index('android:id="@+id/pauseSlider"')
j = s.index("/>", i)
seg = re.sub(r'android:valueFrom="[^"]*" android:valueTo="[^"]*" android:stepSize="[^"]*"',
             f'android:valueFrom="{lo}" android:valueTo="{hi}" android:stepSize="{st}"',
             s[i:j])
p.write_text(s[:i] + seg + s[j:])
PY
}
# Replace the pauseSlider assignment line in Kotlin.
assign() { python3 - "$K" "$1" <<'PY'
import pathlib, re, sys
p = pathlib.Path(sys.argv[1])
lines = p.read_text().splitlines(keepends=True)
for n, l in enumerate(lines):
    if "pauseSlider.setSnapped(" in l or re.search(r"pauseSlider\.value\s*=", l):
        indent = l[:len(l) - len(l.lstrip())]
        lines[n] = f"{indent}{sys.argv[2]}\n"
        break
p.write_text("".join(lines))
PY
}

echo "== 1. baseline =="
reset
check "the fixed tree passes" 0 "check_sliders: OK"

echo "== 2. the bug that shipped =="
reset; range 0 10 1
assign 'binding.pauseSlider.value = p.getFloat(InferenceService.KEY_THERMAL_PAUSE, 0.85f)'
check "placeholder range + fraction value fails" 1 "pauseSlider"
check "and says it throws when DRAWN, not at assignment" 1 "DRAWN"

echo "== 3. the guard logic in the fix must NOT be reported =="
reset
assign 'binding.pauseSlider.setSnapped(v - 0.05f)'
check "a subtracted step is not the value" 0 "check_sliders: OK"
reset
assign 'binding.pauseSlider.setSnapped(binding.resumeSlider.value - 0.05f)'
check "nor is one subtracted from another slider" 0 "check_sliders: OK"
reset
assign 'binding.pauseSlider.setSnapped(base + 0.10f)'
check "nor an added one" 0 "check_sliders: OK"
reset
assign 'binding.pauseSlider.setSnapped(scale * 0.5f)'
check "nor a multiplied one" 0 "check_sliders: OK"

echo "== 4. literals that ARE the value =="
reset
assign 'binding.pauseSlider.setSnapped(99.0f)'
check "a literal outside the range fails" 1 "outside its XML range"
reset
assign 'binding.pauseSlider.value = 0.33f'
check "an unsnapped literal off-tick fails" 1 "does not"
reset
assign 'binding.pauseSlider.value = p.getFloat(InferenceService.KEY_THERMAL_PAUSE, 9.9f)'
check "a getFloat default outside the range fails" 1 "outside its XML range"
reset
assign 'binding.pauseSlider.setSnapped(p.getFloat(InferenceService.KEY_THERMAL_PAUSE, 0.85f))'
check "a valid snapped default passes" 0 "check_sliders: OK"

echo "== 5. the layout on its own =="
reset; range 1.0 0.5 0.1
check "valueFrom above valueTo fails" 1 "pauseSlider"
reset; range 0 1 0.3
check "a range that is not a whole number of steps fails" 1 "pauseSlider"
reset; range 0.30 1.20 0.05
check "a clean range passes" 0 "check_sliders: OK"

echo "== 6. the checker reports through its exit code =="
reset; range 0 10 1
assign 'binding.pauseSlider.value = p.getFloat(InferenceService.KEY_THERMAL_PAUSE, 0.85f)'
# NB: capture first. `run | grep` would inherit the checker's exit 1 under
# `pipefail` even when grep matches, which reads as the checker being silent.
out6="$(run)"
if [ "$(code)" = "1" ] && grep -qF "problem(s)" <<<"$out6"; then
  echo "ok    a finding is a non-zero exit, not just printed output"; pass=$((pass+1))
else
  echo "FAIL  findings printed but exit code is 0"; fail=$((fail+1))
fi

echo "== 7. it is actually wired into CI =="
reset
if grep -q "check_sliders.py" "$T/.github/workflows/build-apk.yml"; then
  echo "ok    the workflow runs it"; pass=$((pass+1))
else
  echo "FAIL  not in the workflow -- a checker nothing runs catches nothing"
  fail=$((fail+1))
fi

echo
echo "mutation: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
