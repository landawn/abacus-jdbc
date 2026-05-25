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
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.query.SqlBuilder;
import com.landawn.abacus.query.condition.Condition;

/**
 * A specialized CRUD DAO interface that disables update and delete operations while allowing read and insert operations.
 * This interface is designed for use cases where stored records must remain immutable after insertion.
 *
 * <p><b>Unchecked Exception Handling:</b></p>
 * <p>This is an "unchecked" DAO variant. All read and insert methods throw {@link UncheckedSQLException}
 * instead of checked {@link java.sql.SQLException}, allowing for cleaner code without explicit exception
 * handling. This makes it particularly convenient for use in lambda expressions, stream operations, and
 * other functional programming patterns where checked exceptions would be cumbersome.</p>
 *
 * <p>This interface extends {@link UncheckedNoUpdateDao}, {@link NoUpdateCrudDao} and {@link UncheckedCrudDao}
 * to provide comprehensive read/insert functionality while blocking update and delete operations. It's
 * particularly useful in audit systems, append-only data stores, or scenarios where historical data must
 * remain immutable.</p>
 *
 * <p>All update-related methods (including {@code update}, {@code batchUpdate}, and {@code upsert}) and all
 * delete-related methods will throw {@link UnsupportedOperationException}.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface AuditLogDao extends UncheckedNoUpdateCrudDao<AuditLog, Long, SqlBuilder.PSC, AuditLogDao> {
 *     // Only read and insert operations available
 * }
 *
 * AuditLogDao auditDao = JdbcUtil.createDao(AuditLogDao.class, dataSource);
 *
 * // Insert operations work without checked exception handling:
 * AuditLog log = new AuditLog("User login", userId);
 * Long id = auditDao.insert(log);
 *
 * // Batch inserts are also supported:
 * List<AuditLog> logs = Arrays.asList(
 *     new AuditLog("Action 1", userId),
 *     new AuditLog("Action 2", userId)
 * );
 * List<Long> ids = auditDao.batchInsert(logs);
 *
 * // Read operations work without checked exception handling:
 * Optional<AuditLog> retrieved = auditDao.get(id);
 * List<AuditLog> userLogs = auditDao.list(Filters.eq("userId", userId));
 * long count = auditDao.count(Filters.between("timestamp", startDate, endDate));
 *
 * // Can be used in functional contexts without try-catch:
 * List<Long> logIds = Arrays.asList(1L, 2L, 3L);
 * logIds.forEach(logId -> auditDao.get(logId).ifPresent(System.out::println));
 *
 * // Update operations throw UnsupportedOperationException:
 * // auditDao.update(log);   // Throws exception
 * // auditDao.upsert(log);   // Throws exception
 *
 * // Delete operations also throw UnsupportedOperationException:
 * // auditDao.deleteById(id);   // Throws exception
 * // auditDao.delete(Filters.lt("timestamp", cutoffDate));   // Throws exception
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <ID> the type of the entity's primary key
 * @param <SB> the {@link SqlBuilder} type used to generate SQL statements; must be one of
 *             {@code SqlBuilder.PSC}, {@code SqlBuilder.PAC}, or {@code SqlBuilder.PLC}
 * @param <TD> the concrete DAO type itself (self-referencing generic for fluent method chaining)
 * @see UncheckedNoUpdateDao
 * @see NoUpdateCrudDao
 * @see UncheckedCrudDao
 * @see com.landawn.abacus.query.Filters
 */
