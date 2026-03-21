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
 * Maps prefixed column aliases to nested target paths during row mapping.
 *
 * <p>For example, a mapping such as {@code d=device} lets a column alias like
 * {@code d.id} populate {@code device.id} on the target object.</p>
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
     * <p>Rules:</p>
     * <ul>
     *   <li>Prefixes should match the column aliases used in the SQL query</li>
     *   <li>Field paths can be simple field names or nested paths (e.g., "address.city")</li>
     *   <li>The prefix must be separated from the column name by a dot (e.g., {@code d.id}); underscores are not supported</li>
     *   <li>The prefix is removed from the column name before mapping to the field</li>
     *   <li>Columns without matching prefixes are mapped normally</li>
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
     * @return the prefix-to-field mapping string, or empty string if using default mapping
     */
    String value() default "";
}
