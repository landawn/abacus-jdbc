package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("2025")
class JdbcUtilUnquotedIdentifierTest {

    @Test
    void qualifiedNamesRejectExplicitDelimitersAndNamesThatNeedQuoting() throws SQLException {
        final Connection conn = upperCaseConnection();

        for (final String name : List.of("\"events\"", "[events]", "`events`", "archive.\"events\"", "\"MixedCase\"", "\"a.b\"", "event name", "event$name",
                "1event", "user", "archive.order", "current_user")) {
            assertThrows(IllegalArgumentException.class, () -> JdbcUtil.toQualifiedSqlIdentifier(conn, name, "tableName"), name);
        }

        verify(conn.getMetaData(), never()).getIdentifierQuoteString();
        verify(conn, never()).prepareStatement(anyString());
    }

    @Test
    void missingQuoteDependentTablesNeverRunAnUnquotedExistenceProbe() throws SQLException {
        for (final String name : quoteDependentNames()) {
            final Connection conn = upperCaseConnection();

            assertFalse(JdbcUtil.tableExists(conn, name), name);

            verify(conn, never()).prepareStatement(anyString());
            verify(conn.getMetaData(), never()).getIdentifierQuoteString();
        }
    }

    @Test
    void missingQuoteDependentTablesNeverRunAnUnquotedColumnProbe() throws SQLException {
        for (final String name : quoteDependentNames()) {
            final Connection conn = upperCaseConnection();

            assertThrows(SQLException.class, () -> JdbcUtil.getColumnNames(conn, name), name);

            verify(conn, never()).prepareStatement(anyString());
            verify(conn.getMetaData(), never()).getIdentifierQuoteString();
        }
    }

    @Test
    void quoteDependentNamesStillResolveThroughExactJdbcMetadata() throws SQLException {
        for (final String[] names : new String[][] { { "\"MixedCase\"", "MixedCase" }, { "[MixedCase]", "MixedCase" }, { "`MixedCase`", "MixedCase" },
                { "\"a.b\"", "a.b" }, { "\"event name\"", "event name" }, { "user", "USER" }, { "\"USER\"", "USER" } }) {
            final Connection conn = upperCaseConnection();
            final DatabaseMetaData metadata = conn.getMetaData();
            final ResultSet tables = metadataRows(names[1]);
            final ResultSet columns = metadataRows(names[1]);
            when(metadata.getTables(null, null, names[1], null)).thenReturn(tables);
            when(metadata.getColumns(null, null, names[1], null)).thenReturn(columns);
            when(columns.getString("COLUMN_NAME")).thenReturn("ID");

            assertTrue(JdbcUtil.tableExists(conn, names[0]), names[0]);
            assertEquals(List.of("ID"), JdbcUtil.getColumnNames(conn, names[0]), names[0]);

            verify(tables).close();
            verify(columns).close();
            verify(conn, never()).prepareStatement(anyString());
            verify(metadata, never()).getIdentifierQuoteString();
        }
    }

    @Test
    void explicitNamesDoNotMatchAnUnquotedCaseVariantInMetadata() throws SQLException {
        final Connection conn = upperCaseConnection();
        final DatabaseMetaData metadata = conn.getMetaData();
        final ResultSet tables = metadataRows("MIXEDCASE");
        final ResultSet columns = metadataRows("MIXEDCASE");
        when(metadata.getTables(null, null, "MixedCase", null)).thenReturn(tables);
        when(metadata.getColumns(null, null, "MixedCase", null)).thenReturn(columns);
        when(columns.getString("COLUMN_NAME")).thenReturn("WRONG_ID");

        assertFalse(JdbcUtil.tableExists(conn, "\"MixedCase\""));
        assertThrows(SQLException.class, () -> JdbcUtil.getColumnNames(conn, "\"MixedCase\""));

        verify(conn, never()).prepareStatement(anyString());
        verify(metadata, never()).getIdentifierQuoteString();
    }

    @Test
    void ordinaryDropWorksButQuotedAndReservedTablesRemainUntouched() throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:unquoted_identifiers_" + UUID.randomUUID());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE items (id INT)");
            stmt.execute("CREATE TABLE \"MixedCase\" (id INT)");
            stmt.execute("CREATE TABLE \"USER\" (id INT)");

            assertEquals(List.of("ID"), JdbcUtil.getColumnNames(conn, "\"MixedCase\""));
            assertEquals(List.of("ID"), JdbcUtil.getColumnNames(conn, "user"));
            assertThrows(IllegalArgumentException.class, () -> JdbcUtil.dropTableIfExists(conn, "\"MixedCase\""));
            assertThrows(IllegalArgumentException.class, () -> JdbcUtil.dropTableIfExists(conn, "user"));
            assertTrue(JdbcUtil.tableExists(conn, "\"MixedCase\""));
            assertTrue(JdbcUtil.tableExists(conn, "user"));

            assertTrue(JdbcUtil.dropTableIfExists(conn, "items"));
            assertFalse(JdbcUtil.tableExists(conn, "items"));
        }
    }

    private static List<String> quoteDependentNames() {
        return List.of("user", "order", "value", "year", "current_user", "rownum", "current_path", "public.user", "public.rownum", "public.current_path",
                "\"MixedCase\"", "\"archive\".events", "\"a.b\"", "event name", "event$name", "1event");
    }

    private static Connection upperCaseConnection() throws SQLException {
        final Connection conn = mock(Connection.class);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(metadata);
        when(metadata.storesUpperCaseIdentifiers()).thenReturn(true);
        when(metadata.supportsSchemasInTableDefinitions()).thenReturn(true);
        when(metadata.getIdentifierQuoteString()).thenThrow(new SQLException("Identifier quote metadata must not be read when quoting is disabled"));
        return conn;
    }

    private static ResultSet metadataRows(final String tableName) throws SQLException {
        final ResultSet rows = mock(ResultSet.class);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString("TABLE_NAME")).thenReturn(tableName);
        return rows;
    }
}
