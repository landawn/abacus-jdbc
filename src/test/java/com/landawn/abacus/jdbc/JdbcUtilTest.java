package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
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
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.ReadOnly;
import com.landawn.abacus.annotation.Transient;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.Jdbc.BiRowMapper;
import com.landawn.abacus.jdbc.Jdbc.OutParam;
import com.landawn.abacus.jdbc.Jdbc.OutParamResult;
import com.landawn.abacus.jdbc.Jdbc.RowFilter;
import com.landawn.abacus.jdbc.Jdbc.RowMapper;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.query.SQLBuilder;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.ImmutableMap;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.function.TriConsumer;
import com.landawn.abacus.util.stream.Stream;

public class JdbcUtilTest extends TestBase {

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
    public void testCreateHikariDataSource() {
        String url = "jdbc:h2:mem:test";
        String user = "sa";
        String password = "";

        // This test requires HikariCP in classpath
        try {
            DataSource ds = JdbcUtil.createHikariDataSource(url, user, password);
            assertNotNull(ds);
        } catch (RuntimeException e) {
            // HikariCP might not be available in test environment
            assertTrue(e.getMessage().contains("HikariConfig") || e.getCause() instanceof ClassNotFoundException);
        }
    }

    @Test
    public void testCreateHikariDataSourceWithPoolConfig() {
        String url = "jdbc:h2:mem:test";
        String user = "sa";
        String password = "";
        int minIdle = 5;
        int maxPoolSize = 20;

        try {
            DataSource ds = JdbcUtil.createHikariDataSource(url, user, password, minIdle, maxPoolSize);
            assertNotNull(ds);
        } catch (RuntimeException e) {
            // HikariCP might not be available in test environment
            assertTrue(e.getMessage().contains("HikariConfig") || e.getCause() instanceof ClassNotFoundException);
        }
    }

    @Test
    public void testCreateC3p0DataSource() {
        String url = "jdbc:h2:mem:test";
        String user = "sa";
        String password = "";

        try {
            DataSource ds = JdbcUtil.createC3p0DataSource(url, user, password);
            assertNotNull(ds);
        } catch (RuntimeException e) {
            // C3P0 might not be available in test environment
            assertTrue(e.getMessage().contains("ComboPooledDataSource") || e.getCause() instanceof ClassNotFoundException);
        }
    }

    @Test
    public void testCreateC3p0DataSourceWithPoolConfig() {
        String url = "jdbc:h2:mem:test";
        String user = "sa";
        String password = "";
        int minPoolSize = 3;
        int maxPoolSize = 15;

        try {
            DataSource ds = JdbcUtil.createC3p0DataSource(url, user, password, minPoolSize, maxPoolSize);
            assertNotNull(ds);
        } catch (RuntimeException e) {
            // C3P0 might not be available in test environment
            assertTrue(e.getMessage().contains("ComboPooledDataSource") || e.getCause() instanceof ClassNotFoundException);
        }
    }

    @Test
    public void testCreateConnection() {
        String url = "jdbc:h2:mem:test";
        String user = "sa";
        String password = "";

        try {
            Connection conn = JdbcUtil.createConnection(url, user, password);
            assertNotNull(conn);
            conn.close();
        } catch (Exception e) {
            // H2 driver might not be available
            assertTrue(e instanceof UncheckedSQLException || e instanceof IllegalArgumentException);
        }
    }

    @Test
    public void testCreateConnectionWithDriverClass() {
        String driverClass = "org.h2.Driver";
        String url = "jdbc:h2:mem:test";
        String user = "sa";
        String password = "";

        try {
            Connection conn = JdbcUtil.createConnection(driverClass, url, user, password);
            assertNotNull(conn);
            conn.close();
        } catch (Exception e) {
            // H2 driver might not be available
            assertTrue(e instanceof UncheckedSQLException || e instanceof IllegalArgumentException);
        }
    }

    @Test
    public void testGetConnection() throws SQLException {
        Connection conn = JdbcUtil.getConnection(mockDataSource);
        assertNotNull(conn);
        assertEquals(mockConnection, conn);
    }

    @Test
    public void testReleaseConnection() {
        // Should not throw exception
        JdbcUtil.releaseConnection(mockConnection, mockDataSource);
        JdbcUtil.releaseConnection(null, mockDataSource);
    }

    @Test
    public void testCloseResultSet() throws SQLException {
        JdbcUtil.close(mockResultSet);
        verify(mockResultSet).close();
    }

    @Test
    public void testCloseResultSetWithStatement() throws SQLException {
        when(mockResultSet.getStatement()).thenReturn(mockStatement);

        JdbcUtil.close(mockResultSet, true);

        verify(mockResultSet).close();
        verify(mockStatement).close();
    }

    @Test
    public void testCloseResultSetWithStatementAndConnection() throws SQLException {
        when(mockResultSet.getStatement()).thenReturn(mockStatement);
        when(mockStatement.getConnection()).thenReturn(mockConnection);

        JdbcUtil.close(mockResultSet, true, true);

        verify(mockResultSet).close();
        verify(mockStatement).close();
        verify(mockConnection).close();
    }

