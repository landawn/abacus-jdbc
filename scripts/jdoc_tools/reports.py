"""
reports.py -- read-only Javadoc "Usage Examples" audits (project-agnostic).

Every function is a pure reader: ``check_X(path, lines) -> list[str]`` of
``path:line: ...`` findings; none ever writes. These back the audit/verify side
of the comments-only workflow:

  - find documented public methods (Step A) ............ methods
  - find documented public methods missing examples .... missing-examples
  - check the required Section-2 block structure ....... usage-check
  - find example calls with no behavior comment ........ missing-behavior-comments
  - find unresolved "..." placeholders ................. placeholders
  - find array/collection .size()/.length misuse ....... sample-member-misuse
  - find "// returns/throws" hidden inside a string .... suspicious-strings
  - generic needle search inside Javadoc ............... scan
  - which documented methods changed in git ............ changed-methods

Nothing here is tied to a package or class name, so it applies to any project's
sources under ``src/main/java``.
"""
from __future__ import annotations

import re
import subprocess
from typing import List, Optional, Tuple

from . import region


# --------------------------------------------------------------------------- #
# shared: the public method (if any) a Javadoc block documents
# --------------------------------------------------------------------------- #
def _earliest_terminator(s: str) -> int:
    """Index of the first ';' or '{' in ``s`` (statement/declaration terminator), or -1."""
    idxs = [s.index(c) for c in ";{" if c in s]
    return min(idxs) if idxs else -1


def _method_after(lines: List[str], end: int) -> Optional[Tuple[int, str]]:
    """Return ``(decl_line_index, method_signature_head)`` for the METHOD/constructor
    declaration that the Javadoc block ending at ``end`` documents, or None when the
    next declaration is a field / non-method statement.

    The signature is gathered across continuation lines only up to the first statement
    terminator (';' or '{'). A terminator reached before any ``(...)`` means the
    declaration is a field -- this avoids mis-reading compressed lines such as
    ``public final boolean _1; BooleanTuple1() {`` as a public method."""
    start = None
    for i in range(end + 1, len(lines)):
        t = lines[i].strip()
        if not t or t.startswith("@"):
            continue
        start = i
        break
    if start is None:
        return None
    sig = ""
    for j in range(start, min(len(lines), start + 12)):
        sig = (sig + " " + lines[j].strip()).strip()
        term = _earliest_terminator(sig)
        if term >= 0:
            head = sig[:term]
            if "(" in head and ")" in head:
                return start, re.sub(r"\s+", " ", head).strip()
            return None  # field / non-method statement before any method signature
    if "(" in sig and ")" in sig:
        return start, re.sub(r"\s+", " ", sig).strip()
    return None


def _is_public_method(sig: str) -> bool:
    s = re.sub(r"^(?:@\w[\w.]*(?:\([^)]*\))?\s*)*", "", sig)
    if not s.startswith("public ") or re.search(r"\b(class|interface|enum|record)\b", s):
        return False
    m = re.search(r"[A-Za-z_$][\w$]*\s*\(", s)  # a method/constructor name immediately before '('
    # a '=' before that '(' would mean a field initializer (e.g. ``public X y = f();``), not a method
    return bool(m) and "=" not in s[: m.start()]


def _method_name(sig: str) -> str:
    m = re.search(r"\b([A-Za-z_$][\w$]*)\s*\(", sig)
    return m.group(1) if m else ""


_EXAMPLE_HEADER = re.compile(r"Usage Examples|<b>Examples?:|Example usage|<b>Example\b", re.I)


def _block_has_examples(block: List[str]) -> bool:
    text = "\n".join(block)
    return "<pre>{@code" in text or bool(_EXAMPLE_HEADER.search(text))


