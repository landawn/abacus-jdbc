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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
        setter.accept(mockPreparedStatement);   // Should do nothing
        verifyNoInteractions(mockPreparedStatement);
    }

    @Test
    public void testParametersSetterAccept() throws SQLException {
        Jdbc.ParametersSetter<PreparedStatement> setter = ps -> ps.setString(1, "test");
        setter.accept(mockPreparedStatement);
        verify(mockPreparedStatement).setString(1, "test");
    }

    // BiParametersSetter Tests
    @Test
    public void testBiParametersSetterDoNothing() throws SQLException {
        Jdbc.BiParametersSetter<Object, Object> setter = Jdbc.BiParametersSetter.DO_NOTHING;
        setter.accept(mockPreparedStatement, "param");
        verifyNoInteractions(mockPreparedStatement);
    }

    @Test
    public void testBiParametersSetterAccept() throws SQLException {
        Jdbc.BiParametersSetter<PreparedStatement, String> setter = (ps, param) -> ps.setString(1, param);
        setter.accept(mockPreparedStatement, "test");
        verify(mockPreparedStatement).setString(1, "test");
    }

    @Test
    public void testBiParametersSetterCreateForArray() throws SQLException {
        List<String> fields = Arrays.asList("name", "age");
        Jdbc.BiParametersSetter<PreparedStatement, Object[]> setter = Jdbc.BiParametersSetter.createForArray(fields, TestEntity.class);

        Object[] params = new Object[] { "John", 25 };
        setter.accept(mockPreparedStatement, params);

        verify(mockPreparedStatement, times(1)).setString(anyInt(), any());
        verify(mockPreparedStatement, times(1)).setInt(anyInt(), anyInt());
    }

    //    @Test
    //    public void testBiParametersSetterCreateForList() throws SQLException {
    //        List<String> fields = Arrays.asList("name", "age");
    //        Jdbc.BiParametersSetter<PreparedStatement, List<Object>> setter = Jdbc.BiParametersSetter.createForList(fields, TestEntity.class);
    //
    //        List<Object> params = Arrays.asList("John", 25);
    //        setter.accept(mockPreparedStatement, params);
    //
    //        //  verify(mockPreparedStatement, times(2)).setObject(anyInt(), any());
    //        verify(mockPreparedStatement, times(1)).setString(anyInt(), any());
    //        verify(mockPreparedStatement, times(1)).setInt(anyInt(), any());
    //    }

    // TriParametersSetter Tests
    @Test
    public void testTriParametersSetterDoNothing() throws SQLException {
        Jdbc.TriParametersSetter<Object, Object> setter = Jdbc.TriParametersSetter.DO_NOTHING;
        setter.accept(mockParsedSql, mockPreparedStatement, "param");
        verifyNoInteractions(mockPreparedStatement);
    }

    @Test
    public void testTriParametersSetterAccept() throws SQLException {
        Jdbc.TriParametersSetter<PreparedStatement, String> setter = (parsedSql, ps, param) -> ps.setString(1, param);
        setter.accept(mockParsedSql, mockPreparedStatement, "test");
        verify(mockPreparedStatement).setString(1, "test");
    }

    // ResultExtractor Tests
    @Test
    public void testResultExtractorToDataset() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);
        Dataset result = Jdbc.ResultExtractor.TO_DATA_SET.apply(mockResultSet);
        assertNotNull(result);
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
        Dataset result = Jdbc.BiResultExtractor.TO_DATA_SET.apply(mockResultSet, Arrays.asList("col1"));
        assertNotNull(result);
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

        Jdbc.BiRowMapper<TestEntity> mapper = Jdbc.BiRowMapper.to(TestEntity.class);
        List<String> columnLabels = Arrays.asList("id", "name", "age");

        TestEntity result = mapper.apply(mockResultSet, columnLabels);
        assertNotNull(result);
    }

    @Test
    public void testBiRowMapperToWithIgnoreNonMatchedColumns() throws SQLException {
        when(mockResultSet.getLong(1)).thenReturn(1L);
        when(mockResultSet.getString(2)).thenReturn("John");
        when(mockResultSet.getInt(3)).thenReturn(25);
        when(mockResultSet.getString(4)).thenReturn("extra");

        Jdbc.BiRowMapper<TestEntity> mapper = Jdbc.BiRowMapper.to(TestEntity.class, true);
        List<String> columnLabels = Arrays.asList("id", "name", "age", "extra_column");

        when(mockResultSetMetaData.getColumnCount()).thenReturn(4);
        when(mockResultSetMetaData.getColumnLabel(4)).thenReturn("extra_column");

        TestEntity result = mapper.apply(mockResultSet, columnLabels);
        assertNotNull(result);
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
    }

    // RowConsumer Tests
    @Test
    public void testRowConsumerDoNothing() throws SQLException {
        Jdbc.RowConsumer.DO_NOTHING.accept(mockResultSet);
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

    // BiRowConsumer Tests
    @Test
    public void testBiRowConsumerDoNothing() throws SQLException {
        Jdbc.BiRowConsumer.DO_NOTHING.accept(mockResultSet, Arrays.asList("col1"));
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

        assertNotNull(outputRow[0]);
        assertNotNull(outputRow[1]);
        assertNotNull(outputRow[2]);
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
    }

    //    @Test
    //    public void testColumnOneSet() throws SQLException {
    //        Jdbc.BiParametersSetter<AbstractQuery, String> stringSetter = Jdbc.Columns.ColumnOne.set(String.class);
    //        Jdbc.BiParametersSetter<AbstractQuery, Integer> intSetter = Jdbc.Columns.ColumnOne.set(Integer.class);
    //
    //        stringSetter.accept(mockAbstractQuery, "test");
    //        intSetter.accept(mockAbstractQuery, 42);
    //
    //        verify(mockAbstractQuery.stmt).setObject(1, "test");
    //        verify(mockAbstractQuery.stmt).setObject(1, 42);
    //    }

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
        Jdbc.Handler<Object> handler = new Jdbc.Handler<Object>() {
        };

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        // Should not throw exceptions
        handler.beforeInvoke(new Object(), new Object[0], methodSignature);
        handler.afterInvoke("result", new Object(), new Object[0], methodSignature);
    }

    // HandlerFactory Tests
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
        Jdbc.Handler<Object> handler = new Jdbc.Handler<Object>() {
        };

        boolean registered = Jdbc.HandlerFactory.register(handler);
        assertTrue(registered);
    }

    @Test
    public void testHandlerFactoryRegisterWithQualifier() {
        Jdbc.Handler<Object> handler = new Jdbc.Handler<Object>() {
        };

        boolean registered = Jdbc.HandlerFactory.register("testHandler", handler);
        assertTrue(registered);

        // Same qualifier should fail
        assertFalse(Jdbc.HandlerFactory.register("testHandler", handler));
    }

    @Test
    public void testHandlerFactoryGet() {
        Jdbc.Handler<Object> handler = new Jdbc.Handler<Object>() {
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
    }

    @Test
    public void testDaoCacheCreateByMapWithMap() {
        Map<String, Object> map = new HashMap<>();
        Jdbc.DaoCache cache = Jdbc.DaoCache.createByMap(map);
        assertNotNull(cache);
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
        Jdbc.DaoCacheByMap cache1 = new Jdbc.DaoCacheByMap();
        assertNotNull(cache1);

        Jdbc.DaoCacheByMap cache2 = new Jdbc.DaoCacheByMap(50);
        assertNotNull(cache2);

        Map<String, Object> map = new HashMap<>();
        Jdbc.DaoCacheByMap cache3 = new Jdbc.DaoCacheByMap(map);
        assertNotNull(cache3);
    }

    // Edge cases and error conditions
    @Test
    public void testNullHandling() throws SQLException {
        // Test null ResultSet in ResultExtractor
        Dataset dataset = Jdbc.ResultExtractor.TO_DATA_SET.apply(null);
        assertNotNull(dataset);
        assertTrue(dataset.isEmpty());

        // Test null ResultSet in BiResultExtractor
        Dataset biDataset = Jdbc.BiResultExtractor.TO_DATA_SET.apply(null, Arrays.asList("col1"));
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
            Jdbc.RowMapper.builder().getInt(0);   // Invalid index (should be 1-based)
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
        assertEquals(30, result.get("A"));   // 10 + 20
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
        when(mockResultSet.getObject(2)).thenReturn(new java.util.Date());

        Jdbc.ColumnGetter<BigDecimal> bigDecimalGetter = Jdbc.ColumnGetter.get(BigDecimal.class);
        BigDecimal bd = bigDecimalGetter.apply(mockResultSet, 1);
        assertNotNull(bd);

        Jdbc.ColumnGetter<Date> dateGetter = Jdbc.ColumnGetter.get(Date.class);
        // This would normally handle conversion from java.util.Date to java.sql.Date
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
        assertNotNull(result[5]);   // Date
        assertNotNull(result[6]);   // Time
        assertEquals("object", result[7]);
    }

    @Test
    public void testBiRowMapperAllBuilderMethods() throws SQLException {
        // Setup mock for BiRowMapper builder test
        when(mockResultSet.getBoolean(1)).thenReturn(false);
        when(mockResultSet.getByte(1)).thenReturn((byte) 20);
        when(mockResultSet.getShort(1)).thenReturn((short) 200);
        when(mockResultSet.getFloat(1)).thenReturn(2.5f);
        when(mockResultSet.getDouble(1)).thenReturn(3.14);
        when(mockResultSet.getBigDecimal(1)).thenReturn(new BigDecimal("456.78"));
        when(mockResultSet.getDate(1)).thenReturn(new Date(System.currentTimeMillis()));
        when(mockResultSet.getTime(1)).thenReturn(new Time(System.currentTimeMillis()));
        when(mockResultSet.getTimestamp(1)).thenReturn(new Timestamp(System.currentTimeMillis()));

        Jdbc.BiRowMapper.BiRowMapperBuilder builder = Jdbc.BiRowMapper.builder();

        builder.getBoolean("active")
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
                .getObject("typed", String.class);

        // Would need to test the actual build results with appropriate column labels
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
        assertNotNull(output[9]);   // Date
        assertNotNull(output[10]);   // Time
        assertNotNull(output[11]);   // Timestamp
        assertEquals("obj", output[12]);
        assertEquals("typed", output[13]);
    }
}