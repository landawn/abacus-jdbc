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
 * Joins a collection or array of strings into a comma-separated SQL fragment that replaces the
 * {@code {name}} token in the surrounding {@link Query @Query} SQL. This is the "list" form of
 * {@link SqlFragment}.
 *
 * <p>Despite the {@code List} suffix in its name, this is <em>not</em> a {@code @Repeatable}
 * container (unlike {@code Handlers} or {@code OutParameters}, which hold multiple
 * {@code @Handler}/{@code @OutParameter} instances). It is an independent parameter annotation
 * applied to a single collection/array parameter whose elements are joined into one SQL fragment.</p>
 *
 * <p>Unlike {@link BindList} — which expands a collection into a parenthesized list of JDBC
 * placeholders for an {@code IN} clause — {@code @SqlFragmentList} rewrites the SQL text itself
 * with the comma-joined elements (with no surrounding parentheses and no value binding). Use it
 * for trusted, whitelisted tokens such as column lists, table names, sort keys, or other
 * pre-approved SQL fragments; do not use it for caller-supplied data.</p>
 *
 * <p>The DAO proxy ({@code DaoImpl}) performs these substitutions while assembling the SQL.
 * Empty collections produce an empty string in place of the token (which is usually a SQL syntax
 * error — callers should guard against that case).</p>
 *
 * <p>If the joined fragment itself contains named parameter placeholders (for example, joining
 * elements such as {@code "discount >= :minDiscount"}), set
 * {@link Query#fragmentsContainNamedParameters() @Query(fragmentsContainNamedParameters = true)}
 * so the proxy re-scans the assembled SQL for them.</p>
 *
 * <p><b>Usage Examples:</b></p>
 *
 * <p><b>Caller-selected projection columns:</b></p>
 * <pre>{@code
 * public interface UserDao extends Dao<User, UserDao> {
 *
 *     @Query("SELECT {cols} FROM users WHERE active = TRUE")
 *     List<Map<String, Object>> projectActive(
 *         @SqlFragmentList("cols") List<String> cols);
 *
 *     // dao.projectActive(List.of("id", "email", "created_at"))
 *     //   -> "SELECT id, email, created_at FROM users WHERE active = TRUE"
 * }
 * }</pre>
 *
 * <p><b>Dynamic ORDER BY:</b></p>
 * <pre>{@code
 * @Query("SELECT * FROM products ORDER BY {sortKeys}")
 * List<Product> sorted(@SqlFragmentList("sortKeys") List<String> sortKeys);
 *
 * // dao.sorted(List.of("category ASC", "price DESC"))
 * //   -> "SELECT * FROM products ORDER BY category ASC, price DESC"
 * }</pre>
 *
 * @see SqlFragment
 * @see BindList
 * @see Query#fragmentsContainNamedParameters()
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
     * <p>The resolved name must correspond to a {@code {name}} token in the surrounding
     * {@link Query @Query} SQL. The collection/array elements are comma-joined (with no surrounding
     * parentheses) and rewritten into that token; an empty collection yields an empty fragment,
     * which is usually a SQL syntax error, so guard against it at the call site.</p>
     *
     * @return the template-variable name; empty means use the method parameter name (requires {@code -parameters})
     */
    String value() default "";
}
