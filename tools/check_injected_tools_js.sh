#!/usr/bin/env sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT INT TERM
python3 - "$ROOT/app/src/main/java/com/reconstruction/spck/MainActivity.java" "$TMP/ScriptExtractor.java" <<'PY'
from pathlib import Path
import sys
source = Path(sys.argv[1]).read_text(encoding="utf-8")
marker = 'private static final String PROJECT_ARCHIVE_TOOLS_SCRIPT = """'
start = source.index(marker)
start = source.index('"""', start) + 3
end = source.index('""";', start)
content = source[start:end]
wrapper = 'public final class ScriptExtractor {\n  static final String SCRIPT = """' + content + '""";\n  public static void main(String[] args) { System.out.print(SCRIPT); }\n}\n'
Path(sys.argv[2]).write_text(wrapper, encoding="utf-8")
PY
javac -d "$TMP" "$TMP/ScriptExtractor.java"
java -cp "$TMP" ScriptExtractor > "$TMP/injected_tools.js"
if command -v node >/dev/null 2>&1; then
  node --check "$TMP/injected_tools.js"
  echo "PASS: injected project-tools JavaScript syntax"
else
  echo "SKIP: Node.js not installed; Java text-block extraction completed"
fi
