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
 * statement is closed after a materializing execution unless {@link #closeAfterExecution(boolean)}
 * is used to keep it open for reuse. Lazy streams retain the statement until the stream is closed,
 * and asynchronous operations retain it until the task completes.</p>
 *
 * @see AbstractQuery
 * @see NamedQuery
 * @see CallableQuery
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/PreparedStatement.html">PreparedStatement</a>
 */
public final class PreparedQuery extends AbstractQuery<PreparedStatement, PreparedQuery> {

    /**
     * Constructs a new {@code PreparedQuery} instance wrapping the specified {@link PreparedStatement}.
     * The {@code PreparedStatement} should be properly initialized with a valid SQL query before being
     * passed to this constructor.
     *
     * <p>This constructor is package-private; client code should obtain instances through factory
     * methods such as {@link JdbcUtil#prepareQuery(java.sql.Connection, String)} rather than calling
     * this constructor directly.</p>
     *
     * <p>The ownership of the {@code PreparedStatement} is transferred to this {@code PreparedQuery}
     * instance, which means this class will be responsible for closing it based on the
     * {@code closeAfterExecution} flag.</p>
     *
     * @param stmt the {@link PreparedStatement} to be wrapped by this query. Must not be {@code null}.
     * @throws IllegalArgumentException if {@code stmt} is {@code null}
     */
    PreparedQuery(final PreparedStatement stmt) {
        super(stmt);
    }
}
