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

import com.landawn.abacus.jdbc.OP;

/**
 * Marks a DAO method to execute a stored procedure or function call.
 * This annotation enables calling database stored procedures with support for
 * input/output parameters and multiple result sets.
 * 
 * <p>The stored procedure can be specified in three ways:</p>
 * <ol>
 *   <li>Inline using the {@code sql} attribute with JDBC call syntax</li>
 *   <li>Reference to a procedure defined in SqlMapper using the {@code id} attribute</li>
 *   <li>Legacy inline using the deprecated {@code value} attribute</li>
 * </ol>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long> {
 *     // Simple stored procedure call
 *     @Call(sql = "{call update_user_status(?, ?)}")
 *     void updateUserStatus(Long userId, String status);
 *     
 *     // Function call with return value
 *     @Call(sql = "{? = call calculate_user_score(?)}")
 *     int calculateUserScore(Long userId);
 *     
 *     // Procedure with OUT parameters
 *     @Call(sql = "{call get_user_stats(?, ?, ?)}")
 *     void getUserStats(Long userId, OutParam<Integer> postCount, OutParam<Date> lastLogin);
 *     
 *     // Procedure returning result set
 *     @Call(sql = "{call find_users_by_criteria(?, ?)}", fetchSize = 100)
 *     List<User> findUsersByCriteria(String status, int minAge);
 *     
 *     // Using SqlMapper reference
 *     @Call(id = "processMonthlyReport")
 *     void processMonthlyReport(int year, int month);
 *     
 *     // Multiple result sets
 *     @Call(sql = "{call get_user_details(?)}", op = OP.queryAll)
 *     List<DataSet> getUserDetails(Long userId);  // Returns user info + orders + payments
 *     
 *     // With query timeout
 *     @Call(sql = "{call generate_report(?, ?)}", queryTimeout = 300)
 *     DataSet generateComplexReport(Date startDate, Date endDate);
 * }
 * }</pre>
 * 
 * <p>Supported parameter types:</p>
 * <ul>
 *   <li>Regular input parameters - any Java type</li>
 *   <li>OUT parameters - wrapped in {@code OutParam<T>}</li>
 *   <li>INOUT parameters - wrapped in {@code InOutParam<T>}</li>
 * </ul>
 *
 * @see Select
 * @see OP
 * @since 0.8
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Call {

    /**
     * Legacy attribute for specifying the stored procedure call.
     * 
     * @return the stored procedure call statement
     * @deprecated Use {@code sql="{call procedure_name(?)}"} or {@code id="procedureId"} for explicit declaration
     */
    @Deprecated
    String value() default "";

    /**
     * Specifies the stored procedure or function call using JDBC escape syntax.
     * 
     * <p>Syntax patterns:</p>
     * <ul>
     *   <li>Procedure: {@code {call procedure_name(?, ?)}}</li>
     *   <li>Function: {@code {? = call function_name(?, ?)}}</li>
     * </ul>
     * 
     * <p>Examples:</p>
     * <pre>{@code
     * // Stored procedure
     * @Call(sql = "{call update_inventory(?, ?)}")
     * void updateInventory(Long productId, int quantity);
     * 
     * // Function with return value
     * @Call(sql = "{? = call calculate_discount(?, ?)}")
     * BigDecimal calculateDiscount(Long customerId, BigDecimal orderTotal);
     * 
     * // With named parameters (if supported by driver)
     * @Call(sql = "{call create_user(:name, :email, :status)}")
     * Long createUser(@Bind("name") String name, 
     *                 @Bind("email") String email, 
     *                 @Bind("status") String status);
     * }</pre>
     *
     * @return the JDBC call statement
     */
    String sql() default "";

    /**
     * Specifies the ID of a stored procedure defined in SqlMapper.
     * This allows centralized management of stored procedure definitions.
     * 
     * <p>Example with SqlMapper:</p>
     * <pre>{@code
     * // In SQL mapper XML
     * <sql id="monthlyRevenueReport">
     *     {call generate_monthly_revenue_report(?, ?, ?)}
     * </sql>
     * 
     * // In DAO
     * @Call(id = "monthlyRevenueReport")
     * DataSet getMonthlyRevenue(int year, int month, OutParam<BigDecimal> totalRevenue);
     * }</pre>
     *
     * @return the procedure ID from SqlMapper
     */
    String id() default "";

    /**
     * Specifies the number of rows to fetch at a time when retrieving result sets.
     * This is useful for procedures that return large result sets.
     * 
     * <p>A value of -1 (default) means the JDBC driver's default fetch size is used.
     * Setting an appropriate fetch size can significantly improve performance for
     * large result sets by reducing memory usage and network round trips.</p>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * // Fetch 500 rows at a time for large result set
     * @Call(sql = "{call get_all_transactions(?, ?)}", fetchSize = 500)
     * List<Transaction> getLargeTransactionSet(Date startDate, Date endDate);
     * }</pre>
     *
     * @return the fetch size, or -1 for driver default
     */
    int fetchSize() default -1;

    /**
     * Specifies the query timeout in seconds.
     * If the procedure execution takes longer than this timeout, it will be cancelled.
     * 
     * <p>A value of -1 (default) means no specific timeout is set.
     * This is particularly useful for long-running procedures like report generation.</p>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * // Allow up to 10 minutes for complex calculation
     * @Call(sql = "{call calculate_yearly_statistics(?)}", queryTimeout = 600)
     * DataSet calculateYearlyStats(int year);
     * }</pre>
     *
     * @return timeout in seconds, or -1 for default timeout
     */
    int queryTimeout() default -1;

    /**
     * Specifies whether a collection parameter should be treated as a single value.
     * This is useful when passing arrays to stored procedures that expect array types.
     * 
     * <p>When {@code true}, a collection/array parameter is passed as a single
     * database array type rather than being expanded.</p>
     * 
     * <p>Example with PostgreSQL array:</p>
     * <pre>{@code
     * @Call(sql = "{call process_batch_ids(?)}", isSingleParameter = true)
     * void processBatchIds(Long[] ids);  // Passed as single array parameter
     * }</pre>
     *
     * @return {@code true} to treat collection as single parameter
     */
    boolean isSingleParameter() default false;

    /**
     * Specifies the operation mode for handling multiple result sets from stored procedures.
     * 
     * <p>Operation modes:</p>
     * <ul>
     *   <li>{@link OP#DEFAULT} - Standard behavior, returns first result set only</li>
     *   <li>{@link OP#query} - Explicitly indicates single result set query</li>
     *   <li>{@link OP#queryAll} - Retrieves all result sets from the procedure</li>
     *   <li>{@link OP#update} - For procedures that perform updates without result sets</li>
     * </ul>
     * 
     * <p>Example with multiple result sets:</p>
     * <pre>{@code
     * // Procedure returns customer info, orders, and payments
     * @Call(sql = "{call get_customer_full_details(?)}", op = OP.queryAll)
     * List<DataSet> getCustomerDetails(Long customerId);
     * 
     * // Usage
     * List<DataSet> results = dao.getCustomerDetails(123L);
     * DataSet customerInfo = results.get(0);
     * DataSet orders = results.get(1);
     * DataSet payments = results.get(2);
     * }</pre>
     * 
     * <p>Note: When using {@code OP.queryAll}, the return type should be {@code List<DataSet>}
     * or use methods like {@code queryAll()}, {@code listAll()}, or {@code streamAll()} from
     * the query API.</p>
     *
     * @return the operation mode, defaults to {@link OP#DEFAULT}
     */
    OP op() default OP.DEFAULT;
}