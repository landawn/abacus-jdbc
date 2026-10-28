# Whole-tree line-by-line review — 2026-10-26

Scope: every class under `src/main/java` (101 files, ~115,500 lines) at `5c19c13d` (tag `review-1026-base`), reviewed by
28 parallel agents in isolated git worktrees (large files split into line windows: JdbcUtil ×4, DaoImpl ×5,
AbstractQuery ×3, Jdbc ×3, DataTransferUtil ×2). Each agent read its whole window, traced callers/callees and the
abacus-common 8.3.1 / abacus-query 4.9.6 sources, fixed only defects it could prove with a concrete trace, and added a
regression test that fails before the fix. Every patch was read before it was merged. Items that are design decisions
or change a documented contract were reported, not changed. `SqlExecutor` is an archived, commented-out placeholder
(class Javadoc only).

## Code fixes (each with a fail-before regression test)

| Area | Defect | Test |
| --- | --- | --- |
| `JdbcUtil.getAllColumnValues(rs, label)` | Resolved the label with `getColumnIndex` (per-column label-then-name), so an earlier column's *name* shadowed a later column's *label* (`SELECT b AS x, a AS b` read `x` for `"b"`). Now uses `findColumn`, then labels before names. | `JdbcUtilIntegrationTest#testGetAllColumnValues_ByLabel_ReadsTheColumnGetObjectReads` |
| `JdbcUtil.stream(rs, label)` | Same shadowing. | `JdbcUtilStreamTest#testStreamWithColumnLabel_EarlierColumnNameDoesNotShadowTheLabel`, `#testStreamWithColumnLabel_FindColumnUnsupported_MatchesLabelsBeforeNames` |
| `JdbcUtil.executeQuery/executeUpdate/execute` | Documented `IllegalArgumentException` for unbindable SQL/parameters surfaced only after a connection was acquired / statement prepared, so a failing data source or closed connection was reported instead. SQL is parsed and parameters checked first (shared with `checkBatchParameters`). | `JdbcUtilTest#testExecuteFamilyRejectsUnbindableArgumentsBeforeAcquiringConnection` |
| `AbstractQuery.stream(Class)`, `list(Class)`, `list(Class, int)`, `CallableQuery.listAndGetOutParameters(Class)` | `BiRowMapper.to` resolves bean metadata eagerly outside any cleanup: an invalid bean class threw and left the statement (and a data-source query's connection) open. New `rowMapperOrClose` helper. | `AbstractQueryStreamTest#testStream_InvalidBeanTargetType_ClosesQuery`, `AbstractQueryResultTest#testList_InvalidBeanTargetType_ClosesQuery`, `CallableQueryResultTest#testListAndGetOutParameters_ByInvalidBeanClass_ClosesQuery` |
| `NamedQuery.setParameters(entity, names)` | Same leak: bean metadata resolved before the cleanup block. | `NamedQueryReviewFixTest#selectiveBeanBindingClosesQueryWhenBeanMetadataIsInvalid` |
| `NamedQuery.addBatchParameters` | An `Object[]` row after a record/immutable-bean row was bound silently by component order instead of the documented `ClassCastException`. | `NamedQueryParameterBindingTest#addBatchParameters_arrayRowAfterRecordRowIsRejected`, `#addBatchParameters_beanRowsOfSiblingSubclassesAreAccepted` |
| `CrudReadOps.batchGet/count(ids)/batchRefresh`, composite `refresh` | ID values were bound by their runtime class, not the ID property's `@Type` (enum stored as ordinal: data conversion error / no match), unlike `getOrNull`/`exists`. `DaoImpl`'s private `TypedParameter` moved to `DaoUtil.TypedParameter` (`@Internal`), the DAO's condition parameter setter binds it with its type. | `DaoImplParameterBindingIntegrationTest#idBatchReads_bindIdsWithPropertyTypes` |
| Upsert family (follow-up of an external review) | The lookups of `upsert(entity)`/`upsert(entity, matchPropNames)` (`Dao`, `UncheckedDao`, `UncheckedCrudDao`; `CrudDao` inherits `Dao`'s), the ID predicate of `Dao`/`UncheckedDao.upsert(entity, cond)`'s update, and `batchUpsert`'s composite matching (`Filters.idToCond`) and NULL-ID update used `Filters.allEqual`/`idToCond`. These bind by runtime class, so an ordinal-enum ID failed to convert, and MySQL would compare `level = 'MID'` as `level = 0`. New `DaoUtil.typedAllEqual`/`typedAnyOfAllEqual` keep a `null` unwrapped so it still renders `IS NULL`. `DaoImpl`'s `update(entity, propNames, cond)` binds typed WHERE values. | `DaoImplParameterBindingIntegrationTest#upsertFamily_bindsMatchAndIdValuesWithPropertyTypes`, `#upsertFamily_typedMatchKeepsIsNullForNullValues` (a mutation that wraps `null` fails it and `DaoImplUpsertRegressionTest#idOnlyNullableUpsertsRemainNoOps`) |
| `JdbcUtil.isEntityOrMapParameter` (second external review) | One named placeholder that is a nested path (`:address.street`) on a bean whose class has a registered single-value `Type` bound the whole bean ("10 USD"). Only a direct property was checked; `NamedQuery` also checks nested paths. Affects single and batch statements. | `JdbcUtilParameterBindingTest#testSingleNestedPlaceholderOfCustomTypeBeanBindsTheLeaf` |
| `DaoUtil.typedAllEqual` / `upsert(entity, matchPropNames)` (second external review) | A nested match path was read through the nested bean but rendered as a qualified column (`Column "ADDRESS.STREET" not found`). The behavior predates this round, but the new Javadoc promised support. Now rejected with IAE before any query, as `batchUpsert` already did. | `DaoUtilTest#testTypedAllEqual_RejectsNestedPropertyPath` |
| Codegen import precedence (second external review) | A requested `javax.persistence` `Table`/`Column`/`Id` import survived an Abacus or Jakarta selection, so the code had two imports of the same simple name and did not compile. New `removeCompetingBuiltInImports` covers all three built-in families. | `JdbcCodeGenerationEntityIntegrationTest#selectedBuiltInAnnotationsTakePrecedenceOverRequestedImports` (now all 6 selected/requested pairs) |
| `CrudDao.update(entity, propNames)` / `batchUpdate(entities, propNames, …)` | With an ID property selected, abacus-query 4.9.6 renames the repeated WHERE parameter (`id = :id_2`), which no property matched: every such call failed. | `DaoImplBatchOperationsIntegrationTest#updateById_selectedPropertiesIncludingTheId_bindsTheIdOfTheWhereClause`, `DaoImplParameterBindingIntegrationTest#updateSelectedProperties_includingACompositeIdPart_bindsTheWhereIdWithPropertyTypes` |
| `Dao.upsert(entity, cond)` / `UncheckedDao.upsert(entity, cond)` | Plain-DAO twin of last round's `CrudDao` fix: ID columns were rewritten (identity column → "cannot be assigned"), and a NULL ID component updated every row sharing it via `IS NULL`. | 2 parameterized tests in `DaoImplUpsertRegressionTest` |
| `NonUpdateCrudDao.insert/batchInsert(namedInsertSql, …)` (+ Unchecked) | Prepared through `JdbcUtil`, bypassing the proxy's SELECT/INSERT gate: a non-update DAO executed `UPDATE`/`DELETE`. | `CapabilityGatingTest#testNonUpdateCrudDao_InsertWithSqlGate` |
| `DaoImpl` createDao | NPE for a CRUD entity whose `@Id` is a public field without a getter; a nested interface the DAO also extends had its `@SqlScript`/`Handler` fields rejected as duplicates. | `DaoImplIntegrationTest#testCreateDao_FieldOnlyIdPropertyIsCheckedAndUsable`, `DaoImplTest#testCreateDao_NestedInterfaceAlsoExtendedKeepsItsSqlScriptAndHandlerFields` |
| `DaoImpl.sameTable` (custom `@Query` INSERT destination) | On databases that match identifiers case-insensitively but store them as written (SQL Server, SQLite), another case spelling of the DAO table was treated as another table (no ID write-back; composite-ID "ambiguous keys" after the insert). A `null` `getColumns` result threw NPE. | `DaoImplGeneratedKeyRegressionTest#caseInsensitiveSpellingsOfTheDaoTableKeepTheEntityIdStrategy`, new `"null"` mode of `#missingGenerationMetadataForAKnownPrimaryKeyFailsBeforeWriting` |
| `DaoImpl.isUsableJsonElementType` (JSON cache copy) | Picked the runtime class of an untyped element even when the parser cannot construct it (`Collections.emptyList()`'s private class, anonymous classes): the DAO call failed after the query succeeded. | `DaoCacheJsonOuterContainersTest#untypedRootsRebuildUniformContainersTheParserCannotConstructFromTheirRuntimeShape` |
| `JoinInfo` many-to-many | A `@JoinedBy` property in another spelling (`ROLEID`) rendered non-existent columns in every select/delete plan. | `JoinInfoTest#testManyToManyPlans_NonCanonicalPropertySpellingRendersCanonicalColumns` |
| `JdbcCodeGenerationUtil.generate*Sql` | Excluded camelCase names were converted again (non-idempotent: `posXY` → `posXy`), so the column stayed in. | `JdbcCodeGenerationSqlTest#testExcludedColumnsMatchCamelCaseFormThatIsNotStableUnderConversion` |
| `TransactionContext.callOutsideTransaction` | The leak `IllegalStateException` always said "has been rolled back", also when that rollback (or the Spring mark) failed. | `SqlTransactionTest#testRunOutsideTransactionDoesNotClaimTheRollbackOfTheTransactionItsCmdLeftOpenWhenThatRollbackFails` |
| `DaoUtil.idsToCondition` | `null` id property list → NPE instead of IAE (validated after the single-id shortcut). | `DaoUtilTest#testIdsToCondition_NullIdPropNameListRejectedBeforeSingleIdShortcut` |

## Javadoc

Roughly 200 corrections. These cover:

- wrong `@throws`/`@param`/`@return` text;
- examples that would not compile or used the wrong overload;
- statements that contradicted the code. Examples: the stateful-setter wording of `forArray`/`forList`, the `GET_OBJECT` conversions, the `OutParam` example that mixed names and indexes, the `configureStatement` restore semantics, the `largeBatchUpdate` fallback, and the swapped `resourceRecordingCompletion`/`hasCompleted` docs;
- the Spring `CannotGetJdbcConnectionException` paths in the DAO interfaces;
- composite-ID argument rules;
- the annotation docs for `@Bind`, `@Cache`, `@MergedById` and `@Query`.

## Reported, not fixed (owner decisions)

MED
- `DaoImpl.insertTargetTable`: `INSERT tbl ...` without `INTO` (MySQL/SQL Server) gives a null destination, so an insert into the DAO's own table loses the ID write-back. Returning null is a documented decision of the previous round.
- `JdbcCodeGenerationUtil` / `DataTransferUtil`: MySQL/MariaDB with `lower_case_table_names=1/2` reports lower-case storage, so mixed-case labels (`userId`) are rejected although MySQL resolves them. Needs a dialect decision.
- `Jdbc.BiRowMapperBuilder` bean targets don't resolve dotted labels (`user.id`), unlike `BiRowMapper.to(Class)`. This was already an owner decision (09-22); it is now documented on the builder.

LOW / DOC
- `AbstractQuery.query(Class)`/`queryThenApply(Class, …)`/`queryThenAccept(Class, …)`: a non-bean class gives IAE without closing the query. Tests pin this behaviour.
- `setLargeMaxRows` reads the original value with `getLargeMaxRows()`, whose JDK default returns 0.
- `getGeneratedKeys()` returning `null` from a non-compliant driver causes an NPE.
- Custom `@Query` INSERT returning `u.Optional<ID>` is empty when the driver returns no key, while an `ID` return falls back to the entity's ID.
- DAO cache key (`JdbcUtil.createCacheKey`) has no parameter types. Without Kryo, same-name/arity overloads with equal JSON arguments share an entry.
- `@CacheResult(enabled = true)` on a `@NonDBOperation` method creates a `DefaultDaoCache` for a CrudDao.
- A negative `@Query(batchSize)` is not rejected at DAO creation.
- SQL fragments are not re-checked against the read-only/non-update gate.
- `hasTopLevelLimitClause` mistakes an unquoted column named `offset` for a LIMIT clause.
- `List<Bean> getXxx()` is treated as a single-row lookup.
- The procedure `queryAll` element-type check is inverted.
- `limitRequiresOrderBy` comes from the `Dsl` dialect, not the actual database.
- `createDao` opens a connection only for a debug log.
- A raw `JoinEntityHelper` gets the wrong IAE message.
- Single-ID `batchGet`/`count(ids)` match a `null` ID with `IS NULL`. This branch was added deliberately in f9bf2eac.
- Multi-name collection overloads of `loadJoinEntities`/`deleteJoinEntities` return early for empty entities before validating names. Documented as "returns immediately".
- Parallel join loads inside a transaction continue after a failure.
- `JoinInfo` gives source entities with equal join keys the same mutable `List`.
- A null component in a multi-column join key matches in memory but not in SQL.
- `executeLargeBatchUpdate` falling back after `UnsupportedOperationException` inside a nested scope marks the outer transaction rollback-only.
- `dropTableIfExists`:
  - On PostgreSQL in a transaction, a concurrent drop leaves the transaction aborted.
  - The SQL Server 3701 message also covers a missing permission.
- `CallableQuery`:
  - An empty OUT parameter name is accepted.
  - `addOrReplaceOutParam` matches names case-sensitively.
- `OutParam.of` uses 0 where `CallableQuery` uses -1, so equal registrations are unequal descriptors.
- `DaoCacheByMap` is a record, so equality is by content.
- `RollbackActionLog.IdSnapshot.run` stops restoring IDs after one entity's getter/setter throws.
- `DataTransferUtil`:
  - A target identifier-case metadata failure surfaces as a checked `SQLException`.
  - `batchInterval` below 1 ms rounds to no pause.
  - The private `CsvRecordReader` reads one character per call.
- Named procedure calls don't reject duplicate `@Bind` names.
- `@RefreshCache` default prefixes also match read methods such as `addressesOf`.
- Five `cs` constants are unused (pinned by `csTest`).
- `ResultSetProxy` fetches metadata once per column for non-date objects.
- `NamedQuery` and `AbstractQuery` disagree on the JDK version from which `Timestamp.from` throws (23 vs 25).
- Checked `ReadOps`/`CrudReadOps` (~99 `@throws` sites) don't yet mention the Spring `CannotGetJdbcConnectionException` path. The other interfaces were updated.

## Verification

- Full suite, offline on JDK 25, run in an isolated copy (Eclipse writes into the checkout's `target/`): `mvn -o -Djacoco.skip=true clean test` gave **7,056 tests, 0 failures, 0 errors, 36 skipped** (after both external-review follow-ups).
- `mvn -o javadoc:javadoc` (`doclint=all`): 0 warnings, 0 errors.
- `git diff --check` is clean. Every touched source keeps the checkout's LF line endings.
- Fail-before:
  - Every new regression test failed on the old code. The agents checked this by reverting their fix in their worktree.
  - For the coordinator's own follow-ups (the `rowMapperOrClose` sites, `NamedQuery.setParameters(entity, names)`, the typed ID binding), I checked it by reverting in the isolated copy.
