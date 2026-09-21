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
import org.springframework.context.ApplicationContext;
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

    private void setApplicationContext(final ApplicationContext context) throws Exception {
        final Field field = SpringApplicationContext.class.getDeclaredField("appContext");
        field.setAccessible(true);
        field.set(target, context);
    }
}
