# Java source review — 2026-10-31

Four reviewers examined all 101 Java source files under `src/main/java`, including interfaces, annotations, package documentation, and nested types. Work was divided into query/result wrappers, JDBC utilities, DAO/join/annotation code, and transaction/transfer/locking/support code. Implementations were checked method by method, with documentation compared against actual ownership, failure, transaction, and allocation behavior. Changes with substantial lifecycle implications received a second independent review.

## Confirmed defects and changes

| Area | Defect and resulting behavior | Regression coverage |
| --- | --- | --- |
| Query close handlers | Recursive callback composition could overflow the stack before older resource-release callbacks ran. Multiple callbacks now run iteratively in reverse registration order, releasing each captured callback after invocation. The single-callback path remains allocation-free beyond the callback itself. | `AbstractQueryLifecycleTest`: 50,000 callbacks, exact order, idempotent close, and mixed failures with all callbacks attempted. |
| Rollback ID restoration | A failing getter or setter stopped restoration of all later IDs in a batch snapshot. Restoration now continues for other eligible properties/entities, then rethrows the first failure with later failures suppressed. An unreadable current ID still prevents unsafe restoration of that entity; application-edited IDs remain protected. | `RollbackActionLogIdRestoreTest`: failing setter, failing written-value check, and multiple failures. All three failed against the original implementation before passing with the fix. |
| Composite join keys | Two composite join keys containing the same null component could match in memory even though the generated SQL equality predicates cannot match null. Such joined rows are now excluded from grouping, without rereading entity getters or allocating another key array. | `JoinInfoNullKeyTest`: each possible null position in two-, three-, and four-component keys, with complete-key controls. |
| Source DAO routing | Generated join load/delete paths selected target DAOs using the source DAO's construction-time data source, despite an overridden `dataSource()` routing ordinary statements elsewhere. They now use the resolved source data source. | `DaoImplJoinRoutingTest`: load/delete through scalar, singleton, and batch overloads. |
| Target DAO routing | A dynamically routed target DAO could remain in the join-resolution cache after moving to another data source; a cached miss could also hide a target that subsequently moved into the route. Dynamic targets and misses involving dynamic candidates are now resolved again. Fixed-source target caching remains. Follow-up: the override check is computed once per pooled DAO instead of reflectively on every scan; a miss that must be rechecked is warned about once per entity, data source and operation (later ones at debug level); miss entries reference their data source weakly. | `DaoImplJoinRoutingTest`: target routes moving into and out of a matching data source; warn-once while rechecking; a retired route's miss does not retain its data source. |
| Routed result caches | One DAO proxy could serve cached results from a previous data source after its route changed. DAO and thread-local result caches now distinguish resolved data-source identity. Weak identity keys avoid retaining transient pools; stable IDs avoid cache-entry churn when many routes remain live. Ordinary DAOs retain their previous path, and uncached calls do not resolve route namespaces. Follow-up: looking up a known route takes no lock and allocates no weak reference. | `DaoCacheRoutingTest`: equal-but-distinct pools, route switching, return to the construction source, two passes across 66 live routes, and per-table invalidation across routes for both cache modes; namespace reuse and release of a collected data source. |
| Missing-table detection | SQLState `42501` was treated globally as a missing table, suppressing PostgreSQL permission errors. It now requires the existing missing-object message detection; HSQLDB's recognized missing-object diagnostic still works. | `JdbcUtilMetadataTest`: permission errors propagate with original identity and cleanup, while HSQLDB missing-object handling remains. |
| Column converter cache | A strong global map keyed by arbitrary JDBC value classes retained their defining class loaders. Converters are now shared constants cached by loader relation: a strong map for classes of JdbcUtil's own or an ancestor loader, a `ClassValue` for classes of a descendant loader, and no cache for unrelated loaders. A `ClassValue` alone (the first version of this fix) stored JdbcUtil-defined converters on bootstrap classes such as `UUID`, so a child-loaded JdbcUtil's loader was never collected. | `JdbcUtilColumnConverterLifecycleTest`: isolated JVMs exercise index/label reads, preserve unknown values by identity, verify collection of disposable descendant and unrelated value-class loaders, and of a child-loaded JdbcUtil's loader after a `UUID` read; loader-ancestry unit test. Existing date/timestamp conversion tests also run. |
| Generated-key assignment | Composite `EntityId` assignment could overwrite recognized non-ID properties. A known generated non-ID column could also be mistaken for an unlabeled generated ID. Assignment now accepts declared ID properties and reserves the fallback for genuinely unknown column labels. | Five added cases in `JdbcUtilResultMappingTest`, plus existing generated-key DAO regression tests. |

