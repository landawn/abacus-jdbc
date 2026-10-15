package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.IntFunctions;
import com.landawn.abacus.util.NoCachingNoUpdating.DisposableObjArray;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;

class JdbcRowMappingTest extends JdbcTestSupport {

    // BiRowMapper Tests
    @Test
    public void testBiRowMapperToArray() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1);
        when(mockResultSet.getObject(2)).thenReturn("John");
        when(mockResultSet.getObject(3)).thenReturn(25);

        List<String> columnLabels = Arrays.asList("id", "name", "age");
        Object[] result = Jdbc.BiRowMapper.TO_ARRAY.apply(mockResultSet, columnLabels);

        assertEquals(3, result.length);
        assertEquals(1, result[0]);
        assertEquals("John", result[1]);
        assertEquals(25, result[2]);
    }

    @Test
    public void testBiRowMapperToList() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1);
        when(mockResultSet.getObject(2)).thenReturn("John");
        when(mockResultSet.getObject(3)).thenReturn(25);

        List<String> columnLabels = Arrays.asList("id", "name", "age");
        List<Object> result = Jdbc.BiRowMapper.TO_LIST.apply(mockResultSet, columnLabels);

        assertEquals(3, result.size());
        assertEquals(1, result.get(0));
        assertEquals("John", result.get(1));
        assertEquals(25, result.get(2));
    }

    @Test
    public void testBiRowMapperToMap() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1);
        when(mockResultSet.getObject(2)).thenReturn("John");
        when(mockResultSet.getObject(3)).thenReturn(25);

        List<String> columnLabels = Arrays.asList("id", "name", "age");
        Map<String, Object> result = Jdbc.BiRowMapper.TO_MAP.apply(mockResultSet, columnLabels);

        assertEquals(3, result.size());
        assertEquals(1, result.get("id"));
        assertEquals("John", result.get("name"));
        assertEquals(25, result.get("age"));
    }

    @Test
    public void testBiRowMapperToLinkedHashMap() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1);
        when(mockResultSet.getObject(2)).thenReturn("John");

        List<String> columnLabels = Arrays.asList("id", "name");
        Map<String, Object> result = Jdbc.BiRowMapper.TO_LINKED_HASH_MAP.apply(mockResultSet, columnLabels);

        assertTrue(result instanceof LinkedHashMap);
        assertEquals(2, result.size());
    }

    @Test
    public void testBiRowMapperToEntityId() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1);
        when(mockResultSet.getObject(2)).thenReturn("John");

        List<String> columnLabels = Arrays.asList("id", "name");
        EntityId result = Jdbc.BiRowMapper.TO_ENTITY_ID.apply(mockResultSet, columnLabels);

        assertNotNull(result);
        assertEquals(1, (Integer) result.get("id"));
        assertEquals("John", result.get("name"));
    }

    @Test
    public void testBiRowMapperAndThen() throws SQLException {
        Jdbc.BiRowMapper<String> mapper = (rs, cols) -> "test";
        Jdbc.BiRowMapper<Integer> composed = mapper.andThen(String::length);
        assertEquals(4, composed.apply(mockResultSet, Arrays.asList("col1")));
    }

    // deprecated BiRowMapper.toRowMapper() - lines 2760-2774
    @Test
    public void testBiRowMapperToRowMapper() throws SQLException {
        when(mockResultSet.getString("name")).thenReturn("Alice");

        Jdbc.BiRowMapper<String> biMapper = (rs, cols) -> rs.getString("name");
        @SuppressWarnings("deprecation")
        Jdbc.RowMapper<String> rowMapper = biMapper.toRowMapper();

        // first call initializes the column label list
        assertEquals("Alice", rowMapper.apply(mockResultSet));
        // second call uses cached column labels
        assertEquals("Alice", rowMapper.apply(mockResultSet));
    }

    @Test
    public void testRowMapperBuilder() throws SQLException {
        when(mockResultSet.getInt(1)).thenReturn(1);
        when(mockResultSet.getString(2)).thenReturn("John");
        when(mockResultSet.getInt(3)).thenReturn(25);

        Jdbc.RowMapper<Object[]> mapper = Jdbc.RowMapper.builder().getInt(1).getString(2).getInt(3).toArray();

        Object[] result = mapper.apply(mockResultSet);
        assertEquals(3, result.length);
        assertEquals(1, result[0]);
        assertEquals("John", result[1]);
        assertEquals(25, result[2]);
    }

    @Test
    public void testRowMapperBuilderToList() throws SQLException {
        when(mockResultSet.getBoolean(1)).thenReturn(true);
        when(mockResultSet.getDouble(2)).thenReturn(3.14);
        when(mockResultSet.getTimestamp(3)).thenReturn(new Timestamp(System.currentTimeMillis()));

        Jdbc.RowMapper<List<Object>> mapper = Jdbc.RowMapper.builder().getBoolean(1).getDouble(2).getTimestamp(3).toList();

        List<Object> result = mapper.apply(mockResultSet);
        assertEquals(3, result.size());
        assertEquals(true, result.get(0));
        assertEquals(3.14, result.get(1));
        assertNotNull(result.get(2));
    }

    @Test
    public void testRowMapperBuilderToMap() throws SQLException {
        when(mockResultSet.getInt(1)).thenReturn(1);
        when(mockResultSet.getString(2)).thenReturn("John");
        when(mockResultSet.getInt(3)).thenReturn(25);

        Jdbc.RowMapper<Map<String, Object>> mapper = Jdbc.RowMapper.builder().getInt(1).getString(2).getInt(3).toMap();

        Map<String, Object> result = mapper.apply(mockResultSet);
        assertEquals(3, result.size());
        assertEquals(1, result.get("id"));
        assertEquals("John", result.get("name"));
        assertEquals(25, result.get("age"));
    }

    @Test
    public void testRowMapperBuilderTo() throws SQLException {
        when(mockResultSet.getInt(1)).thenReturn(1);
        when(mockResultSet.getString(2)).thenReturn("John");

        Jdbc.RowMapper<String> mapper = Jdbc.RowMapper.builder().getInt(1).getString(2).to(arr -> arr.get(0) + "-" + arr.get(1));

        String result = mapper.apply(mockResultSet);
        assertEquals("1-John", result);
    }

    @Test
    public void testRowMapperBuilderSnapshotsConfigurationWhenBuilt() throws SQLException {
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSet.getInt(1)).thenReturn(42);
        when(mockResultSet.getString(1)).thenReturn("changed");

        final Jdbc.RowMapper.RowMapperBuilder builder = Jdbc.RowMapper.builder().getInt(1);
        final Jdbc.RowMapper<Object[]> mapper = builder.toArray();
        builder.getString(1);

        assertEquals(42, mapper.apply(mockResultSet)[0]);
    }

    @Test
    public void testBiRowMapperBuilder() throws SQLException {
        when(mockResultSet.getInt(1)).thenReturn(1);
        when(mockResultSet.getString(2)).thenReturn("John");
        when(mockResultSet.getInt(3)).thenReturn(20);

        Jdbc.BiRowMapper<TestEntity> mapper = Jdbc.BiRowMapper.builder().getInt("id").getString("name").getInt("age").to(TestEntity.class);

        List<String> columnLabels = Arrays.asList("id", "name", "age");
        TestEntity result = mapper.apply(mockResultSet, columnLabels);
        assertNotNull(result);
        // Verify the builder-constructed mapper populated entity fields correctly
        assertEquals(1L, result.getId().longValue());
        assertEquals("John", result.getName());
        assertEquals(Integer.valueOf(20), result.getAge());
    }

    @Test
    public void testBiRowMapperBuilderSnapshotsConfigurationWhenBuilt() throws SQLException {
        when(mockResultSet.getInt(1)).thenReturn(42);
        when(mockResultSet.getString(1)).thenReturn("changed");

        final Jdbc.BiRowMapper.BiRowMapperBuilder builder = Jdbc.BiRowMapper.builder().getInt("value");
        final Jdbc.BiRowMapper<Object[]> mapper = builder.to(Object[].class);
        builder.getString("value");

        assertEquals(42, mapper.apply(mockResultSet, Arrays.asList("value"))[0]);
    }

    @Test
    public void testBiRowMapperTypedArraysUseComponentTypeConversion() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(42);
        when(mockResultSet.getString(1)).thenReturn("42");

        final String[] direct = Jdbc.BiRowMapper.to(String[].class).apply(mockResultSet, Arrays.asList("value"));
        final String[] built = Jdbc.BiRowMapper.builder().to(String[].class).apply(mockResultSet, Arrays.asList("value"));

        assertEquals("42", direct[0]);
        assertEquals("42", built[0]);
    }

    @Test
    public void testBiRowMapperBuilder_ToEntity_WithDefaultGetter() throws SQLException {
        // Uses default GET_OBJECT getter → triggers rsColumnGetters[i] = ColumnGetter.forType(propType) path
        when(mockResultSet.getObject(1)).thenReturn(5L);
        when(mockResultSet.getObject(2)).thenReturn("Leo");
        when(mockResultSet.getObject(3)).thenReturn(30);

        Jdbc.BiRowMapper<TestEntity> mapper = Jdbc.BiRowMapper.builder().to(TestEntity.class);
        List<String> columnLabels = Arrays.asList("id", "name", "age");

        TestEntity result = mapper.apply(mockResultSet, columnLabels);
        assertNotNull(result);
    }

    @Test
    public void testBiRowMapperBuilder_ToEntity_UnmatchedIgnored() throws SQLException {
        // Unmatched column with ignoreUnmatchedColumns=true → columnLabels[i]=null then continue
        when(mockResultSet.getObject(1)).thenReturn(6L);
        when(mockResultSet.getObject(3)).thenReturn(25);

        Jdbc.BiRowMapper<TestEntity> mapper = Jdbc.BiRowMapper.builder().to(TestEntity.class, true);
        List<String> columnLabels = Arrays.asList("id", "unknown_col", "age");

        TestEntity result = mapper.apply(mockResultSet, columnLabels);
        assertNotNull(result);
        assertNull(result.getName()); // unknown_col skipped
    }

    @Test
    public void testBiRowMapperBuilder_ToScalarType() throws SQLException {
        // Non-array/list/map/bean type → single-column scalar path
        when(mockResultSet.getString(1)).thenReturn("scalar_value");

        Jdbc.BiRowMapper<String> mapper = Jdbc.BiRowMapper.builder().to(String.class);
        List<String> columnLabels = Arrays.asList("value");

        String result = mapper.apply(mockResultSet, columnLabels);
        assertEquals("scalar_value", result);
    }

    @Test
    public void testBiRowMapperBuilder_ToList() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1);
        when(mockResultSet.getObject(2)).thenReturn("Ivy");
        when(mockResultSet.getObject(3)).thenReturn(22);

        Jdbc.BiRowMapper<List> mapper = Jdbc.BiRowMapper.builder().to(List.class);
        List<String> columnLabels = Arrays.asList("id", "name", "age");

        List result = mapper.apply(mockResultSet, columnLabels);
        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals(1, result.get(0));
        assertEquals("Ivy", result.get(1));
    }

    @Test
    public void testBiRowMapperBuilder_ToMap() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(7);
        when(mockResultSet.getObject(2)).thenReturn("Jack");
        when(mockResultSet.getObject(3)).thenReturn(35);

        Jdbc.BiRowMapper<Map> mapper = Jdbc.BiRowMapper.builder().to(Map.class);
        List<String> columnLabels = Arrays.asList("id", "name", "age");

        Map result = mapper.apply(mockResultSet, columnLabels);
        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals(7, result.get("id"));
        assertEquals("Jack", result.get("name"));
    }

    @Test
    public void testRowMapperAllBuilderMethods() throws SQLException {
        // Test all builder methods
        when(mockResultSet.getBoolean(1)).thenReturn(true);
        when(mockResultSet.getByte(2)).thenReturn((byte) 10);
        when(mockResultSet.getShort(3)).thenReturn((short) 100);
        when(mockResultSet.getFloat(4)).thenReturn(1.5f);
        when(mockResultSet.getBigDecimal(5)).thenReturn(new BigDecimal("123.45"));
        when(mockResultSet.getDate(6)).thenReturn(new Date(System.currentTimeMillis()));
        when(mockResultSet.getTime(7)).thenReturn(new Time(System.currentTimeMillis()));
        when(mockResultSet.getObject(8)).thenReturn("object");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(8);

        Jdbc.RowMapper<Object[]> mapper = Jdbc.RowMapper.builder()
                .getBoolean(1)
                .getByte(2)
                .getShort(3)
                .getFloat(4)
                .getBigDecimal(5)
                .getDate(6)
                .getTime(7)
                .getObject(8)
                .toArray();

        Object[] result = mapper.apply(mockResultSet);
        assertEquals(8, result.length);
        assertEquals(true, result[0]);
        assertEquals((byte) 10, result[1]);
        assertEquals((short) 100, result[2]);
        assertEquals(1.5f, result[3]);
        assertEquals(new BigDecimal("123.45"), result[4]);
        assertNotNull(result[5]); // Date
        assertNotNull(result[6]); // Time
        assertEquals("object", result[7]);
    }

    @Test
    public void testBiRowMapperAllBuilderMethods() throws SQLException {
        when(mockResultSet.getBoolean(1)).thenReturn(false);
        when(mockResultSet.getByte(2)).thenReturn((byte) 20);
        when(mockResultSet.getShort(3)).thenReturn((short) 200);
        when(mockResultSet.getInt(4)).thenReturn(400);
        when(mockResultSet.getLong(5)).thenReturn(500L);
        when(mockResultSet.getFloat(6)).thenReturn(2.5f);
        when(mockResultSet.getDouble(7)).thenReturn(3.14);
        when(mockResultSet.getBigDecimal(8)).thenReturn(new BigDecimal("456.78"));
        when(mockResultSet.getString(9)).thenReturn("builder");
        Date date = new Date(System.currentTimeMillis());
        Time time = new Time(System.currentTimeMillis());
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());
        when(mockResultSet.getDate(10)).thenReturn(date);
        when(mockResultSet.getTime(11)).thenReturn(time);
        when(mockResultSet.getTimestamp(12)).thenReturn(timestamp);
        when(mockResultSet.getObject(13)).thenReturn("object");
        when(mockResultSet.getString(14)).thenReturn("typed");

        Jdbc.BiRowMapper.BiRowMapperBuilder builder = Jdbc.BiRowMapper.builder();

        Jdbc.BiRowMapper<Object[]> mapper = builder.getBoolean("active")
                .getByte("byteVal")
                .getShort("shortVal")
                .getInt("intVal")
                .getLong("longVal")
                .getFloat("floatVal")
                .getDouble("doubleVal")
                .getBigDecimal("decimal")
                .getString("name")
                .getDate("date")
                .getTime("time")
                .getTimestamp("timestamp")
                .getObject("object")
                .getObject("typed", String.class)
                .to(Object[].class);

        Object[] result = mapper.apply(mockResultSet, Arrays.asList("active", "byteVal", "shortVal", "intVal", "longVal", "floatVal", "doubleVal", "decimal",
                "name", "date", "time", "timestamp", "object", "typed"));

        assertEquals(false, result[0]);
        assertEquals((byte) 20, result[1]);
        assertEquals((short) 200, result[2]);
        assertEquals(400, result[3]);
        assertEquals(500L, result[4]);
        assertEquals(2.5f, result[5]);
        assertEquals(3.14, result[6]);
        assertEquals(new BigDecimal("456.78"), result[7]);
        assertEquals("builder", result[8]);
        assertEquals(date, result[9]);
        assertEquals(time, result[10]);
        assertEquals(timestamp, result[11]);
        assertEquals("object", result[12]);
        assertEquals("typed", result[13]);
    }

    // RowMapper.builder().getLong(int) - line 2167: getLong configures column 1 to use GET_LONG
    @Test
    public void testRowMapperBuilder_GetLongByIndex() throws SQLException {
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSet.getLong(1)).thenReturn(42L);

        Jdbc.RowMapper<Object[]> mapper = Jdbc.RowMapper.builder().getLong(1).toArray();
        Object[] result = mapper.apply(mockResultSet);

        assertEquals(1, result.length);
        assertEquals(42L, result[0]);
    }

    @Test
    public void testRowMapperBuilder_GetObjectByIndexAndType() throws SQLException {
        when(mockResultSet.getString(1)).thenReturn("hello");
        when(mockResultSet.getObject(2)).thenReturn(null);
        when(mockResultSet.getObject(3)).thenReturn(null);

        Jdbc.RowMapper<Object[]> mapper = Jdbc.RowMapper.builder().getObject(1, String.class).toArray();
        Object[] result = mapper.apply(mockResultSet);

        assertEquals(3, result.length);
        assertEquals("hello", result[0]);
    }

    @Test
    public void testRowMapperBuilder_ToFinisher() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1L);
        when(mockResultSet.getObject(2)).thenReturn("Alice");
        when(mockResultSet.getObject(3)).thenReturn(30);

        Jdbc.RowMapper<String> mapper = Jdbc.RowMapper.builder().to((columnLabels, row) -> columnLabels.get(1) + "=" + row.get(1));

        String result = mapper.apply(mockResultSet);
        assertEquals("name=Alice", result);
    }

    // Bug: BiRowMapperBuilder.initColumnGetter undercounted missing configured columns when
    // a duplicate ResultSet column matched the same configured key twice — so the misuse went
    // undetected and downstream code silently dropped the unmatched configuration.
    @Test
    public void testBiRowMapperBuilder_DuplicateRsColumnHidesMissingConfig() {
        Jdbc.BiRowMapper<List> mapper = Jdbc.BiRowMapper.builder().getInt("col1").getString("col2").to(List.class);

        // RS has [col1, col1] — col2 is configured but missing from the result set.
        // Must throw IAE pointing at col2, not silently succeed.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> mapper.apply(mockResultSet, Arrays.asList("col1", "col1")));
        assertTrue(ex.getMessage().contains("col2"), "Expected message to mention col2: " + ex.getMessage());
    }

    @Test
    public void testBiRowMapperBuilderTo_RecordClassMapsColumns() throws SQLException {
        when(mockResultSet.getString(1)).thenReturn("Grace");
        when(mockResultSet.getString(2)).thenReturn("Hopper");

        final Jdbc.BiRowMapper<NameRecord> mapper = Jdbc.BiRowMapper.builder().getString("firstName").getString("lastName").to(NameRecord.class);
        final NameRecord result = mapper.apply(mockResultSet, Arrays.asList("firstName", "lastName"));

        assertEquals(new NameRecord("Grace", "Hopper"), result);
    }

    @Test
    public void testBiRowMapperRepeatedlyRejectsUnmatchedColumns() throws SQLException {
        final List<Jdbc.BiRowMapper<TestEntity>> mappers = List.of(Jdbc.BiRowMapper.to(TestEntity.class),
                Jdbc.BiRowMapper.to(TestEntity.class, Map.of("unused", "name")), Jdbc.BiRowMapper.builder().to(TestEntity.class));
        for (final Jdbc.BiRowMapper<TestEntity> mapper : mappers) {
            final IllegalArgumentException first = assertThrows(IllegalArgumentException.class, () -> mapper.apply(mockResultSet, List.of("unmatched")));
            final IllegalArgumentException second = assertThrows(IllegalArgumentException.class, () -> mapper.apply(mockResultSet, List.of("unmatched")));
            assertEquals(first.getMessage(), second.getMessage());
        }
        verify(mockResultSet, never()).getObject(anyInt());
    }

    @Test
    public void testRowMapperBuilderRejectsNullFinishers() {
        assertThrows(IllegalArgumentException.class, () -> Jdbc.RowMapper.builder().to((Throwables.Function<DisposableObjArray, Object, SQLException>) null));
        assertThrows(IllegalArgumentException.class,
                () -> Jdbc.RowMapper.builder().to((Throwables.BiFunction<List<String>, DisposableObjArray, Object, SQLException>) null));
    }

    @Test
    public void testBiRowMapperBuilderWithNullDefaultGetter() {
        assertThrows(IllegalArgumentException.class, () -> Jdbc.BiRowMapper.builder(null));
    }

    // Bug: BiRowMapperBuilder.to(scalarClass) crashed with AIOBE on a 0-column ResultSet
    // (rsColumnGetters[0] was indexed before the multi-column guard fired).
    @Test
    public void testBiRowMapperBuilder_ToScalarType_EmptyColumnsThrowsIAE() {
        Jdbc.BiRowMapper<String> mapper = Jdbc.BiRowMapper.builder().to(String.class);

        Throwable thrown = assertThrows(Throwable.class, () -> mapper.apply(mockResultSet, new ArrayList<>()));
        // Must be the "multiple columns" IAE, not an ArrayIndexOutOfBoundsException.
        assertTrue(thrown instanceof IllegalArgumentException, "Expected IllegalArgumentException but got: " + thrown);
    }

    @Test
    public void testBiRowMapperBuilder_ToNullTargetClassThrowsIAE() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> Jdbc.BiRowMapper.builder().to(null));
        assertTrue(ex.getMessage().contains("targetClass"));
    }

    @Test
    public void testBiRowMapperBuilder_ToEntity_UnmatchedThrows() {
        // Unmatched column with ignoreUnmatchedColumns=false → throws IllegalArgumentException
        Jdbc.BiRowMapper<TestEntity> mapper = Jdbc.BiRowMapper.builder().to(TestEntity.class, false);
        List<String> columnLabels = Arrays.asList("unknown_col");

        assertThrows(IllegalArgumentException.class, () -> mapper.apply(mockResultSet, columnLabels));
    }

    @Test
    public void testBiRowMapperBuilder_ToScalarType_MultiColumnThrows() throws SQLException {
        // Scalar type with multiple columns → throws
        Jdbc.BiRowMapper<String> mapper = Jdbc.BiRowMapper.builder().to(String.class);

        assertThrows(IllegalArgumentException.class, () -> mapper.apply(mockResultSet, Arrays.asList("col1", "col2")));
    }

    @Test
    public void testBiRowMapperBuilder_UnknownColumnThrows() throws SQLException {
        Jdbc.BiRowMapper<TestEntity> mapper = Jdbc.BiRowMapper.builder().getString("nonexistent").to(TestEntity.class);

        assertThrows(IllegalArgumentException.class, () -> mapper.apply(mockResultSet, Arrays.asList("id", "name", "age")));
    }

    @Test
    public void testRowMapperBuilder_ConfiguredIndexBeyondColumnCountThrowsIAE() throws SQLException {
        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);

        Jdbc.RowMapper<Object[]> mapper = Jdbc.RowMapper.builder().getInt(3).toArray();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> mapper.apply(mockResultSet));
        assertTrue(ex.getMessage().contains("3"), "Expected message to mention missing configured index 3: " + ex.getMessage());
    }

    @Test
    public void testRowMapperCombine2() throws SQLException {
        when(mockResultSet.getInt("id")).thenReturn(1);
        when(mockResultSet.getString("name")).thenReturn("John");

        Jdbc.RowMapper<Integer> firstMapper = rs -> rs.getInt("id");
        Jdbc.RowMapper<String> secondMapper = rs -> rs.getString("name");
        Jdbc.RowMapper<Tuple2<Integer, String>> combined = Jdbc.RowMapper.combine(firstMapper, secondMapper);

        Tuple2<Integer, String> result = combined.apply(mockResultSet);
        assertEquals(1, result._1);
        assertEquals("John", result._2);
    }

    @Test
    public void testRowMapperCombine3() throws SQLException {
        when(mockResultSet.getInt("id")).thenReturn(1);
        when(mockResultSet.getString("name")).thenReturn("John");
        when(mockResultSet.getInt("age")).thenReturn(25);

        Jdbc.RowMapper<Integer> firstMapper = rs -> rs.getInt("id");
        Jdbc.RowMapper<String> secondMapper = rs -> rs.getString("name");
        Jdbc.RowMapper<Integer> thirdMapper = rs -> rs.getInt("age");
        Jdbc.RowMapper<Tuple3<Integer, String, Integer>> combined = Jdbc.RowMapper.combine(firstMapper, secondMapper, thirdMapper);

        Tuple3<Integer, String, Integer> result = combined.apply(mockResultSet);
        assertEquals(1, result._1);
        assertEquals("John", result._2);
        assertEquals(25, result._3);
    }

    @Test
    public void testBiRowMapperCombine2() throws SQLException {
        when(mockResultSet.getInt(1)).thenReturn(1);
        when(mockResultSet.getString(2)).thenReturn("John");

        Jdbc.BiRowMapper<Integer> firstMapper = (rs, cols) -> rs.getInt(1);
        Jdbc.BiRowMapper<String> secondMapper = (rs, cols) -> rs.getString(2);
        Jdbc.BiRowMapper<Tuple2<Integer, String>> combined = Jdbc.BiRowMapper.combine(firstMapper, secondMapper);

        List<String> columnLabels = Arrays.asList("id", "name");
        Tuple2<Integer, String> result = combined.apply(mockResultSet, columnLabels);
        assertEquals(1, result._1);
        assertEquals("John", result._2);
    }

    @Test
    public void testBiRowMapperCombine3() throws SQLException {
        when(mockResultSet.getInt(1)).thenReturn(1);
        when(mockResultSet.getString(2)).thenReturn("John");
        when(mockResultSet.getInt(3)).thenReturn(25);

        Jdbc.BiRowMapper<Integer> firstMapper = (rs, cols) -> rs.getInt(1);
        Jdbc.BiRowMapper<String> secondMapper = (rs, cols) -> rs.getString(2);
        Jdbc.BiRowMapper<Integer> thirdMapper = (rs, cols) -> rs.getInt(3);
        Jdbc.BiRowMapper<Tuple3<Integer, String, Integer>> combined = Jdbc.BiRowMapper.combine(firstMapper, secondMapper, thirdMapper);

        List<String> columnLabels = Arrays.asList("id", "name", "age");
        Tuple3<Integer, String, Integer> result = combined.apply(mockResultSet, columnLabels);
        assertEquals(1, result._1);
        assertEquals("John", result._2);
        assertEquals(25, result._3);
    }

    @Test
    public void testRowMapperAndThen() throws SQLException {
        when(mockResultSet.getString("name")).thenReturn("John");

        Jdbc.RowMapper<String> mapper = rs -> rs.getString("name");
        Jdbc.RowMapper<Integer> composed = mapper.andThen(String::length);
        assertEquals(4, composed.apply(mockResultSet));
    }

    // RowMapper Tests
    @Test
    public void testRowMapperApply() throws SQLException {
        when(mockResultSet.getString("name")).thenReturn("John");

        Jdbc.RowMapper<String> mapper = rs -> rs.getString("name");
        assertEquals("John", mapper.apply(mockResultSet));
    }

    @Test
    public void testRowMapperToBiRowMapper() throws SQLException {
        when(mockResultSet.getString("name")).thenReturn("John");

        Jdbc.RowMapper<String> mapper = rs -> rs.getString("name");
        Jdbc.BiRowMapper<String> biMapper = mapper.toBiRowMapper();
        assertEquals("John", biMapper.apply(mockResultSet, Arrays.asList("name")));
    }

    @Test
    public void testBiRowMapperTo() throws SQLException {
        when(mockResultSet.getLong(1)).thenReturn(1L);
        when(mockResultSet.getString(2)).thenReturn("John");
        when(mockResultSet.getInt(3)).thenReturn(25);
        // BiRowMapper.to() internally uses getObject to read column values
        when(mockResultSet.getObject(1)).thenReturn(1L);
        when(mockResultSet.getObject(2)).thenReturn("John");
        when(mockResultSet.getObject(3)).thenReturn(25);

        Jdbc.BiRowMapper<TestEntity> mapper = Jdbc.BiRowMapper.to(TestEntity.class);
        List<String> columnLabels = Arrays.asList("id", "name", "age");

        TestEntity result = mapper.apply(mockResultSet, columnLabels);
        assertNotNull(result);
        // Verify the mapper populated the entity fields from the ResultSet
        assertEquals(Long.valueOf(1L), result.getId());
        assertEquals("John", result.getName());
        assertEquals(Integer.valueOf(25), result.getAge());
    }

    @Test
    public void testBiRowMapperToWithIgnoreNocountMatchBetweenedColumns() throws SQLException {
        when(mockResultSet.getLong(1)).thenReturn(1L);
        when(mockResultSet.getString(2)).thenReturn("John");
        when(mockResultSet.getInt(3)).thenReturn(25);
        when(mockResultSet.getString(4)).thenReturn("extra");
        // BiRowMapper.to() internally uses getObject to read column values
        when(mockResultSet.getObject(1)).thenReturn(1L);
        when(mockResultSet.getObject(2)).thenReturn("John");
        when(mockResultSet.getObject(3)).thenReturn(25);
        when(mockResultSet.getObject(4)).thenReturn("extra");

        Jdbc.BiRowMapper<TestEntity> mapper = Jdbc.BiRowMapper.to(TestEntity.class, true);
        List<String> columnLabels = Arrays.asList("id", "name", "age", "extra_column");

        when(mockResultSetMetaData.getColumnCount()).thenReturn(4);
        when(mockResultSetMetaData.getColumnLabel(4)).thenReturn("extra_column");

        TestEntity result = mapper.apply(mockResultSet, columnLabels);
        assertNotNull(result);
        // Verify known fields are mapped despite extra unmatched column
        assertEquals(Long.valueOf(1L), result.getId());
        assertEquals("John", result.getName());
        assertEquals(Integer.valueOf(25), result.getAge());
    }

    @Test
    public void testBiRowMapper_ToWithColumnFilterAndConverter() throws SQLException {
        when(mockResultSet.getString(2)).thenReturn("Carol");

        Jdbc.BiRowMapper<TestEntity> mapper = Jdbc.BiRowMapper.to(TestEntity.class, col -> col.equals("name"), col -> col);

        List<String> columnLabels = Arrays.asList("id", "name", "age");
        TestEntity result = mapper.apply(mockResultSet, columnLabels);

        assertNotNull(result);
        assertEquals("Carol", result.getName());
        assertNull(result.getId());
        assertNull(result.getAge());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBiRowMapper_ToObjectArray_WithFilter() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1L);
        when(mockResultSet.getObject(2)).thenReturn("Diana");
        when(mockResultSet.getObject(3)).thenReturn(25);

        // filter: only include "name" column; converter: identity
        Jdbc.BiRowMapper<Object[]> mapper = Jdbc.BiRowMapper.to(Object[].class, col -> col.equals("name"), col -> col, false);

        List<String> columnLabels = Arrays.asList("id", "name", "age");
        Object[] result = mapper.apply(mockResultSet, columnLabels);

        assertNotNull(result);
        assertEquals(3, result.length);
        assertNull(result[0]); // id filtered out
        assertEquals("Diana", result[1]);
        assertNull(result[2]); // age filtered out
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBiRowMapper_ToList_WithFilter() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(2L);
        when(mockResultSet.getObject(2)).thenReturn("Eric");
        when(mockResultSet.getObject(3)).thenReturn(30);

        // filter: only "id" and "name"; converter: identity
        Jdbc.BiRowMapper<List> mapper = Jdbc.BiRowMapper.to(List.class, col -> !col.equals("age"), col -> col, false);

        List<String> columnLabels = Arrays.asList("id", "name", "age");
        List result = mapper.apply(mockResultSet, columnLabels);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(2L, result.get(0));
        assertEquals("Eric", result.get(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBiRowMapper_ToMap_WithFilter() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(3L);
        when(mockResultSet.getObject(2)).thenReturn("Fiona");
        when(mockResultSet.getObject(3)).thenReturn(28);

        // filter: only "name"; converter: uppercase
        Jdbc.BiRowMapper<Map> mapper = Jdbc.BiRowMapper.to(Map.class, col -> col.equals("name"), col -> col.toUpperCase(), false);

        List<String> columnLabels = Arrays.asList("id", "name", "age");
        Map result = mapper.apply(mockResultSet, columnLabels);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("Fiona", result.get("NAME"));
    }

    @Test
    public void testBiRowMapperToPrefixMapSnapshotsConfiguration() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn("9");

        final Map<String, String> prefixMap = new HashMap<>();
        prefixMap.put("u", "user");
        final Jdbc.BiRowMapper<NestedEntity> mapper = Jdbc.BiRowMapper.to(NestedEntity.class, prefixMap);
        prefixMap.put("u", "missing");

        final NestedEntity result = mapper.apply(mockResultSet, Arrays.asList("u.id"));

        assertEquals(9L, result.getUser().getId());
    }

    @Test
    public void testBiRowMapper_ToPrefixMap_UnmatchedColumn_IgnoreUnmatched() throws SQLException {
        // Columns that don't match any property → columnLabels[i] = null → continue in row loop
        when(mockResultSet.getObject(1)).thenReturn(1L);
        when(mockResultSet.getObject(2)).thenReturn("Test");
        when(mockResultSet.getObject(3)).thenReturn(null);

        Map<String, String> prefixMap = new HashMap<>();
        prefixMap.put("u_", "");

        // non-empty prefix map, ignoreUnmatchedColumns=true, all columns unmatched
        Jdbc.BiRowMapper<TestEntity> mapper = Jdbc.BiRowMapper.to(TestEntity.class, prefixMap, true);
        List<String> columnLabels = Arrays.asList("u_id", "u_name", "u_age");

        TestEntity result = mapper.apply(mockResultSet, columnLabels);
        // all columns were unmatched/null → entity has no fields set
        assertNotNull(result);
        assertNull(result.getId());
        assertNull(result.getName());
        assertNull(result.getAge());
    }

    @Test
    public void testBiRowMapper_ToPrefixMap_MixedColumns_DirectAndUnmatched() throws SQLException {
        // Some columns match directly, some are unmatched (ignoreUnmatched=true)
        when(mockResultSet.getObject(1)).thenReturn(7L);
        when(mockResultSet.getString(2)).thenReturn("Mixed");

        Map<String, String> prefixMap = new HashMap<>();
        prefixMap.put("x_", "");

        Jdbc.BiRowMapper<TestEntity> mapper = Jdbc.BiRowMapper.to(TestEntity.class, prefixMap, true);
        // "id" and "name" match directly; "x_unknown" is unmatched
        List<String> columnLabels = Arrays.asList("id", "name", "x_unknown");

        TestEntity result = mapper.apply(mockResultSet, columnLabels);
        assertNotNull(result);
        assertEquals(7L, result.getId());
        assertEquals("Mixed", result.getName());
        assertNull(result.getAge());
    }

    @Test
    public void testBiRowMapper_ToPrefixMap_Column2FieldNameMap() throws SQLException {
        // "first_name" doesn't directly match prop "firstName", but column2FieldNameMap maps it.
        // Using a non-empty prefix map forces the prefix-map code path (lines 3279-3365).
        when(mockResultSet.getString(1)).thenReturn("Alice");

        Map<String, String> prefixMap = new HashMap<>();
        prefixMap.put("unused_", ""); // non-empty, triggers prefix-map branch

        Jdbc.BiRowMapper<CamelCaseEntity> mapper = Jdbc.BiRowMapper.to(CamelCaseEntity.class, prefixMap, false);
        List<String> columnLabels = Arrays.asList("first_name");

        CamelCaseEntity result = mapper.apply(mockResultSet, columnLabels);
        assertNotNull(result);
        assertEquals("Alice", result.getFirstName());
    }

    @Test
    public void testBiRowMapper_ToPrefixMap_Column2FieldNameMapIgnoreUnmatched() throws SQLException {
        // Column labels with both a directly-matched prop and a column2FieldNameMap-matched prop.
        when(mockResultSet.getString(1)).thenReturn("Bob");
        when(mockResultSet.getObject(2)).thenReturn(99);

        Map<String, String> prefixMap = new HashMap<>();
        prefixMap.put("x_", "");

        Jdbc.BiRowMapper<CamelCaseEntity> mapper = Jdbc.BiRowMapper.to(CamelCaseEntity.class, prefixMap, true);
        List<String> columnLabels = Arrays.asList("first_name", "total_count");

        CamelCaseEntity result = mapper.apply(mockResultSet, columnLabels);
        assertNotNull(result);
        assertEquals("Bob", result.getFirstName());
    }

    @Test
    public void testBiRowMapper_To4Arg_EntityClass_Column2FieldNameMap() throws SQLException {
        // 4-arg BiRowMapper.to(entityClass, filter, converter, ignoreUnmatched) with entity path.
        // column "first_name" doesn't directly match "firstName", but column2FieldNameMap maps it.
        when(mockResultSet.getString(1)).thenReturn("Dave");

        Jdbc.BiRowMapper<CamelCaseEntity> mapper = Jdbc.BiRowMapper.to(CamelCaseEntity.class, null, null, false);
        List<String> columnLabels = Arrays.asList("first_name");

        CamelCaseEntity result = mapper.apply(mockResultSet, columnLabels);
        assertNotNull(result);
        assertEquals("Dave", result.getFirstName());
    }

    // Static BiRowMapper.to(class, filter, converter, ignoreUnmatchedColumns=true): a non-trivial
    // filter/converter routes mapping through the full bean block, and an unmatched column is nulled
    // out and skipped rather than throwing (Jdbc L3197).
    @Test
    public void testBiRowMapperTo_FilterConverter_UnmatchedIgnored() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(6L);
        when(mockResultSet.getObject(3)).thenReturn(25);

        final java.util.function.Predicate<String> filter = s -> true;
        final java.util.function.Function<String, String> converter = s -> s;
        final Jdbc.BiRowMapper<TestEntity> mapper = Jdbc.BiRowMapper.to(TestEntity.class, filter, converter, true);

        final TestEntity result = mapper.apply(mockResultSet, Arrays.asList("id", "unknown_col", "age"));

        assertNotNull(result);
        assertNull(result.getName()); // unknown_col was skipped, leaving name unset
    }

    @Test
    public void testBiRowMapperTo_RecordClassMapsColumns() throws SQLException {
        when(mockResultSet.getString(1)).thenReturn("Ada");
        when(mockResultSet.getString(2)).thenReturn("Lovelace");

        final Jdbc.BiRowMapper<NameRecord> mapper = Jdbc.BiRowMapper.to(NameRecord.class);
        final NameRecord result = mapper.apply(mockResultSet, Arrays.asList("firstName", "lastName"));

        assertEquals(new NameRecord("Ada", "Lovelace"), result);
    }

    @Test
    public void testBiRowMapperToBeanWithNullReturningConverter() throws SQLException {
        when(mockResultSet.getLong(1)).thenReturn(1L);
        when(mockResultSet.getString(2)).thenReturn("John");
        when(mockResultSet.getInt(3)).thenReturn(25);
        // BiRowMapper.to() internally uses getObject to read column values
        when(mockResultSet.getObject(1)).thenReturn(1L);
        when(mockResultSet.getObject(2)).thenReturn("John");
        when(mockResultSet.getObject(3)).thenReturn(25);

        // A converter that returns null signals "skip this column". Before the fix the bean branch
        // dereferenced the null label (columnLabels[i].toLowerCase()) and threw NPE on the first row;
        // the Array/List/Map sibling branches already defended this case. With ignoreUnmatchedColumns
        // the null-converter column must be skipped, and the matched columns populated normally.
        final Jdbc.BiRowMapper<TestEntity> mapper = Jdbc.BiRowMapper.to(TestEntity.class, null, c -> c.startsWith("_") ? null : c, true);
        final List<String> columnLabels = Arrays.asList("id", "name", "age", "_internal");

        when(mockResultSetMetaData.getColumnCount()).thenReturn(4);
        when(mockResultSetMetaData.getColumnLabel(4)).thenReturn("_internal");

        final TestEntity result = mapper.apply(mockResultSet, columnLabels);
        assertNotNull(result);
        assertEquals(Long.valueOf(1L), result.getId());
        assertEquals("John", result.getName());
        assertEquals(Integer.valueOf(25), result.getAge());
    }

    @Test
    public void testBiRowMapper_ToScalarType_NullFilter() throws SQLException {
        // Scalar type with null filter/converter → stateful single-column BiRowMapper
        when(mockResultSet.getString(1)).thenReturn("hello");

        Jdbc.BiRowMapper<String> mapper = Jdbc.BiRowMapper.to(String.class, null, null, false);
        List<String> columnLabels = Arrays.asList("value");

        String result = mapper.apply(mockResultSet, columnLabels);
        assertEquals("hello", result);
    }

    @Test
    public void testBiRowMapper_ToPrefixMap_EmptyPrefix() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(4L);
        when(mockResultSet.getString(2)).thenReturn("George");
        when(mockResultSet.getObject(3)).thenReturn(35);

        // empty prefix map → delegates to 2-arg to(entityClass, ignoreUnmatched)
        Jdbc.BiRowMapper<TestEntity> mapper = Jdbc.BiRowMapper.to(TestEntity.class, new HashMap<>());
        List<String> columnLabels = Arrays.asList("id", "name", "age");

        TestEntity result = mapper.apply(mockResultSet, columnLabels);
        assertNotNull(result);
    }

    @Test
    public void testBiRowMapper_ToPrefixMap_NonEmpty_IgnoreUnmatched() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(5L);
        when(mockResultSet.getString(2)).thenReturn("Hannah");
        when(mockResultSet.getObject(3)).thenReturn(40);

        Map<String, String> prefixMap = new HashMap<>();
        prefixMap.put("u_", "");

        // non-empty prefix map, ignoreUnmatched=true
        Jdbc.BiRowMapper<TestEntity> mapper = Jdbc.BiRowMapper.to(TestEntity.class, prefixMap, true);
        List<String> columnLabels = Arrays.asList("id", "name", "age");

        TestEntity result = mapper.apply(mockResultSet, columnLabels);
        assertNotNull(result);
    }

    @Test
    public void testBiRowMapper_ToList_WithNullFilter() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(6L);
        when(mockResultSet.getObject(2)).thenReturn("Ivan");
        when(mockResultSet.getObject(3)).thenReturn(22);

        // null filter with null converter → simple List lambda path (lines 2993-3003)
        Jdbc.BiRowMapper<List> mapper = Jdbc.BiRowMapper.to(List.class, null, null, false);
        List<String> columnLabels = Arrays.asList("id", "name", "age");

        List result = mapper.apply(mockResultSet, columnLabels);
        assertNotNull(result);
        assertEquals(3, result.size());
    }

    @Test
    public void testBiRowMapper_ToMap_WithNullFilter() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(7L);
        when(mockResultSet.getObject(2)).thenReturn("Jana");
        when(mockResultSet.getObject(3)).thenReturn(33);

        // null filter with null converter → simple Map lambda path (lines 3042-3060)
        Jdbc.BiRowMapper<Map> mapper = Jdbc.BiRowMapper.to(Map.class, null, null, false);
        List<String> columnLabels = Arrays.asList("id", "name", "age");

        Map result = mapper.apply(mockResultSet, columnLabels);
        assertNotNull(result);
        assertEquals(3, result.size());
    }

    @Test
    public void testBiRowMapperRetriesFailedColumnConfiguration() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn("Alice");
        when(mockResultSet.getObject(2)).thenReturn(42);
        when(mockResultSet.getString(1)).thenReturn("Alice");
        when(mockResultSet.getInt(2)).thenReturn(42);
        final List<String> labels = List.of("source_name", "source_age");

        for (final Class<?> targetClass : List.of(Object[].class, List.class, Map.class, TestEntity.class)) {
            for (final boolean failInFilter : new boolean[] { false, true }) {
                final java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
                final IllegalStateException failure = new IllegalStateException("column configuration failed");
                final Jdbc.BiRowMapper<?> mapper = Jdbc.BiRowMapper.to(targetClass, label -> {
                    if (failInFilter && label.equals("source_age") && fail.getAndSet(false)) {
                        throw failure;
                    }
                    return true;
                }, label -> {
                    if (!failInFilter && label.equals("source_age") && fail.getAndSet(false)) {
                        throw failure;
                    }
                    return label.substring("source_".length());
                });

                assertSame(failure, assertThrows(IllegalStateException.class, () -> mapper.apply(mockResultSet, labels)));
                final Object result = mapper.apply(mockResultSet, labels);
                if (result instanceof final Object[] values) {
                    assertEquals(List.of("Alice", 42), Arrays.asList(values));
                } else if (result instanceof final List<?> values) {
                    assertEquals(List.of("Alice", 42), values);
                } else if (result instanceof final Map<?, ?> values) {
                    assertEquals(Map.of("name", "Alice", "age", 42), values);
                } else {
                    final TestEntity entity = (TestEntity) result;
                    assertEquals("Alice", entity.getName());
                    assertEquals(42, entity.getAge());
                }
            }
        }
    }

    @Test
    public void testBiRowMapper_ToScalarType_MultiColumnThrows() throws SQLException {
        // Scalar type with null filter/converter and multiple columns → throws
        Jdbc.BiRowMapper<String> mapper = Jdbc.BiRowMapper.to(String.class, null, null, false);
        List<String> columnLabels = Arrays.asList("col1", "col2");

        assertThrows(IllegalArgumentException.class, () -> mapper.apply(mockResultSet, columnLabels));
    }

    @Test
    public void testBiRowMapper_ToScalarType_WithFilterThrows() {
        // Scalar type with non-null filter → throws immediately
        assertThrows(IllegalArgumentException.class, () -> Jdbc.BiRowMapper.to(String.class, col -> true, null, false));
    }

    @Test
    public void testBiRowMapper_ToPrefixMap_UnmatchedColumn_ThrowsException() throws SQLException {
        Map<String, String> prefixMap = new HashMap<>();
        prefixMap.put("x_", "");

        // non-empty prefix map, ignoreUnmatchedColumns=false → throws on unmatched column
        Jdbc.BiRowMapper<TestEntity> mapper = Jdbc.BiRowMapper.to(TestEntity.class, prefixMap, false);
        List<String> columnLabels = Arrays.asList("x_unknown");

        assertThrows(IllegalArgumentException.class, () -> mapper.apply(mockResultSet, columnLabels));
    }

    // Static BiRowMapper.to with ignoreUnmatchedColumns=false throws on an unmatched column.
    @Test
    public void testBiRowMapperTo_FilterConverter_UnmatchedThrows() throws SQLException {
        final java.util.function.Predicate<String> filter = s -> true;
        final java.util.function.Function<String, String> converter = s -> s;
        final Jdbc.BiRowMapper<TestEntity> mapper = Jdbc.BiRowMapper.to(TestEntity.class, filter, converter, false);

        assertThrows(IllegalArgumentException.class, () -> mapper.apply(mockResultSet, Arrays.asList("id", "unknown_col", "age")));
    }

    @Test
    public void testRowMapperToArray() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1);
        when(mockResultSet.getObject(2)).thenReturn("John");
        when(mockResultSet.getObject(3)).thenReturn(25);

        Jdbc.RowMapper<Object[]> mapper = Jdbc.RowMapper.toArray(Jdbc.ColumnGetter.GET_OBJECT);
        Object[] result = mapper.apply(mockResultSet);

        assertEquals(3, result.length);
        assertEquals(1, result[0]);
        assertEquals("John", result[1]);
        assertEquals(25, result[2]);
    }

    @Test
    public void testBiRowMapperToArray2() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1);
        when(mockResultSet.getObject(2)).thenReturn("John");

        Jdbc.BiRowMapper<Object[]> mapper = Jdbc.BiRowMapper.toArray(Jdbc.ColumnGetter.GET_OBJECT);
        List<String> columnLabels = Arrays.asList("id", "name");

        Object[] result = mapper.apply(mockResultSet, columnLabels);
        assertEquals(2, result.length);
        assertEquals(1, result[0]);
        assertEquals("John", result[1]);
    }

    @Test
    public void testStatefulMappers() throws SQLException {
        // Test that stateful mappers maintain state across invocations
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getInt(1)).thenReturn(100);
        when(mockResultSet.getString(2)).thenReturn("test");

        Jdbc.RowMapper<Object[]> mapper = Jdbc.RowMapper.toArray(Jdbc.ColumnGetter.GET_OBJECT);

        // First invocation
        Object[] result1 = mapper.apply(mockResultSet);
        assertEquals(3, result1.length);

        // Second invocation should reuse internal state
        when(mockResultSet.getObject(1)).thenReturn(200);
        Object[] result2 = mapper.apply(mockResultSet);
        assertEquals(3, result2.length);
        assertEquals(200, result2[0]);
    }

    @Test
    public void testRowMapperToCollection() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1);
        when(mockResultSet.getObject(2)).thenReturn("John");
        when(mockResultSet.getObject(3)).thenReturn(25);

        Jdbc.RowMapper<Set<Object>> mapper = Jdbc.RowMapper.toCollection(Jdbc.ColumnGetter.GET_OBJECT, size -> new HashSet<>());
        Set<Object> result = mapper.apply(mockResultSet);

        assertEquals(3, result.size());
        assertTrue(result.contains(1));
        assertTrue(result.contains("John"));
        assertTrue(result.contains(25));
    }

    @Test
    public void testBiRowMapperToCollection() throws SQLException {
        when(mockResultSet.getInt(1)).thenReturn(10);
        when(mockResultSet.getInt(2)).thenReturn(20);

        Jdbc.BiRowMapper<Set<Object>> mapper = Jdbc.BiRowMapper.toCollection(Jdbc.ColumnGetter.GET_INT, size -> new HashSet<>());
        List<String> columnLabels = Arrays.asList("val1", "val2");

        Set<Object> result = mapper.apply(mockResultSet, columnLabels);
        assertEquals(2, result.size());
        assertTrue(result.contains(10));
        assertTrue(result.contains(20));
    }

    @Test
    public void testRowMapperToDisposableObjArray() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1);
        when(mockResultSet.getObject(2)).thenReturn("John");
        when(mockResultSet.getObject(3)).thenReturn(25);

        Jdbc.RowMapper<DisposableObjArray> mapper = Jdbc.RowMapper.toDisposableObjArray();
        DisposableObjArray result = mapper.apply(mockResultSet);

        assertEquals(3, result.length());
        assertEquals(1, result.get(0));
        assertEquals("John", result.get(1));
        assertEquals(25, result.get(2));
    }

    @Test
    public void testRowMapperToDisposableObjArray_WithEntityClass() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(2L);
        when(mockResultSet.getString(2)).thenReturn("Nina");
        when(mockResultSet.getObject(3)).thenReturn(27);

        Jdbc.RowMapper<DisposableObjArray> mapper = Jdbc.RowMapper.toDisposableObjArray(TestEntity.class);
        DisposableObjArray result = mapper.apply(mockResultSet);

        assertNotNull(result);
        assertEquals(3, result.length());
    }

    @Test
    public void testBiRowMapperToDisposableObjArray() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1);
        when(mockResultSet.getObject(2)).thenReturn("John");
        when(mockResultSet.getObject(3)).thenReturn(25);

        Jdbc.BiRowMapper<DisposableObjArray> mapper = Jdbc.BiRowMapper.toDisposableObjArray();
        List<String> columnLabels = Arrays.asList("id", "name", "age");

        DisposableObjArray result = mapper.apply(mockResultSet, columnLabels);
        assertEquals(3, result.length());
        assertEquals(1, result.get(0));
        assertEquals("John", result.get(1));
        assertEquals(25, result.get(2));
    }

    @Test
    public void testBiRowMapperToDisposableObjArray_WithEntityClass() throws SQLException {
        when(mockResultSet.getString(2)).thenReturn("Helen");
        when(mockResultSet.getObject(1)).thenReturn(5L);
        when(mockResultSet.getObject(3)).thenReturn(32);

        Jdbc.BiRowMapper<DisposableObjArray> mapper = Jdbc.BiRowMapper.toDisposableObjArray(TestEntity.class);
        List<String> columnLabels = Arrays.asList("id", "name", "age");

        DisposableObjArray result = mapper.apply(mockResultSet, columnLabels);
        assertNotNull(result);
        assertEquals(3, result.length());
    }

    // RowMapper.toDisposableObjArray(entityClass): "user_name" resolves to field "userName" via the
    // @Column map; an "unknown" column stays unmapped (columnTypes[i]==null -> getColumnValue path).
    @Test
    public void testRowMapperToDisposableObjArray_WithColumnAnnotation() throws SQLException {
        when(mockResultSetMetaData.getColumnCount()).thenReturn(3);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("user_name");
        when(mockResultSetMetaData.getColumnLabel(3)).thenReturn("unknown");
        when(mockResultSet.getObject(3)).thenReturn("x");

        final Jdbc.RowMapper<DisposableObjArray> mapper = Jdbc.RowMapper.toDisposableObjArray(MappedColumnEntity.class);
        final DisposableObjArray arr = mapper.apply(mockResultSet);

        assertNotNull(arr);
        assertEquals(3, arr.length());
        // The unmapped column falls back to JdbcUtil.getColumnValue.
        assertEquals("x", arr.get(2));
    }

    @Test
    public void testRowMapperColumnLookupIsLocaleIndependent() throws SQLException {
        final Locale previousLocale = Locale.getDefault();

        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
            when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("USER_ID");
            when(mockResultSet.getObject(1)).thenReturn("7");

            final Jdbc.RowMapper<DisposableObjArray> mapper = Jdbc.RowMapper.toDisposableObjArray(LocaleMappedEntity.class);
            final DisposableObjArray row = mapper.apply(mockResultSet);

            assertEquals(7L, row.get(0));
        } finally {
            Locale.setDefault(previousLocale);
        }
    }

    @Test
    public void testRowMapperToList() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1);
        when(mockResultSet.getObject(2)).thenReturn("John");
        when(mockResultSet.getObject(3)).thenReturn(25);

        Jdbc.RowMapper<List<Object>> mapper = Jdbc.RowMapper.toList(Jdbc.ColumnGetter.GET_OBJECT);
        List<Object> result = mapper.apply(mockResultSet);

        assertEquals(3, result.size());
        assertEquals(1, result.get(0));
        assertEquals("John", result.get(1));
        assertEquals(25, result.get(2));
    }

    @Test
    public void testBiRowMapperToList2() throws SQLException {
        when(mockResultSet.getString(1)).thenReturn("John");
        when(mockResultSet.getString(2)).thenReturn("Doe");

        Jdbc.BiRowMapper<List<Object>> mapper = Jdbc.BiRowMapper.toList(Jdbc.ColumnGetter.GET_STRING);
        List<String> columnLabels = Arrays.asList("first_name", "last_name");

        List<Object> result = mapper.apply(mockResultSet, columnLabels);
        assertEquals(2, result.size());
        assertEquals("John", result.get(0));
        assertEquals("Doe", result.get(1));
    }

    //    @Test
    //    public void testBiRowMapperToMapWithValueFilter() throws SQLException {
    //        when(mockResultSet.getObject(1)).thenReturn(1);
    //        when(mockResultSet.getObject(2)).thenReturn(null);
    //        when(mockResultSet.getObject(3)).thenReturn("John");
    //
    //        Jdbc.BiRowMapper<Map<String, Object>> mapper = Jdbc.BiRowMapper.toMap(Fn.p(value -> value != null));
    //        List<String> columnLabels = Arrays.asList("id", "email", "name");
    //
    //        Map<String, Object> result = mapper.apply(mockResultSet, columnLabels);
    //        assertEquals(2, result.size());
    //        assertEquals(1, result.get("id"));
    //        assertEquals("John", result.get("name"));
    //        assertFalse(result.containsKey("email"));
    //    }

    @Test
    public void testBiRowMapperToMapWithBiPredicate() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1);
        when(mockResultSet.getObject(2)).thenReturn("secret");
        when(mockResultSet.getObject(3)).thenReturn("John");

        Jdbc.BiRowMapper<Map<String, Object>> mapper = Jdbc.BiRowMapper.toMap((key, value) -> !key.startsWith("_") && value != null, IntFunctions.ofTreeMap());
        List<String> columnLabels = Arrays.asList("id", "_secret", "name");

        Map<String, Object> result = mapper.apply(mockResultSet, columnLabels);
        assertTrue(result instanceof TreeMap);
        assertEquals(2, result.size());
        assertEquals(1, result.get("id"));
        assertEquals("John", result.get("name"));
        assertFalse(result.containsKey("_secret"));
    }

    @Test
    public void testBiRowMapperToMapWithColumnNameConverter() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1);
        when(mockResultSet.getObject(2)).thenReturn("John");

        Jdbc.BiRowMapper<Map<String, Object>> mapper = Jdbc.BiRowMapper.toMap(Fn.f(colName -> colName.toString().toUpperCase()));
        List<String> columnLabels = Arrays.asList("id", "name");

        Map<String, Object> result = mapper.apply(mockResultSet, columnLabels);
        assertEquals(2, result.size());
        assertEquals(1, result.get("ID"));
        assertEquals("John", result.get("NAME"));
    }

    @Test
    public void testBiRowMapperToMap_WithRowExtractor() throws SQLException {
        Jdbc.RowExtractor extractor = (rs, output) -> {
            output[0] = rs.getObject(1);
            output[1] = rs.getObject(2);
            output[2] = rs.getObject(3);
        };
        when(mockResultSet.getObject(1)).thenReturn(10);
        when(mockResultSet.getObject(2)).thenReturn("Eve");
        when(mockResultSet.getObject(3)).thenReturn(null);

        Jdbc.BiRowMapper<Map<String, Object>> mapper = Jdbc.BiRowMapper.toMap(extractor);
        List<String> columnLabels = Arrays.asList("id", "name", "age");

        Map<String, Object> result = mapper.apply(mockResultSet, columnLabels);
        assertEquals(3, result.size());
        assertEquals(10, result.get("id"));
        assertEquals("Eve", result.get("name"));
        assertNull(result.get("age"));
    }

    @Test
    public void testBiRowMapperToMap_WithRowExtractorAndValueFilter() throws SQLException {
        Jdbc.RowExtractor extractor = (rs, output) -> {
            output[0] = rs.getObject(1);
            output[1] = rs.getObject(2);
            output[2] = rs.getObject(3);
        };
        when(mockResultSet.getObject(1)).thenReturn(20);
        when(mockResultSet.getObject(2)).thenReturn("Frank");
        when(mockResultSet.getObject(3)).thenReturn(null);

        Jdbc.BiRowMapper<Map<String, Object>> mapper = Jdbc.BiRowMapper.toMap(extractor, (key, value) -> value != null, IntFunctions.ofLinkedHashMap());

        List<String> columnLabels = Arrays.asList("id", "name", "age");
        Map<String, Object> result = mapper.apply(mockResultSet, columnLabels);
        assertEquals(2, result.size());
        assertEquals(20, result.get("id"));
        assertEquals("Frank", result.get("name"));
        assertFalse(result.containsKey("age"));
    }

    @Test
    public void testBiRowMapperToMap_WithRowExtractorAndNameConverter() throws SQLException {
        Jdbc.RowExtractor extractor = (rs, output) -> {
            output[0] = rs.getObject(1);
            output[1] = rs.getObject(2);
        };
        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("first_name");
        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("user_age");
        when(mockResultSet.getObject(1)).thenReturn("Grace");
        when(mockResultSet.getObject(2)).thenReturn(28);

        Jdbc.BiRowMapper<Map<String, Object>> mapper = Jdbc.BiRowMapper.toMap(extractor, col -> col.toUpperCase(), IntFunctions.ofTreeMap());

        List<String> columnLabels = Arrays.asList("first_name", "user_age");
        Map<String, Object> result = mapper.apply(mockResultSet, columnLabels);
        assertEquals(2, result.size());
        assertTrue(result instanceof TreeMap);
        assertEquals("Grace", result.get("FIRST_NAME"));
        assertEquals(28, result.get("USER_AGE"));
    }

    @Test
    public void testBiRowMapperToMapRetriesFailedNameConversion() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn("Alice");
        when(mockResultSet.getObject(2)).thenReturn(42);
        for (final boolean useRowExtractor : new boolean[] { false, true }) {
            final java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
            final IllegalStateException failure = new IllegalStateException("name conversion failed");
            final java.util.function.Function<String, String> converter = label -> {
                if (label.equals("age") && fail.getAndSet(false)) {
                    throw failure;
                }
                return label.toUpperCase(Locale.ROOT);
            };
            final Jdbc.RowExtractor extractor = (rs, output) -> {
                output[0] = rs.getObject(1);
                output[1] = rs.getObject(2);
            };
            final Jdbc.BiRowMapper<Map<String, Object>> mapper = useRowExtractor ? Jdbc.BiRowMapper.toMap(extractor, converter, IntFunctions.ofMap())
                    : Jdbc.BiRowMapper.toMap(converter);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> mapper.apply(mockResultSet, List.of("name", "age"))));
            assertEquals(Map.of("NAME", "Alice", "AGE", 42), mapper.apply(mockResultSet, List.of("name", "age")));
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testBiRowMapper_ToRowMapper() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1L);
        when(mockResultSet.getObject(2)).thenReturn("Bob");
        when(mockResultSet.getObject(3)).thenReturn(25);

        Jdbc.RowMapper<Object[]> rowMapper = Jdbc.BiRowMapper.TO_ARRAY.toRowMapper();
        assertNotNull(rowMapper);

        Object[] result = rowMapper.apply(mockResultSet);
        assertNotNull(result);
        assertEquals(3, result.length);
        assertEquals(1L, result[0]);
        assertEquals("Bob", result[1]);
    }

    public record NameRecord(String firstName, String lastName) {
    }

    public static class NestedEntity {
        private TestEntity user;

        public TestEntity getUser() {
            return user;
        }

        public void setUser(final TestEntity user) {
            this.user = user;
        }
    }

    // Entity with a @Column-aliased field so a ResultSet column name ("user_name") differs from the
    // Java field ("userName"), exercising the column2FieldNameMap resolution branches in the
    // entity-aware mappers (Jdbc L2037-2044 etc.).
    public static class MappedColumnEntity {
        private Long id;
        @com.landawn.abacus.annotation.Column("user_name")
        private String userName;

        public Long getId() {
            return id;
        }

        public void setId(final Long id) {
            this.id = id;
        }

        public String getUserName() {
            return userName;
        }

        public void setUserName(final String userName) {
            this.userName = userName;
        }
    }

    public static class LocaleMappedEntity {
        @com.landawn.abacus.annotation.Column("user_id")
        private Long id;

        public Long getId() {
            return id;
        }

        public void setId(final Long id) {
            this.id = id;
        }
    }
}
