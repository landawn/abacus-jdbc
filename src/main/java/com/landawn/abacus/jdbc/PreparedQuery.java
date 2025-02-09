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
 * The backed {@code PreparedStatement/CallableStatement} will be closed by default
 * after any execution methods(which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/findOnlyOne/list/execute/...).
 * except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.
 *
 * <br />
 * Generally, don't cache or reuse the instance of this class,
 * except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.
 *
 * <br />
 * The {@code ResultSet} returned by query will always be closed after execution, even {@code 'closeAfterExecution'} flag is set to {@code false}.
 *
 * <br />
 * Remember: parameter/column index in {@code PreparedStatement/ResultSet} starts from 1, not 0.
 *
 *
 * @see {@link com.landawn.abacus.annotation.ReadOnly}
 * @see {@link com.landawn.abacus.annotation.ReadOnlyId}
 * @see {@link com.landawn.abacus.annotation.NonUpdatable}
 * @see {@link com.landawn.abacus.annotation.Transient}
 * @see {@link com.landawn.abacus.annotation.Table}
 * @see {@link com.landawn.abacus.annotation.Column}
 *
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Connection.html">Connection</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Statement.html">Statement</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/PreparedStatement.html">PreparedStatement</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/ResultSet.html">ResultSet</a>
 */
public final class PreparedQuery extends AbstractQuery<PreparedStatement, PreparedQuery> {

    PreparedQuery(final PreparedStatement stmt) {
        super(stmt);
    }
}
