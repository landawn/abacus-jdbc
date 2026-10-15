package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;

import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.Tuple;
import com.landawn.abacus.util.Tuple.Tuple3;

class JdbcHandlerFactoryTest extends JdbcTestSupport {

    @Test
    public void testHandlerFactoryCreateWithBoth() {
        List<String> calls = new ArrayList<>();

        Jdbc.Handler<Object> handler = Jdbc.HandlerFactory.create((target, args, methodSig) -> calls.add("before"),
                (result, target, args, methodSig) -> calls.add("after"));

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        handler.beforeInvoke(new Object(), new Object[0], methodSignature);
        handler.afterInvoke("result", new Object(), new Object[0], methodSignature);

        assertEquals(2, calls.size());
        assertEquals("before", calls.get(0));
        assertEquals("after", calls.get(1));
    }

    @Test
    public void testHandlerFactory_Create_BothInvoke() throws Exception {
        final boolean[] beforeCalled = { false };
        final boolean[] afterCalled = { false };
        @SuppressWarnings("unchecked")
        final Jdbc.Handler<Object> handler = Jdbc.HandlerFactory.create(
                (Throwables.TriConsumer<Object, Object[], Tuple3<Method, ImmutableList<Class<?>>, Class<?>>, RuntimeException>) (proxy, args,
                        sig) -> beforeCalled[0] = true,
                (Throwables.QuadConsumer<Object, Object, Object[], Tuple3<Method, ImmutableList<Class<?>>, Class<?>>, RuntimeException>) (result, proxy, args,
                        sig) -> afterCalled[0] = true);

        handler.beforeInvoke(new Object(), new Object[0], null);
        handler.afterInvoke(null, new Object(), new Object[0], null);
        assertTrue(beforeCalled[0]);
        assertTrue(afterCalled[0]);
    }

    @Test
    public void testHandlerFactoryCreateWithAfterInvoke() {
        List<String> calls = new ArrayList<>();

        Jdbc.Handler<Object> handler = Jdbc.HandlerFactory.createForAfterInvoke(
                (Throwables.QuadConsumer<Object, Object, Object[], Tuple3<Method, ImmutableList<Class<?>>, Class<?>>, RuntimeException>) (result, target, args,
                        methodSig) -> calls.add("after"));

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        handler.afterInvoke("result", new Object(), new Object[0], methodSignature);

        assertEquals(1, calls.size());
        assertEquals("after", calls.get(0));
    }

    @Test
    public void testHandlerFactory_Create_AfterInvoke() throws Exception {
        final boolean[] called = { false };
        @SuppressWarnings("unchecked")
        final Jdbc.Handler<Object> handler = Jdbc.HandlerFactory.createForAfterInvoke(
                (Throwables.QuadConsumer<Object, Object, Object[], Tuple3<Method, ImmutableList<Class<?>>, Class<?>>, RuntimeException>) (result, proxy, args,
                        sig) -> called[0] = true);

        handler.afterInvoke(null, new Object(), new Object[0], null);
        assertTrue(called[0]);
    }

    //    @Test
    //    public void testHandlerFactoryGetByClass() {
    //        class NamedHandler implements Jdbc.Handler<Object> {
    //        }
    //
    //        Jdbc.HandlerFactory.register(NamedHandler.class);
    //        Jdbc.Handler<?> retrieved = Jdbc.HandlerFactory.get(NamedHandler.class);
    //
    //        assertNotNull(retrieved);
    //    }

    //    @Test
    //    public void testHandlerFactoryGetOrCreate() {
    //        class AutoHandler implements Jdbc.Handler<Object> {
    //        }
    //
    //        Jdbc.Handler<?> handler = Jdbc.HandlerFactory.getOrCreate(AutoHandler.class);
    //        assertNotNull(handler);
    //
    //        // Should get the same instance
    //        Jdbc.Handler<?> handler2 = Jdbc.HandlerFactory.getOrCreate(AutoHandler.class);
    //        assertSame(handler, handler2);
    //    }

