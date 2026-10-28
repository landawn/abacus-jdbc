/*
 * Copyright (C) 2015 HaiYang Li
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.landawn.abacus.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.Throwables;

/**
 * A JDBC transaction managed by this library, backed by a {@link Connection}. It is the transaction returned by
 * {@link JdbcUtil#beginTransaction(javax.sql.DataSource)} and its overloads, the one that
 * {@link JdbcUtil#callInTransaction(javax.sql.DataSource, Throwables.Callable) callInTransaction} /
 * {@link JdbcUtil#runInTransaction(javax.sql.DataSource, Throwables.Runnable) runInTransaction} and DAO methods annotated with
 * {@link com.landawn.abacus.jdbc.annotation.Transactional @Transactional} run in, and the one that queries and DAO calls
 * enlist in. This class documentation describes how transactions work in this library.
 *
 * <p><b>Bound to a thread and a data source</b></p>
 * <p>Instances are not created directly. {@code JdbcUtil.beginTransaction(ds)} acquires a connection through
 * {@link JdbcUtil#getConnection(javax.sql.DataSource)}, and, for a transaction of its own, disables its auto-commit mode,
 * applies the requested isolation level (unless it is {@link IsolationLevel#DEFAULT} or already in effect), and registers the
 * transaction under the pair (data source, current thread). A transaction of its own owns the connection for its lifetime and tracks
 * its status: when that transaction completes, the connection's original auto-commit mode and isolation level are restored
 * and the connection is released back to the data source (after a failed rollback the connection is aborted and released
 * without restoring them: restoring auto-commit would commit the work the rollback failed to undo; a connection Spring bound
 * to the thread for synchronization is taken from Spring as well, so that later work in its scope gets a new one).
 * A scope participating in a Spring-managed transaction leaves the connection's state, physical commit or rollback,
 * and final release to Spring; completing the participating scope does not complete Spring's transaction. Do not
 * close the connection manually while the transaction is active.</p>
 *
 * <p><b>&#9888; Warning:</b> A transaction and its connection must be used only on the thread that
 * started it. Transaction context is not propagated to executor tasks, asynchronous query execution
 * ({@code callAsync}/{@code runAsync}), parallel streams, or other threads: work started there does not see the
 * transaction (a lazy {@code Stream} a query prepared in the transaction returned is the one exception, see below). This is
 * enforced: {@link #connection()}, {@link #commit()}, {@link #rollback()},
 * {@link #rollbackIfNotCommitted()}, {@link #close()}, {@link #runOutsideTransaction(Throwables.Runnable)},
 * and {@link #callOutsideTransaction(Throwables.Callable)} all throw {@link IllegalStateException}
 * when invoked from any other thread. So does a query prepared from the data source in the transaction (it runs on the
 * transaction's connection), also once the transaction has completed and released its connection (for a transaction that
 * participates in a Spring-managed one, whose connection Spring keeps: once the completion of that Spring transaction has
 * started, as for {@link #commit()}); {@code callAsync} and {@code runAsync} reject such a
 * query, whatever the executor (also one that runs the task on the calling thread). The deferred execution of a lazy
 * {@code Stream} such a query returned is checked only for the transaction's completion, so that a parallel stream's worker
 * threads can run it while the owner thread waits: don't hand such a stream to another thread.</p>
 *
 * <p><b>Starting and completing a transaction</b></p>
 * <pre>{@code
 * SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
 * try {
 *     dao.save(entity);
 *     dao.update(anotherEntity);
 *     tran.commit();
 * } finally {
 *     tran.rollbackIfNotCommitted(); // no-op after a successful commit
 * }
 *
 * // Or, using try-with-resources:
 * try (SqlTransaction tran2 = JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE)) {
 *     dao.save(entity);
 *     tran2.commit();
 * } // close() rolls back automatically if commit() was not reached
 *
 * // Or let JdbcUtil complete it: committed if the callback returns normally, rolled back if it throws.
 * JdbcUtil.runInTransaction(dataSource, () -> {
 *     dao.save(entity);
 *     dao.update(anotherEntity);
 * });
 * }</pre>
 * <p>This class implements {@link AutoCloseable}; {@link #close()} simply delegates to
 * {@link #rollbackIfNotCommitted()}, making it safe to use in a try-with-resources block. The status is
 * {@link Status#ACTIVE} until the transaction is marked rollback-only ({@link Status#MARKED_ROLLBACK}: a nested scope rolled
 * back or was never completed, or its isolation level could not be restored) or the outermost scope completes, and then
 * {@link Status#COMMITTED} or {@link Status#ROLLED_BACK}, or {@link Status#FAILED_ROLLBACK} if the outermost rollback fails
 * (for a transaction participating in a Spring-managed one: if the Spring transaction can't be marked rollback-only). A
 * failed database commit is rolled back automatically, so the status after a failed {@code commit()} is
 * {@link Status#ROLLED_BACK}, or {@link Status#FAILED_ROLLBACK} if that rollback fails too; a participating transaction
 * whose Spring transaction completed (or began to complete) before its outermost {@code commit()} ends
 * {@link Status#FAILED_COMMIT}, for good: Spring decided the outcome of its work.</p>
 *
 * <p><b>Which operations enlist</b></p>
 * <p>An operation given the data source looks up the transaction of the current thread for that data source and, if there
 * is one, runs on the transaction's connection: {@link JdbcUtil#prepareQuery(javax.sql.DataSource, String)},
 * {@code prepareNamedQuery}, {@code prepareCallableQuery}, {@code executeQuery}, {@code executeUpdate},
 * {@code executeBatchUpdate} and {@code execute} with a data source, and the methods of DAOs created on that data source.
 * This includes operations in methods called from the transaction's scope, so a transaction spans method boundaries
 * without passing it around. Without a transaction, such an operation acquires a connection and releases it when it
 * completes. The following do <i>not</i> enlist:</p>
 * <ul>
 *   <li>operations given a {@link Connection}, such as {@link JdbcUtil#prepareQuery(Connection, String)}: they run on that
 *       connection as it is;</li>
 *   <li>{@link JdbcUtil#getConnection(javax.sql.DataSource)}, which does not consult this transaction (use
 *       {@link #connection()} for its connection); with Spring JDBC on the class path it may still return the same
 *       connection, the one Spring bound to the data source, so never close a connection without releasing it through
 *       {@link JdbcUtil#releaseConnection(Connection, javax.sql.DataSource)};</li>
 *   <li>work on another data source, or on another thread.</li>
 * </ul>
 * <p>Reads enlist like writes: a {@code SELECT} runs on the transaction's connection too, so it sees the transaction's own
 * uncommitted writes and session state (a generated key read with {@code LAST_INSERT_ID()}, temporary tables, session
 * variables, locks). A read that should run on a connection of its own, for example one that reads only committed data,
 * says so explicitly (see <i>Running work outside the transaction</i> below):</p>
 * <pre>{@code
 * try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
 *     JdbcUtil.executeUpdate(dataSource, "INSERT INTO orders (item) VALUES (?)", item);
 *     long orderId = JdbcUtil.prepareQuery(dataSource, "SELECT LAST_INSERT_ID()").queryForLong().orElseThrow(); // the transaction's connection
 *     Report report = JdbcUtil.callOutsideTransaction(dataSource, () -> reportDao.loadDashboard()); // a connection of its own
 *     tran.commit();
 * }
 * }</pre>
 * <p>DAO batch operations ({@code batchSave}, {@code batchInsert}, {@code batchUpdate}, {@code batchDelete},
 * {@code batchDeleteByIds}, {@code batchUpsert}, {@code @Query(batch = true)}) and
 * {@code executeBatchUpdate}/{@code executeLargeBatchUpdate} with a data source run in a transaction scope whenever they
 * have more than one element, so that all elements commit or roll back together: a nested scope of the current transaction
 * if there is one (a failure then marks that transaction rollback-only, as in any nested scope), otherwise a transaction of
 * their own. Inside a transaction, a DAO {@code upsert} (and a {@code batchUpsert} of one entity) runs its lookup and its
 * write in a nested scope too, and the parallel join loads and deletes of a DAO run their tasks on the calling thread, in
 * the transaction.</p>
 *
 * <p><b>Nested scopes</b></p>
 * <p>Beginning a transaction again on the same thread and data source does not start a brand-new transaction: it opens a
 * <i>nested scope</i> of the current one. Every {@code beginTransaction} call returns a new {@code SqlTransaction} object,
 * a handle for that scope, while all scopes of the transaction share one connection and one database transaction:</p>
 * <table border="1" style="border-collapse: collapse;">
 *   <caption><b>Scope events and their effect</b></caption>
 *   <tr style="background-color: #f2f2f2;">
 *     <th>Event</th>
 *     <th>Effect</th>
 *   </tr>
 *   <tr>
 *     <td>Nested {@code commit()}</td>
 *     <td>Completes the scope; no database {@code COMMIT}</td>
 *   </tr>
 *   <tr>
 *     <td>Nested rollback (or a nested {@code rollbackIfNotCommitted()}/{@code close()} without a preceding {@code commit()})</td>
 *     <td>Completes the scope and marks the transaction rollback-only ({@link Status#MARKED_ROLLBACK})</td>
 *   </tr>
 *   <tr>
 *     <td>Scope begun in a transaction marked rollback-only</td>
 *     <td>Joins it, as any nested scope: its work is rolled back with the transaction</td>
 *   </tr>
 *   <tr>
 *     <td>Outermost {@code commit()}</td>
 *     <td>Database {@code COMMIT}; if the transaction is marked rollback-only, a database {@code ROLLBACK} and a
 *         {@link RollbackOnlyException}, so the caller does not take the rolled-back work for committed</td>
 *   </tr>
 *   <tr>
 *     <td>Outermost rollback</td>
 *     <td>Database {@code ROLLBACK}</td>
 *   </tr>
 *   <tr>
 *     <td>A scope completes while a scope opened inside it is still open</td>
 *     <td>The inner scope was never completed: it is exited and the transaction is marked rollback-only. An outermost
 *         {@code commit()} then rolls back and throws {@link RollbackOnlyException}, a nested one throws
 *         {@link IllegalStateException}, and a later {@code commit()} of the exited inner scope throws
 *         {@link IllegalStateException} too</td>
 *   </tr>
 *   <tr>
 *     <td>{@code commit()} of a scope that has completed</td>
 *     <td>{@link IllegalStateException}: {@code commit()} may be called only once per scope, as in Spring, also after a
 *         successful one; the work of a scope that was rolled back or whose commit failed can't be committed</td>
 *   </tr>
 *   <tr>
 *     <td>Deprecated {@code rollback()} of a scope that has completed</td>
 *     <td>{@link IllegalStateException}, as in Spring; {@code rollbackIfNotCommitted()} and {@code close()} stay no-ops on a
 *         completed scope</td>
 *   </tr>
 * </table>
 * <p>Each scope records whether it has completed, so its cleanup call is unambiguous: {@link #rollbackIfNotCommitted()} and
 * {@link #close()} are no-ops on a scope that has committed or rolled back, and on any further call. A nested scope whose
 * cleanup call never runs (for example, its code threw and it has no {@code finally} block) is detected when the enclosing
 * scope completes, instead of leaving the transaction open. Complete scopes innermost first, ideally with
 * try-with-resources.</p>
 * <p>A nested scope may request a different isolation level; it applies to the connection until the scope exits, when the
 * enclosing scope's level is restored. {@link IsolationLevel#DEFAULT} inherits the enclosing scope's level. Databases
 * differ in when a changed level takes effect inside a transaction: PostgreSQL rejects the change once the transaction has
 * run a statement (the nested begin then fails), and MySQL applies it only from the next transaction, so the nested scope's
 * statements still run at the enclosing level. Set the level on the outermost scope where it matters. When the cleanup
 * call of a nested scope cannot restore the enclosing scope's isolation level (some drivers, such as PostgreSQL's, reject
 * isolation changes once the transaction has started), the scope is exited anyway and the transaction is marked
 * rollback-only; the outermost completion rolls back, restores the connection's original isolation level and releases the
 * connection.</p>
 *
 * <p><b>Running work outside the transaction</b></p>
 * <ul>
 *   <li>{@link #runOutsideTransaction(Throwables.Runnable)} / {@link #callOutsideTransaction(Throwables.Callable)}, and
 *       {@link JdbcUtil#runOutsideTransaction(javax.sql.DataSource, Throwables.Runnable)} /
 *       {@link JdbcUtil#callOutsideTransaction(javax.sql.DataSource, Throwables.Callable)}, which need no transaction
 *       reference: the transaction is unregistered from the thread while the command runs, so its operations on the data
 *       source acquire their own connections and commit independently (for example an audit record that must survive a
 *       rollback); the transaction is registered again afterwards. Spring transaction participation is suspended for the
 *       data source meanwhile too, so the connections do not come from a Spring-managed transaction either, nor from
 *       Spring's synchronization of this transaction's own connection (behind a Spring
 *       {@code TransactionAwareDataSourceProxy}, they come from its target data source); work on other data sources still
 *       joins their Spring transactions.</li>
 *   <li>{@link JdbcUtil#runIgnoringSpringTransaction(Throwables.Runnable)} disables only this library's use of
 *       Spring-managed transactions on the current thread (connections are acquired as described for
 *       {@link JdbcUtil#getConnection(javax.sql.DataSource)}).</li>
 * </ul>
 *
 * <p><b>DAO methods annotated with {@code @Transactional}</b></p>
 * <p>The DAO proxy wraps a method annotated with {@link com.landawn.abacus.jdbc.annotation.Transactional} in a transaction
 * scope on the DAO's data source, according to its {@link Propagation}:</p>
 * <table border="1" style="border-collapse: collapse;">
 *   <caption><b>Propagation of DAO methods</b></caption>
 *   <tr style="background-color: #f2f2f2;">
 *     <th>Propagation</th>
 *     <th>Behavior</th>
 *   </tr>
 *   <tr>
 *     <td>{@link Propagation#REQUIRED} (default)</td>
 *     <td>Begins a scope as {@code beginTransaction} does: joins the current transaction or starts one. A failure of a
 *         joined scope marks the transaction rollback-only</td>
 *   </tr>
 *   <tr>
 *     <td>{@link Propagation#MANDATORY}</td>
 *     <td>As {@code REQUIRED}, but throws {@link IllegalStateException} unless
 *         {@link JdbcUtil#isInTransaction(javax.sql.DataSource)} is {@code true}</td>
 *   </tr>
 *   <tr>
 *     <td>{@link Propagation#REQUIRES_NEW}</td>
 *     <td>Runs outside the current transaction (as {@code callOutsideTransaction} does) in a new transaction of its own</td>
 *   </tr>
 *   <tr>
 *     <td>{@link Propagation#SUPPORTS}</td>
 *     <td>No scope of its own; uses the current transaction if there is one</td>
 *   </tr>
 *   <tr>
 *     <td>{@link Propagation#NOT_SUPPORTED} / {@link Propagation#NEVER}</td>
 *     <td>Runs outside the current transaction; {@code NEVER} throws {@link IllegalStateException} if there is one</td>
 *   </tr>
 * </table>
 * <p>There is no savepoint-based nested propagation. A method returning a lazy {@code Stream}, {@code Seq} or
 * {@code Iterator} cannot use {@code REQUIRED}, {@code REQUIRES_NEW}, {@code NOT_SUPPORTED} or {@code NEVER}: DAO creation
 * rejects it, as its result would run after the scope has completed. A result served from a DAO cache bypasses execution,
 * including the transaction handling and the {@code MANDATORY}/{@code NEVER} checks.</p>
 *
 * <p><b>Inside a Spring-managed transaction:</b> With Spring JDBC on the class path, connections are acquired through Spring's
 * {@code DataSourceUtils}, so operations without a {@code JdbcUtil} transaction already run on the connection Spring bound to
 * the data source. When {@link JdbcUtil#beginTransaction(javax.sql.DataSource)} is called
 * while no {@code JdbcUtil} transaction exists for the data source on the current thread, and a Spring-managed
 * transaction owns that data source's connection (see {@link JdbcUtil#isInTransaction(javax.sql.DataSource)}), the
 * returned transaction <i>participates</i> in the Spring transaction instead of completing its connection: it neither
 * changes the connection's auto-commit mode nor its isolation level (requested isolation levels are ignored and
 * {@link #isolationLevel()} reports {@link IsolationLevel#DEFAULT}); the outermost {@link #commit()} completes the scope
 * without a database {@code COMMIT}; and a rollback (an outermost {@code rollbackIfNotCommitted()} without a successful
 * {@code commit()}, or the commit of a transaction marked rollback-only) issues no database {@code ROLLBACK} but marks the
 * Spring transaction rollback-only, so Spring rolls it back when it completes (Spring then reports an
 * {@code UnexpectedRollbackException} for a commit attempt). Either way the connection is released back to Spring.
 * The transaction marked is the one participated in: completed while Spring runs another transaction on the data source
 * ({@code REQUIRES_NEW}) or none ({@code NOT_SUPPORTED}), a participating transaction marks the connection
 * holder of the suspended one (that of {@code DataSourceTransactionManager} or {@code JdbcTransactionManager}), which it
 * consults when it completes, and leaves its connection to it.
 * If the Spring transaction cannot be marked rollback-only - it has completed, or it is suspended and exposes its connection
 * through a handle of its own (JPA), a JPA transaction that owns the connection refuses the mark or can no longer be
 * identified, or the mark throws - the rollback fails: it throws an {@link IllegalStateException} (or the mark's own
 * {@link RuntimeException} or {@link Error}), the status becomes {@link Status#FAILED_ROLLBACK}, the connection is still
 * released back to Spring, and the in-memory changes tied to the work (such as generated IDs written back to entities) are
 * not undone but left to the Spring transaction's outcome, as Spring may still commit the work (if the exception propagates
 * out of a Spring transaction's callback or {@code @Transactional} method, Spring usually rolls it back). A Spring transaction
 * that no rollback-only mark would reach is refused: {@code beginTransaction} then throws {@link IllegalStateException}
 * before any work runs.
 * Nested scopes and queries on the data source on this thread use the participating transaction as they would any other,
 * except while Spring binds another transaction to the data source ({@code REQUIRES_NEW}) or none (it suspended its
 * transaction for {@code NOT_SUPPORTED}): they follow Spring's binding then, until Spring binds the
 * participated transaction again. A Spring transaction whose completion has been observed is not participated in: a
 * transaction begun there is one of its own, on a connection of its own (see {@link JdbcUtil#beginTransaction(javax.sql.DataSource)}
 * for independent callback work). A participating scope left open into those callbacks can neither commit nor roll back
 * there (see {@link #commit()}), nor can a query prepared in it execute. Participation requires the actual owner to be
 * tracked before the transaction begins. Another manager's inner completion does not complete this scope's owner.
 * Register a {@link SpringTransactionTracker} with the transaction managers (first, before their other execution listeners,
 * as {@code SpringTransactionTracker.register} does, and with every one on the data source, as its {@code Registrar}
 * does) to have the transaction manager that owns the connection report when its transaction commits or rolls back: the
 * completion of the transaction participated in is then recognized whatever synchronizations Spring runs meanwhile (in
 * another transaction manager's transaction begun in it, or in a synchronization registered late in its completion
 * callbacks), including when synchronization is disabled or a JPA holder cannot report completion.
 * A connection Spring bound only for synchronization (a non-transactional scope such as {@code SUPPORTS}) is not a
 * Spring-managed transaction, and JTA transactions are not detected: the returned transaction then completes the connection
 * itself. So does a transaction that an operation begins of its own (a DAO batch operation of more than one element, or a
 * {@code @Transactional} DAO method, outside a transaction this library sees), which fails on a connection a JTA
 * transaction manages, as such a connection rejects {@code commit()} and {@code rollback()}.</p>
 *
 * <p><b>Rollback actions</b></p>
 * <p>DAO inserts that write generated IDs back onto their entities register an action that puts the previous IDs back.
 * The actions run newest first when the transaction rolls back, or as soon as a nested scope marks it rollback-only (a
 * rollback-only transaction can only end rolled back; actions registered after the mark, by work in scopes begun since,
 * run when the outermost scope completes), so entities do not keep the IDs of rows that were never committed.
 * Spring decides the outcome of a participating transaction, so a nested scope's rollback does not run its actions. They are
 * registered with Spring's transaction synchronization: they run when the Spring transaction rolls back, or right away when
 * the participating transaction's outermost scope marks it rollback-only (inside a {@code PROPAGATION_NESTED} scope, only
 * those registered since the newest open savepoint), with Spring 6.2 or later: a Spring older than 6.2 reports no
 * savepoints, so none run at the mark, and all follow the Spring transaction's outcome; a Spring rollback to a savepoint
 * runs those registered since that savepoint, with Spring 6.2 or later. Without an active synchronization, they are recorded by the participating transaction
 * itself and run when its outermost scope marks the Spring transaction rollback-only; once its outermost scope has committed,
 * the IDs are kept even if Spring rolls back later. If the mark fails (see above), the IDs are left to the Spring
 * transaction's outcome (and kept, without an active synchronization), as Spring may still commit the rows. A failing
 * action is logged and does not stop the others; an {@link Error} thrown by an action run by a completion call (by the
 * transaction itself, or by Spring's transaction synchronization once the outermost scope has marked the Spring transaction
 * rollback-only) propagates from that call, once the scope has completed. An {@code Error} thrown by an action Spring runs
 * when its transaction completes is logged by Spring.</p>
 *
 * <p><b>Completion scenarios</b></p>
 * <p>In the samples, {@code tran} is the outermost scope, begun by the first {@code JdbcUtil.beginTransaction(dataSource)}
 * on the thread, and {@code nested} a scope begun by a later call on the same thread and data source while {@code tran} is
 * open: they share one connection and one database transaction. {@code dao} is a DAO created on {@code dataSource} (an
 * unchecked one inside Spring's callbacks). Rows 1 to 24 describe a transaction of this library's own; in rows 25 to 29,
 * {@code transactionTemplate} runs a Spring-managed transaction that owns the data source's connection (a
 * {@code DataSourceTransactionManager} for {@code dataSource}), in which {@code tran} participates.</p>
 * <table border="1" style="border-collapse: collapse;">
 *   <caption><b>Completion scenarios and their outcome</b></caption>
 *   <tr style="background-color: #f2f2f2;">
 *     <th>Scenario (explanation and sample code)</th>
 *     <th>Result / expected behavior</th>
 *   </tr>
 *   <tr>
 *     <td><b>1. Commit the outermost scope.</b> The normal success path.
 *       <pre>{@code
 * SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
 * try {
 *     dao.insert(entity);
 *     tran.commit();
 * } finally {
 *     tran.rollbackIfNotCommitted(); // no-op after the commit
 * }
 * }</pre></td>
 *     <td>Database {@code COMMIT}; the status is {@link Status#COMMITTED}. The actions registered to run after the commit
 *         (DAO cache invalidations) run, then the connection's auto-commit mode and isolation level are restored and the
 *         connection is released.</td>
 *   </tr>
 *   <tr>
 *     <td><b>2. Clean up the outermost scope without a commit.</b> The work threw before {@code commit()} was reached.
 *       <pre>{@code
 * SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
 * try {
 *     dao.insert(entity); // throws SQLException
 *     tran.commit();
 * } finally {
 *     tran.rollbackIfNotCommitted();
 * }
 * }</pre></td>
 *     <td>Database {@code ROLLBACK}; the status is {@link Status#ROLLED_BACK}. Entities that received generated IDs in the
 *         transaction get their previous IDs back, and the connection is restored and released. If the database
 *         {@code ROLLBACK} fails, {@code rollbackIfNotCommitted()} throws {@link UncheckedSQLException}, the status is
 *         {@link Status#FAILED_ROLLBACK}, and the connection is aborted and released without restoring its auto-commit mode
 *         and isolation level (restoring auto-commit would commit the work the rollback failed to undo).</td>
 *   </tr>
 *   <tr>
 *     <td><b>3. Use try-with-resources.</b> {@code close()} does what {@code rollbackIfNotCommitted()} does.
 *       <pre>{@code
 * try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
 *     dao.insert(entity);
 *     tran.commit();
 * }
 * }</pre></td>
 *     <td>After a commit, {@code close()} does nothing. If {@code commit()} was not reached, {@code close()} rolls back as
 *         in row 2.</td>
 *   </tr>
 *   <tr>
 *     <td><b>4. Deprecated {@code rollback()} of an open outermost scope.</b>
 *       <pre>{@code
 * SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
 * dao.insert(entity);
 * tran.rollback();
 * }</pre></td>
 *     <td>As in row 2: database {@code ROLLBACK}, and the status is {@link Status#ROLLED_BACK}.</td>
 *   </tr>
 *   <tr>
 *     <td><b>5. Commit a nested scope.</b>
 *       <pre>{@code
 * try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
 *     try (SqlTransaction nested = JdbcUtil.beginTransaction(dataSource)) {
 *         dao.insert(entity);
 *         nested.commit(); // completes the scope only
 *     }
 *
 *     tran.commit(); // the database COMMIT
 * }
 * }</pre></td>
 *     <td>The nested {@code commit()} issues no database {@code COMMIT}: it completes the scope and restores the enclosing
 *         scope's isolation level, and the status stays {@link Status#ACTIVE}. The outermost scope decides the
 *         outcome. If the driver rejects restoring the enclosing scope's isolation level, the nested {@code commit()} throws
 *         {@link UncheckedSQLException} and the scope stays open, so its {@code close()} can complete it (which exits it
 *         anyway and marks the transaction rollback-only).</td>
 *   </tr>
 *   <tr>
 *     <td><b>6. Clean up a nested scope without a commit.</b> Its work failed. {@code close()} and the deprecated
 *         {@code rollback()} do the same.
 *       <pre>{@code
 * SqlTransaction nested = JdbcUtil.beginTransaction(dataSource);
 * try {
 *     dao.insert(entity); // throws SQLException
 *     nested.commit();
 * } finally {
 *     nested.rollbackIfNotCommitted();
 * }
 * }</pre></td>
 *     <td>No database {@code ROLLBACK} yet: the transaction is marked rollback-only ({@link Status#MARKED_ROLLBACK}), and
 *         entities that received generated IDs in the transaction so far get their previous IDs back right away.</td>
 *   </tr>
 *   <tr>
 *     <td><b>7. Begin a scope in a transaction marked rollback-only.</b> A nested failure was caught, and the work goes on.
 *       <pre>{@code
 * // the transaction is rollback-only (row 6)
 * try (SqlTransaction another = JdbcUtil.beginTransaction(dataSource)) {
 *     dao.insert(otherEntity);
 *     another.commit();
 * }
 * }</pre></td>
 *     <td>Accepted: the scope joins the transaction as any nested scope does, its {@code commit()} completes the scope only,
 *         and its work is rolled back with the transaction.</td>
 *   </tr>
 *   <tr>
 *     <td><b>8. Commit the outermost scope of a transaction marked rollback-only.</b>
 *       <pre>{@code
 * try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
 *     try (SqlTransaction nested = JdbcUtil.beginTransaction(dataSource)) {
 *         dao.insert(entity);
 *     } // closed without a commit: the transaction is rollback-only
 *
 *     tran.commit(); // throws RollbackOnlyException
 * }
 * }</pre></td>
 *     <td>Database {@code ROLLBACK}, then {@link RollbackOnlyException} (an {@code IllegalStateException}); the status is
 *         {@link Status#ROLLED_BACK}. If that rollback fails, its failure is thrown instead, with the reason the commit
 *         failed attached as a suppressed exception, and the status is {@link Status#FAILED_ROLLBACK}.</td>
 *   </tr>
 *   <tr>
 *     <td><b>9. Swallow a nested failure in {@code callInTransaction}.</b> A batch operation of more than one element runs
 *         in a nested scope.
 *       <pre>{@code
 * int count = JdbcUtil.callInTransaction(dataSource, () -> {
 *     try {
 *         dao.batchInsert(entities); // throws SQLException: fails in a nested scope
 *     } catch (SQLException e) {
 *         // swallowed: the work goes on
 *     }
 *
 *     return dao.count(cond);
 * }); // throws RollbackOnlyException
 * }</pre></td>
 *     <td>The transaction is rolled back, and {@link RollbackOnlyException} is thrown instead of returning the count: the
 *         caller can't take the rolled-back work for committed.</td>
 *   </tr>
 *   <tr>
 *     <td><b>10. Commit the outermost scope twice.</b>
 *       <pre>{@code
 * SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
 * dao.insert(entity);
 * tran.commit();
 * tran.commit(); // throws IllegalStateException
 * }</pre></td>
 *     <td>The second call throws {@code IllegalStateException} (the scope has already been committed): {@code commit()} may
 *         be called only once per scope, as in Spring. The database commits once.</td>
 *   </tr>
 *   <tr>
 *     <td><b>11. Commit a nested scope twice.</b>
 *       <pre>{@code
 * SqlTransaction nested = JdbcUtil.beginTransaction(dataSource);
 * dao.insert(entity);
 * nested.commit();
 * nested.commit(); // throws IllegalStateException
 * }</pre></td>
 *     <td>The second call throws {@code IllegalStateException}. The transaction stays {@link Status#ACTIVE}, and the
 *         enclosing scope is unaffected.</td>
 *   </tr>
 *   <tr>
 *     <td><b>12. Commit a nested scope again after the outermost scope rolled back.</b>
 *       <pre>{@code
 * SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
 * SqlTransaction nested = JdbcUtil.beginTransaction(dataSource);
 * dao.insert(entity);
 * nested.commit();
 * tran.rollbackIfNotCommitted(); // the work is gone
 * nested.commit(); // throws IllegalStateException
 * }</pre></td>
 *     <td>{@code IllegalStateException}, reporting the status {@link Status#ROLLED_BACK}: the call can't suggest that the
 *         scope's work was committed.</td>
 *   </tr>
 *   <tr>
 *     <td><b>13. Commit a scope that was rolled back.</b>
 *       <pre>{@code
 * SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
 * dao.insert(entity);
 * tran.rollbackIfNotCommitted();
 * tran.commit(); // throws IllegalStateException
 * }</pre></td>
 *     <td>{@code IllegalStateException}: the scope has already ended without a commit, and the transaction has been rolled
 *         back.</td>
 *   </tr>
 *   <tr>
 *     <td><b>14. Commit again after a {@code RollbackOnlyException}.</b>
 *       <pre>{@code
 * try {
 *     tran.commit(); // throws RollbackOnlyException: the transaction was marked rollback-only
 * } catch (RollbackOnlyException e) {
 *     tran.commit(); // throws IllegalStateException
 * }
 * }</pre></td>
 *     <td>The second call throws a plain {@code IllegalStateException}, not another {@code RollbackOnlyException}: the
 *         scope has ended without a commit, and the transaction has been rolled back.</td>
 *   </tr>
 *   <tr>
 *     <td><b>15. The database commit fails.</b> The driver's {@code Connection.commit()} throws, possibly after the
 *         database committed.
 *       <pre>{@code
 * SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
 * dao.insert(entity);
 * tran.commit(); // throws UncheckedSQLException
 * }</pre></td>
 *     <td>{@link UncheckedSQLException}. The transaction is rolled back automatically: the status is
 *         {@link Status#ROLLED_BACK}, or {@link Status#FAILED_ROLLBACK} if that rollback fails too (its failure is
 *         suppressed on the commit's). The actions registered to run after the commit run all the same, because the
 *         database may have committed with only the acknowledgement lost. The generated IDs of the transaction's inserts are
 *         put back with the rollback, as a failed commit usually rolled the work back (a constraint checked at commit, a
 *         serialization failure); in the rare case of a lost acknowledgement, the entities lose the IDs of rows that exist.
 *         A later {@code commit()} throws {@code IllegalStateException}.</td>
 *   </tr>
 *   <tr>
 *     <td><b>16. Deprecated {@code rollback()} twice.</b>
 *       <pre>{@code
 * SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
 * dao.insert(entity);
 * tran.rollback();
 * tran.rollback(); // throws IllegalStateException
 * }</pre></td>
 *     <td>The second call throws {@code IllegalStateException} (the scope has already completed), as in Spring; clean up
 *         with {@code rollbackIfNotCommitted()} or {@code close()} instead. The database rolls back once.</td>
 *   </tr>
 *   <tr>
 *     <td><b>17. Deprecated {@code rollback()} after a commit.</b>
 *       <pre>{@code
 * SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
 * dao.insert(entity);
 * tran.commit();
 * tran.rollback(); // throws IllegalStateException
 * }</pre></td>
 *     <td>{@code IllegalStateException}, reporting the status {@link Status#COMMITTED}: nothing is undone.</td>
 *   </tr>
 *   <tr>
 *     <td><b>18. Deprecated {@code rollback()} of a committed nested scope.</b>
 *       <pre>{@code
 * SqlTransaction nested = JdbcUtil.beginTransaction(dataSource);
 * dao.insert(entity);
 * nested.commit();
 * nested.rollback(); // throws IllegalStateException
 * }</pre></td>
 *     <td>{@code IllegalStateException}. The transaction is not marked rollback-only and stays {@link Status#ACTIVE}.</td>
 *   </tr>
 *   <tr>
 *     <td><b>19. Clean up a completed scope.</b> The same after a rollback.
 *       <pre>{@code
 * tran.commit();
 * tran.rollbackIfNotCommitted(); // no-op
 * tran.close(); // no-op
 * tran.close(); // no-op
 * }</pre></td>
 *     <td>No effect, however often they are called, so {@code finally} blocks and try-with-resources stay safe.</td>
 *   </tr>
 *   <tr>
 *     <td><b>20. Complete a scope while a scope opened inside it is still open.</b> The inner scope was never completed,
 *         for example because its code threw and it has no {@code finally} block.
 *       <pre>{@code
 * SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
 * SqlTransaction nested = JdbcUtil.beginTransaction(dataSource);
 * dao.insert(entity); // nested is never completed
 * tran.commit(); // throws RollbackOnlyException
 * }</pre></td>
 *     <td>The inner scope is exited, never having completed, and the transaction is marked rollback-only. An outermost
 *         {@code commit()} then rolls back and throws {@link RollbackOnlyException}; a nested one throws
 *         {@code IllegalStateException} and ends without a commit, leaving the transaction marked rollback-only.</td>
 *   </tr>
 *   <tr>
 *     <td><b>21. Commit a scope that an enclosing scope exited.</b>
 *       <pre>{@code
 * // continues row 20: tran.commit() exited nested
 * nested.commit(); // throws IllegalStateException
 * }</pre></td>
 *     <td>{@code IllegalStateException}: an enclosing scope completed while the scope was still open and exited it, and the
 *         transaction has been rolled back.</td>
 *   </tr>
 *   <tr>
 *     <td><b>22. Clean up or roll back a scope that an enclosing scope exited.</b>
 *       <pre>{@code
 * // continues row 20
 * nested.rollbackIfNotCommitted(); // no-op
 * nested.close(); // no-op
 * nested.rollback(); // throws IllegalStateException
 * }</pre></td>
 *     <td>{@code rollbackIfNotCommitted()} and {@code close()} do nothing; the deprecated {@code rollback()} throws
 *         {@code IllegalStateException} (the scope has already completed).</td>
 *   </tr>
 *   <tr>
 *     <td><b>23. Complete scopes in the order they were opened.</b> An easy mistake with two scopes in one
 *         try-with-resources statement.
 *       <pre>{@code
 * try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
 *         SqlTransaction nested = JdbcUtil.beginTransaction(dataSource)) {
 *     dao.insert(entity);
 *     tran.commit(); // throws RollbackOnlyException: nested is still open
 *     nested.commit();
 * }
 * }</pre></td>
 *     <td>{@code tran.commit()} finds {@code nested} still open, as in row 20: it rolls back and throws
 *         {@link RollbackOnlyException}, and a {@code commit()} of {@code nested} would throw
 *         {@code IllegalStateException}. Complete scopes innermost first: {@code nested.commit()} before
 *         {@code tran.commit()}.</td>
 *   </tr>
 *   <tr>
 *     <td><b>24. Complete a scope on another thread.</b>
 *       <pre>{@code
 * SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
 * dao.insert(entity);
 * executor.submit(() -> tran.commit()); // throws IllegalStateException in the task
 * }</pre></td>
 *     <td>{@code IllegalStateException}: the transaction is bound to the thread that began it. Every completion call
 *         ({@code commit()}, {@code rollback()}, {@code rollbackIfNotCommitted()}, {@code close()}) checks the thread
 *         first, on a completed scope too.</td>
 *   </tr>
 *   <tr>
 *     <td><b>25. Participating: commit the outermost scope.</b>
 *       <pre>{@code
 * transactionTemplate.executeWithoutResult(status -> {
 *     SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
 *     try {
 *         dao.insert(entity);
 *         tran.commit(); // no database COMMIT
 *     } finally {
 *         tran.rollbackIfNotCommitted();
 *     }
 * }); // Spring commits here
 * }</pre></td>
 *     <td>No database {@code COMMIT}; the status is {@link Status#COMMITTED}, and the connection is released to Spring,
 *         which commits the work when its transaction completes. The connection's auto-commit mode and isolation level are
 *         left to Spring (a requested isolation level is ignored).</td>
 *   </tr>
 *   <tr>
 *     <td><b>26. Participating: roll back.</b> The work failed, or the outermost commit found the transaction
 *         rollback-only (row 8).
 *       <pre>{@code
 * transactionTemplate.executeWithoutResult(status -> {
 *     try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
 *         dao.insert(entity);
 *         // no commit: close() rolls back
 *     }
 * }); // throws UnexpectedRollbackException
 * }</pre></td>
 *     <td>No database {@code ROLLBACK}: the Spring transaction is marked rollback-only, and the connection is released to
 *         Spring, which rolls back when its transaction completes; committing it throws Spring's
 *         {@code UnexpectedRollbackException}. Generated IDs are put back once the mark is set (with a Spring older than 6.2,
 *         once the Spring transaction has rolled back).</td>
 *   </tr>
 *   <tr>
 *     <td><b>27. Participating: the rollback can't mark the Spring transaction.</b> Here the scope was left open past
 *         the Spring transaction, which committed its work; a suspended JPA transaction that can no longer be identified
 *         fails the same way.
 *       <pre>{@code
 * SqlTransaction[] leaked = new SqlTransaction[1];
 *
 * transactionTemplate.executeWithoutResult(status -> {
 *     leaked[0] = JdbcUtil.beginTransaction(dataSource);
 *     dao.insert(entity);
 * }); // Spring commits the row
 *
 * leaked[0].rollbackIfNotCommitted(); // throws IllegalStateException
 * }</pre></td>
 *     <td>{@code IllegalStateException}; the status is {@link Status#FAILED_ROLLBACK}. Generated IDs are not put back but
 *         left to the Spring transaction's outcome (kept here, as the row is committed). The scope is completed: a later
 *         {@code close()} does nothing. A {@code leaked[0].commit()} in place of the rollback throws
 *         {@code IllegalStateException} too, and ends {@link Status#FAILED_COMMIT}: Spring decided the outcome of the work
 *         without it. Both fail the same way in the Spring transaction's {@code afterCommit} or {@code afterCompletion}
 *         callbacks, where Spring still binds its connection. The required {@link SpringTransactionTracker} follows the
 *         actual owner from its beforeCommit/beforeRollback notification, including with disabled synchronization, JPA,
 *         or late synchronization registration. An inner transaction completing does not complete an active outer owner.</td>
 *   </tr>
 *   <tr>
 *     <td><b>28. Participating: complete the scope while Spring runs another transaction on the data source.</b>
 *       <pre>{@code
 * transactionTemplate.executeWithoutResult(outer -> {
 *     SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
 *     dao.insert(entity);
 *
 *     requiresNewTemplate.executeWithoutResult(inner -> {
 *         tran.rollbackIfNotCommitted(); // inside REQUIRES_NEW
 *     });
 * }); // throws UnexpectedRollbackException
 * }</pre></td>
 *     <td>The Spring transaction {@code tran} participates in is marked rollback-only, not the {@code REQUIRES_NEW} one
 *         Spring runs meanwhile, and {@code tran}'s connection is left to it. The inner transaction commits
 *         normally.</td>
 *   </tr>
 *   <tr>
 *     <td><b>29. Participating: repeat a completion call.</b>
 *       <pre>{@code
 * transactionTemplate.executeWithoutResult(status -> {
 *     SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
 *     dao.insert(entity);
 *     tran.commit();
 *     tran.commit(); // throws IllegalStateException
 * });
 * }</pre></td>
 *     <td>The same rules as rows 10 to 19: a repeated {@code commit()} or deprecated {@code rollback()} throws
 *         {@code IllegalStateException}, and {@code rollbackIfNotCommitted()} and {@code close()} do nothing. An exception
 *         that leaves Spring's callback rolls the Spring transaction back.</td>
 *   </tr>
 * </table>
 * <p>Row 23 is the one most likely to surprise: completing scopes in the order they were opened fails, because the outer
 * scope finds the inner one still open. Give each scope its own try-with-resources statement (or {@code try}/{@code finally}
 * block), ending with its {@code commit()} in that block.</p>
 *
 * <p><b>Rules of thumb</b></p>
 * <ol>
 *   <li>Complete every scope exactly once, innermost first: try-with-resources, or {@code commit()} in the {@code try}
 *       block and {@code rollbackIfNotCommitted()} in the {@code finally} block.</li>
 *   <li>Never close {@link #connection()} yourself, and never use the transaction on another thread.</li>
 *   <li>Prefer {@code JdbcUtil.callInTransaction}/{@code runInTransaction} for a unit of work, and
 *       {@code @Transactional(propagation = REQUIRED)} for DAO methods that must be atomic.</li>
 *   <li>Use {@code JdbcUtil.callOutsideTransaction}/{@code runOutsideTransaction} for work that must commit regardless of
 *       the transaction's outcome.</li>
 * </ol>
 *
 * @see Status
 * @see JdbcUtil#beginTransaction(javax.sql.DataSource)
 * @see JdbcUtil#beginTransaction(javax.sql.DataSource, IsolationLevel)
 * @see JdbcUtil#callInTransaction(javax.sql.DataSource, Throwables.Callable)
 * @see JdbcUtil#runInTransaction(javax.sql.DataSource, Throwables.Runnable)
 * @see JdbcUtil#isInTransaction(javax.sql.DataSource)
 * @see com.landawn.abacus.jdbc.annotation.Transactional
 */
