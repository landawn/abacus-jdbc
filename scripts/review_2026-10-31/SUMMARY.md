# Whole-tree line-by-line review — 2026-10-31

Scope: every class under `src/main/java` (101 files, about 117,000 lines) at `11433d59` (tag `review-1031-base`). The
merged result was rebased onto `eb3c0a9e`, which adds only tests, `README.md` and `pom.xml` changes. 36 agents did the
review, each in its own git worktree. Large files were split into line windows:

| File | Windows |
| --- | --- |
| `JdbcUtil` | 5 |
| `DaoImpl` | 5 |
| `AbstractQuery` | 4 |
| `Jdbc` | 3 |
| `DataTransferUtil` | 2 |
| `JdbcCodeGenerationUtil` | 2 |
| `NamedQuery` | 2 |

The DAO interfaces, transactions, Spring/`DBLock`, annotations and small classes were reviewed as groups.

The review covered bugs, Javadoc, performance and memory:

- Each agent read its whole window and traced the callers, the callees and the abacus-common 8.3.2 / abacus-query 4.9.7
  sources.
- Agents fixed only defects they could prove with a concrete trace, and each behaviour fix has a regression test that
  fails before the fix.
- I read every patch before merging it.
- Design questions and changes to documented contracts were reported, not made.

## Code fixes (each with a fail-before regression test)

| Area | Defect | Test |
| --- | --- | --- |
| `JdbcUtil.BUILT_IN_DAO_UPDATE_METHODS` | The set was built by a class-path scan of the DAO package, which runs through abacus-common's class loader. Two cases broke when abacus-jdbc was in a child loader (for example a web application's) and abacus-common in a parent: (1) if the parent could see the DAO package, the set was empty, and `DaoCache` lost its no-op shortcut for built-in updates; (2) if it could not, `JdbcUtil` failed to initialize. The set now comes from walking the sealed `DaoBase`/`JoinEntityBase` hierarchy. | `JdbcUtilColumnConverterLifecycleTest#builtInDaoUpdateMethods_childLoadedJdbcUtil_areFoundWithoutClassPathScan`, `#builtInDaoUpdateMethods_matchPackageScan` |
| `AbstractQuery.CloseHandlers.run` | A close handler that threw an undeclared checked exception (Kotlin, sneaky throw) skipped all older handlers, including the one that releases a data-source query's connection. | `AbstractQueryLifecycleTest#close_handlerThrowsUndeclaredCheckedException_runsOlderHandlersAndRethrowsIt` |
| `AbstractQuery.closeSuppressingFailure` (coordinator follow-up) | Same family: the cleanup after a failed argument check let such an exception replace the primary IAE. | `AbstractQueryLifecycleTest#checkArgNotNull_handlerThrowsUndeclaredCheckedException_keepsTheArgumentFailurePrimary` |
| `Jdbc.ResultSetLayout.isBoundTo` | Once the weakly referenced, bound `ResultSet` was collected, a `null` argument matched, so a stateful mapper used its stale layout instead of failing. | `JdbcStatefulMapperReuseTest#testClearedResultSetReferenceDoesNotBindANullResultSet` |
| `DaoImpl` `@MappedByKey`/`@MergedById` validation | A property backed only by a public field (no getter) was rejected at DAO creation, although the query reads it through the bean info. | `DaoImplIntegrationTest#testCreateDao_MappedByKeyAndMergedByIdOnFieldOnlyProperty` |
| `CallableQuery.listAndGetOutParameters` (4 overloads + `Class`) | This broke the Connector/J streaming rule: the first driver result was closed before advancing, which threw while a later result was streaming, and a mapper failure skipped draining the remaining results. It now uses the same iterator/drain protocol as `queryAndGetOutParameters`. | `CallableQueryMySqlStreamingTest` (3 parameterized × 4 overloads) |
| `JdbcCodeGenerationUtil.generateEntityClass` | Selecting the javax annotations replaced the bare text `jakarta.persistence.Table`/`Column`/`Id`, so requested imports such as `jakarta.persistence.TableGenerator` became javax imports. | `JdbcCodeGenerationEntityTest#testGenerateEntityClass_JavaxSelectionKeepsRequestedJakartaImportsWithSamePrefix` |
| `SqlIdentifierUtil` delimiter detection | It stripped only whitespace, but the identifier parser also skips leading control characters (`String.trim()`). `"\u0001\"S\".t"` was accepted as `S.t` instead of being rejected because quoting is disabled. | `SqlIdentifierUtilTest#delimiterAfterLeadingControlCharacterIsStillExplicit` |
| `NonUpdateDao.batchSave(namedInsertSql, …)` (coordinator follow-up) | With an empty collection, UPDATE/DELETE SQL returned normally. The SQL-kind check now runs before the empty-collection return, as `batchInsert` already does (validation before early return). | `NonUpdateDaoTest#testSaveAndBatchSaveWithNamedSql_NonInsertSqlRejected` |

