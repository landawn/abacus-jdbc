package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedNonUpdateDaoTest extends TestBase {

    private interface DummyUncheckedNonUpdateDao extends UncheckedNonUpdateDao<Object, DummyUncheckedNonUpdateDao> {
    }

    private final DummyUncheckedNonUpdateDao dao = createDefaultMethodProxy(DummyUncheckedNonUpdateDao.class);

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedNonUpdateDao.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedDao() {
        assertTrue(UncheckedReadOps.class.isAssignableFrom(UncheckedNonUpdateDao.class));
    }

    @Test
    public void testExtendsNonUpdateDao() {
        assertTrue(NonUpdateDao.class.isAssignableFrom(UncheckedNonUpdateDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(2, UncheckedNonUpdateDao.class.getTypeParameters().length);
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
