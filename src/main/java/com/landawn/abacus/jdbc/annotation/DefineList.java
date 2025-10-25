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
import java.util.Collection;

/**
 * Defines a named SQL template variable from a collection or array parameter.
 * This annotation converts the elements of a collection or array into a comma-separated string
 * that can be used to dynamically construct SQL queries.
 * 
 * <p>Unlike {@link BindList} which binds values as parameters, {@code DefineList} performs
 * string substitution in the SQL template. This is useful for dynamic SQL construction where
 * the structure of the query itself needs to change based on input.</p>
 * 
 * <p><strong>Security Warning:</strong> Since this performs direct string substitution,
 * be extremely careful to validate and sanitize input to prevent SQL injection attacks.
 * Only use this with trusted input or when dynamic SQL structure is absolutely necessary.</p>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long> {
 *     // Dynamic column selection
 *     @Query("SELECT {columns} FROM users WHERE id = :id")
 *     Map<String, Object> findColumnsById(
 *         @DefineList("columns") List<String> columns,
 *         @Bind("id") long id
 *     );
 *     
 *     // Usage: dao.findColumnsById(Arrays.asList("id", "name", "email"), 123)
 *     // Generates: SELECT id, name, email FROM users WHERE id = ?
 *     
 *     // Dynamic table names (be very careful with this!)
 *     @Query("SELECT * FROM {tables} WHERE status = :status")
 *     List<Map<String, Object>> findFromTables(
 *         @DefineList("tables") String[] tables,
 *         @Bind("status") String status
 *     );
 *     
 *     // Usage: dao.findFromTables(new String[] {"users", "admins"}, "active")
 *     // Generates: SELECT * FROM users, admins WHERE status = ?
 * }
 * }</pre>
 * 
 * <p>The annotation can be used with:</p>
 * <ul>
 *   <li>Arrays of any type</li>
 *   <li>{@link Collection} implementations (List, Set, etc.)</li>
 * </ul>
 * 
 * <p>The elements are converted to strings using their {@code toString()} method
 * and joined with commas. Null elements are converted to the string "null".</p>
 *
 * @see Define
 * @see BindList
 * @since 0.8
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.PARAMETER })
public @interface DefineList {

    /**
     * Specifies the name of the SQL template variable to be replaced.
     * If not specified (empty string), the parameter name will be used as the variable name.
     * 
     * <p>The variable should be referenced in the SQL template using curly braces: {@code {variableName}}</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Explicit name
     * @Query("SELECT {cols} FROM users")
     * List<User> findWithColumns(@DefineList("cols") List<String> columnList);
     * 
     * // Using parameter name (when value is not specified)
     * @Query("SELECT {columns} FROM users")
     * List<User> findWithColumns(@DefineList List<String> columns);
     * }</pre>
     *
     * @return the name of the SQL template variable, or empty string to use parameter name
     */
    String value() default "";
}