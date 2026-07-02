/*
 * Copyright (c) 2021, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.landawn.abacus.jdbc.dao;

import java.sql.SQLException;
import java.util.Collection;
import java.util.concurrent.Executor;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.annotation.Internal;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.NamedQuery;
import com.landawn.abacus.jdbc.PreparedQuery;
import com.landawn.abacus.jdbc.cs;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.query.SqlMapper;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Throwables;

/**
 * Infrastructure root of the DAO capability hierarchy: the shared accessors
 * ({@code dataSource()}, {@code sqlMapper()}, {@code targetEntityClass()}, {@code targetTableName()},
 * {@code executor()}) and the {@code prepareQuery}/{@code prepareNamedQuery}
 * statement builders that every read and write capability relies on.
 *
 * <p>Contains no actual database read or write operation; those live in {@link ReadOps}, {@link InsertOps},
 * {@link UpdateOps} and {@link DeleteOps}, which all extend this interface.</p>
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the self-referencing DAO type
 * @see ReadOps
 * @see Dao
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
public sealed interface DaoBase<T, TD extends DaoBase<T, TD>> permits ReadOps, InsertOps, UpdateOps, DeleteOps, UncheckedDaoBase {

    /**
     * Retrieves the underlying data source used by this DAO for database connections.
     * This data source is used for all database operations performed by this DAO instance.
     *
     * @return the data source configured for this DAO
     */
    @NonDBOperation
    javax.sql.DataSource dataSource();

    /**
     * Retrieves the {@code SqlMapper} instance configured for this DAO.
     * The SqlMapper provides SQL query templates that can be referenced by name.
     * If no SqlMapper is configured, an empty SqlMapper instance will be returned.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SqlMapper sqlMapper = dao.sqlMapper();
     * ParsedSql query = sqlMapper.get("findUserByEmail");
     * }</pre>
     *
     * @return the SqlMapper instance, never {@code null}
     */
    @NonDBOperation
    SqlMapper sqlMapper();

    /**
     * Retrieves the class object representing the entity type managed by this DAO.
     * This is used internally for reflection-based operations.
     *
     * @return the class of the target entity type {@code T}
     * @deprecated for internal framework use only; not intended to be called by application code.
     */
    @Deprecated
    @NonDBOperation
    @Internal
    Class<T> targetEntityClass();

    /**
     * Retrieves the name of the database table associated with the entity type.
     * This is typically derived from the entity class name or specified via annotations.
     *
     * @return the name of the target table
     * @deprecated for internal framework use only; not intended to be called by application code.
     */
    @Deprecated
    @NonDBOperation
    @Internal
    String targetTableName();

    /**
     * Retrieves the executor used for asynchronous operations.
     * This executor is used when async methods are called without specifying a custom executor.
     *
     * @return the default executor for asynchronous operations
     * @deprecated for internal framework use only; not intended to be called by application code.
     */
    @Deprecated
    @NonDBOperation
    @Internal
    Executor executor();

    /**
     * Creates a PreparedQuery for the specified SQL query string.
     * The query can be any valid SQL statement (SELECT, INSERT, UPDATE, DELETE, etc.),
     * unless this DAO is read-only (SELECT only) or no-update (SELECT/INSERT only).
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * PreparedQuery query = dao.prepareQuery("SELECT * FROM users WHERE age > ?");
     * List<User> users = query.setInt(1, 18).list(User.class);
     * }</pre>
     *
     * @param sql the SQL query string
     * @return a PreparedQuery instance for the specified query
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if invoked on a read-only DAO with non-SELECT SQL,
     *                                       or on a no-update DAO with SQL other than SELECT/INSERT
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final String sql) throws SQLException {
        return JdbcUtil.prepareQuery(dataSource(), sql);
    }

    /**
     * Creates a SELECT query based on the specified condition.
     * All columns from the entity table will be selected.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * PreparedQuery query = dao.prepareQuery(Filters.eq("status", "ACTIVE"));
     * List<User> activeUsers = query.list(User.class);
     * }</pre>
     *
     * @param cond the condition appended to the generated SELECT statement
     *             (may include {@code WHERE}, {@code ORDER BY}, {@code LIMIT}, etc.)
     * @return a PreparedQuery instance for the SELECT statement
     * @throws SQLException if a database access error occurs
     * @see Filters
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final Condition cond) throws SQLException {
        return prepareQuery(null, cond);
    }

    /**
     * Creates a SELECT query for specific columns based on the specified condition.
     * Only the specified properties will be included in the SELECT clause.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * PreparedQuery query = dao.prepareQuery(
     *     Arrays.asList("id", "name", "email"),
     *     Filters.eq("status", "ACTIVE")
     * );
     * }</pre>
     *
     * @param selectPropNames the property names to select, or {@code null} to select all
     * @param cond the condition appended to the generated SELECT statement
     *             (may include {@code WHERE}, {@code ORDER BY}, {@code LIMIT}, etc.)
     * @return a PreparedQuery instance
     * @throws IllegalArgumentException if {@code cond} is {@code null}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    PreparedQuery prepareQuery(final Collection<String> selectPropNames, final Condition cond) throws SQLException;

    /**
     * Creates a PreparedQuery optimized for queries that return large result sets.
     * This configures the statement to use cursor-based fetching for better memory efficiency.
     *
     * @param sql the SQL query string
     * @return a PreparedQuery configured for large results
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if invoked on a read-only DAO with non-SELECT SQL,
     *                                       or on a no-update DAO with SQL other than SELECT/INSERT
     * @see JdbcUtil#prepareQueryForLargeResult(javax.sql.DataSource, String)
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQueryForLargeResult(final String sql) throws SQLException {
        return JdbcUtil.prepareQueryForLargeResult(dataSource(), sql);
    }

    /**
     * Creates a SELECT query optimized for large result sets based on the specified condition.
     * All columns will be selected with cursor-based fetching enabled.
     *
     * @param cond the condition appended to the generated SELECT statement
     *             (may include {@code WHERE}, {@code ORDER BY}, {@code LIMIT}, etc.)
     * @return a PreparedQuery configured for large results
     * @throws SQLException if a database access error occurs
     * @see JdbcUtil#prepareQueryForLargeResult(javax.sql.DataSource, String)
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQueryForLargeResult(final Condition cond) throws SQLException {
        return prepareQueryForLargeResult(null, cond);
    }

    /**
     * Creates a SELECT query for specific columns optimized for large result sets.
     * Combines column selection with cursor-based fetching for memory-efficient processing.
     *
     * @param selectPropNames the property names to select, or {@code null} to select all
     * @param cond the condition appended to the generated SELECT statement
     *             (may include {@code WHERE}, {@code ORDER BY}, {@code LIMIT}, etc.)
     * @return a PreparedQuery configured for large results
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQueryForLargeResult(final Collection<String> selectPropNames, final Condition cond) throws SQLException {
        return prepareQuery(selectPropNames, cond).configureStatement(DaoUtil.stmtSetterForBigQueryResult);
    }

    /**
     * Creates a NamedQuery for the specified named SQL query string.
     * Named queries use parameter names (e.g., :name) instead of positional parameters.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * NamedQuery query = dao.prepareNamedQuery(
     *     "SELECT * FROM users WHERE age > :minAge AND status = :status"
     * );
     * List<User> users = query.setInt("minAge", 18)
     *                         .setString("status", "ACTIVE")
     *                         .list(User.class);
     * }</pre>
     *
     * @param namedSql the named SQL query string with :paramName placeholders
     * @return a NamedQuery instance
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if invoked on a read-only DAO with non-SELECT SQL,
     *                                       or on a no-update DAO with SQL other than SELECT/INSERT
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final String namedSql) throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedSql);
    }

    /**
     * Creates a NamedQuery from a pre-parsed SQL object.
     * This is more efficient when reusing the same query multiple times.
     *
     * @param namedSql the pre-parsed named query
     * @return a NamedQuery instance
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if invoked on a read-only DAO with non-SELECT SQL,
     *                                       or on a no-update DAO with SQL other than SELECT/INSERT
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final ParsedSql namedSql) throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedSql);
    }

    /**
     * Creates a named SELECT query based on the specified condition.
     * All columns will be selected using named parameter syntax.
     *
     * @param cond the condition appended to the generated SELECT statement
     *             (may include {@code WHERE}, {@code ORDER BY}, {@code LIMIT}, etc.)
     * @return a NamedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final Condition cond) throws SQLException {
        return prepareNamedQuery(null, cond);
    }

    /**
     * Creates a named SELECT query for specific columns based on the specified condition.
     * Generates a named-parameter query with the selected columns and the supplied condition appended.
     *
     * @param selectPropNames the property names to select, or {@code null} to select all
     * @param cond the condition appended to the generated SELECT statement
     *             (may include {@code WHERE}, {@code ORDER BY}, {@code LIMIT}, etc.)
     * @return a NamedQuery instance
     * @throws IllegalArgumentException if {@code cond} is {@code null}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    NamedQuery prepareNamedQuery(final Collection<String> selectPropNames, final Condition cond) throws SQLException;

    /**
     * Creates a NamedQuery optimized for large result sets.
     * Configures the query to use cursor-based fetching for memory efficiency.
     *
     * @param namedSql the named SQL query string
     * @return a NamedQuery configured for large results
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if invoked on a read-only DAO with non-SELECT SQL,
     *                                       or on a no-update DAO with SQL other than SELECT/INSERT
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQueryForLargeResult(final String namedSql) throws SQLException {
        return JdbcUtil.prepareNamedQueryForLargeResult(dataSource(), namedSql);
    }

    /**
     * Creates a NamedQuery from pre-parsed SQL optimized for large result sets.
     *
     * @param namedSql the pre-parsed named query
     * @return a NamedQuery configured for large results
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if invoked on a read-only DAO with non-SELECT SQL,
     *                                       or on a no-update DAO with SQL other than SELECT/INSERT
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQueryForLargeResult(final ParsedSql namedSql) throws SQLException {
        return JdbcUtil.prepareNamedQueryForLargeResult(dataSource(), namedSql);
    }

    /**
     * Creates a named SELECT query optimized for large result sets based on condition.
     * All columns will be selected with cursor-based fetching.
     *
     * @param cond the condition appended to the generated SELECT statement
     *             (may include {@code WHERE}, {@code ORDER BY}, {@code LIMIT}, etc.)
     * @return a NamedQuery configured for large results
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQueryForLargeResult(final Condition cond) throws SQLException {
        return prepareNamedQueryForLargeResult(null, cond);
    }

    /**
     * Creates a named SELECT query for specific columns optimized for large result sets.
     * Combines column selection with cursor-based fetching and named parameters.
     *
     * @param selectPropNames the property names to select, or {@code null} to select all
     * @param cond the condition appended to the generated SELECT statement
     *             (may include {@code WHERE}, {@code ORDER BY}, {@code LIMIT}, etc.)
     * @return a NamedQuery configured for large results
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQueryForLargeResult(final Collection<String> selectPropNames, final Condition cond) throws SQLException {
        return prepareNamedQuery(selectPropNames, cond).configureStatement(DaoUtil.stmtSetterForBigQueryResult);
    }

    /**
     * Executes an asynchronous database operation using the default executor.
     * The operation runs in a separate thread and returns a ContinuableFuture.
     * Note: Transactions started in the current thread are NOT propagated to the async operation.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ContinuableFuture<List<User>> future = dao.asyncCall(d ->
     *     d.list(Filters.eq("status", "ACTIVE"))
     * );
     *
     * future.thenAccept(users ->
     *     users.forEach(System.out::println)
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param sqlAction function that performs database operations
     * @return ContinuableFuture with the operation result
     * @throws IllegalArgumentException if {@code sqlAction} is {@code null}
     */
    @SuppressWarnings("deprecation")
    @Beta
    @NonDBOperation
    default <R> ContinuableFuture<R> asyncCall(final Throwables.Function<? super TD, ? extends R, SQLException> sqlAction) {
        return asyncCall(sqlAction, executor());
    }

    /**
     * Executes an asynchronous database operation using the specified executor.
     * Provides control over which thread pool executes the operation.
     * Note: Transactions started in the current thread are NOT propagated to the async operation.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService customExecutor = Executors.newFixedThreadPool(10);
     *
     * ContinuableFuture<Boolean> future = dao.asyncCall(
     *     d -> d.exists(Filters.eq("status", "PENDING")),
     *     customExecutor
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param sqlAction function that performs database operations
     * @param executor the executor to run the operation
     * @return ContinuableFuture with the operation result
     * @throws IllegalArgumentException if {@code sqlAction} or {@code executor} is {@code null}
     */
    @Beta
    @NonDBOperation
    default <R> ContinuableFuture<R> asyncCall(final Throwables.Function<? super TD, ? extends R, SQLException> sqlAction, final Executor executor) {
        N.checkArgNotNull(sqlAction, cs.sqlAction);
        N.checkArgNotNull(executor, cs.executor);

        final TD dao = (TD) this;

        return ContinuableFuture.call(() -> sqlAction.apply(dao), executor);
    }

    /**
     * Executes an asynchronous database operation without return value using default executor.
     * Useful for fire-and-forget operations like logging or cleanup.
     * Note: Transactions started in the current thread are NOT propagated to the async operation.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * dao.asyncRun(d -> {
     *     List<User> stale = d.list(Filters.lt("lastAccess", sixMonthsAgo));
     *     System.out.println("stale accounts: " + stale.size());
     * });
     * }</pre>
     *
     * @param sqlAction consumer that performs database operations
     * @return ContinuableFuture that completes when operation finishes
     * @throws IllegalArgumentException if {@code sqlAction} is {@code null}
     */
    @SuppressWarnings("deprecation")
    @Beta
    @NonDBOperation
    default ContinuableFuture<Void> asyncRun(final Throwables.Consumer<? super TD, SQLException> sqlAction) {
        return asyncRun(sqlAction, executor());
    }

    /**
     * Executes an asynchronous database operation without return value using specified executor.
     * Combines async execution with custom thread pool management.
     * Note: Transactions started in the current thread are NOT propagated to the async operation.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);
     *
     * dao.asyncRun(
     *     d -> {
     *         long highValue = d.list(Filters.gt("amount", 1000)).size();
     *         System.out.println("high-value rows: " + highValue);
     *     },
     *     scheduler
     * ).thenRunAsync(() ->
     *     System.out.println("Report generated")
     * );
     * }</pre>
     *
     * @param sqlAction consumer that performs database operations
     * @param executor the executor to run the operation
     * @return ContinuableFuture that completes when operation finishes
     * @throws IllegalArgumentException if {@code sqlAction} or {@code executor} is {@code null}
     */
    @Beta
    @NonDBOperation
    default ContinuableFuture<Void> asyncRun(final Throwables.Consumer<? super TD, SQLException> sqlAction, final Executor executor) {
        N.checkArgNotNull(sqlAction, cs.sqlAction);
        N.checkArgNotNull(executor, cs.executor);

        final TD dao = (TD) this;

        return ContinuableFuture.run(() -> sqlAction.accept(dao), executor);
    }

}
