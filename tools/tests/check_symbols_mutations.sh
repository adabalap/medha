#!/usr/bin/env bash
# Mutation test for tools/check_symbols.py.
#
# The checker said OK on a tree that would not compile, for five releases.
# Every case here is a way it could say OK wrongly, or FAIL wrongly -- the
# second matters just as much, because a checker that cries wolf gets its
# step deleted from the workflow.
set -uo pipefail
SRC="$(cd "$(dirname "$0")/../.." && pwd)"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
cp -r "$SRC" "$W/t"; T="$W/t"
P="$T/app/src/main/java/com/adabala/medha/probe"
pass=0; fail=0

run()  { (cd "$T" && python3 tools/check_symbols.py app/src/main/java 2>&1); }
code() { (cd "$T" && python3 tools/check_symbols.py app/src/main/java >/dev/null 2>&1; echo $?); }
check() { # name expected_code must_contain
  local name="$1" want="$2" needle="${3:-}" got out
  got="$(code)"; out="$(run)"
  if [ "$got" != "$want" ]; then
    echo "FAIL  $name: exit $got, wanted $want"; fail=$((fail+1)); return; fi
  if [ -n "$needle" ] && ! grep -qF "$needle" <<<"$out"; then
    echo "FAIL  $name: output missing '$needle'"; fail=$((fail+1)); return; fi
  echo "ok    $name"; pass=$((pass+1))
}
clean() { rm -rf "$P"; mkdir -p "$P"; }

echo "== 1. baseline =="
clean; rmdir "$P"
check "the real tree passes" 0 "check_symbols: OK"

echo "== 2. the bug CI found =="
# BriefActivity calls toast() twice; MainActivity declares it private.
python3 - "$T" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])/"app/src/main/java/com/adabala/medha/BriefActivity.kt"
s = p.read_text().replace("import android.widget.Toast\n", "")
i = s.index("    // Deliberately a second copy"); j = s.index("\n}", i)
p.write_text(s[:i].rstrip("\n ") + "\n}" + s[j+2:])
PY
check "cross-file private call fails" 1 "toast()"
check "and names the right file" 1 "BriefActivity.kt"
# Derived, not a literal. This read `"2 call sites"` and went red the
# moment BriefActivity grew a third toast() -- asserting a count that
# tracks the tree rather than the behaviour under test. What matters is
# that EVERY call site is reported, so the number comes from the file.
CALLS=$(grep -c '^\s*toast(' "$T/app/src/main/java/com/adabala/medha/BriefActivity.kt" || true)
check "and counts every call site" 1 "$CALLS call sites"
cp "$SRC/app/src/main/java/com/adabala/medha/BriefActivity.kt" \
   "$T/app/src/main/java/com/adabala/medha/BriefActivity.kt"
check "restored tree passes again" 0 "check_symbols: OK"

echo "== 3. private is file-scoped, not global =="
clean
cat > "$P/A.kt" <<'K'
package com.adabala.medha.probe
class ZzA {
    private fun zzHelper(): Int = 1
    fun useOwn(): Int = zzHelper()
}
K
check "same-file private call passes" 0 "check_symbols: OK"
cat > "$P/B.kt" <<'K'
package com.adabala.medha.probe
class ZzB { fun useOther(): Int = zzHelper() }
K
check "other-file private call fails" 1 "zzHelper()"
check "blamed on the calling file" 1 "B.kt"

echo "== 4. visibility that really is module-wide must NOT be flagged =="
for mod in "internal" "" "open"; do
  clean
  cat > "$P/A.kt" <<K
package com.adabala.medha.probe
$mod fun zzShared(): Int = 1
K
  cat > "$P/B.kt" <<'K'
package com.adabala.medha.probe
val zzUse: Int = zzShared()
K
  label="${mod:-public}"
  check "cross-file '$label' call passes" 0 "check_symbols: OK"
done
clean
# NB: members go on their own line. DECL is anchored to line starts, so a
# member declared inline (`class X { fun y() = 1 }`) is invisible to it --
# a real limitation, and one that silently made three of these cases pass
# for the wrong reason until the fixtures were written out properly.
cat > "$P/A.kt" <<'K'
package com.adabala.medha.probe
abstract class ZzBase {
    protected fun zzProt(): Int = 1
}
K
cat > "$P/B.kt" <<'K'
package com.adabala.medha.probe
class ZzSub : ZzBase() {
    fun x(): Int = zzProt()
}
K
check "cross-file 'protected' call passes" 0 "check_symbols: OK"

echo "== 5. private EXTENSION functions are scoped too =="
clean
cat > "$P/A.kt" <<'K'
package com.adabala.medha.probe
private fun String.zzExt(): Int = 1
val zzOwn: Int = "a".zzExt()
K
cat > "$P/B.kt" <<'K'
package com.adabala.medha.probe
val zzOther: Int = zzExt()
K
check "private extension does not leak across files" 1 "zzExt()"
clean
cat > "$P/A.kt" <<'K'
package com.adabala.medha.probe
fun String.zzExt(): Int = 1
K
cat > "$P/B.kt" <<'K'
package com.adabala.medha.probe
val zzOther: Int = zzExt()
K
check "public extension still resolves across files" 0 "check_symbols: OK"

