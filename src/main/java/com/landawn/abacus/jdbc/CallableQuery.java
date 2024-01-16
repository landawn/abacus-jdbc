/*
 * Copyright (c) 2020, Haiyang Li.
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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.jdbc.Jdbc.BiRowMapper;
import com.landawn.abacus.jdbc.Jdbc.RowMapper;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.util.CheckedStream;
import com.landawn.abacus.util.CheckedStream.CheckedIterator;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.DataSet;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.Tuple;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.Tuple.Tuple4;

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
 * @author haiyangl
 *
 * @see {@link com.landawn.abacus.annotation.ReadOnly}
 * @see {@link com.landawn.abacus.annotation.ReadOnlyId}
 * @see {@link com.landawn.abacus.annotation.NonUpdatable}
 * @see {@link com.landawn.abacus.annotation.Transient}
 * @see {@link com.landawn.abacus.annotation.Table}
 * @see {@link com.landawn.abacus.annotation.Column}
 *
 * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html</a>
 * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html</a>
 * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html</a>
 * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html">http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html</a>
 */
@SuppressWarnings("java:S1192")
public final class CallableQuery extends AbstractQuery<CallableStatement, CallableQuery> {

    final CallableStatement cstmt;
    List<Jdbc.OutParam> outParams;

    CallableQuery(CallableStatement stmt) {
        super(stmt);
        this.cstmt = stmt;
    }

    /**
     * Sets the null.
     *
     * @param parameterName
     * @param sqlType
     * @return
     * @throws SQLException the SQL exception
     * @see java.sql.Types
     */
    public CallableQuery setNull(String parameterName, int sqlType) throws SQLException {
        cstmt.setNull(parameterName, sqlType);

        return this;
    }

    /**
     * Sets the null.
     *
     * @param parameterName
     * @param sqlType
     * @param typeName
     * @return
     * @throws SQLException the SQL exception
     * @see java.sql.Types
     */
    public CallableQuery setNull(String parameterName, int sqlType, String typeName) throws SQLException {
        cstmt.setNull(parameterName, sqlType, typeName);

        return this;
    }

    /**
     * Sets the boolean.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setBoolean(String parameterName, boolean x) throws SQLException {
        cstmt.setBoolean(parameterName, x);

        return this;
    }

    /**
     * Sets the boolean.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setBoolean(String parameterName, Boolean x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, java.sql.Types.BOOLEAN);
        } else {
            cstmt.setBoolean(parameterName, x.booleanValue());
        }

        return this;
    }

    /**
     * Sets the byte.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setByte(String parameterName, byte x) throws SQLException {
        cstmt.setByte(parameterName, x);

        return this;
    }

    /**
     * Sets the byte.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setByte(String parameterName, Byte x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, java.sql.Types.TINYINT);
        } else {
            cstmt.setByte(parameterName, x.byteValue());
        }

        return this;
    }

    /**
     * Sets the short.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setShort(String parameterName, short x) throws SQLException {
        cstmt.setShort(parameterName, x);

        return this;
    }

    /**
     * Sets the short.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setShort(String parameterName, Short x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, java.sql.Types.SMALLINT);
        } else {
            cstmt.setShort(parameterName, x.shortValue());
        }

        return this;
    }

    /**
     * Sets the int.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setInt(String parameterName, int x) throws SQLException {
        cstmt.setInt(parameterName, x);

        return this;
    }

    /**
     * Sets the int.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setInt(String parameterName, Integer x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, java.sql.Types.INTEGER);
        } else {
            cstmt.setInt(parameterName, x.intValue());
        }

        return this;
    }

    /**
     * Sets the long.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setLong(String parameterName, long x) throws SQLException {
        cstmt.setLong(parameterName, x);

        return this;
    }

    /**
     * Sets the long.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setLong(String parameterName, Long x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, java.sql.Types.BIGINT);
        } else {
            cstmt.setLong(parameterName, x.longValue());
        }

        return this;
    }

    /**
     * Sets the long.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setLong(String parameterName, BigInteger x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, Types.BIGINT);
        } else {
            cstmt.setLong(parameterName, x.longValueExact());
        }

        return this;
    }

    /**
     * Sets the float.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setFloat(String parameterName, float x) throws SQLException {
        cstmt.setFloat(parameterName, x);

        return this;
    }

    /**
     * Sets the float.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setFloat(String parameterName, Float x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, java.sql.Types.FLOAT);
        } else {
            cstmt.setFloat(parameterName, x.floatValue());
        }

        return this;
    }

    /**
     * Sets the double.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setDouble(String parameterName, double x) throws SQLException {
        cstmt.setDouble(parameterName, x);

        return this;
    }

    /**
     * Sets the double.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setDouble(String parameterName, Double x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, java.sql.Types.DOUBLE);
        } else {
            cstmt.setDouble(parameterName, x.doubleValue());
        }

        return this;
    }

    /**
     * Sets the big decimal.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setBigDecimal(String parameterName, BigDecimal x) throws SQLException {
        cstmt.setBigDecimal(parameterName, x);

        return this;
    }

    /**
     * Sets the big decimal.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setBigDecimal(String parameterName, BigInteger x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, Types.DECIMAL);
        } else {
            cstmt.setBigDecimal(parameterName, new BigDecimal(x));
        }

        return this;
    }

    /**
     * Sets the BigInteger.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException
     * @see {@link #setString(String, BigInteger)}
     * @see {@link #setBigDecimal(String, BigInteger)}
     * @see {@link #setLong(String, BigInteger)}
     */
    @Beta
    public CallableQuery setBigIntegerAsString(String parameterName, BigInteger x) throws SQLException {
        return setString(parameterName, x);
    }

