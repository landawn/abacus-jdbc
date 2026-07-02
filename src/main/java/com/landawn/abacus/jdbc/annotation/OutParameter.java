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
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.sql.CallableStatement;
import java.sql.Types;

/**
 * Declares an {@code OUT} or {@code INOUT} parameter for a stored-procedure DAO method.
 *
 * <p>Each annotation instance identifies the parameter by name or position and supplies the
 * JDBC {@link Types SQL type} used when registering it on the underlying
 * {@link CallableStatement}.</p>
 *
 * <p>The annotation is placed on a stored-procedure DAO method (per its
 * {@link ElementType#METHOD METHOD} target) and is {@link Repeatable repeatable}: declaring several
 * {@code @OutParameter}s on the same method collects them into an {@link OutParameterList}. Exactly one
 * of {@link #name()} or {@link #position()} must be supplied on each instance, while {@link #sqlType()}
 * is always mandatory. The accompanying {@link Query @Query} should declare {@code isProcedure = true}
 * and an {@code op} that retrieves the OUT values (for example {@code OP.executeAndGetOutParameters}).</p>
 *
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * @Query(value = "{call calculate_discount(:price, :customerId, :discount, :finalPrice)}",
 *        isProcedure = true, op = OP.executeAndGetOutParameters)
 * @OutParameter(name = "discount",   sqlType = Types.DECIMAL)
 * @OutParameter(name = "finalPrice", sqlType = Types.DECIMAL)
 * Jdbc.OutParamResult calculateDiscount(
 *         @Bind("price")      BigDecimal price,
 *         @Bind("customerId") long       customerId) throws SQLException;
 * }</pre>
 *
 * @see Query
 * @see OutParameterList
 * @see CallableStatement#registerOutParameter(String, int)
 * @see CallableStatement#registerOutParameter(int, int)
 * @see Types
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(OutParameterList.class)
public @interface OutParameter {

    /**
     * Specifies the name of the output parameter.
     * Use this for named parameters in the stored procedure call.
     *
     * <p>Exactly one of {@code name} or {@code position} must be specified for a given
     * {@code @OutParameter}; supplying both, or neither, fails with {@code UnsupportedOperationException}
     * at DAO initialization time. Named parameters are generally preferred for readability and
     * maintainability when the JDBC driver and procedure support them.</p>
     *
     * <p>The parameter name should match the name used in the stored procedure call syntax.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "{call calculate_discount(:price, :customerId, :discount, :finalPrice)}",
     *        isProcedure = true, op = OP.executeAndGetOutParameters)
     * @OutParameter(name = "discount", sqlType = Types.DECIMAL)
     * @OutParameter(name = "finalPrice", sqlType = Types.DECIMAL)
     * Jdbc.OutParamResult calculateDiscount(
     *     @Bind("price") BigDecimal price,
     *     @Bind("customerId") long customerId
     * ) throws SQLException;
     * }</pre>
     *
     * @return the parameter name; empty string when {@link #position()} is used instead
     * @see CallableStatement#registerOutParameter(String, int)
     */
    String name() default "";

    /**
     * Specifies the position of the output parameter.
     * Use this for positional parameters in the stored procedure call.
     *
     * <p>Positions start from 1, following JDBC convention. Exactly one of {@code name} or
     * {@code position} must be specified for a given {@code @OutParameter} (supplying both, or
     * neither, fails with {@code UnsupportedOperationException} at DAO initialization time).</p>
     *
     * <p>Position-based parameters are useful when:</p>
     * <ul>
     *   <li>The stored procedure doesn't support named parameters</li>
     *   <li>You're working with legacy procedures</li>
     *   <li>The database doesn't support named parameters</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "{call sp_analyze_customer(?, ?, ?, ?)}",
     *        isProcedure = true, op = OP.executeAndGetOutParameters)
     * @OutParameter(position = 2, sqlType = Types.VARCHAR)  // status
     * @OutParameter(position = 3, sqlType = Types.DECIMAL)  // credit_score
     * @OutParameter(position = 4, sqlType = Types.DATE)     // last_purchase
     * Jdbc.OutParamResult analyzeCustomer(long customerId) throws SQLException;
     * }</pre>
     *
     * @return the parameter position (1-based); {@code -1} (default) when {@link #name()} is used instead
     * @see CallableStatement#registerOutParameter(int, int)
     */
    int position() default -1;

    /**
     * Specifies the SQL type of the output parameter.
     * This must be one of the constants defined in {@link java.sql.Types}.
     *
     * <p>Common SQL types include:</p>
     * <ul>
     *   <li>{@link Types#VARCHAR} - String values</li>
     *   <li>{@link Types#INTEGER} - Integer values</li>
     *   <li>{@link Types#DECIMAL} or {@link Types#NUMERIC} - Decimal numbers</li>
     *   <li>{@link Types#DATE}, {@link Types#TIME}, {@link Types#TIMESTAMP} - Date/time values</li>
     *   <li>{@link Types#BOOLEAN} - Boolean values</li>
     *   <li>{@link Types#CLOB} - Character large objects</li>
     *   <li>{@link Types#BLOB} - Binary large objects</li>
     * </ul>
     *
     * <p>The SQL type must match the actual type of the output parameter in the stored procedure.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @OutParameter(name = "message", sqlType = Types.VARCHAR)
     * @OutParameter(name = "count", sqlType = Types.INTEGER)
     * @OutParameter(name = "amount", sqlType = Types.DECIMAL)
     * @OutParameter(name = "processDate", sqlType = Types.TIMESTAMP)
     * @OutParameter(name = "isActive", sqlType = Types.BOOLEAN)
     * }</pre>
     *
     * <p>This element has no default value and must be specified for every {@code @OutParameter}.</p>
     *
     * @return the SQL type constant from {@link java.sql.Types}
     * @see Types
     */
    int sqlType();
}
