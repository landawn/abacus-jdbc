#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import re
from dataclasses import dataclass
from pathlib import Path


TEST_ANNOTATION_RE = re.compile(r"^[ \t]*@Test\b", re.MULTILINE)
METHOD_SIGNATURE_RE = re.compile(
    r"(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+)?(?:synchronized\s+)?(?:<[^>]+>\s+)?void\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(",
    re.MULTILINE,
)
COMMENT_RE = re.compile(r"/\*.*?\*/|//[^\n]*", re.DOTALL)
WS_RE = re.compile(r"\s+")
ASSERTIONS_PREFIX_RE = re.compile(r"\bAssertions\.")
JAVA_LANG_PREFIX_RE = re.compile(r"\bjava\.lang\.")
NOT_NULL_ASSERT_RE = re.compile(r"\b(?:Assertions\.)?assertNotNull\s*\(\s*([^)]+?)\s*\)\s*;")
GENERIC_ASSERT_RE = re.compile(r"\b(?:Assertions\.)?(assert[A-Z][A-Za-z0-9_]*)\s*\(")


@dataclass
class TestMethod:
    file: Path
    method_name: str
    line_number: int
    body: str
    normalized_body: str
    assertion_names: tuple[str, ...]
    not_null_targets: tuple[str, ...]


def strip_comments(code: str) -> str:
    return COMMENT_RE.sub("", code)


def normalize_body(body: str) -> str:
    normalized = strip_comments(body)
    normalized = ASSERTIONS_PREFIX_RE.sub("", normalized)
    normalized = JAVA_LANG_PREFIX_RE.sub("", normalized)
    normalized = WS_RE.sub(" ", normalized).strip()
    return normalized


def assertion_names(body: str) -> tuple[str, ...]:
    return tuple(GENERIC_ASSERT_RE.findall(strip_comments(body)))


def not_null_targets(body: str) -> tuple[str, ...]:
    return tuple(target.strip() for target in NOT_NULL_ASSERT_RE.findall(strip_comments(body)))


def is_existence_only_test(method: TestMethod) -> bool:
    if not method.not_null_targets:
        return False

    if set(method.assertion_names) != {"assertNotNull"}:
        return False

    without_asserts = NOT_NULL_ASSERT_RE.sub("", strip_comments(method.body))
    return not without_asserts.strip()


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
        annotation_match = TEST_ANNOTATION_RE.search(source, search_from)
        if not annotation_match:
            break

        annotation_index = annotation_match.start()

        signature_match = METHOD_SIGNATURE_RE.search(source, annotation_index)
        if not signature_match:
            search_from = annotation_match.end()
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
                assertion_names=assertion_names(body),
                not_null_targets=not_null_targets(body),
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


def report_redundant_existence_tests(methods: list[TestMethod]) -> int:
    by_file: dict[Path, list[TestMethod]] = {}

    for method in methods:
        by_file.setdefault(method.file, []).append(method)

    flagged: list[TestMethod] = []

    for file_methods in by_file.values():
        for method in file_methods:
            if not is_existence_only_test(method):
                continue

            covered_elsewhere = True

            for target in method.not_null_targets:
                target_covered = any(
                    other is not method
                    and target in other.normalized_body
                    and (not is_existence_only_test(other) or set(other.assertion_names) != {"assertNotNull"})
                    for other in file_methods
                )

                if not target_covered:
                    covered_elsewhere = False
                    break

            if covered_elsewhere:
                flagged.append(method)

    if not flagged:
        return 0

    print(f"Found {len(flagged)} heuristic redundant existence-only tests.\n")

    for method in sorted(flagged, key=lambda it: (it.file.as_posix(), it.line_number)):
        targets = ", ".join(method.not_null_targets[:5])
        if len(method.not_null_targets) > 5:
            targets += ", ..."
        print(f"{method.file.as_posix()}:{method.line_number}::{method.method_name} -> {targets}")

    print()
    return len(flagged)


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
    exact_duplicates = report_duplicates(methods)
    redundant_existence_tests = report_redundant_existence_tests(methods)
    return 1 if exact_duplicates or redundant_existence_tests else 0


if __name__ == "__main__":
    raise SystemExit(main())
