package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
@Tag("2025")
class JdbcParameterHandlingIntegrationTest {

    @Test
    void sequentialListParametersAvoidRepeatedIndexedTraversal() throws SQLException {
        final CountingLinkedList params = new CountingLinkedList();
        for (int i = 0; i < 128; i++) {
            params.add("value-" + i);
        }
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final List<String> boundValues = new ArrayList<>();
        doAnswer(invocation -> {
            assertEquals(boundValues.size() + 1, invocation.<Integer> getArgument(0));
            boundValues.add(invocation.getArgument(1));
            return null;
        }).when(stmt).setString(anyInt(), anyString());

        final Jdbc.BiParametersSetter<PreparedStatement, List<String>> setter = Jdbc.BiParametersSetter.forList(Collections.nCopies(params.size(), "value"),
                ValueRow.class);
        setter.accept(stmt, params);

        assertEquals(params, boundValues);
        assertEquals(0, params.indexedReads, "Index lookups make binding a linked list quadratic in its parameter count");
    }

    @Test
    void sequentialListViewsPreserveNullsAndHeterogeneousFieldTypes() throws SQLException {
        final List<Object> params = Collections.unmodifiableList(new LinkedList<>(Arrays.asList(null, 37, true)));
        final Jdbc.BiParametersSetter<PreparedStatement, List<Object>> setter = Jdbc.BiParametersSetter.forList(List.of("name", "age", "active"),
                MixedRow.class);

        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:sequential_parameter_types");
             PreparedStatement stmt = conn.prepareStatement("SELECT CAST(? AS VARCHAR), CAST(? AS INT), CAST(? AS BOOLEAN)")) {
            setter.accept(stmt, params);
            try (ResultSet rows = stmt.executeQuery()) {
                assertTrue(rows.next());
                assertNull(rows.getString(1));
                assertEquals(37, rows.getInt(2));
                assertTrue(rows.getBoolean(3));
            }
        }
    }

    @Test
    void outParameterSnapshotsShareImmutableDefinitions() {
        final Jdbc.OutParam indexed = Jdbc.OutParam.of(1, Types.INTEGER);
        final Jdbc.OutParam named = Jdbc.OutParam.of("status", Types.VARCHAR);
        final List<Jdbc.OutParam> definitions = new ArrayList<>(List.of(indexed, named));
        final Map<Object, Object> values = new LinkedHashMap<>();
        values.put(1, 42);
        values.put("status", "ok");

        final Jdbc.OutParamResult result = new Jdbc.OutParamResult(definitions, values);
        definitions.clear();
        values.clear();

        assertEquals(List.of(indexed, named), result.getOutParams());
        assertEquals(Map.of(1, 42, "status", "ok"), result.getOutParamValues());
        assertSame(indexed, result.getOutParams().get(0));
        assertSame(named, result.getOutParams().get(1));
        assertThrows(UnsupportedOperationException.class, () -> result.getOutParams().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.getOutParamValues().clear());
    }

    public record ValueRow(String value) {
    }

    public record MixedRow(String name, Integer age, Boolean active) {
    }

    private static final class CountingLinkedList extends LinkedList<String> {
        private static final long serialVersionUID = 1L;
        private int indexedReads;

        @Override
        public String get(final int index) {
            indexedReads++;
            return super.get(index);
        }
    }
}
