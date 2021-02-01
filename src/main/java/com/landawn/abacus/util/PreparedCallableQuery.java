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

package com.landawn.abacus.util;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.landawn.abacus.DataSet;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.EntityInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.util.ExceptionalStream.ExceptionalIterator;
import com.landawn.abacus.util.JdbcUtil.BiParametersSetter;
import com.landawn.abacus.util.JdbcUtil.BiResultExtractor;
import com.landawn.abacus.util.JdbcUtil.BiRowFilter;
import com.landawn.abacus.util.JdbcUtil.BiRowMapper;
import com.landawn.abacus.util.JdbcUtil.OutParam;
import com.landawn.abacus.util.JdbcUtil.OutParamResult;
import com.landawn.abacus.util.JdbcUtil.ParametersSetter;
import com.landawn.abacus.util.JdbcUtil.ResultExtractor;
import com.landawn.abacus.util.JdbcUtil.RowFilter;
import com.landawn.abacus.util.JdbcUtil.RowMapper;
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
public class PreparedCallableQuery extends AbstractPreparedQuery<CallableStatement, PreparedCallableQuery> {

    final CallableStatement cstmt;
    List<OutParam> outParams;

    PreparedCallableQuery(CallableStatement stmt) {
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
    public PreparedCallableQuery setNull(String parameterName, int sqlType) throws SQLException {
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
    public PreparedCallableQuery setNull(String parameterName, int sqlType, String typeName) throws SQLException {
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
    public PreparedCallableQuery setBoolean(String parameterName, boolean x) throws SQLException {
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
    public PreparedCallableQuery setBoolean(String parameterName, Boolean x) throws SQLException {
        cstmt.setBoolean(parameterName, N.defaultIfNull(x));

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
    public PreparedCallableQuery setByte(String parameterName, byte x) throws SQLException {
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
    public PreparedCallableQuery setByte(String parameterName, Byte x) throws SQLException {
        cstmt.setByte(parameterName, N.defaultIfNull(x));

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
    public PreparedCallableQuery setShort(String parameterName, short x) throws SQLException {
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
    public PreparedCallableQuery setShort(String parameterName, Short x) throws SQLException {
        cstmt.setShort(parameterName, N.defaultIfNull(x));

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
    public PreparedCallableQuery setInt(String parameterName, int x) throws SQLException {
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
    public PreparedCallableQuery setInt(String parameterName, Integer x) throws SQLException {
        cstmt.setInt(parameterName, N.defaultIfNull(x));

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
    public PreparedCallableQuery setLong(String parameterName, long x) throws SQLException {
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
    public PreparedCallableQuery setLong(String parameterName, Long x) throws SQLException {
        cstmt.setLong(parameterName, N.defaultIfNull(x));

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
    public PreparedCallableQuery setFloat(String parameterName, float x) throws SQLException {
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
    public PreparedCallableQuery setFloat(String parameterName, Float x) throws SQLException {
        cstmt.setFloat(parameterName, N.defaultIfNull(x));

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
    public PreparedCallableQuery setDouble(String parameterName, double x) throws SQLException {
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
    public PreparedCallableQuery setDouble(String parameterName, Double x) throws SQLException {
        cstmt.setDouble(parameterName, N.defaultIfNull(x));

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
    public PreparedCallableQuery setBigDecimal(String parameterName, BigDecimal x) throws SQLException {
        cstmt.setBigDecimal(parameterName, x);

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
    public PreparedCallableQuery setString(String parameterName, String x) throws SQLException {
        cstmt.setString(parameterName, x);

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
    public PreparedCallableQuery setDate(String parameterName, java.sql.Date x) throws SQLException {
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
    public PreparedCallableQuery setDate(String parameterName, java.util.Date x) throws SQLException {
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
    public PreparedCallableQuery setTime(String parameterName, java.sql.Time x) throws SQLException {
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
    public PreparedCallableQuery setTime(String parameterName, java.util.Date x) throws SQLException {
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
    public PreparedCallableQuery setTimestamp(String parameterName, java.sql.Timestamp x) throws SQLException {
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
    public PreparedCallableQuery setTimestamp(String parameterName, java.util.Date x) throws SQLException {
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
    public PreparedCallableQuery setBytes(String parameterName, byte[] x) throws SQLException {
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
    public PreparedCallableQuery setAsciiStream(String parameterName, InputStream inputStream) throws SQLException {
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
    public PreparedCallableQuery setAsciiStream(String parameterName, InputStream inputStream, long length) throws SQLException {
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
    public PreparedCallableQuery setBinaryStream(String parameterName, InputStream inputStream) throws SQLException {
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
    public PreparedCallableQuery setBinaryStream(String parameterName, InputStream inputStream, long length) throws SQLException {
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
    public PreparedCallableQuery setCharacterStream(String parameterName, Reader reader) throws SQLException {
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
    public PreparedCallableQuery setCharacterStream(String parameterName, Reader reader, long length) throws SQLException {
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
    public PreparedCallableQuery setNCharacterStream(String parameterName, Reader reader) throws SQLException {
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
    public PreparedCallableQuery setNCharacterStream(String parameterName, Reader reader, long length) throws SQLException {
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
    public PreparedCallableQuery setBlob(String parameterName, java.sql.Blob x) throws SQLException {
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
    public PreparedCallableQuery setBlob(String parameterName, InputStream inputStream) throws SQLException {
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
    public PreparedCallableQuery setBlob(String parameterName, InputStream inputStream, long length) throws SQLException {
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
    public PreparedCallableQuery setClob(String parameterName, java.sql.Clob x) throws SQLException {
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
    public PreparedCallableQuery setClob(String parameterName, Reader reader) throws SQLException {
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
    public PreparedCallableQuery setClob(String parameterName, Reader reader, long length) throws SQLException {
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
    public PreparedCallableQuery setNClob(String parameterName, java.sql.NClob x) throws SQLException {
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
    public PreparedCallableQuery setNClob(String parameterName, Reader reader) throws SQLException {
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
    public PreparedCallableQuery setNClob(String parameterName, Reader reader, long length) throws SQLException {
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
    public PreparedCallableQuery setURL(String parameterName, URL x) throws SQLException {
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
    public PreparedCallableQuery setSQLXML(String parameterName, java.sql.SQLXML x) throws SQLException {
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
    public PreparedCallableQuery setRowId(String parameterName, java.sql.RowId x) throws SQLException {
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
    public PreparedCallableQuery setObject(String parameterName, Object x) throws SQLException {
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
    public PreparedCallableQuery setObject(String parameterName, Object x, int sqlType) throws SQLException {
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
    public PreparedCallableQuery setObject(String parameterName, Object x, int sqlType, int scaleOrLength) throws SQLException {
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
    public PreparedCallableQuery setParameters(Map<String, ?> parameters) throws SQLException {
        checkArgNotNull(parameters, "parameters");

        for (Map.Entry<String, ?> entry : parameters.entrySet()) {
            setObject(entry.getKey(), entry.getValue());
        }

        return this;
    }

    /**
     * Sets the parameters.
     *
     * @param parameterNames
     * @param entity
     * @return
     * @throws SQLException the SQL exception
     * @see {@link ClassUtil#getPropNameList(Class)}
     * @see {@link ClassUtil#getPropNameListExclusively(Class, Set)}
     * @see {@link ClassUtil#getPropNameListExclusively(Class, Collection)}
     * @see {@link JdbcUtil#getNamedParameters(String)}
     */
    public PreparedCallableQuery setParameters(List<String> parameterNames, Object entity) throws SQLException {
        checkArgNotNull(parameterNames, "parameterNames");
        checkArgNotNull(entity, "entity");

        final Class<?> cls = entity.getClass();
        final EntityInfo entityInfo = ParserUtil.getEntityInfo(cls);
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
    public PreparedCallableQuery registerOutParameter(int parameterIndex, int sqlType) throws SQLException {
        cstmt.registerOutParameter(parameterIndex, sqlType);

        addOutParameters(new OutParam(parameterIndex, null, sqlType, null, -1));

        return this;
    }

    private void addOutParameters(OutParam outParameter) {
        if (outParams == null) {
            outParams = new ArrayList<>();
        }

        outParams.add(outParameter);
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
    public PreparedCallableQuery registerOutParameter(int parameterIndex, int sqlType, int scale) throws SQLException {
        cstmt.registerOutParameter(parameterIndex, sqlType, scale);

        addOutParameters(new OutParam(parameterIndex, null, sqlType, null, scale));

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
    public PreparedCallableQuery registerOutParameter(int parameterIndex, int sqlType, String typeName) throws SQLException {
        cstmt.registerOutParameter(parameterIndex, sqlType, typeName);

        addOutParameters(new OutParam(parameterIndex, null, sqlType, typeName, -1));

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
    public PreparedCallableQuery registerOutParameter(String parameterName, int sqlType) throws SQLException {
        cstmt.registerOutParameter(parameterName, sqlType);

        addOutParameters(new OutParam(-1, parameterName, sqlType, null, -1));

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
    public PreparedCallableQuery registerOutParameter(String parameterName, int sqlType, int scale) throws SQLException {
        cstmt.registerOutParameter(parameterName, sqlType, scale);

        addOutParameters(new OutParam(-1, parameterName, sqlType, null, scale));

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
    public PreparedCallableQuery registerOutParameter(String parameterName, int sqlType, String typeName) throws SQLException {
        cstmt.registerOutParameter(parameterName, sqlType, typeName);

        addOutParameters(new OutParam(-1, parameterName, sqlType, typeName, -1));

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
    public PreparedCallableQuery registerOutParameter(int parameterIndex, SQLType sqlType) throws SQLException {
        cstmt.registerOutParameter(parameterIndex, sqlType);

        addOutParameters(new OutParam(parameterIndex, null, sqlType.getVendorTypeNumber(), null, -1));

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
    public PreparedCallableQuery registerOutParameter(int parameterIndex, SQLType sqlType, int scale) throws SQLException {
        cstmt.registerOutParameter(parameterIndex, sqlType, scale);

        addOutParameters(new OutParam(parameterIndex, null, sqlType.getVendorTypeNumber(), null, scale));

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
    public PreparedCallableQuery registerOutParameter(int parameterIndex, SQLType sqlType, String typeName) throws SQLException {
        cstmt.registerOutParameter(parameterIndex, sqlType, typeName);

        addOutParameters(new OutParam(parameterIndex, null, sqlType.getVendorTypeNumber(), typeName, -1));

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
    public PreparedCallableQuery registerOutParameter(String parameterName, SQLType sqlType) throws SQLException {
        cstmt.registerOutParameter(parameterName, sqlType);

        addOutParameters(new OutParam(-1, parameterName, sqlType.getVendorTypeNumber(), null, -1));

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
    public PreparedCallableQuery registerOutParameter(String parameterName, SQLType sqlType, int scale) throws SQLException {
        cstmt.registerOutParameter(parameterName, sqlType, scale);

        addOutParameters(new OutParam(-1, parameterName, sqlType.getVendorTypeNumber(), null, scale));

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
    public PreparedCallableQuery registerOutParameter(String parameterName, SQLType sqlType, String typeName) throws SQLException {
        cstmt.registerOutParameter(parameterName, sqlType, typeName);

        addOutParameters(new OutParam(-1, parameterName, sqlType.getVendorTypeNumber(), typeName, -1));

        return this;
    }

    /**
     * Register out parameters.
     *
     * @param register
     * @return
     * @throws SQLException the SQL exception
     */
    public PreparedCallableQuery registerOutParameters(final ParametersSetter<? super PreparedCallableQuery> register) throws SQLException {
        checkArgNotNull(register, "register");

        boolean noException = false;

        try {
            register.accept(this);

            noException = true;
        } finally {
            if (noException == false) {
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
    public <T> PreparedCallableQuery registerOutParameters(final T parameter, final BiParametersSetter<? super PreparedCallableQuery, ? super T> register)
            throws SQLException {
        checkArgNotNull(register, "register");

        boolean noException = false;

        try {
            register.accept(this, parameter);

            noException = true;
        } finally {
            if (noException == false) {
                close();
            }
        }

        return this;
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
    public <R> R executeThenApply(final Throwables.Function<? super CallableStatement, ? extends R, SQLException> getter) throws SQLException {
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
    public <R> R executeThenApply(final Throwables.BiFunction<Boolean, ? super CallableStatement, ? extends R, SQLException> getter) throws SQLException {
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
    public <R> R executeThenApply(final Throwables.TriFunction<Boolean, List<OutParam>, ? super CallableStatement, ? extends R, SQLException> getter)
            throws SQLException {
        checkArgNotNull(getter, "getter");
        assertNotClosed();

        try {
            final boolean isFirstResultSet = JdbcUtil.execute(cstmt);
            outParams = outParams == null ? N.<OutParam> emptyList() : outParams;

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
    public void executeThenAccept(final Throwables.Consumer<? super CallableStatement, SQLException> consumer) throws SQLException {
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
    public void executeThenAccept(final Throwables.BiConsumer<Boolean, ? super CallableStatement, SQLException> consumer) throws SQLException {
        super.executeThenAccept(consumer);
    }

    /**
     * Execute then apply.
     *
     * @param <R>
     * @param consumer the first parameter is {@code isFirstResultSet}, the second one is {@code outParametes} and third one is the executed {@code CallableStatement}.
     * @return
     * @throws SQLException the SQL exception
     * @see JdbcUtil#getOutParameters(CallableStatement, int)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, RowMapper)
     * @see JdbcUtil#streamAllResultSets(java.sql.Statement, BiRowMapper)
     */
    public void executeThenAccept(final Throwables.TriConsumer<Boolean, List<OutParam>, ? super CallableStatement, SQLException> consumer) throws SQLException {
        checkArgNotNull(consumer, "consumer");
        assertNotClosed();

        try {
            final boolean isFirstResultSet = JdbcUtil.execute(cstmt);
            outParams = outParams == null ? N.<OutParam> emptyList() : outParams;

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
    public OutParamResult executeAndGetOutParameters() throws SQLException {
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
     * @return a list of {@code DataSet} extracted from all {@code ResultSets} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public List<DataSet> queryAll() throws SQLException {
        return queryAll(ResultExtractor.TO_DATA_SET);
    }

    /**
     * 
     * @param <R>
     * @param resultExtrator
     * @return a list of {@code R} extracted from all {@code ResultSets} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <R> List<R> queryAll(final ResultExtractor<R> resultExtrator) throws SQLException {
        checkArgNotNull(resultExtrator, "resultExtrator");
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            return ExceptionalStream.newStream(JdbcUtil.iterateAllResultSets(cstmt)).map(rs -> JdbcUtil.extractAndCloseResultSet(rs, resultExtrator)).toList();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * 
     * @param <R>
     * @param resultExtrator
     * @return a list of {@code R} extracted from all {@code ResultSets} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <R> List<R> queryAll(final BiResultExtractor<R> resultExtrator) throws SQLException {
        checkArgNotNull(resultExtrator, "resultExtrator");
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            return ExceptionalStream.newStream(JdbcUtil.iterateAllResultSets(cstmt)).map(rs -> JdbcUtil.extractAndCloseResultSet(rs, resultExtrator)).toList();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * 
     * @return the {@code DataSet} extracted from first {@code ResultSet} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public Tuple2<DataSet, OutParamResult> queryAndGetOutParameters() throws SQLException {
        return queryAndGetOutParameters(ResultExtractor.TO_DATA_SET);
    }

    /**
     * 
     * @param <R>
     * @param resultExtrator
     * @return the {@code R} extracted from first {@code ResultSet} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <R> Tuple2<R, OutParamResult> queryAndGetOutParameters(final ResultExtractor<R> resultExtrator) throws SQLException {
        checkArgNotNull(resultExtrator, "resultExtrator");
        assertNotClosed();

        try {
            R result = null;
            final ResultSet rs = executeQuery();

            if (rs != null) {
                result = JdbcUtil.extractAndCloseResultSet(rs, resultExtrator);
            }

            return Tuple.of(result, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * 
     * @param <R>
     * @param resultExtrator
     * @return the {@code R} extracted from first {@code ResultSet} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <R> Tuple2<R, OutParamResult> queryAndGetOutParameters(final BiResultExtractor<R> resultExtrator) throws SQLException {
        checkArgNotNull(resultExtrator, "resultExtrator");
        assertNotClosed();

        try {
            R result = null;
            final ResultSet rs = executeQuery();

            if (rs != null) {
                try {
                    result = JdbcUtil.extractAndCloseResultSet(rs, resultExtrator);
                } finally {
                    JdbcUtil.closeQuietly(rs);
                }
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
    public Tuple2<List<DataSet>, OutParamResult> queryAllAndGetOutParameters() throws SQLException {
        return queryAllAndGetOutParameters(ResultExtractor.TO_DATA_SET);
    }

    /**
     * 
     * @param <R>
     * @param resultExtrator
     * @return a list of {@code R} extracted from all {@code ResultSets} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <R> Tuple2<List<R>, OutParamResult> queryAllAndGetOutParameters(final ResultExtractor<R> resultExtrator) throws SQLException {
        checkArgNotNull(resultExtrator, "resultExtrator");
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            final List<R> resultList = ExceptionalStream.newStream(JdbcUtil.iterateAllResultSets(cstmt))
                    .map(rs -> JdbcUtil.extractAndCloseResultSet(rs, resultExtrator))
                    .toList();

            return Tuple.of(resultList, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * 
     * @param <R>
     * @param resultExtrator
     * @return a list of {@code R} extracted from all {@code ResultSets} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <R> Tuple2<List<R>, OutParamResult> queryAllAndGetOutParameters(final BiResultExtractor<R> resultExtrator) throws SQLException {
        checkArgNotNull(resultExtrator, "resultExtrator");
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            final List<R> resultList = ExceptionalStream.newStream(JdbcUtil.iterateAllResultSets(cstmt))
                    .map(rs -> JdbcUtil.extractAndCloseResultSet(rs, resultExtrator))
                    .toList();

            return Tuple.of(resultList, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * 
     * @param <R1>
     * @param <R2>
     * @param resultExtrator1
     * @param resultExtrator2
     * @return {@code R1/R2} extracted from the first two {@code ResultSets} returned by the executed procedure.
     * @throws SQLException
     */
    public <R1, R2> Tuple2<R1, R2> query2(final BiResultExtractor<R1> resultExtrator1, final BiResultExtractor<R2> resultExtrator2) throws SQLException {
        checkArgNotNull(resultExtrator1, "resultExtrator1");
        checkArgNotNull(resultExtrator2, "resultExtrator2");
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            final ExceptionalIterator<ResultSet, SQLException> iter = JdbcUtil.iterateAllResultSets(cstmt);

            R1 result1 = null;
            R2 result2 = null;

            if (iter.hasNext()) {
                result1 = JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtrator1);
            }

            if (iter.hasNext()) {
                result2 = JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtrator2);
            }

            return Tuple.of(result1, result2);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * 
     * @param <R1>
     * @param <R2>
     * @param resultExtrator1
     * @param resultExtrator2
     * @return {@code R1/R2} extracted from the first two {@code ResultSets} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <R1, R2> Tuple3<R1, R2, OutParamResult> query2AndGetOutParameters(final BiResultExtractor<R1> resultExtrator1,
            final BiResultExtractor<R2> resultExtrator2) throws SQLException {
        checkArgNotNull(resultExtrator1, "resultExtrator1");
        checkArgNotNull(resultExtrator2, "resultExtrator2");
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            final ExceptionalIterator<ResultSet, SQLException> iter = JdbcUtil.iterateAllResultSets(cstmt);

            R1 result1 = null;
            R2 result2 = null;

            if (iter.hasNext()) {
                result1 = JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtrator1);
            }

            if (iter.hasNext()) {
                result2 = JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtrator2);
            }

            return Tuple.of(result1, result2, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * 
     * @param <R1>
     * @param <R2>
     * @param <R3>
     * @param resultExtrator1
     * @param resultExtrator2
     * @param resultExtrator3
     * @return {@code R1/R2/R3} extracted from the first three {@code ResultSets} returned by the executed procedure.
     * @throws SQLException
     */
    public <R1, R2, R3> Tuple3<R1, R2, R3> query3(final BiResultExtractor<R1> resultExtrator1, final BiResultExtractor<R2> resultExtrator2,
            final BiResultExtractor<R3> resultExtrator3) throws SQLException {
        checkArgNotNull(resultExtrator1, "resultExtrator1");
        checkArgNotNull(resultExtrator2, "resultExtrator2");
        checkArgNotNull(resultExtrator3, "resultExtrator3");
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            final ExceptionalIterator<ResultSet, SQLException> iter = JdbcUtil.iterateAllResultSets(cstmt);

            R1 result1 = null;
            R2 result2 = null;
            R3 result3 = null;

            if (iter.hasNext()) {
                result1 = JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtrator1);
            }

            if (iter.hasNext()) {
                result2 = JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtrator2);
            }

            if (iter.hasNext()) {
                result3 = JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtrator3);
            }

            return Tuple.of(result1, result2, result3);
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * 
     * @param <R1>
     * @param <R2>
     * @param <R3>
     * @param resultExtrator1
     * @param resultExtrator2
     * @param resultExtrator3
     * @return {@code R1/R2/R3} extracted from the first three {@code ResultSets} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <R1, R2, R3> Tuple4<R1, R2, R3, OutParamResult> query3AndGetOutParameters(final BiResultExtractor<R1> resultExtrator1,
            final BiResultExtractor<R2> resultExtrator2, final BiResultExtractor<R3> resultExtrator3) throws SQLException {
        checkArgNotNull(resultExtrator1, "resultExtrator1");
        checkArgNotNull(resultExtrator2, "resultExtrator2");
        checkArgNotNull(resultExtrator3, "resultExtrator3");
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            final ExceptionalIterator<ResultSet, SQLException> iter = JdbcUtil.iterateAllResultSets(cstmt);

            R1 result1 = null;
            R2 result2 = null;
            R3 result3 = null;

            if (iter.hasNext()) {
                result1 = JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtrator1);
            }

            if (iter.hasNext()) {
                result2 = JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtrator2);
            }

            if (iter.hasNext()) {
                result3 = JdbcUtil.extractAndCloseResultSet(iter.next(), resultExtrator3);
            }

            return Tuple.of(result1, result2, result3, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * 
     * @param <T>
     * @param targetClass
     * @return the {@code List<T>} extracted from first {@code ResultSet} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <T> Tuple2<List<T>, OutParamResult> listAndGetOutParameters(final Class<T> targetClass) throws SQLException {
        checkArgNotNull(targetClass, "targetClass");

        return listAndGetOutParameters(BiRowMapper.to(targetClass));
    }

    /**
     * 
     * @param <T>
     * @param rowMapper
     * @return the {@code List<T>} extracted from first {@code ResultSet} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <T> Tuple2<List<T>, OutParamResult> listAndGetOutParameters(final RowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowMapper, "rowMapper");
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
     * 
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return the {@code List<T>} extracted from first {@code ResultSet} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <T> Tuple2<List<T>, OutParamResult> listAndGetOutParameters(final RowFilter rowFilter, final RowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowMapper, "rowMapper");
        checkArgNotNull(rowMapper, "rowMapper");
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
     * 
     * @param <T>
     * @param rowMapper
     * @return the {@code List<T>} extracted from first {@code ResultSet} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <T> Tuple2<List<T>, OutParamResult> listAndGetOutParameters(final BiRowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowMapper, "rowMapper");
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
     * 
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return the {@code List<T>} extracted from first {@code ResultSet} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <T> Tuple2<List<T>, OutParamResult> listAndGetOutParameters(final BiRowFilter rowFilter, final BiRowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
        checkArgNotNull(rowMapper, "rowMapper");
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
     * 
     * @param <T>
     * @param targetClass
     * @return the {@code List<T>} extracted from all {@code ResultSets} returned by the executed procedure.
     * @throws SQLException
     */
    public <T> List<T> listAll(final Class<T> targetClass) throws SQLException {
        checkArgNotNull(targetClass, "targetClass");

        return listAll(BiRowMapper.to(targetClass));
    }

    /**
     * 
     * @param <T>
     * @param rowMapper
     * @return the {@code List<T>} extracted from all {@code ResultSets} returned by the executed procedure.
     * @throws SQLException
     */
    public <T> List<T> listAll(final RowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            return JdbcUtil.streamAllResultSets(cstmt, rowMapper).toList();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * 
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return the {@code List<T>} extracted from all {@code ResultSets} returned by the executed procedure.
     * @throws SQLException
     */
    public <T> List<T> listAll(final RowFilter rowFilter, final RowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            return JdbcUtil.streamAllResultSets(cstmt, rowFilter, rowMapper).toList();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * 
     * @param <T>
     * @param rowMapper
     * @return the {@code List<T>} extracted from all {@code ResultSets} returned by the executed procedure.
     * @throws SQLException
     */
    public <T> List<T> listAll(final BiRowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            return JdbcUtil.streamAllResultSets(cstmt, rowMapper).toList();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * 
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return the {@code List<T>} extracted from all {@code ResultSets} returned by the executed procedure.
     * @throws SQLException
     */
    public <T> List<T> listAll(final BiRowFilter rowFilter, final BiRowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            return JdbcUtil.streamAllResultSets(cstmt, rowFilter, rowMapper).toList();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * 
     * @param <T>
     * @param targetClass
     * @return the {@code List<T>} extracted from all {@code ResultSets} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <T> Tuple2<List<T>, OutParamResult> listAllAndGetOutParameters(final Class<T> targetClass) throws SQLException {
        checkArgNotNull(targetClass, "targetClass");

        return listAllAndGetOutParameters(BiRowMapper.to(targetClass));
    }

    /**
     * 
     * @param <T>
     * @param rowMapper
     * @return the {@code List<T>} extracted from all {@code ResultSets} returned by the executed procedure and a list of {@code Out Parameters}.
     * @throws SQLException
     */
    public <T> Tuple2<List<T>, OutParamResult> listAllAndGetOutParameters(final RowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            final List<T> resultList = JdbcUtil.streamAllResultSets(cstmt, rowMapper).toList();

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
    public <T> Tuple2<List<T>, OutParamResult> listAllAndGetOutParameters(final RowFilter rowFilter, final RowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            final List<T> resultList = JdbcUtil.streamAllResultSets(cstmt, rowFilter, rowMapper).toList();

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
    public <T> Tuple2<List<T>, OutParamResult> listAllAndGetOutParameters(final BiRowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            final List<T> resultList = JdbcUtil.streamAllResultSets(cstmt, rowMapper).toList();

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
    public <T> Tuple2<List<T>, OutParamResult> listAllAndGetOutParameters(final BiRowFilter rowFilter, final BiRowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        try {
            JdbcUtil.execute(cstmt);

            final List<T> resultList = JdbcUtil.streamAllResultSets(cstmt, rowFilter, rowMapper).toList();

            return Tuple.of(resultList, JdbcUtil.getOutParameters(cstmt, outParams));
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    /**
     * 
     * @param <T>
     * @param targetClass
     * @return the {@code ExceptionalStream<T>} extracted from all {@code ResultSets} returned by the executed procedure.
     * @throws SQLException
     */
    public <T> ExceptionalStream<T, SQLException> streamAll(final Class<T> targetClass) throws SQLException {
        checkArgNotNull(targetClass, "targetClass");

        return streamAll(BiRowMapper.to(targetClass));
    }

    /**
     * 
     * @param <T>
     * @param rowMapper
     * @return the {@code ExceptionalStream<T>} extracted from all {@code ResultSets} returned by the executed procedure.
     * @throws SQLException
     */
    public <T> ExceptionalStream<T, SQLException> streamAll(final RowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        final Throwables.Supplier<Boolean, SQLException> supplier = () -> JdbcUtil.execute(cstmt);

        return ExceptionalStream.just(supplier, SQLException.class)
                .flatMap(it -> JdbcUtil.streamAllResultSets(cstmt, rowMapper))
                .onClose(() -> closeAfterExecutionIfAllowed());
    }

    /**
     * 
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return the {@code ExceptionalStream<T>} extracted from all {@code ResultSets} returned by the executed procedure.
     * @throws SQLException
     */
    public <T> ExceptionalStream<T, SQLException> streamAll(final RowFilter rowFilter, final RowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        final Throwables.Supplier<Boolean, SQLException> supplier = () -> JdbcUtil.execute(cstmt);

        return ExceptionalStream.just(supplier, SQLException.class)
                .flatMap(it -> JdbcUtil.streamAllResultSets(cstmt, rowFilter, rowMapper))
                .onClose(() -> closeAfterExecutionIfAllowed());
    }

    /**
     * 
     * @param <T>
     * @param rowMapper
     * @return the {@code ExceptionalStream<T>} extracted from all {@code ResultSets} returned by the executed procedure.
     * @throws SQLException
     */
    public <T> ExceptionalStream<T, SQLException> streamAll(final BiRowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        final Throwables.Supplier<Boolean, SQLException> supplier = () -> JdbcUtil.execute(cstmt);

        return ExceptionalStream.just(supplier, SQLException.class)
                .flatMap(it -> JdbcUtil.streamAllResultSets(cstmt, rowMapper))
                .onClose(() -> closeAfterExecutionIfAllowed());
    }

    /**
     * 
     * @param <T>
     * @param rowFilter
     * @param rowMapper
     * @return the {@code ExceptionalStream<T>} extracted from all {@code ResultSets} returned by the executed procedure.
     * @throws SQLException
     */
    public <T> ExceptionalStream<T, SQLException> streamAll(final BiRowFilter rowFilter, final BiRowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowFilter, "rowFilter");
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        final Throwables.Supplier<Boolean, SQLException> supplier = () -> JdbcUtil.execute(cstmt);

        return ExceptionalStream.just(supplier, SQLException.class)
                .flatMap(it -> JdbcUtil.streamAllResultSets(cstmt, rowFilter, rowMapper))
                .onClose(() -> closeAfterExecutionIfAllowed());
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