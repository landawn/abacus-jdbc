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

import java.lang.annotation.Documented;
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
 * implementation. The method's {@code QueryOperation} must be {@code QueryOperation.DEFAULT} or {@code QueryOperation.list}; any other
 * {@code QueryOperation} fails DAO initialization with {@code IllegalArgumentException}.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long, UserDao> {
 *     // Map users by their ID
 *     @Query("SELECT * FROM users WHERE status = :status")
 *     @MappedByKey("id")
 *     Map<Long, User> findUsersByStatus(@Bind("status") String status) throws SQLException;
 *
 *     // Map users by email (assuming email is unique)
 *     @Query("SELECT * FROM users WHERE created_date > :date")
 *     @MappedByKey("email")
 *     Map<String, User> findRecentUsersByEmail(@Bind("date") Date date) throws SQLException;
 *
 *     // Using custom map implementation
 *     @Query("SELECT * FROM users WHERE department = :dept")
 *     @MappedByKey(value = "id", mapClass = java.util.LinkedHashMap.class)
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
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD })
public @interface MappedByKey {

    /**
     * Specifies the target-entity property name to use as the map key.
     *
     * <p>The value of that property is extracted from each merged result entity and used as the key
     * in the resulting map.</p>
     *
     * <p>If left empty, the framework falls back to the target entity's single id
     * property name; a DAO whose entity has no id property then fails initialization with
     * {@code IllegalArgumentException}.</p>
     *
     * <p>Examples:</p>
     * <pre>{@code
     * // Using entity property name
     * @Query("SELECT * FROM products WHERE category = :category")
     * @MappedByKey("productCode")  // Maps to product_code column
     * Map<String, Product> getProductsByCategory(@Bind("category") String category) throws SQLException;
     * }</pre>
     *
     * @return the field name to use as map key, or empty string to fall back to the entity's id property
     */
    String value() default "";

    /**
     * Specifies the Map implementation class to use for the result.
     * The class must be concrete, have a no-argument constructor, and be assignable to the DAO
     * method's declared return type. For example, a method returning {@code LinkedHashMap} must
     * explicitly select {@code LinkedHashMap.class}; the default {@link HashMap} is only compatible
     * with return types that can accept a {@code HashMap}, such as {@code Map} or {@code HashMap}.
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
     * @MappedByKey(value = "id", mapClass = LinkedHashMap.class)
     * LinkedHashMap<Long, User> getUsersInCreationOrder();
     *
     * // Sorted by key
     * @Query("SELECT * FROM products")
     * @MappedByKey(value = "productCode", mapClass = TreeMap.class)
     * TreeMap<String, Product> getProductsSortedByCode();
     *
     * // Thread-safe map
     * @Query("SELECT * FROM config")
     * @MappedByKey(value = "key", mapClass = ConcurrentHashMap.class)
     * ConcurrentHashMap<String, Config> getConfigMap();
     * }</pre>
     *
     * @return the {@code Map} implementation class to instantiate; defaults to {@link HashMap}
     */
    @SuppressWarnings("rawtypes")
    Class<? extends Map> mapClass() default HashMap.class;
}
