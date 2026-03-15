package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.SqlBuilder;

public class ReadOnlyCrudDaoTest extends TestBase {

    private interface DummyReadOnlyCrudDao extends ReadOnlyCrudDao<Object, String, SqlBuilder.PSC, DummyReadOnlyCrudDao> {
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
        assertTrue(NoUpdateCrudDao.class.isAssignableFrom(ReadOnlyCrudDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(4, ReadOnlyCrudDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(ReadOnlyCrudDao.class.getDeclaredMethods().length > 0);
    }

    @Test
    public void testInsert() {
        assertThrows(UnsupportedOperationException.class, () -> dao.insert(new Object()));
    }

    @Test
    public void testInsert_PropNamesToInsert() {
        assertThrows(UnsupportedOperationException.class, () -> dao.insert(new Object(), List.of("name")));
    }

    @Test
    public void testInsert_NamedInsertSql() {
        assertThrows(UnsupportedOperationException.class, () -> dao.insert("insertUser", new Object()));
    }

    @Test
    public void testBatchInsert() {
        assertThrows(UnsupportedOperationException.class, () -> dao.batchInsert(List.of(new Object())));
    }

    @Test
    public void testBatchInsert_BatchSize() {
        assertThrows(UnsupportedOperationException.class, () -> dao.batchInsert(List.of(new Object()), 2));
    }

    @Test
    public void testBatchInsert_PropNamesToInsert() {
        assertThrows(UnsupportedOperationException.class, () -> dao.batchInsert(List.of(new Object()), List.of("name")));
    }

    @Test
    public void testBatchInsert_PropNamesToInsertAndBatchSize() {
        assertThrows(UnsupportedOperationException.class, () -> dao.batchInsert(List.of(new Object()), List.of("name"), 2));
    }

    @Test
    public void testBatchInsert_NamedInsertSql() {
        assertThrows(UnsupportedOperationException.class, () -> dao.batchInsert("insertUser", List.of(new Object())));
    }

    @Test
    public void testBatchInsert_NamedInsertSqlAndBatchSize() {
        assertThrows(UnsupportedOperationException.class, () -> dao.batchInsert("insertUser", List.of(new Object()), 2));
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
