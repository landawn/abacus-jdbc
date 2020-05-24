/*
 * Copyright (c) 2015, Haiyang Li.
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

import static com.landawn.abacus.util.IOUtil.DEFAULT_QUEUE_SIZE_FOR_ROW_PARSER;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.landawn.abacus.DataSet;
import com.landawn.abacus.exception.UncheckedIOException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.type.Type;

/**
 * The Class JdbcUtil.
 *
 * @author Haiyang Li
 * @see {@link com.landawn.abacus.condition.ConditionFactory}
 * @see {@link com.landawn.abacus.condition.ConditionFactory.CF}
 * @see {@link com.landawn.abacus.annotation.ReadOnly}
 * @see {@link com.landawn.abacus.annotation.ReadOnlyId}
 * @see {@link com.landawn.abacus.annotation.NonUpdatable}
 * @see {@link com.landawn.abacus.annotation.Transient}
 * @see {@link com.landawn.abacus.annotation.Table}
 * @see {@link com.landawn.abacus.annotation.Column}
 * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html</a>
 * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html</a>
 * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html</a>
 * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html">http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html</a>
 * @since 0.8
 */
public final class JdbcUtils {
    private JdbcUtils() {
        // singleton.
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final Connection conn, final String insertSQL) throws UncheckedSQLException {
        return importData(dataset, dataset.columnNameList(), conn, insertSQL);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param selectColumnNames
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final Collection<String> selectColumnNames, final Connection conn, final String insertSQL)
            throws UncheckedSQLException {
        return importData(dataset, selectColumnNames, 0, dataset.size(), conn, insertSQL);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final Collection<String> selectColumnNames, final int offset, final int count, final Connection conn,
            final String insertSQL) throws UncheckedSQLException {
        return importData(dataset, selectColumnNames, offset, count, conn, insertSQL, 200, 0);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param batchSize
     * @param batchInterval
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final Collection<String> selectColumnNames, final int offset, final int count, final Connection conn,
            final String insertSQL, final int batchSize, final int batchInterval) throws UncheckedSQLException {
        return importData(dataset, selectColumnNames, offset, count, Fn.alwaysTrue(), conn, insertSQL, batchSize, batchInterval);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param <E>
     * @param dataset
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param filter
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param batchSize
     * @param batchInterval
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> int importData(final DataSet dataset, final Collection<String> selectColumnNames, final int offset, final int count,
            final Throwables.Predicate<? super Object[], E> filter, final Connection conn, final String insertSQL, final int batchSize, final int batchInterval)
            throws UncheckedSQLException, E {
        PreparedStatement stmt = null;

        try {
            stmt = JdbcUtil.prepareStatement(conn, insertSQL);

            return importData(dataset, selectColumnNames, offset, count, filter, stmt, batchSize, batchInterval);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final DataSet dataset, final Connection conn, final String insertSQL, final Map<String, ? extends Type> columnTypeMap)
            throws UncheckedSQLException {
        return importData(dataset, 0, dataset.size(), conn, insertSQL, columnTypeMap);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final DataSet dataset, final int offset, final int count, final Connection conn, final String insertSQL,
            final Map<String, ? extends Type> columnTypeMap) throws UncheckedSQLException {
        return importData(dataset, offset, count, conn, insertSQL, 200, 0, columnTypeMap);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param batchSize
     * @param batchInterval
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final DataSet dataset, final int offset, final int count, final Connection conn, final String insertSQL, final int batchSize,
            final int batchInterval, final Map<String, ? extends Type> columnTypeMap) throws UncheckedSQLException {
        return importData(dataset, offset, count, Fn.alwaysTrue(), conn, insertSQL, batchSize, batchInterval, columnTypeMap);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param <E>
     * @param dataset
     * @param offset
     * @param count
     * @param filter
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param batchSize
     * @param batchInterval
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    @SuppressWarnings("rawtypes")
    public static <E extends Exception> int importData(final DataSet dataset, final int offset, final int count,
            final Throwables.Predicate<? super Object[], E> filter, final Connection conn, final String insertSQL, final int batchSize, final int batchInterval,
            final Map<String, ? extends Type> columnTypeMap) throws UncheckedSQLException, E {
        PreparedStatement stmt = null;

        try {
            stmt = JdbcUtil.prepareStatement(conn, insertSQL);

            return importData(dataset, offset, count, filter, stmt, batchSize, batchInterval, columnTypeMap);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final Connection conn, final String insertSQL,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException {
        return importData(dataset, 0, dataset.size(), conn, insertSQL, stmtSetter);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final int offset, final int count, final Connection conn, final String insertSQL,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException {
        return importData(dataset, offset, count, conn, insertSQL, 200, 0, stmtSetter);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final int offset, final int count, final Connection conn, final String insertSQL, final int batchSize,
            final int batchInterval, final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException {
        return importData(dataset, offset, count, Fn.alwaysTrue(), conn, insertSQL, batchSize, batchInterval, stmtSetter);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param <E>
     * @param dataset
     * @param offset
     * @param count
     * @param filter
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> int importData(final DataSet dataset, final int offset, final int count,
            final Throwables.Predicate<? super Object[], E> filter, final Connection conn, final String insertSQL, final int batchSize, final int batchInterval,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException, E {
        PreparedStatement stmt = null;

        try {
            stmt = JdbcUtil.prepareStatement(conn, insertSQL);

            return importData(dataset, offset, count, filter, stmt, batchSize, batchInterval, stmtSetter);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final PreparedStatement stmt) throws UncheckedSQLException {
        return importData(dataset, dataset.columnNameList(), stmt);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param selectColumnNames
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final Collection<String> selectColumnNames, final PreparedStatement stmt) throws UncheckedSQLException {
        return importData(dataset, selectColumnNames, 0, dataset.size(), stmt);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final Collection<String> selectColumnNames, final int offset, final int count,
            final PreparedStatement stmt) throws UncheckedSQLException {
        return importData(dataset, selectColumnNames, offset, count, stmt, 200, 0);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param batchSize
     * @param batchInterval
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final Collection<String> selectColumnNames, final int offset, final int count,
            final PreparedStatement stmt, final int batchSize, final int batchInterval) throws UncheckedSQLException {
        return importData(dataset, selectColumnNames, offset, count, Fn.alwaysTrue(), stmt, batchSize, batchInterval);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param <E>
     * @param dataset
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param filter
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param batchSize
     * @param batchInterval
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> int importData(final DataSet dataset, final Collection<String> selectColumnNames, final int offset, final int count,
            final Throwables.Predicate<? super Object[], E> filter, final PreparedStatement stmt, final int batchSize, final int batchInterval)
            throws UncheckedSQLException, E {
        final Type<?> objType = N.typeOf(Object.class);
        final Map<String, Type<?>> columnTypeMap = new HashMap<>();

        for (String propName : selectColumnNames) {
            columnTypeMap.put(propName, objType);
        }

        return importData(dataset, offset, count, filter, stmt, batchSize, batchInterval, columnTypeMap);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final DataSet dataset, final PreparedStatement stmt, final Map<String, ? extends Type> columnTypeMap)
            throws UncheckedSQLException {
        return importData(dataset, 0, dataset.size(), stmt, columnTypeMap);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final DataSet dataset, final int offset, final int count, final PreparedStatement stmt,
            final Map<String, ? extends Type> columnTypeMap) throws UncheckedSQLException {
        return importData(dataset, offset, count, stmt, 200, 0, columnTypeMap);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param batchSize
     * @param batchInterval
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final DataSet dataset, final int offset, final int count, final PreparedStatement stmt, final int batchSize,
            final int batchInterval, final Map<String, ? extends Type> columnTypeMap) throws UncheckedSQLException {
        return importData(dataset, offset, count, Fn.alwaysTrue(), stmt, batchSize, batchInterval, columnTypeMap);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param <E>
     * @param dataset
     * @param offset
     * @param count
     * @param filter
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param batchSize
     * @param batchInterval
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    @SuppressWarnings("rawtypes")
    public static <E extends Exception> int importData(final DataSet dataset, final int offset, final int count,
            final Throwables.Predicate<? super Object[], E> filter, final PreparedStatement stmt, final int batchSize, final int batchInterval,
            final Map<String, ? extends Type> columnTypeMap) throws UncheckedSQLException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchInterval >= 0, "'batchSize'=%s must be greater than 0 and 'batchInterval'=%s can't be negative", batchSize,
                batchInterval);

        int result = 0;

        try {
            final int columnCount = columnTypeMap.size();
            final List<String> columnNameList = dataset.columnNameList();
            final int[] columnIndexes = new int[columnCount];
            final Type<Object>[] columnTypes = new Type[columnCount];
            final Set<String> columnNameSet = N.newHashSet(columnCount);

            int idx = 0;
            for (String columnName : columnNameList) {
                if (columnTypeMap.containsKey(columnName)) {
                    columnIndexes[idx] = dataset.getColumnIndex(columnName);
                    columnTypes[idx] = columnTypeMap.get(columnName);
                    columnNameSet.add(columnName);
                    idx++;
                }
            }

            if (columnNameSet.size() != columnTypeMap.size()) {
                final List<String> keys = new ArrayList<>(columnTypeMap.keySet());
                keys.removeAll(columnNameSet);
                throw new RuntimeException(keys + " are not included in titles: " + N.toString(columnNameList));
            }

            final Object[] row = filter == null ? null : new Object[columnCount];
            for (int i = offset, size = dataset.size(); result < count && i < size; i++) {
                dataset.absolute(i);

                if (filter == null) {
                    for (int j = 0; j < columnCount; j++) {
                        columnTypes[j].set(stmt, j + 1, dataset.get(columnIndexes[j]));
                    }
                } else {
                    for (int j = 0; j < columnCount; j++) {
                        row[j] = dataset.get(columnIndexes[j]);
                    }

                    if (filter.test(row) == false) {
                        continue;
                    }

                    for (int j = 0; j < columnCount; j++) {
                        columnTypes[j].set(stmt, j + 1, row[j]);
                    }
                }

                stmt.addBatch();

                if ((++result % batchSize) == 0) {
                    JdbcUtil.executeBatch(stmt);

                    if (batchInterval > 0) {
                        N.sleep(batchInterval);
                    }
                }
            }

            if ((result % batchSize) > 0) {
                JdbcUtil.executeBatch(stmt);
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }

        return result;
    }

    /**
     *
     * @param dataset
     * @param stmt
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final PreparedStatement stmt,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException {
        return importData(dataset, 0, dataset.size(), stmt, stmtSetter);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final int offset, final int count, final PreparedStatement stmt,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException {
        return importData(dataset, offset, count, stmt, 200, 0, stmtSetter);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int importData(final DataSet dataset, final int offset, final int count, final PreparedStatement stmt, final int batchSize,
            final int batchInterval, final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException {
        return importData(dataset, offset, count, Fn.alwaysTrue(), stmt, batchSize, batchInterval, stmtSetter);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param <E>
     * @param dataset
     * @param offset
     * @param count
     * @param filter
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> int importData(final DataSet dataset, final int offset, final int count,
            final Throwables.Predicate<? super Object[], E> filter, final PreparedStatement stmt, final int batchSize, final int batchInterval,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchInterval >= 0, "'batchSize'=%s must be greater than 0 and 'batchInterval'=%s can't be negative", batchSize,
                batchInterval);

        final int columnCount = dataset.columnNameList().size();
        final Object[] row = new Object[columnCount];
        int result = 0;

        try {
            for (int i = offset, size = dataset.size(); result < count && i < size; i++) {
                dataset.absolute(i);

                for (int j = 0; j < columnCount; j++) {
                    row[j] = dataset.get(j);
                }

                if (filter != null && filter.test(row) == false) {
                    continue;
                }

                stmtSetter.accept(stmt, row);

                stmt.addBatch();

                if ((++result % batchSize) == 0) {
                    JdbcUtil.executeBatch(stmt);

                    if (batchInterval > 0) {
                        N.sleep(batchInterval);
                    }
                }
            }

            if ((result % batchSize) > 0) {
                JdbcUtil.executeBatch(stmt);
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }

        return result;
    }

    /**
     *
     * @param <E>
     * @param file
     * @param conn
     * @param insertSQL
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final File file, final Connection conn, final String insertSQL,
            final Throwables.Function<String, Object[], E> func) throws UncheckedSQLException, E {
        return importData(file, 0, Long.MAX_VALUE, conn, insertSQL, 200, 0, func);
    }

    /**
     *
     * @param <E>
     * @param file
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL
     * @param batchSize
     * @param batchInterval
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final File file, final long offset, final long count, final Connection conn, final String insertSQL,
            final int batchSize, final int batchInterval, final Throwables.Function<String, Object[], E> func) throws UncheckedSQLException, E {
        PreparedStatement stmt = null;

        try {
            stmt = JdbcUtil.prepareStatement(conn, insertSQL);

            return importData(file, offset, count, stmt, batchSize, batchInterval, func);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     *
     * @param <E>
     * @param file
     * @param stmt
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final File file, final PreparedStatement stmt, final Throwables.Function<String, Object[], E> func)
            throws UncheckedSQLException, E {
        return importData(file, 0, Long.MAX_VALUE, stmt, 200, 0, func);
    }

    /**
     * Imports the data from file to database.
     *
     * @param <E>
     * @param file
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param func convert line to the parameters for record insert. Returns a <code>null</code> array to skip the line.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final File file, final long offset, final long count, final PreparedStatement stmt, final int batchSize,
            final int batchInterval, final Throwables.Function<String, Object[], E> func) throws UncheckedSQLException, E {
        Reader reader = null;

        try {
            reader = new FileReader(file);

            return importData(reader, offset, count, stmt, batchSize, batchInterval, func);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            IOUtil.close(reader);
        }
    }

    /**
     *
     * @param <E>
     * @param is
     * @param conn
     * @param insertSQL
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final InputStream is, final Connection conn, final String insertSQL,
            final Throwables.Function<String, Object[], E> func) throws UncheckedSQLException, E {
        return importData(is, 0, Long.MAX_VALUE, conn, insertSQL, 200, 0, func);
    }

    /**
     *
     * @param <E>
     * @param is
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL
     * @param batchSize
     * @param batchInterval
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final InputStream is, final long offset, final long count, final Connection conn,
            final String insertSQL, final int batchSize, final int batchInterval, final Throwables.Function<String, Object[], E> func)
            throws UncheckedSQLException, E {
        PreparedStatement stmt = null;

        try {
            stmt = JdbcUtil.prepareStatement(conn, insertSQL);

            return importData(is, offset, count, stmt, batchSize, batchInterval, func);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     *
     * @param <E>
     * @param is
     * @param stmt
     * @param func
     * @return
     * @throws E the e
     */
    public static <E extends Exception> long importData(final InputStream is, final PreparedStatement stmt, final Throwables.Function<String, Object[], E> func)
            throws E {
        return importData(is, 0, Long.MAX_VALUE, stmt, 200, 0, func);
    }

    /**
     * Imports the data from file to database.
     *
     * @param <E>
     * @param is
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param func convert line to the parameters for record insert. Returns a <code>null</code> array to skip the line.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final InputStream is, final long offset, final long count, final PreparedStatement stmt,
            final int batchSize, final int batchInterval, final Throwables.Function<String, Object[], E> func) throws UncheckedSQLException, E {
        final Reader reader = new InputStreamReader(is);

        return importData(reader, offset, count, stmt, batchSize, batchInterval, func);
    }

    /**
     *
     * @param <E>
     * @param reader
     * @param conn
     * @param insertSQL
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final Reader reader, final Connection conn, final String insertSQL,
            final Throwables.Function<String, Object[], E> func) throws UncheckedSQLException, E {
        return importData(reader, 0, Long.MAX_VALUE, conn, insertSQL, 200, 0, func);
    }

    /**
     *
     * @param <E>
     * @param reader
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL
     * @param batchSize
     * @param batchInterval
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final Reader reader, final long offset, final long count, final Connection conn, final String insertSQL,
            final int batchSize, final int batchInterval, final Throwables.Function<String, Object[], E> func) throws UncheckedSQLException, E {
        PreparedStatement stmt = null;

        try {
            stmt = JdbcUtil.prepareStatement(conn, insertSQL);

            return importData(reader, offset, count, stmt, batchSize, batchInterval, func);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     *
     * @param <E>
     * @param reader
     * @param stmt
     * @param func
     * @return
     * @throws E the e
     */
    public static <E extends Exception> long importData(final Reader reader, final PreparedStatement stmt, final Throwables.Function<String, Object[], E> func)
            throws E {
        return importData(reader, 0, Long.MAX_VALUE, stmt, 200, 0, func);
    }

    /**
     * Imports the data from file to database.
     *
     * @param <E>
     * @param reader
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param func convert line to the parameters for record insert. Returns a <code>null</code> array to skip the line.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> long importData(final Reader reader, long offset, final long count, final PreparedStatement stmt, final int batchSize,
            final int batchInterval, final Throwables.Function<String, Object[], E> func) throws UncheckedSQLException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchInterval >= 0, "'batchSize'=%s must be greater than 0 and 'batchInterval'=%s can't be negative", batchSize,
                batchInterval);

        long result = 0;
        final BufferedReader br = Objectory.createBufferedReader(reader);

        try {
            while (offset-- > 0 && br.readLine() != null) {
            }

            String line = null;
            Object[] row = null;

            while (result < count && (line = br.readLine()) != null) {
                row = func.apply(line);

                if (row == null) {
                    continue;
                }

                for (int i = 0, len = row.length; i < len; i++) {
                    stmt.setObject(i + 1, row[i]);
                }

                stmt.addBatch();

                if ((++result % batchSize) == 0) {
                    JdbcUtil.executeBatch(stmt);

                    if (batchInterval > 0) {
                        N.sleep(batchInterval);
                    }
                }
            }

            if ((result % batchSize) > 0) {
                JdbcUtil.executeBatch(stmt);
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            Objectory.recycle(br);
        }

        return result;
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param iter
     * @param conn
     * @param insertSQL
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <T, E extends Exception> long importData(final Iterator<T> iter, final Connection conn, final String insertSQL,
            final Throwables.Function<? super T, Object[], E> func) throws UncheckedSQLException, E {
        return importData(iter, 0, Long.MAX_VALUE, conn, insertSQL, 200, 0, func);
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param iter
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL
     * @param batchSize
     * @param batchInterval
     * @param func
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <T, E extends Exception> long importData(final Iterator<T> iter, final long offset, final long count, final Connection conn,
            final String insertSQL, final int batchSize, final int batchInterval, final Throwables.Function<? super T, Object[], E> func)
            throws UncheckedSQLException, E {
        PreparedStatement stmt = null;

        try {
            stmt = JdbcUtil.prepareStatement(conn, insertSQL);

            return importData(iter, offset, count, stmt, batchSize, batchInterval, func);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param iter
     * @param stmt
     * @param func
     * @return
     * @throws E the e
     */
    public static <T, E extends Exception> long importData(final Iterator<T> iter, final PreparedStatement stmt,
            final Throwables.Function<? super T, Object[], E> func) throws E {
        return importData(iter, 0, Long.MAX_VALUE, stmt, 200, 0, func);
    }

    /**
     * Imports the data from Iterator to database.
     *
     * @param <T>
     * @param <E>
     * @param iter
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param func convert element to the parameters for record insert. Returns a <code>null</code> array to skip the line.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <T, E extends Exception> long importData(final Iterator<T> iter, long offset, final long count, final PreparedStatement stmt,
            final int batchSize, final int batchInterval, final Throwables.Function<? super T, Object[], E> func) throws UncheckedSQLException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchInterval >= 0, "'batchSize'=%s must be greater than 0 and 'batchInterval'=%s can't be negative", batchSize,
                batchInterval);

        long result = 0;

        try {
            while (offset-- > 0 && iter.hasNext()) {
                iter.next();
            }

            Object[] row = null;

            while (result < count && iter.hasNext()) {
                row = func.apply(iter.next());

                if (row == null) {
                    continue;
                }

                for (int i = 0, len = row.length; i < len; i++) {
                    stmt.setObject(i + 1, row[i]);
                }

                stmt.addBatch();

                if ((++result % batchSize) == 0) {
                    JdbcUtil.executeBatch(stmt);

                    if (batchInterval > 0) {
                        N.sleep(batchInterval);
                    }
                }
            }

            if ((result % batchSize) > 0) {
                JdbcUtil.executeBatch(stmt);
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }

        return result;
    }

    /**
     *
     * @param <T>
     * @param iter
     * @param conn
     * @param insertSQL
     * @param stmtSetter
     * @return
     */
    public static <T> long importData(final Iterator<T> iter, final Connection conn, final String insertSQL,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super T> stmtSetter) {
        return importData(iter, 0, Long.MAX_VALUE, conn, insertSQL, 200, 0, stmtSetter);
    }

    /**
     *
     * @param <T>
     * @param iter
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     */
    public static <T> long importData(final Iterator<T> iter, final long offset, final long count, final Connection conn, final String insertSQL,
            final int batchSize, final int batchInterval, final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super T> stmtSetter) {
        return importData(iter, offset, count, Fn.alwaysTrue(), conn, insertSQL, batchSize, batchInterval, stmtSetter);
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param iter
     * @param offset
     * @param count
     * @param filter
     * @param conn
     * @param insertSQL
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <T, E extends Exception> long importData(final Iterator<T> iter, final long offset, final long count,
            final Throwables.Predicate<? super T, E> filter, final Connection conn, final String insertSQL, final int batchSize, final int batchInterval,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super T> stmtSetter) throws UncheckedSQLException, E {
        PreparedStatement stmt = null;

        try {
            stmt = JdbcUtil.prepareStatement(conn, insertSQL);

            return importData(iter, offset, count, filter, stmt, batchSize, batchInterval, stmtSetter);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     *
     * @param <T>
     * @param iter
     * @param stmt
     * @param stmtSetter
     * @return
     */
    public static <T> long importData(final Iterator<T> iter, final PreparedStatement stmt,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super T> stmtSetter) {
        return importData(iter, 0, Long.MAX_VALUE, stmt, 200, 0, stmtSetter);
    }

    /**
     *
     * @param <T>
     * @param iter
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     */
    public static <T> long importData(final Iterator<T> iter, long offset, final long count, final PreparedStatement stmt, final int batchSize,
            final int batchInterval, final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super T> stmtSetter) {
        return importData(iter, offset, count, Fn.alwaysTrue(), stmt, batchSize, batchInterval, stmtSetter);
    }

    /**
     * Imports the data from Iterator to database.
     *
     * @param <T>
     * @param <E>
     * @param iter
     * @param offset
     * @param count
     * @param filter
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <T, E extends Exception> long importData(final Iterator<T> iter, long offset, final long count,
            final Throwables.Predicate<? super T, E> filter, final PreparedStatement stmt, final int batchSize, final int batchInterval,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super T> stmtSetter) throws UncheckedSQLException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchInterval >= 0, "'batchSize'=%s must be greater than 0 and 'batchInterval'=%s can't be negative", batchSize,
                batchInterval);

        long result = 0;

        try {
            while (offset-- > 0 && iter.hasNext()) {
                iter.next();
            }
            T next = null;
            while (result < count && iter.hasNext()) {
                next = iter.next();

                if (filter != null && filter.test(next) == false) {
                    continue;
                }

                stmtSetter.accept(stmt, next);
                stmt.addBatch();

                if ((++result % batchSize) == 0) {
                    JdbcUtil.executeBatch(stmt);

                    if (batchInterval > 0) {
                        N.sleep(batchInterval);
                    }
                }
            }

            if ((result % batchSize) > 0) {
                JdbcUtil.executeBatch(stmt);
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }

        return result;
    }

    /**
     *
     * @param <E>
     * @param conn
     * @param sql
     * @param rowParser
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> void parse(final Connection conn, final String sql, final Throwables.Consumer<Object[], E> rowParser)
            throws UncheckedSQLException, E {
        parse(conn, sql, rowParser, Fn.emptyAction());
    }

    /**
     *
     * @param <E>
     * @param <E2>
     * @param conn
     * @param sql
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final Connection conn, final String sql,
            final Throwables.Consumer<Object[], E> rowParser, final Throwables.Runnable<E2> onComplete) throws UncheckedSQLException, E, E2 {
        parse(conn, sql, 0, Long.MAX_VALUE, rowParser, onComplete);
    }

    /**
     *
     * @param <E>
     * @param conn
     * @param sql
     * @param offset
     * @param count
     * @param rowParser
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> void parse(final Connection conn, final String sql, final long offset, final long count,
            final Throwables.Consumer<Object[], E> rowParser) throws UncheckedSQLException, E {
        parse(conn, sql, offset, count, rowParser, Fn.emptyAction());
    }

    /**
     *
     * @param <E>
     * @param <E2>
     * @param conn
     * @param sql
     * @param offset
     * @param count
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final Connection conn, final String sql, final long offset, final long count,
            final Throwables.Consumer<Object[], E> rowParser, final Throwables.Runnable<E2> onComplete) throws UncheckedSQLException, E, E2 {
        parse(conn, sql, offset, count, 0, 0, rowParser, onComplete);
    }

    /**
     *
     * @param <E>
     * @param conn
     * @param sql
     * @param offset
     * @param count
     * @param processThreadNum
     * @param queueSize
     * @param rowParser
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> void parse(final Connection conn, final String sql, final long offset, final long count, final int processThreadNum,
            final int queueSize, final Throwables.Consumer<Object[], E> rowParser) throws UncheckedSQLException, E {
        parse(conn, sql, offset, count, processThreadNum, queueSize, rowParser, Fn.emptyAction());
    }

    /**
     * Parse the ResultSet obtained by executing query with the specified Connection and sql.
     *
     * @param <E>
     * @param <E2>
     * @param conn
     * @param sql
     * @param offset
     * @param count
     * @param processThreadNum new threads started to parse/process the lines/records
     * @param queueSize size of queue to save the processing records/lines loaded from source data. Default size is 1024.
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final Connection conn, final String sql, final long offset, final long count,
            final int processThreadNum, final int queueSize, final Throwables.Consumer<Object[], E> rowParser, final Throwables.Runnable<E2> onComplete)
            throws UncheckedSQLException, E, E2 {
        PreparedStatement stmt = null;
        try {
            stmt = JdbcUtil.prepareStatement(conn, sql);

            stmt.setFetchDirection(ResultSet.FETCH_FORWARD);

            stmt.setFetchSize(200);

            parse(stmt, offset, count, processThreadNum, queueSize, rowParser, onComplete);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     *
     * @param <E>
     * @param stmt
     * @param rowParser
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> void parse(final PreparedStatement stmt, final Throwables.Consumer<Object[], E> rowParser)
            throws UncheckedSQLException, E {
        parse(stmt, rowParser, Fn.emptyAction());
    }

    /**
     *
     * @param <E>
     * @param <E2>
     * @param stmt
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final PreparedStatement stmt, final Throwables.Consumer<Object[], E> rowParser,
            final Throwables.Runnable<E2> onComplete) throws UncheckedSQLException, E, E2 {
        parse(stmt, 0, Long.MAX_VALUE, rowParser, onComplete);
    }

    /**
     *
     * @param <E>
     * @param stmt
     * @param offset
     * @param count
     * @param rowParser
     * @throws E the e
     */
    public static <E extends Exception> void parse(final PreparedStatement stmt, final long offset, final long count,
            final Throwables.Consumer<Object[], E> rowParser) throws E {
        parse(stmt, offset, count, rowParser, Fn.emptyAction());
    }

    /**
     *
     * @param <E>
     * @param <E2>
     * @param stmt
     * @param offset
     * @param count
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final PreparedStatement stmt, final long offset, final long count,
            final Throwables.Consumer<Object[], E> rowParser, final Throwables.Runnable<E2> onComplete) throws UncheckedSQLException, E, E2 {
        parse(stmt, offset, count, 0, 0, rowParser, onComplete);
    }

    /**
     *
     * @param <E>
     * @param stmt
     * @param offset
     * @param count
     * @param processThreadNum
     * @param queueSize
     * @param rowParser
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> void parse(final PreparedStatement stmt, final long offset, final long count, final int processThreadNum,
            final int queueSize, final Throwables.Consumer<Object[], E> rowParser) throws UncheckedSQLException, E {
        parse(stmt, offset, count, processThreadNum, queueSize, rowParser, Fn.emptyAction());
    }

    /**
     * Parse the ResultSet obtained by executing query with the specified PreparedStatement.
     *
     * @param <E>
     * @param <E2>
     * @param stmt
     * @param offset
     * @param count
     * @param processThreadNum new threads started to parse/process the lines/records
     * @param queueSize size of queue to save the processing records/lines loaded from source data. Default size is 1024.
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final PreparedStatement stmt, final long offset, final long count,
            final int processThreadNum, final int queueSize, final Throwables.Consumer<Object[], E> rowParser, final Throwables.Runnable<E2> onComplete)
            throws UncheckedSQLException, E, E2 {
        ResultSet rs = null;

        try {
            rs = JdbcUtil.executeQuery(stmt);

            parse(rs, offset, count, processThreadNum, queueSize, rowParser, onComplete);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(rs);
        }
    }

    /**
     *
     * @param <E>
     * @param rs
     * @param rowParser
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> void parse(final ResultSet rs, final Throwables.Consumer<Object[], E> rowParser) throws UncheckedSQLException, E {
        parse(rs, rowParser, Fn.emptyAction());
    }

    /**
     *
     * @param <E>
     * @param <E2>
     * @param rs
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final ResultSet rs, final Throwables.Consumer<Object[], E> rowParser,
            final Throwables.Runnable<E2> onComplete) throws UncheckedSQLException, E, E2 {
        parse(rs, 0, Long.MAX_VALUE, rowParser, onComplete);
    }

    /**
     *
     * @param <E>
     * @param rs
     * @param offset
     * @param count
     * @param rowParser
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> void parse(final ResultSet rs, long offset, long count, final Throwables.Consumer<Object[], E> rowParser)
            throws UncheckedSQLException, E {
        parse(rs, offset, count, rowParser, Fn.emptyAction());
    }

    /**
     *
     * @param <E>
     * @param <E2>
     * @param rs
     * @param offset
     * @param count
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final ResultSet rs, long offset, long count,
            final Throwables.Consumer<Object[], E> rowParser, final Throwables.Runnable<E2> onComplete) throws UncheckedSQLException, E, E2 {
        parse(rs, offset, count, 0, 0, rowParser, onComplete);
    }

    /**
     *
     * @param <E>
     * @param rs
     * @param offset
     * @param count
     * @param processThreadNum
     * @param queueSize
     * @param rowParser
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     */
    public static <E extends Exception> void parse(final ResultSet rs, long offset, long count, final int processThreadNum, final int queueSize,
            final Throwables.Consumer<Object[], E> rowParser) throws UncheckedSQLException, E {
        parse(rs, offset, count, processThreadNum, queueSize, rowParser, Fn.emptyAction());
    }

    /**
     * Parse the ResultSet.
     *
     * @param <E>
     * @param <E2>
     * @param rs
     * @param offset
     * @param count
     * @param processThreadNum new threads started to parse/process the lines/records
     * @param queueSize size of queue to save the processing records/lines loaded from source data. Default size is 1024.
     * @param rowParser
     * @param onComplete
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws E the e
     * @throws E2 the e2
     */
    public static <E extends Exception, E2 extends Exception> void parse(final ResultSet rs, long offset, long count, final int processThreadNum,
            final int queueSize, final Throwables.Consumer<Object[], E> rowParser, final Throwables.Runnable<E2> onComplete)
            throws UncheckedSQLException, E, E2 {

        final Iterator<Object[]> iter = new ObjIterator<Object[]>() {
            private final JdbcUtil.BiRowMapper<Object[]> biFunc = JdbcUtil.BiRowMapper.TO_ARRAY;
            private List<String> columnLabels = null;
            private boolean hasNext;

            @Override
            public boolean hasNext() {
                if (hasNext == false) {
                    try {
                        hasNext = rs.next();
                    } catch (SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                }

                return hasNext;
            }

            @Override
            public Object[] next() {
                if (hasNext() == false) {
                    throw new NoSuchElementException();
                }

                hasNext = false;

                try {
                    if (columnLabels == null) {
                        columnLabels = JdbcUtil.getColumnLabelList(rs);
                    }

                    return biFunc.apply(rs, columnLabels);
                } catch (SQLException e) {
                    throw new UncheckedSQLException(e);
                }
            }
        };

        Iterables.parse(iter, offset, count, processThreadNum, queueSize, rowParser, onComplete);
    }

    /**
     *
     * @param sourceConn
     * @param selectSql
     * @param targetConn
     * @param insertSql
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static long copy(final Connection sourceConn, final String selectSql, final Connection targetConn, final String insertSql)
            throws UncheckedSQLException {
        return copy(sourceConn, selectSql, 200, 0, Integer.MAX_VALUE, targetConn, insertSql, JdbcUtil.DEFAULT_STMT_SETTER, 200, 0, false);
    }

    /**
     *
     * @param sourceConn
     * @param selectSql
     * @param fetchSize
     * @param offset
     * @param count
     * @param targetConn
     * @param insertSql
     * @param stmtSetter
     * @param batchSize
     * @param batchInterval
     * @param inParallel do the read and write in separated threads.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static long copy(final Connection sourceConn, final String selectSql, final int fetchSize, final long offset, final long count,
            final Connection targetConn, final String insertSql, final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter,
            final int batchSize, final int batchInterval, final boolean inParallel) throws UncheckedSQLException {
        PreparedStatement selectStmt = null;
        PreparedStatement insertStmt = null;

        int result = 0;

        try {
            insertStmt = JdbcUtil.prepareStatement(targetConn, insertSql);

            selectStmt = JdbcUtil.prepareStatement(sourceConn, selectSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            selectStmt.setFetchSize(fetchSize);

            copy(selectStmt, offset, count, insertStmt, stmtSetter, batchSize, batchInterval, inParallel);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(selectStmt);
            JdbcUtil.closeQuietly(insertStmt);
        }

        return result;
    }

    /**
     *
     * @param selectStmt
     * @param insertStmt
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static long copy(final PreparedStatement selectStmt, final PreparedStatement insertStmt,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter) throws UncheckedSQLException {
        return copy(selectStmt, 0, Integer.MAX_VALUE, insertStmt, stmtSetter, 200, 0, false);
    }

    /**
     *
     * @param selectStmt
     * @param offset
     * @param count
     * @param insertStmt
     * @param stmtSetter
     * @param batchSize
     * @param batchInterval
     * @param inParallel do the read and write in separated threads.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static long copy(final PreparedStatement selectStmt, final long offset, final long count, final PreparedStatement insertStmt,
            final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> stmtSetter, final int batchSize, final int batchInterval,
            final boolean inParallel) throws UncheckedSQLException {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchInterval >= 0, "'batchSize'=%s must be greater than 0 and 'batchInterval'=%s can't be negative", batchSize,
                batchInterval);

        @SuppressWarnings("rawtypes")
        final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> setter = (JdbcUtil.BiParametersSetter) (stmtSetter == null
                ? JdbcUtil.DEFAULT_STMT_SETTER
                : stmtSetter);
        final AtomicLong result = new AtomicLong();

        final Throwables.Consumer<Object[], RuntimeException> rowParser = new Throwables.Consumer<Object[], RuntimeException>() {
            @Override
            public void accept(Object[] row) {
                try {
                    setter.accept(insertStmt, row);

                    insertStmt.addBatch();
                    result.incrementAndGet();

                    if ((result.longValue() % batchSize) == 0) {
                        JdbcUtil.executeBatch(insertStmt);

                        if (batchInterval > 0) {
                            N.sleep(batchInterval);
                        }
                    }
                } catch (SQLException e) {
                    throw new UncheckedSQLException(e);
                }
            }
        };

        final Throwables.Runnable<RuntimeException> onComplete = new Throwables.Runnable<RuntimeException>() {
            @Override
            public void run() {
                if ((result.longValue() % batchSize) > 0) {
                    try {
                        JdbcUtil.executeBatch(insertStmt);
                    } catch (SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                }
            }
        };

        parse(selectStmt, offset, count, 0, inParallel ? DEFAULT_QUEUE_SIZE_FOR_ROW_PARSER : 0, rowParser, onComplete);

        return result.longValue();
    }

}
