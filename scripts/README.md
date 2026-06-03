# scripts/ — Javadoc "Usage Examples" toolkit

Project-agnostic tooling for auditing and tidying the Javadoc **Usage Examples**
blocks — the `<pre>{@code ... }</pre>` snippets — on public methods of public
classes anywhere under `src/main/java/`. Nothing is tied to a package or class
name, so the same tools work on any project's source tree.

It supports a **comments-only** workflow: find which documented public methods
need examples, write/fix examples and **verify them against real library
behavior with a throwaway test**, normalize the block structure, align the
columns, and confirm that *only comments changed*. The tools **never invent the
text of a behavior comment** — expected values are proven with a test, never
guessed.

Two ground rules baked into every tool:

- **Reports never write.** **Fixers are dry-run unless you pass `--apply`.**
- A file's newline style (CRLF/LF) is detected per file and **preserved** on write.

---

## Quick start — the per-class workflow

Process one class at a time (Steps A–F):

```bash
# A. See what needs work, and list the in-scope documented methods.
python scripts/jdoc.py audit src/main/java
python scripts/jdoc.py report missing-examples src/main/java/com/example/Foo.java
python scripts/jdoc.py report methods          src/main/java/com/example/Foo.java

# B–D. Write or fix the examples in Foo.java, then PROVE every documented value
#      with a throwaway JUnit test compiled in isolation against target/classes:
python scripts/verify_jdoc_test.py src/test/java/com/example/FooJavadocVerifyTest.java
#   -> "RESULT: PASS (N test(s))"  (iterate Javadoc + test until green)

# E. Normalize the block structure and align the // columns (comment-only):
python scripts/jdoc.py fix usage-spacing --apply src/main/java/com/example/Foo.java
python scripts/align_jdoc_examples.py    --apply src/main/java/com/example/Foo.java

# Guard + F. Confirm only comments changed, then delete the throwaway test:
python scripts/jdoc.py verify-comment-only src/main/java/com/example/Foo.java
```

Whole-tree variants (`audit`, `validate`, `cleanup`) operate across the project
in one shot — see below.

---

## `jdoc.py` — the CLI

A single entry point over the `jdoc_tools/` package:

```
python scripts/jdoc.py <command> [options] [PATH ...]
```

`PATH` is one or more `.java` files or directories (directories are walked,
skipping `.git`/`target` and `package-info.java`). Run `python scripts/jdoc.py -h`
(or `<command> -h`) for the built-in list.

**Global conventions**

- *Tree commands* (`audit`, `eligible`, `inventory`, `validate`, `cleanup`)
  default their root to `src/main/java` and accept repeatable
  `--exclude-package <pkg>` to skip a package.
- *"In scope"* means a **public method that already has a Javadoc**, inside
  **public** types only (top-level or nested public) — package-private nested
  types (e.g. a `static final class Tuple0`) are excluded.
- **Exit codes** (CI-friendly): "issue" reports, `validate`, and dry-run fixers
  return **1** when there are findings / pending changes (else 0). "Info"
  reports always return 0.

### Discovery & overview

| Command | What it does |
|---|---|
| `audit [root]` | Per-file table of **documented public methods**, how many **have** an examples block, and how many are **missing** one, plus a project TOTAL. The Step-A overview. |
| `eligible [root]` | Lists `.java` files that **already contain** a Usage Examples block, as `file⟶TAB⟶<code-block count>⟶TAB⟶<package>`. (`inventory` is an alias.) |

### `report <name> <PATH…>` — read-only checks

Each prints `path:line: …` findings and a trailing `<NAME>_COUNT <n>`. "issue"
reports exit 1 when they find anything; "info" reports exit 0.

| Report | Kind | What it finds |
|---|---|---|
| `methods` | info | Every in-scope documented public method, tagged `[examples]` or `[NO-EXAMPLES]`, with its signature. |
| `missing-examples` | info | The subset of in-scope documented public methods whose Javadoc has **no** Usage Examples block — i.e. the work list for "add examples". |
| `usage-check` | issue | Structural defects in a Usage Examples block: `usage_after_tag` (block placed after `@param`/etc.), `missing_blank_before_usage`, `missing_blank_after_pre`, `usage_indent_mismatch`, `stray_duplicate_star` (the malformed `*   * <p><b>Usage…` line), `double_blank_javadoc_line`. |
| `missing-behavior-comments` | issue | Example **call statements** inside `{@code}` that lack a trailing `// returns …` / `// throws …` (or a follow-up behavior line). Setup/declaration lines are exempt. |
| `placeholders` | issue | Unresolved `...` placeholders and hand-wavy markers (`typical usage`, `edge case`) left inside example code. |
| `sample-member-misuse` | issue | Array variables that call `.size()`, or collection variables that read `.length`, inside example code. |
| `suspicious-strings` | issue | A `// returns`/`// throws` accidentally **buried inside a string literal** in example code. |
| `scan` | info | Generic substring search inside active Javadoc. Use `--needle "<text>"` (default `// returns`). |
| `changed-methods` | info | Which Javadoc'd methods were touched in the working tree, per `git diff`. |

### `fix <name> <PATH…> [--apply]` — in-place fixers