Added 35 regression cases across eight test files. Complex fixes include comments explaining their failure modes and invariants. No explanatory comments were added solely for parameter validation.

## Javadoc improvements

- Clarified query cleanup after execution versus argument validation, iterative close callbacks, and suppression of later callback failures.
- Explained that existence checks and Java-side row filtering can reduce Java processing/allocation without reducing rows fetched by a driver or returned by a stored procedure.
- Clarified data-source routing for joins and caches, SQL null semantics for composite join keys, and the difference between driver batch size and memory retained by the full invocation.
- Documented converter-cache lifetime and generated-key fallback restrictions.
- Corrected lock acquisition/close race wording: row cleanup is attempted and can fail, leaving the lease until expiry or stale-row reclamation.
- Clarified that transfer methods accepting a data source do not automatically enlist in this library's `SqlTransaction`; callers can supply its connection explicitly. Spring acquisition rules still apply.
- Clarified that `SqlTransaction.connection()` checks thread ownership but can still return its reference after completion, when callers must no longer use it.
- Resolved all 51 missing-documentation warnings found by package-inclusive doclint, covering LOB ownership, identifier parsing/rendering, array-expression recognition, composite-ID comparison keys, transaction scope state, and internal helper parameters/results. `RollbackActionLog` now explicitly declares its previously implicit empty package-private constructor so its creation contract can be documented.

## Compatibility and limits

Public signatures are unchanged. With three or more failing close handlers, later failures are now directly suppressed on the first failure instead of forming a nested recursive suppression tree. This intentional diagnostic change is documented and tested.

The routing contract still requires compatible database product/version/schema and a route that stays consistent within an operation. A single routing `DataSource` object that internally switches tenants cannot be distinguished by object identity. Dynamic target join resolution performs registry scans because its route can change without DAO registration. Custom result-cache implementations continue to own eviction and result retention.

The allocation/lifetime improvements here are structural and regression-tested; no production throughput or latency improvement is claimed without benchmarks. Broader reflection/DAO metadata caches were reviewed but were not redesigned speculatively. Real-driver buffering, localized diagnostics, and database-specific behavior remain subject to their documented contracts. `SqlExecutor` is an archived commented-out implementation with no functional public API; it was not revived.

Existing user dependency edits in both POM files were preserved.

## Validation

Validation is performed with JDK 21, offline Maven, bounded JVM heaps, and Mockito instrumentation loaded at JVM startup to avoid the Windows sandbox's blocked dynamic-attachment handshake. Temporary logging configuration limits output during the larger runs. These are invocation settings, not POM changes.

- Initial focused run: **508 tests passed**, zero failures/errors/skips.
- Final routing/cache run: **89 tests passed**, zero failures/errors/skips.
- Complete default suite: **7,231 tests, zero failures/errors, 55 skipped** (7,176 passed). The skips are 52 opt-in live MySQL cases and three assumptions for an Unsafe policy option unsupported by this JDK. Embedded database, pinned-driver lifecycle, subprocess, and new regression tests ran.
- Supplementary binding regression run: **six tests passed**, zero failures/errors/skips. The scanner found this existing class outside default-suite discovery, so it was selected explicitly.
- Javadoc for **all 101 source files**, including package-visible APIs, passed `-Xdoclint:all` with **zero errors and zero warnings**. The standard Maven report invocation encountered the Windows sandbox's writable-directory check on an existing output directory; direct Javadoc generation using Maven's resolved classpath succeeded in a fresh directory. Generated output: `target/deep-review-api-final/index.html`.
- Final compilation passed for 101 production and 248 test source files; **20 rollback tests passed** after the documentation follow-up. The full-suite result above covers all functional fixes; the subsequent source changes are documentation and an explicit equivalent empty constructor.
- The read-only test-maintenance scanner reports 163 ownership/discovery issues in existing test families; none refer to the added regression classes. These include the two existing parameterized `JdbcValueObjectBindingRegressionTest` methods exercised by the supplementary run. Scanner output is retained in `target/test-inventory.json`; this existing test-organization backlog was not rewritten as part of production-source review.
- Source/test/report `git diff --check` passed. A whole-workspace check also detects trailing whitespace in the user's existing POM changes, which were preserved.

Temporary evidence is under `target/deep-review-*.log`; Maven clean removes it. Detailed reviewer notes are under `target/review-{query,jdbc,dao,support-docs}.md`.

## Coverage ledger