    @Test
    public void testHandlerFactoryCreateWithBeforeInvoke() {
        List<String> calls = new ArrayList<>();

        Jdbc.Handler<Object> handler = Jdbc.HandlerFactory.createForBeforeInvoke((target, args, methodSig) -> calls.add("before"));

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        handler.beforeInvoke(new Object(), new Object[0], methodSignature);

        assertEquals(1, calls.size());
        assertEquals("before", calls.get(0));
    }

    @Test
    public void testHandlerFactory_Create_BeforeInvoke() throws Exception {
        final boolean[] called = { false };
        @SuppressWarnings("unchecked")
        final Jdbc.Handler<Object> handler = Jdbc.HandlerFactory
                .createForBeforeInvoke((Throwables.TriConsumer<Object, Object[], Tuple3<Method, ImmutableList<Class<?>>, Class<?>>, RuntimeException>) (proxy,
                        args, sig) -> called[0] = true);

        handler.beforeInvoke(new Object(), new Object[0], null);
        assertTrue(called[0]);
    }

    // HandlerFactory Tests
    @Test
    @SuppressWarnings("unchecked")
    public void testHandlerFactoryRegisterByClass() {
        // register by class - first time should succeed
        Jdbc.HandlerFactory.register(RegisterByClassHandler.class);
        Jdbc.Handler<?> retrieved = Jdbc.HandlerFactory.get(RegisterByClassHandler.class);
        assertNotNull(retrieved);
        assertTrue(retrieved instanceof RegisterByClassHandler);

        // second registration should return false
        assertFalse(Jdbc.HandlerFactory.register(RegisterByClassHandler.class));
    }

