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
 * Controls whether column values of a {@code Dataset} result are read using the property types of the DAO's
 * entity class. This annotation may only be applied to methods whose return type is {@code Dataset}.
 *
 * <p>When enabled (default), each column whose label matches an entity property (directly, through the
 * entity's column-name mapping, or through a {@link PrefixFieldMapping @PrefixFieldMapping} prefix) is read
 * with that property's type, for example as an {@code enum}, {@code LocalDate} or custom type instead of the
 * driver's default Java object. Columns without a matching property are still returned and are read with the
 * default column-value mapping. The SQL text is never rewritten, and every column keeps its SQL column label.</p>
 *
 * <p>When disabled, every column is read with the default column-value mapping, and
 * {@link PrefixFieldMapping @PrefixFieldMapping} is not supported.</p>
 *
 * <p>A method-level {@code @FetchColumnByEntityClass} always takes precedence over the DAO-level
 * default configured through {@link DaoConfig#fetchColumnByEntityClassForDatasetQuery()}; when no
 * method-level annotation is present, that DAO-level default (itself {@code true} unless overridden)
 * applies.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long, UserDao> {
 *     // Read user columns with the User property types
 *     @Query("SELECT u.*, d.department_name FROM users u JOIN departments d ON u.dept_id = d.id")
 *     @FetchColumnByEntityClass(true)  // This is default, can be omitted
 *     Dataset queryUsersWithDepartment() throws SQLException;
 *
 *     // Read every column with the default column-value mapping
 *     @Query("SELECT u.*, d.department_name FROM users u JOIN departments d ON u.dept_id = d.id")
 *     @FetchColumnByEntityClass(false)
 *     Dataset queryAllUserData() throws SQLException;
 *
 *     // Assuming User class has properties: id, name, email, deptId
 *     // Both methods return the SQL column labels: id, name, email, dept_id, department_name.
 *     // The first reads id, name, email and dept_id with the User property types;
 *     // department_name has no matching property and uses the default mapping.
 * }
 * }</pre>
 *
 * <p>This annotation is particularly useful when:</p>
 * <ul>
 *   <li>Entity properties use types that the default mapping does not produce (enums, {@code java.time} types, custom types)</li>
 *   <li>Prefixed join columns should resolve to nested properties through {@link PrefixFieldMapping @PrefixFieldMapping}</li>
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
     * Specifies whether column values are read using the property types of the entity class.
     *
     * <p>When {@code true} (default):</p>
     * <ul>
     *   <li>Columns that match entity properties are read with those properties' types</li>
     *   <li>Columns without a matching property are still returned, read with the default mapping</li>
     *   <li>{@link PrefixFieldMapping @PrefixFieldMapping} can resolve prefixed column labels to nested properties</li>
     * </ul>
     *
     * <p>When {@code false}:</p>
     * <ul>
     *   <li>Every column is read with the default column-value mapping</li>
     *   <li>{@link PrefixFieldMapping @PrefixFieldMapping} is not supported</li>
     * </ul>
     *
     * <p>In both cases the {@code Dataset} contains every column produced by the SQL, under its SQL column label.</p>
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
     * @FetchColumnByEntityClass(false)  // Use the default column-value mapping for every column
     * Dataset getUserLoginStats() throws SQLException;
     * }</pre>
     *
     * @return {@code true} to read entity-mapped columns with the entity property types, {@code false} to use the
     *         default column-value mapping for every column; defaults to
     *         {@code true}, and a value declared here overrides the DAO-level
     *         {@link DaoConfig#fetchColumnByEntityClassForDatasetQuery()} default
     */
    boolean value() default true;
}