The ledger below lists every production source file and its assigned review area. Unchanged files were reviewed without a further supported change being identified. Support implementation review included transaction propagation and completion, thread ownership, Spring participation and cleanup, generated-ID snapshots, lock leases/retries, transfer resource ownership/batching/CSV parsing, metadata-driven code generation and SQL parsing, logging/configuration, enums, and exception contracts. The query reviewer additionally checked the complete Javadocs in `DBLock`, `SqlTransaction`, `DataTransferUtil`, and `JdbcCodeGenerationUtil`.

### Query/result wrappers (5 files)

- [AbstractQuery.java](../src/main/java/com/landawn/abacus/jdbc/AbstractQuery.java)
- [CallableQuery.java](../src/main/java/com/landawn/abacus/jdbc/CallableQuery.java)
- [NamedQuery.java](../src/main/java/com/landawn/abacus/jdbc/NamedQuery.java)
- [PreparedQuery.java](../src/main/java/com/landawn/abacus/jdbc/PreparedQuery.java)
- [ResultSetProxy.java](../src/main/java/com/landawn/abacus/jdbc/ResultSetProxy.java)

### JDBC utilities (6 files)

- [cs.java](../src/main/java/com/landawn/abacus/jdbc/cs.java)
- [Jdbc.java](../src/main/java/com/landawn/abacus/jdbc/Jdbc.java)
- [JdbcUtil.java](../src/main/java/com/landawn/abacus/jdbc/JdbcUtil.java)
- [QueryOperation.java](../src/main/java/com/landawn/abacus/jdbc/QueryOperation.java)
- [SqlExecutor.java](../src/main/java/com/landawn/abacus/jdbc/SqlExecutor.java)
- [SqlIdentifierUtil.java](../src/main/java/com/landawn/abacus/jdbc/SqlIdentifierUtil.java)

### DAO/join/annotations (74 files)

