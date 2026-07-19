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
import java.util.List;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;

/**
 * Insert capability of {@link CrudDao}: generated-ID extraction and generation, plus
 * {@code insert}/{@code batchInsert} returning generated ids.
 * Extends {@link InsertOps}.
 *
 * <p><b>{@code insert} vs {@code save}:</b> {@code insert}/{@code batchInsert} perform the same INSERT
 * as the inherited {@link InsertOps#save save}/{@code batchSave}, but <em>return</em> the generated
 * id(s) instead of {@code void}. Use {@code insert} when you need the generated key; use the inherited
 * {@code save} for fire-and-forget inserts. The two verbs name the same database operation and differ
 * only in the return value.</p>
 *
 * @param <T> entity type
 * @param <ID> id type
 * @param <TD> self DAO type
 * @see CrudDao
 * @see InsertOps
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
sealed interface CrudInsertOps<T, ID, TD extends DaoBase<T, TD>> extends InsertOps<T, TD> permits CrudDao, NonUpdateCrudDao, UncheckedCrudInsertOps {
    /**
     * Returns a {@link Jdbc.BiRowMapper} that extracts an ID from generated-key rows returned by an insert.
     * Override this method when the framework's default ID extraction strategy is not suitable.
     *
     * <p>The default implementation returns {@code null}, which selects the default extractor.</p>
     *
     * <pre>{@code
     * @Override
     * public Jdbc.BiRowMapper<Long> idExtractor() {
     *     return (rs, columnLabels) -> rs.getLong("id");
     * }
     * }</pre>
     *
     * @return an ID extractor, or {@code null} to use the default extraction strategy
     */
    @SuppressWarnings("SameReturnValue")
    @NonDBOperation
    default Jdbc.BiRowMapper<ID> idExtractor() {
        return null;
    }

    /**
     * Generates a new ID for entity insertion.
     *
     * <p>Override this method for client-side strategies such as UUIDs or sequences. The default
     * implementation throws because ID generation is normally handled by the database.</p>
     *
     * @return the generated ID
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if client-side ID generation is not supported
     * @deprecated ID generation should typically be handled by the database. Override this method
     *             only when a client-side ID generation strategy is required.
     */
    @Deprecated
    @NonDBOperation
    default ID generateId() throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException("ID generation is not supported by default");
    }

    /**
     * Inserts the specified entity into the database and returns its ID.
     * All insertable properties of the entity will be included in the INSERT statement.
     *
     * <p>If the database generates the ID (for example via an auto-increment column), the generated
     * ID is retrieved, written back to the entity's ID property (where applicable) and returned. If the
     * database does not generate a key, the entity's existing ID value is returned instead.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User("John", "Doe", "john@example.com");
     * Long userId = userDao.insert(user);
     * System.out.println("Created user with ID: " + userId);
     * }</pre>
     *
     * @param entity the entity to insert (must not be {@code null})
     * @return the ID of the inserted entity (either database-generated or entity-provided)
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if {@code entity} is {@code null}
     */
    ID insert(final T entity) throws SQLException;

    /**
     * Inserts the specified entity with only the specified properties.
     * This is useful when you want to insert an entity with only certain fields populated.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setEmail("john@example.com");
     * user.setCreatedDate(new Date());
     * Long userId = userDao.insert(user, Arrays.asList("email", "createdDate"));
     * }</pre>
     *
     * @param entity the entity to insert (must not be {@code null})
     * @param propNamesToInsert the property names to include in the INSERT statement (must not be {@code null} or empty)
     * @return the ID of the inserted entity (either database-generated or entity-provided)
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if {@code entity} is {@code null}, or if {@code propNamesToInsert} is {@code null} or empty
     */
    ID insert(final T entity, final Collection<String> propNamesToInsert) throws SQLException;

    /**
     * Inserts an entity using a custom named SQL insert statement.
     * The SQL should use named parameters that match the entity's property names.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "INSERT INTO users (name, email, status) VALUES (:name, :email, 'ACTIVE')";
     * User user = new User("John", "john@example.com");
     * Long userId = userDao.insert(sql, user);
     * }</pre>
     *
     * @param namedInsertSql the named parameter SQL insert statement
     * @param entity the entity whose properties will be bound to the named parameters
     * @return the ID of the inserted entity (either database-generated or entity-provided)
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if {@code namedInsertSql} is {@code null} or empty, or if {@code entity} is {@code null}
     */
    ID insert(final String namedInsertSql, final T entity) throws SQLException;

    /**
     * Performs batch insert of multiple entities using the default batch size
     * ({@link JdbcUtil#DEFAULT_BATCH_SIZE}).
     * This method is more efficient than inserting entities one by one.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = Arrays.asList(
     *     new User("John", "john@example.com"),
     *     new User("Jane", "jane@example.com")
     * );
     * List<Long> ids = userDao.batchInsert(users);
     * }</pre>
     *
     * @param entities the collection of entities to insert
     * @return a list of generated IDs in the same order as the input entities; an empty list if {@code entities} is {@code null} or empty
     * @throws SQLException if a database access error occurs
     */
    default List<ID> batchInsert(final Collection<? extends T> entities) throws SQLException {
        return batchInsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch insert of multiple entities with a specified batch size.
     * Large collections will be processed in batches of the specified size.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> largeUserList = loadUsers();                      // 10000 users
     * List<Long> ids = userDao.batchInsert(largeUserList, 1000);   // Process in batches of 1000
     * }</pre>
     *
     * @param entities the collection of entities to insert
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return a list of generated IDs in the same order as the input entities; an empty list if {@code entities} is {@code null} or empty
     * @throws IllegalArgumentException if {@code batchSize} is not positive
     * @throws SQLException if a database access error occurs
     */
    List<ID> batchInsert(final Collection<? extends T> entities, final int batchSize) throws SQLException;

    /**
     * Performs batch insert with only the specified properties for all entities.
     * Uses the default batch size ({@link JdbcUtil#DEFAULT_BATCH_SIZE}).
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = loadUsers();
     * // Only insert email and createdDate for all users
     * List<Long> ids = userDao.batchInsert(users, Arrays.asList("email", "createdDate"));
     * }</pre>
     *
     * @param entities the collection of entities to insert
     * @param propNamesToInsert the property names to include in the INSERT statement (must not be {@code null} or empty)
     * @return a list of generated IDs in the same order as the input entities; an empty list if {@code entities} is {@code null} or empty
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if {@code propNamesToInsert} is {@code null} or empty
     */
    default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert) throws SQLException {
        return batchInsert(entities, propNamesToInsert, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch insert with only specified properties and custom batch size.
     * This provides fine-grained control over both what fields are inserted and how the batch is processed.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> largeUserList = loadUsers();   // 20000 users
     * // Insert only name and email fields in batches of 1000
     * List<Long> ids = userDao.batchInsert(largeUserList, Arrays.asList("name", "email"), 1000);
     * }</pre>
     *
     * @param entities the collection of entities to insert
     * @param propNamesToInsert the property names to include in the INSERT statement (must not be {@code null} or empty)
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return a list of generated IDs in the same order as the input entities; an empty list if {@code entities} is {@code null} or empty
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if {@code propNamesToInsert} is {@code null} or empty, or if {@code batchSize} is not positive
     */
    List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert, final int batchSize) throws SQLException;

    /**
     * Performs batch insert using a custom named SQL statement with the default batch size
     * ({@link JdbcUtil#DEFAULT_BATCH_SIZE}).
     * This is useful for complex insert scenarios that require custom SQL.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "INSERT INTO users (name, email, status) VALUES (:name, :email, 'PENDING')";
     * List<User> users = loadPendingUsers();
     * List<Long> ids = userDao.batchInsert(sql, users);
     * }</pre>
     *
     * @param namedInsertSql the named parameter SQL insert statement
     * @param entities the collection of entities whose properties will be bound to the named parameters
     * @return a list of generated IDs in the same order as the input entities; an empty list if {@code entities} is {@code null} or empty
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default List<ID> batchInsert(final String namedInsertSql, final Collection<? extends T> entities) throws SQLException {
        return batchInsert(namedInsertSql, entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch insert using a custom named SQL statement with specified batch size.
     * Combines custom SQL flexibility with batch processing efficiency.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "INSERT INTO users (name, email, status, created_date) " +
     *              "VALUES (:name, :email, 'ACTIVE', CURRENT_TIMESTAMP)";
     * List<User> largeUserList = loadNewUsers();   // 15000 users
     * List<Long> ids = userDao.batchInsert(sql, largeUserList, 1000);
     * }</pre>
     *
     * @param namedInsertSql the named parameter SQL insert statement
     * @param entities the collection of entities whose properties will be bound to the named parameters
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return a list of generated IDs in the same order as the input entities; an empty list if {@code entities} is {@code null} or empty
     * @throws SQLException if a database access error occurs
     */
    @Beta
    List<ID> batchInsert(final String namedInsertSql, final Collection<? extends T> entities, final int batchSize) throws SQLException;

}
