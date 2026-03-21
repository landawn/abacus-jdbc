package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.query.SqlBuilder.PSC;
import com.landawn.abacus.util.u.Optional;

public class UncheckedCrudJoinEntityHelperTest extends TestBase {

    interface TestUncheckedCrudJoinDao extends UncheckedCrudDao<TestEntity, Long, PSC, TestUncheckedCrudJoinDao>,
            UncheckedCrudJoinEntityHelper<TestEntity, Long, PSC, TestUncheckedCrudJoinDao> {
    }

    static final class TestEntity {
    }

    @Test
    public void testGet_LoadsRequestedJoinEntity() {
        TestUncheckedCrudJoinDao dao = Mockito.mock(TestUncheckedCrudJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        when(dao.gett(7L)).thenReturn(entity);
        doNothing().when(dao).loadJoinEntities(entity, String.class);

        Optional<TestEntity> result = dao.get(7L, String.class);

        assertTrue(result.isPresent());
        assertSame(entity, result.orElseNull());
        verify(dao).loadJoinEntities(entity, String.class);
    }

    @Test
    public void testGet_LoadsAllJoinEntitiesWhenRequested() {
        TestUncheckedCrudJoinDao dao = Mockito.mock(TestUncheckedCrudJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        when(dao.gett(8L, List.of("id"))).thenReturn(entity);
        doNothing().when(dao).loadAllJoinEntities(entity);

        Optional<TestEntity> result = dao.get(8L, List.of("id"), true);

        assertTrue(result.isPresent());
        assertSame(entity, result.orElseNull());
        verify(dao).loadAllJoinEntities(entity);
    }

    @Test
    public void testBatchGet_UsesDefaultBatchSize() {
        TestUncheckedCrudJoinDao dao = Mockito.mock(TestUncheckedCrudJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<Long> ids = List.of(1L, 2L);
        List<TestEntity> entities = List.of(new TestEntity());

        when(dao.batchGet(ids, null, String.class, JdbcUtil.DEFAULT_BATCH_SIZE)).thenReturn(entities);

        assertEquals(entities, dao.batchGet(ids, String.class));
    }

    @Test
    public void testGet_UsesSelectedProperties() {
        TestUncheckedCrudJoinDao dao = Mockito.mock(TestUncheckedCrudJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        when(dao.gett(9L, List.of("name"), String.class)).thenReturn(entity);

        Optional<TestEntity> result = dao.get(9L, List.of("name"), String.class);

        assertTrue(result.isPresent());
        assertSame(entity, result.orElseNull());
    }
}
