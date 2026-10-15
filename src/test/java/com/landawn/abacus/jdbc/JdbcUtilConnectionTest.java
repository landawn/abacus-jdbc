package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

class JdbcUtilConnectionTest extends JdbcUtilTestSupport {

    @Test
    public void testHikariPoolSizesAreValidatedBeforePoolCreation() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> JdbcUtil.createHikariDataSource("jdbc:unsupported:test", null, null, -1, 0)).getMessage()
                .contains("minIdle"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> JdbcUtil.createHikariDataSource("jdbc:unsupported:test", null, null, 0, 0)).getMessage()
                .contains("maxPoolSize"));
    }

    @Test
    public void testCreateConnectionFailedRegistrationDoesNotPoisonDriverCache() {
        final String url = "jdbc:abacus-test-no-such-driver://localhost/db";

        // First attempt: the driver fails to instantiate (its constructor throws).
        final Exception first = assertThrows(Exception.class, () -> JdbcUtil.createConnection(CtorThrowingDriver.class, url, "u", "p"));
        assertTrue(causeChainContains(first, "ABACUS_TEST_DRIVER_CTOR_BOOM"));

        // Regression: a failed registration must NOT poison the driver-registration cache. The second attempt must
        // re-try registration (and fail in the constructor again), not silently skip registration and surface an
        // unrelated "No suitable driver" error.
        final Exception second = assertThrows(Exception.class, () -> JdbcUtil.createConnection(CtorThrowingDriver.class, url, "u", "p"));
        assertTrue(causeChainContains(second, "ABACUS_TEST_DRIVER_CTOR_BOOM"),
                "Second attempt should re-try driver registration, not skip it due to a poisoned cache");
    }

    // getDriverClassByUrl — unsupported URL throws IllegalArgumentException (lines 843-845).
    @Test
    public void testCreateConnection_UnsupportedUrl_Throws() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.createConnection("jdbc:unknownDB://localhost", "u", "p"));
    }

    // Regression: getDriverClassByUrl used bare substring matching, so a SQL Server URL whose
    // connection string happened to contain "oracle" (e.g., a database name) was routed to the
    // Oracle driver. The fix anchors each check to the `:<vendor>:` scheme token.
    //
    // SQL Server / Oracle drivers may not be on the test classpath, so we assert routing via the
    // class name that getDriverClassByUrl tried to load (surfaced in the exception message).
    @Test
    public void testGetDriverClassByUrl_VendorNameInDatabaseDoesNotConfuseMatcher() throws Exception {
        final java.lang.reflect.Method m = JdbcUtil.class.getDeclaredMethod("getDriverClassByUrl", String.class);
        m.setAccessible(true);

        // jdbc:sqlserver URL with database "oracle_archive" — pre-fix would route to Oracle.
        assertResolvesToVendorDriver(m, "jdbc:sqlserver://host:1433;Database=oracle_archive", "SQLServerDriver",
                "com.microsoft.sqlserver.jdbc.SQLServerDriver");

        // jdbc:sqlserver URL with database "mysql_audit" — pre-fix would route to MySQL.
        assertResolvesToVendorDriver(m, "jdbc:sqlserver://host:1433;Database=mysql_audit", "SQLServerDriver", "com.microsoft.sqlserver.jdbc.SQLServerDriver");

        // A property value can contain a complete vendor token; only the leading JDBC scheme identifies the driver.
        assertResolvesToVendorDriver(m, "jdbc:sqlserver://host:1433;applicationName=:mysql:", "SQLServerDriver",
                "com.microsoft.sqlserver.jdbc.SQLServerDriver");

        // Sanity: legitimate MySQL URL still resolves to the MySQL driver.
        assertResolvesToVendorDriver(m, "jdbc:mysql://host:3306/anything", "mysql", "com.mysql.cj.jdbc.Driver");

        // Sanity: legitimate PostgreSQL URL still resolves to the PostgreSQL driver.
        assertResolvesToVendorDriver(m, "jdbc:postgresql://host:5432/anything", "postgresql", "org.postgresql.Driver");
    }

    @Test
    public void testGetConnection() throws SQLException {
        Connection conn = JdbcUtil.getConnection(mockDataSource);
        assertNotNull(conn);
        assertEquals(mockConnection, conn);
    }

    @Test
    public void testReleaseConnection() throws SQLException {
        JdbcUtil.releaseConnection(mockConnection, mockDataSource);
        // Connection should be closed after release
        verify(mockConnection).close();

        // Releasing a null connection should be a safe no-op
        assertDoesNotThrow(() -> JdbcUtil.releaseConnection(null, mockDataSource));
    }

    //    @Test
    //    public void testGetDBSequenceWithConfig() {
    //        DBSequence seq = JdbcUtil.getDBSequence(mockDataSource, "seq_table", "seq_name", 1000L, 500);
    //        assertNotNull(seq);
    //    }

    @Test
    public void testCreateDBLock() {
        try (DBLock lock = JdbcUtil.createDBLock(mockDataSource, "lock_table");
             DBLock otherLock = JdbcUtil.createDBLock(mockDataSource, "other_lock_table")) {
            assertNotNull(lock);
            assertTrue(lock instanceof AutoCloseable, "DBLock should support try-with-resources");
            assertNotNull(otherLock);
            assertFalse(lock == otherLock, "different table names should yield different instances");
        }
    }

    /** A {@link java.sql.Driver} whose constructor always fails, used to exercise the registration-failure path. */
    public static final class CtorThrowingDriver implements java.sql.Driver {
        public CtorThrowingDriver() {
            throw new RuntimeException("ABACUS_TEST_DRIVER_CTOR_BOOM");
        }

        @Override
        public Connection connect(String url, java.util.Properties info) {
            return null;
        }

        @Override
        public boolean acceptsURL(String url) {
            return false;
        }

        @Override
        public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties info) {
            return new java.sql.DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 0;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return null;
        }
    }

    private static boolean causeChainContains(final Throwable t, final String marker) {
        Throwable cur = t;
        while (cur != null) {
            if (cur.getMessage() != null && cur.getMessage().contains(marker)) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static void assertResolvesToVendorDriver(final java.lang.reflect.Method m, final String url, final String expectedSubstring,
            final String fullClassName) throws Exception {
        try {
            Class<?> driverClass = (Class<?>) m.invoke(null, url);
            assertEquals(fullClassName, driverClass.getName(), "URL '" + url + "' should resolve to " + fullClassName);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            // Driver class not on test classpath — accept as long as the attempted class is the right vendor.
            final Throwable cause = ex.getCause();
            assertNotNull(cause);
            assertTrue(cause.getMessage() != null && cause.getMessage().contains(expectedSubstring),
                    "URL '" + url + "' should attempt to load a driver whose name contains '" + expectedSubstring + "' but got: " + cause.getMessage());
        }
    }
}
