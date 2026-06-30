package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedReadOnlyCrudDaoTest extends TestBase {

    private interface DummyUncheckedReadOnlyCrudDao extends UncheckedReadOnlyCrudDao<Object, String, DummyUncheckedReadOnlyCrudDao> {
    }

    private final DummyUncheckedReadOnlyCrudDao dao = createDefaultMethodProxy(DummyUncheckedReadOnlyCrudDao.class);

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedReadOnlyCrudDao.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedReadOnlyDao() {
        assertTrue(UncheckedReadOnlyDao.class.isAssignableFrom(UncheckedReadOnlyCrudDao.class));
    }

    @Test
    public void testExtendsUncheckedNoUpdateCrudDao() {
        assertTrue(UncheckedCrudReadOps.class.isAssignableFrom(UncheckedReadOnlyCrudDao.class));
    }

    @Test
    public void testExtendsReadOnlyCrudDao() {
        assertTrue(ReadOnlyCrudDao.class.isAssignableFrom(UncheckedReadOnlyCrudDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, UncheckedReadOnlyCrudDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(UncheckedCrudReadOps.class.isAssignableFrom(UncheckedReadOnlyCrudDao.class));
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
