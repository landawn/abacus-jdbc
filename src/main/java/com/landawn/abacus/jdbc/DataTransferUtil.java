/*
 * Copyright (c) 2020, Haiyang Li.
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.annotation.SequentialOnly;
import com.landawn.abacus.annotation.Stateful;
import com.landawn.abacus.jdbc.Jdbc.ColumnGetter;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.query.SqlDialect.ProductInfo;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.BufferedCsvWriter;
import com.landawn.abacus.util.CsvUtil;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.IOUtil;
import com.landawn.abacus.util.LineIterator;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Objectory;
import com.landawn.abacus.util.SK;
import com.landawn.abacus.util.Strings;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.stream.CharStream;

/**
 * Utility class for database import/export operations, CSV processing, and data copying between databases.
 *
 * <p>This class provides static methods for moving data between different sources and targets,
 * including Dataset objects, CSV files, Readers/Writers, Iterators, and database tables.
 * Many import methods offer configurable batch sizes and batch intervals.</p>
 *
 * <p><b>Supported Operations:</b></p>
 * <table border="1" style="border-collapse: collapse;">
 *   <caption><b>Data Operation Types and Methods</b></caption>
 *   <tr style="background-color: #f2f2f2;">
 *     <th>Operation Type</th>
 *     <th>Primary Methods</th>
 *     <th>Data Sources/Targets</th>
 *   </tr>
 *   <tr>
 *     <td>Data Import</td>
 *     <td>{@code importData()}</td>
 *     <td>Dataset or Iterator to database via DataSource, Connection, or PreparedStatement</td>
 *   </tr>
 *   <tr>
 *     <td>CSV Import</td>
 *     <td>{@code importCsv()}</td>
 *     <td>CSV File or Reader to database via DataSource, Connection, or PreparedStatement</td>
 *   </tr>
 *   <tr>
 *     <td>CSV Export</td>
 *     <td>{@code exportCsv()}</td>
 *     <td>Database query results (via DataSource, Connection, PreparedStatement, or ResultSet) to File or Writer
 *         (positional PreparedStatement overloads export to File only; use {@code exportCsvFrom(stmt).to(writer)} for a Writer)</td>
 *   </tr>
 *   <tr>
 *     <td>Data Copying</td>
 *     <td>{@code copy()}</td>
 *     <td>Between DataSources, Connections, or PreparedStatements (same or different databases)</td>
 *   </tr>
 *   <tr>
 *     <td>Utility</td>
 *     <td>{@code resultSetParameterSetter()}</td>
 *     <td>Factory method to create a parameter setter from a {@link Jdbc.ColumnGetter}</td>
 *   </tr>
 *   <tr>
 *     <td>Fluent Builders</td>
 *     <td>{@code importFrom()}, {@code importCsvFrom()}, {@code exportCsvFrom()}, {@code copyFrom()}, {@code copyTable()}</td>
 *     <td>Chainable, named-option alternatives to the positional {@code importData}/{@code importCsv}/{@code exportCsv}/{@code copy} overloads</td>
 *   </tr>
 * </table>
 *
 * <p><b>Fluent Builders:</b> In addition to the positional static overloads, this class exposes
 * builder entry points that read more clearly when several options are configured. Each entry point
 * returns a builder whose chained methods set optional parameters and whose terminal method actually
 * runs the operation:</p>
 * <ul>
 *   <li>{@link #importFrom(Dataset)} &rarr; {@link DatasetImportBuilder} (terminal: {@code to(...)} returns {@code int})</li>
 *   <li>{@link #importFrom(Iterator)} &rarr; {@link RowImportBuilder} (terminal: {@code to(...)} returns {@code long})</li>
 *   <li>{@link #importCsvFrom(File)} / {@link #importCsvFrom(Reader)} &rarr; {@link RowImportBuilder}
 *       (terminal: {@code to(...)} returns {@code long})</li>
 *   <li>{@link #exportCsvFrom(javax.sql.DataSource, String)} / {@link #exportCsvFrom(Connection, String)} /
 *       {@link #exportCsvFrom(PreparedStatement)} / {@link #exportCsvFrom(ResultSet)} &rarr; {@link CsvExportBuilder}
 *       (terminal: {@code to(File)} / {@code to(Writer)} returns {@code long})</li>
 *   <li>{@link #copyFrom(javax.sql.DataSource, String)} / {@link #copyFrom(Connection, String)} /
 *       {@link #copyFrom(PreparedStatement)} &rarr; {@code CopyFrom*} builders (terminal: {@code to(...)} returns {@code long})</li>
 *   <li>{@link #copyTable(javax.sql.DataSource, String)} / {@link #copyTable(Connection, String)}
 *       &rarr; {@code CopyTable*} builders (terminal: {@code to(...)} returns {@code long})</li>
 * </ul>
 *
 * <p><b>Canonical API:</b> Prefer the fluent builders for every operation that needs optional configuration.
 * They give each option a stable name, avoid overload-specific parameter ordering, and are the API surface that
 * will receive new transfer options. The positional methods are retained for source compatibility and remain
 * convenient for short operations that use defaults; option-heavy positional overloads are deprecated and no
 * additional long positional overloads will be added.</p>
 * <table border="1" style="border-collapse: collapse;">
 *   <caption><b>Preferred entry points</b></caption>
 *   <tr><th>Operation</th><th>Canonical form</th></tr>
 *   <tr><td>Dataset import</td><td>{@code importFrom(dataset).filter(...).batchSize(...).to(...)}</td></tr>
 *   <tr><td>Iterator/CSV import</td><td>{@code importFrom(iterator).parameterSetter(...).to(...)} / {@code importCsvFrom(...).parameterSetter(...).to(...)}</td></tr>
 *   <tr><td>CSV export</td><td>{@code exportCsvFrom(...).columns(...).to(...)}</td></tr>
 *   <tr><td>Query copy</td><td>{@code copyFrom(...).parameterSetter(...).to(...)}</td></tr>
 *   <tr><td>Table copy</td><td>{@code copyTable(...).columns(...).to(...)}</td></tr>
 * </table>
 *
 * <p><b>Resource ownership:</b> Methods that accept a {@link Connection}, {@link PreparedStatement} or
 * {@link ResultSet} never close the caller-supplied resource; they only close resources they create
 * internally. Methods that accept a {@link javax.sql.DataSource} obtain a connection and release it
 * back to the data source before returning.</p>
 *
 * <p><b>&#9888; Warning:</b> Import and copy return values count source rows submitted for execution,
 * not the sum of JDBC update counts reported by the target database. A successful return therefore
 * does not prove that every submitted row changed one target row.</p>
 *
 * <p><b>&#9888; Warning:</b> These operations do not create an all-or-nothing transaction. If a later
 * batch fails, earlier batches may already be committed. Supply a connection with an appropriate
 * active transaction when atomicity is required.</p>
 *
 * <p><b>&#9888; Warning:</b> Builders backed by a caller-supplied {@link Reader} or {@link Iterator}
 * are one-shot because their source is consumed by the terminal operation. Row arrays passed to
 * callbacks may be reused; copy a row before retaining it beyond the callback.</p>
 *
 * <p><b>CSV row width:</b> The header determines the reusable row-array length. Shorter data rows
 * are padded with {@code null}; a data row with more fields than the header is rejected with an
 * {@link IllegalArgumentException} instead of being silently truncated.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Import data from a Dataset to database table
 * Dataset dataset = Dataset.rows(List.of("name", "age", "email"),
 *     new Object[][] {{"John Doe", 30, "john@example.com"}, {"Jane Smith", 25, "jane@example.com"}});
 * int importedRows = DataTransferUtil.importData(dataset, dataSource,
 *     "INSERT INTO users (name, age, email) VALUES (?, ?, ?)");
 *
 * // Export query results to CSV file
 * long exportedRows = DataTransferUtil.exportCsv(dataSource,
 *     "SELECT * FROM users ORDER BY id",
 *     new File("export.csv"));
 *
 * // Configure nontrivial operations with the canonical fluent API
 * long copiedRows = DataTransferUtil.copyFrom(sourceDataSource,
 *         "SELECT id, name, age FROM source_users WHERE active = true")
 *     .fetchSize(50000)
 *     .batchSize(10000)
 *     .to(targetDataSource,
 *         "INSERT INTO target_users (user_id, full_name, user_age) VALUES (?, ?, ?)");
 * }</pre>
 *
 * @see Dataset
 * @see CsvUtil
 * @see JdbcUtil
 * @see Connection
 * @see PreparedStatement
 * @see ResultSet
 */
public final class DataTransferUtil {

    private static final Logger logger = LoggerFactory.getLogger(DataTransferUtil.class);

    static final char[] NULL_CHAR_ARRAY = Strings.NULL.toCharArray();

    private DataTransferUtil() {
        // Utility class - prevent instantiation.
    }

    /**
     * Imports data from a Dataset to a database table using the provided DataSource and insert SQL statement.
     * The column order in the SQL statement must be consistent with the column order in the Dataset.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.rows(List.of("name", "age"), new Object[][] {{"John", 25}, {"Jane", 30}});
     * String insertSql = "INSERT INTO users (name, age) VALUES (?, ?)";
     * int rowsImported = DataTransferUtil.importData(dataset, dataSource, insertSql);
     * }</pre>
     *
     * <p>The insert SQL can be generated using:</p>
     * <pre>{@code
     * List<String> columnNameList = new ArrayList<>(dataset.columnNames());
     * String sql = PSC.insert(columnNameList).into(tableName).sql();
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param targetDataSource the DataSource to obtain database connections from
     * @param insertSql the SQL insert statement with placeholders; column order must match the Dataset
     * @return the number of rows successfully imported
     * @throws SQLException if a database access error occurs
     */
    public static int importData(final Dataset dataset, final javax.sql.DataSource targetDataSource, final String insertSql) throws SQLException {
        final Connection conn = JdbcUtil.getConnection(targetDataSource);

        try {
            return importData(dataset, conn, insertSql);
        } finally {
            JdbcUtil.releaseConnection(conn, targetDataSource);
        }
    }

    /**
     * Imports data from a Dataset to a database table using the provided Connection and insert SQL statement.
     * The column order in the SQL statement must be consistent with the column order in the Dataset.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.rows(List.of("name", "age"), new Object[][] {{"John", 25}, {"Jane", 30}});
     * String insertSql = "INSERT INTO users (name, age) VALUES (?, ?)";
     * int rowsImported = DataTransferUtil.importData(dataset, connection, insertSql);
     * }</pre>
     *
     * <p>The insert SQL can be generated using:</p>
     * <pre>{@code
     * List<String> columnNameList = new ArrayList<>(dataset.columnNames());
     * String sql = PSC.insert(columnNameList).into(tableName).sql();
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param conn the Connection to the database
     * @param insertSql the SQL insert statement with placeholders; column order must match the Dataset
     * @return the number of rows successfully imported
     * @throws SQLException if a database access error occurs
     * @see #importData(Dataset, Collection, Connection, String)
     */
    public static int importData(final Dataset dataset, final Connection conn, final String insertSql) throws SQLException {
        return importData(dataset, dataset.columnNames(), conn, insertSql);
    }

    /**
     * Imports selected columns from a Dataset to a database table using the provided Connection and insert SQL statement.
     * Only the specified columns will be imported, and their order in the SQL must match {@code columnNames}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.rows(List.of("id", "name", "age", "email"), new Object[][] {{1, "John", 25, "john@email.com"}});
     * List<String> columnNames = Arrays.asList("name", "age");
     * String insertSql = "INSERT INTO users (name, age) VALUES (?, ?)";
     * int rowsImported = DataTransferUtil.importData(dataset, columnNames, connection, insertSql);
     * }</pre>
     *
     * <p>The insert SQL can be generated using:</p>
     * <pre>{@code
     * List<String> columnNameList = new ArrayList<>(columnNames);
     * String sql = PSC.insert(columnNameList).into(tableName).sql();
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param columnNames the collection of column names to be selected for import
     * @param conn the Connection to the database
     * @param insertSql the SQL insert statement with placeholders; placeholder order must match {@code columnNames}
     * @return the number of rows successfully imported
     * @throws IllegalArgumentException if any name in {@code columnNames} is not a column of the dataset
     * @throws SQLException if a database access error occurs
     */
    public static int importData(final Dataset dataset, final Collection<String> columnNames, final Connection conn, final String insertSql)
            throws SQLException {
        return importData(dataset, columnNames, conn, insertSql, JdbcUtil.DEFAULT_BATCH_SIZE, 0);
    }

    /**
     * Imports selected columns from a Dataset to a database table with configurable batch processing.
     * This method allows fine control over the import process with batch size and interval settings.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.rows(List.of("name", "age"), new Object[][] {{"John", 25}, {"Jane", 30}});
     * List<String> columns = Arrays.asList("name", "age");
     * String insertSql = "INSERT INTO users (name, age) VALUES (?, ?)";
     * int rowsImported = DataTransferUtil.importData(dataset, columns, connection, insertSql, 1000, 100);
     * }</pre>
     *
     * <p>The insert SQL can be generated using:</p>
     * <pre>{@code
     * List<String> columnNameList = new ArrayList<>(columnNames);
     * String sql = PSC.insert(columnNameList).into(tableName).sql();
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param columnNames the collection of column names to be selected for import
     * @param conn the Connection to the database
     * @param insertSql the SQL insert statement with placeholders; placeholder order must match {@code columnNames}
     * @param batchSize the number of rows to be inserted in each batch (must be greater than 0)
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution (must be {@code >= 0})
     * @return the number of rows successfully imported
     * @throws IllegalArgumentException if {@code batchSize <= 0}, {@code batchIntervalInMillis < 0},
     *         or any name in {@code columnNames} is not a column of the dataset
     * @throws SQLException if a database access error occurs
     */
    @Deprecated
    public static int importData(final Dataset dataset, final Collection<String> columnNames, final Connection conn, final String insertSql,
            final int batchSize, final long batchIntervalInMillis) throws SQLException {
        return importData(dataset, columnNames, null, conn, insertSql, batchSize, batchIntervalInMillis);
    }

