package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.Dataset;

class DataTransferSpringOwnershipTest extends TestBase {
    static Stream<Arguments> imports() {
        return Stream.of("dataset", "iterator", "csv", "copy")
                .flatMap(operation -> Stream.of(false, true).map(identityFailure -> Arguments.of(operation, identityFailure)));
    }

    // Empty input still constructs the binding adapter. Failure there must preserve both caller statements,
    // just as binding/execution failures do; successful query factories retain their separate owning policy.
    @ParameterizedTest(name = "{0}, identityFailure={1}")
    @MethodSource("imports")
    void captureFailureDoesNotCloseBorrowedStatements(final String operation, final boolean identityFailure) throws Exception {
        final JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:borrowed_statement_" + UUID.randomUUID());
        try (Connection keeper = ds.getConnection()) {
            JdbcUtil.executeUpdate(keeper, "CREATE TABLE item(id INT PRIMARY KEY)");
            final DataSourceTransactionManager manager = new DataSourceTransactionManager(ds);
            if (identityFailure) SpringTransactionTracker.register(manager);
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                final Connection connection = ((ConnectionHolder) TransactionSynchronizationManager.getResource(ds)).getConnection();
                try (PreparedStatement source = connection.prepareStatement("SELECT id FROM item");
                        PreparedStatement target = spy(connection.prepareStatement("INSERT INTO item VALUES (?)"))) {
                    final SQLException original = new SQLException("cannot read connection identity");
                    if (identityFailure) doThrow(original).when(target).getConnection();
                    final Class<? extends RuntimeException> failureType = identityFailure ? UncheckedSQLException.class : IllegalStateException.class;
                    final RuntimeException failure = assertThrows(failureType, () -> transfer(operation, source, target));
                    if (identityFailure) assertSame(original, failure.getCause());
                    assertEquals(0, failure.getSuppressed().length);
                    assertFalse(source.isClosed(), "source remains caller-owned");
                    assertFalse(target.isClosed(), "target remains caller-owned even if adapter construction fails");
                    assertFalse(connection.isClosed());
                    // Prove that preserving ownership means the caller can reuse the actual JDBC statement.
                    target.setInt(1, 1);
                    assertEquals(1, target.executeUpdate());
                    try (var rs = source.executeQuery()) { assertTrue(rs.next()); assertEquals(1, rs.getInt(1)); }
                } catch (final SQLException e) { throw new AssertionError(e); }
            });
        }
    }

    @SuppressWarnings("deprecation")
    private static void transfer(final String operation, final PreparedStatement source, final PreparedStatement target) throws SQLException {
        switch (operation) {
            case "dataset" -> DataTransferUtil.importData(Dataset.rows(List.of("id"), new Object[0][]), target, 10, 0,
                    (query, row) -> query.setInt(1, (Integer) row[0]));
            case "iterator" -> DataTransferUtil.importData(Collections.<Integer>emptyIterator(), target, 10, 0,
                    (query, id) -> query.setInt(1, id));
            case "csv" -> DataTransferUtil.importCsv(new StringReader("id\n"), row -> true, target, 10, 0,
                    (query, row) -> query.setInt(1, Integer.parseInt(row[0])));
            case "copy" -> DataTransferUtil.copy(source, target, 10, 0, (query, rs) -> query.setInt(1, rs.getInt(1)));
            default -> throw new AssertionError(operation);
        }
    }
}
