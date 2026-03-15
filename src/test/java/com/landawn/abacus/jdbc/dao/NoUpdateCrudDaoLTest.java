package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.SqlBuilder;

public class NoUpdateCrudDaoLTest extends TestBase {

    private interface DummyNoUpdateCrudDaoL extends NoUpdateCrudDaoL<Object, SqlBuilder.PSC, DummyNoUpdateCrudDaoL> {
    }

    private final DummyNoUpdateCrudDaoL dao = createDefaultMethodProxy(DummyNoUpdateCrudDaoL.class);

    @Test
    public void testIsInterface() {
        assertTrue(NoUpdateCrudDaoL.class.isInterface());
    }

    @Test
    public void testExtendsNoUpdateCrudDao() {
        assertTrue(NoUpdateCrudDao.class.isAssignableFrom(NoUpdateCrudDaoL.class));
    }

    @Test
    public void testExtendsCrudDaoL() {
        assertTrue(CrudDaoL.class.isAssignableFrom(NoUpdateCrudDaoL.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, NoUpdateCrudDaoL.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(NoUpdateCrudDaoL.class.getDeclaredMethods().length > 0);
    }

    @Test
    public void testUpdate() {
        assertThrows(UnsupportedOperationException.class, () -> dao.update("status", "active", 1L));
    }

    @Test
    public void testUpdate_Map() {
        assertThrows(UnsupportedOperationException.class, () -> dao.update(Map.of("status", "active"), 1L));
    }

    @Test
    public void testDeleteById() {
        assertThrows(UnsupportedOperationException.class, () -> dao.deleteById(1L));
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
