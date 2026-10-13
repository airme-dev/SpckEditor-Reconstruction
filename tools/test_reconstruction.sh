#!/usr/bin/env sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT INT TERM
javac -Xlint:all -d "$OUT" \
  "$ROOT/app/src/main/java/com/reconstruction/spck/ArchiveSafety.java" \
  "$ROOT/app/src/main/java/com/reconstruction/spck/ProjectArchiveIO.java" \
  "$ROOT/app/src/main/java/com/reconstruction/spck/LocalGitEngine.java" \
  "$ROOT/app/src/main/java/com/reconstruction/spck/ProjectCommandRunner.java" \
  "$ROOT/app/src/main/java/com/reconstruction/spck/WorkspaceStateStore.java" \
  "$ROOT/tools/ArchiveSafetyTest.java" \
  "$ROOT/tools/ProjectArchiveIOTest.java" \
  "$ROOT/tools/LocalGitEngineTest.java" \
  "$ROOT/tools/ProjectCommandRunnerTest.java" \
  "$ROOT/tools/WorkspaceStateStoreTest.java"
java -cp "$OUT" com.reconstruction.spck.ArchiveSafetyTest
java -cp "$OUT" com.reconstruction.spck.ProjectArchiveIOTest
java -cp "$OUT" com.reconstruction.spck.LocalGitEngineTest
java -cp "$OUT" com.reconstruction.spck.ProjectCommandRunnerTest
java -cp "$OUT" com.reconstruction.spck.WorkspaceStateStoreTest
sh -n "$ROOT/gradlew"
echo "PASS: Unix Gradle bootstrap shell syntax"
python3 "$ROOT/tools/verify_reconstruction.py" "$ROOT"
sh "$ROOT/tools/check_injected_tools_js.sh"
