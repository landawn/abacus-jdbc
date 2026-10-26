# Line-by-line review — 2026-10-25

Scope: every class under `src/main/java` (101 files, ~114,900 lines), reviewed in 41
non-overlapping windows by parallel read-only agents (large files were split into line-range
segments). Each agent read its whole window, checked callers/tests/CHANGES.md, and reported only
findings with a verbatim code or Javadoc quote and a concrete trace. The main agent verified every
reported code bug against the source before editing.

## Fixed and covered by regression tests

| Area | Fix | Test |
| --- | --- | --- |
| `CrudDao.upsert` / `batchUpsert` | A key component holding `NULL` was matched with `IS NULL` but written back by the by-id `UPDATE` (`= NULL`). Nullable rows now retain the original match criteria and exclude ID assignments; complete IDs retain the by-id binder and batching. | `DaoImplIntegrationTest#testUpsert_NullableCompositeKeyUpdatesTheMatchedRow`, `DaoImplUpsertRegressionTest` |
| `DaoImpl` custom `@Query` INSERT | Key-column selection follows the resolved SQL destination, not parameter types. Scalar entity-table inserts request the entity IDs; other tables request the driver's keys. Qualified/quoted names use the execution connection's namespace and identifier case rules. | `DaoImplIntegrationTest#testCustomInsert_IntoAnotherTableWithDifferentlyNamedKeyReturnsItsKey`, `DaoImplGeneratedKeyRegressionTest` |
| `DaoImpl` `@CacheResult` | A write method (custom `@Query` executed as an update) could be served from/stored in the result cache, skipping the SQL on an identical call. `cacheResultEnabled` now excludes writes. | `DaoImplIntegrationTest#testCacheResult_onCustomInsertIsNotServedFromTheCache` |
| `JdbcUtil` label conversion | A date/time value read by label used a metadata index that prefers an earlier column's *name*; with a shadowing alias, the wrong column was converted and returned. Uses `ResultSet.findColumn` (with metadata fallback). | `JdbcUtilColumnConversionTest#getColumnValue_byLabel_resolvesTheSameColumnAsTheLabel` |
| `DaoImpl` `@MappedByKey`/`@MergedById` | Rows were mapped into the declared supertype value/element type; an abstract supertype failed every call. Maps into the DAO entity class, whose instances satisfy the declared type. | `DaoImplIntegrationTest#testMappedByKey_SupertypeValueTypeReturnsTheEntity` |

Verification (JDK 21, offline): `mvn -o test-compile -Djacoco.skip=true` is green; the five new
regression tests plus the adjacent existing tests (`testMappedByKey`, `testMergedById_Optional`,
`testMergedById_List`, `testCustomInsert_IntoAnotherTableReturnsItsKeyAsTheDeclaredType`,
`testCachedQuery_Cached`, `testBatchUpsert`, `testBatchUpsert_MultiUniqueProps`) run 12/12 green.
The full `DaoImplIntegrationTest` class runs 117 tests: 115 pass and the only 2 errors are Mockito's
sandboxed mock-maker (see the environment limitation below). `javadoc -Xdoclint:all` over every
edited public source exits 0.

## Javadoc/comment corrections

- `JdbcUtil`: batch validation no longer claims no value is read; `extractData` overloads document
  only the callbacks they accept; `beginTransaction` documents the missing-tracker
  `IllegalStateException`; stale cross-references in the Spring-completion comments corrected.
- `NamedQuery`: `Timestamp` range check boundary corrected to JDK 23+; duplicate listed stream/reader
  names documented as read once.
- `AbstractQuery`: asynchronous cancellation and rejected-submission cleanup are documented as
  honoring `closeAfterExecution(false)`.
- `ResultSetProxy.getObject(String)`: a null label is not documented as an `SQLException` cause.
- `TransactionContext`: participating-scope completion rules for deferred queries and the class
  summary corrected.
- `SqlTransaction`: non-existent `configDao.gett` example corrected; status diagram distinguishes a
  participating `FAILED_COMMIT`; class introduction qualifies connection ownership for Spring.
- `CrudInsertOps`: copy-pasted "named SQL parameters" exception removed from the overloads that take
  no SQL.
- `JoinEntityDeleteOps`/`UncheckedJoinEntityDeleteOps`: the executor overloads no longer document an
  `IllegalStateException` merely for running inside a transaction.
- `CacheResult`, `NonDBOperation`, `DBLock`, `JdbcCodeGenerationUtil`: contract wording aligned with
  the implementation.

## Reviewed and deliberately not changed

- JDBC value objects (`SQLXML`/`Array`/`Ref`/`RowId`/`Struct`) bound as JSON via `setObject`: commit
  `5bff908a` fixed this and commit `7c71dda2` explicitly reverted the fix and its tests. It was not
  re-applied without a design decision; use the dedicated setters or the named-parameter overloads.
- `UncheckedDao.upsert`'s no-ID branch merges leniently while its `@throws` says an unmatched property
  is rejected. Changing merge strictness alters base-entity upserts, so it is left for review.