    /**
     * Sets the string.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setString(String parameterName, String x) throws SQLException {
        cstmt.setString(parameterName, x);

        return this;
    }

    /**
     * Sets the string.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setString(String parameterName, CharSequence x) throws SQLException {
        return setString(parameterName, x == null ? null : x.toString());
    }

    /**
     *
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException
     */
    public CallableQuery setString(String parameterName, char x) throws SQLException {
        return setString(parameterName, String.valueOf(x));
    }

    /**
     *
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException
     */
    public CallableQuery setString(String parameterName, Character x) throws SQLException {
        return setString(parameterName, x == null ? (String) null : x.toString()); //NOSONAR
    }

    /**
     * Sets the string.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setString(String parameterName, BigInteger x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, Types.VARCHAR);
        } else {
            cstmt.setString(parameterName, x.toString(10));
        }

        return this;
    }

    /**
     * Sets the string.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setNString(String parameterName, String x) throws SQLException {
        cstmt.setNString(parameterName, x);

        return this;
    }

    /**
     * Sets the date.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setDate(String parameterName, java.sql.Date x) throws SQLException {
        cstmt.setDate(parameterName, x);

        return this;
    }

    /**
     * Sets the date.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setDate(String parameterName, java.util.Date x) throws SQLException {
        cstmt.setDate(parameterName, x == null ? null : x instanceof java.sql.Date ? (java.sql.Date) x : new java.sql.Date(x.getTime()));

        return this;
    }

    /**
     * Sets the time.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setTime(String parameterName, java.sql.Time x) throws SQLException {
        cstmt.setTime(parameterName, x);

        return this;
    }

    /**
     * Sets the time.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setTime(String parameterName, java.util.Date x) throws SQLException {
        cstmt.setTime(parameterName, x == null ? null : x instanceof java.sql.Time ? (java.sql.Time) x : new java.sql.Time(x.getTime()));

        return this;
    }

    /**
     * Sets the timestamp.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setTimestamp(String parameterName, java.sql.Timestamp x) throws SQLException {
        cstmt.setTimestamp(parameterName, x);

        return this;
    }

    /**
     * Sets the timestamp.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setTimestamp(String parameterName, java.util.Date x) throws SQLException {
        cstmt.setTimestamp(parameterName, x == null ? null : x instanceof java.sql.Timestamp ? (java.sql.Timestamp) x : new java.sql.Timestamp(x.getTime()));

        return this;
    }

    /**
     * Sets the bytes.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setBytes(String parameterName, byte[] x) throws SQLException {
        cstmt.setBytes(parameterName, x);

        return this;
    }

    /**
     * Sets the ascii stream.
     *
     * @param parameterName
     * @param inputStream
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setAsciiStream(String parameterName, InputStream inputStream) throws SQLException {
        cstmt.setAsciiStream(parameterName, inputStream);

        return this;
    }

    /**
     * Sets the ascii stream.
     *
     * @param parameterName
     * @param inputStream
     * @param length
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setAsciiStream(String parameterName, InputStream inputStream, long length) throws SQLException {
        cstmt.setAsciiStream(parameterName, inputStream, length);

        return this;
    }

    /**
     * Sets the binary stream.
     *
     * @param parameterName
     * @param inputStream
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setBinaryStream(String parameterName, InputStream inputStream) throws SQLException {
        cstmt.setBinaryStream(parameterName, inputStream);

        return this;
    }

    /**
     * Sets the binary stream.
     *
     * @param parameterName
     * @param inputStream
     * @param length
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setBinaryStream(String parameterName, InputStream inputStream, long length) throws SQLException {
        cstmt.setBinaryStream(parameterName, inputStream, length);

        return this;
    }

    /**
     * Sets the character stream.
     *
     * @param parameterName
     * @param reader
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setCharacterStream(String parameterName, Reader reader) throws SQLException {
        cstmt.setCharacterStream(parameterName, reader);

        return this;
    }

    /**
     * Sets the character stream.
     *
     * @param parameterName
     * @param reader
     * @param length
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setCharacterStream(String parameterName, Reader reader, long length) throws SQLException {
        cstmt.setCharacterStream(parameterName, reader, length);

        return this;
    }

    /**
     * Sets the N character stream.
     *
     * @param parameterName
     * @param reader
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setNCharacterStream(String parameterName, Reader reader) throws SQLException {
        cstmt.setNCharacterStream(parameterName, reader);

        return this;
    }

    /**
     * Sets the N character stream.
     *
     * @param parameterName
     * @param reader
     * @param length
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setNCharacterStream(String parameterName, Reader reader, long length) throws SQLException {
        cstmt.setNCharacterStream(parameterName, reader, length);

        return this;
    }

    /**
     * Sets the blob.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setBlob(String parameterName, java.sql.Blob x) throws SQLException {
        cstmt.setBlob(parameterName, x);

        return this;
    }

    /**
     * Sets the blob.
     *
     * @param parameterName
     * @param inputStream
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setBlob(String parameterName, InputStream inputStream) throws SQLException {
        cstmt.setBlob(parameterName, inputStream);

        return this;
    }

    /**
     * Sets the blob.
     *
     * @param parameterName
     * @param inputStream
     * @param length
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setBlob(String parameterName, InputStream inputStream, long length) throws SQLException {
        cstmt.setBlob(parameterName, inputStream, length);

        return this;
    }

    /**
     * Sets the clob.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setClob(String parameterName, java.sql.Clob x) throws SQLException {
        cstmt.setClob(parameterName, x);

        return this;
    }

    /**
     * Sets the clob.
     *
     * @param parameterName
     * @param reader
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setClob(String parameterName, Reader reader) throws SQLException {
        cstmt.setClob(parameterName, reader);

        return this;
    }

    /**
     * Sets the clob.
     *
     * @param parameterName
     * @param reader
     * @param length
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setClob(String parameterName, Reader reader, long length) throws SQLException {
        cstmt.setClob(parameterName, reader, length);

        return this;
    }

    /**
     * Sets the N clob.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setNClob(String parameterName, java.sql.NClob x) throws SQLException {
        cstmt.setNClob(parameterName, x);

        return this;
    }

    /**
     * Sets the N clob.
     *
     * @param parameterName
     * @param reader
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setNClob(String parameterName, Reader reader) throws SQLException {
        cstmt.setNClob(parameterName, reader);

        return this;
    }

    /**
     * Sets the N clob.
     *
     * @param parameterName
     * @param reader
     * @param length
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setNClob(String parameterName, Reader reader, long length) throws SQLException {
        cstmt.setNClob(parameterName, reader, length);

        return this;
    }

    /**
     *
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setURL(String parameterName, URL x) throws SQLException {
        cstmt.setURL(parameterName, x);

        return this;
    }

    /**
     *
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setSQLXML(String parameterName, java.sql.SQLXML x) throws SQLException {
        cstmt.setSQLXML(parameterName, x);

        return this;
    }

    /**
     *
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setRowId(String parameterName, java.sql.RowId x) throws SQLException {
        cstmt.setRowId(parameterName, x);

        return this;
    }

    /**
     * Sets the object.
     *
     * @param parameterName
     * @param x
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setObject(String parameterName, Object x) throws SQLException {
        if (x == null) {
            cstmt.setObject(parameterName, x);
        } else {
            N.typeOf(x.getClass()).set(cstmt, parameterName, x);
        }

        return this;
    }

    /**
     * Sets the object.
     *
     * @param parameterName
     * @param x
     * @param sqlType
     * @return
     * @throws SQLException the SQL exception
     * @see java.sql.Types
     */
    public CallableQuery setObject(String parameterName, Object x, int sqlType) throws SQLException {
        cstmt.setObject(parameterName, x, sqlType);

        return this;
    }

