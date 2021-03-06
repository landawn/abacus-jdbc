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

package com.landawn.abacus.util;

import java.io.Closeable;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

import com.landawn.abacus.DataSet;
import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.annotation.LazyEvaluation;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.type.TypeFactory;
import com.landawn.abacus.util.JdbcUtil.BiParametersSetter;
import com.landawn.abacus.util.JdbcUtil.BiResultExtractor;
import com.landawn.abacus.util.JdbcUtil.BiRowConsumer;
import com.landawn.abacus.util.JdbcUtil.BiRowFilter;
import com.landawn.abacus.util.JdbcUtil.BiRowMapper;
import com.landawn.abacus.util.JdbcUtil.ParametersSetter;
import com.landawn.abacus.util.JdbcUtil.ResultExtractor;
import com.landawn.abacus.util.JdbcUtil.RowConsumer;
import com.landawn.abacus.util.JdbcUtil.RowFilter;
import com.landawn.abacus.util.JdbcUtil.RowMapper;
import com.landawn.abacus.util.NoCachingNoUpdating.DisposableObjArray;
import com.landawn.abacus.util.StringUtil.Strings;
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
import com.landawn.abacus.util.function.Consumer;
import com.landawn.abacus.util.function.Predicate;

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
 * @author haiyangl
 * @param <Stmt>
 * @param <This>
 */
public abstract class AbstractPreparedQuery<Stmt extends PreparedStatement, This extends AbstractPreparedQuery<Stmt, This>> implements Closeable {

    static final Logger logger = LoggerFactory.getLogger(AbstractPreparedQuery.class);

    final Stmt stmt;

    boolean isFetchDirectionSet = false;

    boolean isBatch = false;

    boolean isCloseAfterExecution = true;

    boolean isClosed = false;

    Runnable closeHandler;

