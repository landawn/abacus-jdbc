package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class ReadOnlyCrudDaoTest extends TestBase {

    private interface DummyReadOnlyCrudDao extends ReadOnlyCrudDao<Object, String, DummyReadOnlyCrudDao> {
    }

    private final DummyReadOnlyCrudDao dao = createDefaultMethodProxy(DummyReadOnlyCrudDao.class);

    @Test
    public void testIsInterface() {
        assertTrue(ReadOnlyCrudDao.class.isInterface());
    }

    @Test
    public void testExtendsReadOnlyDao() {
        assertTrue(ReadOnlyDao.class.isAssignableFrom(ReadOnlyCrudDao.class));
    }

    @Test
    public void testExtendsNoUpdateCrudDao() {
        assertTrue(ReadableCrudDao.class.isAssignableFrom(ReadOnlyCrudDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, ReadOnlyCrudDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(ReadableCrudDao.class.isAssignableFrom(ReadOnlyCrudDao.class));
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
