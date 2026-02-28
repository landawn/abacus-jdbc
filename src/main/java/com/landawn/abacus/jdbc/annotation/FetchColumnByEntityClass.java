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
 * Controls whether query results should be fetched based on the entity class properties
 * when the return type is a Dataset or similar structure.
 * 
 * <p>When enabled (default), only columns that correspond to properties in the entity class
 * will be fetched from the result set. This provides better performance and cleaner results
 * by avoiding unnecessary data retrieval.</p>
 * 
 * <p>When disabled, all columns from the query result will be fetched, regardless of whether
 * they have corresponding properties in the entity class.</p>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long, SQLBuilder.PSC, UserDao> {
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
 *   <li>Optimizing performance by fetching only required columns</li>
 * </ul>
 * 
 * <p>Note: This annotation only affects methods that return Dataset or similar collection
 * types. It has no effect on methods that return entity objects directly.</p>
 *
 * @see DaoConfig#fetchColumnByEntityClassForDatasetQuery()
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD })
public @interface FetchColumnByEntityClass {

    /**
     * Specifies whether to fetch only columns that match entity class properties.
     * 
     * <p>When {@code true} (default):</p>
     * <ul>
     *   <li>Only columns with matching properties in the entity class are fetched</li>
     *   <li>Provides better performance by reducing data transfer</li>
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
     * @return {@code true} to fetch only entity columns, {@code false} to fetch all columns
     */
    boolean value() default true;
}
