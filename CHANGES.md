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
