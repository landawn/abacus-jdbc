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
package com.landawn.abacus.jdbc;

/**
 * Enum representing various database operations that can be performed through AbstractQuery.
 * These operations define how query results are retrieved and processed in the Abacus JDBC framework.
 * 
 * <p>The OP enum is typically used to specify the operation type in query annotations or method calls,
 * allowing the framework to determine the appropriate execution strategy and return type.</p>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * @Query(value = "SELECT * FROM users WHERE age > ?", op = OP.list)
 * List<User> findUsersByAge(int age);
 * 
 * @Query(value = "SELECT COUNT(*) FROM users", op = OP.queryForSingle)
 * int getUserCount();
 * }</pre>
 * 
 * @see AbstractQuery
 */
public enum OP {
    /**
     * Checks whether any records exist that match the query criteria.
     * Returns a boolean indicating the existence of matching records.
     * 
     * <p>This operation is optimized for existence checks and typically translates to
     * a query with LIMIT 1 or uses EXISTS clause internally for better performance.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "SELECT 1 FROM users WHERE email = ?", op = OP.exists)
     * boolean emailExists(String email);
     * }</pre>
     */
    exists,

    /**
     * Retrieves exactly one record from the query results.
     * Throws an exception if zero or more than one record is found.
     * 
     * <p>Use this operation when you expect exactly one result and want to fail fast
     * if this expectation is not met. This is useful for queries by unique identifiers.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "SELECT * FROM users WHERE id = ?", op = OP.findOnlyOne)
     * User getUserById(long id);
     * }</pre>
     */
    findOnlyOne,

    /**
     * Retrieves the first record from the query results.
     * Returns an Optional that is empty if no records are found.
     * 
     * <p>This operation is useful when you want at most one result but don't require
     * exactly one. The query typically includes an ORDER BY clause to ensure
     * deterministic results.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "SELECT * FROM users WHERE age >= ? ORDER BY age", op = OP.findFirst)
     * Optional<User> findYoungestAdult(int minAge);
     * }</pre>
     */
    findFirst,

    /**
     * Retrieves all matching records as a List.
     * Returns an empty list if no records are found.
     * 
     * <p>This is the most common operation for queries that return multiple records.
     * All results are loaded into memory at once, so use with caution for large result sets.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "SELECT * FROM users WHERE active = true", op = OP.list)
     * List<User> getActiveUsers();
     * }</pre>
     */
    list,

    /**
     * General query operation that returns results based on the method return type.
     * The framework automatically determines the appropriate result handling.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "SELECT * FROM users WHERE age > ?", op = OP.query)
     * Dataset queryUsersByAge(int age);
     * }</pre>
     *
     * @deprecated generally it's unnecessary to specify the {@code "op = OP.query"} in {@code @Query}.
     */
    @Deprecated
    query,

    /**
     * Returns query results as a Stream for lazy evaluation and processing.
     * Useful for handling large result sets without loading all data into memory.
     *
     * <p>The stream should be properly closed after use, preferably in a try-with-resources block.
     * This operation enables processing of large datasets with minimal memory footprint.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "SELECT * FROM users", op = OP.stream)
     * Stream<User> streamAllUsers();
     * }</pre>
     *
     * @deprecated generally it's unnecessary to specify the {@code "op = OP.stream"} in {@code @Query}.
     */
    @Deprecated
    stream,

    /**
     * Retrieves a single value from the query result.
     * Typically used for aggregate queries that return one value.
     * 
     * <p>This operation expects the query to return exactly one row with one column.
     * Common use cases include COUNT, SUM, MAX, MIN queries.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "SELECT MAX(salary) FROM employees", op = OP.queryForSingle)
     * Double getMaxSalary();
     * 
     * @Query(value = "SELECT name FROM users WHERE id = ?", op = OP.queryForSingle)
     * String getUserName(long id);
     * }</pre>
     */
    queryForSingle,

    /**
     * Retrieves a unique single value from the query result.
     * Similar to queryForSingle but returns {@code null} if no result is found instead of throwing exception.
     * 
     * <p>Use this operation when the result might be empty and you want to handle it
     * gracefully with a {@code null} return value rather than an exception.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "SELECT email FROM users WHERE username = ?", op = OP.queryForUnique)
     * String findEmailByUsername(String username);
     * }</pre>
     */
    queryForUnique,

