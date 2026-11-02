package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.Reader;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.Throwables;

public class DataTransferArgumentValidationTest extends TestBase {

    @Test
    void datasetOverloadsRejectNullBeforeAccessingResources() {
        final Connection conn = mock(Connection.class);
        final DataSource dataSource = mock(DataSource.class);
        final PreparedStatement stmt = mock(PreparedStatement.class);

        assertArgument("dataset", () -> DataTransferUtil.importData(null, conn, "INSERT INTO users VALUES (?)"));
        assertArgument("dataset", () -> DataTransferUtil.importData(null, dataSource, "INSERT INTO users VALUES (?)"));
        assertArgument("dataset", () -> DataTransferUtil.importData(null, stmt));
        verifyNoInteractions(conn, dataSource, stmt);

        final Dataset unusedDataset = spy(Dataset.rows(List.of("id"), new Object[0][]));
        assertArgument("conn", () -> DataTransferUtil.importData(unusedDataset, (Connection) null, "INSERT INTO users VALUES (?)"));
        assertArgument("stmt", () -> DataTransferUtil.importData(unusedDataset, (PreparedStatement) null));
        verifyNoInteractions(unusedDataset);
    }

    @Test
    void importsRejectNullConnectionsEvenWhenThereAreNoRows() {
        final Dataset dataset = Dataset.rows(List.of("id"), new Object[0][]);
        final String sql = "INSERT INTO users VALUES (?)";
        final Throwables.BiConsumer<PreparedQuery, Object[], SQLException> setter = (stmt, row) -> {
        };

        assertArgument("conn", () -> DataTransferUtil.importData(dataset, (Connection) null, sql));
        assertArgument("conn", () -> DataTransferUtil.importData(dataset, List.of("id"), null, sql));
        assertArgument("conn", () -> DataTransferUtil.importData(dataset, null, sql, setter));
        assertArgument("conn", () -> DataTransferUtil.importData(Collections.emptyIterator(), null, sql, 1, 0, (stmt, row) -> {
        }));
        assertArgument("conn", () -> DataTransferUtil.importCsv(new File("unused.csv"), null, sql, 1, 0, (stmt, row) -> {
        }));
        assertArgument("conn", () -> DataTransferUtil.importFrom(dataset).to((Connection) null, sql));
        assertArgument("conn", () -> DataTransferUtil.importFrom(Collections.emptyIterator()).parameterSetter((stmt, row) -> {
        }).to((Connection) null, sql));
    }

    @Test
    void exportsRejectRequiredNullResourcesBeforeTouchingOutput() {
        final File file = mock(File.class);
        final StringWriter writer = new StringWriter();

        assertArgument("conn", () -> DataTransferUtil.exportCsv((Connection) null, "SELECT 1", file));
        assertArgument("conn", () -> DataTransferUtil.exportCsv((Connection) null, "SELECT 1", writer));
        assertArgument("stmt", () -> DataTransferUtil.exportCsv((PreparedStatement) null, file));
        assertEquals("", writer.toString());
        verifyNoInteractions(file);
    }

    @Test
    void copyChecksConnectionsInSignatureOrderWithoutExecutingSql() {
        final Connection source = mock(Connection.class);
        final Connection target = mock(Connection.class);

        assertArgument("sourceConn", () -> DataTransferUtil.copy((Connection) null, "SELECT 1", target, "INSERT INTO users VALUES (?)"));
        assertArgument("targetConn", () -> DataTransferUtil.copy(source, "SELECT 1", null, "INSERT INTO users VALUES (?)"));
        assertArgument("targetConn", () -> DataTransferUtil.copyFrom(source, "SELECT 1").to(null, "INSERT INTO users VALUES (?)"));
        verifyNoInteractions(source, target);
    }

