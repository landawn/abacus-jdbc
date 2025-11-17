/*
 * Copyright (c) 2025, Haiyang Li.
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
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import com.landawn.abacus.annotation.Internal;
import com.landawn.abacus.jdbc.Jdbc.ColumnGetter;
import com.landawn.abacus.util.Throwables;

/**
 * A proxy wrapper for {@link ResultSet} that provides optimized column value retrieval
 * with intelligent type handling and caching.
 *
 * <p>This class wraps a {@link ResultSet} and enhances performance by caching column getter
 * strategies based on the actual data types encountered. It provides special handling for
 * Oracle-specific data types (oracle.sql.TIMESTAMP, oracle.sql.DATE, etc.) and automatically
 * converts them to standard Java SQL types.</p>
 *
 * <p>The proxy uses two caching mechanisms:</p>
 * <ul>
 *   <li>Index-based caching: {@code columnGetters} array for column index access</li>
 *   <li>Label-based caching: {@code columnGettersByLabel} map for column label access</li>
 * </ul>
 *
 * <p>Key features:</p>
 * <ul>
 *   <li>Automatic detection and conversion of Oracle SQL types to standard Java types</li>
 *   <li>Performance optimization through getter strategy caching</li>
 *   <li>Transparent delegation of all {@link ResultSet} operations</li>
 *   <li>Special handling for DATE/TIMESTAMP type disambiguation</li>
 * </ul>
 *
 * <p><b>Method Delegation:</b></p>
 * <p>All standard {@link ResultSet} methods are transparently delegated to the underlying
 * ResultSet instance without modification. Only the {@link #getObject(int)} and
 * {@link #getObject(String)} methods provide enhanced functionality with Oracle-specific
 * type conversion and performance optimization through caching.</p>
 *
 * <p>This delegation pattern allows the proxy to be used as a drop-in replacement for any
 * {@link ResultSet} without requiring changes to existing JDBC code. Methods such as {@code next()},
 * {@code getString()}, {@code getInt()}, {@code close()}, and all other standard ResultSet operations
 * are transparently forwarded to the wrapped ResultSet.</p>
 *
 * <p>This class is marked as {@link Internal} and is intended for framework use only.</p>
 *
 * @see ResultSet
 * @see ColumnGetter
 */
@Internal
final class ResultSetProxy implements ResultSet {

    private ColumnGetter<?>[] columnGetters;
    private Map<String, Throwables.Function<ResultSet, Object, SQLException>> columnGettersByLabel;
    private final ResultSet delegate;

    /**
     * Constructs a new ResultSetProxy wrapping the specified ResultSet.
     * The proxy will enhance the performance of {@code getObject()} calls through caching
     * while transparently delegating all other operations to the underlying ResultSet.
     *
     * @param delegate the ResultSet to be wrapped, must not be {@code null}
     */
    ResultSetProxy(ResultSet delegate) {
        this.delegate = delegate;
    }

    /**
     * Creates a ResultSetProxy wrapper for the specified ResultSet.
     * Returns {@code null} if the input ResultSet is {@code null}.
     *
     * <p>This factory method is the recommended way to create ResultSetProxy instances.
     * It provides null-safety by returning {@code null} when given a {@code null} ResultSet.</p>
     *
     * @param rs the ResultSet to wrap, may be {@code null}
     * @return a new ResultSetProxy wrapping the ResultSet, or {@code null} if rs is {@code null}
     */
    static ResultSetProxy wrap(ResultSet rs) {
        return (rs == null) ? null : new ResultSetProxy(rs);
    }

