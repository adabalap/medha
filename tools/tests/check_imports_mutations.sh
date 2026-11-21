#!/usr/bin/env bash
# Tests for tools/check_imports.py.
#
# The checker it complements, check_symbols, was wrong four times in two days
# and every one was found by a build failing rather than by a test. This one
# is new, so it gets its tests before it gets trusted.
#
# Half the cases are false-POSITIVE cases -- ordinary code it must not
# complain about. A checker that cries wolf gets its step deleted from the
# workflow, which is worse than not having written it.
set -uo pipefail
SRC="$(cd "$(dirname "$0")/../.." && pwd)"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
cp -r "$SRC" "$W/t"; T="$W/t"
P="$T/app/src/main/java/com/adabala/medha/probe"
Q="$T/app/src/main/java/com/adabala/medha/other"
pass=0; fail=0

run()  { (cd "$T" && python3 tools/check_imports.py app/src/main/java 2>&1); }
code() { (cd "$T" && python3 tools/check_imports.py app/src/main/java >/dev/null 2>&1; echo $?); }
check() { # name expected_code must_contain
  local name="$1" want="$2" needle="${3:-}" got out
  got="$(code)"; out="$(run)"
  if [ "$got" != "$want" ]; then
    echo "FAIL  $name: exit $got, wanted $want"; fail=$((fail+1)); return; fi
  if [ -n "$needle" ] && ! grep -qF "$needle" <<<"$out"; then
    echo "FAIL  $name: output missing '$needle'"; fail=$((fail+1)); return; fi
  echo "ok    $name"; pass=$((pass+1))
}
absent() { if run | grep -qF "$2"; then echo "FAIL  $1"; fail=$((fail+1));
           else echo "ok    $1"; pass=$((pass+1)); fi; }
clean() { rm -rf "$P" "$Q"; mkdir -p "$P" "$Q"; }

echo "== 1. baseline =="
clean; rmdir "$P" "$Q"
check "the real tree passes" 0 "check_imports: OK"

echo "== 2. the other half of the v0.18.3 failure =="
# BriefActivity needed BOTH `private fun toast` AND this import. check_symbols
# can only ever see the first.
python3 - "$T" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])/"app/src/main/java/com/adabala/medha/BriefActivity.kt"
p.write_text(p.read_text().replace("import android.widget.Toast\n", "", 1))
PY
check "a dropped import fails" 1 "Toast"
check "and names the file" 1 "BriefActivity.kt"
cp "$SRC/app/src/main/java/com/adabala/medha/BriefActivity.kt" \
   "$T/app/src/main/java/com/adabala/medha/BriefActivity.kt"
check "restored tree passes again" 0 "check_imports: OK"

echo "== 3. cross-package types =="
clean
cat > "$Q/Thing.kt" <<'K'
package com.adabala.medha.other
class ZzThing(val n: Int)
K
cat > "$P/Use.kt" <<'K'
package com.adabala.medha.probe
val zzA: ZzThing = ZzThing(1)
K
check "used across packages with no import fails" 1 "ZzThing"
cat > "$P/Use.kt" <<'K'
package com.adabala.medha.probe
import com.adabala.medha.other.ZzThing
val zzA: ZzThing = ZzThing(1)
K
check "with the import, passes" 0 "check_imports: OK"
cat > "$P/Use.kt" <<'K'
package com.adabala.medha.probe
import com.adabala.medha.other.ZzThing as ZzAlias
val zzA: ZzAlias = ZzAlias(1)
K
check "an aliased import resolves the alias" 0 "check_imports: OK"

echo "== 4. same package needs no import =="
clean
cat > "$P/A.kt" <<'K'
package com.adabala.medha.probe
class ZzLocal(val n: Int)
K
cat > "$P/B.kt" <<'K'
package com.adabala.medha.probe
val zzB: ZzLocal = ZzLocal(2)
K
check "same-package type needs no import" 0 "check_imports: OK"

echo "== 5. things that are NOT type references =="
clean
cat > "$P/A.kt" <<'K'
package com.adabala.medha.probe
object ZzConsts {
    const val TAG = "zz"
    const val TTL_MS = 1L
    const val CHANNEL_ID = "c"
}
K
check "ALL_CAPS constants are not types" 0 "check_imports: OK"
clean
cat > "$P/A.kt" <<'K'
package com.adabala.medha.probe
val zzS: String = "x"
val zzL: List<Int> = listOf(1)
val zzB: ByteArray = ByteArray(1)
val zzM: HashMap<String, Int> = HashMap()
val zzR: Regex = Regex("a")
K
check "Kotlin default imports are not reported" 0 "check_imports: OK"
clean
cat > "$P/A.kt" <<'K'
package com.adabala.medha.probe
fun <Item> zzFirst(xs: List<Item>): Item = xs.first()
class ZzBox<Payload>(val p: Payload)
K
check "type parameters are not reported" 0 "check_imports: OK"
clean
cat > "$P/A.kt" <<'K'
package com.adabala.medha.probe
// This comment mentions ZzGhostType and so does the string below.
val zzS: String = "ZzGhostType"
K
check "names in comments and strings are not uses" 0 "check_imports: OK"
absent "and ZzGhostType is nowhere in the output" "ZzGhostType"

echo "== 6. nested types resolve through their owner =="
clean
cat > "$Q/Outer.kt" <<'K'
package com.adabala.medha.other
class ZzOuter {
    class ZzInner(val n: Int)
}
K
cat > "$P/Use.kt" <<'K'
package com.adabala.medha.probe
import com.adabala.medha.other.ZzOuter
val zzN: ZzOuter.ZzInner = ZzOuter.ZzInner(1)
K
check "importing the owner is enough for a nested type" 0 "check_imports: OK"

echo "== 7. generated types in the app package =="
clean
cat > "$T/app/src/main/java/com/adabala/medha/ZzGen.kt" <<'K'
package com.adabala.medha
val zzV: String = BuildConfig.VERSION_NAME
val zzId: Int = R.string.app_name
K
check "BuildConfig and R resolve in the app package" 0 "check_imports: OK"
rm -f "$T/app/src/main/java/com/adabala/medha/ZzGen.kt"

echo "== 8. annotations need importing too =="
clean
cat > "$P/A.kt" <<'K'
package com.adabala.medha.probe
@ZzMarker
class ZzUsesMarker
K
check "an unimported annotation is reported" 1 "ZzMarker"

echo "== 9. the allowlist is its own, not check_symbols' =="
clean
# `toast` is a function name, not a type; the two lists must not be merged.
if (cd "$T" && python3 -c "
import sys; sys.path.insert(0,'tools')
import check_imports as ci, check_symbols as cs
assert ci.DEFAULT is not cs.BUILTIN, 'the two allowlists are the same object'
shared = ci.DEFAULT & cs.BUILTIN
assert shared <= {'String','Int','Long','Float','Double','Boolean','Char',
                  'Byte','Short','Any','Unit','Nothing','Pair','Triple',
                  'Result','Regex'}, f'unexpected overlap: {sorted(shared)}'" >/dev/null 2>&1); then
  echo "ok    DEFAULT and BUILTIN are separate lists"; pass=$((pass+1))
else
  echo "FAIL  the two allowlists have been merged or have drifted"; fail=$((fail+1))
fi

echo "== 10. prose in the allowlist block is not an entry =="
clean
cat > "$P/A.kt" <<'K'
package com.adabala.medha.probe
val zzD: Documentation = Documentation()
K
check "a word from a comment line is not allowlisted" 1 "Documentation"

echo
echo "mutation: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
