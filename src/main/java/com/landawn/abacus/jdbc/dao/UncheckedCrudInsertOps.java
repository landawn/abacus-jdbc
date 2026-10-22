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
import java.util.List;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;

/**
 * Unchecked-exception insert capability of {@link UncheckedCrudDao}: the {@link CrudInsertOps}
 * operations re-declared to throw {@link UncheckedSQLException}.
 *
 * <p><b>Batches, transactions and ID restore:</b> the semantics are those of {@link CrudInsertOps} (see its <i>Batches and
 * transactions</i> and <i>Rollback of an enclosing transaction</i> paragraphs): a {@code batchInsert} of more than one
 * entity runs in one transaction, which participates in an active Spring-managed transaction owning the data source's
 * connection, and the ID values the entities had before an insert are put back if a transaction enclosing the insert
 * rolls back, or if the own transaction of a multi-row {@code batchInsert} does not commit (a participating rollback inside
 * a savepoint scope puts back right away only the IDs of the inserts since the newest open savepoint, and leaves the
 * earlier ones to the enclosing transaction's outcome).</p>
 *
 * @param <T> entity type
 * @param <ID> id type
 * @param <TD> self DAO type
 * @see CrudInsertOps
 * @see UncheckedCrudDao
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
@Beta
sealed interface UncheckedCrudInsertOps<T, ID, TD extends UncheckedDaoBase<T, TD>> extends CrudInsertOps<T, ID, TD>, UncheckedInsertOps<T, TD>
        permits UncheckedCrudDao, UncheckedNonUpdateCrudDao {
    /**
     * Generates a new ID for entity insertion.
     *
     * <p>Override this method for client-side strategies such as UUIDs or sequences. The default
     * implementation throws because ID generation is normally handled by the database.</p>
     *
     * @return the generated ID
     * @throws UnsupportedOperationException if client-side ID generation is not supported
     * @throws UncheckedSQLException if an overriding implementation fails while accessing the database to generate an ID
     * @deprecated ID generation should typically be handled by the database. Override this method
     *             only when a client-side ID generation strategy is required.
     */
    @Deprecated
    @NonDBOperation
    @Override
    default ID generateId() throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException("ID generation is not supported by default");
    }

    /**
     * Inserts the specified entity into the database and returns its ID.
     * All insertable properties of the entity are included in the INSERT statement; the ID property is included only when
     * it has been set (i.e., is not the default value), allowing the database to generate it otherwise.
     *
     * <p>If the database generates the ID (for example via an auto-increment column), the generated
     * ID is retrieved, written back to the entity's ID property (where applicable) and returned. If the
     * database does not generate a key, the entity's existing ID value is returned instead.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User("John", "Doe");
     * user.setEmail("john@example.com");
     * Long id = userDao.insert(user);
     * System.out.println("Created user with ID: " + id);
     * }</pre>
     *
     * @param entity the entity to insert (must not be {@code null})
     * @return the ID of the inserted entity (either database-generated or entity-provided)
     * @throws IllegalArgumentException if {@code entity} is {@code null}
     * @throws UnsupportedOperationException if {@link com.landawn.abacus.jdbc.annotation.DaoConfig#callGenerateIdForInsertIfIdNotSet()} is enabled
     *         and the entity's ID is not set, but {@link #generateId()} has not been overridden
     * @throws IllegalStateException if the ownership of a Spring connection holder bound to the data source cannot be determined (the
     *         insert registers the put-back of the entity's ID with a transaction enclosing it)
     * @throws UncheckedSQLException if acquiring a connection fails, if the auto-commit mode of the connection a Spring-managed transaction bound to
     *         the data source cannot be read, or preparing, binding, or executing an INSERT statement, or reading its generated keys fails
     */
    @Override
    ID insert(final T entity) throws IllegalArgumentException, UnsupportedOperationException, IllegalStateException, UncheckedSQLException;

    /**
     * Inserts the specified entity with only the specified properties.
     * This is useful when you want to insert an entity with only certain fields populated.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setFirstName("John");
     * user.setLastName("Doe");
     * user.setEmail("john@example.com");
     * // Only insert firstName and email, skip lastName
     * Long id = userDao.insert(user, Arrays.asList("firstName", "email"));
     * }</pre>
     *
     * @param entity the entity to insert (must not be {@code null})
     * @param propNamesToInsert the property names to include in the INSERT statement (must not be {@code null} or empty)
     * @return the ID of the inserted entity (either database-generated or entity-provided)
     * @throws IllegalArgumentException if {@code entity} is {@code null}, or if {@code propNamesToInsert} is {@code null} or empty
     * @throws UnsupportedOperationException if {@link com.landawn.abacus.jdbc.annotation.DaoConfig#callGenerateIdForInsertIfIdNotSet()} is enabled,
     *         {@code propNamesToInsert} includes an ID property and the entity's ID is not set, but {@link #generateId()} has not been overridden
     * @throws IllegalStateException if the ownership of a Spring connection holder bound to the data source cannot be determined (the
     *         insert registers the put-back of the entity's ID with a transaction enclosing it)
     * @throws UncheckedSQLException if acquiring a connection fails, if the auto-commit mode of the connection a Spring-managed transaction bound to
     *         the data source cannot be read, or preparing, binding, or executing an INSERT statement, or reading its generated keys fails
     */
    @Override
    ID insert(final T entity, final Collection<String> propNamesToInsert)
            throws IllegalArgumentException, UnsupportedOperationException, IllegalStateException, UncheckedSQLException;

    /**
     * Inserts an entity using a custom named SQL insert statement.
     * The SQL should use named parameters that match the entity's property names.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "INSERT INTO users (first_name, last_name, created_date) " +
     *              "VALUES (:firstName, :lastName, CURRENT_TIMESTAMP)";
     * User user = new User("John", "Doe");
     * Long id = userDao.insert(sql, user);
     * }</pre>
     *
     * @param namedInsertSql the named parameter SQL insert statement
     * @param entity the entity whose properties will be bound to the named parameters
     * @return the ID of the inserted entity (either database-generated or entity-provided)
     * @throws IllegalArgumentException if {@code namedInsertSql} is {@code null} or empty, or if {@code entity} is {@code null},
     *                                  or if {@code namedInsertSql} contains positional (unnamed) parameters,
     *                                  or if {@code entity} has no property for a named parameter in {@code namedInsertSql}
     *                                  other than the reserved {@code now}, {@code sysTime} and {@code sysDate}
     * @throws UnsupportedOperationException if {@link com.landawn.abacus.jdbc.annotation.DaoConfig#callGenerateIdForInsertWithSqlIfIdNotSet()} is
     *         enabled and the entity's ID is not set, but {@link #generateId()} has not been overridden
     * @throws IllegalStateException if the ownership of a Spring connection holder bound to the data source cannot be determined (the
     *         insert registers the put-back of the entity's ID with a transaction enclosing it)
     * @throws UncheckedSQLException if acquiring a connection fails, if the auto-commit mode of the connection a Spring-managed transaction bound to
     *         the data source cannot be read, or preparing, binding, or executing an INSERT statement, or reading its generated keys fails
     */
    @Override
    ID insert(final String namedInsertSql, final T entity)
            throws IllegalArgumentException, UnsupportedOperationException, IllegalStateException, UncheckedSQLException;

    /**
     * Performs batch insert of multiple entities using the default batch size
     * ({@link JdbcUtil#DEFAULT_BATCH_SIZE}).
     * This method is more efficient than inserting entities one by one.
     * As with {@link #insert(Object)}, the ID column is included for an entity only when its ID is set.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = Arrays.asList(
     *     new User("John", "Doe"),
     *     new User("Jane", "Smith"),
     *     new User("Bob", "Johnson")
     * );
     * List<Long> ids = userDao.batchInsert(users);
     * System.out.println("Created " + ids.size() + " users");
     * }</pre>
     *
     * @param entities the collection of entities to insert
     * @return the IDs of the inserted entities, in the same order as the input entities (database-generated keys, which are
     *         also written back to the entities, or the IDs the entities already hold); an empty list if {@code entities} is
     *         {@code null} or empty. If the driver returns generated keys (not all {@code null}/default) whose count differs
     *         from the number of entities, a warning is logged, no key is written back and the entities' current IDs are returned,
     *         in input order. For a composite ID, the entities' full IDs are returned after the generated keys are written back
     *         (a driver may return only the database-generated column)
     * @throws IllegalArgumentException if an element of {@code entities} is {@code null},
     *         or a batch entity lacks a property required by the named SQL parameters
     * @throws UnsupportedOperationException if {@link com.landawn.abacus.jdbc.annotation.DaoConfig#callGenerateIdForInsertIfIdNotSet()} is enabled
     *         and an entity's ID is not set, but {@link #generateId()} has not been overridden
     * @throws IllegalStateException if an existing transaction on the current thread is no longer active and cannot accept
     *         the internally required transaction scope,
     *         or if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *         holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *         own while no JPA transaction owning it can be identified (see
     *         {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, if the auto-commit
     *         mode of the connection a Spring-managed transaction bound to the data source cannot be read, or preparing, binding, or executing an
     *         INSERT statement, or reading its generated keys fails
     */
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities)
            throws IllegalArgumentException, UnsupportedOperationException, IllegalStateException, UncheckedSQLException {
        return batchInsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch insert of multiple entities with a specified batch size.
     * Large collections will be processed in batches of the specified size.
     * As with {@link #insert(Object)}, the ID column is included for an entity only when its ID is set.
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
     *                     With more than one element, all of them run in one transaction, even if they fit in one batch.
     * @return the IDs of the inserted entities, in the same order as the input entities (database-generated keys, which are
     *         also written back to the entities, or the IDs the entities already hold); an empty list if {@code entities} is
     *         {@code null} or empty. If the driver returns generated keys (not all {@code null}/default) whose count differs
     *         from the number of entities, a warning is logged, no key is written back and the entities' current IDs are returned,
     *         in input order. For a composite ID, the entities' full IDs are returned after the generated keys are written back
     *         (a driver may return only the database-generated column)
     * @throws IllegalArgumentException if {@code batchSize} is not positive,
     *         if an element of {@code entities} is {@code null},
     *         or a batch entity lacks a property required by the named SQL parameters
     * @throws UnsupportedOperationException if {@link com.landawn.abacus.jdbc.annotation.DaoConfig#callGenerateIdForInsertIfIdNotSet()} is enabled
     *         and an entity's ID is not set, but {@link #generateId()} has not been overridden
     * @throws IllegalStateException if an existing transaction on the current thread is no longer active and cannot accept
     *         the internally required transaction scope,
     *         or if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *         holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *         own while no JPA transaction owning it can be identified (see
     *         {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, if the auto-commit
     *         mode of the connection a Spring-managed transaction bound to the data source cannot be read, or preparing, binding, or executing an
     *         INSERT statement, or reading its generated keys fails
     */
    @Override
    List<ID> batchInsert(final Collection<? extends T> entities, final int batchSize)
            throws IllegalArgumentException, UnsupportedOperationException, IllegalStateException, UncheckedSQLException;

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
     * @return the IDs of the inserted entities, in the same order as the input entities (database-generated keys, which are
     *         also written back to the entities, or the IDs the entities already hold); an empty list if {@code entities} is
     *         {@code null} or empty. If the driver returns generated keys (not all {@code null}/default) whose count differs
     *         from the number of entities, a warning is logged, no key is written back and the entities' current IDs are returned,
     *         in input order. For a composite ID, the entities' full IDs are returned after the generated keys are written back
     *         (a driver may return only the database-generated column)
     * @throws IllegalArgumentException if {@code propNamesToInsert} is {@code null} or empty,
     *         if an element of {@code entities} is {@code null},
     *         or a batch entity lacks a property required by the named SQL parameters
     * @throws UnsupportedOperationException if {@link com.landawn.abacus.jdbc.annotation.DaoConfig#callGenerateIdForInsertIfIdNotSet()} is enabled,
     *         {@code propNamesToInsert} includes an ID property and an entity's ID is not set, but {@link #generateId()} has not been overridden
     * @throws IllegalStateException if an existing transaction on the current thread is no longer active and cannot accept
     *         the internally required transaction scope,
     *         or if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *         holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *         own while no JPA transaction owning it can be identified (see
     *         {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, if the auto-commit
     *         mode of the connection a Spring-managed transaction bound to the data source cannot be read, or preparing, binding, or executing an
     *         INSERT statement, or reading its generated keys fails
     */
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert)
            throws IllegalArgumentException, UnsupportedOperationException, IllegalStateException, UncheckedSQLException {
        return batchInsert(entities, propNamesToInsert, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch insert with only specified properties and custom batch size.
     * This provides fine-grained control over both what fields are inserted and how the batch is processed.
     *
     * @param entities the collection of entities to insert
     * @param propNamesToInsert the property names to include in the INSERT statement (must not be {@code null} or empty)
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     *                     With more than one element, all of them run in one transaction, even if they fit in one batch.
     * @return the IDs of the inserted entities, in the same order as the input entities (database-generated keys, which are
     *         also written back to the entities, or the IDs the entities already hold); an empty list if {@code entities} is
     *         {@code null} or empty. If the driver returns generated keys (not all {@code null}/default) whose count differs
     *         from the number of entities, a warning is logged, no key is written back and the entities' current IDs are returned,
     *         in input order. For a composite ID, the entities' full IDs are returned after the generated keys are written back
     *         (a driver may return only the database-generated column)
     * @throws IllegalArgumentException if {@code propNamesToInsert} is {@code null} or empty, or if {@code batchSize} is not positive,
     *         if an element of {@code entities} is {@code null},
     *         or a batch entity lacks a property required by the named SQL parameters
     * @throws UnsupportedOperationException if {@link com.landawn.abacus.jdbc.annotation.DaoConfig#callGenerateIdForInsertIfIdNotSet()} is enabled,
     *         {@code propNamesToInsert} includes an ID property and an entity's ID is not set, but {@link #generateId()} has not been overridden
     * @throws IllegalStateException if an existing transaction on the current thread is no longer active and cannot accept
     *         the internally required transaction scope,
     *         or if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *         holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *         own while no JPA transaction owning it can be identified (see
     *         {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, if the auto-commit
     *         mode of the connection a Spring-managed transaction bound to the data source cannot be read, or preparing, binding, or executing an
     *         INSERT statement, or reading its generated keys fails
     */
    @Override
    List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert, final int batchSize)
            throws IllegalArgumentException, UnsupportedOperationException, IllegalStateException, UncheckedSQLException;

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
     * @return the IDs of the inserted entities, in the same order as the input entities (database-generated keys, which are
     *         also written back to the entities, or the IDs the entities already hold); an empty list if {@code entities} is
     *         {@code null} or empty. If the driver returns generated keys (not all {@code null}/default) whose count differs
     *         from the number of entities, a warning is logged, no key is written back and the entities' current IDs are returned,
     *         in input order. For a composite ID, the entities' full IDs are returned after the generated keys are written back
     *         (a driver may return only the database-generated column)
     * @throws IllegalArgumentException if {@code namedInsertSql} is {@code null} or empty,
     *                                  or if {@code namedInsertSql} contains positional (unnamed) parameters,
     *                                  or if an element of {@code entities} has no property for a named parameter in
     *                                  {@code namedInsertSql} other than the reserved {@code now}, {@code sysTime} and {@code sysDate},
     *         if an element of {@code entities} is {@code null},
     *         or a batch entity lacks a property required by the named SQL parameters
     * @throws UnsupportedOperationException if {@link com.landawn.abacus.jdbc.annotation.DaoConfig#callGenerateIdForInsertWithSqlIfIdNotSet()} is
     *         enabled and an entity's ID is not set, but {@link #generateId()} has not been overridden
     * @throws IllegalStateException if an existing transaction on the current thread is no longer active and cannot accept
     *         the internally required transaction scope,
     *         or if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *         holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *         own while no JPA transaction owning it can be identified (see
     *         {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, if the auto-commit
     *         mode of the connection a Spring-managed transaction bound to the data source cannot be read, or preparing, binding, or executing an
     *         INSERT statement, or reading its generated keys fails
     */
    @Beta
    @Override
    default List<ID> batchInsert(final String namedInsertSql, final Collection<? extends T> entities)
            throws IllegalArgumentException, UnsupportedOperationException, IllegalStateException, UncheckedSQLException {
        return batchInsert(namedInsertSql, entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch insert using a custom named SQL statement with specified batch size.
     * Combines custom SQL flexibility with batch processing efficiency.
     *
     * @param namedInsertSql the named parameter SQL insert statement
     * @param entities the collection of entities whose properties will be bound to the named parameters
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     *                     With more than one element, all of them run in one transaction, even if they fit in one batch.
     * @return the IDs of the inserted entities, in the same order as the input entities (database-generated keys, which are
     *         also written back to the entities, or the IDs the entities already hold); an empty list if {@code entities} is
     *         {@code null} or empty. If the driver returns generated keys (not all {@code null}/default) whose count differs
     *         from the number of entities, a warning is logged, no key is written back and the entities' current IDs are returned,
     *         in input order. For a composite ID, the entities' full IDs are returned after the generated keys are written back
     *         (a driver may return only the database-generated column)
     * @throws IllegalArgumentException if {@code namedInsertSql} is {@code null} or empty, or if {@code batchSize} is not positive,
     *                                  or if {@code namedInsertSql} contains positional (unnamed) parameters,
     *                                  or if an element of {@code entities} has no property for a named parameter in
     *                                  {@code namedInsertSql} other than the reserved {@code now}, {@code sysTime} and {@code sysDate},
     *         if an element of {@code entities} is {@code null},
     *         or a batch entity lacks a property required by the named SQL parameters
     * @throws UnsupportedOperationException if {@link com.landawn.abacus.jdbc.annotation.DaoConfig#callGenerateIdForInsertWithSqlIfIdNotSet()} is
     *         enabled and an entity's ID is not set, but {@link #generateId()} has not been overridden
     * @throws IllegalStateException if an existing transaction on the current thread is no longer active and cannot accept
     *         the internally required transaction scope,
     *         or if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *         holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *         own while no JPA transaction owning it can be identified (see
     *         {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, if the auto-commit
     *         mode of the connection a Spring-managed transaction bound to the data source cannot be read, or preparing, binding, or executing an
     *         INSERT statement, or reading its generated keys fails
     */
    @Beta
    @Override
    List<ID> batchInsert(final String namedInsertSql, final Collection<? extends T> entities, final int batchSize)
            throws IllegalArgumentException, UnsupportedOperationException, IllegalStateException, UncheckedSQLException;

}