def documented_public_methods(path: str, lines: List[str]) -> List[dict]:
    """Records for each Javadoc'd public method in a public class: line, name,
    signature, has_examples. Empty if the file has no public top-level type."""
    if not region.has_public_top_level_type("\n".join(lines)):
        return []
    scope_ok = region.type_public_scope(lines)
    records: List[dict] = []
    for start, end in region.iter_javadoc_blocks(lines):
        m = _method_after(lines, end)
        if not m:
            continue
        decl_line, sig = m
        if not _is_public_method(sig):
            continue
        if decl_line < len(scope_ok) and not scope_ok[decl_line]:
            continue  # method is inside a non-public (e.g. package-private) nested type
        records.append({
            "line": decl_line + 1,
            "name": _method_name(sig),
            "signature": sig,
            "has_examples": _block_has_examples(lines[start:end + 1]),
        })
    return records


# --------------------------------------------------------------------------- #
# methods (Step A) / missing-examples
# --------------------------------------------------------------------------- #
def check_methods(path: str, lines: List[str]) -> List[str]:
    out = []
    for r in documented_public_methods(path, lines):
        mark = "examples" if r["has_examples"] else "NO-EXAMPLES"
        out.append(f"{path}:{r['line']}: [{mark}] {r['signature']}")
    return out


def check_missing_examples(path: str, lines: List[str]) -> List[str]:
    return [
        f"{path}:{r['line']}: {r['signature']}"
        for r in documented_public_methods(path, lines)
        if not r["has_examples"]
    ]


# --------------------------------------------------------------------------- #
# usage-check: Section-2 structural formatting of Usage Examples blocks
# --------------------------------------------------------------------------- #
def check_usage(path: str, lines: List[str]) -> List[str]:
    out: List[str] = []

    def report(line_no: int, kind: str, detail: str = "") -> None:
        out.append(f"{path}:{line_no}: {kind}{': ' + detail if detail else ''}")

    for start, end in region.iter_javadoc_blocks(lines):
        block = lines[start:end + 1]
        usage_headers = [i for i, ln in enumerate(block) if "Usage Examples" in ln]
        if not usage_headers:
            continue

        in_pre = False
        first_tag = -1
        for i, ln in enumerate(block):
            if "<pre>{@code" in ln:
                in_pre = True
                continue
            if in_pre:
                if "</pre>" in ln:
                    in_pre = False
                continue
            if re.match(r"^\s*\* @", ln):
                first_tag = i
                break

        if first_tag >= 0 and any(idx > first_tag for idx in usage_headers):
            locs = ", ".join(str(start + idx + 1) for idx in usage_headers)
            report(start + 1, "usage_after_tag", f"usage lines {locs}")

        tag_prefix_line = next((ln for ln in block if re.match(r"^\s*\* @", ln)), None)
        for idx in usage_headers:
            if idx > 0 and not region.is_blank_javadoc_line(block[idx - 1]):
                report(start + idx + 1, "missing_blank_before_usage")
            prefix = region.line_prefix(block[idx])
            if prefix is not None:
                expected = region.line_prefix(tag_prefix_line) if tag_prefix_line else prefix
                if expected is not None and prefix != expected:
                    report(start + idx + 1, "usage_indent_mismatch")

        for idx, ln in enumerate(block):
            if "</pre>" in ln and idx + 1 < len(block) and not region.is_blank_javadoc_line(block[idx + 1]):
                report(start + idx + 1, "missing_blank_after_pre")
            if re.match(r"^\s*\*\s+\*\s*<p><b>Usage Examples", ln):
                report(start + idx + 1, "stray_duplicate_star")

        for idx in range(1, len(block)):
            if region.is_blank_javadoc_line(block[idx]) and region.is_blank_javadoc_line(block[idx - 1]):
                report(start + idx + 1, "double_blank_javadoc_line")

    return out


# --------------------------------------------------------------------------- #
# placeholders: unresolved "..." / hand-wavy markers left inside examples
# --------------------------------------------------------------------------- #
def _strip_string_and_char_literals(s: str) -> str:
    s = re.sub(r'"(?:\\.|[^"\\])*"', '""', s)
    s = re.sub(r"'(?:\\.|[^'\\])*'", "''", s)
    return s


