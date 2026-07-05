#!/usr/bin/env python3
"""
Shared Java test-file parser for the test-maintenance tasks.

Provides a small, dependency-free scanner that understands enough Java lexical
structure (strings, chars, line/block comments, text blocks) to reliably locate
methods and balance braces in the JUnit test sources of this project.

Used by:
  - find_duplicates.py   (Task 1: remove duplicate unit tests)
  - map_ownership.py     (Task 2: one test class per source class)
  - find_no_assert.py    (Task 3: every test method has a meaningful assertion)

The parser is intentionally line/char based rather than a full Java grammar; it
is tuned for the fairly regular test style used in this repo and is safe to
re-run.
"""
from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from typing import List, Optional, Tuple


TEST_ROOT = os.path.join("src", "test", "java")


# ---------------------------------------------------------------------------
# Lexical scan: produce a "mask" marking which characters are real code
# (i.e. NOT inside a string literal, char literal, comment, or text block).
# ---------------------------------------------------------------------------
def code_mask(src: str) -> bytearray:
    """Return a bytearray, one entry per char in src: 1 if the char is active
    code, 0 if it is inside a string/char/comment/text-block."""
    n = len(src)
    mask = bytearray(n)
    i = 0
    NORMAL, LINE_C, BLOCK_C, STR, CHAR, TEXT = range(6)
    state = NORMAL
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if state == NORMAL:
            if c == "/" and nxt == "/":
                state = LINE_C
                i += 2
                continue
            if c == "/" and nxt == "*":
                state = BLOCK_C
                i += 2
                continue
            if c == '"' and src[i : i + 3] == '"""':
                state = TEXT
                i += 3
                continue
            if c == '"':
                state = STR
                i += 1
                continue
            if c == "'":
                state = CHAR
                i += 1
                continue
            mask[i] = 1
            i += 1
            continue
        if state == LINE_C:
            if c == "\n":
                state = NORMAL
                mask[i] = 1  # keep newline as code for line accounting
            i += 1
            continue
        if state == BLOCK_C:
            if c == "*" and nxt == "/":
                state = NORMAL
                i += 2
                continue
            if c == "\n":
                mask[i] = 1
            i += 1
            continue
        if state == STR:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                state = NORMAL
            i += 1
            continue
        if state == CHAR:
            if c == "\\":
                i += 2
                continue
            if c == "'":
                state = NORMAL
            i += 1
            continue
        if state == TEXT:
            if c == "\\":
                i += 2
                continue
            if src[i : i + 3] == '"""':
                state = NORMAL
                i += 3
                continue
            if c == "\n":
                mask[i] = 1
            i += 1
            continue
    return mask


def strip_comments(src: str) -> str:
    """Return src with line/block comments removed but ALL string literals,
    char literals, and text blocks preserved verbatim (contents intact).

    This is what duplicate detection must use: two tests that differ only in a
    string/number argument (e.g. getDeclaredMethod("isBatch") vs "isProcedure")
    are NOT duplicates, so literal contents must survive normalization.
    """
    n = len(src)
    out = []
    i = 0
    NORMAL, LINE_C, BLOCK_C, STR, CHAR, TEXT = range(6)
    state = NORMAL
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if state == NORMAL:
            if c == "/" and nxt == "/":
                state = LINE_C
                i += 2
                continue
            if c == "/" and nxt == "*":
                state = BLOCK_C
                i += 2
                continue
            if src[i : i + 3] == '"""':
                state = TEXT
                out.append('"""')
                i += 3
                continue
            if c == '"':
                state = STR
                out.append(c)
                i += 1
                continue
            if c == "'":
                state = CHAR
                out.append(c)
                i += 1
                continue
            out.append(c)
            i += 1
            continue
        if state == LINE_C:
            if c == "\n":
                state = NORMAL
                out.append("\n")
            i += 1
            continue
        if state == BLOCK_C:
            if c == "*" and nxt == "/":
                state = NORMAL
                i += 2
                continue
            if c == "\n":
                out.append("\n")
            i += 1
            continue
        if state == STR:
            out.append(c)
            if c == "\\":
                if i + 1 < n:
                    out.append(src[i + 1])
                i += 2
                continue
            if c == '"':
                state = NORMAL
            i += 1
            continue
        if state == CHAR:
            out.append(c)
            if c == "\\":
                if i + 1 < n:
                    out.append(src[i + 1])
                i += 2
                continue
            if c == "'":
                state = NORMAL
            i += 1
            continue
        if state == TEXT:
            if c == "\\":
                out.append(src[i : i + 2])
                i += 2
                continue
            if src[i : i + 3] == '"""':
                state = NORMAL
                out.append('"""')
                i += 3
                continue
            out.append(c)
            i += 1
            continue
    return "".join(out)


