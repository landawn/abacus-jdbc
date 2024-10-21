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
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.jdbc.Jdbc.BiRowMapper;
import com.landawn.abacus.jdbc.Jdbc.ResultExtractor;
import com.landawn.abacus.jdbc.Jdbc.RowMapper;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.util.CheckedStream;
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
@SuppressWarnings("java:S1192")
public final class CallableQuery extends AbstractQuery<CallableStatement, CallableQuery> {

    final CallableStatement cstmt;
    List<Jdbc.OutParam> outParams;

    CallableQuery(final CallableStatement stmt) {
        super(stmt);
        cstmt = stmt;
    }

    /**
     * Sets the {@code null}.
     *
     * @param parameterName the name of the parameter
     * @param sqlType the SQL type code defined in {@link java.sql.Types}
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     * @see java.sql.Types
     */
    public CallableQuery setNull(final String parameterName, final int sqlType) throws SQLException {
        cstmt.setNull(parameterName, sqlType);

        return this;
    }

    /**
     * Sets the {@code null} value for the specified parameter.
     *
     * @param parameterName the name of the parameter
     * @param sqlType the SQL type code defined in {@link java.sql.Types}
     * @param typeName the SQL type name
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     * @see java.sql.Types
     */
    public CallableQuery setNull(final String parameterName, final int sqlType, final String typeName) throws SQLException {
        cstmt.setNull(parameterName, sqlType, typeName);

        return this;
    }

