package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.jdbc.annotation.Handler;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.TestBase;
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.Tuple.Tuple3;

class DaoImplHandlerTest extends TestBase {
    private HandlerDao dao;
    private DataSource dataSource;

    @BeforeEach
    void createDao() throws SQLException {
        dataSource = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("H2");
        when(metadata.getDatabaseProductVersion()).thenReturn("2");
        dao = JdbcUtil.createDao(HandlerDao.class, dataSource);
    }

    @Test
    void nestedInvocationSkipsExternalOnlyHandlerAndUnwindsInReverseOrder() {
        final Invocation invocation = new Invocation();

        assertEquals("ok", dao.outer(invocation));
        assertEquals(
                List.of("first.before.outer", "external.before.outer", "last.before.outer", "body.outer", "first.before.inner", "last.before.inner",
                        "body.inner", "last.after.inner:ok", "first.after.inner:ok", "last.after.outer:ok", "external.after.outer:ok", "first.after.outer:ok"),
                invocation.events);
    }

    @ParameterizedTest
    @CsvSource({ "first,false", "first,true", "last,false", "last,true" })
    void beforeFailureUnwindsOnlyHandlersWhoseBeforeSucceeded(final String failingHandler, final boolean nested) {
        final Invocation invocation = new Invocation();
        invocation.beforeFailureHandler = failingHandler;
        invocation.primary = new IllegalStateException("before failed");

        assertSame(invocation.primary, assertThrows(IllegalStateException.class, () -> invoke(nested, invocation)));
        assertFalse(invocation.events.contains("body.inner"));
        assertFalse(invocation.events.contains("last.after.inner:null"));
        assertEquals(failingHandler.equals("last"), invocation.events.contains("first.after.inner:null"));
        if (nested) {
            assertEquals(List.of("last.after.outer:null", "external.after.outer:null", "first.after.outer:null"),
                    invocation.events.subList(invocation.events.size() - 3, invocation.events.size()));
        } else {
            assertEquals(failingHandler.equals("last"), invocation.events.contains("external.after.inner:null"));
        }
        assertExternalHandlerRestored();
    }

    @ParameterizedTest(name = "methodFailure={0}, sharedCleanupFailure={1}")
    @CsvSource({ "false,false,false", "false,false,true", "false,true,false", "false,true,true", "true,false,false", "true,false,true", "true,true,false",
            "true,true,true" })
    void afterFailuresKeepPrimaryAndSuppressDistinctCleanupFailures(final boolean methodFailure, final boolean sharedCleanupFailure, final boolean nested) {
        final Invocation invocation = new Invocation();
        invocation.primary = methodFailure ? new IllegalStateException("method failed") : null;
        invocation.lastAfterFailure = new IllegalStateException("last cleanup failed");
        invocation.firstAfterFailure = sharedCleanupFailure ? invocation.lastAfterFailure : new IllegalStateException("first cleanup failed");

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> invoke(nested, invocation));

        assertSame(methodFailure ? invocation.primary : invocation.lastAfterFailure, thrown);
        assertArrayEquals(methodFailure ? new Throwable[] { invocation.lastAfterFailure }
                : sharedCleanupFailure ? new Throwable[0] : new Throwable[] { invocation.firstAfterFailure }, thrown.getSuppressed());
        assertArrayEquals(sharedCleanupFailure ? new Throwable[0] : new Throwable[] { invocation.firstAfterFailure },
                invocation.lastAfterFailure.getSuppressed());
        final String result = methodFailure ? "null" : "ok";
        assertTrue(invocation.events.contains("last.after.inner:" + result));
        assertTrue(invocation.events.contains("first.after.inner:" + result));
        assertExternalHandlerRestored();
    }

    @Test
    void nestedBeforeFailureRetainsCleanupFailure() {
        final Invocation invocation = new Invocation();
        invocation.beforeFailureHandler = "last";
        invocation.primary = new IllegalStateException("before failed");
        invocation.firstAfterFailure = new IllegalStateException("cleanup failed");

        assertSame(invocation.primary, assertThrows(IllegalStateException.class, () -> dao.outer(invocation)));
        assertArrayEquals(new Throwable[] { invocation.firstAfterFailure }, invocation.primary.getSuppressed());
        assertExternalHandlerRestored();
    }

    @Test
    void daoObjectMethodsUseProxyIdentity() {
        final HandlerDao other = JdbcUtil.createDao(HandlerDao.class, dataSource,
                JdbcUtil.DaoCreationOptions.builder().targetTableName("another_entity").build());

        assertTrue(dao.equals(dao));
        assertFalse(dao.equals(other));
        assertFalse(dao.equals(null));
        assertFalse(dao.equals("unrelated"));
        assertEquals(System.identityHashCode(dao), dao.hashCode());
        assertEquals("HandlerDaoProxy@" + Integer.toHexString(System.identityHashCode(dao)), dao.toString());
    }

    private String invoke(final boolean nested, final Invocation invocation) {
        return nested ? dao.outer(invocation) : dao.inner(invocation);
    }

    private void assertExternalHandlerRestored() {
        final Invocation subsequent = new Invocation();
        assertEquals("ok", dao.inner(subsequent));
        assertEquals(List.of("first.before.inner", "external.before.inner", "last.before.inner", "body.inner", "last.after.inner:ok", "external.after.inner:ok",
                "first.after.inner:ok"), subsequent.events);
    }

    public static final class Invocation {
        final List<String> events = new ArrayList<>();
        String beforeFailureHandler;
        IllegalStateException primary;
        IllegalStateException firstAfterFailure;
        IllegalStateException lastAfterFailure;
    }

    @Handler(qualifier = "FIRST", filter = { "outer", "inner" })
    @Handler(qualifier = "EXTERNAL", filter = { "outer", "inner" }, externalCallsOnly = true)
    @Handler(qualifier = "LAST", filter = { "outer", "inner" })
    public interface HandlerDao extends CrudDao<Entity, Long, HandlerDao> {
        RecordingHandler FIRST = new RecordingHandler("first");
        RecordingHandler EXTERNAL = new RecordingHandler("external");
        RecordingHandler LAST = new RecordingHandler("last");

        default String outer(final Invocation invocation) {
            invocation.events.add("body.outer");
            return inner(invocation);
        }

        default String inner(final Invocation invocation) {
            invocation.events.add("body.inner");
            if (invocation.primary != null) {
                throw invocation.primary;
            }
            return "ok";
        }
    }

    public static final class RecordingHandler implements Jdbc.Handler<HandlerDao> {
        private final String name;

        RecordingHandler(final String name) {
            this.name = name;
        }

        @Override
        public void beforeInvoke(final HandlerDao proxy, final Object[] args, final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> signature) {
            final Invocation invocation = (Invocation) args[0];
            final String method = signature._1.getName();
            invocation.events.add(name + ".before." + method);
            if (method.equals("inner") && name.equals(invocation.beforeFailureHandler)) {
                throw invocation.primary;
            }
        }

        @Override
        public void afterInvoke(final Object result, final HandlerDao proxy, final Object[] args,
                final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> signature) {
            final Invocation invocation = (Invocation) args[0];
            final String method = signature._1.getName();
            invocation.events.add(name + ".after." + method + ":" + result);
            if (method.equals("inner")) {
                final IllegalStateException failure = name.equals("first") ? invocation.firstAfterFailure
                        : name.equals("last") ? invocation.lastAfterFailure : null;
                if (failure != null) {
                    throw failure;
                }
            }
        }
    }

    @lombok.Data
    public static class Entity {
        @Id
        private Long id;
    }
}
