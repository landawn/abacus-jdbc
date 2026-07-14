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

/**
 * Controls whether query results should be fetched based on the entity class properties.
 * This annotation may only be applied to methods whose return type is {@code Dataset}.
 *
 * <p>When enabled (default), only columns that correspond to properties in the entity class
 * will be retained in the returned {@code Dataset}. This keeps the result shape aligned with the
 * entity and avoids materializing unrelated columns; it does not rewrite the SQL projection or
 * prevent the database/driver from returning those columns.</p>
 *
 * <p>When disabled, all columns from the query result will be fetched, regardless of whether
 * they have corresponding properties in the entity class.</p>
 *
 * <p>A method-level {@code @FetchColumnByEntityClass} always takes precedence over the DAO-level
 * default configured through {@link DaoConfig#fetchColumnByEntityClassForDatasetQuery()}; when no
 * method-level annotation is present, that DAO-level default (itself {@code true} unless overridden)
 * applies.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long, UserDao> {
 *     // Only fetch columns that match User class properties
 *     @Query("SELECT u.*, d.department_name FROM users u JOIN departments d ON u.dept_id = d.id")
 *     @FetchColumnByEntityClass(true)  // This is default, can be omitted
 *     Dataset queryUsersWithDepartment();
 *
 *     // Fetch all columns from the query, including department_name
 *     @Query("SELECT u.*, d.department_name FROM users u JOIN departments d ON u.dept_id = d.id")
 *     @FetchColumnByEntityClass(false)
 *     Dataset queryAllUserData();
 *
 *     // Assuming User class has properties: id, name, email, deptId
 *     // First method returns: id, name, email, deptId (department_name is excluded)
 *     // Second method returns: id, name, email, deptId, department_name
 * }
 * }</pre>
 *
 * <p>This annotation is particularly useful when:</p>
 * <ul>
 *   <li>Working with complex joins that return extra columns</li>
 *   <li>You need to fetch calculated columns or aggregations not in the entity</li>
 *   <li>Keeping the returned {@code Dataset} limited to entity-mapped columns</li>
 * </ul>
 *
 * <p>Note: This annotation may only be applied to methods whose return type is {@code Dataset}.
 * Applying it to a method with any other return type causes an {@code IllegalArgumentException}
 * to be thrown when the DAO is initialized.</p>
 *
 * @see DaoConfig#fetchColumnByEntityClassForDatasetQuery()
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD })
public @interface FetchColumnByEntityClass {

    /**
     * Specifies whether to fetch only columns that match entity class properties.
     *
     * <p>When {@code true} (default):</p>
     * <ul>
     *   <li>Only columns with matching properties in the entity class are fetched</li>
     *   <li>Avoids materializing unrelated columns in the returned {@code Dataset}</li>
     *   <li>Results in cleaner Dataset with only relevant columns</li>
     * </ul>
     *
     * <p>When {@code false}:</p>
     * <ul>
     *   <li>All columns from the query result are fetched</li>
     *   <li>Useful when you need additional calculated or joined columns</li>
     *   <li>May include columns that don't map to entity properties</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Entity class
     * public class User {
     *     private Long id;
     *     private String name;
     *     private String email;
     *     // Getters and setters
     * }
     *
     * // DAO method
     * @Query("SELECT id, name, email, COUNT(*) as login_count FROM users GROUP BY id, name, email")
     * @FetchColumnByEntityClass(false)  // Need to fetch login_count
     * Dataset getUserLoginStats();
     * }</pre>
     *
     * @return {@code true} to fetch only entity columns, {@code false} to fetch all columns; defaults to
     *         {@code true}, and a value declared here overrides the DAO-level
     *         {@link DaoConfig#fetchColumnByEntityClassForDatasetQuery()} default
     */
    boolean value() default true;
}
