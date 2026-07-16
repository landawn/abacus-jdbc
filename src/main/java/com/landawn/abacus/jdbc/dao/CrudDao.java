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
import com.landawn.abacus.jdbc.IsolationLevel;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.SqlTransaction;
import com.landawn.abacus.jdbc.cs;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.query.QueryUtil;
import com.landawn.abacus.query.condition.Condition;
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
 * {@link com.landawn.abacus.exception.UncheckedSQLException}, see {@link UncheckedCrudDao}. For variants
 * that forbid mutating operations, see {@link ReadOnlyCrudDao} and {@link NonUpdateCrudDao}.</p>
 *
 * <p><b>ID semantics:</b> the entity class must declare one or more {@code @Id} properties. A single id
 * property maps directly to the {@code <ID>} type (for example {@code Long} or {@code String}), whereas a
 * composite (multi-column) key is represented by an {@link EntityId}. Insert operations write a
 * database-generated key back into the entity's id property where applicable, and {@code by-id} lookups
 * treat the supplied id as a primary-key match.</p>
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
 * Optional<User> retrieved = userDao.get(id);
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <ID> the ID type of the entity (e.g. {@code Long}, {@code String}, {@code EntityId})
 * @param <TD> the self-type of the DAO for fluent interface support
 *
 * @see JdbcUtil#prepareQuery(javax.sql.DataSource, String)
 * @see JdbcUtil#prepareNamedQuery(javax.sql.DataSource, String)
 * @see JdbcUtil#beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)
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
     * @return the saved entity (either newly inserted or updated)
     * @throws IllegalArgumentException if {@code entity} is {@code null}
     * @throws SQLException if a database access error occurs
     * @throws DuplicateResultException if more than one record matches the entity's ID property(ies)
     */
    default T upsert(final T entity) throws SQLException {
        N.checkArgNotNull(entity, cs.entity);

        final Class<?> cls = entity.getClass();
        final List<String> idPropNameList = QueryUtil.idPropNames(cls); // guaranteed non-empty for a CRUD entity class.

        return upsert(entity, idPropNameList);
    }

    /**
     * Performs an upsert operation: inserts {@code entity} if no record matches the specified
     * condition; otherwise copies non-id properties from {@code entity} into the existing record
     * (loaded via {@link #findOnlyOne(Condition)}) and updates it.
     * This allows for upsert logic based on any criteria, not just ID fields.
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
     *         or the loaded database entity (with non-id properties copied from {@code entity}) when an existing record was updated
     * @throws IllegalArgumentException if {@code entity} or {@code cond} is {@code null}
     * @throws SQLException if a database access error occurs
     * @throws DuplicateResultException if more than one record matches the specified condition
     * @see Filters
     */
    @Override
    default T upsert(final T entity, final Condition cond) throws SQLException {
        N.checkArgNotNull(entity, cs.entity);
        N.checkArgNotNull(cond, cs.cond);

        final T dbEntity = findOnlyOne(cond).orElseNull();

        if (dbEntity == null) {
            insert(entity);
            return entity;
        } else {
            final Class<?> cls = entity.getClass();
            final List<String> idPropNameList = QueryUtil.idPropNames(cls);

            if (N.isEmpty(idPropNameList)) {
                Beans.mergeInto(entity, dbEntity);
            } else {
                Beans.mergeInto(entity, dbEntity, false, N.newHashSet(idPropNameList));
            }

            update(dbEntity);
            return dbEntity;
        }
    }

    /**
     * Performs batch upsert of multiple entities using the default batch size
     * ({@link JdbcUtil#DEFAULT_BATCH_SIZE}).
     * Each entity will be inserted if new or updated if it already exists, matching by ID fields.
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
     * @throws SQLException if a database access error occurs
     */
    default List<T> batchUpsert(final Collection<? extends T> entities) throws SQLException {
        return batchUpsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch upsert of multiple entities with a specified batch size,
     * matching existing records by ID fields.
     * Large collections will be processed in batches of the specified size.
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
     * @throws IllegalArgumentException if {@code batchSize} is not positive
     * @throws SQLException if a database access error occurs
     */
    default List<T> batchUpsert(final Collection<? extends T> entities, final int batchSize) throws SQLException {
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
     * @throws IllegalArgumentException if {@code matchPropNames} is {@code null} or empty
     * @throws SQLException if a database access error occurs
     */
    default List<T> batchUpsert(final Collection<? extends T> entities, final Collection<String> matchPropNames) throws SQLException {
        return batchUpsert(entities, matchPropNames, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch upsert based on the specified unique properties with a custom batch size.
     * This provides the most flexibility for batch upsert operations.
     *
     * <p>Internally, the entities are partitioned into those that already exist (matched by the
     * supplied unique properties) and those that do not. New entities are inserted via
     * {@link #batchInsert(Collection, int)}; existing entities are updated by copying non-id
     * (and non-unique-key) properties from the input entity into the loaded database entity and
     * calling {@link #batchUpdate(Collection, int)}. When both inserts and updates are needed
     * (or either set is large), the operation is wrapped in a transaction.</p>
     *
     * <p>For a single match property, a {@code null} key is matched with {@code IS NULL}; it is not
     * placed in an {@code IN} predicate, whose SQL semantics would never match a null column.</p>
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
     * @throws IllegalArgumentException if {@code matchPropNames} is {@code null}/empty,
     *                                  if {@code batchSize} is not positive,
     *                                  or if any name in {@code matchPropNames} is not a property of the entity class
     * @throws IllegalStateException if more than one existing record matches one entity's unique key
     * @throws SQLException if a database access error occurs
     */
    default List<T> batchUpsert(final Collection<? extends T> entities, final Collection<String> matchPropNames, final int batchSize) throws SQLException {
        N.checkArgPositive(batchSize, cs.batchSize);
        N.checkArgNotEmpty(matchPropNames, cs.matchPropNames);

        if (N.isEmpty(entities)) {
            return new ArrayList<>();
        }

        final List<String> uniquePropNameList = matchPropNames instanceof List ? (List<String>) matchPropNames : new ArrayList<>(matchPropNames);
        final T first = N.firstOrNullIfEmpty(entities);
        final Class<?> cls = first.getClass();
        final BeanInfo entityInfo = ParserUtil.getBeanInfo(cls);

        final PropInfo uniquePropInfo = entityInfo.getPropInfo(uniquePropNameList.get(0));

        final List<PropInfo> uniquePropInfos = N.map(uniquePropNameList, entityInfo::getPropInfo);

        for (int i = 0; i < uniquePropInfos.size(); i++) {
            if (uniquePropInfos.get(i) == null) {
                throw new IllegalArgumentException("No property found with name: '" + uniquePropNameList.get(i) + "' in class: " + cls.getName());
            }
        }

        final com.landawn.abacus.util.function.Function<T, Object> singleKeyExtractor = uniquePropInfo::getPropValue;

        @SuppressWarnings("deprecation")
        final com.landawn.abacus.util.function.Function<T, EntityId> entityIdExtractor = it -> {
            final Seid entityId = Seid.of(entityInfo.simpleClassName);

            for (final PropInfo propInfo : uniquePropInfos) {
                entityId.set(propInfo.name, propInfo.getPropValue(it));
            }

            return entityId;
        };

        final com.landawn.abacus.util.function.Function<T, ?> keysExtractor = uniquePropNameList.size() == 1 ? singleKeyExtractor : entityIdExtractor;

        // De-duplicate lookup keys before splitting them into query batches. Without this, equal
        // keys that landed in different batches returned the same database row more than once,
        // and the throwing merger below incorrectly reported a duplicate database result.
        final List<T> dbEntities = uniquePropNameList.size() == 1
                ? Seq.of(N.distinct(N.map(entities, singleKeyExtractor)), SQLException.class)
                        .split(batchSize)
                        .flatmap(it -> list(DaoUtil.singlePropValuesToCondition(uniquePropNameList.get(0), it)))
                        .toList()
                : Seq.of(N.distinct(N.map(entities, entityIdExtractor)), SQLException.class) //
                        .split(batchSize)
                        .flatmap(it -> list(Filters.idToCond(it)))
                        .toList();

        final Map<Object, T> dbIdEntityMap = Stream.of(dbEntities).toMap(keysExtractor, Fn.identity(), Fn.throwingMerger());
        final Map<Boolean, List<T>> map = Stream.of(entities).groupTo(it -> dbIdEntityMap.containsKey(keysExtractor.apply(it)), Fn.identity());
        final List<T> entitiesToUpdate = map.get(true);
        final List<T> entitiesToInsert = map.get(false);

        final List<T> result = new ArrayList<>(entities.size());
        final SqlTransaction tran = (N.notEmpty(entitiesToInsert) && N.notEmpty(entitiesToUpdate))
                || (N.notEmpty(entitiesToInsert) && entitiesToInsert.size() > batchSize)
                || (N.notEmpty(entitiesToUpdate) && entitiesToUpdate.size() > batchSize) ? JdbcUtil.beginTransaction(dataSource()) : null;
        Throwable failure = null;

        try {
            if (N.notEmpty(entitiesToInsert)) {
                batchInsert(entitiesToInsert, batchSize);
            }

            if (N.notEmpty(entitiesToUpdate)) {
                final Set<String> ignoredPropNames = N.newHashSet(matchPropNames);

                final List<String> idPropNameList = QueryUtil.idPropNames(cls);

                if (N.notEmpty(idPropNameList)) {
                    ignoredPropNames.addAll(idPropNameList);
                }

                final List<T> dbEntitiesToUpdate = Stream.of(entitiesToUpdate)
                        .map(it -> Beans.mergeInto(it, dbIdEntityMap.get(keysExtractor.apply(it)), false, ignoredPropNames))
                        .toList();

                batchUpdate(dbEntitiesToUpdate, batchSize);
            }

            if (tran != null) {
                tran.commit();
            }
        } catch (final Throwable e) { //NOSONAR
            failure = e;
            throw e;
        } finally {
            if (tran != null) {
                try {
                    tran.rollbackIfNotCommitted();
                } catch (final RuntimeException | Error rollbackFailure) {
                    if (failure == null) {
                        throw rollbackFailure;
                    }

                    DaoUtil.addSuppressedIfDifferent(failure, rollbackFailure);
                }
            }
        }

        // Classification groups inserts and updates for efficient batch execution, but callers
        // should not observe that internal grouping in the returned list.
        for (final T entity : entities) {
            final T dbEntity = dbIdEntityMap.get(keysExtractor.apply(entity));
            result.add(dbEntity == null ? entity : dbEntity);
        }

        return result;
    }

}