- [annotation/Bind.java](../src/main/java/com/landawn/abacus/jdbc/annotation/Bind.java)
- [annotation/BindList.java](../src/main/java/com/landawn/abacus/jdbc/annotation/BindList.java)
- [annotation/Cache.java](../src/main/java/com/landawn/abacus/jdbc/annotation/Cache.java)
- [annotation/CacheResult.java](../src/main/java/com/landawn/abacus/jdbc/annotation/CacheResult.java)
- [annotation/CacheSerialization.java](../src/main/java/com/landawn/abacus/jdbc/annotation/CacheSerialization.java)
- [annotation/DaoConfig.java](../src/main/java/com/landawn/abacus/jdbc/annotation/DaoConfig.java)
- [annotation/FetchColumnByEntityClass.java](../src/main/java/com/landawn/abacus/jdbc/annotation/FetchColumnByEntityClass.java)
- [annotation/Handler.java](../src/main/java/com/landawn/abacus/jdbc/annotation/Handler.java)
- [annotation/Handlers.java](../src/main/java/com/landawn/abacus/jdbc/annotation/Handlers.java)
- [annotation/MappedByKey.java](../src/main/java/com/landawn/abacus/jdbc/annotation/MappedByKey.java)
- [annotation/MergedById.java](../src/main/java/com/landawn/abacus/jdbc/annotation/MergedById.java)
- [annotation/NonDBOperation.java](../src/main/java/com/landawn/abacus/jdbc/annotation/NonDBOperation.java)
- [annotation/OutParameter.java](../src/main/java/com/landawn/abacus/jdbc/annotation/OutParameter.java)
- [annotation/OutParameters.java](../src/main/java/com/landawn/abacus/jdbc/annotation/OutParameters.java)
- [annotation/package-info.java](../src/main/java/com/landawn/abacus/jdbc/annotation/package-info.java)
- [annotation/PerfLog.java](../src/main/java/com/landawn/abacus/jdbc/annotation/PerfLog.java)
- [annotation/PrefixFieldMapping.java](../src/main/java/com/landawn/abacus/jdbc/annotation/PrefixFieldMapping.java)
- [annotation/Query.java](../src/main/java/com/landawn/abacus/jdbc/annotation/Query.java)
- [annotation/RefreshCache.java](../src/main/java/com/landawn/abacus/jdbc/annotation/RefreshCache.java)
- [annotation/SqlFragment.java](../src/main/java/com/landawn/abacus/jdbc/annotation/SqlFragment.java)
- [annotation/SqlFragmentList.java](../src/main/java/com/landawn/abacus/jdbc/annotation/SqlFragmentList.java)
- [annotation/SqlLogEnabled.java](../src/main/java/com/landawn/abacus/jdbc/annotation/SqlLogEnabled.java)
- [annotation/SqlScript.java](../src/main/java/com/landawn/abacus/jdbc/annotation/SqlScript.java)
- [annotation/SqlSource.java](../src/main/java/com/landawn/abacus/jdbc/annotation/SqlSource.java)
- [annotation/Transactional.java](../src/main/java/com/landawn/abacus/jdbc/annotation/Transactional.java)
- [dao/CrudDao.java](../src/main/java/com/landawn/abacus/jdbc/dao/CrudDao.java)
- [dao/CrudDeleteOps.java](../src/main/java/com/landawn/abacus/jdbc/dao/CrudDeleteOps.java)
- [dao/CrudInsertOps.java](../src/main/java/com/landawn/abacus/jdbc/dao/CrudInsertOps.java)
- [dao/CrudJoinEntityHelper.java](../src/main/java/com/landawn/abacus/jdbc/dao/CrudJoinEntityHelper.java)
- [dao/CrudJoinEntityReadOps.java](../src/main/java/com/landawn/abacus/jdbc/dao/CrudJoinEntityReadOps.java)
- [dao/CrudReadOps.java](../src/main/java/com/landawn/abacus/jdbc/dao/CrudReadOps.java)
- [dao/CrudUpdateOps.java](../src/main/java/com/landawn/abacus/jdbc/dao/CrudUpdateOps.java)
- [dao/Dao.java](../src/main/java/com/landawn/abacus/jdbc/dao/Dao.java)
- [dao/DaoBase.java](../src/main/java/com/landawn/abacus/jdbc/dao/DaoBase.java)
- [dao/DaoUtil.java](../src/main/java/com/landawn/abacus/jdbc/dao/DaoUtil.java)
- [dao/DeleteOps.java](../src/main/java/com/landawn/abacus/jdbc/dao/DeleteOps.java)
- [dao/InsertOps.java](../src/main/java/com/landawn/abacus/jdbc/dao/InsertOps.java)
- [dao/JoinEntityBase.java](../src/main/java/com/landawn/abacus/jdbc/dao/JoinEntityBase.java)
- [dao/JoinEntityDeleteOps.java](../src/main/java/com/landawn/abacus/jdbc/dao/JoinEntityDeleteOps.java)
- [dao/JoinEntityHelper.java](../src/main/java/com/landawn/abacus/jdbc/dao/JoinEntityHelper.java)
- [dao/JoinEntityReadOps.java](../src/main/java/com/landawn/abacus/jdbc/dao/JoinEntityReadOps.java)
- [dao/NonUpdateCrudDao.java](../src/main/java/com/landawn/abacus/jdbc/dao/NonUpdateCrudDao.java)
- [dao/NonUpdateDao.java](../src/main/java/com/landawn/abacus/jdbc/dao/NonUpdateDao.java)
- [dao/package-info.java](../src/main/java/com/landawn/abacus/jdbc/dao/package-info.java)
- [dao/ReadOnlyCrudDao.java](../src/main/java/com/landawn/abacus/jdbc/dao/ReadOnlyCrudDao.java)
- [dao/ReadOnlyCrudJoinEntityHelper.java](../src/main/java/com/landawn/abacus/jdbc/dao/ReadOnlyCrudJoinEntityHelper.java)
- [dao/ReadOnlyDao.java](../src/main/java/com/landawn/abacus/jdbc/dao/ReadOnlyDao.java)
- [dao/ReadOnlyJoinEntityHelper.java](../src/main/java/com/landawn/abacus/jdbc/dao/ReadOnlyJoinEntityHelper.java)
- [dao/ReadOps.java](../src/main/java/com/landawn/abacus/jdbc/dao/ReadOps.java)
- [dao/UncheckedCrudDao.java](../src/main/java/com/landawn/abacus/jdbc/dao/UncheckedCrudDao.java)
- [dao/UncheckedCrudDeleteOps.java](../src/main/java/com/landawn/abacus/jdbc/dao/UncheckedCrudDeleteOps.java)
- [dao/UncheckedCrudInsertOps.java](../src/main/java/com/landawn/abacus/jdbc/dao/UncheckedCrudInsertOps.java)
- [dao/UncheckedCrudJoinEntityHelper.java](../src/main/java/com/landawn/abacus/jdbc/dao/UncheckedCrudJoinEntityHelper.java)
- [dao/UncheckedCrudJoinEntityReadOps.java](../src/main/java/com/landawn/abacus/jdbc/dao/UncheckedCrudJoinEntityReadOps.java)
- [dao/UncheckedCrudReadOps.java](../src/main/java/com/landawn/abacus/jdbc/dao/UncheckedCrudReadOps.java)
- [dao/UncheckedCrudUpdateOps.java](../src/main/java/com/landawn/abacus/jdbc/dao/UncheckedCrudUpdateOps.java)
- [dao/UncheckedDao.java](../src/main/java/com/landawn/abacus/jdbc/dao/UncheckedDao.java)
- [dao/UncheckedDaoBase.java](../src/main/java/com/landawn/abacus/jdbc/dao/UncheckedDaoBase.java)
- [dao/UncheckedDeleteOps.java](../src/main/java/com/landawn/abacus/jdbc/dao/UncheckedDeleteOps.java)
- [dao/UncheckedInsertOps.java](../src/main/java/com/landawn/abacus/jdbc/dao/UncheckedInsertOps.java)
- [dao/UncheckedJoinEntityDeleteOps.java](../src/main/java/com/landawn/abacus/jdbc/dao/UncheckedJoinEntityDeleteOps.java)
- [dao/UncheckedJoinEntityHelper.java](../src/main/java/com/landawn/abacus/jdbc/dao/UncheckedJoinEntityHelper.java)
- [dao/UncheckedJoinEntityReadOps.java](../src/main/java/com/landawn/abacus/jdbc/dao/UncheckedJoinEntityReadOps.java)
- [dao/UncheckedNonUpdateCrudDao.java](../src/main/java/com/landawn/abacus/jdbc/dao/UncheckedNonUpdateCrudDao.java)
- [dao/UncheckedNonUpdateDao.java](../src/main/java/com/landawn/abacus/jdbc/dao/UncheckedNonUpdateDao.java)
- [dao/UncheckedReadOnlyCrudDao.java](../src/main/java/com/landawn/abacus/jdbc/dao/UncheckedReadOnlyCrudDao.java)
- [dao/UncheckedReadOnlyCrudJoinEntityHelper.java](../src/main/java/com/landawn/abacus/jdbc/dao/UncheckedReadOnlyCrudJoinEntityHelper.java)
- [dao/UncheckedReadOnlyDao.java](../src/main/java/com/landawn/abacus/jdbc/dao/UncheckedReadOnlyDao.java)
- [dao/UncheckedReadOnlyJoinEntityHelper.java](../src/main/java/com/landawn/abacus/jdbc/dao/UncheckedReadOnlyJoinEntityHelper.java)
- [dao/UncheckedReadOps.java](../src/main/java/com/landawn/abacus/jdbc/dao/UncheckedReadOps.java)
- [dao/UncheckedUpdateOps.java](../src/main/java/com/landawn/abacus/jdbc/dao/UncheckedUpdateOps.java)
- [dao/UpdateOps.java](../src/main/java/com/landawn/abacus/jdbc/dao/UpdateOps.java)
- [DaoImpl.java](../src/main/java/com/landawn/abacus/jdbc/DaoImpl.java)
- [JoinInfo.java](../src/main/java/com/landawn/abacus/jdbc/JoinInfo.java)

