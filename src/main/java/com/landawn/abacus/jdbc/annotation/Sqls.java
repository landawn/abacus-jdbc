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
 * Specifies SQL statements for DAO methods with default implementations.
 * This annotation is designed exclusively for methods with default implementations in DAO interfaces,
 * not for abstract methods. The annotated method must accept a varargs String parameter as its last argument.
 * 
 * <p>The SQL statements specified in this annotation will be passed to the method's implementation
 * through the varargs parameter, allowing dynamic SQL execution within default interface methods.</p>
 * 
 * <p><strong>Important:</strong> Do not use this annotation on abstract methods. It is only intended
 * for methods that have a default implementation in the DAO interface.</p>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long> {
 *     
 *     @Sqls({"UPDATE users SET last_login = NOW() WHERE id = ?",
 *            "INSERT INTO user_login_history (user_id, login_time) VALUES (?, NOW())"})
 *     default void recordUserLogin(long userId, String... sqls) {
 *         // Default implementation uses the SQL statements from @Sqls
 *         for (String sql : sqls) {
 *             execute(sql, userId);
 *         }
 *     }
 *     
 *     // Using SQL mapper IDs instead of inline SQL
 *     @Sqls({"updateLastLogin", "insertLoginHistory"})
 *     default void recordLogin(User user, String... sqls) {
 *         // Implementation retrieves actual SQL from mapper using the IDs
 *     }
 * }
 * }</pre>
 * 
 * @see Select
 * @see Update
 * @see SqlMapper
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Sqls {

    /**
     * Specifies the SQL statements or SQL mapper IDs to be used by the annotated method.
     * 
     * <p>The values can be:</p>
     * <ul>
     *   <li>Direct SQL statements (e.g., "SELECT * FROM users")</li>
     *   <li>SQL mapper IDs that reference SQL defined in a mapper file or SQL table class</li>
     * </ul>
     * 
     * <p>These values are passed to the method's varargs String parameter, which must be
     * the last parameter in the method signature.</p>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * // Direct SQL statements
     * @Sqls({"DELETE FROM user_sessions WHERE user_id = ?",
     *        "UPDATE users SET active = false WHERE id = ?"})
     * 
     * // SQL mapper IDs
     * @Sqls({"deleteUserSessions", "deactivateUser"})
     * }</pre>
     *
     * @return an array of SQL statements or SQL mapper IDs
     */
    String[] value() default {};
}