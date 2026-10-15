package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.util.function.TriConsumer;

abstract class JdbcUtilBatchCleanupTestSupport extends TestBase {

    protected Field handlerField;

    protected Object previousHandler;

    protected SqlLogConfig previousLogConfig;

    @BeforeEach
    void preserveLoggingConfiguration() throws ReflectiveOperationException {
        handlerField = JdbcUtil.class.getDeclaredField("_sqlLogHandler");
        handlerField.setAccessible(true);
        previousHandler = handlerField.get(null);
        previousLogConfig = JdbcUtil.sqlPerfLogThresholdMillis_TL.get();
        JdbcUtil.sqlPerfLogThresholdMillis_TL.set(new SqlLogConfig(-1, 128));
    }

    @AfterEach
    void restoreLoggingConfiguration() throws IllegalAccessException {
        handlerField.set(null, previousHandler);
        JdbcUtil.sqlPerfLogThresholdMillis_TL.set(previousLogConfig);
    }

    protected static void assertCounts(final Object counts, final boolean largeBatch, final int size) {
        if (largeBatch) {
            final long[] expected = new long[size];
            java.util.Arrays.fill(expected, 1L);
            assertArrayEquals(expected, (long[]) counts);
        } else {
            final int[] expected = new int[size];
            java.util.Arrays.fill(expected, 1);
            assertArrayEquals(expected, (int[]) counts);
        }
    }

    protected static List<Integer> readIds(final Connection connection) throws SQLException {
        final List<Integer> ids = new ArrayList<>();
        try (Statement query = connection.createStatement();
             ResultSet rows = query.executeQuery("SELECT id FROM batch_cleanup ORDER BY id")) {
            while (rows.next()) {
                ids.add(rows.getInt(1));
            }
        }
        return ids;
    }

    protected static final class BatchFailureProxy {
        protected final Statement delegate;
        protected final Throwable cleanupFailure;
        protected final Statement statement;
        protected Throwable executionFailure;
        protected Object lastBatchResult;
        protected int cleanupAttempts;

        protected BatchFailureProxy(final Statement delegate, final Throwable cleanupFailure, final Throwable executionFailure) {
            this.delegate = delegate;
            this.cleanupFailure = cleanupFailure;
            this.executionFailure = executionFailure;
            statement = (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] { Statement.class }, this::invoke);
        }

        protected Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
            if (method.getName().equals("clearBatch") && ++cleanupAttempts == 1) {
                throw cleanupFailure;
            }
            final boolean executingBatch = method.getName().equals("executeBatch") || method.getName().equals("executeLargeBatch");
            if (executingBatch && executionFailure != null) {
                final Throwable failure = executionFailure;
                executionFailure = null;
                throw failure;
            }
            try {
                final Object result = method.invoke(delegate, args);
                if (executingBatch) {
                    lastBatchResult = result;
                }
                return result;
            } catch (final InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    protected void forEachBatchMode(final BatchModeTest test) throws Exception {
        final TriConsumer<String, Long, Long> handler = (sql, start, end) -> {
        };
        for (final boolean logging : new boolean[] { false, true }) {
            handlerField.set(null, logging ? handler : null);
            for (final boolean largeBatch : new boolean[] { false, true }) {
                test.run(largeBatch);
            }
        }
    }

    protected static Object executeBatch(final Statement stmt, final boolean largeBatch) throws SQLException {
        return largeBatch ? JdbcUtil.executeLargeBatch(stmt) : JdbcUtil.executeBatch(stmt);
    }

    @FunctionalInterface
    protected interface BatchModeTest {
        void run(boolean largeBatch) throws Exception;
    }
}
