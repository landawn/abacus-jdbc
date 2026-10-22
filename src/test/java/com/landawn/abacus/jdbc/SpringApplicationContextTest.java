package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;

import com.landawn.abacus.TestBase;

public class SpringApplicationContextTest extends TestBase {

    private SpringApplicationContext target;
    private ApplicationContext appContext;

    @BeforeEach
    public void setUp() throws Exception {
        target = new SpringApplicationContext();
        appContext = mock(ApplicationContext.class);
        // The ApplicationContext is now held in a process-wide (static) holder, so reset it before each
        // test to keep tests isolated from one another.
        setApplicationContext(null);
    }

    // Verifies bean lookup by name delegates to the injected ApplicationContext.
    @Test
    public void testGetBean() throws Exception {
        Object bean = new Object();
        when(appContext.getBean("beanName")).thenReturn(bean);

        setApplicationContext(appContext);

        assertSame(bean, target.getBean("beanName"));
    }

    @Test
    public void testGetBean_NullApplicationContext() {
        assertNull(target.getBean("beanName"));
    }

    @Test
    public void testTypeAndConstructorArePublic() throws NoSuchMethodException {
        assertTrue(Modifier.isPublic(SpringApplicationContext.class.getModifiers()));
        assertTrue(Modifier.isPublic(SpringApplicationContext.class.getDeclaredConstructor().getModifiers()));
    }

    // Verifies typed bean lookup delegates to the injected ApplicationContext.
    @Test
    public void testGetBean_Class() throws Exception {
        when(appContext.getBean(String.class)).thenReturn("bean");

        setApplicationContext(appContext);

        assertSame("bean", target.getBean(String.class));
    }

    @Test
    public void testGetBean_Class_NullApplicationContext() {
        assertNull(target.getBean(String.class));
    }

    @Test
    public void testGetBean_NameAndClass() throws Exception {
        when(appContext.getBean("beanName", String.class)).thenReturn("bean");

        setApplicationContext(appContext);

        assertSame("bean", target.getBean("beanName", String.class));
    }

    @Test
    public void testBeanCreationFailuresPropagateFromEveryLookup() {
        final BeanCreationException failure = new BeanCreationException("beanName", "creation failed");
        when(appContext.getBean("beanName")).thenThrow(failure);
        when(appContext.getBean(String.class)).thenThrow(failure);
        when(appContext.getBean("beanName", String.class)).thenThrow(failure);
        target.setApplicationContext(appContext);

        try {
            assertSame(failure, assertThrows(BeanCreationException.class, () -> target.getBean("beanName")));
            assertSame(failure, assertThrows(BeanCreationException.class, () -> target.getBean(String.class)));
            assertSame(failure, assertThrows(BeanCreationException.class, () -> target.getBean("beanName", String.class)));
        } finally {
            target.setApplicationContext(null);
        }
    }

    @Test
    public void testLookupValidationDependsOnContextAvailabilityAndState() {
        assertNull(target.getBean((String) null));
        assertNull(target.getBean((Class<?>) null));
        assertNull(target.getBean(null, String.class));

        try (GenericApplicationContext context = new GenericApplicationContext()) {
            target.setApplicationContext(context);
            assertThrows(IllegalStateException.class, () -> target.getBean((String) null));
            assertThrows(IllegalStateException.class, () -> target.getBean((Class<?>) null));
            assertThrows(IllegalStateException.class, () -> target.getBean(null, String.class));

            context.refresh();
            assertThrows(IllegalArgumentException.class, () -> target.getBean((String) null));
            assertThrows(IllegalArgumentException.class, () -> target.getBean((Class<?>) null));
            assertThrows(IllegalArgumentException.class, () -> target.getBean(null, String.class));
        } finally {
            target.setApplicationContext(null);
        }
    }

    @Test
    public void testClosingRegisteredContextClearsSharedHolder() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(SpringApplicationContext.class, () -> target);
            context.registerBean("value", String.class, () -> "available");
            context.refresh();
            target.setApplicationContext(context);

            assertSame("available", new SpringApplicationContext().getBean("value"));
            context.close();