    @Test
    void copyRejectsNullOrBlankSqlBeforeAcquiringConnections() {
        final Connection source = mock(Connection.class);
        final Connection target = mock(Connection.class);
        final DataSource sourceDs = mock(DataSource.class);
        final DataSource targetDs = mock(DataSource.class);
        final String select = "SELECT id FROM users";
        final String insert = "INSERT INTO users_copy VALUES (?)";
        final Throwables.BiConsumer<PreparedQuery, ResultSet, SQLException> setter = (query, rs) -> {
        };

        for (final String bad : new String[] { null, "", "  " }) {
            assertArgument("selectSql", () -> DataTransferUtil.copy(sourceDs, bad, targetDs, insert));
            assertArgument("insertSql", () -> DataTransferUtil.copy(sourceDs, select, targetDs, bad));
            assertArgument("selectSql", () -> DataTransferUtil.copy(sourceDs, bad, 10, targetDs, insert, 10));
            assertArgument("insertSql", () -> DataTransferUtil.copy(sourceDs, select, targetDs, bad, setter));
            assertArgument("insertSql", () -> DataTransferUtil.copy(sourceDs, select, 10, targetDs, bad, 10, 0, setter));
            assertArgument("selectSql", () -> DataTransferUtil.copy(source, bad, target, insert));
            assertArgument("insertSql", () -> DataTransferUtil.copy(source, select, target, bad));
            assertArgument("insertSql", () -> DataTransferUtil.copy(source, select, 10, target, bad, 10));
            assertArgument("selectSql", () -> DataTransferUtil.copy(source, bad, target, insert, setter));
            assertArgument("selectSql", () -> DataTransferUtil.copy(source, bad, 10, target, insert, 10, 0, setter));
            assertArgument("selectSql", () -> DataTransferUtil.copyFrom(sourceDs, bad));
            assertArgument("selectSql", () -> DataTransferUtil.copyFrom(source, bad));
            assertArgument("insertSql", () -> DataTransferUtil.copyFrom(sourceDs, select).to(targetDs, bad));
            assertArgument("insertSql", () -> DataTransferUtil.copyFrom(source, select).to(target, bad));
        }

        verifyNoInteractions(source, target, sourceDs, targetDs);
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void importsRejectNullOrBlankInsertSqlBeforeAcquiringConnectionsOrReadingInput() {
        final Connection conn = mock(Connection.class);
        final DataSource dataSource = mock(DataSource.class);
        final Iterator<Object> iter = mock(Iterator.class);
        final Reader reader = mock(Reader.class);
        final File missingFile = new File("missing-import-input.csv");
        final Dataset dataset = Dataset.rows(List.of("id"), new Object[][] { { 1 } });
        final List<String> columns = List.of("id");
        final Predicate<Object[]> filter = row -> true;
        final Map<String, Type> types = Map.of();
        final Throwables.BiConsumer<PreparedQuery, Object[], SQLException> rowSetter = (query, row) -> {
        };
        final Throwables.BiConsumer<PreparedQuery, Object, SQLException> iterSetter = (query, element) -> {
        };
        final Throwables.BiConsumer<PreparedQuery, String[], SQLException> csvSetter = (query, row) -> {
        };

        for (final String bad : new String[] { null, "", "  " }) {
            assertArgument("insertSql", () -> DataTransferUtil.importData(dataset, dataSource, bad));
            assertArgument("insertSql", () -> DataTransferUtil.importData(dataset, conn, bad));
            assertArgument("insertSql", () -> DataTransferUtil.importData(dataset, columns, conn, bad));
            assertArgument("insertSql", () -> DataTransferUtil.importData(dataset, columns, conn, bad, 10, 0));
            assertArgument("insertSql", () -> DataTransferUtil.importData(dataset, columns, filter, conn, bad, 10, 0));
            assertArgument("insertSql", () -> DataTransferUtil.importData(dataset, conn, bad, types));
            assertArgument("insertSql", () -> DataTransferUtil.importData(dataset, conn, bad, 10, 0, types));
            assertArgument("insertSql", () -> DataTransferUtil.importData(dataset, filter, conn, bad, 10, 0, types));
            assertArgument("insertSql", () -> DataTransferUtil.importData(dataset, conn, bad, rowSetter));
            assertArgument("insertSql", () -> DataTransferUtil.importData(dataset, conn, bad, 10, 0, rowSetter));
            assertArgument("insertSql", () -> DataTransferUtil.importData(dataset, filter, conn, bad, 10, 0, rowSetter));
            assertArgument("insertSql", () -> DataTransferUtil.importData(iter, dataSource, bad, iterSetter));
            assertArgument("insertSql", () -> DataTransferUtil.importData(iter, conn, bad, 10, 0, iterSetter));
            assertArgument("insertSql", () -> DataTransferUtil.importCsv(missingFile, dataSource, bad, csvSetter));
            assertArgument("insertSql", () -> DataTransferUtil.importCsv(missingFile, conn, bad, 10, 0, csvSetter));
            assertArgument("insertSql", () -> DataTransferUtil.importCsv(reader, dataSource, bad, csvSetter));
            assertArgument("insertSql", () -> DataTransferUtil.importFrom(dataset).to(dataSource, bad));
            assertArgument("insertSql", () -> DataTransferUtil.importFrom(dataset).to(conn, bad));
            assertArgument("insertSql", () -> DataTransferUtil.importFrom(iter).parameterSetter(iterSetter).to(dataSource, bad));
            assertArgument("insertSql", () -> DataTransferUtil.importFrom(iter).parameterSetter(iterSetter).to(conn, bad));
            assertArgument("insertSql", () -> DataTransferUtil.importCsvFrom(reader).parameterSetter(csvSetter).to(dataSource, bad));
            assertArgument("insertSql", () -> DataTransferUtil.importCsvFrom(missingFile).parameterSetter(csvSetter).to(conn, bad));
        }

        verifyNoInteractions(conn, dataSource, iter, reader);
    }

    @Test
    void setterRejectsNullResourcesEvenAfterCachingAnEmptyResultShape() throws Exception {
        final PreparedQuery query = new PreparedQuery(mock(PreparedStatement.class));
        final ResultSet rs = mock(ResultSet.class);
        final ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(0);
        final Throwables.BiConsumer<PreparedQuery, ResultSet, SQLException> setter = DataTransferUtil
                .newResultSetParameterSetter((resultSet, index) -> resultSet.getObject(index));

        assertArgument("stmt", () -> setter.accept(null, rs));
        verifyNoInteractions(rs);
        setter.accept(query, rs);
        assertArgument("rs", () -> setter.accept(query, null));
    }

    @Test
    void optionalNullColumnConfigurationStillUsesDefaults() throws SQLException {
        final Dataset dataset = Dataset.rows(List.of("id"), new Object[0][]);
        final PreparedStatement stmt = mock(PreparedStatement.class);

        assertEquals(0, DataTransferUtil.importFrom(dataset).columns(null).columnTypes(null).to(stmt));
        verifyNoInteractions(stmt);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBatchConfigurations")
    void invalidBatchConfigurationIsRejected(final String name, final Executable action) {
        assertArgument("batchSize", action);
    }

    private static java.util.stream.Stream<Arguments> invalidBatchConfigurations() {
        final Dataset dataset = Dataset.rows(List.of("id"), new Object[0][]);
        final Connection conn = mock(Connection.class);
        final DataSource sourceDs = mock(DataSource.class);
        final DataSource targetDs = mock(DataSource.class);
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final Iterator<Object> iter = Collections.emptyIterator();
        final File file = new File("unused.csv");
        final Reader reader = new java.io.StringReader("id\n");
        final Predicate<Object[]> filter = row -> true;
        final Predicate<String[]> csvFilter = row -> true;
        final String sql = "INSERT INTO users VALUES (?)";
        final String select = "SELECT id FROM users";
        final Throwables.BiConsumer<PreparedQuery, Object[], SQLException> rowSetter = (query, row) -> {
        };
        final Throwables.BiConsumer<PreparedQuery, Object, SQLException> iterSetter = (query, row) -> {
        };
        final Throwables.BiConsumer<PreparedQuery, String[], SQLException> csvSetter = (query, row) -> {
        };
        final Throwables.BiConsumer<PreparedQuery, ResultSet, SQLException> copySetter = (query, rs) -> {
        };

        return java.util.stream.Stream.of(
                batchCase("filtered dataset with types through connection", () -> DataTransferUtil.importData(dataset, filter, conn, sql, 0, 0, Map.of())),
                batchCase("filtered dataset with custom setter through connection",
                        () -> DataTransferUtil.importData(dataset, filter, conn, sql, 0, 0, rowSetter)),
                batchCase("selected filtered dataset through statement", () -> DataTransferUtil.importData(dataset, List.of("id"), filter, stmt, 0, 0)),
                batchCase("filtered dataset with types through statement", () -> DataTransferUtil.importData(dataset, filter, stmt, 0, 0, Map.of())),
                batchCase("filtered dataset with custom setter through statement", () -> DataTransferUtil.importData(dataset, filter, stmt, 0, 0, rowSetter)),
                batchCase("iterator through connection", () -> DataTransferUtil.importData(iter, conn, sql, 0, 0, iterSetter)),
                batchCase("iterator through statement", () -> DataTransferUtil.importData(iter, stmt, 0, 0, iterSetter)),
                batchCase("CSV file through connection", () -> DataTransferUtil.importCsv(file, conn, sql, 0, 0, csvSetter)),
                batchCase("filtered CSV file through statement", () -> DataTransferUtil.importCsv(file, csvFilter, stmt, 0, 0, csvSetter)),
                batchCase("filtered CSV reader through statement", () -> DataTransferUtil.importCsv(reader, csvFilter, stmt, 0, 0, csvSetter)),
                batchCase("copy through data sources", () -> DataTransferUtil.copy(sourceDs, select, 10, targetDs, sql, 0, 0, copySetter)),
                batchCase("copy through connections", () -> DataTransferUtil.copy(conn, select, 10, conn, sql, 0, 0, copySetter)),
                batchCase("dataset builder", () -> DataTransferUtil.importFrom(dataset).batchSize(0).to(stmt)),
                batchCase("row builder", () -> DataTransferUtil.importFrom(iter).batchSize(0).parameterSetter(iterSetter).to(stmt)));
    }

    @Test
    void buildersRejectMissingSources() {
        final PreparedStatement stmt = mock(PreparedStatement.class);

        final IllegalStateException importFailure = assertThrows(IllegalStateException.class,
                () -> new DataTransferUtil.RowImportBuilder<Object>(null, null, null).to(stmt));
        assertTrue(importFailure.getMessage().contains("Exactly one of 'iter', 'reader' or 'file'"));

        final IllegalStateException exportFailure = assertThrows(IllegalStateException.class,
                () -> new DataTransferUtil.CsvExportBuilder(null, null, null, null, null).to(new StringWriter()));
        assertTrue(exportFailure.getMessage().contains("Exactly one of 'dataSource', 'conn', 'stmt' or 'rs'"));
    }

    @Test
    void importRejectsMissingColumnInMultiColumnSelection() {
        final Dataset dataset = Dataset.rows(List.of("id"), new Object[0][]);
        final Connection conn = mock(Connection.class);

        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.importData(dataset, List.of("id", "missing"), conn, "INSERT INTO users VALUES (?, ?)"));

        assertTrue(failure.getMessage().contains("Column 'missing' is not found"));
        verifyNoInteractions(conn);
    }

    @Test
    void exportCsvWrapsOutputOpenFailure() throws SQLException {
        final ResultSet rs = mock(ResultSet.class);
        final ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(0);
        final File output = new File("target/missing-parent-" + System.nanoTime() + "/output.csv");

        final java.io.UncheckedIOException failure = assertThrows(java.io.UncheckedIOException.class, () -> DataTransferUtil.exportCsv(rs, output));

        assertTrue(failure.getCause() instanceof java.io.IOException);
    }

    @Test
    void copyWithEmptyColumnsDelegatesToAllColumnOverload() throws SQLException {
        final DataSource sourceDs = mock(DataSource.class);
        final DataSource targetDs = mock(DataSource.class);
        final SQLException failure = new SQLException("stop after delegation");
        when(sourceDs.getConnection()).thenThrow(failure);

        final org.springframework.jdbc.CannotGetJdbcConnectionException thrown = assertThrows(org.springframework.jdbc.CannotGetJdbcConnectionException.class,
                () -> DataTransferUtil.copy(sourceDs, targetDs, "users", "users_copy", List.of(), 1));

        assertEquals(failure, thrown.getCause());
    }

    private static Arguments batchCase(final String name, final Executable action) {
        return Arguments.of(name, action);
    }

    private static void assertArgument(final String parameterName, final Executable action) {
        assertTrue(assertThrows(IllegalArgumentException.class, action).getMessage().contains(parameterName));
    }
}
