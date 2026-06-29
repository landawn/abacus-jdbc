package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedNoUpdateCrudDaoTest extends TestBase {

    private interface DummyUncheckedNoUpdateCrudDao extends UncheckedNoUpdateCrudDao<Object, String, DummyUncheckedNoUpdateCrudDao> {
    }

    private final DummyUncheckedNoUpdateCrudDao dao = createDefaultMethodProxy(DummyUncheckedNoUpdateCrudDao.class);

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedNoUpdateCrudDao.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedNoUpdateDao() {
        assertTrue(UncheckedNoUpdateDao.class.isAssignableFrom(UncheckedNoUpdateCrudDao.class));
    }

    @Test
    public void testExtendsNoUpdateCrudDao() {
        assertTrue(NoUpdateCrudDao.class.isAssignableFrom(UncheckedNoUpdateCrudDao.class));
    }

    @Test
    public void testExtendsUncheckedCrudDao() {
        assertTrue(UncheckedReadableCrudDao.class.isAssignableFrom(UncheckedNoUpdateCrudDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, UncheckedNoUpdateCrudDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(UncheckedReadableCrudDao.class.isAssignableFrom(UncheckedNoUpdateCrudDao.class));
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
