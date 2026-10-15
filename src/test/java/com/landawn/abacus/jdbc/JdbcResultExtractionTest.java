package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.ListMultimap;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Suppliers;

class JdbcResultExtractionTest extends JdbcTestSupport {

    // ResultExtractor Tests
    @Test
    public void testResultExtractorToDataset() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);
        Dataset result = Jdbc.ResultExtractor.TO_DATASET.apply(mockResultSet);
        assertNotNull(result);
        // No rows were returned, so the dataset should be empty
        assertTrue(result.isEmpty());
        assertEquals(0, result.size());
    }

    // BiResultExtractor Tests
    @Test
    public void testBiResultExtractorToDataset() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);
        Dataset result = Jdbc.BiResultExtractor.TO_DATASET.apply(mockResultSet, Arrays.asList("col1"));
        assertNotNull(result);
        // No rows were returned, so the dataset should be empty
        assertTrue(result.isEmpty());
        assertEquals(0, result.size());
    }

    @Test
    public void testBiResultExtractorAndThen() throws SQLException {
        Jdbc.BiResultExtractor<String> extractor = (rs, cols) -> "test";
        Jdbc.BiResultExtractor<Integer> composed = extractor.andThen(String::length);
        assertEquals(4, composed.apply(mockResultSet, Arrays.asList("col1")));
    }

    @Test
    public void testResultExtractorGroupingFinishesConcurrentSkipListMap() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, true, false);
        when(mockResultSet.getString(1)).thenReturn("b", "a", "b");
        when(mockResultSet.getInt(2)).thenReturn(3, 5, 7);
        final Jdbc.ResultExtractor<java.util.concurrent.ConcurrentSkipListMap<String, Integer>> extractor = Jdbc.ResultExtractor.groupTo(rs -> rs.getString(1),
                rs -> rs.getInt(2), Collectors.summingInt(Integer::intValue), java.util.concurrent.ConcurrentSkipListMap::new);

        final Map<String, Integer> result = extractor.apply(mockResultSet);
        assertEquals(Map.of("a", 5, "b", 10), result);
        assertEquals(List.of("a", "b"), new ArrayList<>(result.keySet()));
    }

    @Test
    public void testBiResultExtractorGroupingFinishesConcurrentSkipListMap() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, true, false);
        when(mockResultSet.getString(1)).thenReturn("b", "a", "b");
        when(mockResultSet.getInt(2)).thenReturn(3, 5, 7);
        final Jdbc.BiResultExtractor<java.util.concurrent.ConcurrentSkipListMap<String, Integer>> extractor = Jdbc.BiResultExtractor.groupTo(
                (rs, labels) -> rs.getString(1), (rs, labels) -> rs.getInt(2), Collectors.summingInt(Integer::intValue),
                java.util.concurrent.ConcurrentSkipListMap::new);

        final Map<String, Integer> result = extractor.apply(mockResultSet, List.of("category", "value"));
        assertEquals(Map.of("a", 5, "b", 10), result);
        assertEquals(List.of("a", "b"), new ArrayList<>(result.keySet()));
    }

    @Test
    public void testResultExtractorGroupTo() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString("category")).thenReturn("A", "A");
        when(mockResultSet.getInt("value")).thenReturn(10, 20);

        Jdbc.ResultExtractor<Map<String, List<Integer>>> extractor = Jdbc.ResultExtractor.groupTo(rs -> rs.getString("category"), rs -> rs.getInt("value"));

        Map<String, List<Integer>> result = extractor.apply(mockResultSet);
        assertEquals(1, result.size());
        assertEquals(Arrays.asList(10, 20), result.get("A"));
    }

    @Test
    public void testResultExtractorGroupToWithCollector() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString("category")).thenReturn("A", "A");
        when(mockResultSet.getDouble("value")).thenReturn(10.0, 20.0);

        Jdbc.ResultExtractor<Map<String, Double>> extractor = Jdbc.ResultExtractor.groupTo(rs -> rs.getString("category"), rs -> rs.getDouble("value"),
                Collectors.summingDouble(Double::doubleValue));

        Map<String, Double> result = extractor.apply(mockResultSet);
        assertEquals(1, result.size());
        assertEquals(30.0, result.get("A"), 0.001);
    }

    @Test
    public void testBiResultExtractorGroupTo() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("A", "A");
        when(mockResultSet.getInt(2)).thenReturn(10, 20);

        List<String> columnLabels = Arrays.asList("category", "value");

        Jdbc.BiResultExtractor<Map<String, List<Integer>>> extractor = Jdbc.BiResultExtractor.groupTo((rs, cols) -> rs.getString(1),
                (rs, cols) -> rs.getInt(2));

        Map<String, List<Integer>> result = extractor.apply(mockResultSet, columnLabels);
        assertEquals(1, result.size());
        assertEquals(Arrays.asList(10, 20), result.get("A"));
    }

    @Test
    public void testBiResultExtractorGroupToWithCollector_DefaultSupplier() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("A", "A");
        when(mockResultSet.getInt(2)).thenReturn(2, 3);

        Jdbc.BiResultExtractor<Map<String, Integer>> extractor = Jdbc.BiResultExtractor.groupTo((rs, cols) -> rs.getString(1), (rs, cols) -> rs.getInt(2),
                Collectors.summingInt(Integer::intValue));

        Map<String, Integer> result = extractor.apply(mockResultSet, Arrays.asList("category", "amount"));

        assertEquals(1, result.size());
        assertEquals(5, result.get("A"));
    }

    @Test
    public void testResultExtractorAndThen() throws SQLException {
        Jdbc.ResultExtractor<String> extractor = rs -> "test";
        Jdbc.ResultExtractor<Integer> composed = extractor.andThen(String::length);
        assertEquals(4, composed.apply(mockResultSet));
    }

    @Test
    public void testResultExtractorToBiResultExtractor() throws SQLException {
        Jdbc.ResultExtractor<String> extractor = rs -> "test";
        Jdbc.BiResultExtractor<String> biExtractor = extractor.toBiResultExtractor();
        assertEquals("test", biExtractor.apply(mockResultSet, Arrays.asList("col1")));
    }

    @Test
    public void testResultExtractorToDataset_WithEntityClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        Jdbc.ResultExtractor<Dataset> extractor = Jdbc.ResultExtractor.toDataset(TestEntity.class);
        assertNotNull(extractor);

        Dataset result = extractor.apply(mockResultSet);
        assertNotNull(result);
        assertEquals(0, result.size());
    }

    @Test
    public void testResultExtractorToDataset_WithEntityClassAndPrefixMap() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        Jdbc.ResultExtractor<Dataset> extractor = Jdbc.ResultExtractor.toDataset(TestEntity.class, new HashMap<>());
        assertNotNull(extractor);

        Dataset result = extractor.apply(mockResultSet);
        assertNotNull(result);
        assertEquals(0, result.size());
    }

    @Test
    public void testResultExtractorToDataset_WithRowFilter() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn(1L);
        when(mockResultSet.getObject(2)).thenReturn("Alice");
        when(mockResultSet.getObject(3)).thenReturn(30);

        Jdbc.ResultExtractor<Dataset> extractor = Jdbc.ResultExtractor.toDataset(rs -> true);
        assertNotNull(extractor);

        Dataset result = extractor.apply(mockResultSet);
        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    public void testResultExtractorToDataset_WithRowExtractor() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn(1L);
        when(mockResultSet.getObject(2)).thenReturn("Bob");
        when(mockResultSet.getObject(3)).thenReturn(25);

        final Jdbc.RowExtractor noopExtractor = (rs, output) -> {
        };
        Jdbc.ResultExtractor<Dataset> extractor = Jdbc.ResultExtractor.toDataset(noopExtractor);
        assertNotNull(extractor);

        Dataset result = extractor.apply(mockResultSet);
        assertNotNull(result);
    }

    @Test
    public void testResultExtractorToDataset_WithRowFilterAndExtractor() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn(2L);
        when(mockResultSet.getObject(2)).thenReturn("Carol");
        when(mockResultSet.getObject(3)).thenReturn(28);

        final Jdbc.RowExtractor noopExtractor = (rs, output) -> {
        };
        Jdbc.ResultExtractor<Dataset> extractor = Jdbc.ResultExtractor.toDataset(rs -> true, noopExtractor);
        assertNotNull(extractor);

        Dataset result = extractor.apply(mockResultSet);
        assertNotNull(result);
    }

    @Test
    public void testResultExtractorToDatasetAndThen() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        Jdbc.ResultExtractor<Integer> extractor = Jdbc.ResultExtractor.toDatasetAndThen(ds -> ds.size());
        assertNotNull(extractor);

        Integer result = extractor.apply(mockResultSet);
        assertEquals(0, result);
    }

    @Test
    public void testResultExtractorToDatasetAndThen_NullAfterThrowsImmediately() {
        assertThrows(IllegalArgumentException.class, () -> Jdbc.ResultExtractor.toDatasetAndThen(null));
    }

    @Test
    public void testResultExtractorToList() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString("name")).thenReturn("John", "Jane");

        Jdbc.ResultExtractor<List<String>> extractor = Jdbc.ResultExtractor.toList(rs -> rs.getString("name"));

        List<String> result = extractor.apply(mockResultSet);
        assertEquals(Arrays.asList("John", "Jane"), result);
    }

    @Test
    public void testResultExtractorToListWithFilter() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, true, false);
        when(mockResultSet.getInt("age")).thenReturn(20, 15, 25);
        when(mockResultSet.getString("name")).thenReturn("John", "Jane", "Bob");

        Jdbc.ResultExtractor<List<String>> extractor = Jdbc.ResultExtractor.toList(rs -> {
            int age = rs.getInt("age");
            if (age < 18) {
                N.println(rs.getString("name"));
            }
            return age >= 18;
        }, rs -> rs.getString("name"));

        List<String> result = extractor.apply(mockResultSet);
        assertEquals(Arrays.asList("John", "Bob"), result);
    }

    @Test
    public void testResultExtractorToListWithClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getLong(1)).thenReturn(1L);
        when(mockResultSet.getString(2)).thenReturn("John");
        when(mockResultSet.getInt(3)).thenReturn(25);

        Jdbc.ResultExtractor<List<TestEntity>> extractor = Jdbc.ResultExtractor.toList(TestEntity.class);

        List<TestEntity> result = extractor.apply(mockResultSet);
        assertEquals(1, result.size());
    }

    @Test
    public void testBiResultExtractorToList() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("John", "Jane");

        List<String> columnLabels = Arrays.asList("name");

        Jdbc.BiResultExtractor<List<String>> extractor = Jdbc.BiResultExtractor.toList((rs, cols) -> rs.getString(1));

        List<String> result = extractor.apply(mockResultSet, columnLabels);
        assertEquals(Arrays.asList("John", "Jane"), result);
    }

    @Test
    public void testBiResultExtractorToListWithFilter() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, true, false);
        when(mockResultSet.getInt(1)).thenReturn(20, 15, 25);
        when(mockResultSet.getString(2)).thenReturn("John", "Jane", "Bob");

        List<String> columnLabels = Arrays.asList("age", "name");

        Jdbc.BiResultExtractor<List<String>> extractor = Jdbc.BiResultExtractor.toList((rs, cols) -> {
            int age = rs.getInt(1);
            if (age < 18) {
                N.println(rs.getString(2));
            }
            return age >= 18;
        }, (rs, cols) -> rs.getString(2));

        List<String> result = extractor.apply(mockResultSet, columnLabels);
        assertEquals(Arrays.asList("John", "Bob"), result);
    }

    @Test
    public void testBiResultExtractorToListWithTargetClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getLong(1)).thenReturn(1L);
        when(mockResultSet.getString(2)).thenReturn("John");
        when(mockResultSet.getInt(3)).thenReturn(25);

        Jdbc.BiResultExtractor<List<TestEntity>> extractor = Jdbc.BiResultExtractor.toList(TestEntity.class);

        List<TestEntity> result = extractor.apply(mockResultSet, Arrays.asList("id", "name", "age"));

        assertEquals(1, result.size());
        assertEquals("John", result.get(0).getName());
    }

    @Test
    public void testResultExtractorToMap() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getInt("id")).thenReturn(1, 2);
        when(mockResultSet.getString("name")).thenReturn("John", "Jane");

        Jdbc.ResultExtractor<Map<Integer, String>> extractor = Jdbc.ResultExtractor.toMap(rs -> rs.getInt("id"), rs -> rs.getString("name"));

        Map<Integer, String> result = extractor.apply(mockResultSet);
        assertEquals(2, result.size());
        assertEquals("John", result.get(1));
        assertEquals("Jane", result.get(2));
    }

    @Test
    public void testResultExtractorToMapWithSupplier() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getInt("id")).thenReturn(1);
        when(mockResultSet.getString("name")).thenReturn("John");

        Jdbc.ResultExtractor<Map<Integer, String>> extractor = Jdbc.ResultExtractor.toMap(rs -> rs.getInt("id"), rs -> rs.getString("name"),
                Suppliers.ofLinkedHashMap());

        Map<Integer, String> result = extractor.apply(mockResultSet);
        assertEquals(1, result.size());
        assertEquals("John", result.get(1));
    }

    @Test
    public void testResultExtractorToMapWithMergeFunction() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString("category")).thenReturn("A", "A");
        when(mockResultSet.getInt("value")).thenReturn(10, 20);

        Jdbc.ResultExtractor<Map<String, Integer>> extractor = Jdbc.ResultExtractor.toMap(rs -> rs.getString("category"), rs -> rs.getInt("value"),
                Integer::sum);

        Map<String, Integer> result = extractor.apply(mockResultSet);
        assertEquals(1, result.size());
        assertEquals(30, result.get("A"));
    }

    @SuppressWarnings("deprecation")
    @Test
    public void testResultExtractorToMap_WithCollector_Deprecated() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString("category")).thenReturn("A", "A");
        when(mockResultSet.getInt("value")).thenReturn(5, 7);

        // 3-arg deprecated toMap delegates to 4-arg toMap which delegates to groupTo
        Jdbc.ResultExtractor<Map<String, Integer>> extractor = Jdbc.ResultExtractor.toMap(rs -> rs.getString("category"), rs -> rs.getInt("value"),
                Collectors.summingInt(Integer::intValue));

        Map<String, Integer> result = extractor.apply(mockResultSet);
        assertEquals(1, result.size());
        assertEquals(12, result.get("A"));
    }

    @SuppressWarnings("deprecation")
    @Test
    public void testResultExtractorToMap_WithCollectorAndSupplier_Deprecated() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getString("key")).thenReturn("X");
        when(mockResultSet.getInt("val")).thenReturn(99);

        // 4-arg deprecated toMap delegates to groupTo
        Jdbc.ResultExtractor<LinkedHashMap<String, Integer>> extractor = Jdbc.ResultExtractor.toMap(rs -> rs.getString("key"), rs -> rs.getInt("val"),
                Collectors.summingInt(Integer::intValue), LinkedHashMap::new);

        LinkedHashMap<String, Integer> result = extractor.apply(mockResultSet);
        assertEquals(1, result.size());
        assertEquals(99, result.get("X"));
    }

    @Test
    public void testBiResultExtractorToMap() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getInt(1)).thenReturn(1);
        when(mockResultSet.getString(2)).thenReturn("John");

        List<String> columnLabels = Arrays.asList("id", "name");

        Jdbc.BiResultExtractor<Map<Integer, String>> extractor = Jdbc.BiResultExtractor.toMap((rs, cols) -> rs.getInt(1), (rs, cols) -> rs.getString(2));

        Map<Integer, String> result = extractor.apply(mockResultSet, columnLabels);
        assertEquals(1, result.size());
        assertEquals("John", result.get(1));
    }

    @Test
    public void testBiResultExtractorToMapWithMergeFunction_DefaultSupplier() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("A", "A");
        when(mockResultSet.getInt(2)).thenReturn(10, 20);

        Jdbc.BiResultExtractor<Map<String, Integer>> extractor = Jdbc.BiResultExtractor.toMap((rs, cols) -> rs.getString(1), (rs, cols) -> rs.getInt(2),
                Integer::sum);

        Map<String, Integer> result = extractor.apply(mockResultSet, Arrays.asList("category", "amount"));

        assertEquals(1, result.size());
        assertEquals(30, result.get("A"));
    }

    @Test
    public void testBiResultExtractorToMapWithCollector_DefaultSupplier() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("A", "A");
        when(mockResultSet.getInt(2)).thenReturn(10, 20);

        Jdbc.BiResultExtractor<Map<String, Integer>> extractor = Jdbc.BiResultExtractor.toMap((rs, cols) -> rs.getString(1), (rs, cols) -> rs.getInt(2),
                Collectors.summingInt(Integer::intValue));

        Map<String, Integer> result = extractor.apply(mockResultSet, Arrays.asList("category", "amount"));

        assertEquals(1, result.size());
        assertEquals(30, result.get("A"));
    }

    @Test
    public void testBiResultExtractorToMapWithCollectorAndSupplier() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("B", "A");
        when(mockResultSet.getInt(2)).thenReturn(5, 7);

        Jdbc.BiResultExtractor<LinkedHashMap<String, Integer>> extractor = Jdbc.BiResultExtractor.toMap((rs, cols) -> rs.getString(1),
                (rs, cols) -> rs.getInt(2), Collectors.summingInt(Integer::intValue), LinkedHashMap::new);

        LinkedHashMap<String, Integer> result = extractor.apply(mockResultSet, Arrays.asList("category", "amount"));

        assertEquals(2, result.size());
        assertEquals(Arrays.asList("B", "A"), new ArrayList<>(result.keySet()));
        assertEquals(5, result.get("B"));
        assertEquals(7, result.get("A"));
    }

    // deprecated ResultExtractor.toMap(keyExtractor, valueExtractor, downstream) - line 634
    @Test
    public void testResultExtractorToMap_DeprecatedWithCollector() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString("category")).thenReturn("A", "A");
        when(mockResultSet.getInt("value")).thenReturn(10, 20);

        @SuppressWarnings("deprecation")
        Jdbc.ResultExtractor<Map<String, Integer>> extractor = Jdbc.ResultExtractor.toMap(rs -> rs.getString("category"), rs -> rs.getInt("value"),
                Collectors.summingInt(Integer::intValue));

        Map<String, Integer> result = extractor.apply(mockResultSet);
        assertEquals(1, result.size());
        assertEquals(30, result.get("A"));
    }

    // deprecated ResultExtractor.toMap(keyExtractor, valueExtractor, downstream, supplier) - line 656
    @Test
    public void testResultExtractorToMap_DeprecatedWithCollectorAndSupplier() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getString("key")).thenReturn("X");
        when(mockResultSet.getInt("val")).thenReturn(5);

        @SuppressWarnings("deprecation")
        Jdbc.ResultExtractor<LinkedHashMap<String, Integer>> extractor = Jdbc.ResultExtractor.toMap(rs -> rs.getString("key"), rs -> rs.getInt("val"),
                Collectors.summingInt(Integer::intValue), LinkedHashMap::new);

        LinkedHashMap<String, Integer> result = extractor.apply(mockResultSet);
        assertTrue(result instanceof LinkedHashMap);
        assertEquals(1, result.size());
        assertEquals(5, result.get("X"));
    }

    @SuppressWarnings("deprecation")
    @Test
    public void testResultExtractorToMergedList_WithIdPropName() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        Jdbc.ResultExtractor<List<TestEntity>> extractor = Jdbc.ResultExtractor.toMergedList(TestEntity.class, "id");
        assertNotNull(extractor);

        List<TestEntity> result = extractor.apply(mockResultSet);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @SuppressWarnings("deprecation")
    @Test
    public void testResultExtractorToMergedList_WithIdPropNames() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        Jdbc.ResultExtractor<List<TestEntity>> extractor = Jdbc.ResultExtractor.toMergedList(TestEntity.class, Arrays.asList("id", "name"));
        assertNotNull(extractor);

        List<TestEntity> result = extractor.apply(mockResultSet);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @SuppressWarnings("deprecation")
    @Test
    public void testResultExtractorToMergedListCopiesIdPropNames() throws SQLException {
        final List<String> idPropNames = new ArrayList<>(List.of("id"));
        final Jdbc.ResultExtractor<List<TestEntity>> extractor = Jdbc.ResultExtractor.toMergedList(TestEntity.class, idPropNames);
        idPropNames.set(0, "missing");
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getLong(1)).thenReturn(1L);
        when(mockResultSet.getString(2)).thenReturn("John");
        when(mockResultSet.getInt(3)).thenReturn(25);
        when(mockResultSet.getObject(1)).thenReturn(1L);
        when(mockResultSet.getObject(2)).thenReturn("John");
        when(mockResultSet.getObject(3)).thenReturn(25);

        final List<TestEntity> result = extractor.apply(mockResultSet);

        assertEquals(1, result.size());
    }

    @SuppressWarnings("deprecation")
    @Test
    public void testResultExtractorToMergedList_EmptyResultSet() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        Jdbc.ResultExtractor<List<TestEntity>> extractor = Jdbc.ResultExtractor.toMergedList(TestEntity.class);
        assertNotNull(extractor);

        List<TestEntity> result = extractor.apply(mockResultSet);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @SuppressWarnings("deprecation")
    @Test
    public void testResultExtractorToMergedList_NullArgThrows() {
        assertThrows(IllegalArgumentException.class, () -> Jdbc.ResultExtractor.toMergedList(null));
    }

    @Test
    public void testResultExtractorToMultimap() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString("category")).thenReturn("A", "A");
        when(mockResultSet.getInt("value")).thenReturn(10, 20);

        Jdbc.ResultExtractor<ListMultimap<String, Integer>> extractor = Jdbc.ResultExtractor.toMultimap(rs -> rs.getString("category"),
                rs -> rs.getInt("value"));

        ListMultimap<String, Integer> result = extractor.apply(mockResultSet);
        assertEquals(2, result.get("A").size());
        assertTrue(result.get("A").contains(10));
        assertTrue(result.get("A").contains(20));
    }

    @Test
    public void testBiResultExtractorToMultimap() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("A", "A");
        when(mockResultSet.getInt(2)).thenReturn(10, 20);

        List<String> columnLabels = Arrays.asList("category", "value");

        Jdbc.BiResultExtractor<ListMultimap<String, Integer>> extractor = Jdbc.BiResultExtractor.toMultimap((rs, cols) -> rs.getString(1),
                (rs, cols) -> rs.getInt(2));

        ListMultimap<String, Integer> result = extractor.apply(mockResultSet, columnLabels);
        assertEquals(2, result.get("A").size());
    }
}
