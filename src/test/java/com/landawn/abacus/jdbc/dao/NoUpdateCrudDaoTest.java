package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class NoUpdateCrudDaoTest extends TestBase {

    private interface DummyNoUpdateCrudDao extends NoUpdateCrudDao<Object, String, DummyNoUpdateCrudDao> {
    }

    private final DummyNoUpdateCrudDao dao = createDefaultMethodProxy(DummyNoUpdateCrudDao.class);

    @Test
    public void testIsInterface() {
        assertTrue(NoUpdateCrudDao.class.isInterface());
    }

    @Test
    public void testExtendsNoUpdateDao() {
        assertTrue(NoUpdateDao.class.isAssignableFrom(NoUpdateCrudDao.class));
    }

    @Test
    public void testExtendsCrudDao() {
        assertTrue(CrudReadOps.class.isAssignableFrom(NoUpdateCrudDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, NoUpdateCrudDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(CrudReadOps.class.isAssignableFrom(NoUpdateCrudDao.class));
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
