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

import com.landawn.abacus.annotation.JoinedBy;

/**
 * Merges multiple result rows into single entities based on ID fields.
 * This annotation is particularly useful for handling one-to-many relationships
 * where a SQL join produces multiple rows for each parent entity.
 * 
 * <p>When a query with joins returns multiple rows for the same entity (due to
 * one-to-many relationships), this annotation automatically merges those rows
 * into a single entity with nested collections.</p>
 * 
 * <p>The merging is performed based on the entity's ID field(s). Rows with the
 * same ID are combined, with collection properties populated from the multiple rows.</p>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * // Entity classes
 * public class User {
 *     private Long id;
 *     private String firstName;
 *     private String lastName;
 *     private List<Device> devices;  // One-to-many relationship
 *     // getters and setters
 * }
 * 
 * public class Device {
 *     private Long id;
 *     private String model;
 *     // getters and setters
 * }
 * 
 * // DAO interface
 * public interface UserDao extends CrudDao<User, Long> {
 *     // Query returns multiple rows per user (one per device)
 *     @Query("SELECT u.id, u.first_name as firstName, u.last_name as lastName, " +
 *             "d.id as 'devices.id', d.model as 'devices.model' " +
 *             "FROM users u LEFT JOIN devices d ON u.id = d.user_id " +
 *             "WHERE u.id IN ({ids})")
 *     @MergedById
 *     List<User> findUsersWithDevices(@BindList("ids") List<Long> ids);
 *     
 *     // Result: Each User object will have its devices list populated
 * }
 * 
 * // Usage
 * List<User> users = userDao.findUsersWithDevices(Arrays.asList(1L, 2L, 3L));
 * // Each user will have all their devices in the devices list
 * }</pre>
 * 
 * <p>Column naming conventions for nested properties:</p>
 * <ul>
 *   <li>Use dot notation: {@code 'devices.id'}, {@code 'devices.model'}</li>
 *   <li>Or use nested aliases in query results</li>
 * </ul>
 * 
 * <p>The annotation supports:</p>
 * <ul>
 *   <li>Single ID field (most common case)</li>
 *   <li>Composite IDs (using the deprecated value parameter)</li>
 *   <li>Multiple levels of nesting</li>
 *   <li>Both LEFT and INNER joins</li>
 * </ul>
 * 
 * <p>Important notes:</p>
 * <ul>
 *   <li>The query must include the ID field(s) in the SELECT clause</li>
 *   <li>Results must be ordered properly for efficient merging</li>
 *   <li>Collection properties in entities should be initialized</li>
 * </ul>
 *
 * @see MappedByKey
 * @see JoinedBy
 * @since 0.8
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD })
public @interface MergedById {

    /**
     * Specifies the ID field(s) to use for merging rows.
     * For composite keys, provide a comma-separated list of field names.
     *
     * <p><strong>Deprecated:</strong> This parameter is no longer needed in most cases.
     * The framework automatically detects ID fields from entity class annotations
     * (such as {@code @Id}) or standard naming conventions, making explicit specification unnecessary.</p>
     *
     * <p>The automatic ID detection works for:</p>
     * <ul>
     *   <li>Single ID fields annotated with {@code @Id}</li>
     *   <li>Composite keys using {@code @Id} on multiple fields</li>
     *   <li>Standard naming conventions (e.g., "id" field name)</li>
     * </ul>
     *
     * <p>Example with composite key (legacy usage):</p>
     * <pre>{@code
     * // Old way (deprecated) - manually specify composite key
     * @Query("SELECT * FROM order_items WHERE order_date = :date")
     * @MergedById("orderId, productId")
     * List<OrderItem> findByDate(@Bind("date") Date date);
     *
     * // New way (recommended) - let framework detect ID fields
     * @Query("SELECT * FROM order_items WHERE order_date = :date")
     * @MergedById
     * List<OrderItem> findByDate(@Bind("date") Date date);
     * // OrderItem class should have @Id annotations on orderId and productId fields
     * }</pre>
     *
     * @return comma-separated list of ID field names, or empty string for automatic detection
     * @deprecated The framework automatically detects ID fields from entity metadata. Use {@code @MergedById} without the value parameter.
     */
    @Deprecated
    String value() default "";
}