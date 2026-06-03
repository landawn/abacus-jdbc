#!/usr/bin/env python3
"""
align_jdoc_examples.py
======================

Align the inline result / comment columns inside Javadoc ``<pre>{@code ...}</pre>``
usage-example blocks of Java source files, using **display width** (East Asian
Width aware) so that wide CJK / full-width characters do not push the alignment
token out of its column.

Why
---
Example blocks such as::

    * Numbers.toFloat("123.45");    // returns 123.45f
    * Numbers.toFloat((String) null);   // returns 0.0f
    * Numbers.toLong("abc", 0L);                       // throws ...

read best when every ``//`` (or ``=``) lines up in a single column. Two things
break naive space-padding:
  1. a longer line in the group leaves the shorter lines' markers behind;
  2. wide characters (你好, 中, fullwidth forms) render as 2 columns but count as
     1 char, so character-count padding drifts in a real editor.

This tool re-pads only the run of spaces *before* the alignment token so that all
tokens in a group share one display column. Code and comment/result TEXT are never
modified, and already-aligned groups are reproduced byte-for-byte (idempotent).

What it aligns
--------------
Within each ``{@code}`` block, maximal runs of consecutive "alignable" lines are
grouped. A blank ``*`` line and a full-line ``// header`` comment both break a run.
A group is also split where the token *style* changes. Two styles are recognised:

  * comment style :  ``<code>   // <comment>``      -> the ``//`` is aligned
  * result style  :  ``<expr>   = <result>``        -> the ``=`` is aligned
                     (only when the line has no ``;`` and no ``//``, i.e. it is an
                      expression/result line, never a ``Type v = expr;`` setup line)

For each group::

    target_col = max(display_width(code)) + base_gap        # base_gap = min gap, >= 1

and every line is padded so its token lands on ``target_col``. ``//`` markers that
appear inside string/char literals (e.g. ``"http://x"``) are ignored.

Display width: combining marks -> 0; East Asian Wide/Fullwidth -> 2; everything
else (incl. ambiguous-width Greek / accented Latin) -> 1.

Usage
-----
    python scripts/align_jdoc_examples.py [--check | --apply] PATH [PATH ...]

PATH may be a ``.java`` file or a directory (recursively scanned for ``*.java``).
``--check`` (the default) is a dry run that prints proposed line changes;
``--apply`` rewrites files in place. Exit code is 1 in ``--check`` mode when any
file would change (handy for CI / pre-commit), else 0.

Examples
--------
    # preview changes for one file
    python scripts/align_jdoc_examples.py src/main/java/com/example/Foo.java

    # fix every Java file under the source tree
    python scripts/align_jdoc_examples.py --apply src/main/java
"""
from __future__ import annotations

import argparse
import os
import re
import sys
import unicodedata

try:  # make CJK printable on Windows consoles
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass


# --------------------------------------------------------------------------- #
# display width
# --------------------------------------------------------------------------- #
def char_width(ch: str) -> int:
    if unicodedata.combining(ch) or unicodedata.category(ch) in ("Mn", "Me"):
        return 0
    if unicodedata.east_asian_width(ch) in ("W", "F"):
        return 2
    return 1


def disp_width(s: str) -> int:
    return sum(char_width(c) for c in s)


# --------------------------------------------------------------------------- #
# line parsing
# --------------------------------------------------------------------------- #
PREFIX_RE = re.compile(r"^(\s*\*[ \t]?)(.*)$")          # the " * " javadoc gutter
CODE_END_RE = re.compile(r"^\s*\*\s*\}\s*$")            # a lone " * }" closing line


def scan_tokens(rest: str):
    """Yield (kind, index) for candidate tokens outside string/char literals.

    kind is one of '//', '=', ';'. Bracket depth is tracked so only top-level
    '=' (a spaced, standalone '=') is reported."""
    in_str = None
    depth = 0
    i, n = 0, len(rest)
    while i < n:
        c = rest[i]
        if in_str:
            if c == "\\":
                i += 2
                continue
            if c == in_str:
                in_str = None
            i += 1
            continue
        if c in ('"', "'"):
            in_str = c
        elif c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "/" and i + 1 < n and rest[i + 1] == "/":
            yield ("//", i)
            return                                       # comment runs to EOL
        elif c == "=" and depth == 0:
            prv = rest[i - 1] if i > 0 else ""
            nxt = rest[i + 1] if i + 1 < n else ""
            if prv == " " and nxt == " ":                # a spaced standalone '='
                yield ("=", i)
        elif c == ";" and depth == 0:
            yield (";", i)
        i += 1


