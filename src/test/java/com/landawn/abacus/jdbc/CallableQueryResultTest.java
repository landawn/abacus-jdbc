package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CallableQueryResultTest extends CallableQueryTestSupport {

    // --- queryAndGetOutParameters() - delegation (L2414) ---

    @Test
    public void testQueryAndGetOutParameters_NoArg() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<?, Jdbc.OutParamResult> result = callableQuery.queryAndGetOutParameters();
        assertNotNull(result);
        assertNotNull(result._2);
    }

    // --- queryAndGetOutParameters(ResultExtractor): rs != null path (L2460) ---

    @Test
    public void testQueryAndGetOutParameters_ResultExtractor_WithResultSet() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        // Real drivers return -1 once results are drained; without this stub the post-extraction
        // drainRemainingResultsForOutParams() loop hangs (Mockito's default int return is 0).
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<String, Jdbc.OutParamResult> result = callableQuery.queryAndGetOutParameters(resultSet -> "extracted");
        assertNotNull(result);
        assertEquals("extracted", result._1);
    }

    @Test
    public void testQueryAndGetOutParameters_BiResultExtractor_WithResultSet() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        java.sql.ResultSetMetaData meta = Mockito.mock(java.sql.ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(0);
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        // Real drivers return -1 once results are drained; without this stub the post-extraction
        // drainRemainingResultsForOutParams() loop hangs (Mockito's default int return is 0).
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<String, Jdbc.OutParamResult> result = callableQuery
                .queryAndGetOutParameters((resultSet, labels) -> "bi-extracted");
        assertNotNull(result);
        assertEquals("bi-extracted", result._1);
    }

    // Regression: queryAndGetOutParameters / listAndGetOutParameters used to read OUT params
    // immediately after extracting the FIRST result set. Per JDBC spec (and explicitly documented
    // for SQL Server and Oracle), OUT param values become reliably available only after ALL
    // result sets and update counts have been drained. Without draining, the OUT values may be
    // null/stale or the driver may throw. The fix drains via getMoreResults/getUpdateCount.
    @Test
    public void testQueryAndGetOutParameters_DrainsRemainingResultsBeforeReadingOutParams() throws SQLException {
        ResultSet firstRs = mock(ResultSet.class);
        ResultSetMetaData rsMd = mock(ResultSetMetaData.class);
        when(firstRs.getMetaData()).thenReturn(rsMd);
        when(rsMd.getColumnCount()).thenReturn(0);
        when(firstRs.next()).thenReturn(false);

        // First call returns the ResultSet; second call returns null then loop reads update counts.
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(firstRs);
        // Simulate two more result sets behind the first that the fix must drain past:
        // getMoreResults(): true (another RS) → true (another RS) → false (no more RS).
        // After the false return, getUpdateCount(): -1 means no more results.
        when(callableStatement.getMoreResults()).thenReturn(true, true, false);
        when(callableStatement.getUpdateCount()).thenReturn(-1).thenReturn(-1);

        callableQuery.registerOutParameter(1, Types.INTEGER);
        when(callableStatement.getInt(1)).thenReturn(42);

        com.landawn.abacus.util.Tuple.Tuple2<Long, Jdbc.OutParamResult> result = callableQuery.queryAndGetOutParameters(rs -> {
            long count = 0;
            while (rs.next()) {
                count++;
            }
            return count;
        });

        assertNotNull(result);
        assertEquals(0L, result._1);
        // Fix guarantees drain happened — verify getMoreResults was called multiple times.
        verify(callableStatement, org.mockito.Mockito.atLeast(2)).getMoreResults();
        // OUT param retrieval happens AFTER drain.
        assertEquals(Integer.valueOf(42), result._2.getOutParamValue(1));
    }

    @Test
    public void testQueryAndGetOutParametersRejectsLiveResultSetFromExtractor() throws SQLException {
        final ResultSet rs = mock(ResultSet.class);
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        when(callableStatement.getUpdateCount()).thenReturn(-1);

        assertThrows(UnsupportedOperationException.class, () -> callableQuery.queryAndGetOutParameters(resultSet -> resultSet));

        verify(rs).close();
        verify(callableStatement).close();
    }

    // --- queryAndGetOutParameters(ResultExtractor) - no result set path (L2459 false branch) ---

    @Test
    public void testQueryAndGetOutParameters_WithResultExtractor_NullRs() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<String, Jdbc.OutParamResult> result = callableQuery.queryAndGetOutParameters(rs -> "extracted");
        assertNotNull(result);
        org.junit.jupiter.api.Assertions.assertNull(result._1);
    }

    // --- queryAndGetOutParameters(BiResultExtractor) - no result set path ---

    @Test
    public void testQueryAndGetOutParameters_WithBiResultExtractor_NullRs() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<String, Jdbc.OutParamResult> result = callableQuery.queryAndGetOutParameters((rs, labels) -> "bi-extracted");
        assertNotNull(result);
        org.junit.jupiter.api.Assertions.assertNull(result._1);
    }

    // --- queryAllResultSetsAndGetOutParameters(ResultExtractor) with result set (L2612-2613) ---

    @Test
    public void testQueryAllResultSetsAndGetOutParameters_ResultExtractor_WithResultSet() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        when(callableStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);

        com.landawn.abacus.util.Tuple.Tuple2<List<String>, Jdbc.OutParamResult> result = callableQuery
                .queryAllResultSetsAndGetOutParameters(rs2 -> "extracted");

        assertNotNull(result);
        assertEquals(1, result._1.size());
        assertEquals("extracted", result._1.get(0));
    }

    // --- queryAllResultSetsAndGetOutParameters(BiResultExtractor) with result set (L2685-2686) ---

    @Test
    public void testQueryAllResultSetsAndGetOutParameters_BiResultExtractor_WithResultSet() throws SQLException {
        ResultSet rs = resultSetWithZeroColumns();
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        when(callableStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);

        com.landawn.abacus.util.Tuple.Tuple2<List<String>, Jdbc.OutParamResult> result = callableQuery
                .queryAllResultSetsAndGetOutParameters((rs2, labels) -> "bi-extracted");

        assertNotNull(result);
        assertEquals(1, result._1.size());
        assertEquals("bi-extracted", result._1.get(0));
    }

    // --- queryAllResultSetsAndGetOutParameters(): empty path (L2551) ---

    @Test
    public void testQueryAllResultSetsAndGetOutParameters_Empty() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<com.landawn.abacus.util.Dataset>, Jdbc.OutParamResult> result = callableQuery
                .queryAllResultSetsAndGetOutParameters();
        assertNotNull(result);
        assertEquals(0, result._1.size());
    }

    // --- queryAllResultSetsAndGetOutParameters(ResultExtractor): empty path (L2592-2615) ---

    @Test
    public void testQueryAllResultSetsAndGetOutParameters_ResultExtractor_Empty() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<String>, Jdbc.OutParamResult> result = callableQuery.queryAllResultSetsAndGetOutParameters(rs -> "extracted");
        assertNotNull(result);
        assertEquals(0, result._1.size());
    }

    // --- queryAllResultSetsAndGetOutParameters(BiResultExtractor): empty path (L2665-2688) ---

    @Test
    public void testQueryAllResultSetsAndGetOutParameters_BiResultExtractor_Empty() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<String>, Jdbc.OutParamResult> result = callableQuery
                .queryAllResultSetsAndGetOutParameters((rs, labels) -> "bi-extracted");
        assertNotNull(result);
        assertEquals(0, result._1.size());
    }

    // --- query2ResultSetsAndGetOutParameters with one result set (L2763-2764) ---

    @Test
    public void testQuery2ResultSetsAndGetOutParameters_WithOneResultSet() throws SQLException {
        ResultSet rs = resultSetWithZeroColumns();
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        when(callableStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);

        com.landawn.abacus.util.Tuple.Tuple3<String, String, Jdbc.OutParamResult> result = callableQuery
                .query2ResultSetsAndGetOutParameters((rs2, labels) -> "r1", (rs2, labels) -> "r2");

        assertNotNull(result);
        assertEquals("r1", result._1);
        assertEquals(null, result._2);
    }

    // --- query2ResultSetsAndGetOutParameters with two result sets (L2767-2768) ---

    @Test
    public void testQuery2ResultSetsAndGetOutParameters_WithTwoResultSets() throws SQLException {
        ResultSet rs1 = resultSetWithZeroColumns();
        ResultSet rs2 = resultSetWithZeroColumns();
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs1, rs2);
        when(callableStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(true, false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);

        com.landawn.abacus.util.Tuple.Tuple3<String, String, Jdbc.OutParamResult> result = callableQuery
                .query2ResultSetsAndGetOutParameters((rs, labels) -> "r1", (rs, labels) -> "r2");

        assertNotNull(result);
        assertEquals("r1", result._1);
        assertEquals("r2", result._2);
    }

    // Regression: query2ResultSetsAndGetOutParameters consumed only the first two ResultSets via
    // iterateAllResultSets; any trailing RSs/update counts remained buffered when getOutParameters
    // ran, causing stale OUT params on SQL Server / Oracle. Fix adds a drain before getOutParameters.
    @Test
    public void testQuery2ResultSetsAndGetOutParameters_DrainsTrailingResults() throws SQLException {
        ResultSet rs1 = mock(ResultSet.class);
        ResultSet rs2 = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(md.getColumnCount()).thenReturn(0);
        when(rs1.getMetaData()).thenReturn(md);
        when(rs2.getMetaData()).thenReturn(md);

        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs1, rs2);
        // KEEP_CURRENT_RESULT overload (called by iterator after each next()): another RS after rs1, then doesn't matter.
        when(callableStatement.getMoreResults(java.sql.Statement.KEEP_CURRENT_RESULT)).thenReturn(true, true);
        // No-arg overload (called by drain): no more results.
        when(callableStatement.getMoreResults()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);

        callableQuery.registerOutParameter(1, Types.INTEGER);
        when(callableStatement.getInt(1)).thenReturn(55);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(callableStatement);
        com.landawn.abacus.util.Tuple.Tuple3<String, String, Jdbc.OutParamResult> result = callableQuery.query2ResultSetsAndGetOutParameters(
                (Jdbc.BiResultExtractor<String>) (rs, labels) -> "r1", (Jdbc.BiResultExtractor<String>) (rs, labels) -> "r2");

        assertNotNull(result);
        assertEquals("r1", result._1);
        assertEquals("r2", result._2);
        assertEquals(Integer.valueOf(55), result._3.getOutParamValue(1));
        // Pre-fix, the no-arg getMoreResults() was never called; iterateAllResultSets only uses the
        // (int) overload. Post-fix, drain calls the no-arg overload at least once before getInt(1).
        inOrder.verify(callableStatement, org.mockito.Mockito.atLeast(1)).getMoreResults();
        inOrder.verify(callableStatement).getInt(1);
    }

    // --- query2ResultSetsAndGetOutParameters: empty path (L2741-2770) ---

    @Test
    public void testQuery2ResultSetsAndGetOutParameters_Empty() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple3<String, String, Jdbc.OutParamResult> result = callableQuery
                .query2ResultSetsAndGetOutParameters((rs, labels) -> "r1", (rs, labels) -> "r2");
        assertNotNull(result);
    }

    // A driver rejecting getMoreResults(KEEP_CURRENT_RESULT) (e.g. SQL Server): each result set must be extracted before
    // the deferred getMoreResults() closes it, and the trailing result must still be drained before OUT params are read.
    @Test
    public void testQuery2ResultSetsAndGetOutParameters_KeepCurrentResultRejected_ExtractsThenDrains() throws SQLException {
        final ResultSet rs1 = mock(ResultSet.class);
        final ResultSet rs2 = mock(ResultSet.class);
        final ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(md.getColumnCount()).thenReturn(0);
        when(rs1.getMetaData()).thenReturn(md);
        when(rs2.getMetaData()).thenReturn(md);

        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs1, rs2);
        when(callableStatement.getMoreResults(java.sql.Statement.KEEP_CURRENT_RESULT))
                .thenThrow(new java.sql.SQLFeatureNotSupportedException("KEEP_CURRENT_RESULT"));
        // rs1 -> rs2 (deferred advance), rs2 -> trailing rs3 (drain), then no more results.
        when(callableStatement.getMoreResults()).thenReturn(true, true, false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);

        callableQuery.registerOutParameter(1, Types.INTEGER);
        when(callableStatement.getInt(1)).thenReturn(55);

        final java.util.function.Supplier<Long> advanceCount = () -> Mockito.mockingDetails(callableStatement)
                .getInvocations()
                .stream()
                .filter(it -> it.getMethod().getName().equals("getMoreResults") && it.getArguments().length == 0)
                .count();

        final com.landawn.abacus.util.Tuple.Tuple3<Long, Long, Jdbc.OutParamResult> result = callableQuery.query2ResultSetsAndGetOutParameters(
                (Jdbc.BiResultExtractor<Long>) (rs, labels) -> advanceCount.get(), (Jdbc.BiResultExtractor<Long>) (rs, labels) -> advanceCount.get());

        assertEquals(0L, result._1, "rs1 must be extracted before the statement advances past it");
        assertEquals(1L, result._2, "rs2 must be extracted before the statement advances past it");
        assertEquals(Integer.valueOf(55), result._3.getOutParamValue(1));
        assertEquals(3L, advanceCount.get());

        final org.mockito.InOrder inOrder = Mockito.inOrder(callableStatement);
        inOrder.verify(callableStatement, Mockito.times(3)).getMoreResults();
        inOrder.verify(callableStatement).getInt(1);
    }

    // --- query3ResultSetsAndGetOutParameters with one result set (L2852-2853) ---

    @Test
    public void testQuery3ResultSetsAndGetOutParameters_WithOneResultSet() throws SQLException {
        ResultSet rs = resultSetWithZeroColumns();
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        when(callableStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);

        com.landawn.abacus.util.Tuple.Tuple4<String, String, String, Jdbc.OutParamResult> result = callableQuery
                .query3ResultSetsAndGetOutParameters((rs2, labels) -> "r1", (rs2, labels) -> "r2", (rs2, labels) -> "r3");

        assertNotNull(result);
        assertEquals("r1", result._1);
        assertEquals(null, result._2);
        assertEquals(null, result._3);
    }

    // --- query3ResultSetsAndGetOutParameters with three result sets (L2856-2857, L2860-2861) ---

    @Test
    public void testQuery3ResultSetsAndGetOutParameters_WithThreeResultSets() throws SQLException {
        ResultSet rs1 = resultSetWithZeroColumns();
        ResultSet rs2 = resultSetWithZeroColumns();
        ResultSet rs3 = resultSetWithZeroColumns();
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs1, rs2, rs3);
        when(callableStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(true, true, false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);

        com.landawn.abacus.util.Tuple.Tuple4<String, String, String, Jdbc.OutParamResult> result = callableQuery
                .query3ResultSetsAndGetOutParameters((rs, labels) -> "r1", (rs, labels) -> "r2", (rs, labels) -> "r3");

        assertNotNull(result);
        assertEquals("r1", result._1);
        assertEquals("r2", result._2);
        assertEquals("r3", result._3);
    }

    // Same regression as the 2-RS variant, for the 3-RS variant.
    @Test
    public void testQuery3ResultSetsAndGetOutParameters_DrainsTrailingResults() throws SQLException {
        ResultSet rs1 = mock(ResultSet.class);
        ResultSet rs2 = mock(ResultSet.class);
        ResultSet rs3 = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(md.getColumnCount()).thenReturn(0);
        when(rs1.getMetaData()).thenReturn(md);
        when(rs2.getMetaData()).thenReturn(md);
        when(rs3.getMetaData()).thenReturn(md);

        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs1, rs2, rs3);
        when(callableStatement.getMoreResults(java.sql.Statement.KEEP_CURRENT_RESULT)).thenReturn(true, true, true);
        when(callableStatement.getMoreResults()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);

        callableQuery.registerOutParameter(1, Types.INTEGER);
        when(callableStatement.getInt(1)).thenReturn(77);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(callableStatement);
        com.landawn.abacus.util.Tuple.Tuple4<String, String, String, Jdbc.OutParamResult> result = callableQuery.query3ResultSetsAndGetOutParameters(
                (Jdbc.BiResultExtractor<String>) (rs, labels) -> "a", (Jdbc.BiResultExtractor<String>) (rs, labels) -> "b",
                (Jdbc.BiResultExtractor<String>) (rs, labels) -> "c");

        assertNotNull(result);
        assertEquals("a", result._1);
        assertEquals("b", result._2);
        assertEquals("c", result._3);
        assertEquals(Integer.valueOf(77), result._4.getOutParamValue(1));
        inOrder.verify(callableStatement, org.mockito.Mockito.atLeast(1)).getMoreResults();
        inOrder.verify(callableStatement).getInt(1);
    }

    // --- query3ResultSetsAndGetOutParameters: empty path (L2828-2863) ---

    @Test
    public void testQuery3ResultSetsAndGetOutParameters_Empty() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple4<String, String, String, Jdbc.OutParamResult> result = callableQuery
                .query3ResultSetsAndGetOutParameters((rs, labels) -> "r1", (rs, labels) -> "r2", (rs, labels) -> "r3");
        assertNotNull(result);
    }

    // --- queryAndGetOutParameters(BiResultExtractor): rs != null path (L2512) ---

    // --- listAndGetOutParameters(RowMapper): rs != null with row (L2976) ---

    @Test
    public void testListAndGetOutParameters_RowMapper_WithRow() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("Alice");
        // Real drivers return -1 once results are drained; without this stub the post-extraction
        // drainRemainingResultsForOutParams() loop hangs (Mockito's default int return is 0).
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<String>, Jdbc.OutParamResult> result = callableQuery.listAndGetOutParameters(r -> r.getString(1));
        assertNotNull(result);
        assertEquals(1, result._1.size());
        assertEquals("Alice", result._1.get(0));
    }

    // --- listAndGetOutParameters(RowFilter, RowMapper): rs != null with filtered row (L3030-3049) ---

    @Test
    public void testListAndGetOutParameters_RowFilter_RowMapper_WithRow() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("match");
        // Real drivers return -1 once results are drained; without this stub the post-extraction
        // drainRemainingResultsForOutParams() loop hangs (Mockito's default int return is 0).
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<String>, Jdbc.OutParamResult> result = callableQuery.listAndGetOutParameters(r -> true, r -> r.getString(1));
        assertNotNull(result);
        assertEquals(1, result._1.size());
        assertEquals("match", result._1.get(0));
    }

    // --- listAndGetOutParameters(BiRowMapper): rs != null with row (L3105-3108) ---

    @Test
    public void testListAndGetOutParameters_BiRowMapper_WithRow() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        java.sql.ResultSetMetaData meta = Mockito.mock(java.sql.ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(0);
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        // Real drivers return -1 once results are drained; without this stub the post-extraction
        // drainRemainingResultsForOutParams() loop hangs (Mockito's default int return is 0).
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<String>, Jdbc.OutParamResult> result = callableQuery.listAndGetOutParameters((r, labels) -> "row");
        assertNotNull(result);
        assertEquals(1, result._1.size());
        assertEquals("row", result._1.get(0));
    }

    // --- listAndGetOutParameters(BiRowFilter, BiRowMapper): rs != null with row (L3175-3196) ---

    @Test
    public void testListAndGetOutParameters_BiRowFilter_BiRowMapper_WithRow() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        java.sql.ResultSetMetaData meta = Mockito.mock(java.sql.ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(0);
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        // Real drivers return -1 once results are drained; without this stub the post-extraction
        // drainRemainingResultsForOutParams() loop hangs (Mockito's default int return is 0).
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<String>, Jdbc.OutParamResult> result = callableQuery.listAndGetOutParameters((r, labels) -> true,
                (r, labels) -> "birow");
        assertNotNull(result);
        assertEquals(1, result._1.size());
        assertEquals("birow", result._1.get(0));
    }

    @Test
    public void testListAndGetOutParameters_DrainsRemainingResultsBeforeReadingOutParams() throws SQLException {
        ResultSet firstRs = mock(ResultSet.class);
        ResultSetMetaData rsMd = mock(ResultSetMetaData.class);
        when(firstRs.getMetaData()).thenReturn(rsMd);
        when(rsMd.getColumnCount()).thenReturn(0);
        when(firstRs.next()).thenReturn(false);

        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(firstRs);
        when(callableStatement.getMoreResults()).thenReturn(true, false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);

        callableQuery.registerOutParameter(1, Types.INTEGER);
        when(callableStatement.getInt(1)).thenReturn(99);

        com.landawn.abacus.util.Tuple.Tuple2<List<String>, Jdbc.OutParamResult> result = callableQuery
                .listAndGetOutParameters((Jdbc.RowMapper<String>) rs -> "row");

        assertNotNull(result);
        assertEquals(0, result._1.size());
        // Fix guarantees drain happened — verify getMoreResults was called at least once after extraction.
        verify(callableStatement, org.mockito.Mockito.atLeast(1)).getMoreResults();
        assertEquals(Integer.valueOf(99), result._2.getOutParamValue(1));
    }

    // --- listAndGetOutParameters(Class) - delegation (L2914-2916) ---

    @Test
    public void testListAndGetOutParameters_ByClass_EmptyResultSet() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<String>, Jdbc.OutParamResult> result = callableQuery.listAndGetOutParameters(String.class);
        assertNotNull(result);
        assertEquals(0, result._1.size());
    }

    // --- listAndGetOutParameters(RowMapper) - empty result set path (L2974 false branch) ---

    @Test
    public void testListAndGetOutParameters_WithRowMapper_NullRs() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<String>, Jdbc.OutParamResult> result = callableQuery.listAndGetOutParameters(rs -> rs.getString(1));
        assertNotNull(result);
        assertEquals(0, result._1.size());
    }

    // listAndGetOutParameters(RowFilter, RowMapper) - empty result set (L3046 pc false branch)
    @Test
    public void testListAndGetOutParameters_RowFilter_RowMapper_NullRs() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<String>, Jdbc.OutParamResult> result = callableQuery.listAndGetOutParameters(r -> true, r -> r.getString(1));
        assertNotNull(result);
        assertEquals(0, result._1.size());
    }

    // listAndGetOutParameters(RowFilter, RowMapper) - filter returns false (L3048 pc false branch)
    @Test
    public void testListAndGetOutParameters_RowFilter_RowMapper_FilterRejects() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("shouldBeFiltered");
        // Real drivers return -1 once results are drained; without this stub the post-extraction
        // drainRemainingResultsForOutParams() loop hangs (Mockito's default int return is 0).
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<String>, Jdbc.OutParamResult> result = callableQuery.listAndGetOutParameters(r -> false, r -> r.getString(1));
        assertNotNull(result);
        assertEquals(0, result._1.size());
    }

    // listAndGetOutParameters(BiRowFilter, BiRowMapper) - empty result set (L3191 pc false branch)
    @Test
    public void testListAndGetOutParameters_BiRowFilter_BiRowMapper_NullRs() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<String>, Jdbc.OutParamResult> result = callableQuery.listAndGetOutParameters((r, labels) -> true,
                (r, labels) -> "row");
        assertNotNull(result);
        assertEquals(0, result._1.size());
    }

    // listAndGetOutParameters(BiRowFilter, BiRowMapper) - filter returns false (L3195 pc false branch)
    @Test
    public void testListAndGetOutParameters_BiRowFilter_BiRowMapper_FilterRejects() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        java.sql.ResultSetMetaData meta = Mockito.mock(java.sql.ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(0);
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        // Real drivers return -1 once results are drained; without this stub the post-extraction
        // drainRemainingResultsForOutParams() loop hangs (Mockito's default int return is 0).
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<String>, Jdbc.OutParamResult> result = callableQuery.listAndGetOutParameters((r, labels) -> false,
                (r, labels) -> "filtered");
        assertNotNull(result);
        assertEquals(0, result._1.size());
    }

    // --- listAllResultSetsAndGetOutParameters(Class) with result set (L3263-3264) ---

    @Test
    public void testListAllResultSetsAndGetOutParameters_ByClass_WithResultSet() throws SQLException {
        ResultSet rs = resultSetWithZeroColumns();
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        when(callableStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);

        com.landawn.abacus.util.Tuple.Tuple2<List<List<Object>>, Jdbc.OutParamResult> result = callableQuery.listAllResultSetsAndGetOutParameters(Object.class);

        assertNotNull(result);
        assertEquals(1, result._1.size());
        assertEquals(0, result._1.get(0).size());
    }

    // --- listAllResultSetsAndGetOutParameters(RowMapper) with result set (L3338-3339) ---

    @Test
    public void testListAllResultSetsAndGetOutParameters_RowMapper_WithResultSet() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        when(callableStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);

        com.landawn.abacus.util.Tuple.Tuple2<List<List<String>>, Jdbc.OutParamResult> result = callableQuery
                .listAllResultSetsAndGetOutParameters(rs2 -> rs2.getString(1));

        assertNotNull(result);
        assertEquals(1, result._1.size());
        assertEquals(0, result._1.get(0).size());
    }

    // --- listAllResultSetsAndGetOutParameters(RowFilter, RowMapper) with result set (L3416-3417) ---

    @Test
    public void testListAllResultSetsAndGetOutParameters_RowFilter_RowMapper_WithResultSet() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        when(callableStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);

        com.landawn.abacus.util.Tuple.Tuple2<List<List<String>>, Jdbc.OutParamResult> result = callableQuery.listAllResultSetsAndGetOutParameters(r -> true,
                rs2 -> rs2.getString(1));

        assertNotNull(result);
        assertEquals(1, result._1.size());
        assertEquals(0, result._1.get(0).size());
    }

    // --- listAllResultSetsAndGetOutParameters(BiRowMapper) with result set (L3498-3499) ---

    @Test
    public void testListAllResultSetsAndGetOutParameters_BiRowMapper_WithResultSet() throws SQLException {
        ResultSet rs = resultSetWithZeroColumns();
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        when(callableStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);

        com.landawn.abacus.util.Tuple.Tuple2<List<List<String>>, Jdbc.OutParamResult> result = callableQuery
                .listAllResultSetsAndGetOutParameters((rs2, labels) -> "row");

        assertNotNull(result);
        assertEquals(1, result._1.size());
        assertEquals(0, result._1.get(0).size());
    }

    // --- listAllResultSetsAndGetOutParameters(BiRowFilter, BiRowMapper) with result set (L3596-3597) ---

    @Test
    public void testListAllResultSetsAndGetOutParameters_BiRowFilter_BiRowMapper_WithResultSet() throws SQLException {
        ResultSet rs = resultSetWithZeroColumns();
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        when(callableStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);

        com.landawn.abacus.util.Tuple.Tuple2<List<List<String>>, Jdbc.OutParamResult> result = callableQuery
                .listAllResultSetsAndGetOutParameters((r, labels) -> true, (rs2, labels) -> "row");

        assertNotNull(result);
        assertEquals(1, result._1.size());
        assertEquals(0, result._1.get(0).size());
    }

    // --- listAllResultSetsAndGetOutParameters(Class): empty path (L3243-3266) ---

    @Test
    public void testListAllResultSetsAndGetOutParameters_ByClass_Empty() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<List<String>>, Jdbc.OutParamResult> result = callableQuery.listAllResultSetsAndGetOutParameters(String.class);
        assertNotNull(result);
        assertEquals(0, result._1.size());
    }

    // --- listAllResultSetsAndGetOutParameters(RowMapper): empty path (L3318-3341) ---

    @Test
    public void testListAllResultSetsAndGetOutParameters_RowMapper_Empty() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<List<String>>, Jdbc.OutParamResult> result = callableQuery
                .listAllResultSetsAndGetOutParameters(rs -> rs.getString(1));
        assertNotNull(result);
        assertEquals(0, result._1.size());
    }

    // --- listAllResultSetsAndGetOutParameters(RowFilter, RowMapper): empty path (L3395-3419) ---

    @Test
    public void testListAllResultSetsAndGetOutParameters_RowFilter_RowMapper_Empty() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<List<String>>, Jdbc.OutParamResult> result = callableQuery.listAllResultSetsAndGetOutParameters(r -> true,
                rs -> rs.getString(1));
        assertNotNull(result);
        assertEquals(0, result._1.size());
    }

    // --- listAllResultSetsAndGetOutParameters(BiRowMapper): empty path (L3478-3501) ---

    @Test
    public void testListAllResultSetsAndGetOutParameters_BiRowMapper_Empty() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<List<String>>, Jdbc.OutParamResult> result = callableQuery
                .listAllResultSetsAndGetOutParameters((rs, labels) -> rs.getString(1));
        assertNotNull(result);
        assertEquals(0, result._1.size());
    }

    // --- listAllResultSetsAndGetOutParameters(BiRowFilter, BiRowMapper): empty path (L3575-3599) ---

    @Test
    public void testListAllResultSetsAndGetOutParameters_BiRowFilter_BiRowMapper_Empty() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<List<String>>, Jdbc.OutParamResult> result = callableQuery
                .listAllResultSetsAndGetOutParameters((r, labels) -> true, (rs, labels) -> rs.getString(1));
        assertNotNull(result);
        assertEquals(0, result._1.size());
    }

    @Test
    public void testQueryAllResultSetsDrainsAfterExtractorFailureWhenStatementIsReusable() throws SQLException {
        final ResultSet rs = mock(ResultSet.class);
        final SQLException extractorFailure = new SQLException("extractor failed");
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        when(callableStatement.getMoreResults()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.closeAfterExecution(false);

        final SQLException thrown = assertThrows(SQLException.class, () -> callableQuery.queryAllResultSetsAndGetOutParameters(resultSet -> {
            throw extractorFailure;
        }));

        assertSame(extractorFailure, thrown);
        verify(callableStatement).getMoreResults();
        verify(callableStatement, never()).close();
    }

    @Test
    public void testQueryAllResultSetsPreservesCheckedSqlExceptionContract() throws SQLException {
        final SQLException failure = new SQLException("advance failed");
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenThrow(failure);

        final SQLException thrown = assertThrows(SQLException.class, () -> callableQuery.queryAllResultSetsAndGetOutParameters());

        assertSame(failure, thrown);
    }

    @Test
    public void testQueryForInt_ReturnsEmptyWhenProcedureHasNoResultSet() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);

        assertFalse(callableQuery.queryForInt().isPresent());
    }

    // resultSetForAllMethods helper
    private ResultSet resultSetWithZeroColumns() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        java.sql.ResultSetMetaData meta = Mockito.mock(java.sql.ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(0);
        when(rs.next()).thenReturn(false);
        return rs;
    }
}