    /**
     * Sets the object.
     *
     * @param parameterName
     * @param x
     * @param sqlType
     * @param scaleOrLength
     * @return
     * @throws SQLException the SQL exception
     * @see java.sql.Types
     */
    public CallableQuery setObject(final String parameterName, final Object x, final int sqlType, final int scaleOrLength) throws SQLException {
        cstmt.setObject(parameterName, x, sqlType, scaleOrLength);

        return this;
    }

    /**
     * Sets the parameters.
     *
     * @param parameters
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery setParameters(final Map<String, ?> parameters) throws SQLException {
        checkArgNotNull(parameters, "parameters");

        for (Map.Entry<String, ?> entry : parameters.entrySet()) {
            setObject(entry.getKey(), entry.getValue());
        }

        return this;
    }

    /**
     * Sets the parameters.
     * @param entity
     * @param parameterNames
     *
     * @return
     * @throws SQLException the SQL exception
     * @see {@link ClassUtil#getPropNameList(Class)}
     * @see {@link ClassUtil#getPropNameListExclusively(Class, Set)}
     * @see {@link ClassUtil#getPropNameListExclusively(Class, Collection)}
     * @see {@link JdbcUtil#getNamedParameters(String)}
     */
    public CallableQuery setParameters(final Object entity, final List<String> parameterNames) throws SQLException {
        checkArgNotNull(entity, "entity");
        checkArgNotNull(parameterNames, "parameterNames");

        final Class<?> cls = entity.getClass();
        final BeanInfo entityInfo = ParserUtil.getBeanInfo(cls);
        PropInfo propInfo = null;

        for (String parameterName : parameterNames) {
            propInfo = entityInfo.getPropInfo(parameterName);
            propInfo.dbType.set(cstmt, parameterName, propInfo.getPropValue(entity));
        }

        return this;
    }