## Memory fixes (class-loader retention)

| Area | Defect | Test |
| --- | --- | --- |
| `DaoImpl.ImmutableContainerCopySerializer` (KRYO cache copies) | Copy layouts were in a plain `ClassValue` keyed by JDK view classes (bootstrap), so a child-loaded abacus-jdbc stayed reachable for the life of the JVM. It now uses the same loader-relation split as `JdbcUtil.columnConvertersOf`. | `DaoImplKryoCopyLayoutLifecycleTest` (separate-JVM probe) |
| `DBLock` refresh pool and shutdown hook | Both threads inherited the creating thread's context class loader and inheritable thread-locals, which pinned an application's loader when the library is shared. They now use an isolated `Thread.ofPlatform` factory. Visible change: the threads are now named `DBLock-refresh-N` and `DBLock-shutdown-hook`. | `DBLockTest#testRefreshExecutorThreadsDoNotInheritCallerContextLoaderOrThreadLocals` |
| `JdbcUtil` SQL-log thread-locals (coordinator follow-up) | `ThreadLocal.withInitial` installed a `SqlLogConfig` on every pooled thread that ran a statement, which kept a web application's loader reachable. Reads now fall back to shared defaults, and a thread holds a value only while its settings differ from them. | `JdbcUtilLoggingTest#testSqlLogThreadLocalsHoldNoValueAtDefaults` |

## Performance (behaviour-preserving)

- `Jdbc.cacheKeyMatchesTable` runs once per cached key on every invalidation. It now compares the table segment in place
  instead of allocating a substring. Matching rules are pinned by
  `JdbcDaoCacheTest#testDaoCacheByMapUpdate_TableSegmentMatchedInPlaceIgnoringCase`.
- `CrudDao.batchUpsert` now builds each entity's match key once instead of up to 4 times. A composite key builds a `Seid`
  each time. The two stream passes are replaced by one loop, and the ID `PropInfo` lookups are hoisted.
  `BinaryKeyMatchingTest#batchUpsertTreatsContentEqualBinaryKeysInDifferentLookupBatchesAsOneKey` pins the affected case.
- The ID fallback of `insert(namedInsertSql, entity)` (3 sites) now uses `orElseGet`, so the ID is read only when no key
  was returned.

## Javadoc

About 160 corrections. The largest group: the checked `ReadOps` (63 sites) and `CrudReadOps` (36 sites) `@throws
UncheckedSQLException` now name the Spring `CannotGetJdbcConnectionException` path. This closes the last open item of
the 10-26 list. The others:

- `CrudJoinEntityReadOps` batchGet IAE for non-id elements (16 sites).
- `@throws IllegalArgumentException` for invalid bean classes on `find*`/`listAllResultSets`/`listThenApply`/`stream`.
- How null array/collection elements are bound (`setParameters`).
- The `queryForSingleNonNull` example.
- Streaming cleanup for `listAndGetOutParameters`.
- The non-update DAO `save`/`batchSave` gate in `InsertOps`, `package-info.java` and the `NonUpdate*` classes.
- The `QueryOperation.DEFAULT` `Void` return.
- `DaoUtil.idsToCondition`, `getColumnToPropNameMap` and `hasTopLevelLimitClause`.
- The `prepareQuery` examples now own the query before binding.