    /**
     * Sets the boolean value for the specified parameter.
     *
     * @param parameterName the name of the parameter
     * @param x the boolean value to set
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setBoolean(final String parameterName, final boolean x) throws SQLException {
        cstmt.setBoolean(parameterName, x);

        return this;
    }

    /**
     * Sets the boolean value for the specified parameter.
     *
     * @param parameterName the name of the parameter
     * @param x the boolean value to set, or null to set the parameter to SQL NULL
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setBoolean(final String parameterName, final Boolean x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, java.sql.Types.BOOLEAN);
        } else {
            cstmt.setBoolean(parameterName, x);
        }

        return this;
    }

    /**
     * Sets the byte value for the specified parameter.
     *
     * @param parameterName the name of the parameter
     * @param x the byte value to set
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setByte(final String parameterName, final byte x) throws SQLException {
        cstmt.setByte(parameterName, x);

        return this;
    }

    /**
     * Sets the byte value for the specified parameter.
     *
     * @param parameterName the name of the parameter
     * @param x the byte value to set, or null to set the parameter to SQL NULL
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setByte(final String parameterName, final Byte x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, java.sql.Types.TINYINT);
        } else {
            cstmt.setByte(parameterName, x);
        }

        return this;
    }

    /**
     * Sets the short value for the specified parameter.
     *
     * @param parameterName the name of the parameter
     * @param x the short value to set
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setShort(final String parameterName, final short x) throws SQLException {
        cstmt.setShort(parameterName, x);

        return this;
    }

    /**
     * Sets the short value for the specified parameter.
     *
     * @param parameterName the name of the parameter
     * @param x the short value to set, or null to set the parameter to SQL NULL
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setShort(final String parameterName, final Short x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, java.sql.Types.SMALLINT);
        } else {
            cstmt.setShort(parameterName, x);
        }

        return this;
    }

    /**
     * Sets the int value for the specified parameter.
     *
     * @param parameterName the name of the parameter
     * @param x the int value to set
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setInt(final String parameterName, final int x) throws SQLException {
        cstmt.setInt(parameterName, x);

        return this;
    }

    /**
     * Sets the int value for the specified parameter.
     *
     * @param parameterName the name of the parameter
     * @param x the int value to set, or null to set the parameter to SQL NULL
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setInt(final String parameterName, final Integer x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, java.sql.Types.INTEGER);
        } else {
            cstmt.setInt(parameterName, x);
        }

        return this;
    }

    /**
     * Sets the long value for the specified parameter.
     *
     * @param parameterName the name of the parameter
     * @param x the long value to set
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setLong(final String parameterName, final long x) throws SQLException {
        cstmt.setLong(parameterName, x);

        return this;
    }

    /**
     * Sets the long value for the specified parameter.
     *
     * @param parameterName the name of the parameter
     * @param x the long value to set, or null to set the parameter to SQL NULL
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setLong(final String parameterName, final Long x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, java.sql.Types.BIGINT);
        } else {
            cstmt.setLong(parameterName, x);
        }

        return this;
    }

    /**
     * Sets the long value for the specified parameter using a BigInteger.
     *
     * @param parameterName the name of the parameter
     * @param x the BigInteger value to set, or null to set the parameter to SQL NULL
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setLong(final String parameterName, final BigInteger x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, Types.BIGINT);
        } else {
            cstmt.setLong(parameterName, x.longValueExact());
        }

        return this;
    }

    /**
     * Sets the float value for the specified parameter.
     *
     * @param parameterName the name of the parameter
     * @param x the float value to set
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setFloat(final String parameterName, final float x) throws SQLException {
        cstmt.setFloat(parameterName, x);

        return this;
    }

    /**
     * Sets the float value for the specified parameter.
     *
     * @param parameterName the name of the parameter
     * @param x the float value to set, or null to set the parameter to SQL NULL
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setFloat(final String parameterName, final Float x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, java.sql.Types.FLOAT);
        } else {
            cstmt.setFloat(parameterName, x);
        }

        return this;
    }

    /**
     * Sets the double value for the specified parameter.
     *
     * @param parameterName the name of the parameter
     * @param x the double value to set
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setDouble(final String parameterName, final double x) throws SQLException {
        cstmt.setDouble(parameterName, x);

        return this;
    }

    /**
     * Sets the double value for the specified parameter.
     *
     * @param parameterName the name of the parameter
     * @param x the double value to set, or null to set the parameter to SQL NULL
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setDouble(final String parameterName, final Double x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, java.sql.Types.DOUBLE);
        } else {
            cstmt.setDouble(parameterName, x);
        }

        return this;
    }

    /**
     * Sets the BigDecimal value for the specified parameter.
     *
     * @param parameterName the name of the parameter
     * @param x the BigDecimal value to set
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setBigDecimal(final String parameterName, final BigDecimal x) throws SQLException {
        cstmt.setBigDecimal(parameterName, x);

        return this;
    }

    /**
     * Sets the BigDecimal value for the specified parameter using a BigInteger.
     *
     * @param parameterName the name of the parameter
     * @param x the BigInteger value to set, or null to set the parameter to SQL NULL
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setBigDecimal(final String parameterName, final BigInteger x) throws SQLException {
        if (x == null) {
            cstmt.setNull(parameterName, Types.DECIMAL);
        } else {
            cstmt.setBigDecimal(parameterName, new BigDecimal(x));
        }

        return this;
    }

    /**
     * Sets the string value for the specified parameter using a BigInteger.
     *
     * @param parameterName the name of the parameter
     * @param x the BigInteger value to set, or null to set the parameter to SQL NULL
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     * @see #setString(String, BigInteger)
     * @see #setBigDecimal(String, BigInteger)
     * @see #setLong(String, BigInteger)
     */
    @Beta
    public CallableQuery setBigIntegerAsString(final String parameterName, final BigInteger x) throws SQLException {
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
    public CallableQuery setString(final String parameterName, final String x) throws SQLException {
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
    public CallableQuery setString(final String parameterName, final CharSequence x) throws SQLException {
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
    public CallableQuery setString(final String parameterName, final char x) throws SQLException {
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
    public CallableQuery setString(final String parameterName, final Character x) throws SQLException {
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
    public CallableQuery setString(final String parameterName, final BigInteger x) throws SQLException {
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
    public CallableQuery setNString(final String parameterName, final String x) throws SQLException {
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
    public CallableQuery setDate(final String parameterName, final java.sql.Date x) throws SQLException {
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
    public CallableQuery setDate(final String parameterName, final java.util.Date x) throws SQLException {
        cstmt.setDate(parameterName, x == null ? null : x instanceof java.sql.Date ? (java.sql.Date) x : new java.sql.Date(x.getTime()));

        return this;
    }

    /**
     * Sets the date value for the specified parameter using a LocalDate.
     *
     * @param parameterName the name of the parameter
     * @param x the LocalDate value to set, or null to set the parameter to SQL NULL
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setDate(final String parameterName, final LocalDate x) throws SQLException {
        setDate(parameterName, x == null ? null : java.sql.Date.valueOf(x));

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
    public CallableQuery setTime(final String parameterName, final java.sql.Time x) throws SQLException {
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
    public CallableQuery setTime(final String parameterName, final java.util.Date x) throws SQLException {
        cstmt.setTime(parameterName, x == null ? null : x instanceof java.sql.Time ? (java.sql.Time) x : new java.sql.Time(x.getTime()));

        return this;
    }

    /**
     * Sets the time value for the specified parameter using a LocalTime.
     *
     * @param parameterName the name of the parameter
     * @param x the LocalTime value to set, or null to set the parameter to SQL NULL
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setTime(final String parameterName, final LocalTime x) throws SQLException {
        setTime(parameterName, x == null ? null : java.sql.Time.valueOf(x));

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
    public CallableQuery setTimestamp(final String parameterName, final java.sql.Timestamp x) throws SQLException {
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
    public CallableQuery setTimestamp(final String parameterName, final java.util.Date x) throws SQLException {
        cstmt.setTimestamp(parameterName, x == null ? null : x instanceof java.sql.Timestamp ? (java.sql.Timestamp) x : new java.sql.Timestamp(x.getTime()));

        return this;
    }

    /**
     * Sets the timestamp value for the specified parameter using a LocalDateTime.
     *
     * @param parameterName the name of the parameter
     * @param x the LocalDateTime value to set, or null to set the parameter to SQL NULL
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setTimestamp(final String parameterName, final LocalDateTime x) throws SQLException {
        setTimestamp(parameterName, x == null ? null : Timestamp.valueOf(x));

        return this;
    }

    /**
     * Sets the timestamp value for the specified parameter using a ZonedDateTime.
     *
     * @param parameterName the name of the parameter
     * @param x the ZonedDateTime value to set, or null to set the parameter to SQL NULL
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setTimestamp(final String parameterName, final ZonedDateTime x) throws SQLException {
        setTimestamp(parameterName, x == null ? null : Timestamp.from(x.toInstant()));

        return this;
    }

    /**
     * Sets the timestamp value for the specified parameter using an OffsetDateTime.
     *
     * @param parameterName the name of the parameter
     * @param x the OffsetDateTime value to set, or null to set the parameter to SQL NULL
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setTimestamp(final String parameterName, final OffsetDateTime x) throws SQLException {
        setTimestamp(parameterName, x == null ? null : Timestamp.from(x.toInstant()));

        return this;
    }

    /**
     * Sets the timestamp value for the specified parameter using an Instant.
     *
     * @param parameterName the name of the parameter
     * @param x the Instant value to set, or null to set the parameter to SQL NULL
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setTimestamp(final String parameterName, final Instant x) throws SQLException {
        setTimestamp(parameterName, x == null ? null : Timestamp.from(x));

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
    public CallableQuery setBytes(final String parameterName, final byte[] x) throws SQLException {
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
    public CallableQuery setAsciiStream(final String parameterName, final InputStream inputStream) throws SQLException {
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
    public CallableQuery setAsciiStream(final String parameterName, final InputStream inputStream, final long length) throws SQLException {
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
    public CallableQuery setBinaryStream(final String parameterName, final InputStream inputStream) throws SQLException {
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
    public CallableQuery setBinaryStream(final String parameterName, final InputStream inputStream, final long length) throws SQLException {
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
    public CallableQuery setCharacterStream(final String parameterName, final Reader reader) throws SQLException {
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
    public CallableQuery setCharacterStream(final String parameterName, final Reader reader, final long length) throws SQLException {
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
    public CallableQuery setNCharacterStream(final String parameterName, final Reader reader) throws SQLException {
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
    public CallableQuery setNCharacterStream(final String parameterName, final Reader reader, final long length) throws SQLException {
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
    public CallableQuery setBlob(final String parameterName, final java.sql.Blob x) throws SQLException {
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
    public CallableQuery setBlob(final String parameterName, final InputStream inputStream) throws SQLException {
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
    public CallableQuery setBlob(final String parameterName, final InputStream inputStream, final long length) throws SQLException {
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
    public CallableQuery setClob(final String parameterName, final java.sql.Clob x) throws SQLException {
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
    public CallableQuery setClob(final String parameterName, final Reader reader) throws SQLException {
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
    public CallableQuery setClob(final String parameterName, final Reader reader, final long length) throws SQLException {
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
    public CallableQuery setNClob(final String parameterName, final java.sql.NClob x) throws SQLException {
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
    public CallableQuery setNClob(final String parameterName, final Reader reader) throws SQLException {
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
    public CallableQuery setNClob(final String parameterName, final Reader reader, final long length) throws SQLException {
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
    public CallableQuery setURL(final String parameterName, final URL x) throws SQLException {
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
    public CallableQuery setSQLXML(final String parameterName, final java.sql.SQLXML x) throws SQLException {
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
    public CallableQuery setRowId(final String parameterName, final java.sql.RowId x) throws SQLException {
        cstmt.setRowId(parameterName, x);

        return this;
    }

    /**
     * Sets the object value for the specified parameter.
     *
     * @param parameterName the name of the parameter
     * @param x the object value to set
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setObject(final String parameterName, final Object x) throws SQLException {
        if (x == null) {
            cstmt.setObject(parameterName, x);
        } else {
            N.typeOf(x.getClass()).set(cstmt, parameterName, x);
        }

        return this;
    }

    /**
     * Sets the object value for the specified parameter.
     *
     * @param parameterName the name of the parameter
     * @param x the object value to set
     * @param sqlType the SQL type code defined in {@link java.sql.Types}
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     * @see java.sql.Types
     */
    public CallableQuery setObject(final String parameterName, final Object x, final int sqlType) throws SQLException {
        cstmt.setObject(parameterName, x, sqlType);

        return this;
    }

    /**
     * Sets the object value for the specified parameter.
     *
     * @param parameterName the name of the parameter
     * @param x the object value to set
     * @param sqlType the SQL type code defined in {@link java.sql.Types}
     * @param scaleOrLength the number of digits after the decimal point for numeric types, or the length for other types
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     * @see java.sql.Types
     */
    public CallableQuery setObject(final String parameterName, final Object x, final int sqlType, final int scaleOrLength) throws SQLException {
        cstmt.setObject(parameterName, x, sqlType, scaleOrLength);

        return this;
    }

    /**
     * Sets the parameters for the CallableQuery.
     *
     * @param parameters a map containing parameter names and their corresponding values
     * @return the current instance of {@code CallableQuery}
     * @throws IllegalArgumentException if the parameters map is null
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery setParameters(final Map<String, ?> parameters) throws IllegalArgumentException, SQLException {
        checkArgNotNull(parameters, s.parameters);

        for (final Map.Entry<String, ?> entry : parameters.entrySet()) {
            setObject(entry.getKey(), entry.getValue());
        }

        return this;
    }

    /**
     * Sets the parameters for the CallableQuery using the specified entity and parameter names.
     *
     * @param entity the entity object containing the parameters
     * @param parameterNames a collection of parameter names to be set
     * @return the current instance of {@code NamedQuery}
     * @throws IllegalArgumentException if the entity or parameter names are null
     * @throws SQLException if a database access error occurs
     * @see ClassUtil#getPropNameList(Class)
     * @see ClassUtil#getPropNames(Class, Collection)
     * @see ClassUtil#getpropNames(Class, Set)
     * @see JdbcUtil#getNamedParameters(String)
     */
    public CallableQuery setParameters(final Object entity, final List<String> parameterNames) throws IllegalArgumentException, SQLException {
        checkArgNotNull(entity, s.entity);
        checkArgNotNull(parameterNames, s.parameterNames);

        final Class<?> cls = entity.getClass();
        final BeanInfo entityInfo = ParserUtil.getBeanInfo(cls);
        PropInfo propInfo = null;

        for (final String parameterName : parameterNames) {
            propInfo = entityInfo.getPropInfo(parameterName);
            propInfo.dbType.set(cstmt, parameterName, propInfo.getPropValue(entity));
        }

        return this;
    }

    /**
     * Registers the specified parameter to be an OUT parameter.
     *
     * @param parameterIndex the index of the parameter (starts from 1, not 0)
     * @param sqlType the SQL type code defined in {@link java.sql.Types}
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     * @see java.sql.CallableStatement#registerOutParameter(int, int)
     * @see java.sql.Types
     */
    public CallableQuery registerOutParameter(final int parameterIndex, final int sqlType) throws SQLException {
        cstmt.registerOutParameter(parameterIndex, sqlType);

        addOutParameters(new Jdbc.OutParam(parameterIndex, null, sqlType, null, -1));

        return this;
    }

    /**
     * Registers the specified parameter to be an OUT parameter with a specified scale.
     *
     * @param parameterIndex the index of the parameter (starts from 1, not 0)
     * @param sqlType the SQL type code defined in {@link java.sql.Types}
     * @param scale the number of digits to the right of the decimal point
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     * @see java.sql.CallableStatement#registerOutParameter(int, int, int
     * @see java.sql.Types
     */
    public CallableQuery registerOutParameter(final int parameterIndex, final int sqlType, final int scale) throws SQLException {
        cstmt.registerOutParameter(parameterIndex, sqlType, scale);

        addOutParameters(new Jdbc.OutParam(parameterIndex, null, sqlType, null, scale));

        return this;
    }

    /**
     * Registers the specified parameter to be an OUT parameter with a specified type name.
     *
     * @param parameterIndex the index of the parameter (starts from 1, not 0)
     * @param sqlType the SQL type code defined in {@link java.sql.Types}
     * @param typeName the SQL type name
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     * @see java.sql.CallableStatement#registerOutParameter(int, int, String)
     * @see java.sql.Types
     */
    public CallableQuery registerOutParameter(final int parameterIndex, final int sqlType, final String typeName) throws SQLException {
        cstmt.registerOutParameter(parameterIndex, sqlType, typeName);

        addOutParameters(new Jdbc.OutParam(parameterIndex, null, sqlType, typeName, -1));

        return this;
    }

    /**
     * Registers the specified parameter to be an OUT parameter.
     *
     * @param parameterName the name of the parameter
     * @param sqlType the SQL type code defined in {@link java.sql.Types}
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     * @see java.sql.CallableStatement#registerOutParameter(String, int)
     * @see java.sql.Types
     */
    public CallableQuery registerOutParameter(final String parameterName, final int sqlType) throws SQLException {
        cstmt.registerOutParameter(parameterName, sqlType);

        addOutParameters(new Jdbc.OutParam(-1, parameterName, sqlType, null, -1));

        return this;
    }

    /**
     * Registers the specified parameter to be an OUT parameter with a specified scale.
     *
     * @param parameterName the name of the parameter
     * @param sqlType the SQL type code defined in {@link java.sql.Types}
     * @param scale the number of digits to the right of the decimal point
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     * @see java.sql.CallableStatement#registerOutParameter(String, int, int)
     * @see java.sql.Types
     */
    public CallableQuery registerOutParameter(final String parameterName, final int sqlType, final int scale) throws SQLException {
        cstmt.registerOutParameter(parameterName, sqlType, scale);

        addOutParameters(new Jdbc.OutParam(-1, parameterName, sqlType, null, scale));

        return this;
    }

    /**
     * Registers the specified parameter to be an OUT parameter with a specified type name.
     *
     * @param parameterName the name of the parameter
     * @param sqlType the SQL type code defined in {@link java.sql.Types}
     * @param typeName the SQL type name
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     * @see java.sql.CallableStatement#registerOutParameter(String, int, String)
     * @see java.sql.Types
     */
    public CallableQuery registerOutParameter(final String parameterName, final int sqlType, final String typeName) throws SQLException {
        cstmt.registerOutParameter(parameterName, sqlType, typeName);

        addOutParameters(new Jdbc.OutParam(-1, parameterName, sqlType, typeName, -1));

        return this;
    }

    /**
     * Registers the specified parameter to be an OUT parameter.
     *
     * @param parameterIndex the index of the parameter (starts from 1, not 0)
     * @param sqlType the SQL type code defined in {@link java.sql.SQLType}
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     * @see java.sql.CallableStatement#registerOutParameter(int, java.sql.SQLType)
     */
    public CallableQuery registerOutParameter(final int parameterIndex, final SQLType sqlType) throws SQLException {
        cstmt.registerOutParameter(parameterIndex, sqlType);

        addOutParameters(new Jdbc.OutParam(parameterIndex, null, sqlType.getVendorTypeNumber(), null, -1));

        return this;
    }

    /**
     * Registers the specified parameter to be an OUT parameter with a specified scale.
     *
     * @param parameterIndex the index of the parameter (starts from 1, not 0)
     * @param sqlType the SQL type code defined in {@link java.sql.SQLType}
     * @param scale the number of digits to the right of the decimal point
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     * @see java.sql.CallableStatement#registerOutParameter(int, java.sql.SQLType, int)
     */
    public CallableQuery registerOutParameter(final int parameterIndex, final SQLType sqlType, final int scale) throws SQLException {
        cstmt.registerOutParameter(parameterIndex, sqlType, scale);

        addOutParameters(new Jdbc.OutParam(parameterIndex, null, sqlType.getVendorTypeNumber(), null, scale));

        return this;
    }

    /**
     * Registers the specified parameter to be an OUT parameter with a specified type name.
     *
     * @param parameterIndex the index of the parameter (starts from 1, not 0)
     * @param sqlType the SQL type code defined in {@link java.sql.SQLType}
     * @param typeName the SQL type name
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     * @see java.sql.CallableStatement#registerOutParameter(int, java.sql.SQLType, String)
     */
    public CallableQuery registerOutParameter(final int parameterIndex, final SQLType sqlType, final String typeName) throws SQLException {
        cstmt.registerOutParameter(parameterIndex, sqlType, typeName);

        addOutParameters(new Jdbc.OutParam(parameterIndex, null, sqlType.getVendorTypeNumber(), typeName, -1));

        return this;
    }

    /**
     * Registers the specified parameter to be an OUT parameter.
     *
     * @param parameterName the name of the parameter
     * @param sqlType the SQL type code defined in {@link java.sql.SQLType}
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     * @see java.sql.CallableStatement#registerOutParameter(String, java.sql.SQLType)
     */
    public CallableQuery registerOutParameter(final String parameterName, final SQLType sqlType) throws SQLException {
        cstmt.registerOutParameter(parameterName, sqlType);

        addOutParameters(new Jdbc.OutParam(-1, parameterName, sqlType.getVendorTypeNumber(), null, -1));

        return this;
    }

    /**
     * Registers the specified parameter to be an OUT parameter with a specified scale.
     *
     * @param parameterName the name of the parameter
     * @param sqlType the SQL type code defined in {@link java.sql.SQLType}
     * @param scale the number of digits to the right of the decimal point
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     * @see java.sql.CallableStatement#registerOutParameter(String, java.sql.SQLType, int)
     */
    public CallableQuery registerOutParameter(final String parameterName, final SQLType sqlType, final int scale) throws SQLException {
        cstmt.registerOutParameter(parameterName, sqlType, scale);

        addOutParameters(new Jdbc.OutParam(-1, parameterName, sqlType.getVendorTypeNumber(), null, scale));

        return this;
    }

    /**
     * Registers the specified parameter to be an OUT parameter with a specified type name.
     *
     * @param parameterName the name of the parameter
     * @param sqlType the SQL type code defined in {@link java.sql.SQLType}
     * @param typeName the SQL type name
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     * @see java.sql.CallableStatement#registerOutParameter(String, java.sql.SQLType, String)
     */
    public CallableQuery registerOutParameter(final String parameterName, final SQLType sqlType, final String typeName) throws SQLException {
        cstmt.registerOutParameter(parameterName, sqlType, typeName);

        addOutParameters(new Jdbc.OutParam(-1, parameterName, sqlType.getVendorTypeNumber(), typeName, -1));

        return this;
    }

    /**
     * Registers multiple OUT parameters using the specified ParametersSetter.
     *
     * @param register the ParametersSetter to register the OUT parameters
     * @return the current instance of {@code CallableQuery}
     * @throws IllegalArgumentException if the register is null
     * @throws SQLException if a database access error occurs
     */
    public CallableQuery registerOutParameters(final Jdbc.ParametersSetter<? super CallableQuery> register) throws IllegalArgumentException, SQLException {
        checkArgNotNull(register, s.register);

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
     * Registers multiple OUT parameters using the specified BiParametersSetter.
     *
     * @param <T> the type of the parameter
     * @param parameter the parameter to be used in the BiParametersSetter
     * @param register the BiParametersSetter to register the OUT parameters
     * @return the current instance of {@code CallableQuery}
     * @throws SQLException if a database access error occurs
     */
    public <T> CallableQuery registerOutParameters(final T parameter, final Jdbc.BiParametersSetter<? super CallableQuery, ? super T> register)
            throws SQLException {
        checkArgNotNull(register, s.register);

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

    private void addOutParameters(final Jdbc.OutParam outParameter) {
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
     * Executes the CallableStatement and applies the provided function to the result.
     *
     * @param <R> the type of the result
     * @param getter the function to apply to the CallableStatement
     * @return the result of applying the function to the CallableStatement
     * @throws SQLException if a database access error occurs
     * @see JdbcUtil#getOutParameters(CallableStatement, List)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, RowMapper)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, BiRowMapper)
     */
    @Override
    public <R> R executeThenApply(final Throwables.Function<? super CallableStatement, ? extends R, SQLException> getter) throws SQLException { //NOSONAR
        return super.executeThenApply(getter);
    }

    /**
     * Executes the CallableStatement and applies the provided BiFunction to the result.
     *
     * @param <R> the type of the result
     * @param getter the BiFunction to apply to the CallableStatement. The first parameter indicates if the first result is a {@code ResultSet} object,
     *                 the second parameter is the executed {@code CallableStatement}.
     * @return the result of applying the BiFunction to the CallableStatement
     * @throws SQLException if a database access error occurs
     * @see JdbcUtil#getOutParameters(CallableStatement, List)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, RowMapper)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, BiRowMapper)
     */
    @Override
    public <R> R executeThenApply(final Throwables.BiFunction<Boolean, ? super CallableStatement, ? extends R, SQLException> getter) throws SQLException { //NOSONAR
        return super.executeThenApply(getter);
    }

    /**
     * Executes the CallableStatement and applies the provided TriFunction to the result.
     *
     * @param <R> the type of the result
     * @param getter the TriFunction to apply to the CallableStatement. The first parameter indicates if the first result is a {@code ResultSet} object,
     *               the second parameter is the list of OUT parameters, and the third parameter is the executed {@code CallableStatement}.
     * @return the result of applying the TriFunction to the CallableStatement
     * @throws SQLException if a database access error occurs
     * @see JdbcUtil#getOutParameters(CallableStatement, List)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, RowMapper)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, BiRowMapper)
     */
    public <R> R executeThenApply(final Throwables.TriFunction<Boolean, List<Jdbc.OutParam>, ? super CallableStatement, ? extends R, SQLException> getter)
            throws SQLException {
        checkArgNotNull(getter, s.getter);
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
     * Executes the CallableStatement and applies the provided consumer to the statement.
     *
     * @param consumer the consumer to apply to the CallableStatement
     * @throws SQLException if a database access error occurs
     * @see JdbcUtil#getOutParameters(CallableStatement, List)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, RowMapper)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, BiRowMapper)
     */
    @Override
    public void executeThenAccept(final Throwables.Consumer<? super CallableStatement, SQLException> consumer) throws SQLException { //NOSONAR
        super.executeThenAccept(consumer);
    }

    /**
     * Executes the CallableStatement and applies the provided BiConsumer to the statement.
     *
     * @param consumer the BiConsumer to apply to the CallableStatement. The first parameter indicates if the first result is a {@code ResultSet} object,
     *                 the second parameter is the executed {@code CallableStatement}.
     * @throws SQLException if a database access error occurs
     * @see JdbcUtil#getOutParameters(CallableStatement, List)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, RowMapper)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, BiRowMapper)
     */
    @Override
    public void executeThenAccept(final Throwables.BiConsumer<Boolean, ? super CallableStatement, SQLException> consumer) throws SQLException { //NOSONAR
        super.executeThenAccept(consumer);
    }

    /**
     * Executes the CallableStatement and applies the provided TriConsumer to the statement.
     *
     * @param consumer the TriConsumer to apply to the CallableStatement. The first parameter indicates if the first result is a {@code ResultSet} object,
     *                 the second parameter is the list of OUT parameters, and the third parameter is the executed {@code CallableStatement}.
     * @throws SQLException if a database access error occurs
     * @see JdbcUtil#getOutParameters(CallableStatement, List)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, RowMapper)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, BiRowMapper)
     */
    public void executeThenAccept(final Throwables.TriConsumer<Boolean, List<Jdbc.OutParam>, ? super CallableStatement, SQLException> consumer)
            throws SQLException {
        checkArgNotNull(consumer, s.consumer);
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
     * Executes the stored procedure and returns the out parameters.
     *
     * @return a list of {@code Out Parameters}.
     * @throws IllegalStateException if this is closed
     * @throws SQLException if a database access error occurs
     */
    public Jdbc.OutParamResult executeAndGetOutParameters() throws IllegalStateException, SQLException {
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            return JdbcUtil.getOutParameters(cstmt, outParams);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the stored procedure and returns the result set and out parameters.
     * The result set is converted to a DataSet object.
     *
     * @return A Tuple2 object where the first element is a DataSet object that represents the result set returned by the stored procedure,
     *         and the second element is a Jdbc.OutParamResult object that contains the out parameters returned by the stored procedure.
     * @throws SQLException if a database access error occurs or this method is called on a closed CallableStatement
     */
    public Tuple2<DataSet, Jdbc.OutParamResult> queryAndGetOutParameters() throws SQLException {
        return queryAndGetOutParameters(Jdbc.ResultExtractor.TO_DATA_SET);
    }

    /**
     * Executes the stored procedure and returns the result set and out parameters.
     * The result set is converted to a type R object using the provided ResultExtractor.
     *
     * @param <R> The type of the object that the result set will be converted to.
     * @param resultExtractor The ResultExtractor to use for converting the result set to an object of type R.
     * @return A Tuple2 object where the first element is an object of type R that represents the result set returned by the stored procedure,
     *         and the second element is a Jdbc.OutParamResult object that contains the out parameters returned by the stored procedure.
     * @throws IllegalArgumentException if {@code resultExtractor} is {@code null}.
     * @throws IllegalStateException if this method is called on a closed CallableStatement.
     * @throws SQLException if a database access error occurs.
     */
    public <R> Tuple2<R, Jdbc.OutParamResult> queryAndGetOutParameters(final Jdbc.ResultExtractor<? extends R> resultExtractor)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(resultExtractor, s.resultExtractor);
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
     * Executes the stored procedure and returns the result set and out parameters.
     * The result set is converted to a type R object using the provided BiResultExtractor.
     *
     * @param <R> The type of the object that the result set will be converted to.
     * @param resultExtractor The BiResultExtractor to use for converting the result set to an object of type R.
     * @return A Tuple2 object where the first element is an object of type R that represents the result set returned by the stored procedure,
     *         and the second element is a Jdbc.OutParamResult object that contains the out parameters returned by the stored procedure.
     * @throws IllegalArgumentException if {@code resultExtractor} is {@code null}.
     * @throws IllegalStateException if this method is called on a closed CallableStatement.
     * @throws SQLException if a database access error occurs.
     */
    public <R> Tuple2<R, Jdbc.OutParamResult> queryAndGetOutParameters(final Jdbc.BiResultExtractor<? extends R> resultExtractor)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(resultExtractor, s.resultExtractor);
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
     * Executes the stored procedure and returns all result sets and out parameters.
     * Each result set is converted to a DataSet object.
     *
     * @return A Tuple2 object where the first element is a List of DataSet objects, each representing a result set returned by the stored procedure,
     *         and the second element is a Jdbc.OutParamResult object that contains the out parameters returned by the stored procedure.
     * @throws SQLException if a database access error occurs or this method is called on a closed CallableStatement
     */
    public Tuple2<List<DataSet>, Jdbc.OutParamResult> queryAllResultsetsAndGetOutParameters() throws SQLException {
        return queryAllResultsetsAndGetOutParameters(ResultExtractor.TO_DATA_SET);
    }

    /**
     * Executes the stored procedure and returns all result sets and out parameters.
     * Each result set is converted to a type R object using the provided ResultExtractor.
     *
     * @param <R> The type of the object that each result set will be converted to.
     * @param resultExtractor The ResultExtractor to use for converting each result set to an object of type R. Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return A Tuple2 object where the first element is a List of objects of type R, each representing a result set returned by the stored procedure,
     *         and the second element is a Jdbc.OutParamResult object that contains the out parameters returned by the stored procedure.
     * @throws IllegalArgumentException if {@code resultExtractor} is {@code null}.
     * @throws IllegalStateException if this method is called on a closed CallableStatement.
     * @throws SQLException if a database access error occurs.
     */
    public <R> Tuple2<List<R>, Jdbc.OutParamResult> queryAllResultsetsAndGetOutParameters(final Jdbc.ResultExtractor<? extends R> resultExtractor)
            throws SQLException {
        checkArgNotNull(resultExtractor, s.resultExtractor);
        assertNotClosed();

        Throwables.Iterator<ResultSet, SQLException> iter = null;

        try {
            final boolean isResultSet = JdbcUtil.execute(cstmt);

            iter = JdbcUtil.iterateAllResultSets(cstmt, isResultSet);

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
     * Executes the stored procedure and returns all result sets and out parameters.
     * Each result set is converted to a type R object using the provided BiResultExtractor.
     *
     * @param <R> The type of the object that each result set will be converted to.
     * @param resultExtractor The BiResultExtractor to use for converting each result set to an object of type R. Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return A Tuple2 object where the first element is a List of objects of type R, each representing a result set returned by the stored procedure,
     *         and the second element is a Jdbc.OutParamResult object that contains the out parameters returned by the stored procedure.
     * @throws IllegalArgumentException if {@code resultExtractor} is {@code null}.
     * @throws IllegalStateException if this method is called on a closed CallableStatement.
     * @throws SQLException if a database access error occurs.
     */
    public <R> Tuple2<List<R>, Jdbc.OutParamResult> queryAllResultsetsAndGetOutParameters(final Jdbc.BiResultExtractor<? extends R> resultExtractor)
            throws SQLException {
        checkArgNotNull(resultExtractor, s.resultExtractor);
        assertNotClosed();

        Throwables.Iterator<ResultSet, SQLException> iter = null;

        try {
            final boolean isResultSet = JdbcUtil.execute(cstmt);

            iter = JdbcUtil.iterateAllResultSets(cstmt, isResultSet);

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
     * Executes the stored procedure and returns the first two result sets and out parameters.
     * Each result set is converted to a type R1 and R2 object using the provided BiResultExtractors.
     *
     * @param <R1> The type of the object that the first result set will be converted to.
     * @param <R2> The type of the object that the second result set will be converted to.
     * @param resultExtractor1 The BiResultExtractor to use for converting the first result set to an object of type R1.
     * @param resultExtractor2 The BiResultExtractor to use for converting the second result set to an object of type R2.
     * @return A Tuple3 object where the first element is an object of type R1 that represents the first result set returned by the stored procedure,
     *         the second element is an object of type R2 that represents the second result set returned by the stored procedure,
     *         and the third element is a Jdbc.OutParamResult object that contains the out parameters returned by the stored procedure.
     * @throws IllegalArgumentException if resultExtractor1 or resultExtractor2 is {@code null}.
     * @throws IllegalStateException if this method is called on a closed CallableStatement.
     * @throws SQLException if a database access error occurs.
     */
    @Beta
    public <R1, R2> Tuple3<R1, R2, Jdbc.OutParamResult> query2ResultsetsAndGetOutParameters(final Jdbc.BiResultExtractor<? extends R1> resultExtractor1,
            final Jdbc.BiResultExtractor<? extends R2> resultExtractor2) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(resultExtractor1, s.resultExtractor1);
        checkArgNotNull(resultExtractor2, s.resultExtractor2);
        assertNotClosed();

        Throwables.Iterator<ResultSet, SQLException> iter = null;

        try {
            final boolean isResultSet = JdbcUtil.execute(cstmt);

            iter = JdbcUtil.iterateAllResultSets(cstmt, isResultSet);

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
     * Executes the stored procedure and returns the first three result sets and out parameters.
     * Each result set is converted to a type R1, R2, and R3 object using the provided BiResultExtractors.
     *
     * @param <R1> The type of the object that the first result set will be converted to.
     * @param <R2> The type of the object that the second result set will be converted to.
     * @param <R3> The type of the object that the third result set will be converted to.
     * @param resultExtractor1 The BiResultExtractor to use for converting the first result set to an object of type R1.
     * @param resultExtractor2 The BiResultExtractor to use for converting the second result set to an object of type R2.
     * @param resultExtractor3 The BiResultExtractor to use for converting the third result set to an object of type R3.
     * @return A Tuple4 object where the first element is an object of type R1 that represents the first result set returned by the stored procedure,
     *         the second element is an object of type R2 that represents the second result set returned by the stored procedure,
     *         the third element is an object of type R3 that represents the third result set returned by the stored procedure,
     *         and the fourth element is a Jdbc.OutParamResult object that contains the out parameters returned by the stored procedure.
     * @throws IllegalArgumentException if resultExtractor1, resultExtractor2, or resultExtractor3 is {@code null}.
     * @throws IllegalStateException if this method is called on a closed CallableStatement.
     * @throws SQLException if a database access error occurs.
     */
    @Beta
    public <R1, R2, R3> Tuple4<R1, R2, R3, Jdbc.OutParamResult> query3ResultsetsAndGetOutParameters(final Jdbc.BiResultExtractor<? extends R1> resultExtractor1,
            final Jdbc.BiResultExtractor<? extends R2> resultExtractor2, final Jdbc.BiResultExtractor<? extends R3> resultExtractor3)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(resultExtractor1, s.resultExtractor1);
        checkArgNotNull(resultExtractor2, s.resultExtractor2);
        checkArgNotNull(resultExtractor3, s.resultExtractor3);
        assertNotClosed();

        Throwables.Iterator<ResultSet, SQLException> iter = null;

        try {
            final boolean isResultSet = JdbcUtil.execute(cstmt);

            iter = JdbcUtil.iterateAllResultSets(cstmt, isResultSet);

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
     * Executes the stored procedure and returns the result set and out parameters.
     * The result set is converted to a list of objects of type T using the provided target type.
     *
     * @param <T> The type of the object that the result set will be converted to.
     * @param targetType The class of the type T to which the result set will be converted.
     * @return A Tuple2 object where the first element is a List of objects of type T, each representing a row in the result set returned by the stored procedure,
     *         and the second element is a Jdbc.OutParamResult object that contains the out parameters returned by the stored procedure.
     * @throws IllegalArgumentException if targetType is {@code null}.
     * @throws SQLException if a database access error occurs.
     */
    public <T> Tuple2<List<T>, Jdbc.OutParamResult> listAndGetOutParameters(final Class<? extends T> targetType) throws IllegalArgumentException, SQLException {
        checkArgNotNull(targetType, s.targetType);

        return listAndGetOutParameters(Jdbc.BiRowMapper.to(targetType));
    }

    /**
     * Executes the stored procedure and returns the result set and out parameters.
     * The result set is converted to a list of objects of type T using the provided RowMapper.
     *
     * @param <T> The type of the object that the result set will be converted to.
     * @param rowMapper The RowMapper to use for converting the result set to a list of objects of type T.
     * @return A Tuple2 object where the first element is a List of objects of type T, each representing a row in the result set returned by the stored procedure,
     *         and the second element is a Jdbc.OutParamResult object that contains the out parameters returned by the stored procedure.
     * @throws IllegalArgumentException if {@code rowMapper} is {@code null}.
     * @throws IllegalStateException if this method is called on a closed CallableStatement.
     * @throws SQLException if a database access error occurs.
     */
    public <T> Tuple2<List<T>, Jdbc.OutParamResult> listAndGetOutParameters(final Jdbc.RowMapper<? extends T> rowMapper)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowMapper, s.rowMapper);
        assertNotClosed();

        try {
            final List<T> result = new ArrayList<>();
            final ResultSet rs = executeQuery();

            if (rs != null) {
                while (rs.next()) {
                    result.add(rowMapper.apply(rs));
                }
            }

            return Tuple.of(result, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the stored procedure and returns the result set and out parameters.
     * The result set is filtered using the provided RowFilter and converted to a list of objects of type T using the provided RowMapper.
     *
     * @param <T> The type of the object that the result set will be converted to.
     * @param rowFilter The RowFilter to use for filtering the result set.
     * @param rowMapper The RowMapper to use for converting the result set to a list of objects of type T.
     * @return A Tuple2 object where the first element is a List of objects of type T, each representing a row in the filtered result set returned by the stored procedure,
     *         and the second element is a Jdbc.OutParamResult object that contains the out parameters returned by the stored procedure.
     * @throws IllegalArgumentException if {@code rowFilter} or {@code rowMapper} is {@code null}.
     * @throws IllegalStateException if this method is called on a closed CallableStatement.
     * @throws SQLException if a database access error occurs.
     */
    public <T> Tuple2<List<T>, Jdbc.OutParamResult> listAndGetOutParameters(final Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends T> rowMapper)
            throws SQLException {
        checkArgNotNull(rowFilter, s.rowFilter);
        checkArgNotNull(rowMapper, s.rowMapper);
        assertNotClosed();

        try {
            final List<T> result = new ArrayList<>();
            final ResultSet rs = executeQuery();

            if (rs != null) {
                while (rs.next()) {
                    if (rowFilter.test(rs)) {
                        result.add(rowMapper.apply(rs));
                    }
                }
            }

            return Tuple.of(result, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the stored procedure and returns the result set and out parameters.
     * The result set is converted to a list of objects of type T using the provided BiRowMapper.
     *
     * @param <T> The type of the object that the result set will be converted to.
     * @param rowMapper The BiRowMapper to use for converting the result set to a list of objects of type T.
     * @return A Tuple2 object where the first element is a List of objects of type T, each representing a row in the result set returned by the stored procedure,
     *         and the second element is a Jdbc.OutParamResult object that contains the out parameters returned by the stored procedure.
     * @throws IllegalArgumentException if {@code rowMapper} is {@code null}.
     * @throws IllegalStateException if this method is called on a closed CallableStatement.
     * @throws SQLException if a database access error occurs.
     */
    public <T> Tuple2<List<T>, Jdbc.OutParamResult> listAndGetOutParameters(final Jdbc.BiRowMapper<? extends T> rowMapper)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowMapper, s.rowMapper);
        assertNotClosed();

        try {
            final List<T> result = new ArrayList<>();
            final ResultSet rs = executeQuery();

            if (rs != null) {
                final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                while (rs.next()) {
                    result.add(rowMapper.apply(rs, columnLabels));
                }
            }

            return Tuple.of(result, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the stored procedure and returns the result set and out parameters.
     * The result set is filtered using the provided BiRowFilter and converted to a list of objects of type T using the provided BiRowMapper.
     *
     * @param <T> The type of the object that the result set will be converted to.
     * @param rowFilter The BiRowFilter to use for filtering the result set.
     * @param rowMapper The BiRowMapper to use for converting the result set to a list of objects of type T.
     * @return A Tuple2 object where the first element is a List of objects of type T, each representing a row in the filtered result set returned by the stored procedure,
     *         and the second element is a Jdbc.OutParamResult object that contains the out parameters returned by the stored procedure.
     * @throws IllegalArgumentException if {@code rowFilter} or {@code rowMapper} is {@code null}.
     * @throws IllegalStateException if this method is called on a closed CallableStatement.
     * @throws SQLException if a database access error occurs.
     */
    public <T> Tuple2<List<T>, Jdbc.OutParamResult> listAndGetOutParameters(final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowMapper<? extends T> rowMapper)
            throws SQLException {
        checkArgNotNull(rowFilter, s.rowFilter);
        checkArgNotNull(rowMapper, s.rowMapper);
        assertNotClosed();

        try {
            final List<T> result = new ArrayList<>();
            final ResultSet rs = executeQuery();

            if (rs != null) {
                final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                while (rs.next()) {
                    if (rowFilter.test(rs, columnLabels)) {
                        result.add(rowMapper.apply(rs, columnLabels));
                    }
                }
            }

            return Tuple.of(result, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the stored procedure and returns all result sets and out parameters.
     * Each result set is converted to a list of objects of type T using the provided target type.
     *
     * @param <T> The type of the object that each result set will be converted to.
     * @param targetType The class of the type T to which each result set will be converted.
     * @return A Tuple2 object where the first element is a List of Lists of objects of type T, each representing a result set returned by the stored procedure,
     *         and the second element is a Jdbc.OutParamResult object that contains the out parameters returned by the stored procedure.
     * @throws IllegalArgumentException if targetType is {@code null}.
     * @throws IllegalStateException if this method is called on a closed CallableStatement.
     * @throws SQLException if a database access error occurs.
     */
    public <T> Tuple2<List<List<T>>, Jdbc.OutParamResult> listAllResultsetsAndGetOutParameters(final Class<? extends T> targetType)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(targetType, s.targetType);
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            final List<List<T>> resultList = JdbcUtil.<T> streamAllResultSets(cstmt, targetType).map(CheckedStream::toList).toList();

            return Tuple.of(resultList, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * Executes the stored procedure and returns all result sets and out parameters.
     * Each result set is converted to a list of objects of type T using the provided RowMapper.
     *
     * @param <T> The type of the object that each result set will be converted to.
     * @param rowMapper The RowMapper to use for converting each result set to a list of objects of type T.
     * @return A Tuple2 object where the first element is a List of Lists of objects of type T, each representing a result set returned by the stored procedure,
     *         and the second element is a Jdbc.OutParamResult object that contains the out parameters returned by the stored procedure.
     * @throws IllegalArgumentException if {@code rowMapper} is {@code null}.
     * @throws IllegalStateException if this method is called on a closed CallableStatement.
     * @throws SQLException if a database access error occurs.
     */
    public <T> Tuple2<List<List<T>>, Jdbc.OutParamResult> listAllResultsetsAndGetOutParameters(final Jdbc.RowMapper<? extends T> rowMapper)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowMapper, s.rowMapper);
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
     * Executes the stored procedure and returns all result sets and out parameters.
     * Each result set is filtered using the provided RowFilter and converted to a list of objects of type T using the provided RowMapper.
     *
     * @param <T> The type of the object that each result set will be converted to.
     * @param rowFilter The RowFilter to use for filtering each result set.
     * @param rowMapper The RowMapper to use for converting each result set to a list of objects of type T.
     * @return A Tuple2 object where the first element is a List of Lists of objects of type T, each representing a result set returned by the stored procedure,
     *         and the second element is a Jdbc.OutParamResult object that contains the out parameters returned by the stored procedure.
     * @throws IllegalArgumentException if {@code rowFilter} or {@code rowMapper} is {@code null}.
     * @throws IllegalStateException if this method is called on a closed CallableStatement.
     * @throws SQLException if a database access error occurs.
     */
    public <T> Tuple2<List<List<T>>, Jdbc.OutParamResult> listAllResultsetsAndGetOutParameters(final Jdbc.RowFilter rowFilter,
            final Jdbc.RowMapper<? extends T> rowMapper) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, s.rowFilter);
        checkArgNotNull(rowMapper, s.rowMapper);
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
     * Executes the stored procedure and returns all result sets and out parameters.
     * Each result set is converted to a list of objects of type T using the provided BiRowMapper.
     *
     * @param <T> The type of the object that each result set will be converted to.
     * @param rowMapper The BiRowMapper to use for converting each result set to a list of objects of type T.
     * @return A Tuple2 object where the first element is a List of Lists of objects of type T, each representing a result set returned by the stored procedure,
     *         and the second element is a Jdbc.OutParamResult object that contains the out parameters returned by the stored procedure.
     * @throws IllegalArgumentException if {@code rowMapper} is {@code null}.
     * @throws IllegalStateException if this method is called on a closed CallableStatement.
     * @throws SQLException if a database access error occurs.
     */
    public <T> Tuple2<List<List<T>>, Jdbc.OutParamResult> listAllResultsetsAndGetOutParameters(final Jdbc.BiRowMapper<? extends T> rowMapper)
            throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowMapper, s.rowMapper);
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
     * Executes the stored procedure and returns all result sets and out parameters.
     * Each result set is filtered using the provided BiRowFilter and converted to a list of objects of type T using the provided BiRowMapper.
     *
     * @param <T> The type of the object that each result set will be converted to.
     * @param rowFilter The BiRowFilter to use for filtering each result set.
     * @param rowMapper The BiRowMapper to use for converting each result set to a list of objects of type T.
     * @return A Tuple2 object where the first element is a List of Lists of objects of type T, each representing a result set returned by the stored procedure,
     *         and the second element is a Jdbc.OutParamResult object that contains the out parameters returned by the stored procedure.
     * @throws IllegalArgumentException if {@code rowFilter} or {@code rowMapper} is {@code null}.
     * @throws IllegalStateException if this method is called on a closed CallableStatement.
     * @throws SQLException if a database access error occurs.
     */
    public <T> Tuple2<List<List<T>>, Jdbc.OutParamResult> listAllResultsetsAndGetOutParameters(final Jdbc.BiRowFilter rowFilter,
            final Jdbc.BiRowMapper<? extends T> rowMapper) throws IllegalArgumentException, IllegalStateException, SQLException {
        checkArgNotNull(rowFilter, s.rowFilter);
        checkArgNotNull(rowMapper, s.rowMapper);
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
        } catch (final SQLException e) {
            logger.warn("failed to reset statement", e);
        } finally {
            super.closeStatement();
        }
    }
}
