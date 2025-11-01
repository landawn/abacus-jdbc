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
 * <p><strong>Note:</strong> This feature is marked as {@code @Beta} and should be used with caution.
 * Implementing cache at the Data Access Layer (DAL) can lead to data consistency issues if not
 * managed properly. Consider whether caching should be implemented at a higher layer instead.</p>
 * 
 * <p>When applied to a DAO interface, all eligible query methods will have their results cached
 * according to the specified configuration. The cache key is automatically generated based on
 * the method name and parameters.</p>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * @Cache(capacity = 1000, evictDelay = 3600000) // 1 hour eviction
 * public interface CountryDao extends CrudDao<Country, String> {
 *     // Results will be cached automatically
 *     @Query("SELECT * FROM countries WHERE continent = :continent")
 *     List<Country> findByContinent(@Bind("continent") String continent);
 * }
 * 
 * // Using custom cache implementation
 * @Cache(capacity = 500, evictDelay = 1800000, impl = MyCustomDaoCache.class)
 * public interface ConfigDao extends CrudDao<Config, Long> {
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
     * When the cache reaches this capacity, the least recently used entries
     * will be evicted to make room for new entries.
     * 
     * <p>The default value is {@link JdbcUtil#DEFAULT_BATCH_SIZE}, which is
     * typically suitable for most use cases. For DAOs handling large amounts
     * of frequently accessed data, consider increasing this value.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Cache(capacity = 5000) // Large cache for frequently accessed data
     * public interface ProductDao extends CrudDao<Product, Long> {
     *     // Methods here
     * }
     * }</pre>
     *
     * @return the maximum number of cache entries
     */
    int capacity() default JdbcUtil.DEFAULT_BATCH_SIZE;

    /**
     * Specifies the time delay (in milliseconds) after which cached entries will be evicted.
     * This is the maximum time an entry can remain in the cache before being automatically removed,
     * regardless of whether it has been accessed.
     * 
     * <p>The default value is {@link JdbcUtil#DEFAULT_CACHE_EVICT_DELAY}. Set this based on
     * how frequently the underlying data changes. For static reference data, use longer delays;
     * for more dynamic data, use shorter delays.</p>
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
     * @Cache(evictDelay = 86400000) // 24-hour cache for static data
     * public interface CurrencyDao extends CrudDao<Currency, String> {
     *     // Methods here
     * }
     * }</pre>
     *
     * @return the eviction delay in milliseconds
     */
    long evictDelay() default JdbcUtil.DEFAULT_CACHE_EVICT_DELAY;

    /**
     * Specifies the implementation class for the DAO cache.
     * The implementation must extend {@link DaoCache} and have a public constructor
     * that accepts two parameters: {@code (int capacity, long evictDelay)}.
     * 
     * <p>By default, {@link Jdbc.DefaultDaoCache} is used, which provides a
     * thread-safe LRU (Least Recently Used) cache implementation. You can provide
     * a custom implementation for specialized caching requirements.</p>
     * 
     * <p>Example custom cache implementation:</p>
     * <pre>{@code
     * public class MyCustomDaoCache extends DaoCache {
     *     public MyCustomDaoCache(int capacity, long evictDelay) {
     *         super(capacity, evictDelay);
     *         // Custom initialization
     *     }
     *     
     *     @Override
     *     public void put(String key, Object value) {
     *         // Custom caching logic
     *         super.put(key, value);
     *     }
     * }
     * 
     * // Usage
     * @Cache(impl = MyCustomDaoCache.class)
     * public interface UserDao extends CrudDao<User, Long> {
     *     // Methods here
     * }
     * }</pre>
     *
     * @return the cache implementation class
     */
    Class<? extends DaoCache> impl() default Jdbc.DefaultDaoCache.class; //NOSONAR
}