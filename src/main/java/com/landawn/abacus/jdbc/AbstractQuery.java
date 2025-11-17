/*
 * Copyright (c) 2019, Haiyang Li.
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

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.annotation.LazyEvaluation;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.Jdbc.BiResultExtractor;
import com.landawn.abacus.jdbc.Jdbc.BiRowConsumer;
import com.landawn.abacus.jdbc.Jdbc.BiRowFilter;
import com.landawn.abacus.jdbc.Jdbc.BiRowMapper;
import com.landawn.abacus.jdbc.Jdbc.ResultExtractor;
import com.landawn.abacus.jdbc.Jdbc.RowConsumer;
import com.landawn.abacus.jdbc.Jdbc.RowFilter;
import com.landawn.abacus.jdbc.Jdbc.RowMapper;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.type.TypeFactory;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.NoCachingNoUpdating.DisposableObjArray;
import com.landawn.abacus.util.Strings;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.Tuple;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.u.Nullable;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.u.OptionalBoolean;
import com.landawn.abacus.util.u.OptionalByte;
import com.landawn.abacus.util.u.OptionalChar;
import com.landawn.abacus.util.u.OptionalDouble;
import com.landawn.abacus.util.u.OptionalFloat;
import com.landawn.abacus.util.u.OptionalInt;
import com.landawn.abacus.util.u.OptionalLong;
import com.landawn.abacus.util.u.OptionalShort;
import com.landawn.abacus.util.stream.ObjIteratorEx;
import com.landawn.abacus.util.stream.Stream;

/**
 * Abstract base class for JDBC query operations that provides a fluent API for executing SQL queries
 * and updates with PreparedStatement. This class handles parameter setting, result set processing,
 * batch operations, and resource management.
 *
 * <p>Performance Tips:</p>
 * <ul>
 * <li>Avoid unnecessary/repeated database calls.</li>
 * <li>Only fetch the columns you need or update the columns you want.</li>
 * <li>Index is the key point in a lot of database performance issues.</li>
 * </ul>
 *
 * <p>The backed {@code PreparedStatement/CallableStatement} will be closed by default
 * after any execution methods (which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed,
 * for example, get/query/queryForInt/Long/../findFirst/findOnlyOne/list/execute/...).
 * Except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.</p>
 *
 * <p>Generally, don't cache or reuse the instance of this class,
 * except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.</p>
 *
 * <p>Remember: parameter/column index in {@code PreparedStatement/ResultSet} starts from 1, not 0.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * JdbcUtil.prepareQuery(connection, "SELECT * FROM users WHERE age > ?")
 *     .setInt(1, 18)
 *     .list(User.class);
 * }</pre>
 *
 * @param <Stmt> the type of PreparedStatement (PreparedStatement or CallableStatement)
 * @param <This> the concrete query type for method chaining (enables fluent API)
 *
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Connection.html">Connection</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Statement.html">Statement</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/PreparedStatement.html">PreparedStatement</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/ResultSet.html">ResultSet</a>
 */
@SuppressWarnings("resource")
public abstract class AbstractQuery<Stmt extends PreparedStatement, This extends AbstractQuery<Stmt, This>> implements AutoCloseable {

    static final Logger logger = LoggerFactory.getLogger(AbstractQuery.class);

    static final Set<Class<?>> stmtParameterClasses = new HashSet<>();

    static {
        final Method[] methods = PreparedStatement.class.getDeclaredMethods();

        for (final Method m : methods) {
            if (Modifier.isPublic(m.getModifiers()) && m.getName().startsWith("set") && m.getParameterCount() == 2
                    && m.getParameterTypes()[0].equals(int.class)) {
                stmtParameterClasses.add(m.getParameterTypes()[1]);
            }
        }

        final List<Class<?>> primitiveTypes = N.asList(boolean.class, char.class, byte.class, short.class, int.class, long.class, float.class, double.class);

        for (final Class<?> cls : primitiveTypes) {
            stmtParameterClasses.add(cls);
            stmtParameterClasses.add(ClassUtil.wrap(cls));
            stmtParameterClasses.add(N.newArray(cls, 0).getClass());
            stmtParameterClasses.add(N.newArray(ClassUtil.wrap(cls), 0).getClass());
        }

        stmtParameterClasses.remove(Object.class);
    }

    @SuppressWarnings("rawtypes")
    static final Throwables.BiConsumer<AbstractQuery, PreparedStatement, SQLException> defaultAddBatchAction = (q, s) -> s.addBatch();

    Throwables.BiConsumer<? super This, ? super Stmt, SQLException> addBatchAction = defaultAddBatchAction;

    final Stmt stmt;

    boolean isFetchDirectionSet = false;

    boolean isBatch = false;

    boolean isCloseAfterExecution = true;

    boolean isClosed = false;

    Runnable closeHandler;

    AbstractQuery(final Stmt stmt) {
        this.stmt = stmt;
    }

    //        /**
    //         * It's designed to void try-catch.
    //         * This method should be called immediately after {@code JdbcUtil#prepareCallableQuery/SQLExecutor#prepareQuery}.
    //         *
    //         * @return
    //         */
    //        public Try<Q> tried() {
    //            assertNotClosed();
    //
    //            return Try.of((Q) this);
    //        }

    /**
     * Sets whether the PreparedStatement should be closed after execution.
     * By default, the statement is closed after execution to prevent resource leaks.
     * 
     * <p>Set to {@code false} if you want to reuse the statement for multiple executions,
     * but ensure you manually close it when done.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * AbstractQuery query = JdbcUtil.prepareQuery(connection, sql)
     *     .closeAfterExecution(false);
     * try {
     *     query.setInt(1, 10).list();
     *     query.setInt(1, 20).list();
     * } finally {
     *     query.close();
     * }
     * }</pre>
     *
     * @param closeAfterExecution If {@code true}, the statement will be closed after execution. Default is {@code true}.
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalStateException if the query is already closed
     */
    public This closeAfterExecution(final boolean closeAfterExecution) throws IllegalStateException {
        assertNotClosed();

        isCloseAfterExecution = closeAfterExecution;

        return (This) this;
    }

    boolean isCloseAfterExecution() {
        return isCloseAfterExecution;
    }

    /**
     * Registers a callback to be executed when this query is closed.
     * Multiple handlers can be registered and will be executed in reverse order of registration.
     * 
     * <p>This is useful for cleaning up resources that were created for this query.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.onClose(() -> System.out.println("Query closed"))
     *      .onClose(() -> releaseResources());
     * }</pre>
     *
     * @param closeHandler A task to execute after this {@code Query} is closed
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException if closeHandler is null
     * @throws IllegalStateException if this query is already closed
     */
    @SuppressWarnings("hiding")
    public This onClose(final Runnable closeHandler) throws IllegalArgumentException, IllegalStateException {
        checkArgNotNull(closeHandler, cs.closeHandler);
        assertNotClosed();

        if (this.closeHandler == null) {
            this.closeHandler = closeHandler;
        } else {
            final Runnable tmp = this.closeHandler;

            this.closeHandler = () -> {
                try {
                    closeHandler.run();
                } finally {
                    tmp.run();
                }
            };
        }

        return (This) this;
    }

    /**
     * Sets a SQL NULL value for the specified parameter.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setNull(1, Types.VARCHAR)
     *      .setNull(2, Types.INTEGER);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set to {@code null}, starting from 1
     * @param sqlType The SQL type code defined in {@link java.sql.Types}
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     * @see java.sql.Types
     */
    public This setNull(final int parameterIndex, final int sqlType) throws SQLException {
        stmt.setNull(parameterIndex, sqlType);

        return (This) this;
    }

    /**
     * Sets a SQL NULL value for the specified parameter with a type name.
     * This method is used for user-defined types and REF types.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setNull(1, Types.STRUCT, "MY_TYPE");
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set to {@code null}, starting from 1
     * @param sqlType The SQL type code defined in {@link java.sql.Types}
     * @param typeName The fully-qualified name of an SQL user-defined type
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setNull(final int parameterIndex, final int sqlType, final String typeName) throws SQLException {
        stmt.setNull(parameterIndex, sqlType, typeName);

        return (This) this;
    }

    /**
     * Sets a boolean parameter value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setBoolean(1, true)
     *      .setBoolean(2, false);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The boolean value to set
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setBoolean(final int parameterIndex, final boolean x) throws SQLException {
        stmt.setBoolean(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets a Boolean parameter value, handling null values.
     * If the value is null, sets the parameter to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Boolean value = getOptionalBoolean();
     * query.setBoolean(1, value);  // handles null automatically
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Boolean value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setBoolean(final int parameterIndex, final Boolean x) throws SQLException {
        if (x == null) {
            stmt.setNull(parameterIndex, java.sql.Types.BOOLEAN);
        } else {
            stmt.setBoolean(parameterIndex, x);
        }

        return (This) this;
    }

    /**
     * Sets a byte parameter value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setByte(1, (byte)127);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The byte value to set
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setByte(final int parameterIndex, final byte x) throws SQLException {
        stmt.setByte(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets a Byte parameter value, handling null values.
     * If the value is null, sets the parameter to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Byte value = getOptionalByte();
     * query.setByte(1, value);  // handles null automatically
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Byte value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setByte(final int parameterIndex, final Byte x) throws SQLException {
        if (x == null) {
            stmt.setNull(parameterIndex, java.sql.Types.TINYINT);
        } else {
            stmt.setByte(parameterIndex, x);
        }

        return (This) this;
    }

    /**
     * Sets a Byte parameter value with a default value for null.
     * If the value is null, uses the specified default value instead of SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setByte(1, nullableByte, (byte)0);  // use 0 if null
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Byte value to set, or {@code null} to use the default value
     * @param defaultValueForNull The byte value to use if {@code x} is {@code null}
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setByte(final int parameterIndex, final Byte x, final byte defaultValueForNull) throws SQLException {
        if (x == null) {
            stmt.setByte(parameterIndex, defaultValueForNull);
        } else {
            stmt.setByte(parameterIndex, x);
        }

        return (This) this;
    }

    /**
     * Sets a short parameter value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setShort(1, (short)1000);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The short value to set
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setShort(final int parameterIndex, final short x) throws SQLException {
        stmt.setShort(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets a Short parameter value, handling null values.
     * If the value is null, sets the parameter to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Short value = getOptionalShort();
     * query.setShort(1, value);  // handles null automatically
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Short value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setShort(final int parameterIndex, final Short x) throws SQLException {
        if (x == null) {
            stmt.setNull(parameterIndex, java.sql.Types.SMALLINT);
        } else {
            stmt.setShort(parameterIndex, x);
        }

        return (This) this;
    }

    /**
     * Sets a Short parameter value with a default value for null.
     * If the value is null, uses the specified default value instead of SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setShort(1, nullableShort, (short)0);  // use 0 if null
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Short value to set, or {@code null} to use the default value
     * @param defaultValueForNull The short value to use if {@code x} is {@code null}
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setShort(final int parameterIndex, final Short x, final short defaultValueForNull) throws SQLException {
        if (x == null) {
            stmt.setShort(parameterIndex, defaultValueForNull);
        } else {
            stmt.setShort(parameterIndex, x);
        }

        return (This) this;
    }

    /**
     * Sets an int parameter value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setInt(1, 42)
     *      .setInt(2, userId);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The int value to set
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setInt(final int parameterIndex, final int x) throws SQLException {
        stmt.setInt(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets an Integer parameter value, handling null values.
     * If the value is null, sets the parameter to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Integer age = user.getAge();  // might be null
     * query.setInt(1, age);  // handles null automatically
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Integer value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setInt(final int parameterIndex, final Integer x) throws SQLException {
        if (x == null) {
            stmt.setNull(parameterIndex, java.sql.Types.INTEGER);
        } else {
            stmt.setInt(parameterIndex, x);
        }

        return (This) this;
    }

    /**
     * Sets an Integer parameter value with a default value for null.
     * If the value is null, uses the specified default value instead of SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setInt(1, nullableCount, 0);  // use 0 if null
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Integer value to set, or {@code null} to use the default value
     * @param defaultValueForNull The int value to use if {@code x} is {@code null}
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public This setInt(final int parameterIndex, final Integer x, final int defaultValueForNull) throws SQLException {
        if (x == null) {
            stmt.setInt(parameterIndex, defaultValueForNull);
        } else {
            stmt.setInt(parameterIndex, x);
        }

        return (This) this;
    }

    //    /**
    //     * Sets the int.
    //     *
    //     * @param parameterIndex
    //     * @param x
    //     * @return
    //     * @throws SQLException
    //     */
    //    @Beta
    //    public This setInt(final int parameterIndex, final String x) throws SQLException {
    //        if (Strings.isEmpty(x)) {
    //            stmt.setNull(parameterIndex, java.sql.Types.INTEGER);
    //        } else {
    //            boolean noException = false;
    //
    //            try {
    //                stmt.setInt(parameterIndex, Numbers.toInt(x));
    //                noException = true;
    //            } finally {
    //                if (!noException) {
    //                    close();
    //                }
    //            }
    //        }
    //
    //        return (This) this;
    //    }

