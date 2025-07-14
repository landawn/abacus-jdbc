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
import java.util.Map;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.query.SQLBuilder;

/**
 * A CRUD Data Access Object interface that disables update and delete operations while
 * allowing read and insert operations. This interface extends both {@link NoUpdateDao}
 * and {@link CrudDao}, effectively creating a DAO that can only read existing records
 * and insert new ones, but cannot modify or delete existing records.
 * 
 * <p>This pattern is particularly useful for:</p>
 * <ul>
 *   <li>Audit logs or event stores where records should be immutable</li>
 *   <li>Append-only data stores</li>
 *   <li>Historical data that should not be modified</li>
 *   <li>Enforcing data integrity by preventing updates at the DAO level</li>
 * </ul>
 * 
 * <p>All update, upsert, and delete operations will throw {@link UnsupportedOperationException}.
 * Read operations (find, exists, query) and insert operations remain functional.</p>
 * 
 * <p>This interface is marked as {@code @Beta}, indicating it may be subject to
 * incompatible changes, or even removal, in a future release.</p>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * // Define a DAO for immutable transaction records
 * public interface TransactionDao extends NoUpdateCrudDao<Transaction, String, SQLBuilder, TransactionDao> {
 *     // Custom read methods can be added
 *     List<Transaction> findByAccountId(String accountId);
 * }
 * 
 * // Usage:
 * Transaction txn = new Transaction();
 * String id = transactionDao.insert(txn); // Works
 * Transaction retrieved = transactionDao.findById(id); // Works
 * transactionDao.update(txn); // Throws UnsupportedOperationException
 * transactionDao.deleteById(id); // Throws UnsupportedOperationException
 * }</pre>
 *
 * @param <T> the type of the entity
 * @param <ID> the type of the entity's identifier
 * @param <SB> the type of SQLBuilder used for query construction
 * @param <TD> the type of the DAO implementation (self-referencing type parameter)
 * @see NoUpdateDao
 * @see CrudDao
 * @see com.landawn.abacus.condition.ConditionFactory
 * @see com.landawn.abacus.condition.ConditionFactory.CF
 */
