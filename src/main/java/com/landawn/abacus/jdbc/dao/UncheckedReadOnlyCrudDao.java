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

/**
 * A read-only CRUD DAO interface that provides only query operations without any insert, update, or delete capabilities.
 * This interface is useful for creating DAOs that should only have read access to the database,
 * ensuring data safety by throwing {@link UnsupportedOperationException} on any modification attempt.
 *
 * <p><b>Unchecked Exception Handling:</b></p>
 * <p>This is an "unchecked" DAO variant. Query methods throw {@link UncheckedSQLException} instead of checked
 * {@link java.sql.SQLException}, making it easier to work with in functional programming contexts, lambda
 * expressions, and stream operations without requiring explicit exception handling.</p>
 *
 * <p>All write operations (insert, update, delete, and their batch variants) are disabled and throw
 * {@link UnsupportedOperationException}.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserReadOnlyDao extends UncheckedReadOnlyCrudDao<User, Long, UserReadOnlyDao> {
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
 * @param <ID> the type of the entity's primary key
 * @param <TD> the concrete DAO type itself (self-referencing generic for fluent method chaining)
 * @see UncheckedReadOnlyDao
 * @see UncheckedNoUpdateCrudDao
 * @see ReadOnlyCrudDao
 */
@Beta
public interface UncheckedReadOnlyCrudDao<T, ID, TD extends UncheckedReadOnlyCrudDao<T, ID, TD>>
        extends UncheckedReadOnlyDao<T, TD>, UncheckedNoUpdateCrudDao<T, ID, TD>, ReadOnlyCrudDao<T, ID, TD> {

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Inserting is not permitted in a read-only DAO.
     *
     * @param entityToInsert the entity to insert
     * @return never returns normally
     * @throws UnsupportedOperationException always, since inserts are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyCrudDao}. All modifications are prohibited.
     */
    @Deprecated
    @Override
    default ID insert(final T entityToInsert) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("This operation is not supported in a read-only DAO.");
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Inserting is not permitted in a read-only DAO.
     *
     * @param entityToInsert the entity to insert
     * @param propNamesToInsert the property names to include in the {@code INSERT} statement
     * @return never returns normally
     * @throws UnsupportedOperationException always, since inserts are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyCrudDao}. All modifications are prohibited.
     */
    @Deprecated
    @Override
    default ID insert(final T entityToInsert, final Collection<String> propNamesToInsert) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("This operation is not supported in a read-only DAO.");
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Inserting is not permitted in a read-only DAO.
     *
     * @param namedInsertSql the named parameter SQL insert statement
     * @param entityToSave the entity whose properties are bound to the named parameters
     * @return never returns normally
     * @throws UnsupportedOperationException always, since inserts are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyCrudDao}. All modifications are prohibited.
     */
    @Deprecated
    @Override
    default ID insert(final String namedInsertSql, final T entityToSave) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("This operation is not supported in a read-only DAO.");
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Inserting is not permitted in a read-only DAO.
     *
     * @param entities the collection of entities to insert
     * @return never returns normally
     * @throws UnsupportedOperationException always, since inserts are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyCrudDao}. All modifications are prohibited.
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("This operation is not supported in a read-only DAO.");
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Inserting is not permitted in a read-only DAO.
     *
     * @param entities the collection of entities to insert
     * @param batchSize the number of entities to process per batch
     * @return never returns normally
     * @throws UnsupportedOperationException always, since inserts are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyCrudDao}. All modifications are prohibited.
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("This operation is not supported in a read-only DAO.");
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Inserting is not permitted in a read-only DAO.
     *
     * @param entities the collection of entities to insert
     * @param propNamesToInsert the property names to include in the {@code INSERT} statement
     * @return never returns normally
     * @throws UnsupportedOperationException always, since inserts are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyCrudDao}. All modifications are prohibited.
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("This operation is not supported in a read-only DAO.");
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Inserting is not permitted in a read-only DAO.
     *
     * @param entities the collection of entities to insert
     * @param propNamesToInsert the property names to include in the {@code INSERT} statement
     * @param batchSize the number of entities to process per batch
     * @return never returns normally
     * @throws UnsupportedOperationException always, since inserts are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyCrudDao}. All modifications are prohibited.
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert, final int batchSize)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException("This operation is not supported in a read-only DAO.");
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Inserting is not permitted in a read-only DAO.
     *
     * @param namedInsertSql the named parameter SQL insert statement
     * @param entities the collection of entities whose properties are bound to the named parameters
     * @return never returns normally
     * @throws UnsupportedOperationException always, since inserts are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyCrudDao}. All modifications are prohibited.
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final String namedInsertSql, final Collection<? extends T> entities) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("This operation is not supported in a read-only DAO.");
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Inserting is not permitted in a read-only DAO.
     *
     * @param namedInsertSql the named parameter SQL insert statement
     * @param entities the collection of entities whose properties are bound to the named parameters
     * @param batchSize the number of entities to process per batch
     * @return never returns normally
     * @throws UnsupportedOperationException always, since inserts are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyCrudDao}. All modifications are prohibited.
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final String namedInsertSql, final Collection<? extends T> entities, final int batchSize)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException("This operation is not supported in a read-only DAO.");
    }
}
