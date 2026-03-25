package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.exception.DuplicateResultException;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.type.TypeFactory;

public class AbstractQueryTest extends TestBase {

    static final class TestQuery extends AbstractQuery<PreparedStatement, TestQuery> {
        TestQuery(final PreparedStatement stmt) {
            super(stmt);
        }
    }

    private PreparedStatement preparedStatement;
    private TestQuery query;

    @BeforeEach
    public void setUp() throws SQLException {
        preparedStatement = Mockito.mock(PreparedStatement.class);
        Connection connection = Mockito.mock(Connection.class);

        Mockito.when(preparedStatement.getConnection()).thenReturn(connection);
        query = new TestQuery(preparedStatement);
    }

    @Test
    public void testSetParameters_IntArray() throws SQLException {
        TestQuery result = query.setParameters(new int[] { 1, 2, 3 });

        assertSame(query, result);
        verify(preparedStatement).setInt(1, 1);
        verify(preparedStatement).setInt(2, 2);
        verify(preparedStatement).setInt(3, 3);
    }

    @Test
    public void testSetParametersFrom_Collection() throws SQLException {
        TestQuery result = query.setParametersFrom(2, List.of("a", "b"));

        assertSame(query, result);
        verify(preparedStatement).setString(2, "a");
        verify(preparedStatement).setString(3, "b");
    }

    @Test
    public void testSetNullForIndices() throws SQLException {
        TestQuery result = query.setNullForIndices(Types.INTEGER, 1, 3);

        assertSame(query, result);
        verify(preparedStatement).setNull(1, Types.INTEGER);
        verify(preparedStatement).setNull(3, Types.INTEGER);
    }

    @Test
    public void testSetFetchSize() throws SQLException {
        TestQuery result = query.setFetchSize(-1);

        assertSame(query, result);
        verify(preparedStatement).setFetchSize(-1);
    }

    @Test
    public void testCloseAfterExecution_ClosedQuery() throws SQLException {
        query.close();

        assertThrows(IllegalStateException.class, () -> query.closeAfterExecution(false));
    }

    // Tests for setBoolean with defaultValueForNull
    @Test
    public void testSetBoolean_WithDefault_Null() throws SQLException {
        TestQuery result = query.setBoolean(1, (Boolean) null, true);
        assertSame(query, result);
        verify(preparedStatement).setBoolean(1, true);
    }

    @Test
    public void testSetBoolean_WithDefault_NonNull() throws SQLException {
        TestQuery result = query.setBoolean(1, Boolean.FALSE, true);
        assertSame(query, result);
        verify(preparedStatement).setBoolean(1, false);
    }

    // Tests for setByte with defaultValueForNull
    @Test
    public void testSetByte_Wrapper_Null() throws SQLException {
        TestQuery result = query.setByte(1, (Byte) null);
        assertSame(query, result);
        verify(preparedStatement).setNull(1, Types.TINYINT);
    }

    @Test
    public void testSetByte_Wrapper_NonNull() throws SQLException {
        TestQuery result = query.setByte(1, (Byte) (byte) 5);
        assertSame(query, result);
        verify(preparedStatement).setByte(1, (byte) 5);
    }

    // Tests for setShort with null
    @Test
    public void testSetShort_Wrapper_Null() throws SQLException {
        TestQuery result = query.setShort(1, (Short) null);
        assertSame(query, result);
        verify(preparedStatement).setNull(1, Types.SMALLINT);
    }

    @Test
    public void testSetShort_Wrapper_NonNull() throws SQLException {
        TestQuery result = query.setShort(1, (Short) (short) 100);
        assertSame(query, result);
        verify(preparedStatement).setShort(1, (short) 100);
    }

    @Test
    public void testSetShort_WithDefault_Null() throws SQLException {
        TestQuery result = query.setShort(1, (Short) null, (short) 0);
        assertSame(query, result);
        verify(preparedStatement).setShort(1, (short) 0);
    }

    @Test
    public void testSetShort_WithDefault_NonNull() throws SQLException {
        TestQuery result = query.setShort(1, (Short) (short) 7, (short) 0);
        assertSame(query, result);
        verify(preparedStatement).setShort(1, (short) 7);
    }

    // Tests for setInt with null wrapper
    @Test
    public void testSetInt_Wrapper_Null() throws SQLException {
        TestQuery result = query.setInt(1, (Integer) null);
        assertSame(query, result);
        verify(preparedStatement).setNull(1, Types.INTEGER);
    }

