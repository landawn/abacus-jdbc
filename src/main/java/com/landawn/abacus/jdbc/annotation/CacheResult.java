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
import com.landawn.abacus.jdbc.JdbcUtil;

/**
 * Enables method-level result caching for DAO query methods.
 * This annotation provides fine-grained control over caching behavior, including
 * time-to-live, idle timeout, size restrictions, and serialization strategies.
 * 
 * <p><strong>Note:</strong> This feature is marked as {@code @Beta} and may undergo changes.
 * Consider carefully whether caching at the DAO layer is appropriate for your use case,
 * as it can lead to stale data issues if not managed properly.</p>
 * 
 * <p>The cache key is automatically generated based on the method name and parameters.
 * Results are cached after the first execution and returned from cache for subsequent
 * calls with the same parameters until the cache expires or is invalidated.</p>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long> {
 *     // Cache individual user lookups for 30 minutes
 *     @CacheResult(liveTime = 1800000, maxIdleTime = 600000)
 *     @Query("SELECT * FROM users WHERE id = :id")
 *     User findById(@Bind("id") Long id);
 *     
 *     // Cache list results with size restrictions
 *     @CacheResult(liveTime = 300000, minSize = 1, maxSize = 100)
 *     @Query("SELECT * FROM users WHERE status = :status")
 *     List<User> findByStatus(@Bind("status") String status);
 *     
 *     // Use Kryo serialization for complex objects
 *     @CacheResult(liveTime = 3600000, transfer = "kryo")
 *     @Query("SELECT * FROM user_profiles WHERE user_id = :userId")
 *     UserProfile getProfile(@Bind("userId") Long userId);
 *     
 *     // Apply caching to all matching methods at type level
 *     @CacheResult(liveTime = 600000, filter = {"find.*", "get.*"})
 *     public interface ProductDao extends CrudDao<Product, Long> {
 *         // All find* and get* methods will be cached
 *     }
 * }
 * }</pre>
 * 
 * <p>Cache invalidation strategies:</p>
 * <ul>
 *   <li>Time-based: Entries expire after {@code liveTime} milliseconds</li>
 *   <li>Idle-based: Entries expire if not accessed for {@code maxIdleTime}</li>
 *   <li>Size-based: Only cache results within size bounds (for collections)</li>
 *   <li>Manual: Use {@link RefreshCache} annotation on update methods</li>
 * </ul>
 *
 * @see Cache
 * @see RefreshCache
 * @see <a href="https://github.com/EsotericSoftware/kryo">Kryo Serialization</a>
 */
@Beta
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD, ElementType.TYPE })
public @interface CacheResult {

    /**
     * Disables caching when set to {@code true}.
     * This allows temporarily disabling cache without removing the annotation,
     * useful for debugging or testing.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @CacheResult(disabled = true)  // Temporarily disable for debugging
     * @Query("SELECT * FROM users WHERE id = :id")
     * User findById(@Bind("id") Long id);
     * }</pre>
     *
     * @return {@code true} to disable caching, {@code false} to enable
     */
    boolean disabled() default false;

    /**
     * Specifies the maximum time (in milliseconds) a cached entry can live.
     * After this time expires, the entry is removed from cache and the next
     * request will execute the query again.
     * 
     * <p>Common time values:</p>
     * <ul>
     *   <li>5 minutes: {@code 300000}</li>
     *   <li>30 minutes: {@code 1800000}</li>
     *   <li>1 hour: {@code 3600000}</li>
     *   <li>24 hours: {@code 86400000}</li>
     * </ul>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Cache for 15 minutes
     * @CacheResult(liveTime = 900000)
     * @Query("SELECT * FROM configurations WHERE key = :key")
     * Config getConfig(@Bind("key") String key);
     * }</pre>
     *
     * @return the maximum cache entry lifetime in milliseconds
     */
    long liveTime() default JdbcUtil.DEFAULT_CACHE_LIVE_TIME;

