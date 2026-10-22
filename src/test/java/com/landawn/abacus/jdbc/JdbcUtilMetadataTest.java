package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.query.SqlDialect.ProductInfo;

class JdbcUtilMetadataTest extends JdbcUtilTestSupport {

    // An unqualified name is looked up in the current schema only (case variants included). The former all-schemas rows
    // ("MIXED," / "mixed,") were removed: see unqualifiedTableOnlyInAnotherSchemaIsLeftToTheProbe.
    @ParameterizedTest(name = "table={0}, schema={1}")
    @CsvSource({ "MIXED, current", "mixed, current" })
    void tableLookupRetriesCaseInCurrentSchemaWithoutExecutingSql(final String spelling, final String schema) throws SQLException {
        when(mockConnection.getSchema()).thenReturn("current");
        when(mockDatabaseMetaData.getTables(null, schema, spelling, null)).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString("TABLE_NAME")).thenReturn("unrelated", spelling);
        when(mockResultSet.getString("TABLE_SCHEM")).thenReturn(schema);

        assertTrue(JdbcUtil.tableExists(mockConnection, "MiXeD"));

        verify(mockResultSet).close();
        verify(mockConnection, never()).prepareStatement(anyString());
    }

    @ParameterizedTest(name = "columns={0}, schema={1}")
    @CsvSource({ "MIXED, current", "mixed, current" })
    void columnLookupRetriesCaseInCurrentSchemaWithoutMixingTables(final String spelling, final String schema) throws SQLException {
        when(mockConnection.getSchema()).thenReturn("current");
        when(mockDatabaseMetaData.getColumns(null, schema, spelling, null)).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, true, true, false);
        when(mockResultSet.getString("TABLE_NAME")).thenReturn("unrelated", spelling, spelling);
        when(mockResultSet.getString("TABLE_SCHEM")).thenReturn(schema);
        when(mockResultSet.getString("TABLE_CAT")).thenReturn("first", "first", "different");
        when(mockResultSet.getString("COLUMN_NAME")).thenReturn("id");

        assertEquals(List.of("id"), JdbcUtil.getColumnNames(mockConnection, "MiXeD"));

        verify(mockResultSet).close();
        verify(mockConnection, never()).prepareStatement(anyString());
    }

    @Test
    void dropTablePreservesIdentifierMetadataFailureBeforeIssuingSql() throws SQLException {
        final SQLException failure = new SQLException("identifier metadata unavailable");
        when(mockDatabaseMetaData.storesUpperCaseIdentifiers()).thenThrow(failure);

        assertSame(failure, assertThrows(UncheckedSQLException.class, () -> JdbcUtil.dropTableIfExists(mockConnection, "records")).getCause());
        verify(mockConnection, never()).prepareStatement(anyString());
    }

    @Test
    public void testGetDBProductInfo_DataSource() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("8.0.23");

        ProductInfo info = JdbcUtil.getDBProductInfo(mockDataSource);

        assertNotNull(info);
        assertEquals("MySQL", info.name());
        assertEquals("8.0.23", info.version());
    }

    @Test
    public void testGetDBProductInfo_Connection() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("12.5");

        ProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);

        assertNotNull(info);
        assertEquals("PostgreSQL", info.name());
        assertEquals("12.5", info.version());
    }

    // getDBProductInfo edge cases
    @Test
    public void testGetDBProductInfo_MariaDBProductName() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MariaDB");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("10.5.9");

        ProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);

        assertNotNull(info);
        assertEquals("MariaDB", info.name());
        assertEquals("10.5.9", info.version());
    }

    @Test
    public void testGetDBProductInfo_NullConnectionThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.getDBProductInfo((Connection) null));
    }

    @Test
    public void testGetDBProductInfo_NullProductNameThrowsIllegalArgumentException() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn(null);
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.getDBProductInfo(mockConnection));
    }

    @Test
    public void testGetDBProductInfo_NullProductVersionIsNormalized() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("TestDB");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn(null);

        ProductInfo info = JdbcUtil.getDBProductInfo(mockConnection);

        assertNotNull(info);
        assertEquals("TestDB", info.name());
        assertEquals("", info.version());
    }

    // getDBProductInfo DataSource overload with SQLException when Spring bypassed
    @Test
    public void testGetDBProductInfo_DataSource_SQLException() throws SQLException {
        when(mockDataSource.getConnection()).thenThrow(new SQLException("connection failed"));

        JdbcUtil.runIgnoringSpringTransaction(() -> {
            assertThrows(UncheckedSQLException.class, () -> JdbcUtil.getDBProductInfo(mockDataSource));
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void productMetadataFailurePreservesCauseAndConnectionOwnership(final boolean fromDataSource) throws SQLException {
        final SQLException failure = new SQLException("product metadata unavailable");
        when(mockDatabaseMetaData.getDatabaseProductName()).thenThrow(failure);

        assertSame(failure, assertThrows(UncheckedSQLException.class, () -> {
            if (fromDataSource) {
                JdbcUtil.getDBProductInfo(mockDataSource);
            } else {
                JdbcUtil.getDBProductInfo(mockConnection);
            }
        }).getCause());
        verify(mockConnection, org.mockito.Mockito.times(fromDataSource ? 1 : 0)).close();
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

    @Test
    public void testGetColumnNames_DriverWithoutGetSchema() throws SQLException {
        final ResultSet columns = mock(ResultSet.class);
        when(mockConnection.getSchema()).thenThrow(new AbstractMethodError("JDBC 4.0 driver"));
        when(mockDatabaseMetaData.getColumns(null, null, "users", null)).thenReturn(columns);
        when(columns.next()).thenReturn(true, false);
        when(columns.getString("TABLE_NAME")).thenReturn("users");
        when(columns.getString("COLUMN_NAME")).thenReturn("id");

        assertEquals(List.of("id"), JdbcUtil.getColumnNames(mockConnection, "users"));
        verify(columns).close();
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

    @Test
    public void testGetColumnNames_DelimitedNameRequiresExactMetadataMatchAndNoUnquotedFallback() throws SQLException {
        final ResultSet columns = mock(ResultSet.class);
        when(mockConnection.getCatalog()).thenReturn(null);
        when(mockConnection.getSchema()).thenReturn(null);
        when(mockDatabaseMetaData.getColumns(null, null, "mixedCase", null)).thenReturn(columns);
        when(columns.next()).thenReturn(true, false);
        when(columns.getString("TABLE_NAME")).thenReturn("MIXEDCASE");
        when(columns.getString("COLUMN_NAME")).thenReturn("wrong_id");

        assertThrows(SQLException.class, () -> JdbcUtil.getColumnNames(mockConnection, "\"mixedCase\""));

        verify(mockDatabaseMetaData, never()).getColumns(null, null, "MIXEDCASE", null);
        verify(mockDatabaseMetaData, never()).getColumns(null, null, "mixedcase", null);
        verify(mockConnection, never()).prepareStatement("SELECT * FROM mixedCase WHERE 1 > 2");
    }

    // USER can be parsed as a value expression, so the disabled-quoting policy must skip this fallback entirely.
    @Test
    public void testGetColumnNames_FallbackSkipsCaseFoldedReservedWord() throws SQLException {
        when(mockDatabaseMetaData.storesLowerCaseIdentifiers()).thenReturn(true);
        when(mockDatabaseMetaData.getIdentifierQuoteString()).thenReturn("\"");
        // Any other fallback text reaches the default mock statement, which (like PostgreSQL's "FROM user") reports [user].
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnName(1)).thenReturn("user");
        when(mockConnection.prepareStatement("SELECT * FROM \"user\" WHERE 1 > 2")).thenThrow(new SQLException("relation \"user\" does not exist", "42P01"));
        when(mockConnection.prepareStatement("SELECT * FROM \"public\".\"user\" WHERE 1 > 2"))
                .thenThrow(new SQLException("relation \"public.user\" does not exist", "42P01"));

        assertThrows(SQLException.class, () -> JdbcUtil.getColumnNames(mockConnection, "User"));
        assertThrows(SQLException.class, () -> JdbcUtil.getColumnNames(mockConnection, "public.user"));

        verify(mockConnection, never()).prepareStatement(anyString());
    }

    // Guard (passes before and after the quoting fix): without a canonical identifier case (e.g. MySQL, SQL Server), the
    // fallback keeps the unquoted name and does not read the quote string.
    @Test
    public void testGetColumnNames_FallbackUnquotedWithoutCanonicalCase() throws SQLException {
        when(mockDatabaseMetaData.getIdentifierQuoteString()).thenReturn("`");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnName(1)).thenReturn("id");

        assertEquals(List.of("id"), JdbcUtil.getColumnNames(mockConnection, "plain_tbl"));

        verify(mockConnection).prepareStatement("SELECT * FROM plain_tbl WHERE 1 > 2");
        verify(mockDatabaseMetaData, never()).getIdentifierQuoteString();
    }

    // Preserve the caller's qualification and canonical case without adding delimiters.
    @Test
    public void testGetColumnNames_FallbackFoldsEveryPartOfThreePartName() throws SQLException {
        when(mockDatabaseMetaData.storesUpperCaseIdentifiers()).thenReturn(true);
        when(mockDatabaseMetaData.getIdentifierQuoteString()).thenReturn("\"");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnName(1)).thenReturn("ID");

        assertEquals(List.of("ID"), JdbcUtil.getColumnNames(mockConnection, "appdb.sales.orders"));

        verify(mockConnection).prepareStatement("SELECT * FROM APPDB.SALES.ORDERS WHERE 1 > 2");
        verify(mockDatabaseMetaData, never()).getIdentifierQuoteString();
    }

    // JDBC allows TABLE_CAT to be null even when Connection.getCatalog() is not (e.g. older pgjdbc).
    // A delimited name has no SELECT fallback, so rejecting such rows made the lookup fail outright.
    @Test
    public void testGetColumnNames_NullTableCatalogInMetadataStillMatchesConnectionCatalog() throws SQLException {
        final ResultSet colsRs = mock(ResultSet.class);
        when(mockConnection.getCatalog()).thenReturn("appdb");
        when(mockConnection.getSchema()).thenReturn("public");
        when(mockDatabaseMetaData.getColumns("appdb", "public", "Order Items", null)).thenReturn(colsRs);
        when(colsRs.next()).thenReturn(true, true, false);
        when(colsRs.getString("TABLE_NAME")).thenReturn("Order Items");
        when(colsRs.getString("TABLE_SCHEM")).thenReturn("public");
        when(colsRs.getString("TABLE_CAT")).thenReturn(null);
        when(colsRs.getString("COLUMN_NAME")).thenReturn("id", "qty");

        assertEquals(Arrays.asList("id", "qty"), JdbcUtil.getColumnNames(mockConnection, "\"Order Items\""));
        verify(mockConnection, never()).prepareStatement(anyString());
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

    // Like tableExists, the getColumnNames fallback probe must not abort the caller's open PostgreSQL transaction (25P02):
    // a failed probe is rolled back to a savepoint (a rollback failure is attached as suppressed) before propagating.
    @ParameterizedTest(name = "rollbackFails={0}")
    @ValueSource(booleans = { false, true })
    void columnNameProbeOnPostgreSqlRollsBackToSavepointWhenItFails(final boolean rollbackFails) throws SQLException {
        final java.sql.Savepoint savepoint = mock(java.sql.Savepoint.class);
        final SQLException notFound = new SQLException("relation \"missing_tbl\" does not exist", "42P01");
        final SQLException connectionLost = new java.sql.SQLNonTransientConnectionException("connection lost", "08006");
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockConnection.setSavepoint()).thenReturn(savepoint);
        when(mockConnection.prepareStatement("SELECT * FROM missing_tbl WHERE 1 > 2")).thenThrow(notFound);

        if (rollbackFails) {
            doThrow(connectionLost).when(mockConnection).rollback(savepoint);
        }

        assertSame(notFound, assertThrows(SQLException.class, () -> JdbcUtil.getColumnNames(mockConnection, "missing_tbl")));

        verify(mockConnection).rollback(savepoint);
        verify(mockConnection).releaseSavepoint(savepoint);
        assertArrayEquals(rollbackFails ? new Throwable[] { connectionLost } : new Throwable[0], notFound.getSuppressed());
    }

    @Test
    void columnNameProbeReleasesItsSavepointOnSuccessAndNeedsNoneOutsideAPostgreSqlTransaction() throws SQLException {
        final java.sql.Savepoint savepoint = mock(java.sql.Savepoint.class);
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockConnection.setSavepoint()).thenReturn(savepoint);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnName(1)).thenReturn("id");

        assertEquals(List.of("id"), JdbcUtil.getColumnNames(mockConnection, "orders"));
        verify(mockConnection).releaseSavepoint(savepoint);
        verify(mockConnection, never()).rollback(savepoint);

        // Auto-commit mode, or a database that rolls back only the failed statement: no savepoint.
        when(mockConnection.getAutoCommit()).thenReturn(true);
        assertEquals(List.of("id"), JdbcUtil.getColumnNames(mockConnection, "orders"));
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        assertEquals(List.of("id"), JdbcUtil.getColumnNames(mockConnection, "orders"));

        verify(mockConnection).setSavepoint();
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
    void tableExistsDataSourceReleasesConnectionAfterMetadataFailure() throws SQLException {
        final SQLException failure = new SQLException("metadata access denied", "42501");
        when(mockConnection.getMetaData()).thenThrow(failure);

        assertSame(failure, assertThrows(UncheckedSQLException.class, () -> JdbcUtil.tableExists(mockDataSource, "users")).getCause());

        verify(mockDataSource).getConnection();
        verify(mockConnection).close();
    }

    @Test
    void tableExistsDataSourceRejectsMalformedNameBeforeAcquiringConnection() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.tableExists(mockDataSource, "a..b"));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.tableExists(mockDataSource, "a.b.c.d"));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.tableExists(mockDataSource, "\"unclosed"));

        verify(mockDataSource, never()).getConnection();
    }

    @Test
    public void testDoesTableExist() throws SQLException {
        when(mockPreparedStatement.execute()).thenReturn(true);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSet.next()).thenReturn(false);

        boolean exists = JdbcUtil.tableExists(mockConnection, "users");
        assertTrue(exists);

        when(mockPreparedStatement.execute()).thenThrow(new SQLException("Table not found", "42S02"));
        boolean notExists = JdbcUtil.tableExists(mockConnection, "nonexistent");
        assertFalse(notExists);
    }

    @Test
    public void testDoesTableExist_WithQuotedDotIdentifier() throws SQLException {
        final ResultSet tableRs = mock(ResultSet.class);

        when(mockDatabaseMetaData.getTables(null, null, "sales.data", null)).thenReturn(tableRs);
        when(tableRs.next()).thenReturn(true, false);
        when(tableRs.getString("TABLE_NAME")).thenReturn("sales.data");

        assertTrue(JdbcUtil.tableExists(mockConnection, "\"sales.data\""));
    }

    @Test
    public void testTableExists_UnqualifiedNameUsesCurrentCatalog() throws SQLException {
        final ResultSet tableRs = mock(ResultSet.class);
        when(mockConnection.getCatalog()).thenReturn("current_catalog");
        when(mockDatabaseMetaData.getTables("current_catalog", null, "users", null)).thenReturn(tableRs);
        when(tableRs.next()).thenReturn(true);

        assertTrue(JdbcUtil.tableExists(mockConnection, "users"));
        verify(mockDatabaseMetaData).getTables("current_catalog", null, "users", null);
    }

    @Test
    public void testTableExists_DriverWithoutGetSchema() throws SQLException {
        final ResultSet tables = mock(ResultSet.class);
        when(mockConnection.getSchema()).thenThrow(new AbstractMethodError("JDBC 4.0 driver"));
        when(mockDatabaseMetaData.getTables(null, null, "users", null)).thenReturn(tables);
        when(tables.next()).thenReturn(true);

        assertTrue(JdbcUtil.tableExists(mockConnection, "users"));
        verify(tables).close();
    }

    // An unqualified name must not match a table that exists only in another schema (e.g. one outside the PostgreSQL search
    // path): createTableIfNotExists would then skip creating it. Metadata is consulted in the current schema only and the
    // answer is left to the probe, which follows the database's own name resolution.
    @Test
    void unqualifiedTableOnlyInAnotherSchemaIsLeftToTheProbe() throws SQLException {
        final ResultSet otherSchemaRows = mock(ResultSet.class);
        when(mockConnection.getSchema()).thenReturn("current");
        when(mockDatabaseMetaData.getTables(isNull(), isNull(), anyString(), isNull())).thenReturn(otherSchemaRows);
        when(mockDatabaseMetaData.getColumns(isNull(), isNull(), anyString(), isNull())).thenReturn(otherSchemaRows);
        when(otherSchemaRows.next()).thenReturn(true, false);
        when(otherSchemaRows.getString("TABLE_NAME")).thenReturn("orders");
        when(otherSchemaRows.getString("TABLE_SCHEM")).thenReturn("other");
        when(otherSchemaRows.getString("COLUMN_NAME")).thenReturn("other_id");
        final SQLException tableMissing = new SQLException("Table 'orders' doesn't exist", "42S02");
        when(mockConnection.prepareStatement("SELECT 1 FROM orders WHERE 1 > 2")).thenThrow(tableMissing);
        when(mockConnection.prepareStatement("SELECT * FROM orders WHERE 1 > 2")).thenThrow(tableMissing);

        assertFalse(JdbcUtil.tableExists(mockConnection, "orders"));
        assertSame(tableMissing, assertThrows(SQLException.class, () -> JdbcUtil.getColumnNames(mockConnection, "orders")));

        verify(mockDatabaseMetaData, org.mockito.Mockito.atLeastOnce()).getTables(null, "current", "orders", null);
        verify(mockDatabaseMetaData, org.mockito.Mockito.atLeastOnce()).getColumns(null, "current", "orders", null);
        verify(mockDatabaseMetaData, never()).getTables(isNull(), isNull(), anyString(), isNull());
        verify(mockDatabaseMetaData, never()).getColumns(isNull(), isNull(), anyString(), isNull());
    }

    // On PostgreSQL the search path comes from current_schemas(true): a delimited name missing from the current schema is
    // looked up in the other schemas in that order (the query runs under a savepoint inside a transaction).
    @Test
    void delimitedNameIsLookedUpAlongThePostgreSqlSearchPath() throws SQLException {
        when(mockConnection.getSchema()).thenReturn("current");
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        final java.sql.Statement searchPathStatement = mock(java.sql.Statement.class);
        final ResultSet searchPathRows = mock(ResultSet.class);
        final java.sql.Array searchPath = mock(java.sql.Array.class);
        when(mockConnection.createStatement()).thenReturn(searchPathStatement);
        when(searchPathStatement.executeQuery("SELECT current_schemas(true)")).thenReturn(searchPathRows);
        when(searchPathRows.next()).thenReturn(true, false);
        when(searchPathRows.getArray(1)).thenReturn(searchPath);
        when(searchPath.getArray()).thenReturn(new String[] { "pg_catalog", "current", "other" });
        final ResultSet otherRows = mock(ResultSet.class);
        when(mockDatabaseMetaData.getTables(null, "other", "Some Table", null)).thenReturn(otherRows);
        when(otherRows.next()).thenReturn(true, false);
        when(otherRows.getString("TABLE_NAME")).thenReturn("Some Table");
        when(otherRows.getString("TABLE_SCHEM")).thenReturn("other");

        assertTrue(JdbcUtil.tableExists(mockConnection, "\"Some Table\""));

        final org.mockito.InOrder order = org.mockito.Mockito.inOrder(mockDatabaseMetaData);
        order.verify(mockDatabaseMetaData).getTables(null, "current", "Some Table", null);
        order.verify(mockDatabaseMetaData).getTables(null, "pg_catalog", "Some Table", null);
        order.verify(mockDatabaseMetaData).getTables(null, "other", "Some Table", null);
        verify(mockConnection, never()).prepareStatement(anyString());
    }

    // The column lookup follows the same search path, in the same order (current schema, then current_schemas(true) as
    // reported, here pg_catalog before other), and never falls back to the SQL probe for a delimited name.
    @Test
    void delimitedNameColumnsAreLookedUpAlongThePostgreSqlSearchPath() throws SQLException {
        when(mockConnection.getSchema()).thenReturn("current");
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        final java.sql.Statement searchPathStatement = mock(java.sql.Statement.class);
        final ResultSet searchPathRows = mock(ResultSet.class);
        final java.sql.Array searchPath = mock(java.sql.Array.class);
        when(mockConnection.createStatement()).thenReturn(searchPathStatement);
        when(searchPathStatement.executeQuery("SELECT current_schemas(true)")).thenReturn(searchPathRows);
        when(searchPathRows.next()).thenReturn(true, false);
        when(searchPathRows.getArray(1)).thenReturn(searchPath);
        when(searchPath.getArray()).thenReturn(new String[] { "pg_catalog", "current", "other" });
        final ResultSet otherColumns = mock(ResultSet.class);
        when(mockDatabaseMetaData.getColumns(null, "other", "Some Table", null)).thenReturn(otherColumns);
        when(otherColumns.next()).thenReturn(true, true, false);
        when(otherColumns.getString("TABLE_NAME")).thenReturn("Some Table");
        when(otherColumns.getString("TABLE_SCHEM")).thenReturn("other");
        when(otherColumns.getString("COLUMN_NAME")).thenReturn("id", "name");

        assertEquals(List.of("id", "name"), JdbcUtil.getColumnNames(mockConnection, "\"Some Table\""));

        final org.mockito.InOrder order = org.mockito.Mockito.inOrder(mockDatabaseMetaData);
        order.verify(mockDatabaseMetaData).getColumns(null, "current", "Some Table", null);
        order.verify(mockDatabaseMetaData).getColumns(null, "pg_catalog", "Some Table", null);
        order.verify(mockDatabaseMetaData).getColumns(null, "other", "Some Table", null);
        verify(mockDatabaseMetaData, never()).getColumns(isNull(), isNull(), anyString(), isNull());
        verify(mockConnection, never()).prepareStatement(anyString());
        verify(otherColumns).close();
        verify(searchPath).free();
    }

    // tableExists and getColumnNames take the same probe decision for the same name: both skip the probe for a case-folded
    // keyword (its unquoted spelling can't be used in SQL) and both probe the same canonical spelling otherwise. getColumnNames
    // now gates on the same quote-aware probe name as tableExists (behaviorally equivalent while quoting is disabled; the
    // unification keeps the two decisions textually identical once quoting is enabled).
    @Test
    public void testGetColumnNames_ProbeDecisionMatchesTableExists() throws SQLException {
        when(mockDatabaseMetaData.storesLowerCaseIdentifiers()).thenReturn(true);
        when(mockDatabaseMetaData.getIdentifierQuoteString()).thenReturn("\"");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnName(1)).thenReturn("id");

        for (final String keyword : List.of("User", "public.user", "Order")) {
            assertFalse(JdbcUtil.tableExists(mockConnection, keyword), keyword);
            assertThrows(SQLException.class, () -> JdbcUtil.getColumnNames(mockConnection, keyword), keyword);
        }

        verify(mockConnection, never()).prepareStatement(anyString());

        assertTrue(JdbcUtil.tableExists(mockConnection, "Orders"));
        assertEquals(List.of("id"), JdbcUtil.getColumnNames(mockConnection, "Orders"));
        assertTrue(JdbcUtil.tableExists(mockConnection, "Public.Orders"));
        assertEquals(List.of("id"), JdbcUtil.getColumnNames(mockConnection, "Public.Orders"));

        verify(mockConnection).prepareStatement("SELECT 1 FROM orders WHERE 1 > 2");
        verify(mockConnection).prepareStatement("SELECT * FROM orders WHERE 1 > 2");
        verify(mockConnection).prepareStatement("SELECT 1 FROM public.orders WHERE 1 > 2");
        verify(mockConnection).prepareStatement("SELECT * FROM public.orders WHERE 1 > 2");
    }

    // An element of the search path array that isn't a schema name (or array contents that aren't an array) failed the lookup
    // with a ClassCastException (or NullPointerException) after the query succeeded: the transaction was rolled back to the
    // savepoint for nothing, and the search path was dropped. Such values are skipped now, and the array is freed.
    @Test
    void unexpectedSearchPathValuesAreSkipped_andTheArrayIsFreed() throws SQLException {
        final java.sql.Array searchPath = postgreSqlSearchPathInTransaction(new Object[] { "pg_catalog", 42, null, "other" });
        final ResultSet otherRows = mock(ResultSet.class);
        when(mockDatabaseMetaData.getTables(null, "other", "Some Table", null)).thenReturn(otherRows);
        when(otherRows.next()).thenReturn(true, false);
        when(otherRows.getString("TABLE_NAME")).thenReturn("Some Table");
        when(otherRows.getString("TABLE_SCHEM")).thenReturn("other");

        assertTrue(JdbcUtil.tableExists(mockConnection, "\"Some Table\""));

        verify(mockDatabaseMetaData).getTables(null, "pg_catalog", "Some Table", null);
        verify(searchPath).free();
        verify(mockConnection, never()).rollback(any(java.sql.Savepoint.class));
    }

    @Test
    void searchPathArrayWithoutContents_isAnEmptySearchPath() throws SQLException {
        final java.sql.Array searchPath = postgreSqlSearchPathInTransaction(null);

        assertFalse(JdbcUtil.tableExists(mockConnection, "\"Some Table\""));

        verify(searchPath).free();
        verify(mockConnection, never()).rollback(any(java.sql.Savepoint.class));
    }

    private java.sql.Array postgreSqlSearchPathInTransaction(final Object contents) throws SQLException {
        when(mockConnection.getSchema()).thenReturn("current");
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockConnection.setSavepoint()).thenReturn(mock(java.sql.Savepoint.class));
        final java.sql.Statement searchPathStatement = mock(java.sql.Statement.class);
        final ResultSet searchPathRows = mock(ResultSet.class);
        final java.sql.Array searchPath = mock(java.sql.Array.class);
        when(mockConnection.createStatement()).thenReturn(searchPathStatement);
        when(searchPathStatement.executeQuery("SELECT current_schemas(true)")).thenReturn(searchPathRows);
        when(searchPathRows.next()).thenReturn(true, false);
        when(searchPathRows.getArray(1)).thenReturn(searchPath);
        when(searchPath.getArray()).thenReturn(contents);
        return searchPath;
    }

    // On PostgreSQL a failed query aborts the open transaction until it is rolled back. When rolling back to the savepoint fails
    // too, the lookup reports the failure instead of an answer ("no such table") read from an unusable transaction.
    @Test
    void failedSearchPathQueryIsReportedWhenTheTransactionCannotBeRestored() throws SQLException {
        final SQLException queryFailure = new SQLException("search path unavailable");
        final SQLException rollbackFailure = new SQLException("rollback failed");
        final java.sql.Savepoint savepoint = failingPostgreSqlSearchPath(queryFailure);
        doThrow(rollbackFailure).when(mockConnection).rollback(savepoint);

        assertSame(queryFailure, assertThrows(UncheckedSQLException.class, () -> JdbcUtil.tableExists(mockConnection, "\"Some Table\"")).getCause());
        assertTrue(Arrays.asList(queryFailure.getSuppressed()).contains(rollbackFailure));
        assertSame(queryFailure, assertThrows(SQLException.class, () -> JdbcUtil.getColumnNames(mockConnection, "\"Some Table\"")));
    }

    // Restored by the savepoint, the transaction is usable: the lookup falls back to the current schema.
    @Test
    void failedSearchPathQueryFallsBackWhenTheSavepointRestoresTheTransaction() throws SQLException {
        final java.sql.Savepoint savepoint = failingPostgreSqlSearchPath(new SQLException("search path unavailable"));

        assertFalse(JdbcUtil.tableExists(mockConnection, "\"Some Table\""));
        verify(mockConnection).rollback(savepoint);
        verify(mockConnection).releaseSavepoint(savepoint);
    }

    private java.sql.Savepoint failingPostgreSqlSearchPath(final SQLException queryFailure) throws SQLException {
        when(mockConnection.getSchema()).thenReturn("current");
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockConnection.getAutoCommit()).thenReturn(false);
        final java.sql.Savepoint savepoint = mock(java.sql.Savepoint.class);
        when(mockConnection.setSavepoint()).thenReturn(savepoint);
        final java.sql.Statement searchPathStatement = mock(java.sql.Statement.class);
        when(mockConnection.createStatement()).thenReturn(searchPathStatement);
        when(searchPathStatement.executeQuery("SELECT current_schemas(true)")).thenThrow(queryFailure);
        return savepoint;
    }

    @Test
    void parseH2SchemaSearchPathReadsQuotedAndUnquotedNames() {
        assertEquals(List.of("PUBLIC", "OTHER", "My \"x\" Schema"),
                JdbcUtil.parseH2SchemaSearchPath("SET SCHEMA_SEARCH_PATH \"PUBLIC\", \"OTHER\", \"My \"\"x\"\" Schema\""));
        assertEquals(List.of("PUBLIC", "OTHER"), JdbcUtil.parseH2SchemaSearchPath("SET SCHEMA_SEARCH_PATH public, other"));
        assertEquals(List.of(), JdbcUtil.parseH2SchemaSearchPath(null));
        assertEquals(List.of(), JdbcUtil.parseH2SchemaSearchPath("SET SCHEMA PUBLIC"));
    }

    // H2 writes a name with a character outside printable ASCII in the Unicode-escaped form U&"..." (StringUtils.quoteIdentifier):
    // \XXXX or \+XXXXXX per code point, \\ for a backslash, "" for a quote. Read as a bare token, it came out as the bogus
    // upper-cased U&"..." text, so a schema with such a name was never searched.
    @Test
    void parseH2SchemaSearchPathDecodesUnicodeEscapedNames() {
        final String schema = "Sch\u00e9ma";
        assertEquals(List.of("PUBLIC", schema), JdbcUtil.parseH2SchemaSearchPath("SET SCHEMA_SEARCH_PATH \"PUBLIC\", U&\"Sch\\00e9ma\""));

        // The exact text H2 writes: a backslash, a quote, a supplementary code point and a control character.
        final String odd = "a\\b\"c \uD83D\uDE00 \u0001";
        final String quoted = org.h2.util.StringUtils.quoteIdentifier(odd);
        assertTrue(quoted.startsWith("U&\""), quoted);
        assertEquals(List.of(odd, "PUBLIC"), JdbcUtil.parseH2SchemaSearchPath("SET SCHEMA_SEARCH_PATH " + quoted + ", PUBLIC"));

        // A lower-case prefix is read too; a malformed or truncated escape is kept as written.
        assertEquals(List.of("x\u00e9", "\\zz", "\\00", "\\"),
                JdbcUtil.parseH2SchemaSearchPath("SET SCHEMA_SEARCH_PATH u&\"x\\00E9\", U&\"\\zz\", U&\"\\00\", U&\"\\\""));
        assertEquals("x\u00e9\\y", JdbcUtil.decodeH2UnicodeEscapes("x\\00e9\\\\y"));
    }

    // When the current namespace cannot be pinned down (a database with schemas whose driver reports no current schema, or a
    // catalog database with no current catalog), a null-schema/null-catalog metadata lookup would search everywhere, so it
    // is skipped: a simple name is answered by the probe, a quote-dependent name (no probe) is reported missing.
    @ParameterizedTest(name = "schemaDatabase={0}")
    @ValueSource(booleans = { true, false })
    void unknownCurrentNamespaceSkipsMetadataForUnqualifiedNames(final boolean schemaDatabase) throws SQLException {
        if (schemaDatabase) {
            when(mockDatabaseMetaData.supportsSchemasInTableDefinitions()).thenReturn(true);
            when(mockConnection.getSchema()).thenThrow(new AbstractMethodError("JDBC 4.0 driver"));
        } else {
            when(mockDatabaseMetaData.supportsCatalogsInTableDefinitions()).thenReturn(true);
            when(mockConnection.getCatalog()).thenReturn(null);
        }

        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnName(1)).thenReturn("id");

        assertTrue(JdbcUtil.tableExists(mockConnection, "orders"));
        assertEquals(List.of("id"), JdbcUtil.getColumnNames(mockConnection, "orders"));
        assertFalse(JdbcUtil.tableExists(mockConnection, "\"Order Items\""));
        assertThrows(SQLException.class, () -> JdbcUtil.getColumnNames(mockConnection, "\"Order Items\""));

        verify(mockConnection).prepareStatement("SELECT 1 FROM orders WHERE 1 > 2");
        verify(mockConnection).prepareStatement("SELECT * FROM orders WHERE 1 > 2");
        verify(mockDatabaseMetaData, never()).getTables(any(), any(), anyString(), any());
        verify(mockDatabaseMetaData, never()).getColumns(any(), any(), anyString(), any());
    }

    @Test
    public void testTableExists_CurrentCatalogDoesNotAlterUnqualifiedFallbackSql() throws SQLException {
        final ResultSet lowerCaseRs1 = mock(ResultSet.class);
        final ResultSet upperCaseRs = mock(ResultSet.class);
        final ResultSet lowerCaseRs2 = mock(ResultSet.class);
        when(mockConnection.getCatalog()).thenReturn("current_catalog");
        when(mockDatabaseMetaData.getTables("current_catalog", null, "users", null)).thenReturn(lowerCaseRs1, lowerCaseRs2);
        when(mockDatabaseMetaData.getTables("current_catalog", null, "USERS", null)).thenReturn(upperCaseRs);
        when(lowerCaseRs1.next()).thenReturn(false);
        when(upperCaseRs.next()).thenReturn(false);
        when(lowerCaseRs2.next()).thenReturn(false);

        assertTrue(JdbcUtil.tableExists(mockConnection, "users"));

        verify(mockConnection).prepareStatement("SELECT 1 FROM users WHERE 1 > 2");
        verify(mockConnection, never()).prepareStatement("SELECT 1 FROM current_catalog.users WHERE 1 > 2");
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

    @Test
    public void testTableExists_FallbackProbeInAutoCommitMode_NoSavepoint() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockConnection.getAutoCommit()).thenReturn(true);
        when(mockConnection.prepareStatement("SELECT 1 FROM missing_tbl WHERE 1 > 2"))
                .thenThrow(new SQLException("relation \"missing_tbl\" does not exist", "42P01"));

        assertFalse(JdbcUtil.tableExists(mockConnection, "missing_tbl"));

        verify(mockConnection, never()).setSavepoint();
    }

    // Regression: on PostgreSQL the unquoted probe "SELECT 1 FROM user WHERE 1 > 2" succeeds (USER is parsed as the
    // CURRENT_USER function), so a missing table named "user" was reported as present (and createTableIfNotExists skipped
    // creating it). With quoting disabled, a keyword name must not be used in a SQL probe.
    @Test
    public void testTableExists_FallbackProbeSkipsCaseFoldedReservedWord() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockDatabaseMetaData.storesLowerCaseIdentifiers()).thenReturn(true);
        when(mockDatabaseMetaData.getIdentifierQuoteString()).thenReturn("\"");
        when(mockConnection.getAutoCommit()).thenReturn(true);
        // Any other probe text reaches the default mock statement, which succeeds like PostgreSQL's "FROM user".
        when(mockConnection.prepareStatement("SELECT 1 FROM \"user\" WHERE 1 > 2")).thenThrow(new SQLException("relation \"user\" does not exist", "42P01"));
        when(mockConnection.prepareStatement("SELECT 1 FROM \"public\".\"user\" WHERE 1 > 2"))
                .thenThrow(new SQLException("relation \"public.user\" does not exist", "42P01"));

        assertFalse(JdbcUtil.tableExists(mockConnection, "User"));
        assertFalse(JdbcUtil.tableExists(mockConnection, "public.user"));

        verify(mockConnection, never()).prepareStatement(anyString());
    }

    // Guard (passes before and after the quoting fix): without a canonical identifier case (e.g. MySQL, SQL Server), the
    // probe keeps the unquoted name so the database applies its own identifier rules, and does not read the quote string.
    @Test
    public void testTableExists_FallbackProbeUnquotedWithoutCanonicalCase() throws SQLException {
        when(mockDatabaseMetaData.getIdentifierQuoteString()).thenReturn("`");
        when(mockConnection.prepareStatement("SELECT 1 FROM missing_tbl WHERE 1 > 2"))
                .thenThrow(new SQLException("Table 'missing_tbl' doesn't exist", "42S02"));

        assertFalse(JdbcUtil.tableExists(mockConnection, "missing_tbl"));

        verify(mockConnection).prepareStatement("SELECT 1 FROM missing_tbl WHERE 1 > 2");
        verify(mockDatabaseMetaData, never()).getIdentifierQuoteString();
    }

    // Keep qualification and case folding, and retain the savepoint rollback for failed ordinary-name probes.
    @Test
    public void testTableExists_FallbackProbeFoldsEveryPartOfThreePartNameUnderSavepoint() throws SQLException {
        final java.sql.Savepoint savepoint = mock(java.sql.Savepoint.class);
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockDatabaseMetaData.storesLowerCaseIdentifiers()).thenReturn(true);
        when(mockDatabaseMetaData.getIdentifierQuoteString()).thenReturn("\"");
        when(mockConnection.getCatalog()).thenReturn("appdb");
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockConnection.setSavepoint()).thenReturn(savepoint);
        // Any other probe text reaches the default mock statement, which succeeds.
        when(mockConnection.prepareStatement("SELECT 1 FROM appdb.public.orders WHERE 1 > 2"))
                .thenThrow(new SQLException("relation \"public.orders\" does not exist", "42P01"));

        assertFalse(JdbcUtil.tableExists(mockConnection, "AppDb.Public.Orders"));
        verify(mockConnection).rollback(savepoint);

        assertTrue(JdbcUtil.tableExists(mockConnection, "Orders"));
        verify(mockConnection).prepareStatement("SELECT 1 FROM orders WHERE 1 > 2");
        verify(mockConnection, never()).prepareStatement("SELECT 1 FROM appdb.orders WHERE 1 > 2");
        verify(mockDatabaseMetaData, never()).getIdentifierQuoteString();
    }

    // Guard (passes before and after the quoting fix): a driver that reports a canonical identifier case but no usable
    // identifier quote string (null, or " " meaning delimited identifiers are unsupported) keeps the unquoted case-folded
    // probe; an explicitly delimited name runs no probe and does not even read the quote string.
    @Test
    public void testTableExists_FallbackProbeUnquotedWhenQuotingUnsupported() throws SQLException {
        when(mockDatabaseMetaData.storesUpperCaseIdentifiers()).thenReturn(true);
        when(mockConnection.prepareStatement("SELECT 1 FROM MISSING_TBL WHERE 1 > 2")).thenThrow(new SQLException("Table \"MISSING_TBL\" not found", "42S02"));

        assertFalse(JdbcUtil.tableExists(mockConnection, "\"Missing\""));
        verify(mockDatabaseMetaData, never()).getIdentifierQuoteString();

        when(mockDatabaseMetaData.getIdentifierQuoteString()).thenReturn(" ");
        assertFalse(JdbcUtil.tableExists(mockConnection, "missing_tbl"));
        when(mockDatabaseMetaData.getIdentifierQuoteString()).thenReturn(null);
        assertFalse(JdbcUtil.tableExists(mockConnection, "missing_tbl"));

        verify(mockConnection, org.mockito.Mockito.times(2)).prepareStatement("SELECT 1 FROM MISSING_TBL WHERE 1 > 2");
        verify(mockConnection, never()).prepareStatement("SELECT 1 FROM Missing WHERE 1 > 2");
    }

    @Test
    public void testTableExists_DelimitedNameRequiresExactMetadataMatchAndNoUnquotedFallback() throws SQLException {
        final ResultSet tables = mock(ResultSet.class);
        when(mockConnection.getCatalog()).thenReturn(null);
        when(mockConnection.getSchema()).thenReturn(null);
        when(mockDatabaseMetaData.getTables(null, null, "mixedCase", null)).thenReturn(tables);
        when(tables.next()).thenReturn(true, false);
        when(tables.getString("TABLE_NAME")).thenReturn("MIXEDCASE");

        assertFalse(JdbcUtil.tableExists(mockConnection, "\"mixedCase\""));

        verify(mockDatabaseMetaData, never()).getTables(null, null, "MIXEDCASE", null);
        verify(mockDatabaseMetaData, never()).getTables(null, null, "mixedcase", null);
        verify(mockConnection, never()).prepareStatement("SELECT 1 FROM mixedCase WHERE 1 > 2");
    }

    @Test
    public void testTableExists_MixedQualifiedNamePreservesExactCatalogAndSchemaOnly() throws SQLException {
        final ResultSet tables = mock(ResultSet.class);
        when(mockDatabaseMetaData.getTables("mixedCatalog", "mixedSchema", "users", null)).thenReturn(tables);
        when(tables.next()).thenReturn(true, true, false);
        when(tables.getString("TABLE_SCHEM")).thenReturn("mixedSchema", "MIXEDSCHEMA");
        when(tables.getString("TABLE_CAT")).thenReturn("MIXEDCATALOG");

        assertFalse(JdbcUtil.tableExists(mockConnection, "\"mixedCatalog\".\"mixedSchema\".users"));

        verify(mockDatabaseMetaData).getTables("mixedCatalog", "mixedSchema", "USERS", null);
        verify(mockConnection, never()).prepareStatement("SELECT 1 FROM mixedCatalog.mixedSchema.users WHERE 1 > 2");
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

    // BUG FIX: JDBC allows DatabaseMetaData.getTables rows with a null TABLE_CAT (older pgjdbc reports none). A
    // delimited catalog part must only reject a row whose reported catalog differs, not one that reports no catalog.
    @Test
    public void testTableExists_DelimitedCatalog_AcceptsRowWithNullTableCat() throws SQLException {
        final ResultSet tableRs = mock(ResultSet.class);

        when(mockDatabaseMetaData.getTables("cat", "sch", "tbl", null)).thenReturn(tableRs);
        when(tableRs.next()).thenReturn(true, false);
        when(tableRs.getString("TABLE_NAME")).thenReturn("tbl");
        when(tableRs.getString("TABLE_SCHEM")).thenReturn("sch");
        when(tableRs.getString("TABLE_CAT")).thenReturn(null);

        assertTrue(JdbcUtil.tableExists(mockConnection, "\"cat\".\"sch\".\"tbl\""));
    }

    @Test
    public void testTableExists_DelimitedCatalog_RejectsRowWithDifferentTableCat() throws SQLException {
        final ResultSet tableRs = mock(ResultSet.class);

        when(mockDatabaseMetaData.getTables("cat", "sch", "tbl", null)).thenReturn(tableRs);
        when(tableRs.next()).thenReturn(true, false);
        when(tableRs.getString("TABLE_NAME")).thenReturn("tbl");
        when(tableRs.getString("TABLE_SCHEM")).thenReturn("sch");
        when(tableRs.getString("TABLE_CAT")).thenReturn("other_cat");

        assertFalse(JdbcUtil.tableExists(mockConnection, "\"cat\".\"sch\".\"tbl\""));
    }

    // Regression: a "table not found" probe failure whose savepoint rollback also failed (e.g. the connection was lost)
    // used to return false, discarding the rollback failure, so the caller never learned that its transaction may be
    // unusable. It now propagates the probe failure with the rollback failure suppressed.
    @Test
    public void testTableExists_TableNotFoundButSavepointRollbackFails_Propagates() throws SQLException {
        final java.sql.Savepoint savepoint = mock(java.sql.Savepoint.class);
        final SQLException notFound = new SQLException("relation \"missing_tbl\" does not exist", "42P01");
        final SQLException connectionLost = new java.sql.SQLNonTransientConnectionException("connection lost", "08006");
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockConnection.setSavepoint()).thenReturn(savepoint);
        when(mockConnection.prepareStatement("SELECT 1 FROM missing_tbl WHERE 1 > 2")).thenThrow(notFound);
        doThrow(connectionLost).when(mockConnection).rollback(savepoint);

        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, () -> JdbcUtil.tableExists(mockConnection, "missing_tbl"));

        assertSame(notFound, thrown.getCause());
        assertArrayEquals(new Throwable[] { connectionLost }, notFound.getSuppressed());
        verify(mockConnection).releaseSavepoint(savepoint);
    }

    // Regression: on PostgreSQL with auto-commit disabled, a probe that could set no savepoint (the driver refused one) and then
    // failed with "table not found" returned false, leaving the caller's transaction aborted (25P02) behind a clean answer. The
    // failure is now propagated, as getColumnNames and the search-path query already did. In auto-commit mode there is no
    // transaction to abort, so the answers are unchanged there.
    @Test
    public void testTableProbes_TableNotFoundWithoutASavepointInATransaction_Propagate() throws SQLException {
        final SQLException notFound = new SQLException("relation \"missing_tbl\" does not exist", "42P01");
        final SQLException noSavepoint = new java.sql.SQLFeatureNotSupportedException("savepoints are not supported", "0A000");
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockConnection.setSavepoint()).thenThrow(noSavepoint);
        when(mockConnection.prepareStatement("SELECT 1 FROM missing_tbl WHERE 1 > 2")).thenThrow(notFound);
        when(mockConnection.prepareStatement("SELECT * FROM missing_tbl WHERE 1 > 2")).thenThrow(notFound);

        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, () -> JdbcUtil.tableExists(mockConnection, "missing_tbl"));
        assertSame(notFound, thrown.getCause());
        assertSame(notFound, assertThrows(SQLException.class, () -> JdbcUtil.getColumnNames(mockConnection, "missing_tbl")));
        assertEquals(0, notFound.getSuppressed().length);
        verify(mockConnection, never()).rollback(any(java.sql.Savepoint.class));
        verify(mockConnection, never()).rollback();

        when(mockConnection.getAutoCommit()).thenReturn(true);

        assertFalse(JdbcUtil.tableExists(mockConnection, "missing_tbl"));
        assertSame(notFound, assertThrows(SQLException.class, () -> JdbcUtil.getColumnNames(mockConnection, "missing_tbl")));
        assertEquals(0, notFound.getSuppressed().length);
    }

    // isTransactionUsableAfterFailure without a savepoint: when reading the auto-commit mode itself fails (the connection was
    // lost with the probe), the transaction cannot be known to be usable, so the probe failure is propagated instead of a
    // "does not exist" answer, with the auto-commit failure attached as suppressed - the only path that attaches one without a
    // savepoint - and nothing is rolled back. Whether the auto-commit read already failed before the probe (so no savepoint
    // was attempted) or only after it (the savepoint was refused) makes no difference.
    @ParameterizedTest(name = "autoCommitReadFailsBeforeTheProbe={0}")
    @ValueSource(booleans = { false, true })
    public void testTableExists_AutoCommitReadFailsAfterAProbeWithoutASavepoint_PropagatesWithTheFailureSuppressed(
            final boolean autoCommitReadFailsBeforeTheProbe) throws SQLException {
        final SQLException notFound = new SQLException("relation \"missing_tbl\" does not exist", "42P01");
        final SQLException connectionLost = new SQLException("connection lost", "08006");
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");

        if (autoCommitReadFailsBeforeTheProbe) {
            when(mockConnection.getAutoCommit()).thenThrow(connectionLost);
        } else {
            when(mockConnection.getAutoCommit()).thenReturn(false).thenThrow(connectionLost);
            when(mockConnection.setSavepoint()).thenThrow(new java.sql.SQLFeatureNotSupportedException("savepoints are not supported", "0A000"));
        }

        when(mockConnection.prepareStatement("SELECT 1 FROM missing_tbl WHERE 1 > 2")).thenThrow(notFound);

        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, () -> JdbcUtil.tableExists(mockConnection, "missing_tbl"));

        assertSame(notFound, thrown.getCause());
        assertArrayEquals(new Throwable[] { connectionLost }, notFound.getSuppressed());
        verify(mockConnection, times(autoCommitReadFailsBeforeTheProbe ? 0 : 1)).setSavepoint();
        verify(mockConnection, never()).rollback(any(java.sql.Savepoint.class));
        verify(mockConnection, never()).rollback();
        verify(mockConnection, never()).releaseSavepoint(any());
    }

    // splitQualifiedSqlIdentifier: 1/2/3 unquoted segments.
    @Test
    public void testSplitQualifiedSqlIdentifier_PlainSegments() {
        assertArrayEquals(new String[] { "col" }, JdbcUtil.splitQualifiedSqlIdentifier("col", "name"));
        assertArrayEquals(new String[] { "tbl", "col" }, JdbcUtil.splitQualifiedSqlIdentifier("tbl.col", "name"));
        assertArrayEquals(new String[] { "sch", "tbl", "col" }, JdbcUtil.splitQualifiedSqlIdentifier("sch.tbl.col", "name"));
    }

    // splitQualifiedSqlIdentifier: quoted / bracketed / back-ticked segments protect embedded dots.
    @Test
    public void testSplitQualifiedSqlIdentifier_QuotedSegments() {
        // double-quoted segment with an embedded dot stays a single (delimiter-stripped) part
        final String[] dq = JdbcUtil.splitQualifiedSqlIdentifier("\"a.b\".c", "name");
        assertEquals(2, dq.length);
        assertEquals("a.b", dq[0]);
        assertEquals("c", dq[1]);

        // bracketed identifier
        final String[] br = JdbcUtil.splitQualifiedSqlIdentifier("[tab].col", "name");
        assertEquals(2, br.length);
        assertEquals("tab", br[0]);

        // back-ticked identifier
        final String[] bt = JdbcUtil.splitQualifiedSqlIdentifier("`tab`.col", "name");
        assertEquals(2, bt.length);
        assertEquals("tab", bt[0]);

        // a doubled quote inside a quoted identifier is an escaped quote -> still one segment
        final String[] esc = JdbcUtil.splitQualifiedSqlIdentifier("\"a\"\"b\"", "name");
        assertEquals(1, esc.length);
    }

    @Test
    public void testSplitQualifiedSqlIdentifierRejectsTextAfterClosingDelimiter() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.splitQualifiedSqlIdentifier("\"users\"suffix", "tableName"));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.splitQualifiedSqlIdentifier("[users]suffix", "tableName"));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.splitQualifiedSqlIdentifier("`users`suffix", "tableName"));

        assertArrayEquals(new String[] { "schema.with.dot", "users" }, JdbcUtil.splitQualifiedSqlIdentifier("\"schema.with.dot\" . [users]", "tableName"));
    }

    // splitQualifiedSqlIdentifier: malformed inputs throw IllegalArgumentException.
    @Test
    public void testSplitQualifiedSqlIdentifier_Invalid() {
        // unclosed quote
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.splitQualifiedSqlIdentifier("\"abc", "name"));
        // more than 3 segments
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.splitQualifiedSqlIdentifier("a.b.c.d", "name"));
        // empty segment between dots
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.splitQualifiedSqlIdentifier("a..b", "name"));
        // blank input
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.splitQualifiedSqlIdentifier("   ", "name"));
    }

    @Test
    public void testToQualifiedSqlIdentifier_RejectsEmbeddedQuotes() throws SQLException {
        when(mockDatabaseMetaData.getIdentifierQuoteString()).thenReturn("\"");

        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.toQualifiedSqlIdentifier(mockConnection, "schema.my\"table", "tableName"));
    }

    @Test
    public void testToQualifiedSqlIdentifier_RejectsQuotedDotsWithinIdentifier() throws SQLException {
        when(mockDatabaseMetaData.getIdentifierQuoteString()).thenReturn("\"");

        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.toQualifiedSqlIdentifier(mockConnection, "schema.\"sales.data\"", "tableName"));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.toQualifiedSqlIdentifier(mockConnection, "\"sales.data\"", "tableName"));
    }

    @Test
    public void testToQualifiedSqlIdentifier_RejectsUnsafeWhenNoQuoteSupport() throws SQLException {
        when(mockDatabaseMetaData.getIdentifierQuoteString()).thenReturn(" ");

        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.toQualifiedSqlIdentifier(mockConnection, "users;drop", "tableName"));
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
    public void testCreateTableIfNotExists_RecheckFailureDoesNotMaskCreateFailure() throws SQLException {
        final SQLException tableNotFound = new SQLException("Table not found", "42S02");
        final SQLException createFailure = new SQLException("syntax error at end of input", "42601");
        final SQLException transactionAborted = new SQLException("current transaction is aborted, commands ignored", "25P02");
        // 1) existence probe: table missing; 2) CREATE fails; 3) the concurrent-creation re-check fails
        //    because the failed CREATE left the connection unusable.
        when(mockPreparedStatement.execute()).thenThrow(tableNotFound, createFailure, transactionAborted);

        final UncheckedSQLException ex = assertThrows(UncheckedSQLException.class,
                () -> JdbcUtil.createTableIfNotExists(mockConnection, "users", "CREATE TABLE users (id INT"));

        assertSame(createFailure, ex.getCause(), "the CREATE failure must be reported, not the re-check failure");
        assertEquals(1, createFailure.getSuppressed().length);
        assertSame(transactionAborted, createFailure.getSuppressed()[0].getCause());
    }

    @Test
    void createTableIfNotExistsRejectsBlankSchemaBeforeCheckingExistence() throws SQLException {
        // The default mock probe succeeds, so without up-front validation the table is reported as existing.
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.createTableIfNotExists(mockConnection, "users", "   "));

        verify(mockConnection, never()).getMetaData();
        verify(mockConnection, never()).prepareStatement(anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void failedCreateRechecksWhetherAnotherConnectionCreatedTheTable(final boolean createdConcurrently) throws SQLException {
        final SQLException missing = new SQLException("table missing", "42S02");
        final SQLException createFailure = new SQLException("create rejected", "42S01");
        final String schema = "CREATE TABLE users (id INT)";
        when(mockPreparedStatement.execute()).thenThrow(missing, createFailure).thenAnswer(invocation -> {
            if (!createdConcurrently) {
                throw missing;
            }
            return false;
        });

        if (createdConcurrently) {
            assertFalse(JdbcUtil.createTableIfNotExists(mockConnection, "users", schema));
        } else {
            assertSame(createFailure,
                    assertThrows(UncheckedSQLException.class, () -> JdbcUtil.createTableIfNotExists(mockConnection, "users", schema)).getCause());
        }
        verify(mockConnection).prepareStatement(schema);
        verify(mockPreparedStatement, org.mockito.Mockito.times(3)).close();
        verify(mockConnection, never()).close();
    }

    @Test
    void droppingMissingTableDoesNotIssueDropStatement() throws SQLException {
        when(mockPreparedStatement.execute()).thenThrow(new SQLException("table missing", "42S02"));

        assertFalse(JdbcUtil.dropTableIfExists(mockConnection, "users"));

        verify(mockConnection, never()).prepareStatement("DROP TABLE users");
        verify(mockPreparedStatement).close();
        verify(mockConnection, never()).close();
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void failedDropDistinguishesConcurrentDeletionFromPermissionFailure(final boolean droppedConcurrently) throws SQLException {
        final SQLException failure = new SQLException("drop failed", droppedConcurrently ? "42S02" : "42000", droppedConcurrently ? 1146 : 1142);
        when(mockPreparedStatement.execute()).thenReturn(false).thenThrow(failure);

        if (droppedConcurrently) {
            assertFalse(JdbcUtil.dropTableIfExists(mockConnection, "users"));
        } else {
            assertSame(failure, assertThrows(UncheckedSQLException.class, () -> JdbcUtil.dropTableIfExists(mockConnection, "users")).getCause());
        }
        verify(mockConnection).prepareStatement("DROP TABLE users");
        verify(mockPreparedStatement, org.mockito.Mockito.times(2)).close();
        verify(mockConnection, never()).close();
    }

    @Test
    void unknownDatabaseUsesSavepointAndIgnoresSavepointReleaseFailure() throws SQLException {
        final java.sql.Savepoint savepoint = mock(java.sql.Savepoint.class);
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockConnection.setSavepoint()).thenReturn(savepoint);
        when(mockDatabaseMetaData.getDatabaseProductName()).thenThrow(new SQLException("product unavailable"));
        when(mockPreparedStatement.execute()).thenThrow(new SQLException("table missing", "42S02"));
        doThrow(new IllegalStateException("savepoint release failed")).when(mockConnection).releaseSavepoint(savepoint);

        assertFalse(JdbcUtil.tableExists(mockConnection, "users"));

        verify(mockConnection).rollback(savepoint);
        verify(mockConnection).releaseSavepoint(savepoint);
        verify(mockConnection, never()).commit();
        verify(mockConnection, never()).close();
    }

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
    public void testDropTableIfExists_DelimitedNameIsNeverRetriedUnquoted() throws SQLException {
        final ResultSet tableRows = mock(ResultSet.class);
        when(mockDatabaseMetaData.getIdentifierQuoteString()).thenReturn("\"");
        when(mockDatabaseMetaData.getTables(null, null, "mixedCase", null)).thenReturn(tableRows);
        when(tableRows.next()).thenReturn(true, false);
        when(tableRows.getString("TABLE_NAME")).thenReturn("mixedCase");
        when(mockPreparedStatement.execute()).thenThrow(new SQLException("table not found", "42S02"));

        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.dropTableIfExists(mockConnection, "\"mixedCase\""));

        verify(mockConnection, never()).prepareStatement(anyString());
    }

    @Test
    public void testDropTableIfExists_RejectsReservedIdentifier() throws SQLException {
        final ResultSet tableRs = mock(ResultSet.class);

        when(mockDatabaseMetaData.getIdentifierQuoteString()).thenReturn("\"");
        when(mockDatabaseMetaData.getTables(null, null, "order", null)).thenReturn(tableRs);
        when(tableRs.next()).thenReturn(true);
        when(mockPreparedStatement.execute()).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.dropTableIfExists(mockConnection, "order"));
        verify(mockConnection, never()).prepareStatement(anyString());
    }

    @Test
    public void testCheckColumnNameParsesEveryInputAsSingleIdentifier() {
        assertEquals("users", SqlIdentifierUtil.renderColumnName("  users  ", null));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderColumnName("\"user.name\"", null));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderColumnName("users.name", null));
    }

    // Regression: stripIdentifierDelimiters did not unescape doubled quotes inside a quoted body,
    // so toQualifiedSqlIdentifier (which re-quotes) would double-escape and emit corrupt SQL.
    @Test
    public void testStripIdentifierDelimiters_UnescapesDoubledQuote() {
        // The logic now lives in the shared package-private SqlIdentifierUtil.
        assertEquals("a\"b", com.landawn.abacus.jdbc.SqlIdentifierUtil.stripIdentifierDelimiters("\"a\"\"b\""),
                "doubled \" inside double-quoted identifier must unescape");
        assertEquals("a`b", com.landawn.abacus.jdbc.SqlIdentifierUtil.stripIdentifierDelimiters("`a``b`"),
                "doubled ` inside backtick identifier must unescape");
        assertEquals("a]b", com.landawn.abacus.jdbc.SqlIdentifierUtil.stripIdentifierDelimiters("[a]]b]"),
                "doubled ] inside bracketed identifier must unescape");
        // Single-quoted bodies without doubled quotes are returned as the literal body.
        assertEquals("plain", com.landawn.abacus.jdbc.SqlIdentifierUtil.stripIdentifierDelimiters("\"plain\""));
    }

    @Nested
    class DriverDelimiterIndependence {
        @Test
        void qualifiedIdentifiersStayUnquotedWhenTheDriverUsesBrackets() throws SQLException {
            final Connection conn = bracketQuotedConnection();

            assertEquals("CATALOG.ARCHIVE.EVENTS", JdbcUtil.toQualifiedSqlIdentifier(conn, "catalog.archive.events", "tableName"));
            assertThrows(IllegalArgumentException.class, () -> JdbcUtil.toQualifiedSqlIdentifier(conn, "\"MixedCase\".\"a]b\"", "tableName"));
            assertThrows(IllegalArgumentException.class, () -> JdbcUtil.toQualifiedSqlIdentifier(conn, "\"a[b\"", "tableName"));
            verify(conn.getMetaData(), never()).getIdentifierQuoteString();
        }

        @Test
        void tableExistenceFallbackStaysUnquotedWhenTheDriverUsesBrackets() throws SQLException {
            final Connection conn = bracketQuotedConnection();
            final PreparedStatement stmt = mock(PreparedStatement.class);
            when(conn.prepareStatement(anyString())).thenReturn(stmt);

            assertTrue(JdbcUtil.tableExists(conn, "archive.events"));

            verify(conn).prepareStatement("SELECT 1 FROM ARCHIVE.EVENTS WHERE 1 > 2");
            verify(conn.getMetaData(), never()).getIdentifierQuoteString();
            verify(stmt).close();
        }

        @Test
        void columnNameFallbackStaysUnquotedWhenTheDriverUsesBrackets() throws SQLException {
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

            verify(conn).prepareStatement("SELECT * FROM ARCHIVE.EVENTS WHERE 1 > 2");
            verify(conn.getMetaData(), never()).getIdentifierQuoteString();
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
}
