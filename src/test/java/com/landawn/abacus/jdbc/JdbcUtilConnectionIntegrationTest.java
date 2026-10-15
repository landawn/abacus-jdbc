package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class JdbcUtilConnectionIntegrationTest extends JdbcUtilTestSupport {

    @Test
    public void testCreateHikariDataSource() {
        String url = "jdbc:h2:mem:test";
        String user = "sa";
        String password = "";

        assertDataSourceCreation("com.zaxxer.hikari.HikariDataSource", () -> JdbcUtil.createHikariDataSource(url, user, password));
    }

    @Test
    public void testCreateHikariDataSourceWithPoolConfig() {
        String url = "jdbc:h2:mem:test";
        String user = "sa";
        String password = "";
        int minIdle = 5;
        int maxPoolSize = 20;

        assertDataSourceCreation("com.zaxxer.hikari.HikariDataSource", () -> JdbcUtil.createHikariDataSource(url, user, password, minIdle, maxPoolSize));
    }

    @Test
    public void testCreateC3p0DataSource() {
        String url = "jdbc:h2:mem:test";
        String user = "sa";
        String password = "";

        assertDataSourceCreation("com.mchange.v2.c3p0.ComboPooledDataSource", () -> JdbcUtil.createC3p0DataSource(url, user, password));
    }

    @Test
    public void testCreateC3p0DataSourceWithPoolConfig() {
        String url = "jdbc:h2:mem:test";
        String user = "sa";
        String password = "";
        int minPoolSize = 3;
        int maxPoolSize = 15;

        assertDataSourceCreation("com.mchange.v2.c3p0.ComboPooledDataSource",
                () -> JdbcUtil.createC3p0DataSource(url, user, password, minPoolSize, maxPoolSize));
    }

    @Test
    public void testC3p0NonPositiveMaxPoolSizeIsRejectedInsteadOfCreatingAPoolThatNeverHandsOutConnections() {
        // C3P0 itself accepts maxPoolSize <= 0; getConnection() on such a pool then blocks forever.
        assertTrue(assertThrows(IllegalArgumentException.class, () -> JdbcUtil.createC3p0DataSource("jdbc:h2:mem:c3p0_zero_max", "sa", "", 0, 0)).getMessage()
                .contains("maxPoolSize"));
        assertTrue(
                assertThrows(IllegalArgumentException.class, () -> JdbcUtil.createC3p0DataSource("jdbc:h2:mem:c3p0_negative_max", "sa", "", 1, -1)).getMessage()
                        .contains("maxPoolSize"));
    }

    @Test
    public void testCreateConnection() {
        String url = "jdbc:h2:mem:test";
        String user = "sa";
        String password = "";

        assertConnectionCreation(() -> JdbcUtil.createConnection(url, user, password));
    }

    @Test
    public void testCreateConnectionWithDriverClass() {
        String driverClass = "org.h2.Driver";
        String url = "jdbc:h2:mem:test";
        String user = "sa";
        String password = "";

        assertConnectionCreation(() -> JdbcUtil.createConnection(driverClass, url, user, password));
    }

    // BUG FIX: getDriverClassByUrl must not misclassify HSQLDB URLs whose database/host portion
    // contains the substring "h2" (e.g., "h2_compat") as H2. With the bug present, the H2 driver
    // would be selected for an HSQLDB URL and the connection attempt would fail because H2 does
    // not recognize "jdbc:hsqldb:..." URLs.
    @Test
    public void testCreateConnection_HsqldbUrlWithH2InDatabaseName_UsesHsqldbDriver() throws SQLException {
        try (Connection conn = JdbcUtil.createConnection("jdbc:hsqldb:mem:h2_compat_db", "sa", "")) {
            assertNotNull(conn);
            // HSQLDB reports its product name as "HSQL Database Engine"; the H2 driver would not
            // accept this URL at all, so reaching this point already proves the routing is correct.
            String productName = conn.getMetaData().getDatabaseProductName();
            assertTrue(productName != null && productName.toLowerCase().contains("hsql"), "Expected HSQLDB driver, but connected to: " + productName);
        }
    }

    private void assertDataSourceCreation(final String expectedClassName, final Supplier<DataSource> supplier) {
        final DataSource dataSource = assertDoesNotThrow(supplier::get);
        assertNotNull(dataSource);
        assertEquals(expectedClassName, dataSource.getClass().getName());

        final Connection connection = assertDoesNotThrow(() -> dataSource.getConnection());

        try {
            assertFalse(connection.isClosed());
            assertEquals("H2", connection.getMetaData().getDatabaseProductName());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            JdbcUtil.close(connection);
            closeIfPossible(dataSource);
        }
    }

    private void assertConnectionCreation(final Supplier<Connection> supplier) {
        final Connection connection = assertDoesNotThrow(supplier::get);
        assertNotNull(connection);

        try {
            assertFalse(connection.isClosed());
            assertEquals("H2", connection.getMetaData().getDatabaseProductName());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            JdbcUtil.close(connection);
        }
    }

    private void closeIfPossible(final Object candidate) {
        if (candidate instanceof AutoCloseable) {
            final AutoCloseable closeable = (AutoCloseable) candidate;
            assertDoesNotThrow(() -> closeable.close());
        }
    }
}