    @Test
    public void testCloseStatement() throws SQLException {
        JdbcUtil.close(mockStatement);
        verify(mockStatement).close();
    }

    @Test
    public void testCloseConnection() throws SQLException {
        JdbcUtil.close(mockConnection);
        verify(mockConnection).close();
    }

    @Test
    public void testCloseResultSetAndStatement() throws SQLException {
        JdbcUtil.close(mockResultSet, mockStatement);
        verify(mockResultSet).close();
        verify(mockStatement).close();
    }

    @Test
    public void testCloseStatementAndConnection() throws SQLException {
        JdbcUtil.close(mockStatement, mockConnection);
        verify(mockStatement).close();
        verify(mockConnection).close();
    }

    @Test
    public void testCloseAll() throws SQLException {
        JdbcUtil.close(mockResultSet, mockStatement, mockConnection);
        verify(mockResultSet).close();
        verify(mockStatement).close();
        verify(mockConnection).close();
    }

    @Test
    public void testCloseQuietly() throws SQLException {
        // Should not throw exception even if close fails
        doThrow(new SQLException()).when(mockResultSet).close();
        JdbcUtil.closeQuietly(mockResultSet);
    }

    @Test
    public void testCloseQuietlyWithStatement() throws SQLException {
        when(mockResultSet.getStatement()).thenReturn(mockStatement);
        JdbcUtil.closeQuietly(mockResultSet, true);
    }

    @Test
    public void testCloseQuietlyAll() throws SQLException {
        doThrow(new SQLException()).when(mockResultSet).close();
        doThrow(new SQLException()).when(mockStatement).close();
        doThrow(new SQLException()).when(mockConnection).close();

        // Should not throw exception
        JdbcUtil.closeQuietly(mockResultSet, mockStatement, mockConnection);
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
    }

    @Test
    public void testPrepareQuery() throws SQLException {
        String sql = "SELECT * FROM users WHERE id = ?";

        PreparedQuery query = JdbcUtil.prepareQuery(mockDataSource, sql);
        assertNotNull(query);
        query.close();
    }

    @Test
    public void testPrepareQueryWithAutoGeneratedKeys() throws SQLException {
        String sql = "INSERT INTO users (name) VALUES (?)";

        PreparedQuery query = JdbcUtil.prepareQuery(mockDataSource, sql, true);
        assertNotNull(query);
        query.close();
    }

    @Test
    public void testPrepareQueryWithReturnColumnIndexes() throws SQLException {
        String sql = "INSERT INTO users (name) VALUES (?)";
        int[] columnIndexes = { 1 };

        PreparedQuery query = JdbcUtil.prepareQuery(mockDataSource, sql, columnIndexes);
        assertNotNull(query);
        query.close();
    }

    @Test
    public void testPrepareQueryWithReturnColumnNames() throws SQLException {
        String sql = "INSERT INTO users (name) VALUES (?)";
        String[] columnNames = { "id" };

        PreparedQuery query = JdbcUtil.prepareQuery(mockDataSource, sql, columnNames);
        assertNotNull(query);
        query.close();
    }

    @Test
    public void testPrepareNamedQuery() throws SQLException {
        String sql = "SELECT * FROM users WHERE name = :name AND age > :age";

        NamedQuery query = JdbcUtil.prepareNamedQuery(mockDataSource, sql);
        assertNotNull(query);
        query.close();
    }

