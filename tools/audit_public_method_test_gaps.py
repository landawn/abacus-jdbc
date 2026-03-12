#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from pathlib import Path


PACKAGE_RE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)\s*;", re.MULTILINE)
TYPE_RE = re.compile(
    r"^\s*(?:public\s+)?(?:(abstract|final|sealed|non-sealed)\s+)?(class|interface|enum|@interface)\s+([A-Za-z_][A-Za-z0-9_]*)",
    re.MULTILINE,
)
TEST_METHOD_RE = re.compile(r"^[ \t]*@Test\b[\s\S]*?^[ \t]*public\s+void\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(", re.MULTILINE)
DECLARED_TEST_METHOD_RE = re.compile(r"^[ \t]*public\s+void\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(", re.MULTILINE)
PUBLIC_METHOD_RE = re.compile(
    r"^[ \t]*(?:@Override\s*)*(?:public)\s+(?:static\s+|default\s+|final\s+|synchronized\s+|native\s+|strictfp\s+)*"
    r"(?:<[^;{]+>\s+)?([A-Za-z0-9_<>, ?\[\].]+)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(",
    re.MULTILINE,
)
IMPLICIT_INTERFACE_METHOD_RE = re.compile(
    r"^[ \t]*(?:default\s+|static\s+)?(?:<[^;{]+>\s+)?([A-Za-z0-9_<>, ?\[\].]+)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(",
    re.MULTILINE,
)
COMMENT_RE = re.compile(r"/\*.*?\*/|//[^\n]*", re.DOTALL)
TODO_RE = re.compile(r"//\s*TODO:\s*(.+)")


@dataclass
class PublicMethod:
    name: str
    signature: str
    line_number: int
    is_static: bool
    is_abstract: bool


@dataclass
class SourceClass:
    path: Path
    package_name: str
    class_name: str
    type_kind: str
    modifier: str | None
    public_methods: list[PublicMethod]


def strip_comments(text: str) -> str:
    return COMMENT_RE.sub("", text)


def read_package_name(text: str) -> str:
    match = PACKAGE_RE.search(text)
    return match.group(1) if match else ""


def read_declared_type(text: str) -> tuple[str | None, str | None, str | None]:
    match = TYPE_RE.search(text)
    if not match:
        return None, None, None
    return match.group(2), match.group(3), match.group(1)


def discover_source_classes(root: Path) -> list[SourceClass]:
    result: list[SourceClass] = []

    for path in sorted(root.rglob("*.java")):
        if path.name == "package-info.java":
            continue

        text = path.read_text(encoding="utf-8")
        package_name = read_package_name(text)
        type_kind, class_name, modifier = read_declared_type(text)

        if not class_name or not type_kind:
            continue

        result.append(
            SourceClass(
                path=path,
                package_name=package_name,
                class_name=class_name,
                type_kind=type_kind,
                modifier=modifier,
                public_methods=extract_public_methods(text, class_name, type_kind),
            )
        )

    return result


def extract_public_methods(source: str, class_name: str, type_kind: str) -> list[PublicMethod]:
    methods: list[PublicMethod] = []
    normalized = strip_comments(source)
    type_match = TYPE_RE.search(normalized)
    type_body_start = normalized.find("{", type_match.end()) if type_match else -1
    brace_depth = build_brace_depth_map(normalized)
    matches = list(PUBLIC_METHOD_RE.finditer(normalized))

    if type_kind == "interface":
        matches.extend(
            match
            for match in IMPLICIT_INTERFACE_METHOD_RE.finditer(normalized)
            if not normalized[max(0, match.start() - 20):match.start()].strip().startswith(("public", "private", "protected"))
        )

    seen: set[tuple[str, int]] = set()

    for match in sorted(matches, key=lambda item: item.start()):
        return_type, method_name = match.group(1), match.group(2)

        if method_name == class_name:
            continue
        if type_body_start < 0 or match.start() <= type_body_start:
            continue
        if brace_depth[match.start()] != 1:
            continue

        line_number = normalized.count("\n", 0, match.start()) + 1
        signature = normalized[match.start():normalized.find("{", match.end()) if "{" in normalized[match.end():] else match.end()].strip()
        snippet = normalized[match.start():normalized.find("{", match.end()) if "{" in normalized[match.end():] else normalized.find(";", match.end())]
        is_static = " static " in f" {snippet} "
        is_abstract = type_kind == "interface" and " default " not in f" {snippet} " and " static " not in f" {snippet} "
        key = (method_name, line_number)

        if key in seen:
            continue
        seen.add(key)

        methods.append(
            PublicMethod(
                name=method_name,
                signature=signature if signature else f"{return_type} {method_name}(...)",
                line_number=line_number,
                is_static=is_static,
                is_abstract=is_abstract,
            )
        )

    return methods


