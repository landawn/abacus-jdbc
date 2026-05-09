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
 * Joins a collection or array into a comma-separated SQL fragment.
 *
 * <p>Unlike {@link BindList}, this annotation performs direct SQL text substitution. Use it only
 * for trusted tokens such as validated column lists, table names, or other pre-approved fragments.</p>
 *
 * @see SqlFragment
 * @see BindList
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.PARAMETER })
public @interface SqlFragmentList {

    /**
     * Specifies the name of the query template variable that this collection/array parameter
     * is joined into.
     * If empty (the default), the actual method parameter name is used; this requires compiling
     * with the {@code -parameters} javac flag, otherwise initialization fails with
     * {@code UnsupportedOperationException}.
     *
     * <p>The variable is referenced in the query template using curly braces: {@code {variableName}}</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Explicit name
     * @Query("SELECT {cols} FROM users")
     * List<User> findWithColumns(@SqlFragmentList("cols") List<String> columnList);
     *
     * // Using the method parameter name (requires '-parameters')
     * @Query("SELECT {columns} FROM users")
     * List<User> findWithColumns(@SqlFragmentList List<String> columns);
     * }</pre>
     *
     * @return the template-variable name; empty means use the method parameter name (requires {@code -parameters})
     */
    String value() default "";
}
