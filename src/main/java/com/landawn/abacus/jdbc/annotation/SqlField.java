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
 * Customizes the mapping between entity fields and database columns.
 * This annotation can be applied to fields in entity classes to explicitly specify
 * the corresponding database column name, especially when field naming conventions
 * differ from database column naming conventions.
 *
 * <p>When not specified or when the id is empty, the field name itself is used
 * as the column name. This annotation is particularly useful when:</p>
 * <ul>
 *   <li>Database uses different naming conventions (e.g., snake_case vs camelCase)</li>
 *   <li>Column names are reserved keywords or have special characters</li>
 *   <li>Legacy databases with non-standard naming</li>
 *   <li>Mapping to views or complex queries with aliased columns</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Entity class with custom column mappings
 * public class User {
 *     @SqlField(id = "user_id")
 *     private Long id;
 *
 *     @SqlField(id = "full_name")
 *     private String name;
 *
 *     @SqlField  // Uses field name "email" as column name
 *     private String email;
 *
 *     private String status;  // Without annotation, uses field name as column
 * }
 *
 * // DAO usage - framework handles mapping automatically
 * public interface UserDao extends CrudDao<User, Long> {
 *     // Automatically maps user_id→id, full_name→name, email→email
 *     @Query("SELECT user_id, full_name, email, status FROM users WHERE user_id = :id")
 *     User findById(@Bind("id") Long id);
 *
 *     // Insert also uses the mappings
 *     @Query("INSERT INTO users (user_id, full_name, email) VALUES (:id, :name, :email)")
 *     int insertUser(User user);
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SqlField {

    /**
     * Specifies the SQL column name or identifier that this field maps to.
     * If not specified (empty string), the field name will be used.
     *
     * <p>This is useful when the database column naming convention differs from Java field naming
     * conventions, such as when the database uses snake_case while Java uses camelCase.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * public class Customer {
     *     @SqlField(id = "customer_id")
     *     private Long id;
     *
     *     @SqlField(id = "FIRST_NAME")
     *     private String firstName;
     *
     *     @SqlField(id = "email_address")
     *     private String email;
     *
     *     @SqlField  // Uses field name as column name
     *     private String status;
     *
     *     @SqlField(id = "")  // Explicitly uses field name as column name
     *     private Date createdDate;
     * }
     *
     * // Usage in queries
     * @Query("SELECT customer_id, FIRST_NAME, email_address, status FROM customers")
     * List<Customer> findAll();  // Framework maps columns to fields using @SqlField
     * }</pre>
     *
     * @return the SQL column name/identifier for this field, or empty string to use the field name
     */
    String id() default ""; // default will be field name.
}
