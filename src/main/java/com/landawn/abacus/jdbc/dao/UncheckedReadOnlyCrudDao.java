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
import com.landawn.abacus.query.SQLBuilder;

/**
 * A read-only CRUD DAO interface that provides only query operations without any insert, update, or delete capabilities.
 * This interface is useful for creating DAOs that should only have read access to the database,
 * ensuring data safety by preventing any modifications at compile time.
 *
 * <p><b>Unchecked Exception Handling:</b></p>
 * <p>This is an "unchecked" DAO variant. All methods throw {@link UncheckedSQLException} instead of checked
 * {@link java.sql.SQLException}, making it easier to work with in functional programming contexts, lambda
 * expressions, and stream operations without requiring explicit exception handling.</p>
 *
 * <p>All write operations (insert, update, delete, and their batch variants) will throw
 * {@link UnsupportedOperationException}.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserReadOnlyDao extends UncheckedReadOnlyCrudDao<User, Long, SQLBuilder.PSC, UserReadOnlyDao> {
 *     // Only query methods available, no insert/update/delete
 * }
 *
 * UserReadOnlyDao userDao = JdbcUtil.createDao(UserReadOnlyDao.class, readOnlyDataSource);
 *
 * // Query operations work without checked exception handling:
 * Optional<User> user = userDao.get(userId);
 * List<User> users = userDao.list(Filters.eq("status", "ACTIVE"));
 * boolean exists = userDao.exists(Filters.eq("email", "test@example.com"));
 * long count = userDao.count(Filters.gt("age", 18));
 *
 * // Can be used in functional contexts without try-catch:
 * List<Long> userIds = Arrays.asList(1L, 2L, 3L);
 * List<User> usersFound = userIds.stream()
 *     .map(id -> userDao.get(id))
 *     .filter(Optional::isPresent)
 *     .map(Optional::get)
 *     .collect(Collectors.toList());
 *
 * // Write operations throw UnsupportedOperationException:
 * // userDao.insert(user);   // Throws UnsupportedOperationException
 * // userDao.update(user);   // Throws UnsupportedOperationException
 * // userDao.deleteById(id);   // Throws UnsupportedOperationException
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <ID> the ID type of the entity
 * @param <SB> the SQLBuilder type used to generate SQL scripts (must be one of SQLBuilder.PSC/PAC/PLC)
 * @param <TD> the self-type of the DAO for method chaining
 * @see UncheckedReadOnlyDao
 * @see UncheckedNoUpdateCrudDao
 */
@Beta
public interface UncheckedReadOnlyCrudDao<T, ID, SB extends SQLBuilder, TD extends UncheckedReadOnlyCrudDao<T, ID, SB, TD>>
        extends UncheckedReadOnlyDao<T, SB, TD>, UncheckedNoUpdateCrudDao<T, ID, SB, TD>, ReadOnlyCrudDao<T, ID, SB, TD> {

    /**
     * This operation is not supported in a read-only DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entityToInsert the entity to insert (operation will fail)
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as insert operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @Override
    default ID insert(final T entityToInsert) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a read-only DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entityToInsert the entity to insert (operation will fail)
     * @param propNamesToInsert the properties to insert (operation will fail)
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as insert operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @Override
    default ID insert(final T entityToInsert, final Collection<String> propNamesToInsert) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a read-only DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param namedInsertSQL the named insert SQL (operation will fail)
     * @param entityToSave the entity to save (operation will fail)
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as insert operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @Override
    default ID insert(final String namedInsertSQL, final T entityToSave) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entities the entities to insert (ignored)
     * @return never returns, always throws exception
     * @throws UnsupportedOperationException always thrown as insert operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entities the entities to insert (ignored)
     * @param batchSize the batch size (ignored)
     * @return never returns, always throws exception
     * @throws UnsupportedOperationException always thrown as insert operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entities the entities to insert (ignored)
     * @param propNamesToInsert the properties to insert (ignored)
     * @return never returns, always throws exception
     * @throws UnsupportedOperationException always thrown as insert operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entities the entities to insert (ignored)
     * @param propNamesToInsert the properties to insert (ignored)
     * @param batchSize the batch size (ignored)
     * @return never returns, always throws exception
     * @throws UnsupportedOperationException always thrown as insert operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert, final int batchSize)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param namedInsertSQL the named insert SQL (ignored)
     * @param entities the entities to insert (ignored)
     * @return never returns, always throws exception
     * @throws UnsupportedOperationException always thrown as insert operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param namedInsertSQL the named insert SQL (ignored)
     * @param entities the entities to insert (ignored)
     * @param batchSize the batch size (ignored)
     * @return never returns, always throws exception
     * @throws UnsupportedOperationException always thrown as insert operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities, final int batchSize)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}
