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

import com.landawn.abacus.query.SQLBuilder;

/**
 * A specialized interface that combines read-only operations with join entity support for CRUD DAOs.
 * This interface provides functionality to read entities along with their related entities through joins,
 * but explicitly disables any modification operations on the joined entities.
 * 
 * <p>This interface is particularly useful in scenarios where you need to fetch entities with their
 * relationships (like one-to-many or many-to-many) but want to ensure that the related entities
 * cannot be modified through this DAO. It maintains data integrity by preventing cascading updates
 * or deletes on joined entities.</p>
 * 
 * <p>Example usage:
 * <pre>{@code
 * // Assuming we have User and Order entities with a one-to-many relationship
 * UncheckedReadOnlyCrudJoinEntityHelper<User, Long, SQLBuilder, ?> userDao = daoFactory.createReadOnlyJoinDao(User.class);
 * 
 * // Fetch a user with their orders
 * Optional<User> userWithOrders = userDao.findById(123L, Order.class);
 * 
 * // The user object now contains loaded Order entities
 * if (userWithOrders.isPresent()) {
 *     User user = userWithOrders.get();
 *     List<Order> orders = user.getOrders(); // Orders are loaded
 *     
 *     // But you cannot delete join entities through this DAO
 *     try {
 *         userDao.deleteJoinEntities(user, Order.class); // This will throw!
 *     } catch (UnsupportedOperationException e) {
 *         // Expected behavior for read-only join operations
 *     }
 * }
 * }</pre></p>
 * 
 * <p>This interface extends both {@link UncheckedReadOnlyJoinEntityHelper} and {@link UncheckedCrudJoinEntityHelper},
 * inheriting read operations from both while overriding modification operations to throw {@link UnsupportedOperationException}.</p>
 *
 * @param <T> The entity type that this helper manages
 * @param <ID> The type of the entity's primary key (ID)
 * @param <SB> The type of {@link SQLBuilder} used to generate SQL scripts
 * @param <TD> The self-referential type parameter for the DAO, extending {@link UncheckedCrudDao}
 * @see UncheckedReadOnlyJoinEntityHelper
 * @see UncheckedCrudJoinEntityHelper
 * @see UncheckedCrudDao
 */
public interface UncheckedReadOnlyCrudJoinEntityHelper<T, ID, SB extends SQLBuilder, TD extends UncheckedCrudDao<T, ID, SB, TD>>
        extends UncheckedReadOnlyJoinEntityHelper<T, SB, TD>, UncheckedCrudJoinEntityHelper<T, ID, SB, TD> {
}