    AbstractPreparedQuery(Stmt stmt) {
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
     *
     * @param closeAfterExecution default is {@code true}.
     * @return
     */
    public This closeAfterExecution(boolean closeAfterExecution) {
        assertNotClosed();

        this.isCloseAfterExecution = closeAfterExecution;

        return (This) this;
    }

    boolean isCloseAfterExecution() {
        return isCloseAfterExecution;
    }

    /**
     *
     * @param closeHandler A task to execute after this {@code Query} is closed
     * @return
     */
    @SuppressWarnings("hiding")
    public This onClose(final Runnable closeHandler) {
        checkArgNotNull(closeHandler, "closeHandler");
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
     * Sets the null.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param sqlType
     * @return
     * @throws SQLException
     * @see java.sql.Types
     */
    public This setNull(int parameterIndex, int sqlType) throws SQLException {
        stmt.setNull(parameterIndex, sqlType);

        return (This) this;
    }

    /**
     * Sets the null.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param sqlType
     * @param typeName
     * @return
     * @throws SQLException
     * @see java.sql.Types
     */
    public This setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        stmt.setNull(parameterIndex, sqlType, typeName);

        return (This) this;
    }

    /**
     * Sets the boolean.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setBoolean(int parameterIndex, boolean x) throws SQLException {
        stmt.setBoolean(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the boolean.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException
     */
    public This setBoolean(int parameterIndex, Boolean x) throws SQLException {
        stmt.setBoolean(parameterIndex, N.defaultIfNull(x));

        return (This) this;
    }

    /**
     * Sets the byte.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setByte(int parameterIndex, byte x) throws SQLException {
        stmt.setByte(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the byte.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException
     */
    public This setByte(int parameterIndex, Byte x) throws SQLException {
        stmt.setByte(parameterIndex, N.defaultIfNull(x));

        return (This) this;
    }

    /**
     * Sets the short.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setShort(int parameterIndex, short x) throws SQLException {
        stmt.setShort(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the short.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException
     */
    public This setShort(int parameterIndex, Short x) throws SQLException {
        stmt.setShort(parameterIndex, N.defaultIfNull(x));

        return (This) this;
    }

    /**
     * Sets the int.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setInt(int parameterIndex, int x) throws SQLException {
        stmt.setInt(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the int.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException
     */
    public This setInt(int parameterIndex, Integer x) throws SQLException {
        stmt.setInt(parameterIndex, N.defaultIfNull(x));

        return (This) this;
    }

    /**
     * Sets the int.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setInt(int parameterIndex, char x) throws SQLException {
        stmt.setInt(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the int.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException
     */
    public This setInt(int parameterIndex, Character x) throws SQLException {
        stmt.setInt(parameterIndex, N.defaultIfNull(x));

        return (This) this;
    }

    /**
     * Sets the long.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setLong(int parameterIndex, long x) throws SQLException {
        stmt.setLong(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the long.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException
     */
    public This setLong(int parameterIndex, Long x) throws SQLException {
        stmt.setLong(parameterIndex, N.defaultIfNull(x));

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
    public This setFloat(int parameterIndex, float x) throws SQLException {
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
    public This setFloat(int parameterIndex, Float x) throws SQLException {
        stmt.setFloat(parameterIndex, N.defaultIfNull(x));

        return (This) this;
    }

    /**
     * Sets the double.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setDouble(int parameterIndex, double x) throws SQLException {
        stmt.setDouble(parameterIndex, N.defaultIfNull(x));

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
    public This setDouble(int parameterIndex, Double x) throws SQLException {
        stmt.setDouble(parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the big decimal.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException
     */
    public This setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        stmt.setBigDecimal(parameterIndex, x);

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
    public This setString(int parameterIndex, String x) throws SQLException {
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
    public This setString(int parameterIndex, CharSequence x) throws SQLException {
        stmt.setString(parameterIndex, x == null ? null : x.toString());

        return (This) this;
    }

    public This setString(int parameterIndex, char x) throws SQLException {
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
    public This setString(int parameterIndex, Character x) throws SQLException {
        stmt.setString(parameterIndex, x == null ? null : String.valueOf(x));

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
    public This setDate(int parameterIndex, java.sql.Date x) throws SQLException {
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
    public This setDate(int parameterIndex, java.util.Date x) throws SQLException {
        stmt.setDate(parameterIndex, x == null ? null : x instanceof java.sql.Date ? (java.sql.Date) x : new java.sql.Date(x.getTime()));

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
    public This setTime(int parameterIndex, java.sql.Time x) throws SQLException {
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
    public This setTime(int parameterIndex, java.util.Date x) throws SQLException {
        stmt.setTime(parameterIndex, x == null ? null : x instanceof java.sql.Time ? (java.sql.Time) x : new java.sql.Time(x.getTime()));

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
    public This setTimestamp(int parameterIndex, java.sql.Timestamp x) throws SQLException {
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
    public This setTimestamp(int parameterIndex, java.util.Date x) throws SQLException {
        stmt.setTimestamp(parameterIndex, x == null ? null : x instanceof java.sql.Timestamp ? (java.sql.Timestamp) x : new java.sql.Timestamp(x.getTime()));

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
    public This setBytes(int parameterIndex, byte[] x) throws SQLException {
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
    public This setAsciiStream(int parameterIndex, InputStream inputStream) throws SQLException {
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
    public This setAsciiStream(int parameterIndex, InputStream inputStream, long length) throws SQLException {
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
    public This setBinaryStream(int parameterIndex, InputStream inputStream) throws SQLException {
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
    public This setBinaryStream(int parameterIndex, InputStream inputStream, long length) throws SQLException {
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
    public This setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
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
    public This setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
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
    public This setNCharacterStream(int parameterIndex, Reader reader) throws SQLException {
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
    public This setNCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
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
    public This setBlob(int parameterIndex, java.sql.Blob x) throws SQLException {
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
    public This setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
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
    public This setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
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
    public This setClob(int parameterIndex, java.sql.Clob x) throws SQLException {
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
    public This setClob(int parameterIndex, Reader reader) throws SQLException {
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
    public This setClob(int parameterIndex, Reader reader, long length) throws SQLException {
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
    public This setNClob(int parameterIndex, java.sql.NClob x) throws SQLException {
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
    public This setNClob(int parameterIndex, Reader reader) throws SQLException {
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
    public This setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
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
    public This setURL(int parameterIndex, URL x) throws SQLException {
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
    public This setArray(int parameterIndex, java.sql.Array x) throws SQLException {
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
    public This setSQLXML(int parameterIndex, java.sql.SQLXML x) throws SQLException {
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
    public This setRef(int parameterIndex, java.sql.Ref x) throws SQLException {
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
    public This setRowId(int parameterIndex, java.sql.RowId x) throws SQLException {
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
    public This setObject(int parameterIndex, Object x) throws SQLException {
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
    public This setObject(int parameterIndex, Object x, int sqlType) throws SQLException {
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
    public This setObject(int parameterIndex, Object x, int sqlType, int scaleOrLength) throws SQLException {
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
    public This setObject(int parameterIndex, Object x, SQLType sqlType) throws SQLException {
        stmt.setObject(parameterIndex, x, sqlType);

        return (This) this;
    }

    /**
     * Sets the object.
     *
     * @param parameterIndex
     * @param x
     * @param sqlType
     * @param scaleOrLength
     * @return
     * @throws SQLException
     */
    public This setObject(int parameterIndex, Object x, SQLType sqlType, int scaleOrLength) throws SQLException {
        stmt.setObject(parameterIndex, x, sqlType, scaleOrLength);

        return (This) this;
    }

    public This setObject(int parameterIndex, Object x, Type<Object> type) throws SQLException {
        type.set(stmt, parameterIndex, x);

        return (This) this;
    }

    /**
     * Sets the parameters.
     *
     * @param param1
     * @param param2
     * @return
     * @throws SQLException
     */
    public This setParameters(final String param1, final String param2) throws SQLException {
        stmt.setString(1, param1);
        stmt.setString(2, param2);

        return (This) this;
    }

    /**
     * Sets the parameters.
     *
     * @param param1
     * @param param2
     * @param param3
     * @return
     * @throws SQLException
     */
    public This setParameters(final String param1, final String param2, final String param3) throws SQLException {
        stmt.setString(1, param1);
        stmt.setString(2, param2);
        stmt.setString(3, param3);

        return (This) this;
    }

    /**
     * Sets the parameters.
     *
     * @param param1
     * @param param2
     * @param param3
     * @param param4
     * @return
     * @throws SQLException
     */
    public This setParameters(final String param1, final String param2, final String param3, final String param4) throws SQLException {
        stmt.setString(1, param1);
        stmt.setString(2, param2);
        stmt.setString(3, param3);
        stmt.setString(4, param4);

        return (This) this;
    }

    /**
     * Sets the parameters.
     *
     * @param param1
     * @param param2
     * @param param3
     * @param param4
     * @param param5
     * @return
     * @throws SQLException
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
     * Sets the parameters.
     *
     * @param param1
     * @param param2
     * @param param3
     * @param param4
     * @param param5
     * @param param6
     * @return
     * @throws SQLException
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
     * Sets the parameters.
     *
     * @param param1
     * @param param2
     * @param param3
     * @param param4
     * @param param5
     * @param param6
     * @param param7
     * @return
     * @throws SQLException
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

    /**
     * Sets the parameters.
     *
     * @param param1
     * @param param2
     * @return
     * @throws SQLException
     */
    public This setParameters(final Object param1, final Object param2) throws SQLException {
        setObject(1, param1);
        setObject(2, param2);

        return (This) this;
    }

    /**
     * Sets the parameters.
     *
     * @param param1
     * @param param2
     * @param param3
     * @return
     * @throws SQLException
     */
    public This setParameters(final Object param1, final Object param2, final Object param3) throws SQLException {
        setObject(1, param1);
        setObject(2, param2);
        setObject(3, param3);

        return (This) this;
    }

    /**
     * Sets the parameters.
     *
     * @param param1
     * @param param2
     * @param param3
     * @param param4
     * @return
     * @throws SQLException
     */
    public This setParameters(final Object param1, final Object param2, final Object param3, final Object param4) throws SQLException {
        setObject(1, param1);
        setObject(2, param2);
        setObject(3, param3);
        setObject(4, param4);

        return (This) this;
    }

    /**
     * Sets the parameters.
     *
     * @param param1
     * @param param2
     * @param param3
     * @param param4
     * @param param5
     * @return
     * @throws SQLException
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
     * Sets the parameters.
     *
     * @param param1
     * @param param2
     * @param param3
     * @param param4
     * @param param5
     * @param param6
     * @return
     * @throws SQLException
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
     * Sets the parameters.
     *
     * @param param1
     * @param param2
     * @param param3
     * @param param4
     * @param param5
     * @param param6
     * @param param7
     * @return
     * @throws SQLException
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
     * Sets the parameters.
     *
     * @param param1
     * @param param2
     * @param param3
     * @param param4
     * @param param5
     * @param param6
     * @param param7
     * @param param8
     * @return
     * @throws SQLException
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
     * Sets the parameters.
     *
     * @param param1
     * @param param2
     * @param param3
     * @param param4
     * @param param5
     * @param param6
     * @param param7
     * @param param8
     * @param param9
     * @return
     * @throws SQLException
     */
    public This setParameters(final Object param1, final Object param2, final Object param3, final Object param4, final Object param5, final Object param6,
            final Object param7, final Object param8, final Object param9) throws SQLException {
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

    /**
     * Sets the parameters.
     *
     * @param parameters
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
     * @throws SQLException
     */
    public This setParameters(final Object[] parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, "parameters");

        int idx = 1;

        for (Object param : parameters) {
            setObject(idx++, param);
        }

        return (This) this;
    }

    /**
     * Sets the parameters.
     *
     * @param startParameterIndex
     * @param parameters
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
     * @throws SQLException
     */
    public This setParameters(final Collection<?> parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, "parameters");

        int idx = 1;

        for (Object param : parameters) {
            setObject(idx++, param);
        }

        return (This) this;
    }

    /**
     * Sets the parameters.
     *
     * @param paramsSetter
     * @return
     * @throws SQLException
     */
    public This setParameters(final ParametersSetter<? super Stmt> paramsSetter) throws SQLException {
        checkArgNotNull(paramsSetter, "paramsSetter");

        boolean noException = false;

        try {
            paramsSetter.accept(stmt);

            noException = true;
        } finally {
            if (noException == false) {
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
     * @throws SQLException
     */
    public <T> This setParameters(final T parameters, final BiParametersSetter<? super Stmt, ? super T> paramsSetter) throws SQLException {
        checkArgNotNull(paramsSetter, "paramsSetter");

        boolean noException = false;

        try {
            paramsSetter.accept(stmt, parameters);

            noException = true;
        } finally {
            if (noException == false) {
                close();
            }
        }

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
     * Sets the parameters.
     *
     * @param parameters
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
     * @throws SQLException
     */
    public This settParameters(final int[] parameters) throws IllegalArgumentException, SQLException {
        return settParameters(1, parameters);
    }

    /**
     * Sets the parameters.
     *
     * @param startParameterIndex
     * @param parameters
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
     * @throws SQLException
     */
    public This settParameters(int startParameterIndex, final int[] parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, "parameters");

        for (int param : parameters) {
            stmt.setInt(startParameterIndex++, param);
        }

        return (This) this;
    }

    /**
     * Sets the parameters.
     *
     * @param parameters
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
     * @throws SQLException
     */
    public This settParameters(final long[] parameters) throws IllegalArgumentException, SQLException {
        return settParameters(1, parameters);
    }

    /**
     * Sets the parameters.
     *
     * @param startParameterIndex
     * @param parameters
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
     * @throws SQLException
     */
    public This settParameters(int startParameterIndex, final long[] parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, "parameters");

        for (long param : parameters) {
            stmt.setLong(startParameterIndex++, param);
        }

        return (This) this;
    }

    /**
     * Sets the parameters.
     *
     * @param parameters
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
     * @throws SQLException
     */
    public This settParameters(final String[] parameters) throws IllegalArgumentException, SQLException {
        return settParameters(1, parameters);
    }

    /**
     * Sets the parameters.
     *
     * @param startParameterIndex
     * @param parameters
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
     * @throws SQLException
     */
    public This settParameters(int startParameterIndex, final String[] parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, "parameters");

        for (String param : parameters) {
            stmt.setString(startParameterIndex++, param);
        }

        return (This) this;
    }

    /**
     * Sets the parameters.
     *
     * @param <T>
     * @param parameters
     * @param type
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
     * @throws SQLException
     */
    public <T> This settParameters(final T[] parameters, final Class<T> type) throws IllegalArgumentException, SQLException {
        return settParameters(1, parameters, type);
    }

    /**
     * Sets the parameters.
     *
     * @param <T>
     * @param startParameterIndex
     * @param parameters
     * @param type
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
     * @throws SQLException
     */
    public <T> This settParameters(int startParameterIndex, final T[] parameters, final Class<T> type) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, "parameters");
        checkArgNotNull(type, "type");

        final Type<T> setter = N.typeOf(type);

        for (T param : parameters) {
            setter.set(stmt, startParameterIndex++, param);
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
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
     * @throws SQLException
     */
    public <T> This settParameters(final Collection<? extends T> parameters, final Class<T> type) throws IllegalArgumentException, SQLException {
        return settParameters(1, parameters, type);
    }

    /**
     * Sets the parameters.
     *
     * @param <T>
     * @param startParameterIndex
     * @param parameters
     * @param type
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
     * @throws SQLException
     */
    public <T> This settParameters(int startParameterIndex, final Collection<? extends T> parameters, final Class<T> type)
            throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, "parameters");
        checkArgNotNull(type, "type");

        final Type<T> setter = N.typeOf(type);

        for (T param : parameters) {
            setter.set(stmt, startParameterIndex++, param);
        }

        return (This) this;
    }

    /**
     *
     * @param paramsSetter
     * @return
     * @throws SQLException
     */
    @Beta
    public This settParameters(ParametersSetter<? super This> paramsSetter) throws SQLException {
        checkArgNotNull(paramsSetter, "paramsSetter");

        boolean noException = false;

        try {
            paramsSetter.accept((This) this);

            noException = true;
        } finally {
            if (noException == false) {
                close();
            }
        }

        return (This) this;
    }

    /**
     *
     * @param <T>
     * @param parameters
     * @param paramsSetter
     * @return
     * @throws SQLException
     */
    @Beta
    public <T> This settParameters(final T parameters, BiParametersSetter<? super This, ? super T> paramsSetter) throws SQLException {
        checkArgNotNull(paramsSetter, "paramsSetter");

        boolean noException = false;

        try {
            paramsSetter.accept((This) this, parameters);

            noException = true;
        } finally {
            if (noException == false) {
                close();
            }
        }

        return (This) this;
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
    //                stmt.addBatch();
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
     * @param <T>
     * @param batchParameters
     * @return
     * @throws SQLException
     */
    @SuppressWarnings("rawtypes")
    @Beta
    public <T> This addBatchParameters(final Collection<T> batchParameters) throws SQLException {
        checkArgNotNull(batchParameters, "batchParameters");

        if (N.isNullOrEmpty(batchParameters)) {
            return (This) this;
        }

        boolean noException = false;

        try {
            final T first = N.firstNonNull(batchParameters).orElseNull();

            if (first == null) {
                for (int i = 0, size = batchParameters.size(); i < size; i++) {
                    stmt.setObject(1, null);

                    stmt.addBatch();
                }
            } else {
                if (first instanceof Collection) {
                    for (Object parameters : batchParameters) {
                        setParameters((Collection) parameters);

                        stmt.addBatch();
                    }
                } else if (first instanceof Object[]) {
                    for (Object parameters : batchParameters) {
                        setParameters((Object[]) parameters);

                        stmt.addBatch();
                    }
                } else {
                    for (Object obj : batchParameters) {
                        setObject(1, obj);

                        stmt.addBatch();
                    }
                }
            }

            isBatch = batchParameters.size() > 0;

            noException = true;
        } finally {
            if (noException == false) {
                close();
            }
        }

        return (This) this;
    }

    /**
     *
     * @param <T>
     * @param batchParameters
     * @return
     * @throws SQLException
     */
    @Beta
    public <T> This addBatchParameters(final Iterator<T> batchParameters) throws SQLException {
        checkArgNotNull(batchParameters, "batchParameters");

        return addBatchParameters(N.toList(batchParameters));
    }

    /**
     *
     * @param <T>
     * @param batchParameters single batch parameters.
     * @param type
     * @return
     * @throws SQLException
     */
    @Beta
    public <T> This addBatchParameters(final Collection<? extends T> batchParameters, final Class<T> type) throws SQLException {
        checkArgNotNull(batchParameters, "batchParameters");
        checkArgNotNull(type, "type");

        boolean noException = false;
        final Type<T> setter = N.typeOf(type);

        try {
            for (T parameter : batchParameters) {
                setter.set(stmt, 1, parameter);
                stmt.addBatch();
            }

            isBatch = batchParameters.size() > 0;

            noException = true;
        } finally {
            if (noException == false) {
                close();
            }
        }

        return (This) this;
    }

    /**
     *
     * @param <T>
     * @param batchParameters single batch parameters.
     * @param type
     * @return
     * @throws SQLException
     */
    @Beta
    public <T> This addBatchParameters(final Iterator<T> batchParameters, final Class<T> type) throws SQLException {
        checkArgNotNull(batchParameters, "batchParameters");

        return addBatchParameters(N.toList(batchParameters), type);
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
    //        if (N.isNullOrEmpty(batchParameters)) {
    //            return (Q) this;
    //        }
    //
    //        boolean noException = false;
    //
    //        try {
    //            for (Object obj : batchParameters) {
    //                setObject(1, obj);
    //
    //                stmt.addBatch();
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
    public <T> This addBatchParameters(final Collection<T> batchParameters, BiParametersSetter<? super This, ? super T> parametersSetter) throws SQLException {
        checkArgNotNull(batchParameters, "batchParameters");
        checkArgNotNull(parametersSetter, "parametersSetter");

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
    public <T> This addBatchParameters(final Iterator<T> batchParameters, BiParametersSetter<? super This, ? super T> parametersSetter) throws SQLException {
        checkArgNotNull(batchParameters, "batchParameters");
        checkArgNotNull(parametersSetter, "parametersSetter");

        final This it = (This) this;
        boolean noException = false;

        try {
            final Iterator<T> iter = batchParameters;

            while (iter.hasNext()) {
                parametersSetter.accept(it, iter.next());
                stmt.addBatch();
                isBatch = true;
            }

            noException = true;
        } finally {
            if (noException == false) {
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
    //                stmt.addBatch();
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
     * @param <T>
     * @param batchParameters
     * @param parametersSetter
     * @return
     * @throws SQLException
     */
    @Beta
    public <T> This addBatchParameters(final Collection<T> batchParameters,
            Throwables.TriConsumer<? super This, ? super Stmt, ? super T, ? extends SQLException> parametersSetter) throws SQLException {
        checkArgNotNull(batchParameters, "batchParameters");
        checkArgNotNull(parametersSetter, "parametersSetter");

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
    public <T> This addBatchParameters(final Iterator<T> batchParameters,
            Throwables.TriConsumer<? super This, ? super Stmt, ? super T, ? extends SQLException> parametersSetter) throws SQLException {
        checkArgNotNull(batchParameters, "batchParameters");
        checkArgNotNull(parametersSetter, "parametersSetter");

        final This it = (This) this;
        boolean noException = false;

        try {
            final Iterator<T> iter = batchParameters;

            while (iter.hasNext()) {
                parametersSetter.accept(it, stmt, iter.next());
                stmt.addBatch();
                isBatch = true;
            }

            noException = true;
        } finally {
            if (noException == false) {
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
        stmt.addBatch();
        isBatch = true;

        return (This) this;
    }

    int defaultFetchDirection = -1;
    int defaultFetchSize = -1;
    int defaultQueryTimeout = -1;
    int defaultMaxFieldSize = -1;

    /**
     * Sets the fetch direction.
     *
     * @param direction one of <code>ResultSet.FETCH_FORWARD</code>,
     * <code>ResultSet.FETCH_REVERSE</code>, or <code>ResultSet.FETCH_UNKNOWN</code>
     * @return
     * @throws SQLException
     * @see {@link java.sql.Statement#setFetchDirection(int)}
     */
    public This setFetchDirection(FetchDirection direction) throws SQLException {
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
     * @param rows
     * @return
     * @throws SQLException
     */
    public This setFetchSize(int rows) throws SQLException {
        defaultFetchSize = stmt.getFetchSize();

        stmt.setFetchSize(rows);

        return (This) this;
    }

    /**
     * Sets the max field size.
     *
     * @param max
     * @return
     * @throws SQLException
     */
    public This setMaxFieldSize(int max) throws SQLException {
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
    public This setMaxRows(int max) throws SQLException {
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
    public This setLargeMaxRows(long max) throws SQLException {
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
    public This setQueryTimeout(int seconds) throws SQLException {
        defaultQueryTimeout = stmt.getQueryTimeout();

        stmt.setQueryTimeout(seconds);

        return (This) this;
    }

    /**
     * Configure this {@code PreparedQuery/Statement} by {@code stmtSetter}.
     *
     * <pre>
     * <code>
     * final Throwables.Consumer<AbstractPreparedQuery, SQLException> commonStmtConfig = q -> q.setFetchSize(100).setQueryTimeout(60000);
     *
     * JdbcUtil.prepareQuery(sql).configStmt(commonStmtConfig).setParameters(parameters).list...
     * </code>
     * </pre>
     *
     * @param stmtSetter
     * @return
     * @throws SQLException
     */
    @Beta
    public This configStmt(final Throwables.Consumer<? super This, ? extends SQLException> stmtSetter) throws SQLException {
        stmtSetter.accept((This) this);

        return (This) this;
    }

    /**
     * Configure this {@code PreparedQuery/Statement} by {@code stmtSetter}.
     *
     * <pre>
     * <code>
     * final Throwables.Consumer<AbstractPreparedQuery, SQLException> commonStmtConfig = (q, stmt) -> q.setFetchSize(100).setQueryTimeout(60000);
     *
     * JdbcUtil.prepareQuery(sql).configStmt(commonStmtConfig).setParameters(parameters).list...
     * </code>
     * </pre>
     *
     * @param stmtSetter
     * @return
     * @throws SQLException
     */
    @Beta
    public This configStmt(final Throwables.BiConsumer<? super This, ? super Stmt, ? extends SQLException> stmtSetter) throws SQLException {
        stmtSetter.accept((This) this, stmt);

        return (This) this;
    }

    /**
     * Query for boolean.
     *
     * @return
     * @throws SQLException
     */
    public OptionalBoolean queryForBoolean() throws SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? OptionalBoolean.of(rs.getBoolean(1)) : OptionalBoolean.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    static final Type<Character> charType = TypeFactory.getType(char.class);

    /**
     * Query for char.
     *
     * @return
     * @throws SQLException
     */
    public OptionalChar queryForChar() throws SQLException {
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
     * Query for byte.
     *
     * @return
     * @throws SQLException
     */
    public OptionalByte queryForByte() throws SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? OptionalByte.of(rs.getByte(1)) : OptionalByte.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Query for short.
     *
     * @return
     * @throws SQLException
     */
    public OptionalShort queryForShort() throws SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? OptionalShort.of(rs.getShort(1)) : OptionalShort.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Query for int.
     *
     * @return
     * @throws SQLException
     */
    public OptionalInt queryForInt() throws SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? OptionalInt.of(rs.getInt(1)) : OptionalInt.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Query for long.
     *
     * @return
     * @throws SQLException
     */
    public OptionalLong queryForLong() throws SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Query for float.
     *
     * @return
     * @throws SQLException
     */
    public OptionalFloat queryForFloat() throws SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? OptionalFloat.of(rs.getFloat(1)) : OptionalFloat.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Query for double.
     *
     * @return
     * @throws SQLException
     */
    public OptionalDouble queryForDouble() throws SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? OptionalDouble.of(rs.getDouble(1)) : OptionalDouble.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Query for string.
     *
     * @return
     * @throws SQLException
     */
    public Nullable<String> queryForString() throws SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Nullable.of(rs.getString(1)) : Nullable.<String> empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Query big decimal.
     *
     * @return
     * @throws SQLException
     */
    public Nullable<BigDecimal> queryBigDecimal() throws SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Nullable.of(rs.getBigDecimal(1)) : Nullable.<BigDecimal> empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Query for date.
     *
     * @return
     * @throws SQLException
     */
    public Nullable<java.sql.Date> queryForDate() throws SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Nullable.of(rs.getDate(1)) : Nullable.<java.sql.Date> empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Query for time.
     *
     * @return
     * @throws SQLException
     */
    public Nullable<java.sql.Time> queryForTime() throws SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Nullable.of(rs.getTime(1)) : Nullable.<java.sql.Time> empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Query for timestamp.
     *
     * @return
     * @throws SQLException
     */
    public Nullable<java.sql.Timestamp> queryForTimestamp() throws SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Nullable.of(rs.getTimestamp(1)) : Nullable.<java.sql.Timestamp> empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Returns a {@code Nullable} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @param <V> the value type
     * @param targetClass
     * @return
     * @throws SQLException
     */
    public <V> Nullable<V> queryForSingleResult(Class<V> targetClass) throws SQLException {
        checkArgNotNull(targetClass, "targetClass");
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Nullable.of(N.convert(JdbcUtil.getColumnValue(rs, 1), targetClass)) : Nullable.<V> empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Returns an {@code Optional} describing the value in the first row/column if it exists, otherwise return an empty {@code Optional}.
     *
     * @param <V> the value type
     * @param targetClass
     * @return
     * @throws SQLException
     */
    public <V> Optional<V> queryForSingleNonNull(Class<V> targetClass) throws SQLException {
        checkArgNotNull(targetClass, "targetClass");
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Optional.of(N.convert(JdbcUtil.getColumnValue(rs, 1), targetClass)) : Optional.<V> empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Returns a {@code Nullable} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     * And throws {@code DuplicatedResultException} if more than one record found.
     *
     * @param <V> the value type
     * @param targetClass
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     */
    public <V> Nullable<V> queryForUniqueResult(Class<V> targetClass) throws DuplicatedResultException, SQLException {
        checkArgNotNull(targetClass, "targetClass");
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            final Nullable<V> result = rs.next() ? Nullable.of(N.convert(JdbcUtil.getColumnValue(rs, 1), targetClass)) : Nullable.<V> empty();

            if (result.isPresent() && rs.next()) {
                throw new DuplicatedResultException(
                        "At least two results found: " + Strings.concat(result.get(), ", ", N.convert(JdbcUtil.getColumnValue(rs, 1), targetClass)));
            }

            return result;
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Returns an {@code Optional} describing the value in the first row/column if it exists, otherwise return an empty {@code Optional}.
     * And throws {@code DuplicatedResultException} if more than one record found.
     *
     * @param <V> the value type
     * @param targetClass
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     */
    public <V> Optional<V> queryForUniqueNonNull(Class<V> targetClass) throws DuplicatedResultException, SQLException {
        checkArgNotNull(targetClass, "targetClass");
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            final Optional<V> result = rs.next() ? Optional.of(N.convert(JdbcUtil.getColumnValue(rs, 1), targetClass)) : Optional.<V> empty();

            if (result.isPresent() && rs.next()) {
                throw new DuplicatedResultException(
                        "At least two results found: " + Strings.concat(result.get(), ", ", N.convert(JdbcUtil.getColumnValue(rs, 1), targetClass)));
            }

            return result;
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param rs
     * @return
     * @throws SQLException
     */
    private static <T> T get(Class<T> targetClass, ResultSet rs) throws SQLException {
        final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

        return BiRowMapper.to(targetClass).apply(rs, columnLabels);
    }

    /**
     *
     * @return
     * @throws SQLException
     */
    public DataSet query() throws SQLException {
        return query(ResultExtractor.TO_DATA_SET);
    }

    /**
     *
     * @param <R>
     * @param resultExtrator Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return
     * @throws SQLException
     */
    public <R> R query(final ResultExtractor<R> resultExtrator) throws SQLException {
        checkArgNotNull(resultExtrator, "resultExtrator");
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return JdbcUtil.checkNotResultSet(resultExtrator.apply(rs));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @param <R>
     * @param resultExtrator Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return
     * @throws SQLException
     */
    public <R> R query(final BiResultExtractor<R> resultExtrator) throws SQLException {
        checkArgNotNull(resultExtrator, "resultExtrator");
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return JdbcUtil.checkNotResultSet(resultExtrator.apply(rs, JdbcUtil.getColumnLabelList(rs)));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @return
     * @throws DuplicatedResultException If More than one record found by the query
     * @throws SQLException
     * @deprecated replaced by {@code findOnlyOne}.
     */
    @Deprecated
    public <T> Optional<T> get(final Class<T> targetClass) throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(targetClass));
    }

    /**
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException If More than one record found by the query
     * @throws SQLException
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     * @deprecated replaced by {@code findOnlyOne}.
     */
    @Deprecated
    public <T> Optional<T> get(RowMapper<T> rowMapper) throws DuplicatedResultException, SQLException, NullPointerException {
        return Optional.ofNullable(gett(rowMapper));
    }

    /**
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException If More than one record found by the query
     * @throws SQLException
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     * @deprecated replaced by {@code findOnlyOne}.
     */
    @Deprecated
    public <T> Optional<T> get(BiRowMapper<T> rowMapper) throws DuplicatedResultException, SQLException, NullPointerException {
        return Optional.ofNullable(gett(rowMapper));
    }

    /**
     * Gets the t.
     *
     * @param <T>
     * @param targetClass
     * @return
     * @throws DuplicatedResultException If More than one record found by the query
     * @throws SQLException
     * @deprecated replaced by {@code findOnlyOneOrNull}.
     */
    @Deprecated
    public <T> T gett(final Class<T> targetClass) throws DuplicatedResultException, SQLException {
        return findOnlyOneOrNull(targetClass);
    }

    /**
     * Gets the t.
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException If More than one record found by the query
     * @throws SQLException
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     * @deprecated replaced by {@code findOnlyOneOrNull}.
     */
    @Deprecated
    public <T> T gett(RowMapper<T> rowMapper) throws DuplicatedResultException, SQLException, NullPointerException {
        return findOnlyOneOrNull(rowMapper);
    }

    /**
     * Gets the t.
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException If More than one record found by the query
     * @throws SQLException
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     * @deprecated replaced by {@code findOnlyOneOrNull}.
     */
    @Deprecated
    public <T> T gett(BiRowMapper<T> rowMapper) throws DuplicatedResultException, SQLException, NullPointerException {
        return findOnlyOneOrNull(rowMapper);
    }

    /**
     *
     * @return
     * @throws DuplicatedResultException If More than one record found by the query
     * @throws SQLException
     */
    public Optional<Map<String, Object>> findOnlyOne() throws DuplicatedResultException, SQLException {
        return findOnlyOne(BiRowMapper.TO_MAP);
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @return
     * @throws DuplicatedResultException If More than one record found by the query
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     * @throws SQLException
     */
    public <T> Optional<T> findOnlyOne(final Class<T> targetClass) throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(findOnlyOneOrNull(targetClass));
    }

    /**
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException If More than one record found by the query
     * @throws SQLException
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     */
    public <T> Optional<T> findOnlyOne(RowMapper<T> rowMapper) throws DuplicatedResultException, SQLException, NullPointerException {
        return Optional.ofNullable(findOnlyOneOrNull(rowMapper));
    }

    /**
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException If More than one record found by the query
     * @throws SQLException
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     */
    public <T> Optional<T> findOnlyOne(BiRowMapper<T> rowMapper) throws DuplicatedResultException, SQLException, NullPointerException {
        return Optional.ofNullable(findOnlyOneOrNull(rowMapper));
    }

    /**
     *
     * @return
     * @throws DuplicatedResultException If More than one record found by the query
     * @throws SQLException
     */
    public Map<String, Object> findOnlyOneOrNull() throws DuplicatedResultException, SQLException {
        return findOnlyOneOrNull(BiRowMapper.TO_MAP);
    }

    /**
     * Gets the t.
     *
     * @param <T>
     * @param targetClass
     * @return
     * @throws DuplicatedResultException If More than one record found by the query
     * @throws SQLException
     */
    public <T> T findOnlyOneOrNull(final Class<T> targetClass) throws DuplicatedResultException, SQLException {
        checkArgNotNull(targetClass, "targetClass");
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            if (rs.next()) {
                final T result = Objects.requireNonNull(get(targetClass, rs));

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
     * Gets the t.
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException If More than one record found by the query
     * @throws SQLException
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     */
    public <T> T findOnlyOneOrNull(RowMapper<T> rowMapper) throws DuplicatedResultException, SQLException, NullPointerException {
        checkArgNotNull(rowMapper, "rowMapper");
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
     * Gets the t.
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException If More than one record found by the query
     * @throws SQLException
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     */
    public <T> T findOnlyOneOrNull(BiRowMapper<T> rowMapper) throws DuplicatedResultException, SQLException, NullPointerException {
        checkArgNotNull(rowMapper, "rowMapper");
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
        return findFirst(BiRowMapper.TO_MAP);
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @return
     * @throws SQLException
     */
    public <T> Optional<T> findFirst(final Class<T> targetClass) throws SQLException {
        return Optional.ofNullable(findFirstOrNull(targetClass));
    }

    /**
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws SQLException
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     */
    public <T> Optional<T> findFirst(RowMapper<T> rowMapper) throws SQLException, NullPointerException {
        return Optional.ofNullable(findFirstOrNull(rowMapper));
    }

    /**
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     * @deprecated Use {@link stream(RowFilter, RowMapper).first()} instead.
     */
    @Deprecated
    public <T> Optional<T> findFirst(final RowFilter rowFilter, RowMapper<T> rowMapper) throws SQLException, NullPointerException {
        return Optional.ofNullable(findFirstOrNull(rowFilter, rowMapper));
    }

    /**
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws SQLException
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     */
    public <T> Optional<T> findFirst(BiRowMapper<T> rowMapper) throws SQLException, NullPointerException {
        return Optional.ofNullable(findFirstOrNull(rowMapper));
    }

    /**
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     * @deprecated Use {@link stream(BiRowFilter, BiRowMapper).first()} instead.
     */
    @Deprecated
    public <T> Optional<T> findFirst(final BiRowFilter rowFilter, BiRowMapper<T> rowMapper) throws SQLException, NullPointerException {
        return Optional.ofNullable(findFirstOrNull(rowFilter, rowMapper));
    }

    /**
     *
     * @return
     * @throws SQLException
     */
    public Map<String, Object> findFirstOrNull() throws SQLException {
        return findFirstOrNull(BiRowMapper.TO_MAP);
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @return
     * @throws SQLException
     */
    public <T> T findFirstOrNull(final Class<T> targetClass) throws SQLException {
        checkArgNotNull(targetClass, "targetClass");
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            if (rs.next()) {
                return Objects.requireNonNull(get(targetClass, rs));
            }

            return null;
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws SQLException
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     */
    public <T> T findFirstOrNull(RowMapper<T> rowMapper) throws SQLException, NullPointerException {
        checkArgNotNull(rowMapper, "rowMapper");
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
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     * @deprecated Use {@link stream(RowFilter, RowMapper).first()} instead.
     */
    @Deprecated
    public <T> T findFirstOrNull(final RowFilter rowFilter, RowMapper<T> rowMapper) throws SQLException, NullPointerException {
        checkArgNotNull(rowFilter, "rowFilter");
        checkArgNotNull(rowMapper, "rowMapper");
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
     * @param <T>
     * @param rowMapper
     * @return
     * @throws SQLException
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     */
    public <T> T findFirstOrNull(BiRowMapper<T> rowMapper) throws SQLException, NullPointerException {
        checkArgNotNull(rowMapper, "rowMapper");
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
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     * @deprecated Use {@link stream(BiRowFilter, BiRowMapper).first()} instead.
     */
    @Deprecated
    public <T> T findFirstOrNull(final BiRowFilter rowFilter, BiRowMapper<T> rowMapper) throws SQLException, NullPointerException {
        checkArgNotNull(rowFilter, "rowFilter");
        checkArgNotNull(rowMapper, "rowMapper");
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
     *
     * @return
     * @throws SQLException
     */
    public List<Map<String, Object>> list() throws SQLException {
        return list(BiRowMapper.TO_MAP);
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @return
     * @throws SQLException
     */
    public <T> List<T> list(final Class<T> targetClass) throws SQLException {
        return list(BiRowMapper.to(targetClass));
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @param maxResult
     * @return
     * @throws SQLException
     * @deprecated the result size should be limited in database server side by sql scripts.
     */
    @Deprecated
    public <T> List<T> list(final Class<T> targetClass, int maxResult) throws SQLException {
        return list(BiRowMapper.to(targetClass), maxResult);
    }

    /**
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws SQLException
     */
    public <T> List<T> list(RowMapper<T> rowMapper) throws SQLException {
        return list(rowMapper, Integer.MAX_VALUE);
    }

    /**
     *
     * @param <T>
     * @param rowMapper
     * @param maxResult
     * @return
     * @throws SQLException
     * @deprecated the result size should be limited in database server side by sql scripts.
     */
    @Deprecated
    public <T> List<T> list(RowMapper<T> rowMapper, int maxResult) throws SQLException {
        return list(RowFilter.ALWAYS_TRUE, rowMapper, maxResult);
    }

    /**
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException
     */
    public <T> List<T> list(final RowFilter rowFilter, RowMapper<T> rowMapper) throws SQLException {
        return list(rowFilter, rowMapper, Integer.MAX_VALUE);
    }

    /**
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @param maxResult
     * @return
     * @throws SQLException
     */
    public <T> List<T> list(final RowFilter rowFilter, RowMapper<T> rowMapper, int maxResult) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
        checkArgNotNull(rowMapper, "rowMapper");
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
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws SQLException
     */
    public <T> List<T> list(BiRowMapper<T> rowMapper) throws SQLException {
        return list(rowMapper, Integer.MAX_VALUE);
    }

    /**
     *
     * @param <T>
     * @param rowMapper
     * @param maxResult
     * @return
     * @throws SQLException
     * @deprecated the result size should be limited in database server side by sql scripts.
     */
    @Deprecated
    public <T> List<T> list(BiRowMapper<T> rowMapper, int maxResult) throws SQLException {
        return list(BiRowFilter.ALWAYS_TRUE, rowMapper, maxResult);
    }

    /**
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException
     */
    public <T> List<T> list(final BiRowFilter rowFilter, BiRowMapper<T> rowMapper) throws SQLException {
        return list(rowFilter, rowMapper, Integer.MAX_VALUE);
    }

    /**
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @param maxResult
     * @return
     * @throws SQLException
     */
    public <T> List<T> list(final BiRowFilter rowFilter, BiRowMapper<T> rowMapper, int maxResult) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
        checkArgNotNull(rowMapper, "rowMapper");
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

    // Will it cause confusion if it's called in transaction?

    /**
     *
     * @return
     * @throws SQLException
     * @see {@link #query(ResultExtractor)}
     * @see {@link #query(BiResultExtractor)}
     * @see JdbcUtil.ResultExtractor
     * @see JdbcUtil.BiResultExtractor
     */
    @LazyEvaluation
    public ExceptionalStream<Map<String, Object>, SQLException> stream() throws SQLException {
        return stream(BiRowMapper.TO_MAP);
    }

    /**
     * lazy-execution, lazy-fetch.
     *
     * @param <T>
     * @param targetClass
     * @return
     * @throws SQLException
     * @see {@link #query(ResultExtractor)}
     * @see {@link #query(BiResultExtractor)}
     * @see JdbcUtil.ResultExtractor
     * @see JdbcUtil.BiResultExtractor
     */
    @LazyEvaluation
    public <T> ExceptionalStream<T, SQLException> stream(final Class<T> targetClass) throws SQLException {
        return stream(BiRowMapper.to(targetClass));
    }

    // Will it cause confusion if it's called in transaction?
    /**
     * lazy-execution, lazy-fetch.
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see {@link #query(ResultExtractor)}
     * @see {@link #query(BiResultExtractor)}
     * @see JdbcUtil.ResultExtractor
     * @see JdbcUtil.BiResultExtractor
     */
    @LazyEvaluation
    public <T> ExceptionalStream<T, SQLException> stream(final RowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        final Throwables.Supplier<ResultSet, SQLException> supplier = () -> executeQuery();

        return ExceptionalStream.just(supplier, SQLException.class)
                .map(it -> it.get())
                .flatMap(rs -> JdbcUtil.stream(rs, rowMapper).onClose(() -> JdbcUtil.closeQuietly(rs)))
                .onClose(() -> closeAfterExecutionIfAllowed());
    }

    // Will it cause confusion if it's called in transaction?
    /**
     * lazy-execution, lazy-fetch.
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see {@link #query(ResultExtractor)}
     * @see {@link #query(BiResultExtractor)}
     * @see JdbcUtil.ResultExtractor
     * @see JdbcUtil.BiResultExtractor
     */
    @LazyEvaluation
    public <T> ExceptionalStream<T, SQLException> stream(final BiRowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        final Throwables.Supplier<ResultSet, SQLException> supplier = () -> executeQuery();

        return ExceptionalStream.just(supplier, SQLException.class)
                .map(it -> it.get())
                .flatMap(rs -> JdbcUtil.stream(rs, rowMapper).onClose(() -> JdbcUtil.closeQuietly(rs)))
                .onClose(() -> closeAfterExecutionIfAllowed());
    }

    // Will it cause confusion if it's called in transaction?
    /**
     * lazy-execution, lazy-fetch.
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see {@link #query(ResultExtractor)}
     * @see {@link #query(BiResultExtractor)}
     * @see JdbcUtil.ResultExtractor
     * @see JdbcUtil.BiResultExtractor
     */
    @LazyEvaluation
    public <T> ExceptionalStream<T, SQLException> stream(final RowFilter rowFilter, final RowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        final Throwables.Supplier<ResultSet, SQLException> supplier = () -> executeQuery();

        return ExceptionalStream.just(supplier, SQLException.class)
                .map(it -> it.get())
                .flatMap(rs -> JdbcUtil.stream(rs, rowFilter, rowMapper).onClose(() -> JdbcUtil.closeQuietly(rs)))
                .onClose(() -> closeAfterExecutionIfAllowed());
    }

    // Will it cause confusion if it's called in transaction?
    /**
     * lazy-execution, lazy-fetch.
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see {@link #query(ResultExtractor)}
     * @see {@link #query(BiResultExtractor)}
     * @see JdbcUtil.ResultExtractor
     * @see JdbcUtil.BiResultExtractor
     */
    @LazyEvaluation
    public <T> ExceptionalStream<T, SQLException> stream(final BiRowFilter rowFilter, final BiRowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        final Throwables.Supplier<ResultSet, SQLException> supplier = () -> executeQuery();

        return ExceptionalStream.just(supplier, SQLException.class)
                .map(it -> it.get())
                .flatMap(rs -> JdbcUtil.stream(rs, rowFilter, rowMapper).onClose(() -> JdbcUtil.closeQuietly(rs)))
                .onClose(() -> closeAfterExecutionIfAllowed());
    }

    /**
     * Note: using {@code select 1 from ...}, not {@code select count(*) from ...}.
     *
     * @return true, if there is at least one record found.
     * @throws SQLException
     */
    public boolean exists() throws SQLException {
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
     * @return true, if there is no record found.
     * @throws SQLException
     * @see #exists()
     */
    @Beta
    public boolean notExists() throws SQLException {
        return !exists();
    }

    /**
     *
     * @param rowConsumer
     * @throws SQLException
     */
    public void ifExists(final RowConsumer rowConsumer) throws SQLException {
        checkArgNotNull(rowConsumer, "rowConsumer");
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
     * @param rowConsumer
     * @throws SQLException
     */
    public void ifExists(final BiRowConsumer rowConsumer) throws SQLException {
        checkArgNotNull(rowConsumer, "rowConsumer");
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
     * @throws SQLException
     */
    public void ifExistsOrElse(final RowConsumer rowConsumer, Throwables.Runnable<SQLException> orElseAction) throws SQLException {
        checkArgNotNull(rowConsumer, "rowConsumer");
        checkArgNotNull(orElseAction, "orElseAction");
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
     * @throws SQLException
     */
    public void ifExistsOrElse(final BiRowConsumer rowConsumer, Throwables.Runnable<SQLException> orElseAction) throws SQLException {
        checkArgNotNull(rowConsumer, "rowConsumer");
        checkArgNotNull(orElseAction, "orElseAction");
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
     * @throws SQLException
     * @see #queryForInt()
     * @deprecated may be misused and it's inefficient.
     */
    @Deprecated
    public int count() throws SQLException {
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
     * @param rowFilter
     * @return
     * @throws SQLException
     */
    public int count(final RowFilter rowFilter) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
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
     * @param rowFilter
     * @return
     * @throws SQLException
     */
    public int count(final BiRowFilter rowFilter) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
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
     * @param rowFilter
     * @return true, if successful
     * @throws SQLException
     */
    public boolean anyMatch(final RowFilter rowFilter) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
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
     * @param rowFilter
     * @return true, if successful
     * @throws SQLException
     */
    public boolean anyMatch(final BiRowFilter rowFilter) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
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
     * @param rowFilter
     * @return true, if successful
     * @throws SQLException
     */
    public boolean allMatch(final RowFilter rowFilter) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            while (rs.next()) {
                if (rowFilter.test(rs) == false) {
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
     * @return true, if successful
     * @throws SQLException
     */
    public boolean allMatch(final BiRowFilter rowFilter) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

            while (rs.next()) {
                if (rowFilter.test(rs, columnLabels) == false) {
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
     * @return true, if successful
     * @throws SQLException
     */
    public boolean noneMatch(final RowFilter rowFilter) throws SQLException {
        return anyMatch(rowFilter) == false;
    }

    /**
     *
     * @param rowFilter
     * @return true, if successful
     * @throws SQLException
     */
    public boolean noneMatch(final BiRowFilter rowFilter) throws SQLException {
        return anyMatch(rowFilter) == false;
    }

    /**
     *
     * @param rowConsumer
     * @throws SQLException
     */
    public void forEach(final RowConsumer rowConsumer) throws SQLException {
        checkArgNotNull(rowConsumer, "rowConsumer");
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
     * @param rowFilter
     * @param rowConsumer
     * @throws SQLException
     */
    public void forEach(final RowFilter rowFilter, final RowConsumer rowConsumer) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
        checkArgNotNull(rowConsumer, "rowConsumer");
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
     * @param rowConsumer
     * @throws SQLException
     */
    public void forEach(final BiRowConsumer rowConsumer) throws SQLException {
        checkArgNotNull(rowConsumer, "rowConsumer");
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
     * @param rowFilter
     * @param rowConsumer
     * @throws SQLException
     */
    public void forEach(final BiRowFilter rowFilter, final BiRowConsumer rowConsumer) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
        checkArgNotNull(rowConsumer, "rowConsumer");
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
    public void foreach(final Consumer<DisposableObjArray> rowConsumer) throws SQLException {
        checkArgNotNull(rowConsumer, "rowConsumer");

        forEach(RowConsumer.oneOff(rowConsumer));
    }

    /**
     *
     * @param entityClass used to fetch column/row value from {@code ResultSet} by the type of fields/columns defined in this class.
     * @param rowConsumer
     * @throws SQLException
     * @see {@link RowConsumer#oneOff(Class, Consumer)}
     */
    @Beta
    public void foreach(final Class<?> entityClass, final Consumer<DisposableObjArray> rowConsumer) throws SQLException {
        checkArgNotNull(rowConsumer, "rowConsumer");

        forEach(RowConsumer.oneOff(entityClass, rowConsumer));
    }

    /**
     * Returns the generated key if it exists.
     *
     * @param <ID>
     * @return
     * @throws SQLException
     */
    public <ID> Optional<ID> insert() throws SQLException {
        return insert((RowMapper<ID>) JdbcUtil.SINGLE_GENERATED_KEY_EXTRACTOR);
    }

    /**
     *
     * @param <ID>
     * @param autoGeneratedKeyExtractor
     * @return
     * @throws SQLException
     */
    public <ID> Optional<ID> insert(final RowMapper<ID> autoGeneratedKeyExtractor) throws SQLException {
        return insert(autoGeneratedKeyExtractor, JdbcUtil.defaultIdTester);
    }

    /**
     *
     * @param <ID>
     * @param autoGeneratedKeyExtractor
     * @return
     * @throws SQLException
     */
    public <ID> Optional<ID> insert(final BiRowMapper<ID> autoGeneratedKeyExtractor) throws SQLException {
        return insert(autoGeneratedKeyExtractor, JdbcUtil.defaultIdTester);
    }

    /**
     *
     * @param <ID>
     * @param autoGeneratedKeyExtractor
     * @return
     * @throws SQLException
     */
    <ID> Optional<ID> insert(final RowMapper<ID> autoGeneratedKeyExtractor, final Predicate<Object> isDefaultIdTester) throws SQLException {
        assertNotClosed();
        checkArgNotNull(autoGeneratedKeyExtractor, "autoGeneratedKeyExtractor");
        checkArgNotNull(isDefaultIdTester, "isDefaultIdTester");

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
    <ID> Optional<ID> insert(final BiRowMapper<ID> autoGeneratedKeyExtractor, final Predicate<Object> isDefaultIdTester) throws SQLException {
        assertNotClosed();
        checkArgNotNull(autoGeneratedKeyExtractor, "autoGeneratedKeyExtractor");
        checkArgNotNull(isDefaultIdTester, "isDefaultIdTester");

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
        return batchInsert((RowMapper<ID>) JdbcUtil.SINGLE_GENERATED_KEY_EXTRACTOR);
    }

    /**
     *
     * @param <ID>
     * @param autoGeneratedKeyExtractor
     * @return
     * @throws SQLException
     */
    public <ID> List<ID> batchInsert(final RowMapper<ID> autoGeneratedKeyExtractor) throws SQLException {
        return batchInsert(autoGeneratedKeyExtractor, JdbcUtil.defaultIdTester);
    }

    /**
     *
     * @param <ID>
     * @param autoGeneratedKeyExtractor
     * @return
     * @throws SQLException
     */
    public <ID> List<ID> batchInsert(final BiRowMapper<ID> autoGeneratedKeyExtractor) throws SQLException {
        return batchInsert(autoGeneratedKeyExtractor, JdbcUtil.defaultIdTester);
    }

    /**
     *
     * @param <ID>
     * @param autoGeneratedKeyExtractor
     * @return
     * @throws SQLException
     */
    <ID> List<ID> batchInsert(final RowMapper<ID> autoGeneratedKeyExtractor, final Predicate<Object> isDefaultIdTester) throws SQLException {
        assertNotClosed();
        checkArgNotNull(autoGeneratedKeyExtractor, "autoGeneratedKeyExtractor");
        checkArgNotNull(isDefaultIdTester, "isDefaultIdTester");

        try {
            JdbcUtil.executeBatch(stmt);

            List<ID> ids = new ArrayList<>();

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                while (rs.next()) {
                    ids.add(autoGeneratedKeyExtractor.apply(rs));
                }

                if (JdbcUtil.isAllNullIds(ids, isDefaultIdTester)) {
                    ids = new ArrayList<>();
                }

                return ids;
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
    <ID> List<ID> batchInsert(final BiRowMapper<ID> autoGeneratedKeyExtractor, final Predicate<Object> isDefaultIdTester) throws SQLException {
        assertNotClosed();
        checkArgNotNull(autoGeneratedKeyExtractor, "autoGeneratedKeyExtractor");
        checkArgNotNull(isDefaultIdTester, "isDefaultIdTester");

        try {
            JdbcUtil.executeBatch(stmt);

            List<ID> ids = new ArrayList<>();

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                while (rs.next()) {
                    ids.add(autoGeneratedKeyExtractor.apply(rs, columnLabels));
                }

                if (JdbcUtil.isAllNullIds(ids, isDefaultIdTester)) {
                    ids = new ArrayList<>();
                }

                return ids;
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
    public int update() throws SQLException {
        assertNotClosed();

        try {
            return JdbcUtil.executeUpdate(stmt);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @return
     * @throws SQLException
     */
    public int[] batchUpdate() throws SQLException {
        assertNotClosed();

        try {
            return JdbcUtil.executeBatch(stmt);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @return
     * @throws SQLException
     */
    public long largeUpdate() throws SQLException {
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
     * @throws SQLException
     */
    public long[] largeBatchUpdate() throws SQLException {
        assertNotClosed();

        try {
            return JdbcUtil.executeLargeBatch(stmt);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @return true, if successful
     * @throws SQLException
     */
    public boolean execute() throws SQLException {
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
     * @throws SQLException
     */
    public <R> R executeThenApply(final Throwables.Function<? super Stmt, ? extends R, SQLException> getter) throws SQLException {
        checkArgNotNull(getter, "getter");
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
     * @throws SQLException
     */
    public <R> R executeThenApply(final Throwables.BiFunction<Boolean, ? super Stmt, ? extends R, SQLException> getter) throws SQLException {
        checkArgNotNull(getter, "getter");
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
     * @throws SQLException
     */
    public void executeThenAccept(final Throwables.Consumer<? super Stmt, SQLException> consumer) throws SQLException {
        checkArgNotNull(consumer, "consumer");
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
     * @throws SQLException
     */
    public void executeThenAccept(final Throwables.BiConsumer<Boolean, ? super Stmt, SQLException> consumer) throws SQLException {
        checkArgNotNull(consumer, "consumer");
        assertNotClosed();

        try {
            final boolean isFirstResultSet = JdbcUtil.execute(stmt);

            consumer.accept(isFirstResultSet, stmt);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     *
     * @param <R>
     * @param sqlAction
     * @return
     */
    @Beta
    public <R> ContinuableFuture<R> asyncCall(final Throwables.Function<This, R, SQLException> sqlAction) {
        checkArgNotNull(sqlAction, "sqlAction");
        assertNotClosed();

        final This q = (This) this;

        return JdbcUtil.asyncExecutor.execute(() -> sqlAction.apply(q));
    }

    /**
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     *
     * @param <R>
     * @param sqlAction
     * @param executor
     * @return
     */
    @Beta
    public <R> ContinuableFuture<R> asyncCall(final Throwables.Function<This, R, SQLException> sqlAction, final Executor executor) {
        checkArgNotNull(sqlAction, "sqlAction");
        checkArgNotNull(executor, "executor");
        assertNotClosed();

        final This q = (This) this;

        return ContinuableFuture.call(() -> sqlAction.apply(q), executor);
    }

    /**
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     *
     * @param sqlAction
     * @return
     */
    @Beta
    public ContinuableFuture<Void> asyncRun(final Throwables.Consumer<This, SQLException> sqlAction) {
        checkArgNotNull(sqlAction, "sqlAction");
        assertNotClosed();

        final This q = (This) this;

        return JdbcUtil.asyncExecutor.execute(() -> sqlAction.accept(q));
    }

    /**
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     *
     * @param sqlAction
     * @param executor
     * @return
     */
    @Beta
    public ContinuableFuture<Void> asyncRun(final Throwables.Consumer<This, SQLException> sqlAction, final Executor executor) {
        checkArgNotNull(sqlAction, "sqlAction");
        checkArgNotNull(executor, "executor");
        assertNotClosed();

        final This q = (This) this;

        return ContinuableFuture.run(() -> sqlAction.accept(q), executor);
    }

    /**
     * Check arg not null.
     *
     * @param arg
     * @param argName
     */
    protected void checkArgNotNull(Object arg, String argName) {
        if (arg == null) {
            try {
                close();
            } catch (Exception e) {
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
    protected void checkArg(boolean b, String errorMsg) {
        if (b == false) {
            try {
                close();
            } catch (Exception e) {
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
        } catch (SQLException e) {
            logger.warn("failed to reset statement", e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     * Close after execution if allowed.
     *
     * @throws SQLException
     */
    void closeAfterExecutionIfAllowed() throws SQLException {
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