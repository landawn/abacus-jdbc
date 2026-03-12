#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
from dataclasses import dataclass
from pathlib import Path


TEST_ANNOTATION = "@Test"
METHOD_SIGNATURE_RE = re.compile(
    r"(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+)?(?:synchronized\s+)?(?:<[^>]+>\s+)?void\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(",
    re.MULTILINE,
)
ASSERTION_RE = re.compile(r"\bassert[A-Z][A-Za-z0-9_]*\s*\(")


@dataclass
class TestMethod:
    file: Path
    method_name: str
    line_number: int
    body: str


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

        methods.append(TestMethod(java_file, method_name, line_number, body))
        search_from = end_brace_index + 1

    return methods


def discover_test_files(root: Path) -> list[Path]:
    return sorted(path for path in root.rglob("*.java") if path.is_file())


def main() -> int:
    parser = argparse.ArgumentParser(description="Find JUnit @Test methods that contain no JUnit assertions.")
    parser.add_argument("root", nargs="?", default="src/test/java/com/landawn/abacus/jdbc")
    args = parser.parse_args()

    root = Path(args.root)
    missing: list[TestMethod] = []
    methods: list[TestMethod] = []

    for java_file in discover_test_files(root):
        methods.extend(extract_test_methods(java_file))

    for method in methods:
        if not ASSERTION_RE.search(method.body):
            missing.append(method)

    print(f"Scanned {len(methods)} @Test methods across {len(discover_test_files(root))} files.\n")

    if not missing:
        print("All scanned tests contain at least one JUnit assertion call.")
        return 0

    print(f"Found {len(missing)} tests without JUnit assertions.\n")
    for method in missing:
        print(f"{method.file.as_posix()}:{method.line_number}::{method.method_name}")

    return 1


if __name__ == "__main__":
    raise SystemExit(main())