@Beta
public interface UncheckedNoUpdateCrudDao<T, ID, SB extends SqlBuilder, TD extends UncheckedNoUpdateCrudDao<T, ID, SB, TD>>
        extends UncheckedNoUpdateDao<T, SB, TD>, NoUpdateCrudDao<T, ID, SB, TD>, UncheckedCrudDao<T, ID, SB, TD> {

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Updating an existing entity by ID is disallowed by {@code UncheckedNoUpdateCrudDao}
     * because it would mutate an existing record, violating the read/insert-only contract.
     *
     * @param entityToUpdate the entity with updated values
     * @return never returns normally
     * @throws UnsupportedOperationException always, since updates are not permitted
     * @deprecated Unsupported in {@code UncheckedNoUpdateCrudDao}. Updates are not allowed.
     */
    @Deprecated
    @Override
    default int update(final T entityToUpdate) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Selective property updates by ID are disallowed by {@code UncheckedNoUpdateCrudDao}
     * because they would mutate an existing record, violating the read/insert-only contract.
     *
     * @param entityToUpdate the entity containing the values to update
     * @param propNamesToUpdate the property names to update
     * @return never returns normally
     * @throws UnsupportedOperationException always, since updates are not permitted
     * @deprecated Unsupported in {@code UncheckedNoUpdateCrudDao}. Updates are not allowed.
     */
    @Deprecated
    @Override
    default int update(final T entityToUpdate, final Collection<String> propNamesToUpdate) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Single-property updates by ID are disallowed by {@code UncheckedNoUpdateCrudDao}
     * because they would mutate an existing record, violating the read/insert-only contract.
     *
     * @param propName the property name to update
     * @param propValue the new value for the property
     * @param id the entity ID to update
     * @return never returns normally
     * @throws UnsupportedOperationException always, since updates are not permitted
     * @deprecated Unsupported in {@code UncheckedNoUpdateCrudDao}. Updates are not allowed.
     */
    @Override
    @Deprecated
    default int update(final String propName, final Object propValue, final ID id) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Multi-property updates by ID are disallowed by {@code UncheckedNoUpdateCrudDao}
     * because they would mutate an existing record, violating the read/insert-only contract.
     *
     * @param updateProps a map of property names to their new values
     * @param id the entity ID to update
     * @return never returns normally
     * @throws UnsupportedOperationException always, since updates are not permitted
     * @deprecated Unsupported in {@code UncheckedNoUpdateCrudDao}. Updates are not allowed.
     */
    @Deprecated
    @Override
    default int update(final Map<String, Object> updateProps, final ID id) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Batch updates are disallowed by {@code UncheckedNoUpdateCrudDao} because they would
     * mutate existing records, violating the read/insert-only contract.
     *
     * @param entities the collection of entities to update
     * @return never returns normally
     * @throws UnsupportedOperationException always, since batch updates are not permitted
     * @deprecated Unsupported in {@code UncheckedNoUpdateCrudDao}. Updates are not allowed.
     */
    @Deprecated
    @Override
    default int batchUpdate(final Collection<? extends T> entities) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Sized batch updates are disallowed by {@code UncheckedNoUpdateCrudDao} because they
     * would mutate existing records, violating the read/insert-only contract.
     *
     * @param entities the collection of entities to update
     * @param batchSize the number of entities to process per batch
     * @return never returns normally
     * @throws UnsupportedOperationException always, since batch updates are not permitted
     * @deprecated Unsupported in {@code UncheckedNoUpdateCrudDao}. Updates are not allowed.
     */
    @Deprecated
    @Override
    default int batchUpdate(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Selective batch updates are disallowed by {@code UncheckedNoUpdateCrudDao} because they
     * would mutate existing records, violating the read/insert-only contract.
     *
     * @param entities the collection of entities to update
     * @param propNamesToUpdate the property names to update for all entities
     * @return never returns normally
     * @throws UnsupportedOperationException always, since batch updates are not permitted
     * @deprecated Unsupported in {@code UncheckedNoUpdateCrudDao}. Updates are not allowed.
     */
    @Deprecated
    @Override
    default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Sized selective batch updates are disallowed by {@code UncheckedNoUpdateCrudDao} because
     * they would mutate existing records, violating the read/insert-only contract.
     *
     * @param entities the collection of entities to update
     * @param propNamesToUpdate the property names to update for all entities
     * @param batchSize the number of entities to process per batch
     * @return never returns normally
     * @throws UnsupportedOperationException always, since batch updates are not permitted
     * @deprecated Unsupported in {@code UncheckedNoUpdateCrudDao}. Updates are not allowed.
     */
    @Deprecated
    @Override
    default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate, final int batchSize)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Upserts perform an update when a matching record exists, which violates the
     * read/insert-only contract of {@code UncheckedNoUpdateCrudDao}.
     *
     * @param entity the entity to upsert
     * @return never returns normally
     * @throws UnsupportedOperationException always, since upserts are not permitted
     * @deprecated Unsupported in {@code UncheckedNoUpdateCrudDao}. Upserts are not allowed.
     */
    @Deprecated
    @Override
    default T upsert(final T entity) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Upserts perform an update when a matching record exists, which violates the
     * read/insert-only contract of {@code UncheckedNoUpdateCrudDao}.
     *
     * @param entity the entity to insert or update
     * @param uniquePropNamesForQuery the property names that uniquely identify the entity
     * @return never returns normally
     * @throws UnsupportedOperationException always, since upserts are not permitted
     * @deprecated Unsupported in {@code UncheckedNoUpdateCrudDao}. Upserts are not allowed.
     */
    @Deprecated
    @Override
    default T upsert(final T entity, final List<String> uniquePropNamesForQuery) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Upserts perform an update when a matching record exists, which violates the
     * read/insert-only contract of {@code UncheckedNoUpdateCrudDao}.
     *
     * @param entity the entity to insert or update
     * @param cond the condition to check whether the entity already exists
     * @return never returns normally
     * @throws UnsupportedOperationException always, since upserts are not permitted
     * @deprecated Unsupported in {@code UncheckedNoUpdateCrudDao}. Upserts are not allowed.
     */
    @Deprecated
    @Override
    default T upsert(final T entity, final Condition cond) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Batch upserts perform updates when matching records exist, which violates the
     * read/insert-only contract of {@code UncheckedNoUpdateCrudDao}.
     *
     * @param entities the collection of entities to upsert
     * @return never returns normally
     * @throws UnsupportedOperationException always, since batch upserts are not permitted
     * @deprecated Unsupported in {@code UncheckedNoUpdateCrudDao}. Upserts are not allowed.
     */
    @Override
    @Deprecated
    default List<T> batchUpsert(final Collection<? extends T> entities) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Sized batch upserts perform updates when matching records exist, which violates the
     * read/insert-only contract of {@code UncheckedNoUpdateCrudDao}.
     *
     * @param entities the collection of entities to upsert
     * @param batchSize the number of entities to process per batch
     * @return never returns normally
     * @throws UnsupportedOperationException always, since batch upserts are not permitted
     * @deprecated Unsupported in {@code UncheckedNoUpdateCrudDao}. Upserts are not allowed.
     */
    @Override
    @Deprecated
    default List<T> batchUpsert(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Batch upserts perform updates when matching records exist, which violates the
     * read/insert-only contract of {@code UncheckedNoUpdateCrudDao}.
     *
     * @param entities the collection of entities to upsert
     * @param uniquePropNamesForQuery the property names that uniquely identify each entity
     * @return never returns normally
     * @throws UnsupportedOperationException always, since batch upserts are not permitted
     * @deprecated Unsupported in {@code UncheckedNoUpdateCrudDao}. Upserts are not allowed.
     */
    @Override
    @Deprecated
    default List<T> batchUpsert(final Collection<? extends T> entities, final List<String> uniquePropNamesForQuery) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Sized batch upserts perform updates when matching records exist, which violates the
     * read/insert-only contract of {@code UncheckedNoUpdateCrudDao}.
     *
     * @param entities the collection of entities to upsert
     * @param uniquePropNamesForQuery the property names that uniquely identify each entity
     * @param batchSize the number of entities to process per batch
     * @return never returns normally
     * @throws UnsupportedOperationException always, since batch upserts are not permitted
     * @deprecated Unsupported in {@code UncheckedNoUpdateCrudDao}. Upserts are not allowed.
     */
    @Override
    @Deprecated
    default List<T> batchUpsert(final Collection<? extends T> entities, final List<String> uniquePropNamesForQuery, final int batchSize)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Deleting an entity by its populated ID is disallowed by {@code UncheckedNoUpdateCrudDao}
     * because it would remove an existing record, violating the read/insert-only contract.
     *
     * @param entity the entity to delete (must have its ID populated)
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletes are not permitted
     * @deprecated Unsupported in {@code UncheckedNoUpdateCrudDao}. Deletes are not allowed.
     */
    @Deprecated
    @Override
    default int delete(final T entity) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Deleting by ID is disallowed by {@code UncheckedNoUpdateCrudDao} because it would
     * remove an existing record, violating the read/insert-only contract.
     *
     * @param id the entity ID to delete
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletes are not permitted
     * @deprecated Unsupported in {@code UncheckedNoUpdateCrudDao}. Deletes are not allowed.
     */
    @Deprecated
    @Override
    default int deleteById(final ID id) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Batch deletes are disallowed by {@code UncheckedNoUpdateCrudDao} because they would
     * remove existing records, violating the read/insert-only contract.
     *
     * @param entities the collection of entities to delete
     * @return never returns normally
     * @throws UnsupportedOperationException always, since batch deletes are not permitted
     * @deprecated Unsupported in {@code UncheckedNoUpdateCrudDao}. Deletes are not allowed.
     */
    @Deprecated
    @Override
    default int batchDelete(final Collection<? extends T> entities) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Sized batch deletes are disallowed by {@code UncheckedNoUpdateCrudDao} because they
     * would remove existing records, violating the read/insert-only contract.
     *
     * @param entities the collection of entities to delete
     * @param batchSize the number of entities to process per batch
     * @return never returns normally
     * @throws UnsupportedOperationException always, since batch deletes are not permitted
     * @deprecated Unsupported in {@code UncheckedNoUpdateCrudDao}. Deletes are not allowed.
     */
    @Deprecated
    @Override
    default int batchDelete(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Batch deletes by ID are disallowed by {@code UncheckedNoUpdateCrudDao} because they
     * would remove existing records, violating the read/insert-only contract.
     *
     * @param ids the collection of entity IDs to delete
     * @return never returns normally
     * @throws UnsupportedOperationException always, since batch deletes are not permitted
     * @deprecated Unsupported in {@code UncheckedNoUpdateCrudDao}. Deletes are not allowed.
     */
    @Deprecated
    @Override
    default int batchDeleteByIds(final Collection<? extends ID> ids) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * Sized batch deletes by ID are disallowed by {@code UncheckedNoUpdateCrudDao} because
     * they would remove existing records, violating the read/insert-only contract.
     *
     * @param ids the collection of entity IDs to delete
     * @param batchSize the number of IDs to process per batch
     * @return never returns normally
     * @throws UnsupportedOperationException always, since batch deletes are not permitted
     * @deprecated Unsupported in {@code UncheckedNoUpdateCrudDao}. Deletes are not allowed.
     */
    @Deprecated
    @Override
    default int batchDeleteByIds(final Collection<? extends ID> ids, final int batchSize) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}
