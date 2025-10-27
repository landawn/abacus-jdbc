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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.sql.DataSource;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.annotation.Internal;
import com.landawn.abacus.annotation.LazyEvaluation;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.jdbc.CallableQuery;
import com.landawn.abacus.jdbc.IsolationLevel;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.jdbc.Jdbc.Columns.ColumnOne;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.NamedQuery;
import com.landawn.abacus.jdbc.PreparedQuery;
import com.landawn.abacus.jdbc.cs;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.query.QueryUtil;
import com.landawn.abacus.query.SQLBuilder;
import com.landawn.abacus.query.SQLMapper;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.query.condition.ConditionFactory;
import com.landawn.abacus.query.condition.ConditionFactory.CF;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.AsyncExecutor;
import com.landawn.abacus.util.Beans;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.NoCachingNoUpdating.DisposableObjArray;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.u.Nullable;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.u.OptionalBoolean;
import com.landawn.abacus.util.u.OptionalByte;
import com.landawn.abacus.util.u.OptionalChar;
import com.landawn.abacus.util.u.OptionalDouble;
import com.landawn.abacus.util.u.OptionalFloat;
import com.landawn.abacus.util.u.OptionalInt;
import com.landawn.abacus.util.u.OptionalLong;
import com.landawn.abacus.util.u.OptionalShort;
import com.landawn.abacus.util.stream.Stream;

