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
import java.util.Map;
import java.util.Set;

import com.landawn.abacus.exception.DuplicateResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.cs;
import com.landawn.abacus.jdbc.IsolationLevel;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.SqlTransaction;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.query.QueryUtil;
import com.landawn.abacus.util.Beans;
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Seid;
import com.landawn.abacus.util.Seq;
import com.landawn.abacus.util.stream.Stream;

/**
 * Provides comprehensive CRUD (Create, Read, Update, Delete) operations for entity management.
 * This interface is designed to work with entity classes that have an ID field annotated with {@code @Id}.
 *
 * <p>It supports batch operations, single-property queries by ID, unique-result queries, upsert,
 * refresh, and various primitive/Date/byte[] type conversions on top of the base {@link Dao}
 * interface.</p>
 *
 * <p>All database operations declared here are <i>checked</i>: they propagate {@link SQLException}
 * to the caller. For a variant whose methods instead throw the unchecked
 * {@link UncheckedSQLException}, see {@link UncheckedCrudDao}. For variants
 * that forbid mutating operations, see {@link ReadOnlyCrudDao} and {@link NonUpdateCrudDao}.</p>
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
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long, UserDao> {
 *     // Custom query methods can be added here
 * }
 *
 * // Usage
 * UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource);
 * User user = new User("John", "Doe");
 * Long id = userDao.insert(user);
 * com.landawn.abacus.util.u.Optional<User> retrieved = userDao.get(id);
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <ID> the ID type of the entity (e.g. {@code Long}, {@code String}, {@code EntityId})
 * @param <TD> the concrete DAO type itself (self-referencing generic for fluent method chaining)
 *
 * @see JdbcUtil#prepareQuery(javax.sql.DataSource, String)
 * @see JdbcUtil#prepareNamedQuery(javax.sql.DataSource, String)
 * @see JdbcUtil#beginTransaction(javax.sql.DataSource, IsolationLevel)
 * @see Dao
 * @see com.landawn.abacus.annotation.JoinedBy
 * @see com.landawn.abacus.query.Filters
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
public non-sealed interface CrudDao<T, ID, TD extends CrudDao<T, ID, TD>>
        extends Dao<T, TD>, CrudReadOps<T, ID, TD>, CrudInsertOps<T, ID, TD>, CrudUpdateOps<T, ID, TD>, CrudDeleteOps<T, ID, TD> {
    /**
     * Performs an upsert operation, matching existing records by the entity's ID property(ies):
     * inserts {@code entity} if no record with the same ID exists; otherwise updates the existing record
     * with the values from {@code entity}.
     *
     * <p>A {@code null} id property is matched with {@code IS NULL}, unlike in the by-id lookups (see
     * <i>{@code null} key values</i> in the {@linkplain CrudDao class documentation}).</p>
     *
     * <p>Inside a transaction, the lookup and the write run in one transaction scope, as {@link #upsert(Object, Condition)}
     * describes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setId(userId);
     * user.setName("John Doe");
     * user.setEmail("john@example.com");
     * User savedUser = userDao.upsert(user);   // Insert if new, update if exists
     * }</pre>
     *
     * @param entity the entity to insert or update (must not be {@code null})
     * @return the saved entity (the input entity if it was newly inserted; otherwise the merged existing entity that was updated)
     * @throws IllegalArgumentException if {@code entity} is {@code null}, or an existing row is updated and {@code entity}
     *                                  has a non-ID property the loaded entity does not
     * @throws UncheckedSQLException if direct (non-Spring) acquisition of a required database connection fails, or starting or completing the transaction scope
     *         (inside a transaction), or telling whether a transaction is active (from the auto-commit mode of a
     *         Spring-bound connection), fails.
     *         Spring acquisition failures instead propagate as {@code CannotGetJdbcConnectionException}.
     * @throws SQLException if looking up an existing row or executing the required INSERT or UPDATE statement fails
     * @throws DuplicateResultException if more than one record matches the entity's ID property(ies)
     * @throws IllegalStateException if the call runs inside a Spring-managed transaction it cannot participate in (see
     *         {@link #upsert(Object, Condition)})
     * @throws UnsupportedOperationException if no existing row is found,
     *         {@link com.landawn.abacus.jdbc.annotation.DaoConfig#callGenerateIdForInsertIfIdNotSet()} is enabled, the entity's ID is not set
     *         and {@link #generateId()} has not been overridden; or if an existing row is updated and the loaded class is an immutable bean
     */
    default T upsert(final T entity) throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, SQLException, DuplicateResultException,
            UnsupportedOperationException {
        N.checkArgNotNull(entity, cs.entity);

        final Class<?> cls = entity.getClass();
        final List<String> idPropNameList = QueryUtil.idPropNames(cls); // guaranteed non-empty for a CRUD entity class.

        return upsert(entity, idPropNameList);
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
     * User user = new User("john@example.com", "John Doe");
     * // Upsert based on email instead of ID
     * User saved = userDao.upsert(user, Filters.eq("email", user.getEmail()));
     * }</pre>
     *
     * @param entity the entity to insert or update (must not be {@code null})
     * @param cond the condition used to look up an existing record (must not be {@code null})
     * @return the saved entity: the inserted {@code entity} when no existing record was found,
     *         or the loaded database entity (with non-{@code null} non-id properties copied from {@code entity}) when an existing record was updated
     * @throws IllegalArgumentException if {@code entity} or {@code cond} is {@code null}, or an existing row is updated
     *                                  and {@code entity} has a non-ID property the loaded entity does not
     * @throws UncheckedSQLException if direct (non-Spring) acquisition of a required database connection fails, or starting or completing the transaction scope
     *         (inside a transaction), or telling whether a transaction is active (from the auto-commit mode of a
     *         Spring-bound connection), fails.
     *         Spring acquisition failures instead propagate as {@code CannotGetJdbcConnectionException}.
     * @throws SQLException if looking up an existing row or executing the required INSERT or UPDATE statement fails
     * @throws DuplicateResultException if more than one record matches the specified condition
     * @throws IllegalStateException if the call runs inside a Spring-managed transaction it cannot participate in (see
     *         {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UnsupportedOperationException if no existing row is found,
     *         {@link com.landawn.abacus.jdbc.annotation.DaoConfig#callGenerateIdForInsertIfIdNotSet()} is enabled, the entity's ID is not set
     *         and {@link #generateId()} has not been overridden; or if an existing row is updated and the loaded class is an immutable bean
     * @see Filters
     */
    @Override
    default T upsert(final T entity, final Condition cond) throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, SQLException,
            DuplicateResultException, UnsupportedOperationException {
        N.checkArgNotNull(entity, cs.entity);
        N.checkArgNotNull(cond, cs.cond);

        // Inside a transaction on the data source, the lookup and the write run in a nested scope of it (or one participating in
        // the Spring-managed transaction that owns its connection), so that a failure of either marks that transaction
        // rollback-only: a caller that catches it can't commit the lookup's locks without the write (nor can Spring, whose own
        // rules don't roll back for a checked SQLException). Outside one, they run as the statements of any single write do. WHY not a transaction of its own there: on a connection a JTA transaction manages,
        // which this library can't tell (see SqlTransaction), its commit failed and aborted the connection, where an upsert
        // used to work.
        final SqlTransaction tran = JdbcUtil.isInTransaction(dataSource()) ? JdbcUtil.beginTransaction(dataSource()) : null;
        Throwable failure = null;
        T result = null;

        try {
            final T dbEntity = findOnlyOne(cond).orElseNull();

            if (dbEntity == null) {
                insert(entity);
                result = entity;
            } else {
                final Class<?> cls = entity.getClass();
                final List<String> idPropNameList = QueryUtil.idPropNames(cls);

                if (N.isEmpty(idPropNameList)) {
                    Beans.mergeInto(entity, dbEntity);
                } else {
                    Beans.mergeInto(entity, dbEntity, false, N.newHashSet(idPropNameList));
                }

                if (N.anyMatch(idPropNameList, idName -> Beans.getPropValue(dbEntity, idName) == null)) {
                    // A nullable ID is neither addressable with `= NULL` nor necessarily unique with IS NULL. Keep the
                    // original lookup condition, which may distinguish rows sharing that ID, and never assign ID columns
                    // (a generated identity cannot be assigned even its current value).
                    @SuppressWarnings("deprecation")
                    final Collection<String> propNamesToUpdate = JdbcUtil.getUpdatePropNames(targetEntityClass(), N.newHashSet(idPropNameList));

                    if (N.notEmpty(propNamesToUpdate)) {
                        update(dbEntity, propNamesToUpdate, cond);
                    }
                } else {
                    // Preserve the by-id binder's property-specific types, e.g. an enum ID stored as an ordinal.
                    update(dbEntity);
                }
                result = dbEntity;
            }

            if (tran != null) {
                tran.commit();
            }
        } catch (final Throwable e) { //NOSONAR
            failure = e;
            throw e;
        } finally {
            try {
                if (tran != null) {
                    tran.rollbackIfNotCommitted();
                }
            } catch (final RuntimeException | Error rollbackFailure) {
                if (failure == null) {
                    throw rollbackFailure;
                }

                DaoUtil.addSuppressedIfDifferent(failure, rollbackFailure);
            }
        }

        return result;
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
     * List<User> users = loadUsersFromImport();
     * List<User> savedUsers = userDao.batchUpsert(users);
     * }</pre>
     *
     * @param entities the collection of entities to upsert
     * @return a list of saved entities (both inserted and updated), in the same iteration order as
     *         {@code entities}; an empty list if {@code entities} is {@code null} or empty
     * @throws IllegalArgumentException if {@code entities} contains a {@code null} element, or an existing row is updated
     *                                  and an entity has a non-ID property the loaded entity does not
     * @throws UncheckedSQLException if direct (non-Spring) acquisition of a required database connection fails, or starting or completing an internally required transaction fails.
     *         Spring acquisition failures instead propagate as {@code CannotGetJdbcConnectionException}.
     * @throws SQLException if looking up an existing row or executing the required INSERT or UPDATE statement fails
     * @throws IllegalStateException if more than one existing record matches one entity's unique key,
     *                              or if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *                              holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *                              own while no JPA transaction owning it can be identified (see
     *                              {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UnsupportedOperationException if an entity is inserted while
     *         {@link com.landawn.abacus.jdbc.annotation.DaoConfig#callGenerateIdForInsertIfIdNotSet()} is enabled, its ID is not set and
     *         {@link #generateId()} has not been overridden; or if an existing row is updated and the loaded class is an immutable bean
     */
    default List<T> batchUpsert(final Collection<? extends T> entities)
            throws IllegalArgumentException, UncheckedSQLException, SQLException, IllegalStateException, UnsupportedOperationException {
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
     * List<User> importedUsers = loadUsersFromFile();   // 30000 users
     * // Upsert in batches of 2000
     * List<User> savedUsers = userDao.batchUpsert(importedUsers, 2000);
     * System.out.println(savedUsers.size() + " users saved");
     * }</pre>
     *
     * @param entities the collection of entities to upsert
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return a list of saved entities (both inserted and updated), in the same iteration order as
     *         {@code entities}; an empty list if {@code entities} is {@code null} or empty
     * @throws IllegalArgumentException if {@code entities} contains a {@code null} element, or if {@code batchSize} is not positive,
     *                                  or an existing row is updated and an entity has a non-ID property the loaded entity does not
     * @throws UncheckedSQLException if direct (non-Spring) acquisition of a required database connection fails, or starting or completing an internally required transaction fails.
     *         Spring acquisition failures instead propagate as {@code CannotGetJdbcConnectionException}.
     * @throws SQLException if looking up an existing row or executing the required INSERT or UPDATE statement fails
     * @throws IllegalStateException if more than one existing record matches one entity's unique key,
     *                              or if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *                              holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *                              own while no JPA transaction owning it can be identified (see
     *                              {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UnsupportedOperationException if an entity is inserted while
     *         {@link com.landawn.abacus.jdbc.annotation.DaoConfig#callGenerateIdForInsertIfIdNotSet()} is enabled, its ID is not set and
     *         {@link #generateId()} has not been overridden; or if an existing row is updated and the loaded class is an immutable bean
     */
    default List<T> batchUpsert(final Collection<? extends T> entities, final int batchSize)
            throws IllegalArgumentException, UncheckedSQLException, SQLException, IllegalStateException, UnsupportedOperationException {
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
     * List<User> users = loadUsers();
     * // Upsert based on email uniqueness
     * List<User> saved = userDao.batchUpsert(users, Arrays.asList("email"));
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
     * @throws UncheckedSQLException if direct (non-Spring) acquisition of a required database connection fails, or starting or completing an internally required transaction fails.
     *         Spring acquisition failures instead propagate as {@code CannotGetJdbcConnectionException}.
     * @throws SQLException if looking up an existing row or executing the required INSERT or UPDATE statement fails
     * @throws IllegalStateException if more than one existing record matches one entity's unique key,
     *                              or if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *                              holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *                              own while no JPA transaction owning it can be identified (see
     *                              {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UnsupportedOperationException if an entity is inserted while
     *         {@link com.landawn.abacus.jdbc.annotation.DaoConfig#callGenerateIdForInsertIfIdNotSet()} is enabled, its ID is not set and
     *         {@link #generateId()} has not been overridden; or if an existing row is updated and the loaded class is an immutable bean
     */
    default List<T> batchUpsert(final Collection<? extends T> entities, final Collection<String> matchPropNames)
            throws IllegalArgumentException, UncheckedSQLException, SQLException, IllegalStateException, UnsupportedOperationException {
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
     * List<User> largeUserList = importUsers();   // 25000 users from external system
     * // Upsert based on email uniqueness in batches of 2000
     * List<User> saved = userDao.batchUpsert(largeUserList, Arrays.asList("email"), 2000);
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
     * @throws UncheckedSQLException if direct (non-Spring) acquisition of a required database connection fails, or starting or completing an internally required transaction fails.
     *         Spring acquisition failures instead propagate as {@code CannotGetJdbcConnectionException}.
     * @throws SQLException if looking up an existing row or executing the required INSERT or UPDATE statement fails
     * @throws IllegalStateException if more than one existing record matches one entity's unique key,
     *                              or if the call runs inside a Spring-managed transaction it cannot participate in: the ownership of the Spring connection
     *                              holder bound to the data source cannot be determined, or that transaction exposes its connection through a handle of its
     *                              own while no JPA transaction owning it can be identified (see
     *                              {@link com.landawn.abacus.jdbc.JdbcUtil#beginTransaction(javax.sql.DataSource)})
     * @throws UnsupportedOperationException if an entity is inserted while
     *         {@link com.landawn.abacus.jdbc.annotation.DaoConfig#callGenerateIdForInsertIfIdNotSet()} is enabled, its ID is not set and
     *         {@link #generateId()} has not been overridden; or if an existing row is updated and the loaded class is an immutable bean
     */
    default List<T> batchUpsert(final Collection<? extends T> entities, final Collection<String> matchPropNames, final int batchSize)
            throws IllegalArgumentException, UncheckedSQLException, SQLException, IllegalStateException, UnsupportedOperationException {
        if (N.notEmpty(entities)) {
            N.checkArgNotNull(N.firstOrNullIfEmpty(entities), "The first element in the specified collection 'entities' cannot be null");
            for (final T entity : entities) {
                N.checkArgNotNull(entity, "An element of 'entities' cannot be null");
            }
        }

        N.checkArgNotEmpty(matchPropNames, cs.matchPropNames);
        N.checkArgPositive(batchSize, cs.batchSize);

        if (N.isEmpty(entities)) {
            return new ArrayList<>();
        }

        final T first = N.firstOrNullIfEmpty(entities);

        final List<String> uniquePropNameList = matchPropNames instanceof List ? (List<String>) matchPropNames : new ArrayList<>(matchPropNames);
        final Class<?> cls = first.getClass();
        final BeanInfo entityInfo = ParserUtil.getBeanInfo(cls);

        final List<PropInfo> uniquePropInfos = N.map(uniquePropNameList, entityInfo::getPropInfo);

        for (int i = 0; i < uniquePropInfos.size(); i++) {
            if (uniquePropInfos.get(i) == null) {
                throw new IllegalArgumentException("No property found with name: '" + uniquePropNameList.get(i) + "' in class: " + cls.getName());
            }
        }

        final PropInfo uniquePropInfo = uniquePropInfos.get(0);
        final com.landawn.abacus.util.function.Function<T, Object> singleKeyExtractor = uniquePropInfo::getPropValue;

        @SuppressWarnings("deprecation")
        final com.landawn.abacus.util.function.Function<T, EntityId> entityIdExtractor = it -> {
            final Seid entityId = Seid.of(entityInfo.simpleClassName);

            for (final PropInfo propInfo : uniquePropInfos) {
                entityId.set(propInfo.name, propInfo.getPropValue(it));
            }

            return entityId;
        };

        // A single array-typed key (e.g. a byte[] binary UUID) is matched by content: the loaded rows carry their own array
        // instances, so an identity-keyed map never found an existing row and the entity was inserted again.
        final com.landawn.abacus.util.function.Function<T, ?> keysExtractor = uniquePropNameList.size() == 1
                ? it -> DaoUtil.valueKey(singleKeyExtractor.apply(it))
                : entityIdExtractor;

        // The lookups and the writes run in one transaction scope inside a transaction, as for upsert(entity, cond), and, outside
        // one, for more than one entity, as other DAO batch operations do: the lookups used to run before it, each on a connection
        // of its own, and the writes in a transaction only when both inserts and updates were needed or either set was large. A
        // single entity outside a transaction runs as upsert does (see there why not in a transaction of its own). batchInsert
        // writes the generated IDs back onto the inserted entities before this transaction commits; if it rolls back (or a
        // transaction enclosing this call does), batchInsert puts the pre-insert IDs back, as it registers them with the
        // transaction it runs in: otherwise the entities would keep IDs of rows that no longer exist. Inside a Spring-managed
        // transaction this transaction participates in it (see JdbcUtil.beginTransaction).
        final SqlTransaction tran = entities.size() > 1 || JdbcUtil.isInTransaction(dataSource()) ? JdbcUtil.beginTransaction(dataSource()) : null;
        Throwable failure = null;
        final Map<Object, T> dbIdEntityMap;

        try {
            // De-duplicate lookup keys before splitting them into query batches. Without this, equal
            // keys that landed in different batches returned the same database row more than once,
            // and the throwing merger below incorrectly reported a duplicate database result.
            // Query by the resolved property name (as the composite-key path does via Seid): getPropInfo also
            // accepts non-canonical spellings (e.g. "FIRSTNAME") that the SQL builder cannot map to a column.
            final List<T> dbEntities = uniquePropNameList.size() == 1
                    ? Seq.of(N.distinct(N.map(entities, singleKeyExtractor)), SQLException.class)
                            .split(batchSize)
                            .flatmap(it -> list(DaoUtil.singlePropValuesToCondition(uniquePropInfo.name, it, entityInfo)))
                            .toList()
                    : Seq.of(N.distinct(N.map(entities, entityIdExtractor)), SQLException.class) //
                            .split(batchSize)
                            // Each key holds EVERY match property (entityIdExtractor sets them all, null included), so unlike a
                            // caller-supplied EntityId (see DaoUtil.entityIdToCondition) it cannot be incomplete and pair an
                            // entity with a row that agrees on only some of them. A null component renders IS NULL: the
                            // documented match of a null key, which a NOT NULL (e.g. primary key) column never satisfies. The
                            // other values are bound with their property types (Filters.idToCond bound them by their runtime class).
                            .flatmap(it -> list(DaoUtil.typedAnyOfAllEqual(it, entityInfo)))
                            .toList();

            dbIdEntityMap = Stream.of(dbEntities).toMap(keysExtractor, Fn.identity(), Fn.throwingMerger());
            final Map<Boolean, List<T>> map = Stream.of(entities).groupTo(it -> dbIdEntityMap.containsKey(keysExtractor.apply(it)), Fn.identity());
            final List<T> entitiesToUpdate = map.get(true);
            final List<T> entitiesToInsert = map.get(false);
            final List<String> idPropNameList = QueryUtil.idPropNames(cls);

            if (N.notEmpty(entitiesToInsert)) {
                batchInsert(entitiesToInsert, batchSize);
            }

            if (N.notEmpty(entitiesToUpdate)) {
                final Set<String> ignoredPropNames = N.newHashSet(matchPropNames);

                if (N.notEmpty(idPropNameList)) {
                    ignoredPropNames.addAll(idPropNameList);
                }

                final List<T> dbEntitiesToUpdate = Stream.of(entitiesToUpdate)
                        .map(it -> Beans.mergeInto(it, dbIdEntityMap.get(keysExtractor.apply(it)), false, ignoredPropNames))
                        .toList();

                if (N.isEmpty(idPropNameList)) {
                    batchUpdate(dbEntitiesToUpdate, batchSize);
                } else {
                    final List<T> idKeyedEntities = new ArrayList<>();
                    final List<T> nullKeyedEntities = new ArrayList<>();

                    for (final T dbEntity : dbEntitiesToUpdate) {
                        boolean hasNullId = false;

                        for (final String idName : idPropNameList) {
                            if (entityInfo.getPropInfo(idName).getPropValue(dbEntity) == null) {
                                hasNullId = true;
                                break;
                            }
                        }

                        (hasNullId ? nullKeyedEntities : idKeyedEntities).add(dbEntity);
                    }

                    if (N.notEmpty(idKeyedEntities)) {
                        batchUpdate(idKeyedEntities, batchSize);
                    }

                    if (N.notEmpty(nullKeyedEntities)) {
                        @SuppressWarnings("deprecation")
                        final Collection<String> propNamesToUpdate = JdbcUtil.getUpdatePropNames(targetEntityClass(), N.newHashSet(idPropNameList));
                        final List<String> resolvedMatchPropNames = N.map(uniquePropInfos, it -> it.name);

                        // Nullable IDs need not be unique: reuse the original match properties, whose values were excluded
                        // from the merge, so a match by e.g. email cannot overwrite a different row with the same NULL ID.
                        // As for by-id updates, omit all ID columns and do nothing if no updatable properties remain.
                        if (N.notEmpty(propNamesToUpdate)) {
                            for (final T dbEntity : nullKeyedEntities) {
                                update(dbEntity, propNamesToUpdate, DaoUtil.typedAllEqual(dbEntity, resolvedMatchPropNames));
                            }
                        }
                    }
                }
            }

            if (tran != null) {
                tran.commit();
            }
        } catch (final Throwable e) { //NOSONAR
            failure = e;
            throw e;
        } finally {
            try {
                if (tran != null) {
                    tran.rollbackIfNotCommitted();
                }
            } catch (final RuntimeException | Error rollbackFailure) {
                if (failure == null) {
                    throw rollbackFailure;
                }

                DaoUtil.addSuppressedIfDifferent(failure, rollbackFailure);
            }
        }

        final List<T> result = new ArrayList<>(entities.size());

        // Classification groups inserts and updates for efficient batch execution, but callers
        // should not observe that internal grouping in the returned list.
        for (final T entity : entities) {
            final T dbEntity = dbIdEntityMap.get(keysExtractor.apply(entity));
            result.add(dbEntity == null ? entity : dbEntity);
        }

        return result;
    }

}