    /**
     * Register out parameter.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param sqlType
     * @return
     * @throws SQLException the SQL exception
     * @see java.sql.Types
     */
    public CallableQuery registerOutParameter(int parameterIndex, int sqlType) throws SQLException {
        cstmt.registerOutParameter(parameterIndex, sqlType);

        addOutParameters(new Jdbc.OutParam(parameterIndex, null, sqlType, null, -1));

        return this;
    }

    /**
     * Register out parameter.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param sqlType
     * @param scale
     * @return
     * @throws SQLException the SQL exception
     * @see java.sql.Types
     */
    public CallableQuery registerOutParameter(int parameterIndex, int sqlType, int scale) throws SQLException {
        cstmt.registerOutParameter(parameterIndex, sqlType, scale);

        addOutParameters(new Jdbc.OutParam(parameterIndex, null, sqlType, null, scale));

        return this;
    }

    /**
     * Register out parameter.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param sqlType
     * @param typeName
     * @return
     * @throws SQLException the SQL exception
     * @see java.sql.Types
     */
    public CallableQuery registerOutParameter(int parameterIndex, int sqlType, String typeName) throws SQLException {
        cstmt.registerOutParameter(parameterIndex, sqlType, typeName);

        addOutParameters(new Jdbc.OutParam(parameterIndex, null, sqlType, typeName, -1));

        return this;
    }

    /**
     * Register out parameter.
     *
     * @param parameterName
     * @param sqlType
     * @return
     * @throws SQLException the SQL exception
     * @see java.sql.Types
     */
    public CallableQuery registerOutParameter(String parameterName, int sqlType) throws SQLException {
        cstmt.registerOutParameter(parameterName, sqlType);

        addOutParameters(new Jdbc.OutParam(-1, parameterName, sqlType, null, -1));

        return this;
    }

    /**
     * Register out parameter.
     *
     * @param parameterName
     * @param sqlType
     * @param scale
     * @return
     * @throws SQLException the SQL exception
     * @see java.sql.Types
     */
    public CallableQuery registerOutParameter(String parameterName, int sqlType, int scale) throws SQLException {
        cstmt.registerOutParameter(parameterName, sqlType, scale);

        addOutParameters(new Jdbc.OutParam(-1, parameterName, sqlType, null, scale));

        return this;
    }

    /**
     * Register out parameter.
     *
     * @param parameterName
     * @param sqlType
     * @param typeName
     * @return
     * @throws SQLException the SQL exception
     * @see java.sql.Types
     */
    public CallableQuery registerOutParameter(String parameterName, int sqlType, String typeName) throws SQLException {
        cstmt.registerOutParameter(parameterName, sqlType, typeName);

        addOutParameters(new Jdbc.OutParam(-1, parameterName, sqlType, typeName, -1));

        return this;
    }

