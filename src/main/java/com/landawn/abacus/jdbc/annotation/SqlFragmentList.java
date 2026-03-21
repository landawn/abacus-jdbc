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
     * Specifies the name of the query template variable to be replaced.
     * If not specified (empty string), the parameter name will be used.
     * 
     * <p>The variable should be referenced in the query template using curly braces: {@code {variableName}}</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Explicit name
     * @Query("SELECT {cols} FROM users")
     * List<User> findWithColumns(@SqlFragmentList("cols") List<String> columnList);
     * 
     * // Using parameter name (when value is not specified)
     * @Query("SELECT {columns} FROM users")
     * List<User> findWithColumns(@SqlFragmentList List<String> columns);
     * }</pre>
     *
     * @return the name of the query template variable, or empty string if using the parameter name
     */
    String value() default "";
}