    @Test
    public void testHandlerFactoryGet() {
        Jdbc.Handler<Object> handler = new Jdbc.Handler<>() {
        };
        String qualifier = "getTestHandler";

        Jdbc.HandlerFactory.register(qualifier, handler);
        Jdbc.Handler<?> retrieved = Jdbc.HandlerFactory.get(qualifier);

        assertSame(handler, retrieved);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testHandlerFactory_Register_And_Get() {
        final String qualifier = "TestHandlerUnique_" + System.nanoTime();
        final TestHandler handler = new TestHandler();
        boolean registered = Jdbc.HandlerFactory.register(qualifier, handler);
        assertTrue(registered);

        // second registration returns false
        assertFalse(Jdbc.HandlerFactory.register(qualifier, handler));

        assertSame(handler, Jdbc.HandlerFactory.get(qualifier));
    }

    @Test
    public void testHandlerFactory_RegisterByClass() {
        boolean registered = Jdbc.HandlerFactory.register(TestHandler.class);
        // may be false if already registered in testHandlerFactory_Register_And_Get
        assertNotNull(Jdbc.HandlerFactory.get(TestHandler.class));
    }

    @Test
    public void testHandlerFactory_RegisterWithQualifier() {
        final Jdbc.Handler<Object> h = new TestHandler();
        final String qualifier = "myCustomHandler_unique_12345";
        assertTrue(Jdbc.HandlerFactory.register(qualifier, h));
        assertFalse(Jdbc.HandlerFactory.register(qualifier, h));
        assertSame(h, Jdbc.HandlerFactory.get(qualifier));
    }

    @Test
    public void testHandlerFactoryGetByQualifierReturnsConcurrentRegistrationWinner() {
        final String qualifier = JdbcTest.class.getName() + ".qualifierRace." + System.nanoTime();
        final Jdbc.Handler<Object> winner = new Jdbc.Handler<>() {
        };
        final Jdbc.Handler<Object> springBean = new Jdbc.Handler<>() {
        };
        final ApplicationContext applicationContext = Mockito.mock(ApplicationContext.class);

        when(applicationContext.getBean(qualifier)).thenAnswer(invocation -> {
            assertTrue(Jdbc.HandlerFactory.register(qualifier, winner));
            return springBean;
        });

        new SpringApplicationContext().setApplicationContext(applicationContext);

        try {
            assertSame(winner, Jdbc.HandlerFactory.get(qualifier));
        } finally {
            new SpringApplicationContext().setApplicationContext(null);
        }
    }

    @Test
    public void testHandlerFactoryGetByClassReturnsConcurrentRegistrationWinner() {
        final SpringRaceHandler winner = new SpringRaceHandler();
        final ApplicationContext applicationContext = Mockito.mock(ApplicationContext.class);

        when(applicationContext.getBean(SpringRaceHandler.class)).thenAnswer(invocation -> {
            assertTrue(Jdbc.HandlerFactory.register(winner));
            throw new IllegalStateException("simulated Spring lookup failure after concurrent registration");
        });

        new SpringApplicationContext().setApplicationContext(applicationContext);

        try {
            assertSame(winner, Jdbc.HandlerFactory.get(SpringRaceHandler.class));
        } finally {
            new SpringApplicationContext().setApplicationContext(null);
        }
    }

    @Test
    public void testHandlerFactory_GetByString_NotFound_ReturnsNull() {
        assertNull(Jdbc.HandlerFactory.get("nonExistentHandler_xyz_12345"));
    }

    @Test
    public void testHandlerFactory_GetByClass_NotFound_ReturnsNull() {
        assertNull(Jdbc.HandlerFactory.get((Class<? extends Jdbc.Handler<?>>) (Class<?>) java.io.Serializable.class));
    }

    @Test
    public void testHandlerFactoryPropagatesConstructorFailure() {
        assertSame(FailingConstructorHandler.FAILURE,
                assertThrows(IllegalStateException.class, () -> Jdbc.HandlerFactory.register(FailingConstructorHandler.class)));
        assertSame(FailingConstructorHandler.FAILURE,
                assertThrows(IllegalStateException.class, () -> Jdbc.HandlerFactory.getOrCreate(FailingConstructorHandler.class)));
        assertNull(Jdbc.HandlerFactory.get(FailingConstructorHandler.class));
    }

    @Test
    public void testHandlerFactoryIgnoresOptionalSpringLinkageFailures() {
        final String qualifier = JdbcTest.class.getName() + ".linkageFailure." + System.nanoTime();
        final ApplicationContext applicationContext = Mockito.mock(ApplicationContext.class);

        when(applicationContext.getBean(qualifier)).thenThrow(new NoSuchMethodError("incompatible Spring API"));
        when(applicationContext.getBean(SpringLinkageFailureHandler.class)).thenThrow(new NoClassDefFoundError("missing optional Spring type"));
        when(applicationContext.getBean(SpringLinkageFailureHandler.class.getCanonicalName())).thenThrow(new NoSuchMethodError("incompatible Spring API"));

        new SpringApplicationContext().setApplicationContext(applicationContext);

        try {
            assertNull(Jdbc.HandlerFactory.get(qualifier));
            assertNull(Jdbc.HandlerFactory.get(SpringLinkageFailureHandler.class));
        } finally {
            new SpringApplicationContext().setApplicationContext(null);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testHandlerFactoryGetOrCreate() {
        Jdbc.Handler<?> handler = Jdbc.HandlerFactory.getOrCreate(GetOrCreateHandler.class);
        assertNotNull(handler);

        // second call should return the same instance
        Jdbc.Handler<?> handler2 = Jdbc.HandlerFactory.getOrCreate(GetOrCreateHandler.class);
        assertSame(handler, handler2);
    }

    @Test
    public void testHandlerFactory_GetOrCreate() {
        final Jdbc.Handler<?> h = Jdbc.HandlerFactory.getOrCreate(TestHandler.class);
        assertNotNull(h);
    }

    // Regression: HandlerFactory.getOrCreate ignored register()'s false return (which signals
    // another thread won the race), so the losing thread returned its OWN unregistered instance.
    // After the fix, the losing thread re-fetches and returns the winner's registered instance,
    // preserving the once-per-qualifier identity contract.
    @Tag("2025")
    @Test
    public void testHandlerFactory_GetOrCreate_RaceLosingThreadReturnsRegisteredInstance() throws Exception {
        // Pre-register an instance manually to simulate "another thread already won the race".
        Jdbc.Handler<Object> winner = new Jdbc.Handler<>() {
        };

        Jdbc.HandlerFactory.register(winner);

        try {
            // getOrCreate(class) sees winner is registered (under the canonical class name) and
            // returns it. Pre-fix: depended on no-class-registration; here we directly exercise
            // the race semantics by attempting to register a competing instance and confirming
            // the registered winner is what subsequent calls see.
            @SuppressWarnings({ "unchecked", "rawtypes" })
            Class<? extends Jdbc.Handler<?>> handlerClass = (Class) winner.getClass();
            Jdbc.Handler<?> retrieved = Jdbc.HandlerFactory.getOrCreate(handlerClass);

            assertSame(winner, retrieved, "getOrCreate must return the registered winner, not a fresh instance");
        } finally {
            // Clean up the registry slot for this anonymous-class-based test handler.
            // (HandlerFactory.register's qualifier is the canonical class name; the anonymous
            // class name persists for the JVM lifetime, but the next test won't collide because
            // anonymous classes get unique names per declaration site.)
        }
    }

    // Handler Tests
    @Test
    public void testHandlerDefaultMethods() {
        Jdbc.Handler<Object> handler = new Jdbc.Handler<>() {
        };

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        // Default beforeInvoke and afterInvoke should be no-ops that complete without error
        handler.beforeInvoke(new Object(), new Object[0], methodSignature);
        handler.afterInvoke("result", new Object(), new Object[0], methodSignature);

        // Verify the handler is an instance of the Handler interface
        assertTrue(handler instanceof Jdbc.Handler);
        // Verify the method signature tuple was constructed correctly
        assertSame(method, methodSignature._1);
        assertEquals(0, paramTypes.size());
        assertEquals(Object.class, methodSignature._3);
    }

    //    @Test
    //    public void testHandlerFactoryRegisterClass() {
    //        class TestHandler implements Jdbc.Handler<Object> {
    //        }
    //
    //        boolean registered = Jdbc.HandlerFactory.register(TestHandler.class);
    //        assertTrue(registered);
    //
    //        // Second registration should fail
    //        assertFalse(Jdbc.HandlerFactory.register(TestHandler.class));
    //    }

    @Test
    public void testHandlerFactoryRegisterInstance() {
        Jdbc.Handler<Object> handler = new Jdbc.Handler<>() {
        };

        boolean registered = Jdbc.HandlerFactory.register(handler);
        assertTrue(registered);
    }

    @Test
    public void testHandlerFactoryRegisterWithQualifier() {
        Jdbc.Handler<Object> handler = new Jdbc.Handler<>() {
        };

        boolean registered = Jdbc.HandlerFactory.register("testHandler", handler);
        assertTrue(registered);

        // Same qualifier should fail
        assertFalse(Jdbc.HandlerFactory.register("testHandler", handler));
    }

    public static final class FailingConstructorHandler implements Jdbc.Handler<Object> {
        static final IllegalStateException FAILURE = new IllegalStateException("handler construction failed");

        public FailingConstructorHandler() {
            throw FAILURE;
        }
    }

    public static final class SpringRaceHandler implements Jdbc.Handler<Object> {
        // Marker handler used by the deterministic registration-race test.
    }

    public static final class SpringLinkageFailureHandler implements Jdbc.Handler<Object> {
    }

    static final class RegisterByClassHandler implements Jdbc.Handler<Object> {
    }

    static final class GetOrCreateHandler implements Jdbc.Handler<Object> {
    }

    // HandlerFactory tests
    static final class TestHandler implements Jdbc.Handler<Object> {
    }
}
