package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.util.Dataset;

class JdbcTest extends JdbcTestSupport {

    // Edge cases and error conditions
    @Test
    public void testNullHandling() throws SQLException {
        // Test null ResultSet in ResultExtractor
        Dataset dataset = Jdbc.ResultExtractor.TO_DATASET.apply(null);
        assertNotNull(dataset);
        assertTrue(dataset.isEmpty());

        // Test null ResultSet in BiResultExtractor
        Dataset biDataset = Jdbc.BiResultExtractor.TO_DATASET.apply(null, Arrays.asList("col1"));
        assertNotNull(biDataset);
        assertTrue(biDataset.isEmpty());
    }

    @Test
    public void testNullArguments() {
        assertThrows(IllegalArgumentException.class, () -> {
            Jdbc.BiParametersSetter.forArray(null, TestEntity.class);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            Jdbc.BiParametersSetter.forArray(Arrays.asList("field"), null);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            Jdbc.ResultExtractor.toMap(null, rs -> rs.getString("value"));
        });

        assertThrows(IllegalArgumentException.class, () -> {
            Jdbc.RowMapper.builder(null);
        });
    }

    @Test
    public void testTypeConversions() throws SQLException {
        // Test various type conversions through ColumnGetter
        when(mockResultSet.getBigDecimal(1)).thenReturn(new BigDecimal("123.45"));

        Jdbc.ColumnGetter<BigDecimal> bigDecimalGetter = Jdbc.ColumnGetter.forType(BigDecimal.class);
        BigDecimal bd = bigDecimalGetter.get(mockResultSet, 1);
        assertNotNull(bd);
        // Verify the BigDecimal value is preserved exactly
        assertEquals(new BigDecimal("123.45"), bd);
        assertEquals(0, bd.compareTo(new BigDecimal("123.45")));

        // Verify ColumnGetter.get returns a working getter for String type
        when(mockResultSet.getString(1)).thenReturn("hello");
        Jdbc.ColumnGetter<String> stringGetter = Jdbc.ColumnGetter.forType(String.class);
        assertEquals("hello", stringGetter.get(mockResultSet, 1));
    }

    @Test
    public void testEmptyResultSet() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        // Test with various extractors
        Jdbc.ResultExtractor<List<String>> listExtractor = Jdbc.ResultExtractor.toList(rs -> rs.getString("name"));
        List<String> emptyList = listExtractor.apply(mockResultSet);
        assertTrue(emptyList.isEmpty());

        Jdbc.ResultExtractor<Map<Integer, String>> mapExtractor = Jdbc.ResultExtractor.toMap(rs -> rs.getInt("id"), rs -> rs.getString("name"));
        Map<Integer, String> emptyMap = mapExtractor.apply(mockResultSet);
        assertTrue(emptyMap.isEmpty());
    }

    @Test
    public void testComplexScenarios() throws SQLException {
        // Test ResultExtractor with merge function handling duplicates
        when(mockResultSet.next()).thenReturn(true, true, true, false);
        when(mockResultSet.getString("key")).thenReturn("A", "A", "B");
        when(mockResultSet.getInt("value")).thenReturn(10, 20, 30);

        Jdbc.ResultExtractor<Map<String, Integer>> extractor = Jdbc.ResultExtractor.toMap(rs -> rs.getString("key"), rs -> rs.getInt("value"), Integer::sum,
                TreeMap::new);

        Map<String, Integer> result = extractor.apply(mockResultSet);
        assertTrue(result instanceof TreeMap);
        assertEquals(2, result.size());
        assertEquals(30, result.get("A")); // 10 + 20
        assertEquals(30, result.get("B"));
    }
}
