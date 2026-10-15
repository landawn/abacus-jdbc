package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class JdbcRowCallbacksTest extends JdbcTestSupport {

    // RowConsumer Tests
    @Test
    public void testRowConsumerDoNothing() throws SQLException {
        assertDoesNotThrow(() -> Jdbc.RowConsumer.DO_NOTHING.accept(mockResultSet));
        verifyNoInteractions(mockResultSet);
    }

    // BiRowConsumer Tests
    @Test
    public void testBiRowConsumerDoNothing() throws SQLException {
        assertDoesNotThrow(() -> Jdbc.BiRowConsumer.DO_NOTHING.accept(mockResultSet, Arrays.asList("col1")));
        verifyNoInteractions(mockResultSet);
    }

    @Test
    public void testBiRowConsumerAccept() throws SQLException {
        List<String> results = new ArrayList<>();
        Jdbc.BiRowConsumer consumer = (rs, cols) -> {
            results.add(rs.getString(1));
            results.add(cols.get(0));
        };

        when(mockResultSet.getString(1)).thenReturn("value");
        consumer.accept(mockResultSet, Arrays.asList("column1"));

        assertEquals(2, results.size());
        assertEquals("value", results.get(0));
        assertEquals("column1", results.get(1));
    }

    @Test
    public void testBiRowConsumerAndThen() throws SQLException {
        List<String> results = new ArrayList<>();
        Jdbc.BiRowConsumer consumer1 = (rs, cols) -> results.add("first");
        Jdbc.BiRowConsumer consumer2 = (rs, cols) -> results.add("second");
        Jdbc.BiRowConsumer combined = consumer1.andThen(consumer2);

        combined.accept(mockResultSet, Arrays.asList("col1"));

        assertEquals(2, results.size());
        assertEquals("first", results.get(0));
        assertEquals("second", results.get(1));
    }

    @Test
    public void testBiRowFilterAnd() throws SQLException {
        when(mockResultSet.getInt(1)).thenReturn(20);

        Jdbc.BiRowFilter filter1 = (rs, cols) -> cols.size() >= 2;
        Jdbc.BiRowFilter filter2 = (rs, cols) -> rs.getInt(1) >= 18;
        Jdbc.BiRowFilter combined = filter1.and(filter2);

        assertTrue(combined.test(mockResultSet, Arrays.asList("col1", "col2")));

        when(mockResultSet.getInt(1)).thenReturn(15);
        assertFalse(combined.test(mockResultSet, Arrays.asList("col1", "col2")));
    }

    @Test
    public void testBiRowFilterNegate() throws SQLException {
        Jdbc.BiRowFilter filter = (rs, cols) -> cols.size() > 2;
        Jdbc.BiRowFilter negated = filter.negate();

        assertTrue(filter.test(mockResultSet, Arrays.asList("a", "b", "c")));
        assertFalse(negated.test(mockResultSet, Arrays.asList("a", "b", "c")));

        assertFalse(filter.test(mockResultSet, Arrays.asList("a", "b")));
        assertTrue(negated.test(mockResultSet, Arrays.asList("a", "b")));
    }

    @Test
    public void testBiRowFilterOr() throws SQLException {
        when(mockResultSet.getInt(1)).thenReturn(10);

        Jdbc.BiRowFilter filter1 = (rs, cols) -> cols.size() > 3;
        Jdbc.BiRowFilter filter2 = (rs, cols) -> rs.getInt(1) > 5;
        Jdbc.BiRowFilter combined = filter1.or(filter2);

        // filter1=false (only 2 cols), filter2=true (10 > 5) => true
        assertTrue(combined.test(mockResultSet, Arrays.asList("a", "b")));

        when(mockResultSet.getInt(1)).thenReturn(3);
        // filter1=false, filter2=false => false
        assertFalse(combined.test(mockResultSet, Arrays.asList("a", "b")));
    }

    @Test
    public void testBiRowFilterTest() throws SQLException {
        when(mockResultSet.getString(1)).thenReturn("ACTIVE");

        Jdbc.BiRowFilter filter = (rs, cols) -> {
            return cols.contains("status") && rs.getString(1).equals("ACTIVE");
        };

        assertTrue(filter.test(mockResultSet, Arrays.asList("status")));
        assertFalse(filter.test(mockResultSet, Arrays.asList("other")));
    }

    @Test
    public void testRowExtractorBuilder() throws SQLException {
        when(mockResultSet.getInt(1)).thenReturn(100);
        when(mockResultSet.getString(2)).thenReturn("test");
        when(mockResultSet.getDouble(3)).thenReturn(3.14);

        Jdbc.RowExtractor extractor = Jdbc.RowExtractor.builder().getInt(1).getString(2).getDouble(3).build();

        Object[] outputRow = new Object[3];
        extractor.accept(mockResultSet, outputRow);

        assertEquals(100, outputRow[0]);
        assertEquals("test", outputRow[1]);
        assertEquals(3.14, outputRow[2]);
    }

    @Test
    public void testRowExtractorBuilderWithCustomGetter() throws SQLException {
        when(mockResultSet.getString(1)).thenReturn("hello");

        Jdbc.RowExtractor extractor = Jdbc.RowExtractor.builder().get(1, (rs, idx) -> rs.getString(idx).toUpperCase()).build();

        Object[] outputRow = new Object[3];
        extractor.accept(mockResultSet, outputRow);

        assertEquals("HELLO", outputRow[0]);
    }

    @Test
    public void testRowExtractorBuilderSnapshotsConfigurationWhenBuilt() throws SQLException {
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSet.getInt(1)).thenReturn(42);
        when(mockResultSet.getString(1)).thenReturn("changed");

        final Jdbc.RowExtractor.RowExtractorBuilder builder = Jdbc.RowExtractor.builder().getInt(1);
        final Jdbc.RowExtractor extractor = builder.build();
        builder.getString(1);

        final Object[] output = new Object[1];
        extractor.accept(mockResultSet, output);

        assertEquals(42, output[0]);
    }

    @Test
    public void testRowExtractorAllBuilderMethods() throws SQLException {
        when(mockResultSet.getBoolean(1)).thenReturn(true);
        when(mockResultSet.getByte(2)).thenReturn((byte) 5);
        when(mockResultSet.getShort(3)).thenReturn((short) 50);
        when(mockResultSet.getInt(4)).thenReturn(500);
        when(mockResultSet.getLong(5)).thenReturn(5000L);
        when(mockResultSet.getFloat(6)).thenReturn(5.5f);
        when(mockResultSet.getDouble(7)).thenReturn(5.55);
        when(mockResultSet.getBigDecimal(8)).thenReturn(new BigDecimal("555.55"));
        when(mockResultSet.getString(9)).thenReturn("test");
        when(mockResultSet.getDate(10)).thenReturn(new Date(System.currentTimeMillis()));
        when(mockResultSet.getTime(11)).thenReturn(new Time(System.currentTimeMillis()));
        when(mockResultSet.getTimestamp(12)).thenReturn(new Timestamp(System.currentTimeMillis()));
        when(mockResultSet.getObject(13)).thenReturn("obj");
        when(mockResultSet.getString(14)).thenReturn("typed");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(14);

        Jdbc.RowExtractor extractor = Jdbc.RowExtractor.builder()
                .getBoolean(1)
                .getByte(2)
                .getShort(3)
                .getInt(4)
                .getLong(5)
                .getFloat(6)
                .getDouble(7)
                .getBigDecimal(8)
                .getString(9)
                .getDate(10)
                .getTime(11)
                .getTimestamp(12)
                .getObject(13)
                .getObject(14, String.class)
                .build();

        Object[] output = new Object[14];
        extractor.accept(mockResultSet, output);

        assertEquals(true, output[0]);
        assertEquals((byte) 5, output[1]);
        assertEquals((short) 50, output[2]);
        assertEquals(500, output[3]);
        assertEquals(5000L, output[4]);
        assertEquals(5.5f, output[5]);
        assertEquals(5.55, output[6]);
        assertEquals(new BigDecimal("555.55"), output[7]);
        assertEquals("test", output[8]);
        assertNotNull(output[9]); // Date
        assertNotNull(output[10]); // Time
        assertNotNull(output[11]); // Timestamp
        assertEquals("obj", output[12]);
        assertEquals("typed", output[13]);
    }

    @Test
    public void testRowExtractorBuilderRejectsShortOutputArrayAfterInitialization() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1);
        when(mockResultSet.getObject(2)).thenReturn(2);
        when(mockResultSet.getObject(3)).thenReturn(3);

        Jdbc.RowExtractor extractor = Jdbc.RowExtractor.builder().build();

        extractor.accept(mockResultSet, new Object[3]);

        assertThrows(IllegalArgumentException.class, () -> extractor.accept(mockResultSet, new Object[2]));
    }

    @Test
    public void testRowExtractorBuilder_ConfiguredIndexBeyondColumnCountThrowsIAE() throws SQLException {
        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);

        Jdbc.RowExtractor extractor = Jdbc.RowExtractor.builder().getInt(3).build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> extractor.accept(mockResultSet, new Object[3]));
        assertTrue(ex.getMessage().contains("3"), "Expected message to mention missing configured index 3: " + ex.getMessage());
    }

    @Test
    public void testStatefulRowCallbacksValidateRequiredResourcesFirst() {
        final Jdbc.BiParametersSetter<PreparedStatement, Object[]> arraySetter = Jdbc.BiParametersSetter.forArray(List.of("id"), TestEntity.class);
        final Jdbc.BiParametersSetter<PreparedStatement, List<Object>> listSetter = Jdbc.BiParametersSetter.forList(List.of("id"), TestEntity.class);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> arraySetter.accept(null, null)).getMessage().contains("stmt"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> listSetter.accept(null, null)).getMessage().contains("stmt"));
        assertTrue(
                assertThrows(IllegalArgumentException.class, () -> Jdbc.RowExtractor.forType(TestEntity.class).accept(null, null)).getMessage().contains("rs"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Jdbc.RowExtractor.builder().build().accept(null, null)).getMessage().contains("rs"));
    }

    @Test
    public void testRowConsumerForDisposableObjArray() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1);
        when(mockResultSet.getObject(2)).thenReturn("John");
        when(mockResultSet.getObject(3)).thenReturn(25);

        List<Object> results = new ArrayList<>();
        Jdbc.RowConsumer consumer = Jdbc.RowConsumer.forDisposableObjArray(arr -> {
            for (int i = 0; i < arr.length(); i++) {
                results.add(arr.get(i));
            }
        });

        consumer.accept(mockResultSet);

        assertEquals(3, results.size());
        assertEquals(1, results.get(0));
        assertEquals("John", results.get(1));
        assertEquals(25, results.get(2));
    }

    @Test
    public void testRowConsumerForDisposableObjArray_WithEntityClass() throws SQLException {
        when(mockResultSet.getLong(1)).thenReturn(99L);
        when(mockResultSet.getString(2)).thenReturn("Kim");
        when(mockResultSet.getObject(3)).thenReturn(null);

        List<Object> results = new ArrayList<>();
        Jdbc.RowConsumer consumer = Jdbc.RowConsumer.forDisposableObjArray(TestEntity.class, arr -> {
            for (int i = 0; i < arr.length(); i++) {
                results.add(arr.get(i));
            }
        });

        consumer.accept(mockResultSet);

        assertEquals(3, results.size());
    }

    @Test
    public void testBiRowConsumerForDisposableObjArray() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1);
        when(mockResultSet.getObject(2)).thenReturn("John");

        List<Object> results = new ArrayList<>();
        List<String> capturedLabels = new ArrayList<>();

        Jdbc.BiRowConsumer consumer = Jdbc.BiRowConsumer.forDisposableObjArray((cols, arr) -> {
            capturedLabels.addAll(cols);
            for (int i = 0; i < arr.length(); i++) {
                results.add(arr.get(i));
            }
        });

        List<String> columnLabels = Arrays.asList("id", "name");
        consumer.accept(mockResultSet, columnLabels);

        assertEquals(2, results.size());
        assertEquals(1, results.get(0));
        assertEquals("John", results.get(1));
        assertEquals(columnLabels, capturedLabels);
    }

    @Test
    public void testBiRowConsumerForDisposableObjArray_WithEntityClass() throws SQLException {
        when(mockResultSet.getLong(1)).thenReturn(5L);
        when(mockResultSet.getString(2)).thenReturn("Leo");
        when(mockResultSet.getObject(3)).thenReturn(null);

        List<Object> results = new ArrayList<>();
        List<String> capturedLabels = new ArrayList<>();

        Jdbc.BiRowConsumer consumer = Jdbc.BiRowConsumer.forDisposableObjArray(TestEntity.class, (cols, arr) -> {
            capturedLabels.addAll(cols);
            for (int i = 0; i < arr.length(); i++) {
                results.add(arr.get(i));
            }
        });

        List<String> columnLabels = Arrays.asList("id", "name", "age");
        consumer.accept(mockResultSet, columnLabels);

        assertEquals(3, results.size());
        assertEquals(columnLabels, capturedLabels);
    }

    @Test
    public void testRowConsumerCreate() throws SQLException {
        List<Integer> columnIndices = new ArrayList<>();
        Jdbc.RowConsumer consumer = Jdbc.RowConsumer.forEachColumn((rs, colIndex) -> columnIndices.add(colIndex));

        consumer.accept(mockResultSet);

        assertEquals(3, columnIndices.size());
        assertEquals(Arrays.asList(1, 2, 3), columnIndices);
    }

    @Test
    public void testBiRowConsumerCreate() throws SQLException {
        List<Integer> columnIndices = new ArrayList<>();
        Jdbc.BiRowConsumer consumer = Jdbc.BiRowConsumer.forEachColumn((rs, colIndex) -> columnIndices.add(colIndex));

        consumer.accept(mockResultSet, Arrays.asList("col1", "col2"));

        assertEquals(2, columnIndices.size());
        assertEquals(Arrays.asList(1, 2), columnIndices);
    }

    @Test
    public void testRowExtractorCreateBy() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1L);
        when(mockResultSet.getString(2)).thenReturn("John");
        when(mockResultSet.getObject(3)).thenReturn(25);

        Jdbc.RowExtractor extractor = Jdbc.RowExtractor.forType(TestEntity.class);
        Object[] outputRow = new Object[3];

        extractor.accept(mockResultSet, outputRow);

        // Verify extracted values match the mock ResultSet data
        assertEquals(1L, outputRow[0]);
        assertEquals("John", outputRow[1]);
        assertEquals(25, outputRow[2]);
    }

    @Test
    public void testRowExtractorCreateBy_WithColumnLabels() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(3L);
        when(mockResultSet.getString(2)).thenReturn("Mia");

        List<String> columnLabels = Arrays.asList("id", "name");
        Jdbc.RowExtractor extractor = Jdbc.RowExtractor.forType(TestEntity.class, columnLabels);
        Object[] outputRow = new Object[2];

        extractor.accept(mockResultSet, outputRow);

        assertEquals(3L, outputRow[0]);
        assertEquals("Mia", outputRow[1]);
    }

    @Test
    public void testRowExtractorCreateBy_WithPrefixMap() throws SQLException {
        when(mockResultSet.getLong(1)).thenReturn(7L);
        when(mockResultSet.getString(2)).thenReturn("Ned");
        when(mockResultSet.getObject(3)).thenReturn(40);

        Jdbc.RowExtractor extractor = Jdbc.RowExtractor.forType(TestEntity.class, new HashMap<>());
        Object[] outputRow = new Object[3];

        extractor.accept(mockResultSet, outputRow);

        assertNotNull(extractor);
    }

    @Test
    public void testRowExtractorValidatesOutputSizeBeforeResolvingColumnMappings() throws SQLException {
        final Jdbc.RowExtractor extractor = Jdbc.RowExtractor.forType(TestEntity.class, Arrays.asList("id", null));

        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> extractor.accept(mockResultSet, new Object[1]));

        assertTrue(failure.getMessage().contains("output array length"));
        verifyNoInteractions(mockResultSet);
    }

    @Test
    public void testRowExtractorForTypeSnapshotsExplicitColumnLabels() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn("7");

        final List<String> columnLabels = new ArrayList<>(Arrays.asList("id"));
        final Jdbc.RowExtractor extractor = Jdbc.RowExtractor.forType(TestEntity.class, columnLabels);
        columnLabels.set(0, "notAProperty");

        final Object[] output = new Object[1];
        extractor.accept(mockResultSet, output);

        assertEquals(7L, output[0]);
    }

    @Test
    public void testRowExtractor_CreateBy_WithColumnLabels_UnmatchedColumns() throws SQLException {
        // Unmatched column labels → goes through column2FieldNameMap and checkPrefix fallback chain.
        when(mockResultSet.getObject(1)).thenReturn("fallback_value");

        List<String> columnLabels = Arrays.asList("unknown_col");
        Jdbc.RowExtractor extractor = Jdbc.RowExtractor.forType(TestEntity.class, columnLabels);
        Object[] outputRow = new Object[1];

        extractor.accept(mockResultSet, outputRow);
        // unknown_col has no match → JdbcUtil.getColumnValue falls back → rs.getObject(1)
        assertEquals("fallback_value", outputRow[0]);
    }

    @Test
    public void testRowExtractor_CreateBy_WithColumnLabels_CamelCaseField() throws SQLException {
        // "first_name" maps to "firstName" via column2FieldNameMap on CamelCaseEntity.
        when(mockResultSet.getString(1)).thenReturn("Carol");

        List<String> columnLabels = Arrays.asList("first_name");
        Jdbc.RowExtractor extractor = Jdbc.RowExtractor.forType(CamelCaseEntity.class, columnLabels);
        Object[] outputRow = new Object[1];

        extractor.accept(mockResultSet, outputRow);
        assertEquals("Carol", outputRow[0]);
    }

    // A null outputRow is rejected up front, before the extractor performs any ResultSet metadata
    // work on the first call (matching RowExtractorBuilder.build()'s validate-first ordering).
    @Test
    @Tag("2025")
    public void testRowExtractorCreateBy_NullOutputRow_RejectedBeforeMetadataAccess() throws SQLException {
        final Jdbc.RowExtractor extractor = Jdbc.RowExtractor.forType(TestEntity.class);

        assertThrows(IllegalArgumentException.class, () -> extractor.accept(mockResultSet, null));
        verify(mockResultSet, never()).getMetaData();
    }

    @Test
    public void testRowExtractorForTypeRejectsNullAndShortOutputBeforeWriting() throws SQLException {
        final Jdbc.RowExtractor extractor = Jdbc.RowExtractor.forType(TestEntity.class, Arrays.asList("id", "name"));
        final Object[] shortOutput = { "unchanged" };

        assertThrows(IllegalArgumentException.class, () -> extractor.accept(mockResultSet, shortOutput));
        assertEquals("unchanged", shortOutput[0]);
        assertThrows(IllegalArgumentException.class, () -> extractor.accept(mockResultSet, null));
    }

    // A failed first-row initialization must not leave a partially resolved type mapping behind that later
    // calls silently use: the 'id' column resolved before the null label failed, so the retry used to
    // "succeed" with that partial mapping instead of failing again.
    @Test
    public void testRowExtractorForTypeFailedInitializationIsNotCommitted() throws SQLException {
        final Jdbc.RowExtractor extractor = Jdbc.RowExtractor.forType(TestEntity.class, Arrays.asList("id", null));
        final Object[] output = new Object[2];

        assertThrows(RuntimeException.class, () -> extractor.accept(mockResultSet, output));
        assertThrows(RuntimeException.class, () -> extractor.accept(mockResultSet, output));
    }

    @Test
    public void testRowConsumerAccept() throws SQLException {
        List<String> names = new ArrayList<>();
        Jdbc.RowConsumer consumer = rs -> names.add(rs.getString("name"));

        when(mockResultSet.getString("name")).thenReturn("John");
        consumer.accept(mockResultSet);

        assertEquals(1, names.size());
        assertEquals("John", names.get(0));
    }

    @Test
    public void testRowConsumerAndThen() throws SQLException {
        List<String> results = new ArrayList<>();
        Jdbc.RowConsumer consumer1 = rs -> results.add("first");
        Jdbc.RowConsumer consumer2 = rs -> results.add("second");
        Jdbc.RowConsumer combined = consumer1.andThen(consumer2);

        combined.accept(mockResultSet);

        assertEquals(2, results.size());
        assertEquals("first", results.get(0));
        assertEquals("second", results.get(1));
    }

    @Test
    public void testRowConsumerToBiRowConsumer() throws SQLException {
        List<String> names = new ArrayList<>();
        Jdbc.RowConsumer consumer = rs -> names.add(rs.getString("name"));
        Jdbc.BiRowConsumer biConsumer = consumer.toBiRowConsumer();

        when(mockResultSet.getString("name")).thenReturn("John");
        biConsumer.accept(mockResultSet, Arrays.asList("name"));

        assertEquals(1, names.size());
        assertEquals("John", names.get(0));
    }

    // RowExtractor Tests
    @Test
    public void testRowExtractorAccept() throws SQLException {
        when(mockResultSet.getString(1)).thenReturn("John");
        when(mockResultSet.getInt(2)).thenReturn(25);

        Object[] outputRow = new Object[2];
        Jdbc.RowExtractor extractor = (rs, row) -> {
            row[0] = rs.getString(1);
            row[1] = rs.getInt(2);
        };

        extractor.accept(mockResultSet, outputRow);

        assertEquals("John", outputRow[0]);
        assertEquals(25, outputRow[1]);
    }

    @Test
    public void testRowFilterAnd() throws SQLException {
        when(mockResultSet.getInt("age")).thenReturn(20);
        when(mockResultSet.getBoolean("active")).thenReturn(true);

        Jdbc.RowFilter filter1 = rs -> rs.getInt("age") >= 18;
        Jdbc.RowFilter filter2 = rs -> rs.getBoolean("active");
        Jdbc.RowFilter combined = filter1.and(filter2);

        assertTrue(combined.test(mockResultSet));

        when(mockResultSet.getBoolean("active")).thenReturn(false);
        assertFalse(combined.test(mockResultSet));
    }

    @Test
    public void testRowFilterNegate() throws SQLException {
        when(mockResultSet.getBoolean("active")).thenReturn(true);

        Jdbc.RowFilter filter = rs -> rs.getBoolean("active");
        Jdbc.RowFilter negated = filter.negate();

        assertTrue(filter.test(mockResultSet));
        assertFalse(negated.test(mockResultSet));
    }

    @Test
    public void testRowFilterOr() throws SQLException {
        when(mockResultSet.getBoolean("active")).thenReturn(false);
        when(mockResultSet.getInt("age")).thenReturn(16);

        Jdbc.RowFilter filter1 = rs -> rs.getBoolean("active");
        Jdbc.RowFilter filter2 = rs -> rs.getInt("age") >= 18;
        Jdbc.RowFilter combined = filter1.or(filter2);

        assertFalse(combined.test(mockResultSet));

        when(mockResultSet.getBoolean("active")).thenReturn(true);
        assertTrue(combined.test(mockResultSet));
    }

    @Test
    public void testRowFilterTest() throws SQLException {
        when(mockResultSet.getInt("age")).thenReturn(20);

        Jdbc.RowFilter filter = rs -> rs.getInt("age") >= 18;
        assertTrue(filter.test(mockResultSet));

        when(mockResultSet.getInt("age")).thenReturn(15);
        assertFalse(filter.test(mockResultSet));
    }

    @Test
    public void testRowFilterToBiRowFilter() throws SQLException {
        when(mockResultSet.getInt("age")).thenReturn(20);

        Jdbc.RowFilter filter = rs -> rs.getInt("age") >= 18;
        Jdbc.BiRowFilter biFilter = filter.toBiRowFilter();

        assertTrue(biFilter.test(mockResultSet, Arrays.asList("age")));
    }

    // RowFilter Tests
    @Test
    public void testRowFilterAlwaysTrue() throws SQLException {
        assertTrue(Jdbc.RowFilter.ALWAYS_TRUE.test(mockResultSet));
    }

    @Test
    public void testRowFilterAlwaysFalse() throws SQLException {
        assertFalse(Jdbc.RowFilter.ALWAYS_FALSE.test(mockResultSet));
    }

    // BiRowFilter Tests
    @Test
    public void testBiRowFilterAlwaysTrue() throws SQLException {
        assertTrue(Jdbc.BiRowFilter.ALWAYS_TRUE.test(mockResultSet, Arrays.asList("col1")));
    }

    @Test
    public void testBiRowFilterAlwaysFalse() throws SQLException {
        assertFalse(Jdbc.BiRowFilter.ALWAYS_FALSE.test(mockResultSet, Arrays.asList("col1")));
    }
}
