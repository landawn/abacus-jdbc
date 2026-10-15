# Coverage improvement — 2026-10-15

Full-suite line coverage increased from **95.04% to 96.13%**, and branch coverage
from **82.18% to 83.12%**. The added tests cover **216 previously missed lines,
119 branches, and 67 methods**. No previously covered production line was lost.
**100% line and branch coverage have not been reached.**

## Plan and implementation

1. Compare the current suite with its validated coverage baseline and inspect
   missed lines in their calling context. Separate reachable cases from guards,
   disabled features, and runtime-dependent fallbacks.
2. Extend existing behavior groups and use parameterized cases for equivalent
   overloads. Add dedicated groups for DAO preparation, lazy stream failures,
   procedures, join-deletion failures, and JDBC statement options.
3. Assert values, exact JDBC arguments, exception identity and suppression,
   lazy execution, and resource ownership. Exercise public APIs or package
   adapters without changing production state to bypass validation.
4. Run focused tests, the ownership/discovery audit, and the full default suite
   with fresh compiled classes and JaCoCo data. Record the remaining gap.

All four steps are implemented. Production sources, dependency versions, suite
selection, and coverage exclusions are unchanged.

## Results

Baseline: commit `3f44645df6767bde7b31605f3fe2a07e4237682b`, with the validated
coverage retained by the [preceding cleanup](cleanup-2026-10-15.md).

| Metric | Baseline | Final | Additional covered |
| --- | ---: | ---: | ---: |
| Lines | 18,855 / 19,838 (95.04%) | 19,071 / 19,838 (96.13%) | 216 |
| Branches | 10,405 / 12,661 (82.18%) | 10,524 / 12,661 (83.12%) | 119 |
| Instructions | 90,378 / 96,214 (93.93%) | 91,400 / 96,214 (95.00%) | 1,022 |
| Methods | 2,996 / 3,173 (94.42%) | 3,063 / 3,173 (96.53%) | 67 |
| Classes | 186 / 187 | 187 / 187 | 1 |

Source totals include nested classes. Class coverage means that a class was
executed; it does not imply complete line or branch coverage.

| Source | Missed lines before | Missed lines after |
| --- | ---: | ---: |
| DaoImpl.java | 414 | 357 |
| JdbcUtil.java | 214 | 152 |
| JdbcCodeGenerationUtil.java | 61 | 30 |
| UncheckedDao.java | 14 | **0** |
| UncheckedDaoBase.java | 9 | **0** |
| ResultSetProxy.java | 8 | **0** |
| UncheckedCrudUpdateOps.java | 4 | **0** |
| JoinEntityDeleteOps.java | 16 | 4 |
| UncheckedJoinEntityDeleteOps.java | 16 | 4 |
| DataTransferUtil.java | 18 | 12 |
| Jdbc.java | 37 | 36 |

Validation:

- **5,851 cases discovered; 5,815 passed; 36 external MySQL cases skipped;
  zero failures or errors.** This adds 145 executable cases to the baseline.
- The ownership/discovery audit covers 188 test classes with cases and 4,060
  source methods. It reports no problems, duplicate candidates, or assertion
  review findings.
- `git diff --check` passes.
- The scalar test was checked again after explicitly ordering its first-row
  query, so its expected value does not depend on an unspecified database order.

## Behavior covered

- Twenty unchecked DAO preparation variants verify returned query types, named
  and positional binding, generated-key options, custom statement factories,
  fetch settings, original SQL exception causes, and owned-connection cleanup.
  H2 cases also exercise condition-based preparation and selected columns.
- Ten lazy DAO stream overloads each exercise preparation and execution
  failures. Creating a stream performs no connection acquisition; traversal
  preserves the SQL cause and closes only resources that were acquired.
- DAO procedure tests cover indexed and named OUT parameters, dataset lists
  with and without OUT results, named/positional/collection/array inputs, fetch
  hints, query timeouts, and cleanup.
- Primitive optional queries distinguish no row, SQL NULL, one row, and
  duplicate rows. First-row queries and uniqueness-enforcing queries retain
  their different contracts.
- Checked and unchecked join deletion cover all four multi-property overloads
  when deletion and real transaction rollback both fail. The delete failure
  stays primary, rollback failure is suppressed, subsequent properties are
  untouched, and the connection is closed without restoring auto-commit after
  failed rollback. Connection-accepting transaction callbacks have matching
  suppression assertions.
- JDBC tests cover pagination failures across connection/data-source,
  named/positional, and extractor variants; iterator metadata and cleanup
  errors; case/schema metadata retries; Oracle SQL extraction; and exact
  result-set type, concurrency, and holdability forwarding.
- Blob/Clob file writes preserve open/read failures and suppress stream-close
  and LOB-free failures in order. CSV tests cover escaped multiline quotes and
  bounded diagnostics for unterminated fields.
- Generated-code tests cover field modifiers, array declarations, generic
  initializers, annotation compatibility, default configuration, and SQL array
  subscripts containing quoted text or PostgreSQL parameters.
- SQLType-aware result-set updates cover all four index/label and scale
  combinations, including driver exception identity. Single-property unchecked
  updates preserve null values, and the capacity-based map cache factory joins
  existing cache contract checks.

## Remaining gap

There are **767 missed lines and 2,137 missed branches**. The largest source
gaps are DaoImpl (357 lines), JdbcUtil (152), NamedQuery (58), Jdbc (36), and
JdbcCodeGenerationUtil (30).

Further tests can still improve generated-ID edge cases, join result shapes,
entity/column alias mapping, metadata and connection failures, cache handling,
and parser boundaries. The remaining misses are not all unreachable.

Some misses need a production-design or runtime-matrix decision:

- JoinInfo fixes `cascadeDeleteDefinedInDB` to true, leaving 24 DaoImpl lines
  for a second join-delete statement unreachable through generated plans.
- Identifier quoting is disabled by a fixed setting. This also constrains
  quoting-dependent metadata and drop-table fallback paths.
- Annotated-query mapper/filter declarations are rejected before dispatch
  construction, leaving their dispatch branches unreachable through accepted
  DAO declarations.
- Several NamedQuery checks follow name or stream-length validation that has
  already rejected the invalid input.
- Optional driver/library availability, Spring absence/linkage failures,
  method-handle access, and serialization/Unsafe fallbacks require appropriate
  isolated runtime configurations. They were not simulated by mutating
  production internals just to change the coverage score.

## Reproduction and artifacts

Standard repository commands:

```powershell
mvn clean test jacoco:report
node scripts/test-maintenance.mjs --check
git diff --check
```

Validation used JDK 25 and Maven 3.9.16. An ignored POM copy points to the same
repository sources and resources, writes to a fresh build directory, redirects
test output, and caps the test JVM at 768 MiB and two active processors. The
full run uses the default suite and fresh execution data:

```powershell
mvn -o -l target/coverage-push-2026-10-15/final-suite.log -f target/coverage-push-2026-10-15/final-pom.xml '-Djacoco.append=false' test jacoco:report
node scripts/test-maintenance.mjs --check --json target/coverage-push-2026-10-15/test-inventory.json
```

Local generated artifacts, excluded from version control:

- [HTML coverage report](../../target/coverage-push-2026-10-15/final-build/site/jacoco/index.html)
- [Full-suite log](../../target/coverage-push-2026-10-15/final-suite.log)
- [Coverage comparison](../../target/coverage-push-2026-10-15/coverage-summary.json)
- [Remaining missed lines with source text](../../target/coverage-push-2026-10-15/remaining-lines.json)
- [Ownership/discovery audit](../../target/coverage-push-2026-10-15/test-inventory.json)
