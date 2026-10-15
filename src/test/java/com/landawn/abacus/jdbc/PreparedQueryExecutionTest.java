package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.stream.Collectors;
import com.landawn.abacus.util.stream.Stream;
import com.landawn.abacus.util.Tuple.Tuple2;

class PreparedQueryExecutionTest extends PreparedQueryTestSupport {

    @Test
    public void testAddBatch() throws SQLException {
        PreparedQuery result = query.addBatch();
        assertSame(query, result);
        verify(mockStmt).addBatch();
        assertTrue(query.isBatch);
    }

    @Test
    public void testBatchInsert() throws SQLException {
        when(mockStmt.executeBatch()).thenReturn(new int[] { 1, 1, 1 });
        when(mockStmt.getGeneratedKeys()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, true, true, false);
        when(mockResultSet.getObject(1)).thenReturn(101L, 102L, 103L);

        List<Long> result = query.batchInsert();

        assertEquals(Arrays.asList(101L, 102L, 103L), result);
        verify(mockStmt).executeBatch();
    }

    @Test
    public void testBatchInsertWithBiExtractor() throws SQLException {
        when(mockStmt.executeBatch()).thenReturn(new int[] { 1 });
        when(mockStmt.getGeneratedKeys()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, false);

        List<Integer> result = query.batchInsert((rs, labels) -> labels.size());

        assertEquals(Arrays.asList(1), result);
        verify(mockStmt).executeBatch();
    }

    @Test
    public void testBatchInsertWithExtractor() throws SQLException {
        when(mockStmt.executeBatch()).thenReturn(new int[] { 1, 1 });
        when(mockStmt.getGeneratedKeys()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString("uuid")).thenReturn("uuid1", "uuid2");

        List<String> result = query.batchInsert(rs -> rs.getString("uuid"));

        assertEquals(Arrays.asList("uuid1", "uuid2"), result);
        verify(mockStmt).executeBatch();
    }

    @Test
    public void testBatchUpdate() throws SQLException {
        int[] expected = { 1, 2, 0, 1 };
        when(mockStmt.executeBatch()).thenReturn(expected);

        int[] result = query.batchUpdate();

        assertArrayEquals(expected, result);
        verify(mockStmt).executeBatch();
    }

    @Test
    public void testBatchUpdateAndReturnGeneratedKeys() throws SQLException {
        when(mockStmt.executeBatch()).thenReturn(new int[] { 1, 1 });
        when(mockStmt.getGeneratedKeys()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getLong(1)).thenReturn(201L, 202L);

        Tuple2<int[], List<Long>> result = query.batchUpdateAndReturnGeneratedKeys(rs -> rs.getLong(1));

        assertArrayEquals(new int[] { 1, 1 }, result._1);
        assertEquals(Arrays.asList(201L, 202L), result._2);
        verify(mockStmt).executeBatch();
    }

    @Test
    public void testBatchUpdateAndReturnGeneratedKeysWithBiExtractor() throws SQLException {
        when(mockStmt.executeBatch()).thenReturn(new int[] { 1 });
        when(mockStmt.getGeneratedKeys()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, false);

        Tuple2<int[], List<Map<String, Object>>> result = query.batchUpdateAndReturnGeneratedKeys((rs, labels) -> {
            Map<String, Object> map = new HashMap<>();
            map.put("count", labels.size());
            return map;
        });

        assertArrayEquals(new int[] { 1 }, result._1);
        assertEquals(1, result._2.size());
        verify(mockStmt).executeBatch();
    }

