package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.SqlBuilder;
import com.landawn.abacus.query.condition.Condition;

public class UncheckedNoUpdateCrudDaoTest extends TestBase {

    private interface DummyUncheckedNoUpdateCrudDao
            extends UncheckedNoUpdateCrudDao<Object, String, SqlBuilder.PSC, DummyUncheckedNoUpdateCrudDao> {
    }

    private final DummyUncheckedNoUpdateCrudDao dao = createDefaultMethodProxy(DummyUncheckedNoUpdateCrudDao.class);

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedNoUpdateCrudDao.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedNoUpdateDao() {
        assertTrue(UncheckedNoUpdateDao.class.isAssignableFrom(UncheckedNoUpdateCrudDao.class));
    }

    @Test
    public void testExtendsNoUpdateCrudDao() {
        assertTrue(NoUpdateCrudDao.class.isAssignableFrom(UncheckedNoUpdateCrudDao.class));
    }

    @Test
    public void testExtendsUncheckedCrudDao() {
        assertTrue(UncheckedCrudDao.class.isAssignableFrom(UncheckedNoUpdateCrudDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(4, UncheckedNoUpdateCrudDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(UncheckedNoUpdateCrudDao.class.getDeclaredMethods().length > 0);
    }

    @Test
    public void testUpdate() {
        assertThrows(UnsupportedOperationException.class, () -> dao.update(new Object()));
        assertThrows(UnsupportedOperationException.class, () -> dao.update(new Object(), List.of("name")));
        assertThrows(UnsupportedOperationException.class, () -> dao.update("status", "active", "id-1"));
        assertThrows(UnsupportedOperationException.class, () -> dao.update(Map.of("status", "active"), "id-1"));
    }

    @Test
    public void testBatchUpdate() {
        assertThrows(UnsupportedOperationException.class, () -> dao.batchUpdate(List.of(new Object())));
        assertThrows(UnsupportedOperationException.class, () -> dao.batchUpdate(List.of(new Object()), 2));
        assertThrows(UnsupportedOperationException.class, () -> dao.batchUpdate(List.of(new Object()), List.of("name")));
        assertThrows(UnsupportedOperationException.class, () -> dao.batchUpdate(List.of(new Object()), List.of("name"), 2));
    }

    @Test
    public void testUpsert() {
        assertThrows(UnsupportedOperationException.class, () -> dao.upsert(new Object()));
        assertThrows(UnsupportedOperationException.class, () -> dao.upsert(new Object(), List.of("id")));
        assertThrows(UnsupportedOperationException.class, () -> dao.upsert(new Object(), (Condition) null));
    }

    @Test
    public void testBatchUpsert() {
        assertThrows(UnsupportedOperationException.class, () -> dao.batchUpsert(List.of(new Object())));
        assertThrows(UnsupportedOperationException.class, () -> dao.batchUpsert(List.of(new Object()), 2));
        assertThrows(UnsupportedOperationException.class, () -> dao.batchUpsert(List.of(new Object()), List.of("id")));
        assertThrows(UnsupportedOperationException.class, () -> dao.batchUpsert(List.of(new Object()), List.of("id"), 2));
    }

    @Test
    public void testDelete() {
        assertThrows(UnsupportedOperationException.class, () -> dao.delete(new Object()));
    }

    @Test
    public void testDeleteById() {
        assertThrows(UnsupportedOperationException.class, () -> dao.deleteById("id-1"));
    }

    @Test
    public void testBatchDelete() {
        assertThrows(UnsupportedOperationException.class, () -> dao.batchDelete(List.of(new Object())));
        assertThrows(UnsupportedOperationException.class, () -> dao.batchDelete(List.of(new Object()), 2));
    }

    @Test
    public void testBatchDeleteByIds() {
        assertThrows(UnsupportedOperationException.class, () -> dao.batchDeleteByIds(List.of("id-1")));
        assertThrows(UnsupportedOperationException.class, () -> dao.batchDeleteByIds(List.of("id-1"), 2));
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
