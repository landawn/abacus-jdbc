package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.ConnectionProxy;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
        verify(mockDataSource).getConnection();
        verify(targetDataSource).getConnection();
        verify(mockConnection).close();
        verify(targetConnection).close();
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

    // MySQL/MariaDB cannot run the batch inserts on a connection whose streaming result set is still open. When the
    // source and target resolve to the same connection (one connection passed twice, or one DataSource inside a Spring
    // transaction), the source must be read with the plain fetch-size hint instead of Integer.MIN_VALUE streaming.
    @Test
    public void testCopy_SameMySqlConnectionDoesNotStreamSource() throws SQLException {
        final String selectSql = "SELECT id FROM src";
        final String insertSql = "INSERT INTO dst(id) VALUES (?)";

        final SameConnectionFixture viaConnection = new SameConnectionFixture("MySQL");
        assertEquals(1, DataTransferUtil.copy(viaConnection.conn, selectSql, viaConnection.conn, insertSql));
        viaConnection.verifyReadWithFetchSize(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET);

        final SameConnectionFixture viaBuilder = new SameConnectionFixture("MariaDB");
        assertEquals(1, DataTransferUtil.copyFrom(viaBuilder.conn, selectSql).fetchSize(123).to(viaBuilder.conn, insertSql));
        viaBuilder.verifyReadWithFetchSize(123);

        final SameConnectionFixture viaDataSource = new SameConnectionFixture("MySQL");
        assertEquals(1, DataTransferUtil.copy(viaDataSource.dataSource, selectSql, viaDataSource.dataSource, insertSql));
        viaDataSource.verifyReadWithFetchSize(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET);

        final SameConnectionFixture viaDataSourceBuilder = new SameConnectionFixture("MySQL");
        assertEquals(1, DataTransferUtil.copyFrom(viaDataSourceBuilder.dataSource, selectSql).fetchSize(77).to(viaDataSourceBuilder.dataSource, insertSql));
        viaDataSourceBuilder.verifyReadWithFetchSize(77);
    }

    // Table copies reach the same code path: one DataSource on both sides (as inside a Spring transaction) yields one connection.
    @Test
    public void testCopyTable_SameMySqlDataSourceDoesNotStreamSource() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(anyInt())).thenReturn("value");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("col1");
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        assertEquals(1, DataTransferUtil.copy(mockDataSource, mockDataSource, "src_table", "dst_table"));

        verify(mockPreparedStatement, atLeastOnce()).setFetchSize(Math.max(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET, JdbcUtil.DEFAULT_BATCH_SIZE));
        verify(mockPreparedStatement, never()).setFetchSize(Integer.MIN_VALUE);
    }

    // Guard: distinct MySQL connections keep streaming the source row by row.
    @Test
    public void testCopy_DistinctMySqlConnectionsStillStreamSource() throws SQLException {
        final SameConnectionFixture source = new SameConnectionFixture("MySQL");
        final SameConnectionFixture target = new SameConnectionFixture("MySQL");

        assertEquals(1, DataTransferUtil.copyFrom(source.conn, "SELECT id FROM src").fetchSize(123).to(target.conn, "INSERT INTO dst(id) VALUES (?)"));

        verify(source.selectStmt).setFetchSize(Integer.MIN_VALUE);
        verify(source.selectStmt, never()).setFetchSize(123);
    }

    // Spring's TransactionAwareDataSourceProxy hands out a distinct ConnectionProxy around the transaction's connection on
    // every acquisition, so comparing the two Connection objects by identity saw two connections and streamed the source
    // ("Streaming result set ... is still active" on the batch insert); the underlying connections decide.
    @Test
    public void testCopy_SpringConnectionProxiesOfOneMySqlConnectionDoNotStreamSource() throws SQLException {
        final String selectSql = "SELECT id FROM src";
        final String insertSql = "INSERT INTO dst(id) VALUES (?)";

        final SameConnectionFixture viaConnection = new SameConnectionFixture("MySQL");
        assertEquals(1, DataTransferUtil.copy(springConnectionProxy(viaConnection.conn), selectSql, springConnectionProxy(viaConnection.conn), insertSql));
        viaConnection.verifyReadWithFetchSize(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET);

        // Proxy layers are unwrapped repeatedly.
        final SameConnectionFixture viaBuilder = new SameConnectionFixture("MariaDB");
        assertEquals(1,
                DataTransferUtil.copyFrom(springConnectionProxy(springConnectionProxy(viaBuilder.conn)), selectSql)
                        .fetchSize(123)
                        .to(springConnectionProxy(viaBuilder.conn), insertSql));
        viaBuilder.verifyReadWithFetchSize(123);

        // Proxies of distinct connections still stream.
        final SameConnectionFixture source = new SameConnectionFixture("MySQL");
        final SameConnectionFixture target = new SameConnectionFixture("MySQL");
        assertEquals(1, DataTransferUtil.copy(springConnectionProxy(source.conn), selectSql, springConnectionProxy(target.conn), insertSql));
        verify(source.selectStmt).setFetchSize(Integer.MIN_VALUE);
    }

    // The shape this guards: one TransactionAwareDataSourceProxy used for both sides of a copy inside a Spring-managed
    // transaction run without transaction synchronization, so each acquisition from the proxy yields a new handle around the
    // transaction's connection (with synchronization active, Spring binds the first handle and returns it again).
    @Test
    public void testCopy_TransactionAwareDataSourceProxyInsideSpringTransactionDoesNotStreamSource() throws SQLException {
        final SameConnectionFixture fixture = new SameConnectionFixture("MySQL");
        final DataSource proxyDataSource = new TransactionAwareDataSourceProxy(fixture.dataSource);
        TransactionSynchronizationManager.bindResource(fixture.dataSource, new ConnectionHolder(fixture.conn));

        try {
            assertEquals(1, DataTransferUtil.copy(proxyDataSource, "SELECT id FROM src", proxyDataSource, "INSERT INTO dst(id) VALUES (?)"));
        } finally {
            TransactionSynchronizationManager.unbindResource(fixture.dataSource);
        }

        fixture.verifyReadWithFetchSize(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET);
        // The transaction's connection served both sides and stays open for the transaction.
        verify(fixture.dataSource, never()).getConnection();
        verify(fixture.conn, never()).close();
    }

    @Test
    public void testIsSameConnection_UnwrapsSpringConnectionProxiesOnly() throws SQLException {
        final Connection conn = mock(Connection.class);
        final Connection other = mock(Connection.class);

        assertTrue(DataTransferUtil.isSameConnection(conn, conn));
        assertFalse(DataTransferUtil.isSameConnection(conn, other));
        assertTrue(DataTransferUtil.isSameConnection(springConnectionProxy(conn), springConnectionProxy(conn)));
        assertTrue(DataTransferUtil.isSameConnection(conn, springConnectionProxy(springConnectionProxy(conn))));
        assertFalse(DataTransferUtil.isSameConnection(springConnectionProxy(conn), springConnectionProxy(other)));

        // A proxy whose target cannot be obtained (e.g. a closed transaction-aware handle) is left as it is; the statement
        // prepared on it reports the failure.
        final Connection closedHandle = (Connection) Proxy.newProxyInstance(ConnectionProxy.class.getClassLoader(), new Class<?>[] { ConnectionProxy.class },
                (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) {
                        return "closed handle";
                    }

                    throw new IllegalStateException("Connection handle already closed");
                });
        assertFalse(DataTransferUtil.isSameConnection(closedHandle, conn));
        assertFalse(DataTransferUtil.isSameConnection(springConnectionProxy(conn), closedHandle));
    }

    // A LinkageError while a Spring proxy obtained its target connection (through its data source's driver) turned the
    // unwrapping off for the rest of the JVM's life and answered "different connections", which could let MySQL/MariaDB stream
    // the source rows on the connection the inserts then use. It propagates now, and the unwrapping stays on.
    @Test
    public void testIsSameConnection_ALinkageErrorObtainingATargetConnectionPropagates() {
        final Connection conn = mock(Connection.class);
        final UnsatisfiedLinkError driverFailure = new UnsatisfiedLinkError("no native driver library");
        final Connection failingHandle = (Connection) Proxy.newProxyInstance(ConnectionProxy.class.getClassLoader(), new Class<?>[] { ConnectionProxy.class },
                (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) {
                        return "failing handle";
                    }

                    throw driverFailure;
                });

        assertSame(driverFailure, assertThrows(UnsatisfiedLinkError.class, () -> DataTransferUtil.isSameConnection(failingHandle, conn)));
        assertSame(driverFailure, assertThrows(UnsatisfiedLinkError.class, () -> DataTransferUtil.isSameConnection(conn, failingHandle)));
        assertTrue(DataTransferUtil.isSameConnection(springConnectionProxy(conn), springConnectionProxy(conn)));
    }

    /**
     * A Spring {@code ConnectionProxy} around {@code target}, as {@code TransactionAwareDataSourceProxy} creates one per acquisition.
     */
    private static Connection springConnectionProxy(final Connection target) {
        return (Connection) Proxy.newProxyInstance(ConnectionProxy.class.getClassLoader(), new Class<?>[] { ConnectionProxy.class }, (proxy, method, args) -> {
            if ("getTargetConnection".equals(method.getName())) {
                return target;
            }

            try {
                return method.invoke(target, args);
            } catch (final InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    // Statements opened by a copy are closed as by try-with-resources: a close failure after an otherwise successful
    // copy propagates, and the result set opened from a caller-supplied statement is treated the same way.
    @Test
    public void testCopy_OwnedResourceCloseFailurePropagates() throws SQLException {
        final SameConnectionFixture source = new SameConnectionFixture("PostgreSQL");
        final SameConnectionFixture target = new SameConnectionFixture("PostgreSQL");
        final SQLException insertCloseFailure = new SQLException("insert statement close failed");
        Mockito.doThrow(insertCloseFailure).when(target.insertStmt).close();

        assertSame(insertCloseFailure, assertThrows(SQLException.class,
                () -> DataTransferUtil.copy(source.conn, "SELECT id FROM src", target.conn, "INSERT INTO dst(id) VALUES (?)")));
        verify(source.selectStmt).close();

        final SameConnectionFixture statements = new SameConnectionFixture("PostgreSQL");
        final SQLException resultSetCloseFailure = new SQLException("result set close failed");
        Mockito.doThrow(resultSetCloseFailure).when(statements.rs).close();

        assertSame(resultSetCloseFailure, assertThrows(SQLException.class, () -> DataTransferUtil.copyFrom(statements.selectStmt).to(statements.insertStmt)));
        verify(statements.selectStmt, never()).close();
        verify(statements.insertStmt, never()).close();
    }

    /**
     * One mocked connection (also served by a DataSource) that prepares a distinct select and insert statement and
     * yields a one-row, one-column source result.
     */
    private static final class SameConnectionFixture {
        final DataSource dataSource = mock(DataSource.class);
        final Connection conn = mock(Connection.class);
        final PreparedStatement selectStmt = mock(PreparedStatement.class);
        final PreparedStatement insertStmt = mock(PreparedStatement.class);
        final ResultSet rs = mock(ResultSet.class);

        SameConnectionFixture(final String productName) throws SQLException {
            final DatabaseMetaData metaData = mock(DatabaseMetaData.class);
            final ResultSetMetaData rsMetaData = mock(ResultSetMetaData.class);

            when(dataSource.getConnection()).thenReturn(conn);
            when(conn.getMetaData()).thenReturn(metaData);
            when(metaData.getDatabaseProductName()).thenReturn(productName);
            when(metaData.getDatabaseProductVersion()).thenReturn("8");
            when(metaData.getIdentifierQuoteString()).thenReturn("`");
            when(conn.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(selectStmt);
            when(conn.prepareStatement(anyString())).thenReturn(insertStmt);
            when(selectStmt.executeQuery()).thenReturn(rs);
            when(rs.getMetaData()).thenReturn(rsMetaData);
            when(rsMetaData.getColumnCount()).thenReturn(1);
            when(rs.next()).thenReturn(true, false);
            when(rs.getObject(1)).thenReturn("value");
            when(insertStmt.executeBatch()).thenReturn(new int[] { 1 });
        }

        void verifyReadWithFetchSize(final int fetchSize) throws SQLException {
            verify(selectStmt).setFetchSize(fetchSize);
            verify(selectStmt, never()).setFetchSize(Integer.MIN_VALUE);
            verify(insertStmt).addBatch();
            verify(insertStmt).executeBatch();
        }
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

    // Table names that parse but cannot be rendered while identifier quoting is disabled (explicit delimiters, reserved
    // names) are rejected before either connection is acquired or the source table is probed for its columns.
    @Test
    public void testCopyTables_UnrenderableTableNamesRejectedBeforeDatabaseAccess() throws SQLException {
        final DataSource targetDs = mock(DataSource.class);
        final Connection targetConn = mock(Connection.class);

        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.copy(mockDataSource, targetDs, "\"Users\"", "users_backup"));
        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.copy(mockDataSource, targetDs, "users", "order"));
        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.copy(mockDataSource, targetDs, "users", "user", List.of("id")));
        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.copyTable(mockDataSource, "users").to(targetDs, "sales.\"Archive\""));

        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.copy(mockConnection, targetConn, "users", "select"));
        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.copyTable(mockConnection, "\"Users\"").to(targetConn, "users_backup"));

        verify(mockDataSource, never()).getConnection();
        verify(targetDs, never()).getConnection();
        verifyNoInteractions(mockConnection, targetConn);
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

    // The single-name overloads report a bad name under their own parameter, 'tableName', not the delegate's
    // 'sourceTableName'/'targetTableName'; null resources are still reported first.
    @Test
    public void testCopyTables_SingleTableNameOverloadsReportTableNameParameter() throws SQLException {
        final DataSource targetDs = mock(DataSource.class);
        final Connection targetConn = mock(Connection.class);

        for (final String badName : new String[] { "a..b", null, "  " }) {
            final String dataSourceMessage = assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.copy(mockDataSource, targetDs, badName))
                    .getMessage();
            assertTrue(dataSourceMessage.contains("tableName") && !dataSourceMessage.contains("sourceTableName"), dataSourceMessage);

            final String connectionMessage = assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.copy(mockConnection, targetConn, badName))
                    .getMessage();
            assertTrue(connectionMessage.contains("tableName") && !connectionMessage.contains("sourceTableName"), connectionMessage);
        }

        assertTrue(assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.copy((DataSource) null, targetDs, "a..b")).getMessage()
                .contains("sourceDataSource"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.copy(mockConnection, (Connection) null, "a..b")).getMessage()
                .contains("targetConn"));

        verify(mockDataSource, never()).getConnection();
        verify(targetDs, never()).getConnection();
        verifyNoInteractions(mockConnection, targetConn);
    }
}
