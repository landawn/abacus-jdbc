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
package com.landawn.abacus.jdbc.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.landawn.abacus.jdbc.OnDeleteAction;

/**
 * Specifies cascading delete behavior for entity relationships.
 * 
 * <p><strong>DEPRECATED:</strong> This annotation is deprecated and will not be implemented.
 * Cascading delete behavior should be defined and enforced at the database level using
 * foreign key constraints with ON DELETE actions. This provides better data integrity,
 * performance, and consistency across all applications accessing the database.</p>
 * 
 * <p>Instead of using this annotation, define foreign key constraints in your database schema:</p>
 * <pre>{@code
 * -- SQL example for cascading delete
 * CREATE TABLE orders (
 *     id BIGINT PRIMARY KEY,
 *     user_id BIGINT NOT NULL,
 *     FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
 * );
 * 
 * -- SQL example for restrict delete
 * CREATE TABLE payments (
 *     id BIGINT PRIMARY KEY,
 *     order_id BIGINT NOT NULL,
 *     FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE RESTRICT
 * );
 * }</pre>
 * 
 * <p>Database-level constraints provide several advantages:</p>
 * <ul>
 *   <li>Enforced consistently regardless of application layer</li>
 *   <li>Better performance through database optimization</li>
 *   <li>Works with direct SQL queries and other applications</li>
 *   <li>Prevents orphaned records and maintains referential integrity</li>
 * </ul>
 * 
 * <p>If you need application-level cascade behavior, implement it explicitly in your DAO methods:</p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long, SQLBuilder.PSC, UserDao> {
 *     default void deleteUserWithRelatedData(Long userId) {
 *         // Explicitly delete related data
 *         deleteUserOrders(userId);
 *         deleteUserProfiles(userId);
 *         deleteById(userId);
 *     }
 * }
 * }</pre>
 *
 * @deprecated This annotation won't be implemented. Define ON DELETE behavior in database schema instead.
 * @see OnDeleteAction
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD })
@Deprecated
public @interface OnDelete {

    /**
     * Specifies the action to take when a referenced entity is deleted.
     *
     * <p><strong>Note:</strong> This functionality should be implemented at the database level
     * using foreign key constraints rather than in the application layer.</p>
     *
     * <p>Available actions correspond to standard SQL ON DELETE behaviors:</p>
     * <ul>
     *   <li>{@link OnDeleteAction#NO_ACTION} - Default, no automatic action</li>
     *   <li>{@link OnDeleteAction#CASCADE} - Delete dependent records</li>
     *   <li>{@link OnDeleteAction#SET_NULL} - Set foreign key to NULL</li>
     * </ul>
     *
     * @return the delete action, defaults to {@link OnDeleteAction#NO_ACTION}
     * @deprecated Define ON DELETE actions in database foreign key constraints
     */
    @Deprecated
    OnDeleteAction action() default OnDeleteAction.NO_ACTION;
}
