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
 * {@link UncheckedSQLException}.
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the self-referencing DAO type
 * @see InsertOps
 * @see UncheckedDao
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
@Beta
sealed interface UncheckedInsertOps<T, TD extends UncheckedDaoBase<T, TD>> extends InsertOps<T, TD>, UncheckedDaoBase<T, TD>
        permits UncheckedDao, UncheckedNonUpdateDao, UncheckedCrudInsertOps {
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
     * @throws IllegalArgumentException if {@code entity} is {@code null}
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing, binding, or executing an INSERT statement fails
     */
    @Override
    void save(final T entity) throws IllegalArgumentException, UncheckedSQLException;

    /**
     * Saves (inserts) the specified entity with only the specified properties.
     * Only the listed properties will be included in the INSERT statement.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User("John", "Doe");
     * userDao.save(user, Arrays.asList("firstName", "email"));
     * }</pre>
     *
     * @param entity the entity to insert
     * @param propNamesToSave the property names to include in the INSERT (must not be {@code null} or empty)
     * @throws IllegalArgumentException if {@code entity} is {@code null}, or if {@code propNamesToSave} is {@code null} or empty
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing, binding, or executing an INSERT statement fails
     */
    @Override
    void save(final T entity, final Collection<String> propNamesToSave) throws IllegalArgumentException, UncheckedSQLException;

    /**
     * Saves (inserts) the entity using a custom named INSERT SQL statement.
     * The SQL should use named parameters that match the entity properties.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "INSERT INTO users (first_name, last_name) VALUES (:firstName, :lastName)";
     * User user = new User("John", "Doe");
     * userDao.save(sql, user);
     * }</pre>
     *
     * @param namedInsertSql the named INSERT SQL statement
     * @param entity the entity providing the parameter values
     * @throws IllegalArgumentException if {@code namedInsertSql} is {@code null} or empty, or if {@code entity} is {@code null},
     *                                  or if {@code namedInsertSql} contains positional (unnamed) parameters,
     *                                  or if {@code entity} has no property for a named parameter in {@code namedInsertSql}
     *                                  other than the reserved {@code now}, {@code sysTime} and {@code sysDate}
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing, binding, or executing an INSERT statement fails
     */
    @Override
    void save(final String namedInsertSql, final T entity) throws IllegalArgumentException, UncheckedSQLException;

    /**
     * Batch saves (inserts) multiple entities using the default batch size.
     * More efficient than saving entities one by one.
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
     * @param entities the collection of entities to insert
     * @throws IllegalStateException if an existing transaction on the current thread is no longer active and cannot accept
     *         the internally required transaction scope
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing,
     *         binding, or executing an INSERT statement fails
     * @see #batchSave(Collection, int)
     */
    @Override
    default void batchSave(final Collection<? extends T> entities) throws IllegalStateException, UncheckedSQLException {
        batchSave(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch saves (inserts) multiple entities with a specified batch size.
     * The entities are inserted in batches of the specified size for optimal performance.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getLargeUserList();
     * userDao.batchSave(users, 1000);
     * }</pre>
     *
     * @param entities the collection of entities to insert
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @throws IllegalArgumentException if {@code batchSize} is not positive
     * @throws IllegalStateException if an existing transaction on the current thread is no longer active and cannot accept
     *         the internally required transaction scope
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing,
     *         binding, or executing an INSERT statement fails
     */
    @Override
    void batchSave(final Collection<? extends T> entities, final int batchSize) throws IllegalArgumentException, IllegalStateException, UncheckedSQLException;

    /**
     * Batch saves entities with only the specified properties using default batch size.
     * Only the listed properties will be included in the INSERT statements.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getUserList();
     * userDao.batchSave(users, Arrays.asList("firstName", "email"));
     * }</pre>
     *
     * @param entities the collection of entities to insert
     * @param propNamesToSave the property names to include in the INSERT (must not be {@code null} or empty)
     * @throws IllegalArgumentException if {@code propNamesToSave} is {@code null} or empty
     * @throws IllegalStateException if an existing transaction on the current thread is no longer active and cannot accept
     *         the internally required transaction scope
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing,
     *         binding, or executing an INSERT statement fails
     */
    @Override
    default void batchSave(final Collection<? extends T> entities, final Collection<String> propNamesToSave)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException {
        batchSave(entities, propNamesToSave, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch saves entities with only the specified properties and custom batch size.
     * Combines property selection with batch processing for optimal performance.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getLargeUserList();
     * userDao.batchSave(users, Arrays.asList("firstName", "email"), 500);
     * }</pre>
     *
     * @param entities the collection of entities to insert
     * @param propNamesToSave the property names to include (must not be {@code null} or empty)
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @throws IllegalArgumentException if {@code propNamesToSave} is {@code null} or empty, or if {@code batchSize} is not positive
     * @throws IllegalStateException if an existing transaction on the current thread is no longer active and cannot accept
     *         the internally required transaction scope
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing,
     *         binding, or executing an INSERT statement fails
     */
    @Override
    void batchSave(final Collection<? extends T> entities, final Collection<String> propNamesToSave, final int batchSize)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException;

    /**
     * Batch saves entities using a custom named INSERT SQL with default batch size.
     * The SQL should use named parameters matching entity properties.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "INSERT INTO users (first_name, last_name) VALUES (:firstName, :lastName)";
     * List<User> users = getUserList();
     * userDao.batchSave(sql, users);
     * }</pre>
     *
     * @param namedInsertSql the named INSERT SQL statement
     * @param entities the entities providing parameter values
     * @throws IllegalArgumentException if {@code namedInsertSql} is {@code null} or empty,
     *                                  or if {@code namedInsertSql} contains positional (unnamed) parameters,
     *                                  or if an element of {@code entities} has no property for a named parameter in
     *                                  {@code namedInsertSql} other than the reserved {@code now}, {@code sysTime} and {@code sysDate}
     * @throws IllegalStateException if an existing transaction on the current thread is no longer active and cannot accept
     *         the internally required transaction scope
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing,
     *         binding, or executing an INSERT statement fails
     */
    @Beta
    @Override
    default void batchSave(final String namedInsertSql, final Collection<? extends T> entities)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException {
        batchSave(namedInsertSql, entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch saves entities using a custom named INSERT SQL with specified batch size.
     * Provides maximum control over batch insert operations.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "INSERT INTO users (first_name, last_name) VALUES (:firstName, :lastName)";
     * List<User> users = getLargeUserList();
     * userDao.batchSave(sql, users, 1000);
     * }</pre>
     *
     * @param namedInsertSql the named INSERT SQL statement
     * @param entities the entities providing parameter values
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @throws IllegalArgumentException if {@code namedInsertSql} is {@code null} or empty, or if {@code batchSize} is not positive,
     *                                  or if {@code namedInsertSql} contains positional (unnamed) parameters,
     *                                  or if an element of {@code entities} has no property for a named parameter in
     *                                  {@code namedInsertSql} other than the reserved {@code now}, {@code sysTime} and {@code sysDate}
     * @throws IllegalStateException if an existing transaction on the current thread is no longer active and cannot accept
     *         the internally required transaction scope
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing,
     *         binding, or executing an INSERT statement fails
     */
    @Beta
    @Override
    void batchSave(final String namedInsertSql, final Collection<? extends T> entities, final int batchSize)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException;

}
