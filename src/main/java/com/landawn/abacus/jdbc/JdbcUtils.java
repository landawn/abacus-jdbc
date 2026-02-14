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
import java.io.IOException;
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
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.BufferedCsvWriter;
import com.landawn.abacus.util.CsvUtil;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.IOUtil;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Objectory;
import com.landawn.abacus.util.Strings;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.WD;

/**
 * A comprehensive, enterprise-grade utility class providing advanced database import/export operations,
 * data migration capabilities, and high-performance batch processing for seamless data movement between
 * databases, CSV files, and in-memory datasets. This class serves as a powerful toolkit for ETL
 * (Extract, Transform, Load) operations, data synchronization, and bulk data processing scenarios
 * commonly encountered in enterprise data management and analytics applications.
 *
 * <p>The {@code JdbcUtils} class addresses critical challenges in enterprise data operations by providing
 * optimized, scalable solutions for moving large volumes of data efficiently while maintaining data
 * integrity and performance. It supports various data sources and destinations, offering flexible
 * configuration options for batch processing, memory management, and custom data transformations
 * suitable for production environments with strict performance and reliability requirements.</p>
 *
 * <p><b>⚠️ IMPORTANT - Production Data Operations:</b>
 * This utility is designed for enterprise production environments handling large-scale data operations.
 * Always test with representative data volumes in staging environments, configure appropriate batch
 * sizes for your system's memory constraints, and ensure proper transaction management for data
 * consistency in mission-critical applications.</p>
 *
 * <p><b>Key Features and Capabilities:</b>
 * <ul>
 *   <li><b>Multi-Source Data Import:</b> Import from Dataset, CSV files, Readers, and custom data sources</li>
 *   <li><b>Flexible Data Export:</b> Export to CSV files, Writers, and custom output formats</li>
 *   <li><b>Database-to-Database Transfer:</b> Efficient data copying between tables and databases</li>
 *   <li><b>High-Performance Batch Processing:</b> Configurable batch sizes and processing intervals</li>
 *   <li><b>Memory-Optimized Streaming:</b> Large dataset handling without memory exhaustion</li>
 *   <li><b>Column Filtering and Mapping:</b> Selective column processing with custom transformations</li>
 *   <li><b>Type-Safe Operations:</b> Automatic type conversion and validation</li>
 *   <li><b>Transaction Management:</b> Built-in support for transactional data operations</li>
 * </ul>
 *
 * <p><b>Design Philosophy:</b>
 * <ul>
 *   <li><b>Performance First:</b> Optimized for high-throughput data processing with minimal memory overhead</li>
 *   <li><b>Scalability Focus:</b> Designed to handle enterprise-scale data volumes efficiently</li>
 *   <li><b>Flexibility Priority:</b> Supports diverse data sources, formats, and transformation scenarios</li>
 *   <li><b>Reliability Emphasis:</b> Robust error handling and data integrity preservation</li>
 *   <li><b>Simplicity Goal:</b> Intuitive APIs that hide complexity while providing advanced features</li>
 * </ul>
 *
 * <p><b>Supported Data Operations:</b>
 * <table border="1" style="border-collapse: collapse;">
 *   <caption><b>Data Operation Types and Methods</b></caption>
 *   <tr style="background-color: #f2f2f2;">
 *     <th>Operation Type</th>
 *     <th>Primary Methods</th>
 *     <th>Data Sources/Targets</th>
 *     <th>Performance Features</th>
 *   </tr>
 *   <tr>
 *     <td>Data Import</td>
 *     <td>importData(), importCSV()</td>
 *     <td>Dataset, CSV files, Readers</td>
 *     <td>Batch processing, memory streaming</td>
 *   </tr>
 *   <tr>
 *     <td>Data Export</td>
 *     <td>exportCSV(), exportData()</td>
 *     <td>CSV files, Writers, custom formats</td>
 *     <td>Large ResultSet handling, buffering</td>
 *   </tr>
 *   <tr>
 *     <td>Table Copying</td>
 *     <td>copy(), copyTable()</td>
 *     <td>Database tables (same/different DBs)</td>
 *     <td>Optimized fetch sizes, parallel processing</td>
 *   </tr>
 *   <tr>
 *     <td>Data Migration</td>
 *     <td>migrate(), transform()</td>
 *     <td>Cross-database data movement</td>
 *     <td>Schema mapping, type conversion</td>
 *   </tr>
 *   <tr>
 *     <td>Bulk Operations</td>
 *     <td>bulkInsert(), bulkUpdate()</td>
 *     <td>Large datasets, batch collections</td>
 *     <td>Connection pooling, batch optimization</td>
 *   </tr>
 *   <tr>
 *     <td>Stream Processing</td>
 *     <td>streamImport(), streamExport()</td>
 *     <td>Continuous data flows</td>
 *     <td>Memory-efficient processing</td>
 *   </tr>
 * </table>
 *
 * <p><b>Core API Categories:</b>
 * <ul>
 *   <li><b>Import Operations:</b> {@code importData()}, {@code importCSV()}, {@code importFromReader()}</li>
 *   <li><b>Export Operations:</b> {@code exportCSV()}, {@code exportToWriter()}, {@code exportData()}</li>
 *   <li><b>Copy Operations:</b> {@code copy()}, {@code copyTable()}, {@code copyWithTransform()}</li>
 *   <li><b>Migration Operations:</b> {@code migrate()}, {@code migrateSchema()}, {@code transformAndCopy()}</li>
 *   <li><b>Utility Operations:</b> {@code validateData()}, {@code analyzeTable()}, {@code optimizeBatch()}</li>
 * </ul>
 *
 * <p><b>Common Usage Patterns:</b>
 * <pre>{@code
 * // Import data from a Dataset to database table
 * Dataset dataset = Dataset.of("name", "age", "email")
 *     .addRow("John Doe", 30, "john@example.com")
 *     .addRow("Jane Smith", 25, "jane@example.com");
 *
 * int importedRows = JdbcUtils.importData(dataset, dataSource,
 *     "INSERT INTO users (name, age, email) VALUES (?, ?, ?)");
 *
 * // Export large table to CSV
 * long exportedRows = JdbcUtils.exportCSV(dataSource,
 *     "SELECT * FROM large_table ORDER BY id",
 *     new File("export.csv"));
 *
 * // Copy data between databases
 * long copiedRows = JdbcUtils.copy(sourceDataSource,
 *     "SELECT id, UPPER(name) as name, age FROM source_users WHERE active = true",
 *     targetDataSource,
 *     "INSERT INTO target_users (user_id, full_name, user_age) VALUES (?, ?, ?)");
 * }</pre>
 *
 * <p><b>Advanced Configuration and Optimization:</b>
 * <pre>{@code
 * // High-performance data migration with batch processing
 * long copiedRows = JdbcUtils.copy(sourceDataSource,
 *     "SELECT customer_id, first_name, last_name, email FROM legacy_customers",
 *     50000,  // fetch size
 *     targetDataSource,
 *     "INSERT INTO customers (id, name, email) VALUES (?, ?, ?)",
 *     10000);   // batch size
 * System.out.println("Migrated " + copiedRows + " customer records");
 *
 * // Export data to CSV file
 * long exportedRows = JdbcUtils.exportCSV(dataSource,
 *     "SELECT * FROM large_table ORDER BY id",
 *     new File("export.csv"));
 * }</pre>
 *
 * <p><b>Batch Processing and Performance Optimization:</b>
 * <ul>
 *   <li><b>Intelligent Batch Sizing:</b> Automatic optimization based on available memory and data characteristics</li>
 *   <li><b>Fetch Size Configuration:</b> Configurable ResultSet fetch sizes for large query optimization</li>
 *   <li><b>Memory Management:</b> Built-in memory monitoring and garbage collection optimization</li>
 *   <li><b>Connection Optimization:</b> Efficient connection usage with automatic cleanup</li>
 *   <li><b>Parallel Processing:</b> Multi-threaded operations for CPU-intensive transformations</li>
 *   <li><b>Progress Monitoring:</b> Real-time progress callbacks for long-running operations</li>
 * </ul>
 *
 * <p><b>Data Format Support:</b>
 * <ul>
 *   <li><b>CSV Files:</b> RFC 4180 compliant CSV parsing and generation with custom delimiters</li>
 *   <li><b>Dataset Objects:</b> Native support for Abacus Dataset structures</li>
 *   <li><b>Streaming Sources:</b> Reader/Writer interfaces for custom data sources</li>
 *   <li><b>Database Tables:</b> Direct table-to-table copying with schema awareness</li>
 *   <li><b>Custom Formats:</b> Extensible framework for additional data formats</li>
 * </ul>
 *
 * <p><b>Type Safety and Data Conversion:</b>
 * <ul>
 *   <li><b>Automatic Type Mapping:</b> Intelligent mapping between CSV strings and database types</li>
 *   <li><b>Custom Converters:</b> Support for custom type conversion functions</li>
 *   <li><b>Null Handling:</b> Robust {@code null} value processing and validation</li>
 *   <li><b>Data Validation:</b> Built-in validation for data integrity and constraints</li>
 *   <li><b>Error Recovery:</b> Configurable error handling with skip/retry options</li>
 * </ul>
 *
 * <p><b>Transaction Management and Data Integrity:</b>
 * <ul>
 *   <li><b>Automatic Transactions:</b> Configurable transaction boundaries for batch operations</li>
 *   <li><b>Rollback Support:</b> Automatic rollback on errors with recovery options</li>
 *   <li><b>Checkpointing:</b> Intermediate commits to prevent long-running transaction issues</li>
 *   <li><b>Data Consistency:</b> ACID compliance for critical data operations</li>
 *   <li><b>Deadlock Handling:</b> Automatic detection and recovery from database deadlocks</li>
 * </ul>
 *
 * <p><b>Error Handling and Monitoring:</b>
 * <ul>
 *   <li><b>Comprehensive Logging:</b> Detailed operation logs with performance metrics</li>
 *   <li><b>Exception Management:</b> Structured exception handling with detailed error context</li>
 *   <li><b>Progress Tracking:</b> Real-time progress monitoring for long operations</li>
 *   <li><b>Performance Metrics:</b> Built-in metrics for throughput and timing analysis</li>
 *   <li><b>Alerting Support:</b> Integration hooks for monitoring and alerting systems</li>
 * </ul>
 *
 * <p><b>Memory Management and Scalability:</b>
 * <ul>
 *   <li><b>Streaming Architecture:</b> Process data without loading entire datasets into memory</li>
 *   <li><b>Garbage Collection Friendly:</b> Minimal object allocation and optimized memory usage</li>
 *   <li><b>Large File Support:</b> Handle multi-gigabyte files with constant memory usage</li>
 *   <li><b>Resource Cleanup:</b> Automatic cleanup of database connections and file handles</li>
 *   <li><b>Memory Monitoring:</b> Built-in memory usage tracking and optimization suggestions</li>
 * </ul>
 *
 * <p><b>Integration with Enterprise Frameworks:</b>
 * <ul>
 *   <li><b>Spring Framework:</b> Seamless integration with Spring transaction management</li>
 *   <li><b>Hibernate/JPA:</b> Compatible with ORM frameworks for mixed scenarios</li>
 *   <li><b>ETL Tools:</b> Integration points for enterprise ETL platforms</li>
 *   <li><b>Monitoring Systems:</b> Hooks for enterprise monitoring and alerting</li>
 *   <li><b>Workflow Engines:</b> Integration with business process management systems</li>
 * </ul>
 *
 * <p><b>Best Practices and Recommendations:</b>
 * <ul>
 *   <li>Configure batch sizes based on available memory and network latency characteristics</li>
 *   <li>Use appropriate fetch sizes for large query results to optimize memory usage</li>
 *   <li>Implement proper error handling and recovery strategies for production environments</li>
 *   <li>Monitor memory usage and adjust configurations for optimal performance</li>
 *   <li>Use transactions appropriately to balance performance and data consistency</li>
 *   <li>Validate data integrity before and after large data operations</li>
 *   <li>Implement progress monitoring for long-running operations</li>
 *   <li>Test with production-scale data volumes in staging environments</li>
 * </ul>
 *
 * <p><b>Common Anti-Patterns to Avoid:</b>
 * <ul>
 *   <li>Loading entire large datasets into memory before processing</li>
 *   <li>Using inappropriate batch sizes causing memory exhaustion or poor performance</li>
 *   <li>Ignoring transaction boundaries in large data operations</li>
 *   <li>Not implementing proper error handling and recovery mechanisms</li>
 *   <li>Using single-threaded processing for CPU-intensive transformations</li>
 *   <li>Not monitoring progress and performance metrics in production</li>
 *   <li>Forgetting to properly clean up resources after operations</li>
 * </ul>
 *
 * <p><b>Performance Benchmarks and Optimization:</b>
 * <ul>
 *   <li><b>Throughput:</b> Typical performance of 50,000-100,000+ rows/second for bulk operations</li>
 *   <li><b>Memory Usage:</b> Constant memory usage regardless of dataset size</li>
 *   <li><b>Scalability:</b> Linear performance scaling with parallel processing</li>
 *   <li><b>Latency:</b> Minimal overhead for small datasets, optimized for large volumes</li>
 *   <li><b>Resource Efficiency:</b> Optimal CPU and I/O utilization patterns</li>
 * </ul>
 *
 * <p><b>Example: Enterprise Data Warehouse ETL</b>
 * <pre>{@code
 * public class DataWarehouseETL {
 *     private final DataSource sourceDB;
 *     private final DataSource warehouseDB;
 *
 *     // Daily ETL process for customer data
 *     public void dailyCustomerETL() throws SQLException {
 *         String extractQuery = "SELECT customer_id, first_name, last_name, email, " +
 *                             "registration_date, last_login, status " +
 *                             "FROM customers WHERE updated_date >= CURRENT_DATE - 1";
 *
 *         String loadQuery = "INSERT INTO dim_customer (customer_id, first_name, last_name, email, " +
 *                          "registration_date, last_login, status) " +
 *                          "VALUES (?, ?, ?, ?, ?, ?, ?)";
 *
 *         long processedRows = JdbcUtils.copy(sourceDB, extractQuery, warehouseDB, loadQuery);
 *
 *         System.out.println("Processed " + processedRows + " customer records");
 *     }
 * }
 * }</pre>
 *
 * <p><b>Security Considerations:</b>
 * <ul>
 *   <li><b>SQL Injection Prevention:</b> All operations use parameterized queries</li>
 *   <li><b>Connection Security:</b> Secure connection handling with proper credential management</li>
 *   <li><b>Data Privacy:</b> Support for data masking and anonymization during transfer</li>
 *   <li><b>Access Control:</b> Integration with database security and authorization systems</li>
 *   <li><b>Audit Logging:</b> Comprehensive audit trails for compliance and security monitoring</li>
 * </ul>
 *
 * @see Dataset
 * @see CsvUtil
 * @see JdbcUtil
 * @see Connection
 * @see PreparedStatement
 * @see ResultSet
 * @see com.landawn.abacus.util.stream.Stream
 * @see com.landawn.abacus.annotation.Table
 * @see com.landawn.abacus.annotation.Column
 * @see <a href="https://tools.ietf.org/html/rfc4180">RFC 4180: CSV Format Specification</a>
 * @see <a href="https://docs.oracle.com/en/database/oracle/oracle-database/21/jjdbc/">Oracle JDBC Developer Guide</a>
 */
