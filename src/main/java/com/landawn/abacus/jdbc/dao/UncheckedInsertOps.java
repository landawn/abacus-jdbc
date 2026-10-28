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
     * As with {@link #save(Object)}, the ID column is included for an entity only when its ID is set.
     * Does nothing if {@code entities} is {@code null} or empty.
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
     * @throws IllegalStateException if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *         holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *         own while no JPA transaction owning it can be identified (see
     *         {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing,
     *         binding, or executing an INSERT statement fails. With more than one element, the transaction is begun first and a Spring
     *         acquisition failure is not translated: it propagates as {@code CannotGetJdbcConnectionException} (see {@link UncheckedDao})
     * @see #batchSave(Collection, int)
     * @throws IllegalArgumentException if a batch row is {@code null} and the SQL has other than one parameter,
     *         or a batch entity lacks a property required by the named SQL parameters
     */
    @Override
    default void batchSave(final Collection<? extends T> entities) throws IllegalStateException, UncheckedSQLException, IllegalArgumentException {
        batchSave(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch saves (inserts) multiple entities with a specified batch size.
     * The entities are inserted in batches of the specified size for optimal performance.
     * As with {@link #save(Object)}, the ID column is included for an entity only when its ID is set.
     * Does nothing if {@code entities} is {@code null} or empty.
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
     *                     With more than one element, all of them run in one transaction, even if they fit in one batch.
     * @throws IllegalArgumentException if {@code batchSize} is not positive,
     *         if a batch row is {@code null} and the SQL has other than one parameter,
     *         or a batch entity lacks a property required by the named SQL parameters
     * @throws IllegalStateException if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *         holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *         own while no JPA transaction owning it can be identified (see
     *         {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing,
     *         binding, or executing an INSERT statement fails. With more than one element, the transaction is begun first and a Spring
     *         acquisition failure is not translated: it propagates as {@code CannotGetJdbcConnectionException} (see {@link UncheckedDao})
     */
    @Override
    void batchSave(final Collection<? extends T> entities, final int batchSize) throws IllegalArgumentException, IllegalStateException, UncheckedSQLException;

    /**
     * Batch saves entities with only the specified properties using default batch size.
     * Only the listed properties will be included in the INSERT statements.
     * Does nothing if {@code entities} is {@code null} or empty.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getUserList();
     * userDao.batchSave(users, Arrays.asList("firstName", "email"));
     * }</pre>
     *
     * @param entities the collection of entities to insert
     * @param propNamesToSave the property names to include in the INSERT (must not be {@code null} or empty)
     * @throws IllegalArgumentException if {@code propNamesToSave} is {@code null} or empty,
     *         if a batch row is {@code null} and the SQL has other than one parameter,
     *         or a batch entity lacks a property required by the named SQL parameters
     * @throws IllegalStateException if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *         holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *         own while no JPA transaction owning it can be identified (see
     *         {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing,
     *         binding, or executing an INSERT statement fails. With more than one element, the transaction is begun first and a Spring
     *         acquisition failure is not translated: it propagates as {@code CannotGetJdbcConnectionException} (see {@link UncheckedDao})
     */
    @Override
    default void batchSave(final Collection<? extends T> entities, final Collection<String> propNamesToSave)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException {
        batchSave(entities, propNamesToSave, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch saves entities with only the specified properties and custom batch size.
     * Combines property selection with batch processing for optimal performance.
     * Does nothing if {@code entities} is {@code null} or empty.
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
     *                     With more than one element, all of them run in one transaction, even if they fit in one batch.
     * @throws IllegalArgumentException if {@code propNamesToSave} is {@code null} or empty, or if {@code batchSize} is not positive,
     *         if a batch row is {@code null} and the SQL has other than one parameter,
     *         or a batch entity lacks a property required by the named SQL parameters
     * @throws IllegalStateException if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *         holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *         own while no JPA transaction owning it can be identified (see
     *         {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing,
     *         binding, or executing an INSERT statement fails. With more than one element, the transaction is begun first and a Spring
     *         acquisition failure is not translated: it propagates as {@code CannotGetJdbcConnectionException} (see {@link UncheckedDao})
     */
    @Override
    void batchSave(final Collection<? extends T> entities, final Collection<String> propNamesToSave, final int batchSize)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException;

    /**
     * Batch saves entities using a custom named INSERT SQL with default batch size.
     * The SQL should use named parameters matching entity properties.
     * Does nothing if {@code entities} is {@code null} or empty.
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
     *                                  {@code namedInsertSql} other than the reserved {@code now}, {@code sysTime} and {@code sysDate},
     *         if a batch row is {@code null} and the SQL has other than one parameter,
     *         or a batch entity lacks a property required by the named SQL parameters
     * @throws IllegalStateException if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *         holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *         own while no JPA transaction owning it can be identified (see
     *         {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing,
     *         binding, or executing an INSERT statement fails. With more than one element, the transaction is begun first and a Spring
     *         acquisition failure is not translated: it propagates as {@code CannotGetJdbcConnectionException} (see {@link UncheckedDao})
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
     * Does nothing if {@code entities} is {@code null} or empty.
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
     *                     With more than one element, all of them run in one transaction, even if they fit in one batch.
     * @throws IllegalArgumentException if {@code namedInsertSql} is {@code null} or empty, or if {@code batchSize} is not positive,
     *                                  or if {@code namedInsertSql} contains positional (unnamed) parameters,
     *                                  or if an element of {@code entities} has no property for a named parameter in
     *                                  {@code namedInsertSql} other than the reserved {@code now}, {@code sysTime} and {@code sysDate},
     *         if a batch row is {@code null} and the SQL has other than one parameter,
     *         or a batch entity lacks a property required by the named SQL parameters
     * @throws IllegalStateException if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *         holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *         own while no JPA transaction owning it can be identified (see
     *         {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing,
     *         binding, or executing an INSERT statement fails. With more than one element, the transaction is begun first and a Spring
     *         acquisition failure is not translated: it propagates as {@code CannotGetJdbcConnectionException} (see {@link UncheckedDao})
     */
    @Beta
    @Override
    void batchSave(final String namedInsertSql, final Collection<? extends T> entities, final int batchSize)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException;

}
