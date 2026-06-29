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

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.query.condition.Condition;

/**
 * Unchecked-exception delete capability: the {@link DeletableDao} operations re-declared to throw
 * {@link com.landawn.abacus.exception.UncheckedSQLException}.
 * 
 * @param <T> the entity type managed by this DAO
 * @param <TD> the self-referencing DAO type
 * @see DeletableDao
 * @see UncheckedDao
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
@Beta
sealed interface UncheckedDeletableDao<T, TD extends UncheckedReadableDao<T, TD>> extends DeletableDao<T, TD>, UncheckedReadableDao<T, TD>
        permits UncheckedDao, UncheckedDeletableCrudDao {
    /**
     * Deletes all records that match the specified condition.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Delete all inactive users
     * int deletedCount = userDao.delete(Filters.and(
     *     Filters.eq("status", "INACTIVE"),
     *     Filters.lt("lastLogin", oneYearAgo)
     * ));
     * }</pre>
     *
     * @param cond the condition to match records to delete
     * @return the number of records deleted
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int delete(final Condition cond) throws UncheckedSQLException;

}