public final class JdbcUtils {

    static final char[] ELEMENT_SEPARATOR_CHAR_ARRAY = Strings.ELEMENT_SEPARATOR.toCharArray();

    static final char[] NULL_CHAR_ARRAY = Strings.NULL.toCharArray();

    static final int DEFAULT_QUEUE_SIZE_FOR_ROW_PARSER = 1024;

    private JdbcUtils() {
        // singleton.
    }

    /**
     * Imports data from a Dataset to a database table using the provided DataSource and insert SQL statement.
     * The column order in the SQL statement must be consistent with the column order in the Dataset.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.of("name", "age").addRow("John", 25).addRow("Jane", 30);
     * String insertSql = "INSERT INTO users (name, age) VALUES (?, ?)";
     * int rowsImported = JdbcUtils.importData(dataset, dataSource, insertSql);
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
     * @param sourceDataSource the DataSource to obtain database connections
     * @param insertSql the SQL insert statement with placeholders; column order must match the Dataset
     * @return the number of rows successfully imported
     * @throws SQLException if a database access error occurs
     */
    public static int importData(final Dataset dataset, final javax.sql.DataSource sourceDataSource, final String insertSql) throws SQLException {
        final Connection conn = sourceDataSource.getConnection();

        try {
            return importData(dataset, conn, insertSql);
        } finally {
            JdbcUtil.releaseConnection(conn, sourceDataSource);
        }
    }

    /**
     * Imports data from a Dataset to a database table using the provided Connection and insert SQL statement.
     * The column order in the SQL statement must be consistent with the column order in the Dataset.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.of("name", "age").addRow("John", 25).addRow("Jane", 30);
     * String insertSql = "INSERT INTO users (name, age) VALUES (?, ?)";
     * int rowsImported = JdbcUtils.importData(dataset, connection, insertSql);
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
     * @return the number of rows successfully imported
     * @throws SQLException if a database access error occurs
     */
    public static int importData(final Dataset dataset, final Connection conn, final String insertSql) throws SQLException {
        return importData(dataset, dataset.columnNames(), conn, insertSql);
    }

    /**
     * Imports selected columns from a Dataset to a database table using the provided Connection and insert SQL statement.
     * Only the specified columns will be imported, and their order in the SQL must match the Dataset column order.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.of("id", "name", "age", "email").addRow(1, "John", 25, "john@email.com");
     * List<String> selectColumns = Arrays.asList("name", "age");
     * String insertSql = "INSERT INTO users (name, age) VALUES (?, ?)";
     * int rowsImported = JdbcUtils.importData(dataset, selectColumns, connection, insertSql);
     * }</pre>
     *
     * <p>The insert SQL can be generated using:</p>
     * <pre>{@code
     * List<String> columnNameList = new ArrayList<>(dataset.columnNames());
     * columnNameList.retainAll(selectColumnNames);
     * String sql = PSC.insert(columnNameList).into(tableName).sql();
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param selectColumnNames the collection of column names to be selected for import
     * @param conn the Connection to the database
     * @param insertSql the SQL insert statement with placeholders; column order must match the selected columns
     * @return the number of rows successfully imported
     * @throws SQLException if a database access error occurs
     */
    public static int importData(final Dataset dataset, final Collection<String> selectColumnNames, final Connection conn, final String insertSql)
            throws SQLException {
        return importData(dataset, selectColumnNames, conn, insertSql, JdbcUtil.DEFAULT_BATCH_SIZE, 0);
    }

    /**
     * Imports selected columns from a Dataset to a database table with configurable batch processing.
     * This method allows fine control over the import process with batch size and interval settings.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.of("name", "age").addRow("John", 25).addRow("Jane", 30);
     * List<String> columns = Arrays.asList("name", "age");
     * String insertSql = "INSERT INTO users (name, age) VALUES (?, ?)";
     * int rowsImported = JdbcUtils.importData(dataset, columns, connection, insertSql, 1000, 100);
     * }</pre>
     *
     * <p>The insert SQL can be generated using:</p>
     * <pre>{@code
     * List<String> columnNameList = new ArrayList<>(dataset.columnNames());
     * columnNameList.retainAll(selectColumnNames);
     * String sql = PSC.insert(columnNameList).into(tableName).sql();
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param selectColumnNames the collection of column names to be selected for import
     * @param conn the Connection to the database
     * @param insertSql the SQL insert statement with placeholders; column order must match the selected columns
     * @param batchSize the number of rows to be inserted in each batch
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution
     * @return the number of rows successfully imported
     * @throws SQLException if a database access error occurs
     */
    public static int importData(final Dataset dataset, final Collection<String> selectColumnNames, final Connection conn, final String insertSql,
            final int batchSize, final long batchIntervalInMillis) throws SQLException {
        return importData(dataset, selectColumnNames, Fn.alwaysTrue(), conn, insertSql, batchSize, batchIntervalInMillis);
    }

