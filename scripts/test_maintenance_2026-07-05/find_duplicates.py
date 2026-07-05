#!/usr/bin/env python3
"""
Task 1: Find duplicate unit tests.

A duplicate is a test method whose normalized body is identical to another
test method's normalized body (same class or across classes). We report groups
so a human/agent can decide which to keep (the most complete/descriptive) and
which to remove.

Normalization: comments stripped (via the lexical mask in the parser -- the
body still contains comment text, so we additionally strip here), whitespace
collapsed. Method *name* is deliberately ignored so renamed copies collide.

Output: JSON report to stdout (and optionally a file) plus a human summary to
stderr. Read-only; makes no changes.

Usage:
    python find_duplicates.py [--json out.json] [--min-len N]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import defaultdict

from java_test_parser import all_test_files, parse_file, strip_comments


def canonical(body: str) -> str:
    """Comment-free, whitespace-collapsed body for exact-duplicate comparison.

    String/char/numeric literals are PRESERVED so that tests differing only in
    their literal arguments are NOT treated as duplicates."""
    b = strip_comments(body)
    b = re.sub(r"\s+", " ", b)
    return b.strip()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", default=None)
    ap.add_argument("--min-len", type=int, default=0,
                    help="ignore duplicate bodies shorter than N canonical chars")
    args = ap.parse_args()

    groups = defaultdict(list)  # canonical-body -> list of (file, class, name, start, end, len)
    for path in all_test_files():
        tf = parse_file(path)
        for m in tf.methods:
            if not m.is_test:
                continue
            canon = canonical(m.body)
            if len(canon) < args.min_len:
                continue
            if not canon:
                continue
            key = hashlib.sha1(canon.encode("utf-8")).hexdigest()
            groups[key].append({
                "file": tf.path.replace("\\", "/"),
                "class": tf.class_name,
                "name": m.name,
                "start_line": m.start_line,
                "end_line": m.end_line,
                "canon_len": len(canon),
                "canon": canon,
            })

    dup_groups = [g for g in groups.values() if len(g) > 1]
    # sort largest / longest first
    dup_groups.sort(key=lambda g: (-len(g), -g[0]["canon_len"]))

    within = 0
    cross = 0
    report = []
    for g in dup_groups:
        files = {m["file"] for m in g}
        kind = "within-class" if len(files) == 1 else "cross-class"
        if kind == "within-class":
            within += 1
        else:
            cross += 1
        report.append({
            "kind": kind,
            "count": len(g),
            "canon_len": g[0]["canon_len"],
            "members": [{k: v for k, v in m.items() if k != "canon"} for m in g],
            "canon_preview": g[0]["canon"][:200],
        })

    out = {
        "duplicate_group_count": len(dup_groups),
        "within_class_groups": within,
        "cross_class_groups": cross,
        "extra_methods_removable": sum(len(g) - 1 for g in dup_groups),
        "groups": report,
    }
    text = json.dumps(out, indent=2)
    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            fh.write(text)
    else:
        print(text)

    print(f"\n[summary] duplicate groups: {len(dup_groups)} "
          f"(within-class={within}, cross-class={cross}); "
          f"removable extra methods: {out['extra_methods_removable']}",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
