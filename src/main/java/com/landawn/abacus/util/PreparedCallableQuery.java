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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLType;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.EntityInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.util.ExceptionalStream.ExceptionalIterator;
import com.landawn.abacus.util.JdbcUtil.BiParametersSetter;
import com.landawn.abacus.util.JdbcUtil.BiRowMapper;
import com.landawn.abacus.util.JdbcUtil.ParametersSetter;
import com.landawn.abacus.util.JdbcUtil.ResultExtractor;
import com.landawn.abacus.util.u.Holder;
import com.landawn.abacus.util.u.Optional;

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

    final CallableStatement stmt;

    PreparedCallableQuery(CallableStatement stmt) {
        super(stmt);
        this.stmt = stmt;
    }

    /**
     * Sets the null.
     *
     * @param parameterName
     * @param sqlType
     * @return
     * @throws SQLException the SQL exception
     */
    public PreparedCallableQuery setNull(String parameterName, int sqlType) throws SQLException {
        stmt.setNull(parameterName, sqlType);

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
     */
    public PreparedCallableQuery setNull(String parameterName, int sqlType, String typeName) throws SQLException {
        stmt.setNull(parameterName, sqlType, typeName);

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
        stmt.setBoolean(parameterName, x);

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
        stmt.setBoolean(parameterName, N.defaultIfNull(x));

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
        stmt.setByte(parameterName, x);

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
        stmt.setByte(parameterName, N.defaultIfNull(x));

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
        stmt.setShort(parameterName, x);

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
        stmt.setShort(parameterName, N.defaultIfNull(x));

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
        stmt.setInt(parameterName, x);

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
        stmt.setInt(parameterName, N.defaultIfNull(x));

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
        stmt.setLong(parameterName, x);

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
        stmt.setLong(parameterName, N.defaultIfNull(x));

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
        stmt.setFloat(parameterName, x);

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
        stmt.setFloat(parameterName, N.defaultIfNull(x));

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
        stmt.setDouble(parameterName, x);

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
        stmt.setDouble(parameterName, N.defaultIfNull(x));

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
        stmt.setBigDecimal(parameterName, x);

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
        stmt.setString(parameterName, x);

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
        stmt.setDate(parameterName, x);

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
        stmt.setDate(parameterName, x == null ? null : x instanceof java.sql.Date ? (java.sql.Date) x : new java.sql.Date(x.getTime()));

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
        stmt.setTime(parameterName, x);

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
        stmt.setTime(parameterName, x == null ? null : x instanceof java.sql.Time ? (java.sql.Time) x : new java.sql.Time(x.getTime()));

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
        stmt.setTimestamp(parameterName, x);

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
        stmt.setTimestamp(parameterName, x == null ? null : x instanceof java.sql.Timestamp ? (java.sql.Timestamp) x : new java.sql.Timestamp(x.getTime()));

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
        stmt.setBytes(parameterName, x);

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
        stmt.setAsciiStream(parameterName, inputStream);

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
        stmt.setAsciiStream(parameterName, inputStream, length);

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
        stmt.setBinaryStream(parameterName, inputStream);

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
        stmt.setBinaryStream(parameterName, inputStream, length);

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
        stmt.setCharacterStream(parameterName, reader);

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
        stmt.setCharacterStream(parameterName, reader, length);

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
        stmt.setNCharacterStream(parameterName, reader);

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
        stmt.setNCharacterStream(parameterName, reader, length);

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
        stmt.setBlob(parameterName, x);

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
        stmt.setBlob(parameterName, inputStream);

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
        stmt.setBlob(parameterName, inputStream, length);

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
        stmt.setClob(parameterName, x);

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
        stmt.setClob(parameterName, reader);

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
        stmt.setClob(parameterName, reader, length);

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
        stmt.setNClob(parameterName, x);

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
        stmt.setNClob(parameterName, reader);

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
        stmt.setNClob(parameterName, reader, length);

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
        stmt.setURL(parameterName, x);

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
        stmt.setSQLXML(parameterName, x);

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
        stmt.setRowId(parameterName, x);

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
            stmt.setObject(parameterName, x);
        } else {
            N.typeOf(x.getClass()).set(stmt, parameterName, x);
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
     */
    public PreparedCallableQuery setObject(String parameterName, Object x, int sqlType) throws SQLException {
        stmt.setObject(parameterName, x, sqlType);

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
     */
    public PreparedCallableQuery setObject(String parameterName, Object x, int sqlType, int scaleOrLength) throws SQLException {
        stmt.setObject(parameterName, x, sqlType, scaleOrLength);

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
            propInfo.dbType.set(stmt, parameterName, propInfo.getPropValue(entity));
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
     */
    public PreparedCallableQuery registerOutParameter(int parameterIndex, int sqlType) throws SQLException {
        stmt.registerOutParameter(parameterIndex, sqlType);

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
    public PreparedCallableQuery registerOutParameter(int parameterIndex, int sqlType, int scale) throws SQLException {
        stmt.registerOutParameter(parameterIndex, sqlType, scale);

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
    public PreparedCallableQuery registerOutParameter(int parameterIndex, int sqlType, String typeName) throws SQLException {
        stmt.registerOutParameter(parameterIndex, sqlType, typeName);

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
    public PreparedCallableQuery registerOutParameter(String parameterName, int sqlType) throws SQLException {
        stmt.registerOutParameter(parameterName, sqlType);

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
    public PreparedCallableQuery registerOutParameter(String parameterName, int sqlType, int scale) throws SQLException {
        stmt.registerOutParameter(parameterName, sqlType, scale);

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
    public PreparedCallableQuery registerOutParameter(String parameterName, int sqlType, String typeName) throws SQLException {
        stmt.registerOutParameter(parameterName, sqlType, typeName);

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
        stmt.registerOutParameter(parameterIndex, sqlType);

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
        stmt.registerOutParameter(parameterIndex, sqlType, scale);

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
        stmt.registerOutParameter(parameterIndex, sqlType, typeName);

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
        stmt.registerOutParameter(parameterName, sqlType);

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
        stmt.registerOutParameter(parameterName, sqlType, scale);

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
        stmt.registerOutParameter(parameterName, sqlType, typeName);

        return this;
    }

    /**
     * Register out parameters.
     *
     * @param register
     * @return
     * @throws SQLException the SQL exception
     */
    public PreparedCallableQuery registerOutParameters(final ParametersSetter<? super CallableStatement> register) throws SQLException {
        checkArgNotNull(register, "register");

        boolean noException = false;

        try {
            register.accept(stmt);

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
    public <T> PreparedCallableQuery registerOutParameters(final T parameter, final BiParametersSetter<? super CallableStatement, ? super T> register)
            throws SQLException {
        checkArgNotNull(register, "register");

        boolean noException = false;

        try {
            register.accept(stmt, parameter);

            noException = true;
        } finally {
            if (noException == false) {
                close();
            }
        }

        return this;
    }

    /**
     *
     * @param <R>
     * @param resultExtrator
     * @return
     * @throws SQLException the SQL exception
     */
    public <R> Optional<R> call(final ResultExtractor<R> resultExtrator) throws SQLException {
        checkArgNotNull(resultExtrator, "resultExtrator");
        assertNotClosed();

        try {
            boolean ret = JdbcUtil.execute(stmt);
            int updateCount = stmt.getUpdateCount();

            while (ret || updateCount != -1) {
                if (ret) {
                    try (ResultSet rs = stmt.getResultSet()) {
                        return Optional.of(checkNotResultSet(resultExtrator.apply(rs)));
                    }
                } else {
                    ret = stmt.getMoreResults();
                    updateCount = stmt.getUpdateCount();
                }
            }

            return Optional.<R> empty();
        } finally {
            closeAfterExecutionIfAllowed();
        }
    }

    public <T> ExceptionalStream<T, SQLException> callToStream(final BiRowMapper<T> rowMapper) throws SQLException {
        checkArgNotNull(rowMapper, "rowMapper");
        assertNotClosed();

        final ExceptionalIterator<ResultSet, SQLException> lazyIter = ExceptionalIterator
                .of(new Throwables.Supplier<ExceptionalIterator<ResultSet, SQLException>, SQLException>() {
                    private ExceptionalIterator<ResultSet, SQLException> internalIter;

                    @Override
                    public ExceptionalIterator<ResultSet, SQLException> get() throws SQLException {
                        if (internalIter == null) {
                            try {
                                internalIter = new ExceptionalIterator<ResultSet, SQLException>() {
                                    private final Holder<ResultSet> resultSetHolder = new Holder<>();
                                    private boolean isNextResultSet = JdbcUtil.execute(stmt);
                                    private int updateCount = stmt.getUpdateCount();

                                    @Override
                                    public boolean hasNext() throws SQLException {
                                        if (resultSetHolder.isNull()) {
                                            while (isNextResultSet || updateCount != -1) {
                                                if (isNextResultSet) {
                                                    resultSetHolder.setValue(stmt.getResultSet());
                                                    isNextResultSet = false;
                                                    updateCount = 0;

                                                    break;
                                                } else {
                                                    isNextResultSet = stmt.getMoreResults();
                                                    updateCount = stmt.getUpdateCount();
                                                }
                                            }
                                        }

                                        return resultSetHolder.isNotNull();
                                    }

                                    @Override
                                    public ResultSet next() throws SQLException {
                                        if (hasNext() == false) {
                                            throw new NoSuchElementException();
                                        }

                                        return resultSetHolder.getAndSet(null);
                                    }

                                    @Override
                                    public void close() throws SQLException {
                                        try {
                                            if (resultSetHolder.isNotNull()) {
                                                JdbcUtil.closeQuietly(resultSetHolder.getAndSet(null));
                                            }
                                        } finally {
                                            closeAfterExecutionIfAllowed();
                                        }
                                    }
                                };
                            } finally {
                                if (internalIter == null) {
                                    closeAfterExecutionIfAllowed();
                                }
                            }
                        }

                        return internalIter;
                    }
                });

        return ExceptionalStream.newStream(lazyIter)
                .flatMap(rs -> JdbcUtil.stream(rs, rowMapper).onClose(() -> JdbcUtil.closeQuietly(rs)))
                .onClose(new Throwables.Runnable<SQLException>() {
                    @Override
                    public void run() throws SQLException {
                        lazyIter.close();
                    }
                });
    }

}