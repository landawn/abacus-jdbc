package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedReadOnlyDaoTest extends TestBase {

    private interface DummyUncheckedReadOnlyDao extends UncheckedReadOnlyDao<Object, DummyUncheckedReadOnlyDao> {
    }

    private final DummyUncheckedReadOnlyDao dao = createDefaultMethodProxy(DummyUncheckedReadOnlyDao.class);

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedReadOnlyDao.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedNoUpdateDao() {
        assertTrue(UncheckedReadOps.class.isAssignableFrom(UncheckedReadOnlyDao.class));
    }

    @Test
    public void testExtendsReadOnlyDao() {
        assertTrue(ReadOnlyDao.class.isAssignableFrom(UncheckedReadOnlyDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(2, UncheckedReadOnlyDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(UncheckedReadOps.class.isAssignableFrom(UncheckedReadOnlyDao.class));
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
