package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.util.N;

class JdbcColumnAccessTest extends JdbcTestSupport {

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
        verify(mockAbstractQuery).setObject(1, "obj");
    }

    // Invoke SET_BINARY_STREAM lambda body (L5811) - covers the lambda execution
    @Test
    @SuppressWarnings("unchecked")
    public void testColumnOne_SetBinaryStream_LambdaBody() throws Exception {
        InputStream is = new ByteArrayInputStream(new byte[] { 1, 2, 3 });
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

    @Test
    public void testTypedColumnBuildersValidateColumnBeforeType() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Jdbc.RowMapper.builder().getObject(0, null)).getMessage().contains("columnIndex"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Jdbc.RowExtractor.builder().getObject(0, null)).getMessage().contains("columnIndex"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Jdbc.BiRowMapper.builder().getObject(null, null)).getMessage().contains("columnName"));
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
    public void testColumnGetterGet() throws SQLException {
        when(mockResultSet.getString(1)).thenReturn("test");
        // ColumnGetter.forType(Integer.class) flows through Type<Integer>.get which uses rs.getObject for null preservation.
        when(mockResultSet.getObject(1)).thenReturn(42);

        Jdbc.ColumnGetter<String> stringGetter = Jdbc.ColumnGetter.forType(String.class);
        Jdbc.ColumnGetter<Integer> intGetter = Jdbc.ColumnGetter.forType(Integer.class);

        assertEquals("test", stringGetter.get(mockResultSet, 1));
        assertEquals(42, intGetter.get(mockResultSet, 1));
    }

    @Test
    public void testColumnGetterGet_WrapperTypes_PreserveSqlNull() throws SQLException {
        // Regression: ColumnGetter.forType(Wrapper.class) MUST preserve SQL NULL as Java null,
        // unlike the primitive-style ColumnGetter.GET_* constants which return 0 / false for null.
        // Previously the static initializer cached Boolean/Integer/Long/etc. as the primitive ResultSet::getXxx
        // getters, which silently converted SQL NULL to 0 / false. That has now been fixed.
        // Most wrapper Type.get() implementations call rs.getObject(); ShortType uses rs.getShort()+wasNull().
        when(mockResultSet.getObject(1)).thenReturn(null);
        when(mockResultSet.wasNull()).thenReturn(true);

        assertNull(Jdbc.ColumnGetter.forType(Boolean.class).get(mockResultSet, 1));
        assertNull(Jdbc.ColumnGetter.forType(Byte.class).get(mockResultSet, 1));
        assertNull(Jdbc.ColumnGetter.forType(Short.class).get(mockResultSet, 1));
        assertNull(Jdbc.ColumnGetter.forType(Integer.class).get(mockResultSet, 1));
        assertNull(Jdbc.ColumnGetter.forType(Long.class).get(mockResultSet, 1));
        assertNull(Jdbc.ColumnGetter.forType(Float.class).get(mockResultSet, 1));
        assertNull(Jdbc.ColumnGetter.forType(Double.class).get(mockResultSet, 1));

        // Sanity check: the primitive-class lookup still maps to the primitive ResultSet::getXxx getters
        // (which is the only valid behavior for primitive types).
        assertSame(Jdbc.ColumnGetter.GET_INT, Jdbc.ColumnGetter.forType(int.class));
        assertSame(Jdbc.ColumnGetter.GET_BOOLEAN, Jdbc.ColumnGetter.forType(boolean.class));
        assertSame(Jdbc.ColumnGetter.GET_LONG, Jdbc.ColumnGetter.forType(long.class));
    }

    // Bug fix: ColumnGetter.forType(Type) should throw IllegalArgumentException for null type,
    // not a cryptic NullPointerException from the pool's computeIfAbsent.
    @Tag("2025")
    @Test
    public void testColumnGetterGet_NullType_ThrowsIAE() {
        assertThrows(IllegalArgumentException.class, () -> Jdbc.ColumnGetter.forType((com.landawn.abacus.type.Type<?>) null));
    }

    // ColumnGetter Tests
    @Test
    public void testColumnGetterPredefined() throws SQLException {
        when(mockResultSet.getBoolean(1)).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn("test");
        when(mockResultSet.getInt(1)).thenReturn(42);

        assertEquals(true, Jdbc.ColumnGetter.GET_BOOLEAN.get(mockResultSet, 1));
        assertEquals("test", Jdbc.ColumnGetter.GET_STRING.get(mockResultSet, 1));
        assertEquals(42, Jdbc.ColumnGetter.GET_INT.get(mockResultSet, 1));
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

    // ColumnOne.get(Type) documents that repeated calls with the same Type return the same RowMapper instance;
    // that must also hold when the first access to a Type happens concurrently (pool insert is atomic).
    @Test
    public void testColumnOneGetByTypeReturnsSingleInstanceUnderConcurrentFirstAccess() throws Exception {
        final String[] freshTypeNames = { "List<Integer>", "Set<Long>", "Map<String, Integer>", "List<Double>", "Set<Short>", "Map<Long, String>" };
        final int threadCount = 8;
        final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);

        try {
            for (final String typeName : freshTypeNames) {
                final com.landawn.abacus.type.Type<?> type = N.typeOf(typeName);
                final java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(threadCount);
                final List<java.util.concurrent.Future<Jdbc.RowMapper<Object>>> futures = new ArrayList<>(threadCount);

                for (int i = 0; i < threadCount; i++) {
                    futures.add(executor.submit(() -> {
                        barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        return Jdbc.Columns.ColumnOne.get(type);
                    }));
                }

                final Jdbc.RowMapper<Object> first = futures.get(0).get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertNotNull(first);

                for (final java.util.concurrent.Future<Jdbc.RowMapper<Object>> future : futures) {
                    assertSame(first, future.get(10, java.util.concurrent.TimeUnit.SECONDS));
                }

                assertSame(first, Jdbc.Columns.ColumnOne.get(type));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testJdbcGetterMethodReferencesRetainTheirNullReceiverContract() {
        assertThrows(NullPointerException.class, () -> Jdbc.ColumnGetter.GET_INT.get(null, 1));
        assertThrows(IllegalArgumentException.class, () -> Jdbc.ColumnGetter.GET_OBJECT.get(null, 1));
    }

    @Test
    public void testColumnOneFactoriesRejectNullTypes() {
        assertThrows(IllegalArgumentException.class, () -> Jdbc.Columns.ColumnOne.get((Class<Object>) null));
        assertThrows(IllegalArgumentException.class, () -> Jdbc.Columns.ColumnOne.get((com.landawn.abacus.type.Type<Object>) null));
        assertThrows(IllegalArgumentException.class, () -> Jdbc.Columns.ColumnOne.set((Class<Object>) null));
        assertThrows(IllegalArgumentException.class, () -> Jdbc.Columns.ColumnOne.set((com.landawn.abacus.type.Type<Object>) null));
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
}
