"""
region.py -- Javadoc region primitives (shared library).

A faithful Python port of the original ``scripts/codex/javadoc_region.js``,
trimmed to the helpers actually used by the reports/fixes/pipeline modules.

The central idea everywhere in this toolkit: only ever look at, or rewrite,
text that lives inside an *active* Javadoc block (``/** ... */``) -- and, for
example handling, inside a ``<pre>{@code ... }</pre>`` code block within it.
These helpers make that bookkeeping reusable and consistent.

Line handling
-------------
Callers split a file with :func:`split_lines` (which records the file's
dominant newline) and re-join with :func:`join_lines`, so a fix never silently
flips CRLF <-> LF.
"""
from __future__ import annotations

import re
from typing import Iterator, List, Pattern, Tuple

_JAVADOC_START_RE = re.compile(r"^\s*/\*\*")
_PRE_OPEN = "<pre>{@code"
_PRE_CLOSE = "</pre>"


# --------------------------------------------------------------------------- #
# file <-> lines
# --------------------------------------------------------------------------- #
def detect_newline(text: str) -> str:
    """Return the newline sequence to write back ('\\r\\n' if present, else '\\n')."""
    return "\r\n" if "\r\n" in text else "\n"


def split_lines(text: str) -> List[str]:
    """Split on CR?LF, dropping the separators (mirrors JS ``split(/\\r?\\n/)``)."""
    return re.split(r"\r?\n", text)


def join_lines(lines: List[str], newline: str) -> str:
    """Inverse of :func:`split_lines`."""
    return newline.join(lines)


# --------------------------------------------------------------------------- #
# javadoc block detection
# --------------------------------------------------------------------------- #
def is_active_javadoc_start(line: str) -> bool:
    return bool(_JAVADOC_START_RE.match(line))


def is_javadoc_end(line: str) -> bool:
    return "*/" in line


def active_javadoc_line_mask(lines: List[str]) -> List[bool]:
    """Boolean mask: ``True`` for every line that is part of a ``/** ... */`` block."""
    mask = [False] * len(lines)
    in_javadoc = False
    for i, line in enumerate(lines):
        if not in_javadoc and is_active_javadoc_start(line):
            in_javadoc = True
        if in_javadoc:
            mask[i] = True
        if in_javadoc and is_javadoc_end(line):
            in_javadoc = False
    return mask


def iter_javadoc_blocks(lines: List[str]) -> Iterator[Tuple[int, int]]:
    """Yield ``(start, end)`` inclusive line indices for each Javadoc block.

    ``end`` is the line that contains ``*/`` (or the last line of the file if a
    block is unterminated, matching the JS scanners' behaviour)."""
    i = 0
    n = len(lines)
    while i < n:
        if not is_active_javadoc_start(lines[i]):
            i += 1
            continue
        start = i
        end = i
        while end < n and "*/" not in lines[end]:
            end += 1
        if end >= n:
            end = n - 1
        yield start, end
        i = end + 1


def iter_code_lines(lines: List[str]) -> Iterator[Tuple[int, str]]:
    """Yield ``(index, line)`` for every line strictly inside a Javadoc
    ``<pre>{@code ... </pre>`` code block (the marker lines are not yielded).

    Uses the active-Javadoc mask so a stray ``</pre>`` in code outside a comment
    can't confuse the state -- exactly how the JS fixers tracked ``inCode``."""
    mask = active_javadoc_line_mask(lines)
    in_code = False
    for i, line in enumerate(lines):
        if not mask[i]:
            in_code = False
            continue
        if not in_code:
            if _PRE_OPEN in line:
                in_code = True
            continue
        if _PRE_CLOSE in line:
            in_code = False
            continue
        yield i, line


def iter_full_code_blocks(lines: List[str]) -> Iterator[Tuple[int, List[str]]]:
    """Yield ``(block_start_index, block_lines)`` for each ``<pre>{@code ... }</pre>``
    block, *including* the marker lines -- unlike :func:`iter_code_lines`, which yields
    only the lines strictly inside. Used where a check/fixer needs whole-block context
    (e.g. to collect variable declarations before inspecting their uses)."""
    mask = active_javadoc_line_mask(lines)
    in_block = False
    block_start = 0
    block_lines: List[str] = []
    for i, line in enumerate(lines):
        if not mask[i]:
            if in_block:
                yield block_start, block_lines
                in_block, block_lines = False, []
            continue
        if not in_block and _PRE_OPEN in line:
            in_block, block_start, block_lines = True, i, [line]
            if "}</pre>" in line:
                yield block_start, block_lines
                in_block, block_lines = False, []
            continue
        if in_block:
            block_lines.append(line)
            if "}</pre>" in line:
                yield block_start, block_lines
                in_block, block_lines = False, []


def code_of(line: str) -> str:
    """Strip the leading Javadoc gutter (``* `` / ``*``) from a line, leaving the
    example code/text after it."""
    return re.sub(r"^\s*\*\s?", "", line)


# --------------------------------------------------------------------------- #
# content queries (operate on a whole file's text)
# --------------------------------------------------------------------------- #
def active_javadocs_contain(text: str, pattern: Pattern[str]) -> bool:
    """True if ``pattern`` matches within any single Javadoc block's text."""
    lines = split_lines(text)
    for start, end in iter_javadoc_blocks(lines):
        if pattern.search("\n".join(lines[start:end + 1])):
            return True
    return False


