package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.IntFunctions;
import com.landawn.abacus.util.ListMultimap;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.NoCachingNoUpdating.DisposableObjArray;
import com.landawn.abacus.util.Suppliers;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.Tuple;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;

public class JdbcTest extends TestBase {

    @Mock
    private ResultSet mockResultSet;

    @Mock
    private PreparedStatement mockPreparedStatement;

    @Mock
    private CallableStatement mockCallableStatement;

    @Mock
    private ResultSetMetaData mockResultSetMetaData;

    @Mock
    private AbstractQuery mockAbstractQuery;

    @Mock
    private ParsedSql mockParsedSql;

    private AutoCloseable mockitoCloseable;

    @BeforeEach
    public void setUp() throws Exception {
        mockitoCloseable = MockitoAnnotations.openMocks(this);
        when(mockResultSet.getMetaData()).thenReturn(mockResultSetMetaData);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(3);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("name");
        when(mockResultSetMetaData.getColumnLabel(3)).thenReturn("age");
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (mockitoCloseable != null) {
            mockitoCloseable.close();
        }
    }

    // Test entity class for testing
    public static class TestEntity {
        private Long id;
        private String name;
        private Integer age;

        public TestEntity() {
        }

        public TestEntity(Long id, String name, Integer age) {
            this.id = id;
            this.name = name;
            this.age = age;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }
    }

    // ParametersSetter Tests
    @Test
    public void testParametersSetterDoNothing() throws SQLException {
        Jdbc.ParametersSetter<Object> setter = Jdbc.ParametersSetter.DO_NOTHING;
        assertDoesNotThrow(() -> setter.accept(mockPreparedStatement));
        verifyNoInteractions(mockPreparedStatement);
    }

    @Test
    public void testParametersSetterAccept() throws SQLException {
        Jdbc.ParametersSetter<PreparedStatement> setter = ps -> ps.setString(1, "test");
        assertDoesNotThrow(() -> setter.accept(mockPreparedStatement));
        verify(mockPreparedStatement).setString(1, "test");
    }

    // BiParametersSetter Tests
    @Test
    public void testBiParametersSetterDoNothing() throws SQLException {
        Jdbc.BiParametersSetter<Object, Object> setter = Jdbc.BiParametersSetter.DO_NOTHING;
        assertDoesNotThrow(() -> setter.accept(mockPreparedStatement, "param"));
        verifyNoInteractions(mockPreparedStatement);
    }

    @Test
    public void testBiParametersSetterAccept() throws SQLException {
        Jdbc.BiParametersSetter<PreparedStatement, String> setter = (ps, param) -> ps.setString(1, param);
        assertDoesNotThrow(() -> setter.accept(mockPreparedStatement, "test"));
        verify(mockPreparedStatement).setString(1, "test");
    }

    @Test
    public void testBiParametersSetterCreateForArray() throws SQLException {
        List<String> fields = Arrays.asList("name", "age");
        Jdbc.BiParametersSetter<PreparedStatement, Object[]> setter = Jdbc.BiParametersSetter.createForArray(fields, TestEntity.class);
        assertNotNull(setter);

        Object[] params = new Object[] { "John", 25 };
        assertDoesNotThrow(() -> setter.accept(mockPreparedStatement, params));

        verify(mockPreparedStatement, times(1)).setString(anyInt(), any());
        verify(mockPreparedStatement, times(1)).setInt(anyInt(), anyInt());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBiParametersSetterCreateForList() throws SQLException {
        List<String> fields = Arrays.asList("name", "age");
        Jdbc.BiParametersSetter<PreparedStatement, List<Object>> setter = Jdbc.BiParametersSetter.createForList(fields, TestEntity.class);
        assertNotNull(setter);

        List<Object> params = Arrays.asList("John", 25);
        setter.accept(mockPreparedStatement, params);

        verify(mockPreparedStatement, times(1)).setString(anyInt(), any());
        verify(mockPreparedStatement, times(1)).setInt(anyInt(), anyInt());
    }

    @Test
    public void testBiParametersSetterCreateForList_InvalidField() {
        List<String> fields = Arrays.asList("nonexistentField");
        Jdbc.BiParametersSetter<PreparedStatement, List<Object>> setter = Jdbc.BiParametersSetter.createForList(fields, TestEntity.class);
        assertNotNull(setter);
        assertThrows(IllegalArgumentException.class, () -> setter.accept(mockPreparedStatement, Arrays.asList("value")));
    }

    @Test
    public void testBiParametersSetterCreateForArray_InvalidField() {
        List<String> fields = Arrays.asList("nonexistentField");
        Jdbc.BiParametersSetter<PreparedStatement, Object[]> setter = Jdbc.BiParametersSetter.createForArray(fields, TestEntity.class);
        assertNotNull(setter);
        assertThrows(IllegalArgumentException.class, () -> setter.accept(mockPreparedStatement, new Object[] { "value" }));
    }

    // TriParametersSetter Tests
    @Test
    public void testTriParametersSetterDoNothing() throws SQLException {
        Jdbc.TriParametersSetter<Object, Object> setter = Jdbc.TriParametersSetter.DO_NOTHING;
        assertDoesNotThrow(() -> setter.accept(mockParsedSql, mockPreparedStatement, "param"));
        verifyNoInteractions(mockPreparedStatement);
    }

    @Test
    public void testTriParametersSetterAccept() throws SQLException {
        Jdbc.TriParametersSetter<PreparedStatement, String> setter = (parsedSql, ps, param) -> ps.setString(1, param);
        assertDoesNotThrow(() -> setter.accept(mockParsedSql, mockPreparedStatement, "test"));
        verify(mockPreparedStatement).setString(1, "test");
    }

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

    @SuppressWarnings("deprecation")
    @Test
    public void testResultExtractorToMap_WithCollector_Deprecated() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString("category")).thenReturn("A", "A");
        when(mockResultSet.getInt("value")).thenReturn(5, 7);