Dry-run by default (prospective edits print with a `[dry-run]` prefix and the
command exits 1 if anything would change); `--apply` writes. These only reshape
**structure** or make **mechanical, behavior-preserving** corrections — they
never rewrite the *content* of a behavior comment.

| Fixer | What it does |
|---|---|
| `usage-indent` | Re-indents a Usage Examples block's gutter to match the surrounding `@tags`/description indentation. |
| `usage-spacing` | Enforces exactly **one** blank gutter line (` *`) before the `Usage Examples` header and after `</pre>`; collapses extras. |
| `double-blank` | Drops consecutive blank gutter lines anywhere in a Javadoc block. |
| `move-comments-into-pre` | Pulls stray `// …` sample-comment lines sitting just outside `<pre>{@code}` into the block. |
| `type-placeholders` | Rewrites `Type<...>` → `Type<?, ?, ?>` inside example code. |
| `literal-artifacts` | Strips a leaked `// returns …` out of a string/URL in example code (e.g. `"http:// returns x"` → `"http://x"`). |
| `sample-member-misuse` | Rewrites array `var.size()` → `var.length` in examples (the fix companion to the report of the same name). |
| `assignment-style` | Converts `Type.call(...) = result` example lines into `Type.call(...);   // returns result`. |
| `align` | **Delegates to `align_jdoc_examples.py`** — display-width-aware column alignment of the `//`/`=` markers (see below). |
| `move-usage-before-tags` | **Delegates to `fix_javadoc_format4.py`** — moves the Usage Examples block before the `@tags` and collapses double blanks. **`--apply` only** (no dry-run). |

### Project pipelines

| Command | What it does |
|---|---|
| `validate [root]` | Over every *eligible* file, runs the issue reports (`usage-check`, `missing-behavior-comments`, `placeholders`, `sample-member-misuse`, `suspicious-strings`) + an `align --check` + the git comment-only guard; prints `FAIL <check> <file>` blocks and `VALIDATED/FAILED_FILE_COUNT`. Exit 1 on any failure. |
| `cleanup [root] [--apply]` | Runs the structural fixers across every eligible file in dependency order: `move-usage-before-tags` (apply only) → `usage-indent` → `usage-spacing` → `double-blank` → `type-placeholders` → `literal-artifacts` → `sample-member-misuse` → `assignment-style` → `align` (last). Dry-run unless `--apply`. |
| `verify-comment-only <PATH…>` | Asserts a file's `git diff` touches **only** comment lines (blank, `*`, `//`, `/*`, `*/`); prints any offending non-comment changed lines. Exit 1 on violation. The "only comments were edited" guard. |

---

## Standalone scripts (reused by the CLI, also runnable directly)

- **`align_jdoc_examples.py [--check|--apply] PATH…`**
  Aligns the inline `//` (and standalone `=`) columns inside `{@code}` blocks so
  they line up. Uses **display width** (East-Asian-width aware), so full-width /
  CJK characters don't drift the alignment; idempotent on already-aligned code.
  `--check` (default) previews and exits 1 if anything would change; `--apply`
  writes. Backs `cleanup`, `validate`, and `fix align`.

- **`fix_javadoc_format4.py [PATH]`**
  Moves a Usage Examples block that was placed *after* the `@param`/`@return`/…
  tags to *before* them (the required order), and collapses double blank lines
  around it. Always writes (no dry-run). Backs `cleanup` and
  `fix move-usage-before-tags`. Defaults `PATH` to `src/main/java`.

- **`verify_jdoc_test.py <SomeJavadocVerifyTest.java>`**
  Maven-free runner for **one** throwaway verification test. It compiles *only*
  that test into a private temp dir against the already-built `target/classes`
  plus the project's test dependencies, then runs it via `JdocTestRunner` and
  prints `RESULT: PASS (N)` or the failures. Because nothing is written to the
  shared `target/test-classes`, **many agents can verify their own tests in
  parallel** without clobbering each other. On first use it auto-generates
  `scripts/.testcp.txt` (via `mvn -o dependency:build-classpath`) and compiles
  the runner. Since the Javadoc workflow is comments-only, `target/classes`
  never needs rebuilding between runs. A faster alternative to
  `mvn -o test -Dtest=… -Dmaven.main.skip=true`.

- **`JdocTestRunner.java`**
  A tiny JUnit 5 Platform launcher used by `verify_jdoc_test.py`; compiled once
  into `scripts/runner-classes/` (git-ignored).

---

## Package layout (`jdoc_tools/`)

| Module | Contents |
|---|---|
| `region.py` | Shared primitives: newline-preserving `split_lines`/`join_lines`; active-Javadoc masks; `<pre>{@code}` block iterators (`iter_code_lines`, `iter_full_code_blocks`); `comment_index_outside_literals`; and comment/string-aware Java structure helpers (`package_name`, `strip_comments`, `has_public_top_level_type`, `type_public_scope`). |
| `reports.py` | The read-only checks and the `documented_public_methods` scanner (which respects nested-class visibility). Exposes the `REPORTS` registry. |
| `fixes.py` | The in-place structural fixers. Exposes the `FIXES` registry. |
| `pipeline.py` | File discovery (`public_type_files`, `eligible_files`), the `audit`/`validate`/`cleanup` orchestrators, and the git `verify_comment_only`. Lazily reuses the two standalone scripts above. |

---
