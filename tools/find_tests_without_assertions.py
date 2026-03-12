#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
from dataclasses import dataclass
from pathlib import Path


TEST_ANNOTATION_RE = re.compile(r"^[ \t]*@Test\b", re.MULTILINE)
METHOD_SIGNATURE_RE = re.compile(
    r"(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+)?(?:synchronized\s+)?(?:<[^>]+>\s+)?void\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(",
    re.MULTILINE,
)
ASSERTION_RE = re.compile(r"\bassert[A-Z][A-Za-z0-9_]*\s*\(")
ASSERTION_NAME_RE = re.compile(r"\b(?:Assertions\.)?(assert[A-Z][A-Za-z0-9_]*)\s*\(")
TRY_RE = re.compile(r"\btry\s*\{")
CATCH_RE = re.compile(r"\bcatch\s*\(")
CLASS_LITERAL_NOT_NULL_RE = re.compile(r"\b(?:Assertions\.)?assertNotNull\s*\(\s*[A-Za-z_][A-Za-z0-9_$.]*\.class\s*\)")
MOCKITO_VERIFY_RE = re.compile(r"\bverify(?:NoInteractions|NoMoreInteractions)?\s*\(")
WEAK_ASSERTIONS = {"assertDoesNotThrow", "assertNotNull"}


@dataclass
class TestMethod:
    file: Path
    method_name: str
    line_number: int
    body: str


def strip_comments(code: str) -> str:
    return re.sub(r"/\*.*?\*/|//[^\n]*", "", code, flags=re.DOTALL)


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
    weak_only: list[TestMethod] = []
    with_try_catch: list[TestMethod] = []
    methods: list[TestMethod] = []

    for java_file in discover_test_files(root):
        methods.extend(extract_test_methods(java_file))

    for method in methods:
        body = strip_comments(method.body)
        assertion_names = ASSERTION_NAME_RE.findall(body)

        if not assertion_names:
            missing.append(method)
            continue

        if TRY_RE.search(body) and CATCH_RE.search(body):
            with_try_catch.append(method)

        if set(assertion_names).issubset(WEAK_ASSERTIONS) and not MOCKITO_VERIFY_RE.search(body):
            weak_only.append(method)
            continue

        if CLASS_LITERAL_NOT_NULL_RE.search(body):
            weak_only.append(method)

    print(f"Scanned {len(methods)} @Test methods across {len(discover_test_files(root))} files.\n")

    if not missing and not weak_only and not with_try_catch:
        print("All scanned tests contain assertions, avoid weak-only patterns, and do not use try/catch in test bodies.")
        return 0

    if missing:
        print(f"Found {len(missing)} tests without JUnit assertions.\n")
        for method in missing:
            print(f"{method.file.as_posix()}:{method.line_number}::{method.method_name}")
        print()

    if weak_only:
        print(f"Found {len(weak_only)} tests with only weak assertions (for example only assertNotNull/assertDoesNotThrow).\n")
        for method in weak_only:
            print(f"{method.file.as_posix()}:{method.line_number}::{method.method_name}")
        print()

    if with_try_catch:
        print(f"Found {len(with_try_catch)} tests with try/catch blocks.\n")
        for method in with_try_catch:
            print(f"{method.file.as_posix()}:{method.line_number}::{method.method_name}")

    return 1


if __name__ == "__main__":
    raise SystemExit(main())
