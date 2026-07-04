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
import com.landawn.abacus.exception.DuplicateResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.cs;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.query.QueryUtil;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.util.Beans;
import com.landawn.abacus.util.N;

/**
 * Interface for an unchecked Data Access Object (DAO) that extends the base {@link Dao} interface.
 * Its methods throw {@link UncheckedSQLException} instead of {@link java.sql.SQLException}, providing a more
 * convenient API for developers who prefer unchecked exceptions.
 *
 * <p>Through its {@code Unchecked*Ops} super-interfaces it redeclares the save operations and the
 * condition-based query, update, and delete operations so callers do not need to handle checked
 * exceptions for those methods; this interface itself redeclares the {@code upsert} operations.
 * Inherited methods that are not redeclared keep the checked-exception contract from {@link Dao}.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * UncheckedDao<User, UserDao> userDao = ...;
 * User user = new User("John", "Doe");
 * userDao.save(user);
 *
 * Optional<User> foundUser = userDao.findFirst(Filters.eq("firstName", "John"));
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the self-type of the DAO for method chaining
 * @see com.landawn.abacus.jdbc.dao.Dao
 * @see com.landawn.abacus.query.Filters
 */
@Beta
public non-sealed interface UncheckedDao<T, TD extends UncheckedDao<T, TD>>
        extends UncheckedReadOps<T, TD>, UncheckedInsertOps<T, TD>, UncheckedUpdateOps<T, TD>, UncheckedDeleteOps<T, TD>, Dao<T, TD> {
    /**
     * Executes an upsert operation: inserts the entity if no record matches the unique properties,
     * otherwise updates the existing record.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User("john@example.com", "John", "Doe");
     * user.setLastLogin(new Date());
     *
     * // Upsert based on email being unique
     * User result = userDao.upsert(user, Arrays.asList("email"));
     * }</pre>
     *
     * @param entity the entity to insert or update
     * @param uniquePropNamesForQuery the list of property names that uniquely identify the record
     * @return the saved entity (the input entity if it was newly inserted; otherwise the merged existing entity that was updated)
     * @throws IllegalArgumentException if {@code entity} is {@code null} or {@code uniquePropNamesForQuery} is {@code null} or empty
     * @throws UncheckedSQLException if a database access error occurs
     * @throws DuplicateResultException if more than one record matches
     * @see #upsert(Object, Condition)
     */
    @Override
    default T upsert(final T entity, final Collection<String> uniquePropNamesForQuery) throws UncheckedSQLException {
        N.checkArgNotNull(entity, cs.entity);
        N.checkArgNotEmpty(uniquePropNamesForQuery, cs.uniquePropNamesForQuery);

        final Condition cond = Filters.allEqual(entity, uniquePropNamesForQuery);

        return upsert(entity, cond);
    }

    /**
     * Executes an upsert operation: inserts the entity if no record matches the condition,
     * otherwise updates the existing record.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setEmail("john@example.com");
     * user.setScore(100);
     *
     * // Custom condition for upsert
     * User result = userDao.upsert(user, Filters.and(
     *     Filters.eq("email", user.getEmail()),
     *     Filters.eq("accountType", "PREMIUM")
     * ));
     * }</pre>
     *
     * @param entity the entity to insert or update
     * @param cond the condition to verify if the record exists
     * @return the saved entity (the input entity if it was newly inserted; otherwise the merged existing entity that was updated)
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
            save(entity);
            return entity;
        } else {
            final Class<?> cls = entity.getClass();
            final List<String> idPropNameList = QueryUtil.getIdPropNames(cls);

            if (N.isEmpty(idPropNameList)) {
                Beans.mergeInto(entity, dbEntity);
                update(dbEntity, cond);
            } else {
                Beans.mergeInto(entity, dbEntity, false, N.newHashSet(idPropNameList));
                final Condition idCond = Filters.allEqual(dbEntity, idPropNameList);
                update(dbEntity, idCond);
            }

            return dbEntity;
        }
    }

}