    /**
     * Sets a char parameter value as an integer.
     * The character is stored as its numeric value (Unicode code point).
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setInt(1, 'A');  // stores 65
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The char value to set as an integer
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     * @deprecated Generally, {@code char} should be saved as {@code String} in the database
     * @see #setString(int, char)
     */
    @Deprecated
    @Beta
    public This setInt(final int parameterIndex, final char x) throws SQLException {
        stmt.setInt(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets a Character parameter value as an integer, handling null values.
     * The character is stored as its numeric value (Unicode code point).
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Character grade = getGrade();  // might be null
     * query.setInt(1, grade);  // stores as integer or NULL
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Character value to set as an integer, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     * @deprecated Generally, {@code char} should be saved as {@code String} in the database
     * @see #setString(int, Character)
     */
    @Deprecated
    @Beta
    public This setInt(final int parameterIndex, final Character x) throws SQLException {
        if (x == null) {
            stmt.setNull(parameterIndex, java.sql.Types.INTEGER);
        } else {
            stmt.setInt(parameterIndex, x);
        }

        return (This) this;
    }

    /**
     * Sets a long parameter value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setLong(1, System.currentTimeMillis())
     *      .setLong(2, recordId);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The long value to set
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setLong(final int parameterIndex, final long x) throws SQLException {
        stmt.setLong(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets a Long parameter value, handling null values.
     * If the value is null, sets the parameter to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Long timestamp = getOptionalTimestamp();
     * query.setLong(1, timestamp);  // handles null automatically
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Long value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setLong(final int parameterIndex, final Long x) throws SQLException {
        if (x == null) {
            stmt.setNull(parameterIndex, java.sql.Types.BIGINT);
        } else {
            stmt.setLong(parameterIndex, x);
        }

        return (This) this;
    }

    /**
     * Sets a Long parameter value with a default value for null.
     * If the value is null, uses the specified default value instead of SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setLong(1, nullableId, -1L);  // use -1 if null
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Long value to set, or {@code null} to use the default value
     * @param defaultValueForNull The long value to use if {@code x} is {@code null}
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public This setLong(final int parameterIndex, final Long x, final long defaultValueForNull) throws SQLException {
        if (x == null) {
            stmt.setLong(parameterIndex, defaultValueForNull);
        } else {
            stmt.setLong(parameterIndex, x);
        }

        return (This) this;
    }

    //    /**
    //     * Sets the long.
    //     *
    //     * @param parameterIndex
    //     * @param x
    //     * @return
    //     * @throws SQLException
    //     */
    //    @Beta
    //    public This setLong(final int parameterIndex, final String x) throws SQLException {
    //        if (Strings.isEmpty(x)) {
    //            stmt.setNull(parameterIndex, java.sql.Types.BIGINT);
    //        } else {
    //            boolean noException = false;
    //
    //            try {
    //                stmt.setLong(parameterIndex, Numbers.toLong(x));
    //                noException = true;
    //            } finally {
    //                if (!noException) {
    //                    close();
    //                }
    //            }
    //        }
    //
    //        return (This) this;
    //    }

    /**
     * Sets a BigInteger parameter value as a long.
     * The BigInteger must be within the range of a long value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BigInteger bigValue = new BigInteger("123456789");
     * query.setLong(1, bigValue);  // converts to long
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The BigInteger value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     * @throws ArithmeticException If the BigInteger value is too large for a long
     */
    public This setLong(final int parameterIndex, final BigInteger x) throws SQLException {
        if (x == null) {
            stmt.setNull(parameterIndex, java.sql.Types.BIGINT);
        } else {
            boolean noException = false;

            try {
                stmt.setLong(parameterIndex, x.longValueExact());
                noException = true;
            } finally {
                if (!noException) {
                    close();
                }
            }
        }

        return (This) this;
    }

    /**
     * Sets a float parameter value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setFloat(1, 3.14f)
     *      .setFloat(2, temperature);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The float value to set
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setFloat(final int parameterIndex, final float x) throws SQLException {
        stmt.setFloat(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets a Float parameter value, handling null values.
     * If the value is null, sets the parameter to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Float percentage = calculatePercentage();  // might return null
     * query.setFloat(1, percentage);  // handles null automatically
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Float value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setFloat(final int parameterIndex, final Float x) throws SQLException {
        if (x == null) {
            stmt.setNull(parameterIndex, java.sql.Types.FLOAT);
        } else {
            stmt.setFloat(parameterIndex, x);
        }

        return (This) this;
    }

    /**
     * Sets a Float parameter value with a default value for null.
     * If the value is null, uses the specified default value instead of SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setFloat(1, nullableRating, 0.0f);  // use 0.0 if null
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Float value to set, or {@code null} to use the default value
     * @param defaultValueForNull The float value to use if {@code x} is {@code null}
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setFloat(final int parameterIndex, final Float x, final float defaultValueForNull) throws SQLException {
        if (x == null) {
            stmt.setFloat(parameterIndex, defaultValueForNull);
        } else {
            stmt.setFloat(parameterIndex, x);
        }

        return (This) this;
    }

    //    /**
    //     * Sets the float.
    //     *
    //     * @param parameterIndex
    //     * @param x
    //     * @return
    //     * @throws SQLException
    //     */
    //    public This setFloat(final int parameterIndex, final String x) throws SQLException {
    //        if (Strings.isEmpty(x)) {
    //            stmt.setNull(parameterIndex, java.sql.Types.FLOAT);
    //        } else {
    //            boolean noException = false;
    //
    //            try {
    //                stmt.setFloat(parameterIndex, Numbers.toFloat(x));
    //                noException = true;
    //            } finally {
    //                if (!noException) {
    //                    close();
    //                }
    //            }
    //
    //        }
    //
    //        return (This) this;
    //    }

    /**
     * Sets a double parameter value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setDouble(1, 3.14159)
     *      .setDouble(2, price);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The double value to set
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setDouble(final int parameterIndex, final double x) throws SQLException {
        stmt.setDouble(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets a Double parameter value, handling null values.
     * If the value is null, sets the parameter to SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Double amount = calculateAmount();  // might return null
     * query.setDouble(1, amount);  // handles null automatically
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Double value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setDouble(final int parameterIndex, final Double x) throws SQLException {
        if (x == null) {
            stmt.setNull(parameterIndex, java.sql.Types.DOUBLE);
        } else {
            stmt.setDouble(parameterIndex, x);
        }

        return (This) this;
    }

    /**
     * Sets a Double parameter value with a default value for null.
     * If the value is null, uses the specified default value instead of SQL NULL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setDouble(1, nullablePrice, 0.0);  // use 0.0 if null
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Double value to set, or {@code null} to use the default value
     * @param defaultValueForNull The double value to use if {@code x} is {@code null}
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setDouble(final int parameterIndex, final Double x, final double defaultValueForNull) throws SQLException {
        if (x == null) {
            stmt.setDouble(parameterIndex, defaultValueForNull);
        } else {
            stmt.setDouble(parameterIndex, x);
        }

        return (This) this;
    }

    //    /**
    //     * Sets the double.
    //     *
    //     * @param parameterIndex
    //     * @param x
    //     * @return
    //     * @throws SQLException
    //     */
    //    public This setDouble(final int parameterIndex, final String x) throws SQLException {
    //        if (Strings.isEmpty(x)) {
    //            stmt.setNull(parameterIndex, java.sql.Types.DOUBLE);
    //        } else {
    //            boolean noException = false;
    //
    //            try {
    //                stmt.setDouble(parameterIndex, Numbers.toDouble(x));
    //                noException = true;
    //            } finally {
    //                if (!noException) {
    //                    close();
    //                }
    //            }
    //
    //        }
    //
    //        return (This) this;
    //    }

    /**
     * Sets a BigDecimal parameter value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BigDecimal price = new BigDecimal("19.99");
     * query.setBigDecimal(1, price);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The BigDecimal value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setBigDecimal(final int parameterIndex, final BigDecimal x) throws SQLException {
        stmt.setBigDecimal(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets a BigInteger parameter value as a BigDecimal.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BigInteger bigInt = new BigInteger("123456789012345678901234567890");
     * query.setBigDecimal(1, bigInt);  // converts to BigDecimal
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The BigInteger value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setBigDecimal(final int parameterIndex, final BigInteger x) throws SQLException {
        if (x == null) {
            stmt.setNull(parameterIndex, Types.DECIMAL);
        } else {
            stmt.setBigDecimal(parameterIndex, new BigDecimal(x));
        }

        return (This) this;
    }

    /**
     * Sets a BigInteger parameter value as a String.
     * This is useful for databases that don't have native support for arbitrarily large integers.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BigInteger largeNumber = new BigInteger("99999999999999999999999999999");
     * query.setBigIntegerAsString(1, largeNumber);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The BigInteger value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     * @see #setString(int, BigInteger)
     */
    @Beta
    public This setBigIntegerAsString(final int parameterIndex, final BigInteger x) throws SQLException {
        return setString(parameterIndex, x);
    }

    /**
     * Sets a String parameter value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setString(1, "John Doe")
     *      .setString(2, email);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The String value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setString(final int parameterIndex, final String x) throws SQLException {
        stmt.setString(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets a CharSequence parameter value as a String.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * StringBuilder sb = new StringBuilder("Hello World");
     * query.setString(1, sb);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The CharSequence value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setString(final int parameterIndex, final CharSequence x) throws SQLException {
        stmt.setString(parameterIndex, x == null ? null : x.toString());

        return (This) this;
    }

    /**
     * Sets a char parameter value as a String.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setString(1, 'A');  // stores as "A"
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The char value to set as a String
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setString(final int parameterIndex, final char x) throws SQLException {
        stmt.setString(parameterIndex, String.valueOf(x));

        return (This) this;
    }

    /**
     * Sets a Character parameter value as a String, handling null values.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Character initial = user.getMiddleInitial();  // might be null
     * query.setString(1, initial);  // handles null automatically
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Character value to set as a String, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setString(final int parameterIndex, final Character x) throws SQLException {
        stmt.setString(parameterIndex, x == null ? null : String.valueOf(x));

        return (This) this;
    }

    /**
     * Sets a BigInteger parameter value as a String.
     * This is useful for databases that don't have native support for arbitrarily large integers.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BigInteger largeNumber = new BigInteger("99999999999999999999999999999");
     * query.setString(1, largeNumber);  // stores as string
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The BigInteger value to set as a String, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     * @see #setBigIntegerAsString(int, BigInteger)
     */
    @Beta
    public This setString(final int parameterIndex, final BigInteger x) throws SQLException {
        if (x == null) {
            stmt.setNull(parameterIndex, Types.VARCHAR);
        } else {
            stmt.setString(parameterIndex, x.toString(10));
        }

        return (This) this;
    }

    /**
     * Sets a national character set String (NCHAR, NVARCHAR, LONGNVARCHAR) parameter value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setNString(1, "こんにちは");  // Japanese text
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The String value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setNString(final int parameterIndex, final String x) throws SQLException {
        stmt.setNString(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets a national character set CharSequence (NCHAR, NVARCHAR, LONGNVARCHAR) parameter value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * StringBuilder text = new StringBuilder("世界");
     * query.setNString(1, text);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The CharSequence value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setNString(final int parameterIndex, final CharSequence x) throws SQLException {
        stmt.setNString(parameterIndex, x == null ? null : x.toString());

        return (This) this;
    }

    /**
     * Sets a java.sql.Date parameter value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * java.sql.Date date = java.sql.Date.valueOf("2023-12-25");
     * query.setDate(1, date);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Date value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setDate(final int parameterIndex, final java.sql.Date x) throws SQLException {
        stmt.setDate(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets a java.util.Date parameter value as a SQL Date.
     * The time portion of the date is truncated.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * java.util.Date utilDate = new java.util.Date();
     * query.setDate(1, utilDate);  // converts to SQL Date
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Date value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setDate(final int parameterIndex, final java.util.Date x) throws SQLException {
        stmt.setDate(parameterIndex, x == null ? null : x instanceof java.sql.Date ? (java.sql.Date) x : new java.sql.Date(x.getTime()));

        return (This) this;
    }

    /**
     * Sets a SQL Date parameter value using a Calendar for timezone conversion.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
     * query.setDate(1, sqlDate, cal);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Date value to set, or {@code null} to set SQL NULL
     * @param cal The Calendar object to use for timezone conversion
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setDate(final int parameterIndex, final java.sql.Date x, final Calendar cal) throws SQLException {
        stmt.setDate(parameterIndex, x, cal);

        return (This) this;
    }

    /**
     * Sets a LocalDate parameter value as a SQL Date.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * LocalDate today = LocalDate.now();
     * query.setDate(1, today);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The LocalDate value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setDate(final int parameterIndex, final LocalDate x) throws SQLException {
        stmt.setDate(parameterIndex, x == null ? null : java.sql.Date.valueOf(x));

        return (This) this;
    }

    /**
     * Sets a java.sql.Time parameter value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * java.sql.Time time = java.sql.Time.valueOf("14:30:00");
     * query.setTime(1, time);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Time value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setTime(final int parameterIndex, final java.sql.Time x) throws SQLException {
        stmt.setTime(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets a java.util.Date parameter value as a SQL Time.
     * Only the time portion of the date is used.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * java.util.Date utilDate = new java.util.Date();
     * query.setTime(1, utilDate);  // extracts time portion
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Date value to set as Time, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setTime(final int parameterIndex, final java.util.Date x) throws SQLException {
        stmt.setTime(parameterIndex, x == null ? null : x instanceof java.sql.Time ? (java.sql.Time) x : new java.sql.Time(x.getTime()));

        return (This) this;
    }

    /**
     * Sets a SQL Time parameter value using a Calendar for timezone conversion.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
     * query.setTime(1, sqlTime, cal);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Time value to set, or {@code null} to set SQL NULL
     * @param cal The Calendar object to use for timezone conversion
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setTime(final int parameterIndex, final java.sql.Time x, final Calendar cal) throws SQLException {
        stmt.setTime(parameterIndex, x, cal);

        return (This) this;
    }

    /**
     * Sets a LocalTime parameter value as a SQL Time.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * LocalTime now = LocalTime.now();
     * query.setTime(1, now);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The LocalTime value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setTime(final int parameterIndex, final LocalTime x) throws SQLException {
        stmt.setTime(parameterIndex, x == null ? null : java.sql.Time.valueOf(x));

        return (This) this;
    }

    /**
     * Sets a java.sql.Timestamp parameter value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Timestamp ts = new Timestamp(System.currentTimeMillis());
     * query.setTimestamp(1, ts);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Timestamp value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setTimestamp(final int parameterIndex, final java.sql.Timestamp x) throws SQLException {
        stmt.setTimestamp(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets a java.util.Date parameter value as a SQL Timestamp.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * java.util.Date date = new java.util.Date();
     * query.setTimestamp(1, date);  // converts to Timestamp
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Date value to set as Timestamp, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setTimestamp(final int parameterIndex, final java.util.Date x) throws SQLException {
        stmt.setTimestamp(parameterIndex, x == null ? null : x instanceof java.sql.Timestamp ? (java.sql.Timestamp) x : new java.sql.Timestamp(x.getTime()));

        return (This) this;
    }

    /**
     * Sets a SQL Timestamp parameter value using a Calendar for timezone conversion.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
     * query.setTimestamp(1, timestamp, cal);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Timestamp value to set, or {@code null} to set SQL NULL
     * @param cal The Calendar object to use for timezone conversion
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setTimestamp(final int parameterIndex, final java.sql.Timestamp x, final Calendar cal) throws SQLException {
        stmt.setTimestamp(parameterIndex, x, cal);

        return (This) this;
    }

    /**
     * Sets a LocalDateTime parameter value as a SQL Timestamp.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * LocalDateTime now = LocalDateTime.now();
     * query.setTimestamp(1, now);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The LocalDateTime value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setTimestamp(final int parameterIndex, final LocalDateTime x) throws SQLException {
        stmt.setTimestamp(parameterIndex, x == null ? null : Timestamp.valueOf(x));

        return (This) this;
    }

    /**
     * Sets a ZonedDateTime parameter value as a SQL Timestamp.
     * The timestamp is converted to the system's default timezone.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ZonedDateTime zonedNow = ZonedDateTime.now();
     * query.setTimestamp(1, zonedNow);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The ZonedDateTime value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setTimestamp(final int parameterIndex, final ZonedDateTime x) throws SQLException {
        stmt.setTimestamp(parameterIndex, x == null ? null : Timestamp.from(x.toInstant()));

        return (This) this;
    }

    /**
     * Sets an OffsetDateTime parameter value as a SQL Timestamp.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OffsetDateTime offsetNow = OffsetDateTime.now();
     * query.setTimestamp(1, offsetNow);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The OffsetDateTime value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setTimestamp(final int parameterIndex, final OffsetDateTime x) throws SQLException {
        stmt.setTimestamp(parameterIndex, x == null ? null : Timestamp.from(x.toInstant()));

        return (This) this;
    }

    /**
     * Sets an Instant parameter value as a SQL Timestamp.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Instant now = Instant.now();
     * query.setTimestamp(1, now);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Instant value to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setTimestamp(final int parameterIndex, final Instant x) throws SQLException {
        stmt.setTimestamp(parameterIndex, x == null ? null : Timestamp.from(x));

        return (This) this;
    }

    /**
     * Sets a byte array parameter value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * byte[] imageData = loadImageData();
     * query.setBytes(1, imageData);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The byte array to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setBytes(final int parameterIndex, final byte[] x) throws SQLException {
        stmt.setBytes(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets an ASCII stream parameter value.
     * The JDBC driver will read the data from the stream as needed until end-of-file is reached.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * FileInputStream asciiStream = new FileInputStream("data.txt");
     * query.setAsciiStream(1, asciiStream);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param inputStream The input stream containing ASCII data, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setAsciiStream(final int parameterIndex, final InputStream inputStream) throws SQLException {
        stmt.setAsciiStream(parameterIndex, inputStream);

        return (This) this;
    }

    /**
     * Sets an ASCII stream parameter value with a specified length.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * FileInputStream asciiStream = new FileInputStream("data.txt");
     * query.setAsciiStream(1, asciiStream, 1024);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param inputStream The input stream containing ASCII data
     * @param length The number of bytes in the stream
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setAsciiStream(final int parameterIndex, final InputStream inputStream, final int length) throws SQLException {
        stmt.setAsciiStream(parameterIndex, inputStream, length);

        return (This) this;
    }

    /**
     * Sets an ASCII stream parameter value with a specified length.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * FileInputStream asciiStream = new FileInputStream("large_file.txt");
     * query.setAsciiStream(1, asciiStream, file.length());
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param inputStream The input stream containing ASCII data
     * @param length The number of bytes in the stream
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setAsciiStream(final int parameterIndex, final InputStream inputStream, final long length) throws SQLException {
        stmt.setAsciiStream(parameterIndex, inputStream, length);

        return (This) this;
    }

    /**
     * Sets a binary stream parameter value.
     * The JDBC driver will read the data from the stream as needed until end-of-file is reached.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * FileInputStream binaryStream = new FileInputStream("image.jpg");
     * query.setBinaryStream(1, binaryStream);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param inputStream The input stream containing binary data, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setBinaryStream(final int parameterIndex, final InputStream inputStream) throws SQLException {
        stmt.setBinaryStream(parameterIndex, inputStream);

        return (This) this;
    }

    /**
     * Sets a binary stream parameter value with a specified length.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * FileInputStream binaryStream = new FileInputStream("document.pdf");
     * query.setBinaryStream(1, binaryStream, 2048);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param inputStream The input stream containing binary data
     * @param length The number of bytes in the stream
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setBinaryStream(final int parameterIndex, final InputStream inputStream, final int length) throws SQLException {
        stmt.setBinaryStream(parameterIndex, inputStream, length);

        return (This) this;
    }

    /**
     * Sets a binary stream parameter value with a specified length.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * FileInputStream binaryStream = new FileInputStream("large_file.bin");
     * query.setBinaryStream(1, binaryStream, file.length());
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param inputStream The input stream containing binary data
     * @param length The number of bytes in the stream
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setBinaryStream(final int parameterIndex, final InputStream inputStream, final long length) throws SQLException {
        stmt.setBinaryStream(parameterIndex, inputStream, length);

        return (This) this;
    }

    /**
     * Sets a character stream parameter value.
     * The JDBC driver will read the data from the reader as needed until end-of-file is reached.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * FileReader reader = new FileReader("text.txt");
     * query.setCharacterStream(1, reader);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param reader The reader containing character data, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setCharacterStream(final int parameterIndex, final Reader reader) throws SQLException {
        stmt.setCharacterStream(parameterIndex, reader);

        return (This) this;
    }

    /**
     * Sets a character stream parameter value with a specified length.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * StringReader reader = new StringReader("Hello World");
     * query.setCharacterStream(1, reader, 11);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param reader The reader containing character data
     * @param length The number of characters in the stream
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setCharacterStream(final int parameterIndex, final Reader reader, final int length) throws SQLException {
        stmt.setCharacterStream(parameterIndex, reader, length);

        return (This) this;
    }

    /**
     * Sets a character stream parameter value with a specified length.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * FileReader reader = new FileReader("large_text.txt");
     * query.setCharacterStream(1, reader, file.length());
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param reader The reader containing character data
     * @param length The number of characters in the stream
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setCharacterStream(final int parameterIndex, final Reader reader, final long length) throws SQLException {
        stmt.setCharacterStream(parameterIndex, reader, length);

        return (This) this;
    }

    /**
     * Sets a national character stream parameter value.
     * Used for NCHAR, NVARCHAR and LONGNVARCHAR columns.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * StringReader reader = new StringReader("Unicode テキスト");
     * query.setNCharacterStream(1, reader);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param reader The reader containing national character data
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setNCharacterStream(final int parameterIndex, final Reader reader) throws SQLException {
        stmt.setNCharacterStream(parameterIndex, reader);

        return (This) this;
    }

    /**
     * Sets a national character stream parameter value with a specified length.
     * Used for NCHAR, NVARCHAR and LONGNVARCHAR columns.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * StringReader reader = new StringReader("Unicode 文字列");
     * query.setNCharacterStream(1, reader, text.length());
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param reader The reader containing national character data
     * @param length The number of characters in the stream
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setNCharacterStream(final int parameterIndex, final Reader reader, final long length) throws SQLException {
        stmt.setNCharacterStream(parameterIndex, reader, length);

        return (This) this;
    }

    /**
     * Sets a Blob parameter value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Blob blob = connection.createBlob();
     * blob.setBytes(1, imageData);
     * query.setBlob(1, blob);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Blob object, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setBlob(final int parameterIndex, final java.sql.Blob x) throws SQLException {
        stmt.setBlob(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets a Blob parameter value from an InputStream.
     * The data will be read from the stream as needed.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * FileInputStream imageStream = new FileInputStream("photo.jpg");
     * query.setBlob(1, imageStream);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param inputStream The input stream containing the data for the Blob
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setBlob(final int parameterIndex, final InputStream inputStream) throws SQLException {
        stmt.setBlob(parameterIndex, inputStream);

        return (This) this;
    }

    /**
     * Sets a Blob parameter value from an InputStream with a specified length.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * FileInputStream videoStream = new FileInputStream("video.mp4");
     * query.setBlob(1, videoStream, file.length());
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param inputStream The input stream containing the data for the Blob
     * @param length The number of bytes in the stream
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setBlob(final int parameterIndex, final InputStream inputStream, final long length) throws SQLException {
        stmt.setBlob(parameterIndex, inputStream, length);

        return (This) this;
    }

    /**
     * Sets a Clob parameter value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Clob clob = connection.createClob();
     * clob.setString(1, largeText);
     * query.setClob(1, clob);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Clob object, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setClob(final int parameterIndex, final java.sql.Clob x) throws SQLException {
        stmt.setClob(parameterIndex, x);
        return (This) this;
    }

    /**
     * Sets a Clob parameter value from a Reader.
     * The data will be read from the reader as needed.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * FileReader textReader = new FileReader("document.txt");
     * query.setClob(1, textReader);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param reader The reader containing the data for the Clob
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setClob(final int parameterIndex, final Reader reader) throws SQLException {
        stmt.setClob(parameterIndex, reader);
        return (This) this;
    }

    /**
     * Sets a Clob parameter value from a Reader with a specified length.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * StringReader textReader = new StringReader(largeText);
     * query.setClob(1, textReader, largeText.length());
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param reader The reader containing the data for the Clob
     * @param length The number of characters in the stream
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setClob(final int parameterIndex, final Reader reader, final long length) throws SQLException {
        stmt.setClob(parameterIndex, reader, length);

        return (This) this;
    }

    /**
     * Sets an NClob parameter value.
     * Used for storing large amounts of national character data.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * NClob nclob = connection.createNClob();
     * nclob.setString(1, unicodeText);
     * query.setNClob(1, nclob);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The NClob object, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setNClob(final int parameterIndex, final java.sql.NClob x) throws SQLException {
        stmt.setNClob(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets an NClob parameter value from a Reader.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * StringReader unicodeReader = new StringReader("大きなテキスト");
     * query.setNClob(1, unicodeReader);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param reader The reader containing the national character data
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setNClob(final int parameterIndex, final Reader reader) throws SQLException {
        stmt.setNClob(parameterIndex, reader);

        return (This) this;
    }

    /**
     * Sets an NClob parameter value from a Reader with a specified length.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * StringReader unicodeReader = new StringReader(largeUnicodeText);
     * query.setNClob(1, unicodeReader, largeUnicodeText.length());
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param reader The reader containing the national character data
     * @param length The number of characters in the stream
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setNClob(final int parameterIndex, final Reader reader, final long length) throws SQLException {
        stmt.setNClob(parameterIndex, reader, length);

        return (This) this;
    }

    /**
     * Sets a URL parameter value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * URL website = new URL("https://example.com");
     * query.setURL(1, website);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The URL object, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setURL(final int parameterIndex, final URL x) throws SQLException {
        stmt.setURL(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets an Array parameter value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Array array = connection.createArrayOf("VARCHAR", new String[] {"A", "B", "C"});
     * query.setArray(1, array);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Array object, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setArray(final int parameterIndex, final java.sql.Array x) throws SQLException {
        stmt.setArray(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets a SQLXML parameter value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SQLXML xml = connection.createSQLXML();
     * xml.setString("<data>value</data>");
     * query.setSQLXML(1, xml);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The SQLXML object, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setSQLXML(final int parameterIndex, final java.sql.SQLXML x) throws SQLException {
        stmt.setSQLXML(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets a Ref parameter value.
     * A Ref is a reference to an SQL structured type value in the database.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Ref ref = resultSet.getRef("ref_column");
     * query.setRef(1, ref);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The Ref object, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setRef(final int parameterIndex, final java.sql.Ref x) throws SQLException {
        stmt.setRef(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets a RowId parameter value.
     * A RowId is a unique identifier for a row in a database table.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * RowId rowId = resultSet.getRowId("ROWID");
     * query.setRowId(1, rowId);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The RowId object, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setRowId(final int parameterIndex, final java.sql.RowId x) throws SQLException {
        stmt.setRowId(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets an Object parameter value.
     * The JDBC driver will attempt to map the object to an appropriate SQL type.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setObject(1, user)
     *      .setObject(2, LocalDate.now())
     *      .setObject(3, BigDecimal.valueOf(123.45));
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The object to set, or {@code null} to set SQL NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setObject(final int parameterIndex, final Object x) throws SQLException {
        if (x == null) {
            stmt.setObject(parameterIndex, x);
        } else {
            N.typeOf(x.getClass()).set(stmt, parameterIndex, x);
        }

        return (This) this;
    }

    /**
     * Sets an Object parameter value with a specified SQL type.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setObject(1, "123", Types.INTEGER);  // converts string to integer
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The object to set
     * @param sqlType The SQL type to use (from {@link java.sql.Types})
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     * @see java.sql.Types
     */
    public This setObject(final int parameterIndex, final Object x, final int sqlType) throws SQLException {
        stmt.setObject(parameterIndex, x, sqlType);

        return (This) this;
    }

    /**
     * Sets an Object parameter value with a specified SQL type and scale or length.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setObject(1, 123.456, Types.DECIMAL, 2);  // 2 decimal places
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The object to set
     * @param sqlType The SQL type to use (from {@link java.sql.Types})
     * @param scaleOrLength For numeric types, the number of decimal places; for strings, the length
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     * @see java.sql.Types
     */
    public This setObject(final int parameterIndex, final Object x, final int sqlType, final int scaleOrLength) throws SQLException {
        stmt.setObject(parameterIndex, x, sqlType, scaleOrLength);

        return (This) this;
    }

    /**
     * Sets an Object parameter value with a specified SQL type.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SQLType jsonType = JDBCType.OTHER;
     * query.setObject(1, jsonString, jsonType);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The object to set
     * @param sqlType The SQL type to use
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setObject(final int parameterIndex, final Object x, final SQLType sqlType) throws SQLException {
        stmt.setObject(parameterIndex, x, sqlType);

        return (This) this;
    }

    /**
     * Sets an Object parameter value with a specified SQL type and scale or length.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setObject(1, 123.456, JDBCType.DECIMAL, 2);
     * }</pre>
     *
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The object to set
     * @param sqlType The SQL type to use
     * @param scaleOrLength For numeric types, the number of decimal places; for strings, the length
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setObject(final int parameterIndex, final Object x, final SQLType sqlType, final int scaleOrLength) throws SQLException {
        stmt.setObject(parameterIndex, x, sqlType, scaleOrLength);

        return (This) this;
    }

    /**
     * Sets an Object parameter value using a custom Type handler.
     * This allows for custom serialization/deserialization logic.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Type<MyCustomType> customType = TypeFactory.getType(MyCustomType.class);
     * query.setObject(1, myCustomObject, customType);
     * }</pre>
     *
     * @param <T> the type of the object being set as a parameter
     * @param parameterIndex The index of the parameter to set, starting from 1
     * @param x The object to set
     * @param type The Type handler for custom serialization
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException if type is invalid
     * @throws SQLException if a database access error occurs
     */
    public <T> This setObject(final int parameterIndex, final T x, final Type<T> type) throws IllegalArgumentException, SQLException {
        type.set(stmt, parameterIndex, x);

        return (This) this;
    }

    //    public This setParameter(final Jdbc.ParametersSetter<? super Stmt> paramSetter) throws SQLException {
    //        checkArgNotNull(paramSetter, "paramsSetter");
    //
    //        boolean noException = false;
    //
    //        try {
    //            paramSetter.accept(stmt);
    //
    //            noException = true;
    //        } finally {
    //            if (!noException) {
    //                close();
    //            }
    //        }
    //
    //        return (This) this;
    //    }
    //
    //    public <T> This setParameter(final T parameter, final Jdbc.BiParametersSetter<? super Stmt, ? super T> paramSetter) throws SQLException {
    //        checkArgNotNull(paramSetter, "paramsSetter");
    //
    //        boolean noException = false;
    //
    //        try {
    //            paramSetter.accept(stmt, parameter);
    //
    //            noException = true;
    //        } finally {
    //            if (!noException) {
    //                close();
    //            }
    //        }
    //
    //        return (This) this;
    //    }

    /**
     * Sets two String parameters starting from index 1.
     * Convenience method for queries with exactly two String parameters.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setParameters("John", "Doe")
     *      .list();  // SELECT * FROM users WHERE first_name = ? AND last_name = ?
     * }</pre>
     *
     * @param param1 The first String parameter
     * @param param2 The second String parameter
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setParameters(final String param1, final String param2) throws SQLException {
        stmt.setString(1, param1);
        stmt.setString(2, param2);

        return (This) this;
    }

    /**
     * Sets three String parameters starting from index 1.
     * Convenience method for queries with exactly three String parameters.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setParameters("John", "Doe", "john@example.com")
     *      .update();  // UPDATE users SET first_name = ?, last_name = ? WHERE email = ?
     * }</pre>
     *
     * @param param1 The first String parameter
     * @param param2 The second String parameter
     * @param param3 The third String parameter
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setParameters(final String param1, final String param2, final String param3) throws SQLException {
        stmt.setString(1, param1);
        stmt.setString(2, param2);
        stmt.setString(3, param3);

        return (This) this;
    }

    /**
     * Sets four String parameters starting from index 1.
     * Convenience method for queries with exactly four String parameters.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setParameters("John", "Doe", "john@example.com", "USA")
     *      .insert();
     * }</pre>
     *
     * @param param1 The first String parameter
     * @param param2 The second String parameter
     * @param param3 The third String parameter
     * @param param4 The fourth String parameter
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setParameters(final String param1, final String param2, final String param3, final String param4) throws SQLException {
        stmt.setString(1, param1);
        stmt.setString(2, param2);
        stmt.setString(3, param3);
        stmt.setString(4, param4);

        return (This) this;
    }

    /**
     * Sets five String parameters starting from index 1.
     * Convenience method for queries with exactly five String parameters.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setParameters("John", "Doe", "john@example.com", "123-456-7890", "New York")
     *      .execute();
     * }</pre>
     *
     * @param param1 The first String parameter
     * @param param2 The second String parameter
     * @param param3 The third String parameter
     * @param param4 The fourth String parameter
     * @param param5 The fifth String parameter
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setParameters(final String param1, final String param2, final String param3, final String param4, final String param5) throws SQLException {
        stmt.setString(1, param1);
        stmt.setString(2, param2);
        stmt.setString(3, param3);
        stmt.setString(4, param4);
        stmt.setString(5, param5);

        return (This) this;
    }

    /**
     * Sets six String parameters starting from index 1.
     * Convenience method for queries with exactly six String parameters.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setParameters("John", "Doe", "john@example.com", "123-456-7890", "New York", "USA")
     *      .execute();
     * }</pre>
     *
     * @param param1 The first String parameter
     * @param param2 The second String parameter
     * @param param3 The third String parameter
     * @param param4 The fourth String parameter
     * @param param5 The fifth String parameter
     * @param param6 The sixth String parameter
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setParameters(final String param1, final String param2, final String param3, final String param4, final String param5, final String param6)
            throws SQLException {
        stmt.setString(1, param1);
        stmt.setString(2, param2);
        stmt.setString(3, param3);
        stmt.setString(4, param4);
        stmt.setString(5, param5);
        stmt.setString(6, param6);

        return (This) this;
    }

    /**
     * Sets seven String parameters starting from index 1.
     * Convenience method for queries with exactly seven String parameters.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setParameters("John", "Doe", "john@example.com", "123-456-7890", "New York", "USA", "10001")
     *      .execute();
     * }</pre>
     *
     * @param param1 The first String parameter
     * @param param2 The second String parameter
     * @param param3 The third String parameter
     * @param param4 The fourth String parameter
     * @param param5 The fifth String parameter
     * @param param6 The sixth String parameter
     * @param param7 The seventh String parameter
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setParameters(final String param1, final String param2, final String param3, final String param4, final String param5, final String param6,
            final String param7) throws SQLException {
        stmt.setString(1, param1);
        stmt.setString(2, param2);
        stmt.setString(3, param3);
        stmt.setString(4, param4);
        stmt.setString(5, param5);
        stmt.setString(6, param6);
        stmt.setString(7, param7);

        return (This) this;
    }

    //    /**
    //     * Sets the parameters.
    //     *
    //     * @param param1
    //     * @param param2
    //     * @return
    //     * @throws SQLException
    //     * @deprecated to void error: java ambiguous method call with other {@code setParameters} methods
    //     */
    //    @Deprecated
    //    public This setParameters(final Object param1, final Object param2) throws SQLException {
    //        setObject(1, param1);
    //        setObject(2, param2);
    //
    //        return (This) this;
    //    }

    /**
     * Sets three Object parameters starting from index 1.
     * Convenience method for queries with exactly three parameters of any type.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setParameters("John", 25, LocalDate.now())
     *      .list();
     * }</pre>
     *
     * @param param1 The first parameter
     * @param param2 The second parameter
     * @param param3 The third parameter
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setParameters(final Object param1, final Object param2, final Object param3) throws SQLException {
        setObject(1, param1);
        setObject(2, param2);
        setObject(3, param3);

        return (This) this;
    }

    /**
     * Sets four Object parameters starting from index 1.
     * Convenience method for queries with exactly four parameters of any type.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setParameters("John Doe", 25, LocalDate.now(), true)
     *      .list(User.class);
     * }</pre>
     *
     * @param param1 The first parameter
     * @param param2 The second parameter
     * @param param3 The third parameter
     * @param param4 The fourth parameter
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setParameters(final Object param1, final Object param2, final Object param3, final Object param4) throws SQLException {
        setObject(1, param1);
        setObject(2, param2);
        setObject(3, param3);
        setObject(4, param4);

        return (This) this;
    }

    /**
     * Sets five Object parameters starting from index 1.
     * Convenience method for queries with exactly five parameters of any type.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setParameters("John Doe", 25, LocalDate.now(), true, new BigDecimal("50000.00"))
     *      .list(Employee.class);
     * }</pre>
     *
     * @param param1 The first parameter
     * @param param2 The second parameter
     * @param param3 The third parameter
     * @param param4 The fourth parameter
     * @param param5 The fifth parameter
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setParameters(final Object param1, final Object param2, final Object param3, final Object param4, final Object param5) throws SQLException {
        setObject(1, param1);
        setObject(2, param2);
        setObject(3, param3);
        setObject(4, param4);
        setObject(5, param5);

        return (This) this;
    }

    /**
     * Sets six Object parameters starting from index 1.
     * Convenience method for queries with exactly six parameters of any type.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setParameters("John Doe", 25, LocalDate.now(), true, new BigDecimal("50000.00"), "IT")
     *      .list(Employee.class);
     * }</pre>
     *
     * @param param1 The first parameter
     * @param param2 The second parameter
     * @param param3 The third parameter
     * @param param4 The fourth parameter
     * @param param5 The fifth parameter
     * @param param6 The sixth parameter
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setParameters(final Object param1, final Object param2, final Object param3, final Object param4, final Object param5, final Object param6)
            throws SQLException {
        setObject(1, param1);
        setObject(2, param2);
        setObject(3, param3);
        setObject(4, param4);
        setObject(5, param5);
        setObject(6, param6);

        return (This) this;
    }

    /**
     * Sets seven Object parameters starting from index 1.
     * Convenience method for queries with exactly seven parameters of any type.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setParameters("John", "Doe", 25, LocalDate.now(), true, new BigDecimal("50000.00"), "IT")
     *      .list(Employee.class);
     * }</pre>
     *
     * @param param1 The first parameter
     * @param param2 The second parameter
     * @param param3 The third parameter
     * @param param4 The fourth parameter
     * @param param5 The fifth parameter
     * @param param6 The sixth parameter
     * @param param7 The seventh parameter
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setParameters(final Object param1, final Object param2, final Object param3, final Object param4, final Object param5, final Object param6,
            final Object param7) throws SQLException {
        setObject(1, param1);
        setObject(2, param2);
        setObject(3, param3);
        setObject(4, param4);
        setObject(5, param5);
        setObject(6, param6);
        setObject(7, param7);

        return (This) this;
    }

    /**
     * Sets eight Object parameters starting from index 1.
     * Convenience method for queries with exactly eight parameters of any type.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setParameters("John", "Doe", 25, LocalDate.now(), true, new BigDecimal("50000.00"), "IT", "Manager")
     *      .list(Employee.class);
     * }</pre>
     *
     * @param param1 The first parameter
     * @param param2 The second parameter
     * @param param3 The third parameter
     * @param param4 The fourth parameter
     * @param param5 The fifth parameter
     * @param param6 The sixth parameter
     * @param param7 The seventh parameter
     * @param param8 The eighth parameter
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    public This setParameters(final Object param1, final Object param2, final Object param3, final Object param4, final Object param5, final Object param6,
            final Object param7, final Object param8) throws SQLException {
        setObject(1, param1);
        setObject(2, param2);
        setObject(3, param3);
        setObject(4, param4);
        setObject(5, param5);
        setObject(6, param6);
        setObject(7, param7);
        setObject(8, param8);

        return (This) this;
    }

    /**
     * Sets nine Object parameters starting from index 1.
     * Convenience method for queries with exactly nine parameters of any type.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setParameters("John", "Doe", 25, LocalDate.now(), true, new BigDecimal("50000.00"), "IT", "Manager", LocalDateTime.now())
     *      .list(Employee.class);
     * }</pre>
     *
     * @param param1 The first parameter
     * @param param2 The second parameter
     * @param param3 The third parameter
     * @param param4 The fourth parameter
     * @param param5 The fifth parameter
     * @param param6 The sixth parameter
     * @param param7 The seventh parameter
     * @param param8 The eighth parameter
     * @param param9 The ninth parameter
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException if any parameter is invalid
     * @throws SQLException if a database access error occurs
     */
    public This setParameters(final Object param1, final Object param2, final Object param3, final Object param4, final Object param5, final Object param6,
            final Object param7, final Object param8, final Object param9) throws IllegalArgumentException, SQLException {
        setObject(1, param1);
        setObject(2, param2);
        setObject(3, param3);
        setObject(4, param4);
        setObject(5, param5);
        setObject(6, param6);
        setObject(7, param7);
        setObject(8, param8);
        setObject(9, param9);

        return (This) this;
    }

    //    public <T> This setParameters(final T parameters, final Throwables.TriConsumer<? super This, ? super Stmt, ? super T, ? extends SQLException> paramsSetter)
    //            throws SQLException {
    //        checkArgNotNull(paramsSetter, "paramsSetter");
    //
    //        boolean noException = false;
    //
    //        try {
    //            paramsSetter.accept((This) this, stmt, parameters);
    //
    //            noException = true;
    //        } finally {
    //            if (noException == false) {
    //                close();
    //            }
    //        }
    //
    //        return (This) this;
    //    }

    /**
     * Sets multiple int parameters starting from index 1.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setParameters(new int[] {10, 20, 30})
     *      .list();  // SELECT * FROM table WHERE col1 = ? AND col2 = ? AND col3 = ?
     * }</pre>
     *
     * @param parameters The array of int values to set
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException if parameters is null
     * @throws SQLException if a database access error occurs
     */
    public This setParameters(final int[] parameters) throws IllegalArgumentException, SQLException {
        return settParameters(1, parameters);
    }

    /**
     * Sets multiple long parameters starting from index 1.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setParameters(new long[] {1000L, 2000L, 3000L})
     *      .list();
     * }</pre>
     *
     * @param parameters The array of long values to set
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException if parameters is null
     * @throws SQLException if a database access error occurs
     */
    public This setParameters(final long[] parameters) throws IllegalArgumentException, SQLException {
        return settParameters(1, parameters);
    }

    /**
     * Sets multiple String parameters starting from index 1.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setParameters(new String[] {"John", "Doe", "USA"})
     *      .insert();
     * }</pre>
     *
     * @param parameters The array of String values to set
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException if parameters is null
     * @throws SQLException if a database access error occurs
     */
    public This setParameters(final String[] parameters) throws IllegalArgumentException, SQLException {
        return settParameters(1, parameters);
    }

    /**
     * Sets multiple parameters of the same type starting from index 1.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * LocalDate[] dates = {LocalDate.now(), LocalDate.now().plusDays(7)};
     * query.setParameters(dates)
     *      .list();
     * }</pre>
     *
     * @param <T> the type of elements in the parameters array
     * @param parameters The array of values to set
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException if parameters is null
     * @throws SQLException if a database access error occurs
     */
    public <T> This setParameters(final T[] parameters) throws IllegalArgumentException, SQLException {
        return settParameters(1, parameters);
    }

    /**
     * Sets multiple parameters from a Collection starting from index 1.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<String> names = Arrays.asList("Alice", "Bob", "Charlie");
     * query.setParameters(names)
     *      .list();  // SELECT * FROM users WHERE name IN (?, ?, ?)
     * }</pre>
     *
     * @param parameters The collection of values to set
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException if parameters is null
     * @throws SQLException if a database access error occurs
     */
    public This setParameters(final Collection<?> parameters) throws IllegalArgumentException, SQLException {
        return settParameters(1, parameters);
    }

    /**
     * Sets multiple parameters from a typed Collection starting from index 1.
     * This method ensures type safety when setting parameters.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<LocalDate> dates = getDates();
     * query.setParameters(dates, LocalDate.class)
     *      .list();
     * }</pre>
     *
     * @param <T> the type of elements in the parameters collection
     * @param parameters The collection of values to set
     * @param type The class type of the parameters
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException if parameters or type is null
     * @throws SQLException if a database access error occurs
     */
    public <T> This setParameters(final Collection<? extends T> parameters, final Class<T> type) throws IllegalArgumentException, SQLException {
        return settParameters(1, parameters, type);
    }

    /**
     * Sets parameters using a custom ParametersSetter.
     * This allows for complex parameter setting logic.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setParameters(stmt -> {
     *     stmt.setString(1, user.getName());
     *     stmt.setInt(2, user.getAge());
     *     if (user.getEmail() != null) {
     *         stmt.setString(3, user.getEmail());
     *     } else {
     *         stmt.setNull(3, Types.VARCHAR);
     *     }
     * }).list();
     * }</pre>
     *
     * @param paramsSetter The function to set parameters on the PreparedStatement
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException if paramsSetter is null
     * @throws SQLException if a database access error occurs
     */
    public This setParameters(final Jdbc.ParametersSetter<? super Stmt> paramsSetter) throws IllegalArgumentException, SQLException {
        checkArgNotNull(paramsSetter, cs.paramsSetter);

        boolean noException = false;

        try {
            paramsSetter.accept(stmt);

            noException = true;
        } finally {
            if (!noException) {
                close();
            }
        }

        return (This) this;
    }

    //    /**
    //     *
    //     * @param paramsSetter
    //     * @return
    //     * @throws SQLException
    //     */
    //    public This setParameters(final BiParametersSetter<? super This, ? super Stmt> paramsSetter) throws SQLException {
    //        checkArgNotNull(paramsSetter, "paramsSetter");
    //
    //        boolean noException = false;
    //
    //        try {
    //            paramsSetter.accept((This) this, stmt);
    //
    //            noException = true;
    //        } finally {
    //            if (noException == false) {
    //                close();
    //            }
    //        }
    //
    //        return (This) this;
    //    }

    /**
     * Sets parameters using a BiParametersSetter with additional context.
     * This allows passing additional data along with the statement for parameter setting.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = getUser();
     * query.setParameters(user, (stmt, u) -> {
     *     stmt.setString(1, u.getName());
     *     stmt.setInt(2, u.getAge());
     *     stmt.setString(3, u.getEmail());
     * }).update();
     * }</pre>
     *
     * @param <T> the type of the additional parameter object
     * @param parameters The additional data to use when setting parameters
     * @param paramsSetter The function to set parameters on the PreparedStatement
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException if paramsSetter is null
     * @throws SQLException if a database access error occurs
     */
    public <T> This setParameters(final T parameters, final Jdbc.BiParametersSetter<? super Stmt, ? super T> paramsSetter)
            throws IllegalArgumentException, SQLException {
        checkArgNotNull(paramsSetter, cs.paramsSetter);

        boolean noException = false;

        try {
            paramsSetter.accept(stmt, parameters);

            noException = true;
        } finally {
            if (!noException) {
                close();
            }
        }

        return (This) this;
    }

    /**
     * Sets multiple int parameters starting from the specified index.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setString(1, "Active")
     *      .settParameters(2, new int[] {10, 20, 30})
     *      .list();  // WHERE status = ? AND id IN (?, ?, ?)
     * }</pre>
     *
     * @param startParameterIndex The starting parameter index (1-based)
     * @param parameters The array of int values to set
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException if parameters is null
     * @throws SQLException if a database access error occurs
     */
    public This settParameters(int startParameterIndex, final int[] parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, cs.parameters);

        for (final int param : parameters) {
            stmt.setInt(startParameterIndex++, param);
        }

        return (This) this;
    }

    /**
     * Sets multiple long parameters starting from the specified index.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setString(1, "Premium")
     *      .settParameters(2, new long[] {1000L, 2000L, 3000L})
     *      .list();
     * }</pre>
     *
     * @param startParameterIndex The starting parameter index (1-based)
     * @param parameters The array of long values to set
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException if parameters is null
     * @throws SQLException if a database access error occurs
     */
    public This settParameters(int startParameterIndex, final long[] parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, cs.parameters);

        for (final long param : parameters) {
            stmt.setLong(startParameterIndex++, param);
        }

        return (This) this;
    }

    /**
     * Sets multiple String parameters starting from the specified index.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setInt(1, 2023)
     *      .settParameters(2, new String[] {"Q1", "Q2", "Q3", "Q4"})
     *      .list();
     * }</pre>
     *
     * @param startParameterIndex The starting parameter index (1-based)
     * @param parameters The array of String values to set
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException if parameters is null
     * @throws SQLException if a database access error occurs
     */
    public This settParameters(int startParameterIndex, final String[] parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, cs.parameters);

        for (final String param : parameters) {
            stmt.setString(startParameterIndex++, param);
        }

