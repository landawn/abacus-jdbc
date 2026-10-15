package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.util.Throwables;

class DataTransferCopyTest extends DataTransferUtilTestSupport {

    @Test
    public void testCopyColumnsAreValidatedBeforeBatchSizeAndDatabaseAccess() {
        final Connection source = mock(Connection.class);
        final Connection target = mock(Connection.class);
        final DataSource sourceDataSource = mock(DataSource.class);
        final DataSource targetDataSource = mock(DataSource.class);

        assertTrue(assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.copy(source, target, "users", "users", List.of("schema.id"), 0))
                .getMessage()
                .contains("columnName"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.copy(sourceDataSource, targetDataSource, "users", "users", List.of("schema.id"), 0)).getMessage()
                        .contains("columnName"));
        verifyNoInteractions(source, target, sourceDataSource, targetDataSource);
    }

    // Tests for copy methods

    @Test
    public void testCopyBetweenDataSourcesSameTable() throws SQLException {
        // Setup
        DataSource targetDataSource = mock(DataSource.class);
        Connection targetConnection = mock(Connection.class);
        DatabaseMetaData targetDatabaseMetaData = mock(DatabaseMetaData.class);
        PreparedStatement targetPreparedStatement = mock(PreparedStatement.class);

        when(targetDataSource.getConnection()).thenReturn(targetConnection);
        when(targetConnection.getMetaData()).thenReturn(targetDatabaseMetaData);
        when(targetDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(targetDatabaseMetaData.getIdentifierQuoteString()).thenReturn("`");
        when(targetDatabaseMetaData.getDatabaseProductVersion()).thenReturn("8");
        when(targetConnection.prepareStatement(anyString())).thenReturn(targetPreparedStatement);
        when(targetPreparedStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(anyInt())).thenReturn("value");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("col1");
        when(targetPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        // Execute
        long result = DataTransferUtil.copy(mockDataSource, targetDataSource, "test_table");

        // Verify
        assertEquals(1, result);
        verify(mockDataSource, times(2)).getConnection();
        verify(targetDataSource, times(2)).getConnection();
    }

    @Test
    public void testCopyBetweenDataSourcesDifferentTables() throws SQLException {
        // Setup
        DataSource targetDataSource = mock(DataSource.class);
        Connection targetConnection = mock(Connection.class);
        DatabaseMetaData targetDatabaseMetaData = mock(DatabaseMetaData.class);
        PreparedStatement targetPreparedStatement = mock(PreparedStatement.class);

        when(targetDataSource.getConnection()).thenReturn(targetConnection);
        when(targetConnection.getMetaData()).thenReturn(targetDatabaseMetaData);
        when(targetDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(targetDatabaseMetaData.getIdentifierQuoteString()).thenReturn("`");
        when(targetDatabaseMetaData.getDatabaseProductVersion()).thenReturn("8");
        when(targetConnection.prepareStatement(anyString())).thenReturn(targetPreparedStatement);
        when(targetPreparedStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(anyInt())).thenReturn("value");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("col1");
        when(targetPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        // Execute
        long result = DataTransferUtil.copy(mockDataSource, targetDataSource, "source_table", "target_table");

        // Verify
        assertEquals(2, result);
    }

    @Test
    public void testCopyBetweenDataSourcesLeavesGeneratedInsertUnquotedAcrossDialects() throws SQLException {
        final DataSource targetDataSource = mock(DataSource.class);
        final Connection targetConnection = mock(Connection.class);
        final DatabaseMetaData targetDatabaseMetaData = mock(DatabaseMetaData.class);
        final PreparedStatement targetPreparedStatement = mock(PreparedStatement.class);
        final ResultSet targetResultSet = mock(ResultSet.class);
        final ResultSetMetaData targetResultSetMetaData = mock(ResultSetMetaData.class);

        when(targetDataSource.getConnection()).thenReturn(targetConnection);
        when(targetConnection.getMetaData()).thenReturn(targetDatabaseMetaData);
        when(targetDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(targetDatabaseMetaData.getIdentifierQuoteString()).thenReturn("\"");
        when(targetDatabaseMetaData.getDatabaseProductVersion()).thenReturn("16");
        when(targetConnection.prepareStatement(anyString())).thenReturn(targetPreparedStatement);
        when(targetPreparedStatement.executeQuery()).thenReturn(targetResultSet);
        when(targetPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });
        when(targetResultSet.getMetaData()).thenReturn(targetResultSetMetaData);
        when(targetResultSetMetaData.getColumnCount()).thenReturn(1);
        when(targetResultSetMetaData.getColumnLabel(1)).thenReturn("created_date");

        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(anyInt())).thenReturn("value");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("created_date");

        final long result = DataTransferUtil.copy(mockDataSource, targetDataSource, "source_table", "target_table");

        assertEquals(1, result);
        verify(targetConnection).prepareStatement("INSERT INTO target_table(created_date) VALUES (?)");
        verify(targetConnection, never()).prepareStatement("INSERT INTO target_table(\"created_date\") VALUES (?)");
        verify(targetConnection, never()).prepareStatement("INSERT INTO target_table(`created_date`) VALUES (?)");
    }

    @Test
    public void testCopyWithSelectedColumns() throws SQLException {
        // Setup
        DataSource targetDataSource = mock(DataSource.class);
        Connection targetConnection = mock(Connection.class);
        DatabaseMetaData targetDatabaseMetaData = mock(DatabaseMetaData.class);
        PreparedStatement targetPreparedStatement = mock(PreparedStatement.class);
        Collection<String> selectColumnNames = Arrays.asList("col1", "col2");

        when(targetDataSource.getConnection()).thenReturn(targetConnection);
        when(targetConnection.getMetaData()).thenReturn(targetDatabaseMetaData);
        when(targetDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(targetDatabaseMetaData.getIdentifierQuoteString()).thenReturn("`");
        when(targetDatabaseMetaData.getDatabaseProductVersion()).thenReturn("8");
        when(targetConnection.prepareStatement(anyString())).thenReturn(targetPreparedStatement);
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(anyInt())).thenReturn("value");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);
        when(targetPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        // Execute
        long result = DataTransferUtil.copy(mockDataSource, targetDataSource, "source_table", "target_table", selectColumnNames);

        // Verify
        assertEquals(1, result);
    }

    @Test
    public void testCopyWithSelectedColumnsLeavesQualifiedOrdinaryNamesUnquoted() throws SQLException {
        final Connection targetConnection = mock(Connection.class);
        final DatabaseMetaData targetDatabaseMetaData = mock(DatabaseMetaData.class);
        final PreparedStatement targetPreparedStatement = mock(PreparedStatement.class);
        final Collection<String> selectColumnNames = List.of("created_date");

        when(targetConnection.getMetaData()).thenReturn(targetDatabaseMetaData);
        when(targetDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(targetDatabaseMetaData.getIdentifierQuoteString()).thenReturn("\"");
        when(targetDatabaseMetaData.getDatabaseProductVersion()).thenReturn("16");
        when(targetConnection.prepareStatement(anyString())).thenReturn(targetPreparedStatement);
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(anyInt())).thenReturn("value");
        when(targetPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        final long result = DataTransferUtil.copy(mockConnection, targetConnection, "sales.source_table", "archive.target_table", selectColumnNames);

        assertEquals(1, result);
        verify(mockConnection).prepareStatement("SELECT created_date FROM sales.source_table", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        verify(targetConnection).prepareStatement("INSERT INTO archive.target_table(created_date) VALUES (?)");
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
        long result = DataTransferUtil.copy(mockDataSource, selectSql, targetDataSource, insertSql);

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
        long result = DataTransferUtil.copy(mockDataSource, selectSql, targetDataSource, insertSql, stmtSetter);

        // Verify
        assertEquals(1, result);
        verify(targetPreparedStatement).setString(1, "VALUE");
    }

    @Test
    public void testCopyBetweenConnections() throws SQLException {
        // Setup
        Connection targetConnection = mock(Connection.class);
        DatabaseMetaData targetDatabaseMetaData = mock(DatabaseMetaData.class);
        PreparedStatement targetPreparedStatement = mock(PreparedStatement.class);

        when(targetConnection.getMetaData()).thenReturn(targetDatabaseMetaData);
        when(targetDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(targetDatabaseMetaData.getIdentifierQuoteString()).thenReturn("`");
        when(targetDatabaseMetaData.getDatabaseProductVersion()).thenReturn("8");
        when(targetConnection.prepareStatement(anyString())).thenReturn(targetPreparedStatement);
        when(targetPreparedStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(anyInt())).thenReturn("value");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("col1");
        when(targetPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        // Execute
        long result = DataTransferUtil.copy(mockConnection, targetConnection, "test_table");

        // Verify
        assertEquals(2, result);
    }

    @Test
    public void testCopyBetweenConnectionsLeavesGeneratedInsertUnquotedAcrossDialects() throws SQLException {
        final Connection targetConnection = mock(Connection.class);
        final DatabaseMetaData targetDatabaseMetaData = mock(DatabaseMetaData.class);
        final PreparedStatement targetPreparedStatement = mock(PreparedStatement.class);
        final ResultSet targetResultSet = mock(ResultSet.class);
        final ResultSetMetaData targetResultSetMetaData = mock(ResultSetMetaData.class);

        when(targetConnection.getMetaData()).thenReturn(targetDatabaseMetaData);
        when(targetDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(targetDatabaseMetaData.getIdentifierQuoteString()).thenReturn("\"");
        when(targetDatabaseMetaData.getDatabaseProductVersion()).thenReturn("16");
        when(targetConnection.prepareStatement(anyString())).thenReturn(targetPreparedStatement);
        when(targetPreparedStatement.executeQuery()).thenReturn(targetResultSet);
        when(targetPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });
        when(targetResultSet.getMetaData()).thenReturn(targetResultSetMetaData);
        when(targetResultSetMetaData.getColumnCount()).thenReturn(1);
        when(targetResultSetMetaData.getColumnLabel(1)).thenReturn("created_date");

        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(anyInt())).thenReturn("value");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("created_date");

        final long result = DataTransferUtil.copy(mockConnection, targetConnection, "source_table", "target_table");

        assertEquals(1, result);
        verify(targetConnection).prepareStatement("INSERT INTO target_table(created_date) VALUES (?)");
        verify(targetConnection, never()).prepareStatement("INSERT INTO target_table(\"created_date\") VALUES (?)");
        verify(targetConnection, never()).prepareStatement("INSERT INTO target_table(`created_date`) VALUES (?)");
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
        long result = DataTransferUtil.copy(mockPreparedStatement, targetPreparedStatement, 2, 10, stmtSetter);

        // Verify
        assertEquals(3, result);
        verify(targetPreparedStatement, times(2)).executeBatch(); // 3 rows with batch size 2
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
        long result = DataTransferUtil.copy(mockPreparedStatement, targetPreparedStatement, 1, 50, stmtSetter); // 50ms interval

        long endTime = System.currentTimeMillis();

        // Verify
        assertEquals(2, result);
        verify(targetPreparedStatement, times(2)).executeBatch();
        assertTrue((endTime - startTime) >= 50); // At least one interval delay
    }

    // copy(Connection, String, Connection, String) - delegates to full copy with default sizes (line 3007-3008)
    @Test
    public void testCopyBetweenConnectionsWithCustomSql() throws SQLException {
        Connection targetConn = mock(Connection.class);
        PreparedStatement targetStmt = mock(PreparedStatement.class);
        DatabaseMetaData targetMeta = mock(DatabaseMetaData.class);

        when(targetConn.getMetaData()).thenReturn(targetMeta);
        when(targetMeta.getDatabaseProductName()).thenReturn("MySQL");
        when(targetMeta.getIdentifierQuoteString()).thenReturn("`");
        when(targetMeta.getDatabaseProductVersion()).thenReturn("8");
        when(targetConn.prepareStatement(anyString())).thenReturn(targetStmt);
        when(mockConnection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(mockPreparedStatement);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(anyInt())).thenReturn("row1");
        when(targetStmt.executeBatch()).thenReturn(new int[] { 1 });

        long result = DataTransferUtil.copy(mockConnection, "SELECT * FROM src", targetConn, "INSERT INTO dst VALUES (?)");

        assertEquals(1, result);
        verify(targetStmt).addBatch();
        verify(targetStmt).executeBatch();
    }

    // Regression: copy(srcConn, tgtConn, srcTable, tgtTable, batchSize) previously generated the
    // SELECT and INSERT independently from source/target metadata. When the two schemas had the
    // same column SET but a different declaration ORDER, positional setObject(i,...) silently
    // swapped values between columns. The fix derives the column list once from the source and
    // uses it for both SQL statements.
    @Tag("integration")
    @Test
    public void testCopy_AlignsInsertColumnOrderToSourceForAllColumnOverloads() throws Exception {
        // Two in-memory H2 datasources with the same table but different column declaration orders.
        final DataSource srcDs = JdbcUtil.createHikariDataSource("jdbc:h2:mem:copy_src_order;DB_CLOSE_DELAY=-1", "sa", "");
        final DataSource tgtDs = JdbcUtil.createHikariDataSource("jdbc:h2:mem:copy_tgt_order;DB_CLOSE_DELAY=-1", "sa", "");

        try {
            try (Connection conn = srcDs.getConnection();
                 java.sql.Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE T_ORDER (a INT, b INT, c INT)");
                st.execute("INSERT INTO T_ORDER (a, b, c) VALUES (10, 20, 30)");
            }
            // Target has the SAME columns in REVERSE order: c, b, a
            try (Connection conn = tgtDs.getConnection();
                 java.sql.Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE T_ORDER (c INT, b INT, a INT)");
            }

            // Exercise the direct all-column overload and the selected-column overload's documented
            // null/empty aliases. All three must derive one shared order from the source metadata.
            for (int mode = 0; mode < 3; mode++) {
                try (Connection conn = tgtDs.getConnection();
                     java.sql.Statement st = conn.createStatement()) {
                    st.executeUpdate("DELETE FROM T_ORDER");
                }

                try (Connection srcConn = srcDs.getConnection();
                     Connection tgtConn = tgtDs.getConnection()) {
                    final long copied = mode == 0 ? DataTransferUtil.copy(srcConn, tgtConn, "T_ORDER", "T_ORDER", 10)
                            : DataTransferUtil.copy(srcConn, tgtConn, "T_ORDER", "T_ORDER", mode == 1 ? null : List.of(), 10);
                    assertEquals(1L, copied);
                }

                // The target row must hold (a=10, b=20, c=30) matching the source. Before the fix,
                // positional binding into the target's (c, b, a) order swapped a and c.
                try (Connection conn = tgtDs.getConnection();
                     java.sql.Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT a, b, c FROM T_ORDER")) {
                    assertTrue(rs.next());
                    assertEquals(10, rs.getInt("a"), "column 'a' must hold the source value 10, not the swapped 30");
                    assertEquals(20, rs.getInt("b"));
                    assertEquals(30, rs.getInt("c"), "column 'c' must hold the source value 30, not the swapped 10");
                }
            }
        } finally {
            ((com.zaxxer.hikari.HikariDataSource) srcDs).close();
            ((com.zaxxer.hikari.HikariDataSource) tgtDs).close();
        }
    }

    @Test
    public void testCopyWithSelectedColumnsRejectsNamesRequiringQuoting() throws SQLException {
        final Connection targetConnection = mock(Connection.class);
        final Collection<String> selectColumnNames = List.of("created-date");

        assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.copy(mockConnection, targetConnection, "source_table", "target_table", selectColumnNames));

        verify(mockConnection, never()).prepareStatement(anyString(), anyInt(), anyInt());
        verify(targetConnection, never()).prepareStatement(anyString());
    }

    @Test
    public void testCopyWithSelectedColumnsRejectsIdentifiersStartingWithDigit() throws SQLException {
        final Connection targetConnection = mock(Connection.class);
        final DatabaseMetaData targetDatabaseMetaData = mock(DatabaseMetaData.class);

        when(targetConnection.getMetaData()).thenReturn(targetDatabaseMetaData);
        when(targetDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(targetDatabaseMetaData.getIdentifierQuoteString()).thenReturn("\"");
        when(targetDatabaseMetaData.getDatabaseProductVersion()).thenReturn("16");

        assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.copy(mockConnection, targetConnection, "2024_source", "target_table", List.of("total")));
        assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.copy(mockConnection, targetConnection, "source_table", "2024_target", List.of("total")));
        assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.copy(mockConnection, targetConnection, "source_table", "target_table", List.of("2024_total")));

        verify(mockConnection, never()).prepareStatement(anyString(), anyInt(), anyInt());
        verify(targetConnection, never()).prepareStatement(anyString());
    }

    @Test
    public void testCopyWithSelectedColumnsRejectsNonSimpleQualifiedTableParts() throws SQLException {
        final Connection targetConnection = mock(Connection.class);
        final DatabaseMetaData targetDatabaseMetaData = mock(DatabaseMetaData.class);

        when(targetConnection.getMetaData()).thenReturn(targetDatabaseMetaData);
        when(targetDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(targetDatabaseMetaData.getIdentifierQuoteString()).thenReturn("\"");
        when(targetDatabaseMetaData.getDatabaseProductVersion()).thenReturn("16");

        assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.copy(mockConnection, targetConnection, "sales.source-table", "archive.target_table", List.of("created_date")));
        assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.copy(mockConnection, targetConnection, "sales.source_table", "archive.target-table", List.of("created_date")));

        verify(mockConnection, never()).prepareStatement(anyString(), anyInt(), anyInt());
        verify(targetConnection, never()).prepareStatement(anyString());
    }

    @Test
    public void testCopyWithSelectedColumnsRejectsQuotedDotsWithinSingleIdentifier() throws SQLException {
        final Connection targetConnection = mock(Connection.class);
        final DatabaseMetaData targetDatabaseMetaData = mock(DatabaseMetaData.class);

        when(targetConnection.getMetaData()).thenReturn(targetDatabaseMetaData);
        when(targetDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(targetDatabaseMetaData.getIdentifierQuoteString()).thenReturn("\"");
        when(targetDatabaseMetaData.getDatabaseProductVersion()).thenReturn("16");

        assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.copy(mockConnection, targetConnection, "\"sales.source-table\"", "target_table", List.of("created_date")));
        assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.copy(mockConnection, targetConnection, "source_table", "\"archive.target-table\"", List.of("created_date")));

        verify(mockConnection, never()).prepareStatement(anyString(), anyInt(), anyInt());
        verify(targetConnection, never()).prepareStatement(anyString());
    }

    @Test
    public void testCopyWithSelectedColumnsRejectsEmbeddedQuotesInIdentifiers() throws SQLException {
        final Connection targetConnection = mock(Connection.class);
        final DatabaseMetaData targetDatabaseMetaData = mock(DatabaseMetaData.class);

        when(targetConnection.getMetaData()).thenReturn(targetDatabaseMetaData);
        when(targetDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(targetDatabaseMetaData.getIdentifierQuoteString()).thenReturn("\"");
        when(targetDatabaseMetaData.getDatabaseProductVersion()).thenReturn("16");

        assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.copy(mockConnection, targetConnection, "source_table", "target_table", List.of("created\"date")));
        assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.copy(mockConnection, targetConnection, "source_table", "\"archive\"\"target\"", List.of("created_date")));

        verify(mockConnection, never()).prepareStatement(anyString(), anyInt(), anyInt());
        verify(targetConnection, never()).prepareStatement(anyString());
    }

    @Test
    public void testCopyWithSelectedColumnsRejectsExplicitDelimitersOnSimpleIdentifierParts() throws Exception {
        final Connection targetConnection = mock(Connection.class);
        final DatabaseMetaData targetMetadata = mock(DatabaseMetaData.class);

        when(targetConnection.getMetaData()).thenReturn(targetMetadata);
        when(targetMetadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(targetMetadata.getIdentifierQuoteString()).thenReturn("\"");
        when(targetMetadata.getDatabaseProductVersion()).thenReturn("16");
        assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.copy(mockConnection, targetConnection, "sales.\"MixedSource\"", "archive.target_table", List.of("column_name")));
        assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.copy(mockConnection, targetConnection, "sales.source_table", "archive.\"MixedTarget\"", List.of("column_name")));
        assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.copy(mockConnection, targetConnection, "sales.source_table", "archive.target_table", List.of("\"MixedColumn\"")));

        verify(mockConnection, never()).prepareStatement(anyString(), anyInt(), anyInt());
        verify(targetConnection, never()).prepareStatement(anyString());
    }

    /**
     * Regression test for connection-leak in
     * {@code copy(DataSource, DataSource, String, String, int)} and
     * {@code copy(DataSource, String, int, DataSource, String, int, long, BiConsumer)}.
     *
     * <p>Before the fix the finally block called {@code releaseConnection(source...)}
     * followed by {@code releaseConnection(target...)} as two top-level statements;
     * if the first release threw a {@link RuntimeException}, the second release was
     * silently skipped, leaking the target connection. The fix wraps the first release
     * in a nested try/finally so the second always runs.</p>
     */
    @Test
    public void testCopyDataSources_ReleasesTargetConnectionEvenWhenSourceReleaseThrows() throws SQLException {
        // Source DataSource where the connection's close() throws (simulating a release failure).
        final DataSource srcDs = mock(DataSource.class);
        final Connection srcConn = mock(Connection.class);
        when(srcDs.getConnection()).thenReturn(srcConn);
        when(srcConn.getMetaData()).thenReturn(mockDatabaseMetaData);
        // Force releaseConnection -> closeQuietly -> conn.close() to throw a RuntimeException
        // (closeQuietly catches Exception not Error; we use a RuntimeException to ensure it
        // propagates out of the finally on the first release).
        Mockito.doThrow(new RuntimeException("simulated release failure")).when(srcConn).close();

        // Target DataSource: must still have its connection released even though source release threw.
        final DataSource targetDs = mock(DataSource.class);
        final Connection targetConn = mock(Connection.class);
        final DatabaseMetaData targetMd = mock(DatabaseMetaData.class);
        when(targetDs.getConnection()).thenReturn(targetConn);
        when(targetConn.getMetaData()).thenReturn(targetMd);
        when(targetMd.getDatabaseProductName()).thenReturn("MySQL");
        when(targetMd.getIdentifierQuoteString()).thenReturn("`");
        when(targetMd.getDatabaseProductVersion()).thenReturn("8");

        // Drive copy() into the finally block by failing the SQL generation step.
        // generateSelectSql calls getMetaData(); make the source connection's metadata throw to
        // trigger the finally cleanup deterministically (we don't care which step fails as long
        // as we reach finally).
        when(srcConn.getMetaData()).thenThrow(new SQLException("simulated generation failure"));

        // copy() must propagate a failure: either the generation SQLException or the RuntimeException
        // from the source close(). We don't care which surfaces - only that one does.
        assertThrows(Exception.class, () -> DataTransferUtil.copy(srcDs, targetDs, "src_table", "dst_table", 100));

        // The fix guarantees that the target connection's close() is invoked even though the
        // source's close() threw a RuntimeException in the finally block.
        verify(targetConn).close();
    }

    // CopyFromStatement.batchInterval rejects a negative duration immediately.
    // (DataTransferUtil L3075).
    @Test
    public void testCopyFromStatement_NegativeBatchInterval_Throws() {
        final PreparedStatement targetStmt = mock(PreparedStatement.class);

        assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.copyFrom(mockPreparedStatement).batchInterval(Duration.ofMillis(-1)).to(targetStmt));
    }

    @Test
    public void testCopyFromConnection_NegativeFetchSizeIsRejected() {
        final Connection targetConnection = mock(Connection.class);

        assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.copyFrom(mockConnection, "SELECT value FROM source")
                        .fetchSize(-1)
                        .to(targetConnection, "INSERT INTO target(value) VALUES (?)"));
    }

    // CopyFromStatement.batchSize(0) routes a non-positive batch size into copy(...)'s argument validation
    // (DataTransferUtil L3075). The check fires before any query runs, so mock statements suffice.
    @Test
    public void testCopyFromStatement_NonPositiveBatchSize_Throws() {
        final PreparedStatement targetStmt = mock(PreparedStatement.class);

        assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.copyFrom(mockPreparedStatement)
                        .batchSize(0)
                        .parameterSetter((pq, rs) -> pq.setObject(1, rs.getObject(1)))
                        .to(targetStmt));
    }

    // Malformed table/column names of a table copy are rejected before either connection is acquired, and are
    // reported under the public parameter name.
    @Test
    public void testCopyTables_MalformedNamesRejectedBeforeConnectionAcquired() throws SQLException {
        final DataSource targetDs = mock(DataSource.class);

        final IllegalArgumentException source = assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.copy(mockDataSource, targetDs, "a..b", "t"));
        assertTrue(source.getMessage().contains("sourceTableName"), source.getMessage());

        final IllegalArgumentException target = assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.copy(mockDataSource, targetDs, "s", "a..b"));
        assertTrue(target.getMessage().contains("targetTableName"), target.getMessage());

        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.copy(mockDataSource, targetDs, "s", "t", List.of("a.b")));
        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.copy(mockDataSource, targetDs, "s", "t", List.of("  ")));

        verify(mockDataSource, never()).getConnection();
        verify(targetDs, never()).getConnection();
    }
}