- `NamedQuery` single-placeholder nested parameter paths (`:address.street`) for beans whose Abacus
  `Type.isBean()` is false (custom registered Type) bind the whole value. A targeted fix needs a
  decision on type-overridden beans.
- `JdbcCodeGenerationUtil` import pruning for an `@Id` inside `additionalClassBodySource`, and for a
  default annotation import requested through `classNamesToImport` while a custom annotation is
  configured.
- Spring connection-acquisition failures are documented as `UncheckedSQLException` by several
  `@throws` blocks (join reads, `UpdateOps`); on the Spring path they surface as
  `CannotGetJdbcConnectionException`, as `JdbcUtil.getConnection` documents.

## Follow-up review corrections

- Nullable-key single and batch upserts preserve the condition/match properties that selected the row, including when
  several rows share a nullable ID. The update excludes ID columns and remains a no-op for ID-only entities.
- Ordinary upserts keep the by-ID update, preserving generated identities and custom ID database types.
- INSERT tests cover named/positional scalar parameters, optional returns, chunked batches, comments, quoted/qualified
  identifiers, runtime table fragments, and a different schema/table with a differently named ID. Leading CTE target
  resolution is tested separately because H2 does not accept that INSERT shape.
- Cached SELECTs with write-like names remain cacheable; type-level caching still excludes actual INSERTs.
- The column-conversion mocks now supply `findColumn`; separate cases exercise its metadata fallback and alias
  collisions for both Oracle DATE and `java.sql.Date` values.
- Abstract-supertype `@MergedById` tests cover lists, optionals, empty results, duplicate-row merging and unique-result
  rejection. The transaction example uses `getOrNull` to return the entity rather than an `Optional`.

Follow-up verification: offline Maven test execution on JDK 21 completed with **769 tests, zero failures, zero errors,
zero skipped**. The selection included both new regression classes, the full `DaoImplIntegrationTest`, column conversion,
CRUD/unchecked CRUD, DAO parameter binding, batching, transactions, query contracts/validation, and cache transaction/filter
tests. `javadoc -Xdoclint:all` over every edited public source exited 0 (two inherited-method accessibility warnings).
`git diff --check` is clean. The compact build log is `target/review-fixes-summary.log`.

## Additional generated-key and documentation corrections

- Confirmed the remaining cross-table failure with composite-ID DAOs: scalar, optional, and batch calls wrapped the
  other table's key as the DAO's `EntityId` and then failed return conversion after inserting the rows.
- Custom INSERTs without entity arguments now choose key extraction and default-key detection from the same resolved
  destination used when requesting generated-key columns. Inserts into the DAO's own table preserve its typed/custom
  extractor; another table's first generated-key column is read independently and converted to the declared return type.
  Conversion happens while reading a batch's keys, before its multi-row transaction commits.
- Added 17 regression cases (22 total in `DaoImplGeneratedKeyRegressionTest`) covering composite `EntityId`/record IDs,
  scalar/optional/narrower returns, named/positional singleton and chunked batches, empty batches, Object/interface
  arguments, absent/zero/text keys, runtime destination changes, custom entity extractors, and rollback on conversion failure.
- Both `runAsync` overloads now explicitly qualify submission-failure cleanup with `closeAfterExecution(false)`.
  `SqlTransaction` distinguishes Spring-owned connection completion, and `batchUpsert` documents individual updates
  for nullable-ID rows. `@Query` and `CrudInsertOps.idExtractor` describe the cross-table key contract.

Verification: offline JDK 21 Maven run passed **719 tests, zero failures, zero errors, zero skipped**, covering the new
key tests, `DaoImplTest`, full DAO integration, batching, transactions, parameter binding, and query contracts/validation.
`javadoc -Xdoclint:all` passed with the same two inherited-method accessibility warnings. `git diff --check` passed.
The compact build log is `target/review-key-summary.log`.

## Ambiguous-label metadata fallback

- When `findColumn` fails during date conversion, the private metadata fallback now searches every label before
  considering column names. An earlier column name can no longer shadow the requested alias and replace the original
  value with another column's date/timestamp. The public `getColumnIndex` search order is unchanged.
- Expanded coverage combines ambiguous labels with unsupported `findColumn` for Oracle `DATE` and `java.sql.Date`,
  including mixed-case labels. Additional tests cover name-only lookup with null labels, duplicate labels, and metadata
  exception propagation. Existing missing-label tests continue to cover retaining the original value.
- Before the production fix, four combined-scenario cases failed with the wrong column's timestamp. After the fix,
  **266 tests passed, zero failures/errors/skips**, covering all 36 column-conversion cases, JDBC metadata/utilities,
  and ResultSetProxy object mapping/column access. Javadoc validation passed with the same two existing accessibility
  warnings, and `git diff --check` passed. The compact build log is `target/review-label-summary.log`.

## Verification environment

The initial verification could not run Mockito's inline mock maker through self-attachment in the sandbox.
Follow-up runs load `mockito-core-5.24.0.jar` with `-javaagent` at JVM startup, so those tests execute normally.
The JVM heap is capped at 512 MB and active processor count at four for the review runs.