    @Test
    public void testPrepareCallableQuery() throws SQLException {
        String sql = "{call getUserInfo(?, ?)}";

        CallableQuery query = JdbcUtil.prepareCallableQuery(mockDataSource, sql);
        assertNotNull(query);
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

        RowFilter filter = rs -> {
            long id = rs.getLong(1);
            return id > 1;
        };

        Dataset data = JdbcUtil.extractData(mockResultSet, filter);
        assertNotNull(data);
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
    public void testStreamAllResultSets() throws SQLException {
        when(mockStatement.getResultSet()).thenReturn(mockResultSet);
        when(mockStatement.getMoreResults()).thenReturn(false);
        when(mockStatement.getUpdateCount()).thenReturn(-1);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("col");
        when(mockResultSet.next()).thenReturn(false);

        Stream<Dataset> stream = JdbcUtil.streamAllResultSets(mockStatement);
        List<Dataset> list = stream.toList();

        assertNotNull(list);
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
    public void testGetDBSequence() {
        DBSequence seq = JdbcUtil.getDBSequence(mockDataSource, "seq_table", "seq_name");
        assertNotNull(seq);
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
        List<String> params = JdbcUtil.getNamedParameters(sql);

        assertEquals(2, params.size());
        assertTrue(params.contains("name"));
        assertTrue(params.contains("age"));
    }

    @Test
    public void testParseSql() {
        String sql = "SELECT * FROM users WHERE id = :id";
        ParsedSql parsed = JdbcUtil.parseSql(sql);

        assertNotNull(parsed);
        assertEquals(1, parsed.getParameterCount());
        assertTrue(parsed.getNamedParameters().contains("id"));
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
        // After this, SQL logging should be disabled globally
    }

    @Test
    public void testTurnOffSqlPerfLogGlobally() {
        JdbcUtil.turnOffSqlPerfLogGlobally();
        // After this, SQL performance logging should be disabled globally
    }

    @Test
    public void testTurnOffDaoMethodPerfLogGlobally() {
        JdbcUtil.turnOffDaoMethodPerfLogGlobally();
        // After this, DAO method performance logging should be disabled globally
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

        SQLTransaction tran = JdbcUtil.beginTransaction(mockDataSource);
        assertTrue(JdbcUtil.isInTransaction(mockDataSource));
        tran.rollbackIfNotCommitted();
    }

    @Test
    public void testBeginTransaction() throws SQLException {
        SQLTransaction tran = JdbcUtil.beginTransaction(mockDataSource);
        assertNotNull(tran);
        tran.rollbackIfNotCommitted();
    }

    @Test
    public void testBeginTransactionWithIsolation() throws SQLException {
        SQLTransaction tran = JdbcUtil.beginTransaction(mockDataSource, IsolationLevel.READ_COMMITTED);
        assertNotNull(tran);
        tran.rollbackIfNotCommitted();
    }

    @Test
    public void testBeginTransactionWithIsolationAndUpdateOnly() throws SQLException {
        SQLTransaction tran = JdbcUtil.beginTransaction(mockDataSource, IsolationLevel.READ_COMMITTED, true);
        assertNotNull(tran);
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
        JdbcUtil.runInTransaction(mockDataSource, () -> {
            // Transaction code
        });
    }

    @Test
    public void testRunInTransactionWithConnection() throws SQLException {
        JdbcUtil.runInTransaction(mockDataSource, conn -> {
            assertNotNull(conn);
        });
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
        JdbcUtil.runNotInStartedTransaction(mockDataSource, () -> {
            // Non-transactional code
        });
    }

    @Test
    public void testRunNotInStartedTransactionWithDataSource() throws SQLException {
        JdbcUtil.runNotInStartedTransaction(mockDataSource, ds -> {
            assertNotNull(ds);
        });
    }

    @Test
    public void testRunWithoutUsingSpringTransaction() {
        JdbcUtil.runWithoutUsingSpringTransaction(() -> {
            // Code without Spring transaction
        });
    }

    @Test
    public void testCallWithoutUsingSpringTransaction() {
        String result = JdbcUtil.callWithoutUsingSpringTransaction(() -> "test");
        assertEquals("test", result);
    }

    @Test
    public void testAsyncRun() throws Exception {
        ContinuableFuture<Void> future = JdbcUtil.asyncRun(() -> {
            // Async operation
        });

        assertNotNull(future);
        future.get(1, TimeUnit.SECONDS);
    }

    @Test
    public void testAsyncRunMultiple() throws Exception {
        Tuple2<ContinuableFuture<Void>, ContinuableFuture<Void>> futures = JdbcUtil.asyncRun(() -> {
        }, () -> {
        });

        assertNotNull(futures._1);
        assertNotNull(futures._2);

        futures._1.get(1, TimeUnit.SECONDS);
        futures._2.get(1, TimeUnit.SECONDS);
    }

    @Test
    public void testAsyncRunTriple() throws Exception {
        Tuple3<ContinuableFuture<Void>, ContinuableFuture<Void>, ContinuableFuture<Void>> futures = JdbcUtil.asyncRun(() -> {
        }, () -> {
        }, () -> {
        });

        assertNotNull(futures._1);
        assertNotNull(futures._2);
        assertNotNull(futures._3);
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
        interface TestDao extends CrudDao<Object, Long, SQLBuilder, TestDao> {
        }

        RowMapper<Long> extractor = rs -> rs.getLong(1);
        JdbcUtil.setIdExtractorForDao(TestDao.class, extractor);
        // Extractor is set internally
    }

    @Test
    public void testSetIdExtractorForDaoWithBiRowMapper() {
        interface TestDao extends CrudDao<Object, Long, SQLBuilder, TestDao> {
        }

        BiRowMapper<Long> extractor = (rs, labels) -> rs.getLong(1);
        JdbcUtil.setIdExtractorForDao(TestDao.class, extractor);
        // Extractor is set internally
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
    //        SQLMapper sqlMapper = mock(SQLMapper.class);
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
        Jdbc.DaoCache cache = JdbcUtil.startDaoCacheOnCurrentThread();
        assertNotNull(cache);

        JdbcUtil.closeDaoCacheOnCurrentThread();
    }

    @Test
    public void testStartDaoCacheOnCurrentThreadWithCache() {
        Jdbc.DaoCache cache = Jdbc.DaoCache.createByMap();
        Jdbc.DaoCache result = JdbcUtil.startDaoCacheOnCurrentThread(cache);

        assertEquals(cache, result);

        JdbcUtil.closeDaoCacheOnCurrentThread();
    }

    @Test
    public void testCloseDaoCacheOnCurrentThread() {
        JdbcUtil.startDaoCacheOnCurrentThread();
        JdbcUtil.closeDaoCacheOnCurrentThread();
        // Cache is removed from thread local
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