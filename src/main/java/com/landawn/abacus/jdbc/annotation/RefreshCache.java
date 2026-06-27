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
 * Triggers invalidation of the DAO's result cache after the annotated method completes. Use it on
 * write-side operations (insert, update, delete, save, etc.) so that subsequent read-side calls
 * marked with {@link CacheResult} re-issue the SQL instead of returning stale results.
 *
 * <p>The DAO proxy ({@code DaoImpl}) collects {@code @RefreshCache} annotations at build time. At
 * runtime, after a matching method finishes (success or failure), the proxy clears the entries
 * in the DAO's {@link Cache cache pool}. A method-level {@code @RefreshCache} can override the
 * type-level one — most importantly, {@code @RefreshCache(enabled = false)} <em>opts a single
 * method back out</em> of the cache-invalidation set declared at the type level.</p>
 *
 * <p><b>Filter semantics (type-level only):</b> each {@link #filter()} entry matches when the
 * method name starts with that entry, or when the entry matches the full method name as a regular
 * expression. The filter is ignored entirely for method-level usage. The default value already
 * covers the common write-method names emitted by the Abacus DAO base interfaces.</p>
 *
 * <p><strong>Note:</strong> Marked {@link Beta} along with {@link Cache} and {@link CacheResult}.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * @Cache(capacity = 1000)
 * @RefreshCache                                   // type-level: defaults cover insert/update/delete/...
 * public interface ProductDao extends NoUpdateCrudDao<Product, Long, ProductDao> {
 *
 *     @CacheResult(enabled = true, liveTime = 600_000)
 *     @Query("SELECT * FROM product WHERE id = :id")
 *     Product findById(@Bind("id") Long id);
 *
 *     @Query("UPDATE product SET price = :price WHERE id = :id")
 *     int updatePrice(@Bind("id") Long id, @Bind("price") BigDecimal price);
 *     // ← falls under the type-level filter; cache is invalidated after each call.
 *
 *     // High-frequency update that is intentionally exempt from cache invalidation.
 *     @RefreshCache(enabled = false)
 *     @Query("UPDATE product SET view_count = view_count + 1 WHERE id = :id")
 *     int incrementViews(@Bind("id") Long id);
 * }
 * }</pre>
 *
 * @see Cache
 * @see CacheResult
 */
@Beta
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD, ElementType.TYPE })
public @interface RefreshCache {

    /**
     * Specifies whether cache refresh is enabled for the annotated element.
     *
     * <p>At the method level, {@code enabled = false} opts that single method back out of the
     * cache-invalidation set declared by a type-level {@code @RefreshCache} (and its {@link #filter()}).</p>
     *
     * <p>This is useful for:</p>
     * <ul>
     *   <li>Excluding specific methods from triggering cache refresh</li>
     *   <li>Operations that don't affect cached query results (e.g., audit logging)</li>
     *   <li>High-frequency updates where immediate consistency isn't required</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @RefreshCache
     * public interface UserDao extends NoUpdateCrudDao<User, Long, UserDao> {
     *     @Query("UPDATE users SET last_seen = NOW() WHERE id = :id")
     *     @RefreshCache(enabled = false) // Don't refresh cache for this frequent update
     *     void updateLastSeen(long userId);
     *
     *     @Query("UPDATE users SET email = :email WHERE id = :id")
     *     void updateEmail(long userId, String email);   // Will refresh cache
     * }
     * }</pre>
     *
     * @return {@code true} (default) to enable cache refresh, {@code false} to disable it.
     */
    boolean enabled() default true;

    /**
     * Specifies the type-level method-name filter.
     *
     * <p>Each entry matches when the method name starts with that entry or when the entry
     * matches the full method name as a regular expression. This filter is ignored for
     * method-level usage.</p>
     *
     * @return array of filter patterns for method names that should trigger cache refresh;
     *         the default targets common write methods such as {@code update}, {@code delete},
     *         {@code deleteById}, {@code insert}, {@code save}, {@code add}, {@code remove},
     *         {@code upsert}, {@code execute} and their {@code batch*} variants
     */
    String[] filter() default { "update", "delete", "deleteById", "insert", "save", "add", "remove", "upsert", "batchUpdate", "batchDelete", "batchDeleteByIds",
            "batchInsert", "batchSave", "batchAdd", "batchRemove", "batchUpsert", "execute" };
}