@SuppressWarnings("resource")
public final class SqlTransaction implements AutoCloseable {

    /**
     * The state this scope shares with the other scopes of its transaction.
     */
    private final TransactionContext _context; //NOSONAR

    /**
     * Whether this scope opened the transaction: only the outermost scope commits or rolls back the connection.
     */
    private final boolean _outermost; //NOSONAR

    /**
     * The isolation level this scope runs at ({@link IsolationLevel#DEFAULT}: the connection's original level).
     */
    private final IsolationLevel _isolationLevel; //NOSONAR

    /**
     * Whether this scope has completed: committed, rolled back, ended by {@link #rollbackIfNotCommitted()} or {@link #close()},
     * or exited because an enclosing scope completed while it was still open.
     */
    private boolean _completed; //NOSONAR

    /**
     * Whether this scope was completed by an enclosing scope that completed while this one was still open (see
     * {@link TransactionContext}): its work can't be committed, so a later {@link #commit()} of this scope throws.
     */
    private boolean _exitedByEnclosingScope; //NOSONAR

    /**
     * Whether this nested scope was completed by its own successful {@link #commit()}, which a repeated commit's failure then
     * says (the commit of a scope that ended otherwise says so instead). The outermost scope tells by the transaction's status.
     */
    private boolean _committed; //NOSONAR

