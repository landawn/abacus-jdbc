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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.DuplicateResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.IsolationLevel;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.cs;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.query.QueryUtil;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.N;

/**
 * Provides comprehensive CRUD (Create, Read, Update, Delete) operations with unchecked exception
 * handling. It is the unchecked counterpart of {@link CrudDao}: it extends
 * {@link UncheckedDao} (the unchecked base DAO) and {@link CrudDao}, and re-declares the id-based
 * operations so that they throw the unchecked {@link UncheckedSQLException} instead of the checked
 * {@link SQLException}.
 *
 * <p>Because every database operation declared here throws {@link UncheckedSQLException} (a
 * {@link RuntimeException}) rather than a checked exception, this interface is easier to use in
 * functional programming contexts (lambdas, streams) and reduces boilerplate exception handling.</p>
 *
 * <p><b>ID semantics:</b> the entity class must declare one or more {@code @Id} properties. A single id
 * property maps directly to the {@code <ID>} type (for example {@code Long} or {@code String}), whereas a
 * composite (multi-column) key can use an {@link EntityId} or a bean/record with matching ID properties. Insert operations write a
 * database-generated key back into the entity's id property where applicable, and {@code by-id} lookups
 * treat the supplied id as a primary-key match: every declared id property is matched, so an {@code EntityId} that lacks
 * one of them, or holds {@code null} for one, matches no row (keys it carries beyond the id properties are ignored).</p>
 *
 * <p><b>{@code null} key values:</b> the by-id methods and the upsert methods treat a {@code null} key
 * value differently, because they ask different questions.</p>
 * <ul>
 *   <li>The by-id methods ({@code get}, {@code exists}, {@code deleteById}, {@code batchGet}, {@code count(ids)},
 *       {@code refresh}) look a row up by its identity, binding each id value into {@code key = ?}. A {@code NULL}
 *       never equals anything in SQL, so an id with a {@code null} component matches no row; {@code batchGet} and
 *       {@code count(ids)} skip such an id instead of querying for it, so they always agree with {@code get}.</li>
 *   <li>The upsert methods ({@code upsert(entity)}, {@code upsert(entity, matchPropNames)}, {@code batchUpsert})
 *       ask whether a row with the entity's values already exists, so a {@code null} value is matched with
 *       {@code IS NULL}. This keeps an upsert repeatable: if a {@code null} matched nothing, every upsert of
 *       such an entity would insert again, and since most databases allow several {@code NULL}s in a unique
 *       column, nothing would stop the duplicate rows. It also lets a match property such as a nullable
 *       {@code tenantId} use {@code NULL} as a real value.</li>
 * </ul>
 * <p>For {@code NOT NULL} key columns, such as primary-key components, the two rules behave the same: no row
 * matches. They differ only for nullable key columns, where a row whose key holds {@code NULL} can be found
 * and updated by an upsert but cannot be addressed by the by-id methods.</p>
 *
 * <p><b>Connection acquisition failures:</b> as described for {@link UncheckedDao}, a Spring
 * connection-acquisition failure caused by an {@link SQLException} is translated into {@link UncheckedSQLException}
 * unless the operation opens a transaction first. The {@code insert} and {@code batchInsert} operations, and the
 * {@code upsert} and {@code batchUpsert} operations when they insert, don't translate it either.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends UncheckedCrudDao<User, Long, UserDao> {
 *     // Custom query methods can be added here
 * }
 *
 * UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource);
 * User user = new User("John", "Doe");
 * Long id = userDao.insert(user);
 *
 * com.landawn.abacus.util.u.Optional<User> found = userDao.get(id);
 * userDao.update("email", "john@example.com", id);
 * userDao.deleteById(id);
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <ID> the ID type of the entity (e.g. {@code Long}, {@code String}, {@code EntityId})
 * @param <TD> the concrete DAO type itself (self-referencing generic for fluent method chaining)
 * @see JdbcUtil#prepareQuery(javax.sql.DataSource, String)
 * @see JdbcUtil#prepareNamedQuery(javax.sql.DataSource, String)
 * @see JdbcUtil#beginTransaction(javax.sql.DataSource, IsolationLevel)
 * @see UncheckedDao
 * @see CrudDao
 * @see com.landawn.abacus.query.Filters
 */
