#!/usr/bin/env python3
"""
Task 2: map every test file to the source class it owns, and report where the
"one source class -> one test class" invariant is violated.

Ownership rule: FooTest.java owns Foo.java. Recognized test-class suffixes that
still map to Foo: "FooTest", "FooIntegrationTest", "FooMySQLTest".

Reports:
  - source classes with >1 test file  (scattered -> must consolidate)
  - source classes with 0 test files  (missing coverage; informational)
  - test files that map to no source class (orphan)
  - infra/helper test files (no @Test methods) -- excluded from ownership

Read-only. Usage: python map_ownership.py [--json out.json]
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from collections import defaultdict

from java_test_parser import all_test_files, parse_file

SRC_ROOT = os.path.join("src", "main", "java")

# Package we are forbidden to modify (classes directly under it, not subpackages)
FORBIDDEN_PKG = "com.landawn.abacus"


def source_classes() -> dict:
    """basename(without .java) -> path, for all source classes."""
    out = {}
    for dirpath, _dirs, files in os.walk(SRC_ROOT):
        for f in files:
            if f.endswith(".java"):
                out[f[:-5]] = os.path.join(dirpath, f).replace("\\", "/")
    return out


# suffixes stripped (longest first) to find the owning source class
_SUFFIXES = ["IntegrationTest", "MySQLTest", "Test"]


def owner_of(test_class: str, srcs: dict) -> str | None:
    for suf in _SUFFIXES:
        if test_class.endswith(suf):
            base = test_class[: -len(suf)]
            if base in srcs:
                return base
    # exact 'Test' strip fallback already covered; try plain strip
    if test_class.endswith("Test"):
        base = test_class[:-4]
        return base if base in srcs else None
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", default=None)
    args = ap.parse_args()

    srcs = source_classes()
    src_to_tests = defaultdict(list)
    orphans = []
    infra = []

    for path in all_test_files():
        tf = parse_file(path)
        n_tests = sum(1 for m in tf.methods if m.is_test)
        base = os.path.basename(path)[:-5]
        pkg = tf.package
        is_forbidden = pkg == FORBIDDEN_PKG
        owner = owner_of(tf.class_name, srcs)
        rec = {
            "file": path.replace("\\", "/"),
            "class": tf.class_name,
            "package": pkg,
            "n_tests": n_tests,
            "forbidden_pkg": is_forbidden,
        }
        if n_tests == 0:
            infra.append(rec)
            continue
        if owner is None:
            orphans.append(rec)
            continue
        src_to_tests[owner].append(rec)

    scattered = {s: ts for s, ts in src_to_tests.items() if len(ts) > 1}
    covered = set(src_to_tests.keys())
    missing = sorted(set(srcs.keys()) - covered)

    out = {
        "scattered_source_classes": {
            s: sorted(ts, key=lambda r: r["file"]) for s, ts in
            sorted(scattered.items())
        },
        "orphan_test_files": sorted(orphans, key=lambda r: r["file"]),
        "infra_no_test_files": sorted(infra, key=lambda r: r["file"]),
        "source_classes_without_test": missing,
        "counts": {
            "source_classes": len(srcs),
            "covered": len(covered),
            "scattered": len(scattered),
            "orphans": len(orphans),
            "infra": len(infra),
            "missing_test": len(missing),
        },
    }
    text = json.dumps(out, indent=2)
    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            fh.write(text)

    print("== SCATTERED (source class -> multiple test files) ==", file=sys.stderr)
    for s, ts in sorted(scattered.items()):
        print(f"  {s}:", file=sys.stderr)
        for r in sorted(ts, key=lambda r: r["file"]):
            print(f"      {r['class']} ({r['n_tests']} tests)", file=sys.stderr)
    print("\n== ORPHAN test files (no matching source class) ==", file=sys.stderr)
    for r in sorted(orphans, key=lambda r: r["file"]):
        print(f"  {r['class']} ({r['n_tests']} tests) pkg={r['package']}", file=sys.stderr)
    print("\n== INFRA (no @Test) ==", file=sys.stderr)
    for r in sorted(infra, key=lambda r: r["file"]):
        print(f"  {r['class']} pkg={r['package']} forbidden={r['forbidden_pkg']}", file=sys.stderr)
    print(f"\n[summary] {out['counts']}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
