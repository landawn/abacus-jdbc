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

import com.landawn.abacus.annotation.Beta;

/**
 * Controls cache refresh behavior for DAO methods that modify data.
 * This annotation is used in conjunction with caching mechanisms to ensure that
 * cached data is refreshed after operations that modify the underlying data.
 * 
 * <p>When caching is enabled for a DAO, query results may be cached to improve performance.
 * However, when data is modified (through insert, update, or delete operations),
 * the cached data becomes stale. This annotation marks methods that should trigger
 * a cache refresh to maintain data consistency.</p>
 * 
 * <p>The annotation can be applied at both class and method levels:</p>
 * <ul>
 *   <li>Class-level: Applies to all methods matching the filter criteria</li>
 *   <li>Method-level: Applies only to the specific method</li>
 * </ul>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * // Apply to all data-modifying methods in the DAO
 * @CacheResult
 * @RefreshCache
 * public interface UserDao extends CrudDao<User, Long> {
 *     @Select("SELECT * FROM users WHERE id = :id")
 *     User findById(long id); // Results are cached
 *     
 *     @Update("UPDATE users SET name = :name WHERE id = :id")
 *     int updateName(long id, String name); // Triggers cache refresh
 * }
 * 
 * // Selective cache refresh
 * @CacheResult
 * @RefreshCache(filter = {"update.*", "delete.*"})
 * public interface ProductDao extends CrudDao<Product, Long> {
 *     List<Product> findAll(); // Cached
 *     void updatePrice(long id, BigDecimal price); // Refreshes cache
 *     void insertLog(String message); // Does not refresh cache
 * }
 * 
 * // Disable refresh for specific method
 * @RefreshCache
 * public interface OrderDao extends CrudDao<Order, Long> {
 *     @RefreshCache(disabled = true)
 *     void updateLastAccessTime(long orderId); // Won't refresh cache
 * }
 * }</pre>
 * 
 * @see CacheResult
 * @see Cache
 */
@Beta
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD, ElementType.TYPE })
public @interface RefreshCache {

    /**
     * Specifies whether cache refresh is disabled for the annotated element.
     * 
     * <p>This is useful for:</p>
     * <ul>
     *   <li>Excluding specific methods from triggering cache refresh</li>
     *   <li>Operations that don't affect cached query results (e.g., audit logging)</li>
     *   <li>High-frequency updates where immediate consistency isn't required</li>
     * </ul>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * @RefreshCache
     * public interface UserDao {
     *     @Update("UPDATE users SET last_seen = NOW() WHERE id = :id")
     *     @RefreshCache(disabled = true) // Don't refresh cache for this frequent update
     *     void updateLastSeen(long userId);
     *     
     *     @Update("UPDATE users SET email = :email WHERE id = :id")
     *     void updateEmail(long userId, String email); // Will refresh cache
     * }
     * }</pre>
     * 
     * @return {@code true} to disable cache refresh, {@code false} to enable it (default)
     */
    boolean disabled() default false;

    /**
     * Specifies filter patterns for methods when the annotation is applied at the class level.
     * Only methods whose names match at least one of these patterns will trigger cache refresh.
     * 
     * <p>The patterns support:</p>
     * <ul>
     *   <li>Case-insensitive substring matching</li>
     *   <li>Regular expression matching</li>
     * </ul>
     * 
     * <p>Multiple patterns are combined with OR logic - a method needs to match only one pattern.
     * This filter is ignored when the annotation is applied at the method level.</p>
     * 
     * <p>The default filter includes common data-modifying method names to automatically
     * refresh cache after operations that typically change data.</p>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * @RefreshCache(filter = {"save.*", "update.*", "delete.*", "remove.*"})
     * public interface CustomerDao {
     *     void saveCustomer(Customer c);      // Triggers refresh
     *     void updateAddress(long id, Address a); // Triggers refresh
     *     void removeCustomer(long id);       // Triggers refresh
     *     Customer findById(long id);         // Does not trigger refresh
     * }
     * }</pre>
     *
     * @return array of filter patterns for method names that should trigger cache refresh
     */
    String[] filter() default { "update", "delete", "deleteById", "insert", "save", "batchUpdate", "batchDelete", "batchDeleteByIds", "batchInsert",
            "batchSave", "batchUpsert", "upsert", "execute" };
}