@SuppressWarnings("resource")
@Beta
public non-sealed interface UncheckedCrudDao<T, ID, TD extends UncheckedCrudDao<T, ID, TD>> extends UncheckedCrudReadOps<T, ID, TD>,
        UncheckedCrudInsertOps<T, ID, TD>, UncheckedCrudUpdateOps<T, ID, TD>, UncheckedCrudDeleteOps<T, ID, TD>, UncheckedDao<T, TD>, CrudDao<T, ID, TD> {
    /**
     * Performs an upsert operation, matching existing records by the entity's ID property(ies):
     * inserts {@code entity} if no record with the same ID exists; otherwise updates the existing record
     * with the values from {@code entity}.
     *
     * <p>A {@code null} id property is matched with {@code IS NULL}, unlike in the by-id lookups (see
     * <i>{@code null} key values</i> in the {@linkplain UncheckedCrudDao class documentation}).</p>
     *
     * <p>Inside a transaction, the lookup and the write run in one transaction scope, as {@link #upsert(Object, Condition)}
     * describes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setId(123L);
     * user.setEmail("john@example.com");
     * user.setLastSeen(new java.util.Date());
     *
     * User result = userDao.upsert(user);
     * // Result will be either the newly inserted or updated user
     * }</pre>
     *
     * @param entity the entity to insert or update (must not be {@code null})
     * @return the saved entity (the input entity if it was newly inserted; otherwise the merged existing entity that was updated)
     * @throws IllegalArgumentException if {@code entity} is {@code null}, or an existing row is updated and {@code entity}
     *                                  has a non-ID property the loaded entity does not
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing the transaction scope (inside a transaction),
     *         or telling whether a transaction is active (from the auto-commit mode of a Spring-bound connection), fails, or looking up
     *         an existing row or executing the required INSERT or UPDATE statement fails. A Spring acquisition failure of the
     *         connection for the INSERT (outside a transaction, when no existing row is found) is not translated and propagates as
     *         {@code CannotGetJdbcConnectionException}.
     * @throws DuplicateResultException if more than one record matches the entity's ID property(ies)
     * @throws IllegalStateException if the call runs inside a Spring-managed transaction it cannot participate in (see
     *         {@link #upsert(Object, Condition)})
     * @throws UnsupportedOperationException if no existing row is found,
     *         {@link com.landawn.abacus.jdbc.annotation.DaoConfig#callGenerateIdForInsertIfIdNotSet()} is enabled, the entity's ID is not set
     *         and {@link #generateId()} has not been overridden; or if an existing row is updated and the loaded class is an immutable bean
     */
    @Override
    default T upsert(final T entity)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, DuplicateResultException, UnsupportedOperationException {
        N.checkArgNotNull(entity, cs.entity);

        final Class<?> cls = entity.getClass();
        final List<String> idPropNameList = QueryUtil.idPropNames(cls); // guaranteed non-empty for a CRUD entity class.

        return upsert(entity, idPropNameList);
    }

    /**
     * Performs an upsert operation, matching existing records by the specified unique properties:
     * inserts {@code entity} if no record with the same values exists; otherwise updates the existing
     * record with the values from {@code entity}.
     *
     * <p>A {@code null} match value is matched with {@code IS NULL}, so an existing row holding {@code NULL} in that
     * column is found and updated; the by-id lookups never match a {@code null} id component (see
     * <i>{@code null} key values</i> in the {@linkplain UncheckedCrudDao class documentation}).</p>
     *
     * <p>Inside a transaction, the lookup and the write run in one transaction scope, as {@link #upsert(Object, Condition)}
     * describes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setEmail("john@example.com");
     * user.setFirstName("John");
     * user.setLastName("Doe");
     * user.setScore(100);
     *
     * // Upsert based on email being unique
     * User result = userDao.upsert(user, Arrays.asList("email"));
     * }</pre>
     *
     * @param entity the entity to insert or update (must not be {@code null})
     * @param matchPropNames the property names that uniquely identify the record (must not be empty)
     *            (a spelling the entity's property lookup accepts, such as the column name, is resolved to the property name)
     * @return the saved entity (the input entity if it was newly inserted; otherwise the merged existing entity that was updated)
     * @throws IllegalArgumentException if {@code entity} is {@code null} or {@code matchPropNames} is {@code null} or empty,
     *                                  or if any name in {@code matchPropNames} is not a readable property of the entity class
     *                                  (a nested property path such as {@code "address.street"} is not accepted),
     *                                  or an existing row is updated and {@code entity} has a non-ID property the loaded
     *                                  entity does not
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing the transaction scope (inside a transaction),
     *         or telling whether a transaction is active (from the auto-commit mode of a Spring-bound connection), fails, or looking up
     *         an existing row or executing the required INSERT or UPDATE statement fails. A Spring acquisition failure of the
     *         connection for the INSERT (outside a transaction, when no existing row is found) is not translated and propagates as
     *         {@code CannotGetJdbcConnectionException}.
     * @throws DuplicateResultException if more than one record matches the specified {@code matchPropNames}
     * @throws IllegalStateException if the call runs inside a Spring-managed transaction it cannot participate in (see
     *         {@link #upsert(Object, Condition)})
     * @throws UnsupportedOperationException if no existing row is found,
     *         {@link com.landawn.abacus.jdbc.annotation.DaoConfig#callGenerateIdForInsertIfIdNotSet()} is enabled, the entity's ID is not set
     *         and {@link #generateId()} has not been overridden; or if an existing row is updated and the loaded class is an immutable bean
     */
    @Override
    default T upsert(final T entity, final Collection<String> matchPropNames)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, DuplicateResultException, UnsupportedOperationException {
        N.checkArgNotNull(entity, cs.entity);
        N.checkArgNotEmpty(matchPropNames, cs.matchPropNames);

        // The bean-info lookup that reads the values is lenient but the name is rendered into SQL as given, so a spelling
        // such as "FIRSTNAME" would read the right value yet reference a non-existent column.
        // WHY: a null match value renders IS NULL on purpose, the rule batchUpsert documents as well. An upsert matches by
        // values and must be repeatable; the by-id rule (DaoUtil.entityIdToCondition: a null component matches no row) would
        // insert a duplicate on every upsert of an entity holding null for a nullable key column, as most databases allow
        // several NULLs in a unique column. For NOT NULL key columns both rules match no row. The other values are bound with
        // their property types (see DaoUtil.typedAllEqual).
        final Condition cond = DaoUtil.typedAllEqual(entity, DaoUtil.toCanonicalPropNames(entity, matchPropNames));

        return upsert(entity, cond);
    }

    /**
     * Performs an upsert operation: inserts {@code entity} if no record matches the specified
     * condition; otherwise copies the non-{@code null} non-id properties from {@code entity} into the existing record
     * (loaded via {@link #findOnlyOne(Condition)}) and updates it. A {@code null} property of {@code entity}
     * keeps the existing value, so an upsert cannot clear a column to {@code NULL}.
     * ID properties are never assigned by this update. A complete ID addresses the loaded row using its declared
     * database types; if an ID component is {@code null}, the update reuses {@code cond}, since nullable IDs need not be unique.
     * This allows for upsert logic based on any criteria, not just ID fields.
     *
     * <p>Inside a transaction on the DAO's data source, the lookup and the write run in one transaction scope: a nested scope of
     * the transaction active on the thread (a failure, also one of the lookup, then marks that transaction rollback-only, so its
     * outermost {@code commit()} throws {@link com.landawn.abacus.jdbc.RollbackOnlyException}), or one participating in a
     * Spring-managed transaction that owns the data source's connection (see
     * {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)}; a failure then marks the Spring
     * transaction rollback-only, so committing it throws {@code UnexpectedRollbackException}). Outside a transaction, the
     * lookup and the write run as separate statements, the write committed on its own, as any single write is: a transaction
     * of their own would fail on a connection that a JTA transaction manages, which this library does not detect. This does
     * not keep a concurrent upsert of the same key out: under the usual isolation levels both may find no row and insert, and
     * a unique constraint on the matched columns makes one of them fail.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setEmail("john@example.com");
     * user.setDepartment("IT");
     * user.setLastUpdated(new java.util.Date());
     *
     * // Custom condition for upsert
     * Condition cond = Filters.and(
     *     Filters.eq("email", user.getEmail()),
     *     Filters.eq("department", user.getDepartment())
     * );
     *
     * User result = userDao.upsert(user, cond);
     * }</pre>
     *
     * @param entity the entity to insert or update (must not be {@code null})
     * @param cond the condition used to look up an existing record (must not be {@code null})
     * @return the saved entity: the inserted {@code entity} when no existing record was found,
     *         or the loaded database entity (with non-{@code null} non-id properties copied from {@code entity}) when an existing record was updated
     * @throws IllegalArgumentException if {@code entity} or {@code cond} is {@code null}, or an existing row is updated
     *                                  and {@code entity} has a non-ID property the loaded entity does not
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing the transaction scope (inside a transaction),
     *         or telling whether a transaction is active (from the auto-commit mode of a Spring-bound connection), fails, or looking up
     *         an existing row or executing the required INSERT or UPDATE statement fails. A Spring acquisition failure of the
     *         connection for the INSERT (outside a transaction, when no existing row is found) is not translated and propagates as
     *         {@code CannotGetJdbcConnectionException}.
     * @throws DuplicateResultException if more than one record matches the specified condition
     * @throws IllegalStateException if the call runs inside a Spring-managed transaction it cannot participate in (see
     *         {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UnsupportedOperationException if no existing row is found,
     *         {@link com.landawn.abacus.jdbc.annotation.DaoConfig#callGenerateIdForInsertIfIdNotSet()} is enabled, the entity's ID is not set
     *         and {@link #generateId()} has not been overridden; or if an existing row is updated and the loaded class is an immutable bean
     * @see Filters
     */
    @Override
    default T upsert(final T entity, final Condition cond)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, DuplicateResultException, UnsupportedOperationException {
        try {
            return CrudDao.super.upsert(entity, cond);
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Performs batch upsert of multiple entities using the default batch size
     * ({@link JdbcUtil#DEFAULT_BATCH_SIZE}).
     * Each entity will be inserted if new or updated if it already exists, matching by ID fields.
     *
     * <p>Inside a transaction, or for more than one entity, the lookups and the writes run in one transaction scope as
     * {@link #batchUpsert(Collection, Collection, int)} describes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = Arrays.asList(
     *     new User(1L, "John", "john@example.com"),
     *     new User(2L, "Jane", "jane@example.com"),
     *     new User(3L, "Bob", "bob@example.com")
     * );
     *
     * List<User> results = userDao.batchUpsert(users);
     * }</pre>
     *
     * @param entities the collection of entities to upsert
     * @return a list of saved entities (both inserted and updated), in the same iteration order as
     *         {@code entities}; an empty list if {@code entities} is {@code null} or empty
     * @throws IllegalArgumentException if {@code entities} contains a {@code null} element, or an existing row is updated
     *                                  and an entity has a non-ID property the loaded entity does not
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or looking up an
     *         existing row or executing the required INSERT or UPDATE statement fails. A Spring acquisition failure is not translated
     *         and propagates as {@code CannotGetJdbcConnectionException} when the call opens its transaction first (more than one
     *         entity, outside a transaction), or when acquiring the connection for inserting new entities fails.
     * @throws IllegalStateException if more than one existing record matches one entity's unique key,
     *                              or if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *                              holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *                              own while no JPA transaction owning it can be identified (see
     *                              {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UnsupportedOperationException if an entity is inserted while
     *         {@link com.landawn.abacus.jdbc.annotation.DaoConfig#callGenerateIdForInsertIfIdNotSet()} is enabled, its ID is not set and
     *         {@link #generateId()} has not been overridden; or if an existing row is updated and the loaded class is an immutable bean
     */
    @Override
    default List<T> batchUpsert(final Collection<? extends T> entities)
            throws IllegalArgumentException, UncheckedSQLException, IllegalStateException, UnsupportedOperationException {
        return batchUpsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch upsert of multiple entities with a specified batch size,
     * matching existing records by ID fields.
     * Large collections will be processed in batches of the specified size.
     *
     * <p>Inside a transaction, or for more than one entity, the lookups and the writes run in one transaction scope as
     * {@link #batchUpsert(Collection, Collection, int)} describes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> largeUserList = getThousandsOfUsers();
     * // Upsert in batches of 500
     * List<User> results = userDao.batchUpsert(largeUserList, 500);
     * }</pre>
     *
     * @param entities the collection of entities to upsert
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return a list of saved entities (both inserted and updated), in the same iteration order as
     *         {@code entities}; an empty list if {@code entities} is {@code null} or empty
     * @throws IllegalArgumentException if {@code entities} contains a {@code null} element, or if {@code batchSize} is not positive,
     *                                  or an existing row is updated and an entity has a non-ID property the loaded entity does not
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or looking up an
     *         existing row or executing the required INSERT or UPDATE statement fails. A Spring acquisition failure is not translated
     *         and propagates as {@code CannotGetJdbcConnectionException} when the call opens its transaction first (more than one
     *         entity, outside a transaction), or when acquiring the connection for inserting new entities fails.
     * @throws IllegalStateException if more than one existing record matches one entity's unique key,
     *                              or if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *                              holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *                              own while no JPA transaction owning it can be identified (see
     *                              {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UnsupportedOperationException if an entity is inserted while
     *         {@link com.landawn.abacus.jdbc.annotation.DaoConfig#callGenerateIdForInsertIfIdNotSet()} is enabled, its ID is not set and
     *         {@link #generateId()} has not been overridden; or if an existing row is updated and the loaded class is an immutable bean
     */
    @Override
    default List<T> batchUpsert(final Collection<? extends T> entities, final int batchSize)
            throws IllegalArgumentException, UncheckedSQLException, IllegalStateException, UnsupportedOperationException {
        if (N.notEmpty(entities)) {
            N.checkArgNotNull(N.firstOrNullIfEmpty(entities), "The first element in the specified collection 'entities' cannot be null");
            for (final T entity : entities) {
                N.checkArgNotNull(entity, "An element of 'entities' cannot be null");
            }
        }

        N.checkArgPositive(batchSize, cs.batchSize);

        if (N.isEmpty(entities)) {
            return new ArrayList<>();
        }

        final T entity = N.firstOrNullIfEmpty(entities);

        final Class<?> cls = entity.getClass();
        final List<String> idPropNameList = QueryUtil.idPropNames(cls); // guaranteed non-empty for a CRUD entity class.

        return batchUpsert(entities, idPropNameList, batchSize);
    }

    /**
     * Performs batch upsert based on the specified unique properties for matching.
     * This allows upsert logic based on properties other than the ID fields.
     * Uses the default batch size ({@link JdbcUtil#DEFAULT_BATCH_SIZE}).
     *
     * <p>Inside a transaction, or for more than one entity, the lookups and the writes run in one transaction scope as
     * {@link #batchUpsert(Collection, Collection, int)} describes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getUsersFromImport();
     * // Upsert based on email being unique
     * List<User> results = userDao.batchUpsert(users, Arrays.asList("email"));
     * }</pre>
     *
     * @param entities the collection of entities to upsert
     * @param matchPropNames the property names that uniquely identify each entity (must not be empty)
     * @return a list of saved entities (both inserted and updated), in the same iteration order as
     *         {@code entities}; an empty list if {@code entities} is {@code null} or empty
     * @throws IllegalArgumentException if {@code entities} contains a {@code null} element,
     *                                  if {@code matchPropNames} is {@code null} or empty, or if the entities are nonempty
     *                                  and a name in {@code matchPropNames} is not a property of the entity class,
     *                                  or an existing row is updated and an entity has a non-ID property the loaded entity does not
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or looking up an
     *         existing row or executing the required INSERT or UPDATE statement fails. A Spring acquisition failure is not translated
     *         and propagates as {@code CannotGetJdbcConnectionException} when the call opens its transaction first (more than one
     *         entity, outside a transaction), or when acquiring the connection for inserting new entities fails.
     * @throws IllegalStateException if more than one existing record matches one entity's unique key,
     *                              or if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *                              holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *                              own while no JPA transaction owning it can be identified (see
     *                              {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UnsupportedOperationException if an entity is inserted while
     *         {@link com.landawn.abacus.jdbc.annotation.DaoConfig#callGenerateIdForInsertIfIdNotSet()} is enabled, its ID is not set and
     *         {@link #generateId()} has not been overridden; or if an existing row is updated and the loaded class is an immutable bean
     */
    @Override
    default List<T> batchUpsert(final Collection<? extends T> entities, final Collection<String> matchPropNames)
            throws IllegalArgumentException, UncheckedSQLException, IllegalStateException, UnsupportedOperationException {
        return batchUpsert(entities, matchPropNames, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch upsert based on the specified unique properties with a custom batch size.
     * This provides the most flexibility for batch upsert operations.
     *
     * <p>Internally, the entities are partitioned into those that already exist (matched by the
     * supplied unique properties) and those that do not. New entities are inserted via
     * {@link #batchInsert(Collection, int)}; existing entities are updated by copying the non-{@code null}, non-id
     * (and non-unique-key) properties from the input entity into the loaded database entity and
     * calling {@link #batchUpdate(Collection, int)} for rows whose ID components are all non-{@code null}.
     * Rows with a {@code null} ID component are updated individually using the original match properties, excluding
     * ID columns from the assignments; a nullable ID alone need not uniquely identify a row. The lookups and the writes run in one transaction scope inside a
     * transaction, as for {@link #upsert(Object, Condition)}, and, outside one, for more than one entity, as other DAO batch
     * operations do (they used to run apart: the lookups before it, each on a connection of its own, and the writes in a
     * transaction only when both inserts and updates were needed or either set was large); a single entity outside a
     * transaction is upserted as by {@code upsert}. If that transaction,
     * or a transaction enclosing this call, rolls back, the IDs that {@code batchInsert} wrote onto the new
     * entities are put back to their previous values. Inside an active Spring-managed transaction that owns the data
     * source's connection, the transaction participates in it (see
     * {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)}): Spring commits the work, a failure
     * marks the Spring transaction rollback-only, and the IDs are put back if Spring rolls it back (this needs Spring's
     * transaction synchronization, which is enabled by default, and follows the innermost Spring-managed transaction on the
     * thread, as for {@code insert}).</p>
     *
     * <p>For a single match property, a {@code null} key is matched with {@code IS NULL}; it is not
     * placed in an {@code IN} predicate, whose SQL semantics would never match a {@code null} column.
     * With several match properties, every one of them is matched (an entity's key always holds all of them), a
     * {@code null} one with {@code IS NULL} as well; so an entity whose key holds {@code null} for a {@code NOT NULL}
     * column (e.g. a component of the primary key, when matching by id) matches no row and is inserted.
     * Array-typed key values (e.g. a {@code byte[]} binary UUID) are matched by content.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> importedUsers = parseCSVFile();
     * // Upsert based on email, in batches of 1000
     * List<User> results = userDao.batchUpsert(
     *     importedUsers,
     *     Arrays.asList("email"),
     *     1000
     * );
     * }</pre>
     *
     * @param entities the collection of entities to upsert
     * @param matchPropNames the property names that uniquely identify each entity (must not be empty)
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return a list of saved entities (both inserted and updated), in the same iteration order as
     *         {@code entities}; an empty list if {@code entities} is {@code null} or empty
     * @throws IllegalArgumentException if {@code entities} contains a {@code null} element,
     *                                  if {@code matchPropNames} is {@code null}/empty, if {@code batchSize} is not positive,
     *                                  or if the entities are nonempty and a name in {@code matchPropNames} is not a property of the entity class,
     *                                  or an existing row is updated and an entity has a non-ID property the loaded entity does not
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or looking up an
     *         existing row or executing the required INSERT or UPDATE statement fails. A Spring acquisition failure is not translated
     *         and propagates as {@code CannotGetJdbcConnectionException} when the call opens its transaction first (more than one
     *         entity, outside a transaction), or when acquiring the connection for inserting new entities fails.
     * @throws IllegalStateException if more than one existing record matches one entity's unique key,
     *                              or if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *                              holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *                              own while no JPA transaction owning it can be identified (see
     *                              {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UnsupportedOperationException if an entity is inserted while
     *         {@link com.landawn.abacus.jdbc.annotation.DaoConfig#callGenerateIdForInsertIfIdNotSet()} is enabled, its ID is not set and
     *         {@link #generateId()} has not been overridden; or if an existing row is updated and the loaded class is an immutable bean
     */
    @Override
    default List<T> batchUpsert(final Collection<? extends T> entities, final Collection<String> matchPropNames, final int batchSize)
            throws IllegalArgumentException, UncheckedSQLException, IllegalStateException, UnsupportedOperationException {
        try {
            return CrudDao.super.batchUpsert(entities, matchPropNames, batchSize);
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

}
