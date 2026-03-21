package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.SqlBuilder.PSC;
import com.landawn.abacus.util.u.Optional;

public class CrudJoinEntityHelperLTest extends TestBase {

    interface TestCrudJoinDaoL extends CrudDaoL<TestEntity, PSC, TestCrudJoinDaoL>, CrudJoinEntityHelperL<TestEntity, PSC, TestCrudJoinDaoL> {
    }

    static final class TestEntity {
    }

    @Test
    public void testIsInterface() {
        assertTrue(CrudJoinEntityHelperL.class.isInterface());
    }

    @Test
    public void testExtendsCrudJoinEntityHelper() {
        assertTrue(CrudJoinEntityHelper.class.isAssignableFrom(CrudJoinEntityHelperL.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, CrudJoinEntityHelperL.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(CrudJoinEntityHelperL.class.getDeclaredMethods().length > 0);
    }

    @Test
    public void testGet_UsesPrimitiveLongOverloads() throws SQLException {
        TestCrudJoinDaoL dao = Mockito.mock(TestCrudJoinDaoL.class, Mockito.CALLS_REAL_METHODS);
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
    public void testGett_LoadsRequestedJoinEntity() throws SQLException {
        TestCrudJoinDaoL dao = Mockito.mock(TestCrudJoinDaoL.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        when(dao.gett(6L)).thenReturn(entity);
        when(dao.gett(7L, List.of("id"))).thenReturn(entity);
        doNothing().when(dao).loadJoinEntities(entity, String.class);

        assertSame(entity, dao.gett(6L, String.class));
        assertSame(entity, dao.gett(7L, List.of("id"), String.class));
        verify(dao, times(2)).loadJoinEntities(entity, String.class);
    }

    @Test
    public void testGett_LoadsConfiguredJoinCollections() throws SQLException {
        TestCrudJoinDaoL dao = Mockito.mock(TestCrudJoinDaoL.class, Mockito.CALLS_REAL_METHODS);
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
