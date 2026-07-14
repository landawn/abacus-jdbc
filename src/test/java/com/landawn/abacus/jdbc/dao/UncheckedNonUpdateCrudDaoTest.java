package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedNonUpdateCrudDaoTest extends TestBase {

    private interface DummyUncheckedNonUpdateCrudDao extends UncheckedNonUpdateCrudDao<Object, String, DummyUncheckedNonUpdateCrudDao> {
    }

    private final DummyUncheckedNonUpdateCrudDao dao = createDefaultMethodProxy(DummyUncheckedNonUpdateCrudDao.class);

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedNonUpdateCrudDao.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedNonUpdateDao() {
        assertTrue(UncheckedNonUpdateDao.class.isAssignableFrom(UncheckedNonUpdateCrudDao.class));
    }

    @Test
    public void testExtendsNonUpdateCrudDao() {
        assertTrue(NonUpdateCrudDao.class.isAssignableFrom(UncheckedNonUpdateCrudDao.class));
    }

    @Test
    public void testExtendsUncheckedCrudDao() {
        assertTrue(UncheckedCrudReadOps.class.isAssignableFrom(UncheckedNonUpdateCrudDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, UncheckedNonUpdateCrudDao.class.getTypeParameters().length);
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