        // 3-arg deprecated toMap delegates to 4-arg toMap which delegates to groupTo
        Jdbc.ResultExtractor<Map<String, Integer>> extractor = Jdbc.ResultExtractor.toMap(
                rs -> rs.getString("category"), rs -> rs.getInt("value"),
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
        Jdbc.ResultExtractor<LinkedHashMap<String, Integer>> extractor = Jdbc.ResultExtractor.toMap(
                rs -> rs.getString("key"), rs -> rs.getInt("val"),
                Collectors.summingInt(Integer::intValue),
                LinkedHashMap::new);

        LinkedHashMap<String, Integer> result = extractor.apply(mockResultSet);
        assertEquals(1, result.size());
        assertEquals(99, result.get("X"));
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

    // RowMapper Tests
    @Test
    public void testRowMapperApply() throws SQLException {
        when(mockResultSet.getString("name")).thenReturn("John");

        Jdbc.RowMapper<String> mapper = rs -> rs.getString("name");
        assertEquals("John", mapper.apply(mockResultSet));
    }

    @Test
    public void testRowMapperAndThen() throws SQLException {
        when(mockResultSet.getString("name")).thenReturn("John");

        Jdbc.RowMapper<String> mapper = rs -> rs.getString("name");
        Jdbc.RowMapper<Integer> composed = mapper.andThen(String::length);
        assertEquals(4, composed.apply(mockResultSet));
    }

    @Test
    public void testRowMapperToBiRowMapper() throws SQLException {
        when(mockResultSet.getString("name")).thenReturn("John");

        Jdbc.RowMapper<String> mapper = rs -> rs.getString("name");
        Jdbc.BiRowMapper<String> biMapper = mapper.toBiRowMapper();
        assertEquals("John", biMapper.apply(mockResultSet, Arrays.asList("name")));
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
    public void testBiRowMapperBuilderWithNullDefaultGetter() {
        assertThrows(IllegalArgumentException.class, () -> Jdbc.BiRowMapper.builder(null));
    }

    @Test
    public void testBiRowMapperBuilder_ToEntity_WithDefaultGetter() throws SQLException {
        // Uses default GET_OBJECT getter → triggers rsColumnGetters[i] = ColumnGetter.get(propType) path
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
    public void testBiRowMapperBuilder_ToEntity_UnmatchedThrows() {
        // Unmatched column with ignoreUnmatchedColumns=false → throws IllegalArgumentException
        Jdbc.BiRowMapper<TestEntity> mapper = Jdbc.BiRowMapper.builder().to(TestEntity.class, false);
        List<String> columnLabels = Arrays.asList("unknown_col");

        assertThrows(IllegalArgumentException.class, () -> mapper.apply(mockResultSet, columnLabels));
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
    public void testBiRowMapperBuilder_ToScalarType_MultiColumnThrows() throws SQLException {
        // Scalar type with multiple columns → throws
        Jdbc.BiRowMapper<String> mapper = Jdbc.BiRowMapper.builder().to(String.class);

        assertThrows(IllegalArgumentException.class,
                () -> mapper.apply(mockResultSet, Arrays.asList("col1", "col2")));
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
    public void testBiRowMapperBuilder_UnknownColumnThrows() throws SQLException {
        Jdbc.BiRowMapper<TestEntity> mapper = Jdbc.BiRowMapper.builder()
                .getString("nonexistent")
                .to(TestEntity.class);

        assertThrows(IllegalArgumentException.class,
                () -> mapper.apply(mockResultSet, Arrays.asList("id", "name", "age")));
    }

    // RowConsumer Tests
    @Test
    public void testRowConsumerDoNothing() throws SQLException {
        assertDoesNotThrow(() -> Jdbc.RowConsumer.DO_NOTHING.accept(mockResultSet));
        verifyNoInteractions(mockResultSet);
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

    @Test
    public void testRowConsumerCreate() throws SQLException {
        List<Integer> columnIndices = new ArrayList<>();
        Jdbc.RowConsumer consumer = Jdbc.RowConsumer.create((rs, colIndex) -> columnIndices.add(colIndex));

        consumer.accept(mockResultSet);

        assertEquals(3, columnIndices.size());
        assertEquals(Arrays.asList(1, 2, 3), columnIndices);
    }

    @Test
    public void testRowConsumerOneOff() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1);
        when(mockResultSet.getObject(2)).thenReturn("John");
        when(mockResultSet.getObject(3)).thenReturn(25);

        List<Object> results = new ArrayList<>();
        Jdbc.RowConsumer consumer = Jdbc.RowConsumer.oneOff(arr -> {
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
    public void testRowConsumerOneOff_WithEntityClass() throws SQLException {
        when(mockResultSet.getLong(1)).thenReturn(99L);
        when(mockResultSet.getString(2)).thenReturn("Kim");
        when(mockResultSet.getObject(3)).thenReturn(null);

        List<Object> results = new ArrayList<>();
        Jdbc.RowConsumer consumer = Jdbc.RowConsumer.oneOff(TestEntity.class, arr -> {
            for (int i = 0; i < arr.length(); i++) {
                results.add(arr.get(i));
            }
        });

        consumer.accept(mockResultSet);

        assertEquals(3, results.size());
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
    public void testBiRowConsumerCreate() throws SQLException {
        List<Integer> columnIndices = new ArrayList<>();
        Jdbc.BiRowConsumer consumer = Jdbc.BiRowConsumer.create((rs, colIndex) -> columnIndices.add(colIndex));

        consumer.accept(mockResultSet, Arrays.asList("col1", "col2"));

        assertEquals(2, columnIndices.size());
        assertEquals(Arrays.asList(1, 2), columnIndices);
    }

    @Test
    public void testBiRowConsumerOneOff() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1);
        when(mockResultSet.getObject(2)).thenReturn("John");

        List<Object> results = new ArrayList<>();
        List<String> capturedLabels = new ArrayList<>();

        Jdbc.BiRowConsumer consumer = Jdbc.BiRowConsumer.oneOff((cols, arr) -> {
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
    public void testBiRowConsumerOneOff_WithEntityClass() throws SQLException {
        when(mockResultSet.getLong(1)).thenReturn(5L);
        when(mockResultSet.getString(2)).thenReturn("Leo");
        when(mockResultSet.getObject(3)).thenReturn(null);

        List<Object> results = new ArrayList<>();
        List<String> capturedLabels = new ArrayList<>();

        Jdbc.BiRowConsumer consumer = Jdbc.BiRowConsumer.oneOff(TestEntity.class, (cols, arr) -> {
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

    // RowFilter Tests
    @Test
    public void testRowFilterAlwaysTrue() throws SQLException {
        assertTrue(Jdbc.RowFilter.ALWAYS_TRUE.test(mockResultSet));
    }

    @Test
    public void testRowFilterAlwaysFalse() throws SQLException {
        assertFalse(Jdbc.RowFilter.ALWAYS_FALSE.test(mockResultSet));
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
    public void testRowFilterNegate() throws SQLException {
        when(mockResultSet.getBoolean("active")).thenReturn(true);

        Jdbc.RowFilter filter = rs -> rs.getBoolean("active");
        Jdbc.RowFilter negated = filter.negate();

        assertTrue(filter.test(mockResultSet));
        assertFalse(negated.test(mockResultSet));
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
    public void testRowFilterToBiRowFilter() throws SQLException {
        when(mockResultSet.getInt("age")).thenReturn(20);

        Jdbc.RowFilter filter = rs -> rs.getInt("age") >= 18;
        Jdbc.BiRowFilter biFilter = filter.toBiRowFilter();

        assertTrue(biFilter.test(mockResultSet, Arrays.asList("age")));
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

    // BiRowFilter Tests
    @Test
    public void testBiRowFilterAlwaysTrue() throws SQLException {
        assertTrue(Jdbc.BiRowFilter.ALWAYS_TRUE.test(mockResultSet, Arrays.asList("col1")));
    }

    @Test
    public void testBiRowFilterAlwaysFalse() throws SQLException {
        assertFalse(Jdbc.BiRowFilter.ALWAYS_FALSE.test(mockResultSet, Arrays.asList("col1")));
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
    public void testBiRowFilterNegate() throws SQLException {
        Jdbc.BiRowFilter filter = (rs, cols) -> cols.size() > 2;
        Jdbc.BiRowFilter negated = filter.negate();

        assertTrue(filter.test(mockResultSet, Arrays.asList("a", "b", "c")));
        assertFalse(negated.test(mockResultSet, Arrays.asList("a", "b", "c")));

        assertFalse(filter.test(mockResultSet, Arrays.asList("a", "b")));
        assertTrue(negated.test(mockResultSet, Arrays.asList("a", "b")));
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
    public void testRowExtractorCreateBy() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1L);
        when(mockResultSet.getString(2)).thenReturn("John");
        when(mockResultSet.getObject(3)).thenReturn(25);

        Jdbc.RowExtractor extractor = Jdbc.RowExtractor.createBy(TestEntity.class);
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
        Jdbc.RowExtractor extractor = Jdbc.RowExtractor.createBy(TestEntity.class, columnLabels);
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

        Jdbc.RowExtractor extractor = Jdbc.RowExtractor.createBy(TestEntity.class, new HashMap<>());
        Object[] outputRow = new Object[3];

        extractor.accept(mockResultSet, outputRow);

        assertNotNull(extractor);
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
    public void testRowExtractorBuilderRejectsShortOutputArrayAfterInitialization() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(1);
        when(mockResultSet.getObject(2)).thenReturn(2);
        when(mockResultSet.getObject(3)).thenReturn(3);

        Jdbc.RowExtractor extractor = Jdbc.RowExtractor.builder().build();

        extractor.accept(mockResultSet, new Object[3]);

        assertThrows(IllegalArgumentException.class, () -> extractor.accept(mockResultSet, new Object[2]));
    }

    // ColumnGetter Tests
    @Test
    public void testColumnGetterPredefined() throws SQLException {
        when(mockResultSet.getBoolean(1)).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn("test");
        when(mockResultSet.getInt(1)).thenReturn(42);

        assertEquals(true, Jdbc.ColumnGetter.GET_BOOLEAN.apply(mockResultSet, 1));
        assertEquals("test", Jdbc.ColumnGetter.GET_STRING.apply(mockResultSet, 1));
        assertEquals(42, Jdbc.ColumnGetter.GET_INT.apply(mockResultSet, 1));
    }

    @Test
    public void testColumnGetterGet() throws SQLException {
        when(mockResultSet.getString(1)).thenReturn("test");
        when(mockResultSet.getInt(1)).thenReturn(42);

        Jdbc.ColumnGetter<String> stringGetter = Jdbc.ColumnGetter.get(String.class);
        Jdbc.ColumnGetter<Integer> intGetter = Jdbc.ColumnGetter.get(Integer.class);

        assertEquals("test", stringGetter.apply(mockResultSet, 1));
        assertEquals(42, intGetter.apply(mockResultSet, 1));
    }

    // Columns.ColumnOne Tests
    @Test
    public void testColumnOneGetters() throws SQLException {
        when(mockResultSet.getBoolean(1)).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn("test");
        when(mockResultSet.getInt(1)).thenReturn(42);
        when(mockResultSet.getTimestamp(1)).thenReturn(new Timestamp(System.currentTimeMillis()));

        assertEquals(true, Jdbc.Columns.ColumnOne.GET_BOOLEAN.apply(mockResultSet));
        assertEquals("test", Jdbc.Columns.ColumnOne.GET_STRING.apply(mockResultSet));
        assertEquals(42, Jdbc.Columns.ColumnOne.GET_INT.apply(mockResultSet));
        assertNotNull(Jdbc.Columns.ColumnOne.GET_TIMESTAMP.apply(mockResultSet));
    }

    //    @Test
    //    public void testColumnOneSetters() throws SQLException {
    //        Jdbc.Columns.ColumnOne.SET_STRING.accept(mockAbstractQuery, "test");
    //        Jdbc.Columns.ColumnOne.SET_INT.accept(mockAbstractQuery, 42);
    //        Jdbc.Columns.ColumnOne.SET_BOOLEAN.accept(mockAbstractQuery, true);
    //
    //        verify(mockAbstractQuery).setString(1, "test");
    //        verify(mockAbstractQuery).setInt(1, 42);
    //        verify(mockAbstractQuery).setBoolean(1, true);
    //    }

    @Test
    public void testColumnOneGetObject() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn("test");

        Jdbc.RowMapper<Object> mapper = Jdbc.Columns.ColumnOne.getObject();
        assertEquals("test", mapper.apply(mockResultSet));
    }

    @Test
    public void testColumnOneGet() throws SQLException {
        when(mockResultSet.getString(1)).thenReturn("test");
        when(mockResultSet.getObject(1)).thenReturn(42);

        Jdbc.RowMapper<String> stringMapper = Jdbc.Columns.ColumnOne.get(String.class);
        Jdbc.RowMapper<Integer> intMapper = Jdbc.Columns.ColumnOne.get(Integer.class);

        assertEquals("test", stringMapper.apply(mockResultSet));
        assertEquals(42, intMapper.apply(mockResultSet));
    }

    @Test
    public void testColumnOneReadJson() throws SQLException {
        when(mockResultSet.getString(1)).thenReturn("{\"id\":1,\"name\":\"John\",\"age\":25}");

        Jdbc.RowMapper<TestEntity> mapper = Jdbc.Columns.ColumnOne.readJson(TestEntity.class);
        TestEntity result = mapper.apply(mockResultSet);

        assertNotNull(result);
        assertEquals(1L, result.getId().longValue());
        assertEquals("John", result.getName());
        assertEquals(25, result.getAge().intValue());
    }

    @Test
    public void testColumnOneReadXml() throws SQLException {
        String xml = "<TestEntity><id>1</id><name>John</name><age>25</age></TestEntity>";
        when(mockResultSet.getString(1)).thenReturn(xml);

        Jdbc.RowMapper<TestEntity> mapper = Jdbc.Columns.ColumnOne.readXml(TestEntity.class);
        TestEntity result = mapper.apply(mockResultSet);

        assertNotNull(result);
        // Verify XML deserialization produced correct field values
        assertEquals(1L, result.getId().longValue());
        assertEquals("John", result.getName());
        assertEquals(25, result.getAge().intValue());
    }

    @Test
    public void testColumnOneSet_ByClass() {
        // Just creating the setter covers the factory method lines (lambda not invoked)
        Jdbc.BiParametersSetter<AbstractQuery, String> stringSetter = Jdbc.Columns.ColumnOne.set(String.class);
        Jdbc.BiParametersSetter<AbstractQuery, Integer> intSetter = Jdbc.Columns.ColumnOne.set(Integer.class);

        assertNotNull(stringSetter);
        assertNotNull(intSetter);
    }

    @Test
    public void testColumnOneSet_ByType() {
        // Just creating the setter covers the factory method lambda creation line
        @SuppressWarnings("rawtypes")
        com.landawn.abacus.type.Type<String> stringType = com.landawn.abacus.type.TypeFactory.getType(String.class);
        Jdbc.BiParametersSetter<AbstractQuery, String> setter = Jdbc.Columns.ColumnOne.set(stringType);

        assertNotNull(setter);
    }

    @Test
    public void testColumnOneRemainingGetters() throws SQLException {
        when(mockResultSet.getByte(1)).thenReturn((byte) 7);
        when(mockResultSet.getShort(1)).thenReturn((short) 200);
        when(mockResultSet.getLong(1)).thenReturn(9999L);
        when(mockResultSet.getFloat(1)).thenReturn(1.5f);
        when(mockResultSet.getDouble(1)).thenReturn(3.14);
        when(mockResultSet.getBigDecimal(1)).thenReturn(new BigDecimal("12.34"));
        when(mockResultSet.getDate(1)).thenReturn(new Date(0L));
        when(mockResultSet.getTime(1)).thenReturn(new Time(0L));
        when(mockResultSet.getBytes(1)).thenReturn(new byte[] { 1, 2 });

        assertEquals((byte) 7, Jdbc.Columns.ColumnOne.GET_BYTE.apply(mockResultSet));
        assertEquals((short) 200, Jdbc.Columns.ColumnOne.GET_SHORT.apply(mockResultSet));
        assertEquals(9999L, Jdbc.Columns.ColumnOne.GET_LONG.apply(mockResultSet));
        assertEquals(1.5f, Jdbc.Columns.ColumnOne.GET_FLOAT.apply(mockResultSet));
        assertEquals(3.14, Jdbc.Columns.ColumnOne.GET_DOUBLE.apply(mockResultSet));
        assertEquals(new BigDecimal("12.34"), Jdbc.Columns.ColumnOne.GET_BIG_DECIMAL.apply(mockResultSet));
        assertNotNull(Jdbc.Columns.ColumnOne.GET_DATE.apply(mockResultSet));
        assertNotNull(Jdbc.Columns.ColumnOne.GET_TIME.apply(mockResultSet));
        assertEquals(2, Jdbc.Columns.ColumnOne.GET_BYTES.apply(mockResultSet).length);
    }

    @Test
    public void testColumnOneStreamGetters() throws SQLException {
        final java.io.InputStream binaryStream = new java.io.ByteArrayInputStream(new byte[0]);
        final java.io.Reader charStream = new java.io.StringReader("text");
        final java.sql.Blob blob = Mockito.mock(java.sql.Blob.class);
        final java.sql.Clob clob = Mockito.mock(java.sql.Clob.class);

        when(mockResultSet.getBinaryStream(1)).thenReturn(binaryStream);
        when(mockResultSet.getCharacterStream(1)).thenReturn(charStream);
        when(mockResultSet.getBlob(1)).thenReturn(blob);
        when(mockResultSet.getClob(1)).thenReturn(clob);

        assertSame(binaryStream, Jdbc.Columns.ColumnOne.GET_BINARY_STREAM.apply(mockResultSet));
        assertSame(charStream, Jdbc.Columns.ColumnOne.GET_CHARACTER_STREAM.apply(mockResultSet));
        assertSame(blob, Jdbc.Columns.ColumnOne.GET_BLOB.apply(mockResultSet));
        assertSame(clob, Jdbc.Columns.ColumnOne.GET_CLOB.apply(mockResultSet));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Test
    public void testColumnOneSetters() throws SQLException {
        final java.sql.Date sqlDate = new Date(0L);
        final Time sqlTime = new Time(0L);
        final Timestamp sqlTimestamp = new Timestamp(0L);
        final java.util.Date juDate = new java.util.Date(0L);
        final byte[] bytes = new byte[] { 1 };
        final java.sql.Blob blob = Mockito.mock(java.sql.Blob.class);
        final java.sql.Clob clob = Mockito.mock(java.sql.Clob.class);

        Jdbc.Columns.ColumnOne.SET_BOOLEAN.accept(mockAbstractQuery, true);
        Jdbc.Columns.ColumnOne.SET_BYTE.accept(mockAbstractQuery, (byte) 1);
        Jdbc.Columns.ColumnOne.SET_SHORT.accept(mockAbstractQuery, (short) 2);
        Jdbc.Columns.ColumnOne.SET_INT.accept(mockAbstractQuery, 3);
        Jdbc.Columns.ColumnOne.SET_LONG.accept(mockAbstractQuery, 4L);
        Jdbc.Columns.ColumnOne.SET_FLOAT.accept(mockAbstractQuery, 5.0f);
        Jdbc.Columns.ColumnOne.SET_DOUBLE.accept(mockAbstractQuery, 6.0);
        Jdbc.Columns.ColumnOne.SET_BIG_DECIMAL.accept(mockAbstractQuery, new BigDecimal("7"));
        Jdbc.Columns.ColumnOne.SET_STRING.accept(mockAbstractQuery, "eight");
        Jdbc.Columns.ColumnOne.SET_DATE.accept(mockAbstractQuery, sqlDate);
        Jdbc.Columns.ColumnOne.SET_TIME.accept(mockAbstractQuery, sqlTime);
        Jdbc.Columns.ColumnOne.SET_TIMESTAMP.accept(mockAbstractQuery, sqlTimestamp);
        Jdbc.Columns.ColumnOne.SET_JU_DATE.accept(mockAbstractQuery, juDate);
        Jdbc.Columns.ColumnOne.SET_JU_TIME.accept(mockAbstractQuery, juDate);
        Jdbc.Columns.ColumnOne.SET_JU_TIMESTAMP.accept(mockAbstractQuery, juDate);
        Jdbc.Columns.ColumnOne.SET_BYTES.accept(mockAbstractQuery, bytes);
        Jdbc.Columns.ColumnOne.SET_BLOB.accept(mockAbstractQuery, blob);
        Jdbc.Columns.ColumnOne.SET_CLOB.accept(mockAbstractQuery, clob);
        Jdbc.Columns.ColumnOne.SET_OBJECT.accept(mockAbstractQuery, "obj");

        verify(mockAbstractQuery).setBoolean(1, Boolean.TRUE);
        verify(mockAbstractQuery).setByte(1, Byte.valueOf((byte) 1));
        verify(mockAbstractQuery).setShort(1, Short.valueOf((short) 2));
        verify(mockAbstractQuery).setInt(1, Integer.valueOf(3));
        verify(mockAbstractQuery).setLong(1, Long.valueOf(4L));
        verify(mockAbstractQuery).setFloat(1, Float.valueOf(5.0f));
        verify(mockAbstractQuery).setDouble(1, Double.valueOf(6.0));
        verify(mockAbstractQuery).setBigDecimal(1, new BigDecimal("7"));
        verify(mockAbstractQuery).setString(1, "eight");
        verify(mockAbstractQuery).setDate(1, sqlDate);
        verify(mockAbstractQuery).setTime(1, sqlTime);
        verify(mockAbstractQuery).setTimestamp(1, sqlTimestamp);
        verify(mockAbstractQuery).setDate(1, juDate);
        verify(mockAbstractQuery).setTime(1, juDate);
        verify(mockAbstractQuery).setTimestamp(1, juDate);
        verify(mockAbstractQuery).setBytes(1, bytes);
        verify(mockAbstractQuery).setBlob(1, blob);
        verify(mockAbstractQuery).setClob(1, clob);
        verify(mockAbstractQuery).setObject(1, (Object) "obj");
    }

    // OutParam Tests
    @Test
    public void testOutParam() {
        Jdbc.OutParam param = new Jdbc.OutParam();
        param.setParameterIndex(1);
        param.setParameterName("result");
        param.setSqlType(Types.INTEGER);
        param.setTypeName("INTEGER");
        param.setScale(0);

        assertEquals(1, param.getParameterIndex());
        assertEquals("result", param.getParameterName());
        assertEquals(Types.INTEGER, param.getSqlType());
        assertEquals("INTEGER", param.getTypeName());
        assertEquals(0, param.getScale());
    }

    @Test
    public void testOutParamOf() {
        Jdbc.OutParam param = Jdbc.OutParam.of(1, Types.VARCHAR);

        assertEquals(1, param.getParameterIndex());
        assertEquals(Types.VARCHAR, param.getSqlType());
    }

    @Test
    public void testOutParamConstructors() {
        Jdbc.OutParam param1 = new Jdbc.OutParam();
        assertNotNull(param1);

        Jdbc.OutParam param2 = new Jdbc.OutParam(2, "name", Types.VARCHAR, "VARCHAR", 0);
        assertEquals(2, param2.getParameterIndex());
        assertEquals("name", param2.getParameterName());
        assertEquals(Types.VARCHAR, param2.getSqlType());
    }

    // OutParamResult Tests
    @Test
    public void testOutParamResult() {
        List<Jdbc.OutParam> outParams = Arrays.asList(new Jdbc.OutParam(1, "result", Types.INTEGER, null, 0),
                new Jdbc.OutParam(2, "message", Types.VARCHAR, null, 0));

        Map<Object, Object> values = new HashMap<>();
        values.put(1, 100);
        values.put("message", "Success");

        Jdbc.OutParamResult result = new Jdbc.OutParamResult(outParams, values);

        assertEquals(100, (Integer) result.getOutParamValue(1));
        assertEquals("Success", result.getOutParamValue("message"));
        assertEquals(outParams, result.getOutParams());
        assertEquals(values, result.getOutParamValues());
    }

    // Handler Tests
    @Test
    public void testHandlerDefaultMethods() {
        Jdbc.Handler<Object> handler = new Jdbc.Handler<>() {
        };

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        // Default beforeInvoke and afterInvoke should be no-ops that complete without error
        handler.beforeInvoke(new Object(), new Object[0], methodSignature);
        handler.afterInvoke("result", new Object(), new Object[0], methodSignature);

        // Verify the handler is an instance of the Handler interface
        assertTrue(handler instanceof Jdbc.Handler);
        // Verify the method signature tuple was constructed correctly
        assertSame(method, methodSignature._1);
        assertEquals(0, paramTypes.size());
        assertEquals(Object.class, methodSignature._3);
    }

    static final class RegisterByClassHandler implements Jdbc.Handler<Object> {
    }

    static final class GetOrCreateHandler implements Jdbc.Handler<Object> {
    }

    // HandlerFactory Tests
    @Test
    @SuppressWarnings("unchecked")
    public void testHandlerFactoryRegisterByClass() {
        // register by class - first time should succeed
        Jdbc.HandlerFactory.register(RegisterByClassHandler.class);
        Jdbc.Handler<?> retrieved = Jdbc.HandlerFactory.get(RegisterByClassHandler.class);
        assertNotNull(retrieved);
        assertTrue(retrieved instanceof RegisterByClassHandler);

        // second registration should return false
        assertFalse(Jdbc.HandlerFactory.register(RegisterByClassHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testHandlerFactoryGetOrCreate() {
        Jdbc.Handler<?> handler = Jdbc.HandlerFactory.getOrCreate(GetOrCreateHandler.class);
        assertNotNull(handler);

        // second call should return the same instance
        Jdbc.Handler<?> handler2 = Jdbc.HandlerFactory.getOrCreate(GetOrCreateHandler.class);
        assertSame(handler, handler2);
    }

    //    @Test
    //    public void testHandlerFactoryRegisterClass() {
    //        class TestHandler implements Jdbc.Handler<Object> {
    //        }
    //
    //        boolean registered = Jdbc.HandlerFactory.register(TestHandler.class);
    //        assertTrue(registered);
    //
    //        // Second registration should fail
    //        assertFalse(Jdbc.HandlerFactory.register(TestHandler.class));
    //    }

    @Test
    public void testHandlerFactoryRegisterInstance() {
        Jdbc.Handler<Object> handler = new Jdbc.Handler<>() {
        };

        boolean registered = Jdbc.HandlerFactory.register(handler);
        assertTrue(registered);
    }

    @Test
    public void testHandlerFactoryRegisterWithQualifier() {
        Jdbc.Handler<Object> handler = new Jdbc.Handler<>() {
        };

        boolean registered = Jdbc.HandlerFactory.register("testHandler", handler);
        assertTrue(registered);

        // Same qualifier should fail
        assertFalse(Jdbc.HandlerFactory.register("testHandler", handler));
    }

    @Test
    public void testHandlerFactoryGet() {
        Jdbc.Handler<Object> handler = new Jdbc.Handler<>() {
        };
        String qualifier = "getTestHandler";

        Jdbc.HandlerFactory.register(qualifier, handler);
        Jdbc.Handler<?> retrieved = Jdbc.HandlerFactory.get(qualifier);

        assertSame(handler, retrieved);
    }

    //    @Test
    //    public void testHandlerFactoryGetByClass() {
    //        class NamedHandler implements Jdbc.Handler<Object> {
    //        }
    //
    //        Jdbc.HandlerFactory.register(NamedHandler.class);
    //        Jdbc.Handler<?> retrieved = Jdbc.HandlerFactory.get(NamedHandler.class);
    //
    //        assertNotNull(retrieved);
    //    }

    //    @Test
    //    public void testHandlerFactoryGetOrCreate() {
    //        class AutoHandler implements Jdbc.Handler<Object> {
    //        }
    //
    //        Jdbc.Handler<?> handler = Jdbc.HandlerFactory.getOrCreate(AutoHandler.class);
    //        assertNotNull(handler);
    //
    //        // Should get the same instance
    //        Jdbc.Handler<?> handler2 = Jdbc.HandlerFactory.getOrCreate(AutoHandler.class);
    //        assertSame(handler, handler2);
    //    }

    @Test
    public void testHandlerFactoryCreateWithBeforeInvoke() {
        List<String> calls = new ArrayList<>();

        Jdbc.Handler<Object> handler = Jdbc.HandlerFactory.create((target, args, methodSig) -> calls.add("before"));

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        handler.beforeInvoke(new Object(), new Object[0], methodSignature);

        assertEquals(1, calls.size());
        assertEquals("before", calls.get(0));
    }

    @Test
    public void testHandlerFactoryCreateWithAfterInvoke() {
        List<String> calls = new ArrayList<>();

        Jdbc.Handler<Object> handler = Jdbc.HandlerFactory
                .create((Throwables.QuadConsumer<Object, Object, Object[], Tuple3<Method, ImmutableList<Class<?>>, Class<?>>, RuntimeException>) (result,
                        target, args, methodSig) -> calls.add("after"));

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        handler.afterInvoke("result", new Object(), new Object[0], methodSignature);

        assertEquals(1, calls.size());
        assertEquals("after", calls.get(0));
    }

    @Test
    public void testHandlerFactoryCreateWithBoth() {
        List<String> calls = new ArrayList<>();

        Jdbc.Handler<Object> handler = Jdbc.HandlerFactory.create((target, args, methodSig) -> calls.add("before"),
                (result, target, args, methodSig) -> calls.add("after"));

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        handler.beforeInvoke(new Object(), new Object[0], methodSignature);
        handler.afterInvoke("result", new Object(), new Object[0], methodSignature);

        assertEquals(2, calls.size());
        assertEquals("before", calls.get(0));
        assertEquals("after", calls.get(1));
    }

    // DaoCache Tests
    @Test
    public void testDaoCacheCreate() {
        Jdbc.DaoCache cache = Jdbc.DaoCache.create(100, 1000);
        assertNotNull(cache);
        assertTrue(cache instanceof Jdbc.DefaultDaoCache);
    }

    @Test
    public void testDaoCacheCreateByMap() {
        Jdbc.DaoCache cache = Jdbc.DaoCache.createByMap();
        assertNotNull(cache);
        // Verify the factory produces a DaoCacheByMap instance
        assertTrue(cache instanceof Jdbc.DaoCacheByMap);
        // Verify cache starts empty (get returns null for unknown key)
        assertNull(cache.get("nonexistent", null, null, null));
    }

    @Test
    public void testDaoCacheCreateByMapWithMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("preloaded", "value1");
        Jdbc.DaoCache cache = Jdbc.DaoCache.createByMap(map);
        assertNotNull(cache);
        assertTrue(cache instanceof Jdbc.DaoCacheByMap);
        // Verify the cache is backed by the provided map and can see pre-existing entries
        assertEquals("value1", cache.get("preloaded", null, null, null));
    }

    @Test
    public void testDaoCacheCreateByMapWithNullMap() {
        assertThrows(IllegalArgumentException.class, () -> Jdbc.DaoCache.createByMap(null));
        assertThrows(IllegalArgumentException.class, () -> new Jdbc.DaoCacheByMap(null));
    }

    // DefaultDaoCache Tests
    @Test
    public void testDefaultDaoCacheGetPut() throws Exception {
        Jdbc.DefaultDaoCache cache = new Jdbc.DefaultDaoCache(100, 1000);

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        String cacheKey = "testKey";
        Object result = "testResult";

        // Initially should be null
        assertNull(cache.get(cacheKey, null, null, methodSignature));

        // Put and get
        assertTrue(cache.put(cacheKey, result, null, null, methodSignature));
        assertEquals(result, cache.get(cacheKey, null, null, methodSignature));
    }

    @Test
    public void testDefaultDaoCachePutWithTTL() throws Exception {
        Jdbc.DefaultDaoCache cache = new Jdbc.DefaultDaoCache(100, 1000);

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        String cacheKey = "testKey";
        Object result = "testResult";

        assertTrue(cache.put(cacheKey, result, 5000, 3000, null, null, methodSignature));
        assertEquals(result, cache.get(cacheKey, null, null, methodSignature));
    }

    @Test
    public void testDefaultDaoCacheUpdate() throws Exception {
        Jdbc.DefaultDaoCache cache = new Jdbc.DefaultDaoCache(100, 1000);

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        // Put some entries
        cache.put("method#users#params1", "result1", null, null, methodSignature);
        cache.put("method#products#params2", "result2", null, null, methodSignature);
        cache.put("method#users#params3", "result3", null, null, methodSignature);

        // Update with table name
        cache.update("method#users#updateParams", 1, null, null, methodSignature);

        // Users entries should be removed
        assertNull(cache.get("method#users#params1", null, null, methodSignature));
        assertNull(cache.get("method#users#params3", null, null, methodSignature));
        // Products entry should remain
        assertEquals("result2", cache.get("method#products#params2", null, null, methodSignature));
    }

    // DaoCacheByMap Tests
    @Test
    public void testDaoCacheByMapGetPut() throws Exception {
        Jdbc.DaoCacheByMap cache = new Jdbc.DaoCacheByMap();

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        String cacheKey = "testKey";
        Object result = "testResult";

        // Initially should be null
        assertNull(cache.get(cacheKey, null, null, methodSignature));

        // Put and get
        assertTrue(cache.put(cacheKey, result, null, null, methodSignature));
        assertEquals(result, cache.get(cacheKey, null, null, methodSignature));
    }

    @Test
    public void testDaoCacheByMapPutWithTTL() throws Exception {
        Jdbc.DaoCacheByMap cache = new Jdbc.DaoCacheByMap();

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        String cacheKey = "testKey";
        Object result = "testResult";

        // TTL is ignored in map implementation
        assertTrue(cache.put(cacheKey, result, 5000, 3000, null, null, methodSignature));
        assertEquals(result, cache.get(cacheKey, null, null, methodSignature));
    }

    @Test
    public void testDaoCacheByMapUpdate() throws Exception {
        Jdbc.DaoCacheByMap cache = new Jdbc.DaoCacheByMap();

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        // Put some entries
        cache.put("method#users#params1", "result1", null, null, methodSignature);
        cache.put("method#products#params2", "result2", null, null, methodSignature);
        cache.put("method#users#params3", "result3", null, null, methodSignature);

        // Update with table name
        cache.update("method#users#updateParams", 1, null, null, methodSignature);

        // Users entries should be removed
        assertNull(cache.get("method#users#params1", null, null, methodSignature));
        assertNull(cache.get("method#users#params3", null, null, methodSignature));
        // Products entry should remain
        assertEquals("result2", cache.get("method#products#params2", null, null, methodSignature));
    }

    @Test
    public void testDaoCacheByMapConstructors() {
        // Default constructor: empty cache backed by ConcurrentHashMap
        Jdbc.DaoCacheByMap cache1 = new Jdbc.DaoCacheByMap();
        assertNotNull(cache1);
        assertTrue(cache1.cache().isEmpty());

        // Capacity constructor: empty cache with initial capacity
        Jdbc.DaoCacheByMap cache2 = new Jdbc.DaoCacheByMap(50);
        assertNotNull(cache2);
        assertTrue(cache2.cache().isEmpty());

        // Map constructor: cache backed by the provided map
        Map<String, Object> map = new HashMap<>();
        map.put("key1", "val1");
        Jdbc.DaoCacheByMap cache3 = new Jdbc.DaoCacheByMap(map);
        assertNotNull(cache3);
        // Verify the backing map is the same instance and contains the pre-loaded entry
        assertSame(map, cache3.cache());
        assertEquals(1, cache3.cache().size());
        assertEquals("val1", cache3.cache().get("key1"));
    }

    @Test
    public void testDaoCacheByMapPutNullResult() throws Exception {
        Jdbc.DaoCacheByMap cache = new Jdbc.DaoCacheByMap();

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        // null result should return false and not be stored
        assertFalse(cache.put("key1", null, null, null, methodSignature));
        assertNull(cache.get("key1", null, null, methodSignature));

        // null result with TTL should return false too
        assertFalse(cache.put("key2", null, 5000, 3000, null, null, methodSignature));
        assertNull(cache.get("key2", null, null, methodSignature));
    }

    @Test
    public void testDaoCacheByMapUpdate_EmptyTableName() throws Exception {
        Jdbc.DaoCacheByMap cache = new Jdbc.DaoCacheByMap();

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        cache.put("entry1", "val1", null, null, methodSignature);
        cache.put("entry2", "val2", null, null, methodSignature);

        // key without '#' → empty table name → entire cache cleared
        cache.update("simplekey", 1, null, null, methodSignature);

        assertNull(cache.get("entry1", null, null, methodSignature));
        assertNull(cache.get("entry2", null, null, methodSignature));
    }

    @Test
    public void testDaoCacheByMapUpdate_BuiltInUpdateMethod_ZeroResult() throws Exception {
        Jdbc.DaoCacheByMap cache = new Jdbc.DaoCacheByMap();

        // Get an actual method from BUILT_IN_DAO_UPDATE_METHODS
        Method builtInUpdateMethod = JdbcUtil.BUILT_IN_DAO_UPDATE_METHODS.stream()
                .filter(m -> m.getName().equals("update") && m.getParameterCount() == 1)
                .findFirst().orElse(null);

        if (builtInUpdateMethod != null) {
            ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
            Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(builtInUpdateMethod, paramTypes, int.class);

            cache.put("method#users#params", "cached", null, null, methodSignature);

            // result=0 with BUILT_IN_DAO_UPDATE_METHODS method → early return, cache not cleared
            cache.update("method#users#update", 0, null, null, methodSignature);

            // Cache should NOT be cleared (early return occurred)
            assertEquals("cached", cache.get("method#users#params", null, null, methodSignature));
        }
    }

    @Test
    public void testDefaultDaoCacheUpdate_EmptyTableName() throws Exception {
        Jdbc.DefaultDaoCache cache = new Jdbc.DefaultDaoCache(100, 1000);

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        cache.put("entry1", "val1", null, null, methodSignature);
        cache.put("entry2", "val2", null, null, methodSignature);

        // key without '#' → empty table name → entire pool cleared
        cache.update("simplekey", 1, null, null, methodSignature);

        assertNull(cache.get("entry1", null, null, methodSignature));
        assertNull(cache.get("entry2", null, null, methodSignature));
    }

    @Test
    public void testDefaultDaoCacheUpdate_BuiltInUpdateMethod_ZeroResult() throws Exception {
        Jdbc.DefaultDaoCache cache = new Jdbc.DefaultDaoCache(100, 1000);

        // Get an actual method from BUILT_IN_DAO_UPDATE_METHODS (update method from CrudDao)
        Method builtInUpdateMethod = JdbcUtil.BUILT_IN_DAO_UPDATE_METHODS.stream()
                .filter(m -> m.getName().equals("update") && m.getParameterCount() == 1)
                .findFirst().orElse(null);

        if (builtInUpdateMethod != null) {
            ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
            // Return type is int.class
            Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(builtInUpdateMethod, paramTypes, int.class);

            cache.put("method#users#params", "cached", null, null, methodSignature);

            // result=0 with BUILT_IN_DAO_UPDATE_METHODS method → early return, cache not cleared
            cache.update("method#users#update", 0, null, null, methodSignature);

            // Cache should NOT be cleared (early return occurred)
            assertEquals("cached", cache.get("method#users#params", null, null, methodSignature));
        }
    }

    @Test
    public void testDefaultDaoCacheUpdate_BuiltInUpdateMethod_NonZeroResult() throws Exception {
        Jdbc.DefaultDaoCache cache = new Jdbc.DefaultDaoCache(100, 1000);

        // Get an actual method from BUILT_IN_DAO_UPDATE_METHODS
        Method builtInUpdateMethod = JdbcUtil.BUILT_IN_DAO_UPDATE_METHODS.stream()
                .filter(m -> m.getName().equals("update") && m.getParameterCount() == 1)
                .findFirst().orElse(null);

        if (builtInUpdateMethod != null) {
            ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
            Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(builtInUpdateMethod, paramTypes, int.class);

            cache.put("method#users#params", "cached", null, null, methodSignature);

            // result=1 (non-zero) → does NOT early return → cache is cleared
            cache.update("method#users#update", 1, null, null, methodSignature);

            // Cache should be cleared
            assertNull(cache.get("method#users#params", null, null, methodSignature));
        }
    }

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
    public void testInvalidColumnIndex() {
        assertThrows(IllegalArgumentException.class, () -> {
            Jdbc.RowMapper.builder().getInt(0); // Invalid index (should be 1-based)
        });

        assertThrows(IllegalArgumentException.class, () -> {
            Jdbc.RowMapper.builder().get(0, Jdbc.ColumnGetter.GET_INT);
        });
    }

    @Test
    public void testNullArguments() {
        assertThrows(IllegalArgumentException.class, () -> {
            Jdbc.BiParametersSetter.createForArray(null, TestEntity.class);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            Jdbc.BiParametersSetter.createForArray(Arrays.asList("field"), null);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            Jdbc.ResultExtractor.toMap(null, rs -> rs.getString("value"));
        });

        assertThrows(IllegalArgumentException.class, () -> {
            Jdbc.RowMapper.builder(null);
        });
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
    public void testTypeConversions() throws SQLException {
        // Test various type conversions through ColumnGetter
        when(mockResultSet.getBigDecimal(1)).thenReturn(new BigDecimal("123.45"));

        Jdbc.ColumnGetter<BigDecimal> bigDecimalGetter = Jdbc.ColumnGetter.get(BigDecimal.class);
        BigDecimal bd = bigDecimalGetter.apply(mockResultSet, 1);
        assertNotNull(bd);
        // Verify the BigDecimal value is preserved exactly
        assertEquals(new BigDecimal("123.45"), bd);
        assertEquals(0, bd.compareTo(new BigDecimal("123.45")));

        // Verify ColumnGetter.get returns a working getter for String type
        when(mockResultSet.getString(1)).thenReturn("hello");
        Jdbc.ColumnGetter<String> stringGetter = Jdbc.ColumnGetter.get(String.class);
        assertEquals("hello", stringGetter.apply(mockResultSet, 1));
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

    // HandlerFactory tests
    static final class TestHandler implements Jdbc.Handler<Object> {
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testHandlerFactory_Register_And_Get() {
        final String qualifier = "TestHandlerUnique_" + System.nanoTime();
        final TestHandler handler = new TestHandler();
        boolean registered = Jdbc.HandlerFactory.register(qualifier, handler);
        assertTrue(registered);

        // second registration returns false
        assertFalse(Jdbc.HandlerFactory.register(qualifier, handler));

        assertSame(handler, Jdbc.HandlerFactory.get(qualifier));
    }

    @Test
    public void testHandlerFactory_RegisterByClass() {
        boolean registered = Jdbc.HandlerFactory.register(TestHandler.class);
        // may be false if already registered in testHandlerFactory_Register_And_Get
        assertNotNull(Jdbc.HandlerFactory.get(TestHandler.class));
    }

    @Test
    public void testHandlerFactory_GetByString_NotFound_ReturnsNull() {
        assertNull(Jdbc.HandlerFactory.get("nonExistentHandler_xyz_12345"));
    }

    @Test
    public void testHandlerFactory_GetByClass_NotFound_ReturnsNull() {
        assertNull(Jdbc.HandlerFactory.get((Class<? extends Jdbc.Handler<?>>) (Class<?>) java.io.Serializable.class));
    }

    @Test
    public void testHandlerFactory_GetOrCreate() {
        final Jdbc.Handler<?> h = Jdbc.HandlerFactory.getOrCreate(TestHandler.class);
        assertNotNull(h);
    }

    @Test
    public void testHandlerFactory_Create_BeforeInvoke() throws Exception {
        final boolean[] called = { false };
        @SuppressWarnings("unchecked")
        final Jdbc.Handler<Object> handler = Jdbc.HandlerFactory
                .create((Throwables.TriConsumer<Object, Object[], Tuple3<Method, ImmutableList<Class<?>>, Class<?>>, RuntimeException>) (proxy, args,
                        sig) -> called[0] = true);

        handler.beforeInvoke(new Object(), new Object[0], null);
        assertTrue(called[0]);
    }

    @Test
    public void testHandlerFactory_Create_AfterInvoke() throws Exception {
        final boolean[] called = { false };
        @SuppressWarnings("unchecked")
        final Jdbc.Handler<Object> handler = Jdbc.HandlerFactory
                .create((Throwables.QuadConsumer<Object, Object, Object[], Tuple3<Method, ImmutableList<Class<?>>, Class<?>>, RuntimeException>) (result, proxy,
                        args, sig) -> called[0] = true);

        handler.afterInvoke(null, new Object(), new Object[0], null);
        assertTrue(called[0]);
    }

    @Test
    public void testHandlerFactory_Create_BothInvoke() throws Exception {
        final boolean[] beforeCalled = { false };
        final boolean[] afterCalled = { false };
        @SuppressWarnings("unchecked")
        final Jdbc.Handler<Object> handler = Jdbc.HandlerFactory.create(
                (Throwables.TriConsumer<Object, Object[], Tuple3<Method, ImmutableList<Class<?>>, Class<?>>, RuntimeException>) (proxy, args,
                        sig) -> beforeCalled[0] = true,
                (Throwables.QuadConsumer<Object, Object, Object[], Tuple3<Method, ImmutableList<Class<?>>, Class<?>>, RuntimeException>) (result, proxy, args,
                        sig) -> afterCalled[0] = true);

        handler.beforeInvoke(new Object(), new Object[0], null);
        handler.afterInvoke(null, new Object(), new Object[0], null);
        assertTrue(beforeCalled[0]);
        assertTrue(afterCalled[0]);
    }

    @Test
    public void testHandlerFactory_RegisterWithQualifier() {
        final Jdbc.Handler<Object> h = new TestHandler();
        final String qualifier = "myCustomHandler_unique_12345";
        assertTrue(Jdbc.HandlerFactory.register(qualifier, h));
        assertFalse(Jdbc.HandlerFactory.register(qualifier, h));
        assertSame(h, Jdbc.HandlerFactory.get(qualifier));
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

        final Jdbc.RowExtractor noopExtractor = (rs, output) -> {};
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

        final Jdbc.RowExtractor noopExtractor = (rs, output) -> {};
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

        Jdbc.BiRowMapper<Map<String, Object>> mapper = Jdbc.BiRowMapper.toMap(
                extractor,
                (key, value) -> value != null,
                IntFunctions.ofLinkedHashMap());

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

        Jdbc.BiRowMapper<Map<String, Object>> mapper = Jdbc.BiRowMapper.toMap(
                extractor,
                col -> col.toUpperCase(),
                IntFunctions.ofTreeMap());

        List<String> columnLabels = Arrays.asList("first_name", "user_age");
        Map<String, Object> result = mapper.apply(mockResultSet, columnLabels);
        assertEquals(2, result.size());
        assertTrue(result instanceof TreeMap);
        assertEquals("Grace", result.get("FIRST_NAME"));
        assertEquals(28, result.get("USER_AGE"));
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

        Jdbc.RowMapper<String> mapper = Jdbc.RowMapper.builder()
                .to((columnLabels, row) -> columnLabels.get(1) + "=" + row.get(1));

        String result = mapper.apply(mockResultSet);
        assertEquals("name=Alice", result);
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

    @Test
    public void testBiRowMapper_ToWithColumnFilterAndConverter() throws SQLException {
        when(mockResultSet.getString(2)).thenReturn("Carol");

        Jdbc.BiRowMapper<TestEntity> mapper = Jdbc.BiRowMapper.to(
                TestEntity.class,
                col -> col.equals("name"),
                col -> col);

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
        Jdbc.BiRowMapper<Object[]> mapper = Jdbc.BiRowMapper.to(
                Object[].class,
                col -> col.equals("name"),
                col -> col,
                false);

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
        Jdbc.BiRowMapper<List> mapper = Jdbc.BiRowMapper.to(
                List.class,
                col -> !col.equals("age"),
                col -> col,
                false);

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
        Jdbc.BiRowMapper<Map> mapper = Jdbc.BiRowMapper.to(
                Map.class,
                col -> col.equals("name"),
                col -> col.toUpperCase(),
                false);

        List<String> columnLabels = Arrays.asList("id", "name", "age");
        Map result = mapper.apply(mockResultSet, columnLabels);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("Fiona", result.get("NAME"));
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
    public void testBiRowMapper_ToScalarType_MultiColumnThrows() throws SQLException {
        // Scalar type with null filter/converter and multiple columns → throws
        Jdbc.BiRowMapper<String> mapper = Jdbc.BiRowMapper.to(String.class, null, null, false);
        List<String> columnLabels = Arrays.asList("col1", "col2");

        assertThrows(IllegalArgumentException.class, () -> mapper.apply(mockResultSet, columnLabels));
    }

    @Test
    public void testBiRowMapper_ToScalarType_WithFilterThrows() {
        // Scalar type with non-null filter → throws immediately
        assertThrows(IllegalArgumentException.class,
                () -> Jdbc.BiRowMapper.to(String.class, col -> true, null, false));
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
    public void testBiRowMapper_ToPrefixMap_UnmatchedColumn_ThrowsException() throws SQLException {
        Map<String, String> prefixMap = new HashMap<>();
        prefixMap.put("x_", "");

        // non-empty prefix map, ignoreUnmatchedColumns=false → throws on unmatched column
        Jdbc.BiRowMapper<TestEntity> mapper = Jdbc.BiRowMapper.to(TestEntity.class, prefixMap, false);
        List<String> columnLabels = Arrays.asList("x_unknown");

        assertThrows(IllegalArgumentException.class, () -> mapper.apply(mockResultSet, columnLabels));
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

    // Entity with camelCase field to exercise column2FieldNameMap lookup path
    public static class CamelCaseEntity {
        private String firstName;
        private Integer totalCount;

        public String getFirstName() {
            return firstName;
        }

        public void setFirstName(final String firstName) {
            this.firstName = firstName;
        }

        public Integer getTotalCount() {
            return totalCount;
        }

        public void setTotalCount(final Integer totalCount) {
            this.totalCount = totalCount;
        }
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
    public void testRowExtractor_CreateBy_WithColumnLabels_UnmatchedColumns() throws SQLException {
        // Unmatched column labels → goes through column2FieldNameMap and checkPrefix fallback chain.
        when(mockResultSet.getObject(1)).thenReturn("fallback_value");

        List<String> columnLabels = Arrays.asList("unknown_col");
        Jdbc.RowExtractor extractor = Jdbc.RowExtractor.createBy(TestEntity.class, columnLabels);
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
        Jdbc.RowExtractor extractor = Jdbc.RowExtractor.createBy(CamelCaseEntity.class, columnLabels);
        Object[] outputRow = new Object[1];

        extractor.accept(mockResultSet, outputRow);
        assertEquals("Carol", outputRow[0]);
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

    // Invoke SET_BINARY_STREAM lambda body (L5811) - covers the lambda execution
    @Test
    @SuppressWarnings("unchecked")
    public void testColumnOne_SetBinaryStream_LambdaBody() throws Exception {
        InputStream is = new ByteArrayInputStream(new byte[]{1, 2, 3});
        Jdbc.Columns.ColumnOne.SET_BINARY_STREAM.accept(mockAbstractQuery, is);
        verify(mockAbstractQuery).setBinaryStream(1, is);
    }

    // Invoke SET_CHARACTER_STREAM lambda body (L5817)
    @Test
    @SuppressWarnings("unchecked")
    public void testColumnOne_SetCharacterStream_LambdaBody() throws Exception {
        Reader reader = new StringReader("test");
        Jdbc.Columns.ColumnOne.SET_CHARACTER_STREAM.accept(mockAbstractQuery, reader);
        verify(mockAbstractQuery).setCharacterStream(1, reader);
    }
}
