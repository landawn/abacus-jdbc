package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.query.SqlDialect.ProductInfo;
import com.landawn.abacus.TestBase;

@Tag("integration")
class JdbcUtilIdentifierMetadataIntegrationTest extends TestBase {

    @Nested
    class QuoteMetadata {
        private static Connection dialect(Connection real, String product, String quote, List<String> statements) throws Exception {
            Connection conn = mock(Connection.class, delegatesTo(real));
            DatabaseMetaData metadata = mock(DatabaseMetaData.class, delegatesTo(real.getMetaData()));
            doReturn(metadata).when(conn).getMetaData();
            doReturn(product).when(metadata).getDatabaseProductName();
            doReturn(quote).when(metadata).getIdentifierQuoteString();
            doAnswer(call -> {
                String sql = call.getArgument(0);
                statements.add(sql);
                return real.prepareStatement(sql);
            }).when(conn).prepareStatement(anyString());
            doAnswer(call -> {
                String sql = call.getArgument(0);
                statements.add(sql);
                return real.prepareStatement(sql, call.getArgument(1), call.getArgument(2));
            }).when(conn).prepareStatement(anyString(), anyInt(), anyInt());
            return conn;
        }

        @Test
        void codeGenerationSkipsProductAndQuoteMetadataForEveryOverload() throws Exception {
            try (Connection real = DriverManager.getConnection("jdbc:h2:mem:codegen_without_quote_metadata");
                 Statement stmt = real.createStatement()) {
                stmt.execute("create table t(id int, note varchar, extra_note varchar)");
                Connection conn = dialect(real, "H2", "\"", new ArrayList<>());
                DatabaseMetaData metadata = conn.getMetaData();
                doThrow(new AssertionError("Product metadata is unnecessary for unquoted SQL generation")).when(metadata).getDatabaseProductName();
                doThrow(new AssertionError("Product metadata is unnecessary for unquoted SQL generation")).when(metadata).getDatabaseProductVersion();
                doThrow(new AssertionError("Quote metadata must not be read when quoting is disabled")).when(metadata).getIdentifierQuoteString();

                assertAll(() -> assertEquals("SELECT ID, NOTE, EXTRA_NOTE FROM t", JdbcCodeGenerationUtil.generateSelectSql(conn, "t")),
                        () -> assertEquals("SELECT ID, NOTE FROM t WHERE ID > 0",
                                JdbcCodeGenerationUtil.generateSelectSql(conn, "t", List.of("extra_note"), "ID > 0")),
                        () -> assertEquals("INSERT INTO t(ID, NOTE, EXTRA_NOTE) VALUES (?, ?, ?)", JdbcCodeGenerationUtil.generateInsertSql(conn, "t")),
                        () -> assertEquals("INSERT INTO t(ID, NOTE) VALUES (?, ?)", JdbcCodeGenerationUtil.generateInsertSql(conn, "t", List.of("extra_note"))),
                        () -> assertEquals("INSERT INTO t(ID, NOTE, EXTRA_NOTE) VALUES (:id, :note, :extraNote)",
                                JdbcCodeGenerationUtil.generateNamedInsertSql(conn, "t")),
                        () -> assertEquals("INSERT INTO t(ID, NOTE) VALUES (:id, :note)",
                                JdbcCodeGenerationUtil.generateNamedInsertSql(conn, "t", List.of("extra_note"))),
                        () -> assertEquals("UPDATE t SET ID = ?, NOTE = ?, EXTRA_NOTE = ?", JdbcCodeGenerationUtil.generateUpdateSql(conn, "t")),
                        () -> assertEquals("UPDATE t SET NOTE = ?, EXTRA_NOTE = ? WHERE ID = ?", JdbcCodeGenerationUtil.generateUpdateSql(conn, "t", "id")),
                        () -> assertEquals("UPDATE t SET NOTE = ? WHERE ID = ?",
                                JdbcCodeGenerationUtil.generateUpdateSql(conn, "t", List.of("extra_note"), List.of("id"), null)),
                        () -> assertEquals("UPDATE t SET ID = :id, NOTE = :note, EXTRA_NOTE = :extraNote",
                                JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "t")),
                        () -> assertEquals("UPDATE t SET NOTE = :note, EXTRA_NOTE = :extraNote WHERE ID = :id",
                                JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "t", "id")),
                        () -> assertEquals("UPDATE t SET NOTE = :note WHERE ID = :id",
                                JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "t", List.of("extra_note"), List.of("id"), null)));

