/*
 * Copyright (c) 2020, Haiyang Li.
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

import java.sql.PreparedStatement;

/**
 * A wrapper class for {@link PreparedStatement} that provides a fluent API for executing parameterized SQL queries.
 * This class simplifies the execution of prepared statements by providing convenient methods for parameter binding,
 * result retrieval, and resource management.
 * 
 * <p>The backed {@code PreparedStatement/CallableStatement} will be closed by default
 * after any execution methods (which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed,
 * for example, get/query/queryForInt/Long/../findFirst/findOnlyOne/list/execute/...).
 * Except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.
 * 
 * <p><b>Important Notes:</b>
 * <ul>
 *   <li>Generally, don't cache or reuse the instance of this class,
 *       except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.</li>
 *   <li>The {@code ResultSet} returned by query will always be closed after execution, even {@code 'closeAfterExecution'} flag is set to {@code false}.</li>
 *   <li>Remember: parameter/column index in {@code PreparedStatement/ResultSet} starts from 1, not 0.</li>
 * </ul>
 *
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * // Simple query execution
 * PreparedQuery query = JdbcUtil.prepareQuery(conn, "SELECT * FROM users WHERE id = ?");
 * User user = query.setInt(1, 123)
 *                  .findFirst(User.class)
 *                  .orElse(null);
 * 
 * // Multiple executions with closeAfterExecution(false)
 * PreparedQuery reusableQuery = JdbcUtil.prepareQuery(connection, "SELECT * FROM users WHERE age > ?")
 *                                   .closeAfterExecution(false);
 * List<User> adults = reusableQuery.setInt(1, 18).list(User.class);
 * List<User> seniors = reusableQuery.setInt(1, 65).list(User.class);
 * reusableQuery.close(); // Remember to close manually
 * }</pre>
 * 
 * <p>This class is thread-safe only if the underlying {@code PreparedStatement} is not accessed concurrently
 * from multiple threads. It's recommended to create a new instance for each thread or use proper synchronization.
 *
 * @see com.landawn.abacus.annotation.ReadOnly
 * @see com.landawn.abacus.annotation.ReadOnlyId
 * @see com.landawn.abacus.annotation.NonUpdatable
 * @see com.landawn.abacus.annotation.Transient
 * @see com.landawn.abacus.annotation.Table
 * @see com.landawn.abacus.annotation.Column
 * @see AbstractQuery
 * @see NamedQuery
 * @see CallableQuery
 *
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Connection.html">Connection</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Statement.html">Statement</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/PreparedStatement.html">PreparedStatement</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/ResultSet.html">ResultSet</a>
 * 
 */
public final class PreparedQuery extends AbstractQuery<PreparedStatement, PreparedQuery> {

    /**
     * Constructs a new {@code PreparedQuery} instance with the specified {@link PreparedStatement}.
     * The PreparedStatement should be properly initialized with a valid SQL query before being passed to this constructor.
     * 
     * <p>The ownership of the {@code PreparedStatement} is transferred to this {@code PreparedQuery} instance,
     * which means this class will be responsible for closing it based on the {@code closeAfterExecution} flag.
     * 
     * <p><b>Usage Example:</b></p>
     * <pre>{@code
     * Connection conn = dataSource.getConnection();
     * PreparedStatement pstmt = conn.prepareStatement("SELECT * FROM employees WHERE department = ?");
     * PreparedQuery query = new PreparedQuery(pstmt);
     * }</pre>
     * 
     * @param stmt the {@link PreparedStatement} to be wrapped by this query. Must not be {@code null}.
     * @throws IllegalArgumentException if the statement has already been closed
     */
    PreparedQuery(final PreparedStatement stmt) {
        super(stmt);
    }
}