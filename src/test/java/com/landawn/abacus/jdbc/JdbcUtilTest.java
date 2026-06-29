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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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

    private record NamedRecordParameter(String firstName, String lastName) {
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        // Reset the static resultSetClassNotSupportAbsolute set so tests don't pollute each other
        final Field rsNoAbsoluteField = JdbcUtil.class.getDeclaredField("resultSetClassNotSupportAbsolute");
        rsNoAbsoluteField.setAccessible(true);
        ((Set<?>) rsNoAbsoluteField.get(null)).clear();

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
    public void testGetDBProductInfo_NullConnectionThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.getDBProductInfo((Connection) null));
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
    public void testIsDefaultIdPropValue_BigNumberZeroDetection() {
        assertTrue(JdbcUtil.isDefaultIdPropValue(BigDecimal.ZERO));
        assertTrue(JdbcUtil.isDefaultIdPropValue(BigInteger.ZERO));
        assertFalse(JdbcUtil.isDefaultIdPropValue(new BigDecimal("1E-400")));
        assertFalse(JdbcUtil.isDefaultIdPropValue(BigInteger.ONE));
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

    /** A {@link java.sql.Driver} whose constructor always fails, used to exercise the registration-failure path. */
    public static final class CtorThrowingDriver implements java.sql.Driver {
        public CtorThrowingDriver() {
            throw new RuntimeException("ABACUS_TEST_DRIVER_CTOR_BOOM");
        }

        @Override
        public Connection connect(String url, java.util.Properties info) {
            return null;
        }

        @Override
        public boolean acceptsURL(String url) {
            return false;
        }

        @Override
        public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties info) {
            return new java.sql.DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 0;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return null;
        }
    }

    private static boolean causeChainContains(final Throwable t, final String marker) {
        Throwable cur = t;
        while (cur != null) {
            if (cur.getMessage() != null && cur.getMessage().contains(marker)) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    @Test
    public void testCreateConnectionFailedRegistrationDoesNotPoisonDriverCache() {
        final String url = "jdbc:abacus-test-no-such-driver://localhost/db";

        // First attempt: the driver fails to instantiate (its constructor throws).
        final Exception first = assertThrows(Exception.class, () -> JdbcUtil.createConnection(CtorThrowingDriver.class, url, "u", "p"));
        assertTrue(causeChainContains(first, "ABACUS_TEST_DRIVER_CTOR_BOOM"));

        // Regression: a failed registration must NOT poison the driver-registration cache. The second attempt must
        // re-try registration (and fail in the constructor again), not silently skip registration and surface an
        // unrelated "No suitable driver" error.
        final Exception second = assertThrows(Exception.class, () -> JdbcUtil.createConnection(CtorThrowingDriver.class, url, "u", "p"));
        assertTrue(causeChainContains(second, "ABACUS_TEST_DRIVER_CTOR_BOOM"),
                "Second attempt should re-try driver registration, not skip it due to a poisoned cache");
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

    // closeQuietly(ResultSet, boolean, boolean) validates flags and discovers statement/connection handles.
    @Test
    public void testCloseQuietly_ResultSetFlags() throws SQLException {
        when(mockResultSet.getStatement()).thenReturn(mockStatement);
        when(mockStatement.getConnection()).thenReturn(mockConnection);

        assertDoesNotThrow(() -> JdbcUtil.closeQuietly(mockResultSet, true, true));

        verify(mockResultSet).getStatement();
        verify(mockStatement).getConnection();
        verify(mockResultSet).close();
        verify(mockStatement).close();
        verify(mockConnection).close();
    }

    @Test
    public void testCloseQuietly_InvalidFlags() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.closeQuietly(mockResultSet, false, true));
    }

    @Test
    public void testCloseQuietly_NullResultSetWithFlags() {
        assertDoesNotThrow(() -> JdbcUtil.closeQuietly((ResultSet) null, true, true));
    }

    @Test
    public void testSkipInt() throws SQLException {
        when(mockResultSet.getRow()).thenReturn(0, 2);
        when(mockResultSet.next()).thenReturn(true, true, false);

        long skipped = JdbcUtil.skip(mockResultSet, 2);
        assertEquals(2, skipped);
    }

    @Test
    public void testSkipLong() throws SQLException {
        when(mockResultSet.getRow()).thenReturn(0, 1);
        when(mockResultSet.next()).thenReturn(true, false);

        long skipped = JdbcUtil.skip(mockResultSet, 1L);
        assertEquals(1, skipped);
    }

    @Test
    public void testGetColumnCount() throws SQLException {
        when(mockResultSetMetaData.getColumnCount()).thenReturn(5);

        int count = JdbcUtil.getColumnCount(mockResultSet);
        assertEquals(5, count);
    }

    @Test
    public void testGetColumnNames() throws SQLException {
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);
        when(mockPreparedStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.getMetaData()).thenReturn(mockResultSetMetaData);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(3);
        when(mockResultSetMetaData.getColumnName(1)).thenReturn("id");
        when(mockResultSetMetaData.getColumnName(2)).thenReturn("name");
        when(mockResultSetMetaData.getColumnName(3)).thenReturn("age");

        List<String> columns = JdbcUtil.getColumnNames(mockConnection, "users");

        assertEquals(3, columns.size());
        assertEquals("id", columns.get(0));
        assertEquals("name", columns.get(1));
        assertEquals("age", columns.get(2));
    }

    // A two-part "schema.table" name splits into schema/table (JdbcUtil L1840-1843) and the columns
    // are read from JDBC metadata via getColumnNamesFromMetadata (L1915-1941).
    @Test
    public void testGetColumnNames_TwoPartName_FromMetadata() throws SQLException {
        final ResultSet colsRs = mock(ResultSet.class);
        when(mockConnection.getCatalog()).thenReturn("cat");
        when(mockConnection.getSchema()).thenReturn("myschema");
        when(mockDatabaseMetaData.getColumns("cat", "myschema", "users", null)).thenReturn(colsRs);
        when(colsRs.next()).thenReturn(true, true, false);
        when(colsRs.getString("TABLE_NAME")).thenReturn("users");
        when(colsRs.getString("TABLE_SCHEM")).thenReturn("myschema");
        when(colsRs.getString("TABLE_CAT")).thenReturn("cat");
        when(colsRs.getString("COLUMN_NAME")).thenReturn("id", "name");

        final List<String> cols = JdbcUtil.getColumnNames(mockConnection, "myschema.users");
        assertEquals(Arrays.asList("id", "name"), cols);
    }

    // A three-part "catalog.schema.table" name fills all three slots (JdbcUtil L1844-1848).
    @Test
    public void testGetColumnNames_ThreePartName_FromMetadata() throws SQLException {
        final ResultSet colsRs = mock(ResultSet.class);
        when(mockDatabaseMetaData.getColumns("mycat", "myschema", "users", null)).thenReturn(colsRs);
        when(colsRs.next()).thenReturn(true, false);
        when(colsRs.getString("TABLE_NAME")).thenReturn("users");
        when(colsRs.getString("TABLE_SCHEM")).thenReturn("myschema");
        when(colsRs.getString("TABLE_CAT")).thenReturn("mycat");
        when(colsRs.getString("COLUMN_NAME")).thenReturn("id");

        final List<String> cols = JdbcUtil.getColumnNames(mockConnection, "mycat.myschema.users");
        assertEquals(Arrays.asList("id"), cols);
    }

    // When neither the metadata lookups nor the SELECT fallback yield any column, a SQLException is
    // raised (JdbcUtil L1886); getColumns returning null also exercises the rs==null guard (L1911).
    @Test
    public void testGetColumnNames_NoColumnsFound_Throws() throws SQLException {
        when(mockConnection.getCatalog()).thenReturn(null);
        when(mockConnection.getSchema()).thenReturn(null);
        // getColumns(...) is unstubbed -> returns null -> emptyList for every metadata attempt.
        // The SELECT fallback runs against the default mock statement whose metadata reports 0 columns.
        when(mockResultSetMetaData.getColumnCount()).thenReturn(0);

        assertThrows(SQLException.class, () -> JdbcUtil.getColumnNames(mockConnection, "ghost_table"));
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

    // Blob whose length exceeds Integer.MAX_VALUE trips the size guard (JdbcUtil L2262); the Blob is
    // still freed in the finally block.
    @Test
    public void testGetColumnValue_BlobSizeOverflow_Throws() throws SQLException {
        when(mockBlob.length()).thenReturn((long) Integer.MAX_VALUE + 1L);
        when(mockResultSet.getObject(1)).thenReturn(mockBlob);

        assertThrows(SQLException.class, () -> JdbcUtil.getColumnValue(mockResultSet, 1));
        verify(mockBlob).free();
    }

    // Clob whose length exceeds Integer.MAX_VALUE trips the size guard (JdbcUtil L2272).
    @Test
    public void testGetColumnValue_ClobSizeOverflow_Throws() throws SQLException {
        when(mockClob.length()).thenReturn((long) Integer.MAX_VALUE + 1L);
        when(mockResultSet.getObject(1)).thenReturn(mockClob);

        assertThrows(SQLException.class, () -> JdbcUtil.getColumnValue(mockResultSet, 1));
        verify(mockClob).free();
    }

    // A non-String/Number/Timestamp/Boolean/Blob/Clob value routes through the column converter
    // (JdbcUtil L2279); the default converter returns the value unchanged.
    @Test
    public void testGetColumnValue_NonLobObject_AppliesConverter() throws SQLException {
        final java.util.Date date = new java.util.Date();
        when(mockResultSet.getObject(1)).thenReturn(date);

        assertEquals(date, JdbcUtil.getColumnValue(mockResultSet, 1));
    }

    // getColumnValue(rs, columnLabel) label-path: simple value short-circuits (JdbcUtil L2321/L2330).
    @Test
    public void testGetColumnValueByLabel() throws SQLException {
        when(mockResultSet.getObject("name")).thenReturn("Alice");
        assertEquals("Alice", JdbcUtil.getColumnValue(mockResultSet, "name"));
    }

    // getColumnValue(rs, columnLabel) label-path Blob materialization to byte[] (JdbcUtil L2334-2342).
    @Test
    public void testGetColumnValueByLabel_Blob() throws SQLException {
        final byte[] data = "blob".getBytes();
        when(mockBlob.length()).thenReturn((long) data.length);
        when(mockBlob.getBytes(1, data.length)).thenReturn(data);
        when(mockResultSet.getObject("data")).thenReturn(mockBlob);

        assertArrayEquals(data, (byte[]) JdbcUtil.getColumnValue(mockResultSet, "data"));
        verify(mockBlob).free();
    }

    // getColumnValue(rs, columnLabel) label-path Clob materialization to String (JdbcUtil L2344-2352).
    @Test
    public void testGetColumnValueByLabel_Clob() throws SQLException {
        final String data = "clob";
        when(mockClob.length()).thenReturn((long) data.length());
        when(mockClob.getSubString(1, data.length())).thenReturn(data);
        when(mockResultSet.getObject("doc")).thenReturn(mockClob);

        assertEquals(data, JdbcUtil.getColumnValue(mockResultSet, "doc"));
        verify(mockClob).free();
    }

    // getColumnValue(rs, columnLabel) label-path converter fallback for a non-LOB object (JdbcUtil L2355).
    @Test
    public void testGetColumnValueByLabel_NonLobObject_AppliesConverter() throws SQLException {
        final java.util.Date date = new java.util.Date();
        when(mockResultSet.getObject("created")).thenReturn(date);

        assertEquals(date, JdbcUtil.getColumnValue(mockResultSet, "created"));
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
    public void testSetParameters_NamedRecordParameter() throws SQLException {
        ParsedSql parsedSql = JdbcUtil.parseSql("SELECT * FROM users WHERE first_name = :firstName AND last_name = :lastName");

        JdbcUtil.setParameters(parsedSql, mockPreparedStatement, new Object[] { new NamedRecordParameter("Ada", "Lovelace") });

        verify(mockPreparedStatement).setString(1, "Ada");
        verify(mockPreparedStatement).setString(2, "Lovelace");
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
    public void testExecuteBatchUpdate_IgnoresJdbcSentinelCounts() throws SQLException {
        String sql = "INSERT INTO users (name) VALUES (?)";
        List<Object[]> params = Arrays.asList(new Object[] { "John" }, new Object[] { "Jane" }, new Object[] { "Bob" }, new Object[] { "Alice" });
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 2, Statement.SUCCESS_NO_INFO, Statement.EXECUTE_FAILED, 1 });

        int total = JdbcUtil.executeBatchUpdate(mockDataSource, sql, params);
        assertEquals(3, total);
    }

    @Test
    public void testExecuteBatchUpdate_ThrowsOnIntCountOverflow() throws SQLException {
        String sql = "INSERT INTO users (name) VALUES (?)";
        List<Object[]> params = Arrays.asList(new Object[] { "John" }, new Object[] { "Jane" });
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { Integer.MAX_VALUE }, new int[] { 1 });

        ArithmeticException thrown = assertThrows(ArithmeticException.class, () -> JdbcUtil.executeBatchUpdate(mockConnection, sql, params, 1));
        assertTrue(thrown.getMessage().contains("executeLargeBatchUpdate"));
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
    public void testExecuteLargeBatchUpdate_IgnoresJdbcSentinelCounts() throws SQLException {
        String sql = "INSERT INTO users (name) VALUES (?)";
        List<Object[]> params = Arrays.asList(new Object[] { "John" }, new Object[] { "Jane" }, new Object[] { "Bob" }, new Object[] { "Alice" });
        when(mockPreparedStatement.executeLargeBatch()).thenReturn(new long[] { 2L, Statement.SUCCESS_NO_INFO, Statement.EXECUTE_FAILED, 1L });

        long total = JdbcUtil.executeLargeBatchUpdate(mockDataSource, sql, params);
        assertEquals(3L, total);
    }

    @Test
    public void testExecuteLargeBatchUpdate_ThrowsOnLongCountOverflow() throws SQLException {
        String sql = "INSERT INTO users (name) VALUES (?)";
        List<Object[]> params = Arrays.asList(new Object[] { "John" }, new Object[] { "Jane" });
        when(mockPreparedStatement.executeLargeBatch()).thenReturn(new long[] { Long.MAX_VALUE }, new long[] { 1L });

        ArithmeticException thrown = assertThrows(ArithmeticException.class, () -> JdbcUtil.executeLargeBatchUpdate(mockConnection, sql, params, 1));
        assertTrue(thrown.getMessage().contains("exceeds Long.MAX_VALUE"));
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
    public void testStreamAllResultSets_multipleResultSets() throws SQLException {
        // Simulate a stored procedure returning two result sets.
        // The second ResultSet is a separate mock.
        ResultSet mockResultSet2 = mock(ResultSet.class);
        ResultSetMetaData mockResultSetMetaData2 = mock(ResultSetMetaData.class);

        // First result set: one row with column "a"
        when(mockResultSet.getMetaData()).thenReturn(mockResultSetMetaData);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("a");
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn("row1");

        // Second result set: one row with column "b"
        when(mockResultSet2.getMetaData()).thenReturn(mockResultSetMetaData2);
        when(mockResultSetMetaData2.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData2.getColumnLabel(1)).thenReturn("b");
        when(mockResultSet2.next()).thenReturn(true, false);
        when(mockResultSet2.getObject(1)).thenReturn("row2");

        // First call: getResultSet() returns mockResultSet (first result set is current)
        when(mockStatement.getResultSet()).thenReturn(mockResultSet);
        // After next() consumes the first RS, getMoreResults(KEEP_CURRENT_RESULT) returns true (second RS available)
        when(mockStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(true, false);
        // When hasNext() is called for the second RS, getResultSet() returns mockResultSet2
        when(mockStatement.getResultSet()).thenReturn(mockResultSet, mockResultSet2);
        // After consuming the second RS, getMoreResults(KEEP_CURRENT_RESULT) returns false; getUpdateCount() == -1
        when(mockStatement.getUpdateCount()).thenReturn(-1);

        Stream<Dataset> stream = JdbcUtil.streamAllResultSets(mockStatement);
        List<Dataset> list = stream.toList();

        assertEquals(2, list.size(), "should yield two Datasets for two result sets");
        assertEquals(1, list.get(0).size(), "first Dataset should have one row");
        assertEquals(1, list.get(1).size(), "second Dataset should have one row");
    }

    @Test
    @Tag("2025")
    public void testIterateAllResultSets_NextClosesResultSetIfGetMoreResultsThrows() throws SQLException {
        // hasNext() pulls the first ResultSet into the holder via stmt.getResultSet().
        when(mockStatement.getResultSet()).thenReturn(mockResultSet);
        // next() removes it from the holder and calls stmt.getMoreResults(KEEP_CURRENT_RESULT)
        // to advance. If that call fails, the caller never receives the ResultSet, so the iterator
        // must close it itself rather than leaking it until the statement is closed.
        when(mockStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenThrow(new SQLException("driver error during getMoreResults"));

        com.landawn.abacus.util.stream.ObjIteratorEx<ResultSet> iter = JdbcUtil.iterateAllResultSets(mockStatement, true);
        try {
            assertTrue(iter.hasNext(), "first ResultSet should be available");
            UncheckedSQLException ex = assertThrows(UncheckedSQLException.class, iter::next);
            assertNotNull(ex.getCause());
            verify(mockResultSet).close();
        } finally {
            iter.closeResource();
        }
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
    public void testToQualifiedSqlIdentifier_PreservesQuotedDotsWithinIdentifier() throws SQLException {
        when(mockDatabaseMetaData.getIdentifierQuoteString()).thenReturn("\"");

        assertEquals("\"schema\".\"sales.data\"", JdbcUtil.toQualifiedSqlIdentifier(mockConnection, "schema.\"sales.data\"", "tableName"));
        assertEquals("\"sales.data\"", JdbcUtil.toQualifiedSqlIdentifier(mockConnection, "\"sales.data\"", "tableName"));
    }

    @Test
    public void testToQualifiedSqlIdentifier_RejectsUnsafeWhenNoQuoteSupport() throws SQLException {
        when(mockDatabaseMetaData.getIdentifierQuoteString()).thenReturn(" ");

        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.toQualifiedSqlIdentifier(mockConnection, "users;drop", "tableName"));
    }

    @Test
    public void testDoesTableExist_WithQuotedDotIdentifier() throws SQLException {
        final ResultSet tableRs = mock(ResultSet.class);

        when(mockDatabaseMetaData.getTables(null, null, "sales.data", null)).thenReturn(tableRs);
        when(tableRs.next()).thenReturn(true);

        assertTrue(JdbcUtil.doesTableExist(mockConnection, "\"sales.data\""));
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
    public void testGetOutParameters_floatTypeUsesGetDouble() throws SQLException {
        // Regression: per the JDBC spec (Appendix B), SQL FLOAT is an 8-byte, double-precision type that
        // maps to Java double (synonym of Types.DOUBLE), while SQL REAL is the 4-byte type mapping to Java
        // float. A Types.FLOAT OUT parameter must therefore be read with getDouble(), not getFloat() (which
        // would narrow to single precision and overflow to Float.POSITIVE_INFINITY beyond ~3.4e38).
        final double preciseDoubleValue = 1234567.890123456d;
        final float realValue = 3.5f;

        when(mockCallableStatement.getDouble(1)).thenReturn(preciseDoubleValue);
        when(mockCallableStatement.getFloat(2)).thenReturn(realValue);

        List<OutParam> outParams = Arrays.asList(OutParam.of(1, Types.FLOAT), OutParam.of(2, Types.REAL));

        OutParamResult result = JdbcUtil.getOutParameters(mockCallableStatement, outParams);

        assertNotNull(result);

        // Types.FLOAT must be retrieved at full double precision (via getDouble), not narrowed to a Float.
        Object floatParam = result.getOutParamValue(1);
        assertTrue(floatParam instanceof Double, "Types.FLOAT OUT param should be read as Double via getDouble()");
        assertEquals(preciseDoubleValue, (Double) floatParam, 0.0d);

        // Types.REAL must still be retrieved as a Float (via getFloat).
        Object realParam = result.getOutParamValue(2);
        assertTrue(realParam instanceof Float, "Types.REAL OUT param should be read as Float via getFloat()");
        assertEquals(realValue, (Float) realParam, 0.0f);

        // Confirm the correct CallableStatement accessors were invoked for each SQL type.
        verify(mockCallableStatement).getDouble(1);
        verify(mockCallableStatement).getFloat(2);
        verify(mockCallableStatement, never()).getFloat(1);
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
        Collection<String> propNames = JdbcUtil.getInsertPropNames(entity.getClass());

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
    @DisplayName("isInTransaction should not propagate LinkageError when Spring is on classpath but mismatched")
    public void testIsInTransactionToleratesLinkageError() throws Exception {
        // The Spring DataSourceUtils.isConnectionTransactional fallback should catch any LinkageError
        // subtype (NoClassDefFoundError, NoSuchMethodError, etc.) — not just NoClassDefFoundError —
        // so that an absent/mismatched Spring on the classpath does not crash callers.
        // We can only test the public-API contract here: with no active transaction the call must
        // simply return without throwing, regardless of whether Spring participation is enabled.
        final Field isInSpringField = JdbcUtil.class.getDeclaredField("isInSpring");
        isInSpringField.setAccessible(true);
        final boolean originalIsInSpring = (boolean) isInSpringField.get(null);

        try {
            assertDoesNotThrow(() -> JdbcUtil.isInTransaction(mockDataSource));
        } finally {
            isInSpringField.set(null, originalIsInSpring);
        }
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
        interface TestDao extends CrudDao<Object, Long, TestDao> {
        }

        RowMapper<Long> extractor = rs -> rs.getLong(1);
        JdbcUtil.setIdExtractorForDao(TestDao.class, extractor);
        assertTrue(getIdExtractorPool().containsKey(TestDao.class));
        getIdExtractorPool().remove(TestDao.class);
    }

    @Test
    public void testSetIdExtractorForDaoWithBiRowMapper() {
        interface TestDao extends CrudDao<Object, Long, TestDao> {
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
    //        TestDao dao = JdbcUtil.createDao(TestDao.class, mockDataSource, PSC);
    //        assertNotNull(dao);
    //    }
    //
    //    @Test
    //    public void testCreateDaoWithSqlMapper() {
    //        interface TestDao extends Dao {}
    //        SqlMapper sqlMapper = mock(SqlMapper.class);
    //
    //        TestDao dao = JdbcUtil.createDao(TestDao.class, mockDataSource, PSC, sqlMapper);
    //        assertNotNull(dao);
    //    }
    //
    //    @Test
    //    public void testCreateDaoWithExecutor() {
    //        interface TestDao extends Dao {}
    //        ExecutorService executor = Executors.newSingleThreadExecutor();
    //
    //        try {
    //            TestDao dao = JdbcUtil.createDao(TestDao.class, mockDataSource, PSC, executor);
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
    //        TestDao dao = JdbcUtil.createDao(TestDao.class, "custom_table", mockDataSource, PSC);
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
        assertDoesNotThrow(() -> JdbcUtil.DEFAULT_STMT_SETTER.accept(JdbcUtil.prepareQuery(mockConnection, "SELECT 1"), params));
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

    @Test
    public void testSkip_ZeroRows_ReturnsZero() throws SQLException {
        assertEquals(0, JdbcUtil.skip(mockResultSet, 0));
        assertEquals(0, JdbcUtil.skip(mockResultSet, 0L));
    }

    @Test
    public void testSkip_ManualIteration_WhenCurrentRowNearMax() throws SQLException {
        // rowsToSkip=2, currentRow=Integer.MAX_VALUE-1 → 2 > (MAX - (MAX-1)) = 2 > 1 → manual loop
        ResultSet freshRs = mock(ResultSet.class);
        when(freshRs.getRow()).thenReturn(Integer.MAX_VALUE - 1);
        when(freshRs.next()).thenReturn(true, false);

        long skipped = JdbcUtil.skip(freshRs, 2L);
        assertEquals(1, skipped);
    }

    @Test
    public void testSkip_Absolute_PastEnd_LastReturnsTrue() throws SQLException {
        // Use a fresh mock to avoid static resultSetClassNotSupportAbsolute pollution
        // rs.absolute() ignored; newRow=0 → else → rs.last() returns true → lastRow=3
        ResultSet freshRs = mock(ResultSet.class);
        when(freshRs.getRow()).thenReturn(0, 0, 3);
        when(freshRs.last()).thenReturn(true);

        long skipped = JdbcUtil.skip(freshRs, 5L);
        assertEquals(3, skipped);
        verify(freshRs).afterLast();
    }

    @Test
    public void testSkip_Absolute_PastEnd_LastReturnsFalse() throws SQLException {
        // Use a fresh mock; newRow=0 → else → rs.last() returns false → skipped=0
        ResultSet freshRs = mock(ResultSet.class);
        when(freshRs.getRow()).thenReturn(0, 0);
        when(freshRs.last()).thenReturn(false);

        long skipped = JdbcUtil.skip(freshRs, 5L);
        assertEquals(0, skipped);
    }

    @Test
    public void testSkip_AbsoluteThrows_FallsBackToManualIteration() throws SQLException {
        // rs.absolute() throws → catch block → remaining=1 after getRow adjustment → rs.next()==true → skipped=2
        ResultSet freshRs = mock(ResultSet.class);
        when(freshRs.getRow()).thenReturn(0, 1);
        doThrow(new SQLException("absolute not supported")).when(freshRs).absolute(anyInt());
        when(freshRs.next()).thenReturn(true, false);

        long skipped = JdbcUtil.skip(freshRs, 2L);
        assertEquals(2, skipped);
    }

    @Test
    public void testSkip_GetRowUnsupported_FallsBackToManualIteration() throws SQLException {
        ResultSet freshRs = mock(ResultSet.class);
        when(freshRs.getRow()).thenThrow(new SQLException("getRow not supported"));
        when(freshRs.next()).thenReturn(true, true, false);

        long skipped = JdbcUtil.skip(freshRs, 2L);

        assertEquals(2, skipped);
    }

    // getInsertPropNames overloads
    @Test
    public void testGetInsertPropNames_WithExclusions() {
        class TestInsertEntity {
            private String firstName;
            private String lastName;
            @ReadOnly
            private String readOnlyField;

            public String getFirstName() {
                return firstName;
            }

            public void setFirstName(String firstName) {
                this.firstName = firstName;
            }

            public String getLastName() {
                return lastName;
            }

            public void setLastName(String lastName) {
                this.lastName = lastName;
            }

            public String getReadOnlyField() {
                return readOnlyField;
            }

            public void setReadOnlyField(String readOnlyField) {
                this.readOnlyField = readOnlyField;
            }
        }

        TestInsertEntity entity = new TestInsertEntity();
        Set<String> excluded = Set.of("lastName");
        Collection<String> propNames = JdbcUtil.getInsertPropNames(entity.getClass(), excluded);

        assertNotNull(propNames);
        assertFalse(propNames.contains("readOnlyField"));
        assertFalse(propNames.contains("lastName"));
        assertTrue(propNames.contains("firstName"));
    }

    @Test
    public void testGetInsertPropNames_FromClass() {
        class TestInsertClass {
            private String name;
            @ReadOnly
            private String readOnlyField;

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

        Collection<String> propNames = JdbcUtil.getInsertPropNames(TestInsertClass.class);

        assertNotNull(propNames);
        assertFalse(propNames.contains("readOnlyField"));
        assertTrue(propNames.contains("name"));
    }

    @Test
    public void testGetInsertPropNames_FromClassWithExclusions() {
        class TestInsertExClass {
            private String name;
            private String desc;

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getDesc() {
                return desc;
            }

            public void setDesc(String desc) {
                this.desc = desc;
            }
        }

        Set<String> excluded = Set.of("desc");
        Collection<String> propNames = JdbcUtil.getInsertPropNames(TestInsertExClass.class, excluded);

        assertNotNull(propNames);
        assertFalse(propNames.contains("desc"));
        assertTrue(propNames.contains("name"));
    }

    // getSelectPropNames overloads
    @Test
    public void testGetSelectPropNames_WithExclusions() {
        class TestSelectClass {
            private Long id;
            private String name;
            @Transient
            private String transField;

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

            public String getTransField() {
                return transField;
            }

            public void setTransField(String transField) {
                this.transField = transField;
            }
        }

        Set<String> excluded = Set.of("id");
        Collection<String> propNames = JdbcUtil.getSelectPropNames(TestSelectClass.class, excluded);

        assertNotNull(propNames);
        assertFalse(propNames.contains("transField"));
        assertFalse(propNames.contains("id"));
        assertTrue(propNames.contains("name"));
    }

    @Test
    public void testGetSelectPropNames_WithSubEntities() {
        class TestSubEntityClass {
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

        Collection<String> propNames = JdbcUtil.getSelectPropNames(TestSubEntityClass.class, true, null);

        assertNotNull(propNames);
        assertTrue(propNames.contains("id"));
        assertTrue(propNames.contains("name"));
    }

    // getUpdatePropNames overloads
    @Test
    public void testGetUpdatePropNames_FromClass() {
        class TestUpdateClass {
            private String name;
            @ReadOnly
            private String readOnlyField;

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

        Collection<String> propNames = JdbcUtil.getUpdatePropNames(TestUpdateClass.class);

        assertNotNull(propNames);
        assertTrue(propNames.contains("name"));
        assertFalse(propNames.contains("readOnlyField"));
    }

    @Test
    public void testGetUpdatePropNames_FromClassWithExclusions() {
        class TestUpdateExClass {
            private String name;
            private String desc;

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getDesc() {
                return desc;
            }

            public void setDesc(String desc) {
                this.desc = desc;
            }
        }

        Set<String> excluded = Set.of("desc");
        Collection<String> propNames = JdbcUtil.getUpdatePropNames(TestUpdateExClass.class, excluded);

        assertNotNull(propNames);
        assertFalse(propNames.contains("desc"));
        assertTrue(propNames.contains("name"));
    }

    // getColumnNames validation
    @Test
    public void testGetColumnNames_NullConnection() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.getColumnNames(null, "users"));
    }

    @Test
    public void testGetColumnNames_BlankTableName() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.getColumnNames(mockConnection, ""));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.getColumnNames(mockConnection, "   "));
    }

    // getColumnLabels
    @Test
    public void testGetColumnLabels() throws SQLException {
        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("user_id");
        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("");
        when(mockResultSetMetaData.getColumnName(2)).thenReturn("user_name");

        List<String> labels = JdbcUtil.getColumnLabels(mockResultSet);

        assertEquals(2, labels.size());
        assertEquals("user_id", labels.get(0));
        assertEquals("user_name", labels.get(1));
    }

    // getDBProductInfo edge cases
    @Test
    public void testGetDBProductInfo_MariaDBProductName() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MariaDB");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("10.5.9");

        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);

        assertNotNull(info);
        assertEquals(DBVersion.MariaDB, info.version());
    }

    @Test
    public void testGetDBProductInfo_NullProductNameAndVersion() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn(null);
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn(null);

        DBProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);

        assertNotNull(info);
        assertEquals(DBVersion.OTHERS, info.version());
    }

    // getDBProductInfo DataSource overload with SQLException when Spring bypassed
    @Test
    public void testGetDBProductInfo_DataSource_SQLException() throws SQLException {
        when(mockDataSource.getConnection()).thenThrow(new SQLException("connection failed"));

        JdbcUtil.runWithoutUsingSpringTransaction(() -> {
            assertThrows(UncheckedSQLException.class, () -> JdbcUtil.getDBProductInfo(mockDataSource));
        });
    }

    // blob2String edge cases
    @Test
    public void testBlob2String_NullBlob() throws SQLException {
        assertNull(JdbcUtil.blob2String(null));
    }

    @Test
    public void testBlob2String_NullBlobWithCharset() throws SQLException {
        assertNull(JdbcUtil.blob2String(null, StandardCharsets.UTF_8));
    }

    // clob2String edge cases
    @Test
    public void testClob2String_NullClob() throws SQLException {
        assertNull(JdbcUtil.clob2String(null));
    }

    // writeBlobToFile edge cases
    @Test
    public void testWriteBlobToFile_NullBlob() throws SQLException, IOException {
        assertEquals(0L, JdbcUtil.writeBlobToFile(null, new File("output.txt")));
    }

    // writeClobToFile edge cases
    @Test
    public void testWriteClobToFile_NullClob() throws SQLException, IOException {
        assertEquals(0L, JdbcUtil.writeClobToFile(null, new File("output.txt")));
    }

    // isNullOrDefault additional edge cases
    @Test
    public void testIsNullOrDefault_FloatZero() {
        assertTrue(JdbcUtil.isNullOrDefault(0.0f));
    }

    @Test
    public void testIsNullOrDefault_ShortZero() {
        assertTrue(JdbcUtil.isNullOrDefault((short) 0));
    }

    @Test
    public void testIsNullOrDefault_ByteZero() {
        assertTrue(JdbcUtil.isNullOrDefault((byte) 0));
    }

    @Test
    public void testIsNullOrDefault_NonZeroFloat() {
        assertFalse(JdbcUtil.isNullOrDefault(0.1f));
    }

    @Test
    public void testIsNullOrDefault_NonZeroShort() {
        assertFalse(JdbcUtil.isNullOrDefault((short) 1));
    }

    // enableSqlLog with negative maxSqlLogLength
    @Test
    public void testEnableSqlLog_NegativeMaxLength() {
        assertDoesNotThrow(() -> JdbcUtil.enableSqlLog(-1));
    }

    // setMinExecutionTimeForSqlPerfLog with custom maxLogLength
    @Test
    public void testSetMinExecutionTimeForSqlPerfLog_WithMaxLength() {
        assertDoesNotThrow(() -> JdbcUtil.setMinExecutionTimeForSqlPerfLog(100, 512));
    }

    // callWithSqlLogDisabled when log already disabled
    @Test
    public void testCallWithSqlLogDisabled_WhenAlreadyDisabled() {
        JdbcUtil.disableSqlLog();
        assertFalse(JdbcUtil.isSqlLogEnabled());

        String result = JdbcUtil.callWithSqlLogDisabled(() -> "test");
        assertEquals("test", result);
        assertFalse(JdbcUtil.isSqlLogEnabled());
    }

    // runWithSqlLogDisabled when log already disabled
    @Test
    public void testRunWithSqlLogDisabled_WhenAlreadyDisabled() {
        JdbcUtil.disableSqlLog();
        assertFalse(JdbcUtil.isSqlLogEnabled());

        boolean[] ran = { false };
        JdbcUtil.runWithSqlLogDisabled(() -> ran[0] = true);
        assertTrue(ran[0]);
        assertFalse(JdbcUtil.isSqlLogEnabled());
    }

    // closeQuietly ResultSet flags - closeResultSet without closeStatement is valid
    @Test
    public void testCloseQuietly_CloseRsWithoutStmt_Valid() throws SQLException {
        assertDoesNotThrow(() -> JdbcUtil.closeQuietly(mockResultSet, false));
    }

    // prepareNamedQuery with ParsedSql
    @Test
    public void testPrepareNamedQuery_WithParsedSql() throws SQLException {
        ParsedSql parsedSql = JdbcUtil.parseSql("SELECT * FROM t WHERE id = :id");
        NamedQuery query = JdbcUtil.prepareNamedQuery(mockConnection, parsedSql);
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

    // close(ResultSet) wraps SQLException as UncheckedSQLException (line 988).
    @Test
    public void testClose_ResultSet_WrapsSQLException() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        doThrow(new SQLException("boom")).when(rs).close();

        assertThrows(com.landawn.abacus.exception.UncheckedSQLException.class, () -> JdbcUtil.close(rs));
    }

    // close(ResultSet, boolean, boolean) — closeConnection without closeStatement throws (line 1052).
    @Test
    public void testClose_ResultSetBooleanFlags_InvalidConfig_Throws() {
        ResultSet rs = mock(ResultSet.class);

        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.close(rs, false, true));
    }

    // close(ResultSet, boolean, boolean) — null rs short-circuits.
    @Test
    public void testClose_ResultSetBooleanFlags_NullResultSet_NoOp() {
        assertDoesNotThrow(() -> JdbcUtil.close((ResultSet) null, true, true));
    }

    // close(ResultSet, boolean, boolean) — getStatement throws SQLException (line 1071).
    @Test
    public void testClose_ResultSetBooleanFlags_GetStatementThrows_WrapsSQLException() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getStatement()).thenThrow(new SQLException("getStatement failed"));

        assertThrows(com.landawn.abacus.exception.UncheckedSQLException.class, () -> JdbcUtil.close(rs, true, false));
    }

    // close(Statement) wraps SQLException (line 1108).
    @Test
    public void testClose_Statement_WrapsSQLException() throws SQLException {
        Statement stmt = mock(Statement.class);
        doThrow(new SQLException("boom")).when(stmt).close();

        assertThrows(com.landawn.abacus.exception.UncheckedSQLException.class, () -> JdbcUtil.close(stmt));
    }

    // close(Connection) wraps SQLException (line 1145).
    @Test
    @SuppressWarnings("deprecation")
    public void testClose_Connection_WrapsSQLException() throws SQLException {
        Connection conn = mock(Connection.class);
        doThrow(new SQLException("boom")).when(conn).close();

        assertThrows(com.landawn.abacus.exception.UncheckedSQLException.class, () -> JdbcUtil.close(conn));
    }

    // close(rs, stmt) — rs.close() throws (line 1186).
    @Test
    public void testClose_ResultSetStatement_RsThrows_WrapsSQLException() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        Statement stmt = mock(Statement.class);
        doThrow(new SQLException("rs boom")).when(rs).close();

        assertThrows(com.landawn.abacus.exception.UncheckedSQLException.class, () -> JdbcUtil.close(rs, stmt));
    }

    // close(rs, stmt) — stmt.close() throws (line 1193).
    @Test
    public void testClose_ResultSetStatement_StmtThrows_WrapsSQLException() throws SQLException {
        Statement stmt = mock(Statement.class);
        doThrow(new SQLException("stmt boom")).when(stmt).close();

        assertThrows(com.landawn.abacus.exception.UncheckedSQLException.class, () -> JdbcUtil.close((ResultSet) null, stmt));
    }

    // close(stmt, conn) — stmt.close() throws (line 1230).
    @Test
    public void testClose_StatementConnection_StmtThrows_WrapsSQLException() throws SQLException {
        Statement stmt = mock(Statement.class);
        Connection conn = mock(Connection.class);
        doThrow(new SQLException("stmt boom")).when(stmt).close();

        assertThrows(com.landawn.abacus.exception.UncheckedSQLException.class, () -> JdbcUtil.close(stmt, conn));
    }

    // close(stmt, conn) — conn.close() throws when stmt is null (line 1237).
    @Test
    public void testClose_StatementConnection_ConnThrows_WrapsSQLException() throws SQLException {
        Connection conn = mock(Connection.class);
        doThrow(new SQLException("conn boom")).when(conn).close();

        assertThrows(com.landawn.abacus.exception.UncheckedSQLException.class, () -> JdbcUtil.close((Statement) null, conn));
    }

    // close(rs, stmt, conn) — rs.close() throws (line 1276).
    @Test
    public void testClose_RsStmtConn_RsThrows_WrapsSQLException() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        doThrow(new SQLException("rs boom")).when(rs).close();

        assertThrows(com.landawn.abacus.exception.UncheckedSQLException.class, () -> JdbcUtil.close(rs, null, null));
    }

    // close(rs, stmt, conn) — stmt.close() throws (line 1283).
    @Test
    public void testClose_RsStmtConn_StmtThrows_WrapsSQLException() throws SQLException {
        Statement stmt = mock(Statement.class);
        doThrow(new SQLException("stmt boom")).when(stmt).close();

        assertThrows(com.landawn.abacus.exception.UncheckedSQLException.class, () -> JdbcUtil.close((ResultSet) null, stmt, (Connection) null));
    }

    // close(rs, stmt, conn) — conn.close() throws (line 1290).
    @Test
    public void testClose_RsStmtConn_ConnThrows_WrapsSQLException() throws SQLException {
        Connection conn = mock(Connection.class);
        doThrow(new SQLException("conn boom")).when(conn).close();

        assertThrows(com.landawn.abacus.exception.UncheckedSQLException.class, () -> JdbcUtil.close((ResultSet) null, (Statement) null, conn));
    }

    // closeQuietly(Statement, Connection) — both null is no-op (lines 1517-1519).
    @Test
    public void testCloseQuietly_StatementConnection_NullSafe() {
        assertDoesNotThrow(() -> JdbcUtil.closeQuietly((Statement) null, (Connection) null));
    }

    // closeQuietly(Statement, Connection) — both throw, neither propagates.
    @Test
    public void testCloseQuietly_StatementConnection_BothThrow_Suppressed() throws SQLException {
        Statement stmt = mock(Statement.class);
        Connection conn = mock(Connection.class);
        doThrow(new SQLException("stmt")).when(stmt).close();
        doThrow(new SQLException("conn")).when(conn).close();

        assertDoesNotThrow(() -> JdbcUtil.closeQuietly(stmt, conn));
    }

    // DEFAULT_SQL_EXTRACTOR — basic statement returns toString() (lines 250-266 happy path).
    @Test
    public void testDefaultSqlExtractor_BasicStatement() throws SQLException {
        Statement stmt = mock(Statement.class);
        when(stmt.toString()).thenReturn("SELECT 1");

        String sql = JdbcUtil.DEFAULT_SQL_EXTRACTOR.apply(stmt);

        assertEquals("SELECT 1", sql);
    }

    // getDriverClassByUrl — unsupported URL throws IllegalArgumentException (lines 843-845).
    @Test
    public void testCreateConnection_UnsupportedUrl_Throws() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.createConnection("jdbc:unknownDB://localhost", "u", "p"));
    }

    // BUG FIX: tableExists must not be fooled by JDBC '_' / '%' wildcard expansion in the
    // tableNamePattern argument to DatabaseMetaData.getTables. Looking up "users_log" must NOT
    // return true when only an unrelated table named "usersXlog" exists.
    @Test
    public void testTableExists_RejectsWildcardFalsePositive() throws SQLException {
        final ResultSet tableRs = mock(ResultSet.class);

        when(mockDatabaseMetaData.getTables(null, null, "users_log", null)).thenReturn(tableRs);
        // The driver returns a row, but for an unrelated table name (a real false positive that
        // happens when '_' is interpreted as the single-char wildcard).
        when(tableRs.next()).thenReturn(true, false);
        when(tableRs.getString("TABLE_NAME")).thenReturn("usersXlog");
        // After the metadata check correctly rejects the false positive, tableExists falls back to
        // a direct SELECT against the safe-qualified name. Simulate the real-DB outcome: that SELECT
        // raises a "doesn't exist" SQLException.
        when(mockConnection.prepareStatement("SELECT 1 FROM users_log WHERE 1 > 2")).thenThrow(new SQLException("Table 'users_log' doesn't exist"));

        assertFalse(JdbcUtil.tableExists(mockConnection, "users_log"), "tableExists must verify the returned TABLE_NAME, not blindly trust rows.next()");
    }

    // BUG FIX: tableExists with wildcard chars in the pattern must accept a row whose TABLE_NAME
    // actually matches the requested name (case-insensitively).
    @Test
    public void testTableExists_AcceptsExactMatchAmongWildcardResults() throws SQLException {
        final ResultSet tableRs = mock(ResultSet.class);

        when(mockDatabaseMetaData.getTables(null, null, "users_log", null)).thenReturn(tableRs);
        when(tableRs.next()).thenReturn(true, true, false);
        when(tableRs.getString("TABLE_NAME")).thenReturn("usersXlog", "USERS_LOG");

        assertTrue(JdbcUtil.tableExists(mockConnection, "users_log"));
    }

    // BUG FIX: getDriverClassByUrl must not misclassify HSQLDB URLs whose database/host portion
    // contains the substring "h2" (e.g., "h2_compat") as H2. With the bug present, the H2 driver
    // would be selected for an HSQLDB URL and the connection attempt would fail because H2 does
    // not recognize "jdbc:hsqldb:..." URLs.
    @Test
    public void testCreateConnection_HsqldbUrlWithH2InDatabaseName_UsesHsqldbDriver() throws SQLException {
        try (Connection conn = JdbcUtil.createConnection("jdbc:hsqldb:mem:h2_compat_db", "sa", "")) {
            assertNotNull(conn);
            // HSQLDB reports its product name as "HSQL Database Engine"; the H2 driver would not
            // accept this URL at all, so reaching this point already proves the routing is correct.
            String productName = conn.getMetaData().getDatabaseProductName();
            assertTrue(productName != null && productName.toLowerCase().contains("hsql"), "Expected HSQLDB driver, but connected to: " + productName);
        }
    }

    // BUG FIX: When close(ResultSet, Statement) encounters failures for both resources,
    // the first exception must be thrown with the second one attached via addSuppressed.
    // The previous implementation silently lost the ResultSet close error when the
    // Statement close also failed.
    @Test
    @Tag("2025")
    @DisplayName("close(ResultSet, Statement): preserves first exception with second suppressed when both throw")
    public void testClose_ResultSetStatement_BothThrow_FirstExceptionWithSecondSuppressed() throws SQLException {
        final SQLException rsEx = new SQLException("rs boom");
        final SQLException stmtEx = new SQLException("stmt boom");

        final ResultSet rs = mock(ResultSet.class);
        final Statement stmt = mock(Statement.class);
        doThrow(rsEx).when(rs).close();
        doThrow(stmtEx).when(stmt).close();

        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, () -> JdbcUtil.close(rs, stmt));
        assertEquals(rsEx, thrown.getCause(), "primary exception should be from ResultSet.close()");
        assertEquals(1, thrown.getCause().getSuppressed().length, "should have one suppressed exception");
        assertEquals(stmtEx, thrown.getCause().getSuppressed()[0], "suppressed exception should be from Statement.close()");
    }

    // BUG FIX: When close(Statement, Connection) encounters failures for both resources,
    // the first exception must be thrown with the second one attached via addSuppressed.
    @Test
    @Tag("2025")
    @DisplayName("close(Statement, Connection): preserves first exception with second suppressed when both throw")
    public void testClose_StatementConnection_BothThrow_FirstExceptionWithSecondSuppressed() throws SQLException {
        final SQLException stmtEx = new SQLException("stmt boom");
        final SQLException connEx = new SQLException("conn boom");

        final Statement stmt = mock(Statement.class);
        final Connection conn = mock(Connection.class);
        doThrow(stmtEx).when(stmt).close();
        doThrow(connEx).when(conn).close();

        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, () -> JdbcUtil.close(stmt, conn));
        assertEquals(stmtEx, thrown.getCause(), "primary exception should be from Statement.close()");
        assertEquals(1, thrown.getCause().getSuppressed().length, "should have one suppressed exception");
        assertEquals(connEx, thrown.getCause().getSuppressed()[0], "suppressed exception should be from Connection.close()");
    }

    // BUG FIX: When close(ResultSet, Statement, Connection) encounters failures for all three resources,
    // the first exception (from ResultSet) must be thrown with the remaining two attached via addSuppressed.
    @Test
    @Tag("2025")
    @DisplayName("close(ResultSet, Statement, Connection): preserves first exception with all others suppressed when all throw")
    public void testClose_ResultSetStatementConnection_AllThrow_PreservesFirstWithRestSuppressed() throws SQLException {
        final SQLException rsEx = new SQLException("rs boom");
        final SQLException stmtEx = new SQLException("stmt boom");
        final SQLException connEx = new SQLException("conn boom");

        final ResultSet rs = mock(ResultSet.class);
        final Statement stmt = mock(Statement.class);
        final Connection conn = mock(Connection.class);
        doThrow(rsEx).when(rs).close();
        doThrow(stmtEx).when(stmt).close();
        doThrow(connEx).when(conn).close();

        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, () -> JdbcUtil.close(rs, stmt, conn));
        assertEquals(rsEx, thrown.getCause(), "primary exception should be from ResultSet.close()");
        assertEquals(2, thrown.getCause().getSuppressed().length, "should have two suppressed exceptions");
        assertEquals(stmtEx, thrown.getCause().getSuppressed()[0], "first suppressed should be from Statement.close()");
        assertEquals(connEx, thrown.getCause().getSuppressed()[1], "second suppressed should be from Connection.close()");
    }

    // Regression: tableExists(metadata, ...) used to verify only TABLE_NAME when the pattern contained
    // a wildcard, so a request for "my_app.users" matched "users" in any schema (e.g., "myXapp") because
    // schemaPattern's `_` is also a JDBC wildcard. The fix verifies TABLE_SCHEM on hit as well.
    @Test
    public void testTableExists_SchemaWildcardVerifiedAgainstActualSchemaName() throws Exception {
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        final ResultSet rows = mock(ResultSet.class);
        when(metadata.getTables(null, "my_app", "users", null)).thenReturn(rows);
        // One matching row: same table name, but wrong schema (wildcard expansion matched "myXapp").
        when(rows.next()).thenReturn(true).thenReturn(false);
        when(rows.getString("TABLE_NAME")).thenReturn("users");
        when(rows.getString("TABLE_SCHEM")).thenReturn("myXapp");

        final java.lang.reflect.Method m = JdbcUtil.class.getDeclaredMethod("tableExists", DatabaseMetaData.class, String.class, String.class, String.class);
        m.setAccessible(true);

        Object result = m.invoke(null, metadata, null, "my_app", "users");

        assertEquals(Boolean.FALSE, result, "wildcard expansion to wrong schema must be rejected");
    }

    @Test
    public void testTableExists_SchemaWildcardAcceptsRealSchemaMatch() throws Exception {
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        final ResultSet rows = mock(ResultSet.class);
        when(metadata.getTables(null, "my_app", "users", null)).thenReturn(rows);
        when(rows.next()).thenReturn(true).thenReturn(false);
        when(rows.getString("TABLE_NAME")).thenReturn("users");
        when(rows.getString("TABLE_SCHEM")).thenReturn("MY_APP"); // case-insensitive match

        final java.lang.reflect.Method m = JdbcUtil.class.getDeclaredMethod("tableExists", DatabaseMetaData.class, String.class, String.class, String.class);
        m.setAccessible(true);

        Object result = m.invoke(null, metadata, null, "my_app", "users");

        assertEquals(Boolean.TRUE, result, "actual schema match must still return true");
    }

    // Regression: streamAllResultSets(stmt) hardcoded isFirstResultSet=true. If the user called
    // stmt.execute(...) and the first result was an update count (so stmt.getResultSet() returns null),
    // the iterator broke out of its loop and the stream terminated empty even when more ResultSets
    // followed. The fix falls through to advance via getMoreResults/getUpdateCount in that case.
    @Test
    public void testIterateAllResultSets_FirstIsUpdateCountAdvancesToNextResultSet() throws Exception {
        final Statement stmt = mock(Statement.class);
        final ResultSet realRs = mock(ResultSet.class);

        // 1st hasNext: getResultSet() returns null (update count); fall through; getUpdateCount()
        // returns >=0 so getMoreResults() runs; 2nd iteration getResultSet() returns the real RS.
        when(stmt.getResultSet()).thenReturn(null).thenReturn(realRs);
        when(stmt.getUpdateCount()).thenReturn(1).thenReturn(-1);
        when(stmt.getMoreResults()).thenReturn(true);
        when(stmt.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(false);

        final java.lang.reflect.Method m = JdbcUtil.class.getDeclaredMethod("iterateAllResultSets", Statement.class, boolean.class);
        m.setAccessible(true);

        @SuppressWarnings("unchecked")
        java.util.Iterator<ResultSet> it = (java.util.Iterator<ResultSet>) m.invoke(null, stmt, true);

        assertTrue(it.hasNext(), "iterator must surface the ResultSet that comes after the leading update count");
        assertEquals(realRs, it.next());
        assertFalse(it.hasNext(), "iterator must terminate when there are no more results");
    }

    // Regression: getDriverClassByUrl used bare substring matching, so a SQL Server URL whose
    // connection string happened to contain "oracle" (e.g., a database name) was routed to the
    // Oracle driver. The fix anchors each check to the `:<vendor>:` scheme token.
    //
    // SQL Server / Oracle drivers may not be on the test classpath, so we assert routing via the
    // class name that getDriverClassByUrl tried to load (surfaced in the exception message).
    @Test
    public void testGetDriverClassByUrl_VendorNameInDatabaseDoesNotConfuseMatcher() throws Exception {
        final java.lang.reflect.Method m = JdbcUtil.class.getDeclaredMethod("getDriverClassByUrl", String.class);
        m.setAccessible(true);

        // jdbc:sqlserver URL with database "oracle_archive" — pre-fix would route to Oracle.
        assertResolvesToVendorDriver(m, "jdbc:sqlserver://host:1433;Database=oracle_archive", "SQLServerDriver",
                "com.microsoft.sqlserver.jdbc.SQLServerDriver");

        // jdbc:sqlserver URL with database "mysql_audit" — pre-fix would route to MySQL.
        assertResolvesToVendorDriver(m, "jdbc:sqlserver://host:1433;Database=mysql_audit", "SQLServerDriver", "com.microsoft.sqlserver.jdbc.SQLServerDriver");

        // Sanity: legitimate MySQL URL still resolves to the MySQL driver.
        assertResolvesToVendorDriver(m, "jdbc:mysql://host:3306/anything", "mysql", "com.mysql.cj.jdbc.Driver");

        // Sanity: legitimate PostgreSQL URL still resolves to the PostgreSQL driver.
        assertResolvesToVendorDriver(m, "jdbc:postgresql://host:5432/anything", "postgresql", "org.postgresql.Driver");
    }

    private static void assertResolvesToVendorDriver(final java.lang.reflect.Method m, final String url, final String expectedSubstring,
            final String fullClassName) throws Exception {
        try {
            Class<?> driverClass = (Class<?>) m.invoke(null, url);
            assertEquals(fullClassName, driverClass.getName(), "URL '" + url + "' should resolve to " + fullClassName);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            // Driver class not on test classpath — accept as long as the attempted class is the right vendor.
            final Throwable cause = ex.getCause();
            assertNotNull(cause);
            assertTrue(cause.getMessage() != null && cause.getMessage().contains(expectedSubstring),
                    "URL '" + url + "' should attempt to load a driver whose name contains '" + expectedSubstring + "' but got: " + cause.getMessage());
        }
    }

    // Regression: stripIdentifierDelimiters did not unescape doubled quotes inside a quoted body,
    // so toQualifiedSqlIdentifier (which re-quotes) would double-escape and emit corrupt SQL.
    @Test
    public void testStripIdentifierDelimiters_UnescapesDoubledQuote() throws Exception {
        final java.lang.reflect.Method m = JdbcUtil.class.getDeclaredMethod("stripIdentifierDelimiters", String.class);
        m.setAccessible(true);

        assertEquals("a\"b", m.invoke(null, "\"a\"\"b\""), "doubled \" inside double-quoted identifier must unescape");
        assertEquals("a`b", m.invoke(null, "`a``b`"), "doubled ` inside backtick identifier must unescape");
        assertEquals("a]b", m.invoke(null, "[a]]b]"), "doubled ] inside bracketed identifier must unescape");
        // Single-quoted bodies without doubled quotes are returned as the literal body.
        assertEquals("plain", m.invoke(null, "\"plain\""));
    }

    // Regression: isTableNotExistsException relied solely on SQLState (which Oracle/SQL Server do not
    // populate reliably) and Locale-dependent message matching. The fix adds vendor errorCode checks
    // and uses Locale.ROOT for lowercasing.
    @Test
    public void testIsTableNotExistsException_VendorErrorCodes() throws Exception {
        final java.lang.reflect.Method m = JdbcUtil.class.getDeclaredMethod("isTableNotExistsException", Throwable.class);
        m.setAccessible(true);

        // Oracle ORA-00942 — message is in a non-English form so the message check would miss it.
        SQLException oracle = new SQLException("ORA-00942: opaque error", "72000", 942);
        assertEquals(Boolean.TRUE, m.invoke(null, oracle));

        // SQL Server error 208 ("Invalid object name") with non-matching SQLState
        SQLException mssql = new SQLException("Invalid object name 'dbo.foo'", "S0002", 208);
        assertEquals(Boolean.TRUE, m.invoke(null, mssql));

        // MySQL ER_NO_SUCH_TABLE
        SQLException mysql = new SQLException("Table 'db.foo' doesn't exist", "42S02", 1146);
        assertEquals(Boolean.TRUE, m.invoke(null, mysql));

        // DB2 SQLState 42704
        SQLException db2 = new SQLException("undefined name", "42704", -204);
        assertEquals(Boolean.TRUE, m.invoke(null, db2));

        // Unrelated SQLException must NOT match.
        SQLException unrelated = new SQLException("primary key violation", "23000", 1062);
        assertEquals(Boolean.FALSE, m.invoke(null, unrelated));
    }

    // Regression: writeBlobToFile / writeClobToFile leaked the InputStream / Reader they obtained
    // from the Blob/Clob, because IOUtil.write closes only the output stream. The fix wraps the
    // input in try-with-resources.
    @Test
    public void testWriteBlobToFile_ClosesBinaryStream() throws Exception {
        final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.io.InputStream tracked = new java.io.ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        Blob blob = mock(Blob.class);
        when(blob.getBinaryStream()).thenReturn(tracked);

        File tmp = File.createTempFile("blob-leak-test", ".bin");
        tmp.deleteOnExit();
        try {
            JdbcUtil.writeBlobToFile(blob, tmp);
        } finally {
            tmp.delete();
        }

        assertTrue(closed.get(), "writeBlobToFile must close the InputStream returned by Blob.getBinaryStream()");
        verify(blob).free();
    }

    @Test
    public void testWriteClobToFile_ClosesCharacterStream() throws Exception {
        final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.io.Reader tracked = new java.io.StringReader("hello") {
            @Override
            public void close() {
                closed.set(true);
                super.close();
            }
        };
        Clob clob = mock(Clob.class);
        when(clob.getCharacterStream()).thenReturn(tracked);

        File tmp = File.createTempFile("clob-leak-test", ".txt");
        tmp.deleteOnExit();
        try {
            JdbcUtil.writeClobToFile(clob, tmp);
        } finally {
            tmp.delete();
        }

        assertTrue(closed.get(), "writeClobToFile must close the Reader returned by Clob.getCharacterStream()");
        verify(clob).free();
    }

    // Regression: getSqlOperation's fallback loop used SqlOperation.name() (which uses Java enum
    // constant naming, e.g., "BEGIN_TRANSACTION" with underscore) instead of sqlToken() ("BEGIN
    // TRANSACTION" with space), so multi-word operations never matched. It also lacked a word
    // boundary so "CREATEX foo" was misclassified as CREATE.
    @Test
    public void testGetSqlOperation_BeginTransactionMatchesSpaceVariant() throws Exception {
        final java.lang.reflect.Method m = JdbcUtil.class.getDeclaredMethod("getSqlOperation", String.class);
        m.setAccessible(true);

        Object op = m.invoke(null, "BEGIN TRANSACTION");
        assertEquals(com.landawn.abacus.query.SqlOperation.BEGIN_TRANSACTION, op);

        op = m.invoke(null, "begin transaction read only");
        assertEquals(com.landawn.abacus.query.SqlOperation.BEGIN_TRANSACTION, op);
    }

    @Test
    public void testGetSqlOperation_RequiresWordBoundary() throws Exception {
        final java.lang.reflect.Method m = JdbcUtil.class.getDeclaredMethod("getSqlOperation", String.class);
        m.setAccessible(true);

        // "CREATEX foo" should NOT match CREATE — there is no word boundary after CREATE.
        assertEquals(com.landawn.abacus.query.SqlOperation.UNKNOWN, m.invoke(null, "CREATEX foo"));

        // But "CREATE TABLE foo (...)" should match CREATE.
        assertEquals(com.landawn.abacus.query.SqlOperation.CREATE, m.invoke(null, "CREATE TABLE foo (id INT)"));

        // Punctuation also counts as a boundary.
        assertEquals(com.landawn.abacus.query.SqlOperation.DROP, m.invoke(null, "DROP(TABLE foo)"));
    }
}
