#!/usr/bin/env python3
"""
jdoc.py -- one CLI for the Javadoc "Usage Examples" toolkit (jdoc_tools).

A project-agnostic toolkit for auditing and tidying the Javadoc *Usage Examples*
of public methods in public classes anywhere under ``src/main/java``. It supports
the comments-only "audit & complete Usage Examples" workflow:

  Step A  list documented public methods / find those missing examples
            python scripts/jdoc.py audit
            python scripts/jdoc.py report missing-examples <file|dir>
            python scripts/jdoc.py report methods          <file|dir>

  audit    check the required block structure + behavior comments (read-only)
            python scripts/jdoc.py report usage-check               <file|dir>
            python scripts/jdoc.py report missing-behavior-comments <file|dir>
            python scripts/jdoc.py validate src/main/java

  Step E  normalize structure / align columns (writes only with --apply)
            python scripts/jdoc.py fix usage-spacing --apply <file|dir>
            python scripts/jdoc.py fix align        --apply <file|dir>
            python scripts/jdoc.py cleanup          --apply src/main/java

  guard    confirm a file's git diff changed only comments (Section "only
           comments were edited")
            python scripts/jdoc.py verify-comment-only <file>

Reports never write. Fixers are dry-run unless you pass --apply. The fixers only
reshape structure / apply mechanical corrections -- they never invent the text of
a behavior comment (verify those with a test, per the task rules).
"""
from __future__ import annotations

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from jdoc_tools import region, reports, fixes, pipeline  # noqa: E402

try:  # make CJK printable on Windows consoles
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

_FIXER_NAMES = list(fixes.FIXES) + ["align", "move-usage-before-tags"]


def _read(path: str):
    text = pipeline.read_text(path)
    return text, region.detect_newline(text), region.split_lines(text)


def _cmd_report(args) -> int:
    if args.name not in reports.REPORTS:
        print(f"unknown report: {args.name}\navailable: {', '.join(reports.REPORTS)}", file=sys.stderr)
        return 2
    fn, is_issue = reports.REPORTS[args.name]
    findings = 0
    for path in pipeline.find_java_files(args.paths):
        _text, _nl, lines = _read(path)
        out = fn(path, lines, needle=args.needle) if args.name == "scan" else fn(path, lines)
        for line in out:
            print(line)
        findings += len(out)
    print(f"{args.name.upper().replace('-', '_')}_COUNT {findings}")
    return 1 if (is_issue and findings) else 0


def _cmd_fix(args) -> int:
    name = args.name
    if name == "align":
        align = pipeline._sibling("align_jdoc_examples")
        return align.main((["--apply"] if args.apply else []) + args.paths)
    if name == "move-usage-before-tags":
        if not args.apply:
            print("move-usage-before-tags has no dry-run; re-run with --apply", file=sys.stderr)
            return 2
        format4 = pipeline._sibling("fix_javadoc_format4")
        for path in pipeline.find_java_files(args.paths):
            changed, n = format4.fix_file(path)
            if changed:
                print(f"{path}: moved/collapsed {n} block(s)")
        return 0

    if name not in fixes.FIXES:
        print(f"unknown fixer: {name}\navailable: {', '.join(_FIXER_NAMES)}", file=sys.stderr)
        return 2

    fixer = fixes.FIXES[name]
    total = files_changed = 0
    for path in pipeline.find_java_files(args.paths):
        _text, newline, lines = _read(path)
        new_lines, count, details = fixer(path, lines)
        for line in details:
            print(f"{'' if args.apply else '[dry-run] '}{line}")
        if new_lines != lines:
            files_changed += 1
            total += max(count, 1)
            if args.apply:
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write(region.join_lines(new_lines, newline))
    verb = "changed" if args.apply else "would change"
    print(f"{name.upper().replace('-', '_')}: {verb} {files_changed} file(s), {total} edit(s)")
    return 1 if (not args.apply and files_changed) else 0


def _cmd_eligible(args) -> int:
    for file, count, pkg in pipeline.eligible_files(args.root, args.exclude_package):
        print(f"{file}\t{count}\t{pkg}")
    return 0


def _cmd_audit(args) -> int:
    return pipeline.audit(args.root, args.exclude_package)


def _cmd_validate(args) -> int:
    return pipeline.validate(args.root, args.exclude_package)


def _cmd_cleanup(args) -> int:
    return pipeline.cleanup(args.root, args.apply, args.exclude_package)


def _cmd_verify_comment_only(args) -> int:
    failed = 0
    files = pipeline.find_java_files(args.paths)
    for path in files:
        violations = pipeline.verify_comment_only(path)
        if violations:
            failed += 1
            print(f"Non-comment changed lines in {path}:")
            for line in violations[:50]:
                print(line)
            if len(violations) > 50:
                print(f"... {len(violations) - 50} more")
    print(f"COMMENT_ONLY_VERIFIED_FILE_COUNT {len(files)}")
    return 1 if failed else 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="jdoc.py",
        description="Javadoc Usage Examples auditing / normalization toolkit (any project).",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="reports: " + ", ".join(reports.REPORTS) + "\nfixers:  " + ", ".join(_FIXER_NAMES),
    )
    sub = p.add_subparsers(dest="command", required=True)

    sp = sub.add_parser("report", help="run a read-only check")
    sp.add_argument("name", help="check name (see epilog of -h)")
    sp.add_argument("paths", nargs="+", help=".java file(s) or directory(ies)")
    sp.add_argument("--needle", default="// returns", help="for 'scan': text to search inside Javadoc")
    sp.set_defaults(func=_cmd_report)

    sp = sub.add_parser("fix", help="run an in-place fixer (dry-run unless --apply)")
    sp.add_argument("name", help="fixer name (see epilog of -h)")
    sp.add_argument("paths", nargs="+", help=".java file(s) or directory(ies)")
    sp.add_argument("--apply", action="store_true", help="write changes (default: preview)")
    sp.set_defaults(func=_cmd_fix)

    sp = sub.add_parser("audit", help="overview: documented public methods vs. missing examples")
    sp.add_argument("root", nargs="?", default="src/main/java")
    sp.add_argument("--exclude-package", action="append", default=[], help="package to skip (repeatable)")
    sp.set_defaults(func=_cmd_audit)

    sp = sub.add_parser("eligible", help="list files with Usage Examples (file<TAB>count<TAB>package)")
    sp.add_argument("root", nargs="?", default="src/main/java")
    sp.add_argument("--exclude-package", action="append", default=[])
    sp.set_defaults(func=_cmd_eligible)

    sp = sub.add_parser("inventory", help="alias of 'eligible'")
    sp.add_argument("root", nargs="?", default="src/main/java")
    sp.add_argument("--exclude-package", action="append", default=[])
    sp.set_defaults(func=_cmd_eligible)

    sp = sub.add_parser("validate", help="run all structural checks across the eligible tree")
    sp.add_argument("root", nargs="?", default="src/main/java")
    sp.add_argument("--exclude-package", action="append", default=[])
    sp.set_defaults(func=_cmd_validate)

    sp = sub.add_parser("cleanup", help="run all structural fixers across the eligible tree")
    sp.add_argument("root", nargs="?", default="src/main/java")
    sp.add_argument("--apply", action="store_true", help="write changes (default: preview)")
    sp.add_argument("--exclude-package", action="append", default=[])
    sp.set_defaults(func=_cmd_cleanup)

    sp = sub.add_parser("verify-comment-only", help="assert a file's git diff touches only comments")
    sp.add_argument("paths", nargs="+", help=".java file(s) or directory(ies)")
    sp.set_defaults(func=_cmd_verify_comment_only)

    return p


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
