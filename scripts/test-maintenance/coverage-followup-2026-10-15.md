# Coverage follow-up — 2026-10-15

This pass raises full-suite line coverage from **93.70% to 95.04%** and branch coverage from **80.97% to 82.18%**. It adds coverage for 267 production lines and 154 branches, concentrated in DaoImpl and JdbcUtil. **100% coverage has not been reached.**

## Results

Baseline: commit `74b59707e485bb0e0f57e6c5aeee5e86acace1d8` and the [initial full-suite report](coverage-2026-10-15.md). The final suite includes unit tests and embedded H2 integration tests.

| Metric | Baseline | Final | Additional covered |
| --- | ---: | ---: | ---: |
| Lines | 18,588 / 19,838 (93.70%) | 18,855 / 19,838 (95.04%) | 267 |
| Branches | 10,251 / 12,661 (80.97%) | 10,405 / 12,661 (82.18%) | 154 |
| Instructions | 89,240 / 96,214 (92.75%) | 90,378 / 96,214 (93.93%) | 1,138 |
| Methods | 2,934 / 3,173 (92.47%) | 2,996 / 3,173 (94.42%) | 62 |

Source totals include nested classes.

| Source | Missed lines before | Missed lines after | Line coverage before | Line coverage after | Missed branches before → after |
| --- | ---: | ---: | ---: | ---: | ---: |
| DaoImpl.java | 485 | 414 | 89.85% | 91.34% | 1,218 → 1,146 |
| JdbcUtil.java | 410 | 214 | 87.79% | 93.63% | 402 → 320 |

Final validation: **5,530 cases discovered, 5,494 passed, 36 skipped, zero failures or errors**. The skips are the opt-in external MySQL tests. Compared with the baseline, 172 additional cases execute.

The ownership/discovery audit covers 179 test files and 4,341 test methods. It reports no problems, duplicate candidates, or assertion-review warnings. Whitespace checks pass.

## Test improvements

- JDBC OUT parameters are grouped in JdbcUtilOutParameterTest. Parameterized cases cover 26 SQL types through indexed and named access, including SQL NULL, floating-point and decimal precision, timestamps and time zones, Unicode, byte arrays, reference values, LOB materialization, and resource ownership. Five existing tests moved into this group, and two narrower tests were removed after stronger cases superseded them.
- JDBC query preparation covers generated-key options, large-result modes, failure cleanup, and connection reuse across 14 variants inside a transaction. Assertions check statement and connection ownership.
- JDBC batches cover execution and commit failures, successful rollback, auto-commit restoration failures, suppression order, empty input, and update counts exceeding the integer range.
- Metadata tests distinguish concurrent create/drop outcomes from real SQL failures and verify connection/savepoint cleanup. Result mapping and iterator tests cover aliases, prefixes, collisions, composite generated IDs, callback cleanup, and mapping/count/advance failures.
- DAO tests cover generated IDs mixed with supplied or zero IDs, selected-column and custom-SQL overloads, database-generated IDs when selected columns omit the ID, and chunk boundaries. Composite EntityId integration tests verify that reads, updates, and deletes bind every key component.
- DAO handler tests verify external-only invocation, nested calls, before/after ordering, failure suppression, invocation-state restoration, and proxy identity. Cache tests cover invalid declarations and simultaneous failures from thread-local and DAO caches.
- A real H2 join-deletion test fails after the first batch chunk, verifies rollback of earlier deletes, then removes the foreign-key guard and verifies a successful retry.

New integration fixtures own and close their H2 databases. Imports and test grouping were normalized in the changed files. The repository Maven configuration, suite discovery rules, and coverage exclusions were not changed.

## Remaining gap

The report still contains **983 missed lines and 2,256 missed branches**. DaoImpl and JdbcUtil account for 628 of those lines.

The remaining gap is a mixture:

- Testable behavior still needs coverage: callable DAO dispatch and binding combinations, connection/data-source alternatives, metadata fallback/error cases, transaction-boundary cleanup, and cache-key/container handling.
- Some paths depend on runtime or optional-library availability, including method-handle access and serialization/container-copy fallbacks.
- Some paths are disabled by the current implementation. In particular, JoinInfo sets `cascadeDeleteDefinedInDB = true`, so its deletion plans never supply the second SQL statement. This leaves 24 missed DaoImpl lines in the two-statement join-deletion paths. The earlier suggestion that ordinary integration tests could reach these branches was incorrect; the added rollback test exercises the supported batch-deletion path.
- Other examples include identifier-quoting paths behind a fixed false setting and annotated-query mapper/filter branches rejected before dispatch construction. These should be reviewed as production design/dead-code questions, rather than enabled or excluded merely to raise coverage.

The method with the largest remaining DaoImpl gap is `createQueryFunctionByMethod` (129 missed lines); it contains both dispatch variants needing tests and branches constrained by declaration validation. JdbcUtil's remaining misses are more dispersed; `dropTableIfExists` is its largest single method gap at 12 lines.

No production policy or coverage exclusion was changed for this pass. Concurrent DaoImpl helper-name and comment edits were preserved. The final full run included those edits; a later change only restored one private parameter name and its Javadoc, with identical logic and line positions.

## Reproduction and artifacts

The standard repository command is:

```powershell
mvn clean test jacoco:report
node scripts/test-maintenance.mjs --check
```

Validation used JDK 25 and Maven 3.9.16. An ignored copy of the POM used absolute repository source/resource paths and a separate build directory, preserving the same dependencies, compiler settings, suite, and coverage exclusions. Test JVMs were capped at 768 MiB and two active processors; test output was redirected to report files.

The final complete run used fresh JaCoCo execution data:

```powershell
mvn -o -l target/coverage-followup-2026-10-15/final-suite.log -f target/coverage-followup-2026-10-15/final-pom.xml '-Djacoco.append=false' test jacoco:report
node scripts/test-maintenance.mjs --check --json target/coverage-followup-2026-10-15/final-inventory.json
git diff --check
```

Local generated artifacts, excluded from version control:

- [Final HTML coverage report](../../target/coverage-followup-2026-10-15/final-build/site/jacoco/index.html)
- [Final XML coverage report](../../target/coverage-followup-2026-10-15/final-build/site/jacoco/jacoco.xml)
- [Baseline XML](../../target/coverage-followup-2026-10-15/baseline-jacoco.xml)
- [Coverage comparison JSON](../../target/coverage-followup-2026-10-15/summary.json)
- [Full-suite Maven log](../../target/coverage-followup-2026-10-15/final-suite.log)
- [Surefire reports](../../target/coverage-followup-2026-10-15/final-build/surefire-reports)
- [Final test audit](../../target/coverage-followup-2026-10-15/final-inventory.json)
