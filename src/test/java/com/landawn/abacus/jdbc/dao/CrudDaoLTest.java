package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.SqlBuilder.PSC;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.u.OptionalBoolean;

public class CrudDaoLTest extends TestBase {

    interface TestCrudDaoL extends CrudDaoL<TestEntity, PSC, TestCrudDaoL> {
    }

    static final class TestEntity {
    }

    @Test
    public void testQueryForBoolean() throws SQLException {
        TestCrudDaoL dao = Mockito.mock(TestCrudDaoL.class, Mockito.CALLS_REAL_METHODS);

        when(dao.queryForBoolean("active", Long.valueOf(3L))).thenReturn(OptionalBoolean.of(true));

        assertTrue(dao.queryForBoolean("active", 3L).getAsBoolean());
        verify(dao).queryForBoolean("active", Long.valueOf(3L));
    }

    @Test
    public void testGet_WrapsEntityInOptional() throws SQLException {
        TestCrudDaoL dao = Mockito.mock(TestCrudDaoL.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        when(dao.get(Long.valueOf(5L))).thenReturn(Optional.of(entity));

        Optional<TestEntity> result = dao.get(5L);

        assertTrue(result.isPresent());
        assertSame(entity, result.orElseNull());
    }

    @Test
    public void testUpdate_DelegatesPrimitiveId() throws SQLException {
        TestCrudDaoL dao = Mockito.mock(TestCrudDaoL.class, Mockito.CALLS_REAL_METHODS);

        when(dao.update("name", "updated", Long.valueOf(6L))).thenReturn(1);

        assertEquals(1, dao.update("name", "updated", 6L));
    }
}
