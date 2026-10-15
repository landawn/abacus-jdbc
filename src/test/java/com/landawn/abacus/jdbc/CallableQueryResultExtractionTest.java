package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("2025")
class CallableQueryResultExtractionTest {
    @Test
    void outputParameterSnapshotsRemainIsolatedAcrossCallbackMutationAndReregistration() throws Exception {
        final CallableStatement statement = mock(CallableStatement.class);
        try (CallableQuery query = new CallableQuery(statement).closeAfterExecution(false)) {
            query.registerOutParameter(1, Types.INTEGER);
            query.executeThenAccept((stmt, definitions, firstResultSet) -> definitions.clear());

            final List<Jdbc.OutParam> before = query.executeThenApply((stmt, definitions, firstResultSet) -> definitions);
            assertEquals(1, before.size());
            assertEquals(Types.INTEGER, before.get(0).sqlType());

            query.registerOutParameter(1, Types.VARCHAR);
            final List<Jdbc.OutParam> after = query.executeThenApply((stmt, definitions, firstResultSet) -> definitions);
            assertEquals(1, after.size());
            assertEquals(Types.VARCHAR, after.get(0).sqlType());
            assertEquals(Types.INTEGER, before.get(0).sqlType());
            after.clear();
            assertEquals(1, before.size());
        }
    }

    @Test
    void reusedClassExtractorKeepsMetadataSeparateForEveryResultSet() throws Exception {
        final ResultSet first = singleRow("name", "id");
        final ResultSet second = singleRow("amount");
        when(first.getObject(1)).thenReturn("first");
        when(first.getObject(2)).thenReturn(1);
        when(second.getObject(1)).thenReturn(2.5);
        final CallableStatement statement = statement(first, second);

        try (CallableQuery query = new CallableQuery(statement)) {
            final List<List<Object[]>> rows = query.listAllResultSetsAndGetOutParameters(Object[].class)._1;
            assertEquals(2, rows.size());
            assertEquals(1, rows.get(0).size());
            assertEquals(1, rows.get(1).size());
            assertArrayEquals(new Object[] { "first", 1 }, rows.get(0).get(0));
            assertArrayEquals(new Object[] { 2.5 }, rows.get(1).get(0));
        }
        verify(first).close();
        verify(second).close();
    }

    @Test
    void reusedMapperExtractorsApplyFiltersAndLabelsToEachResultSet() throws Exception {
        for (int variant = 0; variant < 4; variant++) {
            final ResultSet first = singleRow("first_label");
            final ResultSet second = singleRow("second_label");
            when(first.getString(1)).thenReturn("first");
            when(second.getString(1)).thenReturn("second");
            final CallableStatement statement = statement(first, second);
            try (CallableQuery query = new CallableQuery(statement)) {
                final List<List<String>> rows;
                if (variant == 0) {
                    rows = query.listAllResultSetsAndGetOutParameters((Jdbc.RowMapper<String>) rs -> rs.getString(1))._1;
                    assertEquals(List.of(List.of("first"), List.of("second")), rows);
                } else if (variant == 1) {
                    rows = query.listAllResultSetsAndGetOutParameters(rs -> rs.getString(1).startsWith("s"), rs -> rs.getString(1))._1;
                    assertEquals(List.of(List.of(), List.of("second")), rows);
                } else if (variant == 2) {
                    rows = query.listAllResultSetsAndGetOutParameters((rs, labels) -> labels.get(0) + ":" + rs.getString(1))._1;
                    assertEquals(List.of(List.of("first_label:first"), List.of("second_label:second")), rows);
                } else {
                    rows = query.listAllResultSetsAndGetOutParameters((rs, labels) -> labels.get(0).equals("second_label"),
                            (rs, labels) -> labels.get(0) + ":" + rs.getString(1))._1;
                    assertEquals(List.of(List.of(), List.of("second_label:second")), rows);
                }
            }
            verify(first).close();
            verify(second).close();
        }
    }

    private static CallableStatement statement(final ResultSet first, final ResultSet second) throws SQLException {
        final CallableStatement statement = mock(CallableStatement.class);
        when(statement.execute()).thenReturn(true);
        when(statement.getResultSet()).thenReturn(first, second);
        when(statement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(true, false);
        when(statement.getUpdateCount()).thenReturn(-1);
        return statement;
    }

    private static ResultSet singleRow(final String... labels) throws SQLException {
        final ResultSet rows = mock(ResultSet.class);
        final ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(rows.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(labels.length);
        for (int i = 0; i < labels.length; i++) {
            when(metadata.getColumnLabel(i + 1)).thenReturn(labels[i]);
        }
        when(rows.next()).thenReturn(true, false);
        return rows;
    }
}