    /**
     * Constructs a scope of {@code context}; called by {@link TransactionContext#enterScope(IsolationLevel)} only.
     *
     * @param context the transaction this scope belongs to
     * @param outermost whether this scope opened the transaction
     * @param isolationLevel the effective isolation level of this scope
     */
    SqlTransaction(final TransactionContext context, final boolean outermost, final IsolationLevel isolationLevel) {
        _context = context;
        _outermost = outermost;
        _isolationLevel = isolationLevel;
    }

    /**
     * Returns the unique identifier of the transaction this scope belongs to. All scopes of one transaction (the outermost
     * scope and the nested scopes opened inside it) report the same identifier, which also appears in this class's log
     * messages.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
     *     logger.info("Starting transaction: {}", tran.id());
     * }
     * }</pre>
     *
     * @return the unique transaction identifier, never {@code null}
     */
    public String id() {
        return _context.id();
    }

    /**
     * Returns the JDBC connection of the transaction. It is shared by all scopes of the transaction and must not be closed
     * manually: the outermost scope releases it when the transaction completes.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
     *     Connection conn = tran.connection();
     *     try (PreparedStatement stmt = conn.prepareStatement("UPDATE users SET active = 0 WHERE id = ?")) {
     *         stmt.setLong(1, userId);
     *         stmt.executeUpdate();
     *     }
     *     tran.commit();
     * }
     * }</pre>
     *
     * @return the JDBC connection used by the transaction
     * @throws IllegalStateException if called from a thread other than the transaction's owner thread
     */
    public Connection connection() throws IllegalStateException {
        return _context.connection();
    }