        return (This) this;
    }

    /**
     * Sets multiple parameters of the same type starting from the specified index.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BigDecimal[] amounts = {new BigDecimal("100.50"), new BigDecimal("200.75")};
     * query.setString(1, "USD")
     *      .settParameters(2, amounts)
     *      .list();
     * }</pre>
     *
     * @param <T> the type of elements in the parameters array
     * @param startParameterIndex The starting parameter index (1-based)
     * @param parameters The array of values to set
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException if parameters is null
     * @throws SQLException if a database access error occurs
     */
    public <T> This settParameters(int startParameterIndex, final T[] parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, cs.parameters);

        final Class<?> componentType = parameters.getClass().getComponentType();

        if (stmtParameterClasses.contains(componentType)) {
            final Type<T> eleType = N.typeOf(componentType);

            for (final T param : parameters) {
                eleType.set(stmt, startParameterIndex++, param);
            }
        } else {
            for (final Object param : parameters) {
                setObject(startParameterIndex++, param);
            }
        }

        return (This) this;
    }

    /**
     * Sets multiple parameters from a Collection starting from the specified index.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<String> categories = Arrays.asList("Electronics", "Books", "Clothing");
     * query.setDate(1, startDate)
     *      .settParameters(2, categories)
     *      .list();
     * }</pre>
     *
     * @param startParameterIndex The starting parameter index (1-based)
     * @param parameters The collection of values to set
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException if parameters is null
     * @throws SQLException if a database access error occurs
     */
    public This settParameters(int startParameterIndex, final Collection<?> parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, cs.parameters);

        for (final Object param : parameters) {
            setObject(startParameterIndex++, param);
        }

        return (This) this;
    }

    /**
     * Sets multiple parameters from a typed Collection starting from the specified index.
     * This method ensures type safety when setting parameters.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Set<UUID> userIds = getUserIds();
     * query.setString(1, "Active")
     *      .settParameters(2, userIds, UUID.class)
     *      .list();
     * }</pre>
     *
     * @param <T> the type of elements in the parameters collection
     * @param startParameterIndex The starting parameter index (1-based)
     * @param parameters The collection of values to set
     * @param type The class type of the parameters
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException if parameters or type is null
     * @throws SQLException if a database access error occurs
     */
    public <T> This settParameters(int startParameterIndex, final Collection<? extends T> parameters, final Class<T> type)
            throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, cs.parameters);
        checkArgNotNull(type, cs.type);

        final Type<T> eleType = N.typeOf(type);

        for (final T param : parameters) {
            eleType.set(stmt, startParameterIndex++, param);
        }

        return (This) this;
    }

    //    /**
    //     *
    //     * @param paramsSetter
    //     * @return
    //     * @throws SQLException
    //     */
    //    public This setParameters(final BiParametersSetter<? super This, ? super Stmt> paramsSetter) throws SQLException {
    //        checkArgNotNull(paramsSetter, "paramsSetter");
    //
    //        boolean noException = false;
    //
    //        try {
    //            paramsSetter.accept((This) this, stmt);
    //
    //            noException = true;
    //        } finally {
    //            if (noException == false) {
    //                close();
    //            }
    //        }
    //
    //        return (This) this;
    //    }

    //    public <T> This setParameters(final T parameters, final Throwables.TriConsumer<? super This, ? super Stmt, ? super T, ? extends SQLException> paramsSetter)
    //            throws SQLException {
    //        checkArgNotNull(paramsSetter, "paramsSetter");
    //
    //        boolean noException = false;
    //
    //        try {
    //            paramsSetter.accept((This) this, stmt, parameters);
    //
    //            noException = true;
    //        } finally {
    //            if (noException == false) {
    //                close();
    //            }
    //        }
    //
    //        return (This) this;
    //    }

    /**
     * Sets parameters using a custom ParametersSetter that operates on this query instance.
     * This provides access to all the parameter setting methods of this class.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.settParameters(q -> {
     *     q.setString(1, "John")
     *      .setInt(2, 25)
     *      .setDate(3, LocalDate.now());
     * }).list();
     * }</pre>
     *
     * @param paramsSetter The function to set parameters on this query
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException if paramsSetter is null
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public This settParameters(final Jdbc.ParametersSetter<? super This> paramsSetter) throws IllegalArgumentException, SQLException {
        checkArgNotNull(paramsSetter, cs.paramsSetter);

        boolean noException = false;

        try {
            paramsSetter.accept((This) this);

            noException = true;
        } finally {
            if (!noException) {
                close();
            }
        }

        return (This) this;
    }

    /**
     * Sets parameters using a BiParametersSetter that operates on this query instance with additional context.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * UserFilter filter = getUserFilter();
     * query.settParameters(filter, (q, f) -> {
     *     q.setString(1, f.getName());
     *     if (f.getMinAge() != null) {
     *         q.setInt(2, f.getMinAge());
     *     } else {
     *         q.setNull(2, Types.INTEGER);
     *     }
     * }).list();
     * }</pre>
     *
     * @param <T> the type of the additional parameter object
     * @param parameters The additional data to use when setting parameters
     * @param paramsSetter The function to set parameters on this query
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException if paramsSetter is null
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public <T> This settParameters(final T parameters, final Jdbc.BiParametersSetter<? super This, ? super T> paramsSetter)
            throws IllegalArgumentException, SQLException {
        checkArgNotNull(paramsSetter, cs.paramsSetter);

        boolean noException = false;

        try {
            paramsSetter.accept((This) this, parameters);

            noException = true;
        } finally {
            if (!noException) {
                close();
            }
        }

        return (This) this;
    }

    /**
     * Sets the same SQL NULL value to multiple parameter positions.
     * Useful when multiple parameters should be NULL with the same SQL type.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Set positions 2, 4, and 6 to NULL (VARCHAR)
     * query.setString(1, "Active")
     *      .setNullForMultiPositions(Types.VARCHAR, 2, 4, 6)
     *      .setInt(3, 100)
     *      .setDate(5, LocalDate.now());
     * }</pre>
     *
     * @param sqlType The SQL type to set, as defined in {@link java.sql.Types}
     * @param parameterIndices The parameter positions to set to NULL
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     * @see java.sql.Types
     */
    @Beta
    public This setNullForMultiPositions(final int sqlType, final int... parameterIndices) throws SQLException {
        checkParameterIndices(parameterIndices);

        for (final int parameterIndex : parameterIndices) {
            setNull(parameterIndex, sqlType);
        }

        return (This) this;
    }

    /**
     * Sets the same Boolean value to multiple parameter positions.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setBooleanForMultiPositions(true, 1, 3, 5, 7);
     * }</pre>
     *
     * @param parameterValue The Boolean value to set
     * @param parameterIndices The parameter positions to set
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public This setBooleanForMultiPositions(final Boolean parameterValue, final int... parameterIndices) throws SQLException {
        checkParameterIndices(parameterIndices);

        for (final int parameterIndex : parameterIndices) {
            setBoolean(parameterIndex, parameterValue);
        }

        return (This) this;
    }

    /**
     * Sets the same Integer value to multiple parameter positions.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Set default status code to multiple positions
     * query.setIntForMultiPositions(0, 2, 5, 8);
     * }</pre>
     *
     * @param parameterValue The Integer value to set
     * @param parameterIndices The parameter positions to set
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public This setIntForMultiPositions(final Integer parameterValue, final int... parameterIndices) throws SQLException {
        checkParameterIndices(parameterIndices);

        for (final int parameterIndex : parameterIndices) {
            setInt(parameterIndex, parameterValue);
        }

        return (This) this;
    }

    //    /**
    //     * Note: The reason for giving name: {@code setIntegerForMultiPositions}, not {@code setIntForMultiPositions} is because of error: <i>The method setIntForMultiPositions(int, int[]) is ambiguous for the type</i>.
    //     *
    //     * @param parameterValue
    //     * @param parameterIndices
    //     * @return
    //     * @throws SQLException
    //     */
    //    @Beta
    //    public This setIntegerForMultiPositions(final Integer parameterValue, final int... parameterIndices) throws SQLException {
    //        checkParameterIndices(parameterIndices);
    //
    //        for (int parameterIndex : parameterIndices) {
    //            setInt(parameterIndex, parameterValue);
    //        }
    //
    //        return (This) this;
    //    }

    // ambiguous
    //    /**
    //     *
    //     *
    //     * @param parameterValue
    //     * @param parameterIndices
    //     * @return
    //     * @throws SQLException
    //     */
    //    @Beta
    //    public This setLongForMultiPositions(final long parameterValue, final int... parameterIndices) throws SQLException {
    //        checkParameterIndices(parameterIndices);
    //
    //        for (int parameterIndex : parameterIndices) {
    //            setLong(parameterIndex, parameterValue);
    //        }
    //
    //        return (This) this;
    //    }

    /**
     * Sets the same Long value to multiple parameter positions.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setLongForMultiPositions(System.currentTimeMillis(), 2, 4, 6);
     * }</pre>
     *
     * @param parameterValue The Long value to set
     * @param parameterIndices The parameter positions to set
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public This setLongForMultiPositions(final Long parameterValue, final int... parameterIndices) throws SQLException {
        checkParameterIndices(parameterIndices);

        for (final int parameterIndex : parameterIndices) {
            setLong(parameterIndex, parameterValue);
        }

        return (This) this;
    }

    /**
     * Sets the same Double value to multiple parameter positions.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setDoubleForMultiPositions(0.0, 1, 3, 5, 7);
     * }</pre>
     *
     * @param parameterValue The Double value to set
     * @param parameterIndices The parameter positions to set
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public This setDoubleForMultiPositions(final Double parameterValue, final int... parameterIndices) throws SQLException {
        checkParameterIndices(parameterIndices);

        for (final int parameterIndex : parameterIndices) {
            setDouble(parameterIndex, parameterValue);
        }

        return (This) this;
    }

    /**
     * Sets the same String value to multiple parameter positions.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setStringForMultiPositions("N/A", 2, 4, 6, 8);
     * }</pre>
     *
     * @param parameterValue The String value to set
     * @param parameterIndices The parameter positions to set
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public This setStringForMultiPositions(final String parameterValue, final int... parameterIndices) throws SQLException {
        checkParameterIndices(parameterIndices);

        for (final int parameterIndex : parameterIndices) {
            setString(parameterIndex, parameterValue);
        }

        return (This) this;
    }

    /**
     * Sets the same Date value to multiple parameter positions.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * java.sql.Date today = new java.sql.Date(System.currentTimeMillis());
     * query.setDateForMultiPositions(today, 1, 3, 5);
     * }</pre>
     *
     * @param parameterValue The Date value to set
     * @param parameterIndices The parameter positions to set
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public This setDateForMultiPositions(final java.sql.Date parameterValue, final int... parameterIndices) throws SQLException {
        checkParameterIndices(parameterIndices);

        for (final int parameterIndex : parameterIndices) {
            setDate(parameterIndex, parameterValue);
        }

        return (This) this;
    }

    /**
     * Sets the same java.util.Date value to multiple parameter positions.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * java.util.Date now = new java.util.Date();
     * query.setDateForMultiPositions(now, 2, 4, 6);
     * }</pre>
     *
     * @param parameterValue The Date value to set
     * @param parameterIndices The parameter positions to set
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public This setDateForMultiPositions(final java.util.Date parameterValue, final int... parameterIndices) throws SQLException {
        checkParameterIndices(parameterIndices);

        for (final int parameterIndex : parameterIndices) {
            setDate(parameterIndex, parameterValue);
        }

        return (This) this;
    }

    /**
     * Sets the same Time value to multiple parameter positions.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * java.sql.Time noon = java.sql.Time.valueOf("12:00:00");
     * query.setTimeForMultiPositions(noon, 1, 3, 5);
     * }</pre>
     *
     * @param parameterValue The Time value to set
     * @param parameterIndices The parameter positions to set
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public This setTimeForMultiPositions(final java.sql.Time parameterValue, final int... parameterIndices) throws SQLException {
        checkParameterIndices(parameterIndices);

        for (final int parameterIndex : parameterIndices) {
            setTime(parameterIndex, parameterValue);
        }

        return (This) this;
    }

    /**
     * Sets the same java.util.Date value as Time to multiple parameter positions.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * java.util.Date time = new java.util.Date();
     * query.setTimeForMultiPositions(time, 2, 4, 6);
     * }</pre>
     *
     * @param parameterValue The Date value to set as Time
     * @param parameterIndices The parameter positions to set
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public This setTimeForMultiPositions(final java.util.Date parameterValue, final int... parameterIndices) throws SQLException {
        checkParameterIndices(parameterIndices);

        for (final int parameterIndex : parameterIndices) {
            setTime(parameterIndex, parameterValue);
        }

        return (This) this;
    }

    /**
     * Sets the same Timestamp value to multiple parameter positions.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Timestamp ts = new Timestamp(System.currentTimeMillis());
     * query.setTimestampForMultiPositions(ts, 1, 3, 5, 7);
     * }</pre>
     *
     * @param parameterValue The Timestamp value to set
     * @param parameterIndices The parameter positions to set
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public This setTimestampForMultiPositions(final java.sql.Timestamp parameterValue, final int... parameterIndices) throws SQLException {
        checkParameterIndices(parameterIndices);

        for (final int parameterIndex : parameterIndices) {
            setTimestamp(parameterIndex, parameterValue);
        }

        return (This) this;
    }

    /**
     * Sets the same java.util.Date value as Timestamp to multiple parameter positions.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * java.util.Date now = new java.util.Date();
     * query.setTimestampForMultiPositions(now, 2, 4, 6, 8);
     * }</pre>
     *
     * @param parameterValue The Date value to set as Timestamp
     * @param parameterIndices The parameter positions to set
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public This setTimestampForMultiPositions(final java.util.Date parameterValue, final int... parameterIndices) throws SQLException {
        checkParameterIndices(parameterIndices);

        for (final int parameterIndex : parameterIndices) {
            setTimestamp(parameterIndex, parameterValue);
        }

        return (This) this;
    }

    /**
     * Sets the same Object value to multiple parameter positions.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * UUID defaultId = UUID.randomUUID();
     * query.setObjectForMultiPositions(defaultId, 1, 3, 5, 7);
     * }</pre>
     *
     * @param parameterValue The Object value to set
     * @param parameterIndices The parameter positions to set
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public This setObjectForMultiPositions(final Object parameterValue, final int... parameterIndices) throws SQLException {
        checkParameterIndices(parameterIndices);

        for (final int parameterIndex : parameterIndices) {
            setObject(parameterIndex, parameterValue);
        }

        return (This) this;
    }

    private void checkParameterIndices(final int... parameterIndices) {
        checkArg(N.notEmpty(parameterIndices), "'parameterIndices' can't be null or empty");

        for (final int parameterIndex : parameterIndices) {
            if (parameterIndex <= 0) {
                checkArg(parameterIndex > 0, "'parameterIndices' must all be positive. It can't be: " + N.toString(parameterIndices));
            }
        }
    }

    //    /**
    //     * @param <T>
    //     * @param batchParameters
    //     * @param parametersSetter
    //     * @return
    //     * @throws SQLException
    //     */
    //    <T> Q setBatchParameters(final Collection<T> batchParameters, BiParametersSetter<? super Q, ? super T> parametersSetter) throws SQLException {
    //        return setBatchParameters(batchParameters.iterator(), parametersSetter);
    //    }
    //
    //    /**
    //     *
    //     * @param <T>
    //     * @param batchParameters
    //     * @param parametersSetter
    //     * @return
    //     * @throws SQLException
    //     */
    //    <T> Q setBatchParameters(final Iterator<T> batchParameters, BiParametersSetter<? super Q, ? super T> parametersSetter) throws SQLException {
    //        checkArgNotNull(batchParameters, "batchParameters");
    //        checkArgNotNull(parametersSetter, "parametersSetter");
    //
    //        boolean noException = false;
    //
    //        try {
    //            if (isBatch) {
    //                stmt.clearBatch();
    //            }
    //
    //            final Iterator<T> iter = batchParameters;
    //
    //            while (iter.hasNext()) {
    //                parametersSetter.accept((Q) this, iter.next());
    //                addBatch();
    //                isBatch = true;
    //            }
    //
    //            noException = true;
    //        } finally {
    //            if (noException == false) {
    //                close();
    //            }
    //        }
    //
    //        return (Q) this;
    //    }

    /**
     * Adds multiple sets of parameters for batch execution.
     * Each element in the collection represents one set of parameters.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<List<Object>> batchData = Arrays.asList(
     *     Arrays.asList("John", 25, "john@email.com"),
     *     Arrays.asList("Jane", 30, "jane@email.com"),
     *     Arrays.asList("Bob", 35, "bob@email.com")
     * );
     * query.addBatchParameters(batchData).batchInsert();
     * }</pre>
     *
     * @param batchParameters Collection where each element is a collection of parameters for one batch
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException if batchParameters is null
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public This addBatchParameters(final Collection<?> batchParameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(batchParameters, cs.batchParameters);

        if (N.isEmpty(batchParameters)) {
            return (This) this;
        }

        return addBatchParameters(batchParameters.iterator());
    }

    /**
     * Adds multiple sets of typed parameters for batch execution.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<String> names = Arrays.asList("Alice", "Bob", "Charlie");
     * query.addBatchParameters(names, String.class).batchUpdate();
     * // Each name becomes a single parameter in a batch
     * }</pre>
     *
     * @param <T> the type of elements in the batch parameters collection
     * @param batchParameters Collection of parameters
     * @param type The class type of the parameters
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException If batchParameters or type is null
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public <T> This addBatchParameters(final Collection<? extends T> batchParameters, final Class<T> type) throws IllegalArgumentException, SQLException {
        checkArgNotNull(batchParameters, cs.batchParameters);
        checkArgNotNull(type, cs.type);

        if (N.isEmpty(batchParameters)) {
            return (This) this;
        }

        return addBatchParameters(batchParameters.iterator(), type);
    }

    /**
     * Adds multiple sets of parameters for batch execution using an iterator.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Iterator<Object[]> dataIterator = getLargeDataset();
     * query.addBatchParameters(dataIterator).batchUpdate();
     * }</pre>
     *
     * @param batchParameters Iterator over parameter sets
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException if batchParameters is null
     * @throws SQLException if a database access error occurs
     */
    @Beta
    @SuppressWarnings("rawtypes")
    public This addBatchParameters(final Iterator<?> batchParameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(batchParameters, cs.batchParameters);

        @SuppressWarnings("UnnecessaryLocalVariable")
        final Iterator<?> iter = batchParameters;
        boolean noException = false;

        try {
            if (!iter.hasNext()) {
                return (This) this;
            }

            final Object first = iter.next();

            if (first instanceof Collection) {
                setParameters((Collection) first);
                addBatch();

                while (iter.hasNext()) {
                    setParameters((Collection) iter.next());

                    addBatch();
                }
            } else if (first instanceof Object[]) {
                setParameters((Object[]) first);
                addBatch();

                while (iter.hasNext()) {
                    setParameters((Object[]) iter.next());

                    addBatch();
                }
            } else {
                stmt.setObject(1, first);
                addBatch();

                while (iter.hasNext()) {
                    stmt.setObject(1, iter.next());

                    addBatch();
                }
            }

            noException = true;
        } finally {
            if (!noException) {
                close();
            }
        }

        return (This) this;
    }

    /**
     * Adds multiple sets of typed parameters for batch execution using an iterator.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Iterator<LocalDate> dates = getDateRange();
     * query.addBatchParameters(dates, LocalDate.class).batchUpdate();
     * }</pre>
     *
     * @param <T> the type of elements in the batch parameters collection
     * @param batchParameters Iterator over parameters
     * @param type The class type of the parameters
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException If batchParameters or type is null
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public <T> This addBatchParameters(final Iterator<? extends T> batchParameters, final Class<T> type) throws IllegalArgumentException, SQLException {
        checkArgNotNull(batchParameters, cs.batchParameters);
        checkArgNotNull(type, cs.type);

        @SuppressWarnings("UnnecessaryLocalVariable")
        final Iterator<? extends T> iter = batchParameters;
        final Type<T> setter = N.typeOf(type);
        boolean noException = false;

        try {
            if (!iter.hasNext()) {
                return (This) this;
            }

            while (iter.hasNext()) {
                setter.set(stmt, 1, iter.next());
                addBatch();
            }

            noException = true;
        } finally {
            if (!noException) {
                close();
            }
        }

        return (This) this;
    }

    //    /**
    //     *
    //     * @param batchParameters
    //     * @return
    //     * @throws SQLException
    //     */
    //    public Q addSingleBatchParameters(final Collection<?> batchParameters) throws SQLException {
    //        checkArgNotNull(batchParameters, "batchParameters");
    //
    //        if (N.isEmpty(batchParameters)) {
    //            return (Q) this;
    //        }
    //
    //        boolean noException = false;
    //
    //        try {
    //            for (Object obj : batchParameters) {
    //                setObject(1, obj);
    //
    //                addBatch();
    //            }
    //
    //            isBatch = batchParameters.size() > 0;
    //
    //            noException = true;
    //        } finally {
    //            if (noException == false) {
    //                close();
    //            }
    //        }
    //
    //        return (Q) this;
    //    }
    //
    //    /**
    //     *
    //     * @param batchParameters
    //     * @return
    //     * @throws SQLException
    //     */
    //    public Q addSingleBatchParameters(final Iterator<?> batchParameters) throws SQLException {
    //        checkArgNotNull(batchParameters, "batchParameters");
    //
    //        return addSingleBatchParameters(Iterators.toList(batchParameters));
    //    }

    /**
     * Adds multiple sets of parameters for batch execution using a custom parameter setter.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getUsers();
     * query.addBatchParameters(users, (q, user) -> {
     *     q.setString(1, user.getName())
     *      .setInt(2, user.getAge())
     *      .setString(3, user.getEmail());
     * }).batchInsert();
     * }</pre>
     *
     * @param <T> the type of elements in the batch parameters collection
     * @param batchParameters Collection of parameter objects
     * @param parametersSetter Function to set parameters for each object
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException If batchParameters or parametersSetter is null
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public <T> This addBatchParameters(final Collection<? extends T> batchParameters, final Jdbc.BiParametersSetter<? super This, ? super T> parametersSetter)
            throws SQLException {
        checkArgNotNull(batchParameters, cs.batchParameters);
        checkArgNotNull(parametersSetter, cs.parametersSetter);

        return addBatchParameters(batchParameters.iterator(), parametersSetter);
    }

    /**
     * Adds multiple sets of parameters for batch execution using a custom parameter setter and iterator.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Iterator<Product> products = getProductIterator();
     * query.addBatchParameters(products, (q, product) -> {
     *     q.setString(1, product.getName())
     *      .setBigDecimal(2, product.getPrice())
     *      .setInt(3, product.getStock());
     * }).batchUpdate();
     * }</pre>
     *
     * @param <T> the type of elements in the batch parameters collection
     * @param batchParameters Iterator over parameter objects
     * @param parametersSetter Function to set parameters for each object
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException If batchParameters or parametersSetter is null
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public <T> This addBatchParameters(final Iterator<? extends T> batchParameters, final Jdbc.BiParametersSetter<? super This, ? super T> parametersSetter)
            throws SQLException {
        checkArgNotNull(batchParameters, cs.batchParameters);
        checkArgNotNull(parametersSetter, cs.parametersSetter);

        final This it = (This) this;
        boolean noException = false;

        try {
            @SuppressWarnings("UnnecessaryLocalVariable")
            final Iterator<? extends T> iter = batchParameters;

            while (iter.hasNext()) {
                parametersSetter.accept(it, iter.next());
                addBatch();
            }

            noException = true;
        } finally {
            if (!noException) {
                close();
            }
        }

        return it;
    }

    //    /**
    //     * @param <T>
    //     * @param batchParameters
    //     * @param parametersSetter
    //     * @return
    //     * @throws SQLException
    //     */
    //    @Beta
    //    public <T> This addBatchParametters(final Collection<T> batchParameters, BiParametersSetter<? super Stmt, ? super T> parametersSetter) throws SQLException {
    //        checkArgNotNull(batchParameters, "batchParameters");
    //        checkArgNotNull(parametersSetter, "parametersSetter");
    //
    //        return addBatchParametters(batchParameters.iterator(), parametersSetter);
    //    }
    //
    //    /**
    //     *
    //     * @param <T>
    //     * @param batchParameters
    //     * @param parametersSetter
    //     * @return
    //     * @throws SQLException
    //     */
    //    @Beta
    //    public <T> This addBatchParametters(final Iterator<T> batchParameters, BiParametersSetter<? super Stmt, ? super T> parametersSetter) throws SQLException {
    //        checkArgNotNull(batchParameters, "batchParameters");
    //        checkArgNotNull(parametersSetter, "parametersSetter");
    //
    //        boolean noException = false;
    //
    //        try {
    //            final Iterator<T> iter = batchParameters;
    //
    //            while (iter.hasNext()) {
    //                parametersSetter.accept(stmt, iter.next());
    //                addBatch();
    //                isBatch = true;
    //            }
    //
    //            noException = true;
    //        } finally {
    //            if (noException == false) {
    //                close();
    //            }
    //        }
    //
    //        return (This) this;
    //    }

    /**
     * Adds multiple sets of parameters for batch execution using a TriConsumer parameter setter.
     * This provides access to both the query instance and the underlying statement.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<ComplexObject> objects = getComplexObjects();
     * query.addBatchParameters(objects, (q, stmt, obj) -> {
     *     q.setString(1, obj.getName());
     *     // Direct statement access for special cases
     *     stmt.setObject(2, obj.getCustomType(), Types.OTHER);
     *     q.setTimestamp(3, obj.getTimestamp());
     * }).batchInsert();
     * }</pre>
     *
     * @param <T> the type of elements in the batch parameters collection
     * @param batchParameters Collection of parameter objects
     * @param parametersSetter Function to set parameters with access to query and statement
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException If batchParameters or parametersSetter is null
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public <T> This addBatchParameters(final Collection<? extends T> batchParameters,
            final Throwables.TriConsumer<? super This, ? super Stmt, ? super T, ? extends SQLException> parametersSetter)
            throws IllegalArgumentException, SQLException {
        checkArgNotNull(batchParameters, cs.batchParameters);
        checkArgNotNull(parametersSetter, cs.parametersSetter);

        return addBatchParameters(batchParameters.iterator(), parametersSetter);
    }

    /**
     * Adds multiple sets of parameters for batch execution using a TriConsumer parameter setter and iterator.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Iterator<DataRecord> records = getBigDataIterator();
     * query.addBatchParameters(records, (q, stmt, record) -> {
     *     // Mix query convenience methods with direct statement access
     *     q.setString(1, record.getId());
     *     stmt.setArray(2, createSqlArray(record.getTags()));
     *     q.setTimestamp(3, record.getCreatedAt());
     * }).batchUpdate();
     * }</pre>
     *
     * @param <T> the type of elements in the batch parameters collection
     * @param batchParameters Iterator over parameter objects
     * @param parametersSetter Function to set parameters with access to query and statement
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException If batchParameters or parametersSetter is null
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public <T> This addBatchParameters(final Iterator<? extends T> batchParameters,
            final Throwables.TriConsumer<? super This, ? super Stmt, ? super T, ? extends SQLException> parametersSetter)
            throws IllegalArgumentException, SQLException {
        checkArgNotNull(batchParameters, cs.batchParameters);
        checkArgNotNull(parametersSetter, cs.parametersSetter);

        final This it = (This) this;
        boolean noException = false;

        try {
            @SuppressWarnings("UnnecessaryLocalVariable")
            final Iterator<? extends T> iter = batchParameters;

            while (iter.hasNext()) {
                parametersSetter.accept(it, stmt, iter.next());
                addBatch();
            }

            noException = true;
        } finally {
            if (!noException) {
                close();
            }
        }

        return it;
    }

    //    /**
    //     * @param <T>
    //     * @param batchParameters
    //     * @param parametersSetter
    //     * @return
    //     * @throws SQLException
    //     * @deprecated replaced by {@code addBatchParametters}
    //     */
    //    @Deprecated
    //    @Beta
    //    public <T> Q addBatchParameters2(final Collection<T> batchParameters, BiParametersSetter<? super S, ? super T> parametersSetter) throws SQLException {
    //        return addBatchParametters(batchParameters, parametersSetter);
    //    }
    //
    //    /**
    //     *
    //     * @param <T>
    //     * @param batchParameters
    //     * @param parametersSetter
    //     * @return
    //     * @throws SQLException
    //     * @deprecated replaced by {@code addBatchParametters}
    //     */
    //    @Deprecated
    //    @Beta
    //    public <T> Q addBatchParameters2(final Iterator<T> batchParameters, BiParametersSetter<? super S, ? super T> parametersSetter) throws SQLException {
    //        return addBatchParametters(batchParameters, parametersSetter);
    //    }

    /**
     * Adds the current set of parameters to this statement's batch of commands.
     * Call this after setting all parameters for one row/record.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setString(1, "John").setInt(2, 25).addBatch();
     * query.setString(1, "Jane").setInt(2, 30).addBatch();
     * query.setString(1, "Bob").setInt(2, 35).addBatch();
     * int[] results = query.batchUpdate();
     * }</pre>
     *
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs or this method is called on a closed statement
     * @see java.sql.PreparedStatement#addBatch()
     */
    public This addBatch() throws SQLException {
        assertNotClosed();

        addBatchAction.accept((This) this, stmt);

        isBatch = true;

        return (This) this;
    }

    This configAddBatchAction(final Throwables.BiConsumer<? super This, ? super Stmt, SQLException> addBatchAction) {
        this.addBatchAction = addBatchAction;

        return (This) this;
    }

    int defaultFetchDirection = -1;
    int defaultFetchSize = -1;
    int defaultQueryTimeout = -1;
    int defaultMaxFieldSize = -1;

    /**
     * Sets the direction for fetching rows from database tables.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setFetchDirection(FetchDirection.FORWARD)
     *      .setFetchSize(1000)
     *      .stream();
     * }</pre>
     *
     * @param direction One of {@code ResultSet.FETCH_FORWARD}, {@code ResultSet.FETCH_REVERSE}, 
     *                  or {@code ResultSet.FETCH_UNKNOWN}
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     * @see java.sql.Statement#setFetchDirection(int)
     */
    public This setFetchDirection(final FetchDirection direction) throws SQLException {
        assertNotClosed();

        defaultFetchDirection = stmt.getFetchDirection();

        stmt.setFetchDirection(direction.intValue);

        isFetchDirectionSet = true;

        return (This) this;
    }

    /**
     * Sets the fetch direction to FORWARD.
     * This is a convenience method equivalent to {@code setFetchDirection(FetchDirection.FORWARD)}.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setFetchDirectionToForward()
     *      .setFetchSize(500)
     *      .list();
     * }</pre>
     *
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     * @see #setFetchDirection(FetchDirection)
     */
    public This setFetchDirectionToForward() throws SQLException {
        return setFetchDirection(FetchDirection.FORWARD);
    }

    /**
     * Sets the number of rows that should be fetched from the database when more rows are needed.
     * This is a hint to the JDBC driver and may be ignored.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // For large result sets
     * query.setFetchSize(1000)
     *      .stream()  // Process rows in batches of 1000
     *      .forEach(row -> processRow(row));
     * }</pre>
     *
     * @param fetchSize The number of rows to fetch. Use 0 to let the JDBC driver choose.
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     * @see java.sql.Statement#setFetchSize(int)
     */
    public This setFetchSize(final int fetchSize) throws SQLException {
        assertNotClosed();

        defaultFetchSize = stmt.getFetchSize();

        stmt.setFetchSize(fetchSize);

        return (This) this;
    }

    /**
     * Sets the maximum number of bytes that can be returned for character and binary column values.
     * This limit applies only to BINARY, VARBINARY, LONGVARBINARY, CHAR, VARCHAR, NCHAR, NVARCHAR, 
     * LONGNVARCHAR and LONGVARCHAR columns.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setMaxFieldSize(1024 * 1024)  // 1MB limit
     *      .list();
     * }</pre>
     *
     * @param max The new column size limit in bytes; zero means there is no limit
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     * @see java.sql.Statement#setMaxFieldSize(int)
     */
    public This setMaxFieldSize(final int max) throws SQLException {
        assertNotClosed();

        defaultMaxFieldSize = stmt.getMaxFieldSize();

        stmt.setMaxFieldSize(max);

        return (This) this;
    }

    /**
     * Sets the maximum number of rows that this query can return.
     * If the limit is exceeded, the excess rows are silently dropped.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setMaxRows(100)  // Return at most 100 rows
     *      .list();
     * }</pre>
     *
     * @param max The new max rows limit; zero means there is no limit
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     * @see java.sql.Statement#setMaxRows(int)
     */
    public This setMaxRows(final int max) throws SQLException {
        assertNotClosed();

        stmt.setMaxRows(max);

        return (This) this;
    }

    /**
     * Sets the maximum number of rows that this query can return (for large row counts).
     * If the limit is exceeded, the excess rows are silently dropped.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setLargeMaxRows(1_000_000L)  // Return at most 1 million rows
     *      .stream();
     * }</pre>
     *
     * @param max The new max rows limit; zero means there is no limit
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     * @see java.sql.Statement#setLargeMaxRows(long)
     */
    public This setLargeMaxRows(final long max) throws SQLException {
        assertNotClosed();

        stmt.setLargeMaxRows(max);

        return (This) this;
    }

    /**
     * Sets the number of seconds the driver will wait for a Statement to execute.
     * If the limit is exceeded, a SQLException is thrown.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.setQueryTimeout(30)  // 30 seconds timeout
     *      .list();
     * }</pre>
     *
     * @param seconds The new query timeout limit in seconds; zero means there is no limit
     * @return this AbstractQuery instance for method chaining
     * @throws SQLException if a database access error occurs
     * @see java.sql.Statement#setQueryTimeout(int)
     */
    public This setQueryTimeout(final int seconds) throws SQLException {
        assertNotClosed();

        defaultQueryTimeout = stmt.getQueryTimeout();

        stmt.setQueryTimeout(seconds);

        return (This) this;
    }

    /**
     * Configures this statement using a custom configuration function.
     * This allows for advanced statement configuration not covered by other methods.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * final Throwables.Consumer<PreparedStatement, SQLException> commonConfig = stmt -> {
     *     stmt.setFetchSize(100);
     *     stmt.setQueryTimeout(60);
     *     stmt.setPoolable(false);
     * };
     * 
     * query.configStmt(commonConfig)
     *      .setParameters(params)
     *      .list();
     * }</pre>
     *
     * @param stmtSetter The function to configure the statement
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException If stmtSetter is null
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public This configStmt(final Throwables.Consumer<? super Stmt, ? extends SQLException> stmtSetter) throws IllegalArgumentException, SQLException {
        checkArgNotNull(stmtSetter, cs.stmtSetter);
        assertNotClosed();

        boolean noException = false;

        try {
            stmtSetter.accept(stmt);

            noException = true;
        } finally {
            if (!noException) {
                close();
            }
        }

        return (This) this;
    }

    /**
     * Configures this statement using a BiConsumer that has access to both the query and statement.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * query.configStmt((q, stmt) -> {
     *     q.setFetchSize(100).setQueryTimeout(60);
     *     // Additional direct statement configuration
     *     stmt.setPoolable(false);
     * })
     * .setParameters(params)
     * .list();
     * }</pre>
     *
     * @param stmtSetter The function to configure the statement
     * @return this AbstractQuery instance for method chaining
     * @throws IllegalArgumentException If stmtSetter is null
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public This configStmt(final Throwables.BiConsumer<? super This, ? super Stmt, ? extends SQLException> stmtSetter)
            throws IllegalArgumentException, SQLException {
        checkArgNotNull(stmtSetter, cs.stmtSetter);
        assertNotClosed();

        boolean noException = false;

        try {
            stmtSetter.accept((This) this, stmt);

            noException = true;
        } finally {
            if (!noException) {
                close();
            }
        }

        return (This) this;
    }

    int getFetchSize() throws SQLException {
        return stmt.getFetchSize();
    }

    int getFetchDirection() throws SQLException {
        return stmt.getFetchDirection();
    }

    /**
     * Executes the query and returns the first boolean value from the result set.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalBoolean isActive = query
     *     .setString(1, userId)
     *     .queryForBoolean();  // SELECT is_active FROM users WHERE id = ?
     * }</pre>
     *
     * @return An {@code OptionalBoolean} containing the value if present, otherwise empty
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public OptionalBoolean queryForBoolean() throws IllegalStateException, SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? OptionalBoolean.of(rs.getBoolean(1)) : OptionalBoolean.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    static final Type<Character> charType = TypeFactory.getType(char.class);

    /**
     * Executes the query and returns the first character value from the result set.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalChar grade = query
     *     .setString(1, studentId)
     *     .queryForChar();  // SELECT grade FROM students WHERE id = ?
     * }</pre>
     *
     * @return An {@code OptionalChar} containing the value if present, otherwise empty
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public OptionalChar queryForChar() throws IllegalStateException, SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            if (rs.next()) {
                return OptionalChar.of(charType.get(rs, 1));
            } else {
                return OptionalChar.empty();
            }
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the query and returns the first byte value from the result set.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalByte status = query
     *     .setInt(1, recordId)
     *     .queryForByte();  // SELECT status_code FROM records WHERE id = ?
     * }</pre>
     *
     * @return An {@code OptionalByte} containing the value if present, otherwise empty
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public OptionalByte queryForByte() throws IllegalStateException, SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? OptionalByte.of(rs.getByte(1)) : OptionalByte.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the query and returns the first short value from the result set.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalShort year = query
     *     .setString(1, movieId)
     *     .queryForShort();  // SELECT release_year FROM movies WHERE id = ?
     * }</pre>
     *
     * @return An {@code OptionalShort} containing the value if present, otherwise empty
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public OptionalShort queryForShort() throws IllegalStateException, SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? OptionalShort.of(rs.getShort(1)) : OptionalShort.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the query and returns the first integer value from the result set.
     * Commonly used for COUNT queries.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalInt count = query
     *     .setString(1, "Active")
     *     .queryForInt();  // SELECT COUNT(*) FROM users WHERE status = ?
     * 
     * int totalUsers = count.orElse(0);
     * }</pre>
     *
     * @return An {@code OptionalInt} containing the value if present, otherwise empty
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public OptionalInt queryForInt() throws IllegalStateException, SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? OptionalInt.of(rs.getInt(1)) : OptionalInt.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the query and returns the first long value from the result set.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalLong totalRevenue = query
     *     .setInt(1, year)
     *     .queryForLong();  // SELECT SUM(amount) FROM sales WHERE year = ?
     * }</pre>
     *
     * @return An {@code OptionalLong} containing the value if present, otherwise empty
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public OptionalLong queryForLong() throws IllegalStateException, SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the query and returns the first float value from the result set.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalFloat rating = query
     *     .setString(1, productId)
     *     .queryForFloat();  // SELECT avg_rating FROM products WHERE id = ?
     * }</pre>
     *
     * @return An {@code OptionalFloat} containing the value if present, otherwise empty
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public OptionalFloat queryForFloat() throws IllegalStateException, SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? OptionalFloat.of(rs.getFloat(1)) : OptionalFloat.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the query and returns the first double value from the result set.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalDouble average = query
     *     .setString(1, department)
     *     .queryForDouble();  // SELECT AVG(salary) FROM employees WHERE dept = ?
     * }</pre>
     *
     * @return An {@code OptionalDouble} containing the value if present, otherwise empty
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public OptionalDouble queryForDouble() throws IllegalStateException, SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? OptionalDouble.of(rs.getDouble(1)) : OptionalDouble.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the query and returns the first String value from the result set.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<String> name = query
     *     .setInt(1, userId)
     *     .queryForString();  // SELECT name FROM users WHERE id = ?
     * 
     * String userName = name.orElse("Unknown");
     * }</pre>
     *
     * @return A {@code Nullable<String>} containing the value if present, otherwise empty
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public Nullable<String> queryForString() throws IllegalStateException, SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Nullable.of(rs.getString(1)) : Nullable.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    private static final Type<BigInteger> BIG_INTEGER_TYPE = Type.of(BigInteger.class);

    /**
     * Executes the query and returns the first BigInteger value from the result set.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<BigInteger> largeNumber = query
     *     .setString(1, accountId)
     *     .queryForBigInteger();  // SELECT balance FROM accounts WHERE id = ?
     * }</pre>
     *
     * @return A {@code Nullable<BigInteger>} containing the value if present, otherwise empty
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public Nullable<BigInteger> queryForBigInteger() throws IllegalStateException, SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Nullable.of(BIG_INTEGER_TYPE.get(rs, 1)) : Nullable.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the query and returns the first BigDecimal value from the result set.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<BigDecimal> price = query
     *     .setString(1, productCode)
     *     .queryForBigDecimal();  // SELECT price FROM products WHERE code = ?
     * }</pre>
     *
     * @return A {@code Nullable<BigDecimal>} containing the value if present, otherwise empty
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public Nullable<BigDecimal> queryForBigDecimal() throws IllegalStateException, SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Nullable.of(rs.getBigDecimal(1)) : Nullable.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the query and returns the first Date value from the result set.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<java.sql.Date> birthDate = query
     *     .setString(1, employeeId)
     *     .queryForDate();  // SELECT birth_date FROM employees WHERE id = ?
     * }</pre>
     *
     * @return A {@code Nullable<java.sql.Date>} containing the value if present, otherwise empty
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public Nullable<java.sql.Date> queryForDate() throws IllegalStateException, SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Nullable.of(rs.getDate(1)) : Nullable.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the query and returns the first Time value from the result set.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<java.sql.Time> startTime = query
     *     .setString(1, eventId)
     *     .queryForTime();  // SELECT start_time FROM events WHERE id = ?
     * }</pre>
     *
     * @return A {@code Nullable<java.sql.Time>} containing the value if present, otherwise empty
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public Nullable<java.sql.Time> queryForTime() throws IllegalStateException, SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Nullable.of(rs.getTime(1)) : Nullable.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the query and returns the first Timestamp value from the result set.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<Timestamp> lastLogin = query
     *     .setString(1, username)
     *     .queryForTimestamp();  // SELECT last_login FROM users WHERE username = ?
     * }</pre>
     *
     * @return A {@code Nullable<java.sql.Timestamp>} containing the value if present, otherwise empty
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public Nullable<java.sql.Timestamp> queryForTimestamp() throws IllegalStateException, SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Nullable.of(rs.getTimestamp(1)) : Nullable.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the query and returns the first byte array value from the result set.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<byte[]> avatar = query
     *     .setInt(1, userId)
     *     .queryForBytes();  // SELECT avatar_data FROM users WHERE id = ?
     * }</pre>
     *
     * @return A {@code Nullable<byte[]>} containing the value if present, otherwise empty
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public Nullable<byte[]> queryForBytes() throws IllegalStateException, SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Nullable.of(rs.getBytes(1)) : Nullable.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the query and returns the first value from the result set as the specified type.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<LocalDate> date = query
     *     .setString(1, orderId)
     *     .queryForSingleResult(LocalDate.class);  // SELECT order_date FROM orders WHERE id = ?
     * }</pre>
     *
     * @param <V> the type of the single result value to be returned
     * @param targetValueType The class of the desired result type
     * @return A {@code Nullable} containing the value if present, otherwise empty
     * @throws IllegalArgumentException If targetValueType is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public <V> Nullable<V> queryForSingleResult(final Class<? extends V> targetValueType) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(targetValueType, cs.targetType);
        assertNotClosed();

        return queryForSingleResult(Type.of(targetValueType));
    }

    /**
     * Executes the query and returns the first value from the result set using a custom Type handler.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Type<MyCustomType> customType = TypeFactory.getType(MyCustomType.class);
     * Nullable<MyCustomType> result = query
     *     .setInt(1, id)
     *     .queryForSingleResult(customType);
     * }</pre>
     *
     * @param <V> the type of the single result value to be returned
     * @param targetValueType The Type handler for converting the result
     * @return A {@code Nullable} containing the value if present, otherwise empty
     * @throws IllegalArgumentException If targetValueType is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public <V> Nullable<V> queryForSingleResult(final Type<? extends V> targetValueType) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(targetValueType, cs.targetType);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Nullable.of(targetValueType.get(rs, 1)) : Nullable.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the query and returns the first non-null value from the result set as the specified type.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<String> email = query
     *     .setInt(1, userId)
     *     .queryForSingleNonNull(String.class);  // SELECT email FROM users WHERE id = ?
     * 
     * email.ifPresent(e -> sendNotification(e));
     * }</pre>
     *
     * @param <V> the type of the single result value to be returned
     * @param targetValueType The class of the desired result type
     * @return An {@code Optional} containing the non-null value if present, otherwise empty
     * @throws IllegalArgumentException If targetValueType is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public <V> Optional<V> queryForSingleNonNull(final Class<? extends V> targetValueType)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(targetValueType, cs.targetType);
        assertNotClosed();

        return queryForSingleNonNull(Type.of(targetValueType));
    }

    /**
     * Executes the query and returns the first non-null value from the result set using a custom Type handler.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Type<UUID> uuidType = Type.of(UUID.class);
     * Optional<UUID> sessionId = query
     *     .setString(1, token)
     *     .queryForSingleNonNull(uuidType);
     * }</pre>
     *
     * @param <V> the type of the single result value to be returned
     * @param targetValueType The Type handler for converting the result
     * @return An {@code Optional} containing the non-null value if present, otherwise empty
     * @throws IllegalArgumentException If targetValueType is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public <V> Optional<V> queryForSingleNonNull(final Type<? extends V> targetValueType) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(targetValueType, cs.targetType);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Optional.of(targetValueType.get(rs, 1)) : Optional.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the query and returns the unique result value from the result set as the specified type.
     * Throws DuplicatedResultException if more than one row is found.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<String> username = query
     *     .setString(1, email)
     *     .queryForUniqueResult(String.class);  // SELECT username FROM users WHERE email = ?
     * // Throws exception if multiple users have the same email
     * }</pre>
     *
     * @param <V> the type of the single result value to be returned
     * @param targetValueType The class of the desired result type
     * @return A {@code Nullable} containing the unique value if present, otherwise empty
     * @throws IllegalArgumentException If targetValueType is null
     * @throws IllegalStateException if this query is closed
     * @throws DuplicatedResultException If more than one row is found
     * @throws SQLException if a database access error occurs
     */
    public <V> Nullable<V> queryForUniqueResult(final Class<? extends V> targetValueType)
            throws IllegalArgumentException, IllegalStateException, DuplicatedResultException, SQLException {
        checkArgNotNull(targetValueType, cs.targetType);
        assertNotClosed();

        return queryForUniqueResult(Type.of(targetValueType));
    }

    /**
     * Executes the query and returns the unique result value from the result set using a custom Type handler.
     * Throws DuplicatedResultException if more than one row is found.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Type<BigDecimal> moneyType = Type.of(BigDecimal.class);
     * Nullable<BigDecimal> balance = query
     *     .setString(1, accountNumber)
     *     .queryForUniqueResult(moneyType);  // SELECT balance FROM accounts WHERE account_no = ?
     * }</pre>
     *
     * @param <V> the type of the single result value to be returned
     * @param targetValueType The Type handler for converting the result
     * @return A {@code Nullable} containing the unique value if present, otherwise empty
     * @throws IllegalArgumentException If targetValueType is null
     * @throws IllegalStateException if this query is closed
     * @throws DuplicatedResultException If more than one row is found
     * @throws SQLException if a database access error occurs
     */
    public <V> Nullable<V> queryForUniqueResult(final Type<? extends V> targetValueType)
            throws IllegalArgumentException, IllegalStateException, DuplicatedResultException, SQLException {
        checkArgNotNull(targetValueType, cs.targetType);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            final Nullable<V> result = rs.next() ? Nullable.of(targetValueType.get(rs, 1)) : Nullable.empty();

            if (result.isPresent() && rs.next()) {
                throw new DuplicatedResultException(
                        "At least two results found: " + Strings.concat(result.get(), ", ", N.convert(JdbcUtil.getColumnValue(rs, 1), targetValueType)));
            }

            return result;
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the query and returns the unique non-null result value as the specified type.
     * Throws DuplicatedResultException if more than one row is found.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<Integer> userId = query
     *     .setString(1, sessionToken)
     *     .queryForUniqueNonNull(Integer.class);  // SELECT user_id FROM sessions WHERE token = ?
     * // Throws exception if multiple sessions exist for the token
     * }</pre>
     *
     * @param <V> the type of the single result value to be returned
     * @param targetValueType The class of the desired result type
     * @return An {@code Optional} containing the unique non-null value if present, otherwise empty
     * @throws IllegalArgumentException If targetValueType is null
     * @throws IllegalStateException if this query is closed
     * @throws DuplicatedResultException If more than one row is found
     * @throws SQLException if a database access error occurs
     */
    public <V> Optional<V> queryForUniqueNonNull(final Class<? extends V> targetValueType)
            throws IllegalArgumentException, IllegalStateException, DuplicatedResultException, SQLException {
        checkArgNotNull(targetValueType, cs.targetType);
        assertNotClosed();

        return queryForUniqueNonNull(Type.of(targetValueType));
    }

    /**
     * Executes the query and returns the unique non-null result value using a custom Type handler.
     * Throws DuplicatedResultException if more than one row is found.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Type<CustomId> idType = Type.of(CustomId.class);
     * Optional<CustomId> id = query
     *     .setString(1, externalRef)
     *     .queryForUniqueNonNull(idType);
     * }</pre>
     *
     * @param <V> the type of the single result value to be returned
     * @param targetValueType The Type handler for converting the result
     * @return An {@code Optional} containing the unique non-null value if present, otherwise empty
     * @throws IllegalArgumentException If targetValueType is null
     * @throws IllegalStateException if this query is closed
     * @throws DuplicatedResultException If more than one row is found
     * @throws SQLException if a database access error occurs
     */
    public <V> Optional<V> queryForUniqueNonNull(final Type<? extends V> targetValueType)
            throws IllegalArgumentException, IllegalStateException, DuplicatedResultException, SQLException {
        checkArgNotNull(targetValueType, cs.targetType);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            final Optional<V> result = rs.next() ? Optional.of(targetValueType.get(rs, 1)) : Optional.empty();

            if (result.isPresent() && rs.next()) {
                throw new DuplicatedResultException(
                        "At least two results found: " + Strings.concat(result.get(), ", ", N.convert(JdbcUtil.getColumnValue(rs, 1), targetValueType)));
            }

            return result;
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @param <T> the type of entity to extract from the result set
     * @param rs the ResultSet to extract data from
     * @param targetType the class type to map the row to
     * @return the extracted entity instance
     * @throws SQLException if a database access error occurs
     */
    private static <T> T getRow(final ResultSet rs, final Class<? extends T> targetType) throws SQLException {
        final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

        return Jdbc.BiRowMapper.to(targetType).apply(rs, columnLabels);
    }

    /**
     * Retrieves the first {@code ResultSet} and converts it to a {@code Dataset}.
     * 
     * <p>This method executes the query and returns all results in a Dataset, which provides
     * a flexible, in-memory representation of the result set data. The Dataset can be used
     * for further data manipulation and transformation.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = preparedQuery.query();
     * dataset.forEach(row -> System.out.println(row));
     * }</pre>
     *
     * <p><b>Note:</b> The underlying statement will be closed after execution unless
     * {@link #closeAfterExecution(boolean)} has been set to {@code false}.</p>
     *
     * @return A {@code Dataset} containing all rows from the query result
     * @throws SQLException if a database access error occurs
     * @see Dataset
     */
    public Dataset query() throws SQLException {
        return query(Jdbc.ResultExtractor.TO_DATA_SET);
    }

    /**
     * Retrieves the first {@code ResultSet} and maps it to a {@code Dataset} using the specified entity class.
     * 
     * <p>The entity class is used to determine how to map columns from the result set to the Dataset.
     * This provides type information for better data handling and conversion.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = preparedQuery.query(User.class);
     * // Dataset will use User class metadata for column mapping
     * }</pre>
     *
     * <p><b>Note:</b> The underlying statement will be closed after execution unless
     * {@link #closeAfterExecution(boolean)} has been set to {@code false}.</p>
     *
     * @param entityClassForExtractor The class used to provide metadata for mapping columns in the result set
     * @return A {@code Dataset} containing the results with entity-aware column mapping
     * @throws SQLException if a database access error occurs
     * @see Jdbc.ResultExtractor#toDataset(Class)
     */
    public Dataset query(final Class<?> entityClassForExtractor) throws SQLException {
        return query(Jdbc.ResultExtractor.toDataset(entityClassForExtractor));
    }

    /**
     * Executes the query and extracts the result using the provided {@code ResultExtractor}.
     * 
     * <p>This is a flexible method that allows custom result extraction logic. The ResultExtractor
     * receives the entire ResultSet and can process it in any way needed, returning a custom result.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Extract to a custom summary object
     * Summary summary = preparedQuery.query(rs -> {
     *     Summary s = new Summary();
     *     while (rs.next()) {
     *         s.addValue(rs.getDouble("amount"));
     *     }
     *     return s;
     * });
     * }</pre>
     *
     * <p><b>Note:</b> The underlying statement will be closed after execution unless
     * {@link #closeAfterExecution(boolean)} has been set to {@code false}.</p>
     *
     * @param <R> the type of the result extracted by the extractor function
     * @param resultExtractor The extractor used to process the {@code ResultSet} and produce the result.
     *                        The ResultSet will be automatically closed after this call.
     * @return The result extracted from the {@code ResultSet}
     * @throws IllegalArgumentException if resultExtractor is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public <R> R query(final Jdbc.ResultExtractor<? extends R> resultExtractor) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(resultExtractor, cs.resultExtractor);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return JdbcUtil.checkNotResultSet(resultExtractor.apply(rs));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the query and extracts the result using the provided {@code BiResultExtractor}.
     * 
     * <p>Similar to {@link #query(ResultExtractor)}, but the BiResultExtractor also receives
     * the column labels list, which can be useful for dynamic column processing.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Extract with column labels awareness
     * Map<String, List<Object>> columnData = preparedQuery.query((rs, labels) -> {
     *     Map<String, List<Object>> result = new HashMap<>();
     *     for (String label : labels) {
     *         result.put(label, new ArrayList<>());
     *     }
     *     while (rs.next()) {
     *         for (String label : labels) {
     *             result.get(label).add(rs.getObject(label));
     *         }
     *     }
     *     return result;
     * });
     * }</pre>
     *
     * @param <R> the type of the result extracted by the extractor function
     * @param resultExtractor The extractor that receives both ResultSet and column labels. 
     *                        The ResultSet will be automatically closed after this call.
     * @return The result extracted from the {@code ResultSet}
     * @throws IllegalArgumentException if resultExtractor is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public <R> R query(final Jdbc.BiResultExtractor<? extends R> resultExtractor) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(resultExtractor, cs.resultExtractor);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return JdbcUtil.checkNotResultSet(resultExtractor.apply(rs, JdbcUtil.getColumnLabelList(rs)));
        } finally {
            closeAfterExecutionIfAllowed();

        }
    }

    /**
     * Retrieves at most two {@code ResultSets} from a stored procedure or multi-result query.
     * 
     * <p>This method is typically used with stored procedures that return multiple result sets.
     * It extracts the first two result sets using the provided extractors.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Tuple2<List<User>, List<Order>> results = callableQuery.query2Resultsets(
     *     Jdbc.BiResultExtractor.toList(User.class),
     *     Jdbc.BiResultExtractor.toList(Order.class)
     * );
     * List<User> users = results._1;
     * List<Order> orders = results._2;
     * }</pre>
     *
     * @param <R1> the type of result extracted from the first {@code ResultSet}
     * @param <R2> the type of result extracted from the second {@code ResultSet}
     * @param resultExtractor1 The extractor for the first {@code ResultSet}. ResultSet will be closed after extraction.
     * @param resultExtractor2 The extractor for the second {@code ResultSet}. ResultSet will be closed after extraction.
     * @return A {@code Tuple2} containing the results from both ResultSets (may contain null if ResultSet not available)
     * @throws IllegalArgumentException If any of the provided extractors is {@code null}
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public <R1, R2> Tuple2<R1, R2> query2Resultsets(final Jdbc.BiResultExtractor<? extends R1> resultExtractor1,
            final Jdbc.BiResultExtractor<? extends R2> resultExtractor2) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(resultExtractor1, cs.resultExtractor1);
        checkArgNotNull(resultExtractor2, cs.resultExtractor2);
        assertNotClosed();

        ObjIteratorEx<ResultSet> iter = null;

        try {
            final boolean isResultSet = JdbcUtil.execute(stmt);

            iter = JdbcUtil.iterateAllResultSets(stmt, isResultSet);

            R1 result1 = null;
            R2 result2 = null;

            if (iter.hasNext()) {
                result1 = JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtractor1);
            }

            if (iter.hasNext()) {
                result2 = JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtractor2);
            }

            return Tuple.of(result1, result2);
        } finally {
            try {
                if (iter != null) {
                    iter.close();
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }
    }

    /**
     * Retrieves at most three {@code ResultSets} from a stored procedure or multi-result query.
     * 
     * <p>This method is typically used with stored procedures that return multiple result sets.
     * It extracts the first three result sets using the provided extractors.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Tuple3<List<User>, List<Order>, Summary> results = callableQuery.query3Resultsets(
     *     Jdbc.BiResultExtractor.toList(User.class),
     *     Jdbc.BiResultExtractor.toList(Order.class),
     *     rs -> new Summary(rs.getInt(1), rs.getDouble(2))
     * );
     * }</pre>
     *
     * @param <R1> the type of result extracted from the first {@code ResultSet}
     * @param <R2> the type of result extracted from the second {@code ResultSet}
     * @param <R3> the type of result extracted from the third {@code ResultSet}
     * @param resultExtractor1 The extractor for the first {@code ResultSet}. ResultSet will be closed after extraction.
     * @param resultExtractor2 The extractor for the second {@code ResultSet}. ResultSet will be closed after extraction.
     * @param resultExtractor3 The extractor for the third {@code ResultSet}. ResultSet will be closed after extraction.
     * @return A {@code Tuple3} containing the results from all three ResultSets (may contain null if ResultSet not available)
     * @throws IllegalArgumentException If any of the provided extractors is {@code null}
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    @Beta
    public <R1, R2, R3> Tuple3<R1, R2, R3> query3Resultsets(final Jdbc.BiResultExtractor<? extends R1> resultExtractor1,
            final Jdbc.BiResultExtractor<? extends R2> resultExtractor2, final Jdbc.BiResultExtractor<? extends R3> resultExtractor3)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(resultExtractor1, cs.resultExtractor1);
        checkArgNotNull(resultExtractor2, cs.resultExtractor2);
        checkArgNotNull(resultExtractor3, cs.resultExtractor3);
        assertNotClosed();

        ObjIteratorEx<ResultSet> iter = null;

        try {
            final boolean isResultSet = JdbcUtil.execute(stmt);

            iter = JdbcUtil.iterateAllResultSets(stmt, isResultSet);

            R1 result1 = null;
            R2 result2 = null;
            R3 result3 = null;

            if (iter.hasNext()) {
                result1 = JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtractor1);
            }

            if (iter.hasNext()) {
                result2 = JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtractor2);
            }

            if (iter.hasNext()) {
                result3 = JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtractor3);
            }

            return Tuple.of(result1, result2, result3);
        } finally {
            try {
                if (iter != null) {
                    iter.close();
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }
    }

    /**
     * Retrieves all {@code ResultSets} and converts them to a list of {@code Dataset}.
     * 
     * <p>This method is useful for stored procedures that return multiple result sets.
     * Each result set is converted to a separate Dataset in the returned list.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Call a stored procedure that returns multiple result sets
     * List<Dataset> allResults = callableQuery.queryAllResultsets();
     * Dataset firstResultSet = allResults.get(0);
     * Dataset secondResultSet = allResults.get(1);
     * }</pre>
     *
     * @return A list of {@code Dataset} objects, one for each ResultSet returned by the query
     * @throws SQLException if a database access error occurs
     * @see #queryAllResultsets(ResultExtractor)
     * @see #streamAllResultsets()
     */
    public List<Dataset> queryAllResultsets() throws SQLException {
        return queryAllResultsets(ResultExtractor.TO_DATA_SET);
    }

    /**
     * Retrieves all {@code ResultSets} and processes them with the specified {@code ResultExtractor}.
     * 
     * <p>This method allows custom processing of each result set returned by a stored procedure
     * or multi-result query.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Extract row counts from all result sets
     * List<Integer> rowCounts = callableQuery.queryAllResultsets(rs -> {
     *     int count = 0;
     *     while (rs.next()) count++;
     *     return count;
     * });
     * }</pre>
     *
     * @param <R> the type of result extracted from each {@code ResultSet}
     * @param resultExtractor The extractor to process each {@code ResultSet}. Each ResultSet will be closed after extraction.
     * @return A list containing the extracted results from all ResultSets
     * @throws IllegalArgumentException if resultExtractor is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #streamAllResultsets(ResultExtractor)
     */
    public <R> List<R> queryAllResultsets(final Jdbc.ResultExtractor<? extends R> resultExtractor)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(resultExtractor, cs.resultExtractor);
        assertNotClosed();

        ObjIteratorEx<ResultSet> iter = null;

        try {
            final boolean isResultSet = JdbcUtil.execute(stmt);

            iter = JdbcUtil.iterateAllResultSets(stmt, isResultSet);

            final List<R> result = new ArrayList<>();

            while (iter.hasNext()) {
                result.add(JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtractor));
            }

            return result;
        } finally {
            try {
                if (iter != null) {
                    iter.close();
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }
    }

    /**
     * Retrieves all {@code ResultSets} and processes them with the specified {@code BiResultExtractor}.
     * 
     * <p>Similar to {@link #queryAllResultsets(ResultExtractor)}, but the extractor also receives
     * the column labels for each result set.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Extract column metadata from all result sets
     * List<Map<String, Class<?>>> metadata = callableQuery.queryAllResultsets((rs, labels) -> {
     *     Map<String, Class<?>> types = new HashMap<>();
     *     for (String label : labels) {
     *         types.put(label, rs.getMetaData().getColumnClassName(
     *             rs.findColumn(label)).getClass());
     *     }
     *     return types;
     * });
     * }</pre>
     *
     * @param <R> the type of result extracted from each {@code ResultSet}
     * @param resultExtractor The extractor that receives ResultSet and column labels. Each ResultSet will be closed after extraction.
     * @return A list containing the extracted results from all ResultSets
     * @throws IllegalArgumentException if resultExtractor is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #streamAllResultsets(BiResultExtractor)
     */
    public <R> List<R> queryAllResultsets(final Jdbc.BiResultExtractor<? extends R> resultExtractor)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(resultExtractor, cs.resultExtractor);
        assertNotClosed();

        ObjIteratorEx<ResultSet> iter = null;

        try {
            final boolean isResultSet = JdbcUtil.execute(stmt);

            iter = JdbcUtil.iterateAllResultSets(stmt, isResultSet);

            final List<R> result = new ArrayList<>();

            while (iter.hasNext()) {
                result.add(JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtractor));
            }

            return result;
        } finally {
            try {
                if (iter != null) {
                    iter.close();
                }
            } finally {
                closeAfterExecutionIfAllowed();
            }
        }
    }

    /**
     * Executes a query and applies a function to the resulting {@code Dataset}.
     * 
     * <p>This method combines query execution with result transformation in a single operation,
     * useful for chaining operations on the query result.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Calculate average from query result
     * Double average = preparedQuery.queryThenApply(dataset -> 
     *     dataset.stream().mapToDouble(row -> row.getDouble("amount")).average().orElse(0.0)
     * );
     * }</pre>
     *
     * @param <R> the type of result produced by the transformation function
     * @param <E> the type of exception that may be thrown by the function
     * @param func The function to apply to the {@code Dataset} resulting from the query
     * @return The result produced by applying the function to the {@code Dataset}
     * @throws SQLException if a database access error occurs
     * @throws E If the function throws an exception
     */
    @Beta
    public <R, E extends Exception> R queryThenApply(final Throwables.Function<? super Dataset, ? extends R, E> func) throws SQLException, E {
        return func.apply(query());
    }

    /**
     * Executes a query and applies a function to the resulting {@code Dataset}, using the specified entity class.
     * 
     * <p>Similar to {@link #queryThenApply(Throwables.Function)}, but uses an entity class
     * for better type-aware column mapping in the Dataset.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Process User entities from query
     * List<String> names = preparedQuery.queryThenApply(User.class, dataset -> 
     *     dataset.stream().map(row -> row.getString("name")).toList()
     * );
     * }</pre>
     *
     * @param <R> the type of result produced by the transformation function
     * @param <E> the type of exception that may be thrown by the function
     * @param entityClassForExtractor The class used to provide metadata for column mapping
     * @param func The function to apply to the {@code Dataset} resulting from the query
     * @return The result produced by applying the function to the {@code Dataset}
     * @throws SQLException if a database access error occurs
     * @throws E If the function throws an exception
     * @see Jdbc.ResultExtractor#toDataset(Class)
     */
    @Beta
    public <R, E extends Exception> R queryThenApply(final Class<?> entityClassForExtractor, final Throwables.Function<? super Dataset, ? extends R, E> func)
            throws SQLException, E {
        return func.apply(query(entityClassForExtractor));
    }

    /**
     * Executes a query and applies a consumer action to the resulting {@code Dataset}.
     * 
     * <p>This method is useful when you need to perform side effects with the query results
     * without returning a value.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Print all results
     * preparedQuery.queryThenAccept(dataset -> 
     *     dataset.forEach(row -> System.out.println(row))
     * );
     * }</pre>
     *
     * @param <E> the type of exception that may be thrown by the consumer
     * @param action The consumer action to apply to the {@code Dataset} resulting from the query
     * @throws SQLException if a database access error occurs
     * @throws E If the consumer action throws an exception
     */
    @Beta
    public <E extends Exception> void queryThenAccept(final Throwables.Consumer<? super Dataset, E> action) throws SQLException, E {
        action.accept(query());
    }

    /**
     * Executes a query and applies a consumer action to the resulting {@code Dataset}, using the specified entity class.
     * 
     * <p>Similar to {@link #queryThenAccept(Throwables.Consumer)}, but uses an entity class
     * for better type-aware column mapping in the Dataset.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Process User entities and save to file
     * preparedQuery.queryThenAccept(User.class, dataset -> {
     *     try (Writer writer = new FileWriter("users.csv")) {
     *         dataset.writeCsv(writer);
     *     }
     * });
     * }</pre>
     *
     * @param <E> the type of exception that may be thrown by the consumer
     * @param entityClassForExtractor The class used to provide metadata for column mapping
     * @param action The consumer action to apply to the {@code Dataset} resulting from the query
     * @throws SQLException if a database access error occurs
     * @throws E If the consumer action throws an exception
     * @see Jdbc.ResultExtractor#toDataset(Class)
     */
    @Beta
    public <E extends Exception> void queryThenAccept(final Class<?> entityClassForExtractor, final Throwables.Consumer<? super Dataset, E> action)
            throws SQLException, E {
        action.accept(query(entityClassForExtractor));
    }

    /**
     * Executes a query and returns an {@code Optional} containing a map of column names to values if exactly one record is found.
     * 
     * <p>This method ensures that the query returns exactly one row. If no rows or multiple rows
     * are found, it throws an appropriate exception.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Find exactly one user by ID
     * Optional<Map<String, Object>> user = preparedQuery
     *     .setLong(1, userId)
     *     .findOnlyOne();
     * 
     * user.ifPresent(u -> System.out.println("Found user: " + u.get("name")));
     * }</pre>
     *
     * @return An {@code Optional} containing a map of column names to values if exactly one record is found, otherwise empty
     * @throws DuplicatedResultException If the query finds more than one record
     * @throws SQLException if a database access error occurs
     * @see #queryForUniqueResult(Class)
     * @see #queryForUniqueNonNull(Class)
     */
    public Optional<Map<String, Object>> findOnlyOne() throws DuplicatedResultException, SQLException {
        return findOnlyOne(Jdbc.BiRowMapper.TO_MAP);
    }

    /**
     * Executes a query and returns an {@code Optional} containing a single result of the specified type if exactly one record is found.
     * 
     * <p>This method maps the single result row to an instance of the specified class using
     * reflection-based mapping.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Find exactly one user by email
     * Optional<User> user = preparedQuery
     *     .setString(1, email)
     *     .findOnlyOne(User.class);
     * 
     * User foundUser = user.orElseThrow(() -> new NotFoundException("User not found"));
     * }</pre>
     *
     * @param <T> the type of entity to be retrieved from the result set
     * @param targetType The class to map the result row to
     * @return An {@code Optional} containing the mapped object if exactly one record is found, otherwise empty
     * @throws DuplicatedResultException If the query finds more than one record
     * @throws SQLException if a database access error occurs
     * @see #queryForUniqueResult(Class)
     * @see #queryForUniqueNonNull(Class)
     */
    public <T> Optional<T> findOnlyOne(final Class<? extends T> targetType) throws NullPointerException, DuplicatedResultException, SQLException {
        return Optional.ofNullable(findOnlyOneOrNull(targetType));
    }

    /**
     * Executes a query and returns an {@code Optional} containing a single result extracted by the specified {@code RowMapper} if exactly one record is found.
     * 
     * <p>This method allows custom mapping logic for the single result row.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Custom mapping for a single result
     * Optional<UserSummary> summary = preparedQuery
     *     .setLong(1, userId)
     *     .findOnlyOne(rs -> new UserSummary(
     *         rs.getString("name"),
     *         rs.getInt("post_count")
     *     ));
     * }</pre>
     *
     * @param <T> the type of entity to be retrieved from the result set
     * @param rowMapper The {@code RowMapper} used to map the result set to the result object
     * @return An {@code Optional} containing the mapped object if exactly one record is found, otherwise empty
     * @throws IllegalArgumentException If the {@code rowMapper} returns {@code null} for the found record
     * @throws DuplicatedResultException If the query finds more than one record
     * @throws SQLException if a database access error occurs
     */
    public <T> Optional<T> findOnlyOne(final Jdbc.RowMapper<? extends T> rowMapper) throws NullPointerException, DuplicatedResultException, SQLException {
        return Optional.ofNullable(findOnlyOneOrNull(rowMapper));
    }

    /**
     * Executes a query and returns an {@code Optional} containing a single result extracted by the specified {@code BiRowMapper} if exactly one record is found.
     * 
     * <p>Similar to {@link #findOnlyOne(Jdbc.RowMapper)}, but the mapper also receives the column labels.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Map with column labels awareness
     * Optional<CustomObject> result = preparedQuery
     *     .setLong(1, id)
     *     .findOnlyOne((rs, labels) -> {
     *         CustomObject obj = new CustomObject();
     *         for (String label : labels) {
     *             obj.setProperty(label, rs.getObject(label));
     *         }
     *         return obj;
     *     });
     * }</pre>
     *
     * @param <T> the type of entity to be retrieved from the result set
     * @param rowMapper The {@code BiRowMapper} used to map the result set to the result object
     * @return An {@code Optional} containing the mapped object if exactly one record is found, otherwise empty
     * @throws IllegalArgumentException If the {@code rowMapper} returns {@code null} for the found record
     * @throws DuplicatedResultException If the query finds more than one record
     * @throws SQLException if a database access error occurs
     */
    public <T> Optional<T> findOnlyOne(final Jdbc.BiRowMapper<? extends T> rowMapper) throws NullPointerException, DuplicatedResultException, SQLException {
        return Optional.ofNullable(findOnlyOneOrNull(rowMapper));
    }

    /**
     * Executes a query and returns a single result as a {@code Map<String, Object>} if exactly one record is found.
     * 
     * <p>Similar to {@link #findOnlyOne()}, but returns null instead of an empty Optional when no record is found.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Map<String, Object> user = preparedQuery
     *     .setLong(1, userId)
     *     .findOnlyOneOrNull();
     * 
     * if (user != null) {
     *     System.out.println("Found user: " + user.get("name"));
     * }
     * }</pre>
     *
     * @return A {@code Map<String, Object>} containing the result if exactly one record is found, otherwise {@code null}
     * @throws DuplicatedResultException If the query finds more than one record
     * @throws SQLException if a database access error occurs
     */
    public Map<String, Object> findOnlyOneOrNull() throws DuplicatedResultException, SQLException {
        return findOnlyOneOrNull(Jdbc.BiRowMapper.TO_MAP);
    }

    /**
     * Executes a query and returns a single result of the specified type if exactly one record is found.
     * 
     * <p>Similar to {@link #findOnlyOne(Class)}, but returns null instead of an empty Optional when no record is found.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = preparedQuery
     *     .setString(1, email)
     *     .findOnlyOneOrNull(User.class);
     * 
     * if (user == null) {
     *     throw new NotFoundException("User not found");
     * }
     * }</pre>
     *
     * @param <T> the type of entity to be retrieved from the result set
     * @param targetType The class to map the result row to
     * @return The mapped object if exactly one record is found, otherwise {@code null}
     * @throws IllegalArgumentException If the target type is invalid
     * @throws IllegalStateException if this query is closed
     * @throws DuplicatedResultException If the query finds more than one record
     * @throws SQLException if a database access error occurs
     */
    public <T> T findOnlyOneOrNull(final Class<? extends T> targetType) throws NullPointerException, DuplicatedResultException, SQLException {
        checkArgNotNull(targetType, cs.targetType);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            if (rs.next()) {
                final T result = Objects.requireNonNull(getRow(rs, targetType));

                if (rs.next()) {
                    throw new DuplicatedResultException("More than one record found by the query");
                }

                return result;
            } else {
                return null;
            }
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes a query and returns a single result extracted by the specified {@code RowMapper} if exactly one record is found.
     * 
     * <p>Similar to {@link #findOnlyOne(Jdbc.RowMapper)}, but returns null instead of an empty Optional when no record is found.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * UserDTO user = preparedQuery
     *     .setLong(1, userId)
     *     .findOnlyOneOrNull(rs -> new UserDTO(
     *         rs.getLong("id"),
     *         rs.getString("username")
     *     ));
     * }</pre>
     *
     * @param <T> the type of entity to be retrieved from the result set
     * @param rowMapper The {@code RowMapper} used to map the result set to the result object
     * @return The mapped object if exactly one record is found, otherwise {@code null}
     * @throws IllegalArgumentException If the {@code rowMapper} returns {@code null} for the found record
     * @throws IllegalStateException if this query is closed
     * @throws DuplicatedResultException If the query finds more than one record
     * @throws SQLException if a database access error occurs
     */
    public <T> T findOnlyOneOrNull(final Jdbc.RowMapper<? extends T> rowMapper) throws NullPointerException, DuplicatedResultException, SQLException {
        checkArgNotNull(rowMapper, cs.rowMapper);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            if (rs.next()) {
                final T result = Objects.requireNonNull(rowMapper.apply(rs));

                if (rs.next()) {
                    throw new DuplicatedResultException("More than one record found by the query");
                }

                return result;
            } else {
                return null;
            }

        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes a query and returns a single result extracted by the specified {@code BiRowMapper} if exactly one record is found.
     * 
     * <p>Similar to {@link #findOnlyOne(BiRowMapper)}, but returns null instead of an empty Optional when no record is found.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * CustomResult result = preparedQuery
     *     .setLong(1, id)
     *     .findOnlyOneOrNull((rs, labels) -> {
     *         // Custom mapping with column labels
     *         return new CustomResult(rs, labels);
     *     });
     * }</pre>
     *
     * @param <T> the type of entity to be retrieved from the result set
     * @param rowMapper The {@code BiRowMapper} used to map the result set to the result object
     * @return The mapped object if exactly one record is found, otherwise {@code null}
     * @throws IllegalArgumentException If the {@code rowMapper} returns {@code null} for the found record
     * @throws IllegalStateException if this query is closed
     * @throws DuplicatedResultException If the query finds more than one record
     * @throws SQLException if a database access error occurs
     */
    public <T> T findOnlyOneOrNull(final Jdbc.BiRowMapper<? extends T> rowMapper) throws NullPointerException, DuplicatedResultException, SQLException {
        checkArgNotNull(rowMapper, cs.rowMapper);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            if (rs.next()) {
                final T result = Objects.requireNonNull(rowMapper.apply(rs, JdbcUtil.getColumnLabelList(rs)));

                if (rs.next()) {
                    throw new DuplicatedResultException("More than one record found by the query");
                }

                return result;
            } else {
                return null;
            }

        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes a query and returns the first result as an {@code Optional} containing a {@code Map<String, Object>}.
     * 
     * <p>Unlike {@link #findOnlyOne()}, this method doesn't throw an exception if multiple rows are found;
     * it simply returns the first row.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get the first matching user
     * Optional<Map<String, Object>> firstUser = preparedQuery
     *     .setString(1, "active")
     *     .findFirst();
     *
     * firstUser.ifPresent(user -> System.out.println("First user: " + user.get("name")));
     * }</pre>
     *
     * <p><b>Note:</b> The underlying statement will be closed after execution unless
     * {@link #closeAfterExecution(boolean)} has been set to {@code false}.</p>
     *
     * @return An {@code Optional} containing the first result as a map, or empty if no result is found
     * @throws SQLException if a database access error occurs
     * @see #queryForUniqueResult(Class)
     * @see #queryForUniqueNonNull(Class)
     */
    public Optional<Map<String, Object>> findFirst() throws SQLException {
        return findFirst(Jdbc.BiRowMapper.TO_MAP);
    }

    /**
     * Executes a query and returns the first result as an {@code Optional} containing an object of the specified type.
     * 
     * <p>This method maps the first result row to an instance of the specified class.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get the first active user
     * Optional<User> firstUser = preparedQuery
     *     .setString(1, "active")
     *     .findFirst(User.class);
     * 
     * firstUser.ifPresent(user -> System.out.println("First active user: " + user.getName()));
     * }</pre>
     *
     * @param <T> the type of entity to be retrieved from the result set
     * @param targetType The class to map the result row to
     * @return An {@code Optional} containing the first result, or empty if no result is found
     * @throws SQLException if a database access error occurs
     * @see #queryForUniqueResult(Class)
     * @see #queryForUniqueNonNull(Class)
     */
    public <T> Optional<T> findFirst(final Class<? extends T> targetType) throws NullPointerException, SQLException {
        return Optional.ofNullable(findFirstOrNull(targetType));
    }

    /**
     * Executes a query and returns the first result as an {@code Optional} containing an object extracted by the specified {@code RowMapper}.
     * 
     * <p>This method allows custom mapping logic for the first result row.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Custom mapping for the first result
     * Optional<String> firstName = preparedQuery
     *     .setString(1, "active")
     *     .findFirst(rs -> rs.getString("first_name") + " " + rs.getString("last_name"));
     * }</pre>
     *
     * @param <T> the type of entity to be retrieved from the result set
     * @param rowMapper The {@code RowMapper} used to map the result set to an object
     * @return An {@code Optional} containing the first result, or empty if no result is found
     * @throws IllegalArgumentException If {@code rowMapper} is {@code null}
     * @throws SQLException if a database access error occurs
     */
    public <T> Optional<T> findFirst(final Jdbc.RowMapper<? extends T> rowMapper) throws NullPointerException, SQLException {
        return Optional.ofNullable(findFirstOrNull(rowMapper));
    }

    /**
     * Executes a query with the specified {@code RowFilter} and {@code RowMapper}, and returns the first matching result as an {@code Optional}.
     * 
     * <p>This method filters rows and maps the first matching row.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Find first premium user
     * Optional<User> firstPremium = preparedQuery
     *     .findFirst(
     *         rs -> rs.getBoolean("is_premium"),
     *         rs -> new User(rs.getLong("id"), rs.getString("name"))
     *     );
     * }</pre>
     *
     * @param <T> the type of entity to be retrieved from the result set
     * @param rowFilter The {@code RowFilter} used to filter the rows in the result set
     * @param rowMapper The {@code RowMapper} used to map the result set to an object
     * @return An {@code Optional} containing the first matching result, or empty if no match is found
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException If {@code rowMapper} is {@code null}
     * @deprecated Use {@code stream(RowFilter, RowMapper).first()} instead
     */
    @Deprecated
    public <T> Optional<T> findFirst(final Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends T> rowMapper) throws NullPointerException, SQLException {
        return Optional.ofNullable(findFirstOrNull(rowFilter, rowMapper));
    }

    /**
     * Executes a query and returns the first result as an {@code Optional} containing an object extracted by the specified {@code BiRowMapper}.
     * 
     * <p>Similar to {@link #findFirst(RowMapper)}, but the mapper also receives the column labels.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Map first result with column awareness
     * Optional<Map<String, Object>> first = preparedQuery
     *     .findFirst((rs, labels) -> {
     *         Map<String, Object> row = new HashMap<>();
     *         for (String label : labels) {
     *             row.put(label.toLowerCase(), rs.getObject(label));
     *         }
     *         return row;
     *     });
     * }</pre>
     *
     * @param <T> the type of entity to be retrieved from the result set
     * @param rowMapper The {@code BiRowMapper} used to map the result set to an object
     * @return An {@code Optional} containing the first result, or empty if no result is found
     * @throws IllegalArgumentException If {@code rowMapper} is {@code null}
     * @throws SQLException if a database access error occurs
     */
    public <T> Optional<T> findFirst(final Jdbc.BiRowMapper<? extends T> rowMapper) throws NullPointerException, SQLException {
        return Optional.ofNullable(findFirstOrNull(rowMapper));
    }

    /**
     * Executes a query with the specified {@code BiRowFilter} and {@code BiRowMapper}, and returns the first matching result as an {@code Optional}.
     * 
     * <p>This method filters rows using column labels and maps the first matching row.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Find first row where any column contains "admin"
     * Optional<User> firstAdmin = preparedQuery
     *     .findFirst(
     *         (rs, labels) -> labels.stream()
     *             .anyMatch(label -> rs.getString(label).contains("admin")),
     *         (rs, labels) -> new User(rs)
     *     );
     * }</pre>
     *
     * @param <T> the type of entity to be retrieved from the result set
     * @param rowFilter The {@code BiRowFilter} used to filter the rows in the result set
     * @param rowMapper The {@code BiRowMapper} used to map the result set to an object
     * @return An {@code Optional} containing the first matching result, or empty if no match is found
     * @throws IllegalArgumentException If {@code rowMapper} is {@code null}
     * @throws SQLException if a database access error occurs
     * @deprecated Use {@code stream(BiRowFilter, BiRowMapper).first()} instead
     */
    @Deprecated
    public <T> Optional<T> findFirst(final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowMapper<? extends T> rowMapper)
            throws NullPointerException, SQLException {
        return Optional.ofNullable(findFirstOrNull(rowFilter, rowMapper));
    }

    /**
     * Executes a query and returns the first result as a {@code Map<String, Object>}.
     * 
     * <p>Similar to {@link #findFirst()}, but returns null instead of an empty Optional when no result is found.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Map<String, Object> firstRow = preparedQuery.findFirstOrNull();
     * if (firstRow != null) {
     *     System.out.println("Found: " + firstRow);
     * }
     * }</pre>
     *
     * @return A {@code Map<String, Object>} containing the first result, or {@code null} if no result is found
     * @throws SQLException if a database access error occurs
     */
    public Map<String, Object> findFirstOrNull() throws SQLException {
        return findFirstOrNull(Jdbc.BiRowMapper.TO_MAP);
    }

    /**
     * Executes a query and returns the first result of the specified type.
     * 
     * <p>Similar to {@link #findFirst(Class)}, but returns null instead of an empty Optional when no result is found.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User firstUser = preparedQuery
     *     .setString(1, "active")
     *     .findFirstOrNull(User.class);
     * 
     * if (firstUser != null) {
     *     processUser(firstUser);
     * }
     * }</pre>
     *
     * @param <T> the type of entity to be retrieved from the result set
     * @param targetType The class to map the result row to
     * @return The first result mapped to the specified type, or {@code null} if no result is found
     * @throws IllegalArgumentException If targetType is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public <T> T findFirstOrNull(final Class<? extends T> targetType) throws NullPointerException, SQLException {
        checkArgNotNull(targetType, cs.targetType);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            if (rs.next()) {
                return Objects.requireNonNull(getRow(rs, targetType));
            }

            return null;
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes a query and returns the first result extracted by the specified {@code RowMapper}.
     * 
     * <p>Similar to {@link #findFirst(RowMapper)}, but returns null instead of an empty Optional when no result is found.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String name = preparedQuery
     *     .setLong(1, userId)
     *     .findFirstOrNull(rs -> rs.getString("name"));
     * }</pre>
     *
     * @param <T> the type of entity to be retrieved from the result set
     * @param rowMapper The {@code RowMapper} used to map the result set to an object
     * @return The first result mapped by the rowMapper, or {@code null} if no result is found
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException If {@code rowMapper} returns {@code null} for the found record
     */
    public <T> T findFirstOrNull(final Jdbc.RowMapper<? extends T> rowMapper) throws NullPointerException, SQLException {
        checkArgNotNull(rowMapper, cs.rowMapper);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            if (rs.next()) {
                return Objects.requireNonNull(rowMapper.apply(rs));
            }

            return null;
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes a query with filtering and returns the first matching result extracted by the specified {@code RowMapper}.
     * 
     * <p>This method applies the filter to each row and returns the first row that matches.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User firstActiveUser = preparedQuery
     *     .findFirstOrNull(
     *         rs -> rs.getString("status").equals("ACTIVE"),
     *         rs -> new User(rs.getLong("id"), rs.getString("name"))
     *     );
     * }</pre>
     *
     * @param <T> the type of entity to be retrieved from the result set
     * @param rowFilter The {@code RowFilter} used to filter rows
     * @param rowMapper The {@code RowMapper} used to map the result set to an object
     * @return The first matching result, or {@code null} if no match is found
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @deprecated Use {@code stream(RowFilter, RowMapper).first()} instead
     */
    @Deprecated
    public <T> T findFirstOrNull(final Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends T> rowMapper) throws NullPointerException, SQLException {
        checkArgNotNull(rowFilter, cs.rowFilter);
        checkArgNotNull(rowMapper, cs.rowMapper);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            while (rs.next()) {
                if (rowFilter.test(rs)) {
                    return Objects.requireNonNull(rowMapper.apply(rs));
                }
            }

            return null;
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes a query and returns the first result extracted by the specified {@code BiRowMapper}.
     * 
     * <p>Similar to {@link #findFirst(BiRowMapper)}, but returns null instead of an empty Optional when no result is found.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * CustomObject first = preparedQuery
     *     .findFirstOrNull((rs, labels) -> {
     *         CustomObject obj = new CustomObject();
     *         obj.populateFrom(rs, labels);
     *         return obj;
     *     });
     * }</pre>
     *
     * @param <T> the type of entity to be retrieved from the result set
     * @param rowMapper The {@code BiRowMapper} used to map the result set to an object
     * @return The first result mapped by the rowMapper, or {@code null} if no result is found
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public <T> T findFirstOrNull(final Jdbc.BiRowMapper<? extends T> rowMapper) throws NullPointerException, SQLException {
        checkArgNotNull(rowMapper, cs.rowMapper);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            if (rs.next()) {
                return Objects.requireNonNull(rowMapper.apply(rs, JdbcUtil.getColumnLabelList(rs)));
            } else {
                return null;
            }
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes a query with filtering and returns the first matching result extracted by the specified {@code BiRowMapper}.
     * 
     * <p>This method applies the filter with column labels awareness and returns the first matching row.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Product firstExpensive = preparedQuery
     *     .findFirstOrNull(
     *         (rs, labels) -> rs.getDouble("price") > 1000,
     *         (rs, labels) -> new Product(rs, labels)
     *     );
     * }</pre>
     *
     * @param <T> the type of entity to be retrieved from the result set
     * @param rowFilter The {@code BiRowFilter} used to filter rows
     * @param rowMapper The {@code BiRowMapper} used to map the result set to an object
     * @return The first matching result, or {@code null} if no match is found
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @deprecated Use {@code stream(BiRowFilter, BiRowMapper).first()} instead
     */
    @Deprecated
    public <T> T findFirstOrNull(final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowMapper<? extends T> rowMapper) throws NullPointerException, SQLException {
        checkArgNotNull(rowFilter, cs.rowFilter);
        checkArgNotNull(rowMapper, cs.rowMapper);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

            while (rs.next()) {
                if (rowFilter.test(rs, columnLabels)) {
                    return Objects.requireNonNull(rowMapper.apply(rs, columnLabels));
                }
            }

            return null;
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Lists all rows in the first {@code ResultSet} as maps of column names to values.
     * 
     * <p>This is one of the most commonly used methods for retrieving multiple rows from a query.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Map<String, Object>> users = preparedQuery
     *     .setString(1, "active")
     *     .list();
     *
     * for (Map<String, Object> user : users) {
     *     System.out.println(user.get("name") + ": " + user.get("email"));
     * }
     * }</pre>
     *
     * <p><b>Note:</b> The underlying statement will be closed after execution unless
     * {@link #closeAfterExecution(boolean)} has been set to {@code false}.</p>
     *
     * @return A list of maps, where each map represents a row with column names as keys
     * @throws SQLException if a database access error occurs
     */
    public List<Map<String, Object>> list() throws SQLException {
        return list(Jdbc.BiRowMapper.TO_MAP);
    }

    /**
     * Lists the rows in the first ResultSet and maps them to the specified target type.
     * This method uses reflection-based mapping to convert each row to an instance of the target class.
     * 
     * <p><b>Mapping Rules:</b></p>
     * <ul>
     *   <li>Column names are matched to field/property names (case-insensitive)</li>
     *   <li>Underscores in column names are converted to camelCase (e.g., user_name → userName)</li>
     *   <li>The target class must have a no-argument constructor</li>
     *   <li>Fields can be set via public fields or setter methods</li>
     *   <li>Type conversion is automatic for common types</li>
     * </ul>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Define a simple entity class
     * public class User {
     *     private Long id;
     *     private String name;
     *     private int age;
     *     // getters and setters...
     * }
     * 
     * // Query and map to User objects
     * List<User> users = preparedQuery
     *     .setInt(1, 18)
     *     .list(User.class);
     * 
     * // Process the results
     * users.forEach(user -> {
     *     System.out.println(user.getName() + ": " + user.getAge());
     * });
     * }</pre>
     *
     * @param <T> the type of entities in the returned list
     * @param targetType The class of the type to map the rows to. Must not be null.
     * @return A list of objects of the specified type, where each object represents a row in the result set.
     *         Returns an empty list if no rows are found.
     * @throws IllegalArgumentException If targetType is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs or mapping fails
     * @see #list(Jdbc.RowMapper)
     * @see #stream(Class)
     * @see Jdbc.BiRowMapper#to(Class)
     */
    public <T> List<T> list(final Class<? extends T> targetType) throws SQLException {
        return list(Jdbc.BiRowMapper.to(targetType));
    }

    /**
     * Lists the rows in the first ResultSet and maps them to the specified target type with a maximum result limit.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Deprecated: Prefer using SQL LIMIT clause instead
     * List<User> users = query.list(User.class, 10);
     * // Better: Use LIMIT in SQL: "SELECT * FROM users LIMIT 10"
     * }</pre>
     *
     * @param <T> the type of entities in the returned list
     * @param targetType The class of the type to map the rows to
     * @param maxResult The maximum number of results to return
     * @return A list of objects of the specified type, limited by maxResult
     * @throws SQLException if a database access error occurs
     * @deprecated The result size should be limited in database server side by SQL scripts (e.g., LIMIT clause).
     *             Use SQL's LIMIT/TOP/ROWNUM instead for better performance.
     */
    @Deprecated
    public <T> List<T> list(final Class<? extends T> targetType, final int maxResult) throws SQLException {
        return list(Jdbc.BiRowMapper.to(targetType), maxResult);
    }

    /**
     * Lists the rows in the first ResultSet using the provided row mapper.
     * This method provides complete control over how each row is converted to an object.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Custom mapping for complex logic
     * List<UserDTO> users = preparedQuery
     *     .setString(1, "active")
     *     .list(rs -> {
     *         UserDTO dto = new UserDTO();
     *         dto.setId(rs.getLong("id"));
     *         dto.setFullName(rs.getString("first_name") + " " + rs.getString("last_name"));
     *         dto.setActive(rs.getBoolean("is_active"));
     *         return dto;
     *     });
     * 
     * // Using a predefined mapper
     * RowMapper<Product> productMapper = rs -> new Product(
     *     rs.getLong("id"),
     *     rs.getString("name"),
     *     rs.getBigDecimal("price")
     * );
     * List<Product> products = query.list(productMapper);
     * }</pre>
     *
     * @param <T> the type of entities in the returned list
     * @param rowMapper The row mapper to map each row of the ResultSet to an object. Must not be null.
     * @return A list of objects of the specified type, where each object represents a row in the result set
     * @throws IllegalArgumentException If rowMapper is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs or the row mapper throws an exception
     * @see Jdbc.RowMapper
     * @see #list(Jdbc.BiRowMapper)
     */
    public <T> List<T> list(final Jdbc.RowMapper<? extends T> rowMapper) throws SQLException {
        return list(rowMapper, Integer.MAX_VALUE);
    }

    /**
     * Lists the rows in the first ResultSet using the provided row mapper with a maximum result limit.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Deprecated: Prefer using SQL LIMIT clause instead
     * List<User> users = query.list(rs -> new User(rs.getLong("id"), rs.getString("name")), 10);
     * // Better: Use LIMIT in SQL: "SELECT * FROM users LIMIT 10"
     * }</pre>
     *
     * @param <T> the type of entities in the returned list
     * @param rowMapper The row mapper to map each row to an object
     * @param maxResult The maximum number of results to return
     * @return A list of mapped objects, limited by maxResult
     * @throws SQLException if a database access error occurs
     * @deprecated The result size should be limited in database server side by SQL scripts (e.g., LIMIT clause).
     *             Use SQL's LIMIT/TOP/ROWNUM instead for better performance.
     */
    @Deprecated
    public <T> List<T> list(final Jdbc.RowMapper<? extends T> rowMapper, final int maxResult) throws SQLException {
        return list(Jdbc.RowFilter.ALWAYS_TRUE, rowMapper, maxResult);
    }

    /**
     * Lists the rows in the first ResultSet that match the specified row filter and maps them using the provided row mapper.
     * This method allows filtering rows at the JDBC level before mapping.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Filter rows based on a condition before mapping
     * List<User> activeUsers = preparedQuery
     *     .list(
     *         rs -> rs.getBoolean("is_active"),  // Row filter
     *         rs -> new User(                    // Row mapper
     *             rs.getLong("id"),
     *             rs.getString("name")
     *         )
     *     );
     * 
     * // More complex filtering
     * List<Order> largeOrders = query
     *     .list(
     *         rs -> rs.getBigDecimal("total").compareTo(new BigDecimal("1000")) > 0,
     *         rs -> mapToOrder(rs)
     *     );
     * }</pre>
     *
     * @param <T> the type of entities in the returned list
     * @param rowFilter The filter to apply to each row of the ResultSet. Must not be null.
     * @param rowMapper The row mapper to map each row of the ResultSet to an object. Must not be null.
     * @return A list of objects that passed the filter, mapped by the row mapper
     * @throws IllegalArgumentException If rowFilter or rowMapper is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see Jdbc.RowFilter
     * @see Jdbc.RowMapper
     */
    public <T> List<T> list(final Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends T> rowMapper) throws SQLException {
        return list(rowFilter, rowMapper, Integer.MAX_VALUE);
    }

    /**
     * Lists the rows in the first ResultSet that match the specified row filter and maps them using the provided row mapper,
     * with a maximum result limit.
     * 
     * <p>This method processes rows sequentially, applying the filter first and only mapping rows that pass the filter.
     * Processing stops when either the ResultSet is exhausted or maxResult mapped objects have been created.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get top 10 high-value orders
     * List<Order> topOrders = preparedQuery
     *     .list(
     *         rs -> rs.getBigDecimal("total").compareTo(new BigDecimal("1000")) > 0,
     *         rs -> new Order(rs.getLong("id"), rs.getBigDecimal("total")),
     *         10
     *     );
     * }</pre>
     *
     * @param <T> the type of entities in the returned list
     * @param rowFilter The filter to apply to each row. Must not be null.
     * @param rowMapper The row mapper to map filtered rows to objects. Must not be null.
     * @param maxResult The maximum number of results to return. Must be non-negative.
     * @return A list of objects that passed the filter, limited by maxResult
     * @throws IllegalArgumentException If rowFilter or rowMapper is null, or maxResult is negative
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public <T> List<T> list(final Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends T> rowMapper, int maxResult)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, cs.rowFilter);
        checkArgNotNull(rowMapper, cs.rowMapper);
        checkArg(maxResult >= 0, "'maxResult' can' be negative: " + maxResult);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            final List<T> result = new ArrayList<>();

            while (maxResult > 0 && rs.next()) {
                if (rowFilter.test(rs)) {
                    result.add(rowMapper.apply(rs));
                    maxResult--;
                }
            }

            return result;
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Lists the rows in the first ResultSet and maps them using the provided BiRowMapper.
     * BiRowMapper receives both the ResultSet and column labels, providing more context for mapping.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Map with access to column labels
     * List<Map<String, Object>> results = preparedQuery
     *     .list((rs, columnLabels) -> {
     *         Map<String, Object> row = new HashMap<>();
     *         for (String label : columnLabels) {
     *             row.put(label.toLowerCase(), rs.getObject(label));
     *         }
     *         return row;
     *     });
     * 
     * // Dynamic mapping based on available columns
     * List<FlexibleDTO> dtos = query
     *     .list((rs, columnLabels) -> {
     *         FlexibleDTO dto = new FlexibleDTO();
     *         if (columnLabels.contains("name")) {
     *             dto.setName(rs.getString("name"));
     *         }
     *         if (columnLabels.contains("description")) {
     *             dto.setDescription(rs.getString("description"));
     *         }
     *         return dto;
     *     });
     * }</pre>
     *
     * @param <T> the type of entities in the returned list
     * @param rowMapper The BiRowMapper to map each row. Receives ResultSet and column labels. Must not be null.
     * @return A list of objects mapped by the BiRowMapper
     * @throws IllegalArgumentException If rowMapper is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see Jdbc.BiRowMapper
     * @see #list(Jdbc.RowMapper)
     */
    public <T> List<T> list(final Jdbc.BiRowMapper<? extends T> rowMapper) throws SQLException {
        return list(rowMapper, Integer.MAX_VALUE);
    }

    /**
     * Lists the rows in the first ResultSet using a BiRowMapper with a maximum result limit.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Deprecated: Prefer using SQL LIMIT clause instead
     * List<User> users = query.list((rs, labels) -> new User(rs.getLong("id"), rs.getString("name")), 10);
     * // Better: Use LIMIT in SQL: "SELECT * FROM users LIMIT 10"
     * }</pre>
     *
     * @param <T> the type of entities in the returned list
     * @param rowMapper The BiRowMapper to map each row
     * @param maxResult The maximum number of results to return
     * @return A list of mapped objects, limited by maxResult
     * @throws SQLException if a database access error occurs
     * @deprecated The result size should be limited in database server side by SQL scripts (e.g., LIMIT clause).
     *             Use SQL's LIMIT/TOP/ROWNUM instead for better performance.
     */
    @Deprecated
    public <T> List<T> list(final Jdbc.BiRowMapper<? extends T> rowMapper, final int maxResult) throws SQLException {
        return list(Jdbc.BiRowFilter.ALWAYS_TRUE, rowMapper, maxResult);
    }

    /**
     * Lists the rows in the first ResultSet that match the specified BiRowFilter and maps them using the provided BiRowMapper.
     * Both the filter and mapper receive the ResultSet and column labels for maximum flexibility.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Filter based on column existence and values
     * List<Product> products = preparedQuery
     *     .list(
     *         (rs, labels) -> labels.contains("price") && rs.getBigDecimal("price").compareTo(BigDecimal.ZERO) > 0,
     *         (rs, labels) -> new Product(rs.getLong("id"), rs.getString("name"), rs.getBigDecimal("price"))
     *     );
     * }</pre>
     *
     * @param <T> the type of entities in the returned list
     * @param rowFilter The BiRowFilter to test each row. Must not be null.
     * @param rowMapper The BiRowMapper to map filtered rows. Must not be null.
     * @return A list of objects that passed the filter
     * @throws IllegalArgumentException If rowFilter or rowMapper is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see Jdbc.BiRowFilter
     * @see Jdbc.BiRowMapper
     */
    public <T> List<T> list(final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowMapper<? extends T> rowMapper) throws SQLException {
        return list(rowFilter, rowMapper, Integer.MAX_VALUE);
    }

    /**
     * Lists the rows in the first ResultSet that match the specified BiRowFilter and maps them using the provided BiRowMapper,
     * with a maximum result limit.
     * 
     * <p>This method provides the most flexible row processing, with both filtering and mapping having access to
     * the ResultSet and column metadata. Processing stops when maxResult objects have been created.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Complex filtering and mapping with result limit
     * List<OrderSummary> summaries = preparedQuery
     *     .list(
     *         (rs, labels) -> {
     *             // Only include orders with all required fields
     *             return labels.containsAll(Arrays.asList("id", "total", "status")) &&
     *                    "COMPLETED".equals(rs.getString("status"));
     *         },
     *         (rs, labels) -> {
     *             OrderSummary summary = new OrderSummary();
     *             summary.setId(rs.getLong("id"));
     *             summary.setTotal(rs.getBigDecimal("total"));
     *             // Include optional fields if present
     *             if (labels.contains("customer_name")) {
     *                 summary.setCustomerName(rs.getString("customer_name"));
     *             }
     *             return summary;
     *         },
     *         100  // Limit to 100 results
     *     );
     * }</pre>
     *
     * @param <T> the type of entities in the returned list
     * @param rowFilter The BiRowFilter to test each row. Must not be null.
     * @param rowMapper The BiRowMapper to map filtered rows. Must not be null.
     * @param maxResult The maximum number of results to return. Must be non-negative.
     * @return A list of objects that passed the filter, limited by maxResult
     * @throws IllegalArgumentException If rowFilter or rowMapper is null, or maxResult is negative
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public <T> List<T> list(final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowMapper<? extends T> rowMapper, int maxResult)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, cs.rowFilter);
        checkArgNotNull(rowMapper, cs.rowMapper);
        checkArg(maxResult >= 0, "'maxResult' can' be negative: " + maxResult);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);
            final List<T> result = new ArrayList<>();

            while (maxResult > 0 && rs.next()) {
                if (rowFilter.test(rs, columnLabels)) {
                    result.add(rowMapper.apply(rs, columnLabels));
                    maxResult--;
                }
            }

            return result;
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Lists all the ResultSets returned by the query (typically from stored procedures).
     * Each ResultSet is converted to a list of objects of the specified type.
     * 
     * <p>This method is primarily used when executing stored procedures that return multiple ResultSets.
     * Each ResultSet is processed independently and mapped to a list of the target type.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Call a stored procedure that returns multiple result sets
     * CallableQuery query = JdbcUtil.prepareCall(connection, "{call getOrdersAndCustomers(?)}");
     * query.setInt(1, regionId);
     * 
     * List<List<Object>> allResults = query.listAllResultsets(Object.class);
     * List<Order> orders = (List<Order>) allResults.get(0);
     * List<Customer> customers = (List<Customer>) allResults.get(1);
     * 
     * // With specific types for each result set
     * List<List<Order>> orderResults = query.listAllResultsets(Order.class);
     * // Note: This assumes all result sets can be mapped to Order type
     * }</pre>
     *
     * @param <T> the type of entities extracted from each result set
     * @param targetType The class to map each row to. Must not be null.
     * @return A list of lists, where each inner list represents one ResultSet
     * @throws IllegalArgumentException If targetType is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #listAllResultsets(Jdbc.RowMapper)
     */
    public <T> List<List<T>> listAllResultsets(final Class<? extends T> targetType) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(targetType, cs.targetType);
        assertNotClosed();

        try {
            JdbcUtil.execute(stmt);

            return JdbcUtil.<List<T>> streamAllResultSets(stmt, Jdbc.BiResultExtractor.toList(targetType)).toList();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Lists all the ResultSets using the provided row mapper.
     * This method is used for processing multiple ResultSets returned by stored procedures.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Custom mapping for each result set
     * List<List<CustomDTO>> allResults = callableQuery
     *     .listAllResultsets(rs -> {
     *         CustomDTO dto = new CustomDTO();
     *         dto.setId(rs.getLong("id"));
     *         dto.setData(rs.getString("data"));
     *         return dto;
     *     });
     * }</pre>
     *
     * @param <T> the type of entities extracted from each result set
     * @param rowMapper The row mapper to apply to each row in all ResultSets. Must not be null.
     * @return A list of lists, where each inner list represents one ResultSet
     * @throws IllegalArgumentException If rowMapper is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see Jdbc.RowMapper
     */
    public <T> List<List<T>> listAllResultsets(final Jdbc.RowMapper<? extends T> rowMapper)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowMapper, cs.rowMapper);
        assertNotClosed();

        try {
            JdbcUtil.execute(stmt);

            return JdbcUtil.<List<T>> streamAllResultSets(stmt, Jdbc.ResultExtractor.toList(rowMapper)).toList();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Lists all the ResultSets that match the specified row filter and maps them using the provided row mapper.
     * Allows filtering rows across all ResultSets before mapping.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Process multiple result sets with filtering
     * List<List<ActiveRecord>> activeRecords = callableQuery
     *     .listAllResultsets(
     *         rs -> rs.getBoolean("is_active"),
     *         rs -> new ActiveRecord(rs.getLong("id"), rs.getString("name"))
     *     );
     * }</pre>
     *
     * @param <T> the type of entities extracted from each result set
     * @param rowFilter The filter to apply to each row. Must not be null.
     * @param rowMapper The row mapper for filtered rows. Must not be null.
     * @return A list of lists, where each inner list contains filtered and mapped rows from one ResultSet
     * @throws IllegalArgumentException If rowFilter or rowMapper is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public <T> List<List<T>> listAllResultsets(final Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends T> rowMapper)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, cs.rowFilter);
        checkArgNotNull(rowMapper, cs.rowMapper);
        assertNotClosed();

        try {
            JdbcUtil.execute(stmt);

            return JdbcUtil.<List<T>> streamAllResultSets(stmt, Jdbc.ResultExtractor.toList(rowFilter, rowMapper)).toList();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Lists all the ResultSets and maps them using the provided BiRowMapper.
     * The BiRowMapper receives both ResultSet and column labels for flexible mapping.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Map multiple result sets with column awareness
     * List<List<FlexibleDTO>> allResults = callableQuery
     *     .listAllResultsets((rs, labels) -> {
     *         FlexibleDTO dto = new FlexibleDTO();
     *         dto.setAvailableColumns(labels);
     *         for (String label : labels) {
     *             dto.addValue(label, rs.getObject(label));
     *         }
     *         return dto;
     *     });
     * }</pre>
     *
     * @param <T> the type of entities extracted from each result set
     * @param rowMapper The BiRowMapper to apply to each row. Must not be null.
     * @return A list of lists, where each inner list represents one ResultSet
     * @throws IllegalArgumentException If rowMapper is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see Jdbc.BiRowMapper
     */
    public <T> List<List<T>> listAllResultsets(final Jdbc.BiRowMapper<? extends T> rowMapper)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowMapper, cs.rowMapper);
        assertNotClosed();

        try {
            JdbcUtil.execute(stmt);

            return JdbcUtil.<List<T>> streamAllResultSets(stmt, Jdbc.BiResultExtractor.toList(rowMapper)).toList();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Lists all the ResultSets that match the specified BiRowFilter and maps them using the provided BiRowMapper.
     * Both filter and mapper receive ResultSet and column labels for maximum flexibility.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Process multiple result sets with column-aware filtering and mapping
     * List<List<ProcessedRecord>> results = callableQuery
     *     .listAllResultsets(
     *         (rs, labels) -> labels.contains("process_flag") && rs.getBoolean("process_flag"),
     *         (rs, labels) -> {
     *             ProcessedRecord record = new ProcessedRecord();
     *             record.setId(rs.getLong("id"));
     *             if (labels.contains("metadata")) {
     *                 record.setMetadata(rs.getString("metadata"));
     *             }
     *             return record;
     *         }
     *     );
     * }</pre>
     *
     * @param <T> the type of entities extracted from each result set
     * @param rowFilter The BiRowFilter to test each row. Must not be null.
     * @param rowMapper The BiRowMapper for filtered rows. Must not be null.
     * @return A list of lists with filtered and mapped rows from each ResultSet
     * @throws IllegalArgumentException If rowFilter or rowMapper is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public <T> List<List<T>> listAllResultsets(final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowMapper<? extends T> rowMapper)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, cs.rowFilter);
        checkArgNotNull(rowMapper, cs.rowMapper);
        assertNotClosed();

        try {
            JdbcUtil.execute(stmt);

            return JdbcUtil.<List<T>> streamAllResultSets(stmt, Jdbc.BiResultExtractor.toList(rowFilter, rowMapper)).toList();
        } finally {
            closeAfterExecutionIfAllowed();
        }

    }

    /**
     * Retrieves query results as a list and applies the specified function to transform them.
     * This method combines querying and transformation in a single operation.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Convert list to a map indexed by ID
     * Map<Long, User> userMap = preparedQuery
     *     .setString(1, "active")
     *     .listThenApply(User.class, users -> 
     *         users.stream().collect(Collectors.toMap(User::getId, Function.identity()))
     *     );
     * 
     * // Calculate statistics from results
     * Statistics stats = query
     *     .listThenApply(Order.class, orders -> {
     *         Statistics s = new Statistics();
     *         s.count = orders.size();
     *         s.totalAmount = orders.stream()
     *             .map(Order::getAmount)
     *             .reduce(BigDecimal.ZERO, BigDecimal::add);
     *         return s;
     *     });
     * }</pre>
     *
     * @param <T> the type of entities in the list result
     * @param <R> the type of result after applying the transformation function
     * @param <E> the type of exception that may be thrown by the function
     * @param targetType The class to map each row to. Must not be null.
     * @param func The function to apply to the list. Must not be null.
     * @return The result of applying the function to the list
     * @throws SQLException if a database access error occurs
     * @throws E If the function throws an exception
     * @see #list(Class)
     * @see #listThenAccept(Class, Throwables.Consumer)
     */
    @Beta
    public <T, R, E extends Exception> R listThenApply(final Class<? extends T> targetType, final Throwables.Function<? super List<T>, ? extends R, E> func)
            throws SQLException, E {
        return func.apply(list(targetType));
    }

    /**
     * Retrieves query results using a row mapper and applies the specified function to transform them.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Extract specific data from custom-mapped results
     * Set<String> uniqueCategories = preparedQuery
     *     .listThenApply(
     *         rs -> rs.getString("category"),
     *         categories -> new HashSet<>(categories)
     *     );
     * 
     * // Generate a summary from mapped results
     * String summary = query
     *     .listThenApply(
     *         rs -> new OrderInfo(rs.getLong("id"), rs.getBigDecimal("total")),
     *         orders -> orders.stream()
     *             .map(o -> o.getId() + ": $" + o.getTotal())
     *             .collect(Collectors.joining(", "))
     *     );
     * }</pre>
     *
     * @param <T> the type of entities in the list result
     * @param <R> the type of result after applying the transformation function
     * @param <E> the type of exception that may be thrown by the function
     * @param rowMapper The row mapper to use. Must not be null.
     * @param func The function to apply to the list. Must not be null.
     * @return The result of applying the function to the list
     * @throws SQLException if a database access error occurs
     * @throws E If the function throws an exception
     */
    @Beta
    public <T, R, E extends Exception> R listThenApply(final Jdbc.RowMapper<? extends T> rowMapper,
            final Throwables.Function<? super List<T>, ? extends R, E> func) throws SQLException, E {
        return func.apply(list(rowMapper));
    }

    /**
     * Retrieves query results using a BiRowMapper and applies the specified function to transform them.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Process results with column awareness and transform
     * JsonArray jsonResult = preparedQuery
     *     .listThenApply(
     *         (rs, labels) -> {
     *             JsonObject obj = new JsonObject();
     *             for (String label : labels) {
     *                 obj.add(label, rs.getObject(label));
     *             }
     *             return obj;
     *         },
     *         list -> {
     *             JsonArray array = new JsonArray();
     *             list.forEach(array::add);
     *             return array;
     *         }
     *     );
     * }</pre>
     *
     * @param <T> the type of entities in the list result
     * @param <R> the type of result after applying the transformation function
     * @param <E> the type of exception that may be thrown by the function
     * @param rowMapper The BiRowMapper to use. Must not be null.
     * @param func The function to apply to the list. Must not be null.
     * @return The result of applying the function to the list
     * @throws SQLException if a database access error occurs
     * @throws E If the function throws an exception
     */
    @Beta
    public <T, R, E extends Exception> R listThenApply(final Jdbc.BiRowMapper<? extends T> rowMapper,
            final Throwables.Function<? super List<T>, ? extends R, E> func) throws SQLException, E {
        return func.apply(list(rowMapper));
    }

    /**
     * Retrieves query results as a list and processes them with the specified consumer.
     * This method is useful for side effects like logging, caching, or batch processing.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Log all results
     * preparedQuery
     *     .setInt(1, departmentId)
     *     .listThenAccept(Employee.class, employees -> {
     *         logger.info("Found {} employees", employees.size());
     *         employees.forEach(emp -> logger.debug("Employee: {}", emp));
     *     });
     * 
     * // Cache results
     * query.listThenAccept(Product.class, products -> {
     *     products.forEach(p -> cache.put(p.getId(), p));
     * });
     * 
     * // Send batch notifications
     * query.listThenAccept(User.class, users -> {
     *     List<String> emails = users.stream()
     *         .map(User::getEmail)
     *         .collect(Collectors.toList());
     *     emailService.sendBulkNotification(emails);
     * });
     * }</pre>
     *
     * @param <T> the type of entities in the list result
     * @param <E> the type of exception that may be thrown by the consumer
     * @param targetType The class to map each row to. Must not be null.
     * @param consumer The consumer to process the list. Must not be null.
     * @throws SQLException if a database access error occurs
     * @throws E If the consumer throws an exception
     * @see #list(Class)
     * @see #listThenApply(Class, Throwables.Function)
     */
    @Beta
    public <T, E extends Exception> void listThenAccept(final Class<? extends T> targetType, final Throwables.Consumer<? super List<T>, E> consumer)
            throws SQLException, E {
        consumer.accept(list(targetType));
    }

    /**
     * Retrieves query results using a RowMapper and processes them with the specified consumer.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Process custom-mapped results
     * preparedQuery
     *     .listThenAccept(
     *         rs -> new EventLog(rs.getTimestamp("time"), rs.getString("message")),
     *         logs -> {
     *             logs.forEach(log -> auditService.record(log));
     *             logger.info("Processed {} audit logs", logs.size());
     *         }
     *     );
     * }</pre>
     *
     * @param <T> the type of entities in the list result
     * @param <E> the type of exception that may be thrown by the consumer
     * @param rowMapper The RowMapper to use. Must not be null.
     * @param consumer The consumer to process the list. Must not be null.
     * @throws SQLException if a database access error occurs
     * @throws E If the consumer throws an exception
     */
    @Beta
    public <T, E extends Exception> void listThenAccept(final Jdbc.RowMapper<? extends T> rowMapper, final Throwables.Consumer<? super List<T>, E> consumer)
            throws SQLException, E {
        consumer.accept(list(rowMapper));
    }

    /**
     * Retrieves query results using a BiRowMapper and processes them with the specified consumer.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Process results with column metadata
     * preparedQuery
     *     .listThenAccept(
     *         (rs, labels) -> {
     *             Map<String, Object> row = new HashMap<>();
     *             labels.forEach(label -> row.put(label, rs.getObject(label)));
     *             return row;
     *         },
     *         results -> {
     *             // Export to CSV or other format
     *             csvExporter.export(results);
     *         }
     *     );
     * }</pre>
     *
     * @param <T> the type of entities in the list result
     * @param <E> the type of exception that may be thrown by the consumer
     * @param rowMapper The BiRowMapper to use. Must not be null.
     * @param consumer The consumer to process the list. Must not be null.
     * @throws SQLException if a database access error occurs
     * @throws E If the consumer throws an exception
     */
    @Beta
    public <T, E extends Exception> void listThenAccept(final Jdbc.BiRowMapper<? extends T> rowMapper, final Throwables.Consumer<? super List<T>, E> consumer)
            throws SQLException, E {
        consumer.accept(list(rowMapper));
    }

    /**
     * Streams the rows in the first ResultSet as a lazy-evaluated stream of maps.
     * Each map contains column names as keys and column values as values.
     * 
     * <p><b>Important:</b> This method uses lazy execution and lazy fetching. The query is not executed
     * until a terminal operation is called on the stream. The Connection and Statement remain open
     * until the stream is closed or a terminal operation completes.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Process large result set without loading all into memory
     * try (Stream<Map<String, Object>> stream = preparedQuery.stream()) {
     *     stream.filter(row -> (Integer) row.get("age") > 21)
     *           .map(row -> row.get("email"))
     *           .forEach(email -> sendNewsletter(email));
     * }
     * 
     * // Aggregate operations
     * long count = preparedQuery.stream()
     *     .filter(row -> "active".equals(row.get("status")))
     *     .count();
     * }</pre>
     *
     * @return A lazy-evaluated Stream of {@code Map<String, Object>} representing the rows
     * @throws IllegalStateException if this query is closed
     * @see #list()
     * @see #stream(Class)
     * @see Stream
     */
    @LazyEvaluation
    public Stream<Map<String, Object>> stream() {
        // Will it cause confusion if it's called in transaction?

        return stream(Jdbc.BiRowMapper.TO_MAP);
    }

    /**
     * Streams the rows in the first ResultSet, mapping each row to the specified target type.
     * This method provides lazy evaluation for memory-efficient processing of large result sets.
     * 
     * <p><b>Important:</b> The stream is lazy-evaluated. The query executes when a terminal operation
     * is called on the stream. Resources are held open until the stream is closed.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Process users in batches without loading all into memory
     * try (Stream<User> userStream = preparedQuery.stream(User.class)) {
     *     userStream.filter(user -> user.isActive())
     *               .map(User::getEmail)
     *               .distinct()
     *               .forEach(email -> emailService.send(email));
     * }
     * 
     * // Collect filtered results
     * List<Customer> premiumCustomers = preparedQuery
     *     .stream(Customer.class)
     *     .filter(c -> c.getTier() == CustomerTier.PREMIUM)
     *     .collect(Collectors.toList());
     * }</pre>
     *
     * @param <T> the type of entities in the stream result
     * @param targetType The class to map each row to. Must not be null.
     * @return A lazy-evaluated Stream of the specified type
     * @throws IllegalArgumentException If targetType is null
     * @throws IllegalStateException if this query is closed
     * @see #stream(Jdbc.RowMapper)
     * @see #list(Class)
     */
    @LazyEvaluation
    public <T> Stream<T> stream(final Class<? extends T> targetType) {
        return stream(Jdbc.BiRowMapper.to(targetType));
    }

    /**
     * Streams the rows in the first ResultSet using the provided RowMapper.
     * Provides custom row mapping with lazy evaluation for efficient processing.
     * 
     * <p><b>Important:</b> The stream is lazy-evaluated and holds database resources open.
     * Always close the stream or use try-with-resources to ensure proper cleanup.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Stream with custom mapping
     * try (Stream<CustomerDTO> stream = preparedQuery.stream(rs -> {
     *     CustomerDTO dto = new CustomerDTO();
     *     dto.setId(rs.getLong("customer_id"));
     *     dto.setFullName(rs.getString("first_name") + " " + rs.getString("last_name"));
     *     dto.setTotalPurchases(rs.getBigDecimal("total_purchases"));
     *     return dto;
     * })) {
     *     stream.sorted(Comparator.comparing(CustomerDTO::getTotalPurchases).reversed())
     *           .limit(10)
     *           .forEach(System.out::println);
     * }
     * 
     * // Parallel processing for CPU-intensive operations
     * preparedQuery.stream(rowMapper)
     *     .parallel()
     *     .map(this::enrichWithExternalData)
     *     .forEach(this::process);
     * }</pre>
     *
     * @param <T> the type of entities in the stream result
     * @param rowMapper The RowMapper to transform each row. Must not be null.
     * @return A lazy-evaluated Stream of mapped objects
     * @throws IllegalArgumentException If rowMapper is null
     * @throws IllegalStateException if this query is closed
     * @see Jdbc.RowMapper
     * @see #stream(Jdbc.BiRowMapper)
     */
    @SuppressWarnings("resource")
    @LazyEvaluation
    public <T> Stream<T> stream(final Jdbc.RowMapper<? extends T> rowMapper) throws IllegalArgumentException, IllegalStateException {
        // Will it cause confusion if it's called in transaction?

        checkArgNotNull(rowMapper, cs.rowMapper);
        assertNotClosed();

        final Supplier<ResultSet> supplier = createQuerySupplier();

        return Stream.just(supplier)
                .map(Supplier::get)
                .flatMap(rs -> JdbcUtil.<T> stream(rs, rowMapper).onClose(() -> JdbcUtil.closeQuietly(rs)))
                .onClose(this::closeAfterExecutionIfAllowed);
    }

    /**
     * Streams the rows in the first ResultSet using the provided BiRowMapper.
     * The BiRowMapper receives both ResultSet and column labels for flexible mapping.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Stream with column-aware mapping
     * try (Stream<FlexibleRecord> stream = preparedQuery.stream((rs, labels) -> {
     *     FlexibleRecord record = new FlexibleRecord();
     *     for (String label : labels) {
     *         if (!label.startsWith("internal_")) {  // Skip internal columns
     *             record.addField(label, rs.getObject(label));
     *         }
     *     }
     *     return record;
     * })) {
     *     stream.forEach(record -> processRecord(record));
     * }
     * }</pre>
     *
     * @param <T> the type of entities in the stream result
     * @param rowMapper The BiRowMapper receiving ResultSet and column labels. Must not be null.
     * @return A lazy-evaluated Stream of mapped objects
     * @throws IllegalArgumentException If rowMapper is null
     * @throws IllegalStateException if this query is closed
     * @see Jdbc.BiRowMapper
     */
    @SuppressWarnings("resource")
    @LazyEvaluation
    public <T> Stream<T> stream(final Jdbc.BiRowMapper<? extends T> rowMapper) throws IllegalArgumentException, IllegalStateException {
        // Will it cause confusion if it's called in transaction?

        checkArgNotNull(rowMapper, cs.rowMapper);
        assertNotClosed();

        final Supplier<ResultSet> supplier = createQuerySupplier();

        return Stream.just(supplier)
                .map(Supplier::get)
                .flatMap(rs -> JdbcUtil.<T> stream(rs, rowMapper).onClose(() -> JdbcUtil.closeQuietly(rs)))
                .onClose(this::closeAfterExecutionIfAllowed);
    }

    /**
     * Streams the rows in the first ResultSet, filtering with RowFilter and mapping with RowMapper.
     * Combines filtering and mapping in a lazy-evaluated stream for efficient processing.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Stream with filtering and mapping
     * try (Stream<Order> stream = preparedQuery.stream(
     *     rs -> rs.getBigDecimal("amount").compareTo(minAmount) >= 0,  // Filter
     *     rs -> new Order(rs.getLong("id"), rs.getBigDecimal("amount"))  // Mapper
     * )) {
     *     BigDecimal total = stream
     *         .map(Order::getAmount)
     *         .reduce(BigDecimal.ZERO, BigDecimal::add);
     * }
     * }</pre>
     *
     * @param <T> the type of entities in the stream result
     * @param rowFilter The filter to test each row. Must not be null.
     * @param rowMapper The mapper for rows that pass the filter. Must not be null.
     * @return A lazy-evaluated Stream of filtered and mapped objects
     * @throws IllegalArgumentException If rowFilter or rowMapper is null
     * @throws IllegalStateException if this query is closed
     * @see Jdbc.RowFilter
     * @see Jdbc.RowMapper
     */
    @SuppressWarnings("resource")
    @LazyEvaluation
    public <T> Stream<T> stream(final Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends T> rowMapper)
            throws IllegalArgumentException, IllegalStateException {
        // Will it cause confusion if it's called in transaction?

        checkArgNotNull(rowFilter, cs.rowFilter);
        checkArgNotNull(rowMapper, cs.rowMapper);
        assertNotClosed();

        final Supplier<ResultSet> supplier = createQuerySupplier();

        return Stream.just(supplier)
                .map(Supplier::get)
                .flatMap(rs -> JdbcUtil.<T> stream(rs, rowFilter, rowMapper).onClose(() -> JdbcUtil.closeQuietly(rs)))
                .onClose(this::closeAfterExecutionIfAllowed);
    }

    /**
     * Streams the rows in the first ResultSet, filtering with BiRowFilter and mapping with BiRowMapper.
     * Both filter and mapper receive ResultSet and column labels for maximum flexibility.
     * <p>
     * Note: The opened {@code Connection} and {@code Statement} will be held till {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     * </p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Advanced streaming with column-aware filtering and mapping
     * try (Stream<Report> stream = preparedQuery.stream(
     *     (rs, labels) -> labels.contains("published") && rs.getBoolean("published"),
     *     (rs, labels) -> {
     *         Report report = new Report();
     *         report.setId(rs.getLong("id"));
     *         // Only set optional fields if they exist
     *         if (labels.contains("author")) {
     *             report.setAuthor(rs.getString("author"));
     *         }
     *         return report;
     *     }
     * )) {
     *     stream.forEach(report -> publishReport(report));
     * }
     * }</pre>
     *
     * @param <T> the type of entities in the stream result
     * @param rowFilter The BiRowFilter to test each row. Must not be null.
     * @param rowMapper The BiRowMapper for filtered rows. Must not be null.
     * @return A lazy-evaluated Stream of filtered and mapped objects
     * @throws IllegalArgumentException If rowFilter or rowMapper is null
     * @throws IllegalStateException if this query is closed
     * @see Jdbc.BiRowFilter
     * @see Jdbc.BiRowMapper
     */
    @SuppressWarnings("resource")
    @LazyEvaluation
    public <T> Stream<T> stream(final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowMapper<? extends T> rowMapper)
            throws IllegalArgumentException, IllegalStateException {
        // Will it cause confusion if it's called in transaction?

        checkArgNotNull(rowFilter, cs.rowFilter);
        checkArgNotNull(rowMapper, cs.rowMapper);
        assertNotClosed();

        final Supplier<ResultSet> supplier = createQuerySupplier();

        return Stream.just(supplier)
                .map(Supplier::get)
                .flatMap(rs -> JdbcUtil.<T> stream(rs, rowFilter, rowMapper).onClose(() -> JdbcUtil.closeQuietly(rs)))
                .onClose(this::closeAfterExecutionIfAllowed);
    }

    //    /**
    //     * Streams all the {@code ResultSets}.
    //     * Usually, this method is used to retrieve multiple results from a stored procedure.
    //     *
    //     * <br />
    //     * lazy-execution, lazy-fetch.
    //     *
    //     * <br />
    //     * Note: The opened {@code Connection} and {@code Statement} will be held till {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
    //     *
    //     * @param <T> The type of the elements in the stream.
    //     * @param targetType The class of the type to be extracted from the {@code ResultSets}.
    //     * @return A {@code Stream} of {@code Stream<T, SQLException>} representing the rows in all {@code ResultSets}.
    //     * @throws IllegalArgumentException If the provided {@code targetType} is invalid.
    //     * @throws IllegalStateException If this is closed.
    //     */
    //    @SuppressWarnings("resource")
    //    public <T> Stream<Stream<T>> streamAllResultsets(final Class<? extends T> targetType) throws IllegalArgumentException, IllegalStateException {
    //        checkArgNotNull(targetType, s.targetType);
    //        assertNotClosed();
    //
    //        final Supplier<Boolean> supplier = createExecuteSupplier();
    //
    //        return Stream.just(supplier).flatMap(it -> JdbcUtil.<T> streamAllResultSets(stmt, targetType)).onClose(this::closeAfterExecutionIfAllowed);
    //
    //    }
    //
    //    /**
    //     * Streams all the {@code ResultSets}.
    //     * Usually, this method is used to retrieve multiple results from a stored procedure.
    //     *
    //     * <br />
    //     * lazy-execution, lazy-fetch.
    //     *
    //     * <br />
    //     * Note: The opened {@code Connection} and {@code Statement} will be held till {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
    //     *
    //     * @param <T> The type of the elements in the stream.
    //     * @param rowMapper The {@code RowMapper} to map rows of the {@code ResultSet} to the target type.
    //     * @return A {@code Stream} of {@code Stream<T, SQLException>} representing the rows in all {@code ResultSets}.
    //     * @throws IllegalArgumentException If the provided {@code RowMapper} is invalid.
    //     * @throws IllegalStateException If this is closed.
    //     */
    //    @SuppressWarnings("resource")
    //    public <T> Stream<Stream<T>> streamAllResultsets(final Jdbc.RowMapper<? extends T> rowMapper) throws IllegalArgumentException, IllegalStateException {
    //        checkArgNotNull(rowMapper, s.rowMapper);
    //        assertNotClosed();
    //
    //        final Supplier<Boolean> supplier = createExecuteSupplier();
    //
    //        return Stream.just(supplier).flatMap(it -> JdbcUtil.<T> streamAllResultSets(stmt, rowMapper)).onClose(this::closeAfterExecutionIfAllowed);
    //    }
    //
    //    /**
    //     * Streams all the {@code ResultSets}.
    //     * Usually, this method is used to retrieve multiple results from a stored procedure.
    //     *
    //     * <br />
    //     * lazy-execution, lazy-fetch.
    //     *
    //     * <br />
    //     * Note: The opened {@code Connection} and {@code Statement} will be held till {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
    //     *
    //     * @param <T> The type of the elements in the stream.
    //     * @param rowFilter The {@code RowFilter} to filter rows of the {@code ResultSet}.
    //     * @param rowMapper The {@code RowMapper} to map rows of the {@code ResultSet} to the target type.
    //     * @return A {@code Stream} of {@code Stream<T, SQLException>} representing the rows in all {@code ResultSets}.
    //     * @throws IllegalArgumentException If the provided {@code RowFilter} or {@code RowMapper} is invalid.
    //     * @throws IllegalStateException If this is closed.
    //     */
    //    @SuppressWarnings("resource")
    //    public <T> Stream<Stream<T>> streamAllResultsets(final Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends T> rowMapper)
    //            throws IllegalArgumentException, IllegalStateException {
    //        checkArgNotNull(rowFilter, s.rowFilter);
    //        checkArgNotNull(rowMapper, s.rowMapper);
    //        assertNotClosed();
    //
    //        final Supplier<Boolean> supplier = createExecuteSupplier();
    //
    //        return Stream.just(supplier).flatMap(it -> JdbcUtil.<T> streamAllResultSets(stmt, rowFilter, rowMapper)).onClose(this::closeAfterExecutionIfAllowed);
    //    }
    //
    //    /**
    //     * Streams all the {@code ResultSets}.
    //     * Usually, this method is used to retrieve multiple results from a stored procedure.
    //     *
    //     * <br />
    //     * lazy-execution, lazy-fetch.
    //     *
    //     * <br />
    //     * Note: The opened {@code Connection} and {@code Statement} will be held till {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
    //     *
    //     * @param <T> The type of the elements in the stream.
    //     * @param rowMapper The {@code BiRowMapper} to map rows of the {@code ResultSet} to the target type.
    //     * @return A {@code Stream} of {@code Stream<T, SQLException>} representing the rows in all {@code ResultSets}.
    //     * @throws IllegalArgumentException If the provided {@code rowMapper} is invalid.
    //     * @throws IllegalStateException If this is closed.
    //     */
    //    @SuppressWarnings("resource")
    //    public <T> Stream<Stream<T>> streamAllResultsets(final Jdbc.BiRowMapper<? extends T> rowMapper) throws IllegalArgumentException, IllegalStateException {
    //        checkArgNotNull(rowMapper, s.rowMapper);
    //        assertNotClosed();
    //
    //        final Supplier<Boolean> supplier = createExecuteSupplier();
    //
    //        return Stream.just(supplier).flatMap(it -> JdbcUtil.<T> streamAllResultSets(stmt, rowMapper)).onClose(this::closeAfterExecutionIfAllowed);
    //    }
    //
    //    /**
    //     * Streams all the {@code ResultSets}.
    //     * Usually, this method is used to retrieve multiple results from a stored procedure.
    //     *
    //     * <br />
    //     * lazy-execution, lazy-fetch.
    //     *
    //     * <br />
    //     * Note: The opened {@code Connection} and {@code Statement} will be held till {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
    //     *
    //     * @param <T> The type of the elements in the stream.
    //     * @param rowFilter The {@code BiRowFilter} to filter rows of the {@code ResultSet}.
    //     * @param rowMapper The {@code BiRowMapper} to map rows of the {@code ResultSet} to the target type.
    //     * @return A {@code Stream} of {@code Stream<T, SQLException>} representing the rows in all {@code ResultSets}.
    //     * @throws IllegalArgumentException If the provided {@code rowFilter} or {@code rowMapper} is invalid.
    //     * @throws IllegalStateException If this is closed.
    //     */
    //    @SuppressWarnings("resource")
    //    public <T> Stream<Stream<T>> streamAllResultsets(final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowMapper<? extends T> rowMapper)
    //            throws IllegalArgumentException, IllegalStateException {
    //        checkArgNotNull(rowFilter, s.rowFilter);
    //        checkArgNotNull(rowMapper, s.rowMapper);
    //        assertNotClosed();
    //
    //        final Supplier<Boolean> supplier = createExecuteSupplier();
    //
    //        return Stream.just(supplier).flatMap(it -> JdbcUtil.<T> streamAllResultSets(stmt, rowFilter, rowMapper)).onClose(this::closeAfterExecutionIfAllowed);
    //    }

    /**
     * Streams all ResultSets as Datasets from a stored procedure or multi-result query.
     * 
     * <p>This method is typically used when executing stored procedures that return multiple result sets.
     * Each ResultSet is converted to a Dataset for easy manipulation and processing.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Execute a stored procedure that returns multiple result sets
     * try (Stream<Dataset> resultSets = callableQuery.streamAllResultsets()) {
     *     resultSets.forEach(dataset -> {
     *         System.out.println("Result set with " + dataset.size() + " rows");
     *         dataset.forEach(row -> processRow(row));
     *     });
     * }
     * }</pre>
     *
     * @return A stream of Dataset objects, one for each ResultSet returned by the query
     * @throws IllegalStateException if this query is closed
     * @throws UncheckedSQLException If a database access error occurs
     * @see #queryAllResultsets()
     * @see Dataset
     */
    @Beta
    public Stream<Dataset> streamAllResultsets() {
        return streamAllResultsets(ResultExtractor.TO_DATA_SET);
    }

    /**
     * Streams all ResultSets using the specified ResultExtractor to process each ResultSet.
     * 
     * <p>This method is typically used when executing stored procedures that return multiple result sets.
     * Each ResultSet is processed by the provided ResultExtractor to produce a result of type R.</p>
     * 
     * <p><b>Important:</b> The ResultExtractor should not save or return the ResultSet reference,
     * as it will be automatically closed after processing.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Extract summary information from each result set
     * Stream<Summary> summaries = callableQuery.streamAllResultsets(
     *     ResultExtractor.toList(rs -> new Summary(
     *         rs.getString("name"),
     *         rs.getInt("count"),
     *         rs.getDouble("total")
     *     ))
     * );
     * }</pre>
     *
     * @param <R> the type of result extracted from each ResultSet
     * @param resultExtractor The extractor used to process each ResultSet and produce a result.
     *                        Must not be null. The ResultSet will be closed after extraction.
     * @return A stream of R extracted from all ResultSets returned by the executed procedure
     * @throws IllegalArgumentException If the provided resultExtractor is null
     * @throws IllegalStateException if this query is closed
     * @throws UncheckedSQLException If a database access error occurs
     * @see #queryAllResultsets(ResultExtractor)
     * @see ResultExtractor
     */
    @Beta
    @SuppressWarnings("resource")
    public <R> Stream<R> streamAllResultsets(final Jdbc.ResultExtractor<? extends R> resultExtractor) throws IllegalArgumentException, IllegalStateException {
        checkArgNotNull(resultExtractor, cs.resultExtractor);
        assertNotClosed();

        final Supplier<Boolean> supplier = createExecuteSupplier();

        return Stream.just(supplier)
                .flatMap(isResultSetSupplier -> Stream.of(JdbcUtil.iterateAllResultSets(stmt, isResultSetSupplier.get())))
                .mapE(rs -> JdbcUtil.<R> extractAndCloseResultSet(rs, resultExtractor))
                .onClose(this::closeAfterExecutionIfAllowed);
    }

    /**
     * Streams all ResultSets using the specified BiResultExtractor to process each ResultSet.
     * 
     * <p>This method is typically used when executing stored procedures that return multiple result sets.
     * Each ResultSet is processed by the provided BiResultExtractor which receives both the ResultSet
     * and column labels for flexible processing.</p>
     * 
     * <p><b>Important:</b> The BiResultExtractor should not save or return the ResultSet reference,
     * as it will be automatically closed after processing.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Extract data with column-aware processing
     * Stream<Report> reports = callableQuery.streamAllResultsets(
     *     (rs, columnLabels) -> {
     *         List<Report> list = new ArrayList<>();
     *         while (rs.next()) {
     *             Report report = new Report();
     *             // Process based on available columns
     *             if (columnLabels.contains("status")) {
     *                 report.setStatus(rs.getString("status"));
     *             }
     *             list.add(report);
     *         }
     *         return list;
     *     }
     * );
     * }</pre>
     *
     * @param <R> the type of result extracted from each ResultSet
     * @param resultExtractor The extractor used to process each ResultSet with column labels.
     *                        Must not be null. The ResultSet will be closed after extraction.
     * @return A stream of R extracted from all ResultSets returned by the executed procedure
     * @throws IllegalArgumentException If the provided resultExtractor is null
     * @throws IllegalStateException if this query is closed
     * @throws UncheckedSQLException If a database access error occurs
     * @see #queryAllResultsets(BiResultExtractor)
     * @see BiResultExtractor
     */
    @Beta
    @SuppressWarnings("resource")
    public <R> Stream<R> streamAllResultsets(final Jdbc.BiResultExtractor<? extends R> resultExtractor) throws IllegalArgumentException, IllegalStateException {
        checkArgNotNull(resultExtractor, cs.resultExtractor);
        assertNotClosed();

        final Supplier<Boolean> supplier = createExecuteSupplier();

        return Stream.just(supplier)
                .flatMap(isResultSetSupplier -> Stream.of(JdbcUtil.iterateAllResultSets(stmt, isResultSetSupplier.get())))
                .mapE(rs -> JdbcUtil.<R> extractAndCloseResultSet(rs, resultExtractor))
                .onClose(this::closeAfterExecutionIfAllowed);
    }

    private Supplier<ResultSet> createQuerySupplier() {
        return () -> {
            try {
                return executeQuery();
            } catch (final SQLException e) {
                throw new UncheckedSQLException(e);
            }
        };
    }

    private Supplier<Boolean> createExecuteSupplier() {
        return () -> {
            try {
                return JdbcUtil.execute(stmt);
            } catch (final SQLException e) {
                throw new UncheckedSQLException(e);
            }
        };
    }

    /**
     * Checks if there is at least one record found by executing the query.
     * 
     * <p>This method is optimized for existence checking. Unlike count operations,
     * it returns as soon as the first record is found, making it more efficient
     * for large result sets.</p>
     * 
     * <p><b>Note:</b> This method uses the actual query (e.g., {@code SELECT * FROM ...})
     * not a rewritten query like {@code SELECT 1 FROM ...} or {@code SELECT COUNT(*) FROM ...}.
     * The query stops processing after finding the first row.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Check if user exists
     * boolean userExists = JdbcUtil.prepareQuery(conn, "SELECT * FROM users WHERE email = ?")
     *     .setString(1, "john@example.com")
     *     .exists();
     *     
     * if (userExists) {
     *     // Process existing user
     * }
     * }</pre>
     *
     * @return {@code true} if there is at least one record found, {@code false} otherwise
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #notExists()
     * @see #count()
     */
    public boolean exists() throws IllegalStateException, SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Checks if there are no records found by executing the query.
     * 
     * <p>This is a convenience method that returns the opposite of {@link #exists()}.
     * It's provided because "not exists" is a common pattern in database queries
     * and {@code notExists()} is more readable than {@code !exists()}.</p>
     * 
     * <p><b>Why add notExists()?</b> While {@code !exists()} works, {@code notExists()}
     * better expresses the intent when checking for absence of records, similar to
     * SQL's {@code NOT EXISTS} clause.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Check if email is available for registration
     * boolean emailAvailable = JdbcUtil.prepareQuery(conn, "SELECT * FROM users WHERE email = ?")
     *     .setString(1, "newuser@example.com")
     *     .notExists();
     *     
     * if (emailAvailable) {
     *     // Proceed with registration
     * }
     * }</pre>
     *
     * @return {@code true} if there are no records found, {@code false} if at least one record exists
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #exists()
     */
    @Beta
    public boolean notExists() throws SQLException {
        return !exists();
    }

    /**
     * Executes the given RowConsumer if a record exists in the result set.
     * 
     * <p>This method combines existence checking with conditional processing.
     * If at least one row is found, the consumer is called with the first row's ResultSet.</p>
     * 
     * <p><b>Note:</b> Only the first row is processed even if multiple rows exist.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Process user if found
     * JdbcUtil.prepareQuery(conn, "SELECT * FROM users WHERE id = ?")
     *     .setLong(1, userId)
     *     .ifExists(rs -> {
     *         String name = rs.getString("name");
     *         String email = rs.getString("email");
     *         sendWelcomeEmail(name, email);
     *     });
     * }</pre>
     *
     * @param rowConsumer The consumer to process the first row if it exists. Must not be null.
     * @throws IllegalArgumentException If rowConsumer is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #ifExistsOrElse(RowConsumer, Throwables.Runnable)
     */
    public void ifExists(final Jdbc.RowConsumer rowConsumer) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowConsumer, cs.rowConsumer);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            if (rs.next()) {
                rowConsumer.accept(rs);
            }
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the given BiRowConsumer if a record exists in the result set.
     * 
     * <p>This method is similar to {@link #ifExists(RowConsumer)} but provides access
     * to column labels along with the ResultSet, enabling column-aware processing.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Process user with dynamic column handling
     * JdbcUtil.prepareQuery(conn, "SELECT * FROM users WHERE id = ?")
     *     .setLong(1, userId)
     *     .ifExists((rs, labels) -> {
     *         User user = new User();
     *         user.setId(rs.getLong("id"));
     *         
     *         // Only process columns that exist
     *         if (labels.contains("middle_name")) {
     *             user.setMiddleName(rs.getString("middle_name"));
     *         }
     *         processUser(user);
     *     });
     * }</pre>
     *
     * @param rowConsumer The consumer to process the first row with column labels if it exists. Must not be null.
     * @throws IllegalArgumentException If rowConsumer is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #ifExistsOrElse(BiRowConsumer, Throwables.Runnable)
     */
    public void ifExists(final Jdbc.BiRowConsumer rowConsumer) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowConsumer, cs.rowConsumer);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            if (rs.next()) {
                rowConsumer.accept(rs, JdbcUtil.getColumnLabelList(rs));
            }
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the given RowConsumer if a record exists, otherwise executes the orElseAction.
     * 
     * <p>This method provides a complete conditional execution pattern: process the first row
     * if it exists, or execute an alternative action if no rows are found.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Load user or create default
     * JdbcUtil.prepareQuery(conn, "SELECT * FROM users WHERE id = ?")
     *     .setLong(1, userId)
     *     .ifExistsOrElse(
     *         rs -> {
     *             // User found - load data
     *             currentUser = new User(
     *                 rs.getLong("id"),
     *                 rs.getString("name"),
     *                 rs.getString("email")
     *             );
     *         },
     *         () -> {
     *             // User not found - create default
     *             currentUser = User.createDefault(userId);
     *             saveUser(currentUser);
     *         }
     *     );
     * }</pre>
     *
     * @param rowConsumer The consumer to process the first row if it exists. Must not be null.
     * @param orElseAction The action to execute if no record exists. Must not be null.
     * @throws IllegalArgumentException If rowConsumer or orElseAction is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public void ifExistsOrElse(final Jdbc.RowConsumer rowConsumer, final Throwables.Runnable<SQLException> orElseAction)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowConsumer, cs.rowConsumer);
        checkArgNotNull(orElseAction, cs.orElseAction);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            if (rs.next()) {
                rowConsumer.accept(rs);
            } else {
                orElseAction.run();
            }
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the given BiRowConsumer if a record exists, otherwise executes the orElseAction.
     * 
     * <p>This method is similar to {@link #ifExistsOrElse(RowConsumer, Throwables.Runnable)} 
     * but provides access to column labels for column-aware processing.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Load configuration or use defaults
     * JdbcUtil.prepareQuery(conn, "SELECT * FROM config WHERE app_id = ?")
     *     .setString(1, appId)
     *     .ifExistsOrElse(
     *         (rs, labels) -> {
     *             // Load existing configuration
     *             config = new Config();
     *             config.setAppId(rs.getString("app_id"));
     *             
     *             // Handle optional columns
     *             if (labels.contains("custom_settings")) {
     *                 config.setCustomSettings(rs.getString("custom_settings"));
     *             }
     *         },
     *         () -> {
     *             // Create default configuration
     *             config = Config.createDefault(appId);
     *             insertDefaultConfig(config);
     *         }
     *     );
     * }</pre>
     *
     * @param rowConsumer The consumer to process the first row with column labels if it exists. Must not be null.
     * @param orElseAction The action to execute if no record exists. Must not be null.
     * @throws IllegalArgumentException If rowConsumer or orElseAction is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public void ifExistsOrElse(final Jdbc.BiRowConsumer rowConsumer, final Throwables.Runnable<SQLException> orElseAction)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowConsumer, cs.rowConsumer);
        checkArgNotNull(orElseAction, cs.orElseAction);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            if (rs.next()) {
                rowConsumer.accept(rs, JdbcUtil.getColumnLabelList(rs));
            } else {
                orElseAction.run();
            }
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Counts all records returned by the query by iterating through the entire result set.
     * 
     * <p><b>Warning:</b> This method is deprecated because it can be inefficient and may be misused.
     * It retrieves and counts ALL rows from the database, which can be very slow for large result sets.
     * Consider using {@code SELECT COUNT(*) FROM ...} queries instead for better performance.</p>
     * 
     * <p><b>Alternative Approach:</b></p>
     * <pre>{@code
     * // Better: Use COUNT(*) query
     * int count = JdbcUtil.prepareQuery(conn, "SELECT COUNT(*) FROM users WHERE status = ?")
     *     .setString(1, "active")
     *     .queryForInt()
     *     .orElse(0);
     *     
     * // Instead of:
     * int count = JdbcUtil.prepareQuery(conn, "SELECT * FROM users WHERE status = ?")
     *     .setString(1, "active")
     *     .count();  // Inefficient!
     * }</pre>
     *
     * @return The total number of records in the result set
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #queryForInt()
     * @see #count(RowFilter)
     * @deprecated This method is inefficient for large result sets. Use {@code SELECT COUNT(*)} queries instead.
     */
    @Deprecated
    public int count() throws IllegalStateException, SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            int cnt = 0;

            while (rs.next()) {
                cnt++;
            }

            return cnt;
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Counts the number of rows that match the given row filter.
     * 
     * <p>This method iterates through all rows in the result set and counts only those
     * that satisfy the provided filter condition. While more flexible than {@link #count()},
     * it still requires fetching all rows and can be inefficient for large result sets.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Count premium users from a user query
     * int premiumCount = JdbcUtil.prepareQuery(conn, "SELECT * FROM users WHERE created_date > ?")
     *     .setDate(1, lastMonth)
     *     .count(rs -> rs.getBoolean("is_premium") && rs.getDouble("balance") > 0);
     *     
     * System.out.println("Premium users with positive balance: " + premiumCount);
     * }</pre>
     *
     * @param rowFilter The filter to apply to each row. Only rows where this returns {@code true} are counted. Must not be null.
     * @return The count of rows that match the filter
     * @throws IllegalArgumentException If rowFilter is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #count(BiRowFilter)
     * @see #anyMatch(RowFilter)
     */
    @Beta
    public int count(final Jdbc.RowFilter rowFilter) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, cs.rowFilter);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            int cnt = 0;

            while (rs.next()) {
                if (rowFilter.test(rs)) {
                    cnt++;
                }
            }

            return cnt;
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Counts the number of rows that match the given BiRowFilter.
     * 
     * <p>This method provides column-aware counting, where the filter has access to both
     * the ResultSet and the column labels. This allows for dynamic filtering based on
     * which columns are present in the result set.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Count rows with specific conditions based on available columns
     * int validCount = JdbcUtil.prepareQuery(conn, "SELECT * FROM products")
     *     .count((rs, labels) -> {
     *         // Always check required fields
     *         if (rs.getDouble("price") <= 0) return false;
     *         
     *         // Additional check only if column exists
     *         if (labels.contains("discontinued")) {
     *             return !rs.getBoolean("discontinued");
     *         }
     *         return true;
     *     });
     * }</pre>
     *
     * @param rowFilter The filter to apply to each row with column labels. Must not be null.
     * @return The count of rows that match the filter
     * @throws IllegalArgumentException If rowFilter is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #count(RowFilter)
     */
    public int count(final Jdbc.BiRowFilter rowFilter) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, cs.rowFilter);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);
            int cnt = 0;

            while (rs.next()) {
                if (rowFilter.test(rs, columnLabels)) {
                    cnt++;
                }
            }

            return cnt;
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Checks if any row in the result set matches the given row filter.
     * 
     * <p>This method short-circuits and returns {@code true} as soon as a matching row is found,
     * making it efficient for checking existence of records matching specific criteria.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Check if any order is pending
     * boolean hasPendingOrders = JdbcUtil.prepareQuery(conn, 
     *         "SELECT * FROM orders WHERE customer_id = ? AND date >= ?")
     *     .setLong(1, customerId)
     *     .setDate(2, startDate)
     *     .anyMatch(rs -> "PENDING".equals(rs.getString("status")));
     *     
     * if (hasPendingOrders) {
     *     notifyCustomer("You have pending orders");
     * }
     * }</pre>
     *
     * @param rowFilter The filter to test each row. Must not be null.
     * @return {@code true} if any row matches the filter, {@code false} if no rows match or result set is empty
     * @throws IllegalArgumentException If rowFilter is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #allMatch(RowFilter)
     * @see #noneMatch(RowFilter)
     */
    public boolean anyMatch(final Jdbc.RowFilter rowFilter) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, cs.rowFilter);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            while (rs.next()) {
                if (rowFilter.test(rs)) {
                    return true;
                }
            }

            return false;
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Checks if any row in the result set matches the given BiRowFilter.
     * 
     * <p>This method provides column-aware matching with short-circuit evaluation.
     * It returns {@code true} as soon as a matching row is found.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Check if any product has invalid data considering available columns
     * boolean hasInvalidProducts = JdbcUtil.prepareQuery(conn, "SELECT * FROM products")
     *     .anyMatch((rs, labels) -> {
     *         // Check price
     *         if (rs.getDouble("price") < 0) return true;
     *         
     *         // Check optional validation
     *         if (labels.contains("min_quantity") && rs.getInt("min_quantity") < 0) {
     *             return true;
     *         }
     *         
     *         return false;
     *     });
     * }</pre>
     *
     * @param rowFilter The filter to test each row with column labels. Must not be null.
     * @return {@code true} if any row matches the filter, {@code false} if no rows match or result set is empty
     * @throws IllegalArgumentException If rowFilter is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #anyMatch(RowFilter)
     */
    public boolean anyMatch(final Jdbc.BiRowFilter rowFilter) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, cs.rowFilter);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

            while (rs.next()) {
                if (rowFilter.test(rs, columnLabels)) {
                    return true;
                }
            }

            return false;
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Checks if all rows in the result set match the given RowFilter.
     * 
     * <p>This method returns {@code true} only if every row in the result set satisfies
     * the filter condition. It short-circuits and returns {@code false} as soon as a
     * non-matching row is found. An empty result set returns {@code true}.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Verify all items in order are in stock
     * boolean allInStock = JdbcUtil.prepareQuery(conn, 
     *         "SELECT * FROM order_items WHERE order_id = ?")
     *     .setLong(1, orderId)
     *     .allMatch(rs -> rs.getInt("quantity") <= rs.getInt("stock_available"));
     *     
     * if (allInStock) {
     *     processOrder(orderId);
     * } else {
     *     notifyOutOfStock(orderId);
     * }
     * }</pre>
     *
     * @param rowFilter The filter to test each row. Must not be null.
     * @return {@code true} if all rows match the filter or result set is empty, {@code false} otherwise
     * @throws IllegalArgumentException If rowFilter is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #anyMatch(RowFilter)
     * @see #noneMatch(RowFilter)
     */
    public boolean allMatch(final Jdbc.RowFilter rowFilter) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, cs.rowFilter);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            while (rs.next()) {
                if (!rowFilter.test(rs)) {
                    return false;
                }
            }

            return true;
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Checks if all rows in the result set match the given BiRowFilter.
     * 
     * <p>This method provides column-aware validation for all rows. It returns {@code true}
     * only if every row satisfies the filter condition, with access to column labels
     * for dynamic validation.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Validate all employee records have required fields
     * boolean allValid = JdbcUtil.prepareQuery(conn, "SELECT * FROM employees WHERE dept_id = ?")
     *     .setInt(1, deptId)
     *     .allMatch((rs, labels) -> {
     *         // Required fields
     *         if (rs.getString("email") == null) return false;
     *         if (rs.getDouble("salary") <= 0) return false;
     *         
     *         // Optional validation if column exists
     *         if (labels.contains("hire_date") && rs.getDate("hire_date") == null) {
     *             return false;
     *         }
     *         
     *         return true;
     *     });
     * }</pre>
     *
     * @param rowFilter The filter to test each row with column labels. Must not be null.
     * @return {@code true} if all rows match the filter or result set is empty, {@code false} otherwise
     * @throws IllegalArgumentException If rowFilter is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #allMatch(RowFilter)
     */
    public boolean allMatch(final Jdbc.BiRowFilter rowFilter) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, cs.rowFilter);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

            while (rs.next()) {
                if (!rowFilter.test(rs, columnLabels)) {
                    return false;
                }
            }

            return true;
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Checks if no rows in the result set match the given RowFilter.
     * 
     * <p>This is a convenience method that returns the opposite of {@link #anyMatch(RowFilter)}.
     * It returns {@code true} only if no row satisfies the filter condition.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Ensure no orders exceed credit limit
     * boolean withinCreditLimit = JdbcUtil.prepareQuery(conn, 
     *         "SELECT * FROM orders WHERE customer_id = ? AND status = 'PENDING'")
     *     .setLong(1, customerId)
     *     .noneMatch(rs -> rs.getDouble("total_amount") > creditLimit);
     *     
     * if (withinCreditLimit) {
     *     approveAllPendingOrders(customerId);
     * }
     * }</pre>
     *
     * @param rowFilter The filter to test each row. Must not be null.
     * @return {@code true} if no rows match the filter or result set is empty, {@code false} if any row matches
     * @throws IllegalArgumentException If rowFilter is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #anyMatch(RowFilter)
     * @see #allMatch(RowFilter)
     */
    public boolean noneMatch(final Jdbc.RowFilter rowFilter) throws SQLException {
        return !anyMatch(rowFilter);
    }

    /**
     * Checks if no rows in the result set match the given BiRowFilter.
     * 
     * <p>This is a convenience method that returns the opposite of {@link #anyMatch(BiRowFilter)}.
     * It provides column-aware filtering and returns {@code true} only if no row satisfies
     * the filter condition.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Ensure no products have conflicting data
     * boolean noConflicts = JdbcUtil.prepareQuery(conn, "SELECT * FROM products WHERE category_id = ?")
     *     .setInt(1, categoryId)
     *     .noneMatch((rs, labels) -> {
     *         // Check for conflicts based on available columns
     *         if (labels.contains("special_price") && labels.contains("discount_percent")) {
     *             return rs.getDouble("special_price") > 0 && rs.getDouble("discount_percent") > 0;
     *         }
     *         return false;
     *     });
     * }</pre>
     *
     * @param rowFilter The filter to test each row with column labels. Must not be null.
     * @return {@code true} if no rows match the filter or result set is empty, {@code false} if any row matches
     * @throws IllegalArgumentException If rowFilter is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #anyMatch(BiRowFilter)
     */
    public boolean noneMatch(final Jdbc.BiRowFilter rowFilter) throws SQLException {
        return !anyMatch(rowFilter);
    }

    /**
     * Iterates over each row in the result set and applies the given RowConsumer.
     * 
     * <p>This method processes all rows in the result set sequentially, applying the
     * provided consumer to each row. Unlike stream operations, this executes immediately
     * and processes all rows before returning.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Process all active users
     * JdbcUtil.prepareQuery(conn, "SELECT * FROM users WHERE active = true")
     *     .forEach(rs -> {
     *         String email = rs.getString("email");
     *         String name = rs.getString("name");
     *         sendNewsletter(email, name);
     *     });
     *     
     * // Export data to file
     * try (PrintWriter writer = new PrintWriter("users.csv")) {
     *     preparedQuery.forEach(rs -> {
     *         writer.println(String.format("%d,%s,%s",
     *             rs.getLong("id"),
     *             rs.getString("name"),
     *             rs.getString("email")
     *         ));
     *     });
     * }
     * }</pre>
     *
     * @param rowConsumer The consumer to apply to each row. Must not be null.
     * @throws IllegalArgumentException If rowConsumer is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #forEach(RowFilter, RowConsumer)
     * @see #forEach(BiRowConsumer)
     */
    public void forEach(final Jdbc.RowConsumer rowConsumer) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowConsumer, cs.rowConsumer);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            while (rs.next()) {
                rowConsumer.accept(rs);
            }

        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Iterates over each row that matches the filter and applies the given RowConsumer.
     * 
     * <p>This method combines filtering and processing, only applying the consumer to
     * rows that satisfy the filter condition. This is more efficient than filtering
     * after retrieval for large result sets.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Process only premium users with high balance
     * JdbcUtil.prepareQuery(conn, "SELECT * FROM users")
     *     .forEach(
     *         rs -> rs.getBoolean("is_premium") && rs.getDouble("balance") > 1000,
     *         rs -> {
     *             String email = rs.getString("email");
     *             sendSpecialOffer(email);
     *             logPremiumUser(rs.getLong("id"));
     *         }
     *     );
     * }</pre>
     *
     * @param rowFilter The filter to apply to each row. Only matching rows are processed. Must not be null.
     * @param rowConsumer The consumer to apply to each filtered row. Must not be null.
     * @throws IllegalArgumentException If rowFilter or rowConsumer is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #forEach(RowConsumer)
     */
    public void forEach(final Jdbc.RowFilter rowFilter, final Jdbc.RowConsumer rowConsumer)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, cs.rowFilter);
        checkArgNotNull(rowConsumer, cs.rowConsumer);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {

            while (rs.next()) {
                if (rowFilter.test(rs)) {
                    rowConsumer.accept(rs);
                }
            }
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Iterates over each row in the result set and applies the given BiRowConsumer.
     * 
     * <p>This method provides column-aware processing, passing both the ResultSet and
     * column labels to the consumer. This enables dynamic processing based on the
     * actual columns present in the result set.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Process rows with dynamic column handling
     * JdbcUtil.prepareQuery(conn, "SELECT * FROM products")
     *     .forEach((rs, labels) -> {
     *         Product product = new Product();
     *         product.setId(rs.getLong("id"));
     *         product.setName(rs.getString("name"));
     *         
     *         // Handle optional columns
     *         if (labels.contains("description")) {
     *             product.setDescription(rs.getString("description"));
     *         }
     *         if (labels.contains("category_name")) {
     *             product.setCategoryName(rs.getString("category_name"));
     *         }
     *         
     *         processProduct(product);
     *     });
     * }</pre>
     *
     * @param rowConsumer The consumer to apply to each row with column labels. Must not be null.
     * @throws IllegalArgumentException If rowConsumer is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #forEach(BiRowFilter, BiRowConsumer)
     */
    public void forEach(final Jdbc.BiRowConsumer rowConsumer) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowConsumer, cs.rowConsumer);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

            while (rs.next()) {
                rowConsumer.accept(rs, columnLabels);
            }

        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Iterates over each row that matches the filter and applies the given BiRowConsumer.
     * 
     * <p>This method combines column-aware filtering and processing. Both the filter
     * and consumer receive the ResultSet and column labels, enabling sophisticated
     * conditional processing based on available columns.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Process valid products with dynamic field handling
     * JdbcUtil.prepareQuery(conn, "SELECT p.*, c.name as category_name FROM products p " +
     *                             "LEFT JOIN categories c ON p.category_id = c.id")
     *     .forEach(
     *         (rs, labels) -> {
     *             // Filter: only active products with category
     *             return rs.getBoolean("active") && 
     *                    labels.contains("category_name") && 
     *                    rs.getString("category_name") != null;
     *         },
     *         (rs, labels) -> {
     *             // Process filtered rows
     *             ProductReport report = new ProductReport();
     *             report.setProductId(rs.getLong("id"));
     *             report.setProductName(rs.getString("name"));
     *             report.setCategoryName(rs.getString("category_name"));
     *             
     *             // Add optional data if available
     *             if (labels.contains("last_updated")) {
     *                 report.setLastUpdated(rs.getTimestamp("last_updated"));
     *             }
     *             
     *             generateReport(report);
     *         }
     *     );
     * }</pre>
     *
     * @param rowFilter The filter to apply to each row with column labels. Must not be null.
     * @param rowConsumer The consumer to apply to each filtered row with column labels. Must not be null.
     * @throws IllegalArgumentException If rowFilter or rowConsumer is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     */
    public void forEach(final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowConsumer rowConsumer)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, cs.rowFilter);
        checkArgNotNull(rowConsumer, cs.rowConsumer);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

            while (rs.next()) {
                if (rowFilter.test(rs, columnLabels)) {
                    rowConsumer.accept(rs, columnLabels);
                }
            }

        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the query and returns a ResultSet.
     * 
     * <p>This protected method is used internally by other query methods. It sets
     * the fetch direction to FORWARD if not already set, then executes the query.</p>
     *
     * @return The ResultSet from the executed query
     * @throws SQLException if a database access error occurs
     */
    protected ResultSet executeQuery() throws SQLException {
        if (!isFetchDirectionSet) {
            stmt.setFetchDirection(ResultSet.FETCH_FORWARD);
        }

        return JdbcUtil.executeQuery(stmt);
    }

    /**
     * Iterates over each row and applies the given Consumer to a DisposableObjArray.
     * 
     * <p>This method provides a lightweight way to process rows using disposable arrays
     * that are automatically recycled. The DisposableObjArray contains all column values
     * for the current row.</p>
     * 
     * <p><b>Note:</b> The DisposableObjArray is only valid within the consumer execution.
     * Do not store references to it or its contents for later use.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Process rows with disposable arrays
     * preparedQuery.foreach(row -> {
     *     Long id = (Long) row.get(0);
     *     String name = (String) row.get(1);
     *     Double salary = (Double) row.get(2);
     *     
     *     if (salary > 50000) {
     *         System.out.println("High earner: " + name);
     *     }
     * });
     * }</pre>
     *
     * @param rowConsumer The consumer to apply to each row's DisposableObjArray. Must not be null.
     * @throws IllegalArgumentException If rowConsumer is null
     * @throws SQLException if a database access error occurs
     * @see RowConsumer#oneOff(Consumer)
     * @see #foreach(Class, Consumer)
     */
    @Beta
    public void foreach(final Consumer<DisposableObjArray> rowConsumer) throws SQLException { //NOSONAR
        checkArgNotNull(rowConsumer, cs.rowConsumer);

        forEach(Jdbc.RowConsumer.oneOff(rowConsumer));
    }

    /**
     * Iterates over each row and applies the given Consumer to a DisposableObjArray,
     * using the specified entity class to guide column retrieval.
     * 
     * <p>This method uses the field types defined in the entity class to properly
     * retrieve values from the ResultSet, ensuring type safety and proper conversion.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Define entity class
     * public class User {
     *     private Long id;
     *     private String name;
     *     private LocalDate birthDate;
     *     private Boolean active;
     *     // getters/setters...
     * }
     * 
     * // Process with type-guided retrieval
     * preparedQuery.foreach(User.class, row -> {
     *     Long id = (Long) row.get(0);      // Retrieved as Long
     *     String name = (String) row.get(1); // Retrieved as String
     *     LocalDate birthDate = (LocalDate) row.get(2); // Retrieved as LocalDate
     *     Boolean active = (Boolean) row.get(3); // Retrieved as Boolean
     *     
     *     if (active) {
     *         sendBirthdayGreeting(id, name, birthDate);
     *     }
     * });
     * }</pre>
     *
     * @param entityClass The class used to determine proper types for column value retrieval. Must not be null.
     * @param rowConsumer The consumer to apply to each row's DisposableObjArray. Must not be null.
     * @throws IllegalArgumentException If entityClass or rowConsumer is null
     * @throws SQLException if a database access error occurs
     * @see RowConsumer#oneOff(Class, Consumer)
     */
    @Beta
    public void foreach(final Class<?> entityClass, final Consumer<DisposableObjArray> rowConsumer) throws SQLException { //NOSONAR
        checkArgNotNull(rowConsumer, cs.rowConsumer);

        forEach(Jdbc.RowConsumer.oneOff(entityClass, rowConsumer));
    }

    /**
     * Executes an INSERT statement and retrieves the auto-generated key.
     * 
     * <p>This method executes the insert and attempts to retrieve the generated key
     * (typically an auto-increment ID). If no key is generated or the key is a
     * default value (null, 0, etc.), an empty Optional is returned.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Insert new user and get generated ID
     * Optional<Long> userId = JdbcUtil.prepareQuery(conn, 
     *         "INSERT INTO users (name, email) VALUES (?, ?)")
     *     .setString(1, "John Doe")
     *     .setString(2, "john@example.com")
     *     .insert();
     *     
     * userId.ifPresent(id -> {
     *     System.out.println("Created user with ID: " + id);
     * });
     * }</pre>
     *
     * @param <ID> the type of the auto-generated key (typically Long or Integer)
     * @return An Optional containing the generated key if available, otherwise empty
     * @throws SQLException if a database access error occurs
     * @see #insert(RowMapper)
     * @see #batchInsert()
     */
    public <ID> Optional<ID> insert() throws SQLException {
        return insert((Jdbc.RowMapper<ID>) JdbcUtil.SINGLE_GENERATED_KEY_EXTRACTOR);
    }

    /**
     * Executes an INSERT statement and retrieves the auto-generated key using a custom extractor.
     * 
     * <p>This method allows custom extraction of generated keys, useful when dealing with
     * composite keys or non-standard key generation. The method executes the INSERT statement
     * and then applies the provided extractor to the generated keys ResultSet.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Insert with custom key extraction
     * Optional<String> generatedCode = JdbcUtil.prepareQuery(conn,
     *         "INSERT INTO products (name, price) VALUES (?, ?)")
     *     .setString(1, "Widget")
     *     .setDouble(2, 19.99)
     *     .insert(rs -> rs.getString("product_code")); // Custom column
     *     
     * // Insert with composite key
     * Optional<CompositeKey> key = preparedQuery.insert(rs -> 
     *     new CompositeKey(rs.getLong("id"), rs.getString("code"))
     * );
     * }</pre>
     *
     * @param <ID> the type of the auto-generated key (typically Long or Integer)
     * @param autoGeneratedKeyExtractor The function to extract the key from the generated keys ResultSet. 
     *                                  The function receives the ResultSet positioned at the generated key row.
     *                                  Must not be null.
     * @return An Optional containing the generated key if available, otherwise empty.
     *         Empty is returned if no key was generated or if the extractor returns null.
     * @throws IllegalArgumentException If autoGeneratedKeyExtractor is null
     * @throws SQLException if a database access error occurs or the statement fails to execute
     * @see #insert(BiRowMapper)
     * @see #insert()
     */
    public <ID> Optional<ID> insert(final Jdbc.RowMapper<? extends ID> autoGeneratedKeyExtractor) throws SQLException {
        return insert(autoGeneratedKeyExtractor, JdbcUtil.defaultIdTester);
    }

    /**
     * Executes an INSERT statement and retrieves the auto-generated key using a bi-row mapper.
     * 
     * <p>This method provides access to both the ResultSet and column labels when extracting
     * generated keys, offering more flexibility than the standard row mapper.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<Map<String, Object>> keyMap = preparedQuery.insert((rs, columnLabels) -> {
     *     Map<String, Object> map = new HashMap<>();
     *     for (String label : columnLabels) {
     *         map.put(label, rs.getObject(label));
     *     }
     *     return map;
     * });
     * }</pre>
     *
     * @param <ID> the type of the auto-generated key (typically Long or Integer)
     * @param autoGeneratedKeyExtractor The extractor that receives both ResultSet and column labels.
     *                                  Must not be null.
     * @return An Optional containing the generated key if it exists, otherwise an empty Optional
     * @throws IllegalArgumentException If autoGeneratedKeyExtractor is null
     * @throws SQLException if a database access error occurs
     * @see #insert(RowMapper)
     */
    public <ID> Optional<ID> insert(final Jdbc.BiRowMapper<? extends ID> autoGeneratedKeyExtractor) throws SQLException {
        return insert(autoGeneratedKeyExtractor, JdbcUtil.defaultIdTester);
    }

    /**
     * Executes an INSERT statement and retrieves the auto-generated key with custom validation.
     * 
     * <p>This internal method provides the core implementation for insert operations with
     * generated key retrieval. It allows specification of a custom predicate to determine
     * whether a generated key is valid (non-default).</p>
     *
     * @param <ID> the type of the auto-generated key (typically Long or Integer)
     * @param autoGeneratedKeyExtractor The extractor to retrieve the auto-generated key
     * @param isDefaultIdTester The predicate to test if the generated key is a default/invalid value
     * @return An Optional containing the generated key if it exists and is valid, otherwise an empty Optional
     * @throws SQLException if a database access error occurs
     */
    <ID> Optional<ID> insert(final Jdbc.RowMapper<? extends ID> autoGeneratedKeyExtractor, final Predicate<Object> isDefaultIdTester) throws SQLException {
        checkArgNotNull(autoGeneratedKeyExtractor, cs.autoGeneratedKeyExtractor);
        checkArgNotNull(isDefaultIdTester, cs.isDefaultIdTester);
        assertNotClosed();

        try {
            JdbcUtil.executeUpdate(stmt);

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                final ID id = rs.next() ? autoGeneratedKeyExtractor.apply(rs) : null;
                return id == null || isDefaultIdTester.test(id) ? Optional.empty() : Optional.of(id);
            }
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes an INSERT statement and retrieves the auto-generated key with custom validation using a bi-row mapper.
     * 
     * <p>This internal method provides the core implementation for insert operations with
     * generated key retrieval using a bi-row mapper that has access to column labels.</p>
     *
     * @param <ID> the type of the auto-generated key (typically Long or Integer)
     * @param autoGeneratedKeyExtractor The extractor that receives both ResultSet and column labels
     * @param isDefaultIdTester The predicate to test if the generated key is a default/invalid value
     * @return An Optional containing the generated key if it exists and is valid, otherwise an empty Optional
     * @throws SQLException if a database access error occurs
     */
    <ID> Optional<ID> insert(final Jdbc.BiRowMapper<? extends ID> autoGeneratedKeyExtractor, final Predicate<Object> isDefaultIdTester) throws SQLException {
        checkArgNotNull(autoGeneratedKeyExtractor, cs.autoGeneratedKeyExtractor);
        checkArgNotNull(isDefaultIdTester, cs.isDefaultIdTester);
        assertNotClosed();

        try {
            JdbcUtil.executeUpdate(stmt);

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);
                    final ID id = autoGeneratedKeyExtractor.apply(rs, columnLabels);
                    return isDefaultIdTester.test(id) ? Optional.empty() : Optional.of(id);
                } else {
                    return Optional.empty();
                }
            }
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes a batch INSERT statement and retrieves all generated keys as Long values.
     * 
     * <p>This method uses the default single-column generated key extractor that assumes
     * the generated keys are numeric (Long) values in the first column.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Long> generatedIds = JdbcUtil.prepareQuery(conn,
     *         "INSERT INTO users (name, email) VALUES (?, ?)")
     *     .addBatch("John", "john@example.com")
     *     .addBatch("Jane", "jane@example.com")
     *     .addBatch("Bob", "bob@example.com")
     *     .batchInsert();
     * }</pre>
     *
     * @param <ID> the type of the auto-generated keys (inferred as Long by default)
     * @return A list of generated keys. Empty list if no keys were generated.
     * @throws SQLException if a database access error occurs
     * @see #batchInsert(RowMapper)
     */
    public <ID> List<ID> batchInsert() throws SQLException {
        return batchInsert((Jdbc.RowMapper<ID>) JdbcUtil.SINGLE_GENERATED_KEY_EXTRACTOR);
    }

    /**
     * Executes a batch INSERT statement and retrieves all generated keys using a custom extractor.
     * 
     * <p>This method allows custom extraction of generated keys from batch insert operations,
     * useful when dealing with non-numeric keys or composite keys.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<UUID> generatedUUIDs = preparedQuery.batchInsert(rs -> 
     *     UUID.fromString(rs.getString("id"))
     * );
     * }</pre>
     *
     * @param <ID> the type of the auto-generated keys
     * @param autoGeneratedKeyExtractor The extractor to retrieve the auto-generated keys from each row.
     *                                  Must not be null.
     * @return A list of generated keys. Empty list if no keys were generated.
     * @throws IllegalArgumentException If autoGeneratedKeyExtractor is null
     * @throws SQLException if a database access error occurs
     * @see #batchInsert()
     * @see #batchInsert(BiRowMapper)
     */
    public <ID> List<ID> batchInsert(final Jdbc.RowMapper<? extends ID> autoGeneratedKeyExtractor) throws SQLException {
        return batchInsert(autoGeneratedKeyExtractor, JdbcUtil.defaultIdTester);
    }

    /**
     * Executes a batch INSERT statement and retrieves all generated keys using a bi-row mapper.
     * 
     * <p>This method provides access to both the ResultSet and column labels when extracting
     * generated keys from batch operations.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Map<String, Object>> keyMaps = preparedQuery.batchInsert((rs, labels) -> {
     *     Map<String, Object> keyMap = new HashMap<>();
     *     for (String label : labels) {
     *         keyMap.put(label, rs.getObject(label));
     *     }
     *     return keyMap;
     * });
     * }</pre>
     *
     * @param <ID> the type of the auto-generated keys
     * @param autoGeneratedKeyExtractor The extractor that receives both ResultSet and column labels.
     *                                  Must not be null.
     * @return A list of generated keys. Empty list if no keys were generated.
     * @throws IllegalArgumentException If autoGeneratedKeyExtractor is null
     * @throws SQLException if a database access error occurs
     * @see #batchInsert(RowMapper)
     */
    public <ID> List<ID> batchInsert(final Jdbc.BiRowMapper<? extends ID> autoGeneratedKeyExtractor) throws SQLException {
        return batchInsert(autoGeneratedKeyExtractor, JdbcUtil.defaultIdTester);
    }

    /**
     * Executes a batch INSERT statement and retrieves generated keys with custom validation.
     * 
     * <p>This internal method provides the core implementation for batch insert operations
     * with generated key retrieval and validation.</p>
     *
     * @param <ID> the type of the auto-generated keys
     * @param autoGeneratedKeyExtractor The extractor to retrieve the auto-generated keys
     * @param isDefaultIdTester A predicate to test if the generated key is a default/invalid value
     * @return A list of generated keys, excluding any that fail the validation test
     * @throws SQLException if a database access error occurs
     */
    <ID> List<ID> batchInsert(final Jdbc.RowMapper<? extends ID> autoGeneratedKeyExtractor, final Predicate<Object> isDefaultIdTester) throws SQLException {
        checkArgNotNull(autoGeneratedKeyExtractor, cs.autoGeneratedKeyExtractor);
        checkArgNotNull(isDefaultIdTester, cs.isDefaultIdTester);
        assertNotClosed();

        try {
            JdbcUtil.executeBatch(stmt);

            final List<ID> ids = new ArrayList<>();

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                while (rs.next()) {
                    ids.add(autoGeneratedKeyExtractor.apply(rs));
                }
            }

            if (JdbcUtil.isAllNullIds(ids, isDefaultIdTester)) {
                return new ArrayList<>();
            }

            return ids;
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes a batch INSERT statement and retrieves generated keys with custom validation using a bi-row mapper.
     * 
     * <p>This internal method provides the core implementation for batch insert operations
     * with generated key retrieval using a bi-row mapper that has access to column labels.</p>
     *
     * @param <ID> the type of the auto-generated keys
     * @param autoGeneratedKeyExtractor The extractor that receives both ResultSet and column labels
     * @param isDefaultIdTester A predicate to test if the generated key is a default/invalid value
     * @return A list of generated keys, excluding any that fail the validation test
     * @throws SQLException if a database access error occurs
     */
    <ID> List<ID> batchInsert(final Jdbc.BiRowMapper<? extends ID> autoGeneratedKeyExtractor, final Predicate<Object> isDefaultIdTester) throws SQLException {
        checkArgNotNull(autoGeneratedKeyExtractor, cs.autoGeneratedKeyExtractor);
        checkArgNotNull(isDefaultIdTester, cs.isDefaultIdTester);
        assertNotClosed();

        try {
            JdbcUtil.executeBatch(stmt);

            final List<ID> ids = new ArrayList<>();

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                while (rs.next()) {
                    ids.add(autoGeneratedKeyExtractor.apply(rs, columnLabels));
                }
            }

            if (JdbcUtil.isAllNullIds(ids, isDefaultIdTester)) {
                return new ArrayList<>();
            }

            return ids;
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes an UPDATE, INSERT, or DELETE statement and returns the number of affected rows.
     * 
     * <p>This method executes the prepared statement and returns the update count,
     * which represents the number of rows affected by the SQL statement.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * int rowsUpdated = JdbcUtil.prepareQuery(conn,
     *         "UPDATE users SET status = 'ACTIVE' WHERE last_login > ?")
     *     .setDate(1, lastWeek)
     *     .update();
     *     
     * int rowsDeleted = JdbcUtil.prepareQuery(conn,
     *         "DELETE FROM logs WHERE created_date < ?")
     *     .setDate(1, oneMonthAgo)
     *     .update();
     * }</pre>
     *
     * @return The number of rows affected by the update. Returns 0 if no rows were affected.
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs or the SQL statement is not a DML statement
     * @see #largeUpdate()
     * @see #batchUpdate()
     */
    public int update() throws IllegalStateException, SQLException {
        assertNotClosed();

        try {
            return JdbcUtil.executeUpdate(stmt);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes an UPDATE/INSERT/DELETE statement and returns both the affected row count and generated keys.
     * 
     * <p>This method is useful when you need to perform an update operation that generates keys
     * (e.g., an INSERT with auto-generated columns or an UPDATE that triggers key generation).</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Tuple2<Integer, List<Long>> result = JdbcUtil.prepareQuery(conn,
     *         "INSERT INTO audit_log (action, user_id) VALUES (?, ?)")
     *     .setString(1, "LOGIN")
     *     .setLong(2, userId)
     *     .updateAndReturnGeneratedKeys(rs -> rs.getLong(1));
     *     
     * int rowsInserted = result._1;
     * List<Long> auditIds = result._2;
     * }</pre>
     *
     * @param <T> the type of the auto-generated keys
     * @param autoGeneratedKeyExtractor The extractor to retrieve the auto-generated keys.
     *                                  Must not be null.
     * @return A tuple containing the number of rows affected and a list of generated keys.
     *         The list may be empty if no keys were generated.
     * @throws IllegalArgumentException If the provided key extractor is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #updateAndReturnGeneratedKeys(BiRowMapper)
     * @see #update()
     */
    public <T> Tuple2<Integer, List<T>> updateAndReturnGeneratedKeys(final Jdbc.RowMapper<T> autoGeneratedKeyExtractor)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(autoGeneratedKeyExtractor, cs.autoGeneratedKeyExtractor);
        assertNotClosed();

        try {
            final int updatedRowCount = JdbcUtil.executeUpdate(stmt);
            final List<T> generatedKeysList = new ArrayList<>();

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                while (rs.next()) {
                    generatedKeysList.add(autoGeneratedKeyExtractor.apply(rs));
                }
            }

            return verifyResult(updatedRowCount, generatedKeysList);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes an UPDATE/INSERT/DELETE statement and returns both the affected row count and generated keys
     * using a bi-row mapper.
     * 
     * <p>This method provides access to both the ResultSet and column labels when extracting
     * generated keys from update operations.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Tuple2<Integer, List<Map<String, Object>>> result = 
     *     preparedQuery.updateAndReturnGeneratedKeys((rs, labels) -> {
     *         Map<String, Object> keyMap = new HashMap<>();
     *         for (String label : labels) {
     *             keyMap.put(label, rs.getObject(label));
     *         }
     *         return keyMap;
     *     });
     * }</pre>
     *
     * @param <T> the type of the auto-generated keys
     * @param autoGeneratedKeyExtractor The extractor that receives both ResultSet and column labels.
     *                                  Must not be null.
     * @return A tuple containing the number of rows affected and a list of generated keys
     * @throws IllegalArgumentException If the provided key extractor is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #updateAndReturnGeneratedKeys(RowMapper)
     */
    public <T> Tuple2<Integer, List<T>> updateAndReturnGeneratedKeys(final Jdbc.BiRowMapper<T> autoGeneratedKeyExtractor)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(autoGeneratedKeyExtractor, cs.autoGeneratedKeyExtractor);
        assertNotClosed();

        try {
            final int updatedRowCount = JdbcUtil.executeUpdate(stmt);
            final List<T> generatedKeysList = new ArrayList<>();

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                while (rs.next()) {
                    generatedKeysList.add(autoGeneratedKeyExtractor.apply(rs, columnLabels));
                }
            }

            return verifyResult(updatedRowCount, generatedKeysList);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Internal helper method to create a result tuple.
     * 
     * @param <U> the type of the update count value
     * @param <T> the type of the auto-generated keys
     * @param updatedRowCount The number of rows updated
     * @param generatedKeysList The list of generated keys
     * @return A tuple containing the update count and generated keys list
     */
    private <U, T> Tuple2<U, List<T>> verifyResult(final U updatedRowCount, final List<T> generatedKeysList) {
        return Tuple.of(updatedRowCount, generatedKeysList);
    }

    /**
     * Executes a batch of UPDATE/INSERT/DELETE statements and returns an array of update counts.
     * 
     * <p>This method executes all commands in the current batch and returns an array
     * containing one element for each command, indicating the number of rows affected
     * by that command.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * int[] results = JdbcUtil.prepareQuery(conn,
     *         "UPDATE users SET last_login = ? WHERE id = ?")
     *     .addBatch(timestamp1, userId1)
     *     .addBatch(timestamp2, userId2)
     *     .addBatch(timestamp3, userId3)
     *     .batchUpdate();
     *     
     * for (int i = 0; i < results.length; i++) {
     *     System.out.println("Update " + i + " affected " + results[i] + " rows");
     * }
     * }</pre>
     *
     * @return An array of update counts containing one element for each command in the batch.
     *         The elements are ordered according to the order in which commands were added to the batch.
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs or any command in the batch fails
     * @see #largeBatchUpdate()
     * @see #batchUpdateAndReturnGeneratedKeys(RowMapper)
     */
    public int[] batchUpdate() throws IllegalStateException, SQLException {
        assertNotClosed();

        try {
            return JdbcUtil.executeBatch(stmt);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes a batch update operation and returns both the update counts and generated keys.
     * 
     * <p>This method is useful for batch INSERT operations where you need to track both
     * the success of each insert and retrieve the generated keys.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Tuple2<int[], List<Long>> result = JdbcUtil.prepareQuery(conn,
     *         "INSERT INTO orders (customer_id, total) VALUES (?, ?)")
     *     .addBatch(customerId1, total1)
     *     .addBatch(customerId2, total2)
     *     .batchUpdateAndReturnGeneratedKeys(rs -> rs.getLong("order_id"));
     *     
     * int[] insertCounts = result._1;
     * List<Long> orderIds = result._2;
     * }</pre>
     *
     * @param <T> the type of the auto-generated keys
     * @param autoGeneratedKeyExtractor The extractor to retrieve the auto-generated keys.
     *                                  Must not be null.
     * @return A tuple containing an array of update counts and a list of generated keys
     * @throws IllegalArgumentException If the provided key extractor is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #batchUpdate()
     * @see #batchUpdateAndReturnGeneratedKeys(BiRowMapper)
     */
    public <T> Tuple2<int[], List<T>> batchUpdateAndReturnGeneratedKeys(final Jdbc.RowMapper<T> autoGeneratedKeyExtractor)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(autoGeneratedKeyExtractor, cs.autoGeneratedKeyExtractor);
        assertNotClosed();

        try {
            final int[] updatedRowCount = JdbcUtil.executeBatch(stmt);
            final List<T> generatedKeysList = new ArrayList<>();

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                while (rs.next()) {
                    generatedKeysList.add(autoGeneratedKeyExtractor.apply(rs));
                }
            }

            return verifyResult(updatedRowCount, generatedKeysList);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes a batch update operation and returns both the update counts and generated keys
     * using a bi-row mapper.
     * 
     * <p>This method provides access to both the ResultSet and column labels when extracting
     * generated keys from batch update operations.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Tuple2<int[], List<CompositeKey>> result = 
     *     preparedQuery.batchUpdateAndReturnGeneratedKeys((rs, labels) -> 
     *         new CompositeKey(rs.getLong("id"), rs.getString("code"))
     *     );
     * }</pre>
     *
     * @param <T> the type of the auto-generated keys
     * @param autoGeneratedKeyExtractor The extractor that receives both ResultSet and column labels.
     *                                  Must not be null.
     * @return A tuple containing an array of update counts and a list of generated keys
     * @throws IllegalArgumentException If the provided key extractor is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #batchUpdateAndReturnGeneratedKeys(RowMapper)
     */
    public <T> Tuple2<int[], List<T>> batchUpdateAndReturnGeneratedKeys(final Jdbc.BiRowMapper<T> autoGeneratedKeyExtractor)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(autoGeneratedKeyExtractor, cs.autoGeneratedKeyExtractor);
        assertNotClosed();

        try {
            final int[] updatedRowCount = JdbcUtil.executeBatch(stmt);
            final List<T> generatedKeysList = new ArrayList<>();

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                while (rs.next()) {
                    generatedKeysList.add(autoGeneratedKeyExtractor.apply(rs, columnLabels));
                }
            }

            return verifyResult(updatedRowCount, generatedKeysList);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes a large update operation that may affect more rows than can be represented by an int.
     * 
     * <p>This method is similar to {@link #update()} but returns a long value to accommodate
     * update counts that exceed {@link Integer#MAX_VALUE}. Use this method when working with
     * very large tables or bulk operations.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * long rowsArchived = JdbcUtil.prepareQuery(conn,
     *         "INSERT INTO archive_table SELECT * FROM active_table WHERE created < ?")
     *     .setDate(1, oneYearAgo)
     *     .largeUpdate();
     *     
     * System.out.println("Archived " + rowsArchived + " rows");
     * }</pre>
     *
     * @return The number of rows affected by the update as a long value
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #update()
     * @see #largeBatchUpdate()
     */
    public long largeUpdate() throws IllegalStateException, SQLException {
        assertNotClosed();

        try {
            return JdbcUtil.executeLargeUpdate(stmt);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes a large batch update operation that may affect more rows than can be represented by int values.
     * 
     * <p>This method is similar to {@link #batchUpdate()} but returns an array of long values
     * to accommodate update counts that exceed {@link Integer#MAX_VALUE}.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * long[] results = JdbcUtil.prepareQuery(conn,
     *         "UPDATE large_table SET processed = true WHERE batch_id = ?")
     *     .addBatch(batchId1)
     *     .addBatch(batchId2)
     *     .addBatch(batchId3)
     *     .largeBatchUpdate();
     *     
     * for (int i = 0; i < results.length; i++) {
     *     System.out.println("Batch " + i + " updated " + results[i] + " rows");
     * }
     * }</pre>
     *
     * @return An array containing the number of rows affected by each update in the batch as long values
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #batchUpdate()
     * @see #largeUpdate()
     */
    public long[] largeBatchUpdate() throws IllegalStateException, SQLException {
        assertNotClosed();

        try {
            return JdbcUtil.executeLargeBatch(stmt);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the SQL statement which may return multiple results.
     * 
     * <p>This method executes the prepared statement and returns a boolean indicating
     * the type of the first result. Use this method when the SQL statement may return
     * multiple result sets, update counts, or a combination of both.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * boolean hasResultSet = JdbcUtil.prepareQuery(conn, "CALL complex_procedure(?)")
     *     .setInt(1, parameter)
     *     .execute();
     *     
     * if (hasResultSet) {
     *     // First result is a ResultSet
     *     // Use getResultSet() to retrieve it
     * } else {
     *     // First result is an update count
     *     // Use getUpdateCount() to retrieve it
     * }
     * }</pre>
     *
     * <p><b>Note:</b> The underlying statement will be closed after execution unless
     * {@link #closeAfterExecution(boolean)} has been set to {@code false}.</p>
     *
     * @return {@code true} if the first result is a ResultSet object;
     *         {@code false} if it is an update count or there are no results
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #executeThenApply(Throwables.Function)
     * @see #executeThenApply(Throwables.BiFunction)
     * @see #executeThenAccept(Throwables.Consumer)
     * @see #executeThenAccept(Throwables.BiConsumer)
     */
    public boolean execute() throws IllegalStateException, SQLException {
        assertNotClosed();

        try {
            return JdbcUtil.execute(stmt);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the SQL statement and applies the provided function to extract a result.
     * 
     * <p>This method executes the statement and then applies the provided function to the
     * PreparedStatement, allowing custom result extraction. This is useful when you need
     * to access statement metadata or handle complex result processing.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get the first result set after execution
     * ResultSet rs = preparedQuery.executeThenApply(stmt -> stmt.getResultSet());
     * 
     * // Get update count and check warnings
     * Integer count = preparedQuery.executeThenApply(stmt -> {
     *     int updateCount = stmt.getUpdateCount();
     *     SQLWarning warning = stmt.getWarnings();
     *     if (warning != null) {
     *         logger.warn("SQL Warning: " + warning.getMessage());
     *     }
     *     return updateCount;
     * });
     * }</pre>
     *
     * @param <R> the type of result returned from the operation
     * @param getter The function to apply to the PreparedStatement after execution.
     *               Must not be null.
     * @return The result of applying the function to the PreparedStatement
     * @throws IllegalArgumentException If the provided function is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #execute()
     * @see #executeThenApply(Throwables.BiFunction) 
     */
    public <R> R executeThenApply(final Throwables.Function<? super Stmt, ? extends R, SQLException> getter)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(getter, cs.getter);
        assertNotClosed();

        try {
            JdbcUtil.execute(stmt);

            return getter.apply(stmt);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the SQL statement and applies the provided bi-function to extract a result.
     * 
     * <p>This method executes the statement and then applies the provided function to both
     * the PreparedStatement and a boolean indicating whether the first result is a ResultSet.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Process result based on type
     * Object result = preparedQuery.executeThenApply((stmt, isResultSet) -> {
     *     if (isResultSet) {
     *         return processResultSet(stmt.getResultSet());
     *     } else {
     *         return stmt.getUpdateCount();
     *     }
     * });
     * }</pre>
     *
     * @param <R> the type of result returned from the operation
     * @param getter The function to apply to the PreparedStatement. The first parameter is the executed PreparedStatement,
     *               the second parameter indicates if the first result is a ResultSet object.
     *               Must not be null.
     * @return The result of applying the function to the PreparedStatement
     * @throws IllegalArgumentException If the provided function is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #executeThenApply(Throwables.Function) 
     */
    public <R> R executeThenApply(final Throwables.BiFunction<? super Stmt, Boolean, ? extends R, SQLException> getter)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(getter, cs.getter);
        assertNotClosed();

        try {
            final boolean isFirstResultSet = JdbcUtil.execute(stmt);

            return getter.apply(stmt, isFirstResultSet);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the SQL statement and applies the provided consumer to process the statement.
     * 
     * <p>This method executes the statement and then passes the PreparedStatement to the
     * provided consumer for processing. This is useful for side effects or when you don't
     * need to return a value.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Process warnings after execution
     * preparedQuery.executeThenAccept(stmt -> {
     *     SQLWarning warning = stmt.getWarnings();
     *     while (warning != null) {
     *         logger.warn("SQL Warning: " + warning.getMessage());
     *         warning = warning.getNextWarning();
     *     }
     * });
     * 
     * // Process multiple result sets
     * preparedQuery.executeThenAccept(stmt -> {
     *     boolean hasMoreResults = true;
     *     while (hasMoreResults) {
     *         if (stmt.getUpdateCount() != -1) {
     *             System.out.println("Update count: " + stmt.getUpdateCount());
     *         } else {
     *             try (ResultSet rs = stmt.getResultSet()) {
     *                 processResultSet(rs);
     *             }
     *         }
     *         hasMoreResults = stmt.getMoreResults();
     *     }
     * });
     * }</pre>
     *
     * @param consumer The consumer to apply to the PreparedStatement after execution.
     *                 Must not be null.
     * @throws IllegalArgumentException If the provided consumer is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #execute()
     * @see #executeThenAccept(Throwables.BiConsumer) 
     */
    public void executeThenAccept(final Throwables.Consumer<? super Stmt, SQLException> consumer)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(consumer, cs.consumer);
        assertNotClosed();

        try {
            JdbcUtil.execute(stmt);

            consumer.accept(stmt);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the SQL statement and applies the provided bi-consumer to process the statement.
     * 
     * <p>This method executes the statement and then passes both the PreparedStatement and
     * a boolean indicating whether the first result is a ResultSet to the provided consumer.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * preparedQuery.executeThenAccept((stmt, isResultSet) -> {
     *     if (isResultSet) {
     *         try (ResultSet rs = stmt.getResultSet()) {
     *             while (rs.next()) {
     *                 System.out.println("Result: " + rs.getString(1));
     *             }
     *         }
     *     } else {
     *         System.out.println("Rows affected: " + stmt.getUpdateCount());
     *     }
     * });
     * }</pre>
     *
     * @param consumer The consumer to apply to the PreparedStatement. The first parameter is the executed PreparedStatement,
     *                 the second parameter indicates if the first result is a ResultSet object.
     *                 Must not be null.
     * @throws IllegalArgumentException If the provided consumer is null
     * @throws IllegalStateException if this query is closed
     * @throws SQLException if a database access error occurs
     * @see #executeThenAccept(Throwables.Consumer) 
     */
    public void executeThenAccept(final Throwables.BiConsumer<? super Stmt, Boolean, SQLException> consumer)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(consumer, cs.consumer);
        assertNotClosed();

        try {
            final boolean isFirstResultSet = JdbcUtil.execute(stmt);

            consumer.accept(stmt, isFirstResultSet);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Asynchronously executes the provided SQL action using this query instance.
     * 
     * <p>This method allows asynchronous execution of database operations using the default
     * executor. The query instance will remain open until the asynchronous operation completes.</p>
     * 
     * <p><b>Note:</b> The opened Connection and Statement will be held until the sqlAction
     * is completed by another thread. Ensure proper resource management and avoid keeping
     * connections open longer than necessary.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ContinuableFuture<List<User>> future = preparedQuery
     *     .setInt(1, departmentId)
     *     .asyncCall(query -> query.list(User.class));
     *     
     * // Do other work while query executes
     * 
     * List<User> users = future.get(); // Wait for result
     * }</pre>
     *
     * @param <R> the type of result produced by the SQL operation
     * @param sqlAction The SQL action to be executed asynchronously. Must not be null.
     * @return A ContinuableFuture representing the result of the asynchronous execution
     * @throws IllegalArgumentException If the provided SQL action is null
     * @throws IllegalStateException if this query is closed
     * @see #asyncCall(Throwables.Function, Executor) 
     * @see #asyncRun(Throwables.Consumer) 
     */
    @Beta
    public <R> ContinuableFuture<R> asyncCall(final Throwables.Function<? super This, ? extends R, SQLException> sqlAction)
            throws IllegalArgumentException, IllegalStateException {
        checkArgNotNull(sqlAction, cs.sqlAction);
        assertNotClosed();

        final This q = (This) this;

        return JdbcUtil.asyncExecutor.execute(() -> sqlAction.apply(q));
    }

    /**
     * Asynchronously executes the provided SQL action using this query instance with a custom executor.
     * 
     * <p>This method allows asynchronous execution of database operations using a specified
     * executor. This is useful when you want to control the thread pool used for database operations.</p>
     * 
     * <p><b>Note:</b> The opened Connection and Statement will be held until the sqlAction
     * is completed by another thread. Ensure the executor has appropriate thread pool settings
     * to avoid resource exhaustion.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService customExecutor = Executors.newFixedThreadPool(10);
     * 
     * ContinuableFuture<Integer> future = preparedQuery
     *     .setString(1, "INACTIVE")
     *     .setDate(2, sixMonthsAgo)
     *     .asyncCall(query -> query.update(), customExecutor);
     *     
     * future.thenAccept(count -> 
     *     System.out.println("Updated " + count + " inactive users")
     * );
     * }</pre>
     *
     * @param <R> the type of result produced by the SQL operation
     * @param sqlAction The SQL action to be executed asynchronously. Must not be null.
     * @param executor The executor to use for asynchronous execution. Must not be null.
     * @return A ContinuableFuture representing the result of the asynchronous execution
     * @throws IllegalArgumentException If sqlAction or executor is null
     * @throws IllegalStateException if this query is closed
     * @see #asyncCall(Throwables.Function) 
     */
    @Beta
    public <R> ContinuableFuture<R> asyncCall(final Throwables.Function<? super This, ? extends R, SQLException> sqlAction, final Executor executor)
            throws IllegalArgumentException, IllegalStateException {
        checkArgNotNull(sqlAction, cs.sqlAction);
        checkArgNotNull(executor, cs.executor);
        assertNotClosed();

        final This q = (This) this;

        return ContinuableFuture.call(() -> sqlAction.apply(q), executor);
    }

    /**
     * Asynchronously executes the provided SQL action without returning a result.
     * 
     * <p>This method is similar to {@link #asyncCall(Throwables.Function)} but for operations that
     * don't return a value (void operations). Useful for INSERT, UPDATE, DELETE operations
     * where you only care about completion, not the result.</p>
     * 
     * <p><b>Note:</b> The opened Connection and Statement will be held until the sqlAction
     * is completed by another thread.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ContinuableFuture<Void> future = preparedQuery
     *     .setString(1, "Processing")
     *     .setLong(2, batchId)
     *     .asyncRun(query -> {
     *         int updated = query.update();
     *         logger.info("Updated {} records for batch {}", updated, batchId);
     *     });
     *     
     * // Continue with other work while update runs
     * future.thenRun(() -> System.out.println("Batch update completed"));
     * }</pre>
     *
     * @param sqlAction The SQL action to be executed asynchronously. Must not be null.
     * @return A ContinuableFuture representing the completion of the asynchronous execution
     * @throws IllegalArgumentException If the provided SQL action is null
     * @throws IllegalStateException if this query is closed
     * @see #asyncRun(Throwables.Consumer, Executor) 
     * @see #asyncCall(Throwables.Function) 
     */
    @Beta
    public ContinuableFuture<Void> asyncRun(final Throwables.Consumer<? super This, SQLException> sqlAction)
            throws IllegalArgumentException, IllegalStateException {
        checkArgNotNull(sqlAction, cs.sqlAction);
        assertNotClosed();

        final This q = (This) this;

        return JdbcUtil.asyncExecutor.execute(() -> sqlAction.accept(q));
    }

    /**
     * Asynchronously executes the provided SQL action without returning a result using a custom executor.
     * 
     * <p>This method is similar to {@link #asyncCall(Throwables.Function, Executor)} but allows specification of
     * a custom executor for the asynchronous operation.</p>
     * 
     * <p><b>Note:</b> The opened Connection and Statement will be held until the sqlAction
     * is completed by another thread.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);
     * 
     * ContinuableFuture<Void> future = preparedQuery
     *     .setTimestamp(1, new Timestamp(System.currentTimeMillis()))
     *     .asyncRun(query -> {
     *         query.batchUpdate();
     *         // Send notification after batch completes
     *         notificationService.sendBatchComplete();
     *     }, scheduler);
     * }</pre>
     *
     * @param sqlAction The SQL action to be executed asynchronously. Must not be null.
     * @param executor The executor to use for asynchronous execution. Must not be null.
     * @return A ContinuableFuture representing the completion of the asynchronous execution
     * @throws IllegalArgumentException If sqlAction or executor is null
     * @throws IllegalStateException if this query is closed
     * @see #asyncRun(Throwables.Consumer) 
     */
    @Beta
    public ContinuableFuture<Void> asyncRun(final Throwables.Consumer<? super This, SQLException> sqlAction, final Executor executor)
            throws IllegalArgumentException, IllegalStateException {
        checkArgNotNull(sqlAction, cs.sqlAction);
        checkArgNotNull(executor, cs.executor);
        assertNotClosed();

        final This q = (This) this;

        return ContinuableFuture.run(() -> sqlAction.accept(q), executor);
    }

    /**
     * Validates that the provided argument is not null.
     * 
     * <p>If the argument is null, this method will attempt to close the query
     * before throwing an IllegalArgumentException.</p>
     *
     * @param arg The argument to check
     * @param argName The name of the argument for error messaging
     * @throws IllegalArgumentException If arg is null
     */
    protected void checkArgNotNull(final Object arg, final String argName) {
        if (arg == null) {
            final IllegalArgumentException iae = new IllegalArgumentException("'" + argName + "' can't be null");

            try {
                close();
            } catch (final Exception e) {
                JdbcUtil.logger.error("Failed to close PreparedQuery", e);
                iae.addSuppressed(e);
            }

            throw iae;
        }
    }

    /**
     * Validates a boolean condition.
     * 
     * <p>If the condition is false, this method will attempt to close the query
     * before throwing an IllegalArgumentException with the provided error message.</p>
     *
     * @param b The condition to check
     * @param errorMsg The error message to use if the condition is false
     * @throws IllegalArgumentException If b is false
     */
    protected void checkArg(final boolean b, final String errorMsg) {
        if (!b) {
            final IllegalArgumentException iae = new IllegalArgumentException(errorMsg);

            try {
                close();
            } catch (final Exception e) {
                JdbcUtil.logger.error("Failed to close PreparedQuery", e);
                iae.addSuppressed(e);
            }

            throw iae;
        }
    }

    /**
     * Closes this query instance and releases any resources associated with it.
     * 
     * <p>This method closes the underlying PreparedStatement and performs any additional
     * cleanup specified by the close handler. If the instance is already closed, this
     * method does nothing (idempotent).</p>
     * 
     * <p>The method will:</p>
     * <ul>
     *   <li>Close the underlying PreparedStatement</li>
     *   <li>Reset any modified statement properties to their defaults</li>
     *   <li>Execute any registered close handler</li>
     *   <li>Mark this instance as closed</li>
     * </ul>
     * 
     * <p><b>Note:</b> After calling close(), any attempt to use this query instance
     * will result in an IllegalStateException.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * AbstractQuery<?, ?> query = null;
     * try {
     *     query = JdbcUtil.prepareQuery(conn, sql);
     *     // Use query...
     * } finally {
     *     if (query != null) {
     *         query.close();
     *     }
     * }
     * 
     * // Or use try-with-resources:
     * try (AbstractQuery<?, ?> query = JdbcUtil.prepareQuery(conn, sql)) {
     *     // Use query...
     * } // Automatically closed
     * }</pre>
     *
     * @see AutoCloseable#close()
     */
    @Override
    public void close() {
        final Runnable localCloseHandler;

        synchronized (this) {
            if (isClosed) {
                return;
            }

            isClosed = true;
            localCloseHandler = closeHandler;
        }

        if (localCloseHandler == null) {
            closeStatement();
        } else {
            try {
                closeStatement();
            } finally {
                localCloseHandler.run();
            }
        }
    }

    /**
     * Closes the underlying PreparedStatement and resets any modified properties.
     * 
     * <p>This method is called internally by {@link #close()} to perform the actual
     * statement cleanup. It resets any modified statement properties (fetch direction,
     * fetch size, max field size, query timeout) to their original values before
     * closing the statement.</p>
     * 
     * @see JdbcUtil#execute(PreparedStatement)
     * @see JdbcUtil#executeUpdate(PreparedStatement)
     * @see JdbcUtil#clearParameters(PreparedStatement)
     */
    protected void closeStatement() {
        try {
            // stmt.clearParameters(); // cleared by JdbcUtil.clearParameters(stmt) after stmt.execute/executeQuery/executeUpdate.

            // Reset batch action to default to prevent memory leaks
            addBatchAction = defaultAddBatchAction;

            if (defaultFetchDirection >= 0) {
                stmt.setFetchDirection(defaultFetchDirection);
            }

            if (defaultFetchSize >= 0) {
                stmt.setFetchSize(defaultFetchSize);
            }

            if (defaultMaxFieldSize >= 0) {
                stmt.setMaxFieldSize(defaultMaxFieldSize);
            }

            if (defaultQueryTimeout >= 0) {
                stmt.setQueryTimeout(defaultQueryTimeout);
            }
        } catch (final SQLException e) {
            logger.warn("failed to reset statement", e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     * Closes this query after execution if automatic closing is enabled.
     * 
     * <p>This method is called internally after each execution method to implement
     * the automatic resource management behavior. If closeAfterExecution is true
     * (the default), the query will be closed automatically.</p>
     */
    void closeAfterExecutionIfAllowed() {
        if (isCloseAfterExecution) {
            close();
        }
    }

    /**
     * Verifies that this query instance is not closed.
     * 
     * <p>This method is called internally before any operation to ensure the query
     * is still open and usable.</p>
     * 
     * @throws IllegalStateException If this instance has been closed
     */
    void assertNotClosed() {
        if (isClosed) {
            throw new IllegalStateException();
        }
    }
}
