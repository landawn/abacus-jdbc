package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.Throwables;

class DataTransferNullArgumentValidationTest extends TestBase {

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
        final Throwables.BiConsumer<PreparedQuery, Object[], SQLException> setter = (stmt, row) -> { };

        assertArgument("conn", () -> DataTransferUtil.importData(dataset, (Connection) null, sql));
        assertArgument("conn", () -> DataTransferUtil.importData(dataset, List.of("id"), null, sql));
        assertArgument("conn", () -> DataTransferUtil.importData(dataset, null, sql, setter));
        assertArgument("conn", () -> DataTransferUtil.importData(Collections.emptyIterator(), null, sql, 1, 0, (stmt, row) -> { }));
        assertArgument("conn", () -> DataTransferUtil.importCsv(new File("unused.csv"), null, sql, 1, 0, (stmt, row) -> { }));
        assertArgument("conn", () -> DataTransferUtil.importFrom(dataset).to((Connection) null, sql));
        assertArgument("conn", () -> DataTransferUtil.importFrom(Collections.emptyIterator()).parameterSetter((stmt, row) -> { })
                .to((Connection) null, sql));
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
    void setterRejectsNullResourcesEvenAfterCachingAnEmptyResultShape() throws Exception {
        final PreparedQuery query = new PreparedQuery(mock(PreparedStatement.class));
        final ResultSet rs = mock(ResultSet.class);
        final ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(0);
        final Throwables.BiConsumer<PreparedQuery, ResultSet, SQLException> setter =
                DataTransferUtil.newResultSetParameterSetter((resultSet, index) -> resultSet.getObject(index));

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

    @Test
    void identifierQuotingRejectsRequiredNullInputsButPreservesEmptyIdentifiers() {
        assertArgument("identifier", () -> SqlIdentifierUtil.quoteIdentifier(null, "\""));
        assertArgument("quote", () -> SqlIdentifierUtil.quoteIdentifier("name", null));
        assertArgument("quote", () -> SqlIdentifierUtil.quoteIdentifier("name", ""));
        assertEquals("\"\"", SqlIdentifierUtil.quoteIdentifier("", "\""));
        assertEquals("\"a\"\"b\"", SqlIdentifierUtil.quoteIdentifier("a\"b", "\""));
    }

    private static void assertArgument(final String parameterName, final Executable action) {
        assertTrue(assertThrows(IllegalArgumentException.class, action).getMessage().contains(parameterName));
    }
}
