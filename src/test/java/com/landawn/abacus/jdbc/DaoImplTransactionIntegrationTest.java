package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.Table;
import com.landawn.abacus.jdbc.annotation.PerfLog;
import com.landawn.abacus.jdbc.annotation.SqlLogEnabled;
import com.landawn.abacus.jdbc.annotation.Transactional;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.util.Throwables;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DaoImplTransactionIntegrationTest extends TestBase {

    private final JdbcDataSource dataSource = new JdbcDataSource();
    private final Map<Logging, TransactionDao<?>> daos = new EnumMap<>(Logging.class);
    private Connection keeper;
    private SqlLogConfig previousSqlLog;
    private SqlLogConfig previousPerfLog;

    @BeforeAll
    void createDatabase() throws SQLException {
        dataSource.setURL("jdbc:h2:mem:dao_transaction_" + UUID.randomUUID());
        keeper = dataSource.getConnection();
        try (Statement statement = keeper.createStatement()) {
            statement.execute("CREATE TABLE transaction_record (id BIGINT PRIMARY KEY)");
        }
        daos.put(Logging.NONE, JdbcUtil.createDao(PlainDao.class, dataSource));
        daos.put(Logging.SQL, JdbcUtil.createDao(SqlLoggingDao.class, dataSource));
        daos.put(Logging.PERFORMANCE, JdbcUtil.createDao(PerfLoggingDao.class, dataSource));
        daos.put(Logging.BOTH, JdbcUtil.createDao(BothLoggingDao.class, dataSource));
    }

    @BeforeEach
    void resetRowsAndLogging() throws SQLException {
        try (Statement statement = keeper.createStatement()) {
            statement.executeUpdate("DELETE FROM transaction_record");
        }
        previousSqlLog = JdbcUtil.isSQLLogEnabled_TL.get();
        previousPerfLog = JdbcUtil.sqlPerfLogThresholdMillis_TL.get();
        JdbcUtil.isSQLLogEnabled_TL.set(new SqlLogConfig(false, 137));
        JdbcUtil.sqlPerfLogThresholdMillis_TL.set(new SqlLogConfig(91L, 149));
    }

    @AfterEach
    void restoreLogging() {
        JdbcUtil.isSQLLogEnabled_TL.set(previousSqlLog);
        JdbcUtil.sqlPerfLogThresholdMillis_TL.set(previousPerfLog);
    }

    @AfterAll
    void closeDatabase() throws SQLException {
        if (keeper != null) {
            keeper.close();
        }
    }

    @ParameterizedTest(name = "{0}, logging={1}, outer={2}, failure={3}")
    @MethodSource("transactionCases")
    void transactional_propagation_preservesCommitBoundariesAndCallerContext(final Propagation propagation, final Logging logging, final boolean outerActive,
            final boolean fail) throws SQLException {
        final TransactionDao<?> dao = daos.get(logging);
        final SqlTransaction outer = outerActive ? JdbcUtil.beginTransaction(dataSource) : null;
        final Connection outerConnection = outer == null ? null : outer.connection();
        final boolean rejected = propagation == Propagation.MANDATORY && !outerActive || propagation == Propagation.NEVER && outerActive;
        final boolean joinsOuter = outerActive
                && (propagation == Propagation.REQUIRED || propagation == Propagation.MANDATORY || propagation == Propagation.SUPPORTS);
        final boolean transactional = joinsOuter || propagation == Propagation.REQUIRED || propagation == Propagation.REQUIRES_NEW;
        final SQLException failure = new SQLException("work failed after insert");
        final AtomicBoolean invoked = new AtomicBoolean();
        final Throwables.Supplier<Integer, SQLException> work = () -> {
            invoked.set(true);
            assertEquals(transactional, JdbcUtil.isInTransaction(dataSource));
            assertEquals(logging == Logging.SQL || logging == Logging.BOTH, JdbcUtil.isSQLLogEnabled_TL.get().isEnabled);
            assertEquals(logging == Logging.SQL || logging == Logging.BOTH ? 321 : 137, JdbcUtil.isSQLLogEnabled_TL.get().maxSqlLogLength);
            assertEquals(logging == Logging.PERFORMANCE || logging == Logging.BOTH ? 1234L : 91L,
                    JdbcUtil.sqlPerfLogThresholdMillis_TL.get().sqlPerfLogThresholdMillis);

            try (PreparedQuery query = JdbcUtil.prepareQuery(dataSource, "INSERT INTO transaction_record (id) VALUES (1)")) {
                query.configureStatement(statement -> {
                    assertEquals(!transactional, statement.getConnection().getAutoCommit());
                    if (joinsOuter) {
                        assertSame(outerConnection, statement.getConnection());
                    } else if (outerActive) {
                        assertNotSame(outerConnection, statement.getConnection());
                    }
                });
                assertEquals(1, query.update());
            }
            if (fail) {
                throw failure;
            }
            return 42;
        };

        try {
            if (rejected) {
                final IllegalStateException error = assertThrows(IllegalStateException.class, () -> invoke(dao, propagation, work));
                assertTrue(error.getMessage().contains(propagation.name()));
                assertFalse(invoked.get());
            } else if (fail) {
                assertSame(failure, assertThrows(SQLException.class, () -> invoke(dao, propagation, work)));
            } else {
                assertEquals(42, invoke(dao, propagation, work));
                assertTrue(invoked.get());
            }

            assertEquals(outerActive, JdbcUtil.isInTransaction(dataSource));
            if (outerActive) {
                try (PreparedQuery query = JdbcUtil.prepareQuery(dataSource, "SELECT 1")) {
                    query.configureStatement(statement -> assertSame(outerConnection, statement.getConnection()));
                    assertEquals(1, query.queryForInt().get());
                }
            }
            assertFalse(JdbcUtil.isSQLLogEnabled_TL.get().isEnabled);
            assertEquals(137, JdbcUtil.isSQLLogEnabled_TL.get().maxSqlLogLength);
            assertEquals(91L, JdbcUtil.sqlPerfLogThresholdMillis_TL.get().sqlPerfLogThresholdMillis);
            assertEquals(149, JdbcUtil.sqlPerfLogThresholdMillis_TL.get().maxSqlLogLength);
        } finally {
            if (outer != null) {
                outer.rollbackIfNotCommitted();
            }
        }

        assertFalse(JdbcUtil.isInTransaction(dataSource));
        final boolean persisted = !rejected && !joinsOuter && (!transactional || !fail);
        try (Statement statement = keeper.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM transaction_record")) {
            assertTrue(resultSet.next());
            assertEquals(persisted ? 1 : 0, resultSet.getInt(1));
        }
    }

    private static Stream<Arguments> transactionCases() {
        return Stream.of(Propagation.values())
                .flatMap(propagation -> Stream.of(Logging.values())
                        .flatMap(logging -> Stream.of(false, true)
                                .flatMap(outer -> Stream.of(false, true).map(fail -> Arguments.of(propagation, logging, outer, fail)))));
    }

    private static int invoke(final TransactionDao<?> dao, final Propagation propagation, final Throwables.Supplier<Integer, SQLException> work)
            throws SQLException {
        return switch (propagation) {
            case REQUIRED -> dao.required(work);
            case MANDATORY -> dao.mandatory(work);
            case SUPPORTS -> dao.supports(work);
            case REQUIRES_NEW -> dao.requiresNew(work);
            case NOT_SUPPORTED -> dao.notSupported(work);
            case NEVER -> dao.never(work);
        };
    }

    private enum Logging {
        NONE, SQL, PERFORMANCE, BOTH
    }

    interface TransactionDao<D extends TransactionDao<D>> extends CrudDao<Record, Long, D> {
        @Transactional(propagation = Propagation.REQUIRED)
        default int required(final Throwables.Supplier<Integer, SQLException> work) throws SQLException {
            return work.get();
        }

        @Transactional(propagation = Propagation.MANDATORY)
        default int mandatory(final Throwables.Supplier<Integer, SQLException> work) throws SQLException {
            return work.get();
        }

        @Transactional(propagation = Propagation.SUPPORTS)
        default int supports(final Throwables.Supplier<Integer, SQLException> work) throws SQLException {
            return work.get();
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        default int requiresNew(final Throwables.Supplier<Integer, SQLException> work) throws SQLException {
            return work.get();
        }

        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        default int notSupported(final Throwables.Supplier<Integer, SQLException> work) throws SQLException {
            return work.get();
        }

        @Transactional(propagation = Propagation.NEVER)
        default int never(final Throwables.Supplier<Integer, SQLException> work) throws SQLException {
            return work.get();
        }
    }

    interface PlainDao extends TransactionDao<PlainDao> {
    }

    @SqlLogEnabled(value = true, maxSqlLogLength = 321)
    interface SqlLoggingDao extends TransactionDao<SqlLoggingDao> {
    }

    @PerfLog(sqlPerfLogThresholdMillis = 1234L)
    interface PerfLoggingDao extends TransactionDao<PerfLoggingDao> {
    }

    @SqlLogEnabled(value = true, maxSqlLogLength = 321)
    @PerfLog(sqlPerfLogThresholdMillis = 1234L)
    interface BothLoggingDao extends TransactionDao<BothLoggingDao> {
    }

    @Table("transaction_record")
    public static class Record {
        @Id
        private Long id;

        public Long getId() {
            return id;
        }

        public void setId(final Long id) {
            this.id = id;
        }
    }
}
