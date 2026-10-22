package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.sql.DataSource;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.RowDataset;
import com.landawn.abacus.util.Throwables;

class DataTransferImportTest extends DataTransferUtilTestSupport {

    @Test
    public void testSelectedColumnsAreValidatedBeforeLaterImportArguments() {
        final Dataset dataset = Dataset.rows(List.of("id"), new Object[][] { { 1 } });
        final Connection unusedConnection = mock(Connection.class);
        final PreparedStatement unusedStatement = mock(PreparedStatement.class);

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.importData(dataset, List.of("missing"), null, unusedConnection, "INSERT INTO users VALUES (?)", 0, 0)).getMessage()
                        .contains("missing"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.importData(dataset, List.of("missing"), null, unusedStatement, 0, 0))
                .getMessage()
                .contains("missing"));
        verifyNoInteractions(unusedConnection, unusedStatement);
    }

    // Tests for importData methods with Dataset

    @Test
    public void testImportDataWithDatasetAndDataSource() throws SQLException {
        // Setup
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1", "col2"));
        when(mockDataset.size()).thenReturn(2);
        when(mockDataset.get(anyInt(), anyInt())).thenReturn("value");
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1, 1 });

        String insertSql = "INSERT INTO test_table (col1, col2) VALUES (?, ?)";

        // Execute
        int result = DataTransferUtil.importData(mockDataset, mockDataSource, insertSql);

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
        when(mockDataset.get(anyInt(), anyInt())).thenReturn("value");
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        String insertSql = "INSERT INTO test_table (col1, col2) VALUES (?, ?)";

        // Execute
        int result = DataTransferUtil.importData(mockDataset, mockConnection, insertSql);

        // Verify
        assertEquals(1, result);
        verify(mockConnection).prepareStatement(insertSql);
        verify(mockPreparedStatement).addBatch();
        verify(mockPreparedStatement).executeBatch();
    }

    @Test
    public void testImportDataWithSelectedColumns() throws SQLException {
        // Setup
        List<String> selectColumnNames = Arrays.asList("col1");
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1", "col2"));
        when(mockDataset.size()).thenReturn(1);
        when(mockDataset.get(anyInt(), anyInt())).thenReturn("value");
        when(mockDataset.getColumnIndex("col1")).thenReturn(0);
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        String insertSql = "INSERT INTO test_table (col1) VALUES (?)";

        // Execute
        int result = DataTransferUtil.importData(mockDataset, selectColumnNames, mockConnection, insertSql);

        // Verify
        assertEquals(1, result);
        verify(mockConnection).prepareStatement(insertSql);
    }

    @Test
    public void testImportDataWithBatchSizeAndInterval() throws SQLException {
        // Setup
        List<String> selectColumnNames = Arrays.asList("col1");
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        when(mockDataset.size()).thenReturn(5);
        when(mockDataset.get(anyInt(), anyInt())).thenReturn("value");
        when(mockDataset.getColumnIndex("col1")).thenReturn(0);
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1, 1 });

        String insertSql = "INSERT INTO test_table (col1) VALUES (?)";

        // Execute
        int result = DataTransferUtil.importData(mockDataset, selectColumnNames, mockConnection, insertSql, 2, 10);

        // Verify
        assertEquals(5, result);
        verify(mockPreparedStatement, atLeast(2)).executeBatch();
    }

    @Test
    public void testImportDataWithFilter() throws SQLException, Exception {
        // Setup
        List<String> selectColumnNames = Arrays.asList("col1");
        Predicate<Object[]> filter = row -> "valid".equals(row[0]);

        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        when(mockDataset.size()).thenReturn(3);
        when(mockDataset.get(anyInt(), eq(0))).thenReturn("valid", "invalid", "valid");

        when(mockDataset.getColumnIndex("col1")).thenReturn(0);
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        String insertSql = "INSERT INTO test_table (col1) VALUES (?)";

        // Execute
        int result = DataTransferUtil.importData(mockDataset, selectColumnNames, filter, mockConnection, insertSql, 1, 0);

        // Verify
        assertEquals(2, result); // Only 2 valid rows
        verify(mockPreparedStatement, times(2)).addBatch();
    }

    @Test
    public void testImportDataWithColumnTypeMap() throws SQLException {
        // Setup
        Map<String, Type> columnTypeMap = new HashMap<>();
        columnTypeMap.put("col1", N.typeOf(String.class));

        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        when(mockDataset.size()).thenReturn(1);
        when(mockDataset.get(anyInt(), anyInt())).thenReturn("value");
        when(mockDataset.getColumnIndex("col1")).thenReturn(0);
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        String insertSql = "INSERT INTO test_table (col1) VALUES (?)";

        // Execute
        int result = DataTransferUtil.importData(mockDataset, mockConnection, insertSql, columnTypeMap);

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
        when(mockDataset.get(anyInt(), eq(0))).thenReturn("value");
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        String insertSql = "INSERT INTO test_table (col1) VALUES (?)";

        // Execute
        int result = DataTransferUtil.importData(mockDataset, mockConnection, insertSql, stmtSetter);

        // Verify
        assertEquals(1, result);
        verify(mockPreparedStatement).setString(1, "value");
    }

    @Test
    public void testImportDataWithDatasetAndPreparedStatement() throws SQLException {
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        when(mockDataset.size()).thenReturn(1);
        when(mockDataset.get(anyInt(), eq(0))).thenReturn("value");
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        final int result = DataTransferUtil.importData(mockDataset, mockPreparedStatement);

        assertEquals(1, result);
        verify(mockPreparedStatement).addBatch();
        verify(mockPreparedStatement).executeBatch();
    }

    @Test
    public void testImportDataWithSelectedColumnsAndPreparedStatementBatchConfig() throws SQLException {
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        when(mockDataset.size()).thenReturn(2);
        when(mockDataset.get(anyInt(), eq(0))).thenReturn("first", "second");
        when(mockDataset.getColumnIndex("col1")).thenReturn(0);
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 }, new int[] { 1 });

        final int result = DataTransferUtil.importData(mockDataset, List.of("col1"), mockPreparedStatement, 1, 0);

        assertEquals(2, result);
        verify(mockPreparedStatement, times(2)).addBatch();
        verify(mockPreparedStatement, times(2)).executeBatch();
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
        long result = DataTransferUtil.importData(iter, mockDataSource, insertSql, stmtSetter);

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
        long result = DataTransferUtil.importData(iter, mockConnection, insertSql, 3, 10, stmtSetter);

        // Verify
        assertEquals(5, result);
        verify(mockPreparedStatement, times(2)).executeBatch(); // 5 rows with batch size 3
    }

    // importData(Dataset, PreparedStatement, Map) - delegates to batch version (line 700-701)
    @Test
    public void testImportDataWithDatasetPreparedStatementAndTypeMap() throws SQLException {
        Map<String, Type> columnTypeMap = new HashMap<>();
        columnTypeMap.put("col1", N.typeOf(String.class));

        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        when(mockDataset.size()).thenReturn(1);
        when(mockDataset.get(anyInt(), eq(0))).thenReturn("value");
        when(mockDataset.getColumnIndex("col1")).thenReturn(0);
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        int result = DataTransferUtil.importData(mockDataset, mockPreparedStatement, columnTypeMap);

        assertEquals(1, result);
        verify(mockPreparedStatement).addBatch();
        verify(mockPreparedStatement).executeBatch();
    }

    // importData with columnTypeMap that doesn't include all dataset columns → unmapped columns bound by runtime type
    @Test
    public void testImportDataWithColumnTypeMap_DefaultsToObjectType() throws SQLException {
        Map<String, Type> columnTypeMap = new HashMap<>();
        columnTypeMap.put("col1", N.typeOf(String.class));
        // Dataset has col1 AND col2; columnTypeMap only has col1 → col2 is bound by its runtime type
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1", "col2"));
        when(mockDataset.size()).thenReturn(1);
        when(mockDataset.get(anyInt(), eq(0))).thenReturn("value1"); // col1 value
        when(mockDataset.get(anyInt(), eq(1))).thenReturn(42); // col2 value (bound by runtime type)
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        int result = DataTransferUtil.importData(mockDataset, mockPreparedStatement, columnTypeMap);
        assertEquals(1, result);
    }

    /**
     * Regression test for the loop-condition bug in
     * {@code importData(Dataset, Predicate, PreparedStatement, int, long, BiConsumer)}.
     *
     * <p>Before the fix the loop condition was {@code result < size && i < size}.
     * Because {@code result} counts only accepted rows, the {@code result < size} guard
     * could never fire (result &lt;= i always), but it was semantically wrong: if future
     * refactoring made {@code result} skip ahead the guard would terminate the scan early,
     * causing rows at the end of the dataset to be silently skipped.  The fix removes the
     * redundant {@code result < size} clause so the loop always visits every row.</p>
     *
     * <p>This test verifies that when a filter rejects some rows in a dataset all rows
     * are still examined (every row index is read) and only the accepted rows
     * are batched.</p>
     */
    @Test
    public void testImportDataWithFilterAndStmtSetterExaminesAllRows() throws SQLException, Exception {
        // Setup: 4-row single-column dataset; filter accepts rows whose value equals "keep"
        // row 0 -> "keep" (accepted), row 1 -> "skip", row 2 -> "skip", row 3 -> "keep" (accepted)
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        when(mockDataset.size()).thenReturn(4);
        // get(row, 0) is called once per iteration (column index 0 in the inner loop)
        when(mockDataset.get(anyInt(), eq(0))).thenReturn("keep", "skip", "skip", "keep");
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 }, new int[] { 1 });

        Throwables.BiConsumer<PreparedQuery, Object[], SQLException> stmtSetter = (pq, row) -> pq.setString(1, (String) row[0]);
        Predicate<Object[]> filter = row -> "keep".equals(row[0]);

        // Execute: batchSize=1 so executeBatch is called after every accepted row
        int result = DataTransferUtil.importData(mockDataset, filter, mockPreparedStatement, 1, 0L, stmtSetter);

        // Verify: exactly 2 rows accepted
        assertEquals(2, result);
        // addBatch must be called exactly twice (once per accepted row)
        verify(mockPreparedStatement, times(2)).addBatch();
        // every row index (0, 1, 2, 3) must be read — proves all rows were examined
        verify(mockDataset).get(0, 0);
        verify(mockDataset).get(1, 0);
        verify(mockDataset).get(2, 0);
        verify(mockDataset).get(3, 0);
        // rows are read by index, so the caller's Dataset cursor is never moved
        verify(mockDataset, never()).moveToRow(anyInt());
    }

    // importData(Dataset, PreparedStatement, stmtSetter) delegating overload (DataTransferUtil L874).
    @Test
    public void testImportData_DatasetStmtStmtSetter() throws SQLException {
        final Throwables.BiConsumer<PreparedQuery, Object[], SQLException> stmtSetter = (q, row) -> q.setString(1, (String) row[0]);
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        when(mockDataset.size()).thenReturn(1);
        when(mockDataset.get(anyInt(), eq(0))).thenReturn("value");
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        final int result = DataTransferUtil.importData(mockDataset, mockPreparedStatement, stmtSetter);

        assertEquals(1, result);
        verify(mockPreparedStatement).setString(1, "value");
    }

    // importData(Dataset, PreparedStatement, batchSize, batchInterval, stmtSetter) delegating overload
    // (DataTransferUtil L904).
    @Test
    public void testImportData_DatasetStmtBatchConfigStmtSetter() throws SQLException {
        final Throwables.BiConsumer<PreparedQuery, Object[], SQLException> stmtSetter = (q, row) -> q.setString(1, (String) row[0]);
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        when(mockDataset.size()).thenReturn(2);
        when(mockDataset.get(anyInt(), eq(0))).thenReturn("a", "b");
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 }, new int[] { 1 });

        final int result = DataTransferUtil.importData(mockDataset, mockPreparedStatement, 1, 0L, stmtSetter);

        assertEquals(2, result);
        verify(mockPreparedStatement, times(2)).setString(anyInt(), anyString());
    }

    @Test
    public void testImportDataColumnTypeMapInvalidKeyIsRejectedEvenForEmptyDataset() throws SQLException {
        // Regression: a columnTypeMap key that is not a column of the dataset must be rejected per the documented
        // contract (@throws IllegalArgumentException), even when the dataset is empty. Previously the validation
        // lived inside the per-row setter, which is never invoked for an empty dataset, so the bad key slipped through.
        final Map<String, Type> columnTypeMap = new HashMap<>();
        columnTypeMap.put("nonexistent_column", N.typeOf(String.class));

        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        when(mockDataset.size()).thenReturn(0);

        final String insertSql = "INSERT INTO test_table (col1) VALUES (?)";

        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.importData(mockDataset, mockConnection, insertSql, columnTypeMap));
    }

    @Test
    public void testImportDataNullColumnTypeIsRejectedEvenForEmptyDataset() throws SQLException {
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        when(mockDataset.size()).thenReturn(0);
        final Map<String, Type> columnTypeMap = new HashMap<>();
        columnTypeMap.put("col1", null);

        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.importData(mockDataset, mockPreparedStatement, columnTypeMap));
        verify(mockPreparedStatement, never()).addBatch();
    }

    @Test
    public void testImportDataNullColumnTypeMapIsTreatedAsEmpty() throws SQLException {
        // Regression: a null columnTypeMap must be tolerated (treated like an empty map, i.e. every column is bound
        // by its value's runtime type), consistent with the up-front validation that already skips a null/empty map.
        // Previously the per-row setter dereferenced columnTypeMap unconditionally -> NPE on the first row of a
        // non-empty dataset.
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        when(mockDataset.size()).thenReturn(1);
        when(mockDataset.get(anyInt(), anyInt())).thenReturn("value");
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        final String insertSql = "INSERT INTO test_table (col1) VALUES (?)";

        final int result = DataTransferUtil.importData(mockDataset, mockConnection, insertSql, (Map<String, Type>) null);

        assertEquals(1, result);
        verify(mockConnection).prepareStatement(insertSql);
    }

    @Test
    public void testImportDataWithSelectedColumnsRejectsUnknownColumn() {
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));

        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.importData(mockDataset, List.of("missing"), mockPreparedStatement));
    }

    // An empty column selection imports every dataset column, as importFrom(dataset).columns(emptyList) does, instead of
    // binding no parameters for each row.
    @Test
    public void testImportDataWithEmptyColumnNamesImportsAllColumnsLikeBuilder() throws SQLException {
        final Dataset dataset = Dataset.rows(List.of("a", "b"), new Object[][] { { "a1", "b1" }, { "a2", "b2" } });
        final List<String> noColumns = List.of();
        final String insertSql = "INSERT INTO t (a, b) VALUES (?, ?)";
        final Predicate<Object[]> acceptAll = row -> true;

        final List<Throwables.Function<PreparedStatement, Integer, SQLException>> imports = List.of( //
                stmt -> DataTransferUtil.importData(dataset, noColumns, connectionFor(stmt), insertSql),
                stmt -> DataTransferUtil.importData(dataset, noColumns, connectionFor(stmt), insertSql, 1, 0),
                stmt -> DataTransferUtil.importData(dataset, noColumns, acceptAll, connectionFor(stmt), insertSql, 10, 0),
                stmt -> DataTransferUtil.importData(dataset, noColumns, stmt), stmt -> DataTransferUtil.importData(dataset, noColumns, stmt, 10, 0),
                stmt -> DataTransferUtil.importData(dataset, noColumns, acceptAll, stmt, 10, 0),
                stmt -> DataTransferUtil.importFrom(dataset).columns(noColumns).to(stmt));

        for (final Throwables.Function<PreparedStatement, Integer, SQLException> importer : imports) {
            final PreparedStatement stmt = mock(PreparedStatement.class);
            when(stmt.executeBatch()).thenReturn(new int[] { 1, 1 });

            assertEquals(2, importer.apply(stmt));

            verify(stmt).setString(1, "a1");
            verify(stmt).setString(2, "b1");
            verify(stmt).setString(1, "a2");
            verify(stmt).setString(2, "b2");
            verify(stmt, times(2)).addBatch();
        }
    }

    private static Connection connectionFor(final PreparedStatement stmt) throws SQLException {
        final Connection conn = mock(Connection.class);
        when(conn.prepareStatement(anyString())).thenReturn(stmt);
        return conn;
    }

    // Edge case tests

    @Test
    public void testImportDataWithZeroBatchSize() {
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        assertThrows(IllegalArgumentException.class, () -> {
            DataTransferUtil.importData(mockDataset, Arrays.asList("col1"), mockConnection, "INSERT INTO test VALUES (?)", 0, 0);
        });
    }

    @Test
    public void testImportDataWithNegativeBatchInterval() {
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        assertThrows(IllegalArgumentException.class, () -> {
            DataTransferUtil.importData(mockDataset, Arrays.asList("col1"), mockConnection, "INSERT INTO test VALUES (?)", 1, -1);
        });
    }

    @Test
    public void testImportDataWithEmptyDataset() throws SQLException {
        // Setup
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        when(mockDataset.size()).thenReturn(0);

        String insertSql = "INSERT INTO test_table (col1) VALUES (?)";

        // Execute
        int result = DataTransferUtil.importData(mockDataset, mockConnection, insertSql);

        // Verify
        assertEquals(0, result);
        verify(mockPreparedStatement, never()).addBatch();
        verify(mockPreparedStatement, never()).executeBatch();
    }

    @Test
    public void testImportDataWithNullFilter() {
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        List<String> selectColumnNames = Arrays.asList("col1");
        String insertSql = "INSERT INTO test_table (col1) VALUES (?)";

        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.importData(mockDataset, selectColumnNames, null, mockConnection, insertSql, 1, 0));
    }

    // importData with columnTypeMap that has a column NOT in the dataset → IllegalArgumentException (L810-812)
    @Test
    public void testImportDataWithColumnTypeMap_ExtraColumnInMap_Throws() throws SQLException {
        Map<String, Type> columnTypeMap = new HashMap<>();
        columnTypeMap.put("col1", N.typeOf(String.class));
        columnTypeMap.put("nonExistent", N.typeOf(String.class)); // not in dataset
        // Dataset only has col1
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        when(mockDataset.size()).thenReturn(1);
        when(mockDataset.get(anyInt(), eq(0))).thenReturn("value");
        when(mockDataset.getColumnIndex("col1")).thenReturn(0);
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.importData(mockDataset, mockPreparedStatement, columnTypeMap));
    }

    // Regression: DataTransferUtil.importData(Dataset, ..., PreparedStatement, ...) used to delegate the
    // addBatch call via a PreparedQuery wrapper. AbstractQuery.addBatch closes the underlying
    // statement on failure (intended for fluent-API ownership), which violated the documented
    // contract that this method does NOT close the caller's stmt. The fix calls stmt.addBatch()
    // directly so a driver-thrown addBatch error no longer closes the user's stmt.
    @Test
    public void testImportData_AddBatchFailureDoesNotCloseCallerStmt() throws Exception {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        // Driver throws on addBatch (e.g., bind-type mismatch detected late).
        Mockito.doThrow(new SQLException("simulated addBatch failure")).when(stmt).addBatch();

        final Dataset dataset = new RowDataset(ImmutableList.of("id", "name"), ImmutableList.of(ImmutableList.of(1, 2), ImmutableList.of("a", "b")));

        // The stmtSetter is a no-op binder; the failure must come from addBatch itself.
        final Throwables.BiConsumer<PreparedQuery, Object[], SQLException> noopSetter = (pq, row) -> {
            // intentionally empty
        };

        assertThrows(SQLException.class, () -> DataTransferUtil.importData(dataset, stmt, noopSetter));

        // Pre-fix: the wrapper's close-on-failure path called JdbcUtil.closeQuietly(stmt) which
        // invokes stmt.close(). Post-fix: stmt.close() must NOT have been called.
        verify(stmt, never()).close();
    }

    @Test
    public void testRequiredImportSettersAreRejectedForEmptySources() {
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("value"));
        when(mockDataset.size()).thenReturn(0);

        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.importData(mockDataset, mockPreparedStatement,
                (Throwables.BiConsumer<? super PreparedQuery, ? super Object[], SQLException>) null));
        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.importData(List.<String> of().iterator(), mockPreparedStatement, 10, 0,
                (Throwables.BiConsumer<? super PreparedQuery, ? super String, SQLException>) null));
        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.importCsv(new StringReader("header"), row -> true, mockPreparedStatement, 10, 0,
                (Throwables.BiConsumer<? super PreparedQuery, ? super String[], SQLException>) null));
    }

    @Test
    public void testDatasetImportBuilderSnapshotsSelectedColumns() throws SQLException {
        final List<String> selectedColumns = new ArrayList<>(List.of("col1"));
        final DataTransferUtil.DatasetImportBuilder builder = DataTransferUtil.importFrom(mockDataset).columns(selectedColumns);

        selectedColumns.add("missing");
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        when(mockDataset.size()).thenReturn(1);
        when(mockDataset.get(anyInt(), anyInt())).thenReturn("value");
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        assertEquals(1, builder.to(mockPreparedStatement));
    }

    @Test
    public void testDatasetImportBuilderSnapshotsColumnTypeMap() throws SQLException {
        final Map<String, Type> columnTypes = new HashMap<>();
        columnTypes.put("col1", N.typeOf(String.class));
        final DataTransferUtil.DatasetImportBuilder builder = DataTransferUtil.importFrom(mockDataset).columnTypes(columnTypes);

        columnTypes.put("missing", N.typeOf(String.class));
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));
        when(mockDataset.size()).thenReturn(1);
        when(mockDataset.get(anyInt(), anyInt())).thenReturn("value");
        when(mockDataset.getColumnIndex("col1")).thenReturn(0);
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        assertEquals(1, builder.to(mockPreparedStatement));
    }

    @Test
    public void testDatasetImportBuilderValidatesConfigurationBeforeGettingConnection() {
        final DataSource dataSource = mock(DataSource.class);
        final DataTransferUtil.DatasetImportBuilder builder = DataTransferUtil.importFrom(mockDataset)
                .columns(List.of("col1"))
                .parameterSetter((query, row) -> {
                });

        assertThrows(IllegalArgumentException.class, () -> builder.to(dataSource, "INSERT INTO test VALUES (?)"));
        verifyNoInteractions(dataSource);
    }

    @Test
    public void testRowImportBuilderValidatesSetterBeforeGettingConnection() {
        final DataSource dataSource = mock(DataSource.class);
        final DataTransferUtil.RowImportBuilder<String> builder = DataTransferUtil.importFrom(List.of("row").iterator());

        assertThrows(IllegalArgumentException.class, () -> builder.to(dataSource, "INSERT INTO test VALUES (?)"));
        verifyNoInteractions(dataSource);
    }

    @Test
    public void testRowImportBuilderValidatesSetterBeforePreparingStatement() {
        final Connection connection = mock(Connection.class);
        final DataTransferUtil.RowImportBuilder<String> builder = DataTransferUtil.importFrom(List.of("row").iterator());

        assertThrows(IllegalArgumentException.class, () -> builder.to(connection, "INSERT INTO test VALUES (?)"));
        verifyNoInteractions(connection);
    }

    @Test
    @Tag("2025")
    public void testImportBuilderNullTargetDataSourceIsRejectedByItsDeclaredParameterName() {
        // JdbcUtil.getConnection(ds) would also reject it, but its message names 'ds'; the builder must name
        // the parameter the caller actually passed.
        when(mockDataset.columnNames()).thenReturn(ImmutableList.of("col1"));

        final DataTransferUtil.DatasetImportBuilder datasetBuilder = DataTransferUtil.importFrom(mockDataset).columns(List.of("col1"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> datasetBuilder.to((DataSource) null, "INSERT INTO test VALUES (?)"));
        assertTrue(failure.getMessage().contains("targetDataSource"), failure.getMessage());

        final DataTransferUtil.RowImportBuilder<String> rowBuilder = DataTransferUtil.importFrom(List.of("row").iterator()).parameterSetter((query, row) -> {
        });
        failure = assertThrows(IllegalArgumentException.class, () -> rowBuilder.to((DataSource) null, "INSERT INTO test VALUES (?)"));
        assertTrue(failure.getMessage().contains("targetDataSource"), failure.getMessage());
    }

    // Class doc: a caller-supplied Reader is never closed, whether the import wraps it in its own pooled buffer or uses a
    // caller-supplied BufferedReader directly.
    @Test
    public void testImportCsv_CallerSuppliedReaderIsNotClosed() throws SQLException {
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 2 });
        final Throwables.BiConsumer<PreparedQuery, String[], SQLException> setter = (query, row) -> query.setString(1, row[0]);

        final ClosableStringReader plain = new ClosableStringReader("id\n1\n2");
        assertEquals(2, DataTransferUtil.importCsv(plain, mockPreparedStatement, setter));
        assertFalse(plain.closed);

        final ClosableStringReader wrapped = new ClosableStringReader("id\n1\n2");
        assertEquals(2, DataTransferUtil.importCsvFrom(new BufferedReader(wrapped)).parameterSetter(setter).to(mockPreparedStatement));
        assertFalse(wrapped.closed);

        final ClosableStringReader viaBuilder = new ClosableStringReader("id\n1\n2");
        assertEquals(2, DataTransferUtil.importCsvFrom(viaBuilder).parameterSetter(setter).to(mockPreparedStatement));
        assertFalse(viaBuilder.closed);

        verify(mockPreparedStatement, times(6)).addBatch();
        verify(mockPreparedStatement, times(3)).setString(1, "1");
        verify(mockPreparedStatement, times(3)).setString(1, "2");
    }

    private static final class ClosableStringReader extends StringReader {
        boolean closed = false;

        ClosableStringReader(final String s) {
            super(s);
        }

        @Override
        public void close() {
            closed = true;
            super.close();
        }
    }
}
