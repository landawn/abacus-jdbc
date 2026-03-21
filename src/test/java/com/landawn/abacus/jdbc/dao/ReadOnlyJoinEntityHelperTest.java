package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.SqlBuilder;

public class ReadOnlyJoinEntityHelperTest extends TestBase {

    private interface DummyReadOnlyJoinEntityHelper extends Dao<Object, SqlBuilder.PSC, DummyReadOnlyJoinEntityHelper>,
            ReadOnlyJoinEntityHelper<Object, SqlBuilder.PSC, DummyReadOnlyJoinEntityHelper> {
    }

    private final DummyReadOnlyJoinEntityHelper helper = createDefaultMethodProxy(DummyReadOnlyJoinEntityHelper.class);

    @Test
    public void testIsInterface() {
        assertTrue(ReadOnlyJoinEntityHelper.class.isInterface());
    }

    @Test
    public void testExtendsJoinEntityHelper() {
        assertTrue(JoinEntityHelper.class.isAssignableFrom(ReadOnlyJoinEntityHelper.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, ReadOnlyJoinEntityHelper.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(ReadOnlyJoinEntityHelper.class.getDeclaredMethods().length > 0);
    }

    @Test
    public void testDeleteJoinEntities_UnsupportedOperations() {
        Executor executor = Runnable::run;

        assertThrows(UnsupportedOperationException.class, () -> helper.deleteJoinEntities(new Object(), String.class));
        assertThrows(UnsupportedOperationException.class, () -> helper.deleteJoinEntities(List.of(new Object()), String.class));
        assertThrows(UnsupportedOperationException.class, () -> helper.deleteJoinEntities(new Object(), "orders"));
        assertThrows(UnsupportedOperationException.class, () -> helper.deleteJoinEntities(List.of(new Object()), "orders"));
        assertThrows(UnsupportedOperationException.class, () -> helper.deleteJoinEntities(new Object(), List.of("orders")));
        assertThrows(UnsupportedOperationException.class, () -> helper.deleteJoinEntities(new Object(), List.of("orders"), true));
        assertThrows(UnsupportedOperationException.class, () -> helper.deleteJoinEntities(new Object(), List.of("orders"), executor));
        assertThrows(UnsupportedOperationException.class, () -> helper.deleteJoinEntities(List.of(new Object()), List.of("orders")));
        assertThrows(UnsupportedOperationException.class, () -> helper.deleteJoinEntities(List.of(new Object()), List.of("orders"), true));
        assertThrows(UnsupportedOperationException.class, () -> helper.deleteJoinEntities(List.of(new Object()), List.of("orders"), executor));
    }

    @Test
    public void testDeleteAllJoinEntities_UnsupportedOperations() {
        Executor executor = Runnable::run;

        assertThrows(UnsupportedOperationException.class, () -> helper.deleteAllJoinEntities(new Object()));
        assertThrows(UnsupportedOperationException.class, () -> helper.deleteAllJoinEntities(new Object(), true));
        assertThrows(UnsupportedOperationException.class, () -> helper.deleteAllJoinEntities(new Object(), executor));
        assertThrows(UnsupportedOperationException.class, () -> helper.deleteAllJoinEntities(List.of(new Object())));
        assertThrows(UnsupportedOperationException.class, () -> helper.deleteAllJoinEntities(List.of(new Object()), true));
        assertThrows(UnsupportedOperationException.class, () -> helper.deleteAllJoinEntities(List.of(new Object()), executor));
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
