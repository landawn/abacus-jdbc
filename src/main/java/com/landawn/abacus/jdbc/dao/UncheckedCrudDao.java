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
     * Performs an upsert operation, matching existing records by the entity's ID property(ies):
     * inserts {@code entity} if no record with the same ID exists; otherwise updates the existing record
     * with the values from {@code entity}.
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
     * @return the saved entity (either newly inserted or updated)
     * @throws IllegalArgumentException if {@code entity} is {@code null}
     * @throws UncheckedSQLException if a database access error occurs
     * @throws DuplicateResultException if more than one record matches the entity's ID property(ies)
     */
    @Override
    default T upsert(final T entity) throws UncheckedSQLException {
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
     * @param matchPropNames the property names that uniquely identify each entity (must not be empty)
     * @return the saved entity (the input entity if it was newly inserted; otherwise the merged existing entity that was updated)
     * @throws IllegalArgumentException if {@code entity} is {@code null} or {@code matchPropNames} is {@code null} or empty
     * @throws UncheckedSQLException if a database access error occurs
     * @throws DuplicateResultException if more than one record matches
     */
    @Override
    default T upsert(final T entity, final Collection<String> matchPropNames) throws UncheckedSQLException {
        N.checkArgNotNull(entity, cs.entity);
        N.checkArgNotEmpty(matchPropNames, cs.matchPropNames);

        final Condition cond = Filters.allEqual(entity, matchPropNames);

        return upsert(entity, cond);
    }

    /**
     * Performs an upsert operation: inserts {@code entity} if no record matches the specified
     * condition; otherwise copies non-id properties from {@code entity} into the existing record
     * (loaded via {@link #findOnlyOne(Condition)}) and updates it.
     * This allows for upsert logic based on any criteria, not just ID fields.
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
     *         or the loaded database entity (with non-id properties copied from {@code entity}) when an existing record was updated
     * @throws IllegalArgumentException if {@code entity} or {@code cond} is {@code null}
     * @throws UncheckedSQLException if a database access error occurs
     * @throws DuplicateResultException if more than one record matches the specified condition
     * @see Filters
     */
    @Override
    default T upsert(final T entity, final Condition cond) throws UncheckedSQLException {
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
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default List<T> batchUpsert(final Collection<? extends T> entities) throws UncheckedSQLException {
        return batchUpsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch upsert of multiple entities with a specified batch size,
     * matching existing records by ID fields.
     * Large collections will be processed in batches of the specified size.
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
     * List<User> users = getUsersFromImport();
     * // Upsert based on email being unique
     * List<User> results = userDao.batchUpsert(users, Arrays.asList("email"));
     * }</pre>
     *
     * @param entities the collection of entities to upsert
     * @param matchPropNames the property names that uniquely identify each entity (must not be empty)
     * @return a list of saved entities (both inserted and updated), in the same iteration order as
     *         {@code entities}; an empty list if {@code entities} is {@code null} or empty
     * @throws IllegalArgumentException if {@code matchPropNames} is {@code null} or empty
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default List<T> batchUpsert(final Collection<? extends T> entities, final Collection<String> matchPropNames) throws UncheckedSQLException {
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
     * @throws IllegalArgumentException if {@code matchPropNames} is {@code null}/empty,
     *                                  if {@code batchSize} is not positive,
     *                                  or if any name in {@code matchPropNames} is not a property of the entity class
     * @throws IllegalStateException if more than one existing record matches one entity's unique key
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default List<T> batchUpsert(final Collection<? extends T> entities, final Collection<String> matchPropNames, final int batchSize)
            throws UncheckedSQLException {
        try {
            return CrudDao.super.batchUpsert(entities, matchPropNames, batchSize);
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

}
