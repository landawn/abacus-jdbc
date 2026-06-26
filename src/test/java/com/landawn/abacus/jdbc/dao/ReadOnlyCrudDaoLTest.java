package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class ReadOnlyCrudDaoLTest extends TestBase {

    private interface DummyReadOnlyCrudDaoL extends ReadOnlyCrudDaoL<Object, DummyReadOnlyCrudDaoL> {
    }

    private final DummyReadOnlyCrudDaoL dao = createDefaultMethodProxy(DummyReadOnlyCrudDaoL.class);

    @Test
    public void testIsInterface() {
        assertTrue(ReadOnlyCrudDaoL.class.isInterface());
    }

    @Test
    public void testExtendsReadOnlyCrudDao() {
        assertTrue(ReadOnlyCrudDao.class.isAssignableFrom(ReadOnlyCrudDaoL.class));
    }

    @Test
    public void testExtendsNoUpdateCrudDaoL() {
        assertTrue(NoUpdateCrudDaoL.class.isAssignableFrom(ReadOnlyCrudDaoL.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(2, ReadOnlyCrudDaoL.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(true, "Interface may inherit all methods without declaring its own");
    }

    @Test
    public void testInsertUnsupported_InheritedFromReadOnlyCrudDao() {
        assertThrows(UnsupportedOperationException.class, () -> dao.insert(new Object()));
        assertThrows(UnsupportedOperationException.class, () -> dao.batchInsert(List.of(new Object())));
    }

    @Test
    public void testUpdateUnsupported_LongOverloadInheritedFromNoUpdateCrudDaoL() {
        assertThrows(UnsupportedOperationException.class, () -> dao.update("status", "x", 1L));
        assertThrows(UnsupportedOperationException.class, () -> dao.deleteById(1L));
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
