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
 * Fluent wrapper around a {@link PreparedStatement}.
 *
 * <p>{@code PreparedQuery} exposes the generic execution and mapping operations defined by
 * {@link AbstractQuery} for SQL that already uses positional JDBC parameters. The wrapped
 * statement is closed after execution unless {@link #closeAfterExecution(boolean)} is used
 * to keep it open for reuse.</p>
 *
 * @see AbstractQuery
 * @see NamedQuery
 * @see CallableQuery
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/PreparedStatement.html">PreparedStatement</a>
 */
public final class PreparedQuery extends AbstractQuery<PreparedStatement, PreparedQuery> {

    /**
     * Constructs a new {@code PreparedQuery} instance with the specified {@link PreparedStatement}.
     * The PreparedStatement should be properly initialized with a valid SQL query before being passed to this constructor.
     * 
     * <p>The ownership of the {@code PreparedStatement} is transferred to this {@code PreparedQuery} instance,
     * which means this class will be responsible for closing it based on the {@code closeAfterExecution} flag.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Connection conn = dataSource.getConnection();
     * PreparedStatement pstmt = conn.prepareStatement("SELECT * FROM employees WHERE department = ?");
     * PreparedQuery query = new PreparedQuery(pstmt);
     * }</pre>
     * 
     * @param stmt the {@link PreparedStatement} to be wrapped by this query. Must not be {@code null}.
     */
    PreparedQuery(final PreparedStatement stmt) {
        super(stmt);
    }
}