    /**
     * Specifies the maximum idle time (in milliseconds) for a cached entry.
     * If an entry is not accessed within this time, it expires and is removed.
     * This is useful for frequently accessed data that should expire if unused.
     * 
     * <p>The entry expires when either {@code liveTime} or {@code maxIdleTime}
     * is exceeded, whichever comes first.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Expire if not accessed for 10 minutes
     * @CacheResult(liveTime = 3600000, maxIdleTime = 600000)
     * @Query("SELECT * FROM user_sessions WHERE token = :token")
     * UserSession getSession(@Bind("token") String token);
     * }</pre>
     *
     * @return the maximum idle time in milliseconds
     */
    long maxIdleTime() default JdbcUtil.DEFAULT_CACHE_MAX_IDLE_TIME;

    /**
     * Specifies the minimum size requirement for caching collection results.
     * Results with fewer elements than this value will not be cached.
     * Only applies to methods returning {@code Collection} or {@code Dataset}.
     * 
     * <p>This is useful to avoid caching overhead for very small result sets
     * that are cheap to query.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Only cache if result has at least 10 items
     * @CacheResult(minSize = 10)
     * @Query("SELECT * FROM products WHERE category = :category")
     * List<Product> findByCategory(@Bind("category") String category);
     * }</pre>
     *
     * @return the minimum collection size to cache, 0 means no minimum
     */
    int minSize() default 0;

    /**
     * Specifies the maximum size limit for caching collection results.
     * Results with more elements than this value will not be cached.
     * Only applies to methods returning {@code Collection} or {@code Dataset}.
     * 
     * <p>This prevents memory issues from caching very large result sets
     * and ensures predictable memory usage.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Don't cache if result has more than 1000 items
     * @CacheResult(maxSize = 1000)
     * @Query("SELECT * FROM orders WHERE date >= :startDate")
     * List<Order> findOrdersSince(@Bind("startDate") Date date);
     * }</pre>
     *
     * @return the maximum collection size to cache
     */
    int maxSize() default Integer.MAX_VALUE;

    /**
     * Specifies the serialization strategy for cache storage and retrieval.
     * This determines how objects are copied when stored in or retrieved from cache.
     * 
     * <p>Available options:</p>
     * <ul>
     *   <li>{@code "none"} (default) - No serialization, stores direct references</li>
     *   <li>{@code "kryo"} - Uses Kryo for fast binary serialization</li>
     *   <li>{@code "json"} - Uses JSON for human-readable serialization</li>
     * </ul>
     * 
     * <p>Serialization provides isolation between cached objects and application code,
     * preventing unintended modifications to cached data.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Use Kryo for deep copying complex objects
     * @CacheResult(transfer = "kryo")
     * @Query("SELECT * FROM user_profiles WHERE user_id = :userId")
     * UserProfile getComplexProfile(@Bind("userId") Long userId);
     * 
     * // Use JSON for debugging/logging friendly format
     * @CacheResult(transfer = "json")
     * @Query("SELECT * FROM audit_logs WHERE id = :id")
     * AuditLog getAuditLog(@Bind("id") Long id);
     * }</pre>
     *
     * @return the serialization strategy name
     * @see <a href="https://github.com/EsotericSoftware/kryo">Kryo Serialization</a>
     */
    String transfer() default "none";

    /**
     * Specifies method name patterns to apply caching when used at type level.
     * Only methods matching these patterns will have caching enabled.
     * 
     * <p>Patterns can be:</p>
     * <ul>
     *   <li>Exact method names (case-insensitive contains match)</li>
     *   <li>Regular expressions</li>
     *   <li>Multiple patterns are joined with OR logic</li>
     * </ul>
     * 
     * <p>This attribute is ignored when the annotation is applied at method level.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @CacheResult(liveTime = 600000, 
     *              filter = {"find.*", "get.*", "load.*", "fetch.*"})
     * public interface UserDao extends CrudDao<User, Long> {
     *     // These methods will be cached
     *     User findById(Long id);
     *     List<User> findByStatus(String status);
     *     User getByEmail(String email);
     *     
     *     // These methods will NOT be cached
     *     void updateUser(User user);
     *     int deleteById(Long id);
     * }
     * }</pre>
     *
     * @return array of method name patterns to cache
     */
    String[] filter() default { "query", "queryFor", "list", "get", "batchGet", "find", "findFirst", "findOnlyOne", "exist", "notExist", "count" };
}