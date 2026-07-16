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
 * Execution modes selected through the {@code op} element of
 * {@link com.landawn.abacus.jdbc.annotation.Query}, each corresponding to a terminal operation of
 * {@link AbstractQuery}.
 *
 * <p>Each constant selects a particular result-extraction strategy -- for example an existence
 * check, single-row retrieval, list or stream materialization, retrieval of every {@code ResultSet}
 * returned by a stored procedure, or an update count. The value is normally supplied through the
 * {@code op} attribute of a {@code @Query}-annotated DAO method; when it is left at its default of
 * {@link #DEFAULT}, the framework infers the appropriate strategy from the SQL statement and the
 * method's return type.</p>
 *
 * <p>Every constant except {@link #DEFAULT} is deliberately spelled in {@code camelCase} to mirror the name of
 * the corresponding {@link AbstractQuery} terminal operation it selects (for example {@link #findFirst},
 * {@link #queryForSingle}). {@code DEFAULT} is upper-cased because it is a meta-sentinel that maps to no single
 * terminal method; this casing difference is intentional, not an inconsistency.</p>
 *
 * @see AbstractQuery
 * @see com.landawn.abacus.jdbc.annotation.Query
 */
public enum QueryOperation {
    /**
     * Checks whether any records exist that match the query criteria.
     * Returns a {@code boolean} indicating the existence of matching records.
     *
     * <p>The supplied query is executed as-is and {@code true} is returned as soon as it yields at least one
     * row (the result set is not fully read). The framework does not add a {@code LIMIT} or {@code EXISTS}
     * clause, so write the SQL to be efficient for existence checks (e.g. {@code SELECT 1 ... LIMIT 1}).</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "SELECT 1 FROM users WHERE email = ?", op = QueryOperation.exists)
     * boolean emailExists(String email);
     * }</pre>
     *
     * @see AbstractQuery#exists()
     */
    exists,

    /**
     * Retrieves exactly one record from the query results.
     * No row is treated as an empty result, and {@code DuplicateResultException} is thrown
     * if more than one record is found.
     *
     * <p>Use this operation when you expect at most one result and want to fail fast
     * if a uniqueness constraint is violated. This is useful for queries by unique identifiers
     * or unique columns where duplicates would indicate a data integrity issue.</p>
     *
     * <p>For DAO methods, the final return value is adapted to the declared method return type
     * (for example {@code Optional}, a bare value, {@code null}, or a default primitive value).</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "SELECT * FROM users WHERE id = ?", op = QueryOperation.findOnlyOne)
     * Optional<User> getUserById(long id);
     * }</pre>
     *
     * @see AbstractQuery#findOnlyOne(Class)
     */
    findOnlyOne,

    /**
     * Retrieves the first record from the query results.
     * No row is treated as an empty result.
     *
     * <p>This operation is useful when you want at most one result but don't require
     * exactly one. The query should typically include an {@code ORDER BY} clause to
     * ensure deterministic results.</p>
     *
     * <p>For DAO methods, the final return value is adapted to the declared method return type
     * (for example {@code Optional}, a bare value, {@code null}, or a default primitive value).</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "SELECT * FROM users WHERE age >= ? ORDER BY age", op = QueryOperation.findFirst)
     * Optional<User> findYoungestAdult(int minAge);
     * }</pre>
     *
     * @see AbstractQuery#findFirst(Class)
     */
    findFirst,

    /**
     * Retrieves all matching records.
     * No rows are represented by an empty collection-like result.
     *
     * <p>This is the most common operation for queries that return multiple records.
     * All results are loaded into memory at once, so use with caution for large result sets.</p>
     *
     * <p>For DAO methods, the final return value is adapted to the declared collection-like
     * method return type.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "SELECT * FROM users WHERE active = true", op = QueryOperation.list)
     * List<User> getActiveUsers();
     * }</pre>
     *
     * @see AbstractQuery#list()
     */
    list,

    /**
     * Materializes a query result as a {@code Dataset}. It is not the general inference sentinel;
     * use {@link #DEFAULT} for inference from an arbitrary DAO return type. Although the underlying
     * fluent query API also has extractor-based {@code query(...)} overloads, extractor parameters
     * on annotation-driven DAO methods are not currently enabled.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "SELECT * FROM users WHERE age > ?", op = QueryOperation.query)
     * Dataset queryUsersByAge(int age);
     * }</pre>
     *
     * @deprecated Generally it is unnecessary to specify {@code "op = QueryOperation.query"} in {@code @Query}; rely on
     *             {@link #DEFAULT} so the framework can infer the correct operation from the method's return type.
     */
    @Deprecated
    query,

    /**
     * Returns query results as a {@code Stream} for lazy evaluation and processing.
     * Useful for handling large result sets without loading all data into memory.
     *
     * <p>The stream should be properly closed after use, preferably in a try-with-resources block.
     * This operation enables processing of large datasets with minimal memory footprint.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "SELECT * FROM users", op = QueryOperation.stream)
     * Stream<User> streamAllUsers();
     * }</pre>
     *
     * @deprecated Generally it is unnecessary to specify {@code "op = QueryOperation.stream"} in {@code @Query}; rely on
     *             {@link #DEFAULT} -- the framework will use streaming automatically when the method's return
     *             type is a {@code Stream}.
     */
    @Deprecated
    stream,

    /**
     * Retrieves the first single column value from the query result without checking for uniqueness.
     * Typically used for aggregate queries that return one value (e.g., {@code COUNT}, {@code SUM}, {@code MAX}, {@code MIN}).
     *
     * <p>Unlike {@link #queryForUnique}, this operation does not throw an exception if the
     * query returns more than one row -- it simply returns the value from the first row.
     * The query is expected to return a single column.</p>
     *
     * <p>For DAO methods, the final return value is adapted to the declared method return type
     * (for example {@code Nullable}, {@code Optional}, a bare scalar value, {@code null}, or a default primitive value).</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "SELECT MAX(salary) FROM employees", op = QueryOperation.queryForSingle)
     * Double getMaxSalary();
     *
     * @Query(value = "SELECT name FROM users WHERE id = ?", op = QueryOperation.queryForSingle)
     * String getUserName(long id);
     * }</pre>
     *
     * @see AbstractQuery#queryForSingleValue(Class)
     */
    queryForSingle,

    /**
     * Retrieves a unique single column value from the query result, ensuring at most one row exists.
     * No row is treated as an empty result, and {@code DuplicateResultException} is thrown
     * if more than one row is found.
     *
     * <p>Unlike {@link #queryForSingle}, this operation enforces uniqueness by verifying
     * that the query produces at most one row. Use this when the query targets a unique
     * column or constraint and duplicates would indicate a data integrity issue.</p>
     *
     * <p>For DAO methods, the final return value is adapted to the declared method return type
     * (for example {@code Nullable}, {@code Optional}, a bare scalar value, {@code null}, or a default primitive value).</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "SELECT email FROM users WHERE username = ?", op = QueryOperation.queryForUnique)
     * String findEmailByUsername(String username);
     * }</pre>
     *
     * @see AbstractQuery#queryForUniqueValue(Class)
     */
    queryForUnique,

    /**
     * Retrieves all {@code ResultSet}s from a stored procedure call as {@code List}s.
     * Each {@code ResultSet} is converted to a {@code List} of the specified type.
     *
     * <p>This operation is primarily used with {@code @Query} annotation for stored procedures
     * that return multiple result sets. Each result set is processed and returned
     * in a collection via {@code listAllResultSets/listAllResultSetsAndGetOutParameters}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "{call getUserBatches(?)}", op = QueryOperation.listAll, procedure = true)
     * List<List<User>> getUserBatches(long departmentId);
     * }</pre>
     *
     * @see AbstractQuery#listAllResultSets(Class)
     * @see CallableQuery#listAllResultSetsAndGetOutParameters(Class)
     */
    listAll,

    /**
     * Retrieves all {@code ResultSet}s from a stored procedure call as {@code Dataset}s.
     * Each {@code ResultSet} is converted to a {@code Dataset} for flexible data manipulation.
     *
     * <p>Similar to {@link #listAll} but returns {@code Dataset} objects which provide more
     * flexibility for data processing and transformation compared to typed {@code List}s.
     * This operation is primarily used with {@code @Query} annotation to retrieve all the {@code ResultSet}s
     * returned from the executed procedure via {@code queryAllResultSets/queryAllResultSetsAndGetOutParameters}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "{call getComplexReport(?, ?)}", op = QueryOperation.queryAll, procedure = true)
     * List<Dataset> getComplexReport(Date startDate, Date endDate);
     * }</pre>
     *
     * @see AbstractQuery#queryAllResultSets()
     * @see CallableQuery#queryAllResultSetsAndGetOutParameters()
     */
    queryAll,

    /**
     * Retrieves all {@code ResultSet}s from a stored procedure call as {@code Stream}s.
     * Result sets are discovered and processed lazily, one at a time.
     *
     * <p>With the standard DAO return type {@code Stream<Dataset>}, each individual result set is
     * fully materialized as a {@code Dataset} before that stream element is emitted; the laziness is
     * across result sets, not rows within a result set. The returned stream owns JDBC resources and
     * must be closed. This operation requires {@code @Query(procedure = true)}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "{call streamLargeDatasets()}", op = QueryOperation.streamAll, procedure = true)
     * Stream<Dataset> streamLargeDatasets();
     * }</pre>
     *
     * @see AbstractQuery#streamAllResultSets()
     */
    streamAll,

    /**
     * Executes a stored procedure and retrieves OUT parameters.
     * Used when the primary goal is to get output parameters rather than result sets.
     *
     * <p>This operation is specifically designed for stored procedures with OUT or INOUT
     * parameters. The return type must be {@code Jdbc.OutParamResult}, or a supertype capable of
     * accepting it, from which the OUT parameter values are read. The corresponding
     * {@code @Query} must set {@code procedure = true}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "{call calculateStats(?, ?, ?)}", op = QueryOperation.executeAndGetOutParameters, procedure = true)
     * @OutParameter(position = 2, sqlType = Types.INTEGER)
     * @OutParameter(position = 3, sqlType = Types.DECIMAL)
     * Jdbc.OutParamResult calculateStats(int input);
     * }</pre>
     *
     * <p>This operation is primarily used with {@code @Query} annotation to execute the target procedure
     * and get out parameters by {@code executeAndGetOutParameters}.</p>
     *
     * @see CallableQuery#executeAndGetOutParameters()
     */
    executeAndGetOutParameters,

    /**
     * Executes an {@code UPDATE} or {@code DELETE} (or other data-modifying, non-INSERT) statement and
     * obtains an {@code int} affected-row count from JDBC.
     *
     * <p>A DAO method may expose that count as {@code int}/{@code Integer},
     * {@code long}/{@code Long}, {@code boolean}/{@code Boolean} ({@code true} when the count is
     * positive), or {@code void}. Note: {@code INSERT} statements are
     * always dispatched to the dedicated insert path, which returns the generated key(s) (or
     * {@code void}), not an affected-row count — this operation does not apply to them.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "UPDATE users SET active = false WHERE last_login < ?", op = QueryOperation.update)
     * int deactivateInactiveUsers(Date threshold);
     *
     * @Query(value = "DELETE FROM users WHERE id = ?", op = QueryOperation.update)
     * int deleteUser(long id);
     * }</pre>
     *
     * @see AbstractQuery#update()
     */
    update,

    /**
     * Executes an {@code UPDATE} or {@code DELETE} (or other data-modifying, non-INSERT) statement that
     * may affect a large number of rows.
     * The selected operation obtains a {@code long} row count from JDBC for compatibility with
     * large datasets.
     *
     * <p>Use this operation when the number of affected rows might exceed the range of {@code int}.
     * This is particularly relevant for bulk operations on large tables. A DAO method may return
     * {@code long}/{@code Long}, {@code int}/{@code Integer} (with an exact-range check),
     * {@code boolean}/{@code Boolean} ({@code true} when the count is positive), or {@code void}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query(value = "DELETE FROM audit_logs WHERE created_date < ?", op = QueryOperation.largeUpdate)
     * long purgeOldAuditLogs(Date cutoffDate);
     * }</pre>
     *
     * @see AbstractQuery#largeUpdate()
     */
    largeUpdate,

    /* batchUpdate,*/

    /**
     * Default operation that lets the framework automatically determine the appropriate operation
     * based on the SQL statement type and method signature.
     *
     * <p>When {@code DEFAULT} is used, the framework analyzes the SQL statement, method return type,
     * and, for some single-result conventions, the method name. A {@code SELECT} can therefore become
     * a list, dataset, stream, existence check, first/unique-row lookup, or scalar lookup. An
     * {@code UPDATE}/{@code DELETE} becomes an update operation; an {@code INSERT} is dispatched to
     * the dedicated generated-key path.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query("SELECT * FROM users")  // QueryOperation defaults to QueryOperation.DEFAULT
     * List<User> getAllUsers();      // Framework infers QueryOperation.list
     *
     * @Query("DELETE FROM users WHERE id = ?")  // QueryOperation defaults to QueryOperation.DEFAULT
     * int deleteUser(long id);                  // Framework infers QueryOperation.update
     * }</pre>
     *
     */
    DEFAULT

}
