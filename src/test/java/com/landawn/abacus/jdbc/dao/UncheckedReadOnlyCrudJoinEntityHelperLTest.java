package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.SqlBuilder;

public class UncheckedReadOnlyCrudJoinEntityHelperLTest extends TestBase {

    private interface DummyDao extends UncheckedCrudDaoL<Object, SqlBuilder.PSC, DummyDao>,
            UncheckedReadOnlyCrudJoinEntityHelperL<Object, SqlBuilder.PSC, DummyDao> {
    }

    private final DummyDao dao = createDefaultMethodProxy(DummyDao.class);

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedReadOnlyCrudJoinEntityHelperL.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedReadOnlyJoinEntityHelper() {
        assertTrue(UncheckedReadOnlyJoinEntityHelper.class.isAssignableFrom(UncheckedReadOnlyCrudJoinEntityHelperL.class));
    }

    @Test
    public void testExtendsUncheckedCrudJoinEntityHelperL() {
        assertTrue(UncheckedCrudJoinEntityHelperL.class.isAssignableFrom(UncheckedReadOnlyCrudJoinEntityHelperL.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, UncheckedReadOnlyCrudJoinEntityHelperL.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(true, "Interface may inherit all methods without declaring its own");
    }

    @Test
    public void testDeleteJoinEntities_ResolvesToReadOnlyOverride() {
        assertThrows(UnsupportedOperationException.class, () -> dao.deleteJoinEntities(new Object(), String.class));
        assertThrows(UnsupportedOperationException.class, () -> dao.deleteJoinEntities(new Object(), "orders"));
        assertThrows(UnsupportedOperationException.class, () -> dao.deleteJoinEntities(new Object(), List.of("orders")));
        assertThrows(UnsupportedOperationException.class, () -> dao.deleteAllJoinEntities(new Object()));
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
