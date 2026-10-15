package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

@Tag("2025")
class SpringApplicationContextLifecycleTest {
    @AfterEach
    void resetSharedContext() {
        new SpringApplicationContext().setApplicationContext(null);
    }

    @Test
    void failedRefreshReleasesAnAutomaticallyInjectedContextWithoutACloseEvent() {
        final AtomicInteger closeEvents = new AtomicInteger();
        final SpringApplicationContext lookup = new SpringApplicationContext();

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.addApplicationListener(event -> {
                if (event instanceof ContextClosedEvent) {
                    closeEvents.incrementAndGet();
                }
            });
            context.registerBean("bridge", SpringApplicationContext.class);
            context.registerBean("value", Object.class, Object::new);
            context.registerBean("failure", Object.class, () -> {
                assertSame(context.getBean("value"), lookup.getBean("value"));
                throw new IllegalStateException("refresh failed after the bridge was injected");
            }, definition -> definition.setDependsOn("bridge"));

            assertThrows(BeanCreationException.class, context::refresh);
            assertEquals(0, closeEvents.get());
            assertNull(lookup.getBean("value"));
            assertNull(lookup.getBean(Object.class));
            assertNull(lookup.getBean("value", Object.class));
        }
    }

    @Test
    void failedRefreshDoesNotReleaseAContextInstalledByAnotherBridge() {
        try (AnnotationConfigApplicationContext failedContext = new AnnotationConfigApplicationContext();
             AnnotationConfigApplicationContext replacement = new AnnotationConfigApplicationContext()) {
            final Object replacementValue = new Object();
            replacement.registerBean("bridge", SpringApplicationContext.class);
            replacement.registerBean("value", Object.class, () -> replacementValue);
            failedContext.registerBean("bridge", SpringApplicationContext.class);
            failedContext.registerBean("failure", Object.class, () -> {
                replacement.refresh();
                throw new IllegalStateException("old refresh failed after its context was replaced");
            }, definition -> definition.setDependsOn("bridge"));

            assertThrows(BeanCreationException.class, failedContext::refresh);
            assertSame(replacementValue, new SpringApplicationContext().getBean("value"));
        }
    }

    @Test
    void repeatedDestructionIsHarmlessAndDoesNotReleaseALaterContext() {
        try (AnnotationConfigApplicationContext first = new AnnotationConfigApplicationContext();
             AnnotationConfigApplicationContext replacement = new AnnotationConfigApplicationContext()) {
            first.registerBean("value", Object.class, Object::new);
            first.refresh();
            final Object replacementValue = new Object();
            replacement.registerBean("value", Object.class, () -> replacementValue);
            replacement.refresh();

            final SpringApplicationContext bridge = new SpringApplicationContext();
            bridge.setApplicationContext(first);
            bridge.destroy();
            assertNull(new SpringApplicationContext().getBean("value"));
            bridge.destroy();
            assertNull(new SpringApplicationContext().getBean("value"));

            // A second destroy() after another bridge installed a live context must leave that context alone.
            new SpringApplicationContext().setApplicationContext(replacement);
            bridge.destroy();
            assertSame(replacementValue, new SpringApplicationContext().getBean("value"));
        }
    }
}
