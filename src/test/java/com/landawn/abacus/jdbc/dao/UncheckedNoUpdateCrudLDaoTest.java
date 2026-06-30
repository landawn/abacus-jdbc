package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedNoUpdateCrudLDaoTest extends TestBase {

    private interface DummyUncheckedNoUpdateCrudLDao extends UncheckedNoUpdateCrudLDao<Object, DummyUncheckedNoUpdateCrudLDao> {
    }

    private final DummyUncheckedNoUpdateCrudLDao dao = createDefaultMethodProxy(DummyUncheckedNoUpdateCrudLDao.class);

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedNoUpdateCrudLDao.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedNoUpdateCrudDao() {
        assertTrue(UncheckedNoUpdateCrudDao.class.isAssignableFrom(UncheckedNoUpdateCrudLDao.class));
    }

    @Test
    public void testExtendsUncheckedCrudLDao() {
        assertTrue(UncheckedCrudLReadOps.class.isAssignableFrom(UncheckedNoUpdateCrudLDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(2, UncheckedNoUpdateCrudLDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(UncheckedCrudLReadOps.class.isAssignableFrom(UncheckedNoUpdateCrudLDao.class));
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
