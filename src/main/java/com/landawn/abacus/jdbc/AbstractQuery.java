/*
 * Copyright (c) 2019, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.annotation.LazyEvaluation;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.jdbc.Jdbc.BiResultExtractor;
import com.landawn.abacus.jdbc.Jdbc.ResultExtractor;
import com.landawn.abacus.jdbc.Jdbc.RowConsumer;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.type.TypeFactory;
import com.landawn.abacus.util.CheckedStream;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.DataSet;
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

/**
 * Performance Tips:
 * <li>Avoid unnecessary/repeated database calls.</li>
 * <li>Only fetch the columns you need or update the columns you want.</li>
 * <li>Index is the key point in a lot of database performance issues.</li>
 *
 * <br />
 *
 * The backed {@code PreparedStatement/CallableStatement} will be closed by default
 * after any execution methods(which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/findOnlyOne/list/execute/...).
 * except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.
 *
 * <br />
 * Generally, don't cache or reuse the instance of this class,
 * except the {@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}.
 *
 * <br />
 * Remember: parameter/column index in {@code PreparedStatement/ResultSet} starts from 1, not 0.
 *
 * @param <Stmt>
 * @param <This>
 * @see PreparedStatement
 */
@SuppressWarnings("java:S1192")
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
     * Sets the flag to close the statement after execution.
     *
     * @param closeAfterExecution If {@code true}, the statement will be closed after execution. Default is {@code true}.
     * @return The current instance of the query.
     * @throws IllegalStateException If the query is already closed.
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
     * Registers a task to be executed when this query is closed.
     *
     * @param closeHandler A task to execute after this {@code Query} is closed.
     * @return The current instance of the query.
     * @throws IllegalArgumentException If the provided closeHandler is {@code null}.
     * @throws IllegalStateException If this query is already closed.
     */
    @SuppressWarnings("hiding")
    public This onClose(final Runnable closeHandler) throws IllegalArgumentException, IllegalStateException {
        checkArgNotNull(closeHandler, s.closeHandler);
        assertNotClosed();

        if (this.closeHandler == null) {
            this.closeHandler = closeHandler;
        } else {
            final Runnable tmp = this.closeHandler;

            this.closeHandler = () -> {
                try {
                    tmp.run();
                } finally {
                    closeHandler.run();
                }
            };
        }

        return (This) this;
    }

    /**
     * Sets {@code null} value for the specified parameter index.
     *
     * @param parameterIndex The index of the parameter to set to {@code null}, starting from 1.
     * @param sqlType The SQL type code defined in {@link java.sql.Types}.
     * @return The current instance of the query.
     * @throws SQLException If a database access error occurs.
     */
    public This setNull(final int parameterIndex, final int sqlType) throws SQLException {
        stmt.setNull(parameterIndex, sqlType);

        return (This) this;
    }

    /**
     * Sets {@code null} value for the specified parameter index with a specified SQL type and type name.
     *
     * @param parameterIndex The index of the parameter to set to {@code null}, starting from 1.
     * @param sqlType The SQL type code defined in {@link java.sql.Types}.
     * @param typeName The SQL type name.
     * @return The current instance of the query.
     * @throws SQLException If a database access error occurs.
     */
    public This setNull(final int parameterIndex, final int sqlType, final String typeName) throws SQLException {
        stmt.setNull(parameterIndex, sqlType, typeName);

        return (This) this;
    }

    /**
     * Sets the boolean value for the specified parameter index.
     *
     * @param parameterIndex The index of the parameter to set, starting from 1.
     * @param x The boolean value to set.
     * @return The current instance of the query.
     * @throws SQLException If a database access error occurs.
     */
    public This setBoolean(final int parameterIndex, final boolean x) throws SQLException {
        stmt.setBoolean(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the boolean value for the specified parameter index.
     *
     * @param parameterIndex The index of the parameter to set, starting from 1.
     * @param x The boolean value to set, or {@code null} to set the parameter to SQL {@code NULL}.
     * @return The current instance of the query.
     * @throws SQLException If a database access error occurs.
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
     * Sets the byte value for the specified parameter index.
     *
     * @param parameterIndex The index of the parameter to set, starting from 1.
     * @param x The byte value to set.
     * @return The current instance of the query.
     * @throws SQLException If a database access error occurs.
     */
    public This setByte(final int parameterIndex, final byte x) throws SQLException {
        stmt.setByte(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the byte value for the specified parameter index.
     *
     * @param parameterIndex The index of the parameter to set, starting from 1.
     * @param x The byte value to set, or {@code null} to set the parameter to SQL {@code NULL}.
     * @return The current instance of the query.
     * @throws SQLException If a database access error occurs.
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
     * Sets the byte value for the specified parameter index.
     *
     * @param parameterIndex The index of the parameter to set, starting from 1.
     * @param x The byte value to set, or {@code null} to use the default value.
     * @param defaultValueForNull The default byte value to set if {@code x} is {@code null}.
     * @return The current instance of the query.
     * @throws SQLException If a database access error occurs.
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
     * Sets the short value for the specified parameter index.
     *
     * @param parameterIndex The index of the parameter to set, starting from 1.
     * @param x The short value to set.
     * @return The current instance of the query.
     * @throws SQLException If a database access error occurs.
     */
    public This setShort(final int parameterIndex, final short x) throws SQLException {
        stmt.setShort(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the short value for the specified parameter index.
     *
     * @param parameterIndex The index of the parameter to set, starting from 1.
     * @param x The short value to set, or {@code null} to set the parameter to SQL {@code NULL}.
     * @return The current instance of the query.
     * @throws SQLException If a database access error occurs.
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
     * Sets the short value for the specified parameter index.
     *
     * @param parameterIndex The index of the parameter to set, starting from 1.
     * @param x The short value to set, or {@code null} to use the default value.
     * @param defaultValueForNull The default short value to set if {@code x} is {@code null}.
     * @return The current instance of the query.
     * @throws SQLException If a database access error occurs.
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
     * Sets the integer value for the specified parameter index.
     *
     * @param parameterIndex The index of the parameter to set, starting from 1.
     * @param x The integer value to set.
     * @return The current instance of the query.
     * @throws SQLException If a database access error occurs.
     */
    public This setInt(final int parameterIndex, final int x) throws SQLException {
        stmt.setInt(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the integer value for the specified parameter index.
     *
     * @param parameterIndex The index of the parameter to set, starting from 1.
     * @param x The integer value to set, or {@code null} to set the parameter to SQL {@code NULL}.
     * @return The current instance of the query.
     * @throws SQLException If a database access error occurs.
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
     * Sets the integer value for the specified parameter index.
     *
     * @param parameterIndex The index of the parameter to set, starting from 1.
     * @param x The integer value to set, or {@code null} to use the default value.
     * @param defaultValueForNull The default integer value to set if {@code x} is {@code null}.
     * @return The current instance of the query.
     * @throws SQLException If a database access error occurs.
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
     * Sets the integer value for the specified parameter index using a character.
     *
     * @param parameterIndex The index of the parameter to set, starting from 1.
     * @param x The character value to set as an integer.
     * @return The current instance of the query.
     * @throws SQLException If a database access error occurs.
     * @deprecated Generally, {@code char} should be saved as {@code String} in the database.
     * @see #setString(int, char)
     */
    @Deprecated
    @Beta
    public This setInt(final int parameterIndex, final char x) throws SQLException {
        stmt.setInt(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the integer value for the specified parameter index using a Character.
     *
     * @param parameterIndex The index of the parameter to set, starting from 1.
     * @param x The Character value to set as an integer, or {@code null} to set the parameter to SQL {@code NULL}.
     * @return The current instance of the query.
     * @throws SQLException If a database access error occurs.
     * @deprecated Generally, {@code char} should be saved as {@code String} in the database.
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
     * Sets the long value for the specified parameter index.
     *
     * @param parameterIndex The index of the parameter to set, starting from 1.
     * @param x The long value to set.
     * @return The current instance of the query.
     * @throws SQLException If a database access error occurs.
     */
    public This setLong(final int parameterIndex, final long x) throws SQLException {
        stmt.setLong(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the long value for the specified parameter index.
     *
     * @param parameterIndex The index of the parameter to set, starting from 1.
     * @param x The long value to set, or {@code null} to set the parameter to SQL {@code NULL}.
     * @return The current instance of the query.
     * @throws SQLException If a database access error occurs.
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
     * Sets the long value for the specified parameter index.
     *
     * @param parameterIndex The index of the parameter to set, starting from 1.
     * @param x The long value to set, or {@code null} to use the default value.
     * @param defaultValueForNull The default long value to set if {@code x} is {@code null}.
     * @return The current instance of the query.
     * @throws SQLException If a database access error occurs.
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
     * Sets the long.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException
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
     * Sets the float.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setFloat(final int parameterIndex, final float x) throws SQLException {
        stmt.setFloat(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the float.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException
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
     * Sets the float.
     *
     * @param parameterIndex
     * @param x
     * @param defaultValueForNull
     * @return
     * @throws SQLException
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
     * Sets the double.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setDouble(final int parameterIndex, final double x) throws SQLException {
        stmt.setDouble(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the double.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException
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
     * Sets the double.
     *
     * @param parameterIndex
     * @param x
     * @param defaultValueForNull
     * @return
     * @throws SQLException
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
     * Sets the big decimal.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setBigDecimal(final int parameterIndex, final BigDecimal x) throws SQLException {
        stmt.setBigDecimal(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the BigInteger.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
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
     * Sets the BigInteger.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     * @see {@link #setString(int, BigInteger)}
     * @see {@link #setBigDecimal(int, BigInteger)}
     * @see {@link #setLong(int, BigInteger)}
     */
    @Beta
    public This setBigIntegerAsString(final int parameterIndex, final BigInteger x) throws SQLException {
        return setString(parameterIndex, x);
    }

    /**
     * Sets the string.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setString(final int parameterIndex, final String x) throws SQLException {
        stmt.setString(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the string.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setString(final int parameterIndex, final CharSequence x) throws SQLException {
        stmt.setString(parameterIndex, x == null ? null : x.toString());

        return (This) this;
    }

    /**
     *
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException
     */
    public This setString(final int parameterIndex, final char x) throws SQLException {
        stmt.setString(parameterIndex, String.valueOf(x));

        return (This) this;
    }

    /**
     * Sets the String.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException
     */
    public This setString(final int parameterIndex, final Character x) throws SQLException {
        stmt.setString(parameterIndex, x == null ? null : String.valueOf(x));

        return (This) this;
    }

    /**
     * Sets the BigInteger.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
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
     * Sets the String.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException
     */
    public This setNString(final int parameterIndex, final String x) throws SQLException {
        stmt.setNString(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the String.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException
     */
    public This setNString(final int parameterIndex, final CharSequence x) throws SQLException {
        stmt.setNString(parameterIndex, x == null ? null : x.toString());

        return (This) this;
    }

    /**
     * Sets the date.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setDate(final int parameterIndex, final java.sql.Date x) throws SQLException {
        stmt.setDate(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the date.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setDate(final int parameterIndex, final java.util.Date x) throws SQLException {
        stmt.setDate(parameterIndex, x == null ? null : x instanceof java.sql.Date ? (java.sql.Date) x : new java.sql.Date(x.getTime()));

        return (This) this;
    }

    /**
     * Sets the date.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @param cal
     * @return
     * @throws SQLException
     */
    public This setDate(final int parameterIndex, final java.sql.Date x, final Calendar cal) throws SQLException {
        stmt.setDate(parameterIndex, x, cal);

        return (This) this;
    }

    /**
     * Sets the date.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setDate(final int parameterIndex, final LocalDate x) throws SQLException {
        stmt.setDate(parameterIndex, x == null ? null : java.sql.Date.valueOf(x));

        return (This) this;
    }

    /**
     * Sets the time.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setTime(final int parameterIndex, final java.sql.Time x) throws SQLException {
        stmt.setTime(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the time.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setTime(final int parameterIndex, final java.util.Date x) throws SQLException {
        stmt.setTime(parameterIndex, x == null ? null : x instanceof java.sql.Time ? (java.sql.Time) x : new java.sql.Time(x.getTime()));

        return (This) this;
    }

    /**
     * Sets the time.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @param cal
     * @return
     * @throws SQLException
     */
    public This setTime(final int parameterIndex, final java.sql.Time x, final Calendar cal) throws SQLException {
        stmt.setTime(parameterIndex, x, cal);

        return (This) this;
    }

    /**
     * Sets the time.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setTime(final int parameterIndex, final LocalTime x) throws SQLException {
        stmt.setTime(parameterIndex, x == null ? null : java.sql.Time.valueOf(x));

        return (This) this;
    }

    /**
     * Sets the timestamp.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setTimestamp(final int parameterIndex, final java.sql.Timestamp x) throws SQLException {
        stmt.setTimestamp(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the timestamp.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setTimestamp(final int parameterIndex, final java.util.Date x) throws SQLException {
        stmt.setTimestamp(parameterIndex, x == null ? null : x instanceof java.sql.Timestamp ? (java.sql.Timestamp) x : new java.sql.Timestamp(x.getTime()));

        return (This) this;
    }

    /**
     * Sets the timestamp.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @param cal
     * @return
     * @throws SQLException
     */
    public This setTimestamp(final int parameterIndex, final java.sql.Timestamp x, final Calendar cal) throws SQLException {
        stmt.setTimestamp(parameterIndex, x, cal);

        return (This) this;
    }

    /**
     * Sets the timestamp.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setTimestamp(final int parameterIndex, final LocalDateTime x) throws SQLException {
        stmt.setTimestamp(parameterIndex, x == null ? null : Timestamp.valueOf(x));

        return (This) this;
    }

    /**
     * Sets the timestamp.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setTimestamp(final int parameterIndex, final ZonedDateTime x) throws SQLException {
        stmt.setTimestamp(parameterIndex, x == null ? null : Timestamp.from(x.toInstant()));

        return (This) this;
    }

    /**
     * Sets the timestamp.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setTimestamp(final int parameterIndex, final OffsetDateTime x) throws SQLException {
        stmt.setTimestamp(parameterIndex, x == null ? null : Timestamp.from(x.toInstant()));

        return (This) this;
    }

    /**
     * Sets the timestamp.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setTimestamp(final int parameterIndex, final Instant x) throws SQLException {
        stmt.setTimestamp(parameterIndex, x == null ? null : Timestamp.from(x));

        return (This) this;
    }

    /**
     * Sets the bytes.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException
     */
    public This setBytes(final int parameterIndex, final byte[] x) throws SQLException {
        stmt.setBytes(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the ascii stream.
     *
     * @param parameterIndex
     * @param inputStream
     * @return
     * @throws SQLException
     */
    public This setAsciiStream(final int parameterIndex, final InputStream inputStream) throws SQLException {
        stmt.setAsciiStream(parameterIndex, inputStream);

        return (This) this;
    }

    /**
     * Sets the ascii stream.
     *
     * @param parameterIndex
     * @param inputStream
     * @param length
     * @return
     * @throws SQLException
     */
    public This setAsciiStream(final int parameterIndex, final InputStream inputStream, final int length) throws SQLException {
        stmt.setAsciiStream(parameterIndex, inputStream, length);

        return (This) this;
    }

    /**
     * Sets the ascii stream.
     *
     * @param parameterIndex
     * @param inputStream
     * @param length
     * @return
     * @throws SQLException
     */
    public This setAsciiStream(final int parameterIndex, final InputStream inputStream, final long length) throws SQLException {
        stmt.setAsciiStream(parameterIndex, inputStream, length);

        return (This) this;
    }

    /**
     * Sets the binary stream.
     *
     * @param parameterIndex
     * @param inputStream
     * @return
     * @throws SQLException
     */
    public This setBinaryStream(final int parameterIndex, final InputStream inputStream) throws SQLException {
        stmt.setBinaryStream(parameterIndex, inputStream);

        return (This) this;
    }

    /**
     * Sets the binary stream.
     *
     * @param parameterIndex
     * @param inputStream
     * @param length
     * @return
     * @throws SQLException
     */
    public This setBinaryStream(final int parameterIndex, final InputStream inputStream, final int length) throws SQLException {
        stmt.setBinaryStream(parameterIndex, inputStream, length);

        return (This) this;
    }

    /**
     * Sets the binary stream.
     *
     * @param parameterIndex
     * @param inputStream
     * @param length
     * @return
     * @throws SQLException
     */
    public This setBinaryStream(final int parameterIndex, final InputStream inputStream, final long length) throws SQLException {
        stmt.setBinaryStream(parameterIndex, inputStream, length);

        return (This) this;
    }

    /**
     * Sets the character stream.
     *
     * @param parameterIndex
     * @param reader
     * @return
     * @throws SQLException
     */
    public This setCharacterStream(final int parameterIndex, final Reader reader) throws SQLException {
        stmt.setCharacterStream(parameterIndex, reader);

        return (This) this;
    }

    /**
     * Sets the character stream.
     *
     * @param parameterIndex
     * @param reader
     * @param length
     * @return
     * @throws SQLException
     */
    public This setCharacterStream(final int parameterIndex, final Reader reader, final int length) throws SQLException {
        stmt.setCharacterStream(parameterIndex, reader, length);

        return (This) this;
    }

    /**
     * Sets the character stream.
     *
     * @param parameterIndex
     * @param reader
     * @param length
     * @return
     * @throws SQLException
     */
    public This setCharacterStream(final int parameterIndex, final Reader reader, final long length) throws SQLException {
        stmt.setCharacterStream(parameterIndex, reader, length);

        return (This) this;
    }

    /**
     * Sets the N character stream.
     *
     * @param parameterIndex
     * @param reader
     * @return
     * @throws SQLException
     */
    public This setNCharacterStream(final int parameterIndex, final Reader reader) throws SQLException {
        stmt.setNCharacterStream(parameterIndex, reader);

        return (This) this;
    }

    /**
     * Sets the N character stream.
     *
     * @param parameterIndex
     * @param reader
     * @param length
     * @return
     * @throws SQLException
     */
    public This setNCharacterStream(final int parameterIndex, final Reader reader, final long length) throws SQLException {
        stmt.setNCharacterStream(parameterIndex, reader, length);

        return (This) this;
    }

    /**
     * Sets the blob.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException
     */
    public This setBlob(final int parameterIndex, final java.sql.Blob x) throws SQLException {
        stmt.setBlob(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the blob.
     *
     * @param parameterIndex
     * @param inputStream
     * @return
     * @throws SQLException
     */
    public This setBlob(final int parameterIndex, final InputStream inputStream) throws SQLException {
        stmt.setBlob(parameterIndex, inputStream);

        return (This) this;
    }

    /**
     * Sets the blob.
     *
     * @param parameterIndex
     * @param inputStream
     * @param length
     * @return
     * @throws SQLException
     */
    public This setBlob(final int parameterIndex, final InputStream inputStream, final long length) throws SQLException {
        stmt.setBlob(parameterIndex, inputStream, length);

        return (This) this;
    }

    /**
     * Sets the clob.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException
     */
    public This setClob(final int parameterIndex, final java.sql.Clob x) throws SQLException {
        stmt.setClob(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the clob.
     *
     * @param parameterIndex
     * @param reader
     * @return
     * @throws SQLException
     */
    public This setClob(final int parameterIndex, final Reader reader) throws SQLException {
        stmt.setClob(parameterIndex, reader);

        return (This) this;
    }

    /**
     * Sets the clob.
     *
     * @param parameterIndex
     * @param reader
     * @param length
     * @return
     * @throws SQLException
     */
    public This setClob(final int parameterIndex, final Reader reader, final long length) throws SQLException {
        stmt.setClob(parameterIndex, reader, length);

        return (This) this;
    }

    /**
     * Sets the N clob.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException
     */
    public This setNClob(final int parameterIndex, final java.sql.NClob x) throws SQLException {
        stmt.setNClob(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the N clob.
     *
     * @param parameterIndex
     * @param reader
     * @return
     * @throws SQLException
     */
    public This setNClob(final int parameterIndex, final Reader reader) throws SQLException {
        stmt.setNClob(parameterIndex, reader);

        return (This) this;
    }

    /**
     * Sets the N clob.
     *
     * @param parameterIndex
     * @param reader
     * @param length
     * @return
     * @throws SQLException
     */
    public This setNClob(final int parameterIndex, final Reader reader, final long length) throws SQLException {
        stmt.setNClob(parameterIndex, reader, length);

        return (This) this;
    }

    /**
     * Sets the URL.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException
     */
    public This setURL(final int parameterIndex, final URL x) throws SQLException {
        stmt.setURL(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the array.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException
     */
    public This setArray(final int parameterIndex, final java.sql.Array x) throws SQLException {
        stmt.setArray(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the SQLXML.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException
     */
    public This setSQLXML(final int parameterIndex, final java.sql.SQLXML x) throws SQLException {
        stmt.setSQLXML(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the ref.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException
     */
    public This setRef(final int parameterIndex, final java.sql.Ref x) throws SQLException {
        stmt.setRef(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the row id.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException
     */
    public This setRowId(final int parameterIndex, final java.sql.RowId x) throws SQLException {
        stmt.setRowId(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the object.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
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
     * Sets the object.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @param sqlType
     * @return
     * @throws SQLException
     * @see java.sql.Types
     */
    public This setObject(final int parameterIndex, final Object x, final int sqlType) throws SQLException {
        stmt.setObject(parameterIndex, x, sqlType);

        return (This) this;
    }

    /**
     * Sets the object.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @param sqlType
     * @param scaleOrLength
     * @return
     * @throws SQLException
     * @see java.sql.Types
     */
    public This setObject(final int parameterIndex, final Object x, final int sqlType, final int scaleOrLength) throws SQLException {
        stmt.setObject(parameterIndex, x, sqlType, scaleOrLength);

        return (This) this;
    }

    /**
     * Sets the object.
     *
     * @param parameterIndex
     * @param x
     * @param sqlType
     * @return
     * @throws SQLException
     */
    public This setObject(final int parameterIndex, final Object x, final SQLType sqlType) throws SQLException {
        stmt.setObject(parameterIndex, x, sqlType);

        return (This) this;
    }

    /**
     * Sets the object for the specified parameter index using the provided SQL type and scale or length.
     *
     * @param parameterIndex The index of the parameter to set, starting from 1.
     * @param x The object to set.
     * @param sqlType The SQL type of the object.
     * @param scaleOrLength The scale or length of the object.
     * @return The current instance of the query.
     * @throws SQLException If a database access error occurs.
     */
    public This setObject(final int parameterIndex, final Object x, final SQLType sqlType, final int scaleOrLength) throws SQLException {
        stmt.setObject(parameterIndex, x, sqlType, scaleOrLength);

        return (This) this;
    }

    /**
     * Sets the object for the specified parameter index using the provided type.
     *
     * @param <T> The type of the object to set.
     * @param parameterIndex The index of the parameter to set, starting from 1.
     * @param x The object to set.
     * @param type The type of the object.
     * @return The current instance of the query.
     * @throws IllegalArgumentException If the provided type is invalid.
     * @throws SQLException If a database access error occurs.
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
     * Sets the parameters for the prepared statement starting from index {@code 1}.
     *
     * @param param1 The first parameter to set.
     * @param param2 The second parameter to set.
     * @return The current instance of the query.
     * @throws IllegalArgumentException If an illegal argument is provided.
     * @throws SQLException If a database access error occurs.
     */
    public This setParameters(final String param1, final String param2) throws SQLException {
        stmt.setString(1, param1);
        stmt.setString(2, param2);

        return (This) this;
    }

    /**
     * Sets the parameters for the prepared statement starting from index {@code 1}.
     *
     * @param param1 The first parameter to set.
     * @param param2 The second parameter to set.
     * @param param3 The third parameter to set.
     * @return The current instance of the query.
     * @throws IllegalArgumentException If an illegal argument is provided.
     * @throws SQLException If a database access error occurs.
     */
    public This setParameters(final String param1, final String param2, final String param3) throws SQLException {
        stmt.setString(1, param1);
        stmt.setString(2, param2);
        stmt.setString(3, param3);

        return (This) this;
    }

    /**
     * Sets the parameters for the prepared statement starting from index {@code 1}.
     *
     * @param param1 The first parameter to set.
     * @param param2 The second parameter to set.
     * @param param3 The third parameter to set.
     * @param param4 The fourth parameter to set.
     * @return The current instance of the query.
     * @throws IllegalArgumentException If an illegal argument is provided.
     * @throws SQLException If a database access error occurs.
     */
    public This setParameters(final String param1, final String param2, final String param3, final String param4) throws SQLException {
        stmt.setString(1, param1);
        stmt.setString(2, param2);
        stmt.setString(3, param3);
        stmt.setString(4, param4);

        return (This) this;
    }

    /**
     * Sets the parameters for the prepared statement starting from index {@code 1}.
     *
     * @param param1 The first parameter to set.
     * @param param2 The second parameter to set.
     * @param param3 The third parameter to set.
     * @param param4 The fourth parameter to set.
     * @param param5 The fifth parameter to set.
     * @return The current instance of the query.
     * @throws IllegalArgumentException If an illegal argument is provided.
     * @throws SQLException If a database access error occurs.
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
     * Sets the parameters for the prepared statement starting from index {@code 1}.
     *
     * @param param1 The first parameter to set.
     * @param param2 The second parameter to set.
     * @param param3 The third parameter to set.
     * @param param4 The fourth parameter to set.
     * @param param5 The fifth parameter to set.
     * @param param6 The sixth parameter to set.
     * @return The current instance of the query.
     * @throws IllegalArgumentException If an illegal argument is provided.
     * @throws SQLException If a database access error occurs.
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
     * Sets the parameters for the prepared statement starting from index {@code 1}.
     *
     * @param param1 The first parameter to set.
     * @param param2 The second parameter to set.
     * @param param3 The third parameter to set.
     * @param param4 The fourth parameter to set.
     * @param param5 The fifth parameter to set.
     * @param param6 The sixth parameter to set.
     * @param param7 The seventh parameter to set.
     * @return The current instance of the query.
     * @throws IllegalArgumentException If an illegal argument is provided.
     * @throws SQLException If a database access error occurs.
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
     * Sets the parameters for the prepared statement starting from index {@code 1}.
     *
     * @param param1 The first parameter to set.
     * @param param2 The second parameter to set.
     * @param param3 The third parameter to set.
     * @return The current instance of the query.
     * @throws IllegalArgumentException If an illegal argument is provided.
     * @throws SQLException If a database access error occurs.
     */
    public This setParameters(final Object param1, final Object param2, final Object param3) throws SQLException {
        setObject(1, param1);
        setObject(2, param2);
        setObject(3, param3);

        return (This) this;
    }

    /**
     * Sets the parameters for the prepared statement starting from index {@code 1}.
     *
     * @param param1 The first parameter to set.
     * @param param2 The second parameter to set.
     * @param param3 The third parameter to set.
     * @param param4 The fourth parameter to set.
     * @return The current instance of the query.
     * @throws IllegalArgumentException If an illegal argument is provided.
     * @throws SQLException If a database access error occurs.
     */
    public This setParameters(final Object param1, final Object param2, final Object param3, final Object param4) throws SQLException {
        setObject(1, param1);
        setObject(2, param2);
        setObject(3, param3);
        setObject(4, param4);

        return (This) this;
    }

    /**
     * Sets the parameters for the prepared statement starting from index {@code 1}.
     *
     * @param param1 The first parameter to set.
     * @param param2 The second parameter to set.
     * @param param3 The third parameter to set.
     * @param param4 The fourth parameter to set.
     * @param param5 The fifth parameter to set.
     * @return The current instance of the query.
     * @throws IllegalArgumentException If an illegal argument is provided.
     * @throws SQLException If a database access error occurs.
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
     * Sets the parameters for the prepared statement starting from index {@code 1}.
     *
     * @param param1 The first parameter to set.
     * @param param2 The second parameter to set.
     * @param param3 The third parameter to set.
     * @param param4 The fourth parameter to set.
     * @param param5 The fifth parameter to set.
     * @param param6 The sixth parameter to set.
     * @return The current instance of the query.
     * @throws IllegalArgumentException If an illegal argument is provided.
     * @throws SQLException If a database access error occurs.
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
     * Sets the parameters for the prepared statement starting from index {@code 1}.
     *
     * @param param1 The first parameter to set.
     * @param param2 The second parameter to set.
     * @param param3 The third parameter to set.
     * @param param4 The fourth parameter to set.
     * @param param5 The fifth parameter to set.
     * @param param6 The sixth parameter to set.
     * @param param7 The seventh parameter to set.
     * @return The current instance of the query.
     * @throws IllegalArgumentException If an illegal argument is provided.
     * @throws SQLException If a database access error occurs.
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
     * Sets the parameters for the prepared statement starting from index {@code 1}.
     *
     * @param param1 The first parameter to set.
     * @param param2 The second parameter to set.
     * @param param3 The third parameter to set.
     * @param param4 The fourth parameter to set.
     * @param param5 The fifth parameter to set.
     * @param param6 The sixth parameter to set.
     * @param param7 The seventh parameter to set.
     * @param param8 The eighth parameter to set.
     * @return The current instance of the query.
     * @throws IllegalArgumentException If an illegal argument is provided.
     * @throws SQLException If a database access error occurs.
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
     * Sets the parameters for the prepared statement starting from index {@code 1}.
     *
     * @param param1 The first parameter to set.
     * @param param2 The second parameter to set.
     * @param param3 The third parameter to set.
     * @param param4 The fourth parameter to set.
     * @param param5 The fifth parameter to set.
     * @param param6 The sixth parameter to set.
     * @param param7 The seventh parameter to set.
     * @param param8 The eighth parameter to set.
     * @param param9 The ninth parameter to set.
     * @return The current instance of the query.
     * @throws IllegalArgumentException If an illegal argument is provided.
     * @throws SQLException If a database access error occurs.
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
     * Sets the parameters for the prepared statement.
     *
     * @param parameters The array of parameters to set.
     * @return The current instance of the query.
     * @throws IllegalArgumentException If an illegal argument is provided.
     * @throws SQLException If a database access error occurs.
     */
    public This setParameters(final int[] parameters) throws IllegalArgumentException, SQLException {
        return settParameters(1, parameters);
    }

    /**
     * Sets the parameters.
     *
     * @param parameters
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is {@code null}.
     * @throws SQLException
     */
    public This setParameters(final long[] parameters) throws IllegalArgumentException, SQLException {
        return settParameters(1, parameters);
    }

    /**
     * Sets the parameters.
     *
     * @param parameters
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is {@code null}.
     * @throws SQLException
     */
    public This setParameters(final String[] parameters) throws IllegalArgumentException, SQLException {
        return settParameters(1, parameters);
    }

    /**
     * Sets the parameters.
     *
     * @param <T>
     * @param parameters it should be an array of concrete types. For example: {@code String[]}, {@code Date[]}.
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is {@code null}.
     * @throws SQLException
     */
    public <T> This setParameters(final T[] parameters) throws IllegalArgumentException, SQLException {
        return settParameters(1, parameters);
    }

    /**
     * Sets the parameters.
     *
     * @param parameters
     * @return
     * @throws IllegalArgumentException if specified {@code parameters}.
     * @throws SQLException
     */
    public This setParameters(final Collection<?> parameters) throws IllegalArgumentException, SQLException {
        return settParameters(1, parameters);
    }

    /**
     * Sets the parameters.
     *
     * @param <T>
     * @param parameters
     * @param type
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is {@code null}.
     * @throws SQLException
     */
    public <T> This setParameters(final Collection<? extends T> parameters, final Class<T> type) throws IllegalArgumentException, SQLException {
        return settParameters(1, parameters, type);
    }

    /**
     * Sets the parameters.
     *
     * @param paramsSetter
     * @return
     * @throws IllegalArgumentException
     * @throws SQLException
     */
    public This setParameters(final Jdbc.ParametersSetter<? super Stmt> paramsSetter) throws IllegalArgumentException, SQLException {
        checkArgNotNull(paramsSetter, s.paramsSetter);

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
     * Sets the parameters.
     *
     * @param <T>
     * @param parameters
     * @param paramsSetter
     * @return
     * @throws IllegalArgumentException
     * @throws SQLException
     */
    public <T> This setParameters(final T parameters, final Jdbc.BiParametersSetter<? super Stmt, ? super T> paramsSetter)
            throws IllegalArgumentException, SQLException {
        checkArgNotNull(paramsSetter, s.paramsSetter);

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
     * Sets the parameters.
     *
     * @param startParameterIndex
     * @param parameters
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is {@code null}.
     * @throws SQLException
     */
    public This settParameters(int startParameterIndex, final int[] parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, s.parameters);

        for (final int param : parameters) {
            stmt.setInt(startParameterIndex++, param);
        }

        return (This) this;
    }

    /**
     * Sets the parameters.
     *
     * @param startParameterIndex
     * @param parameters
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is {@code null}.
     * @throws SQLException
     */
    public This settParameters(int startParameterIndex, final long[] parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, s.parameters);

        for (final long param : parameters) {
            stmt.setLong(startParameterIndex++, param);
        }

        return (This) this;
    }

    /**
     * Sets the parameters.
     *
     * @param startParameterIndex
     * @param parameters
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is {@code null}.
     * @throws SQLException
     */
    public This settParameters(int startParameterIndex, final String[] parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, s.parameters);

        for (final String param : parameters) {
            stmt.setString(startParameterIndex++, param);
        }

        return (This) this;
    }

    /**
     * Sets the parameters.
     *
     * @param <T>
     * @param startParameterIndex
     * @param parameters it should be an array of concrete types. For example: {@code String[]}, {@code Date[]}.
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is {@code null}.
     * @throws SQLException
     */
    public <T> This settParameters(int startParameterIndex, final T[] parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, s.parameters);

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
     * Sets the parameters.
     *
     * @param startParameterIndex
     * @param parameters
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is {@code null}.
     * @throws SQLException
     */
    public This settParameters(int startParameterIndex, final Collection<?> parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, s.parameters);

        for (final Object param : parameters) {
            setObject(startParameterIndex++, param);
        }

        return (This) this;
    }

    /**
     * Sets the parameters.
     *
     * @param <T>
     * @param startParameterIndex
     * @param parameters
     * @param type
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is {@code null}.
     * @throws SQLException
     */
    public <T> This settParameters(int startParameterIndex, final Collection<? extends T> parameters, final Class<T> type)
            throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, s.parameters);
        checkArgNotNull(type, s.type);

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
     *
     *
     * @param paramsSetter
     * @return
     * @throws IllegalArgumentException
     * @throws SQLException
     */
    @Beta
    public This settParameters(final Jdbc.ParametersSetter<? super This> paramsSetter) throws IllegalArgumentException, SQLException {
        checkArgNotNull(paramsSetter, s.paramsSetter);

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
     *
     *
     * @param <T>
     * @param parameters
     * @param paramsSetter
     * @return
     * @throws IllegalArgumentException
     * @throws SQLException
     */
    @Beta
    public <T> This settParameters(final T parameters, final Jdbc.BiParametersSetter<? super This, ? super T> paramsSetter)
            throws IllegalArgumentException, SQLException {
        checkArgNotNull(paramsSetter, s.paramsSetter);

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
     *
     *
     * @param sqlType
     * @param parameterIndices
     * @return
     * @throws SQLException
     * @see java.sql.Types
     * @deprecated
     */
    @Deprecated
    @Beta
    public This setNullForMultiPositions(final int sqlType, final int... parameterIndices) throws SQLException {
        checkParameterIndices(parameterIndices);

        for (final int parameterIndex : parameterIndices) {
            setNull(parameterIndex, sqlType);
        }

        return (This) this;
    }

    /**
     *
     *
     * @param parameterValue
     * @param parameterIndices
     * @return
     * @throws SQLException
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
     *
     *
     * @param parameterValue
     * @param parameterIndices
     * @return
     * @throws SQLException
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
     *
     *
     * @param parameterValue
     * @param parameterIndices
     * @return
     * @throws SQLException
     */
    @Beta
    public This setLongForMultiPositions(final Long parameterValue, final int... parameterIndices) throws SQLException {
        checkParameterIndices(parameterIndices);

        for (final int parameterIndex : parameterIndices) {
            setLong(parameterIndex, parameterValue);
        }

        return (This) this;
    }

    //    /**
    //     *
    //     *
    //     * @param parameterValue
    //     * @param parameterIndices
    //     * @return
    //     * @throws SQLException
    //     */
    //    @Beta
    //    public This setDoubleForMultiPositions(final double parameterValue, final int... parameterIndices) throws SQLException {
    //        checkParameterIndices(parameterIndices);
    //
    //        for (int parameterIndex : parameterIndices) {
    //            setDouble(parameterIndex, parameterValue);
    //        }
    //
    //        return (This) this;
    //    }

    /**
     *
     *
     * @param parameterValue
     * @param parameterIndices
     * @return
     * @throws SQLException
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
     *
     *
     * @param parameterValue
     * @param parameterIndices
     * @return
     * @throws SQLException
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
     *
     *
     * @param parameterValue
     * @param parameterIndices
     * @return
     * @throws SQLException
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
     *
     *
     * @param parameterValue
     * @param parameterIndices
     * @return
     * @throws SQLException
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
     *
     *
     * @param parameterValue
     * @param parameterIndices
     * @return
     * @throws SQLException
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
     *
     *
     * @param parameterValue
     * @param parameterIndices
     * @return
     * @throws SQLException
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
     *
     *
     * @param parameterValue
     * @param parameterIndices
     * @return
     * @throws SQLException
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
     *
     *
     * @param parameterValue
     * @param parameterIndices
     * @return
     * @throws SQLException
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
     *
     *
     * @param parameterValue
     * @param parameterIndices
     * @return
     * @throws SQLException
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
     *
     *
     * @param batchParameters
     * @return
     * @throws IllegalArgumentException
     * @throws SQLException
     */
    @Beta
    public This addBatchParameters(final Collection<?> batchParameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(batchParameters, s.batchParameters);

        if (N.isEmpty(batchParameters)) {
            return (This) this;
        }

        return addBatchParameters(batchParameters.iterator());
    }

    /**
     *
     *
     * @param <T>
     * @param batchParameters single batch parameters.
     * @param type
     * @return
     * @throws IllegalArgumentException
     * @throws SQLException
     */
    @Beta
    public <T> This addBatchParameters(final Collection<? extends T> batchParameters, final Class<T> type) throws IllegalArgumentException, SQLException {
        checkArgNotNull(batchParameters, s.batchParameters);
        checkArgNotNull(type, s.type);

        if (N.isEmpty(batchParameters)) {
            return (This) this;
        }

        return addBatchParameters(batchParameters.iterator(), type);
    }

    /**
     *
     *
     * @param batchParameters
     * @return
     * @throws IllegalArgumentException
     * @throws SQLException
     */
    @Beta
    @SuppressWarnings("rawtypes")
    public This addBatchParameters(final Iterator<?> batchParameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(batchParameters, s.batchParameters);

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
     *
     *
     * @param <T>
     * @param batchParameters single batch parameters.
     * @param type
     * @return
     * @throws IllegalArgumentException
     * @throws SQLException
     */
    @Beta
    public <T> This addBatchParameters(final Iterator<? extends T> batchParameters, final Class<T> type) throws IllegalArgumentException, SQLException {
        checkArgNotNull(batchParameters, s.batchParameters);
        checkArgNotNull(type, s.type);

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
     * @param <T>
     * @param batchParameters
     * @param parametersSetter
     * @return
     * @throws SQLException
     */
    @Beta
    public <T> This addBatchParameters(final Collection<? extends T> batchParameters, final Jdbc.BiParametersSetter<? super This, ? super T> parametersSetter)
            throws SQLException {
        checkArgNotNull(batchParameters, s.batchParameters);
        checkArgNotNull(parametersSetter, s.parametersSetter);

        return addBatchParameters(batchParameters.iterator(), parametersSetter);
    }

    /**
     *
     * @param <T>
     * @param batchParameters
     * @param parametersSetter
     * @return
     * @throws SQLException
     */
    @Beta
    public <T> This addBatchParameters(final Iterator<? extends T> batchParameters, final Jdbc.BiParametersSetter<? super This, ? super T> parametersSetter)
            throws SQLException {
        checkArgNotNull(batchParameters, s.batchParameters);
        checkArgNotNull(parametersSetter, s.parametersSetter);

        final This it = (This) this;
        boolean noException = false;

        try {
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
     *
     *
     * @param <T>
     * @param batchParameters
     * @param parametersSetter
     * @return
     * @throws IllegalArgumentException
     * @throws SQLException
     */
    @Beta
    public <T> This addBatchParameters(final Collection<? extends T> batchParameters,
            final Throwables.TriConsumer<? super This, ? super Stmt, ? super T, ? extends SQLException> parametersSetter)
            throws IllegalArgumentException, SQLException {
        checkArgNotNull(batchParameters, s.batchParameters);
        checkArgNotNull(parametersSetter, s.parametersSetter);

        return addBatchParameters(batchParameters.iterator(), parametersSetter);
    }

    /**
     *
     *
     * @param <T>
     * @param batchParameters
     * @param parametersSetter
     * @return
     * @throws IllegalArgumentException
     * @throws SQLException
     */
    @Beta
    public <T> This addBatchParameters(final Iterator<? extends T> batchParameters,
            final Throwables.TriConsumer<? super This, ? super Stmt, ? super T, ? extends SQLException> parametersSetter)
            throws IllegalArgumentException, SQLException {
        checkArgNotNull(batchParameters, s.batchParameters);
        checkArgNotNull(parametersSetter, s.parametersSetter);

        final This it = (This) this;
        boolean noException = false;

        try {
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
     * Adds the batch.
     *
     * @return
     * @throws SQLException
     */
    public This addBatch() throws SQLException {
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
     * Sets the fetch direction.
     *
     * @param direction one of {@code ResultSet.FETCH_FORWARD},
     * {@code ResultSet.FETCH_REVERSE}, or {@code ResultSet.FETCH_UNKNOWN}
     * @return
     * @throws SQLException
     * @see {@link java.sql.Statement#setFetchDirection(int)}
     */
    public This setFetchDirection(final FetchDirection direction) throws SQLException {
        defaultFetchDirection = stmt.getFetchDirection();

        stmt.setFetchDirection(direction.intValue);

        isFetchDirectionSet = true;

        return (This) this;
    }

    /**
     *
     * @return
     * @throws SQLException
     * @see {@link #setFetchDirection(FetchDirection)}
     */
    public This setFetchDirectionToForward() throws SQLException {
        return setFetchDirection(FetchDirection.FORWARD);
    }

    /**
     * Sets the fetch size.
     *
     * @param fetchSize the number of rows to fetch
     * @return
     * @throws SQLException
     */
    public This setFetchSize(final int fetchSize) throws SQLException {
        defaultFetchSize = stmt.getFetchSize();

        stmt.setFetchSize(fetchSize);

        return (This) this;
    }

    /**
     * Sets the max field size.
     *
     * @param max
     * @return
     * @throws SQLException
     */
    public This setMaxFieldSize(final int max) throws SQLException {
        defaultMaxFieldSize = stmt.getMaxFieldSize();

        stmt.setMaxFieldSize(max);

        return (This) this;
    }

    /**
     * Sets the max rows.
     *
     * @param max
     * @return
     * @throws SQLException
     */
    public This setMaxRows(final int max) throws SQLException {
        stmt.setMaxRows(max);

        return (This) this;
    }

    /**
     * Sets the large max rows.
     *
     * @param max
     * @return
     * @throws SQLException
     */
    public This setLargeMaxRows(final long max) throws SQLException {
        stmt.setLargeMaxRows(max);

        return (This) this;
    }

    /**
     * Sets the query timeout.
     *
     * @param seconds
     * @return
     * @throws SQLException
     */
    public This setQueryTimeout(final int seconds) throws SQLException {
        defaultQueryTimeout = stmt.getQueryTimeout();

        stmt.setQueryTimeout(seconds);

        return (This) this;
    }

    /**
     * Configure this {@code PreparedQuery/Statement} by {@code stmtSetter}.
     *
     * <pre>
     * <code>
     * final Throwables.Consumer<AbstractQuery, SQLException> commonStmtConfig = q -> q.setFetchSize(100).setQueryTimeout(60000);
     *
     * JdbcUtil.prepareQuery(sql).configStmt(commonStmtConfig).setParameters(parameters).list...
     * </code>
     * </pre>
     *
     * @param stmtSetter
     * @return
     * @throws IllegalArgumentException
     * @throws SQLException
     */
    @Beta
    public This configStmt(final Throwables.Consumer<? super Stmt, ? extends SQLException> stmtSetter) throws IllegalArgumentException, SQLException {
        checkArgNotNull(stmtSetter, s.stmtSetter);
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
     * Configure this {@code PreparedQuery/Statement} by {@code stmtSetter}.
     *
     * <pre>
     * <code>
     * final Throwables.Consumer<AbstractQuery, SQLException> commonStmtConfig = (q, stmt) -> q.setFetchSize(100).setQueryTimeout(60000);
     *
     * JdbcUtil.prepareQuery(sql).configStmt(commonStmtConfig).setParameters(parameters).list...
     * </code>
     * </pre>
     *
     * @param stmtSetter
     * @return
     * @throws IllegalArgumentException
     * @throws SQLException
     */
    @Beta
    public This configStmt(final Throwables.BiConsumer<? super This, ? super Stmt, ? extends SQLException> stmtSetter)
            throws IllegalArgumentException, SQLException {
        checkArgNotNull(stmtSetter, s.stmtSetter);

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
     * Returns an {@code OptionalBoolean} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalBoolean}.
     *
     * @return
     * @throws IllegalStateException if this is closed
     * @throws SQLException
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
     * Returns an {@code OptionalChar} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalChar}.
     *
     * @return
     * @throws IllegalStateException if this is closed
     * @throws SQLException
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
     * Returns an {@code OptionalByte} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalByte}.
     *
     * @return
     * @throws IllegalStateException if this is closed
     * @throws SQLException
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
     * Returns an {@code OptionalShort} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalShort}.
     *
     * @return
     * @throws IllegalStateException if this is closed
     * @throws SQLException
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
     * Returns an {@code OptionalInt} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalInt}.
     *
     * @return
     * @throws IllegalStateException if this is closed
     * @throws SQLException
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
     * Returns an {@code OptionalLong} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalLong}.
     *
     * @return
     * @throws IllegalStateException if this is closed
     * @throws SQLException
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
     * Returns an {@code OptionalFloat} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalFloat}.
     *
     * @return
     * @throws IllegalStateException if this is closed
     * @throws SQLException
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
     * Returns an {@code OptionalDouble} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalDouble}.
     *
     * @return
     * @throws IllegalStateException if this is closed
     * @throws SQLException
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
     * Returns a {@code Nullable<String>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @return
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public Nullable<String> queryForString() throws IllegalStateException, SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Nullable.of(rs.getString(1)) : Nullable.<String> empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    private static final Type<BigInteger> BIG_INTEGER_TYPE = Type.of(BigInteger.class);

    /**
     * Returns a {@code Nullable<BigInteger>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @return
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    @Beta
    public Nullable<BigInteger> queryForBigInteger() throws IllegalStateException, SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Nullable.of(BIG_INTEGER_TYPE.get(rs, 1)) : Nullable.<BigInteger> empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Returns a {@code Nullable<BigDecimal>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @return
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    @Beta
    public Nullable<BigDecimal> queryForBigDecimal() throws IllegalStateException, SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Nullable.of(rs.getBigDecimal(1)) : Nullable.<BigDecimal> empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Returns a {@code Nullable<java.sql.Date>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @return
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public Nullable<java.sql.Date> queryForDate() throws IllegalStateException, SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Nullable.of(rs.getDate(1)) : Nullable.<java.sql.Date> empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Returns a {@code Nullable<java.sql.Time>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @return
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public Nullable<java.sql.Time> queryForTime() throws IllegalStateException, SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Nullable.of(rs.getTime(1)) : Nullable.<java.sql.Time> empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Returns a {@code Nullable<java.sql.Timestamp>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @return
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public Nullable<java.sql.Timestamp> queryForTimestamp() throws IllegalStateException, SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Nullable.of(rs.getTimestamp(1)) : Nullable.<java.sql.Timestamp> empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Returns a {@code Nullable<byte[]>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @return
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public Nullable<byte[]> queryForBytes() throws IllegalStateException, SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Nullable.of(rs.getBytes(1)) : Nullable.<byte[]> empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Returns a {@code Nullable} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @param <V> the value type
     * @param targetType
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public <V> Nullable<V> queryForSingleResult(final Class<? extends V> targetType) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(targetType, s.targetType);
        assertNotClosed();

        return queryForSingleResult(Type.of(targetType));
    }

    /**
     * Returns a {@code Nullable} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @param <V>
     * @param targetType
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public <V> Nullable<V> queryForSingleResult(final Type<? extends V> targetType) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(targetType, s.targetType);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Nullable.of(targetType.get(rs, 1)) : Nullable.<V> empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Returns an {@code Optional} describing the value in the first row/column if it exists, otherwise return an empty {@code Optional}.
     *
     * @param <V> the value type
     * @param targetType
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public <V> Optional<V> queryForSingleNonNull(final Class<? extends V> targetType) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(targetType, s.targetType);
        assertNotClosed();

        return queryForSingleNonNull(Type.of(targetType));
    }

    /**
     * Returns an {@code Optional} describing the value in the first row/column if it exists, otherwise return an empty {@code Optional}.
     *
     * @param <V>
     * @param targetType
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public <V> Optional<V> queryForSingleNonNull(final Type<? extends V> targetType) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(targetType, s.targetType);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Optional.of(targetType.get(rs, 1)) : Optional.<V> empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Returns a {@code Nullable} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @param <V> the value type
     * @param targetType
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     */
    public <V> Nullable<V> queryForUniqueResult(final Class<? extends V> targetType)
            throws IllegalArgumentException, IllegalStateException, DuplicatedResultException, SQLException {
        checkArgNotNull(targetType, s.targetType);
        assertNotClosed();

        return queryForUniqueResult(Type.of(targetType));
    }

    /**
     * Returns a {@code Nullable} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @param <V>
     * @param targetType
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     */
    public <V> Nullable<V> queryForUniqueResult(final Type<? extends V> targetType)
            throws IllegalArgumentException, IllegalStateException, DuplicatedResultException, SQLException {
        checkArgNotNull(targetType, s.targetType);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            final Nullable<V> result = rs.next() ? Nullable.of(targetType.get(rs, 1)) : Nullable.<V> empty();

            if (result.isPresent() && rs.next()) {
                throw new DuplicatedResultException(
                        "At least two results found: " + Strings.concat(result.get(), ", ", N.convert(JdbcUtil.getColumnValue(rs, 1), targetType)));
            }

            return result;
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Returns an {@code Optional} describing the value in the first row/column if it exists, otherwise return an empty {@code Optional}.
     *
     * @param <V> the value type
     * @param targetType
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     */
    public <V> Optional<V> queryForUniqueNonNull(final Class<? extends V> targetType)
            throws IllegalArgumentException, IllegalStateException, DuplicatedResultException, SQLException {
        checkArgNotNull(targetType, s.targetType);
        assertNotClosed();

        return queryForUniqueNonNull(Type.of(targetType));
    }

    /**
     * Returns an {@code Optional} describing the value in the first row/column if it exists, otherwise return an empty {@code Optional}.
     *
     * @param <V> the value type
     * @param targetType
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     */
    public <V> Optional<V> queryForUniqueNonNull(final Type<? extends V> targetType)
            throws IllegalArgumentException, IllegalStateException, DuplicatedResultException, SQLException {
        checkArgNotNull(targetType, s.targetType);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            final Optional<V> result = rs.next() ? Optional.of(targetType.get(rs, 1)) : Optional.<V> empty();

            if (result.isPresent() && rs.next()) {
                throw new DuplicatedResultException(
                        "At least two results found: " + Strings.concat(result.get(), ", ", N.convert(JdbcUtil.getColumnValue(rs, 1), targetType)));
            }

            return result;
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @param rs
     * @param targetType
     * @param <T>
     * @return
     * @throws SQLException
     */
    private static <T> T getRow(final ResultSet rs, final Class<? extends T> targetType) throws SQLException {
        final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

        return Jdbc.BiRowMapper.to(targetType).apply(rs, columnLabels);
    }

    /**
     * Retrieves the first {@code ResultSet}.
     *
     * @return
     * @throws SQLException
     */
    public DataSet query() throws SQLException {
        return query(Jdbc.ResultExtractor.TO_DATA_SET);
    }

    /**
     * Retrieves the first {@code ResultSet}.
     *
     * @param entityClass used to fetch fields from columns
     * @return
     * @throws SQLException
     * @see {@link Jdbc.ResultExtractor#toDataSet(Class)}
     */
    public DataSet query(final Class<?> entityClass) throws SQLException {
        return query(Jdbc.ResultExtractor.toDataSet(entityClass));
    }

    /**
     * Retrieves the first {@code ResultSet}.
     *
     * @param <R>
     * @param resultExtractor Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public <R> R query(final Jdbc.ResultExtractor<? extends R> resultExtractor) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(resultExtractor, s.resultExtractor);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return JdbcUtil.checkNotResultSet(resultExtractor.apply(rs));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Retrieves the first {@code ResultSet}.
     *
     * @param <R>
     * @param resultExtractor Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public <R> R query(final Jdbc.BiResultExtractor<? extends R> resultExtractor) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(resultExtractor, s.resultExtractor);
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return JdbcUtil.checkNotResultSet(resultExtractor.apply(rs, JdbcUtil.getColumnLabelList(rs)));
        } finally {
            closeAfterExecutionIfAllowed();

        }
    }

    /**
     * Retrieves at most two {@code ResultSets}.
     *
     * @param <R1>
     * @param <R2>
     * @param resultExtractor1 Don't save/return {@code ResultSet}. It will be closed after this call.
     * @param resultExtractor2 Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return {@code R1/R2} extracted from the first two {@code ResultSets} returned by the executed procedure.
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    @Beta
    public <R1, R2> Tuple2<R1, R2> query2Resultsets(final Jdbc.BiResultExtractor<? extends R1> resultExtractor1,
            final Jdbc.BiResultExtractor<? extends R2> resultExtractor2) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(resultExtractor1, s.resultExtractor1);
        checkArgNotNull(resultExtractor2, s.resultExtractor2);
        assertNotClosed();

        Throwables.Iterator<ResultSet, SQLException> iter = null;

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
     * Retrieves at most three {@code ResultSets}.
     *
     * @param <R1>
     * @param <R2>
     * @param <R3>
     * @param resultExtractor1 Don't save/return {@code ResultSet}. It will be closed after this call.
     * @param resultExtractor2 Don't save/return {@code ResultSet}. It will be closed after this call.
     * @param resultExtractor3 Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return {@code R1/R2/R3} extracted from the first three {@code ResultSets} returned by the executed procedure.
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    @Beta
    public <R1, R2, R3> Tuple3<R1, R2, R3> query3Resultsets(final Jdbc.BiResultExtractor<? extends R1> resultExtractor1,
            final Jdbc.BiResultExtractor<? extends R2> resultExtractor2, final Jdbc.BiResultExtractor<? extends R3> resultExtractor3)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(resultExtractor1, s.resultExtractor1);
        checkArgNotNull(resultExtractor2, s.resultExtractor2);
        checkArgNotNull(resultExtractor3, s.resultExtractor3);
        assertNotClosed();

        Throwables.Iterator<ResultSet, SQLException> iter = null;

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
     * Retrieves all the {@code ResultSets}.
     *
     * @return a list of {@code DataSet} extracted from all {@code ResultSets} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public List<DataSet> queryMultiResultsets() throws SQLException {
        return queryMultiResultsets(ResultExtractor.TO_DATA_SET);
    }

    /**
     * Retrieves all the {@code ResultSets}.
     *
     * @param <R>
     * @param resultExtractor Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return a list of {@code R} extracted from all {@code ResultSets} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public <R> List<R> queryMultiResultsets(final Jdbc.ResultExtractor<? extends R> resultExtractor)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(resultExtractor, s.resultExtractor);
        assertNotClosed();

        Throwables.Iterator<ResultSet, SQLException> iter = null;

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
     * Retrieves all the {@code ResultSets}.
     *
     * @param <R>
     * @param resultExtractor Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return a list of {@code R} extracted from all {@code ResultSets} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public <R> List<R> queryMultiResultsets(final Jdbc.BiResultExtractor<? extends R> resultExtractor)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(resultExtractor, s.resultExtractor);
        assertNotClosed();

        Throwables.Iterator<ResultSet, SQLException> iter = null;

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
     *
     * @param <R>
     * @param <E>
     * @param func
     * @return
     * @throws SQLException
     * @throws E
     */
    @Beta
    public <R, E extends Exception> R queryThenApply(final Throwables.Function<? super DataSet, ? extends R, E> func) throws SQLException, E {
        return func.apply(query());
    }

    /**
     *
     * @param <R>
     * @param <E>
     * @param entityClass used to fetch fields from columns
     * @param func
     * @return
     * @throws SQLException
     * @throws E
     * @see {@link Jdbc.ResultExtractor#toDataSet(Class)}
     */
    @Beta
    public <R, E extends Exception> R queryThenApply(final Class<?> entityClass, final Throwables.Function<? super DataSet, ? extends R, E> func)
            throws SQLException, E {
        return func.apply(query(entityClass));
    }

    /**
     *
     *
     * @param <E>
     * @param action
     * @throws SQLException
     * @throws E
     */
    @Beta
    public <E extends Exception> void queryThenAccept(final Throwables.Consumer<? super DataSet, E> action) throws SQLException, E {
        action.accept(query());
    }

    /**
     *
     *
     * @param <E>
     * @param entityClass used to fetch fields from columns
     * @param action
     * @throws SQLException
     * @throws E
     * @see {@link Jdbc.ResultExtractor#toDataSet(Class)}
     */
    @Beta
    public <E extends Exception> void queryThenAccept(final Class<?> entityClass, final Throwables.Consumer<? super DataSet, E> action) throws SQLException, E {
        action.accept(query(entityClass));
    }

    //    /**
    //     *
    //     * @param <T>
    //     * @param targetType
    //     * @return
    //     * @throws DuplicatedResultException If More than one record found by the query
    //     * @throws SQLException
    //     * @deprecated replaced by {@code findOnlyOne}.
    //     */
    //    @Deprecated
    //    public <T> Optional<T> get(final Class<? extends T> targetType) throws DuplicatedResultException, SQLException {
    //        return Optional.ofNullable(gett(targetType));
    //    }
    //
    //    /**
    //     *
    //     * @param <T>
    //     * @param rowMapper
    //     * @return
    //     * @throws DuplicatedResultException If More than one record found by the query
    //     * @throws SQLException
    //     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
    //     * @deprecated replaced by {@code findOnlyOne}.
    //     */
    //    @Deprecated
    //    public <T> Optional<T> get(Jdbc.RowMapper<? extends T> rowMapper) throws DuplicatedResultException, SQLException, IllegalArgumentException {
    //        return Optional.ofNullable(gett(rowMapper));
    //    }
    //
    //    /**
    //     *
    //     * @param <T>
    //     * @param rowMapper
    //     * @return
    //     * @throws DuplicatedResultException If More than one record found by the query
    //     * @throws SQLException
    //     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
    //     * @deprecated replaced by {@code findOnlyOne}.
    //     */
    //    @Deprecated
    //    public <T> Optional<T> get(Jdbc.BiRowMapper<? extends T> rowMapper) throws DuplicatedResultException, SQLException, IllegalArgumentException {
    //        return Optional.ofNullable(gett(rowMapper));
    //    }
    //
    //    /**
    //     *
    //     * @param <T>
    //     * @param targetType
    //     * @return
    //     * @throws DuplicatedResultException If More than one record found by the query
    //     * @throws SQLException
    //     * @deprecated replaced by {@code findOnlyOneOrNull}.
    //     */
    //    @Deprecated
    //    public <T> T gett(final Class<? extends T> targetType) throws DuplicatedResultException, SQLException {
    //        return findOnlyOneOrNull(targetType);
    //    }
    //
    //    /**
    //     *
    //     * @param <T>
    //     * @param rowMapper
    //     * @return
    //     * @throws DuplicatedResultException If More than one record found by the query
    //     * @throws SQLException
    //     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
    //     * @deprecated replaced by {@code findOnlyOneOrNull}.
    //     */
    //    @Deprecated
    //    public <T> T gett(Jdbc.RowMapper<? extends T> rowMapper) throws DuplicatedResultException, SQLException, IllegalArgumentException {
    //        return findOnlyOneOrNull(rowMapper);
    //    }
    //
    //    /**
    //     *
    //     * @param <T>
    //     * @param rowMapper
    //     * @return
    //     * @throws DuplicatedResultException If More than one record found by the query
    //     * @throws SQLException
    //     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
    //     * @deprecated replaced by {@code findOnlyOneOrNull}.
    //     */
    //    @Deprecated
    //    public <T> T gett(Jdbc.BiRowMapper<? extends T> rowMapper) throws DuplicatedResultException, SQLException, IllegalArgumentException {
    //        return findOnlyOneOrNull(rowMapper);
    //    }

    /**
     *
     * @return
     * @throws DuplicatedResultException If More than one record found by the query
     * @throws SQLException
     */
    public Optional<Map<String, Object>> findOnlyOne() throws DuplicatedResultException, SQLException {
        return findOnlyOne(Jdbc.BiRowMapper.TO_MAP);
    }

    /**
     *
     *
     * @param <T>
     * @param targetType
     * @return
     * @throws DuplicatedResultException If More than one record found by the query
     * @throws SQLException
     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
     */
    public <T> Optional<T> findOnlyOne(final Class<? extends T> targetType) throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(findOnlyOneOrNull(targetType));
    }

    /**
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException If More than one record found by the query
     * @throws SQLException
     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
     */
    public <T> Optional<T> findOnlyOne(final Jdbc.RowMapper<? extends T> rowMapper) throws DuplicatedResultException, SQLException, IllegalArgumentException {
        return Optional.ofNullable(findOnlyOneOrNull(rowMapper));
    }

    /**
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException If More than one record found by the query
     * @throws SQLException
     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
     */
    public <T> Optional<T> findOnlyOne(final Jdbc.BiRowMapper<? extends T> rowMapper) throws DuplicatedResultException, SQLException, IllegalArgumentException {
        return Optional.ofNullable(findOnlyOneOrNull(rowMapper));
    }

    /**
     *
     * @return
     * @throws DuplicatedResultException If More than one record found by the query
     * @throws SQLException
     */
    public Map<String, Object> findOnlyOneOrNull() throws DuplicatedResultException, SQLException {
        return findOnlyOneOrNull(Jdbc.BiRowMapper.TO_MAP);
    }

    /**
     *
     *
     * @param <T>
     * @param targetType
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws DuplicatedResultException If More than one record found by the query
     * @throws SQLException
     */
    public <T> T findOnlyOneOrNull(final Class<? extends T> targetType)
            throws IllegalArgumentException, IllegalStateException, DuplicatedResultException, SQLException {
        checkArgNotNull(targetType, s.targetType);
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
     *
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws IllegalStateException if this is closed
     * @throws DuplicatedResultException If More than one record found by the query
     * @throws SQLException
     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
     */
    public <T> T findOnlyOneOrNull(final Jdbc.RowMapper<? extends T> rowMapper)
            throws IllegalStateException, DuplicatedResultException, SQLException, IllegalArgumentException {
        checkArgNotNull(rowMapper, s.rowMapper);
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
     *
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws IllegalStateException if this is closed
     * @throws DuplicatedResultException If More than one record found by the query
     * @throws SQLException
     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
     */
    public <T> T findOnlyOneOrNull(final Jdbc.BiRowMapper<? extends T> rowMapper)
            throws IllegalStateException, DuplicatedResultException, SQLException, IllegalArgumentException {
        checkArgNotNull(rowMapper, s.rowMapper);
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
     *
     * @return
     * @throws SQLException
     */
    public Optional<Map<String, Object>> findFirst() throws SQLException {
        return findFirst(Jdbc.BiRowMapper.TO_MAP);
    }

    /**
     *
     * @param <T>
     * @param targetType
     * @return
     * @throws SQLException
     */
    public <T> Optional<T> findFirst(final Class<? extends T> targetType) throws SQLException {
        return Optional.ofNullable(findFirstOrNull(targetType));
    }

    /**
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws SQLException
     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
     */
    public <T> Optional<T> findFirst(final Jdbc.RowMapper<? extends T> rowMapper) throws SQLException, IllegalArgumentException {
        return Optional.ofNullable(findFirstOrNull(rowMapper));
    }

    /**
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException
     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
     * @deprecated Use {@code stream(RowFilter, RowMapper).first()} instead.
     */
    @Deprecated
    public <T> Optional<T> findFirst(final Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends T> rowMapper)
            throws SQLException, IllegalArgumentException {
        return Optional.ofNullable(findFirstOrNull(rowFilter, rowMapper));
    }

    /**
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws SQLException
     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
     */
    public <T> Optional<T> findFirst(final Jdbc.BiRowMapper<? extends T> rowMapper) throws SQLException, IllegalArgumentException {
        return Optional.ofNullable(findFirstOrNull(rowMapper));
    }

    /**
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException
     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
     * @deprecated Use {@code stream(BiRowFilter, BiRowMapper).first()} instead.
     */
    @Deprecated
    public <T> Optional<T> findFirst(final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowMapper<? extends T> rowMapper)
            throws SQLException, IllegalArgumentException {
        return Optional.ofNullable(findFirstOrNull(rowFilter, rowMapper));
    }

    /**
     *
     * @return
     * @throws SQLException
     */
    public Map<String, Object> findFirstOrNull() throws SQLException {
        return findFirstOrNull(Jdbc.BiRowMapper.TO_MAP);
    }

    /**
     *
     *
     * @param <T>
     * @param targetType
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public <T> T findFirstOrNull(final Class<? extends T> targetType) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(targetType, s.targetType);
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
     *
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
     */
    public <T> T findFirstOrNull(final Jdbc.RowMapper<? extends T> rowMapper) throws IllegalStateException, SQLException, IllegalArgumentException {
        checkArgNotNull(rowMapper, s.rowMapper);
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
     *
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
     * @deprecated Use {@code stream(RowFilter, RowMapper).first()} instead.
     */
    @Deprecated
    public <T> T findFirstOrNull(final Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends T> rowMapper)
            throws IllegalStateException, SQLException, IllegalArgumentException {
        checkArgNotNull(rowFilter, s.rowFilter);
        checkArgNotNull(rowMapper, s.rowMapper);
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
     *
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
     */
    public <T> T findFirstOrNull(final Jdbc.BiRowMapper<? extends T> rowMapper) throws IllegalStateException, SQLException, IllegalArgumentException {
        checkArgNotNull(rowMapper, s.rowMapper);
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
     *
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
     * @deprecated Use {@code stream(BiRowFilter, BiRowMapper).first()} instead.
     */
    @Deprecated
    public <T> T findFirstOrNull(final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowMapper<? extends T> rowMapper)
            throws IllegalStateException, SQLException, IllegalArgumentException {
        checkArgNotNull(rowFilter, s.rowFilter);
        checkArgNotNull(rowMapper, s.rowMapper);
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
     * Lists the rows in the first {@code ResultSet}.
     *
     * @return
     * @throws SQLException
     */
    public List<Map<String, Object>> list() throws SQLException {
        return list(Jdbc.BiRowMapper.TO_MAP);
    }

    /**
     * Lists the rows in the first {@code ResultSet}.
     *
     * @param <T>
     * @param targetType
     * @return
     * @throws SQLException
     */
    public <T> List<T> list(final Class<? extends T> targetType) throws SQLException {
        return list(Jdbc.BiRowMapper.to(targetType));
    }

    /**
     * Lists the rows in the first {@code ResultSet}.
     *
     * @param <T>
     * @param targetType
     * @param maxResult
     * @return
     * @throws SQLException
     * @deprecated the result size should be limited in database server side by sql scripts.
     */
    @Deprecated
    public <T> List<T> list(final Class<? extends T> targetType, final int maxResult) throws SQLException {
        return list(Jdbc.BiRowMapper.to(targetType), maxResult);
    }

    /**
     * Lists the rows in the first {@code ResultSet}.
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws SQLException
     */
    public <T> List<T> list(final Jdbc.RowMapper<? extends T> rowMapper) throws SQLException {
        return list(rowMapper, Integer.MAX_VALUE);
    }

    /**
     * Lists the rows in the first {@code ResultSet}.
     *
     * @param <T>
     * @param rowMapper
     * @param maxResult
     * @return
     * @throws SQLException
     * @deprecated the result size should be limited in database server side by sql scripts.
     */
    @Deprecated
    public <T> List<T> list(final Jdbc.RowMapper<? extends T> rowMapper, final int maxResult) throws SQLException {
        return list(Jdbc.RowFilter.ALWAYS_TRUE, rowMapper, maxResult);
    }

    /**
     * Lists the rows in the first {@code ResultSet}.
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException
     */
    public <T> List<T> list(final Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends T> rowMapper) throws SQLException {
        return list(rowFilter, rowMapper, Integer.MAX_VALUE);
    }

    /**
     * Lists the rows in the first {@code ResultSet}.
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @param maxResult
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public <T> List<T> list(final Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends T> rowMapper, int maxResult)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, s.rowFilter);
        checkArgNotNull(rowMapper, s.rowMapper);
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
     * Lists the rows in the first {@code ResultSet}.
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws SQLException
     */
    public <T> List<T> list(final Jdbc.BiRowMapper<? extends T> rowMapper) throws SQLException {
        return list(rowMapper, Integer.MAX_VALUE);
    }

    /**
     * Lists the rows in the first {@code ResultSet}.
     *
     * @param <T>
     * @param rowMapper
     * @param maxResult
     * @return
     * @throws SQLException
     * @deprecated the result size should be limited in database server side by sql scripts.
     */
    @Deprecated
    public <T> List<T> list(final Jdbc.BiRowMapper<? extends T> rowMapper, final int maxResult) throws SQLException {
        return list(Jdbc.BiRowFilter.ALWAYS_TRUE, rowMapper, maxResult);
    }

    /**
     * Lists the rows in the first {@code ResultSet}.
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException
     */
    public <T> List<T> list(final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowMapper<? extends T> rowMapper) throws SQLException {
        return list(rowFilter, rowMapper, Integer.MAX_VALUE);
    }

    /**
     * Lists the rows in the first {@code ResultSet}.
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @param maxResult
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public <T> List<T> list(final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowMapper<? extends T> rowMapper, int maxResult)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, s.rowFilter);
        checkArgNotNull(rowMapper, s.rowMapper);
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
     * Lists all the {@code ResultSets}.
     *
     * @param <T>
     * @param targetType
     * @return the {@code List<T>} extracted from all {@code ResultSets} returned by the executed procedure.
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public <T> List<List<T>> listMultiResultsets(final Class<? extends T> targetType) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(targetType, s.targetType);
        assertNotClosed();

        try {
            JdbcUtil.execute(stmt);

            return JdbcUtil.<T> streamAllResultSets(stmt, targetType).map(CheckedStream::toList).toList();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Lists all the {@code ResultSets}.
     *
     * @param <T>
     * @param rowMapper
     * @return the {@code List<T>} extracted from all {@code ResultSets} returned by the executed procedure.
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public <T> List<List<T>> listMultiResultsets(final Jdbc.RowMapper<? extends T> rowMapper)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowMapper, s.rowMapper);
        assertNotClosed();

        try {
            JdbcUtil.execute(stmt);

            return JdbcUtil.<T> streamAllResultSets(stmt, rowMapper).map(CheckedStream::toList).toList();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Lists all the {@code ResultSets}.
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return the {@code List<T>} extracted from all {@code ResultSets} returned by the executed procedure.
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public <T> List<List<T>> listMultiResultsets(final Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends T> rowMapper)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, s.rowFilter);
        checkArgNotNull(rowMapper, s.rowMapper);
        assertNotClosed();

        try {
            JdbcUtil.execute(stmt);

            return JdbcUtil.<T> streamAllResultSets(stmt, rowFilter, rowMapper).map(CheckedStream::toList).toList();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Lists all the {@code ResultSets}.
     *
     * @param <T>
     * @param rowMapper
     * @return the {@code List<T>} extracted from all {@code ResultSets} returned by the executed procedure.
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public <T> List<List<T>> listMultiResultsets(final Jdbc.BiRowMapper<? extends T> rowMapper)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowMapper, s.rowMapper);
        assertNotClosed();

        try {
            JdbcUtil.execute(stmt);

            return JdbcUtil.<T> streamAllResultSets(stmt, rowMapper).map(CheckedStream::toList).toList();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Lists all the {@code ResultSets}.
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return the {@code List<T>} extracted from all {@code ResultSets} returned by the executed procedure.
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public <T> List<List<T>> listMultiResultsets(final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowMapper<? extends T> rowMapper)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, s.rowFilter);
        checkArgNotNull(rowMapper, s.rowMapper);
        assertNotClosed();

        try {
            JdbcUtil.execute(stmt);

            return JdbcUtil.<T> streamAllResultSets(stmt, rowFilter, rowMapper).map(CheckedStream::toList).toList();
        } finally {
            closeAfterExecutionIfAllowed();
        }

    }

    /**
     *
     * @param <T>
     * @param <R>
     * @param <E>
     * @param targetType
     * @param func
     * @return
     * @throws SQLException
     * @throws E
     */
    @Beta
    public <T, R, E extends Exception> R listThenApply(final Class<? extends T> targetType, final Throwables.Function<? super List<T>, ? extends R, E> func)
            throws SQLException, E {
        return func.apply(list(targetType));
    }

    /**
     *
     * @param <T>
     * @param <R>
     * @param <E>
     * @param rowMapper
     * @param func
     * @return
     * @throws SQLException
     * @throws E
     */
    @Beta
    public <T, R, E extends Exception> R listThenApply(final Jdbc.RowMapper<? extends T> rowMapper,
            final Throwables.Function<? super List<T>, ? extends R, E> func) throws SQLException, E {
        return func.apply(list(rowMapper));
    }

    /**
     *
     * @param <T>
     * @param <R>
     * @param <E>
     * @param rowMapper
     * @param func
     * @return
     * @throws SQLException
     * @throws E
     */
    @Beta
    public <T, R, E extends Exception> R listThenApply(final Jdbc.BiRowMapper<? extends T> rowMapper,
            final Throwables.Function<? super List<T>, ? extends R, E> func) throws SQLException, E {
        return func.apply(list(rowMapper));
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param targetType
     * @param consumer
     * @throws SQLException
     * @throws E
     */
    @Beta
    public <T, E extends Exception> void listThenAccept(final Class<? extends T> targetType, final Throwables.Consumer<? super List<T>, E> consumer)
            throws SQLException, E {
        consumer.accept(list(targetType));
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param rowMapper
     * @param consumer
     * @throws SQLException
     * @throws E
     */
    @Beta
    public <T, E extends Exception> void listThenAccept(final Jdbc.RowMapper<? extends T> rowMapper, final Throwables.Consumer<? super List<T>, E> consumer)
            throws SQLException, E {
        consumer.accept(list(rowMapper));
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param rowMapper
     * @param consumer
     * @throws SQLException
     * @throws E
     */
    @Beta
    public <T, E extends Exception> void listThenAccept(final Jdbc.BiRowMapper<? extends T> rowMapper, final Throwables.Consumer<? super List<T>, E> consumer)
            throws SQLException, E {
        consumer.accept(list(rowMapper));
    }

    // Will it cause confusion if it's called in transaction?

    /**
     * Streams the rows in the first {@code ResultSet}.
     *
     * <br />
     * lazy-execution, lazy-fetch.
     *
     * <br />
     *
     * Note: The opened {@code Connection} and {@code Statement} will be held till {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @return
     * @see {@link #query(ResultExtractor)}
     * @see {@link #query(BiResultExtractor)}
     * @see Jdbc.ResultExtractor
     * @see Jdbc.BiResultExtractor
     */
    @LazyEvaluation
    public CheckedStream<Map<String, Object>, SQLException> stream() {
        return stream(Jdbc.BiRowMapper.TO_MAP);
    }

    /**
     *
     * <br />
     *
     * Note: The opened {@code Connection} and {@code Statement} will be held till {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param <T>
     * @param targetType
     * @return
     * @see {@link #query(ResultExtractor)}
     * @see {@link #query(BiResultExtractor)}
     * @see Jdbc.ResultExtractor
     * @see Jdbc.BiResultExtractor
     */
    @LazyEvaluation
    public <T> CheckedStream<T, SQLException> stream(final Class<? extends T> targetType) {
        return stream(Jdbc.BiRowMapper.to(targetType));
    }

    // Will it cause confusion if it's called in transaction?
    /**
     * Streams the rows in the first {@code ResultSet}.
     *
     * <br />
     * lazy-execution, lazy-fetch.
     *
     * <br />
     *
     * Note: The opened {@code Connection} and {@code Statement} will be held till {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @see {@link #query(ResultExtractor)}
     * @see {@link #query(BiResultExtractor)}
     * @see Jdbc.ResultExtractor
     * @see Jdbc.BiResultExtractor
     */
    @SuppressWarnings("resource")
    @LazyEvaluation
    public <T> CheckedStream<T, SQLException> stream(final Jdbc.RowMapper<? extends T> rowMapper) throws IllegalArgumentException, IllegalStateException {
        checkArgNotNull(rowMapper, s.rowMapper);
        assertNotClosed();

        final Throwables.Supplier<ResultSet, SQLException> supplier = this::executeQuery;

        return CheckedStream.just(supplier, SQLException.class)
                .map(Throwables.Supplier::get)
                .flatMap(rs -> JdbcUtil.<T> stream(rs, rowMapper))
                .onClose(this::closeAfterExecutionIfAllowed);
    }

    // Will it cause confusion if it's called in transaction?
    /**
     * Streams the rows in the first {@code ResultSet}.
     *
     * <br />
     * lazy-execution, lazy-fetch.
     *
     * <br />
     *
     * Note: The opened {@code Connection} and {@code Statement} will be held till {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @see {@link #query(ResultExtractor)}
     * @see {@link #query(BiResultExtractor)}
     * @see Jdbc.ResultExtractor
     * @see Jdbc.BiResultExtractor
     */
    @SuppressWarnings("resource")
    @LazyEvaluation
    public <T> CheckedStream<T, SQLException> stream(final Jdbc.BiRowMapper<? extends T> rowMapper) throws IllegalArgumentException, IllegalStateException {
        checkArgNotNull(rowMapper, s.rowMapper);
        assertNotClosed();

        final Throwables.Supplier<ResultSet, SQLException> supplier = this::executeQuery;

        return CheckedStream.just(supplier, SQLException.class)
                .map(Throwables.Supplier::get)
                .flatMap(rs -> JdbcUtil.<T> stream(rs, rowMapper))
                .onClose(this::closeAfterExecutionIfAllowed);
    }

    // Will it cause confusion if it's called in transaction?
    /**
     * Streams the rows in the first {@code ResultSet}.
     *
     * <br />
     * lazy-execution, lazy-fetch.
     *
     * <br />
     *
     * Note: The opened {@code Connection} and {@code Statement} will be held till {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @see {@link #query(ResultExtractor)}
     * @see {@link #query(BiResultExtractor)}
     * @see Jdbc.ResultExtractor
     * @see Jdbc.BiResultExtractor
     */
    @SuppressWarnings("resource")
    @LazyEvaluation
    public <T> CheckedStream<T, SQLException> stream(final Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends T> rowMapper)
            throws IllegalArgumentException, IllegalStateException {
        checkArgNotNull(rowFilter, s.rowFilter);
        checkArgNotNull(rowMapper, s.rowMapper);
        assertNotClosed();

        final Throwables.Supplier<ResultSet, SQLException> supplier = this::executeQuery;

        return CheckedStream.just(supplier, SQLException.class)
                .map(Throwables.Supplier::get)
                .flatMap(rs -> JdbcUtil.<T> stream(rs, rowFilter, rowMapper))
                .onClose(this::closeAfterExecutionIfAllowed);
    }

    // Will it cause confusion if it's called in transaction?
    /**
     * Streams the rows in the first {@code ResultSet}.
     *
     * <br />
     * lazy-execution, lazy-fetch.
     *
     * <br />
     *
     * Note: The opened {@code Connection} and {@code Statement} will be held till {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @see {@link #query(ResultExtractor)}
     * @see {@link #query(BiResultExtractor)}
     * @see Jdbc.ResultExtractor
     * @see Jdbc.BiResultExtractor
     */
    @SuppressWarnings("resource")
    @LazyEvaluation
    public <T> CheckedStream<T, SQLException> stream(final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowMapper<? extends T> rowMapper)
            throws IllegalArgumentException, IllegalStateException {
        checkArgNotNull(rowFilter, s.rowFilter);
        checkArgNotNull(rowMapper, s.rowMapper);
        assertNotClosed();

        final Throwables.Supplier<ResultSet, SQLException> supplier = this::executeQuery;

        return CheckedStream.just(supplier, SQLException.class)
                .map(Throwables.Supplier::get)
                .flatMap(rs -> JdbcUtil.<T> stream(rs, rowFilter, rowMapper))
                .onClose(this::closeAfterExecutionIfAllowed);
    }

    /**
     * Streams all the {@code ResultSets}.
     *
     * <br />
     * lazy-execution, lazy-fetch.
     *
     * @param <T>
     * @param targetType
     * @return the {@code CheckedStream<T>} extracted from all {@code ResultSets} returned by the executed procedure.
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     */
    @SuppressWarnings("resource")
    public <T> CheckedStream<CheckedStream<T, SQLException>, SQLException> streamMultiResultsets(final Class<? extends T> targetType)
            throws IllegalArgumentException, IllegalStateException {
        checkArgNotNull(targetType, s.targetType);
        assertNotClosed();

        final Throwables.Supplier<Boolean, SQLException> supplier = () -> JdbcUtil.execute(stmt);

        return CheckedStream.just(supplier, SQLException.class)
                .flatMap(it -> JdbcUtil.<T> streamAllResultSets(stmt, targetType))
                .onClose(this::closeAfterExecutionIfAllowed);

    }

    /**
     * Streams all the {@code ResultSets}.
     *
     * <br />
     * lazy-execution, lazy-fetch.
     *
     * @param <T>
     * @param rowMapper
     * @return the {@code CheckedStream<T>} extracted from all {@code ResultSets} returned by the executed procedure.
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     */
    @SuppressWarnings("resource")
    public <T> CheckedStream<CheckedStream<T, SQLException>, SQLException> streamMultiResultsets(final Jdbc.RowMapper<? extends T> rowMapper)
            throws IllegalArgumentException, IllegalStateException {
        checkArgNotNull(rowMapper, s.rowMapper);
        assertNotClosed();

        final Throwables.Supplier<Boolean, SQLException> supplier = () -> JdbcUtil.execute(stmt);

        return CheckedStream.just(supplier, SQLException.class)
                .flatMap(it -> JdbcUtil.<T> streamAllResultSets(stmt, rowMapper))
                .onClose(this::closeAfterExecutionIfAllowed);
    }

    /**
     * Streams all the {@code ResultSets}.
     *
     * <br />
     * lazy-execution, lazy-fetch.
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return the {@code CheckedStream<T>} extracted from all {@code ResultSets} returned by the executed procedure.
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     */
    @SuppressWarnings("resource")
    public <T> CheckedStream<CheckedStream<T, SQLException>, SQLException> streamMultiResultsets(final Jdbc.RowFilter rowFilter,
            final Jdbc.RowMapper<? extends T> rowMapper) throws IllegalArgumentException, IllegalStateException {
        checkArgNotNull(rowFilter, s.rowFilter);
        checkArgNotNull(rowMapper, s.rowMapper);
        assertNotClosed();

        final Throwables.Supplier<Boolean, SQLException> supplier = () -> JdbcUtil.execute(stmt);

        return CheckedStream.just(supplier, SQLException.class)
                .flatMap(it -> JdbcUtil.<T> streamAllResultSets(stmt, rowFilter, rowMapper))
                .onClose(this::closeAfterExecutionIfAllowed);
    }

    /**
     * Streams all the {@code ResultSets}.
     *
     * <br />
     * lazy-execution, lazy-fetch.
     *
     * @param <T>
     * @param rowMapper
     * @return the {@code CheckedStream<T>} extracted from all {@code ResultSets} returned by the executed procedure.
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     */
    @SuppressWarnings("resource")
    public <T> CheckedStream<CheckedStream<T, SQLException>, SQLException> streamMultiResultsets(final Jdbc.BiRowMapper<? extends T> rowMapper)
            throws IllegalArgumentException, IllegalStateException {
        checkArgNotNull(rowMapper, s.rowMapper);
        assertNotClosed();

        final Throwables.Supplier<Boolean, SQLException> supplier = () -> JdbcUtil.execute(stmt);

        return CheckedStream.just(supplier, SQLException.class)
                .flatMap(it -> JdbcUtil.<T> streamAllResultSets(stmt, rowMapper))
                .onClose(this::closeAfterExecutionIfAllowed);
    }

    /**
     * Streams all the {@code ResultSets}.
     *
     * <br />
     * lazy-execution, lazy-fetch.
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return the {@code CheckedStream<T>} extracted from all {@code ResultSets} returned by the executed procedure.
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     */
    @SuppressWarnings("resource")
    public <T> CheckedStream<CheckedStream<T, SQLException>, SQLException> streamMultiResultsets(final Jdbc.BiRowFilter rowFilter,
            final Jdbc.BiRowMapper<? extends T> rowMapper) throws IllegalArgumentException, IllegalStateException {
        checkArgNotNull(rowFilter, s.rowFilter);
        checkArgNotNull(rowMapper, s.rowMapper);
        assertNotClosed();

        final Throwables.Supplier<Boolean, SQLException> supplier = () -> JdbcUtil.execute(stmt);

        return CheckedStream.just(supplier, SQLException.class)
                .flatMap(it -> JdbcUtil.<T> streamAllResultSets(stmt, rowFilter, rowMapper))
                .onClose(this::closeAfterExecutionIfAllowed);
    }

    /**
     * Note: using {@code select 1 from ...}, not {@code select count(*) from ...}.
     *
     * @return {@code true}, if there is at least one record found.
     * @throws IllegalStateException if this is closed
     * @throws SQLException
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
     * Why adding {@code notExists()}? not just {@code exists() == false} or {@code !exists()}?
     * Because {@code notExists()} is not minor case. It's a general case. {@code not exists} is better expression than {@code exists() == false} or {@code !exists()}.
     * <br />
     * Note: using {@code select 1 from ...}, not {@code select count(*) from ...}.
     *
     * @return {@code true}, if there is no record found.
     * @throws SQLException
     * @see #exists()
     */
    @Beta
    public boolean notExists() throws SQLException {
        return !exists();
    }

    /**
     *
     *
     * @param rowConsumer
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public void ifExists(final Jdbc.RowConsumer rowConsumer) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowConsumer, s.rowConsumer);
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
     *
     *
     * @param rowConsumer
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public void ifExists(final Jdbc.BiRowConsumer rowConsumer) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowConsumer, s.rowConsumer);
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
     * If exists or else.
     *
     * @param rowConsumer
     * @param orElseAction
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public void ifExistsOrElse(final Jdbc.RowConsumer rowConsumer, final Throwables.Runnable<SQLException> orElseAction)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowConsumer, s.rowConsumer);
        checkArgNotNull(orElseAction, s.orElseAction);
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
     * If exists or else.
     *
     * @param rowConsumer
     * @param orElseAction
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public void ifExistsOrElse(final Jdbc.BiRowConsumer rowConsumer, final Throwables.Runnable<SQLException> orElseAction)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowConsumer, s.rowConsumer);
        checkArgNotNull(orElseAction, s.orElseAction);
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
     * Uses {@code queryForInt()} with query {@code select count(*) from ...}
     *
     * @return
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     * @see #queryForInt()
     * @deprecated may be misused and it's inefficient.
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
     *
     *
     * @param rowFilter
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public int count(final Jdbc.RowFilter rowFilter) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, s.rowFilter);
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
     *
     *
     * @param rowFilter
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public int count(final Jdbc.BiRowFilter rowFilter) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, s.rowFilter);
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
     *
     *
     * @param rowFilter
     * @return {@code true}, if successful
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public boolean anyMatch(final Jdbc.RowFilter rowFilter) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, s.rowFilter);
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
     *
     *
     * @param rowFilter
     * @return {@code true}, if successful
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public boolean anyMatch(final Jdbc.BiRowFilter rowFilter) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, s.rowFilter);
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
     *
     *
     * @param rowFilter
     * @return {@code true}, if successful
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public boolean allMatch(final Jdbc.RowFilter rowFilter) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, s.rowFilter);
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
     *
     *
     * @param rowFilter
     * @return {@code true}, if successful
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public boolean allMatch(final Jdbc.BiRowFilter rowFilter) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, s.rowFilter);
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
     *
     * @param rowFilter
     * @return {@code true}, if successful
     * @throws SQLException
     */
    public boolean noneMatch(final Jdbc.RowFilter rowFilter) throws SQLException {
        return !anyMatch(rowFilter);
    }

    /**
     *
     * @param rowFilter
     * @return {@code true}, if successful
     * @throws SQLException
     */
    public boolean noneMatch(final Jdbc.BiRowFilter rowFilter) throws SQLException {
        return !anyMatch(rowFilter);
    }

    // TODO should set big fetch size for operation: query, list, stream, forEach, anyMatch/allMatch/noneMatch if it's not set for performance improvement?
    // May not? because there is fetchSize method to call? It's set for those methods in Dao because there is no fetch size method in Dao class. Make sense?

    /**
     *
     *
     * @param rowConsumer
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public void forEach(final Jdbc.RowConsumer rowConsumer) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowConsumer, s.rowConsumer);
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
     *
     *
     * @param rowFilter
     * @param rowConsumer
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public void forEach(final Jdbc.RowFilter rowFilter, final Jdbc.RowConsumer rowConsumer)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, s.rowFilter);
        checkArgNotNull(rowConsumer, s.rowConsumer);
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
     *
     *
     * @param rowConsumer
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public void forEach(final Jdbc.BiRowConsumer rowConsumer) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowConsumer, s.rowConsumer);
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
     *
     *
     * @param rowFilter
     * @param rowConsumer
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public void forEach(final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowConsumer rowConsumer)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, s.rowFilter);
        checkArgNotNull(rowConsumer, s.rowConsumer);
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
     *
     * @return
     * @throws SQLException
     */
    protected ResultSet executeQuery() throws SQLException {
        if (!isFetchDirectionSet) {
            stmt.setFetchDirection(ResultSet.FETCH_FORWARD);
        }

        return JdbcUtil.executeQuery(stmt);
    }

    /**
     *
     * @param rowConsumer
     * @throws SQLException
     * @see {@link RowConsumer#oneOff(Consumer)}
     */
    @Beta
    public void foreach(final Consumer<DisposableObjArray> rowConsumer) throws SQLException { //NOSONAR
        checkArgNotNull(rowConsumer, s.rowConsumer);

        forEach(Jdbc.RowConsumer.oneOff(rowConsumer));
    }

    /**
     *
     * @param entityClass used to fetch column/row value from {@code ResultSet} by the type of fields/columns defined in this class.
     * @param rowConsumer
     * @throws SQLException
     * @see {@link RowConsumer#oneOff(Class, Consumer)}
     */
    @Beta
    public void foreach(final Class<?> entityClass, final Consumer<DisposableObjArray> rowConsumer) throws SQLException { //NOSONAR
        checkArgNotNull(rowConsumer, s.rowConsumer);

        forEach(Jdbc.RowConsumer.oneOff(entityClass, rowConsumer));
    }

    /**
     * Returns the generated key if it exists.
     *
     * @param <ID>
     * @return
     * @throws SQLException
     */
    public <ID> Optional<ID> insert() throws SQLException {
        return insert((Jdbc.RowMapper<ID>) JdbcUtil.SINGLE_GENERATED_KEY_EXTRACTOR);
    }

    /**
     *
     * @param <ID>
     * @param autoGeneratedKeyExtractor
     * @return
     * @throws SQLException
     */
    public <ID> Optional<ID> insert(final Jdbc.RowMapper<? extends ID> autoGeneratedKeyExtractor) throws SQLException {
        return insert(autoGeneratedKeyExtractor, JdbcUtil.defaultIdTester);
    }

    /**
     *
     * @param <ID>
     * @param autoGeneratedKeyExtractor
     * @return
     * @throws SQLException
     */
    public <ID> Optional<ID> insert(final Jdbc.BiRowMapper<? extends ID> autoGeneratedKeyExtractor) throws SQLException {
        return insert(autoGeneratedKeyExtractor, JdbcUtil.defaultIdTester);
    }

    /**
     *
     * @param <ID>
     * @param autoGeneratedKeyExtractor
     * @return
     * @throws SQLException
     */
    <ID> Optional<ID> insert(final Jdbc.RowMapper<? extends ID> autoGeneratedKeyExtractor, final Predicate<Object> isDefaultIdTester) throws SQLException {
        assertNotClosed();
        checkArgNotNull(autoGeneratedKeyExtractor, s.autoGeneratedKeyExtractor);
        checkArgNotNull(isDefaultIdTester, s.isDefaultIdTester);

        try {
            JdbcUtil.executeUpdate(stmt);

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                final ID id = rs.next() ? autoGeneratedKeyExtractor.apply(rs) : null;
                return isDefaultIdTester.test(id) ? Optional.<ID> empty() : Optional.of(id);
            }
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @param <ID>
     * @param autoGeneratedKeyExtractor
     * @return
     * @throws SQLException
     */
    <ID> Optional<ID> insert(final Jdbc.BiRowMapper<? extends ID> autoGeneratedKeyExtractor, final Predicate<Object> isDefaultIdTester) throws SQLException {
        assertNotClosed();
        checkArgNotNull(autoGeneratedKeyExtractor, s.autoGeneratedKeyExtractor);
        checkArgNotNull(isDefaultIdTester, s.isDefaultIdTester);

        try {
            JdbcUtil.executeUpdate(stmt);

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);
                    final ID id = autoGeneratedKeyExtractor.apply(rs, columnLabels);
                    return isDefaultIdTester.test(id) ? Optional.<ID> empty() : Optional.of(id);
                } else {
                    return Optional.<ID> empty();
                }
            }
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Returns the generated key if it exists.
     *
     * @param <ID>
     * @return
     * @throws SQLException
     */
    public <ID> List<ID> batchInsert() throws SQLException {
        return batchInsert((Jdbc.RowMapper<ID>) JdbcUtil.SINGLE_GENERATED_KEY_EXTRACTOR);
    }

    /**
     *
     * @param <ID>
     * @param autoGeneratedKeyExtractor
     * @return
     * @throws SQLException
     */
    public <ID> List<ID> batchInsert(final Jdbc.RowMapper<? extends ID> autoGeneratedKeyExtractor) throws SQLException {
        return batchInsert(autoGeneratedKeyExtractor, JdbcUtil.defaultIdTester);
    }

    /**
     *
     * @param <ID>
     * @param autoGeneratedKeyExtractor
     * @return
     * @throws SQLException
     */
    public <ID> List<ID> batchInsert(final Jdbc.BiRowMapper<? extends ID> autoGeneratedKeyExtractor) throws SQLException {
        return batchInsert(autoGeneratedKeyExtractor, JdbcUtil.defaultIdTester);
    }

    /**
     *
     * @param <ID>
     * @param autoGeneratedKeyExtractor
     * @return
     * @throws SQLException
     */
    <ID> List<ID> batchInsert(final Jdbc.RowMapper<? extends ID> autoGeneratedKeyExtractor, final Predicate<Object> isDefaultIdTester) throws SQLException {
        assertNotClosed();
        checkArgNotNull(autoGeneratedKeyExtractor, s.autoGeneratedKeyExtractor);
        checkArgNotNull(isDefaultIdTester, s.isDefaultIdTester);

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
     *
     * @param <ID>
     * @param autoGeneratedKeyExtractor
     * @return
     * @throws SQLException
     */
    <ID> List<ID> batchInsert(final Jdbc.BiRowMapper<? extends ID> autoGeneratedKeyExtractor, final Predicate<Object> isDefaultIdTester) throws SQLException {
        assertNotClosed();
        checkArgNotNull(autoGeneratedKeyExtractor, s.autoGeneratedKeyExtractor);
        checkArgNotNull(isDefaultIdTester, s.isDefaultIdTester);

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
     *
     *
     * @return
     * @throws IllegalStateException if this is closed
     * @throws SQLException
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
     *
     *
     * @param <T>
     * @param autoGeneratedKeyExtractor
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public <T> Tuple2<Integer, List<T>> updateAndReturnGeneratedKeys(final Jdbc.RowMapper<T> autoGeneratedKeyExtractor)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        assertNotClosed();
        checkArgNotNull(autoGeneratedKeyExtractor, s.autoGeneratedKeyExtractor);

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
     *
     *
     * @param <T>
     * @param autoGeneratedKeyExtractor
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public <T> Tuple2<Integer, List<T>> updateAndReturnGeneratedKeys(final Jdbc.BiRowMapper<T> autoGeneratedKeyExtractor)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        assertNotClosed();
        checkArgNotNull(autoGeneratedKeyExtractor, s.autoGeneratedKeyExtractor);

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

    private <U, T> Tuple2<U, List<T>> verifyResult(final U updatedRowCount, final List<T> generatedKeysList) {

        //    if (N.allNull(generatedKeysList)) {
        //        return Tuple.of(updatedRowCount, new ArrayList<>(0));
        //    } else {
        //        return Tuple.of(updatedRowCount, generatedKeysList);
        //    }

        return Tuple.of(updatedRowCount, generatedKeysList);
    }

    /**
     *
     *
     * @return
     * @throws IllegalStateException if this is closed
     * @throws SQLException
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
     *
     *
     * @param <T>
     * @param autoGeneratedKeyExtractor
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public <T> Tuple2<int[], List<T>> batchUpdateAndReturnGeneratedKeys(final Jdbc.RowMapper<T> autoGeneratedKeyExtractor)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        assertNotClosed();
        checkArgNotNull(autoGeneratedKeyExtractor, s.autoGeneratedKeyExtractor);

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
     *
     *
     * @param <T>
     * @param autoGeneratedKeyExtractor
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public <T> Tuple2<int[], List<T>> batchUpdateAndReturnGeneratedKeys(final Jdbc.BiRowMapper<T> autoGeneratedKeyExtractor)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        assertNotClosed();
        checkArgNotNull(autoGeneratedKeyExtractor, s.autoGeneratedKeyExtractor);

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
     *
     *
     * @return
     * @throws IllegalStateException if this is closed
     * @throws SQLException
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
     * Large batch update.
     *
     * @return
     * @throws IllegalStateException if this is closed
     * @throws SQLException
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
     *
     *
     * @return {@code true}, if successful
     * @throws IllegalStateException if this is closed
     * @throws SQLException
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
     * Execute then apply.
     *
     * @param <R>
     * @param getter
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public <R> R executeThenApply(final Throwables.Function<? super Stmt, ? extends R, SQLException> getter)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(getter, s.getter);
        assertNotClosed();

        try {
            JdbcUtil.execute(stmt);

            return getter.apply(stmt);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Execute then apply.
     *
     * @param <R>
     * @param getter
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public <R> R executeThenApply(final Throwables.BiFunction<Boolean, ? super Stmt, ? extends R, SQLException> getter)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(getter, s.getter);
        assertNotClosed();

        try {
            final boolean isFirstResultSet = JdbcUtil.execute(stmt);

            return getter.apply(isFirstResultSet, stmt);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Execute then accept.
     *
     * @param consumer
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public void executeThenAccept(final Throwables.Consumer<? super Stmt, SQLException> consumer)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(consumer, s.consumer);
        assertNotClosed();

        try {
            JdbcUtil.execute(stmt);

            consumer.accept(stmt);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Execute then accept.
     *
     * @param consumer
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     * @throws SQLException
     */
    public void executeThenAccept(final Throwables.BiConsumer<Boolean, ? super Stmt, SQLException> consumer)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(consumer, s.consumer);
        assertNotClosed();

        try {
            final boolean isFirstResultSet = JdbcUtil.execute(stmt);

            consumer.accept(isFirstResultSet, stmt);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Note: The opened {@code Connection} and {@code Statement} will be held till {@code sqlAction} is completed by another thread.
     *
     * @param <R>
     * @param sqlAction
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     */
    @Beta
    public <R> ContinuableFuture<R> asyncCall(final Throwables.Function<? super This, ? extends R, SQLException> sqlAction)
            throws IllegalArgumentException, IllegalStateException {
        checkArgNotNull(sqlAction, s.sqlAction);
        assertNotClosed();

        final This q = (This) this;

        return JdbcUtil.asyncExecutor.execute(() -> sqlAction.apply(q));
    }

    /**
     * Note: The opened {@code Connection} and {@code Statement} will be held till {@code sqlAction} is completed by another thread.
     *
     * @param <R>
     * @param sqlAction
     * @param executor
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     */
    @Beta
    public <R> ContinuableFuture<R> asyncCall(final Throwables.Function<? super This, ? extends R, SQLException> sqlAction, final Executor executor)
            throws IllegalArgumentException, IllegalStateException {
        checkArgNotNull(sqlAction, s.sqlAction);
        checkArgNotNull(executor, s.executor);
        assertNotClosed();

        final This q = (This) this;

        return ContinuableFuture.call(() -> sqlAction.apply(q), executor);
    }

    /**
     * Note: The opened {@code Connection} and {@code Statement} will be held till {@code sqlAction} is completed by another thread.
     *
     * @param sqlAction
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     */
    @Beta
    public ContinuableFuture<Void> asyncRun(final Throwables.Consumer<? super This, SQLException> sqlAction)
            throws IllegalArgumentException, IllegalStateException {
        checkArgNotNull(sqlAction, s.sqlAction);
        assertNotClosed();

        final This q = (This) this;

        return JdbcUtil.asyncExecutor.execute(() -> sqlAction.accept(q));
    }

    /**
     * Note: The opened {@code Connection} and {@code Statement} will be held till {@code sqlAction} is completed by another thread.
     *
     * @param sqlAction
     * @param executor
     * @return
     * @throws IllegalArgumentException
     * @throws IllegalStateException if this is closed
     */
    @Beta
    public ContinuableFuture<Void> asyncRun(final Throwables.Consumer<? super This, SQLException> sqlAction, final Executor executor)
            throws IllegalArgumentException, IllegalStateException {
        checkArgNotNull(sqlAction, s.sqlAction);
        checkArgNotNull(executor, s.executor);
        assertNotClosed();

        final This q = (This) this;

        return ContinuableFuture.run(() -> sqlAction.accept(q), executor);
    }

    /**
     * Check arg not {@code null}.
     *
     * @param arg
     * @param argName
     */
    protected void checkArgNotNull(final Object arg, final String argName) {
        if (arg == null) {
            try {
                close();
            } catch (final Exception e) {
                JdbcUtil.logger.error("Failed to close PreparedQuery", e);
            }

            throw new IllegalArgumentException("'" + argName + "' can't be null");
        }
    }

    /**
     *
     * @param b
     * @param errorMsg
     */
    protected void checkArg(final boolean b, final String errorMsg) {
        if (!b) {
            try {
                close();
            } catch (final Exception e) {
                JdbcUtil.logger.error("Failed to close PreparedQuery", e);
            }

            throw new IllegalArgumentException(errorMsg);
        }
    }

    /**
     * Close.
     */
    @Override
    public void close() {
        if (isClosed) {
            return;
        }

        isClosed = true;

        if (closeHandler == null) {
            closeStatement();
        } else {
            try {
                closeStatement();
            } finally {
                closeHandler.run();
            }
        }
    }

    /**
     * @see JdbcUtil#execute(PreparedStatement)
     * @see JdbcUtil#executeUpdate(PreparedStatement)
     * @see JdbcUtil#executeUpdate(PreparedStatement)
     * @see JdbcUtil#clearParameters(PreparedStatement)
     */
    protected void closeStatement() {
        try {
            // stmt.clearParameters(); // cleared by JdbcUtil.clearParameters(stmt) after stmt.execute/executeQuery/executeUpdate.

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
     * Close after execution if allowed.
     *
     */
    void closeAfterExecutionIfAllowed() {
        if (isCloseAfterExecution) {
            close();
        }
    }

    /**
     * Assert not closed.
     */
    void assertNotClosed() {
        if (isClosed) {
            throw new IllegalStateException();
        }
    }
}
