package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class JdbcUtilBatchCleanupIntegrationTest extends JdbcUtilBatchCleanupTestSupport {

    @Test
    void successfulH2BatchesRemainReusableWhenTheDriverAlreadyClearedExecutedCommands() throws Exception {
        forEachBatchMode(largeBatch -> {
            for (final Throwable cleanup : new Throwable[] { new SQLException("clear failed"), new IllegalStateException("clear failed") }) {
                try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:");
                     Statement driver = connection.createStatement()) {
                    driver.execute("CREATE TABLE batch_cleanup (id INT PRIMARY KEY)");
                    final BatchFailureProxy intercepted = new BatchFailureProxy(driver, cleanup, null);
                    final Statement stmt = intercepted.statement;
                    stmt.addBatch("INSERT INTO batch_cleanup VALUES (1)");
                    stmt.addBatch("INSERT INTO batch_cleanup VALUES (2)");

                    final Object firstCounts = executeBatch(stmt, largeBatch);
                    assertSame(intercepted.lastBatchResult, firstCounts);
                    assertCounts(firstCounts, largeBatch, 2);
                    assertEquals(1, intercepted.cleanupAttempts);
                    assertEquals(List.of(1, 2), readIds(connection));
                    assertFalse(stmt.isClosed());

                    // H2 discards successfully executed commands itself. Reusing it here must not be read as
                    // a guarantee that an ignored cleanup failure empties every driver's pending batch.
                    stmt.addBatch("INSERT INTO batch_cleanup VALUES (3)");
                    final Object secondCounts = executeBatch(stmt, largeBatch);
                    assertSame(intercepted.lastBatchResult, secondCounts);
                    assertCounts(secondCounts, largeBatch, 1);
                    assertEquals(2, intercepted.cleanupAttempts);
                    assertEquals(List.of(1, 2, 3), readIds(connection));
                    assertFalse(stmt.isClosed());
                }
            }
        });
    }

    @Test
    void failedExecutionAndCleanupRequireExplicitClearingBeforeStatementReuse() throws Exception {
        forEachBatchMode(largeBatch -> {
            for (final Throwable cleanup : new Throwable[] { new SQLException("clear failed"), new IllegalStateException("clear failed") }) {
                try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:");
                     Statement driver = connection.createStatement()) {
                    driver.execute("CREATE TABLE batch_cleanup (id INT PRIMARY KEY)");
                    final SQLException primary = new SQLException("execution rejected before reaching the driver");
                    final BatchFailureProxy intercepted = new BatchFailureProxy(driver, cleanup, primary);
                    final Statement stmt = intercepted.statement;
                    stmt.addBatch("INSERT INTO batch_cleanup VALUES (1)");

                    assertSame(primary, assertThrows(SQLException.class, () -> executeBatch(stmt, largeBatch)));
                    assertArrayEquals(new Throwable[] { cleanup }, primary.getSuppressed());
                    assertEquals(1, intercepted.cleanupAttempts);
                    assertEquals(List.of(), readIds(connection));
                    assertFalse(stmt.isClosed());

                    // Both injected failures occurred before delegation, so the real H2 statement still has
                    // row 1 queued. Recover explicitly before a second batch; failed cleanup promises no reset.
                    stmt.clearBatch();
                    stmt.addBatch("INSERT INTO batch_cleanup VALUES (2)");
                    final Object counts = executeBatch(stmt, largeBatch);
                    assertSame(intercepted.lastBatchResult, counts);
                    assertCounts(counts, largeBatch, 1);
                    assertEquals(3, intercepted.cleanupAttempts);
                    assertEquals(List.of(2), readIds(connection));
                    assertFalse(stmt.isClosed());
                }
            }
        });
    }
}
