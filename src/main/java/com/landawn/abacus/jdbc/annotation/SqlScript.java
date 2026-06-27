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

/**
 * Marks a {@code static final String} field as a named SQL script — an inline alternative to
 * pulling SQL from an external mapper via {@link SqlSource}.
 *
 * <p>The DAO proxy ({@code DaoImpl}) scans the DAO interface for fields carrying this annotation
 * at build time and registers each one as a named entry. Methods annotated with
 * {@link Query @Query(id = "name")} then resolve their SQL by looking up that name. This lets
 * you keep large or reused SQL definitions next to the DAO without sprinkling multi-line
 * {@code @Query(value = "...")} strings throughout the interface.</p>
 *
 * <p><b>Constraints (enforced at DAO initialization, otherwise {@code IllegalArgumentException}):</b></p>
 * <ul>
 *   <li>The annotated field must be declared {@code static final String} on the DAO interface
 *       (or a nested class within it).</li>
 *   <li>The resolved id (either {@link #id()} when non-empty, or the field name) must be a
 *       non-empty, whitespace-free Java identifier.</li>
 *   <li>No two annotated fields may resolve to the same id.</li>
 *   <li>The id must not collide with any entry already loaded by {@link SqlSource}.</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long, UserDao> {
 *
 *     @SqlScript
 *     String sql_listUserWithBiggerId =
 *             PSC.selectFrom(User.class).where(Filters.gt("id")).sql();
 *
 *     @SqlScript(id = "sql_softDeleteById")          // explicit id overrides field name
 *     String softDeleteByIdSql =
 *             "UPDATE user SET deleted = 1, deleted_at = NOW() WHERE id = :id";
 *
 *     @Query(id = "sql_listUserWithBiggerId")
 *     List<User> listUserWithBiggerId(@Bind("id") long minId);
 *
 *     @Query(id = "sql_softDeleteById")
 *     int softDeleteById(@Bind("id") long id);
 * }
 * }</pre>
 *
 * @see Query
 * @see SqlSource
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SqlScript {

    /**
     * Supplies an optional identifier that overrides the annotated field name when the SQL is
     * registered. When left empty, the declaration name (for example {@code sql_listUserWithBiggerId})
     * becomes the key. Setting a custom id makes it possible to share the same SQL across
     * differently named constants or to shorten the token referenced from {@link Query}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @SqlScript(id = "sql_listUserWithBiggerId")
     * static final String listUserWithBiggerId =
     *         PSC.selectFrom(User.class).where(Filters.gt("id")).sql();
     * }</pre>
     *
     * <p>When supplied, the id must be a non-empty, whitespace-free Java identifier that is unique
     * among all {@link SqlScript} fields on the DAO and does not collide with any id loaded via
     * {@link SqlSource}; otherwise DAO initialization fails with {@code IllegalArgumentException}.</p>
     *
     * @return the identifier used by {@link Query#id()}; empty means the annotated field name is used
     */
    String id() default "";
}
