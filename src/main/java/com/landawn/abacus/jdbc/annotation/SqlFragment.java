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
     * Specifies the name of the query template variable that this parameter replaces.
     * If empty (the default), the actual method parameter name is used; this requires compiling
     * with the {@code -parameters} javac flag, otherwise initialization fails with
     * {@code UnsupportedOperationException}.
     *
     * <p>Template variables are referenced in the SQL using curly braces: {@code {variableName}}.</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * @Query("SELECT * FROM {schema}.{table} WHERE id = :id")
     * User findById(
     *     @SqlFragment("schema") String schemaName,
     *     @SqlFragment("table") String tableName,
     *     @Bind("id") long id
     * );
     * }</pre>
     *
     * <p>Using the method parameter name (requires {@code -parameters}):</p>
     * <pre>{@code
     * @Query("SELECT {columns} FROM users")
     * List<Map<String, Object>> findWithColumns(@SqlFragment String columns);
     * // The parameter name "columns" is used as the template-variable name
     * }</pre>
     *
     * @return the template-variable name; empty means use the method parameter name (requires {@code -parameters})
     */
    String value() default "";
}