    /**
     * Retrieves all ResultSets from a stored procedure call as Lists.
     * Each ResultSet is converted to a List of the specified type.
     *
     * <p>This operation is primarily used with {@code @Query} annotation for stored procedures
     * that return multiple result sets. Each result set is processed and returned
     * in a collection.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "{call getUsersAndOrders(?)}", op = OP.listAll, isProcedure = true)
     * Tuple2<List<User>, List<Order>> getUsersAndOrders(long userId);
     * }</pre>
     *
     * <p>This operation is primarily used with {@code @Query} annotation to retrieve all the {@code ResultSets}
     * returned from the executed procedure by {@code listAll/listAllAndGetOutParameters}.</p>
     */
    listAll,

    /**
     * Retrieves all ResultSets from a stored procedure call as Datasets.
     * Each ResultSet is converted to a Dataset for flexible data manipulation.
     *
     * <p>Similar to listAll but returns Dataset objects which provide more
     * flexibility for data processing and transformation compared to typed Lists.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "{call getComplexReport(?, ?)}", op = OP.queryAll, isProcedure = true)
     * List<Dataset> getComplexReport(Date startDate, Date endDate);
     * }</pre>
     *
     * <p>This operation is primarily used with {@code @Query} annotation to retrieve all the {@code ResultSets}
     * returned from the executed procedure by {@code queryAll/queryAllAndGetOutParameters}.</p>
     */
    queryAll,

    /**
     * Retrieves all ResultSets from a stored procedure call as Streams.
     * Enables lazy processing of multiple result sets with minimal memory usage.
     *
     * <p>This operation is ideal for stored procedures that return large result sets
     * where you want to process data in a streaming fashion rather than loading
     * everything into memory at once.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "{call streamLargeDatasets()}", op = OP.streamAll, isProcedure = true)
     * Tuple2<Stream<User>, Stream<Transaction>> streamLargeDatasets();
     * }</pre>
     *
     * <p>This operation is primarily used with {@code @Query} annotation to retrieve all the {@code ResultSets}
     * returned from the executed procedure by {@code streamAll}.</p>
     */
    streamAll,

    /**
     * Executes a stored procedure and retrieves OUT parameters.
     * Used when the primary goal is to get output parameters rather than result sets.
     *
     * <p>This operation is specifically designed for stored procedures with OUT or INOUT
     * parameters. The return type should match the structure of the output parameters.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "{call calculateStats(?, ?, ?)}", op = OP.executeAndGetOutParameters, isProcedure = true)
     * @OutParameter(position = 2, sqlType = Types.INTEGER)
     * @OutParameter(position = 3, sqlType = Types.DECIMAL)
     * Tuple2<Integer, Double> calculateStats(@Bind("input") int input);
     * }</pre>
     *
     * <p>This operation is primarily used with {@code @Query} annotation to execute the target procedure
     * and get out parameters by {@code executeAndGetOutParameters}.</p>
     */
    executeAndGetOutParameters,

    /**
     * Executes an UPDATE, INSERT, or DELETE statement and returns the number of affected rows.
     * Returns an int representing the row count.
     * 
     * <p>This is the standard operation for DML (Data Manipulation Language) statements
     * that modify data in the database. The return value indicates how many rows were affected.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "UPDATE users SET active = false WHERE last_login < ?", op = OP.update)
     * int deactivateInactiveUsers(Date threshold);
     * 
     * @Query(value = "INSERT INTO users (name, email) VALUES (?, ?)", op = OP.update)
     * int createUser(String name, String email);
     * }</pre>
     */
    update,

    /**
     * Executes an UPDATE, INSERT, or DELETE statement that may affect a large number of rows.
     * Returns a long representing the row count for compatibility with large datasets.
     * 
     * <p>Use this operation when the number of affected rows might exceed the range of int.
     * This is particularly relevant for bulk operations on large tables.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "DELETE FROM audit_logs WHERE created_date < ?", op = OP.largeUpdate)
     * long purgeOldAuditLogs(Date cutoffDate);
     * }</pre>
     */
    largeUpdate,

    /* batchUpdate,*/

    /**
     * Default operation that lets the framework automatically determine the appropriate operation
     * based on the SQL statement type and method signature.
     * 
     * <p>When DEFAULT is used, the framework analyzes the SQL statement and method return type
     * to select the most appropriate operation. For example, SELECT statements default to list
     * or query operations, while UPDATE/INSERT/DELETE statements default to update operations.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query("SELECT * FROM users")  // op defaults to OP.DEFAULT
     * List<User> getAllUsers();  // Framework infers OP.list
     * 
     * @Query("DELETE FROM users WHERE id = ?")  // op defaults to OP.DEFAULT  
     * int deleteUser(long id);  // Framework infers OP.update
     * }</pre>
     */
    DEFAULT

}
