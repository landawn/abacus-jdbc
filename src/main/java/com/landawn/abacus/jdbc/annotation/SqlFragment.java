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
 * Performs SQL template substitution for one query fragment.
 *
 * <p>Unlike {@link Bind}, this annotation changes the SQL text itself rather than binding a
 * JDBC value. Only use it with trusted or validated input such as whitelisted identifiers or
 * predefined SQL snippets.</p>
 *
 * @see SqlFragmentList
 * @see Bind
 * @see Query#fragmentContainsNamedParameters()
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.PARAMETER })
public @interface SqlFragment {

    /**
     * Specifies the name of the query template variable to be replaced.
     * If not specified (empty string), the parameter name will be used.
     * 
     * <p>The value can be:</p>
     * <ul>
     *   <li>A simple placeholder name (e.g., "tableName")</li>
     *   <li>A custom placeholder with default value syntax (e.g., "{where -> WHERE active = true}")</li>
     * </ul>
     * 
     * <p>Simple placeholder example:</p>
     * <pre>{@code
     * @Query("SELECT * FROM {schema}.{table} WHERE id = :id")
     * User findById(
     *     @SqlFragment("schema") String schemaName,
     *     @SqlFragment("table") String tableName,
     *     @Bind("id") long id
     * );
     * }</pre>
     * 
     * <p>Custom placeholder with default:</p>
     * <pre>{@code
     * // Custom syntax allows for more complex replacements
     * @Query("SELECT * FROM users {filter -> WHERE active = true}")
     * List<User> findUsers(@SqlFragment("{filter -> WHERE active = true}") String customFilter);
     * 
     * // Usage:
     * findUsers("WHERE role = 'ADMIN'");   // Replaces the entire {filter -> ...} block
     * findUsers(null);                     // Uses the default "WHERE active = true"
     * }</pre>
     * 
     * <p>Using parameter name when value is empty:</p>
     * <pre>{@code
     * @Query("SELECT {columns} FROM users")
     * List<Map<String, Object>> findWithColumns(@SqlFragment String columns);
     * // The parameter name "columns" is used as the placeholder name
     * }</pre>
     *
     * @return the query template variable name, or empty string if using the parameter name
     */
    String value() default "";
}
