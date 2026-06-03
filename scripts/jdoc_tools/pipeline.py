"""
pipeline.py -- file discovery and whole-project orchestration (project-agnostic).

Defaults to ``src/main/java`` and walks whatever tree it is given; nothing is
tied to a package or class name.

Functions
---------
public_type_files : every .java with a public top-level type.
eligible_files    : the subset whose Javadoc already contains Usage Examples.
audit             : Step-A overview -- documented public methods vs. those still
                    missing an examples block, per file and in total.
validate          : run the structural checks (+ align --check + comment-only git
                    guard) across the files that have examples.
cleanup           : run the structural fixers (+ align) across the same files.
verify_comment_only : assert a file's git diff touches only comment lines.

It reuses the two existing standalone scripts rather than re-porting them:
``align_jdoc_examples`` (column alignment, Step E) and ``fix_javadoc_format4``
(move the Usage Examples block before the @tags + collapse double blanks).
"""
from __future__ import annotations

import importlib
import os
import re
import subprocess
import sys
from typing import List, Optional, Tuple

from . import region, reports, fixes

_USAGE_PAT = re.compile(
    r"(?:Usage Examples|<b>Examples?:|Example usage|<b>Example\b|Typical usage pattern)", re.I
)
_PRE_COUNT = re.compile(r"<pre>\{@code")


# --------------------------------------------------------------------------- #
# small IO + lazy reuse of the sibling standalone scripts
# --------------------------------------------------------------------------- #
def read_text(path: str) -> str:
    with open(path, "r", encoding="utf-8", errors="ignore") as fh:
        return fh.read()


def _sibling(module_name: str):
    """Import a standalone script from scripts/ (its dir is on sys.path)."""
    scripts_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    if scripts_dir not in sys.path:
        sys.path.insert(0, scripts_dir)
    return importlib.import_module(module_name)


def find_java_files(paths: List[str]) -> List[str]:
    """Expand files/dirs to a sorted list of ``.java`` files (skips .git/target)."""
    out: List[str] = []
    for p in paths:
        if os.path.isdir(p):
            for root, _dirs, names in os.walk(p):
                if ".git" in root or os.sep + "target" in root:
                    continue
                for name in sorted(names):
                    if name.endswith(".java") and name != "package-info.java":
                        out.append(os.path.join(root, name))
        elif os.path.isfile(p):
            out.append(p)
    return out


def _read_keepends(path: str) -> List[str]:
    with open(path, "r", encoding="utf-8", newline="") as fh:
        return fh.readlines()


# --------------------------------------------------------------------------- #
# file discovery
# --------------------------------------------------------------------------- #
def public_type_files(root: str, exclude_packages: Optional[List[str]] = None) -> List[str]:
    """Every .java under ``root`` that declares a public top-level type."""
    exclude = set(exclude_packages or [])
    out: List[str] = []
    for file in find_java_files([root]):
        text = read_text(file)
        if exclude and region.package_name(text) in exclude:
            continue
        if region.has_public_top_level_type(text):
            out.append(file.replace("\\", "/"))
    return sorted(out)


def eligible_files(root: str, exclude_packages: Optional[List[str]] = None) -> List[Tuple[str, int, str]]:
    """``[(file, code_block_count, package), ...]`` for public types whose Javadoc
    already contains Usage Examples, sorted by path."""
    result: List[Tuple[str, int, str]] = []
    for file in public_type_files(root, exclude_packages):
        text = read_text(file)
        if not region.active_javadocs_contain(text, _USAGE_PAT):
            continue
        result.append((file, region.count_in_active_javadocs(text, _PRE_COUNT), region.package_name(text)))
    return result


# --------------------------------------------------------------------------- #
# audit (Step A overview)
# --------------------------------------------------------------------------- #
def audit(root: str, exclude_packages: Optional[List[str]] = None) -> int:
    files = public_type_files(root, exclude_packages)
    total_methods = total_with = total_missing = 0
    for file in files:
        lines = region.split_lines(read_text(file))
        recs = reports.documented_public_methods(file, lines)
        if not recs:
            continue
        with_ex = sum(1 for r in recs if r["has_examples"])
        missing = len(recs) - with_ex
        total_methods += len(recs)
        total_with += with_ex
        total_missing += missing
        flag = "" if missing == 0 else f"  <-- {missing} missing"
        print(f"{file}\tmethods={len(recs)}\twith_examples={with_ex}\tmissing={missing}{flag}")
    print(f"\nTOTAL documented public methods={total_methods} "
          f"with_examples={total_with} missing_examples={total_missing} "
          f"across {len(files)} public-type file(s)")
    return 0


