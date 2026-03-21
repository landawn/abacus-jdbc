package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.SqlBuilder.PSC;

public class UncheckedCrudJoinEntityHelperLTest extends TestBase {

    interface TestUncheckedCrudJoinDaoL
            extends UncheckedCrudDaoL<TestEntity, PSC, TestUncheckedCrudJoinDaoL>, UncheckedCrudJoinEntityHelperL<TestEntity, PSC, TestUncheckedCrudJoinDaoL> {
    }

    static final class TestEntity {
    }

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedCrudJoinEntityHelperL.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedCrudJoinEntityHelper() {
        assertTrue(UncheckedCrudJoinEntityHelper.class.isAssignableFrom(UncheckedCrudJoinEntityHelperL.class));
    }

    @Test
    public void testExtendsCrudJoinEntityHelperL() {
        assertTrue(CrudJoinEntityHelperL.class.isAssignableFrom(UncheckedCrudJoinEntityHelperL.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, UncheckedCrudJoinEntityHelperL.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(UncheckedCrudJoinEntityHelperL.class.getDeclaredMethods().length > 0);
    }

    @Test
    public void testGet_UsesPrimitiveLongOverloads() {
        TestUncheckedCrudJoinDaoL dao = Mockito.mock(TestUncheckedCrudJoinDaoL.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        when(dao.gett(1L, String.class)).thenReturn(entity);
        when(dao.gett(2L, true)).thenReturn(entity);
        when(dao.gett(3L, List.of("name"), String.class)).thenReturn(entity);
        when(dao.gett(4L, List.of("name"), List.of(String.class, Integer.class))).thenReturn(entity);
        when(dao.gett(5L, List.of("name"), true)).thenReturn(entity);

        assertSame(entity, dao.get(1L, String.class).orElseNull());
        assertSame(entity, dao.get(2L, true).orElseNull());
        assertSame(entity, dao.get(3L, List.of("name"), String.class).orElseNull());
        assertSame(entity, dao.get(4L, List.of("name"), List.of(String.class, Integer.class)).orElseNull());
        assertSame(entity, dao.get(5L, List.of("name"), true).orElseNull());
    }

    @Test
    public void testGett_LoadsRequestedJoinEntity() {
        TestUncheckedCrudJoinDaoL dao = Mockito.mock(TestUncheckedCrudJoinDaoL.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        when(dao.gett(6L)).thenReturn(entity);
        when(dao.gett(7L, List.of("id"))).thenReturn(entity);
        doNothing().when(dao).loadJoinEntities(entity, String.class);

        assertSame(entity, dao.gett(6L, String.class));
        assertSame(entity, dao.gett(7L, List.of("id"), String.class));
        verify(dao, times(2)).loadJoinEntities(entity, String.class);
    }

    @Test
    public void testGett_LoadsConfiguredJoinCollections() {
        TestUncheckedCrudJoinDaoL dao = Mockito.mock(TestUncheckedCrudJoinDaoL.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        when(dao.gett(8L)).thenReturn(entity);
        when(dao.gett(9L, List.of("id"))).thenReturn(entity);
        doNothing().when(dao).loadAllJoinEntities(entity);
        doNothing().when(dao).loadJoinEntities(eq(entity), eq(String.class));
        doNothing().when(dao).loadJoinEntities(eq(entity), eq(Integer.class));

        assertSame(entity, dao.gett(8L, true));
        assertSame(entity, dao.gett(9L, List.of("id"), List.of(String.class, Integer.class)));
        assertSame(entity, dao.gett(9L, List.of("id"), true));
        verify(dao, times(2)).loadAllJoinEntities(entity);
        verify(dao).loadJoinEntities(entity, String.class);
        verify(dao).loadJoinEntities(entity, Integer.class);
    }
}
