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

/**
 * Insert capability of {@link Dao}: {@code save}/{@code batchSave}. Extends {@link DaoBase}.
 * 
 * @param <T> the entity type managed by this DAO
 * @param <TD> the self-referencing DAO type
 * @see Dao
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
sealed interface InsertOps<T, TD extends DaoBase<T, TD>> extends DaoBase<T, TD> permits Dao, NoUpdateDao, CrudInsertOps, UncheckedInsertOps {
    /**
     * Saves (inserts) the specified entity to the database.
     * All insertable properties of the entity (i.e., excluding {@code @ReadOnly}, {@code @Transient}, etc.)
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