    @Test
    public void testSetInt_Wrapper_NonNull() throws SQLException {
        TestQuery result = query.setInt(1, (Integer) 42);
        assertSame(query, result);
        verify(preparedStatement).setInt(1, 42);
    }

    @Test
    public void testSetInt_WithDefault_NonNull() throws SQLException {
        TestQuery result = query.setInt(1, Integer.valueOf(7), 0);
        assertSame(query, result);
        verify(preparedStatement).setInt(1, 7);
    }

    @Test
    public void testSetInt_CharacterWrapper_NonNull() throws SQLException {
        TestQuery result = query.setInt(1, Character.valueOf('A'));
        assertSame(query, result);
        verify(preparedStatement).setInt(1, 'A');
    }

    // Tests for setLong with null wrapper
    @Test
    public void testSetLong_Wrapper_Null() throws SQLException {
        TestQuery result = query.setLong(1, (Long) null);
        assertSame(query, result);
        verify(preparedStatement).setNull(1, Types.BIGINT);
    }

    @Test
    public void testSetLong_Wrapper_NonNull() throws SQLException {
        TestQuery result = query.setLong(1, (Long) 1000L);
        assertSame(query, result);
        verify(preparedStatement).setLong(1, 1000L);
    }

    @Test
    public void testSetLong_WithDefault_NonNull() throws SQLException {
        TestQuery result = query.setLong(1, Long.valueOf(99L), -1L);
        assertSame(query, result);
        verify(preparedStatement).setLong(1, 99L);
    }

    // Tests for setFloat with null wrapper
    @Test
    public void testSetFloat_Wrapper_Null() throws SQLException {
        TestQuery result = query.setFloat(1, (Float) null);
        assertSame(query, result);
        verify(preparedStatement).setNull(1, Types.FLOAT);
    }

    @Test
    public void testSetFloat_Wrapper_NonNull() throws SQLException {
        TestQuery result = query.setFloat(1, (Float) 1.5f);
        assertSame(query, result);
        verify(preparedStatement).setFloat(1, 1.5f);
    }

    // Tests for setDouble with null wrapper
    @Test
    public void testSetDouble_Wrapper_Null() throws SQLException {
        TestQuery result = query.setDouble(1, (Double) null);
        assertSame(query, result);
        verify(preparedStatement).setNull(1, Types.DOUBLE);
    }

    @Test
    public void testSetDouble_Wrapper_NonNull() throws SQLException {
        TestQuery result = query.setDouble(1, (Double) 3.14);
        assertSame(query, result);
        verify(preparedStatement).setDouble(1, 3.14);
    }

    @Test
    public void testAddBatchParameters_EmptyCollection() throws SQLException {
        TestQuery result = query.addBatchParameters(List.of());

        assertSame(query, result);
        verify(preparedStatement, never()).addBatch();
    }

    @Test
    public void testAddBatchParameters_IteratorOfScalarValues() throws SQLException {
        TestQuery result = query.addBatchParameters(List.of(1, 2).iterator());

        assertSame(query, result);
        verify(preparedStatement).setInt(1, 1);
        verify(preparedStatement).setInt(1, 2);
        verify(preparedStatement, times(2)).addBatch();
    }

    // Custom batch actions bypass the default PreparedStatement.addBatch call.
    @Test
    public void testConfigAddBatchAction() throws SQLException {
        query.configAddBatchAction((it, stmt) -> stmt.setInt(9, 99));

        TestQuery result = query.addBatch();

        assertSame(query, result);
        verify(preparedStatement).setInt(9, 99);
        verify(preparedStatement, never()).addBatch();
    }

    @Test
    public void testGetFetchSettings() throws SQLException {
        when(preparedStatement.getFetchSize()).thenReturn(128);
        when(preparedStatement.getFetchDirection()).thenReturn(ResultSet.FETCH_REVERSE);

        assertEquals(128, query.getFetchSize());
        assertEquals(ResultSet.FETCH_REVERSE, query.getFetchDirection());
    }

    // setFloat(int, Float, float) - non-null branch (line 867) was not covered
    @Test
    public void testSetFloat_WithDefault_NonNull() throws SQLException {
        TestQuery result = query.setFloat(1, Float.valueOf(2.5f), 0.0f);
        assertSame(query, result);
        verify(preparedStatement).setFloat(1, 2.5f);
    }

    // setDouble(int, Double, double) - non-null branch (line 965) was not covered
    @Test
    public void testSetDouble_WithDefault_NonNull() throws SQLException {
        TestQuery result = query.setDouble(1, Double.valueOf(1.23), 0.0);
        assertSame(query, result);
        verify(preparedStatement).setDouble(1, 1.23);
    }

