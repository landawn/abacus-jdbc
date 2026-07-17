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
 *   <li>Automatic detection and conversion of Oracle SQL types (including {@code TIMESTAMPTZ}/{@code TIMESTAMPLTZ}) to standard Java types</li>
 *   <li>Inline materialization of {@link Blob} values to {@code byte[]} and {@link Clob} values to {@code String}</li>
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
 * <p>This class is marked as {@link Internal} and is intended for framework use only;
 * it is package-private and not part of the public API. Application code should not
 * reference this class directly. Like the wrapped {@code ResultSet}, an instance is
 * intended for single-threaded cursor access.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * ResultSet rs = stmt.executeQuery("SELECT id, created_ts, photo FROM account");
 * ResultSet proxy = ResultSetProxy.wrap(rs);
 *
 * while (proxy.next()) {
 *     long id = proxy.getLong("id");
 *     // getObject(...) applies Oracle type normalization and LOB materialization
 *     Object ts = proxy.getObject("created_ts"); // java.sql.Timestamp
 *     Object photo = proxy.getObject("photo");   // byte[] (Blob materialized)
 * }
 * }</pre>
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
     * <p>No null-check is performed here; passing a {@code null} delegate results in a
     * {@link NullPointerException} on the first delegated operation. Use {@link #wrap(ResultSet)}
     * for null-safe construction.</p>
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
     * Interfaces implemented by this proxy are satisfied by the proxy itself; other
     * interfaces are delegated to the wrapped {@code ResultSet}.
     *
     * @param <T> the type of the object to be returned
     * @param iface a Class defining an interface that the result must implement
     * @return an object that implements the interface, may be a proxy
     * @throws SQLException if no object implementing the interface is found
     */
    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface != null && iface.isInstance(this)) {
            return iface.cast(this);
        }

        return delegate.unwrap(iface);
    }

    /**
     * Returns {@code true} if this proxy or its delegate implements the given interface.
     *
     * @param iface a Class defining an interface
     * @return {@code true} if this implements the interface or directly or indirectly wraps an object that does
     * @throws SQLException if an error occurs while determining whether this is a wrapper for an object with the given interface
     */
    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface != null && (iface.isInstance(this) || delegate.isWrapperFor(iface));
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

    /**
     * Reports whether the last column read had a value of SQL NULL.
     *
     * <p>Note that you must first call one of the getter methods on a column to try to read its value
     * and then call this method to see if the value read was SQL NULL. Delegates to the underlying
     * {@link ResultSet}.</p>
     *
     * @return {@code true} if the last column value read was SQL NULL and {@code false} otherwise
     * @throws SQLException if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public boolean wasNull() throws SQLException {
        return delegate.wasNull();
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@code String}.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public String getString(int columnIndex) throws SQLException {
        return delegate.getString(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@code boolean}.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value; if the value is SQL NULL, the value returned is {@code false}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        return delegate.getBoolean(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@code byte}.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value; if the value is SQL NULL, the value returned is {@code 0}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public byte getByte(int columnIndex) throws SQLException {
        return delegate.getByte(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@code short}.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value; if the value is SQL NULL, the value returned is {@code 0}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public short getShort(int columnIndex) throws SQLException {
        return delegate.getShort(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as an {@code int}.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value; if the value is SQL NULL, the value returned is {@code 0}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public int getInt(int columnIndex) throws SQLException {
        return delegate.getInt(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@code long}.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value; if the value is SQL NULL, the value returned is {@code 0}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public long getLong(int columnIndex) throws SQLException {
        return delegate.getLong(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@code float}.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value; if the value is SQL NULL, the value returned is {@code 0}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public float getFloat(int columnIndex) throws SQLException {
        return delegate.getFloat(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@code double}.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value; if the value is SQL NULL, the value returned is {@code 0}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public double getDouble(int columnIndex) throws SQLException {
        return delegate.getDouble(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link BigDecimal}
     * with the given scale (number of digits to the right of the decimal point).
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param scale the number of digits to the right of the decimal point
     * @return the column value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     * @deprecated Deprecated in the JDBC API since JDK 1.2; use {@link #getBigDecimal(int)} instead.
     */
    @Deprecated(since = "1.2")
    @Override
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        return delegate.getBigDecimal(columnIndex, scale);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@code byte} array.
     * The bytes represent the raw values returned by the driver. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        return delegate.getBytes(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link Date}.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Date getDate(int columnIndex) throws SQLException {
        return delegate.getDate(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link Time}.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Time getTime(int columnIndex) throws SQLException {
        return delegate.getTime(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link Timestamp}.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        return delegate.getTimestamp(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as a stream of ASCII characters.
     * The value can then be read in chunks from the stream. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return a Java input stream that delivers the column value as a stream of one-byte ASCII characters; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        return delegate.getAsciiStream(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as a stream of two-byte Unicode characters.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return a Java input stream that delivers the column value as a stream of two-byte Unicode characters; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     * @deprecated Deprecated in the JDBC API since JDK 1.2; use {@link #getCharacterStream(int)} instead.
     */
    @Deprecated(since = "1.2")
    @Override
    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        return delegate.getUnicodeStream(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as a binary stream of uninterpreted bytes.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return a Java input stream that delivers the column value as a stream of uninterpreted bytes; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        return delegate.getBinaryStream(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@code String}.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return the column value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public String getString(String columnLabel) throws SQLException {
        return delegate.getString(columnLabel);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@code boolean}.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return the column value; if the value is SQL NULL, the value returned is {@code false}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return delegate.getBoolean(columnLabel);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@code byte}.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return the column value; if the value is SQL NULL, the value returned is {@code 0}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public byte getByte(String columnLabel) throws SQLException {
        return delegate.getByte(columnLabel);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@code short}.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return the column value; if the value is SQL NULL, the value returned is {@code 0}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public short getShort(String columnLabel) throws SQLException {
        return delegate.getShort(columnLabel);
    }

    /**
     * Retrieves the value of the designated column in the current row as an {@code int}.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return the column value; if the value is SQL NULL, the value returned is {@code 0}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public int getInt(String columnLabel) throws SQLException {
        return delegate.getInt(columnLabel);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@code long}.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return the column value; if the value is SQL NULL, the value returned is {@code 0}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public long getLong(String columnLabel) throws SQLException {
        return delegate.getLong(columnLabel);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@code float}.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return the column value; if the value is SQL NULL, the value returned is {@code 0}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public float getFloat(String columnLabel) throws SQLException {
        return delegate.getFloat(columnLabel);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@code double}.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return the column value; if the value is SQL NULL, the value returned is {@code 0}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return delegate.getDouble(columnLabel);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link BigDecimal}
     * with the given scale (number of digits to the right of the decimal point).
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param scale the number of digits to the right of the decimal point
     * @return the column value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     * @deprecated Deprecated in the JDBC API since JDK 1.2; use {@link #getBigDecimal(String)} instead.
     */
    @Deprecated(since = "1.2")
    @Override
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        return delegate.getBigDecimal(columnLabel, scale);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@code byte} array.
     * The bytes represent the raw values returned by the driver. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return the column value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        return delegate.getBytes(columnLabel);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link Date}.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return the column value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Date getDate(String columnLabel) throws SQLException {
        return delegate.getDate(columnLabel);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link Time}.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return the column value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Time getTime(String columnLabel) throws SQLException {
        return delegate.getTime(columnLabel);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link Timestamp}.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return the column value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        return delegate.getTimestamp(columnLabel);
    }

    /**
     * Retrieves the value of the designated column in the current row as a stream of ASCII characters.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return a Java input stream that delivers the column value as a stream of one-byte ASCII characters; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        return delegate.getAsciiStream(columnLabel);
    }

    /**
     * Retrieves the value of the designated column in the current row as a stream of two-byte Unicode characters.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return a Java input stream that delivers the column value as a stream of two-byte Unicode characters; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     * @deprecated Deprecated in the JDBC API since JDK 1.2; use {@link #getCharacterStream(String)} instead.
     */
    @Deprecated(since = "1.2")
    @Override
    public InputStream getUnicodeStream(String columnLabel) throws SQLException {
        return delegate.getUnicodeStream(columnLabel);
    }

    /**
     * Retrieves the value of the designated column in the current row as a binary stream of uninterpreted bytes.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return a Java input stream that delivers the column value as a stream of uninterpreted bytes; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        return delegate.getBinaryStream(columnLabel);
    }

    /**
     * Retrieves the first warning reported by calls on this ResultSet object.
     * Subsequent warnings on this ResultSet object will be chained to the {@link SQLWarning} object
     * that this method returns. Delegates to the underlying {@link ResultSet}.
     *
     * @return the first {@link SQLWarning} object reported or {@code null} if there are none
     * @throws SQLException if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public SQLWarning getWarnings() throws SQLException {
        return delegate.getWarnings();
    }

    /**
     * Clears all warnings reported on this ResultSet object. After this method is called, the method
     * {@link #getWarnings} returns {@code null} until a new warning is reported for this ResultSet object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @throws SQLException if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public void clearWarnings() throws SQLException {
        delegate.clearWarnings();
    }

    /**
     * Retrieves the name of the SQL cursor used by this ResultSet object. Delegates to the underlying {@link ResultSet}.
     *
     * @return the SQL name for this ResultSet object's cursor
     * @throws SQLException if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public String getCursorName() throws SQLException {
        return delegate.getCursorName();
    }

    /**
     * Retrieves the number, types and properties of this ResultSet object's columns.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @return the description of this ResultSet object's columns
     * @throws SQLException if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        return delegate.getMetaData();
    }

    /**
     * Retrieves the value of the designated column in the current row as an Object.
     *
     * <p>This method provides optimized retrieval with intelligent type handling:</p>
     * <ul>
     *   <li>Caches the appropriate getter strategy for each column on the first access that returns a non-null value</li>
     *   <li>Automatically converts Oracle-specific types ({@code oracle.sql.TIMESTAMP}, {@code oracle.sql.DATE}) to standard Java SQL types</li>
     *   <li>Handles Oracle {@code TIMESTAMPTZ}/{@code TIMESTAMPLTZ} columns via the driver's timezone-aware conversion</li>
     *   <li>Disambiguates between {@code DATE} and {@code TIMESTAMP} types using metadata when necessary</li>
     *   <li>Materializes {@link Blob} values to {@code byte[]} and {@link Clob} values to {@code String}</li>
     * </ul>
     *
     * <p>If the column index is non-positive or beyond the column count, the call is delegated to
     * the underlying {@link ResultSet}; if the value read is SQL NULL, the already-read {@code null}
     * is returned. In both cases no getter strategy is cached.</p>
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

        if (columnIndex <= 0 || columnIndex >= columnGetters.length) {
            return delegate.getObject(columnIndex);
        }

        if (columnGetters[columnIndex] == null) {
            Object ret = delegate.getObject(columnIndex);

            if (ret == null) {
                // Don't cache getter when value is null -- defer type detection to first non-null value
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
                    // Datum.timestampValue() (no-arg) throws SQLException on TIMESTAMPLTZ because
                    // LTZ requires the originating Connection's session timezone to materialize.
                    // delegate.getTimestamp lets the driver perform the timezone-aware conversion
                    // correctly for both TZ and LTZ.
                    ret = delegate.getTimestamp(columnIndex);
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

                    // Mirror the oracle.sql.DATE branch above: oracle.sql.TIMESTAMP as the declared
                    // metadata type also indicates the column carries time-of-day info; reading via
                    // getDate would silently truncate. Pre-fix, only "java.sql.Timestamp" was checked.
                    if ("java.sql.Timestamp".equals(metaDataClassName) || "oracle.sql.TIMESTAMP".equals(metaDataClassName)) {
                        ret = delegate.getTimestamp(columnIndex);
                        columnGetters[columnIndex] = ColumnGetter.GET_TIMESTAMP;
                    } else {
                        columnGetters[columnIndex] = ColumnGetter.GET_DATE;
                    }
                } else {
                    // Materialize Blob/Clob inline so the row-1 return matches what subsequent rows
                    // produce via the cached GET_OBJECT (which delegates to JdbcUtil.getColumnValue
                    // and unconditionally materializes LOBs). Pre-fix, row 1 returned the raw Blob
                    // while rows 2+ returned byte[] — inconsistent for the same column.
                    if (ret instanceof Blob blob) {
                        ret = materialize(blob);
                    } else if (ret instanceof Clob clob) {
                        ret = materialize(clob);
                    }
                    columnGetters[columnIndex] = ColumnGetter.GET_OBJECT;
                }
            }

            return ret;
        } else {
            return columnGetters[columnIndex].get(delegate, columnIndex);
        }
    }

    /**
     * Retrieves the value of the designated column in the current row as an Object.
     *
     * <p>This method provides optimized retrieval with intelligent type handling:</p>
     * <ul>
     *   <li>Caches the appropriate getter strategy for each column label on the first access that returns a non-null value</li>
     *   <li>Automatically converts Oracle-specific types ({@code oracle.sql.TIMESTAMP}, {@code oracle.sql.DATE}) to standard Java SQL types</li>
     *   <li>Handles Oracle {@code TIMESTAMPTZ}/{@code TIMESTAMPLTZ} columns via the driver's timezone-aware conversion</li>
     *   <li>Disambiguates between {@code DATE} and {@code TIMESTAMP} types using metadata when necessary</li>
     *   <li>Materializes {@link Blob} values to {@code byte[]} and {@link Clob} values to {@code String}</li>
     * </ul>
     *
     * <p>Unlike the index form, the label is always resolved to a column index via the underlying
     * {@link ResultSet#findColumn(String)}, so there is no out-of-range pass-through path for the
     * label form; like the index form, the getter strategy is cached only after the first non-null
     * read for that label.</p>
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
            columnGettersByLabel = new HashMap<>();
        } else {
            getter = columnGettersByLabel.get(columnLabel);
        }

        if (getter == null) {
            final int columnIndex = delegate.findColumn(columnLabel);
            Object ret = delegate.getObject(columnIndex);

            if (ret == null) {
                // Don't cache getter when value is null -- defer type detection to first non-null value
                return ret;
            }

            if (ret instanceof String || ret instanceof Number || ret instanceof java.sql.Timestamp || ret instanceof Boolean) {
                // Matches the index path's cached GET_OBJECT getter, so a type-shifting column (e.g.
                // SQLite dynamic typing) reads identically whether accessed by index or by label.
                getter = rs -> JdbcUtil.getColumnValue(rs, columnIndex);
            } else {
                final String className = ret.getClass().getName();

                if ("oracle.sql.TIMESTAMP".equals(className)) {
                    ret = ((oracle.sql.Datum) ret).timestampValue();
                    getter = rs -> rs.getTimestamp(columnIndex);
                } else if ("oracle.sql.TIMESTAMPTZ".equals(className) || "oracle.sql.TIMESTAMPLTZ".equals(className)) {
                    // Datum.timestampValue() (no-arg) throws SQLException on TIMESTAMPLTZ because
                    // LTZ requires the originating Connection's session timezone to materialize.
                    // Delegate to driver getTimestamp for both TZ and LTZ; see index-path variant.
                    ret = delegate.getTimestamp(columnIndex);
                    getter = rs -> rs.getTimestamp(columnIndex);
                } else if (className.startsWith("oracle.sql.DATE")) {
                    metadata = delegate.getMetaData();
                    final String metaDataClassName = metadata.getColumnClassName(columnIndex);

                    if ("java.sql.Timestamp".equals(metaDataClassName) || "oracle.sql.TIMESTAMP".equals(metaDataClassName)) {
                        ret = delegate.getTimestamp(columnIndex);
                        getter = rs -> rs.getTimestamp(columnIndex);
                    } else {
                        ret = delegate.getDate(columnIndex);
                        getter = rs -> rs.getDate(columnIndex);
                    }
                } else if (ret instanceof java.sql.Date) {
                    metadata = delegate.getMetaData();
                    final String metaDataClassName = metadata.getColumnClassName(columnIndex);

                    // Mirror the oracle.sql.DATE branch and the index-path java.sql.Date branch:
                    // an oracle.sql.TIMESTAMP declared metadata type means the column carries
                    // time-of-day info; reading via getDate would silently truncate. Pre-fix,
                    // only "java.sql.Timestamp" was checked here.
                    if ("java.sql.Timestamp".equals(metaDataClassName) || "oracle.sql.TIMESTAMP".equals(metaDataClassName)) {
                        ret = delegate.getTimestamp(columnIndex);
                        getter = rs -> rs.getTimestamp(columnIndex);
                    } else {
                        getter = rs -> rs.getDate(columnIndex);
                    }
                } else {
                    // Mirror the index-path fall-through: materialize Blob/Clob inline so this
                    // first-row return matches what subsequent rows produce via the cached getter.
                    // The cached getter goes through JdbcUtil.getColumnValue (matching the index
                    // path) so the same column read via int or label yields the same type.
                    if (ret instanceof Blob blob) {
                        ret = materialize(blob);
                    } else if (ret instanceof Clob clob) {
                        ret = materialize(clob);
                    }
                    getter = rs -> JdbcUtil.getColumnValue(rs, columnIndex);
                }
            }

            columnGettersByLabel.put(columnLabel, getter);

            return ret;
        } else {
            return getter.apply(delegate);
        }
    }

    private static byte[] materialize(final Blob blob) throws SQLException {
        Throwable failure = null;

        try {
            final long len = blob.length();

            if (len > Integer.MAX_VALUE) {
                throw new SQLException("Blob size " + len + " exceeds maximum supported size of " + Integer.MAX_VALUE);
            }

            return blob.getBytes(1, (int) len);
        } catch (final Throwable e) { //NOSONAR
            failure = e;
            throw e;
        } finally {
            try {
                blob.free();
            } catch (final Throwable e) { //NOSONAR - preserve unchecked cleanup failures too
                if (failure == null) {
                    throw e;
                }

                if (failure != e) {
                    failure.addSuppressed(e);
                }
            }
        }
    }

    private static String materialize(final Clob clob) throws SQLException {
        Throwable failure = null;

        try {
            final long len = clob.length();

            if (len > Integer.MAX_VALUE) {
                throw new SQLException("Clob size " + len + " exceeds maximum supported size of " + Integer.MAX_VALUE);
            }

            return clob.getSubString(1, (int) len);
        } catch (final Throwable e) { //NOSONAR
            failure = e;
            throw e;
        } finally {
            try {
                clob.free();
            } catch (final Throwable e) { //NOSONAR - preserve unchecked cleanup failures too
                if (failure == null) {
                    throw e;
                }

                if (failure != e) {
                    failure.addSuppressed(e);
                }
            }
        }
    }

    /**
     * Retrieves the value of the designated column in the current row and converts it to the requested
     * Java data type, if conversion is supported by the driver. Delegates to the underlying {@link ResultSet}.
     *
     * @param <T> the requested Java type
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param type the {@link Class} representing the Java data type to convert the designated column to
     * @return an instance of {@code type} holding the column value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if conversion is not supported, type is {@code null} or another error occurs; if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        return delegate.getObject(columnIndex, type);
    }

    /**
     * Retrieves the value of the designated column in the current row and converts it to the requested
     * Java data type, if conversion is supported by the driver. Delegates to the underlying {@link ResultSet}.
     *
     * @param <T> the requested Java type
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param type the {@link Class} representing the Java data type to convert the designated column to
     * @return an instance of {@code type} holding the column value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if conversion is not supported, type is {@code null} or another error occurs; if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        return delegate.getObject(columnLabel, type);
    }

    /**
     * Maps the given column label to its column index. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return the column index of the given column label
     * @throws SQLException if the ResultSet object does not contain a column labeled columnLabel, a database access error occurs or this method is called on a closed result set
     */
    @Override
    public int findColumn(String columnLabel) throws SQLException {
        return delegate.findColumn(columnLabel);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link Reader} of characters.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return a {@link Reader} that delivers the column value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        return delegate.getCharacterStream(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link Reader} of characters.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return a {@link Reader} that delivers the column value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Reader getCharacterStream(String columnLabel) throws SQLException {
        return delegate.getCharacterStream(columnLabel);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link BigDecimal}
     * with full precision. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value (full precision); if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        return delegate.getBigDecimal(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link BigDecimal}
     * with full precision. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return the column value (full precision); if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        return delegate.getBigDecimal(columnLabel);
    }

    /**
     * Retrieves whether the cursor is before the first row in this ResultSet object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @return {@code true} if the cursor is before the first row; {@code false} if the cursor is at any other position or the result set contains no rows
     * @throws SQLException if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public boolean isBeforeFirst() throws SQLException {
        return delegate.isBeforeFirst();
    }

    /**
     * Retrieves whether the cursor is after the last row in this ResultSet object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @return {@code true} if the cursor is after the last row; {@code false} if the cursor is at any other position or the result set contains no rows
     * @throws SQLException if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public boolean isAfterLast() throws SQLException {
        return delegate.isAfterLast();
    }

    /**
     * Retrieves whether the cursor is on the first row of this ResultSet object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @return {@code true} if the cursor is on the first row; {@code false} otherwise
     * @throws SQLException if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public boolean isFirst() throws SQLException {
        return delegate.isFirst();
    }

    /**
     * Retrieves whether the cursor is on the last row of this ResultSet object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @return {@code true} if the cursor is on the last row; {@code false} otherwise
     * @throws SQLException if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public boolean isLast() throws SQLException {
        return delegate.isLast();
    }

    /**
     * Moves the cursor to the front of this ResultSet object, just before the first row.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @throws SQLException if a database access error occurs; this method is called on a closed result set or the result set type is {@code TYPE_FORWARD_ONLY}
     */
    @Override
    public void beforeFirst() throws SQLException {
        delegate.beforeFirst();
    }

    /**
     * Moves the cursor to the end of this ResultSet object, just after the last row.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @throws SQLException if a database access error occurs; this method is called on a closed result set or the result set type is {@code TYPE_FORWARD_ONLY}
     */
    @Override
    public void afterLast() throws SQLException {
        delegate.afterLast();
    }

    /**
     * Moves the cursor to the first row in this ResultSet object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @return {@code true} if the cursor is on a valid row; {@code false} if there are no rows in the result set
     * @throws SQLException if a database access error occurs; this method is called on a closed result set or the result set type is {@code TYPE_FORWARD_ONLY}
     */
    @Override
    public boolean first() throws SQLException {
        return delegate.first();
    }

    /**
     * Moves the cursor to the last row in this ResultSet object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @return {@code true} if the cursor is on a valid row; {@code false} if there are no rows in the result set
     * @throws SQLException if a database access error occurs; this method is called on a closed result set or the result set type is {@code TYPE_FORWARD_ONLY}
     */
    @Override
    public boolean last() throws SQLException {
        return delegate.last();
    }

    /**
     * Retrieves the current row number. The first row is number 1, the second number 2, and so on.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @return the current row number; {@code 0} if there is no current row
     * @throws SQLException if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public int getRow() throws SQLException {
        return delegate.getRow();
    }

    /**
     * Moves the cursor to the given row number in this ResultSet object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param row the number of the row to which the cursor should move. A positive number indicates the row number counting from the beginning of the result set; a negative number indicates the row number counting from the end of the result set; zero indicates a position before the first row
     * @return {@code true} if the cursor is moved to a position in this ResultSet object; {@code false} if the cursor is before the first row or after the last row
     * @throws SQLException if a database access error occurs; this method is called on a closed result set or the result set type is {@code TYPE_FORWARD_ONLY}
     */
    @Override
    public boolean absolute(int row) throws SQLException {
        return delegate.absolute(row);
    }

    /**
     * Moves the cursor a relative number of rows, either positive or negative, from the current row.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param rows an {@code int} specifying the number of rows to move from the current row; a positive number moves the cursor forward; a negative number moves the cursor backward
     * @return {@code true} if the cursor is on a row; {@code false} otherwise
     * @throws SQLException if a database access error occurs; this method is called on a closed result set; there is no current row or the result set type is {@code TYPE_FORWARD_ONLY}
     */
    @Override
    public boolean relative(int rows) throws SQLException {
        return delegate.relative(rows);
    }

    /**
     * Moves the cursor to the previous row in this ResultSet object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @return {@code true} if the cursor is now positioned on a valid row; {@code false} if the cursor is positioned before the first row
     * @throws SQLException if a database access error occurs; this method is called on a closed result set or the result set type is {@code TYPE_FORWARD_ONLY}
     */
    @Override
    public boolean previous() throws SQLException {
        return delegate.previous();
    }

    /**
     * Gives a hint as to the direction in which the rows in this ResultSet object will be processed.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param direction an {@code int} specifying the suggested fetch direction; one of {@code ResultSet.FETCH_FORWARD}, {@code ResultSet.FETCH_REVERSE}, or {@code ResultSet.FETCH_UNKNOWN}
     * @throws SQLException if a database access error occurs; this method is called on a closed result set or the result set type is {@code TYPE_FORWARD_ONLY} and the fetch direction is not {@code FETCH_FORWARD}
     */
    @Override
    public void setFetchDirection(int direction) throws SQLException {
        delegate.setFetchDirection(direction);
    }

    /**
     * Retrieves the fetch direction for this ResultSet object. Delegates to the underlying {@link ResultSet}.
     *
     * @return the current fetch direction for this ResultSet object
     * @throws SQLException if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public int getFetchDirection() throws SQLException {
        return delegate.getFetchDirection();
    }

    /**
     * Gives the JDBC driver a hint as to the number of rows that should be fetched from the database
     * when more rows are needed for this ResultSet object. Delegates to the underlying {@link ResultSet}.
     *
     * @param rows the number of rows to fetch
     * @throws SQLException if a database access error occurs; this method is called on a closed result set or the condition {@code rows >= 0} is not satisfied
     */
    @Override
    public void setFetchSize(int rows) throws SQLException {
        delegate.setFetchSize(rows);
    }

    /**
     * Retrieves the fetch size for this ResultSet object. Delegates to the underlying {@link ResultSet}.
     *
     * @return the current fetch size for this ResultSet object
     * @throws SQLException if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public int getFetchSize() throws SQLException {
        return delegate.getFetchSize();
    }

    /**
     * Retrieves the type of this ResultSet object. Delegates to the underlying {@link ResultSet}.
     *
     * @return {@code ResultSet.TYPE_FORWARD_ONLY}, {@code ResultSet.TYPE_SCROLL_INSENSITIVE}, or {@code ResultSet.TYPE_SCROLL_SENSITIVE}
     * @throws SQLException if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public int getType() throws SQLException {
        return delegate.getType();
    }

    /**
     * Retrieves the concurrency mode of this ResultSet object. Delegates to the underlying {@link ResultSet}.
     *
     * @return the concurrency type, either {@code ResultSet.CONCUR_READ_ONLY} or {@code ResultSet.CONCUR_UPDATABLE}
     * @throws SQLException if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public int getConcurrency() throws SQLException {
        return delegate.getConcurrency();
    }

    /**
     * Retrieves whether the current row has been updated. Delegates to the underlying {@link ResultSet}.
     *
     * @return {@code true} if the current row is detected to have been visibly updated by the owner or another transaction
     * @throws SQLException if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public boolean rowUpdated() throws SQLException {
        return delegate.rowUpdated();
    }

    /**
     * Retrieves whether the current row has had an insertion. Delegates to the underlying {@link ResultSet}.
     *
     * @return {@code true} if the current row is detected to have been inserted
     * @throws SQLException if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public boolean rowInserted() throws SQLException {
        return delegate.rowInserted();
    }

    /**
     * Retrieves whether a row has been deleted. Delegates to the underlying {@link ResultSet}.
     *
     * @return {@code true} if the current row is detected to have been deleted by the owner or another transaction
     * @throws SQLException if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public boolean rowDeleted() throws SQLException {
        return delegate.rowDeleted();
    }

    /**
     * Updates the designated column with a {@code null} value. The updater methods modify the current
     * row or the insert row only; the database is not changed until {@link #updateRow} or {@link #insertRow}
     * is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateNull(int columnIndex) throws SQLException {
        delegate.updateNull(columnIndex);
    }

    /**
     * Updates the designated column with a {@code boolean} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateBoolean(int columnIndex, boolean x) throws SQLException {
        delegate.updateBoolean(columnIndex, x);
    }

    /**
     * Updates the designated column with a {@code byte} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateByte(int columnIndex, byte x) throws SQLException {
        delegate.updateByte(columnIndex, x);
    }

    /**
     * Updates the designated column with a {@code short} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateShort(int columnIndex, short x) throws SQLException {
        delegate.updateShort(columnIndex, x);
    }

    /**
     * Updates the designated column with an {@code int} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateInt(int columnIndex, int x) throws SQLException {
        delegate.updateInt(columnIndex, x);
    }

    /**
     * Updates the designated column with a {@code long} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateLong(int columnIndex, long x) throws SQLException {
        delegate.updateLong(columnIndex, x);
    }

    /**
     * Updates the designated column with a {@code float} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateFloat(int columnIndex, float x) throws SQLException {
        delegate.updateFloat(columnIndex, x);
    }

    /**
     * Updates the designated column with a {@code double} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateDouble(int columnIndex, double x) throws SQLException {
        delegate.updateDouble(columnIndex, x);
    }

    /**
     * Updates the designated column with a {@link BigDecimal} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
        delegate.updateBigDecimal(columnIndex, x);
    }

    /**
     * Updates the designated column with a {@code String} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateString(int columnIndex, String x) throws SQLException {
        delegate.updateString(columnIndex, x);
    }

    /**
     * Updates the designated column with a {@code byte} array value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateBytes(int columnIndex, byte[] x) throws SQLException {
        delegate.updateBytes(columnIndex, x);
    }

    /**
     * Updates the designated column with a {@link Date} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateDate(int columnIndex, Date x) throws SQLException {
        delegate.updateDate(columnIndex, x);
    }

    /**
     * Updates the designated column with a {@link Time} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateTime(int columnIndex, Time x) throws SQLException {
        delegate.updateTime(columnIndex, x);
    }

    /**
     * Updates the designated column with a {@link Timestamp} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
        delegate.updateTimestamp(columnIndex, x);
    }

    /**
     * Updates the designated column with an ASCII stream value, which will have the specified number of bytes.
     * The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @param length the length of the stream
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
        delegate.updateAsciiStream(columnIndex, x, length);
    }

    /**
     * Updates the designated column with a binary stream value, which will have the specified number of bytes.
     * The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @param length the length of the stream
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
        delegate.updateBinaryStream(columnIndex, x, length);
    }

    /**
     * Updates the designated column with a character stream value, which will have the specified number of characters.
     * The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @param length the length of the stream
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
        delegate.updateCharacterStream(columnIndex, x, length);
    }

    /**
     * Updates the designated column with an {@code Object} value, optionally specifying a scale or length.
     * The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @param scaleOrLength for an object of {@code java.math.BigDecimal}, this is the number of digits after the decimal point; for Java Object types {@code InputStream} and {@code Reader}, this is the length of the data in the stream or reader; for all other types, this value will be ignored
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
        delegate.updateObject(columnIndex, x, scaleOrLength);
    }

    /**
     * Updates the designated column with an {@code Object} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateObject(int columnIndex, Object x) throws SQLException {
        delegate.updateObject(columnIndex, x);
    }

    /**
     * Updates the designated column with an {@code Object} value using the given target SQL type.
     * The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet} (without this override, the JDBC 4.2 interface
     * default would throw {@code SQLFeatureNotSupportedException} even when the wrapped driver
     * supports it).
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @param targetSqlType the SQL type to be sent to the database
     * @param scaleOrLength for an object of {@code java.math.BigDecimal}, this is the number of digits after the decimal point; for Java Object types {@code InputStream} and {@code Reader}, this is the length of the data in the stream or reader; for all other types, this value will be ignored
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateObject(int columnIndex, Object x, java.sql.SQLType targetSqlType, int scaleOrLength) throws SQLException {
        delegate.updateObject(columnIndex, x, targetSqlType, scaleOrLength);
    }

    /**
     * Updates the designated column with an {@code Object} value using the given target SQL type.
     * The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @param targetSqlType the SQL type to be sent to the database
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateObject(int columnIndex, Object x, java.sql.SQLType targetSqlType) throws SQLException {
        delegate.updateObject(columnIndex, x, targetSqlType);
    }

    /**
     * Updates the designated column with a {@code null} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateNull(String columnLabel) throws SQLException {
        delegate.updateNull(columnLabel);
    }

    /**
     * Updates the designated column with a {@code boolean} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateBoolean(String columnLabel, boolean x) throws SQLException {
        delegate.updateBoolean(columnLabel, x);
    }

    /**
     * Updates the designated column with a {@code byte} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateByte(String columnLabel, byte x) throws SQLException {
        delegate.updateByte(columnLabel, x);
    }

    /**
     * Updates the designated column with a {@code short} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateShort(String columnLabel, short x) throws SQLException {
        delegate.updateShort(columnLabel, x);
    }

    /**
     * Updates the designated column with an {@code int} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateInt(String columnLabel, int x) throws SQLException {
        delegate.updateInt(columnLabel, x);
    }

    /**
     * Updates the designated column with a {@code long} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateLong(String columnLabel, long x) throws SQLException {
        delegate.updateLong(columnLabel, x);
    }

    /**
     * Updates the designated column with a {@code float} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateFloat(String columnLabel, float x) throws SQLException {
        delegate.updateFloat(columnLabel, x);
    }

    /**
     * Updates the designated column with a {@code double} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateDouble(String columnLabel, double x) throws SQLException {
        delegate.updateDouble(columnLabel, x);
    }

    /**
     * Updates the designated column with a {@link BigDecimal} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
        delegate.updateBigDecimal(columnLabel, x);
    }

    /**
     * Updates the designated column with a {@code String} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateString(String columnLabel, String x) throws SQLException {
        delegate.updateString(columnLabel, x);
    }

    /**
     * Updates the designated column with a {@code byte} array value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateBytes(String columnLabel, byte[] x) throws SQLException {
        delegate.updateBytes(columnLabel, x);
    }

    /**
     * Updates the designated column with a {@link Date} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateDate(String columnLabel, Date x) throws SQLException {
        delegate.updateDate(columnLabel, x);
    }

    /**
     * Updates the designated column with a {@link Time} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateTime(String columnLabel, Time x) throws SQLException {
        delegate.updateTime(columnLabel, x);
    }

    /**
     * Updates the designated column with a {@link Timestamp} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
        delegate.updateTimestamp(columnLabel, x);
    }

    /**
     * Updates the designated column with an ASCII stream value, which will have the specified number of bytes.
     * The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @param length the length of the stream
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
        delegate.updateAsciiStream(columnLabel, x, length);
    }

    /**
     * Updates the designated column with a binary stream value, which will have the specified number of bytes.
     * The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @param length the length of the stream
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {
        delegate.updateBinaryStream(columnLabel, x, length);
    }

    /**
     * Updates the designated column with a character stream value, which will have the specified number of characters.
     * The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param reader the {@link Reader} object containing the new column value
     * @param length the length of the stream
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException {
        delegate.updateCharacterStream(columnLabel, reader, length);
    }

    /**
     * Updates the designated column with an {@code Object} value, optionally specifying a scale or length.
     * The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @param scaleOrLength for an object of {@code java.math.BigDecimal}, this is the number of digits after the decimal point; for Java Object types {@code InputStream} and {@code Reader}, this is the length of the data in the stream or reader; for all other types, this value will be ignored
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
        delegate.updateObject(columnLabel, x, scaleOrLength);
    }

    /**
     * Updates the designated column with an {@code Object} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateObject(String columnLabel, Object x) throws SQLException {
        delegate.updateObject(columnLabel, x);
    }

    /**
     * Updates the designated column with an {@code Object} value using the given target SQL type.
     * The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @param targetSqlType the SQL type to be sent to the database
     * @param scaleOrLength for an object of {@code java.math.BigDecimal}, this is the number of digits after the decimal point; for Java Object types {@code InputStream} and {@code Reader}, this is the length of the data in the stream or reader; for all other types, this value will be ignored
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateObject(String columnLabel, Object x, java.sql.SQLType targetSqlType, int scaleOrLength) throws SQLException {
        delegate.updateObject(columnLabel, x, targetSqlType, scaleOrLength);
    }

    /**
     * Updates the designated column with an {@code Object} value using the given target SQL type.
     * The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @param targetSqlType the SQL type to be sent to the database
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateObject(String columnLabel, Object x, java.sql.SQLType targetSqlType) throws SQLException {
        delegate.updateObject(columnLabel, x, targetSqlType);
    }

    /**
     * Inserts the contents of the insert row into this ResultSet object and into the database.
     * The cursor must be on the insert row when this method is called. Delegates to the underlying {@link ResultSet}.
     *
     * @throws SQLException if a database access error occurs; this method is called when the cursor is not on the insert row; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void insertRow() throws SQLException {
        delegate.insertRow();
    }

    /**
     * Updates the underlying database with the new contents of the current row of this ResultSet object.
     * This method cannot be called when the cursor is on the insert row. Delegates to the underlying {@link ResultSet}.
     *
     * @throws SQLException if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY}; this method is called when the cursor is on the insert row or this method is called on a closed result set
     */
    @Override
    public void updateRow() throws SQLException {
        delegate.updateRow();
    }

    /**
     * Deletes the current row from this ResultSet object and from the underlying database.
     * This method cannot be called when the cursor is on the insert row. Delegates to the underlying {@link ResultSet}.
     *
     * @throws SQLException if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY}; this method is called when the cursor is on the insert row or this method is called on a closed result set
     */
    @Override
    public void deleteRow() throws SQLException {
        delegate.deleteRow();
    }

    /**
     * Refreshes the current row with its most recent value in the database. This method cannot be called
     * when the cursor is on the insert row. Delegates to the underlying {@link ResultSet}.
     *
     * @throws SQLException if a database access error occurs; this method is called on a closed result set; the result set type is {@code TYPE_FORWARD_ONLY} or this method is called when the cursor is on the insert row
     */
    @Override
    public void refreshRow() throws SQLException {
        delegate.refreshRow();
    }

    /**
     * Cancels the updates made to the current row in this ResultSet object. This method may be called
     * after calling an updater method but before calling {@link #updateRow}. Delegates to the underlying {@link ResultSet}.
     *
     * @throws SQLException if a database access error occurs; this method is called on a closed result set; the result set concurrency is {@code CONCUR_READ_ONLY} or if this method is called when the cursor is on the insert row
     */
    @Override
    public void cancelRowUpdates() throws SQLException {
        delegate.cancelRowUpdates();
    }

    /**
     * Moves the cursor to the insert row. The current cursor position is remembered while the cursor is
     * positioned on the insert row. Delegates to the underlying {@link ResultSet}.
     *
     * @throws SQLException if a database access error occurs; this method is called on a closed result set or the result set concurrency is {@code CONCUR_READ_ONLY}
     */
    @Override
    public void moveToInsertRow() throws SQLException {
        delegate.moveToInsertRow();
    }

    /**
     * Moves the cursor to the remembered cursor position, usually the current row. This method has no
     * effect if the cursor is not on the insert row. Delegates to the underlying {@link ResultSet}.
     *
     * @throws SQLException if a database access error occurs; this method is called on a closed result set or the result set concurrency is {@code CONCUR_READ_ONLY}
     */
    @Override
    public void moveToCurrentRow() throws SQLException {
        delegate.moveToCurrentRow();
    }

    /**
     * Retrieves the {@link Statement} object that produced this ResultSet object. Delegates to the underlying {@link ResultSet}.
     *
     * @return the {@code Statement} object that produced this ResultSet object or {@code null} if the result set was produced some other way
     * @throws SQLException if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Statement getStatement() throws SQLException {
        return delegate.getStatement();
    }

    /**
     * Retrieves the value of the designated column in the current row as an {@code Object}, using the given
     * type map for custom mapping of the SQL structured or distinct type. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param map a {@code java.util.Map} object that contains the mapping from SQL type names to classes in the Java programming language
     * @return an {@code Object} in the Java programming language representing the SQL value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        return delegate.getObject(columnIndex, map);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link Ref} object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return a {@link Ref} object representing an SQL REF value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Ref getRef(int columnIndex) throws SQLException {
        return delegate.getRef(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link Blob} object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return a {@link Blob} object representing the SQL BLOB value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Blob getBlob(int columnIndex) throws SQLException {
        return delegate.getBlob(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link Clob} object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return a {@link Clob} object representing the SQL CLOB value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Clob getClob(int columnIndex) throws SQLException {
        return delegate.getClob(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as an {@link Array} object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return an {@link Array} object representing the SQL ARRAY value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Array getArray(int columnIndex) throws SQLException {
        return delegate.getArray(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as an {@code Object}, using the given
     * type map for custom mapping of the SQL structured or distinct type. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param map a {@code java.util.Map} object that contains the mapping from SQL type names to classes in the Java programming language
     * @return an {@code Object} in the Java programming language representing the SQL value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        return delegate.getObject(columnLabel, map);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link Ref} object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return a {@link Ref} object representing an SQL REF value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Ref getRef(String columnLabel) throws SQLException {
        return delegate.getRef(columnLabel);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link Blob} object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return a {@link Blob} object representing the SQL BLOB value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Blob getBlob(String columnLabel) throws SQLException {
        return delegate.getBlob(columnLabel);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link Clob} object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return a {@link Clob} object representing the SQL CLOB value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Clob getClob(String columnLabel) throws SQLException {
        return delegate.getClob(columnLabel);
    }

    /**
     * Retrieves the value of the designated column in the current row as an {@link Array} object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return an {@link Array} object representing the SQL ARRAY value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Array getArray(String columnLabel) throws SQLException {
        return delegate.getArray(columnLabel);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link Date} object, using the
     * given {@link Calendar} object to construct an appropriate millisecond value for the date.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param cal the {@code java.util.Calendar} object to use in constructing the date
     * @return the column value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Date getDate(int columnIndex, Calendar cal) throws SQLException {
        return delegate.getDate(columnIndex, cal);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link Date} object, using the
     * given {@link Calendar} object to construct an appropriate millisecond value for the date.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param cal the {@code java.util.Calendar} object to use in constructing the date
     * @return the column value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Date getDate(String columnLabel, Calendar cal) throws SQLException {
        return delegate.getDate(columnLabel, cal);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link Time} object, using the
     * given {@link Calendar} object to construct an appropriate millisecond value for the time.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param cal the {@code java.util.Calendar} object to use in constructing the time
     * @return the column value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Time getTime(int columnIndex, Calendar cal) throws SQLException {
        return delegate.getTime(columnIndex, cal);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link Time} object, using the
     * given {@link Calendar} object to construct an appropriate millisecond value for the time.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param cal the {@code java.util.Calendar} object to use in constructing the time
     * @return the column value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Time getTime(String columnLabel, Calendar cal) throws SQLException {
        return delegate.getTime(columnLabel, cal);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link Timestamp} object, using the
     * given {@link Calendar} object to construct an appropriate millisecond value for the timestamp.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param cal the {@code java.util.Calendar} object to use in constructing the timestamp
     * @return the column value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
        return delegate.getTimestamp(columnIndex, cal);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link Timestamp} object, using the
     * given {@link Calendar} object to construct an appropriate millisecond value for the timestamp.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param cal the {@code java.util.Calendar} object to use in constructing the timestamp
     * @return the column value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
        return delegate.getTimestamp(columnLabel, cal);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link URL} object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value as a {@link URL} object; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; this method is called on a closed result set or if a URL is malformed
     */
    @Override
    public URL getURL(int columnIndex) throws SQLException {
        return delegate.getURL(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link URL} object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return the column value as a {@link URL} object; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; this method is called on a closed result set or if a URL is malformed
     */
    @Override
    public URL getURL(String columnLabel) throws SQLException {
        return delegate.getURL(columnLabel);
    }

    /**
     * Updates the designated column with a {@link Ref} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateRef(int columnIndex, Ref x) throws SQLException {
        delegate.updateRef(columnIndex, x);
    }

    /**
     * Updates the designated column with a {@link Ref} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateRef(String columnLabel, Ref x) throws SQLException {
        delegate.updateRef(columnLabel, x);
    }

    /**
     * Updates the designated column with a {@link Blob} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateBlob(int columnIndex, Blob x) throws SQLException {
        delegate.updateBlob(columnIndex, x);
    }

    /**
     * Updates the designated column with a {@link Blob} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateBlob(String columnLabel, Blob x) throws SQLException {
        delegate.updateBlob(columnLabel, x);
    }

    /**
     * Updates the designated column with a {@link Clob} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateClob(int columnIndex, Clob x) throws SQLException {
        delegate.updateClob(columnIndex, x);
    }

    /**
     * Updates the designated column with a {@link Clob} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateClob(String columnLabel, Clob x) throws SQLException {
        delegate.updateClob(columnLabel, x);
    }

    /**
     * Updates the designated column with an {@link Array} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateArray(int columnIndex, Array x) throws SQLException {
        delegate.updateArray(columnIndex, x);
    }

    /**
     * Updates the designated column with an {@link Array} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateArray(String columnLabel, Array x) throws SQLException {
        delegate.updateArray(columnLabel, x);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link RowId} object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value as a {@link RowId} object; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public RowId getRowId(int columnIndex) throws SQLException {
        return delegate.getRowId(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link RowId} object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return the column value as a {@link RowId} object; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public RowId getRowId(String columnLabel) throws SQLException {
        return delegate.getRowId(columnLabel);
    }

    /**
     * Updates the designated column with a {@link RowId} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateRowId(int columnIndex, RowId x) throws SQLException {
        delegate.updateRowId(columnIndex, x);
    }

    /**
     * Updates the designated column with a {@link RowId} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateRowId(String columnLabel, RowId x) throws SQLException {
        delegate.updateRowId(columnLabel, x);
    }

    /**
     * Retrieves the holdability of this ResultSet object. Delegates to the underlying {@link ResultSet}.
     *
     * @return either {@code ResultSet.HOLD_CURSORS_OVER_COMMIT} or {@code ResultSet.CLOSE_CURSORS_AT_COMMIT}
     * @throws SQLException if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public int getHoldability() throws SQLException {
        return delegate.getHoldability();
    }

    /**
     * Retrieves whether this ResultSet object has been closed. Delegates to the underlying {@link ResultSet}.
     *
     * @return {@code true} if this ResultSet object is closed; {@code false} if it is still open
     * @throws SQLException if a database access error occurs
     */
    @Override
    public boolean isClosed() throws SQLException {
        return delegate.isClosed();
    }

    /**
     * Updates the designated column with a {@code String} value, intended for an NCHAR, NVARCHAR or LONGNVARCHAR
     * column. The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param nString the new column value
     * @throws SQLException if the columnIndex is not valid; if the driver does not support national character sets; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateNString(int columnIndex, String nString) throws SQLException {
        delegate.updateNString(columnIndex, nString);
    }

    /**
     * Updates the designated column with a {@code String} value, intended for an NCHAR, NVARCHAR or LONGNVARCHAR
     * column. The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param nString the new column value
     * @throws SQLException if the columnLabel is not valid; if the driver does not support national character sets; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateNString(String columnLabel, String nString) throws SQLException {
        delegate.updateNString(columnLabel, nString);
    }

    /**
     * Updates the designated column with an {@link NClob} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param nClob the new column value
     * @throws SQLException if the columnIndex is not valid; if the driver does not support national character sets; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
        delegate.updateNClob(columnIndex, nClob);
    }

    /**
     * Updates the designated column with an {@link NClob} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param nClob the new column value
     * @throws SQLException if the columnLabel is not valid; if the driver does not support national character sets; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
        delegate.updateNClob(columnLabel, nClob);
    }

    /**
     * Retrieves the value of the designated column in the current row as an {@link NClob} object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return an {@link NClob} object representing the SQL NCLOB value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnIndex is not valid; if the driver does not support national character sets; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public NClob getNClob(int columnIndex) throws SQLException {
        return delegate.getNClob(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as an {@link NClob} object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return an {@link NClob} object representing the SQL NCLOB value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnLabel is not valid; if the driver does not support national character sets; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public NClob getNClob(String columnLabel) throws SQLException {
        return delegate.getNClob(columnLabel);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link SQLXML} object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return a {@link SQLXML} object that maps an SQL XML value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public SQLXML getSQLXML(int columnIndex) throws SQLException {
        return delegate.getSQLXML(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link SQLXML} object.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return a {@link SQLXML} object that maps an SQL XML value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public SQLXML getSQLXML(String columnLabel) throws SQLException {
        return delegate.getSQLXML(columnLabel);
    }

    /**
     * Updates the designated column with a {@link SQLXML} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param xmlObject the new column value
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; this method is called on a closed result set; the {@link SQLXML} object's {@code getCharacterStream} or {@code getBinaryStream} method was called or has been closed; an error occurs processing the XML value or the result set concurrency is {@code CONCUR_READ_ONLY}
     */
    @Override
    public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
        delegate.updateSQLXML(columnIndex, xmlObject);
    }

    /**
     * Updates the designated column with a {@link SQLXML} value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param xmlObject the new column value
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; this method is called on a closed result set; the {@link SQLXML} object's {@code getCharacterStream} or {@code getBinaryStream} method was called or has been closed; an error occurs processing the XML value or the result set concurrency is {@code CONCUR_READ_ONLY}
     */
    @Override
    public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
        delegate.updateSQLXML(columnLabel, xmlObject);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@code String}, intended for
     * NCHAR, NVARCHAR and LONGNVARCHAR columns. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public String getNString(int columnIndex) throws SQLException {
        return delegate.getNString(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@code String}, intended for
     * NCHAR, NVARCHAR and LONGNVARCHAR columns. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return the column value; if the value is SQL NULL, the value returned is {@code null}
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public String getNString(String columnLabel) throws SQLException {
        return delegate.getNString(columnLabel);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link Reader} of characters,
     * intended for NCHAR, NVARCHAR and LONGNVARCHAR columns. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return a {@link Reader} that delivers the column value; if the value is SQL NULL, the value returned is {@code null} in the Java programming language
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        return delegate.getNCharacterStream(columnIndex);
    }

    /**
     * Retrieves the value of the designated column in the current row as a {@link Reader} of characters,
     * intended for NCHAR, NVARCHAR and LONGNVARCHAR columns. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @return a {@link Reader} that delivers the column value; if the value is SQL NULL, the value returned is {@code null} in the Java programming language
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs or this method is called on a closed result set
     */
    @Override
    public Reader getNCharacterStream(String columnLabel) throws SQLException {
        return delegate.getNCharacterStream(columnLabel);
    }

    /**
     * Updates the designated column with a character stream value, which will have the specified number of
     * characters, intended for an NCHAR, NVARCHAR or LONGNVARCHAR column. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @param length the length of the stream
     * @throws SQLException if the columnIndex is not valid; if the driver does not support national character sets; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        delegate.updateNCharacterStream(columnIndex, x, length);
    }

    /**
     * Updates the designated column with a character stream value, which will have the specified number of
     * characters, intended for an NCHAR, NVARCHAR or LONGNVARCHAR column. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param reader the {@link Reader} object containing the new column value
     * @param length the length of the stream
     * @throws SQLException if the columnLabel is not valid; if the driver does not support national character sets; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        delegate.updateNCharacterStream(columnLabel, reader, length);
    }

    /**
     * Updates the designated column with an ASCII stream value, which will have the specified number of bytes.
     * The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @param length the length of the stream
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
        delegate.updateAsciiStream(columnIndex, x, length);
    }

    /**
     * Updates the designated column with a binary stream value, which will have the specified number of bytes.
     * The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @param length the length of the stream
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
        delegate.updateBinaryStream(columnIndex, x, length);
    }

    /**
     * Updates the designated column with a character stream value, which will have the specified number of characters.
     * The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @param length the length of the stream
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        delegate.updateCharacterStream(columnIndex, x, length);
    }

    /**
     * Updates the designated column with an ASCII stream value, which will have the specified number of bytes.
     * The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @param length the length of the stream
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {
        delegate.updateAsciiStream(columnLabel, x, length);
    }

    /**
     * Updates the designated column with a binary stream value, which will have the specified number of bytes.
     * The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @param length the length of the stream
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {
        delegate.updateBinaryStream(columnLabel, x, length);
    }

    /**
     * Updates the designated column with a character stream value, which will have the specified number of characters.
     * The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param reader the {@link Reader} object containing the new column value
     * @param length the length of the stream
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        delegate.updateCharacterStream(columnLabel, reader, length);
    }

    /**
     * Updates the designated column using the given input stream, which will have the specified number of bytes.
     * The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param inputStream an object that contains the data to set the parameter value to
     * @param length the number of bytes in the parameter data
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException {
        delegate.updateBlob(columnIndex, inputStream, length);
    }

    /**
     * Updates the designated column using the given input stream, which will have the specified number of bytes.
     * The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param inputStream an object that contains the data to set the parameter value to
     * @param length the number of bytes in the parameter data
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException {
        delegate.updateBlob(columnLabel, inputStream, length);
    }

    /**
     * Updates the designated column using the given {@link Reader} object, which is the given number of characters long.
     * The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param reader an object that contains the data to set the parameter value to
     * @param length the number of characters in the parameter data
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
        delegate.updateClob(columnIndex, reader, length);
    }

    /**
     * Updates the designated column using the given {@link Reader} object, which is the given number of characters long.
     * The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param reader an object that contains the data to set the parameter value to
     * @param length the number of characters in the parameter data
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
        delegate.updateClob(columnLabel, reader, length);
    }

    /**
     * Updates the designated column using the given {@link Reader} object, which is the given number of characters long.
     * The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param reader an object that contains the data to set the parameter value to
     * @param length the number of characters in the parameter data
     * @throws SQLException if the columnIndex is not valid; if the driver does not support national character sets; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
        delegate.updateNClob(columnIndex, reader, length);
    }

    /**
     * Updates the designated column using the given {@link Reader} object, which is the given number of characters long.
     * The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param reader an object that contains the data to set the parameter value to
     * @param length the number of characters in the parameter data
     * @throws SQLException if the columnLabel is not valid; if the driver does not support national character sets; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
        delegate.updateNClob(columnLabel, reader, length);
    }

    /**
     * Updates the designated column with a character stream value, intended for an NCHAR, NVARCHAR or LONGNVARCHAR
     * column. The driver does the necessary conversion from Java character format to the national character set in
     * the database. The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @throws SQLException if the columnIndex is not valid; if the driver does not support national character sets; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
        delegate.updateNCharacterStream(columnIndex, x);
    }

    /**
     * Updates the designated column with a character stream value, intended for an NCHAR, NVARCHAR or LONGNVARCHAR
     * column. The driver does the necessary conversion from Java character format to the national character set in
     * the database. The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param reader the {@link Reader} object containing the new column value
     * @throws SQLException if the columnLabel is not valid; if the driver does not support national character sets; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
        delegate.updateNCharacterStream(columnLabel, reader);
    }

    /**
     * Updates the designated column with an ASCII stream value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
        delegate.updateAsciiStream(columnIndex, x);
    }

    /**
     * Updates the designated column with a binary stream value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
        delegate.updateBinaryStream(columnIndex, x);
    }

    /**
     * Updates the designated column with a character stream value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
        delegate.updateCharacterStream(columnIndex, x);
    }

    /**
     * Updates the designated column with an ASCII stream value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
        delegate.updateAsciiStream(columnLabel, x);
    }

    /**
     * Updates the designated column with a binary stream value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param x the new column value
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
        delegate.updateBinaryStream(columnLabel, x);
    }

    /**
     * Updates the designated column with a character stream value. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param reader the {@link Reader} object containing the new column value
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
        delegate.updateCharacterStream(columnLabel, reader);
    }

    /**
     * Updates the designated column using the given input stream. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param inputStream an object that contains the data to set the parameter value to
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
        delegate.updateBlob(columnIndex, inputStream);
    }

    /**
     * Updates the designated column using the given input stream. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param inputStream an object that contains the data to set the parameter value to
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
        delegate.updateBlob(columnLabel, inputStream);
    }

    /**
     * Updates the designated column using the given {@link Reader} object. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param reader an object that contains the data to set the parameter value to
     * @throws SQLException if the columnIndex is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateClob(int columnIndex, Reader reader) throws SQLException {
        delegate.updateClob(columnIndex, reader);
    }

    /**
     * Updates the designated column using the given {@link Reader} object. The database is not changed until
     * {@link #updateRow} or {@link #insertRow} is called. Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param reader an object that contains the data to set the parameter value to
     * @throws SQLException if the columnLabel is not valid; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateClob(String columnLabel, Reader reader) throws SQLException {
        delegate.updateClob(columnLabel, reader);
    }

    /**
     * Updates the designated column using the given {@link Reader} object, intended for an NCHAR, NVARCHAR or
     * LONGNVARCHAR column. The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param reader an object that contains the data to set the parameter value to
     * @throws SQLException if the columnIndex is not valid; if the driver does not support national character sets; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateNClob(int columnIndex, Reader reader) throws SQLException {
        delegate.updateNClob(columnIndex, reader);
    }

    /**
     * Updates the designated column using the given {@link Reader} object, intended for an NCHAR, NVARCHAR or
     * LONGNVARCHAR column. The database is not changed until {@link #updateRow} or {@link #insertRow} is called.
     * Delegates to the underlying {@link ResultSet}.
     *
     * @param columnLabel the label for the column specified with the SQL AS clause. If the SQL AS clause was not specified, then the label is the name of the column
     * @param reader an object that contains the data to set the parameter value to
     * @throws SQLException if the columnLabel is not valid; if the driver does not support national character sets; if a database access error occurs; the result set concurrency is {@code CONCUR_READ_ONLY} or this method is called on a closed result set
     */
    @Override
    public void updateNClob(String columnLabel, Reader reader) throws SQLException {
        delegate.updateNClob(columnLabel, reader);
    }

}
