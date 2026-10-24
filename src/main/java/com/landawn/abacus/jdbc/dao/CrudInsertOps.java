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
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.jdbc.JdbcUtil;

/**
 * Insert capability of {@link CrudDao}: generated-ID extraction and generation, plus
 * {@code insert}/{@code batchInsert} returning generated ids.
 * Extends {@link InsertOps}.
 *
 * <p><b>{@code insert} vs {@code save}:</b> {@code insert}/{@code batchInsert} return the inserted IDs
 * and populate database-generated IDs on the entities. The inherited {@link InsertOps#save save}/{@code batchSave}
 * methods return {@code void}; they do not retrieve database-generated IDs or write them back to the entities.
 * Use {@code insert} when subsequent operations need the generated ID.</p>
 *
 * <p><b>Composite IDs:</b> some drivers (e.g. MySQL, SQL Server) return only the auto-generated column of a composite
 * key, under a label such as {@code GENERATED_KEY} that names no property. That value is assigned only to the ID
 * property declared as database-generated ({@code @ReadOnlyId}, {@code @Id} with {@code @ReadOnly}, or JPA
 * {@code @GeneratedValue}); without exactly one such declaration it is not assigned (a warning is logged). Register
 * a custom extractor with {@code JdbcUtil.setIdExtractorForDao} for other layouts.</p>
 *
 * <p><b>Batches and transactions:</b> a {@code batchInsert} of more than one entity runs in one transaction (even if the
 * entities fit in one batch) and writes the generated IDs back to the entities after its transaction scope commits; a
 * single entity runs without one. Inside an active Spring-managed transaction that owns the data source's connection,
 * that transaction participates in Spring's (see {@code JdbcUtil.beginTransaction}): Spring commits the work, and a
 * failure marks Spring's transaction rollback-only.</p>
 *
 * <p><b>Rollback of an enclosing transaction:</b> when an insert runs inside an enclosing transaction - a
 * {@code JdbcUtil} transaction it joins, or a Spring-managed transaction it participates in - the ID values the entities
 * had before the insert are put back if that transaction is rolled back, so that the entities do not keep the IDs of rows
 * that no longer exist. Outside any transaction, a database-generated key written back after the statement executed is
 * kept; an ID generated in the application ({@code DaoConfig.callGenerateIdForInsertIfIdNotSet()}) is put back only if
 * the statement never executed, or if the own transaction of a multi-row {@code batchInsert} does not commit (it could not
 * begin, or it is rolled back after a failure: no row remains then). A Spring-managed transaction's rollback is observed
 * through Spring's transaction synchronization, which is enabled by default: with a transaction manager that runs without
 * it ({@code SYNCHRONIZATION_NEVER}), the IDs of a successful insert are kept if Spring rolls back afterwards. And Spring
 * exposes the synchronization of the innermost Spring-managed transaction on the thread only: an insert on the data source
 * of an enclosing transaction while a transaction of another transaction manager runs inside it has its IDs follow the
 * inner transaction's outcome. If the inner transaction rolls back while the enclosing one commits, the IDs are put back
 * although the rows exist, so inserting such an entity again would duplicate its row; if the inner one commits while the
 * enclosing one rolls back, the IDs are kept although the rows are gone. A rollback of the insert's own {@code JdbcUtil}
 * transaction marks the enclosing transaction rollback-only and puts the IDs back correctly: right away for the inserts of
 * work that can only roll back, at completion for the rest. Inside a savepoint scope ({@code PROPAGATION_NESTED}) that is
 * the IDs of the inserts since the newest open savepoint, whose scope the mark dooms; the IDs of the enclosing
 * transaction's earlier inserts follow its outcome, since Spring's rollback to the savepoint clears the mark (with
 * {@code DataSourceTransactionManager} or {@code JdbcTransactionManager}; a {@code JpaTransactionManager}'s JPA transaction
 * stays marked) and the enclosing transaction may still commit their rows (Spring reports no savepoint release, so the mark of a nested scope
 * that already committed lingers until its savepoint object is garbage collected, which meanwhile only defers the restore
 * of the IDs registered before it to the transaction's completion). A rollback to a savepoint
 * ({@code PROPAGATION_NESTED}, or a programmatic savepoint) puts back the IDs of the inserts since that savepoint and leaves
 * the earlier ones to the enclosing transaction's outcome; this needs Spring 6.2 or later, which reports savepoints to its
 * transaction synchronizations (with an older Spring, the IDs of an insert in a rolled-back nested transaction are kept
 * although its rows are gone, and those a participating rollback would put back right away follow the transaction's outcome
 * instead). Spring reports a rollback to a savepoint before performing it and does not report its
 * failure: if rolling back to the savepoint fails and the application catches that and commits the transaction, the IDs
 * since the savepoint have been put back although their rows are committed.</p>
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
     * <p>The default implementation returns {@code null}, which selects the default extractor (the one registered with
     * {@link JdbcUtil#setIdExtractorForDao(Class, Jdbc.BiRowMapper)}, if any, otherwise the built-in one). A non-null
     * extractor returned here takes precedence over a registered one. This method is called the first time an insert
     * needs an extractor, and its result is then reused for the life of the DAO instance.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // In the DAO interface, e.g. interface UserDao extends CrudDao<User, Long, UserDao>
     * @Override
     * default Jdbc.BiRowMapper<Long> idExtractor() {
     *     return (rs, columnLabels) -> rs.getLong(1);
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
     * @throws UnsupportedOperationException if client-side ID generation is not supported
     * @throws SQLException if an overriding implementation fails while accessing the database to generate an ID
     * @deprecated ID generation should typically be handled by the database. Override this method
     *             only when a client-side ID generation strategy is required.
     */
    @Deprecated
    @NonDBOperation
    default ID generateId() throws UnsupportedOperationException, SQLException {
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
     * User user = new User("John", "Doe", "john@example.com");
     * Long userId = userDao.insert(user);
     * System.out.println("Created user with ID: " + userId);
     * }</pre>
     *
     * @param entity the entity to insert (must not be {@code null})
     * @return the ID of the inserted entity (either database-generated or entity-provided)
     * @throws IllegalArgumentException if {@code entity} is {@code null}
     * @throws UnsupportedOperationException if {@link com.landawn.abacus.jdbc.annotation.DaoConfig#callGenerateIdForInsertIfIdNotSet()} is enabled
     *         and the entity's ID is not set, but {@link #generateId()} has not been overridden
     * @throws IllegalStateException if the ownership of a Spring connection holder bound to the data source cannot be determined (the
     *         insert registers the put-back of the entity's ID with a transaction enclosing it)
     * @throws UncheckedSQLException if acquiring a required database connection fails, or if the auto-commit mode of the connection a
     *         Spring-managed transaction bound to the data source cannot be read
     * @throws SQLException if preparing, binding, or executing an INSERT statement, or reading its generated keys fails
     */
    ID insert(final T entity) throws IllegalArgumentException, UnsupportedOperationException, IllegalStateException, UncheckedSQLException, SQLException;

    /**
     * Inserts the specified entity with only the specified properties.
     * This is useful when you want to insert an entity with only certain fields populated.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setEmail("john@example.com");
     * user.setCreatedDate(new java.util.Date());
     * Long userId = userDao.insert(user, Arrays.asList("email", "createdDate"));
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
     * @throws UncheckedSQLException if acquiring a required database connection fails, or if the auto-commit mode of the connection a
     *         Spring-managed transaction bound to the data source cannot be read
     * @throws SQLException if preparing, binding, or executing an INSERT statement, or reading its generated keys fails
     */
    ID insert(final T entity, final Collection<String> propNamesToInsert)
            throws IllegalArgumentException, UnsupportedOperationException, IllegalStateException, UncheckedSQLException, SQLException;

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
     * @throws IllegalArgumentException if {@code namedInsertSql} is {@code null} or empty, or if {@code entity} is {@code null},
     *                                  or if {@code namedInsertSql} contains positional (unnamed) parameters,
     *                                  or if {@code entity} has no property for a named parameter in {@code namedInsertSql}
     *                                  other than the reserved {@code now}, {@code sysTime} and {@code sysDate}
     * @throws UnsupportedOperationException if {@link com.landawn.abacus.jdbc.annotation.DaoConfig#callGenerateIdForInsertWithSqlIfIdNotSet()} is
     *         enabled and the entity's ID is not set, but {@link #generateId()} has not been overridden
     * @throws IllegalStateException if the ownership of a Spring connection holder bound to the data source cannot be determined (the
     *         insert registers the put-back of the entity's ID with a transaction enclosing it)
     * @throws UncheckedSQLException if acquiring a required database connection fails, or if the auto-commit mode of the connection a
     *         Spring-managed transaction bound to the data source cannot be read
     * @throws SQLException if preparing, binding, or executing an INSERT statement, or reading its generated keys fails
     */
    ID insert(final String namedInsertSql, final T entity)
            throws IllegalArgumentException, UnsupportedOperationException, IllegalStateException, UncheckedSQLException, SQLException;

    /**
     * Performs batch insert of multiple entities using the default batch size
     * ({@link JdbcUtil#DEFAULT_BATCH_SIZE}).
     * This method is more efficient than inserting entities one by one.
     * As with {@link #insert(Object)}, the ID column is included for an entity only when its ID is set.
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
     * @throws IllegalStateException if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *         holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *         own while no JPA transaction owning it can be identified (see
     *         {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UncheckedSQLException if acquiring a required database connection fails, starting or completing an internally required transaction
     *         fails, or if the auto-commit mode of the connection a Spring-managed transaction bound to the data source cannot be read
     * @throws SQLException if preparing, binding, or executing an INSERT statement, or reading its generated keys fails
     */
    default List<ID> batchInsert(final Collection<? extends T> entities)
            throws IllegalArgumentException, UnsupportedOperationException, IllegalStateException, UncheckedSQLException, SQLException {
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
     * @throws IllegalStateException if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *         holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *         own while no JPA transaction owning it can be identified (see
     *         {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UncheckedSQLException if acquiring a required database connection fails, starting or completing an internally required transaction
     *         fails, or if the auto-commit mode of the connection a Spring-managed transaction bound to the data source cannot be read
     * @throws SQLException if preparing, binding, or executing an INSERT statement, or reading its generated keys fails
     */
    List<ID> batchInsert(final Collection<? extends T> entities, final int batchSize)
            throws IllegalArgumentException, UnsupportedOperationException, IllegalStateException, UncheckedSQLException, SQLException;

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
     * @throws IllegalStateException if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *         holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *         own while no JPA transaction owning it can be identified (see
     *         {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UncheckedSQLException if acquiring a required database connection fails, starting or completing an internally required transaction
     *         fails, or if the auto-commit mode of the connection a Spring-managed transaction bound to the data source cannot be read
     * @throws SQLException if preparing, binding, or executing an INSERT statement, or reading its generated keys fails
     */
    default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert)
            throws IllegalArgumentException, UnsupportedOperationException, IllegalStateException, UncheckedSQLException, SQLException {
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
     * @throws IllegalStateException if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *         holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *         own while no JPA transaction owning it can be identified (see
     *         {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UncheckedSQLException if acquiring a required database connection fails, starting or completing an internally required transaction
     *         fails, or if the auto-commit mode of the connection a Spring-managed transaction bound to the data source cannot be read
     * @throws SQLException if preparing, binding, or executing an INSERT statement, or reading its generated keys fails
     */
    List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert, final int batchSize)
            throws IllegalArgumentException, UnsupportedOperationException, IllegalStateException, UncheckedSQLException, SQLException;

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
     * @throws IllegalStateException if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *         holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *         own while no JPA transaction owning it can be identified (see
     *         {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UncheckedSQLException if acquiring a required database connection fails, starting or completing an internally required transaction
     *         fails, or if the auto-commit mode of the connection a Spring-managed transaction bound to the data source cannot be read
     * @throws SQLException if preparing, binding, or executing an INSERT statement, or reading its generated keys fails
     */
    @Beta
    default List<ID> batchInsert(final String namedInsertSql, final Collection<? extends T> entities)
            throws IllegalArgumentException, UnsupportedOperationException, IllegalStateException, UncheckedSQLException, SQLException {
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
     * @throws IllegalStateException if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *         holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *         own while no JPA transaction owning it can be identified (see
     *         {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UncheckedSQLException if acquiring a required database connection fails, starting or completing an internally required transaction
     *         fails, or if the auto-commit mode of the connection a Spring-managed transaction bound to the data source cannot be read
     * @throws SQLException if preparing, binding, or executing an INSERT statement, or reading its generated keys fails
     */
    @Beta
    List<ID> batchInsert(final String namedInsertSql, final Collection<? extends T> entities, final int batchSize)
            throws IllegalArgumentException, UnsupportedOperationException, IllegalStateException, UncheckedSQLException, SQLException;

}
