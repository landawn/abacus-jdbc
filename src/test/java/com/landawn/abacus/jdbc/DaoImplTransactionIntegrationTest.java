package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumMap;
import java.util.Arrays;
import java.util.List;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.Table;
import com.landawn.abacus.jdbc.annotation.PerfLog;
import com.landawn.abacus.jdbc.annotation.Query;
import com.landawn.abacus.jdbc.annotation.SqlLogEnabled;
import com.landawn.abacus.jdbc.annotation.Transactional;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.util.Throwables;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DaoImplTransactionIntegrationTest extends TestBase {

    private final JdbcDataSource dataSource = new JdbcDataSource();
    private final Map<Logging, TransactionDao<?>> daos = new EnumMap<>(Logging.class);
    private GeneratedDao generatedDao;
    private AtomicDao atomicDao;
    private Connection keeper;
    private SqlLogConfig previousSqlLog;
    private SqlLogConfig previousPerfLog;

    @BeforeAll
    void createDatabase() throws SQLException {
        dataSource.setURL("jdbc:h2:mem:dao_transaction_" + UUID.randomUUID());
        keeper = dataSource.getConnection();
        try (Statement statement = keeper.createStatement()) {
            statement.execute("CREATE TABLE transaction_record (id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE generated_record (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(10))");
            statement.execute("CREATE TABLE atomic_record (id BIGINT PRIMARY KEY, name VARCHAR(3))");
            statement.execute("CREATE TABLE atomic_child (id BIGINT PRIMARY KEY, record_id BIGINT REFERENCES atomic_record (id))");
        }
        generatedDao = JdbcUtil.createDao(GeneratedDao.class, dataSource);
        atomicDao = JdbcUtil.createDao(AtomicDao.class, dataSource);
        daos.put(Logging.NONE, JdbcUtil.createDao(PlainDao.class, dataSource));
        daos.put(Logging.SQL, JdbcUtil.createDao(SqlLoggingDao.class, dataSource));
        daos.put(Logging.PERFORMANCE, JdbcUtil.createDao(PerfLoggingDao.class, dataSource));
        daos.put(Logging.BOTH, JdbcUtil.createDao(BothLoggingDao.class, dataSource));
    }

    @BeforeEach
    void resetRowsAndLogging() throws SQLException {
        try (Statement statement = keeper.createStatement()) {
            statement.executeUpdate("DELETE FROM transaction_record");
            statement.executeUpdate("DELETE FROM generated_record");
            statement.executeUpdate("DELETE FROM atomic_child");
            statement.executeUpdate("DELETE FROM atomic_record");
            statement.executeUpdate("INSERT INTO atomic_record (id, name) VALUES (1, 'a'), (2, 'b')");
            statement.executeUpdate("INSERT INTO atomic_child (id, record_id) VALUES (1, 2)");
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

    // REQUIRED/MANDATORY used to open a SqlTransaction scope on Spring's transaction-bound connection and commit it when the
    // DAO method returned, so a later Spring rollback could no longer undo the DAO method's work.
    @ParameterizedTest(name = "{0}, logging={1}")
    @MethodSource("springJoinCases")
    void transactional_requiredOrMandatory_joinsSpringManagedTransactionWithoutCommittingIt(final Propagation propagation, final Logging logging)
            throws SQLException {
        final TransactionDao<?> dao = daos.get(logging);
        final org.springframework.transaction.support.TransactionTemplate template = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));

        for (final boolean springRollsBack : new boolean[] { true, false }) {
            template.executeWithoutResult(status -> {
                final Connection springConnection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);

                try {
                    assertEquals(42, invoke(dao, propagation, () -> {
                        try (PreparedQuery query = JdbcUtil.prepareQuery(dataSource, "INSERT INTO transaction_record (id) VALUES (1)")) {
                            query.configureStatement(statement -> {
                                assertSame(springConnection, statement.getConnection());
                                assertFalse(statement.getConnection().getAutoCommit());
                            });
                            assertEquals(1, query.update());
                        }
                        return 42;
                    }));

                    assertFalse(springConnection.getAutoCommit());
                } catch (final SQLException e) {
                    throw new IllegalStateException(e);
                } finally {
                    org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(springConnection, dataSource);
                }

                if (springRollsBack) {
                    status.setRollbackOnly();
                }
            });

            assertFalse(JdbcUtil.isInTransaction(dataSource));

            try (Statement statement = keeper.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM transaction_record")) {
                assertTrue(resultSet.next());
                assertEquals(springRollsBack ? 0 : 1, resultSet.getInt(1), "Spring " + (springRollsBack ? "rollback" : "commit") + " governs the work");
            }
        }

        assertFalse(JdbcUtil.isSQLLogEnabled_TL.get().isEnabled);
        assertEquals(91L, JdbcUtil.sqlPerfLogThresholdMillis_TL.get().sqlPerfLogThresholdMillis);
    }

    // A non-transactional Spring scope (SUPPORTS with synchronization) binds an auto-commit connection: REQUIRED must still
    // run in a scope of its own there, so a failure rolls back the method's work instead of leaving it auto-committed.
    @ParameterizedTest(name = "logging={0}")
    @MethodSource("springSynchronizedLoggingCases")
    void transactional_required_keepsOwnScopeInNonTransactionalSpringScope(final Logging logging) {
        final TransactionDao<?> dao = daos.get(logging);
        final org.springframework.transaction.support.TransactionTemplate template = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_SUPPORTS);
        final SQLException failure = new SQLException("work failed after insert");

        template.executeWithoutResult(status -> {
            final Connection boundConnection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);

            try {
                assertTrue(boundConnection.getAutoCommit());
                assertSame(failure, assertThrows(SQLException.class, () -> dao.required(() -> {
                    try (PreparedQuery query = JdbcUtil.prepareQuery(dataSource, "INSERT INTO transaction_record (id) VALUES (1)")) {
                        query.configureStatement(statement -> assertFalse(statement.getConnection().getAutoCommit()));
                        assertEquals(1, query.update());
                    }
                    throw failure;
                })));
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(boundConnection, dataSource);
            }
        });

        try (Statement statement = keeper.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM transaction_record")) {
            assertTrue(resultSet.next());
            assertEquals(0, resultSet.getInt(1));
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // A failure inside a method that joined a Spring-managed transaction propagates without completing that transaction, and
    // marks it rollback-only. The old outermost SqlTransaction scope rolled back Spring's connection instead, discarding the work
    // Spring had done before the call, and Spring then committed whatever followed; letting Spring commit the failed method's
    // partial work is not right either: Spring's commit now rolls everything back (UnexpectedRollbackException).
    @ParameterizedTest(name = "{0}, logging={1}")
    @MethodSource("springJoinCases")
    void transactional_requiredOrMandatory_failureInSpringManagedTransactionMarksItRollbackOnly(final Propagation propagation, final Logging logging)
            throws SQLException {
        final TransactionDao<?> dao = daos.get(logging);
        final org.springframework.transaction.support.TransactionTemplate template = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        final SQLException failure = new SQLException("work failed after insert");

        assertThrows(org.springframework.transaction.UnexpectedRollbackException.class, () -> template.executeWithoutResult(status -> {
            final Connection springConnection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);

            try {
                try (Statement statement = springConnection.createStatement()) {
                    assertEquals(1, statement.executeUpdate("INSERT INTO transaction_record (id) VALUES (1)"));
                }

                assertSame(failure, assertThrows(SQLException.class, () -> invoke(dao, propagation, () -> {
                    try (PreparedQuery query = JdbcUtil.prepareQuery(dataSource, "INSERT INTO transaction_record (id) VALUES (2)")) {
                        query.configureStatement(statement -> assertSame(springConnection, statement.getConnection()));
                        assertEquals(1, query.update());
                    }
                    throw failure;
                })));

                // Neither rolled back nor committed by the DAO method: both rows are still pending in Spring's transaction.
                assertFalse(springConnection.getAutoCommit());

                try (Statement statement = springConnection.createStatement();
                     ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM transaction_record")) {
                    assertTrue(resultSet.next());
                    assertEquals(2, resultSet.getInt(1));
                }

                try (Statement statement = keeper.createStatement();
                     ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM transaction_record")) {
                    assertTrue(resultSet.next());
                    assertEquals(0, resultSet.getInt(1));
                }

                assertTrue(status.isRollbackOnly());
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(springConnection, dataSource);
            }
        }));

        assertFalse(JdbcUtil.isInTransaction(dataSource));

        // The caller handled the failure and tried to let Spring commit: Spring rolled back instead, the work before the call too.
        try (Statement statement = keeper.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM transaction_record")) {
            assertTrue(resultSet.next());
            assertEquals(0, resultSet.getInt(1));
        }

        assertFalse(JdbcUtil.isSQLLogEnabled_TL.get().isEnabled);
        assertEquals(91L, JdbcUtil.sqlPerfLogThresholdMillis_TL.get().sqlPerfLogThresholdMillis);
    }

    // A JdbcUtil transaction opened inside a Spring transaction participates in it, and the method joins it as a nested scope:
    // its failure marks that transaction rollback-only, as it does outside Spring, and the rollback-only JdbcUtil transaction in
    // turn marks the Spring transaction rollback-only, so Spring's commit rolls back (UnexpectedRollbackException).
    @ParameterizedTest(name = "{0}, logging={1}")
    @MethodSource("springJoinCases")
    void transactional_requiredOrMandatory_joinsJdbcUtilTransactionOpenedInsideSpringTransaction(final Propagation propagation, final Logging logging)
            throws SQLException {
        final TransactionDao<?> dao = daos.get(logging);
        final org.springframework.transaction.support.TransactionTemplate template = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        final SQLException failure = new SQLException("work failed after insert");

        assertThrows(org.springframework.transaction.UnexpectedRollbackException.class, () -> template.executeWithoutResult(status -> {
            final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource);

            try {
                assertSame(org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource), outer.connection());
                org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(outer.connection(), dataSource);

                assertSame(failure, assertThrows(SQLException.class, () -> invoke(dao, propagation, () -> {
                    try (PreparedQuery query = JdbcUtil.prepareQuery(dataSource, "INSERT INTO transaction_record (id) VALUES (1)")) {
                        query.configureStatement(statement -> assertSame(outer.connection(), statement.getConnection()));
                        assertEquals(1, query.update());
                    }
                    throw failure;
                })));

                assertEquals(Transaction.Status.MARKED_ROLLBACK, outer.status());
                outer.commit();
                assertEquals(Transaction.Status.ROLLED_BACK, outer.status());
                assertTrue(status.isRollbackOnly());
            } finally {
                outer.rollbackIfNotCommitted();
            }
        }));

        assertFalse(JdbcUtil.isInTransaction(dataSource));

        try (Statement statement = keeper.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM transaction_record")) {
            assertTrue(resultSet.next());
            assertEquals(0, resultSet.getInt(1));
        }
    }

    // A built-in batch executed in several chunks opened an outermost SqlTransaction scope of its own on Spring's connection
    // and committed that connection when it finished. Inside a @Transactional(REQUIRED) method it used to nest in the
    // method's own scope, which now joins the Spring transaction without one, so a later failure of the method could no longer
    // undo the batch.
    @ParameterizedTest(name = "{0}, inRequiredMethod={1}")
    @MethodSource("springBatchCases")
    void builtInBatch_inSpringManagedTransaction_leavesItsCompletionToSpring(final BuiltInBatch batch, final boolean inRequiredMethod) throws SQLException {
        final TransactionDao<?> dao = daos.get(Logging.NONE);
        final org.springframework.transaction.support.TransactionTemplate template = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        final SQLException failure = new SQLException("work failed after batch");

        try (Statement statement = keeper.createStatement()) {
            statement.executeUpdate("INSERT INTO transaction_record (id) VALUES (3), (4)");
        }

        template.executeWithoutResult(status -> {
            final Connection springConnection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);

            try {
                if (inRequiredMethod) {
                    assertSame(failure, assertThrows(SQLException.class, () -> dao.required(() -> {
                        batch.run(dao);
                        throw failure;
                    })));
                } else {
                    batch.run(dao);
                }

                // The batch ran in Spring's transaction and did not commit it.
                assertFalse(springConnection.getAutoCommit());
                assertEquals(batch == BuiltInBatch.BATCH_INSERT || batch == BuiltInBatch.QUERY_BATCH_INSERT ? 4 : 0, countRecords(springConnection));
                assertEquals(2, countRecords(keeper));
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(springConnection, dataSource);
            }

            status.setRollbackOnly();
        });

        assertFalse(JdbcUtil.isInTransaction(dataSource));
        assertEquals(2, countRecords(keeper));
    }

    // An insert that writes generated IDs back while it runs inside a JdbcUtil transaction (directly, or in a scope nested in it)
    // kept those IDs after the enclosing transaction rolled the rows back; only the multi-statement paths inside a Spring
    // transaction put them back. Every DAO insert now registers the pre-insert IDs with the enclosing transaction.
    @ParameterizedTest(name = "{0}, outerCommits={1}")
    @MethodSource("idRestoreCases")
    void inserts_inJdbcUtilTransaction_putBackGeneratedIdsIfItRollsBack(final GeneratedInsert insert, final boolean outerCommits) throws SQLException {
        final List<GeneratedRecord> records = insert.single ? List.of(generated("a")) : List.of(generated("a"), generated("b"));
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource);

        try {
            insert.run(generatedDao, records);
            records.forEach(record -> assertNotNull(record.getId(), insert + " writes the generated ID back"));

            if (outerCommits) {
                outer.commit();
            }
        } finally {
            outer.rollbackIfNotCommitted();
        }

        records.forEach(record -> assertEquals(!outerCommits, record.getId() == null));
        assertEquals(outerCommits ? records.size() : 0, count(keeper, "SELECT COUNT(*) FROM generated_record"));
    }

    // Inside a transaction the pre-insert ID snapshot was taken before the elements were validated, so a null element failed
    // with a NullPointerException instead of the IllegalArgumentException thrown outside a transaction.
    @Test
    void batchInserts_withANullElement_inJdbcUtilTransaction_rejectItAsAnIllegalArgument() throws SQLException {
        final List<GeneratedRecord> records = Arrays.asList(generated("a"), null);
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource);

        try {
            assertThrows(IllegalArgumentException.class, () -> generatedDao.batchInsert(records, 2));
            assertThrows(IllegalArgumentException.class, () -> generatedDao.batchInsert(records, List.of("name"), 2));
            assertThrows(IllegalArgumentException.class, () -> generatedDao.batchInsert("INSERT INTO generated_record (name) VALUES (:name)", records, 2));
            assertThrows(IllegalArgumentException.class, () -> generatedDao.insertAll(records));
        } finally {
            outer.rollbackIfNotCommitted();
        }

        // The same outside a transaction.
        assertThrows(IllegalArgumentException.class, () -> generatedDao.batchInsert(records, 2));
        assertThrows(IllegalArgumentException.class, () -> generatedDao.batchInsert(records, List.of("name"), 2));
        assertThrows(IllegalArgumentException.class, () -> generatedDao.batchInsert("INSERT INTO generated_record (name) VALUES (:name)", records, 2));
        assertThrows(IllegalArgumentException.class, () -> generatedDao.insertAll(records));

        assertNull(records.get(0).getId());
        assertEquals(0, count(keeper, "SELECT COUNT(*) FROM generated_record"));
    }

    // The pre-insert IDs are snapshots, so they must be put back newest first. Oldest first, an entity inserted twice in one
    // transaction kept the ID of its first, rolled-back insert; with the second insert in a nested scope that rolls back, the
    // entity got that ID as soon as the nested scope rolled back.
    @ParameterizedTest(name = "nested={0}")
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void inserts_ofTheSameEntityTwice_putBackTheIdItHadBeforeTheTransaction(final boolean nested) throws SQLException {
        final GeneratedRecord record = generated("a");
        final GeneratedRecord other = generated("b");
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource);

        try {
            assertNotNull(generatedDao.insert(record, List.of("name")));
            assertNotNull(generatedDao.insert(other));

            if (nested) {
                final SqlTransaction inner = JdbcUtil.beginTransaction(dataSource);

                try {
                    assertNotNull(generatedDao.insert(record, List.of("name")));
                } finally {
                    inner.rollbackIfNotCommitted();
                }

                // The transaction can now only end rolled back: the IDs are put back right away.
                assertNull(record.getId());
                assertNull(other.getId());
            } else {
                assertNotNull(generatedDao.insert(record, List.of("name")));
                assertNotNull(record.getId());
            }
        } finally {
            outer.rollbackIfNotCommitted();
        }

        assertNull(record.getId());
        assertNull(other.getId());
        assertEquals(0, count(keeper, "SELECT COUNT(*) FROM generated_record"));
    }

    // Outside any transaction nothing is registered or restored: the IDs of committed rows stay.
    @Test
    void inserts_outsideTransactions_keepTheirIds() throws SQLException {
        final GeneratedRecord record = generated("a");
        final List<GeneratedRecord> records = List.of(generated("b"), generated("c"));

        generatedDao.insert(record);
        generatedDao.batchInsert(records, 1);

        assertNotNull(record.getId());
        records.forEach(it -> assertNotNull(it.getId()));
        assertEquals(3, count(keeper, "SELECT COUNT(*) FROM generated_record"));
    }

    // A batch of several rows that fit in one JDBC batch ran without a transaction, so on a driver that keeps executing (and
    // auto-committing) the rows of a batch around a failing one, the other rows stayed applied. Now it runs in a transaction.
    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.EnumSource(FailingBatch.class)
    void batchesOfSeveralRowsInOneChunk_areAtomic(final FailingBatch batch) {
        assertThrows(Exception.class, () -> batch.run(atomicDao, dataSource));

        assertEquals(2, count(keeper, "SELECT COUNT(*) FROM atomic_record"), batch + " left the table unchanged");
        assertEquals(0, count(keeper, "SELECT COUNT(*) FROM atomic_record WHERE id = 10"));
        assertEquals(1, count(keeper, "SELECT COUNT(*) FROM atomic_record WHERE id = 1 AND name = 'a'"));
        assertFalse(JdbcUtil.isInTransaction(dataSource));
    }

    // A single row still runs without a transaction of its own.
    @Test
    void batchOfOneRow_runsWithoutATransaction() throws SQLException {
        final AtomicRecord record = atomic(3, "c");

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class, Mockito.CALLS_REAL_METHODS)) {
            assertEquals(1, atomicDao.batchInsert(List.of(record), 10).size());
            assertEquals(1, atomicDao.batchUpdate(List.of(atomic(3, "d")), 10));
            assertEquals(1, JdbcUtil.executeBatchUpdate(dataSource, "UPDATE atomic_record SET name = ? WHERE id = ?", List.of(List.of("e", 3L))));
            jdbcUtil.verify(() -> JdbcUtil.beginTransaction(ArgumentMatchers.any(javax.sql.DataSource.class)), Mockito.never());
            jdbcUtil.verify(() -> JdbcUtil.beginTransaction(ArgumentMatchers.any(javax.sql.DataSource.class), ArgumentMatchers.any(IsolationLevel.class)),
                    Mockito.never());
        }

        assertEquals(1, count(keeper, "SELECT COUNT(*) FROM atomic_record WHERE id = 3 AND name = 'e'"));
    }

    // With Spring participation disabled on the thread, JdbcUtil does not use Spring's connection, so REQUIRED must not join
    // the Spring transaction either: it keeps a scope of its own on a separate connection, independent of Spring's outcome.
    @ParameterizedTest(name = "logging={0}")
    @MethodSource("springSynchronizedLoggingCases")
    void transactional_required_keepsOwnScopeWhenSpringParticipationIsDisabled(final Logging logging) throws SQLException {
        final TransactionDao<?> dao = daos.get(logging);
        final org.springframework.transaction.support.TransactionTemplate template = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        final SQLException failure = new SQLException("work failed after insert");

        template.executeWithoutResult(status -> {
            final Connection springConnection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);

            try {
                for (final long id : new long[] { 1, 2 }) {
                    final Throwables.Supplier<Integer, SQLException> work = () -> {
                        try (PreparedQuery query = JdbcUtil.prepareQuery(dataSource, "INSERT INTO transaction_record (id) VALUES (" + id + ")")) {
                            query.configureStatement(statement -> {
                                assertNotSame(springConnection, statement.getConnection());
                                assertFalse(statement.getConnection().getAutoCommit());
                            });
                            assertEquals(1, query.update());
                        }
                        if (id == 2) {
                            throw failure;
                        }
                        return 42;
                    };

                    if (id == 1) {
                        assertEquals(42, JdbcUtil.callIgnoringSpringTransaction(() -> dao.required(work)));
                    } else {
                        assertSame(failure, assertThrows(SQLException.class, () -> JdbcUtil.callIgnoringSpringTransaction(() -> dao.required(work))));
                    }
                }
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(springConnection, dataSource);
            }

            status.setRollbackOnly();
        });

        // Row 1 was committed by the method's own scope despite Spring's rollback; row 2 was rolled back by it.
        try (Statement statement = keeper.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT id FROM transaction_record")) {
            assertTrue(resultSet.next());
            assertEquals(1L, resultSet.getLong(1));
            assertFalse(resultSet.next());
        }
    }

    private static Stream<Arguments> idRestoreCases() {
        return Stream.of(GeneratedInsert.values()).flatMap(insert -> Stream.of(false, true).map(outerCommits -> Arguments.of(insert, outerCommits)));
    }

    private static int count(final Connection connection, final String sql) {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static GeneratedRecord generated(final String name) {
        final GeneratedRecord record = new GeneratedRecord();
        record.setName(name);
        return record;
    }

    private static AtomicRecord atomic(final long id, final String name) {
        final AtomicRecord record = new AtomicRecord();
        record.setId(id);
        record.setName(name);
        return record;
    }

    /** The DAO inserts that write generated IDs back: on the first record only ({@code single}) or on all of them. */
    private enum GeneratedInsert {
        INSERT(true),
        INSERT_PROPS(true),
        INSERT_SQL(true),
        BATCH_OF_ONE(true),
        QUERY_INSERT(true),
        BATCH_ONE_CHUNK(false),
        BATCH_CHUNKS(false),
        BATCH_PROPS(false),
        BATCH_SQL(false),
        QUERY_BATCH(false),
        UPSERT(false);

        final boolean single;

        GeneratedInsert(final boolean single) {
            this.single = single;
        }

        void run(final GeneratedDao dao, final List<GeneratedRecord> records) throws SQLException {
            final GeneratedRecord first = records.get(0);
            final String sql = "INSERT INTO generated_record (name) VALUES (:name)";

            switch (this) {
                case INSERT -> assertNotNull(dao.insert(first));
                case INSERT_PROPS -> assertNotNull(dao.insert(first, java.util.List.of("name")));
                case INSERT_SQL -> assertNotNull(dao.insert(sql, first));
                case BATCH_OF_ONE -> assertEquals(1, dao.batchInsert(records).size());
                case QUERY_INSERT -> assertNotNull(dao.insertOne(first));
                case BATCH_ONE_CHUNK -> assertEquals(2, dao.batchInsert(records, 10).size());
                case BATCH_CHUNKS -> assertEquals(2, dao.batchInsert(records, 1).size());
                case BATCH_PROPS -> assertEquals(2, dao.batchInsert(records, java.util.List.of("name"), 10).size());
                case BATCH_SQL -> assertEquals(2, dao.batchInsert(sql, records, 10).size());
                case QUERY_BATCH -> assertEquals(2, dao.insertAll(records).size());
                case UPSERT -> assertEquals(2, dao.batchUpsert(records, java.util.List.of("name"), 10).size());
            }
        }
    }

    /** Batch operations on two rows in one JDBC batch, whose second row fails. */
    private enum FailingBatch {
        BATCH_INSERT, BATCH_SAVE, BATCH_UPDATE, BATCH_DELETE, BATCH_DELETE_BY_IDS, QUERY_BATCH_INSERT, QUERY_BATCH_UPDATE, JDBC_BATCH, JDBC_LARGE_BATCH;

        void run(final AtomicDao dao, final javax.sql.DataSource dataSource) throws SQLException {
            final String insertSql = "INSERT INTO atomic_record (id, name) VALUES (?, ?)";

            switch (this) {
                case BATCH_INSERT -> dao.batchInsert(List.of(atomic(10, "x"), atomic(1, "y")), 10);
                case BATCH_SAVE -> dao.batchSave(List.of(atomic(10, "x"), atomic(1, "y")), 10);
                case BATCH_UPDATE -> dao.batchUpdate(List.of(atomic(1, "z"), atomic(2, "much too long")), 10);
                case BATCH_DELETE -> dao.batchDelete(List.of(atomic(1, "a"), atomic(2, "b")), 10);
                case BATCH_DELETE_BY_IDS -> dao.batchDeleteByIds(List.of(1L, 2L), 10);
                case QUERY_BATCH_INSERT -> dao.insertAll(List.of(atomic(10, "x"), atomic(1, "y")));
                case QUERY_BATCH_UPDATE -> dao.updateAll(List.of(atomic(1, "z"), atomic(2, "much too long")));
                case JDBC_BATCH -> JdbcUtil.executeBatchUpdate(dataSource, insertSql, List.of(List.of(10L, "x"), List.of(1L, "y")));
                case JDBC_LARGE_BATCH -> JdbcUtil.executeLargeBatchUpdate(dataSource, insertSql, List.of(List.of(10L, "x"), List.of(1L, "y")));
            }
        }
    }

    private static Stream<Logging> springSynchronizedLoggingCases() {
        return Stream.of(Logging.NONE, Logging.BOTH);
    }

    private static Stream<Arguments> springBatchCases() {
        return Stream.of(BuiltInBatch.values()).flatMap(batch -> Stream.of(false, true).map(inRequiredMethod -> Arguments.of(batch, inRequiredMethod)));
    }

    private static int countRecords(final Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM transaction_record")) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static Record record(final long id) {
        final Record record = new Record();
        record.setId(id);
        return record;
    }

    /**
     * Built-in batch operations run with batch size 1 on two rows, so each executes in two chunks under a transaction scope.
     */
    private enum BuiltInBatch {
        BATCH_INSERT, BATCH_DELETE, QUERY_BATCH_INSERT, QUERY_BATCH_DELETE;

        void run(final TransactionDao<?> dao) throws SQLException {
            switch (this) {
                case BATCH_INSERT -> assertEquals(2, dao.batchInsert(java.util.List.of(record(1), record(2)), 1).size());
                case BATCH_DELETE -> assertEquals(2, dao.batchDelete(java.util.List.of(record(3), record(4)), 1));
                case QUERY_BATCH_INSERT -> dao.insertInBatches(java.util.List.of(record(1), record(2)));
                case QUERY_BATCH_DELETE -> assertEquals(2, dao.deleteInBatches(java.util.List.of(3L, 4L)));
            }
        }
    }

    private static Stream<Arguments> springJoinCases() {
        return Stream.of(Propagation.REQUIRED, Propagation.MANDATORY)
                .flatMap(propagation -> Stream.of(Logging.NONE, Logging.BOTH).map(logging -> Arguments.of(propagation, logging)));
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

        @Query(value = "INSERT INTO transaction_record (id) VALUES (:id)", batch = true, batchSize = 1)
        void insertInBatches(java.util.List<Record> records) throws SQLException;

        @Query(value = "DELETE FROM transaction_record WHERE id = ?", batch = true, batchSize = 1)
        int deleteInBatches(java.util.List<Long> ids) throws SQLException;
    }

    interface PlainDao extends TransactionDao<PlainDao> {
    }

    interface GeneratedDao extends CrudDao<GeneratedRecord, Long, GeneratedDao> {
        @Query("INSERT INTO generated_record (name) VALUES (:name)")
        Long insertOne(GeneratedRecord record) throws SQLException;

        @Query(value = "INSERT INTO generated_record (name) VALUES (:name)", batch = true)
        List<Long> insertAll(List<GeneratedRecord> records) throws SQLException;
    }

    interface AtomicDao extends CrudDao<AtomicRecord, Long, AtomicDao> {
        @Query(value = "INSERT INTO atomic_record (id, name) VALUES (:id, :name)", batch = true)
        void insertAll(List<AtomicRecord> records) throws SQLException;

        @Query(value = "UPDATE atomic_record SET name = :name WHERE id = :id", batch = true)
        int updateAll(List<AtomicRecord> records) throws SQLException;
    }

    @Table("generated_record")
    public static class GeneratedRecord {
        @Id
        private Long id;
        private String name;

        public Long getId() {
            return id;
        }

        public void setId(final Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }
    }

    @Table("atomic_record")
    public static class AtomicRecord {
        @Id
        private Long id;
        private String name;

        public Long getId() {
            return id;
        }

        public void setId(final Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }
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
