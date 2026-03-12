package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import com.landawn.abacus.TestBase;

public class SpringApplicationContextTest extends TestBase {

    private SpringApplicationContext target;
    private ApplicationContext appContext;

    @BeforeEach
    public void setUp() throws Exception {
        target = new SpringApplicationContext();
        appContext = mock(ApplicationContext.class);
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

    private void setApplicationContext(final ApplicationContext context) throws Exception {
        final Field field = SpringApplicationContext.class.getDeclaredField("appContext");
        field.setAccessible(true);
        field.set(target, context);
    }
}
