/*
 * Copyright (c) 2015, Haiyang Li.
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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.annotation.Internal;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.Jdbc.BiParametersSetter;
import com.landawn.abacus.jdbc.Jdbc.BiResultExtractor;
import com.landawn.abacus.jdbc.Jdbc.BiRowFilter;
import com.landawn.abacus.jdbc.Jdbc.BiRowMapper;
import com.landawn.abacus.jdbc.Jdbc.OutParam;
import com.landawn.abacus.jdbc.Jdbc.OutParamResult;
import com.landawn.abacus.jdbc.Jdbc.ResultExtractor;
import com.landawn.abacus.jdbc.Jdbc.RowExtractor;
import com.landawn.abacus.jdbc.Jdbc.RowFilter;
import com.landawn.abacus.jdbc.Jdbc.RowMapper;
import com.landawn.abacus.jdbc.SQLTransaction.CreatedBy;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.jdbc.dao.Dao;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.parser.JSONParser;
import com.landawn.abacus.parser.KryoParser;
import com.landawn.abacus.parser.ParserFactory;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.query.AbstractQueryBuilder.SP;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.query.QueryUtil;
import com.landawn.abacus.query.SQLBuilder;
import com.landawn.abacus.query.SQLMapper;
import com.landawn.abacus.query.SQLOperation;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.AsyncExecutor;
import com.landawn.abacus.util.Beans;
import com.landawn.abacus.util.Charsets;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.ExceptionUtil;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.Fn.BiConsumers;
import com.landawn.abacus.util.Holder;
import com.landawn.abacus.util.IOUtil;
import com.landawn.abacus.util.ImmutableMap;
import com.landawn.abacus.util.InternalUtil;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.NamingPolicy;
import com.landawn.abacus.util.ObjectPool;
import com.landawn.abacus.util.RowDataset;
import com.landawn.abacus.util.Seid;
import com.landawn.abacus.util.Splitter;
import com.landawn.abacus.util.Strings;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.Tuple;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.function.TriConsumer;
import com.landawn.abacus.util.stream.EntryStream;
import com.landawn.abacus.util.stream.ObjIteratorEx;
import com.landawn.abacus.util.stream.Stream;
import com.landawn.abacus.util.stream.Stream.StreamEx;

/**
 * A comprehensive, production-ready utility class providing enterprise-grade JDBC operations with advanced
 * database access patterns, transaction management, and performance optimization features. This class serves
 * as a high-level abstraction over standard JDBC APIs, offering simplified database interactions while
 * maintaining full control over connections, transactions, and resource management for mission-critical
 * applications requiring robust data persistence solutions.
 *
 * <p>The {@code JdbcUtil} class addresses common challenges in enterprise database programming by providing
 * intuitive APIs for complex JDBC operations, automatic resource management, comprehensive error handling,
 * and seamless integration with popular frameworks. It supports both traditional JDBC patterns and modern
 * functional programming approaches, making it suitable for legacy system integration and greenfield
 * development scenarios in enterprise environments.</p>
 *
 * <p><b>⚠️ IMPORTANT - Production Database Usage:</b>
 * This utility is designed for enterprise production environments with built-in support for connection
 * pooling, transaction management, SQL injection prevention, and performance monitoring. Always use
 * prepared statements and parameterized queries to prevent SQL injection attacks and ensure optimal
 * performance through statement caching and execution plan optimization.</p>
 *
 * <p><b>Key Features and Capabilities:</b>
 * <ul>
 *   <li><b>Simplified JDBC Operations:</b> High-level APIs that eliminate boilerplate code and resource management</li>
 *   <li><b>Transaction Management:</b> Comprehensive transaction support with Spring Framework integration</li>
 *   <li><b>Connection Pooling:</b> Built-in support for HikariCP, C3P0, and other popular connection pools</li>
 *   <li><b>Batch Processing:</b> Optimized batch operations for high-throughput data processing scenarios</li>
 *   <li><b>Stream Integration:</b> Functional programming patterns with Java Streams for ResultSet processing</li>
 *   <li><b>SQL Logging:</b> Comprehensive SQL execution logging with performance metrics and debugging information</li>
 *   <li><b>Type Safety:</b> Strong typing for database operations with automatic object mapping</li>
 *   <li><b>Asynchronous Operations:</b> Non-blocking database operations with {@link ContinuableFuture} support</li>
 * </ul>
 *
 * <p><b>Design Philosophy:</b>
 * <ul>
 *   <li><b>Simplicity Over Complexity:</b> Intuitive APIs that handle complex JDBC scenarios transparently</li>
 *   <li><b>Performance First:</b> Optimized for high-throughput enterprise applications with minimal overhead</li>
 *   <li><b>Resource Safety:</b> Automatic resource management preventing connection leaks and memory issues</li>
 *   <li><b>Framework Integration:</b> Seamless compatibility with Spring, Hibernate, and other enterprise frameworks</li>
 *   <li><b>Developer Productivity:</b> Reduces development time while maintaining enterprise-grade reliability</li>
 * </ul>
 *
 * <p><b>Supported Database Operations:</b>
 * <table border="1" style="border-collapse: collapse;">
 *   <caption><b>Database Operation Types and Methods</b></caption>
 *   <tr style="background-color: #f2f2f2;">
 *     <th>Operation Type</th>
 *     <th>Primary Methods</th>
 *     <th>Use Cases</th>
 *     <th>Performance Notes</th>
 *   </tr>
 *   <tr>
 *     <td>Query Execution</td>
 *     <td>executeQuery(), prepareQuery()</td>
 *     <td>SELECT operations, data retrieval</td>
 *     <td>Uses prepared statements for caching</td>
 *   </tr>
 *   <tr>
 *     <td>Data Modification</td>
 *     <td>executeUpdate(), prepareUpdate()</td>
 *     <td>INSERT, UPDATE, DELETE operations</td>
 *     <td>Optimized for bulk operations</td>
 *   </tr>
 *   <tr>
 *     <td>Batch Processing</td>
 *     <td>executeBatch(), prepareBatch()</td>
 *     <td>High-volume data processing</td>
 *     <td>Significant performance gains</td>
 *   </tr>
 *   <tr>
 *     <td>Stored Procedures</td>
 *     <td>prepareCall(), executeCall()</td>
 *     <td>Complex business logic execution</td>
 *     <td>Database-side processing</td>
 *   </tr>
 *   <tr>
 *     <td>Transaction Management</td>
 *     <td>beginTransaction(), commit(), rollback()</td>
 *     <td>ACID compliance, data consistency</td>
 *     <td>Connection-level transaction control</td>
 *   </tr>
 *   <tr>
 *     <td>Stream Processing</td>
 *     <td>stream(), streamAll()</td>
 *     <td>Large ResultSet processing</td>
 *     <td>Memory-efficient iteration</td>
 *   </tr>
 * </table>
 *
 * <p><b>Core API Categories:</b>
 * <ul>
 *   <li><b>Connection Management:</b> {@code getConnection()}, {@code releaseConnection()}, {@code createDataSource()}</li>
 *   <li><b>Query Operations:</b> {@code executeQuery()}, {@code prepareQuery()}, {@code findFirst()}, {@code exists()}</li>
 *   <li><b>Update Operations:</b> {@code executeUpdate()}, {@code prepareUpdate()}, {@code insert()}, {@code update()}, {@code delete()}</li>
 *   <li><b>Batch Operations:</b> {@code executeBatch()}, {@code prepareBatch()}, {@code batchInsert()}, {@code batchUpdate()}</li>
 *   <li><b>Transaction Support:</b> {@code beginTransaction()}, {@code runInTransaction()}, {@code asyncRunInTransaction()}</li>
 *   <li><b>DAO Creation:</b> {@code createDao()}, {@code createCrudDao()}, {@code createReadOnlyDao()}</li>
 * </ul>
 *
 * <p><b>Common Usage Patterns:</b>
 * <pre>{@code
 * // Basic query execution with automatic resource management
 * DataSource dataSource = JdbcUtil.createHikariDataSource(url, user, password);
 * List<User> users = JdbcUtil.executeQuery(dataSource,
 *     "SELECT * FROM users WHERE department = ? AND active = ?",
 *     User.class, "Engineering", true);
 *
 * // PreparedQuery for reusable statements
 * try (PreparedQuery query = JdbcUtil.prepareQuery(dataSource,
 *         "SELECT * FROM orders WHERE customer_id = ? AND order_date > ?").closeAfterExecution(false)) {
 *
 *     List<Order> recentOrders = query.setLong(1, customerId)
 *         .setDate(2, lastWeek)
 *         .list(Order.class);
 *
 *     // Reuse the same prepared statement
 *     List<Order> allOrders = query.setLong(1, customerId)
 *         .setDate(2, yearAgo)
 *         .list(Order.class);
 * }
 *
 * // Transaction management with automatic rollback
 * SQLTransaction transaction = JdbcUtil.beginTransaction(dataSource);
 * try {
 *     JdbcUtil.executeUpdate(transaction.connection(),
 *         "UPDATE accounts SET balance = balance - ? WHERE id = ?",
 *         amount, fromAccountId);
 *
 *     JdbcUtil.executeUpdate(transaction.connection(),
 *         "UPDATE accounts SET balance = balance + ? WHERE id = ?",
 *         amount, toAccountId);
 *
 *     transaction.commit();
 * } finally {
 *     transaction.rollbackIfNotCommitted();
 * }
 *
 * // Stream processing for large result sets
 * try (Stream<Product> productStream = JdbcUtil.stream(dataSource,
 *         Product.class, "SELECT * FROM products WHERE category = ?", category)) {
 *
 *     Map<String, List<Product>> productsByBrand = productStream
 *         .filter(p -> p.getPrice() > minPrice)
 *         .collect(Collectors.groupingBy(Product::getBrand));
 * }
 * }</pre>
 *
 * <p><b>Advanced Transaction Patterns:</b>
 * <pre>{@code
 * // Functional transaction management
 * public class OrderService {
 *     private final DataSource dataSource;
 *
 *     public Order processOrder(OrderRequest request) {
 *         return JdbcUtil.runInTransaction(dataSource, () -> {
 *             // Validate inventory
 *             Dataset inventoryResult = JdbcUtil.executeQuery(dataSource,
 *                 "SELECT 1 FROM inventory WHERE product_id = ? AND quantity >= ?",
 *                 request.getProductId(), request.getQuantity());
 *
 *             if (inventoryResult.isEmpty()) {
 *                 throw new InsufficientInventoryException();
 *             }
 *
 *             // Create order with auto-generated key
 *             long orderId = JdbcUtil.prepareQuery(dataSource,
 *                     "INSERT INTO orders (customer_id, product_id, quantity, order_date) VALUES (?, ?, ?, ?)", true)
 *                 .setLong(1, request.getCustomerId())
 *                 .setLong(2, request.getProductId())
 *                 .setInt(3, request.getQuantity())
 *                 .setTimestamp(4, Timestamp.valueOf(LocalDateTime.now()))
 *                 .insert()
 *                 .orElseThrow(() -> new RuntimeException("Failed to get generated ID"));
 *
 *             // Update inventory
 *             JdbcUtil.executeUpdate(dataSource,
 *                 "UPDATE inventory SET quantity = quantity - ? WHERE product_id = ?",
 *                 request.getQuantity(), request.getProductId());
 *
 *             // Return created order
 *             return JdbcUtil.prepareQuery(dataSource, "SELECT * FROM orders WHERE id = ?")
 *                 .setLong(1, orderId)
 *                 .findFirst(Order.class)
 *                 .orElse(null);
 *         });
 *     }
 *
 *     // Batch processing in transaction
 *     public void processOrdersBatch(List<OrderRequest> requests) {
 *         List<Object[]> batchParams = new ArrayList<>();
 *         for (OrderRequest request : requests) {
 *             batchParams.add(new Object[] {request.getCustomerId(),
 *                 request.getProductId(), request.getQuantity()});
 *         }
 *         JdbcUtil.executeBatchUpdate(dataSource,
 *             "INSERT INTO orders (customer_id, product_id, quantity) VALUES (?, ?, ?)",
 *             batchParams);
 *     }
 * }
 * }</pre>
 *
 * <p><b>Batch Processing and Performance Optimization:</b>
 * <ul>
 *   <li><b>Batch Size Optimization:</b> Default batch size of 200 with configurable limits for optimal performance</li>
 *   <li><b>Statement Caching:</b> Automatic prepared statement caching to reduce compilation overhead</li>
 *   <li><b>Fetch Size Control:</b> Configurable fetch sizes for large result sets (default: 1000)</li>
 *   <li><b>Connection Pooling:</b> Integration with HikariCP, C3P0, and other enterprise connection pools</li>
 *   <li><b>Bulk Operations:</b> Optimized bulk insert, update, and delete operations</li>
 * </ul>
 *
 * <p><b>Database Vendor Support:</b>
 * <ul>
 *   <li><b>Universal JDBC:</b> Works with any JDBC-compliant database driver</li>
 *   <li><b>Vendor Optimizations:</b> Specific optimizations for Oracle, PostgreSQL, MySQL, SQL Server</li>
 *   <li><b>SQL Dialect Support:</b> Handles vendor-specific SQL syntax variations</li>
 *   <li><b>Type Mapping:</b> Automatic Java-to-SQL type mapping with vendor-specific extensions</li>
 *   <li><b>Pagination Support:</b> Database-specific pagination query generation</li>
 * </ul>
 *
 * <p><b>Security and SQL Injection Prevention:</b>
 * <ul>
 *   <li><b>Parameterized Queries:</b> All query methods use prepared statements with parameter binding</li>
 *   <li><b>Input Validation:</b> Automatic validation of SQL parameters to prevent injection attacks</li>
 *   <li><b>Connection Security:</b> Secure connection handling with proper credential management</li>
 *   <li><b>SQL Sanitization:</b> Built-in sanitization for dynamic SQL construction scenarios</li>
 *   <li><b>Audit Logging:</b> Comprehensive logging for security audit and compliance requirements</li>
 * </ul>
 *
 * <p><b>Error Handling and Exception Management:</b>
 * <ul>
 *   <li><b>UncheckedSQLException:</b> Runtime wrapper for SQLException to eliminate checked exception handling</li>
 *   <li><b>Connection Failures:</b> Automatic retry logic for transient connection failures</li>
 *   <li><b>Resource Cleanup:</b> Guaranteed resource cleanup even in exceptional circumstances</li>
 *   <li><b>Detailed Error Messages:</b> Enhanced error messages with SQL context and parameter information</li>
 *   <li><b>Exception Translation:</b> Framework-specific exception translation for Spring integration</li>
 * </ul>
 *
 * <p><b>Performance Monitoring and Logging:</b>
 * <ul>
 *   <li><b>SQL Execution Logging:</b> Detailed logging of all SQL statements with execution times</li>
 *   <li><b>Performance Metrics:</b> Built-in metrics collection for query performance analysis</li>
 *   <li><b>Connection Pool Monitoring:</b> Statistics on connection usage and pool health</li>
 *   <li><b>Slow Query Detection:</b> Automatic detection and logging of slow-performing queries</li>
 *   <li><b>Debug Information:</b> Comprehensive debugging information for troubleshooting</li>
 * </ul>
 *
 * <p><b>Framework Integration Support:</b>
 * <ul>
 *   <li><b>Spring Framework:</b> Seamless integration with Spring's transaction management and dependency injection</li>
 *   <li><b>Spring Boot:</b> Auto-configuration support for Spring Boot applications</li>
 *   <li><b>Hibernate Integration:</b> Compatible with Hibernate for mixed ORM/JDBC scenarios</li>
 *   <li><b>JTA Support:</b> Integration with Java Transaction API for distributed transactions</li>
 *   <li><b>CDI Compatible:</b> Works with Java EE/Jakarta EE dependency injection frameworks</li>
 * </ul>
 *
 * <p><b>Data Access Object (DAO) Support:</b>
 * <ul>
 *   <li><b>DAO Creation:</b> Dynamic DAO implementation generation with automatic method mapping</li>
 *   <li><b>CRUD Operations:</b> Built-in CRUD operations with type-safe entity handling</li>
 *   <li><b>Custom Queries:</b> Support for custom query methods with annotation-based configuration</li>
 *   <li><b>Caching Integration:</b> DAO-level caching with configurable cache strategies</li>
 *   <li><b>Validation Support:</b> Automatic entity validation before database operations</li>
 * </ul>
 *
 * <p><b>Type Safety and Object Mapping:</b>
 * <ul>
 *   <li><b>Automatic Mapping:</b> Automatic mapping between ResultSet and Java objects using reflection</li>
 *   <li><b>Type Conversion:</b> Built-in type conversion for common database-to-Java type mappings</li>
 *   <li><b>Generic Support:</b> Full generic type support for collections and complex objects</li>
 *   <li><b>Custom Mappers:</b> Support for custom RowMapper implementations for complex mapping scenarios</li>
 *   <li><b>Annotation Support:</b> JPA-style annotations for entity mapping configuration</li>
 * </ul>
 *
 * <p><b>Best Practices and Recommendations:</b>
 * <ul>
 *   <li>Always use parameterized queries to prevent SQL injection attacks</li>
 *   <li>Configure appropriate connection pool settings for your application load</li>
 *   <li>Use transactions for operations that modify multiple tables or require consistency</li>
 *   <li>Leverage batch operations for bulk data processing to improve performance</li>
 *   <li>Monitor SQL execution times and optimize slow queries with proper indexing</li>
 *   <li>Use streaming APIs for large result sets to avoid memory exhaustion</li>
 *   <li>Configure appropriate timeouts for database operations in production environments</li>
 *   <li>Implement proper error handling and retry logic for transient failures</li>
 * </ul>
 *
 * <p><b>Common Anti-Patterns to Avoid:</b>
 * <ul>
 *   <li>Creating new connections for each database operation (use connection pooling)</li>
 *   <li>String concatenation for SQL construction (use parameterized queries)</li>
 *   <li>Ignoring transaction boundaries in multi-step operations</li>
 *   <li>Not closing resources properly (use try-with-resources or JdbcUtil's automatic management)</li>
 *   <li>Loading entire large result sets into memory (use streaming or pagination)</li>
 *   <li>Not handling SQL exceptions appropriately in application logic</li>
 *   <li>Using overly complex queries when simpler ones would suffice</li>
 * </ul>
 *
 * <p><b>Performance Optimization Guidelines:</b>
 * <ul>
 *   <li><b>Index Optimization:</b> Ensure proper database indexing for frequently queried columns</li>
 *   <li><b>Query Optimization:</b> Write efficient SQL queries avoiding unnecessary JOINs and subqueries</li>
 *   <li><b>Batch Processing:</b> Use batch operations for bulk data modifications</li>
 *   <li><b>Connection Pooling:</b> Configure connection pools with appropriate min/max connection limits</li>
 *   <li><b>Statement Caching:</b> Leverage prepared statement caching for frequently executed queries</li>
 *   <li><b>Fetch Size Tuning:</b> Adjust fetch sizes based on expected result set sizes</li>
 * </ul>
 *
 * <p><b>Memory Management and Resource Optimization:</b>
 * <ul>
 *   <li><b>Connection Lifecycle:</b> Automatic connection acquisition and release</li>
 *   <li><b>Statement Cleanup:</b> Proper cleanup of PreparedStatement and CallableStatement resources</li>
 *   <li><b>ResultSet Management:</b> Automatic ResultSet closing and memory cleanup</li>
 *   <li><b>Large Object Handling:</b> Efficient handling of BLOB and CLOB data types</li>
 *   <li><b>Memory Monitoring:</b> Built-in monitoring for memory usage in large data operations</li>
 * </ul>
 *
 * @see Connection
 * @see com.landawn.abacus.query.condition.ConditionFactory
 * @see com.landawn.abacus.query.condition.ConditionFactory.CF
 * @see com.landawn.abacus.annotation.ReadOnly
 * @see com.landawn.abacus.annotation.ReadOnlyId
 * @see com.landawn.abacus.annotation.NonUpdatable
 * @see com.landawn.abacus.annotation.Transient
 * @see com.landawn.abacus.annotation.Table
 * @see com.landawn.abacus.annotation.Column
 * @see PreparedStatement
 * @see ResultSet
 * @see javax.sql.DataSource
 * @see SQLTransaction
 * @see PreparedQuery
 * @see com.landawn.abacus.jdbc.dao.Dao
 * @see com.landawn.abacus.jdbc.dao.CrudDao
 * @see com.landawn.abacus.util.ContinuableFuture
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Connection.html">Connection</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Statement.html">Statement</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/PreparedStatement.html">PreparedStatement</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/ResultSet.html">ResultSet</a>
 * @see <a href="https://docs.oracle.com/javase/tutorial/jdbc/">JDBC Tutorial</a>
 * @see <a href="https://github.com/brettwooldridge/HikariCP">HikariCP Connection Pool</a>
 */
@SuppressWarnings({ "java:S1192", "java:S6539", "resource" })
public final class JdbcUtil {

    static final Logger logger = LoggerFactory.getLogger(JdbcUtil.class);

    static final Logger sqlLogger = LoggerFactory.getLogger("com.landawn.abacus.SQL");

    /**
     * Default batch size for batch operations. Value: 200
     */
    public static final int DEFAULT_BATCH_SIZE = 200;

    /**
     * Default fetch size for retrieving large result sets. Value: 1000
     */
    public static final int DEFAULT_FETCH_SIZE_FOR_BIG_RESULT = 1000;

    /**
     * Default fetch size for stream-based result processing. Value: 100
     */
    public static final int DEFAULT_FETCH_SIZE_FOR_STREAM = 100;

    /**
     * Default capacity for internal caches. Value: 1000
     */
    public static final int DEFAULT_CACHE_CAPACITY = 1000;

    /**
     * Default cache evict delay in milliseconds. Value: 3000 (3 seconds)
     */
    public static final int DEFAULT_CACHE_EVICT_DELAY = 3 * 1000;

    /**
     * Default cache live time in milliseconds. Value: 1800000 (30 minutes)
     */
    public static final int DEFAULT_CACHE_LIVE_TIME = 30 * 60 * 1000;

    /**
     * Default maximum length for SQL logs. Value: 1024
     */
    public static final int DEFAULT_MAX_SQL_LOG_LENGTH = 1024;

    /**
     * Default minimum execution time in milliseconds for SQL performance logging. Value: 1000 (1 second)
     */
    public static final long DEFAULT_MIN_EXECUTION_TIME_FOR_SQL_PERF_LOG = 1000L;

    /**
     * Default minimum execution time in milliseconds for DAO method performance logging. Value: 3000 (3 seconds)
     */
    public static final long DEFAULT_MIN_EXECUTION_TIME_FOR_DAO_METHOD_PERF_LOG = 3000L;

    /**
     * Default maximum idle time for cache entries in milliseconds. Value: 180000 (3 minutes)
     */
    public static final int DEFAULT_CACHE_MAX_IDLE_TIME = 3 * 60 * 1000;

    /**
     * Default SQL extractor function used to extract SQL statements from Statement objects.
     * This function handles various JDBC driver implementations including HikariCP and C3P0 wrapped statements.
     */
    public static final Throwables.Function<Statement, String, SQLException> DEFAULT_SQL_EXTRACTOR = stmt -> {
        Statement stmtToUse = stmt;
        String clsName = stmtToUse.getClass().getName();

        if ((clsName.startsWith("com.zaxxer.hikari") || clsName.startsWith("com.mchange.v2.c3p0")) && stmt.isWrapperFor(Statement.class)) {
            stmtToUse = stmt.unwrap(Statement.class);
            clsName = stmtToUse.getClass().getName();
        }

        if (clsName.startsWith("oracle.jdbc") && (stmtToUse instanceof oracle.jdbc.internal.OraclePreparedStatement)) { //NOSONAR
            try {
                return ((oracle.jdbc.internal.OraclePreparedStatement) stmtToUse).getOriginalSql();
            } catch (final SQLException e) {
                // ignore.
            }
        }

        return stmtToUse.toString();
    };

    static final JSONParser jsonParser = ParserFactory.createJSONParser();

    static final KryoParser kryoParser = ParserFactory.isKryoAvailable() ? ParserFactory.createKryoParser() : null;

    static final char CHAR_ZERO = 0;

    // static final int MAX_BATCH_SIZE = 1000;

    // TODO is it right to do it?
    // static final KeyedObjectPool<Statement, PoolableWrapper<String>> stmtPoolForSql = PoolFactory.createKeyedObjectPool(1000, 3000);

    // ...
    static final String CURRENT_DIR_PATH = "./";

    static final AsyncExecutor asyncExecutor = new AsyncExecutor(//
            N.max(64, IOUtil.CPU_CORES * 8), // coreThreadPoolSize
            N.max(128, IOUtil.CPU_CORES * 16), // maxThreadPoolSize
            180L, TimeUnit.SECONDS);

    static final BiParametersSetter<? super PreparedQuery, ? super Object[]> DEFAULT_STMT_SETTER = (stmt, parameters) -> {
        for (int i = 0, len = parameters.length; i < len; i++) {
            stmt.setObject(i + 1, parameters[i]);
        }
    };

    private static final Set<String> sqlStateForTableNotExists = N.newHashSet();

    static {
        sqlStateForTableNotExists.add("42S02"); // for MySQCF.
        sqlStateForTableNotExists.add("42P01"); // for PostgreSQCF.
        sqlStateForTableNotExists.add("42501"); // for HSQLDB.
    }

    static final Set<String> QUERY_METHOD_NAME_SET = N.asSet("query", "queryFor", "list", "get", "batchGet", "find", "findFirst", "findOnlyOne", "load",
            "exist", "notExist", "count");

    static final Set<String> UPDATE_METHOD_NAME_SET = N.asSet("update", "delete", "deleteById", "insert", "save", "batchUpdate", "batchDelete",
            "batchDeleteByIds", "batchInsert", "batchSave", "batchUpsert", "upsert", "execute");

    static final Set<Method> BUILT_IN_DAO_QUERY_METHODS = StreamEx.of(ClassUtil.getClassesByPackage(Dao.class.getPackageName(), false, true)) //
            .filter(Dao.class::isAssignableFrom)
            .flattmap(Class::getDeclaredMethods)
            .filter(it -> Modifier.isPublic(it.getModifiers()) && !Modifier.isStatic(it.getModifiers()))
            .filter(it -> it.getAnnotation(NonDBOperation.class) == null)
            .filter(it -> N.anyMatch(QUERY_METHOD_NAME_SET, e -> Strings.containsIgnoreCase(it.getName(), e)))
            .toImmutableSet();

    static final Set<Method> BUILT_IN_DAO_UPDATE_METHODS = StreamEx.of(ClassUtil.getClassesByPackage(Dao.class.getPackageName(), false, true)) //
            .filter(Dao.class::isAssignableFrom)
            .flattmap(Class::getDeclaredMethods)
            .filter(it -> Modifier.isPublic(it.getModifiers()) && !Modifier.isStatic(it.getModifiers()))
            .filter(it -> it.getAnnotation(NonDBOperation.class) == null)
            .filter(it -> N.anyMatch(UPDATE_METHOD_NAME_SET, e -> Strings.containsIgnoreCase(it.getName(), e)))
            .toImmutableSet();

    static final Predicate<Method> IS_QUERY_METHOD = method -> N.anyMatch(QUERY_METHOD_NAME_SET,
            it -> Strings.isNotEmpty(it) && (Strings.startsWith(method.getName(), it) || Pattern.matches(it, method.getName())));

    static final Predicate<Method> IS_UPDATE_METHOD = method -> N.anyMatch(UPDATE_METHOD_NAME_SET,
            it -> Strings.isNotEmpty(it) && (Strings.startsWith(method.getName(), it) || Pattern.matches(it, method.getName())));

    static Throwables.Function<Statement, String, SQLException> _sqlExtractor = DEFAULT_SQL_EXTRACTOR; //NOSONAR

    static final ThreadLocal<SqlLogConfig> isSQLLogEnabled_TL = ThreadLocal.withInitial(() -> new SqlLogConfig(false, DEFAULT_MAX_SQL_LOG_LENGTH));

    static final ThreadLocal<SqlLogConfig> minExecutionTimeForSqlPerfLog_TL = ThreadLocal
            .withInitial(() -> new SqlLogConfig(DEFAULT_MIN_EXECUTION_TIME_FOR_SQL_PERF_LOG, DEFAULT_MAX_SQL_LOG_LENGTH));

    static final ThreadLocal<Boolean> isSpringTransactionalDisabled_TL = ThreadLocal.withInitial(() -> false);

    static boolean isSqlLogAllowed = true;

    static boolean isSqlPerfLogAllowed = true;

    static boolean isDaoMethodPerfLogAllowed = true;

    static boolean isInSpring = true;

    static TriConsumer<String, Long, Long> _sqlLogHandler = null; //NOSONAR

    @SuppressWarnings("rawtypes")
    private static final Map<Tuple2<Class<?>, Class<?>>, Map<NamingPolicy, Tuple3<BiRowMapper, com.landawn.abacus.util.function.Function, com.landawn.abacus.util.function.BiConsumer>>> idGeneratorGetterSetterPool = new ConcurrentHashMap<>();

    @SuppressWarnings("rawtypes")
    private static final Tuple3<BiRowMapper, com.landawn.abacus.util.function.Function, com.landawn.abacus.util.function.BiConsumer> noIdGeneratorGetterSetter = Tuple
            .of(JdbcUtil.NO_BI_GENERATED_KEY_EXTRACTOR, entity -> null, BiConsumers.doNothing());

    @SuppressWarnings("rawtypes")
    private static final Map<Class<? extends Dao>, BiRowMapper<?>> idExtractorPool = new ConcurrentHashMap<>();

    static final String CACHE_KEY_SPLITOR = "#";

    static final ThreadLocal<Jdbc.DaoCache> localThreadCache_TL = new ThreadLocal<>();

    static {
        try {
            isInSpring = ClassUtil.forClass("org.springframework.jdbc.datasource.DataSourceUtils") != null;
        } catch (final Throwable e) {
            isInSpring = false;
        }
    }

    private JdbcUtil() {
        // singleton
    }

    /**
     * Retrieves the database product information from the given DataSource.
     * This method establishes a temporary connection to extract metadata about the database,
     * including its product name, version, and a standardized {@link DBVersion} enum.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DataSource dataSource = ...; // Obtain a DataSource instance
     * DBProductInfo dbInfo = JdbcUtil.getDBProductInfo(dataSource);
     *
     * System.out.println("Database Product Name: " + dbInfo.productName());
     * System.out.println("Database Product Version: " + dbInfo.productVersion());
     *
     * // Perform actions based on the database type
     * if (dbInfo.version().isPostgreSQL()) {
     *     System.out.println("This is a PostgreSQL database.");
     * } else if (dbInfo.version() == DBVersion.MySQL_8) {
     *     System.out.println("This is MySQL version 8.");
     * }
     * }</pre>
     *
     * @param ds The DataSource from which to obtain a database connection.
     * @return A {@link DBProductInfo} object containing the database product name, version, and type.
     * @throws UncheckedSQLException If a database access error occurs while trying to connect to the database.
     * @see #getDBProductInfo(Connection)
     * @see DBProductInfo
     * @see DBVersion
     */
    public static DBProductInfo getDBProductInfo(final javax.sql.DataSource ds) throws UncheckedSQLException {
        Connection conn = null;

        try {
            conn = ds.getConnection();
            return getDBProductInfo(conn);
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.releaseConnection(conn, ds);
        }
    }

    /**
     * Retrieves the database product information from the given {@link Connection}.
     * This method extracts metadata to determine the database type and version without closing the connection.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Connection connection = ...; // Obtain a database Connection
     * DBProductInfo dbInfo = JdbcUtil.getDBProductInfo(connection);
     *
     * System.out.println("Database Name: " + dbInfo.productName());
     * System.out.println("Database Version: " + dbInfo.productVersion());
     *
     * // Example of checking for a specific database version
     * if (dbInfo.version() == DBVersion.Oracle) {
     *     System.out.println("Connected to an Oracle database.");
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use for retrieving metadata. It must be an active connection.
     * @return A {@link DBProductInfo} object containing the database product name, version, and a standardized {@link DBVersion} enum.
     * @throws UncheckedSQLException If a database access error occurs while retrieving metadata.
     * @see #getDBProductInfo(javax.sql.DataSource)
     * @see DatabaseMetaData
     */
    public static DBProductInfo getDBProductInfo(final Connection conn) throws UncheckedSQLException {
        try {
            final DatabaseMetaData metaData = conn.getMetaData();

            final String dbProductName = metaData.getDatabaseProductName();
            final String dbProductVersion = metaData.getDatabaseProductVersion();

            DBVersion dbVersion = DBVersion.OTHERS;

            if (Strings.containsIgnoreCase(dbProductName, "H2")) {
                dbVersion = DBVersion.H2;
            } else if (Strings.containsIgnoreCase(dbProductName, "HSQL")) {
                dbVersion = DBVersion.HSQLDB;
            } else if (Strings.containsIgnoreCase(dbProductName, "MySQL")) {
                if (dbProductVersion.startsWith("5.5")) {
                    dbVersion = DBVersion.MySQL_5_5;
                } else if (dbProductVersion.startsWith("5.6")) {
                    dbVersion = DBVersion.MySQL_5_6;
                } else if (dbProductVersion.startsWith("5.7")) {
                    dbVersion = DBVersion.MySQL_5_7;
                } else if (dbProductVersion.startsWith("5.8")) {
                    dbVersion = DBVersion.MySQL_5_8;
                } else if (dbProductVersion.startsWith("5.9")) {
                    dbVersion = DBVersion.MySQL_5_9;
                } else if (dbProductVersion.startsWith("6")) {
                    dbVersion = DBVersion.MySQL_6;
                } else if (dbProductVersion.startsWith("7")) {
                    dbVersion = DBVersion.MySQL_7;
                } else if (dbProductVersion.startsWith("8")) {
                    dbVersion = DBVersion.MySQL_8;
                } else if (dbProductVersion.startsWith("9")) {
                    dbVersion = DBVersion.MySQL_9;
                } else if (dbProductVersion.startsWith("10")) {
                    dbVersion = DBVersion.MySQL_10;
                } else {
                    dbVersion = DBVersion.MySQL_OTHERS;
                }
            } else if (Strings.containsIgnoreCase(dbProductName, "MariaDB")) {
                dbVersion = DBVersion.MariaDB;
            } else if (Strings.containsIgnoreCase(dbProductName, "PostgreSQL")) {
                if (dbProductVersion.startsWith("9.2")) {
                    dbVersion = DBVersion.PostgreSQL_9_2;
                } else if (dbProductVersion.startsWith("9.3")) {
                    dbVersion = DBVersion.PostgreSQL_9_3;
                } else if (dbProductVersion.startsWith("9.4")) {
                    dbVersion = DBVersion.PostgreSQL_9_4;
                } else if (dbProductVersion.startsWith("9.5")) {
                    dbVersion = DBVersion.PostgreSQL_9_5;
                } else if (dbProductVersion.startsWith("10")) {
                    dbVersion = DBVersion.PostgreSQL_10;
                } else if (dbProductVersion.startsWith("11")) {
                    dbVersion = DBVersion.PostgreSQL_11;
                } else if (dbProductVersion.startsWith("12")) {
                    dbVersion = DBVersion.PostgreSQL_12;
                } else {
                    dbVersion = DBVersion.PostgreSQL_OTHERS;
                }
            } else if (Strings.containsIgnoreCase(dbProductName, "Oracle")) {
                dbVersion = DBVersion.Oracle;
            } else if (Strings.containsIgnoreCase(dbProductName, "DB2")) {
                dbVersion = DBVersion.DB2;
            } else if (Strings.containsIgnoreCase(dbProductName, "SQL SERVER")) {
                dbVersion = DBVersion.SQL_Server;
            }

            return new DBProductInfo(dbProductName, dbProductVersion, dbVersion);
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Creates a {@code HikariDataSource} with the specified database connection details.
     * HikariCP is a high-performance JDBC connection pool known for its speed and reliability.
     * This method provides a convenient way to configure a connection pool with basic settings.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Create a DataSource for a MySQL database
     * DataSource dataSource = JdbcUtil.createHikariDataSource(
     *     "jdbc:mysql://localhost:3306/mydatabase",
     *     "db_user",
     *     "db_password"
     * );
     *
     * // The returned DataSource can be used with other methods in JdbcUtil
     * try (Connection connection = dataSource.getConnection()) {
     *     // Use the connection...
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param url The JDBC URL for the database connection (e.g., "jdbc:mysql://localhost:3306/mydb").
     * @param user The username for database authentication.
     * @param password The password for database authentication.
     * @return A {@code javax.sql.DataSource} instance configured with HikariCP.
     * @throws RuntimeException If the HikariCP library is not available in the classpath or if the configuration fails.
     * @see #createHikariDataSource(String, String, String, int, int)
     * @see com.zaxxer.hikari.HikariDataSource
     */
    public static javax.sql.DataSource createHikariDataSource(final String url, final String user, final String password) {
        try {
            final com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
            config.setJdbcUrl(url);
            config.setUsername(user);
            config.setPassword(password);

            return new com.zaxxer.hikari.HikariDataSource(config);
        } catch (final Exception e) {
            throw ExceptionUtil.toRuntimeException(e, true);
        }
    }

    /**
     * Creates a {@code HikariDataSource} with specified connection details and pool size configuration.
     * This method allows for more advanced tuning of the connection pool's performance characteristics.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Create a DataSource with custom pool size for a PostgreSQL database
     * DataSource dataSource = JdbcUtil.createHikariDataSource(
     *     "jdbc:postgresql://localhost:5432/mydatabase",
     *     "db_user",
     *     "db_password",
     *     5,  // Minimum number of idle connections
     *     20  // Maximum number of connections in the pool
     * );
     *
     * // The DataSource is ready to be used for acquiring connections
     * try (Connection connection = dataSource.getConnection()) {
     *     // Perform database operations...
     * } catch (SQLException e) {
     *     // Handle potential exceptions
     * }
     * }</pre>
     *
     * @param url The JDBC URL for the database connection.
     * @param user The username for database authentication.
     * @param password The password for database authentication.
     * @param minIdle The minimum number of idle connections that HikariCP tries to maintain in the pool.
     * @param maxPoolSize The maximum number of connections that can be in the pool, including both idle and in-use connections.
     * @return A {@code javax.sql.DataSource} instance configured with HikariCP and custom pool settings.
     * @throws RuntimeException If the HikariCP library is not available in the classpath or if the configuration fails.
     * @see #createHikariDataSource(String, String, String)
     * @see com.zaxxer.hikari.HikariConfig
     */
    public static javax.sql.DataSource createHikariDataSource(final String url, final String user, final String password, final int minIdle,
            final int maxPoolSize) {
        try {
            final com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
            config.setJdbcUrl(url);
            config.setUsername(user);
            config.setPassword(password);
            config.setMinimumIdle(minIdle);
            config.setMaximumPoolSize(maxPoolSize);

            return new com.zaxxer.hikari.HikariDataSource(config);
        } catch (final Exception e) {
            throw ExceptionUtil.toRuntimeException(e, true);
        }
    }

    /**
     * Creates a C3P0 {@code ComboPooledDataSource} with the specified database connection details.
     * C3P0 is a mature and highly configurable JDBC connection pooling library.
     * This method is marked as {@code @Beta}, indicating it is subject to change.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Create a C3P0 DataSource for a PostgreSQL database
     * DataSource dataSource = JdbcUtil.createC3p0DataSource(
     *     "jdbc:postgresql://localhost:5432/mydatabase",
     *     "db_user",
     *     "db_password"
     * );
     *
     * // Use the DataSource to get a connection
     * try (Connection connection = dataSource.getConnection()) {
     *     // ...
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param url The JDBC URL for the database connection.
     * @param user The username for database authentication.
     * @param password The password for database authentication.
     * @return A {@code javax.sql.DataSource} instance configured with C3P0.
     * @throws RuntimeException If the C3P0 library is not available in the classpath or if configuration fails.
     * @see #createC3p0DataSource(String, String, String, int, int)
     * @see com.mchange.v2.c3p0.ComboPooledDataSource
     */
    @Beta
    public static javax.sql.DataSource createC3p0DataSource(final String url, final String user, final String password) {
        try {
            final com.mchange.v2.c3p0.ComboPooledDataSource cpds = new com.mchange.v2.c3p0.ComboPooledDataSource();
            cpds.setJdbcUrl(url);
            cpds.setUser(user);
            cpds.setPassword(password);

            return cpds;
        } catch (final Exception e) {
            throw ExceptionUtil.toRuntimeException(e, true);
        }
    }

    /**
     * Creates a C3P0 {@code ComboPooledDataSource} with specified connection details and pool size configuration.
     * This method allows for tuning the connection pool's size to match application requirements.
     * This method is marked as {@code @Beta}, indicating it is subject to change.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Create a C3P0 DataSource with custom pool size for an Oracle database
     * DataSource dataSource = JdbcUtil.createC3p0DataSource(
     *     "jdbc:oracle:thin:@localhost:1521:xe",
     *     "db_user",
     *     "db_password",
     *     3,  // Minimum number of connections in the pool
     *     15  // Maximum number of connections in the pool
     * );
     *
     * // The DataSource can now be used to obtain pooled connections
     * try (Connection connection = dataSource.getConnection()) {
     *     // ...
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param url The JDBC URL for the database connection.
     * @param user The username for database authentication.
     * @param password The password for database authentication.
     * @param minPoolSize The minimum number of connections the pool will maintain.
     * @param maxPoolSize The maximum number of connections the pool will allow.
     * @return A {@code javax.sql.DataSource} instance configured with C3P0 and custom pool settings.
     * @throws RuntimeException If the C3P0 library is not available in the classpath or if configuration fails.
     * @see #createC3p0DataSource(String, String, String)
     * @see com.mchange.v2.c3p0.ComboPooledDataSource
     */
    @Beta
    public static javax.sql.DataSource createC3p0DataSource(final String url, final String user, final String password, final int minPoolSize,
            final int maxPoolSize) {
        try {
            final com.mchange.v2.c3p0.ComboPooledDataSource cpds = new com.mchange.v2.c3p0.ComboPooledDataSource();
            cpds.setJdbcUrl(url);
            cpds.setUser(user);
            cpds.setPassword(password);
            cpds.setMinPoolSize(minPoolSize);
            cpds.setMaxPoolSize(maxPoolSize);
            return cpds;
        } catch (final Exception e) {
            throw ExceptionUtil.toRuntimeException(e, true);
        }
    }

    /**
     * Creates a new database {@link Connection} using the specified URL, username, and password.
     * This method automatically determines the appropriate JDBC driver based on the provided URL.
     * Note: This method creates a new, non-pooled connection. For applications requiring connection pooling,
     * it is recommended to use a {@code DataSource} (e.g., created by {@link #createHikariDataSource}).
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String jdbcUrl = "jdbc:mysql://localhost:3306/mydatabase";
     * String username = "root";
     * String password = "your_password";
     *
     * try (Connection connection = JdbcUtil.createConnection(jdbcUrl, username, password)) {
     *     // Perform database operations with the connection
     *     Statement statement = connection.createStatement();
     *     // ...
     * } catch (UncheckedSQLException | SQLException e) {
     *     // Handle exceptions
     * }
     * }</pre>
     *
     * @param url The JDBC URL for the database connection (e.g., "jdbc:mysql://localhost:3306/mydb").
     * @param user The username for database authentication.
     * @param password The password for database authentication.
     * @return A new {@link Connection} object.
     * @throws UncheckedSQLException If a database access error occurs or the driver cannot be determined from the URL.
     * @see #createConnection(String, String, String, String)
     * @see DriverManager#getConnection(String, String, String)
     */
    public static Connection createConnection(final String url, final String user, final String password) throws UncheckedSQLException {
        return createConnection(getDriverClassByUrl(url), url, user, password);
    }

    /**
     * Creates a new database {@link Connection} using an explicitly specified driver class.
     * This method is useful when the JDBC driver cannot be automatically determined from the URL
     * or when a specific driver version needs to be enforced.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String driver = "com.mysql.cj.jdbc.Driver";
     * String url = "jdbc:mysql://localhost:3306/mydatabase";
     * String user = "root";
     * String password = "your_password";
     *
     * try (Connection connection = JdbcUtil.createConnection(driver, url, user, password)) {
     *     // Use the connection for database operations
     * } catch (UncheckedSQLException | SQLException e) {
     *     // Handle connection errors
     * }
     * }</pre>
     *
     * @param driverClass The fully qualified name of the JDBC driver class (e.g., "com.mysql.cj.jdbc.Driver").
     * @param url The JDBC URL for the database connection.
     * @param user The username for database authentication.
     * @param password The password for database authentication.
     * @return A new {@link Connection} object.
     * @throws UncheckedSQLException If a database access error occurs or the specified driver class is not found.
     * @see #createConnection(Class, String, String, String)
     */
    public static Connection createConnection(final String driverClass, final String url, final String user, final String password)
            throws UncheckedSQLException {
        final Class<? extends Driver> cls = ClassUtil.forClass(driverClass);

        return createConnection(cls, url, user, password);
    }

    /**
     * Creates a new database {@link Connection} using a type-safe {@link Driver} class.
     * This method provides compile-time safety for specifying the JDBC driver and avoids potential
     * runtime errors from misspelled driver class names.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Example with the MySQL driver class
     * Class<? extends java.sql.Driver> driverClass = com.mysql.cj.jdbc.Driver.class;
     * String url = "jdbc:mysql://localhost:3306/mydatabase";
     * String user = "root";
     * String password = "your_password";
     *
     * try (Connection connection = JdbcUtil.createConnection(driverClass, url, user, password)) {
     *     // The connection is ready for use
     * } catch (UncheckedSQLException | SQLException e) {
     *     // Handle exceptions
     * }
     * }</pre>
     *
     * @param driverClass The JDBC {@link Driver} class (e.g., {@code com.mysql.cj.jdbc.Driver.class}).
     * @param url The JDBC URL for the database connection.
     * @param user The username for database authentication.
     * @param password The password for database authentication.
     * @return A new {@link Connection} object.
     * @throws UncheckedSQLException If a database access error occurs during connection creation.
     * @see #createConnection(String, String, String, String)
     * @see DriverManager#registerDriver(Driver)
     */
    public static Connection createConnection(final Class<? extends Driver> driverClass, final String url, final String user, final String password)
            throws UncheckedSQLException {
        try {
            DriverManager.registerDriver(N.newInstance(driverClass));

            return DriverManager.getConnection(url, user, password);
        } catch (final SQLException e) {
            throw new UncheckedSQLException("Failed to close create connection", e);
        }
    }

    /**
     * Returns the JDBC driver class corresponding to the provided database URL.
     * This method analyzes the URL pattern to determine which JDBC driver should be used.
     *
     * @param url the JDBC URL to analyze
     * @return the driver class corresponding to the URL, or {@code null} if not found
     */
    private static Class<? extends Driver> getDriverClassByUrl(final String url) {
        N.checkArgNotEmpty(url, cs.url);

        Class<? extends Driver> driverClass = null;
        // jdbc:mysql://localhost:3306/abacustest
        if (Strings.indexOfIgnoreCase(url, "mysql") >= 0) {
            driverClass = ClassUtil.forClass("com.mysql.cj.jdbc.Driver");
            // jdbc:postgresql://localhost:5432/abacustest
        } else if (Strings.indexOfIgnoreCase(url, "postgresql") >= 0) {
            driverClass = ClassUtil.forClass("org.postgresql.Driver");
            // jdbc:h2:hsql://<host>:<port>/<database>
        } else if (Strings.indexOfIgnoreCase(url, "h2") >= 0) {
            driverClass = ClassUtil.forClass("org.h2.Driver");
            // jdbc:hsqldb:hsql://localhost/abacustest
        } else if (Strings.indexOfIgnoreCase(url, "hsqldb") >= 0) {
            driverClass = ClassUtil.forClass("org.hsqldb.JDBCDriver");
            // url=jdbc:oracle:thin:@localhost:1521:abacustest
        } else if (Strings.indexOfIgnoreCase(url, "oracle") >= 0) {
            driverClass = ClassUtil.forClass("oracle.jdbc.driver.OracleDriver");
            // url=jdbc:sqlserver://localhost:1433;Database=abacustest
        } else if (Strings.indexOfIgnoreCase(url, "sqlserver") >= 0) {
            driverClass = ClassUtil.forClass("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            // jdbc:db2://localhost:50000/abacustest
        } else if (Strings.indexOfIgnoreCase(url, "db2") >= 0) {
            driverClass = ClassUtil.forClass("com.ibm.db2.jcc.DB2Driver");
        } else {
            throw new IllegalArgumentException(
                    "Can not identity the driver class by url: " + url + ". Only mysql, postgresql, hsqldb, sqlserver, oracle and db2 are supported currently");
        }
        return driverClass;
    }

    /**
     * Retrieves a {@link Connection} from the specified {@link javax.sql.DataSource}.
     * This method is aware of Spring-managed transactions. If a transaction is active,
     * it returns the connection associated with the current transaction. Otherwise, it
     * retrieves a new connection from the DataSource.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DataSource dataSource = ...; // Your configured DataSource
     * Connection connection = null;
     * try {
     *     connection = JdbcUtil.getConnection(dataSource);
     *     // Perform database operations
     *     try (Statement stmt = connection.createStatement()) {
     *         // ...
     *     }
     * } catch (UncheckedSQLException | SQLException e) {
     *     // Handle exceptions
     * } finally {
     *     // Always release the connection in a finally block
     *     JdbcUtil.releaseConnection(connection, dataSource);
     * }
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} from which to obtain a connection.
     * @return A {@link Connection} object.
     * @throws UncheckedSQLException If a database access error occurs.
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     * @see org.springframework.jdbc.datasource.DataSourceUtils#getConnection(javax.sql.DataSource)
     */
    public static Connection getConnection(final javax.sql.DataSource ds) throws UncheckedSQLException {
        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
            try {
                return org.springframework.jdbc.datasource.DataSourceUtils.getConnection(ds);
            } catch (final NoClassDefFoundError e) {
                isInSpring = false;

                try {
                    return ds.getConnection();
                } catch (final SQLException e1) {
                    throw new UncheckedSQLException(e1);
                }
            }
        } else {
            try {
                return ds.getConnection();
            } catch (final SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     * Releases the given {@link Connection} back to the {@link javax.sql.DataSource}.
     * This method correctly handles connections in Spring-managed transactions, ensuring that
     * connections are not prematurely closed. If no transaction is active, it closes the connection.
     * It is crucial to call this method in a {@code finally} block to prevent connection leaks.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DataSource dataSource = ...;
     * Connection connection = null;
     * try {
     *     connection = JdbcUtil.getConnection(dataSource);
     *     // ... perform database work
     * } finally {
     *     // This ensures the connection is always released, even if an error occurs.
     *     JdbcUtil.releaseConnection(connection, dataSource);
     * }
     * }</pre>
     *
     * @param conn The {@link Connection} to be released. Can be {@code null}, in which case the method does nothing.
     * @param ds The {@link javax.sql.DataSource} from which the connection was obtained.
     * @see #getConnection(javax.sql.DataSource)
     * @see org.springframework.jdbc.datasource.DataSourceUtils#releaseConnection(Connection, javax.sql.DataSource)
     */
    public static void releaseConnection(final Connection conn, final javax.sql.DataSource ds) {
        if (conn == null) {
            return;
        }

        if (isInSpring && ds != null && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
            try {
                org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(conn, ds);
            } catch (final NoClassDefFoundError e) {
                isInSpring = false;
                JdbcUtil.closeQuietly(conn);
            }
        } else {
            JdbcUtil.closeQuietly(conn);
        }
    }

    /**
     * Creates the close handler.
     *
     * @param conn The database Connection to release
     * @param ds The DataSource from which the connection was obtained
     * @return A Runnable that releases the connection back to the DataSource
     */
    static Runnable createCloseHandler(final Connection conn, final javax.sql.DataSource ds) {
        return () -> JdbcUtil.releaseConnection(conn, ds);
    }

    /**
     * Closes the specified {@link ResultSet}.
     * It is recommended to use a try-with-resources statement to manage {@code ResultSet} resources,
     * which automatically handles closing. However, if manual closing is necessary, this method
     * can be used in a {@code finally} block.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Recommended: try-with-resources
     * try (ResultSet rs = statement.executeQuery("SELECT * FROM users")) {
     *     while (rs.next()) {
     *         // ... process results
     *     }
     * } // rs is automatically closed here
     *
     * // Manual closing in a finally block
     * ResultSet rs = null;
     * try {
     *     rs = statement.executeQuery("SELECT * FROM users");
     *     // ...
     * } finally {
     *     JdbcUtil.close(rs);
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to close. If {@code null}, the method does nothing.
     * @throws UncheckedSQLException If a database access error occurs during closing.
     * @see #closeQuietly(ResultSet)
     */
    public static void close(final ResultSet rs) throws UncheckedSQLException {
        if (rs != null) {
            try {
                rs.close();
            } catch (final SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     * Closes the specified {@link ResultSet} and, optionally, its associated {@link Statement}.
     * This is useful when the {@code Statement} is created and used only for a single {@code ResultSet}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Statement stmt = connection.createStatement();
     * ResultSet rs = null;
     * try {
     *     rs = stmt.executeQuery("SELECT * FROM orders");
     *     // ... process results
     * } finally {
     *     // Closes both the ResultSet and the Statement
     *     JdbcUtil.close(rs, true);
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to close. If {@code null}, no action is taken.
     * @param closeStatement If {@code true}, the {@link Statement} that created the {@code ResultSet} will also be closed.
     * @throws UncheckedSQLException If a database access error occurs during closing.
     * @see #close(ResultSet, boolean, boolean)
     * @see #closeQuietly(ResultSet, boolean)
     */
    public static void close(final ResultSet rs, final boolean closeStatement) throws UncheckedSQLException {
        close(rs, closeStatement, false);
    }

    /**
     * Closes a {@link ResultSet}, and optionally the associated {@link Statement} and {@link Connection}.
     * This method is intended for use in scenarios where all three resources are managed together
     * and need to be closed simultaneously. Using a {@code DataSource} and {@link #releaseConnection}
     * is generally preferred over manual connection management.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // This is a low-level operation; using a DataSource is typically better.
     * Connection conn = JdbcUtil.createConnection(url, user, pass);
     * Statement stmt = conn.createStatement();
     * ResultSet rs = null;
     * try {
     *     rs = stmt.executeQuery("SELECT * FROM products");
     *     // ... process results
     * } finally {
     *     // Closes rs, stmt, and conn
     *     JdbcUtil.close(rs, true, true);
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to close. Can be {@code null}.
     * @param closeStatement If {@code true}, the {@link Statement} from the {@code ResultSet} is also closed.
     * @param closeConnection If {@code true}, the {@link Connection} from the {@code Statement} is also closed.
     *        This requires {@code closeStatement} to be {@code true}.
     * @throws IllegalArgumentException If {@code closeConnection} is {@code true} but {@code closeStatement} is {@code false}.
     * @throws UncheckedSQLException If a database access error occurs during closing.
     * @see #closeQuietly(ResultSet, boolean, boolean)
     */
    public static void close(final ResultSet rs, final boolean closeStatement, final boolean closeConnection)
            throws IllegalArgumentException, UncheckedSQLException {
        if (closeConnection && !closeStatement) {
            throw new IllegalArgumentException("'closeStatement' can't be false while 'closeConnection' is true");
        }

        if (rs == null) {
            return;
        }

        Connection conn = null;
        Statement stmt = null;

        try {
            if (closeStatement) {
                stmt = rs.getStatement();
            }

            if (closeConnection && stmt != null) {
                conn = stmt.getConnection();
            }
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            close(rs, stmt, conn);
        }
    }

    /**
     * Closes the specified {@link Statement}.
     * It is recommended to use a try-with-resources statement to manage {@code Statement} resources.
     * This method can be used in a {@code finally} block for manual resource management.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Recommended: try-with-resources
     * try (Statement stmt = connection.createStatement()) {
     *     stmt.executeUpdate("DELETE FROM users WHERE status = 'inactive'");
     * } // stmt is automatically closed here
     *
     * // Manual closing
     * Statement stmt = null;
     * try {
     *     stmt = connection.createStatement();
     *     // ...
     * } finally {
     *     JdbcUtil.close(stmt);
     * }
     * }</pre>
     *
     * @param stmt The {@link Statement} to close. If {@code null}, the method does nothing.
     * @throws UncheckedSQLException If a database access error occurs during closing.
     * @see #closeQuietly(Statement)
     */
    public static void close(final Statement stmt) throws UncheckedSQLException {
        if (stmt != null) {
            try {
                stmt.close();
            } catch (final SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     * Closes the specified {@link Connection}.
     *
     * @deprecated This method is deprecated because it directly closes the connection, which is often not the desired
     * behavior when using a {@link javax.sql.DataSource} that provides pooled connections.
     * Use {@link #releaseConnection(Connection, javax.sql.DataSource)} instead to correctly handle
     * pooled connections and integration with transaction managers.
     *
     * <p><b>Recommended Alternative:</b></p>
     * <pre>{@code
     * DataSource dataSource = ...;
     * Connection conn = null;
     * try {
     *     conn = JdbcUtil.getConnection(dataSource);
     *     // ... perform database operations
     * } finally {
     *     // Correctly releases the connection back to the pool or closes it.
     *     JdbcUtil.releaseConnection(conn, dataSource);
     * }
     * }</pre>
     *
     * @param conn The {@link Connection} to close. If {@code null}, the method does nothing.
     * @throws UncheckedSQLException If a database access error occurs during closing.
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     * @see #closeQuietly(Connection)
     */
    @Deprecated
    public static void close(final Connection conn) throws UncheckedSQLException {
        if (conn != null) {
            try {
                conn.close();
            } catch (final SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     * Closes the specified {@link ResultSet} and {@link Statement}.
     * Resources are closed in the correct order: {@code ResultSet} first, then {@code Statement}.
     * Using try-with-resources is generally preferred for managing these resources.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Recommended: separate try-with-resources
     * try (Statement stmt = connection.createStatement();
     *      ResultSet rs = stmt.executeQuery("SELECT * FROM users")) {
     *     // ... process results
     * } // Both rs and stmt are automatically closed.
     *
     * // Manual closing
     * Statement stmt = null;
     * ResultSet rs = null;
     * try {
     *     stmt = connection.createStatement();
     *     rs = stmt.executeQuery("SELECT * FROM users");
     *     // ...
     * } finally {
     *     JdbcUtil.close(rs, stmt);
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to close. Can be {@code null}.
     * @param stmt The {@link Statement} to close. Can be {@code null}.
     * @throws UncheckedSQLException If a database access error occurs during closing.
     * @see #closeQuietly(ResultSet, Statement)
     */
    public static void close(final ResultSet rs, final Statement stmt) throws UncheckedSQLException {
        try {
            if (rs != null) {
                rs.close();
            }
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            try {
                if (stmt != null) {
                    stmt.close();
                }
            } catch (final SQLException e) {
                throw new UncheckedSQLException(e); //NOSONAR
            }
        }
    }

    /**
     * Closes the specified {@link Statement} and {@link Connection}.
     * Resources are closed in order: {@code Statement} first, then {@code Connection}.
     * It is generally better to manage connections at a higher level, for example, by using
     * a {@code DataSource} and {@link #releaseConnection}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Manual resource management (less common)
     * Connection conn = null;
     * Statement stmt = null;
     * try {
     *     conn = JdbcUtil.createConnection(url, user, pass);
     *     stmt = conn.createStatement();
     *     // ...
     * } finally {
     *     JdbcUtil.close(stmt, conn);
     * }
     * }</pre>
     *
     * @param stmt The {@link Statement} to close. Can be {@code null}.
     * @param conn The {@link Connection} to close. Can be {@code null}.
     * @throws UncheckedSQLException If a database access error occurs during closing.
     * @see #closeQuietly(Statement, Connection)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static void close(final Statement stmt, final Connection conn) throws UncheckedSQLException {
        try {
            if (stmt != null) {
                stmt.close();
            }
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            try {
                if (conn != null) {
                    conn.close();
                }
            } catch (final SQLException e) {
                throw new UncheckedSQLException(e); //NOSONAR
            }
        }
    }

    /**
     * Closes the specified {@link ResultSet}, {@link Statement}, and {@link Connection}.
     * Resources are closed in the correct order: {@code ResultSet}, then {@code Statement}, then {@code Connection}.
     * This is a low-level utility, and modern JDBC programming often relies on try-with-resources
     * or connection pooling which automates this cleanup.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Connection conn = null;
     * Statement stmt = null;
     * ResultSet rs = null;
     * try {
     *     conn = JdbcUtil.createConnection(url, user, pass);
     *     stmt = conn.createStatement();
     *     rs = stmt.executeQuery("SELECT * FROM employees");
     *     // ... process results
     * } finally {
     *     // Closes all three resources in the correct order.
     *     JdbcUtil.close(rs, stmt, conn);
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to close. Can be {@code null}.
     * @param stmt The {@link Statement} to close. Can be {@code null}.
     * @param conn The {@link Connection} to close. Can be {@code null}.
     * @throws UncheckedSQLException If a database access error occurs during closing.
     * @see #closeQuietly(ResultSet, Statement, Connection)
     */
    public static void close(final ResultSet rs, final Statement stmt, final Connection conn) throws UncheckedSQLException {
        try {
            if (rs != null) {
                rs.close();
            }
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            try {
                if (stmt != null) {
                    stmt.close();
                }
            } catch (final SQLException e) {
                throw new UncheckedSQLException(e); //NOSONAR
            } finally {
                try {
                    if (conn != null) {
                        conn.close();
                    }
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e); //NOSONAR
                }
            }
        }
    }

    /**
     * Unconditionally closes a {@link ResultSet}, ignoring any {@code SQLException} that occurs.
     * This method is useful in {@code finally} blocks where you need to ensure a resource is
     * closed without handling potential exceptions from the close operation itself.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ResultSet rs = null;
     * try {
     *     rs = statement.executeQuery("SELECT * FROM users");
     *     // ... process data
     * } catch (SQLException e) {
     *     // handle query exception
     * } finally {
     *     // Ensures the ResultSet is closed, suppressing any close exceptions.
     *     JdbcUtil.closeQuietly(rs);
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to close. Can be {@code null}.
     * @see #close(ResultSet)
     */
    public static void closeQuietly(final ResultSet rs) {
        closeQuietly(rs, null, null);
    }

    /**
     * Unconditionally closes a {@link ResultSet} and, optionally, its associated {@link Statement}.
     * Any {@code SQLException} thrown during the closing of either resource is caught and ignored.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Statement stmt = null;
     * ResultSet rs = null;
     * try {
     *     stmt = connection.createStatement();
     *     rs = stmt.executeQuery("SELECT * FROM products");
     *     // ...
     * } catch (SQLException e) {
     *     // ...
     * } finally {
     *     // Quietly closes both the ResultSet and the Statement.
     *     JdbcUtil.closeQuietly(rs, true);
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to close. Can be {@code null}.
     * @param closeStatement If {@code true}, the {@link Statement} associated with the {@code ResultSet} will also be closed quietly.
     * @throws UncheckedSQLException If retrieving the {@code Statement} from the {@code ResultSet} fails.
     * @see #close(ResultSet, boolean)
     */
    public static void closeQuietly(final ResultSet rs, final boolean closeStatement) throws UncheckedSQLException {
        closeQuietly(rs, closeStatement, false);
    }

    /**
     * Unconditionally closes a {@link ResultSet} and, optionally, its associated {@link Statement} and {@link Connection}.
     * Any exceptions thrown during closing are caught and ignored. This is a low-level utility for ensuring
     * all resources are closed without needing nested {@code finally} blocks or complex error handling.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Connection conn = null;
     * Statement stmt = null;
     * ResultSet rs = null;
     * try {
     *     conn = ...;
     *     stmt = conn.createStatement();
     *     rs = stmt.executeQuery("SELECT * FROM logs");
     *     // ...
     * } catch (SQLException e) {
     *     // ...
     * } finally {
     *     // Quietly closes all three resources.
     *     JdbcUtil.closeQuietly(rs, {@code true}, true);
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to close. Can be {@code null}.
     * @param closeStatement If {@code true}, the associated {@link Statement} is also closed quietly.
     * @param closeConnection If {@code true}, the associated {@link Connection} is also closed quietly.
     *        Requires {@code closeStatement} to be {@code true}.
     * @throws IllegalArgumentException If {@code closeConnection} is {@code true} but {@code closeStatement} is {@code false}.
     * @see #close(ResultSet, boolean, boolean)
     */
    public static void closeQuietly(final ResultSet rs, final boolean closeStatement, final boolean closeConnection) throws IllegalArgumentException {
        if (closeConnection && !closeStatement) {
            throw new IllegalArgumentException("'closeStatement' can't be false while 'closeConnection' is true");
        }

        if (rs == null) {
            return;
        }

        Connection conn = null;
        Statement stmt = null;

        try {
            if (closeStatement) {
                stmt = rs.getStatement();
            }

            if (closeConnection && stmt != null) {
                conn = stmt.getConnection();
            }
        } catch (final SQLException e) {
            logger.error("Failed to get Statement or Connection by ResultSet", e);
        } finally {
            closeQuietly(rs, stmt, conn);
        }
    }

    /**
     * Unconditionally closes a {@link Statement}, ignoring any {@code SQLException} that occurs.
     * This is useful for ensuring a statement is closed in a {@code finally} block without
     * needing to handle potential closing exceptions.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Statement stmt = null;
     * try {
     *     stmt = connection.createStatement();
     *     // ... execute statement
     * } catch (SQLException e) {
     *     // ... handle execution exception
     * } finally {
     *     JdbcUtil.closeQuietly(stmt);
     * }
     * }</pre>
     *
     * @param stmt The {@link Statement} to close. Can be {@code null}.
     * @see #close(Statement)
     */
    public static void closeQuietly(final Statement stmt) {
        closeQuietly(null, stmt, null);
    }

    /**
     * Unconditionally closes a {@link Connection}, ignoring any {@code SQLException}.
     *
     * @deprecated This method is deprecated as it encourages manual connection management, which can be
     * error-prone. It's almost always better to use a {@link javax.sql.DataSource} and let it
     * manage the connection lifecycle, or use {@link #releaseConnection(Connection, javax.sql.DataSource)}
     * to safely return a connection to a pool.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Avoid this pattern; use a DataSource instead.
     * Connection conn = null;
     * try {
     *     conn = JdbcUtil.createConnection(url, user, pass);
     *     // ...
     * } catch (SQLException e) {
     *     // ...
     * } finally {
     *     JdbcUtil.closeQuietly(conn); // Deprecated practice
     * }
     * }</pre>
     *
     * @param conn The {@link Connection} to close. Can be {@code null}.
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     * @see #close(Connection)
     */
    @Deprecated
    public static void closeQuietly(final Connection conn) {
        closeQuietly(null, null, conn);
    }

    /**
     * Unconditionally closes a {@link ResultSet} and a {@link Statement}.
     * Any {@code SQLException} thrown during closing is ignored. This method is useful for
     * cleaning up resources in a {@code finally} block without complex error handling.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Statement stmt = null;
     * ResultSet rs = null;
     * try {
     *     stmt = connection.createStatement();
     *     rs = stmt.executeQuery("SELECT * FROM users");
     *     // ...
     * } catch (SQLException e) {
     *     // ...
     * } finally {
     *     JdbcUtil.closeQuietly(rs, stmt);
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to close. Can be {@code null}.
     * @param stmt The {@link Statement} to close. Can be {@code null}.
     * @see #close(ResultSet, Statement)
     */
    public static void closeQuietly(final ResultSet rs, final Statement stmt) {
        closeQuietly(rs, stmt, null);
    }

    /**
     * Unconditionally closes a {@link Statement} and a {@link Connection}.
     * Any {@code SQLException} thrown during closing is ignored. This is primarily for cleanup
     * in {@code finally} blocks when not using a {@code DataSource}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Connection conn = null;
     * Statement stmt = null;
     * try {
     *     conn = JdbcUtil.createConnection(url, user, pass);
     *     stmt = conn.createStatement();
     *     // ...
     * } catch (SQLException e) {
     *     // ...
     * } finally {
     *     JdbcUtil.closeQuietly(stmt, conn);
     * }
     * }</pre>
     *
     * @param stmt The {@link Statement} to close. Can be {@code null}.
     * @param conn The {@link Connection} to close. Can be {@code null}.
     * @see #close(Statement, Connection)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static void closeQuietly(final Statement stmt, final Connection conn) {
        closeQuietly(null, stmt, conn);
    }

    /**
     * Unconditionally closes a {@link ResultSet}, a {@link Statement}, and a {@link Connection}.
     * Any {@code SQLException} thrown during the closing of any of these resources is ignored.
     * This is a convenience method for resource cleanup in a {@code finally} block.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Connection conn = null;
     * Statement stmt = null;
     * ResultSet rs = null;
     * try {
     *     conn = ...;
     *     stmt = conn.createStatement();
     *     rs = stmt.executeQuery("SELECT * FROM departments");
     *     // ...
     * } catch (SQLException e) {
     *     // ...
     * } finally {
     *     JdbcUtil.closeQuietly(rs, stmt, conn);
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to close. Can be {@code null}.
     * @param stmt The {@link Statement} to close. Can be {@code null}.
     * @param conn The {@link Connection} to close. Can be {@code null}.
     * @see #close(ResultSet, Statement, Connection)
     */
    public static void closeQuietly(final ResultSet rs, final Statement stmt, final Connection conn) {
        if (rs != null) {
            try {
                rs.close();
            } catch (final Exception e) {
                logger.error("Failed to close ResultSet", e);
            }
        }

        if (stmt != null) {
            try {
                stmt.close();
            } catch (final Exception e) {
                logger.error("Failed to close Statement", e);
            }
        }

        if (conn != null) {
            try {
                conn.close();
            } catch (final Exception e) {
                logger.error("Failed to close Connection", e);
            }
        }
    }

    /**
     * Skips a specified number of rows in a {@link ResultSet}.
     * This method attempts to move the cursor forward by the given number of rows.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ResultSet rs = statement.executeQuery("SELECT * FROM users");
     * // Skip the first 10 users
     * int skippedRows = JdbcUtil.skip(rs, 10);
     *
     * if (skippedRows == 10) {
     *     // Now processing from the 11th user
     *     if (rs.next()) {
     *         // ...
     *     }
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to skip rows in.
     * @param n The number of rows to skip.
     * @return The number of rows actually skipped, which may be less than {@code n} if the end of the
     *         {@code ResultSet} is reached.
     * @throws SQLException If a database access error occurs or the result set type is {@code TYPE_FORWARD_ONLY}.
     * @see #skip(ResultSet, long)
     */
    public static int skip(final ResultSet rs, final int n) throws SQLException {
        return skip(rs, (long) n);
    }

    private static final Set<Class<?>> resultSetClassNotSupportAbsolute = ConcurrentHashMap.newKeySet();

    /**
     * Skips a specified number of rows in a {@link ResultSet}, supporting a {@code long} count.
     * This method efficiently moves the cursor forward. It attempts to use {@link ResultSet#absolute(int)}
     * for scrollable result sets and falls back to manual iteration for forward-only result sets or
     * when {@code absolute()} is not supported by the driver.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ResultSet rs = statement.executeQuery("SELECT * FROM event_log");
     * long largeNumberOfRowsToSkip = 100000L;
     * int skipped = JdbcUtil.skip(rs, largeNumberOfRowsToSkip);
     *
     * System.out.println("Skipped " + skipped + " rows.");
     *
     * // Start processing from the next row
     * while (rs.next()) {
     *     // ...
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to skip rows in.
     * @param n The number of rows to skip.
     * @return The number of rows actually skipped.
     * @throws SQLException If a database access error occurs.
     * @see ResultSet#absolute(int)
     */
    public static int skip(final ResultSet rs, long n) throws SQLException {
        if (n <= 0) {
            return 0;
        } else if (n == 1) {
            return rs.next() ? 1 : 0;
        } else {
            final int currentRow = rs.getRow();

            if ((n > Integer.MAX_VALUE) || (n > Integer.MAX_VALUE - currentRow
                    || (resultSetClassNotSupportAbsolute.size() > 0 && resultSetClassNotSupportAbsolute.contains(rs.getClass())))) {
                while (n-- > 0L && rs.next()) {
                    // continue.
                }
            } else {
                try {
                    rs.absolute((int) n + currentRow);
                } catch (final SQLException e) {
                    while (n-- > 0L && rs.next()) {
                        // continue.
                    }

                    resultSetClassNotSupportAbsolute.add(rs.getClass());
                }
            }

            return rs.getRow() - currentRow;
        }
    }

    /**
     * Returns the number of columns in a {@link ResultSet}.
     * This is a convenience method that retrieves the column count from the {@link ResultSetMetaData}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ResultSet rs = statement.executeQuery("SELECT id, name, email FROM users");
     * int count = JdbcUtil.getColumnCount(rs);
     * System.out.println("The ResultSet has " + count + " columns."); // Prints 3
     * }</pre>
     *
     * @param rs The {@link ResultSet} to get the column count from.
     * @return The number of columns in the result set.
     * @throws SQLException If a database access error occurs.
     * @see ResultSet#getMetaData()
     * @see ResultSetMetaData#getColumnCount()
     */
    public static int getColumnCount(final ResultSet rs) throws SQLException {
        return rs.getMetaData().getColumnCount();
    }

    /**
     * Returns an ordered list of column names for a specified table.
     * This method retrieves metadata by executing a query that returns no rows (e.g., {@code SELECT * FROM ... WHERE 1 > 2}).
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection connection = dataSource.getConnection()) {
     *     List<String> userColumns = JdbcUtil.getColumnNameList(connection, "users");
     *     System.out.println("Columns in 'users' table: " + userColumns);
     *     // Example Output: [id, first_name, last_name, email, registration_date]
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use.
     * @param tableName The name of the table for which to retrieve column names.
     * @return A {@link List} of column names in the order they are defined in the table.
     * @throws SQLException If a database access error occurs or the table does not exist.
     * @see #getColumnLabelList(ResultSet)
     */
    public static List<String> getColumnNameList(final Connection conn, final String tableName) throws SQLException {
        final String query = "SELECT * FROM " + tableName + " WHERE 1 > 2";
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            stmt = prepareStatement(conn, query);
            rs = executeQuery(stmt);

            final ResultSetMetaData metaData = rs.getMetaData();
            final int columnCount = metaData.getColumnCount();
            final List<String> columnNameList = new ArrayList<>(columnCount);

            for (int i = 1, n = columnCount + 1; i < n; i++) {
                columnNameList.add(metaData.getColumnName(i));
            }

            return columnNameList;
        } finally {
            closeQuietly(rs, stmt);
        }
    }

    /**
     * Returns an ordered list of column labels from a {@link ResultSet}.
     * Column labels are the display names for columns, which can be aliases assigned in the SQL query.
     * If a column has no explicit label, its name is returned instead.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Statement stmt = connection.createStatement();
     *      ResultSet rs = stmt.executeQuery("SELECT user_id AS 'User ID', user_name AS 'User Name' FROM users")) {
     *
     *     List<String> labels = JdbcUtil.getColumnLabelList(rs);
     *     System.out.println(labels); // Output: [User ID, User Name]
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} from which to retrieve column labels.
     * @return A {@link List} of column labels in the order they appear in the {@code ResultSet}.
     * @throws SQLException If a database access error occurs.
     * @see #getColumnNameList(Connection, String)
     * @see ResultSetMetaData#getColumnLabel(int)
     */
    public static List<String> getColumnLabelList(final ResultSet rs) throws SQLException {
        final ResultSetMetaData metaData = rs.getMetaData();
        final int columnCount = metaData.getColumnCount();
        final List<String> labelList = new ArrayList<>(columnCount);

        for (int i = 1, n = columnCount + 1; i < n; i++) {
            labelList.add(getColumnLabel(metaData, i));
        }

        return labelList;
    }

    /**
     * Returns the column label for a specified column index from {@link ResultSetMetaData}.
     * This method prioritizes the column label, but falls back to the column name if the label is {@code null} or empty.
     * This is useful for consistently getting a meaningful name for a column, especially when aliases are used.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ResultSetMetaData metaData = rs.getMetaData();
     * // For a query like "SELECT first_name AS name FROM users"
     * String label = JdbcUtil.getColumnLabel(metaData, 1); // Returns "name"
     *
     * // For a query like "SELECT first_name FROM users"
     * String sameLabel = JdbcUtil.getColumnLabel(metaData, 1); // Returns "first_name"
     * }</pre>
     *
     * @param rsmd The {@link ResultSetMetaData} to get the label from.
     * @param columnIndex The 1-based index of the column.
     * @return The column label if available; otherwise, the column name.
     * @throws SQLException If a database access error occurs.
     * @see ResultSetMetaData#getColumnLabel(int)
     * @see ResultSetMetaData#getColumnName(int)
     */
    public static String getColumnLabel(final ResultSetMetaData rsmd, final int columnIndex) throws SQLException {
        final String result = rsmd.getColumnLabel(columnIndex);

        return Strings.isEmpty(result) ? rsmd.getColumnName(columnIndex) : result;
    }

    /**
     * Returns the 1-based index of a column in a {@link ResultSet} given its name.
     * The search for the column is case-insensitive and checks both column labels (aliases) and actual column names.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // For a query: "SELECT user_id, user_name AS name FROM users"
     * ResultSet rs = ...;
     *
     * int indexByName = JdbcUtil.getColumnIndex(rs, "user_id"); // Returns 1
     * int indexByLabel = JdbcUtil.getColumnIndex(rs, "name");    // Returns 2
     * int caseInsensitiveIndex = JdbcUtil.getColumnIndex(rs, "USERNAME"); // Still returns 2 if "user_name" is the original name
     * int notFoundIndex = JdbcUtil.getColumnIndex(rs, "email"); // Returns -1
     * }</pre>
     *
     * @param resultSet The {@link ResultSet} to search within.
     * @param columnName The name or label of the column to find.
     * @return The 1-based index of the column, or -1 if not found.
     * @throws SQLException If a database access error occurs.
     * @see #getColumnIndex(ResultSetMetaData, String)
     */
    public static int getColumnIndex(final ResultSet resultSet, final String columnName) throws SQLException {
        return getColumnIndex(resultSet.getMetaData(), columnName);
    }

    /**
     * Returns the 1-based index of a column from {@link ResultSetMetaData} given its name.
     * This method performs a case-insensitive search, checking column labels first, then column names.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ResultSetMetaData metaData = rs.getMetaData();
     * // For a query: "SELECT user_id, user_name AS name FROM users"
     *
     * int indexByLabel = JdbcUtil.getColumnIndex(metaData, "name"); // Returns 2
     * int indexByName = JdbcUtil.getColumnIndex(metaData, "user_id"); // Returns 1
     * int notFoundIndex = JdbcUtil.getColumnIndex(metaData, "address"); // Returns -1
     * }</pre>
     *
     * @param rsmd The {@link ResultSetMetaData} to search within.
     * @param columnName The name or label of the column to find.
     * @return The 1-based index of the column, or -1 if not found.
     * @throws SQLException If a database access error occurs.
     * @see #getColumnIndex(ResultSet, String)
     */
    public static int getColumnIndex(final ResultSetMetaData rsmd, final String columnName) throws SQLException {
        final int columnCount = rsmd.getColumnCount();

        String columnLabel = null;

        for (int columnIndex = 1; columnIndex <= columnCount; columnIndex++) {
            columnLabel = rsmd.getColumnLabel(columnIndex);

            if (columnLabel != null && columnLabel.equalsIgnoreCase(columnName)) {
                return columnIndex;
            }

            columnLabel = rsmd.getColumnName(columnIndex);

            if (columnLabel != null && columnLabel.equalsIgnoreCase(columnName)) {
                return columnIndex;
            }
        }

        return -1;
    }

    @FunctionalInterface
    interface ColumnConverterByIndex {
        Object apply(ResultSet rs, int columnIndex, Object columnValue) throws SQLException;
    }

    @FunctionalInterface
    interface ColumnConverterByLabel {
        Object apply(ResultSet rs, String columnLabel, Object columnValue) throws SQLException;
    }

    static final Throwables.Function<Object, java.sql.Timestamp, SQLException> oracleTimestampToJavaTimestamp = obj -> ((oracle.sql.Datum) obj)
            .timestampValue();

    static final Throwables.Function<Object, java.sql.Date, SQLException> oracleTimestampToJavaDate = obj -> ((oracle.sql.Datum) obj).dateValue();

    private static final ObjectPool<Class<?>, Tuple2<ColumnConverterByIndex, ColumnConverterByLabel>> columnConverterPool = new ObjectPool<>(128);

    private static final Function<Object, Tuple2<ColumnConverterByIndex, ColumnConverterByLabel>> columnConverterGetter = ret -> {
        Tuple2<ColumnConverterByIndex, ColumnConverterByLabel> converterTP = columnConverterPool.get(ret.getClass());

        if (converterTP == null) {
            final Class<?> cls = ret.getClass();
            final String className = cls.getName();

            if ("oracle.sql.TIMESTAMP".equals(className)) {
                converterTP = Tuple.of((rs, columnIndex, val) -> ((oracle.sql.Datum) val).timestampValue(),
                        (rs, columnLabel, val) -> ((oracle.sql.Datum) val).timestampValue());
            } else if ("oracle.sql.TIMESTAMPTZ".equals(className) || "oracle.sql.TIMESTAMPLTZ".equals(className)) {
                converterTP = Tuple.of((rs, columnIndex, val) -> ((oracle.sql.Datum) val).timestampValue(), // ((oracle.sql.TIMESTAMPTZ) val).zonedDateTimeValue(),
                        (rs, columnLabel, val) -> ((oracle.sql.Datum) val).timestampValue()); // ((oracle.sql.TIMESTAMPTZ) val).zonedDateTimeValue());
            } else if (className.startsWith("oracle.sql.DATE")) {
                converterTP = Tuple.of((rs, columnIndex, val) -> {
                    final String metaDataClassName = rs.getMetaData().getColumnClassName(columnIndex);

                    if ("java.sql.Timestamp".equals(metaDataClassName) || "oracle.sql.TIMESTAMP".equals(metaDataClassName)) {
                        return rs.getTimestamp(columnIndex);
                    } else {
                        return rs.getDate(columnIndex);
                    }
                }, (rs, columnLabel, val) -> {
                    final ResultSetMetaData metaData = rs.getMetaData();
                    final int columnIndex = getColumnIndex(metaData, columnLabel);
                    final String metaDataClassName = metaData.getColumnClassName(columnIndex);

                    if ("java.sql.Timestamp".equals(metaDataClassName) || "oracle.sql.TIMESTAMP".equals(metaDataClassName)) {
                        return rs.getTimestamp(columnIndex);
                    } else {
                        return rs.getDate(columnIndex);
                    }
                });
            } else if (ret instanceof java.sql.Date) {
                converterTP = Tuple.of((rs, columnIndex, val) -> {
                    final String metaDataClassName = rs.getMetaData().getColumnClassName(columnIndex);

                    if ("java.sql.Timestamp".equals(metaDataClassName)) {
                        return rs.getTimestamp(columnIndex);
                    }

                    return val;
                }, (rs, columnLabel, val) -> {
                    final ResultSetMetaData metaData = rs.getMetaData();
                    final int columnIndex = getColumnIndex(metaData, columnLabel);
                    final String metaDataClassName = metaData.getColumnClassName(columnIndex);

                    if ("java.sql.Timestamp".equals(metaDataClassName)) {
                        return rs.getTimestamp(columnIndex);
                    }

                    return val;
                });
            } else {
                converterTP = Tuple.of((rs, columnIndex, val) -> val, (rs, columnLabel, val) -> val);
            }

            columnConverterPool.put(cls, converterTP);
        }

        return converterTP;
    };

    private static final ColumnConverterByIndex columnConverterByIndex = (rs, columnIndex, val) -> columnConverterGetter.apply(val)._1.apply(rs, columnIndex,
            val);

    private static final ColumnConverterByLabel columnConverterByLabel = (rs, columnLabel, val) -> columnConverterGetter.apply(val)._2.apply(rs, columnLabel,
            val);

    /**
     * Retrieves the value of a specified column in the current row of a {@link ResultSet}.
     * This method provides special handling for data types like {@link Blob}, {@link Clob},
     * and database-specific date/time types (e.g., Oracle's {@code TIMESTAMP}) to ensure
     * they are converted to standard Java types.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (ResultSet rs = stmt.executeQuery("SELECT id, name, profile_picture_blob FROM users")) {
     *     while (rs.next()) {
     *         long id = (long) JdbcUtil.getColumnValue(rs, 1);
     *         String name = (String) JdbcUtil.getColumnValue(rs, 2);
     *         byte[] profilePic = (byte[]) JdbcUtil.getColumnValue(rs, 3); // Blob is converted to byte[]
     *         // ...
     *     }
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} from which to retrieve the value.
     * @param columnIndex The 1-based index of the column.
     * @return The column value as a standard Java object. {@link Blob} is returned as {@code byte[]},
     *         {@link Clob} as {@code String}.
     * @throws SQLException If a database access error occurs.
     * @see #getColumnValue(ResultSet, String)
     */
    public static Object getColumnValue(final ResultSet rs, final int columnIndex) throws SQLException {
        return getColumnValue(rs, columnIndex, true);
    }

    /**
     * Retrieves the value of the specified column in the current row of the given ResultSet.
     * This method also checks the data type of the column value if specified.
     *
     * @param rs The ResultSet from which to retrieve the column value
     * @param columnIndex The index of the column to retrieve, starting from 1
     * @param checkDateType Whether to check the data type of the column value
     * @return The value of the specified column in the current row of the ResultSet
     * @throws SQLException If a SQL exception occurs while retrieving the column value
     */
    static Object getColumnValue(final ResultSet rs, final int columnIndex, final boolean checkDateType) throws SQLException {
        // Copied from JdbcUtils#getResultSetValue(ResultSet, int) in SpringJdbc under Apache License, Version 2.0.

        Object ret = rs.getObject(columnIndex);

        if (ret == null || ret instanceof String || ret instanceof Number || ret instanceof java.sql.Timestamp || ret instanceof Boolean) {
            return ret;
        }

        if (ret instanceof final Blob blob) {
            try {
                ret = blob.getBytes(1, (int) blob.length());
            } finally {
                blob.free();
            }
        } else if (ret instanceof final Clob clob) {
            try {
                ret = clob.getSubString(1, (int) clob.length());
            } finally {
                clob.free();
            }
        } else if (checkDateType && !(rs instanceof ResultSetProxy)) {
            ret = columnConverterByIndex.apply(rs, columnIndex, ret);
        }

        return ret;
    }

    /**
     * Retrieves the value of a specified column in the current row of a {@link ResultSet} by its label.
     * This method is deprecated because looking up a column by its label/name on every row can be inefficient.
     * It is recommended to look up column indices once and then use {@link #getColumnValue(ResultSet, int)}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Using the deprecated method (less efficient)
     * try (ResultSet rs = stmt.executeQuery("SELECT id, user_name AS name FROM users")) {
     *     while (rs.next()) {
     *         long id = (long) JdbcUtil.getColumnValue(rs, "id");
     *         String name = (String) JdbcUtil.getColumnValue(rs, "name");
     *     }
     * }
     *
     * // Recommended approach
     * try (ResultSet rs = stmt.executeQuery("SELECT id, user_name AS name FROM users")) {
     *     int idIndex = JdbcUtil.getColumnIndex(rs, "id");
     *     int nameIndex = JdbcUtil.getColumnIndex(rs, "name");
     *     while (rs.next()) {
     *         long id = (long) JdbcUtil.getColumnValue(rs, idIndex);
     *         String name = (String) JdbcUtil.getColumnValue(rs, nameIndex);
     *     }
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} from which to retrieve the value.
     * @param columnLabel The label of the column to retrieve.
     * @return The value of the specified column.
     * @throws SQLException If a database access error occurs.
     * @deprecated Use {@link #getColumnValue(ResultSet, int)} with a cached column index for better performance.
     * @see #getColumnValue(ResultSet, int)
     * @see #getColumnIndex(ResultSet, String)
     */
    @Deprecated
    public static Object getColumnValue(final ResultSet rs, final String columnLabel) throws SQLException {
        return getColumnValue(rs, columnLabel, true);
    }

    @Deprecated
    static Object getColumnValue(final ResultSet rs, final String columnLabel, final boolean checkDateType) throws SQLException {
        // Copied from JdbcUtils#getResultSetValue(ResultSet, int) in SpringJdbc under Apache License, Version 2.0.

        Object ret = rs.getObject(columnLabel);

        if (ret == null || ret instanceof String || ret instanceof Number || ret instanceof java.sql.Timestamp || ret instanceof Boolean) {
            return ret;
        }

        if (ret instanceof final Blob blob) {
            try {
                ret = blob.getBytes(1, (int) blob.length());
            } finally {
                blob.free();
            }
        } else if (ret instanceof final Clob clob) {
            try {
                ret = clob.getSubString(1, (int) clob.length());
            } finally {
                clob.free();
            }
        } else if (checkDateType && !(rs instanceof ResultSetProxy)) {
            ret = columnConverterByLabel.apply(rs, columnLabel, ret);
        }

        return ret;
    }

    /**
     * Retrieves all values from a single column of a {@link ResultSet} and returns them as a list.
     * This method iterates through the entire {@code ResultSet}, extracts the value from the specified
     * column for each row, and collects them into a {@link List}. The {@code ResultSet} will be
     * fully consumed after this method is called.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get a list of all user emails from the 'users' table
     * try (Statement stmt = connection.createStatement();
     *      ResultSet rs = stmt.executeQuery("SELECT email FROM users")) {
     *
     *     List<String> emails = JdbcUtil.getAllColumnValues(rs, 1);
     *     System.out.println("All user emails: " + emails);
     * }
     * }</pre>
     *
     * @param <T> The expected type of the elements in the returned list.
     * @param rs The {@link ResultSet} to retrieve values from. It will be iterated to the end.
     * @param columnIndex The 1-based index of the column to retrieve.
     * @return A {@link List} containing all values from the specified column.
     * @throws SQLException If a database access error occurs.
     */
    public static <T> List<T> getAllColumnValues(final ResultSet rs, final int columnIndex) throws SQLException {
        // Copied from JdbcUtils#getResultSetValue(ResultSet, int) in SpringJdbc under Apache License, Version 2.0.

        final List<Object> result = new ArrayList<>();
        Object val = null;

        while (rs.next()) {
            val = rs.getObject(columnIndex);

            if (val == null) {
                result.add(val);
            } else if (val instanceof String || val instanceof Number || val instanceof java.sql.Timestamp || val instanceof Boolean) {
                result.add(val);

                while (rs.next()) {
                    result.add(rs.getObject(columnIndex));
                }
            } else if (val instanceof Blob blob) {
                try {
                    result.add(blob.getBytes(1, (int) blob.length()));
                    blob.free();
                    blob = null;

                    while (rs.next()) {
                        blob = rs.getBlob(columnIndex);
                        result.add(blob.getBytes(1, (int) blob.length()));
                        blob.free();
                        blob = null;
                    }
                } finally {
                    if (blob != null) {
                        blob.free();
                    }
                }
            } else if (val instanceof Clob clob) {
                try {
                    result.add(clob.getSubString(1, (int) clob.length()));
                    clob.free();
                    clob = null;

                    while (rs.next()) {
                        clob = rs.getClob(columnIndex);
                        result.add(clob.getSubString(1, (int) clob.length()));
                        clob.free();
                        clob = null;
                    }
                } finally {
                    if (clob != null) {
                        clob.free();
                    }
                }
            } else {
                final String className = val.getClass().getName();

                if ("oracle.sql.TIMESTAMP".equals(className) || "oracle.sql.TIMESTAMPTZ".equals(className) || "oracle.sql.TIMESTAMPLTZ".equals(className)) {
                    do {
                        result.add(rs.getTimestamp(columnIndex));
                    } while (rs.next());
                } else if (className.startsWith("oracle.sql.DATE")) {
                    final ResultSetMetaData metaData = rs.getMetaData();
                    final String metaDataClassName = metaData.getColumnClassName(columnIndex);

                    if ("java.sql.Timestamp".equals(metaDataClassName) || "oracle.sql.TIMESTAMP".equals(metaDataClassName)) {
                        do {
                            result.add(rs.getTimestamp(columnIndex));
                        } while (rs.next());
                    } else {
                        do {
                            result.add(rs.getDate(columnIndex));
                        } while (rs.next());
                    }
                } else if (val instanceof java.sql.Date) {
                    final ResultSetMetaData metaData = rs.getMetaData();

                    if ("java.sql.Timestamp".equals(metaData.getColumnClassName(columnIndex))) {
                        do {
                            result.add(rs.getTimestamp(columnIndex));
                        } while (rs.next());
                    } else {
                        result.add(val);

                        while (rs.next()) {
                            result.add(rs.getDate(columnIndex));
                        }
                    }
                } else {
                    result.add(val);

                    while (rs.next()) {
                        result.add(rs.getDate(columnIndex));
                    }
                }
            }
        }

        return (List<T>) result;
    }

    /**
     * Retrieves all values from a single column of a {@link ResultSet} by its label and returns them as a list.
     * This method iterates through the entire {@code ResultSet}, finds the column index by its label,
     * extracts the value for each row, and collects them into a {@link List}. The {@code ResultSet}
     * will be fully consumed after this method is called.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get a list of all user names from the 'users' table using a column label
     * try (Statement stmt = connection.createStatement();
     *      ResultSet rs = stmt.executeQuery("SELECT user_name AS name FROM users")) {
     *
     *     List<String> names = JdbcUtil.getAllColumnValues(rs, "name");
     *     System.out.println("All user names: " + names);
     * }
     * }</pre>
     *
     * @param <T> The expected type of the elements in the returned list.
     * @param rs The {@link ResultSet} to retrieve values from. It will be iterated to the end.
     * @param columnLabel The label of the column to retrieve.
     * @return A {@link List} containing all values from the specified column.
     * @throws SQLException If a database access error occurs.
     * @throws IllegalArgumentException if the column label does not exist in the result set.
     */
    public static <T> List<T> getAllColumnValues(final ResultSet rs, final String columnLabel) throws SQLException {
        final int columnIndex = JdbcUtil.getColumnIndex(rs, columnLabel);

        if (columnIndex < 1) {
            throw new IllegalArgumentException("No column found by name: " + columnLabel + " in result set: " + JdbcUtil.getColumnLabelList(rs));
        }

        return getAllColumnValues(rs, columnIndex);
    }

    /**
     * Retrieves the value of a specified column in the current row of a {@link ResultSet} and converts it to the given target type.
     * This method simplifies type casting and conversion from JDBC types to Java types.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (ResultSet rs = stmt.executeQuery("SELECT id, balance, is_active FROM accounts")) {
     *     while (rs.next()) {
     *         // Retrieve values with explicit type conversion
     *         Long accountId = JdbcUtil.getColumnValue(rs, 1, Long.class);
     *         BigDecimal balance = JdbcUtil.getColumnValue(rs, 2, BigDecimal.class);
     *         boolean isActive = JdbcUtil.getColumnValue(rs, 3, boolean.class);
     *
     *         System.out.println("Account: " + accountId + ", Balance: " + balance + ", Active: " + isActive);
     *     }
     * }
     * }</pre>
     *
     * @param <T> The generic type of the desired value.
     * @param rs The {@link ResultSet} from which to retrieve the value.
     * @param columnIndex The 1-based index of the column.
     * @param targetClass The {@link Class} of the desired type {@code T}.
     * @return The column value, converted to the specified {@code targetClass}.
     * @throws SQLException If a database access error occurs.
     * @see #getColumnValue(ResultSet, String, Class)
     */
    public static <T> T getColumnValue(final ResultSet rs, final int columnIndex, final Class<? extends T> targetClass) throws SQLException {
        return N.<T> typeOf(targetClass).get(rs, columnIndex);
    }

    /**
     * Retrieves the value of a specified column by its label and converts it to the given target type.
     * This method is deprecated due to the performance overhead of looking up the column index for each row.
     * For better performance, cache the column index and use {@link #getColumnValue(ResultSet, int, Class)}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Deprecated usage:
     * try (ResultSet rs = stmt.executeQuery("SELECT id, balance FROM accounts")) {
     *     while (rs.next()) {
     *         Long id = JdbcUtil.getColumnValue(rs, "id", Long.class);
     *         BigDecimal balance = JdbcUtil.getColumnValue(rs, "balance", BigDecimal.class);
     *     }
     * }
     *
     * // Recommended alternative:
     * try (ResultSet rs = stmt.executeQuery("SELECT id, balance FROM accounts")) {
     *     int idIndex = rs.findColumn("id");
     *     int balanceIndex = rs.findColumn("balance");
     *     while (rs.next()) {
     *         Long id = JdbcUtil.getColumnValue(rs, idIndex, Long.class);
     *         BigDecimal balance = JdbcUtil.getColumnValue(rs, balanceIndex, BigDecimal.class);
     *     }
     * }
     * }</pre>
     *
     * @param <T> The generic type of the desired value.
     * @param rs The {@link ResultSet} from which to retrieve the value.
     * @param columnLabel The label of the column to retrieve.
     * @param targetClass The {@link Class} of the desired type {@code T}.
     * @return The column value, converted to the specified {@code targetClass}.
     * @throws SQLException If a database access error occurs.
     * @deprecated Use {@link #getColumnValue(ResultSet, int, Class)} with a cached column index for better performance.
     */
    @Deprecated
    public static <T> T getColumnValue(final ResultSet rs, final String columnLabel, final Class<? extends T> targetClass) throws SQLException {
        return N.<T> typeOf(targetClass).get(rs, columnLabel);
    }

    /**
     * Retrieves a mapping from database column names to entity field names for a given entity class.
     * This mapping is crucial for the automatic object-relational mapping (ORM) features,
     * allowing {@code JdbcUtil} to populate entity objects from a {@code ResultSet}.
     * The mapping is determined by analyzing the entity class's annotations (e.g., {@code @Column}) and naming conventions.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Assuming User class has fields like 'userId' and 'userName' mapped to columns 'user_id' and 'user_name'
     * ImmutableMap<String, String> columnToFieldMap = JdbcUtil.getColumn2FieldNameMap(User.class);
     *
     * System.out.println(columnToFieldMap.get("user_id"));   // Output: userId
     * System.out.println(columnToFieldMap.get("user_name")); // Output: userName
     * }</pre>
     *
     * @param entityClass The entity class to analyze for column-to-field mappings.
     * @return An {@link ImmutableMap} where keys are database column names and values are the corresponding entity field names.
     * @see com.landawn.abacus.annotation.Column
     * @see com.landawn.abacus.util.NamingPolicy
     */
    public static ImmutableMap<String, String> getColumn2FieldNameMap(final Class<?> entityClass) {
        return QueryUtil.getColumn2PropNameMap(entityClass);
    }

    /**
     * Determines the {@link SQLOperation} type from a given SQL string by analyzing its leading keyword.
     * This method trims the SQL string and performs a case-insensitive check for keywords like
     * {@code SELECT}, {@code UPDATE}, {@code INSERT}, {@code DELETE}, etc.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SQLOperation op1 = JdbcUtil.getSQLOperation("SELECT * FROM users");
     * // op1 is SQLOperation.SELECT
     *
     * SQLOperation op2 = JdbcUtil.getSQLOperation("  insert into accounts (id, name) values (1, 'test')");
     * // op2 is SQLOperation.INSERT
     *
     * SQLOperation op3 = JdbcUtil.getSQLOperation("CREATE TABLE new_table (...)");
     * // op3 might be SQLOperation.CREATE or SQLOperation.UNKNOWN depending on the implementation
     * }</pre>
     *
     * @param sql The SQL statement to analyze.
     * @return The identified {@link SQLOperation}, or {@link SQLOperation#UNKNOWN} if the operation cannot be determined.
     * @see SQLOperation
     */
    static SQLOperation getSQLOperation(final String sql) {
        if (Strings.startsWithIgnoreCase(sql.trim(), "select ")) {
            return SQLOperation.SELECT;
        } else if (Strings.startsWithIgnoreCase(sql.trim(), "update ")) {
            return SQLOperation.UPDATE;
        } else if (Strings.startsWithIgnoreCase(sql.trim(), "insert ")) {
            return SQLOperation.INSERT;
        } else if (Strings.startsWithIgnoreCase(sql.trim(), "delete ")) {
            return SQLOperation.DELETE;
        } else if (Strings.startsWithIgnoreCase(sql.trim(), "merge ")) {
            return SQLOperation.MERGE;
        } else {
            for (final SQLOperation so : SQLOperation.values()) {
                if (Strings.startsWithIgnoreCase(sql.trim(), so.name())) {
                    return so;
                }
            }
        }

        return SQLOperation.UNKNOWN;
    }

    static final Throwables.Consumer<PreparedStatement, SQLException> stmtSetterForBigQueryResult = stmt -> {
        // stmt.setFetchDirectionToForward().setFetchSize(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT);
        stmt.setFetchDirection(ResultSet.FETCH_FORWARD);

        if (stmt.getFetchSize() < JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT) {
            stmt.setFetchSize(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT);
        }
    };

    static final Throwables.Consumer<PreparedStatement, SQLException> stmtSetterForStream = stmt -> {
        stmt.setFetchDirection(ResultSet.FETCH_FORWARD);

        if (stmt.getFetchSize() < JdbcUtil.DEFAULT_FETCH_SIZE_FOR_STREAM) {
            stmt.setFetchSize(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_STREAM);
        }
    };

    /**
     * Prepares a SQL query for execution, returning a {@link PreparedQuery} object that can be reused
     * multiple times with different parameters({@code 'closeAfterExecution'} flag is set to {@code false} by calling {@code #closeAfterExecution(false)}).
     * This is more efficient than creating new prepared statements
     * for each execution, especially when the same query is executed repeatedly with different parameters.
     *
     * <p>This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.</p>
     *
     * <p><b>Key Features:</b></p>
     * <ul>
     *   <li>Automatic resource management when used with try-with-resources</li>
     *   <li>Support for method chaining with fluent parameter setting API</li>
     *   <li>Integration with transaction context for transactional operations</li>
     *   <li>Type-safe result mapping to Java objects, Lists, Maps, and more</li>
     *   <li>Stream support for memory-efficient processing of large result sets</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Basic query execution with single result
     * // If closeAfterExecution(false) is not called,
     * // there is no need to place the query instance in a try-catch block for closure.
     * Optional<User> user = JdbcUtil.prepareQuery(dataSource, "SELECT * FROM users WHERE id = ?")
     *                                  .setLong(1, userId).findFirst(User.class);
     * if (user.isPresent()) {
     *     System.out.println("Found: " + user.get().getName());
     * } 
     *
     * // Query with multiple parameters returning a list 
     * List<Order> orders = JdbcUtil.prepareQuery(dataSource, "SELECT * FROM orders WHERE customer_id = ? AND status = ? AND order_date > ?")
     *         .setLong(1, customerId)
     *         .setString(2, "PENDING")
     *         .setDate(3, Date.valueOf(lastWeek))
     *         .list(Order.class);
     *
     *  orders.forEach(order -> System.out.println("Order #" + order.getId())); 
     *
     * // Reusing the same PreparedQuery with different parameters
     * try (PreparedQuery query = JdbcUtil.prepareQuery(dataSource,
     *         "SELECT COUNT(*) FROM products WHERE category = ?").closeAfterExecution(false)) {
     *
     *     long electronicsCount = query.setString(1, "Electronics")
     *                                  .queryForSingleResult(Long.class)
     *                                  .orElse(0L);
     *
     *     long clothingCount = query.setString(1, "Clothing")
     *                               .queryForSingleResult(Long.class)
     *                               .orElse(0L);
     *
     *     System.out.println("Electronics: " + electronicsCount);
     *     System.out.println("Clothing: " + clothingCount);
     * }
     *
     * // Stream processing for large result sets
     * Stream<Transaction> stream = JdbcUtil.prepareQuery(dataSource,
     *         "SELECT * FROM transactions WHERE amount > ?")
     *     .setDouble(1, 1000.0)
     *     .stream(Transaction.class);
     *
     * double totalAmount = stream
     *     .filter(t -> t.getStatus().equals("COMPLETED"))
     *     .mapToDouble(Transaction::getAmount)
     *     .sum();
     *
     * System.out.println("Total: " + totalAmount);
     *
     * // Working with Dataset (column-oriented data structure)
     * Dataset dataset = JdbcUtil.prepareQuery(dataSource,
     *         "SELECT name, email, age FROM users WHERE department = ?")
     *     .setString(1, "Engineering")
     *     .query();
     * dataset.forEach(row -> {
     *     System.out.println(row.get("name") + " - " + row.get("email"));
     * });
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from, must not be {@code null}
     * @param sql The SQL query to prepare with optional {@code ?} parameter placeholders, must not be {@code null} or empty
     * @return A new {@link PreparedQuery} instance ready for parameter setting and execution
     * @throws IllegalArgumentException If {@code ds} or {@code sql} is {@code null} or empty
     * @throws SQLException If a database access error occurs during preparation
     * @see PreparedQuery
     * @see #prepareQuery(Connection, String)
     * @see #executeQuery(javax.sql.DataSource, String, Object...)
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(sql, cs.sql);

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareQuery(conn, sql).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a SQL query with support for retrieving auto-generated keys.
     * This is typically used for {@code INSERT} statements when you need to get the ID of the newly created row.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code  
     * Optional<Long> newUserId = JdbcUtil.prepareQuery(dataSource,
     *         "INSERT INTO users (first_name, last_name) VALUES (?, ?)", true).setString(1, "John")
     *                                     .setString(2, "Doe")
     *                                     .insert();
     *
     * if (newUserId.isPresent()) {
     *     System.out.println("New user created with ID: " + newUserId.get());
     * } 
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param sql The SQL statement to prepare (usually an {@code INSERT} statement).
     * @param autoGeneratedKeys A boolean flag; if {@code true}, the driver will be instructed to make generated keys available.
     * @return A new {@link PreparedQuery} instance configured to handle auto-generated keys.
     * @throws IllegalArgumentException If {@code ds} or {@code sql} is {@code null} or empty.
     * @throws SQLException If a database access error occurs or the driver does not support auto-generated keys.
     * @see Statement#RETURN_GENERATED_KEYS
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql, final boolean autoGeneratedKeys)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(sql, cs.sql);

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql, autoGeneratedKeys);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareQuery(conn, sql, autoGeneratedKeys).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a SQL query to make auto-generated keys available from specified column indexes.
     * This is an advanced feature for drivers that can return values from multiple auto-generated columns.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Assuming a table where a trigger generates a UUID and a timestamp on insert.
     * String insertSql = "INSERT INTO documents (content) VALUES (?)";
     *
     * Row row = JdbcUtil.prepareQuery(dataSource, insertSql, new int[]{1, 4}) // Assuming id is 1, created_at is 4
     *     .setString(1, "Some content...")
     *     .insert()
     *     .orElse(null);
     *
     * if (row != null) {
     *     String generatedUuid = row.getString(1);
     *     Timestamp creationTime = row.getTimestamp(2);
     *     System.out.println("New document created with UUID: " + generatedUuid + " at " + creationTime);
     * }
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param sql The SQL statement to prepare.
     * @param returnColumnIndexes An array of column indexes that should be made available for retrieval.
     * @return A new {@link PreparedQuery} instance.
     * @throws IllegalArgumentException If any of the arguments are {@code null} or empty.
     * @throws SQLException If a database access error occurs.
     * @see Connection#prepareStatement(String, int[])
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql, final int[] returnColumnIndexes)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotEmpty(returnColumnIndexes, cs.returnColumnIndexes);

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql, returnColumnIndexes);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareQuery(conn, sql, returnColumnIndexes).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a SQL query to make auto-generated keys available from specified column names.
     * This is an advanced feature for drivers that can return values from multiple auto-generated columns by name.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Assuming a table with an auto-incrementing 'id' and a 'created_at' column with a default value.
     * String query = "INSERT INTO logs (message) VALUES (?)";
     *
     * Row generatedValues = JdbcUtil.prepareQuery(dataSource, query, new String[]{"id", "created_at"})
     *     .setString(1, "User logged in")
     *     .insert()
     *     .orElse(null);
     *
     * if (generatedValues != null) {
     *     long newId = generatedValues.getLong("id");
     *     Timestamp creationTime = generatedValues.getTimestamp("created_at");
     *     System.out.println("New log entry created with ID: " + newId + " at " + creationTime);
     * }
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param sql The SQL statement to prepare.
     * @param returnColumnNames An array of column names that should be made available for retrieval.
     * @return A new {@link PreparedQuery} instance.
     * @throws IllegalArgumentException If any of the arguments are {@code null} or empty.
     * @throws SQLException If a database access error occurs.
     * @see Connection#prepareStatement(String, String[])
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql, final String[] returnColumnNames)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotEmpty(returnColumnNames, cs.returnColumnNames);

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql, returnColumnNames);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareQuery(conn, sql, returnColumnNames).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a SQL query using a custom {@link PreparedStatement} creator.
     * This method provides an extension point to customize the creation of the {@code PreparedStatement},
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Example: Creating a scrollable and updatable ResultSet
     * List<User> users = JdbcUtil.prepareQuery(dataSource, "SELECT * FROM users",
     *      (conn, sql) -> conn.prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE))
     *     .list(User.class);
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param sql The SQL statement to prepare.
     * @param stmtCreator A function that takes a {@link Connection} and a SQL string and returns a new {@link PreparedStatement}.
     * @return A new {@link PreparedQuery} instance wrapping the custom-created statement.
     * @throws IllegalArgumentException If any of the arguments are {@code null}.
     * @throws SQLException If a database access error occurs.
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql, stmtCreator);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareQuery(conn, sql, stmtCreator).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a SQL query using a provided {@link Connection}. 
     *
     * <p><b>Important:</b> This method does not manage the lifecycle of the connection. The caller MUST close the provided {@code Connection} to avoid resource leaks.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     // If closeAfterExecution(false) is not called,
     *     // there is no need to place the query instance in a try-catch block for closure.
     *     User user = JdbcUtil.prepareQuery(conn, "SELECT * FROM users WHERE id = ?").setLong(1, userId).findOnlyOne(User.class);
     *     // ...
     * } catch (SQLException e) {
     *     // Handle exceptions
     * } // Connection is automatically closed here
     * }</pre>
     *
     * @param conn The database {@link Connection} to use for the query. It will not be closed by this method.
     * @param sql The SQL query to prepare.
     * @return A new {@link PreparedQuery} instance.
     * @throws IllegalArgumentException If {@code conn} or {@code sql} is {@code null} or empty.
     * @throws SQLException If a database access error occurs.
     * @see #prepareQuery(javax.sql.DataSource, String)
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        return new PreparedQuery(prepareStatement(conn, sql));
    }

    /**
     * Prepares a SQL query with auto-generated keys support using a provided {@link Connection}.
     *
     * <p><b>Important:</b> This method does not manage the lifecycle of the connection. The caller MUST close the provided {@code Connection} to avoid resource leaks.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     // If closeAfterExecution(false) is not called,
     *     // there is no need to place query instance in a try-catch block for closure.
     *     Optional<Long> newId = JdbcUtil.prepareQuery(conn, "INSERT INTO users (name) VALUES (?)", true)
     *         .setString(1, "New User")
     *         .insert();
     *     System.out.println("New user ID: " + newId.orElse(null));
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use. It will not be closed by this method.
     * @param sql The SQL statement to prepare.
     * @param autoGeneratedKeys If {@code true}, generated keys will be available for retrieval.
     * @return A new {@link PreparedQuery} instance.
     * @throws IllegalArgumentException If {@code conn} or {@code sql} is {@code null} or empty.
     * @throws SQLException If a database access error occurs.
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql, final boolean autoGeneratedKeys)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        return new PreparedQuery(prepareStatement(conn, sql, autoGeneratedKeys));
    }

    /**
     * Prepares a SQL query to retrieve auto-generated keys from specified column indexes, using a provided {@link Connection}.
     *
     * <p><b>Important:</b> This method does not manage the lifecycle of the connection. The caller MUST close the provided {@code Connection} to avoid resource leaks.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     // Assumes columns 1 ('id') and 4 ('creation_ts') are auto-generated
     *     Row generated = JdbcUtil.prepareQuery(conn,
     *             "INSERT INTO events (message) VALUES (?)", new int[]{1, 4})
     *         .setString(1, "System startup")
     *         .insert()
     *         .orElse(null);
     *     if (generated != null) {
     *         System.out.println("New event ID: " + generated.get(0));
     *         System.out.println("Creation timestamp: " + generated.get(1));
     *     }
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use. It will not be closed by this method.
     * @param sql The SQL statement to prepare.
     * @param returnColumnIndexes An array of 1-based column indexes of generated keys to return.
     * @return A new {@link PreparedQuery} instance.
     * @throws IllegalArgumentException If any argument is {@code null} or empty.
     * @throws SQLException If a database access error occurs.
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql, final int[] returnColumnIndexes)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotEmpty(returnColumnIndexes, cs.returnColumnIndexes);

        return new PreparedQuery(prepareStatement(conn, sql, returnColumnIndexes));
    }

    /**
     * Prepares a SQL query to retrieve auto-generated keys from specified column names, using a provided {@link Connection}.
     *
     * <p><b>Important:</b> This method does not manage the lifecycle of the connection. The caller MUST close the provided {@code Connection} to avoid resource leaks.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     // Assumes columns 'id' and 'creation_ts' are auto-generated
     *     Row generated = JdbcUtil.prepareQuery(conn,
     *             "INSERT INTO events (message) VALUES (?)", new String[]{"id", "creation_ts"})
     *         .setString(1, "System shutdown")
     *         .insert()
     *         .orElse(null);
     *     if (generated != null) {
     *         System.out.println("New event ID: " + generated.getLong("id"));
     *         System.out.println("Creation timestamp: " + generated.getTimestamp("creation_ts"));
     *     }
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use. It will not be closed by this method.
     * @param sql The SQL statement to prepare.
     * @param returnColumnNames An array of column names of generated keys to return.
     * @return A new {@link PreparedQuery} instance.
     * @throws IllegalArgumentException If any argument is {@code null} or empty.
     * @throws SQLException If a database access error occurs.
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql, final String[] returnColumnNames)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotEmpty(returnColumnNames, cs.returnColumnNames);

        return new PreparedQuery(prepareStatement(conn, sql, returnColumnNames));
    }

    /**
     * Prepares a SQL query using a custom {@link PreparedStatement} creator and a provided {@link Connection}.
     *
     * <p><b>Important:</b> This method does not manage the lifecycle of the connection. The caller MUST close the provided {@code Connection} to avoid resource leaks.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     // Create a query with a specific fetch size and timeout
     *     JdbcUtil.prepareQuery(conn, "SELECT * FROM large_table",
     *             (c, s) -> {
     *                 PreparedStatement stmt = c.prepareStatement(s);
     *                 stmt.setFetchSize(100);
     *                 stmt.setQueryTimeout(30); // 30 seconds
     *                 return stmt;
     *             })
     *         .stream(Record.class)
     *         .forEach(System.out::println);
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use. It will not be closed by this method.
     * @param sql The SQL statement to prepare.
     * @param stmtCreator A factory function to create the {@link PreparedStatement}.
     * @return A new {@link PreparedQuery} instance.
     * @throws IllegalArgumentException If any argument is {@code null}.
     * @throws SQLException If a database access error occurs.
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);

        return new PreparedQuery(prepareStatement(conn, sql, stmtCreator));
    }

    /**
     * Prepares a SQL query optimized for processing large result sets from a {@link javax.sql.DataSource}.
     * This method configures the underlying {@link PreparedStatement} with a forward-only fetch direction
     * and a larger fetch size to improve performance when streaming or iterating over many rows.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Efficiently stream over a large table without loading everything into memory.
     * long count = JdbcUtil.prepareQueryForBigResult(dataSource, "SELECT * FROM event_logs")
     *     .stream(LogEntry.class)
     *     .filter(entry -> entry.getLevel().equals("ERROR"))
     *     .count();
     * System.out.println("Found " + count + " error entries.");
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param sql The SQL query to prepare.
     * @return A {@link PreparedQuery} instance optimized for large result sets.
     * @throws SQLException If a database access error occurs.
     * @see #prepareQueryForBigResult(Connection, String)
     */
    @Beta
    public static PreparedQuery prepareQueryForBigResult(final javax.sql.DataSource ds, final String sql) throws SQLException {
        return prepareQuery(ds, sql).configStmt(stmtSetterForBigQueryResult);
    }

    /**
     * Prepares a SQL query optimized for large result sets using a provided {@link Connection}.
     * The caller is responsible for closing the connection. This method configures the statement
     * for efficient forward-only, large-fetch-size processing.
     *
     * <p><b>Important:</b> This method does not manage the lifecycle of the connection. The caller MUST close the provided {@code Connection} to avoid resource leaks.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     // Stream results without high memory usage
     *     JdbcUtil.prepareQueryForBigResult(conn, "SELECT * FROM audit_trail")
     *         .stream(Audit.class)
     *         .forEach(audit -> {
     *             // process audit record
     *         });
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use. It will not be closed by this method.
     * @param sql The SQL query to prepare.
     * @return A {@link PreparedQuery} instance optimized for large result sets.
     * @throws SQLException If a database access error occurs.
     * @see #prepareQueryForBigResult(javax.sql.DataSource, String)
     */
    @Beta
    public static PreparedQuery prepareQueryForBigResult(final Connection conn, final String sql) throws SQLException {
        return prepareQuery(conn, sql).configStmt(stmtSetterForBigQueryResult);
    }

    /**
     * Prepares a named-parameter SQL query for execution, returning a {@link NamedQuery} object.
     * Named parameters (e.g., {@code :firstName}) are used instead of traditional positional {@code ?} parameters.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "SELECT * FROM users WHERE first_name = :firstName AND status = :status";
     * try (NamedQuery query = JdbcUtil.prepareNamedQuery(dataSource, sql)) {
     *     query.setParameter("firstName", "John");
     *     query.setParameter("status", "ACTIVE");
     *
     *     List<User> users = query.list(User.class);
     *     // ...
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param namedSql The SQL query with named parameters (e.g., {@code :paramName}).
     * @return A new {@link NamedQuery} instance.
     * @throws IllegalArgumentException If {@code ds} or {@code namedSql} is {@code null} or empty.
     * @throws SQLException If a database access error occurs.
     * @see NamedQuery
     * @see #prepareNamedQuery(Connection, String)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(namedSql, cs.namedSql);

        final SQLTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, namedSql).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a named SQL query with auto-generated keys support using the provided DataSource.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<Long> newUserId = JdbcUtil.prepareNamedQuery(dataSource,
     *         "INSERT INTO users (first_name, last_name, email) VALUES (:firstName, :lastName, :email)", true)
     *     .setParameter("firstName", "John")
     *     .setParameter("lastName", "Doe")
     *     .setParameter("email", "john.doe@example.com")
     *     .insert();
     *
     * if (newUserId.isPresent()) {
     *     System.out.println("New user created with ID: " + newUserId.get());
     * }
     * }</pre>
     *
     * @param ds The DataSource to use for the query
     * @param namedSql The named SQL string to prepare
     * @param autoGeneratedKeys Whether auto-generated keys should be returned
     * @return A NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException If the DataSource or named SQL string is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql, final boolean autoGeneratedKeys)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(namedSql, cs.namedSql);

        final SQLTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, autoGeneratedKeys);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, autoGeneratedKeys).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a named SQL query with specific column indexes for auto-generated keys using the provided DataSource.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Assuming a table where a trigger generates a UUID at column 1 and a timestamp at column 4 on insert
     * String insertSql = "INSERT INTO documents (title, content) VALUES (:title, :content)";
     *
     * Row row = JdbcUtil.prepareNamedQuery(dataSource, insertSql, new int[]{1, 4})
     *     .setParameter("title", "Annual Report")
     *     .setParameter("content", "Report content...")
     *     .insert()
     *     .orElse(null);
     *
     * if (row != null) {
     *     String generatedUuid = row.getString(1);
     *     Timestamp creationTime = row.getTimestamp(2);
     *     System.out.println("Document created with UUID: " + generatedUuid + " at " + creationTime);
     * }
     * }</pre>
     *
     * @param ds The DataSource to use for the query
     * @param namedSql The named SQL string to prepare
     * @param returnColumnIndexes The column indexes for which auto-generated keys should be returned
     * @return A NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException If the DataSource, named SQL string, or returnColumnIndexes is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql, final int[] returnColumnIndexes)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(namedSql, cs.namedSql);
        N.checkArgNotEmpty(returnColumnIndexes, cs.returnColumnIndexes);

        final SQLTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, returnColumnIndexes);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, returnColumnIndexes).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a named SQL query with specific column names for auto-generated keys using the provided DataSource.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Assuming a table with an auto-incrementing 'id' and a 'created_at' column with a default value
     * String query = "INSERT INTO logs (message, severity) VALUES (:message, :severity)";
     *
     * Row generatedValues = JdbcUtil.prepareNamedQuery(dataSource, query, new String[]{"id", "created_at"})
     *     .setParameter("message", "User logged in")
     *     .setParameter("severity", "INFO")
     *     .insert()
     *     .orElse(null);
     *
     * if (generatedValues != null) {
     *     long newId = generatedValues.getLong("id");
     *     Timestamp creationTime = generatedValues.getTimestamp("created_at");
     *     System.out.println("New log entry created with ID: " + newId + " at " + creationTime);
     * }
     * }</pre>
     *
     * @param ds The DataSource to use for the query
     * @param namedSql The named SQL string to prepare
     * @param returnColumnNames The column names for which auto-generated keys should be returned
     * @return A NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException If the DataSource, named SQL string, or returnColumnNames is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql, final String[] returnColumnNames)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(namedSql, cs.namedSql);
        N.checkArgNotEmpty(returnColumnNames, cs.returnColumnNames);

        final SQLTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, returnColumnNames);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, returnColumnNames).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a named SQL query using a custom statement creator with the provided DataSource.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Example: Creating a scrollable and updatable ResultSet with named parameters
     * List<User> users = JdbcUtil.prepareNamedQuery(dataSource,
     *         "SELECT * FROM users WHERE department = :dept",
     *         (conn, sql) -> conn.prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE))
     *     .setParameter("dept", "Engineering")
     *     .list(User.class);
     * }</pre>
     *
     * @param ds The DataSource to use for the query
     * @param namedSql The named SQL string to prepare
     * @param stmtCreator A function to create a PreparedStatement with custom configuration
     * @return A NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException If the DataSource, named SQL string, or stmtCreator is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(namedSql, cs.namedSql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);

        final SQLTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, stmtCreator);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, stmtCreator).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a named SQL query using the provided Connection and named SQL string.
     *
     * <p><b>Important:</b> This method does not manage the lifecycle of the connection. The caller MUST close the provided {@code Connection} to avoid resource leaks.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Connection conn = dataSource.getConnection();
     * try (NamedQuery query = JdbcUtil.prepareNamedQuery(conn, 
     *         "UPDATE users SET status = :status WHERE id = :id")) {
     *     int updated = query.setParameter("status", "ACTIVE")
     *                        .setParameter("id", userId)
     *                        .update();
     * } finally {
     *     conn.close();
     * }
     * }</pre>
     *
     * @param conn The Connection to use for the query
     * @param namedSql The named SQL string to prepare
     * @return A NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException If the Connection or named SQL string is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while preparing the query
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(namedSql, cs.namedSql);

        final ParsedSql parsedSql = parseNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, parsedSql), parsedSql);
    }

    /**
     * Prepares a named SQL query with auto-generated keys support using the provided Connection.
     *
     * <p><b>Important:</b> This method does not manage the lifecycle of the connection. The caller MUST close the provided {@code Connection} to avoid resource leaks.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     Optional<Long> newId = JdbcUtil.prepareNamedQuery(conn,
     *             "INSERT INTO products (name, price, category) VALUES (:name, :price, :category)", true)
     *         .setParameter("name", "Laptop")
     *         .setParameter("price", 999.99)
     *         .setParameter("category", "Electronics")
     *         .insert();
     *     System.out.println("New product ID: " + newId.orElse(null));
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The Connection to use for the query
     * @param namedSql The named SQL string to prepare
     * @param autoGeneratedKeys Whether auto-generated keys should be returned
     * @return A NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException If the Connection or named SQL string is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while preparing the query
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql, final boolean autoGeneratedKeys)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(namedSql, cs.namedSql);

        final ParsedSql parsedSql = parseNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, parsedSql, autoGeneratedKeys), parsedSql);
    }

    /**
     * Prepares a named SQL query with specific column indexes for auto-generated keys using the provided Connection.
     *
     * <p><b>Important:</b> This method does not manage the lifecycle of the connection. The caller MUST close the provided {@code Connection} to avoid resource leaks.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     // Assumes columns 1 ('id') and 4 ('creation_ts') are auto-generated
     *     Row generated = JdbcUtil.prepareNamedQuery(conn,
     *             "INSERT INTO events (event_type, message) VALUES (:type, :msg)", new int[]{1, 4})
     *         .setParameter("type", "SYSTEM")
     *         .setParameter("msg", "System startup")
     *         .insert()
     *         .orElse(null);
     *     if (generated != null) {
     *         System.out.println("New event ID: " + generated.get(0));
     *         System.out.println("Creation timestamp: " + generated.get(1));
     *     }
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The Connection to use for the query
     * @param namedSql The named SQL string to prepare
     * @param returnColumnIndexes The column indexes for which auto-generated keys should be returned
     * @return A NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException If the Connection, named SQL string, or returnColumnIndexes is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while preparing the query
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql, final int[] returnColumnIndexes)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(namedSql, cs.namedSql);
        N.checkArgNotEmpty(returnColumnIndexes, cs.returnColumnIndexes);

        final ParsedSql parsedSql = parseNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, parsedSql, returnColumnIndexes), parsedSql);
    }

    /**
     * Prepares a named SQL query with specific column names for auto-generated keys using the provided Connection.
     *
     * <p><b>Important:</b> This method does not manage the lifecycle of the connection. The caller MUST close the provided {@code Connection} to avoid resource leaks.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     Row generatedValues = JdbcUtil.prepareNamedQuery(conn,
     *             "INSERT INTO notifications (user_id, message) VALUES (:userId, :msg)",
     *             new String[]{"id", "created_at"})
     *         .setParameter("userId", 123)
     *         .setParameter("msg", "Welcome to the system")
     *         .insert()
     *         .orElse(null);
     *
     *     if (generatedValues != null) {
     *         long newId = generatedValues.getLong("id");
     *         Timestamp creationTime = generatedValues.getTimestamp("created_at");
     *         System.out.println("New notification ID: " + newId + " at " + creationTime);
     *     }
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The Connection to use for the query
     * @param namedSql The named SQL string to prepare
     * @param returnColumnNames The column names for which auto-generated keys should be returned
     * @return A NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException If the Connection, named SQL string, or returnColumnNames is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while preparing the query
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql, final String[] returnColumnNames)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(namedSql, cs.namedSql);
        N.checkArgNotEmpty(returnColumnNames, cs.returnColumnNames);

        final ParsedSql parsedSql = parseNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, parsedSql, returnColumnNames), parsedSql);
    }

    /**
     * Prepares a named SQL query using a custom statement creator with the provided Connection.
     *
     * <p><b>Important:</b> This method does not manage the lifecycle of the connection. The caller MUST close the provided {@code Connection} to avoid resource leaks.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     // Create a scrollable ResultSet with named parameters
     *     List<Order> orders = JdbcUtil.prepareNamedQuery(conn,
     *             "SELECT * FROM orders WHERE customer_id = :customerId",
     *             (c, sql) -> c.prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY))
     *         .setParameter("customerId", 456)
     *         .list(Order.class);
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The Connection to use for the query
     * @param namedSql The named SQL string to prepare
     * @param stmtCreator A function to create a PreparedStatement with custom configuration
     * @return A NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException If the Connection, named SQL string, or stmtCreator is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while preparing the query
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(namedSql, cs.namedSql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);

        final ParsedSql parsedSql = parseNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, parsedSql, stmtCreator), parsedSql);
    }

    /**
     * Prepares a named SQL query using the provided DataSource and ParsedSql object.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.
     * </p>
     *
     * @param ds The DataSource to use for the query
     * @param namedSql The ParsedSql object containing the named SQL
     * @return A NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException If the DataSource or named SQL is {@code null} or invalid
     * @throws SQLException If a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotNull(namedSql, cs.namedSql);
        validateNamedSql(namedSql);

        final SQLTransaction tran = getTransaction(ds, namedSql.getParameterizedSql(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, namedSql).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a named SQL query with auto-generated keys support using the provided DataSource and ParsedSql object.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.
     * </p>
     *
     * @param ds The DataSource to use for the query
     * @param namedSql The ParsedSql object containing the named SQL
     * @param autoGeneratedKeys Whether auto-generated keys should be returned
     * @return A NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException If the DataSource or named SQL is {@code null} or invalid
     * @throws SQLException If a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql, final boolean autoGeneratedKeys)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotNull(namedSql, cs.namedSql);
        validateNamedSql(namedSql);

        final SQLTransaction tran = getTransaction(ds, namedSql.getParameterizedSql(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, autoGeneratedKeys);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, autoGeneratedKeys).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a named SQL query with specific column indexes for auto-generated keys using the provided DataSource and ParsedSql object.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.
     * </p>
     *
     * @param ds The DataSource to use for the query
     * @param namedSql The ParsedSql object containing the named SQL
     * @param returnColumnIndexes The column indexes for which auto-generated keys should be returned
     * @return A NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException If the DataSource, named SQL, or returnColumnIndexes is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql, final int[] returnColumnIndexes)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotNull(namedSql, cs.namedSql);
        N.checkArgNotEmpty(returnColumnIndexes, cs.returnColumnIndexes);
        validateNamedSql(namedSql);

        final SQLTransaction tran = getTransaction(ds, namedSql.getParameterizedSql(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, returnColumnIndexes);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, returnColumnIndexes).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a named SQL query with specific column names for auto-generated keys using the provided DataSource and ParsedSql object.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.
     * </p>
     *
     * @param ds The DataSource to use for the query
     * @param namedSql The ParsedSql object containing the named SQL
     * @param returnColumnNames The column names for which auto-generated keys should be returned
     * @return A NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException If the DataSource, named SQL, or returnColumnNames is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql, final String[] returnColumnNames)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotNull(namedSql, cs.namedSql);
        N.checkArgNotEmpty(returnColumnNames, cs.returnColumnNames);
        validateNamedSql(namedSql);

        final SQLTransaction tran = getTransaction(ds, namedSql.getParameterizedSql(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, returnColumnNames);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, returnColumnNames).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a named SQL query using a custom statement creator with the provided DataSource and ParsedSql object.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.
     * </p>
     *
     * @param ds The DataSource to use for the query
     * @param namedSql The ParsedSql object containing the named SQL
     * @param stmtCreator A function to create a PreparedStatement with custom configuration
     * @return A NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException If the DataSource, named SQL, or stmtCreator is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotNull(namedSql, cs.namedSql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);
        validateNamedSql(namedSql);

        final SQLTransaction tran = getTransaction(ds, namedSql.getParameterizedSql(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, stmtCreator);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, stmtCreator).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a named SQL query using the provided Connection and ParsedSql object.
     *
     * <p><b>Important:</b> This method does not manage the lifecycle of the connection. The caller MUST close the provided {@code Connection} to avoid resource leaks.</p>
     *
     * @param conn The Connection to use for the query
     * @param namedSql The ParsedSql object containing the named SQL
     * @return A NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException If the Connection or named SQL is {@code null} or invalid
     * @throws SQLException If a SQL exception occurs while preparing the query
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final ParsedSql namedSql) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotNull(namedSql, cs.namedSql);
        validateNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSql), namedSql);
    }

    /**
     * Prepares a named SQL query with auto-generated keys support using the provided Connection and ParsedSql object.
     *
     * <p><b>Important:</b> This method does not manage the lifecycle of the connection. The caller MUST close the provided {@code Connection} to avoid resource leaks.</p>
     *
     * @param conn The Connection to use for the query
     * @param namedSql The ParsedSql object containing the named SQL
     * @param autoGeneratedKeys Whether auto-generated keys should be returned
     * @return A NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException If the Connection or named SQL is {@code null} or invalid
     * @throws SQLException If a SQL exception occurs while preparing the query
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final ParsedSql namedSql, final boolean autoGeneratedKeys)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotNull(namedSql, cs.namedSql);
        validateNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSql, autoGeneratedKeys), namedSql);
    }

    /**
     * Prepares a named SQL query with specific column indexes for auto-generated keys using the provided Connection and ParsedSql object.
     *
     * <p><b>Important:</b> This method does not manage the lifecycle of the connection. The caller MUST close the provided {@code Connection} to avoid resource leaks.</p>
     *
     * @param conn The Connection to use for the query
     * @param namedSql The ParsedSql object containing the named SQL
     * @param returnColumnIndexes The column indexes for which auto-generated keys should be returned
     * @return A NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException If the Connection, named SQL, or returnColumnIndexes is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while preparing the query
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final ParsedSql namedSql, final int[] returnColumnIndexes)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotNull(namedSql, cs.namedSql);
        N.checkArgNotEmpty(returnColumnIndexes, cs.returnColumnIndexes);
        validateNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSql, returnColumnIndexes), namedSql);
    }

    /**
     * Prepares a named SQL query with specific column names for auto-generated keys using the provided Connection and ParsedSql object.
     *
     * <p><b>Important:</b> This method does not manage the lifecycle of the connection. The caller MUST close the provided {@code Connection} to avoid resource leaks.</p>
     *
     * @param conn The Connection to use for the query
     * @param namedSql The ParsedSql object containing the named SQL
     * @param returnColumnNames The column names for which auto-generated keys should be returned
     * @return A NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException If the Connection, named SQL, or returnColumnNames is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while preparing the query
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final ParsedSql namedSql, final String[] returnColumnNames)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotNull(namedSql, cs.namedSql);
        N.checkArgNotEmpty(returnColumnNames, cs.returnColumnNames);
        validateNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSql, returnColumnNames), namedSql);
    }

    /**
     * Prepares a named SQL query using a custom statement creator with the provided Connection and ParsedSql object.
     *
     * <p><b>Important:</b> This method does not manage the lifecycle of the connection. The caller MUST close the provided {@code Connection} to avoid resource leaks.</p>
     *
     * @param conn The Connection to use for the query
     * @param namedSql The ParsedSql object containing the named SQL
     * @param stmtCreator A function to create a PreparedStatement with custom configuration
     * @return A NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException If the Connection, named SQL, or stmtCreator is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while preparing the query
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final ParsedSql namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotNull(namedSql, cs.namedSql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);
        validateNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSql, stmtCreator), namedSql);
    }

    /**
     * Prepares a named SQL query optimized for large result sets using the provided DataSource.
     * This method sets the fetch direction to FORWARD and the fetch size to DEFAULT_FETCH_SIZE_FOR_BIG_RESULT (1000).
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.
     * </p>
     *
     * @param ds The DataSource to use for the query
     * @param namedSql The named SQL string to prepare
     * @return A NamedQuery object configured for big result sets
     * @throws SQLException If a SQL exception occurs while preparing the query
     */
    @Beta
    public static NamedQuery prepareNamedQueryForBigResult(final javax.sql.DataSource ds, final String namedSql) throws SQLException {
        return prepareNamedQuery(ds, namedSql).configStmt(stmtSetterForBigQueryResult);
    }

    /**
     * Prepares a named SQL query optimized for large result sets using the provided DataSource and ParsedSql object.
     * This method sets the fetch direction to FORWARD and the fetch size to DEFAULT_FETCH_SIZE_FOR_BIG_RESULT (1000).
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.
     * </p>
     *
     * @param ds The DataSource to use for the query
     * @param namedSql The ParsedSql object containing the named SQL
     * @return A NamedQuery object configured for big result sets
     * @throws SQLException If a SQL exception occurs while preparing the query
     */
    @Beta
    public static NamedQuery prepareNamedQueryForBigResult(final javax.sql.DataSource ds, final ParsedSql namedSql) throws SQLException {
        return prepareNamedQuery(ds, namedSql).configStmt(stmtSetterForBigQueryResult);
    }

    /**
     * Prepares a named SQL query optimized for large result sets using the provided Connection.
     * This method sets the fetch direction to FORWARD and the fetch size to DEFAULT_FETCH_SIZE_FOR_BIG_RESULT (1000).
     *
     * <p><b>Important:</b> This method does not manage the lifecycle of the connection. The caller MUST close the provided {@code Connection} to avoid resource leaks.</p>
     *
     * @param conn The Connection to use for the query
     * @param namedSql The named SQL string to prepare
     * @return A NamedQuery object configured for big result sets
     * @throws SQLException If a SQL exception occurs while preparing the query
     */
    @Beta
    public static NamedQuery prepareNamedQueryForBigResult(final Connection conn, final String namedSql) throws SQLException {
        return prepareNamedQuery(conn, namedSql).configStmt(stmtSetterForBigQueryResult);
    }

    /**
     * Prepares a callable SQL query (stored procedure) using the provided DataSource.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.
     * </p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (CallableQuery query = JdbcUtil.prepareCallableQuery(dataSource, 
     *         "{call get_user_info(?, ?, ?)}")) {
     *     query.setLong(1, userId);
     *     query.registerOutParameter(2, Types.VARCHAR);
     *     query.registerOutParameter(3, Types.DATE);
     *     query.execute();
     *     
     *     String name = query.getString(2);
     *     Date createdDate = query.getDate(3);
     * }
     * }</pre>
     * 
     *
     * @param ds The DataSource to use for the query
     * @param sql The SQL string for the stored procedure call
     * @return A CallableQuery object representing the prepared callable SQL query
     * @throws IllegalArgumentException If the DataSource or SQL string is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static CallableQuery prepareCallableQuery(final javax.sql.DataSource ds, final String sql) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(sql, cs.sql);

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareCallableQuery(tran.connection(), sql);
        } else {
            CallableQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareCallableQuery(conn, sql).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a callable SQL query using a custom statement creator with the provided DataSource.
     * This method allows for fine-grained control over the CallableStatement creation process,
     * enabling custom configurations such as result set type, concurrency, and holdability.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Create a CallableStatement with specific result set type and concurrency
     * try (CallableQuery query = JdbcUtil.prepareCallableQuery(dataSource,
     *         "{call get_users(?, ?)}",
     *         (conn, sql) -> conn.prepareCall(sql,
     *             ResultSet.TYPE_SCROLL_INSENSITIVE,
     *             ResultSet.CONCUR_READ_ONLY))) {
     *
     *     query.setInt(1, departmentId);
     *     query.registerOutParameter(2, Types.INTEGER);
     *     ResultSet rs = query.executeQuery();
     *     // Process result set that can be scrolled backward
     * }
     *
     * // Configure statement with specific timeout
     * Throwables.BiFunction<Connection, String, CallableStatement, SQLException> creator =
     *     (conn, sql) -> {
     *         CallableStatement stmt = conn.prepareCall(sql);
     *         stmt.setQueryTimeout(30); // 30 seconds timeout
     *         return stmt;
     *     };
     * try (CallableQuery query = JdbcUtil.prepareCallableQuery(dataSource,
     *         "{call long_running_procedure(?)}", creator)) {
     *     query.setString(1, "param");
     *     query.execute();
     * }
     * }</pre>
     *
     * @param ds The DataSource to use for the query, must not be {@code null}
     * @param sql The SQL string for the stored procedure call, must not be {@code null} or empty
     * @param stmtCreator A functional interface that creates a CallableStatement with custom configuration.
     *                    Receives the Connection and SQL string, and returns a configured CallableStatement.
     *                    Must not be {@code null}.
     * @return A CallableQuery object representing the prepared callable SQL query
     * @throws IllegalArgumentException If {@code ds}, {@code sql}, or {@code stmtCreator} is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while preparing the query
     * @see #prepareCallableQuery(javax.sql.DataSource, String)
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static CallableQuery prepareCallableQuery(final javax.sql.DataSource ds, final String sql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareCallableQuery(tran.connection(), sql, stmtCreator);
        } else {
            CallableQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareCallableQuery(conn, sql, stmtCreator).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a callable SQL query (stored procedure) using the provided Connection.
     *
     * <p><b>Important:</b> This method does not manage the lifecycle of the connection. The caller MUST close the provided {@code Connection} to avoid resource leaks.</p>
     *
     * @param conn The Connection to use for the query
     * @param sql The SQL string for the stored procedure call
     * @return A CallableQuery object representing the prepared callable SQL query
     * @throws IllegalArgumentException If the Connection or SQL string is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while preparing the query
     */
    public static CallableQuery prepareCallableQuery(final Connection conn, final String sql) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        return new CallableQuery(prepareCallable(conn, sql));
    }

    /**
     * Prepares a callable SQL query using a custom statement creator with the provided Connection.
     *
     * <p><b>Important:</b> This method does not manage the lifecycle of the connection. The caller MUST close the provided {@code Connection} to avoid resource leaks.</p>
     *
     * @param conn The Connection to use for the query
     * @param sql The SQL string for the stored procedure call
     * @param stmtCreator A function to create a CallableStatement with custom configuration
     * @return A CallableQuery object representing the prepared callable SQL query
     * @throws IllegalArgumentException If the Connection, SQL string, or stmtCreator is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while preparing the query
     */
    public static CallableQuery prepareCallableQuery(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);

        return new CallableQuery(prepareCallable(conn, sql, stmtCreator));
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql) throws SQLException {
        JdbcUtil.logSql(sql);

        return conn.prepareStatement(sql);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql, final boolean autoGeneratedKeys) throws SQLException {
        JdbcUtil.logSql(sql);

        return conn.prepareStatement(sql, autoGeneratedKeys ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql, final int[] returnColumnIndexes) throws SQLException {
        JdbcUtil.logSql(sql);

        return conn.prepareStatement(sql, returnColumnIndexes);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql, final String[] returnColumnNames) throws SQLException {
        JdbcUtil.logSql(sql);

        return conn.prepareStatement(sql, returnColumnNames);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql, final int resultSetType, final int resultSetConcurrency)
            throws SQLException {
        JdbcUtil.logSql(sql);

        return conn.prepareStatement(sql, resultSetType, resultSetConcurrency);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql, final int resultSetType, final int resultSetConcurrency,
            final int resultSetHoldability) throws SQLException {
        JdbcUtil.logSql(sql);

        return conn.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        JdbcUtil.logSql(sql);

        return stmtCreator.apply(conn, sql);
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql) throws SQLException {
        JdbcUtil.logSql(parsedSql.sql());

        return conn.prepareStatement(parsedSql.getParameterizedSql());
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql, final boolean autoGeneratedKeys) throws SQLException {
        JdbcUtil.logSql(parsedSql.sql());

        return conn.prepareStatement(parsedSql.getParameterizedSql(), autoGeneratedKeys ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS);
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql, final int[] returnColumnIndexes) throws SQLException {
        JdbcUtil.logSql(parsedSql.sql());

        return conn.prepareStatement(parsedSql.getParameterizedSql(), returnColumnIndexes);
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql, final String[] returnColumnNames) throws SQLException {
        JdbcUtil.logSql(parsedSql.sql());

        return conn.prepareStatement(parsedSql.getParameterizedSql(), returnColumnNames);
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql, final int resultSetType, final int resultSetConcurrency)
            throws SQLException {
        JdbcUtil.logSql(parsedSql.sql());

        return conn.prepareStatement(parsedSql.getParameterizedSql(), resultSetType, resultSetConcurrency);
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql, final int resultSetType, final int resultSetConcurrency,
            final int resultSetHoldability) throws SQLException {
        JdbcUtil.logSql(parsedSql.sql());

        return conn.prepareStatement(parsedSql.getParameterizedSql(), resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        JdbcUtil.logSql(parsedSql.sql());

        return stmtCreator.apply(conn, parsedSql.getParameterizedSql());
    }

    static CallableStatement prepareCallable(final Connection conn, final String sql) throws SQLException {
        JdbcUtil.logSql(sql);

        return conn.prepareCall(sql);
    }

    static CallableStatement prepareCallable(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
        JdbcUtil.logSql(sql);

        return stmtCreator.apply(conn, sql);
    }

    static CallableStatement prepareCallable(final Connection conn, final ParsedSql parsedSql) throws SQLException {
        JdbcUtil.logSql(parsedSql.sql());

        return conn.prepareCall(parsedSql.getParameterizedSql());
    }

    static CallableStatement prepareCallable(final Connection conn, final ParsedSql parsedSql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
        JdbcUtil.logSql(parsedSql.sql());

        return stmtCreator.apply(conn, parsedSql.getParameterizedSql());
    }

    /**
     * Prepares a PreparedStatement for the given SQL query and sets the provided parameters.
     * The SQL string can contain either positional (?) or named parameters (:paramName).
     *
     * @param conn the database connection to use
     * @param sql the SQL statement, which may contain positional or named parameters
     * @param parameters the parameter values to set on the prepared statement
     * @return a PreparedStatement with parameters set, ready for execution
     * @throws SQLException if a database access error occurs or the SQL is invalid
     */
    static PreparedStatement prepareStmt(final Connection conn, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final PreparedStatement stmt = prepareStatement(conn, parsedSql);

        if (N.notEmpty(parameters)) {
            setParameters(parsedSql, stmt, parameters);
        }

        return stmt;
    }

    /**
     * Prepares a CallableStatement for executing stored procedures or functions with the given SQL and parameters.
     * The SQL string can contain either positional (?) or named parameters (:paramName).
     *
     * @param conn the database connection to use
     * @param sql the SQL call statement, which may contain positional or named parameters
     * @param parameters the parameter values to set on the callable statement
     * @return a CallableStatement with parameters set, ready for execution
     * @throws SQLException if a database access error occurs or the SQL is invalid
     */
    static CallableStatement prepareCall(final Connection conn, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final CallableStatement stmt = prepareCallable(conn, parsedSql);

        if (N.notEmpty(parameters)) {
            setParameters(parsedSql, stmt, parameters);
        }

        return stmt;
    }

    /**
     * Prepares a PreparedStatement for batch execution with multiple sets of parameters.
     * Each element in the parameters list represents one batch of parameters to be added to the statement.
     * The SQL string can contain either positional (?) or named parameters (:paramName).
     *
     * @param conn the database connection to use
     * @param sql the SQL statement, which may contain positional or named parameters
     * @param parametersList a list where each element contains parameter values for one batch operation
     * @return a PreparedStatement with all batches added, ready for batch execution via executeBatch()
     * @throws SQLException if a database access error occurs or the SQL is invalid
     */
    static PreparedStatement prepareBatchStmt(final Connection conn, final String sql, final List<?> parametersList) throws SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final PreparedStatement stmt = prepareStatement(conn, parsedSql);

        for (final Object parameters : parametersList) {
            setParameters(parsedSql, stmt, N.asArray(parameters));
            stmt.addBatch();
        }

        return stmt;
    }

    /**
     * Prepares a CallableStatement for batch execution of stored procedures or functions with multiple sets of parameters.
     * Each element in the parameters list represents one batch of parameters to be added to the statement.
     * The SQL string can contain either positional (?) or named parameters (:paramName).
     *
     * @param conn the database connection to use
     * @param sql the SQL call statement, which may contain positional or named parameters
     * @param parametersList a list where each element contains parameter values for one batch operation
     * @return a CallableStatement with all batches added, ready for batch execution via executeBatch()
     * @throws SQLException if a database access error occurs or the SQL is invalid
     */
    static CallableStatement prepareBatchCall(final Connection conn, final String sql, final List<?> parametersList) throws SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final CallableStatement stmt = prepareCallable(conn, parsedSql);

        for (final Object parameters : parametersList) {
            setParameters(parsedSql, stmt, N.asArray(parameters));
            stmt.addBatch();
        }

        return stmt;
    }

    /**
     * Parses a named SQL statement into a ParsedSql object and validates that all parameters are named.
     * Named SQL uses parameters in the format :paramName or #{paramName}.
     *
     * @param namedSql the SQL statement containing named parameters
     * @return a ParsedSql object representing the parsed and validated named SQL
     * @throws IllegalArgumentException if the SQL is not a valid named SQL (contains positional parameters)
     */
    private static ParsedSql parseNamedSql(final String namedSql) {
        N.checkArgNotEmpty(namedSql, cs.namedSql);

        final ParsedSql parsedSql = ParsedSql.parse(namedSql);

        validateNamedSql(parsedSql);

        return parsedSql;
    }

    private static void validateNamedSql(final ParsedSql namedSql) {
        if (namedSql.getNamedParameters().size() != namedSql.getParameterCount()) {
            throw new IllegalArgumentException("\"" + namedSql.sql() + "\" is not a valid named sql:");
        }
    }

    static SQLTransaction getTransaction(final javax.sql.DataSource ds, final String sql, final CreatedBy createdBy) {
        final SQLOperation sqlOperation = JdbcUtil.getSQLOperation(sql);
        final SQLTransaction tran = SQLTransaction.getTransaction(ds, createdBy);

        if (tran == null || (tran.isForUpdateOnly() && sqlOperation == SQLOperation.SELECT)) {
            return null;
        } else {
            return tran;
        }
    }

    /**
     * Executes a SQL query immediately with the provided parameters and returns all results as a {@link Dataset}.
     * This is a convenience method for one-time query execution where the result set is loaded entirely into memory.
     * For reusable queries or streaming large result sets, consider using {@link #prepareQuery(javax.sql.DataSource, String)} instead.
     *
     * <p>If a transaction is active in the current thread (started via {@link #beginTransaction(javax.sql.DataSource)}
     * or Spring's transactional support), the transaction's Connection will be used. Otherwise, a new Connection is
     * obtained from the DataSource and automatically released after query execution.</p>
     *
     * <p><b>Key Differences from prepareQuery():</b></p>
     * <ul>
     *   <li><b>executeQuery():</b> One-time execution, loads all results into memory, closes resources immediately</li>
     *   <li><b>prepareQuery():</b> Reusable statement, supports streaming, requires manual resource cleanup</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Simple query with parameters
     * Dataset result = JdbcUtil.executeQuery(dataSource,
     *     "SELECT * FROM users WHERE age > ? AND city = ?",
     *     18, "New York");
     *
     * result.forEach(row -> {
     *     System.out.println(row.get("name") + " - " + row.get("email"));
     * });
     *
     * // Query returning single value
     * Dataset countResult = JdbcUtil.executeQuery(dataSource,
     *     "SELECT COUNT(*) as total FROM orders WHERE status = ?",
     *     "PENDING");
     * long totalOrders = countResult.getRow(0).getLong("total");
     *
     * // Query with IN clause using multiple parameters
     * Dataset products = JdbcUtil.executeQuery(dataSource,
     *     "SELECT * FROM products WHERE category IN (?, ?, ?)",
     *     "Electronics", "Books", "Clothing");
     *
     * // Iterate through results
     * for (int i = 0; i < products.size(); i++) {
     *     System.out.println(products.get(i, "product_name"));
     * }
     *
     * // Query with no parameters
     * Dataset allUsers = JdbcUtil.executeQuery(dataSource,
     *     "SELECT id, name, email FROM users ORDER BY created_at DESC");
     *
     * // Working with column names
     * List<String> columnNames = allUsers.columnNameList();
     * columnNames.forEach(System.out::println);
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to obtain a connection from, must not be {@code null}
     * @param sql The SQL SELECT statement to execute with optional {@code ?} parameter placeholders, must not be {@code null} or empty
     * @param parameters Variable number of parameters to bind to the SQL statement, matching the {@code ?} placeholders in order.
     *                   Can be empty if the SQL has no parameters. Supports primitive types, Strings, Dates, and other JDBC-compatible types
     * @return A {@link Dataset} object containing all query results loaded into memory with row and column access methods
     * @throws IllegalArgumentException If {@code ds} or {@code sql} is {@code null} or empty
     * @throws SQLException If a database access error occurs, the SQL is invalid, or parameter types are incompatible
     * @see PreparedStatement#executeQuery()
     * @see #prepareQuery(javax.sql.DataSource, String)
     * @see Dataset
     */
    public static Dataset executeQuery(final javax.sql.DataSource ds, final String sql, final Object... parameters)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(sql, cs.sql);

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return executeQuery(tran.connection(), sql, parameters);
        } else {
            final Connection conn = JdbcUtil.getConnection(ds);

            try {
                return executeQuery(conn, sql, parameters);
            } finally {
                JdbcUtil.releaseConnection(conn, ds);
            }
        }
    }

    /**
     * Executes a SQL query using the provided Connection and SQL string with optional parameters.
     * This method does not close the provided Connection after the query is executed.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Connection conn = dataSource.getConnection();
     * try {
     *     Dataset result = JdbcUtil.executeQuery(conn, 
     *         "SELECT * FROM products WHERE price BETWEEN ? AND ?", 
     *         10.0, 100.0);
     * } finally {
     *     conn.close();
     * }
     * }</pre>
     *
     * @param conn The Connection to use for the query
     * @param sql The SQL string to execute
     * @param parameters Optional parameters for the SQL query
     * @return A Dataset object containing the result of the query
     * @throws IllegalArgumentException If the Connection or SQL string is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while executing the query
     * @see PreparedStatement#executeQuery()
     */
    public static Dataset executeQuery(final Connection conn, final String sql, final Object... parameters) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            stmt = prepareStmt(conn, sql, parameters);

            stmt.setFetchDirection(ResultSet.FETCH_FORWARD);

            rs = executeQuery(stmt);

            return extractData(rs);
        } finally {
            closeQuietly(rs, stmt);
        }
    }

    /**
     * Executes a SQL data modification statement (INSERT, UPDATE, DELETE) or DDL statement immediately
     * with the provided parameters and returns the number of affected rows.
     * This is a convenience method for one-time update execution. For reusable update statements,
     * consider using {@link #prepareQuery(javax.sql.DataSource, String)} instead.
     *
     * <p>If a transaction is active in the current thread (started via {@link #beginTransaction(javax.sql.DataSource)}
     * or Spring's transactional support), the transaction's Connection will be used. Otherwise, a new Connection is
     * obtained from the DataSource and automatically released after update execution.</p>
     *
     * <p><b>Supported SQL Operations:</b></p>
     * <ul>
     *   <li><b>INSERT:</b> Adds new rows to a table</li>
     *   <li><b>UPDATE:</b> Modifies existing rows based on WHERE conditions</li>
     *   <li><b>DELETE:</b> Removes rows based on WHERE conditions</li>
     *   <li><b>DDL:</b> Data Definition Language statements (CREATE, ALTER, DROP, etc.)</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // UPDATE: Modify existing records
     * int rowsUpdated = JdbcUtil.executeUpdate(dataSource,
     *     "UPDATE users SET status = ? WHERE last_login < ?",
     *     "INACTIVE", thirtyDaysAgo);
     * System.out.println("Deactivated " + rowsUpdated + " users");
     *
     * // INSERT: Add new record
     * int rowsInserted = JdbcUtil.executeUpdate(dataSource,
     *     "INSERT INTO audit_log (user_id, action, timestamp) VALUES (?, ?, ?)",
     *     userId, "LOGIN", new Timestamp(System.currentTimeMillis()));
     *
     * // DELETE: Remove records
     * int rowsDeleted = JdbcUtil.executeUpdate(dataSource,
     *     "DELETE FROM sessions WHERE expired_at < ?",
     *     new Date(System.currentTimeMillis()));
     *
     * // UPDATE with multiple conditions
     * int updated = JdbcUtil.executeUpdate(dataSource,
     *     "UPDATE products SET price = price * ? WHERE category = ? AND stock > ?",
     *     0.9, "Electronics", 100);
     *
     * // INSERT with multiple values
     * int inserted = JdbcUtil.executeUpdate(dataSource,
     *     "INSERT INTO user_preferences (user_id, theme, language, notifications) VALUES (?, ?, ?, ?)",
     *     userId, "DARK", "en_US", true);
     *
     * // DELETE all records (use with caution!)
     * int allDeleted = JdbcUtil.executeUpdate(dataSource,
     *     "DELETE FROM temp_data");  // No parameters needed
     *
     * // Conditional update
     * if (needsUpdate) {
     *     int count = JdbcUtil.executeUpdate(dataSource,
     *         "UPDATE inventory SET quantity = quantity - ? WHERE product_id = ? AND quantity >= ?",
     *         orderQuantity, productId, orderQuantity);
     *
     *     if (count == 0) {
     *         throw new InsufficientInventoryException("Not enough stock");
     *     }
     * }
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to obtain a connection from, must not be {@code null}
     * @param sql The SQL INSERT, UPDATE, DELETE, or DDL statement with optional {@code ?} parameter placeholders,
     *            must not be {@code null} or empty
     * @param parameters Variable number of parameters to bind to the SQL statement, matching the {@code ?} placeholders in order.
     *                   Can be empty if the SQL has no parameters. Supports primitive types, Strings, Dates, and other JDBC-compatible types
     * @return The number of rows affected by the statement. Returns 0 for DDL statements or when no rows match the WHERE clause
     * @throws IllegalArgumentException If {@code ds} or {@code sql} is {@code null} or empty
     * @throws SQLException If a database access error occurs, the SQL is invalid, or parameter types are incompatible
     * @see PreparedStatement#executeUpdate()
     * @see #prepareQuery(javax.sql.DataSource, String)
     */
    public static int executeUpdate(final javax.sql.DataSource ds, final String sql, final Object... parameters) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(sql, cs.sql);

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return executeUpdate(tran.connection(), sql, parameters);
        } else {
            final Connection conn = JdbcUtil.getConnection(ds);

            try {
                return executeUpdate(conn, sql, parameters);
            } finally {
                JdbcUtil.releaseConnection(conn, ds);
            }
        }
    }

    /**
     * Executes a SQL update using the provided Connection and SQL string with optional parameters.
     * This method does not close the provided Connection after the update is executed.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Connection conn = dataSource.getConnection();
     * try {
     *     int rowsDeleted = JdbcUtil.executeUpdate(conn, 
     *         "DELETE FROM orders WHERE order_date < ? AND status = ?", 
     *         cutoffDate, "CANCELLED");
     * } finally {
     *     conn.close();
     * }
     * }</pre>
     *
     * @param conn The Connection to use for the update
     * @param sql The SQL string to execute
     * @param parameters Optional parameters for the SQL update
     * @return The number of rows affected by the update
     * @throws IllegalArgumentException If the Connection or SQL string is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while executing the update
     * @see PreparedStatement#executeUpdate()
     */
    public static int executeUpdate(final Connection conn, final String sql, final Object... parameters) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        PreparedStatement stmt = null;

        try {
            stmt = prepareStmt(conn, sql, parameters);

            return executeUpdate(stmt);
        } finally {
            closeQuietly(stmt);
        }
    }

    /**
     * Executes a batch SQL update using the provided DataSource with default batch size.
     * The default batch size is {@link #DEFAULT_BATCH_SIZE}.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Object[]> batchParams = Arrays.asList(
     *     new Object[] {"John", 25},
     *     new Object[] {"Jane", 30},
     *     new Object[] {"Bob", 35}
     * );
     * int totalRows = JdbcUtil.executeBatchUpdate(dataSource, 
     *     "INSERT INTO users (name, age) VALUES (?, ?)", 
     *     batchParams);
     * }</pre>
     *
     * @param ds The DataSource to use for the batch update
     * @param sql The SQL string to execute
     * @param listOfParameters A list of parameter sets for the batch update
     * @return The number of rows affected by the batch update
     * @throws IllegalArgumentException If the DataSource or SQL string is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while executing the batch update
     * @see PreparedStatement#executeBatch()
     */
    public static int executeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters)
            throws IllegalArgumentException, SQLException {
        return executeBatchUpdate(ds, sql, listOfParameters, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Executes a batch SQL update using the provided DataSource with specified batch size.
     * Large lists will be automatically split into smaller batches for optimal performance.
     * When the number of parameter sets exceeds the batch size, a transaction is automatically
     * started to ensure atomicity.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Insert 10,000 records in batches of 500
     * List<Object[]> largeDataset = new ArrayList<>();
     * for (int i = 0; i < 10000; i++) {
     *     largeDataset.add(new Object[] {"User" + i, i % 100});
     * }
     * int totalRows = JdbcUtil.executeBatchUpdate(dataSource,
     *     "INSERT INTO users (name, age) VALUES (?, ?)",
     *     largeDataset,
     *     500); // Process in batches of 500
     *
     * // Update records with custom batch size for better memory management
     * List<Object[]> updates = Arrays.asList(
     *     new Object[] {"active", 1},
     *     new Object[] {"active", 2},
     *     new Object[] {"inactive", 3}
     * );
     * int updated = JdbcUtil.executeBatchUpdate(dataSource,
     *     "UPDATE users SET status = ? WHERE id = ?",
     *     updates,
     *     100);
     * }</pre>
     *
     * @param ds The DataSource to use for the batch update, must not be {@code null}
     * @param sql The SQL string to execute, must not be {@code null} or empty
     * @param listOfParameters A list of parameter sets for the batch update. Each element should be
     *                        an Object array or a compatible collection representing one set of parameters.
     * @param batchSize The size of each batch, must be positive. Smaller batches use less memory
     *                  but may be slower; larger batches are faster but use more memory.
     * @return The total number of rows affected by the batch update across all batches
     * @throws IllegalArgumentException If {@code ds} or {@code sql} is {@code null} or empty, or if {@code batchSize} is not positive
     * @throws SQLException If a SQL exception occurs while executing the batch update
     * @see PreparedStatement#executeBatch()
     * @see #executeBatchUpdate(javax.sql.DataSource, String, List)
     */
    public static int executeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters, final int batchSize)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgPositive(batchSize, cs.batchSize);

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return executeBatchUpdate(tran.connection(), sql, listOfParameters, batchSize);
        } else if (listOfParameters.size() <= batchSize) {
            final Connection conn = JdbcUtil.getConnection(ds);

            try {
                return executeBatchUpdate(conn, sql, listOfParameters, batchSize);
            } finally {
                JdbcUtil.releaseConnection(conn, ds);
            }
        } else {
            final SQLTransaction tran2 = JdbcUtil.beginTransaction(ds);
            int ret = 0;

            try {
                ret = executeBatchUpdate(tran2.connection(), sql, listOfParameters, batchSize);
                tran2.commit();
            } finally {
                tran2.rollbackIfNotCommitted();
            }

            return ret;
        }
    }

    /**
     * Executes a batch SQL update using the provided Connection with default batch size.
     * This method does not close the provided Connection after the batch update is executed.
     *
     * @param conn The Connection to use for the batch update
     * @param sql The SQL string to execute
     * @param listOfParameters A list of parameter sets for the batch update
     * @return The number of rows affected by the batch update
     * @throws IllegalArgumentException If the Connection or SQL string is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while executing the batch update
     * @see PreparedStatement#executeBatch()
     */
    public static int executeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters)
            throws IllegalArgumentException, SQLException {
        return executeBatchUpdate(conn, sql, listOfParameters, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Executes a batch SQL update using the provided Connection with specified batch size.
     * This method does not close the provided Connection after the batch update is executed.
     *
     * @param conn The Connection to use for the batch update
     * @param sql The SQL string to execute
     * @param listOfParameters A list of parameter sets for the batch update
     * @param batchSize The size of each batch
     * @return The number of rows affected by the batch update
     * @throws IllegalArgumentException If the Connection or SQL string is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while executing the batch update
     * @see PreparedStatement#executeBatch()
     */
    public static int executeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters, final int batchSize)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn);
        N.checkArgNotNull(sql);
        N.checkArgPositive(batchSize, cs.batchSize);

        if (N.isEmpty(listOfParameters)) {
            return 0;
        }

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final boolean originalAutoCommit = conn.getAutoCommit();
        PreparedStatement stmt = null;
        boolean noException = false;

        try {
            if (originalAutoCommit && listOfParameters.size() > batchSize) {
                conn.setAutoCommit(false);
            }

            stmt = prepareStatement(conn, parsedSql);

            final Object[] parameters = new Object[1];
            int res = 0;
            int idx = 0;

            for (final Object parameter : listOfParameters) {
                parameters[0] = parameter;

                setParameters(parsedSql, stmt, parameters);
                stmt.addBatch();

                if (++idx % batchSize == 0) {
                    res += N.sum(executeBatch(stmt));
                }
            }

            if (idx % batchSize != 0) {
                res += N.sum(executeBatch(stmt));
            }

            noException = true;

            return res;
        } finally {
            if (originalAutoCommit && listOfParameters.size() > batchSize) {
                try {
                    if (noException) {
                        conn.commit();
                    } else {
                        conn.rollback();
                    }
                } finally {
                    try {
                        conn.setAutoCommit(true);
                    } finally {
                        JdbcUtil.closeQuietly(stmt);
                    }
                }
            } else {
                JdbcUtil.closeQuietly(stmt);
            }
        }
    }

    /**
     * Executes a large batch SQL update using the provided DataSource with default batch size.
     * This method returns a long value to support updates affecting more than Integer.MAX_VALUE rows.
     *
     * @param ds The DataSource to use for the batch update
     * @param sql The SQL string to execute
     * @param listOfParameters A list of parameter sets for the batch update
     * @return The number of rows affected by the batch update as a long value
     * @throws IllegalArgumentException If the DataSource or SQL string is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while executing the batch update
     * @see PreparedStatement#executeLargeBatch()
     */
    public static long executeLargeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters)
            throws IllegalArgumentException, SQLException {
        return executeLargeBatchUpdate(ds, sql, listOfParameters, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Executes a large batch SQL update using the provided DataSource with specified batch size.
     * This method returns a long value to support updates affecting more than Integer.MAX_VALUE rows.
     *
     * @param ds The DataSource to use for the batch update
     * @param sql The SQL string to execute
     * @param listOfParameters A list of parameter sets for the batch update
     * @param batchSize The size of each batch
     * @return The number of rows affected by the batch update as a long value
     * @throws IllegalArgumentException If the DataSource or SQL string is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while executing the batch update
     * @see PreparedStatement#executeLargeBatch()
     */
    public static long executeLargeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters, final int batchSize)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgPositive(batchSize, cs.batchSize);

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return executeLargeBatchUpdate(tran.connection(), sql, listOfParameters, batchSize);
        } else if (listOfParameters.size() <= batchSize) {
            final Connection conn = JdbcUtil.getConnection(ds);

            try {
                return executeLargeBatchUpdate(conn, sql, listOfParameters, batchSize);
            } finally {
                JdbcUtil.releaseConnection(conn, ds);
            }
        } else {
            final SQLTransaction tran2 = JdbcUtil.beginTransaction(ds);
            long ret = 0;

            try {
                ret = executeLargeBatchUpdate(tran2.connection(), sql, listOfParameters, batchSize);
                tran2.commit();
            } finally {
                tran2.rollbackIfNotCommitted();
            }

            return ret;
        }
    }

    /**
     * Executes a large batch SQL update using the provided Connection with default batch size.
     * This method does not close the provided Connection after the batch update is executed.
     *
     * @param conn The Connection to use for the batch update
     * @param sql The SQL string to execute
     * @param listOfParameters A list of parameter sets for the batch update
     * @return The number of rows affected by the batch update as a long value
     * @throws IllegalArgumentException If the Connection or SQL string is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while executing the batch update
     * @see PreparedStatement#executeLargeBatch()
     */
    public static long executeLargeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters)
            throws IllegalArgumentException, SQLException {
        return executeLargeBatchUpdate(conn, sql, listOfParameters, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Executes a large batch SQL update using the provided Connection with specified batch size.
     * This method does not close the provided Connection after the batch update is executed.
     *
     * @param conn The Connection to use for the batch update
     * @param sql The SQL string to execute
     * @param listOfParameters A list of parameter sets for the batch update
     * @param batchSize The size of each batch
     * @return The number of rows affected by the batch update as a long value
     * @throws IllegalArgumentException If the Connection or SQL string is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while executing the batch update
     * @see PreparedStatement#executeLargeBatch()
     */
    public static long executeLargeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters, final int batchSize)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn);
        N.checkArgNotNull(sql);
        N.checkArgPositive(batchSize, cs.batchSize);

        if (N.isEmpty(listOfParameters)) {
            return 0;
        }

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final boolean originalAutoCommit = conn.getAutoCommit();
        PreparedStatement stmt = null;
        boolean noException = false;

        try {
            if (originalAutoCommit && listOfParameters.size() > batchSize) {
                conn.setAutoCommit(false);
            }

            stmt = prepareStatement(conn, parsedSql);

            final Object[] parameters = new Object[1];
            long res = 0;
            int idx = 0;

            for (final Object parameter : listOfParameters) {
                parameters[0] = parameter;

                setParameters(parsedSql, stmt, parameters);
                stmt.addBatch();

                if (++idx % batchSize == 0) {
                    res += N.sum(executeLargeBatch(stmt));
                }
            }

            if (idx % batchSize != 0) {
                res += N.sum(executeLargeBatch(stmt));
            }

            noException = true;

            return res;
        } finally {
            if (originalAutoCommit && listOfParameters.size() > batchSize) {
                try {
                    if (noException) {
                        conn.commit();
                    } else {
                        conn.rollback();
                    }
                } finally {
                    try {
                        conn.setAutoCommit(true);
                    } finally {
                        JdbcUtil.closeQuietly(stmt);
                    }
                }
            } else {
                JdbcUtil.closeQuietly(stmt);
            }
        }
    }

    /**
     * Executes a SQL statement using the provided DataSource with optional parameters.
     * This method can execute any SQL statement and returns a boolean indicating the type of result.
     *
     * @param ds The DataSource to use for the SQL execution
     * @param sql The SQL string to execute
     * @param parameters Optional parameters for the SQL statement
     * @return {@code true} if the first result is a ResultSet object; {@code false} if it is an update count or there are no results
     * @throws IllegalArgumentException If the DataSource or SQL string is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while executing the statement
     * @see PreparedStatement#execute()
     */
    public static boolean execute(final javax.sql.DataSource ds, final String sql, final Object... parameters) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(sql, cs.sql);

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return execute(tran.connection(), sql, parameters);
        } else {
            final Connection conn = JdbcUtil.getConnection(ds);

            try {
                return execute(conn, sql, parameters);
            } finally {
                JdbcUtil.releaseConnection(conn, ds);
            }
        }
    }

    /**
     * Executes a SQL statement using the provided Connection with optional parameters.
     * This method does not close the provided Connection after the statement is executed.
     *
     * @param conn The Connection to use for the SQL execution
     * @param sql The SQL string to execute
     * @param parameters Optional parameters for the SQL statement
     * @return {@code true} if the first result is a ResultSet object; {@code false} if it is an update count or there are no results
     * @throws IllegalArgumentException If the Connection or SQL string is {@code null} or empty
     * @throws SQLException If a SQL exception occurs while executing the statement
     * @see PreparedStatement#execute()
     */
    public static boolean execute(final Connection conn, final String sql, final Object... parameters) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        PreparedStatement stmt = null;

        try {
            stmt = prepareStmt(conn, sql, parameters);

            return JdbcUtil.execute(stmt);
        } finally {
            closeQuietly(stmt);
        }
    }

    static ResultSet executeQuery(final PreparedStatement stmt) throws SQLException {
        final SqlLogConfig sqlLogConfig = JdbcUtil.minExecutionTimeForSqlPerfLog_TL.get();

        if (JdbcUtil.isToHandleSqlLog(sqlLogConfig)) {
            final long startTime = System.currentTimeMillis();

            try {
                // return stmt.executeQuery();
                // For better performance.
                return ResultSetProxy.wrap(stmt.executeQuery());
            } finally {
                JdbcUtil.handleSqlLog(stmt, sqlLogConfig, startTime);

                clearParameters(stmt);
            }
        } else {
            try {
                // return stmt.executeQuery();
                // For better performance.
                return ResultSetProxy.wrap(stmt.executeQuery());
            } finally {
                clearParameters(stmt);
            }
        }
    }

    static int executeUpdate(final PreparedStatement stmt) throws SQLException {
        final SqlLogConfig sqlLogConfig = JdbcUtil.minExecutionTimeForSqlPerfLog_TL.get();

        if (JdbcUtil.isToHandleSqlLog(sqlLogConfig)) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeUpdate();
            } finally {
                JdbcUtil.handleSqlLog(stmt, sqlLogConfig, startTime);

                clearParameters(stmt);
            }
        } else {
            try {
                return stmt.executeUpdate();
            } finally {
                clearParameters(stmt);
            }
        }
    }

    static long executeLargeUpdate(final PreparedStatement stmt) throws SQLException {
        final SqlLogConfig sqlLogConfig = JdbcUtil.minExecutionTimeForSqlPerfLog_TL.get();

        if (JdbcUtil.isToHandleSqlLog(sqlLogConfig)) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeLargeUpdate();
            } finally {
                JdbcUtil.handleSqlLog(stmt, sqlLogConfig, startTime);

                try {
                    stmt.clearBatch();
                } catch (final SQLException e) {
                    logger.error("Failed to clear batch parameters after executeLargeUpdate", e);
                }
            }
        } else {
            try {
                return stmt.executeLargeUpdate();
            } finally {
                try {
                    stmt.clearBatch();
                } catch (final SQLException e) {
                    logger.error("Failed to clear batch parameters after executeLargeUpdate", e);
                }
            }
        }
    }

    static int[] executeBatch(final Statement stmt) throws SQLException {
        final SqlLogConfig sqlLogConfig = JdbcUtil.minExecutionTimeForSqlPerfLog_TL.get();

        if (JdbcUtil.isToHandleSqlLog(sqlLogConfig)) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeBatch();
            } finally {
                JdbcUtil.handleSqlLog(stmt, sqlLogConfig, startTime);

                try {
                    stmt.clearBatch();
                } catch (final SQLException e) {
                    logger.error("Failed to clear batch parameters after executeBatch", e);
                }
            }
        } else {
            try {
                return stmt.executeBatch();
            } finally {
                try {
                    stmt.clearBatch();
                } catch (final SQLException e) {
                    logger.error("Failed to clear batch parameters after executeBatch", e);
                }
            }
        }
    }

    static long[] executeLargeBatch(final Statement stmt) throws SQLException {
        final SqlLogConfig sqlLogConfig = JdbcUtil.minExecutionTimeForSqlPerfLog_TL.get();

        if (JdbcUtil.isToHandleSqlLog(sqlLogConfig)) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeLargeBatch();
            } finally {
                JdbcUtil.handleSqlLog(stmt, sqlLogConfig, startTime);

                try {
                    stmt.clearBatch();
                } catch (final SQLException e) {
                    logger.error("Failed to clear batch parameters after executeLargeBatch", e);
                }
            }
        } else {
            try {
                return stmt.executeLargeBatch();
            } finally {
                try {
                    stmt.clearBatch();
                } catch (final SQLException e) {
                    logger.error("Failed to clear batch parameters after executeLargeBatch", e);
                }
            }
        }
    }

    static boolean execute(final PreparedStatement stmt) throws SQLException {
        final SqlLogConfig sqlLogConfig = JdbcUtil.minExecutionTimeForSqlPerfLog_TL.get();

        if (JdbcUtil.isToHandleSqlLog(sqlLogConfig)) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.execute();
            } finally {
                JdbcUtil.handleSqlLog(stmt, sqlLogConfig, startTime);

                clearParameters(stmt);
            }
        } else {
            try {
                return stmt.execute();
            } finally {
                clearParameters(stmt);
            }
        }
    }

    static void clearParameters(final PreparedStatement stmt) {
        // calling clearParameters() will impact/remove registered out parameters in CallableStatement.
        if (stmt == null || stmt instanceof CallableStatement) {
            // no
        } else {
            try {
                stmt.clearParameters();
            } catch (final SQLException e) {
                logger.error("Failed to clear parameters after execution", e);
            }
        }
    }

    static void setParameters(final ParsedSql parsedSql, final PreparedStatement stmt, final Object[] parameters) throws SQLException {
        final int parameterCount = parsedSql.getParameterCount();

        if (parameterCount == 0) {
            return;
        } else if (N.isEmpty(parameters)) {
            throw new IllegalArgumentException(
                    "The count of parameter in sql is: " + parsedSql.getParameterCount() + ". But the specified parameters is null or empty");
        }

        @SuppressWarnings("rawtypes")
        Type[] parameterTypes = null;
        Object[] parameterValues = null;

        if (isEntityOrMapParameter(parsedSql, parameters)) {
            final List<String> namedParameters = parsedSql.getNamedParameters();
            final Object parameter_0 = parameters[0];
            final Class<?> cls = parameter_0.getClass();

            parameterValues = new Object[parameterCount];

            if (Beans.isBeanClass(cls)) {
                @SuppressWarnings("UnnecessaryLocalVariable")
                final Object entity = parameter_0;
                final BeanInfo entityInfo = ParserUtil.getBeanInfo(cls);
                parameterTypes = new Type[parameterCount];
                PropInfo propInfo = null;

                for (int i = 0; i < parameterCount; i++) {
                    propInfo = entityInfo.getPropInfo(namedParameters.get(i));

                    if (propInfo == null) {
                        throw new IllegalArgumentException(
                                "No property found with name: " + namedParameters.get(i) + " in class: " + ClassUtil.getCanonicalClassName(cls));
                    }

                    parameterValues[i] = propInfo.getPropValue(entity);
                    parameterTypes[i] = propInfo.dbType;
                }
            } else if (parameter_0 instanceof Map) {
                final Map<String, Object> m = (Map<String, Object>) parameter_0;

                for (int i = 0; i < parameterCount; i++) {
                    parameterValues[i] = m.get(namedParameters.get(i));

                    if ((parameterValues[i] == null) && !m.containsKey(namedParameters.get(i))) {
                        throw new IllegalArgumentException("Parameter for property '" + namedParameters.get(i) + "' is missed");
                    }
                }
            } else {
                final EntityId entityId = (EntityId) parameter_0;

                for (int i = 0; i < parameterCount; i++) {
                    parameterValues[i] = entityId.get(namedParameters.get(i));

                    if ((parameterValues[i] == null) && !entityId.containsKey(namedParameters.get(i))) {
                        throw new IllegalArgumentException("Parameter for property '" + namedParameters.get(i) + "' is missed");
                    }
                }
            }
        } else {
            parameterValues = getParameterValues(parsedSql, parameters);
        }

        setParameters(stmt, parameterCount, parameterValues, parameterTypes);
    }

    @SuppressWarnings("rawtypes")
    static void setParameters(final PreparedStatement stmt, final int parameterCount, final Object[] parameters, final Type[] parameterTypes)
            throws SQLException {
        if (N.notEmpty(parameterTypes) && parameterTypes.length >= parameterCount) {
            for (int i = 0; i < parameterCount; i++) {
                parameterTypes[i].set(stmt, i + 1, parameters[i]);
            }
        } else if (N.notEmpty(parameters) && parameters.length >= parameterCount) {
            for (int i = 0; i < parameterCount; i++) {
                if (parameters[i] == null) {
                    stmt.setObject(i + 1, parameters[i]);
                } else {
                    N.typeOf(parameters[i].getClass()).set(stmt, i + 1, parameters[i]);
                }
            }
        }
    }

    /**
     * Extracts and returns parameter values from the provided parameters array.
     * If a single parameter is provided and it's an array or collection with sufficient elements,
     * this method unwraps it to use its contents as the actual parameter values.
     *
     * @param parsedSql the parsed SQL statement containing parameter information
     * @param parameters the parameters provided, which may be individual values or a single array/collection
     * @return an array of parameter values ready to be set on a PreparedStatement
     */
    static Object[] getParameterValues(final ParsedSql parsedSql, final Object... parameters) {
        if ((parameters.length == 1) && (parameters[0] != null)) {
            if (parameters[0] instanceof Object[] && ((((Object[]) parameters[0]).length) >= parsedSql.getParameterCount())) {
                return (Object[]) parameters[0];
            } else if (parameters[0] instanceof final Collection<?> c && (((List<?>) parameters[0]).size() >= parsedSql.getParameterCount())) {
                return c.toArray(new Object[0]);
            }
        }

        return parameters;
    }

    static boolean isEntityOrMapParameter(final ParsedSql parsedSql, final Object... parameters) {
        if (N.isEmpty(parsedSql.getNamedParameters()) || N.isEmpty(parameters) || (parameters.length != 1) || (parameters[0] == null)) {
            return false;
        }

        final Class<?> cls = parameters[0].getClass();

        return Beans.isBeanClass(cls) || Beans.isRecordClass(cls) || Map.class.isAssignableFrom(cls) || EntityId.class.isAssignableFrom(cls);
    }

    static final RowFilter INTERNAL_DUMMY_ROW_FILTER = RowFilter.ALWAYS_TRUE;

    static final RowExtractor INTERNAL_DUMMY_ROW_EXTRACTOR = (rs, outputRow) -> {
        throw new UnsupportedOperationException("DO NOT CALL ME.");
    };

    /**
     * Extracts data from the provided ResultSet and returns it as a Dataset.
     * This method reads all rows from the current position of the ResultSet.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ResultSet rs = stmt.executeQuery("SELECT * FROM users");
     * Dataset dataset = JdbcUtil.extractData(rs);
     * }</pre>
     *
     * @param rs The ResultSet to extract data from
     * @return A Dataset containing the extracted data
     * @throws SQLException If a SQL exception occurs while extracting data
     */
    public static Dataset extractData(final ResultSet rs) throws SQLException {
        return extractData(rs, false);
    }

    /**
     * Extracts data from the provided ResultSet starting from the specified offset and up to the specified count.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ResultSet rs = stmt.executeQuery("SELECT * FROM users");
     * Dataset dataset = JdbcUtil.extractData(rs, 10, 50); // Skip 10 rows, get next 50
     * }</pre>
     *
     * @param rs The ResultSet to extract data from
     * @param offset The starting position in the ResultSet (0-based)
     * @param count The maximum number of rows to extract
     * @return A Dataset containing the extracted data
     * @throws SQLException If a SQL exception occurs while extracting data
     */
    public static Dataset extractData(final ResultSet rs, final int offset, final int count) throws SQLException {
        return extractData(rs, offset, count, false);
    }

    /**
     * Extracts data from the provided ResultSet using the specified RowFilter.
     * Only rows that pass the filter will be included in the result.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Filter rows where age is greater than 18
     * ResultSet rs = stmt.executeQuery("SELECT name, age, email FROM users");
     * RowFilter adultFilter = resultSet -> resultSet.getInt("age") > 18;
     * Dataset adults = JdbcUtil.extractData(rs, adultFilter);
     *
     * // Filter rows based on multiple conditions
     * RowFilter activeUsersFilter = resultSet ->
     *     resultSet.getBoolean("is_active") &&
     *     resultSet.getString("status").equals("VERIFIED");
     * Dataset activeUsers = JdbcUtil.extractData(rs, activeUsersFilter);
     * }</pre>
     *
     * @param rs The ResultSet to extract data from, must not be {@code null}
     * @param filter The RowFilter to apply while extracting data. This is a functional interface that tests each row;
     *               only rows for which {@code filter.test(rs)} returns {@code true} will be included in the result.
     *               Must not be {@code null}.
     * @return A Dataset containing the filtered data
     * @throws SQLException If a SQL exception occurs while extracting data
     * @throws IllegalArgumentException If {@code rs} or {@code filter} is {@code null}
     * @see RowFilter
     * @see #extractData(ResultSet, RowFilter, RowExtractor)
     */
    public static Dataset extractData(final ResultSet rs, final RowFilter filter) throws SQLException {
        return extractData(rs, 0, Integer.MAX_VALUE, filter, INTERNAL_DUMMY_ROW_EXTRACTOR, false);
    }

    /**
     * Extracts data from the provided ResultSet using the specified RowExtractor.
     * The RowExtractor can transform or manipulate each row during extraction, allowing you to
     * modify column values before they are added to the resulting Dataset.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Transform email addresses to lowercase during extraction
     * ResultSet rs = stmt.executeQuery("SELECT id, name, email FROM users");
     * RowExtractor emailNormalizer = (resultSet, outputRow) -> {
     *     outputRow[2] = ((String) outputRow[2]).toLowerCase(); // email is at index 2
     * };
     * Dataset normalizedData = JdbcUtil.extractData(rs, emailNormalizer);
     *
     * // Mask sensitive data during extraction
     * RowExtractor maskSensitiveData = (resultSet, outputRow) -> {
     *     String ssn = (String) outputRow[3]; // assuming SSN is at index 3
     *     if (ssn != null && ssn.length() > 4) {
     *         outputRow[3] = "***-**-" + ssn.substring(ssn.length() - 4);
     *     }
     * };
     * Dataset maskedData = JdbcUtil.extractData(rs, maskSensitiveData);
     * }</pre>
     *
     * @param rs The ResultSet to extract data from, must not be {@code null}
     * @param rowExtractor The RowExtractor to apply while extracting data. This is a functional interface
     *                     that receives the current ResultSet and an output row array, allowing modification
     *                     of the row data before it's added to the Dataset. Must not be {@code null}.
     * @return A Dataset containing the extracted and transformed data
     * @throws SQLException If a SQL exception occurs while extracting data
     * @throws IllegalArgumentException If {@code rs} or {@code rowExtractor} is {@code null}
     * @see RowExtractor
     * @see #extractData(ResultSet, RowFilter, RowExtractor)
     */
    public static Dataset extractData(final ResultSet rs, final RowExtractor rowExtractor) throws SQLException {
        return extractData(rs, 0, Integer.MAX_VALUE, INTERNAL_DUMMY_ROW_FILTER, rowExtractor, false);
    }

    /**
     * Extracts data from the provided ResultSet using both RowFilter and RowExtractor.
     * This method combines filtering and transformation: first, rows are filtered based on the
     * {@code RowFilter}, then the remaining rows are transformed using the {@code RowExtractor}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Filter adult users and normalize their email addresses
     * ResultSet rs = stmt.executeQuery("SELECT id, name, email, age FROM users");
     *
     * RowFilter adultFilter = resultSet -> resultSet.getInt("age") >= 18;
     * RowExtractor emailNormalizer = (resultSet, outputRow) -> {
     *     outputRow[2] = ((String) outputRow[2]).toLowerCase(); // normalize email
     * };
     * Dataset adultUsersWithNormalizedEmails = JdbcUtil.extractData(rs, adultFilter, emailNormalizer);
     *
     * // Filter active transactions and calculate a derived field
     * RowFilter activeFilter = resultSet -> resultSet.getString("status").equals("ACTIVE");
     * RowExtractor addTaxColumn = (resultSet, outputRow) -> {
     *     // Assuming amount is at index 3, calculate tax and store it
     *     Double amount = (Double) outputRow[3];
     *     if (amount != null) {
     *         outputRow[3] = amount * 1.08; // Add 8% tax
     *     }
     * };
     * Dataset activeTransactionsWithTax = JdbcUtil.extractData(rs, activeFilter, addTaxColumn);
     * }</pre>
     *
     * @param rs The ResultSet to extract data from, must not be {@code null}
     * @param filter The RowFilter to apply for filtering rows. Only rows for which {@code filter.test(rs)}
     *               returns {@code true} will be processed by the extractor. Must not be {@code null}.
     * @param rowExtractor The RowExtractor to apply for transforming filtered rows. This receives the
     *                     ResultSet and output row array for modification. Must not be {@code null}.
     * @return A Dataset containing the filtered and transformed data
     * @throws SQLException If a SQL exception occurs while extracting data
     * @throws IllegalArgumentException If any argument is {@code null}
     * @see RowFilter
     * @see RowExtractor
     * @see #extractData(ResultSet, RowFilter)
     * @see #extractData(ResultSet, RowExtractor)
     */
    public static Dataset extractData(final ResultSet rs, final RowFilter filter, final RowExtractor rowExtractor) throws SQLException {
        return extractData(rs, 0, Integer.MAX_VALUE, filter, rowExtractor, false);
    }

    /**
     * Extracts data from the provided ResultSet and returns it as a Dataset.
     * This method allows specifying whether to close the ResultSet after extraction.
     *
     * @param rs The ResultSet to extract data from
     * @param closeResultSet Whether to close the ResultSet after extraction
     * @return A Dataset containing the extracted data
     * @throws SQLException If a SQL exception occurs while extracting data
     */
    public static Dataset extractData(final ResultSet rs, final boolean closeResultSet) throws SQLException {
        return extractData(rs, 0, Integer.MAX_VALUE, closeResultSet);
    }

    /**
     * Extracts data from the provided ResultSet with specified offset and count.
     * This method allows specifying whether to close the ResultSet after extraction.
     *
     * @param rs The ResultSet to extract data from
     * @param offset The starting position in the ResultSet
     * @param count The number of rows to extract
     * @param closeResultSet Whether to close the ResultSet after extraction
     * @return A Dataset containing the extracted data
     * @throws SQLException If a SQL exception occurs while extracting data
     */
    public static Dataset extractData(final ResultSet rs, final int offset, final int count, final boolean closeResultSet) throws SQLException {
        return extractData(rs, offset, count, INTERNAL_DUMMY_ROW_FILTER, INTERNAL_DUMMY_ROW_EXTRACTOR, closeResultSet);
    }

    /**
     * Extracts data from the provided ResultSet with offset, count, and filter.
     * This method allows specifying whether to close the ResultSet after extraction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Extract 10 active users starting from the 5th record
     * ResultSet rs = stmt.executeQuery("SELECT * FROM users ORDER BY created_at");
     * RowFilter activeFilter = resultSet -> resultSet.getBoolean("is_active");
     * Dataset activeUsers = JdbcUtil.extractData(rs, 5, 10, activeFilter, true);
     *
     * // Paginated extraction with filtering
     * int pageSize = 20;
     * int pageNumber = 3; // zero-based
     * RowFilter verifiedFilter = resultSet -> resultSet.getString("status").equals("VERIFIED");
     * Dataset page = JdbcUtil.extractData(rs, pageNumber * pageSize, pageSize, verifiedFilter, false);
     * }</pre>
     *
     * @param rs The ResultSet to extract data from, must not be {@code null}
     * @param offset The starting position (0-based) in the ResultSet, must be non-negative
     * @param count The maximum number of rows to extract, must be non-negative
     * @param filter The RowFilter to apply while extracting data. Only rows for which {@code filter.test(rs)}
     *               returns {@code true} will be included. Must not be {@code null}.
     * @param closeResultSet Whether to close the ResultSet after extraction
     * @return A Dataset containing the extracted data
     * @throws SQLException If a SQL exception occurs while extracting data
     * @throws IllegalArgumentException If any argument is invalid (null or negative values)
     * @see #extractData(ResultSet, int, int, RowFilter, RowExtractor, boolean)
     */
    public static Dataset extractData(final ResultSet rs, final int offset, final int count, final RowFilter filter, final boolean closeResultSet)
            throws SQLException {
        return extractData(rs, offset, count, filter, INTERNAL_DUMMY_ROW_EXTRACTOR, closeResultSet);
    }

    /**
     * Extracts data from the provided ResultSet with offset, count, and extractor.
     * This method allows specifying whether to close the ResultSet after extraction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Extract 50 users starting from position 100, masking sensitive data
     * ResultSet rs = stmt.executeQuery("SELECT id, name, email, ssn FROM users");
     * RowExtractor maskExtractor = (resultSet, outputRow) -> {
     *     String ssn = (String) outputRow[3];
     *     if (ssn != null && ssn.length() > 4) {
     *         outputRow[3] = "***-**-" + ssn.substring(ssn.length() - 4);
     *     }
     * };
     * Dataset maskedUsers = JdbcUtil.extractData(rs, 100, 50, maskExtractor, true);
     *
     * // Paginated extraction with data transformation
     * RowExtractor normalizer = (resultSet, outputRow) -> {
     *     outputRow[2] = ((String) outputRow[2]).toLowerCase(); // normalize email
     * };
     * Dataset page = JdbcUtil.extractData(rs, 0, 25, normalizer, false);
     * }</pre>
     *
     * @param rs The ResultSet to extract data from, must not be {@code null}
     * @param offset The starting position (0-based) in the ResultSet, must be non-negative
     * @param count The maximum number of rows to extract, must be non-negative
     * @param rowExtractor The RowExtractor to apply for transforming rows. This receives the ResultSet
     *                     and output row array for modification. Must not be {@code null}.
     * @param closeResultSet Whether to close the ResultSet after extraction
     * @return A Dataset containing the extracted and transformed data
     * @throws SQLException If a SQL exception occurs while extracting data
     * @throws IllegalArgumentException If any argument is invalid (null or negative values)
     * @see #extractData(ResultSet, int, int, RowFilter, RowExtractor, boolean)
     */
    public static Dataset extractData(final ResultSet rs, final int offset, final int count, final RowExtractor rowExtractor, final boolean closeResultSet)
            throws SQLException {
        return extractData(rs, offset, count, INTERNAL_DUMMY_ROW_FILTER, rowExtractor, closeResultSet);
    }

    /**
     * Extracts data from the provided ResultSet with all extraction options.
     * This is the most comprehensive extraction method providing full control over the extraction process,
     * including pagination (offset/count), filtering (RowFilter), and transformation (RowExtractor).
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Complete example: paginated extraction with filtering and transformation
     * ResultSet rs = stmt.executeQuery("SELECT id, name, email, age, salary FROM employees");
     *
     * // Filter: only employees over 25 years old
     * RowFilter ageFilter = resultSet -> resultSet.getInt("age") > 25;
     *
     * // Transform: mask salary to show only salary range
     * RowExtractor salaryMasker = (resultSet, outputRow) -> {
     *     Double salary = (Double) outputRow[4]; // salary at index 4
     *     if (salary != null) {
     *         outputRow[4] = (salary / 10000) * 10000; // Round to nearest 10k
     *     }
     * };
     *
     * // Extract page 2 (records 20-40) with filtering and transformation, auto-close ResultSet
     * Dataset employees = JdbcUtil.extractData(rs, 20, 20, ageFilter, salaryMasker, true);
     *
     * // Another example: Extract top 100 active users with normalized emails
     * RowFilter activeFilter = resultSet -> resultSet.getBoolean("is_active");
     * RowExtractor emailNormalizer = (resultSet, outputRow) -> {
     *     outputRow[2] = ((String) outputRow[2]).toLowerCase();
     * };
     * Dataset activeUsers = JdbcUtil.extractData(rs, 0, 100, activeFilter, emailNormalizer, false);
     * }</pre>
     *
     * @param rs The ResultSet to extract data from, must not be {@code null}
     * @param offset The starting position (0-based) in the ResultSet, must be non-negative
     * @param count The maximum number of rows to extract, must be non-negative
     * @param filter The RowFilter to apply for filtering rows. Only rows for which {@code filter.test(rs)}
     *               returns {@code true} will be processed. Must not be {@code null}.
     * @param rowExtractor The RowExtractor to apply for transforming filtered rows. This receives the
     *                     ResultSet and output row array for modification. Must not be {@code null}.
     * @param closeResultSet Whether to close the ResultSet after extraction completes (or if an error occurs)
     * @return A Dataset containing the filtered and transformed data
     * @throws SQLException If a SQL exception occurs while extracting data
     * @throws IllegalArgumentException If any argument is invalid (null or negative values)
     * @see RowFilter
     * @see RowExtractor
     * @see #extractData(ResultSet, RowFilter, RowExtractor)
     */
    public static Dataset extractData(final ResultSet rs, final int offset, final int count, final RowFilter filter, final RowExtractor rowExtractor,
            final boolean closeResultSet) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(rs, cs.ResultSet);
        N.checkArgNotNegative(offset, cs.offset);
        N.checkArgNotNegative(count, cs.count);
        N.checkArgNotNull(filter, cs.filter);
        N.checkArgNotNull(rowExtractor, cs.rowExtractor);
        final boolean checkDateType = checkDateType(rs);

        try {
            return JdbcUtil.extractResultSetToDataset(rs, offset, count, filter, rowExtractor, checkDateType);
        } finally {
            if (closeResultSet) {
                closeQuietly(rs);
            }
        }
    }

    static Dataset extractResultSetToDataset(final ResultSet rs, final int offset, int count, final RowFilter filter, final RowExtractor rowExtractor,
            final boolean checkDateType) throws SQLException {
        final ResultSetMetaData rsmd = rs.getMetaData();
        final int columnCount = rsmd.getColumnCount();
        final List<String> columnNameList = new ArrayList<>(columnCount);
        final List<List<Object>> columnList = new ArrayList<>(columnCount);

        for (int i = 0; i < columnCount;) {
            columnNameList.add(JdbcUtil.getColumnLabel(rsmd, ++i));
            columnList.add(new ArrayList<>());
        }

        JdbcUtil.skip(rs, offset);

        if (filter == INTERNAL_DUMMY_ROW_FILTER) {
            if (rowExtractor == INTERNAL_DUMMY_ROW_EXTRACTOR) {
                while (count > 0 && rs.next()) {
                    for (int i = 0; i < columnCount;) {
                        columnList.get(i).add(JdbcUtil.getColumnValue(rs, ++i, checkDateType));
                    }

                    count--;
                }
            } else {
                final Object[] outputRow = new Object[columnCount];

                while (count > 0 && rs.next()) {
                    rowExtractor.accept(rs, outputRow);

                    for (int i = 0; i < columnCount; i++) {
                        columnList.get(i).add(outputRow[i]);
                    }

                    count--;
                }
            }
        } else {
            if (rowExtractor == INTERNAL_DUMMY_ROW_EXTRACTOR) {
                while (count > 0 && rs.next()) {
                    if (filter.test(rs)) {
                        for (int i = 0; i < columnCount;) {
                            columnList.get(i).add(JdbcUtil.getColumnValue(rs, ++i, checkDateType));
                        }

                        count--;
                    }
                }
            } else {
                final Object[] outputRow = new Object[columnCount];

                while (count > 0 && rs.next()) {
                    if (filter.test(rs)) {
                        rowExtractor.accept(rs, outputRow);

                        for (int i = 0; i < columnCount; i++) {
                            columnList.get(i).add(outputRow[i]);
                        }

                        count--;
                    }
                }
            }
        }

        // return new RowDataset(null, entityClass, columnNameList, columnList);
        return new RowDataset(columnNameList, columnList);
    }

    static <R> R extractAndCloseResultSet(final ResultSet rs, final ResultExtractor<? extends R> resultExtractor) throws SQLException {
        try {
            return checkNotResultSet(resultExtractor.apply(rs));
        } finally {
            closeQuietly(rs);
        }
    }

    static <R> R extractAndCloseResultSet(final ResultSet rs, final BiResultExtractor<? extends R> resultExtractor) throws SQLException {
        try {
            return checkNotResultSet(resultExtractor.apply(rs, getColumnLabelList(rs)));
        } finally {
            closeQuietly(rs);
        }
    }

    /**
     * Creates a stream from the provided ResultSet.
     * Each element in the stream is an Object array containing values from all columns of a row.
     * It's the user's responsibility to close the ResultSet after the stream is finished.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ResultSet rs = stmt.executeQuery("SELECT * FROM users");
     * try {
     *     JdbcUtil.stream(rs)
     *         .forEach(row -> {
     *             System.out.println(Arrays.toString(row));
     *         });
     * } finally {
     *     rs.close();
     * }
     * 
     * // Or with auto-close:
     * JdbcUtil.stream(resultSet).onClose(Fn.closeQuietly(resultSet))
     *     .forEach(row -> processRow(row));
     * }</pre>
     *
     * @param resultSet The ResultSet to create a stream from
     * @return A Stream of Object arrays containing the data from the ResultSet
     * @throws IllegalArgumentException If the provided ResultSet is null
     */
    public static Stream<Object[]> stream(final ResultSet resultSet) {
        return stream(resultSet, Object[].class);
    }

    /**
     * Creates a stream from the provided ResultSet, mapping each row to the specified target class.
     * It's the user's responsibility to close the ResultSet after the stream is finished.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ResultSet rs = stmt.executeQuery("SELECT * FROM users");
     * JdbcUtil.stream(rs, User.class)
     *     .onClose(Fn.closeQuietly(rs))
     *     .filter(user -> user.getAge() > 18)
     *     .forEach(user -> processUser(user));
     * }</pre>
     *
     * @param <T> The type of the result extracted from the ResultSet
     * @param resultSet The ResultSet to create a stream from
     * @param targetClass The class of the result type. Column names from the ResultSet will be mapped to properties of this class
     * @return A Stream of the extracted results
     * @throws IllegalArgumentException If the provided arguments are invalid
     */
    public static <T> Stream<T> stream(final ResultSet resultSet, final Class<? extends T> targetClass) throws IllegalArgumentException {
        N.checkArgNotNull(targetClass, cs.targetClass);
        N.checkArgNotNull(resultSet, cs.resultSet);

        return stream(resultSet, BiRowMapper.to(targetClass));
    }

    /**
     * Creates a stream from the provided ResultSet using the specified RowMapper.
     * It's the user's responsibility to close the input {@code resultSet} after the stream is finished, or call:
     * {@code JdbcUtil.stream(resultSet, rowMapper).onClose(Fn.closeQuietly(resultSet))...}
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * RowMapper<String> nameMapper = rs -> rs.getString("name");
     * JdbcUtil.stream(resultSet, nameMapper)
     *     .onClose(Fn.closeQuietly(resultSet))
     *     .forEach(name -> System.out.println(name));
     * }</pre>
     *
     * @param <T> the type of the result extracted from the ResultSet
     * @param resultSet the ResultSet to create a stream from
     * @param rowMapper the RowMapper to apply while extracting data. This mapper is called for each row in the ResultSet
     * @return a Stream of the extracted results
     * @throws IllegalArgumentException if the provided arguments are invalid
     */
    public static <T> Stream<T> stream(final ResultSet resultSet, final RowMapper<? extends T> rowMapper) throws IllegalArgumentException {
        N.checkArgNotNull(resultSet, cs.resultSet);
        N.checkArgNotNull(rowMapper, cs.rowMapper);

        return Stream.of(iterate(resultSet, rowMapper, null));
    }

    static <T> ObjIteratorEx<T> iterate(final ResultSet resultSet, final RowMapper<? extends T> rowMapper, final Runnable onClose) {
        return new ObjIteratorEx<>() {
            private boolean hasNext;

            @Override
            public boolean hasNext() {
                if (!hasNext) {
                    try {
                        hasNext = resultSet.next();
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                }

                return hasNext;
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException(InternalUtil.ERROR_MSG_FOR_NO_SUCH_EX);
                }

                hasNext = false;

                try {
                    return rowMapper.apply(resultSet);
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e);
                }
            }

            @Override
            public void advance(final long n) throws IllegalArgumentException {
                N.checkArgNotNegative(n, cs.n);

                final long m = hasNext ? n - 1 : n;

                try {
                    JdbcUtil.skip(resultSet, m);
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e);
                }

                hasNext = false;
            }

            @Override
            public long count() {
                long cnt = hasNext ? 1 : 0;
                hasNext = false;

                try {
                    while (resultSet.next()) {
                        cnt++;
                    }
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e);
                }

                return cnt;
            }

            private boolean isClosed = false;

            @Override
            public void close() {
                if (isClosed) {
                    return;
                }

                isClosed = true;

                if (onClose != null) {
                    onClose.run();
                }
            }
        };
    }

    /**
     * Creates a stream from the provided ResultSet using the specified RowFilter and RowMapper.
     * Only rows that pass the filter will be included in the stream.
     * It's the user's responsibility to close the input {@code resultSet} after the stream is finished.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * RowFilter ageFilter = rs -> rs.getInt("age") > 18;
     * RowMapper<User> userMapper = rs -> new User(rs.getString("name"), rs.getInt("age"));
     * JdbcUtil.stream(resultSet, ageFilter, userMapper)
     *     .onClose(Fn.closeQuietly(resultSet))
     *     .forEach(user -> processAdultUser(user));
     * }</pre>
     *
     * @param <T> the type of the result extracted from the ResultSet
     * @param resultSet the ResultSet to create a stream from
     * @param rowFilter the RowFilter to apply while filtering rows. Only rows for which this filter returns {@code true} will be included
     * @param rowMapper the RowMapper to apply while extracting data from filtered rows
     * @return a Stream of the extracted results
     * @throws IllegalArgumentException if the provided arguments are invalid
     */
    public static <T> Stream<T> stream(final ResultSet resultSet, final RowFilter rowFilter, final RowMapper<? extends T> rowMapper)
            throws IllegalArgumentException {
        N.checkArgNotNull(resultSet, cs.resultSet);
        N.checkArgNotNull(rowFilter, cs.rowFilter);
        N.checkArgNotNull(rowMapper, cs.rowMapper);

        return Stream.of(iterate(resultSet, rowFilter, rowMapper, null));
    }

    static <T> ObjIteratorEx<T> iterate(final ResultSet resultSet, final RowFilter rowFilter, final RowMapper<? extends T> rowMapper, final Runnable onClose) {
        N.checkArgNotNull(resultSet, cs.resultSet);
        N.checkArgNotNull(rowFilter, cs.rowFilter);
        N.checkArgNotNull(rowMapper, cs.rowMapper);

        return new ObjIteratorEx<>() {
            private boolean hasNext;

            @Override
            public boolean hasNext() {
                if (!hasNext) {
                    try {
                        while (resultSet.next()) {
                            if (rowFilter.test(resultSet)) {
                                hasNext = true;
                                break;
                            }
                        }
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                }

                return hasNext;
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException(InternalUtil.ERROR_MSG_FOR_NO_SUCH_EX);
                }

                hasNext = false;

                try {
                    return rowMapper.apply(resultSet);
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e);
                }
            }

            private boolean isClosed = false;

            @Override
            public void close() {
                if (isClosed) {
                    return;
                }

                isClosed = true;

                if (onClose != null) {
                    onClose.run();
                }
            }
        };
    }

    /**
     * Creates a stream from the provided ResultSet using the specified BiRowMapper.
     * BiRowMapper receives both the ResultSet and column labels, allowing for more flexible mapping.
     * It's the user's responsibility to close the input {@code resultSet} after the stream is finished.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BiRowMapper<Map<String, Object>> mapMapper = (rs, columnLabels) -> {
     *     Map<String, Object> row = new HashMap<>();
     *     for (String label : columnLabels) {
     *         row.put(label, rs.getObject(label));
     *     }
     *     return row;
     * };
     * JdbcUtil.stream(resultSet, mapMapper)
     *     .onClose(Fn.closeQuietly(resultSet))
     *     .forEach(row -> System.out.println(row));
     * }</pre>
     *
     * @param <T> the type of the result extracted from the ResultSet
     * @param resultSet the ResultSet to create a stream from
     * @param rowMapper the BiRowMapper to apply while extracting data. This mapper receives both the ResultSet and column labels
     * @return a Stream of the extracted results
     * @throws IllegalArgumentException if the provided arguments are invalid
     */
    public static <T> Stream<T> stream(final ResultSet resultSet, final BiRowMapper<? extends T> rowMapper) throws IllegalArgumentException {
        N.checkArgNotNull(resultSet, cs.resultSet);
        N.checkArgNotNull(rowMapper, cs.rowMapper);

        return Stream.of(iterate(resultSet, rowMapper, null));
    }

    static <T> ObjIteratorEx<T> iterate(final ResultSet resultSet, final BiRowMapper<? extends T> rowMapper, final Runnable onClose) {
        return new ObjIteratorEx<>() {
            private List<String> columnLabels = null;
            private boolean hasNext;

            @Override
            public boolean hasNext() {
                if (!hasNext) {
                    try {
                        hasNext = resultSet.next();
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                }

                return hasNext;
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException(InternalUtil.ERROR_MSG_FOR_NO_SUCH_EX);
                }

                hasNext = false;

                if (columnLabels == null) {
                    try {
                        columnLabels = getColumnLabelList(resultSet);
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                }

                try {
                    return rowMapper.apply(resultSet, columnLabels);
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e);
                }
            }

            @Override
            public void advance(final long n) {
                N.checkArgNotNegative(n, cs.n);

                final long m = hasNext ? n - 1 : n;

                try {
                    JdbcUtil.skip(resultSet, m);
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e);
                }

                hasNext = false;
            }

            @Override
            public long count() {
                long cnt = hasNext ? 1 : 0;
                hasNext = false;

                try {
                    while (resultSet.next()) {
                        cnt++;
                    }
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e);
                }

                return cnt;
            }

            private boolean isClosed = false;

            @Override
            public void close() {
                if (isClosed) {
                    return;
                }

                isClosed = true;

                if (onClose != null) {
                    onClose.run();
                }
            }
        };
    }

    /**
     * Creates a stream from the provided ResultSet using the specified BiRowFilter and BiRowMapper.
     * Both the filter and mapper receive the ResultSet and column labels for maximum flexibility.
     * It's the user's responsibility to close the input {@code resultSet} after the stream is finished.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BiRowFilter hasNonNullValues = (rs, columnLabels) -> {
     *     for (String label : columnLabels) {
     *         if (rs.getObject(label) != null) return true;
     *     }
     *     return false;
     * };
     * BiRowMapper<String> csvMapper = (rs, columnLabels) -> 
     *     columnLabels.stream()
     *         .map(label -> rs.getString(label))
     *         .collect(Collectors.joining(","));
     * 
     * JdbcUtil.stream(resultSet, hasNonNullValues, csvMapper)
     *     .onClose(Fn.closeQuietly(resultSet))
     *     .forEach(csvRow -> System.out.println(csvRow));
     * }</pre>
     *
     * @param <T> the type of the result extracted from the ResultSet
     * @param resultSet the ResultSet to create a stream from
     * @param rowFilter the BiRowFilter to apply while filtering rows. Both ResultSet and column labels are provided
     * @param rowMapper the BiRowMapper to apply while extracting data from filtered rows
     * @return a Stream of the extracted results
     * @throws IllegalArgumentException if the provided arguments are invalid
     */
    public static <T> Stream<T> stream(final ResultSet resultSet, final BiRowFilter rowFilter, final BiRowMapper<? extends T> rowMapper)
            throws IllegalArgumentException {
        N.checkArgNotNull(resultSet, cs.resultSet);
        N.checkArgNotNull(rowFilter, cs.rowFilter);
        N.checkArgNotNull(rowMapper, cs.rowMapper);

        return Stream.of(iterate(resultSet, rowFilter, rowMapper));
    }

    static <T> ObjIteratorEx<T> iterate(final ResultSet resultSet, final BiRowFilter rowFilter, final BiRowMapper<? extends T> rowMapper) {
        return new ObjIteratorEx<>() {
            private List<String> columnLabels = null;
            private boolean hasNext;

            @Override
            public boolean hasNext() {
                if (columnLabels == null) {
                    try {
                        columnLabels = JdbcUtil.getColumnLabelList(resultSet);
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                }

                if (!hasNext) {
                    try {
                        while (resultSet.next()) {
                            if (rowFilter.test(resultSet, columnLabels)) {
                                hasNext = true;
                                break;
                            }
                        }
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                }

                return hasNext;
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException(InternalUtil.ERROR_MSG_FOR_NO_SUCH_EX);
                }

                hasNext = false;

                try {
                    return rowMapper.apply(resultSet, columnLabels);
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e);
                }
            }
        };
    }

    /**
     * Creates a stream from the provided ResultSet using the specified column index.
     * This is useful when you only need values from a single column.
     * It's the user's responsibility to close the input {@code resultSet} after the stream is finished.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Stream all names from the first column
     * JdbcUtil.stream(resultSet, 1)
     *     .onClose(Fn.closeQuietly(resultSet))
     *     .forEach(name -> System.out.println(name));
     * }</pre>
     *
     * @param <T> the type of the result extracted from the ResultSet
     * @param resultSet the ResultSet to create a stream from
     * @param columnIndex the index of the column to extract data from, starting from 1
     * @return a Stream of the extracted results
     * @throws IllegalArgumentException if the provided arguments are invalid
     */
    public static <T> Stream<T> stream(final ResultSet resultSet, final int columnIndex) throws IllegalArgumentException {
        N.checkArgNotNull(resultSet, cs.resultSet);
        N.checkArgPositive(columnIndex, cs.columnIndex);

        final boolean checkDateType = JdbcUtil.checkDateType(resultSet);
        final RowMapper<? extends T> rowMapper = rs -> (T) getColumnValue(resultSet, columnIndex, checkDateType);

        return stream(resultSet, rowMapper);
    }

    /**
     * Creates a stream from the provided ResultSet using the specified column name.
     * This is useful when you only need values from a single column identified by name.
     * It's the user's responsibility to close the input {@code resultSet} after the stream is finished.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Stream all email addresses
     * JdbcUtil.stream(resultSet, "email")
     *     .onClose(Fn.closeQuietly(resultSet))
     *     .filter(email -> email != {@code null} && email.contains("@"))
     *     .forEach(email -> sendNewsletter(email));
     * }</pre>
     *
     * @param <T> the type of the result extracted from the ResultSet
     * @param resultSet the ResultSet to create a stream from
     * @param columnName the name of the column to extract data from
     * @return a Stream of the extracted results
     * @throws IllegalArgumentException if the provided arguments are invalid
     */
    public static <T> Stream<T> stream(final ResultSet resultSet, final String columnName) throws IllegalArgumentException {
        N.checkArgNotNull(resultSet, cs.resultSet);
        N.checkArgNotEmpty(columnName, cs.columnName);

        final RowMapper<? extends T> rowMapper = new RowMapper<>() {
            private int columnIndex = -1;
            private boolean checkDateType = true;

            @Override
            public T apply(final ResultSet rs) throws SQLException {
                if (columnIndex == -1) {
                    columnIndex = getColumnIndex(resultSet, columnName);
                    checkDateType = JdbcUtil.checkDateType(resultSet);
                }

                return (T) getColumnValue(resultSet, columnIndex, checkDateType);
            }
        };

        return stream(resultSet, rowMapper);
    }

    /**
     * Extracts all ResultSets from the provided Statement and returns them as a Stream of Dataset.
     * This is useful when executing stored procedures that return multiple result sets.
     * It's the user's responsibility to close the input {@code stmt} after the stream is finished.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * CallableStatement stmt = conn.prepareCall("{call sp_get_multiple_results()}");
     * JdbcUtil.streamAllResultSets(stmt)
     *     .onClose(Fn.closeQuietly(stmt))
     *     .forEach(dataset -> {
     *         System.out.println("Result set with " + dataset.size() + " rows");
     *         dataset.println();
     *     });
     * }</pre>
     *
     * @param stmt the Statement to extract ResultSets from
     * @return a Stream of Dataset containing the extracted ResultSets
     */
    public static Stream<Dataset> streamAllResultSets(final Statement stmt) {
        return streamAllResultSets(stmt, ResultExtractor.TO_DATA_SET);
    }

    /**
     * Extracts all ResultSets from the provided Statement and returns them as a Stream.
     * Each ResultSet is processed by the provided ResultExtractor.
     * It's the user's responsibility to close the input {@code stmt} after the stream is finished.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ResultExtractor<List<String>> namesExtractor = rs -> {
     *     List<String> names = new ArrayList<>();
     *     while (rs.next()) {
     *         names.add(rs.getString("name"));
     *     }
     *     return names;
     * };
     * 
     * JdbcUtil.streamAllResultSets(stmt, namesExtractor)
     *     .onClose(Fn.closeQuietly(stmt))
     *     .forEach(namesList -> System.out.println("Found " + namesList.size() + " names"));
     * }</pre>
     *
     * @param <R> the type of the result extracted from the ResultSet
     * @param stmt the Statement to extract ResultSets from
     * @param resultExtractor the ResultExtractor to apply while extracting data from each ResultSet
     * @return a Stream of the extracted results
     * @throws IllegalArgumentException if the provided arguments are invalid
     */
    @SuppressWarnings("resource")
    public static <R> Stream<R> streamAllResultSets(final Statement stmt, final ResultExtractor<R> resultExtractor) throws IllegalArgumentException {
        N.checkArgNotNull(stmt, cs.stmt);
        N.checkArgNotNull(resultExtractor, cs.resultExtractor);

        final Supplier<ObjIteratorEx<ResultSet>> supplier = Fn.memoize(() -> iterateAllResultSets(stmt, true));

        return Stream.just(supplier)
                .onClose(() -> supplier.get().close())
                .flatMap(it -> Stream.of(it.get()))
                .map(Fn.ff(rs -> extractAndCloseResultSet(rs, resultExtractor)));
    }

    /**
     * Extracts all ResultSets from the provided Statement and returns them as a Stream.
     * Each ResultSet is processed by the provided BiResultExtractor which also receives column labels.
     * It's the user's responsibility to close the input {@code stmt} after the stream is finished.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BiResultExtractor<Map<String, List<Object>>> columnarExtractor = (rs, columnLabels) -> {
     *     Map<String, List<Object>> columns = new HashMap<>();
     *     for (String label : columnLabels) {
     *         columns.put(label, new ArrayList<>());
     *     }
     *     while (rs.next()) {
     *         for (String label : columnLabels) {
     *             columns.get(label).add(rs.getObject(label));
     *         }
     *     }
     *     return columns;
     * };
     * 
     * JdbcUtil.streamAllResultSets(stmt, columnarExtractor)
     *     .onClose(Fn.closeQuietly(stmt))
     *     .forEach(columnsMap -> {
     *         columnsMap.forEach((col, values) -> 
     *             System.out.println(col + ": " + values.size() + " values"));
     *     });
     * }</pre>
     *
     * @param <R> the type of the result extracted from the ResultSet
     * @param stmt the Statement to extract ResultSets from
     * @param resultExtractor the BiResultExtractor to apply while extracting data
     * @return a Stream of the extracted results
     * @throws IllegalArgumentException if the provided arguments are invalid
     */
    @SuppressWarnings("resource")
    public static <R> Stream<R> streamAllResultSets(final Statement stmt, final BiResultExtractor<R> resultExtractor) throws IllegalArgumentException {
        N.checkArgNotNull(stmt, cs.stmt);
        N.checkArgNotNull(resultExtractor, cs.resultExtractor);

        final Supplier<ObjIteratorEx<ResultSet>> supplier = Fn.memoize(() -> iterateAllResultSets(stmt, true));

        return Stream.just(supplier)
                .onClose(() -> supplier.get().close())
                .flatMap(it -> Stream.of(it.get()))
                .map(Fn.ff(rs -> extractAndCloseResultSet(rs, resultExtractor)));
    }

    static ObjIteratorEx<ResultSet> iterateAllResultSets(final Statement stmt, final boolean isFirstResultSet) { //NOSONAR
        return new ObjIteratorEx<>() {
            private final Holder<ResultSet> resultSetHolder = new Holder<>();
            private boolean isNextResultSet = isFirstResultSet;
            private boolean noMoreResult = false;

            @Override
            public boolean hasNext() {
                if (resultSetHolder.isNull() && !noMoreResult) {
                    try {
                        while (true) {
                            if (isNextResultSet) {
                                resultSetHolder.setValue(stmt.getResultSet());
                                isNextResultSet = false;
                                break;
                            } else if (stmt.getUpdateCount() != -1) {
                                isNextResultSet = stmt.getMoreResults();
                            } else {
                                noMoreResult = true;

                                break;
                            }
                        }
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                }

                return resultSetHolder.isNotNull();
            }

            @Override
            public ResultSet next() {
                if (!hasNext()) {
                    throw new NoSuchElementException(InternalUtil.ERROR_MSG_FOR_NO_SUCH_EX);
                }

                return resultSetHolder.getAndSet(null);
            }

            private boolean isClosed = false;

            @Override
            public void close() {
                if (isClosed) {
                    return;
                }

                isClosed = true;

                if (resultSetHolder.isNotNull()) {
                    JdbcUtil.closeQuietly(resultSetHolder.getAndSet(null));
                }
            }
        };
    }

    /**
     * Runs a {@code Stream} with each element (page) loaded from the database table by running the specified SQL {@code query}.
     * The query must be ordered by at least one key/id and have a result size limitation (e.g., LIMIT pageSize).
     * This method is useful for processing large result sets in manageable chunks.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String query = "SELECT * FROM users WHERE id > ? ORDER BY id LIMIT 1000";
     * JdbcUtil.queryByPage(dataSource, query, 1000, (preparedQuery, previousPage) -> {
     *     if (previousPage == null) {
     *         preparedQuery.setLong(1, 0);
     *     } else {
     *         long lastId = previousPage.getLong(previousPage.size() - 1, "id");
     *         preparedQuery.setLong(1, lastId);
     *     }
     * }).forEach(page -> {
     *     System.out.println("Processing " + page.size() + " records");
     *     // Process the page
     * });
     * }</pre>
     *
     * @param ds the DataSource to get the connection from
     * @param query the SQL query to run for each page. Must include ORDER BY and LIMIT/FETCH clauses
     * @param pageSize the number of rows to fetch per page
     * @param paramSetter the BiParametersSetter to set parameters for the query; the second parameter is the result set for the previous page (null for first page)
     * @return a Stream of Dataset, each representing a page of results
     */
    @SuppressWarnings("rawtypes")
    public static Stream<Dataset> queryByPage(final javax.sql.DataSource ds, final String query, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, Dataset> paramSetter) {
        return queryByPage(ds, query, pageSize, paramSetter, Jdbc.ResultExtractor.TO_DATA_SET);
    }

    /**
     * Runs a {@code Stream} with each element (page) loaded from the database table by running the specified SQL {@code query}.
     * The query must be ordered by at least one key/id and have a result size limitation.
     * Each page is processed by the provided ResultExtractor.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String query = "SELECT * FROM orders WHERE order_date > ? ORDER BY order_id LIMIT 500";
     * ResultExtractor<List<Order>> ordersExtractor = rs -> {
     *     List<Order> orders = new ArrayList<>();
     *     while (rs.next()) {
     *         orders.add(new Order(rs.getLong("order_id"), rs.getDate("order_date")));
     *     }
     *     return orders;
     * };
     * 
     * JdbcUtil.queryByPage(dataSource, query, 500, (preparedQuery, previousOrders) -> {
     *     if (previousOrders == null) {
     *         preparedQuery.setDate(1, startDate);
     *     } else {
     *         Order lastOrder = previousOrders.get(previousOrders.size() - 1);
     *         preparedQuery.setDate(1, lastOrder.getOrderDate());
     *     }
     * }, ordersExtractor)
     * .forEach(orders -> processOrderBatch(orders));
     * }</pre>
     *
     * @param <R> the type of the result extracted from each page
     * @param ds the DataSource to get the connection from
     * @param query the SQL query to run for each page
     * @param pageSize the number of rows to fetch per page
     * @param paramSetter the BiParametersSetter to set parameters for the query
     * @param resultExtractor the ResultExtractor to extract results from the ResultSet
     * @return a Stream of the extracted results
     */
    @SuppressWarnings("rawtypes")
    public static <R> Stream<R> queryByPage(final javax.sql.DataSource ds, final String query, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, R> paramSetter, final Jdbc.ResultExtractor<R> resultExtractor) {

        final boolean isNamedQuery = ParsedSql.parse(query).getNamedParameters().size() > 0;

        return Stream.of(Holder.of((R) null)) //
                .cycled()
                .map(it -> {
                    try {
                        final R ret = (isNamedQuery ? JdbcUtil.prepareNamedQuery(ds, query) : JdbcUtil.prepareQuery(ds, query)) //
                                .setFetchDirectionToForward()
                                .setFetchSize(pageSize)
                                .settParameters(it.value(), paramSetter)
                                .query(resultExtractor);

                        it.setValue(ret);

                        return ret;
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                })
                .takeWhile(JdbcUtil::isNotEmptyResult);
    }

    /**
     * Runs a {@code Stream} with each element (page) loaded from the database table by running the specified SQL {@code query}.
     * The query must be ordered by at least one key/id and have a result size limitation.
     * Each page is processed by the provided BiResultExtractor.
     *
     * @param <R> the type of the result extracted from each page
     * @param ds the DataSource to get the connection from
     * @param query the SQL query to run for each page
     * @param pageSize the number of rows to fetch per page
     * @param paramSetter the BiParametersSetter to set parameters for the query
     * @param resultExtractor the BiResultExtractor to extract results from the ResultSet
     * @return a Stream of the extracted results
     */
    @SuppressWarnings("rawtypes")
    public static <R> Stream<R> queryByPage(final javax.sql.DataSource ds, final String query, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, R> paramSetter, final Jdbc.BiResultExtractor<R> resultExtractor) {

        final boolean isNamedQuery = ParsedSql.parse(query).getNamedParameters().size() > 0;

        return Stream.of(Holder.of((R) null)) //
                .cycled()
                .map(it -> {
                    try {
                        final R ret = (isNamedQuery ? JdbcUtil.prepareNamedQuery(ds, query) : JdbcUtil.prepareQuery(ds, query)) //
                                .setFetchDirectionToForward()
                                .setFetchSize(pageSize)
                                .settParameters(it.value(), paramSetter)
                                .query(resultExtractor);

                        it.setValue(ret);

                        return ret;
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                })
                .takeWhile(JdbcUtil::isNotEmptyResult);
    }

    /**
     * Runs a {@code Stream} with each element (page) loaded from the database table by running the specified SQL {@code query}.
     * Similar to the DataSource version but uses an existing Connection.
     * The query must be ordered by at least one key/id and have a result size limitation.
     *
     * @param conn the Connection to use for queries
     * @param query the SQL query to run for each page
     * @param pageSize the number of rows to fetch per page
     * @param paramSetter the BiParametersSetter to set parameters for the query
     * @return a Stream of Dataset, each representing a page of results
     */
    @SuppressWarnings("rawtypes")
    public static Stream<Dataset> queryByPage(final Connection conn, final String query, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, Dataset> paramSetter) {
        return queryByPage(conn, query, pageSize, paramSetter, Jdbc.ResultExtractor.TO_DATA_SET);
    }

    /**
     * Runs a {@code Stream} with each element (page) loaded from the database table by running the specified SQL {@code query}.
     * Similar to the DataSource version but uses an existing Connection.
     * Each page is processed by the provided ResultExtractor.
     *
     * @param <R> the type of the result extracted from each page
     * @param conn the Connection to use for queries
     * @param query the SQL query to run for each page
     * @param pageSize the number of rows to fetch per page
     * @param paramSetter the BiParametersSetter to set parameters for the query
     * @param resultExtractor the ResultExtractor to extract results from the ResultSet
     * @return a Stream of the extracted results
     */
    @SuppressWarnings("rawtypes")
    public static <R> Stream<R> queryByPage(final Connection conn, final String query, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, R> paramSetter, final Jdbc.ResultExtractor<R> resultExtractor) {

        final boolean isNamedQuery = ParsedSql.parse(query).getNamedParameters().size() > 0;

        return Stream.of(Holder.of((R) null)) //
                .cycled()
                .map(it -> {
                    try {
                        final R ret = (isNamedQuery ? JdbcUtil.prepareNamedQuery(conn, query) : JdbcUtil.prepareQuery(conn, query)) //
                                .setFetchDirectionToForward()
                                .setFetchSize(pageSize)
                                .settParameters(it.value(), paramSetter)
                                .query(resultExtractor);

                        it.setValue(ret);

                        return ret;
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                })
                .takeWhile(JdbcUtil::isNotEmptyResult);
    }

    /**
     * Runs a {@code Stream} with each element (page) loaded from the database table by running the specified SQL {@code query}.
     * Similar to the DataSource version but uses an existing Connection.
     * Each page is processed by the provided BiResultExtractor.
     *
     * @param <R> the type of the result extracted from each page
     * @param conn the Connection to use for queries
     * @param query the SQL query to run for each page
     * @param pageSize the number of rows to fetch per page
     * @param paramSetter the BiParametersSetter to set parameters for the query
     * @param resultExtractor the BiResultExtractor to extract results from the ResultSet
     * @return a Stream of the extracted results
     */
    @SuppressWarnings("rawtypes")
    public static <R> Stream<R> queryByPage(final Connection conn, final String query, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, R> paramSetter, final Jdbc.BiResultExtractor<R> resultExtractor) {

        final boolean isNamedQuery = ParsedSql.parse(query).getNamedParameters().size() > 0;

        return Stream.of(Holder.of((R) null)) //
                .cycled()
                .map(it -> {
                    try {
                        final R ret = (isNamedQuery ? JdbcUtil.prepareNamedQuery(conn, query) : JdbcUtil.prepareQuery(conn, query)) //
                                .setFetchDirectionToForward()
                                .setFetchSize(pageSize)
                                .settParameters(it.value(), paramSetter)
                                .query(resultExtractor);

                        it.setValue(ret);

                        return ret;
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                })
                .takeWhile(JdbcUtil::isNotEmptyResult);
    }

    @SuppressWarnings("rawtypes")
    static boolean isNotEmptyResult(final Object ret) {
        if (ret == null) {
            return false;
        }

        if (ret instanceof Dataset) {
            return N.notEmpty((Dataset) ret);
        } else if (ret instanceof Collection) {
            return N.notEmpty((Collection) ret);
        } else if (ret instanceof Map) {
            return N.notEmpty((Map) ret);
        } else if (ret instanceof Iterable) {
            return N.notEmpty((Iterable) ret);
        } else if (ret instanceof Iterator) {
            return N.notEmpty((Iterator) ret);
        }

        return true;
    }

    static <R> R checkNotResultSet(final R result) {
        if (result instanceof ResultSet) {
            throw new UnsupportedOperationException("The result value of ResultExtractor/BiResultExtractor.apply can't be ResultSet");
        }

        return result;
    }

    static boolean checkDateType(final ResultSet rs) {
        try {
            return checkDateType(rs.getStatement());
        } catch (final Exception e) {
            return true;
        }
    }

    static boolean checkDateType(final Statement stmt) {
        try {
            return Strings.containsIgnoreCase(JdbcUtil.getDBProductInfo(stmt.getConnection()).productName(), "Oracle");
        } catch (final SQLException e) {
            return true;
        }
    }

    interface OutParameterGetter {

        Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException;

        Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException;
    }

    private static final Map<Integer, OutParameterGetter> sqlTypeGetterMap = new HashMap<>(Types.class.getDeclaredFields().length * 2);

    static {
        sqlTypeGetterMap.put(Types.BOOLEAN, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getBoolean(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getBoolean(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.BIT, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getByte(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getByte(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.TINYINT, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getByte(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getByte(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.SMALLINT, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getShort(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getShort(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.INTEGER, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getInt(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getInt(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.BIGINT, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getLong(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getLong(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.FLOAT, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getFloat(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getFloat(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.DOUBLE, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getDouble(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getDouble(outParameterName);
            }
        });

        sqlTypeGetterMap.put(Types.REAL, sqlTypeGetterMap.get(Types.DOUBLE));

        sqlTypeGetterMap.put(Types.NUMERIC, sqlTypeGetterMap.get(Types.BIGINT));

        sqlTypeGetterMap.put(Types.DECIMAL, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getBigDecimal(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getBigDecimal(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.CHAR, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getString(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getString(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.VARCHAR, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getString(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getString(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.LONGVARCHAR, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getString(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getString(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.NCHAR, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getNString(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getNString(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.NVARCHAR, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getNString(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getNString(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.LONGNVARCHAR, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getNString(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getNString(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.DATE, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getDate(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getDate(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.TIME, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getTime(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getTime(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.TIMESTAMP, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getTimestamp(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getTimestamp(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.BLOB, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getBlob(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getBlob(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.CLOB, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getClob(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getClob(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.NCLOB, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getNClob(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getNClob(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.ARRAY, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getArray(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getArray(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.REF, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getRef(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getRef(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.ROWID, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getRowId(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getRowId(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.SQLXML, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getSQLXML(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getSQLXML(outParameterName);
            }
        });
    }

    private static final OutParameterGetter objOutParameterGetter = new OutParameterGetter() {
        @Override
        public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
            return stmt.getObject(outParameterIndex);
        }

        @Override
        public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
            return stmt.getObject(outParameterName);
        }
    };

    /**
     * Checks if a table exists in the database.
     * This method attempts to execute a simple SELECT query on the table to determine its existence.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * if (JdbcUtil.doesTableExist(ds, "users")) {
     *     System.out.println("Users table exists");
     * } else {
     *     System.out.println("Users table does not exist");
     * }
     * }</pre>
     *
     * @param ds The data source to get the connection from
     * @param tableName The name of the table to check
     * @return {@code true} if the table exists, {@code false} otherwise
     * @throws UncheckedSQLException if a database error occurs (other than table not existing)
     */
    public static boolean doesTableExist(final javax.sql.DataSource ds, final String tableName) {
        Connection conn = null;

        try {
            conn = ds.getConnection();
            return doesTableExist(conn, tableName);
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            releaseConnection(conn, ds);
        }
    }

    /**
     * Checks if a table exists in the database.
     * This method attempts to execute a simple SELECT query on the table to determine its existence.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * if (JdbcUtil.doesTableExist(connection, "users")) {
     *     System.out.println("Users table exists");
     * } else {
     *     System.out.println("Users table does not exist");
     * }
     * }</pre>
     *
     * @param conn The database connection to use for checking table existence
     * @param tableName The name of the table to check
     * @return {@code true} if the table exists, {@code false} otherwise
     * @throws UncheckedSQLException if a database error occurs (other than table not existing)
     */
    public static boolean doesTableExist(final Connection conn, final String tableName) {
        try {
            execute(conn, "SELECT 1 FROM " + tableName + " WHERE 1 > 2");

            return true;
        } catch (final SQLException e) {
            if (isTableNotExistsException(e)) {
                return false;
            }

            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Creates a table if it does not already exist in the database.
     * This method first checks if the table exists, and if not, executes the provided schema to create it.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String schema = "CREATE TABLE users (" +
     *                 "id BIGINT PRIMARY KEY, " +
     *                 "name VARCHAR(100), " +
     *                 "email VARCHAR(255))";
     * boolean created = JdbcUtil.createTableIfNotExists(connection, "users", schema);
     * System.out.println(created ? "Table created" : "Table already exists");
     * }</pre>
     *
     * @param conn The database connection to use for creating the table
     * @param tableName The name of the table to create
     * @param schema The SQL schema definition (CREATE TABLE statement) for the table
     * @return {@code true} if the table was created, {@code false} if the table already exists
     * @throws UncheckedSQLException if a database access error occurs during table creation
     */
    public static boolean createTableIfNotExists(final Connection conn, final String tableName, final String schema) {
        if (doesTableExist(conn, tableName)) {
            return false;
        }

        try {
            execute(conn, schema);

            return true;
        } catch (final SQLException e) {
            return false;
        }
    }

    /**
     * Drops the specified table if it exists in the database.
     * This method first checks if the table exists before attempting to drop it,
     * preventing errors from trying to drop a non-existent table.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * boolean dropped = JdbcUtil.dropTableIfExists(connection, "temp_users");
     * System.out.println(dropped ? "Table dropped" : "Table did not exist");
     * }</pre>
     *
     * @param conn The database connection to use for dropping the table
     * @param tableName The name of the table to drop
     * @return {@code true} if the table was dropped, {@code false} if the table did not exist or could not be dropped
     */
    public static boolean dropTableIfExists(final Connection conn, final String tableName) {
        try {
            if (doesTableExist(conn, tableName)) {
                execute(conn, "DROP TABLE " + tableName);

                return true;
            }
        } catch (final SQLException e) {
            // ignore.
        }

        return false;
    }

    /**
     * Returns a new instance of {@code DBLock} for implementing global locks using a database table.
     * This provides a distributed locking mechanism that works across multiple application instances.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBLock lock = JdbcUtil.getDBLock(dataSource, "distributed_locks");
     * if (lock.acquire("job_processor")) {
     *     try {
     *         // Perform exclusive operation
     *     } finally {
     *         lock.release("job_processor");
     *     }
     * }
     * }</pre>
     *
     * @param ds The data source to use for database connections
     * @param tableName The name of the table used for storing lock information
     * @return A new instance of {@code DBLock} for distributed locking
     */
    public static DBLock getDBLock(final javax.sql.DataSource ds, final String tableName) {
        return new DBLock(ds, tableName);
    }

    /**
     * Checks if is table not exists exception.
     *
     * @param e
     * @return {@code true}, if is table not exists exception
     */
    static boolean isTableNotExistsException(final Throwable e) {
        if (e instanceof final SQLException sqlException) {
            if (sqlException.getSQLState() != null && sqlStateForTableNotExists.contains(sqlException.getSQLState())) {
                return true;
            }

            final String msg = N.defaultIfNull(e.getMessage(), "").toLowerCase();
            return Strings.isNotEmpty(msg) && (msg.contains("not exist") || msg.contains("doesn't exist") || msg.contains("not found"));
        }

        return false;
    }

    // TODO is it right to do it?
    //    static <ST extends Statement> ST checkStatement(ST stmt, String sql) {
    //        if (isSqlPerfLogAllowed && N.notEmpty(sql)) {
    //            stmtPoolForSql.put(stmt, Poolable.wrap(sql, 3000, 3000));
    //        }
    //
    //        return stmt;
    //    }

    static final com.landawn.abacus.util.function.Predicate<Object> defaultIdTester = JdbcUtil::isDefaultIdPropValue;

    /**
     * Checks if is default id prop value.
     *
     * @param value
     * @return {@code true}, if is default id prop value
     * @deprecated for internal only.
     */
    @Deprecated
    @Internal
    static boolean isDefaultIdPropValue(final Object value) {
        if ((value == null) || (value instanceof Number && (((Number) value).longValue() == 0))) {
            return true;
        } else if (value instanceof EntityId) {
            return N.allMatch(((EntityId) value).entrySet(), it -> JdbcUtil.isDefaultIdPropValue(it.getValue()));
        } else if (Beans.isBeanClass(value.getClass())) {
            final Class<?> entityClass = value.getClass();
            final List<String> idPropNameList = QueryUtil.getIdFieldNames(entityClass);

            if (N.isEmpty(idPropNameList)) {
                return true;
            } else {
                final BeanInfo idBeanInfo = ParserUtil.getBeanInfo(entityClass);
                return N.allMatch(idPropNameList, idName -> JdbcUtil.isDefaultIdPropValue(idBeanInfo.getPropValue(value, idName)));
            }
        }

        return false;
    }

    static <ID> boolean isAllNullIds(final List<ID> ids) {
        return isAllNullIds(ids, defaultIdTester);
    }

    static <ID> boolean isAllNullIds(final List<ID> ids, final Predicate<Object> isDefaultIdTester) {
        return N.notEmpty(ids) && ids.stream().allMatch(isDefaultIdTester);
    }

    //    /**
    //     * Executes the specified SQL action.
    //     *
    //     * @param sqlAction The SQL action to be executed.
    //     * @throws IllegalArgumentException If the SQL action is invalid.
    //     */
    //    @Beta
    //    public static void run(final Throwables.Runnable<Exception> sqlAction) throws IllegalArgumentException {
    //        N.checkArgNotNull(sqlAction, s.sqlAction);
    //
    //        try {
    //            sqlAction.run();
    //        } catch (final Exception e) {
    //           throw ExceptionUtil.toRuntimeException(e, true);
    //        }
    //    }
    //
    //    /**
    //     * Executes the specified SQL action with the given input parameter.
    //     *
    //     * @param <T> The type of the input parameter.
    //     * @param t The input parameter.
    //     * @param sqlAction The SQL action to be executed.
    //     * @throws IllegalArgumentException If the SQL action is invalid.
    //     */
    //    @Beta
    //    public static <T> void run(final T t, final Throwables.Consumer<? super T, Exception> sqlAction) throws IllegalArgumentException {
    //        N.checkArgNotNull(sqlAction, s.sqlAction);
    //
    //        try {
    //            sqlAction.accept(t);
    //        } catch (final Exception e) {
    //            throw ExceptionUtil.toRuntimeException(e, true);
    //        }
    //    }
    //
    //    /**
    //     * Executes the specified SQL action with the given input parameters.
    //     *
    //     * @param <T> The type of the first input parameter.
    //     * @param <U> The type of the second input parameter.
    //     * @param t The first input parameter.
    //     * @param u The second input parameter.
    //     * @param sqlAction The SQL action to be executed.
    //     * @throws IllegalArgumentException If the SQL action is invalid.
    //     */
    //    @Beta
    //    public static <T, U> void run(final T t, final U u, final Throwables.BiConsumer<? super T, ? super U, Exception> sqlAction)
    //            throws IllegalArgumentException {
    //        N.checkArgNotNull(sqlAction, s.sqlAction);
    //
    //        try {
    //            sqlAction.accept(t, u);
    //        } catch (final Exception e) {
    //            throw ExceptionUtil.toRuntimeException(e, true);
    //        }
    //    }
    //
    //    /**
    //     * Executes the specified SQL action with the given input parameters.
    //     *
    //     * @param <A> The type of the first input parameter.
    //     * @param <B> The type of the second input parameter.
    //     * @param <C> The type of the third input parameter.
    //     * @param a The first input parameter.
    //     * @param b The second input parameter.
    //     * @param c The third input parameter.
    //     * @param sqlAction The SQL action to be executed.
    //     * @throws IllegalArgumentException If the SQL action is invalid.
    //     */
    //    @Beta
    //    public static <A, B, C> void run(final A a, final B b, final C c, final Throwables.TriConsumer<? super A, ? super B, ? super C, Exception> sqlAction)
    //            throws IllegalArgumentException {
    //        N.checkArgNotNull(sqlAction, s.sqlAction);
    //
    //        try {
    //            sqlAction.accept(a, b, c);
    //        } catch (final Exception e) {
    //            throw ExceptionUtil.toRuntimeException(e, true);
    //        }
    //    }
    //
    //    /**
    //     * Executes the specified SQL action and returns the result.
    //     *
    //     * @param <R> The type of the result.
    //     * @param sqlAction The SQL action to be executed.
    //     * @return The result of the SQL action.
    //     * @throws IllegalArgumentException If the SQL action is {@code null}.
    //     */
    //    @Beta
    //    public static <R> R call(final Callable<R> sqlAction) throws IllegalArgumentException {
    //        N.checkArgNotNull(sqlAction, s.sqlAction);
    //
    //        try {
    //            return sqlAction.call();
    //        } catch (final Exception e) {
    //            throw ExceptionUtil.toRuntimeException(e, true);
    //        }
    //    }
    //
    //    /**
    //     * Executes the specified SQL action with the given input parameter and returns the result.
    //     *
    //     * @param <T> The type of the input parameter.
    //     * @param <R> The type of the result.
    //     * @param t The input parameter.
    //     * @param sqlAction The SQL action to be executed.
    //     * @return The result of the SQL action.
    //     * @throws IllegalArgumentException If the SQL action is invalid.
    //     */
    //    @Beta
    //    public static <T, R> R call(final T t, final Throwables.Function<? super T, ? extends R, Exception> sqlAction) throws IllegalArgumentException {
    //        N.checkArgNotNull(sqlAction, s.sqlAction);
    //
    //        try {
    //            return sqlAction.apply(t);
    //        } catch (final Exception e) {
    //            throw ExceptionUtil.toRuntimeException(e, true);
    //        }
    //    }
    //
    //    /**
    //     * Calls the specified SQL action with two input parameters and returns the result.
    //     *
    //     * @param <T> The type of the first input parameter.
    //     * @param <U> The type of the second input parameter.
    //     * @param <R> The type of the result.
    //     * @param t The first input parameter.
    //     * @param u The second input parameter.
    //     * @param sqlAction The SQL action to be executed.
    //     * @return The result of the SQL action.
    //     * @throws IllegalArgumentException If the SQL action is invalid.
    //     */
    //    @Beta
    //    public static <T, U, R> R call(final T t, final U u, final Throwables.BiFunction<? super T, ? super U, ? extends R, Exception> sqlAction)
    //            throws IllegalArgumentException {
    //        N.checkArgNotNull(sqlAction, s.sqlAction);
    //
    //        try {
    //            return sqlAction.apply(t, u);
    //        } catch (final Exception e) {
    //            throw ExceptionUtil.toRuntimeException(e, true);
    //        }
    //    }
    //
    //    /**
    //     * Calls the specified SQL action with three input parameters and returns the result.
    //     *
    //     * @param <A> The type of the first input parameter.
    //     * @param <B> The type of the second input parameter.
    //     * @param <C> The type of the third input parameter.
    //     * @param <R> The type of the result.
    //     * @param a The first input parameter.
    //     * @param b The second input parameter.
    //     * @param c The third input parameter.
    //     * @param sqlAction The SQL action to be executed.
    //     * @return The result of the SQL action.
    //     * @throws IllegalArgumentException If the SQL action is invalid.
    //     */
    //    @Beta
    //    public static <A, B, C, R> R call(final A a, final B b, final C c,
    //            final Throwables.TriFunction<? super A, ? super B, ? super C, ? extends R, Exception> sqlAction) throws IllegalArgumentException {
    //        N.checkArgNotNull(sqlAction, s.sqlAction);
    //
    //        try {
    //            return sqlAction.apply(a, b, c);
    //        } catch (final Exception e) {
    //            throw ExceptionUtil.toRuntimeException(e, true);
    //        }
    //    }

    /**
     * Asynchronously runs the specified SQL action in a separate thread.
     * Note: Any transaction started in current thread won't be automatically applied to the specified 
     * {@code sqlAction} which will be executed in another thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ContinuableFuture<Void> future = JdbcUtil.asyncRun(() -> {
     *     // Perform database operations
     *     JdbcUtil.executeUpdate(dataSource, "UPDATE users SET status = ? WHERE id = ?", "active", userId);
     * });
     *
     * future.thenRun(() -> System.out.println("Update completed"));
     * }</pre>
     *
     * @param sqlAction The SQL action to be executed asynchronously
     * @return A ContinuableFuture representing the result of the asynchronous computation
     * @throws IllegalArgumentException if the specified SQL action is {@code null}
     */
    @Beta
    public static ContinuableFuture<Void> asyncRun(final Throwables.Runnable<Exception> sqlAction) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        return asyncExecutor.execute(sqlAction);
    }

    /**
     * Asynchronously runs two SQL actions in separate threads.
     * Note: Any transaction started in current thread won't be automatically applied to the specified 
     * {@code sqlAction} which will be executed in another thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Tuple2<ContinuableFuture<Void>, ContinuableFuture<Void>> futures = JdbcUtil.asyncRun(
     *     () -> JdbcUtil.executeUpdate(dataSource, "UPDATE users SET status = ?", "active"),
     *     () -> JdbcUtil.executeUpdate(dataSource, "UPDATE orders SET processed = ?", true)
     * );
     *
     * ContinuableFuture.allOf(futures._1, futures._2).thenRun(() ->
     *     System.out.println("Both updates completed")
     * );
     * }</pre>
     *
     * @param sqlAction1 The first SQL action to be executed asynchronously
     * @param sqlAction2 The second SQL action to be executed asynchronously
     * @return A Tuple2 containing two ContinuableFuture objects representing the results of the asynchronous computations
     * @throws IllegalArgumentException if any of the SQL actions are {@code null}
     */
    @Beta
    public static Tuple2<ContinuableFuture<Void>, ContinuableFuture<Void>> asyncRun(final Throwables.Runnable<Exception> sqlAction1,
            final Throwables.Runnable<Exception> sqlAction2) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction1, cs.sqlAction1);
        N.checkArgNotNull(sqlAction2, cs.sqlAction2);

        return Tuple.of(asyncExecutor.execute(sqlAction1), asyncExecutor.execute(sqlAction2));
    }

    /**
     * Asynchronously runs three SQL actions in separate threads.
     * Note: Any transaction started in current thread won't be automatically applied to the specified 
     * {@code sqlAction} which will be executed in another thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Tuple3<ContinuableFuture<Void>, ContinuableFuture<Void>, ContinuableFuture<Void>> futures =
     *     JdbcUtil.asyncRun(
     *         () -> JdbcUtil.executeUpdate(dataSource, "UPDATE users SET status = ?", "active"),
     *         () -> JdbcUtil.executeUpdate(dataSource, "UPDATE orders SET processed = ?", true),
     *         () -> JdbcUtil.executeUpdate(dataSource, "UPDATE inventory SET updated = ?", new Date())
     *     );
     *
     * ContinuableFuture.allOf(futures._1, futures._2, futures._3).thenRun(() ->
     *     System.out.println("All updates completed")
     * );
     * }</pre>
     *
     * @param sqlAction1 The first SQL action to be executed asynchronously
     * @param sqlAction2 The second SQL action to be executed asynchronously
     * @param sqlAction3 The third SQL action to be executed asynchronously
     * @return A Tuple3 containing three ContinuableFuture objects representing the results of the asynchronous computations
     * @throws IllegalArgumentException if any of the SQL actions are {@code null}
     */
    @Beta
    public static Tuple3<ContinuableFuture<Void>, ContinuableFuture<Void>, ContinuableFuture<Void>> asyncRun(final Throwables.Runnable<Exception> sqlAction1,
            final Throwables.Runnable<Exception> sqlAction2, final Throwables.Runnable<Exception> sqlAction3) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction1, cs.sqlAction1);
        N.checkArgNotNull(sqlAction2, cs.sqlAction2);
        N.checkArgNotNull(sqlAction3, cs.sqlAction3);

        return Tuple.of(asyncExecutor.execute(sqlAction1), asyncExecutor.execute(sqlAction2), asyncExecutor.execute(sqlAction3));
    }

    /**
     * Asynchronously runs the specified SQL action with the given parameter.
     * Note: Any transaction started in current thread won't be automatically applied to the specified 
     * {@code sqlAction} which will be executed in another thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User entity = new User(123, "John", "john@example.com");
     * ContinuableFuture<Void> future = JdbcUtil.asyncRun(entity, e -> {
     *     JdbcUtil.executeUpdate(dataSource, "INSERT INTO users (name, email) VALUES (?, ?)", e.getName(), e.getEmail());
     * });
     *
     * future.thenRun(() -> System.out.println("User inserted"));
     * }</pre>
     *
     * @param <T> The type of the parameter
     * @param t The parameter to be passed to the SQL action
     * @param sqlAction The SQL action to be executed with the parameter
     * @return A ContinuableFuture representing the result of the asynchronous computation
     * @throws IllegalArgumentException if the SQL action is {@code null}
     */
    @Beta
    public static <T> ContinuableFuture<Void> asyncRun(final T t, final Throwables.Consumer<? super T, Exception> sqlAction) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        return asyncExecutor.execute(() -> sqlAction.accept(t));
    }

    /**
     * Asynchronously runs the specified SQL action with two parameters.
     * Note: Any transaction started in current thread won't be automatically applied to the specified 
     * {@code sqlAction} which will be executed in another thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ContinuableFuture<Void> future = JdbcUtil.asyncRun(userId, status, 
     *     (id, st) -> JdbcUtil.update(dataSource, "UPDATE users SET status = ? WHERE id = ?", st, id)
     * );
     * 
     * future.thenRun(() -> System.out.println("Status updated"));
     * }</pre>
     *
     * @param <T> The type of the first parameter
     * @param <U> The type of the second parameter
     * @param t The first parameter to be passed to the SQL action
     * @param u The second parameter to be passed to the SQL action
     * @param sqlAction The SQL action to be executed with the parameters
     * @return A ContinuableFuture representing the result of the asynchronous computation
     * @throws IllegalArgumentException if the SQL action is {@code null}
     */
    @Beta
    public static <T, U> ContinuableFuture<Void> asyncRun(final T t, final U u, final Throwables.BiConsumer<? super T, ? super U, Exception> sqlAction)
            throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        return asyncExecutor.execute(() -> sqlAction.accept(t, u));
    }

    /**
     * Asynchronously runs the specified SQL action with three parameters.
     * Note: Any transaction started in current thread won't be automatically applied to the specified 
     * {@code sqlAction} which will be executed in another thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ContinuableFuture<Void> future = JdbcUtil.asyncRun(userId, orderId, status,
     *     (uid, oid, st) -> {
     *         JdbcUtil.update(dataSource, "UPDATE orders SET status = ? WHERE user_id = ? AND order_id = ?", 
     *                         st, uid, oid);
     *     }
     * );
     * 
     * future.thenRun(() -> System.out.println("Order status updated"));
     * }</pre>
     *
     * @param <A> The type of the first parameter
     * @param <B> The type of the second parameter
     * @param <C> The type of the third parameter
     * @param a The first parameter to be passed to the SQL action
     * @param b The second parameter to be passed to the SQL action
     * @param c The third parameter to be passed to the SQL action
     * @param sqlAction The SQL action to be executed with the parameters
     * @return A ContinuableFuture representing the result of the asynchronous computation
     * @throws IllegalArgumentException if the SQL action is {@code null}
     */
    @Beta
    public static <A, B, C> ContinuableFuture<Void> asyncRun(final A a, final B b, final C c,
            final Throwables.TriConsumer<? super A, ? super B, ? super C, Exception> sqlAction) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        return asyncExecutor.execute(() -> sqlAction.accept(a, b, c));
    }

    /**
     * Asynchronously calls the specified SQL action and returns a result.
     * Note: Any transaction started in current thread won't be automatically applied to the specified 
     * {@code sqlAction} which will be executed in another thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ContinuableFuture<User> future = JdbcUtil.asyncCall(() -> {
     *     return JdbcUtil.prepareQuery(dataSource, "SELECT * FROM users WHERE id = ?").setLong(1, userId).findFirst(User.class).orElse(null);
     * });
     *
     * future.thenAccept(user -> System.out.println("Found user: " + (user != {@code null} ? user.getName() : "none")));
     * }</pre>
     *
     * @param <R> The type of the result
     * @param sqlAction The SQL action that produces a result
     * @return A ContinuableFuture representing the result of the asynchronous computation
     * @throws IllegalArgumentException if the SQL action is {@code null}
     */
    @Beta
    public static <R> ContinuableFuture<R> asyncCall(final Callable<R> sqlAction) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        return asyncExecutor.execute(sqlAction);
    }

    /**
     * Asynchronously calls two SQL actions and returns their results.
     * Note: Any transaction started in current thread won't be automatically applied to the specified 
     * {@code sqlAction} which will be executed in another thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Tuple2<ContinuableFuture<String>, ContinuableFuture<List<String>>> futures = JdbcUtil.asyncCall(
     *     () -> JdbcUtil.prepareQuery(dataSource, "SELECT name FROM users WHERE id = ?").setLong(1, userId).queryForSingleResult(String.class).orElse(null),
     *     () -> JdbcUtil.prepareQuery(dataSource, "SELECT email FROM users WHERE age > ?").setInt(1, 18).list(String.class)
     * );
     *
     * futures._1.thenAccept(name -> System.out.println("User name: " + name));
     * futures._2.thenAccept(results -> System.out.println("Found " + results.size() + " emails"));
     * }</pre>
     *
     * @param <R1> The type of the result from the first action
     * @param <R2> The type of the result from the second action
     * @param sqlAction1 The first SQL action that produces a result
     * @param sqlAction2 The second SQL action that produces a result
     * @return A Tuple2 containing two ContinuableFutures representing the results of the asynchronous computations
     * @throws IllegalArgumentException if any of the SQL actions are {@code null}
     */
    @Beta
    public static <R1, R2> Tuple2<ContinuableFuture<R1>, ContinuableFuture<R2>> asyncCall(final Callable<R1> sqlAction1, final Callable<R2> sqlAction2)
            throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction1, cs.sqlAction1);
        N.checkArgNotNull(sqlAction2, cs.sqlAction2);

        return Tuple.of(asyncExecutor.execute(sqlAction1), asyncExecutor.execute(sqlAction2));
    }

    /**
     * Asynchronously calls three SQL actions and returns their results.
     * Note: Any transaction started in current thread won't be automatically applied to the specified 
     * {@code sqlAction} which will be executed in another thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Tuple3<ContinuableFuture<Long>, ContinuableFuture<BigDecimal>, ContinuableFuture<List<Product>>> futures =
     *     JdbcUtil.asyncCall(
     *         () -> JdbcUtil.queryForSingleResult(Long.class, dataSource, "SELECT COUNT(*) FROM orders"),
     *         () -> JdbcUtil.queryForSingleResult(BigDecimal.class, dataSource, "SELECT SUM(total) FROM orders"),
     *         () -> JdbcUtil.prepareQuery(dataSource, "SELECT * FROM products WHERE stock < ?").setInt(1, 10).list(Product.class)
     *     );
     * 
     * ContinuableFuture.allOf(futures._1, futures._2, futures._3).thenRun(() -> {
     *     System.out.println("All queries completed");
     * });
     * }</pre>
     *
     * @param <R1> The type of the result from the first action
     * @param <R2> The type of the result from the second action
     * @param <R3> The type of the result from the third action
     * @param sqlAction1 The first SQL action that produces a result
     * @param sqlAction2 The second SQL action that produces a result
     * @param sqlAction3 The third SQL action that produces a result
     * @return A Tuple3 containing three ContinuableFutures representing the results of the asynchronous computations
     * @throws IllegalArgumentException if any of the SQL actions are {@code null}
     */
    @Beta
    public static <R1, R2, R3> Tuple3<ContinuableFuture<R1>, ContinuableFuture<R2>, ContinuableFuture<R3>> asyncCall(final Callable<R1> sqlAction1,
            final Callable<R2> sqlAction2, final Callable<R3> sqlAction3) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction1, cs.sqlAction1);
        N.checkArgNotNull(sqlAction2, cs.sqlAction2);
        N.checkArgNotNull(sqlAction3, cs.sqlAction3);

        return Tuple.of(asyncExecutor.execute(sqlAction1), asyncExecutor.execute(sqlAction2), asyncExecutor.execute(sqlAction3));
    }

    /**
     * Asynchronously calls the specified SQL action with one parameter and returns a result.
     * Note: Any transaction started in current thread won't be automatically applied to the specified 
     * {@code sqlAction} which will be executed in another thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ContinuableFuture<User> future = JdbcUtil.asyncCall(123L,
     *     param -> JdbcUtil.prepareQuery(dataSource, "SELECT * FROM users WHERE id = ?").setLong(1, param).queryForSingleResult(User.class).orElse(null)
     * );
     *
     * future.thenAccept(user -> System.out.println("Found user: " + (user != {@code null} ? user.getName() : "none")));
     * }</pre>
     *
     * @param <T> The type of the parameter
     * @param <R> The type of the result
     * @param t The parameter to pass to the SQL action
     * @param sqlAction The SQL action that takes a parameter and produces a result
     * @return A ContinuableFuture representing the result of the asynchronous computation
     * @throws IllegalArgumentException if the sqlAction is {@code null}
     */
    @Beta
    public static <T, R> ContinuableFuture<R> asyncCall(final T t, final Throwables.Function<? super T, ? extends R, Exception> sqlAction)
            throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        return asyncExecutor.execute(() -> sqlAction.apply(t));
    }

    /**
     * Asynchronously calls the specified SQL action with two parameters and returns a result.
     * Note: Any transaction started in current thread won't be automatically applied to the specified 
     * {@code sqlAction} which will be executed in another thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ContinuableFuture<List<Order>> future = JdbcUtil.asyncCall(userId, status,
     *     (uid, st) -> JdbcUtil.query(dataSource, 
     *                                 "SELECT * FROM orders WHERE user_id = ? AND status = ?", uid, st)
     *                          .list(Order.class)
     * );
     * 
     * future.thenAccept(orders -> System.out.println("Found " + orders.size() + " orders"));
     * }</pre>
     *
     * @param <T> The type of the first parameter
     * @param <U> The type of the second parameter
     * @param <R> The type of the result
     * @param t The first parameter to pass to the SQL action
     * @param u The second parameter to pass to the SQL action
     * @param sqlAction The SQL action that takes two parameters and produces a result
     * @return A ContinuableFuture representing the result of the asynchronous computation
     * @throws IllegalArgumentException if the sqlAction is {@code null}
     */
    @Beta
    public static <T, U, R> ContinuableFuture<R> asyncCall(final T t, final U u,
            final Throwables.BiFunction<? super T, ? super U, ? extends R, Exception> sqlAction) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        return asyncExecutor.execute(() -> sqlAction.apply(t, u));
    }

    /**
     * Asynchronously calls the specified SQL action with three parameters and returns a result.
     * Note: Any transaction started in current thread won't be automatically applied to the specified 
     * {@code sqlAction} which will be executed in another thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ContinuableFuture<BigDecimal> future = JdbcUtil.asyncCall(startDate, endDate, category,
     *     (start, end, cat) -> JdbcUtil.queryForSingleResult(BigDecimal.class, dataSource,
     *         "SELECT SUM(amount) FROM sales WHERE date BETWEEN ? AND ? AND category = ?", 
     *         start, end, cat)
     * );
     * 
     * future.thenAccept(total -> System.out.println("Total sales: " + total));
     * }</pre>
     *
     * @param <A> The type of the first parameter
     * @param <B> The type of the second parameter
     * @param <C> The type of the third parameter
     * @param <R> The type of the result
     * @param a The first parameter to pass to the SQL action
     * @param b The second parameter to pass to the SQL action
     * @param c The third parameter to pass to the SQL action
     * @param sqlAction The SQL action that takes three parameters and produces a result
     * @return A ContinuableFuture representing the result of the asynchronous computation
     * @throws IllegalArgumentException if the sqlAction is {@code null}
     */
    @Beta
    public static <A, B, C, R> ContinuableFuture<R> asyncCall(final A a, final B b, final C c,
            final Throwables.TriFunction<? super A, ? super B, ? super C, ? extends R, Exception> sqlAction) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        return asyncExecutor.execute(() -> sqlAction.apply(a, b, c));
    }

    static final RowMapper<Object> NO_GENERATED_KEY_EXTRACTOR = rs -> null;

    static final RowMapper<Object> SINGLE_GENERATED_KEY_EXTRACTOR = rs -> getColumnValue(rs, 1);

    @SuppressWarnings("deprecation")
    static final RowMapper<Object> MULTI_GENERATED_KEY_EXTRACTOR = rs -> {
        final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

        if (columnLabels.size() == 1) {
            return getColumnValue(rs, 1);
        } else {
            final int columnCount = columnLabels.size();
            final Seid id = Seid.of(Strings.EMPTY);

            for (int i = 1; i <= columnCount; i++) {
                id.set(columnLabels.get(i - 1), getColumnValue(rs, i));
            }

            return id;
        }
    };

    static final BiRowMapper<Object> NO_BI_GENERATED_KEY_EXTRACTOR = (rs, columnLabels) -> null;

    static final BiRowMapper<Object> SINGLE_BI_GENERATED_KEY_EXTRACTOR = (rs, columnLabels) -> getColumnValue(rs, 1);

    @SuppressWarnings("deprecation")
    static final BiRowMapper<Object> MULTI_BI_GENERATED_KEY_EXTRACTOR = (rs, columnLabels) -> {
        if (columnLabels.size() == 1) {
            return getColumnValue(rs, 1);
        } else {
            final int columnCount = columnLabels.size();
            final Seid id = Seid.of(Strings.EMPTY);

            for (int i = 1; i <= columnCount; i++) {
                id.set(columnLabels.get(i - 1), getColumnValue(rs, i));
            }

            return id;
        }
    };

    private static final Map<Class<?>, Map<String, Optional<PropInfo>>> entityPropInfoQueueMap = new ConcurrentHashMap<>();

    static PropInfo getSubPropInfo(final Class<?> entityClass, final String propName) {
        final BeanInfo entityInfo = ParserUtil.getBeanInfo(entityClass);
        Map<String, Optional<PropInfo>> propInfoQueueMap = entityPropInfoQueueMap.get(entityClass);
        Optional<PropInfo> propInfoHolder = null;
        PropInfo propInfo = null;

        if (propInfoQueueMap == null) {
            propInfoQueueMap = new ObjectPool<>((entityInfo.propInfoList.size() + 1) * 2);
            entityPropInfoQueueMap.put(entityClass, propInfoQueueMap);
        } else {
            propInfoHolder = propInfoQueueMap.get(propName);
        }

        if (propInfoHolder == null) {
            final String[] strs = Splitter.with('.').splitToArray(propName);

            if (strs.length > 1) {
                Class<?> propClass = entityClass;
                BeanInfo propBeanInfo = null;

                for (int i = 0, len = strs.length; i < len; i++) {
                    propBeanInfo = Beans.isBeanClass(propClass) ? ParserUtil.getBeanInfo(propClass) : null;
                    propInfo = propBeanInfo == null ? null : propBeanInfo.getPropInfo(strs[i]);

                    if (propInfo == null) {
                        if (i == 0) {
                            return null; // return directly because the first part is not valid property/field name of the target entity class.
                        }

                        break;
                    }

                    if (i == len - 1) {
                        propInfoHolder = Optional.of(propInfo);
                        break;
                    }

                    if (propInfo.type.isCollection()) {
                        propClass = propInfo.type.getElementType().clazz();
                    } else {
                        propClass = propInfo.clazz;
                    }
                }
            }

            propInfoQueueMap.put(propName, propInfoHolder == null ? Optional.empty() : propInfoHolder);
        } else if (propInfoHolder.isPresent()) {
            propInfo = propInfoHolder.get();
        }

        return propInfo;
    }

    static Object[] getParameterArray(final SP sp) {
        return N.isEmpty(sp.parameters) ? N.EMPTY_OBJECT_ARRAY : sp.parameters.toArray();
    }

    static <R> BiRowMapper<R> toBiRowMapper(final RowMapper<R> rowMapper) {
        return (rs, columnLabels) -> rowMapper.apply(rs);
    }

    //    @SuppressWarnings("rawtypes")
    //    static Class<?> getTargetEntityClass(final Class<? extends Dao> daoInterface) {
    //        if (N.notEmpty(daoInterface.getGenericInterfaces()) && daoInterface.getGenericInterfaces()[0] instanceof ParameterizedType) {
    //            final ParameterizedType parameterizedType = (ParameterizedType) daoInterface.getGenericInterfaces()[0];
    //            java.lang.reflect.Type[] typeArguments = parameterizedType.getActualTypeArguments();
    //
    //            if (typeArguments.length >= 1 && typeArguments[0] instanceof Class) {
    //                if (!Beans.isBeanClass((Class) typeArguments[0])) {
    //                    throw new IllegalArgumentException(
    //                            "Entity Type parameter of Dao interface must be: Object.class or entity class with getter/setter methods. Can't be: "
    //                                    + typeArguments[0]);
    //                }
    //
    //                return (Class) typeArguments[0];
    //            }
    //        }
    //
    //        throw new IllegalArgumentException("Invalid Dao interface: " + daoInterface + ". No entity class found by type parameter");
    //    }
    //
    //    @SuppressWarnings("rawtypes")
    //    static final Map<javax.sql.DataSource, Map<Class<?>, Dao>> dsEntityDaoPool = new IdentityHashMap<>();

    //    /**
    //     *
    //     * @param ds
    //     * @param targetEntityOrDaoClass
    //     */
    //    public static void removeCachedDao(final javax.sql.DataSource ds, final Class<?> targetEntityOrDaoClass) {
    //        N.checkArgNotNull(ds, "dataSource");
    //        N.checkArgNotNull(targetEntityOrDaoClass, "targetEntityOrDaoClass");
    //
    //        @SuppressWarnings("rawtypes")
    //        final Class<?> targetEntityClass = Dao.class.isAssignableFrom(targetEntityOrDaoClass)
    //                ? getTargetEntityClass((Class<? extends Dao>) targetEntityOrDaoClass)
    //                : targetEntityOrDaoClass;
    //
    //        synchronized (dsEntityDaoPool) {
    //            @SuppressWarnings("rawtypes")
    //            Map<Class<?>, Dao> entityDaoPool = dsEntityDaoPool.get(ds);
    //
    //            if (entityDaoPool != null) {
    //                entityDaoPool.remove(targetEntityClass);
    //
    //                if (N.isEmpty(entityDaoPool)) {
    //                    dsEntityDaoPool.remove(ds);
    //                }
    //            }
    //        }
    //    }

    //    /**
    //     *
    //     * @param ds
    //     * @param targetEntityOrDaoClass
    //     */
    //    public static void removeCachedDao(final javax.sql.DataSource ds, final Class<?> targetEntityOrDaoClass) {
    //        N.checkArgNotNull(ds, "dataSource");
    //        N.checkArgNotNull(targetEntityOrDaoClass, "targetEntityOrDaoClass");
    //
    //        @SuppressWarnings("rawtypes")
    //        final Class<?> targetEntityClass = Dao.class.isAssignableFrom(targetEntityOrDaoClass)
    //                ? getTargetEntityClass((Class<? extends Dao>) targetEntityOrDaoClass)
    //                : targetEntityOrDaoClass;
    //
    //        synchronized (dsEntityDaoPool) {
    //            @SuppressWarnings("rawtypes")
    //            Map<Class<?>, Dao> entityDaoPool = dsEntityDaoPool.get(ds);
    //
    //            if (entityDaoPool != null) {
    //                entityDaoPool.remove(targetEntityClass);
    //
    //                if (N.isEmpty(entityDaoPool)) {
    //                    dsEntityDaoPool.remove(ds);
    //                }
    //            }
    //        }
    //    }

    /**
     * Retrieves the output parameters from the given CallableStatement.
     * This method extracts the values of output parameters after executing a stored procedure.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "{call get_user_info(?, ?, ?)}";
     * try (CallableStatement stmt = connection.prepareCall(sql)) {
     *     stmt.setLong(1, userId);
     *     stmt.registerOutParameter(2, Types.VARCHAR);
     *     stmt.registerOutParameter(3, Types.INTEGER);
     *     stmt.execute();
     *     
     *     List<OutParam> outParams = Arrays.asList(
     *         OutParam.of(2, Types.VARCHAR),
     *         OutParam.of(3, Types.INTEGER)
     *     );
     *     
     *     OutParamResult result = JdbcUtil.getOutParameters(stmt, outParams);
     *     String name = (String) result.getOutParamValue(2);
     *     Integer age = (Integer) result.getOutParamValue(3);
     * }
     * }</pre>
     *
     * @param stmt The CallableStatement from which to retrieve the output parameters
     * @param outParams The list of OutParam objects representing the output parameters to retrieve
     * @return An OutParamResult containing the retrieved output parameter values
     * @throws IllegalArgumentException if the provided arguments are invalid
     * @throws SQLException if a SQL exception occurs while retrieving the output parameters
     */
    public static OutParamResult getOutParameters(final CallableStatement stmt, final List<OutParam> outParams) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(stmt, cs.stmt);

        if (N.isEmpty(outParams)) {
            return new OutParamResult(N.emptyList(), N.emptyMap());
        }

        final Map<Object, Object> outParamValues = new LinkedHashMap<>(outParams.size());
        OutParameterGetter outParameterGetter = null;
        Object key = null;
        Object value = null;

        for (final OutParam outParam : outParams) {
            outParameterGetter = sqlTypeGetterMap.getOrDefault(outParam.getSqlType(), objOutParameterGetter);

            if (outParam.getParameterIndex() > 0) {
                key = outParam.getParameterIndex();
                value = outParameterGetter.getOutParameter(stmt, outParam.getParameterIndex());
            } else {
                key = outParam.getParameterName();
                value = outParameterGetter.getOutParameter(stmt, outParam.getParameterName());
            }

            if (value instanceof final ResultSet rs) {
                try {
                    value = JdbcUtil.extractData(rs);
                } finally {
                    JdbcUtil.closeQuietly(rs);
                }
            } else if (value instanceof final Blob blob) {
                value = blob.getBytes(1, (int) blob.length());
            } else if (value instanceof final Clob clob) {
                value = clob.getSubString(1, (int) clob.length());
            }

            outParamValues.put(key, value);
        }

        return new OutParamResult(outParams, outParamValues);
    }

    /**
     * Extracts the named parameters from the given SQL string.
     * Named parameters are placeholders in SQL that start with ':' followed by the parameter name.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "SELECT * FROM users WHERE name = :name AND age > :age AND city = :city";
     * List<String> params = JdbcUtil.getNamedParameters(sql);
     * // Returns: ["name", "age", "city"]
     * }</pre>
     *
     * @param sql the SQL string containing named parameters (e.g., :paramName)
     * @return a list of named parameter names found in the SQL string (without the ':' prefix)
     */
    public static List<String> getNamedParameters(final String sql) {
        return ParsedSql.parse(sql).getNamedParameters();
    }

    /**
     * Parses the given SQL string and returns a ParsedSql object.
     * This method analyzes SQL statements to extract information about parameters and structure.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "SELECT * FROM users WHERE name = :name AND age > ?";
     * ParsedSql parsedSql = JdbcUtil.parseSql(sql);
     * List<String> namedParams = parsedSql.getNamedParameters(); // ["name"]
     * String convertedSql = parsedSql.getParameterizedSql(); // SQL with named params converted to ?
     * }</pre>
     *
     * @param sql the SQL string to be parsed
     * @return a ParsedSql object containing parsed information about the SQL string
     * @see ParsedSql#parse(String)
     */
    public static ParsedSql parseSql(final String sql) {
        return ParsedSql.parse(sql);
    }

    /**
     * Returns the property names suitable for INSERT operations for the given entity.
     * This method returns all property names that should be included in an INSERT statement,
     * excluding properties marked with annotations like @ReadOnly, @Id (for auto-generated IDs), etc.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * Collection<String> propNames = JdbcUtil.getInsertPropNames(user);
     * // Returns property names that should be included in INSERT statement
     * }</pre>
     *
     * @param entity the entity object to analyze
     * @return a collection of property names suitable for INSERT operations
     */
    public static Collection<String> getInsertPropNames(final Object entity) {
        return getInsertPropNames(entity, null);
    }

    /**
     * Returns the property names suitable for INSERT operations for the given entity,
     * excluding the specified property names.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * Set<String> excludedProps = N.asSet("createdTime", "modifiedTime");
     * Collection<String> propNames = JdbcUtil.getInsertPropNames(user, excludedProps);
     * // Returns property names for INSERT, excluding specified properties
     * }</pre>
     *
     * @param entity the entity object to analyze
     * @param excludedPropNames property names to exclude from the result
     * @return a collection of property names suitable for INSERT operations
     */
    @SuppressWarnings("deprecation")
    public static Collection<String> getInsertPropNames(final Object entity, final Set<String> excludedPropNames) {
        return QueryUtil.getInsertPropNames(entity, excludedPropNames);
    }

    /**
     * Returns the property names suitable for INSERT operations for the given entity class.
     * This method analyzes the class structure to determine which properties should be
     * included in INSERT statements.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Collection<String> propNames = JdbcUtil.getInsertPropNames(User.class);
     * // Returns property names that should be included in INSERT statement for User entities
     * }</pre>
     *
     * @param entityClass the entity class to analyze
     * @return a collection of property names suitable for INSERT operations
     */
    public static Collection<String> getInsertPropNames(final Class<?> entityClass) {
        return getInsertPropNames(entityClass, null);
    }

    /**
     * Returns the property names suitable for INSERT operations for the given entity class,
     * excluding the specified property names.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Set<String> excludedProps = N.asSet("id", "version");
     * Collection<String> propNames = JdbcUtil.getInsertPropNames(User.class, excludedProps);
     * // Returns property names for INSERT, excluding specified properties
     * }</pre>
     *
     * @param entityClass the entity class to analyze
     * @param excludedPropNames property names to exclude from the result
     * @return a collection of property names suitable for INSERT operations
     */
    @SuppressWarnings("deprecation")
    public static Collection<String> getInsertPropNames(final Class<?> entityClass, final Set<String> excludedPropNames) {
        return QueryUtil.getInsertPropNames(entityClass, excludedPropNames);
    }

    /**
     * Gets the property names suitable for SELECT operations for the given entity class.
     * This method returns all property names that should be included in a SELECT statement,
     * excluding properties marked with @Transient or other exclusion annotations.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Collection<String> propNames = JdbcUtil.getSelectPropNames(User.class);
     * // Returns property names that should be included in SELECT statement
     * }</pre>
     *
     * @param entityClass the entity class to analyze
     * @return a collection of property names suitable for SELECT operations
     */
    public static Collection<String> getSelectPropNames(final Class<?> entityClass) {
        return getSelectPropNames(entityClass, null);
    }

    /**
     * Gets the property names suitable for SELECT operations for the given entity class,
     * excluding the specified property names.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Set<String> excludedProps = N.asSet("password", "secretKey");
     * Collection<String> propNames = JdbcUtil.getSelectPropNames(User.class, excludedProps);
     * // Returns property names for SELECT, excluding sensitive properties
     * }</pre>
     *
     * @param entityClass the entity class to analyze
     * @param excludedPropNames property names to exclude from the result
     * @return a collection of property names suitable for SELECT operations
     */
    public static Collection<String> getSelectPropNames(final Class<?> entityClass, final Set<String> excludedPropNames) {
        return getSelectPropNames(entityClass, false, excludedPropNames);
    }

    /**
     * Gets the property names suitable for SELECT operations for the given entity class,
     * with an option to include sub-entity properties and exclude specified property names.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Include properties of related entities (e.g., user.address.street)
     * Collection<String> propNames = JdbcUtil.getSelectPropNames(User.class, {@code true}, null);
     * // Returns property names including sub-entity properties
     * }</pre>
     *
     * @param entityClass the entity class to analyze
     * @param includeSubEntityProperties whether to include properties of sub-entities
     * @param excludedPropNames property names to exclude from the result
     * @return a collection of property names suitable for SELECT operations
     */
    @SuppressWarnings("deprecation")
    public static Collection<String> getSelectPropNames(final Class<?> entityClass, final boolean includeSubEntityProperties,
            final Set<String> excludedPropNames) {
        return QueryUtil.getSelectPropNames(entityClass, includeSubEntityProperties, excludedPropNames);
    }

    /**
     * Gets the property names suitable for UPDATE operations for the given entity class.
     * This method returns all property names that should be included in an UPDATE statement,
     * excluding properties marked with @ReadOnly, @NonUpdatable, @Id, etc.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Collection<String> propNames = JdbcUtil.getUpdatePropNames(User.class);
     * // Returns property names that can be updated
     * }</pre>
     *
     * @param entityClass the entity class to analyze
     * @return a collection of property names suitable for UPDATE operations
     */
    public static Collection<String> getUpdatePropNames(final Class<?> entityClass) {
        return getUpdatePropNames(entityClass, null);
    }

    /**
     * Gets the property names suitable for UPDATE operations for the given entity class,
     * excluding the specified property names.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Set<String> excludedProps = N.asSet("createdTime", "createdBy");
     * Collection<String> propNames = JdbcUtil.getUpdatePropNames(User.class, excludedProps);
     * // Returns property names for UPDATE, excluding specified properties
     * }</pre>
     *
     * @param entityClass the entity class to analyze
     * @param excludedPropNames property names to exclude from the result
     * @return a collection of property names suitable for UPDATE operations
     */
    @SuppressWarnings("deprecation")
    public static Collection<String> getUpdatePropNames(final Class<?> entityClass, final Set<String> excludedPropNames) {
        return QueryUtil.getUpdatePropNames(entityClass, excludedPropNames);
    }

    /**
     * Converts a Blob to a String using UTF-8 encoding and frees the Blob resources.
     * This method reads all bytes from the Blob and converts them to a String.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Blob blob = resultSet.getBlob("data");
     * String content = JdbcUtil.blob2String(blob);
     * // The blob is automatically freed after conversion
     * }</pre>
     *
     * @param blob the Blob object to be converted to a String
     * @return the String representation of the Blob content
     * @throws SQLException if a SQL exception occurs while accessing the Blob
     */
    public static String blob2String(final Blob blob) throws SQLException {
        try {
            return new String(blob.getBytes(1, (int) blob.length()), Charsets.UTF_8);
        } finally {
            if (blob != null) {
                blob.free();
            }
        }
    }

    /**
     * Converts a Blob to a String using the specified character encoding and frees the Blob resources.
     * This method reads all bytes from the Blob and converts them to a String using the given charset.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Blob blob = resultSet.getBlob("data");
     * String content = JdbcUtil.blob2String(blob, Charsets.ISO_8859_1);
     * // The blob is automatically freed after conversion
     * }</pre>
     *
     * @param blob the Blob object to be converted to a String
     * @param charset the character encoding to use for the conversion
     * @return the String representation of the Blob content
     * @throws SQLException if a SQL exception occurs while accessing the Blob
     */
    public static String blob2String(final Blob blob, final Charset charset) throws SQLException {
        try {
            return new String(blob.getBytes(1, (int) blob.length()), charset);
        } finally {
            if (blob != null) {
                blob.free();
            }
        }
    }

    /**
     * Writes the content of a Blob to a file and frees the Blob resources.
     * This method streams the Blob content directly to the specified file.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Blob blob = resultSet.getBlob("document");
     * File outputFile = new File("document.pdf");
     * long bytesWritten = JdbcUtil.writeBlobToFile(blob, outputFile);
     * // The blob is automatically freed after writing
     * }</pre>
     *
     * @param blob the Blob object containing the data to be written
     * @param output the File object representing the output file
     * @return the number of bytes written to the file
     * @throws SQLException if a SQL exception occurs while accessing the Blob
     * @throws IOException if an I/O error occurs while writing to the file
     */
    public static long writeBlobToFile(final Blob blob, final File output) throws SQLException, IOException {
        try {
            return IOUtil.write(blob.getBinaryStream(), output);
        } finally {
            if (blob != null) {
                blob.free();
            }
        }
    }

    /**
     * Converts a Clob to a String and frees the Clob resources.
     * This method reads all characters from the Clob and returns them as a String.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Clob clob = resultSet.getClob("description");
     * String content = JdbcUtil.clob2String(clob);
     * // The clob is automatically freed after conversion
     * }</pre>
     *
     * @param clob the Clob object to be converted to a String
     * @return the String representation of the Clob content
     * @throws SQLException if a SQL exception occurs while accessing the Clob
     */
    public static String clob2String(final Clob clob) throws SQLException {
        try {
            return clob.getSubString(1, (int) clob.length());
        } finally {
            if (clob != null) {
                clob.free();
            }
        }
    }

    /**
     * Writes the content of a Clob to a file and frees the Clob resources.
     * This method streams the Clob content directly to the specified file.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Clob clob = resultSet.getClob("article");
     * File outputFile = new File("article.txt");
     * long charsWritten = JdbcUtil.writeClobToFile(clob, outputFile);
     * // The clob is automatically freed after writing
     * }</pre>
     *
     * @param clob the Clob object containing the data to be written
     * @param output the File object representing the output file
     * @return the number of characters written to the file
     * @throws SQLException if a SQL exception occurs while accessing the Clob
     * @throws IOException if an I/O exception occurs while writing to the file
     */
    public static long writeClobToFile(final Clob clob, final File output) throws SQLException, IOException {
        try {
            return IOUtil.write(clob.getCharacterStream(), output);
        } finally {
            if (clob != null) {
                clob.free();
            }
        }
    }

    /**
     * Checks if the given value is {@code null} or equals the default value for its type.
     * Default values are: 0 for numeric types, {@code false} for boolean, empty for collections/maps,
     * and {@code null} for reference types.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * JdbcUtil.isNullOrDefault(null);        // true
     * JdbcUtil.isNullOrDefault(0);           // true
     * JdbcUtil.isNullOrDefault(false);       // true
     * JdbcUtil.isNullOrDefault("");          // {@code false} (empty string is not default)
     * JdbcUtil.isNullOrDefault(1);           // false
     * }</pre>
     *
     * @param value the value to check
     * @return {@code true} if the value is {@code null} or the default value for its type, {@code false} otherwise
     */
    public static boolean isNullOrDefault(final Object value) {
        return (value == null) || (value instanceof Number num && num.longValue() == 0) || (value instanceof Boolean b && !b)
                || N.equals(value, N.defaultValueOf(value.getClass()));
    }

    static <K, V> void merge(final Map<K, V> map, final K key, final V value, final BinaryOperator<V> remappingFunction) {
        final V oldValue = map.get(key);

        if (oldValue == null && !map.containsKey(key)) {
            map.put(key, value);
        } else {
            map.put(key, remappingFunction.apply(oldValue, value));
        }
    }

    static String checkPrefix(final BeanInfo entityInfo, final String columnName, final Map<String, String> prefixAndFieldNameMap,
            final List<String> columnLabelList) {

        final int idx = columnName.indexOf('.');

        if (idx <= 0) {
            return columnName;
        }

        final String prefix = columnName.substring(0, idx);
        PropInfo propInfo = entityInfo.getPropInfo(prefix);

        if (propInfo != null) {
            return columnName;
        }

        if (N.notEmpty(prefixAndFieldNameMap) && prefixAndFieldNameMap.containsKey(prefix)) {
            propInfo = entityInfo.getPropInfo(prefixAndFieldNameMap.get(prefix));

            if (propInfo != null) {
                return propInfo.name + columnName.substring(idx);
            }
        }

        propInfo = entityInfo.getPropInfo(prefix + "s"); // Trying to do something smart?
        final int len = prefix.length() + 1;

        if (propInfo != null && (propInfo.type.isBean() || (propInfo.type.isCollection() && propInfo.type.getElementType().isBean()))
                && N.noneMatch(columnLabelList, it -> it.length() > len && it.charAt(len) == '.' && Strings.startsWithIgnoreCase(it, prefix + "s."))) {
            // good
        } else {
            propInfo = entityInfo.getPropInfo(prefix + "es"); // Trying to do something smart?
            final int len2 = prefix.length() + 2;

            if (propInfo != null && (propInfo.type.isBean() || (propInfo.type.isCollection() && propInfo.type.getElementType().isBean()))
                    && N.noneMatch(columnLabelList, it -> it.length() > len2 && it.charAt(len2) == '.' && Strings.startsWithIgnoreCase(it, prefix + "es."))) {
                // good
            } else {
                // Sorry, have done all I can do.
                propInfo = null;
            }
        }

        if (propInfo != null) {
            return propInfo.name + columnName.substring(idx);
        }

        return columnName;
    }

    // <<==============================================Jdbc Context=======================================================

    /**
     * Globally disables SQL logging across all threads in the application.
     * Once called, SQL statements will not be logged regardless of thread-local settings.
     * This setting cannot be reversed during the application lifecycle.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Disable SQL logging for production environment
     * JdbcUtil.turnOffSqlLogGlobally();
     * }</pre>
     */
    public static void turnOffSqlLogGlobally() {
        isSqlLogAllowed = false;
    }

    /**
     * Globally disables SQL performance logging across all threads in the application.
     * Once called, SQL execution times will not be logged regardless of thread-local settings.
     * This setting cannot be reversed during the application lifecycle.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Disable SQL performance logging for production
     * JdbcUtil.turnOffSqlPerfLogGlobally();
     * }</pre>
     */
    public static void turnOffSqlPerfLogGlobally() {
        isSqlPerfLogAllowed = false;
    }

    /**
     * Globally disables DAO method performance logging across all threads in the application.
     * Once called, DAO method execution times will not be logged regardless of thread-local settings.
     * This setting cannot be reversed during the application lifecycle.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Disable DAO performance logging
     * JdbcUtil.turnOffDaoMethodPerfLogGlobally();
     * }</pre>
     */
    public static void turnOffDaoMethodPerfLogGlobally() {
        isDaoMethodPerfLogAllowed = false;
    }

    /**
     * Enables/Disables SQL logging in the current thread.
     *
     * @param b {@code true} to enable SQL logging, {@code false} to disable it.
     * @deprecated replaced by {@code enableSqlLog/disableSqlLog}.
     */
    @Deprecated
    static void enableSqlLog(final boolean b) {
        enableSqlLog(b, DEFAULT_MAX_SQL_LOG_LENGTH);
    }

    /**
     * Enables/Disables SQL logging in the current thread.
     *
     * @param b {@code true} to enable SQL logging, {@code false} to disable it.
     * @param maxSqlLogLength The maximum length of the SQL log. Default value is 1024.
     * @deprecated replaced by {@code enableSqlLog/disableSqlLog}.
     */
    @Deprecated
    static void enableSqlLog(final boolean b, final int maxSqlLogLength) {
        final SqlLogConfig config = isSQLLogEnabled_TL.get();
        // synchronized (isSQLLogEnabled_TL) {
        if (logger.isDebugEnabled() && config.isEnabled != b) {
            if (b) {
                logger.debug("Turn on [SQL] log");
            } else {
                logger.debug("Turn off [SQL] log");
            }
        }

        config.set(b, maxSqlLogLength);
        // }
    }

    /**
     * Enables SQL logging for the current thread with the default maximum log length.
     * When enabled, all SQL statements executed in the current thread will be logged.
     * The default maximum SQL log length is 1024 characters.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * JdbcUtil.enableSqlLog();
     * // Execute SQL operations - they will be logged
     * // ...
     * JdbcUtil.disableSqlLog();
     * }</pre>
     */
    public static void enableSqlLog() {
        enableSqlLog(DEFAULT_MAX_SQL_LOG_LENGTH);
    }

    /**
     * Enables SQL logging for the current thread with a specified maximum log length.
     * SQL statements longer than the specified length will be truncated in the logs.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * JdbcUtil.enableSqlLog(2048); // Allow longer SQL statements in logs
     * // Execute SQL operations
     * // ...
     * JdbcUtil.disableSqlLog();
     * }</pre>
     *
     * @param maxSqlLogLength the maximum length of SQL statements in logs
     */
    public static void enableSqlLog(final int maxSqlLogLength) {
        enableSqlLog(true, maxSqlLogLength);
    }

    /**
     * Disables SQL logging for the current thread.
     * After calling this method, SQL statements executed in the current thread will not be logged.
     * The maximum SQL log length setting is preserved for when logging is re-enabled.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * JdbcUtil.enableSqlLog();
     * // SQL operations here will be logged
     * 
     * JdbcUtil.disableSqlLog();
     * // SQL operations here will NOT be logged
     * }</pre>
     */
    public static void disableSqlLog() {
        enableSqlLog(false, isSQLLogEnabled_TL.get().maxSqlLogLength);
    }

    /**
     * Checks if SQL logging is enabled for the current thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * if (JdbcUtil.isSqlLogEnabled()) {
     *     System.out.println("SQL logging is active");
     * }
     * }</pre>
     *
     * @return {@code true} if SQL logging is enabled in the current thread, {@code false} otherwise
     */
    public static boolean isSqlLogEnabled() {
        return isSQLLogEnabled_TL.get().isEnabled;
    }

    static void logSql(final String sql) {
        if (!isSqlLogAllowed || !sqlLogger.isDebugEnabled()) {
            return;
        }

        final SqlLogConfig sqlLogConfig = isSQLLogEnabled_TL.get();

        if (sqlLogConfig.isEnabled) {
            if (sql.length() <= sqlLogConfig.maxSqlLogLength) {
                sqlLogger.debug(Strings.concat("[SQL]: ", sql));
            } else {
                sqlLogger.debug(Strings.concat("[SQL]: " + Strings.abbreviate(sql, sqlLogConfig.maxSqlLogLength)));
            }
        }
    }

    static void handleSqlLog(final Statement stmt, final SqlLogConfig sqlLogConfig, final long startTime) throws SQLException {
        final long endTime = System.currentTimeMillis();
        final long elapsedTime = endTime - startTime;
        String sql = null;

        final Throwables.Function<Statement, String, SQLException> sqlExtractor = N.defaultIfNull(JdbcUtil._sqlExtractor, JdbcUtil.DEFAULT_SQL_EXTRACTOR);

        if (isSqlPerfLogAllowed && sqlLogger.isInfoEnabled() && elapsedTime >= sqlLogConfig.minExecutionTimeForSqlPerfLog) {
            sql = sqlExtractor.apply(stmt);

            if (sql.length() <= sqlLogConfig.maxSqlLogLength) {
                sqlLogger.info(Strings.concat("[SQL-PERF]: ", String.valueOf(elapsedTime), ", ", sql));
            } else {
                sqlLogger.info(Strings.concat("[SQL-PERF]: ", String.valueOf(elapsedTime), ", ", Strings.abbreviate(sql, sqlLogConfig.maxSqlLogLength)));
            }
        }

        final TriConsumer<String, Long, Long> sqlLogHandler = _sqlLogHandler;

        if (sqlLogHandler != null) {
            if (sql == null) {
                sql = sqlExtractor.apply(stmt);
            }

            sqlLogHandler.accept(sql, startTime, endTime);
        }
    }

    static boolean isToHandleSqlLog(final SqlLogConfig sqlLogConfig) {
        return _sqlLogHandler != null || (isSqlPerfLogAllowed && sqlLogConfig.minExecutionTimeForSqlPerfLog >= 0 && sqlLogger.isInfoEnabled());
    }

    /**
     * Retrieves the current SQL extractor function used to extract SQL statements from Statement objects.
     * The SQL extractor is used internally for logging and monitoring purposes.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Throwables.Function<Statement, String, SQLException> extractor = JdbcUtil.getSqlExtractor();
     * // Use the extractor to get SQL from a statement
     * String sql = extractor.apply(statement);
     * }</pre>
     *
     * @return the current SQL extractor function
     */
    public static Throwables.Function<Statement, String, SQLException> getSqlExtractor() {
        return JdbcUtil._sqlExtractor;
    }

    /**
     * Sets a custom SQL extractor function to extract SQL statements from Statement objects.
     * This is useful when using custom Statement implementations or when the default
     * extraction method doesn't work for your JDBC driver.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * JdbcUtil.setSqlExtractor(statement -> {
     *     if (statement instanceof MyCustomStatement) {
     *         return ((MyCustomStatement) statement).getOriginalSql();
     *     }
     *     return statement.toString();
     * });
     * }</pre>
     *
     * @param sqlExtractor the SQL extractor function to set
     */
    public static void setSqlExtractor(final Throwables.Function<Statement, String, SQLException> sqlExtractor) {
        JdbcUtil._sqlExtractor = sqlExtractor;
    }

    /**
     * Retrieves the current SQL log handler that processes SQL statements and their execution times.
     * The handler receives the SQL statement, start time, and end time of execution.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * TriConsumer<String, Long, Long> handler = JdbcUtil.getSqlLogHandler();
     * if (handler != null) {
     *     // Handler is configured
     * }
     * }</pre>
     *
     * @return the current SQL log handler, or {@code null} if none is set
     */
    public static TriConsumer<String, Long, Long> getSqlLogHandler() {
        return _sqlLogHandler;
    }

    /**
     * Sets a custom SQL log handler to process SQL statements and their execution times.
     * This allows for custom logging, monitoring, or alerting based on SQL execution.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * JdbcUtil.setSqlLogHandler((sql, startTime, endTime) -> {
     *     long duration = endTime - startTime;
     *     if (duration > 1000) { // Log slow queries
     *         logger.warn("Slow query ({}ms): {}", duration, sql);
     *     }
     *     // Send metrics to monitoring system
     *     metricsCollector.recordSqlExecution(sql, duration);
     * });
     * }</pre>
     *
     * @param sqlLogHandler the handler that receives: SQL statement, start time (ms), end time (ms)
     */
    public static void setSqlLogHandler(final TriConsumer<String, Long, Long> sqlLogHandler) {
        _sqlLogHandler = sqlLogHandler;
    }

    /**
     * Sets the minimum execution time threshold for SQL performance logging in the current thread.
     * Only SQL statements that take longer than this threshold will be logged for performance monitoring.
     * Uses the default maximum SQL log length of 1024 characters.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Log SQL statements that take more than 500ms
     * JdbcUtil.setMinExecutionTimeForSqlPerfLog(500);
     * }</pre>
     *
     * @param minExecutionTimeForSqlPerfLog the minimum execution time in milliseconds
     */
    public static void setMinExecutionTimeForSqlPerfLog(final long minExecutionTimeForSqlPerfLog) {
        setMinExecutionTimeForSqlPerfLog(minExecutionTimeForSqlPerfLog, DEFAULT_MAX_SQL_LOG_LENGTH);
    }

    /**
     * Sets the minimum execution time threshold for SQL performance logging in the current thread
     * with a specified maximum SQL log length.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Log SQL statements that take more than 1 second, with longer log length
     * JdbcUtil.setMinExecutionTimeForSqlPerfLog(1000, 2048);
     * 
     * // Disable performance logging
     * JdbcUtil.setMinExecutionTimeForSqlPerfLog(-1);
     * }</pre>
     *
     * @param minExecutionTimeForSqlPerfLog the minimum execution time in milliseconds (use -1 to disable)
     * @param maxSqlLogLength the maximum length of SQL statements in performance logs
     */
    public static void setMinExecutionTimeForSqlPerfLog(final long minExecutionTimeForSqlPerfLog, final int maxSqlLogLength) {
        final SqlLogConfig config = minExecutionTimeForSqlPerfLog_TL.get();
        // synchronized (minExecutionTimeForSqlPerfLog_TL) {
        if (logger.isDebugEnabled() && config.minExecutionTimeForSqlPerfLog != minExecutionTimeForSqlPerfLog) {
            if (minExecutionTimeForSqlPerfLog >= 0) {
                logger.debug("set 'minExecutionTimeForSqlPerfLog' to: " + minExecutionTimeForSqlPerfLog);
            } else {
                logger.debug("Turn off SQL performance log");
            }
        }

        config.set(minExecutionTimeForSqlPerfLog, maxSqlLogLength);
        // }
    }

    /**
     * Gets the current minimum execution time threshold for SQL performance logging in the current thread.
     * SQL statements that execute faster than this threshold will not be logged for performance.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * long threshold = JdbcUtil.getMinExecutionTimeForSqlPerfLog();
     * System.out.println("Performance logging threshold: " + threshold + "ms");
     * }</pre>
     *
     * @return the minimum execution time in milliseconds (default is 1000ms)
     */
    public static long getMinExecutionTimeForSqlPerfLog() {
        return minExecutionTimeForSqlPerfLog_TL.get().minExecutionTimeForSqlPerfLog;
    }

    /**
     * Executes the specified action with SQL logging temporarily disabled.
     * This is useful for executing sensitive queries or reducing log verbosity for specific operations.
     * Note: The SQL action should not be executed in another thread as the logging flag is thread-local.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * JdbcUtil.runWithSqlLogDisabled(() -> {
     *     // Execute sensitive SQL operations without logging
     *     JdbcUtil.executeUpdate(dataSource,
     *         "UPDATE users SET password = ? WHERE id = ?",
     *         password, userId);
     * });
     * }</pre>
     *
     * @param <E> the type of exception that the action may throw
     * @param sqlAction the action to execute without SQL logging
     * @throws E if the action throws an exception
     */
    public static <E extends Exception> void runWithSqlLogDisabled(final Throwables.Runnable<E> sqlAction) throws E {
        if (isSqlLogEnabled()) {
            disableSqlLog();

            try {
                sqlAction.run();
            } finally {
                enableSqlLog();
            }
        } else {
            sqlAction.run();
        }
    }

    /**
     * Executes the specified callable with SQL logging temporarily disabled and returns its result.
     * This is useful for executing sensitive queries that return values without logging.
     * Note: The SQL action should not be executed in another thread as the logging flag is thread-local.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String result = JdbcUtil.callWithSqlLogDisabled(() -> {
     *     // Execute sensitive query without logging
     *     return JdbcUtil.prepareQuery(dataSource, "SELECT email FROM users WHERE id = ?")
     *                    .setString(1, userId)
     *                    .queryForString()
     *                    .orElse(null);
     * });
     * }</pre>
     *
     * @param <R> the type of result returned by the callable
     * @param <E> the type of exception that the callable may throw
     * @param sqlAction the callable to execute without SQL logging
     * @return the result of the callable
     * @throws E if the callable throws an exception
     */
    public static <R, E extends Exception> R callWithSqlLogDisabled(final Throwables.Callable<R, E> sqlAction) throws E {
        if (isSqlLogEnabled()) {
            disableSqlLog();

            try {
                return sqlAction.call();
            } finally {
                enableSqlLog();
            }
        } else {
            return sqlAction.call();
        }
    }

    /**
     * Checks if there is an active transaction for the given DataSource in the current thread.
     * This includes both JdbcUtil-managed transactions and Spring-managed transactions.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * if (JdbcUtil.isInTransaction(dataSource)) {
     *     // Execute operations within the existing transaction
     * } else {
     *     // Start a new transaction
     *     SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);
     *     // ...
     * }
     * }</pre>
     *
     * @param ds the DataSource to check for an active transaction
     * @return {@code true} if there is an active transaction, {@code false} otherwise
     */
    public static boolean isInTransaction(final javax.sql.DataSource ds) {
        if (SQLTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL) != null) {
            return true;
        }

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);

                return org.springframework.jdbc.datasource.DataSourceUtils.isConnectionTransactional(conn, ds);
            } catch (final NoClassDefFoundError e) {
                isInSpring = false;
            } finally {
                JdbcUtil.releaseConnection(conn, ds);
            }
        }

        return false;
    }

    /**
     * Begins a new database transaction with the default isolation level for the specified DataSource.
     * All subsequent JDBC operations in the same thread using this DataSource will participate in this transaction
     * until it is committed or rolled back. This provides ACID guarantees for multi-statement operations.
     *
     * <p>The transaction must be explicitly committed via {@code commit()} to persist changes, or rolled back
     * via {@code rollback()} or {@code rollbackIfNotCommitted()} to discard changes. Always use a try-finally
     * block to ensure the transaction is properly completed even if exceptions occur.</p>
     *
     * <p><b>Transaction Scope and Sharing:</b></p>
     * <p>The transaction is bound to the current thread and DataSource. All JDBC operations executed through
     * methods like {@code prepareQuery()}, {@code executeUpdate()}, etc. in the same thread with the same
     * DataSource will automatically use this transaction's connection. This includes operations in called methods,
     * enabling transactional behavior across method boundaries.</p>
     *
     * <p><b>Spring Integration:</b></p>
     * <p>If Spring's transaction management is active, JdbcUtil will automatically participate in the Spring
     * transaction instead of creating a new one. This ensures seamless integration with Spring's {@code @Transactional}
     * annotation and declarative transaction management.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Basic transaction usage
     * SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     JdbcUtil.executeUpdate(dataSource,
     *         "INSERT INTO orders (customer_id, total) VALUES (?, ?)",
     *         customerId, total);
     *
     *     JdbcUtil.executeUpdate(dataSource,
     *         "UPDATE inventory SET quantity = quantity - ? WHERE product_id = ?",
     *         quantity, productId);
     *
     *     tran.commit();
     * } finally {
     *     tran.rollbackIfNotCommitted();
     * }
     *
     * // Transaction with conditional rollback
     * SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     int updatedRows = JdbcUtil.executeUpdate(dataSource,
     *         "UPDATE accounts SET balance = balance - ? WHERE id = ? AND balance >= ?",
     *         amount, accountId, amount);
     *
     *     if (updatedRows == 0) {
     *         throw new InsufficientFundsException("Insufficient balance");
     *     }
     *
     *     JdbcUtil.executeUpdate(dataSource,
     *         "INSERT INTO transactions (account_id, amount, type) VALUES (?, ?, ?)",
     *         accountId, amount, "DEBIT");
     *
     *     tran.commit();
     * } catch (Exception e) {
     *     // Transaction automatically rolled back in finally block
     *     logger.error("Transaction failed: " + e.getMessage());
     *     throw e;
     * } finally {
     *     tran.rollbackIfNotCommitted();
     * }
     *
     * // Transaction shared across method calls
     * public void processOrder(Order order) {
     *     SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);
     *     try {
     *         createOrder(order);      // Shares this transaction
     *         updateInventory(order);  // Shares this transaction
     *         sendNotification(order); // Shares this transaction
     *         tran.commit();
     *     } finally {
     *         tran.rollbackIfNotCommitted();
     *     }
     * }
     *
     * private void createOrder(Order order) {
     *     // This automatically uses the transaction from processOrder()
     *     JdbcUtil.executeUpdate(dataSource,
     *         "INSERT INTO orders (id, customer_id, total) VALUES (?, ?, ?)",
     *         order.getId(), order.getCustomerId(), order.getTotal());
     * }
     * }</pre>
     *
     * @param dataSource the {@link javax.sql.DataSource} for which to begin the transaction, must not be {@code null}
     * @return a {@link SQLTransaction} object representing the new transaction that must be committed or rolled back
     * @throws UncheckedSQLException if a database access error occurs while beginning the transaction
     * @see #beginTransaction(javax.sql.DataSource, IsolationLevel)
     * @see #beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)
     * @see SQLTransaction#commit()
     * @see SQLTransaction#rollback()
     * @see SQLTransaction#rollbackIfNotCommitted()
     */
    public static SQLTransaction beginTransaction(final javax.sql.DataSource dataSource) throws UncheckedSQLException {
        return beginTransaction(dataSource, IsolationLevel.DEFAULT);
    }

    /**
     * Begins a new transaction with the specified isolation level for the given DataSource.
     * The transaction must be explicitly committed or rolled back.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SQLTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
     * try {
     *     // Perform database operations with READ_COMMITTED isolation
     *     JdbcUtil.executeUpdate(dataSource,
     *         "UPDATE accounts SET balance = balance + ? WHERE id = ?",
     *         amount, accountId);
     *     tran.commit();
     * } finally {
     *     tran.rollbackIfNotCommitted();
     * }
     * }</pre>
     *
     * @param dataSource the DataSource for which to begin the transaction
     * @param isolationLevel the isolation level for the transaction
     * @return a SQLTransaction object representing the new transaction
     * @throws UncheckedSQLException if a SQL exception occurs while beginning the transaction
     * @see #beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)
     */
    public static SQLTransaction beginTransaction(final javax.sql.DataSource dataSource, final IsolationLevel isolationLevel) throws UncheckedSQLException {
        return beginTransaction(dataSource, isolationLevel, false);
    }

    /**
     * Starts a global transaction which will be shared by all in-line database queries with the same DataSource
     * in the same thread. This includes methods like prepareQuery, prepareNamedQuery, prepareCallableQuery,
     * and SQLExecutor operations.
     *
     * <p>Spring Transaction is supported and integrated. If a Spring transaction is already active
     * with the specified DataSource, the Connection from the Spring transaction will be used.</p>
     *
     * <p><b>Example of transaction sharing:</b></p>
     * <pre>{@code
     * public void doSomethingA() {
     *     final SQLTransaction tranA = JdbcUtil.beginTransaction(dataSource1, IsolationLevel.DEFAULT, false);
     *     try {
     *         // Operations here share tranA
     *         doSomethingB(); // Shares tranA (same thread, same dataSource1)
     *         doSomethingC(); // Uses different transaction (different dataSource2)
     *         tranA.commit();
     *     } finally {
     *         tranA.rollbackIfNotCommitted();
     *     }
     * }
     * 
     * public void doSomethingB() {
     *     final SQLTransaction tranB = JdbcUtil.beginTransaction(dataSource1, IsolationLevel.DEFAULT, false);
     *     try {
     *         // This reuses tranA from doSomethingA()
     *         tranB.commit();
     *     } finally {
     *         tranB.rollbackIfNotCommitted();
     *     }
     * }
     * }</pre>
     *
     * @param dataSource the DataSource for which to begin the transaction
     * @param isolationLevel the isolation level for the transaction
     * @param isForUpdateOnly whether this transaction is only for update operations
     * @return a SQLTransaction object representing the transaction
     * @throws UncheckedSQLException if a SQL exception occurs while beginning the transaction
     * @see JdbcUtil#getConnection(javax.sql.DataSource)
     * @see JdbcUtil#releaseConnection(Connection, javax.sql.DataSource)
     */
    public static SQLTransaction beginTransaction(final javax.sql.DataSource dataSource, final IsolationLevel isolationLevel, final boolean isForUpdateOnly)
            throws UncheckedSQLException {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(isolationLevel, cs.isolationLevel);

        SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

        if (tran == null) {
            Connection conn = null;
            boolean noException = false;

            try { //NOSONAR
                conn = JdbcUtil.getConnection(dataSource);
                tran = new SQLTransaction(dataSource, conn, isolationLevel, CreatedBy.JDBC_UTIL, true); //NOSONAR
                tran.incrementAndGetRef(isolationLevel, isForUpdateOnly);

                noException = true;
            } catch (final SQLException e) {
                throw new UncheckedSQLException(e);
            } finally {
                if (!noException) {
                    JdbcUtil.releaseConnection(conn, dataSource);
                }
            }

            logger.info("Create a new SQLTransaction(id={})", tran.id());
            SQLTransaction.putTransaction(tran);
        } else {
            logger.info("Reusing the existing SQLTransaction(id={})", tran.id());
            tran.incrementAndGetRef(isolationLevel, isForUpdateOnly);
        }

        return tran;
    }

    /**
     * Executes the given callable within a transaction and returns its result.
     * If the callable completes successfully, the transaction is committed.
     * If an exception occurs, the transaction is rolled back.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String result = JdbcUtil.callInTransaction(dataSource, () -> {
     *     // Perform database operations
     *     JdbcUtil.executeUpdate(dataSource,
     *         "INSERT INTO audit_log (event, timestamp) VALUES (?, ?)",
     *         "USER_LOGIN", System.currentTimeMillis());
     *     return "Success";
     * });
     * }</pre>
     *
     * @param <T> the type of the result returned by the callable
     * @param <E> the type of exception that the callable may throw
     * @param dataSource the DataSource for the transaction
     * @param cmd the callable to execute within the transaction
     * @return the result of the callable execution
     * @throws IllegalArgumentException if dataSource or cmd is null
     * @throws E if the callable throws an exception
     */
    @Beta
    public static <T, E extends Throwable> T callInTransaction(final javax.sql.DataSource dataSource, final Throwables.Callable<T, E> cmd)
            throws IllegalArgumentException, E {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(cmd, cs.cmd);

        final SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);
        T result = null;

        try {
            result = cmd.call();
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }

        return result;
    }

    /**
     * Executes the given function within a transaction, providing the transaction's connection.
     * If the function completes successfully, the transaction is committed.
     * If an exception occurs, the transaction is rolled back.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = JdbcUtil.callInTransaction(dataSource, conn -> {
     *     try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM users")) {
     *         ResultSet rs = JdbcUtil.executeQuery(ps);
     *         // Process results
     *         return users;
     *     }
     * });
     * }</pre>
     *
     * @param <T> the type of the result returned by the function
     * @param <E> the type of exception that the function may throw
     * @param dataSource the DataSource for the transaction
     * @param cmd the function to execute with the transaction's connection
     * @return the result of the function execution
     * @throws E if the function throws an exception
     */
    @Beta
    public static <T, E extends Throwable> T callInTransaction(final javax.sql.DataSource dataSource, final Throwables.Function<Connection, T, E> cmd)
            throws E {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(cmd, cs.cmd);

        final SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);
        T result = null;

        try {
            result = cmd.apply(tran.connection());
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }

        return result;
    }

    /**
     * Executes the given runnable within a transaction.
     * If the runnable completes successfully, the transaction is committed.
     * If an exception occurs, the transaction is rolled back.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * JdbcUtil.runInTransaction(dataSource, () -> {
     *     // Perform multiple database operations
     *     userDao.insert(user);
     *     auditDao.logUserCreation(user);
     * });
     * }</pre>
     *
     * @param <E> the type of exception that the runnable may throw
     * @param dataSource the DataSource for the transaction
     * @param cmd the runnable to execute within the transaction
     * @throws IllegalArgumentException if dataSource or cmd is null
     * @throws E if the runnable throws an exception
     */
    @Beta
    public static <E extends Throwable> void runInTransaction(final javax.sql.DataSource dataSource, final Throwables.Runnable<E> cmd)
            throws IllegalArgumentException, E {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(cmd, cs.cmd);

        final SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);

        try {
            cmd.run();
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    /**
     * Executes the given consumer within a transaction, providing the transaction's connection.
     * If the consumer completes successfully, the transaction is committed.
     * If an exception occurs, the transaction is rolled back.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * JdbcUtil.runInTransaction(dataSource, conn -> {
     *     try (PreparedStatement ps = conn.prepareStatement("UPDATE users SET active = ? WHERE id = ?")) {
     *         ps.setBoolean(1, false);
     *         ps.setLong(2, userId);
     *         JdbcUtil.executeUpdate(ps);
     *     }
     * });
     * }</pre>
     *
     * @param <E> the type of exception that the consumer may throw
     * @param dataSource the DataSource for the transaction
     * @param cmd the consumer to execute with the transaction's connection
     * @throws IllegalArgumentException if dataSource or cmd is null
     * @throws E if the consumer throws an exception
     */
    @Beta
    public static <E extends Throwable> void runInTransaction(final javax.sql.DataSource dataSource, final Throwables.Consumer<Connection, E> cmd)
            throws IllegalArgumentException, E {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(cmd, cs.cmd);

        final SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);

        try {
            cmd.accept(tran.connection());
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    /**
     * Executes the given callable outside any active transaction for the specified DataSource.
     * If a transaction is active in the current thread, a new connection (not part of the transaction)
     * will be used to execute the callable.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Inside a transaction, but need to log something immediately
     * JdbcUtil.callNotInStartedTransaction(dataSource, () -> {
     *     // This runs with a separate connection
     *     auditDao.logImmediately("Operation started");
     *     return "Logged";
     * });
     * }</pre>
     *
     * @param <T> the type of the result returned by the callable
     * @param <E> the type of exception that the callable may throw
     * @param dataSource the DataSource to use
     * @param cmd the callable to execute outside any transaction
     * @return the result of the callable execution
     * @throws IllegalArgumentException if dataSource or cmd is null
     * @throws E if the callable throws an exception
     */
    @Beta
    public static <T, E extends Throwable> T callNotInStartedTransaction(final javax.sql.DataSource dataSource, final Throwables.Callable<T, E> cmd)
            throws IllegalArgumentException, E {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(cmd, cs.cmd);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
            doNotUseSpringTransactional(true);

            final SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

            try {
                if (tran == null) {
                    return cmd.call();
                } else {
                    return tran.callNotInMe(cmd);
                }
            } finally {
                doNotUseSpringTransactional(false);
            }
        } else {
            final SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

            if (tran == null) {
                return cmd.call();
            } else {
                return tran.callNotInMe(cmd);
            }
        }
    }

    /**
     * Executes the given function outside any active transaction for the specified DataSource.
     * The function receives the DataSource as a parameter and can use it to create connections
     * that are not part of any active transaction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String result = JdbcUtil.callNotInStartedTransaction(dataSource, ds -> {
     *     // Use the DataSource to perform operations outside transaction
     *     try (Connection conn = ds.getConnection()) {
     *         // Perform non-transactional operations
     *         return "Done";
     *     }
     * });
     * }</pre>
     *
     * @param <T> the type of the result returned by the function
     * @param <E> the type of exception that the function may throw
     * @param dataSource the DataSource to use
     * @param cmd the function to execute outside any transaction
     * @return the result of the function execution
     * @throws IllegalArgumentException if dataSource or cmd is null
     * @throws E if the function throws an exception
     */
    @Beta
    public static <T, E extends Throwable> T callNotInStartedTransaction(final javax.sql.DataSource dataSource,
            final Throwables.Function<javax.sql.DataSource, T, E> cmd) throws IllegalArgumentException, E {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(cmd, cs.cmd);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
            doNotUseSpringTransactional(true);

            final SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

            try {
                if (tran == null) {
                    return cmd.apply(dataSource);
                } else {
                    return tran.callNotInMe(() -> cmd.apply(dataSource));
                }
            } finally {
                doNotUseSpringTransactional(false);
            }
        } else {
            final SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

            if (tran == null) {
                return cmd.apply(dataSource);
            } else {
                return tran.callNotInMe(() -> cmd.apply(dataSource));
            }
        }
    }

    /**
     * Executes the given runnable outside any active transaction for the specified DataSource.
     * If a transaction is active in the current thread, a new connection (not part of the transaction)
     * will be used to execute the runnable.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Inside a transaction, but need to perform non-transactional operation
     * JdbcUtil.runNotInStartedTransaction(dataSource, () -> {
     *     // This runs with a separate connection
     *     cacheDao.refreshCache();
     * });
     * }</pre>
     *
     * @param <E> the type of exception that the runnable may throw
     * @param dataSource the DataSource to use
     * @param cmd the runnable to execute outside any transaction
     * @throws IllegalArgumentException if dataSource or cmd is null
     * @throws E if the runnable throws an exception
     */
    @Beta
    public static <E extends Throwable> void runNotInStartedTransaction(final javax.sql.DataSource dataSource, final Throwables.Runnable<E> cmd)
            throws IllegalArgumentException, E {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(cmd, cs.cmd);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
            doNotUseSpringTransactional(true);

            final SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

            try {
                if (tran == null) {
                    cmd.run();
                } else {
                    tran.runNotInMe(cmd);
                }
            } finally {
                doNotUseSpringTransactional(false);
            }
        } else {
            final SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

            if (tran == null) {
                cmd.run();
            } else {
                tran.runNotInMe(cmd);
            }
        }
    }

    /**
     * Executes the given consumer outside any active transaction for the specified DataSource.
     * The consumer receives the DataSource as a parameter and can use it to create connections
     * that are not part of any active transaction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * JdbcUtil.runNotInStartedTransaction(dataSource, ds -> {
     *     // Use the DataSource for non-transactional operations
     *     try (Connection conn = ds.getConnection()) {
     *         // Perform operations that should not be part of current transaction
     *     }
     * });
     * }</pre>
     *
     * @param <E> the type of exception that the consumer may throw
     * @param dataSource the DataSource to use
     * @param cmd the consumer to execute outside any transaction
     * @throws IllegalArgumentException if dataSource or cmd is null
     * @throws E if the consumer throws an exception
     */
    @Beta
    public static <E extends Throwable> void runNotInStartedTransaction(final javax.sql.DataSource dataSource,
            final Throwables.Consumer<javax.sql.DataSource, E> cmd) throws IllegalArgumentException, E {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(cmd, cs.cmd);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
            doNotUseSpringTransactional(true);

            final SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

            try {
                if (tran == null) {
                    cmd.accept(dataSource);
                } else {
                    tran.runNotInMe(() -> cmd.accept(dataSource));
                }
            } finally {
                doNotUseSpringTransactional(false);
            }
        } else {
            final SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

            if (tran == null) {
                cmd.accept(dataSource);
            } else {
                tran.runNotInMe(() -> cmd.accept(dataSource));
            }
        }
    }

    /**
     * Executes the given runnable without using Spring transaction management.
     * This temporarily disables Spring transaction integration for the current thread.
     * Note: The action should not be executed in another thread as the flag is thread-local.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * JdbcUtil.runWithoutUsingSpringTransaction(() -> {
     *     // Operations here will not participate in Spring transactions
     *     jdbcDao.performNonTransactionalOperation();
     * });
     * }</pre>
     *
     * @param <E> the type of exception that the runnable may throw
     * @param sqlAction the runnable to execute without Spring transaction
     * @throws E if the runnable throws an exception
     */
    public static <E extends Exception> void runWithoutUsingSpringTransaction(final Throwables.Runnable<E> sqlAction) throws E {
        if (isSpringTransactionalNotUsed()) {
            sqlAction.run();
        } else {
            doNotUseSpringTransactional(true);

            try {
                sqlAction.run();
            } finally {
                doNotUseSpringTransactional(false);
            }
        }
    }

    /**
     * Executes the given callable without using Spring transaction management and returns its result.
     * This temporarily disables Spring transaction integration for the current thread.
     * Note: The action should not be executed in another thread as the flag is thread-local.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String result = JdbcUtil.callWithoutUsingSpringTransaction(() -> {
     *     // Operations here will not participate in Spring transactions
     *     return jdbcDao.queryWithoutTransaction();
     * });
     * }</pre>
     *
     * @param <R> the type of result returned by the callable
     * @param <E> the type of exception that the callable may throw
     * @param sqlAction the callable to execute without Spring transaction
     * @return the result of the callable
     * @throws E if the callable throws an exception
     */
    public static <R, E extends Exception> R callWithoutUsingSpringTransaction(final Throwables.Callable<R, E> sqlAction) throws E {
        if (isSpringTransactionalNotUsed()) {
            return sqlAction.call();
        } else {
            doNotUseSpringTransactional(true);

            try {
                return sqlAction.call();
            } finally {
                doNotUseSpringTransactional(false);
            }
        }
    }

    /**
     * Don't share {@code Spring Transactional} in the current thread.
     *
     * {@code Spring Transactional} won't be used in fetching Connection if it's disabled.
     *
     * @param b {@code true} to not share, {@code false} to share it again.
     */
    static void doNotUseSpringTransactional(final boolean b) {
        // synchronized (isSpringTransactionalDisabled_TL) {
        if (isInSpring) {
            if (logger.isWarnEnabled() && isSpringTransactionalDisabled_TL.get() != b) { //NOSONAR
                if (b) {
                    logger.warn("Disable Spring Transactional");
                } else {
                    logger.warn("Enable Spring Transactional again");
                }
            }

            isSpringTransactionalDisabled_TL.set(b);
        } else {
            logger.warn("Not in Spring or not able to retrieve Spring Transactional");
        }
        // }
    }

    //    /**
    //     * Check if {@code Spring Transactional} is shared or not in the current thread.
    //     *
    //     * @return {@code true} if it's not shared, otherwise {@code false} is returned.
    //     * @deprecated replaced by {@link #isSpringTransactionalNotUsed()}
    //     */
    //    @Deprecated
    //    public static boolean isSpringTransactionalDisabled() {
    //        return !isInSpring || isSpringTransactionalDisabled_TL.get();
    //    }

    //    /**
    //     * Don't share {@code Spring Transactional} in current thread.
    //     *
    //     * {@code Spring Transactional} won't be used in fetching Connection if it's disabled.
    //     *
    //     * @param b {@code true} to not share, {@code false} to share it again.
    //     * @deprecated replaced by {@link #doNotUseSpringTransactional(boolean)}
    //     */
    //    @Deprecated
    //    public static void disableSpringTransactional(final boolean b) {
    //        doNotUseSpringTransactional(b);
    //    }

    //    /**
    //     * Check if {@code Spring Transactional} is shared or not in the current thread.
    //     *
    //     * @return {@code true} if it's not shared, otherwise {@code false} is returned.
    //     * @deprecated replaced by {@link #isSpringTransactionalNotUsed()}
    //     */
    //    @Deprecated
    //    public static boolean isSpringTransactionalDisabled() {
    //        return !isInSpring || isSpringTransactionalDisabled_TL.get();
    //    }

    //    /**
    //     * Don't share {@code Spring Transactional} in current thread.
    //     *
    //     * {@code Spring Transactional} won't be used in fetching Connection if it's disabled.
    //     *
    //     * @param b {@code true} to not share, {@code false} to share it again.
    //     * @deprecated replaced by {@link #doNotUseSpringTransactional(boolean)}
    //     */
    //    @Deprecated
    //    public static void disableSpringTransactional(final boolean b) {
    //        doNotUseSpringTransactional(b);
    //    }

    //    /**
    //     * Check if {@code Spring Transactional} is shared or not in the current thread.
    //     *
    //     * @return {@code true} if it's not shared, otherwise {@code false} is returned.
    //     * @deprecated replaced by {@link #isSpringTransactionalNotUsed()}
    //     */
    //    @Deprecated
    //    public static boolean isSpringTransactionalDisabled() {
    //        return !isInSpring || isSpringTransactionalDisabled_TL.get();
    //    }

    //    /**
    //     * Don't share {@code Spring Transactional} in current thread.
    //     *
    //     * {@code Spring Transactional} won't be used in fetching Connection if it's disabled.
    //     *
    //     * @param b {@code true} to not share, {@code false} to share it again.
    //     * @deprecated replaced by {@link #doNotUseSpringTransactional(boolean)}
    //     */
    //    @Deprecated
    //    public static void disableSpringTransactional(final boolean b) {
    //        doNotUseSpringTransactional(b);
    //    }

    //    /**
    //     * Check if {@code Spring Transactional} is shared or not in the current thread.
    //     *
    //     * @return {@code true} if it's not shared, otherwise {@code false} is returned.
    //     * @deprecated replaced by {@link #isSpringTransactionalNotUsed()}
    //     */
    //    @Deprecated
    //    public static boolean isSpringTransactionalDisabled() {
    //        return !isInSpring || isSpringTransactionalDisabled_TL.get();
    //    }

    //    /**
    //     * Don't share {@code Spring Transactional} in current thread.
    //     *
    //     * {@code Spring Transactional} won't be used in fetching Connection if it's disabled.
    //     *
    //     * @param b {@code true} to not share, {@code false} to share it again.
    //     * @deprecated replaced by {@link #doNotUseSpringTransactional(boolean)}
    //     */
    //    @Deprecated
    //    public static void disableSpringTransactional(final boolean b) {
    //        doNotUseSpringTransactional(b);
    //    }

    //    /**
    //     * Check if {@code Spring Transactional} is shared or not in the current thread.
    //     *
    //     * @return {@code true} if it's not shared, otherwise {@code false} is returned.
    //     * @deprecated replaced by {@link #isSpringTransactionalNotUsed()}
    //     */
    //    @Deprecated
    //    public static boolean isSpringTransactionalDisabled() {
    //        return !isInSpring || isSpringTransactionalDisabled_TL.get();
    //    }
    /**
     * Check if {@code Spring Transactional} is shared or not in the current thread.
     *
     * @return {@code true} if it's not shared, otherwise {@code false} is returned.
     */
    static boolean isSpringTransactionalNotUsed() {
        return !isInSpring || isSpringTransactionalDisabled_TL.get();
    }

    @SuppressWarnings({ "rawtypes", "deprecation", "null" })
    static <ID> Tuple3<BiRowMapper<ID>, com.landawn.abacus.util.function.Function<Object, ID>, com.landawn.abacus.util.function.BiConsumer<ID, Object>> getIdGeneratorGetterSetter(
            final Class<? extends Dao> daoInterface, final Class<?> entityClass, final NamingPolicy namingPolicy, final Class<?> idType) {
        if (!Beans.isBeanClass(entityClass)) {
            return (Tuple3) noIdGeneratorGetterSetter;
        }

        final Tuple2<Class<?>, Class<?>> key = Tuple.of(entityClass, idType);

        Map<NamingPolicy, Tuple3<BiRowMapper, com.landawn.abacus.util.function.Function, com.landawn.abacus.util.function.BiConsumer>> map = idGeneratorGetterSetterPool
                .get(key);

        if (map == null) {
            final List<String> idPropNameList = QueryUtil.getIdFieldNames(entityClass);
            final boolean isNoId = N.isEmpty(idPropNameList) || QueryUtil.isFakeId(idPropNameList);
            final String oneIdPropName = isNoId ? null : idPropNameList.get(0);
            final BeanInfo entityInfo = isNoId ? null : ParserUtil.getBeanInfo(entityClass);
            final List<PropInfo> idPropInfoList = isNoId ? null : Stream.of(idPropNameList).map(entityInfo::getPropInfo).toList();
            final PropInfo idPropInfo = isNoId ? null : entityInfo.getPropInfo(oneIdPropName);
            final boolean isOneId = !isNoId && idPropNameList.size() == 1;
            final boolean isEntityId = idType != null && EntityId.class.isAssignableFrom(idType);
            final BeanInfo idBeanInfo = Beans.isBeanClass(idType) ? ParserUtil.getBeanInfo(idType) : null;

            final com.landawn.abacus.util.function.Function<Object, ID> idGetter = isNoId ? noIdGeneratorGetterSetter._2 //
                    : (isOneId ? idPropInfo::getPropValue //
                            : (isEntityId ? entity -> {
                                final Seid ret = Seid.of(ClassUtil.getSimpleClassName(entityClass));

                                for (final PropInfo propInfo : idPropInfoList) {
                                    ret.set(propInfo.name, propInfo.getPropValue(entity));
                                }

                                return (ID) ret;
                            } : entity -> {
                                final Object ret = idBeanInfo.createBeanResult();

                                for (final PropInfo propInfo : idPropInfoList) {
                                    Beans.setPropValue(ret, propInfo.name, propInfo.getPropValue(entity));
                                }

                                return (ID) idBeanInfo.finishBeanResult(ret);
                            }));

            final com.landawn.abacus.util.function.BiConsumer<ID, Object> idSetter = isNoId ? noIdGeneratorGetterSetter._3 //
                    : (isOneId ? (id, entity) -> idPropInfo.setPropValue(entity, id) //
                            : (isEntityId ? (id, entity) -> {
                                if (id instanceof final EntityId entityId) {
                                    PropInfo propInfo = null;

                                    for (final String propName : entityId.keySet()) {
                                        propInfo = entityInfo.getPropInfo(propName);

                                        if ((propInfo = entityInfo.getPropInfo(propName)) != null) {
                                            propInfo.setPropValue(entity, entityId.get(propName));
                                        }
                                    }
                                } else {
                                    logger.warn("Can't set generated keys by id type: " + ClassUtil.getCanonicalClassName(id.getClass()));
                                }
                            } : (id, entity) -> {
                                if (id != null && Beans.isBeanClass(id.getClass())) {
                                    @SuppressWarnings("UnnecessaryLocalVariable")
                                    final Object entityId = id;

                                    for (final PropInfo propInfo : idPropInfoList) {
                                        propInfo.setPropValue(entity, Beans.getPropValue(entityId, propInfo.name));
                                    }
                                } else {
                                    logger.warn(
                                            "Can't set generated keys by id type: " + (id == null ? "null" : ClassUtil.getCanonicalClassName(id.getClass())));
                                }
                            }));

            map = new EnumMap<>(NamingPolicy.class);

            for (final NamingPolicy np : NamingPolicy.values()) {
                final ImmutableMap<String, String> propColumnNameMap = QueryUtil.getProp2ColumnNameMap(entityClass, namingPolicy);

                final ImmutableMap<String, String> columnPropNameMap = EntryStream.of(propColumnNameMap)
                        .inversed()
                        .flatmapKey(e -> N.asList(e, e.toLowerCase(), e.toUpperCase()))
                        .distinctByKey()
                        .toImmutableMap();

                final BiRowMapper<Object> keyExtractor = isNoId ? noIdGeneratorGetterSetter._1
                        : (idExtractorPool.containsKey(daoInterface) ? (BiRowMapper<Object>) idExtractorPool.get(daoInterface) //
                                : (isOneId ? (rs, columnLabels) -> idPropInfo.dbType.get(rs, 1) //
                                        : (rs, columnLabels) -> {
                                            if (columnLabels.size() == 1) {
                                                return idPropInfo.dbType.get(rs, 1);
                                            } else if (isEntityId) {
                                                final int columnCount = columnLabels.size();
                                                final Seid id = Seid.of(ClassUtil.getSimpleClassName(entityClass));
                                                String columnName = null;
                                                String propName = null;
                                                PropInfo propInfo = null;

                                                for (int i = 0; i < columnCount; i++) {
                                                    columnName = columnLabels.get(i);

                                                    if ((propName = columnPropNameMap.get(columnName)) == null
                                                            || (propInfo = entityInfo.getPropInfo(propName)) == null) {
                                                        id.set(columnName, JdbcUtil.getColumnValue(rs, i + 1));
                                                    } else {
                                                        id.set(propInfo.name, propInfo.dbType.get(rs, i + 1));
                                                    }
                                                }

                                                return id;
                                            } else {
                                                final List<Tuple2<String, PropInfo>> tpList = Stream.of(columnLabels)
                                                        .filter(it -> idBeanInfo.getPropInfo(it) != null)
                                                        .map(it -> Tuple.of(it, idBeanInfo.getPropInfo(it)))
                                                        .toList();
                                                final Object id = idBeanInfo.createBeanResult();

                                                for (final Tuple2<String, PropInfo> tp : tpList) {
                                                    tp._2.setPropValue(id, tp._2.dbType.get(rs, tp._1));
                                                }

                                                return idBeanInfo.finishBeanResult(id);
                                            }
                                        }));

                map.put(np, Tuple.of(keyExtractor, idGetter, idSetter));
            }

            idGeneratorGetterSetterPool.put(key, map);
        }

        return (Tuple3) map.get(namingPolicy);
    }

    /**
     * Sets the ID extractor for the specified DAO interface.
     * This allows customization of how IDs are extracted from ResultSets for a specific DAO.
     * The extractor is used when retrieving generated keys after insert operations.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Custom ID extraction for composite keys
     * JdbcUtil.setIdExtractorForDao(UserDao.class, rs -> {
     *     return new UserId(rs.getLong("tenant_id"), rs.getLong("user_id"));
     * });
     * }</pre>
     *
     * @param <T> the type of the entity
     * @param <ID> the type of the ID
     * @param <SB> the type of the SQLBuilder
     * @param <TD> the type of the CrudDao
     * @param daoInterface the DAO interface class
     * @param idExtractor the RowMapper used to extract the ID from ResultSet
     * @throws IllegalArgumentException if daoInterface or idExtractor is null
     */
    public static <T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>> void setIdExtractorForDao(
            final Class<? extends CrudDao<T, ID, SB, TD>> daoInterface, final RowMapper<? extends ID> idExtractor) throws IllegalArgumentException {
        N.checkArgNotNull(daoInterface, cs.daoInterface);
        N.checkArgNotNull(idExtractor, cs.idExtractor);

        idExtractorPool.put(daoInterface, (rs, cls) -> idExtractor.apply(rs));
    }

    /**
     * Sets the ID extractor for the specified DAO interface using a BiRowMapper.
     * This allows customization of how IDs are extracted from ResultSets with access to column labels.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Custom ID extraction with column label awareness
     * JdbcUtil.setIdExtractorForDao(UserDao.class, (rs, columnLabels) -> {
     *     if (columnLabels.contains("composite_id")) {
     *         return UserIdParser.parse(rs.getString("composite_id"));
     *     }
     *     return rs.getLong("id");
     * });
     * }</pre>
     *
     * @param <T> the type of the entity
     * @param <ID> the type of the ID
     * @param <SB> the type of the SQLBuilder
     * @param <TD> the type of the CrudDao
     * @param daoInterface the DAO interface class
     * @param idExtractor the BiRowMapper used to extract the ID with column information
     * @throws IllegalArgumentException if daoInterface or idExtractor is null
     */
    public static <T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>> void setIdExtractorForDao(
            final Class<? extends CrudDao<T, ID, SB, TD>> daoInterface, final BiRowMapper<? extends ID> idExtractor) throws IllegalArgumentException {
        N.checkArgNotNull(daoInterface, cs.daoInterface);
        N.checkArgNotNull(idExtractor, cs.idExtractor);

        idExtractorPool.put(daoInterface, idExtractor);
    }

    /**
     * Creates a dynamic Data Access Object (DAO) implementation for the specified interface and DataSource.
     * This method generates a runtime proxy that implements all methods in the DAO interface, automatically
     * handling CRUD operations, query execution, and transaction management based on method signatures and annotations.
     *
     * <p>The created DAO provides type-safe database access with automatic SQL generation for standard CRUD operations,
     * custom query support via annotations or SQL files, and seamless integration with the JdbcUtil transaction management.
     * All CRUD operations are automatically implemented based on the entity class and its annotations.</p>
     *
     * <p><b>Key Features of Generated DAOs:</b></p>
     * <ul>
     *   <li>Automatic CRUD operations (save, update, delete, findById, list, etc.)</li>
     *   <li>Custom query methods defined by annotations or external SQL files</li>
     *   <li>Batch operation support for high-throughput processing</li>
     *   <li>Transaction-aware operations that participate in active transactions</li>
     *   <li>Type-safe parameter binding and result mapping</li>
     *   <li>Asynchronous operation support with CompletableFuture return types</li>
     *   <li>Stream-based result processing for large datasets</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Define a DAO interface extending CrudDao or Dao
     * public interface UserDao extends CrudDao<User, Long, SQLBuilder.PSC, UserDao> {
     *     // Automatic CRUD methods are inherited:
     *     // - save(User user)
     *     // - batchSave(Collection<User> users)
     *     // - getsById(Long id)
     *     // - update(User user)
     *     // - deleteById(Long id)
     *     // - list()
     *     // - findFirst(Condition where)
     *
     *     // Custom query methods
     *     @Select("SELECT * FROM users WHERE email = ?")
     *     Optional<User> findByEmail(String email);
     *
     *     @Select("SELECT * FROM users WHERE status = ? ORDER BY created_at DESC")
     *     List<User> findByStatus(String status);
     *
     *     @NamedSelect("SELECT * FROM users WHERE age >= :minAge AND city = :city")
     *     Stream<User> findByAgeAndCity(@Bind("minAge") int minAge, @Bind("city") String city);
     * }
     *
     * // Create and use the DAO
     * UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource);
     *
     * // Use inherited CRUD operations
     * User newUser = new User("john@example.com", "John Doe");
     * userDao.save(newUser);
     *
     * // Use custom query methods
     * Optional<User> user = userDao.findByEmail("john@example.com");
     * if (user.isPresent()) {
     *     System.out.println("Found: " + user.get().getName());
     * }
     *
     * // List all active users
     * List<User> activeUsers = userDao.findByStatus("ACTIVE");
     *
     * // Stream results for large datasets
     * try (Stream<User> stream = userDao.findByAgeAndCity(25, "New York")) {
     *     long count = stream
     *         .filter(u -> u.getEmail().endsWith("@company.com"))
     *         .count();
     * }
     *
     * // Batch operations
     * List<User> users = Arrays.asList(user1, user2, user3);
     * userDao.batchSave(users);
     *
     * // Update operations
     * user.get().setStatus("INACTIVE");
     * userDao.update(user.get());
     *
     * // Delete operations
     * userDao.deleteById(userId);
     * }</pre>
     *
     * <p><b>Advanced DAO Features:</b></p>
     * <pre>{@code
     * // Define a DAO with complex queries
     * public interface OrderDao extends CrudDao<Order, Long, SQLBuilder.PSC, OrderDao> {
     *     // Aggregate queries
     *     @Select("SELECT COUNT(*) FROM orders WHERE status = ?")
     *     long countByStatus(String status);
     *
     *     @Select("SELECT SUM(total_amount) FROM orders WHERE customer_id = ?")
     *     Optional<BigDecimal> getTotalByCustomer(Long customerId);
     *
     *     // Complex joins (SQL defined externally in SQL mapper file)
     *     @NamedSelect("findOrdersWithCustomerDetails")
     *     List<OrderWithCustomer> findOrdersWithCustomerDetails(@Bind("startDate") Date start);
     *
     *     // Async operations
     *     @Select("SELECT * FROM orders WHERE id = ?")
     *     CompletableFuture<Optional<Order>> findByIdAsync(Long id);
     * }
     *
     * OrderDao orderDao = JdbcUtil.createDao(OrderDao.class, dataSource);
     *
     * // Use aggregate queries
     * long pendingCount = orderDao.countByStatus("PENDING");
     *
     * // Async operations
     * CompletableFuture<Optional<Order>> future = orderDao.findByIdAsync(orderId);
     * future.thenAccept(order -> {
     *     order.ifPresent(o -> System.out.println("Order: " + o.getId()));
     * });
     * }</pre>
     *
     * @param <TD> the type parameter of the DAO interface, must extend {@link Dao}
     * @param daoInterface the DAO interface class to implement, must not be {@code null}. The interface should
     *                     extend {@link Dao} or {@link CrudDao} and define the entity type and ID type
     * @param ds the {@link javax.sql.DataSource} to use for all database operations, must not be {@code null}
     * @return a dynamically generated DAO instance implementing the specified interface with full CRUD capabilities
     * @throws IllegalArgumentException if {@code daoInterface} or {@code ds} is {@code null}
     * @see Dao
     * @see CrudDao
     * @see #createDao(Class, javax.sql.DataSource, SQLMapper)
     * @see #createDao(Class, javax.sql.DataSource, Executor)
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds) {
        return createDao(daoInterface, ds, JdbcUtil.asyncExecutor.getExecutor());
    }

    /**
     * Creates a DAO instance with a custom SQL mapper for query externalization.
     * The SQL mapper allows SQL queries to be defined in external files or resources.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SQLMapper sqlMapper = SQLMapper.fromFile("sql/user-queries.xml");
     * UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource, sqlMapper);
     * }</pre>
     *
     * @param <TD> the type of the DAO
     * @param daoInterface the DAO interface class to implement
     * @param ds the DataSource to use for database operations
     * @param sqlMapper the SQL mapper for externalizing queries
     * @return a DAO instance implementing the specified interface
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds, final SQLMapper sqlMapper) {
        return createDao(daoInterface, ds, sqlMapper, JdbcUtil.asyncExecutor.getExecutor());
    }

    /**
     * Creates a DAO instance with a custom SQL mapper and DAO cache.
     * The cache can improve performance by caching query results.
     *
     * @param <TD> the type of the DAO
     * @param daoInterface the DAO interface class to implement
     * @param ds the DataSource to use for database operations
     * @param sqlMapper the SQL mapper for externalizing queries
     * @param daoCache the cache for DAO operations (should not be shared between DAOs)
     * @return a DAO instance implementing the specified interface
     * @deprecated Use version without explicit cache parameter
     */
    @Deprecated
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds, final SQLMapper sqlMapper,
            final Jdbc.DaoCache daoCache) {
        return createDao(daoInterface, ds, sqlMapper, daoCache, JdbcUtil.asyncExecutor.getExecutor());
    }

    /**
     * Creates a DAO instance with a custom executor for asynchronous operations.
     * This allows control over the thread pool used for async DAO methods.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService executor = Executors.newFixedThreadPool(10);
     * UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource, executor);
     * CompletableFuture<List<User>> future = userDao.findAllAsync();
     * }</pre>
     *
     * @param <TD> the type of the DAO
     * @param daoInterface the DAO interface class to implement
     * @param ds the DataSource to use for database operations
     * @param executor the executor for asynchronous operations
     * @return a DAO instance implementing the specified interface
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds, final Executor executor) {
        return createDao(daoInterface, ds, null, executor);
    }

    /**
     * Creates a DAO instance with a custom SQL mapper and executor.
     * Combines external SQL management with custom thread pool control.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SQLMapper sqlMapper = SQLMapper.fromFile("sql/queries.xml");
     * ExecutorService executor = Executors.newCachedThreadPool();
     * UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource, sqlMapper, executor);
     * }</pre>
     *
     * @param <TD> the type of the DAO
     * @param daoInterface the DAO interface class to implement
     * @param ds the DataSource to use for database operations
     * @param sqlMapper the SQL mapper for externalizing queries
     * @param executor the executor for asynchronous operations
     * @return a DAO instance implementing the specified interface
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds, final SQLMapper sqlMapper,
            final Executor executor) {
        return createDao(daoInterface, ds, sqlMapper, null, executor);
    }

    /**
     * Creates a DAO instance with all customization options.
     * Provides full control over SQL mapping, caching, and async execution.
     *
     * @param <TD> the type of the DAO
     * @param daoInterface the DAO interface class to implement
     * @param ds the DataSource to use for database operations
     * @param sqlMapper the SQL mapper for externalizing queries
     * @param daoCache the cache for DAO operations (should not be shared between DAOs)
     * @param executor the executor for asynchronous operations
     * @return a DAO instance implementing the specified interface
     * @deprecated Use version without explicit cache parameter
     */
    @Deprecated
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds, final SQLMapper sqlMapper,
            final Jdbc.DaoCache daoCache, final Executor executor) {
        return DaoImpl.createDao(daoInterface, null, ds, sqlMapper, daoCache, executor);
    }

    /**
     * Creates a DAO instance for a specific table name.
     * This is useful when the table name differs from the entity class name.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Entity class is "User" but table is "app_users"
     * UserDao userDao = JdbcUtil.createDao(UserDao.class, "app_users", dataSource);
     * }</pre>
     *
     * @param <TD> the type of the DAO
     * @param daoInterface the DAO interface class to implement
     * @param targetTableName the specific table name to use
     * @param ds the DataSource to use for database operations
     * @return a DAO instance implementing the specified interface
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final String targetTableName, final javax.sql.DataSource ds) {
        return createDao(daoInterface, targetTableName, ds, JdbcUtil.asyncExecutor.getExecutor());
    }

    /**
     * Creates a DAO instance for a specific table with a custom SQL mapper.
     * Combines custom table naming with external SQL management.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SQLMapper sqlMapper = SQLMapper.fromFile("sql/legacy-queries.xml");
     * UserDao userDao = JdbcUtil.createDao(UserDao.class, "legacy_users", dataSource, sqlMapper);
     * }</pre>
     *
     * @param <TD> the type of the DAO
     * @param daoInterface the DAO interface class to implement
     * @param targetTableName the specific table name to use
     * @param ds the DataSource to use for database operations
     * @param sqlMapper the SQL mapper for externalizing queries
     * @return a DAO instance implementing the specified interface
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final String targetTableName, final javax.sql.DataSource ds,
            final SQLMapper sqlMapper) {
        return createDao(daoInterface, targetTableName, ds, sqlMapper, JdbcUtil.asyncExecutor.getExecutor());
    }

    /**
     * Creates a DAO instance for a specific table with SQL mapper and cache.
     *
     * @param <TD> the type of the DAO
     * @param daoInterface the DAO interface class to implement
     * @param targetTableName the specific table name to use
     * @param ds the DataSource to use for database operations
     * @param sqlMapper the SQL mapper for externalizing queries
     * @param daoCache the cache for DAO operations
     * @return a DAO instance implementing the specified interface
     * @deprecated Use version without explicit cache parameter
     */
    @Deprecated
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final String targetTableName, final javax.sql.DataSource ds,
            final SQLMapper sqlMapper, final Jdbc.DaoCache daoCache) {
        return createDao(daoInterface, targetTableName, ds, sqlMapper, daoCache, JdbcUtil.asyncExecutor.getExecutor());
    }

    /**
     * Creates a DAO instance for a specific table with a custom executor.
     * Allows custom table naming with control over async operations.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ForkJoinPool customPool = new ForkJoinPool(20);
     * UserDao userDao = JdbcUtil.createDao(UserDao.class, "users_2024", dataSource, customPool);
     * }</pre>
     *
     * @param <TD> the type of the DAO
     * @param daoInterface the DAO interface class to implement
     * @param targetTableName the specific table name to use
     * @param ds the DataSource to use for database operations
     * @param executor the executor for asynchronous operations
     * @return a DAO instance implementing the specified interface
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final String targetTableName, final javax.sql.DataSource ds,
            final Executor executor) {
        return createDao(daoInterface, targetTableName, ds, null, executor);
    }

    /**
     * Creates a DAO instance for a specific table with SQL mapper and executor.
     * Combines all customization options except caching.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SQLMapper sqlMapper = SQLMapper.fromResource("/sql/custom-queries.xml");
     * ExecutorService executor = Executors.newWorkStealingPool();
     * UserDao userDao = JdbcUtil.createDao(UserDao.class, "custom_users", dataSource, sqlMapper, executor);
     * }</pre>
     *
     * @param <TD> the type of the DAO
     * @param daoInterface the DAO interface class to implement
     * @param targetTableName the specific table name to use
     * @param ds the DataSource to use for database operations
     * @param sqlMapper the SQL mapper for externalizing queries
     * @param executor the executor for asynchronous operations
     * @return a DAO instance implementing the specified interface
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final String targetTableName, final javax.sql.DataSource ds,
            final SQLMapper sqlMapper, final Executor executor) {
        return createDao(daoInterface, targetTableName, ds, sqlMapper, null, executor);
    }

    /**
     * Creates a DAO instance with all customization options including table name.
     * Provides maximum flexibility for DAO configuration.
     *
     * @param <TD> the type of the DAO
     * @param daoInterface the DAO interface class to implement
     * @param targetTableName the specific table name to use
     * @param ds the DataSource to use for database operations
     * @param sqlMapper the SQL mapper for externalizing queries
     * @param cache the cache for DAO operations (should not be shared between DAOs)
     * @param executor the executor for asynchronous operations
     * @return a DAO instance implementing the specified interface
     * @throws IllegalArgumentException if required parameters are invalid
     * @deprecated Use version without explicit cache parameter
     */
    @Deprecated
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final String targetTableName, final javax.sql.DataSource ds,
            final SQLMapper sqlMapper, final Jdbc.DaoCache cache, final Executor executor) throws IllegalArgumentException {
        return DaoImpl.createDao(daoInterface, targetTableName, ds, sqlMapper, cache, executor);
    }

    @SuppressWarnings("unused")
    static String createCacheKey(final String tableName, final String fullClassMethodName, final Object[] args, final Logger daoLogger) {
        String paramKey = null;

        if (kryoParser != null) {
            try {
                paramKey = kryoParser.serialize(args);
            } catch (final Exception e) {
                // ignore;
                daoLogger.warn("Failed to generated cache key and not able cache the result for method: " + fullClassMethodName);
            }
        } else {
            final List<Object> newArgs = Stream.of(args).map(it -> {
                if (it == null) {
                    return null;
                }

                final Type<?> type = N.typeOf(it.getClass());

                if (type.isSerializable() || type.isCollection() || type.isMap() || type.isArray() || type.isBean() || type.isEntityId()) {
                    return it;
                } else {
                    return it.toString();
                }
            }).toList();

            try {
                paramKey = N.toJson(newArgs);
            } catch (final Exception e) {
                // ignore;
                daoLogger.warn("Failed to generated cache key and not able cache the result for method: " + fullClassMethodName);
            }
        }

        return Strings.concat(fullClassMethodName, CACHE_KEY_SPLITOR, tableName, CACHE_KEY_SPLITOR, paramKey);
    }

    /**
     * Enables DAO query result caching for the current thread.
     * Creates a new thread-local cache that will be used by all DAOs in the current thread.
     * Must be paired with {@link #closeDaoCacheOnCurrentThread()} to prevent memory leaks.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Jdbc.DaoCache cache = JdbcUtil.startDaoCacheOnCurrentThread();
     * try {
     *     // DAO operations here will use the cache
     *     userDao.findById(1L); // First call hits database
     *     userDao.findById(1L); // Second call uses cache
     * } finally {
     *     JdbcUtil.closeDaoCacheOnCurrentThread();
     * }
     * }</pre>
     *
     * @return the created DaoCache for the current thread
     * @see Jdbc.DaoCache#createByMap()
     * @see #closeDaoCacheOnCurrentThread()
     */
    public static Jdbc.DaoCache startDaoCacheOnCurrentThread() {
        final Jdbc.DaoCache localThreadCache = Jdbc.DaoCache.createByMap();

        return startDaoCacheOnCurrentThread(localThreadCache);
    }

    /**
     * Enables the specified DAO cache for the current thread.
     * The provided cache will be used by all DAOs in the current thread.
     * Must be paired with {@link #closeDaoCacheOnCurrentThread()} to prevent memory leaks.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Use a custom cache implementation
     * Map<String, Object> cacheMap = new LRUMap<>(1000);
     * Jdbc.DaoCache cache = Jdbc.DaoCache.createByMap(cacheMap);
     * 
     * JdbcUtil.startDaoCacheOnCurrentThread(cache);
     * try {
     *     // DAO operations use the custom cache
     *     productDao.findPopular();
     * } finally {
     *     JdbcUtil.closeDaoCacheOnCurrentThread();
     * }
     * }</pre>
     *
     * @param localThreadCache the cache to use for the current thread
     * @return the specified localThreadCache
     * @see Jdbc.DaoCache#createByMap()
     * @see Jdbc.DaoCache#createByMap(Map)
     * @see #closeDaoCacheOnCurrentThread()
     */
    public static Jdbc.DaoCache startDaoCacheOnCurrentThread(final Jdbc.DaoCache localThreadCache) {
        localThreadCache_TL.set(localThreadCache);

        return localThreadCache;
    }

    /**
     * Closes and removes the DAO cache for the current thread.
     * This method should always be called in a finally block after starting a thread-local cache
     * to prevent memory leaks.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * JdbcUtil.startDaoCacheOnCurrentThread();
     * try {
     *     // Use cached DAO operations
     * } finally {
     *     JdbcUtil.closeDaoCacheOnCurrentThread(); // Always clean up
     * }
     * }</pre>
     *
     * @see #startDaoCacheOnCurrentThread()
     * @see #startDaoCacheOnCurrentThread(Jdbc.DaoCache)
     */
    public static void closeDaoCacheOnCurrentThread() {
        localThreadCache_TL.remove();
    }

    // ==============================================Jdbc Context=======================================================>>
}
