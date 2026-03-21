package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.SqlBuilder;

public class UncheckedReadOnlyDaoTest extends TestBase {

    private interface DummyUncheckedReadOnlyDao extends UncheckedReadOnlyDao<Object, SqlBuilder.PSC, DummyUncheckedReadOnlyDao> {
    }

    private final DummyUncheckedReadOnlyDao dao = createDefaultMethodProxy(DummyUncheckedReadOnlyDao.class);

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedReadOnlyDao.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedNoUpdateDao() {
        assertTrue(UncheckedNoUpdateDao.class.isAssignableFrom(UncheckedReadOnlyDao.class));
    }

    @Test
    public void testExtendsReadOnlyDao() {
        assertTrue(ReadOnlyDao.class.isAssignableFrom(UncheckedReadOnlyDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, UncheckedReadOnlyDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(UncheckedReadOnlyDao.class.getDeclaredMethods().length > 0);
    }

    @Test
    public void testSave_UnsupportedOperations() {
        assertThrows(UnsupportedOperationException.class, () -> dao.save(new Object()));
        assertThrows(UnsupportedOperationException.class, () -> dao.save(new Object(), List.of("name")));
        assertThrows(UnsupportedOperationException.class, () -> dao.save("insertUser", new Object()));
    }

    @Test
    public void testBatchSave_UnsupportedOperations() {
        assertThrows(UnsupportedOperationException.class, () -> dao.batchSave(List.of(new Object())));
        assertThrows(UnsupportedOperationException.class, () -> dao.batchSave(List.of(new Object()), 2));
        assertThrows(UnsupportedOperationException.class, () -> dao.batchSave(List.of(new Object()), List.of("name")));
        assertThrows(UnsupportedOperationException.class, () -> dao.batchSave(List.of(new Object()), List.of("name"), 2));
        assertThrows(UnsupportedOperationException.class, () -> dao.batchSave("insertUser", List.of(new Object())));
        assertThrows(UnsupportedOperationException.class, () -> dao.batchSave("insertUser", List.of(new Object()), 2));
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
