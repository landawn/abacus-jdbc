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
import java.util.HashMap;
import java.util.Map;

/**
 * Transforms query results into a Map structure where each result row is keyed by a specified field.
 * This annotation is useful when you need to quickly lookup entities by a unique identifier
 * or when you want to group results by a specific field value.
 * 
 * <p>The annotation extracts the value of the specified key field from each result row
 * and uses it as the map key. If multiple rows have the same key value, the last row
 * will overwrite previous ones (unless using a multi-value map implementation).</p>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long> {
 *     // Map users by their ID
 *     @Query("SELECT * FROM users WHERE status = :status")
 *     @MappedByKey("id")
 *     Map<Long, User> findUsersByStatus(@Bind("status") String status);
 *     
 *     // Map users by email (assuming email is unique)
 *     @Query("SELECT * FROM users WHERE created_date > :date")
 *     @MappedByKey(keyName = "email")
 *     Map<String, User> findRecentUsersByEmail(@Bind("date") Date date);
 *     
 *     // Using custom map implementation
 *     @Query("SELECT * FROM users WHERE department = :dept")
 *     @MappedByKey(keyName = "id", mapClass = LinkedHashMap.class)
 *     Map<Long, User> findUsersByDepartment(@Bind("dept") String dept);
 *     
 *     // Map with composite objects
 *     @Query("SELECT id, name, email, COUNT(*) as login_count FROM users GROUP BY id, name, email")
 *     @MappedByKey("id")
 *     Map<Long, Map<String, Object>> getUserLoginStats();
 * }
 * 
 * // Usage example
 * Map<Long, User> usersById = userDao.findUsersByStatus("ACTIVE");
 * User user = usersById.get(123L);  // Quick lookup by ID
 * }</pre>
 * 
 * <p>Important considerations:</p>
 * <ul>
 *   <li>The key field must exist in the query results</li>
 *   <li>Null key values will be included in the map (if the Map implementation supports {@code null} keys)</li>
 *   <li>Duplicate keys will result in later values overwriting earlier ones</li>
 *   <li>The return type must be a Map or a subtype of Map</li>
 * </ul>
 *
 * @see MergedById
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD })
public @interface MappedByKey {

    /**
     * Legacy attribute for specifying the key field name.
     *
     * <p><strong>Deprecated:</strong> Use {@link #keyName()} instead for better clarity and consistency.
     * This method is maintained for backward compatibility only.</p>
     *
     * <p>Example migration:</p>
     * <pre>{@code
     * // Old way (deprecated)
     * @MappedByKey("id")
     * Map<Long, User> getUserMap();
     *
     * // New way (recommended)
     * @MappedByKey(keyName = "id")
     * Map<Long, User> getUserMap();
     * }</pre>
     *
     * @return the field name to use as map key, or empty string if using {@link #keyName()} instead
     * @deprecated Use {@link #keyName()} for explicit and clear field name declaration
     */
    @Deprecated
    String value() default "";

    /**
     * Specifies the name of the field to use as the map key.
     * This field must exist in the query result set.
     * 
     * <p>The field value is extracted from each result row and used as the key
     * in the resulting map. The field can be:</p>
     * <ul>
     *   <li>A database column name</li>
     *   <li>An entity property name (if using entity mapping)</li>
     *   <li>An alias defined in the SQL query</li>
     * </ul>
     * 
     * <p>Examples:</p>
     * <pre>{@code
     * // Using database column name
     * @Query("SELECT user_id, user_name, email FROM users")
     * @MappedByKey(keyName = "user_id")
     * Map<Long, Map<String, Object>> getUsers();
     * 
     * // Using entity property name
     * @Query("SELECT * FROM products WHERE category = :category")
     * @MappedByKey(keyName = "productCode")  // Maps to product_code column
     * Map<String, Product> getProductsByCategory(@Bind("category") String category);
     * 
     * // Using SQL alias
     * @Query("SELECT id, name, price * 0.9 as discounted_price FROM products")
     * @MappedByKey(keyName = "discounted_price")
     * Map<BigDecimal, Product> getProductsByDiscountPrice();
     * }</pre>
     *
     * @return the field name to use as map key, or empty string if using the deprecated {@link #value()} instead
     */
    String keyName() default "";

    /**
     * Specifies the Map implementation class to use for the result.
     * The class must have a no-argument constructor.
     * 
     * <p>Common implementations:</p>
     * <ul>
     *   <li>{@link HashMap} (default) - No ordering, best performance</li>
     *   <li>{@link java.util.LinkedHashMap} - Maintains insertion order</li>
     *   <li>{@link java.util.TreeMap} - Sorted by key</li>
     *   <li>{@link java.util.concurrent.ConcurrentHashMap} - Thread-safe</li>
     * </ul>
     * 
     * <p>Examples:</p>
     * <pre>{@code
     * // Maintain insertion order
     * @Query("SELECT * FROM users ORDER BY created_date")
     * @MappedByKey(keyName = "id", mapClass = LinkedHashMap.class)
     * LinkedHashMap<Long, User> getUsersInCreationOrder();
     * 
     * // Sorted by key
     * @Query("SELECT * FROM products")
     * @MappedByKey(keyName = "productCode", mapClass = TreeMap.class)
     * TreeMap<String, Product> getProductsSortedByCode();
     * 
     * // Thread-safe map
     * @Query("SELECT * FROM config")
     * @MappedByKey(keyName = "key", mapClass = ConcurrentHashMap.class)
     * ConcurrentHashMap<String, Config> getConfigMap();
     * }</pre>
     *
     * @return the Map implementation class, defaults to HashMap
     */
    @SuppressWarnings("rawtypes")
    Class<? extends Map> mapClass() default HashMap.class;
}
