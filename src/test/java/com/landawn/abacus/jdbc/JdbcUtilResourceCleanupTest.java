package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.exception.UncheckedSQLException;

class JdbcUtilResourceCleanupTest extends JdbcUtilTestSupport {

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

    // close(ResultSet) wraps SQLException as UncheckedSQLException (line 988).
    @Test
    public void testClose_ResultSet_WrapsSQLException() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        doThrow(new SQLException("boom")).when(rs).close();

        assertThrows(com.landawn.abacus.exception.UncheckedSQLException.class, () -> JdbcUtil.close(rs));
    }

    // close(ResultSet, boolean, boolean) — getStatement throws SQLException (line 1071).
    @Test
    public void testClose_ResultSetBooleanFlags_GetStatementThrows_WrapsSQLException() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getStatement()).thenThrow(new SQLException("getStatement failed"));

        assertThrows(com.landawn.abacus.exception.UncheckedSQLException.class, () -> JdbcUtil.close(rs, true, false));
    }

    @Test
    public void testClose_ResultSetBooleanFlags_PreservesLookupFailureWhenCloseAlsoFails() throws SQLException {
        final SQLException lookupFailure = new SQLException("getStatement failed");
        final SQLException closeFailure = new SQLException("close failed");
        final ResultSet rs = mock(ResultSet.class);
        when(rs.getStatement()).thenThrow(lookupFailure);
        doThrow(closeFailure).when(rs).close();

        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, () -> JdbcUtil.close(rs, true, false));
        assertSame(lookupFailure, thrown.getCause());
        assertArrayEquals(new Throwable[] { closeFailure }, thrown.getCause().getSuppressed());
    }

    @Test
    public void testClose_ResultSetBooleanFlags_CleansUpAfterUncheckedLookupFailure() throws SQLException {
        final IllegalStateException lookupFailure = new IllegalStateException("getStatement failed");
        final SQLException closeFailure = new SQLException("close failed");
        final ResultSet rs = mock(ResultSet.class);
        when(rs.getStatement()).thenThrow(lookupFailure);
        doThrow(closeFailure).when(rs).close();

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> JdbcUtil.close(rs, true, false));

        assertSame(lookupFailure, thrown);
        assertArrayEquals(new Throwable[] { closeFailure }, thrown.getSuppressed());
        verify(rs).close();
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

    @Test
    @DisplayName("close(ResultSet, Statement): an unchecked ResultSet failure does not skip Statement.close()")
    public void testClose_ResultSetStatement_UncheckedFailureStillClosesStatement() throws SQLException {
        final IllegalStateException rsEx = new IllegalStateException("rs runtime failure");
        final SQLException stmtEx = new SQLException("stmt SQL failure");
        final ResultSet rs = mock(ResultSet.class);
        final Statement stmt = mock(Statement.class);
        doThrow(rsEx).when(rs).close();
        doThrow(stmtEx).when(stmt).close();

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> JdbcUtil.close(rs, stmt));

        assertSame(rsEx, thrown);
        assertArrayEquals(new Throwable[] { stmtEx }, thrown.getSuppressed());
        verify(stmt).close();
    }

    @Test
    @DisplayName("close(Statement, Connection): an Error from Statement.close() does not skip Connection.close()")
    public void testClose_StatementConnection_ErrorStillClosesConnection() throws SQLException {
        final AssertionError stmtError = new AssertionError("stmt error");
        final IllegalStateException connEx = new IllegalStateException("conn runtime failure");
        final Statement stmt = mock(Statement.class);
        final Connection conn = mock(Connection.class);
        doThrow(stmtError).when(stmt).close();
        doThrow(connEx).when(conn).close();

        final AssertionError thrown = assertThrows(AssertionError.class, () -> JdbcUtil.close(stmt, conn));

        assertSame(stmtError, thrown);
        assertArrayEquals(new Throwable[] { connEx }, thrown.getSuppressed());
        verify(conn).close();
    }

    @Test
    @DisplayName("close(ResultSet, true, true): an unchecked close failure does not leak its Statement or Connection")
    public void testClose_ResultSetBooleanFlags_UncheckedCloseFailureStillClosesOwners() throws SQLException {
        final IllegalStateException rsEx = new IllegalStateException("rs runtime failure");
        final ResultSet rs = mock(ResultSet.class);
        final Statement stmt = mock(Statement.class);
        final Connection conn = mock(Connection.class);
        when(rs.getStatement()).thenReturn(stmt);
        when(stmt.getConnection()).thenReturn(conn);
        doThrow(rsEx).when(rs).close();

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> JdbcUtil.close(rs, true, true));

        assertSame(rsEx, thrown);
        verify(stmt).close();
        verify(conn).close();
    }

    @Test
    @DisplayName("close overloads preserve a reused SQLException instead of attempting self-suppression")
    public void testCloseOverloadsDoNotSelfSuppressReusedException() throws SQLException {
        final SQLException resultSetStatementFailure = new SQLException("shared result-set/statement failure");
        final ResultSet rs1 = mock(ResultSet.class);
        final Statement stmt1 = mock(Statement.class);
        doThrow(resultSetStatementFailure).when(rs1).close();
        doThrow(resultSetStatementFailure).when(stmt1).close();

        final UncheckedSQLException rsStmtThrown = assertThrows(UncheckedSQLException.class, () -> JdbcUtil.close(rs1, stmt1));
        assertSame(resultSetStatementFailure, rsStmtThrown.getCause());
        assertEquals(0, resultSetStatementFailure.getSuppressed().length);

        final SQLException statementConnectionFailure = new SQLException("shared statement/connection failure");
        final Statement stmt2 = mock(Statement.class);
        final Connection conn2 = mock(Connection.class);
        doThrow(statementConnectionFailure).when(stmt2).close();
        doThrow(statementConnectionFailure).when(conn2).close();

        final UncheckedSQLException stmtConnThrown = assertThrows(UncheckedSQLException.class, () -> JdbcUtil.close(stmt2, conn2));
        assertSame(statementConnectionFailure, stmtConnThrown.getCause());
        assertEquals(0, statementConnectionFailure.getSuppressed().length);

        final SQLException allResourcesFailure = new SQLException("shared three-resource failure");
        final ResultSet rs3 = mock(ResultSet.class);
        final Statement stmt3 = mock(Statement.class);
        final Connection conn3 = mock(Connection.class);
        doThrow(allResourcesFailure).when(rs3).close();
        doThrow(allResourcesFailure).when(stmt3).close();
        doThrow(allResourcesFailure).when(conn3).close();

        final UncheckedSQLException allThrown = assertThrows(UncheckedSQLException.class, () -> JdbcUtil.close(rs3, stmt3, conn3));
        assertSame(allResourcesFailure, allThrown.getCause());
        assertEquals(0, allResourcesFailure.getSuppressed().length);
        verify(rs3).close();
        verify(stmt3).close();
        verify(conn3).close();
    }

    @Test
    public void testCloseQuietly_InvalidFlags() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.closeQuietly(mockResultSet, false, true));
    }

    @Test
    public void testCloseQuietly_NullResultSetWithFlags() {
        assertDoesNotThrow(() -> JdbcUtil.closeQuietly((ResultSet) null, true, true));
    }

    // closeQuietly(Statement, Connection) — both null is no-op (lines 1517-1519).
    @Test
    public void testCloseQuietly_StatementConnection_NullSafe() {
        assertDoesNotThrow(() -> JdbcUtil.closeQuietly((Statement) null, (Connection) null));
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

    // Test closeQuietly with statement+connection flag and exception getting statement
    @Test
    public void testCloseQuietlyWithStatementAndConnection_StatementSQLException() throws SQLException {
        doThrow(new SQLException("getStatement failed")).when(mockResultSet).getStatement();
        // Should not throw even when getStatement() fails
        assertDoesNotThrow(() -> JdbcUtil.closeQuietly(mockResultSet, true, true));
    }

    // closeQuietly must stay quiet when the statement lookup fails with an unchecked exception, and still close rs.
    @Test
    public void testCloseQuietlyWithStatementAndConnection_StatementRuntimeException() throws SQLException {
        doThrow(new IllegalStateException("getStatement failed")).when(mockResultSet).getStatement();

        assertDoesNotThrow(() -> JdbcUtil.closeQuietly(mockResultSet, true, true));
        assertDoesNotThrow(() -> JdbcUtil.closeQuietly(mockResultSet, true));
        verify(mockResultSet, org.mockito.Mockito.times(2)).close();
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

    // closeQuietly ResultSet flags - closeResultSet without closeStatement is valid
    @Test
    public void testCloseQuietly_CloseRsWithoutStmt_Valid() throws SQLException {
        assertDoesNotThrow(() -> JdbcUtil.closeQuietly(mockResultSet, false));
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

        String result = JdbcUtil.blobToString(mockBlob);
        assertEquals(data, result);
        verify(mockBlob).free();
    }

    @Test
    public void testBlob2StringWithCharset() throws SQLException {
        String data = "test blob data";
        byte[] bytes = data.getBytes(StandardCharsets.ISO_8859_1);
        when(mockBlob.length()).thenReturn((long) bytes.length);
        when(mockBlob.getBytes(1, bytes.length)).thenReturn(bytes);

        String result = JdbcUtil.blobToString(mockBlob, StandardCharsets.ISO_8859_1);
        assertEquals(data, result);
        verify(mockBlob).free();
    }

    // blobToString edge cases
    @Test
    public void testBlob2String_NullBlob() throws SQLException {
        assertNull(JdbcUtil.blobToString(null));
    }

    @Test
    public void testBlob2String_NullBlobWithCharset() throws SQLException {
        assertNull(JdbcUtil.blobToString(null, StandardCharsets.UTF_8));
    }

    @Test
    public void testBlob2String_PreservesReadFailureWhenFreeAlsoFails() throws SQLException {
        final SQLException readFailure = new SQLException("read failed");
        final SQLException freeFailure = new SQLException("free failed");
        when(mockBlob.length()).thenThrow(readFailure);
        doThrow(freeFailure).when(mockBlob).free();

        final SQLException thrown = assertThrows(SQLException.class, () -> JdbcUtil.blobToString(mockBlob));
        assertSame(readFailure, thrown);
        assertArrayEquals(new Throwable[] { freeFailure }, thrown.getSuppressed());
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

    // writeBlobToFile edge cases
    @Test
    public void testWriteBlobToFile_NullBlob() throws SQLException, IOException {
        assertEquals(0L, JdbcUtil.writeBlobToFile(null, new File("output.txt")));
    }

    @Test
    public void testClob2String() throws SQLException {
        String data = "test clob data";
        when(mockClob.length()).thenReturn((long) data.length());
        when(mockClob.getSubString(1, data.length())).thenReturn(data);

        String result = JdbcUtil.clobToString(mockClob);
        assertEquals(data, result);
        verify(mockClob).free();
    }

    // clobToString edge cases
    @Test
    public void testClob2String_NullClob() throws SQLException {
        assertNull(JdbcUtil.clobToString(null));
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

    // writeClobToFile edge cases
    @Test
    public void testWriteClobToFile_NullClob() throws SQLException, IOException {
        assertEquals(0L, JdbcUtil.writeClobToFile(null, new File("output.txt")));
    }

    @Nested
    class ExecutionParameterCleanup {
        @Test
        void successfulQueryKeepsItsResultSetWhenParameterCleanupFailsUnchecked() throws SQLException {
            final PreparedStatement stmt = mock(PreparedStatement.class);
            final ResultSet driverResult = mock(ResultSet.class);
            when(stmt.executeQuery()).thenReturn(driverResult);
            when(driverResult.next()).thenReturn(true);
            when(driverResult.getInt(1)).thenReturn(42);
            doThrow(new IllegalStateException("clear failed")).when(stmt).clearParameters();

            try (ResultSet result = JdbcUtil.executeQuery(stmt)) {
                assertTrue(result.next());
                assertEquals(42, result.getInt(1));
            }

            verify(stmt).clearParameters();
            verify(driverResult).close();
        }

        @Test
        void executionFailuresSurviveUncheckedParameterCleanupForEveryOperation() throws SQLException {
            for (int operation = 0; operation < 4; operation++) {
                final PreparedStatement stmt = mock(PreparedStatement.class);
                final SQLException primary = new SQLException("execution failed");
                doThrow(primary).when(stmt).executeQuery();
                doThrow(primary).when(stmt).executeUpdate();
                doThrow(primary).when(stmt).executeLargeUpdate();
                doThrow(primary).when(stmt).execute();
                doThrow(new IllegalStateException("clear failed")).when(stmt).clearParameters();
                final int selectedOperation = operation;

                assertSame(primary, assertThrows(SQLException.class, () -> execute(stmt, selectedOperation)));
                verify(stmt).clearParameters();
            }
        }

        @Test
        void fatalParameterCleanupErrorsStillPropagate() throws SQLException {
            final PreparedStatement stmt = mock(PreparedStatement.class);
            final AssertionError failure = new AssertionError("fatal cleanup");
            doThrow(failure).when(stmt).clearParameters();

            assertSame(failure, assertThrows(AssertionError.class, () -> JdbcUtil.clearParameters(stmt)));
        }

        @Test
        void successfulUpdateAndExecuteResultsSurviveUncheckedParameterCleanup() throws SQLException {
            final PreparedStatement stmt = mock(PreparedStatement.class);
            final long largeCount = (long) Integer.MAX_VALUE + 1;
            when(stmt.executeUpdate()).thenReturn(7);
            when(stmt.executeLargeUpdate()).thenReturn(largeCount);
            when(stmt.execute()).thenReturn(true);
            doThrow(new IllegalStateException("clear failed")).when(stmt).clearParameters();

            assertEquals(7, JdbcUtil.executeUpdate(stmt));
            assertEquals(largeCount, JdbcUtil.executeLargeUpdate(stmt));
            assertTrue(JdbcUtil.execute(stmt));
            verify(stmt, never()).close();
        }

        @Test
        void uncheckedExecutionFailuresSurviveBestEffortParameterCleanup() throws SQLException {
            for (final Throwable primary : new Throwable[] { new IllegalStateException("execution failed"), new AssertionError("execution failed") }) {
                final PreparedStatement stmt = mock(PreparedStatement.class);
                doThrow(primary).when(stmt).execute();
                doThrow(new IllegalStateException("clear failed")).when(stmt).clearParameters();

                assertSame(primary, assertThrows(primary.getClass(), () -> JdbcUtil.execute(stmt)));
                verify(stmt).clearParameters();
            }
        }

        @Test
        void callableStatementsKeepTheirBindingsAndOutRegistrations() throws SQLException {
            final CallableStatement stmt = mock(CallableStatement.class);
            final ResultSet driverResult = mock(ResultSet.class);
            when(stmt.executeQuery()).thenReturn(driverResult);

            JdbcUtil.clearParameters(null);
            try (ResultSet result = JdbcUtil.executeQuery(stmt)) {
                JdbcUtil.executeUpdate(stmt);
                JdbcUtil.executeLargeUpdate(stmt);
                JdbcUtil.execute(stmt);
            }

            verify(stmt, never()).clearParameters();
            verify(stmt, never()).close();
            verify(driverResult).close();
        }

        private static Object execute(final PreparedStatement stmt, final int operation) throws SQLException {
            return switch (operation) {
                case 0 -> JdbcUtil.executeQuery(stmt);
                case 1 -> JdbcUtil.executeUpdate(stmt);
                case 2 -> JdbcUtil.executeLargeUpdate(stmt);
                case 3 -> JdbcUtil.execute(stmt);
                default -> throw new AssertionError("Unknown operation: " + operation);
            };
        }
    }
}
