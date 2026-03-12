#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
from pathlib import Path


CLASS_RE = re.compile(r"\bclass\s+([A-Za-z_][A-Za-z0-9_]*)\b")


def java_classes(root: Path) -> dict[str, Path]:
    result: dict[str, Path] = {}
    for path in sorted(root.rglob("*.java")):
        if path.name.endswith("package-info.java"):
            continue
        result[path.stem] = path
    return result


def canonical_test_name(source_name: str) -> str:
    return f"{source_name}Test"


def owned_source_name(test_name: str) -> str | None:
    if not test_name.endswith("Test"):
        return None

    base = test_name[:-4]
    if base.endswith("2025"):
        base = base[:-4]
    return base or None


def read_declared_class_name(path: Path) -> str | None:
    text = path.read_text(encoding="utf-8")
    match = CLASS_RE.search(text)
    return match.group(1) if match else None


def main() -> int:
    parser = argparse.ArgumentParser(description="Report source-to-test class ownership and canonical naming mismatches.")
    parser.add_argument("--src", default="src/main/java/com/landawn/abacus/jdbc")
    parser.add_argument("--tests", default="src/test/java/com/landawn/abacus/jdbc")
    args = parser.parse_args()

    source_classes = java_classes(Path(args.src))
    test_files = sorted(Path(args.tests).rglob("*Test.java"))

    print(f"Found {len(source_classes)} source classes and {len(test_files)} test classes.\n")

    issues = 0
    for test_file in test_files:
        test_name = test_file.stem
        declared_class = read_declared_class_name(test_file)
        source_name = owned_source_name(test_name)

        if source_name is None:
            print(f"Unmapped test name: {test_file.as_posix()}")
            issues += 1
            continue

        source_path = source_classes.get(source_name)
        expected_name = canonical_test_name(source_name)

        if source_path is None:
            print(f"No matching source class for {test_file.as_posix()} (inferred source: {source_name})")
            issues += 1
            continue

        if test_name != expected_name:
            print(f"Non-canonical test file: {test_file.as_posix()} -> expected {expected_name}.java")
            issues += 1

        if declared_class and declared_class != test_name:
            print(f"Class/file mismatch: {test_file.as_posix()} declares {declared_class}")
            issues += 1

    if issues == 0:
        print("All detected test classes map to canonical source owners.")

    return 1 if issues else 0


if __name__ == "__main__":
    raise SystemExit(main())
