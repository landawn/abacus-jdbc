package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.ReadOnly;
import com.landawn.abacus.annotation.Transient;
import com.landawn.abacus.jdbc.Jdbc.BiRowMapper;
import com.landawn.abacus.jdbc.Jdbc.OutParam;
import com.landawn.abacus.jdbc.Jdbc.OutParamResult;
import com.landawn.abacus.jdbc.Jdbc.RowFilter;
import com.landawn.abacus.jdbc.Jdbc.RowMapper;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.query.SqlBuilder;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.ImmutableMap;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.function.TriConsumer;
import com.landawn.abacus.util.stream.Stream;

public class JdbcUtilTest extends TestBase {

    // TODO: The remaining JdbcUtil overload matrix includes close/skip helpers and large-result query adapters whose
    // behavior is mostly exercised through shared internals. Add focused fixture-based tests for any uncovered branches.

    private DataSource mockDataSource;
    private Connection mockConnection;
    private PreparedStatement mockPreparedStatement;
    private CallableStatement mockCallableStatement;
    private ResultSet mockResultSet;
    private ResultSetMetaData mockResultSetMetaData;
    private DatabaseMetaData mockDatabaseMetaData;
    private Statement mockStatement;
    private Blob mockBlob;
    private Clob mockClob;

    @BeforeEach
    public void setUp() throws SQLException {
        mockDataSource = mock(DataSource.class);
        mockConnection = mock(Connection.class);
        mockPreparedStatement = mock(PreparedStatement.class);
        mockCallableStatement = mock(CallableStatement.class);
        mockResultSet = mock(ResultSet.class);
        mockResultSetMetaData = mock(ResultSetMetaData.class);
        mockDatabaseMetaData = mock(DatabaseMetaData.class);
        mockStatement = mock(Statement.class);
        mockBlob = mock(Blob.class);
        mockClob = mock(Clob.class);

        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("8");
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);
        when(mockConnection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(mockPreparedStatement);
        when(mockConnection.prepareCall(anyString())).thenReturn(mockCallableStatement);
        when(mockConnection.getMetaData()).thenReturn(mockDatabaseMetaData);
        when(mockPreparedStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.getMetaData()).thenReturn(mockResultSetMetaData);
    }

    @AfterEach
    public void tearDown() {
        // Clean up any thread-local state
        JdbcUtil.disableSqlLog();
        JdbcUtil.closeDaoCacheOnCurrentThread();
    }

    @Test
    public void testGetDBProductInfo_DataSource() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("8.0.23");

        DBProductInfo info = JdbcUtil.getDBProductInfo(mockDataSource);

