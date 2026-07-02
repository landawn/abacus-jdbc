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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.DuplicateResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
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
import com.landawn.abacus.util.stream.Stream;

/**
 * The UncheckedCrudDao interface provides comprehensive CRUD (Create, Read, Update, Delete) operations
 * with unchecked exceptions. It is the unchecked counterpart of {@link CrudDao}: it extends
 * {@link UncheckedDao} (the unchecked base DAO) and {@link CrudDao}, and re-declares the id-based
 * operations so that they throw the unchecked {@link UncheckedSQLException} instead of the checked
 * {@link java.sql.SQLException}.
 *
 * <p>Because every database operation declared here throws {@link UncheckedSQLException} (a
 * {@link RuntimeException}) rather than a checked exception, this interface is easier to use in
 * functional programming contexts (lambdas, streams) and reduces boilerplate exception handling.</p>
 *
 * <p><b>ID semantics:</b> the entity class must declare one or more {@code @Id} properties. A single id
 * property maps directly to the {@code <ID>} type (for example {@code Long} or {@code String}), whereas a
 * composite (multi-column) key is represented by an {@link EntityId}. Insert operations write a
 * database-generated key back into the entity's id property where applicable, and {@code by-id} lookups
 * treat the supplied id as a primary-key match.</p>
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
 * Optional<User> found = userDao.get(id);
 * userDao.update("email", "john@example.com", id);
 * userDao.deleteById(id);
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <ID> the ID type of the entity (e.g. {@code Long}, {@code String}, {@code EntityId})
 * @param <TD> the self-type of the DAO for method chaining
 * @see JdbcUtil#prepareQuery(javax.sql.DataSource, String)
 * @see JdbcUtil#prepareNamedQuery(javax.sql.DataSource, String)
 * @see JdbcUtil#beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)
 * @see UncheckedDao
 * @see CrudDao
 * @see com.landawn.abacus.query.Filters
 */
