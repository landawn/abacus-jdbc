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
 * Maps prefixed column aliases to nested target paths during row-to-bean mapping. This is the
 * companion to {@link MergedById} for queries that join multiple tables and want to populate
 * nested entity properties from columns whose aliases use a short prefix.
 *
 * <p>The DAO proxy ({@code DaoImpl}) reads this annotation when building the row mapper for a
 * {@link Query @Query} method. For each column whose alias starts with {@code prefix.}, the
 * proxy strips the {@code prefix.} and then assigns the value to the corresponding nested
 * property on the target object.</p>
 *
 * <p><b>Usage Examples:</b></p>
 *
 * <p><b>Single-table prefix, simple one-to-one nesting:</b></p>
 * <pre>{@code
 * public class User {
 *     long      id;
 *     String    name;
 *     Address   address;     // nested target
 * }
 *
 * public interface UserDao extends Dao<User, UserDao> {
 *
 *     @PrefixFieldMapping("addr=address")
 *     @Query("SELECT u.id, u.name, " +
 *            "       a.street AS \"addr.street\", a.city AS \"addr.city\" " +
 *            "FROM users u JOIN addresses a ON a.user_id = u.id WHERE u.id = :id")
 *     User getWithAddress(@Bind("id") long id);
 * }
 * // addr.street -> address.street, addr.city -> address.city
 * }</pre>
 *
 * <p><b>Multiple prefixes and a one-to-many merge:</b></p>
 * <pre>{@code
 * @PrefixFieldMapping("d=devices")
 * @MergedById
 * @Query("SELECT u.id, u.name, " +
 *        "       d.id AS \"d.id\", d.model AS \"d.model\" " +
 *        "FROM users u LEFT JOIN devices d ON d.user_id = u.id " +
 *        "WHERE u.id IN ({ids})")
 * List<User> usersWithDevices(@BindList("ids") List<Long> ids);
 * }</pre>
 *
 * @see Query
 * @see MergedById
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD })
public @interface PrefixFieldMapping {

    /**
     * Specifies the mapping between column prefixes and object field paths.
     * The format is a comma-separated list of prefix=fieldPath pairs.
     *
     * <p>Format: {@code "prefix1=fieldPath1, prefix2=fieldPath2, ..."}</p>
     *
     * <p>Two distinct syntaxes are involved &mdash; do not confuse them:</p>
     * <ul>
     *   <li><b>Mapping syntax (this element's value):</b> a comma-separated list of {@code prefix=fieldPath}
     *       pairs. The left side ({@code prefix}) is the alias prefix; the right side ({@code fieldPath}) is the
     *       target property path, which may be a simple field name or a dot-separated nested path
     *       (e.g., {@code "addr=address"} or {@code "bill=billing.address"}). The {@code =} separates the two.</li>
     *   <li><b>Column-alias syntax (in the SQL query):</b> each matching column alias must be of the form
     *       {@code prefix.columnName}, i.e. the prefix is joined to the column name by a dot (e.g., {@code addr.street});
     *       an underscore separator such as {@code addr_street} is not recognized.</li>
     * </ul>
     *
     * <p>Resolution rules:</p>
     * <ul>
     *   <li>Each {@code prefix} must match the leading segment (before the dot) of the column aliases used in the query
     *       (e.g., the prefix {@code addr} matches the alias {@code addr.street})</li>
     *   <li>The {@code prefix.} portion is stripped from the alias, and the remaining column name is mapped onto the
     *       configured {@code fieldPath} target</li>
     *   <li>Columns whose aliases have no matching prefix are mapped normally</li>
     * </ul>
     *
     * <p>Example mappings:</p>
     * <pre>{@code
     * // Simple prefix mapping
     * @PrefixFieldMapping("addr=address")
     * // addr.street -> address.street
     * // addr.city -> address.city
     *
     * // Multiple prefix mappings
     * @PrefixFieldMapping("u=user, o=order, p=payment")
     * // u.name -> user.name
     * // o.id -> order.id
     * // p.amount -> payment.amount
     *
     * // Nested field mapping
     * @PrefixFieldMapping("bill=billing.address, ship=shipping.address")
     * // bill.street -> billing.address.street
     * // ship.street -> shipping.address.street
     * }</pre>
     *
     * @return the comma-separated {@code prefix=fieldPath} mapping string; empty (default) means no prefix mapping is applied
     */
    String value() default "";
}
