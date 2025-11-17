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
import com.landawn.abacus.query.SQLBuilder;
import com.landawn.abacus.query.condition.Condition;

/**
 * A specialized CRUD DAO interface that disables update operations while allowing read, insert, and delete operations.
 * This interface is designed for use cases where data modification is restricted to insertions and deletions only,
 * ensuring data integrity by preventing accidental updates.
 *
 * <p>This interface extends multiple DAO interfaces to provide comprehensive read, insert, and delete functionality while
 * blocking update operations. It's particularly useful in audit systems, append-only data stores, or scenarios
 * where historical data must remain immutable.</p>
 *
 * <p>This interface throws {@link UncheckedSQLException} instead of checked {@link java.sql.SQLException},
 * allowing for cleaner code without explicit exception handling.</p>
 *
 * <p>All update-related methods (including {@code update}, {@code batchUpdate}, and {@code upsert}) will throw
 * {@link UnsupportedOperationException}.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface AuditLogDao extends UncheckedNoUpdateCrudDao<AuditLog, Long, SQLBuilder.PSC, AuditLogDao> {
 *     // Only read, insert, and delete operations available
 * }
 *
 * AuditLogDao auditDao = JdbcUtil.createDao(AuditLogDao.class, dataSource);
 *
 * // Insert operations work normally
 * AuditLog log = new AuditLog("User login", userId);
 * Long id = auditDao.insert(log);
 *
 * // Read operations work normally
 * Optional<AuditLog> retrieved = auditDao.get(id);
 * List<AuditLog> logs = auditDao.list(CF.eq("userId", userId));
 *
 * // Update operations throw UnsupportedOperationException
 * // auditDao.update(log);  // This will throw!
 *
 * // Delete operations work normally
 * auditDao.deleteById(id);
 * }</pre>
 *
 * @param <T> the entity type
 * @param <ID> the ID type
 * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
 * @param <TD> the self-type of the DAO for method chaining
 * @see com.landawn.abacus.query.condition.ConditionFactory
 * @see com.landawn.abacus.query.condition.ConditionFactory.CF
 * @see NoUpdateCrudDao
 * @see UncheckedCrudDao
 */
