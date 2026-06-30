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

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.NamedQuery;
import com.landawn.abacus.jdbc.PreparedQuery;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.query.ParsedSql;

/**
 * Insert capability of {@link Dao}: {@code save}/{@code batchSave} and the generated-key
 * {@code prepareQuery}/{@code prepareNamedQuery} overloads. Extends {@link ReadOps}.
 * 
 * @param <T> the entity type managed by this DAO
 * @param <TD> the self-referencing DAO type
 * @see Dao
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
sealed interface InsertOps<T, TD extends ReadOps<T, TD>> extends ReadOps<T, TD> permits Dao, NoUpdateDao, CrudInsertOps, UncheckedInsertOps {
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
     * Saves (inserts) the specified entity to the database.
     * All insertable properties of the entity (i.e., excluding {@code @ReadOnly}, {@code @NonInsertable}, etc.)
     * are included in the INSERT statement. The ID property is included only when it has been set
     * (i.e., is not the default value), allowing the database to generate it otherwise.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User("John", "Doe", "john@example.com");
     * dao.save(user);
     * }</pre>
     *
     * @param entity the entity to insert
     * @throws SQLException if a database access error occurs
     */
    void save(final T entity) throws SQLException;

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
     * @param entity the entity to insert
     * @param propNamesToSave the property names to include in the INSERT
     * @throws SQLException if a database access error occurs
     */
    void save(final T entity, final Collection<String> propNamesToSave) throws SQLException;

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
     * @param namedInsertSql the named INSERT SQL statement
     * @param entity the entity providing the parameter values
     * @throws SQLException if a database access error occurs
     */
    void save(final String namedInsertSql, final T entity) throws SQLException;

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
     * dao.batchSave(users, 1000);   // Insert in batches of 1000
     * }</pre>
     *
     * @param entitiesToSave the collection of entities to insert
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
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
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @throws SQLException if a database access error occurs
     */
    void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave, final int batchSize) throws SQLException;

    /**
     * Batch saves entities using a custom named INSERT SQL with default batch size.
     * The SQL should use named parameters matching entity properties.
     *
     * @param namedInsertSql the named INSERT SQL statement
     * @param entitiesToSave the entities providing parameter values
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default void batchSave(final String namedInsertSql, final Collection<? extends T> entitiesToSave) throws SQLException {
        batchSave(namedInsertSql, entitiesToSave, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch saves entities using a custom named INSERT SQL with specified batch size.
     * Provides maximum control over batch insert operations.
     *
     * @param namedInsertSql the named INSERT SQL statement
     * @param entitiesToSave the entities providing parameter values
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @throws SQLException if a database access error occurs
     */
    @Beta
    void batchSave(final String namedInsertSql, final Collection<? extends T> entitiesToSave, final int batchSize) throws SQLException;

}
