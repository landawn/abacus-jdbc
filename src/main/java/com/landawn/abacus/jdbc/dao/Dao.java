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

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

import javax.sql.DataSource;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.DuplicateResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.jdbc.CallableQuery;
import com.landawn.abacus.jdbc.cs;
import com.landawn.abacus.jdbc.IsolationLevel;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.NamedQuery;
import com.landawn.abacus.jdbc.PreparedQuery;
import com.landawn.abacus.jdbc.SqlTransaction;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.query.QueryUtil;
import com.landawn.abacus.util.Beans;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Throwables;

/**
 * The {@code Dao} interface provides a comprehensive data access abstraction layer for database operations.
 * It serves as a base interface for creating type-safe, SQL-based data access objects with support for
 * both traditional JDBC operations and modern functional programming patterns.
 *
 * <p>Synchronous statement execution propagates checked {@link SQLException}s to the caller.
 * Connection acquisition and transaction management can instead fail with
 * {@link UncheckedSQLException}; connection acquisition through Spring instead propagates
 * {@code CannotGetJdbcConnectionException}. For a variant whose database methods translate
 * checked SQL failures to the unchecked
 * {@link UncheckedSQLException}, see {@link UncheckedDao}. For variants
 * that forbid mutating operations, see {@link ReadOnlyDao} and {@link NonUpdateDao}.</p>
 *
 * <h2>Key Features:</h2>
 * <ul>
 *   <li>Type-safe database operations with compile-time checking</li>
 *   <li>Support for both parameterized and named parameterized SQL queries</li>
 *   <li>Lazy evaluation with Stream API integration</li>
 *   <li>Asynchronous operation support</li>
 *   <li>Transaction management integration</li>
 *   <li>Flexible query building with Condition API</li>
 * </ul>
 *
 * <h2>Performance Tips:</h2>
 * <ul>
 *   <li>Avoid unnecessary/repeated database calls</li>
 *   <li>Only fetch the columns you need or update the columns you want</li>
 *   <li>Proper indexing is a key factor in many database performance issues</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long, UserDao> {
 *     @Query("INSERT INTO users (id, first_name, last_name, email) VALUES (:id, :firstName, :lastName, :email)")
 *     void insertWithId(User user) throws SQLException;
 *
 *     @Query("UPDATE users SET first_name = :firstName, last_name = :lastName WHERE id = :id")
 *     int updateFirstAndLastName(@Bind("firstName") String newFirstName,
 *                                @Bind("lastName") String newLastName,
 *                                @Bind("id") long id) throws SQLException;
 *
 *     @Query("SELECT first_name, last_name FROM users WHERE id = :id")
 *     User getFirstAndLastNameBy(@Bind("id") long id) throws SQLException;
 *
 *     @Query("SELECT id, first_name, last_name, email FROM users")
 *     com.landawn.abacus.util.stream.Stream<User> allUsers(); // Must not declare 'throws SQLException'
 * }
 *
 * // Usage
 * UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource);
 * User user = userDao.getFirstAndLastNameBy(123L);
 * }</pre>
 *
 * <p><b>Transaction Example:</b></p>
 * <pre>{@code
 * final com.landawn.abacus.jdbc.SqlTransaction tran =
 *         JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
 * try {
 *     User user = userDao.getOrNull(id);
 *     userDao.update(user);
 *     tran.commit();
 * } finally {
 *     tran.rollbackIfNotCommitted();
 * }
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the self-type of the DAO for fluent interface support
 *
 * @see JdbcUtil#createDao(Class, DataSource)
 * @see JdbcUtil#createDao(Class, DataSource, JdbcUtil.DaoCreationOptions)
 * @see JdbcUtil#prepareQuery(javax.sql.DataSource, String)
 * @see JdbcUtil#prepareNamedQuery(javax.sql.DataSource, String)
 * @see JdbcUtil#beginTransaction(javax.sql.DataSource, IsolationLevel)
 * @see CrudDao
 * @see Filters
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
public non-sealed interface Dao<T, TD extends Dao<T, TD>> extends ReadOps<T, TD>, InsertOps<T, TD>, UpdateOps<T, TD>, DeleteOps<T, TD> {

    /**
     * Creates a PreparedQuery with the option to generate keys for INSERT statements.
     * When generateKeys is {@code true}, auto-generated keys can be retrieved after execution.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (PreparedQuery query = dao.prepareQuery("INSERT INTO users (name) VALUES (?)", true)) {
     *     com.landawn.abacus.util.u.Optional<Long> generatedId = query.setString(1, "John").insert();
     * }
     * }</pre>
     *
     * @param sql the SQL query string
     * @param generateKeys {@code true} to return generated keys, {@code false} otherwise
     * @return a PreparedQuery instance
     * @throws IllegalArgumentException if {@code sql} is {@code null} or empty
     * @throws UncheckedSQLException if direct (non-Spring) acquisition of a required database connection fails.
     *         Spring acquisition failures instead propagate as {@code CannotGetJdbcConnectionException}.
     * @throws SQLException if preparing or configuring the SQL statement fails
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final String sql, final boolean generateKeys) throws IllegalArgumentException, UncheckedSQLException, SQLException {
        return JdbcUtil.prepareQuery(dataSource(), sql, generateKeys);
    }

    /**
     * Creates a PreparedQuery that will return specific columns as generated keys.
     * This is useful when you need to retrieve specific auto-generated column values.
     *
     * @param sql the SQL query string
     * @param generatedKeyColumnIndexes array of column indexes to return as generated keys
     * @return a PreparedQuery instance
     * @throws IllegalArgumentException if {@code sql} is {@code null} or empty, or if {@code generatedKeyColumnIndexes} is {@code null} or empty
     * @throws UncheckedSQLException if direct (non-Spring) acquisition of a required database connection fails.
     *         Spring acquisition failures instead propagate as {@code CannotGetJdbcConnectionException}.
     * @throws SQLException if preparing or configuring the SQL statement fails
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final String sql, final int[] generatedKeyColumnIndexes)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        return JdbcUtil.prepareQuery(dataSource(), sql, generatedKeyColumnIndexes);
    }

    /**
     * Creates a PreparedQuery that will return specific named columns as generated keys.
     * This allows retrieval of auto-generated values from specific columns by name.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (PreparedQuery query = dao.prepareQuery(
     *         "INSERT INTO users (name) VALUES (?)",
     *         new String[] {"id", "created_at"})) {
     *     query.setString(1, "John").insert();
     * }
     * }</pre>
     *
     * @param sql the SQL query string
     * @param generatedKeyColumnNames array of column names to return as generated keys
     * @return a PreparedQuery instance
     * @throws IllegalArgumentException if {@code sql} is {@code null} or empty, or if {@code generatedKeyColumnNames} is {@code null} or empty
     * @throws UncheckedSQLException if direct (non-Spring) acquisition of a required database connection fails.
     *         Spring acquisition failures instead propagate as {@code CannotGetJdbcConnectionException}.
     * @throws SQLException if preparing or configuring the SQL statement fails
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final String sql, final String[] generatedKeyColumnNames)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        return JdbcUtil.prepareQuery(dataSource(), sql, generatedKeyColumnNames);
    }

    /**
     * Creates a PreparedQuery using a custom statement creator function.
     * This provides maximum flexibility for creating prepared statements with custom options.
     *
     * @param sql the SQL query string
     * @param stmtCreator function to create the PreparedStatement with custom options
     * @return a PreparedQuery instance
     * @throws IllegalArgumentException if {@code sql} is {@code null} or empty, or if {@code stmtCreator} is {@code null}
     * @throws UncheckedSQLException if direct (non-Spring) acquisition of a required database connection fails.
     *         Spring acquisition failures instead propagate as {@code CannotGetJdbcConnectionException}.
     * @throws SQLException if preparing or configuring the SQL statement fails, or a supplied JDBC callback throws {@link SQLException}
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final String sql, final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);

        return JdbcUtil.prepareQuery(dataSource(), sql, stmtCreator);
    }

    /**
     * Creates a NamedQuery with the option to generate keys for INSERT statements.
     * Combines named parameters with auto-generated key retrieval.
     *
     * @param namedSql the named SQL query string
     * @param generateKeys {@code true} to return generated keys, {@code false} otherwise
     * @return a NamedQuery instance
     * @throws IllegalArgumentException if {@code namedSql} is {@code null} or empty,
     *                                  or if {@code namedSql} contains positional (unnamed) parameters
     * @throws UncheckedSQLException if direct (non-Spring) acquisition of a required database connection fails.
     *         Spring acquisition failures instead propagate as {@code CannotGetJdbcConnectionException}.
     * @throws SQLException if preparing or configuring the SQL statement fails
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final String namedSql, final boolean generateKeys)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedSql, generateKeys);
    }

    /**
     * Creates a NamedQuery that will return specific columns as generated keys.
     * Useful for INSERT statements with named parameters that need to retrieve auto-generated values.
     *
     * @param namedSql the named SQL query string
     * @param generatedKeyColumnIndexes array of column indexes to return
     * @return a NamedQuery instance
     * @throws IllegalArgumentException if {@code namedSql} is {@code null} or empty, or if {@code generatedKeyColumnIndexes} is {@code null} or empty,
     *                                  or if {@code namedSql} contains positional (unnamed) parameters
     * @throws UncheckedSQLException if direct (non-Spring) acquisition of a required database connection fails.
     *         Spring acquisition failures instead propagate as {@code CannotGetJdbcConnectionException}.
     * @throws SQLException if preparing or configuring the SQL statement fails
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final String namedSql, final int[] generatedKeyColumnIndexes)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedSql, generatedKeyColumnIndexes);
    }

    /**
     * Creates a NamedQuery that will return specific named columns as generated keys.
     * Provides the most readable way to retrieve auto-generated values with named queries.
     *
     * @param namedSql the named SQL query string
     * @param generatedKeyColumnNames array of column names to return
     * @return a NamedQuery instance
     * @throws IllegalArgumentException if {@code namedSql} is {@code null} or empty, or if {@code generatedKeyColumnNames} is {@code null} or empty,
     *                                  or if {@code namedSql} contains positional (unnamed) parameters
     * @throws UncheckedSQLException if direct (non-Spring) acquisition of a required database connection fails.
     *         Spring acquisition failures instead propagate as {@code CannotGetJdbcConnectionException}.
     * @throws SQLException if preparing or configuring the SQL statement fails
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final String namedSql, final String[] generatedKeyColumnNames)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedSql, generatedKeyColumnNames);
    }

    /**
     * Creates a NamedQuery from a pre-parsed SQL object with key generation option.
     *
     * @param namedSql the pre-parsed named query
     * @param generateKeys {@code true} to return generated keys, {@code false} otherwise
     * @return a NamedQuery instance
     * @throws IllegalArgumentException if {@code namedSql} is {@code null},
     *                                  or if {@code namedSql} contains positional (unnamed) parameters
     * @throws UncheckedSQLException if direct (non-Spring) acquisition of a required database connection fails.
     *         Spring acquisition failures instead propagate as {@code CannotGetJdbcConnectionException}.
     * @throws SQLException if preparing or configuring the SQL statement fails
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final ParsedSql namedSql, final boolean generateKeys)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedSql, generateKeys);
    }

    /**
     * Creates a NamedQuery from a pre-parsed SQL with specific return columns by index.
     *
     * @param namedSql the pre-parsed named query
     * @param generatedKeyColumnIndexes array of column indexes to return
     * @return a NamedQuery instance
     * @throws IllegalArgumentException if {@code namedSql} is {@code null}, or if {@code generatedKeyColumnIndexes} is {@code null} or empty,
     *                                  or if {@code namedSql} contains positional (unnamed) parameters
     * @throws UncheckedSQLException if direct (non-Spring) acquisition of a required database connection fails.
     *         Spring acquisition failures instead propagate as {@code CannotGetJdbcConnectionException}.
     * @throws SQLException if preparing or configuring the SQL statement fails
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final ParsedSql namedSql, final int[] generatedKeyColumnIndexes)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedSql, generatedKeyColumnIndexes);
    }

    /**
     * Creates a NamedQuery from a pre-parsed SQL with specific return columns by name.
     *
     * @param namedSql the pre-parsed named query
     * @param generatedKeyColumnNames array of column names to return
     * @return a NamedQuery instance
     * @throws IllegalArgumentException if {@code namedSql} is {@code null}, or if {@code generatedKeyColumnNames} is {@code null} or empty,
     *                                  or if {@code namedSql} contains positional (unnamed) parameters
     * @throws UncheckedSQLException if direct (non-Spring) acquisition of a required database connection fails.
     *         Spring acquisition failures instead propagate as {@code CannotGetJdbcConnectionException}.
     * @throws SQLException if preparing or configuring the SQL statement fails
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final ParsedSql namedSql, final String[] generatedKeyColumnNames)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedSql, generatedKeyColumnNames);
    }

    /**
     * Creates a NamedQuery using a custom statement creator function.
     * Provides maximum control over statement creation with named parameters.
     *
     * @param namedSql the named SQL query string
     * @param stmtCreator function to create the PreparedStatement
     * @return a NamedQuery instance
     * @throws IllegalArgumentException if {@code namedSql} is {@code null} or empty, or if {@code stmtCreator} is {@code null},
     *                                  or if {@code namedSql} contains positional (unnamed) parameters
     * @throws UncheckedSQLException if direct (non-Spring) acquisition of a required database connection fails.
     *         Spring acquisition failures instead propagate as {@code CannotGetJdbcConnectionException}.
     * @throws SQLException if preparing or configuring the SQL statement fails, or a supplied JDBC callback throws {@link SQLException}
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final String namedSql, final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotEmpty(namedSql, cs.namedSql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);

        return JdbcUtil.prepareNamedQuery(dataSource(), namedSql, stmtCreator);
    }

    /**
     * Creates a NamedQuery from a pre-parsed SQL with custom statement creation.
     *
     * @param namedSql the pre-parsed named query
     * @param stmtCreator function to create the PreparedStatement
     * @return a NamedQuery instance
     * @throws IllegalArgumentException if {@code namedSql} is {@code null}, or if {@code stmtCreator} is {@code null},
     *                                  or if {@code namedSql} contains positional (unnamed) parameters
     * @throws UncheckedSQLException if direct (non-Spring) acquisition of a required database connection fails.
     *         Spring acquisition failures instead propagate as {@code CannotGetJdbcConnectionException}.
     * @throws SQLException if preparing or configuring the SQL statement fails, or a supplied JDBC callback throws {@link SQLException}
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final ParsedSql namedSql, final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotNull(namedSql, cs.namedSql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);

        return JdbcUtil.prepareNamedQuery(dataSource(), namedSql, stmtCreator);
    }

    /**
     * Creates a CallableQuery for executing stored procedures or functions.
     * The query should use the JDBC escape syntax: {@code {call procedure_name(?, ?)}}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (CallableQuery query = dao.prepareCallableQuery("{call get_user_count(?)}")) {
     *     com.landawn.abacus.jdbc.Jdbc.OutParamResult outParams = query
     *             .registerOutParameter(1, java.sql.Types.INTEGER)
     *             .executeAndGetOutParameters();
     *     int count = outParams.getOutParamValue(1);
     * }
     * }</pre>
     *
     * @param sql the stored procedure call string
     * @return a CallableQuery instance
     * @throws IllegalArgumentException if {@code sql} is {@code null} or empty
     * @throws UncheckedSQLException if direct (non-Spring) acquisition of a required database connection fails.
     *         Spring acquisition failures instead propagate as {@code CannotGetJdbcConnectionException}.
     * @throws SQLException if preparing or configuring the SQL statement fails
     */
    @Beta
    @NonDBOperation
    default CallableQuery prepareCallableQuery(final String sql) throws IllegalArgumentException, UncheckedSQLException, SQLException {
        return JdbcUtil.prepareCallableQuery(dataSource(), sql);
    }

    /**
     * Creates a CallableQuery using a custom statement creator.
     * Provides maximum control over callable statement creation.
     *
     * @param sql the stored procedure call string
     * @param stmtCreator function to create the CallableStatement
     * @return a CallableQuery instance
     * @throws IllegalArgumentException if {@code sql} is {@code null} or empty, or if {@code stmtCreator} is {@code null}
     * @throws UncheckedSQLException if direct (non-Spring) acquisition of a required database connection fails.
     *         Spring acquisition failures instead propagate as {@code CannotGetJdbcConnectionException}.
     * @throws SQLException if preparing or configuring the SQL statement fails, or a supplied JDBC callback throws {@link SQLException}
     */
    @Beta
    @NonDBOperation
    default CallableQuery prepareCallableQuery(final String sql, final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);

        return JdbcUtil.prepareCallableQuery(dataSource(), sql, stmtCreator);
    }

    /**
     * Executes an upsert operation: inserts the entity if no record matches the unique properties,
     * otherwise updates the existing record.
     *
     * <p>A {@code null} match value is matched with {@code IS NULL}, so an existing row holding {@code NULL} in that
     * column is found and updated; the by-id lookups never match a {@code null} id component (see
     * <i>{@code null} key values</i> in the {@linkplain CrudDao CrudDao class documentation}).</p>
     *
     * <p>Inside a transaction, the lookup and the write run in one transaction scope, as {@link #upsert(Object, Condition)}
     * describes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User("john@example.com", "John Doe");
     * User saved = dao.upsert(user, Arrays.asList("email"));
     * // Inserts if email doesn't exist, updates if it does
     * }</pre>
     *
     * @param entity the entity to insert or update
     * @param matchPropNames property names that uniquely identify the record
     *            (a spelling the entity's property lookup accepts, such as the column name, is resolved to the property name)
     * @return the saved entity (the input entity if it was newly inserted; otherwise the merged existing entity that was updated)
     * @throws IllegalArgumentException if {@code entity} is {@code null} or {@code matchPropNames} is {@code null} or empty,
     *                                  or if any name in {@code matchPropNames} is not a readable property of the entity class,
     *                                  or an existing row is updated, {@code entity} has ID properties and a non-ID property the loaded
     *                                  entity does not
     * @throws UncheckedSQLException if direct (non-Spring) acquisition of a required database connection fails, or starting or completing the transaction scope
     *         (inside a transaction), or telling whether a transaction is active (from the auto-commit mode of a
     *         Spring-bound connection), fails.
     *         Spring acquisition failures instead propagate as {@code CannotGetJdbcConnectionException}.
     * @throws SQLException if looking up an existing row or executing the required INSERT or UPDATE statement fails
     * @throws DuplicateResultException if more than one record matches the specified {@code matchPropNames}
     * @throws IllegalStateException if the call runs inside a Spring-managed transaction it cannot participate in (see
     *         {@link #upsert(Object, Condition)})
     * @throws UnsupportedOperationException if an existing row is updated and the loaded class is an immutable bean
     * @see #upsert(Object, Condition)
     */
    default T upsert(final T entity, final Collection<String> matchPropNames) throws IllegalArgumentException, IllegalStateException, UncheckedSQLException,
            SQLException, DuplicateResultException, UnsupportedOperationException {
        N.checkArgNotNull(entity, cs.entity);
        N.checkArgNotEmpty(matchPropNames, cs.matchPropNames);

        // Filters.allEqual reads values through the lenient bean-info lookup but renders the raw name into SQL, so a spelling
        // such as "FIRSTNAME" would read the right value yet reference a non-existent column.
        // WHY: a null match value renders IS NULL on purpose, the rule batchUpsert documents as well. An upsert matches by
        // values and must be repeatable; the by-id rule (DaoUtil.entityIdToCondition: a null component matches no row) would
        // insert a duplicate on every upsert of an entity holding null for a nullable key column, as most databases allow
        // several NULLs in a unique column. For NOT NULL key columns both rules match no row.
        final Condition cond = Filters.allEqual(entity, DaoUtil.toCanonicalPropNames(entity, matchPropNames));

        return upsert(entity, cond);
    }

    /**
     * Executes an upsert operation: inserts the entity if no record matches the condition,
     * otherwise updates the existing record.
     * More flexible than property-based upsert for complex conditions.
     *
     * <p>When a record is found, the non-{@code null} non-id properties of {@code entity} are copied into
     * the loaded record before it is updated; a {@code null} property keeps the existing value, so an
     * upsert cannot clear a column to {@code NULL}. When the input has no ID properties, properties absent from the
     * loaded entity are ignored. With ID properties, an unmatched non-ID property is rejected.</p>
     *
     * <p>Inside a transaction on the DAO's data source, the lookup and the write run in one transaction scope: a nested scope of
     * the transaction active on the thread (a failure, also one of the lookup, then marks that transaction rollback-only, so its
     * outermost {@code commit()} throws {@link com.landawn.abacus.jdbc.RollbackOnlyException}), or one participating in a
     * Spring-managed transaction that owns the data source's connection (see
     * {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)}; a failure then marks the Spring
     * transaction rollback-only, so committing it throws {@code UnexpectedRollbackException}). Outside a transaction, the
     * lookup and the write run as separate statements, the write committed on its own, as any single write is: a transaction
     * of their own would fail on a connection that a JTA transaction manages, which this library does not detect. This does
     * not keep a concurrent upsert of the same key out: under the usual isolation levels both may find no row and insert, and
     * a unique constraint on the matched columns makes one of them fail.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setEmail("john@example.com");
     * user.setStatus("ACTIVE");
     *
     * Condition cond = Filters.and(
     *     Filters.eq("email", user.getEmail()),
     *     Filters.eq("deleted", false)
     * );
     *
     * User saved = dao.upsert(user, cond);
     * }</pre>
     *
     * @param entity the entity to insert or update
     * @param cond condition to check for existence
     * @return the saved entity (the input entity if it was newly inserted; otherwise the merged existing entity that was updated)
     * @throws IllegalArgumentException if {@code entity} or {@code cond} is {@code null}, or an existing row is updated
     *                                  and {@code entity} has ID properties and a non-ID property the loaded entity does not
     * @throws UncheckedSQLException if direct (non-Spring) acquisition of a required database connection fails, or starting or completing the transaction scope
     *         (inside a transaction), or telling whether a transaction is active (from the auto-commit mode of a
     *         Spring-bound connection), fails.
     *         Spring acquisition failures instead propagate as {@code CannotGetJdbcConnectionException}.
     * @throws SQLException if looking up an existing row or executing the required INSERT or UPDATE statement fails
     * @throws DuplicateResultException if more than one record matches the specified condition
     * @throws IllegalStateException if the call runs inside a Spring-managed transaction it cannot participate in (see
     *         {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UnsupportedOperationException if an existing row is updated and the loaded class is an immutable bean
     */
    default T upsert(final T entity, final Condition cond) throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, SQLException,
            DuplicateResultException, UnsupportedOperationException {
        N.checkArgNotNull(entity, cs.entity);
        N.checkArgNotNull(cond, cs.cond);

        // Inside a transaction on the data source, the lookup and the write run in a nested scope of it (or one participating in
        // the Spring-managed transaction that owns its connection), so that a failure of either marks that transaction
        // rollback-only: a caller that catches it can't commit the lookup's locks without the write (nor can Spring, whose own
        // rules don't roll back for a checked SQLException). Outside one, they run as the statements of any single write do. WHY not a transaction of its own there: on a connection a JTA transaction manages,
        // which this library can't tell (see SqlTransaction), its commit failed and aborted the connection, where an upsert
        // used to work.
        final SqlTransaction tran = JdbcUtil.isInTransaction(dataSource()) ? JdbcUtil.beginTransaction(dataSource()) : null;
        Throwable failure = null;
        T result = null;

        try {
            final T dbEntity = findOnlyOne(cond).orElseNull();

            if (dbEntity == null) {
                save(entity);
                result = entity;
            } else {
                final Class<?> cls = entity.getClass();
                final List<String> idPropNameList = QueryUtil.idPropNames(cls);

                if (N.isEmpty(idPropNameList)) {
                    // A no-ID subtype may carry properties not persisted by this DAO; preserve its lenient merge.
                    Beans.mergeInto(entity, dbEntity);
                    update(dbEntity, cond);
                } else {
                    Beans.mergeInto(entity, dbEntity, false, N.newHashSet(idPropNameList));
                    final Condition idCond = Filters.allEqual(dbEntity, idPropNameList);
                    update(dbEntity, idCond);
                }

                result = dbEntity;
            }

            if (tran != null) {
                tran.commit();
            }
        } catch (final Throwable e) { //NOSONAR
            failure = e;
            throw e;
        } finally {
            try {
                if (tran != null) {
                    tran.rollbackIfNotCommitted();
                }
            } catch (final RuntimeException | Error rollbackFailure) {
                if (failure == null) {
                    throw rollbackFailure;
                }

                DaoUtil.addSuppressedIfDifferent(failure, rollbackFailure);
            }
        }

        return result;
    }

}