    /**
     * Returns the isolation level of this scope: the level requested when it was begun or, for a nested scope that requested
     * {@link IsolationLevel#DEFAULT}, the level of the scope it is nested in. A transaction participating in a Spring-managed
     * transaction always reports {@link IsolationLevel#DEFAULT}: it runs at the isolation level of the Spring transaction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE)) {
     *     assert tran.isolationLevel() == IsolationLevel.SERIALIZABLE;
     * }
     * }</pre>
     *
     * @return the isolation level of this scope, never {@code null}
     * @see IsolationLevel
     */
    public IsolationLevel isolationLevel() {
        return _isolationLevel;
    }

    /**
     * Returns the current status of the transaction this scope belongs to: {@link Status#ACTIVE} until the transaction is
     * marked rollback-only ({@link Status#MARKED_ROLLBACK}) or the outermost scope completes.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
     *     if (tran.status() == SqlTransaction.Status.ACTIVE) {
     *         // The transaction can still be committed
     *     }
     * }
     * }</pre>
     *
     * @return the current transaction status, never {@code null}
     * @see Status
     */
    public Status status() {
        return _context.status();
    }

    /**
     * Checks whether the transaction this scope belongs to is active, i.e. its status is {@link Status#ACTIVE}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
     *     if (tran.isActive()) {
     *         performDatabaseOperations();
     *     }
     * }
     * }</pre>
     *
     * @return {@code true} if the transaction is active, {@code false} otherwise
     */
    public boolean isActive() {
        return _context.status() == Status.ACTIVE;
    }

