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
import java.util.List;

import javax.sql.DataSource;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.DuplicateResultException;
import com.landawn.abacus.jdbc.CallableQuery;
import com.landawn.abacus.jdbc.IsolationLevel;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.NamedQuery;
import com.landawn.abacus.jdbc.PreparedQuery;
import com.landawn.abacus.jdbc.cs;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.query.QueryUtil;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.util.Beans;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Throwables;

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
 *   <li>Proper indexing is a key factor in many database performance issues</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long, UserDao> {
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
 *     Stream<User> allUsers(); // Stream-returning methods must not declare 'throws SQLException'
 * }
 *
 * // Usage
 * UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource);
 * User user = userDao.getFirstAndLastNameBy(123L);
 * }</pre>
 *
 * <p><b>Transaction Example:</b></p>
 * <pre>{@code
 * final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
 * try {
 *     User user = userDao.gett(id);
 *     userDao.update(user);
 *     tran.commit();
 * } finally {
 *     tran.rollbackIfNotCommitted();
 * }
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the self-type parameter for fluent API support; must be the concrete sub-DAO interface
 *
 * @see JdbcUtil#createDao(Class, DataSource)
 * @see JdbcUtil#createDao(Class, DataSource, JdbcUtil.DaoCreationOptions)
 * @see JdbcUtil#prepareQuery(javax.sql.DataSource, String)
 * @see JdbcUtil#prepareNamedQuery(javax.sql.DataSource, String)
 * @see JdbcUtil#beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)
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
     * PreparedQuery query = dao.prepareQuery("INSERT INTO users (name) VALUES (?)", true);
     * Optional<Long> generatedId = query.setString(1, "John").insert();
     * }</pre>
     *
     * @param sql the SQL query string
     * @param generateKeys {@code true} to return generated keys, {@code false} otherwise
     * @return a PreparedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final String sql, final boolean generateKeys) throws SQLException {
        return JdbcUtil.prepareQuery(dataSource(), sql, generateKeys);
    }

    /**
     * Creates a PreparedQuery that will return specific columns as generated keys.
     * This is useful when you need to retrieve specific auto-generated column values.
     *
     * @param sql the SQL query string
     * @param returnColumnIndexes array of column indexes to return as generated keys
     * @return a PreparedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final String sql, final int[] returnColumnIndexes) throws SQLException {
        return JdbcUtil.prepareQuery(dataSource(), sql, returnColumnIndexes);
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
     * @param sql the SQL query string
     * @param returnColumnNames array of column names to return as generated keys
     * @return a PreparedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final String sql, final String[] returnColumnNames) throws SQLException {
        return JdbcUtil.prepareQuery(dataSource(), sql, returnColumnNames);
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
     * Creates a NamedQuery with the option to generate keys for INSERT statements.
     * Combines named parameters with auto-generated key retrieval.
     *
     * @param namedSql the named SQL query string
     * @param generateKeys {@code true} to return generated keys
     * @return a NamedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final String namedSql, final boolean generateKeys) throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedSql, generateKeys);
    }

    /**
     * Creates a NamedQuery that will return specific columns as generated keys.
     * Useful for INSERT statements with named parameters that need to retrieve auto-generated values.
     *
     * @param namedSql the named SQL query string
     * @param returnColumnIndexes array of column indexes to return
     * @return a NamedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final String namedSql, final int[] returnColumnIndexes) throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedSql, returnColumnIndexes);
    }

    /**
     * Creates a NamedQuery that will return specific named columns as generated keys.
     * Provides the most readable way to retrieve auto-generated values with named queries.
     *
     * @param namedSql the named SQL query string
     * @param returnColumnNames array of column names to return
     * @return a NamedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final String namedSql, final String[] returnColumnNames) throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedSql, returnColumnNames);
    }

    /**
     * Creates a NamedQuery from a pre-parsed SQL object with key generation option.
     *
     * @param namedSql the pre-parsed named query
     * @param generateKeys {@code true} to return generated keys
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
     * @param namedSql the pre-parsed named query
     * @param returnColumnIndexes array of column indexes to return
     * @return a NamedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final ParsedSql namedSql, final int[] returnColumnIndexes) throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedSql, returnColumnIndexes);
    }

    /**
     * Creates a NamedQuery from a pre-parsed SQL with specific return columns by name.
     *
     * @param namedSql the pre-parsed named query
     * @param returnColumnNames array of column names to return
     * @return a NamedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final ParsedSql namedSql, final String[] returnColumnNames) throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedSql, returnColumnNames);
    }

    /**
     * Creates a NamedQuery using a custom statement creator function.
     * Provides maximum control over statement creation with named parameters.
     *
     * @param namedSql the named SQL query string
     * @param stmtCreator function to create the PreparedStatement
     * @return a NamedQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final String namedSql, final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
            throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedSql, stmtCreator);
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
     * Creates a CallableQuery for executing stored procedures or functions.
     * The query should use the JDBC escape syntax: {@code {call procedure_name(?, ?)}}
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Jdbc.OutParamResult outParams = dao.prepareCallableQuery("{call get_user_count(?)}")
     *                                    .registerOutParameter(1, Types.INTEGER)
     *                                    .executeAndGetOutParameters();
     * int count = outParams.getOutParamValue(1);
     * }</pre>
     *
     * @param sql the stored procedure call string
     * @return a CallableQuery instance
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @NonDBOperation
    default CallableQuery prepareCallableQuery(final String sql) throws SQLException {
        return JdbcUtil.prepareCallableQuery(dataSource(), sql);
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
     * @return the saved entity (the input entity if it was newly inserted; otherwise the merged existing entity that was updated)
     * @throws IllegalArgumentException if {@code entity} is {@code null} or {@code uniquePropNamesForQuery} is {@code null} or empty
     * @throws SQLException if a database access error occurs
     * @throws DuplicateResultException if more than one record matches
     * @see #upsert(Object, Condition)
     */
    default T upsert(final T entity, final List<String> uniquePropNamesForQuery) throws SQLException {
        N.checkArgNotNull(entity, cs.entity);
        N.checkArgNotEmpty(uniquePropNamesForQuery, cs.uniquePropNamesForQuery);

        final Condition cond = Filters.allEqual(entity, uniquePropNamesForQuery);

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
     * @throws IllegalArgumentException if {@code entity} or {@code cond} is {@code null}
     * @throws SQLException if a database access error occurs
     * @throws DuplicateResultException if more than one record matches the specified condition
     */
    default T upsert(final T entity, final Condition cond) throws SQLException {
        N.checkArgNotNull(entity, cs.entity);
        N.checkArgNotNull(cond, cs.cond);

        final T dbEntity = findOnlyOne(cond).orElseNull();

        if (dbEntity == null) {
            save(entity);
            return entity;
        } else {
            final Class<?> cls = entity.getClass();
            final List<String> idPropNameList = QueryUtil.getIdPropNames(cls);

            if (N.isEmpty(idPropNameList)) {
                Beans.mergeInto(entity, dbEntity);
                update(dbEntity, cond);
            } else {
                Beans.mergeInto(entity, dbEntity, false, N.newHashSet(idPropNameList));
                final Condition idCond = Filters.allEqual(dbEntity, idPropNameList);
                update(dbEntity, idCond);
            }

            return dbEntity;
        }
    }

}
