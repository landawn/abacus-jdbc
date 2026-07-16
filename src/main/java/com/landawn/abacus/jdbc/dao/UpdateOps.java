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
import java.util.HashMap;
import java.util.Map;

import com.landawn.abacus.annotation.JoinedBy;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.query.condition.Condition;

/**
 * Update capability of {@link Dao}: condition-based {@code update} operations. Extends {@link DaoBase}.
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the self-referencing DAO type
 * @see Dao
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
sealed interface UpdateOps<T, TD extends DaoBase<T, TD>> extends DaoBase<T, TD> permits Dao, CrudUpdateOps, UncheckedUpdateOps {
    /**
     * Updates a single property for all records matching the condition.
     * Convenience method for updating one field.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * int updated = dao.update("status", "INACTIVE", Filters.lt("lastLogin", thirtyDaysAgo));
     * System.out.println("Deactivated " + updated + " users");
     * }</pre>
     *
     * @param propName the property name to update
     * @param propValue the new value for the property
     * @param cond the condition to match records
     * @return the number of records updated
     * @throws SQLException if a database access error occurs
     */
    default int update(final String propName, final Object propValue, final Condition cond) throws SQLException {
        final Map<String, Object> updateProps = new HashMap<>();
        updateProps.put(propName, propValue);

        return update(updateProps, cond);
    }

    /**
     * Updates multiple properties for all records matching the condition.
     * The map keys are property names and values are the new values.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Map<String, Object> updates = new HashMap<>();
     * updates.put("lastModified", new Date());
     * updates.put("modifiedBy", currentUser);
     * int count = dao.update(updates, Filters.eq("status", "PENDING"));
     * }</pre>
     *
     * @param updateProps map of property names to new values
     * @param cond the condition to match records
     * @return the number of records updated
     * @throws SQLException if a database access error occurs
     */
    int update(final Map<String, Object> updateProps, final Condition cond) throws SQLException;

    /**
     * Updates records matching the condition using all updatable properties from the entity.
     * This updates every property of the entity that is considered updatable
     * (i.e., excluding {@code @ReadOnly}, {@code @NonUpdatable}, {@code @Id},
     * {@link JoinedBy @JoinedBy}, etc.),
     * regardless of whether the value is {@code null}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User updates = new User();
     * updates.setStatus("ACTIVE");
     * updates.setLastLogin(new Date());
     * int count = dao.update(updates, Filters.eq("id", userId));
     * }</pre>
     *
     * @param entity the entity containing update values
     * @param cond the condition to match records
     * @return the number of records updated
     * @throws SQLException if a database access error occurs
     */
    default int update(final T entity, final Condition cond) throws SQLException {
        @SuppressWarnings("deprecation")
        final Collection<String> propNamesToUpdate = JdbcUtil.getUpdatePropNames(targetEntityClass());

        return update(entity, propNamesToUpdate, cond);
    }

    /**
     * Updates records with only the specified properties from the entity.
     * This allows precise control over which fields are updated.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setEmail("new@example.com");
     * user.setPhone("555-1234");
     * int count = dao.update(
     *     user,
     *     Arrays.asList("email", "phone"),
     *     Filters.eq("id", userId)
     * );
     * }</pre>
     *
     * @param entity the entity containing update values
     * @param propNamesToUpdate the property names to update
     * @param cond the condition to match records
     * @return the number of records updated
     * @throws SQLException if a database access error occurs
     */
    int update(final T entity, final Collection<String> propNamesToUpdate, final Condition cond) throws SQLException;

}
