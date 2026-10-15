package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import com.landawn.abacus.exception.UncheckedSQLException;

class JdbcCodeGenerationSqlTest extends JdbcCodeGenerationUtilTestSupport {

    @Test
    public void testGenerateSelectSql_WithExcludedColumns() {
        String sql = JdbcCodeGenerationUtil.generateSelectSql(connection, "order_history", List.of("createdAt"), "status = 'OPEN'");

        assertEquals("SELECT id, status FROM order_history WHERE status = 'OPEN'", sql);
    }

    // Test generateSelectSql with DataSource (basic overload)
    @Test
    public void testGenerateSelectSql_WithDataSource() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        String sql = JdbcCodeGenerationUtil.generateSelectSql(dataSource, "order_history");
        assertNotNull(sql);
        assertTrue(sql.startsWith("SELECT"));
    }

    // generateSelectSql(DataSource, tableName, excludedColumns, whereClause) - line 940
    @Test
    public void testGenerateSelectSql_DataSource_WithExcludedColumnsAndWhere() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String sql = JdbcCodeGenerationUtil.generateSelectSql(dataSource, "order_history", Arrays.asList("status"), "id > 0");
        assertNotNull(sql);
        assertTrue(sql.startsWith("SELECT"));
    }

    @Test
    public void testGenerateSelectSql_RejectsSpecialTableNameBeforeMetadataQuery() throws SQLException {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateSelectSql(connection, "order-history"));

        assertTrue(failure.getMessage().contains("quoting is disabled"), failure.getMessage());
        Mockito.verify(connection, Mockito.never()).prepareStatement(ArgumentMatchers.anyString());
        Mockito.verify(databaseMetaData, Mockito.never()).getIdentifierQuoteString();
    }

    @Test
    public void testGenerateSelectSql_RejectsDecodedMetadataLabelContainingDot() throws SQLException {
        when(resultSetMetaData.getColumnLabel(2)).thenReturn("user.name");

        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateSelectSql(connection, "order_history"));

        assertTrue(failure.getMessage().contains("quoting is disabled"), failure.getMessage());
        Mockito.verify(preparedStatement).close();
        Mockito.verify(resultSet).close();
        Mockito.verify(databaseMetaData, Mockito.never()).getIdentifierQuoteString();
    }

    @Test
    public void testGenerateSelectSql_RejectsQualifiedSpecialTableName() throws SQLException {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateSelectSql(connection, "sales.order-history"));

        assertTrue(failure.getMessage().contains("quoting is disabled"), failure.getMessage());
        Mockito.verify(connection, Mockito.never()).prepareStatement(ArgumentMatchers.anyString());
        Mockito.verify(databaseMetaData, Mockito.never()).getIdentifierQuoteString();
    }

    @Test
    public void testGenerateSelectSql_RejectsQuotedDotsWithinSingleIdentifier() throws SQLException {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateSelectSql(connection, "\"sales.data\""));

        assertTrue(failure.getMessage().contains("quoting is disabled"), failure.getMessage());
        Mockito.verify(connection, Mockito.never()).prepareStatement(ArgumentMatchers.anyString());
        Mockito.verify(databaseMetaData, Mockito.never()).getIdentifierQuoteString();
    }

    @Test
    public void testGenerateSelectSql_RejectsExplicitDelimiterOnSimpleQualifiedPart() throws SQLException {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateSelectSql(connection, "sales.\"events\""));

        assertTrue(failure.getMessage().contains("quoting is disabled"), failure.getMessage());
        Mockito.verify(connection, Mockito.never()).prepareStatement(ArgumentMatchers.anyString());
        Mockito.verify(databaseMetaData, Mockito.never()).getIdentifierQuoteString();
    }

    @Test
    public void testGenerateSelectSql_RejectsSinglePartIdentifierStartingWithDigit() throws SQLException {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateSelectSql(connection, "123abc"));

        assertTrue(failure.getMessage().contains("quoting is disabled"), failure.getMessage());
        Mockito.verify(connection, Mockito.never()).prepareStatement(ArgumentMatchers.anyString());
        Mockito.verify(databaseMetaData, Mockito.never()).getIdentifierQuoteString();
    }

    @Test
    public void testGenerateSelectSql_RejectsEmbeddedBacktickInColumnName() throws SQLException {
        when(resultSetMetaData.getColumnLabel(2)).thenReturn("we`ird");

        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateSelectSql(connection, "order_history"));

        assertTrue(failure.getMessage().contains("quoting is disabled"), failure.getMessage());
        Mockito.verify(preparedStatement).close();
        Mockito.verify(resultSet).close();
        Mockito.verify(databaseMetaData, Mockito.never()).getIdentifierQuoteString();
    }

    @Test
    public void testGenerateSelectSql_RejectsEmbeddedDoubleQuoteInColumnName() throws SQLException {
        when(resultSetMetaData.getColumnLabel(2)).thenReturn("we\"ird");

        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateSelectSql(connection, "order_history"));

        assertTrue(failure.getMessage().contains("quoting is disabled"), failure.getMessage());
        Mockito.verify(preparedStatement).close();
        Mockito.verify(resultSet).close();
        Mockito.verify(databaseMetaData, Mockito.never()).getIdentifierQuoteString();
    }

    // A whitespace-only whereClause used to be appended verbatim, producing unparseable SQL such as
    // "UPDATE order_history SET ... WHERE   ". It is now treated like null/empty and omitted.
    @Test
    public void testGenerateSelectSql_BlankWhereClauseIsOmitted() {
        assertEquals("SELECT id, created_at, status FROM order_history", JdbcCodeGenerationUtil.generateSelectSql(connection, "order_history", null, "   "));
        assertEquals("SELECT id, created_at, status FROM order_history", JdbcCodeGenerationUtil.generateSelectSql(connection, "order_history", null, "\t\n"));
    }

    // Excluding every column must fail fast with IllegalArgumentException instead of emitting malformed SQL
    // (mirrors the existing guard in the UPDATE family).
    @Test
    public void testGenerateSelectSql_AllColumnsExcluded_Throws() {
        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateSelectSql(connection, "order_history", List.of("id", "created_at", "status"), null));
    }

    @Test
    public void testGenerateSelectSql_DataSourceWrapsSQLException() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("connection failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateSelectSql(dataSource, "demo"));
    }

    // Tests for SQL generation methods that wrap SQLException into UncheckedSQLException
    // (DataSource overloads — covers catch blocks at lines 958-959, 1026-1027, 1087-1088,
    // 1155-1156, 1218-1219, 1287-1288, 1348-1349, 1424-1425, 1496-1497, 1524-1525,
    // 1589-1590, 1660-1661).

    @Test
    public void testGenerateSelectSql_DataSourceWithExcluded_WrapsSQLException() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("ds failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateSelectSql(ds, "t", List.of("x"), "id = 1"));
    }

    @Test
    public void testGenerateSelectSql_Connection_WrapsSQLException() throws SQLException {
        final Connection conn = mockConnectionThatThrowsOnExecuteQuery();
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateSelectSql(conn, "t"));
    }

    @Test
    public void testGenerateSelectSql_ConnectionWithExcluded_WrapsSQLException() throws SQLException {
        final Connection conn = mockConnectionThatThrowsOnExecuteQuery();
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateSelectSql(conn, "t", List.of("x"), null));
    }

    @Test
    public void testGenerateInsertSql_WithExcludedColumns() {
        String sql = JdbcCodeGenerationUtil.generateInsertSql(connection, "order_history", List.of("status"));

        assertEquals("INSERT INTO order_history(id, created_at) VALUES (?, ?)", sql);
    }

    // Test generateInsertSql with DataSource
    @Test
    public void testGenerateInsertSql_WithDataSource() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        String sql = JdbcCodeGenerationUtil.generateInsertSql(dataSource, "order_history");
        assertNotNull(sql);
        assertTrue(sql.startsWith("INSERT"));
    }

    // generateInsertSql(DataSource, tableName, excludedColumns) - line 1069
    @Test
    public void testGenerateInsertSql_DataSource_WithExcludedColumns() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String sql = JdbcCodeGenerationUtil.generateInsertSql(dataSource, "order_history", Arrays.asList("id"));
        assertNotNull(sql);
        assertTrue(sql.startsWith("INSERT"));
    }

    @Test
    public void testGenerateInsertSql_RejectsSpecialTableNameBeforeMetadataQuery() throws SQLException {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateInsertSql(connection, "order-history"));

        assertTrue(failure.getMessage().contains("quoting is disabled"), failure.getMessage());
        Mockito.verify(connection, Mockito.never()).prepareStatement(ArgumentMatchers.anyString());
        Mockito.verify(databaseMetaData, Mockito.never()).getIdentifierQuoteString();
    }

    @Test
    public void testGenerateInsertSql_AllColumnsExcluded_Throws() {
        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateInsertSql(connection, "order_history", List.of("id", "created_at", "status")));
    }

    @Test
    public void testGenerateInsertSql_DataSource_WrapsSQLException() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("ds failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateInsertSql(ds, "t"));
    }

    @Test
    public void testGenerateInsertSql_DataSourceWithExcluded_WrapsSQLException() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("ds failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateInsertSql(ds, "t", List.of("x")));
    }

    @Test
    public void testGenerateInsertSql_Connection_WrapsSQLException() throws SQLException {
        final Connection conn = mockConnectionThatThrowsOnExecuteQuery();
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateInsertSql(conn, "t"));
    }

    @Test
    public void testGenerateInsertSql_ConnectionWithExcluded_WrapsSQLException() throws SQLException {
        final Connection conn = mockConnectionThatThrowsOnExecuteQuery();
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateInsertSql(conn, "t", List.of("x")));
    }

    // generateNamedInsertSql(DataSource, tableName) - line 1138
    @Test
    public void testGenerateNamedInsertSql_DataSource() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String sql = JdbcCodeGenerationUtil.generateNamedInsertSql(dataSource, "order_history");
        assertNotNull(sql);
        assertTrue(sql.startsWith("INSERT"));
    }

    // generateNamedInsertSql(Connection, tableName) - line 1164
    @Test
    public void testGenerateNamedInsertSql_Connection() throws SQLException {
        String sql = JdbcCodeGenerationUtil.generateNamedInsertSql(connection, "order_history");
        assertNotNull(sql);
        assertTrue(sql.startsWith("INSERT"));
    }

    // generateNamedInsertSql(DataSource, tableName, excludedColumns) - line 1200
    @Test
    public void testGenerateNamedInsertSql_DataSource_WithExcludedColumns() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String sql = JdbcCodeGenerationUtil.generateNamedInsertSql(dataSource, "order_history", Arrays.asList("id"));
        assertNotNull(sql);
        assertTrue(sql.startsWith("INSERT"));
    }

    // generateNamedInsertSql(Connection, tableName, excludedColumns) - line 1231
    @Test
    public void testGenerateNamedInsertSql_Connection_WithExcludedColumns() throws SQLException {
        String sql = JdbcCodeGenerationUtil.generateNamedInsertSql(connection, "order_history", Arrays.asList("id"));
        assertNotNull(sql);
        assertTrue(sql.startsWith("INSERT"));
    }

    @Test
    public void testGenerateNamedInsertSql_InvalidParameterName_Throws() throws SQLException {
        when(resultSetMetaData.getColumnCount()).thenReturn(1);
        when(resultSetMetaData.getColumnLabel(1)).thenReturn("123value");

        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateNamedInsertSql(connection, "order_history"));
        assertTrue(failure.getMessage().contains("valid named parameter"), failure.getMessage());
    }

    // A stored label whose case would change cannot be emitted unquoted without changing its identity.
    @Test
    public void testGenerateSqlRejectsLabelsWhoseCaseWouldBeFolded_LowerCaseDatabase() throws SQLException {
        when(databaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(databaseMetaData.getIdentifierQuoteString()).thenReturn("\"");
        when(databaseMetaData.storesLowerCaseIdentifiers()).thenReturn(true);
        when(resultSetMetaData.getColumnLabel(2)).thenReturn("createdAt");
        when(resultSetMetaData.getColumnLabel(3)).thenReturn("STATUS");

        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateSelectSql(connection, "order_history"));
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateNamedInsertSql(connection, "order_history"));
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql(connection, "order_history", "id"));
        assertEquals("SELECT id FROM order_history",
                JdbcCodeGenerationUtil.generateSelectSql(connection, "order_history", List.of("createdAt", "STATUS"), null));
        Mockito.verify(databaseMetaData, Mockito.never()).getIdentifierQuoteString();
    }

    @Test
    public void testGenerateNamedInsertSql_AllColumnsExcluded_Throws() {
        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateNamedInsertSql(connection, "order_history", List.of("id", "created_at", "status")));
    }

    @Test
    public void testGenerateNamedInsertSql_CollidingParameterNames_Throws() throws SQLException {
        when(resultSetMetaData.getColumnCount()).thenReturn(2);
        when(resultSetMetaData.getColumnLabel(1)).thenReturn("user_id");
        when(resultSetMetaData.getColumnLabel(2)).thenReturn("userId");

        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateNamedInsertSql(connection, "order_history"));
        assertTrue(failure.getMessage().contains("both map to named parameter"), failure.getMessage());
    }

    @Test
    public void testGenerateNamedInsertSql_DataSource_WrapsSQLException() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("ds failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateNamedInsertSql(ds, "t"));
    }

    @Test
    public void testGenerateNamedInsertSql_DataSourceWithExcluded_WrapsSQLException() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("ds failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateNamedInsertSql(ds, "t", List.of("x")));
    }

    @Test
    public void testGenerateNamedInsertSql_Connection_WrapsSQLException() throws SQLException {
        final Connection conn = mockConnectionThatThrowsOnExecuteQuery();
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateNamedInsertSql(conn, "t"));
    }

    @Test
    public void testGenerateNamedInsertSql_ConnectionWithExcluded_WrapsSQLException() throws SQLException {
        final Connection conn = mockConnectionThatThrowsOnExecuteQuery();
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateNamedInsertSql(conn, "t", List.of("x")));
    }

    @Test
    public void testGenerateUpdateSql_ResolvesCamelCaseKeyToActualColumn() {
        final String sql = JdbcCodeGenerationUtil.generateUpdateSql(connection, "order_history", "createdAt");

        assertEquals("UPDATE order_history SET id = ?, status = ? WHERE created_at = ?", sql);
    }

    // generateUpdateSql(DataSource, tableName) - line 1270
    @Test
    public void testGenerateUpdateSql_DataSource() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String sql = JdbcCodeGenerationUtil.generateUpdateSql(dataSource, "order_history");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
    }

    // generateUpdateSql(Connection, tableName) - line 1298
    @Test
    public void testGenerateUpdateSql_Connection() throws SQLException {
        String sql = JdbcCodeGenerationUtil.generateUpdateSql(connection, "order_history");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
    }

    // generateUpdateSql(DataSource, tableName, keyColumnName) - line 1331
    @Test
    public void testGenerateUpdateSql_DataSource_WithKeyColumn() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String sql = JdbcCodeGenerationUtil.generateUpdateSql(dataSource, "order_history", "id");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
    }

    // generateUpdateSql(Connection, tableName, keyColumnName) - line 1357
    @Test
    public void testGenerateUpdateSql_Connection_WithKeyColumn() throws SQLException {
        String sql = JdbcCodeGenerationUtil.generateUpdateSql(connection, "order_history", "id");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
    }

    // generateUpdateSql(DataSource, tableName, excludedColumnNames, keyColumnNames, whereClause) - line 1420
    @Test
    public void testGenerateUpdateSql_DataSource_WithExcludedColumnsAndWhere() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String sql = JdbcCodeGenerationUtil.generateUpdateSql(dataSource, "order_history", Arrays.asList("created_at"), Arrays.asList("id"), "status = 'OPEN'");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
        assertTrue(sql.contains("WHERE"));
    }

    // generateUpdateSql(Connection, tableName, excludedColumnNames, keyColumnNames, whereClause) - line 1460
    @Test
    public void testGenerateUpdateSql_Connection_WithExcludedColumnsAndWhere() throws SQLException {
        String sql = JdbcCodeGenerationUtil.generateUpdateSql(connection, "order_history", Arrays.asList("created_at"), Arrays.asList("id"), "status = 'OPEN'");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
        assertTrue(sql.contains("WHERE"));
    }

    // generateUpdateSql with whereClause only (no keyColumnNames) — L1501
    @Test
    public void testGenerateUpdateSql_WithWhereClauseOnly_NoKeyColumns() throws SQLException {
        final String sql = JdbcCodeGenerationUtil.generateUpdateSql(connection, "order_history", null, null, "status = 'OPEN'");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
        assertTrue(sql.contains("WHERE status = 'OPEN'"));
    }

    // A resolved key column is removed from the SET clause by its exact label, as in the single-key overload. Removing it
    // after camelCase normalization silently dropped a distinct column ("userId" for key "user_id") from the SET clause.
    @Test
    public void testGenerateUpdateSql_KeyColumnsRemovedFromSetByExactLabel() throws SQLException {
        when(databaseMetaData.storesUpperCaseIdentifiers()).thenReturn(false);
        when(databaseMetaData.storesLowerCaseIdentifiers()).thenReturn(false);
        when(resultSetMetaData.getColumnLabel(1)).thenReturn("user_id");
        when(resultSetMetaData.getColumnLabel(2)).thenReturn("userId");

        assertEquals("UPDATE order_history SET userId = ?, status = ? WHERE user_id = ?",
                JdbcCodeGenerationUtil.generateUpdateSql(connection, "order_history", null, List.of("user_id"), null));
        assertEquals(JdbcCodeGenerationUtil.generateUpdateSql(connection, "order_history", "user_id") + " AND (status = 'OPEN')",
                JdbcCodeGenerationUtil.generateUpdateSql(connection, "order_history", null, List.of("user_id"), "status = 'OPEN'"));

        // Named form: the kept "userId" column and the key "user_id" both map to :userId, rejected like the single-key overload.
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(connection, "order_history", "user_id"));
        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(connection, "order_history", null, List.of("user_id"), null));
    }

    @Test
    public void testGenerateUpdateSql_BlankWhereClauseIsOmitted() {
        assertEquals("UPDATE order_history SET id = ?, created_at = ?, status = ?",
                JdbcCodeGenerationUtil.generateUpdateSql(connection, "order_history", null, null, "   "));
        assertEquals("UPDATE order_history SET created_at = ?, status = ? WHERE id = ?",
                JdbcCodeGenerationUtil.generateUpdateSql(connection, "order_history", null, List.of("id"), " \t "));
    }

    @Test
    public void testGenerateUpdateSql_DataSource_WrapsSQLException() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("ds failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql(ds, "t"));
    }

    @Test
    public void testGenerateUpdateSql_DataSourceWithKey_WrapsSQLException() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("ds failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql(ds, "t", "id"));
    }

    @Test
    public void testGenerateUpdateSql_DataSourceWithExcludedAndKeys_WrapsSQLException() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("ds failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql(ds, "t", List.of("x"), List.of("id"), null));
    }

    @Test
    public void testGenerateUpdateSql_Connection_WrapsSQLException() throws SQLException {
        final Connection conn = mockConnectionThatThrowsOnExecuteQuery();
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql(conn, "t"));
    }

    @Test
    public void testGenerateUpdateSql_ConnectionWithKey_WrapsSQLException() throws SQLException {
        final Connection conn = mockConnectionThatThrowsOnExecuteQuery();
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql(conn, "t", "id"));
    }

    @Test
    public void testGenerateUpdateSql_ConnectionWithExcludedAndKeys_WrapsSQLException() throws SQLException {
        final Connection conn = mockConnectionThatThrowsOnExecuteQuery();
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql(conn, "t", List.of("x"), List.of("id"), null));
    }

    @Test
    public void testGenerateUpdateSql_AllColumnsExcludedFromSet_Throws() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateUpdateSql(connection, "order_history", null, List.of("id", "createdAt", "status"), null));
    }

    @Test
    public void testGenerateUpdateSql_SingleKeyOnlyColumn_Throws() throws SQLException {
        when(resultSetMetaData.getColumnCount()).thenReturn(1);
        when(resultSetMetaData.getColumnLabel(1)).thenReturn("id");

        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql(connection, "order_history", "id"));
    }

    // resolveKeyColumnNames exact-match ambiguity branch: two metadata labels that are case variants of
    // each other ("USER_ID" / "user_id") both equalsIgnoreCase-match the requested key column, so the key
    // cannot be resolved to a single column and must be rejected instead of silently picking one.
    @Test
    @Tag("2025")
    public void testGenerateUpdateSql_KeyColumnMatchesTwoCaseVariantLabels_AmbiguousThrows() throws SQLException {
        when(resultSetMetaData.getColumnCount()).thenReturn(3);
        when(resultSetMetaData.getColumnLabel(1)).thenReturn("USER_ID");
        when(resultSetMetaData.getColumnLabel(2)).thenReturn("user_id");
        when(resultSetMetaData.getColumnLabel(3)).thenReturn("status");

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateUpdateSql(connection, "order_history", "user_id"));

        assertTrue(thrown.getMessage().contains("Ambiguous key column"), () -> "unexpected message: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("user_id"), () -> "unexpected message: " + thrown.getMessage());
    }

    // generateNamedUpdateSql(DataSource, tableName) - line 1507
    @Test
    public void testGenerateNamedUpdateSql_DataSource() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String sql = JdbcCodeGenerationUtil.generateNamedUpdateSql(dataSource, "order_history");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
    }

    // generateNamedUpdateSql(Connection, tableName) - line 1536
    @Test
    public void testGenerateNamedUpdateSql_Connection() throws SQLException {
        String sql = JdbcCodeGenerationUtil.generateNamedUpdateSql(connection, "order_history");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
    }

    // generateNamedUpdateSql(DataSource, tableName, keyColumnName) - line 1572
    @Test
    public void testGenerateNamedUpdateSql_DataSource_WithKeyColumn() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String sql = JdbcCodeGenerationUtil.generateNamedUpdateSql(dataSource, "order_history", "id");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
    }

    // generateNamedUpdateSql(Connection, tableName, keyColumnName) - line 1598
    @Test
    public void testGenerateNamedUpdateSql_Connection_WithKeyColumn() throws SQLException {
        String sql = JdbcCodeGenerationUtil.generateNamedUpdateSql(connection, "order_history", "id");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
    }

    // generateNamedUpdateSql(DataSource, tableName, excludedColumnNames, keyColumnNames, whereClause) - line 1656
    @Test
    public void testGenerateNamedUpdateSql_DataSource_WithExcludedColumnsAndWhere() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String sql = JdbcCodeGenerationUtil.generateNamedUpdateSql(dataSource, "order_history", Arrays.asList("created_at"), Arrays.asList("id"),
                "status = 'OPEN'");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
        assertTrue(sql.contains(":"));
        assertTrue(sql.contains("WHERE"));
    }

    // generateNamedUpdateSql(Connection, tableName, excludedColumnNames, keyColumnNames, whereClause) - line 1696
    @Test
    public void testGenerateNamedUpdateSql_Connection_WithExcludedColumnsAndWhere() throws SQLException {
        String sql = JdbcCodeGenerationUtil.generateNamedUpdateSql(connection, "order_history", Arrays.asList("created_at"), Arrays.asList("id"),
                "status = 'OPEN'");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
        assertTrue(sql.contains(":"));
        assertTrue(sql.contains("WHERE"));
    }

    // generateNamedUpdateSql with whereClause only (no keyColumnNames) — L1737
    @Test
    public void testGenerateNamedUpdateSql_WithWhereClauseOnly_NoKeyColumns() throws SQLException {
        final String sql = JdbcCodeGenerationUtil.generateNamedUpdateSql(connection, "order_history", null, null, "status = 'OPEN'");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
        assertTrue(sql.contains("WHERE status = 'OPEN'"));
    }

    @Test
    public void testGenerateNamedUpdateSql_BlankWhereClauseIsOmitted() {
        assertEquals("UPDATE order_history SET id = :id, created_at = :createdAt, status = :status",
                JdbcCodeGenerationUtil.generateNamedUpdateSql(connection, "order_history", null, null, "   "));
        assertEquals("UPDATE order_history SET created_at = :createdAt, status = :status WHERE id = :id",
                JdbcCodeGenerationUtil.generateNamedUpdateSql(connection, "order_history", null, List.of("id"), " \t "));
    }

    @Test
    public void testGenerateNamedUpdateSql_MissingKeyColumn_Throws() {
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(connection, "order_history", "missingKey"));
    }

    @Test
    public void testGenerateNamedUpdateSql_DataSource_WrapsSQLException() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("ds failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(ds, "t"));
    }

    @Test
    public void testGenerateNamedUpdateSql_DataSourceWithKey_WrapsSQLException() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("ds failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(ds, "t", "id"));
    }

    @Test
    public void testGenerateNamedUpdateSql_DataSourceWithExcludedAndKeys_WrapsSQLException() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("ds failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(ds, "t", List.of("x"), List.of("id"), null));
    }

    @Test
    public void testGenerateNamedUpdateSql_Connection_WrapsSQLException() throws SQLException {
        final Connection conn = mockConnectionThatThrowsOnExecuteQuery();
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "t"));
    }

    @Test
    public void testGenerateNamedUpdateSql_ConnectionWithKey_WrapsSQLException() throws SQLException {
        final Connection conn = mockConnectionThatThrowsOnExecuteQuery();
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "t", "id"));
    }

    @Test
    public void testGenerateNamedUpdateSql_ConnectionWithExcludedAndKeys_WrapsSQLException() throws SQLException {
        final Connection conn = mockConnectionThatThrowsOnExecuteQuery();
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "t", List.of("x"), List.of("id"), null));
    }

    @Test
    public void testGenerateNamedUpdateSql_AllColumnsExcludedFromSet_Throws() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(connection, "order_history", null, List.of("id", "createdAt", "status"), null));
    }

    @Test
    public void testGenerateNamedUpdateSql_SingleKeyOnlyColumn_Throws() throws SQLException {
        when(resultSetMetaData.getColumnCount()).thenReturn(1);
        when(resultSetMetaData.getColumnLabel(1)).thenReturn("id");

        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(connection, "order_history", "id"));
    }

    // resolveKeyColumnNames camelCase-match ambiguity branch: the requested key "user-id" exact-matches
    // no metadata label, but its camelCase normalization "userId" matches BOTH "user_id" and "userId",
    // so the fallback resolution must be rejected as ambiguous (naming both candidate columns).
    @Test
    @Tag("2025")
    public void testGenerateNamedUpdateSql_CamelCaseKeyMatchesTwoDistinctLabels_AmbiguousThrows() throws SQLException {
        when(resultSetMetaData.getColumnCount()).thenReturn(3);
        when(resultSetMetaData.getColumnLabel(1)).thenReturn("user_id");
        when(resultSetMetaData.getColumnLabel(2)).thenReturn("userId");
        when(resultSetMetaData.getColumnLabel(3)).thenReturn("status");

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(connection, "order_history", "user-id"));

        assertTrue(thrown.getMessage().contains("Ambiguous key column"), () -> "unexpected message: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("matches both"), () -> "unexpected message: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("user_id"), () -> "unexpected message: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("userId"), () -> "unexpected message: " + thrown.getMessage());
    }

    // Tests for the Connection-based catch blocks (lines 930-931, 1001-1002, 1060-1061,
    // 1130-1131, 1190-1191, 1261-1262, 1323-1324, 1385-1386, 1496-1497, 1564-1565,
    // 1626-1627, 1736-1737) — triggered when the inner JDBC operation throws SQLException.

    private Connection mockConnectionThatThrowsOnExecuteQuery() throws SQLException {
        final Connection conn = Mockito.mock(Connection.class);
        final DatabaseMetaData md = Mockito.mock(DatabaseMetaData.class);
        final PreparedStatement stmt = Mockito.mock(PreparedStatement.class);

        when(conn.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("MySQL");
        when(md.getIdentifierQuoteString()).thenReturn("`");
        when(md.getDatabaseProductVersion()).thenReturn("8.0");
        when(conn.prepareStatement(ArgumentMatchers.anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenThrow(new SQLException("execute failed"));
        return conn;
    }
}
