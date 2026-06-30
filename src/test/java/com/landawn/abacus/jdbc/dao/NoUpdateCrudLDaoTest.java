package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class NoUpdateCrudLDaoTest extends TestBase {

    private interface DummyNoUpdateCrudLDao extends NoUpdateCrudLDao<Object, DummyNoUpdateCrudLDao> {
    }

    private final DummyNoUpdateCrudLDao dao = createDefaultMethodProxy(DummyNoUpdateCrudLDao.class);

    @Test
    public void testIsInterface() {
        assertTrue(NoUpdateCrudLDao.class.isInterface());
    }

    @Test
    public void testExtendsNoUpdateCrudDao() {
        assertTrue(NoUpdateCrudDao.class.isAssignableFrom(NoUpdateCrudLDao.class));
    }

    @Test
    public void testExtendsCrudLDao() {
        assertTrue(CrudLReadOps.class.isAssignableFrom(NoUpdateCrudLDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(2, NoUpdateCrudLDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(CrudLReadOps.class.isAssignableFrom(NoUpdateCrudLDao.class));
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
