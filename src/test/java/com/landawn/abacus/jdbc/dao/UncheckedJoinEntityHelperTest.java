package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.SqlBuilder.PSC;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.util.u.Optional;

public class UncheckedJoinEntityHelperTest extends TestBase {

    interface TestUncheckedJoinDao
            extends UncheckedDao<TestEntity, PSC, TestUncheckedJoinDao>, UncheckedJoinEntityHelper<TestEntity, PSC, TestUncheckedJoinDao> {
    }

    static final class TestEntity {
    }

    @Test
    public void testFindFirst_LoadsRequestedJoinEntity() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.findFirst(null, condition)).thenReturn(Optional.of(entity));
        doNothing().when(dao).loadJoinEntities(entity, String.class);

        Optional<TestEntity> result = dao.findFirst(null, String.class, condition);

        assertTrue(result.isPresent());
        assertSame(entity, result.orElseNull());
        verify(dao).loadJoinEntities(entity, String.class);
    }

    @Test
    public void testFindFirst_LoadsAllJoinEntitiesWhenRequested() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.findFirst(List.of("id"), condition)).thenReturn(Optional.of(entity));
        doNothing().when(dao).loadAllJoinEntities(entity);

        Optional<TestEntity> result = dao.findFirst(List.of("id"), true, condition);

        assertTrue(result.isPresent());
        assertSame(entity, result.orElseNull());
        verify(dao).loadAllJoinEntities(entity);
    }

    @Test
    public void testFindOnlyOne_LoadsEachRequestedJoinEntity() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.findOnlyOne(null, condition)).thenReturn(Optional.of(entity));
        doNothing().when(dao).loadJoinEntities(eq(entity), eq(String.class));
        doNothing().when(dao).loadJoinEntities(eq(entity), eq(Integer.class));

        Optional<TestEntity> result = dao.findOnlyOne(null, List.of(String.class, Integer.class), condition);

        assertTrue(result.isPresent());
        assertSame(entity, result.orElseNull());
        verify(dao).loadJoinEntities(entity, String.class);
        verify(dao).loadJoinEntities(entity, Integer.class);
    }
}
