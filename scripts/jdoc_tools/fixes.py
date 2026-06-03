"""
fixes.py -- in-place Javadoc example fixers (structural / mechanical only).

Every fixer is a pure ``fix_X(path, lines) -> (new_lines, count, details)`` that
never touches the filesystem; the CLI writes only on ``--apply``. So ``--check``
(dry-run) is the safe default.

These fixers only reshape *structure* (the Section-2 layout) or apply mechanical,
behavior-preserving corrections. They never invent or rewrite the *content* of a
behavior comment -- that is the agent/test's job per the task's rule "Never guess
expected values; verify with tests." (The earlier content-guessing fixers, which
were tuned to one library's vocabulary, were removed in the generalization.)

Fixers:
  usage-indent ........ align a Usage Examples block to the surrounding gutter
  usage-spacing ....... exactly one blank gutter line before header and after </pre>
  double-blank ........ drop consecutive blank gutter lines
  move-comments-into-pre  pull stray "// ..." sample comments inside the block
  type-placeholders ... "Type<...>" -> "Type<?, ?, ?>"
  literal-artifacts ... strip a leaked "// returns" out of strings / URLs in code
  sample-member-misuse  array variable ".size()" -> ".length"
  assignment-style .... "Type.call(...) = result" -> "Type.call(...);  // returns result"

(Block ordering -- Usage Examples before @tags -- and column alignment are handled
by the existing scripts/fix_javadoc_format4.py and scripts/align_jdoc_examples.py.)
"""
from __future__ import annotations

import re
from typing import List, Tuple

from . import region
from .reports import _ARRAY_DECL  # array-decl pattern shared with the member-misuse check

FixResult = Tuple[List[str], int, List[str]]


def _is_blank_javadoc(line: str) -> bool:
    return bool(re.match(r"^\s*\*\s*$", line))


def _blank_for(line: str) -> str:
    m = re.match(r"^(\s*)\*", line)
    return f"{m.group(1) if m else '     '}*"


# --------------------------------------------------------------------------- #
# usage-indent
# --------------------------------------------------------------------------- #
_TAG_RE = re.compile(r"^(\s*)\*\s*@(param|return|throws|see|since|deprecated)\b")
_STAR_RE = re.compile(r"^(\s*)\*(.*)$")
_USAGE_RE = re.compile(r"<p><b>.*(?:Usage Examples|Example).*</b></p>")


def fix_usage_indent(path: str, lines: List[str]) -> FixResult:
    lines = list(lines)
    changed = 0
    for start, end in region.iter_javadoc_blocks(lines):
        expected = None
        for i in range(start, end + 1):
            m = _TAG_RE.match(lines[i])
            if m:
                expected = m.group(1)
                break
        if expected is None:
            for i in range(start + 1, end + 1):
                m = _STAR_RE.match(lines[i])
                if m and m.group(2).strip():
                    expected = m.group(1)
                    break
        if expected is None:
            continue
        for i in range(start + 1, end + 1):
            if not _USAGE_RE.search(lines[i]):
                continue
            block_start = i
            if block_start > start and re.match(r"^\s*\*\s*$", lines[block_start - 1]):
                block_start -= 1
            block_end = i
            while block_end <= end and "</pre>" not in lines[block_end]:
                block_end += 1
            if block_end <= end and block_end + 1 <= end and (
                re.match(r"^\s*\*\s*$", lines[block_end + 1]) or re.match(r"^\s*$", lines[block_end + 1])
            ):
                block_end += 1
            for j in range(block_start, block_end + 1):
                if re.match(r"^\s*$", lines[j]):
                    if lines[j] != expected + "*":
                        lines[j] = expected + "*"
                        changed += 1
                    continue
                m = _STAR_RE.match(lines[j])
                if m and m.group(1) != expected:
                    lines[j] = expected + "*" + m.group(2)
                    changed += 1
    return lines, changed, []


# --------------------------------------------------------------------------- #
# usage-spacing
# --------------------------------------------------------------------------- #
def fix_usage_spacing(path: str, lines: List[str]) -> FixResult:
    lines = list(lines)
    changed = 0

    in_javadoc = False
    i = 0
    while i < len(lines):
        if not in_javadoc and region.is_active_javadoc_start(lines[i]):
            in_javadoc = True
            i += 1
            continue
        if in_javadoc and "*/" in lines[i]:
            in_javadoc = False
            i += 1
            continue
        if in_javadoc and i > 0 and _is_blank_javadoc(lines[i]) and _is_blank_javadoc(lines[i - 1]):
            del lines[i]
            changed += 1
            i -= 1
            continue
        i += 1

    i = 0
    while i < len(lines):
        mask = region.active_javadoc_line_mask(lines)
        if not mask[i] or not re.search(r"<p><b>.*(?:Usage Examples|Examples?|Example usage).*</b></p>", lines[i]):
            i += 1
            continue
        blank = _blank_for(lines[i])
        while i > 0 and _is_blank_javadoc(lines[i - 1]) and i > 1 and _is_blank_javadoc(lines[i - 2]):
            del lines[i - 1]
            i -= 1
            changed += 1
        if i > 0 and not _is_blank_javadoc(lines[i - 1]):
            lines.insert(i, blank)
            i += 1
            changed += 1
        close = i + 1
        while close < len(lines) and "</pre>" not in lines[close]:
            close += 1
        if close >= len(lines):
            i += 1
            continue
        while close + 2 < len(lines) and _is_blank_javadoc(lines[close + 1]) and _is_blank_javadoc(lines[close + 2]):
            del lines[close + 2]
            changed += 1
        if close + 1 < len(lines) and not _is_blank_javadoc(lines[close + 1]):
            lines.insert(close + 1, blank)
            changed += 1
        i += 1
    return lines, changed, []


