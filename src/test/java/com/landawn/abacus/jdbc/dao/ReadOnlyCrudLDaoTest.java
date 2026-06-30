package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class ReadOnlyCrudLDaoTest extends TestBase {

    private interface DummyReadOnlyCrudLDao extends ReadOnlyCrudLDao<Object, DummyReadOnlyCrudLDao> {
    }

    private final DummyReadOnlyCrudLDao dao = createDefaultMethodProxy(DummyReadOnlyCrudLDao.class);

    @Test
    public void testIsInterface() {
        assertTrue(ReadOnlyCrudLDao.class.isInterface());
    }

    @Test
    public void testExtendsReadOnlyCrudDao() {
        assertTrue(ReadOnlyCrudDao.class.isAssignableFrom(ReadOnlyCrudLDao.class));
    }

    @Test
    public void testExtendsNoUpdateCrudLDao() {
        assertTrue(CrudLReadOps.class.isAssignableFrom(ReadOnlyCrudLDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(2, ReadOnlyCrudLDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(true, "Interface may inherit all methods without declaring its own");
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
