package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.util.stream.Stream;

/**
 * The close handler of a query prepared on a connection Spring bound to the thread (see {@code JdbcUtil.createCloseHandler}):
 * on the acquiring thread, the connection is handed back to Spring whatever the thread's Spring setting is at close time.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcUtilSpringCloseHandlerTest extends TestBase {

    private final JdbcDataSource dataSource = new JdbcDataSource();
    private Connection keeper;

    @BeforeAll
    void createDatabase() throws SQLException {
        dataSource.setURL("jdbc:h2:mem:spring_close_handler_" + UUID.randomUUID());
        keeper = dataSource.getConnection();

        try (Statement statement = keeper.createStatement()) {
            statement.execute("CREATE TABLE close_item (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(10))");
        }
    }

    @BeforeEach
    void resetRows() throws SQLException {
        try (Statement statement = keeper.createStatement()) {
            statement.executeUpdate("DELETE FROM close_item");
        }
    }

    @AfterAll
    void closeDatabase() throws SQLException {
        keeper.close();
    }

    // The handler knew at creation time that the connection was Spring's, yet released it through releaseConnection, which
    // re-reads the thread's Spring setting at close time: a query prepared inside the Spring transaction and closed inside
    // runIgnoringSpringTransaction on the same thread closed the Spring transaction's connection in the middle of the
    // transaction (the later statement failed and the commit had nothing to commit).
    @Test
    void queryClosedInsideIgnoringScope_leavesSpringsConnectionToTheTransaction() {
        transactionTemplate().executeWithoutResult(status -> {
            final Connection spring = DataSourceUtils.getConnection(dataSource);

            try {
                insert(spring, "before");
                final PreparedQuery query = JdbcUtil.prepareQuery(dataSource, "SELECT COUNT(*) FROM close_item");

                // queryForInt closes the query (closeAfterExecution) inside the ignoring scope; the count proves the query ran on
                // the transaction's connection (the uncommitted row is visible).
                JdbcUtil.runIgnoringSpringTransaction(() -> assertEquals(1, query.queryForInt().orElseThrow()));

                assertFalse(spring.isClosed());
                assertTrue(DataSourceUtils.isConnectionTransactional(spring, dataSource));
                insert(spring, "after");
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                DataSourceUtils.releaseConnection(spring, dataSource);
            }
        });

        assertEquals(2, countItems());
    }

    @Test
    void explicitCloseAndLazyStreamInsideIgnoringScope_leaveSpringsConnectionToTheTransaction() {
        transactionTemplate().executeWithoutResult(status -> {
            final Connection spring = DataSourceUtils.getConnection(dataSource);

            try {
                insert(spring, "before");
                final PreparedQuery query = JdbcUtil.prepareQuery(dataSource, "SELECT COUNT(*) FROM close_item").closeAfterExecution(false);
                assertEquals(1, query.queryForInt().orElseThrow());

                JdbcUtil.runIgnoringSpringTransaction(query::close);

                assertFalse(spring.isClosed());

                final Stream<Long> ids = JdbcUtil.prepareQuery(dataSource, "SELECT id FROM close_item").stream(Long.class);

                JdbcUtil.runIgnoringSpringTransaction(() -> {
                    try (ids) {
                        assertEquals(1, ids.count());
                    }
                });

                assertFalse(spring.isClosed());
                insert(spring, "after");
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                DataSourceUtils.releaseConnection(spring, dataSource);
            }
        });

        assertEquals(2, countItems());
    }

    // Guard: outside any Spring scope the handler still closes the connection it obtained, also inside the ignoring scope.
    @Test
    void queryOutsideSpringTransaction_closesItsOwnConnection() throws SQLException {
        final PreparedQuery query = JdbcUtil.prepareQuery(dataSource, "SELECT COUNT(*) FROM close_item").closeAfterExecution(false);
        final Connection own = query.stmt.getConnection();

        assertEquals(0, query.queryForInt().orElseThrow());
        JdbcUtil.runIgnoringSpringTransaction(query::close);

        assertTrue(own.isClosed());
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    private static void insert(final Connection connection, final String name) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO close_item (name) VALUES ('" + name + "')");
        }
    }

    private int countItems() {
        try (Statement statement = keeper.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM close_item")) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