    // addBatchParameters(Collection<T>, Class<T>) with empty collection should return early
    @Test
    public void testAddBatchParameters_EmptyCollectionWithType() throws SQLException {
        TestQuery result = query.addBatchParameters(List.of(), String.class);
        assertSame(query, result);
        verify(preparedStatement, never()).addBatch();
    }

    // addBatchParameters(Iterator) with empty iterator should return early (line 3679-3680)
    @Test
    public void testAddBatchParameters_EmptyIterator() throws SQLException {
        TestQuery result = query.addBatchParameters(List.of().iterator());
        assertSame(query, result);
        verify(preparedStatement, never()).addBatch();
    }

    // addBatchParameters(Iterator, BiParametersSetter) - lines 3867-3891
    @Test
    public void testAddBatchParameters_IteratorWithBiParametersSetter() throws SQLException {
        final List<String> items = List.of("a", "b");
        TestQuery result = query.addBatchParameters(items.iterator(), (Jdbc.BiParametersSetter<TestQuery, String>) (q, s) -> preparedStatement.setString(1, s));
        assertSame(query, result);
        verify(preparedStatement).setString(1, "a");
        verify(preparedStatement).setString(1, "b");
        verify(preparedStatement, times(2)).addBatch();
    }

    // addBatchParameters(Collection, BiParametersSetter) - lines 3838-3843
    @Test
    public void testAddBatchParameters_CollectionWithBiParametersSetter() throws SQLException {
        final List<Integer> items = List.of(10, 20);
        TestQuery result = query.addBatchParameters(items, (Jdbc.BiParametersSetter<TestQuery, Integer>) (q, v) -> preparedStatement.setInt(1, v));
        assertSame(query, result);
        verify(preparedStatement).setInt(1, 10);
        verify(preparedStatement).setInt(1, 20);
        verify(preparedStatement, times(2)).addBatch();
    }

    @Test
    public void testSetObject_WithType_DelegatesToType() throws SQLException {
        final Type<String> strType = TypeFactory.getType(String.class);

        TestQuery result = query.setObject(1, "hello", strType);

        assertSame(query, result);
        verify(preparedStatement).setString(1, "hello");
    }

    @Test
    public void testFindOnlyOneOrNull_Class_NoResult_ReturnsNull() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        assertNull(query.findOnlyOneOrNull(String.class));
    }

    @Test
    public void testFindOnlyOneOrNull_Class_DuplicateResultThrows() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final java.sql.ResultSetMetaData meta = Mockito.mock(java.sql.ResultSetMetaData.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("val");
        when(rs.getString(1)).thenReturn("first");

        assertThrows(DuplicateResultException.class, () -> query.findOnlyOneOrNull(String.class));
    }

    @Test
    public void testFindOnlyOneOrNull_RowMapper_NoResult_ReturnsNull() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        assertNull(query.findOnlyOneOrNull(r -> r.getString(1)));
    }

    @Test
    public void testFindOnlyOneOrNull_RowMapper_DuplicateResultThrows() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true);
        when(rs.getString(1)).thenReturn("value");

        assertThrows(DuplicateResultException.class, () -> query.findOnlyOneOrNull(r -> r.getString(1)));
    }

    @Test
    public void testCheckArgNotNull_NullArg_ThrowsIllegalArgument() {
        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                () -> query.checkArgNotNull(null, "myParam"));
        assertTrue(iae.getMessage().contains("myParam"));
    }

    @Test
    public void testCheckArgNotNull_CloseHandlerThrows_SuppressedException() {
        final RuntimeException closeEx = new RuntimeException("close handler failed");
        query.onClose(() -> { throw closeEx; });

        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                () -> query.checkArgNotNull(null, "someArg"));

        assertTrue(iae.getMessage().contains("someArg"));
        assertEquals(1, iae.getSuppressed().length);
        assertSame(closeEx, iae.getSuppressed()[0]);
    }

    @Test
    public void testCheckArg_FalseCondition_ThrowsIllegalArgument() {
        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                () -> query.checkArg(false, "condition must be true"));
        assertEquals("condition must be true", iae.getMessage());
    }

    @Test
    public void testCheckArg_CloseHandlerThrows_SuppressedException() {
        final RuntimeException closeEx = new RuntimeException("close handler failed");
        query.onClose(() -> { throw closeEx; });

        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                () -> query.checkArg(false, "bad condition"));

        assertEquals("bad condition", iae.getMessage());
        assertEquals(1, iae.getSuppressed().length);
        assertSame(closeEx, iae.getSuppressed()[0]);
    }
}