    /**
     * Commits this scope.
     *
     * <p>A nested scope's commit only completes the scope: the database {@code COMMIT} is issued when the outermost scope
     * commits. If the transaction has been marked rollback-only (a nested scope rolled back, typically because its failure
     * was caught and the work went on), the outermost commit rolls back instead and throws {@link RollbackOnlyException}:
     * none of the transaction's work was committed. If the rollback fails, its failure is thrown instead (such as the
     * {@link UncheckedSQLException} of the rollback), as is an unchecked failure of the connection cleanup after the
     * rollback; the reason the commit failed is attached to it as a suppressed exception (a {@code RollbackOnlyException}
     * once the transaction has been rolled back, an {@code IllegalStateException} otherwise).</p>
     *
     * <p>Scopes opened inside this one that are still open when it commits were never completed (for example, a nested scope
     * whose code threw before its {@code commit()} and has no {@code finally} block): they are exited, the transaction is
     * marked rollback-only, and this method throws: an outermost scope rolls the transaction back and throws
     * {@link RollbackOnlyException} (or the failure of the rollback, as above), a nested scope throws
     * {@link IllegalStateException}. If restoring an isolation level while exiting such a scope fails with an unchecked
     * exception, that exception is thrown instead (by an outermost scope, after the same rollback).</p>
     *
     * <p>If the driver fails to restore the enclosing scope's isolation level when this nested scope exits, the failure
     * propagates (an SQL error as an {@link UncheckedSQLException}, a runtime exception or an error as it is) and this scope
     * stays open, so its {@link #rollbackIfNotCommitted()} can complete it.</p>
     *
     * <p>When the outermost scope actually commits, the status becomes {@link Status#COMMITTED} on success. On failure the
     * transaction is rolled back automatically, leaving the status {@link Status#ROLLED_BACK} (or
     * {@link Status#FAILED_ROLLBACK} if that rollback also fails). Then the connection's original auto-commit mode and
     * isolation level are restored and the connection is released (after a failed rollback it is aborted and released). For a
     * transaction participating in a Spring-managed transaction, the outermost commit issues no database {@code COMMIT}: it
     * completes the transaction and releases the connection to Spring, which commits the work when its transaction
     * completes; a rollback-only one marks the Spring transaction rollback-only and throws {@link RollbackOnlyException}, or the
     * failure of the mark (the status is then {@link Status#FAILED_ROLLBACK}; see {@link #rollbackIfNotCommitted()}). If the
     * Spring transaction has completed already (the scope was left open past it), or runs its {@code afterCommit} or
     * {@code afterCompletion} callbacks (the scope was left open into them), the commit throws {@link IllegalStateException}
     * and the status becomes {@link Status#FAILED_COMMIT}: Spring decided the outcome of the work without it (see row 27 of
     * the completion scenarios for what is detected).</p>
     *
     * <p>After a successful database commit, this library runs its actions registered to run after the commit (such as the
     * invalidation of DAO result caches). A failing one is logged; an {@link Error} thrown by one propagates from this method,
     * once the connection has been cleaned up, although the work has been committed (the status is {@link Status#COMMITTED}).</p>
     *
     * <p>A commit of a scope that has completed throws {@link IllegalStateException}. {@code commit()} may be called only once
     * per scope, as in Spring: a repeated commit after a successful one throws too (it used to be ignored, and for a nested
     * scope returned normally also after the transaction had rolled back the scope's work). The work of a scope that has
     * ended otherwise can't be committed: a scope that was rolled back (by
     * {@link #rollbackIfNotCommitted()}, {@link #close()} or {@link #rollback()}), whose commit failed (for example with
     * {@link RollbackOnlyException}), or that an enclosing scope exited because it completed while this one was still open.
     * The paired {@link #rollbackIfNotCommitted()} (or {@link #close()}) of a completed scope is a no-op.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
     *     dao.save(entity);
     *     dao.update(anotherEntity);
     *     tran.commit();
     * }
     * }</pre>
     *
     * @throws IllegalStateException if called from a thread other than the transaction's owner thread, if a scope opened
     *         inside this one was never completed, if this scope has already completed (committed, rolled back, its commit
     *         failed, or an enclosing scope completed and exited it while it was still open), if the outermost commit of a
     *         participating transaction marked rollback-only cannot mark the Spring transaction rollback-only (see
     *         {@link #rollbackIfNotCommitted()}), or if the Spring-managed transaction a participating transaction participates in
     *         has completed, or begun to complete, before its outermost commit
     * @throws RollbackOnlyException if this is the outermost scope and the transaction was marked rollback-only, or a scope
     *         opened inside this one was never completed: the transaction has been rolled back (a participating transaction:
     *         the Spring-managed transaction has been marked rollback-only, so Spring rolls back the work)
     * @throws UncheckedSQLException if an SQL error occurs while restoring the enclosing scope's isolation level (the scope
     *         then stays open, so its {@link #rollbackIfNotCommitted()} can complete it), committing, or rolling back a
     *         rollback-only transaction. A failed database commit triggers an automatic rollback; any rollback failure is
     *         suppressed on the commit exception.
     */
    public void commit() throws IllegalStateException, UncheckedSQLException {
        _context.commit(this);
    }

