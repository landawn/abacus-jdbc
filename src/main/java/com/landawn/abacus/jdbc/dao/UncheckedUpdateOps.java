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
import java.util.HashMap;
import java.util.Map;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.query.QueryUtil;
import com.landawn.abacus.query.condition.Condition;

/**
 * Unchecked-exception update capability: the {@link UpdateOps} operations re-declared to throw
 * {@link com.landawn.abacus.exception.UncheckedSQLException}.
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the self-referencing DAO type
 * @see UpdateOps
 * @see UncheckedDao
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
@Beta
sealed interface UncheckedUpdateOps<T, TD extends UncheckedDaoBase<T, TD>> extends UpdateOps<T, TD>, UncheckedDaoBase<T, TD>
        permits UncheckedDao, UncheckedCrudUpdateOps {
    /**
     * Updates a single property value for all records matching the condition.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * int updated = userDao.update("status", "INACTIVE", Filters.lt("lastLogin", thirtyDaysAgo));
     * }</pre>
     *
     * @param propName the property name to update
     * @param propValue the new value for the property
     * @param cond the condition to match records to update
     * @return the number of records updated
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default int update(final String propName, final Object propValue, final Condition cond) throws UncheckedSQLException {
        final Map<String, Object> updateProps = new HashMap<>();
        updateProps.put(propName, propValue);

        return update(updateProps, cond);
    }

    /**
     * Updates multiple properties for all records matching the condition.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Map<String, Object> updates = new HashMap<>();
     * updates.put("status", "VERIFIED");
     * updates.put("verifiedDate", new Date());
     * int updated = userDao.update(updates, Filters.eq("pendingVerification", true));
     * }</pre>
     *
     * @param updateProps a map of property names to their new values
     * @param cond the condition to match records to update
     * @return the number of records updated
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int update(final Map<String, Object> updateProps, final Condition cond) throws UncheckedSQLException;

    /**
     * Updates all records matching the condition using all updatable properties from the entity.
     * This updates every property of the entity that is considered updatable
     * (i.e., excluding {@code @ReadOnly}, {@code @NonUpdatable}, {@code @Id}, etc.),
     * regardless of whether the value is {@code null}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User template = new User();
     * template.setStatus("MIGRATED");
     * template.setMigratedDate(new Date());
     * int updated = userDao.update(template, Filters.eq("legacySystem", true));
     * }</pre>
     *
     * @param entity the entity containing values to update
     * @param cond the condition to match records to update
     * @return the number of records updated
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default int update(final T entity, final Condition cond) throws UncheckedSQLException {
        @SuppressWarnings("deprecation")
        final Collection<String> propNamesToUpdate = QueryUtil.updatePropNames(targetEntityClass(), null);

        return update(entity, propNamesToUpdate, cond);
    }

    /**
     * Updates records matching the condition with specified properties from the entity.
     * Only the properties listed in propNamesToUpdate will be updated.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User updates = new User();
     * updates.setEmail("newemail@example.com");
     * updates.setPhone("555-1234");
     * updates.setAddress("123 Main St");   // This won't be updated
     *
     * int updated = userDao.update(
     *     updates,
     *     Arrays.asList("email", "phone"),  // Only update these fields
     *     Filters.eq("id", 123)
     * );
     * }</pre>
     *
     * @param entity the entity containing values to update
     * @param propNamesToUpdate the properties to update from the entity
     * @param cond the condition to match records to update
     * @return the number of records updated
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int update(final T entity, final Collection<String> propNamesToUpdate, final Condition cond) throws UncheckedSQLException;

}