/**
 * The {@code Dao} interface provides a comprehensive data access abstraction layer for database operations.
 * It serves as a base interface for creating type-safe, SQL-based data access objects with support for
 * both traditional JDBC operations and modern functional programming patterns.
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
 *   <li>Index is the key point in a lot of database performance issues</li>
 * </ul>
 * 
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long, SQLBuilder.PSC> {
 *     @Query("INSERT INTO user (id, first_name, last_name, email) VALUES (:id, :firstName, :lastName, :email)")
 *     void insertWithId(User user) throws SQLException;
 *
 *     @Query("UPDATE user SET first_name = :firstName, last_name = :lastName WHERE id = :id")
 *     int updateFirstAndLastName(@Bind("firstName") String newFirstName,
 *                                @Bind("lastName") String newLastName,
 *                                @Bind("id") long id) throws SQLException;
 *
 *     @Query("SELECT first_name, last_name FROM user WHERE id = :id")
 *     User getFirstAndLastNameBy(@Bind("id") long id) throws SQLException;
 *
 *     @Query("SELECT id, first_name, last_name, email FROM user")
 *     Stream<User> allUsers() throws SQLException;
 * }
 *
 * // Usage
 * UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource);
 * User user = userDao.getFirstAndLastNameBy(123L);
 * }</pre>
 * 
 * <h2>Transaction Example:</h2>
 * <pre>{@code
 * final SQLTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
 * try {
 *     userDao.getById(id);
 *     userDao.update(...);
 *     tran.commit();
 * } finally {
 *     tran.rollbackIfNotCommitted();
 * }
 * }</pre>
 *
 * @param <T> the entity type that this DAO manages
 * @param <SB> the SQLBuilder type used to generate SQL scripts (must be one of SQLBuilder.PSC/PAC/PLC)
 * @param <TD> the self-type parameter for fluent API support
 *
 * @see JdbcUtil#createDao(Class, DataSource)
 * @see JdbcUtil#createDao(Class, DataSource, SQLMapper)
 * @see JdbcUtil#prepareQuery(javax.sql.DataSource, String)
 * @see JdbcUtil#prepareNamedQuery(javax.sql.DataSource, String)
 * @see JdbcUtil#beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)
 * @see CrudDao
 * @see ConditionFactory
 * @see ConditionFactory.CF
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
public interface Dao<T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> {

    /**
     * Retrieves the underlying data source used by this DAO for database connections.
     * This data source is used for all database operations performed by this DAO instance.
     *
     * @return the data source configured for this DAO
     */
    @NonDBOperation
    javax.sql.DataSource dataSource();

    /**
     * Retrieves the {@code SQLMapper} instance configured for this DAO.
     * The SQLMapper provides SQL query templates that can be referenced by name.
     * If no SQLMapper is configured, an empty SQLMapper instance will be returned.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SQLMapper sqlMapper = dao.sqlMapper();
     * String query = sqlMapper.get("findUserByEmail");
     * }</pre>
     *
     * @return the SQLMapper instance, never null
     */
    @NonDBOperation
    SQLMapper sqlMapper();

    /**
     * Retrieves the class object representing the entity type managed by this DAO.
     * This is used internally for reflection-based operations.
     *
     * @return the class of the target entity type T
     * @deprecated Internal use only
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
     * @deprecated Internal use only
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
     * @deprecated Internal use only
     */
    @Deprecated
    @NonDBOperation
    @Internal
    Executor executor();

    /**
     * Retrieves the async executor wrapper that provides enhanced async operation support.
     * This provides additional functionality over the standard Executor interface.
     *
     * @return the async executor instance
     * @deprecated Internal use only
     */
    @Deprecated
    @NonDBOperation
    @Internal
    AsyncExecutor asyncExecutor();

    /**
     * Creates a PreparedQuery for the specified SQL query string.
     * The query can be any valid SQL statement (SELECT, INSERT, UPDATE, DELETE, etc.).
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * PreparedQuery query = dao.prepareQuery("SELECT * FROM users WHERE age > ?");
     * List<User> users = query.setInt(1, 18).list(User.class);
     * }</pre>
     *
     * @param query the SQL query string
     * @return a PreparedQuery instance for the specified query
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final String query) throws SQLException {
        return JdbcUtil.prepareQuery(dataSource(), query);
    }

    /**
     * Creates a PreparedQuery with the option to generate keys for INSERT statements.
     * When generateKeys is true, auto-generated keys can be retrieved after execution.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * PreparedQuery query = dao.prepareQuery("INSERT INTO users (name) VALUES (?)", true);
     * long generatedId = query.setString(1, "John").insert().getGeneratedKeys().getLong(1);
     * }</pre>
     *
     * @param query the SQL query string
     * @param generateKeys true to return generated keys, {@code false} otherwise
     * @return a PreparedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final String query, final boolean generateKeys) throws SQLException {
        return JdbcUtil.prepareQuery(dataSource(), query, generateKeys);
    }

    /**
     * Creates a PreparedQuery that will return specific columns as generated keys.
     * This is useful when you need to retrieve specific auto-generated column values.
     *
     * @param query the SQL query string
     * @param returnColumnIndexes array of column indexes to return as generated keys
     * @return a PreparedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final String query, final int[] returnColumnIndexes) throws SQLException {
        return JdbcUtil.prepareQuery(dataSource(), query, returnColumnIndexes);
    }

    /**
     * Creates a PreparedQuery that will return specific named columns as generated keys.
     * This allows retrieval of auto-generated values from specific columns by name.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * PreparedQuery query = dao.prepareQuery(
     *     "INSERT INTO users (name) VALUES (?)", 
     *     new String[] {"id", "created_at"}
     * );
     * }</pre>
     *
     * @param query the SQL query string
     * @param returnColumnNames array of column names to return as generated keys
     * @return a PreparedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final String query, final String[] returnColumnNames) throws SQLException {
        return JdbcUtil.prepareQuery(dataSource(), query, returnColumnNames);
    }

    /**
     * Creates a PreparedQuery using a custom statement creator function.
     * This provides maximum flexibility for creating prepared statements with custom options.
     *
     * @param sql the SQL query string
     * @param stmtCreator function to create the PreparedStatement with custom options
     * @return a PreparedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final String sql, final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
            throws SQLException {
        return JdbcUtil.prepareQuery(dataSource(), sql, stmtCreator);
    }

    /**
     * Creates a SELECT query based on the specified condition.
     * All columns from the entity table will be selected.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * PreparedQuery query = dao.prepareQuery(CF.eq("status", "ACTIVE"));
     * List<User> activeUsers = query.list(User.class);
     * }</pre>
     *
     * @param cond the condition for the WHERE clause
     * @return a PreparedQuery instance for the SELECT statement
     * @throws SQLException if a database access error occurs
     * @see ConditionFactory
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
     *     CF.eq("status", "ACTIVE")
     * );
     * }</pre>
     *
     * @param selectPropNames the property names to select, or null to select all
     * @param cond the condition for the WHERE clause
     * @return a PreparedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final Collection<String> selectPropNames, final Condition cond) throws SQLException {
        return DaoUtil.getDaoPreparedQueryFunc(this)._1.apply(selectPropNames, cond);
    }

    /**
     * Creates a PreparedQuery optimized for queries that return large result sets.
     * This configures the statement to use cursor-based fetching for better memory efficiency.
     *
     * @param query the SQL query string
     * @return a PreparedQuery configured for large results
     * @throws SQLException if a database access error occurs
     * @see JdbcUtil#prepareNamedQueryForBigResult(DataSource, String)
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQueryForBigResult(final String query) throws SQLException {
        return JdbcUtil.prepareQueryForBigResult(dataSource(), query);
    }

    /**
     * Creates a SELECT query optimized for large result sets based on the specified condition.
     * All columns will be selected with cursor-based fetching enabled.
     *
     * @param cond the condition for the WHERE clause
     * @return a PreparedQuery configured for large results
     * @throws SQLException if a database access error occurs
     * @see JdbcUtil#prepareNamedQueryForBigResult(DataSource, String)
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQueryForBigResult(final Condition cond) throws SQLException {
        return prepareQueryForBigResult(null, cond);
    }

    /**
     * Creates a SELECT query for specific columns optimized for large result sets.
     * Combines column selection with cursor-based fetching for memory-efficient processing.
     *
     * @param selectPropNames the property names to select, or null to select all
     * @param cond the condition for the WHERE clause
     * @return a PreparedQuery configured for large results
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQueryForBigResult(final Collection<String> selectPropNames, final Condition cond) throws SQLException {
        return prepareQuery(selectPropNames, cond).configStmt(DaoUtil.stmtSetterForBigQueryResult);
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
     * List<User> users = query.setParameter("minAge", 18)
     *                         .setParameter("status", "ACTIVE")
     *                         .list(User.class);
     * }</pre>
     *
     * @param namedQuery the named SQL query string with :paramName placeholders
     * @return a NamedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final String namedQuery) throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery);
    }

    /**
     * Creates a NamedQuery with the option to generate keys for INSERT statements.
     * Combines named parameters with auto-generated key retrieval.
     *
     * @param namedQuery the named SQL query string
     * @param generateKeys true to return generated keys
     * @return a NamedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final String namedQuery, final boolean generateKeys) throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, generateKeys);
    }

    /**
     * Creates a NamedQuery that will return specific columns as generated keys.
     * Useful for INSERT statements with named parameters that need to retrieve auto-generated values.
     *
     * @param namedQuery the named SQL query string
     * @param returnColumnIndexes array of column indexes to return
     * @return a NamedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final String namedQuery, final int[] returnColumnIndexes) throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnIndexes);
    }

    /**
     * Creates a NamedQuery that will return specific named columns as generated keys.
     * Provides the most readable way to retrieve auto-generated values with named queries.
     *
     * @param namedQuery the named SQL query string
     * @param returnColumnNames array of column names to return
     * @return a NamedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final String namedQuery, final String[] returnColumnNames) throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnNames);
    }

    /**
     * Creates a NamedQuery using a custom statement creator function.
     * Provides maximum control over statement creation with named parameters.
     *
     * @param namedQuery the named SQL query string
     * @param stmtCreator function to create the PreparedStatement
     * @return a NamedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final String namedQuery, final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
            throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, stmtCreator);
    }

    /**
     * Creates a NamedQuery from a pre-parsed SQL object.
     * This is more efficient when reusing the same query multiple times.
     *
     * @param namedSql the pre-parsed named query
     * @return a NamedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final ParsedSql namedSql) throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedSql);
    }

    /**
     * Creates a NamedQuery from a pre-parsed SQL object with key generation option.
     *
     * @param namedSql the pre-parsed named query
     * @param generateKeys true to return generated keys
     * @return a NamedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final ParsedSql namedSql, final boolean generateKeys) throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedSql, generateKeys);
    }

    /**
     * Creates a NamedQuery from a pre-parsed SQL with specific return columns by index.
     *
     * @param namedQuery the pre-parsed named query
     * @param returnColumnIndexes array of column indexes to return
     * @return a NamedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final int[] returnColumnIndexes) throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnIndexes);
    }

    /**
     * Creates a NamedQuery from a pre-parsed SQL with specific return columns by name.
     *
     * @param namedQuery the pre-parsed named query
     * @param returnColumnNames array of column names to return
     * @return a NamedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final String[] returnColumnNames) throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnNames);
    }

    /**
     * Creates a NamedQuery from a pre-parsed SQL with custom statement creation.
     *
     * @param namedSql the pre-parsed named query
     * @param stmtCreator function to create the PreparedStatement
     * @return a NamedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final ParsedSql namedSql, final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
            throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedSql, stmtCreator);
    }

    /**
     * Creates a named SELECT query based on the specified condition.
     * All columns will be selected using named parameter syntax.
     *
     * @param cond the condition for the WHERE clause
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
     * Generates a named parameter query with selected columns and WHERE clause.
     *
     * @param selectPropNames the property names to select, or null for all
     * @param cond the condition for the WHERE clause
     * @return a NamedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final Collection<String> selectPropNames, final Condition cond) throws SQLException {
        return DaoUtil.getDaoPreparedQueryFunc(this)._2.apply(selectPropNames, cond);
    }

    /**
     * Creates a NamedQuery optimized for large result sets.
     * Configures the query to use cursor-based fetching for memory efficiency.
     *
     * @param namedQuery the named SQL query string
     * @return a NamedQuery configured for large results
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQueryForBigResult(final String namedQuery) throws SQLException {
        return JdbcUtil.prepareNamedQueryForBigResult(dataSource(), namedQuery);
    }

    /**
     * Creates a NamedQuery from pre-parsed SQL optimized for large result sets.
     *
     * @param namedSql the pre-parsed named query
     * @return a NamedQuery configured for large results
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQueryForBigResult(final ParsedSql namedSql) throws SQLException {
        return JdbcUtil.prepareNamedQueryForBigResult(dataSource(), namedSql);
    }

    /**
     * Creates a named SELECT query optimized for large result sets based on condition.
     * All columns will be selected with cursor-based fetching.
     *
     * @param cond the condition for the WHERE clause
     * @return a NamedQuery configured for large results
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQueryForBigResult(final Condition cond) throws SQLException {
        return prepareNamedQueryForBigResult(null, cond);
    }

    /**
     * Creates a named SELECT query for specific columns optimized for large result sets.
     * Combines column selection with cursor-based fetching and named parameters.
     *
     * @param selectPropNames the property names to select, or null for all
     * @param cond the condition for the WHERE clause
     * @return a NamedQuery configured for large results
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQueryForBigResult(final Collection<String> selectPropNames, final Condition cond) throws SQLException {
        return prepareNamedQuery(selectPropNames, cond).configStmt(DaoUtil.stmtSetterForBigQueryResult);
    }

    /**
     * Creates a CallableQuery for executing stored procedures or functions.
     * The query should use the JDBC escape syntax: {call procedure_name(?, ?)}
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * CallableQuery query = dao.prepareCallableQuery("{call get_user_count(?)}");
     * query.registerOutParameter(1, Types.INTEGER);
     * query.execute();
     * int count = query.getInt(1);
     * }</pre>
     *
     * @param query the stored procedure call string
     * @return a CallableQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default CallableQuery prepareCallableQuery(final String query) throws SQLException {
        return JdbcUtil.prepareCallableQuery(dataSource(), query);
    }

    /**
     * Creates a CallableQuery using a custom statement creator.
     * Provides maximum control over callable statement creation.
     *
     * @param sql the stored procedure call string
     * @param stmtCreator function to create the CallableStatement
     * @return a CallableQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default CallableQuery prepareCallableQuery(final String sql, final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator)
            throws SQLException {
        return JdbcUtil.prepareCallableQuery(dataSource(), sql, stmtCreator);
    }

    /**
     * Saves (inserts) the specified entity to the database.
     * All non-null properties of the entity will be included in the INSERT statement.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User("John", "Doe", "john@example.com");
     * dao.save(user);
     * }</pre>
     *
     * @param entityToSave the entity to insert
     * @throws SQLException if a database access error occurs
     */
    void save(final T entityToSave) throws SQLException;

    /**
     * Saves (inserts) the specified entity with only the specified properties.
     * Only the listed properties will be included in the INSERT statement.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setEmail("john@example.com");
     * dao.save(user, Arrays.asList("email"));
     * }</pre>
     *
     * @param entityToSave the entity to insert
     * @param propNamesToSave the property names to include in the INSERT
     * @throws SQLException if a database access error occurs
     */
    void save(final T entityToSave, final Collection<String> propNamesToSave) throws SQLException;

    /**
     * Saves (inserts) the entity using a custom named INSERT SQL statement.
     * The SQL should use named parameters that match the entity properties.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "INSERT INTO users (name, email) VALUES (:name, :email)";
     * dao.save(sql, user);
     * }</pre>
     *
     * @param namedInsertSQL the named INSERT SQL statement
     * @param entityToSave the entity providing the parameter values
     * @throws SQLException if a database access error occurs
     */
    void save(final String namedInsertSQL, final T entityToSave) throws SQLException;

    /**
     * Batch saves (inserts) multiple entities using the default batch size.
     * More efficient than saving entities one by one.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = Arrays.asList(user1, user2, user3);
     * dao.batchSave(users);
     * }</pre>
     *
     * @param entitiesToSave the collection of entities to insert
     * @throws SQLException if a database access error occurs
     * @see #batchSave(Collection, int)
     */
    default void batchSave(final Collection<? extends T> entitiesToSave) throws SQLException {
        batchSave(entitiesToSave, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch saves (inserts) multiple entities with a specified batch size.
     * The entities are inserted in batches of the specified size for optimal performance.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = generateLargeUserList();
     * dao.batchSave(users, 1000); // Insert in batches of 1000
     * }</pre>
     *
     * @param entitiesToSave the collection of entities to insert
     * @param batchSize the number of entities to insert in each batch
     * @throws SQLException if a database access error occurs
     */
    void batchSave(final Collection<? extends T> entitiesToSave, final int batchSize) throws SQLException;

    /**
     * Batch saves entities with only the specified properties using default batch size.
     * Only the listed properties will be included in the INSERT statements.
     *
     * @param entitiesToSave the collection of entities to insert
     * @param propNamesToSave the property names to include in the INSERT
     * @throws SQLException if a database access error occurs
     */
    default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave) throws SQLException {
        batchSave(entitiesToSave, propNamesToSave, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch saves entities with only the specified properties and custom batch size.
     * Combines property selection with batch processing for optimal performance.
     *
     * @param entitiesToSave the collection of entities to insert
     * @param propNamesToSave the property names to include
     * @param batchSize the number of entities per batch
     * @throws SQLException if a database access error occurs
     */
    void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave, final int batchSize) throws SQLException;

    /**
     * Batch saves entities using a custom named INSERT SQL with default batch size.
     * The SQL should use named parameters matching entity properties.
     *
     * @param namedInsertSQL the named INSERT SQL statement
     * @param entitiesToSave the entities providing parameter values
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave) throws SQLException {
        batchSave(namedInsertSQL, entitiesToSave, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch saves entities using a custom named INSERT SQL with specified batch size.
     * Provides maximum control over batch insert operations.
     *
     * @param namedInsertSQL the named INSERT SQL statement
     * @param entitiesToSave the entities providing parameter values
     * @param batchSize the number of entities per batch
     * @throws SQLException if a database access error occurs
     */
    @Beta
    void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave, final int batchSize) throws SQLException;

    /**
     * Checks if at least one record exists that matches the specified condition.
     * More efficient than counting when you only need to know if records exist.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * boolean hasActiveUsers = dao.exists(CF.eq("status", "ACTIVE"));
     * if (hasActiveUsers) {
     *     // Process active users
     * }
     * }</pre>
     *
     * @param cond the condition to check
     * @return {@code true} if at least one matching record exists
     * @throws SQLException if a database access error occurs
     * @see ConditionFactory
     */
    boolean exists(final Condition cond) throws SQLException;

    /**
     * Checks if no records exist that match the specified condition.
     * Convenience method that returns the opposite of exists().
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * if (dao.notExists(CF.eq("email", email))) {
     *     // Email is available, proceed with registration
     * }
     * }</pre>
     *
     * @param cond the condition to check
     * @return {@code true} if no matching records exist
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default boolean notExists(final Condition cond) throws SQLException {
        return !exists(cond);
    }

    /**
     * Counts the number of records that match the specified condition.
     * Returns the exact count of matching records.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * int activeCount = dao.count(CF.eq("status", "ACTIVE"));
     * System.out.println("Active users: " + activeCount);
     * }</pre>
     *
     * @param cond the condition for counting
     * @return the number of matching records
     * @throws SQLException if a database access error occurs
     */
    int count(final Condition cond) throws SQLException;

    /**
     * Finds the first record that matches the specified condition.
     * Returns an Optional containing the entity if found, empty otherwise.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<User> user = dao.findFirst(CF.eq("email", "john@example.com"));
     * user.ifPresent(u -> System.out.println("Found: " + u.getName()));
     * }</pre>
     *
     * @param cond the search condition
     * @return Optional containing the first matching entity
     * @throws SQLException if a database access error occurs
     */
    Optional<T> findFirst(final Condition cond) throws SQLException;

    /**
     * Finds the first record matching the condition and maps it using the provided mapper.
     * Allows custom transformation of the result.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<String> userName = dao.findFirst(
     *     CF.eq("id", 123),
     *     rs -> rs.getString("name")
     * );
     * }</pre>
     *
     * @param <R> the result type after applying the mapping function
     * @param cond the search condition
     * @param rowMapper the function to map the result row
     * @return Optional containing the mapped result
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if rowMapper returns null
     */
    <R> Optional<R> findFirst(final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper) throws SQLException, IllegalArgumentException;

    /**
     * Finds the first record matching the condition and maps it using a bi-function mapper.
     * The mapper receives both the ResultSet and column labels.
     *
     * @param <R> the result type after applying the mapping function
     * @param cond the search condition
     * @param rowMapper the bi-function to map the result row
     * @return Optional containing the mapped result
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if rowMapper returns null
     */
    <R> Optional<R> findFirst(final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper) throws SQLException, IllegalArgumentException;

    /**
     * Finds the first record with only specified properties matching the condition.
     * Useful for retrieving partial entities with only needed fields.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<User> user = dao.findFirst(
     *     Arrays.asList("id", "name", "email"),
     *     CF.eq("status", "ACTIVE")
     * );
     * }</pre>
     *
     * @param selectPropNames the properties to select, null for all
     * @param cond the search condition
     * @return Optional containing the first matching entity
     * @throws SQLException if a database access error occurs
     */
    Optional<T> findFirst(final Collection<String> selectPropNames, final Condition cond) throws SQLException;

    /**
     * Finds the first record with specified properties and maps the result.
     * Combines property selection with custom result mapping.
     *
     * @param <R> the result type after applying the mapping function
     * @param selectPropNames the properties to select, null for all
     * @param cond the search condition
     * @param rowMapper the function to map the result
     * @return Optional containing the mapped result
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if rowMapper returns null
     */
    <R> Optional<R> findFirst(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper)
            throws SQLException, IllegalArgumentException;

    /**
     * Finds the first record with specified properties using a bi-function mapper.
     * Provides maximum flexibility for property selection and result mapping.
     *
     * @param <R> the result type after applying the mapping function
     * @param selectPropNames the properties to select, null for all
     * @param cond the search condition
     * @param rowMapper the bi-function to map the result
     * @return Optional containing the mapped result
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if rowMapper returns null
     */
    <R> Optional<R> findFirst(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper)
            throws SQLException, IllegalArgumentException;

    /**
     * Finds exactly one record matching the condition, throwing exception if multiple found.
     * Use this when you expect exactly zero or one result.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<User> user = dao.findOnlyOne(CF.eq("email", "john@example.com"));
     * // Throws DuplicatedResultException if multiple users have this email
     * }</pre>
     *
     * @param cond the search condition
     * @return Optional containing the single matching entity
     * @throws DuplicatedResultException if more than one record matches
     * @throws SQLException if a database access error occurs
     */
    Optional<T> findOnlyOne(final Condition cond) throws DuplicatedResultException, SQLException;

    /**
     * Finds exactly one record and maps it, throwing exception if multiple found.
     * Ensures uniqueness while allowing custom result transformation.
     *
     * @param <R> the result type after applying the mapping function
     * @param cond the search condition
     * @param rowMapper the function to map the result
     * @return Optional containing the mapped result
     * @throws DuplicatedResultException if more than one record matches
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if rowMapper returns null
     */
    <R> Optional<R> findOnlyOne(final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper)
            throws DuplicatedResultException, SQLException, IllegalArgumentException;

    /**
     * Finds exactly one record using a bi-function mapper, throwing if multiple found.
     * Provides column labels to the mapper for more flexible processing.
     *
     * @param <R> the result type after applying the mapping function
     * @param cond the search condition
     * @param rowMapper the bi-function to map the result
     * @return Optional containing the mapped result
     * @throws DuplicatedResultException if more than one record matches
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if rowMapper returns null
     */
    <R> Optional<R> findOnlyOne(final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper)
            throws DuplicatedResultException, SQLException, IllegalArgumentException;

    /**
     * Finds exactly one record with specified properties, throwing if multiple found.
     * Combines property selection with uniqueness constraint.
     *
     * @param selectPropNames the properties to select, null for all
     * @param cond the search condition
     * @return Optional containing the single matching entity
     * @throws DuplicatedResultException if more than one record matches
     * @throws SQLException if a database access error occurs
     */
    Optional<T> findOnlyOne(final Collection<String> selectPropNames, final Condition cond) throws DuplicatedResultException, SQLException;

    /**
     * Finds exactly one record with specified properties and maps it.
     * Ensures both property selection and uniqueness with custom mapping.
     *
     * @param <R> the result type after applying the mapping function
     * @param selectPropNames the properties to select, null for all
     * @param cond the search condition
     * @param rowMapper the function to map the result
     * @return Optional containing the mapped result
     * @throws DuplicatedResultException if more than one record matches
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if rowMapper returns null
     */
    <R> Optional<R> findOnlyOne(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper)
            throws DuplicatedResultException, SQLException, IllegalArgumentException;

    /**
     * Finds exactly one record with specified properties using a bi-function mapper.
     * Maximum flexibility with property selection, uniqueness, and custom mapping.
     *
     * @param <R> the result type after applying the mapping function
     * @param selectPropNames the properties to select, null for all
     * @param cond the search condition
     * @param rowMapper the bi-function to map the result
     * @return Optional containing the mapped result
     * @throws DuplicatedResultException if more than one record matches
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if rowMapper returns null
     */
    <R> Optional<R> findOnlyOne(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper)
            throws DuplicatedResultException, SQLException, IllegalArgumentException;

    /**
     * Queries for a boolean value from a single column.
     * Returns an OptionalBoolean containing the value if found.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalBoolean isActive = dao.queryForBoolean("is_active", CF.eq("id", 123));
     * if (isActive.orElse(false)) {
     *     // User is active
     * }
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param cond the search condition
     * @return OptionalBoolean containing the value
     * @throws SQLException if a database access error occurs
     */
    OptionalBoolean queryForBoolean(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries for a char value from a single column.
     * Returns an OptionalChar containing the value if found.
     *
     * @param singleSelectPropName the property name to select
     * @param cond the search condition
     * @return OptionalChar containing the value
     * @throws SQLException if a database access error occurs
     */
    OptionalChar queryForChar(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries for a byte value from a single column.
     * Returns an OptionalByte containing the value if found.
     *
     * @param singleSelectPropName the property name to select
     * @param cond the search condition
     * @return OptionalByte containing the value
     * @throws SQLException if a database access error occurs
     */
    OptionalByte queryForByte(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries for a short value from a single column.
     * Returns an OptionalShort containing the value if found.
     *
     * @param singleSelectPropName the property name to select
     * @param cond the search condition
     * @return OptionalShort containing the value
     * @throws SQLException if a database access error occurs
     */
    OptionalShort queryForShort(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries for an integer value from a single column.
     * Returns an OptionalInt containing the value if found.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalInt age = dao.queryForInt("age", CF.eq("id", 123));
     * System.out.println("Age: " + age.orElse(0));
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param cond the search condition
     * @return OptionalInt containing the value
     * @throws SQLException if a database access error occurs
     */
    OptionalInt queryForInt(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries for a long value from a single column.
     * Returns an OptionalLong containing the value if found.
     *
     * @param singleSelectPropName the property name to select
     * @param cond the search condition
     * @return OptionalLong containing the value
     * @throws SQLException if a database access error occurs
     */
    OptionalLong queryForLong(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries for a float value from a single column.
     * Returns an OptionalFloat containing the value if found.
     *
     * @param singleSelectPropName the property name to select
     * @param cond the search condition
     * @return OptionalFloat containing the value
     * @throws SQLException if a database access error occurs
     */
    OptionalFloat queryForFloat(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries for a double value from a single column.
     * Returns an OptionalDouble containing the value if found.
     *
     * @param singleSelectPropName the property name to select
     * @param cond the search condition
     * @return OptionalDouble containing the value
     * @throws SQLException if a database access error occurs
     */
    OptionalDouble queryForDouble(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries for a String value from a single column.
     * Returns a Nullable containing the value, which can be null.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<String> name = dao.queryForString("name", CF.eq("id", 123));
     * System.out.println("Name: " + name.orElse("Unknown"));
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param cond the search condition
     * @return Nullable containing the String value
     * @throws SQLException if a database access error occurs
     */
    Nullable<String> queryForString(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries for a Date value from a single column.
     * Returns a Nullable containing the java.sql.Date value.
     *
     * @param singleSelectPropName the property name to select
     * @param cond the search condition
     * @return Nullable containing the Date value
     * @throws SQLException if a database access error occurs
     */
    Nullable<java.sql.Date> queryForDate(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries for a Time value from a single column.
     * Returns a Nullable containing the java.sql.Time value.
     *
     * @param singleSelectPropName the property name to select
     * @param cond the search condition
     * @return Nullable containing the Time value
     * @throws SQLException if a database access error occurs
     */
    Nullable<java.sql.Time> queryForTime(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries for a Timestamp value from a single column.
     * Returns a Nullable containing the java.sql.Timestamp value.
     *
     * @param singleSelectPropName the property name to select
     * @param cond the search condition
     * @return Nullable containing the Timestamp value
     * @throws SQLException if a database access error occurs
     */
    Nullable<java.sql.Timestamp> queryForTimestamp(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries for a byte array from a single column.
     * Returns a Nullable containing the byte array value.
     *
     * @param singleSelectPropName the property name to select
     * @param cond the search condition
     * @return Nullable containing the byte array
     * @throws SQLException if a database access error occurs
     */
    Nullable<byte[]> queryForBytes(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries for a single value of the specified type from a column.
     * Returns a Nullable that can contain null values.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<BigDecimal> balance = dao.queryForSingleResult(
     *     "balance", 
     *     CF.eq("account_id", 123), 
     *     BigDecimal.class
     * );
     * }</pre>
     *
     * @param <V> the value type
     * @param singleSelectPropName the property name to select
     * @param cond the search condition
     * @param targetValueType the class of the target value type
     * @return Nullable containing the value
     * @throws SQLException if a database access error occurs
     */
    <V> Nullable<V> queryForSingleResult(final String singleSelectPropName, final Condition cond, final Class<? extends V> targetValueType) throws SQLException;

    /**
     * Queries for a single non-null value of the specified type.
     * Returns an Optional, empty if no value found or if the value is null.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<String> email = dao.queryForSingleNonNull(
     *     "email", 
     *     CF.eq("id", 123), 
     *     String.class
     * );
     * }</pre>
     *
     * @param <V> the value type
     * @param singleSelectPropName the property name to select
     * @param cond the search condition
     * @param targetValueType the class of the target value type
     * @return Optional containing the non-null value
     * @throws SQLException if a database access error occurs
     */
    <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final Condition cond, final Class<? extends V> targetValueType)
            throws SQLException;

    /**
     * Queries for a single value using a custom row mapper.
     * Provides flexibility in how the single column value is transformed.
     *
     * @param <V> the value type
     * @param singleSelectPropName the property name to select
     * @param cond the search condition
     * @param rowMapper the function to map the result
     * @return Optional containing the mapped value
     * @throws SQLException if a database access error occurs
     */
    @Beta
    <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final Condition cond, final Jdbc.RowMapper<? extends V> rowMapper)
            throws SQLException;

    /**
     * Queries for a unique single value, throwing if multiple rows found.
     * Returns a Nullable that can contain null values.
     *
     * @param <V> the value type
     * @param singleSelectPropName the property name to select
     * @param cond the search condition
     * @param targetValueType the class of the target value type
     * @return Nullable containing the unique value
     * @throws DuplicatedResultException if more than one row matches
     * @throws SQLException if a database access error occurs
     */
    <V> Nullable<V> queryForUniqueResult(final String singleSelectPropName, final Condition cond, final Class<? extends V> targetValueType)
            throws DuplicatedResultException, SQLException;

    /**
     * Queries for a unique non-null single value, throwing if multiple rows found.
     * Combines uniqueness constraint with non-null requirement.
     *
     * @param <V> the value type
     * @param singleSelectPropName the property name to select
     * @param cond the search condition
     * @param targetValueType the class of the target value type
     * @return Optional containing the unique non-null value
     * @throws DuplicatedResultException if more than one row matches
     * @throws SQLException if a database access error occurs
     */
    <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final Condition cond, final Class<? extends V> targetValueType)
            throws DuplicatedResultException, SQLException;

    /**
     * Queries for a unique value using a custom row mapper.
     * Ensures uniqueness while allowing custom value transformation.
     *
     * @param <V> the value type
     * @param singleSelectPropName the property name to select
     * @param cond the search condition
     * @param rowMapper the function to map the result
     * @return Optional containing the unique mapped value
     * @throws DuplicatedResultException if more than one row matches
     * @throws SQLException if a database access error occurs
     */
    @Beta
    <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final Condition cond, final Jdbc.RowMapper<? extends V> rowMapper)
            throws DuplicatedResultException, SQLException;

    /**
     * Executes a query and returns the results as a Dataset.
     * Dataset provides a flexible, column-oriented view of the results.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset ds = dao.query(CF.gt("age", 18));
     * for (int i = 0; i < ds.size(); i++) {
     *     System.out.println(ds.getString(i, "name"));
     * }
     * }</pre>
     *
     * @param cond the search condition
     * @return Dataset containing the query results
     * @throws SQLException if a database access error occurs
     */
    Dataset query(final Condition cond) throws SQLException;

    /**
     * Executes a query for specific columns and returns results as a Dataset.
     * Only the specified properties will be included in the result.
     *
     * @param selectPropNames the properties to select, null for all
     * @param cond the search condition
     * @return Dataset containing the query results
     * @throws SQLException if a database access error occurs
     */
    Dataset query(final Collection<String> selectPropNames, final Condition cond) throws SQLException;

    /**
     * Executes a query and processes results with a custom result extractor.
     * The ResultSet is passed to the extractor for custom processing.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Map<Long, String> idToName = dao.query(
     *     CF.isNotNull("id"),
     *     rs -> {
     *         Map<Long, String> map = new HashMap<>();
     *         while (rs.next()) {
     *             map.put(rs.getLong("id"), rs.getString("name"));
     *         }
     *         return map;
     *     }
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param cond the search condition
     * @param resultExtractor function to process the ResultSet
     * @return the extracted result
     * @throws SQLException if a database access error occurs
     */
    <R> R query(final Condition cond, final Jdbc.ResultExtractor<? extends R> resultExtractor) throws SQLException;

    /**
     * Executes a query for specific columns with a custom result extractor.
     * Combines column selection with custom result processing.
     *
     * @param <R> the result type
     * @param selectPropNames the properties to select, null for all
     * @param cond the search condition
     * @param resultExtractor function to process the ResultSet
     * @return the extracted result
     * @throws SQLException if a database access error occurs
     */
    <R> R query(final Collection<String> selectPropNames, final Condition cond, final Jdbc.ResultExtractor<? extends R> resultExtractor) throws SQLException;

    /**
     * Executes a query with a bi-function result extractor.
     * The extractor receives both ResultSet and column labels.
     *
     * @param <R> the result type
     * @param cond the search condition
     * @param resultExtractor bi-function to process the ResultSet
     * @return the extracted result
     * @throws SQLException if a database access error occurs
     */
    <R> R query(final Condition cond, final Jdbc.BiResultExtractor<? extends R> resultExtractor) throws SQLException;

    /**
     * Executes a query for specific columns with a bi-function result extractor.
     * Maximum flexibility for column selection and result processing.
     *
     * @param <R> the result type
     * @param selectPropNames the properties to select, null for all
     * @param cond the search condition
     * @param resultExtractor bi-function to process the ResultSet
     * @return the extracted result
     * @throws SQLException if a database access error occurs
     */
    <R> R query(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiResultExtractor<? extends R> resultExtractor) throws SQLException;

    /**
     * Returns a list of all entities matching the specified condition.
     * The results are eagerly loaded into memory.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> activeUsers = dao.list(CF.eq("status", "ACTIVE"));
     * for (User user : activeUsers) {
     *     System.out.println(user.getName());
     * }
     * }</pre>
     *
     * @param cond the search condition
     * @return list of matching entities
     * @throws SQLException if a database access error occurs
     */
    List<T> list(final Condition cond) throws SQLException;

    /**
     * Returns a list of results mapped by the provided row mapper.
     * Each row is transformed by the mapper function.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<String> names = dao.list(
     *     CF.eq("active", true),
     *     rs -> rs.getString("name")
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param cond the search condition
     * @param rowMapper function to map each row
     * @return list of mapped results
     * @throws SQLException if a database access error occurs
     */
    <R> List<R> list(final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper) throws SQLException;

    /**
     * Returns a list of results mapped by a bi-function row mapper.
     * The mapper receives both ResultSet and column labels for each row.
     *
     * @param <R> the result type
     * @param cond the search condition
     * @param rowMapper bi-function to map each row
     * @return list of mapped results
     * @throws SQLException if a database access error occurs
     */
    <R> List<R> list(final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper) throws SQLException;

    /**
     * Returns a filtered list of results mapped by the row mapper.
     * Only rows passing the filter are included in the result.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> adultUsers = dao.list(
     *     CF.isNotNull("age"),
     *     rs -> rs.getInt("age") >= 18,  // filter
     *     rs -> mapToUser(rs)             // mapper
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param cond the search condition
     * @param rowFilter predicate to filter rows
     * @param rowMapper function to map filtered rows
     * @return list of filtered and mapped results
     * @throws SQLException if a database access error occurs
     */
    <R> List<R> list(final Condition cond, final Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends R> rowMapper) throws SQLException;

    /**
     * Returns a filtered list using bi-function filter and mapper.
     * Both filter and mapper receive ResultSet and column labels.
     *
     * @param <R> the result type
     * @param cond the search condition
     * @param rowFilter bi-predicate to filter rows
     * @param rowMapper bi-function to map filtered rows
     * @return list of filtered and mapped results
     * @throws SQLException if a database access error occurs
     */
    <R> List<R> list(final Condition cond, final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowMapper<? extends R> rowMapper) throws SQLException;

    /**
     * Returns a list of entities with only the specified properties populated.
     * More efficient than loading full entities when only specific fields are needed.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = dao.list(
     *     Arrays.asList("id", "name", "email"),
     *     CF.eq("department", "IT")
     * );
     * }</pre>
     *
     * @param selectPropNames the properties to select, null for all
     * @param cond the search condition
     * @return list of partially loaded entities
     * @throws SQLException if a database access error occurs
     */
    List<T> list(final Collection<String> selectPropNames, final Condition cond) throws SQLException;

    /**
     * Returns a list of selected properties mapped by the row mapper.
     * Combines property selection with custom mapping.
     *
     * @param <R> the result type
     * @param selectPropNames the properties to select, null for all
     * @param cond the search condition
     * @param rowMapper function to map each row
     * @return list of mapped results
     * @throws SQLException if a database access error occurs
     */
    <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper) throws SQLException;

    /**
     * Returns a list of selected properties mapped by a bi-function mapper.
     * The mapper receives column labels for the selected properties.
     *
     * @param <R> the result type
     * @param selectPropNames the properties to select, null for all
     * @param cond the search condition
     * @param rowMapper bi-function to map each row
     * @return list of mapped results
     * @throws SQLException if a database access error occurs
     */
    <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper) throws SQLException;

    /**
     * Returns a filtered list of selected properties mapped by the row mapper.
     * Combines property selection, filtering, and mapping.
     *
     * @param <R> the result type
     * @param selectPropNames the properties to select, null for all
     * @param cond the search condition
     * @param rowFilter predicate to filter rows
     * @param rowMapper function to map filtered rows
     * @return list of filtered and mapped results
     * @throws SQLException if a database access error occurs
     */
    <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowFilter rowFilter,
            final Jdbc.RowMapper<? extends R> rowMapper) throws SQLException;

    /**
     * Returns a filtered list with bi-function filter and mapper for selected properties.
     * Maximum flexibility for property selection, filtering, and mapping.
     *
     * @param <R> the result type
     * @param selectPropNames the properties to select, null for all
     * @param cond the search condition
     * @param rowFilter bi-predicate to filter rows
     * @param rowMapper bi-function to map filtered rows
     * @return list of filtered and mapped results
     * @throws SQLException if a database access error occurs
     */
    <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowFilter rowFilter,
            final Jdbc.BiRowMapper<? extends R> rowMapper) throws SQLException;

    /**
     * Returns a list of values from a single property/column.
     * The property type is automatically detected and used for mapping.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<String> emails = dao.list("email", CF.eq("newsletter", true));
     * }</pre>
     *
     * @param <R> the result type
     * @param singleSelectPropName the single property to select
     * @param cond the search condition
     * @return list of property values
     * @throws SQLException if a database access error occurs
     */
    default <R> List<R> list(final String singleSelectPropName, final Condition cond) throws SQLException {
        final PropInfo propInfo = ParserUtil.getBeanInfo(targetEntityClass()).getPropInfo(singleSelectPropName);
        final Jdbc.RowMapper<? extends R> rowMapper = propInfo == null ? ColumnOne.getObject() : ColumnOne.get((Type<R>) propInfo.dbType);

        return list(singleSelectPropName, cond, rowMapper);
    }

    /**
     * Returns a list of single property values mapped by the row mapper.
     * Allows custom transformation of single column values.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<String> upperNames = dao.list(
     *     "name", 
     *     CF.isNotNull("name"),
     *     rs -> rs.getString(1).toUpperCase()
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param singleSelectPropName the single property to select
     * @param cond the search condition
     * @param rowMapper function to map the property value
     * @return list of mapped values
     * @throws SQLException if a database access error occurs
     */
    default <R> List<R> list(final String singleSelectPropName, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper) throws SQLException {
        return list(N.asList(singleSelectPropName), cond, rowMapper);
    }

    /**
     * Returns a filtered list of single property values.
     * Only values passing the filter are included.
     *
     * @param <R> the result type
     * @param singleSelectPropName the single property to select
     * @param cond the search condition
     * @param rowFilter predicate to filter values
     * @param rowMapper function to map filtered values
     * @return list of filtered and mapped values
     * @throws SQLException if a database access error occurs
     */
    default <R> List<R> list(final String singleSelectPropName, final Condition cond, final Jdbc.RowFilter rowFilter,
            final Jdbc.RowMapper<? extends R> rowMapper) throws SQLException {
        return list(N.asList(singleSelectPropName), cond, rowFilter, rowMapper);
    }

    /**
     * Returns a lazy Stream of entities matching the condition.
     * The stream uses lazy evaluation - no database connection or query execution occurs until a terminal operation is called.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Stream<User> users = dao.stream(CF.eq("status", "ACTIVE"))) {
     *     users.filter(u -> u.getAge() > 21)
     *          .map(User::getEmail)
     *          .forEach(System.out::println);
     * }
     * }</pre>
     *
     * @param cond the search condition
     * @return lazy stream of matching entities
     * @see ConditionFactory
     */
    @LazyEvaluation
    Stream<T> stream(final Condition cond);

    /**
     * Returns a lazy Stream with custom row mapping.
     * Combines lazy evaluation with custom result transformation.
     *
     * @param <R> the result type
     * @param cond the search condition
     * @param rowMapper function to map each row
     * @return lazy stream of mapped results
     */
    @LazyEvaluation
    <R> Stream<R> stream(final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper);

    /**
     * Returns a lazy Stream with bi-function row mapping.
     * The mapper receives both ResultSet and column labels.
     *
     * @param <R> the result type
     * @param cond the search condition
     * @param rowMapper bi-function to map each row
     * @return lazy stream of mapped results
     */
    @LazyEvaluation
    <R> Stream<R> stream(final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper);

    /**
     * Returns a filtered lazy Stream with row mapping.
     * Only rows passing the filter are included in the stream.
     *
     * @param <R> the result type
     * @param cond the search condition
     * @param rowFilter predicate to filter rows
     * @param rowMapper function to map filtered rows
     * @return lazy stream of filtered and mapped results
     */
    @LazyEvaluation
    <R> Stream<R> stream(final Condition cond, final Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends R> rowMapper);

    /**
     * Returns a filtered lazy Stream with bi-function filter and mapper.
     * Maximum flexibility for stream processing with filtering.
     *
     * @param <R> the result type
     * @param cond the search condition
     * @param rowFilter bi-predicate to filter rows
     * @param rowMapper bi-function to map filtered rows
     * @return lazy stream of filtered and mapped results
     */
    @LazyEvaluation
    <R> Stream<R> stream(final Condition cond, final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowMapper<? extends R> rowMapper);

    /**
     * Returns a lazy Stream of entities with selected properties.
     * Only specified properties are loaded for each entity.
     *
     * @param selectPropNames the properties to select, null for all
     * @param cond the search condition
     * @return lazy stream of partially loaded entities
     */
    @LazyEvaluation
    Stream<T> stream(final Collection<String> selectPropNames, final Condition cond);

    /**
     * Returns a lazy Stream of selected properties with row mapping.
     * Combines property selection with custom mapping in a lazy stream.
     *
     * @param <R> the result type
     * @param selectPropNames the properties to select, null for all
     * @param cond the search condition
     * @param rowMapper function to map each row
     * @return lazy stream of mapped results
     */
    @LazyEvaluation
    <R> Stream<R> stream(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper);

    /**
     * Returns a lazy Stream with bi-function mapping for selected properties.
     * The mapper receives column labels for the selected properties.
     *
     * @param <R> the result type
     * @param selectPropNames the properties to select, null for all
     * @param cond the search condition
     * @param rowMapper bi-function to map each row
     * @return lazy stream of mapped results
     */
    @LazyEvaluation
    <R> Stream<R> stream(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper);

    /**
     * Returns a filtered lazy Stream of selected properties with mapping.
     * Combines property selection, filtering, and mapping in a lazy stream.
     *
     * @param <R> the result type
     * @param selectPropNames the properties to select, null for all
     * @param cond the search condition
     * @param rowFilter predicate to filter rows
     * @param rowMapper function to map filtered rows
     * @return lazy stream of filtered and mapped results
     */
    @LazyEvaluation
    <R> Stream<R> stream(final Collection<String> selectPropNames, final Condition cond, Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends R> rowMapper);

    /**
     * Returns a filtered lazy Stream with maximum flexibility.
     * Combines all features: property selection, bi-function filtering and mapping.
     *
     * @param <R> the result type
     * @param selectPropNames the properties to select, null for all
     * @param cond the search condition
     * @param rowFilter bi-predicate to filter rows
     * @param rowMapper bi-function to map filtered rows
     * @return lazy stream of filtered and mapped results
     */
    @LazyEvaluation
    <R> Stream<R> stream(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowFilter rowFilter,
            final Jdbc.BiRowMapper<? extends R> rowMapper);

    /**
     * Returns a lazy Stream of values from a single property.
     * The property type is automatically detected for mapping.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Stream<String> emails = dao.stream("email", CF.eq("active", true))) {
     *     emails.distinct()
     *           .sorted()
     *           .forEach(System.out::println);
     * }
     * }</pre>
     *
     * @param <R> the result type
     * @param singleSelectPropName the single property to select
     * @param cond the search condition
     * @return lazy stream of property values
     */
    @LazyEvaluation
    default <R> Stream<R> stream(final String singleSelectPropName, final Condition cond) {
        final PropInfo propInfo = ParserUtil.getBeanInfo(targetEntityClass()).getPropInfo(singleSelectPropName);
        final Jdbc.RowMapper<? extends R> rowMapper = propInfo == null ? ColumnOne.getObject() : ColumnOne.get((Type<R>) propInfo.dbType);

        return stream(singleSelectPropName, cond, rowMapper);
    }

    /**
     * Returns a lazy Stream of single property values with custom mapping.
     * Allows transformation of single column values in a stream.
     *
     * @param <R> the result type
     * @param singleSelectPropName the single property to select
     * @param cond the search condition
     * @param rowMapper function to map property values
     * @return lazy stream of mapped values
     */
    @LazyEvaluation
    default <R> Stream<R> stream(final String singleSelectPropName, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper) {
        return stream(N.asList(singleSelectPropName), cond, rowMapper);
    }

    /**
     * Returns a filtered lazy Stream of single property values.
     * Only values passing the filter are included in the stream.
     *
     * @param <R> the result type
     * @param singleSelectPropName the single property to select
     * @param cond the search condition
     * @param rowFilter predicate to filter values
     * @param rowMapper function to map filtered values
     * @return lazy stream of filtered and mapped values
     */
    @LazyEvaluation
    default <R> Stream<R> stream(final String singleSelectPropName, final Condition cond, final Jdbc.RowFilter rowFilter,
            final Jdbc.RowMapper<? extends R> rowMapper) {
        return stream(N.asList(singleSelectPropName), cond, rowFilter, rowMapper);
    }

    /**
     * Returns a paginated Stream of query results as Dataset pages.
     * Each element in the stream represents one page of results. The condition must include orderBy for consistent pagination.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Stream<Dataset> pages = dao.paginate(
     *     CF.criteria().where(CF.gt("id", 0)).orderBy("id"),
     *     100,
     *     (query, lastPageResult) -> {
     *         if (lastPageResult != null && lastPageResult.size() > 0) {
     *             long lastId = lastPageResult.getLong(lastPageResult.size() - 1, "id");
     *             query.setLong(1, lastId);
     *         }
     *     }
     * );
     * }</pre>
     *
     * @param cond the condition with required orderBy clause
     * @param pageSize the number of records per page
     * @param paramSetter function to set parameters for next page based on previous results
     * @return stream of Dataset pages
     */
    @Beta
    @LazyEvaluation
    Stream<Dataset> paginate(final Condition cond, final int pageSize, final Jdbc.BiParametersSetter<? super PreparedQuery, Dataset> paramSetter);

    /**
     * Returns a paginated Stream with custom result extraction.
     * Each page is processed by the result extractor.
     *
     * @param <R> the result type
     * @param cond the condition with required orderBy clause
     * @param pageSize the number of records per page
     * @param paramSetter function to set parameters for next page
     * @param resultExtractor function to process each page's ResultSet
     * @return stream of processed page results
     */
    @Beta
    @LazyEvaluation
    <R> Stream<R> paginate(final Condition cond, final int pageSize, final Jdbc.BiParametersSetter<? super PreparedQuery, R> paramSetter,
            final Jdbc.ResultExtractor<? extends R> resultExtractor);

    /**
     * Returns a paginated Stream with bi-function result extraction.
     * The extractor receives both ResultSet and column labels for each page.
     *
     * @param <R> the result type
     * @param cond the condition with required orderBy clause
     * @param pageSize the number of records per page
     * @param paramSetter function to set parameters for next page
     * @param resultExtractor bi-function to process each page
     * @return stream of processed page results
     */
    @Beta
    @LazyEvaluation
    <R> Stream<R> paginate(final Condition cond, final int pageSize, final Jdbc.BiParametersSetter<? super PreparedQuery, R> paramSetter,
            final Jdbc.BiResultExtractor<? extends R> resultExtractor);

    /**
     * Returns a paginated Stream with selected properties as Dataset pages.
     * Only specified properties are included in each page.
     *
     * @param selectPropNames the properties to select, null for all
     * @param cond the condition with required orderBy clause
     * @param pageSize the number of records per page
     * @param paramSetter function to set parameters for next page
     * @return stream of Dataset pages with selected properties
     */
    @Beta
    @LazyEvaluation
    Stream<Dataset> paginate(final Collection<String> selectPropNames, final Condition cond, final int pageSize,
            final Jdbc.BiParametersSetter<? super PreparedQuery, Dataset> paramSetter);

    /**
     * Returns a paginated Stream of selected properties with custom extraction.
     * Combines property selection with custom page processing.
     *
     * @param <R> the result type
     * @param selectPropNames the properties to select, null for all
     * @param cond the condition with required orderBy clause
     * @param pageSize the number of records per page
     * @param paramSetter function to set parameters for next page
     * @param resultExtractor function to process each page
     * @return stream of processed page results
     */
    @Beta
    @LazyEvaluation
    <R> Stream<R> paginate(final Collection<String> selectPropNames, final Condition cond, final int pageSize,
            final Jdbc.BiParametersSetter<? super PreparedQuery, R> paramSetter, final Jdbc.ResultExtractor<? extends R> resultExtractor);

    /**
     * Returns a paginated Stream with bi-function extraction for selected properties.
     * Maximum flexibility for paginated queries with custom processing.
     *
     * @param <R> the result type
     * @param selectPropNames the properties to select, null for all
     * @param cond the condition with required orderBy clause
     * @param pageSize the number of records per page
     * @param paramSetter function to set parameters for next page
     * @param resultExtractor bi-function to process each page
     * @return stream of processed page results
     */
    @Beta
    @LazyEvaluation
    <R> Stream<R> paginate(final Collection<String> selectPropNames, final Condition cond, final int pageSize,
            final Jdbc.BiParametersSetter<? super PreparedQuery, R> paramSetter, final Jdbc.BiResultExtractor<? extends R> resultExtractor);

    /**
     * Iterates over query results, applying the row consumer to each row.
     * This is useful for processing large result sets without loading all data into memory.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * dao.forEach(
     *     CF.eq("status", "PENDING"),
     *     rs -> processRecord(rs.getLong("id"), rs.getString("data"))
     * );
     * }</pre>
     *
     * @param cond the search condition
     * @param rowConsumer consumer to process each row
     * @throws SQLException if a database access error occurs
     */
    void forEach(final Condition cond, final Jdbc.RowConsumer rowConsumer) throws SQLException;

    /**
     * Iterates over results with a bi-consumer receiving ResultSet and column labels.
     * Provides column metadata along with row data.
     *
     * @param cond the search condition
     * @param rowConsumer bi-consumer to process each row
     * @throws SQLException if a database access error occurs
     */
    void forEach(final Condition cond, final Jdbc.BiRowConsumer rowConsumer) throws SQLException;

    /**
     * Iterates over filtered results, processing only rows that pass the filter.
     * Combines filtering with row processing for efficiency.
     *
     * @param cond the search condition
     * @param rowFilter predicate to filter rows
     * @param rowConsumer consumer for filtered rows
     * @throws SQLException if a database access error occurs
     */
    void forEach(final Condition cond, final Jdbc.RowFilter rowFilter, final Jdbc.RowConsumer rowConsumer) throws SQLException;

    /**
     * Iterates over filtered results with bi-function filter and consumer.
     * Both receive ResultSet and column labels.
     *
     * @param cond the search condition
     * @param rowFilter bi-predicate to filter rows
     * @param rowConsumer bi-consumer for filtered rows
     * @throws SQLException if a database access error occurs
     */
    void forEach(final Condition cond, final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowConsumer rowConsumer) throws SQLException;

    /**
     * Iterates over selected properties, applying the consumer to each row.
     * Only specified properties are retrieved and processed.
     *
     * @param selectPropNames the properties to select, null for all
     * @param cond the search condition
     * @param rowConsumer consumer to process each row
     * @throws SQLException if a database access error occurs
     */
    void forEach(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowConsumer rowConsumer) throws SQLException;

    /**
     * Iterates over selected properties with a bi-consumer.
     * The consumer receives column labels for the selected properties.
     *
     * @param selectPropNames the properties to select, null for all
     * @param cond the search condition
     * @param rowConsumer bi-consumer to process each row
     * @throws SQLException if a database access error occurs
     */
    void forEach(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowConsumer rowConsumer) throws SQLException;

    /**
     * Iterates over filtered results of selected properties.
     * Combines property selection, filtering, and processing.
     *
     * @param selectPropNames the properties to select, null for all
     * @param cond the search condition
     * @param rowFilter predicate to filter rows
     * @param rowConsumer consumer for filtered rows
     * @throws SQLException if a database access error occurs
     */
    void forEach(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowFilter rowFilter, final Jdbc.RowConsumer rowConsumer)
            throws SQLException;

    /**
     * Iterates over filtered results with maximum flexibility.
     * All parameters support bi-function interfaces.
     *
     * @param selectPropNames the properties to select, null for all
     * @param cond the search condition
     * @param rowFilter bi-predicate to filter rows
     * @param rowConsumer bi-consumer for filtered rows
     * @throws SQLException if a database access error occurs
     */
    void forEach(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowConsumer rowConsumer)
            throws SQLException;

    /**
     * Iterates over results using a disposable object array consumer.
     * The array is reused for each row to minimize object allocation.
     * WARNING: Do not store or cache the array parameter as it's reused.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * dao.foreach(
     *     Arrays.asList("id", "name", "age"),
     *     CF.gt("age", 18),
     *     row -> {
     *         Long id = (Long) row.get(0);
     *         String name = (String) row.get(1);
     *         Integer age = (Integer) row.get(2);
     *         // Process immediately, don't store row
     *     }
     * );
     * }</pre>
     *
     * @param selectPropNames the properties to select
     * @param cond the search condition
     * @param rowConsumer consumer that receives reusable row array
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default void foreach(final Collection<String> selectPropNames, final Condition cond, final Consumer<DisposableObjArray> rowConsumer) throws SQLException {
        forEach(selectPropNames, cond, Jdbc.RowConsumer.oneOff(targetEntityClass(), rowConsumer));
    }

    /**
     * Iterates over all results using a disposable object array consumer.
     * Convenience method that selects all properties.
     *
     * @param cond the search condition
     * @param rowConsumer consumer that receives reusable row array
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default void foreach(final Condition cond, final Consumer<DisposableObjArray> rowConsumer) throws SQLException {
        forEach(cond, Jdbc.RowConsumer.oneOff(targetEntityClass(), rowConsumer));
    }

    /**
     * Updates a single property for all records matching the condition.
     * Convenience method for updating one field.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * int updated = dao.update("status", "INACTIVE", CF.lt("lastLogin", thirtyDaysAgo));
     * System.out.println("Deactivated " + updated + " users");
     * }</pre>
     *
     * @param propName the property name to update
     * @param propValue the new value for the property
     * @param cond the condition to match records
     * @return the number of records updated
     * @throws SQLException if a database access error occurs
     */
    default int update(final String propName, final Object propValue, final Condition cond) throws SQLException {
        final Map<String, Object> updateProps = new HashMap<>();
        updateProps.put(propName, propValue);

        return update(updateProps, cond);
    }

    /**
     * Updates multiple properties for all records matching the condition.
     * The map keys are property names and values are the new values.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Map<String, Object> updates = new HashMap<>();
     * updates.put("lastModified", new Date());
     * updates.put("modifiedBy", currentUser);
     * int count = dao.update(updates, CF.eq("status", "PENDING"));
     * }</pre>
     *
     * @param updateProps map of property names to new values
     * @param cond the condition to match records
     * @return the number of records updated
     * @throws SQLException if a database access error occurs
     */
    int update(final Map<String, Object> updateProps, final Condition cond) throws SQLException;

    /**
     * Updates records matching the condition with all non-null properties from the entity.
     * This updates all properties except those with null values.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User updates = new User();
     * updates.setStatus("ACTIVE");
     * updates.setLastLogin(new Date());
     * int count = dao.update(updates, CF.eq("id", userId));
     * }</pre>
     *
     * @param entity the entity containing update values
     * @param cond the condition to match records
     * @return the number of records updated
     * @throws SQLException if a database access error occurs
     */
    default int update(final T entity, final Condition cond) throws SQLException {
        @SuppressWarnings("deprecation")
        final Collection<String> propNamesToUpdate = QueryUtil.getUpdatePropNames(targetEntityClass(), null);

        return update(entity, propNamesToUpdate, cond);
    }

    /**
     * Updates records with only the specified properties from the entity.
     * This allows precise control over which fields are updated.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setEmail("new@example.com");
     * user.setPhone("555-1234");
     * int count = dao.update(
     *     user, 
     *     Arrays.asList("email", "phone"),
     *     CF.eq("id", userId)
     * );
     * }</pre>
     *
     * @param entity the entity containing update values
     * @param propNamesToUpdate the property names to update
     * @param cond the condition to match records
     * @return the number of records updated
     * @throws SQLException if a database access error occurs
     */
    int update(final T entity, final Collection<String> propNamesToUpdate, final Condition cond) throws SQLException;

    /**
     * Performs an upsert operation - inserts if not exists, updates if exists.
     * The existence check is based on the specified unique properties.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User("john@example.com", "John Doe");
     * User saved = dao.upsert(user, Arrays.asList("email"));
     * // Inserts if email doesn't exist, updates if it does
     * }</pre>
     *
     * @param entity the entity to insert or update
     * @param uniquePropNamesForQuery property names that uniquely identify the record
     * @return the saved entity (newly inserted or updated)
     * @throws SQLException if a database access error occurs
     */
    default T upsert(final T entity, final List<String> uniquePropNamesForQuery) throws SQLException {
        N.checkArgNotNull(entity, cs.entity);
        N.checkArgNotEmpty(uniquePropNamesForQuery, cs.uniquePropNamesForQuery);

        final Condition cond = CF.eqAnd(entity, uniquePropNamesForQuery);

        return upsert(entity, cond);
    }

    /**
     * Performs an upsert operation based on a custom condition.
     * More flexible than property-based upsert for complex conditions.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setEmail("john@example.com");
     * user.setStatus("ACTIVE");
     * 
     * Condition cond = CF.and(
     *     CF.eq("email", user.getEmail()),
     *     CF.eq("deleted", false)
     * );
     * 
     * User saved = dao.upsert(user, cond);
     * }</pre>
     *
     * @param entity the entity to insert or update
     * @param cond condition to check for existence
     * @return the saved entity (newly inserted or updated)
     * @throws SQLException if a database access error occurs
     */
    default T upsert(final T entity, final Condition cond) throws SQLException {
        N.checkArgNotNull(entity, cs.entity);
        N.checkArgNotNull(cond, cs.cond);

        final T dbEntity = findOnlyOne(cond).orElseNull();

        if (dbEntity == null) {
            save(entity);
            return entity;
        } else {
            Beans.merge(entity, dbEntity);
            update(dbEntity, cond);
            return dbEntity;
        }
    }

    /**
     * Deletes all records matching the specified condition.
     * Returns the count of deleted records.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * int deleted = dao.delete(CF.lt("expiryDate", new Date()));
     * System.out.println("Deleted " + deleted + " expired records");
     * }</pre>
     *
     * @param cond the condition to match records for deletion
     * @return the number of records deleted
     * @throws SQLException if a database access error occurs
     */
    int delete(final Condition cond) throws SQLException;

    /**
     * Executes an asynchronous database operation using the default executor.
     * The operation runs in a separate thread and returns a ContinuableFuture.
     * Note: Transactions started in the current thread are NOT propagated to the async operation.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ContinuableFuture<List<User>> future = dao.asyncCall(d -> 
     *     d.list(CF.eq("status", "ACTIVE"))
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
     */
    @Beta
    @NonDBOperation
    default <R> ContinuableFuture<R> asyncCall(final Throwables.Function<? super TD, ? extends R, SQLException> sqlAction) {
        return asyncCall(sqlAction, executor());
    }

    /**
     * Executes an asynchronous database operation using the specified executor.
     * Provides control over which thread pool executes the operation.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService customExecutor = Executors.newFixedThreadPool(10);
     * 
     * ContinuableFuture<Integer> future = dao.asyncCall(
     *     d -> d.update("status", "PROCESSED", CF.eq("status", "PENDING")),
     *     customExecutor
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param sqlAction function that performs database operations
     * @param executor the executor to run the operation
     * @return ContinuableFuture with the operation result
     */
    @Beta
    @NonDBOperation
    default <R> ContinuableFuture<R> asyncCall(final Throwables.Function<? super TD, ? extends R, SQLException> sqlAction, final Executor executor) {
        N.checkArgNotNull(sqlAction, cs.func);
        N.checkArgNotNull(executor, cs.executor);

        final TD tdao = (TD) this;

        return ContinuableFuture.call(() -> sqlAction.apply(tdao), executor);
    }

    /**
     * Executes an asynchronous database operation without return value using default executor.
     * Useful for fire-and-forget operations like logging or cleanup.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * dao.asyncRun(d -> {
     *     d.delete(CF.lt("createdDate", oneYearAgo));
     *     d.update("archived", true, CF.lt("lastAccess", sixMonthsAgo));
     * });
     * }</pre>
     *
     * @param sqlAction consumer that performs database operations
     * @return ContinuableFuture that completes when operation finishes
     */
    @Beta
    @NonDBOperation
    default ContinuableFuture<Void> asyncRun(final Throwables.Consumer<? super TD, SQLException> sqlAction) {
        return asyncRun(sqlAction, executor());
    }

    /**
     * Executes an asynchronous database operation without return value using specified executor.
     * Combines async execution with custom thread pool management.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);
     * 
     * dao.asyncRun(
     *     d -> d.save(generateDailyReport()),
     *     scheduler
     * ).thenRun(() -> 
     *     System.out.println("Report saved")
     * );
     * }</pre>
     *
     * @param sqlAction consumer that performs database operations
     * @param executor the executor to run the operation
     * @return ContinuableFuture that completes when operation finishes
     */
    @Beta
    @NonDBOperation
    default ContinuableFuture<Void> asyncRun(final Throwables.Consumer<? super TD, SQLException> sqlAction, final Executor executor) {
        N.checkArgNotNull(sqlAction, cs.action);
        N.checkArgNotNull(executor, cs.executor);

        final TD tdao = (TD) this;

        return ContinuableFuture.run(() -> sqlAction.accept(tdao), executor);
    }
}