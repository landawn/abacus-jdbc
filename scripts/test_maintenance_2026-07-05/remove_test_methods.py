#!/usr/bin/env python3
"""
Reusable helper for Task 1: remove specific test methods by (file, method,
start_line). Driven by a JSON targets file so the exact operation is recorded
and re-runnable (idempotent: a target already gone is skipped with a note).

Targets JSON format:
    [
      {"file": "src/test/.../FooTest.java", "method": "testBar", "start_line": 158,
       "reason": "duplicate of AbstractQueryTest.testX"},
      ...
    ]

Removal span: the method's annotation block through its closing brace, plus one
immediately-preceding blank separator line (to avoid leaving a double blank).

Usage:
    python remove_test_methods.py --targets removals.json [--dry-run]
"""
from __future__ import annotations

import argparse
import json
import sys

from java_test_parser import parse_file


def remove_from_file(path: str, targets: list, dry_run: bool) -> list:
    """targets: list of dicts with 'method' and optional 'start_line'. Returns
    list of result strings."""
    results = []
    with open(path, "r", encoding="utf-8", newline="") as fh:
        raw = fh.read()
    # Preserve line ending style.
    newline = "\r\n" if "\r\n" in raw else "\n"
    tf = parse_file(path)

    # Resolve each target to a (start_line, end_line) span using the parser.
    spans = []
    for t in targets:
        name = t["method"]
        want_line = t.get("start_line")
        cands = [m for m in tf.methods if m.name == name]
        if want_line is not None:
            cands = [m for m in cands if abs(m.start_line - want_line) <= 3] or cands
        if not cands:
            results.append(f"SKIP {path}::{name} (not found; already removed?)")
            continue
        if len(cands) > 1 and want_line is None:
            results.append(f"AMBIGUOUS {path}::{name} ({len(cands)} matches); specify start_line")
            continue
        m = cands[0]
        spans.append((m.start_line, m.end_line, name, t.get("reason", "")))

    if not spans:
        return results

    # Work on physical lines (1-based). Remove highest line numbers first so
    # earlier indices stay valid.
    lines = raw.split(newline)
    # lines[i] is line i+1
    spans.sort(key=lambda s: s[0], reverse=True)
    for start_line, end_line, name, reason in spans:
        s = start_line - 1  # 0-based inclusive
        e = end_line - 1     # 0-based inclusive
        # Consume one preceding blank separator line if present.
        if s - 1 >= 0 and lines[s - 1].strip() == "":
            s -= 1
        del lines[s : e + 1]
        results.append(f"REMOVED {path}::{name} lines {start_line}-{end_line} ({reason})")

    if not dry_run:
        with open(path, "w", encoding="utf-8", newline="") as fh:
            fh.write(newline.join(lines))
    return results


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--targets", required=True)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    with open(args.targets, "r", encoding="utf-8") as fh:
        targets = json.load(fh)

    by_file = {}
    for t in targets:
        by_file.setdefault(t["file"], []).append(t)

    all_results = []
    for path, ts in by_file.items():
        all_results.extend(remove_from_file(path, ts, args.dry_run))

    for r in all_results:
        print(r)
    removed = sum(1 for r in all_results if r.startswith("REMOVED"))
    print(f"\n[summary] removed={removed} "
          f"skipped={sum(1 for r in all_results if r.startswith('SKIP'))} "
          f"ambiguous={sum(1 for r in all_results if r.startswith('AMBIGUOUS'))}"
          f"{' (DRY RUN)' if args.dry_run else ''}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