echo "== 6. private vals are scoped =="
clean
cat > "$P/A.kt" <<'K'
package com.adabala.medha.probe
private val zzFn: () -> Int = { 1 }
K
cat > "$P/B.kt" <<'K'
package com.adabala.medha.probe
val zzOther: Int = zzFn()
K
check "private val does not leak across files" 1 "zzFn()"

echo "== 6b. a private extension on a BUILTIN type is ordinary code =="
# `private fun String.foo()` makes DECL capture the RECEIVER. Recorded as a
# declaration it means "String is declared private here", and String is in
# BUILTIN -- so the allowlist audit fires on a file that is perfectly fine.
# This was a live bug in the audit, found by this suite rather than by CI.
clean
cat > "$P/A.kt" <<'K'
package com.adabala.medha.probe
private fun String.zzExt(): Int = 1
val zzOwn: Int = "a".zzExt()
K
check "private extension on String does not fail the build" 0 "check_symbols: OK"
clean
cat > "$P/A.kt" <<'K'
package com.adabala.medha.probe
private fun Int.zzOnInt(): Int = 1
private fun Long.zzOnLong(): Int = 1
val zzOwn: Int = 1.zzOnInt()
K
check "nor on other allowlisted types" 0 "check_symbols: OK"

echo "== 7. the allowlist cannot hide a project symbol =="
clean
cat > "$P/A.kt" <<'K'
package com.adabala.medha.probe
class ZzA {
    private fun zzMasked(): Int = 1
}
K
python3 - "$T" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])/"tools/check_symbols.py"
s = p.read_text().replace("\nlaunch\n", "\nlaunch\nzzMasked\n", 1)
p.write_text(s)
PY
check "BUILTIN entry that is private-only fails" 1 "zzMasked()"
check "and says where it is declared" 1 "A.kt"
check "and explains the two meanings" 1 "everywhere and compiles nowhere"

echo "== 8. EXPECTED_COLLISIONS is the only way to allow one =="
python3 - "$T" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])/"tools/check_symbols.py"
p.write_text(p.read_text().replace('    "launch",\n', '    "launch",\n    "zzMasked",\n', 1))
PY
check "a documented collision is allowed" 0 "check_symbols: OK"
cp "$SRC/tools/check_symbols.py" "$T/tools/check_symbols.py"

echo "== 9. prose in the allowlist block is not a symbol =="
clean
cat > "$P/B.kt" <<'K'
package com.adabala.medha.probe
val zzR: Int = region()
K
check "a word from a comment line is not allowlisted" 1 "region()"
clean
cat > "$P/B.kt" <<'K'
package com.adabala.medha.probe
val zzR: Int = receiver()
K
check "nor is another one" 1 "receiver()"

echo "== 9b. a callback parameter is a declaration, not an unresolved call =="
clean
# The gap that produced this section: a parameter typed `() -> Unit` is
# invisible to a pattern keyed on a capitalised type name, so every callback
# invocation in the project read as unresolved -- and the checker's own
# remedy line suggests BUILTIN, which is how `toast` got in. Both halves are
# asserted, because fixing the false positive by filing these module-wide
# reintroduces section 6.
cat > "$P/A.kt" <<'K'
package com.adabala.medha.probe
private fun zzAsk(zzWho: String, zzThen: () -> Unit) {
    if (zzWho.isNotEmpty()) zzThen()
}
fun zzGo() = zzAsk("x") { }
K
check "an invoked callback parameter resolves" 0 "check_symbols: OK"

clean
cat > "$P/A.kt" <<'K'
package com.adabala.medha.probe
suspend fun zzLater(zzThen: suspend (Int) -> Unit) { zzThen(1) }
K
check "a suspend function type too" 0 "check_symbols: OK"

clean
# A named argument whose value happens to be parenthesised must NOT become a
# declaration -- that is why the pattern requires the arrow.
cat > "$P/A.kt" <<'K'
package com.adabala.medha.probe
fun zzTake(zzN: Int): Int = zzN
val zzA: Int = zzTake(zzN = (1 + 2))
val zzB: Int = zzMissing()
K
check "a parenthesised named argument is not a declaration" 1 "zzMissing()"

echo "== 10. launch still works both ways =="
clean; rmdir "$P"
if grep -q "private fun launch" "$T/app/src/main/java/com/adabala/medha/BriefActivity.kt" \
   && grep -rq "launch {" "$T/app/src/main/java"; then
  check "project launch() and coroutine launch{} coexist" 0 "check_symbols: OK"
else
  echo "FAIL  the launch collision no longer exists; EXPECTED_COLLISIONS is stale"
  fail=$((fail+1))
fi

echo
echo "mutation: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