def _has_placeholder(line: str) -> bool:
    code = _strip_string_and_char_literals(line)
    return (
        bool(re.search(r"\.\.\.\s*;", code))
        or bool(re.search(r"\(\s*\.\.\.\s*\)", code))
        or bool(re.search(r"<\s*\.\.\.\s*>", code))
        or bool(re.match(r"^\s*\*\s*\.\.\.\s*$", code))
        or "typical usage" in code
        or "edge case" in code
    )


def check_placeholders(path: str, lines: List[str]) -> List[str]:
    out: List[str] = []
    for i, line in region.iter_code_lines(lines):
        if _has_placeholder(line):
            out.append(f"{path}:{i + 1}: {line.strip()}")
    return out


# --------------------------------------------------------------------------- #
# missing-behavior-comments: an example call line with no "// returns/throws"
# --------------------------------------------------------------------------- #
def _has_follow_up_comment(lines: List[str], index: int) -> bool:
    nxt = lines[index + 1].strip() if index + 1 < len(lines) else ""
    return bool(re.match(r"^\*\s*//\s*(?:returns|throws|no\b|same\b|empty\b|unchanged\b)", nxt, re.I))


def _is_behavior_statement(code: str) -> bool:
    """A call statement that produces a result/side effect and so should carry a
    behavior comment -- not a setup/declaration line. Project-agnostic."""
    if not code.endswith(";") or "(" not in code or ")" not in code:
        return False
    if re.match(r"^(if|for|while|switch|try|catch|return|throw|new |}|{|@|else)\b", code):
        return False
    if "=" in code.split("//")[0]:  # assignment / declaration = setup, exempt
        return False
    return bool(re.search(r"[A-Za-z_$][\w$]*\s*\([^;]*\)\s*;$", code))


def check_missing_behavior_comments(path: str, lines: List[str]) -> List[str]:
    out: List[str] = []
    for start, end in region.iter_javadoc_blocks(lines):
        m = _method_after(lines, end)
        if not m or not _is_public_method(m[1]):
            continue
        in_pre = False
        for index in range(start, end + 1):
            line = lines[index]
            if "<pre>{@code" in line:
                in_pre = True
                continue
            if not in_pre:
                continue
            if "</pre>" in line:
                in_pre = False
                continue
            mm = re.match(r"^\s*\*\s?(.*)$", line)
            if not mm:
                continue
            code = mm.group(1).strip()
            if not code or code.startswith("//") or code.startswith("*") or "//" in code:
                continue
            if _has_follow_up_comment(lines, index):
                continue
            if _is_behavior_statement(code):
                out.append(f"{path}:{index + 1}: {code}")
    return out


# --------------------------------------------------------------------------- #
# sample-member-misuse: array.size() / collection.length confusion
# --------------------------------------------------------------------------- #
_PRIMITIVES = "boolean|byte|char|short|int|long|float|double"
_ARRAY_DECL = re.compile(
    r"\b(?:final\s+)?(?:" + _PRIMITIVES + r"|[A-Z_$][\w$]*(?:\s*<[^;=]+>)?)\s*\[\]\s+([A-Za-z_$][\w$]*)\b"
)
_COLLECTION_DECL = re.compile(
    r"\b(?:final\s+)?(?:List|ArrayList|LinkedList|Collection|Set|HashSet|LinkedHashSet|"
    r"SortedSet|NavigableSet|Map|HashMap|LinkedHashMap|SortedMap|NavigableMap|Deque|"
    r"ArrayDeque|Queue|PrimitiveList|BooleanList|CharList|ByteList|ShortList|IntList|"
    r"LongList|FloatList|DoubleList)\b(?:\s*<[^;=]+>)?\s+([A-Za-z_$][\w$]*)\b"
)