# --------------------------------------------------------------------------- #
# verify-comment-only (git)
# --------------------------------------------------------------------------- #
def verify_comment_only(path: str) -> List[str]:
    """Return offending non-comment changed lines for ``path`` per ``git diff``."""
    try:
        diff = subprocess.run(["git", "diff", "--", path], capture_output=True, text=True, check=False).stdout
    except FileNotFoundError:
        return []
    violations: List[str] = []
    for line in region.split_lines(diff):
        if not line or not re.match(r"^[+-]", line):
            continue
        if re.match(r"^(\+\+\+|---)", line):
            continue
        content = line[1:].strip()
        if (content == "" or content.startswith("*") or content.startswith("//")
                or content.startswith("/*") or content.startswith("*/")):
            continue
        violations.append(line)
    return violations


# --------------------------------------------------------------------------- #
# validate: run every structural check (+ align check + comment-only) over a tree
# --------------------------------------------------------------------------- #
_VALIDATE_CHECKS = [
    "usage-check", "missing-behavior-comments", "placeholders",
    "sample-member-misuse", "suspicious-strings",
]


def validate(root: str, exclude_packages: Optional[List[str]] = None) -> int:
    files = [item[0] for item in eligible_files(root, exclude_packages)]
    align = _sibling("align_jdoc_examples")
    failures = []
    for file in files:
        lines = region.split_lines(read_text(file))
        problem = None
        for name in _VALIDATE_CHECKS:
            fn, _ = reports.REPORTS[name]
            issues = fn(file, lines)
            if issues:
                problem = (name, issues)
                break
        if problem is None and align.realign(_read_keepends(file))[1]:
            problem = ("align (--check)", [f"{file}: would re-align"])
        if problem is None:
            v = verify_comment_only(file)
            if v:
                problem = ("verify-comment-only", v)
        if problem:
            failures.append((file, problem[0], problem[1]))

    for file, check, output in failures:
        print(f"FAIL {check} {file}")
        for line in output[:20]:
            print(f"  {line}")
        if len(output) > 20:
            print("  ...")
    print(f"VALIDATED_FILE_COUNT {len(files)}")
    print(f"FAILED_FILE_COUNT {len(failures)}")
    return 1 if failures else 0


# --------------------------------------------------------------------------- #
# cleanup: run the structural fixers in order over a tree, then align
# --------------------------------------------------------------------------- #
_CLEANUP_FIXES = [
    "usage-indent",
    "usage-spacing",
    "double-blank",
    "type-placeholders",
    "literal-artifacts",
    "sample-member-misuse",
    "assignment-style",
]


def cleanup(root: str, apply: bool, exclude_packages: Optional[List[str]] = None) -> int:
    files = [item[0] for item in eligible_files(root, exclude_packages)]
    print(f"ELIGIBLE_USAGE_FILE_COUNT {len(files)}")
    if not files:
        return 0
    format4 = _sibling("fix_javadoc_format4")
    align = _sibling("align_jdoc_examples")
    total_changes = 0
    for file in files:
        if apply:
            format4.fix_file(file)  # move Usage Examples before @tags + collapse double blanks
        for name in _CLEANUP_FIXES:
            text = read_text(file)
            lines = region.split_lines(text)
            newline = region.detect_newline(text)
            new_lines, count, _details = fixes.FIXES[name](file, lines)
            total_changes += count
            if apply and new_lines != lines:
                with open(file, "w", encoding="utf-8") as fh:
                    fh.write(region.join_lines(new_lines, newline))
        align.process_file(file, apply, quiet=True)  # column alignment last (Step E)
    verb = "applied" if apply else "would apply"
    print(f"CLEANUP_DONE files={len(files)} fixer_changes={total_changes} ({verb})")
    if not apply:
        print("(dry run -- re-run with --apply to write)")
    return 0
