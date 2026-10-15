package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.Jdbc.BiRowMapper;
import com.landawn.abacus.jdbc.Jdbc.RowMapper;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.stream.ObjIteratorEx;
import com.landawn.abacus.util.stream.Stream;

class JdbcUtilStreamTest extends JdbcUtilTestSupport {

    @Test
    public void testStream() throws SQLException {
        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn(1L, 2L);
        when(mockResultSet.getObject(2)).thenReturn("John", "Jane");

        Stream<Object[]> stream = JdbcUtil.stream(mockResultSet);
        List<Object[]> list = stream.toList();

        assertEquals(2, list.size());
        assertEquals(1L, list.get(0)[0]);
        assertEquals("John", list.get(0)[1]);
    }

    @Test
    public void testStreamWithTargetClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getString(1)).thenReturn("test");

        Stream<String[]> stream = JdbcUtil.stream(mockResultSet, String[].class);
        List<String[]> list = stream.toList();

        assertEquals(1, list.size());
    }

    @Test
    public void testStreamWithRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getLong(1)).thenReturn(1L, 2L);

        RowMapper<Long> mapper = rs -> rs.getLong(1);
        Stream<Long> stream = JdbcUtil.stream(mockResultSet, mapper);
        List<Long> list = stream.toList();

        assertEquals(2, list.size());
        assertEquals(1L, list.get(0));
        assertEquals(2L, list.get(1));
    }

    @Test
    public void testStreamWithColumnIndex() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn("val1", "val2");
        when(mockResultSet.getStatement()).thenReturn(mockStatement);
        when(mockStatement.getConnection()).thenReturn(mockConnection);
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("H2");

        Stream<String> stream = JdbcUtil.stream(mockResultSet, 1);
        List<String> list = stream.toList();

        assertEquals(2, list.size());
        assertEquals("val1", list.get(0));
        assertEquals("val2", list.get(1));
    }

    @Test
    public void testStreamWithColumnName() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn("value");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("name");

        Stream<String> stream = JdbcUtil.stream(mockResultSet, "name");
        List<String> list = stream.toList();

        assertEquals(1, list.size());
    }

    @Test
    public void teststreamAllResultSets() throws SQLException {
        when(mockStatement.getResultSet()).thenReturn(mockResultSet);
        when(mockStatement.getMoreResults()).thenReturn(false);
        when(mockStatement.getUpdateCount()).thenReturn(-1);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("col");
        when(mockResultSet.next()).thenReturn(false);

        Stream<Dataset> stream = JdbcUtil.streamAllResultSets(mockStatement);
        List<Dataset> list = stream.toList();

        assertNotNull(list);
        // The statement has one result set and getMoreResults returns false, so exactly one Dataset
        assertEquals(1, list.size(), "should yield one Dataset for a single result set");
        // The single result set had no rows
        assertEquals(0, list.get(0).size(), "the single Dataset should be empty");
    }

    @Test
    public void testStreamAllResultSets_multipleResultSets() throws SQLException {
        // Simulate a stored procedure returning two result sets.
        // The second ResultSet is a separate mock.
        ResultSet mockResultSet2 = mock(ResultSet.class);
        ResultSetMetaData mockResultSetMetaData2 = mock(ResultSetMetaData.class);

        // First result set: one row with column "a"
        when(mockResultSet.getMetaData()).thenReturn(mockResultSetMetaData);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("a");
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn("row1");

        // Second result set: one row with column "b"
        when(mockResultSet2.getMetaData()).thenReturn(mockResultSetMetaData2);
        when(mockResultSetMetaData2.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData2.getColumnLabel(1)).thenReturn("b");
        when(mockResultSet2.next()).thenReturn(true, false);
        when(mockResultSet2.getObject(1)).thenReturn("row2");

        // First call: getResultSet() returns mockResultSet (first result set is current)
        when(mockStatement.getResultSet()).thenReturn(mockResultSet);
        // After next() consumes the first RS, getMoreResults(KEEP_CURRENT_RESULT) returns true (second RS available)
        when(mockStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(true, false);
        // When hasNext() is called for the second RS, getResultSet() returns mockResultSet2
        when(mockStatement.getResultSet()).thenReturn(mockResultSet, mockResultSet2);
        // After consuming the second RS, getMoreResults(KEEP_CURRENT_RESULT) returns false; getUpdateCount() == -1
        when(mockStatement.getUpdateCount()).thenReturn(-1);

        Stream<Dataset> stream = JdbcUtil.streamAllResultSets(mockStatement);
        List<Dataset> list = stream.toList();

        assertEquals(2, list.size(), "should yield two Datasets for two result sets");
        assertEquals(1, list.get(0).size(), "first Dataset should have one row");
        assertEquals(1, list.get(1).size(), "second Dataset should have one row");
    }

    // The public stream API over the deferred-advance fallback extracts every result set before advancing past it.
    @Test
    public void testStreamAllResultSets_KeepCurrentResultRejected_ExtractsEveryResultSet() throws SQLException {
        final ResultSet rs1 = mock(ResultSet.class);
        final ResultSet rs2 = mock(ResultSet.class);
        when(rs1.next()).thenReturn(true, false);
        when(rs2.next()).thenReturn(true, true, false);
        when(mockStatement.getResultSet()).thenReturn(rs1, rs2);
        when(mockStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenThrow(new SQLException("This operation is not supported."));
        when(mockStatement.getConnection()).thenReturn(mockConnection);
        when(mockDatabaseMetaData.supportsMultipleOpenResults()).thenReturn(false);
        when(mockStatement.getMoreResults()).thenReturn(true, false);
        when(mockStatement.getUpdateCount()).thenReturn(-1);

        try (Stream<Integer> rowCounts = JdbcUtil.streamAllResultSets(mockStatement, (Jdbc.ResultExtractor<Integer>) rs -> {
            int count = 0;

            while (rs.next()) {
                count++;
            }

            return count;
        })) {
            assertEquals(List.of(1, 2), rowCounts.toList());
        }

        verify(mockStatement, never()).getMoreResults(Statement.KEEP_CURRENT_RESULT);
    }

    @Test
    public void testStreamAllResultSets_EarlyCloseClosesPrefetchedResultSet() throws SQLException {
        final ResultSet secondResultSet = mock(ResultSet.class);

        when(mockStatement.getResultSet()).thenReturn(mockResultSet, secondResultSet);
        when(mockStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(true);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("value");
        when(mockResultSet.next()).thenReturn(false);

        try (Stream<Dataset> stream = JdbcUtil.streamAllResultSets(mockStatement)) {
            assertTrue(stream.first().isPresent(), "the first ResultSet should be extracted");
        }

        verify(mockResultSet).close();
        verify(secondResultSet).close();
        verify(mockStatement, never()).close();
    }

    // KEEP_CURRENT_RESULT rejected with SQLFeatureNotSupportedException although the driver's capability could not be
    // read (no connection): the iterator falls back to a deferred getMoreResults(), delivers every result set while it
    // is still open, never retries KEEP_CURRENT_RESULT, and stops at the end of the results.
    @Test
    public void testIterateAllResultSets_KeepCurrentResultNotSupported_DefersAdvance() throws SQLException {
        final ResultSet rs1 = mock(ResultSet.class);
        final ResultSet rs2 = mock(ResultSet.class);
        when(mockStatement.getResultSet()).thenReturn(rs1, rs2);
        when(mockStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenThrow(new java.sql.SQLFeatureNotSupportedException("KEEP_CURRENT_RESULT"));
        when(mockStatement.getMoreResults()).thenReturn(true, false);
        when(mockStatement.getUpdateCount()).thenReturn(-1);

        final com.landawn.abacus.util.stream.ObjIteratorEx<ResultSet> iter = JdbcUtil.iterateAllResultSets(mockStatement, true);

        assertTrue(iter.hasNext());
        iter.next().next();
        verify(rs1).next();
        verify(mockStatement, never()).getMoreResults();
        verify(rs1, never()).close();

        assertTrue(iter.hasNext());
        verify(mockStatement).getMoreResults();
        iter.next().next();
        verify(rs2).next();

        assertFalse(iter.hasNext());
        assertThrows(NoSuchElementException.class, iter::next);
        verify(mockStatement).getMoreResults(Statement.KEEP_CURRENT_RESULT);
        verify(mockStatement, org.mockito.Mockito.times(2)).getMoreResults();
        iter.closeResource();
    }

    // Regression: a driver reporting supportsMultipleOpenResults() == false used to have EVERY getMoreResults(KEEP_CURRENT_RESULT)
    // failure reinterpreted as "unsupported", so a genuine failure (e.g. a timeout) was swallowed and iteration ended
    // normally. The capability is now checked up front: KEEP_CURRENT_RESULT is never called for such a driver, and a
    // failure of the deferred getMoreResults() propagates.
    @Test
    public void testIterateAllResultSets_DriverWithoutMultipleOpenResults_NeverCallsKeepCurrentResultAndPropagatesTimeout() throws SQLException {
        final java.sql.SQLTimeoutException timeout = new java.sql.SQLTimeoutException("query timed out");
        when(mockStatement.getResultSet()).thenReturn(mockResultSet);
        when(mockStatement.getConnection()).thenReturn(mockConnection);
        when(mockConnection.getMetaData()).thenReturn(mockDatabaseMetaData);
        when(mockDatabaseMetaData.supportsMultipleOpenResults()).thenReturn(false);
        when(mockStatement.getMoreResults()).thenThrow(timeout);

        final com.landawn.abacus.util.stream.ObjIteratorEx<ResultSet> iter = JdbcUtil.iterateAllResultSets(mockStatement, true);

        iter.next().next();
        verify(mockResultSet).next();
        assertSame(timeout, assertThrows(UncheckedSQLException.class, iter::hasNext).getCause());
        verify(mockStatement, never()).getMoreResults(Statement.KEEP_CURRENT_RESULT);
        iter.closeResource();
    }

    // A timeout from getMoreResults(KEEP_CURRENT_RESULT) on a driver that declares multiple-open-result support, or
    // whose capability is unknown, is a real failure: it propagates and the undelivered result set is closed.
    @Test
    public void testIterateAllResultSets_KeepCurrentResultTimeoutIsNeverTreatedAsUnsupported() throws SQLException {
        final java.sql.SQLTimeoutException timeout = new java.sql.SQLTimeoutException("query timed out");
        when(mockStatement.getResultSet()).thenReturn(mockResultSet);
        when(mockStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenThrow(timeout);

        // Capability unknown (no connection).
        when(mockStatement.getConnection()).thenReturn(null);
        assertSame(timeout, assertThrows(UncheckedSQLException.class, JdbcUtil.iterateAllResultSets(mockStatement, true)::next).getCause());

        // Capability declared.
        when(mockStatement.getConnection()).thenReturn(mockConnection);
        when(mockConnection.getMetaData()).thenReturn(mockDatabaseMetaData);
        when(mockDatabaseMetaData.supportsMultipleOpenResults()).thenReturn(true);
        assertSame(timeout, assertThrows(UncheckedSQLException.class, JdbcUtil.iterateAllResultSets(mockStatement, true)::next).getCause());

        verify(mockResultSet, org.mockito.Mockito.times(2)).close();
        verify(mockStatement, never()).getMoreResults();
    }

    // Regression: streamAllResultSets(stmt) hardcoded isFirstResultSet=true. If the user called
    // stmt.execute(...) and the first result was an update count (so stmt.getResultSet() returns null),
    // the iterator broke out of its loop and the stream terminated empty even when more ResultSets
    // followed. The fix falls through to advance via getMoreResults/getUpdateCount in that case.
    @Test
    public void testIterateAllResultSets_FirstIsUpdateCountAdvancesToNextResultSet() throws Exception {
        final Statement stmt = mock(Statement.class);
        final ResultSet realRs = mock(ResultSet.class);

        // 1st hasNext: getResultSet() returns null (update count); fall through; getUpdateCount()
        // returns >=0 so getMoreResults() runs; 2nd iteration getResultSet() returns the real RS.
        when(stmt.getResultSet()).thenReturn(null).thenReturn(realRs);
        when(stmt.getUpdateCount()).thenReturn(1).thenReturn(-1);
        when(stmt.getMoreResults()).thenReturn(true);
        when(stmt.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(false);

        final java.lang.reflect.Method m = JdbcUtil.class.getDeclaredMethod("iterateAllResultSets", Statement.class, boolean.class);
        m.setAccessible(true);

        @SuppressWarnings("unchecked")
        java.util.Iterator<ResultSet> it = (java.util.Iterator<ResultSet>) m.invoke(null, stmt, true);

        assertTrue(it.hasNext(), "iterator must surface the ResultSet that comes after the leading update count");
        final ResultSet surfaced = it.next();
        // Surfaced ResultSets are wrapped for parity with single-ResultSet reads (executeQuery).
        assertTrue(surfaced instanceof com.landawn.abacus.jdbc.ResultSetProxy);
        surfaced.next();
        verify(realRs).next(); // the proxy delegates to the underlying ResultSet
        assertFalse(it.hasNext(), "iterator must terminate when there are no more results");
    }

    @Test
    @Tag("2025")
    public void testIterateAllResultSets_NextClosesResultSetIfGetMoreResultsThrows() throws SQLException {
        // hasNext() pulls the first ResultSet into the holder via stmt.getResultSet().
        when(mockStatement.getResultSet()).thenReturn(mockResultSet);
        // next() removes it from the holder and calls stmt.getMoreResults(KEEP_CURRENT_RESULT)
        // to advance. If that call fails, the caller never receives the ResultSet, so the iterator
        // must close it itself rather than leaking it until the statement is closed.
        when(mockStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenThrow(new SQLException("driver error during getMoreResults"));

        com.landawn.abacus.util.stream.ObjIteratorEx<ResultSet> iter = JdbcUtil.iterateAllResultSets(mockStatement, true);
        try {
            assertTrue(iter.hasNext(), "first ResultSet should be available");
            UncheckedSQLException ex = assertThrows(UncheckedSQLException.class, iter::next);
            assertNotNull(ex.getCause());
            verify(mockResultSet).close();
        } finally {
            iter.closeResource();
        }
    }

    // Closing the iterator right after a deferred-advance delivery leaves the delivered result set to the caller and
    // does not advance the statement.
    @Test
    public void testIterateAllResultSets_KeepCurrentResultNotSupported_CloseAfterDeliveryDoesNotAdvance() throws SQLException {
        when(mockStatement.getResultSet()).thenReturn(mockResultSet);
        when(mockStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenThrow(new java.sql.SQLFeatureNotSupportedException("KEEP_CURRENT_RESULT"));

        final com.landawn.abacus.util.stream.ObjIteratorEx<ResultSet> iter = JdbcUtil.iterateAllResultSets(mockStatement, true);
        assertNotNull(iter.next());
        iter.closeResource();

        assertFalse(iter.hasNext());
        verify(mockStatement, never()).getMoreResults();
        verify(mockResultSet, never()).close();
    }

    // A driver that supports multiple open results reports a real failure: the undelivered result set is closed and
    // the failure is propagated.
    @Test
    public void testIterateAllResultSets_KeepCurrentResultFailureOnDriverSupportingMultipleOpenResults_Propagates() throws SQLException {
        final SQLException failure = new SQLException("deadlock");
        when(mockStatement.getResultSet()).thenReturn(mockResultSet);
        when(mockStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenThrow(failure);
        when(mockStatement.getConnection()).thenReturn(mockConnection);
        when(mockDatabaseMetaData.supportsMultipleOpenResults()).thenReturn(true);

        final com.landawn.abacus.util.stream.ObjIteratorEx<ResultSet> iter = JdbcUtil.iterateAllResultSets(mockStatement, true);

        assertSame(failure, assertThrows(UncheckedSQLException.class, iter::next).getCause());
        verify(mockResultSet).close();
        verify(mockStatement, never()).getMoreResults();
    }

    // If the driver's capabilities cannot be read, KEEP_CURRENT_RESULT support is assumed, so its failure propagates
    // unchanged (the lookup failure is only logged).
    @Test
    public void testIterateAllResultSets_MetadataFailure_AssumesKeepCurrentResultAndPropagatesItsFailure() throws SQLException {
        final SQLException failure = new SQLException("getMoreResults failed");
        final SQLException metadataFailure = new SQLException("connection is closed");
        when(mockStatement.getResultSet()).thenReturn(mockResultSet);
        when(mockStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenThrow(failure);
        when(mockStatement.getConnection()).thenReturn(mockConnection);
        when(mockConnection.getMetaData()).thenThrow(metadataFailure);

        final com.landawn.abacus.util.stream.ObjIteratorEx<ResultSet> iter = JdbcUtil.iterateAllResultSets(mockStatement, true);

        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, iter::next);
        assertSame(failure, thrown.getCause());
        assertEquals(0, failure.getSuppressed().length);
        verify(mockResultSet).close();
        verify(mockStatement, never()).getMoreResults();
    }

    @Test
    public void testPageFactoriesValidateParametersInSignatureOrder() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> JdbcUtil.queryByPage((DataSource) null, null, 0, null)).getMessage().contains("ds"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> JdbcUtil.queryByPage((Connection) null, null, 0, null)).getMessage().contains("conn"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> JdbcUtil.queryByPage(mockDataSource, null, 0, null)).getMessage().contains("sql"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> JdbcUtil.queryByPage(mockConnection, "SELECT 1", 0, null)).getMessage()
                .contains("pageSize"));
        verifyNoInteractions(mockDataSource, mockConnection);
    }

    @Test
    public void testQueryByPage() throws SQLException {
        String query = "SELECT * FROM users WHERE id > ? ORDER BY id LIMIT 10";
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn(1L);

        Stream<Dataset> pages = JdbcUtil.queryByPage(mockDataSource, query, 10, (pq, prev) -> {
            pq.setLong(1, 0);
        });

        Dataset firstPage = pages.first().orElse(null);
        assertNotNull(firstPage);
        // The mock returned one row, so the first page should contain exactly one row
        assertEquals(1, firstPage.size(), "first page should contain the single returned row");
    }

    // Regression: writeBlobToFile / writeClobToFile leaked the InputStream / Reader they obtained
    // from the Blob/Clob, because IOUtil.write closes only the output stream. The fix wraps the
    // input in try-with-resources.
    @Test
    public void testWriteBlobToFile_ClosesBinaryStream() throws Exception {
        final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.io.InputStream tracked = new java.io.ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        Blob blob = mock(Blob.class);
        when(blob.getBinaryStream()).thenReturn(tracked);

        File tmp = File.createTempFile("blob-leak-test", ".bin");
        tmp.deleteOnExit();
        try {
            JdbcUtil.writeBlobToFile(blob, tmp);
        } finally {
            tmp.delete();
        }

        assertTrue(closed.get(), "writeBlobToFile must close the InputStream returned by Blob.getBinaryStream()");
        verify(blob).free();
    }

    @Test
    public void testWriteClobToFile_ClosesCharacterStream() throws Exception {
        final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.io.Reader tracked = new java.io.StringReader("hello") {
            @Override
            public void close() {
                closed.set(true);
                super.close();
            }
        };
        Clob clob = mock(Clob.class);
        when(clob.getCharacterStream()).thenReturn(tracked);

        File tmp = File.createTempFile("clob-leak-test", ".txt");
        tmp.deleteOnExit();
        try {
            JdbcUtil.writeClobToFile(clob, tmp);
        } finally {
            tmp.delete();
        }

        assertTrue(closed.get(), "writeClobToFile must close the Reader returned by Clob.getCharacterStream()");
        verify(clob).free();
    }

    @Test
    public void testDefaultFetchSizeForStream() {
        assertEquals(100, JdbcUtil.DEFAULT_FETCH_SIZE_FOR_STREAM);
    }

    @Test
    public void testRowIteratorCountAndAdvanceRememberExhaustion() throws SQLException {
        for (int variant : new int[] { 0, 2 }) {
            for (boolean count : new boolean[] { true, false }) {
                final ResultSet rs = mock(ResultSet.class);
                when(rs.getMetaData()).thenReturn(mockResultSetMetaData);
                when(rs.next()).thenReturn(true, false).thenThrow(new SQLException("Cursor is already after the last row"));
                final ObjIteratorEx<Integer> iter = rowIterator(rs, variant);

                assertTrue(iter.hasNext());
                if (count) {
                    assertEquals(1, iter.count());
                } else {
                    iter.advance(2);
                }

                assertFalse(iter.hasNext());
                assertEquals(0, iter.count());
                assertThrows(NoSuchElementException.class, iter::next);
                verify(rs, org.mockito.Mockito.times(2)).next();
            }
        }
    }

    @Test
    public void testRowIteratorsRememberExhaustion() throws SQLException {
        for (int variant = 0; variant < 4; variant++) {
            final ResultSet rs = mock(ResultSet.class);
            when(rs.getMetaData()).thenReturn(mockResultSetMetaData);
            when(rs.next()).thenReturn(true, false).thenThrow(new SQLException("Cursor is already after the last row"));
            final ObjIteratorEx<Integer> iter = rowIterator(rs, variant);

            assertEquals(1, iter.next());
            assertFalse(iter.hasNext());
            assertFalse(iter.hasNext());
            assertThrows(NoSuchElementException.class, iter::next);
            assertEquals(0, iter.count());
            iter.advance(1);
            assertFalse(iter.hasNext());
            verify(rs, org.mockito.Mockito.times(2)).next();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void rowIteratorCloseCallbackRunsOnceWithoutClosingBorrowedResultSet(final int variant) throws SQLException {
        final AtomicInteger closes = new AtomicInteger();
        final ObjIteratorEx<Integer> iterator = closeableRowIterator(variant, closes::incrementAndGet);

        iterator.closeResource();
        iterator.closeResource();

        assertEquals(1, closes.get());
        verify(mockResultSet, never()).close();
        verify(mockResultSet, never()).next();
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void rowIteratorCloseWithoutCallbackKeepsBorrowedResultSet(final int variant) throws SQLException {
        final ObjIteratorEx<Integer> iterator = closeableRowIterator(variant, null);

        assertDoesNotThrow(iterator::closeResource);
        assertDoesNotThrow(iterator::closeResource);

        verify(mockResultSet, never()).close();
        verify(mockResultSet, never()).next();
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void failedRowIteratorCloseCallbackIsNotRetried(final int variant) throws SQLException {
        final AtomicInteger closes = new AtomicInteger();
        final IllegalStateException failure = new IllegalStateException("close failed");
        final ObjIteratorEx<Integer> iterator = closeableRowIterator(variant, () -> {
            closes.incrementAndGet();
            throw failure;
        });

        assertSame(failure, assertThrows(IllegalStateException.class, iterator::closeResource));
        assertDoesNotThrow(iterator::closeResource);

        assertEquals(1, closes.get());
        verify(mockResultSet, never()).close();
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3 })
    void rowIteratorPreservesMappingSqlException(final int variant) throws SQLException {
        final SQLException failure = new SQLException("mapping failed");
        when(mockResultSet.next()).thenReturn(true);
        final ObjIteratorEx<Integer> iterator = switch (variant) {
            case 0 -> JdbcUtil.iterate(mockResultSet, (RowMapper<Integer>) row -> {
                throw failure;
            }, null);
            case 1 -> JdbcUtil.iterate(mockResultSet, row -> true, (RowMapper<Integer>) row -> {
                throw failure;
            }, null);
            case 2 -> JdbcUtil.iterate(mockResultSet, (BiRowMapper<Integer>) (row, labels) -> {
                throw failure;
            }, null);
            default -> JdbcUtil.iterate(mockResultSet, (row, labels) -> true, (BiRowMapper<Integer>) (row, labels) -> {
                throw failure;
            });
        };

        assertSame(failure, assertThrows(UncheckedSQLException.class, iterator::next).getCause());
        verify(mockResultSet, never()).close();
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 2 })
    void rowIteratorCountAndAdvancePreserveCursorFailure(final int variant) throws SQLException {
        final SQLException failure = new SQLException("cursor failed");
        when(mockResultSet.next()).thenThrow(failure);
        when(mockResultSet.getType()).thenReturn(ResultSet.TYPE_FORWARD_ONLY);

        assertSame(failure, assertThrows(UncheckedSQLException.class, () -> rowIterator(mockResultSet, variant).count()).getCause());
        assertSame(failure, assertThrows(UncheckedSQLException.class, () -> rowIterator(mockResultSet, variant).advance(1)).getCause());
        verify(mockResultSet, never()).close();
    }

    private ObjIteratorEx<Integer> closeableRowIterator(final int variant, final Runnable onClose) {
        return switch (variant) {
            case 0 -> JdbcUtil.iterate(mockResultSet, (RowMapper<Integer>) row -> 1, onClose);
            case 1 -> JdbcUtil.iterate(mockResultSet, row -> true, row -> 1, onClose);
            case 2 -> JdbcUtil.iterate(mockResultSet, (BiRowMapper<Integer>) (row, labels) -> 1, onClose);
            default -> throw new AssertionError(variant);
        };
    }

    private static ObjIteratorEx<Integer> rowIterator(final ResultSet rs, final int variant) {
        return switch (variant) {
            case 0 -> JdbcUtil.iterate(rs, (RowMapper<Integer>) row -> 1, null);
            case 1 -> JdbcUtil.iterate(rs, row -> true, row -> 1, null);
            case 2 -> JdbcUtil.iterate(rs, (BiRowMapper<Integer>) (row, labels) -> 1, null);
            default -> JdbcUtil.iterate(rs, (row, labels) -> true, (row, labels) -> 1);
        };
    }
}