        assertNotNull(info);
        assertEquals("MySQL", info.productName());
        assertEquals("8.0.23", info.productVersion());
        assertEquals(DBVersion.MySQL_8, info.version());
    }

    @Test
    public void testGetDBProductInfo_Connection() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("12.5");

        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);

        assertNotNull(info);
        assertEquals("PostgreSQL", info.productName());
        assertEquals("12.5", info.productVersion());
        assertEquals(DBVersion.PostgreSQL_12, info.version());
    }

    @Test
    public void testGetDBProductInfo_PostgreSQL96_Connection() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("9.6.24");

        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);

        assertNotNull(info);
        assertEquals("PostgreSQL", info.productName());
        assertEquals("9.6.24", info.productVersion());
        assertEquals(DBVersion.PostgreSQL_9_6, info.version());
    }

    @Test
    public void testGetDBProductInfo_MariaDBReportedAsMySQL_Connection() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("5.5.5-10.11.11-MariaDB");

        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);

        assertNotNull(info);
        assertEquals("MySQL", info.productName());
        assertEquals("5.5.5-10.11.11-MariaDB", info.productVersion());
        assertEquals(DBVersion.MariaDB, info.version());
    }

    @Test
    public void testPrepareQueryForLargeResult_DataSource() throws SQLException {
        PreparedQuery query = JdbcUtil.prepareQueryForLargeResult(mockDataSource, "SELECT * FROM demo");

        assertNotNull(query);
        verify(mockPreparedStatement).setFetchDirection(ResultSet.FETCH_FORWARD);
        verify(mockPreparedStatement).setFetchSize(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT);
    }

    @Test
    public void testCreateHikariDataSource() {
        String url = "jdbc:h2:mem:test";
        String user = "sa";
        String password = "";

        assertDataSourceCreation("com.zaxxer.hikari.HikariDataSource", () -> JdbcUtil.createHikariDataSource(url, user, password));
    }

    @Test
    public void testCreateHikariDataSourceWithPoolConfig() {
        String url = "jdbc:h2:mem:test";
        String user = "sa";
        String password = "";
        int minIdle = 5;
        int maxPoolSize = 20;

        assertDataSourceCreation("com.zaxxer.hikari.HikariDataSource", () -> JdbcUtil.createHikariDataSource(url, user, password, minIdle, maxPoolSize));
    }

    @Test
    public void testCreateC3p0DataSource() {
        String url = "jdbc:h2:mem:test";
        String user = "sa";
        String password = "";

        assertDataSourceCreation("com.mchange.v2.c3p0.ComboPooledDataSource", () -> JdbcUtil.createC3p0DataSource(url, user, password));
    }

    @Test
    public void testCreateC3p0DataSourceWithPoolConfig() {
        String url = "jdbc:h2:mem:test";
        String user = "sa";
        String password = "";
        int minPoolSize = 3;
        int maxPoolSize = 15;

        assertDataSourceCreation("com.mchange.v2.c3p0.ComboPooledDataSource",
                () -> JdbcUtil.createC3p0DataSource(url, user, password, minPoolSize, maxPoolSize));
    }

    @Test
    public void testCreateConnection() {
        String url = "jdbc:h2:mem:test";
        String user = "sa";
        String password = "";

        assertConnectionCreation(() -> JdbcUtil.createConnection(url, user, password));
    }

    @Test
    public void testCreateConnectionWithDriverClass() {
        String driverClass = "org.h2.Driver";
        String url = "jdbc:h2:mem:test";
        String user = "sa";
        String password = "";

        assertConnectionCreation(() -> JdbcUtil.createConnection(driverClass, url, user, password));
    }

    @Test
    public void testGetConnection() throws SQLException {
        Connection conn = JdbcUtil.getConnection(mockDataSource);
        assertNotNull(conn);
        assertEquals(mockConnection, conn);
    }

    @Test
    public void testReleaseConnection() throws SQLException {
        JdbcUtil.releaseConnection(mockConnection, mockDataSource);
        // Connection should be closed after release
        verify(mockConnection).close();

        // Releasing a null connection should be a safe no-op
        assertDoesNotThrow(() -> JdbcUtil.releaseConnection(null, mockDataSource));
    }

    @Test
    public void testCloseResultSet() throws SQLException {
        assertDoesNotThrow(() -> JdbcUtil.close(mockResultSet));
        verify(mockResultSet).close();
    }

    @Test
    public void testCloseResultSetWithStatement() throws SQLException {
        when(mockResultSet.getStatement()).thenReturn(mockStatement);

        assertDoesNotThrow(() -> JdbcUtil.close(mockResultSet, true));

        verify(mockResultSet).close();
        verify(mockStatement).close();
    }

    @Test
    public void testCloseResultSetWithStatementAndConnection() throws SQLException {
        when(mockResultSet.getStatement()).thenReturn(mockStatement);
        when(mockStatement.getConnection()).thenReturn(mockConnection);

        assertDoesNotThrow(() -> JdbcUtil.close(mockResultSet, true, true));

        verify(mockResultSet).close();
        verify(mockStatement).close();
        verify(mockConnection).close();
    }

    @Test
    public void testCloseStatement() throws SQLException {
        assertDoesNotThrow(() -> JdbcUtil.close(mockStatement));
        verify(mockStatement).close();
    }

    @Test
    public void testCloseConnection() throws SQLException {
        assertDoesNotThrow(() -> JdbcUtil.close(mockConnection));
        verify(mockConnection).close();
    }

    @Test
    public void testCloseResultSetAndStatement() throws SQLException {
        assertDoesNotThrow(() -> JdbcUtil.close(mockResultSet, mockStatement));
        verify(mockResultSet).close();
        verify(mockStatement).close();
    }

    @Test
    public void testCloseStatementAndConnection() throws SQLException {
        assertDoesNotThrow(() -> JdbcUtil.close(mockStatement, mockConnection));
        verify(mockStatement).close();
        verify(mockConnection).close();
    }

    @Test
    public void testCloseAll() throws SQLException {
        assertDoesNotThrow(() -> JdbcUtil.close(mockResultSet, mockStatement, mockConnection));
        verify(mockResultSet).close();
        verify(mockStatement).close();
        verify(mockConnection).close();
    }

    @Test
    public void testCloseQuietly() throws SQLException {
        doThrow(new SQLException("simulated")).when(mockResultSet).close();
        // closeQuietly must swallow the SQLException instead of propagating it
        assertDoesNotThrow(() -> JdbcUtil.closeQuietly(mockResultSet));
        verify(mockResultSet).close();
    }

    @Test
    public void testCloseQuietlyWithStatement() throws SQLException {
        when(mockResultSet.getStatement()).thenReturn(mockStatement);
        // closeQuietly with closeStatement=true must close both resources without throwing
        assertDoesNotThrow(() -> JdbcUtil.closeQuietly(mockResultSet, true));
        verify(mockResultSet).close();
        verify(mockStatement).close();
    }

    @Test
    public void testCloseQuietlyAll() throws SQLException {
        doThrow(new SQLException("rs")).when(mockResultSet).close();
        doThrow(new SQLException("stmt")).when(mockStatement).close();
        doThrow(new SQLException("conn")).when(mockConnection).close();

        // All three SQLExceptions must be swallowed
        assertDoesNotThrow(() -> JdbcUtil.closeQuietly(mockResultSet, mockStatement, mockConnection));
        // Verify close was attempted on every resource despite earlier failures
        verify(mockResultSet).close();
        verify(mockStatement).close();
        verify(mockConnection).close();
    }

    @Test
    public void testSkipInt() throws SQLException {
        when(mockResultSet.getRow()).thenReturn(0, 2);
        when(mockResultSet.next()).thenReturn(true, true, false);

        int skipped = JdbcUtil.skip(mockResultSet, 2);
        assertEquals(2, skipped);
    }

    @Test
    public void testSkipLong() throws SQLException {
        when(mockResultSet.getRow()).thenReturn(0, 1);
        when(mockResultSet.next()).thenReturn(true, false);

        int skipped = JdbcUtil.skip(mockResultSet, 1L);
        assertEquals(1, skipped);
    }

    @Test
    public void testGetColumnCount() throws SQLException {
        when(mockResultSetMetaData.getColumnCount()).thenReturn(5);

        int count = JdbcUtil.getColumnCount(mockResultSet);
        assertEquals(5, count);
    }

    @Test
    public void testGetColumnNameList() throws SQLException {
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);
        when(mockPreparedStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.getMetaData()).thenReturn(mockResultSetMetaData);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(3);
        when(mockResultSetMetaData.getColumnName(1)).thenReturn("id");
        when(mockResultSetMetaData.getColumnName(2)).thenReturn("name");
        when(mockResultSetMetaData.getColumnName(3)).thenReturn("age");

        List<String> columns = JdbcUtil.getColumnNameList(mockConnection, "users");

        assertEquals(3, columns.size());
        assertEquals("id", columns.get(0));
        assertEquals("name", columns.get(1));
        assertEquals("age", columns.get(2));
    }

    @Test
    public void testGetColumnLabelList() throws SQLException {
        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("user_id");
        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("user_name");

        List<String> labels = JdbcUtil.getColumnLabelList(mockResultSet);

        assertEquals(2, labels.size());
        assertEquals("user_id", labels.get(0));
        assertEquals("user_name", labels.get(1));
    }

    @Test
    public void testGetColumnLabel() throws SQLException {
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("label");
        when(mockResultSetMetaData.getColumnName(1)).thenReturn("name");

        String label = JdbcUtil.getColumnLabel(mockResultSetMetaData, 1);
        assertEquals("label", label);

        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("");
        when(mockResultSetMetaData.getColumnName(2)).thenReturn("name");
        String nameAsLabel = JdbcUtil.getColumnLabel(mockResultSetMetaData, 2);
        assertEquals("name", nameAsLabel);
    }

    @Test
    public void testGetColumnIndexByResultSet() throws SQLException {
        when(mockResultSetMetaData.getColumnCount()).thenReturn(3);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("name");
        when(mockResultSetMetaData.getColumnLabel(3)).thenReturn("age");

        int index = JdbcUtil.getColumnIndex(mockResultSet, "name");
        assertEquals(2, index);

        int notFound = JdbcUtil.getColumnIndex(mockResultSet, "unknown");
        assertEquals(-1, notFound);
    }

    @Test
    public void testGetColumnIndexByMetaData() throws SQLException {
        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("name");

        int index = JdbcUtil.getColumnIndex(mockResultSetMetaData, "name");
        assertEquals(2, index);
    }

    @Test
    public void testGetColumnValue() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn("test");
        Object value = JdbcUtil.getColumnValue(mockResultSet, 1);
        assertEquals("test", value);

        when(mockResultSet.getObject(2)).thenReturn(123);
        Object numValue = JdbcUtil.getColumnValue(mockResultSet, 2);
        assertEquals(123, numValue);
    }

    @Test
    public void testGetColumnValueWithBlob() throws SQLException {
        byte[] data = "blob data".getBytes();
        when(mockBlob.length()).thenReturn((long) data.length);
        when(mockBlob.getBytes(1, data.length)).thenReturn(data);
        when(mockResultSet.getObject(1)).thenReturn(mockBlob);

        Object value = JdbcUtil.getColumnValue(mockResultSet, 1);
        assertArrayEquals(data, (byte[]) value);
    }

    @Test
    public void testGetColumnValueWithClob() throws SQLException {
        String data = "clob data";
        when(mockClob.length()).thenReturn((long) data.length());
        when(mockClob.getSubString(1, data.length())).thenReturn(data);
        when(mockResultSet.getObject(1)).thenReturn(mockClob);

        Object value = JdbcUtil.getColumnValue(mockResultSet, 1);
        assertEquals(data, value);
    }

    @Test
    public void testGetColumnValueWithTargetClass() throws SQLException {
        when(mockResultSet.getString(1)).thenReturn("test");
        String value = JdbcUtil.getColumnValue(mockResultSet, 1, String.class);
        assertEquals("test", value);

        when(mockResultSet.getInt(2)).thenReturn(123);
        when(mockResultSet.getObject(2)).thenReturn(123);
        Integer intValue = JdbcUtil.getColumnValue(mockResultSet, 2, Integer.class);
        assertEquals(123, intValue);
    }

    @Test
    public void testGetAllColumnValues() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn("val1", "val2");

        List<String> values = JdbcUtil.getAllColumnValues(mockResultSet, 1);

        assertEquals(2, values.size());
        assertEquals("val1", values.get(0));
        assertEquals("val2", values.get(1));
    }

    @Test
    public void testGetAllColumnValuesByName() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn("val1", "val2");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("name");

        List<String> values = JdbcUtil.getAllColumnValues(mockResultSet, "name");

        assertEquals(2, values.size());
    }

    @Test
    public void testGetColumn2FieldNameMap() {
        // This would require a test entity class
        class TestEntity {
            private Long id;
            private String name;

            public Long getId() {
                return id;
            }

            public void setId(Long id) {
                this.id = id;
            }

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }
        }

        ImmutableMap<String, String> map = JdbcUtil.getColumn2FieldNameMap(TestEntity.class);
        assertNotNull(map);
        // The map is keyed by non-standard column names that differ from Java field names;
        // for a simple entity whose column names match field names, it may be empty.
        assertTrue(map instanceof ImmutableMap, "should return an ImmutableMap");

        // Calling again should return the same cached instance (identity check)
        ImmutableMap<String, String> map2 = JdbcUtil.getColumn2FieldNameMap(TestEntity.class);
        assertTrue(map == map2, "repeated calls should return the same cached ImmutableMap instance");
    }

    @Test
    public void testPrepareQuery() throws SQLException {
        String sql = "SELECT * FROM users WHERE id = ?";

        PreparedQuery query = JdbcUtil.prepareQuery(mockDataSource, sql);
        assertNotNull(query);
        assertTrue(query instanceof PreparedQuery, "should return a PreparedQuery instance");
        // A connection should have been obtained from the data source
        verify(mockDataSource).getConnection();
        verify(mockConnection).prepareStatement(sql);
        query.close();
    }

    @Test
    public void testPrepareQueryWithAutoGeneratedKeys() throws SQLException {
        String sql = "INSERT INTO users (name) VALUES (?)";
        when(mockConnection.prepareStatement(anyString(), anyInt())).thenReturn(mockPreparedStatement);

        PreparedQuery query = JdbcUtil.prepareQuery(mockDataSource, sql, true);
        assertNotNull(query);
        assertTrue(query instanceof PreparedQuery, "should return a PreparedQuery instance");
        // Verify auto-generated-keys flag was forwarded to the connection
        verify(mockConnection).prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
        query.close();
    }

    @Test
    public void testPrepareQueryWithReturnColumnIndexes() throws SQLException {
        String sql = "INSERT INTO users (name) VALUES (?)";
        int[] columnIndexes = { 1 };
        when(mockConnection.prepareStatement(sql, columnIndexes)).thenReturn(mockPreparedStatement);

        PreparedQuery query = JdbcUtil.prepareQuery(mockDataSource, sql, columnIndexes);
        assertNotNull(query);
        assertTrue(query instanceof PreparedQuery, "should return a PreparedQuery instance");
        // Verify the column-index overload was used on the connection
        verify(mockConnection).prepareStatement(sql, columnIndexes);
        query.close();
    }

    @Test
    public void testPrepareQueryWithReturnColumnNames() throws SQLException {
        String sql = "INSERT INTO users (name) VALUES (?)";
        String[] columnNames = { "id" };
        when(mockConnection.prepareStatement(sql, columnNames)).thenReturn(mockPreparedStatement);

        PreparedQuery query = JdbcUtil.prepareQuery(mockDataSource, sql, columnNames);
        assertNotNull(query);
        assertTrue(query instanceof PreparedQuery, "should return a PreparedQuery instance");
        // Verify the column-name overload was used on the connection
        verify(mockConnection).prepareStatement(sql, columnNames);
        query.close();
    }

    @Test
    public void testPrepareNamedQuery() throws SQLException {
        String sql = "SELECT * FROM users WHERE name = :name AND age > :age";

        NamedQuery query = JdbcUtil.prepareNamedQuery(mockDataSource, sql);
        assertNotNull(query);
        assertTrue(query instanceof NamedQuery, "should return a NamedQuery instance");
        // A connection must have been acquired to prepare the statement
        verify(mockDataSource).getConnection();
        query.close();
    }

    @Test
    public void testPrepareCallableQuery() throws SQLException {
        String sql = "{call getUserInfo(?, ?)}";

        CallableQuery query = JdbcUtil.prepareCallableQuery(mockDataSource, sql);
        assertNotNull(query);
        assertTrue(query instanceof CallableQuery, "should return a CallableQuery instance");
        // Verify prepareCall was invoked on the connection
        verify(mockConnection).prepareCall(sql);
        query.close();
    }

    @Test
    public void testExecuteQuery() throws SQLException {
        String sql = "SELECT * FROM users";
        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("name");
        when(mockResultSet.next()).thenReturn(false);

        Dataset result = JdbcUtil.executeQuery(mockDataSource, sql);
        assertNotNull(result);
        // No rows were returned, so the dataset should be empty
        assertEquals(0, result.size(), "dataset should have zero rows when ResultSet is empty");
    }

    @Test
    public void testExecuteUpdate() throws SQLException {
        String sql = "UPDATE users SET name = ? WHERE id = ?";
        when(mockPreparedStatement.executeUpdate()).thenReturn(1);

        int affected = JdbcUtil.executeUpdate(mockDataSource, sql, "John", 1L);
        assertEquals(1, affected);
    }

    @Test
    public void testExecuteBatchUpdate() throws SQLException {
        String sql = "INSERT INTO users (name) VALUES (?)";
        List<Object[]> params = Arrays.asList(new Object[] { "John" }, new Object[] { "Jane" });
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1, 1 });

        int total = JdbcUtil.executeBatchUpdate(mockDataSource, sql, params);
        assertEquals(2, total);
    }

    @Test
    public void testExecuteLargeBatchUpdate() throws SQLException {
        String sql = "INSERT INTO users (name) VALUES (?)";
        List<Object[]> params = Arrays.asList(new Object[] { "John" }, new Object[] { "Jane" });
        when(mockPreparedStatement.executeLargeBatch()).thenReturn(new long[] { 1L, 1L });

        long total = JdbcUtil.executeLargeBatchUpdate(mockDataSource, sql, params);
        assertEquals(2L, total);
    }

    @Test
    public void testExecute() throws SQLException {
        String sql = "CREATE TABLE test (id INT)";
        when(mockPreparedStatement.execute()).thenReturn(false);

        boolean hasResultSet = JdbcUtil.execute(mockDataSource, sql);
        assertFalse(hasResultSet);
    }

    @Test
    public void testExtractData() throws SQLException {
        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("name");
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn(1L);
        when(mockResultSet.getObject(2)).thenReturn("John");

        Dataset data = JdbcUtil.extractData(mockResultSet);
        assertNotNull(data);
        assertEquals(1, data.size());
    }

    @Test
    public void testExtractDataWithOffsetAndCount() throws SQLException {
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(mockResultSet.next()).thenReturn(true, true, true, false);
        when(mockResultSet.getObject(1)).thenReturn(1L, 2L, 3L);

        Dataset data = JdbcUtil.extractData(mockResultSet, 1, 2);
        assertNotNull(data);
        assertEquals(2, data.size());
    }

    @Test
    public void testExtractDataWithFilter() throws SQLException {
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn(1L, 2L);
        when(mockResultSet.getLong(1)).thenReturn(1L, 2L);

        RowFilter filter = rs -> {
            long id = rs.getLong(1);
            return id > 1;
        };

        Dataset data = JdbcUtil.extractData(mockResultSet, filter);
        assertNotNull(data);
        // Only the row with id > 1 (i.e. id=2) should pass the filter
        assertEquals(1, data.size(), "filter should keep only the row where id > 1");
    }

    @Test
    public void testStream() throws SQLException {
        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn(1L, 2L);
        when(mockResultSet.getObject(2)).thenReturn("John", "Jane");

        Stream<Object[]> stream = JdbcUtil.stream(mockResultSet);
        List<Object[]> list = stream.toList();

        assertEquals(2, list.size());
        assertEquals(1L, list.get(0)[0]);
        assertEquals("John", list.get(0)[1]);
    }

    @Test
    public void testStreamWithTargetClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getString(1)).thenReturn("test");

        Stream<String[]> stream = JdbcUtil.stream(mockResultSet, String[].class);
        List<String[]> list = stream.toList();

        assertEquals(1, list.size());
    }

    @Test
    public void testStreamWithRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getLong(1)).thenReturn(1L, 2L);

        RowMapper<Long> mapper = rs -> rs.getLong(1);
        Stream<Long> stream = JdbcUtil.stream(mockResultSet, mapper);
        List<Long> list = stream.toList();

        assertEquals(2, list.size());
        assertEquals(1L, list.get(0));
        assertEquals(2L, list.get(1));
    }

    @Test
    public void testStreamWithColumnIndex() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn("val1", "val2");
        when(mockResultSet.getStatement()).thenReturn(mockStatement);
        when(mockStatement.getConnection()).thenReturn(mockConnection);
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("H2");

        Stream<String> stream = JdbcUtil.stream(mockResultSet, 1);
        List<String> list = stream.toList();

        assertEquals(2, list.size());
        assertEquals("val1", list.get(0));
        assertEquals("val2", list.get(1));
    }

    @Test
    public void testStreamWithColumnName() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn("value");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("name");

        Stream<String> stream = JdbcUtil.stream(mockResultSet, "name");
        List<String> list = stream.toList();

        assertEquals(1, list.size());
    }

    @Test
    public void teststreamAllResultSets() throws SQLException {
        when(mockStatement.getResultSet()).thenReturn(mockResultSet);
        when(mockStatement.getMoreResults()).thenReturn(false);
        when(mockStatement.getUpdateCount()).thenReturn(-1);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("col");
        when(mockResultSet.next()).thenReturn(false);

        Stream<Dataset> stream = JdbcUtil.streamAllResultSets(mockStatement);
        List<Dataset> list = stream.toList();

        assertNotNull(list);
        // The statement has one result set and getMoreResults returns false, so exactly one Dataset
        assertEquals(1, list.size(), "should yield one Dataset for a single result set");
        // The single result set had no rows
        assertEquals(0, list.get(0).size(), "the single Dataset should be empty");
    }

    @Test
    public void testQueryByPage() throws SQLException {
        String query = "SELECT * FROM users WHERE id > ? ORDER BY id LIMIT 10";
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn(1L);

        Stream<Dataset> pages = JdbcUtil.queryByPage(mockDataSource, query, 10, (pq, prev) -> {
            pq.setLong(1, 0);
        });

        Dataset firstPage = pages.first().orElse(null);
        assertNotNull(firstPage);
        // The mock returned one row, so the first page should contain exactly one row
        assertEquals(1, firstPage.size(), "first page should contain the single returned row");
    }

    @Test
    public void testDoesTableExist() throws SQLException {
        when(mockPreparedStatement.execute()).thenReturn(true);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSet.next()).thenReturn(false);

        boolean exists = JdbcUtil.doesTableExist(mockConnection, "users");
        assertTrue(exists);

        when(mockPreparedStatement.execute()).thenThrow(new SQLException("Table not found", "42S02"));
        boolean notExists = JdbcUtil.doesTableExist(mockConnection, "nonexistent");
        assertFalse(notExists);
    }

    //    @Test
    //    public void testCreateTableIfNotExists() throws SQLException {
    //        String schema = "CREATE TABLE users (id INT PRIMARY KEY)";
    //
    //        // Table doesn't exist
    //        when(mockPreparedStatement.execute()).thenThrow(new SQLException("Table not found", "42S02"));
    //        when(mockPreparedStatement.execute()).thenReturn(false);
    //
    //        boolean created = JdbcUtil.createTableIfNotExists(mockConnection, "users", schema);
    //        assertTrue(created);
    //    }

    @Test
    public void testDropTableIfExists() throws SQLException {
        when(mockPreparedStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSet.next()).thenReturn(false);
        when(mockPreparedStatement.execute()).thenReturn(false);

        boolean dropped = JdbcUtil.dropTableIfExists(mockConnection, "users");
        assertTrue(dropped);
    }

    @Test
    public void testDropTableIfExists_QuotesIdentifier() throws SQLException {
        final ResultSet tableRs = mock(ResultSet.class);

        when(mockDatabaseMetaData.getIdentifierQuoteString()).thenReturn("\"");
        when(mockDatabaseMetaData.getTables(null, null, "order", null)).thenReturn(tableRs);
        when(tableRs.next()).thenReturn(true);
        when(mockPreparedStatement.execute()).thenReturn(false);

        boolean dropped = JdbcUtil.dropTableIfExists(mockConnection, "order");
        assertTrue(dropped);
        verify(mockConnection).prepareStatement("DROP TABLE \"order\"");
    }

    @Test
    public void testToQualifiedSqlIdentifier_QuotesAndEscapes() throws SQLException {
        when(mockDatabaseMetaData.getIdentifierQuoteString()).thenReturn("\"");

        assertEquals("\"schema\".\"my\"\"table\"", JdbcUtil.toQualifiedSqlIdentifier(mockConnection, "schema.my\"table", "tableName"));
    }

    @Test
    public void testToQualifiedSqlIdentifier_RejectsUnsafeWhenNoQuoteSupport() throws SQLException {
        when(mockDatabaseMetaData.getIdentifierQuoteString()).thenReturn(" ");

        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.toQualifiedSqlIdentifier(mockConnection, "users;drop", "tableName"));
    }

    //    @Test
    //    public void testGetDBSequenceWithConfig() {
    //        DBSequence seq = JdbcUtil.getDBSequence(mockDataSource, "seq_table", "seq_name", 1000L, 500);
    //        assertNotNull(seq);
    //    }

    @Test
    public void testGetDBLock() {
        DBLock lock = JdbcUtil.getDBLock(mockDataSource, "lock_table");
        assertNotNull(lock);
        assertTrue(lock instanceof DBLock, "should return a DBLock instance");
        // A second call with a different table name should produce a distinct instance
        DBLock otherLock = JdbcUtil.getDBLock(mockDataSource, "other_lock_table");
        assertNotNull(otherLock);
        assertFalse(lock == otherLock, "different table names should yield different DBLock instances");
    }

    @Test
    public void testGetOutParameters() throws SQLException {
        List<OutParam> outParams = Arrays.asList(OutParam.of(1, Types.VARCHAR), OutParam.of(2, Types.INTEGER));

        when(mockCallableStatement.getString(1)).thenReturn("result");
        when(mockCallableStatement.getInt(2)).thenReturn(42);

        OutParamResult result = JdbcUtil.getOutParameters(mockCallableStatement, outParams);

        assertNotNull(result);
        assertEquals("result", result.getOutParamValue(1));
        assertEquals(42, (Integer) result.getOutParamValue(2));
    }

    @Test
    public void testGetNamedParameters() {
        String sql = "SELECT * FROM users WHERE name = :name AND age > :age";
        List<String> params = JdbcUtil.namedParameters(sql);

        assertEquals(2, params.size());
        assertTrue(params.contains("name"));
        assertTrue(params.contains("age"));
    }

    @Test
    public void testParseSql() {
        String sql = "SELECT * FROM users WHERE id = :id";
        ParsedSql parsed = JdbcUtil.parseSql(sql);

        assertNotNull(parsed);
        assertEquals(1, parsed.parameterCount());
        assertTrue(parsed.namedParameters().contains("id"));
    }

    @Test
    public void testGetInsertPropNames() {
        class TestEntity {
            private Long id;
            private String name;
            @ReadOnly
            private String readOnlyField;

            public Long getId() {
                return id;
            }

            public void setId(Long id) {
                this.id = id;
            }

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getReadOnlyField() {
                return readOnlyField;
            }

            public void setReadOnlyField(String readOnlyField) {
                this.readOnlyField = readOnlyField;
            }
        }

        TestEntity entity = new TestEntity();
        Collection<String> propNames = JdbcUtil.getInsertPropNames(entity);

        assertNotNull(propNames);
        assertFalse(propNames.contains("readOnlyField"));
    }

    @Test
    public void testGetSelectPropNames() {
        class TestEntity {
            private Long id;
            private String name;
            @Transient
            private String transientField;

            public Long getId() {
                return id;
            }

            public void setId(Long id) {
                this.id = id;
            }

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getTransientField() {
                return transientField;
            }

            public void setTransientField(String transientField) {
                this.transientField = transientField;
            }
        }

        Collection<String> propNames = JdbcUtil.getSelectPropNames(TestEntity.class);

        assertNotNull(propNames);
        assertTrue(propNames.contains("id"));
        assertTrue(propNames.contains("name"));
        assertFalse(propNames.contains("transientField"));
    }

    //    @Test
    //    public void testGetUpdatePropNames() {
    //        class TestEntity {
    //            @Id
    //            private Long id;
    //            private String name;
    //            @NonUpdatable
    //            private String createdDate;
    //
    //            public Long getId() { return id; }
    //            public void setId(Long id) { this.id = id; }
    //            public String getName() { return name; }
    //            public void setName(String name) { this.name = name; }
    //            public String getCreatedDate() { return createdDate; }
    //            public void setCreatedDate(String createdDate) { this.createdDate = createdDate; }
    //        }
    //
    //        Collection<String> propNames = JdbcUtil.getUpdatePropNames(TestEntity.class);
    //
    //        assertNotNull(propNames);
    //        assertTrue(propNames.contains("name"));
    //        assertFalse(propNames.contains("id"));
    //        assertFalse(propNames.contains("createdDate"));
    //    }

    @Test
    public void testBlob2String() throws SQLException {
        String data = "test blob data";
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        when(mockBlob.length()).thenReturn((long) bytes.length);
        when(mockBlob.getBytes(1, bytes.length)).thenReturn(bytes);

        String result = JdbcUtil.blob2String(mockBlob);
        assertEquals(data, result);
        verify(mockBlob).free();
    }

    @Test
    public void testBlob2StringWithCharset() throws SQLException {
        String data = "test blob data";
        byte[] bytes = data.getBytes(StandardCharsets.ISO_8859_1);
        when(mockBlob.length()).thenReturn((long) bytes.length);
        when(mockBlob.getBytes(1, bytes.length)).thenReturn(bytes);

        String result = JdbcUtil.blob2String(mockBlob, StandardCharsets.ISO_8859_1);
        assertEquals(data, result);
        verify(mockBlob).free();
    }

    @Test
    public void testWriteBlobToFile() throws SQLException, IOException {
        File tempFile = File.createTempFile("test", ".tmp");
        tempFile.deleteOnExit();

        byte[] data = "test blob data".getBytes();
        when(mockBlob.getBinaryStream()).thenReturn(new java.io.ByteArrayInputStream(data));

        long written = JdbcUtil.writeBlobToFile(mockBlob, tempFile);
        assertTrue(written > 0);
        verify(mockBlob).free();

        tempFile.delete();
    }

    @Test
    public void testClob2String() throws SQLException {
        String data = "test clob data";
        when(mockClob.length()).thenReturn((long) data.length());
        when(mockClob.getSubString(1, data.length())).thenReturn(data);

        String result = JdbcUtil.clob2String(mockClob);
        assertEquals(data, result);
        verify(mockClob).free();
    }

    @Test
    public void testWriteClobToFile() throws SQLException, IOException {
        File tempFile = File.createTempFile("test", ".txt");
        tempFile.deleteOnExit();

        String data = "test clob data";
        when(mockClob.getCharacterStream()).thenReturn(new java.io.StringReader(data));

        long written = JdbcUtil.writeClobToFile(mockClob, tempFile);
        assertTrue(written > 0);
        verify(mockClob).free();

        tempFile.delete();
    }

    @Test
    public void testIsNullOrDefault() {
        assertTrue(JdbcUtil.isNullOrDefault(null));
        assertTrue(JdbcUtil.isNullOrDefault(0));
        assertTrue(JdbcUtil.isNullOrDefault(0L));
        assertTrue(JdbcUtil.isNullOrDefault(0D));
        assertTrue(JdbcUtil.isNullOrDefault(false));

        assertFalse(JdbcUtil.isNullOrDefault(1));
        assertFalse(JdbcUtil.isNullOrDefault(""));
        assertFalse(JdbcUtil.isNullOrDefault("test"));
        assertFalse(JdbcUtil.isNullOrDefault(true));
    }

    @Test
    public void testTurnOffSqlLogGlobally() {
        JdbcUtil.turnOffSqlLogGlobally();
        assertFalse(JdbcUtil.isSqlLogAllowed);
    }

    @Test
    public void testTurnOffSqlPerfLogGlobally() {
        JdbcUtil.turnOffSqlPerfLogGlobally();
        assertFalse(JdbcUtil.isSqlPerfLogAllowed);
    }

    @Test
    public void testTurnOffDaoMethodPerfLogGlobally() {
        JdbcUtil.turnOffDaoMethodPerfLogGlobally();
        assertFalse(JdbcUtil.isDaoMethodPerfLogAllowed);
    }

    @Test
    public void testEnableSqlLog() {
        JdbcUtil.enableSqlLog();
        assertTrue(JdbcUtil.isSqlLogEnabled());

        JdbcUtil.enableSqlLog(2048);
        assertTrue(JdbcUtil.isSqlLogEnabled());
    }

    @Test
    public void testDisableSqlLog() {
        JdbcUtil.enableSqlLog();
        assertTrue(JdbcUtil.isSqlLogEnabled());

        JdbcUtil.disableSqlLog();
        assertFalse(JdbcUtil.isSqlLogEnabled());
    }

    @Test
    public void testIsSqlLogEnabled() {
        JdbcUtil.disableSqlLog();
        assertFalse(JdbcUtil.isSqlLogEnabled());

        JdbcUtil.enableSqlLog();
        assertTrue(JdbcUtil.isSqlLogEnabled());
    }

    @Test
    public void testGetSqlExtractor() {
        Throwables.Function<Statement, String, SQLException> extractor = JdbcUtil.getSqlExtractor();
        assertNotNull(extractor);
        // The default extractor should be the built-in DEFAULT_SQL_EXTRACTOR
        assertEquals(JdbcUtil.DEFAULT_SQL_EXTRACTOR, extractor, "initial extractor should be the default");
    }

    @Test
    public void testSetSqlExtractor() throws SQLException {
        Throwables.Function<Statement, String, SQLException> customExtractor = stmt -> "custom sql";
        JdbcUtil.setSqlExtractor(customExtractor);

        assertEquals(customExtractor, JdbcUtil.getSqlExtractor());

        // Reset to default
        JdbcUtil.setSqlExtractor(JdbcUtil.DEFAULT_SQL_EXTRACTOR);
    }

    @Test
    public void testGetSqlLogHandler() {
        TriConsumer<String, Long, Long> handler = JdbcUtil.getSqlLogHandler();
        // Initially null
        assertNull(handler);
    }

    @Test
    public void testSetSqlLogHandler() {
        TriConsumer<String, Long, Long> handler = (sql, start, end) -> {
            // Custom logging
        };

        JdbcUtil.setSqlLogHandler(handler);
        assertEquals(handler, JdbcUtil.getSqlLogHandler());

        // Reset
        JdbcUtil.setSqlLogHandler(null);
    }

    @Test
    public void testSetMinExecutionTimeForSqlPerfLog() {
        JdbcUtil.setMinExecutionTimeForSqlPerfLog(500);
        assertEquals(500, JdbcUtil.getMinExecutionTimeForSqlPerfLog());

        JdbcUtil.setMinExecutionTimeForSqlPerfLog(1000, 2048);
        assertEquals(1000, JdbcUtil.getMinExecutionTimeForSqlPerfLog());
    }

    @Test
    public void testGetMinExecutionTimeForSqlPerfLog() {
        long defaultTime = JdbcUtil.getMinExecutionTimeForSqlPerfLog();
        assertTrue(defaultTime >= 0);
    }

    @Test
    public void testRunWithSqlLogDisabled() {
        JdbcUtil.enableSqlLog();

        JdbcUtil.runWithSqlLogDisabled(() -> {
            assertFalse(JdbcUtil.isSqlLogEnabled());
        });

        assertTrue(JdbcUtil.isSqlLogEnabled());
    }

    @Test
    public void testCallWithSqlLogDisabled() {
        JdbcUtil.enableSqlLog();

        String result = JdbcUtil.callWithSqlLogDisabled(() -> {
            assertFalse(JdbcUtil.isSqlLogEnabled());
            return "test";
        });

        assertEquals("test", result);
        assertTrue(JdbcUtil.isSqlLogEnabled());
    }

    @Test
    public void testIsInTransaction() throws SQLException {
        assertFalse(JdbcUtil.isInTransaction(mockDataSource));

        SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource);
        assertTrue(JdbcUtil.isInTransaction(mockDataSource));
        tran.rollbackIfNotCommitted();
    }

    @Test
    public void testBeginTransaction() throws SQLException {
        SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource);
        assertNotNull(tran);
        assertTrue(tran.isActive(), "newly begun transaction should be active");
        // The data source should now be marked as in-transaction
        assertTrue(JdbcUtil.isInTransaction(mockDataSource));
        tran.rollbackIfNotCommitted();
    }

    @Test
    public void testBeginTransactionWithIsolation() throws SQLException {
        SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource, IsolationLevel.READ_COMMITTED);
        assertNotNull(tran);
        assertTrue(tran.isActive(), "newly begun transaction should be active");
        assertEquals(IsolationLevel.READ_COMMITTED, tran.isolationLevel(), "isolation level should match the requested level");
        tran.rollbackIfNotCommitted();
    }

    @Test
    public void testBeginTransactionWithIsolationAndUpdateOnly() throws SQLException {
        SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource, IsolationLevel.READ_COMMITTED, true);
        assertNotNull(tran);
        assertTrue(tran.isActive(), "newly begun transaction should be active");
        assertEquals(IsolationLevel.READ_COMMITTED, tran.isolationLevel(), "isolation level should match the requested level");
        // The transaction's connection should be the one from the mock data source
        assertEquals(mockConnection, tran.connection(), "transaction should use the connection from the data source");
        tran.rollbackIfNotCommitted();
    }

    @Test
    public void testCallInTransaction() throws SQLException {
        String result = JdbcUtil.callInTransaction(mockDataSource, () -> "test");
        assertEquals("test", result);
    }

    @Test
    public void testCallInTransactionWithConnection() throws SQLException {
        String result = JdbcUtil.callInTransaction(mockDataSource, conn -> {
            assertNotNull(conn);
            return "test";
        });
        assertEquals("test", result);
    }

    @Test
    public void testRunInTransaction() throws SQLException {
        final boolean[] executed = { false };
        JdbcUtil.runInTransaction(mockDataSource, () -> {
            executed[0] = true;
        });
        assertTrue(executed[0]);
    }

    @Test
    public void testRunInTransactionWithConnection() throws SQLException {
        final boolean[] executed = { false };
        JdbcUtil.runInTransaction(mockDataSource, conn -> {
            assertNotNull(conn);
            executed[0] = true;
        });
        assertTrue(executed[0]);
    }

    @Test
    public void testCallNotInStartedTransaction() throws SQLException {
        String result = JdbcUtil.callNotInStartedTransaction(mockDataSource, () -> "test");
        assertEquals("test", result);
    }

    @Test
    public void testCallNotInStartedTransactionWithDataSource() throws SQLException {
        String result = JdbcUtil.callNotInStartedTransaction(mockDataSource, ds -> {
            assertNotNull(ds);
            return "test";
        });
        assertEquals("test", result);
    }

    @Test
    public void testRunNotInStartedTransaction() throws SQLException {
        final boolean[] executed = { false };
        JdbcUtil.runNotInStartedTransaction(mockDataSource, () -> {
            executed[0] = true;
        });
        assertTrue(executed[0]);
    }

    @Test
    public void testRunNotInStartedTransactionWithDataSource() throws SQLException {
        final boolean[] executed = { false };
        JdbcUtil.runNotInStartedTransaction(mockDataSource, ds -> {
            assertNotNull(ds);
            executed[0] = true;
        });
        assertTrue(executed[0]);
    }

    @Test
    public void testRunWithoutUsingSpringTransaction() {
        final boolean[] executed = { false };
        JdbcUtil.runWithoutUsingSpringTransaction(() -> {
            executed[0] = true;
        });
        assertTrue(executed[0]);
    }

    @Test
    public void testCallWithoutUsingSpringTransaction() {
        String result = JdbcUtil.callWithoutUsingSpringTransaction(() -> "test");
        assertEquals("test", result);
    }

    @Test
    public void testAsyncRun() throws Exception {
        final boolean[] executed = { false };
        ContinuableFuture<Void> future = JdbcUtil.asyncRun(() -> {
            executed[0] = true;
        });

        assertNotNull(future);
        Void result = future.get(1, TimeUnit.SECONDS);
        assertNull(result, "a Runnable-based future should resolve to null");
        assertTrue(executed[0], "the async action should have been executed");
    }

    @Test
    public void testAsyncRunMultiple() throws Exception {
        final boolean[] executed = { false, false };
        Tuple2<ContinuableFuture<Void>, ContinuableFuture<Void>> futures = JdbcUtil.asyncRun(() -> {
            executed[0] = true;
        }, () -> {
            executed[1] = true;
        });

        assertNotNull(futures._1);
        assertNotNull(futures._2);

        futures._1.get(1, TimeUnit.SECONDS);
        futures._2.get(1, TimeUnit.SECONDS);
        // Both async actions must have completed
        assertTrue(executed[0], "first async action should have been executed");
        assertTrue(executed[1], "second async action should have been executed");
    }

    @Test
    public void testAsyncRunTriple() throws Exception {
        final boolean[] executed = { false, false, false };
        Tuple3<ContinuableFuture<Void>, ContinuableFuture<Void>, ContinuableFuture<Void>> futures = JdbcUtil.asyncRun(() -> {
            executed[0] = true;
        }, () -> {
            executed[1] = true;
        }, () -> {
            executed[2] = true;
        });

        assertNotNull(futures._1);
        assertNotNull(futures._2);
        assertNotNull(futures._3);

        // Wait for all three to complete and verify execution
        futures._1.get(1, TimeUnit.SECONDS);
        futures._2.get(1, TimeUnit.SECONDS);
        futures._3.get(1, TimeUnit.SECONDS);
        assertTrue(executed[0], "first async action should have been executed");
        assertTrue(executed[1], "second async action should have been executed");
        assertTrue(executed[2], "third async action should have been executed");
    }

    @Test
    public void testAsyncRunWithParameter() throws Exception {
        ContinuableFuture<Void> future = JdbcUtil.asyncRun("test", str -> {
            assertEquals("test", str);
        });

        assertNotNull(future);
        future.get(1, TimeUnit.SECONDS);
    }

    @Test
    public void testAsyncRunWithTwoParameters() throws Exception {
        ContinuableFuture<Void> future = JdbcUtil.asyncRun("test", 123, (str, num) -> {
            assertEquals("test", str);
            assertEquals(123, num);
        });

        assertNotNull(future);
        future.get(1, TimeUnit.SECONDS);
    }

    @Test
    public void testAsyncRunWithThreeParameters() throws Exception {
        ContinuableFuture<Void> future = JdbcUtil.asyncRun("a", "b", "c", (p1, p2, p3) -> {
            assertEquals("a", p1);
            assertEquals("b", p2);
            assertEquals("c", p3);
        });

        assertNotNull(future);
        future.get(1, TimeUnit.SECONDS);
    }

    @Test
    public void testAsyncCall() throws Exception {
        ContinuableFuture<String> future = JdbcUtil.asyncCall(() -> "result");

        assertNotNull(future);
        assertEquals("result", future.get(1, TimeUnit.SECONDS));
    }

    @Test
    public void testAsyncCallMultiple() throws Exception {
        Tuple2<ContinuableFuture<String>, ContinuableFuture<Integer>> futures = JdbcUtil.asyncCall(() -> "test", () -> 123);

        assertEquals("test", futures._1.get(1, TimeUnit.SECONDS));
        assertEquals(123, futures._2.get(1, TimeUnit.SECONDS));
    }

    @Test
    public void testAsyncCallTriple() throws Exception {
        Tuple3<ContinuableFuture<String>, ContinuableFuture<Integer>, ContinuableFuture<Boolean>> futures = JdbcUtil.asyncCall(() -> "test", () -> 123,
                () -> true);

        assertEquals("test", futures._1.get(1, TimeUnit.SECONDS));
        assertEquals(123, futures._2.get(1, TimeUnit.SECONDS));
        assertTrue(futures._3.get(1, TimeUnit.SECONDS));
    }

    @Test
    public void testAsyncCallWithParameter() throws Exception {
        ContinuableFuture<String> future = JdbcUtil.asyncCall("input", str -> str + "!");

        assertEquals("input!", future.get(1, TimeUnit.SECONDS));
    }

    @Test
    public void testAsyncCallWithTwoParameters() throws Exception {
        ContinuableFuture<String> future = JdbcUtil.asyncCall("a", "b", (p1, p2) -> p1 + p2);

        assertEquals("ab", future.get(1, TimeUnit.SECONDS));
    }

    @Test
    public void testAsyncCallWithThreeParameters() throws Exception {
        ContinuableFuture<String> future = JdbcUtil.asyncCall("a", "b", "c", (p1, p2, p3) -> p1 + p2 + p3);

        assertEquals("abc", future.get(1, TimeUnit.SECONDS));
    }

    @Test
    public void testSetIdExtractorForDao() {
        interface TestDao extends CrudDao<Object, Long, SqlBuilder, TestDao> {
        }

        RowMapper<Long> extractor = rs -> rs.getLong(1);
        JdbcUtil.setIdExtractorForDao(TestDao.class, extractor);
        assertTrue(getIdExtractorPool().containsKey(TestDao.class));
        getIdExtractorPool().remove(TestDao.class);
    }

    @Test
    public void testSetIdExtractorForDaoWithBiRowMapper() {
        interface TestDao extends CrudDao<Object, Long, SqlBuilder, TestDao> {
        }

        BiRowMapper<Long> extractor = (rs, labels) -> rs.getLong(1);
        JdbcUtil.setIdExtractorForDao(TestDao.class, extractor);
        assertTrue(extractor == getIdExtractorPool().get(TestDao.class));
        getIdExtractorPool().remove(TestDao.class);
    }

    //    @Test
    //    public void testCreateDao() {
    //        interface TestDao extends Dao {}
    //
    //        TestDao dao = JdbcUtil.createDao(TestDao.class, mockDataSource);
    //        assertNotNull(dao);
    //    }
    //
    //    @Test
    //    public void testCreateDaoWithSqlMapper() {
    //        interface TestDao extends Dao {}
    //        SqlMapper sqlMapper = mock(SqlMapper.class);
    //
    //        TestDao dao = JdbcUtil.createDao(TestDao.class, mockDataSource, sqlMapper);
    //        assertNotNull(dao);
    //    }
    //
    //    @Test
    //    public void testCreateDaoWithExecutor() {
    //        interface TestDao extends Dao {}
    //        ExecutorService executor = Executors.newSingleThreadExecutor();
    //
    //        try {
    //            TestDao dao = JdbcUtil.createDao(TestDao.class, mockDataSource, executor);
    //            assertNotNull(dao);
    //        } finally {
    //            executor.shutdown();
    //        }
    //    }
    //
    //    @Test
    //    public void testCreateDaoWithTableName() {
    //        interface TestDao extends Dao {}
    //
    //        TestDao dao = JdbcUtil.createDao(TestDao.class, "custom_table", mockDataSource);
    //        assertNotNull(dao);
    //    }

    @Test
    public void testStartDaoCacheOnCurrentThread() {
        Jdbc.DaoCache cache = JdbcUtil.openDaoCacheOnCurrentThread();
        assertNotNull(cache);
        // The thread-local should now hold the cache
        assertNotNull(JdbcUtil.localThreadCache_TL.get(), "thread-local cache should be set after open");
        assertEquals(cache, JdbcUtil.localThreadCache_TL.get(), "thread-local cache should be the returned instance");

        JdbcUtil.closeDaoCacheOnCurrentThread();
        // After closing, the thread-local should be cleared
        assertNull(JdbcUtil.localThreadCache_TL.get(), "thread-local cache should be null after close");
    }

    @Test
    public void testStartDaoCacheOnCurrentThreadWithCache() {
        Jdbc.DaoCache cache = Jdbc.DaoCache.createByMap();
        Jdbc.DaoCache result = JdbcUtil.openDaoCacheOnCurrentThread(cache);

        assertEquals(cache, result);

        JdbcUtil.closeDaoCacheOnCurrentThread();
    }

    @Test
    public void testCloseDaoCacheOnCurrentThread() {
        JdbcUtil.openDaoCacheOnCurrentThread();
        JdbcUtil.closeDaoCacheOnCurrentThread();
        assertNull(JdbcUtil.localThreadCache_TL.get());
    }

    private void assertDataSourceCreation(final String expectedClassName, final Supplier<DataSource> supplier) {
        final DataSource dataSource = assertDoesNotThrow(supplier::get);
        assertNotNull(dataSource);
        assertEquals(expectedClassName, dataSource.getClass().getName());

        final Connection connection = assertDoesNotThrow(() -> dataSource.getConnection());

        try {
            assertFalse(connection.isClosed());
            assertEquals("H2", connection.getMetaData().getDatabaseProductName());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            JdbcUtil.close(connection);
            closeIfPossible(dataSource);
        }
    }

    private void assertConnectionCreation(final Supplier<Connection> supplier) {
        final Connection connection = assertDoesNotThrow(supplier::get);
        assertNotNull(connection);

        try {
            assertFalse(connection.isClosed());
            assertEquals("H2", connection.getMetaData().getDatabaseProductName());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            JdbcUtil.close(connection);
        }
    }

    private void closeIfPossible(final Object candidate) {
        if (candidate instanceof AutoCloseable) {
            final AutoCloseable closeable = (AutoCloseable) candidate;
            assertDoesNotThrow(() -> closeable.close());
        }
    }

    // Tests for public constants
    @Test
    public void testDefaultBatchSize() {
        assertEquals(200, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @Test
    public void testDefaultFetchSizeForBigResult() {
        assertEquals(1000, JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT);
    }

    @Test
    public void testDefaultFetchSizeForStream() {
        assertEquals(100, JdbcUtil.DEFAULT_FETCH_SIZE_FOR_STREAM);
    }

    @Test
    public void testDefaultCacheCapacity() {
        assertEquals(1000, JdbcUtil.DEFAULT_CACHE_CAPACITY);
    }

    @Test
    public void testDefaultCacheEvictDelay() {
        assertEquals(3 * 1000, JdbcUtil.DEFAULT_CACHE_EVICT_DELAY);
    }

    @Test
    public void testDefaultCacheLiveTime() {
        assertEquals(30 * 60 * 1000, JdbcUtil.DEFAULT_CACHE_LIVE_TIME);
    }

    @Test
    public void testDefaultMaxSqlLogLength() {
        assertEquals(1024, JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH);
    }

    @Test
    public void testDefaultMinExecutionTimeForSqlPerfLog() {
        assertEquals(1000L, JdbcUtil.DEFAULT_MIN_EXECUTION_TIME_FOR_SQL_PERF_LOG);
    }

    @Test
    public void testDefaultMinExecutionTimeForDaoMethodPerfLog() {
        assertEquals(3000L, JdbcUtil.DEFAULT_MIN_EXECUTION_TIME_FOR_DAO_METHOD_PERF_LOG);
    }

    @Test
    public void testDefaultCacheMaxIdleTime() {
        assertEquals(3 * 60 * 1000, JdbcUtil.DEFAULT_CACHE_MAX_IDLE_TIME);
    }

    @SuppressWarnings("unchecked")
    private Map<Class<? extends com.landawn.abacus.jdbc.dao.Dao>, BiRowMapper<?>> getIdExtractorPool() {
        try {
            final Field field = JdbcUtil.class.getDeclaredField("idExtractorPool");
            field.setAccessible(true);
            return (Map<Class<? extends com.landawn.abacus.jdbc.dao.Dao>, BiRowMapper<?>>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // Tests for DB version detection - MySQL variants
    @Test
    public void testGetDBProductInfo_MySQL55() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("5.5.62");
        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);
        assertEquals(DBVersion.MySQL_5_5, info.version());
    }

    @Test
    public void testGetDBProductInfo_MySQL56() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("5.6.51");
        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);
        assertEquals(DBVersion.MySQL_5_6, info.version());
    }

    @Test
    public void testGetDBProductInfo_MySQL57() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("5.7.41");
        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);
        assertEquals(DBVersion.MySQL_5_7, info.version());
    }

    @Test
    public void testGetDBProductInfo_MySQL58() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("5.8.0");
        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);
        assertEquals(DBVersion.MySQL_5_8, info.version());
    }

    @Test
    public void testGetDBProductInfo_MySQL59() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("5.9.0");
        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);
        assertEquals(DBVersion.MySQL_5_9, info.version());
    }

    @Test
    public void testGetDBProductInfo_MySQL6() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("6.0.0");
        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);
        assertEquals(DBVersion.MySQL_6, info.version());
    }

    @Test
    public void testGetDBProductInfo_MySQL7() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("7.0.0");
        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);
        assertEquals(DBVersion.MySQL_7, info.version());
    }

    @Test
    public void testGetDBProductInfo_MySQL9() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("9.0.0");
        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);
        assertEquals(DBVersion.MySQL_9, info.version());
    }

    @Test
    public void testGetDBProductInfo_MySQL10() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("10.0.0");
        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);
        assertEquals(DBVersion.MySQL_10, info.version());
    }

    @Test
    public void testGetDBProductInfo_MySQLOthers() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("4.0.0");
        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);
        assertEquals(DBVersion.MySQL_OTHERS, info.version());
    }

    // Tests for DB version detection - PostgreSQL variants
    @Test
    public void testGetDBProductInfo_PostgreSQL92() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("9.2.24");
        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);
        assertEquals(DBVersion.PostgreSQL_9_2, info.version());
    }

    @Test
    public void testGetDBProductInfo_PostgreSQL93() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("9.3.25");
        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);
        assertEquals(DBVersion.PostgreSQL_9_3, info.version());
    }

    @Test
    public void testGetDBProductInfo_PostgreSQL94() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("9.4.26");
        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);
        assertEquals(DBVersion.PostgreSQL_9_4, info.version());
    }

    @Test
    public void testGetDBProductInfo_PostgreSQL95() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("9.5.25");
        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);
        assertEquals(DBVersion.PostgreSQL_9_5, info.version());
    }

    @Test
    public void testGetDBProductInfo_PostgreSQL10() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("10.23");
        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);
        assertEquals(DBVersion.PostgreSQL_10, info.version());
    }

    @Test
    public void testGetDBProductInfo_PostgreSQL11() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("11.21");
        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);
        assertEquals(DBVersion.PostgreSQL_11, info.version());
    }

    @Test
    public void testGetDBProductInfo_PostgreSQLOthers() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("7.4.0");
        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);
        assertEquals(DBVersion.PostgreSQL_OTHERS, info.version());
    }

    // Tests for DB version detection - other vendors
    @Test
    public void testGetDBProductInfo_HSQLDB() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("HSQL Database Engine");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("2.7.1");
        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);
        assertEquals(DBVersion.HSQLDB, info.version());
    }

    @Test
    public void testGetDBProductInfo_Oracle() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("Oracle");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("19c");
        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);
        assertEquals(DBVersion.Oracle, info.version());
    }

    @Test
    public void testGetDBProductInfo_DB2() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("DB2/LINUXX8664");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("11.5");
        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);
        assertEquals(DBVersion.DB2, info.version());
    }

    @Test
    public void testGetDBProductInfo_SQLServer() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("Microsoft SQL SERVER");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("15.00");
        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);
        assertEquals(DBVersion.SQLServer, info.version());
    }

    @Test
    public void testGetDBProductInfo_Others() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("SomeUnknownDB");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("1.0");
        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);
        assertEquals(DBVersion.OTHERS, info.version());
    }

    // Test closeQuietly with statement+connection flag and exception getting statement
    @Test
    public void testCloseQuietlyWithStatementAndConnection_StatementSQLException() throws SQLException {
        doThrow(new SQLException("getStatement failed")).when(mockResultSet).getStatement();
        // Should not throw even when getStatement() fails
        assertDoesNotThrow(() -> JdbcUtil.closeQuietly(mockResultSet, true, true));
    }

    // Test closeQuietly(rs, stmt, conn) convenience method
    @Test
    public void testCloseQuietlyStatement() throws SQLException {
        assertDoesNotThrow(() -> JdbcUtil.closeQuietly(mockStatement));
        verify(mockStatement).close();
    }

    @Test
    public void testCloseQuietlyConnection() throws SQLException {
        assertDoesNotThrow(() -> JdbcUtil.closeQuietly(mockConnection));
        verify(mockConnection).close();
    }

    // Test callNotInStartedTransaction when inside a transaction - runs outside the transaction
    @Test
    public void testCallNotInStartedTransaction_WhileInTransaction() throws SQLException {
        SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource);
        try {
            // The call should execute outside the active transaction
            String result = JdbcUtil.callNotInStartedTransaction(mockDataSource, () -> "outsideResult");
            assertEquals("outsideResult", result);
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    @Test
    public void testCallNotInStartedTransactionWithDS_WhileInTransaction() throws SQLException {
        SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource);
        try {
            String result = JdbcUtil.callNotInStartedTransaction(mockDataSource, ds -> {
                assertNotNull(ds);
                return "dsResult";
            });
            assertEquals("dsResult", result);
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    @Test
    public void testRunNotInStartedTransaction_WhileInTransaction() throws SQLException {
        SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource);
        try {
            boolean[] ran = { false };
            JdbcUtil.runNotInStartedTransaction(mockDataSource, () -> ran[0] = true);
            assertTrue(ran[0]);
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    @Test
    public void testRunNotInStartedTransactionWithDS_WhileInTransaction() throws SQLException {
        SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource);
        try {
            boolean[] ran = { false };
            JdbcUtil.runNotInStartedTransaction(mockDataSource, ds -> {
                assertNotNull(ds);
                ran[0] = true;
            });
            assertTrue(ran[0]);
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    // Test DEFAULT_STMT_SETTER handles array of parameters
    @Test
    public void testDefaultStmtSetter() throws SQLException {
        Object[] params = { "value1", 42, true };
        assertDoesNotThrow(() -> JdbcUtil.DEFAULT_STMT_SETTER.accept(
                JdbcUtil.prepareQuery(mockConnection, "SELECT 1"), params));
    }

    // Test getAllColumnValues with Blob values
    @Test
    public void testGetAllColumnValues_BlobColumn() throws SQLException {
        byte[] data = "blobdata".getBytes();
        Blob blob1 = mock(Blob.class);
        Blob blob2 = mock(Blob.class);
        when(blob1.length()).thenReturn((long) data.length);
        when(blob1.getBytes(1, data.length)).thenReturn(data);
        when(blob2.length()).thenReturn((long) data.length);
        when(blob2.getBytes(1, data.length)).thenReturn(data);

        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn(blob1);
        when(mockResultSet.getBlob(1)).thenReturn(blob2);

        List<?> values = JdbcUtil.getAllColumnValues(mockResultSet, 1);
        assertEquals(2, values.size());
    }

    // Test prepareQuery with Connection overloads
    @Test
    public void testPrepareQuery_WithConnection() throws SQLException {
        PreparedQuery query = JdbcUtil.prepareQuery(mockConnection, "SELECT 1");
        assertNotNull(query);
        query.close();
    }

    @Test
    public void testPrepareNamedQuery_WithConnection() throws SQLException {
        NamedQuery query = JdbcUtil.prepareNamedQuery(mockConnection, "SELECT * FROM t WHERE id = :id");
        assertNotNull(query);
        query.close();
    }

    @Test
    public void testPrepareCallableQuery_WithConnection() throws SQLException {
        CallableQuery query = JdbcUtil.prepareCallableQuery(mockConnection, "{call proc(?)}");
        assertNotNull(query);
        query.close();
    }

    // Test entity class for various tests
    public static class TestEntity {
        private Long id;
        private String name;
        private Integer age;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }
    }
}
