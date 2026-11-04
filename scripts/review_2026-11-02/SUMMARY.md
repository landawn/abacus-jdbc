# Whole-tree line-by-line review — 2026-11-02

Scope: every class under `src/main/java` (101 files, about 117,600 lines) at `0b3309e5` (tag `review-1102-base`).
36 agents did the review, each in its own git worktree. Large files were split into line windows:

| File | Windows |
| --- | --- |
| `JdbcUtil` | 5 |
| `DaoImpl` | 5 |
| `AbstractQuery` | 4 |
| `Jdbc` | 3 |
| `DataTransferUtil`, `JdbcCodeGenerationUtil`, `NamedQuery` | 2 each |
| `CallableQuery`, `ResultSetProxy` | 1 each |

The DAO interfaces (6 groups), transactions, Spring/`DBLock`, annotations and the small classes were reviewed as groups.

The review covered bugs, Javadoc, performance and memory:

- Each agent read its whole scope and traced the callers, the callees and the abacus-common 8.3.2 / abacus-query 4.9.7
  sources.
- Agents fixed only defects they could prove with a concrete trace. Each behaviour fix has a regression test that failed
  before the fix; each agent checked this by reverting its fix in its worktree.
- I read every patch before merging it, and forwarded cross-scope findings to the agent owning the code.
- Design questions and changes to documented contracts were reported, not made.

## Code fixes (each with a fail-before regression test)

### Undeclared checked exceptions in cleanup paths (one family: 7 agents and the coordinator)

A callback written in Kotlin or using a sneaky throw (`@SneakyThrows`) can throw a checked exception it does not declare:
a parameter setter, a custom `Type.set`, a getter called through reflectasm (abacus-common's ASM `PropInfo.getPropValue`
does not wrap it), a row iterator, a caller's stream/reader, an extractor, an executor. The 10-31 round fixed this for
`CloseHandlers.run`/`closeSuppressingFailure`; the remaining cleanup catches were still
`catch (SQLException | RuntimeException | Error e)`. Such an exception then either skipped the cleanup (the statement,
and a data-source query's connection, leaked) or was taken for a success, so a cleanup failure (a Connector/J streaming
drain, a failing close handler) replaced it. Every site now catches `Throwable` (precise rethrow keeps the signatures).

| Area | Sites | Effect before | Test |
| --- | --- | --- | --- |
| `JdbcUtil.prepareStmt`/`prepareCall`/`prepareBatchStmt`/`prepareBatchCall` | 4 | statement left open | `JdbcUtilParameterBindingTest#testStatementPreparationClosesStatementAfterUndeclaredCheckedBindingFailure` (5 routes) |
| `JdbcUtil.runBatchUpdate`/`runLargeBatchUpdate` | 2 | a failing rollback threw `NullPointerException` (`addSuppressedIfDistinct(null, …)`); a failing auto-commit restore replaced the batch failure | `JdbcUtilBatchTest#testBatchUpdateKeepsUndeclaredCheckedFailurePrimaryWhenCleanupFails` (4 cases) |
| `JdbcUtil.extractAndCloseResultSet` (coordinator) | 2 | the streaming close-time read failure replaced the extractor's failure | `JdbcUtilResultAdvanceTest#connectorJStreamingExtractionKeepsUndeclaredCheckedExtractorFailurePrimary` (2 cases) |
| `AbstractQuery` binding/config: `setParameters(ParametersSetter)`, `setParameters(T, BiParametersSetter)`, `settParameters` ×2, `setObjectForIndices`, `addBatchParameters` ×4, `configureStatement` ×2 | 11 | query left open | `AbstractQueryParameterBindingTest#userCodeThrowsUndeclaredCheckedException_closesQueryAndPropagatesIt` (11 cases) |
| `AbstractQuery` multi-result: `query2ResultSets`, `query3ResultSets`, `queryAllResultSets` ×2, `listAllResultSets` ×5 | 9 | primary failure lost | `AbstractQueryFailureTest#multipleResults_undeclaredCheckedReadFailure_staysPrimaryOverCleanupFailures` (18 cases) |
| `AbstractQuery` async: `submitAsyncTask`, `callAsync`/`runAsync` ×4, `AsyncQueryFutureTask.done` | 6 | query left open on a failed submission; `Future.cancel` threw | `AbstractQueryLifecycleTest#async_executorThrowsUndeclaredCheckedException_closesQueryAndDetachesTheTask`, `#cancel_beforeExecution_handlerThrowsUndeclaredCheckedException_cancelStillReturnsNormally` |
| `CallableQuery` multi-result/OUT-parameter (12) and binding (5) | 17 | primary failure lost / query left open | `CallableQueryFailureTest#resultReading_undeclaredCheckedReadFailure_staysPrimaryOverCleanupFailures` (32), `#binding_undeclaredCheckedFailure_closesQuery` (5) |
| `NamedQuery.repeatableStream` | 1 | query left open | `NamedQueryRepeatedStreamTest#bufferingFailureWithAnUndeclaredCheckedExceptionClosesTheQuery` |
| `NamedQuery.setParameters` (Map, EntityId, Object ×4, entity+names, TriParametersSetter), `addBatchParameters(Iterator)` | 10 | query left open | `NamedQueryParameterBindingTest#userCodeThrowsUndeclaredCheckedException_closesQueryAndPropagatesIt` (10 cases) |