def check_sample_member_misuse(path: str, lines: List[str]) -> List[str]:
    out: List[str] = []
    for block_start, block in region.iter_full_code_blocks(lines):
        array_vars = set()
        collection_vars = set()
        for ln in block:
            code = region.code_of(ln)
            array_vars.update(_ARRAY_DECL.findall(code))
            collection_vars.update(_COLLECTION_DECL.findall(code))
        for offset, ln in enumerate(block):
            code = region.code_of(ln)
            for var in array_vars:
                if re.search(r"\b" + re.escape(var) + r"\.size\(\)", code):
                    out.append(f'{path}:{block_start + offset + 1}: array variable "{var}" uses .size(): {code.strip()}')
            for var in collection_vars:
                if re.search(r"\b" + re.escape(var) + r"\.length\b", code):
                    out.append(f'{path}:{block_start + offset + 1}: collection variable "{var}" uses .length: {code.strip()}')
    return out


# --------------------------------------------------------------------------- #
# suspicious-strings: a "// returns/throws" buried inside a string literal
# --------------------------------------------------------------------------- #
def _has_suspicious_string(line: str) -> bool:
    in_string = False
    escaped = False
    current = ""
    for ch in line:
        if not in_string:
            if ch == '"':
                in_string, escaped, current = True, False, '"'
            continue
        current += ch
        if escaped:
            escaped = False
            continue
        if ch == "\\":
            escaped = True
            continue
        if ch == '"':
            if re.search(r"//\s*(returns|throws)\b", current, re.I):
                return True
            in_string, current = False, ""
    return False


def check_suspicious_strings(path: str, lines: List[str]) -> List[str]:
    out: List[str] = []
    for i, line in region.iter_code_lines(lines):
        idx = region.comment_index_outside_literals(line)
        code = line if idx < 0 else line[:idx]
        if _has_suspicious_string(code):
            out.append(f"{path}:{i + 1}:{line.strip()}")
    return out


# --------------------------------------------------------------------------- #
# scan: generic needle search inside active Javadoc (info report, exit 0)
# --------------------------------------------------------------------------- #
def check_scan(path: str, lines: List[str], needle: str = "// returns") -> List[str]:
    out: List[str] = []
    mask = region.active_javadoc_line_mask(lines)
    for i, line in enumerate(lines):
        if mask[i] and needle in line:
            out.append(f"{path}:{i + 1}:{line.strip()}")
    return out


# --------------------------------------------------------------------------- #
# changed-methods: which Javadoc'd methods changed in the working tree (git)
# --------------------------------------------------------------------------- #
def _git_changed_new_lines(path: str) -> set:
    try:
        diff = subprocess.run(["git", "diff", "--", path], capture_output=True, text=True, check=False).stdout
    except FileNotFoundError:
        return set()
    changed = set()
    new_line = 0
    for line in region.split_lines(diff):
        hunk = re.match(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@", line)
        if hunk:
            new_line = int(hunk.group(1))
            continue
        if not new_line:
            continue
        if line.startswith("+") and not line.startswith("+++"):
            changed.add(new_line)
            new_line += 1
        elif line.startswith("-") and not line.startswith("---"):
            continue
        else:
            new_line += 1
    return changed


def check_changed_methods(path: str, lines: List[str]) -> List[str]:
    changed_lines = _git_changed_new_lines(path)
    if not changed_lines:
        return []
    out: List[str] = []
    for start, end in region.iter_javadoc_blocks(lines):
        if any((start + 1) <= ln <= (end + 1) for ln in changed_lines):
            m = _method_after(lines, end)
            sig = m[1] if m else "(class javadoc)"
            out.append(f"{path}:{(m[0] + 1) if m else start + 1}: {sig}")
    return out


# --------------------------------------------------------------------------- #
# registry -- name -> (function, is_issue_report)
# --------------------------------------------------------------------------- #
REPORTS = {
    "methods": (check_methods, False),
    "missing-examples": (check_missing_examples, False),
    "usage-check": (check_usage, True),
    "missing-behavior-comments": (check_missing_behavior_comments, True),
    "placeholders": (check_placeholders, True),
    "sample-member-misuse": (check_sample_member_misuse, True),
    "suspicious-strings": (check_suspicious_strings, True),
    "scan": (check_scan, False),
    "changed-methods": (check_changed_methods, False),
}
