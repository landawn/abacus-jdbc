## 4.9.4
* Breaking: every `JdbcUtil.beginTransaction` call returns its own `SqlTransaction`, a handle for the scope it opened (a nested call used to return the enclosing call's object; nested scopes still share one transaction, connection and `id()`). `SqlTransaction.equals`/`hashCode` are identity-based, and `isolationLevel()` reports the scope's own level. A scope's `rollbackIfNotCommitted()`/`close()` is a no-op once that scope has completed, and on any further call; a second `commit()` of a nested scope throws `IllegalStateException` instead of ending the enclosing scope. A scope that completes while a scope opened inside it is still open (no `commit()` or cleanup call, e.g. no `finally` block) exits that scope and marks the transaction rollback-only: an outermost `commit()` then rolls back and throws `RollbackOnlyException`, a nested one throws `IllegalStateException`, and so does a later `commit()` of the exited scope (scopes must complete innermost first: e.g. committing two scopes of one try-with-resources block in their opening order now rolls back and throws). Previously such a scope left the transaction registered on the thread, `ACTIVE` (a nested `commit()` without its cleanup call, followed by a failure of the enclosing scope) or `MARKED_ROLLBACK`, holding its connection: later work on that thread and data source joined it and was never committed
* Breaking: the outermost `SqlTransaction.commit()` of a transaction that a nested scope marked rollback-only rolls back and throws the new `RollbackOnlyException` (an `IllegalStateException`), and so do `JdbcUtil.callInTransaction`/`runInTransaction` and `@Transactional(REQUIRED/MANDATORY)` DAO methods whose code caught a nested failure and went on; they used to return normally, so the caller could take rolled-back work for committed. If that rollback fails, its failure is thrown instead, with the reason attached as a suppressed exception (a failure of the connection cleanup after a successful rollback carries the `RollbackOnlyException`)
* Breaking: the rollback of a `SqlTransaction` that participates in a Spring-managed transaction fails with `IllegalStateException` (status `FAILED_ROLLBACK`) when its rollback-only mark does not reach the transaction that decides the outcome: no connection holder is bound to the data source any more (its transaction was suspended or has completed), or a JPA transaction owning the connection refuses the mark or can no longer be identified. Such a rollback was only logged and reported as done (`ROLLED_BACK`) while Spring could still commit the work, and, without Spring's transaction synchronization, put the generated IDs of that work back; as when the mark throws, they are now left to the Spring transaction's outcome. Without synchronization, the rollback of a nested participating scope (or one never completed) no longer puts the generated IDs back right away either: the outermost scope puts them back once it has marked the Spring transaction rollback-only, and keeps them if that mark fails, as Spring may then commit the rows
* Breaking: for-update-only transactions are removed, with `JdbcUtil.beginTransaction(DataSource, IsolationLevel, boolean isForUpdateOnly)`: every statement prepared from a data source joins the transaction of the current thread, a plain `SELECT` included. Deciding by the SQL text which statements joined could not see what a query depends on or does: a read of session state (`LAST_INSERT_ID()`, `SCOPE_IDENTITY()`, `currval`/`lastval`, temporary tables, session variables) ran on another pooled connection and returned wrong data, lock functions escaped the transaction (`pg_advisory_xact_lock` released at once, `GET_LOCK`/`pg_advisory_lock` went back to the pool still held), a read of rows the transaction had written waited on its own locks on databases that lock for reads (with SQL Server's default lock timeout, forever), and reads missed the transaction's own writes and isolation level. A read that must not join says so with `callOutsideTransaction`/`runOutsideTransaction`. `executeBatchUpdate`/`executeLargeBatchUpdate(DataSource, ...)` run a plain read on the transaction's connection too. A `@Query` `SELECT` declared with `op = update`/`largeUpdate` is rejected at DAO creation whenever `SqlParser` classifies it as a read (the removed SQL scanner was consulted as well)
* Breaking: `JdbcUtil.executeBatchUpdate`/`executeLargeBatchUpdate(DataSource, ...)` with more than one parameter set run in a nested scope of the transaction already active on the thread, as DAO batch operations do: a failed batch marks that transaction rollback-only (its outermost `commit()` then rolls back and throws `RollbackOnlyException`). They used to run on the transaction's connection without a scope, so a caller that caught a batch failure committed the rows of the statements that had run. A parameter set that lacks a value for one of the SQL's parameters (too few positional values, or no property or entry for a named one) is rejected before the scope begins, without marking anything; a value that fails when it is bound (rejected by the driver or by its type, or a getter that throws) fails in the scope, as a failing statement does. A single parameter set runs as a single statement does, without a scope
* Breaking: `SqlTransaction.commit()` of a scope that ended without a commit, rolled back (by `rollbackIfNotCommitted()`, `close()` or `rollback()`) or whose commit failed (`RollbackOnlyException`, a failed database commit), throws `IllegalStateException`. It used to return normally, as if the scope's work had been committed. A repeated `commit()` after a successful one throws `IllegalStateException` too, as in Spring: `commit()` may be called only once per scope (it was ignored, and for a nested scope returned normally also after the transaction had rolled the scope's work back). So does the deprecated `rollback()` of a scope that has completed (committed, rolled back, or exited by an enclosing scope; it was logged and ignored); `rollbackIfNotCommitted()` and `close()` stay no-ops on a completed scope. The deprecated `rollback()` of a nested scope exits the scope as `rollbackIfNotCommitted()` does, also when the enclosing isolation level can't be restored (its `SQLException` is logged, the transaction marked rollback-only): it threw and left the scope open, the transaction `ACTIVE` after an explicit rollback
* Breaking: a transaction marked rollback-only accepts new scopes, as a Spring-managed transaction does: `beginTransaction`, `callInTransaction`/`runInTransaction`, `@Transactional(REQUIRED/MANDATORY)` DAO methods and the DAO and `JdbcUtil` batch operations called after a caught failure run, and their work is rolled back with the transaction, whose outermost `commit()` throws `RollbackOnlyException`. They used to throw `IllegalStateException` before running, while plain statements on the data source kept running in the transaction
* Breaking: inside a transaction, `upsert` and `batchUpsert` run their lookup and their writes in one transaction scope (a nested scope of the transaction active on the thread, or one participating in a Spring-managed transaction that owns the data source's connection), so a failure marks the transaction rollback-only; outside one, `batchUpsert` of more than one entity runs them in a transaction of its own, as other DAO batch operations do, while `upsert` (and `batchUpsert` of one entity) runs them as separate statements, as any single write (a transaction of its own would fail on a connection that a JTA transaction manages, which this library does not detect). The lookup used to run before and apart from the writes, on a connection of its own (or be served by a DAO result cache), and `batchUpsert` wrote in a transaction only when both inserts and updates were needed or either set was large. A concurrent upsert of the same key is still not kept out: a unique constraint makes one of them fail
* Breaking: DAO creation rejects `@Transactional` declared on a `@NonDBOperation` method, where it had no effect (one it would inherit is ignored); a method that overrides a `@Transactional` method inherits its annotation, as in Spring (it ran without a transaction), also an override of a method of a generic super-interface (`save(String)` for `save(T)` of `Base<T>`)
* Work run outside a Spring-managed transaction (`callOutsideTransaction`/`runOutsideTransaction`, `runIgnoringSpringTransaction`/`callIgnoringSpringTransaction`, DAO `@Transactional(REQUIRES_NEW)` and `NOT_SUPPORTED`) and the lock-table changes of a `DBLock` take their connection from the target data source of a Spring `TransactionAwareDataSourceProxy`. Through the proxy they got the connection of the Spring transaction: a `REQUIRES_NEW` commit also committed the Spring transaction's earlier work, which its rollback then could not undo, work meant to commit on its own (an audit record) was rolled back with the Spring transaction, and a `DBLock` switched that connection to auto-commit, committing the Spring transaction's work
* A `LinkageError` thrown while `JdbcUtil` acquires a connection through Spring (by the data source or its JDBC driver, such as a native library or a driver class that fails to load) propagates, and so does one thrown while `JdbcUtil` reads the connection Spring bound and its auto-commit mode (by the driver, or by the connection handle of an ORM transaction manager: `isInTransaction` answered `false`, and a transaction begun then committed the Spring transaction's connection with its earlier work). It used to turn this library's Spring support off for the rest of the JVM's life, and the connection was taken from the data source directly: from then on, on every thread and for every data source, `JdbcUtil` and DAO work in a Spring-managed transaction ran and committed on connections of its own, a transaction begun through a `TransactionAwareDataSourceProxy` committed the Spring transaction's connection with its earlier work, and work meant to run outside the Spring transaction (including `DBLock`'s lock-table changes) ran in it. Whether Spring is used is decided once, when `JdbcUtil` is initialized; a partial Spring installation counts as absent, now with a warning. A `DataTransferUtil` copy propagates such an error from a Spring connection proxy obtaining its target connection too, instead of no longer recognizing two proxies of one connection for the rest of the JVM's life (which could let MySQL/MariaDB stream the source rows on the connection the inserts use)
* `SqlTransaction.runOutsideTransaction`/`callOutsideTransaction` suspend this library's use of Spring-managed transactions for the transaction's data source while the command runs, whether or not the transaction participates in one, as `JdbcUtil.runOutsideTransaction`/`callOutsideTransaction` do. While Spring's transaction synchronization was active (a `SUPPORTS` scope, or a Spring transaction on another data source), Spring had bound the transaction's own connection to the thread, so work meant to commit on its own ran in the transaction and rolled back with it. They no longer hold a monitor while the command runs, which pinned a virtual thread to its carrier (JDK 21 to 23)
* An `Error` thrown while a `SqlTransaction` puts back the generated IDs of rolled-back inserts propagates from the call that completed the scope, once the transaction is rolled back (or marked rollback-only) and its connection released, also one thrown by an action that Spring's transaction synchronization runs when a participating rollback marks the Spring transaction rollback-only; it used to be logged only
* Work run outside a transaction (`JdbcUtil` and `SqlTransaction` `callOutsideTransaction`/`runOutsideTransaction`, DAO `@Transactional(REQUIRES_NEW)` and `NOT_SUPPORTED`) suspends this library's use of Spring-managed transactions for that data source only, and only for the Spring transaction bound when it began: work on another data source inside it used to ignore that data source's Spring transaction as well, and work in a Spring transaction begun inside it (a `REQUIRES_NEW` scope) ignored that transaction, both running and committing on a connection of their own, whatever the transaction's outcome. `runIgnoringSpringTransaction` still applies to every data source
* A `SqlTransaction` that participates in a Spring-managed transaction no longer takes the work of a Spring scope begun inside it: while Spring binds another transaction to the data source (`REQUIRES_NEW`) or none (`NOT_SUPPORTED`), operations and `beginTransaction` calls on it follow Spring's binding (a transaction begun there is one of its own), and the participating transaction is joined again once Spring resumes its transaction. Work in a `REQUIRES_NEW` scope used to run on the suspended transaction's connection and roll back with it
* A Spring-managed transaction whose completion has started is no longer participated in: a transaction begun in its `afterCompletion` callbacks, or in its `afterCommit` callbacks once this library has taken a connection of the transaction or begun a scope in it while it was the innermost Spring transaction on the thread (not one this library used only inside another transaction manager's transaction begun in it, whose suspended synchronizations no Spring API reaches, nor one whose work all went through other code, such as JPA: begin it with `PROPAGATION_REQUIRES_NEW` there), is one of its own, on a connection of its own (as `PROPAGATION_REQUIRES_NEW`, which Spring advises there, would begin it). A participating scope's work there was committed by Spring's cleanup (restoring auto-commit), also when the scope rolled back. Spring tells the completion of the innermost transaction on the thread only: in the completion callbacks of a transaction that another transaction manager began inside the transaction owning the data source, that one counts as completing too
* After a failed rollback, a `SqlTransaction` aborts its connection (`Connection.abort`) before releasing it (released also when the abort throws, such as the `AbstractMethodError` of a driver written before JDBC 4.1): it went back to the pool with the transaction's work pending, which a pool that restores auto-commit committed. A connection Spring bound to the thread for synchronization (in a `SUPPORTS` scope, or while a Spring transaction runs on another data source) is taken from Spring's connection holder and closed as well, so that later work in the Spring scope gets a new connection instead of the aborted one. A `SqlTransaction` also releases its connection the way it acquired it: completed inside `runIgnoringSpringTransaction` (or begun in one), it closed a connection Spring had bound to the thread for synchronization (in a `SUPPORTS` scope, or while a Spring transaction runs on another data source), which Spring still used
* A participating `SqlTransaction` completed while Spring runs another transaction on its data source (`REQUIRES_NEW`) or none (`NOT_SUPPORTED`) marks the transaction it participates in rollback-only (that of a `DataSourceTransactionManager` or `JdbcTransactionManager`; a JPA transaction can't be reached while suspended, so the rollback fails with `IllegalStateException`), and leaves its connection to it. It marked the transaction Spring bound then (or failed), so the one it participated in committed the work rolled back, and released its connection through `DataSourceUtils`, which closed the connection of the suspended transaction, whose commit then failed
* DAO result caches, and the cache of an `openDaoCacheScope`, are kept out of transactions on the DAO's data source (a `JdbcUtil` transaction, or a Spring-managed one that owns its connection): results read there are neither stored nor served, and a write there invalidates the cache once the transaction has committed (a built-in cache once per transaction and table; also when its commit failed or Spring reports an unknown outcome, as the transaction may have committed): only then for a write that joins a `JdbcUtil` transaction of its own (a built-in method, its `default` overloads included, or a `@Query` method; a `RuntimeException` from a custom cache's `update` is then logged instead of thrown from the write), right away too for any other: one in a Spring-managed transaction, or one that may commit on its own (a `default` method of the application, or a `REQUIRES_NEW`/`NOT_SUPPORTED`/`NEVER` one). An `Error` from such an invalidation after a Spring commit is thrown from `afterCompletion`, where Spring logs it, once the `afterCommit` callbacks of the other synchronizations have run. The transaction checks use the data source of a DAO whose interface overrides `dataSource()`. While a Spring-managed transaction that wrote a table runs, results of that table read outside it are neither served from nor stored in these caches, as long as the transaction's connection holder records its completion (`DataSourceTransactionManager`, `JdbcTransactionManager`), whatever transaction's synchronizations Spring runs meanwhile. With a `SpringTransactionTracker` registered with its transaction manager, the transaction manager reports the completion instead (JPA transactions included). For a JPA transaction without one, the commit is observed through the transaction synchronization of the innermost Spring transaction on the thread only: with synchronization disabled, the second invalidation runs right away too, and for a write to the data source of an enclosing transaction while another transaction manager's transaction runs inside it, at that inner commit, or not at all if that one rolls back; a result read outside the transaction before the enclosing commit may then stay cached until it expires. A read stores its result only if no invalidation of its table ran since it began (also one by a write the method made itself; the table name is compared ignoring case, as the built-in caches do): one that fetched the rows before a write and stored them after the write's invalidation kept the old rows cached until they expired. Uncommitted results used to be cached for other threads (also after a rollback), cached results missed the transaction's own writes, and results cached again before the commit outlived it
* The parallel join loads and deletes (the `Executor` and `inParallel` overloads of `loadJoinEntities`, `loadJoinEntitiesIfAbsent`, `loadAllJoinEntities`, `deleteJoinEntities` and `deleteAllJoinEntities`) run their tasks on the calling thread inside a transaction on the DAO's data source (the deletes as the sequential overload does, in one scope stopping at the first failure): on other threads they ran outside the transaction, each on a connection of its own, so a load missed its uncommitted rows, a delete committed on its own, and a task could wait on the transaction's locks. The overloads of `loadJoinEntities`, `loadJoinEntitiesIfAbsent` and `deleteJoinEntities` for several property names, sequential and parallel, check every name before any join of them is loaded or deleted: an invalid name failed once the names before it had been loaded or deleted, in its own task for the parallel ones (deletes committed outside a transaction), and, for a sequential delete in a transaction, in the scope the deletes ran in, which then marked the transaction rollback-only
* A query prepared from a data source in a `JdbcUtil` transaction (on the transaction's connection) runs only on the transaction's thread and until the transaction completes: `callAsync`/`runAsync` reject it (and close it, unless `closeAfterExecution(false)` is set), and running it on another thread, or after the transaction has released its connection, throws `IllegalStateException` (in the latter case closing the query, unless `closeAfterExecution(false)` is set); `callAsync`/`runAsync` reject it before they validate their arguments. A query prepared in a `JdbcUtil` transaction that participates in a Spring-managed one runs until the completion of that Spring transaction starts (see the `FAILED_COMMIT` entry for what is detected). A stream it returned may be consumed by a parallel stream's worker threads before the transaction completes; it must not be handed to another thread
* Generated IDs: an insert in a transaction takes its snapshot of the entities' IDs right before it first writes one, so an insert that writes none (its entities carry their IDs, and the database returns no keys or returns those IDs, as H2, PostgreSQL and Oracle do: an ID an entity already holds is not written again) takes none, and a rollback puts back only the IDs that still hold the value the insert wrote (an ID the application set since is kept). With a Spring older than 6.2, which reports no savepoints, a participating rollback leaves the IDs to the Spring transaction's outcome instead of putting back those of every insert on the data source, also inside a `PROPAGATION_NESTED` scope. The savepoint marks of the rollback actions are purged when only marks are added, too
* The outermost `commit()` of a `SqlTransaction` participating in a Spring-managed transaction that has completed meanwhile, or runs its `afterCommit` or `afterCompletion` callbacks (the scope was left open past its commit or rollback), throws `IllegalStateException`, with the status `FAILED_COMMIT`: it returned normally, with the status `COMMITTED`, although Spring had decided the outcome of the work without it, and may have rolled it back. Its rollback fails with `IllegalStateException` in those callbacks too (status `FAILED_ROLLBACK`), as it does once the Spring transaction has completed: it marked the transaction, which could no longer change its outcome, and reported `ROLLED_BACK`, also after Spring had committed the work. In those callbacks, detected through the synchronization this library registers with Spring's when the scope begins (for a scope begun inside another transaction manager's transaction, begun in the one it participates in, while that inner transaction's callbacks run as well); once they have run, for a `DataSourceTransactionManager` or `JdbcTransactionManager` transaction, whose connection holder records its completion (a completed JPA transaction can't be told from a suspended one, so such a commit returns normally). With a `SpringTransactionTracker` registered with the transaction manager, all of these, also in a synchronization registered late in the completion callbacks, are detected as the transaction manager reports them
* `SqlTransaction.callOutsideTransaction`/`runOutsideTransaction` roll back a transaction the command began and left open (as `rollbackIfNotCommitted()` of its outermost scope) before they throw their `IllegalStateException`, and register the transaction they ran the command outside again: the transaction left open stayed registered in its place, so later work on the data source meant for that transaction joined it, and it kept its connection on the thread. The exception is thrown also when the command completed the transaction it runs outside (the transaction left open went unnoticed)
* A failed ID generation (`generateId()`, or the ID setter) of a DAO insert in a transaction no longer makes a later rollback put back an ID the application sets after the failure (e.g. to retry the insert); nor does a setter of a key the database generated that fails after assigning it (a custom setter, through `@AccessFieldByMethod`, or the setter of a later entity of a batch, after those of the earlier ones). An entity whose ID can't be read after such a failure (its getter throws too; the failure is attached to the setter's as suppressed) is left alone by the rollback, keeping the ID the insert assigned (the rollback can't tell whether the application has set another one since); the other entities of the batch are handled as above (the first such getter failure used to make the rollback put back the IDs of every entity of the insert, also those the application had set since)
* A DAO method that overrides a `@NonDBOperation` method of a super-interface (such as a `default` override of `dataSource()`) is a non-DB operation too, annotated or not, as an override inherits `@Transactional`: the type-level handlers, logging and caching whose filters matched it applied to it
* `JdbcUtil.executeBatchUpdate`/`executeLargeBatchUpdate` check every parameter set against the SQL's parameters (enough positional values; a property or entry for every named parameter) before any of them is bound, and the `DataSource` overloads before they acquire a connection: a set that couldn't satisfy them failed only when it was bound, once the batches before it had run, in the caller's transaction on a connection whose auto-commit was off. The message names the index of the set
* New `SpringTransactionTracker`, a Spring `TransactionExecutionListener` (Spring 6.1+) to register with the transaction managers: it reports to this library when each transaction they begin commits or rolls back, keyed by the connection holder the transaction binds, so the library follows the transaction that owns a data source's connection, whatever synchronizations Spring runs meanwhile. With it, a `SqlTransaction` participating in a Spring transaction, the queries prepared in it, `beginTransaction` and `isInTransaction` recognize the completion of that transaction also from inside another transaction manager's transaction begun in it, in a synchronization registered late in its completion callbacks, and after a JPA transaction's callbacks; and a write in a Spring transaction keeps the DAO result caches out of its table until the transaction manager reports the completion, JPA transactions included. A transaction whose commit or rollback fails keeps them out until Spring's cleanup after it has finished, as the cleanup may still commit the work the failure left on the connection: a `DataSourceTransactionManager`/`JdbcTransactionManager` transaction until its connection holder is cleared (the cleanup's last step, after restoring auto-commit), a JPA transaction until its entity manager is closed (the cleanup's last step, after the JPA dialect's connection release; an entity manager bound before the transaction, as for open-in-view, until it is closed), whether or not anything still references the transaction's connection holder or a `SqlTransaction` of it, and another kind of transaction manager's, whose holder keeps a handle of its own, until its connection holder has been garbage collected. The completion is also reported through a transaction synchronization the tracker registers, so an execution listener that throws before the tracker's callbacks, with synchronization disabled, keeps the caches out until that cleanup has finished. Register it first, before the other execution listeners of a transaction manager, and with every transaction manager on a data source (registering it with only some of them is unsupported; still, a later transaction of a transaction manager without it, on a connection holder bound to the data source before a tracked transaction, is no longer taken for completed by the record the tracked one left, provided it runs with transaction synchronization, which Spring's cleanup after the tracked transaction clears first: work in it ran apart from it and survived its rollback. Work begun from a connection hook inside that cleanup still runs apart from the completed transaction): `SpringTransactionTracker.register(transactionManagers...)` puts a single tracker before the existing listeners, and a `SpringTransactionTracker.Registrar` bean (a `BeanPostProcessor`, declared with a `static` bean method) does so for every transaction manager of a Spring application context. Without it, the behavior is unchanged

## 4.9.3
* Breaking: generated SQL no longer quotes identifiers. `JdbcCodeGenerationUtil.generate*Sql`, `DataTransferUtil` copies, `JdbcUtil.dropTableIfExists` and `JdbcUtil.createDBLock` emit only simple, undelimited table and column names; a name that would need quoting (explicitly delimited, containing other characters, a common reserved word such as `order` or `user`, or a metadata name whose letter case the database would fold) is rejected with `IllegalArgumentException`, where 4.9.2 quoted delimited and non-simple names
* `JdbcUtil.tableExists` and `JdbcUtil.getColumnNames` look such names up through JDBC metadata only, without their fallback query. `getColumnNames` decides whether to run its fallback query with the same rule and the same probe name as `tableExists` (behaviourally equivalent while identifier quoting is disabled); on a database that aborts the transaction on a failed statement (PostgreSQL), a table-not-found probe without a savepoint inside a transaction propagates from `tableExists` too, instead of answering `false` on an aborted transaction
* `JdbcUtil.tableExists`, `getColumnNames` and `createTableIfNotExists` (and the `DBLock` table setup) look an unqualified table name up only in the connection's current schema and catalog, not in every schema; when the current schema is unknown on a database with schemas, only the fallback query (the database's own name resolution) decides. A delimited or quote-dependent name, which that query can't check, is also looked up in the other schemas of the search path, in order (PostgreSQL-compatible databases and H2). The `getColumnNames` fallback query runs under a savepoint on PostgreSQL-compatible databases inside a transaction, so a missing table no longer aborts the caller's transaction
* Repeated named stream/reader parameters are buffered and bound with independent copies
* `Jdbc.DefaultDaoCache` implements `AutoCloseable` and its `close()` is public: it releases the pool's eviction task and JVM shutdown hook. `Jdbc.DaoCache` itself declares no `close()`. Closing a cache does not unregister the DAOs created with it; their cached methods then fail
* `@CacheResult(cacheNestedResults = false)` caches only the outer result of a method, without the inherited type-level caching of the DAO calls it makes
* `JoinInfo` builds join SQL plans per dialect on first use and keeps metadata looked up outside registered DAOs in a bounded cache; `getEntityJoinInfo`, `getPropJoinInfo` and `getJoinEntityPropNamesByType` no longer declare `IllegalStateException`, and DAO creation still fails for a join whose plans cannot be built
* `JdbcCodeGenerationUtil.generateEntityClassByQuery` uses prepared-statement metadata without executing the query when the driver reports the type of every column before execution
* The DAO interfaces, `JdbcUtil`, `DataTransferUtil` and `JdbcCodeGenerationUtil` no longer declare Spring's unchecked `CannotGetJdbcConnectionException` in their `throws` clauses (their `@throws` documentation is unchanged; source and binary compatible). Reading the DAO interfaces' methods loaded that class, so without spring-jdbc on the class path (Spring is a `provided` dependency) `JdbcUtil` failed to initialize with `NoClassDefFoundError` and DAOs could not be created; a client calling `JdbcUtil`, `DataTransferUtil` or `JdbcCodeGenerationUtil` methods did not compile without spring-jdbc ("cannot access CannotGetJdbcConnectionException"), and reflection over these classes (`getDeclaredMethods()`, as frameworks, mocking libraries and native-image analysis do) failed with `NoClassDefFoundError`. No class other than the Spring adapters names a Spring type in a member's signature any more (`Jdbc.HandlerFactory`'s field for the Spring application context included)
* Inside a Spring-managed transaction that owns the data source's connection, `JdbcUtil.beginTransaction`, `callInTransaction`/`runInTransaction`, `executeBatchUpdate`/`executeLargeBatchUpdate(DataSource, ...)`, `@Transactional(REQUIRED/MANDATORY)` DAO methods and the built-in multi-statement DAO operations participate in it instead of committing Spring's connection: the connection's auto-commit mode and isolation level are left alone (a requested isolation level is ignored), the outermost commit leaves the commit to Spring, and a rollback or failure marks the Spring transaction rollback-only (Spring then rolls back; committing it throws `UnexpectedRollbackException`, or, with `JpaTransactionManager`, the JPA commit failure). The mark reaches the connection holder, the JPA `EntityTransaction` of the `JpaTransactionManager` that exposes the data source's JDBC connection (matched by its entity manager factory's data source, or else by the entity manager the connection's handle keeps, so another manager's JPA transaction is left alone; a programmatic `TransactionTemplate` JPA transaction used to commit the rolled-back work). An enclosing declarative (`@Transactional`) transaction of another transaction manager is no longer marked as well. Where neither mark would reach the transaction (a transaction manager that exposes its connection through a handle of its own while no JPA transaction owning it can be identified), `beginTransaction` throws `IllegalStateException` before any work runs. A transaction manager with transaction synchronization disabled is participated in too (a JdbcUtil scope used to commit its connection, including the enclosing transaction's earlier work). Owned means begun by `DataSourceTransactionManager`/`JdbcTransactionManager` for that data source, or by a JPA/Hibernate transaction manager that exposes its JDBC connection, also behind a `TransactionAwareDataSourceProxy`; a connection Spring bound only for synchronization (a non-transactional scope such as `SUPPORTS`, or a transaction on another data source) still gets a transaction of its own. JTA transactions are not detected
* `JdbcUtil.isInTransaction` (and so `@Transactional(MANDATORY/NEVER)`) applies the same ownership rule, no longer acquires a connection, and rejects a `null` data source
* DAO inserts that write generated IDs back onto entities put the previous IDs back if the enclosing `JdbcUtil` or Spring transaction rolls back. A Spring rollback is observed through Spring's transaction synchronization: with synchronization disabled, the IDs of a successful insert are kept. It is observed for the innermost Spring transaction on the thread: an insert on the data source of an enclosing transaction while another transaction manager's transaction runs inside it follows the inner transaction's outcome (an inner rollback puts the IDs back even if the enclosing transaction commits the rows; an inner commit keeps them even if the enclosing transaction rolls back); a rollback to a savepoint (`PROPAGATION_NESTED`, or a programmatic savepoint) puts back the IDs of the inserts since that savepoint only, also on a repeated rollback to a programmatic savepoint (Spring 6.2+ reports savepoints to its synchronizations, before performing the rollback and without reporting its failure; with an older Spring those IDs are kept although the rows are gone). A rollback of a `JdbcUtil` scope that participates in the Spring transaction puts them back right away, once the Spring transaction is marked rollback-only; if the JPA provider fails to mark its transaction, they follow the Spring transaction's outcome instead
* A failed DAO `insert`/`batchInsert` puts back the IDs it generated in the application (`@DaoConfig(callGenerateIdForInsertIfIdNotSet / callGenerateIdForInsertWithSqlIfIdNotSet)`) when nothing was inserted: before its statement executed, or when its own transaction rolled back (e.g. a later chunk failed). Previously the entities kept the IDs of rows that were never inserted (a single `insert` also when the entity's ID setter failed after storing the generated ID, or a part of a composite ID). A single statement that has executed keeps its IDs, as its row may already be committed; so does a batch, built-in or a `@Query(batch = true)` INSERT of entities, whose commit went through before its connection cleanup failed (database-generated IDs are then assigned as well)
* DAO batch operations (`batchSave`, `batchInsert`, `batchUpdate`, `batchDelete`, `batchDeleteByIds`, `@Query(batch = true)`) and `executeBatchUpdate`/`executeLargeBatchUpdate(DataSource, ...)` run in a transaction whenever they have more than one element, also when all of them fit in one batch. `executeBatchUpdate`/`executeLargeBatchUpdate(DataSource, ...)` with a plain read under a for-update-only transaction run on a separate connection for every batch size instead of joining it. `batchInsert` and an entity `@Query(batch = true)` INSERT reject a `null` element with `IllegalArgumentException` before anything runs (it used to fail with `NullPointerException` inside a transaction, or insert an all-null row)
* `batchInsert` returns the entities' IDs when the driver returns a different number of generated keys; composite bean/record IDs with a single unlabeled generated key (such as MySQL's `GENERATED_KEY`) return the full ID instead of failing with `ClassCastException`
* The cleanup of a nested `SqlTransaction` scope always exits, even when the driver rejects restoring the isolation level (PostgreSQL): the transaction is marked rollback-only instead of leaking its connection; a `RuntimeException` or `Error` from the driver propagates after the scope has been exited (an `SQLException` is logged). A participating rollback whose rollback-only mark fails ends in `FAILED_ROLLBACK` and leaves the in-memory changes (generated IDs) to the Spring transaction's outcome instead of undoing them; a JPA owner is marked only when every identified JPA transaction accepts the mark. A query run through `callAsync`/`runAsync` no longer releases a Spring-bound connection from another thread, and a query closed inside `runIgnoringSpringTransaction` on the thread that prepared it in a Spring transaction leaves Spring's connection to the transaction
* For-update-only transactions: SQL with several `;`-separated statements uses the transaction unless every statement is a plain read, and locking reads (`FOR UPDATE`, `FOR NO KEY UPDATE`, `FOR SHARE`, `FOR KEY SHARE`, `LOCK IN SHARE MODE`, DB2 `USE AND KEEP ... LOCKS`, SQL Server `WITH (UPDLOCK|XLOCK|HOLDLOCK|...)`) use it, so their locks last until it ends. On MySQL/MariaDB, a CTE `UPDATE`/`DELETE` whose `#` comment contains the word "select" now joins the transaction
* DAO creation now rejects, instead of failing on every call: `java.util.stream` / `Iterator` / `Seq` / non-`Stream` abacus stream return types, `@Query(batch = true)` on a query, a procedure returning `Tuple2` with a single-result operation, and a custom INSERT return type narrower than the entity's id type when the method may insert the DAO entity (a custom INSERT into another table keeps any return type and returns that table's key converted to it, on CRUD DAOs too). A custom `SELECT` method returning `void` executes the statement and reads and discards its rows (so a `SELECT ... FOR UPDATE` locks every row)
* DAO creation also rejects a `Map` ID type, a composite ID class that lacks one of the entity's ID properties, an interface that combines `ReadOnlyDao` with `Dao` or `NonUpdateDao` (or `NonUpdateDao` with `Dao`), and a `Seq`/`Iterator` return of a `@Transactional` method wherever a `Stream` return is already rejected. Custom `@Query` SELECT methods may return `Iterable`/`SequencedCollection` (served as a `List`) and `ImmutableCollection` (an `ImmutableList`); a custom INSERT of a composite-ID DAO may return `Seid`
* `Dao.update(entity, propNamesToUpdate, cond)` accepts raw `col = expr` SET expressions (as `CrudDao.update` does) and rejects an unknown property with `IllegalArgumentException`
* `CrudDao.update(entity, propNamesToUpdate)` and `batchUpdate(entities, propNamesToUpdate[, batchSize])` reject unknown property names up front; `update(Map, id)` and `update(Map, Condition)` bind IDs and values with the properties' declared types (e.g. an enum stored as its ordinal); `count(Condition)` also drops an outer `ORDER BY`/`LIMIT`/`OFFSET`/`FETCH` at the end of a literal (`Filters.expr`) condition, unless the literal has its own `GROUP BY` or set operation
* Property names to update are read as a raw SET expression (e.g. `"score = score + 5"`) only when they have an `=` outside quotes, parentheses and brackets, as abacus-query 4.9.5 renders them; an element such as `notes['a=b']` is rejected as an unknown property instead of being left unbound and shifting the condition values
* JSON cache copies keep the milliseconds and runtime class of `java.util.Date` values, and copy `Timestamp`, `Calendar` and `java.time` values exactly (nanos, zone, offset) as results, inside optional wrappers, as collection/array elements and as map keys or values; bean properties and `Dataset` values keep the JSON parser's precision
* DAO result-cache keys include the DAO's identity, so a cache shared by several DAOs (an `openDaoCacheScope` cache, or one `DaoCreationOptions.cache`) no longer returns results read through another DAO interface or data source; invalidation by table is unchanged. The process-wide DAO proxy pool is bounded to 4,096 proxies (least recently used evicted with a second chance); evicted proxies keep working. With several type-level `@CacheResult`/`@RefreshCache` annotations, a method uses the first one whose filter matches it
* Dataset extraction (`extractData`, `ResultExtractor.TO_DATASET`, `query()`, `queryByPage`, `streamAllResultSets`, result-set OUT parameters) renames duplicate column labels (`ID_2`, `ID_3`, ...) and names unlabeled columns `column<N>` instead of throwing `IllegalArgumentException`
* Stateful row mappers, consumers and extractors from the `Jdbc` factories and builders re-initialize for a `ResultSet` with a different column layout (checked once per result set), so they can be cached and reused; `BiRowMapper.builder()...to(recordClass)` converts values to the record component types
* Named-parameter bean binding supports nested property paths such as `:address.street` (a null intermediate binds SQL NULL) and reads each repeated name once; `NamedQuery.setLong(String, BigInteger)` closes the query on a binding failure like its siblings
* `AbstractQuery.insert`/`batchInsert` with a custom key extractor return a bean/record key that declares no ID property instead of treating it as a default ID; `JdbcUtil.getOutParameters` rejects an `OutParam` with neither a positive index nor a name
* `CrudReadOps.batchGet`/`count(ids)` match a composite id given as an `EntityId`, a `Map` or an ID bean/record on every declared ID property, as `getOrNull`/`deleteById` do: an id lacking a component, or with a `null` one, matches no row instead of every row agreeing on its present components or, for a `null` component (rendered `IS NULL` before), every row holding `NULL` in a nullable key column (`refresh` no longer fails with `DuplicateResultException` on such a column). `batchGet`/`count(ids)` validate every composite id before de-duplicating (an invalid id could be dropped as a duplicate of a valid one, so its rejection depended on the order) and de-duplicate composite ids by their declared ID properties (two `EntityId`s/`Map`s differing only in keys beyond them, or beans differing in non-ID properties, are one id: they no longer return or count the same row twice across chunk boundaries) and content-equal array ids (e.g. `byte[]`) given in a `Set` or inside `Map` ids; parallel join loads/deletes wait for all tasks when the caller is interrupted
* `JoinInfo.setJoinPropEntities(Collection, Collection)` ignores joined rows whose single-column join key is null
* Table names that can't be rendered without quoting (`JdbcCodeGenerationUtil` DataSource overloads, `DataTransferUtil` table copies) and malformed qualified names (`JdbcUtil.tableExists(DataSource, ...)`) are rejected before a connection is acquired; `JdbcCodeGenerationUtil.generateEntityClass*` validates its configuration before database access; `createTableIfNotExists` rejects a blank DDL
* `DataTransferUtil`: the `importData(dataset, columnNames, ...)` overloads import all columns for an empty `columnNames`, as `importFrom(dataset).columns(emptyList)` does; a copy whose source and target are the same connection, also behind distinct Spring connection proxies (`TransactionAwareDataSourceProxy`), no longer enables MySQL/MariaDB streaming; a `null` or blank `selectSql`/`insertSql` is rejected before a connection is acquired; statements and result sets opened internally are closed as by try-with-resources, so a close failure propagates
* `DBLock` keeps refreshing its locks after an `Error` in a refresh cycle; `SpringApplicationContext` keeps every registered context, so closing a child context falls back to its still-running parent instead of clearing the holder
* `JdbcCodeGenerationUtil.generateEntityClass*` skips primary-key detection when the entity name is not an existing table (fixes "Table not found" on SQLite), and derives a column's Java type from its SQL type when the driver reports `java.lang.Object` (SQLite with no rows or a NULL value)
* The `CannotGetJdbcConnectionException` documentation of the unchecked DAO interfaces states which operations translate a Spring connection-acquisition failure into `UncheckedSQLException`
* An empty serial `Blob`/`Clob` (e.g. from `CachedRowSet`) reads as an empty value; `JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql` treats a MySQL `#{name}` placeholder as a parameter, not a comment
* Improvements and bug fixes
* A participating `SqlTransaction` rolled back inside a Spring `PROPAGATION_NESTED` scope puts back right away only the IDs of the inserts since the newest open savepoint (the nested scope, which can only roll back); the enclosing transaction's earlier inserts keep their IDs until its outcome is known (kept if it handles the nested `UnexpectedRollbackException` and commits, put back when it completes rolled back). Previously every insert of the data source lost its ID at once although the enclosing rows could still commit. The savepoint marks of the rollback-action log hold their savepoint weakly and are purged once it is garbage collected (Spring reports no savepoint release), and a driver whose `Connection.setSavepoint` returns `null` no longer fails Spring's nested-transaction begin with an `IllegalArgumentException` from the synchronization
* Application-generated IDs of a single-statement insert (`insert`, a batch of one) are no longer put back when clearing the statement's parameters or batch, or the SQL log handler, throws an `Error` after the row was inserted (and committed under auto-commit): the execution mark is set inside `JdbcUtil.executeUpdate`/`executeBatch` right after the statement executed, before any cleanup
* Custom `@Query(batch = true)` INSERT of DAO entities: whether a batch holds entities is decided by the declared element type (a wildcard bound such as `Collection<? extends Entity>` included) or its first non-null row (was: its first row), so a batch with a leading `null` row, or an all-null entity batch, is rejected with `IllegalArgumentException` before anything runs instead of inserting all-null rows and leaving the entities' generated IDs unassigned; null rows of non-entity batches are unchanged, and a `null` or empty batch collection still executes nothing. A batch element type bound to an interface the entity class does not implement (e.g. `Collection<? extends Serializable>`) no longer disables the generated-key retrieval of a `void` batch INSERT: a subclass of a non-final entity class may implement it, and such an entity now receives its generated ID (a `Map` parameter or `Map`/`Collection` element type stays a non-entity argument and requests no keys). `CrudDao.batchInsert(entities, batchSize)` reuses its validation pass instead of reading every entity's ID a second time
* `batchGet(ids)`/`count(ids)` of a composite-id DAO reject a batch mixing `EntityId`s with `Map` or id-bean elements with an `IllegalArgumentException` whichever kind comes first (a `Map`/bean following an `EntityId` used to fail with a `ClassCastException`); the messages for a null or empty `EntityId` element name the element of `ids`
* H2: `JdbcUtil.tableExists`/`getColumnNames` find a delimited table in a search-path schema whose name contains a non-ASCII or control character; H2 reports such a name as `U&"..."` with `\XXXX` escapes, which the search-path parser could not read

## 4.9.2
* Naming convention improvements
* Improvements and bug fixes

## 4.9.1
* Naming convention improvements
* Improvements and bug fixes

## 4.9.0
* Naming convention improvements
* Improvements and bug fixes

## 4.8.6
* Naming convention improvements
* Improvements and bug fixes

## 4.8.5
* Naming convention improvements
* Improvements and bug fixes

## 4.8.4
* Naming convention improvements
* Improvements and bug fixes

## 4.8.3
* Naming convention improvements
* Improvements and bug fixes

## 4.8.2
* Naming convention improvements
* Improvements and bug fixes

## 4.8.1

* Prefer `callAsync`/`runAsync`; retain `callAsync`/`runAsync` as deprecated compatibility aliases.
* Prefer `setSqlPerfLogThresholdMillis`/`getSqlPerfLogThresholdMillis`; retain `sqlPerfLogThresholdMillis` as deprecated compatibility aliases.
* Prefer `openDaoCacheScope()` with try-with-resources; deprecate the manual thread-local DAO-cache lifecycle pair.
* Improve Javadocs and unit tests; fix transaction, query lifecycle, DAO validation, and SQL conversion edge cases.

## 4.8.0
* Naming convention improvements
* Improvements and bug fixes

## 4.7.7
* Naming convention improvements
* Improvements and bug fixes

## 4.7.6
* Naming convention improvements
* Improvements and bug fixes

## 4.7.3
* Naming convention improvements
* Improvements and bug fixes

## 4.7.2
* Change <groupId>com.landawn</groupId> to <groupId>com.landawn.abacus</groupId>
* Naming convention improvements
* Improvements and bug fixes

## 4.7.1
* Naming convention improvements
* Improvements and bug fixes

## 4.7.0
* Naming convention improvements
* Improvements and bug fixes

## 4.6.9
* Naming convention improvements
* Improvements and bug fixes

## 4.6.8
* Naming convention improvements
* Improvements and bug fixes

## 4.6.7
* Naming convention improvements
* Improvements and bug fixes

## 4.6.6
* Naming convention improvements
* Improvements and bug fixes

## 4.6.5
* Naming convention improvements
* Improvements and bug fixes

## 4.6.4
* Naming convention improvements
* Improvements and bug fixes

## 4.6.3
* Naming convention improvements
* Improvements and bug fixes

## 4.6.2
* Naming convention improvements
* Improvements and bug fixes

## 4.6.1
* Naming convention improvements
* Improvements and bug fixes

## 4.6.0
* Naming convention improvements
* Improvements and bug fixes

## 4.3.1
* Naming convention improvements
* Improvements and bug fixes

## 4.3.0
* Naming convention improvements
* Improvements and bug fixes

## 4.2.1
* Naming convention improvements
* Improvements and bug fixes

## 4.2.0
* Naming convention improvements
* Improvements and bug fixes

## 4.1.2
* Improvements and bug fixes

## 4.1.1
* Improvements and bug fixes

## 4.1.0
* Improvements and bug fixes

## 4.0.9

* Improve `JdbcCodeGenerationUtil`.

## 4.0.8

* Improve javadoc and add unit tests

## 4.0.7

* Improve javadoc and add unit tests

## 4.0.6

* Improve javadoc and add unit tests

## 4.0.5

* Improve javadoc and add unit tests

## 4.0.4

* Improve javadoc and add unit tests

## 4.0.3

* Improve javadoc and add unit tests

## 4.0.2

* Improve javadoc and add unit tests

## 4.0.1

* Improve javadoc and add unit tests

## 4.0.0

* Improve javadoc and add unit tests

## 3.26.8

* Improve javadoc and add unit tests

## 3.26.7

* Improve javadoc and add unit tests

## 3.26.6

* Improve javadoc and add unit tests

## 3.26.5

* Improve javadoc and add unit tests

## 3.26.3

* Improve javadoc and add unit tests

## 3.26.2

* Improve javadoc and add unit tests

## 3.25.11

* Improve javadoc and add unit tests

## 3.25.1

* Improve javadoc and add unit tests

### 3.25.0
* Replace `@Select/Update/Delete/Insert` with `@Query`.
* Improvements and bug fix.

### 3.10.12

* Improvements and bug fix.

### 3.10.11

* Improvements and bug fix.

### 3.10.10

* Improvements and bug fix.

### 3.10.9

* Improvements and bug fix.

### 3.10.8

* Improvements and bug fix.

### 3.10.7

* Improvements and bug fix.

### 3.10.6

* Improvements and bug fix.

### 3.10.5

* Improvements and bug fix.

### 3.10.4

* Improvements and bug fix.

### 3.10.3

* Improvements and bug fix.

### 3.10.2

* Improvements and bug fix.

### 3.10.1

* Improvements and bug fix.

### 3.10.0

* Improvements and bug fix.

### 3.9.8

* Improvements and bug fix.

### 3.9.7

* Improvements and bug fix.

### 3.9.6

* Refactor `JdbcUtils.exportCsv(...)`.
* Improvements and bug fix.

### 3.9.5

* Refactor `JdbcUtils.exportCsv(...)`.
* Improvements and bug fix.

### 3.9.4

* Improvements and bug fix.

### 3.9.3

* Improvements and bug fix.

### 3.9.2

* Rollback changes in `3.9.0`

### 3.9.0

* Refactory `JdbcUtil`.
* Add `JdbcContext`.
* Improvements and bug fix.

### 3.8.11

* Support `DaoCache`
* Improvements and bug fix.

### 3.8.10

* Support `DaoCache`
* Improvements and bug fix.

### 3.8.9

* Improvements and bug fix.

### 3.8.8

* Improvements and bug fix.

### 3.8.7

* Improvements and bug fix.

### 3.8.6

* Refactoring: `Dao.queryForSingleValue/queryForUniqueValue`.
* Improvements and bug fix.

### 3.8.5

* Improvements and bug fix.

### 3.8.4

* Improvements and bug fix.

### 3.8.3

* Upgrade dependence: `abacus-common:5.6.3`.
* Remove : `JdbcUtil.streamAllResultSets`.
* Rename `JdbcUtil.extractAllResultSets` to `JdbcUtil.streamAllResultSets`.
* Refactoring: `AbstractQuery.streamAllResultSets` and `CallableQuery.queryAllResultsetsAndGetOutParameters`.
* Improvements and bug fix.

### 3.8.2

* Upgrade dependence: `abacus-common:5.6.0`
* Improvements and bug fix.

### 3.8.1

* Improvements and bug fix.

### 3.8.0

* Improve JavaDoc.
* Improvements and bug fix.

### 3.7.13

* Improve JavaDoc.
* Improvements and bug fix.

### 3.7.12

* Improve JavaDoc.
* Improvements and bug fix.

### 3.7.11

* Improvements and bug fix.

### 3.7.10

* Improvements and bug fix.

### 3.7.9

* Improvements and bug fix.

### 3.7.8

* Improvements and bug fix.

### 3.7.7

* Improvements and bug fix.

### 3.7.6

* Improvements and bug fix.

### 3.7.5

* Improvements and bug fix.

### 3.7.4

* Improvements and bug fix.

### 3.7.3

* Improvements and bug fix.

### 3.7.2

* Improvements and bug fix.

### 3.7.1

* Improvements and bug fix.

### 3.7.0

* Improvements and bug fix.

### 3.6.6

* Improvements and bug fix.

### 3.6.5

* Improvements and bug fix.

### 3.6.4

* Improvements and bug fix.

### 3.6.3

* Improvements and bug fix.

### 3.6.2

* Improvements and bug fix.

### 3.6.1

* Improvements and bug fix.

### 3.6.0

* Improvements and bug fix.

### 3.5.22

* Improvements and bug fix.

### 3.5.21

* Improvements and bug fix.

### 3.5.20

* Improvements and bug fix.

### 3.5.19

* Improvements and bug fix.

### 3.5.18

* Improvements and bug fix.

### 3.5.17

* Improvements and bug fix.

### 3.5.16

* Improvements and bug fix.

### 3.5.15

* Improvements and bug fix.

### 3.5.14

* Improvements and bug fix.

### 3.5.13

* Improvements and bug fix.

### 3.5.12

* Improvements and bug fix.

### 3.5.11

* Improvements and bug fix.

### 3.5.10

* Improvements and bug fix.

### 3.5.9

* Improvements and bug fix.

### 3.5.8

* Improvements and bug fix.

### 3.5.7

* Improvements and bug fix.

### 3.5.6

* Improvements and bug fix.

### 3.5.5

* Improvements and bug fix.

### 3.5.4

* Improvements and bug fix.

### 3.5.3

* Improvements and bug fix.

### 3.5.2

* Improvements and bug fix.

### 3.5.1

* Improvements and bug fix.

### 3.5.0

* Improvements and bug fix.

### 3.3.26

* Improvements and bug fix.


### 3.3.25

* Improvements and bug fix.


### 3.3.24

* Improvements and bug fix.


### 3.3.23

* Improvements and bug fix.


### 3.3.22

* Improvements and bug fix.


### 3.3.21

* Improvements and bug fix.


### 3.3.20

* Improvements and bug fix.


### 3.3.19

* Improvements and bug fix.


### 3.3.18

* Improvements and bug fix.


### 3.3.17

* Improvements and bug fix.


### 3.3.16

* Improvements and bug fix.


### 3.3.15

* Improvements and bug fix.


### 3.3.14

* Improvements and bug fix.


### 3.3.13

* Improvements and bug fix.


### 3.3.12

* Add `AbstractQuery.queryThenApply/queryThenAccept/listThenApply`.
* Improvements and bug fix.


### 3.3.11

* Improvements and bug fix.


### 3.3.10

* Improvements and bug fix.


### 3.3.9

* Improvements and bug fix.


### 3.3.8

* Improvements and bug fix.


### 3.3.7

* Improvements and bug fix.


### 3.3.6

* Improvements and bug fix.


### 3.3.5

* Improvements and bug fix.


### 3.3.4

* Improvements and bug fix.


### 3.3.3

* Improvements and bug fix.


### 3.3.2

* Improvements and bug fix.


### 3.3.1

* Improvements and bug fix.


### 3.3.0

* Improvements By SonarLint


### 3.2.23

* Improvements and bug fix.


### 3.2.22

* Improvements and bug fix.


### 3.2.21

* Improvements and bug fix.


### 3.2.20

* Improvements and bug fix.


### 3.2.19

* Improvements and bug fix.


### 3.2.18

* Improvements and bug fix.


### 3.2.17

* Improvements and bug fix.


### 3.2.16

* Improvements and bug fix.


### 3.2.15

* Improvements and bug fix.


### 3.2.14

* Improvements and bug fix.


### 3.2.13

* Improvements and bug fix.


### 3.2.12

* Improvements and bug fix.


### 3.2.11

* Improvements and bug fix.


### 3.2.10

* Improvements and bug fix.


### 3.2.9

* Add `PrefixFieldMapping`.
* Improvements and bug fix.


### 3.2.8

* Improvements and bug fix.


### 3.2.7

* Improvements and bug fix.


### 3.2.6

* Improvements and bug fix.


### 3.2.5

* Improvements and bug fix.


### 3.2.4

* Improvements and bug fix.


### 3.2.3

* Improvements and bug fix.


### 3.2.2

* Improvements and bug fix.


### 3.2.1

* Improvements and bug fix.


### 3.2.0

* Improvements and bug fix.


### 3.1.9

* Improvements and bug fix.


### 3.1.8

* Improvements and bug fix.


### 3.1.7

* Improvements and bug fix.


### 3.1.6

* Improvements and bug fix.


### 3.1.5

* Improvements and bug fix.


### 3.1

* Improvements and bug fix.


### 2.9

* Improvements and bug fix.


### 2.8.13

* Improvements and bug fix.


### 2.8.12

* Improvements and bug fix.


### 2.8.11

* Improvements and bug fix.


### 2.8.10

* Improvements and bug fix.


### 2.8.9

* Improvements and bug fix.


### 2.8.8

* Support `BindList`.
* Improvements and bug fix.


### 2.8.7

* Support `Record`.
* Improvements and bug fix.


### 2.8.6

* Support `Record`.
* Improvements and bug fix.


### 2.8.5

* Improvements and bug fix.


### 2.8.4

* Improvements and bug fix.


### 2.8.3

* Create `abacus-cache`. 
* Create `abacus-query`.
* Improvements and bug fix.


### 2.8.2

* Improvements and bug fix.


### 2.8.1

* Improvements and bug fix.


### 2.8.0

* Improvements and bug fix.


### 2.7.12

* Improvements and bug fix.


### 2.7.11

* Add `JdbcUtil.generateEntityClass`.

* Improvements and bug fix.


### 2.7.10

* Improvements and bug fix.


### 2.7.9

* Improvements and bug fix.


### 2.7.8

* Improvements and bug fix.


### 2.7.6

* Improvements and bug fix.


### 2.7.5

* Improvements and bug fix.


### 2.7.4

* Improvements and bug fix.


### 2.7.3

* Improvements and bug fix.


### 2.7.2

* Improvements and bug fix.


### 2.7.1

* Refactoring `JdbcUtils` and move `CSVUtil` to project abacus-common.
* Improvements and bug fix.


### 2.7.0

* Improvements and bug fix.


### 2.6.12

* Improvements and bug fix.


### 2.6.11

* Improvements and bug fix.


### 2.6.10

* Improvements and bug fix.


### 2.6.9

* A new release.


### 2.6.8

* Improvements and bug fix.


### 2.6.7

* Improvements and bug fix.


### 2.6.6

* Improvements and bug fix.


### 2.6.5

* Improvements and bug fix.


### 2.6.4

* Improvements and bug fix.


### 2.6.3

* Improvements and bug fix.


### 2.6.2

* Improvements and bug fix.


### 2.6.1

* Improvements and bug fix.


### 2.6.0

* Improvements and bug fix.


### 2.5.12

* Improvements and bug fix.


### 2.5.11

* Improvements and bug fix.


### 2.5.10

* Improvements and bug fix.


### 2.5.9

* Improvements and bug fix.


### 2.5.8

* Improvements and bug fix.


### 2.5.7

* Improvements and bug fix.


### 2.5.6

* Improvements and bug fix.


### 2.5.5

* Improvements and bug fix.


### 2.5.4

* Improvements and bug fix.


### 2.5.3

* Refactoring `SqlBuilder`.
* Improvements and bug fix.


### 2.5.2

* Improvements and bug fix.


### 2.5.1

* Improvements and bug fix.


### 2.5

* Improvements and bug fix.


### 2.4

* Improvements and bug fix.


### 2.3

* Improvements and bug fix.


### 2.2

* Add `CSVUtil`.
* Improvements and bug fix.


### 2.1

* Add `UncheckedDao`.
* Improvements and bug fix.


### 2.0

* Remove `SqlExecutor/Mapper`.
* Remove `DataSource` and the package.
* Improvements and bug fix.


### 1.20.18

* Improvements and bug fix.


### 1.20.17

* Remove `PreparedQuery.listToMap`.
* Improvements and bug fix.


### 1.20.16

* Improvements and bug fix.


### 1.20.15

* Improvements and bug fix.


### 1.20.14

* Improvements and bug fix.


### 1.20.13

* Improvements and bug fix.


### 1.20.12

* Improvements and bug fix.


### 1.20.11

* Improvements and bug fix.


### 1.20.10

* Support `Dao.Define`.
* Improvements and bug fix.


### 1.20.9

* Improvements and bug fix.


### 1.20.8

* Improvements and bug fix.


### 1.20.7

* Improvements and bug fix.


### 1.20.6

* Improvements and bug fix.


### 1.20.5

* Add: `CrudDaoL`.
* Improvements and bug fix.


### 1.20.4

* Refactoring: `CrudDao.get(selectPropNames, ID)` to `CrudDao.get(ID, selectPropNames)`.
* Improvements and bug fix.


### 1.20.3

* Improvements and bug fix.


### 1.20.2

* Improvements and bug fix.


### 1.20.1

* Rename `NamedSQL` to `ParsedSql`.
* Improvements and bug fix.


### 1.20

* Improvements and bug fix.


### 1.10.9

* Rename `asyncApply/Accept` to `callAsync/Run`.
* Support Dao cache.
* Improvements and bug fix.


### 1.10.8

* Improvements and bug fix.


### 1.10.7

* Move `JdbcUtil.FetchDirection` to `com.landawn.abacus.util.FetchDirection`.
* Improvements and bug fix.


### 1.10.6

* Rename `Dao.saveAll` to `Dao.batchSave`.
* Refactoring: change `delete(entity, deleteAllJoinEntities)` to `delete(entity, onDeleteAction)`.
* Improvements and bug fix.


### 1.10.5

* Supports `@Handler`.
* Improvements and bug fix.


### 1.10.4

* Supports `@OutParameter`.
* Improvements and bug fix.


### 1.10.3

* Supports `@PerfLog`.
* Improvements and bug fix.


### 1.10.2

* Supports `Transactional.Propagation`.
* Improvements and bug fix.


### 1.10.1

* Supports `@Sql/@Transactional`.
* Improvements and bug fix.


### 1.10.0

* Add `JdbcUtil.setLogSQL/logSQLPerfAt`.
* Add `PreparedQuery.listToMap`.
* Add `CrudDao.get(id, includeAllJoinEntities)./delete(entity, deleteAllJoinEntities)`.
* Improvements and bug fix.


### 1.10

* Supports `batchInsert/Update/Delete` by annotated SQL in `Dao` interface.
* Improvements and bug fix.


### 1.9.23

* Improvements and bug fix.


### 1.9.22

* Improvements and bug fix.


### 1.9.21

* Improvements and bug fix.


### 1.9.20

* Improvements and bug fix.


### 1.9.19

* Improvements and bug fix.


### 1.9.18

* Improvements and bug fix.


### 1.9.17

* Remove `AsyncMapper`.
* Add `BasicDao/Mapper.loadJoinEntities/loadJoinEntitiesIfAbsent`.
* Improvements and bug fix.


### 1.9.16

* Improvements and bug fix.


### 1.9.15

* Improvements and bug fix.


### 1.9.14

* Improvements and bug fix.


### 1.9.13

* Improvements and bug fix.


### 1.9.12

* Improvements and bug fix.


### 1.9.11

* Improvements and bug fix.


### 1.9.10

* Improvements and bug fix.


### 1.9.9

* Remove parameter `startParameterIndex` from `JdbcUtil.PreparedQuery.setParameters(..., parameters...)`.
* Improvements and bug fix.


### 1.9.8

* Improvements and bug fix.


### 1.9.7

* Improvements and bug fix.


### 1.9.6

* Add `Dao.batchDeleteByIds`.
* Rename `SqlExecutor.w` to `SqlExecutor.create`.
* Improvements and bug fix.


### 1.9.5

* Add `Dao.batchGet`.
* Improvements and bug fix.


### 1.9.4

* Add `Dao.findFirst/list/stream(..., rowMapper)`.
* Improvements and bug fix.


### 1.9.3

* Improvements and bug fix.


### 1.9.2

* Improvements and bug fix.


### 1.9.1

* Third release.


### 1.9.0

* Second release.


### 0.0.1-SNAPSHOT

* First release.
