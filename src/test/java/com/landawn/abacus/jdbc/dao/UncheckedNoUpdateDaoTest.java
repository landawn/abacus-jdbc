package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.SqlBuilder;
import com.landawn.abacus.query.condition.Condition;

public class UncheckedNoUpdateDaoTest extends TestBase {

    private interface DummyUncheckedNoUpdateDao extends UncheckedNoUpdateDao<Object, SqlBuilder.PSC, DummyUncheckedNoUpdateDao> {
    }

    private final DummyUncheckedNoUpdateDao dao = createDefaultMethodProxy(DummyUncheckedNoUpdateDao.class);

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedNoUpdateDao.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedDao() {
        assertTrue(UncheckedDao.class.isAssignableFrom(UncheckedNoUpdateDao.class));
    }

    @Test
    public void testExtendsNoUpdateDao() {
        assertTrue(NoUpdateDao.class.isAssignableFrom(UncheckedNoUpdateDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, UncheckedNoUpdateDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(UncheckedNoUpdateDao.class.getDeclaredMethods().length > 0);
    }

    @Test
    public void testUpdate() {
        assertThrows(UnsupportedOperationException.class, () -> dao.update("status", "active", null));
    }

    @Test
    public void testUpdate_Map() {
        assertThrows(UnsupportedOperationException.class, () -> dao.update(Map.of("status", "active"), null));
    }

    @Test
    public void testUpdate_Entity() {
        assertThrows(UnsupportedOperationException.class, () -> dao.update(new Object(), null));
    }

    @Test
    public void testUpdate_EntityWithPropNames() {
        assertThrows(UnsupportedOperationException.class, () -> dao.update(new Object(), List.of("status"), null));
    }

    @Test
    public void testUpsert_UniquePropNames() {
        assertThrows(UnsupportedOperationException.class, () -> dao.upsert(new Object(), List.of("id")));
    }

    @Test
    public void testUpsert_Condition() {
        assertThrows(UnsupportedOperationException.class, () -> dao.upsert(new Object(), (Condition) null));
    }

    @Test
    public void testDelete() {
        assertThrows(UnsupportedOperationException.class, () -> dao.delete(null));
    }

    @SuppressWarnings("unchecked")
    private static <T> T createDefaultMethodProxy(final Class<T> interfaceType) {
        final InvocationHandler handler = (proxy, method, args) -> {
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }

            throw new UnsupportedOperationException("Unexpected invocation: " + method);
        };

        return (T) Proxy.newProxyInstance(interfaceType.getClassLoader(), new Class<?>[] { interfaceType }, handler);
    }
}
