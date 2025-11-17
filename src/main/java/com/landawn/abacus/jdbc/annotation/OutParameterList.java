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
 * Container annotation for multiple {@link OutParameter} annotations on a single method.
 * This annotation is used when a stored procedure or callable statement has multiple output parameters.
 * 
 * <p>This annotation serves as a container for the {@link OutParameter} repeatable annotation,
 * allowing multiple output parameters to be declared for a single DAO method that calls a stored procedure.</p>
 * 
 * <p>You typically don't use this annotation directly. Instead, use multiple {@link OutParameter}
 * annotations, and the compiler will automatically wrap them in this container.</p>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface EmployeeDao extends CrudDao<Employee, Long> {
 *
 *     // Multiple output parameters using repeatable annotation
 *     @Query(value = "{call calculate_salary_info(?, ?, ?, ?)}", isProcedure = true)
 *     @OutParameter(position = 2, sqlType = Types.DECIMAL)
 *     @OutParameter(position = 3, sqlType = Types.DECIMAL)
 *     @OutParameter(position = 4, sqlType = Types.VARCHAR)
 *     Map<String, Object> calculateSalaryInfo(@Bind("employeeId") long employeeId);
 *
 *     // Equivalent using OutParameterList directly (not recommended)
 *     @Query(value = "{call get_employee_stats(?, ?, ?, ?)}", isProcedure = true)
 *     @OutParameterList({
 *         @OutParameter(position = 2, sqlType = Types.DECIMAL),
 *         @OutParameter(position = 3, sqlType = Types.DECIMAL),
 *         @OutParameter(position = 4, sqlType = Types.INTEGER)
 *     })
 *     Map<String, Object> getEmployeeStats(@Bind("employeeId") long employeeId);
 * }
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
     * Each OutParameter defines a single output parameter for a stored procedure call.
     * 
     * <p>This array is automatically populated when using multiple {@link OutParameter}
     * annotations on a method due to the {@link java.lang.annotation.Repeatable} mechanism.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @OutParameterList({
     *     @OutParameter(position = 1, sqlType = Types.VARCHAR),
     *     @OutParameter(name = "count", position = 2, sqlType = Types.INTEGER),
     *     @OutParameter(name = "total", position = 3, sqlType = Types.DECIMAL)
     * })
     * }</pre>
     *
     * @return array of OutParameter annotations defining the output parameters
     */
    OutParameter[] value();
}
