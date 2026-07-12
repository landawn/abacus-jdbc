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

import java.util.Collection;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.JdbcUtil;

/**
 * Unchecked-exception insert capability: the {@link InsertOps} operations re-declared to throw
 * {@link com.landawn.abacus.exception.UncheckedSQLException}.
 * 
 * @param <T> the entity type managed by this DAO
 * @param <TD> the self-referencing DAO type
 * @see InsertOps
 * @see UncheckedDao
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
@Beta
sealed interface UncheckedInsertOps<T, TD extends UncheckedDaoBase<T, TD>> extends InsertOps<T, TD>, UncheckedDaoBase<T, TD>
        permits UncheckedDao, UncheckedNoUpdateDao, UncheckedCrudInsertOps {
    /**
     * Saves (inserts) the specified entity to the database.
     * All insertable properties of the entity (i.e., excluding {@code @ReadOnly}, {@code @Transient}, etc.)
     * are included in the INSERT statement. The ID property is included only when it has been set
     * (i.e., is not the default value), allowing the database to generate it otherwise.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User("John", "Doe");
     * userDao.save(user);
     * }</pre>
     *
     * @param entity the entity to insert
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    void save(final T entity) throws UncheckedSQLException;

    /**
     * Saves the specified entity with only the specified properties.
     * Properties not included in {@code propNamesToSave} will not be persisted.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User("John", "Doe");
     * userDao.save(user, Arrays.asList("firstName", "email"));
     * }</pre>
     *
     * @param entity the entity to insert
     * @param propNamesToSave the property names to include in the INSERT
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    void save(final T entity, final Collection<String> propNamesToSave) throws UncheckedSQLException;

    /**
     * Saves the entity using a named insert SQL statement. The SQL statement should contain
     * named parameters that will be populated from the entity properties.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "INSERT INTO users (first_name, last_name) VALUES (:firstName, :lastName)";
     * User user = new User("John", "Doe");
     * userDao.save(sql, user);
     * }</pre>
     *
     * @param namedInsertSql the named insert SQL statement
     * @param entity the entity to save
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    void save(final String namedInsertSql, final T entity) throws UncheckedSQLException;

    /**
     * Batch saves the specified entities to the database using the default batch size.
     * This method is more efficient than saving entities one by one.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = Arrays.asList(
     *     new User("John", "Doe"),
     *     new User("Jane", "Smith")
     * );
     * userDao.batchSave(users);
     * }</pre>
     *
     * @param entities the collection of entities to save
     * @throws UncheckedSQLException if a database access error occurs
     * @see #batchSave(Collection, int)
     */
    @Override
    default void batchSave(final Collection<? extends T> entities) throws UncheckedSQLException {
        batchSave(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch saves the specified entities to the database using the specified batch size.
     * The entities will be saved in batches to improve performance.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getLargeUserList();
     * userDao.batchSave(users, 1000);
     * }</pre>
     *
     * @param entities the collection of entities to save
     * @param batchSize the number of entities to process in each batch. The operation will split large collections into chunks of this size.
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    void batchSave(final Collection<? extends T> entities, final int batchSize) throws UncheckedSQLException;

    /**
     * Batch saves the specified entities with only the specified properties using the default batch size.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getUserList();
     * userDao.batchSave(users, Arrays.asList("firstName", "email"));
     * }</pre>
     *
     * @param entities the collection of entities to save
     * @param propNamesToSave the properties to save for each entity
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default void batchSave(final Collection<? extends T> entities, final Collection<String> propNamesToSave) throws UncheckedSQLException {
        batchSave(entities, propNamesToSave, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch saves the specified entities with only the specified properties using the specified batch size.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getLargeUserList();
     * userDao.batchSave(users, Arrays.asList("firstName", "email"), 500);
     * }</pre>
     *
     * @param entities the collection of entities to save
     * @param propNamesToSave the properties to save for each entity
     * @param batchSize the number of entities to process in each batch. The operation will split large collections into chunks of this size.
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    void batchSave(final Collection<? extends T> entities, final Collection<String> propNamesToSave, final int batchSize) throws UncheckedSQLException;

    /**
     * Batch saves entities using a named insert SQL statement with the default batch size.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "INSERT INTO users (first_name, last_name) VALUES (:firstName, :lastName)";
     * List<User> users = getUserList();
     * userDao.batchSave(sql, users);
     * }</pre>
     *
     * @param namedInsertSql the named insert SQL statement
     * @param entities the collection of entities to save
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default void batchSave(final String namedInsertSql, final Collection<? extends T> entities) throws UncheckedSQLException {
        batchSave(namedInsertSql, entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch saves entities using a named insert SQL statement with the specified batch size.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "INSERT INTO users (first_name, last_name) VALUES (:firstName, :lastName)";
     * List<User> users = getLargeUserList();
     * userDao.batchSave(sql, users, 1000);
     * }</pre>
     *
     * @param namedInsertSql the named insert SQL statement
     * @param entities the collection of entities to save
     * @param batchSize the number of entities to process in each batch. The operation will split large collections into chunks of this size.
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    void batchSave(final String namedInsertSql, final Collection<? extends T> entities, final int batchSize) throws UncheckedSQLException;

}