### Other code fixes

| Area | Defect | Test |
| --- | --- | --- |
| `JdbcUtil.closeQuietly(ResultSet, Statement, Connection)` (and the 2-resource overloads) | An `Error` from an earlier close (e.g. a driver `LinkageError`) escaped at once, so the statement and the connection were never closed. Every close is now attempted; the first `Error` is rethrown afterwards, later ones suppressed. | `JdbcUtilResourceCleanupTest#testCloseQuietlyAll_ErrorStillClosesRemainingResources` |
| `JdbcUtil.getAllColumnValues(ResultSet, String)` | A `null` label reached the driver (pgjdbc: `NullPointerException`); now `IllegalArgumentException`, as `stream(ResultSet, String)` does. | `JdbcUtilResultMappingTest#testGetAllColumnValuesByName_NullOrEmptyLabelRejected` |
| `JdbcUtil.isTableNotExistsException` | SQLite reports a missing table only by message (`no such table`, SQLState `null`, code 1): `tableExists` threw instead of returning `false` (also the concurrent-drop paths of `dropTableIfExists`/`createTableIfNotExists`). | `JdbcUtilMetadataTest#tableExists_sqliteNoSuchTableDiagnostic_returnsFalse` |
| `Jdbc.DefaultDaoCache.update` / `DaoCacheByMap.update` | A built-in `batchUpdate`/`batchDelete`/`batchDeleteByIds` returning 0 skipped the invalidation, but their count leaves out `SUCCESS_NO_INFO` (Oracle before 12c reports it for every prepared-batch statement): old rows stayed cached (the `openDaoCacheScope` cache with a CRUD DAO). Batch methods now always invalidate. | `JdbcDaoCacheTest#testBuiltInCachesUpdate_BuiltInBatchMethodZeroResult_StillInvalidates`, `DaoCacheTransactionTest#aBuiltInBatchWriteWhoseDriverReportsNoUpdateCounts_invalidatesTheBuiltInScopeCaches` |
| `CallableQuery.setParameters(entity, names)` | `getBeanInfo` ran before the cleanup `try`: invalid bean metadata (a blank-padded `@Column`) left the query open (also reached by `setParameters(Object)`). | `CallableQueryParameterBindingTest#testSetParameters_EntityInvalidBeanMetadata_ClosesQuery` |
| `NamedQuery.setParameters(Object)` with one placeholder | Classifying a class with a registered single-value `Type` resolves its bean metadata outside the cleanup block; invalid metadata left the query open (the path the 10-26 fix of `setParameters(entity, names)` missed). | `NamedQueryParameterBindingTest#setParameters_singlePlaceholderTypedClassWithInvalidMetadataClosesQuery` |
| `DaoImpl.insertTargetTable` (coordinator follow-up, owner-approved) | A custom `@Query` INSERT without `INTO` (MySQL/MariaDB, SQL Server) had no identified destination, so an insert of the DAO entity into its own table was taken for one into another table and its generated key was dropped: `void insert(User u)` left the ID unset, a regression against 4.9.3. Without a top-level `INTO`, the first name after `INSERT` and its modifiers (`LOW_PRIORITY`/`DELAYED`/`HIGH_PRIORITY`/`IGNORE`, `TOP (n) PERCENT`) is the destination; an `INTO` found later still decides. | `DaoImplGeneratedKeyRegressionTest#insertDestinationWithoutIntoIsTheFirstNameAfterInsertAndItsModifiers`, `#entityInsertWithoutIntoWritesItsGeneratedKeyBack` |
| `DaoImpl.isListQuery` | A raw `Collection<X>` return type with `op = list`/`listAll` failed DAO creation, although `DEFAULT` serves it as a `List` and `@MergedById` validation accepts it. | `DaoImplIntegrationTest#testCustomSelect_RawCollectionReturnTypeWithListOperation` |
| `DaoImpl.resolveQueryReturnType` | A JDK 21 `SequencedSet<X>` return type passed DAO creation, then every call failed after the query ran (no factory instantiates the interface). It is served as a `LinkedHashSet`. | `DaoImplIntegrationTest#testCustomSelect_SequencedSetReturnType` |
| `DaoImpl` JSON DAO-cache copy: enum result | abacus-common writes a root enum quoted but reads it back with the quotes: every call of a cached method returning an enum failed after its SQL ran. An enum constant is now its own copy. | `DaoCacheJsonOuterContainersTest#rootEnumIsServedFromTheCache` |
| `DaoImpl` JSON DAO-cache copy: root container | A root collection/map of a non-public class the parser cannot construct (`List.of`, `Stream.toList()`, `Arrays.asList`, `Collections.unmodifiable*`, `Set.of`, `Map.of`, `EnumSet`) failed every call ("No default constructor found"); 4.9.4 fixed this for elements only. It is rebuilt as `ArrayList`/`LinkedHashSet`/`TreeSet`/`LinkedHashMap`/`TreeMap` when the return type accepts it. | `DaoCacheJsonOuterContainersTest#rootsTheParserCannotConstructAreRebuiltAsDefaultContainersOfTheirKind` |
| `JdbcCodeGenerationUtil` additional class body | Only `//` comments were stripped: `private int a /* c */;` was rejected, `private /* c */ final int a = 1;` got a `copy()` assignment (did not compile), a comma in a comment split off a ghost declarator. (Also resolves the 10-31 LOW "trailing block comment".) | `JdbcCodeGenerationEntityTest#testGenerateEntityClass_BlockCommentsInAdditionalFieldDeclarationsAreIgnored`, `JdbcCodeGenerationEntityIntegrationTest#testGenerateEntityClass_BlockCommentsInAdditionalFieldDeclarationsCompile` |
| `JdbcCodeGenerationUtil` `@Table` name | `generateEntityClass(conn, " padded_table ")` emitted the padded name, which `BeanInfo` rejects: the entity compiled but every use failed. | `JdbcCodeGenerationEntityIntegrationTest#testGenerateEntityClass_TableNameWithSurroundingWhitespaceGivesUsableEntity` |
| `JdbcCodeGenerationUtil.checkGeneratedAnnotationClass` | A lambda constant in a custom annotation (a private static synthetic method; also coverage agents' `$jacocoInit`) was taken for a required element; an interface extending `Annotation` that is not an annotation was accepted (generated source did not compile). | `JdbcCodeGenerationEntityTest#testGenerateEntityClass_IgnoresNonElementMethodsOfCustomAnnotationClass`, new case of `rejectsAnnotationsThatCannotRepresentTheGeneratedTable` |
| `DaoUtil` completion of parallel join loads/deletes (`complete`/`uncheckedComplete` and the two failure consumers) | An `Error` thrown by an `Executor`/`inParallel` join task (a driver `LinkageError`) came out as an `UncheckedException` wrapping it (`getAsResult` carries it in an `ExecutionException`, which `toRuntimeException(e, true)` wrapped); the sequential overloads propagate the `Error`. It is now rethrown as itself, with the later task failures suppressed. | `DaoUtilTest#testCompletionMethodsPropagateTaskErrorWithLaterFailuresSuppressed` |
| `DBLock.unlock` | A failed row deletion kept the lock in the local refresh pool, so the refresh task kept the row alive and the target stayed locked for every other host until `close()`. The local entry is now removed in `finally`; a left-over row is reclaimed after a minute without refresh, or by a retried `unlock`. | `DBLockTest#testUnlock_FailedDeletionStopsRefreshingTheReleasedLock` |
| `DBLock.tryAcquireLock` | The lease timestamps were taken before waiting for a new connection after a failed cleanup: a long pool wait inserted an already-expired row that another host could take over. | `DBLockTest#testTryLock_LeaseStartsOnceTheInsertConnectionIsObtained` |

## Memory and performance (behaviour-preserving)

- Memory, `JdbcUtil.getSubPropInfo`: the row mappers call it for every column label their entity has no property for
  (e.g. with `ignoreUnmatchedColumns`); a plain name always yields `null` but was cached in the static per-class map, so
  every distinct unmatched label stayed for the life of the JVM. Plain names are now answered without the cache.
  `JdbcUtilResultMappingTest#testGetSubPropInfo_SingleSegment_NotRetainedInCache`.
- Performance and memory, `JdbcUtil.extractResultSetToDataset` (every `Dataset` extraction: `extractData`, `TO_DATASET`,
  `query()`, `queryByPage`, OUT-parameter cursors): the public `RowDataset` constructor copied every column, so the
  original and the copy of every cell were alive at once. The private, unaliased lists are now adopted with abacus-common's
  `RowDataset.fromOwnedColumns`, as its own CSV/Excel importers do. Pinned by
  `JdbcUtilResultMappingTest#testExtractData_ResultOwnsIndependentGrowableColumns`.
- `DaoImpl` custom `@Query` INSERT returning `ID`: the ID fallback is read lazily (`orElseGet`), as the three built-in
  insert sites already do since 10-31.

## Javadoc

About 210 corrections. The largest groups:

- The `@throws IllegalStateException` of every DAO method that opens a transaction scope (batch insert/save/update/delete,
  `upsert(entity, cond)`/`batchUpsert`, the join deletes; about 100 sites in `InsertOps`/`UpdateOps`/`DeleteOps`/`Dao`/
  `CrudDao` and their unchecked/CRUD/join variants) now names the missing `SpringTransactionTracker` (required since 4.9.4).
  New tests pin the behaviour (`SpringScopeLifecycleTest#missingTrackingRejectsMultiElementDaoBatches`,
  `SpringTransactionParticipationTest#scopeOpeningDeletes_inAnUntrackedSpringTransaction_failBeforeDeleting`).
- `@throws IllegalArgumentException` for invalid bean property metadata and `null`/blank property names
  (`AbstractQuery`, `CallableQuery`, `NamedQuery`, insert ops).
- `batchUpdate` `@return`: `SUCCESS_NO_INFO` rows count as 0. `batchDeleteByIds(ids, batchSize)`: one by-id DELETE per
  ID in JDBC batches, no `IN` list.
- How single-property `list`/`stream`/`foreach` read values (with the property's type; `ReadOpsTest` pins it).
- `CrudDao` class docs: a `null` single-property id element of `batchGet`/`count(ids)` matches `IS NULL`.
- `SqlTransaction`: H2 commits the work done so far whenever a nested scope changes the isolation level.
- `DBLock`: hosts must share the JVM default time zone, not only synchronized clocks.
- `@CacheSerialization`/`@CacheResult`: JSON shares enum constants; root containers the parser cannot construct are
  rebuilt. `@Query`: an `Optional` scalar of a found SQL `NULL` fails with `NullPointerException` (declare `Nullable`).
- `JdbcUtil.callIgnoringSpringTransaction` example (`SUM` returns a `NULL` row: `orElseIfNull`), `call/runOutsideTransaction`
  roll back a transaction left open, `BiRowMapper.to(Object[].class)` vs `to(Object.class)` LOB materialization,
  `RowMapperBuilder` primitive getters turn SQL `NULL` into `0`.

## Reported, not fixed (owner decisions)

MED
- `JdbcUtil.asyncExecutor` (abacus-common `AsyncExecutor`): workers and its JVM shutdown hook are created with
  `new Thread` on the caller's thread (inheriting TCCL, inheritable thread-locals) and core threads never time out. With
  the library in a shared loader, the first application to create a DAO stays pinned (through the hook), and each one whose
  request thread created a worker. Same pattern as the 10-31 `DBLock` fix; changing it alters the TCCL that async
  callbacks see and the JVM-exit wait.
- `JdbcUtil.createCacheKey` without Kryo (a `provided` dependency): the JSON key writes a `Timestamp` as epoch millis, so
  two calls differing below the millisecond share a cached result (also `Timestamp`/`Date`/`Long` of equal millis on an
  `Object` parameter). New evidence beside the 10-26 "no parameter types in the key" item.
- `HandlerFactory.handlerPool` is keyed by class name: with the library in a shared parent loader, a redeployed (or
  second) application's `@Handler(impl = X.class)` gets the old loader's handler instance.
- Nested isolation: on drivers that commit on `setTransactionIsolation` (H2 verified, Derby per its docs), a nested scope
  with another level (`@Transactional(isolationLevel=X)` DAO methods inside a transaction, nested
  `beginTransaction(ds, X)`) silently commits the enclosing work. Now documented; every fix changes behaviour.
- Performance: each transaction of its own costs 2 extra round trips on PostgreSQL/MySQL (isolation read at begin,
  always set back at completion), because the docs promise to restore the original level.
- `CrudReadOps.refresh`/`batchRefresh` read through the DAO result cache (their inner `getOrNull`/`list` match cache
  filters, and `openDaoCacheScope` caches them): refresh can copy stale values.

LOW (selection; each agent's full list is in its report)
- `DaoImpl.prepareQuery` still cleans up only on `SQLException | RuntimeException | Error`; after this round's
  `settParameters` fix the only remaining trigger is a driver that throws undeclared checked exceptions.
- `DaoImpl`: custom `@Query` INSERT into the DAO's own table returning `Optional<ID>` is empty when the driver returns no
  key (an `ID` return gives the entity's ID); join metadata looked up per call; by-id `queryForXxx` recompute
  `handleLimit`; `save`/`insert` with property names don't validate them before the driver; `batchSave` redundant
  `allMatch`; multi-SQL `@Query` entries keep a trailing space; a procedure with mapper-id SQL and fragments loses the
  `:now`/`:sysTime` injection; `u.Optional*` primitives deep-copied on every cache hit.
- `JdbcUtil`: `freeBlob`/`freeClob` on JDBC 3 drivers; driver class loading through abacus-common's loader;
  `resultSetClassNotSupportAbsolute` and `dslPool` (a dialect with a capturing lambda) unbounded/strong; filtered
  `iterate` variants run the mapper for skipped rows; `withJoinedByPropertiesExclude` defeats `QueryUtil`'s cache;
  `prepareQuery(ds, …)` examples chain setters on an unowned query; SAP HANA missing-table message.
- `Jdbc`: `to(Object.class)` returns a raw `Clob`/`Blob` that dies with the statement; `toMap(Function…)` stores a `null`
  key; RowMapperBuilder `toArray`/`toCollection` NPE on `apply(null)`.
- `CrudDao.batchUpsert`/`batchRefresh` match loaded rows with Java `equals` (collation, DECIMAL scale, CHAR padding);
  `batchRefresh` shares value objects between entities with one ID.
- Join deletes: a `null` element of `entities` is caught inside the transaction scope (marks the caller's transaction
  rollback-only).
- `DataTransferUtil`: uninterruptible batch pause; `CsvExportBuilder.to(File)` checks the target after the query ran.
- `DBLock`: a lost INSERT response leaves the caller's own row blocking the target; the static shutdown hook pins a
  redeployed application's loader (as abacus-common's do).
- `JdbcCodeGenerationUtil`: `@AllArgsConstructor` kept next to `@NoArgsConstructor` when every column is excluded and
  the body has a method; `null` column label+name NPE; `TYPE_USE`-only table annotations rejected.
- `@PrefixFieldMapping` accepts `List<AbstractSupertype>` though rows map into the declared element type.
- `cs`: `action`, `joinPropEntities`, `parameterNames` (new), `selectPropName`, `propsToUpdate` unused (public, kept).
- `DaoUtil`/`JoinInfo`: one-to-many single-key batch select/delete rebuilds its SQL per chunk; the bounded join-metadata
  cache holds classes strongly (known family).
- `ResultSetProxy.getObject(String)` resolves labels only through `findColumn`, while a comment in
  `JdbcUtil.getColumnIndexForLabel` says some drivers accept a name in `getObject(String)` that `findColumn` rejects; no
  such driver identified.

## Verification

- Full suite, offline on JDK 25, in the isolated merge worktree (`mvn -o -Djacoco.skip=true clean test`):
  **7,544 tests, 0 failures, 0 errors, 52 skipped** (the MySQL-only class), after the `insertTargetTable` follow-up. Earlier
  runs of the merged tree also passed (7,541 without G7's fix, 7,542 with it).
- `mvn -o javadoc:javadoc` (`doclint=all`): generated, 0 warnings, 0 errors.
- `git diff --check` is clean, and every touched source keeps the checkout's LF line endings; the 57 files in the checkout
  are byte-identical to the tested merge tree.
- Fail-before: every agent checked each behaviour fix by reverting it in its worktree and running the new tests (all
  failed). The coordinator fixes were checked the same way: `extractAndCloseResultSet` (2 of 2 new cases fail reverted) and
  `insertTargetTable` (both new tests fail reverted; the DAO test sees the entity ID left `null`).
- Pinning tests (no fix; they document existing behaviour): `ReadOpsTest#testSingleProp_ListStreamAndForeach_ReadWithPropertyTypes`,
  `SpringScopeLifecycleTest#missingTrackingRejectsMultiElementDaoBatches`,
  `SpringTransactionParticipationTest#scopeOpeningDeletes_inAnUntrackedSpringTransaction_failBeforeDeleting`,
  `JdbcUtilResultMappingTest#testExtractData_ResultOwnsIndependentGrowableColumns`.
- Agents with no change: DI2, DI3, DT1, DT2, RSP, G2 (reports list what they traced).

## Follow-up review and fixes (2026-11-03)

A line-by-line review of the uncommitted changes above (6 agents) found the issues below; all were then fixed by 4 agents
in isolated copies (each behaviour fix has a fail-before regression test) plus coordinator Javadoc edits.

| Area | Defect | Test |
| --- | --- | --- |
| `DaoImpl.insertTargetTable` (MED) | An INTO-less SQL Server INSERT with `OUTPUT ... INTO` (or a later statement after `;`) took the OUTPUT/next statement's table: the own-table key was still dropped, or valid SQL rejected. | `DaoImplGeneratedKeyRegressionTest#insertDestinationWithoutIntoIgnoresOutputIntoAndLaterStatements` |
| `DaoImpl` `TOP`/`PERCENT` | Always skipped as modifiers: a MySQL table named `top` resolved to `null`. `TOP` is now a modifier only before `(`. | `#insertDestinationWithoutIntoTakesTopForSqlServersModifierOnlyBeforeItsGroup` |
| `DaoImpl` JSON cache | A declared `EnumSet` failed every call (now cloned, also when held); non-constructible containers held by an `Optional`/`Nullable` of an unresolved type failed (now rebuilt as default containers). | `DaoCacheJsonOuterContainersTest#heldNonPublicContainersOfAnUnresolvedTypeAreRebuiltAsDefaultContainers` and the EnumSet assertions |
| Missing tests of the 11-02 round | `JdbcUtilMetadataTest#tableExists_sqliteNoSuchTableDiagnostic_returnsFalse` and `JdbcUtilResultMappingTest#testGetSubPropInfo_SingleSegment_NotRetainedInCache` were listed above but did not exist; now added (plus `#dropTableIfExists_sqliteNoSuchTableAfterConcurrentDrop_returnsFalse`; `createTableIfNotExists` does not use the check). | as named |
| `JdbcUtil.close(...)` x3, `close(rs, bool, bool)`, `closeAfterPreparationFailure`, `extractData(..., closeResultSet)` | Undeclared checked exceptions skipped later closes / replaced the primary failure. `closeQuietly(rs, bool, bool)`: a close `Error` replaced a lookup `Error`. | `JdbcUtilResourceCleanupTest`, `JdbcUtilParameterBindingTest`, `JdbcUtilResultMappingTest` (new cases) |
| `Jdbc` built-in caches | An empty/`null` batch (no SQL) invalidated the cache on every call. | `JdbcDaoCacheTest#testBuiltInCachesUpdate_BuiltInBatchMethodWithNoElement_DoesNotInvalidate`, `DaoCacheTransactionTest#aBuiltInBatchWriteOfNoElement_leavesTheBuiltInScopeCachesAlone` |
| `AbstractQuery`/`NamedQuery.setLong(.., BigInteger)` | Narrow catch (CallableQuery's twin was converted). | `AbstractQueryParameterBindingTest`, `NamedQueryScalarBindingTest` |
| `listAllResultSets(Class)`, `listAllResultSetsAndGetOutParameters(Class)` | Invalid bean metadata failed only after the statement ran (never with zero result sets). | `AbstractQueryResultTest`, `CallableQueryResultTest` |
| `NamedQuery`/`CallableQuery.setParameters(entity, names)` | A failing custom name collection left the query open. | `NamedQueryParameterBindingTest`, `CallableQueryParameterBindingTest` |
| Codegen | Comments kept `@AllArgsConstructor` next to `@NoArgsConstructor` (did not compile); code after a multi-line comment's `*/` was dropped; converters got the padded entity name. | `JdbcCodeGenerationEntityIntegrationTest`, `JdbcCodeGenerationEntityTest`, `JdbcCodeGenerationFieldParsingTest` |

Javadoc: tracker clauses now say it must be registered before the Spring transaction began (82 sites, `dao/`);
`batchDelete`/`batchDeleteByIds` `SUCCESS_NO_INFO`; `UncheckedCrudInsertOps` class doc; all `closeQuietly` overloads
rethrow `Error`; `Jdbc` "non-batch" wording; `addBatchParameters` IAE scope. Left as is (driver-only paths): the
statement-config setters and `addBatch()` of `AbstractQuery`, the commit/rollback/Blob/executeBatch catches of `JdbcUtil`.

Verification: full suite in an isolated copy (`mvn -o -Djacoco.skip=true clean test`, JDK 25): **7,603 tests, 0 failures,
0 errors, 52 skipped**; `javadoc:javadoc` 0 warnings; `git diff --check` clean; the 64 changed files are byte-identical
to the tested copy. A first run with JaCoCo crashed a fork with native OOM (machine memory pressure), not a test failure.

### External review follow-up (2026-11-03, 4 items)

- Cleanup catches widened to `Throwable` (an undeclared checked exception from a driver or wrapper): batch commit,
  rollback, auto-commit restore and `clearBatch` (`runBatchUpdate`/`runLargeBatchUpdate`; a failed COMMIT skipped the
  rollback and left the caller's connection in manual-commit mode), `executeBatch`/`executeLargeBatch`,
  `closeExtractedResultSet`, and `AbstractQuery.closeAllResultsAndQueryIfAllowed`/`discardRemainingResultsIfStatementWillBeReused`.
  Tests: `JdbcUtilBatchTest#testBatchUpdateHandlesUndeclaredCheckedCommitAndCleanupFailures` (8),
  `JdbcUtilResultAdvanceTest#connectorJStreamingExtractionSuppressesUndeclaredCheckedCloseFailure` (2),
  `AbstractQueryFailureTest#multipleResults_undeclaredCheckedDrainFailure_isSuppressedOnTheReadFailure` (9).
- `DaoUtil` completion: later task failures are kept when the first task threw an `InvocationTargetException`/
  `UndeclaredThrowableException` wrapper (unwrapped by the conversion). `getAsResult()` already reports a plain runtime
  exception unwrapped with its suppressed failures, so that case was never broken.
  Test: `DaoUtilTest#testCompletionMethodsKeepLaterFailuresWhenTheFirstTaskFailureIsUnwrapped`.
- Docs: `batchGet`/`count(ids)` all-`null` ids (26 sites, single id matches `IS NULL`); `additionalClassBodySource` comments.
- Fail-before: all 20 new test cases failed against the previous sources. Full suite 7,623/0/0/52, javadoc 0 warnings
  (copy taken before another session's concurrent edits at 03:27-03:32, which this run did not cover).

## Follow-up 2026-11-03 (third review of local changes, then "Fix all findings")

9 report-only reviewers over a frozen snapshot of the local changes found 0 HIGH, 3 MED and ~20 LOW/DOC/test items;
6 fix agents then worked in isolated copies (disjoint files) and the results were merged by file.

| Area | Fix | Tests |
|---|---|---|
| AbstractQuery/CallableQuery | close() always runs close handlers after an undeclared checked cleanup failure; resets log any non-Error; stream paths (iterator consumption) close and keep the traversal failure primary | AbstractQueryUndeclaredCheckedCloseTest (new), CallableQueryFailureTest |
| RollbackActionLog/TransactionContext/SqlTransaction | rollback actions and IdSnapshot catch Throwable; interrupt re-set (commit/rollback actions, abort); FAILED_ROLLBACK cleanup attempts every step; one generic withSuppressed; stale "RuntimeException or Error" docs; DataSourceUtils.releaseConnection logs (doc) | SqlTransactionUndeclaredCheckedCleanupTest (+18), RollbackActionLogTest, RollbackActionLogIdRestoreTest |
| JdbcUtil | interrupt re-set at swallow/wrap sites; raw Throwable contained in clearing/log handler/extractor; releaseConnectionAfter keeps the DataSource operation's failure primary (execute*/prepare*/tableExists/getDBProductInfo/getOutParameters); probe savepoint rollback on any failure (getColumnNames, tableExists); docs | JdbcUtilUndeclaredCheckedCleanupTest, JdbcUtilMetadataTest, JdbcUtilBatchCleanupTest |
| DaoUtil + dao interfaces | uncheckedSql restores interrupts again (callInterrupt=true; futures path keeps false); join prop names validated also for empty entities; docs (null single ids, composite-only all-null IAE, Spring task failures, propNames IAE) | DaoUtilTest, JoinEntityHelperIntegrationTest, SpringTransactionParticipationTest, CrudReadOpsNullSingleIdIntegrationTest (new) |
| DaoImpl/Jdbc | held/element enum-with-body (getDeclaringClass), EnumMap copy, public JDK non-constructible roots; insertTargetTable `;`-less batch and `top` table; batchInsert/batchSave propNames checked before empty return; EnclosingIdSnapshot and insert cleanup catch Throwable; docs | DaoCacheJsonOuterContainersTest, DaoCacheJsonChildLoaderEnumTest (new), DaoImplEnclosingIdSnapshotTest (new), DaoImplGeneratedKeyRegressionTest, DaoImplBatchOperationsIntegrationTest, JdbcDaoCacheTest |
| JdbcCodeGenerationUtil | code after a text block statement ending at a lower brace depth; text-block constants for @AllArgsConstructor removal; control characters stripped from the entity name; docs | JdbcCodeGenerationFieldParsingTest, JdbcCodeGenerationEntityIntegrationTest |

Not fixed: an enum-with-body of a child class loader INSIDE a JSON-cached container still fails in abacus-common
(`EnumType.enumTypeName` -> `ClassUtil.forName`, upstream); `beginTransaction(ds)` still releases its connection in a nested
finally; the setter-style config methods and `addBatch()` keep their narrow catches (deliberate).