# --------------------------------------------------------------------------- #
# double-blank
# --------------------------------------------------------------------------- #
def fix_double_blank(path: str, lines: List[str]) -> FixResult:
    out: List[str] = []
    in_javadoc = False
    prev_blank = False
    count = 0
    for line in lines:
        if region.is_active_javadoc_start(line):
            in_javadoc = True
            prev_blank = False
        blank = in_javadoc and bool(re.match(r"^\s*\*\s*$", line))
        if blank and prev_blank:
            count += 1
            continue
        out.append(line)
        prev_blank = blank
        if in_javadoc and re.search(r"\*/\s*$", line):
            in_javadoc = False
            prev_blank = False
    return out, count, []


# --------------------------------------------------------------------------- #
# sample-member-misuse: array variable ".size()" -> ".length"
# --------------------------------------------------------------------------- #
def fix_sample_member_misuse(path: str, lines: List[str]) -> FixResult:
    new_lines = list(lines)
    count = 0
    details: List[str] = []
    for block_start, block in region.iter_full_code_blocks(lines):
        array_vars = set()
        for ln in block:
            array_vars.update(_ARRAY_DECL.findall(region.code_of(ln)))
        if not array_vars:
            continue
        for offset in range(len(block)):
            idx = block_start + offset
            for var in array_vars:
                pat = re.compile(r"\b" + re.escape(var) + r"\.size\(\)")
                hits = len(pat.findall(new_lines[idx]))
                if hits:
                    new_lines[idx] = pat.sub(f"{var}.length", new_lines[idx])
                    count += hits
                    details.append(f"{path}:{idx + 1}: {var}.size() -> {var}.length")
    return new_lines, count, details


# --------------------------------------------------------------------------- #
# literal-artifacts: strip a leaked "// returns" out of strings / URLs in code
# --------------------------------------------------------------------------- #
def _fix_string_artifacts(text: str) -> str:
    text = re.sub(r"://\s+returns\s+", "://", text, flags=re.I)
    text = re.sub(r"//\s+returns\s+", "//", text, flags=re.I)
    return text


def fix_literal_artifacts(path: str, lines: List[str]) -> FixResult:
    lines = list(lines)
    count = 0
    for i, line in region.iter_code_lines(lines):
        idx = region.comment_index_outside_literals(line)
        fixed = _fix_string_artifacts(line) if idx < 0 else _fix_string_artifacts(line[:idx]) + line[idx:]
        if fixed != line:
            lines[i] = fixed
            count += 1
    return lines, count, []


# --------------------------------------------------------------------------- #
# assignment-style: "Type.call(...) = result" -> "Type.call(...);  // returns result"
# --------------------------------------------------------------------------- #
_ASSIGN_RE = re.compile(r"^(\s*\*\s+)([A-Z][\w$]*(?:\.[A-Za-z_$][\w$]*)+\s*\(.*\))\s*=\s*(.+?)\s*$")


def _result_comment(result: str) -> str:
    rhs = result.strip().rstrip()
    m = re.match(r"^throws\s+(?:an?\s+)?(.+)$", rhs, re.I)
    if m:
        return f"throws {m.group(1).strip()}"
    m = re.match(r"^(?:an?\s+)?([A-Za-z_$][\w$]*(?:Exception|Error)\b.*)$", rhs)
    if m:
        return f"throws {m.group(1).strip()}"
    return f"returns {rhs}"


def _is_public_method_declaration(decl: str) -> bool:
    return bool(re.match(r"^public\b", decl)) and not re.search(r"\b(class|interface|enum|@interface)\b", decl) and "(" in decl


