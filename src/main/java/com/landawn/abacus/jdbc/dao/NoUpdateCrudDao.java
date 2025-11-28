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
import java.util.Map;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.query.SQLBuilder;
import com.landawn.abacus.query.condition.Condition;

/**
 * A CRUD Data Access Object interface that disables update and delete operations while allowing read and insert operations.
 * This interface extends both {@link NoUpdateDao} and {@link CrudDao}, effectively creating a DAO that can only
 * read existing records and insert new ones, but cannot modify or delete existing records.
 *
 * <p>This pattern is particularly useful for:</p>
 * <ul>
 *   <li>Audit logs or event stores where records should be immutable</li>
 *   <li>Append-only data stores and event sourcing patterns</li>
 *   <li>Historical data that should not be modified</li>
 *   <li>Enforcing data integrity by preventing updates at the DAO level</li>
 * </ul>
 *
 * <p>All update, upsert, and delete operations will throw {@link UnsupportedOperationException}.
 * Read operations (find, exists, query) and insert operations remain functional.</p>
 *
 * <p><b>Supported Operations:</b></p>
 * <ul>
 *   <li><b>Read by ID:</b> {@code get(ID)}, {@code gett(ID)}, {@code queryForBoolean/Int/Long/String(propName, ID)}</li>
 *   <li><b>Query Operations:</b> {@code list(Condition)}, {@code findFirst(Condition)}, {@code findOnlyOne(Condition)}</li>
 *   <li><b>Aggregate Operations:</b> {@code count(Condition)}, {@code exists(Condition)}</li>
 *   <li><b>Insert Operations:</b> {@code insert(entity)}, {@code batchInsert(entities)}</li>
 *   <li><b>Query Preparation:</b> {@code prepareQuery} and {@code prepareNamedQuery} for SELECT and INSERT</li>
 * </ul>
 *
 * <p>This interface is marked as {@code @Beta}, indicating it may be subject to
 * incompatible changes, or even removal, in a future release.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Define a DAO for immutable transaction records
 * public interface TransactionDao extends NoUpdateCrudDao<Transaction, String, SQLBuilder.PSC, TransactionDao> {
 *     // Custom read methods can be added
 * }
 *
 * TransactionDao transactionDao = JdbcUtil.createDao(TransactionDao.class, dataSource);
 *
 * // Supported operations - all work fine:
 *
 * // Insert operations
 * Transaction txn = new Transaction("TXN001", customerId, amount);
 * String txnId = transactionDao.insert(txn);  // Returns generated ID
 *
 * List<Transaction> newTransactions = createTransactions();
 * List<String> ids = transactionDao.batchInsert(newTransactions);  // Batch insert
 *
 * // Read by ID operations
 * Optional<Transaction> transaction = transactionDao.get(txnId);  // Returns Optional
 * Transaction txn2 = transactionDao.gett(txnId);  // Returns null if not found
 *
 * // Query single property by ID
 * Nullable<String> status = transactionDao.queryForString("status", txnId);
 * OptionalDouble amount = transactionDao.queryForDouble("amount", txnId);
 *
 * // Query operations
 * List<Transaction> txns = transactionDao.list(Filters.eq("customerId", customerId));
 * Optional<Transaction> firstTxn = transactionDao.findFirst(Filters.gt("amount", 1000.0));
 * Optional<Transaction> uniqueTxn = transactionDao.findOnlyOne(Filters.eq("referenceNumber", "REF123"));
 *
 * // Count and existence checks
 * int count = transactionDao.count(Filters.eq("status", "PENDING"));
 * boolean exists = transactionDao.exists(Filters.eq("id", txnId));
 *
 * // Prepare custom SELECT queries
 * List<Transaction> results = transactionDao.prepareQuery(
 *         "SELECT * FROM transactions WHERE amount > ? AND status = ?")
 *         .setDouble(1, 500.0)
 *         .setString(2, "COMPLETED")
 *         .list(Transaction.class);
 *
 * // Unsupported operations - all throw UnsupportedOperationException:
 * transactionDao.update(txn);  // Throws exception
 * transactionDao.update("status", "CANCELLED", txnId);  // Throws exception
 * transactionDao.deleteById(txnId);  // Throws exception
 * transactionDao.delete(txn);  // Throws exception
 * transactionDao.batchDelete(transactions);  // Throws exception
 * transactionDao.upsert(txn);  // Throws exception
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <ID> the ID type of the entity
 * @param <SB> the SQLBuilder type used for query construction
 * @param <TD> the DAO implementation type (self-referencing for method chaining)
 * @see NoUpdateDao
 * @see CrudDao
 * @see com.landawn.abacus.query.Filters
 */
@SuppressWarnings("RedundantThrows")
@Beta
public interface NoUpdateCrudDao<T, ID, SB extends SQLBuilder, TD extends NoUpdateCrudDao<T, ID, SB, TD>>
        extends NoUpdateDao<T, SB, TD>, CrudDao<T, ID, SB, TD> {

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entityToUpdate the entity with updated values
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as updates are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default int update(final T entityToUpdate) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entityToUpdate the entity containing the values to update
     * @param propNamesToUpdate the property names to update. If {@code null} or empty, all properties will be updated
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as updates are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default int update(final T entityToUpdate, final Collection<String> propNamesToUpdate) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param propName the property name to update
     * @param propValue the new value for the property
     * @param id the entity ID to update
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as updates are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Override
    @Deprecated
    default int update(final String propName, final Object propValue, final ID id) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param updateProps a map of property names to their new values
     * @param id the entity ID to update
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as updates are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default int update(final Map<String, Object> updateProps, final ID id) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities to update
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as updates are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default int batchUpdate(final Collection<? extends T> entities) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities to update
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as updates are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default int batchUpdate(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities to update
     * @param propNamesToUpdate the property names to update for all entities
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as updates are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities to update
     * @param propNamesToUpdate the property names to update for all entities
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as updates are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate, final int batchSize)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity to upsert
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as upserts are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default T upsert(final T entity) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity to insert or update
     * @param cond the condition to check if the entity exists
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as upserts are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default T upsert(final T entity, final Condition cond) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity to insert or update
     * @param uniquePropNamesForQuery the property names that uniquely identify the entity
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as upserts are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default T upsert(final T entity, final List<String> uniquePropNamesForQuery) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities to upsert
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as upserts are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Override
    @Deprecated
    default List<T> batchUpsert(final Collection<? extends T> entities) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities to upsert
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as upserts are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Override
    @Deprecated
    default List<T> batchUpsert(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities to upsert
     * @param uniquePropNamesForQuery the property names that uniquely identify each entity
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as upserts are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Override
    @Deprecated
    default List<T> batchUpsert(final Collection<? extends T> entities, final List<String> uniquePropNamesForQuery) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities to upsert
     * @param uniquePropNamesForQuery the property names that uniquely identify each entity
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as upserts are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Override
    @Deprecated
    default List<T> batchUpsert(final Collection<? extends T> entities, final List<String> uniquePropNamesForQuery, final int batchSize)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity to delete (must have ID populated)
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as deletes are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default int delete(final T entity) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param id the entity ID to delete
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as deletes are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default int deleteById(final ID id) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities to delete
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as deletes are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default int batchDelete(final Collection<? extends T> entities) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities to delete
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as deletes are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default int batchDelete(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param ids the collection of IDs to delete
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as deletes are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default int batchDeleteByIds(final Collection<? extends ID> ids) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param ids the collection of IDs to delete
     * @param batchSize the number of IDs to process in each batch
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as deletes are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default int batchDeleteByIds(final Collection<? extends ID> ids, final int batchSize) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}
