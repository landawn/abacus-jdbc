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
 * Triggers cache invalidation after matching DAO methods complete.
 *
 * <p>Method-level usage affects only the annotated method. Type-level usage applies to
 * methods whose names match {@link #filter()} by method-name prefix or by a full
 * regular-expression match.</p>
 *
 * @see Cache
 * @see CacheResult
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
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @RefreshCache
     * public interface UserDao {
     *     @Query("UPDATE users SET last_seen = NOW() WHERE id = :id")
     *     @RefreshCache(disabled = true) // Don't refresh cache for this frequent update
     *     void updateLastSeen(long userId);
     *     
     *     @Query("UPDATE users SET email = :email WHERE id = :id")
     *     void updateEmail(long userId, String email);   // Will refresh cache
     * }
     * }</pre>
     * 
     * @return {@code true} to disable cache refresh, {@code false} to enable it (default)
     */
    boolean disabled() default false;

    /**
     * Specifies the type-level method-name filter.
     *
     * <p>Each entry matches when the method name starts with that entry or when the entry
     * matches the full method name as a regular expression. This filter is ignored for
     * method-level usage.</p>
     *
     * @return array of filter patterns for method names that should trigger cache refresh
     */
    String[] filter() default { "update", "delete", "deleteById", "insert", "save", "add", "remove", "upsert", "batchUpdate", "batchDelete", "batchDeleteByIds",
            "batchInsert", "batchSave", "batchAdd", "batchRemove", "batchUpsert", "execute" };
}