def build_brace_depth_map(text: str) -> list[int]:
    depth_map = [0] * len(text)
    depth = 0

    for index, ch in enumerate(text):
        depth_map[index] = depth
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth = max(depth - 1, 0)

    return depth_map


def corresponding_test_path(src_root: Path, test_root: Path, source_path: Path) -> Path:
    relative = source_path.relative_to(src_root)
    return test_root / relative.parent / f"{source_path.stem}Test.java"


def extract_test_method_names(test_path: Path) -> tuple[list[str], str | None]:
    text = test_path.read_text(encoding="utf-8")
    names = TEST_METHOD_RE.findall(text)
    todo = TODO_RE.search(text)

    if names:
        return names, todo.group(1).strip() if todo else None

    return DECLARED_TEST_METHOD_RE.findall(text), todo.group(1).strip() if todo else None


def covers_method(test_name: str, method_name: str) -> bool:
    lower_method_name = method_name.lower()

    if test_name.startswith("test") and len(test_name) > 4:
        suffix = test_name[4:]
        method_token = suffix.split("_", 1)[0].lower()
        return method_token == lower_method_name

    return lower_method_name in test_name.lower()


def audit(src_root: Path, test_root: Path) -> list[dict[str, object]]:
    report: list[dict[str, object]] = []

    for source_class in discover_source_classes(src_root):
        test_path = corresponding_test_path(src_root, test_root, source_class.path)
        test_methods, skip_reason = extract_test_method_names(test_path) if test_path.exists() else ([], None)

        gaps = []
        for method in source_class.public_methods:
            covered_by = [test_name for test_name in test_methods if covers_method(test_name, method.name)]
            if not covered_by:
                gaps.append(
                    {
                        "name": method.name,
                        "line": method.line_number,
                        "signature": method.signature,
                        "is_static": method.is_static,
                        "is_abstract": method.is_abstract,
                    }
                )

        report.append(
            {
                "source_path": source_class.path.as_posix(),
                "package": source_class.package_name,
                "class_name": source_class.class_name,
                "type_kind": source_class.type_kind,
                "modifier": source_class.modifier,
                "test_path": test_path.as_posix(),
                "test_exists": test_path.exists(),
                "public_method_count": len(source_class.public_methods),
                "test_method_count": len(test_methods),
                "skip_reason": skip_reason,
                "gaps": gaps,
            }
        )

    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Audit source/test class pairs and report likely public method coverage gaps.")
    parser.add_argument("--src", default="src/main/java")
    parser.add_argument("--tests", default="src/test/java")
    parser.add_argument("--json-out", default="")
    args = parser.parse_args()

    report = audit(Path(args.src), Path(args.tests))
    uncovered_classes = [item for item in report if ((item["gaps"] and not item["skip_reason"]) or not item["test_exists"])]
    skipped_classes = [item for item in report if item["skip_reason"]]

    print(f"Scanned {len(report)} source classes.")
    print(f"Found {sum(1 for item in report if item['test_exists'])} existing corresponding test classes.")
    print(f"Found {len(uncovered_classes)} classes with missing test classes or likely uncovered public methods.\n")

    if skipped_classes:
        print(f"Skipped {len(skipped_classes)} classes with explicit TODO justification in their test class.\n")

    for item in uncovered_classes:
        print(f"{item['source_path']} -> {item['test_path']}")
        print(f"  kind={item['type_kind']} methods={item['public_method_count']} test_exists={item['test_exists']} tests={item['test_method_count']}")
        for gap in item["gaps"][:12]:
            print(f"  - gap: {gap['name']} (line {gap['line']})")
        if len(item["gaps"]) > 12:
            print(f"  - ... {len(item['gaps']) - 12} more gaps")
        print()

    if skipped_classes:
        for item in skipped_classes:
            print(f"SKIPPED {item['source_path']} -> {item['test_path']}")
            print(f"  reason: {item['skip_reason']}")
            print()

    if args.json_out:
        Path(args.json_out).write_text(json.dumps(report, indent=2), encoding="utf-8")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
