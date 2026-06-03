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
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.jdbc.Jdbc.DaoCache;
import com.landawn.abacus.jdbc.JdbcUtil;

/**
 * Enables caching at the DAO level for database query results.
 * This annotation is typically used for DAOs that interact with static or rarely-changing tables,
 * where caching can significantly improve performance by reducing database queries.
 *
 * <p><strong>Note:</strong> This feature is marked as {@code @Beta} and may change in future versions.</p>
 *
 * <p>Implementing cache at the Data Access Layer (DAL) can lead to data consistency issues if not
 * managed properly. Consider whether caching should be implemented at a higher layer instead.</p>
 *
 * <p><strong>What {@code @Cache} does:</strong> {@code @Cache} is a type-level annotation that only
 * configures the cache pool (capacity, eviction sweep interval, and implementation) shared by the DAO.
 * It does not, by itself, cache any method result; methods must opt in to caching via
 * {@link CacheResult @CacheResult}, and cached entries are invalidated/refreshed via
 * {@link RefreshCache @RefreshCache}.</p>
 *
 * <p><strong>Restriction (applies to the method-result annotations, not to {@code @Cache}):</strong>
 * {@code @CacheResult} and {@code @RefreshCache} (whether declared at the type or method level) are only
 * honored on {@code NoUpdateDao}/{@code UncheckedNoUpdateDao} subtypes. Using either annotation on a DAO
 * that supports updates/deletes fails with {@code UnsupportedOperationException} at initialization time.
 * {@code @Cache} itself carries no such DAO-type restriction.</p>
 *
 * <p>When applied to such a DAO interface, methods annotated with {@link CacheResult} (or eligible
 * methods when {@code @CacheResult} is applied at the type level) will have their results cached
 * according to the specified configuration. The cache key is automatically derived from the
 * fully-qualified method name and the serialized parameters.</p>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * @Cache(capacity = 1000, evictDelay = 60000) // Run eviction sweep every 60 seconds
 * public interface CountryDao extends NoUpdateCrudDao<Country, String, SqlBuilder.PSC, CountryDao> {
 *     // Results will be cached when annotated with @CacheResult
 *     @CacheResult
 *     @Query("SELECT * FROM countries WHERE continent = :continent")
 *     List<Country> findByContinent(@Bind("continent") String continent);
 * }
 *
 * // Using custom cache implementation
 * @Cache(capacity = 500, evictDelay = 30000, impl = MyCustomDaoCache.class)
 * public interface ConfigDao extends ReadOnlyDao<Config, SqlBuilder.PSC, ConfigDao> {
 *     // Query results cached with custom implementation
 * }
 * }</pre>
 *
 * @see CacheResult
 * @see RefreshCache
 * @see DaoCache
 */
@Beta
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.TYPE })
public @interface Cache {

    /**
     * Specifies the maximum number of entries the cache can hold.
     * When the cache is at capacity, the underlying pool's eviction policy determines
     * which entries are removed to make room for new ones (the default
     * {@link Jdbc.DefaultDaoCache} relies on the {@code KeyedObjectPool}'s default policy
     * combined with TTL/idle-time-based eviction; see {@link CacheResult#liveTime()} and
     * {@link CacheResult#maxIdleTime()}).
     *
     * <p>The default value is {@link JdbcUtil#DEFAULT_CACHE_CAPACITY} (1000 entries), which is
     * typically suitable for most use cases. For DAOs handling large amounts
     * of frequently accessed data, consider increasing this value.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Cache(capacity = 5000) // Large cache for frequently accessed data
     * public interface ProductDao extends NoUpdateCrudDao<Product, Long, SqlBuilder.PSC, ProductDao> {
     *     // Methods here
     * }
     * }</pre>
     *
     * @return the maximum number of cache entries
     */
    int capacity() default JdbcUtil.DEFAULT_CACHE_CAPACITY;

    /**
     * Specifies the interval (in milliseconds) at which the background eviction scheduler
     * runs to remove expired entries from the cache. This is <em>not</em> the per-entry
     * time-to-live; entry expiration is controlled by {@link CacheResult#liveTime()} and
     * {@link CacheResult#maxIdleTime()} when the {@link CacheResult} annotation is used on
     * individual methods.
     *
     * <p>The default value is {@link JdbcUtil#DEFAULT_CACHE_EVICT_DELAY} (3 seconds).
     * Smaller values cause more frequent sweeps (lower memory footprint, higher CPU cost);
     * larger values reduce sweep overhead at the cost of holding expired entries longer.</p>
     *
     * <p>Common interval values:</p>
     * <ul>
     *   <li>1 second: {@code 1000}</li>
     *   <li>3 seconds (default): {@code 3000}</li>
     *   <li>30 seconds: {@code 30000}</li>
     *   <li>1 minute: {@code 60000}</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Cache(evictDelay = 60000) // Sweep expired entries every 60 seconds
     * public interface CurrencyDao extends ReadOnlyDao<Currency, SqlBuilder.PSC, CurrencyDao> {
     *     // Methods here
     * }
     * }</pre>
     *
     * @return the interval in milliseconds between eviction sweeps
     */
    long evictDelay() default JdbcUtil.DEFAULT_CACHE_EVICT_DELAY;

    /**
     * Specifies the implementation class for the DAO cache.
     * The implementation must implement {@link DaoCache} and have a public constructor
     * that accepts two parameters: {@code (int capacity, long evictDelay)}.
     *
     * <p>By default, {@link Jdbc.DefaultDaoCache} is used, which is backed by a
     * keyed object pool with TTL and idle-time-based eviction. You can provide
     * a custom implementation for specialized caching requirements.</p>
     * 
     * <p>Example custom cache implementation:</p>
     * <pre>{@code
     * public class MyCustomDaoCache extends Jdbc.DefaultDaoCache {
     *     public MyCustomDaoCache(int capacity, long evictDelay) {
     *         super(capacity, evictDelay);
     *     }
     *
     *     @Override
     *     public Object get(String defaultCacheKey, Object daoProxy, Object[] args,
     *             Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
     *         // Custom cache retrieval logic
     *         return super.get(defaultCacheKey, daoProxy, args, methodSignature);
     *     }
     * }
     *
     * // Usage
     * @Cache(impl = MyCustomDaoCache.class)
     * public interface UserDao extends NoUpdateCrudDao<User, Long, SqlBuilder.PSC, UserDao> {
     *     // Methods here
     * }
     * }</pre>
     *
     * @return the cache implementation class
     */
    Class<? extends DaoCache> impl() default Jdbc.DefaultDaoCache.class; //NOSONAR
}