@SuppressWarnings("RedundantThrows")
@Beta
public interface NoUpdateCrudDao<T, ID, SB extends SQLBuilder, TD extends NoUpdateCrudDao<T, ID, SB, TD>>
        extends NoUpdateDao<T, SB, TD>, CrudDao<T, ID, SB, TD> {

    /**
     * This operation is not supported for no-update DAOs.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entityToUpdate the entity containing the new values
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as updates are not supported
     * @deprecated This operation is not supported for no-update DAOs
     */
    @Deprecated
    @Override
    default int update(final T entityToUpdate) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported for no-update DAOs.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entityToUpdate the entity containing the new values
     * @param propNamesToUpdate collection of property names to update
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as updates are not supported
     * @deprecated This operation is not supported for no-update DAOs
     */
    @Deprecated
    @Override
    default int update(final T entityToUpdate, final Collection<String> propNamesToUpdate) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported for no-update DAOs.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param propName the name of the property to update
     * @param propValue the new value for the property
     * @param id the ID of the entity to update
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as updates are not supported
     * @deprecated This operation is not supported for no-update DAOs
     */
    @Override
    @Deprecated
    default int update(final String propName, final Object propValue, final ID id) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported for no-update DAOs.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param updateProps a map of property names to their new values
     * @param id the ID of the entity to update
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as updates are not supported
     * @deprecated This operation is not supported for no-update DAOs
     */
    @Deprecated
    @Override
    default int update(final Map<String, Object> updateProps, final ID id) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported for no-update DAOs.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities collection of entities to update
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as updates are not supported
     * @deprecated This operation is not supported for no-update DAOs
     */
    @Deprecated
    @Override
    default int batchUpdate(final Collection<? extends T> entities) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported for no-update DAOs.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities collection of entities to update
     * @param batchSize the batch size for batch execution
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as updates are not supported
     * @deprecated This operation is not supported for no-update DAOs
     */
    @Deprecated
    @Override
    default int batchUpdate(final Collection<? extends T> entities, final int batchSize) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported for no-update DAOs.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities collection of entities to update
     * @param propNamesToUpdate collection of property names to update
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as updates are not supported
     * @deprecated This operation is not supported for no-update DAOs
     */
    @Deprecated
    @Override
    default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported for no-update DAOs.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities collection of entities to update
     * @param propNamesToUpdate collection of property names to update
     * @param batchSize the batch size for batch execution
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as updates are not supported
     * @deprecated This operation is not supported for no-update DAOs
     */
    @Deprecated
    @Override
    default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate, final int batchSize)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported for no-update DAOs.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity to upsert
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as upserts are not supported
     * @deprecated This operation is not supported for no-update DAOs
     */
    @Deprecated
    @Override
    default T upsert(final T entity) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported for no-update DAOs.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity to upsert
     * @param cond condition to verify if the record exists
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as upserts are not supported
     * @deprecated This operation is not supported for no-update DAOs
     */
    @Deprecated
    @Override
    default T upsert(final T entity, final Condition cond) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported for no-update DAOs.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity to upsert
     * @param uniquePropNamesForQuery property names that uniquely identify the entity
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as upserts are not supported
     * @deprecated This operation is not supported for no-update DAOs
     */
    @Deprecated
    @Override
    default T upsert(final T entity, final List<String> uniquePropNamesForQuery) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported for no-update DAOs.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities collection of entities to upsert
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as upserts are not supported
     * @deprecated This operation is not supported for no-update DAOs
     */
    @Override
    @Deprecated
    default List<T> batchUpsert(final Collection<? extends T> entities) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported for no-update DAOs.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities collection of entities to upsert
     * @param batchSize the batch size for batch execution
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as upserts are not supported
     * @deprecated This operation is not supported for no-update DAOs
     */
    @Override
    @Deprecated
    default List<T> batchUpsert(final Collection<? extends T> entities, final int batchSize) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported for no-update DAOs.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities collection of entities to upsert
     * @param uniquePropNamesForQuery property names that uniquely identify each entity
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as upserts are not supported
     * @deprecated This operation is not supported for no-update DAOs
     */
    @Override
    @Deprecated
    default List<T> batchUpsert(final Collection<? extends T> entities, final List<String> uniquePropNamesForQuery)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported for no-update DAOs.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities collection of entities to upsert
     * @param uniquePropNamesForQuery property names that uniquely identify each entity
     * @param batchSize the batch size for batch execution
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as upserts are not supported
     * @deprecated This operation is not supported for no-update DAOs
     */
    @Override
    @Deprecated
    default List<T> batchUpsert(final Collection<? extends T> entities, final List<String> uniquePropNamesForQuery, final int batchSize)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported for no-update DAOs.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity to delete
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as deletes are not supported
     * @deprecated This operation is not supported for no-update DAOs
     */
    @Deprecated
    @Override
    default int delete(final T entity) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported for no-update DAOs.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param id the ID of the entity to delete
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as deletes are not supported
     * @deprecated This operation is not supported for no-update DAOs
     */
    @Deprecated
    @Override
    default int deleteById(final ID id) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported for no-update DAOs.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities collection of entities to delete
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as deletes are not supported
     * @deprecated This operation is not supported for no-update DAOs
     */
    @Deprecated
    @Override
    default int batchDelete(final Collection<? extends T> entities) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported for no-update DAOs.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities collection of entities to delete
     * @param batchSize the batch size for batch execution
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as deletes are not supported
     * @deprecated This operation is not supported for no-update DAOs
     */
    @Deprecated
    @Override
    default int batchDelete(final Collection<? extends T> entities, final int batchSize) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported for no-update DAOs.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param ids collection of IDs of entities to delete
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as deletes are not supported
     * @deprecated This operation is not supported for no-update DAOs
     */
    @Deprecated
    @Override
    default int batchDeleteByIds(final Collection<? extends ID> ids) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported for no-update DAOs.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param ids collection of IDs of entities to delete
     * @param batchSize the batch size for batch execution
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as deletes are not supported
     * @deprecated This operation is not supported for no-update DAOs
     */
    @Deprecated
    @Override
    default int batchDeleteByIds(final Collection<? extends ID> ids, final int batchSize) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}