def fix_assignment_style(path: str, lines: List[str]) -> FixResult:
    lines = list(lines)
    count = 0
    details: List[str] = []
    for start, end in region.iter_javadoc_blocks(lines):
        decl = ""
        for i in range(end + 1, len(lines)):
            t = lines[i].strip()
            if not t or t.startswith("@"):
                continue
            decl = t
            break
        if not _is_public_method_declaration(decl):
            continue
        in_code = False
        for j in range(start, end + 1):
            if not in_code and "<pre>{@code" in lines[j]:
                in_code = True
                continue
            if in_code and "</pre>" in lines[j]:
                in_code = False
                continue
            if not in_code:
                continue
            m = _ASSIGN_RE.match(lines[j])
            if not m:
                continue
            prefix, call, result = m.group(1), m.group(2), m.group(3)
            if "//" in call or "//" in result:
                continue
            converted = f"{prefix}{call};   // {_result_comment(result)}"
            if converted != lines[j]:
                details.append(f"{path}:{j + 1}: {lines[j].strip()} -> {converted.strip()}")
                lines[j] = converted
                count += 1
    return lines, count, details


# --------------------------------------------------------------------------- #
# type-placeholders: "Type<...>" -> "Type<?, ?, ?>" inside code
# --------------------------------------------------------------------------- #
def fix_type_placeholders(path: str, lines: List[str]) -> FixResult:
    lines = list(lines)
    count = 0
    for i, line in region.iter_code_lines(lines):
        updated = re.sub(r"\b([A-Z][A-Za-z0-9_.]*)<\.\.\.>", r"\1<?, ?, ?>", line)
        if updated != line:
            lines[i] = updated
            count += 1
    return lines, count, []


# --------------------------------------------------------------------------- #
# move-comments-into-pre: pull stray "// ..." sample comments inside the block
# --------------------------------------------------------------------------- #
def _is_sample_comment(line: str) -> bool:
    return bool(re.match(r"^\s*\*\s+//\s+\S", line))


def fix_move_comments_into_pre(path: str, lines: List[str]) -> FixResult:
    lines = list(lines)
    total = 0
    details: List[str] = []
    i = 0
    while i < len(lines):
        mask = region.active_javadoc_line_mask(lines)
        if not mask[i] or "Usage Examples" not in lines[i]:
            i += 1
            continue

        comments_before = []
        pre_index = -1
        j = i + 1
        while j < min(len(lines), i + 20) and mask[j]:
            if "<pre>{@code" in lines[j]:
                pre_index = j
                break
            if _is_sample_comment(lines[j]):
                comments_before.append({"index": j, "line": lines[j]})
            j += 1
        if pre_index < 0:
            i += 1
            continue

        j = i - 1
        while j >= 0 and mask[j] and _is_sample_comment(lines[j]):
            comments_before.insert(0, {"index": j, "line": lines[j]})
            j -= 1

        end_pre = pre_index + 1
        while end_pre < len(lines) and mask[end_pre] and "}</pre>" not in lines[end_pre]:
            end_pre += 1
        if end_pre >= len(lines) or not mask[end_pre]:
            i += 1
            continue

        comments_after = []
        j = end_pre + 1
        while j < len(lines) and mask[j] and _is_sample_comment(lines[j]):
            comments_after.append({"index": j, "line": lines[j]})
            j += 1

        comments = comments_before + comments_after
        if not comments:
            i += 1
            continue

        for c in comments_before:
            details.append(f"{path}:{c['index'] + 1}: move before pre {c['line'].strip()}")
        for c in comments_after:
            details.append(f"{path}:{c['index'] + 1}: move before close {c['line'].strip()}")
        total += len(comments)

        for c in sorted(comments, key=lambda c: c["index"], reverse=True):
            del lines[c["index"]]

        removed_before_pre = sum(1 for c in comments if c["index"] < pre_index)
        adjusted_pre = pre_index - removed_before_pre
        removed_before_end = sum(1 for c in comments if c["index"] < end_pre)
        adjusted_end = end_pre - removed_before_end
        insert_index = adjusted_pre + 1

        if insert_index < len(lines) and re.match(r"^\s*\*\s*$", lines[insert_index]):
            del lines[insert_index]
            adjusted_end -= 1
        if comments_before:
            for offset, c in enumerate(comments_before):
                lines.insert(insert_index + offset, c["line"])
            adjusted_end += len(comments_before)
        if comments_after:
            close_insert = adjusted_end
            if close_insert > 0 and not re.match(r"^\s*\*\s*$", lines[close_insert - 1]):
                lines.insert(close_insert, _blank_for(lines[close_insert]))
                adjusted_end += 1
            for offset, c in enumerate(comments_after):
                lines.insert(adjusted_end + offset, c["line"])

        i = insert_index + len(comments_before)
    return lines, total, details


# --------------------------------------------------------------------------- #
# registry -- name -> fixer
# --------------------------------------------------------------------------- #
FIXES = {
    "usage-indent": fix_usage_indent,
    "usage-spacing": fix_usage_spacing,
    "double-blank": fix_double_blank,
    "move-comments-into-pre": fix_move_comments_into_pre,
    "type-placeholders": fix_type_placeholders,
    "literal-artifacts": fix_literal_artifacts,
    "sample-member-misuse": fix_sample_member_misuse,
    "assignment-style": fix_assignment_style,
}
