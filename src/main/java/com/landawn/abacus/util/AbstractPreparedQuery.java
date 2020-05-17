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
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.Executor;

import com.landawn.abacus.DataSet;
import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.type.TypeFactory;
import com.landawn.abacus.util.ExceptionalStream.ExceptionalIterator;
import com.landawn.abacus.util.JdbcUtil.BiParametersSetter;
import com.landawn.abacus.util.JdbcUtil.BiResultExtractor;
import com.landawn.abacus.util.JdbcUtil.BiRowConsumer;
import com.landawn.abacus.util.JdbcUtil.BiRowFilter;
import com.landawn.abacus.util.JdbcUtil.BiRowMapper;
import com.landawn.abacus.util.JdbcUtil.ParametersSetter;
import com.landawn.abacus.util.JdbcUtil.ResultExtractor;
import com.landawn.abacus.util.JdbcUtil.RowConsumer;
import com.landawn.abacus.util.JdbcUtil.RowFilter;
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
import com.landawn.abacus.util.stream.Stream;

/**
 * The backed {@code PreparedStatement/CallableStatement} will be closed by default
 * after any execution methods(which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/...).
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
 * @param <S>
 * @param <Q>
 */
abstract class AbstractPreparedQuery<S extends PreparedStatement, Q extends AbstractPreparedQuery<S, Q>> implements Closeable {

    final S stmt;

    boolean isFetchDirectionSet = false;

    boolean isBatch = false;

    boolean isCloseAfterExecution = true;

    boolean isClosed = false;

    Runnable closeHandler;

