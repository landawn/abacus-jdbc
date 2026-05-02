package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.query.SqlBuilder.PSC;
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

public class UncheckedCrudDaoLTest extends TestBase {

    interface TestUncheckedCrudDaoL extends UncheckedCrudDaoL<TestEntity, PSC, TestUncheckedCrudDaoL> {
    }

    static final class TestEntity {
    }

    @Test
    public void testQueryForBoolean() {
        TestUncheckedCrudDaoL dao = Mockito.mock(TestUncheckedCrudDaoL.class, Mockito.CALLS_REAL_METHODS);

        when(dao.queryForBoolean("active", Long.valueOf(3L))).thenReturn(OptionalBoolean.of(true));

        assertTrue(dao.queryForBoolean("active", 3L).getAsBoolean());
        verify(dao).queryForBoolean("active", Long.valueOf(3L));
    }

    @Test
    public void testGet_WrapsEntityInOptional() {
        TestUncheckedCrudDaoL dao = Mockito.mock(TestUncheckedCrudDaoL.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        when(dao.get(Long.valueOf(5L))).thenReturn(Optional.of(entity));

        Optional<TestEntity> result = dao.get(5L);

        assertTrue(result.isPresent());
        assertSame(entity, result.orElseNull());
    }

    @Test
    public void testUpdate_DelegatesPrimitiveId() {
        TestUncheckedCrudDaoL dao = Mockito.mock(TestUncheckedCrudDaoL.class, Mockito.CALLS_REAL_METHODS);

        when(dao.update("name", "updated", Long.valueOf(6L))).thenReturn(1);

        assertEquals(1, dao.update("name", "updated", 6L));
    }

    @Test
    public void testQueryForPrimitiveScalarDelegates() {
        TestUncheckedCrudDaoL dao = Mockito.mock(TestUncheckedCrudDaoL.class, Mockito.CALLS_REAL_METHODS);
        Date date = new Date(1L);
        Time time = new Time(2L);
        Timestamp timestamp = new Timestamp(3L);
        byte[] bytes = new byte[] { 1, 2 };

        when(dao.queryForChar("grade", Long.valueOf(1L))).thenReturn(OptionalChar.of('A'));
        when(dao.queryForByte("flags", Long.valueOf(2L))).thenReturn(OptionalByte.of((byte) 7));
        when(dao.queryForShort("port", Long.valueOf(3L))).thenReturn(OptionalShort.of((short) 8));
        when(dao.queryForInt("age", Long.valueOf(4L))).thenReturn(OptionalInt.of(9));
        when(dao.queryForLong("count", Long.valueOf(5L))).thenReturn(OptionalLong.of(10L));
        when(dao.queryForFloat("ratio", Long.valueOf(6L))).thenReturn(OptionalFloat.of(1.5f));
        when(dao.queryForDouble("amount", Long.valueOf(7L))).thenReturn(OptionalDouble.of(2.5d));
        when(dao.queryForString("name", Long.valueOf(8L))).thenReturn(Nullable.of("alice"));
        when(dao.queryForDate("birthDate", Long.valueOf(9L))).thenReturn(Nullable.of(date));
        when(dao.queryForTime("startTime", Long.valueOf(10L))).thenReturn(Nullable.of(time));
        when(dao.queryForTimestamp("lastModified", Long.valueOf(11L))).thenReturn(Nullable.of(timestamp));
        when(dao.queryForBytes("avatar", Long.valueOf(12L))).thenReturn(Nullable.of(bytes));

        assertEquals('A', dao.queryForChar("grade", 1L).get());
        assertEquals((byte) 7, dao.queryForByte("flags", 2L).get());
        assertEquals((short) 8, dao.queryForShort("port", 3L).get());
        assertEquals(9, dao.queryForInt("age", 4L).get());
        assertEquals(10L, dao.queryForLong("count", 5L).get());
        assertEquals(1.5f, dao.queryForFloat("ratio", 6L).get());
        assertEquals(2.5d, dao.queryForDouble("amount", 7L).get());
        assertEquals("alice", dao.queryForString("name", 8L).get());
        assertSame(date, dao.queryForDate("birthDate", 9L).get());
        assertSame(time, dao.queryForTime("startTime", 10L).get());
        assertSame(timestamp, dao.queryForTimestamp("lastModified", 11L).get());
        assertSame(bytes, dao.queryForBytes("avatar", 12L).get());
    }

    @Test
    public void testQueryForGenericAndIdDelegates() {
        TestUncheckedCrudDaoL dao = Mockito.mock(TestUncheckedCrudDaoL.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Jdbc.RowMapper<String> rowMapper = rs -> "mapped";

        doReturn(Nullable.of(99)).when(dao).queryForSingleValue("score", Long.valueOf(13L), Integer.class);
        doReturn(Optional.of("alice")).when(dao).queryForSingleNonNull("name", Long.valueOf(14L), String.class);
        doReturn(Optional.of("mapped")).when(dao).queryForSingleNonNull("name", Long.valueOf(15L), rowMapper);
        doReturn(Nullable.of("a@x")).when(dao).queryForUniqueValue("email", Long.valueOf(16L), String.class);
        doReturn(Optional.of("b@x")).when(dao).queryForUniqueNonNull("email", Long.valueOf(17L), String.class);
        doReturn(Optional.of("mapped-unique")).when(dao).queryForUniqueNonNull("email", Long.valueOf(18L), rowMapper);
        when(dao.get(Long.valueOf(19L), List.of("name"))).thenReturn(Optional.of(entity));
        when(dao.exists(Long.valueOf(20L))).thenReturn(true);
        when(dao.update(Map.of("status", "active"), Long.valueOf(21L))).thenReturn(2);
        when(dao.deleteById(Long.valueOf(22L))).thenReturn(1);

        assertEquals(99, dao.queryForSingleValue("score", 13L, Integer.class).get());
        assertEquals("alice", dao.queryForSingleNonNull("name", 14L, String.class).orElseNull());
        assertEquals("mapped", dao.queryForSingleNonNull("name", 15L, rowMapper).orElseNull());
        assertEquals("a@x", dao.queryForUniqueValue("email", 16L, String.class).get());
        assertEquals("b@x", dao.queryForUniqueNonNull("email", 17L, String.class).orElseNull());
        assertEquals("mapped-unique", dao.queryForUniqueNonNull("email", 18L, rowMapper).orElseNull());
        assertSame(entity, dao.get(19L, List.of("name")).orElseNull());
        assertTrue(dao.exists(20L));
        assertEquals(2, dao.update(Map.of("status", "active"), 21L));
        assertEquals(1, dao.deleteById(22L));
    }

    @Test
    public void testGettAndNotExists_DelegatesPrimitiveId() {
        TestUncheckedCrudDaoL dao = Mockito.mock(TestUncheckedCrudDaoL.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        when(dao.gett(Long.valueOf(23L))).thenReturn(entity);
        when(dao.gett(Long.valueOf(24L), List.of("name"))).thenReturn(entity);
        when(dao.exists(Long.valueOf(25L))).thenReturn(false);

        assertSame(entity, dao.gett(23L));
        assertSame(entity, dao.gett(24L, List.of("name")));
        assertTrue(dao.notExists(25L));
    }
}