@Beta
public interface UncheckedNoUpdateCrudDao<T, ID, SB extends SQLBuilder, TD extends UncheckedNoUpdateCrudDao<T, ID, SB, TD>>
        extends UncheckedNoUpdateDao<T, SB, TD>, NoUpdateCrudDao<T, ID, SB, TD>, UncheckedCrudDao<T, ID, SB, TD> {

    /**
     * This operation is not supported in a no-update DAO.
     * 
     * <p>Attempting to update an entity is not allowed in this DAO implementation as it's designed
     * to prevent any modifications to existing records. Use insert for new records or delete for removal.</p>
     * 
     * @param entityToUpdate The entity to update (operation will fail)
     * @return Never returns normally
     * @throws UnsupportedOperationException Always thrown as update operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Deprecated
    @Override
    default int update(final T entityToUpdate) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * 
     * <p>Attempting to update specific properties of an entity is not allowed. This DAO is designed
     * for immutable data scenarios where records can only be inserted or deleted, never modified.</p>
     * 
     * <p>Example of what would fail:
     * <pre>{@code
     * User user = new User();
     * user.setId(123L);
     * user.setEmail("newemail@example.com");
     * // This will throw UnsupportedOperationException
     * dao.update(user, Arrays.asList("email"));
     * }</pre>
     * 
     * @param entityToUpdate The entity containing updated values (operation will fail)
     * @param propNamesToUpdate Collection of property names to update (operation will fail)
     * @return Never returns normally
     * @throws UnsupportedOperationException Always thrown as update operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Deprecated
    @Override
    default int update(final T entityToUpdate, final Collection<String> propNamesToUpdate) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * 
     * <p>Attempting to update a single property value by ID is not allowed. This restriction ensures
     * data immutability in systems where audit trails or historical accuracy is critical.</p>
     * 
     * <p>Example of what would fail:
     * <pre>{@code
     * // This will throw UnsupportedOperationException
     * dao.update("status", "INACTIVE", 123L);
     * }</pre>
     *
     * @param propName The name of the property to update (operation will fail)
     * @param propValue The new value for the property (operation will fail)
     * @param id The ID of the entity to update (operation will fail)
     * @return Never returns normally
     * @throws UnsupportedOperationException Always thrown as update operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Override
    @Deprecated
    default int update(final String propName, final Object propValue, final ID id) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * 
     * <p>Attempting to update multiple properties by ID using a map is not allowed. This DAO type
     * is specifically designed for append-only or immutable data scenarios.</p>
     * 
     * <p>Example of what would fail:
     * <pre>{@code
     * Map<String, Object> updates = new HashMap<>();
     * updates.put("email", "newemail@example.com");
     * updates.put("status", "ACTIVE");
     * // This will throw UnsupportedOperationException
     * dao.update(updates, 123L);
     * }</pre>
     * 
     * @param updateProps Map of property names to their new values (operation will fail)
     * @param id The ID of the entity to update (operation will fail)
     * @return Never returns normally
     * @throws UnsupportedOperationException Always thrown as update operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Deprecated
    @Override
    default int update(final Map<String, Object> updateProps, final ID id) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * 
     * <p>Batch update operations are not allowed. For bulk operations, consider using batch insert
     * for new records or batch delete for removing existing records.</p>
     * 
     * @param entities Collection of entities to update (operation will fail)
     * @return Never returns normally
     * @throws UnsupportedOperationException Always thrown as update operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Deprecated
    @Override
    default int batchUpdate(final Collection<? extends T> entities) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * 
     * <p>Batch update operations with custom batch size are not allowed. This DAO maintains
     * data immutability by preventing all forms of updates.</p>
     * 
     * @param entities Collection of entities to update (operation will fail)
     * @param batchSize The batch size for the operation (operation will fail)
     * @return Never returns normally
     * @throws UnsupportedOperationException Always thrown as update operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Deprecated
    @Override
    default int batchUpdate(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * 
     * <p>Batch update of specific properties is not allowed. Consider using a different DAO type
     * if update operations are required for your use case.</p>
     * 
     * @param entities Collection of entities to update (operation will fail)
     * @param propNamesToUpdate Properties to update in each entity (operation will fail)
     * @return Never returns normally
     * @throws UnsupportedOperationException Always thrown as update operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Deprecated
    @Override
    default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * 
     * <p>Batch update with specific properties and custom batch size is not allowed. This DAO type
     * ensures that once data is inserted, it cannot be modified, only deleted if necessary.</p>
     * 
     * @param entities Collection of entities to update (operation will fail)
     * @param propNamesToUpdate Properties to update in each entity (operation will fail)
     * @param batchSize The batch size for the operation (operation will fail)
     * @return Never returns normally
     * @throws UnsupportedOperationException Always thrown as update operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Deprecated
    @Override
    default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate, final int batchSize)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * 
     * <p>The upsert (insert-or-update) operation is not allowed because it includes update functionality.
     * Use insert operations for new records only. If a record already exists, it must be deleted first
     * before inserting a new version.</p>
     * 
     * <p>Example of what would fail:
     * <pre>{@code
     * User user = new User("John", "john@example.com");
     * user.setId(123L);
     * // This will throw UnsupportedOperationException
     * dao.upsert(user);
     * }</pre>
     * 
     * @param entity The entity to insert or update (operation will fail)
     * @return Never returns normally
     * @throws UnsupportedOperationException Always thrown as upsert operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Deprecated
    @Override
    default T upsert(final T entity) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * 
     * <p>Upsert with unique property names for conflict detection is not allowed. This DAO type
     * prevents all forms of updates, including conditional updates through upsert operations.</p>
     * 
     * @param entity The entity to insert or update (operation will fail)
     * @param uniquePropNamesForQuery Property names used to check for existing records (operation will fail)
     * @return Never returns normally
     * @throws UnsupportedOperationException Always thrown as upsert operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Deprecated
    @Override
    default T upsert(final T entity, final List<String> uniquePropNamesForQuery) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * 
     * <p>Conditional upsert operations are not allowed. The update portion of upsert functionality
     * conflicts with the no-update design principle of this DAO type.</p>
     * 
     * @param entity The entity to insert or update (operation will fail)
     * @param cond Condition to check for existing records (operation will fail)
     * @return Never returns normally
     * @throws UnsupportedOperationException Always thrown as upsert operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Deprecated
    @Override
    default T upsert(final T entity, final Condition cond) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * 
     * <p>Batch upsert operations are not allowed. For bulk operations, use batch insert for new
     * records exclusively. Existing records cannot be updated through this DAO.</p>
     * 
     * @param entities Collection of entities to upsert (operation will fail)
     * @return Never returns normally
     * @throws UnsupportedOperationException Always thrown as batch upsert operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Override
    @Deprecated
    default List<T> batchUpsert(final Collection<? extends T> entities) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * 
     * <p>Batch upsert with custom batch size is not allowed. This DAO maintains strict immutability
     * of existing records.</p>
     * 
     * @param entities Collection of entities to upsert (operation will fail)
     * @param batchSize The batch size for the operation (operation will fail)
     * @return Never returns normally
     * @throws UnsupportedOperationException Always thrown as batch upsert operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Override
    @Deprecated
    default List<T> batchUpsert(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * 
     * <p>Batch upsert with unique property specification is not allowed. Use batch insert for
     * adding new records in bulk.</p>
     * 
     * @param entities Collection of entities to upsert (operation will fail)
     * @param uniquePropNamesForQuery Property names for conflict detection (operation will fail)
     * @return Never returns normally
     * @throws UnsupportedOperationException Always thrown as batch upsert operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Override
    @Deprecated
    default List<T> batchUpsert(final Collection<? extends T> entities, final List<String> uniquePropNamesForQuery) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * 
     * <p>Batch upsert with unique properties and custom batch size is not allowed. This DAO type
     * is designed for scenarios where data integrity requires preventing all updates.</p>
     * 
     * @param entities Collection of entities to upsert (operation will fail)
     * @param uniquePropNamesForQuery Property names for conflict detection (operation will fail)
     * @param batchSize The batch size for the operation (operation will fail)
     * @return Never returns normally
     * @throws UnsupportedOperationException Always thrown as batch upsert operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Override
    @Deprecated
    default List<T> batchUpsert(final Collection<? extends T> entities, final List<String> uniquePropNamesForQuery, final int batchSize)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * 
     * <p>While this DAO supports delete operations, this particular method signature is deprecated
     * to maintain API consistency. Use {@link #deleteById(Object)} or other delete methods instead.</p>
     * 
     * <p>Example of preferred approach:
     * <pre>{@code
     * // Instead of: dao.delete(entity);
     * // Use: dao.deleteById(entity.getId());
     * }</pre>
     * 
     * @param entity The entity to delete (operation will fail)
     * @return Never returns normally
     * @throws UnsupportedOperationException Always thrown to maintain API consistency
     * @deprecated Use {@link #deleteById(Object)} or other delete methods instead
     */
    @Deprecated
    @Override
    default int delete(final T entity) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * 
     * <p>While delete operations are generally supported, this specific method is deprecated.
     * Use alternative delete methods that don't require passing the entire entity.</p>
     * 
     * <p>Example of preferred approach:
     * <pre>{@code
     * // Preferred way to delete by ID
     * int deletedCount = dao.deleteById(123L);
     * 
     * // Or delete by condition
     * int deletedCount = dao.deleteAll(CF.eq("status", "INACTIVE"));
     * }</pre>
     * 
     * @param id The ID of the entity to delete (operation will fail)
     * @return Never returns normally
     * @throws UnsupportedOperationException Always thrown to maintain API consistency
     * @deprecated Use other delete methods that are not deprecated
     */
    @Deprecated
    @Override
    default int deleteById(final ID id) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * 
     * <p>Batch delete by entity collection is deprecated in this interface. Use batch delete
     * methods that operate on IDs or conditions instead.</p>
     * 
     * @param entities Collection of entities to delete (operation will fail)
     * @return Never returns normally
     * @throws UnsupportedOperationException Always thrown to maintain API consistency
     * @deprecated Use {@link #batchDeleteByIds(Collection)} or condition-based delete methods
     */
    @Deprecated
    @Override
    default int batchDelete(final Collection<? extends T> entities) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * 
     * <p>Batch delete with custom batch size is deprecated. Use alternative batch delete methods
     * that operate on IDs or conditions.</p>
     * 
     * @param entities Collection of entities to delete (operation will fail)
     * @param batchSize The batch size for the operation (operation will fail)
     * @return Never returns normally
     * @throws UnsupportedOperationException Always thrown to maintain API consistency
     * @deprecated Use {@link #batchDeleteByIds(Collection, int)} or condition-based delete methods
     */
    @Deprecated
    @Override
    default int batchDelete(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * 
     * <p>Batch delete by ID collection is deprecated in this interface. Use condition-based
     * delete methods for bulk deletions.</p>
     * 
     * <p>Example of alternative approach:
     * <pre>{@code
     * // Instead of: dao.batchDeleteByIds(Arrays.asList(1L, 2L, 3L));
     * // Use: dao.deleteAll(CF.in("id", Arrays.asList(1L, 2L, 3L)));
     * }</pre>
     * 
     * @param ids Collection of IDs to delete (operation will fail)
     * @return Never returns normally
     * @throws UnsupportedOperationException Always thrown to maintain API consistency
     * @deprecated Use condition-based delete methods instead
     */
    @Deprecated
    @Override
    default int batchDeleteByIds(final Collection<? extends ID> ids) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * 
     * <p>Batch delete by IDs with custom batch size is deprecated. Use condition-based delete
     * methods which provide more flexibility and better performance.</p>
     * 
     * @param ids Collection of IDs to delete (operation will fail)
     * @param batchSize The batch size for the operation (operation will fail)
     * @return Never returns normally
     * @throws UnsupportedOperationException Always thrown to maintain API consistency
     * @deprecated Use condition-based delete methods instead
     */
    @Deprecated
    @Override
    default int batchDeleteByIds(final Collection<? extends ID> ids, final int batchSize) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}
