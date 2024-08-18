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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.annotation.SequentialOnly;
import com.landawn.abacus.annotation.Stateful;
import com.landawn.abacus.jdbc.Jdbc.ColumnGetter;
import com.landawn.abacus.parser.JSONSerializationConfig;
import com.landawn.abacus.parser.JSONSerializationConfig.JSC;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.BufferedJSONWriter;
import com.landawn.abacus.util.BufferedReader;
import com.landawn.abacus.util.CSVUtil;
import com.landawn.abacus.util.DataSet;
import com.landawn.abacus.util.DateTimeFormat;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.IOUtil;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Objectory;
import com.landawn.abacus.util.ParsedSql;
import com.landawn.abacus.util.Strings;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.WD;

/**
 *
 * @author Haiyang Li
 * @see {@link com.landawn.abacus.util.CSVUtil}
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

    static final char[] ELEMENT_SEPARATOR_CHAR_ARRAY = Strings.ELEMENT_SEPARATOR.toCharArray();

    static final char[] NULL_CHAR_ARRAY = Strings.NULL_STRING.toCharArray();

    static final int DEFAULT_QUEUE_SIZE_FOR_ROW_PARSER = 1024;

    private JdbcUtils() {
        // singleton.
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param sourceDataSource
     * @param insertSQL the column order in the sql must be consistent with the column order in the DataSet. Here is sample about how to create the sql:
     * <pre><code>
     *         List<String> columnNameList = new ArrayList<>(dataset.columnNameList());
     *         columnNameList.retainAll(yourSelectColumnNames);
     *         String sql = RE.insert(columnNameList).into(tableName).sql();
     * </code></pre>
     * @return
     * @throws SQLException
     */
    public static int importData(final DataSet dataset, final javax.sql.DataSource sourceDataSource, final String insertSQL) throws SQLException {
        final Connection conn = sourceDataSource.getConnection();

        try {
            return importData(dataset, conn, insertSQL);
        } finally {
            JdbcUtil.releaseConnection(conn, sourceDataSource);
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
     * @return
     * @throws SQLException
     */
    public static int importData(final DataSet dataset, final Connection conn, final String insertSQL) throws SQLException {
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
     * @throws SQLException
     */
    public static int importData(final DataSet dataset, final Collection<String> selectColumnNames, final Connection conn, final String insertSQL)
            throws SQLException {
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
     * @throws SQLException
     */
    public static int importData(final DataSet dataset, final Collection<String> selectColumnNames, final int offset, final int count, final Connection conn,
            final String insertSQL) throws SQLException {
        return importData(dataset, selectColumnNames, offset, count, conn, insertSQL, JdbcUtil.DEFAULT_BATCH_SIZE, 0);
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
     * @param batchIntervalInMillis
     * @return
     * @throws SQLException
     */
    public static int importData(final DataSet dataset, final Collection<String> selectColumnNames, final int offset, final int count, final Connection conn,
            final String insertSQL, final int batchSize, final long batchIntervalInMillis) throws SQLException {
        return importData(dataset, selectColumnNames, offset, count, Fn.alwaysTrue(), conn, insertSQL, batchSize, batchIntervalInMillis);
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
     * @param batchIntervalInMillis
     * @return
     * @throws SQLException
     * @throws E
     */
    public static <E extends Exception> int importData(final DataSet dataset, final Collection<String> selectColumnNames, final int offset, final int count,
            final Throwables.Predicate<? super Object[], E> filter, final Connection conn, final String insertSQL, final int batchSize,
            final long batchIntervalInMillis) throws SQLException, E {

        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSQL)) {
            return importData(dataset, selectColumnNames, offset, count, filter, stmt, batchSize, batchIntervalInMillis);
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
     * @throws SQLException
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final DataSet dataset, final Connection conn, final String insertSQL, final Map<String, ? extends Type> columnTypeMap)
            throws SQLException {
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
     * @throws SQLException
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final DataSet dataset, final int offset, final int count, final Connection conn, final String insertSQL,
            final Map<String, ? extends Type> columnTypeMap) throws SQLException {
        return importData(dataset, offset, count, conn, insertSQL, JdbcUtil.DEFAULT_BATCH_SIZE, 0, columnTypeMap);
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
     * @param batchIntervalInMillis
     * @param columnTypeMap
     * @return
     * @throws SQLException
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final DataSet dataset, final int offset, final int count, final Connection conn, final String insertSQL, final int batchSize,
            final long batchIntervalInMillis, final Map<String, ? extends Type> columnTypeMap) throws SQLException {
        return importData(dataset, offset, count, Fn.alwaysTrue(), conn, insertSQL, batchSize, batchIntervalInMillis, columnTypeMap);
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
     * @param batchIntervalInMillis
     * @param columnTypeMap
     * @return
     * @throws SQLException
     * @throws E
     */
    @SuppressWarnings("rawtypes")
    public static <E extends Exception> int importData(final DataSet dataset, final int offset, final int count,
            final Throwables.Predicate<? super Object[], E> filter, final Connection conn, final String insertSQL, final int batchSize,
            final long batchIntervalInMillis, final Map<String, ? extends Type> columnTypeMap) throws SQLException, E {

        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSQL)) {
            return importData(dataset, offset, count, filter, stmt, batchSize, batchIntervalInMillis, columnTypeMap);
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
     * @throws SQLException
     */
    public static int importData(final DataSet dataset, final Connection conn, final String insertSQL,
            final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> stmtSetter) throws SQLException {
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
     * @throws SQLException
     */
    public static int importData(final DataSet dataset, final int offset, final int count, final Connection conn, final String insertSQL,
            final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> stmtSetter) throws SQLException {
        return importData(dataset, offset, count, conn, insertSQL, JdbcUtil.DEFAULT_BATCH_SIZE, 0, stmtSetter);
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
     * @param batchIntervalInMillis
     * @param stmtSetter
     * @return
     * @throws SQLException
     */
    public static int importData(final DataSet dataset, final int offset, final int count, final Connection conn, final String insertSQL, final int batchSize,
            final long batchIntervalInMillis, final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> stmtSetter)
            throws SQLException {
        return importData(dataset, offset, count, Fn.alwaysTrue(), conn, insertSQL, batchSize, batchIntervalInMillis, stmtSetter);
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
     * @param batchIntervalInMillis
     * @param stmtSetter
     * @return
     * @throws SQLException
     * @throws E
     */
    public static <E extends Exception> int importData(final DataSet dataset, final int offset, final int count,
            final Throwables.Predicate<? super Object[], E> filter, final Connection conn, final String insertSQL, final int batchSize,
            final long batchIntervalInMillis, final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> stmtSetter)
            throws SQLException, E {

        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSQL)) {
            return importData(dataset, offset, count, filter, stmt, batchSize, batchIntervalInMillis, stmtSetter);
        }
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @return
     * @throws SQLException
     */
    public static int importData(final DataSet dataset, final PreparedStatement stmt) throws SQLException {
        return importData(dataset, dataset.columnNameList(), stmt);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param selectColumnNames
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @return
     * @throws SQLException
     */
    public static int importData(final DataSet dataset, final Collection<String> selectColumnNames, final PreparedStatement stmt) throws SQLException {
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
     * @throws SQLException
     */
    public static int importData(final DataSet dataset, final Collection<String> selectColumnNames, final int offset, final int count,
            final PreparedStatement stmt) throws SQLException {
        return importData(dataset, selectColumnNames, offset, count, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0);
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
     * @param batchIntervalInMillis
     * @return
     * @throws SQLException
     */
    public static int importData(final DataSet dataset, final Collection<String> selectColumnNames, final int offset, final int count,
            final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis) throws SQLException {
        return importData(dataset, selectColumnNames, offset, count, Fn.alwaysTrue(), stmt, batchSize, batchIntervalInMillis);
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
     * @param batchIntervalInMillis
     * @return
     * @throws SQLException
     * @throws E
     */
    public static <E extends Exception> int importData(final DataSet dataset, final Collection<String> selectColumnNames, final int offset, final int count,
            final Throwables.Predicate<? super Object[], E> filter, final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis)
            throws SQLException, E {
        final Type<?> objType = N.typeOf(Object.class);
        final Map<String, Type<?>> columnTypeMap = new HashMap<>();

        for (String propName : selectColumnNames) {
            columnTypeMap.put(propName, objType);
        }

        return importData(dataset, offset, count, filter, stmt, batchSize, batchIntervalInMillis, columnTypeMap);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param columnTypeMap
     * @return
     * @throws SQLException
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final DataSet dataset, final PreparedStatement stmt, final Map<String, ? extends Type> columnTypeMap) throws SQLException {
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
     * @throws SQLException
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final DataSet dataset, final int offset, final int count, final PreparedStatement stmt,
            final Map<String, ? extends Type> columnTypeMap) throws SQLException {
        return importData(dataset, offset, count, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, columnTypeMap);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param batchSize
     * @param batchIntervalInMillis
     * @param columnTypeMap
     * @return
     * @throws SQLException
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final DataSet dataset, final int offset, final int count, final PreparedStatement stmt, final int batchSize,
            final long batchIntervalInMillis, final Map<String, ? extends Type> columnTypeMap) throws SQLException {
        return importData(dataset, offset, count, Fn.alwaysTrue(), stmt, batchSize, batchIntervalInMillis, columnTypeMap);
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
     * @param batchIntervalInMillis
     * @param columnTypeMap
     * @return
     * @throws IllegalArgumentException
     * @throws SQLException
     * @throws E
     */
    @SuppressWarnings({ "rawtypes", "null" })
    public static <E extends Exception> int importData(final DataSet dataset, final int offset, final int count,
            final Throwables.Predicate<? super Object[], E> filter, final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis,
            final Map<String, ? extends Type> columnTypeMap) throws IllegalArgumentException, SQLException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count); //NOSONAR
        N.checkArgument(batchSize > 0 && batchIntervalInMillis >= 0, "'batchSize'=%s must be greater than 0 and 'batchIntervalInMillis'=%s can't be negative", //NOSONAR
                batchSize, batchIntervalInMillis);

        final Throwables.BiConsumer<PreparedQuery, Object[], SQLException> stmtSetter = new Throwables.BiConsumer<>() {
            private int columnCount = 0;
            private Type<Object>[] columnTypes = null;
            private int[] columnIndexes = new int[columnCount];

            @Override
            public void accept(PreparedQuery t, Object[] u) throws SQLException {
                if (columnTypes == null) {
                    columnCount = columnTypeMap.size();
                    columnTypes = new Type[columnCount];
                    columnIndexes = new int[columnCount];

                    final List<String> columnNameList = dataset.columnNameList();
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
                        throw new IllegalArgumentException(keys + " are not included in titles: " + N.toString(columnNameList));
                    }
                }

                for (int j = 0; j < columnCount; j++) {
                    columnTypes[j].set(stmt, j + 1, dataset.get(columnIndexes[j]));
                }
            }
        };

        return importData(dataset, offset, count, filter, stmt, batchSize, batchIntervalInMillis, stmtSetter);
    }

    /**
     *
     * @param dataset
     * @param stmt
     * @param stmtSetter
     * @return
     * @throws SQLException
     */
    public static int importData(final DataSet dataset, final PreparedStatement stmt,
            final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> stmtSetter) throws SQLException {
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
     * @throws SQLException
     */
    public static int importData(final DataSet dataset, final int offset, final int count, final PreparedStatement stmt,
            final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> stmtSetter) throws SQLException {
        return importData(dataset, offset, count, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, stmtSetter);
    }

    /**
     * Imports the data from <code>DataSet</code> to database.
     *
     * @param dataset
     * @param offset
     * @param count
     * @param stmt the column order in the sql must be consistent with the column order in the DataSet.
     * @param batchSize
     * @param batchIntervalInMillis
     * @param stmtSetter
     * @return
     * @throws SQLException
     */
    public static int importData(final DataSet dataset, final int offset, final int count, final PreparedStatement stmt, final int batchSize,
            final long batchIntervalInMillis, final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> stmtSetter)
            throws SQLException {
        return importData(dataset, offset, count, Fn.alwaysTrue(), stmt, batchSize, batchIntervalInMillis, stmtSetter);
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
     * @param batchIntervalInMillis
     * @param stmtSetter
     * @return
     * @throws IllegalArgumentException
     * @throws SQLException
     * @throws E
     */
    public static <E extends Exception> int importData(final DataSet dataset, final int offset, final int count,
            final Throwables.Predicate<? super Object[], E> filter, final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> stmtSetter) throws IllegalArgumentException, SQLException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchIntervalInMillis >= 0, "'batchSize'=%s must be greater than 0 and 'batchIntervalInMillis'=%s can't be negative",
                batchSize, batchIntervalInMillis);

        final PreparedQuery stmtForSetter = new PreparedQuery(stmt);

        final int columnCount = dataset.columnNameList().size();
        final Object[] row = new Object[columnCount];
        int result = 0;

        for (int i = offset, size = dataset.size(); result < count && i < size; i++) {
            dataset.absolute(i);

            for (int j = 0; j < columnCount; j++) {
                row[j] = dataset.get(j);
            }

            if (filter != null && !filter.test(row)) {
                continue;
            }

            stmtSetter.accept(stmtForSetter, row);

            stmtForSetter.addBatch();

            if ((++result % batchSize) == 0) {
                JdbcUtil.executeBatch(stmt);

                if (batchIntervalInMillis > 0) {
                    N.sleep(batchIntervalInMillis);
                }
            }
        }

        if ((result % batchSize) > 0) {
            JdbcUtil.executeBatch(stmt);
        }

        return result;
    }

    /**
     *
     * @param <E>
     * @param file
     * @param sourceDataSource
     * @param insertSQL
     * @param func
     * @return
     * @throws SQLException
     * @throws IOException
     * @throws E
     */
    public static <E extends Exception> long importData(final File file, final javax.sql.DataSource sourceDataSource, final String insertSQL,
            final Throwables.Function<? super String, Object[], E> func) throws SQLException, IOException, E {
        final Connection conn = sourceDataSource.getConnection();

        try {
            return importData(file, conn, insertSQL, func);
        } finally {
            JdbcUtil.releaseConnection(conn, sourceDataSource);
        }
    }

    /**
     *
     * @param <E>
     * @param file
     * @param conn
     * @param insertSQL
     * @param func
     * @return
     * @throws SQLException
     * @throws IOException
     * @throws E
     */
    public static <E extends Exception> long importData(final File file, final Connection conn, final String insertSQL,
            final Throwables.Function<? super String, Object[], E> func) throws SQLException, IOException, E {
        return importData(file, 0, Long.MAX_VALUE, conn, insertSQL, JdbcUtil.DEFAULT_BATCH_SIZE, 0, func);
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
     * @param batchIntervalInMillis
     * @param func
     * @return
     * @throws SQLException
     * @throws IOException
     * @throws E
     */
    public static <E extends Exception> long importData(final File file, final long offset, final long count, final Connection conn, final String insertSQL,
            final int batchSize, final long batchIntervalInMillis, final Throwables.Function<? super String, Object[], E> func)
            throws SQLException, IOException, E {

        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSQL)) {
            return importData(file, offset, count, stmt, batchSize, batchIntervalInMillis, func);
        }
    }

    /**
     *
     * @param <E>
     * @param file
     * @param stmt
     * @param func
     * @return
     * @throws SQLException
     * @throws IOException
     * @throws E
     */
    public static <E extends Exception> long importData(final File file, final PreparedStatement stmt,
            final Throwables.Function<? super String, Object[], E> func) throws SQLException, IOException, E {
        return importData(file, 0, Long.MAX_VALUE, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, func);
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
     * @param batchIntervalInMillis
     * @param func convert line to the parameters for record insert. Returns a <code>null</code> array to skip the line.
     * @return
     * @throws SQLException
     * @throws IOException
     * @throws E
     */
    public static <E extends Exception> long importData(final File file, final long offset, final long count, final PreparedStatement stmt, final int batchSize,
            final long batchIntervalInMillis, final Throwables.Function<? super String, Object[], E> func) throws SQLException, IOException, E {

        try (Reader reader = IOUtil.newFileReader(file)) {
            return importData(reader, offset, count, stmt, batchSize, batchIntervalInMillis, func);
        }
    }

    /**
     *
     * @param <E>
     * @param is
     * @param sourceDataSource
     * @param insertSQL
     * @param func
     * @return
     * @throws SQLException
     * @throws IOException
     * @throws E
     */
    public static <E extends Exception> long importData(final InputStream is, final javax.sql.DataSource sourceDataSource, final String insertSQL,
            final Throwables.Function<? super String, Object[], E> func) throws SQLException, IOException, E {
        final Connection conn = sourceDataSource.getConnection();

        try {
            return importData(is, conn, insertSQL, func);
        } finally {
            JdbcUtil.releaseConnection(conn, sourceDataSource);
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
     * @throws SQLException
     * @throws IOException
     * @throws E
     */
    public static <E extends Exception> long importData(final InputStream is, final Connection conn, final String insertSQL,
            final Throwables.Function<? super String, Object[], E> func) throws SQLException, IOException, E {
        return importData(is, 0, Long.MAX_VALUE, conn, insertSQL, JdbcUtil.DEFAULT_BATCH_SIZE, 0, func);
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
     * @param batchIntervalInMillis
     * @param func
     * @return
     * @throws SQLException
     * @throws IOException
     * @throws E
     */
    public static <E extends Exception> long importData(final InputStream is, final long offset, final long count, final Connection conn,
            final String insertSQL, final int batchSize, final long batchIntervalInMillis, final Throwables.Function<? super String, Object[], E> func)
            throws SQLException, IOException, E {
        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSQL)) {
            return importData(is, offset, count, stmt, batchSize, batchIntervalInMillis, func);
        }
    }

    /**
     *
     * @param <E>
     * @param is
     * @param stmt
     * @param func
     * @return
     * @throws SQLException
     * @throws IOException
     * @throws E
     */
    public static <E extends Exception> long importData(final InputStream is, final PreparedStatement stmt,
            final Throwables.Function<? super String, Object[], E> func) throws SQLException, IOException, E {
        return importData(is, 0, Long.MAX_VALUE, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, func);
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
     * @param batchIntervalInMillis
     * @param func convert line to the parameters for record insert. Returns a <code>null</code> array to skip the line.
     * @return
     * @throws SQLException
     * @throws IOException
     * @throws E
     */
    public static <E extends Exception> long importData(final InputStream is, final long offset, final long count, final PreparedStatement stmt,
            final int batchSize, final long batchIntervalInMillis, final Throwables.Function<? super String, Object[], E> func)
            throws SQLException, IOException, E {
        final Reader reader = IOUtil.newInputStreamReader(is);

        return importData(reader, offset, count, stmt, batchSize, batchIntervalInMillis, func);
    }

    /**
     *
     * @param <E>
     * @param reader
     * @param sourceDataSource
     * @param insertSQL
     * @param func
     * @return
     * @throws SQLException
     * @throws IOException
     * @throws E
     */
    public static <E extends Exception> long importData(final Reader reader, final javax.sql.DataSource sourceDataSource, final String insertSQL,
            final Throwables.Function<? super String, Object[], E> func) throws SQLException, IOException, E {
        final Connection conn = sourceDataSource.getConnection();

        try {
            return importData(reader, conn, insertSQL, func);
        } finally {
            JdbcUtil.releaseConnection(conn, sourceDataSource);
        }
    }

    /**
     *
     * @param <E>
     * @param reader
     * @param conn
     * @param insertSQL
     * @param func
     * @return
     * @throws SQLException
     * @throws IOException
     * @throws E
     */
    public static <E extends Exception> long importData(final Reader reader, final Connection conn, final String insertSQL,
            final Throwables.Function<? super String, Object[], E> func) throws SQLException, IOException, E {
        return importData(reader, 0, Long.MAX_VALUE, conn, insertSQL, JdbcUtil.DEFAULT_BATCH_SIZE, 0, func);
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
     * @param batchIntervalInMillis
     * @param func
     * @return
     * @throws SQLException
     * @throws IOException
     * @throws E
     */
    public static <E extends Exception> long importData(final Reader reader, final long offset, final long count, final Connection conn, final String insertSQL,
            final int batchSize, final long batchIntervalInMillis, final Throwables.Function<? super String, Object[], E> func)
            throws SQLException, IOException, E {
        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSQL)) {
            return importData(reader, offset, count, stmt, batchSize, batchIntervalInMillis, func);
        }
    }

    /**
     *
     * @param <E>
     * @param reader
     * @param stmt
     * @param func
     * @return
     * @throws SQLException
     * @throws IOException
     * @throws E
     */
    public static <E extends Exception> long importData(final Reader reader, final PreparedStatement stmt,
            final Throwables.Function<? super String, Object[], E> func) throws SQLException, IOException, E {
        return importData(reader, 0, Long.MAX_VALUE, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, func);
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
     * @param batchIntervalInMillis
     * @param func convert line to the parameters for record insert. Returns a <code>null</code> array to skip the line.
     * @return
     * @throws IllegalArgumentException
     * @throws SQLException
     * @throws IOException
     * @throws E
     */
    public static <E extends Exception> long importData(final Reader reader, long offset, final long count, final PreparedStatement stmt, final int batchSize,
            final long batchIntervalInMillis, final Throwables.Function<? super String, Object[], E> func)
            throws IllegalArgumentException, SQLException, IOException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchIntervalInMillis >= 0, "'batchSize'=%s must be greater than 0 and 'batchIntervalInMillis'=%s can't be negative",
                batchSize, batchIntervalInMillis);

        long result = 0;
        final BufferedReader br = Objectory.createBufferedReader(reader);

        try {
            while (offset-- > 0 && br.readLine() != null) {
                // skip.
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

                    if (batchIntervalInMillis > 0) {
                        N.sleep(batchIntervalInMillis);
                    }
                }
            }

            if ((result % batchSize) > 0) {
                JdbcUtil.executeBatch(stmt);
            }
        } finally {
            Objectory.recycle(br);
        }

        return result;
    }

    /**
     *
     * @param <T>
     * @param iter
     * @param sourceDataSource
     * @param insertSQL
     * @param stmtSetter
     * @return
     * @throws SQLException
     */
    public static <T> long importData(final Iterator<? extends T> iter, final javax.sql.DataSource sourceDataSource, final String insertSQL,
            final Throwables.BiConsumer<? super PreparedQuery, ? super T, SQLException> stmtSetter) throws SQLException {
        final Connection conn = sourceDataSource.getConnection();

        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSQL)) {
            return importData(iter, stmt, stmtSetter);
        } finally {
            JdbcUtil.releaseConnection(conn, sourceDataSource);
        }
    }

    /**
     *
     * @param <T>
     * @param iter
     * @param conn
     * @param insertSQL
     * @param stmtSetter
     * @return
     * @throws SQLException
     */
    public static <T> long importData(final Iterator<? extends T> iter, final Connection conn, final String insertSQL,
            final Throwables.BiConsumer<? super PreparedQuery, ? super T, SQLException> stmtSetter) throws SQLException {
        return importData(iter, 0, Long.MAX_VALUE, conn, insertSQL, JdbcUtil.DEFAULT_BATCH_SIZE, 0, stmtSetter);
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
     * @param batchIntervalInMillis
     * @param stmtSetter
     * @return
     * @throws SQLException
     */
    public static <T> long importData(final Iterator<? extends T> iter, final long offset, final long count, final Connection conn, final String insertSQL,
            final int batchSize, final long batchIntervalInMillis, final Throwables.BiConsumer<? super PreparedQuery, ? super T, SQLException> stmtSetter)
            throws SQLException {
        return importData(iter, offset, count, Fn.alwaysTrue(), conn, insertSQL, batchSize, batchIntervalInMillis, stmtSetter);
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
     * @param batchIntervalInMillis
     * @param stmtSetter
     * @return
     * @throws SQLException
     * @throws E
     */
    public static <T, E extends Exception> long importData(final Iterator<? extends T> iter, final long offset, final long count,
            final Throwables.Predicate<? super T, E> filter, final Connection conn, final String insertSQL, final int batchSize,
            final long batchIntervalInMillis, final Throwables.BiConsumer<? super PreparedQuery, ? super T, SQLException> stmtSetter) throws SQLException, E {
        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSQL)) {
            return importData(iter, offset, count, filter, stmt, batchSize, batchIntervalInMillis, stmtSetter);
        }
    }

    /**
     *
     * @param <T>
     * @param iter
     * @param stmt
     * @param stmtSetter
     * @return
     * @throws SQLException
     */
    public static <T> long importData(final Iterator<? extends T> iter, final PreparedStatement stmt,
            final Throwables.BiConsumer<? super PreparedQuery, ? super T, SQLException> stmtSetter) throws SQLException {
        return importData(iter, 0, Long.MAX_VALUE, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, stmtSetter);
    }

    /**
     *
     * @param <T>
     * @param iter
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchIntervalInMillis
     * @param stmtSetter
     * @return
     * @throws SQLException
     */
    public static <T> long importData(final Iterator<? extends T> iter, long offset, final long count, final PreparedStatement stmt, final int batchSize,
            final long batchIntervalInMillis, final Throwables.BiConsumer<? super PreparedQuery, ? super T, SQLException> stmtSetter) throws SQLException {
        return importData(iter, offset, count, Fn.alwaysTrue(), stmt, batchSize, batchIntervalInMillis, stmtSetter);
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
     * @param batchIntervalInMillis
     * @param stmtSetter
     * @return
     * @throws IllegalArgumentException
     * @throws SQLException
     * @throws E
     */
    public static <T, E extends Exception> long importData(final Iterator<? extends T> iter, long offset, final long count,
            final Throwables.Predicate<? super T, E> filter, final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super T, SQLException> stmtSetter) throws IllegalArgumentException, SQLException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchIntervalInMillis >= 0, "'batchSize'=%s must be greater than 0 and 'batchIntervalInMillis'=%s can't be negative",
                batchSize, batchIntervalInMillis);

        final PreparedQuery stmtForSetter = new PreparedQuery(stmt);
        long result = 0;

        while (offset-- > 0 && iter.hasNext()) {
            iter.next();
        }

        T next = null;
        while (result < count && iter.hasNext()) {
            next = iter.next();

            if (filter != null && !filter.test(next)) {
                continue;
            }

            stmtSetter.accept(stmtForSetter, next);
            stmtForSetter.addBatch();

            if ((++result % batchSize) == 0) {
                JdbcUtil.executeBatch(stmt);

                if (batchIntervalInMillis > 0) {
                    N.sleep(batchIntervalInMillis);
                }
            }
        }

        if ((result % batchSize) > 0) {
            JdbcUtil.executeBatch(stmt);
        }

        return result;
    }

    //    /**
    //     *
    //     * @param file
    //     * @param conn
    //     * @param insertSQL
    //     * @param columnTypeList
    //     * @return
    //     * @throws SQLException
    //     * @throws IOException
    //     */
    //    @SuppressWarnings("rawtypes")
    //    public static long importCSV(final File file, final Connection conn, final String insertSQL, final List<? extends Type> columnTypeList)
    //            throws SQLException, IOException {
    //        return importCSV(file, 0, Long.MAX_VALUE, true, conn, insertSQL, JdbcUtil.DEFAULT_BATCH_SIZE, 0, columnTypeList);
    //    }
    //
    //    /**
    //     *
    //     * @param file
    //     * @param offset
    //     * @param count
    //     * @param skipTitle
    //     * @param conn
    //     * @param insertSQL
    //     * @param batchSize
    //     * @param batchIntervalInMillis
    //     * @param columnTypeList
    //     * @return
    //     * @throws SQLException
    //     * @throws IOException
    //     */
    //    @SuppressWarnings({ "unchecked", "rawtypes" })
    //    public static long importCSV(final File file, final long offset, final long count, final boolean skipTitle, final Connection conn, final String insertSQL,
    //            final int batchSize, final long batchIntervalInMillis, final List<? extends Type> columnTypeList) throws SQLException, IOException {
    //        return importCSV(file, offset, count, skipTitle, Fn.<String[]> alwaysTrue(), conn, insertSQL, batchSize, batchIntervalInMillis, columnTypeList);
    //    }
    //
    //    /**
    //     * Imports the data from CSV to database.
    //     *
    //     * @param <E>
    //     * @param file
    //     * @param offset
    //     * @param count
    //     * @param skipTitle
    //     * @param filter
    //     * @param conn
    //     * @param insertSQL the column order in the sql must be consistent with the column order in the CSV file.
    //     * @param batchSize
    //     * @param batchIntervalInMillis
    //     * @param columnTypeList set the column type to null to skip the column in CSV.
    //     * @return
    //     * @throws SQLException
    //     * @throws IOException
    //     * @throws E
    //     */
    //    @SuppressWarnings("rawtypes")
    //    public static <E extends Exception> long importCSV(final File file, final long offset, final long count, final boolean skipTitle,
    //            final Throwables.Predicate<? super String[], E> filter, final Connection conn, final String insertSQL, final int batchSize,
    //            final long batchIntervalInMillis, final List<? extends Type> columnTypeList) throws SQLException, IOException, E {
    //
    //        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSQL)) {
    //            return importCSV(file, offset, count, skipTitle, filter, stmt, batchSize, batchIntervalInMillis, columnTypeList);
    //        }
    //    }
    //
    //    /**
    //     *
    //     * @param file
    //     * @param stmt
    //     * @param columnTypeList
    //     * @return
    //     * @throws SQLException
    //     * @throws IOException
    //     */
    //    @SuppressWarnings("rawtypes")
    //    public static long importCSV(final File file, final PreparedStatement stmt, final List<? extends Type> columnTypeList) throws SQLException, IOException {
    //        return importCSV(file, 0, Long.MAX_VALUE, true, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, columnTypeList);
    //    }
    //
    //    /**
    //     *
    //     * @param file
    //     * @param offset
    //     * @param count
    //     * @param skipTitle
    //     * @param stmt
    //     * @param batchSize
    //     * @param batchIntervalInMillis
    //     * @param columnTypeList
    //     * @return
    //     * @throws SQLException
    //     * @throws IOException
    //     */
    //    @SuppressWarnings({ "unchecked", "rawtypes" })
    //    public static long importCSV(final File file, long offset, final long count, final boolean skipTitle, final PreparedStatement stmt, final int batchSize,
    //            final long batchIntervalInMillis, final List<? extends Type> columnTypeList) throws SQLException, IOException {
    //        return importCSV(file, offset, count, skipTitle, Fn.<String[]> alwaysTrue(), stmt, batchSize, batchIntervalInMillis, columnTypeList);
    //    }
    //
    //    /**
    //     * Imports the data from CSV to database.
    //     *
    //     * @param <E>
    //     * @param file
    //     * @param offset
    //     * @param count
    //     * @param skipTitle
    //     * @param filter
    //     * @param stmt the column order in the sql must be consistent with the column order in the CSV file.
    //     * @param batchSize
    //     * @param batchIntervalInMillis
    //     * @param columnTypeList set the column type to null to skip the column in CSV.
    //     * @return
    //     * @throws SQLException
    //     * @throws IOException
    //     * @throws E
    //     */
    //    @SuppressWarnings("rawtypes")
    //    public static <E extends Exception> long importCSV(final File file, final long offset, final long count, final boolean skipTitle,
    //            final Throwables.Predicate<? super String[], E> filter, final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis,
    //            final List<? extends Type> columnTypeList) throws SQLException, IOException, E {
    //
    //        try (Reader reader = new FileReader(file)) {
    //            return importCSV(reader, offset, count, skipTitle, filter, stmt, batchSize, batchIntervalInMillis, columnTypeList);
    //        }
    //    }
    //
    //    /**
    //     *
    //     * @param is
    //     * @param stmt
    //     * @param columnTypeList
    //     * @return
    //     * @throws SQLException
    //     * @throws IOException
    //     */
    //    @SuppressWarnings("rawtypes")
    //    public static long importCSV(final InputStream is, final PreparedStatement stmt, final List<? extends Type> columnTypeList)
    //            throws SQLException, IOException {
    //        return importCSV(is, 0, Long.MAX_VALUE, true, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, columnTypeList);
    //    }
    //
    //    /**
    //     *
    //     * @param is
    //     * @param offset
    //     * @param count
    //     * @param skipTitle
    //     * @param stmt
    //     * @param batchSize
    //     * @param batchIntervalInMillis
    //     * @param columnTypeList
    //     * @return
    //     * @throws SQLException
    //     * @throws IOException
    //     */
    //    @SuppressWarnings({ "unchecked", "rawtypes" })
    //    public static long importCSV(final InputStream is, long offset, final long count, final boolean skipTitle, final PreparedStatement stmt,
    //            final int batchSize, final long batchIntervalInMillis, final List<? extends Type> columnTypeList) throws SQLException, IOException {
    //        return importCSV(is, offset, count, skipTitle, Fn.<String[]> alwaysTrue(), stmt, batchSize, batchIntervalInMillis, columnTypeList);
    //    }
    //
    //    /**
    //     * Imports the data from CSV to database.
    //     *
    //     * @param <E>
    //     * @param is
    //     * @param offset
    //     * @param count
    //     * @param skipTitle
    //     * @param filter
    //     * @param stmt the column order in the sql must be consistent with the column order in the CSV file.
    //     * @param batchSize
    //     * @param batchIntervalInMillis
    //     * @param columnTypeList set the column type to null to skip the column in CSV.
    //     * @return
    //     * @throws SQLException
    //     * @throws IOException
    //     * @throws E
    //     */
    //    @SuppressWarnings("rawtypes")
    //    public static <E extends Exception> long importCSV(final InputStream is, final long offset, final long count, final boolean skipTitle,
    //            final Throwables.Predicate<? super String[], E> filter, final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis,
    //            final List<? extends Type> columnTypeList) throws SQLException, IOException, E {
    //        final Reader reader = new InputStreamReader(is);
    //
    //        return importCSV(reader, offset, count, skipTitle, filter, stmt, batchSize, batchIntervalInMillis, columnTypeList);
    //    }
    //
    //    /**
    //     *
    //     * @param reader
    //     * @param stmt
    //     * @param columnTypeList
    //     * @return
    //     * @throws SQLException
    //     * @throws IOException
    //     */
    //    @SuppressWarnings("rawtypes")
    //    public static long importCSV(final Reader reader, final PreparedStatement stmt, final List<? extends Type> columnTypeList)
    //            throws SQLException, IOException {
    //        return importCSV(reader, 0, Long.MAX_VALUE, true, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, columnTypeList);
    //    }
    //
    //    /**
    //     *
    //     * @param reader
    //     * @param offset
    //     * @param count
    //     * @param skipTitle
    //     * @param stmt
    //     * @param batchSize
    //     * @param batchIntervalInMillis
    //     * @param columnTypeList
    //     * @return
    //     * @throws SQLException
    //     * @throws IOException
    //     */
    //    @SuppressWarnings({ "unchecked", "rawtypes" })
    //    public static long importCSV(final Reader reader, long offset, final long count, final boolean skipTitle, final PreparedStatement stmt, final int batchSize,
    //            final long batchIntervalInMillis, final List<? extends Type> columnTypeList) throws SQLException, IOException {
    //        return importCSV(reader, offset, count, skipTitle, Fn.<String[]> alwaysTrue(), stmt, batchSize, batchIntervalInMillis, columnTypeList);
    //    }
    //
    //    /**
    //     * Imports the data from CSV to database.
    //     *
    //     * @param <E>
    //     * @param reader
    //     * @param offset
    //     * @param count
    //     * @param skipTitle
    //     * @param filter
    //     * @param stmt the column order in the sql must be consistent with the column order in the CSV file.
    //     * @param batchSize
    //     * @param batchIntervalInMillis
    //     * @param columnTypeList set the column type to null to skip the column in CSV.
    //     * @return
    //     * @throws SQLException
    //     * @throws IOException
    //     * @throws E
    //     */
    //    @SuppressWarnings({ "unchecked", "rawtypes" })
    //    public static <E extends Exception> long importCSV(final Reader reader, long offset, final long count, final boolean skipTitle,
    //            final Throwables.Predicate<? super String[], E> filter, final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis,
    //            final List<? extends Type> columnTypeList) throws SQLException, IOException, E {
    //        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
    //        N.checkArgument(batchSize > 0 && batchIntervalInMillis >= 0, "'batchSize'=%s must be greater than 0 and 'batchIntervalInMillis'=%s can't be negative",
    //                batchSize, batchIntervalInMillis);
    //
    //        final BiConsumer<String[], String> lineParser = CSVUtil.getCurrentLineParser();
    //        long result = 0;
    //        final BufferedReader br = Objectory.createBufferedReader(reader);
    //
    //        try {
    //            if (skipTitle) {
    //                br.readLine(); // skip the title line.
    //            }
    //
    //            while (offset-- > 0 && br.readLine() != null) {
    //                // skip.
    //            }
    //
    //            final Type<Object>[] columnTypes = columnTypeList.toArray(new Type[columnTypeList.size()]);
    //            final String[] strs = new String[columnTypeList.size()];
    //            String line = null;
    //            Type<Object> type = null;
    //
    //            while (result < count && (line = br.readLine()) != null) {
    //                lineParser.accept(strs, line);
    //
    //                if (filter != null && !filter.test(strs)) {
    //                    continue;
    //                }
    //
    //                for (int i = 0, parameterIndex = 1, len = strs.length; i < len; i++) {
    //                    type = columnTypes[i];
    //
    //                    if (type == null) {
    //                        continue;
    //                    }
    //
    //                    type.set(stmt, parameterIndex++, (strs[i] == null) ? null : type.valueOf(strs[i]));
    //                }
    //
    //                stmt.addBatch();
    //
    //                result++;
    //
    //                if ((result % batchSize) == 0) {
    //                    JdbcUtil.executeBatch(stmt);
    //
    //                    if (batchIntervalInMillis > 0) {
    //                        N.sleep(batchIntervalInMillis);
    //                    }
    //                }
    //
    //                N.fill(strs, null);
    //            }
    //
    //            if ((result % batchSize) > 0) {
    //                JdbcUtil.executeBatch(stmt);
    //            }
    //        } finally {
    //            Objectory.recycle(br);
    //        }
    //
    //        return result;
    //    }
    //
    //    /**
    //     * Imports the data from CSV to database.
    //     *
    //     * @param file
    //     * @param conn
    //     * @param insertSQL the column order in the sql must be consistent with the column order in the CSV file.
    //     * @param columnTypeMap
    //     * @return
    //     * @throws SQLException
    //     * @throws IOException
    //     */
    //    @SuppressWarnings("rawtypes")
    //    public static long importCSV(final File file, final Connection conn, final String insertSQL, final Map<String, ? extends Type> columnTypeMap)
    //            throws SQLException, IOException {
    //        return importCSV(file, 0, Long.MAX_VALUE, conn, insertSQL, JdbcUtil.DEFAULT_BATCH_SIZE, 0, columnTypeMap);
    //    }
    //
    //    /**
    //     *
    //     * @param file
    //     * @param offset
    //     * @param count
    //     * @param conn
    //     * @param insertSQL
    //     * @param batchSize
    //     * @param batchIntervalInMillis
    //     * @param columnTypeMap
    //     * @return
    //     * @throws SQLException
    //     * @throws IOException
    //     */
    //    @SuppressWarnings("rawtypes")
    //    public static long importCSV(final File file, final long offset, final long count, final Connection conn, final String insertSQL, final int batchSize,
    //            final long batchIntervalInMillis, final Map<String, ? extends Type> columnTypeMap) throws SQLException, IOException {
    //        return importCSV(file, offset, count, Fn.<String[]> alwaysTrue(), conn, insertSQL, batchSize, batchIntervalInMillis, columnTypeMap);
    //    }
    //
    //    /**
    //     *
    //     * @param <E>
    //     * @param file
    //     * @param offset
    //     * @param count
    //     * @param filter
    //     * @param conn
    //     * @param insertSQL the column order in the sql must be consistent with the column order in the CSV file.
    //     * @param batchSize
    //     * @param batchIntervalInMillis
    //     * @param columnTypeMap
    //     * @return
    //     * @throws SQLException
    //     * @throws IOException
    //     * @throws E
    //     */
    //    @SuppressWarnings("rawtypes")
    //    public static <E extends Exception> long importCSV(final File file, final long offset, final long count, final Throwables.Predicate<? super String[], E> filter,
    //            final Connection conn, final String insertSQL, final int batchSize, final long batchIntervalInMillis,
    //            final Map<String, ? extends Type> columnTypeMap) throws SQLException, IOException, E {
    //
    //        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSQL)) {
    //            return importCSV(file, offset, count, filter, stmt, batchSize, batchIntervalInMillis, columnTypeMap);
    //        }
    //    }
    //
    //    /**
    //     *
    //     * @param file
    //     * @param stmt
    //     * @param columnTypeMap
    //     * @return
    //     * @throws SQLException
    //     * @throws IOException
    //     */
    //    @SuppressWarnings("rawtypes")
    //    public static long importCSV(final File file, final PreparedStatement stmt, final Map<String, ? extends Type> columnTypeMap)
    //            throws SQLException, IOException {
    //        return importCSV(file, 0, Long.MAX_VALUE, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, columnTypeMap);
    //    }
    //
    //    /**
    //     *
    //     * @param file
    //     * @param offset
    //     * @param count
    //     * @param stmt
    //     * @param batchSize
    //     * @param batchIntervalInMillis
    //     * @param columnTypeMap
    //     * @return
    //     * @throws SQLException
    //     * @throws IOException
    //     */
    //    @SuppressWarnings("rawtypes")
    //    public static long importCSV(final File file, final long offset, final long count, final PreparedStatement stmt, final int batchSize,
    //            final long batchIntervalInMillis, final Map<String, ? extends Type> columnTypeMap) throws SQLException, IOException {
    //        return importCSV(file, offset, count, Fn.<String[]> alwaysTrue(), stmt, batchSize, batchIntervalInMillis, columnTypeMap);
    //    }
    //
    //    /**
    //     * Imports the data from CSV to database.
    //     *
    //     * @param <E>
    //     * @param file
    //     * @param offset
    //     * @param count
    //     * @param filter
    //     * @param stmt the column order in the sql must be consistent with the column order in the CSV file.
    //     * @param batchSize
    //     * @param batchIntervalInMillis
    //     * @param columnTypeMap
    //     * @return
    //     * @throws SQLException
    //     * @throws IOException
    //     * @throws E
    //     */
    //    @SuppressWarnings("rawtypes")
    //    public static <E extends Exception> long importCSV(final File file, final long offset, final long count, final Throwables.Predicate<? super String[], E> filter,
    //            final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis, final Map<String, ? extends Type> columnTypeMap)
    //            throws SQLException, IOException, E {
    //
    //        try (Reader reader = new FileReader(file)) {
    //            return importCSV(reader, offset, count, filter, stmt, batchSize, batchIntervalInMillis, columnTypeMap);
    //        }
    //    }
    //
    //    /**
    //     *
    //     * @param is
    //     * @param stmt
    //     * @param columnTypeMap
    //     * @return
    //     * @throws SQLException
    //     * @throws IOException
    //     */
    //    @SuppressWarnings("rawtypes")
    //    public static long importCSV(final InputStream is, final PreparedStatement stmt, final Map<String, ? extends Type> columnTypeMap)
    //            throws SQLException, IOException {
    //        return importCSV(is, 0, Long.MAX_VALUE, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, columnTypeMap);
    //    }
    //
    //    /**
    //     *
    //     * @param is
    //     * @param offset
    //     * @param count
    //     * @param stmt
    //     * @param batchSize
    //     * @param batchIntervalInMillis
    //     * @param columnTypeMap
    //     * @return
    //     * @throws SQLException
    //     * @throws IOException
    //     */
    //    @SuppressWarnings("rawtypes")
    //    public static long importCSV(final InputStream is, final long offset, final long count, final PreparedStatement stmt, final int batchSize,
    //            final long batchIntervalInMillis, final Map<String, ? extends Type> columnTypeMap) throws SQLException, IOException {
    //        return importCSV(is, offset, count, Fn.<String[]> alwaysTrue(), stmt, batchSize, batchIntervalInMillis, columnTypeMap);
    //    }
    //
    //    /**
    //     * Imports the data from CSV to database.
    //     *
    //     * @param <E>
    //     * @param is
    //     * @param offset
    //     * @param count
    //     * @param filter
    //     * @param stmt the column order in the sql must be consistent with the column order in the CSV file.
    //     * @param batchSize
    //     * @param batchIntervalInMillis
    //     * @param columnTypeMap
    //     * @return
    //     * @throws SQLException
    //     * @throws IOException
    //     * @throws E
    //     */
    //    @SuppressWarnings("rawtypes")
    //    public static <E extends Exception> long importCSV(final InputStream is, long offset, final long count, final Throwables.Predicate<? super String[], E> filter,
    //            final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis, final Map<String, ? extends Type> columnTypeMap)
    //            throws SQLException, IOException, E {
    //        final Reader reader = new InputStreamReader(is);
    //        return importCSV(reader, offset, count, filter, stmt, batchSize, batchIntervalInMillis, columnTypeMap);
    //    }
    //
    //    /**
    //     *
    //     * @param reader
    //     * @param stmt
    //     * @param columnTypeMap
    //     * @return
    //     * @throws SQLException
    //     * @throws IOException
    //     */
    //    @SuppressWarnings("rawtypes")
    //    public static long importCSV(final Reader reader, final PreparedStatement stmt, final Map<String, ? extends Type> columnTypeMap)
    //            throws SQLException, IOException {
    //        return importCSV(reader, 0, Long.MAX_VALUE, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, columnTypeMap);
    //    }
    //
    //    /**
    //     *
    //     * @param reader
    //     * @param offset
    //     * @param count
    //     * @param stmt
    //     * @param batchSize
    //     * @param batchIntervalInMillis
    //     * @param columnTypeMap
    //     * @return
    //     * @throws SQLException
    //     * @throws IOException
    //     */
    //    @SuppressWarnings({ "unchecked", "rawtypes" })
    //    public static long importCSV(final Reader reader, long offset, final long count, final PreparedStatement stmt, final int batchSize,
    //            final long batchIntervalInMillis, final Map<String, ? extends Type> columnTypeMap) throws SQLException, IOException {
    //        return importCSV(reader, offset, count, Fn.<String[]> alwaysTrue(), stmt, batchSize, batchIntervalInMillis, columnTypeMap);
    //    }
    //
    //    /**
    //     * Imports the data from CSV to database.
    //     *
    //     * @param <E>
    //     * @param reader
    //     * @param offset
    //     * @param count
    //     * @param filter
    //     * @param stmt the column order in the sql must be consistent with the column order in the CSV file.
    //     * @param batchSize
    //     * @param batchIntervalInMillis
    //     * @param columnTypeMap
    //     * @return
    //     * @throws SQLException
    //     * @throws IOException
    //     * @throws E
    //     */
    //    @SuppressWarnings({ "unchecked", "rawtypes" })
    //    public static <E extends Exception> long importCSV(final Reader reader, long offset, final long count, final Throwables.Predicate<? super String[], E> filter,
    //            final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis, final Map<String, ? extends Type> columnTypeMap)
    //            throws SQLException, IOException, E {
    //        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
    //        N.checkArgument(batchSize > 0 && batchIntervalInMillis >= 0, "'batchSize'=%s must be greater than 0 and 'batchIntervalInMillis'=%s can't be negative",
    //                batchSize, batchIntervalInMillis);
    //
    //        final Function<String, String[]> headerParser = CSVUtil.getCurrentHeaderParser();
    //        final BiConsumer<String[], String> lineParser = CSVUtil.getCurrentLineParser();
    //        long result = 0;
    //        final BufferedReader br = Objectory.createBufferedReader(reader);
    //
    //        try {
    //            String line = br.readLine();
    //            final String[] titles = headerParser.apply(line);
    //
    //            final Type<Object>[] columnTypes = new Type[titles.length];
    //            final List<String> columnNameList = new ArrayList<>(columnTypeMap.size());
    //
    //            for (int i = 0, columnCount = titles.length; i < columnCount; i++) {
    //                if (columnTypeMap.containsKey(titles[i])) {
    //                    columnTypes[i] = columnTypeMap.get(titles[i]);
    //                    columnNameList.add(titles[i]);
    //                }
    //            }
    //
    //            if (columnNameList.size() != columnTypeMap.size()) {
    //                final List<String> keys = new ArrayList<>(columnTypeMap.keySet());
    //                keys.removeAll(columnNameList);
    //                throw new IllegalArgumentException(keys + " are not included in titles: " + N.toString(titles));
    //            }
    //
    //            while (offset-- > 0 && br.readLine() != null) {
    //                // skip.
    //            }
    //
    //            final boolean isNullOrEmptyTypes = N.isEmpty(columnTypes);
    //            final String[] strs = new String[titles.length];
    //            Type<Object> type = null;
    //
    //            while (result < count && (line = br.readLine()) != null) {
    //                lineParser.accept(strs, line);
    //
    //                if (filter != null && !filter.test(strs)) {
    //                    continue;
    //                }
    //
    //                if (isNullOrEmptyTypes) {
    //                    for (int i = 0, len = strs.length; i < len; i++) {
    //                        stmt.setObject(i + 1, strs[i]);
    //                    }
    //                } else {
    //                    for (int i = 0, parameterIndex = 1, len = strs.length; i < len; i++) {
    //                        type = columnTypes[i];
    //
    //                        if (type == null) {
    //                            continue;
    //                        }
    //
    //                        type.set(stmt, parameterIndex++, (strs[i] == null) ? null : type.valueOf(strs[i]));
    //                    }
    //                }
    //
    //                stmt.addBatch();
    //
    //                result++;
    //
    //                if ((result % batchSize) == 0) {
    //                    JdbcUtil.executeBatch(stmt);
    //
    //                    if (batchIntervalInMillis > 0) {
    //                        N.sleep(batchIntervalInMillis);
    //                    }
    //                }
    //
    //                N.fill(strs, null);
    //            }
    //
    //            if ((result % batchSize) > 0) {
    //                JdbcUtil.executeBatch(stmt);
    //            }
    //        } finally {
    //            Objectory.recycle(br);
    //        }
    //
    //        return result;
    //    }

    /**
     * Imports the data from CSV to database.
     *
     * @param file
     * @param sourceDataSource
     * @param insertSQL the column order in the sql should be consistent with the column order in the CSV file.
     * @param stmtSetter
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long importCSV(final File file, final javax.sql.DataSource sourceDataSource, final String insertSQL,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> stmtSetter) throws SQLException, IOException {
        final Connection conn = sourceDataSource.getConnection();

        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSQL)) {
            return importCSV(file, stmt, stmtSetter);
        } finally {
            JdbcUtil.releaseConnection(conn, sourceDataSource);
        }
    }

    /**
     * Imports the data from CSV to database.
     *
     * @param file
     * @param conn
     * @param insertSQL the column order in the sql should be consistent with the column order in the CSV file.
     * @param stmtSetter
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long importCSV(final File file, final Connection conn, final String insertSQL,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> stmtSetter) throws SQLException, IOException {
        return importCSV(file, 0, Long.MAX_VALUE, conn, insertSQL, JdbcUtil.DEFAULT_BATCH_SIZE, 0, stmtSetter);
    }

    /**
     *
     * @param file
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL
     * @param batchSize
     * @param batchIntervalInMillis
     * @param stmtSetter
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long importCSV(final File file, final long offset, final long count, final Connection conn, final String insertSQL, final int batchSize,
            final long batchIntervalInMillis, final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> stmtSetter)
            throws SQLException, IOException {
        return importCSV(file, offset, count, Fn.<String[]> alwaysTrue(), conn, insertSQL, batchSize, batchIntervalInMillis, stmtSetter);
    }

    /**
     *
     * @param <E>
     * @param file
     * @param offset
     * @param count
     * @param filter
     * @param conn
     * @param insertSQL the column order in the sql should be consistent with the column order in the CSV file.
     * @param batchSize
     * @param batchIntervalInMillis
     * @param stmtSetter
     * @return
     * @throws SQLException
     * @throws IOException
     * @throws E
     */
    public static <E extends Exception> long importCSV(final File file, final long offset, final long count,
            final Throwables.Predicate<? super String[], E> filter, final Connection conn, final String insertSQL, final int batchSize,
            final long batchIntervalInMillis, final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> stmtSetter)
            throws SQLException, IOException, E {

        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSQL)) {
            return importCSV(file, offset, count, filter, stmt, batchSize, batchIntervalInMillis, stmtSetter);
        }
    }

    /**
     *
     * @param file
     * @param stmt
     * @param stmtSetter
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long importCSV(final File file, final PreparedStatement stmt,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> stmtSetter) throws SQLException, IOException {
        return importCSV(file, 0, Long.MAX_VALUE, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, stmtSetter);
    }

    /**
     *
     * @param file
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchIntervalInMillis
     * @param stmtSetter
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long importCSV(final File file, final long offset, final long count, final PreparedStatement stmt, final int batchSize,
            final long batchIntervalInMillis, final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> stmtSetter)
            throws SQLException, IOException {
        return importCSV(file, offset, count, Fn.<String[]> alwaysTrue(), stmt, batchSize, batchIntervalInMillis, stmtSetter);
    }

    /**
     * Imports the data from CSV to database.
     *
     * @param <E>
     * @param file
     * @param offset
     * @param count
     * @param filter
     * @param stmt the column order in the sql should be consistent with the column order in the CSV file.
     * @param batchSize
     * @param batchIntervalInMillis
     * @param stmtSetter
     * @return
     * @throws SQLException
     * @throws IOException
     * @throws E
     */
    public static <E extends Exception> long importCSV(final File file, final long offset, final long count,
            final Throwables.Predicate<? super String[], E> filter, final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> stmtSetter) throws SQLException, IOException, E {
        try (Reader reader = IOUtil.newFileReader(file)) {
            return importCSV(reader, offset, count, filter, stmt, batchSize, batchIntervalInMillis, stmtSetter);
        }
    }

    /**
     * Imports the data from CSV to database.
     *
     * @param is
     * @param sourceDataSource
     * @param insertSQL the column order in the sql should be consistent with the column order in the CSV file.
     * @param stmtSetter
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long importCSV(final InputStream is, final javax.sql.DataSource sourceDataSource, final String insertSQL,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> stmtSetter) throws SQLException, IOException {
        final Connection conn = sourceDataSource.getConnection();

        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSQL)) {
            return importCSV(is, stmt, stmtSetter);
        } finally {
            JdbcUtil.releaseConnection(conn, sourceDataSource);
        }
    }

    /**
     *
     * @param is
     * @param stmt
     * @param stmtSetter
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long importCSV(final InputStream is, final PreparedStatement stmt,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> stmtSetter) throws SQLException, IOException {
        return importCSV(is, 0, Long.MAX_VALUE, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, stmtSetter);
    }

    /**
     *
     * @param is
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchIntervalInMillis
     * @param stmtSetter
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long importCSV(final InputStream is, final long offset, final long count, final PreparedStatement stmt, final int batchSize,
            final long batchIntervalInMillis, final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> stmtSetter)
            throws SQLException, IOException {
        return importCSV(is, offset, count, Fn.<String[]> alwaysTrue(), stmt, batchSize, batchIntervalInMillis, stmtSetter);
    }

    /**
     * Imports the data from CSV to database.
     *
     * @param <E>
     * @param is
     * @param offset
     * @param count
     * @param filter
     * @param stmt the column order in the sql should be consistent with the column order in the CSV file.
     * @param batchSize
     * @param batchIntervalInMillis
     * @param stmtSetter
     * @return
     * @throws SQLException
     * @throws IOException
     * @throws E
     */
    public static <E extends Exception> long importCSV(final InputStream is, long offset, final long count,
            final Throwables.Predicate<? super String[], E> filter, final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> stmtSetter) throws SQLException, IOException, E {
        final Reader reader = IOUtil.newInputStreamReader(is);
        return importCSV(reader, offset, count, filter, stmt, batchSize, batchIntervalInMillis, stmtSetter);
    }

    /**
     * Imports the data from CSV to database.
     *
     * @param reader
     * @param sourceDataSource
     * @param insertSQL the column order in the sql should be consistent with the column order in the CSV file.
     * @param stmtSetter
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long importCSV(final Reader reader, final javax.sql.DataSource sourceDataSource, final String insertSQL,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> stmtSetter) throws SQLException, IOException {
        final Connection conn = sourceDataSource.getConnection();

        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSQL)) {
            return importCSV(reader, stmt, stmtSetter);
        } finally {
            JdbcUtil.releaseConnection(conn, sourceDataSource);
        }
    }

    /**
     *
     * @param reader
     * @param stmt
     * @param stmtSetter
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long importCSV(final Reader reader, final PreparedStatement stmt,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> stmtSetter) throws SQLException, IOException {
        return importCSV(reader, 0, Long.MAX_VALUE, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, stmtSetter);
    }

    /**
     *
     * @param reader
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchIntervalInMillis
     * @param stmtSetter
     * @return
     * @throws SQLException
     * @throws IOException
     */
    @SuppressWarnings({ "unchecked" })
    public static long importCSV(final Reader reader, long offset, final long count, final PreparedStatement stmt, final int batchSize,
            final long batchIntervalInMillis, final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> stmtSetter)
            throws SQLException, IOException {
        return importCSV(reader, offset, count, Fn.<String[]> alwaysTrue(), stmt, batchSize, batchIntervalInMillis, stmtSetter);
    }

    /**
     * Imports the data from CSV to database.
     *
     * @param <E>
     * @param reader
     * @param offset
     * @param count
     * @param filter
     * @param stmt the column order in the sql should be consistent with the column order in the CSV file.
     * @param batchSize
     * @param batchIntervalInMillis
     * @param stmtSetter
     * @return
     * @throws IllegalArgumentException
     * @throws SQLException
     * @throws IOException
     * @throws E
     */
    @SuppressWarnings({ "unchecked", "resource" })
    public static <E extends Exception> long importCSV(final Reader reader, long offset, final long count,
            final Throwables.Predicate<? super String[], E> filter, final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> stmtSetter)
            throws IllegalArgumentException, SQLException, IOException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchIntervalInMillis >= 0, "'batchSize'=%s must be greater than 0 and 'batchIntervalInMillis'=%s can't be negative",
                batchSize, batchIntervalInMillis);

        final PreparedQuery stmtForSetter = new PreparedQuery(stmt);
        final Function<String, String[]> headerParser = CSVUtil.getCurrentHeaderParser();
        final BiConsumer<String, String[]> lineParser = CSVUtil.getCurrentLineParser();
        long result = 0;
        final BufferedReader br = Objectory.createBufferedReader(reader);

        try {
            String line = br.readLine();
            final String[] titles = headerParser.apply(line);

            while (offset-- > 0 && br.readLine() != null) {
                // continue
            }

            final String[] output = new String[titles.length];

            while (result < count && (line = br.readLine()) != null) {
                lineParser.accept(line, output);

                if (filter != null && !filter.test(output)) {
                    continue;
                }

                stmtSetter.accept(stmtForSetter, output);
                stmtForSetter.addBatch();

                if ((++result % batchSize) == 0) {
                    JdbcUtil.executeBatch(stmt);

                    if (batchIntervalInMillis > 0) {
                        N.sleep(batchIntervalInMillis);
                    }
                }

                N.fill(output, null);
            }

            if ((result % batchSize) > 0) {
                JdbcUtil.executeBatch(stmt);
            }
        } finally {
            Objectory.recycle(br);
        }

        return result;
    }

    /**
     * Exports the data from database to CVS. Title will be added at the first line and columns will be quoted.
     * <br />
     * Each line in the output file/Writer is an array of JSON String without root bracket.
     *
     * @param out
     * @param sourceDataSource
     * @param querySQL
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long exportCSV(final File out, final javax.sql.DataSource sourceDataSource, final String querySQL) throws SQLException, IOException {
        final Connection conn = sourceDataSource.getConnection();

        try {
            return exportCSV(out, conn, querySQL);
        } finally {
            JdbcUtil.releaseConnection(conn, sourceDataSource);
        }
    }

    /**
     * Exports the data from database to CVS. Title will be added at the first line and columns will be quoted.
     * <br />
     * Each line in the output file/Writer is an array of JSON String without root bracket.
     *
     * @param out
     * @param conn
     * @param querySQL
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long exportCSV(final File out, final Connection conn, final String querySQL) throws SQLException, IOException {
        return exportCSV(out, conn, querySQL, 0, Long.MAX_VALUE, true, true);
    }

    /**
     * Exports the data from database to CVS.
     * <br />
     * Each line in the output file/Writer is an array of JSON String without root bracket.
     *
     * @param out
     * @param conn
     * @param querySQL
     * @param offset
     * @param count
     * @param writeTitle
     * @param quoted
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long exportCSV(final File out, final Connection conn, final String querySQL, final long offset, final long count, final boolean writeTitle,
            final boolean quoted) throws SQLException, IOException {
        return exportCSV(out, conn, querySQL, null, offset, count, writeTitle, quoted);
    }

    /**
     * Exports the data from database to CVS.
     * <br />
     * Each line in the output file/Writer is an array of JSON String without root bracket.
     *
     * @param out
     * @param conn
     * @param querySQL
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param writeTitle
     * @param quoted
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long exportCSV(final File out, final Connection conn, final String querySQL, final Collection<String> selectColumnNames, final long offset,
            final long count, final boolean writeTitle, final boolean quoted) throws SQLException, IOException {
        final ParsedSql sql = ParsedSql.parse(querySQL);

        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, sql.getParameterizedSql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {

            setFetchForBigResult(conn, stmt);

            return exportCSV(out, stmt, selectColumnNames, offset, count, writeTitle, quoted);
        }
    }

    /**
     * Exports the data from database to CVS. Title will be added at the first line and columns will be quoted.
     * <br />
     * Each line in the output file/Writer is an array of JSON String without root bracket.
     *
     * @param out
     * @param stmt
     * @return
     * @throws SQLException
     * @throws IOException
     */
    @SuppressWarnings("unchecked")
    public static long exportCSV(final File out, final PreparedStatement stmt) throws SQLException, IOException {
        return exportCSV(out, stmt, 0, Long.MAX_VALUE, true, true);
    }

    /**
     * Exports the data from database to CVS.
     * <br />
     * Each line in the output file/Writer is an array of JSON String without root bracket.
     *
     * @param out
     * @param stmt
     * @param offset
     * @param count
     * @param writeTitle
     * @param quoted
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long exportCSV(final File out, final PreparedStatement stmt, final long offset, final long count, final boolean writeTitle,
            final boolean quoted) throws SQLException, IOException {
        return exportCSV(out, stmt, null, offset, count, writeTitle, quoted);
    }

    /**
     * Exports the data from database to CVS.
     * <br />
     * Each line in the output file/Writer is an array of JSON String without root bracket.
     *
     * @param out
     * @param stmt
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param writeTitle
     * @param quoted
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long exportCSV(final File out, final PreparedStatement stmt, final Collection<String> selectColumnNames, final long offset, final long count,
            final boolean writeTitle, final boolean quoted) throws SQLException, IOException {
        ResultSet rs = null;

        try {
            rs = JdbcUtil.executeQuery(stmt);
            // rs.setFetchSize(DEFAULT_FETCH_SIZE);

            return exportCSV(out, rs, selectColumnNames, offset, count, writeTitle, quoted);
        } finally {
            JdbcUtil.closeQuietly(rs);
        }
    }

    /**
     * Exports the data from database to CVS. Title will be added at the first line and columns will be quoted.
     * <br />
     * Each line in the output file/Writer is an array of JSON String without root bracket.
     *
     * @param out
     * @param rs
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long exportCSV(final File out, final ResultSet rs) throws SQLException, IOException {
        return exportCSV(out, rs, 0, Long.MAX_VALUE, true, true);
    }

    /**
     * Exports the data from database to CVS.
     * <br />
     * Each line in the output file/Writer is an array of JSON String without root bracket.
     *
     * @param out
     * @param rs
     * @param offset
     * @param count
     * @param writeTitle
     * @param quoted
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long exportCSV(final File out, final ResultSet rs, final long offset, final long count, final boolean writeTitle, final boolean quoted)
            throws SQLException, IOException {
        return exportCSV(out, rs, null, offset, count, writeTitle, quoted);
    }

    /**
     * Each line in the output file/Writer is an array of JSON String without root bracket.
     *
     * @param out
     * @param rs
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param writeTitle
     * @param quoted
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long exportCSV(final File out, final ResultSet rs, final Collection<String> selectColumnNames, final long offset, final long count,
            final boolean writeTitle, final boolean quoted) throws SQLException, IOException {
        if (!out.exists()) {
            out.createNewFile(); //NOSONAR
        }

        try (OutputStream os = new FileOutputStream(out)) {
            long result = exportCSV(os, rs, selectColumnNames, offset, count, writeTitle, quoted);

            os.flush();

            return result;
        }
    }

    /**
     * Exports the data from database to CVS. Title will be added at the first line and columns will be quoted.
     * <br />
     * Each line in the output file/Writer is an array of JSON String without root bracket.
     *
     * @param out
     * @param sourceDataSource
     * @param querySQL
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long exportCSV(final OutputStream out, final javax.sql.DataSource sourceDataSource, final String querySQL) throws SQLException, IOException {
        final Connection conn = sourceDataSource.getConnection();

        try {
            return exportCSV(out, conn, querySQL);
        } finally {
            JdbcUtil.releaseConnection(conn, sourceDataSource);
        }
    }

    /**
     * Exports the data from database to CVS. Title will be added at the first line and columns will be quoted.
     * <br />
     * Each line in the output file/Writer is an array of JSON String without root bracket.
     *
     * @param out
     * @param conn
     * @param querySQL
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long exportCSV(final OutputStream out, final Connection conn, final String querySQL) throws SQLException, IOException {
        final ParsedSql sql = ParsedSql.parse(querySQL);

        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, sql.getParameterizedSql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                ResultSet rs = JdbcUtil.executeQuery(stmt)) {

            setFetchForBigResult(conn, stmt);

            return exportCSV(out, rs);
        }
    }

    /**
     * Exports the data from database to CVS. Title will be added at the first line and columns will be quoted.
     * <br />
     * Each line in the output file/Writer is an array of JSON String without root bracket.
     *
     * @param out
     * @param rs
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long exportCSV(final OutputStream out, final ResultSet rs) throws SQLException, IOException {
        return exportCSV(out, rs, 0, Long.MAX_VALUE, true, true);
    }

    /**
     * Exports the data from database to CVS.
     * <br />
     * Each line in the output file/Writer is an array of JSON String without root bracket.
     *
     * @param out
     * @param rs
     * @param offset
     * @param count
     * @param writeTitle
     * @param quoted
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long exportCSV(final OutputStream out, final ResultSet rs, final long offset, final long count, final boolean writeTitle,
            final boolean quoted) throws SQLException, IOException {
        return exportCSV(out, rs, null, offset, count, writeTitle, quoted);
    }

    /**
     * Exports the data from database to CVS.
     * <br />
     * Each line in the output file/Writer is an array of JSON String without root bracket.
     *
     * @param out
     * @param rs
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param writeTitle
     * @param quoted
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long exportCSV(final OutputStream out, final ResultSet rs, final Collection<String> selectColumnNames, final long offset, final long count,
            final boolean writeTitle, final boolean quoted) throws SQLException, IOException {

        Writer writer = IOUtil.newOutputStreamWriter(out);

        long result = exportCSV(writer, rs, selectColumnNames, offset, count, writeTitle, quoted);

        writer.flush();

        return result;
    }

    /**
     * Exports the data from database to CVS. Title will be added at the first line and columns will be quoted.
     * <br />
     * Each line in the output file/Writer is an array of JSON String without root bracket.
     *
     * @param out
     * @param sourceDataSource
     * @param querySQL
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long exportCSV(final Writer out, final javax.sql.DataSource sourceDataSource, final String querySQL) throws SQLException, IOException {
        final Connection conn = sourceDataSource.getConnection();

        try {
            return exportCSV(out, conn, querySQL);
        } finally {
            JdbcUtil.releaseConnection(conn, sourceDataSource);
        }
    }

    /**
     * Exports the data from database to CVS. Title will be added at the first line and columns will be quoted.
     * <br />
     * Each line in the output file/Writer is an array of JSON String without root bracket.
     *
     * @param out
     * @param conn
     * @param querySQL
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long exportCSV(final Writer out, final Connection conn, final String querySQL) throws SQLException, IOException {
        final ParsedSql sql = ParsedSql.parse(querySQL);

        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, sql.getParameterizedSql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                ResultSet rs = JdbcUtil.executeQuery(stmt)) {

            setFetchForBigResult(conn, stmt);

            return exportCSV(out, rs);
        }
    }

    /**
     * Exports the data from database to CVS. Title will be added at the first line and columns will be quoted.
     * <br />
     * Each line in the output file/Writer is an array of JSON String without root bracket.
     *
     * @param out
     * @param rs
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long exportCSV(final Writer out, final ResultSet rs) throws SQLException, IOException {
        return exportCSV(out, rs, 0, Long.MAX_VALUE, true, true);
    }

    /**
     * Exports the data from database to CVS.
     * <br />
     * Each line in the output file/Writer is an array of JSON String without root bracket.
     *
     * @param out
     * @param rs
     * @param offset
     * @param count
     * @param writeTitle
     * @param quoted
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long exportCSV(final Writer out, final ResultSet rs, final long offset, final long count, final boolean writeTitle, final boolean quoted)
            throws SQLException, IOException {
        return exportCSV(out, rs, null, offset, count, writeTitle, quoted);
    }

    /**
     * Exports the data from database to CVS.
     * <br />
     * Each line in the output file/Writer is an array of JSON String without root bracket.
     *
     * @param out
     * @param rs
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param writeTitle
     * @param quoted
     * @return
     * @throws IllegalArgumentException
     * @throws SQLException
     * @throws IOException
     */
    @SuppressWarnings("deprecation")
    public static long exportCSV(final Writer out, final ResultSet rs, final Collection<String> selectColumnNames, final long offset, final long count,
            final boolean writeTitle, final boolean quoted) throws IllegalArgumentException, SQLException, IOException {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);

        final JSONSerializationConfig config = JSC.create();
        config.setDateTimeFormat(DateTimeFormat.ISO_8601_TIMESTAMP);

        if (quoted) {
            config.setCharQuotation(WD._QUOTATION_D);
            config.setStringQuotation(WD._QUOTATION_D);
        } else {
            config.setCharQuotation((char) 0);
            config.setStringQuotation((char) 0);
        }

        long result = 0;
        final Type<Object> strType = N.typeOf(String.class);
        final BufferedJSONWriter bw = out instanceof BufferedJSONWriter ? (BufferedJSONWriter) out : Objectory.createBufferedJSONWriter(out);

        try {
            final boolean checkDateType = JdbcUtil.checkDateType(rs);

            final ResultSetMetaData rsmd = rs.getMetaData();
            final int columnCount = rsmd.getColumnCount();
            final String[] columnNames = new String[columnCount];
            final Set<String> columnNameSet = selectColumnNames == null ? null : N.newHashSet(selectColumnNames);
            String label = null;

            for (int i = 0; i < columnCount; i++) {
                label = JdbcUtil.getColumnLabel(rsmd, i + 1);

                if (columnNameSet == null || columnNameSet.remove(label)) {
                    columnNames[i] = label;
                }
            }

            if (columnNameSet != null && columnNameSet.size() > 0) {
                throw new IllegalArgumentException(columnNameSet + " are not included in query result");
            }

            if (writeTitle) {
                for (int i = 0, j = 0, len = columnNames.length; i < len; i++) {
                    if (columnNames[i] == null) {
                        continue;
                    }

                    if (j++ > 0) {
                        bw.write(ELEMENT_SEPARATOR_CHAR_ARRAY);
                    }

                    if (quoted) {
                        bw.write(WD._QUOTATION_D);
                        bw.write(columnNames[i]);
                        bw.write(WD._QUOTATION_D);
                    } else {
                        bw.write(columnNames[i]);
                    }
                }

                bw.write(IOUtil.LINE_SEPARATOR);
            }

            final Type<Object>[] typeArray = new Type[columnCount];
            Type<Object> type = null;
            Object value = null;

            if (offset > 0) {
                JdbcUtil.skip(rs, offset);
            }

            while (result < count && rs.next()) {
                if (result++ > 0) {
                    bw.write(IOUtil.LINE_SEPARATOR);
                }

                for (int i = 0, j = 0; i < columnCount; i++) {
                    if (columnNames[i] == null) {
                        continue;
                    }

                    if (j++ > 0) {
                        bw.write(ELEMENT_SEPARATOR_CHAR_ARRAY);
                    }

                    type = typeArray[i];

                    if (type == null) {
                        value = JdbcUtil.getColumnValue(rs, i + 1, checkDateType);

                        if (value == null) {
                            bw.write(NULL_CHAR_ARRAY);
                        } else {
                            type = N.typeOf(value.getClass());
                            typeArray[i] = type;

                            if (type.isSerializable()) {
                                type.writeCharacter(bw, value, config);
                            } else {
                                type.writeCharacter(bw, CSVUtil.jsonParser.serialize(value, config), config);
                            }
                        }
                    } else {
                        if (type.isSerializable()) {
                            type.writeCharacter(bw, type.get(rs, i + 1), config);
                        } else {
                            strType.writeCharacter(bw, CSVUtil.jsonParser.serialize(type.get(rs, i + 1), config), config);
                        }
                    }
                }
            }

            bw.flush();
        } finally {
            if (bw != out) {
                Objectory.recycle(bw);
            }
        }

        return result;
    }

    private static final Supplier<Throwables.BiConsumer<PreparedQuery, ResultSet, SQLException>> supplierOfStmtSetterByRS = new Supplier<>() { // NOSONAR
        @Override
        public Throwables.BiConsumer<PreparedQuery, ResultSet, SQLException> get() {
            return new Throwables.BiConsumer<>() {
                private int columnCount = 0;

                @Override
                public void accept(final PreparedQuery stmt, final ResultSet rs) throws SQLException {
                    if (columnCount == 0) {
                        columnCount = rs.getMetaData().getColumnCount();
                    }

                    for (int columnIndex = 1; columnIndex <= columnCount; columnIndex++) {
                        stmt.setObject(columnIndex, JdbcUtil.getColumnValue(rs, columnIndex));
                    }
                }
            };
        }
    };

    /**
     *
     *
     * @param columnGetterForAll
     * @return
     */
    @Beta
    @SequentialOnly
    @Stateful
    public static Throwables.BiConsumer<PreparedQuery, ResultSet, SQLException> createParamSetter(final ColumnGetter<?> columnGetterForAll) {
        return new Throwables.BiConsumer<>() {
            private int columnCount = -1;

            @Override
            public void accept(final PreparedQuery stmt, final ResultSet rs) throws SQLException {
                if (columnCount < 0) {
                    columnCount = JdbcUtil.getColumnCount(rs);
                }

                for (int i = 1; i <= columnCount; i++) {
                    stmt.setObject(i, columnGetterForAll.apply(rs, i));
                }
            }
        };
    }

    /**
    *
    * @param sourceDataSource
    * @param targetDataSource
    * @param tableName
    * @return
    * @throws SQLException
    */
    public static long copy(final javax.sql.DataSource sourceDataSource, final javax.sql.DataSource targetDataSource, final String tableName)
            throws SQLException {
        return copy(sourceDataSource, targetDataSource, tableName, tableName);
    }

    /**
    *
    * @param sourceDataSource
    * @param targetDataSource
    * @param sourceTableName
    * @param targetTableName
    * @return
    * @throws SQLException
    */
    public static long copy(final javax.sql.DataSource sourceDataSource, final javax.sql.DataSource targetDataSource, final String sourceTableName,
            final String targetTableName) throws SQLException {
        return copy(sourceDataSource, targetDataSource, sourceTableName, targetTableName, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
    *
    * @param sourceDataSource
    * @param targetDataSource
    * @param sourceTableName
    * @param targetTableName
    * @param batchSize
    * @return
    * @throws SQLException
    */
    public static long copy(final javax.sql.DataSource sourceDataSource, final javax.sql.DataSource targetDataSource, final String sourceTableName,
            final String targetTableName, final int batchSize) throws SQLException {
        String selectSql = null;
        String insertSql = null;
        Connection conn = null;

        try {
            conn = sourceDataSource.getConnection();

            selectSql = CodeGenerationUtil.generateSelectSql(conn, sourceTableName);
            insertSql = CodeGenerationUtil.generateInsertSql(conn, sourceTableName);

            if (!sourceTableName.equals(targetTableName)) {
                insertSql = Strings.replaceFirstIgnoreCase(insertSql, sourceTableName, targetTableName);
            }
        } finally {
            JdbcUtil.releaseConnection(conn, sourceDataSource);
        }

        return copy(sourceDataSource, selectSql, N.max(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT, batchSize), targetDataSource, insertSql, batchSize);
    }

    /**
     *
     * @param sourceDataSource
     * @param targetDataSource
     * @param sourceTableName
     * @param targetTableName
     * @param selectColumnNames
     * @return
     * @throws SQLException
     */
    public static long copy(final javax.sql.DataSource sourceDataSource, final javax.sql.DataSource targetDataSource, final String sourceTableName,
            final String targetTableName, final Collection<String> selectColumnNames) throws SQLException {
        return copy(sourceDataSource, targetDataSource, sourceTableName, targetTableName, selectColumnNames, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
    *
    * @param sourceDataSource
    * @param targetDataSource
    * @param sourceTableName
    * @param targetTableName
    * @param selectColumnNames
    * @param batchSize
    * @return
    * @throws SQLException
    */
    public static long copy(final javax.sql.DataSource sourceDataSource, final javax.sql.DataSource targetDataSource, final String sourceTableName,
            final String targetTableName, final Collection<String> selectColumnNames, final int batchSize) throws SQLException {
        String selectSql = null;
        String insertSql = null;
        Connection conn = null;

        try {
            conn = sourceDataSource.getConnection();

            selectSql = generateSelectSql(conn, sourceTableName, selectColumnNames);
            insertSql = generateInsertSql(conn, sourceTableName, selectColumnNames);

            if (!sourceTableName.equals(targetTableName)) {
                insertSql = Strings.replaceFirstIgnoreCase(insertSql, sourceTableName, targetTableName);
            }
        } finally {
            JdbcUtil.releaseConnection(conn, sourceDataSource);
        }

        return copy(sourceDataSource, selectSql, N.max(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT, batchSize), targetDataSource, insertSql, batchSize);
    }

    /**
    *
    * @param sourceDataSource
    * @param selectSql
    * @param targetDataSource
    * @param insertSql
    * @return
    * @throws SQLException
    */
    public static long copy(final javax.sql.DataSource sourceDataSource, final String selectSql, final javax.sql.DataSource targetDataSource,
            final String insertSql) throws SQLException {
        return copy(sourceDataSource, selectSql, JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT, targetDataSource, insertSql, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
    *
    * @param sourceDataSource
    * @param selectSql
    * @param fetchSize it should be bigger than {@code batchSize}. It can be x times {@code batchSize}, depends on how big one record is and how much memory is available.
    * @param targetDataSource
    * @param insertSql
    * @param batchSize
    * @return
    * @throws SQLException
    */
    public static long copy(final javax.sql.DataSource sourceDataSource, final String selectSql, final int fetchSize,
            final javax.sql.DataSource targetDataSource, final String insertSql, final int batchSize) throws SQLException {
        return copy(sourceDataSource, selectSql, fetchSize, targetDataSource, insertSql, batchSize, supplierOfStmtSetterByRS.get());
    }

    /**
    *
    * @param sourceDataSource
    * @param selectSql
    * @param targetDataSource
    * @param insertSql
    * @param stmtSetter
    * @return
    * @throws SQLException
    */
    public static long copy(final javax.sql.DataSource sourceDataSource, final String selectSql, final javax.sql.DataSource targetDataSource,
            final String insertSql, final Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> stmtSetter) throws SQLException {
        return copy(sourceDataSource, selectSql, JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT, targetDataSource, insertSql, JdbcUtil.DEFAULT_BATCH_SIZE,
                stmtSetter);
    }

    /**
    *
    * @param sourceDataSource
    * @param selectSql
    * @param fetchSize it should be bigger than {@code batchSize}. It can be x times {@code batchSize}, depends on how big one record is and how much memory is available.
    * @param targetDataSource
    * @param insertSql
    * @param batchSize
    * @param stmtSetter
    * @return
    * @throws SQLException
    */
    public static long copy(final javax.sql.DataSource sourceDataSource, final String selectSql, final int fetchSize,
            final javax.sql.DataSource targetDataSource, final String insertSql, final int batchSize,
            final Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> stmtSetter) throws SQLException {
        Connection sourceConn = null;
        Connection targetConn = null;

        try {
            sourceConn = JdbcUtil.getConnection(sourceDataSource);
            targetConn = JdbcUtil.getConnection(targetDataSource);

            return copy(sourceConn, selectSql, fetchSize, targetConn, insertSql, batchSize, stmtSetter);
        } finally {
            if (sourceConn != null) {
                JdbcUtil.releaseConnection(sourceConn, sourceDataSource);
            }

            if (targetConn != null) {
                JdbcUtil.releaseConnection(targetConn, targetDataSource);
            }
        }
    }

    /**
    *
    * @param sourceConn
    * @param targetConn
    * @param tableName
    * @return
    * @throws SQLException
    */
    public static long copy(final Connection sourceConn, final Connection targetConn, final String tableName) throws SQLException {
        return copy(sourceConn, targetConn, tableName, tableName);
    }

    /**
    *
    * @param sourceConn
    * @param targetConn
    * @param sourceTableName
    * @param targetTableName
    * @return
    * @throws SQLException
    */
    public static long copy(final Connection sourceConn, final Connection targetConn, final String sourceTableName, final String targetTableName)
            throws SQLException {
        return copy(sourceConn, targetConn, sourceTableName, targetTableName, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
    *
    * @param sourceConn
    * @param targetConn
    * @param sourceTableName
    * @param targetTableName
    * @param batchSize
    * @return
    * @throws SQLException
    */
    public static long copy(final Connection sourceConn, final Connection targetConn, final String sourceTableName, final String targetTableName,
            final int batchSize) throws SQLException {
        final String selectSql = CodeGenerationUtil.generateSelectSql(sourceConn, sourceTableName);
        String insertSql = CodeGenerationUtil.generateInsertSql(sourceConn, sourceTableName);

        if (!sourceTableName.equals(targetTableName)) {
            insertSql = Strings.replaceFirstIgnoreCase(insertSql, sourceTableName, targetTableName);
        }

        return copy(sourceConn, selectSql, N.max(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT, batchSize), targetConn, insertSql, batchSize);
    }

    /**
    *
    * @param sourceConn
    * @param targetConn
    * @param sourceTableName
    * @param targetTableName
    * @param selectColumnNames
    * @return
    * @throws SQLException
    */
    public static long copy(final Connection sourceConn, final Connection targetConn, final String sourceTableName, final String targetTableName,
            final Collection<String> selectColumnNames) throws SQLException {
        return copy(sourceConn, targetConn, sourceTableName, targetTableName, selectColumnNames, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
    *
    * @param sourceConn
    * @param targetConn
    * @param sourceTableName
    * @param targetTableName
    * @param selectColumnNames
    * @param batchSize
    * @return
    * @throws SQLException
    */
    public static long copy(final Connection sourceConn, final Connection targetConn, final String sourceTableName, final String targetTableName,
            final Collection<String> selectColumnNames, final int batchSize) throws SQLException {
        final String selectSql = generateSelectSql(sourceConn, sourceTableName, selectColumnNames);
        String insertSql = generateInsertSql(sourceConn, sourceTableName, selectColumnNames);

        if (!sourceTableName.equals(targetTableName)) {
            insertSql = Strings.replaceFirstIgnoreCase(insertSql, sourceTableName, targetTableName);
        }

        return copy(sourceConn, selectSql, N.max(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT, batchSize), targetConn, insertSql, batchSize);
    }

    private static String generateSelectSql(final Connection conn, final String tableName, final Collection<String> selectColumnNames) {
        if (N.isEmpty(selectColumnNames)) {
            return CodeGenerationUtil.generateSelectSql(conn, tableName);
        }

        final StringBuilder sb = new StringBuilder();

        sb.append(WD.SELECT).append(WD._SPACE);

        final Iterator<String> iter = selectColumnNames.iterator();
        final int lastIdx = selectColumnNames.size() - 1;
        int cnt = 0;

        while (iter.hasNext() && cnt++ < lastIdx) {
            sb.append(iter.next()).append(WD.COMMA_SPACE);
        }

        sb.append(iter.next()).append(WD._SPACE).append(WD.FROM).append(WD._SPACE).append(tableName);

        return sb.toString();
    }

    private static String generateInsertSql(final Connection conn, final String tableName, final Collection<String> selectColumnNames) {
        if (N.isEmpty(selectColumnNames)) {
            return CodeGenerationUtil.generateInsertSql(conn, tableName);
        }

        final StringBuilder sb = new StringBuilder();

        sb.append(WD.INSERT).append(WD._SPACE).append(WD.INTO).append(WD._SPACE).append(tableName).append(WD._PARENTHESES_L);

        final Iterator<String> iter = selectColumnNames.iterator();
        final int lastIdx = selectColumnNames.size() - 1;
        int cnt = 0;

        while (iter.hasNext() && cnt++ < lastIdx) {
            sb.append(iter.next()).append(WD.COMMA_SPACE);
        }

        sb.append(iter.next())
                .append(WD._PARENTHESES_R)
                .append(WD._SPACE)
                .append(WD.VALUES)
                .append(WD._SPACE)
                .append(Strings.repeat("?", selectColumnNames.size(), ", ", "(", ")"));

        return sb.toString();
    }

    /**
     *
     * @param sourceConn
     * @param selectSql
     * @param targetConn
     * @param insertSql
     * @return
     * @throws SQLException
     */
    public static long copy(final Connection sourceConn, final String selectSql, final Connection targetConn, final String insertSql) throws SQLException {
        return copy(sourceConn, selectSql, JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT, targetConn, insertSql, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     *
     * @param sourceConn
     * @param selectSql
     * @param fetchSize it should be bigger than {@code batchSize}. It can be x times {@code batchSize}, depends on how big one record is and how much memory is available.
     * @param targetConn
     * @param insertSql
     * @param batchSize
     * @return
     * @throws SQLException
     */
    public static long copy(final Connection sourceConn, final String selectSql, final int fetchSize, final Connection targetConn, final String insertSql,
            final int batchSize) throws SQLException {
        return copy(sourceConn, selectSql, fetchSize, targetConn, insertSql, batchSize, supplierOfStmtSetterByRS.get());
    }

    /**
     *
     *
     * @param sourceConn
     * @param selectSql
     * @param targetConn
     * @param insertSql
     * @param stmtSetter
     * @return
     * @throws SQLException
     */
    public static long copy(final Connection sourceConn, final String selectSql, final Connection targetConn, final String insertSql,
            final Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> stmtSetter) throws SQLException {
        return copy(sourceConn, selectSql, JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT, targetConn, insertSql, JdbcUtil.DEFAULT_BATCH_SIZE, stmtSetter);
    }

    /**
     *
     * @param sourceConn
     * @param selectSql
     * @param fetchSize it should be bigger than {@code batchSize}. It can be x times {@code batchSize}, depends on how big one record is and how much memory is available.
     * @param targetConn
     * @param insertSql
     * @param batchSize
     * @param stmtSetter
     * @return
     * @throws SQLException
     */
    public static long copy(final Connection sourceConn, final String selectSql, final int fetchSize, final Connection targetConn, final String insertSql,
            final int batchSize, final Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> stmtSetter) throws SQLException {
        return copy(sourceConn, selectSql, fetchSize, 0, Long.MAX_VALUE, targetConn, insertSql, batchSize, 0, stmtSetter);
    }

    /**
     *
     * @param sourceConn
     * @param selectSql
     * @param fetchSize it should be bigger than {@code batchSize}. It can be x times {@code batchSize}, depends on how big one record is and how much memory is available.
     * @param offset
     * @param count
     * @param targetConn
     * @param insertSql
     * @param batchSize
     * @param batchIntervalInMillis
     * @param stmtSetter
     * @return
     * @throws SQLException
     */
    public static long copy(final Connection sourceConn, final String selectSql, final int fetchSize, final long offset, final long count,
            final Connection targetConn, final String insertSql, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> stmtSetter) throws SQLException {
        PreparedStatement selectStmt = null;
        PreparedStatement insertStmt = null;

        int result = 0;

        try {
            selectStmt = JdbcUtil.prepareStatement(sourceConn, selectSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            setFetchForBigResult(sourceConn, selectStmt, fetchSize);

            insertStmt = JdbcUtil.prepareStatement(targetConn, insertSql);

            copy(selectStmt, offset, count, insertStmt, batchSize, batchIntervalInMillis, stmtSetter);
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
     * @throws SQLException
     */
    public static long copy(final PreparedStatement selectStmt, final PreparedStatement insertStmt,
            final Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> stmtSetter) throws SQLException {
        return copy(selectStmt, 0, Long.MAX_VALUE, insertStmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, stmtSetter);
    }

    /**
     *
     * @param selectStmt
     * @param offset
     * @param count
     * @param insertStmt
     * @param batchSize
     * @param batchIntervalInMillis
     * @param stmtSetter
     * @return
     * @throws SQLException
     */
    public static long copy(final PreparedStatement selectStmt, final long offset, final long count, final PreparedStatement insertStmt, final int batchSize,
            final long batchIntervalInMillis, final Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> stmtSetter)
            throws SQLException {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchIntervalInMillis >= 0, "'batchSize'=%s must be greater than 0 and 'batchIntervalInMillis'=%s can't be negative",
                batchSize, batchIntervalInMillis);

        final Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> stmtSetterForInsert = N.defaultIfNull(stmtSetter,
                supplierOfStmtSetterByRS);
        final PreparedQuery preparedQueryForInsert = new PreparedQuery(insertStmt);

        ResultSet rs = null;

        try {
            rs = JdbcUtil.executeQuery(selectStmt);

            if (offset > 0) {
                JdbcUtil.skip(rs, offset);
            }

            long cnt = 0;

            while (cnt < count && rs.next()) {
                cnt++;

                stmtSetterForInsert.accept(preparedQueryForInsert, rs);
                insertStmt.addBatch();

                if (cnt % batchSize == 0) {
                    JdbcUtil.executeBatch(insertStmt);

                    if (batchIntervalInMillis > 0) {
                        N.sleep(batchIntervalInMillis);
                    }
                }
            }

            if (cnt % batchSize > 0) {
                JdbcUtil.executeBatch(insertStmt);
            }

            // insertStmt.clearBatch(); // clearBatch() is called in JdbcUtil.executeBatch(insertStmt)

            return cnt;
        } finally {
            JdbcUtil.closeQuietly(rs);
        }
    }

    //    private static final Supplier<Jdbc.RowExtractor> supplierOfRowExtractor = new Supplier<>() {
    //        @Override
    //        public RowExtractor get() {
    //            return new Jdbc.RowExtractor() {
    //                private int columnCount = 0;
    //
    //                @Override
    //                public void accept(final ResultSet rs, final Object[] outputRow) throws SQLException {
    //                    if (columnCount == 0) {
    //                        columnCount = rs.getMetaData().getColumnCount();
    //                    }
    //
    //                    for (int i = 0; i < columnCount; i++) {
    //                        outputRow[i] = rs.getObject(i + 1);
    //                    }
    //                }
    //            };
    //        }
    //    };

    //    /**
    //     * Starts another thread to read the records from {@code ResultSet} meanwhile run batch insert in current thread in parallel.
    //     *
    //     * @param sourceDataSource
    //     * @param selectSql
    //     * @param targetDataSource
    //     * @param insertSql
    //     * @param stmtSetter
    //     * @return
    //     * @throws SQLException
    //     */
    //    public static long copyInParallel(final javax.sql.DataSource sourceDataSource, final String selectSql, final javax.sql.DataSource targetDataSource,
    //            final String insertSql, final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> stmtSetter) throws SQLException {
    //        return copyInParallel(sourceDataSource, selectSql, JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT, targetDataSource, insertSql, JdbcUtil.DEFAULT_BATCH_SIZE,
    //                stmtSetter);
    //    }
    //
    //    /**
    //     * Starts another thread to read the records from {@code ResultSet} meanwhile run batch insert in current thread in parallel.
    //     *
    //     * @param sourceDataSource
    //     * @param selectSql
    //     * @param fetchSize it should be bigger than {@code batchSize}. It can be x times {@code batchSize}, depends on how big one record is and how much memory is available.
    //     * @param targetDataSource
    //     * @param insertSql
    //     * @param batchSize
    //     * @param inParallel
    //     * @param stmtSetter
    //     * @return
    //     * @throws SQLException
    //     */
    //    public static long copyInParallel(final javax.sql.DataSource sourceDataSource, final String selectSql, final int fetchSize,
    //            final javax.sql.DataSource targetDataSource, final String insertSql, final int batchSize,
    //            final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> stmtSetter) throws SQLException {
    //        Connection sourceConn = null;
    //        Connection targetConn = null;
    //
    //        try {
    //            sourceConn = JdbcUtil.getConnection(sourceDataSource);
    //            targetConn = JdbcUtil.getConnection(targetDataSource);
    //
    //            return copyInParallel(sourceConn, selectSql, fetchSize, targetConn, insertSql, batchSize, stmtSetter);
    //        } finally {
    //            if (sourceConn != null) {
    //                JdbcUtil.releaseConnection(sourceConn, sourceDataSource);
    //            }
    //
    //            if (targetConn != null) {
    //                JdbcUtil.releaseConnection(targetConn, targetDataSource);
    //            }
    //        }
    //    }
    //
    //    /**
    //     * Starts another thread to read the records from {@code ResultSet} meanwhile run batch insert in current thread in parallel.
    //     *
    //     * @param sourceConn
    //     * @param selectSql
    //     * @param targetConn
    //     * @param insertSql
    //     * @param stmtSetter
    //     * @return
    //     * @throws SQLException
    //     */
    //    public static long copyInParallel(final Connection sourceConn, final String selectSql, final Connection targetConn, final String insertSql,
    //            final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> stmtSetter) throws SQLException {
    //        return copyInParallel(sourceConn, selectSql, JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT, targetConn, insertSql, JdbcUtil.DEFAULT_BATCH_SIZE,
    //                stmtSetter);
    //    }
    //
    //    /**
    //     * Starts another thread to read the records from {@code ResultSet} meanwhile run batch insert in current thread in parallel.
    //     *
    //     * @param sourceConn
    //     * @param selectSql
    //     * @param fetchSize it should be bigger than {@code batchSize}. It can be x times {@code batchSize}, depends on how big one record is and how much memory is available.
    //     * @param targetConn
    //     * @param insertSql
    //     * @param batchSize
    //     * @param stmtSetter
    //     * @return
    //     * @throws SQLException
    //     */
    //    public static long copyInParallel(final Connection sourceConn, final String selectSql, final int fetchSize, final Connection targetConn,
    //            final String insertSql, final int batchSize, final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> stmtSetter)
    //            throws SQLException {
    //        return copyInParallel(sourceConn, selectSql, fetchSize, 0, Long.MAX_VALUE, targetConn, insertSql, batchSize, 0, stmtSetter);
    //    }
    //
    //    /**
    //     * Starts another thread to read the records from {@code ResultSet} meanwhile run batch insert in current thread in parallel.
    //     *
    //     * @param sourceConn
    //     * @param selectSql
    //     * @param fetchSize it should be bigger than {@code batchSize}. It can be x times {@code batchSize}, depends on how big one record is and how much memory is available.
    //     * @param offset
    //     * @param count
    //     * @param targetConn
    //     * @param insertSql
    //     * @param batchSize
    //     * @param batchIntervalInMillis
    //     * @param stmtSetter
    //     * @return
    //     * @throws SQLException
    //     */
    //    public static long copyInParallel(final Connection sourceConn, final String selectSql, final int fetchSize, final long offset, final long count,
    //            final Connection targetConn, final String insertSql, final int batchSize, final long batchIntervalInMillis,
    //            final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> stmtSetter) throws SQLException {
    //        PreparedStatement selectStmt = null;
    //        PreparedStatement insertStmt = null;
    //
    //        int result = 0;
    //
    //        try {
    //            selectStmt = JdbcUtil.prepareStatement(sourceConn, selectSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    //            selectStmt.setFetchSize(fetchSize);
    //
    //            insertStmt = JdbcUtil.prepareStatement(targetConn, insertSql);
    //
    //            copyInParallel(selectStmt, offset, count, insertStmt, batchSize, batchIntervalInMillis, Jdbc.BiRowMapper.TO_ARRAY, stmtSetter);
    //        } finally {
    //            JdbcUtil.closeQuietly(selectStmt);
    //            JdbcUtil.closeQuietly(insertStmt);
    //        }
    //
    //        return result;
    //    }
    //
    //    /**
    //     *
    //     * @param selectStmt
    //     * @param insertStmt
    //     * @param stmtSetter
    //     * @return
    //     * @throws SQLException
    //     */
    //    public static long copyInParallel(final PreparedStatement selectStmt, final PreparedStatement insertStmt,
    //            final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> stmtSetter) throws SQLException {
    //        return copyInParallel(selectStmt, insertStmt, Jdbc.BiRowMapper.TO_ARRAY, stmtSetter);
    //    }
    //
    //    /**
    //     *
    //     * @param selectStmt
    //     * @param insertStmt
    //     * @param rowMapper
    //     * @param stmtSetter
    //     * @return
    //     * @throws SQLException
    //     */
    //    public static <T> long copyInParallel(final PreparedStatement selectStmt, final PreparedStatement insertStmt, final Jdbc.BiRowMapper<? extends T> rowMapper,
    //            final Throwables.BiConsumer<? super PreparedQuery, ? super T, SQLException> stmtSetter) throws SQLException {
    //        return copyInParallel(selectStmt, 0, Long.MAX_VALUE, insertStmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, rowMapper, stmtSetter);
    //    }
    //
    //    /**
    //     *
    //     * @param selectStmt
    //     * @param offset
    //     * @param count
    //     * @param insertStmt
    //     * @param batchSize
    //     * @param batchIntervalInMillis
    //     * @param rowMapper
    //     * @param stmtSetter
    //     * @return
    //     * @throws SQLException
    //     */
    //    public static <T> long copyInParallel(final PreparedStatement selectStmt, final long offset, final long count, final PreparedStatement insertStmt,
    //            final int batchSize, final long batchIntervalInMillis, final Jdbc.BiRowMapper<? extends T> rowMapper,
    //            final Throwables.BiConsumer<? super PreparedQuery, ? super T, SQLException> stmtSetter) throws SQLException {
    //        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
    //        N.checkArgument(batchSize > 0 && batchIntervalInMillis >= 0, "'batchSize'=%s must be greater than 0 and 'batchIntervalInMillis'=%s can't be negative",
    //                batchSize, batchIntervalInMillis);
    //
    //        final PreparedQuery preparedQueryForInsert = new PreparedQuery(insertStmt);
    //
    //        ResultSet rs = null;
    //
    //        try {
    //            rs = JdbcUtil.executeQuery(selectStmt);
    //
    //            if (offset > 0) {
    //                JdbcUtil.skip(rs, offset);
    //            }
    //
    //            long cnt = 0;
    //
    //            while (cnt < count && rs.next()) {
    //                cnt++;
    //
    //                stmtSetter.accept(preparedQueryForInsert, rowMapper.apply(rs)); // TODO
    //                insertStmt.addBatch();
    //
    //                if (cnt % batchSize == 0) {
    //                    JdbcUtil.executeBatch(insertStmt);
    //
    //                    if (batchIntervalInMillis > 0) {
    //                        N.sleep(batchIntervalInMillis);
    //                    }
    //                }
    //            }
    //
    //            if (cnt % batchSize > 0) {
    //                JdbcUtil.executeBatch(insertStmt);
    //            }
    //
    //            // insertStmt.clearBatch(); // clearBatch() is called in JdbcUtil.executeBatch(insertStmt)
    //
    //            return cnt;
    //        } finally {
    //            JdbcUtil.closeQuietly(rs);
    //        }
    //    }

    //    /**
    //     *
    //     * @param <E>
    //     * @param conn
    //     * @param sql
    //     * @param rowParser
    //     * @throws SQLException
    //     * @throws E
    //     */
    //    public static <E extends Exception> void parse(final Connection conn, final String sql, final Throwables.Consumer<? super ResultSet, E> rowParser)
    //            throws SQLException, E {
    //        parse(conn, sql, rowParser, Fn.emptyAction());
    //    }
    //
    //    /**
    //     *
    //     * @param <E>
    //     * @param <E2>
    //     * @param conn
    //     * @param sql
    //     * @param rowParser
    //     * @param onComplete
    //     * @throws SQLException
    //     * @throws E
    //     * @throws E2
    //     */
    //    public static <E extends Exception, E2 extends Exception> void parse(final Connection conn, final String sql,
    //            final Throwables.Consumer<? super ResultSet, E> rowParser, final Throwables.Runnable<E2> onComplete) throws SQLException, E, E2 {
    //        parse(conn, sql, 0, Long.MAX_VALUE, rowParser, onComplete);
    //    }
    //
    //    /**
    //     *
    //     * @param <E>
    //     * @param conn
    //     * @param sql
    //     * @param offset
    //     * @param count
    //     * @param rowParser
    //     * @throws SQLException
    //     * @throws E
    //     */
    //    public static <E extends Exception> void parse(final Connection conn, final String sql, final long offset, final long count,
    //            final Throwables.Consumer<? super ResultSet, E> rowParser) throws SQLException, E {
    //        parse(conn, sql, offset, count, rowParser, Fn.emptyAction());
    //    }
    //
    //    /**
    //     *
    //     * @param <E>
    //     * @param <E2>
    //     * @param conn
    //     * @param sql
    //     * @param offset
    //     * @param count
    //     * @param rowParser
    //     * @param onComplete
    //     * @throws SQLException
    //     * @throws E
    //     * @throws E2
    //     */
    //    public static <E extends Exception, E2 extends Exception> void parse(final Connection conn, final String sql, final long offset, final long count,
    //            final Throwables.Consumer<? super ResultSet, E> rowParser, final Throwables.Runnable<E2> onComplete) throws SQLException, E, E2 {
    //        parse(conn, sql, offset, count, 0, 0, rowParser, onComplete);
    //    }
    //
    //    /**
    //     *
    //     * @param <E>
    //     * @param conn
    //     * @param sql
    //     * @param offset
    //     * @param count
    //     * @param processThreadNum
    //     * @param queueSize
    //     * @param rowParser
    //     * @throws SQLException
    //     * @throws E
    //     */
    //    public static <E extends Exception> void parse(final Connection conn, final String sql, final long offset, final long count, final int processThreadNum,
    //            final int queueSize, final Throwables.Consumer<? super ResultSet, E> rowParser) throws SQLException, E {
    //        parse(conn, sql, offset, count, processThreadNum, queueSize, rowParser, Fn.emptyAction());
    //    }
    //
    //    /**
    //     * Parse the ResultSet obtained by executing query with the specified Connection and sql.
    //     *
    //     * @param <E>
    //     * @param <E2>
    //     * @param conn
    //     * @param sql
    //     * @param offset
    //     * @param count
    //     * @param processThreadNum new threads started to parse/process the lines/records
    //     * @param queueSize size of queue to save the processing records/lines loaded from source data. Default size is 1024.
    //     * @param rowParser
    //     * @param onComplete
    //     * @throws SQLException
    //     * @throws E
    //     * @throws E2
    //     */
    //    public static <E extends Exception, E2 extends Exception> void parse(final Connection conn, final String sql, final long offset, final long count,
    //            final int processThreadNum, final int queueSize, final Throwables.Consumer<? super ResultSet, E> rowParser, final Throwables.Runnable<E2> onComplete)
    //            throws SQLException, E, E2 {
    //        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, sql)) {
    //
    //            setFetchForBigResult(conn, stmt);
    //
    //            parse(stmt, offset, count, processThreadNum, queueSize, rowParser, onComplete);
    //        }
    //    }
    //
    //    /**
    //     *
    //     * @param <E>
    //     * @param stmt
    //     * @param rowParser
    //     * @throws SQLException
    //     * @throws E
    //     */
    //    public static <E extends Exception> void parse(final PreparedStatement stmt, final Throwables.Consumer<? super ResultSet, E> rowParser) throws SQLException, E {
    //        parse(stmt, rowParser, Fn.emptyAction());
    //    }
    //
    //    /**
    //     *
    //     * @param <E>
    //     * @param <E2>
    //     * @param stmt
    //     * @param rowParser
    //     * @param onComplete
    //     * @throws SQLException
    //     * @throws E
    //     * @throws E2
    //     */
    //    public static <E extends Exception, E2 extends Exception> void parse(final PreparedStatement stmt, final Throwables.Consumer<? super ResultSet, E> rowParser,
    //            final Throwables.Runnable<E2> onComplete) throws SQLException, E, E2 {
    //        parse(stmt, 0, Long.MAX_VALUE, rowParser, onComplete);
    //    }
    //
    //    /**
    //     *
    //     * @param <E>
    //     * @param stmt
    //     * @param offset
    //     * @param count
    //     * @param rowParser
    //     * @throws SQLException
    //     * @throws E
    //     */
    //    public static <E extends Exception> void parse(final PreparedStatement stmt, final long offset, final long count,
    //            final Throwables.Consumer<? super ResultSet, E> rowParser) throws SQLException, E {
    //        parse(stmt, offset, count, rowParser, Fn.emptyAction());
    //    }
    //
    //    /**
    //     *
    //     * @param <E>
    //     * @param <E2>
    //     * @param stmt
    //     * @param offset
    //     * @param count
    //     * @param rowParser
    //     * @param onComplete
    //     * @throws SQLException
    //     * @throws E
    //     * @throws E2
    //     */
    //    public static <E extends Exception, E2 extends Exception> void parse(final PreparedStatement stmt, final long offset, final long count,
    //            final Throwables.Consumer<? super ResultSet, E> rowParser, final Throwables.Runnable<E2> onComplete) throws SQLException, E, E2 {
    //        parse(stmt, offset, count, 0, 0, rowParser, onComplete);
    //    }
    //
    //    /**
    //     *
    //     * @param <E>
    //     * @param stmt
    //     * @param offset
    //     * @param count
    //     * @param processThreadNum
    //     * @param queueSize
    //     * @param rowParser
    //     * @throws SQLException
    //     * @throws E
    //     */
    //    public static <E extends Exception> void parse(final PreparedStatement stmt, final long offset, final long count, final int processThreadNum,
    //            final int queueSize, final Throwables.Consumer<? super ResultSet, E> rowParser) throws SQLException, E {
    //        parse(stmt, offset, count, processThreadNum, queueSize, rowParser, Fn.emptyAction());
    //    }
    //
    //    /**
    //     * Parse the ResultSet obtained by executing query with the specified PreparedStatement.
    //     *
    //     * @param <E>
    //     * @param <E2>
    //     * @param stmt
    //     * @param offset
    //     * @param count
    //     * @param processThreadNum new threads started to parse/process the lines/records
    //     * @param queueSize size of queue to save the processing records/lines loaded from source data. Default size is 1024.
    //     * @param rowParser
    //     * @param onComplete
    //     * @throws SQLException
    //     * @throws E
    //     * @throws E2
    //     */
    //    public static <E extends Exception, E2 extends Exception> void parse(final PreparedStatement stmt, final long offset, final long count,
    //            final int processThreadNum, final int queueSize, final Throwables.Consumer<? super ResultSet, E> rowParser, final Throwables.Runnable<E2> onComplete)
    //            throws SQLException, E, E2 {
    //        ResultSet rs = null;
    //
    //        try {
    //            rs = JdbcUtil.executeQuery(stmt);
    //
    //            parse(rs, offset, count, processThreadNum, queueSize, rowParser, onComplete);
    //        } finally {
    //            JdbcUtil.closeQuietly(rs);
    //        }
    //    }
    //
    //    /**
    //     *
    //     * @param <E>
    //     * @param rs
    //     * @param rowParser
    //     * @throws SQLException
    //     * @throws E
    //     */
    //    public static <E extends Exception> void parse(final ResultSet rs, final Throwables.Consumer<? super ResultSet, E> rowParser) throws SQLException, E {
    //        parse(rs, rowParser, Fn.emptyAction());
    //    }
    //
    //    /**
    //     *
    //     * @param <E>
    //     * @param <E2>
    //     * @param rs
    //     * @param rowParser
    //     * @param onComplete
    //     * @throws SQLException
    //     * @throws E
    //     * @throws E2
    //     */
    //    public static <E extends Exception, E2 extends Exception> void parse(final ResultSet rs, final Throwables.Consumer<? super ResultSet, E> rowParser,
    //            final Throwables.Runnable<E2> onComplete) throws SQLException, E, E2 {
    //        parse(rs, 0, Long.MAX_VALUE, rowParser, onComplete);
    //    }
    //
    //    /**
    //     *
    //     * @param <E>
    //     * @param rs
    //     * @param offset
    //     * @param count
    //     * @param rowParser
    //     * @throws SQLException
    //     * @throws E
    //     */
    //    public static <E extends Exception> void parse(final ResultSet rs, long offset, long count, final Throwables.Consumer<? super ResultSet, E> rowParser)
    //            throws SQLException, E {
    //        parse(rs, offset, count, rowParser, Fn.emptyAction());
    //    }
    //
    //    /**
    //     *
    //     * @param <E>
    //     * @param <E2>
    //     * @param rs
    //     * @param offset
    //     * @param count
    //     * @param rowParser
    //     * @param onComplete
    //     * @throws SQLException
    //     * @throws E
    //     * @throws E2
    //     */
    //    public static <E extends Exception, E2 extends Exception> void parse(final ResultSet rs, long offset, long count,
    //            final Throwables.Consumer<? super ResultSet, E> rowParser, final Throwables.Runnable<E2> onComplete) throws SQLException, E, E2 {
    //        parse(rs, offset, count, 0, 0, rowParser, onComplete);
    //    }
    //
    //    /**
    //     *
    //     * @param <E>
    //     * @param rs
    //     * @param offset
    //     * @param count
    //     * @param processThreadNum
    //     * @param queueSize
    //     * @param rowParser
    //     * @throws SQLException
    //     * @throws E
    //     */
    //    public static <E extends Exception> void parse(final ResultSet rs, long offset, long count, final int processThreadNum, final int queueSize,
    //            final Throwables.Consumer<? super ResultSet, E> rowParser) throws SQLException, E {
    //        parse(rs, offset, count, processThreadNum, queueSize, rowParser, Fn.emptyAction());
    //    }
    //
    //    /**
    //     * Parse the ResultSet.
    //     *
    //     * @param <E>
    //     * @param <E2>
    //     * @param rs
    //     * @param offset
    //     * @param count
    //     * @param processThreadNum new threads started to parse/process the lines/records
    //     * @param queueSize size of queue to save the processing records/lines loaded from source data. Default size is 1024.
    //     * @param rowParser
    //     * @param onComplete
    //     * @throws E
    //     * @throws E2
    //     */
    //    public static <E extends Exception, E2 extends Exception> void parse(final ResultSet rs, long offset, long count, final int processThreadNum,
    //            final int queueSize, final Throwables.Consumer<? super ResultSet, E> rowParser, final Throwables.Runnable<E2> onComplete) throws E, E2 {
    //        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can not be negative", offset, count);
    //
    //        Iterators.forEach(iter, offset, count, processThreadNum, queueSize, elementParser);
    //    }

    private static void setFetchForBigResult(final Connection conn, final PreparedStatement stmt) throws SQLException {
        setFetchForBigResult(conn, stmt, JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT);
    }

    private static void setFetchForBigResult(final Connection conn, final PreparedStatement stmt, final int fetchSize) throws SQLException {
        stmt.setFetchDirection(ResultSet.FETCH_FORWARD);

        if (JdbcUtil.getDBProductInfo(conn).getVersion().isMySQL()) {
            stmt.setFetchSize(Integer.MIN_VALUE);
        } else {
            stmt.setFetchSize(fetchSize);
        }
    }

}
