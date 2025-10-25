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
 * Declares an output parameter for stored procedure calls.
 * This annotation is used to register OUT or IN/OUT parameters when calling stored procedures
 * through DAO methods, allowing the retrieval of values returned by the procedure.
 * 
 * <p>Output parameters can be identified either by name or position. When using multiple
 * output parameters, this annotation can be repeated thanks to the {@link Repeatable} meta-annotation.</p>
 * 
 * <p>The registered output parameters are typically returned in a Map where keys are either
 * the parameter names or positions (as strings).</p>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface EmployeeDao extends CrudDao<Employee, Long> {
 *
 *     // Single output parameter by position
 *     @Query(value = "{call get_employee_count(?)}", isProcedure = true)
 *     @OutParameter(position = 1, sqlType = Types.INTEGER)
 *     int getEmployeeCount();
 *
 *     // Multiple output parameters by name
 *     @Query(value = "{call calculate_bonus(:employeeId, :bonus, :tax)}", isProcedure = true)
 *     @OutParameter(name = "bonus", sqlType = Types.DECIMAL)
 *     @OutParameter(name = "tax", sqlType = Types.DECIMAL)
 *     Map<String, Object> calculateBonus(@Bind("employeeId") long employeeId);
 *
 *     // Mixed IN and OUT parameters
 *     @Query(value = "{call update_salary(:employeeId, :increase, :newSalary, :effectiveDate)}", isProcedure = true)
 *     @OutParameter(name = "newSalary", sqlType = Types.DECIMAL)
 *     @OutParameter(name = "effectiveDate", sqlType = Types.DATE)
 *     Map<String, Object> updateSalary(
 *         @Bind("employeeId") long employeeId,
 *         @Bind("increase") BigDecimal increase
 *     );
 *
 *     // Using position-based parameters
 *     @Query(value = "{call sp_get_stats(?, ?, ?, ?)}", isProcedure = true)
 *     @OutParameter(position = 2, sqlType = Types.INTEGER) // total_count
 *     @OutParameter(position = 3, sqlType = Types.DECIMAL) // average_salary
 *     @OutParameter(position = 4, sqlType = Types.VARCHAR) // department_name
 *     Map<String, Object> getDepartmentStats(@Bind long departmentId);
 * }
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
     * <p>Either {@code name} or {@code position} must be specified, but not both.
     * Named parameters are generally preferred for readability and maintainability.</p>
     * 
     * <p>The parameter name should match the name used in the stored procedure call syntax.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "{call calculate_discount(:price, :customerId, :discount, :finalPrice)}", isProcedure = true)
     * @OutParameter(name = "discount", sqlType = Types.DECIMAL)
     * @OutParameter(name = "finalPrice", sqlType = Types.DECIMAL)
     * Map<String, Object> calculateDiscount(
     *     @Bind("price") BigDecimal price,
     *     @Bind("customerId") long customerId
     * );
     * }</pre>
     *
     * @return the parameter name, or empty string if using position
     * @see CallableStatement#registerOutParameter(String, int)
     */
    String name() default "";

    /**
     * Specifies the position of the output parameter.
     * Use this for positional parameters in the stored procedure call.
     * 
     * <p>Positions start from 1, following JDBC convention. Either {@code name} or
     * {@code position} must be specified, but not both.</p>
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
     * @Query(value = "{call sp_analyze_customer(?, ?, ?, ?)}", isProcedure = true)
     * @OutParameter(position = 2, sqlType = Types.VARCHAR)  // status
     * @OutParameter(position = 3, sqlType = Types.DECIMAL)  // credit_score
     * @OutParameter(position = 4, sqlType = Types.DATE)     // last_purchase
     * Map<String, Object> analyzeCustomer(@Bind long customerId);
     * }</pre>
     *
     * @return the parameter position (1-based), or -1 if using name
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
     * @return the SQL type constant from {@link java.sql.Types}
     * @see Types
     */
    int sqlType();
}