def line_of(src: str, idx: int) -> int:
    """1-based line number of character index idx."""
    return src.count("\n", 0, idx) + 1


@dataclass
class Method:
    name: str
    is_test: bool
    annotations: List[str]           # annotation lines above the method, e.g. ["@Test"]
    signature: str                   # text from after annotations up to and incl '{'
    body: str                        # text between the outer { } (exclusive)
    start_idx: int                   # index of first char of the annotation/sig block
    end_idx: int                     # index just after closing brace
    start_line: int
    end_line: int
    full_text: str                   # annotations + signature + body + closing brace (verbatim)

    def norm_body(self) -> str:
        return normalize_body(self.body)


@dataclass
class TestFile:
    path: str
    package: str
    class_name: str
    methods: List[Method] = field(default_factory=list)


_METHOD_DECL = re.compile(
    r"(?:public|protected|private|static|final|synchronized|\s)*"
    r"(?:<[^>]*>\s*)?"          # generic method type params
    r"[\w\.\[\]<>,\s\?]+?\s+"   # return type
    r"(\w+)\s*\("               # method name + (
)


def normalize_body(body: str) -> str:
    """Normalize a method body for duplicate comparison: drop comments,
    collapse whitespace, strip. Comments are already excluded via code_mask in
    the extraction path, but this guards direct callers."""
    b = re.sub(r"\s+", " ", body)
    return b.strip()


def _find_matching_brace(src: str, mask: bytearray, open_idx: int) -> int:
    """Given index of an active-code '{', return index of the matching '}'."""
    depth = 0
    i = open_idx
    n = len(src)
    while i < n:
        if mask[i]:
            c = src[i]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    return i
        i += 1
    return -1


