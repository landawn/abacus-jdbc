package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.landawn.abacus.TestBase;

class SpringCodeGenerationOwnerTest extends TestBase {
    static Stream<Arguments> generators() {
        return Stream.of("select", "selectExcluded", "insert", "insertExcluded", "namedInsert", "namedInsertExcluded", "update", "updateKey",
                "updateExcluded", "namedUpdate", "namedUpdateKey", "namedUpdateExcluded")
                .flatMap(operation -> Stream.of("active", "commit", "rollback").map(phase -> Arguments.of(operation, phase)));
    }

    // These generators execute an empty SELECT to obtain column metadata. They must obey the same completion
    // boundary as ordinary queries even though their SQL is generated internally and does not change rows.
    @ParameterizedTest(name = "{0}, phase={1}")
    @MethodSource("generators")
    void columnLookupQueriesRespectSpringCompletion(final String operation, final String phase) throws Exception {
        final JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:code_generation_owner_" + UUID.randomUUID());
        try (Connection keeper = ds.getConnection(); Statement stmt = keeper.createStatement()) {
            stmt.executeUpdate("CREATE TABLE item(id INT PRIMARY KEY, note VARCHAR(30))");
            final DataSourceTransactionManager manager = new DataSourceTransactionManager(ds);
            SpringTransactionTracker.register(manager);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicBoolean checked = new AtomicBoolean();
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                final Connection connection = ((ConnectionHolder) TransactionSynchronizationManager.getResource(ds)).getConnection();
                final Runnable generate = () -> {
                    try {
                        if (phase.equals("active")) assertFalse(generate(connection, operation).isEmpty());
                        else assertThrows(IllegalStateException.class, () -> generate(connection, operation));
                        assertFalse(connection.isClosed(), "the caller still owns the connection");
                        checked.set(true);
                    } catch (final Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                };
                if (phase.equals("active")) generate.run();
                else TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { if (phase.equals("commit")) generate.run(); }
                    @Override public void afterCompletion(final int outcome) { if (phase.equals("rollback")) generate.run(); }
                });
                if (phase.equals("rollback")) status.setRollbackOnly();
            });
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertTrue(checked.get());
        }
    }

    private static String generate(final Connection connection, final String operation) {
        return switch (operation) {
            case "select" -> JdbcCodeGenerationUtil.generateSelectSql(connection, "item");
            case "selectExcluded" -> JdbcCodeGenerationUtil.generateSelectSql(connection, "item", List.of("note"), "id = ?");
            case "insert" -> JdbcCodeGenerationUtil.generateInsertSql(connection, "item");
            case "insertExcluded" -> JdbcCodeGenerationUtil.generateInsertSql(connection, "item", List.of("id"));
            case "namedInsert" -> JdbcCodeGenerationUtil.generateNamedInsertSql(connection, "item");
            case "namedInsertExcluded" -> JdbcCodeGenerationUtil.generateNamedInsertSql(connection, "item", List.of("id"));
            case "update" -> JdbcCodeGenerationUtil.generateUpdateSql(connection, "item");
            case "updateKey" -> JdbcCodeGenerationUtil.generateUpdateSql(connection, "item", "id");
            case "updateExcluded" -> JdbcCodeGenerationUtil.generateUpdateSql(connection, "item", List.of("id"), List.of("id"), "id = ?");
            case "namedUpdate" -> JdbcCodeGenerationUtil.generateNamedUpdateSql(connection, "item");
            case "namedUpdateKey" -> JdbcCodeGenerationUtil.generateNamedUpdateSql(connection, "item", "id");
            case "namedUpdateExcluded" -> JdbcCodeGenerationUtil.generateNamedUpdateSql(connection, "item", List.of("id"), List.of("id"), "id = :id");
            default -> throw new AssertionError(operation);
        };
    }
}
