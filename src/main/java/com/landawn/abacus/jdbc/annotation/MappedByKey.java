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
 * <p>The annotation extracts the value of the specified key from each result row and
 * uses it as the map key. The key must be a property (getter) of the DAO's <i>target entity
 * class</i> (for example, {@code id} or {@code email}); SQL column labels must map to that
 * property, and the map's value type must be the target entity type (or a supertype) — plain
 * row-map values such as {@code Map<String, Object>} are not supported. Rows sharing the same
 * key value are merged into a single entity (the same merge {@link MergedById} performs), so
 * duplicate keys never overwrite each other.</p>
 *
 * <p>Per its {@code @Target}, this annotation is placed on a DAO query method whose declared return
 * type is a {@code Map} (or a {@code Map} subtype); see {@link #mapClass()} to control the concrete
 * implementation. The method's {@code OP} must be {@code OP.DEFAULT} or {@code OP.list}; any other
 * {@code OP} fails DAO initialization with {@code IllegalArgumentException}.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long, UserDao> {
 *     // Map users by their ID
 *     @Query("SELECT * FROM users WHERE status = :status")
 *     @MappedByKey(keyName = "id")
 *     Map<Long, User> findUsersByStatus(@Bind("status") String status) throws SQLException;
 *
 *     // Map users by email (assuming email is unique)
 *     @Query("SELECT * FROM users WHERE created_date > :date")
 *     @MappedByKey(keyName = "email")
 *     Map<String, User> findRecentUsersByEmail(@Bind("date") Date date) throws SQLException;
 *
 *     // Using custom map implementation
 *     @Query("SELECT * FROM users WHERE department = :dept")
 *     @MappedByKey(keyName = "id", mapClass = java.util.LinkedHashMap.class)
 *     Map<Long, User> findUsersByDepartment(@Bind("dept") String dept) throws SQLException;
 * }
 *
 * // Usage example
 * Map<Long, User> usersById = userDao.findUsersByStatus("ACTIVE");
 * User user = usersById.get(123L);   // Quick lookup by ID
 * }</pre>
 *
 * <p>Important considerations:</p>
 * <ul>
 *   <li>The key must be a property (getter) of the DAO's target entity class</li>
 *   <li>The map's value type must be the target entity type or a supertype ({@code Map<K, ? super Entity>})</li>
 *   <li>Null key values will be included in the map (if the Map implementation supports {@code null} keys)</li>
 *   <li>Rows sharing the same key are merged into one entity before the map is built</li>
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
     * This attribute is retained for backward compatibility only.</p>
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
     * Specifies the target-entity property name to use as the map key.
     *
     * <p>The value of that property is extracted from each merged result entity and used as the key
     * in the resulting map.</p>
     *
     * <p>If both {@code keyName} and the deprecated {@link #value()} are left empty, the framework falls
     * back to the target entity's single id property name; a DAO whose entity has no id property then
     * fails initialization with {@code IllegalArgumentException}. When both are set, the deprecated
     * {@link #value()} takes precedence.</p>
     *
     * <p>Examples:</p>
     * <pre>{@code
     * // Using entity property name
     * @Query("SELECT * FROM products WHERE category = :category")
     * @MappedByKey(keyName = "productCode")  // Maps to product_code column
     * Map<String, Product> getProductsByCategory(@Bind("category") String category) throws SQLException;
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
     * @return the {@code Map} implementation class to instantiate; defaults to {@link HashMap}
     */
    @SuppressWarnings("rawtypes")
    Class<? extends Map> mapClass() default HashMap.class;
}
