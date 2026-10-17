Performance and memory improvements

This implements the seven principal findings from the [review](performance-memory-review-2026-10-15.md), plus ten code-level tuning opportunities and the fetch-size documentation improvement. Existing cache policy defaults, transaction ownership, generated-ID assignment order, and full-row callback contracts are preserved. The nested-cache improvement is explicitly opt-in.

| Review finding | Implemented behavior |
|---|---|
| F1: pooled cache lifecycle | `Jdbc.DefaultDaoCache` implements `AutoCloseable`; its `close()` is public and releases its pool resources. `Jdbc.DaoCache` declares no `close()`, so existing custom caches keep their own `close()` methods. A cache that DAO creation makes for a proxy it then discards is closed if it is `AutoCloseable`. |
| F2: completed futures retain queries | Static asynchronous tasks detach query and action references when execution or cancellation takes ownership. Completed, cancelled and rejected retained tasks release those references. Running tasks still own their own cleanup. |
| F3: mixed-ID staging | Mixed-ID save/insert buffers flush at the batch boundary and at SQL-shape changes, preserving input order. Two statements are reused within the existing transaction. Insert IDs remain deferred until commit, and generated-key counts are checked across the complete homogeneous run. |
| F4: full-width Dataset projection | Imports without user filters or setters read only the selected columns into a compact reusable row. User callbacks retain the complete original row and selection ordering/duplicates remain supported. |
| F5: unbounded join metadata | Metadata of a registered DAO proxy, which the proxy retains anyway, is reused for that DAO/entity/table combination. Other combinations share one metadata/type-index cache of at most 1,024 entries, evicted in insertion order with a second chance for entries used since the previous scan. Entities without joins do not retain table-name keys, and a registered DAO's combination does not also occupy a slot of that cache. Previously returned maps and plans remain usable after eviction. |
| F6: executing SELECTs for metadata | Code generation first uses `PreparedStatement.getMetaData()`. Null or empty metadata, metadata that reports a column as `Object` or without a class (SQLite types columns from the current row, so before execution it describes every column as `Object`), and any describe failure (feature, unimplemented-method or ordinary `SQLException`, such as SQL Server's temp-table limitation) retain the execution fallback; a failure on that path (executing the query, or reading its metadata afterwards) is reported with the describe failure attached as suppressed. Primary-key discovery still uses the originating connection. |
| F7: nested cache copies | `@CacheResult(cacheNestedResults = false)` suppresses inherited type-level result caching inside the annotated operation, while preserving explicit method annotations and cache invalidation. Existing behavior remains the default. |

Batch size bounds the submission buffer. Generated-ID assignments deferred until commit still require O(N) storage. Entities are paired through a second iterator over the input instead of being buffered, so a driver returning fewer keys than rows (or none) adds no retained references; only keys returned ahead of their rows are queued. The existing whole-run pairing policy remains intact.

Use the cache policy on DAOs whose convenience/default methods should cache only their outer result:

```java
@CacheResult(enabled = true, serialization = CacheSerialization.JSON,
        cacheNestedResults = false)
public interface UserDao extends ReadOnlyCrudDao<User, Long, UserDao> {
}
```

The scope follows synchronous calls on the current thread, including calls to another DAO proxy, and is removed when the operation returns or throws. An explicit method-level annotation keeps that method's own cache policy; it does not lift an outer suppression scope for the method's inherited descendants. Local thread caches retain their existing independent behavior. Calling an inner method directly still allows it to cache its own result.

Publicly created pooled caches can now be closed before JVM exit. A DAO proxy created with a cache stays registered for the life of the JVM and keeps that cache, so create the cache and its DAOs once for the application's lifetime and close the cache only after the DAOs' last use (for example at undeployment); afterwards their caching methods fail with `IllegalStateException`. Wrapping `createDao` in try-with-resources per use would register a new proxy each time. `clear()` still removes entries without shutting down the cache. Hold such a cache as a `DefaultDaoCache` to close it; `DaoCache` itself has no lifecycle method.

| Additional opportunity | Disposition |
|---|---|
| O1: asynchronous executor capacity | Preserved the existing default policy. A finite default queue would introduce task rejection under previously accepted workloads, and concurrency depends on connection-pool capacity. Use the existing explicit `Executor` overloads or `DaoCreationOptions.executor(...)` with an application-owned bounded executor and explicit rejection policy. |
| O2: permanent DAO registry | Preserved the documented identity/lifetime contract. Reuse DAO/configuration instances; changing proxy reuse and registry ownership needs a separate API design. |
| O3: repeated join lookup misses | Added a bounded 256-entry miss cache. Successful DAO registration invalidates misses, generation checks prevent stale publication, and the caller's fallback is never cached. |
| O4: default-method allocation | Adapt each MethodHandle once to an exact receiver/argument-array invocation shape. No per-call binding or generic argument adaptation. |
| O5: cache invalidation staging | Remove directly while traversing the pool's existing key snapshot, eliminating the second match list. |
| O6: NULL label lookups | Cache the resolved column index independently of first-non-null getter selection. Getter strategies reuse shared constants. |
| O7: CSV character calls | Deferred a parser rewrite. The reader already buffers I/O; scalar-call counts alone do not establish a throughput gain worth changing quoting and custom-parser behavior. |
| O8: projection lookup | Resolve multiple names through a first-index map, and resolve once per Dataset builder terminal operation. |
| O9: table-copy connections | Retain the two owned metadata connections through copying, preserving Spring-aware release semantics. |
| O10: eager join SQL plans | Build each dialect's complete plan set on first use, including PSC/PAC/PLC. DAO creation builds the plans of the DAO's own dialect, so a join whose SQL cannot be planned still fails DAO creation. Failed construction removes unpublished partial plans. The set-null UPDATE plan, which nothing read, is no longer built. |
| O11: lock maintenance | Reuse one owned auto-commit connection/statement during refresh and close. After execution failure, subsequent locks receive fresh resources. A refresh cycle, and the release of held locks on close, stops after an acquisition failure so pool outages do not incur one acquisition timeout per held lock; locks that close leaves unreleased expire with their leases. Keep individual updates, ownership checks and stale-lock handling. |
| O12: repeated stream materialization | Preserved the documented replay contract. Spooling changes temporary-file lifecycle, I/O failure behavior and cleanup; it needs a separately designed repeatable-source API and workload evidence. |
| O13: fetch-size bounds | Documented driver cursor prerequisites and preserved caller transaction ownership; fetch size alone is not advertised as a heap bound. |
| O14: generated composite IDs | Removed the per-row stream/tuple/list staging and bind directly through the existing label/property resolution. No new shared mutable layout cache. |
| O15: refresh bucket capacity | Allocate each ID's grouping bucket with initial capacity one, preserving duplicates and existing all-reads-before-mutation behavior. |

Join metadata hits remain lock-free. Metadata is built outside any lock, because parsing entity bean metadata runs entity initializers that may look up join metadata themselves; concurrent misses of one key may build it more than once, and the first result published is shared. Publishing, eviction and the removal of a newly registered DAO's entry and queued key are serialized; applications using more than 1,024 active combinations outside registered DAOs may rebuild metadata. Outside DAO creation, SQL-plan failures occur at first use of the corresponding dialect. Cache eviction affects reuse, not the validity of objects already held by live DAOs.

Validation was last completed on 2026-10-16, after the second review's follow-up fixes: the full default Maven suite passed **5,972 tests, 0 failures, 0 errors, 36 skipped**, **57 more** than the 5,915-test review baseline (the five tests of the removed set-null plan were deleted). The metadata-database code-generation test was replaced by the describe-fallback tests, and DAO creation now has tests for a join whose plans cannot be built and for a DAO rejected before its plans are built. Fresh JDK 21 Javadoc generation with `-Xdoclint:all` passed with no errors; its 40 warnings are accessibility warnings in DAO interfaces, unchanged from the first run. Generated pages were checked for the `DefaultDaoCache.close()` contract and `cacheNestedResults` option. `git diff --check` passed.

Focused regressions cover weak-reference retention in a separate JVM, cancellation races, cache lifecycle and compatibility, SQL order/rollback/generated keys, cache-copy counts and opt-in boundaries, Dataset read counts, metadata-only H2 generation, cache cardinality, concurrent plan publication, lock-maintenance operation counts and recovery after injected failures. All six new test classes participate in the default suite. No production throughput claim is made without a representative workload benchmark.

The deterministic operation checks demonstrate the intended changes: a 100-row, 500-column Dataset import selecting one column performs 100 source reads; the mixed-ID test submits its first batch after three entities for a batch size of three, reusing two statements; a 20-lock refresh/close cycle prepares one statement and borrows one connection on the successful path; and the opt-in JSON cache policy retains one result graph instead of four for the tested forwarding chain.

The reproducible full-suite command is:

```powershell
mvn -o '-Dmaven.repo.local=C:/Users/haiyangl/.m2/repository' '-Djacoco.skip=true' test
```

The logs of the initial implementation run are under `target/perf-implementation/` ([full-test.log](../target/perf-implementation/full-test.log), 5,958 tests; [dao-retest.log](../target/perf-implementation/dao-retest.log); [javadoc-fresh.log](../target/perf-implementation/javadoc-fresh.log)); a future Maven clean removes them. They predate the review follow-up fixes, whose validation above was not saved in the repository; rerun the command above to reproduce it. The initial targeted run caught the MethodHandle argument-type error fixed before the successful runs. Maven's standalone Javadoc report goal reused stale output, so fresh documentation was generated directly with the JDK and the saved `javadoc.options` / `javadoc.sources` argument files instead.
