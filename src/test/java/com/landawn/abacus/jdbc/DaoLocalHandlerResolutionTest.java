package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.landawn.abacus.jdbc.dao.NonUpdateCrudDao;
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.Tuple.Tuple3;

@Tag("2025")
class DaoLocalHandlerResolutionTest {
    @AfterEach
    void clearContext() {
        new SpringApplicationContext().setApplicationContext(null);
    }

    @Test
    void daoLocalHandlerDoesNotCreateAnUnusedSpringPrototype() {
        final AtomicInteger created = new AtomicInteger();
        LocalHandlerDao.DAO_LOCAL_LIFECYCLE_HANDLER.invocations.set(0);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
             com.zaxxer.hikari.HikariDataSource dataSource = (com.zaxxer.hikari.HikariDataSource) JdbcUtil
                     .createHikariDataSource("jdbc:h2:mem:local_handler_review", "sa", "")) {
            context.registerBean(SpringApplicationContext.class);
            context.registerBean("DAO_LOCAL_LIFECYCLE_HANDLER", PrototypeHandler.class, () -> {
                created.incrementAndGet();
                return new PrototypeHandler();
            }, definition -> definition.setScope(ConfigurableBeanFactory.SCOPE_PROTOTYPE));
            context.refresh();

            final LocalHandlerDao dao = JdbcUtil.createDao(LocalHandlerDao.class, dataSource);
            assertEquals("ok", dao.getMessage());
            assertEquals(1, LocalHandlerDao.DAO_LOCAL_LIFECYCLE_HANDLER.invocations.get());
            assertEquals(0, created.get());
        }
    }

    public interface LocalHandlerDao extends NonUpdateCrudDao<DaoImplIntegrationTest.UserAccount, Long, LocalHandlerDao> {
        LocalHandler DAO_LOCAL_LIFECYCLE_HANDLER = new LocalHandler();

        @com.landawn.abacus.jdbc.annotation.Handler(qualifier = "DAO_LOCAL_LIFECYCLE_HANDLER")
        default String getMessage() {
            return "ok";
        }
    }

    public static final class LocalHandler implements Jdbc.Handler<LocalHandlerDao> {
        final AtomicInteger invocations = new AtomicInteger();

        @Override
        public void beforeInvoke(final LocalHandlerDao proxy, final Object[] args, final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            invocations.incrementAndGet();
        }
    }

    public static final class PrototypeHandler implements Jdbc.Handler<Object> {
    }
}