@SuppressWarnings("resource")
@Beta
public non-sealed interface UncheckedCrudDao<T, ID, TD extends UncheckedCrudDao<T, ID, TD>> extends UncheckedCrudReadOps<T, ID, TD>,
        UncheckedCrudInsertOps<T, ID, TD>, UncheckedCrudUpdateOps<T, ID, TD>, UncheckedCrudDeleteOps<T, ID, TD>, UncheckedDao<T, TD>, CrudDao<T, ID, TD> {
    /**
     * Performs an upsert operation: inserts the entity if it doesn't exist based on ID fields, otherwise updates the existing entity.
     * The entity must have ID field(s) defined.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setId(123L);
     * user.setEmail("john@example.com");
     * user.setLastSeen(new Date());
     *
     * User result = userDao.upsert(user);
     * // Result will be either the newly inserted or updated user
     * }</pre>
     *
     * @param entity the entity to insert or update
     * @return the inserted or updated entity
     * @throws UncheckedSQLException if a database access error occurs
     * @throws IllegalArgumentException if {@code entity} is {@code null}
     * @throws DuplicateResultException if more than one record matches the entity's ID property(ies)
     */
    @Override
    default T upsert(final T entity) throws UncheckedSQLException {
        N.checkArgNotNull(entity, cs.entity);

        final Class<?> cls = entity.getClass();
        final List<String> idPropNameList = QueryUtil.getIdPropNames(cls); // guaranteed non-empty for a CRUD entity class.

        return upsert(entity, idPropNameList);
    }

    /**
     * Performs an upsert operation: inserts the entity if it doesn't exist based on the specified unique properties, otherwise updates the existing entity.
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
     * @param entity the entity to insert or update
     * @param uniquePropNamesForQuery the property names that uniquely identify the record
     * @return the inserted or updated entity
     * @throws IllegalArgumentException if {@code entity} is {@code null} or {@code uniquePropNamesForQuery} is {@code null} or empty
     * @throws UncheckedSQLException if a database access error occurs
     * @throws DuplicateResultException if more than one record matches
     */
    @Override
    default T upsert(final T entity, final List<String> uniquePropNamesForQuery) throws UncheckedSQLException {
        N.checkArgNotNull(entity, cs.entity);
        N.checkArgNotEmpty(uniquePropNamesForQuery, cs.uniquePropNamesForQuery);

        final Condition cond = Filters.allEqual(entity, uniquePropNamesForQuery);

        return upsert(entity, cond);
    }

    /**
     * Executes an upsert operation based on the specified condition.
     * If no record matches the condition, inserts the entity.
     * Otherwise, copies the non-id properties from the entity into the existing record and updates it.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setEmail("john@example.com");
     * user.setDepartment("IT");
     * user.setLastUpdated(new Date());
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
     * @param entity the entity to insert or update
     * @param cond the condition to check for existing record
     * @return the inserted or updated entity
     * @throws IllegalArgumentException if {@code entity} or {@code cond} is {@code null}
     * @throws UncheckedSQLException if a database access error occurs
     * @throws DuplicateResultException if more than one record matches the specified condition
     */
    @Override
    default T upsert(final T entity, final Condition cond) throws UncheckedSQLException {
        N.checkArgNotNull(entity, cs.entity);
        N.checkArgNotNull(cond, cs.cond);

        final T dbEntity = findOnlyOne(cond).orElseNull();

        if (dbEntity == null) {
            insert(entity);
            return entity;
        } else {
            final Class<?> cls = entity.getClass();
            final List<String> idPropNameList = QueryUtil.getIdPropNames(cls);

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
     * Batch upserts multiple entities using the default batch size.
     * Entities are inserted if they don't exist (based on ID), otherwise updated.
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
     * @return a list of saved entities (both inserted and updated); an empty list if {@code entities} is {@code null} or empty
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default List<T> batchUpsert(final Collection<? extends T> entities) throws UncheckedSQLException {
        return batchUpsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch upserts multiple entities using the specified batch size.
     * Entities are inserted if they don't exist (based on ID), otherwise updated.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> largeUserList = getThousandsOfUsers();
     * // Upsert in batches of 500
     * List<User> results = userDao.batchUpsert(largeUserList, 500);
     * }</pre>
     *
     * @param entities the collection of entities to upsert
     * @param batchSize the size of each batch
     * @return a list of saved entities (both inserted and updated); an empty list if {@code entities} is {@code null} or empty
     * @throws IllegalArgumentException if {@code batchSize} is not positive
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default List<T> batchUpsert(final Collection<? extends T> entities, final int batchSize) throws UncheckedSQLException {
        N.checkArgPositive(batchSize, cs.batchSize);

        if (N.isEmpty(entities)) {
            return new ArrayList<>();
        }

        final T entity = N.firstOrNullIfEmpty(entities);
        final Class<?> cls = entity.getClass();
        final List<String> idPropNameList = QueryUtil.getIdPropNames(cls); // guaranteed non-empty for a CRUD entity class.

        return batchUpsert(entities, idPropNameList, batchSize);
    }

    /**
     * Batch upserts multiple entities based on the specified unique properties.
     * Uses the default batch size.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getUsersFromImport();
     * // Upsert based on email being unique
     * List<User> results = userDao.batchUpsert(users, Arrays.asList("email"));
     * }</pre>
     *
     * @param entities the collection of entities to upsert
     * @param uniquePropNamesForQuery the property names that uniquely identify each record
     * @return a list of saved entities (both inserted and updated); an empty list if {@code entities} is {@code null} or empty
     * @throws IllegalArgumentException if {@code uniquePropNamesForQuery} is {@code null} or empty
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default List<T> batchUpsert(final Collection<? extends T> entities, final List<String> uniquePropNamesForQuery) throws UncheckedSQLException {
        return batchUpsert(entities, uniquePropNamesForQuery, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch upserts multiple entities based on the specified unique properties using the specified batch size.
     * This method efficiently handles large collections by:
     * 1. Querying existing records in batches
     * 2. Separating entities into insert and update groups
     * 3. Performing batch insert and batch update operations
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
     * @param uniquePropNamesForQuery the property names that uniquely identify each record
     * @param batchSize the size of each batch
     * @return a list of saved entities (both inserted and updated); an empty list if {@code entities} is {@code null} or empty
     * @throws IllegalArgumentException if {@code batchSize} is not positive or {@code uniquePropNamesForQuery} is {@code null} or empty
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default List<T> batchUpsert(final Collection<? extends T> entities, final List<String> uniquePropNamesForQuery, final int batchSize)
            throws UncheckedSQLException {
        N.checkArgPositive(batchSize, cs.batchSize);
        N.checkArgNotEmpty(uniquePropNamesForQuery, cs.uniquePropNamesForQuery);

        if (N.isEmpty(entities)) {
            return new ArrayList<>();
        }

        final T first = N.firstOrNullIfEmpty(entities);
        final Class<?> cls = first.getClass();
        final BeanInfo entityInfo = ParserUtil.getBeanInfo(cls);

        final PropInfo uniquePropInfo = entityInfo.getPropInfo(uniquePropNamesForQuery.get(0));

        if (uniquePropInfo == null) {
            throw new IllegalArgumentException("No property found with name: '" + uniquePropNamesForQuery.get(0) + "' in class: " + cls.getName());
        }

        final List<PropInfo> uniquePropInfos = N.map(uniquePropNamesForQuery, entityInfo::getPropInfo);

        for (int i = 0; i < uniquePropInfos.size(); i++) {
            if (uniquePropInfos.get(i) == null) {
                throw new IllegalArgumentException("No property found with name: '" + uniquePropNamesForQuery.get(i) + "' in class: " + cls.getName());
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

        final com.landawn.abacus.util.function.Function<T, ?> keysExtractor = uniquePropNamesForQuery.size() == 1 ? singleKeyExtractor : entityIdExtractor;

        final List<T> dbEntities = uniquePropNamesForQuery.size() == 1
                ? Stream.of(entities).split(batchSize).flatmap(it -> list(Filters.in(uniquePropNamesForQuery.get(0), N.map(it, singleKeyExtractor)))).toList()
                : Stream.of(entities).split(batchSize).flatmap(it -> list(Filters.idToCond(N.map(it, entityIdExtractor)))).toList();

        final Map<Object, T> dbIdEntityMap = Stream.of(dbEntities).toMap(keysExtractor, Fn.identity(), Fn.throwingMerger());
        final Map<Boolean, List<T>> map = Stream.of(entities).groupTo(it -> dbIdEntityMap.containsKey(keysExtractor.apply(it)), Fn.identity());
        final List<T> entitiesToUpdate = map.get(true);
        final List<T> entitiesToInsert = map.get(false);

        final List<T> result = new ArrayList<>(entities.size());
        final SqlTransaction tran = (N.notEmpty(entitiesToInsert) && N.notEmpty(entitiesToUpdate))
                || (N.notEmpty(entitiesToInsert) && entitiesToInsert.size() > batchSize)
                || (N.notEmpty(entitiesToUpdate) && entitiesToUpdate.size() > batchSize) ? JdbcUtil.beginTransaction(dataSource()) : null;

        try {
            if (N.notEmpty(entitiesToInsert)) {
                batchInsert(entitiesToInsert, batchSize);
                result.addAll(entitiesToInsert);
            }

            if (N.notEmpty(entitiesToUpdate)) {
                final Set<String> ignoredPropNames = N.newHashSet(uniquePropNamesForQuery);

                final List<String> idPropNameList = QueryUtil.getIdPropNames(cls);

                if (N.notEmpty(idPropNameList)) {
                    ignoredPropNames.addAll(idPropNameList);
                }

                final List<T> dbEntitiesToUpdate = Stream.of(entitiesToUpdate)
                        .map(it -> Beans.mergeInto(it, dbIdEntityMap.get(keysExtractor.apply(it)), false, ignoredPropNames))
                        .toList();

                batchUpdate(dbEntitiesToUpdate, batchSize);

                result.addAll(dbEntitiesToUpdate);
            }

            if (tran != null) {
                tran.commit();
            }
        } finally {
            if (tran != null) {
                tran.rollbackIfNotCommitted();
            }
        }

        return result;
    }

}
