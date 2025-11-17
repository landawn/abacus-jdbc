/*
 * Copyright (c) 2022, Haiyang Li.
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
 * Maps database column prefixes to object field paths for result set mapping.
 * This annotation is used when joining multiple tables and needing to map columns
 * with prefixes to nested objects or specific fields in the result object.
 * 
 * <p>When performing SQL joins, it's common to alias columns with prefixes to avoid
 * naming conflicts. This annotation helps map those prefixed columns to the appropriate
 * fields in your domain objects, including nested objects.</p>
 * 
 * <p><strong>Deprecated:</strong> This annotation is deprecated and should not be used for new development.
 * Consider using one of the following modern alternatives:</p>
 * <ul>
 *   <li>Use explicit column aliases with the {@link MergedById} annotation for nested object mapping</li>
 *   <li>Define separate query methods for joined data and compose results in application code</li>
 *   <li>Use proper JOIN syntax with nested property notation (e.g., {@code 'device.id'})</li>
 *   <li>Leverage ORM frameworks with built-in association mapping capabilities</li>
 * </ul>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long> {
 *     
 *     @Query("SELECT u.id, u.name, u.email, " +
 *             "d.id as d_id, d.model as d_model, d.os as d_os, " +
 *             "c.id as c_id, c.phone as c_phone, c.address as c_address " +
 *             "FROM users u " +
 *             "LEFT JOIN devices d ON u.id = d.user_id " +
 *             "LEFT JOIN contacts c ON u.id = c.user_id " +
 *             "WHERE u.id = :id")
 *     @PrefixFieldMapping("d=device, c=contact")
 *     User findUserWithDetails(@Bind("id") long id);
 *     
 *     // Result mapping:
 *     // d_id -> user.device.id
 *     // d_model -> user.device.model
 *     // d_os -> user.device.os
 *     // c_id -> user.contact.id
 *     // c_phone -> user.contact.phone
 *     // c_address -> user.contact.address
 * }
 * 
 * // Domain objects
 * public class User {
 *     private Long id;
 *     private String name;
 *     private String email;
 *     private Device device;  // Nested object
 *     private Contact contact; // Nested object
 *     // getters/setters
 * }
 * }</pre>
 *
 */
@Deprecated
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD })
public @interface PrefixFieldMapping {

    /**
     * Specifies the mapping between column prefixes and object field paths.
     * The format is a comma-separated list of prefix=fieldPath pairs.
     * 
     * <p>Format: {@code "prefix1=fieldPath1, prefix2=fieldPath2, ..."}</p>
     * 
     * <p>Rules:</p>
     * <ul>
     *   <li>Prefixes should match the column aliases used in the SQL query</li>
     *   <li>Field paths can be simple field names or nested paths (e.g., "address.city")</li>
     *   <li>The prefix is removed from the column name before mapping to the field</li>
     *   <li>Columns without matching prefixes are mapped normally</li>
     * </ul>
     * 
     * <p>Example mappings:</p>
     * <pre>{@code
     * // Simple prefix mapping
     * @PrefixFieldMapping("addr=address")
     * // addr_street -> address.street
     * // addr_city -> address.city
     * 
     * // Multiple prefix mappings
     * @PrefixFieldMapping("u=user, o=order, p=payment")
     * // u_name -> user.name
     * // o_id -> order.id
     * // p_amount -> payment.amount
     * 
     * // Nested field mapping
     * @PrefixFieldMapping("bill=billing.address, ship=shipping.address")
     * // bill_street -> billing.address.street
     * // ship_street -> shipping.address.street
     * }</pre>
     * 
     * @return the prefix-to-field mapping string, or empty string if using default mapping
     * @deprecated This entire annotation is deprecated. Use explicit column mapping with {@link MergedById}
     *             or modern ORM features for nested object mapping instead.
     */
    @Deprecated
    String value() default "";
}