            assertNull(target.getBean("value"));
            assertNull(target.getBean(String.class));
            assertNull(target.getBean("value", String.class));
        } finally {
            target.setApplicationContext(null);
        }
    }

    @Test
    public void testClosingOldContextPreservesReplacementContext() {
        try (GenericApplicationContext oldContext = new GenericApplicationContext();
             GenericApplicationContext replacementContext = new GenericApplicationContext()) {
            oldContext.registerBean(SpringApplicationContext.class, () -> target);
            oldContext.refresh();
            target.setApplicationContext(oldContext);

            final SpringApplicationContext replacementBridge = new SpringApplicationContext();
            replacementContext.registerBean(SpringApplicationContext.class, () -> replacementBridge);
            replacementContext.registerBean("value", String.class, () -> "replacement");
            replacementContext.refresh();
            replacementBridge.setApplicationContext(replacementContext);

            oldContext.close();
            assertSame("replacement", target.getBean("value"));

            replacementContext.close();
            assertNull(target.getBean("value"));
        } finally {
            target.setApplicationContext(null);
        }
    }

    @Test
    public void testClosingChildContextFallsBackToStillRunningParent() {
        try (GenericApplicationContext parent = new GenericApplicationContext();
             GenericApplicationContext child = new GenericApplicationContext()) {
            final SpringApplicationContext parentBridge = new SpringApplicationContext();
            parent.registerBean(SpringApplicationContext.class, () -> parentBridge);
            parent.registerBean("value", String.class, () -> "parent");
            parent.refresh();
            parentBridge.setApplicationContext(parent);

            child.setParent(parent);
            child.registerBean(SpringApplicationContext.class, () -> target);
            child.registerBean("childOnly", String.class, () -> "child");
            child.refresh();
            target.setApplicationContext(child);

            assertSame("child", target.getBean("childOnly"));
            assertSame("parent", target.getBean("value"));

            child.close();
            assertSame("parent", target.getBean("value"));
            assertSame("parent", new SpringApplicationContext().getBean("value", String.class));
            assertThrows(NoSuchBeanDefinitionException.class, () -> target.getBean("childOnly"));

            parent.close();
            assertNull(target.getBean("value"));
        } finally {
            target.setApplicationContext(null);
        }
    }

    @Test
    public void testReRegisteringContextIsIdempotentAndKeepsItsPosition() {
        try (GenericApplicationContext parent = new GenericApplicationContext();
             GenericApplicationContext child = new GenericApplicationContext()) {
            final SpringApplicationContext parentBridge = new SpringApplicationContext();
            parent.registerBean(SpringApplicationContext.class, () -> parentBridge);
            parent.registerBean("value", String.class, () -> "parent");
            parent.refresh();
            parentBridge.setApplicationContext(parent);

            child.setParent(parent);
            child.registerBean(SpringApplicationContext.class, () -> target);
            child.registerBean("value", String.class, () -> "child");
            child.refresh();
            target.setApplicationContext(child);

            // A repeated injection, or another (e.g. lazily created) bridge of an already registered context,
            // neither duplicates it nor moves it ahead of a context registered later.
            target.setApplicationContext(child);
            parentBridge.setApplicationContext(parent);
            new SpringApplicationContext().setApplicationContext(parent);
            assertSame("child", target.getBean("value"));

            child.close();
            assertSame("parent", target.getBean("value"));

            // One close removes the context however often it was registered.
            parent.close();
            assertNull(target.getBean("value"));
        } finally {
            target.setApplicationContext(null);
        }
    }

    @Test
    public void testReleasingOneContextKeepsTheOthersInRegistrationOrder() {
        final ApplicationContext first = mock(ApplicationContext.class);
        final ApplicationContext second = mock(ApplicationContext.class);
        final ApplicationContext third = mock(ApplicationContext.class);
        when(first.getBean("value")).thenReturn("first");
        when(second.getBean("value")).thenReturn("second");
        when(third.getBean("value")).thenReturn("third");
        final SpringApplicationContext firstBridge = new SpringApplicationContext();
        final SpringApplicationContext secondBridge = new SpringApplicationContext();
        final SpringApplicationContext thirdBridge = new SpringApplicationContext();

        try {
            firstBridge.setApplicationContext(first);
            secondBridge.setApplicationContext(second);
            thirdBridge.setApplicationContext(third);
            assertSame("third", target.getBean("value"));

            // Releasing a context other than the newest leaves the lookup context unchanged.
            target.onApplicationEvent(new ContextClosedEvent(second));
            assertSame("third", target.getBean("value"));

            // Destroying the newest context's bridge falls back to the newest one still registered.
            thirdBridge.destroy();
            assertSame("first", target.getBean("value"));

            secondBridge.destroy();
            assertSame("first", target.getBean("value"));

            firstBridge.destroy();
            assertNull(target.getBean("value"));
        } finally {
            target.setApplicationContext(null);
        }
    }

    private void setApplicationContext(final ApplicationContext context) throws Exception {
        final Field field = SpringApplicationContext.class.getDeclaredField("appContext");
        field.setAccessible(true);
        field.set(target, context);
    }
}
