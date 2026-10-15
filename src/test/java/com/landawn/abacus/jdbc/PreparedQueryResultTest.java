package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.exception.DuplicateResultException;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.u.Nullable;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.u.OptionalBoolean;
import com.landawn.abacus.util.u.OptionalByte;
import com.landawn.abacus.util.u.OptionalChar;
import com.landawn.abacus.util.u.OptionalDouble;
import com.landawn.abacus.util.u.OptionalFloat;
import com.landawn.abacus.util.u.OptionalInt;
import com.landawn.abacus.util.u.OptionalLong;
import com.landawn.abacus.util.u.OptionalShort;

class PreparedQueryResultTest extends PreparedQueryTestSupport {

    @Test
    public void testFindFirst() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn("first");

        Optional<Map<String, Object>> result = query.findFirst();

        assertTrue(result.isPresent());
        verify(mockResultSet).close();
    }

    //    @Test
    //    public void testFindFirstWithFilter() throws SQLException {
    //        when(mockResultSet.next()).thenReturn(true, true, true);
    //        when(mockResultSet.getInt(1)).thenReturn(5, 10, 15);
    //
    //        Optional<Integer> result = query.findFirst(rs -> rs.getInt(1) > 8, rs -> rs.getInt(1));
    //
    //        assertTrue(result.isPresent());
    //        assertEquals(10, result.get());
    //        verify(mockResultSet).close();
    //    }

    @Test
    public void testFindFirstBiRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);

        Optional<List<String>> result = query.findFirst((rs, labels) -> labels);

        assertTrue(result.isPresent());
        verify(mockResultSet).close();
    }

    @Test
    public void testFindFirstClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);

        Optional<TestEntity> result = query.findFirst(TestEntity.class);

        assertTrue(result.isPresent());
        verify(mockResultSet).close();
    }

    @Test
    public void testFindFirstOrNull() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getObject(1)).thenReturn("value");

        Map<String, Object> result = query.findFirstOrNull();

        assertNotNull(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testFindFirstOrNullBiRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);

        Integer result = query.findFirstOrNull((rs, labels) -> labels.size());

        assertEquals(1, result);
        verify(mockResultSet).close();
    }

    @Test
    public void testFindFirstOrNullClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);

        TestEntity result = query.findFirstOrNull(TestEntity.class);

        assertNotNull(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testFindFirstOrNullEmpty() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        Map<String, Object> result = query.findFirstOrNull();

        assertNull(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testFindFirstOrNullRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn("result");

        String result = query.findFirstOrNull(rs -> rs.getString(1));

        assertEquals("result", result);
        verify(mockResultSet).close();
    }

    @Test
    public void testFindFirstOrNullWithBiFilter() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true);
        when(mockResultSet.getString(1)).thenReturn("skip", "take");

        String result = query.findFirstOrNull((rs, labels) -> rs.getString(1).startsWith("t"), (rs, labels) -> rs.getString(1).toUpperCase());

        assertEquals("TAKE", result);
        verify(mockResultSet).close();
    }

    @Test
    public void testFindFirstOrNullWithFilter() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getBoolean(1)).thenReturn(false, true);
        when(mockResultSet.getString(2)).thenReturn("take");

        String result = query.findFirstOrNull(rs -> rs.getBoolean(1), rs -> rs.getString(2));

        assertEquals("take", result);
        verify(mockResultSet).close();
    }

    @Test
    public void testFindFirstRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn("first");

        Optional<String> result = query.findFirst(rs -> rs.getString(1));

        assertTrue(result.isPresent());
        assertEquals("first", result.get());
        verify(mockResultSet).close();
    }

    @Test
    public void testFindFirstWithBiFilter() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true);
        when(mockResultSet.getString(1)).thenReturn("skip", "take");

        Optional<String> result = query.findFirst((rs, labels) -> rs.getString(1).equals("take"), (rs, labels) -> rs.getString(1));

        assertTrue(result.isPresent());
        assertEquals("take", result.get());
        verify(mockResultSet).close();
    }

    @Test
    public void testFindOnlyOne() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn("value1");
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("col1");

        Optional<Map<String, Object>> result = query.findOnlyOne();

        assertTrue(result.isPresent());
        verify(mockResultSet).close();
    }

    @Test
    public void testFindOnlyOneBiRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        Optional<Integer> result = query.findOnlyOne((rs, labels) -> labels.size());

        assertTrue(result.isPresent());
        assertEquals(1, result.get());
        verify(mockResultSet).close();
    }

    @Test
    public void testFindOnlyOneClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        Optional<TestEntity> result = query.findOnlyOne(TestEntity.class);

        assertTrue(result.isPresent());
        verify(mockResultSet).close();
    }

    @Test
    public void testFindOnlyOneDuplicated() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true);

        assertThrows(DuplicateResultException.class, () -> query.findOnlyOne());
        verify(mockResultSet).close();
    }

    @Test
    public void testFindOnlyOneOrNull() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn("value");

        Map<String, Object> result = query.findOnlyOneOrNull();

        assertNotNull(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testFindOnlyOneOrNullBiRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        String result = query.findOnlyOneOrNull((rs, labels) -> "result");

        assertEquals("result", result);
        verify(mockResultSet).close();
    }

    @Test
    public void testFindOnlyOneOrNullClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        TestEntity result = query.findOnlyOneOrNull(TestEntity.class);

        assertNotNull(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testFindOnlyOneOrNullEmpty() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        Map<String, Object> result = query.findOnlyOneOrNull();

        assertNull(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testFindOnlyOneOrNullRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getString(1)).thenReturn("test");

        String result = query.findOnlyOneOrNull(rs -> rs.getString(1));

        assertEquals("test", result);
        verify(mockResultSet).close();
    }

    @Test
    public void testFindOnlyOneRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getString(1)).thenReturn("test");

        Optional<String> result = query.findOnlyOne(rs -> rs.getString(1));

        assertTrue(result.isPresent());
        assertEquals("test", result.get());
        verify(mockResultSet).close();
    }

    @Test
    public void testList() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn("val1", "val2");

        List<Map<String, Object>> result = query.list();

        assertEquals(2, result.size());
        verify(mockResultSet).close();
    }

    @Test
    public void testListAllResultSetsBiRowMapper() throws SQLException {
        when(mockStmt.execute()).thenReturn(true);
        when(mockStmt.getMoreResults()).thenReturn(false);
        when(mockStmt.getUpdateCount()).thenReturn(-1);
        when(mockResultSet.next()).thenReturn(true, false);

        List<List<Integer>> results = query.listAllResultSets((rs, labels) -> labels.size());

        assertEquals(1, results.size());
        assertEquals(Arrays.asList(1), results.get(0));
    }

    //    @Test
    //    public void testListWithBiFilter() throws SQLException {
    //        when(mockResultSet.next()).thenReturn(true, true, true, false);
    //        when(mockResultSet.getString(1)).thenReturn("skip", "take1", "take2");
    //
    //        List<String> result = query.list((rs, labels) -> rs.getString(1).startsWith("take"), (rs, labels) -> rs.getString(1));
    //
    //        assertEquals(Arrays.asList("take1", "take2"), result);
    //        verify(mockResultSet).close();
    //    }

    @Test
    public void testListAllResultSetsRowMapper() throws SQLException {
        when(mockStmt.execute()).thenReturn(true);
        when(mockStmt.getMoreResults()).thenReturn(false);
        when(mockStmt.getUpdateCount()).thenReturn(-1);
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getString(1)).thenReturn("result");

        List<List<String>> results = query.listAllResultSets(rs -> rs.getString(1));

        assertEquals(1, results.size());
        assertEquals(Arrays.asList("result"), results.get(0));
    }

    @Test
    public void testListAllResultSetsWithBiFilter() throws SQLException {
        when(mockStmt.execute()).thenReturn(true);
        when(mockStmt.getMoreResults()).thenReturn(false);
        when(mockStmt.getUpdateCount()).thenReturn(-1);
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("skip", "take");

        List<List<String>> results = query.listAllResultSets((rs, labels) -> rs.getString(1).equals("take"), (rs, labels) -> rs.getString(1).toUpperCase());

        assertEquals(1, results.size());
        assertEquals(Arrays.asList("TAKE"), results.get(0));
    }

    @Test
    public void testListAllResultSetsWithFilter() throws SQLException {
        when(mockStmt.execute()).thenReturn(true);
        when(mockStmt.getMoreResults()).thenReturn(true);
        when(mockStmt.getUpdateCount()).thenReturn(-1);
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getInt(1)).thenReturn(5, 10);

        List<List<Integer>> results = query.listAllResultSets(rs -> rs.getInt(1) > 8, rs -> rs.getInt(1));

        assertEquals(1, results.size());
        assertEquals(Arrays.asList(10), results.get(0));
    }

    @Test
    public void testListBiRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);

        List<Integer> result = query.list((rs, labels) -> labels.size());

        assertEquals(Arrays.asList(1, 1), result);
        verify(mockResultSet).close();
    }

    @Test
    public void testListBiRowMapperWithMaxResult() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, true);

        List<String> result = query.list((rs, labels) -> "row", 2);

        assertEquals(Arrays.asList("row", "row"), result);
        verify(mockResultSet).close();
    }

    @Test
    public void testListClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);

        List<TestEntity> result = query.list(TestEntity.class);

        assertEquals(2, result.size());
        verify(mockResultSet).close();
    }

    @Test
    public void testListClassWithMaxResult() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, true, false);

        List<TestEntity> result = query.list(TestEntity.class, 2);

        assertEquals(2, result.size());
        verify(mockResultSet).close();
    }

    @Test
    public void testListRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("A", "B");

        List<String> result = query.list(rs -> rs.getString(1));

        assertEquals(Arrays.asList("A", "B"), result);
        verify(mockResultSet).close();
    }

    @Test
    public void testListRowMapperWithMaxResult() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, true);
        when(mockResultSet.getString(1)).thenReturn("A", "B", "C");

        List<String> result = query.list(rs -> rs.getString(1), 2);

        assertEquals(Arrays.asList("A", "B"), result);
        verify(mockResultSet).close();
    }

    @Test
    public void testListThenAcceptBiRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        List<Integer> captured = new ArrayList<>();
        query.listThenAccept((rs, labels) -> labels.size(), list -> captured.addAll(list));

        assertEquals(Arrays.asList(1), captured);
        verify(mockResultSet).close();
    }

    @Test
    public void testListThenAcceptClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);

        List<Integer> sizes = new ArrayList<>();
        query.listThenAccept(TestEntity.class, list -> sizes.add(list.size()));

        assertEquals(Arrays.asList(2), sizes);
        verify(mockResultSet).close();
    }

    @Test
    public void testListThenAcceptRejectsNullConsumerBeforeQueryExecution() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> query.listThenAccept(rs -> rs.getString(1), null));
        verify(mockStmt, never()).executeQuery();
    }

    @Test
    public void testListThenAcceptRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getString(1)).thenReturn("test");

        List<String> captured = new ArrayList<>();
        query.listThenAccept(rs -> rs.getString(1), list -> captured.addAll(list));

        assertEquals(Arrays.asList("test"), captured);
        verify(mockResultSet).close();
    }

    @Test
    public void testListThenApplyBiRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        Integer result = query.listThenApply((rs, labels) -> labels.size(), list -> list.get(0));

        assertEquals(1, result);
        verify(mockResultSet).close();
    }

    @Test
    public void testListThenApplyClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);

        Integer result = query.listThenApply(TestEntity.class, list -> list.size());

        assertEquals(2, result);
        verify(mockResultSet).close();
    }

    @Test
    public void testListThenApplyRejectsNullFunctionBeforeQueryExecution() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> query.listThenApply(TestEntity.class, null));
        verify(mockStmt, never()).executeQuery();
    }

    @Test
    public void testListThenApplyRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("A", "B");

        String result = query.listThenApply(rs -> rs.getString(1), list -> String.join(",", list));

        assertEquals("A,B", result);
        verify(mockResultSet).close();
    }

    @Test
    public void testQuery2ResultSets() throws SQLException {
        when(mockStmt.getMoreResults()).thenReturn(true, true, false);
        when(mockStmt.getResultSet()).thenReturn(mockResultSet, mockResultSet);
        when(mockStmt.getUpdateCount()).thenReturn(0, 0, -1);

        Tuple2<String, Integer> result = query.query2ResultSets((rs, labels) -> "result1", (rs, labels) -> 42);

        assertEquals("result1", result._1);
        assertEquals(42, result._2);
    }

    @Test
    public void testQuery3ResultSets() throws SQLException {
        when(mockStmt.getMoreResults()).thenReturn(true, true, true, false);
        when(mockStmt.getResultSet()).thenReturn(mockResultSet, mockResultSet, mockResultSet);
        when(mockStmt.getUpdateCount()).thenReturn(0, 0, 0, -1);

        Tuple3<String, Integer, Boolean> result = query.query3ResultSets((rs, labels) -> "result1", (rs, labels) -> 42, (rs, labels) -> true);

        assertEquals("result1", result._1);
        assertEquals(42, result._2);
        assertEquals(true, result._3);
    }

    @Test
    public void testQueryAllResultSets() throws SQLException {
        when(mockStmt.getMoreResults()).thenReturn(true, false);
        when(mockStmt.getUpdateCount()).thenReturn(0, -1);

        List<Dataset> results = query.queryAllResultSets();

        assertNotNull(results);
        assertFalse(results.isEmpty());
    }

    @Test
    public void testQueryAllResultSetsWithBiExtractor() throws SQLException {
        // Stub execute() to return true so the first result set is produced
        when(mockStmt.execute()).thenReturn(true);
        when(mockStmt.getMoreResults()).thenReturn(false);
        when(mockStmt.getUpdateCount()).thenReturn(-1);

        List<Map<String, Integer>> results = query.queryAllResultSets((rs, labels) -> {
            Map<String, Integer> map = new HashMap<>();
            map.put("columnCount", labels.size());
            return map;
        });

        assertNotNull(results);
        // execute() returns true, yielding one result set; getMoreResults()=false terminates
        assertEquals(1, results.size());
        // Metadata stub has columnCount=1, so labels list has 1 entry
        assertEquals(1, results.get(0).get("columnCount"));
        assertTrue(results.get(0).containsKey("columnCount"));
    }

    @Test
    public void testQueryAllResultSetsWithExtractor() throws SQLException {
        when(mockStmt.getMoreResults()).thenReturn(true, false);
        when(mockStmt.getUpdateCount()).thenReturn(0, -1);

        List<Integer> results = query.queryAllResultSets(rs -> {
            int count = 0;
            while (rs.next()) {
                count++;
            }
            return count;
        });

        assertNotNull(results);
        // execute() returns false (unstubbed), getUpdateCount()=0 triggers getMoreResults()=true,
        // yielding one result set; then getUpdateCount()=-1 terminates iteration
        assertEquals(1, results.size());
        // mockResultSet.next() is not stubbed so returns false — extractor sees 0 rows
        assertEquals(0, results.get(0));
    }

    @Test
    public void testQueryDataset() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn("value");

        Dataset result = query.query();

        assertNotNull(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryDatasetWithEntityClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        Dataset result = query.query(TestEntity.class);

        assertNotNull(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForBigDecimal() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getBigDecimal(1)).thenReturn(new BigDecimal("123.45"));

        Nullable<BigDecimal> result = query.queryForBigDecimal();

        assertTrue(result.isPresent());
        assertEquals(new BigDecimal("123.45"), result.orElse(null));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForBigInteger() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn("123456789");

        Nullable<BigInteger> result = query.queryForBigInteger();

        assertTrue(result.isPresent());
        assertEquals(new BigInteger("123456789"), result.orElse(null));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForBoolean() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getBoolean(1)).thenReturn(true);

        OptionalBoolean result = query.queryForBoolean();

        assertTrue(result.isPresent());
        assertTrue(result.orElse(false));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForBooleanEmpty() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        OptionalBoolean result = query.queryForBoolean();

        assertFalse(result.isPresent());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForByte() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getByte(1)).thenReturn((byte) 127);

        OptionalByte result = query.queryForByte();

        assertTrue(result.isPresent());
        assertEquals((byte) 127, result.orElseThrow());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForBytes() throws SQLException {
        byte[] bytes = { 1, 2, 3, 4, 5 };
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getBytes(1)).thenReturn(bytes);
        Nullable<byte[]> result = query.queryForBytes();

        assertTrue(result.isPresent());
        assertArrayEquals(bytes, result.orElse(null));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForChar() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn("A");

        OptionalChar result = query.queryForChar();

        assertTrue(result.isPresent());
        assertEquals('A', result.orElseThrow());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForDate() throws SQLException {
        java.sql.Date date = new java.sql.Date(System.currentTimeMillis());
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getDate(1)).thenReturn(date);

        Nullable<java.sql.Date> result = query.queryForDate();

        assertTrue(result.isPresent());
        assertEquals(date, result.orElse(null));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForDouble() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getDouble(1)).thenReturn(3.14159);

        OptionalDouble result = query.queryForDouble();

        assertTrue(result.isPresent());
        assertEquals(3.14159, result.orElseThrow());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForFloat() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getFloat(1)).thenReturn(3.14f);

        OptionalFloat result = query.queryForFloat();

        assertTrue(result.isPresent());
        assertEquals(3.14f, result.orElseThrow());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForInt() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getInt(1)).thenReturn(42);

        OptionalInt result = query.queryForInt();

        assertTrue(result.isPresent());
        assertEquals(42, result.orElseThrow());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForLong() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getLong(1)).thenReturn(123456789L);

        OptionalLong result = query.queryForLong();

        assertTrue(result.isPresent());
        assertEquals(123456789L, result.orElseThrow());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForShort() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getShort(1)).thenReturn((short) 1000);

        OptionalShort result = query.queryForShort();

        assertTrue(result.isPresent());
        assertEquals((short) 1000, result.orElseThrow());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForSingleNonNullClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn("test");

        Optional<String> result = query.queryForSingleNonNull(String.class);

        assertTrue(result.isPresent());
        assertEquals("test", result.get());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForSingleNonNullType() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getObject(1)).thenReturn(123L);

        Optional<Long> result = query.queryForSingleNonNull(Type.of(Long.class));

        assertTrue(result.isPresent());
        assertEquals(123L, result.get());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForSingleResultClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn("test");

        Nullable<String> result = query.queryForSingleValue(String.class);

        assertTrue(result.isPresent());
        assertEquals("test", result.orElse(null));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForSingleResultType() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getObject(1)).thenReturn(42);

        Nullable<Integer> result = query.queryForSingleValue(Type.of(Integer.class));

        assertTrue(result.isPresent());
        assertEquals(42, result.orElse(-1));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForString() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn("test");

        Nullable<String> result = query.queryForString();

        assertTrue(result.isPresent());
        assertEquals("test", result.orElse(null));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForTime() throws SQLException {
        java.sql.Time time = new java.sql.Time(System.currentTimeMillis());
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getTime(1)).thenReturn(time);

        Nullable<java.sql.Time> result = query.queryForTime();

        assertTrue(result.isPresent());
        assertEquals(time, result.orElse(null));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForTimestamp() throws SQLException {
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getTimestamp(1)).thenReturn(timestamp);

        Nullable<Timestamp> result = query.queryForTimestamp();

        assertTrue(result.isPresent());
        assertEquals(timestamp, result.orElse(null));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForUniqueNonNullClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn(42);

        Optional<Integer> result = query.queryForUniqueNonNull(Integer.class);

        assertTrue(result.isPresent());
        assertEquals(42, result.get());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForUniqueNonNullDuplicated() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true);
        when(mockResultSet.getString(1)).thenReturn("first", "second");

        assertThrows(DuplicateResultException.class, () -> query.queryForUniqueNonNull(String.class));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForUniqueNonNullType() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getString(1)).thenReturn(UUID.randomUUID().toString());

        Optional<UUID> result = query.queryForUniqueNonNull(Type.of(UUID.class));

        assertTrue(result.isPresent());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForUniqueResultClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getString(1)).thenReturn("unique");

        Nullable<String> result = query.queryForUniqueValue(String.class);

        assertTrue(result.isPresent());
        assertEquals("unique", result.orElse(null));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForUniqueResultDuplicated() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true);
        when(mockResultSet.getString(1)).thenReturn("first", "second");

        assertThrows(DuplicateResultException.class, () -> query.queryForUniqueValue(String.class));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForUniqueResultType() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getBigDecimal(1)).thenReturn(new BigDecimal("123.45"));

        Nullable<BigDecimal> result = query.queryForUniqueValue(Type.of(BigDecimal.class));

        assertTrue(result.isPresent());
        assertEquals(new BigDecimal("123.45"), result.orElse(null));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryThenAccept() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        List<Dataset> captured = new ArrayList<>();
        query.queryThenAccept(dataset -> captured.add(dataset));

        assertEquals(1, captured.size());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryThenAcceptWithEntityClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        List<Dataset> captured = new ArrayList<>();
        query.queryThenAccept(TestEntity.class, dataset -> captured.add(dataset));

        assertEquals(1, captured.size());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryThenApply() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        Integer result = query.queryThenApply(dataset -> dataset.size());

        assertNotNull(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryThenApplyWithEntityClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        Integer result = query.queryThenApply(TestEntity.class, dataset -> dataset.size());

        assertNotNull(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryWithBiResultExtractor() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        Map<String, Object> result = query.query((rs, labels) -> {
            Map<String, Object> map = new HashMap<>();
            if (rs.next()) {
                for (String label : labels) {
                    map.put(label, rs.getObject(label));
                }
            }
            return map;
        });

        assertNotNull(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryWithResultExtractor() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getString(1)).thenReturn("abc");

        String result = query.query(rs -> {
            return rs.next() ? rs.getString(1) : null;
        });

        assertNotNull(result);
        verify(mockResultSet).close();
    }
}