    /**
     * Rolls back this scope.
     *
     * <p>A nested scope's rollback marks the transaction rollback-only ({@link Status#MARKED_ROLLBACK}); the database
     * {@code ROLLBACK} is issued when the outermost scope completes, whether it commits or rolls back. The outermost rollback
     * sets the status to {@link Status#ROLLED_BACK} (or {@link Status#FAILED_ROLLBACK} if the rollback fails), restores the
     * connection's original state and releases it (after a failed rollback, aborts and releases it without restoring its
     * state). For a participating transaction, see {@link #rollbackIfNotCommitted()}.
     * The scope is always exited, as by {@link #rollbackIfNotCommitted()}: if the enclosing scope's isolation level cannot be
     * restored, the transaction stays marked rollback-only; an {@link SQLException} from the connection is logged, while a
     * {@link RuntimeException} or an {@link Error} is thrown after the scope has been exited (it used to leave a nested scope
     * open, and the transaction {@link Status#ACTIVE}, after an explicit rollback). Scopes opened inside this one that are
     * still open are exited first; an unchecked failure while exiting them is thrown after this scope has been rolled
     * back.</p>
     *
     * <p>A rollback of a scope that has already completed (committed, rolled back, or exited by an enclosing scope) throws
     * {@link IllegalStateException}, as in Spring and as {@link #commit()} does (it used to be logged and ignored); the
     * cleanup calls {@link #rollbackIfNotCommitted()} and {@link #close()} stay no-ops on a completed scope.</p>
     *
     * @throws IllegalStateException if called from a thread other than the transaction's owner thread, if this scope has
     *         already completed, or if the outermost rollback of a participating transaction cannot mark the Spring
     *         transaction rollback-only (see {@link #rollbackIfNotCommitted()}; the status is then {@link Status#FAILED_ROLLBACK})
     * @throws UncheckedSQLException if an SQL error occurs during the outermost rollback
     * @throws RuntimeException if marking the Spring transaction rollback-only fails with another exception (an {@link Error}
     *         from the mark propagates as well); the status is then {@link Status#FAILED_ROLLBACK}
     * @deprecated replaced by {@link #rollbackIfNotCommitted()}
     */
    @Deprecated
    public void rollback() throws IllegalStateException, UncheckedSQLException {
        _context.rollback(this);
    }

