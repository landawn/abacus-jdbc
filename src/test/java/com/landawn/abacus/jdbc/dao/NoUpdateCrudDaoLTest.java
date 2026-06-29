package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class NoUpdateCrudDaoLTest extends TestBase {

    private interface DummyNoUpdateCrudDaoL extends NoUpdateCrudDaoL<Object, DummyNoUpdateCrudDaoL> {
    }

    private final DummyNoUpdateCrudDaoL dao = createDefaultMethodProxy(DummyNoUpdateCrudDaoL.class);

    @Test
    public void testIsInterface() {
        assertTrue(NoUpdateCrudDaoL.class.isInterface());
    }

    @Test
    public void testExtendsNoUpdateCrudDao() {
        assertTrue(NoUpdateCrudDao.class.isAssignableFrom(NoUpdateCrudDaoL.class));
    }

    @Test
    public void testExtendsCrudDaoL() {
        assertTrue(ReadableCrudDaoL.class.isAssignableFrom(NoUpdateCrudDaoL.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(2, NoUpdateCrudDaoL.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(ReadableCrudDaoL.class.isAssignableFrom(NoUpdateCrudDaoL.class));
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
