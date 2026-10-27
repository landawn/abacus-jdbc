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

## Resolution of earlier deferrals

- JDBC value objects (`SQLXML`/`Array`/`Ref`/`RowId`/`Struct`) are handled by `abacus-common` 8.3.1;
  public query regression tests now protect the specialized setters and H2 SQLXML content round trip.
- `UncheckedDao.upsert` retains its lenient no-ID merge; the Javadocs and a subtype-property regression test
  now describe that behavior rather than promising an exception.
- Explicit default annotation imports survive custom annotation selection. An annotation used only in
  additional source still requires `classNamesToImport` or a fully qualified name, as clarified in the Javadocs.
- Update/join-read exception documentation now distinguishes direct/translated SQL failures from untranslated
  Spring `CannotGetJdbcConnectionException`; checked and unchecked acquisition tests cover the distinction.

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

## Custom-Type beans with a single nested placeholder

- `NamedQuery` now classifies a custom-Type bean as a property source when its single placeholder resolves to a
  nested path, consistently with multi-placeholder queries. Classification checks metadata without invoking getters.
  Direct/mapped properties retain precedence, and unresolved names still bind the whole object through its Type.
- Added 12 regression cases covering single and batch binding, String and primitive leaves, null intermediate
  values, leading/interior null batch rows, unresolved paths and collection traversal, mapped-property precedence,
  getter evaluation counts, and explicit whole-object `setObject` binding. Six cases failed before the production fix.
- Updated the class and binding-method Javadocs and changelog with the precedence rule and compatibility guidance:
  callers requiring whole-object binding despite a resolvable nested name can use `setObject` explicitly.

Verification: offline JDK 21 Maven run passed **716 tests, zero failures, zero errors, zero skipped**, covering all
`NamedQuery*Test` classes, `JdbcUtilParameterBindingTest`, and `DaoImplParameterBindingIntegrationTest`.
`javadoc -Xdoclint:all` passed for `NamedQuery`; the sandbox's Java output-directory permission check required running
that validation outside the sandbox. `git diff --check` passed for the four files changed in this follow-up.
The compact build log is `target/review-nested-summary.log`.

## Verification environment

The initial verification could not run Mockito's inline mock maker through self-attachment in the sandbox.
Follow-up runs load `mockito-core-5.24.0.jar` with `-javaagent` at JVM startup, so those tests execute normally.
The JVM heap is capped at 512 MB and active processor count at four for the review runs.

## 2026-10-26 recheck

### Reported issues

| # | Reported issue | Recheck status |
| --- | --- | --- |
| 1 | Nullable-key `CrudDao.upsert`/`batchUpsert` did not update the row | **Fixed.** The rewrite reuses the original match condition and excludes ID assignments. `DaoImplUpsertRegressionTest` 8/8 and the integration test pass. |
| 2 | Custom `@Query` INSERT requested the DAO entity's ID column names for another table | **Fixed, including A/B below.** Destination-based key selection and ID write-back; `DaoImplGeneratedKeyRegressionTest` 30/30. |
| 3 | `@CacheResult` could serve/store a write | **Fixed.** `cacheResultEnabled` excludes `isUpdateMethod`; the custom-INSERT cache test passes. |
| 4 | Label-based date conversion could read a shadowing column | **Fixed.** `findColumn` plus a label-first metadata fallback; `JdbcUtilColumnConversionTest` 8/8. |
| 5 | `@MappedByKey`/`@MergedById` mapped into a declared supertype | **Fixed.** Both paths use the entity class; integration tests pass. |
| 6 | JDBC value objects bound as JSON through `setObject` | **Fixed by `abacus-common` 8.3.1 and covered here.** Six regression cases exercise prepared, named, and callable positional binding and H2 SQLXML content. |
| 7 | Single-placeholder nested path on a custom-Type bean bound the whole bean | **Fixed in the working tree** (uncommitted). One-placeholder probe now binds `Main`; the earlier 716-test selection and the final selection below pass. |
| 8 | Codegen import pruning for additional-body `@Id` and requested default imports | **Resolved.** Requested defaults are preserved with fully qualified custom annotations, with compile tests for Abacus and Jakarta annotations. Additional-body `@Id` needs an explicit import by contract; the documented example and existing compile test cover it. |
| 9 | `UncheckedDao.upsert` no-ID lenient merge versus documented `IllegalArgumentException` | **Resolved.** Preserve leniency, document it, and test unmapped subtype properties and null-value retention. |
| 10 | Spring acquisition failures documented as `UncheckedSQLException` | **Resolved.** Update/join-read throws clauses now name untranslated Spring failures. Tests cover checked propagation and unchecked translation. |