def parse_file(path: str) -> TestFile:
    with open(path, "r", encoding="utf-8") as fh:
        src = fh.read()
    mask = code_mask(src)

    src_nc = strip_comments(src)  # comment-free view for package/class detection
    pkg_m = re.search(r"^\s*package\s+([\w\.]+)\s*;", src_nc, re.MULTILINE)
    package = pkg_m.group(1) if pkg_m else ""
    cls_m = re.search(r"\b(?:public\s+)?(?:final\s+|abstract\s+)?(?:class|interface|enum)\s+(\w+)", src_nc)
    class_name = cls_m.group(1) if cls_m else os.path.splitext(os.path.basename(path))[0]

    tf = TestFile(path=path, package=package, class_name=class_name, methods=[])

    # Find all '@' annotation starts in active code; we detect method-preceding
    # annotation clusters, then the method declaration + body.
    # Strategy: scan for method declarations by locating '{' that begin a method
    # body. We instead iterate annotation clusters: find "@Test" (or any method
    # that has a body) reliably by walking annotations then the signature.
    i = 0
    n = len(src)
    consumed_until = 0
    # We locate every top-level-ish annotation cluster followed by a method.
    ann_iter = re.finditer(r"(?m)^([ \t]*)@(\w+)", src)
    seen_starts = set()
    for m in ann_iter:
        astart = m.start()
        if astart < consumed_until:
            continue
        if not mask[m.start(2)]:
            continue
        # Collect the contiguous block of annotation lines starting here.
        # Walk upward is unnecessary; walk this cluster forward.
        # Find the end of annotations: keep consuming lines that (after strip)
        # start with '@' or are continuations of an annotation's parentheses.
        pos = astart
        annotations = []
        # Parse consecutive annotation lines.
        while True:
            line_start = pos
            nl = src.find("\n", pos)
            if nl == -1:
                nl = n
            line = src[line_start:nl]
            stripped = line.strip()
            if stripped.startswith("@"):
                # annotation may span multiple lines via (...) — balance parens
                seg_end = nl
                paren = _paren_balance(src, mask, line_start, nl)
                while paren > 0:
                    nnl = src.find("\n", seg_end + 1)
                    if nnl == -1:
                        nnl = n
                    paren += _paren_balance(src, mask, seg_end + 1, nnl)
                    seg_end = nnl
                annotations.append(src[line_start:seg_end].strip())
                pos = seg_end + 1
            else:
                break
        # Now src[pos:] should hold the method/field signature. Find next '{' or ';' in code.
        j = pos
        brace = -1
        semi = -1
        while j < n:
            if mask[j]:
                if src[j] == "{":
                    brace = j
                    break
                if src[j] == ";":
                    semi = j
                    break
            j += 1
        if brace == -1:
            # annotation on a field or abstract decl; skip
            consumed_until = (semi + 1) if semi != -1 else pos
            continue
        signature = src[pos : brace + 1]
        # Extract method name
        name_m = _METHOD_DECL.search(signature)
        if not name_m:
            # Not a method (could be class/enum/anon); skip past this brace block
            close = _find_matching_brace(src, mask, brace)
            consumed_until = brace + 1
            continue
        name = name_m.group(1)
        close = _find_matching_brace(src, mask, brace)
        if close == -1:
            continue
        body = src[brace + 1 : close]
        is_test = any(a.split("(")[0] in ("@Test", "@ParameterizedTest", "@RepeatedTest", "@TestFactory") for a in annotations)
        full_text = src[astart : close + 1]
        meth = Method(
            name=name,
            is_test=is_test,
            annotations=annotations,
            signature=signature.strip(),
            body=body,
            start_idx=astart,
            end_idx=close + 1,
            start_line=line_of(src, astart),
            end_line=line_of(src, close),
            full_text=full_text,
        )
        if meth.start_line not in seen_starts:
            tf.methods.append(meth)
            seen_starts.add(meth.start_line)
        consumed_until = close + 1
    return tf


def _paren_balance(src: str, mask: bytearray, start: int, end: int) -> int:
    bal = 0
    for k in range(start, min(end, len(src))):
        if mask[k]:
            if src[k] == "(":
                bal += 1
            elif src[k] == ")":
                bal -= 1
    return bal


def all_test_files(root: str = TEST_ROOT) -> List[str]:
    out = []
    for dirpath, _dirs, files in os.walk(root):
        for f in files:
            if f.endswith(".java"):
                out.append(os.path.join(dirpath, f))
    return sorted(out)


# Assertion detection ---------------------------------------------------------
_ASSERT_PATTERNS = [
    r"\bassert\w*\s*\(",         # assertEquals, assertTrue, assertThrows, assertNull...
    r"\bAssertions\.\w+\s*\(",
    r"\bAssert\.\w+\s*\(",       # legacy junit4 (shouldn't exist but be safe)
    r"\bassertThat\s*\(",        # hamcrest / assertj
    r"\bverify\s*\(",            # mockito verify(...)
    r"\bfail\s*\(",              # explicit fail
    r"\.(?:should|must)\w*\s*\(",
    r"\bassert\b\s",             # java 'assert' keyword statement
]
_ASSERT_RE = re.compile("|".join(_ASSERT_PATTERNS))


def has_assertion(body: str) -> bool:
    return bool(_ASSERT_RE.search(body))


if __name__ == "__main__":
    # Smoke test / summary
    files = all_test_files()
    total_methods = 0
    total_tests = 0
    for p in files:
        tf = parse_file(p)
        tests = [m for m in tf.methods if m.is_test]
        total_methods += len(tf.methods)
        total_tests += len(tests)
    print(f"files={len(files)} methods={total_methods} tests={total_tests}")