    /**
     * Imports filtered data from a Dataset to a database table with configurable batch processing.
     * Only rows that pass the filter predicate will be imported.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.rows(List.of("name", "age"), new Object[][] {{"John", 25}, {"Jane", 30}, {"Bob", 15}});
     * List<String> columns = Arrays.asList("name", "age");
     * // Only import adults (age >= 18)
     * Predicate<Object[]> filter = row -> ((Integer) row[1]) >= 18;
     * String insertSql = "INSERT INTO adult_users (name, age) VALUES (?, ?)";
     * int rowsImported = DataTransferUtil.importData(dataset, columns, filter, conn, insertSql, 1000, 0);
     * }</pre>
     *
     * <p>The insert SQL can be generated using:</p>
     * <pre>{@code
     * List<String> columnNameList = new ArrayList<>(columnNames);
     * String sql = PSC.insert(columnNameList).into(tableName).sql();
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param columnNames the collection of column names to be selected for import
     * @param filter a predicate to filter the rows; only rows returning {@code true} will be imported. If {@code null}, every row is imported
     * @param conn the Connection to the database
     * @param insertSql the SQL insert statement with placeholders; placeholder order must match {@code columnNames}
     * @param batchSize the number of rows to be inserted in each batch (must be greater than 0)
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution (must be {@code >= 0})
     * @return the number of rows successfully imported (after filtering)
     * @throws IllegalArgumentException if {@code batchSize <= 0}, {@code batchIntervalInMillis < 0},
     *         or any name in {@code columnNames} is not a column of the dataset
     * @throws SQLException if a database access error occurs
     */
    @Deprecated
    public static int importData(final Dataset dataset, final Collection<String> columnNames, final Predicate<? super Object[]> filter, final Connection conn,
            final String insertSql, final int batchSize, final long batchIntervalInMillis) throws SQLException {

        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSql)) {
            return importData(dataset, columnNames, filter, stmt, batchSize, batchIntervalInMillis);
        }
    }

    /**
     * Imports data from a Dataset to a database table with custom column type mapping.
     * This method allows specifying the type for each column, enabling custom type conversions during import.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.rows(List.of("name", "birthdate"), new Object[][] {{"John", "1990-01-15"}});
     * Map<String, Type> columnTypes = new HashMap<>();
     * columnTypes.put("name", Type.of(String.class));
     * columnTypes.put("birthdate", Type.of(java.sql.Date.class));
     * String insertSql = "INSERT INTO users (name, birthdate) VALUES (?, ?)";
     * int rowsImported = DataTransferUtil.importData(dataset, connection, insertSql, columnTypes);
     * }</pre>
     *
     * <p>The insert SQL can be generated using:</p>
     * <pre>{@code
     * List<String> columnNameList = new ArrayList<>(dataset.columnNames());
     * columnNameList.retainAll(yourSelectColumnNames);
     * String sql = PSC.insert(columnNameList).into(tableName).sql();
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param conn the Connection to the database
     * @param insertSql the SQL insert statement with placeholders; column order must match the Dataset
     * @param columnTypeMap a map specifying the types of the columns for type conversion
     * @return the number of rows successfully imported
     * @throws IllegalArgumentException if any key in {@code columnTypeMap} is not a column of the dataset or a mapped {@link Type} is {@code null}
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final Dataset dataset, final Connection conn, final String insertSql, final Map<String, ? extends Type> columnTypeMap)
            throws SQLException {
        return importData(dataset, conn, insertSql, JdbcUtil.DEFAULT_BATCH_SIZE, 0, columnTypeMap);
    }

    /**
     * Imports data from a Dataset to a database table with custom column type mapping and batch processing.
     * This method combines type mapping with configurable batch processing for optimal performance.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.rows(List.of("name", "birthdate", "score"),
     *     new Object[][] {{"John", "1990-01-15", "95.5"}, {"Jane", "1992-03-20", "87.3"}});
     * Map<String, Type> columnTypes = new HashMap<>();
     * columnTypes.put("name", Type.of(String.class));
     * columnTypes.put("birthdate", Type.of(java.sql.Date.class));
     * columnTypes.put("score", Type.of(Double.class));
     * String insertSql = "INSERT INTO students (name, birthdate, score) VALUES (?, ?, ?)";
     * int rowsImported = DataTransferUtil.importData(dataset, conn, insertSql, 1000, 0, columnTypes);
     * }</pre>
     *
     * <p>The insert SQL can be generated using:</p>
     * <pre>{@code
     * List<String> columnNameList = new ArrayList<>(dataset.columnNames());
     * columnNameList.retainAll(yourSelectColumnNames);
     * String sql = PSC.insert(columnNameList).into(tableName).sql();
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param conn the Connection to the database
     * @param insertSql the SQL insert statement with placeholders; column order must match the Dataset
     * @param batchSize the number of rows to be inserted in each batch (must be greater than 0)
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution (must be {@code >= 0})
     * @param columnTypeMap a map specifying the types of the columns for type conversion
     * @return the number of rows successfully imported
     * @throws IllegalArgumentException if {@code batchSize <= 0}, {@code batchIntervalInMillis < 0},
     *         any key in {@code columnTypeMap} is not a column of the dataset, or a mapped {@link Type} is {@code null}
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("rawtypes")
    @Deprecated
    public static int importData(final Dataset dataset, final Connection conn, final String insertSql, final int batchSize, final long batchIntervalInMillis,
            final Map<String, ? extends Type> columnTypeMap) throws SQLException {
        return importData(dataset, null, conn, insertSql, batchSize, batchIntervalInMillis, columnTypeMap);
    }

    /**
     * Imports filtered data from a Dataset to a database table with custom column type mapping and batch processing.
     * This method provides maximum control over the import process with filtering, type mapping, and batch configuration.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.rows(List.of("name", "age", "status"),
     *     new Object[][] {{"John", "25", "active"}, {"Jane", "30", "inactive"}});
     * Map<String, Type> columnTypes = new HashMap<>();
     * columnTypes.put("name", Type.of(String.class));
     * columnTypes.put("age", Type.of(Integer.class));
     * columnTypes.put("status", Type.of(String.class));
     * // Only import active users
     * Predicate<Object[]> filter = row -> "active".equals(row[2]);
     * String insertSql = "INSERT INTO active_users (name, age, status) VALUES (?, ?, ?)";
     * int rowsImported = DataTransferUtil.importData(dataset, filter, conn, insertSql, 500, 50, columnTypes);
     * }</pre>
     *
     * <p>The insert SQL can be generated using:</p>
     * <pre>{@code
     * List<String> columnNameList = new ArrayList<>(dataset.columnNames());
     * columnNameList.retainAll(yourSelectColumnNames);
     * String sql = PSC.insert(columnNameList).into(tableName).sql();
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param filter a predicate to filter the rows; only rows returning {@code true} will be imported. If {@code null}, every row is imported
     * @param conn the Connection to the database
     * @param insertSql the SQL insert statement with placeholders; column order must match the Dataset
     * @param batchSize the number of rows to be inserted in each batch (must be greater than 0)
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution (must be {@code >= 0})
     * @param columnTypeMap a map specifying the types of the columns for type conversion
     * @return the number of rows successfully imported (after filtering)
     * @throws IllegalArgumentException if {@code batchSize <= 0}, {@code batchIntervalInMillis < 0},
     *         any key in {@code columnTypeMap} is not a column of the dataset, or a mapped {@link Type} is {@code null}
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("rawtypes")
    @Deprecated
    public static int importData(final Dataset dataset, final Predicate<? super Object[]> filter, final Connection conn, final String insertSql,
            final int batchSize, final long batchIntervalInMillis, final Map<String, ? extends Type> columnTypeMap) throws SQLException {
        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSql)) {
            return importData(dataset, filter, stmt, batchSize, batchIntervalInMillis, columnTypeMap);
        }
    }

    /**
     * Imports data from a Dataset to a database table with a custom statement setter.
     * This method provides complete control over how values are set on the PreparedStatement.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.rows(List.of("name", "age"), new Object[][] {{"John", 25}, {"Jane", 30}});
     * String insertSql = "INSERT INTO users (name, age, created_date) VALUES (?, ?, ?)";
     * Throwables.BiConsumer<PreparedQuery, Object[], SQLException> setter = (query, row) -> {
     *     query.setString(1, (String) row[0]);
     *     query.setInt(2, (Integer) row[1]);
     *     query.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
     * };
     * int rowsImported = DataTransferUtil.importData(dataset, connection, insertSql, setter);
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param conn the Connection to the database
     * @param insertSql the SQL insert statement with placeholders
     * @param parameterSetter a BiConsumer to set the parameters of the {@link PreparedQuery} for each row; must not be {@code null}
     * @return the number of rows successfully imported
     * @throws IllegalArgumentException if {@code parameterSetter} is {@code null}
     * @throws SQLException if a database access error occurs
     */
    public static int importData(final Dataset dataset, final Connection conn, final String insertSql,
            final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> parameterSetter) throws SQLException {
        return importData(dataset, conn, insertSql, JdbcUtil.DEFAULT_BATCH_SIZE, 0, parameterSetter);
    }

    /**
     * Imports data from a Dataset to a database table with a custom statement setter and batch processing.
     * This method combines custom parameter setting with configurable batch processing.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.rows(List.of("name", "age"), new Object[][] {{"John", 25}, {"Jane", 30}});
     * String insertSql = "INSERT INTO users (name, age, created_date) VALUES (?, ?, ?)";
     * Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> setter = (stmt, row) -> {
     *     stmt.setString(1, (String) row[0]);
     *     stmt.setInt(2, (Integer) row[1]);
     *     stmt.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
     * };
     * int rowsImported = DataTransferUtil.importData(dataset, conn, insertSql, 1000, 100, setter);
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param conn the Connection to the database
     * @param insertSql the SQL insert statement with placeholders
     * @param batchSize the number of rows to be inserted in each batch (must be greater than 0)
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution (must be {@code >= 0})
     * @param parameterSetter a BiConsumer to set the parameters of the {@link PreparedQuery} for each row; must not be {@code null}
     * @return the number of rows successfully imported
     * @throws IllegalArgumentException if {@code parameterSetter} is {@code null}, {@code batchSize <= 0}, or {@code batchIntervalInMillis < 0}
     * @throws SQLException if a database access error occurs
     */
    @Deprecated
    public static int importData(final Dataset dataset, final Connection conn, final String insertSql, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> parameterSetter) throws SQLException {
        return importData(dataset, null, conn, insertSql, batchSize, batchIntervalInMillis, parameterSetter);
    }

    /**
     * Imports filtered data from a Dataset to a database table with a custom statement setter and batch processing.
     * This method provides maximum flexibility with filtering, custom parameter setting, and batch configuration.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.rows(List.of("name", "age", "status"),
     *     new Object[][] {{"John", 25, "active"}, {"Jane", 30, "inactive"}});
     * // Only import active users
     * Predicate<Object[]> filter = row -> "active".equals(row[2]);
     * String insertSql = "INSERT INTO active_users (name, age, last_login) VALUES (?, ?, ?)";
     * Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> setter = (stmt, row) -> {
     *     stmt.setString(1, (String) row[0]);
     *     stmt.setInt(2, (Integer) row[1]);
     *     stmt.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
     * };
     * int rowsImported = DataTransferUtil.importData(dataset, filter, conn, insertSql, 500, 0, setter);
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param filter a predicate to filter the rows; only rows returning {@code true} will be imported. If {@code null}, every row is imported
     * @param conn the Connection to the database
     * @param insertSql the SQL insert statement with placeholders
     * @param batchSize the number of rows to be inserted in each batch (must be greater than 0)
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution (must be {@code >= 0})
     * @param parameterSetter a BiConsumer to set the parameters of the {@link PreparedQuery} for each row; must not be {@code null}
     * @return the number of rows successfully imported (after filtering)
     * @throws IllegalArgumentException if {@code parameterSetter} is {@code null}, {@code batchSize <= 0}, or {@code batchIntervalInMillis < 0}
     * @throws SQLException if a database access error occurs
     */
    @Deprecated
    public static int importData(final Dataset dataset, final Predicate<? super Object[]> filter, final Connection conn, final String insertSql,
            final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> parameterSetter) throws SQLException {
        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSql)) {
            return importData(dataset, filter, stmt, batchSize, batchIntervalInMillis, parameterSetter);
        }
    }

    /**
     * Imports data from a Dataset to a database table using the provided PreparedStatement.
     * All columns from the Dataset will be imported in their original order.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.rows(List.of("name", "age"), new Object[][] {{"John", 25}, {"Jane", 30}});
     * PreparedStatement stmt = connection.prepareStatement("INSERT INTO users (name, age) VALUES (?, ?)");
     * int rowsImported = DataTransferUtil.importData(dataset, stmt);
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param stmt the PreparedStatement to be used for the import (will not be closed by this method)
     * @return the number of rows successfully imported
     * @throws SQLException if a database access error occurs
     */
    public static int importData(final Dataset dataset, final PreparedStatement stmt) throws SQLException {
        return importData(dataset, dataset.columnNames(), stmt);
    }

    /**
     * Imports selected columns from a Dataset to a database table using the provided PreparedStatement.
     * Only the specified columns will be imported, and their order must match the PreparedStatement parameters.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.rows(List.of("id", "name", "age", "email"), new Object[][] {{1, "John", 25, "john@email.com"}});
     * List<String> columnNames = Arrays.asList("name", "age");
     * PreparedStatement stmt = connection.prepareStatement("INSERT INTO users (name, age) VALUES (?, ?)");
     * int rowsImported = DataTransferUtil.importData(dataset, columnNames, stmt);
     * }</pre>
     *
     * <p>The insert SQL can be generated using:</p>
     * <pre>{@code
     * List<String> columnNameList = new ArrayList<>(columnNames);
     * String sql = PSC.insert(columnNameList).into(tableName).sql();
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param columnNames the collection of column names to be selected for import
     * @param stmt the PreparedStatement to be used for the import (will not be closed by this method)
     * @return the number of rows successfully imported
     * @throws IllegalArgumentException if any name in {@code columnNames} is not a column of the dataset
     * @throws SQLException if a database access error occurs
     */
    public static int importData(final Dataset dataset, final Collection<String> columnNames, final PreparedStatement stmt) throws SQLException {
        return importData(dataset, columnNames, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0);
    }

    /**
     * Imports selected columns from a Dataset to a database table using the provided PreparedStatement with batch processing.
     * This method allows control over the batch size and interval for optimal performance.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.rows(List.of("name", "age"), new Object[][] {{"John", 25}, {"Jane", 30}});
     * List<String> columns = Arrays.asList("name", "age");
     * PreparedStatement stmt = connection.prepareStatement("INSERT INTO users (name, age) VALUES (?, ?)");
     * int rowsImported = DataTransferUtil.importData(dataset, columns, stmt, 1000, 100);
     * }</pre>
     *
     * <p>The insert SQL can be generated using:</p>
     * <pre>{@code
     * List<String> columnNameList = new ArrayList<>(dataset.columnNames());
     * columnNameList.retainAll(columnNames);
     * String sql = PSC.insert(columnNameList).into(tableName).sql();
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param columnNames the collection of column names to be selected for import
     * @param stmt the PreparedStatement to be used for the import (will not be closed by this method)
     * @param batchSize the number of rows to be inserted in each batch (must be greater than 0)
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution (must be {@code >= 0})
     * @return the number of rows successfully imported
     * @throws IllegalArgumentException if {@code batchSize <= 0}, {@code batchIntervalInMillis < 0},
     *         or any name in {@code columnNames} is not a column of the dataset
     * @throws SQLException if a database access error occurs
     */
    @Deprecated
    public static int importData(final Dataset dataset, final Collection<String> columnNames, final PreparedStatement stmt, final int batchSize,
            final long batchIntervalInMillis) throws SQLException {
        return importData(dataset, columnNames, null, stmt, batchSize, batchIntervalInMillis);
    }

    /**
     * Imports filtered data from selected columns of a Dataset to a database table using the provided PreparedStatement.
     * This method provides filtering capability with batch processing for selective data import.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.rows(List.of("name", "age", "status"),
     *     new Object[][] {{"John", 25, "active"}, {"Jane", 30, "inactive"}});
     * List<String> columns = Arrays.asList("name", "age");
     * // Only import active users
     * Predicate<Object[]> filter = row -> "active".equals(row[2]);
     * PreparedStatement stmt = connection.prepareStatement("INSERT INTO active_users (name, age) VALUES (?, ?)");
     * int rowsImported = DataTransferUtil.importData(dataset, columns, filter, stmt, 500, 0);
     * }</pre>
     *
     * <p>The insert SQL can be generated using:</p>
     * <pre>{@code
     * List<String> columnNameList = new ArrayList<>(dataset.columnNames());
     * columnNameList.retainAll(columnNames);
     * String sql = PSC.insert(columnNameList).into(tableName).sql();
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param columnNames the collection of column names to be selected for import
     * @param filter a predicate to filter the rows; only rows returning {@code true} will be imported. If {@code null}, every row is imported
     * @param stmt the PreparedStatement to be used for the import (will not be closed by this method)
     * @param batchSize the number of rows to be inserted in each batch (must be greater than 0)
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution (must be {@code >= 0})
     * @return the number of rows successfully imported (after filtering)
     * @throws IllegalArgumentException if {@code batchSize <= 0}, {@code batchIntervalInMillis < 0},
     *         or any name in {@code columnNames} is not a column of the dataset
     * @throws SQLException if a database access error occurs
     */
    @Deprecated
    public static int importData(final Dataset dataset, final Collection<String> columnNames, final Predicate<? super Object[]> filter,
            final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis) throws SQLException {
        N.checkArgNotNull(dataset, cs.dataset);
        N.checkArgNotNull(columnNames, "columnNames");
        N.checkArgNotNull(stmt, "stmt");
        N.checkArgument(batchSize > 0 && batchIntervalInMillis >= 0, "'batchSize'=%s must be greater than 0 and 'batchIntervalInMillis'=%s can't be negative",
                batchSize, batchIntervalInMillis);

        final List<String> allColumnNames = dataset.columnNames();
        final List<String> selectedColumnNameList = new ArrayList<>(columnNames);

        // Validate that all selected column names exist in the dataset
        for (final String colName : selectedColumnNameList) {
            if (!allColumnNames.contains(colName)) {
                throw new IllegalArgumentException("Column '" + colName + "' is not found in dataset columns: " + allColumnNames);
            }
        }

        // Map selected column names to their indices in the dataset
        final int[] selectedColumnIndices = new int[selectedColumnNameList.size()];
        for (int i = 0; i < selectedColumnNameList.size(); i++) {
            selectedColumnIndices[i] = allColumnNames.indexOf(selectedColumnNameList.get(i));
        }

        final Throwables.BiConsumer<PreparedQuery, Object[], SQLException> parameterSetter = (t, row) -> {
            for (int i = 0; i < selectedColumnIndices.length; i++) {
                t.setObject(i + 1, row[selectedColumnIndices[i]]);
            }
        };

        return importData(dataset, filter, stmt, batchSize, batchIntervalInMillis, parameterSetter);
    }

    /**
     * Imports data from a Dataset to a database table using the provided PreparedStatement with custom column type mapping.
     * This method allows specifying the type for each column for proper type conversion during import.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.rows(List.of("name", "birthdate"), new Object[][] {{"John", "1990-01-15"}});
     * Map<String, Type> columnTypes = new HashMap<>();
     * columnTypes.put("name", Type.of(String.class));
     * columnTypes.put("birthdate", Type.of(java.sql.Date.class));
     * PreparedStatement stmt = connection.prepareStatement("INSERT INTO users (name, birthdate) VALUES (?, ?)");
     * int rowsImported = DataTransferUtil.importData(dataset, stmt, columnTypes);
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param stmt the PreparedStatement to be used for the import (will not be closed by this method)
     * @param columnTypeMap a map specifying the types of the columns for type conversion
     * @return the number of rows successfully imported
     * @throws IllegalArgumentException if any key in {@code columnTypeMap} is not a column of the dataset or a mapped {@link Type} is {@code null}
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final Dataset dataset, final PreparedStatement stmt, final Map<String, ? extends Type> columnTypeMap) throws SQLException {
        return importData(dataset, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, columnTypeMap);
    }

    /**
     * Imports data from a Dataset to a database table using the provided PreparedStatement with custom column type mapping and batch processing.
     * This method combines type mapping with configurable batch processing for optimal performance.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.rows(List.of("name", "birthdate", "score"),
     *     new Object[][] {{"John", "1990-01-15", "95.5"}, {"Jane", "1992-03-20", "87.3"}});
     * Map<String, Type> columnTypes = new HashMap<>();
     * columnTypes.put("name", Type.of(String.class));
     * columnTypes.put("birthdate", Type.of(java.sql.Date.class));
     * columnTypes.put("score", Type.of(Double.class));
     * PreparedStatement stmt = connection.prepareStatement("INSERT INTO students (name, birthdate, score) VALUES (?, ?, ?)");
     * int rowsImported = DataTransferUtil.importData(dataset, stmt, 1000, 0, columnTypes);
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param stmt the PreparedStatement to be used for the import (will not be closed by this method)
     * @param batchSize the number of rows to be inserted in each batch (must be greater than 0)
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution (must be {@code >= 0})
     * @param columnTypeMap a map specifying the types of the columns for type conversion
     * @return the number of rows successfully imported
     * @throws IllegalArgumentException if {@code batchSize <= 0}, {@code batchIntervalInMillis < 0},
     *         any key in {@code columnTypeMap} is not a column of the dataset, or a mapped {@link Type} is {@code null}
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("rawtypes")
    @Deprecated
    public static int importData(final Dataset dataset, final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis,
            final Map<String, ? extends Type> columnTypeMap) throws SQLException {
        return importData(dataset, null, stmt, batchSize, batchIntervalInMillis, columnTypeMap);
    }

    /**
     * Imports filtered data from a Dataset to a database table using the provided PreparedStatement with custom column type mapping and batch processing.
     * This method provides maximum control over the import process with filtering, type mapping, and batch configuration.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.rows(List.of("name", "age", "status"),
     *     new Object[][] {{"John", "25", "active"}, {"Jane", "30", "inactive"}});
     * Map<String, Type> columnTypes = new HashMap<>();
     * columnTypes.put("name", Type.of(String.class));
     * columnTypes.put("age", Type.of(Integer.class));
     * columnTypes.put("status", Type.of(String.class));
     * // Only import active users
     * Predicate<Object[]> filter = row -> "active".equals(row[2]);
     * PreparedStatement stmt = connection.prepareStatement("INSERT INTO active_users (name, age, status) VALUES (?, ?, ?)");
     * int rowsImported = DataTransferUtil.importData(dataset, filter, stmt, 500, 50, columnTypes);
     * }</pre>
     *
     * <p>The insert SQL can be generated using:</p>
     * <pre>{@code
     * List<String> columnNameList = new ArrayList<>(dataset.columnNames());
     * columnNameList.retainAll(yourSelectColumnNames);
     * String sql = PSC.insert(columnNameList).into(tableName).sql();
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param filter a predicate to filter the rows; only rows returning {@code true} will be imported. If {@code null}, every row is imported
     * @param stmt the PreparedStatement to be used for the import (will not be closed by this method)
     * @param batchSize the number of rows to be inserted in each batch (must be greater than 0)
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution (must be {@code >= 0})
     * @param columnTypeMap a map specifying the types of the columns for type conversion
     * @return the number of rows successfully imported (after filtering)
     * @throws IllegalArgumentException if {@code batchSize <= 0}, {@code batchIntervalInMillis < 0},
     *         any key in {@code columnTypeMap} is not a column of the dataset, or a mapped {@link Type} is {@code null}
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings({ "rawtypes", "null" })
    @Deprecated
    public static int importData(final Dataset dataset, final Predicate<? super Object[]> filter, final PreparedStatement stmt, final int batchSize,
            final long batchIntervalInMillis, final Map<String, ? extends Type> columnTypeMap) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(dataset, cs.dataset);
        N.checkArgNotNull(stmt, "stmt");
        N.checkArgument(batchSize > 0 && batchIntervalInMillis >= 0, "'batchSize'=%s must be greater than 0 and 'batchIntervalInMillis'=%s can't be negative",
                //NOSONAR
                batchSize, batchIntervalInMillis);

        // Validate columnTypeMap keys up front so an unknown key is rejected per the @throws contract even when the
        // dataset is empty (the per-row setter below is never invoked for a zero-row dataset).
        if (N.notEmpty(columnTypeMap)) {
            final List<String> columnNameList = dataset.columnNames();

            if (!columnNameList.containsAll(columnTypeMap.keySet())) {
                final List<String> keys = new ArrayList<>(columnTypeMap.keySet());
                keys.removeAll(columnNameList);
                throw new IllegalArgumentException(keys + " are not columns of the dataset: " + N.toString(columnNameList));
            }

            for (final Map.Entry<String, ? extends Type> entry : columnTypeMap.entrySet()) {
                if (entry.getValue() == null) {
                    throw new IllegalArgumentException("No Type is specified for dataset column '" + entry.getKey() + "'");
                }
            }
        }

        final Type<Object> objType = N.typeOf(Object.class);
        final boolean hasColumnTypeMap = N.notEmpty(columnTypeMap);
        final Throwables.BiConsumer<PreparedQuery, Object[], SQLException> parameterSetter = new Throwables.BiConsumer<>() {
            private int columnCount = 0;
            private Type<Object>[] columnTypes = null;

            @Override
            public void accept(final PreparedQuery t, final Object[] u) throws SQLException {
                if (columnTypes == null) {
                    final List<String> columnNameList = dataset.columnNames();
                    columnCount = columnNameList.size();

                    columnTypes = new Type[columnCount];

                    for (int i = 0; i < columnCount; i++) {
                        final String columnName = columnNameList.get(i);

                        if (hasColumnTypeMap && columnTypeMap.containsKey(columnName)) {
                            columnTypes[i] = N.requireNonNull(columnTypeMap.get(columnName));
                        } else {
                            columnTypes[i] = objType;
                        }
                    }
                }

                for (int i = 0; i < columnCount; i++) {
                    // Bind through the passed query (not the captured outer stmt) so the setter stays
                    // correct if it is ever reused against a different statement.
                    t.setObject(i + 1, u[i], columnTypes[i]);
                }
            }
        };

        return importData(dataset, filter, stmt, batchSize, batchIntervalInMillis, parameterSetter);
    }

    /**
     * Imports data from a Dataset to a database table using the provided PreparedStatement with a custom statement setter.
     * This method provides complete control over how values are set on the PreparedStatement.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.rows(List.of("name", "age"), new Object[][] {{"John", 25}, {"Jane", 30}});
     * PreparedStatement stmt = connection.prepareStatement("INSERT INTO users (name, age, created_date) VALUES (?, ?, ?)");
     * Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> setter = (query, row) -> {
     *     query.setString(1, (String) row[0]);
     *     query.setInt(2, (Integer) row[1]);
     *     query.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
     * };
     * int rowsImported = DataTransferUtil.importData(dataset, stmt, setter);
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param stmt the PreparedStatement to be used for the import (will not be closed by this method)
     * @param parameterSetter a BiConsumer to set the parameters of the {@link PreparedQuery} for each row; must not be {@code null}
     * @return the number of rows successfully imported
     * @throws IllegalArgumentException if {@code parameterSetter} is {@code null}
     * @throws SQLException if a database access error occurs
     */
    public static int importData(final Dataset dataset, final PreparedStatement stmt,
            final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> parameterSetter) throws SQLException {
        return importData(dataset, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, parameterSetter);
    }

    /**
     * Imports data from a Dataset to a database table using the provided PreparedStatement with a custom statement setter and batch processing.
     * This method combines custom parameter setting with configurable batch processing for optimal performance.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.rows(List.of("name", "age"), new Object[][] {{"John", 25}, {"Jane", 30}});
     * PreparedStatement stmt = connection.prepareStatement("INSERT INTO users (name, age, created_date) VALUES (?, ?, ?)");
     * Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> setter = (query, row) -> {
     *     query.setString(1, (String) row[0]);
     *     query.setInt(2, (Integer) row[1]);
     *     query.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
     * };
     * int rowsImported = DataTransferUtil.importData(dataset, stmt, 1000, 100, setter);
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param stmt the PreparedStatement to be used for the import (will not be closed by this method)
     * @param batchSize the number of rows to be inserted in each batch (must be greater than 0)
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution (must be {@code >= 0})
     * @param parameterSetter a BiConsumer to set the parameters of the {@link PreparedQuery} for each row; must not be {@code null}
     * @return the number of rows successfully imported
     * @throws IllegalArgumentException if {@code parameterSetter} is {@code null}, {@code batchSize <= 0}, or {@code batchIntervalInMillis < 0}
     * @throws SQLException if a database access error occurs
     */
    @Deprecated
    public static int importData(final Dataset dataset, final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> parameterSetter) throws SQLException {
        return importData(dataset, null, stmt, batchSize, batchIntervalInMillis, parameterSetter);
    }

    /**
     * Imports filtered data from a Dataset to a database table using the provided PreparedStatement with a custom statement setter and batch processing.
     * This method provides maximum flexibility with filtering, custom parameter setting, and batch configuration.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.rows(List.of("name", "age", "status"),
     *     new Object[][] {{"John", 25, "active"}, {"Jane", 30, "inactive"}});
     * PreparedStatement stmt = connection.prepareStatement("INSERT INTO active_users (name, age, last_login) VALUES (?, ?, ?)");
     * // Only import active users
     * Predicate<Object[]> filter = row -> "active".equals(row[2]);
     * Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> setter = (query, row) -> {
     *     query.setString(1, (String) row[0]);
     *     query.setInt(2, (Integer) row[1]);
     *     query.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
     * };
     * int rowsImported = DataTransferUtil.importData(dataset, filter, stmt, 500, 0, setter);
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param filter a predicate to filter the rows; only rows returning {@code true} will be imported. If {@code null}, every row is imported
     * @param stmt the PreparedStatement to be used for the import (will not be closed by this method)
     * @param batchSize the number of rows to be inserted in each batch (must be greater than 0)
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution (must be {@code >= 0})
     * @param parameterSetter a BiConsumer to set the parameters of the {@link PreparedQuery} for each row; must not be {@code null}
     * @return the number of rows successfully imported (after filtering)
     * @throws IllegalArgumentException if {@code parameterSetter} is {@code null}, {@code batchSize <= 0}, or {@code batchIntervalInMillis < 0}
     * @throws SQLException if a database access error occurs
     */
    @Deprecated
    public static int importData(final Dataset dataset, final Predicate<? super Object[]> filter, final PreparedStatement stmt, final int batchSize,
            final long batchIntervalInMillis, final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> parameterSetter)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(dataset, cs.dataset);
        N.checkArgNotNull(stmt, "stmt");
        N.checkArgument(batchSize > 0 && batchIntervalInMillis >= 0, "'batchSize'=%s must be greater than 0 and 'batchIntervalInMillis'=%s can't be negative",
                batchSize, batchIntervalInMillis);
        N.checkArgNotNull(parameterSetter, "parameterSetter");

        final PreparedQuery stmtForSetter = new PreparedQuery(stmt);

        final int columnCount = dataset.columnNames().size();
        final Object[] row = new Object[columnCount];
        int result = 0;
        boolean hasExecutedBatch = false;

        logger.debug("Importing Dataset(rows={}, columns={}, batchSize={}, batchIntervalInMillis={})", dataset.size(), columnCount, batchSize,
                batchIntervalInMillis);

        for (int i = 0, size = dataset.size(); i < size; i++) {
            dataset.moveToRow(i);

            for (int j = 0; j < columnCount; j++) {
                row[j] = dataset.get(j);
            }

            if (filter != null && !filter.test(row)) {
                continue;
            }

            parameterSetter.accept(stmtForSetter, row);

            // Call stmt.addBatch() directly rather than stmtForSetter.addBatch(), because the
            // latter (via AbstractQuery.addBatch) closes the underlying stmt on failure — which
            // would break the documented contract that this method does not close the caller's stmt.
            stmt.addBatch();

            if ((++result % batchSize) == 0) {
                pauseBeforeSubsequentBatch(hasExecutedBatch, batchIntervalInMillis);
                JdbcUtil.executeBatch(stmt);
                hasExecutedBatch = true;
            }
        }

        if ((result % batchSize) > 0) {
            pauseBeforeSubsequentBatch(hasExecutedBatch, batchIntervalInMillis);
            JdbcUtil.executeBatch(stmt);
        }

        logger.info("Imported Dataset rows(imported={}, sourceRows={}, columns={})", result, dataset.size(), columnCount);

        return result;
    }

    /**
     * Imports data from an Iterator to the database using the specified DataSource and SQL insert statement.
     * This method uses default batch processing settings for optimal performance.
     *
     * <p>This method is ideal for importing data from collections, streams, or any other iterable source.
     * The statement setter is responsible for mapping each element from the iterator to the PreparedStatement parameters.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Import user data from a list
     * List<User> users = loadUsersFromFile();
     * DataSource dataSource = getDataSource();
     * String insertSql = "INSERT INTO users (id, name, email) VALUES (?, ?, ?)";
     *
     * long rowsImported = DataTransferUtil.importData(users.iterator(), dataSource, insertSql,
     *     (stmt, user) -> {
     *         stmt.setLong(1, user.getId());
     *         stmt.setString(2, user.getName());
     *         stmt.setString(3, user.getEmail());
     *     });
     *
     * System.out.println("Imported " + rowsImported + " users");
     * }</pre>
     *
     * @param <T> iterator element type
     * @param iter the Iterator containing the data to be imported
     * @param targetDataSource the DataSource to obtain database connections from
     * @param insertSql the SQL insert statement with parameter placeholders ({@code ?})
     * @param parameterSetter a BiConsumer to map iterator elements to {@link PreparedQuery} parameters; must not be {@code null}
     * @return the total number of rows successfully inserted
     * @throws IllegalArgumentException if {@code iter} or {@code parameterSetter} is {@code null}
     * @throws SQLException if a database access error occurs
     * @see LineIterator#of(File)
     * @see LineIterator#of(Reader)
     */
    public static <T> long importData(final Iterator<? extends T> iter, final javax.sql.DataSource targetDataSource, final String insertSql,
            final Throwables.BiConsumer<? super PreparedQuery, ? super T, SQLException> parameterSetter) throws SQLException {
        final Connection conn = JdbcUtil.getConnection(targetDataSource);

        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSql)) {
            return importData(iter, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, parameterSetter);
        } finally {
            JdbcUtil.releaseConnection(conn, targetDataSource);
        }
    }

    /**
     * Imports data from an Iterator to the database using the provided Connection with configurable batch processing.
     * This method provides more control over the import process compared to the DataSource variant.
     *
     * <p>This method allows fine-tuning of batch processing parameters for optimal performance based on
     * the specific use case and data volume.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Import large dataset with custom batch settings
     * Iterator<Product> products = loadProductsIterator();
     * Connection conn = dataSource.getConnection();
     * String insertSql = "INSERT INTO products (sku, name, price, stock) VALUES (?, ?, ?, ?)";
     *
     * try {
     *     long rowsImported = DataTransferUtil.importData(products, conn, insertSql,
     *         5000,  // larger batch size for better performance
     *         100,   // 100ms pause between batches to avoid overwhelming the DB
     *         (stmt, product) -> {
     *             stmt.setString(1, product.getSku());
     *             stmt.setString(2, product.getName());
     *             stmt.setBigDecimal(3, product.getPrice());
     *             stmt.setInt(4, product.getStock());
     *         });
     *
     *     System.out.println("Successfully imported " + rowsImported + " products");
     * } finally {
     *     conn.close();
     * }
     * }</pre>
     *
     * @param <T> iterator element type
     * @param iter the Iterator containing the data to be imported
     * @param conn the Connection to the database (will not be closed by this method)
     * @param insertSql the SQL insert statement with parameter placeholders ({@code ?})
     * @param batchSize the number of rows to accumulate before executing a batch insert (must be greater than 0)
     * @param batchIntervalInMillis the pause duration in milliseconds between batch executions (must be {@code >= 0})
     * @param parameterSetter a BiConsumer to map iterator elements to {@link PreparedQuery} parameters; must not be {@code null}
     * @return the total number of rows successfully inserted
     * @throws IllegalArgumentException if {@code iter} or {@code parameterSetter} is {@code null},
     *         {@code batchSize <= 0}, or {@code batchIntervalInMillis < 0}
     * @throws SQLException if a database access error occurs
     * @see LineIterator#of(File)
     * @see LineIterator#of(Reader)
     */
    @Deprecated
    public static <T> long importData(final Iterator<? extends T> iter, final Connection conn, final String insertSql, final int batchSize,
            final long batchIntervalInMillis, final Throwables.BiConsumer<? super PreparedQuery, ? super T, SQLException> parameterSetter) throws SQLException {
        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSql)) {
            return importData(iter, stmt, batchSize, batchIntervalInMillis, parameterSetter);
        }
    }

    /**
     * Imports data from an Iterator to the database using the provided PreparedStatement with configurable batch processing.
     * This is the lowest-level import method providing maximum control over the import process.
     *
     * <p>This method is useful when you need to reuse a PreparedStatement or have specific statement configuration requirements.
     * The PreparedStatement will not be closed by this method, allowing for reuse in subsequent operations.</p>
     *
     * <p>Performance tips:</p>
     * <ul>
     *   <li>Larger batch sizes generally improve performance but consume more memory</li>
     *   <li>Batch intervals can help prevent database overload during massive imports</li>
     *   <li>Consider the database's maximum packet size when setting batch size</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Import with transaction control and prepared statement reuse
     * Connection conn = dataSource.getConnection();
     * conn.setAutoCommit(false);
     *
     * PreparedStatement stmt = conn.prepareStatement(
     *     "INSERT INTO orders (order_id, customer_id, total, status) VALUES (?, ?, ?, ?)");
     *
     * try {
     *     // Import pending orders
     *     Iterator<Order> pendingOrders = getPendingOrders();
     *     long pending = DataTransferUtil.importData(pendingOrders, stmt, 1000, 0,
     *         (query, order) -> {
     *             query.setLong(1, order.getId());
     *             query.setLong(2, order.getCustomerId());
     *             query.setBigDecimal(3, order.getTotal());
     *             query.setString(4, "PENDING");
     *         });
     *
     *     // Reuse statement for completed orders
     *     Iterator<Order> completedOrders = getCompletedOrders();
     *     long completed = DataTransferUtil.importData(completedOrders, stmt, 1000, 0,
     *         (query, order) -> {
     *             query.setLong(1, order.getId());
     *             query.setLong(2, order.getCustomerId());
     *             query.setBigDecimal(3, order.getTotal());
     *             query.setString(4, "COMPLETED");
     *         });
     *
     *     conn.commit();
     *     System.out.println("Imported " + pending + " pending and " + completed + " completed orders");
     * } catch (Exception e) {
     *     conn.rollback();
     *     throw e;
     * } finally {
     *     stmt.close();
     *     conn.close();
     * }
     * }</pre>
     *
     * @param <T> iterator element type
     * @param iter the Iterator containing the data to be imported
     * @param stmt the PreparedStatement to be used for the import (will not be closed)
     * @param batchSize the number of rows to accumulate before executing a batch insert (must be greater than 0)
     * @param batchIntervalInMillis the pause duration in milliseconds between batch executions (must be {@code >= 0})
     * @param parameterSetter a BiConsumer to map iterator elements to {@link PreparedQuery} parameters; must not be {@code null}
     * @return the total number of rows successfully inserted
     * @throws IllegalArgumentException if {@code iter} or {@code parameterSetter} is {@code null},
     *         {@code batchSize <= 0}, or {@code batchIntervalInMillis < 0}
     * @throws SQLException if a database access error occurs
     * @see LineIterator#of(File)
     * @see LineIterator#of(Reader)
     */
    @Deprecated
    public static <T> long importData(final Iterator<? extends T> iter, final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super T, SQLException> parameterSetter) throws SQLException {
        return importData(iter, (Predicate<? super T>) null, stmt, batchSize, batchIntervalInMillis, parameterSetter);
    }

    /**
     * Internal core shared by the public {@code importData(Iterator, ...)} overloads and {@link RowImportBuilder}:
     * iterates {@code iter}, applies the optional {@code filter}, binds each surviving element via {@code parameterSetter}
     * and batches the inserts.
     *
     * @param <T> the iterator element type
     * @param iter the elements to import
     * @param filter an optional per-element filter; {@code null} imports every element
     * @param stmt the PreparedStatement (not closed here)
     * @param batchSize the batch size (must be {@code > 0})
     * @param batchIntervalInMillis the inter-batch pause (must be {@code >= 0})
     * @param parameterSetter binds each surviving element to the statement parameters
     * @return the number of rows imported
     * @throws SQLException if a database access error occurs
     */
    private static <T> long importData(final Iterator<? extends T> iter, final Predicate<? super T> filter, final PreparedStatement stmt, final int batchSize,
            final long batchIntervalInMillis, final Throwables.BiConsumer<? super PreparedQuery, ? super T, SQLException> parameterSetter) throws SQLException {
        N.checkArgNotNull(iter, "iter");
        N.checkArgNotNull(stmt, "stmt");
        N.checkArgNotNull(parameterSetter, "parameterSetter");
        N.checkArgument(batchSize > 0 && batchIntervalInMillis >= 0, "'batchSize'=%s must be greater than 0 and 'batchIntervalInMillis'=%s can't be negative",
                batchSize, batchIntervalInMillis);

        final PreparedQuery stmtForSetter = new PreparedQuery(stmt);
        long result = 0;
        boolean hasExecutedBatch = false;

        logger.debug("Importing iterator data(batchSize={}, batchIntervalInMillis={})", batchSize, batchIntervalInMillis);

        T next = null;
        while (iter.hasNext()) {
            next = iter.next();

            if (filter != null && !filter.test(next)) {
                continue;
            }

            parameterSetter.accept(stmtForSetter, next);
            // Call stmt.addBatch() directly to avoid AbstractQuery.addBatch closing the caller's
            // stmt on failure (the doc guarantees this method does not close it).
            stmt.addBatch();

            if ((++result % batchSize) == 0) {
                pauseBeforeSubsequentBatch(hasExecutedBatch, batchIntervalInMillis);
                JdbcUtil.executeBatch(stmt);
                hasExecutedBatch = true;
            }
        }

        if ((result % batchSize) > 0) {
            pauseBeforeSubsequentBatch(hasExecutedBatch, batchIntervalInMillis);
            JdbcUtil.executeBatch(stmt);
        }

        logger.info("Imported iterator data rows(imported={})", result);

        return result;
    }

    /**
     * Imports data from a CSV file to the database using the specified DataSource.
     * This method uses default batch processing settings.
     *
     * <p>The first line of the CSV file is treated as a header row and will be skipped during import.
     * The provided statement setter is responsible for parsing each CSV row value and binding it to
     * the appropriate {@link PreparedQuery} parameter.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Import customer data from CSV
     * File csvFile = new File("customers.csv");
     * DataSource dataSource = getDataSource();
     * String insertSql = "INSERT INTO customers (name, email, phone) VALUES (?, ?, ?)";
     *
     * long rowsImported = DataTransferUtil.importCsv(csvFile, dataSource, insertSql,
     *     (stmt, row) -> {
     *         stmt.setString(1, row[0]);   // name
     *         stmt.setString(2, row[1]);   // email
     *         stmt.setString(3, row[2]);   // phone
     *     });
     *
     * System.out.println("Imported " + rowsImported + " customers from CSV");
     * }</pre>
     *
     * @param file the CSV file containing the data to be imported
     * @param targetDataSource the DataSource to obtain database connections from
     * @param insertSql the SQL insert statement with parameter placeholders ({@code ?})
     * @param parameterSetter a BiConsumer to set {@link PreparedQuery} parameters from each CSV row's values; must not be {@code null}
     * @return the total number of rows successfully imported
     * @throws IllegalArgumentException if {@code parameterSetter} is {@code null}
     * @throws SQLException if a database access error occurs
     * @throws UncheckedIOException if an I/O error occurs while reading the file
     */
    public static long importCsv(final File file, final javax.sql.DataSource targetDataSource, final String insertSql,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> parameterSetter) throws SQLException {
        final Connection conn = JdbcUtil.getConnection(targetDataSource);

        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSql)) {
            return importCsv(file, stmt, parameterSetter);
        } finally {
            JdbcUtil.releaseConnection(conn, targetDataSource);
        }
    }

    /**
     * Imports data from a CSV file to the database using the provided Connection with configurable batch processing.
     * This method provides control over batch size and processing intervals for optimal performance.
     *
     * <p>The first line of the CSV file is treated as a header row and will be skipped during import.</p>
     *
     * <p>This method is useful for importing large CSV files where you need to control memory usage
     * and database load through batch processing parameters.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Import large product catalog with optimized batch settings
     * File csvFile = new File("product_catalog.csv");
     * Connection conn = dataSource.getConnection();
     * String insertSql = "INSERT INTO products (sku, name, category, price) VALUES (?, ?, ?, ?)";
     *
     * try {
     *     long rowsImported = DataTransferUtil.importCsv(csvFile, conn, insertSql,
     *         2000,  // batch size
     *         50,    // 50ms pause between batches
     *         (stmt, row) -> {
     *             stmt.setString(1, row[0]);
     *             stmt.setString(2, row[1]);
     *             stmt.setString(3, row[2]);
     *             stmt.setBigDecimal(4, new BigDecimal(row[3]));
     *         });
     *
     *     System.out.println("Successfully imported " + rowsImported + " products");
     * } finally {
     *     conn.close();
     * }
     * }</pre>
     *
     * @param file the CSV file containing the data to be imported
     * @param conn the Connection to the database (will not be closed by this method)
     * @param insertSql the SQL insert statement with parameter placeholders ({@code ?})
     * @param batchSize the number of rows to accumulate before executing a batch insert (must be greater than 0)
     * @param batchIntervalInMillis the pause duration in milliseconds between batch executions (must be {@code >= 0})
     * @param parameterSetter a BiConsumer to set {@link PreparedQuery} parameters from each CSV row's values; must not be {@code null}
     * @return the total number of rows successfully imported
     * @throws IllegalArgumentException if {@code parameterSetter} is {@code null}, {@code batchSize <= 0}, or {@code batchIntervalInMillis < 0}
     * @throws SQLException if a database access error occurs
     * @throws UncheckedIOException if an I/O error occurs while reading the file
     */
    @Deprecated
    public static long importCsv(final File file, final Connection conn, final String insertSql, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> parameterSetter) throws SQLException {
        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSql)) {
            return importCsv(file, stmt, batchSize, batchIntervalInMillis, parameterSetter);
        }
    }

    /**
     * Imports data from a CSV file to the database using the provided PreparedStatement with default batch settings.
     * This method provides direct control over the PreparedStatement used for import.
     *
     * <p>The PreparedStatement will not be closed by this method, allowing for reuse in subsequent operations.
     * The first line of the CSV file is treated as headers and will be skipped.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Import with custom prepared statement configuration
     * File csvFile = new File("transactions.csv");
     * PreparedStatement stmt = conn.prepareStatement(
     *     "INSERT INTO transactions (account_id, amount, type, date) VALUES (?, ?, ?, ?)",
     *     Statement.RETURN_GENERATED_KEYS);
     *
     * long rowsImported = DataTransferUtil.importCsv(csvFile, stmt,
     *     (query, row) -> {
     *         query.setLong(1, Long.parseLong(row[0]));
     *         query.setBigDecimal(2, new BigDecimal(row[1]));
     *         query.setString(3, row[2]);
     *         query.setDate(4, Date.valueOf(row[3]));
     *     });
     *
     * // Can retrieve generated keys if needed
     * ResultSet generatedKeys = stmt.getGeneratedKeys();
     * }</pre>
     *
     * @param file the CSV file containing the data to be imported
     * @param stmt the PreparedStatement to be used for the import (will not be closed)
     * @param parameterSetter a BiConsumer to set {@link PreparedQuery} parameters from CSV row values; must not be {@code null}
     * @return the total number of rows successfully imported
     * @throws IllegalArgumentException if {@code parameterSetter} is {@code null}
     * @throws SQLException if a database access error occurs
     * @throws UncheckedIOException if an I/O error occurs while reading the file
     */
    public static long importCsv(final File file, final PreparedStatement stmt,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> parameterSetter) throws SQLException {
        return importCsv(file, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, parameterSetter);
    }

    /**
     * Imports data from a CSV file to the database using the provided PreparedStatement with configurable batch processing.
     * This method provides full control over the import process including batch size and processing intervals.
     *
     * <p>The first line of the CSV file is treated as a header row and will be skipped during import.</p>
     *
     * <p>This is useful for importing large CSV files where you need fine-grained control over
     * memory usage and database load through batch processing parameters.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Import large dataset with progress tracking
     * File csvFile = new File("large_dataset.csv");
     * PreparedStatement stmt = conn.prepareStatement(
     *     "INSERT INTO records (id, data, timestamp) VALUES (?, ?, ?)");
     *
     * AtomicLong processedRows = new AtomicLong(0);
     *
     * long totalRows = DataTransferUtil.importCsv(csvFile, stmt, 5000, 100,
     *     (query, row) -> {
     *         query.setLong(1, Long.parseLong(row[0]));
     *         query.setString(2, row[1]);
     *         query.setTimestamp(3, Timestamp.valueOf(row[2]));
     *
     *         long processed = processedRows.incrementAndGet();
     *         if (processed % 10000 == 0) {
     *             System.out.println("Processed " + processed + " rows...");
     *         }
     *     });
     *
     * System.out.println("Import completed. Total rows: " + totalRows);
     * }</pre>
     *
     * @param file the CSV file containing the data to be imported
     * @param stmt the PreparedStatement to be used for the import (will not be closed)
     * @param batchSize the number of rows to accumulate before executing a batch insert (must be greater than 0)
     * @param batchIntervalInMillis the pause duration in milliseconds between batch executions (must be {@code >= 0})
     * @param parameterSetter a BiConsumer to set {@link PreparedQuery} parameters from CSV row values; must not be {@code null}
     * @return the total number of rows successfully imported
     * @throws IllegalArgumentException if {@code parameterSetter} is {@code null}, {@code batchSize <= 0}, or {@code batchIntervalInMillis < 0}
     * @throws SQLException if a database access error occurs
     * @throws UncheckedIOException if an I/O error occurs while reading the file
     */
    @Deprecated
    public static long importCsv(final File file, final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> parameterSetter) throws SQLException {
        return importCsv(file, null, stmt, batchSize, batchIntervalInMillis, parameterSetter);
    }

    /**
     * Imports data from a CSV file to the database with row filtering capability.
     * This method allows selective import of CSV rows based on a filter predicate.
     *
     * <p>The first line of the CSV file is treated as a header row and will be skipped during import.
     * The filter predicate is applied to each subsequent CSV row (as a String array).
     * Only rows for which the filter returns {@code true} will be imported to the database.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Import only active users from CSV
     * File csvFile = new File("all_users.csv");
     * PreparedStatement stmt = conn.prepareStatement(
     *     "INSERT INTO active_users (id, name, email, status) VALUES (?, ?, ?, ?)");
     *
     * // Filter to import only users with "ACTIVE" status (assuming status is in column 3)
     * Predicate<String[]> activeUsersFilter = row -> "ACTIVE".equals(row[3]);
     *
     * long rowsImported = DataTransferUtil.importCsv(csvFile, activeUsersFilter, stmt, 1000, 0,
     *     (query, row) -> {
     *         query.setLong(1, Long.parseLong(row[0]));
     *         query.setString(2, row[1]);
     *         query.setString(3, row[2]);
     *         query.setString(4, row[3]);
     *     });
     *
     * System.out.println("Imported " + rowsImported + " active users");
     * }</pre>
     *
     * @param file the CSV file containing the data to be imported
     * @param filter a predicate to filter rows; only rows returning {@code true} will be imported. If {@code null}, every row is imported
     * @param stmt the PreparedStatement to be used for the import (will not be closed)
     * @param batchSize the number of rows to accumulate before executing a batch insert (must be greater than 0)
     * @param batchIntervalInMillis the pause duration in milliseconds between batch executions (must be {@code >= 0})
     * @param parameterSetter a BiConsumer to set {@link PreparedQuery} parameters from CSV row values; must not be {@code null}
     * @return the total number of rows successfully imported (after filtering)
     * @throws IllegalArgumentException if {@code parameterSetter} is {@code null}, {@code batchSize <= 0}, or {@code batchIntervalInMillis < 0}
     * @throws SQLException if a database access error occurs
     * @throws UncheckedIOException if an I/O error occurs while reading the file
     */
    @Deprecated
    public static long importCsv(final File file, final Predicate<? super String[]> filter, final PreparedStatement stmt, final int batchSize,
            final long batchIntervalInMillis, final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> parameterSetter)
            throws SQLException {
        N.checkArgNotNull(file, "file");
        N.checkArgNotNull(stmt, "stmt");
        N.checkArgNotNull(parameterSetter, "parameterSetter");
        N.checkArgument(batchSize > 0 && batchIntervalInMillis >= 0, "'batchSize'=%s must be greater than 0 and 'batchIntervalInMillis'=%s can't be negative",
                batchSize, batchIntervalInMillis);

        try (Reader reader = IOUtil.newFileReader(file)) {
            return importCsv(reader, filter, stmt, batchSize, batchIntervalInMillis, parameterSetter);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Imports data from a CSV Reader to the database using the specified DataSource.
     * This method is useful when the CSV data comes from a source other than a file.
     *
     * <p>The Reader can be from any source such as a network stream, string, or in-memory data.
     * The first line is treated as headers and will be skipped during import.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Import CSV data from a string
     * String csvData = "name,age,city\nJohn,25,NYC\nJane,30,LA\nBob,35,Chicago";
     * Reader reader = new StringReader(csvData);
     * DataSource dataSource = getDataSource();
     * String insertSql = "INSERT INTO people (name, age, city) VALUES (?, ?, ?)";
     *
     * long rowsImported = DataTransferUtil.importCsv(reader, dataSource, insertSql,
     *     (stmt, row) -> {
     *         stmt.setString(1, row[0]);
     *         stmt.setInt(2, Integer.parseInt(row[1]));
     *         stmt.setString(3, row[2]);
     *     });
     *
     * System.out.println("Imported " + rowsImported + " rows from CSV data");
     * }</pre>
     *
     * @param reader the Reader to read the CSV data from
     * @param targetDataSource the DataSource to obtain database connections from
     * @param insertSql the SQL insert statement with parameter placeholders ({@code ?})
     * @param parameterSetter a BiConsumer to set {@link PreparedQuery} parameters from each CSV row's values; must not be {@code null}
     * @return the total number of rows successfully imported
     * @throws IllegalArgumentException if {@code reader} or {@code parameterSetter} is {@code null}
     * @throws SQLException if a database access error occurs
     * @throws UncheckedIOException if an I/O error occurs while reading from the reader
     */
    public static long importCsv(final Reader reader, final javax.sql.DataSource targetDataSource, final String insertSql,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> parameterSetter) throws SQLException {
        final Connection conn = JdbcUtil.getConnection(targetDataSource);

        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSql)) {
            return importCsv(reader, stmt, parameterSetter);
        } finally {
            JdbcUtil.releaseConnection(conn, targetDataSource);
        }
    }

    /**
     * Imports data from a CSV Reader to the database using the provided PreparedStatement with default batch settings.
     * This method provides direct control over the PreparedStatement used for import.
     *
     * <p>The first line read from the Reader is treated as a header row and will be skipped during import.
     * The PreparedStatement will not be closed by this method, allowing for reuse.
     * The Reader will be wrapped in a BufferedReader for optimal performance if not already buffered.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Import CSV data from an HTTP response
     * URL url = new URL("https://example.com/data.csv");
     * Reader reader = new InputStreamReader(url.openStream());
     * PreparedStatement stmt = conn.prepareStatement(
     *     "INSERT INTO data (col1, col2, col3) VALUES (?, ?, ?)");
     *
     * long rowsImported = DataTransferUtil.importCsv(reader, stmt,
     *     (query, row) -> {
     *         query.setString(1, row[0]);
     *         query.setString(2, row[1]);
     *         query.setString(3, row[2]);
     *     });
     *
     * System.out.println("Imported " + rowsImported + " rows from remote CSV");
     * }</pre>
     *
     * @param reader the Reader to read the CSV data from
     * @param stmt the PreparedStatement to be used for the import (will not be closed)
     * @param parameterSetter a BiConsumer to set {@link PreparedQuery} parameters from CSV row values; must not be {@code null}
     * @return the total number of rows successfully imported
     * @throws IllegalArgumentException if {@code reader} or {@code parameterSetter} is {@code null}
     * @throws SQLException if a database access error occurs
     * @throws UncheckedIOException if an I/O error occurs while reading from the reader
     */
    public static long importCsv(final Reader reader, final PreparedStatement stmt,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> parameterSetter) throws SQLException {
        return importCsv(reader, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, parameterSetter);
    }

    /**
     * Imports data from a CSV Reader to the database using the provided PreparedStatement with configurable batch processing.
     * This method provides control over batch size and processing intervals.
     *
     * <p>The first line read from the Reader is treated as a header row and will be skipped during import.</p>
     *
     * <p>This method is useful for importing CSV data from various sources with optimized batch processing
     * for better performance and resource management.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Import large CSV data with batch optimization
     * Reader reader = new InputStreamReader(largeInputStream);
     * PreparedStatement stmt = conn.prepareStatement(
     *     "INSERT INTO large_table (id, data, timestamp) VALUES (?, ?, ?)");
     *
     * long startTime = System.nanoTime();
     * long rowsImported = DataTransferUtil.importCsv(reader, stmt, 10000, 200,
     *     (query, row) -> {
     *         query.setLong(1, Long.parseLong(row[0]));
     *         query.setString(2, row[1]);
     *         query.setTimestamp(3, Timestamp.valueOf(row[2]));
     *     });
     *
     * long durationMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
     * System.out.println("Imported " + rowsImported + " rows in " + durationMillis + "ms");
     * }</pre>
     *
     * @param reader the Reader to read the CSV data from
     * @param stmt the PreparedStatement to be used for the import (will not be closed)
     * @param batchSize the number of rows to accumulate before executing a batch insert (must be greater than 0)
     * @param batchIntervalInMillis the pause duration in milliseconds between batch executions (must be {@code >= 0})
     * @param parameterSetter a BiConsumer to set {@link PreparedQuery} parameters from CSV row values; must not be {@code null}
     * @return the total number of rows successfully imported
     * @throws IllegalArgumentException if {@code reader} or {@code parameterSetter} is {@code null},
     *         {@code batchSize <= 0}, {@code batchIntervalInMillis < 0}, or a data row has more fields than the header
     * @throws SQLException if a database access error occurs
     * @throws UncheckedIOException if an I/O error occurs while reading from the reader
     */
    @Deprecated
    public static long importCsv(final Reader reader, final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> parameterSetter) throws SQLException {
        return importCsv(reader, null, stmt, batchSize, batchIntervalInMillis, parameterSetter);
    }

    /**
     * Imports data from a CSV Reader to the database with row filtering capability and configurable batch processing.
     * This is the most comprehensive CSV import method providing full control over the import process.
     *
     * <p>The first line read from the Reader is treated as a header row and will be skipped during import.</p>
     *
     * <p>This method combines all import features:</p>
     * <ul>
     *   <li>Custom data source (Reader)</li>
     *   <li>Row filtering before import</li>
     *   <li>Configurable batch processing</li>
     *   <li>Custom value mapping</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Import CSV data with complex filtering and validation
     * Reader reader = new FileReader("user_data.csv");
     * PreparedStatement stmt = conn.prepareStatement(
     *     "INSERT INTO users (id, email, age, country) VALUES (?, ?, ?, ?)");
     *
     * // Complex filter: valid email, age >= 18, allowed countries
     * Set<String> allowedCountries = Set.of("US", "CA", "UK", "AU");
     * Predicate<String[]> complexFilter = row -> {
     *     // Validate email format (simple check)
     *     if (!row[1].contains("@")) return false;
     *
     *     // Check age >= 18
     *     try {
     *         if (Integer.parseInt(row[2]) < 18) return false;
     *     } catch (NumberFormatException e) {
     *         return false;
     *     }
     *
     *     // Check allowed countries
     *     return allowedCountries.contains(row[3]);
     * };
     *
     * long rowsImported = DataTransferUtil.importCsv(reader, complexFilter, stmt, 2000, 0,
     *     (query, row) -> {
     *         query.setLong(1, Long.parseLong(row[0]));
     *         query.setString(2, row[1].toLowerCase());   // normalize email
     *         query.setInt(3, Integer.parseInt(row[2]));
     *         query.setString(4, row[3]);
     *     });
     *
     * System.out.println("Imported " + rowsImported + " valid users");
     * }</pre>
     *
     * @param reader the Reader to read the CSV data from
     * @param filter a predicate to filter rows; only rows returning {@code true} will be imported. If {@code null}, every row is imported
     * @param stmt the PreparedStatement to be used for the import (will not be closed)
     * @param batchSize the number of rows to accumulate before executing a batch insert (must be greater than 0)
     * @param batchIntervalInMillis the pause duration in milliseconds between batch executions (must be {@code >= 0})
     * @param parameterSetter a BiConsumer to set {@link PreparedQuery} parameters from CSV row values; must not be {@code null}
     * @return the total number of rows successfully imported (after filtering)
     * @throws IllegalArgumentException if {@code reader} or {@code parameterSetter} is {@code null},
     *         {@code batchSize <= 0}, {@code batchIntervalInMillis < 0}, or a data row has more fields than the header
     * @throws SQLException if a database access error occurs
     * @throws UncheckedIOException if an I/O error occurs while reading from the reader
     */
    @Deprecated
    public static long importCsv(final Reader reader, final Predicate<? super String[]> filter, final PreparedStatement stmt, final int batchSize,
            final long batchIntervalInMillis, final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> parameterSetter)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(reader, "reader");
        N.checkArgNotNull(stmt, "stmt");
        N.checkArgNotNull(parameterSetter, "parameterSetter");
        N.checkArgument(batchSize > 0 && batchIntervalInMillis >= 0, "'batchSize'=%s must be greater than 0 and 'batchIntervalInMillis'=%s can't be negative",
                batchSize, batchIntervalInMillis);

        final PreparedQuery stmtForSetter = new PreparedQuery(stmt);
        final Function<String, String[]> headerParser = CsvUtil.getCurrentHeaderParser();
        final BiConsumer<String, String[]> lineParser = CsvUtil.getCurrentLineParser();
        final boolean isBufferedReader = IOUtil.isBufferedReader(reader);
        final BufferedReader br = isBufferedReader ? (BufferedReader) reader : Objectory.createBufferedReader(reader);
        long result = 0;
        boolean hasExecutedBatch = false;

        logger.debug("Importing CSV data(batchSize={}, batchIntervalInMillis={})", batchSize, batchIntervalInMillis);

        try {
            String line = br.readLine();

            if (line == null) {
                logger.info("Imported CSV data rows(imported=0, columns=0)");
                return 0;
            }

            final String[] titles = headerParser.apply(line);

            int columnCount = titles.length;
            final String[] output = new String[columnCount];

            while ((line = br.readLine()) != null) {
                parseCsvRow(lineParser, line, output);

                if (filter != null && !filter.test(output)) {
                    N.fill(output, null);
                    continue;
                }

                parameterSetter.accept(stmtForSetter, output);
                // Call stmt.addBatch() directly to avoid AbstractQuery.addBatch closing the caller's
                // stmt on failure (the doc guarantees this method does not close it).
                stmt.addBatch();

                if ((++result % batchSize) == 0) {
                    pauseBeforeSubsequentBatch(hasExecutedBatch, batchIntervalInMillis);
                    JdbcUtil.executeBatch(stmt);
                    hasExecutedBatch = true;
                }

                N.fill(output, null);
            }

            if ((result % batchSize) > 0) {
                pauseBeforeSubsequentBatch(hasExecutedBatch, batchIntervalInMillis);
                JdbcUtil.executeBatch(stmt);
            }

            logger.info("Imported CSV data rows(imported={}, columns={})", result, columnCount);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (!isBufferedReader) {
                Objectory.recycle(br);
            }
        }

        return result;
    }

    private static void parseCsvRow(final BiConsumer<String, String[]> lineParser, final String line, final String[] output) {
        try {
            lineParser.accept(line, output);
        } catch (final IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("CSV data row has more fields than the header's " + output.length + " column(s): " + line, e);
        }
    }

    /**
     * Exports data from the database to a CSV file using the specified DataSource and SQL query.
     * This method executes the query and writes all results to the specified file.
     *
     * <p>The output CSV file will include a header row with column names from the query result.
     * Each value in the CSV will be properly quoted and escaped according to CSV standards.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Export all active users to CSV
     * DataSource dataSource = getDataSource();
     * String query = "SELECT id, name, email, registration_date FROM users WHERE active = true";
     * File outputFile = new File("active_users.csv");
     *
     * long rowsExported = DataTransferUtil.exportCsv(dataSource, query, outputFile);
     * System.out.println("Exported " + rowsExported + " active users to " + outputFile);
     * }</pre>
     *
     * @param sourceDataSource the DataSource to obtain database connections from
     * @param selectSql the SQL query to execute for retrieving data
     * @param output the File to write the CSV data to (will be created if doesn't exist)
     * @return the total number of rows exported to the CSV file
     * @throws SQLException if a database access error occurs
     * @throws UncheckedIOException if an I/O error occurs while writing to the file
     */
    public static long exportCsv(final javax.sql.DataSource sourceDataSource, final String selectSql, final File output) throws SQLException {
        final Connection conn = JdbcUtil.getConnection(sourceDataSource);

        try {
            return exportCsv(conn, selectSql, output);
        } finally {
            JdbcUtil.releaseConnection(conn, sourceDataSource);
        }
    }

    /**
     * Exports data from the database to a CSV file using the provided Connection and SQL query.
     * This method provides direct control over the database connection used for export.
     *
     * <p>The query is executed with optimal settings for large result sets, including
     * forward-only, read-only cursor and appropriate fetch size for better performance.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Export data with transaction isolation
     * Connection conn = dataSource.getConnection();
     * conn.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
     *
     * try {
     *     String query = "SELECT * FROM large_table WHERE created_date >= '2023-01-01'";
     *     File outputFile = new File("export_2023.csv");
     *
     *     long rowsExported = DataTransferUtil.exportCsv(conn, query, outputFile);
     *     System.out.println("Successfully exported " + rowsExported + " rows");
     * } finally {
     *     conn.close();
     * }
     * }</pre>
     *
     * @param conn the Connection to the database (will not be closed by this method)
     * @param selectSql the SQL query to execute for retrieving data
     * @param output the File to write the CSV data to (will be created if doesn't exist)
     * @return the total number of rows exported to the CSV file
     * @throws SQLException if a database access error occurs
     * @throws UncheckedIOException if an I/O error occurs while writing to the file
     */
    public static long exportCsv(final Connection conn, final String selectSql, final File output) throws SQLException {
        return exportCsv(conn, selectSql, null, output);
    }

    /**
     * Exports selected columns from the database to a CSV file using the provided Connection and SQL query.
     * This method allows you to specify which columns from the query result should be included in the CSV.
     *
     * <p>Only the specified columns will be written to the CSV file, in the order they appear in the result set.
     * This is useful when you want to exclude sensitive or unnecessary columns from the export.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Export only specific columns from a query result
     * Connection conn = dataSource.getConnection();
     * String query = "SELECT id, name, email, ssn, salary, department FROM employees";
     * File outputFile = new File("employee_list.csv");
     *
     * // Only export non-sensitive columns
     * Set<String> columnsToExport = Set.of("id", "name", "department");
     *
     * try {
     *     long rowsExported = DataTransferUtil.exportCsv(conn, query, columnsToExport, outputFile);
     *     System.out.println("Exported " + rowsExported + " employees (filtered columns)");
     * } finally {
     *     conn.close();
     * }
     * }</pre>
     *
     * @param conn the Connection to the database (will not be closed by this method)
     * @param selectSql the SQL query to execute for retrieving data
     * @param columnNames collection of column names to include in export ({@code null} or empty for all columns)
     * @param output the File to write the CSV data to (will be created if doesn't exist)
     * @return the total number of rows exported to the CSV file
     * @throws IllegalArgumentException if {@code output} is {@code null}, or if any specified column name is not found in the query result
     * @throws SQLException if a database access error occurs
     * @throws UncheckedIOException if an I/O error occurs while writing to the file
     */
    @Deprecated
    public static long exportCsv(final Connection conn, final String selectSql, final Collection<String> columnNames, final File output) throws SQLException {
        // Validate the output target before doing any database work, so a null output fails fast
        // instead of after the SELECT has already been executed.
        N.checkArgNotNull(output, "output");

        final ParsedSql sql = ParsedSql.parse(selectSql);

        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, sql.parameterizedSql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {

            setFetchForLargeResult(conn, stmt);

            return exportCsv(stmt, columnNames, output);
        }
    }

    /**
     * Exports data from the database to a CSV file using the provided PreparedStatement.
     * This method executes the statement and writes all results to the specified file.
     *
     * <p>This method is useful when you need to set parameters on the statement before execution
     * or when you want to reuse a prepared statement for multiple exports.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Export data with parameterized query
     * PreparedStatement stmt = conn.prepareStatement(
     *     "SELECT * FROM orders WHERE order_date BETWEEN ? AND ? AND status = ?");
     * stmt.setDate(1, Date.valueOf("2023-01-01"));
     * stmt.setDate(2, Date.valueOf("2023-12-31"));
     * stmt.setString(3, "COMPLETED");
     *
     * File outputFile = new File("completed_orders_2023.csv");
     * long rowsExported = DataTransferUtil.exportCsv(stmt, outputFile);
     *
     * System.out.println("Exported " + rowsExported + " completed orders for 2023");
     * }</pre>
     *
     * @param stmt the PreparedStatement to execute (will not be closed by this method)
     * @param output the File to write the CSV data to (will be created if doesn't exist)
     * @return the total number of rows exported to the CSV file
     * @throws SQLException if a database access error occurs
     * @throws UncheckedIOException if an I/O error occurs while writing to the file
     */
    public static long exportCsv(final PreparedStatement stmt, final File output) throws SQLException {
        return exportCsv(stmt, null, output);
    }

    /**
     * Exports selected columns from the database to a CSV file using the provided PreparedStatement.
     * This method executes the statement and writes only the specified columns to the file.
     *
     * <p>This method combines the flexibility of prepared statements with column filtering,
     * allowing precise control over what data is exported.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Export filtered data with specific columns
     * PreparedStatement stmt = conn.prepareStatement(
     *     "SELECT u.*, p.* FROM users u JOIN profiles p ON u.id = p.user_id WHERE u.country = ?");
     * stmt.setString(1, "US");
     *
     * // Only export user information, not profile data
     * Set<String> userColumns = Set.of("id", "name", "email", "country");
     * File outputFile = new File("us_users.csv");
     *
     * long rowsExported = DataTransferUtil.exportCsv(stmt, userColumns, outputFile);
     * System.out.println("Exported " + rowsExported + " US users");
     * }</pre>
     *
     * @param stmt the PreparedStatement to execute (will not be closed by this method)
     * @param columnNames collection of column names to include in export ({@code null} or empty for all columns)
     * @param output the File to write the CSV data to (will be created if doesn't exist)
     * @return the total number of rows exported to the CSV file
     * @throws IllegalArgumentException if {@code output} is {@code null}, or if any specified column name is not found in the query result
     * @throws SQLException if a database access error occurs
     * @throws UncheckedIOException if an I/O error occurs while writing to the file
     */
    @Deprecated
    public static long exportCsv(final PreparedStatement stmt, final Collection<String> columnNames, final File output) throws SQLException {
        // Validate the output target before doing any database work, so a null output fails fast
        // instead of after the query has already been executed.
        N.checkArgNotNull(output, "output");

        ResultSet rs = null;

        try {
            rs = JdbcUtil.executeQuery(stmt);

            return exportCsv(rs, columnNames, output);
        } finally {
            JdbcUtil.closeQuietly(rs);
        }
    }

    /**
     * Exports data from a ResultSet to a CSV file.
     * This method writes all columns from each row following the ResultSet's current cursor position to the file.
     *
     * <p>This overload accepts a ResultSet directly, useful when you already have a ResultSet
     * from a complex operation or need fine-grained control over the export process.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Export from a scrollable ResultSet with preprocessing
     * Statement stmt = conn.createStatement(
     *     ResultSet.TYPE_SCROLL_INSENSITIVE,
     *     ResultSet.CONCUR_READ_ONLY);
     * ResultSet rs = stmt.executeQuery("SELECT * FROM products");
     *
     * rs.absolute(100); // position on row 100; export begins with row 101
     *
     * File outputFile = new File("products_from_101.csv");
     * long rowsExported = DataTransferUtil.exportCsv(rs, outputFile);
     *
     * System.out.println("Exported " + rowsExported + " products (skipped first 100)");
     * }</pre>
     *
     * @param rs the ResultSet containing the data to export (will not be closed by this method)
     * @param output the File to write the CSV data to (will be created if doesn't exist)
     * @return the total number of rows exported to the CSV file
     * @throws SQLException if a database access error occurs
     * @throws UncheckedIOException if an I/O error occurs while writing to the file
     */
    public static long exportCsv(final ResultSet rs, final File output) throws SQLException {
        return exportCsv(rs, null, output);
    }

    /**
     * Exports selected columns from a ResultSet to a CSV file.
     * This method writes only the specified columns from the ResultSet to the file.
     *
     * <p>Column names are case-sensitive and must match exactly with the column labels in the ResultSet.
     * If a specified column is not found in the ResultSet, an IllegalArgumentException will be thrown.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Export specific columns from a complex join result
     * Statement stmt = conn.createStatement();
     * ResultSet rs = stmt.executeQuery(
     *     "SELECT o.*, c.*, p.* FROM orders o " +
     *     "JOIN customers c ON o.customer_id = c.id " +
     *     "JOIN products p ON o.product_id = p.id");
     *
     * // Only export order and customer names, not product details
     * Set<String> exportColumns = Set.of("order_id", "order_date", "customer_name", "total");
     * File outputFile = new File("order_summary.csv");
     *
     * long rowsExported = DataTransferUtil.exportCsv(rs, exportColumns, outputFile);
     * System.out.println("Exported " + rowsExported + " order summaries");
     * }</pre>
     *
     * @param rs the ResultSet containing the data to export (will not be closed by this method)
     * @param columnNames collection of column names to include in export ({@code null} or empty for all columns)
     * @param output the File to write the CSV data to (will be created if doesn't exist)
     * @return the total number of rows exported to the CSV file
     * @throws IllegalArgumentException if any specified column name is not found in the ResultSet
     * @throws SQLException if a database access error occurs
     * @throws UncheckedIOException if an I/O error occurs while writing to the file
     */
    @Deprecated
    public static long exportCsv(final ResultSet rs, final Collection<String> columnNames, final File output) throws SQLException {
        N.checkArgNotNull(output, "output");

        // Opening the writer creates a missing file atomically. A separate exists/createNewFile
        // check introduces a TOCTOU race: another process can create the file between those calls,
        // causing createNewFile() to return false even though the output is perfectly writable.
        try (Writer writer = new FileWriter(output, StandardCharsets.UTF_8)) {
            return exportCsv(rs, columnNames, writer);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Exports data from the database to a CSV Writer using the specified DataSource and SQL query.
     * This method is useful when you need to write CSV data to a custom destination.
     *
     * <p>The Writer can be any implementation such as StringWriter for in-memory CSV generation,
     * OutputStreamWriter for network transmission, or any custom Writer implementation.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Export to HTTP response
     * DataSource dataSource = getDataSource();
     * HttpServletResponse response = getResponse();
     * response.setContentType("text/csv");
     * response.setHeader("Content-Disposition", "attachment; filename=\"report.csv\"");
     *
     * Writer writer = new OutputStreamWriter(response.getOutputStream());
     * String query = "SELECT * FROM monthly_report WHERE month = CURRENT_MONTH()";
     *
     * long rowsExported = DataTransferUtil.exportCsv(dataSource, query, writer);
     * writer.flush();
     *
     * logger.info("Streamed " + rowsExported + " rows to client");
     * }</pre>
     *
     * @param sourceDataSource the DataSource to obtain database connections from
     * @param selectSql the SQL query to execute for retrieving data
     * @param output the Writer to write the CSV data to (will be flushed but not closed by this method)
     * @return the total number of rows exported
     * @throws SQLException if a database access error occurs
     * @throws UncheckedIOException if an I/O error occurs while writing
     */
    public static long exportCsv(final javax.sql.DataSource sourceDataSource, final String selectSql, final Writer output) throws SQLException {
        final Connection conn = JdbcUtil.getConnection(sourceDataSource);

        try {
            return exportCsv(conn, selectSql, output);
        } finally {
            JdbcUtil.releaseConnection(conn, sourceDataSource);
        }
    }

    /**
     * Exports data from the database to a CSV Writer using the provided Connection and SQL query.
     * This method executes the query and streams results directly to the Writer.
     *
     * <p>This method is optimized for large result sets with appropriate cursor and fetch size settings.
     * The Writer is flushed (but not closed) by this method before it returns.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Export to string for further processing
     * Connection conn = dataSource.getConnection();
     * StringWriter stringWriter = new StringWriter();
     * String query = "SELECT id, name, value FROM metrics WHERE date = CURRENT_DATE";
     *
     * try {
     *     long rowsExported = DataTransferUtil.exportCsv(conn, query, stringWriter);
     *     String csvData = stringWriter.toString();
     *
     *     // Process CSV data (e.g., send via email, store in cache, etc.)
     *     emailService.sendCsvReport(csvData);
     *
     * } finally {
     *     conn.close();
     * }
     * }</pre>
     *
     * @param conn the Connection to the database (will not be closed by this method)
     * @param selectSql the SQL query to execute for retrieving data
     * @param output the Writer to write the CSV data to (will be flushed but not closed by this method)
     * @return the total number of rows exported
     * @throws SQLException if a database access error occurs
     * @throws UncheckedIOException if an I/O error occurs while writing
     */
    public static long exportCsv(final Connection conn, final String selectSql, final Writer output) throws SQLException {
        final ParsedSql sql = ParsedSql.parse(selectSql);

        final PreparedStatement stmt = JdbcUtil.prepareStatement(conn, sql.parameterizedSql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);

        try {
            setFetchForLargeResult(conn, stmt);

            try (ResultSet rs = JdbcUtil.executeQuery(stmt)) {
                return exportCsv(rs, output);
            }
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     * Exports data from a ResultSet to a CSV Writer.
     * This method writes all columns from each row following the ResultSet's current cursor position to the Writer.
     *
     * <p>This is useful for streaming CSV data or writing to custom destinations.
     * The Writer will be flushed but not closed by this method.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Stream large result set to compressed file
     * ResultSet rs = stmt.executeQuery("SELECT * FROM large_table");
     *
     * try (FileOutputStream fos = new FileOutputStream("data.csv.gz");
     *      GZIPOutputStream gzos = new GZIPOutputStream(fos);
     *      Writer writer = new OutputStreamWriter(gzos, StandardCharsets.UTF_8)) {
     *
     *     long rowsExported = DataTransferUtil.exportCsv(rs, writer);
     *     System.out.println("Exported " + rowsExported + " rows to compressed CSV");
     * }
     * }</pre>
     *
     * @param rs the ResultSet containing the data to be exported (will not be closed by this method)
     * @param output the Writer to write the CSV data to (will be flushed but not closed by this method)
     * @return the number of rows exported
     * @throws SQLException if a database access error occurs
     * @throws UncheckedIOException if an I/O error occurs while writing
     */
    public static long exportCsv(final ResultSet rs, final Writer output) throws SQLException {
        return exportCsv(rs, null, output);
    }

    /**
     * Exports data from a ResultSet to a Writer in CSV format with column selection.
     * This method writes the specified columns from the ResultSet to the Writer in CSV format.
     * The first line contains column headers, and each subsequent line represents a row of data.
     *
     * <p>The method handles proper CSV formatting including:</p>
     * <ul>
     *   <li>Column headers in the first line</li>
     *   <li>Proper escaping of special characters</li>
     *   <li>Null value handling</li>
     *   <li>Type-aware conversion based on each value's runtime type (including heterogeneous result columns)</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Export only specific columns to CSV
     * Set<String> columns = Set.of("name", "email", "created_date");
     *
     * try (Writer writer = new FileWriter("users_export.csv")) {
     *     ResultSet rs = stmt.executeQuery("SELECT * FROM users");
     *     long exported = DataTransferUtil.exportCsv(rs, columns, writer);
     *     System.out.println("Exported " + exported + " rows");
     * }
     * }</pre>
     *
     * @param rs the ResultSet containing the data to be exported (will not be closed by this method)
     * @param columnNames the collection of column names to be selected for export; if {@code null} or empty, all columns are exported
     * @param output the Writer to write the CSV data to (will be flushed but not closed by this method)
     * @return the number of rows exported
     * @throws IllegalArgumentException if any specified column name is not found in the ResultSet
     * @throws SQLException if a database access error occurs
     * @throws UncheckedIOException if an I/O error occurs while writing
     */
    @Deprecated
    public static long exportCsv(final ResultSet rs, final Collection<String> columnNames, final Writer output) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(rs, "rs");
        N.checkArgNotNull(output, "output");

        final Type<Object> strType = N.typeOf(String.class);
        final boolean isBufferedWriter = output instanceof BufferedCsvWriter;
        final BufferedCsvWriter bw = isBufferedWriter ? (BufferedCsvWriter) output : Objectory.createBufferedCsvWriter(output);
        long result = 0;

        logger.debug("Exporting ResultSet to CSV(columnNames={})", columnNames == null ? null : columnNames.size());

        try {
            final boolean checkDateType = JdbcUtil.checkDateType(rs);

            final ResultSetMetaData rsmd = rs.getMetaData();
            final int columnCount = rsmd.getColumnCount();
            final String[] resultColumnNames = new String[columnCount];
            final Set<String> columnNameSet = N.isEmpty(columnNames) ? null : N.newHashSet(columnNames);
            String label = null;

            for (int i = 0; i < columnCount; i++) {
                label = JdbcUtil.getColumnLabel(rsmd, i + 1);

                if (columnNameSet == null || columnNameSet.remove(label)) {
                    resultColumnNames[i] = label;
                }
            }

            if (columnNameSet != null && columnNameSet.size() > 0) {
                throw new IllegalArgumentException(columnNameSet + " are not included in the query result");
            }

            // Duplicate names in columnNames match a single result column, so count the
            // matched columns rather than trusting columnNames.size().
            int exportedColumnCount = 0;

            for (final String columnName : resultColumnNames) {
                if (columnName != null) {
                    exportedColumnCount++;
                }
            }

            final char separator = SK._COMMA;

            for (int i = 0, j = 0, len = resultColumnNames.length; i < len; i++) {
                if (resultColumnNames[i] == null) {
                    continue;
                }

                if (j++ > 0) {
                    bw.write(separator);
                }

                CsvUtil.writeField(bw, strType, resultColumnNames[i]);
            }

            bw.write(IOUtil.LINE_SEPARATOR_UNIX);

            Object value = null;

            while (rs.next()) {
                if (result++ > 0) {
                    bw.write(IOUtil.LINE_SEPARATOR_UNIX);
                }

                for (int i = 0, j = 0; i < columnCount; i++) {
                    if (resultColumnNames[i] == null) {
                        continue;
                    }

                    if (j++ > 0) {
                        bw.write(separator);
                    }

                    // Do not cache a column's Type from its first non-null value. JDBC drivers are
                    // allowed to return heterogeneous runtime types for dynamic/union expressions
                    // (and schemaless databases can do so for ordinary columns). Reusing the first
                    // Type would invoke its typed ResultSet getter for later rows, silently coercing
                    // or truncating values that have a different runtime type.
                    value = JdbcUtil.getColumnValue(rs, i + 1, checkDateType);

                    if (value == null) {
                        bw.write(NULL_CHAR_ARRAY);
                    } else {
                        CsvUtil.writeField(bw, N.typeOf(value.getClass()), value);
                    }
                }
            }

            bw.flush();

            logger.info("Exported CSV rows(exported={}, columns={})", result, exportedColumnCount);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (!isBufferedWriter) {
                Objectory.recycle(bw);
            }
        }

        return result;
    }

    private static final Supplier<Throwables.BiConsumer<PreparedQuery, ResultSet, SQLException>> supplierOfStmtSetterByRS = () -> new Throwables.BiConsumer<>() {
        private int columnCount = -1;

        @Override
        public void accept(final PreparedQuery stmt, final ResultSet rs) throws SQLException {
            if (columnCount < 0) {
                columnCount = rs.getMetaData().getColumnCount();
            }

            for (int columnIndex = 1; columnIndex <= columnCount; columnIndex++) {
                stmt.setObject(columnIndex, JdbcUtil.getColumnValue(rs, columnIndex));
            }
        }
    };

    /**
     * Copies all data from a table in the source data source to a table with the same name in the target data source.
     * This method uses default batch processing settings for optimal performance.
     *
     * <p>The method automatically generates appropriate SELECT and INSERT statements based on the table schema.
     * All columns from the source table are copied to the target table.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Copy entire table between databases
     * long rowsCopied = DataTransferUtil.copy(sourceDataSource, targetDataSource, "customers");
     * System.out.println("Copied " + rowsCopied + " rows");
     * }</pre>
     *
     * @param sourceDataSource the data source from which to copy data
     * @param targetDataSource the data source to which to copy data
     * @param tableName the name of the table to copy
     * @return the number of rows copied
     * @throws SQLException if a database access error occurs or the table doesn't exist
     */
    public static long copy(final javax.sql.DataSource sourceDataSource, final javax.sql.DataSource targetDataSource, final String tableName)
            throws SQLException {
        return copy(sourceDataSource, targetDataSource, tableName, tableName);
    }

    /**
     * Copies all data from a table in the source data source to a table in the target data source.
     * The source and target tables can have different names.
     *
     * <p>This method is useful for copying data between tables with different names or
     * for creating backup tables with a different naming convention.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Copy table to a backup table with different name
     * long rowsCopied = DataTransferUtil.copy(sourceDS, targetDS, "customers", "customers_backup");
     * System.out.println("Backed up " + rowsCopied + " customer records");
     * }</pre>
     *
     * @param sourceDataSource the data source from which to copy data
     * @param targetDataSource the data source to which to copy data
     * @param sourceTableName the name of the table in the source data source
     * @param targetTableName the name of the table in the target data source
     * @return the number of rows copied
     * @throws SQLException if a database access error occurs or either table doesn't exist
     */
    public static long copy(final javax.sql.DataSource sourceDataSource, final javax.sql.DataSource targetDataSource, final String sourceTableName,
            final String targetTableName) throws SQLException {
        return copy(sourceDataSource, targetDataSource, sourceTableName, targetTableName, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Copies all data from a table in the source data source to a table in the target data source
     * with a specified batch size for performance tuning.
     *
     * <p>The batch size controls how many rows are accumulated before executing a batch insert.
     * Larger batch sizes can improve performance for large data transfers but require more memory.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Copy large table with custom batch size
     * long rowsCopied = DataTransferUtil.copy(sourceDS, targetDS, "large_table", "large_table", 5000);
     * System.out.println("Copied " + rowsCopied + " rows in batches of 5000");
     * }</pre>
     *
     * @param sourceDataSource the data source from which to copy data
     * @param targetDataSource the data source to which to copy data
     * @param sourceTableName the name of the table in the source data source
     * @param targetTableName the name of the table in the target data source
     * @param batchSize the number of rows to copy in each batch (must be greater than 0)
     * @return the number of rows copied
     * @throws IllegalArgumentException if {@code batchSize <= 0}
     * @throws SQLException if a database access error occurs
     */
    @Deprecated
    public static long copy(final javax.sql.DataSource sourceDataSource, final javax.sql.DataSource targetDataSource, final String sourceTableName,
            final String targetTableName, final int batchSize) throws SQLException {
        N.checkArgPositive(batchSize, cs.batchSize);

        String selectSql = null;
        String insertSql = null;
        Connection sourceConn = null;
        Connection targetConn = null;

        try {
            sourceConn = JdbcUtil.getConnection(sourceDataSource);
            targetConn = JdbcUtil.getConnection(targetDataSource);

            // Generate the SELECT from source first, then derive the column ordering from the
            // source's result-set metadata and use it to drive the INSERT against the target.
            // Otherwise, when source and target have the same column set but different metadata
            // orders, positional setObject(i, ...) silently swaps values between columns.
            selectSql = JdbcCodeGenerationUtil.generateSelectSql(sourceConn, sourceTableName);
            insertSql = generateInsertSqlFromSelectColumns(sourceConn, selectSql, targetConn, targetTableName);
        } finally {
            // Release both connections even if one release throws (avoid leaking the second).
            try {
                JdbcUtil.releaseConnection(sourceConn, sourceDataSource);
            } finally {
                JdbcUtil.releaseConnection(targetConn, targetDataSource);
            }
        }

        return copy(sourceDataSource, selectSql, N.max(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET, batchSize), targetDataSource, insertSql, batchSize);
    }

    /**
     * Copies specified columns from a table in the source data source to a table in the target data source.
     * Only the columns specified in the collection will be copied.
     *
     * <p>This method is useful when you need to copy only a subset of columns or when the
     * target table has a different structure than the source table.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Copy only specific columns
     * Set<String> columns = Set.of("id", "name", "email", "status");
     * long rowsCopied = DataTransferUtil.copy(sourceDS, targetDS, "users", "active_users", columns);
     * System.out.println("Copied " + rowsCopied + " users with selected columns");
     * }</pre>
     *
     * @param sourceDataSource the data source from which to copy data
     * @param targetDataSource the data source to which to copy data
     * @param sourceTableName the name of the table in the source data source
     * @param targetTableName the name of the table in the target data source
     * @param columnNames the collection of column names to copy; {@code null} or empty copies all columns
     * @return the number of rows copied
     * @throws SQLException if a database access error occurs or any specified column doesn't exist
     */
    @Deprecated
    public static long copy(final javax.sql.DataSource sourceDataSource, final javax.sql.DataSource targetDataSource, final String sourceTableName,
            final String targetTableName, final Collection<String> columnNames) throws SQLException {
        return copy(sourceDataSource, targetDataSource, sourceTableName, targetTableName, columnNames, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Copies specified columns from a table in the source data source to a table in the target data source
     * with a custom batch size for performance tuning.
     *
     * <p>This method combines column selection with batch size control for optimized copying
     * of partial table data.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Copy specific columns with large batch size for performance
     * List<String> columns = Arrays.asList("customer_id", "order_date", "total_amount");
     * long rowsCopied = DataTransferUtil.copy(sourceDS, targetDS, "orders", "order_summary",
     *                                   columns, 10000);
     * System.out.println("Copied " + rowsCopied + " order summaries");
     * }</pre>
     *
     * @param sourceDataSource the data source from which to copy data
     * @param targetDataSource the data source to which to copy data
     * @param sourceTableName the name of the table in the source data source
     * @param targetTableName the name of the table in the target data source
     * @param columnNames the collection of column names to copy; {@code null} or empty copies all columns
     * @param batchSize the number of rows to copy in each batch (must be greater than 0)
     * @return the number of rows copied
     * @throws IllegalArgumentException if {@code batchSize <= 0}
     * @throws SQLException if a database access error occurs
     */
    @Deprecated
    public static long copy(final javax.sql.DataSource sourceDataSource, final javax.sql.DataSource targetDataSource, final String sourceTableName,
            final String targetTableName, final Collection<String> columnNames, final int batchSize) throws SQLException {
        N.checkArgPositive(batchSize, cs.batchSize);

        String selectSql = null;
        String insertSql = null;
        Connection sourceConn = null;
        Connection targetConn = null;

        try {
            sourceConn = JdbcUtil.getConnection(sourceDataSource);
            targetConn = JdbcUtil.getConnection(targetDataSource);

            selectSql = generateSelectSql(sourceConn, sourceTableName, columnNames);
            insertSql = generateInsertSql(targetConn, targetTableName, columnNames);
        } finally {
            // Release both connections even if one release throws (avoid leaking the second).
            try {
                JdbcUtil.releaseConnection(sourceConn, sourceDataSource);
            } finally {
                JdbcUtil.releaseConnection(targetConn, targetDataSource);
            }
        }

        return copy(sourceDataSource, selectSql, N.max(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET, batchSize), targetDataSource, insertSql, batchSize);
    }

    /**
     * Copies data from a source data source to a target data source using custom SQL queries.
     * This method provides full control over the SELECT and INSERT statements used for copying.
     *
     * <p>The method uses default fetch and batch sizes for optimal performance with large result sets.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Copy with custom WHERE clause
     * String selectSql = "SELECT id, name, status FROM users WHERE status = 'ACTIVE'";
     * String insertSql = "INSERT INTO active_users (id, name, status) VALUES (?, ?, ?)";
     *
     * long rowsCopied = DataTransferUtil.copy(sourceDS, selectSql, targetDS, insertSql);
     * System.out.println("Copied " + rowsCopied + " active users");
     * }</pre>
     *
     * @param sourceDataSource the data source from which to copy data
     * @param selectSql the SQL query to select data from the source data source
     * @param targetDataSource the data source to which to copy data
     * @param insertSql the SQL query to insert data into the target data source (must have matching parameter placeholders)
     * @return the number of rows copied
     * @throws SQLException if a database access error occurs or SQL statements are invalid
     */
    public static long copy(final javax.sql.DataSource sourceDataSource, final String selectSql, final javax.sql.DataSource targetDataSource,
            final String insertSql) throws SQLException {
        return copy(sourceDataSource, selectSql, JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET, targetDataSource, insertSql, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Copies data from a source data source to a target data source using custom SQL queries
     * with specified fetch and batch sizes for performance optimization.
     *
     * <p>The fetch size controls how many rows are retrieved from the source at once,
     * while the batch size controls how many rows are inserted at once. For optimal performance,
     * fetch size should be larger than batch size.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Copy large dataset with optimized settings
     * String selectSql = "SELECT * FROM transactions WHERE year = 2023";
     * String insertSql = "INSERT INTO transactions_archive VALUES (?, ?, ?, ?, ?)";
     *
     * long rowsCopied = DataTransferUtil.copy(sourceDS, selectSql, 50000, targetDS, insertSql, 5000);
     * System.out.println("Archived " + rowsCopied + " transactions");
     * }</pre>
     *
     * @param sourceDataSource the data source from which to copy data
     * @param selectSql the SQL query to select data from the source data source
     * @param fetchSize the JDBC fetch-size hint; must be {@code >= 0} and should be larger than {@code batchSize}
     * @param targetDataSource the data source to which to copy data
     * @param insertSql the SQL query to insert data into the target data source
     * @param batchSize the number of rows to copy in each batch (must be greater than 0)
     * @return the number of rows copied
     * @throws IllegalArgumentException if {@code fetchSize < 0} or {@code batchSize <= 0}
     * @throws SQLException if a database access error occurs
     */
    @Deprecated
    public static long copy(final javax.sql.DataSource sourceDataSource, final String selectSql, final int fetchSize,
            final javax.sql.DataSource targetDataSource, final String insertSql, final int batchSize) throws SQLException {
        N.checkArgPositive(batchSize, cs.batchSize);

        return copy(sourceDataSource, selectSql, fetchSize, targetDataSource, insertSql, batchSize, 0, supplierOfStmtSetterByRS.get());
    }

    /**
     * Copies data from a source data source to a target data source using custom SQL queries
     * and a custom statement setter for parameter mapping.
     *
     * <p>This method provides maximum flexibility by allowing custom parameter setting logic
     * through the statement setter BiConsumer.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Copy with data transformation
     * Throwables.BiConsumer<PreparedQuery, ResultSet, SQLException> setter = (pq, rs) -> {
     *     pq.setLong(1, rs.getLong("id"));
     *     pq.setString(2, rs.getString("name").toUpperCase());
     *     pq.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
     * };
     *
     * long rowsCopied = DataTransferUtil.copy(sourceDS, selectSql, targetDS, insertSql, setter);
     * }</pre>
     *
     * @param sourceDataSource the data source from which to copy data
     * @param selectSql the SQL query to select data from the source data source
     * @param targetDataSource the data source to which to copy data
     * @param insertSql the SQL query to insert data into the target data source
     * @param parameterSetter a bi-consumer to set parameters on the prepared statement from the result set;
     *                   if {@code null}, a default setter copies all columns by index
     * @return the number of rows copied
     * @throws SQLException if a database access error occurs
     */
    @Deprecated
    public static long copy(final javax.sql.DataSource sourceDataSource, final String selectSql, final javax.sql.DataSource targetDataSource,
            final String insertSql, final Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> parameterSetter) throws SQLException {
        return copy(sourceDataSource, selectSql, JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET, targetDataSource, insertSql, JdbcUtil.DEFAULT_BATCH_SIZE, 0,
                parameterSetter);
    }

    /**
     * Copies data from a source data source to a target data source with full control over all aspects
     * of the copy operation including SQL queries, performance settings, and parameter mapping.
     *
     * <p>This is the most comprehensive copy method, offering:</p>
     * <ul>
     *   <li>Custom SELECT and INSERT SQL statements</li>
     *   <li>Configurable fetch size for reading source data</li>
     *   <li>Configurable batch size for inserting target data</li>
     *   <li>Batch interval for throttling inserts</li>
     *   <li>Custom parameter setter for data transformation</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Complex copy with throttling and transformation
     * Throwables.BiConsumer<PreparedQuery, ResultSet, SQLException> setter = (pq, rs) -> {
     *     // Custom transformation logic
     *     pq.setLong(1, rs.getLong("id"));
     *     pq.setString(2, processName(rs.getString("name")));
     *     pq.setDate(3, rs.getDate("created"));
     * };
     *
     * long rowsCopied = DataTransferUtil.copy(
     *     sourceDS, "SELECT * FROM large_table", 100000,
     *     targetDS, "INSERT INTO processed_table VALUES (?, ?, ?)",
     *     5000, 1000, setter
     * );
     * }</pre>
     *
     * @param sourceDataSource the data source from which to copy data
     * @param selectSql the SQL query to select data from the source data source
     * @param fetchSize the JDBC fetch-size hint; must be {@code >= 0} and should be larger than {@code batchSize}
     * @param targetDataSource the data source to which to copy data
     * @param insertSql the SQL query to insert data into the target data source
     * @param batchSize the number of rows to copy in each batch (must be greater than 0)
     * @param batchIntervalInMillis the interval in milliseconds between each batch (0 for no delay; must be {@code >= 0})
     * @param parameterSetter a bi-consumer to set parameters on the prepared statement;
     *                   if {@code null}, a default setter copies all columns by index
     * @return the number of rows copied
     * @throws IllegalArgumentException if {@code fetchSize < 0}, {@code batchSize <= 0}, or {@code batchIntervalInMillis < 0}
     * @throws SQLException if a database access error occurs
     */
    @Deprecated
    public static long copy(final javax.sql.DataSource sourceDataSource, final String selectSql, final int fetchSize,
            final javax.sql.DataSource targetDataSource, final String insertSql, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> parameterSetter) throws SQLException {
        Connection sourceConn = null;
        Connection targetConn = null;

        try {
            sourceConn = JdbcUtil.getConnection(sourceDataSource);
            targetConn = JdbcUtil.getConnection(targetDataSource);

            return copy(sourceConn, selectSql, fetchSize, targetConn, insertSql, batchSize, batchIntervalInMillis, parameterSetter);
        } finally {
            // Release both connections even if one release throws (avoid leaking the second).
            try {
                if (sourceConn != null) {
                    JdbcUtil.releaseConnection(sourceConn, sourceDataSource);
                }
            } finally {
                if (targetConn != null) {
                    JdbcUtil.releaseConnection(targetConn, targetDataSource);
                }
            }
        }
    }

    /**
     * Copies all data from a table with the same name between two database connections.
     * This is a convenience method for copying entire tables within the same database
     * or between different databases using existing connections.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection sourceConn = sourceDS.getConnection();
     *      Connection targetConn = targetDS.getConnection()) {
     *
     *     long rowsCopied = DataTransferUtil.copy(sourceConn, targetConn, "products");
     *     System.out.println("Copied " + rowsCopied + " products");
     * }
     * }</pre>
     *
     * <p><b>&#9888; Warning:</b> {@code sourceConn} and {@code targetConn} should be distinct connections.
     * On drivers that stream large results row-by-row (MySQL/MariaDB, where the source statement is
     * configured with a streaming fetch size), issuing the batch inserts on the same connection that
     * holds the open streaming result set fails with a driver error.</p>
     *
     * @param sourceConn the connection to the source database
     * @param targetConn the connection to the target database
     * @param tableName the name of the table to copy data from and to
     * @return the number of rows copied
     * @throws SQLException if a database access error occurs
     */
    public static long copy(final Connection sourceConn, final Connection targetConn, final String tableName) throws SQLException {
        return copy(sourceConn, targetConn, tableName, tableName);
    }

    /**
     * Copies all data from a source table to a target table using the specified connections.
     * The source and target tables can have different names.
     *
     * <p>This method automatically generates the appropriate SELECT and INSERT statements
     * based on the source table's schema.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection sourceConn = sourceDS.getConnection();
     *      Connection targetConn = targetDS.getConnection()) {
     *
     *     long rowsCopied = DataTransferUtil.copy(sourceConn, targetConn, "orders", "orders_archive");
     *     System.out.println("Archived " + rowsCopied + " orders");
     * }
     * }</pre>
     *
     * @param sourceConn the connection to the source database
     * @param targetConn the connection to the target database
     * @param sourceTableName the name of the source table to copy data from
     * @param targetTableName the name of the target table to copy data to
     * @return the number of rows copied
     * @throws SQLException if a database access error occurs
     */
    public static long copy(final Connection sourceConn, final Connection targetConn, final String sourceTableName, final String targetTableName)
            throws SQLException {
        return copy(sourceConn, targetConn, sourceTableName, targetTableName, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Copies all data from a source table to a target table using the specified connections
     * with a custom batch size for performance tuning.
     *
     * <p>The batch size determines how many rows are accumulated before executing a batch insert.
     * Larger batch sizes can improve performance but require more memory.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection sourceConn = sourceDS.getConnection();
     *      Connection targetConn = targetDS.getConnection()) {
     *
     *     // Use larger batch size for better performance with large tables
     *     long rowsCopied = DataTransferUtil.copy(sourceConn, targetConn,
     *                                       "large_table", "large_table_copy", 10000);
     *     System.out.println("Copied " + rowsCopied + " rows");
     * }
     * }</pre>
     *
     * @param sourceConn the connection to the source database
     * @param targetConn the connection to the target database
     * @param sourceTableName the name of the source table to copy data from
     * @param targetTableName the name of the target table to copy data to
     * @param batchSize the number of rows to copy in each batch (must be greater than 0)
     * @return the number of rows copied
     * @throws IllegalArgumentException if {@code batchSize <= 0}
     * @throws SQLException if a database access error occurs
     */
    @Deprecated
    public static long copy(final Connection sourceConn, final Connection targetConn, final String sourceTableName, final String targetTableName,
            final int batchSize) throws SQLException {
        N.checkArgPositive(batchSize, cs.batchSize);

        // Generate the SELECT from source first, then derive the column ordering from the
        // source's result-set metadata and use it to drive the INSERT against the target.
        // Otherwise, when source and target have the same column set but different metadata
        // orders, positional setObject(i, ...) silently swaps values between columns.
        final String selectSql = JdbcCodeGenerationUtil.generateSelectSql(sourceConn, sourceTableName);
        final String insertSql = generateInsertSqlFromSelectColumns(sourceConn, selectSql, targetConn, targetTableName);

        return copy(sourceConn, selectSql, N.max(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET, batchSize), targetConn, insertSql, batchSize);
    }

    /**
     * Generates an INSERT SQL for {@code targetTableName} on {@code targetConn} using the column
     * ordering taken from executing {@code selectSql} on {@code sourceConn}. This guarantees the
     * INSERT column order matches the SELECT, so positional parameter binding stays aligned even
     * when the two databases store columns in different orders.
     */
    private static String generateInsertSqlFromSelectColumns(final Connection sourceConn, final String selectSql, final Connection targetConn,
            final String targetTableName) throws SQLException {
        // Zero-row probe: only column labels are needed. Executing the raw (unbounded) SELECT here
        // would buffer the whole source table client-side on drivers like MySQL/PostgreSQL before
        // the actual streaming copy even starts. Both callers pass the codegen-generated
        // "SELECT c1, c2, ... FROM table" form (no WHERE clause), so appending a false predicate is safe.
        try (PreparedStatement stmt = JdbcUtil.prepareStatement(sourceConn, selectSql + " WHERE 1 > 2");
             ResultSet rs = stmt.executeQuery()) {
            final java.util.List<String> sourceColumns = JdbcUtil.getColumnLabels(rs);
            return generateInsertSql(targetConn, targetTableName, sourceColumns);
        }
    }

    /**
     * Copies specified columns from a source table to a target table using the provided connections.
     * Only the columns specified in the collection will be copied.
     *
     * <p>This method is useful when copying a subset of columns or when the target table
     * has a different structure than the source table.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection sourceConn = sourceDS.getConnection();
     *      Connection targetConn = targetDS.getConnection()) {
     *
     *     Set<String> columns = Set.of("id", "name", "price", "category");
     *     long rowsCopied = DataTransferUtil.copy(sourceConn, targetConn,
     *                                       "products", "product_catalog", columns);
     *     System.out.println("Copied " + rowsCopied + " products to catalog");
     * }
     * }</pre>
     *
     * @param sourceConn the connection to the source database
     * @param targetConn the connection to the target database
     * @param sourceTableName the name of the source table to copy data from
     * @param targetTableName the name of the target table to copy data to
     * @param columnNames the collection of column names to be copied; {@code null} or empty copies all columns
     * @return the number of rows copied
     * @throws SQLException if a database access error occurs or any specified column doesn't exist
     */
    @Deprecated
    public static long copy(final Connection sourceConn, final Connection targetConn, final String sourceTableName, final String targetTableName,
            final Collection<String> columnNames) throws SQLException {
        return copy(sourceConn, targetConn, sourceTableName, targetTableName, columnNames, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Copies specified columns from a source table to a target table using the provided connections
     * with a custom batch size for performance optimization.
     *
     * <p>This method combines selective column copying with batch processing control,
     * allowing fine-tuned performance for partial table copies.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection sourceConn = sourceDS.getConnection();
     *      Connection targetConn = targetDS.getConnection()) {
     *
     *     List<String> essentialColumns = Arrays.asList("customer_id", "name", "email");
     *     long rowsCopied = DataTransferUtil.copy(sourceConn, targetConn,
     *                                       "customers", "customer_summary",
     *                                       essentialColumns, 5000);
     *     System.out.println("Created summary with " + rowsCopied + " customers");
     * }
     * }</pre>
     *
     * @param sourceConn the connection to the source database
     * @param targetConn the connection to the target database
     * @param sourceTableName the name of the source table to copy data from
     * @param targetTableName the name of the target table to copy data to
     * @param columnNames the collection of column names to be copied; {@code null} or empty copies all columns
     * @param batchSize the number of rows to be copied in each batch (must be greater than 0)
     * @return the number of rows copied
     * @throws IllegalArgumentException if {@code batchSize <= 0}
     * @throws SQLException if a database access error occurs
     */
    @Deprecated
    public static long copy(final Connection sourceConn, final Connection targetConn, final String sourceTableName, final String targetTableName,
            final Collection<String> columnNames, final int batchSize) throws SQLException {
        N.checkArgPositive(batchSize, cs.batchSize);

        final String selectSql = generateSelectSql(sourceConn, sourceTableName, columnNames);
        final String insertSql = generateInsertSql(targetConn, targetTableName, columnNames);

        return copy(sourceConn, selectSql, N.max(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET, batchSize), targetConn, insertSql, batchSize);
    }

    private static String generateSelectSql(final Connection conn, final String tableName, final Collection<String> columnNames) {
        if (N.isEmpty(columnNames)) {
            return JdbcCodeGenerationUtil.generateSelectSql(conn, tableName);
        }

        final ProductInfo dbProductInfo = JdbcUtil.getDBProductInfo(conn);
        final StringBuilder sb = new StringBuilder();

        sb.append(SK.SELECT).append(SK._SPACE);

        final Iterator<String> iter = columnNames.iterator();
        final int lastIdx = columnNames.size() - 1;
        int cnt = 0;

        while (iter.hasNext() && cnt++ < lastIdx) {
            sb.append(checkColumnName(iter.next(), dbProductInfo)).append(SK.COMMA_SPACE);
        }

        sb.append(checkColumnName(iter.next(), dbProductInfo))
                .append(SK._SPACE)
                .append(SK.FROM)
                .append(SK._SPACE)
                .append(checkTableName(tableName, dbProductInfo));

        return sb.toString();
    }

    private static String generateInsertSql(final Connection conn, final String tableName, final Collection<String> columnNames) {
        if (N.isEmpty(columnNames)) {
            return JdbcCodeGenerationUtil.generateInsertSql(conn, tableName);
        }

        final ProductInfo dbProductInfo = JdbcUtil.getDBProductInfo(conn);
        final StringBuilder sb = new StringBuilder();

        sb.append(SK.INSERT).append(SK._SPACE).append(SK.INTO).append(SK._SPACE).append(checkTableName(tableName, dbProductInfo)).append(SK._PARENTHESIS_L);

        final Iterator<String> iter = columnNames.iterator();
        final int lastIdx = columnNames.size() - 1;
        int cnt = 0;

        while (iter.hasNext() && cnt++ < lastIdx) {
            sb.append(checkColumnName(iter.next(), dbProductInfo)).append(SK.COMMA_SPACE);
        }

        sb.append(checkColumnName(iter.next(), dbProductInfo))
                .append(SK._PARENTHESIS_R)
                .append(SK._SPACE)
                .append(SK.VALUES)
                .append(SK._SPACE)
                .append(Strings.repeat("?", columnNames.size(), ", ", "(", ")"));

        return sb.toString();
    }

    private static String checkTableName(final String tableName, final ProductInfo dbProductInfo) {
        final String quote = getTableColumnNameQuoteChar(dbProductInfo);

        final String[] parts = JdbcUtil.splitQualifiedSqlIdentifier(tableName, "tableName");
        final boolean[] explicitlyDelimitedParts = explicitlyDelimitedIdentifierParts(tableName, parts.length);

        if (parts.length == 1) {
            return explicitlyDelimitedParts[0] || !isSimpleSqlIdentifier(parts[0]) ? quoteIdentifier(parts[0], quote) : parts[0];
        }

        final StringBuilder sb = new StringBuilder(tableName.length() + parts.length * 2);

        for (int i = 0, len = parts.length; i < len; i++) {
            if (i > 0) {
                sb.append('.');
            }

            // Preserve an explicitly delimited part even when its decoded text is a simple
            // identifier. Dropping the delimiters from (for example) "MixedCase" changes its
            // identity on case-folding databases. Plain simple parts remain unquoted so ordinary
            // names still receive the database's normal case folding.
            sb.append(explicitlyDelimitedParts[i] || !isSimpleSqlIdentifier(parts[i]) ? quoteIdentifier(parts[i], quote) : parts[i]);
        }

        return sb.toString();
    }

    private static String checkColumnName(final String columnName, final ProductInfo dbProductInfo) {
        N.checkArgNotBlank(columnName, cs.columnName);

        final String quote = getTableColumnNameQuoteChar(dbProductInfo);

        if (startsWithIdentifierDelimiter(columnName)) {
            final String[] parts = JdbcUtil.splitQualifiedSqlIdentifier(columnName, "columnName");

            if (parts.length != 1) {
                throw new IllegalArgumentException("'columnName' must be a single identifier: " + columnName);
            }

            // Decode the caller's delimiter and re-quote with the active database dialect. This
            // both preserves case-sensitive simple names and avoids double-quoting input such as
            // "MixedCase" or [order].
            return quoteIdentifier(parts[0], quote);
        }

        return isSimpleSqlIdentifier(columnName) ? columnName : quoteIdentifier(columnName, quote);
    }

    private static boolean startsWithIdentifierDelimiter(final String identifier) {
        final String trimmed = identifier.trim();

        return !trimmed.isEmpty() && (trimmed.charAt(0) == '"' || trimmed.charAt(0) == '`' || trimmed.charAt(0) == '[');
    }

    /**
     * Returns which parts of a validated qualified identifier were explicitly delimited in the
     * caller's text. {@link JdbcUtil#splitQualifiedSqlIdentifier(String, String)} deliberately
     * returns decoded names only, so this small companion scan retains the case-sensitivity signal
     * needed when the names are rendered for another database dialect.
     */
    private static boolean[] explicitlyDelimitedIdentifierParts(final String qualifiedName, final int partCount) {
        final boolean[] result = new boolean[partCount];
        final String trimmed = qualifiedName.trim();
        int partIndex = 0;
        boolean atPartStart = true;
        char closingQuote = 0;

        for (int i = 0, len = trimmed.length(); i < len; i++) {
            final char ch = trimmed.charAt(i);

            if (closingQuote != 0) {
                if (ch == closingQuote) {
                    if (i + 1 < len && trimmed.charAt(i + 1) == closingQuote) {
                        i++;
                    } else {
                        closingQuote = 0;
                    }
                }

                continue;
            }

            if (atPartStart) {
                if (Character.isWhitespace(ch)) {
                    continue;
                }

                if (ch == '"' || ch == '`' || ch == '[') {
                    result[partIndex] = true;
                    closingQuote = ch == '[' ? ']' : ch;
                }

                atPartStart = false;
            } else if (ch == '.') {
                partIndex++;
                atPartStart = true;
            }
        }

        return result;
    }

    private static boolean isSimpleSqlIdentifier(final String identifier) {
        if (Strings.isEmpty(identifier)) {
            return false;
        }

        final char first = identifier.charAt(0);

        if (!(Strings.isAsciiAlpha(first) || first == '_')) {
            return false;
        }

        return CharStream.of(identifier).skip(1).allMatch(ch -> Strings.isAsciiAlpha(ch) || Strings.isAsciiNumeric(ch) || ch == '_');
    }

    private static String quoteIdentifier(final String identifier, final String quote) {
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    private static String getTableColumnNameQuoteChar(final ProductInfo dbProductInfo) {
        return dbProductInfo != null && Strings.containsAnyIgnoreCase(dbProductInfo.name(), "MySQL", "MariaDB") ? "`" : "\"";
    }

    /**
     * Copies data between databases using custom SQL queries and existing connections.
     * This method provides direct control over the SELECT and INSERT statements.
     *
     * <p>Uses default fetch and batch sizes optimized for large result sets.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection sourceConn = sourceDS.getConnection();
     *      Connection targetConn = targetDS.getConnection()) {
     *
     *     String selectSql = "SELECT * FROM orders WHERE status = 'COMPLETED'";
     *     String insertSql = "INSERT INTO completed_orders VALUES (?, ?, ?, ?)";
     *
     *     long rowsCopied = DataTransferUtil.copy(sourceConn, selectSql, targetConn, insertSql);
     *     System.out.println("Archived " + rowsCopied + " completed orders");
     * }
     * }</pre>
     *
     * <p><b>&#9888; Warning:</b> {@code sourceConn} and {@code targetConn} should be distinct connections.
     * On drivers that stream large results row-by-row (MySQL/MariaDB, where the source statement is
     * configured with a streaming fetch size), issuing the batch inserts on the same connection that
     * holds the open streaming result set fails with a driver error.</p>
     *
     * @param sourceConn the connection to the source database
     * @param selectSql the SQL query to select data from the source database
     * @param targetConn the connection to the target database
     * @param insertSql the SQL query to insert data into the target database
     * @return the number of rows copied
     * @throws SQLException if a database access error occurs
     */
    public static long copy(final Connection sourceConn, final String selectSql, final Connection targetConn, final String insertSql) throws SQLException {
        return copy(sourceConn, selectSql, JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET, targetConn, insertSql, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Copies data between databases using custom SQL queries with specified fetch and batch sizes.
     * This method provides control over memory usage and performance characteristics.
     *
     * <p>The fetch size controls how many rows are retrieved from the source at once,
     * while the batch size controls how many rows are inserted at once. For optimal performance,
     * fetch size should be larger than batch size.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection sourceConn = sourceDS.getConnection();
     *      Connection targetConn = targetDS.getConnection()) {
     *
     *     String selectSql = "SELECT * FROM huge_table";
     *     String insertSql = "INSERT INTO huge_table_copy VALUES (?, ?, ?, ?, ?)";
     *
     *     // Large fetch size for reading, moderate batch size for writing
     *     long rowsCopied = DataTransferUtil.copy(sourceConn, selectSql, 100000,
     *                                       targetConn, insertSql, 5000);
     *     System.out.println("Copied " + rowsCopied + " rows efficiently");
     * }
     * }</pre>
     *
     * @param sourceConn the connection to the source database
     * @param selectSql the SQL query to select data from the source database
     * @param fetchSize the JDBC fetch-size hint for the source statement; must be {@code >= 0}
     * @param targetConn the connection to the target database
     * @param insertSql the SQL query to insert data into the target database
     * @param batchSize the number of rows to be copied in each batch (must be greater than 0)
     * @return the number of rows copied
     * @throws IllegalArgumentException if {@code fetchSize < 0} or {@code batchSize <= 0}
     * @throws SQLException if a database access error occurs
     */
    @Deprecated
    public static long copy(final Connection sourceConn, final String selectSql, final int fetchSize, final Connection targetConn, final String insertSql,
            final int batchSize) throws SQLException {
        N.checkArgPositive(batchSize, cs.batchSize);

        return copy(sourceConn, selectSql, fetchSize, targetConn, insertSql, batchSize, 0, supplierOfStmtSetterByRS.get());
    }

    /**
     * Copies data between databases using custom SQL queries and a custom statement setter.
     * This method allows for data transformation during the copy process.
     *
     * <p>The statement setter provides complete control over how data is mapped from
     * the source ResultSet to the target PreparedStatement parameters.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Copy with data transformation and type conversion
     * Throwables.BiConsumer<PreparedQuery, ResultSet, SQLException> setter = (pq, rs) -> {
     *     pq.setLong(1, rs.getLong("id"));
     *     pq.setString(2, rs.getString("first_name") + " " + rs.getString("last_name"));
     *     pq.setDate(3, new java.sql.Date(rs.getTimestamp("created_at").getTime()));
     *     pq.setBoolean(4, "ACTIVE".equals(rs.getString("status")));
     * };
     *
     * long rowsCopied = DataTransferUtil.copy(sourceConn, selectSql, targetConn, insertSql, setter);
     * }</pre>
     *
     * @param sourceConn the connection to the source database
     * @param selectSql the SQL query to select data from the source database
     * @param targetConn the connection to the target database
     * @param insertSql the SQL query to insert data into the target database
     * @param parameterSetter the custom statement setter to set the parameters of the prepared statement;
     *                   if {@code null}, a default setter copies all columns by index
     * @return the number of rows copied
     * @throws SQLException if a database access error occurs
     */
    @Deprecated
    public static long copy(final Connection sourceConn, final String selectSql, final Connection targetConn, final String insertSql,
            final Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> parameterSetter) throws SQLException {
        return copy(sourceConn, selectSql, JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET, targetConn, insertSql, JdbcUtil.DEFAULT_BATCH_SIZE, 0,
                parameterSetter);
    }

    /**
     * Copies data between databases with full control over all aspects of the operation.
     * This is the most comprehensive copy method for connection-based operations.
     *
     * <p>Features include:</p>
     * <ul>
     *   <li>Custom SQL queries for maximum flexibility</li>
     *   <li>Configurable fetch size for memory management</li>
     *   <li>Configurable batch size for insert performance</li>
     *   <li>Batch interval for rate limiting</li>
     *   <li>Custom statement setter for data transformation</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Complex copy with rate limiting and transformation
     * Throwables.BiConsumer<PreparedQuery, ResultSet, SQLException> setter = (pq, rs) -> {
     *     pq.setLong(1, rs.getLong("id"));
     *     pq.setString(2, sanitize(rs.getString("data")));
     *     pq.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
     * };
     *
     * long rowsCopied = DataTransferUtil.copy(
     *     sourceConn,
     *     "SELECT id, data FROM sensitive_table",
     *     50000,  // Large fetch size
     *     targetConn,
     *     "INSERT INTO sanitized_table VALUES (?, ?, ?)",
     *     1000,   // Smaller batch size
     *     100,    // 100ms delay between batches
     *     setter
     * );
     * }</pre>
     *
     * @param sourceConn the connection to the source database
     * @param selectSql the SQL query to select data from the source database
     * @param fetchSize the JDBC fetch-size hint for the source statement; must be {@code >= 0}
     * @param targetConn the connection to the target database
     * @param insertSql the SQL query to insert data into the target database
     * @param batchSize the number of rows to be copied in each batch (must be greater than 0)
     * @param batchIntervalInMillis the interval in milliseconds between each batch (0 for no delay; must be {@code >= 0})
     * @param parameterSetter the custom statement setter to set the parameters of the prepared statement;
     *                   if {@code null}, a default setter copies all columns by index
     * @return the number of rows copied
     * @throws IllegalArgumentException if {@code fetchSize < 0}, {@code batchSize <= 0}, or {@code batchIntervalInMillis < 0}
     * @throws SQLException if a database access error occurs
     */
    @Deprecated
    public static long copy(final Connection sourceConn, final String selectSql, final int fetchSize, final Connection targetConn, final String insertSql,
            final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> parameterSetter) throws SQLException {
        PreparedStatement selectStmt = null;
        PreparedStatement insertStmt = null;

        try {
            selectStmt = JdbcUtil.prepareStatement(sourceConn, selectSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            setFetchForLargeResult(sourceConn, selectStmt, fetchSize);

            insertStmt = JdbcUtil.prepareStatement(targetConn, insertSql);

            return copy(selectStmt, insertStmt, batchSize, batchIntervalInMillis, parameterSetter);
        } finally {
            JdbcUtil.closeQuietly(selectStmt);
            JdbcUtil.closeQuietly(insertStmt);
        }
    }

    /**
     * Copies data from a source PreparedStatement to a target PreparedStatement with full control
     * over batch processing and parameter mapping.
     *
     * <p>This low-level method provides direct control over prepared statements and is useful
     * when you need to reuse statements or have complex statement preparation requirements.</p>
     *
     * <p>The method executes the select statement, iterates through the results, and uses the
     * statement setter to map data to the insert statement parameters. Data is inserted in
     * batches with optional delays between batches.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * PreparedStatement selectStmt = sourceConn.prepareStatement(
     *     "SELECT * FROM source_table WHERE created > ?");
     * selectStmt.setDate(1, cutoffDate);
     *
     * PreparedStatement insertStmt = targetConn.prepareStatement(
     *     "INSERT INTO target_table VALUES (?, ?, ?)");
     *
     * Throwables.BiConsumer<PreparedQuery, ResultSet, SQLException> setter = (pq, rs) -> {
     *     pq.setLong(1, rs.getLong(1));
     *     pq.setString(2, rs.getString(2));
     *     pq.setTimestamp(3, rs.getTimestamp(3));
     * };
     *
     * long rowsCopied = DataTransferUtil.copy(selectStmt, insertStmt, 1000, 0, setter);
     * System.out.println("Copied " + rowsCopied + " recent records");
     * }</pre>
     *
     * @param selectStmt the PreparedStatement used to select data from the source
     * @param insertStmt the PreparedStatement used to insert data into the target
     * @param batchSize the number of rows to process in each batch (must be greater than 0)
     * @param batchIntervalInMillis the interval in milliseconds between each batch (0 for no delay; must be {@code >= 0})
     * @param parameterSetter a BiConsumer that sets the parameters for the {@link PreparedQuery} from the ResultSet;
     *                   if {@code null}, a default setter copies all columns by index
     * @return the number of rows copied
     * @throws IllegalArgumentException if {@code batchSize <= 0} or {@code batchIntervalInMillis < 0}
     * @throws SQLException if a database access error occurs
     */
    @Deprecated
    public static long copy(final PreparedStatement selectStmt, final PreparedStatement insertStmt, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> parameterSetter) throws SQLException {
        N.checkArgNotNull(selectStmt, "selectStmt");
        N.checkArgNotNull(insertStmt, "insertStmt");
        N.checkArgument(batchSize > 0 && batchIntervalInMillis >= 0, "'batchSize'=%s must be greater than 0 and 'batchIntervalInMillis'=%s can't be negative",
                batchSize, batchIntervalInMillis);

        final Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> stmtSetterForInsert = N.defaultIfNull(parameterSetter,
                supplierOfStmtSetterByRS.get());
        final PreparedQuery preparedQueryForInsert = new PreparedQuery(insertStmt);

        ResultSet rs = null;

        logger.debug("Copying data(batchSize={}, batchIntervalInMillis={}, customStmtSetter={})", batchSize, batchIntervalInMillis, parameterSetter != null);

        try {
            rs = JdbcUtil.executeQuery(selectStmt);

            long cnt = 0;
            boolean hasExecutedBatch = false;

            while (rs.next()) {
                cnt++;

                stmtSetterForInsert.accept(preparedQueryForInsert, rs);
                insertStmt.addBatch();

                if (cnt % batchSize == 0) {
                    pauseBeforeSubsequentBatch(hasExecutedBatch, batchIntervalInMillis);
                    JdbcUtil.executeBatch(insertStmt);
                    hasExecutedBatch = true;
                }
            }

            if (cnt % batchSize > 0) {
                pauseBeforeSubsequentBatch(hasExecutedBatch, batchIntervalInMillis);
                JdbcUtil.executeBatch(insertStmt);
            }

            // insertStmt.clearBatch();   // clearBatch() is called in JdbcUtil.executeBatch(insertStmt)

            logger.info("Copied rows(copied={})", cnt);

            return cnt;
        } finally {
            JdbcUtil.closeQuietly(rs);
        }
    }

    private static void pauseBeforeSubsequentBatch(final boolean hasExecutedBatch, final long batchIntervalInMillis) {
        if (hasExecutedBatch && batchIntervalInMillis > 0) {
            N.sleepUninterruptibly(batchIntervalInMillis);
        }
    }

    private static long toBatchIntervalMillis(final Duration delay) {
        N.checkArgNotNull(delay, "delay");
        N.checkArgument(!delay.isNegative(), "delay must not be negative: %s", delay);

        try {
            return delay.toMillis();
        } catch (final ArithmeticException e) {
            throw new IllegalArgumentException("delay is too large to represent in milliseconds: " + delay, e);
        }
    }

    private static void setFetchForLargeResult(final Connection conn, final PreparedStatement stmt) throws SQLException {
        setFetchForLargeResult(conn, stmt, JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET);
    }

    private static void setFetchForLargeResult(final Connection conn, final PreparedStatement stmt, final int fetchSize) throws SQLException {
        N.checkArgNotNegative(fetchSize, "fetchSize");
        stmt.setFetchDirection(ResultSet.FETCH_FORWARD);

        // MySQL and MariaDB share a protocol-level requirement for Integer.MIN_VALUE to enable
        // row-by-row streaming; without it the driver buffers the entire result set in client
        // memory. MariaDB drivers may report the product name as either "MariaDB" or "MySQL" (with
        // "MariaDB" only in the version string), so match both name and version.
        final ProductInfo productInfo = JdbcUtil.getDBProductInfo(conn);

        if (Strings.containsAnyIgnoreCase(productInfo.name(), "MySQL", "MariaDB") || Strings.containsIgnoreCase(productInfo.version(), "MariaDB")) {
            stmt.setFetchSize(Integer.MIN_VALUE);
        } else {
            stmt.setFetchSize(fetchSize);
        }
    }

    /**
     * Creates a parameter setter for a {@link PreparedQuery} using the provided {@link ColumnGetter}.
     *
     * <p>The returned {@link Throwables.BiConsumer} is stateful and caches
     * the ResultSet column count on first invocation by calling {@link JdbcUtil#getColumnCount(ResultSet)}.
     * For each column index {@code 1..columnCount}, it calls
     * {@link PreparedQuery#setObject(int, Object)} with the value from
     * {@code columnGetter.get(resultSet, index)}.</p>
     *
     * <p>Because the column count is cached, the setter must only be reused for ResultSet instances
     * with the same number of columns, and should not be shared across threads.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ColumnGetter<Object> getter = (rs, columnIndex) -> rs.getObject(columnIndex);
     * Throwables.BiConsumer<PreparedQuery, ResultSet, SQLException> setter =
     *     DataTransferUtil.resultSetParameterSetter(getter);
     *
     * // Use in copy operation
     * long copied = DataTransferUtil.copy(sourceConn, selectSql, targetConn, insertSql, setter);
     * }</pre>
     *
     * @param columnGetter the ColumnGetter to apply to each column index in every row
     * @return a stateful BiConsumer that maps ResultSet columns to PreparedQuery parameter positions
     * @throws IllegalArgumentException if {@code columnGetter} is {@code null}
     * @see #copy(Connection, String, Connection, String, Throwables.BiConsumer)
     */
    @Beta
    @SequentialOnly
    @Stateful
    public static Throwables.BiConsumer<PreparedQuery, ResultSet, SQLException> resultSetParameterSetter(final ColumnGetter<?> columnGetter) {
        N.checkArgNotNull(columnGetter, "columnGetter");

        return new Throwables.BiConsumer<>() {
            private int columnCount = -1;

            @Override
            public void accept(final PreparedQuery stmt, final ResultSet rs) throws SQLException {
                if (columnCount < 0) {
                    columnCount = JdbcUtil.getColumnCount(rs);
                }

                for (int i = 1; i <= columnCount; i++) {
                    stmt.setObject(i, columnGetter.get(rs, i));
                }
            }
        };
    }

    /**
     * Creates a fluent builder for importing the data of a {@link Dataset} into a database table.
     *
     * <p>The returned {@link DatasetImportBuilder} lets you configure the optional aspects of the import
     * (selected columns, row filter, batch size/interval, column-type mapping or a custom statement setter)
     * through chained calls, and then run the import with one of the terminal {@code to(...)} methods.
     * It is an ergonomic alternative to the many positional {@code importData(Dataset, ...)} overloads.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.rows(List.of("name", "age"), new Object[][] {{"John", 25}, {"Jane", 30}});
     * List<String> cols = Arrays.asList("name", "age");
     *
     * // Equivalent to importData(dataset, cols, filter, conn, insertSql, 1000, 0) run on a connection from dataSource
     * int rowsImported = DataTransferUtil.importFrom(dataset)
     *         .columns(cols)
     *         .filter(row -> ((Integer) row[1]) >= 18)
     *         .batchSize(1000)
     *         .to(dataSource, "INSERT INTO users (name, age) VALUES (?, ?)");
     * }</pre>
     *
     * @param dataset the Dataset whose data will be imported (must not be {@code null})
     * @return a {@link DatasetImportBuilder} for configuring and running the import
     * @throws IllegalArgumentException if {@code dataset} is {@code null}
     * @see DatasetImportBuilder
     * @see #importData(Dataset, javax.sql.DataSource, String)
     */
    @Beta
    public static DatasetImportBuilder importFrom(final Dataset dataset) {
        N.checkArgNotNull(dataset, cs.dataset);

        return new DatasetImportBuilder(dataset);
    }

    /**
     * A fluent builder that configures and runs the import of a {@link Dataset} into a database table.
     *
     * <p>Obtain an instance via {@link DataTransferUtil#importFrom(Dataset)}, chain any of the optional configuration
     * methods, then call one of the terminal {@code to(...)} methods to run the import. Each configuration
     * method returns {@code this}, so calls can be chained.</p>
     *
     * <p>The three value-mapping strategies &mdash; {@link #columns(Collection)},
     * {@link #columnTypes(Map)} and {@link #parameterSetter(Throwables.BiConsumer)} &mdash; are mutually
     * exclusive; configuring more than one causes the terminal {@code to(...)} call to throw
     * {@link IllegalArgumentException}. When none of them is configured, all columns of the dataset are
     * imported in order. The {@link #filter(Predicate)} is independent and may be combined with
     * any of them.</p>
     *
     * @see DataTransferUtil#importFrom(Dataset)
     */
    public static final class DatasetImportBuilder {
        private final Dataset dataset;
        private Collection<String> columnNames;
        private Predicate<? super Object[]> filter;
        private int batchSize = JdbcUtil.DEFAULT_BATCH_SIZE;
        private long batchIntervalInMillis = 0;
        @SuppressWarnings("rawtypes")
        private Map<String, ? extends Type> columnTypeMap;
        private Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> parameterSetter;

        DatasetImportBuilder(final Dataset dataset) {
            this.dataset = dataset;
        }

        /**
         * Restricts the import to the specified columns, in the given order. The order must match the
         * parameter placeholders of the insert SQL. Mutually exclusive with {@link #columnTypes(Map)}
         * and {@link #parameterSetter(Throwables.BiConsumer)}.
         *
         * @param columns the column names to import; {@code null} or empty imports all columns. The supplied collection is copied.
         * @return this builder
         */
        public DatasetImportBuilder columns(final Collection<String> columns) {
            columnNames = N.isEmpty(columns) ? null : new ArrayList<>(columns);

            return this;
        }

        /**
         * Imports only the rows for which the given predicate returns {@code true}. The predicate receives
         * the row as an {@code Object[]} of all dataset column values, in dataset column order.
         *
         * @param filter the row filter; {@code null} imports every row
         * @return this builder
         */
        public DatasetImportBuilder filter(final Predicate<? super Object[]> filter) {
            this.filter = filter;

            return this;
        }

        /**
         * Sets the number of rows inserted per batch.
         *
         * @param batchSize the batch size (must be greater than 0 when {@code to(...)} is called)
         * @return this builder
         */
        public DatasetImportBuilder batchSize(final int batchSize) {
            this.batchSize = batchSize;

            return this;
        }

        /**
         * Sets the pause between consecutive batch executions.
         *
         * @param delay the delay between completed batches; must not be {@code null}, negative, or too large to represent in milliseconds
         * @return this builder
         * @throws IllegalArgumentException if {@code delay} is {@code null}, negative, or too large to represent in milliseconds
         */
        public DatasetImportBuilder batchDelay(final Duration delay) {
            batchIntervalInMillis = toBatchIntervalMillis(delay);

            return this;
        }

        /**
         * Supplies a per-column {@link Type} map used to coerce values while setting statement parameters.
         * Mutually exclusive with {@link #columns(Collection)} and {@link #parameterSetter(Throwables.BiConsumer)}.
         *
         * @param columnTypes a map of column name to {@link Type}; keys must be columns of the dataset. The supplied map is copied.
         * @return this builder
         */
        @SuppressWarnings("rawtypes")
        public DatasetImportBuilder columnTypes(final Map<String, ? extends Type> columnTypes) {
            columnTypeMap = N.isEmpty(columnTypes) ? null : new LinkedHashMap<>(columnTypes);

            return this;
        }

        /**
         * Supplies a custom setter that maps each row to the statement parameters, giving full control over
         * how values are bound. Mutually exclusive with {@link #columns(Collection)} and
         * {@link #columnTypes(Map)}.
         *
         * @param parameterSetter a BiConsumer that sets the parameters of the {@link PreparedQuery} for each row
         * @return this builder
         */
        public DatasetImportBuilder parameterSetter(final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> parameterSetter) {
            this.parameterSetter = parameterSetter;

            return this;
        }

        /**
         * Runs the import against a connection obtained from the given DataSource. The connection is released
         * back to the DataSource when the import completes.
         *
         * @param targetDataSource the DataSource to obtain a database connection from
         * @param insertSql the SQL insert statement with placeholders
         * @return the number of rows successfully imported
         * @throws IllegalArgumentException if more than one value-mapping strategy is configured, or
         *         {@code batchSize <= 0}, or a configured column name
         *         is not a column of the dataset
         * @throws SQLException if a database access error occurs
         */
        public int to(final javax.sql.DataSource targetDataSource, final String insertSql) throws SQLException {
            final Connection conn = JdbcUtil.getConnection(targetDataSource);

            try {
                return to(conn, insertSql);
            } finally {
                JdbcUtil.releaseConnection(conn, targetDataSource);
            }
        }

        /**
         * Runs the import against the given Connection.
         *
         * @param conn the Connection to the database
         * @param insertSql the SQL insert statement with placeholders
         * @return the number of rows successfully imported
         * @throws IllegalArgumentException if more than one value-mapping strategy is configured, or
         *         {@code batchSize <= 0}, or a configured column name
         *         is not a column of the dataset
         * @throws SQLException if a database access error occurs
         */
        public int to(final Connection conn, final String insertSql) throws SQLException {
            try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSql)) {
                return to(stmt);
            }
        }

        /**
         * Runs the import against the given PreparedStatement. The statement is not closed by this method.
         *
         * @param stmt the PreparedStatement to be used for the import (will not be closed by this method)
         * @return the number of rows successfully imported
         * @throws IllegalArgumentException if more than one value-mapping strategy is configured, or
         *         {@code batchSize <= 0}, or a configured column name
         *         is not a column of the dataset
         * @throws SQLException if a database access error occurs
         */
        public int to(final PreparedStatement stmt) throws SQLException {
            int configuredStrategies = 0;

            if (N.notEmpty(columnNames)) {
                configuredStrategies++;
            }

            if (N.notEmpty(columnTypeMap)) {
                configuredStrategies++;
            }

            if (parameterSetter != null) {
                configuredStrategies++;
            }

            if (configuredStrategies > 1) {
                throw new IllegalArgumentException("Only one of 'columns', 'columnTypes' or 'parameterSetter' can be configured for a single import");
            }

            if (parameterSetter != null) {
                return importData(dataset, filter, stmt, batchSize, batchIntervalInMillis, parameterSetter);
            } else if (N.notEmpty(columnTypeMap)) {
                return importData(dataset, filter, stmt, batchSize, batchIntervalInMillis, columnTypeMap);
            } else {
                final Collection<String> selectedColumnNames = N.isEmpty(columnNames) ? dataset.columnNames() : columnNames;

                return importData(dataset, selectedColumnNames, filter, stmt, batchSize, batchIntervalInMillis);
            }
        }
    }

    /**
     * Creates a fluent builder for importing the elements of an {@link Iterator} into a database table, one row per element.
     *
     * <p>Configure how an element becomes a database row with {@link RowImportBuilder#parameterSetter(Throwables.BiConsumer)},
     * optionally skipping elements with {@link RowImportBuilder#filter(Predicate)}. The iterator is not closed by this
     * builder.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * long n = DataTransferUtil.importFrom(users.iterator())
     *         .parameterSetter((q, u) -> { q.setLong(1, u.getId()); q.setString(2, u.getName()); })
     *         .to(dataSource, "INSERT INTO users (id, name) VALUES (?, ?)");
     * }</pre>
     *
     * @param <T> the iterator element type
     * @param iter the iterator whose elements will be imported (must not be {@code null})
     * @return a {@link RowImportBuilder} over the iterator's elements
     * @throws IllegalArgumentException if {@code iter} is {@code null}
     * @see #importData(Iterator, PreparedStatement, int, long, Throwables.BiConsumer)
     */
    @Beta
    public static <T> RowImportBuilder<T> importFrom(final Iterator<? extends T> iter) {
        N.checkArgNotNull(iter, "iter");

        return new RowImportBuilder<>(iter, null, null);
    }

    /**
     * Creates a fluent builder for importing the rows of a CSV {@link File} into a database table.
     *
     * <p>The first line is treated as a header and skipped; every subsequent line is tokenized (using the current
     * {@link CsvUtil} parser) and exposed to the builder as a {@code String[]} of column values. Bind each row with
     * {@link RowImportBuilder#parameterSetter(Throwables.BiConsumer)}, optionally skipping rows with
     * {@link RowImportBuilder#filter(Predicate)}. The file is opened when a terminal
     * {@code to(...)} runs and closed before it returns.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * long n = DataTransferUtil.importCsvFrom(new File("users.csv"))
     *         .filter(row -> Integer.parseInt(row[1]) >= 18)
     *         .parameterSetter((q, row) -> { q.setString(1, row[0]); q.setInt(2, Integer.parseInt(row[1])); })
     *         .to(connection, "INSERT INTO users (name, age) VALUES (?, ?)");
     * }</pre>
     *
     * @param file the CSV file to import (must not be {@code null})
     * @return a {@link RowImportBuilder} over the CSV rows ({@code String[]} per row)
     * @throws IllegalArgumentException if {@code file} is {@code null}
     * @see #importCsvFrom(Reader)
     */
    @Beta
    public static RowImportBuilder<String[]> importCsvFrom(final File file) {
        N.checkArgNotNull(file, "file");

        return new RowImportBuilder<>(null, null, file);
    }

    /**
     * Creates a fluent builder for importing the rows of CSV data read from a {@link Reader} into a database table.
     *
     * <p>The first line is treated as a header and skipped; each subsequent line is tokenized into a {@code String[]}.
     * See {@link #importCsvFrom(File)} for configuration. The caller-supplied {@code Reader} is NOT closed by this builder.</p>
     *
     * @param reader the reader supplying CSV data (must not be {@code null}); not closed by this builder
     * @return a {@link RowImportBuilder} over the CSV rows ({@code String[]} per row)
     * @throws IllegalArgumentException if {@code reader} is {@code null}
     * @see #importCsvFrom(File)
     */
    @Beta
    public static RowImportBuilder<String[]> importCsvFrom(final Reader reader) {
        N.checkArgNotNull(reader, "reader");

        return new RowImportBuilder<>(null, reader, null);
    }

    /**
     * A fluent builder that imports rows from an {@link Iterator} or a CSV {@link File}/{@link Reader} into a database table.
     *
     * <p>Obtain an instance via {@link DataTransferUtil#importFrom(Iterator)} (one row per element) or
     * {@link DataTransferUtil#importCsvFrom(File)} / {@link DataTransferUtil#importCsvFrom(Reader)}
     * (CSV; the header line is skipped; element type {@code String[]}). Chain the optional configuration methods, then
     * call a terminal {@code to(...)} method to run the import.</p>
     *
     * <p>A value-binding strategy must be configured via {@link #parameterSetter(Throwables.BiConsumer)} (binds each element
     * directly); the terminal {@code to(...)} throws {@link IllegalArgumentException} if it is not set.
     * {@link #filter(Predicate)} is independent and optional.</p>
     *
     * @param <T> the per-row element type ({@code String[]} for CSV import, or the iterator element type)
     * @see DataTransferUtil#importFrom(Iterator)
     * @see DataTransferUtil#importCsvFrom(File)
     */
    public static final class RowImportBuilder<T> {
        private final Iterator<? extends T> iter;
        private final Reader reader;
        private final File file;
        private Predicate<? super T> filter;
        private int batchSize = JdbcUtil.DEFAULT_BATCH_SIZE;
        private long batchIntervalInMillis = 0;
        private Throwables.BiConsumer<? super PreparedQuery, ? super T, SQLException> parameterSetter;

        RowImportBuilder(final Iterator<? extends T> iter, final Reader reader, final File file) {
            this.iter = iter;
            this.reader = reader;
            this.file = file;
        }

        /**
         * Imports only the elements/rows for which the given predicate returns {@code true}.
         *
         * @param filter the row filter; {@code null} imports every row
         * @return this builder
         */
        public RowImportBuilder<T> filter(final Predicate<? super T> filter) {
            this.filter = filter;

            return this;
        }

        /**
         * Sets the number of rows inserted per batch.
         *
         * @param batchSize the batch size (must be greater than 0 when {@code to(...)} is called)
         * @return this builder
         */
        public RowImportBuilder<T> batchSize(final int batchSize) {
            this.batchSize = batchSize;

            return this;
        }

        /**
         * Sets the pause between consecutive batch executions.
         *
         * @param delay the delay between completed batches; must not be {@code null}, negative, or too large to represent in milliseconds
         * @return this builder
         * @throws IllegalArgumentException if {@code delay} is {@code null}, negative, or too large to represent in milliseconds
         */
        public RowImportBuilder<T> batchDelay(final Duration delay) {
            batchIntervalInMillis = toBatchIntervalMillis(delay);

            return this;
        }

        /**
         * Supplies a custom setter that binds each element to the insert statement parameters.
         *
         * @param parameterSetter binds the parameters of the {@link PreparedQuery} for each element
         * @return this builder
         */
        public RowImportBuilder<T> parameterSetter(final Throwables.BiConsumer<? super PreparedQuery, ? super T, SQLException> parameterSetter) {
            this.parameterSetter = parameterSetter;

            return this;
        }

        /**
         * Runs the import against a connection obtained from the given DataSource; the connection is released back to
         * the DataSource when the import completes.
         *
         * @param targetDataSource the DataSource to obtain a database connection from
         * @param insertSql the SQL insert statement with placeholders
         * @return the number of rows successfully imported
         * @throws IllegalArgumentException if {@code parameterSetter} is not configured or {@code batchSize <= 0}
         * @throws IllegalStateException if the builder does not have exactly one source (an iterator, reader or file)
         *         configured; normally guaranteed by the factory methods
         * @throws SQLException if a database access error occurs
         * @throws UncheckedIOException if an I/O error occurs reading the file/reader
         */
        public long to(final javax.sql.DataSource targetDataSource, final String insertSql) throws SQLException {
            final Connection conn = JdbcUtil.getConnection(targetDataSource);

            try {
                return to(conn, insertSql);
            } finally {
                JdbcUtil.releaseConnection(conn, targetDataSource);
            }
        }

        /**
         * Runs the import against the given Connection.
         *
         * @param conn the Connection to the database
         * @param insertSql the SQL insert statement with placeholders
         * @return the number of rows successfully imported
         * @throws IllegalArgumentException if {@code parameterSetter} is not configured or {@code batchSize <= 0}
         * @throws IllegalStateException if the builder does not have exactly one source (an iterator, reader or file)
         *         configured; normally guaranteed by the factory methods
         * @throws SQLException if a database access error occurs
         * @throws UncheckedIOException if an I/O error occurs reading the file/reader
         */
        public long to(final Connection conn, final String insertSql) throws SQLException {
            try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSql)) {
                return to(stmt);
            }
        }

        /**
         * Runs the import against the given PreparedStatement. A caller-supplied {@code Reader}/{@code Iterator} is
         * not closed; a {@code File} source is opened and closed by this method.
         *
         * @param stmt the PreparedStatement to be used for the import (will not be closed by this method)
         * @return the number of rows successfully imported
         * @throws IllegalArgumentException if {@code parameterSetter} is not configured or {@code batchSize <= 0}
         * @throws IllegalStateException if the builder does not have exactly one source (an iterator, reader or file)
         *         configured; normally guaranteed by the factory methods
         * @throws SQLException if a database access error occurs
         * @throws UncheckedIOException if an I/O error occurs reading the file/reader
         */
        public long to(final PreparedStatement stmt) throws SQLException {
            int configuredSources = 0;

            if (iter != null) {
                configuredSources++;
            }

            if (reader != null) {
                configuredSources++;
            }

            if (file != null) {
                configuredSources++;
            }

            if (configuredSources != 1) {
                throw new IllegalStateException("Exactly one of 'iter', 'reader' or 'file' must be configured for a single import");
            }

            final Throwables.BiConsumer<? super PreparedQuery, ? super T, SQLException> setter = resolveSetter();

            if (iter != null) {
                return importData(iter, filter, stmt, batchSize, batchIntervalInMillis, setter);
            } else if (file != null) {
                try (Reader r = IOUtil.newFileReader(file)) {
                    return importFromCsv(r, stmt, setter);
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else {
                return importFromCsv(reader, stmt, setter);
            }
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        private long importFromCsv(final Reader r, final PreparedStatement stmt,
                final Throwables.BiConsumer<? super PreparedQuery, ? super T, SQLException> setter) throws SQLException {
            // A Reader/File source is always CSV; T is String[] for the importCsvFrom(...) entry points.
            return importCsv(r, (Predicate) filter, stmt, batchSize, batchIntervalInMillis,
                    (Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException>) (Throwables.BiConsumer) setter);
        }

        private Throwables.BiConsumer<? super PreparedQuery, ? super T, SQLException> resolveSetter() {
            if (parameterSetter == null) {
                throw new IllegalArgumentException("'parameterSetter' must be configured before calling to(...)");
            }

            return parameterSetter;
        }
    }

    /**
     * Creates a fluent builder for exporting the rows of a SELECT query (run against the given
     * {@link javax.sql.DataSource}) to CSV. A connection is obtained when a terminal {@code to(...)} runs and released
     * before it returns.
     *
     * <p>It is an ergonomic alternative to the positional {@code exportCsv(DataSource, String, ...)} overloads and, via
     * {@link CsvExportBuilder#columns(Collection)}, supports column selection for BOTH
     * {@link CsvExportBuilder#to(File) File} and {@link CsvExportBuilder#to(Writer) Writer} targets.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * long n = DataTransferUtil.exportCsvFrom(dataSource, "SELECT id, name, email FROM users")
     *         .columns(List.of("id", "name"))
     *         .to(new File("users.csv"));
     * }</pre>
     *
     * @param sourceDataSource the DataSource to obtain a connection from (must not be {@code null})
     * @param selectSql the SQL query to execute (must not be {@code null})
     * @return a {@link CsvExportBuilder}
     * @throws IllegalArgumentException if {@code sourceDataSource} or {@code selectSql} is {@code null}
     * @see #exportCsvFrom(Connection, String)
     */
    @Beta
    public static CsvExportBuilder exportCsvFrom(final javax.sql.DataSource sourceDataSource, final String selectSql) {
        N.checkArgNotNull(sourceDataSource, "sourceDataSource");
        N.checkArgNotNull(selectSql, "selectSql");

        return new CsvExportBuilder(sourceDataSource, null, null, null, selectSql);
    }

    /**
     * Creates a fluent builder for exporting the rows of a SELECT query (run against the given {@link Connection}) to
     * CSV. The connection is NOT closed by the builder.
     *
     * @param conn the Connection to run the query against (must not be {@code null}; not closed by the builder)
     * @param selectSql the SQL query to execute (must not be {@code null})
     * @return a {@link CsvExportBuilder}
     * @throws IllegalArgumentException if {@code conn} or {@code selectSql} is {@code null}
     * @see #exportCsvFrom(javax.sql.DataSource, String)
     */
    @Beta
    public static CsvExportBuilder exportCsvFrom(final Connection conn, final String selectSql) {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(selectSql, "selectSql");

        return new CsvExportBuilder(null, conn, null, null, selectSql);
    }

    /**
     * Creates a fluent builder for exporting the result of executing the given {@link PreparedStatement} to CSV. The
     * statement is executed when a terminal {@code to(...)} runs; the statement is NOT closed by the builder (the
     * {@code ResultSet} it produces is).
     *
     * @param stmt the PreparedStatement to execute (must not be {@code null}; not closed by the builder)
     * @return a {@link CsvExportBuilder}
     * @throws IllegalArgumentException if {@code stmt} is {@code null}
     */
    @Beta
    public static CsvExportBuilder exportCsvFrom(final PreparedStatement stmt) {
        N.checkArgNotNull(stmt, "stmt");

        return new CsvExportBuilder(null, null, stmt, null, null);
    }

    /**
     * Creates a fluent builder for exporting the rows of the given {@link ResultSet} to CSV. Export starts with the
     * next row after its current cursor position. The {@code ResultSet} is NOT closed by the builder.
     *
     * @param rs the ResultSet to export (must not be {@code null}; not closed by the builder)
     * @return a {@link CsvExportBuilder}
     * @throws IllegalArgumentException if {@code rs} is {@code null}
     */
    @Beta
    public static CsvExportBuilder exportCsvFrom(final ResultSet rs) {
        N.checkArgNotNull(rs, "rs");

        return new CsvExportBuilder(null, null, null, rs, null);
    }

    /**
     * A fluent builder that exports the rows of a query/result to CSV. Obtain an instance via one of the
     * {@code exportCsvFrom(...)} factory methods ({@link DataTransferUtil#exportCsvFrom(javax.sql.DataSource, String)},
     * {@link DataTransferUtil#exportCsvFrom(Connection, String)}, {@link DataTransferUtil#exportCsvFrom(PreparedStatement)} or
     * {@link DataTransferUtil#exportCsvFrom(ResultSet)}), optionally restrict the columns with
     * {@link #columns(Collection)}, then write to a {@link File} or a {@link Writer} with a terminal
     * {@code to(...)} call.
     *
     * <p>The exported CSV always starts with a header row of column labels. A caller-supplied
     * {@code Connection}/{@code PreparedStatement}/{@code ResultSet} is never closed; a {@code DataSource} connection is
     * obtained and released by the terminal. A {@code File} target is created if it does not exist; a {@code Writer}
     * target is flushed but not closed.</p>
     *
     * @see DataTransferUtil#exportCsvFrom(javax.sql.DataSource, String)
     */
    public static final class CsvExportBuilder {
        private final javax.sql.DataSource dataSource;
        private final Connection conn;
        private final PreparedStatement stmt;
        private final ResultSet rs;
        private final String selectSql;
        private Collection<String> columnNames;

        CsvExportBuilder(final javax.sql.DataSource dataSource, final Connection conn, final PreparedStatement stmt, final ResultSet rs,
                final String selectSql) {
            this.dataSource = dataSource;
            this.conn = conn;
            this.stmt = stmt;
            this.rs = rs;
            this.selectSql = selectSql;
        }

        /**
         * Restricts the export to the named columns (matched against the query's result-set column labels), in the
         * order they appear in the result. {@code null} or empty exports all columns.
         *
         * @param columns the column names to export; {@code null} for all columns. The supplied collection is copied.
         * @return this builder
         */
        public CsvExportBuilder columns(final Collection<String> columns) {
            columnNames = N.isEmpty(columns) ? null : new ArrayList<>(columns);

            return this;
        }

        /**
         * Runs the export and writes the CSV to the given {@link File} (created if it does not exist).
         *
         * @param output the file to write to
         * @return the number of rows exported
         * @throws IllegalArgumentException if {@code output} is {@code null} or a configured column name is not present in the query result
         * @throws IllegalStateException if the builder does not have exactly one query source (a DataSource, Connection,
         *         PreparedStatement or ResultSet) configured; normally guaranteed by the factory methods
         * @throws SQLException if a database access error occurs
         * @throws UncheckedIOException if an I/O error occurs while writing
         */
        public long to(final File output) throws SQLException {
            N.checkArgNotNull(output, "output");

            return export(r -> exportCsv(r, columnNames, output));
        }

        /**
         * Runs the export and writes the CSV to the given {@link Writer} (flushed, but not closed).
         *
         * @param output the writer to write to
         * @return the number of rows exported
         * @throws IllegalArgumentException if {@code output} is {@code null} or a configured column name is not present in the query result
         * @throws IllegalStateException if the builder does not have exactly one query source (a DataSource, Connection,
         *         PreparedStatement or ResultSet) configured; normally guaranteed by the factory methods
         * @throws SQLException if a database access error occurs
         * @throws UncheckedIOException if an I/O error occurs while writing
         */
        public long to(final Writer output) throws SQLException {
            N.checkArgNotNull(output, "output");

            return export(r -> exportCsv(r, columnNames, output));
        }

        private long export(final ResultSetExporter exporter) throws SQLException {
            int configuredSources = 0;

            if (dataSource != null) {
                configuredSources++;
            }

            if (conn != null) {
                configuredSources++;
            }

            if (stmt != null) {
                configuredSources++;
            }

            if (rs != null) {
                configuredSources++;
            }

            if (configuredSources != 1) {
                throw new IllegalStateException("Exactly one of 'dataSource', 'conn', 'stmt' or 'rs' must be configured for a single export");
            }

            if (rs != null) {
                return exporter.export(rs);
            } else if (stmt != null) {
                ResultSet r = null;

                try {
                    r = JdbcUtil.executeQuery(stmt);

                    return exporter.export(r);
                } finally {
                    JdbcUtil.closeQuietly(r);
                }
            } else if (conn != null) {
                return exportFromConnection(conn, exporter);
            } else {
                final Connection c = JdbcUtil.getConnection(dataSource);

                try {
                    return exportFromConnection(c, exporter);
                } finally {
                    JdbcUtil.releaseConnection(c, dataSource);
                }
            }
        }

        private long exportFromConnection(final Connection c, final ResultSetExporter exporter) throws SQLException {
            final ParsedSql sql = ParsedSql.parse(selectSql);
            final PreparedStatement st = JdbcUtil.prepareStatement(c, sql.parameterizedSql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);

            try {
                setFetchForLargeResult(c, st);

                try (ResultSet r = JdbcUtil.executeQuery(st)) {
                    return exporter.export(r);
                }
            } finally {
                JdbcUtil.closeQuietly(st);
            }
        }

        @FunctionalInterface
        private interface ResultSetExporter {
            long export(ResultSet rs) throws SQLException;
        }
    }

    /**
     * Creates a fluent builder for copying the rows of a SELECT query from a source {@link javax.sql.DataSource}
     * into a target table, using explicit SELECT and INSERT SQL.
     *
     * <p>The returned {@link CopyFromDataSource} lets you configure {@code fetchSize}, {@code batchSize},
     * {@code batchIntervalInMillis} and a custom {@code parameterSetter} through chained calls, then run the copy
     * with {@link CopyFromDataSource#to(javax.sql.DataSource, String)}. It is an ergonomic alternative to the
     * positional {@code copy(DataSource, String, ..., DataSource, String, ...)} overloads.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * long copied = DataTransferUtil.copyFrom(sourceDataSource, "SELECT id, name FROM users WHERE active = true")
     *         .fetchSize(50000)
     *         .batchSize(5000)
     *         .to(targetDataSource, "INSERT INTO active_users (id, name) VALUES (?, ?)");
     * }</pre>
     *
     * @param sourceDataSource the data source to read from (must not be {@code null})
     * @param selectSql the SQL query selecting the rows to copy (must not be {@code null})
     * @return a {@link CopyFromDataSource} for configuring and running the copy
     * @throws IllegalArgumentException if {@code sourceDataSource} or {@code selectSql} is {@code null}
     * @see CopyFromDataSource
     * @see #copy(javax.sql.DataSource, String, javax.sql.DataSource, String)
     */
    @Beta
    public static CopyFromDataSource copyFrom(final javax.sql.DataSource sourceDataSource, final String selectSql) {
        N.checkArgNotNull(sourceDataSource, "sourceDataSource");
        N.checkArgNotNull(selectSql, "selectSql");

        return new CopyFromDataSource(sourceDataSource, selectSql);
    }

    /**
     * Creates a fluent builder for copying the rows of a SELECT query from a source {@link Connection} into a
     * target table on another {@link Connection}, using explicit SELECT and INSERT SQL.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * long copied = DataTransferUtil.copyFrom(sourceConn, "SELECT * FROM orders")
     *         .batchSize(1000)
     *         .batchDelay(Duration.ofMillis(100))
     *         .to(targetConn, "INSERT INTO orders_archive VALUES (?, ?, ?, ?)");
     * }</pre>
     *
     * @param sourceConn the connection to read from (must not be {@code null})
     * @param selectSql the SQL query selecting the rows to copy (must not be {@code null})
     * @return a {@link CopyFromConnection} for configuring and running the copy
     * @throws IllegalArgumentException if {@code sourceConn} or {@code selectSql} is {@code null}
     * @see CopyFromConnection
     * @see #copy(Connection, String, Connection, String)
     */
    @Beta
    public static CopyFromConnection copyFrom(final Connection sourceConn, final String selectSql) {
        N.checkArgNotNull(sourceConn, "sourceConn");
        N.checkArgNotNull(selectSql, "selectSql");

        return new CopyFromConnection(sourceConn, selectSql);
    }

    /**
     * Creates a fluent builder for copying the rows produced by a source {@link PreparedStatement} into a
     * target {@link PreparedStatement}.
     *
     * <p>Because the select statement is supplied directly, the fetch size is whatever the caller configured on
     * it; the builder does not expose {@code fetchSize}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * long copied = DataTransferUtil.copyFrom(selectStmt)
     *         .batchSize(1000)
     *         .parameterSetter((pq, rs) -> { pq.setLong(1, rs.getLong(1)); pq.setString(2, rs.getString(2)); })
     *         .to(insertStmt);
     * }</pre>
     *
     * @param selectStmt the statement that produces the rows to copy (must not be {@code null}; not closed by the copy)
     * @return a {@link CopyFromStatement} for configuring and running the copy
     * @throws IllegalArgumentException if {@code selectStmt} is {@code null}
     * @see CopyFromStatement
     * @see #copy(PreparedStatement, PreparedStatement, int, long, Throwables.BiConsumer)
     */
    @Beta
    public static CopyFromStatement copyFrom(final PreparedStatement selectStmt) {
        N.checkArgNotNull(selectStmt, "selectStmt");

        return new CopyFromStatement(selectStmt);
    }

    /**
     * Creates a fluent builder for copying a whole table (or selected columns) from a source
     * {@link javax.sql.DataSource} to a target table, generating the SELECT and INSERT SQL from the table schema.
     *
     * <p>The returned {@link CopyTableFromDataSource} lets you configure {@code columnNames} and
     * {@code batchSize}, then run the copy with {@link CopyTableFromDataSource#to(javax.sql.DataSource, String)}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Copy the whole table to a backup table
     * long copied = DataTransferUtil.copyTable(sourceDataSource, "users").to(targetDataSource, "users_backup");
     *
     * // Copy selected columns with a custom batch size
     * long partial = DataTransferUtil.copyTable(sourceDataSource, "users")
     *         .columns(List.of("id", "name", "email"))
     *         .batchSize(10000)
     *         .to(targetDataSource, "users_lite");
     * }</pre>
     *
     * @param sourceDataSource the data source to read from (must not be {@code null})
     * @param sourceTableName the name of the source table (must not be blank)
     * @return a {@link CopyTableFromDataSource} for configuring and running the copy
     * @throws IllegalArgumentException if {@code sourceDataSource} is {@code null} or {@code sourceTableName} is blank
     * @see CopyTableFromDataSource
     * @see #copy(javax.sql.DataSource, javax.sql.DataSource, String, String)
     */
    @Beta
    public static CopyTableFromDataSource copyTable(final javax.sql.DataSource sourceDataSource, final String sourceTableName) {
        N.checkArgNotNull(sourceDataSource, "sourceDataSource");
        N.checkArgNotBlank(sourceTableName, "sourceTableName");

        return new CopyTableFromDataSource(sourceDataSource, sourceTableName);
    }

    /**
     * Creates a fluent builder for copying a whole table (or selected columns) from a source {@link Connection}
     * to a target table on another {@link Connection}, generating the SELECT and INSERT SQL from the table schema.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * long copied = DataTransferUtil.copyTable(sourceConn, "users")
     *         .columns(List.of("id", "name"))
     *         .to(targetConn, "users_backup");
     * }</pre>
     *
     * @param sourceConn the connection to read from (must not be {@code null})
     * @param sourceTableName the name of the source table (must not be blank)
     * @return a {@link CopyTableFromConnection} for configuring and running the copy
     * @throws IllegalArgumentException if {@code sourceConn} is {@code null} or {@code sourceTableName} is blank
     * @see CopyTableFromConnection
     * @see #copy(Connection, Connection, String, String)
     */
    @Beta
    public static CopyTableFromConnection copyTable(final Connection sourceConn, final String sourceTableName) {
        N.checkArgNotNull(sourceConn, "sourceConn");
        N.checkArgNotBlank(sourceTableName, "sourceTableName");

        return new CopyTableFromConnection(sourceConn, sourceTableName);
    }

    /**
     * A fluent builder that copies the rows of a SELECT query from a source {@link javax.sql.DataSource} into a
     * target table. Obtain an instance via {@link DataTransferUtil#copyFrom(javax.sql.DataSource, String)}, chain
     * any of the optional configuration methods ({@link #fetchSize(int)}, {@link #batchSize(int)},
     * {@link #batchDelay(Duration)}, {@link #parameterSetter(Throwables.BiConsumer)}), then call the terminal
     * {@link #to(javax.sql.DataSource, String)} to run the copy. Each configuration method returns {@code this}.
     *
     * <p>Connections are obtained from the source and target data sources and released back to them when the
     * copy completes.</p>
     *
     * @see DataTransferUtil#copyFrom(javax.sql.DataSource, String)
     */
    public static final class CopyFromDataSource {
        private final javax.sql.DataSource sourceDataSource;
        private final String selectSql;
        private int fetchSize = JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET;
        private int batchSize = JdbcUtil.DEFAULT_BATCH_SIZE;
        private long batchIntervalInMillis = 0;
        private Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> parameterSetter;

        CopyFromDataSource(final javax.sql.DataSource sourceDataSource, final String selectSql) {
            this.sourceDataSource = sourceDataSource;
            this.selectSql = selectSql;
        }

        /**
         * Sets the number of rows fetched from the source at a time (should be {@code >=} the batch size).
         *
         * @param fetchSize the JDBC fetch-size hint; must be {@code >= 0} when {@code to(...)} is called
         * @return this builder
         */
        public CopyFromDataSource fetchSize(final int fetchSize) {
            this.fetchSize = fetchSize;

            return this;
        }

        /**
         * Sets the number of rows inserted into the target per batch.
         *
         * @param batchSize the batch size (must be greater than 0 when {@code to(...)} is called)
         * @return this builder
         */
        public CopyFromDataSource batchSize(final int batchSize) {
            this.batchSize = batchSize;

            return this;
        }

        /**
         * Sets the pause between consecutive batch executions.
         *
         * @param delay the delay between completed batches; must not be {@code null}, negative, or too large to represent in milliseconds
         * @return this builder
         * @throws IllegalArgumentException if {@code delay} is {@code null}, negative, or too large to represent in milliseconds
         */
        public CopyFromDataSource batchDelay(final Duration delay) {
            batchIntervalInMillis = toBatchIntervalMillis(delay);

            return this;
        }

        /**
         * Sets a custom setter mapping each source {@link ResultSet} row to the target insert parameters. When
         * {@code null} (the default), all columns are copied by index.
         *
         * @param parameterSetter the parameter setter
         * @return this builder
         */
        public CopyFromDataSource parameterSetter(final Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> parameterSetter) {
            this.parameterSetter = parameterSetter;

            return this;
        }

        /**
         * Runs the copy into the given target DataSource and insert SQL.
         *
         * @param targetDataSource the data source to write to
         * @param insertSql the SQL insert statement with placeholders matching the selected columns
         * @return the number of rows copied
         * @throws IllegalArgumentException if {@code fetchSize < 0} or {@code batchSize <= 0}
         * @throws SQLException if a database access error occurs
         */
        public long to(final javax.sql.DataSource targetDataSource, final String insertSql) throws SQLException {
            return copy(sourceDataSource, selectSql, fetchSize, targetDataSource, insertSql, batchSize, batchIntervalInMillis, parameterSetter);
        }
    }

    /**
     * A fluent builder that copies the rows of a SELECT query between two {@link Connection}s. Obtain an instance
     * via {@link DataTransferUtil#copyFrom(Connection, String)}, chain any of the optional configuration methods
     * ({@link #fetchSize(int)}, {@link #batchSize(int)}, {@link #batchDelay(Duration)},
     * {@link #parameterSetter(Throwables.BiConsumer)}), then call the terminal {@link #to(Connection, String)} to run
     * the copy. Each configuration method returns {@code this}.
     *
     * <p>Neither the source nor the target connection is closed by the copy; the caller retains ownership.</p>
     *
     * @see DataTransferUtil#copyFrom(Connection, String)
     */
    public static final class CopyFromConnection {
        private final Connection sourceConn;
        private final String selectSql;
        private int fetchSize = JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET;
        private int batchSize = JdbcUtil.DEFAULT_BATCH_SIZE;
        private long batchIntervalInMillis = 0;
        private Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> parameterSetter;

        CopyFromConnection(final Connection sourceConn, final String selectSql) {
            this.sourceConn = sourceConn;
            this.selectSql = selectSql;
        }

        /**
         * Sets the number of rows fetched from the source at a time (should be {@code >=} the batch size).
         *
         * @param fetchSize the JDBC fetch-size hint; must be {@code >= 0} when {@code to(...)} is called
         * @return this builder
         */
        public CopyFromConnection fetchSize(final int fetchSize) {
            this.fetchSize = fetchSize;

            return this;
        }

        /**
         * Sets the number of rows inserted into the target per batch.
         *
         * @param batchSize the batch size (must be greater than 0 when {@code to(...)} is called)
         * @return this builder
         */
        public CopyFromConnection batchSize(final int batchSize) {
            this.batchSize = batchSize;

            return this;
        }

        /**
         * Sets the pause between consecutive batch executions.
         *
         * @param delay the delay between completed batches; must not be {@code null}, negative, or too large to represent in milliseconds
         * @return this builder
         * @throws IllegalArgumentException if {@code delay} is {@code null}, negative, or too large to represent in milliseconds
         */
        public CopyFromConnection batchDelay(final Duration delay) {
            batchIntervalInMillis = toBatchIntervalMillis(delay);

            return this;
        }

        /**
         * Sets a custom setter mapping each source {@link ResultSet} row to the target insert parameters. When
         * {@code null} (the default), all columns are copied by index.
         *
         * @param parameterSetter the parameter setter
         * @return this builder
         */
        public CopyFromConnection parameterSetter(final Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> parameterSetter) {
            this.parameterSetter = parameterSetter;

            return this;
        }

        /**
         * Runs the copy into the given target Connection and insert SQL.
         *
         * @param targetConn the connection to write to
         * @param insertSql the SQL insert statement with placeholders matching the selected columns
         * @return the number of rows copied
         * @throws IllegalArgumentException if {@code fetchSize < 0} or {@code batchSize <= 0}
         * @throws SQLException if a database access error occurs
         */
        public long to(final Connection targetConn, final String insertSql) throws SQLException {
            return copy(sourceConn, selectSql, fetchSize, targetConn, insertSql, batchSize, batchIntervalInMillis, parameterSetter);
        }
    }

    /**
     * A fluent builder that copies the rows produced by a source {@link PreparedStatement} into a target
     * {@link PreparedStatement}. Obtain an instance via {@link DataTransferUtil#copyFrom(PreparedStatement)}, chain
     * any of the optional configuration methods ({@link #batchSize(int)}, {@link #batchDelay(Duration)},
     * {@link #parameterSetter(Throwables.BiConsumer)}), then call the terminal {@link #to(PreparedStatement)} to run the
     * copy. Each configuration method returns {@code this}.
     *
     * <p>The fetch size is whatever the caller already configured on the source statement, so this builder does
     * not expose a {@code fetchSize} option. Neither the source nor the target statement is closed by the copy.</p>
     *
     * @see DataTransferUtil#copyFrom(PreparedStatement)
     */
    public static final class CopyFromStatement {
        private final PreparedStatement selectStmt;
        private int batchSize = JdbcUtil.DEFAULT_BATCH_SIZE;
        private long batchIntervalInMillis = 0;
        private Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> parameterSetter;

        CopyFromStatement(final PreparedStatement selectStmt) {
            this.selectStmt = selectStmt;
        }

        /**
         * Sets the number of rows inserted into the target per batch.
         *
         * @param batchSize the batch size (must be greater than 0 when {@code to(...)} is called)
         * @return this builder
         */
        public CopyFromStatement batchSize(final int batchSize) {
            this.batchSize = batchSize;

            return this;
        }

        /**
         * Sets the pause between consecutive batch executions.
         *
         * @param delay the delay between completed batches; must not be {@code null}, negative, or too large to represent in milliseconds
         * @return this builder
         * @throws IllegalArgumentException if {@code delay} is {@code null}, negative, or too large to represent in milliseconds
         */
        public CopyFromStatement batchDelay(final Duration delay) {
            batchIntervalInMillis = toBatchIntervalMillis(delay);

            return this;
        }

        /**
         * Sets a custom setter mapping each source {@link ResultSet} row to the target insert parameters. When
         * {@code null} (the default), all columns are copied by index.
         *
         * @param parameterSetter the parameter setter
         * @return this builder
         */
        public CopyFromStatement parameterSetter(final Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> parameterSetter) {
            this.parameterSetter = parameterSetter;

            return this;
        }

        /**
         * Runs the copy into the given target statement.
         *
         * @param insertStmt the statement to insert into (not closed by this method)
         * @return the number of rows copied
         * @throws IllegalArgumentException if {@code batchSize <= 0}
         * @throws SQLException if a database access error occurs
         */
        public long to(final PreparedStatement insertStmt) throws SQLException {
            return copy(selectStmt, insertStmt, batchSize, batchIntervalInMillis, parameterSetter);
        }
    }

    /**
     * A fluent builder that copies a table (or selected columns) between two {@link javax.sql.DataSource}s,
     * generating the SELECT and INSERT SQL from the table schema. Obtain an instance via
     * {@link DataTransferUtil#copyTable(javax.sql.DataSource, String)}, optionally chain {@link #columns(Collection)}
     * and {@link #batchSize(int)}, then call the terminal {@link #to(javax.sql.DataSource, String)} to run the copy.
     * Each configuration method returns {@code this}.
     *
     * <p>Connections are obtained from the source and target data sources and released back to them when the
     * copy completes.</p>
     *
     * @see DataTransferUtil#copyTable(javax.sql.DataSource, String)
     */
    public static final class CopyTableFromDataSource {
        private final javax.sql.DataSource sourceDataSource;
        private final String sourceTableName;
        private Collection<String> columnNames;
        private int batchSize = JdbcUtil.DEFAULT_BATCH_SIZE;

        CopyTableFromDataSource(final javax.sql.DataSource sourceDataSource, final String sourceTableName) {
            this.sourceDataSource = sourceDataSource;
            this.sourceTableName = sourceTableName;
        }

        /**
         * Restricts the copy to the specified columns. When {@code null} or empty (the default), all columns are copied.
         *
         * @param columns the column names to copy. The supplied collection is copied.
         * @return this builder
         */
        public CopyTableFromDataSource columns(final Collection<String> columns) {
            columnNames = N.isEmpty(columns) ? null : new ArrayList<>(columns);

            return this;
        }

        /**
         * Sets the number of rows copied per batch.
         *
         * @param batchSize the batch size (must be greater than 0 when {@code to(...)} is called)
         * @return this builder
         */
        public CopyTableFromDataSource batchSize(final int batchSize) {
            this.batchSize = batchSize;

            return this;
        }

        /**
         * Runs the copy into the given target DataSource and table.
         *
         * @param targetDataSource the data source to write to
         * @param targetTableName the name of the target table
         * @return the number of rows copied
         * @throws IllegalArgumentException if {@code batchSize <= 0}
         * @throws SQLException if a database access error occurs
         */
        public long to(final javax.sql.DataSource targetDataSource, final String targetTableName) throws SQLException {
            return N.isEmpty(columnNames) ? copy(sourceDataSource, targetDataSource, sourceTableName, targetTableName, batchSize)
                    : copy(sourceDataSource, targetDataSource, sourceTableName, targetTableName, columnNames, batchSize);
        }
    }

    /**
     * A fluent builder that copies a table (or selected columns) between two {@link Connection}s, generating the
     * SELECT and INSERT SQL from the table schema. Obtain an instance via
     * {@link DataTransferUtil#copyTable(Connection, String)}, optionally chain {@link #columns(Collection)}
     * and {@link #batchSize(int)}, then call the terminal {@link #to(Connection, String)} to run the copy. Each
     * configuration method returns {@code this}.
     *
     * <p>Neither the source nor the target connection is closed by the copy; the caller retains ownership.</p>
     *
     * @see DataTransferUtil#copyTable(Connection, String)
     */
    public static final class CopyTableFromConnection {
        private final Connection sourceConn;
        private final String sourceTableName;
        private Collection<String> columnNames;
        private int batchSize = JdbcUtil.DEFAULT_BATCH_SIZE;

        CopyTableFromConnection(final Connection sourceConn, final String sourceTableName) {
            this.sourceConn = sourceConn;
            this.sourceTableName = sourceTableName;
        }

        /**
         * Restricts the copy to the specified columns. When {@code null} or empty (the default), all columns are copied.
         *
         * @param columns the column names to copy. The supplied collection is copied.
         * @return this builder
         */
        public CopyTableFromConnection columns(final Collection<String> columns) {
            columnNames = N.isEmpty(columns) ? null : new ArrayList<>(columns);

            return this;
        }

        /**
         * Sets the number of rows copied per batch.
         *
         * @param batchSize the batch size (must be greater than 0 when {@code to(...)} is called)
         * @return this builder
         */
        public CopyTableFromConnection batchSize(final int batchSize) {
            this.batchSize = batchSize;

            return this;
        }

        /**
         * Runs the copy into the given target Connection and table.
         *
         * @param targetConn the connection to write to
         * @param targetTableName the name of the target table
         * @return the number of rows copied
         * @throws IllegalArgumentException if {@code batchSize <= 0}
         * @throws SQLException if a database access error occurs
         */
        public long to(final Connection targetConn, final String targetTableName) throws SQLException {
            return N.isEmpty(columnNames) ? copy(sourceConn, targetConn, sourceTableName, targetTableName, batchSize)
                    : copy(sourceConn, targetConn, sourceTableName, targetTableName, columnNames, batchSize);
        }
    }
}