### Regressions found by recheck (fixed in the follow-up below)

- **A. Multi-generated-column other table returns the wrong key.** For `other_multi(created TIMESTAMP DEFAULT CURRENT_TIMESTAMP, log_id IDENTITY, name)`, H2 returns `CREATED` then `LOG_ID` from `getGeneratedKeys()`. `CustomInsertKeys` reads column 1, so a custom INSERT returning `long` returned `1793001248617` (the timestamp) while the actual `log_id` was `1` — silently wrong, no exception. This is new behavior: the previous code failed on the entity ID column name instead of returning a wrong key.
- **B. Entity argument inserted into another table overwrites the entity ID.** For `@Query("INSERT INTO audit_log(name) VALUES(:name)") void copy(ProbeRow row)`, `isEntity` is true because the argument is the DAO entity, so `CustomInsertKeys` is not used and the other table's generated key is written back through `idSetter`. Probe: input `row.id=999`, actual `audit_log.log_id=1`, after the call `row.id=1`. Silent entity corruption; the previous code failed on the missing entity ID column.

### Completed follow-up

- Select another table's single primary-key column through JDBC metadata before executing. Preserve identifier case,
  schema/catalog qualification, and runtime fragments. Reject composite primary keys before writing. If metadata cannot
  identify a key, accept only one driver-generated column; ambiguous results fail instead of converting a timestamp to an ID.
  This fallback check is necessarily after execution: singleton autocommit may already have stored the row, while multi-row
  batches roll back. The Javadocs explain explicit generated-key column selection for such cases.
- Use the resolved destination for custom/entity ID extraction, default-key checks, and ID write-back, including singleton
  and chunked batches, void calls, custom extractors, composite-ID entities, and enclosing transaction rollback.
- Preserve requested default annotation imports with custom annotations and compile the generated source for both
  Abacus and Jakarta imports. Keep the documented explicit-import requirement for annotations used only in additional source.
- Add regression coverage for JDBC value objects, lenient no-ID upserts, and Spring acquisition exception propagation.

Verification: **1,332 tests passed, zero failures, zero errors, zero skipped**, including 20 new cases. The run covers full
DAO integration, batch/transaction integration, parameter binding, all code-generation test classes, unchecked acquisition
and upserts, and the value-object regressions. `javadoc -Xdoclint:all -package` exits 0 for every edited main source, with
seven existing missing-tag warnings on `JdbcCodeGenerationUtil.startsArrayExpression`. Scoped `git diff --check` passes.
The compact test log is `target/review-all-followup-summary.log`; detailed test and Javadoc logs use the same prefix.

## 2026-10-26 second recheck

This records the findings before the final follow-up below; its remaining generated-key edge and documentation gaps are now resolved.

The follow-up above resolves the two recheck regressions:

- **A fixed.** `customInsertKeyColumnNames` resolves the destination's single primary-key column before execution; the `created`-before-`log_id` table now returns `log_id` (probe: returned key `1`, actual `log_id` `1`). The 30 generated-key tests pass.
- **B fixed.** ID write-back is gated on `generatedKeys.entityTable`. An entity argument inserting `audit_log` retains `row.id=999` while the destination key is `1`; covered by `crossTableEntityArgumentsNeverReceiveTheDestinationId` and batch variants.

Reported-issue status:

| # | Issue | Final status |
| --- | --- | --- |
| 1-7 | Nullable upsert, custom-insert keys, cache-write guard, label conversion, mapped supertype, JDBC value objects, NamedQuery nested path | **Fixed** (value objects fixed by `abacus-common` 8.3.1 plus the new `JdbcValueObjectBindingRegressionTest`; NamedQuery remains uncommitted in the working tree). |
| 8 | Codegen imports | **Resolved under the explicit-import contract.** Requested default imports survive fully qualified custom annotations and compile. Automatic annotation importing from `additionalClassBodySource` is unsupported by design; list `@Id` in `classNamesToImport` or fully qualify it. |
| 9 | `UncheckedDao` no-ID upsert docs | **Fixed for `UncheckedDao`.** Lenient no-ID merge is now documented and tested. The checked `Dao`/`CrudDao` `@throws` text still describes the old strict behavior for the no-ID branch. |
| 10 | Spring acquisition docs | **Fixed for the reported join/update interfaces.** `CrudDao`, `CrudDeleteOps` and `CrudInsertOps` still carry the older unqualified acquisition wording. |

Remaining edge (not introduced by this round): another table whose generated key is **not** its primary key. If the PK is a supplied business key and a separate identity is generated, `customInsertKeyColumnNames` requests the PK; for a `long` return the supplied value later fails conversion **after the row was inserted** (`NumberFormatException: c is not a valid Long`), and a `String` return would return the supplied PK value instead of the generated identity. The generated-key tests cover an identity PK, a no-PK single generated column, composite-PK rejection and multi-generated ambiguity, but not this shape. A composite PK is rejected before writing by design and is covered.

Verification this round: compile green; `DaoImplGeneratedKeyRegressionTest` 30/30, `DaoImplUpsertRegressionTest` 8/8; `DaoImplIntegrationTest` 117/119 in this sandbox (2 known Mockito self-attach errors) and 119/119 in the recorded full run; `JdbcUtilColumnConversionTest` 8/36 here (28 Mockito environment errors) and 36/36 in the full run; the recorded full selection is **1,332/1,332**. Probes confirm A/B, JDBC value objects, NamedQuery nested binding, and codegen requested-import behavior; codegen additional-body `@Id` remains `import=false` without `classNamesToImport`.

## 2026-10-26 supplied-primary-key follow-up

- Resolve a known single primary key's generation status before executing another-table INSERTs that return a key.
  If that PK is not reported as generated, select a unique identity, or a unique generated column when there are no
  identities. A supplied business key no longer replaces the separately generated value, for numeric, String,
  optional, singleton-batch, and chunked-batch returns. Generated PKs keep precedence over other generated columns.
- Escape metadata patterns and filter exact catalog/schema/table identifiers. Unknown or unsupported generation
  metadata for a known PK, no provable generated column, and multiple candidate columns fail before writing.
  A default alone does not prove a column is generated. Use explicit generated-key columns when needed; a plain
  supplied PK is no longer returned automatically. The existing no-PK/unsupported-PK fallback and its post-execution
  ambiguity check remain documented. Composite-PK rejection remains intentional and tested.
- Added 13 generated-key regression cases (43 total), including qualified/quoted names, wildcard-like names,
  computed columns, unknown/unsupported metadata, and zero inserted rows on rejected selection. Updated the unrelated
  bean integration test's fixture to use a BY DEFAULT identity, retaining its explicit-ID and no-write-back assertions;
  rejecting a plain supplied PK is covered separately.
- Corrected both checked `Dao.upsert` overloads to describe lenient no-ID merging and added four subtype/null-value
  regression cases. `CrudDao` requires ID properties at DAO creation; its strict merge documentation now accurately
  refers to unmatched non-ID properties rather than claiming support for a no-ID CRUD entity.
- Corrected Spring acquisition wording throughout `Dao`, `CrudDao`, `CrudInsertOps`, and `CrudDeleteOps`. Expanded the
  checked propagation test to cover insert, batch insert, delete, upsert, and batch upsert, both when acquisition throws
  and when a data source returns null. Existing unchecked translation coverage remains green.

