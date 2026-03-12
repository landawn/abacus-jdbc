#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import re
from dataclasses import dataclass
from pathlib import Path


TEST_ANNOTATION = "@Test"
METHOD_SIGNATURE_RE = re.compile(
    r"(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+)?(?:synchronized\s+)?(?:<[^>]+>\s+)?void\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(",
    re.MULTILINE,
)
COMMENT_RE = re.compile(r"/\*.*?\*/|//[^\n]*", re.DOTALL)
WS_RE = re.compile(r"\s+")
ASSERTIONS_PREFIX_RE = re.compile(r"\bAssertions\.")
JAVA_LANG_PREFIX_RE = re.compile(r"\bjava\.lang\.")


@dataclass
class TestMethod:
    file: Path
    method_name: str
    line_number: int
    body: str
    normalized_body: str


def strip_comments(code: str) -> str:
    return COMMENT_RE.sub("", code)


def normalize_body(body: str) -> str:
    normalized = strip_comments(body)
    normalized = ASSERTIONS_PREFIX_RE.sub("", normalized)
    normalized = JAVA_LANG_PREFIX_RE.sub("", normalized)
    normalized = WS_RE.sub(" ", normalized).strip()
    return normalized


def find_matching_brace(source: str, start_index: int) -> int:
    depth = 0
    in_string = False
    in_text_block = False
    in_char = False
    in_line_comment = False
    in_block_comment = False
    escaped = False

    for index in range(start_index, len(source)):
        ch = source[index]
        next_two = source[index:index + 2]
        next_three = source[index:index + 3]

        if escaped:
            escaped = False
            continue

        if in_line_comment:
            if ch == "\n":
                in_line_comment = False
            continue

        if in_block_comment:
            if next_two == "*/":
                in_block_comment = False
                continue
            continue

        if not in_string and not in_char and not in_text_block:
            if next_two == "//":
                in_line_comment = True
                continue

            if next_two == "/*":
                in_block_comment = True
                continue

        if ch == "\\" and (in_string or in_char):
            escaped = True
            continue

        if next_three == '"""' and not in_char:
            in_text_block = not in_text_block
            continue

        if in_text_block:
            continue

        if ch == '"' and not in_char:
            in_string = not in_string
            continue

        if ch == "'" and not in_string:
            in_char = not in_char
            continue

        if in_string or in_char:
            continue

        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return index

    raise ValueError(f"Unbalanced braces starting at index {start_index}")


def extract_test_methods(java_file: Path) -> list[TestMethod]:
    source = java_file.read_text(encoding="utf-8")
    methods: list[TestMethod] = []
    search_from = 0

    while True:
        annotation_index = source.find(TEST_ANNOTATION, search_from)
        if annotation_index == -1:
            break

        signature_match = METHOD_SIGNATURE_RE.search(source, annotation_index)
        if not signature_match:
            search_from = annotation_index + len(TEST_ANNOTATION)
            continue

        method_name = signature_match.group(1)
        brace_index = source.find("{", signature_match.end())
        if brace_index == -1:
            search_from = signature_match.end()
            continue

        end_brace_index = find_matching_brace(source, brace_index)
        body = source[brace_index + 1:end_brace_index]
        line_number = source.count("\n", 0, signature_match.start()) + 1

        methods.append(
            TestMethod(
                file=java_file,
                method_name=method_name,
                line_number=line_number,
                body=body,
                normalized_body=normalize_body(body),
            )
        )

        search_from = end_brace_index + 1

    return methods


def discover_test_files(root: Path) -> list[Path]:
    return sorted(path for path in root.rglob("*Test.java") if path.is_file())


def report_duplicates(methods: list[TestMethod]) -> int:
    buckets: dict[str, list[TestMethod]] = {}

    for method in methods:
        if not method.normalized_body:
            continue

        digest = hashlib.sha256(method.normalized_body.encode("utf-8")).hexdigest()
        buckets.setdefault(digest, []).append(method)

    duplicate_groups = [group for group in buckets.values() if len(group) > 1]
    duplicate_groups.sort(key=lambda group: (group[0].file.as_posix(), group[0].line_number))

    if not duplicate_groups:
        print("No exact normalized duplicate @Test bodies found.")
        return 0

    print(f"Found {len(duplicate_groups)} duplicate groups.\n")

    for index, group in enumerate(duplicate_groups, start=1):
        snippet = group[0].normalized_body[:160]
        print(f"[{index}] {snippet}")
        for method in group:
            print(f"  - {method.file.as_posix()}:{method.line_number}::{method.method_name}")
        print()

    return len(duplicate_groups)


def main() -> int:
    parser = argparse.ArgumentParser(description="Find exact duplicate JUnit @Test bodies in Java test sources.")
    parser.add_argument(
        "root",
        nargs="?",
        default="src/test/java",
        help="Root directory to scan. Defaults to src/test/java.",
    )
    args = parser.parse_args()

    root = Path(args.root)
    methods: list[TestMethod] = []

    for java_file in discover_test_files(root):
        methods.extend(extract_test_methods(java_file))

    print(f"Scanned {len(methods)} @Test methods across {len(discover_test_files(root))} files.\n")
    return 1 if report_duplicates(methods) else 0


if __name__ == "__main__":
    raise SystemExit(main())
