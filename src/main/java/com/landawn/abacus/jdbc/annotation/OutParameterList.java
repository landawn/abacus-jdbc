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
 * Container annotation for repeatable {@link OutParameter} declarations on a single stored-procedure
 * DAO method.
 *
 * <p>You normally do not write {@code @OutParameterList(...)} by hand: the Java compiler
 * synthesizes it automatically when more than one {@link OutParameter @OutParameter} appears on
 * the same method. Direct use is only needed in rare cases (for example, when generating
 * annotations programmatically).</p>
 *
 * <p>The DAO proxy registers each contained {@code @OutParameter} on the underlying
 * {@link java.sql.CallableStatement} in declaration order; the registered values are then
 * exposed to the caller only through a {@code Jdbc.OutParamResult} return value (with
 * {@code op = OP.executeAndGetOutParameters}) or a {@code Tuple2<T, Jdbc.OutParamResult>}
 * return value.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Implicit form — preferred. The compiler wraps the two @OutParameters in an @OutParameterList.
 * @Query(value = "{call calculate_discount(:price, :customerId, :discount, :finalPrice)}",
 *        isProcedure = true, op = OP.executeAndGetOutParameters)
 * @OutParameter(name = "discount",   sqlType = Types.DECIMAL)
 * @OutParameter(name = "finalPrice", sqlType = Types.DECIMAL)
 * Jdbc.OutParamResult calculateDiscount(
 *         @Bind("price")      BigDecimal price,
 *         @Bind("customerId") long       customerId) throws SQLException;
 *
 * // Explicit form — equivalent to the above.
 * @Query(value = "{call calculate_discount(:price, :customerId, :discount, :finalPrice)}",
 *        isProcedure = true, op = OP.executeAndGetOutParameters)
 * @OutParameterList({
 *     @OutParameter(name = "discount",   sqlType = Types.DECIMAL),
 *     @OutParameter(name = "finalPrice", sqlType = Types.DECIMAL)
 * })
 * Jdbc.OutParamResult calculateDiscountExplicit(
 *         @Bind("price")      BigDecimal price,
 *         @Bind("customerId") long       customerId) throws SQLException;
 * }</pre>
 *
 * @see OutParameter
 * @see Query
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OutParameterList {

    /**
     * The array of {@link OutParameter} annotations contained in this list.
     * Each {@code OutParameter} defines a single output parameter for a stored procedure call.
     *
     * <p>This array is automatically populated when using multiple {@link OutParameter}
     * annotations on a method via the {@link java.lang.annotation.Repeatable} mechanism;
     * direct use of {@code @OutParameterList} is rarely needed.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @OutParameterList({
     *     @OutParameter(position = 1, sqlType = Types.VARCHAR),
     *     @OutParameter(position = 2, sqlType = Types.INTEGER),
     *     @OutParameter(position = 3, sqlType = Types.DECIMAL)
     * })
     * }</pre>
     *
     * @return the contained {@link OutParameter} annotations
     */
    OutParameter[] value();
}
