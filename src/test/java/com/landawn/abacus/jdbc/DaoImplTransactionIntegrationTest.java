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
import java.util.concurrent.atomic.AtomicInteger;
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
import com.landawn.abacus.exception.DuplicateResultException;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.jdbc.annotation.PerfLog;
import com.landawn.abacus.jdbc.annotation.Query;
import com.landawn.abacus.jdbc.annotation.SqlLogEnabled;
import com.landawn.abacus.jdbc.annotation.Transactional;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.query.Filters;
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
                assertThrows(RollbackOnlyException.class, outer::commit);
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

    // A @Transactional(REQUIRED) DAO method whose body catches the failure of a nested @Transactional call: the transaction is
    // rollback-only, so the method's own commit rolls back and throws RollbackOnlyException instead of returning normally
    // (it used to return as if its work had been committed). Both wrapper variants (with and without SQL/perf logging).
    @Test
    void transactional_required_throwsRollbackOnlyAfterACaughtNestedFailure() throws SQLException {
        for (final Logging logging : Logging.values()) {
            final TransactionDao<?> dao = daos.get(logging);
            final SQLException nestedFailure = new SQLException("nested work failed");

            assertThrows(RollbackOnlyException.class, () -> dao.required(() -> {
                try (PreparedQuery query = JdbcUtil.prepareQuery(dataSource, "INSERT INTO transaction_record (id) VALUES (1)")) {
                    assertEquals(1, query.update());
                }

                assertSame(nestedFailure, assertThrows(SQLException.class, () -> dao.required(() -> {
                    throw nestedFailure;
                })), logging.name());

                return 1; // the nested failure was handled: the method goes on
            }), logging.name());

            assertEquals(0, countRecords(keeper), logging.name());
            assertFalse(JdbcUtil.isInTransaction(dataSource), logging.name());
        }
    }

    // Transaction review, finding 8: a @Transactional(REQUIRED) DAO method or callInTransaction called after a caught nested
    // failure joins the rollback-only transaction, as in Spring: its work runs and is rolled back with the transaction, whose
    // outermost commit throws RollbackOnlyException. Such a call used to throw IllegalStateException before its work ran, while
    // plain statements on the data source kept running in the transaction.
    @Test
    void transactional_required_joinsATransactionMarkedRollbackOnly() throws SQLException {
        for (final Logging logging : Logging.values()) {
            final TransactionDao<?> dao = daos.get(logging);
            final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource);

            try {
                assertThrows(SQLException.class, () -> dao.required(() -> {
                    throw new SQLException("nested work failed");
                }), logging.name());
                assertEquals(Transaction.Status.MARKED_ROLLBACK, outer.status(), logging.name());

                assertEquals(42, dao.required(() -> {
                    assertEquals(2, outer.context().depth());

                    try (PreparedQuery query = JdbcUtil.prepareQuery(dataSource, "INSERT INTO transaction_record (id) VALUES (1)")) {
                        assertEquals(1, query.update());
                    }

                    return 42;
                }), logging.name());
                assertEquals(43, (int) JdbcUtil.callInTransaction(dataSource, () -> 43), logging.name());

                assertThrows(RollbackOnlyException.class, outer::commit, logging.name());
            } finally {
                outer.rollbackIfNotCommitted();
            }

            assertEquals(0, countRecords(keeper), logging.name());
            assertFalse(JdbcUtil.isInTransaction(dataSource), logging.name());
        }
    }

    // Transaction review, finding 14: upsert looked the row up before and apart from the write, on a connection of its own
    // outside a transaction, and batchUpsert ran its writes in a transaction only when both inserts and updates were needed
    // (or either set was large). Inside a transaction, the lookup and the writes run in one scope of it now, on its connection,
    // and batchUpsert of more than one entity in a transaction of its own outside one. Round 16 review: outside a transaction,
    // an upsert (and a batchUpsert of one entity) began a transaction of its own, whose commit failed on a connection a JTA
    // transaction manages (it rejects commit()), where it used to work: they run as statements of their own there again.
    @Test
    void upsert_looksUpAndWritesInOneTransactionScopeInsideATransaction() throws SQLException {
        final AtomicInteger acquired = new AtomicInteger();
        final AtomicInteger commits = new AtomicInteger();
        final javax.sql.DataSource counting = new org.springframework.jdbc.datasource.DelegatingDataSource(dataSource) {
            @Override
            public Connection getConnection() throws SQLException {
                acquired.incrementAndGet();
                final Connection conn = super.getConnection();

                return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class },
                        (proxy, method, args) -> {
                            if (method.getName().equals("commit")) {
                                commits.incrementAndGet();
                            }

                            try {
                                return method.invoke(conn, args);
                            } catch (final java.lang.reflect.InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
            }
        };
        final GeneratedDao dao = JdbcUtil.createDao(GeneratedDao.class, counting);
        acquired.set(0);

        // Outside a transaction: the lookup and the write of each upsert run as statements of their own; nothing is committed
        // by an upsert itself.
        assertEquals("a", dao.upsert(generated(1L, "a")).getName()); // inserted
        assertEquals("b", dao.upsert(generated(1L, "b")).getName()); // updated
        assertEquals(4, acquired.get(), "a connection for the lookup and one for the write of each upsert");
        assertEquals(1, dao.batchUpsert(List.of(generated(1L, "b2"))).size()); // a single entity, as upsert
        assertEquals(0, commits.get(), "no transaction of its own");

        // Inside a transaction: in a scope of it, on its connection.
        acquired.set(0);
        final SqlTransaction tran = JdbcUtil.beginTransaction(counting);

        try {
            assertEquals("c", dao.upsert(generated(1L, "c")).getName());
            assertEquals("e", dao.upsert(generated(3L, "e")).getName());
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }

        assertEquals(1, acquired.get(), "the transaction's connection");
        assertEquals(1, commits.get(), "committed by the transaction");

        // batchUpsert of more than one entity outside a transaction: a transaction of its own, on one connection.
        acquired.set(0);
        commits.set(0);
        assertEquals(2, dao.batchUpsert(List.of(generated(1L, "d"), generated(2L, "f"))).size()); // one update, one insert
        assertEquals(1, acquired.get(), "one connection for the lookups and the writes of batchUpsert");
        assertEquals(1, commits.get());
        assertEquals(3, count(keeper, "SELECT COUNT(*) FROM generated_record"));
        assertEquals(1, count(keeper, "SELECT COUNT(*) FROM generated_record WHERE id = 1 AND name = 'd'"));
    }

    // Round 16 review (tests): batchUpsert inside a transaction runs in a scope of it: a failure (here the duplicate rows its
    // lookup finds) marks the transaction rollback-only, and when the transaction rolls back, the IDs batchUpsert wrote onto the
    // entities it inserted are put back.
    @Test
    void batchUpsert_insideATransaction_marksItRollbackOnlyOnFailure_andItsRollbackPutsTheIdsBack() throws SQLException {
        // Generated IDs: an explicit one could be the next one the identity column hands out.
        generatedDao.insert(generated("same"));
        generatedDao.insert(generated("same"));
        final GeneratedRecord inserted = generated("new");

        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

        try {
            assertEquals(1, generatedDao.batchUpsert(List.of(inserted), List.of("name")).size());
            assertNotNull(inserted.getId(), "inserted");

            assertThrows(IllegalStateException.class, () -> generatedDao.batchUpsert(List.of(generated("same"), generated("other")), List.of("name")));
            assertEquals(Transaction.Status.MARKED_ROLLBACK, tran.status());
            assertThrows(RollbackOnlyException.class, tran::commit);
        } finally {
            tran.rollbackIfNotCommitted();
        }

        assertNull(inserted.getId(), "the ID of the rolled-back insert is put back");
        assertEquals(2, count(keeper, "SELECT COUNT(*) FROM generated_record"));
    }

    // Round 16 review (tests): the upsert of a DAO that is not a CrudDao has its own copy of the scope handling: inside a
    // transaction, its failure marks the transaction rollback-only too.
    @ParameterizedTest(name = "unchecked={0}")
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void upsertOfANonCrudDao_failingInsideATransaction_marksItRollbackOnly(final boolean unchecked) throws SQLException {
        generatedDao.insert(generated(1L, "same"));
        generatedDao.insert(generated(2L, "same"));

        final PlainGeneratedDao checkedDao = JdbcUtil.createDao(PlainGeneratedDao.class, dataSource);
        final UncheckedPlainGeneratedDao uncheckedDao = JdbcUtil.createDao(UncheckedPlainGeneratedDao.class, dataSource);
        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

        try {
            if (unchecked) {
                assertThrows(DuplicateResultException.class, () -> uncheckedDao.upsert(generated(3L, "new"), Filters.eq("name", "same")));
            } else {
                assertThrows(DuplicateResultException.class, () -> checkedDao.upsert(generated(3L, "new"), Filters.eq("name", "same")));
            }

            assertEquals(Transaction.Status.MARKED_ROLLBACK, tran.status());
            assertThrows(RollbackOnlyException.class, tran::commit);
        } finally {
            tran.rollbackIfNotCommitted();
        }

        // Outside a transaction it upserts as statements of its own.
        assertEquals("x",
                (unchecked ? uncheckedDao.upsert(generated(4L, "x"), Filters.eq("name", "x")) : checkedDao.upsert(generated(4L, "x"), Filters.eq("name", "x")))
                        .getName());
        assertEquals(1, count(keeper, "SELECT COUNT(*) FROM generated_record WHERE name = 'x'"));
    }

    // Inside a transaction, the scope of a failed upsert marks the transaction rollback-only, as the failure of any
    // transactional DAO operation does: it can't commit work the failed upsert may have left half done.
    @Test
    void upsert_failingInsideATransaction_marksItRollbackOnly() throws SQLException {
        generatedDao.insert(generated(1L, "same"));
        generatedDao.insert(generated(2L, "same"));

        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

        try {
            assertThrows(DuplicateResultException.class, () -> generatedDao.upsert(generated(3L, "new"), Filters.eq("name", "same")));
            assertEquals(Transaction.Status.MARKED_ROLLBACK, tran.status());
            assertThrows(RollbackOnlyException.class, tran::commit);
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    // Transaction review, low-severity finding: @Transactional on a @NonDBOperation method had no effect (the method is not run
    // as a DB operation); DAO creation rejects it now.
    @Test
    void transactional_onANonDBOperationMethod_isRejected() {
        final UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> JdbcUtil.createDao(NonDbTransactionalDao.class, dataSource));
        assertTrue(thrown.getMessage().contains("@NonDBOperation"), thrown.getMessage());
    }

    // A default method overriding a @Transactional one inherits its transaction, as in Spring: Java does not inherit method
    // annotations, so the override ran without one.
    @Test
    void transactional_isInheritedByAnOverridingDefaultMethod() throws SQLException {
        final OverridingDao dao = JdbcUtil.createDao(OverridingDao.class, dataSource);

        assertEquals(1, dao.required(() -> {
            assertTrue(JdbcUtil.isInTransaction(dataSource));
            return 1;
        }));
        assertFalse(JdbcUtil.isInTransaction(dataSource));
    }

    // Round 16 review (tests): an abstract re-declaration of a @Transactional method (here a @Query method re-declared without
    // the annotation) inherits its annotation as a default override does: REQUIRES_NEW commits the insert on its own, though the
    // enclosing transaction rolls back.
    @Test
    void transactional_isInheritedByAnAbstractRedeclaration() throws SQLException {
        final RedeclaringDao dao = JdbcUtil.createDao(RedeclaringDao.class, dataSource);
        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

        try {
            dao.insertOnItsOwn(7L);
        } finally {
            tran.rollbackIfNotCommitted();
        }

        assertEquals(1, count(keeper, "SELECT COUNT(*) FROM transaction_record WHERE id = 7"), "committed on its own (REQUIRES_NEW)");
    }

    // Round 14 review: an override of a method of a generic super-interface (save(String) for save(T) of Base<T>) was not found,
    // as it was looked up by its erased parameter types (save(Object)): only the bridge method javac adds inherited the
    // annotation, and the override ran without a transaction. An overload is no override.
    @Test
    void transactional_isInheritedByAnOverrideOfAGenericMethod() throws SQLException {
        final GenericOverridingDao dao = JdbcUtil.createDao(GenericOverridingDao.class, dataSource);

        assertEquals(1, dao.requiredWith("override", () -> {
            assertTrue(JdbcUtil.isInTransaction(dataSource));
            return 1;
        }));
        assertEquals(2, dao.requiredWith(2, () -> {
            assertFalse(JdbcUtil.isInTransaction(dataSource), "an overload");
            return 2;
        }));

        // Through the generic super-interface (the bridge method, which calls the override).
        final GenericTransactionalBase<String> base = dao;
        assertEquals(3, base.requiredWith("base", () -> {
            assertTrue(JdbcUtil.isInTransaction(dataSource));
            return 3;
        }));
    }

    // External review (tests): the annotation is that of the nearest overridden method that declares one, as in Spring: an
    // override in between that declares none does not stop the search. An override opts out with SUPPORTS.
    @Test
    void transactional_isInheritedPastAnUnannotatedOverrideInBetween_andAnOverrideOptsOutWithSupports() throws SQLException {
        final IntermediateOverridingDao dao = JdbcUtil.createDao(IntermediateOverridingDao.class, dataSource);

        assertEquals(1, dao.required(() -> {
            assertTrue(JdbcUtil.isInTransaction(dataSource), "REQUIRED of TransactionDao, past UnannotatedMiddleDao's override");
            return 1;
        }));

        final OptingOutDao optingOut = JdbcUtil.createDao(OptingOutDao.class, dataSource);

        assertEquals(2, optingOut.required(() -> {
            assertFalse(JdbcUtil.isInTransaction(dataSource), "SUPPORTS: no transaction of its own");
            return 2;
        }));
    }

    // Round 15 review: a @NonDBOperation override of a @Transactional method was rejected at DAO creation, for the annotation it
    // inherited: such an override could not be declared at all. Only a @Transactional it declares is rejected; one it would
    // inherit is ignored, as the method is not run as a DB operation.
    @Test
    void transactional_inheritedByANonDBOperationOverride_isIgnored() throws SQLException {
        final NonDbOverridingDao dao = JdbcUtil.createDao(NonDbOverridingDao.class, dataSource);

        assertEquals(1, dao.required(() -> {
            assertFalse(JdbcUtil.isInTransaction(dataSource));
            return 1;
        }));
    }

    // Round 15 review: a query prepared in a transaction and used after the transaction completed was rejected but left open,
    // with its statement and close handlers. It is closed first now, unless closeAfterExecution(false) is set; on another thread,
    // where the owner thread may be using it, it is not.
    @Test
    void aQueryUsedAfterItsTransactionCompleted_isClosed() throws Exception {
        final AtomicInteger closed = new AtomicInteger();
        final PreparedQuery closing;
        final PreparedQuery kept;
        final PreparedQuery otherThread;
        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

        try {
            closing = JdbcUtil.prepareQuery(dataSource, "SELECT id FROM transaction_record").onClose(closed::incrementAndGet);
            kept = JdbcUtil.prepareQuery(dataSource, "SELECT id FROM transaction_record").closeAfterExecution(false).onClose(closed::incrementAndGet);
            otherThread = JdbcUtil.prepareQuery(dataSource, "SELECT id FROM transaction_record").onClose(closed::incrementAndGet);

            final java.util.concurrent.CompletableFuture<Throwable> elsewhere = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    otherThread.list(Long.class);
                    return null;
                } catch (final Throwable e) {
                    return e;
                }
            });
            assertTrue(elsewhere.get() instanceof IllegalStateException);
            assertFalse(otherThread.isClosed, "not closed on another thread");

            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }

        assertThrows(IllegalStateException.class, () -> closing.list(Long.class));
        assertTrue(closing.isClosed);
        assertEquals(1, closed.get(), "its close handler ran");

        assertThrows(IllegalStateException.class, () -> kept.list(Long.class));
        assertFalse(kept.isClosed, "kept open with closeAfterExecution(false)");
        kept.close();
        otherThread.close();
        assertEquals(3, closed.get());
    }

    // Round 14 review: a parallel stream of a query prepared in a transaction failed with IllegalStateException: the stream
    // starts the query's deferred execution on one of its worker threads, which the check binding the query to the transaction's
    // thread refused. The deferred execution only checks that the transaction has not completed.
    @Test
    void aParallelStreamOfAQueryPreparedInATransaction_runs() throws SQLException {
        try (Statement statement = keeper.createStatement()) {
            statement.executeUpdate("INSERT INTO transaction_record (id) VALUES (1), (2), (3)");
        }

        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

        try {
            JdbcUtil.executeUpdate(dataSource, "INSERT INTO transaction_record (id) VALUES (4)"); // not committed

            final List<Long> ids = JdbcUtil.prepareQuery(dataSource, "SELECT id FROM transaction_record")
                    .stream(Long.class)
                    .parallel(4)
                    .map(id -> id * 10)
                    .sorted()
                    .toList();
            assertEquals(List.of(10L, 20L, 30L, 40L), ids, "the uncommitted row is read too");

            // Executed once the transaction has completed, the stream fails: its connection has been released.
            try (com.landawn.abacus.util.stream.Stream<Long> stream = JdbcUtil.prepareQuery(dataSource, "SELECT id FROM transaction_record")
                    .stream(Long.class)) {
                tran.commit();
                assertThrows(IllegalStateException.class, stream::toList);
            }
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    // Transaction review, low-severity finding: a rollback put back the ID an insert had written even after the application had
    // set another ID since. Only an ID that still holds the written value is put back now.
    @Test
    void anIdSetAfterTheInsert_isKeptByARollback() throws SQLException {
        final GeneratedRecord changed = generated("changed");
        final GeneratedRecord kept = generated("kept");
        final List<GeneratedRecord> batch = List.of(generated("b1"), generated("b2"));
        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

        try {
            generatedDao.insert(changed);
            generatedDao.insert(kept);
            generatedDao.batchInsert(batch);
            assertNotNull(changed.getId());

            changed.setId(99L);
            batch.get(0).setId(98L);
        } finally {
            tran.rollbackIfNotCommitted();
        }

        assertEquals(99L, changed.getId(), "the ID the application set is kept");
        assertNull(kept.getId(), "the written ID is put back");
        assertEquals(98L, batch.get(0).getId());
        assertNull(batch.get(1).getId());
    }

    // Round 16 review: when the ID generation of an insert failed in a transaction, the snapshot taken before it recorded no
    // write, so a rollback put back every ID it had captured, also one the application set after the failure to retry the insert.
    // The restored values count as written now: an ID set since is kept.
    @Test
    void aFailedIdGeneration_keepsAnIdTheApplicationSetsAfterIt() throws SQLException {
        final FailingIdGeneratorDao dao = JdbcUtil.createDao(FailingIdGeneratorDao.class, dataSource);
        final GeneratedRecord record = generated("retried");
        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

        try {
            FailingIdGeneratorDao.FAIL_NEXT.set(true);
            assertThrows(IllegalStateException.class, () -> dao.insert(record));
            assertNull(record.getId(), "nothing was inserted: the ID is put back");

            record.setId(42L); // the application retries with an ID of its own
            dao.insert(record);
            assertEquals(42L, record.getId());
        } finally {
            tran.rollbackIfNotCommitted();
        }

        assertEquals(42L, record.getId(), "the ID the application set is kept");
    }

    // External review: a key setter that failed after assigning the key the database generated (a custom setter, through
    // @AccessFieldByMethod; in a batch, the setter of a later entity, after those of the earlier ones) recorded no write, so a
    // rollback put back every captured ID, also one the application had set since. The values the failed setter left count
    // as written now: an ID the application sets afterwards is kept, one still holding the written key is put back.
    @ParameterizedTest(name = "{0}")
    @MethodSource("failingSetterInserts")
    void aKeySetterFailingAfterAssigning_recordsTheKeyAsWritten(final String insert, final int entities) throws SQLException {
        final FailingSetterDao dao = JdbcUtil.createDao(FailingSetterDao.class, dataSource);

        for (final boolean reassigned : new boolean[] { false, true }) {
            final List<FailingSetterRecord> records = new java.util.ArrayList<>();

            for (int i = 0; i < entities; i++) {
                records.add(FailingSetterRecord.named("r" + i));
            }

            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

            try {
                FailingSetterRecord.FAIL_AT.set(entities - 1); // the setter of the last entity fails, after assigning its key
                assertThrows(IllegalStateException.class, () -> insertFailingSetterRecords(dao, insert, records));
                records.forEach(record -> assertNotNull(record.id, "every key was assigned"));

                if (reassigned) {
                    for (int i = 0; i < entities; i++) {
                        records.get(i).id = 90L + i; // the application sets IDs of its own
                    }
                }
            } finally {
                FailingSetterRecord.FAIL_AT.set(-1);
                tran.rollbackIfNotCommitted();
            }

            for (int i = 0; i < entities; i++) {
                assertEquals(reassigned ? Long.valueOf(90L + i) : null, records.get(i).id, insert + ", reassigned=" + reassigned + ", entity " + i);
            }
        }

        assertEquals(0, count(keeper, "SELECT COUNT(*) FROM generated_record"));
    }

    // External review (tests): a composite key the database generated, written in part when a setter fails (the setter of the
    // second part throws before assigning it): the part written counts as written, so a rollback puts the key back unless the
    // application has set it since.
    @Test
    void aCompositeKeyWrittenInPartWhenItsSetterFails_isPutBackUnlessSetSince() throws SQLException {
        try (Statement statement = keeper.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS partial_key_record (tenant_id BIGINT DEFAULT 7, row_id BIGINT AUTO_INCREMENT, name VARCHAR(10),"
                    + " PRIMARY KEY (tenant_id, row_id))");
            statement.executeUpdate("DELETE FROM partial_key_record");
        }

        final PartialKeyDao dao = JdbcUtil.createDao(PartialKeyDao.class, dataSource);

        for (final boolean reassigned : new boolean[] { false, true }) {
            final PartialKeyRecord record = new PartialKeyRecord();
            record.setName("p");
            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

            try {
                PartialKeyRecord.FAIL_ROW_ID.set(true);
                assertThrows(IllegalStateException.class, () -> dao.insert(record));
                assertEquals(7L, record.tenantId, "the first part was written");
                assertNull(record.rowId, "the second was not");

                if (reassigned) {
                    record.tenantId = 1L;
                    record.rowId = 2L;
                }
            } finally {
                PartialKeyRecord.FAIL_ROW_ID.set(false);
                tran.rollbackIfNotCommitted();
            }

            assertEquals(reassigned ? Long.valueOf(1L) : null, record.tenantId, "reassigned=" + reassigned);
            assertEquals(reassigned ? Long.valueOf(2L) : null, record.rowId, "reassigned=" + reassigned);
        }

        assertEquals(0, count(keeper, "SELECT COUNT(*) FROM partial_key_record"));
    }

    // External review (tests): a failure to read the values a failed setter left (its getter throws) is attached to the setter's
    // failure as suppressed, which propagates. External review: the first failure left no value recorded at all, so a rollback
    // put back the captured IDs of every entity, also those the application had set since (90 -> null; [90, 91] ->
    // [null, null]). Each entity is recorded on its own now: one whose ID can't be read is recorded as unknown and left alone
    // (it keeps the ID the insert assigned, as the rollback can't tell whether the application has set another one since), the
    // others as before. External review (tests): with several unreadable entities, the later failures are attached to the
    // first one, as suppressed.
    @ParameterizedTest(name = "entities={0}, unreadable={1}, reassigned={2}")
    @org.junit.jupiter.params.provider.CsvSource({ "1, 1, false", "1, 1, true", "2, 1, false", "2, 1, true", "3, 2, false", "3, 2, true" })
    void aFailureToRecordTheValuesAFailedSetterLeft_isSuppressedAndLeavesTheUnreadableEntitiesAlone(final int entities, final int unreadable,
            final boolean reassigned) throws SQLException {
        final FailingSetterDao dao = JdbcUtil.createDao(FailingSetterDao.class, dataSource);
        final List<FailingSetterRecord> records = new java.util.ArrayList<>();

        for (int i = 0; i < entities; i++) {
            records.add(FailingSetterRecord.named("r" + i));
        }

        final Long[] keys = new Long[entities];
        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

        try {
            FailingSetterRecord.FAIL_AT.set(entities - 1); // the setter of the last entity fails, after assigning its key
            // then the next ID reads fail: those of the first entities
            FailingSetterRecord.GETTER_FAILURES_AFTER_SETTER.set(unreadable);

            final IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> insertFailingSetterRecords(dao, entities == 1 ? "insert" : "batchInsert", records));
            assertEquals("the ID setter failed after assigning the ID", thrown.getMessage(), "the setter's failure propagates");
            assertEquals(1, thrown.getSuppressed().length);
            final Throwable firstGetterFailure = thrown.getSuppressed()[0];
            assertEquals("the ID getter failed", firstGetterFailure.getMessage());
            assertEquals(unreadable - 1, firstGetterFailure.getSuppressed().length, "the later getter failures are attached to the first");

            for (final Throwable later : firstGetterFailure.getSuppressed()) {
                assertEquals("the ID getter failed", later.getMessage());
            }

            for (int i = 0; i < entities; i++) {
                keys[i] = records.get(i).id;
                assertNotNull(keys[i]);

                if (reassigned) {
                    records.get(i).id = 90L + i;
                }
            }
        } finally {
            FailingSetterRecord.resetFailures();
            tran.rollbackIfNotCommitted();
        }

        for (int i = 0; i < entities; i++) {
            final Long expected = reassigned ? Long.valueOf(90L + i) : i < unreadable ? keys[i] : null;
            assertEquals(expected, records.get(i).id, "entity " + i + (i < unreadable ? " (unknown: left alone)" : " (recorded)"));
        }
    }

    private static Stream<Arguments> failingSetterInserts() {
        return Stream.of(Arguments.of("insert", 1), Arguments.of("batchInsert", 2), Arguments.of("@Query insert", 1), Arguments.of("@Query batch insert", 2));
    }

    private static void insertFailingSetterRecords(final FailingSetterDao dao, final String insert, final List<FailingSetterRecord> records)
            throws SQLException {
        switch (insert) {
            case "insert" -> dao.insert(records.get(0));
            case "batchInsert" -> dao.batchInsert(records);
            case "@Query insert" -> dao.insertOne(records.get(0));
            default -> dao.insertAll(records);
        }
    }

    // Round 17 review (tests): the batch path of that fix. A batchInsert whose ID generation fails for a later entity puts back
    // the IDs generated for the entities before it (nothing was inserted), and the restored values count as written: the IDs
    // the application sets afterwards to retry the batch are kept by a later rollback.
    @Test
    void aFailedIdGenerationInABatchInsert_keepsIdsTheApplicationSetsAfterIt() throws SQLException {
        final FailingIdGeneratorDao dao = JdbcUtil.createDao(FailingIdGeneratorDao.class, dataSource);
        final GeneratedRecord first = generated("first");
        final GeneratedRecord second = generated("second");
        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

        try {
            FailingIdGeneratorDao.FAIL_AFTER.set(1); // the generation for the second entity fails
            assertThrows(IllegalStateException.class, () -> dao.batchInsert(List.of(first, second)));
            assertNull(first.getId(), "nothing was inserted: the generated ID is put back");
            assertNull(second.getId());

            first.setId(42L); // the application retries with IDs of its own
            second.setId(43L);
            assertEquals(2, dao.batchInsert(List.of(first, second)).size());
        } finally {
            FailingIdGeneratorDao.FAIL_AFTER.set(-1);
            tran.rollbackIfNotCommitted();
        }

        assertEquals(42L, first.getId(), "the IDs the application set are kept");
        assertEquals(43L, second.getId());
        assertEquals(0, count(keeper, "SELECT COUNT(*) FROM generated_record"));
    }

    // Round 17 review (tests): a DAO whose interface overrides dataSource() runs its statements on that data source, and puts
    // back the IDs of its inserts when the transaction there rolls back (round 16 fixed the checks, which used the DAO's own
    // data source; only the cache side was tested).
    @Test
    void anInsertThroughADaoOverridingItsDataSource_putsBackItsIdWhenTheTransactionThereRollsBack() throws SQLException {
        final JdbcDataSource own = new JdbcDataSource();
        own.setURL("jdbc:h2:mem:dao_transaction_own_" + UUID.randomUUID());
        OverridingDataSourceGeneratedDao.TARGET.set(dataSource);

        try {
            final OverridingDataSourceGeneratedDao dao = JdbcUtil.createDao(OverridingDataSourceGeneratedDao.class, own);
            final GeneratedRecord record = generated("overriding");
            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

            try {
                dao.insert(record);
                assertNotNull(record.getId());
            } finally {
                tran.rollbackIfNotCommitted();
            }

            assertNull(record.getId(), "the transaction on the overriding data source rolled the row back: its ID is put back");
            assertEquals(0, count(keeper, "SELECT COUNT(*) FROM generated_record"));
        } finally {
            OverridingDataSourceGeneratedDao.TARGET.set(null);
        }
    }

    // Transaction review, low-severity finding: an insert that writes no ID (its entities carry their IDs, and the driver returns
    // no keys for them, as MySQL does for a table without an auto-increment column) took a snapshot of its entities in a
    // transaction all the same: a batch of a million entities cost a million weak references. The snapshot is taken right before
    // the first ID write now.
    @Test
    void anInsertWritingNoId_takesNoSnapshotInATransaction() throws SQLException {
        final javax.sql.DataSource keyless = withoutGeneratedKeys(dataSource);
        final GeneratedDao dao = JdbcUtil.createDao(GeneratedDao.class, keyless);
        final SqlTransaction tran = JdbcUtil.beginTransaction(keyless);

        try {
            dao.insert(generated(1L, "a"));
            assertEquals(2, dao.batchInsert(List.of(generated(2L, "b"), generated(3L, "c"))).size());
            assertEquals(0, tran.context().rollbackActionCount(), "no ID was written");
        } finally {
            tran.rollbackIfNotCommitted();
        }

        final SqlTransaction generating = JdbcUtil.beginTransaction(dataSource);

        try {
            generatedDao.insert(generated("d"));
            assertEquals(1, generating.context().rollbackActionCount(), "a generated ID was written");
        } finally {
            generating.rollbackIfNotCommitted();
        }
    }

    // Round 14 review: H2, PostgreSQL and Oracle return the explicit IDs an insert was given as its generated keys. Writing them
    // back changed nothing, but inside a transaction it took a snapshot of every entity's ID first, kept until the transaction
    // ended, and recorded the values written. An entity that already holds the returned ID is left alone now; one inserted
    // without an ID still gets its key, and a rollback still puts that back.
    @Test
    void anInsertWhoseKeysAreTheEntitiesOwnIds_takesNoSnapshotInATransaction() throws SQLException {
        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
        final GeneratedRecord generatedOne = generated("g");

        try {
            final GeneratedRecord single = generated(1001L, "a");
            assertEquals(1001L, generatedDao.insert(single));
            assertEquals(List.of(1002L, 1003L), generatedDao.batchInsert(List.of(generated(1002L, "b"), generated(1003L, "c"))));
            assertEquals(List.of(1004L, 1005L), generatedDao.batchInsert(List.of(generated(1004L, "d"), generated(1005L, "e")), 1));
            assertEquals(1006L, generatedDao.insert(generated(1006L, "f"), List.of("id", "name")));
            assertEquals(List.of(1007L, 1008L), generatedDao.batchInsert(List.of(generated(1007L, "g"), generated(1008L, "h")), List.of("id", "name"), 10));
            assertEquals(1009L, generatedDao.insertOneWithId(generated(1009L, "i")));
            assertEquals(List.of(1010L, 1011L), generatedDao.insertAllWithIds(List.of(generated(1010L, "j"), generated(1011L, "k"))));
            assertEquals(0, tran.context().rollbackActionCount(), "the returned keys are the entities' own IDs");
            assertEquals(1001L, single.getId());

            generatedDao.insert(generatedOne);
            assertNotNull(generatedOne.getId());
            assertEquals(1, tran.context().rollbackActionCount(), "a generated ID was written");
        } finally {
            tran.rollbackIfNotCommitted();
        }

        assertNull(generatedOne.getId(), "the generated ID is put back");
    }

    // A batch mixing entities with explicit IDs and without: a rollback puts back the generated IDs only.
    @Test
    void aMixedBatchRolledBack_keepsTheExplicitIds() throws SQLException {
        final GeneratedRecord explicit = generated(1101L, "x");
        final GeneratedRecord generatedOne = generated("y");
        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

        try {
            assertEquals(2, generatedDao.batchInsert(List.of(explicit, generatedOne)).size());
            assertNotNull(generatedOne.getId());
        } finally {
            tran.rollbackIfNotCommitted();
        }

        assertEquals(1101L, explicit.getId());
        assertNull(generatedOne.getId());
    }

    /** Wraps {@code target}: its statements return no generated keys. */
    private static javax.sql.DataSource withoutGeneratedKeys(final javax.sql.DataSource target) {
        return (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(DaoImplTransactionIntegrationTest.class.getClassLoader(),
                new Class<?>[] { javax.sql.DataSource.class }, (proxy, method, args) -> {
                    final Object result = invoke(target, method, args);

                    return result instanceof final Connection connection ? withoutGeneratedKeys(Connection.class, connection) : result;
                });
    }

    private static <T> T withoutGeneratedKeys(final Class<T> type, final T target) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(DaoImplTransactionIntegrationTest.class.getClassLoader(), new Class<?>[] { type },
                (proxy, method, args) -> {
                    if ("getGeneratedKeys".equals(method.getName())) {
                        return ((java.sql.Statement) target).getConnection().createStatement().executeQuery("SELECT id FROM generated_record WHERE 1 = 0");
                    }

                    final Object result = invoke(target, method, args);

                    return result instanceof final java.sql.PreparedStatement statement && !(result instanceof java.sql.CallableStatement)
                            ? withoutGeneratedKeys(java.sql.PreparedStatement.class, statement)
                            : result;
                }));
    }

    private static Object invoke(final Object target, final java.lang.reflect.Method method, final Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (final java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static GeneratedRecord generated(final Long id, final String name) {
        final GeneratedRecord record = generated(name);
        record.setId(id);
        return record;
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

    interface UnannotatedMiddleDao<D extends UnannotatedMiddleDao<D>> extends TransactionDao<D> {
        @Override
        default int required(final Throwables.Supplier<Integer, SQLException> work) throws SQLException {
            return work.get();
        }
    }

    interface IntermediateOverridingDao extends UnannotatedMiddleDao<IntermediateOverridingDao> {
        @Override
        default int required(final Throwables.Supplier<Integer, SQLException> work) throws SQLException {
            return work.get();
        }
    }

    interface OptingOutDao extends TransactionDao<OptingOutDao> {
        @Override
        @Transactional(propagation = Propagation.SUPPORTS)
        default int required(final Throwables.Supplier<Integer, SQLException> work) throws SQLException {
            return work.get();
        }
    }

    interface NonDbTransactionalDao extends CrudDao<Record, Long, NonDbTransactionalDao> {
        @NonDBOperation
        @Transactional
        default int helper() {
            return 1;
        }
    }

    interface OverridingDao extends TransactionDao<OverridingDao> {
        @Override
        default int required(final Throwables.Supplier<Integer, SQLException> work) throws SQLException {
            return work.get();
        }
    }

    interface GenericTransactionalBase<V> {
        @Transactional(propagation = Propagation.REQUIRED)
        default int requiredWith(final V value, final Throwables.Supplier<Integer, SQLException> work) throws SQLException {
            return work.get();
        }
    }

    // The type argument reaches the base through a type variable of its own.
    interface GenericTransactionalMiddle<W> extends GenericTransactionalBase<W> {
    }

    interface GenericOverridingDao extends CrudDao<Record, Long, GenericOverridingDao>, GenericTransactionalMiddle<String> {
        @Override
        default int requiredWith(final String value, final Throwables.Supplier<Integer, SQLException> work) throws SQLException {
            return work.get();
        }

        default int requiredWith(final Integer value, final Throwables.Supplier<Integer, SQLException> work) throws SQLException {
            return work.get();
        }
    }

    interface NonDbOverridingDao extends TransactionDao<NonDbOverridingDao> {
        @Override
        @NonDBOperation
        default int required(final Throwables.Supplier<Integer, SQLException> work) throws SQLException {
            return work.get();
        }
    }

    interface TransactionalInsertDao<D extends TransactionalInsertDao<D>> extends CrudDao<Record, Long, D> {
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        @Query("INSERT INTO transaction_record (id) VALUES (:id)")
        void insertOnItsOwn(@com.landawn.abacus.jdbc.annotation.Bind("id") long id) throws SQLException;
    }

    interface RedeclaringDao extends TransactionalInsertDao<RedeclaringDao> {
        @Override
        @Query("INSERT INTO transaction_record (id) VALUES (:id)")
        void insertOnItsOwn(@com.landawn.abacus.jdbc.annotation.Bind("id") long id) throws SQLException;
    }

    @com.landawn.abacus.jdbc.annotation.DaoConfig(callGenerateIdForInsertIfIdNotSet = true)
    interface FailingIdGeneratorDao extends CrudDao<GeneratedRecord, Long, FailingIdGeneratorDao> {
        AtomicBoolean FAIL_NEXT = new AtomicBoolean();
        // The number of generateId() calls that succeed before one fails; negative: none fails.
        AtomicInteger FAIL_AFTER = new AtomicInteger(-1);

        @Override
        default Long generateId() {
            if (FAIL_NEXT.getAndSet(false) || FAIL_AFTER.getAndDecrement() == 0) {
                throw new IllegalStateException("no ID available");
            }

            return 1000L;
        }
    }

    interface FailingSetterDao extends CrudDao<FailingSetterRecord, Long, FailingSetterDao> {
        @Query("INSERT INTO generated_record (name) VALUES (:name)")
        Long insertOne(FailingSetterRecord record) throws SQLException;

        @Query(value = "INSERT INTO generated_record (name) VALUES (:name)", batch = true)
        List<Long> insertAll(List<FailingSetterRecord> records) throws SQLException;
    }

    /** Read and written through its methods, whose ID setter fails once armed, after assigning the ID. */
    @Table("generated_record")
    @com.landawn.abacus.annotation.AccessFieldByMethod
    public static class FailingSetterRecord {
        /** The number of non-null IDs set before the setter fails; negative: it does not fail. */
        static final AtomicInteger FAIL_AT = new AtomicInteger(-1);
        /** How many reads of the ID fail, one after the other, right after a failed setter. */
        static final AtomicInteger GETTER_FAILURES_AFTER_SETTER = new AtomicInteger();
        private static final AtomicInteger getterFailures = new AtomicInteger();

        @Id
        Long id;
        private String name;

        static FailingSetterRecord named(final String name) {
            final FailingSetterRecord record = new FailingSetterRecord();
            record.name = name;
            return record;
        }

        public Long getId() {
            if (getterFailures.get() > 0 && getterFailures.getAndDecrement() > 0) {
                throw new IllegalStateException("the ID getter failed");
            }

            return id;
        }

        public void setId(final Long id) {
            this.id = id;

            if (id != null && FAIL_AT.get() >= 0 && FAIL_AT.getAndDecrement() == 0) {
                getterFailures.set(GETTER_FAILURES_AFTER_SETTER.get());
                throw new IllegalStateException("the ID setter failed after assigning the ID");
            }
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }

        static void resetFailures() {
            FAIL_AT.set(-1);
            GETTER_FAILURES_AFTER_SETTER.set(0);
            getterFailures.set(0);
        }
    }

    interface PartialKeyDao extends CrudDao<PartialKeyRecord, PartialKeyId, PartialKeyDao> {
    }

    public static class PartialKeyId {
        private Long tenantId;
        private Long rowId;

        public Long getTenantId() {
            return tenantId;
        }

        public void setTenantId(final Long tenantId) {
            this.tenantId = tenantId;
        }

        public Long getRowId() {
            return rowId;
        }

        public void setRowId(final Long rowId) {
            this.rowId = rowId;
        }
    }

    /** A composite key the database generates, read and written through methods: the setter of its second part fails once armed. */
    @Table("partial_key_record")
    @com.landawn.abacus.annotation.AccessFieldByMethod
    public static class PartialKeyRecord {
        static final AtomicBoolean FAIL_ROW_ID = new AtomicBoolean();

        @Id
        Long tenantId;
        @Id
        Long rowId;
        private String name;

        public Long getTenantId() {
            return tenantId;
        }

        public void setTenantId(final Long tenantId) {
            this.tenantId = tenantId;
        }

        public Long getRowId() {
            return rowId;
        }

        public void setRowId(final Long rowId) {
            if (rowId != null && FAIL_ROW_ID.getAndSet(false)) {
                throw new IllegalStateException("the setter of the second part failed");
            }

            this.rowId = rowId;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }
    }

    interface OverridingDataSourceGeneratedDao extends CrudDao<GeneratedRecord, Long, OverridingDataSourceGeneratedDao> {
        java.util.concurrent.atomic.AtomicReference<javax.sql.DataSource> TARGET = new java.util.concurrent.atomic.AtomicReference<>();

        @Override
        default javax.sql.DataSource dataSource() {
            return TARGET.get();
        }
    }

    interface PlainGeneratedDao extends com.landawn.abacus.jdbc.dao.Dao<GeneratedRecord, PlainGeneratedDao> {
    }

    interface UncheckedPlainGeneratedDao extends com.landawn.abacus.jdbc.dao.UncheckedDao<GeneratedRecord, UncheckedPlainGeneratedDao> {
    }

    interface GeneratedDao extends CrudDao<GeneratedRecord, Long, GeneratedDao> {
        @Query("INSERT INTO generated_record (id, name) VALUES (:id, :name)")
        Long insertOneWithId(GeneratedRecord record) throws SQLException;

        @Query(value = "INSERT INTO generated_record (id, name) VALUES (:id, :name)", batch = true)
        List<Long> insertAllWithIds(List<GeneratedRecord> records) throws SQLException;

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
