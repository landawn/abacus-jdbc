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
import java.util.Map;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.query.SQLBuilder;

/**
 * A CRUD Data Access Object interface for entities with {@code Long} type primary keys
 * that disables update and delete operations while allowing read and insert operations. This interface extends both
 * {@link NoUpdateCrudDao} and {@link CrudDaoL}, providing read and insert operations
 * while blocking update and delete functionality.
 * 
 * <p>This interface is useful when you want to create a DAO that can read and insert
 * records but cannot modify or delete existing records. All update and delete methods
 * throw {@link UnsupportedOperationException}.</p>
 * 
 * <p>This interface is marked as {@code @Beta}, indicating it may be subject to
 * incompatible changes, or even removal, in a future release.</p>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * public interface AuditLogDao extends NoUpdateCrudDaoL<AuditLog, SQLBuilder, AuditLogDao> {
 *     // Can insert new audit logs and read existing ones
 *     // Cannot update or delete audit logs (immutable records)
 * }
 * 
 * // Usage:
 * AuditLog log = new AuditLog();
 * Long id = auditLogDao.insert(log); // Works
 * AuditLog retrieved = auditLogDao.findById(id); // Works
 * auditLogDao.update("status", "processed", id); // Throws UnsupportedOperationException
 * }</pre>
 * 
 * @param <T> the type of the entity
 * @param <SB> the type of SQLBuilder used for query construction
 * @param <TD> the type of the DAO implementation (self-referencing type parameter)
 * @see NoUpdateCrudDao
 * @see CrudDaoL
 */
@SuppressWarnings("RedundantThrows")
@Beta
public interface NoUpdateCrudDaoL<T, SB extends SQLBuilder, TD extends NoUpdateCrudDaoL<T, SB, TD>>
        extends NoUpdateCrudDao<T, Long, SB, TD>, CrudDaoL<T, SB, TD> {

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
    @Deprecated
    @Override
    default int update(final String propName, final Object propValue, final long id) throws SQLException, UnsupportedOperationException {
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
    default int update(final Map<String, Object> updateProps, final long id) throws SQLException, UnsupportedOperationException {
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
    default int deleteById(final long id) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}