    /**
     * Unwraps this proxy to return an object that implements the given interface.
     * Delegates to the underlying ResultSet's unwrap method.
     *
     * @param <T> the type of the object to be returned
     * @param iface a Class defining an interface that the result must implement
     * @return an object that implements the interface, may be a proxy
     * @throws SQLException if no object implementing the interface is found
     */
    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
    }

    /**
     * Returns {@code true} if this proxy wraps an object that implements the given interface.
     * Delegates to the underlying ResultSet's isWrapperFor method.
     *
     * @param iface a Class defining an interface
     * @return {@code true} if this implements the interface or directly or indirectly wraps an object that does
     * @throws SQLException if an error occurs while determining whether this is a wrapper for an object with the given interface
     */
    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface);
    }

    /**
     * Moves the cursor forward one row from its current position.
     * Delegates to the underlying ResultSet.
     *
     * @return {@code true} if the new current row is valid; {@code false} if there are no more rows
     * @throws SQLException if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public boolean next() throws SQLException {
        return delegate.next();
    }

    /**
     * Releases this ResultSet object's database and JDBC resources immediately.
     * Delegates to the underlying ResultSet.
     *
     * @throws SQLException if a database access error occurs
     */
    @Override
    public void close() throws SQLException {
        delegate.close();
    }

    @Override
    public boolean wasNull() throws SQLException {
        return delegate.wasNull();
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        return delegate.getString(columnIndex);
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        return delegate.getBoolean(columnIndex);
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        return delegate.getByte(columnIndex);
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        return delegate.getShort(columnIndex);
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        return delegate.getInt(columnIndex);
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        return delegate.getLong(columnIndex);
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        return delegate.getFloat(columnIndex);
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        return delegate.getDouble(columnIndex);
    }

    @Deprecated(since = "1.2")
    @Override
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        return delegate.getBigDecimal(columnIndex, scale);
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        return delegate.getBytes(columnIndex);
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        return delegate.getDate(columnIndex);
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        return delegate.getTime(columnIndex);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        return delegate.getTimestamp(columnIndex);
    }

    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        return delegate.getAsciiStream(columnIndex);
    }

    @Deprecated(since = "1.2")
    @Override
    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        return delegate.getUnicodeStream(columnIndex);
    }

    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        return delegate.getBinaryStream(columnIndex);
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        return delegate.getString(columnLabel);
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return delegate.getBoolean(columnLabel);
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        return delegate.getByte(columnLabel);
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        return delegate.getShort(columnLabel);
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return delegate.getInt(columnLabel);
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return delegate.getLong(columnLabel);
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        return delegate.getFloat(columnLabel);
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return delegate.getDouble(columnLabel);
    }

    @Deprecated(since = "1.2")
    @Override
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        return delegate.getBigDecimal(columnLabel, scale);
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        return delegate.getBytes(columnLabel);
    }

    @Override
    public Date getDate(String columnLabel) throws SQLException {
        return delegate.getDate(columnLabel);
    }

    @Override
    public Time getTime(String columnLabel) throws SQLException {
        return delegate.getTime(columnLabel);
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        return delegate.getTimestamp(columnLabel);
    }

    @Override
    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        return delegate.getAsciiStream(columnLabel);
    }

    @Deprecated(since = "1.2")
    @Override
    public InputStream getUnicodeStream(String columnLabel) throws SQLException {
        return delegate.getUnicodeStream(columnLabel);
    }

    @Override
    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        return delegate.getBinaryStream(columnLabel);
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return delegate.getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        delegate.clearWarnings();
    }

    @Override
    public String getCursorName() throws SQLException {
        return delegate.getCursorName();
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        return delegate.getMetaData();
    }

    /**
     * Retrieves the value of the designated column in the current row as an Object.
     *
     * <p>This method provides optimized retrieval with intelligent type handling:</p>
     * <ul>
     *   <li>Caches the appropriate getter strategy for each column on first access</li>
     *   <li>Automatically converts Oracle-specific types (oracle.sql.TIMESTAMP, oracle.sql.DATE) to standard Java SQL types</li>
     *   <li>Disambiguates between DATE and TIMESTAMP types using metadata when necessary</li>
     * </ul>
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value; if the value is SQL NULL, the value returned is null
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Object getObject(int columnIndex) throws SQLException {
        ResultSetMetaData metadata = null;

        if (columnGetters == null) {
            metadata = delegate.getMetaData();
            columnGetters = new ColumnGetter[metadata.getColumnCount() + 1];
        }

        if (columnGetters[columnIndex] == null) {
            Object ret = delegate.getObject(columnIndex);

            if (ret == null) {
                return ret;
            }

            if (ret instanceof String || ret instanceof Number || ret instanceof java.sql.Timestamp || ret instanceof Boolean) {
                columnGetters[columnIndex] = ColumnGetter.GET_OBJECT;
            } else {
                if (metadata == null) {
                    metadata = delegate.getMetaData();
                }

                final String className = ret.getClass().getName();

                if ("oracle.sql.TIMESTAMP".equals(className)) {
                    ret = ((oracle.sql.Datum) ret).timestampValue();
                    columnGetters[columnIndex] = ColumnGetter.GET_TIMESTAMP;
                } else if ("oracle.sql.TIMESTAMPTZ".equals(className) || "oracle.sql.TIMESTAMPLTZ".equals(className)) {
                    ret = ((oracle.sql.Datum) ret).timestampValue();
                    columnGetters[columnIndex] = ColumnGetter.GET_TIMESTAMP;
                } else if (className.startsWith("oracle.sql.DATE")) {
                    final String metaDataClassName = metadata.getColumnClassName(columnIndex);

                    if ("java.sql.Timestamp".equals(metaDataClassName) || "oracle.sql.TIMESTAMP".equals(metaDataClassName)) {
                        ret = delegate.getTimestamp(columnIndex);
                        columnGetters[columnIndex] = ColumnGetter.GET_TIMESTAMP;
                    } else {
                        ret = delegate.getDate(columnIndex);
                        columnGetters[columnIndex] = ColumnGetter.GET_DATE;
                    }

                } else if (ret instanceof java.sql.Date) {
                    final String metaDataClassName = metadata.getColumnClassName(columnIndex);

                    if ("java.sql.Timestamp".equals(metaDataClassName)) {
                        ret = delegate.getTimestamp(columnIndex);
                        columnGetters[columnIndex] = ColumnGetter.GET_TIMESTAMP;
                    } else {
                        columnGetters[columnIndex] = ColumnGetter.GET_DATE;
                    }
                } else {
                    columnGetters[columnIndex] = ColumnGetter.GET_OBJECT;
                }
            }

            return ret;
        } else {
            return columnGetters[columnIndex].apply(delegate, columnIndex);
        }
    }

    /**
     * Retrieves the value of the designated column in the current row as an Object.
     *
     * <p>This method provides optimized retrieval with intelligent type handling:</p>
     * <ul>
     *   <li>Caches the appropriate getter strategy for each column label on first access</li>
     *   <li>Automatically converts Oracle-specific types (oracle.sql.TIMESTAMP, oracle.sql.DATE) to standard Java SQL types</li>
     *   <li>Disambiguates between DATE and TIMESTAMP types using metadata when necessary</li>
     * </ul>
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return the column value; if the value is SQL NULL, the value returned is null
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Object getObject(String columnLabel) throws SQLException {
        ResultSetMetaData metadata = null;
        Throwables.Function<ResultSet, Object, SQLException> getter = null;

        if (columnGettersByLabel == null) {
            metadata = delegate.getMetaData();
            columnGettersByLabel = new HashMap<>(metadata.getColumnCount() + 1);
        } else {
            getter = columnGettersByLabel.get(columnLabel);
        }

        if (getter == null) {
            final int columnIndex = delegate.findColumn(columnLabel);
            Object ret = delegate.getObject(columnIndex);

            if (ret == null) {
                return ret;
            }

            if (ret instanceof String || ret instanceof Number || ret instanceof java.sql.Timestamp || ret instanceof Boolean) {
                getter = rs -> rs.getObject(columnIndex);
            } else {
                if (metadata == null) {
                    metadata = delegate.getMetaData();
                }

                final String className = ret.getClass().getName();

                if ("oracle.sql.TIMESTAMP".equals(className)) {
                    ret = ((oracle.sql.Datum) ret).timestampValue();
                    getter = rs -> rs.getTimestamp(columnIndex);
                } else if ("oracle.sql.TIMESTAMPTZ".equals(className) || "oracle.sql.TIMESTAMPLTZ".equals(className)) {
                    ret = ((oracle.sql.Datum) ret).timestampValue();
                    getter = rs -> rs.getTimestamp(columnIndex);
                } else if (className.startsWith("oracle.sql.DATE")) {
                    final String metaDataClassName = metadata.getColumnClassName(columnIndex);

                    if ("java.sql.Timestamp".equals(metaDataClassName) || "oracle.sql.TIMESTAMP".equals(metaDataClassName)) {
                        ret = delegate.getTimestamp(columnIndex);
                        getter = rs -> rs.getTimestamp(columnIndex);
                    } else {
                        ret = delegate.getDate(columnIndex);
                        getter = rs -> rs.getDate(columnIndex);
                    }
                } else if (ret instanceof java.sql.Date) {
                    final String metaDataClassName = metadata.getColumnClassName(columnIndex);

                    if ("java.sql.Timestamp".equals(metaDataClassName)) {
                        ret = delegate.getTimestamp(columnIndex);
                        getter = rs -> rs.getTimestamp(columnIndex);
                    } else {
                        getter = rs -> rs.getDate(columnIndex);
                    }
                } else {
                    getter = rs -> rs.getObject(columnIndex);
                }
            }

            columnGettersByLabel.put(columnLabel, getter);

            return ret;
        } else {
            return getter.apply(delegate);
        }
    }

    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        return delegate.getObject(columnIndex, type);
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        return delegate.getObject(columnLabel, type);
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        return delegate.findColumn(columnLabel);
    }

    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        return delegate.getCharacterStream(columnIndex);
    }

    @Override
    public Reader getCharacterStream(String columnLabel) throws SQLException {
        return delegate.getCharacterStream(columnLabel);
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        return delegate.getBigDecimal(columnIndex);
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        return delegate.getBigDecimal(columnLabel);
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        return delegate.isBeforeFirst();
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        return delegate.isAfterLast();
    }

    @Override
    public boolean isFirst() throws SQLException {
        return delegate.isFirst();
    }

    @Override
    public boolean isLast() throws SQLException {
        return delegate.isLast();
    }

    @Override
    public void beforeFirst() throws SQLException {
        delegate.beforeFirst();
    }

    @Override
    public void afterLast() throws SQLException {
        delegate.afterLast();
    }

    @Override
    public boolean first() throws SQLException {
        return delegate.first();
    }

    @Override
    public boolean last() throws SQLException {
        return delegate.last();
    }

    @Override
    public int getRow() throws SQLException {
        return delegate.getRow();
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        return delegate.absolute(row);
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        return delegate.relative(rows);
    }

    @Override
    public boolean previous() throws SQLException {
        return delegate.previous();
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        delegate.setFetchDirection(direction);
    }

    @Override
    public int getFetchDirection() throws SQLException {
        return delegate.getFetchDirection();
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        delegate.setFetchSize(rows);
    }

    @Override
    public int getFetchSize() throws SQLException {
        return delegate.getFetchSize();
    }

    @Override
    public int getType() throws SQLException {
        return delegate.getType();
    }

    @Override
    public int getConcurrency() throws SQLException {
        return delegate.getConcurrency();
    }

    @Override
    public boolean rowUpdated() throws SQLException {
        return delegate.rowUpdated();
    }

    @Override
    public boolean rowInserted() throws SQLException {
        return delegate.rowInserted();
    }

    @Override
    public boolean rowDeleted() throws SQLException {
        return delegate.rowDeleted();
    }

    @Override
    public void updateNull(int columnIndex) throws SQLException {
        delegate.updateNull(columnIndex);
    }

    @Override
    public void updateBoolean(int columnIndex, boolean x) throws SQLException {
        delegate.updateBoolean(columnIndex, x);
    }

    @Override
    public void updateByte(int columnIndex, byte x) throws SQLException {
        delegate.updateByte(columnIndex, x);
    }

    @Override
    public void updateShort(int columnIndex, short x) throws SQLException {
        delegate.updateShort(columnIndex, x);
    }

    @Override
    public void updateInt(int columnIndex, int x) throws SQLException {
        delegate.updateInt(columnIndex, x);
    }

    @Override
    public void updateLong(int columnIndex, long x) throws SQLException {
        delegate.updateLong(columnIndex, x);
    }

    @Override
    public void updateFloat(int columnIndex, float x) throws SQLException {
        delegate.updateFloat(columnIndex, x);
    }

    @Override
    public void updateDouble(int columnIndex, double x) throws SQLException {
        delegate.updateDouble(columnIndex, x);
    }

    @Override
    public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
        delegate.updateBigDecimal(columnIndex, x);
    }

    @Override
    public void updateString(int columnIndex, String x) throws SQLException {
        delegate.updateString(columnIndex, x);
    }

    @Override
    public void updateBytes(int columnIndex, byte[] x) throws SQLException {
        delegate.updateBytes(columnIndex, x);
    }

    @Override
    public void updateDate(int columnIndex, Date x) throws SQLException {
        delegate.updateDate(columnIndex, x);
    }

    @Override
    public void updateTime(int columnIndex, Time x) throws SQLException {
        delegate.updateTime(columnIndex, x);
    }

    @Override
    public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
        delegate.updateTimestamp(columnIndex, x);
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
        delegate.updateAsciiStream(columnIndex, x, length);
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
        delegate.updateBinaryStream(columnIndex, x, length);
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
        delegate.updateCharacterStream(columnIndex, x, length);
    }

    @Override
    public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
        delegate.updateObject(columnIndex, x, scaleOrLength);
    }

    @Override
    public void updateObject(int columnIndex, Object x) throws SQLException {
        delegate.updateObject(columnIndex, x);
    }

    @Override
    public void updateNull(String columnLabel) throws SQLException {
        delegate.updateNull(columnLabel);
    }

    @Override
    public void updateBoolean(String columnLabel, boolean x) throws SQLException {
        delegate.updateBoolean(columnLabel, x);
    }

    @Override
    public void updateByte(String columnLabel, byte x) throws SQLException {
        delegate.updateByte(columnLabel, x);
    }

    @Override
    public void updateShort(String columnLabel, short x) throws SQLException {
        delegate.updateShort(columnLabel, x);
    }

    @Override
    public void updateInt(String columnLabel, int x) throws SQLException {
        delegate.updateInt(columnLabel, x);
    }

    @Override
    public void updateLong(String columnLabel, long x) throws SQLException {
        delegate.updateLong(columnLabel, x);
    }

    @Override
    public void updateFloat(String columnLabel, float x) throws SQLException {
        delegate.updateFloat(columnLabel, x);
    }

    @Override
    public void updateDouble(String columnLabel, double x) throws SQLException {
        delegate.updateDouble(columnLabel, x);
    }

    @Override
    public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
        delegate.updateBigDecimal(columnLabel, x);
    }

    @Override
    public void updateString(String columnLabel, String x) throws SQLException {
        delegate.updateString(columnLabel, x);
    }

    @Override
    public void updateBytes(String columnLabel, byte[] x) throws SQLException {
        delegate.updateBytes(columnLabel, x);
    }

    @Override
    public void updateDate(String columnLabel, Date x) throws SQLException {
        delegate.updateDate(columnLabel, x);
    }

    @Override
    public void updateTime(String columnLabel, Time x) throws SQLException {
        delegate.updateTime(columnLabel, x);
    }

    @Override
    public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
        delegate.updateTimestamp(columnLabel, x);
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
        delegate.updateAsciiStream(columnLabel, x, length);
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {
        delegate.updateBinaryStream(columnLabel, x, length);
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException {
        delegate.updateCharacterStream(columnLabel, reader, length);
    }

    @Override
    public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
        delegate.updateObject(columnLabel, x, scaleOrLength);
    }

    @Override
    public void updateObject(String columnLabel, Object x) throws SQLException {
        delegate.updateObject(columnLabel, x);
    }

    @Override
    public void insertRow() throws SQLException {
        delegate.insertRow();
    }

    @Override
    public void updateRow() throws SQLException {
        delegate.updateRow();
    }

    @Override
    public void deleteRow() throws SQLException {
        delegate.deleteRow();
    }

    @Override
    public void refreshRow() throws SQLException {
        delegate.refreshRow();
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        delegate.cancelRowUpdates();
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        delegate.moveToInsertRow();
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        delegate.moveToCurrentRow();
    }

    @Override
    public Statement getStatement() throws SQLException {
        return delegate.getStatement();
    }

    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        return delegate.getObject(columnIndex, map);
    }

    @Override
    public Ref getRef(int columnIndex) throws SQLException {
        return delegate.getRef(columnIndex);
    }

    @Override
    public Blob getBlob(int columnIndex) throws SQLException {
        return delegate.getBlob(columnIndex);
    }

    @Override
    public Clob getClob(int columnIndex) throws SQLException {
        return delegate.getClob(columnIndex);
    }

    @Override
    public Array getArray(int columnIndex) throws SQLException {
        return delegate.getArray(columnIndex);
    }

    @Override
    public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        return delegate.getObject(columnLabel, map);
    }

    @Override
    public Ref getRef(String columnLabel) throws SQLException {
        return delegate.getRef(columnLabel);
    }

    @Override
    public Blob getBlob(String columnLabel) throws SQLException {
        return delegate.getBlob(columnLabel);
    }

    @Override
    public Clob getClob(String columnLabel) throws SQLException {
        return delegate.getClob(columnLabel);
    }

    @Override
    public Array getArray(String columnLabel) throws SQLException {
        return delegate.getArray(columnLabel);
    }

    @Override
    public Date getDate(int columnIndex, Calendar cal) throws SQLException {
        return delegate.getDate(columnIndex, cal);
    }

    @Override
    public Date getDate(String columnLabel, Calendar cal) throws SQLException {
        return delegate.getDate(columnLabel, cal);
    }

    @Override
    public Time getTime(int columnIndex, Calendar cal) throws SQLException {
        return delegate.getTime(columnIndex, cal);
    }

    @Override
    public Time getTime(String columnLabel, Calendar cal) throws SQLException {
        return delegate.getTime(columnLabel, cal);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
        return delegate.getTimestamp(columnIndex, cal);
    }

    @Override
    public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
        return delegate.getTimestamp(columnLabel, cal);
    }

    @Override
    public URL getURL(int columnIndex) throws SQLException {
        return delegate.getURL(columnIndex);
    }

    @Override
    public URL getURL(String columnLabel) throws SQLException {
        return delegate.getURL(columnLabel);
    }

    @Override
    public void updateRef(int columnIndex, Ref x) throws SQLException {
        delegate.updateRef(columnIndex, x);
    }

    @Override
    public void updateRef(String columnLabel, Ref x) throws SQLException {
        delegate.updateRef(columnLabel, x);
    }

    @Override
    public void updateBlob(int columnIndex, Blob x) throws SQLException {
        delegate.updateBlob(columnIndex, x);
    }

    @Override
    public void updateBlob(String columnLabel, Blob x) throws SQLException {
        delegate.updateBlob(columnLabel, x);
    }

    @Override
    public void updateClob(int columnIndex, Clob x) throws SQLException {
        delegate.updateClob(columnIndex, x);
    }

    @Override
    public void updateClob(String columnLabel, Clob x) throws SQLException {
        delegate.updateClob(columnLabel, x);
    }

    @Override
    public void updateArray(int columnIndex, Array x) throws SQLException {
        delegate.updateArray(columnIndex, x);
    }

    @Override
    public void updateArray(String columnLabel, Array x) throws SQLException {
        delegate.updateArray(columnLabel, x);
    }

    @Override
    public RowId getRowId(int columnIndex) throws SQLException {
        return delegate.getRowId(columnIndex);
    }

    @Override
    public RowId getRowId(String columnLabel) throws SQLException {
        return delegate.getRowId(columnLabel);
    }

    @Override
    public void updateRowId(int columnIndex, RowId x) throws SQLException {
        delegate.updateRowId(columnIndex, x);
    }

    @Override
    public void updateRowId(String columnLabel, RowId x) throws SQLException {
        delegate.updateRowId(columnLabel, x);
    }

    @Override
    public int getHoldability() throws SQLException {
        return delegate.getHoldability();
    }

    @Override
    public boolean isClosed() throws SQLException {
        return delegate.isClosed();
    }

    @Override
    public void updateNString(int columnIndex, String nString) throws SQLException {
        delegate.updateNString(columnIndex, nString);
    }

    @Override
    public void updateNString(String columnLabel, String nString) throws SQLException {
        delegate.updateNString(columnLabel, nString);
    }

    @Override
    public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
        delegate.updateNClob(columnIndex, nClob);
    }

    @Override
    public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
        delegate.updateNClob(columnLabel, nClob);
    }

    @Override
    public NClob getNClob(int columnIndex) throws SQLException {
        return delegate.getNClob(columnIndex);
    }

    @Override
    public NClob getNClob(String columnLabel) throws SQLException {
        return delegate.getNClob(columnLabel);
    }

    @Override
    public SQLXML getSQLXML(int columnIndex) throws SQLException {
        return delegate.getSQLXML(columnIndex);
    }

    @Override
    public SQLXML getSQLXML(String columnLabel) throws SQLException {
        return delegate.getSQLXML(columnLabel);
    }

    @Override
    public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
        delegate.updateSQLXML(columnIndex, xmlObject);
    }

    @Override
    public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
        delegate.updateSQLXML(columnLabel, xmlObject);
    }

    @Override
    public String getNString(int columnIndex) throws SQLException {
        return delegate.getNString(columnIndex);
    }

    @Override
    public String getNString(String columnLabel) throws SQLException {
        return delegate.getNString(columnLabel);
    }

    @Override
    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        return delegate.getNCharacterStream(columnIndex);
    }

    @Override
    public Reader getNCharacterStream(String columnLabel) throws SQLException {
        return delegate.getNCharacterStream(columnLabel);
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        delegate.updateNCharacterStream(columnIndex, x, length);
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        delegate.updateNCharacterStream(columnLabel, reader, length);
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
        delegate.updateAsciiStream(columnIndex, x, length);
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
        delegate.updateBinaryStream(columnIndex, x, length);
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        delegate.updateCharacterStream(columnIndex, x, length);
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {
        delegate.updateAsciiStream(columnLabel, x, length);
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {
        delegate.updateBinaryStream(columnLabel, x, length);
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        delegate.updateCharacterStream(columnLabel, reader, length);
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException {
        delegate.updateBlob(columnIndex, inputStream, length);
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException {
        delegate.updateBlob(columnLabel, inputStream, length);
    }

    @Override
    public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
        delegate.updateClob(columnIndex, reader, length);
    }

    @Override
    public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
        delegate.updateClob(columnLabel, reader, length);
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
        delegate.updateNClob(columnIndex, reader, length);
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
        delegate.updateNClob(columnLabel, reader, length);
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
        delegate.updateNCharacterStream(columnIndex, x);
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
        delegate.updateNCharacterStream(columnLabel, reader);
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
        delegate.updateAsciiStream(columnIndex, x);
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
        delegate.updateBinaryStream(columnIndex, x);
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
        delegate.updateCharacterStream(columnIndex, x);
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
        delegate.updateAsciiStream(columnLabel, x);
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
        delegate.updateBinaryStream(columnLabel, x);
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
        delegate.updateCharacterStream(columnLabel, reader);
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
        delegate.updateBlob(columnIndex, inputStream);
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
        delegate.updateBlob(columnLabel, inputStream);
    }

    @Override
    public void updateClob(int columnIndex, Reader reader) throws SQLException {
        delegate.updateClob(columnIndex, reader);
    }

    @Override
    public void updateClob(String columnLabel, Reader reader) throws SQLException {
        delegate.updateClob(columnLabel, reader);
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader) throws SQLException {
        delegate.updateNClob(columnIndex, reader);
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader) throws SQLException {
        delegate.updateNClob(columnLabel, reader);
    }

}