    /**
     * Ends this scope, rolling it back unless it has completed: a no-op after {@link #commit()} or {@link #rollback()} of this
     * scope, and on any further call. Call it from the {@code finally} block paired with the scope (or use try-with-resources:
     * {@link #close()} does the same).
     *
     * <p>A nested scope that is ended this way marks the transaction {@link Status#MARKED_ROLLBACK}; the database
     * {@code ROLLBACK} is issued when the outermost scope completes. The scope is always exited: if the enclosing scope's
     * isolation level cannot be restored on the connection, the transaction stays marked rollback-only; an
     * {@link SQLException} from the connection is logged, while a {@link RuntimeException} or an {@link Error} is propagated
     * after the scope has been exited. Scopes opened inside this one that are still open are exited first.</p>
     *
     * <p>For a transaction participating in a Spring-managed transaction, the outermost rollback issues no database
     * {@code ROLLBACK}; it marks the Spring transaction rollback-only and releases the connection to Spring. If the Spring
     * transaction cannot be marked (no connection holder is bound to the data source any more, a JPA transaction that owns
     * the connection refuses the mark or can no longer be identified, the completion of the Spring transaction has started so
     * that the mark can't change its outcome any more, see {@link #commit()}, or the mark throws), the rollback fails with an
     * {@link IllegalStateException} (or the mark's own failure), the status becomes {@link Status#FAILED_ROLLBACK} (Spring
     * may still commit the work), and the in-memory changes tied to the work (such as generated IDs written back to
     * entities) are not undone but left to the Spring transaction's outcome.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     dao.save(entity);
     *     tran.commit();
     * } finally {
     *     tran.rollbackIfNotCommitted(); // no-op after the commit
     * }
     * }</pre>
     *
     * @throws IllegalStateException if called from a thread other than the transaction's owner thread, or if the outermost
     *         rollback of a participating transaction cannot mark the Spring transaction rollback-only (see above); the status
     *         is then {@link Status#FAILED_ROLLBACK}
     * @throws UncheckedSQLException if an SQL error occurs during the outermost rollback
     * @throws RuntimeException if marking the Spring transaction rollback-only fails with another exception (an {@link Error}
     *         from the mark propagates as well); the status is then {@link Status#FAILED_ROLLBACK}
     */
    public void rollbackIfNotCommitted() throws IllegalStateException, UncheckedSQLException {
        _context.rollbackIfNotCommitted(this);
    }

    /**
     * Executes {@code cmd} outside of this transaction: the transaction is unregistered from the current thread while
     * {@code cmd} runs, so operations on the data source inside it acquire their own connections and commit independently,
     * and it is registered again afterwards (if it is still active or marked rollback-only). This library's participation in
     * Spring-managed transactions is also suspended for the transaction's data source while {@code cmd} runs, so those
     * connections come neither from a Spring transaction this transaction participates in nor, while Spring's transaction
     * synchronization is active, from Spring's binding of this transaction's own connection to the thread. Work on other
     * data sources inside {@code cmd} still joins their Spring-managed transactions.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
     *     dao.update(entity);
     *     tran.runOutsideTransaction(() -> auditDao.insert(auditRecord)); // committed even if tran rolls back
     *     tran.commit();
     * }
     * }</pre>
     *
     * @param <E> the exception type that may be thrown during execution
     * @param cmd the {@code Runnable} to be executed outside of this transaction, must not be {@code null}
     * @throws IllegalStateException if called from a thread other than the transaction's owner thread, or if, after
     *         {@code cmd} completes normally, another transaction has been opened on this thread for the same data source and
     *         was not closed: it is rolled back, as by {@link #rollbackIfNotCommitted()} of its outermost scope, and this
     *         transaction registered again (if {@code cmd} throws, that condition is attached to its exception as a suppressed
     *         exception)
     * @throws IllegalArgumentException if {@code cmd} is {@code null}
     * @throws E if the {@code Runnable} throws an exception
     */
    public <E extends Throwable> void runOutsideTransaction(final Throwables.Runnable<E> cmd) throws IllegalStateException, IllegalArgumentException, E {
        _context.runOutsideTransaction(cmd);
    }