def count_in_active_javadocs(text: str, pattern: Pattern[str]) -> int:
    """Total number of ``pattern`` matches across all Javadoc blocks."""
    lines = split_lines(text)
    count = 0
    for start, end in iter_javadoc_blocks(lines):
        count += len(pattern.findall("\n".join(lines[start:end + 1])))
    return count


# --------------------------------------------------------------------------- #
# in-line comment detection (respecting string / char literals)
# --------------------------------------------------------------------------- #
def comment_index_outside_literals(line: str) -> int:
    """Index of the ``//`` that starts a real comment, or -1.

    Slashes inside double- or single-quoted literals (e.g. ``"http://x"``) are
    ignored. Mirrors the identical helper duplicated across the JS scripts."""
    in_string = False
    in_char = False
    escaped = False
    for i in range(len(line) - 1):
        ch = line[i]
        if escaped:
            escaped = False
            continue
        if ch == "\\":
            escaped = True
            continue
        if not in_char and ch == '"':
            in_string = not in_string
            continue
        if not in_string and ch == "'":
            in_char = not in_char
            continue
        if not in_string and not in_char and ch == "/" and line[i + 1] == "/":
            return i
    return -1


def is_blank_javadoc_line(line: str) -> bool:
    """A bare ``*`` continuation line (possibly indented)."""
    return line.strip() == "*"


def line_prefix(line: str) -> str | None:
    """Leading whitespace up to (not including) the ``*``, or None if no star."""
    m = re.match(r"^(\s*)\*", line)
    return m.group(1) if m else None


# --------------------------------------------------------------------------- #
# light Java structure helpers (comment/literal aware, project-agnostic)
# --------------------------------------------------------------------------- #
_PACKAGE_RE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)\s*;", re.M)
_PUBLIC_TYPE_RE = re.compile(
    r"^\s*public\s+(?:abstract\s+|final\s+|sealed\s+|non-sealed\s+|strictfp\s+)*"
    r"\b(?:class|interface|enum|record)\b", re.M,
)
_PUBLIC_ANNOTATION_RE = re.compile(r"^\s*public\s+@interface\b", re.M)


def package_name(text: str) -> str:
    m = _PACKAGE_RE.search(text)
    return m.group(1) if m else ""


def strip_comments(text: str) -> str:
    """Blank out all comments and string/char literals, preserving newlines and
    character offsets, so braces/keywords can be scanned without false hits from
    text inside comments or strings. (Each blanked char becomes a space.)"""
    out: List[str] = []
    in_block = in_line = in_string = in_char = escaped = False
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if in_line:
            if ch in "\n\r":
                in_line = False
                out.append(ch)
            else:
                out.append(" ")
            i += 1
            continue
        if in_block:
            if ch == "*" and nxt == "/":
                out.append("  ")
                i += 2
                in_block = False
                continue
            out.append(ch if ch in "\n\r" else " ")
            i += 1
            continue
        if escaped:
            escaped = False
            out.append(" ")
            i += 1
            continue
        if (in_string or in_char) and ch == "\\":
            escaped = True
            out.append(" ")
            i += 1
            continue
        if not in_string and not in_char and ch == "/" and nxt == "/":
            out.append("  ")
            i += 2
            in_line = True
            continue
        if not in_string and not in_char and ch == "/" and nxt == "*":
            out.append("  ")
            i += 2
            in_block = True
            continue
        if not in_char and ch == '"':
            in_string = not in_string
            out.append(" ")
            i += 1
            continue
        if not in_string and ch == "'":
            in_char = not in_char
            out.append(" ")
            i += 1
            continue
        out.append(" " if (in_string or in_char) else ch)
        i += 1
    return "".join(out)


def has_public_top_level_type(text: str) -> bool:
    """True if the file declares a ``public`` class/interface/enum/record
    (excluding a bare ``public @interface``)."""
    code = strip_comments(text)
    return bool(_PUBLIC_TYPE_RE.search(code)) and not _PUBLIC_ANNOTATION_RE.search(code)


_TYPE_DECL_RE = re.compile(r"\b(?:class|interface|enum|record)\s+[A-Za-z_$][\w$]*")


def type_public_scope(lines: List[str]) -> List[bool]:
    """For each line, ``True`` if it sits inside a type-nesting whose enclosing types
    are ALL public -- i.e. a public method declared there is genuinely in scope
    ("public methods in public top-level OR nested public classes"). Lines outside any
    type, or inside a non-public nested type (e.g. a ``static final class Tuple0``),
    are ``False``.

    Braces and type declarations are read from a comment/string-stripped copy so braces
    inside comments or string literals don't skew the nesting. A type's ``public``-ness
    is taken from the modifiers preceding its keyword on the declaration line."""
    stripped = split_lines(strip_comments("\n".join(lines)))
    n = len(lines)
    ok = [False] * n
    depth = 0
    stack: List[dict] = []          # each: {"open_depth": int, "all_public": bool}
    pending_public = None           # a type was declared; its '{' not yet consumed
    for i in range(n):
        sl = stripped[i] if i < len(stripped) else ""
        ok[i] = stack[-1]["all_public"] if stack else False
        if pending_public is None:
            m = _TYPE_DECL_RE.search(sl)
            if m:
                pending_public = bool(re.search(r"\bpublic\b", sl[: m.start()]))
        for ch in sl:
            if ch == "{":
                if pending_public is not None:
                    parent_ok = stack[-1]["all_public"] if stack else True
                    stack.append({"open_depth": depth, "all_public": pending_public and parent_ok})
                    pending_public = None
                depth += 1
            elif ch == "}":
                depth -= 1
                if stack and stack[-1]["open_depth"] == depth:
                    stack.pop()
    return ok