## Reported, not fixed (owner decisions)

MED
- `CallableQuery` used through the inherited single-result methods (`list`/`query`/`stream` on a procedure) with MySQL
  Connector/J streaming has the same problem as the `listAndGetOutParameters` fix: `AbstractQuery`'s drain calls a plain
  `getMoreResults()` without reading each result. A fix needs `AbstractQuery` to change how it owns results, and should
  be verified against a live mysqld.
- `DBLock`: the static executor holds every `DBLock` strongly (`this::refreshLocks`). A lock that is never closed is
  never collected and refreshes its rows forever. Fixing this needs a weak task plus a `Cleaner`.
- `TransactionContext`'s static registry holds the `Thread` and `DataSource` strongly. A misused, never-completed
  transaction stays reachable after its thread dies. (This was already recommended on 10-22.)
- `DaoImpl` custom `@Query` INSERT into another table runs `getPrimaryKeys`/`getColumns` on every call. Caching it depends
  on the current schema and on DDL.
- `SqlIdentifierUtil.UNQUOTED_KEYWORDS` misses Oracle `UID`/`SYSDATE`/`ROWID`/`LEVEL` and MySQL `UTC_*`. Fixing it needs
  product information passed to the render helpers.

LOW (selection; each agent's full list is in its report)
- `paginate` with a literal `offset > ?` loses its page limit. This is new evidence for the known `hasTopLevelLimitClause`
  item.
- `HandlerFactory.handlerPool`, `JdbcUtil.idGeneratorGetterSetterPool`/`idExtractorPool`, `COLUMN_GETTER_POOL` and
  `ColumnOne.rowMapperPool` hold classes strongly. Each adds nothing beyond `DaoImpl.daoPool` or abacus-common's
  `TypeFactory`, which already pin the same classes.
- `runBatchUpdate`/`runLargeBatchUpdate` catch only `SQLException | RuntimeException | Error`.
- `DataTransferUtil`: abacus-common 8.3.2 now has a public block-reading `CsvUtil.CsvRecordReader` (the private reader
  reads one character per call). `importCsv(File, …)` opens the connection before opening the file.
- `CrudReadOps.batchRefresh` matches with Java `equals` (`BigDecimal` scale, CHAR padding).
- Many-to-many batch select (INNER JOIN) vs single select (`IN` subquery) differ on duplicate junction rows.
- `JdbcCodeGenerationUtil`: Lombok constructors exceed 255 parameter slots on wide tables. A class named like a package
  root (`java`) does not compile. A field line with a trailing block comment is not recognized.
- `SpringTransactionOwners` lookups take one JVM-wide lock. `DBLock.close()` uses `cancel(true)`.
- `TransactionContext` writes 4 INFO lines per transaction.
- The `OutParameter` examples use `:name` markers inside `{call …}`, which only works on drivers that accept them.

## Verification

- Full suite, offline on JDK 25, in an isolated worktree rebased on `eb3c0a9e` (`mvn -o -Djacoco.skip=true clean test`):
  **7,431 tests, 0 failures, 0 errors, 52 skipped**.
- `mvn -o javadoc:javadoc` (`doclint=all`): 0 warnings, 0 errors.
- `git diff --check` is clean, and every touched source keeps the checkout's LF line endings.
- Fail-before:
  - Every agent fix's regression test failed on the old code; each agent checked this by reverting its fix in its
    worktree.
  - The 3 coordinator fixes (`closeSuppressingFailure`, the SQL-log thread-locals, the `batchSave` gate) were checked by
    reverting all 3 at once: 3 tests run, 3 failures.