    /**
     * Executes {@code cmd} outside of this transaction and returns its result; see
     * {@link #runOutsideTransaction(Throwables.Runnable)}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
     *     dao.save(entity);
     *     Config committed = tran.callOutsideTransaction(() -> configDao.getOrNull(configKey)); // reads committed state
     *     tran.commit();
     * }
     * }</pre>
     *
     * @param <R> the result type returned by the operation
     * @param <E> the exception type that may be thrown during execution
     * @param cmd the {@code Callable} to be executed outside of this transaction, must not be {@code null}
     * @return the result returned by the {@code Callable}
     * @throws IllegalStateException see {@link #runOutsideTransaction(Throwables.Runnable)}
     * @throws IllegalArgumentException if {@code cmd} is {@code null}
     * @throws E if the {@code Callable} throws an exception
     */
    public <R, E extends Throwable> R callOutsideTransaction(final Throwables.Callable<? extends R, E> cmd)
            throws IllegalStateException, IllegalArgumentException, E {
        return _context.callOutsideTransaction(cmd);
    }

    /**
     * Ends this scope by calling {@link #rollbackIfNotCommitted()}, so the scope can be used in a try-with-resources block:
     * a no-op after {@link #commit()}, and on any further call.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
     *     dao.save(entity);
     *     tran.commit();
     * } // close() rolls back if commit() was not reached
     * }</pre>
     *
     * @throws IllegalStateException if called from a thread other than the transaction's owner thread, or if the outermost
     *         rollback of a participating transaction cannot mark the Spring transaction rollback-only (see
     *         {@link #rollbackIfNotCommitted()})
     * @throws UncheckedSQLException if an SQL error occurs during the outermost rollback
     * @throws RuntimeException if marking the Spring transaction rollback-only fails with another exception (an {@link Error}
     *         from the mark propagates as well); the status is then {@link Status#FAILED_ROLLBACK}
     * @see #rollbackIfNotCommitted()
     */
    @Override
    public void close() throws IllegalStateException, UncheckedSQLException {
        rollbackIfNotCommitted();
    }

    /**
     * Returns the transaction this scope belongs to.
     *
     * @return the shared transaction state
     */
    TransactionContext context() {
        return _context;
    }

    /**
     * Returns whether this scope opened the transaction.
     *
     * @return {@code true} for the outermost scope
     */
    boolean isOutermost() {
        return _outermost;
    }

    boolean isCompleted() {
        return _completed;
    }

    void markCompleted() {
        _completed = true;
    }

    boolean isCommitted() {
        return _committed;
    }

    /**
     * Marks this nested scope completed by its own successful commit.
     */
    void markCommitted() {
        _completed = true;
        _committed = true;
    }

    boolean isExitedByEnclosingScope() {
        return _exitedByEnclosingScope;
    }

    /**
     * Marks this scope completed by an enclosing scope that completed while this one was still open.
     */
    void markExitedByEnclosingScope() {
        _completed = true;
        _exitedByEnclosingScope = true;
    }

    /**
     * Returns a string representation of this scope, including the transaction's identifier.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
     *     logger.debug("Transaction details: {}", tran); // SqlTransaction={id=..., outermost=true, completed=false}
     * }
     * }</pre>
     *
     * @return a string representation of this scope
     */
    @Override
    public String toString() {
        return "SqlTransaction={id=" + _context.id() + ", outermost=" + _outermost + ", completed=" + _completed + "}";
    }

    /**
     * The status of a transaction, shared by all its scopes (see {@link SqlTransaction#status()}). It changes from
     * {@link #ACTIVE} only when the transaction is marked rollback-only or when its outermost scope completes:
     * <pre>
     * ACTIVE → MARKED_ROLLBACK (a nested scope rolled back or was never completed, or the enclosing scope's isolation level
     *                          could not be restored when a nested scope exited)
     * ACTIVE → COMMITTED (the outermost commit() succeeds)
     * ACTIVE → FAILED_COMMIT → ROLLED_BACK / FAILED_ROLLBACK (a transaction of its own: the database commit fails, and is rolled back automatically)
     * ACTIVE → FAILED_COMMIT (participating: the Spring-managed transaction had completed before the outermost commit; final, nothing is rolled back)
     * ACTIVE / MARKED_ROLLBACK → ROLLED_BACK / FAILED_ROLLBACK (the outermost scope rolls back, or its commit() finds the
     *                                                          transaction marked rollback-only)
     * </pre>
     *
     * <p>For a transaction that participates in a Spring-managed transaction, {@code COMMITTED} and {@code ROLLED_BACK}
     * describe the completion of its scope (the Spring transaction decides the outcome of the work), {@code FAILED_ROLLBACK}
     * means that the Spring transaction could not be marked rollback-only, and {@code FAILED_COMMIT}, final for such a
     * transaction, that the Spring transaction had completed (or begun to complete) before the outermost commit, deciding the
     * outcome of the work without it.</p>
     */
    public enum Status {
        /**
         * The transaction is open and not marked rollback-only: the status from its beginning until it is marked rollback-only
         * or its outermost scope completes.
         */
        ACTIVE,

        /**
         * The transaction can only end rolled back: a nested scope rolled back (for example, its work threw and its cleanup call
         * ended it), a scope completed while a scope opened inside it was still open, or the exit of a nested scope could not
         * restore the enclosing scope's isolation level. Scopes begun meanwhile join the transaction, and their work is rolled
         * back with it; the outermost scope rolls back even if it calls {@link SqlTransaction#commit()}, which then throws
         * {@link RollbackOnlyException} (or the failure of that rollback).
         */
        MARKED_ROLLBACK,

        /**
         * The outermost scope committed the transaction: the database {@code COMMIT} succeeded. For a transaction that
         * participates in a Spring-managed transaction, its outermost scope completed without a database {@code COMMIT}, and
         * Spring commits the work when its own transaction completes.
         */
        COMMITTED,

        /**
         * The outermost commit did not complete. For a transaction of this library's own, the status is transient, never
         * reported once {@link SqlTransaction#commit()} has returned or thrown: a failed database commit is rolled back
         * automatically, ending {@link #ROLLED_BACK} or {@link #FAILED_ROLLBACK}. For a transaction that participates in a
         * Spring-managed transaction, it is final: the Spring transaction had completed (or begun to complete, in its
         * completion callbacks) before the outermost commit, and decided the outcome of the work without it, so nothing is
         * rolled back.
         */
        FAILED_COMMIT,

        /**
         * The outermost scope rolled the transaction back: its {@link SqlTransaction#rollbackIfNotCommitted()} (or
         * {@link SqlTransaction#close()}, or the deprecated {@link SqlTransaction#rollback()}) ran without a commit, its commit
         * found the transaction marked rollback-only (or a scope opened inside it still open), or its database commit failed
         * and was rolled back automatically. For a transaction that participates in a Spring-managed transaction, the Spring
         * transaction has been marked rollback-only instead, and Spring rolls the work back when its transaction completes.
         */
        ROLLED_BACK,

        /**
         * The rollback failed: the database {@code ROLLBACK} threw, and the connection was aborted and released without
         * restoring its auto-commit mode and isolation level. Manual intervention may be required to restore database
         * consistency. For a transaction that participates in a Spring-managed transaction, the Spring transaction could not
         * be marked rollback-only, so Spring may still commit the work.
         */
        FAILED_ROLLBACK
    }

    /**
     * Enumeration representing the creator of a transaction.
     * This is used internally to track and manage transactions by their origin.
     */
    enum CreatedBy {
        /**
         * Transaction created by JdbcUtil for general database operations.
         */
        JDBC_UTIL
    }
}
