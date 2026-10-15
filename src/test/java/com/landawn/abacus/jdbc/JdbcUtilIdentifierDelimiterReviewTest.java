package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("2025")
class JdbcUtilIdentifierDelimiterReviewTest {

    @Test
    void qualifiedIdentifiersUseMatchingBracketDelimiters() throws SQLException {
        final Connection conn = bracketQuotedConnection();

        assertEquals("[CATALOG].[ARCHIVE].[EVENTS]", JdbcUtil.toQualifiedSqlIdentifier(conn, "catalog.archive.events", "tableName"));
        assertEquals("[MixedCase].[a]]b]", JdbcUtil.toQualifiedSqlIdentifier(conn, "\"MixedCase\".\"a]b\"", "tableName"));
        assertEquals("[a[b]", JdbcUtil.toQualifiedSqlIdentifier(conn, "\"a[b\"", "tableName"));
    }

    @Test
    void tableExistenceFallbackUsesMatchingBracketDelimiters() throws SQLException {
        final Connection conn = bracketQuotedConnection();
        final PreparedStatement stmt = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(stmt);

        assertTrue(JdbcUtil.tableExists(conn, "archive.events"));

        verify(conn).prepareStatement("SELECT 1 FROM [ARCHIVE].[EVENTS] WHERE 1 > 2");
        verify(stmt).close();
    }

    @Test
    void columnNameFallbackUsesMatchingBracketDelimiters() throws SQLException {
        final Connection conn = bracketQuotedConnection();
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final ResultSet rows = mock(ResultSet.class);
        final ResultSetMetaData columns = mock(ResultSetMetaData.class);
        when(conn.prepareStatement(anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(columns);
        when(columns.getColumnCount()).thenReturn(1);
        when(columns.getColumnName(1)).thenReturn("ID");

        assertEquals(List.of("ID"), JdbcUtil.getColumnNames(conn, "archive.events"));

        verify(conn).prepareStatement("SELECT * FROM [ARCHIVE].[EVENTS] WHERE 1 > 2");
        verify(stmt).close();
        verify(rows).close();
    }

    private static Connection bracketQuotedConnection() throws SQLException {
        final Connection conn = mock(Connection.class);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(metadata);
        when(metadata.getIdentifierQuoteString()).thenReturn("[");
        when(metadata.storesUpperCaseIdentifiers()).thenReturn(true);
        when(metadata.supportsSchemasInTableDefinitions()).thenReturn(true);
        return conn;
    }
}
