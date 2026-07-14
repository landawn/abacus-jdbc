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
 * Performs SQL template substitution for one query fragment. The annotated method parameter's
 * string value replaces the {@code {name}} token in the surrounding {@link Query @Query} SQL
 * <em>before</em> the statement is prepared.
 *
 * <p>Unlike {@link Bind} — which sends the value through a JDBC parameter placeholder —
 * {@code @SqlFragment} rewrites the SQL text itself. That makes it the right tool for tokens
 * that JDBC cannot parameterize (table names, column lists, {@code ORDER BY} expressions,
 * sub-conditions) but a dangerous one for untrusted input. Always supply pre-validated or
 * whitelisted strings; otherwise the framework offers no protection against SQL injection at
 * this layer.</p>
 *
 * <p>The DAO proxy ({@code DaoImpl}) resolves these substitutions when building the SQL for a
 * call. If the replacement text itself contains named parameter placeholders (e.g.,
 * {@code "discount >= :minDiscount"}), set
 * {@link Query#fragmentsContainNamedParameters() @Query(fragmentsContainNamedParameters = true)}
 * so the proxy re-scans the assembled SQL for them.</p>
 *
 * <p><b>Usage Examples:</b></p>
 *
 * <p><b>Whitelisted table / schema substitution:</b></p>
 * <pre>{@code
 * public interface UserDao extends Dao<User, UserDao> {
 *
 *     @Query("SELECT * FROM {schema}.{table} WHERE id = :id")
 *     User findById(
 *         @SqlFragment("schema") String schema,    // e.g., "tenant_a"
 *         @SqlFragment("table")  String table,     // e.g., "users"
 *         @Bind("id")            long id);
 * }
 * }</pre>
 *
 * <p><b>Dynamic WHERE clause that itself binds named parameters:</b></p>
 * <pre>{@code
 * @Query(value = "SELECT * FROM promotions WHERE {whereClause}",
 *        fragmentsContainNamedParameters = true)
 * List<Promotion> findActive(
 *     @SqlFragment("whereClause") String whereClause,
 *     @Bind("minDiscount")        int    minDiscount);
 *
 * // Caller:
 * dao.findActive("discount >= :minDiscount AND status = 'ACTIVE'", 10);
 * }</pre>
 *
 * @see SqlFragmentList
 * @see Bind
 * @see Query#fragmentsContainNamedParameters()
 */
@Documented
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
     * <p>The resolved name must correspond to a {@code {name}} token in the surrounding
     * {@link Query @Query} SQL, and each {@code @SqlFragment} parameter on a method should target a
     * distinct token. Because the substitution rewrites the SQL text (it is not a JDBC bind), supply
     * only trusted, pre-validated strings.</p>
     *
     * @return the template-variable name; empty means use the method parameter name (requires {@code -parameters})
     */
    String value() default "";
}
