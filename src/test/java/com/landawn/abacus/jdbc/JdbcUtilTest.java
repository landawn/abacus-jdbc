package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.u.Nullable;
import com.landawn.abacus.util.u.Optional;

class JdbcUtilTest extends JdbcUtilTestSupport {

    @Test
    public void testColumnHelpersPreserveNullValuesAndSkipNoOps() throws SQLException {
        assertEquals(0, JdbcUtil.skip(null, 0));
        assertEquals(0L, JdbcUtil.skip(null, -1L));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.skip(null, 1));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.skip(null, 1L));
        when(mockResultSet.getMetaData()).thenReturn(mockResultSetMetaData);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(mockResultSetMetaData.getColumnName(1)).thenReturn("id");
        assertEquals(-1, JdbcUtil.getColumnIndex(mockResultSet, null));
        assertEquals(-1, JdbcUtil.getColumnIndex(mockResultSetMetaData, null));
        assertNull(JdbcUtil.getColumnValue(mockResultSet, 1));
    }

    @Test
    public void testColumnHelpersRejectNullResourcesInSignatureOrder() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.getColumnCount(null));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.getColumnLabels(null));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.getColumnLabel(null, 1));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.getColumnIndex((ResultSet) null, "id"));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.getColumnIndex((ResultSetMetaData) null, "id"));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.getColumnValue(null, 1));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.getColumnValue(null, "id"));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.getAllColumnValues(null, 1));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.getAllColumnValues(null, "id"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> JdbcUtil.getColumnValue(null, 1, null)).getMessage().contains("rs"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> JdbcUtil.getColumnValue(null, "id", null)).getMessage().contains("rs"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> JdbcUtil.getColumnValue(mockResultSet, 1, null)).getMessage().contains("targetClass"));
        verifyNoInteractions(mockResultSet);
    }

    @Test
    public void testExecuteQuery() throws SQLException {
        String sql = "SELECT * FROM users";
        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("name");
        when(mockResultSet.next()).thenReturn(false);

        Dataset result = JdbcUtil.executeQuery(mockDataSource, sql);
        assertNotNull(result);
        // No rows were returned, so the dataset should be empty
        assertEquals(0, result.size(), "dataset should have zero rows when ResultSet is empty");
    }

    @Test
    public void testExecuteUpdate() throws SQLException {
        String sql = "UPDATE users SET name = ? WHERE id = ?";
        when(mockPreparedStatement.executeUpdate()).thenReturn(1);

        int affected = JdbcUtil.executeUpdate(mockDataSource, sql, "John", 1L);
        assertEquals(1, affected);
    }

    @Test
    public void testExecute() throws SQLException {
        String sql = "CREATE TABLE test (id INT)";
        when(mockPreparedStatement.execute()).thenReturn(false);

        boolean hasResultSet = JdbcUtil.execute(mockDataSource, sql);
        assertFalse(hasResultSet);
    }

    // SQL or parameters that can't be bound are rejected before a connection is acquired (or a statement prepared): checked
    // only when binding, a data source that couldn't hand out a connection reported its own failure instead.
    @Test
    public void testExecuteFamilyRejectsUnbindableArgumentsBeforeAcquiringConnection() throws SQLException {
        when(mockDataSource.getConnection()).thenThrow(new SQLException("pool exhausted"));

        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.executeQuery(mockDataSource, "   "));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.executeQuery(mockDataSource, "SELECT * FROM users WHERE id = :id", Map.of("name", "x")));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> JdbcUtil.executeUpdate(mockDataSource, "UPDATE users SET name = ? WHERE id = ?", "John"))
                .getMessage()
                .contains("but only 1 specified"));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.executeUpdate(mockDataSource, "UPDATE users SET name = ? WHERE id = ?"));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.execute(mockDataSource, "UPDATE users SET name = :name WHERE id = ?", "John", 1));
        org.mockito.Mockito.verify(mockDataSource, org.mockito.Mockito.never()).getConnection();

        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.executeQuery(mockConnection, "SELECT * FROM users WHERE id = ?"));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.executeUpdate(mockConnection, "UPDATE users SET name = ? WHERE id = ?", List.of("John")));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.execute(mockConnection, "SELECT * FROM users WHERE id = :id", Map.of()));
        org.mockito.Mockito.verify(mockConnection, org.mockito.Mockito.never()).prepareStatement(org.mockito.ArgumentMatchers.anyString());

        // Arguments that can be bound still reach the statement: a single array or collection supplies the values.
        when(mockPreparedStatement.executeUpdate()).thenReturn(1);
        assertEquals(1, JdbcUtil.executeUpdate(mockConnection, "UPDATE users SET name = ? WHERE id = ?", List.of("John", 1L)));
        assertEquals(1, JdbcUtil.executeUpdate(mockConnection, "UPDATE users SET name = :name WHERE id = :id", Map.of("name", "John", "id", 1L)));
    }

    // isNotEmptyResult: null and every empty/non-empty container branch.
    @Test
    public void testIsNotEmptyResult_NullAndCollections() {
        assertFalse(JdbcUtil.isNotEmptyResult(null));

        assertFalse(JdbcUtil.isNotEmptyResult(new java.util.ArrayList<>()));
        assertTrue(JdbcUtil.isNotEmptyResult(List.of("a")));

        assertFalse(JdbcUtil.isNotEmptyResult(new java.util.HashMap<>()));
        assertTrue(JdbcUtil.isNotEmptyResult(Map.of("k", "v")));
    }

    // isNotEmptyResult: Dataset, Iterable (non-Collection) and Iterator branches, plus the fall-through.
    @Test
    public void testIsNotEmptyResult_DatasetIterableIterator() {
        final com.landawn.abacus.util.Dataset empty = com.landawn.abacus.util.Dataset.rows(List.of("id"), new Object[][] {});
        final com.landawn.abacus.util.Dataset nonEmpty = com.landawn.abacus.util.Dataset.rows(List.of("id"), new Object[][] { { 1L } });
        assertFalse(JdbcUtil.isNotEmptyResult(empty));
        assertTrue(JdbcUtil.isNotEmptyResult(nonEmpty));

        // a bare Iterable (lambda) that is not a Collection/Map/Dataset
        final Iterable<String> emptyIterable = () -> java.util.Collections.<String> emptyIterator();
        final Iterable<String> nonEmptyIterable = () -> List.of("x").iterator();
        assertFalse(JdbcUtil.isNotEmptyResult(emptyIterable));
        assertTrue(JdbcUtil.isNotEmptyResult(nonEmptyIterable));

        // an Iterator (not an Iterable)
        assertFalse(JdbcUtil.isNotEmptyResult(java.util.Collections.emptyIterator()));
        assertTrue(JdbcUtil.isNotEmptyResult(List.of("x").iterator()));

        // any other object type -> treated as a non-empty result.
        assertTrue(JdbcUtil.isNotEmptyResult("scalar"));
    }

    // Regression: isTableNotExistsException relied solely on SQLState (which Oracle/SQL Server do not
    // populate reliably) and Locale-dependent message matching. The fix adds vendor errorCode checks
    // and uses Locale.ROOT for lowercasing.
    @Test
    public void testIsTableNotExistsException_VendorErrorCodes() throws Exception {
        final java.lang.reflect.Method m = JdbcUtil.class.getDeclaredMethod("isTableNotExistsException", Throwable.class);
        m.setAccessible(true);

        // Oracle ORA-00942 — message is in a non-English form so the message check would miss it.
        SQLException oracle = new SQLException("ORA-00942: opaque error", "72000", 942);
        assertEquals(Boolean.TRUE, m.invoke(null, oracle));

        // SQL Server error 208 ("Invalid object name") with non-matching SQLState
        SQLException mssql = new SQLException("Invalid object name 'dbo.foo'", "S0002", 208);
        assertEquals(Boolean.TRUE, m.invoke(null, mssql));

        // MySQL ER_NO_SUCH_TABLE
        SQLException mysql = new SQLException("Table 'db.foo' doesn't exist", "42S02", 1146);
        assertEquals(Boolean.TRUE, m.invoke(null, mysql));

        // DB2 SQLState 42704
        SQLException db2 = new SQLException("undefined name", "42704", -204);
        assertEquals(Boolean.TRUE, m.invoke(null, db2));

        // Unrelated SQLException must NOT match.
        SQLException unrelated = new SQLException("primary key violation", "23000", 1062);
        assertEquals(Boolean.FALSE, m.invoke(null, unrelated));
    }

    @Test
    public void testRunAsync() throws Exception {
        final boolean[] executed = { false };
        ContinuableFuture<Void> future = JdbcUtil.runAsync(() -> {
            executed[0] = true;
        });

        assertNotNull(future);
        Void result = future.get(1, TimeUnit.SECONDS);
        assertNull(result, "a Runnable-based future should resolve to null");
        assertTrue(executed[0], "the async action should have been executed");
    }

    @Test
    public void testRunAsyncMultiple() throws Exception {
        final boolean[] executed = { false, false };
        Tuple2<ContinuableFuture<Void>, ContinuableFuture<Void>> futures = JdbcUtil.runAsync(() -> {
            executed[0] = true;
        }, () -> {
            executed[1] = true;
        });

        assertNotNull(futures._1);
        assertNotNull(futures._2);

        futures._1.get(1, TimeUnit.SECONDS);
        futures._2.get(1, TimeUnit.SECONDS);
        // Both async actions must have completed
        assertTrue(executed[0], "first async action should have been executed");
        assertTrue(executed[1], "second async action should have been executed");
    }

    @Test
    public void testRunAsyncTriple() throws Exception {
        final boolean[] executed = { false, false, false };
        Tuple3<ContinuableFuture<Void>, ContinuableFuture<Void>, ContinuableFuture<Void>> futures = JdbcUtil.runAsync(() -> {
            executed[0] = true;
        }, () -> {
            executed[1] = true;
        }, () -> {
            executed[2] = true;
        });

        assertNotNull(futures._1);
        assertNotNull(futures._2);
        assertNotNull(futures._3);

        // Wait for all three to complete and verify execution
        futures._1.get(1, TimeUnit.SECONDS);
        futures._2.get(1, TimeUnit.SECONDS);
        futures._3.get(1, TimeUnit.SECONDS);
        assertTrue(executed[0], "first async action should have been executed");
        assertTrue(executed[1], "second async action should have been executed");
        assertTrue(executed[2], "third async action should have been executed");
    }

    @Test
    public void testRunAsyncWithParameter() throws Exception {
        ContinuableFuture<Void> future = JdbcUtil.runAsync("test", str -> {
            assertEquals("test", str);
        });

        assertNotNull(future);
        future.get(1, TimeUnit.SECONDS);
    }

    @Test
    public void testRunAsyncWithTwoParameters() throws Exception {
        ContinuableFuture<Void> future = JdbcUtil.runAsync("test", 123, (str, num) -> {
            assertEquals("test", str);
            assertEquals(123, num);
        });

        assertNotNull(future);
        future.get(1, TimeUnit.SECONDS);
    }

    @Test
    public void testRunAsyncWithThreeParameters() throws Exception {
        ContinuableFuture<Void> future = JdbcUtil.runAsync("a", "b", "c", (p1, p2, p3) -> {
            assertEquals("a", p1);
            assertEquals("b", p2);
            assertEquals("c", p3);
        });

        assertNotNull(future);
        future.get(1, TimeUnit.SECONDS);
    }

    @Test
    public void testCallAsync() throws Exception {
        ContinuableFuture<String> future = JdbcUtil.callAsync(() -> "result");

        assertNotNull(future);
        assertEquals("result", future.get(1, TimeUnit.SECONDS));
    }

    @Test
    public void testCallAsyncMultiple() throws Exception {
        Tuple2<ContinuableFuture<String>, ContinuableFuture<Integer>> futures = JdbcUtil.callAsync(() -> "test", () -> 123);

        assertEquals("test", futures._1.get(1, TimeUnit.SECONDS));
        assertEquals(123, futures._2.get(1, TimeUnit.SECONDS));
    }

    @Test
    public void testCallAsyncTriple() throws Exception {
        Tuple3<ContinuableFuture<String>, ContinuableFuture<Integer>, ContinuableFuture<Boolean>> futures = JdbcUtil.callAsync(() -> "test", () -> 123,
                () -> true);

        assertEquals("test", futures._1.get(1, TimeUnit.SECONDS));
        assertEquals(123, futures._2.get(1, TimeUnit.SECONDS));
        assertTrue(futures._3.get(1, TimeUnit.SECONDS));
    }

    @Test
    public void testCallAsyncWithParameter() throws Exception {
        ContinuableFuture<String> future = JdbcUtil.callAsync("input", str -> str + "!");

        assertEquals("input!", future.get(1, TimeUnit.SECONDS));
    }

    @Test
    public void testCallAsyncWithTwoParameters() throws Exception {
        ContinuableFuture<String> future = JdbcUtil.callAsync("a", "b", (p1, p2) -> p1 + p2);

        assertEquals("ab", future.get(1, TimeUnit.SECONDS));
    }

    @Test
    public void testCallAsyncWithThreeParameters() throws Exception {
        ContinuableFuture<String> future = JdbcUtil.callAsync("a", "b", "c", (p1, p2, p3) -> p1 + p2 + p3);

        assertEquals("abc", future.get(1, TimeUnit.SECONDS));
    }

    @Test
    public void testParseSql() {
        String sql = "SELECT * FROM users WHERE id = :id";
        ParsedSql parsed = JdbcUtil.parseSql(sql);

        assertNotNull(parsed);
        assertEquals(1, parsed.parameterCount());
        assertTrue(parsed.namedParameters().contains("id"));
    }

    @Test
    public void testIsNullOrDefault() {
        assertTrue(JdbcUtil.isNullOrDefault(null));
        assertTrue(JdbcUtil.isNullOrDefault(0));
        assertTrue(JdbcUtil.isNullOrDefault(0L));
        assertTrue(JdbcUtil.isNullOrDefault(0D));
        assertTrue(JdbcUtil.isNullOrDefault(false));

        assertFalse(JdbcUtil.isNullOrDefault(1));
        assertFalse(JdbcUtil.isNullOrDefault(new BigDecimal("1E-400")));
        assertFalse(JdbcUtil.isNullOrDefault(new BigInteger("1" + "0".repeat(400))));
        assertFalse(JdbcUtil.isNullOrDefault(""));
        assertFalse(JdbcUtil.isNullOrDefault("test"));
        assertFalse(JdbcUtil.isNullOrDefault(true));
    }

    // Documented: a type whose default value is an empty instance (java.util.Optional and its primitive siblings, abacus
    // Optional/Nullable) is default when empty, and only then.
    @Test
    public void testIsNullOrDefault_EmptyOptionalsAreDefault() {
        assertTrue(JdbcUtil.isNullOrDefault(java.util.Optional.empty()));
        assertTrue(JdbcUtil.isNullOrDefault(OptionalInt.empty()));
        assertTrue(JdbcUtil.isNullOrDefault(OptionalLong.empty()));
        assertTrue(JdbcUtil.isNullOrDefault(OptionalDouble.empty()));
        assertTrue(JdbcUtil.isNullOrDefault(Optional.empty()));
        assertTrue(JdbcUtil.isNullOrDefault(Nullable.empty()));

        assertFalse(JdbcUtil.isNullOrDefault(java.util.Optional.of(1)));
        assertFalse(JdbcUtil.isNullOrDefault(java.util.Optional.of(0)));
        assertFalse(JdbcUtil.isNullOrDefault(OptionalInt.of(0)));
        assertFalse(JdbcUtil.isNullOrDefault(OptionalLong.of(1L)));
        assertFalse(JdbcUtil.isNullOrDefault(OptionalDouble.of(0D)));
        assertFalse(JdbcUtil.isNullOrDefault(Optional.of(1)));
        assertFalse(JdbcUtil.isNullOrDefault(Nullable.of("x")));
        assertFalse(JdbcUtil.isNullOrDefault(Nullable.of(null)));
    }

    // isNullOrDefault additional edge cases
    @Test
    public void testIsNullOrDefault_FloatZero() {
        assertTrue(JdbcUtil.isNullOrDefault(0.0f));
    }

    @Test
    public void testIsNullOrDefault_ShortZero() {
        assertTrue(JdbcUtil.isNullOrDefault((short) 0));
    }

    @Test
    public void testIsNullOrDefault_ByteZero() {
        assertTrue(JdbcUtil.isNullOrDefault((byte) 0));
    }

    @Test
    public void testIsNullOrDefault_NonZeroFloat() {
        assertFalse(JdbcUtil.isNullOrDefault(0.1f));
    }

    @Test
    public void testIsNullOrDefault_NonZeroShort() {
        assertFalse(JdbcUtil.isNullOrDefault((short) 1));
    }

    @Test
    public void testDefaultCacheCapacity() {
        assertEquals(1000, JdbcUtil.DEFAULT_CACHE_CAPACITY);
    }

    @Test
    public void testDefaultCacheEvictDelay() {
        assertEquals(3 * 1000, JdbcUtil.DEFAULT_CACHE_EVICT_DELAY);
    }

    @Test
    public void testDefaultCacheLiveTime() {
        assertEquals(30 * 60 * 1000, JdbcUtil.DEFAULT_CACHE_LIVE_TIME);
    }

    @Test
    public void testDefaultCacheMaxIdleTime() {
        assertEquals(3 * 60 * 1000, JdbcUtil.DEFAULT_CACHE_MAX_IDLE_TIME);
    }

    @Test
    public void testDefaultFetchSizeForBigResult() {
        assertEquals(1000, JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET);
    }
}