### Transaction/transfer/locking/support (16 files)

- [DataTransferUtil.java](../src/main/java/com/landawn/abacus/jdbc/DataTransferUtil.java)
- [DBLock.java](../src/main/java/com/landawn/abacus/jdbc/DBLock.java)
- [EmptyHandler.java](../src/main/java/com/landawn/abacus/jdbc/EmptyHandler.java)
- [FetchDirection.java](../src/main/java/com/landawn/abacus/jdbc/FetchDirection.java)
- [IsolationLevel.java](../src/main/java/com/landawn/abacus/jdbc/IsolationLevel.java)
- [JdbcCodeGenerationUtil.java](../src/main/java/com/landawn/abacus/jdbc/JdbcCodeGenerationUtil.java)
- [package-info.java](../src/main/java/com/landawn/abacus/jdbc/package-info.java)
- [Propagation.java](../src/main/java/com/landawn/abacus/jdbc/Propagation.java)
- [RollbackActionLog.java](../src/main/java/com/landawn/abacus/jdbc/RollbackActionLog.java)
- [RollbackOnlyException.java](../src/main/java/com/landawn/abacus/jdbc/RollbackOnlyException.java)
- [SpringApplicationContext.java](../src/main/java/com/landawn/abacus/jdbc/SpringApplicationContext.java)
- [SpringTransactionOwners.java](../src/main/java/com/landawn/abacus/jdbc/SpringTransactionOwners.java)
- [SpringTransactionTracker.java](../src/main/java/com/landawn/abacus/jdbc/SpringTransactionTracker.java)
- [SqlLogConfig.java](../src/main/java/com/landawn/abacus/jdbc/SqlLogConfig.java)
- [SqlTransaction.java](../src/main/java/com/landawn/abacus/jdbc/SqlTransaction.java)
- [TransactionContext.java](../src/main/java/com/landawn/abacus/jdbc/TransactionContext.java)