                verify(metadata, never()).getDatabaseProductName();
                verify(metadata, never()).getDatabaseProductVersion();
                verify(metadata, never()).getIdentifierQuoteString();
            }
        }

        @Test
        void codeGenerationLeavesEveryStatementFamilyUnquotedDespiteDriverQuotes() throws Exception {
            for (String product : List.of("Spark SQL", "Databricks", "Apache Hive", "Google BigQuery", "OtherDB")) {
                try (Connection real = DriverManager.getConnection("jdbc:h2:mem:metadata_quotes");
                     Statement stmt = real.createStatement()) {
                    stmt.execute("create table t(id int, note varchar, total_value int)");
                    Connection conn = dialect(real, product, "`", new ArrayList<>());
                    assertNull(SqlIdentifierUtil.quoteString(conn, ProductInfo.of(product, "1")));
                    assertEquals("SELECT ID, NOTE, TOTAL_VALUE FROM t", JdbcCodeGenerationUtil.generateSelectSql(conn, "t"));
                    assertEquals("SELECT NOTE, TOTAL_VALUE FROM t", JdbcCodeGenerationUtil.generateSelectSql(conn, "t", List.of("id"), null));
                    assertEquals("INSERT INTO t(ID, NOTE, TOTAL_VALUE) VALUES (?, ?, ?)", JdbcCodeGenerationUtil.generateInsertSql(conn, "t"));
                    assertEquals("INSERT INTO t(ID, NOTE, TOTAL_VALUE) VALUES (:id, :note, :totalValue)",
                            JdbcCodeGenerationUtil.generateNamedInsertSql(conn, "t"));
                    assertEquals("UPDATE t SET NOTE = ?, TOTAL_VALUE = ? WHERE ID = ?", JdbcCodeGenerationUtil.generateUpdateSql(conn, "t", "id"));
                    assertEquals("UPDATE t SET NOTE = :note, TOTAL_VALUE = :totalValue WHERE ID = :id",
                            JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "t", "id"));
                }
            }
        }

        @Test
        void copyLeavesIdentifiersUnquotedAndPreservesTargetCaseFoldingAndColumnOrder() throws Exception {
            try (Connection source = DriverManager.getConnection("jdbc:h2:mem:quote_copy_source");
                 Connection target = DriverManager.getConnection("jdbc:h2:mem:quote_copy_target;DATABASE_TO_LOWER=TRUE");
                 Statement src = source.createStatement();
                 Statement dst = target.createStatement()) {
                src.execute("create table t(id int, note varchar, extra_note varchar)");
                src.execute("insert into t values(1, 'payload', 'additional value')");
                dst.execute("create table t(extra_note varchar, id int, note varchar)");
                List<String> sourceSql = new ArrayList<>();
                List<String> targetSql = new ArrayList<>();
                Connection sourceDialect = dialect(source, "Spark SQL", "`", sourceSql);
                Connection targetDialect = dialect(target, "OtherDB", "\"", targetSql);
                assertEquals(1, DataTransferUtil.copy(sourceDialect, targetDialect, "t", "t"));
                assertOnlySourceFetchConfigurationReadsProductMetadata(sourceDialect, targetDialect);
                assertTrue(sourceSql.stream().anyMatch(sql -> sql.startsWith("SELECT ID, NOTE, EXTRA_NOTE")), sourceSql.toString());
                assertTrue(targetSql.stream().anyMatch(sql -> sql.startsWith("INSERT INTO t(id, note, extra_note)")), targetSql.toString());
                try (ResultSet rs = dst.executeQuery("select id, note, extra_note from t")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                    assertEquals("payload", rs.getString(2));
                    assertEquals("additional value", rs.getString(3));
                }
            }
        }

        @Test
        void selectedColumnCopySkipsQuotingMetadataButKeepsFetchConfiguration() throws Exception {
            try (Connection source = DriverManager.getConnection("jdbc:h2:mem:selected_copy_source");
                 Connection target = DriverManager.getConnection("jdbc:h2:mem:selected_copy_target");
                 Statement src = source.createStatement();
                 Statement dst = target.createStatement()) {
                src.execute("create table t(id int, note varchar)");
                src.execute("insert into t values(1, 'payload')");
                dst.execute("create table t(note varchar, id int)");
                Connection sourceDialect = dialect(source, "H2", "\"", new ArrayList<>());
                Connection targetDialect = dialect(target, "H2", "\"", new ArrayList<>());
                DatabaseMetaData sourceMetadata = sourceDialect.getMetaData();
                DatabaseMetaData targetMetadata = targetDialect.getMetaData();
                doThrow(new AssertionError("The target SQL builder does not need product metadata")).when(targetMetadata).getDatabaseProductName();
                doThrow(new AssertionError("The target SQL builder does not need product metadata")).when(targetMetadata).getDatabaseProductVersion();
                doThrow(new AssertionError("Quote metadata must not be read when quoting is disabled")).when(targetMetadata).getIdentifierQuoteString();
                doThrow(new AssertionError("Quote metadata must not be read when quoting is disabled")).when(sourceMetadata).getIdentifierQuoteString();

                assertEquals(1, DataTransferUtil.copy(sourceDialect, targetDialect, "t", "t", List.of("id", "note")));
                assertOnlySourceFetchConfigurationReadsProductMetadata(sourceDialect, targetDialect);
                try (ResultSet rs = dst.executeQuery("select id, note from t")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                    assertEquals("payload", rs.getString(2));
                }
            }
        }

        private static void assertOnlySourceFetchConfigurationReadsProductMetadata(Connection source, Connection target) throws Exception {
            // The source product still controls streaming fetch behavior; identifier rendering must add no lookups.
            verify(source.getMetaData(), times(1)).getDatabaseProductName();
            verify(source.getMetaData(), times(1)).getDatabaseProductVersion();
            verify(source.getMetaData(), never()).getIdentifierQuoteString();
            verify(target.getMetaData(), never()).getDatabaseProductName();
            verify(target.getMetaData(), never()).getDatabaseProductVersion();
            verify(target.getMetaData(), never()).getIdentifierQuoteString();
        }

        @Test
        void unsupportedQuotingLeavesOrdinaryColumnsUnquoted() throws Exception {
            for (String quote : new String[] { null, "", " " }) {
                try (Connection real = DriverManager.getConnection("jdbc:h2:mem:no_quotes");
                     Statement stmt = real.createStatement()) {
                    stmt.execute("create table t(id int, note varchar)");
                    Connection conn = dialect(real, "Informix", quote, new ArrayList<>());
                    assertEquals("SELECT ID, NOTE FROM t", JdbcCodeGenerationUtil.generateSelectSql(conn, "t"));
                    assertTrue(JdbcCodeGenerationUtil.generateInsertSql(conn, "t").contains("(ID, NOTE)"));
                    stmt.execute("create table tricky(\"a b\" int)");
                    assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateSelectSql(conn, "tricky"));
                }
            }
        }

        @Test
        void sqlServerAndAseDoNotReturnBracketsWhenIdentifierQuotingIsDisabled() throws Exception {
            for (String product : List.of("Microsoft SQL Server", "Adaptive Server Enterprise")) {
                try (Connection real = DriverManager.getConnection("jdbc:h2:mem:bracket_quotes;MODE=MSSQLServer");
                     Statement stmt = real.createStatement()) {
                    stmt.execute("create table t(id int, note varchar)");
                    stmt.execute("create table tricky(id int, \"ORDER\" int, \"a]b\" int)");
                    Connection conn = dialect(real, product, "\"", new ArrayList<>());
                    assertNull(SqlIdentifierUtil.quoteString(conn, ProductInfo.of(product, "1")));
                    assertEquals("SELECT ID, NOTE FROM t", JdbcCodeGenerationUtil.generateSelectSql(conn, "t"));
                    assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateSelectSql(conn, "tricky"));
                    assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateInsertSql(conn, "tricky"));
                }
            }
        }

        @Test
        void copiesOrdinaryColumnsWhenNeitherDriverSupportsQuoting() throws Exception {
            try (Connection source = DriverManager.getConnection("jdbc:h2:mem:no_quote_copy_source");
                 Connection target = DriverManager.getConnection("jdbc:h2:mem:no_quote_copy_target");
                 Statement src = source.createStatement();
                 Statement dst = target.createStatement()) {
                src.execute("create table t(id int, note varchar)");
                src.execute("insert into t values(1, 'actual value')");
                dst.execute("create table t(note varchar, id int)");
                List<String> targetSql = new ArrayList<>();
                assertEquals(1,
                        DataTransferUtil.copy(dialect(source, "Informix", " ", new ArrayList<>()), dialect(target, "Informix", " ", targetSql), "t", "t"));
                assertTrue(targetSql.stream().anyMatch(sql -> sql.startsWith("INSERT INTO t(ID, NOTE)")), targetSql.toString());
                try (ResultSet rs = dst.executeQuery("select id, note from t")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                    assertEquals("actual value", rs.getString(2));
                }
            }
        }
    }

    @Nested
    class CatalogQualification {
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
                verify(metadata, never()).getIdentifierQuoteString();
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
                verify(metadata, never()).getIdentifierQuoteString();
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
            when(conn.prepareStatement("SELECT * FROM archive.events WHERE 1 > 2")).thenReturn(stmt);
            when(stmt.executeQuery()).thenReturn(rows);
            when(rows.getMetaData()).thenReturn(columns);
            when(columns.getColumnCount()).thenReturn(1);
            when(columns.getColumnName(1)).thenReturn("archived_id");

            assertEquals(List.of("archived_id"), JdbcUtil.getColumnNames(conn, "Archive.Events"));

            verify(conn).prepareStatement("SELECT * FROM archive.events WHERE 1 > 2");
            verify(metadata, never()).getIdentifierQuoteString();
            verify(stmt).close();
        }

        @Test
        void tableExistenceFallbackRetainsCatalogQualificationAndIdentifierFolding() throws SQLException {
            final Connection conn = catalogOnlyConnection();
            final DatabaseMetaData metadata = conn.getMetaData();
            final PreparedStatement stmt = mock(PreparedStatement.class);
            when(metadata.storesLowerCaseIdentifiers()).thenReturn(true);
            when(metadata.getIdentifierQuoteString()).thenReturn("`");
            when(conn.prepareStatement("SELECT 1 FROM archive.events WHERE 1 > 2")).thenReturn(stmt);

            assertTrue(JdbcUtil.tableExists(conn, "Archive.Events"));

            verify(conn).prepareStatement("SELECT 1 FROM archive.events WHERE 1 > 2");
            verify(metadata, never()).getIdentifierQuoteString();
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

    @Nested
    class UnquotedIdentifiers {
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
}
