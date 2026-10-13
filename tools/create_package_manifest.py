#!/usr/bin/env python3
"""Generate a deterministic per-file SHA-256 manifest for the source package."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "PACKAGE_MANIFEST.json"
EXCLUDED_PARTS = {".git", ".gradle", "build", "__pycache__"}
EXCLUDED_SUFFIXES = {".pyc", ".iml"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def included(path: Path) -> bool:
    if path == MANIFEST or any(part in EXCLUDED_PARTS for part in path.relative_to(ROOT).parts):
        return False
    if path.suffix in EXCLUDED_SUFFIXES or path.name in {".DS_Store", "Thumbs.db"}:
        return False
    return path.is_file()


def main() -> int:
    paths = sorted((p for p in ROOT.rglob("*") if included(p)), key=lambda p: p.relative_to(ROOT).as_posix())
    entries = []
    for path in paths:
        entries.append({
            "path": path.relative_to(ROOT).as_posix(),
            "bytes": path.stat().st_size,
            "sha256": sha256(path),
        })
    payload = {
        "schemaVersion": 1,
        "package": "Spck Editor Reconstruction",
        "phase": 9,
        "versionName": "0.9.0-reconstruction-final",
        "manifestExcludesItself": True,
        "fileCount": len(entries),
        "files": entries,
    }
    MANIFEST.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Wrote {MANIFEST.name}: {len(entries)} files with SHA-256 hashes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
