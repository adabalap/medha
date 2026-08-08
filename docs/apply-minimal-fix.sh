#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# MINIMAL FIX for the v0.1.1 build failure. Run this in your repo root FIRST,
# independently of the larger refactor, and confirm CI goes green.
#
# The tagged commit still carries a Room-annotated DAO from before the SQLite
# migration. Nothing else references it: MemoryRepository, Retriever,
# LocalServer and InferenceService all talk to MedhaDatabase's hand-written SQL.
# ---------------------------------------------------------------------------
set -euo pipefail

DAO="app/src/main/java/com/example/litertservice/data/Daos.kt"

if [ ! -f "$DAO" ]; then
  echo "No $DAO present — your working tree is already past this failure."
  exit 0
fi

echo "Checking nothing references the DAO types..."
if grep -rn --include='*.kt' -E "ConversationDao|MessageDao|DocumentDao|ChunkDao" app/src \
     | grep -v "^$DAO:"; then
  echo "ERROR: something still references a DAO type. Resolve those first."
  exit 1
fi

git rm "$DAO"
git commit -m "fix(build): drop orphaned Room DAO left over from the SQLite migration

data/Daos.kt imported androidx.room.* but the module has no Room dependency
and no KSP/KAPT annotation processor, so every import was unresolved.

The data layer moved to hand-written SQL in data/MedhaDatabase.kt; this file
was missed. Nothing references its types."

echo
echo "Done. Push and watch the build."