Verification: **935 tests passed, zero failures, zero errors, zero skipped**, covering the focused regressions, full DAO
integration, batching, transactions, parameter binding, query contracts, checked/unchecked DAO behavior, and JDBC
metadata. The focused 189-test run also passed. `javadoc -Xdoclint:all -package` exits 0 for all seven main-source files
edited in this follow-up, with 10 pre-existing missing-tag/inherited-link warnings. Scoped `git diff --check` passes.
The compact test log is `target/review-supplied-pk-summary.log`; detailed test and Javadoc logs use the same prefix.

## 2026-10-26 identifier spelling and import precedence

- Fixed the review finding where a case-insensitive INSERT used a different table-name spelling from the stored name.
  Primary-key metadata resolved the table, but the subsequent column-pattern lookup reused the SQL spelling and failed.
  Carry the catalog/schema/table identifiers returned by primary-key metadata into that lookup, retaining supplied
  qualifiers when the driver omits them. Keep wildcard escaping and exact filtering to distinguish quoted tables.
- Added eight cases covering unqualified and schema-qualified names, wildcard-like names, generated PKs and supplied
  PKs with separate identities, numeric/String/optional results, and singleton/chunked batches. All eight reproduced
  the metadata error before the fix. A ninth case verifies that case-sensitive quoted tables keep distinct keys.
- Added three compile tests for built-in Abacus/Jakarta/Javax annotation precedence over competing requested imports,
  with fully qualified competing annotations in the additional body. Clarified that removal applies to emitted
  built-ins. Issue #8 is resolved under the explicit-import contract; automatic annotation importing is not part of it.

Verification: **1,241 tests passed, zero failures, zero errors, zero skipped**, including full DAO integration,
batch/transaction integration, binding, checked/unchecked DAO behavior, metadata, and all code-generation tests.
The focused run passed 70 tests (52 generated-key cases and 18 code-generation integration cases).
`javadoc -Xdoclint:all -package` exits 0 for all three main-source files edited in this follow-up, with seven existing
missing-tag warnings. Scoped `git diff --check` passes. Logs are `target/review-identifier-summary.log`,
`target/review-identifier-tests.log`, and `target/review-identifier-javadoc.log`.

## 2026-10-26 composite-ID fixture and unavailable metadata

- Reproduced `DaoImplTest.testCompositeBeanIdWithSingleUnlabeledGeneratedKeyReturnsTheFullId` failing in the custom
  INSERT path. Its SQL named `TenantRow`, while PSC derives `tenant_row`; the mismatched destination took the other-table
  path and encountered an unstubbed null metadata result. Aligned both custom INSERTs with `tenant_row`/`tenant_id` and
  added explicit assertions that scalar and batch calls write the generated component back and retain tenant IDs.
- Defensively treat a null `getPrimaryKeys` result as unavailable metadata. JDBC specifies an empty ResultSet, but
  non-conforming wrappers can return null. Four new cases cover empty/null results, SQLFeatureNotSupportedException,
  and AbstractMethodError, including scalar/singleton/chunked batch keys, entity-ID preservation, ambiguous-key rejection,
  and rollback of ambiguous multi-row batches. The null-result case failed before the guard; the corrected composite-ID
  fixture already passed without the guard, proving that the destination mismatch was independently resolved.
- Kept `getPrimaryKeys` schema/table arguments literal, as JDBC specifies. Two real-H2 cases cover `S_1` and `S%1`
  alongside the decoy schema `SX1`, checking numeric/String/batch returns and leaving the decoy untouched. Only the
  `getColumns` lookup receives escaped patterns. Documented this distinction beside the metadata call.

Verification: the configured full `mvn -o -Djacoco.skip=true test` suite is green: **7,014 tests, zero failures,
zero errors, 39 skipped** (6,975 passed). This includes all of `DaoImplTest` and all 58 generated-key regression cases.
JDK 21 and the Mockito startup javaagent were used as above. `javadoc -Xdoclint:all -package` exits 0 without warnings
for `DaoImpl`; scoped `git diff --check` passes. Logs are `target/review-metadata-full-summary.log`,
`target/review-metadata-full-tests.log`, and `target/review-metadata-javadoc.log`.