    /**
     * Register out parameter.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param sqlType
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery registerOutParameter(int parameterIndex, SQLType sqlType) throws SQLException {
        cstmt.registerOutParameter(parameterIndex, sqlType);

        addOutParameters(new Jdbc.OutParam(parameterIndex, null, sqlType.getVendorTypeNumber(), null, -1));

        return this;
    }

    /**
     * Register out parameter.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param sqlType
     * @param scale
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery registerOutParameter(int parameterIndex, SQLType sqlType, int scale) throws SQLException {
        cstmt.registerOutParameter(parameterIndex, sqlType, scale);

        addOutParameters(new Jdbc.OutParam(parameterIndex, null, sqlType.getVendorTypeNumber(), null, scale));

        return this;
    }

    /**
     * Register out parameter.
     *
     * @param parameterIndex starts from 1, not 0.
     * @param sqlType
     * @param typeName
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery registerOutParameter(int parameterIndex, SQLType sqlType, String typeName) throws SQLException {
        cstmt.registerOutParameter(parameterIndex, sqlType, typeName);

        addOutParameters(new Jdbc.OutParam(parameterIndex, null, sqlType.getVendorTypeNumber(), typeName, -1));

        return this;
    }

    /**
     * Register out parameter.
     *
     * @param parameterName
     * @param sqlType
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery registerOutParameter(String parameterName, SQLType sqlType) throws SQLException {
        cstmt.registerOutParameter(parameterName, sqlType);

        addOutParameters(new Jdbc.OutParam(-1, parameterName, sqlType.getVendorTypeNumber(), null, -1));

        return this;
    }

    /**
     * Register out parameter.
     *
     * @param parameterName
     * @param sqlType
     * @param scale
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery registerOutParameter(String parameterName, SQLType sqlType, int scale) throws SQLException {
        cstmt.registerOutParameter(parameterName, sqlType, scale);

        addOutParameters(new Jdbc.OutParam(-1, parameterName, sqlType.getVendorTypeNumber(), null, scale));

        return this;
    }

    /**
     * Register out parameter.
     *
     * @param parameterName
     * @param sqlType
     * @param typeName
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery registerOutParameter(String parameterName, SQLType sqlType, String typeName) throws SQLException {
        cstmt.registerOutParameter(parameterName, sqlType, typeName);

        addOutParameters(new Jdbc.OutParam(-1, parameterName, sqlType.getVendorTypeNumber(), typeName, -1));

        return this;
    }

    /**
     * Register out parameters.
     *
     * @param register
     * @return
     * @throws SQLException the SQL exception
     */
    public CallableQuery registerOutParameters(final Jdbc.ParametersSetter<? super CallableQuery> register) throws SQLException {
        checkArgNotNull(register, "register");

        boolean noException = false;

        try {
            register.accept(this);

            noException = true;
        } finally {
            if (!noException) {
                close();
            }
        }

        return this;
    }

    /**
     * Register out parameters.
     *
     * @param <T>
     * @param parameter
     * @param register
     * @return
     * @throws SQLException the SQL exception
     */
    public <T> CallableQuery registerOutParameters(final T parameter, final Jdbc.BiParametersSetter<? super CallableQuery, ? super T> register)
            throws SQLException {
        checkArgNotNull(register, "register");

        boolean noException = false;

        try {
            register.accept(this, parameter);

            noException = true;
        } finally {
            if (!noException) {
                close();
            }
        }

        return this;
    }

    private void addOutParameters(Jdbc.OutParam outParameter) {
        if (outParams == null) {
            outParams = new ArrayList<>();
        }

        outParams.add(outParameter);
    }

    @Override
    protected ResultSet executeQuery() throws SQLException {
        if (!isFetchDirectionSet) {
            cstmt.setFetchDirection(ResultSet.FETCH_FORWARD);
        }

        boolean ret = JdbcUtil.execute(cstmt);
        int updateCount = cstmt.getUpdateCount();

        while (ret || updateCount != -1) {
            if (ret) {
                return cstmt.getResultSet();
            } else {
                ret = cstmt.getMoreResults();
                updateCount = cstmt.getUpdateCount();
            }
        }

        return null;
    }

    /**
     * Execute then apply.
     *
     * @param <R>
     * @param getter
     * @return
     * @throws SQLException the SQL exception
     * @see JdbcUtil#getOutParameters(CallableStatement, int)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, RowMapper)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, BiRowMapper)
     */
    @Override
    public <R> R executeThenApply(final Throwables.Function<? super CallableStatement, ? extends R, SQLException> getter) throws SQLException { //NOSONAR
        return super.executeThenApply(getter);
    }

    /**
     * Execute then apply.
     *
     * @param <R>
     * @param getter
     * @return
     * @throws SQLException the SQL exception
     * @see JdbcUtil#getOutParameters(CallableStatement, int)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, RowMapper)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, BiRowMapper)
     */
    @Override
    public <R> R executeThenApply(final Throwables.BiFunction<Boolean, ? super CallableStatement, ? extends R, SQLException> getter) throws SQLException { //NOSONAR
        return super.executeThenApply(getter);
    }

