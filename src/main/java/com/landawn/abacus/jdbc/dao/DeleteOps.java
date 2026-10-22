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

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.query.condition.Condition;

/**
 * Delete capability of {@link Dao}: condition-based {@code delete}. Extends {@link DaoBase}.
 * 
 * @param <T> the entity type managed by this DAO
 * @param <TD> the self-referencing DAO type
 * @see Dao
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
sealed interface DeleteOps<T, TD extends DaoBase<T, TD>> extends DaoBase<T, TD> permits Dao, CrudDeleteOps, UncheckedDeleteOps {
    /**
     * Deletes all records matching the specified condition.
     * Returns the count of deleted records.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * int deleted = dao.delete(Filters.lt("expiryDate", new java.util.Date()));
     * System.out.println("Deleted " + deleted + " expired records");
     * }</pre>
     *
     * @param cond the condition to match records for deletion
     * @return the number of records deleted, or {@code 0} if none match
     * @throws IllegalArgumentException if {@code cond} is {@code null}
     * @throws UncheckedSQLException if acquiring a required database connection fails
     * @throws SQLException if preparing, binding, or executing a DELETE statement fails
     */
    int delete(final Condition cond) throws IllegalArgumentException, UncheckedSQLException, SQLException;

}
