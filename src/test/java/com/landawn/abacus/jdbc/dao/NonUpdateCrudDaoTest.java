package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class NonUpdateCrudDaoTest extends TestBase {

    private interface DummyNonUpdateCrudDao extends NonUpdateCrudDao<Object, String, DummyNonUpdateCrudDao> {
    }

    private final DummyNonUpdateCrudDao dao = createDefaultMethodProxy(DummyNonUpdateCrudDao.class);

    @Test
    public void testIsInterface() {
        assertTrue(NonUpdateCrudDao.class.isInterface());
    }

    @Test
    public void testExtendsNonUpdateDao() {
        assertTrue(NonUpdateDao.class.isAssignableFrom(NonUpdateCrudDao.class));
    }

    @Test
    public void testExtendsCrudDao() {
        assertTrue(CrudReadOps.class.isAssignableFrom(NonUpdateCrudDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, NonUpdateCrudDao.class.getTypeParameters().length);
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
