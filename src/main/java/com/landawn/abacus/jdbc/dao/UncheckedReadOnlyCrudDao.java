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
 * <p>This interface throws {@link UncheckedSQLException} instead of checked {@link java.sql.SQLException},
 * making it easier to work with in functional programming contexts.</p>
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
 * Optional<User> user = userDao.get(userId);  // OK
 * List<User> users = userDao.list(CF.eq("status", "ACTIVE"));  // OK
 * // userDao.insert(user);  // Throws UnsupportedOperationException
 * // userDao.update(user);  // Throws UnsupportedOperationException
 * // userDao.deleteById(id);  // Throws UnsupportedOperationException
 * }</pre>
 *
 * @param <T> the entity type
 * @param <ID> the ID type
 * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
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
     * @throws UncheckedSQLException if a database access error occurs (will not be thrown as operation fails earlier)
     * @throws UnsupportedOperationException always thrown as insert operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Deprecated
    @Override
    default ID insert(final T entityToInsert) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a read-only DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entityToInsert the entity to insert (operation will fail)
     * @param propNamesToInsert the properties to insert (operation will fail)
     * @return never returns normally
     * @throws UncheckedSQLException if a database access error occurs (will not be thrown as operation fails earlier)
     * @throws UnsupportedOperationException always thrown as insert operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Deprecated
    @Override
    default ID insert(final T entityToInsert, final Collection<String> propNamesToInsert) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a read-only DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param namedInsertSQL the named insert SQL (operation will fail)
     * @param entityToSave the entity to save (operation will fail)
     * @return never returns normally
     * @throws UncheckedSQLException if a database access error occurs (will not be thrown as operation fails earlier)
     * @throws UnsupportedOperationException always thrown as insert operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Deprecated
    @Override
    default ID insert(final String namedInsertSQL, final T entityToSave) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entities the entities to insert (ignored)
     * @return never returns, always throws exception
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as insert operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entities the entities to insert (ignored)
     * @param batchSize the batch size (ignored)
     * @return never returns, always throws exception
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as insert operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities, final int batchSize) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entities the entities to insert (ignored)
     * @param propNamesToInsert the properties to insert (ignored)
     * @return never returns, always throws exception
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as insert operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert)
            throws UncheckedSQLException, UnsupportedOperationException {
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
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as insert operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert, final int batchSize)
            throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param namedInsertSQL the named insert SQL (ignored)
     * @param entities the entities to insert (ignored)
     * @return never returns, always throws exception
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as insert operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities)
            throws UncheckedSQLException, UnsupportedOperationException {
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
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as insert operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities, final int batchSize)
            throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}