package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

@Tag("2025")
class HandlerFactorySpringLifecycleTest {
    @AfterEach
    void clearContext() {
        new SpringApplicationContext().setApplicationContext(null);
    }

    @Test
    void namedLookupReleasesClosedContextAndUsesItsReplacement() {
        final String qualifier = getClass().getName() + ".named";
        final NamedHandler firstHandler = new NamedHandler();
        try (AnnotationConfigApplicationContext first = context(qualifier, NamedHandler.class, firstHandler)) {
            assertSame(firstHandler, Jdbc.HandlerFactory.get(qualifier));
            first.close();
            assertNull(Jdbc.HandlerFactory.get(qualifier));

            final NamedHandler replacementHandler = new NamedHandler();
            try (AnnotationConfigApplicationContext replacement = context(qualifier, NamedHandler.class, replacementHandler)) {
                assertSame(replacementHandler, Jdbc.HandlerFactory.get(qualifier));
            }
            assertNull(Jdbc.HandlerFactory.get(qualifier));
        }
    }

    @Test
    void classLookupTracksReplacementWithoutRetainingTheOldBean() {
        final String qualifier = ClassHandler.class.getCanonicalName();
        final ClassHandler firstHandler = new ClassHandler();
        try (AnnotationConfigApplicationContext first = context(qualifier, ClassHandler.class, firstHandler)) {
            assertSame(firstHandler, Jdbc.HandlerFactory.get(ClassHandler.class));
            final ClassHandler replacementHandler = new ClassHandler();
            try (AnnotationConfigApplicationContext replacement = context(qualifier, ClassHandler.class, replacementHandler)) {
                assertSame(replacementHandler, Jdbc.HandlerFactory.get(ClassHandler.class));
                first.close();
                assertSame(replacementHandler, Jdbc.HandlerFactory.get(ClassHandler.class));
            }
            assertNull(Jdbc.HandlerFactory.get(ClassHandler.class));
        }
    }

    @Test
    void lookupsRespectSpringPrototypeScope() {
        final String qualifier = PrototypeHandler.class.getCanonicalName();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SpringApplicationContext.class);
            context.registerBean(qualifier, PrototypeHandler.class, PrototypeHandler::new,
                    definition -> definition.setScope(ConfigurableBeanFactory.SCOPE_PROTOTYPE));
            context.refresh();
            assertNotSame(Jdbc.HandlerFactory.get(qualifier), Jdbc.HandlerFactory.get(qualifier));
            assertNotSame(Jdbc.HandlerFactory.get(PrototypeHandler.class), Jdbc.HandlerFactory.get(PrototypeHandler.class));
            assertNotSame(Jdbc.HandlerFactory.getOrCreate(PrototypeHandler.class), Jdbc.HandlerFactory.getOrCreate(PrototypeHandler.class));
        }
    }

    @Test
    void explicitRegistrationSurvivesSpringContextShutdown() {
        final String qualifier = getClass().getName() + ".registered";
        final RegisteredHandler registered = new RegisteredHandler();
        assertTrue(Jdbc.HandlerFactory.register(qualifier, registered));
        try (AnnotationConfigApplicationContext context = context(qualifier, RegisteredHandler.class, new RegisteredHandler())) {
            assertSame(registered, Jdbc.HandlerFactory.get(qualifier));
            context.close();
            assertSame(registered, Jdbc.HandlerFactory.get(qualifier));
        }
    }

    @Test
    void explicitRegistrationDuringSuccessfulSpringLookupTakesPrecedence() {
        final RacingHandler registered = new RacingHandler();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SpringApplicationContext.class);
            context.registerBean(RacingHandler.class, () -> {
                assertTrue(Jdbc.HandlerFactory.register(registered));
                return new RacingHandler();
            }, definition -> definition.setScope(ConfigurableBeanFactory.SCOPE_PROTOTYPE));
            context.refresh();

            assertSame(registered, Jdbc.HandlerFactory.get(RacingHandler.class));
            context.close();
            assertSame(registered, Jdbc.HandlerFactory.get(RacingHandler.class));
        }
    }

    @Test
    void explicitRegistrationDuringNamedSpringLookupTakesPrecedence() {
        final String qualifier = getClass().getName() + ".racingNamed";
        final RacingHandler registered = new RacingHandler();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SpringApplicationContext.class);
            context.registerBean(qualifier, RacingHandler.class, () -> {
                assertTrue(Jdbc.HandlerFactory.register(qualifier, registered));
                return new RacingHandler();
            }, definition -> definition.setScope(ConfigurableBeanFactory.SCOPE_PROTOTYPE));
            context.refresh();

            assertSame(registered, Jdbc.HandlerFactory.get(qualifier));
            context.close();
            assertSame(registered, Jdbc.HandlerFactory.get(qualifier));
        }
    }

    @Test
    void namedSpringBeanThatIsNotAHandlerIsIgnored() {
        final String qualifier = getClass().getName() + ".notAHandler";
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SpringApplicationContext.class);
            context.registerBean(qualifier, String.class, () -> "not a handler");
            context.refresh();

            assertNull(Jdbc.HandlerFactory.get(qualifier));
            assertSame("not a handler", context.getBean(qualifier));
        }
    }

    @Test
    void propagatedChildCloseDoesNotClearItsParentsContext() {
        final String qualifier = getClass().getName() + ".parent";
        final NamedHandler parentHandler = new NamedHandler();
        try (AnnotationConfigApplicationContext parent = context(qualifier, NamedHandler.class, parentHandler)) {
            try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
                child.setParent(parent);
                child.refresh();
                assertSame(parentHandler, Jdbc.HandlerFactory.get(qualifier));
            }
            assertSame(parentHandler, Jdbc.HandlerFactory.get(qualifier));
        }
        assertNull(Jdbc.HandlerFactory.get(qualifier));
    }

    @Test
    void closingAChildWithItsOwnBridgeKeepsItsRunningParentsContext() {
        final String qualifier = getClass().getName() + ".parentWithChildBridge";
        final String childQualifier = getClass().getName() + ".childWithOwnBridge";
        final NamedHandler parentHandler = new NamedHandler();
        final NamedHandler childHandler = new NamedHandler();
        try (AnnotationConfigApplicationContext parent = context(qualifier, NamedHandler.class, parentHandler)) {
            try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
                child.setParent(parent);
                child.registerBean(SpringApplicationContext.class);
                child.registerBean(childQualifier, NamedHandler.class, () -> childHandler);
                child.refresh();
                assertSame(childHandler, Jdbc.HandlerFactory.get(childQualifier));
                assertSame(parentHandler, Jdbc.HandlerFactory.get(qualifier));
            }
            assertSame(parentHandler, Jdbc.HandlerFactory.get(qualifier));
            assertNull(Jdbc.HandlerFactory.get(childQualifier));
        }
        assertNull(Jdbc.HandlerFactory.get(qualifier));
    }

    private static <T extends Jdbc.Handler<?>> AnnotationConfigApplicationContext context(final String qualifier, final Class<T> type, final T handler) {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(SpringApplicationContext.class);
        context.registerBean(qualifier, type, () -> handler);
        context.refresh();
        return context;
    }

    public static final class NamedHandler implements Jdbc.Handler<Object> {
    }

    public static final class ClassHandler implements Jdbc.Handler<Object> {
    }

    public static final class PrototypeHandler implements Jdbc.Handler<Object> {
    }

    public static final class RegisteredHandler implements Jdbc.Handler<Object> {
    }

    public static final class RacingHandler implements Jdbc.Handler<Object> {
    }
}