    /**
     * Execute then apply.
     *
     * @param <R>
     * @param getter the first parameter is {@code isFirstResultSet}, the second one is {@code outParametes} and third one is the executed {@code CallableStatement}.
     * @return
     * @throws SQLException the SQL exception
     * @see JdbcUtil#getOutParameters(CallableStatement, int)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, RowMapper)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, BiRowMapper)
     */
    public <R> R executeThenApply(final Throwables.TriFunction<Boolean, List<Jdbc.OutParam>, ? super CallableStatement, ? extends R, SQLException> getter)
            throws SQLException {
        checkArgNotNull(getter, "getter");
        assertNotClosed();

        try {
            final boolean isFirstResultSet = JdbcUtil.execute(cstmt);
            outParams = outParams == null ? N.<Jdbc.OutParam> emptyList() : outParams;

            return getter.apply(isFirstResultSet, outParams, cstmt);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Execute then accept.
     *
     * @param consumer
     * @throws SQLException the SQL exception
     * @see JdbcUtil#getOutParameters(CallableStatement, int)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, RowMapper)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, BiRowMapper)
     */
    @Override
    public void executeThenAccept(final Throwables.Consumer<? super CallableStatement, SQLException> consumer) throws SQLException { //NOSONAR
        super.executeThenAccept(consumer);
    }

    /**
     * Execute then accept.
     *
     * @param consumer
     * @throws SQLException the SQL exception
     * @see JdbcUtil#getOutParameters(CallableStatement, int)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, RowMapper)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, BiRowMapper)
     */
    @Override
    public void executeThenAccept(final Throwables.BiConsumer<Boolean, ? super CallableStatement, SQLException> consumer) throws SQLException { //NOSONAR
        super.executeThenAccept(consumer);
    }

    /**
     * Execute then apply.
     *
     * @param consumer the first parameter is {@code isFirstResultSet}, the second one is {@code outParametes} and third one is the executed {@code CallableStatement}.
     * @throws SQLException the SQL exception
     * @see JdbcUtil#getOutParameters(CallableStatement, int)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, RowMapper)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, BiRowMapper)
     */
    public void executeThenAccept(final Throwables.TriConsumer<Boolean, List<Jdbc.OutParam>, ? super CallableStatement, SQLException> consumer)
            throws SQLException {
        checkArgNotNull(consumer, "consumer");
        assertNotClosed();

        try {
            final boolean isFirstResultSet = JdbcUtil.execute(cstmt);
            outParams = outParams == null ? N.<Jdbc.OutParam> emptyList() : outParams;

            consumer.accept(isFirstResultSet, outParams, cstmt);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @return a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public Jdbc.OutParamResult executeAndGetOutParameters() throws SQLException {
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            return JdbcUtil.getOutParameters(cstmt, outParams);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @return the {@code DataSet} extracted from first {@code ResultSet} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public Tuple2<DataSet, Jdbc.OutParamResult> queryAndGetOutParameters() throws SQLException {
        return queryAndGetOutParameters(Jdbc.ResultExtractor.TO_DATA_SET);
    }

    /**
     *
     * @param <R>
     * @param resultExtractor Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return the {@code R} extracted from first {@code ResultSet} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <R> Tuple2<R, Jdbc.OutParamResult> queryAndGetOutParameters(final Jdbc.ResultExtractor<? extends R> resultExtractor) throws SQLException {
        checkArgNotNull(resultExtractor, "resultExtractor");
        assertNotClosed();

        try {
            R result = null;
            final ResultSet rs = executeQuery();

            if (rs != null) {
                result = JdbcUtil.extractAndCloseResultSet(rs, resultExtractor);
            }

            return Tuple.of(result, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @param <R>
     * @param resultExtractor Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return the {@code R} extracted from first {@code ResultSet} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <R> Tuple2<R, Jdbc.OutParamResult> queryAndGetOutParameters(final Jdbc.BiResultExtractor<? extends R> resultExtractor) throws SQLException {
        checkArgNotNull(resultExtractor, "resultExtractor");
        assertNotClosed();

        try {
            R result = null;
            final ResultSet rs = executeQuery();

            if (rs != null) {
                result = JdbcUtil.extractAndCloseResultSet(rs, resultExtractor);
            }

            return Tuple.of(result, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @return a list of {@code DataSet} extracted from all {@code ResultSets} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public Tuple2<List<DataSet>, Jdbc.OutParamResult> queryMultiResultsetsAndGetOutParameters() throws SQLException {
        return queryMultiResultsetsAndGetOutParameters(Jdbc.ResultExtractor.TO_DATA_SET);
    }

    /**
     *
     * @param <R>
     * @param resultExtractor Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return a list of {@code R} extracted from all {@code ResultSets} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <R> Tuple2<List<R>, Jdbc.OutParamResult> queryMultiResultsetsAndGetOutParameters(final Jdbc.ResultExtractor<? extends R> resultExtractor)
            throws SQLException {
        checkArgNotNull(resultExtractor, "resultExtractor");
        assertNotClosed();

        CheckedIterator<ResultSet, SQLException> iter = null;

        try {
            JdbcUtil.execute(cstmt);

            iter = JdbcUtil.iterateAllResultSets(cstmt);

            final List<R> resultList = new ArrayList<>();

            while (iter.hasNext()) {
                resultList.add(JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtractor));
            }

            return Tuple.of(resultList, JdbcUtil.getOutParameters(cstmt, outParams));
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
     * @param resultExtractor Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return a list of {@code R} extracted from all {@code ResultSets} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <R> Tuple2<List<R>, Jdbc.OutParamResult> queryMultiResultsetsAndGetOutParameters(final Jdbc.BiResultExtractor<? extends R> resultExtractor)
            throws SQLException {
        checkArgNotNull(resultExtractor, "resultExtractor");
        assertNotClosed();

        CheckedIterator<ResultSet, SQLException> iter = null;

        try {
            JdbcUtil.execute(cstmt);

            iter = JdbcUtil.iterateAllResultSets(cstmt);

            final List<R> resultList = new ArrayList<>();

            while (iter.hasNext()) {
                resultList.add(JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtractor));
            }

            return Tuple.of(resultList, JdbcUtil.getOutParameters(cstmt, outParams));
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
     * @param <R1>
     * @param <R2>
     * @param resultExtractor1 Don't save/return {@code ResultSet}. It will be closed after this call.
     * @param resultExtractor2 Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return {@code R1/R2} extracted from the first two {@code ResultSets} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    @Beta
    public <R1, R2> Tuple3<R1, R2, Jdbc.OutParamResult> query2ResultsetsAndGetOutParameters(final Jdbc.BiResultExtractor<? extends R1> resultExtractor1,
            final Jdbc.BiResultExtractor<? extends R2> resultExtractor2) throws SQLException {
        checkArgNotNull(resultExtractor1, "resultExtractor1");
        checkArgNotNull(resultExtractor2, "resultExtractor2");
        assertNotClosed();

        CheckedIterator<ResultSet, SQLException> iter = null;

        try {
            JdbcUtil.execute(cstmt);

            iter = JdbcUtil.iterateAllResultSets(cstmt);

            R1 result1 = null;
            R2 result2 = null;

            if (iter.hasNext()) {
                result1 = JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtractor1);
            }

            if (iter.hasNext()) {
                result2 = JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtractor2);
            }

            return Tuple.of(result1, result2, JdbcUtil.getOutParameters(cstmt, outParams));
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
     * @param <R1>
     * @param <R2>
     * @param <R3>
     * @param resultExtractor1 Don't save/return {@code ResultSet}. It will be closed after this call.
     * @param resultExtractor2 Don't save/return {@code ResultSet}. It will be closed after this call.
     * @param resultExtractor3 Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return {@code R1/R2/R3} extracted from the first three {@code ResultSets} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    @Beta
    public <R1, R2, R3> Tuple4<R1, R2, R3, Jdbc.OutParamResult> query3ResultsetsAndGetOutParameters(final Jdbc.BiResultExtractor<? extends R1> resultExtractor1,
            final Jdbc.BiResultExtractor<? extends R2> resultExtractor2, final Jdbc.BiResultExtractor<? extends R3> resultExtractor3) throws SQLException {
        checkArgNotNull(resultExtractor1, "resultExtractor1");
        checkArgNotNull(resultExtractor2, "resultExtractor2");
        checkArgNotNull(resultExtractor3, "resultExtractor3");
        assertNotClosed();

        CheckedIterator<ResultSet, SQLException> iter = null;

        try {
            JdbcUtil.execute(cstmt);

            iter = JdbcUtil.iterateAllResultSets(cstmt);

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

            return Tuple.of(result1, result2, result3, JdbcUtil.getOutParameters(cstmt, outParams));
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
     * @param <T>
     * @param targetType
     * @return the {@code List<T>} extracted from first {@code ResultSet} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <T> Tuple2<List<T>, Jdbc.OutParamResult> listAndGetOutParameters(final Class<? extends T> targetType) throws SQLException {
        checkArgNotNull(targetType, "targetType");

        return listAndGetOutParameters(Jdbc.BiRowMapper.to(targetType));
    }

    /**
     *
     * @param <T>
     * @param rowMapper
     * @return the {@code List<T>} extracted from first {@code ResultSet} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <T> Tuple2<List<T>, Jdbc.OutParamResult> listAndGetOutParameters(final Jdbc.RowMapper<? extends T> rowMapper) throws SQLException {
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        try {
            final List<T> result = new ArrayList<>();
            final ResultSet rs = executeQuery();

            if (rs != null) {
                JdbcUtil.setCheckDateTypeFlag(cstmt);

                while (rs.next()) {
                    result.add(rowMapper.apply(rs));
                }
            }

            return Tuple.of(result, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            JdbcUtil.resetCheckDateTypeFlag();

            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return the {@code List<T>} extracted from first {@code ResultSet} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <T> Tuple2<List<T>, Jdbc.OutParamResult> listAndGetOutParameters(final Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends T> rowMapper)
            throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        try {
            final List<T> result = new ArrayList<>();
            final ResultSet rs = executeQuery();

            if (rs != null) {
                JdbcUtil.setCheckDateTypeFlag(cstmt);

                while (rs.next()) {
                    if (rowFilter.test(rs)) {
                        result.add(rowMapper.apply(rs));
                    }
                }
            }

            return Tuple.of(result, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            JdbcUtil.resetCheckDateTypeFlag();

            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @param <T>
     * @param rowMapper
     * @return the {@code List<T>} extracted from first {@code ResultSet} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <T> Tuple2<List<T>, Jdbc.OutParamResult> listAndGetOutParameters(final Jdbc.BiRowMapper<? extends T> rowMapper) throws SQLException {
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        try {
            final List<T> result = new ArrayList<>();
            final ResultSet rs = executeQuery();

            if (rs != null) {
                JdbcUtil.setCheckDateTypeFlag(cstmt);

                final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                while (rs.next()) {
                    result.add(rowMapper.apply(rs, columnLabels));
                }
            }

            return Tuple.of(result, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            JdbcUtil.resetCheckDateTypeFlag();

            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return the {@code List<T>} extracted from first {@code ResultSet} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <T> Tuple2<List<T>, Jdbc.OutParamResult> listAndGetOutParameters(final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowMapper<? extends T> rowMapper)
            throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        try {
            final List<T> result = new ArrayList<>();
            final ResultSet rs = executeQuery();

            if (rs != null) {
                JdbcUtil.setCheckDateTypeFlag(cstmt);

                final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                while (rs.next()) {
                    if (rowFilter.test(rs, columnLabels)) {
                        result.add(rowMapper.apply(rs, columnLabels));
                    }
                }
            }

            return Tuple.of(result, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            JdbcUtil.resetCheckDateTypeFlag();

            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @param <T>
     * @param targetType
     * @return the {@code List<T>} extracted from all {@code ResultSets} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <T> Tuple2<List<List<T>>, Jdbc.OutParamResult> listMultiResultsetsAndGetOutParameters(final Class<? extends T> targetType) throws SQLException {
        checkArgNotNull(targetType, "targetType");

        return listMultiResultsetsAndGetOutParameters(Jdbc.BiRowMapper.to(targetType));
    }

    /**
     *
     * @param <T>
     * @param rowMapper
     * @return the {@code List<T>} extracted from all {@code ResultSets} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <T> Tuple2<List<List<T>>, Jdbc.OutParamResult> listMultiResultsetsAndGetOutParameters(final Jdbc.RowMapper<? extends T> rowMapper)
            throws SQLException {
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            final List<List<T>> resultList = JdbcUtil.<T> streamAllResultSets(cstmt, rowMapper).map(CheckedStream::toList).toList();

            return Tuple.of(resultList, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return the {@code List<T>} extracted from all {@code ResultSets} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <T> Tuple2<List<List<T>>, Jdbc.OutParamResult> listMultiResultsetsAndGetOutParameters(final Jdbc.RowFilter rowFilter,
            final Jdbc.RowMapper<? extends T> rowMapper) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            final List<List<T>> resultList = JdbcUtil.<T> streamAllResultSets(cstmt, rowFilter, rowMapper).map(CheckedStream::toList).toList();

            return Tuple.of(resultList, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @param <T>
     * @param rowMapper
     * @return the {@code List<T>} extracted from all {@code ResultSets} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <T> Tuple2<List<List<T>>, Jdbc.OutParamResult> listMultiResultsetsAndGetOutParameters(final Jdbc.BiRowMapper<? extends T> rowMapper)
            throws SQLException {
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            final List<List<T>> resultList = JdbcUtil.<T> streamAllResultSets(cstmt, rowMapper).map(CheckedStream::toList).toList();

            return Tuple.of(resultList, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     *
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return the {@code List<T>} extracted from all {@code ResultSets} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <T> Tuple2<List<List<T>>, Jdbc.OutParamResult> listMultiResultsetsAndGetOutParameters(final Jdbc.BiRowFilter rowFilter,
            final Jdbc.BiRowMapper<? extends T> rowMapper) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            final List<List<T>> resultList = JdbcUtil.<T> streamAllResultSets(cstmt, rowFilter, rowMapper).map(CheckedStream::toList).toList();

            return Tuple.of(resultList, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * @see JdbcUtil#execute(PreparedStatement)
     * @see JdbcUtil#executeUpdate(PreparedStatement)
     * @see JdbcUtil#executeUpdate(PreparedStatement)
     * @see JdbcUtil#clearParameters(PreparedStatement)
     */
    @Override
    protected void closeStatement() {
        try {
            cstmt.clearParameters();
        } catch (SQLException e) {
            logger.warn("failed to reset statement", e);
        } finally {
            super.closeStatement();
        }
    }
}