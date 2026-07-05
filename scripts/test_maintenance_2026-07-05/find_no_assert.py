#!/usr/bin/env python3
"""
Task 3: find test methods lacking a meaningful assertion.

Two buckets:
  - zero_assertion : @Test method whose body has no assertion-like call at all
                     (no assert*, no Mockito verify(...), no fail(...), no
                     assertThrows/assertDoesNotThrow).
  - trivial_assertion : @Test method whose ONLY assertion is a tautology, e.g.
                     assertTrue(true...), assertFalse(false...), assertNotNull(<literal>).

Both are candidates for strengthening. The zero bucket is authoritative; the
trivial bucket is heuristic and must be eyeballed.

Read-only. Usage: python find_no_assert.py [--json out.json]
"""
from __future__ import annotations

import argparse
import json
import re
import sys

from java_test_parser import all_test_files, parse_file, has_assertion, strip_comments

# tautological / meaningless assertion patterns (whole-body, after comment strip)
_TRIVIAL_TRUE = re.compile(r"\bassertTrue\s*\(\s*true\b")
_TRIVIAL_FALSE = re.compile(r"\bassertFalse\s*\(\s*false\b")

# Any assertion-like call (mirror of parser.has_assertion, reused via import).


def only_trivial(body: str) -> bool:
    """True if the body has assertion calls but every one of them is trivial."""
    b = strip_comments(body)
    # collect all assertion call heads
    calls = re.findall(r"\b(assert\w+|verify|fail)\s*\(", b)
    if not calls:
        return False
    # If there is any verify/fail or any non-trivial assert*, it's not trivial.
    for c in calls:
        if c in ("verify", "fail"):
            return False
    # Now every call is an assertXxx. Check each occurrence is trivial.
    # Simple approach: body is trivial-only if the only assert* present are
    # assertTrue(true)/assertFalse(false) forms.
    non_trivial = re.findall(r"\bassert\w+\s*\(", b)
    trivial_hits = len(_TRIVIAL_TRUE.findall(b)) + len(_TRIVIAL_FALSE.findall(b))
    return trivial_hits > 0 and trivial_hits == len(non_trivial)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", default=None)
    args = ap.parse_args()

    zero = []
    trivial = []
    total_tests = 0
    for path in all_test_files():
        tf = parse_file(path)
        for m in tf.methods:
            if not m.is_test:
                continue
            total_tests += 1
            rec = {
                "file": tf.path.replace("\\", "/"),
                "class": tf.class_name,
                "name": m.name,
                "start_line": m.start_line,
                "end_line": m.end_line,
            }
            if not has_assertion(m.body):
                zero.append(rec)
            elif only_trivial(m.body):
                trivial.append(rec)

    out = {
        "total_tests": total_tests,
        "zero_assertion_count": len(zero),
        "trivial_assertion_count": len(trivial),
        "zero_assertion": sorted(zero, key=lambda r: (r["file"], r["start_line"])),
        "trivial_assertion": sorted(trivial, key=lambda r: (r["file"], r["start_line"])),
    }
    text = json.dumps(out, indent=2)
    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            fh.write(text)

    print("== ZERO-ASSERTION test methods ==", file=sys.stderr)
    for r in out["zero_assertion"]:
        print(f"  {r['file'].split('/')[-1]}::{r['name']}  ({r['start_line']}-{r['end_line']})", file=sys.stderr)
    print("\n== TRIVIAL-ONLY assertion test methods ==", file=sys.stderr)
    for r in out["trivial_assertion"]:
        print(f"  {r['file'].split('/')[-1]}::{r['name']}  ({r['start_line']}-{r['end_line']})", file=sys.stderr)
    print(f"\n[summary] total_tests={total_tests} "
          f"zero_assertion={len(zero)} trivial_only={len(trivial)}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