    @Test
    public void testCount() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, true, false);

        int count = query.count();

        assertEquals(3, count);
        verify(mockResultSet).close();
    }

    @Test
    public void testCountWithBiFilter() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("yes", "no");

        int count = query.count((rs, labels) -> rs.getString(1).equals("yes"));

        assertEquals(1, count);
        verify(mockResultSet).close();
    }

    @Test
    public void testCountWithFilter() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, true, false);
        when(mockResultSet.getInt(1)).thenReturn(5, 10, 15);

        int count = query.count(rs -> rs.getInt(1) > 8);

        assertEquals(2, count);
        verify(mockResultSet).close();
    }

    @Test
    public void testExecute() throws SQLException {
        when(mockStmt.execute()).thenReturn(true);

        boolean result = query.execute();

        assertTrue(result);
        verify(mockStmt).execute();
    }

    @Test
    public void testExecuteThenAccept() throws SQLException {
        when(mockStmt.execute()).thenReturn(false);
        when(mockStmt.getUpdateCount()).thenReturn(5);

        List<Integer> captured = new ArrayList<>();
        query.executeThenAccept(stmt -> captured.add(stmt.getUpdateCount()));

        assertEquals(Arrays.asList(5), captured);
        verify(mockStmt).execute();
    }

    @Test
    public void testExecuteThenAcceptBiConsumer() throws SQLException {
        when(mockStmt.execute()).thenReturn(true);

        List<String> captured = new ArrayList<>();
        query.executeThenAccept((stmt, isResultSet) -> captured.add(isResultSet ? "RS" : "UC"));

        assertEquals(Arrays.asList("RS"), captured);
        verify(mockStmt).execute();
    }

    @Test
    public void testExecuteThenApply() throws SQLException {
        when(mockStmt.execute()).thenReturn(false);
        when(mockStmt.getUpdateCount()).thenReturn(10);

        Integer result = query.executeThenApply(stmt -> stmt.getUpdateCount());

        assertEquals(10, result);
        verify(mockStmt).execute();
    }

    @Test
    public void testExecuteThenApplyBiFunction() throws SQLException {
        when(mockStmt.execute()).thenReturn(true);

        String result = query.executeThenApply((stmt, isResultSet) -> isResultSet ? "ResultSet" : "UpdateCount");

        assertEquals("ResultSet", result);
        verify(mockStmt).execute();
    }

    //    @Test
    //    public void teststreamAllResultSets() throws SQLException {
    //        when(mockStmt.getMoreResults()).thenReturn(true, false);
    //        when(mockStmt.getUpdateCount()).thenReturn(-1);
    //        when(mockResultSet.next()).thenReturn(true, false);
    //
    //        try (Stream<Dataset> stream = query.streamAllResultSets()) {
    //            long count = stream.count();
    //            assertEquals(1, count);
    //        }
    //    }
    //
    //    @Test
    //    public void teststreamAllResultSetsWithExtractor() throws SQLException {
    //        when(mockStmt.getMoreResults()).thenReturn(false);
    //        when(mockStmt.getUpdateCount()).thenReturn(-1);
    //        when(mockResultSet.next()).thenReturn(true, false);
    //
    //        try (Stream<Integer> stream = query.streamAllResultSets(rs -> {
    //            int count = 0;
    //            while (rs.next())
    //                count++;
    //            return count;
    //        })) {
    //            List<Integer> result = stream.collect(Collectors.toList());
    //            assertEquals(Arrays.asList(1), result);
    //        }
    //    }

    @Test
    public void testExists() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);

        boolean result = query.exists();

        assertTrue(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testExistsFalse() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        boolean result = query.exists();

        assertFalse(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testNotExists() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        boolean result = query.notExists();

        assertTrue(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testNotExistsFalse() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);

        boolean result = query.notExists();

        assertFalse(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testUpdate() throws SQLException {
        when(mockStmt.executeUpdate()).thenReturn(5);

        int result = query.update();

        assertEquals(5, result);
        verify(mockStmt).executeUpdate();
    }

    @Test
    public void testUpdateAndReturnGeneratedKeys() throws SQLException {
        when(mockStmt.executeUpdate()).thenReturn(1);
        when(mockStmt.getGeneratedKeys()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getLong(1)).thenReturn(999L);

        Tuple2<Integer, List<Long>> result = query.updateAndReturnGeneratedKeys(rs -> rs.getLong(1));

        assertEquals(1, result._1.intValue());
        assertEquals(Arrays.asList(999L), result._2);
        verify(mockStmt).executeUpdate();
    }

    @Test
    public void testUpdateAndReturnGeneratedKeysWithBiExtractor() throws SQLException {
        when(mockStmt.executeUpdate()).thenReturn(2);
        when(mockStmt.getGeneratedKeys()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, true, false);

        Tuple2<Integer, List<String>> result = query.updateAndReturnGeneratedKeys((rs, labels) -> "key");

        assertEquals(2, result._1.intValue());
        assertEquals(Arrays.asList("key", "key"), result._2);
        verify(mockStmt).executeUpdate();
    }
}