    /**
     * Instantiates a new abstract prepared query.
     *
     * @param stmt
     */
    AbstractPreparedQuery(S stmt) {
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
    public Q closeAfterExecution(boolean closeAfterExecution) {
        assertNotClosed();

        this.isCloseAfterExecution = closeAfterExecution;

        return (Q) this;
    }

    /**
     *
     * @return
     */
    boolean isCloseAfterExecution() {
        return isCloseAfterExecution;
    }

    /**
     *
     * @param closeHandler A task to execute after this {@code Query} is closed
     * @return
     */
    public Q onClose(final Runnable closeHandler) {
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

        return (Q) this;
    }

    /**
     * Sets the null.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param sqlType
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setNull(int parameterIndex, int sqlType) throws SQLException {
        stmt.setNull(parameterIndex, sqlType);

        return (Q) this;
    }

    /**
     * Sets the null.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param sqlType
     * @param typeName
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        stmt.setNull(parameterIndex, sqlType, typeName);

        return (Q) this;
    }

    /**
     * Sets the boolean.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setBoolean(int parameterIndex, boolean x) throws SQLException {
        stmt.setBoolean(parameterIndex, x);

        return (Q) this;
    }

    /**
     * Sets the boolean.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setBoolean(int parameterIndex, Boolean x) throws SQLException {
        stmt.setBoolean(parameterIndex, N.defaultIfNull(x));

        return (Q) this;
    }

    /**
     * Sets the byte.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setByte(int parameterIndex, byte x) throws SQLException {
        stmt.setByte(parameterIndex, x);

        return (Q) this;
    }

    /**
     * Sets the byte.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setByte(int parameterIndex, Byte x) throws SQLException {
        stmt.setByte(parameterIndex, N.defaultIfNull(x));

        return (Q) this;
    }

    /**
     * Sets the short.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setShort(int parameterIndex, short x) throws SQLException {
        stmt.setShort(parameterIndex, x);

        return (Q) this;
    }

    /**
     * Sets the short.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setShort(int parameterIndex, Short x) throws SQLException {
        stmt.setShort(parameterIndex, N.defaultIfNull(x));

        return (Q) this;
    }

    /**
     * Sets the int.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setInt(int parameterIndex, int x) throws SQLException {
        stmt.setInt(parameterIndex, x);

        return (Q) this;
    }

    /**
     * Sets the int.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setInt(int parameterIndex, Integer x) throws SQLException {
        stmt.setInt(parameterIndex, N.defaultIfNull(x));

        return (Q) this;
    }

    /**
     * Sets the int.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setInt(int parameterIndex, char x) throws SQLException {
        stmt.setInt(parameterIndex, x);

        return (Q) this;
    }

    /**
     * Sets the int.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setInt(int parameterIndex, Character x) throws SQLException {
        stmt.setInt(parameterIndex, N.defaultIfNull(x));

        return (Q) this;
    }

    /**
     * Sets the long.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setLong(int parameterIndex, long x) throws SQLException {
        stmt.setLong(parameterIndex, x);

        return (Q) this;
    }

    /**
     * Sets the long.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setLong(int parameterIndex, Long x) throws SQLException {
        stmt.setLong(parameterIndex, N.defaultIfNull(x));

        return (Q) this;
    }

    /**
     * Sets the float.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setFloat(int parameterIndex, float x) throws SQLException {
        stmt.setFloat(parameterIndex, x);

        return (Q) this;
    }

    /**
     * Sets the float.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setFloat(int parameterIndex, Float x) throws SQLException {
        stmt.setFloat(parameterIndex, N.defaultIfNull(x));

        return (Q) this;
    }

    /**
     * Sets the double.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setDouble(int parameterIndex, double x) throws SQLException {
        stmt.setDouble(parameterIndex, N.defaultIfNull(x));

        return (Q) this;
    }

    /**
     * Sets the double.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setDouble(int parameterIndex, Double x) throws SQLException {
        stmt.setDouble(parameterIndex, x);

        return (Q) this;
    }

    /**
     * Sets the big decimal.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        stmt.setBigDecimal(parameterIndex, x);

        return (Q) this;
    }

    /**
     * Sets the string.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setString(int parameterIndex, String x) throws SQLException {
        stmt.setString(parameterIndex, x);

        return (Q) this;
    }

    /**
     * Sets the string.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setString(int parameterIndex, CharSequence x) throws SQLException {
        stmt.setString(parameterIndex, x == null ? null : x.toString());

        return (Q) this;
    }

    public Q setString(int parameterIndex, char x) throws SQLException {
        stmt.setString(parameterIndex, String.valueOf(x));

        return (Q) this;
    }

    /**
     * Sets the String.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setString(int parameterIndex, Character x) throws SQLException {
        stmt.setString(parameterIndex, x == null ? null : String.valueOf(x));

        return (Q) this;
    }

    /**
     * Sets the date.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setDate(int parameterIndex, java.sql.Date x) throws SQLException {
        stmt.setDate(parameterIndex, x);

        return (Q) this;
    }

    /**
     * Sets the date.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setDate(int parameterIndex, java.util.Date x) throws SQLException {
        stmt.setDate(parameterIndex, x == null ? null : x instanceof java.sql.Date ? (java.sql.Date) x : new java.sql.Date(x.getTime()));

        return (Q) this;
    }

    /**
     * Sets the time.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setTime(int parameterIndex, java.sql.Time x) throws SQLException {
        stmt.setTime(parameterIndex, x);

        return (Q) this;
    }

    /**
     * Sets the time.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setTime(int parameterIndex, java.util.Date x) throws SQLException {
        stmt.setTime(parameterIndex, x == null ? null : x instanceof java.sql.Time ? (java.sql.Time) x : new java.sql.Time(x.getTime()));

        return (Q) this;
    }

    /**
     * Sets the timestamp.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setTimestamp(int parameterIndex, java.sql.Timestamp x) throws SQLException {
        stmt.setTimestamp(parameterIndex, x);

        return (Q) this;
    }

    /**
     * Sets the timestamp.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setTimestamp(int parameterIndex, java.util.Date x) throws SQLException {
        stmt.setTimestamp(parameterIndex, x == null ? null : x instanceof java.sql.Timestamp ? (java.sql.Timestamp) x : new java.sql.Timestamp(x.getTime()));

        return (Q) this;
    }

    /**
     * Sets the bytes.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setBytes(int parameterIndex, byte[] x) throws SQLException {
        stmt.setBytes(parameterIndex, x);

        return (Q) this;
    }

    /**
     * Sets the ascii stream.
     *
     * @param parameterIndex
     * @param inputStream
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setAsciiStream(int parameterIndex, InputStream inputStream) throws SQLException {
        stmt.setAsciiStream(parameterIndex, inputStream);

        return (Q) this;
    }

    /**
     * Sets the ascii stream.
     *
     * @param parameterIndex
     * @param inputStream
     * @param length
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setAsciiStream(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        stmt.setAsciiStream(parameterIndex, inputStream, length);

        return (Q) this;
    }

    /**
     * Sets the binary stream.
     *
     * @param parameterIndex
     * @param inputStream
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setBinaryStream(int parameterIndex, InputStream inputStream) throws SQLException {
        stmt.setBinaryStream(parameterIndex, inputStream);

        return (Q) this;
    }

    /**
     * Sets the binary stream.
     *
     * @param parameterIndex
     * @param inputStream
     * @param length
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setBinaryStream(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        stmt.setBinaryStream(parameterIndex, inputStream, length);

        return (Q) this;
    }

    /**
     * Sets the character stream.
     *
     * @param parameterIndex
     * @param reader
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        stmt.setCharacterStream(parameterIndex, reader);

        return (Q) this;
    }

    /**
     * Sets the character stream.
     *
     * @param parameterIndex
     * @param reader
     * @param length
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        stmt.setCharacterStream(parameterIndex, reader, length);

        return (Q) this;
    }

    /**
     * Sets the N character stream.
     *
     * @param parameterIndex
     * @param reader
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setNCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        stmt.setNCharacterStream(parameterIndex, reader);

        return (Q) this;
    }

    /**
     * Sets the N character stream.
     *
     * @param parameterIndex
     * @param reader
     * @param length
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setNCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        stmt.setNCharacterStream(parameterIndex, reader, length);

        return (Q) this;
    }

    /**
     * Sets the blob.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setBlob(int parameterIndex, java.sql.Blob x) throws SQLException {
        stmt.setBlob(parameterIndex, x);

        return (Q) this;
    }

    /**
     * Sets the blob.
     *
     * @param parameterIndex
     * @param inputStream
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        stmt.setBlob(parameterIndex, inputStream);

        return (Q) this;
    }

    /**
     * Sets the blob.
     *
     * @param parameterIndex
     * @param inputStream
     * @param length
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        stmt.setBlob(parameterIndex, inputStream, length);

        return (Q) this;
    }

    /**
     * Sets the clob.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setClob(int parameterIndex, java.sql.Clob x) throws SQLException {
        stmt.setClob(parameterIndex, x);

        return (Q) this;
    }

    /**
     * Sets the clob.
     *
     * @param parameterIndex
     * @param reader
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setClob(int parameterIndex, Reader reader) throws SQLException {
        stmt.setClob(parameterIndex, reader);

        return (Q) this;
    }

    /**
     * Sets the clob.
     *
     * @param parameterIndex
     * @param reader
     * @param length
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        stmt.setClob(parameterIndex, reader, length);

        return (Q) this;
    }

    /**
     * Sets the N clob.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setNClob(int parameterIndex, java.sql.NClob x) throws SQLException {
        stmt.setNClob(parameterIndex, x);

        return (Q) this;
    }

    /**
     * Sets the N clob.
     *
     * @param parameterIndex
     * @param reader
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setNClob(int parameterIndex, Reader reader) throws SQLException {
        stmt.setNClob(parameterIndex, reader);

        return (Q) this;
    }

    /**
     * Sets the N clob.
     *
     * @param parameterIndex
     * @param reader
     * @param length
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        stmt.setNClob(parameterIndex, reader, length);

        return (Q) this;
    }

    /**
     * Sets the URL.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setURL(int parameterIndex, URL x) throws SQLException {
        stmt.setURL(parameterIndex, x);

        return (Q) this;
    }

    /**
     * Sets the array.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setArray(int parameterIndex, java.sql.Array x) throws SQLException {
        stmt.setArray(parameterIndex, x);

        return (Q) this;
    }

    /**
     * Sets the SQLXML.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setSQLXML(int parameterIndex, java.sql.SQLXML x) throws SQLException {
        stmt.setSQLXML(parameterIndex, x);

        return (Q) this;
    }

    /**
     * Sets the ref.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setRef(int parameterIndex, java.sql.Ref x) throws SQLException {
        stmt.setRef(parameterIndex, x);

        return (Q) this;
    }

    /**
     * Sets the row id.
     *
     * @param parameterIndex
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setRowId(int parameterIndex, java.sql.RowId x) throws SQLException {
        stmt.setRowId(parameterIndex, x);

        return (Q) this;
    }

    /**
     * Sets the object.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setObject(int parameterIndex, Object x) throws SQLException {
        if (x == null) {
            stmt.setObject(parameterIndex, x);
        } else {
            N.typeOf(x.getClass()).set(stmt, parameterIndex, x);
        }

        return (Q) this;
    }

    /**
     * Sets the object.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @param sqlType
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setObject(int parameterIndex, Object x, int sqlType) throws SQLException {
        stmt.setObject(parameterIndex, x, sqlType);

        return (Q) this;
    }

    /**
     * Sets the object.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param x
     * @param sqlType
     * @param scaleOrLength
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setObject(int parameterIndex, Object x, int sqlType, int scaleOrLength) throws SQLException {
        stmt.setObject(parameterIndex, x, sqlType, scaleOrLength);

        return (Q) this;
    }

    /**
     * Sets the object.
     *
     * @param parameterIndex
     * @param x
     * @param sqlType
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setObject(int parameterIndex, Object x, SQLType sqlType) throws SQLException {
        stmt.setObject(parameterIndex, x, sqlType);

        return (Q) this;
    }

    /**
     * Sets the object.
     *
     * @param parameterIndex
     * @param x
     * @param sqlType
     * @param scaleOrLength
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setObject(int parameterIndex, Object x, SQLType sqlType, int scaleOrLength) throws SQLException {
        stmt.setObject(parameterIndex, x, sqlType, scaleOrLength);

        return (Q) this;
    }

    public Q setObject(int parameterIndex, Object x, Type<Object> type) throws SQLException {
        type.set(stmt, parameterIndex, x);

        return (Q) this;
    }

    /**
     * Sets the parameters.
     *
     * @param param1
     * @param param2
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setParameters(final String param1, final String param2) throws SQLException {
        stmt.setString(1, param1);
        stmt.setString(2, param2);

        return (Q) this;
    }

    /**
     * Sets the parameters.
     *
     * @param param1
     * @param param2
     * @param param3
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setParameters(final String param1, final String param2, final String param3) throws SQLException {
        stmt.setString(1, param1);
        stmt.setString(2, param2);
        stmt.setString(3, param3);

        return (Q) this;
    }

    /**
     * Sets the parameters.
     *
     * @param param1
     * @param param2
     * @param param3
     * @param param4
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setParameters(final String param1, final String param2, final String param3, final String param4) throws SQLException {
        stmt.setString(1, param1);
        stmt.setString(2, param2);
        stmt.setString(3, param3);
        stmt.setString(4, param4);

        return (Q) this;
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
     * @throws SQLException the SQL exception
     */
    public Q setParameters(final String param1, final String param2, final String param3, final String param4, final String param5) throws SQLException {
        stmt.setString(1, param1);
        stmt.setString(2, param2);
        stmt.setString(3, param3);
        stmt.setString(4, param4);
        stmt.setString(5, param5);

        return (Q) this;
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
     * @throws SQLException the SQL exception
     */
    public Q setParameters(final String param1, final String param2, final String param3, final String param4, final String param5, final String param6)
            throws SQLException {
        stmt.setString(1, param1);
        stmt.setString(2, param2);
        stmt.setString(3, param3);
        stmt.setString(4, param4);
        stmt.setString(5, param5);
        stmt.setString(6, param6);

        return (Q) this;
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
     * @throws SQLException the SQL exception
     */
    public Q setParameters(final String param1, final String param2, final String param3, final String param4, final String param5, final String param6,
            final String param7) throws SQLException {
        stmt.setString(1, param1);
        stmt.setString(2, param2);
        stmt.setString(3, param3);
        stmt.setString(4, param4);
        stmt.setString(5, param5);
        stmt.setString(6, param6);
        stmt.setString(7, param7);

        return (Q) this;
    }

    /**
     * Sets the parameters.
     *
     * @param param1
     * @param param2
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setParameters(final Object param1, final Object param2) throws SQLException {
        setObject(1, param1);
        setObject(2, param2);

        return (Q) this;
    }

    /**
     * Sets the parameters.
     *
     * @param param1
     * @param param2
     * @param param3
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setParameters(final Object param1, final Object param2, final Object param3) throws SQLException {
        setObject(1, param1);
        setObject(2, param2);
        setObject(3, param3);

        return (Q) this;
    }

    /**
     * Sets the parameters.
     *
     * @param param1
     * @param param2
     * @param param3
     * @param param4
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setParameters(final Object param1, final Object param2, final Object param3, final Object param4) throws SQLException {
        setObject(1, param1);
        setObject(2, param2);
        setObject(3, param3);
        setObject(4, param4);

        return (Q) this;
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
     * @throws SQLException the SQL exception
     */
    public Q setParameters(final Object param1, final Object param2, final Object param3, final Object param4, final Object param5) throws SQLException {
        setObject(1, param1);
        setObject(2, param2);
        setObject(3, param3);
        setObject(4, param4);
        setObject(5, param5);

        return (Q) this;
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
     * @throws SQLException the SQL exception
     */
    public Q setParameters(final Object param1, final Object param2, final Object param3, final Object param4, final Object param5, final Object param6)
            throws SQLException {
        setObject(1, param1);
        setObject(2, param2);
        setObject(3, param3);
        setObject(4, param4);
        setObject(5, param5);
        setObject(6, param6);

        return (Q) this;
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
     * @throws SQLException the SQL exception
     */
    public Q setParameters(final Object param1, final Object param2, final Object param3, final Object param4, final Object param5, final Object param6,
            final Object param7) throws SQLException {
        setObject(1, param1);
        setObject(2, param2);
        setObject(3, param3);
        setObject(4, param4);
        setObject(5, param5);
        setObject(6, param6);
        setObject(7, param7);

        return (Q) this;
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
     * @throws SQLException the SQL exception
     */
    public Q setParameters(final Object param1, final Object param2, final Object param3, final Object param4, final Object param5, final Object param6,
            final Object param7, final Object param8) throws SQLException {
        setObject(1, param1);
        setObject(2, param2);
        setObject(3, param3);
        setObject(4, param4);
        setObject(5, param5);
        setObject(6, param6);
        setObject(7, param7);
        setObject(8, param8);

        return (Q) this;
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
     * @throws SQLException the SQL exception
     */
    public Q setParameters(final Object param1, final Object param2, final Object param3, final Object param4, final Object param5, final Object param6,
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

        return (Q) this;
    }

    /**
     * Sets the parameters.
     *
     * @param parameters
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
     * @throws SQLException the SQL exception
     */
    public Q setParameters(final Object[] parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, "parameters");

        int idx = 1;

        for (Object param : parameters) {
            setObject(idx++, param);
        }

        return (Q) this;
    }

    /**
     * Sets the parameters.
     *
     * @param startParameterIndex
     * @param parameters
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
     * @throws SQLException the SQL exception
     */
    public Q setParameters(final Collection<?> parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, "parameters");

        int idx = 1;

        for (Object param : parameters) {
            setObject(idx++, param);
        }

        return (Q) this;
    }

    /**
     * Sets the parameters.
     *
     * @param paramSetter
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setParameters(final ParametersSetter<? super S> paramSetter) throws SQLException {
        checkArgNotNull(paramSetter, "paramSetter");

        boolean noException = false;

        try {
            paramSetter.accept(stmt);

            noException = true;
        } finally {
            if (noException == false) {
                close();
            }
        }

        return (Q) this;
    }

    /**
     * Sets the parameters.
     *
     * @param <T>
     * @param parameters
     * @param paramSetter
     * @return
     * @throws SQLException the SQL exception
     */
    public <T> Q setParameters(final T parameters, final BiParametersSetter<? super S, ? super T> paramSetter) throws SQLException {
        checkArgNotNull(paramSetter, "paramSetter");

        boolean noException = false;

        try {
            paramSetter.accept(stmt, parameters);

            noException = true;
        } finally {
            if (noException == false) {
                close();
            }
        }

        return (Q) this;
    }

    /**
     * Sets the parameters.
     *
     * @param parameters
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
     * @throws SQLException the SQL exception
     */
    public Q settParameters(final int[] parameters) throws IllegalArgumentException, SQLException {
        return settParameters(1, parameters);
    }

    /**
     * Sets the parameters.
     *
     * @param startParameterIndex
     * @param parameters
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
     * @throws SQLException the SQL exception
     */
    public Q settParameters(int startParameterIndex, final int[] parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, "parameters");

        for (int param : parameters) {
            stmt.setInt(startParameterIndex++, param);
        }

        return (Q) this;
    }

    /**
     * Sets the parameters.
     *
     * @param parameters
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
     * @throws SQLException the SQL exception
     */
    public Q settParameters(final long[] parameters) throws IllegalArgumentException, SQLException {
        return settParameters(1, parameters);
    }

    /**
     * Sets the parameters.
     *
     * @param startParameterIndex
     * @param parameters
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
     * @throws SQLException the SQL exception
     */
    public Q settParameters(int startParameterIndex, final long[] parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, "parameters");

        for (long param : parameters) {
            stmt.setLong(startParameterIndex++, param);
        }

        return (Q) this;
    }

    /**
     * Sets the parameters.
     *
     * @param parameters
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
     * @throws SQLException the SQL exception
     */
    public Q settParameters(final String[] parameters) throws IllegalArgumentException, SQLException {
        return settParameters(1, parameters);
    }

    /**
     * Sets the parameters.
     *
     * @param startParameterIndex
     * @param parameters
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
     * @throws SQLException the SQL exception
     */
    public Q settParameters(int startParameterIndex, final String[] parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, "parameters");

        for (String param : parameters) {
            stmt.setString(startParameterIndex++, param);
        }

        return (Q) this;
    }

    /**
     * Sets the parameters.
     *
     * @param <T>
     * @param parameters
     * @param type
     * @return
     * @throws IllegalArgumentException if specified {@code parameters} or {@code type} is null.
     * @throws SQLException the SQL exception
     */
    public <T> Q settParameters(final T[] parameters, final Class<T> type) throws IllegalArgumentException, SQLException {
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
     * @throws SQLException the SQL exception
     */
    public <T> Q settParameters(int startParameterIndex, final T[] parameters, final Class<T> type) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, "parameters");
        checkArgNotNull(type, "type");

        final Type<T> setter = N.typeOf(type);

        for (T param : parameters) {
            setter.set(stmt, startParameterIndex++, param);
        }

        return (Q) this;
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
     * @throws SQLException the SQL exception
     */
    public <T> Q settParameters(final Collection<? extends T> parameters, final Class<T> type) throws IllegalArgumentException, SQLException {
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
     * @throws SQLException the SQL exception
     */
    public <T> Q settParameters(int startParameterIndex, final Collection<? extends T> parameters, final Class<T> type)
            throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, "parameters");
        checkArgNotNull(type, "type");

        final Type<T> setter = N.typeOf(type);

        for (T param : parameters) {
            setter.set(stmt, startParameterIndex++, param);
        }

        return (Q) this;
    }

    /**
     *
     * @param paramSetter
     * @return
     * @throws SQLException the SQL exception
     */
    public Q settParameters(ParametersSetter<? super Q> paramSetter) throws SQLException {
        checkArgNotNull(paramSetter, "paramSetter");

        boolean noException = false;

        try {
            paramSetter.accept((Q) this);

            noException = true;
        } finally {
            if (noException == false) {
                close();
            }
        }

        return (Q) this;
    }

    /**
     *
     * @param <T>
     * @param parameter
     * @param paramSetter
     * @return
     * @throws SQLException the SQL exception
     */
    public <T> Q settParameters(final T parameter, BiParametersSetter<? super Q, ? super T> paramSetter) throws SQLException {
        checkArgNotNull(paramSetter, "paramSetter");

        boolean noException = false;

        try {
            paramSetter.accept((Q) this, parameter);

            noException = true;
        } finally {
            if (noException == false) {
                close();
            }
        }

        return (Q) this;
    }

    //    /**
    //     * @param <T>
    //     * @param batchParameters
    //     * @param parametersSetter
    //     * @return
    //     * @throws SQLException the SQL exception
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
    //     * @throws SQLException the SQL exception
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
    public <T> Q addBatchParameters(final Collection<T> batchParameters) throws SQLException {
        checkArgNotNull(batchParameters, "batchParameters");

        if (N.isNullOrEmpty(batchParameters)) {
            return (Q) this;
        }

        boolean noException = false;

        try {
            final T first = N.firstNonNull(batchParameters).orNull();

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

        return (Q) this;
    }

    /**
     * 
     * @param <T>
     * @param batchParameters
     * @return
     * @throws SQLException
     */
    @Beta
    public <T> Q addBatchParameters(final Iterator<T> batchParameters) throws SQLException {
        checkArgNotNull(batchParameters, "batchParameters");

        return addBatchParameters(Iterators.toList(batchParameters));
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
    public <T> Q addBatchParameters(final Collection<? extends T> batchParameters, final Class<T> type) throws SQLException {
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

        return (Q) this;
    }

    //    /**
    //     * 
    //     * @param batchParameters
    //     * @return
    //     * @throws SQLException the SQL exception
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
    //     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
     */
    @Beta
    public <T> Q addBatchParameters(final Collection<T> batchParameters, BiParametersSetter<? super Q, ? super T> parametersSetter) throws SQLException {
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
     * @throws SQLException the SQL exception
     */
    @Beta
    public <T> Q addBatchParameters(final Iterator<T> batchParameters, BiParametersSetter<? super Q, ? super T> parametersSetter) throws SQLException {
        checkArgNotNull(batchParameters, "batchParameters");
        checkArgNotNull(parametersSetter, "parametersSetter");

        boolean noException = false;

        try {
            final Iterator<T> iter = batchParameters;

            while (iter.hasNext()) {
                parametersSetter.accept((Q) this, iter.next());
                stmt.addBatch();
                isBatch = true;
            }

            noException = true;
        } finally {
            if (noException == false) {
                close();
            }
        }

        return (Q) this;
    }

    /**
     * @param <T>
     * @param batchParameters
     * @param parametersSetter
     * @return
     * @throws SQLException the SQL exception
     */
    @Beta
    public <T> Q addBatchParameters(final Collection<T> batchParameters,
            Throwables.TriConsumer<? super Q, ? super S, ? super T, ? extends SQLException> parametersSetter) throws SQLException {
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
     * @throws SQLException the SQL exception
     */
    @Beta
    public <T> Q addBatchParameters(final Iterator<T> batchParameters,
            Throwables.TriConsumer<? super Q, ? super S, ? super T, ? extends SQLException> parametersSetter) throws SQLException {
        checkArgNotNull(batchParameters, "batchParameters");
        checkArgNotNull(parametersSetter, "parametersSetter");

        boolean noException = false;

        try {
            final Iterator<T> iter = batchParameters;

            while (iter.hasNext()) {
                parametersSetter.accept((Q) this, stmt, iter.next());
                stmt.addBatch();
                isBatch = true;
            }

            noException = true;
        } finally {
            if (noException == false) {
                close();
            }
        }

        return (Q) this;
    }

    /**
     * @param <T>
     * @param batchParameters
     * @param parametersSetter
     * @return
     * @throws SQLException the SQL exception
     */
    @Beta
    public <T> Q addBatchParametters(final Collection<T> batchParameters, BiParametersSetter<? super S, ? super T> parametersSetter) throws SQLException {
        checkArgNotNull(batchParameters, "batchParameters");
        checkArgNotNull(parametersSetter, "parametersSetter");

        return addBatchParametters(batchParameters.iterator(), parametersSetter);
    }

    /**
     *
     * @param <T>
     * @param batchParameters
     * @param parametersSetter
     * @return
     * @throws SQLException the SQL exception
     */
    @Beta
    public <T> Q addBatchParametters(final Iterator<T> batchParameters, BiParametersSetter<? super S, ? super T> parametersSetter) throws SQLException {
        checkArgNotNull(batchParameters, "batchParameters");
        checkArgNotNull(parametersSetter, "parametersSetter");

        boolean noException = false;

        try {
            final Iterator<T> iter = batchParameters;

            while (iter.hasNext()) {
                parametersSetter.accept(stmt, iter.next());
                stmt.addBatch();
                isBatch = true;
            }

            noException = true;
        } finally {
            if (noException == false) {
                close();
            }
        }

        return (Q) this;
    }

    //    /**
    //     * @param <T>
    //     * @param batchParameters
    //     * @param parametersSetter
    //     * @return
    //     * @throws SQLException the SQL exception
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
    //     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
     */
    public Q addBatch() throws SQLException {
        stmt.addBatch();
        isBatch = true;

        return (Q) this;
    }

    /**
     * Sets the fetch direction.
     *
     * @param direction one of <code>ResultSet.FETCH_FORWARD</code>,
     * <code>ResultSet.FETCH_REVERSE</code>, or <code>ResultSet.FETCH_UNKNOWN</code>
     * @return
     * @throws SQLException the SQL exception
     * @see {@link java.sql.Statement#setFetchDirection(int)}
     */
    public Q setFetchDirection(FetchDirection direction) throws SQLException {
        isFetchDirectionSet = true;

        stmt.setFetchDirection(direction.intValue);

        return (Q) this;
    }

    /**
     * Sets the fetch size.
     *
     * @param rows
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setFetchSize(int rows) throws SQLException {
        stmt.setFetchSize(rows);

        return (Q) this;
    }

    /**
     * Sets the max rows.
     *
     * @param max
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setMaxRows(int max) throws SQLException {
        stmt.setMaxRows(max);

        return (Q) this;
    }

    /**
     * Sets the large max rows.
     *
     * @param max
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setLargeMaxRows(long max) throws SQLException {
        stmt.setLargeMaxRows(max);

        return (Q) this;
    }

    /**
     * Sets the max field size.
     *
     * @param max
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setMaxFieldSize(int max) throws SQLException {
        stmt.setMaxFieldSize(max);

        return (Q) this;
    }

    /**
     * Sets the query timeout.
     *
     * @param seconds
     * @return
     * @throws SQLException the SQL exception
     */
    public Q setQueryTimeout(int seconds) throws SQLException {
        stmt.setQueryTimeout(seconds);

        return (Q) this;
    }

    /**
     * Query for boolean.
     *
     * @return
     * @throws SQLException the SQL exception
     */
    public OptionalBoolean queryForBoolean() throws SQLException {
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? OptionalBoolean.of(rs.getBoolean(1)) : OptionalBoolean.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /** The Constant charType. */
    private static final Type<Character> charType = TypeFactory.getType(char.class);

    /**
     * Query for char.
     *
     * @return
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws DuplicatedResultException if more than one record found.
     * @throws SQLException the SQL exception
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
     * @throws DuplicatedResultException if more than one record found.
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
     */
    private <T> T get(Class<T> targetClass, ResultSet rs) throws SQLException {
        final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

        return BiRowMapper.to(targetClass).apply(rs, columnLabels);
    }

    /**
     *
     * @return
     * @throws SQLException the SQL exception
     */
    public DataSet query() throws SQLException {
        return query(ResultExtractor.TO_DATA_SET);
    }

    /**
     *
     * @param <R>
     * @param resultExtrator
     * @return
     * @throws SQLException the SQL exception
     */
    public <R> R query(final ResultExtractor<R> resultExtrator) throws SQLException {
        checkArgNotNull(resultExtrator, "resultExtrator");
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return checkNotResultSet(resultExtrator.apply(rs));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @param <R>
     * @param resultExtrator
     * @return
     * @throws SQLException the SQL exception
     */
    public <R> R query(final BiResultExtractor<R> resultExtrator) throws SQLException {
        checkArgNotNull(resultExtrator, "resultExtrator");
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return checkNotResultSet(resultExtrator.apply(rs, JdbcUtil.getColumnLabelList(rs)));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @return
     * @throws DuplicatedResultException If there are more than one record found by the query
     * @throws SQLException the SQL exception
     */
    public <T> Optional<T> get(final Class<T> targetClass) throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(targetClass));
    }

    /**
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException If there are more than one record found by the query
     * @throws SQLException the SQL exception
     */
    public <T> Optional<T> get(JdbcUtil.RowMapper<T> rowMapper) throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(rowMapper));
    }

    /**
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException If there are more than one record found by the query
     * @throws SQLException the SQL exception
     */
    public <T> Optional<T> get(BiRowMapper<T> rowMapper) throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(rowMapper));
    }

    /**
     * Gets the t.
     *
     * @param <T>
     * @param targetClass
     * @return
     * @throws DuplicatedResultException If there are more than one record found by the query
     * @throws SQLException the SQL exception
     */
    public <T> T gett(final Class<T> targetClass) throws DuplicatedResultException, SQLException {
        checkArgNotNull(targetClass, "targetClass");
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            if (rs.next()) {
                final T result = Objects.requireNonNull(get(targetClass, rs));

                if (rs.next()) {
                    throw new DuplicatedResultException("There are more than one record found by the query");
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
     * @throws DuplicatedResultException If there are more than one record found by the query
     * @throws SQLException the SQL exception
     */
    public <T> T gett(JdbcUtil.RowMapper<T> rowMapper) throws DuplicatedResultException, SQLException {
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            if (rs.next()) {
                final T result = Objects.requireNonNull(rowMapper.apply(rs));

                if (rs.next()) {
                    throw new DuplicatedResultException("There are more than one record found by the query");
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
     * @throws DuplicatedResultException If there are more than one record found by the query
     * @throws SQLException the SQL exception
     */
    public <T> T gett(BiRowMapper<T> rowMapper) throws DuplicatedResultException, SQLException {
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            if (rs.next()) {
                final T result = Objects.requireNonNull(rowMapper.apply(rs, JdbcUtil.getColumnLabelList(rs)));

                if (rs.next()) {
                    throw new DuplicatedResultException("There are more than one record found by the query");
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
     * @param <T>
     * @param targetClass
     * @return
     * @throws SQLException the SQL exception
     */
    public <T> Optional<T> findFirst(final Class<T> targetClass) throws SQLException {
        checkArgNotNull(targetClass, "targetClass");
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            if (rs.next()) {
                return Optional.of(get(targetClass, rs));
            } else {
                return Optional.empty();
            }
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws SQLException the SQL exception
     */
    public <T> Optional<T> findFirst(JdbcUtil.RowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Optional.of(rowMapper.apply(rs)) : Optional.<T> empty();
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
     * @throws SQLException the SQL exception
     */
    public <T> Optional<T> findFirst(final RowFilter rowFilter, JdbcUtil.RowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            while (rs.next()) {
                if (rowFilter.test(rs)) {
                    return Optional.of(rowMapper.apply(rs));
                }
            }

            return Optional.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws SQLException the SQL exception
     */
    public <T> Optional<T> findFirst(BiRowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            return rs.next() ? Optional.of(rowMapper.apply(rs, JdbcUtil.getColumnLabelList(rs))) : Optional.<T> empty();
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
     * @throws SQLException the SQL exception
     */
    public <T> Optional<T> findFirst(final BiRowFilter rowFilter, BiRowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        try (ResultSet rs = executeQuery()) {
            final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

            while (rs.next()) {
                if (rowFilter.test(rs, columnLabels)) {
                    return Optional.of(rowMapper.apply(rs, columnLabels));
                }
            }

            return Optional.empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @param <T>
     * @param targetClass
     * @return
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
     */
    public <T> List<T> list(JdbcUtil.RowMapper<T> rowMapper) throws SQLException {
        return list(rowMapper, Integer.MAX_VALUE);
    }

    /**
     *
     * @param <T>
     * @param rowMapper
     * @param maxResult
     * @return
     * @throws SQLException the SQL exception
     * @deprecated the result size should be limited in database server side by sql scripts.
     */
    @Deprecated
    public <T> List<T> list(JdbcUtil.RowMapper<T> rowMapper, int maxResult) throws SQLException {
        return list(RowFilter.ALWAYS_TRUE, rowMapper, maxResult);
    }

    /**
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException the SQL exception
     */
    public <T> List<T> list(final RowFilter rowFilter, JdbcUtil.RowMapper<T> rowMapper) throws SQLException {
        return list(rowFilter, rowMapper, Integer.MAX_VALUE);
    }

    /**
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @param maxResult
     * @return
     * @throws SQLException the SQL exception
     */
    public <T> List<T> list(final RowFilter rowFilter, JdbcUtil.RowMapper<T> rowMapper, int maxResult) throws SQLException {
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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

    /**
     * lazy-execution, lazy-fetch.
     *
     * @param <T>
     * @param targetClass
     * @return
     * @throws SQLException the SQL exception
     */
    public <T> ExceptionalStream<T, SQLException> stream(final Class<T> targetClass) throws SQLException {
        return stream(BiRowMapper.to(targetClass));
    }

    /**
     * lazy-execution, lazy-fetch.
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws SQLException the SQL exception
     */
    public <T> ExceptionalStream<T, SQLException> stream(final JdbcUtil.RowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        final ExceptionalIterator<T, SQLException> lazyIter = ExceptionalIterator
                .of(new Throwables.Supplier<ExceptionalIterator<T, SQLException>, SQLException>() {
                    private ExceptionalIterator<T, SQLException> internalIter;

                    @Override
                    public ExceptionalIterator<T, SQLException> get() throws SQLException {
                        if (internalIter == null) {
                            ResultSet rs = null;

                            try {
                                rs = executeQuery();
                                final ResultSet resultSet = rs;

                                internalIter = new ExceptionalIterator<T, SQLException>() {
                                    private boolean hasNext;

                                    @Override
                                    public boolean hasNext() throws SQLException {
                                        if (hasNext == false) {
                                            hasNext = resultSet.next();
                                        }

                                        return hasNext;
                                    }

                                    @Override
                                    public T next() throws SQLException {
                                        if (hasNext() == false) {
                                            throw new NoSuchElementException();
                                        }

                                        hasNext = false;

                                        return rowMapper.apply(resultSet);
                                    }

                                    @Override
                                    public void skip(long n) throws SQLException {
                                        N.checkArgNotNegative(n, "n");

                                        final long m = hasNext ? n - 1 : n;
                                        hasNext = false;

                                        JdbcUtil.skip(resultSet, m);
                                    }

                                    @Override
                                    public long count() throws SQLException {
                                        long cnt = hasNext ? 1 : 0;
                                        hasNext = false;

                                        while (resultSet.next()) {
                                            cnt++;
                                        }

                                        return cnt;
                                    }

                                    @Override
                                    public void close() throws SQLException {
                                        try {
                                            JdbcUtil.closeQuietly(resultSet);
                                        } finally {
                                            closeAfterExecutionIfAllowed();
                                        }
                                    }
                                };
                            } finally {
                                if (internalIter == null) {
                                    try {
                                        JdbcUtil.closeQuietly(rs);
                                    } finally {
                                        closeAfterExecutionIfAllowed();
                                    }
                                }
                            }
                        }

                        return internalIter;
                    }
                });

        return ExceptionalStream.newStream(lazyIter).onClose(new Throwables.Runnable<SQLException>() {
            @Override
            public void run() throws SQLException {
                lazyIter.close();
            }
        });
    }

    /**
     * lazy-execution, lazy-fetch.
     *
     * @param <T>
     * @param rowMapper
     * @return
     * @throws SQLException the SQL exception
     */
    public <T> ExceptionalStream<T, SQLException> stream(final BiRowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        final ExceptionalIterator<T, SQLException> lazyIter = ExceptionalIterator
                .of(new Throwables.Supplier<ExceptionalIterator<T, SQLException>, SQLException>() {
                    private ExceptionalIterator<T, SQLException> internalIter;

                    @Override
                    public ExceptionalIterator<T, SQLException> get() throws SQLException {
                        if (internalIter == null) {
                            ResultSet rs = null;

                            try {
                                rs = executeQuery();
                                final ResultSet resultSet = rs;

                                internalIter = new ExceptionalIterator<T, SQLException>() {
                                    private List<String> columnLabels = null;
                                    private boolean hasNext;

                                    @Override
                                    public boolean hasNext() throws SQLException {
                                        if (hasNext == false) {
                                            hasNext = resultSet.next();
                                        }

                                        return hasNext;
                                    }

                                    @Override
                                    public T next() throws SQLException {
                                        if (hasNext() == false) {
                                            throw new NoSuchElementException();
                                        }

                                        hasNext = false;

                                        if (columnLabels == null) {
                                            columnLabels = JdbcUtil.getColumnLabelList(resultSet);
                                        }

                                        return rowMapper.apply(resultSet, columnLabels);
                                    }

                                    @Override
                                    public void skip(long n) throws SQLException {
                                        N.checkArgNotNegative(n, "n");

                                        final long m = hasNext ? n - 1 : n;
                                        hasNext = false;

                                        JdbcUtil.skip(resultSet, m);
                                    }

                                    @Override
                                    public long count() throws SQLException {
                                        long cnt = hasNext ? 1 : 0;
                                        hasNext = false;

                                        while (resultSet.next()) {
                                            cnt++;
                                        }

                                        return cnt;
                                    }

                                    @Override
                                    public void close() throws SQLException {
                                        try {
                                            JdbcUtil.closeQuietly(resultSet);
                                        } finally {
                                            closeAfterExecutionIfAllowed();
                                        }
                                    }
                                };
                            } finally {
                                if (internalIter == null) {
                                    try {
                                        JdbcUtil.closeQuietly(rs);
                                    } finally {
                                        closeAfterExecutionIfAllowed();
                                    }
                                }
                            }
                        }

                        return internalIter;
                    }
                });

        return ExceptionalStream.newStream(lazyIter).onClose(new Throwables.Runnable<SQLException>() {
            @Override
            public void run() throws SQLException {
                lazyIter.close();
            }
        });
    }

    /**
     * lazy-execution, lazy-fetch.
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException
     */
    public <T> ExceptionalStream<T, SQLException> stream(final RowFilter rowFilter, final JdbcUtil.RowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        final ExceptionalIterator<T, SQLException> lazyIter = ExceptionalIterator
                .of(new Throwables.Supplier<ExceptionalIterator<T, SQLException>, SQLException>() {
                    private ExceptionalIterator<T, SQLException> internalIter;

                    @Override
                    public ExceptionalIterator<T, SQLException> get() throws SQLException {
                        if (internalIter == null) {
                            ResultSet rs = null;

                            try {
                                rs = executeQuery();
                                final ResultSet resultSet = rs;

                                internalIter = new ExceptionalIterator<T, SQLException>() {
                                    private boolean hasNext;

                                    @Override
                                    public boolean hasNext() throws SQLException {
                                        if (hasNext == false) {
                                            while (resultSet.next()) {
                                                if (rowFilter.test(resultSet)) {
                                                    hasNext = true;
                                                    break;
                                                }
                                            }
                                        }

                                        return hasNext;
                                    }

                                    @Override
                                    public T next() throws SQLException {
                                        if (hasNext() == false) {
                                            throw new NoSuchElementException();
                                        }

                                        hasNext = false;

                                        return rowMapper.apply(resultSet);
                                    }

                                    @Override
                                    public void close() throws SQLException {
                                        try {
                                            JdbcUtil.closeQuietly(resultSet);
                                        } finally {
                                            closeAfterExecutionIfAllowed();
                                        }
                                    }
                                };
                            } finally {
                                if (internalIter == null) {
                                    try {
                                        JdbcUtil.closeQuietly(rs);
                                    } finally {
                                        closeAfterExecutionIfAllowed();
                                    }
                                }
                            }
                        }

                        return internalIter;
                    }
                });

        return ExceptionalStream.newStream(lazyIter).onClose(new Throwables.Runnable<SQLException>() {
            @Override
            public void run() throws SQLException {
                lazyIter.close();
            }
        });
    }

    /**
     * lazy-execution, lazy-fetch.
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException the SQL exception
     */
    public <T> ExceptionalStream<T, SQLException> stream(final BiRowFilter rowFilter, final BiRowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        final ExceptionalIterator<T, SQLException> lazyIter = ExceptionalIterator
                .of(new Throwables.Supplier<ExceptionalIterator<T, SQLException>, SQLException>() {
                    private ExceptionalIterator<T, SQLException> internalIter;

                    @Override
                    public ExceptionalIterator<T, SQLException> get() throws SQLException {
                        if (internalIter == null) {
                            ResultSet rs = null;

                            try {
                                rs = executeQuery();
                                final ResultSet resultSet = rs;

                                internalIter = new ExceptionalIterator<T, SQLException>() {
                                    private List<String> columnLabels = null;
                                    private boolean hasNext;

                                    @Override
                                    public boolean hasNext() throws SQLException {
                                        if (columnLabels == null) {
                                            columnLabels = JdbcUtil.getColumnLabelList(resultSet);
                                        }

                                        if (hasNext == false) {
                                            while (resultSet.next()) {
                                                if (rowFilter.test(resultSet, columnLabels)) {
                                                    hasNext = true;
                                                    break;
                                                }
                                            }
                                        }

                                        return hasNext;
                                    }

                                    @Override
                                    public T next() throws SQLException {
                                        if (hasNext() == false) {
                                            throw new NoSuchElementException();
                                        }

                                        hasNext = false;

                                        return rowMapper.apply(resultSet, columnLabels);
                                    }

                                    @Override
                                    public void close() throws SQLException {
                                        try {
                                            JdbcUtil.closeQuietly(resultSet);
                                        } finally {
                                            closeAfterExecutionIfAllowed();
                                        }
                                    }
                                };
                            } finally {
                                if (internalIter == null) {
                                    try {
                                        JdbcUtil.closeQuietly(rs);
                                    } finally {
                                        closeAfterExecutionIfAllowed();
                                    }
                                }
                            }
                        }

                        return internalIter;
                    }
                });

        return ExceptionalStream.newStream(lazyIter).onClose(new Throwables.Runnable<SQLException>() {
            @Override
            public void run() throws SQLException {
                lazyIter.close();
            }
        });
    }

    /**
     * Note: using {@code select 1 from ...}, not {@code select count(*) from ...}.
     *
     * @return true, if successful
     * @throws SQLException the SQL exception
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
     *
     * @param rowConsumer
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * Note: using {@code select count(*) from ...}
     *
     * @return
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
     */
    public boolean noneMatch(final RowFilter rowFilter) throws SQLException {
        return anyMatch(rowFilter) == false;
    }

    /**
     *
     * @param rowFilter
     * @return true, if successful
     * @throws SQLException the SQL exception
     */
    public boolean noneMatch(final BiRowFilter rowFilter) throws SQLException {
        return anyMatch(rowFilter) == false;
    }

    /**
     *
     * @param rowConsumer
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
     */
    private ResultSet executeQuery() throws SQLException {
        if (!isFetchDirectionSet) {
            stmt.setFetchDirection(ResultSet.FETCH_FORWARD);
        }

        return JdbcUtil.executeQuery(stmt);
    }

    /**
     * Returns the generated key if it exists.
     *
     * @param <ID>
     * @return
     * @throws SQLException the SQL exception
     */
    public <ID> Optional<ID> insert() throws SQLException {
        return insert((JdbcUtil.RowMapper<ID>) JdbcUtil.SINGLE_GENERATED_KEY_EXTRACTOR);
    }

    /**
     *
     * @param <ID>
     * @param autoGeneratedKeyExtractor
     * @return
     * @throws SQLException the SQL exception
     */
    public <ID> Optional<ID> insert(final JdbcUtil.RowMapper<ID> autoGeneratedKeyExtractor) throws SQLException {
        assertNotClosed();

        try {
            JdbcUtil.executeUpdate(stmt);

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                return rs.next() ? Optional.ofNullable(autoGeneratedKeyExtractor.apply(rs)) : Optional.<ID> empty();
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
     * @throws SQLException the SQL exception
     */
    public <ID> Optional<ID> insert(final BiRowMapper<ID> autoGeneratedKeyExtractor) throws SQLException {
        assertNotClosed();

        try {
            JdbcUtil.executeUpdate(stmt);

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                    return Optional.ofNullable(autoGeneratedKeyExtractor.apply(rs, columnLabels));
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
     * @throws SQLException the SQL exception
     */
    public <ID> List<ID> batchInsert() throws SQLException {
        return batchInsert((JdbcUtil.RowMapper<ID>) JdbcUtil.SINGLE_GENERATED_KEY_EXTRACTOR);
    }

    /**
     *
     * @param <ID>
     * @param autoGeneratedKeyExtractor
     * @return
     * @throws SQLException the SQL exception
     */
    public <ID> List<ID> batchInsert(final JdbcUtil.RowMapper<ID> autoGeneratedKeyExtractor) throws SQLException {
        assertNotClosed();

        try {
            JdbcUtil.executeBatch(stmt);

            List<ID> ids = new ArrayList<>();

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                while (rs.next()) {
                    ids.add(autoGeneratedKeyExtractor.apply(rs));
                }

                if (N.notNullOrEmpty(ids) && Stream.of(ids).allMatch(Fn.isNull())) {
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
     * @throws SQLException the SQL exception
     */
    public <ID> List<ID> batchInsert(final BiRowMapper<ID> autoGeneratedKeyExtractor) throws SQLException {
        assertNotClosed();

        try {
            JdbcUtil.executeBatch(stmt);

            List<ID> ids = new ArrayList<>();

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                while (rs.next()) {
                    ids.add(autoGeneratedKeyExtractor.apply(rs, columnLabels));
                }

                if (N.notNullOrEmpty(ids) && Stream.of(ids).allMatch(Fn.isNull())) {
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
     */
    public long largeUpdate() throws SQLException {
        assertNotClosed();

        try {
            return stmt.executeLargeUpdate();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Large batch update.
     *
     * @return
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
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
     * @throws SQLException the SQL exception
     */
    public <R> R executeThenApply(final Throwables.Function<? super S, ? extends R, SQLException> getter) throws SQLException {
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
     * @throws SQLException the SQL exception
     */
    public <R> R executeThenApply(final Throwables.BiFunction<Boolean, ? super S, ? extends R, SQLException> getter) throws SQLException {
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
     * @throws SQLException the SQL exception
     */
    public void executeThenAccept(final Throwables.Consumer<? super S, SQLException> consumer) throws SQLException {
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
     * @throws SQLException the SQL exception
     */
    public void executeThenAccept(final Throwables.BiConsumer<Boolean, ? super S, SQLException> consumer) throws SQLException {
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
     *
     * @param <R>
     * @param func
     * @return
     */
    @Beta
    public <R> ContinuableFuture<R> asyncCall(final Throwables.Function<Q, R, SQLException> func) {
        checkArgNotNull(func, "func");
        assertNotClosed();

        final Q q = (Q) this;

        return JdbcUtil.asyncExecutor.execute(() -> func.apply(q));
    }

    /**
     *
     * @param <R>
     * @param func
     * @param executor
     * @return
     */
    @Beta
    public <R> ContinuableFuture<R> asyncCall(final Throwables.Function<Q, R, SQLException> func, final Executor executor) {
        checkArgNotNull(func, "func");
        checkArgNotNull(executor, "executor");
        assertNotClosed();

        final Q q = (Q) this;

        return ContinuableFuture.call(() -> func.apply(q), executor);
    }

    /**
     *
     * @param action
     * @return
     */
    @Beta
    public ContinuableFuture<Void> asyncRun(final Throwables.Consumer<Q, SQLException> action) {
        checkArgNotNull(action, "action");
        assertNotClosed();

        final Q q = (Q) this;

        return JdbcUtil.asyncExecutor.execute(() -> action.accept(q));
    }

    /**
     *
     * @param action
     * @param executor
     * @return
     */
    @Beta
    public ContinuableFuture<Void> asyncRun(final Throwables.Consumer<Q, SQLException> action, final Executor executor) {
        checkArgNotNull(action, "action");
        checkArgNotNull(executor, "executor");
        assertNotClosed();

        final Q q = (Q) this;

        return ContinuableFuture.run(() -> action.accept(q), executor);
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

    protected <R> R checkNotResultSet(R result) {
        if (result instanceof ResultSet) {
            throw new UnsupportedOperationException("The result value of ResultExtractor/BiResultExtractor.apply can't be ResultSet");
        }

        return result;
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
            JdbcUtil.closeQuietly(stmt);
        } else {
            try {
                JdbcUtil.closeQuietly(stmt);
            } finally {
                closeHandler.run();
            }
        }
    }

    /**
     * Close after execution if allowed.
     *
     * @throws SQLException the SQL exception
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