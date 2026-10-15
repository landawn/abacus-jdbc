package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.Map;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

@Tag("integration")
class DBLockIntegrationTest extends TestBase {

    @Test
    void refreshTask_liveDatabase_renewsLeaseAndEvictsMissingRows() throws Exception {
        final JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:scheduled_lock_" + System.nanoTime());
        final CountDownLatch refreshed = new CountDownLatch(1);
        final DataSource observed = mock(DataSource.class);
        when(observed.getConnection()).thenAnswer(call -> observeRefreshes(database.getConnection(), refreshed));

        try (Connection observer = database.getConnection();
             DBLock lock = new DBLock(observed, "refresh_locks")) {
            assertNotNull(lock.tryLock("resource", 60_000L, 0L));
            JdbcUtil.executeUpdate(observer, "UPDATE refresh_locks SET expiry_time = TIMESTAMP '2000-01-01 00:00:00'");

            assertTrue(refreshed.await(5, TimeUnit.SECONDS), "The scheduled task must refresh the lock");
            assertEquals(1,
                    JdbcUtil.prepareQuery(observer, "SELECT COUNT(*) FROM refresh_locks WHERE expiry_time > CURRENT_TIMESTAMP").queryForInt().orElse(-1));

            // Observe the scheduler once, then exercise eviction without racing another scheduled refresh.
            ((ScheduledFuture<?>) field(lock, "scheduledFuture")).cancel(false);
            JdbcUtil.executeUpdate(observer, "DELETE FROM refresh_locks WHERE target = ?", "resource");
            lock.refreshLocks();
            assertTrue(((Map<?, ?>) field(lock, "targetCodePool")).isEmpty());
        }
    }

    @Test
    public void testLockLifecycleIsIndependentOfAmbientTransaction() throws Exception {
        final org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:independent_lock_" + System.nanoTime());

        try (Connection observer = ds.getConnection();
             DBLock lock = new DBLock(ds, "independent_locks")) {
            JdbcUtil.executeUpdate(observer, "CREATE TABLE business_data(id INT)");
            final String code;

            try (SqlTransaction transaction = JdbcUtil.beginTransaction(ds)) {
                JdbcUtil.executeUpdate(ds, "INSERT INTO business_data VALUES (1)");
                code = lock.tryLock("resource", 60_000, 0);
                assertNotNull(code);
                assertEquals(1, JdbcUtil.prepareQuery(observer, "SELECT COUNT(*) FROM independent_locks").queryForInt().orElse(-1));
                assertEquals(0, JdbcUtil.prepareQuery(observer, "SELECT COUNT(*) FROM business_data").queryForInt().orElse(-1));
            }

            assertEquals(1, JdbcUtil.prepareQuery(observer, "SELECT COUNT(*) FROM independent_locks").queryForInt().orElse(-1));
            assertEquals(0, JdbcUtil.prepareQuery(observer, "SELECT COUNT(*) FROM business_data").queryForInt().orElse(-1));

            try (SqlTransaction transaction = JdbcUtil.beginTransaction(ds)) {
                assertTrue(lock.unlock("resource", code));
                assertEquals(0, JdbcUtil.prepareQuery(observer, "SELECT COUNT(*) FROM independent_locks").queryForInt().orElse(-1));
            }

            assertEquals(0, JdbcUtil.prepareQuery(observer, "SELECT COUNT(*) FROM independent_locks").queryForInt().orElse(-1));
            assertNotNull(lock.tryLock("close-resource", 60_000, 0));

            try (SqlTransaction transaction = JdbcUtil.beginTransaction(ds)) {
                lock.close();
                assertEquals(0, JdbcUtil.prepareQuery(observer, "SELECT COUNT(*) FROM independent_locks").queryForInt().orElse(-1));
            }
        }
    }

    @Test
    public void testLockConnectionsEnableAutoCommitAndRefreshIndependently() throws Exception {
        final org.h2.jdbcx.JdbcDataSource database = new org.h2.jdbcx.JdbcDataSource();
        database.setURL("jdbc:h2:mem:manual_lock_" + System.nanoTime());
        final DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenAnswer(invocation -> {
            final Connection conn = database.getConnection();
            conn.setAutoCommit(false);
            return conn;
        });

        try (Connection observer = database.getConnection();
             DBLock lock = new DBLock(ds, "manual_locks")) {
            ((ScheduledFuture<?>) field(lock, "scheduledFuture")).cancel(false);
            final String code = lock.tryLock("resource", 60_000, 0);
            assertNotNull(code);
            assertEquals(1, JdbcUtil.prepareQuery(observer, "SELECT COUNT(*) FROM manual_locks").queryForInt().orElse(-1));

            JdbcUtil.executeUpdate(observer, "UPDATE manual_locks SET expiry_time = TIMESTAMP '2000-01-01 00:00:00'");
            try (SqlTransaction transaction = JdbcUtil.beginTransaction(ds)) {
                lock.refreshLocks();
                assertEquals(1,
                        JdbcUtil.prepareQuery(observer, "SELECT COUNT(*) FROM manual_locks WHERE expiry_time > CURRENT_TIMESTAMP").queryForInt().orElse(-1));
            }

            assertTrue(lock.unlock("resource", code));
            assertEquals(0, JdbcUtil.prepareQuery(observer, "SELECT COUNT(*) FROM manual_locks").queryForInt().orElse(-1));
        }
    }

    private static Connection observeRefreshes(final Connection connection, final CountDownLatch refreshed) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class }, (proxy, method, args) -> {
            try {
                final Object result = method.invoke(connection, args);
                if (method.getName().equals("prepareStatement") && ((String) args[0]).startsWith("UPDATE ")) {
                    final PreparedStatement statement = (PreparedStatement) result;
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] { PreparedStatement.class },
                            (statementProxy, statementMethod, statementArgs) -> {
                                try {
                                    final Object value = statementMethod.invoke(statement, statementArgs);
                                    if (statementMethod.getName().equals("executeUpdate") && Integer.valueOf(1).equals(value)) {
                                        refreshed.countDown();
                                    }
                                    return value;
                                } catch (final InvocationTargetException failure) {
                                    throw failure.getCause();
                                }
                            });
                }
                return result;
            } catch (final InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
    }

    private static Object field(final Object target, final String name) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
