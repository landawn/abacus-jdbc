package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.util.stream.Collectors;
import com.landawn.abacus.util.stream.Stream;

class PreparedQueryStreamTest extends PreparedQueryTestSupport {

    @Test
    public void testForeach() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn("value");

        List<Object> results = new ArrayList<>();
        query.foreach(row -> results.add(row.get(0)));

        assertEquals(1, results.size());
        assertEquals("value", results.get(0));
        verify(mockResultSet).close();
    }

    @Test
    public void testForEach() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("A", "B");

        List<String> results = new ArrayList<>();
        query.forEach(rs -> results.add(rs.getString(1)));

        assertEquals(Arrays.asList("A", "B"), results);
        verify(mockResultSet).close();
    }

    //    @Test
    //    public void testForEachWithFilter() throws SQLException {
    //        when(mockResultSet.next()).thenReturn(true, true, true, false);
    //        when(mockResultSet.getInt(1)).thenReturn(5, 10, 15);
    //
    //        List<Integer> results = new ArrayList<>();
    //        query.forEach(rs -> rs.getInt(1) > 8, rs -> results.add(rs.getInt(1)));
    //
    //        assertEquals(Arrays.asList(10, 15), results);
    //        verify(mockResultSet).close();
    //    }

    @Test
    public void testForEachBiConsumer() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        List<Integer> results = new ArrayList<>();
        query.forEach((rs, labels) -> results.add(labels.size()));

        assertEquals(Arrays.asList(1), results);
        verify(mockResultSet).close();
    }

    @Test
    public void testForEachWithBiFilter() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("skip", "take");

        List<String> results = new ArrayList<>();
        query.forEach((rs, labels) -> rs.getString(1).equals("take"), (rs, labels) -> results.add(rs.getString(1)));

        assertEquals(Arrays.asList("take"), results);
        verify(mockResultSet).close();
    }

    @Test
    public void testStream() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn("val1", "val2");

        try (Stream<Map<String, Object>> stream = query.stream()) {
            List<Map<String, Object>> result = stream.collect(Collectors.toList());
            assertEquals(2, result.size());
        }

        verify(mockResultSet).close();
    }

    @Test
    public void testStreamBiRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        try (Stream<Integer> stream = query.stream((rs, labels) -> labels.size())) {
            List<Integer> result = stream.collect(Collectors.toList());
            assertEquals(Arrays.asList(1), result);
        }

        verify(mockResultSet).close();
    }

    @Test
    public void testStreamClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);

        try (Stream<TestEntity> stream = query.stream(TestEntity.class)) {
            long count = stream.count();
            assertEquals(2, count);
        }

        verify(mockResultSet).close();
    }

    @Test
    public void testStreamRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("A", "B");

        try (Stream<String> stream = query.stream(rs -> rs.getString(1))) {
            List<String> result = stream.collect(Collectors.toList());
            assertEquals(Arrays.asList("A", "B"), result);
        }

        verify(mockResultSet).close();
    }
}
