package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

import com.landawn.abacus.TestBase;

@Tag("2025")
public class JdbcUtilMetadataReviewTest extends TestBase {

    @Test
    void columnNamesUseTheQualifiedCatalogOnCatalogOnlyDatabases() throws SQLException {
        for (final String table : List.of("archive.events", "`archive`.`events`")) {
            final Connection conn = catalogOnlyConnection();
            final DatabaseMetaData metadata = conn.getMetaData();
            final ResultSet columns = mock(ResultSet.class);
            when(metadata.getColumns("archive", null, "events", null)).thenReturn(columns);
            when(columns.next()).thenReturn(true, false);
            when(columns.getString("TABLE_CAT")).thenReturn("archive");
            when(columns.getString("TABLE_NAME")).thenReturn("events");
            when(columns.getString("COLUMN_NAME")).thenReturn("archived_id");

            assertEquals(List.of("archived_id"), JdbcUtil.getColumnNames(conn, table));

            verify(metadata).getColumns("archive", null, "events", null);
            verify(columns).close();
            verify(conn, never()).getSchema();
            verify(conn, never()).prepareStatement(anyString());
        }
    }

    @Test
    void tableExistsUsesTheQualifiedCatalogOnCatalogOnlyDatabases() throws SQLException {
        for (final String table : List.of("archive.events", "`archive`.`events`")) {
            final Connection conn = catalogOnlyConnection();
            final DatabaseMetaData metadata = conn.getMetaData();
            final ResultSet tables = mock(ResultSet.class);
            when(metadata.getTables("archive", null, "events", null)).thenReturn(tables);
            when(tables.next()).thenReturn(true, false);
            when(tables.getString("TABLE_CAT")).thenReturn("archive");
            when(tables.getString("TABLE_NAME")).thenReturn("events");

            assertTrue(JdbcUtil.tableExists(conn, table));

            verify(metadata).getTables("archive", null, "events", null);
            verify(tables).close();
            verify(conn, never()).getSchema();
            verify(conn, never()).prepareStatement(anyString());
        }
    }

    @Test
    void tableInCurrentCatalogDoesNotMakeMissingQualifiedTableExist() throws SQLException {
        final Connection conn = catalogOnlyConnection();
        final ResultSet currentCatalogTables = mock(ResultSet.class);
        when(currentCatalogTables.next()).thenReturn(true);
        when(conn.getMetaData().getTables(eq("live"), any(), eq("events"), isNull())).thenReturn(currentCatalogTables);
        when(conn.prepareStatement("SELECT 1 FROM archive.events WHERE 1 > 2")).thenThrow(new SQLException("missing table", "42S02"));

        assertFalse(JdbcUtil.tableExists(conn, "archive.events"));

        verify(conn.getMetaData(), never()).getTables(eq("live"), any(), anyString(), isNull());
    }

    @Test
    void columnNameFallbackRetainsCatalogQualificationAndIdentifierFolding() throws SQLException {
        final Connection conn = catalogOnlyConnection();
        final DatabaseMetaData metadata = conn.getMetaData();
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final ResultSet rows = mock(ResultSet.class);
        final ResultSetMetaData columns = mock(ResultSetMetaData.class);
        when(metadata.storesLowerCaseIdentifiers()).thenReturn(true);
        when(metadata.getIdentifierQuoteString()).thenReturn("`");
        when(conn.prepareStatement("SELECT * FROM `archive`.`events` WHERE 1 > 2")).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(columns);
        when(columns.getColumnCount()).thenReturn(1);
        when(columns.getColumnName(1)).thenReturn("archived_id");

        assertEquals(List.of("archived_id"), JdbcUtil.getColumnNames(conn, "Archive.Events"));

        verify(conn).prepareStatement("SELECT * FROM `archive`.`events` WHERE 1 > 2");
        verify(stmt).close();
    }

    @Test
    void tableExistenceFallbackRetainsCatalogQualificationAndIdentifierFolding() throws SQLException {
        final Connection conn = catalogOnlyConnection();
        final DatabaseMetaData metadata = conn.getMetaData();
        final PreparedStatement stmt = mock(PreparedStatement.class);
        when(metadata.storesLowerCaseIdentifiers()).thenReturn(true);
        when(metadata.getIdentifierQuoteString()).thenReturn("`");
        when(conn.prepareStatement("SELECT 1 FROM `archive`.`events` WHERE 1 > 2")).thenReturn(stmt);

        assertTrue(JdbcUtil.tableExists(conn, "Archive.Events"));

        verify(conn).prepareStatement("SELECT 1 FROM `archive`.`events` WHERE 1 > 2");
        verify(stmt).close();
    }

    @Test
    void databasesSupportingSchemasKeepSchemaQualification() throws SQLException {
        final Connection conn = catalogOnlyConnection();
        final DatabaseMetaData metadata = conn.getMetaData();
        final ResultSet columns = mock(ResultSet.class);
        final ResultSet tables = mock(ResultSet.class);
        when(metadata.supportsSchemasInTableDefinitions()).thenReturn(true);
        when(metadata.getColumns("live", "archive", "events", null)).thenReturn(columns);
        when(columns.next()).thenReturn(true, false);
        when(columns.getString("TABLE_CAT")).thenReturn("live");
        when(columns.getString("TABLE_SCHEM")).thenReturn("archive");
        when(columns.getString("TABLE_NAME")).thenReturn("events");
        when(columns.getString("COLUMN_NAME")).thenReturn("archived_id");
        when(metadata.getTables("live", "archive", "events", null)).thenReturn(tables);
        when(tables.next()).thenReturn(true, false);

        assertEquals(List.of("archived_id"), JdbcUtil.getColumnNames(conn, "archive.events"));
        assertTrue(JdbcUtil.tableExists(conn, "archive.events"));
    }

    private static Connection catalogOnlyConnection() throws SQLException {
        final Connection conn = mock(Connection.class);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(metadata);
        when(conn.getCatalog()).thenReturn("live");
        when(metadata.supportsCatalogsInTableDefinitions()).thenReturn(true);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        return conn;
    }
}
