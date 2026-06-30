package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedNoUpdateDaoTest extends TestBase {

    private interface DummyUncheckedNoUpdateDao extends UncheckedNoUpdateDao<Object, DummyUncheckedNoUpdateDao> {
    }

    private final DummyUncheckedNoUpdateDao dao = createDefaultMethodProxy(DummyUncheckedNoUpdateDao.class);

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedNoUpdateDao.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedDao() {
        assertTrue(UncheckedReadOps.class.isAssignableFrom(UncheckedNoUpdateDao.class));
    }

    @Test
    public void testExtendsNoUpdateDao() {
        assertTrue(NoUpdateDao.class.isAssignableFrom(UncheckedNoUpdateDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(2, UncheckedNoUpdateDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(UncheckedReadOps.class.isAssignableFrom(UncheckedNoUpdateDao.class));
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
