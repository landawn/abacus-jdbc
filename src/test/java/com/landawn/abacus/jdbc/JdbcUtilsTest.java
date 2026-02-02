package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.RowDataset;
import com.landawn.abacus.util.Throwables;

public class JdbcUtilsTest extends TestBase {

    @Mock
    private DataSource mockDataSource;

    @Mock
    private Connection mockConnection;

    @Mock
    private DatabaseMetaData mockDatabaseMetaData;

    @Mock
    private PreparedStatement mockPreparedStatement;

    @Mock
    private ResultSet mockResultSet;

    @Mock
    private ResultSetMetaData mockResultSetMetaData;

    private Dataset mockDataset;

    @BeforeEach
    public void setUp() throws SQLException {
        MockitoAnnotations.openMocks(this);
        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.getMetaData()).thenReturn(mockDatabaseMetaData);
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("8");
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);
        when(mockConnection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(mockPreparedStatement);
        when(mockPreparedStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.getMetaData()).thenReturn(mockResultSetMetaData);
        mockDataset = Mockito.mock(RowDataset.class);
    }

    // Tests for importData methods with Dataset

    @Test
    public void testImportDataWithDatasetAndDataSource() throws SQLException {
        // Setup
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1", "col2"));
        when(mockDataset.size()).thenReturn(2);
        when(mockDataset.get(anyInt())).thenReturn("value");
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1, 1 });

        String insertSql = "INSERT INTO test_table (col1, col2) VALUES (?, ?)";

        // Execute
        int result = JdbcUtils.importData(mockDataset, mockDataSource, insertSql);

        // Verify
        assertEquals(2, result);
        verify(mockDataSource).getConnection();
        verify(mockConnection).prepareStatement(insertSql);
        verify(mockPreparedStatement, times(2)).addBatch();
        verify(mockPreparedStatement).executeBatch();
    }

    @Test
    public void testImportDataWithDatasetAndConnection() throws SQLException {
        // Setup
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1", "col2"));
        when(mockDataset.size()).thenReturn(1);
        when(mockDataset.get(anyInt())).thenReturn("value");
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        String insertSql = "INSERT INTO test_table (col1, col2) VALUES (?, ?)";

        // Execute
        int result = JdbcUtils.importData(mockDataset, mockConnection, insertSql);

        // Verify
        assertEquals(1, result);
        verify(mockConnection).prepareStatement(insertSql);
        verify(mockPreparedStatement).addBatch();
        verify(mockPreparedStatement).executeBatch();
    }

    @Test
    public void testImportDataWithSelectedColumns() throws SQLException {
        // Setup
        List<String> selectColumns = Arrays.asList("col1");
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1", "col2"));
        when(mockDataset.size()).thenReturn(1);
        when(mockDataset.get(anyInt())).thenReturn("value");
        when(mockDataset.getColumnIndex("col1")).thenReturn(0);
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        String insertSql = "INSERT INTO test_table (col1) VALUES (?)";

        // Execute
        int result = JdbcUtils.importData(mockDataset, selectColumns, mockConnection, insertSql);

        // Verify
        assertEquals(1, result);
        verify(mockConnection).prepareStatement(insertSql);
    }

    @Test
    public void testImportDataWithBatchSizeAndInterval() throws SQLException {
        // Setup
        List<String> selectColumns = Arrays.asList("col1");
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        when(mockDataset.size()).thenReturn(5);
        when(mockDataset.get(anyInt())).thenReturn("value");
        when(mockDataset.getColumnIndex("col1")).thenReturn(0);
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1, 1 });

        String insertSql = "INSERT INTO test_table (col1) VALUES (?)";

        // Execute
        int result = JdbcUtils.importData(mockDataset, selectColumns, mockConnection, insertSql, 2, 10);

        // Verify
        assertEquals(5, result);
        verify(mockPreparedStatement, atLeast(2)).executeBatch();
    }

    @Test
    public void testImportDataWithFilter() throws SQLException, Exception {
        // Setup
        List<String> selectColumns = Arrays.asList("col1");
        Throwables.Predicate<Object[], Exception> filter = row -> "valid".equals(row[0]);

        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        when(mockDataset.size()).thenReturn(3);
        when(mockDataset.get(0)).thenReturn("valid", "invalid", "valid");

        when(mockDataset.getColumnIndex("col1")).thenReturn(0);
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        String insertSql = "INSERT INTO test_table (col1) VALUES (?)";

        // Execute
        int result = JdbcUtils.importData(mockDataset, selectColumns, filter, mockConnection, insertSql, 1, 0);

        // Verify
        assertEquals(2, result);   // Only 2 valid rows
        verify(mockPreparedStatement, times(2)).addBatch();
    }

    @Test
    public void testImportDataWithColumnTypeMap() throws SQLException {
        // Setup
        Map<String, Type> columnTypeMap = new HashMap<>();
        columnTypeMap.put("col1", N.typeOf(String.class));

        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        when(mockDataset.size()).thenReturn(1);
        when(mockDataset.get(anyInt())).thenReturn("value");
        when(mockDataset.getColumnIndex("col1")).thenReturn(0);
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        String insertSql = "INSERT INTO test_table (col1) VALUES (?)";

        // Execute
        int result = JdbcUtils.importData(mockDataset, mockConnection, insertSql, columnTypeMap);

        // Verify
        assertEquals(1, result);
        verify(mockConnection).prepareStatement(insertSql);
    }

    @Test
    public void testImportDataWithCustomStmtSetter() throws SQLException {
        // Setup
        Throwables.BiConsumer<PreparedQuery, Object[], SQLException> stmtSetter = (stmt, row) -> stmt.setString(1, (String) row[0]);

        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        when(mockDataset.size()).thenReturn(1);
        when(mockDataset.get(0)).thenReturn("value");
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        String insertSql = "INSERT INTO test_table (col1) VALUES (?)";

        // Execute
        int result = JdbcUtils.importData(mockDataset, mockConnection, insertSql, stmtSetter);

        // Verify
        assertEquals(1, result);
        verify(mockPreparedStatement).setString(1, "value");
    }

    // Tests for importData methods with File

    @Test
    public void testImportDataFromFileWithDataSource() throws SQLException, IOException, Exception {
        // Setup
        File tempFile = File.createTempFile("test", ".txt");
        tempFile.deleteOnExit();

        Throwables.Function<String, Object[], Exception> func = line -> line.split(",");
        String insertSql = "INSERT INTO test_table (col1, col2) VALUES (?, ?)";

        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        // Execute
        long result = JdbcUtils.importData(tempFile, mockDataSource, insertSql, func);

        // Verify
        assertEquals(0, result);   // Empty file
        verify(mockDataSource).getConnection();
    }

    @Test
    public void testImportDataFromFileWithConnection() throws SQLException, IOException, Exception {
        // Setup
        File tempFile = File.createTempFile("test", ".txt");
        tempFile.deleteOnExit();
        java.nio.file.Files.write(tempFile.toPath(), Arrays.asList("val1,val2", "val3,val4"));

        Throwables.Function<String, Object[], Exception> func = line -> line.split(",");
        String insertSql = "INSERT INTO test_table (col1, col2) VALUES (?, ?)";

        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        // Execute
        long result = JdbcUtils.importData(tempFile, mockConnection, insertSql, 1, 0, func);

        // Verify
        assertEquals(2, result);
        verify(mockPreparedStatement, times(2)).addBatch();
    }

    // Tests for importData methods with Reader

    @Test
    public void testImportDataFromReaderWithDataSource() throws SQLException, IOException, Exception {
        // Setup
        Reader reader = new StringReader("line1\nline2");
        Throwables.Function<String, Object[], Exception> func = line -> new Object[] { line };
        String insertSql = "INSERT INTO test_table (col1) VALUES (?)";

        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        // Execute
        long result = JdbcUtils.importData(reader, mockDataSource, insertSql, func);

        // Verify
        assertEquals(2, result);
        verify(mockDataSource).getConnection();
    }

    @Test
    public void testImportDataFromReaderWithConnection() throws SQLException, IOException, Exception {
        // Setup
        Reader reader = new StringReader("value1\nvalue2\nvalue3");
        Throwables.Function<String, Object[], Exception> func = line -> new Object[] { line };
        String insertSql = "INSERT INTO test_table (col1) VALUES (?)";

        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        // Execute
        long result = JdbcUtils.importData(reader, mockConnection, insertSql, 2, 0, func);

        // Verify
        assertEquals(3, result);
        verify(mockPreparedStatement, times(2)).executeBatch();   // 3 rows with batch size 2
    }

    // Tests for importData methods with Iterator

    @Test
    public void testImportDataFromIteratorWithDataSource() throws SQLException {
        // Setup
        Iterator<String> iter = Arrays.asList("val1", "val2").iterator();
        Throwables.BiConsumer<PreparedQuery, String, SQLException> stmtSetter = (stmt, val) -> stmt.setString(1, val);
        String insertSql = "INSERT INTO test_table (col1) VALUES (?)";

        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        // Execute
        long result = JdbcUtils.importData(iter, mockDataSource, insertSql, stmtSetter);

        // Verify
        assertEquals(2, result);
        verify(mockDataSource).getConnection();
    }

    @Test
    public void testImportDataFromIteratorWithConnection() throws SQLException {
        // Setup
        Iterator<Integer> iter = Arrays.asList(1, 2, 3, 4, 5).iterator();
        Throwables.BiConsumer<PreparedQuery, Integer, SQLException> stmtSetter = (stmt, val) -> stmt.setInt(1, val);
        String insertSql = "INSERT INTO test_table (num) VALUES (?)";

        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        // Execute
        long result = JdbcUtils.importData(iter, mockConnection, insertSql, 3, 10, stmtSetter);

        // Verify
        assertEquals(5, result);
        verify(mockPreparedStatement, times(2)).executeBatch();   // 5 rows with batch size 3
    }

    // Tests for importCSV methods

    @Test
    public void testImportCSVFromFileWithDataSource() throws SQLException, IOException {
        // Setup
        File tempFile = File.createTempFile("test", ".csv");
        tempFile.deleteOnExit();
        java.nio.file.Files.write(tempFile.toPath(), Arrays.asList("col1,col2", "val1,val2"));

        Throwables.BiConsumer<PreparedQuery, String[], SQLException> stmtSetter = (stmt, row) -> {
            stmt.setString(1, row[0]);
            stmt.setString(2, row[1]);
        };
        String insertSql = "INSERT INTO test_table (col1, col2) VALUES (?, ?)";

        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        // Execute
        long result = JdbcUtils.importCSV(tempFile, mockDataSource, insertSql, stmtSetter);

        // Verify
        assertEquals(1, result);   // 1 data row (header skipped)
        verify(mockDataSource).getConnection();
    }

    @Test
    public void testImportCSVFromFileWithFilter() throws SQLException, IOException, Exception {
        // Setup
        File tempFile = File.createTempFile("test", ".csv");
        tempFile.deleteOnExit();
        java.nio.file.Files.write(tempFile.toPath(), Arrays.asList("col1,col2", "valid,val2", "invalid,val3", "valid,val4"));

        Throwables.Predicate<String[], Exception> filter = row -> "valid".equals(row[0]);
        Throwables.BiConsumer<PreparedQuery, String[], SQLException> stmtSetter = (stmt, row) -> {
            stmt.setString(1, row[0]);
            stmt.setString(2, row[1]);
        };
        String insertSql = "INSERT INTO test_table (col1, col2) VALUES (?, ?)";

        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        // Execute
        long result = JdbcUtils.importCSV(tempFile, filter, mockPreparedStatement, 1, 0, stmtSetter);

        // Verify
        assertEquals(2, result);   // 2 valid rows
        verify(mockPreparedStatement, times(2)).addBatch();
    }

    @Test
    public void testImportCSVFromReaderWithDataSource() throws SQLException, IOException {
        // Setup
        Reader reader = new StringReader("col1,col2\nval1,val2\nval3,val4");
        Throwables.BiConsumer<PreparedQuery, String[], SQLException> stmtSetter = (stmt, row) -> {
            stmt.setString(1, row[0]);
            stmt.setString(2, row[1]);
        };
        String insertSql = "INSERT INTO test_table (col1, col2) VALUES (?, ?)";

        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        // Execute
        long result = JdbcUtils.importCSV(reader, mockDataSource, insertSql, stmtSetter);

        // Verify
        assertEquals(2, result);   // 2 data rows
        verify(mockDataSource).getConnection();
    }

    // Tests for exportCSV methods

    @Test
    public void testExportCSVToFileWithDataSource() throws SQLException, IOException {
        // Setup
        File tempFile = File.createTempFile("export", ".csv");
        tempFile.deleteOnExit();

        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("col1");
        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("col2");
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn("val1", "val3");
        when(mockResultSet.getObject(2)).thenReturn("val2", "val4");

        String querySql = "SELECT * FROM test_table";

        // Execute
        long result = JdbcUtils.exportCSV(mockDataSource, querySql, tempFile);

        // Verify
        assertEquals(2, result);
        assertTrue(tempFile.exists());
        verify(mockDataSource).getConnection();
    }

    @Test
    public void testExportCSVToFileWithSelectedColumns() throws SQLException, IOException {
        // Setup
        File tempFile = File.createTempFile("export", ".csv");
        tempFile.deleteOnExit();
        Collection<String> selectColumns = Arrays.asList("col1");

        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("col1");
        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("col2");
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn("val1");

        String querySql = "SELECT * FROM test_table";

        // Execute
        long result = JdbcUtils.exportCSV(mockConnection, querySql, selectColumns, tempFile);

        // Verify
        assertEquals(1, result);
        assertTrue(tempFile.exists());
    }

    @Test
    public void testExportCSVToWriter() throws SQLException, IOException {
        // Setup
        Writer writer = new StringWriter();

        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("col1");
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn("val1");
        when(mockResultSet.getString(1)).thenReturn("val2");

        // Execute
        long result = JdbcUtils.exportCSV(mockResultSet, writer);

        // Verify
        assertEquals(2, result);
        String csvContent = writer.toString();
        assertTrue(csvContent.contains("col1"));
        assertTrue(csvContent.contains("val1"));
        assertTrue(csvContent.contains("val2"));
    }

    @Test
    public void testExportCSVWithNullValues() throws SQLException, IOException {
        // Setup
        Writer writer = new StringWriter();

        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("col1");
        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("col2");
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn("val1");
        when(mockResultSet.getObject(2)).thenReturn(null);

        // Execute
        long result = JdbcUtils.exportCSV(mockResultSet, writer);

        // Verify
        assertEquals(1, result);
        String csvContent = writer.toString();
        assertTrue(csvContent.contains("null"));
    }

    // Tests for copy methods

    @Test
    public void testCopyBetweenDataSourcesSameTable() throws SQLException {
        // Setup
        DataSource targetDataSource = mock(DataSource.class);
        Connection targetConnection = mock(Connection.class);
        PreparedStatement targetPreparedStatement = mock(PreparedStatement.class);

        when(targetDataSource.getConnection()).thenReturn(targetConnection);
        when(targetConnection.prepareStatement(anyString())).thenReturn(targetPreparedStatement);
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(anyInt())).thenReturn("value");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(targetPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        // Execute
        long result = JdbcUtils.copy(mockDataSource, targetDataSource, "test_table");

        // Verify
        assertEquals(1, result);
        verify(mockDataSource, times(2)).getConnection();
        verify(targetDataSource).getConnection();
    }

    @Test
    public void testCopyBetweenDataSourcesDifferentTables() throws SQLException {
        // Setup
        DataSource targetDataSource = mock(DataSource.class);
        Connection targetConnection = mock(Connection.class);
        PreparedStatement targetPreparedStatement = mock(PreparedStatement.class);

        when(targetDataSource.getConnection()).thenReturn(targetConnection);
        when(targetConnection.prepareStatement(anyString())).thenReturn(targetPreparedStatement);
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(anyInt())).thenReturn("value");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(targetPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        // Execute
        long result = JdbcUtils.copy(mockDataSource, targetDataSource, "source_table", "target_table");

        // Verify
        assertEquals(2, result);
    }

    @Test
    public void testCopyWithSelectedColumns() throws SQLException {
        // Setup
        DataSource targetDataSource = mock(DataSource.class);
        Connection targetConnection = mock(Connection.class);
        PreparedStatement targetPreparedStatement = mock(PreparedStatement.class);
        Collection<String> selectColumns = Arrays.asList("col1", "col2");

        when(targetDataSource.getConnection()).thenReturn(targetConnection);
        when(targetConnection.prepareStatement(anyString())).thenReturn(targetPreparedStatement);
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(anyInt())).thenReturn("value");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);
        when(targetPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        // Execute
        long result = JdbcUtils.copy(mockDataSource, targetDataSource, "source_table", "target_table", selectColumns);

        // Verify
        assertEquals(1, result);
    }

    @Test
    public void testCopyWithCustomSQL() throws SQLException {
        // Setup
        DataSource targetDataSource = mock(DataSource.class);
        Connection targetConnection = mock(Connection.class);
        PreparedStatement targetPreparedStatement = mock(PreparedStatement.class);

        when(targetDataSource.getConnection()).thenReturn(targetConnection);
        when(targetConnection.prepareStatement(anyString())).thenReturn(targetPreparedStatement);
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(anyInt())).thenReturn("value");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(targetPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        String selectSql = "SELECT * FROM source WHERE active = true";
        String insertSql = "INSERT INTO target (col1) VALUES (?)";

        // Execute
        long result = JdbcUtils.copy(mockDataSource, selectSql, targetDataSource, insertSql);

        // Verify
        assertEquals(1, result);
    }

    @Test
    public void testCopyWithCustomStmtSetter() throws SQLException {
        // Setup
        DataSource targetDataSource = mock(DataSource.class);
        Connection targetConnection = mock(Connection.class);
        PreparedStatement targetPreparedStatement = mock(PreparedStatement.class);

        when(targetDataSource.getConnection()).thenReturn(targetConnection);
        when(targetConnection.prepareStatement(anyString())).thenReturn(targetPreparedStatement);
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getString(1)).thenReturn("value");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(targetPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        Throwables.BiConsumer<PreparedQuery, ResultSet, SQLException> stmtSetter = (pq, rs) -> pq.setString(1, rs.getString(1).toUpperCase());

        String selectSql = "SELECT name FROM source";
        String insertSql = "INSERT INTO target (name_upper) VALUES (?)";

        // Execute
        long result = JdbcUtils.copy(mockDataSource, selectSql, targetDataSource, insertSql, stmtSetter);

        // Verify
        assertEquals(1, result);
        verify(targetPreparedStatement).setString(1, "VALUE");
    }

    @Test
    public void testCopyBetweenConnections() throws SQLException {
        // Setup
        Connection targetConnection = mock(Connection.class);
        PreparedStatement targetPreparedStatement = mock(PreparedStatement.class);

        when(targetConnection.prepareStatement(anyString())).thenReturn(targetPreparedStatement);
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(anyInt())).thenReturn("value");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(targetPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        // Execute
        long result = JdbcUtils.copy(mockConnection, targetConnection, "test_table");

        // Verify
        assertEquals(2, result);
    }

    @Test
    public void testCopyBetweenPreparedStatements() throws SQLException {
        // Setup
        PreparedStatement targetPreparedStatement = mock(PreparedStatement.class);
        when(mockResultSet.next()).thenReturn(true, true, true, false);
        when(mockResultSet.getObject(anyInt())).thenReturn("value");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(targetPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        Throwables.BiConsumer<PreparedQuery, ResultSet, SQLException> stmtSetter = (pq, rs) -> pq.setObject(1, rs.getObject(1));

        // Execute
        long result = JdbcUtils.copy(mockPreparedStatement, targetPreparedStatement, 2, 10, stmtSetter);

        // Verify
        assertEquals(3, result);
        verify(targetPreparedStatement, times(2)).executeBatch();   // 3 rows with batch size 2
    }

    // Test for createParamSetter

    @Test
    public void testCreateParamSetter() throws SQLException {
        // Setup
        Jdbc.ColumnGetter<String> columnGetter = (rs, columnIndex) -> rs.getString(columnIndex).toUpperCase();

        PreparedQuery preparedQuery = mock(PreparedQuery.class);
        when(mockResultSet.getString(1)).thenReturn("value1");
        when(mockResultSet.getString(2)).thenReturn("value2");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);

        // Execute
        Throwables.BiConsumer<PreparedQuery, ResultSet, SQLException> setter = JdbcUtils.createParamSetter(columnGetter);
        setter.accept(preparedQuery, mockResultSet);

        // Verify
        verify(preparedQuery).setObject(1, "VALUE1");
        verify(preparedQuery).setObject(2, "VALUE2");
    }

    // Edge case tests

    @Test
    public void testImportDataWithZeroBatchSize() {
        assertThrows(IllegalArgumentException.class, () -> {
            JdbcUtils.importData(mockDataset, Arrays.asList("col1"), mockConnection, "INSERT INTO test VALUES (?)", 0, 0);
        });
    }

    @Test
    public void testImportDataWithNegativeBatchInterval() {
        assertThrows(IllegalArgumentException.class, () -> {
            JdbcUtils.importData(mockDataset, Arrays.asList("col1"), mockConnection, "INSERT INTO test VALUES (?)", 1, -1);
        });
    }

    @Test
    public void testImportDataWithEmptyDataset() throws SQLException {
        // Setup
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        when(mockDataset.size()).thenReturn(0);

        String insertSql = "INSERT INTO test_table (col1) VALUES (?)";

        // Execute
        int result = JdbcUtils.importData(mockDataset, mockConnection, insertSql);

        // Verify
        assertEquals(0, result);
        verify(mockPreparedStatement, never()).addBatch();
        verify(mockPreparedStatement, never()).executeBatch();
    }

    @Test
    public void testExportCSVWithInvalidColumn() throws SQLException, IOException {
        // Setup
        Writer writer = new StringWriter();
        Collection<String> selectColumns = Arrays.asList("invalid_column");

        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("col1");

        // Execute & Verify
        assertThrows(IllegalArgumentException.class, () -> {
            JdbcUtils.exportCSV(mockResultSet, selectColumns, writer);
        });
    }

    @Test
    public void testImportDataWithNullFilter() throws SQLException, Exception {
        // Setup
        List<String> selectColumns = Arrays.asList("col1");
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        when(mockDataset.size()).thenReturn(1);
        when(mockDataset.get(0)).thenReturn("value");
        when(mockDataset.getColumnIndex("col1")).thenReturn(0);
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        String insertSql = "INSERT INTO test_table (col1) VALUES (?)";

        // Execute
        int result = JdbcUtils.importData(mockDataset, selectColumns, null, mockConnection, insertSql, 1, 0);

        // Verify
        assertEquals(1, result);
        verify(mockPreparedStatement).addBatch();
    }

    @Test
    public void testImportCSVWithSkippedRows() throws SQLException, IOException, Exception {
        // Setup
        Reader reader = new StringReader("col1,col2\nval1,val2\nval3,val4");
        Throwables.Predicate<String[], Exception> filter = row -> row[0].equals("val1");
        Throwables.BiConsumer<PreparedQuery, String[], SQLException> stmtSetter = (stmt, row) -> {
            stmt.setString(1, row[0]);
            stmt.setString(2, row[1]);
        };

        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        // Execute
        long result = JdbcUtils.importCSV(reader, filter, mockPreparedStatement, 1, 0, stmtSetter);

        // Verify
        assertEquals(1, result);   // Only 1 row matches filter
        verify(mockPreparedStatement, times(1)).addBatch();
    }

    @Test
    public void testCopyWithLargeBatchInterval() throws SQLException {
        // Setup
        PreparedStatement targetPreparedStatement = mock(PreparedStatement.class);
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(anyInt())).thenReturn("value");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(targetPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        Throwables.BiConsumer<PreparedQuery, ResultSet, SQLException> stmtSetter = (pq, rs) -> pq.setObject(1, rs.getObject(1));

        long startTime = System.currentTimeMillis();

        // Execute
        long result = JdbcUtils.copy(mockPreparedStatement, targetPreparedStatement, 1, 50, stmtSetter);   // 50ms interval

        long endTime = System.currentTimeMillis();

        // Verify
        assertEquals(2, result);
        verify(targetPreparedStatement, times(2)).executeBatch();
        assertTrue((endTime - startTime) >= 50);   // At least one interval delay
    }
}