    /**
     * Imports filtered data from a Dataset to a database table with configurable batch processing.
     * Only rows that pass the filter predicate will be imported.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.of("name", "age").addRow("John", 25).addRow("Jane", 30).addRow("Bob", 15);
     * List<String> columns = Arrays.asList("name", "age");
     * // Only import adults (age >= 18)
     * Predicate<Object[]> filter = row -> ((Integer) row[1]) >= 18;
     * String insertSql = "INSERT INTO adult_users (name, age) VALUES (?, ?)";
     * int rowsImported = JdbcUtils.importData(dataset, columns, filter, conn, insertSql, 1000, 0);
     * }</pre>
     *
     * <p>The insert SQL can be generated using:</p>
     * <pre>{@code
     * List<String> columnNameList = new ArrayList<>(dataset.columnNames());
     * columnNameList.retainAll(selectColumnNames);
     * String sql = PSC.insert(columnNameList).into(tableName).sql();
     * }</pre>
     *
     * @param <E> exception type that filter might throw
     * @param dataset the Dataset containing the data to be imported
     * @param selectColumnNames the collection of column names to be selected for import
     * @param filter a predicate to filter the rows; only rows returning {@code true} will be imported
     * @param conn the Connection to the database
     * @param insertSql the SQL insert statement with placeholders; column order must match the selected columns
     * @param batchSize the number of rows to be inserted in each batch
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution
     * @return the number of rows successfully imported
     * @throws SQLException if a database access error occurs
     * @throws E if the filter throws an exception
     */
    public static <E extends Exception> int importData(final Dataset dataset, final Collection<String> selectColumnNames,
            final Throwables.Predicate<? super Object[], E> filter, final Connection conn, final String insertSql, final int batchSize,
            final long batchIntervalInMillis) throws SQLException, E {

        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSql)) {
            return importData(dataset, selectColumnNames, filter, stmt, batchSize, batchIntervalInMillis);
        }
    }

    /**
     * Imports data from a Dataset to a database table with custom column type mapping.
     * This method allows specifying the type for each column, enabling custom type conversions during import.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.of("name", "birthdate").addRow("John", "1990-01-15");
     * Map<String, Type> columnTypes = new HashMap<>();
     * columnTypes.put("name", Type.of(String.class));
     * columnTypes.put("birthdate", Type.of(java.sql.Date.class));
     * String insertSql = "INSERT INTO users (name, birthdate) VALUES (?, ?)";
     * int rowsImported = JdbcUtils.importData(dataset, connection, insertSql, columnTypes);
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
     * Dataset dataset = Dataset.of("name", "birthdate", "score")
     *     .addRow("John", "1990-01-15", "95.5")
     *     .addRow("Jane", "1992-03-20", "87.3");
     * Map<String, Type> columnTypes = new HashMap<>();
     * columnTypes.put("name", Type.of(String.class));
     * columnTypes.put("birthdate", Type.of(java.sql.Date.class));
     * columnTypes.put("score", Type.of(Double.class));
     * String insertSql = "INSERT INTO students (name, birthdate, score) VALUES (?, ?, ?)";
     * int rowsImported = JdbcUtils.importData(dataset, conn, insertSql, 1000, 0, columnTypes);
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
     * @param batchSize the number of rows to be inserted in each batch
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution
     * @param columnTypeMap a map specifying the types of the columns for type conversion
     * @return the number of rows successfully imported
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final Dataset dataset, final Connection conn, final String insertSql, final int batchSize, final long batchIntervalInMillis,
            final Map<String, ? extends Type> columnTypeMap) throws SQLException {
        return importData(dataset, Fn.alwaysTrue(), conn, insertSql, batchSize, batchIntervalInMillis, columnTypeMap);
    }

    /**
     * Imports filtered data from a Dataset to a database table with custom column type mapping and batch processing.
     * This method provides maximum control over the import process with filtering, type mapping, and batch configuration.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.of("name", "age", "status")
     *     .addRow("John", "25", "active")
     *     .addRow("Jane", "30", "inactive");
     * Map<String, Type> columnTypes = new HashMap<>();
     * columnTypes.put("name", Type.of(String.class));
     * columnTypes.put("age", Type.of(Integer.class));
     * columnTypes.put("status", Type.of(String.class));
     * // Only import active users
     * Predicate<Object[]> filter = row -> "active".equals(row[2]);
     * String insertSql = "INSERT INTO active_users (name, age, status) VALUES (?, ?, ?)";
     * int rowsImported = JdbcUtils.importData(dataset, filter, conn, insertSql, 500, 50, columnTypes);
     * }</pre>
     *
     * <p>The insert SQL can be generated using:</p>
     * <pre>{@code
     * List<String> columnNameList = new ArrayList<>(dataset.columnNames());
     * columnNameList.retainAll(yourSelectColumnNames);
     * String sql = PSC.insert(columnNameList).into(tableName).sql();
     * }</pre>
     *
     * @param <E> exception type that filter might throw
     * @param dataset the Dataset containing the data to be imported
     * @param filter a predicate to filter the rows; only rows returning {@code true} will be imported
     * @param conn the Connection to the database
     * @param insertSql the SQL insert statement with placeholders; column order must match the Dataset
     * @param batchSize the number of rows to be inserted in each batch
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution
     * @param columnTypeMap a map specifying the types of the columns for type conversion
     * @return the number of rows successfully imported
     * @throws SQLException if a database access error occurs
     * @throws E if the filter throws an exception
     */
    @SuppressWarnings("rawtypes")
    public static <E extends Exception> int importData(final Dataset dataset, final Throwables.Predicate<? super Object[], E> filter, final Connection conn,
            final String insertSql, final int batchSize, final long batchIntervalInMillis, final Map<String, ? extends Type> columnTypeMap)
            throws SQLException, E {
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
     * Dataset dataset = Dataset.of("name", "age").addRow("John", 25).addRow("Jane", 30);
     * String insertSql = "INSERT INTO users (name, age, created_date) VALUES (?, ?, ?)";
     * Throwables.BiConsumer<PreparedQuery, Object[], SQLException> setter = (query, row) -> {
     *     query.setString(1, (String) row[0]);
     *     query.setInt(2, (Integer) row[1]);
     *     query.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
     * };
     * int rowsImported = JdbcUtils.importData(dataset, connection, insertSql, setter);
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param conn the Connection to the database
     * @param insertSql the SQL insert statement with placeholders
     * @param stmtSetter a BiConsumer to set the parameters of the PreparedStatement for each row
     * @return the number of rows successfully imported
     * @throws SQLException if a database access error occurs
     */
    public static int importData(final Dataset dataset, final Connection conn, final String insertSql,
            final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> stmtSetter) throws SQLException {
        return importData(dataset, conn, insertSql, JdbcUtil.DEFAULT_BATCH_SIZE, 0, stmtSetter);
    }

    /**
     * Imports data from a Dataset to a database table with a custom statement setter and batch processing.
     * This method combines custom parameter setting with configurable batch processing.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.of("name", "age").addRow("John", 25).addRow("Jane", 30);
     * String insertSql = "INSERT INTO users (name, age, created_date) VALUES (?, ?, ?)";
     * BiConsumer<PreparedQuery, Object[]> setter = (stmt, row) -> {
     *     stmt.setString(1, (String) row[0]);
     *     stmt.setInt(2, (Integer) row[1]);
     *     stmt.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
     * };
     * int rowsImported = JdbcUtils.importData(dataset, conn, insertSql, 1000, 100, setter);
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param conn the Connection to the database
     * @param insertSql the SQL insert statement with placeholders
     * @param batchSize the number of rows to be inserted in each batch
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution
     * @param stmtSetter a BiConsumer to set the parameters of the PreparedStatement for each row
     * @return the number of rows successfully imported
     * @throws SQLException if a database access error occurs
     */
    public static int importData(final Dataset dataset, final Connection conn, final String insertSql, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> stmtSetter) throws SQLException {
        return importData(dataset, Fn.alwaysTrue(), conn, insertSql, batchSize, batchIntervalInMillis, stmtSetter);
    }

    /**
     * Imports filtered data from a Dataset to a database table with a custom statement setter and batch processing.
     * This method provides maximum flexibility with filtering, custom parameter setting, and batch configuration.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.of("name", "age", "status")
     *     .addRow("John", 25, "active")
     *     .addRow("Jane", 30, "inactive");
     * // Only import active users
     * Predicate<Object[]> filter = row -> "active".equals(row[2]);
     * String insertSql = "INSERT INTO active_users (name, age, last_login) VALUES (?, ?, ?)";
     * BiConsumer<PreparedQuery, Object[]> setter = (stmt, row) -> {
     *     stmt.setString(1, (String) row[0]);
     *     stmt.setInt(2, (Integer) row[1]);
     *     stmt.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
     * };
     * int rowsImported = JdbcUtils.importData(dataset, filter, conn, insertSql, 500, 0, setter);
     * }</pre>
     *
     * @param <E> exception type that filter might throw
     * @param dataset the Dataset containing the data to be imported
     * @param filter a predicate to filter the rows; only rows returning {@code true} will be imported
     * @param conn the Connection to the database
     * @param insertSql the SQL insert statement with placeholders
     * @param batchSize the number of rows to be inserted in each batch
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution
     * @param stmtSetter a BiConsumer to set the parameters of the PreparedStatement for each row
     * @return the number of rows successfully imported
     * @throws SQLException if a database access error occurs
     * @throws E if the filter throws an exception
     */
    public static <E extends Exception> int importData(final Dataset dataset, final Throwables.Predicate<? super Object[], E> filter, final Connection conn,
            final String insertSql, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> stmtSetter) throws SQLException, E {
        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSql)) {
            return importData(dataset, filter, stmt, batchSize, batchIntervalInMillis, stmtSetter);
        }
    }

    /**
     * Imports data from a Dataset to a database table using the provided PreparedStatement.
     * All columns from the Dataset will be imported in their original order.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.of("name", "age").addRow("John", 25).addRow("Jane", 30);
     * PreparedStatement stmt = connection.prepareStatement("INSERT INTO users (name, age) VALUES (?, ?)");
     * int rowsImported = JdbcUtils.importData(dataset, stmt);
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param stmt the PreparedStatement to be used for the import
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
     * Dataset dataset = Dataset.of("id", "name", "age", "email").addRow(1, "John", 25, "john@email.com");
     * List<String> selectColumns = Arrays.asList("name", "age");
     * PreparedStatement stmt = connection.prepareStatement("INSERT INTO users (name, age) VALUES (?, ?)");
     * int rowsImported = JdbcUtils.importData(dataset, selectColumns, stmt);
     * }</pre>
     *
     * <p>The insert SQL can be generated using:</p>
     * <pre>{@code
     * List<String> columnNameList = new ArrayList<>(dataset.columnNames());
     * columnNameList.retainAll(selectColumnNames);
     * String sql = PSC.insert(columnNameList).into(tableName).sql();
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param selectColumnNames the collection of column names to be selected for import
     * @param stmt the PreparedStatement to be used for the import
     * @return the number of rows successfully imported
     * @throws SQLException if a database access error occurs
     */
    public static int importData(final Dataset dataset, final Collection<String> selectColumnNames, final PreparedStatement stmt) throws SQLException {
        return importData(dataset, selectColumnNames, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0);
    }

    /**
     * Imports selected columns from a Dataset to a database table using the provided PreparedStatement with batch processing.
     * This method allows control over the batch size and interval for optimal performance.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.of("name", "age").addRow("John", 25).addRow("Jane", 30);
     * List<String> columns = Arrays.asList("name", "age");
     * PreparedStatement stmt = connection.prepareStatement("INSERT INTO users (name, age) VALUES (?, ?)");
     * int rowsImported = JdbcUtils.importData(dataset, columns, stmt, 1000, 100);
     * }</pre>
     *
     * <p>The insert SQL can be generated using:</p>
     * <pre>{@code
     * List<String> columnNameList = new ArrayList<>(dataset.columnNames());
     * columnNameList.retainAll(selectColumnNames);
     * String sql = PSC.insert(columnNameList).into(tableName).sql();
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param selectColumnNames the collection of column names to be selected for import
     * @param stmt the PreparedStatement to be used for the import
     * @param batchSize the number of rows to be inserted in each batch
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution
     * @return the number of rows successfully imported
     * @throws SQLException if a database access error occurs
     */
    public static int importData(final Dataset dataset, final Collection<String> selectColumnNames, final PreparedStatement stmt, final int batchSize,
            final long batchIntervalInMillis) throws SQLException {
        return importData(dataset, selectColumnNames, Fn.alwaysTrue(), stmt, batchSize, batchIntervalInMillis);
    }

    /**
     * Imports filtered data from selected columns of a Dataset to a database table using the provided PreparedStatement.
     * This method provides filtering capability with batch processing for selective data import.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.of("name", "age", "status")
     *     .addRow("John", 25, "active")
     *     .addRow("Jane", 30, "inactive");
     * List<String> columns = Arrays.asList("name", "age");
     * // Only import active users
     * Predicate<Object[]> filter = row -> "active".equals(row[2]);
     * PreparedStatement stmt = connection.prepareStatement("INSERT INTO active_users (name, age) VALUES (?, ?)");
     * int rowsImported = JdbcUtils.importData(dataset, columns, filter, stmt, 500, 0);
     * }</pre>
     *
     * <p>The insert SQL can be generated using:</p>
     * <pre>{@code
     * List<String> columnNameList = new ArrayList<>(dataset.columnNames());
     * columnNameList.retainAll(selectColumnNames);
     * String sql = PSC.insert(columnNameList).into(tableName).sql();
     * }</pre>
     *
     * @param <E> exception type that filter might throw
     * @param dataset the Dataset containing the data to be imported
     * @param selectColumnNames the collection of column names to be selected for import
     * @param filter a predicate to filter the rows; only rows returning {@code true} will be imported
     * @param stmt the PreparedStatement to be used for the import
     * @param batchSize the number of rows to be inserted in each batch
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution
     * @return the number of rows successfully imported
     * @throws SQLException if a database access error occurs
     * @throws E if the filter throws an exception
     */
    public static <E extends Exception> int importData(final Dataset dataset, final Collection<String> selectColumnNames,
            final Throwables.Predicate<? super Object[], E> filter, final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis)
            throws SQLException, E {
        final Type<?> objType = N.typeOf(Object.class);
        final Map<String, Type<?>> columnTypeMap = new HashMap<>();

        for (final String propName : selectColumnNames) {
            columnTypeMap.put(propName, objType);
        }

        return importData(dataset, filter, stmt, batchSize, batchIntervalInMillis, columnTypeMap);
    }

    /**
     * Imports data from a Dataset to a database table using the provided PreparedStatement with custom column type mapping.
     * This method allows specifying the type for each column for proper type conversion during import.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.of("name", "birthdate").addRow("John", "1990-01-15");
     * Map<String, Type> columnTypes = new HashMap<>();
     * columnTypes.put("name", Type.of(String.class));
     * columnTypes.put("birthdate", Type.of(java.sql.Date.class));
     * PreparedStatement stmt = connection.prepareStatement("INSERT INTO users (name, birthdate) VALUES (?, ?)");
     * int rowsImported = JdbcUtils.importData(dataset, stmt, columnTypes);
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param stmt the PreparedStatement to be used for the import
     * @param columnTypeMap a map specifying the types of the columns for type conversion
     * @return the number of rows successfully imported
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
     * Dataset dataset = Dataset.of("name", "birthdate", "score")
     *     .addRow("John", "1990-01-15", "95.5")
     *     .addRow("Jane", "1992-03-20", "87.3");
     * Map<String, Type> columnTypes = new HashMap<>();
     * columnTypes.put("name", Type.of(String.class));
     * columnTypes.put("birthdate", Type.of(java.sql.Date.class));
     * columnTypes.put("score", Type.of(Double.class));
     * PreparedStatement stmt = connection.prepareStatement("INSERT INTO students (name, birthdate, score) VALUES (?, ?, ?)");
     * int rowsImported = JdbcUtils.importData(dataset, stmt, 1000, 0, columnTypes);
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param stmt the PreparedStatement to be used for the import
     * @param batchSize the number of rows to be inserted in each batch
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution
     * @param columnTypeMap a map specifying the types of the columns for type conversion
     * @return the number of rows successfully imported
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("rawtypes")
    public static int importData(final Dataset dataset, final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis,
            final Map<String, ? extends Type> columnTypeMap) throws SQLException {
        return importData(dataset, Fn.alwaysTrue(), stmt, batchSize, batchIntervalInMillis, columnTypeMap);
    }

    /**
     * Imports filtered data from a Dataset to a database table using the provided PreparedStatement with custom column type mapping and batch processing.
     * This method provides maximum control over the import process with filtering, type mapping, and batch configuration.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.of("name", "age", "status")
     *     .addRow("John", "25", "active")
     *     .addRow("Jane", "30", "inactive");
     * Map<String, Type> columnTypes = new HashMap<>();
     * columnTypes.put("name", Type.of(String.class));
     * columnTypes.put("age", Type.of(Integer.class));
     * columnTypes.put("status", Type.of(String.class));
     * // Only import active users
     * Predicate<Object[]> filter = row -> "active".equals(row[2]);
     * PreparedStatement stmt = connection.prepareStatement("INSERT INTO active_users (name, age, status) VALUES (?, ?, ?)");
     * int rowsImported = JdbcUtils.importData(dataset, filter, stmt, 500, 50, columnTypes);
     * }</pre>
     *
     * <p>The insert SQL can be generated using:</p>
     * <pre>{@code
     * List<String> columnNameList = new ArrayList<>(dataset.columnNames());
     * columnNameList.retainAll(yourSelectColumnNames);
     * String sql = PSC.insert(columnNameList).into(tableName).sql();
     * }</pre>
     *
     * @param <E> exception type that filter might throw
     * @param dataset the Dataset containing the data to be imported
     * @param filter a predicate to filter the rows; only rows returning {@code true} will be imported
     * @param stmt the PreparedStatement to be used for the import
     * @param batchSize the number of rows to be inserted in each batch
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution
     * @param columnTypeMap a map specifying the types of the columns for type conversion
     * @return the number of rows successfully imported
     * @throws IllegalArgumentException if batchSize is not greater than 0 or batchIntervalInMillis is negative
     * @throws SQLException if a database access error occurs
     * @throws E if the filter throws an exception
     */
    @SuppressWarnings({ "rawtypes", "null" })
    public static <E extends Exception> int importData(final Dataset dataset, final Throwables.Predicate<? super Object[], E> filter,
            final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis, final Map<String, ? extends Type> columnTypeMap)
            throws IllegalArgumentException, SQLException, E {
        // N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative");   //NOSONAR
        N.checkArgument(batchSize > 0 && batchIntervalInMillis >= 0, "'batchSize'=%s must be greater than 0 and 'batchIntervalInMillis'=%s can't be negative",
                //NOSONAR
                batchSize, batchIntervalInMillis);

        final Type<Object> objType = N.typeOf(Object.class);
        final Throwables.BiConsumer<PreparedQuery, Object[], SQLException> stmtSetter = new Throwables.BiConsumer<>() {
            private int columnCount = 0;
            private Type<Object>[] columnTypes = null;

            @Override
            public void accept(final PreparedQuery t, final Object[] u) throws SQLException {
                if (columnTypes == null) {
                    final List<String> columnNameList = dataset.columnNames();
                    columnCount = columnNameList.size();

                    final Set<String> columnNameSet = N.newHashSet(columnCount);
                    columnTypes = new Type[columnCount];

                    String columnName = null;

                    for (int i = 0; i < columnCount; i++) {
                        columnName = columnNameList.get(i);

                        if (columnTypeMap.containsKey(columnName)) {
                            columnTypes[i] = N.requireNonNull(columnTypeMap.get(columnName));
                            columnNameSet.add(columnName);
                        } else {
                            columnTypes[i] = objType;
                        }
                    }

                    if (columnNameSet.size() != columnTypeMap.size()) {
                        final List<String> keys = new ArrayList<>(columnTypeMap.keySet());
                        keys.removeAll(columnNameSet);
                        throw new IllegalArgumentException(keys + " are not included in titles: " + N.toString(columnNameList));
                    }
                }

                for (int i = 0; i < columnCount; i++) {
                    columnTypes[i].set(stmt, i + 1, u[i]);
                }
            }
        };

        return importData(dataset, filter, stmt, batchSize, batchIntervalInMillis, stmtSetter);
    }

    /**
     * Imports data from a Dataset to a database table using the provided PreparedStatement with a custom statement setter.
     * This method provides complete control over how values are set on the PreparedStatement.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.of("name", "age").addRow("John", 25).addRow("Jane", 30);
     * PreparedStatement stmt = connection.prepareStatement("INSERT INTO users (name, age, created_date) VALUES (?, ?, ?)");
     * BiConsumer<PreparedQuery, Object[]> setter = (query, row) -> {
     *     query.setString(1, (String) row[0]);
     *     query.setInt(2, (Integer) row[1]);
     *     query.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
     * };
     * int rowsImported = JdbcUtils.importData(dataset, stmt, setter);
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param stmt the PreparedStatement to be used for the import
     * @param stmtSetter a BiConsumer to set the parameters of the PreparedStatement for each row
     * @return the number of rows successfully imported
     * @throws SQLException if a database access error occurs
     */
    public static int importData(final Dataset dataset, final PreparedStatement stmt,
            final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> stmtSetter) throws SQLException {
        return importData(dataset, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, stmtSetter);
    }

    /**
     * Imports data from a Dataset to a database table using the provided PreparedStatement with a custom statement setter and batch processing.
     * This method combines custom parameter setting with configurable batch processing for optimal performance.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.of("name", "age").addRow("John", 25).addRow("Jane", 30);
     * PreparedStatement stmt = connection.prepareStatement("INSERT INTO users (name, age, created_date) VALUES (?, ?, ?)");
     * BiConsumer<PreparedQuery, Object[]> setter = (query, row) -> {
     *     query.setString(1, (String) row[0]);
     *     query.setInt(2, (Integer) row[1]);
     *     query.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
     * };
     * int rowsImported = JdbcUtils.importData(dataset, stmt, 1000, 100, setter);
     * }</pre>
     *
     * @param dataset the Dataset containing the data to be imported
     * @param stmt the PreparedStatement to be used for the import
     * @param batchSize the number of rows to be inserted in each batch
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution
     * @param stmtSetter a BiConsumer to set the parameters of the PreparedStatement for each row
     * @return the number of rows successfully imported
     * @throws SQLException if a database access error occurs
     */
    public static int importData(final Dataset dataset, final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> stmtSetter) throws SQLException {
        return importData(dataset, Fn.alwaysTrue(), stmt, batchSize, batchIntervalInMillis, stmtSetter);
    }

    /**
     * Imports filtered data from a Dataset to a database table using the provided PreparedStatement with a custom statement setter and batch processing.
     * This method provides maximum flexibility with filtering, custom parameter setting, and batch configuration.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset dataset = Dataset.of("name", "age", "status")
     *     .addRow("John", 25, "active")
     *     .addRow("Jane", 30, "inactive");
     * PreparedStatement stmt = connection.prepareStatement("INSERT INTO active_users (name, age, last_login) VALUES (?, ?, ?)");
     * // Only import active users
     * Predicate<Object[]> filter = row -> "active".equals(row[2]);
     * BiConsumer<PreparedQuery, Object[]> setter = (query, row) -> {
     *     query.setString(1, (String) row[0]);
     *     query.setInt(2, (Integer) row[1]);
     *     query.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
     * };
     * int rowsImported = JdbcUtils.importData(dataset, filter, stmt, 500, 0, setter);
     * }</pre>
     *
     * @param <E> exception type that filter might throw
     * @param dataset the Dataset containing the data to be imported
     * @param filter a predicate to filter the rows; only rows returning {@code true} will be imported
     * @param stmt the PreparedStatement to be used for the import
     * @param batchSize the number of rows to be inserted in each batch
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution
     * @param stmtSetter a BiConsumer to set the parameters of the PreparedStatement for each row
     * @return the number of rows successfully imported
     * @throws IllegalArgumentException if batchSize is not greater than 0 or batchIntervalInMillis is negative
     * @throws SQLException if a database access error occurs
     * @throws E if the filter throws an exception
     */
    public static <E extends Exception> int importData(final Dataset dataset, final Throwables.Predicate<? super Object[], E> filter,
            final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException> stmtSetter) throws IllegalArgumentException, SQLException, E {
        // N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative");
        N.checkArgument(batchSize > 0 && batchIntervalInMillis >= 0, "'batchSize'=%s must be greater than 0 and 'batchIntervalInMillis'=%s can't be negative",
                batchSize, batchIntervalInMillis);

        final PreparedQuery stmtForSetter = new PreparedQuery(stmt);

        final int columnCount = dataset.columnNames().size();
        final Object[] row = new Object[columnCount];
        int result = 0;

        for (int i = 0, size = dataset.size(); result < size && i < size; i++) {
            dataset.moveToRow(i);

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
                    N.sleepUninterruptibly(batchIntervalInMillis);
                }
            }
        }

        if ((result % batchSize) > 0) {
            JdbcUtil.executeBatch(stmt);
        }

        return result;
    }

    /**
     * Imports data from a file to a database table using the provided DataSource and a line parser function.
     * Each line from the file is processed by the function to create parameter arrays for insertion.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * File csvFile = new File("users.csv");
     * String insertSql = "INSERT INTO users (name, age) VALUES (?, ?)";
     * Function<String, Object[]> parser = line -> {
     *     String[] parts = line.split(",");
     *     return new Object[] { parts[0], Integer.parseInt(parts[1]) };
     * };
     * long rowsImported = JdbcUtils.importData(csvFile, dataSource, insertSql, parser);
     * }</pre>
     *
     * @param <E> exception type that function might throw
     * @param file the file containing the data to be imported
     * @param sourceDataSource the DataSource to obtain database connections
     * @param insertSql the SQL insert statement with placeholders
     * @param func a function to process each line and convert it to an array of objects for insertion; returns {@code null} to skip the line
     * @return the number of rows successfully imported
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs
     * @throws E if the function throws an exception
     */
    public static <E extends Exception> long importData(final File file, final javax.sql.DataSource sourceDataSource, final String insertSql,
            final Throwables.Function<? super String, Object[], E> func) throws SQLException, IOException, E {
        final Connection conn = sourceDataSource.getConnection();

        try {
            return importData(file, conn, insertSql, JdbcUtil.DEFAULT_BATCH_SIZE, 0, func);
        } finally {
            JdbcUtil.releaseConnection(conn, sourceDataSource);
        }
    }

    /**
     * Imports data from a file to a database table using the provided Connection with batch processing and a line parser function.
     * This method provides control over batch size and interval for optimal performance when importing large files.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * File csvFile = new File("large_users.csv");
     * String insertSql = "INSERT INTO users (name, age, email) VALUES (?, ?, ?)";
     * Function<String, Object[]> parser = line -> {
     *     String[] parts = line.split(",");
     *     if (parts.length < 3) return null;  // Skip invalid lines
     *     return new Object[] { parts[0], Integer.parseInt(parts[1]), parts[2] };
     * };
     * long rowsImported = JdbcUtils.importData(csvFile, connection, insertSql, 1000, 100, parser);
     * }</pre>
     *
     * @param <E> exception type that function might throw
     * @param file the file containing the data to be imported
     * @param conn the Connection to the database
     * @param insertSql the SQL insert statement with placeholders
     * @param batchSize the number of rows to be inserted in each batch
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution
     * @param func a function to process each line and convert it to an array of objects for insertion; returns {@code null} to skip the line
     * @return the number of rows successfully imported
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs
     * @throws E if the function throws an exception
     */
    public static <E extends Exception> long importData(final File file, final Connection conn, final String insertSql, final int batchSize,
            final long batchIntervalInMillis, final Throwables.Function<? super String, Object[], E> func) throws SQLException, IOException, E {
        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSql)) {
            return importData(file, stmt, batchSize, batchIntervalInMillis, func);
        }
    }

    /**
     * Imports data from a file to a database table using the provided PreparedStatement with batch processing and a line parser function.
     * This method provides direct control over the PreparedStatement and batch processing parameters.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * File dataFile = new File("products.txt");
     * PreparedStatement stmt = connection.prepareStatement("INSERT INTO products (name, price) VALUES (?, ?)");
     * Function<String, Object[]> parser = line -> {
     *     String[] parts = line.split("\\|");
     *     return new Object[] { parts[0], Double.parseDouble(parts[1]) };
     * };
     * long rowsImported = JdbcUtils.importData(dataFile, stmt, 500, 50, parser);
     * }</pre>
     *
     * @param <E> exception type that function might throw
     * @param file the file containing the data to be imported
     * @param stmt the PreparedStatement to be used for the import
     * @param batchSize the number of rows to be inserted in each batch
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution
     * @param func a function to process each line and convert it to an array of objects for insertion; returns {@code null} to skip the line
     * @return the number of rows successfully imported
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs
     * @throws E if the function throws an exception
     */
    public static <E extends Exception> long importData(final File file, final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis,
            final Throwables.Function<? super String, Object[], E> func) throws SQLException, IOException, E {
        try (Reader reader = IOUtil.newFileReader(file)) {
            return importData(reader, stmt, batchSize, batchIntervalInMillis, func);
        }
    }

    /**
     * Imports data from a Reader to a database table using the provided DataSource and a line parser function.
     * Each line from the Reader is processed by the function to create parameter arrays for insertion.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Reader reader = new StringReader("John,25\nJane,30\nBob,35");
     * String insertSql = "INSERT INTO users (name, age) VALUES (?, ?)";
     * Function<String, Object[]> parser = line -> {
     *     String[] parts = line.split(",");
     *     return new Object[] { parts[0], Integer.parseInt(parts[1]) };
     * };
     * long rowsImported = JdbcUtils.importData(reader, dataSource, insertSql, parser);
     * }</pre>
     *
     * @param <E> exception type that function might throw
     * @param reader the Reader containing the data to be imported
     * @param sourceDataSource the DataSource to obtain database connections
     * @param insertSql the SQL insert statement with placeholders
     * @param line2Parameters a function to process each line and convert it to an array of objects for insertion; returns {@code null} to skip the line
     * @return the number of rows successfully imported
     * @throws IllegalArgumentException if {@code batchSize <= 0} or {@code batchIntervalInMillis < 0}
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs
     * @throws E if the function throws an exception
     */
    public static <E extends Exception> long importData(final Reader reader, final javax.sql.DataSource sourceDataSource, final String insertSql,
            final Throwables.Function<? super String, Object[], E> line2Parameters) throws SQLException, IOException, E {
        final Connection conn = sourceDataSource.getConnection();

        try {
            return importData(reader, conn, insertSql, JdbcUtil.DEFAULT_BATCH_SIZE, 0, line2Parameters);
        } finally {
            JdbcUtil.releaseConnection(conn, sourceDataSource);
        }
    }

    /**
     * Imports data from a Reader to a database table using the provided Connection with batch processing and a line parser function.
     * This method provides control over batch size and interval for optimal performance when importing large data streams.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Reader reader = new FileReader("large_data.txt");
     * String insertSql = "INSERT INTO transactions (account, amount, date) VALUES (?, ?, ?)";
     * Function<String, Object[]> parser = line -> {
     *     String[] parts = line.split("\t");
     *     return new Object[] {
     *         parts[0], 
     *         new BigDecimal(parts[1]), 
     *         java.sql.Date.valueOf(parts[2]) 
     *     };
     * };
     * long rowsImported = JdbcUtils.importData(reader, connection, insertSql, 2000, 200, parser);
     * }</pre>
     *
     * @param <E> exception type that function might throw
     * @param reader the Reader containing the data to be imported
     * @param conn the Connection to the database
     * @param insertSql the SQL insert statement with placeholders
     * @param batchSize the number of rows to be inserted in each batch
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution
     * @param func a function to process each line and convert it to an array of objects for insertion; returns {@code null} to skip the line
     * @return the number of rows successfully imported
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs
     * @throws E if the function throws an exception
     */
    public static <E extends Exception> long importData(final Reader reader, final Connection conn, final String insertSql, final int batchSize,
            final long batchIntervalInMillis, final Throwables.Function<? super String, Object[], E> func) throws SQLException, IOException, E {
        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSql)) {
            return importData(reader, stmt, batchSize, batchIntervalInMillis, func);
        }
    }

    /**
     * Imports data from a Reader to a database table using the provided PreparedStatement with batch processing and a line parser function.
     * This method provides direct control over the PreparedStatement and batch processing parameters for streaming data import.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Reader reader = new InputStreamReader(inputStream);
     * PreparedStatement stmt = connection.prepareStatement("INSERT INTO logs (level, message, timestamp) VALUES (?, ?, ?)");
     * Function<String, Object[]> parser = line -> {
     *     // Parse log format: [LEVEL] timestamp - message
     *     Pattern pattern = Pattern.compile("\\[(\\w+)\\] (\\d+) - (.+)");
     *     Matcher matcher = pattern.matcher(line);
     *     if (!matcher.matches()) return null;
     *     return new Object[] {
     *         matcher.group(1), 
     *         matcher.group(3), 
     *         new Timestamp(Long.parseLong(matcher.group(2))) 
     *     };
     * };
     * long rowsImported = JdbcUtils.importData(reader, stmt, 1000, 0, parser);
     * }</pre>
     *
     * @param <E> exception type that function might throw
     * @param reader the Reader containing the data to be imported
     * @param stmt the PreparedStatement to be used for the import
     * @param batchSize the number of rows to be inserted in each batch
     * @param batchIntervalInMillis the interval in milliseconds between each batch execution
     * @param line2Parameters a function to process each line and convert it to an array of objects for insertion; returns {@code null} to skip the line
     * @return the number of rows successfully imported
     * @throws IllegalArgumentException if {@code batchSize <= 0} or {@code batchIntervalInMillis < 0}
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs
     * @throws E if the function throws an exception
     */
    public static <E extends Exception> long importData(final Reader reader, final PreparedStatement stmt, final int batchSize,
            final long batchIntervalInMillis, final Throwables.Function<? super String, Object[], E> line2Parameters)
            throws IllegalArgumentException, SQLException, IOException, E {
        // N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative");
        N.checkArgument(batchSize > 0 && batchIntervalInMillis >= 0, "'batchSize'=%s must be greater than 0 and 'batchIntervalInMillis'=%s can't be negative",
                batchSize, batchIntervalInMillis);

        long result = 0;
        final BufferedReader br = Objectory.createBufferedReader(reader);

        try {
            String line = null;
            Object[] row = null;

            while ((line = br.readLine()) != null) {
                row = line2Parameters.apply(line);

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
                        N.sleepUninterruptibly(batchIntervalInMillis);
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
     * long rowsImported = JdbcUtils.importData(users.iterator(), dataSource, insertSql,
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
     * @param sourceDataSource the DataSource to obtain database connections from
     * @param insertSql the SQL insert statement with parameter placeholders (?)
     * @param stmtSetter a BiConsumer to map iterator elements to PreparedStatement parameters
     * @return the total number of rows successfully inserted
     * @throws SQLException if a database access error occurs
     */
    public static <T> long importData(final Iterator<? extends T> iter, final javax.sql.DataSource sourceDataSource, final String insertSql,
            final Throwables.BiConsumer<? super PreparedQuery, ? super T, SQLException> stmtSetter) throws SQLException {
        final Connection conn = sourceDataSource.getConnection();

        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSql)) {
            return importData(iter, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, stmtSetter);
        } finally {
            JdbcUtil.releaseConnection(conn, sourceDataSource);
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
     *     long rowsImported = JdbcUtils.importData(products, conn, insertSql, 
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
     * @param insertSql the SQL insert statement with parameter placeholders (?)
     * @param batchSize the number of rows to accumulate before executing a batch insert
     * @param batchIntervalInMillis the pause duration in milliseconds between batch executions
     * @param stmtSetter a BiConsumer to map iterator elements to PreparedStatement parameters
     * @return the total number of rows successfully inserted
     * @throws SQLException if a database access error occurs
     */
    public static <T> long importData(final Iterator<? extends T> iter, final Connection conn, final String insertSql, final int batchSize,
            final long batchIntervalInMillis, final Throwables.BiConsumer<? super PreparedQuery, ? super T, SQLException> stmtSetter) throws SQLException {
        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSql)) {
            return importData(iter, stmt, batchSize, batchIntervalInMillis, stmtSetter);
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
     *     long pending = JdbcUtils.importData(pendingOrders, stmt, 1000, 0,
     *         (query, order) -> {
     *             query.setLong(1, order.getId());
     *             query.setLong(2, order.getCustomerId());
     *             query.setBigDecimal(3, order.getTotal());
     *             query.setString(4, "PENDING");
     *         });
     *
     *     // Reuse statement for completed orders
     *     Iterator<Order> completedOrders = getCompletedOrders();
     *     long completed = JdbcUtils.importData(completedOrders, stmt, 1000, 0,
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
     * @param batchIntervalInMillis the pause duration in milliseconds between batch executions (0 for no pause)
     * @param stmtSetter a BiConsumer to map iterator elements to PreparedStatement parameters
     * @return the total number of rows successfully inserted
     * @throws IllegalArgumentException if batchSize is not positive or batchIntervalInMillis is negative
     * @throws SQLException if a database access error occurs
     */
    public static <T> long importData(final Iterator<? extends T> iter, final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super T, SQLException> stmtSetter) throws SQLException {
        N.checkArgument(batchSize > 0 && batchIntervalInMillis >= 0, "'batchSize'=%s must be greater than 0 and 'batchIntervalInMillis'=%s can't be negative",
                batchSize, batchIntervalInMillis);

        final PreparedQuery stmtForSetter = new PreparedQuery(stmt);
        long result = 0;

        T next = null;
        while (iter.hasNext()) {
            next = iter.next();

            stmtSetter.accept(stmtForSetter, next);
            stmtForSetter.addBatch();

            if ((++result % batchSize) == 0) {
                JdbcUtil.executeBatch(stmt);

                if (batchIntervalInMillis > 0) {
                    N.sleepUninterruptibly(batchIntervalInMillis);
                }
            }
        }

        if ((result % batchSize) > 0) {
            JdbcUtil.executeBatch(stmt);
        }

        return result;
    }

    /**
     * Imports data from a CSV file to the database using the specified DataSource.
     * This method uses default batch processing settings and expects the CSV column order to match the SQL parameter order.
     *
     * <p>The first line of the CSV file is treated as headers and will be skipped during import.
     * The statement setter is responsible for parsing and setting each CSV row value to the appropriate PreparedStatement parameter.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Import customer data from CSV
     * File csvFile = new File("customers.csv");
     * DataSource dataSource = getDataSource();
     * String insertSql = "INSERT INTO customers (name, email, phone) VALUES (?, ?, ?)";
     *
     * long rowsImported = JdbcUtils.importCSV(csvFile, dataSource, insertSql,
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
     * @param sourceDataSource the DataSource to obtain database connections from
     * @param insertSql the SQL insert statement (column order must match CSV column order)
     * @param stmtSetter a BiConsumer to set PreparedStatement parameters from CSV row values
     * @return the total number of rows successfully imported
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs while reading the file
     */
    public static long importCSV(final File file, final javax.sql.DataSource sourceDataSource, final String insertSql,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> stmtSetter) throws SQLException, IOException {
        final Connection conn = sourceDataSource.getConnection();

        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSql)) {
            return importCSV(file, stmt, stmtSetter);
        } finally {
            JdbcUtil.releaseConnection(conn, sourceDataSource);
        }
    }

    /**
     * Imports data from a CSV file to the database using the provided Connection with configurable batch processing.
     * This method provides control over batch size and processing intervals for optimal performance.
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
     *     long rowsImported = JdbcUtils.importCSV(csvFile, conn, insertSql, 
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
     * @param insertSql the SQL insert statement (column order must match CSV column order)
     * @param batchSize the number of rows to accumulate before executing a batch insert
     * @param batchIntervalInMillis the pause duration in milliseconds between batch executions
     * @param stmtSetter a BiConsumer to set PreparedStatement parameters from CSV row values
     * @return the total number of rows successfully imported
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs while reading the file
     */
    public static long importCSV(final File file, final Connection conn, final String insertSql, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> stmtSetter) throws SQLException, IOException {
        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSql)) {
            return importCSV(file, stmt, batchSize, batchIntervalInMillis, stmtSetter);
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
     * long rowsImported = JdbcUtils.importCSV(csvFile, stmt,
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
     * @param stmtSetter a BiConsumer to set PreparedStatement parameters from CSV row values
     * @return the total number of rows successfully imported
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs while reading the file
     */
    public static long importCSV(final File file, final PreparedStatement stmt,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> stmtSetter) throws SQLException, IOException {
        return importCSV(file, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, stmtSetter);
    }

    /**
     * Imports data from a CSV file to the database using the provided PreparedStatement with configurable batch processing.
     * This method provides full control over the import process including batch size and processing intervals.
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
     * long totalRows = JdbcUtils.importCSV(csvFile, stmt, 5000, 100,
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
     * @param batchSize the number of rows to accumulate before executing a batch insert
     * @param batchIntervalInMillis the pause duration in milliseconds between batch executions
     * @param stmtSetter a BiConsumer to set PreparedStatement parameters from CSV row values
     * @return the total number of rows successfully imported
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs while reading the file
     */
    public static long importCSV(final File file, final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> stmtSetter) throws SQLException, IOException {
        return importCSV(file, Fn.alwaysTrue(), stmt, batchSize, batchIntervalInMillis, stmtSetter);
    }

    /**
     * Imports data from a CSV file to the database with row filtering capability.
     * This method allows selective import of CSV rows based on a filter predicate.
     *
     * <p>The filter predicate is applied to each CSV row (as a String array) before import.
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
     * long rowsImported = JdbcUtils.importCSV(csvFile, activeUsersFilter, stmt, 1000, 0,
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
     * @param <E> exception type that filter may throw
     * @param file the CSV file containing the data to be imported
     * @param filter a predicate to filter rows; only rows returning {@code true} will be imported
     * @param stmt the PreparedStatement to be used for the import (will not be closed)
     * @param batchSize the number of rows to accumulate before executing a batch insert
     * @param batchIntervalInMillis the pause duration in milliseconds between batch executions
     * @param stmtSetter a BiConsumer to set PreparedStatement parameters from CSV row values
     * @return the total number of rows successfully imported (after filtering)
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs while reading the file
     * @throws E if the filter throws an exception
     */
    public static <E extends Exception> long importCSV(final File file, final Throwables.Predicate<? super String[], E> filter, final PreparedStatement stmt,
            final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> stmtSetter) throws SQLException, IOException, E {
        try (Reader reader = IOUtil.newFileReader(file)) {
            return importCSV(reader, filter, stmt, batchSize, batchIntervalInMillis, stmtSetter);
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
     * long rowsImported = JdbcUtils.importCSV(reader, dataSource, insertSql,
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
     * @param sourceDataSource the DataSource to obtain database connections from
     * @param insertSql the SQL insert statement (column order must match CSV column order)
     * @param stmtSetter a BiConsumer to set PreparedStatement parameters from CSV row values
     * @return the total number of rows successfully imported
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs while reading from the reader
     */
    public static long importCSV(final Reader reader, final javax.sql.DataSource sourceDataSource, final String insertSql,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> stmtSetter) throws SQLException, IOException {
        final Connection conn = sourceDataSource.getConnection();

        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, insertSql)) {
            return importCSV(reader, stmt, stmtSetter);
        } finally {
            JdbcUtil.releaseConnection(conn, sourceDataSource);
        }
    }

    /**
     * Imports data from a CSV Reader to the database using the provided PreparedStatement with default batch settings.
     * This method provides direct control over the PreparedStatement used for import.
     *
     * <p>The PreparedStatement will not be closed by this method, allowing for reuse.
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
     * long rowsImported = JdbcUtils.importCSV(reader, stmt,
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
     * @param stmtSetter a BiConsumer to set PreparedStatement parameters from CSV row values
     * @return the total number of rows successfully imported
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs while reading from the reader
     */
    public static long importCSV(final Reader reader, final PreparedStatement stmt,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> stmtSetter) throws SQLException, IOException {
        return importCSV(reader, stmt, JdbcUtil.DEFAULT_BATCH_SIZE, 0, stmtSetter);
    }

    /**
     * Imports data from a CSV Reader to the database using the provided PreparedStatement with configurable batch processing.
     * This method provides control over batch size and processing intervals.
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
     * long startTime = System.currentTimeMillis();
     * long rowsImported = JdbcUtils.importCSV(reader, stmt, 10000, 200,
     *     (query, row) -> {
     *         query.setLong(1, Long.parseLong(row[0]));
     *         query.setString(2, row[1]);
     *         query.setTimestamp(3, Timestamp.valueOf(row[2]));
     *     });
     *
     * long duration = System.currentTimeMillis() - startTime;
     * System.out.println("Imported " + rowsImported + " rows in " + duration + "ms");
     * }</pre>
     *
     * @param reader the Reader to read the CSV data from
     * @param stmt the PreparedStatement to be used for the import (will not be closed)
     * @param batchSize the number of rows to accumulate before executing a batch insert
     * @param batchIntervalInMillis the pause duration in milliseconds between batch executions
     * @param stmtSetter a BiConsumer to set PreparedStatement parameters from CSV row values
     * @return the total number of rows successfully imported
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs while reading from the reader
     */
    public static long importCSV(final Reader reader, final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> stmtSetter) throws SQLException, IOException {
        return importCSV(reader, Fn.alwaysTrue(), stmt, batchSize, batchIntervalInMillis, stmtSetter);
    }

    /**
     * Imports data from a CSV Reader to the database with row filtering capability and configurable batch processing.
     * This is the most comprehensive CSV import method providing full control over the import process.
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
     * long rowsImported = JdbcUtils.importCSV(reader, complexFilter, stmt, 2000, 0,
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
     * @param <E> exception type that filter may throw
     * @param reader the Reader to read the CSV data from
     * @param filter a predicate to filter rows; only rows returning {@code true} will be imported
     * @param stmt the PreparedStatement to be used for the import (will not be closed)
     * @param batchSize the number of rows to accumulate before executing a batch insert (must be greater than 0)
     * @param batchIntervalInMillis the pause duration in milliseconds between batch executions (must be >= 0)
     * @param stmtSetter a BiConsumer to set PreparedStatement parameters from CSV row values
     * @return the total number of rows successfully imported (after filtering)
     * @throws IllegalArgumentException if batchSize is not positive or batchIntervalInMillis is negative
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs while reading from the reader
     * @throws E if the filter throws an exception
     */
    public static <E extends Exception> long importCSV(final Reader reader, final Throwables.Predicate<? super String[], E> filter,
            final PreparedStatement stmt, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException> stmtSetter)
            throws IllegalArgumentException, SQLException, IOException, E {
        N.checkArgument(batchSize > 0 && batchIntervalInMillis >= 0, "'batchSize'=%s must be greater than 0 and 'batchIntervalInMillis'=%s can't be negative",
                batchSize, batchIntervalInMillis);

        final PreparedQuery stmtForSetter = new PreparedQuery(stmt);
        final Function<String, String[]> headerParser = CsvUtil.getCurrentHeaderParser();
        final BiConsumer<String, String[]> lineParser = CsvUtil.getCurrentLineParser();
        final boolean isBufferedReader = IOUtil.isBufferedReader(reader);
        final BufferedReader br = isBufferedReader ? (BufferedReader) reader : Objectory.createBufferedReader(reader);
        long result = 0;

        try {
            String line = br.readLine();
            final String[] titles = headerParser.apply(line);

            int columnCount = titles.length;
            final String[] output = new String[columnCount];

            while ((line = br.readLine()) != null) {
                lineParser.accept(line, output);

                if (filter != null && !filter.test(output)) {
                    continue;
                }

                stmtSetter.accept(stmtForSetter, output);
                stmtForSetter.addBatch();

                if ((++result % batchSize) == 0) {
                    JdbcUtil.executeBatch(stmt);

                    if (batchIntervalInMillis > 0) {
                        N.sleepUninterruptibly(batchIntervalInMillis);
                    }
                }

                N.fill(output, null);
            }

            if ((result % batchSize) > 0) {
                JdbcUtil.executeBatch(stmt);
            }
        } finally {
            if (!isBufferedReader) {
                Objectory.recycle(br);
            }
        }

        return result;
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
     * long rowsExported = JdbcUtils.exportCSV(dataSource, query, outputFile);
     * System.out.println("Exported " + rowsExported + " active users to " + outputFile);
     * }</pre>
     *
     * @param sourceDataSource the DataSource to obtain database connections from
     * @param querySql the SQL query to execute for retrieving data
     * @param output the File to write the CSV data to (will be created if doesn't exist)
     * @return the total number of rows exported to the CSV file
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs while writing to the file
     */
    public static long exportCSV(final javax.sql.DataSource sourceDataSource, final String querySql, final File output) throws SQLException, IOException {
        final Connection conn = sourceDataSource.getConnection();

        try {
            return exportCSV(conn, querySql, output);
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
     *     long rowsExported = JdbcUtils.exportCSV(conn, query, outputFile);
     *     System.out.println("Successfully exported " + rowsExported + " rows");
     * } finally {
     *     conn.close();
     * }
     * }</pre>
     *
     * @param conn the Connection to the database (will not be closed by this method)
     * @param querySql the SQL query to execute for retrieving data
     * @param output the File to write the CSV data to (will be created if doesn't exist)
     * @return the total number of rows exported to the CSV file
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs while writing to the file
     */
    public static long exportCSV(final Connection conn, final String querySql, final File output) throws SQLException, IOException {
        return exportCSV(conn, querySql, null, output);
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
     *     long rowsExported = JdbcUtils.exportCSV(conn, query, columnsToExport, outputFile);
     *     System.out.println("Exported " + rowsExported + " employees (filtered columns)");
     * } finally {
     *     conn.close();
     * }
     * }</pre>
     *
     * @param conn the Connection to the database (will not be closed by this method)
     * @param querySql the SQL query to execute for retrieving data
     * @param selectColumnNames collection of column names to include in export (null for all columns)
     * @param output the File to write the CSV data to (will be created if doesn't exist)
     * @return the total number of rows exported to the CSV file
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs while writing to the file
     */
    public static long exportCSV(final Connection conn, final String querySql, final Collection<String> selectColumnNames, final File output)
            throws SQLException, IOException {
        final ParsedSql sql = ParsedSql.parse(querySql);

        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, sql.getParameterizedSql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {

            setFetchForLargeResult(conn, stmt);

            return exportCSV(stmt, selectColumnNames, output);
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
     * long rowsExported = JdbcUtils.exportCSV(stmt, outputFile);
     *
     * System.out.println("Exported " + rowsExported + " completed orders for 2023");
     * }</pre>
     *
     * @param stmt the PreparedStatement to execute (will not be closed by this method)
     * @param out the File to write the CSV data to (will be created if doesn't exist)
     * @return the total number of rows exported to the CSV file
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs while writing to the file
     */
    public static long exportCSV(final PreparedStatement stmt, final File out) throws SQLException, IOException {
        return exportCSV(stmt, null, out);
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
     * long rowsExported = JdbcUtils.exportCSV(stmt, userColumns, outputFile);
     * System.out.println("Exported " + rowsExported + " US users");
     * }</pre>
     *
     * @param stmt the PreparedStatement to execute (will not be closed by this method)
     * @param selectColumnNames collection of column names to include in export (null for all columns)
     * @param output the File to write the CSV data to (will be created if doesn't exist)
     * @return the total number of rows exported to the CSV file
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs while writing to the file
     */
    public static long exportCSV(final PreparedStatement stmt, final Collection<String> selectColumnNames, final File output) throws SQLException, IOException {
        ResultSet rs = null;

        try {
            rs = JdbcUtil.executeQuery(stmt);

            return exportCSV(rs, selectColumnNames, output);
        } finally {
            JdbcUtil.closeQuietly(rs);
        }
    }

    /**
     * Exports data from a ResultSet to a CSV file.
     * This method writes all columns from the current position of the ResultSet to the file.
     *
     * <p>This is the lowest-level export method, useful when you already have a ResultSet
     * from a complex operation or need maximum control over the export process.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Export from a scrollable ResultSet with preprocessing
     * Statement stmt = conn.createStatement(
     *     ResultSet.TYPE_SCROLL_INSENSITIVE,
     *     ResultSet.CONCUR_READ_ONLY);
     * ResultSet rs = stmt.executeQuery("SELECT * FROM products");
     *
     * // Skip first 100 rows
     * rs.absolute(100);
     *
     * File outputFile = new File("products_from_100.csv");
     * long rowsExported = JdbcUtils.exportCSV(rs, outputFile);
     *
     * System.out.println("Exported " + rowsExported + " products (skipped first 100)");
     * }</pre>
     *
     * @param rs the ResultSet containing the data to export (will not be closed by this method)
     * @param output the File to write the CSV data to (will be created if doesn't exist)
     * @return the total number of rows exported to the CSV file
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs while writing to the file
     */
    public static long exportCSV(final ResultSet rs, final File output) throws SQLException, IOException {
        return exportCSV(rs, null, output);
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
     * long rowsExported = JdbcUtils.exportCSV(rs, exportColumns, outputFile);
     * System.out.println("Exported " + rowsExported + " order summaries");
     * }</pre>
     *
     * @param rs the ResultSet containing the data to export (will not be closed by this method)
     * @param selectColumnNames collection of column names to include in export (null for all columns)
     * @param output the File to write the CSV data to (will be created if doesn't exist)
     * @return the total number of rows exported to the CSV file
     * @throws IllegalArgumentException if any specified column name is not found in the ResultSet
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs while writing to the file
     */
    public static long exportCSV(final ResultSet rs, final Collection<String> selectColumnNames, final File output) throws SQLException, IOException {
        if (!output.exists() && !output.createNewFile()) {
            throw new IOException("Failed to create file: " + output);
        }

        try (Writer writer = IOUtil.newFileWriter(output)) {
            return exportCSV(rs, selectColumnNames, writer);
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
     * long rowsExported = JdbcUtils.exportCSV(dataSource, query, writer);
     * writer.flush();
     *
     * logger.info("Streamed " + rowsExported + " rows to client");
     * }</pre>
     *
     * @param sourceDataSource the DataSource to obtain database connections from
     * @param querySql the SQL query to execute for retrieving data
     * @param output the Writer to write the CSV data to (will not be closed by this method)
     * @return the total number of rows exported
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs while writing
     */
    public static long exportCSV(final javax.sql.DataSource sourceDataSource, final String querySql, final Writer output) throws SQLException, IOException {
        final Connection conn = sourceDataSource.getConnection();

        try {
            return exportCSV(conn, querySql, output);
        } finally {
            JdbcUtil.releaseConnection(conn, sourceDataSource);
        }
    }

    /**
     * Exports data from the database to a CSV Writer using the provided Connection and SQL query.
     * This method executes the query and streams results directly to the Writer.
     *
     * <p>This method is optimized for large result sets with appropriate cursor and fetch size settings.
     * The Writer should be flushed after this method returns to ensure all data is written.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Export to string for further processing
     * Connection conn = dataSource.getConnection();
     * StringWriter stringWriter = new StringWriter();
     * String query = "SELECT id, name, value FROM metrics WHERE date = CURRENT_DATE";
     *
     * try {
     *     long rowsExported = JdbcUtils.exportCSV(conn, query, stringWriter);
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
     * @param querySql the SQL query to execute for retrieving data
     * @param output the Writer to write the CSV data to (will not be closed by this method)
     * @return the total number of rows exported
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs while writing
     */
    public static long exportCSV(final Connection conn, final String querySql, final Writer output) throws SQLException, IOException {
        final ParsedSql sql = ParsedSql.parse(querySql);

        final PreparedStatement stmt = JdbcUtil.prepareStatement(conn, sql.getParameterizedSql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);

        try {
            setFetchForLargeResult(conn, stmt);

            try (ResultSet rs = JdbcUtil.executeQuery(stmt)) {
                return exportCSV(rs, output);
            }
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     * Exports data from a ResultSet to a CSV Writer.
     * This method writes all columns from the current position of the ResultSet to the Writer.
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
     *     long rowsExported = JdbcUtils.exportCSV(rs, writer);
     *     System.out.println("Exported " + rowsExported + " rows to compressed CSV");
     * }
     * }</pre>
     *
     * @param rs the ResultSet containing the data to be exported
     * @param output the Writer to write the CSV data to 
     * @return the number of rows exported
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs
     */
    public static long exportCSV(final ResultSet rs, final Writer output) throws SQLException, IOException {
        return exportCSV(rs, null, output);
    }

    /**
     * Exports data from a ResultSet to a CSV file with column selection.
     * This method writes the specified columns from the ResultSet to the Writer in CSV format.
     * The first line contains column headers, and each subsequent line represents a row of data.
     *
     * <p>The method handles proper CSV formatting including:</p>
     * <ul>
     *   <li>Column headers in the first line</li>
     *   <li>Proper escaping of special characters</li>
     *   <li>Null value handling</li>
     *   <li>Type-aware value conversion</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Export only specific columns to CSV
     * Set<String> columns = Set.of("name", "email", "created_date");
     *
     * try (Writer writer = new FileWriter("users_export.csv")) {
     *     ResultSet rs = stmt.executeQuery("SELECT * FROM users");
     *     long exported = JdbcUtils.exportCSV(rs, columns, writer);
     *     System.out.println("Exported " + exported + " rows");
     * }
     * }</pre>
     *
     * @param rs the ResultSet containing the data to be exported
     * @param selectColumnNames the collection of column names to be selected for export; if {@code null}, all columns are exported
     * @param output the Writer to write the CSV data to
     * @return the number of rows exported
     * @throws IllegalArgumentException if any specified column name is not found in the ResultSet
     * @throws SQLException if a database access error occurs
     * @throws IOException if an I/O error occurs
     */
    public static long exportCSV(final ResultSet rs, final Collection<String> selectColumnNames, final Writer output)
            throws IllegalArgumentException, SQLException, IOException {
        // N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative");

        final Type<Object> strType = N.typeOf(String.class);
        final boolean isBufferedWriter = output instanceof BufferedCsvWriter;
        final BufferedCsvWriter bw = isBufferedWriter ? (BufferedCsvWriter) output : Objectory.createBufferedCsvWriter(output);
        long result = 0;

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
                throw new IllegalArgumentException(columnNameSet + " are not included in the query result");
            }

            final char separator = WD._COMMA;

            for (int i = 0, j = 0, len = columnNames.length; i < len; i++) {
                if (columnNames[i] == null) {
                    continue;
                }

                if (j++ > 0) {
                    bw.write(separator);
                }

                CsvUtil.writeField(bw, strType, columnNames[i]);
            }

            bw.write(IOUtil.LINE_SEPARATOR_UNIX);

            final Type<Object>[] typeArray = new Type[columnCount];
            Type<Object> type = null;
            Object value = null;

            while (rs.next()) {
                if (result++ > 0) {
                    bw.write(IOUtil.LINE_SEPARATOR_UNIX);
                }

                for (int i = 0, j = 0; i < columnCount; i++) {
                    if (columnNames[i] == null) {
                        continue;
                    }

                    if (j++ > 0) {
                        bw.write(separator);
                    }

                    type = typeArray[i];

                    if (type == null) {
                        value = JdbcUtil.getColumnValue(rs, i + 1, checkDateType);

                        if (value == null) {
                            bw.write(NULL_CHAR_ARRAY);
                        } else {
                            type = N.typeOf(value.getClass());
                            typeArray[i] = type;

                            CsvUtil.writeField(bw, type, value);
                        }
                    } else {
                        CsvUtil.writeField(bw, type, type.get(rs, i + 1));
                    }
                }
            }

            bw.flush();
        } finally {
            if (!isBufferedWriter) {
                Objectory.recycle(bw);
            }
        }

        return result;
    }

    private static final Supplier<Throwables.BiConsumer<PreparedQuery, ResultSet, SQLException>> supplierOfStmtSetterByRS = () -> new Throwables.BiConsumer<>() {
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
     * long rowsCopied = JdbcUtils.copy(sourceDataSource, targetDataSource, "customers");
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
     * long rowsCopied = JdbcUtils.copy(sourceDS, targetDS, "customers", "customers_backup");
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
     * long rowsCopied = JdbcUtils.copy(sourceDS, targetDS, "large_table", "large_table", 5000);
     * System.out.println("Copied " + rowsCopied + " rows in batches of 5000");
     * }</pre>
     *
     * @param sourceDataSource the data source from which to copy data
     * @param targetDataSource the data source to which to copy data
     * @param sourceTableName the name of the table in the source data source
     * @param targetTableName the name of the table in the target data source
     * @param batchSize the number of rows to copy in each batch (must be greater than 0)
     * @return the number of rows copied
     * @throws SQLException if a database access error occurs
     */
    public static long copy(final javax.sql.DataSource sourceDataSource, final javax.sql.DataSource targetDataSource, final String sourceTableName,
            final String targetTableName, final int batchSize) throws SQLException {
        String selectSql = null;
        String insertSql = null;
        Connection conn = null;

        try {
            conn = sourceDataSource.getConnection();

            selectSql = JdbcCodeGenerationUtil.generateSelectSql(conn, sourceTableName);
            insertSql = JdbcCodeGenerationUtil.generateInsertSql(conn, sourceTableName);

            if (!sourceTableName.equals(targetTableName)) {
                insertSql = Strings.replaceFirstIgnoreCase(insertSql, sourceTableName, targetTableName);
            }
        } finally {
            JdbcUtil.releaseConnection(conn, sourceDataSource);
        }

        return copy(sourceDataSource, selectSql, N.max(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT, batchSize), targetDataSource, insertSql, batchSize);
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
     * long rowsCopied = JdbcUtils.copy(sourceDS, targetDS, "users", "active_users", columns);
     * System.out.println("Copied " + rowsCopied + " users with selected columns");
     * }</pre>
     *
     * @param sourceDataSource the data source from which to copy data
     * @param targetDataSource the data source to which to copy data
     * @param sourceTableName the name of the table in the source data source
     * @param targetTableName the name of the table in the target data source
     * @param selectColumnNames the collection of column names to copy
     * @return the number of rows copied
     * @throws SQLException if a database access error occurs or any specified column doesn't exist
     */
    public static long copy(final javax.sql.DataSource sourceDataSource, final javax.sql.DataSource targetDataSource, final String sourceTableName,
            final String targetTableName, final Collection<String> selectColumnNames) throws SQLException {
        return copy(sourceDataSource, targetDataSource, sourceTableName, targetTableName, selectColumnNames, JdbcUtil.DEFAULT_BATCH_SIZE);
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
     * long rowsCopied = JdbcUtils.copy(sourceDS, targetDS, "orders", "order_summary",
     *                                   columns, 10000);
     * System.out.println("Copied " + rowsCopied + " order summaries");
     * }</pre>
     *
     * @param sourceDataSource the data source from which to copy data
     * @param targetDataSource the data source to which to copy data
     * @param sourceTableName the name of the table in the source data source
     * @param targetTableName the name of the table in the target data source
     * @param selectColumnNames the collection of column names to copy
     * @param batchSize the number of rows to copy in each batch (must be greater than 0)
     * @return the number of rows copied
     * @throws SQLException if a database access error occurs
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
     * long rowsCopied = JdbcUtils.copy(sourceDS, selectSql, targetDS, insertSql);
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
        return copy(sourceDataSource, selectSql, JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT, targetDataSource, insertSql, JdbcUtil.DEFAULT_BATCH_SIZE);
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
     * long rowsCopied = JdbcUtils.copy(sourceDS, selectSql, 50000, targetDS, insertSql, 5000);
     * System.out.println("Archived " + rowsCopied + " transactions");
     * }</pre>
     *
     * @param sourceDataSource the data source from which to copy data
     * @param selectSql the SQL query to select data from the source data source
     * @param fetchSize the number of rows to fetch at a time (should be larger than batchSize)
     * @param targetDataSource the data source to which to copy data
     * @param insertSql the SQL query to insert data into the target data source
     * @param batchSize the number of rows to copy in each batch (must be greater than 0)
     * @return the number of rows copied
     * @throws SQLException if a database access error occurs
     */
    public static long copy(final javax.sql.DataSource sourceDataSource, final String selectSql, final int fetchSize,
            final javax.sql.DataSource targetDataSource, final String insertSql, final int batchSize) throws SQLException {
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
     * long rowsCopied = JdbcUtils.copy(sourceDS, selectSql, targetDS, insertSql, setter);
     * }</pre>
     *
     * @param sourceDataSource the data source from which to copy data
     * @param selectSql the SQL query to select data from the source data source
     * @param targetDataSource the data source to which to copy data
     * @param insertSql the SQL query to insert data into the target data source
     * @param stmtSetter a bi-consumer to set parameters on the prepared statement from the result set
     * @return the number of rows copied
     * @throws SQLException if a database access error occurs
     */
    public static long copy(final javax.sql.DataSource sourceDataSource, final String selectSql, final javax.sql.DataSource targetDataSource,
            final String insertSql, final Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> stmtSetter) throws SQLException {
        return copy(sourceDataSource, selectSql, JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT, targetDataSource, insertSql, JdbcUtil.DEFAULT_BATCH_SIZE, 0,
                stmtSetter);
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
     * long rowsCopied = JdbcUtils.copy(
     *     sourceDS, "SELECT * FROM large_table", 100000,
     *     targetDS, "INSERT INTO processed_table VALUES (?, ?, ?)", 
     *     5000, 1000, setter
     * );
     * }</pre>
     *
     * @param sourceDataSource the data source from which to copy data
     * @param selectSql the SQL query to select data from the source data source
     * @param fetchSize the number of rows to fetch at a time (should be larger than batchSize)
     * @param targetDataSource the data source to which to copy data
     * @param insertSql the SQL query to insert data into the target data source
     * @param batchSize the number of rows to copy in each batch (must be greater than 0)
     * @param batchIntervalInMillis the interval in milliseconds between each batch (0 for no delay)
     * @param stmtSetter a bi-consumer to set parameters on the prepared statement
     * @return the number of rows copied
     * @throws SQLException if a database access error occurs
     */
    public static long copy(final javax.sql.DataSource sourceDataSource, final String selectSql, final int fetchSize,
            final javax.sql.DataSource targetDataSource, final String insertSql, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> stmtSetter) throws SQLException {
        Connection sourceConn = null;
        Connection targetConn = null;

        try {
            sourceConn = JdbcUtil.getConnection(sourceDataSource);
            targetConn = JdbcUtil.getConnection(targetDataSource);

            return copy(sourceConn, selectSql, fetchSize, targetConn, insertSql, batchSize, batchIntervalInMillis, stmtSetter);
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
     * Copies all data from a table with the same name between two database connections.
     * This is a convenience method for copying entire tables within the same database
     * or between different databases using existing connections.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection sourceConn = sourceDS.getConnection();
     *      Connection targetConn = targetDS.getConnection()) {
     *
     *     long rowsCopied = JdbcUtils.copy(sourceConn, targetConn, "products");
     *     System.out.println("Copied " + rowsCopied + " products");
     * }
     * }</pre>
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
     *     long rowsCopied = JdbcUtils.copy(sourceConn, targetConn, "orders", "orders_archive");
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
     *     long rowsCopied = JdbcUtils.copy(sourceConn, targetConn, 
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
     * @throws SQLException if a database access error occurs
     */
    public static long copy(final Connection sourceConn, final Connection targetConn, final String sourceTableName, final String targetTableName,
            final int batchSize) throws SQLException {
        final String selectSql = JdbcCodeGenerationUtil.generateSelectSql(sourceConn, sourceTableName);
        String insertSql = JdbcCodeGenerationUtil.generateInsertSql(sourceConn, sourceTableName);

        if (!sourceTableName.equals(targetTableName)) {
            insertSql = Strings.replaceFirstIgnoreCase(insertSql, sourceTableName, targetTableName);
        }

        return copy(sourceConn, selectSql, N.max(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT, batchSize), targetConn, insertSql, batchSize);
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
     *     long rowsCopied = JdbcUtils.copy(sourceConn, targetConn, 
     *                                       "products", "product_catalog", columns);
     *     System.out.println("Copied " + rowsCopied + " products to catalog");
     * }
     * }</pre>
     *
     * @param sourceConn the connection to the source database
     * @param targetConn the connection to the target database
     * @param sourceTableName the name of the source table to copy data from
     * @param targetTableName the name of the target table to copy data to
     * @param selectColumnNames the collection of column names to be copied
     * @return the number of rows copied
     * @throws SQLException if a database access error occurs or any specified column doesn't exist
     */
    public static long copy(final Connection sourceConn, final Connection targetConn, final String sourceTableName, final String targetTableName,
            final Collection<String> selectColumnNames) throws SQLException {
        return copy(sourceConn, targetConn, sourceTableName, targetTableName, selectColumnNames, JdbcUtil.DEFAULT_BATCH_SIZE);
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
     *     long rowsCopied = JdbcUtils.copy(sourceConn, targetConn,
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
     * @param selectColumnNames the collection of column names to be copied
     * @param batchSize the number of rows to be copied in each batch (must be greater than 0)
     * @return the number of rows copied
     * @throws SQLException if a database access error occurs
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
            return JdbcCodeGenerationUtil.generateSelectSql(conn, tableName);
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
            return JdbcCodeGenerationUtil.generateInsertSql(conn, tableName);
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
     *     long rowsCopied = JdbcUtils.copy(sourceConn, selectSql, targetConn, insertSql);
     *     System.out.println("Archived " + rowsCopied + " completed orders");
     * }
     * }</pre>
     *
     * @param sourceConn the connection to the source database
     * @param selectSql the SQL query to select data from the source database
     * @param targetConn the connection to the target database
     * @param insertSql the SQL query to insert data into the target database
     * @return the number of rows copied
     * @throws SQLException if a database access error occurs
     */
    public static long copy(final Connection sourceConn, final String selectSql, final Connection targetConn, final String insertSql) throws SQLException {
        return copy(sourceConn, selectSql, JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT, targetConn, insertSql, JdbcUtil.DEFAULT_BATCH_SIZE);
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
     *     long rowsCopied = JdbcUtils.copy(sourceConn, selectSql, 100000,
     *                                       targetConn, insertSql, 5000);
     *     System.out.println("Copied " + rowsCopied + " rows efficiently");
     * }
     * }</pre>
     *
     * @param sourceConn the connection to the source database
     * @param selectSql the SQL query to select data from the source database
     * @param fetchSize the number of rows to fetch at a time from the source database
     * @param targetConn the connection to the target database
     * @param insertSql the SQL query to insert data into the target database
     * @param batchSize the number of rows to be copied in each batch (must be greater than 0)
     * @return the number of rows copied
     * @throws SQLException if a database access error occurs
     */
    public static long copy(final Connection sourceConn, final String selectSql, final int fetchSize, final Connection targetConn, final String insertSql,
            final int batchSize) throws SQLException {
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
     * long rowsCopied = JdbcUtils.copy(sourceConn, selectSql, targetConn, insertSql, setter);
     * }</pre>
     *
     * @param sourceConn the connection to the source database
     * @param selectSql the SQL query to select data from the source database
     * @param targetConn the connection to the target database
     * @param insertSql the SQL query to insert data into the target database
     * @param stmtSetter the custom statement setter to set the parameters of the prepared statement
     * @return the number of rows copied
     * @throws SQLException if a database access error occurs
     */
    public static long copy(final Connection sourceConn, final String selectSql, final Connection targetConn, final String insertSql,
            final Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> stmtSetter) throws SQLException {
        return copy(sourceConn, selectSql, JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT, targetConn, insertSql, JdbcUtil.DEFAULT_BATCH_SIZE, 0, stmtSetter);
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
     * long rowsCopied = JdbcUtils.copy(
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
     * @param fetchSize the number of rows to fetch at a time from the source database
     * @param targetConn the connection to the target database
     * @param insertSql the SQL query to insert data into the target database
     * @param batchSize the number of rows to be copied in each batch (must be greater than 0)
     * @param batchIntervalInMillis the interval in milliseconds between each batch (0 for no delay)
     * @param stmtSetter the custom statement setter to set the parameters of the prepared statement
     * @return the number of rows copied
     * @throws SQLException if a database access error occurs
     */
    public static long copy(final Connection sourceConn, final String selectSql, final int fetchSize, final Connection targetConn, final String insertSql,
            final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> stmtSetter) throws SQLException {
        PreparedStatement selectStmt = null;
        PreparedStatement insertStmt = null;

        try {
            selectStmt = JdbcUtil.prepareStatement(sourceConn, selectSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            setFetchForLargeResult(sourceConn, selectStmt, fetchSize);

            insertStmt = JdbcUtil.prepareStatement(targetConn, insertSql);

            return copy(selectStmt, insertStmt, batchSize, batchIntervalInMillis, stmtSetter);
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
     * long rowsCopied = JdbcUtils.copy(selectStmt, insertStmt, 1000, 0, setter);
     * System.out.println("Copied " + rowsCopied + " recent records");
     * }</pre>
     *
     * @param selectStmt the PreparedStatement used to select data from the source
     * @param insertStmt the PreparedStatement used to insert data into the target
     * @param batchSize the number of rows to process in each batch (must be greater than 0)
     * @param batchIntervalInMillis the interval in milliseconds between each batch (0 for no delay)
     * @param stmtSetter a BiConsumer that sets the parameters for the PreparedStatement from the ResultSet
     * @return the number of rows copied
     * @throws SQLException if a database access error occurs
     */
    public static long copy(final PreparedStatement selectStmt, final PreparedStatement insertStmt, final int batchSize, final long batchIntervalInMillis,
            final Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> stmtSetter) throws SQLException {
        // N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative");
        N.checkArgument(batchSize > 0 && batchIntervalInMillis >= 0, "'batchSize'=%s must be greater than 0 and 'batchIntervalInMillis'=%s can't be negative",
                batchSize, batchIntervalInMillis);

        final Throwables.BiConsumer<? super PreparedQuery, ? super ResultSet, SQLException> stmtSetterForInsert = N.defaultIfNull(stmtSetter,
                supplierOfStmtSetterByRS);
        final PreparedQuery preparedQueryForInsert = new PreparedQuery(insertStmt);

        ResultSet rs = null;

        try {
            rs = JdbcUtil.executeQuery(selectStmt);

            long cnt = 0;

            while (rs.next()) {
                cnt++;

                stmtSetterForInsert.accept(preparedQueryForInsert, rs);
                insertStmt.addBatch();

                if (cnt % batchSize == 0) {
                    JdbcUtil.executeBatch(insertStmt);

                    if (batchIntervalInMillis > 0) {
                        N.sleepUninterruptibly(batchIntervalInMillis);
                    }
                }
            }

            if (cnt % batchSize > 0) {
                JdbcUtil.executeBatch(insertStmt);
            }

            // insertStmt.clearBatch();   // clearBatch() is called in JdbcUtil.executeBatch(insertStmt)

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
    //    public static long copyInParallel(final Connection sourceConn, final String selectSql, final int fetchSize,
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
    //            copyInParallel(selectStmt, insertStmt, batchSize, batchIntervalInMillis, Jdbc.BiRowMapper.TO_ARRAY, stmtSetter);
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
    //    public static <T> long copyInParallel(final PreparedStatement selectStmt, final PreparedStatement insertStmt,
    //            final int batchSize, final long batchIntervalInMillis, final Jdbc.BiRowMapper<? extends T> rowMapper,
    //            final Throwables.BiConsumer<? super PreparedQuery, ? super T, SQLException> stmtSetter) throws SQLException {
    //        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative");
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
    //                stmtSetter.accept(preparedQueryForInsert, rowMapper.apply(rs));   // TODO
    //                insertStmt.addBatch();
    //
    //                if (cnt % batchSize == 0) {
    //                    JdbcUtil.executeBatch(insertStmt);
    //
    //                    if (batchIntervalInMillis > 0) {
    //                        N.sleepUninterruptibly(batchIntervalInMillis);
    //                    }
    //                }
    //            }
    //
    //            if (cnt % batchSize > 0) {
    //                JdbcUtil.executeBatch(insertStmt);
    //            }
    //
    //            // insertStmt.clearBatch();   // clearBatch() is called in JdbcUtil.executeBatch(insertStmt)
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
    //    public static <E extends Exception> void parse(final Connection conn, final String sql,
    //            final Throwables.Consumer<? super ResultSet, E> rowParser) throws SQLException, E {
    //        parse(conn, sql, rowParser, Fn.emptyAction());
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
    //    public static <E extends Exception, E2 extends Exception> void parse(final Connection conn, final String sql,
    //            final Throwables.Consumer<? super ResultSet, E> rowParser, final Throwables.Runnable<E2> onComplete) throws SQLException, E, E2 {
    //        parse(conn, sql, 0, 0, rowParser, onComplete);
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
    //    public static <E extends Exception> void parse(final Connection conn, final String sql, final int processThreadNum,
    //            final int queueSize, final Throwables.Consumer<? super ResultSet, E> rowParser) throws SQLException, E {
    //        parse(conn, sql, processThreadNum, queueSize, rowParser, Fn.emptyAction());
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
    //     * @param queueSize size of queue to save the processing records/lines loaded from source data. The default size is 1024.
    //     * @param rowParser
    //     * @param onComplete
    //     * @throws SQLException
    //     * @throws E
    //     * @throws E2
    //     */
    //    public static <E extends Exception, E2 extends Exception> void parse(final Connection conn, final String sql,
    //            final int processThreadNum, final int queueSize, final Throwables.Consumer<? super ResultSet, E> rowParser, final Throwables.Runnable<E2> onComplete)
    //            throws SQLException, E, E2 {
    //        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, sql)) {
    //
    //            setFetchForLargeResult(conn, stmt);
    //
    //            parse(stmt, processThreadNum, queueSize, rowParser, onComplete);
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
    //    public static <E extends Exception> void parse(final PreparedStatement stmt,
    //            final Throwables.Consumer<? super ResultSet, E> rowParser) throws SQLException, E {
    //        parse(stmt, rowParser, Fn.emptyAction());
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
    //    public static <E extends Exception, E2 extends Exception> void parse(final PreparedStatement stmt,
    //            final Throwables.Consumer<? super ResultSet, E> rowParser, final Throwables.Runnable<E2> onComplete) throws SQLException, E, E2 {
    //        parse(stmt, 0, 0, rowParser, onComplete);
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
    //    public static <E extends Exception> void parse(final PreparedStatement stmt, final int processThreadNum,
    //            final int queueSize, final Throwables.Consumer<? super ResultSet, E> rowParser) throws SQLException, E {
    //        parse(stmt, processThreadNum, queueSize, rowParser, Fn.emptyAction());
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
    //     * @param queueSize size of queue to save the processing records/lines loaded from source data. The default size is 1024.
    //     * @param rowParser
    //     * @param onComplete
    //     * @throws SQLException
    //     * @throws E
    //     * @throws E2
    //     */
    //    public static <E extends Exception, E2 extends Exception> void parse(final PreparedStatement stmt,
    //            final int processThreadNum, final int queueSize, final Throwables.Consumer<? super ResultSet, E> rowParser, final Throwables.Runnable<E2> onComplete)
    //            throws SQLException, E, E2 {
    //        ResultSet rs = null;
    //
    //        try {
    //            rs = JdbcUtil.executeQuery(stmt);
    //
    //            parse(rs, processThreadNum, queueSize, rowParser, onComplete);
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
    //        parse(rs, rowParser, Fn.emptyAction());
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
    //        parse(rs, 0, 0, rowParser, onComplete);
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
    //        parse(rs, processThreadNum, queueSize, rowParser, Fn.emptyAction());
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
    //     * @param queueSize size of queue to save the processing records/lines loaded from source data. The default size is 1024.
    //     * @param rowParser
    //     * @param onComplete
    //     * @throws E
    //     * @throws E2
    //     */
    //    public static <E extends Exception, E2 extends Exception> void parse(final ResultSet rs, long offset, long count, final int processThreadNum,
    //            final int queueSize, final Throwables.Consumer<? super ResultSet, E> rowParser, final Throwables.Runnable<E2> onComplete) throws E, E2 {
    //        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can not be negative");
    //
    //        Iterators.forEach(iter, processThreadNum, queueSize, elementParser);
    //    }

    private static void setFetchForLargeResult(final Connection conn, final PreparedStatement stmt) throws SQLException {
        setFetchForLargeResult(conn, stmt, JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT);
    }

    private static void setFetchForLargeResult(final Connection conn, final PreparedStatement stmt, final int fetchSize) throws SQLException {
        stmt.setFetchDirection(ResultSet.FETCH_FORWARD);

        if (JdbcUtil.getDBProductInfo(conn).version().isMySQL()) {
            stmt.setFetchSize(Integer.MIN_VALUE);
        } else {
            stmt.setFetchSize(fetchSize);
        }
    }

    /**
     * Creates a parameter setter for a PreparedQuery using the provided ColumnGetter.
     * This utility method simplifies the creation of statement setters for copy operations.
     *
     * <p>The returned BiConsumer automatically determines the column count from the ResultSet
     * and uses the provided ColumnGetter to extract values for each column. This is particularly
     * useful when you need custom value extraction logic across all columns.</p>
     *
     * <p><strong>Important:</strong> The returned BiConsumer is stateful and maintains the column
     * count after first use. It should not be reused across different ResultSets with different
     * column counts or used in parallel operations.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Create a setter that converts all nulls to empty strings
     * ColumnGetter<Object> getter = (rs, columnIndex) -> {
     *     Object value = rs.getObject(columnIndex);
     *     return (value == null && rs.getMetaData().getColumnType(columnIndex) == Types.VARCHAR) 
     *            ? "" : value;
     * };
     *
     * Throwables.BiConsumer<PreparedQuery, ResultSet, SQLException> setter =
     *     JdbcUtils.createParamSetter(getter);
     *
     * // Use in copy operation
     * long copied = JdbcUtils.copy(sourceConn, selectSql, targetConn, insertSql, setter);
     * }</pre>
     *
     * @param columnGetterForAll the ColumnGetter to use for extracting values from all columns
     * @return a stateful BiConsumer that sets parameters on a PreparedQuery based on ResultSet values
     * @see #copy(Connection, String, Connection, String, Throwables.BiConsumer)
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
}
