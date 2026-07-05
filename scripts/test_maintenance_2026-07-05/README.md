# Test-maintenance scripts (2026-07-05)

Reusable, dependency-free (Python 3, stdlib only) tooling for three recurring
test-hygiene tasks over `src/test/java`. **Run every command from the repo
root** — the scripts use repo-root-relative paths (`src/test/java`,
`src/main/java`).

## Files

| File | Purpose |
|------|---------|
| `java_test_parser.py` | Shared library. Lexical Java scanner (handles strings, chars, line/block comments, text blocks) that extracts `@Test` methods, balances braces, strips comments while preserving literals, and detects assertions. Imported by the other scripts. Run directly for a smoke summary (`files/methods/tests`). |
| `find_duplicates.py` | **Task 1** — finds test methods whose normalized bodies are identical (within-class and cross-class). String/char/numeric **literals are preserved** during normalization, so tests differing only in an argument are NOT flagged. |
| `remove_test_methods.py` | **Task 1/3 applier** — removes specific methods listed in a targets JSON. Idempotent (a method already gone is skipped). Supports `--dry-run`. |
| `map_ownership.py` | **Task 2** — maps each test file to the source class it owns (`FooTest`/`FooIntegrationTest`/`FooMySQLTest` → `Foo`) and reports scattering, orphans, infra, and missing coverage. |
| `find_no_assert.py` | **Task 3** — finds `@Test` methods with **zero** assertions or only **trivial** ones (`assertTrue(true)` / `assertFalse(false)`). Counts Mockito `verify(...)`, `fail(...)`, `assertThrows`, `assertDoesNotThrow` as assertions. |
| `task1_removals.json` | Targets removed for Task 1 (8 duplicate methods), with reasons. |
| `task3_stub_removals.json` | Target removed for Task 3 (1 dead stub, `csTest.testGetterConstant`). |
| `*_final.json` | Final read-only reports proving the end state (0 duplicates; only forbidden-package methods lack assertions). |

## Usage

```bash
# From repo root:
python scripts/test_maintenance_2026-07-05/java_test_parser.py                       # smoke summary

# Task 1: detect duplicates -> review -> remove
python scripts/test_maintenance_2026-07-05/find_duplicates.py --json /tmp/dups.json
python scripts/test_maintenance_2026-07-05/remove_test_methods.py \
       --targets scripts/test_maintenance_2026-07-05/task1_removals.json --dry-run
python scripts/test_maintenance_2026-07-05/remove_test_methods.py \
       --targets scripts/test_maintenance_2026-07-05/task1_removals.json

# Task 2: ownership / scattering report
python scripts/test_maintenance_2026-07-05/map_ownership.py

# Task 3: methods lacking a meaningful assertion
python scripts/test_maintenance_2026-07-05/find_no_assert.py
```

## Rules encoded (project-specific)

- **Do not modify** classes directly under `com.landawn.abacus` (e.g.
  `CodeHelper`, `JavaDocHelper`, `TestBase`, `Maven`, `AbacusJdbcTestSuite`).
  Only test classes in sub-packages may be edited. The scripts flag these via
  `forbidden_pkg` / by package so they are excluded from edits.
- **Integration tier is intentional**: `*IntegrationTest` (self-contained H2)
  and `*MySQLTest` (needs a real MySQL) are a deliberate separate test tier and
  are NOT merged into the unit `*Test` classes.
- A "duplicate" requires identical bodies **including literal arguments** —
  never fold tests that differ only in inputs/edge cases.

## Verifying results

The test suite uses JUnit Jupiter with `testFailureIgnore`/`@Suite`, so the
Maven exit code is not a reliable pass/fail signal. Parse the XML instead:

```bash
python - <<'PY'
import glob, xml.etree.ElementTree as ET
tot=fa=er=sk=0
for f in glob.glob("target/surefire-reports/*.xml"):
    r=ET.parse(f).getroot()
    tot+=int(r.get("tests",0)); fa+=int(r.get("failures",0))
    er+=int(r.get("errors",0)); sk+=int(r.get("skipped",0))
print(f"tests={tot} failures={fa} errors={er} skipped={sk}")
PY
```