def classify(rest: str):
    """Return (kind, token_index) for an alignable line, else None."""
    has_semicolon = False
    eq_idx = None
    for kind, idx in scan_tokens(rest):
        if kind == "//":
            if idx > 0 and rest[:idx].strip():
                return ("//", idx)
            return None                                  # comment-only line
        if kind == ";":
            has_semicolon = True
        elif kind == "=" and eq_idx is None:
            eq_idx = idx
    if eq_idx is not None and not has_semicolon and rest[:eq_idx].strip():
        return ("=", eq_idx)
    return None


def split_line(line: str):
    """Return (prefix, code, gap, tail, kind) for an alignable line, else None."""
    content = line.rstrip("\r\n")
    m = PREFIX_RE.match(content)
    if not m:
        return None
    prefix, rest = m.group(1), m.group(2)
    res = classify(rest)
    if res is None:
        return None
    kind, idx = res
    code = rest[:idx].rstrip()
    if not code:
        return None
    gap = len(rest[:idx]) - len(code)
    tail = rest[idx:]
    return prefix, code, gap, tail, kind


# --------------------------------------------------------------------------- #
# core
# --------------------------------------------------------------------------- #
def realign(lines):
    """Return (new_lines, changed_indices)."""
    in_code = False
    parsed = [None] * len(lines)
    for i, line in enumerate(lines):
        content = line.rstrip("\r\n")
        if not in_code:
            if "{@code" in content and "}" not in content.split("{@code", 1)[1]:
                in_code = True
            continue
        if "</pre>" in content or CODE_END_RE.match(content):
            in_code = False
            continue
        parsed[i] = split_line(line)

    new_lines = list(lines)
    changed = []
    i, n = 0, len(lines)
    while i < n:
        if parsed[i] is None:
            i += 1
            continue
        kind = parsed[i][4]
        j = i
        while j < n and parsed[j] is not None and parsed[j][4] == kind:
            j += 1
        group = range(i, j)
        base_gap = max(1, min(parsed[k][2] for k in group))
        target = max(disp_width(parsed[k][1]) for k in group) + base_gap
        for k in group:
            prefix, code, _gap, tail, _kind = parsed[k]
            pad = max(1, target - disp_width(code))
            eol = lines[k][len(lines[k].rstrip("\r\n")):]
            rebuilt = prefix + code + (" " * pad) + tail + eol
            if rebuilt != lines[k]:
                new_lines[k] = rebuilt
                changed.append(k)
        i = j
    return new_lines, changed


def process_file(path, apply, quiet):
    with open(path, "r", encoding="utf-8", newline="") as fh:
        lines = fh.readlines()
    new_lines, changed = realign(lines)
    if changed and apply:
        with open(path, "w", encoding="utf-8", newline="") as fh:
            fh.writelines(new_lines)
    if changed and not quiet:
        verb = "re-aligned" if apply else "would re-align"
        print(f"{path}: {verb} {len(changed)} line(s)")
        if not apply:
            for k in changed:
                old = lines[k].rstrip("\r\n")
                new = new_lines[k].rstrip("\r\n")
                print(f"  L{k + 1}")
                print(f"    - {old}")
                print(f"    + {new}")
    return len(changed)


def iter_java_files(paths):
    for p in paths:
        if os.path.isdir(p):
            for root, _dirs, files in os.walk(p):
                for name in sorted(files):
                    if name.endswith(".java"):
                        yield os.path.join(root, name)
        else:
            yield p


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="Align // and = columns in Javadoc {@code} example blocks "
                    "(display-width aware).")
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true",
                      help="dry run; print proposed changes (default)")
    mode.add_argument("--apply", action="store_true",
                      help="rewrite files in place")
    ap.add_argument("-q", "--quiet", action="store_true",
                    help="suppress per-line diff output")
    ap.add_argument("paths", nargs="+", metavar="PATH",
                    help=".java file or directory (recursed for *.java)")
    args = ap.parse_args(argv)

    apply = args.apply
    total_files = 0
    total_changed = 0
    files_changed = 0
    for path in iter_java_files(args.paths):
        if not os.path.isfile(path):
            print(f"skip (not a file): {path}", file=sys.stderr)
            continue
        total_files += 1
        c = process_file(path, apply, args.quiet)
        total_changed += c
        if c:
            files_changed += 1

    verb = "re-aligned" if apply else "would re-align"
    print(f"\n{total_files} file(s) scanned; {verb} {total_changed} line(s) "
          f"across {files_changed} file(s).")
    # non-zero exit in check mode when changes are pending (useful for CI)
    if not apply and total_changed:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
