package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.JoinedBy;
import com.landawn.abacus.annotation.Table;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.annotation.PerfLog;
import com.landawn.abacus.jdbc.annotation.Query;
import com.landawn.abacus.jdbc.annotation.SqlLogEnabled;
import com.landawn.abacus.jdbc.annotation.Transactional;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.jdbc.dao.Dao;
import com.landawn.abacus.jdbc.dao.JoinEntityHelper;
import com.landawn.abacus.jdbc.dao.UncheckedDao;
import com.landawn.abacus.jdbc.dao.UncheckedJoinEntityHelper;
import com.landawn.abacus.util.Throwables;

/**
 * When a transaction begun by {@link JdbcUtil#beginTransaction(DataSource)} (directly, or by DAO operations,
 * {@code @Transactional} methods and {@code callInTransaction}/{@code runInTransaction}) participates in a Spring-managed
 * transaction and when it runs as a transaction of its own: it participates only in a transaction that owns the data
 * source's connection, never in a connection Spring bound merely for synchronization. A participating transaction leaves
 * the outcome to Spring - its failure marks the Spring transaction rollback-only - and generated IDs are put back if Spring
 * rolls back.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpringTransactionParticipationTest extends TestBase {

    private final JdbcDataSource dataSourceA = new JdbcDataSource();
    private final JdbcDataSource dataSourceB = new JdbcDataSource();
    // Hands out connections with auto-commit off, like a pool configured with autoCommit=false.
    private DataSource autoCommitOffA;
    private Connection keeperA;
    private Connection keeperB;

    private ItemDao itemDaoA;
    private LoggingItemDao loggingItemDaoA;
    private ItemDao itemDaoAutoCommitOff;
    private LoggingItemDao loggingItemDaoAutoCommitOff;
    private ItemDao itemDaoB;
    private OwnerDao ownerDao;
    private UncheckedOwnerDao uncheckedOwnerDao;

    @BeforeAll
    void createDatabases() throws SQLException {
        dataSourceA.setURL("jdbc:h2:mem:spring_participation_a_" + UUID.randomUUID());
        dataSourceB.setURL("jdbc:h2:mem:spring_participation_b_" + UUID.randomUUID());
        autoCommitOffA = new AutoCommitOffDataSource(dataSourceA);
        keeperA = dataSourceA.getConnection();
        keeperB = dataSourceB.getConnection();

        try (Statement statement = keeperA.createStatement()) {
            statement.execute("CREATE TABLE spring_item (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(10))");
            statement.execute("CREATE TABLE spring_owner (id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE spring_note (id BIGINT PRIMARY KEY, owner_id BIGINT, pinned_owner_id BIGINT)");
            statement.execute("CREATE TABLE spring_tag (id BIGINT PRIMARY KEY, owner_id BIGINT)");
        }

        try (Statement statement = keeperB.createStatement()) {
            statement.execute("CREATE TABLE spring_item (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(10))");
        }

        itemDaoA = JdbcUtil.createDao(ItemDao.class, dataSourceA);
        loggingItemDaoA = JdbcUtil.createDao(LoggingItemDao.class, dataSourceA);
        itemDaoAutoCommitOff = JdbcUtil.createDao(ItemDao.class, autoCommitOffA);
        loggingItemDaoAutoCommitOff = JdbcUtil.createDao(LoggingItemDao.class, autoCommitOffA);
        itemDaoB = JdbcUtil.createDao(ItemDao.class, dataSourceB);
        ownerDao = JdbcUtil.createDao(OwnerDao.class, dataSourceA);
        uncheckedOwnerDao = JdbcUtil.createDao(UncheckedOwnerDao.class, dataSourceA);
    }

    @BeforeEach
    void resetRows() throws SQLException {
        try (Statement statement = keeperA.createStatement()) {
            statement.executeUpdate("DELETE FROM spring_item");
            statement.executeUpdate("DELETE FROM spring_owner");
            statement.executeUpdate("DELETE FROM spring_note");
            statement.executeUpdate("DELETE FROM spring_tag");
            // H2 identities don't roll back: without the restart, the generated IDs of earlier tests reach the explicit IDs some
            // tests insert (100, 500) once enough tests have run.
            statement.executeUpdate("ALTER TABLE spring_item ALTER COLUMN id RESTART WITH 1");
        }

        try (Statement statement = keeperB.createStatement()) {
            statement.executeUpdate("DELETE FROM spring_item");
            statement.executeUpdate("ALTER TABLE spring_item ALTER COLUMN id RESTART WITH 1");
        }
    }

    @AfterAll
    void closeDatabases() throws SQLException {
        keeperA.close();
        keeperB.close();
    }

    // A non-transactional Spring scope (SUPPORTS) still binds the connection for synchronization. With auto-commit off by
    // default, that bound connection looked like a transaction, so REQUIRED/MANDATORY ran without a transaction of their own
    // and the insert was rolled back when Spring released the connection: the call returned normally, yet nothing persisted.
    // MANDATORY is now rejected there: the synchronized connection is not a transaction (JdbcUtil.isInTransaction).
    @ParameterizedTest(name = "{0}, logging={1}")
    @MethodSource("joinCases")
    void requiredOrMandatory_inSynchronizedScopeWithAutoCommitOff_commitsATransactionOfItsOwn(final Propagation propagation, final boolean logging) {
        final ItemDaoBase<?> dao = logging ? loggingItemDaoAutoCommitOff : itemDaoAutoCommitOff;

        supportsTemplate(autoCommitOffA).executeWithoutResult(status -> {
            final Connection bound = DataSourceUtils.getConnection(autoCommitOffA);

            try {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertFalse(bound.getAutoCommit());
                assertFalse(JdbcUtil.isInTransaction(autoCommitOffA));

                if (propagation == Propagation.MANDATORY) {
                    assertThrows(IllegalStateException.class, () -> invoke(dao, propagation, () -> insertItem(autoCommitOffA, "a")));
                } else {
                    assertEquals(42, invoke(dao, propagation, () -> insertItem(autoCommitOffA, "a")));
                }
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                DataSourceUtils.releaseConnection(bound, autoCommitOffA);
            }
        });

        assertEquals(propagation == Propagation.MANDATORY ? 0 : 1, countItems(keeperA));
    }

    // The own transaction of a REQUIRED method keeps its isolation level.
    @ParameterizedTest(name = "logging={0}")
    @ValueSource(booleans = { false, true })
    void required_inSynchronizedScope_appliesTheIsolationLevelOfItsOwnTransaction(final boolean logging) {
        final ItemDaoBase<?> dao = logging ? loggingItemDaoAutoCommitOff : itemDaoAutoCommitOff;

        supportsTemplate(autoCommitOffA).executeWithoutResult(status -> {
            final Connection bound = DataSourceUtils.getConnection(autoCommitOffA);

            try {
                assertEquals(42, dao.requiredSerializable(() -> {
                    try (PreparedQuery query = JdbcUtil.prepareQuery(autoCommitOffA, "SELECT 1")) {
                        query.configureStatement(
                                statement -> assertEquals(Connection.TRANSACTION_SERIALIZABLE, statement.getConnection().getTransactionIsolation()));
                        assertEquals(1, query.queryForInt().orElseThrow());
                    }
                    return 42;
                }));
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                DataSourceUtils.releaseConnection(bound, autoCommitOffA);
            }
        });
    }

    // The batch's own transaction commits it in a non-transactional scope (it was skipped, and the rows were lost).
    @ParameterizedTest(name = "{0}")
    @EnumSource(BatchInsert.class)
    void batchInsert_inSynchronizedScopeWithAutoCommitOff_commitsATransactionOfItsOwn(final BatchInsert batchInsert) {
        final List<Item> items = batchInsert.newItems();

        supportsTemplate(autoCommitOffA).executeWithoutResult(status -> {
            final Connection bound = DataSourceUtils.getConnection(autoCommitOffA);

            try {
                batchInsert.run(itemDaoAutoCommitOff, items);
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                DataSourceUtils.releaseConnection(bound, autoCommitOffA);
            }
        });

        assertEquals(items.size(), countItems(keeperA));
        items.forEach(item -> assertNotNull(item.getId()));
    }

    // A transaction on data source B doesn't own A's connection, which Spring binds for synchronization only: REQUIRED on A
    // commits a transaction of its own (it joined, and A's row was lost), while REQUIRED on B joins B's transaction.
    @ParameterizedTest(name = "logging={0}")
    @ValueSource(booleans = { false, true })
    void required_onDataSourceWithoutATransaction_commitsItsOwnWhileATransactionRunsOnAnotherDataSource(final boolean logging) {
        final ItemDaoBase<?> daoA = logging ? loggingItemDaoAutoCommitOff : itemDaoAutoCommitOff;

        new TransactionTemplate(new DataSourceTransactionManager(dataSourceB)).executeWithoutResult(status -> {
            final Connection boundA = DataSourceUtils.getConnection(autoCommitOffA);

            try {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertFalse(boundA.getAutoCommit());
                assertEquals(42, daoA.required(() -> insertItem(autoCommitOffA, "a")));
                assertEquals(42, itemDaoB.required(() -> insertItem(dataSourceB, "b")));
                batchInsertTwoChunks(itemDaoAutoCommitOff);
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                DataSourceUtils.releaseConnection(boundA, autoCommitOffA);
            }

            status.setRollbackOnly();
        });

        assertEquals(3, countItems(keeperA), "A's work committed by its own transactions");
        assertEquals(0, countItems(keeperB), "B's work rolled back with B's transaction");
    }

    // Spring's connection holders, simulated: DataSourceTransactionManager marks its holder transaction-active, a JPA/Hibernate
    // transaction manager wraps its connection in its own ConnectionHandle (and can't set the flag), and DataSourceUtils binds a
    // SimpleConnectionHandle for mere synchronization. Only the first two are transactions. The method joins the first; it refuses
    // the second, whose holder no bound JPA transaction owns, so a rollback could not reach that transaction
    // (SpringJpaTransactionParticipationTest covers identifiable JPA transactions).
    @ParameterizedTest(name = "{0}, {1}, logging={2}")
    @MethodSource("holderCases")
    void requiredOrMandatory_joinOnlyConnectionsATransactionOwns(final HolderKind holderKind, final Propagation propagation, final boolean logging)
            throws SQLException {
        final ItemDaoBase<?> dao = logging ? loggingItemDaoA : itemDaoA;

        try (Connection connection = dataSourceA.getConnection()) {
            connection.setAutoCommit(false);
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.bindResource(dataSourceA, holderKind.holder(connection));

            try {
                assertEquals(holderKind.joins, JdbcUtil.isInTransaction(dataSourceA));

                if (holderKind == HolderKind.JPA_CONNECTION_HANDLE) {
                    final IllegalStateException e = assertThrows(IllegalStateException.class, () -> invoke(dao, propagation, () -> 42));
                    assertTrue(e.getMessage().contains("no active JPA transaction owning the connection"), e.getMessage());
                    return;
                }

                if (!holderKind.joins && propagation == Propagation.MANDATORY) {
                    // A connection bound merely for synchronization is not a transaction.
                    assertThrows(IllegalStateException.class, () -> invoke(dao, propagation, () -> 42));
                    return;
                }

                assertEquals(42, invoke(dao, propagation, () -> {
                    try (PreparedQuery query = JdbcUtil.prepareQuery(dataSourceA, "INSERT INTO spring_item (name) VALUES ('a')")) {
                        query.configureStatement(statement -> assertSame(connection, statement.getConnection()));
                        assertEquals(1, query.update());
                    }
                    return 42;
                }));

                if (holderKind.joins) {
                    assertEquals(0, countItems(keeperA), "a joined transaction is left uncommitted");
                    connection.rollback();
                    assertEquals(0, countItems(keeperA));
                } else {
                    assertEquals(1, countItems(keeperA), "the method's own transaction committed the work");
                }
            } finally {
                TransactionSynchronizationManager.unbindResource(dataSourceA);
                TransactionSynchronizationManager.setActualTransactionActive(false);
                TransactionSynchronizationManager.clearSynchronization();
            }
        }
    }

    // REQUIRED calls batchUpsert(two rows, batch size 1), whose transaction scope used to be outermost on Spring's connection
    // (REQUIRED joins Spring without a scope of its own) and committed it, so the rows survived Spring's rollback.
    @ParameterizedTest(name = "inRequiredMethod={0}, springRollsBack={1}")
    @MethodSource("rollbackCases")
    void batchUpsert_inSpringTransaction_leavesTheOutcomeToSpringAndPutsBackIdsOnRollback(final boolean inRequiredMethod, final boolean springRollsBack) {
        final List<Item> items = List.of(item(null, "a"), item(null, "b"));
        final SQLException failure = new SQLException("work failed after batchUpsert");

        transactionTemplate().executeWithoutResult(status -> {
            final Connection spring = DataSourceUtils.getConnection(dataSourceA);

            try {
                final Throwables.Supplier<Integer, SQLException> work = () -> {
                    assertEquals(2, itemDaoA.batchUpsert(items, 1).size());
                    items.forEach(item -> assertNotNull(item.getId()));
                    assertEquals(2, countItems(spring));
                    assertEquals(0, countItems(keeperA), "Spring's connection is not committed by batchUpsert");

                    if (springRollsBack && inRequiredMethod) {
                        throw failure;
                    }

                    return 42;
                };

                if (!inRequiredMethod) {
                    work.get();
                } else if (springRollsBack) {
                    assertSame(failure, assertThrows(SQLException.class, () -> itemDaoA.required(work)));
                } else {
                    assertEquals(42, itemDaoA.required(work));
                }
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                DataSourceUtils.releaseConnection(spring, dataSourceA);
            }

            if (springRollsBack) {
                status.setRollbackOnly();
            }
        });

        assertEquals(springRollsBack ? 0 : 2, countItems(keeperA));
        items.forEach(item -> assertEquals(springRollsBack, item.getId() == null, "IDs put back only if Spring rolls back"));
    }

    // A batchUpsert that fails inside a Spring transaction throws its own failure and marks the Spring transaction rollback-only:
    // Spring's commit then rolls back (UnexpectedRollbackException), including the work done before the call, and the inserted
    // entity's ID is put back (as soon as the transaction is marked). (The failed operation's partial work used to be committed with the rest.)
    @ParameterizedTest(name = "springRollsBack={0}")
    @ValueSource(booleans = { false, true })
    void batchUpsert_failingInSpringTransaction_throwsItsOwnFailureAndMarksTheTransactionRollbackOnly(final boolean springRollsBack) throws SQLException {
        try (Statement statement = keeperA.createStatement()) {
            statement.executeUpdate("INSERT INTO spring_item (id, name) VALUES (100, 'x')");
        }

        final Item existing = item(100L, "much too long");
        final Item added = item(null, "n");

        final Runnable work = () -> transactionTemplate().executeWithoutResult(status -> {
            final Connection spring = DataSourceUtils.getConnection(dataSourceA);

            try {
                insertItem(dataSourceA, "s");
                final SQLException thrown = assertThrows(SQLException.class, () -> itemDaoA.batchUpsert(List.of(added, existing), 1));
                assertTrue(thrown.getMessage().toLowerCase().contains("too long"), thrown.getMessage());
                // Marked rollback-only, the transaction can only end rolled back: the inserted ID is put back right away.
                assertNull(added.getId());
                assertTrue(status.isRollbackOnly());
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                DataSourceUtils.releaseConnection(spring, dataSourceA);
            }

            if (springRollsBack) {
                status.setRollbackOnly();
            }
        });

        if (springRollsBack) {
            work.run();
        } else {
            assertThrows(UnexpectedRollbackException.class, work::run);
        }

        assertEquals(1, countItems(keeperA));
        assertEquals("x", queryName(100L));
        assertNull(added.getId());
    }

    // Inside a Spring transaction the pre-insert ID snapshot was taken before the elements were validated, so a null element
    // failed with a NullPointerException instead of the IllegalArgumentException thrown outside a transaction.
    @Test
    void batchInserts_withANullElement_inSpringTransaction_rejectItAsAnIllegalArgument() {
        final List<Item> items = Arrays.asList(item(null, "a"), null);

        transactionTemplate().executeWithoutResult(status -> {
            assertThrows(IllegalArgumentException.class, () -> itemDaoA.batchInsert(items, 2));
            assertThrows(IllegalArgumentException.class, () -> itemDaoA.batchInsert(items, List.of("name"), 2));
            assertThrows(IllegalArgumentException.class, () -> itemDaoA.batchInsert("INSERT INTO spring_item (name) VALUES (:name)", items, 2));
            assertThrows(IllegalArgumentException.class, () -> itemDaoA.insertInBatches(items));
            status.setRollbackOnly();
        });

        assertNull(items.get(0).getId());
        assertEquals(0, countItems(keeperA));
    }

    // Built-in multi-statement inserts that join a Spring transaction write the generated IDs back right away; they are put back
    // if Spring rolls the transaction back (rows that no longer exist) and kept if it commits.
    @ParameterizedTest(name = "{0}, springRollsBack={1}")
    @MethodSource("batchInsertRollbackCases")
    void batchInsert_inSpringTransaction_putsBackIdsOnlyIfSpringRollsBack(final BatchInsert batchInsert, final boolean springRollsBack) {
        final List<Item> items = batchInsert.newItems();
        final List<Long> idsBefore = items.stream().map(Item::getId).toList();

        transactionTemplate().executeWithoutResult(status -> {
            try {
                batchInsert.run(itemDaoA, items);
                assertEquals(0, countItems(keeperA), "Spring's connection is not committed by the batch");
                items.forEach(item -> assertNotNull(item.getId()));
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }

            if (springRollsBack) {
                status.setRollbackOnly();
            }
        });

        assertEquals(springRollsBack ? 0 : items.size(), countItems(keeperA));

        if (springRollsBack) {
            assertEquals(idsBefore, items.stream().map(Item::getId).toList());
        } else {
            items.forEach(item -> assertNotNull(item.getId()));
        }
    }

    // Deleting the joins of several properties ran in a transaction scope of its own, outermost on Spring's connection, and
    // committed it: the rows stayed deleted after Spring's rollback.
    @ParameterizedTest(name = "{0}, unchecked={1}")
    @MethodSource("joinDeleteCases")
    void multiPropertyJoinDeletes_inSpringTransaction_leaveTheOutcomeToSpring(final JoinDelete joinDelete, final boolean unchecked) throws SQLException {
        try (Statement statement = keeperA.createStatement()) {
            statement.executeUpdate("INSERT INTO spring_owner (id) VALUES (1), (2)");
            statement
                    .executeUpdate("INSERT INTO spring_note (id, owner_id, pinned_owner_id) VALUES (11, 1, NULL), (12, NULL, 1), (21, 2, NULL), (22, NULL, 2)");
            statement.executeUpdate("INSERT INTO spring_tag (id, owner_id) VALUES (31, 1), (32, 2)");
        }

        transactionTemplate().executeWithoutResult(status -> {
            final Connection spring = DataSourceUtils.getConnection(dataSourceA);

            try {
                assertTrue(joinDelete.run(unchecked ? uncheckedOwnerDao : ownerDao, owner(1), owner(2)) > 0);
                assertTrue(count(spring, "SELECT COUNT(*) FROM spring_note") + count(spring, "SELECT COUNT(*) FROM spring_tag") < 6);
                assertEquals(4, count(keeperA, "SELECT COUNT(*) FROM spring_note"), "Spring's connection is not committed by the deletes");
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                DataSourceUtils.releaseConnection(spring, dataSourceA);
            }

            status.setRollbackOnly();
        });

        assertEquals(4, count(keeperA, "SELECT COUNT(*) FROM spring_note"));
        assertEquals(2, count(keeperA, "SELECT COUNT(*) FROM spring_tag"));
    }

    // Spring participation disabled on the thread: the work doesn't join, even inside a real Spring transaction.
    @ParameterizedTest(name = "logging={0}")
    @ValueSource(booleans = { false, true })
    void required_withSpringParticipationDisabled_doesNotJoin(final boolean logging) {
        final ItemDaoBase<?> dao = logging ? loggingItemDaoA : itemDaoA;

        transactionTemplate().executeWithoutResult(status -> {
            final Connection spring = DataSourceUtils.getConnection(dataSourceA);

            try {
                assertEquals(42, JdbcUtil.callIgnoringSpringTransaction(() -> dao.required(() -> {
                    try (PreparedQuery query = JdbcUtil.prepareQuery(dataSourceA, "INSERT INTO spring_item (name) VALUES ('a')")) {
                        query.configureStatement(statement -> assertNotSame(spring, statement.getConnection()));
                        assertEquals(1, query.update());
                    }
                    return 42;
                })));
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                DataSourceUtils.releaseConnection(spring, dataSourceA);
            }

            status.setRollbackOnly();
        });

        assertEquals(1, countItems(keeperA));
    }

    // JdbcUtil.beginTransaction inside a Spring transaction used to open an outermost scope on Spring's connection and commit it.
    // It now participates: Spring's connection is neither committed nor reconfigured, and a scope that ends without commit()
    // marks the Spring transaction rollback-only, so Spring's commit rolls back everything (UnexpectedRollbackException).
    @ParameterizedTest(name = "scopeCommits={0}")
    @ValueSource(booleans = { false, true })
    void beginTransaction_inSpringTransaction_participatesWithoutCompletingSpringsConnection(final boolean scopeCommits) {
        final Runnable work = () -> transactionTemplate().executeWithoutResult(status -> {
            final Connection spring = DataSourceUtils.getConnection(dataSourceA);

            try {
                insertItem(dataSourceA, "before");
                final int isolation = spring.getTransactionIsolation();
                final SqlTransaction tran = JdbcUtil.beginTransaction(dataSourceA, IsolationLevel.SERIALIZABLE);

                try {
                    assertSame(spring, tran.connection());
                    assertEquals(IsolationLevel.DEFAULT, tran.isolationLevel());
                    assertEquals(isolation, spring.getTransactionIsolation(), "the requested isolation level is ignored");

                    final SqlTransaction nested = JdbcUtil.beginTransaction(dataSourceA);
                    assertSame(tran, nested);

                    try {
                        insertItem(dataSourceA, "inside");
                        nested.commit();
                    } finally {
                        nested.rollbackIfNotCommitted();
                    }

                    if (scopeCommits) {
                        tran.commit();
                    }
                } finally {
                    tran.rollbackIfNotCommitted();
                }

                assertFalse(spring.getAutoCommit());
                assertFalse(spring.isClosed());
                assertEquals(0, countItems(keeperA), "Spring's connection is not committed by the JdbcUtil transaction");
                assertEquals(2, countItems(spring));
                assertEquals(!scopeCommits, status.isRollbackOnly());
                assertEquals(scopeCommits ? Transaction.Status.COMMITTED : Transaction.Status.ROLLED_BACK, tran.status());
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                DataSourceUtils.releaseConnection(spring, dataSourceA);
            }
        });

        if (scopeCommits) {
            work.run();
        } else {
            assertThrows(UnexpectedRollbackException.class, work::run);
        }

        assertEquals(scopeCommits ? 2 : 0, countItems(keeperA));
        assertFalse(JdbcUtil.isInTransaction(dataSourceA));
    }

    // callInTransaction/runInTransaction used to commit Spring's connection. They now participate: Spring commits the work, and a
    // failure marks the Spring transaction rollback-only.
    @ParameterizedTest(name = "{0}, fails={1}")
    @MethodSource("inTransactionCases")
    void callOrRunInTransaction_inSpringTransaction_leaveTheOutcomeToSpring(final InTransaction inTransaction, final boolean fails) {
        final SQLException failure = new SQLException("work failed");
        final Runnable work = () -> transactionTemplate().executeWithoutResult(status -> {
            final Connection spring = DataSourceUtils.getConnection(dataSourceA);

            try {
                if (fails) {
                    assertSame(failure, assertThrows(SQLException.class, () -> inTransaction.run(dataSourceA, spring, failure)));
                } else {
                    inTransaction.run(dataSourceA, spring, null);
                }

                assertEquals(0, countItems(keeperA), "Spring's connection is not committed by " + inTransaction);
                assertEquals(1, countItems(spring));
                assertEquals(fails, status.isRollbackOnly());
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                DataSourceUtils.releaseConnection(spring, dataSourceA);
            }
        });

        if (fails) {
            assertThrows(UnexpectedRollbackException.class, work::run);
        } else {
            work.run();
        }

        assertEquals(fails ? 0 : 1, countItems(keeperA));
    }

    // A batch of several chunks used to run in a transaction of its own on Spring's connection and commit it.
    @ParameterizedTest(name = "large={0}")
    @ValueSource(booleans = { false, true })
    void executeBatchUpdate_inSpringTransaction_leavesTheOutcomeToSpring(final boolean large) {
        final String sql = "INSERT INTO spring_item (name) VALUES (?)";
        final List<List<String>> rows = List.of(List.of("a"), List.of("b"), List.of("c"));

        transactionTemplate().executeWithoutResult(status -> {
            final Connection spring = DataSourceUtils.getConnection(dataSourceA);

            try {
                assertEquals(3L, large ? JdbcUtil.executeLargeBatchUpdate(dataSourceA, sql, rows, 2) : JdbcUtil.executeBatchUpdate(dataSourceA, sql, rows, 2));
                assertEquals(0, countItems(keeperA), "Spring's connection is not committed by the batch");
                assertEquals(3, countItems(spring));
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                DataSourceUtils.releaseConnection(spring, dataSourceA);
            }

            status.setRollbackOnly();
        });

        assertEquals(0, countItems(keeperA));
    }

    // isInTransaction applies the ownership rule (a connection bound for synchronization, or a transaction on another data source,
    // is not a transaction of this data source) and looks through TransactionAwareDataSourceProxy layers to the target's binding.
    @Test
    void isInTransaction_followsTheOwnershipRuleAndSeesThroughTransactionAwareProxies() {
        final DataSource proxy = new TransactionAwareDataSourceProxy(new TransactionAwareDataSourceProxy(dataSourceA));

        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.isInTransaction(null));
        assertFalse(JdbcUtil.isInTransaction(dataSourceA));

        supportsTemplate(dataSourceA).executeWithoutResult(status -> {
            final Connection bound = DataSourceUtils.getConnection(dataSourceA);

            try {
                assertFalse(JdbcUtil.isInTransaction(dataSourceA));
                assertFalse(JdbcUtil.isInTransaction(proxy));
            } finally {
                DataSourceUtils.releaseConnection(bound, dataSourceA);
            }
        });

        new TransactionTemplate(new DataSourceTransactionManager(dataSourceB)).executeWithoutResult(status -> {
            final Connection boundA = DataSourceUtils.getConnection(dataSourceA);

            try {
                assertTrue(JdbcUtil.isInTransaction(dataSourceB));
                assertFalse(JdbcUtil.isInTransaction(dataSourceA));
            } finally {
                DataSourceUtils.releaseConnection(boundA, dataSourceA);
            }
        });

        transactionTemplate().executeWithoutResult(status -> {
            assertTrue(JdbcUtil.isInTransaction(dataSourceA));
            assertTrue(JdbcUtil.isInTransaction(proxy));
            assertFalse(JdbcUtil.callIgnoringSpringTransaction(() -> JdbcUtil.isInTransaction(dataSourceA)));
        });
    }

    // Through a TransactionAwareDataSourceProxy the transaction begun by JdbcUtil found no holder under the proxy and committed the
    // proxy's connection, which is Spring's.
    @Test
    void beginTransaction_onTransactionAwareProxy_participatesInTheTargetsSpringTransaction() {
        final DataSource proxy = new TransactionAwareDataSourceProxy(dataSourceA);

        transactionTemplate().executeWithoutResult(status -> {
            final SqlTransaction tran = JdbcUtil.beginTransaction(proxy);

            try {
                insertItem(proxy, "p");
                tran.commit();
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                tran.rollbackIfNotCommitted();
            }

            assertEquals(0, countItems(keeperA), "Spring's connection is not committed through the proxy");
            status.setRollbackOnly();
        });

        assertEquals(0, countItems(keeperA));
    }

    // A query run by callAsync on a Spring-bound connection was closed on the executor thread, where Spring finds no binding:
    // DataSourceUtils closed Spring's connection in the middle of its transaction.
    @Test
    void callAsync_inSpringTransaction_leavesSpringsConnectionToTheTransaction() {
        transactionTemplate().executeWithoutResult(status -> {
            final Connection spring = DataSourceUtils.getConnection(dataSourceA);

            try {
                insertItem(dataSourceA, "before");
                final PreparedQuery query = JdbcUtil.prepareQuery(dataSourceA, "SELECT COUNT(*) FROM spring_item");
                assertEquals(1, query.callAsync(q -> q.queryForInt().orElseThrow()).get());
                assertFalse(spring.isClosed());
                insertItem(dataSourceA, "after");
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            } finally {
                DataSourceUtils.releaseConnection(spring, dataSourceA);
            }
        });

        assertEquals(2, countItems(keeperA));
    }

    // Single-row inserts that join a Spring transaction (insert, a @Query insert, a batch of one) wrote the generated ID back and
    // kept it after Spring rolled the row back. The ID is now put back if Spring rolls back.
    @ParameterizedTest(name = "springRollsBack={0}")
    @ValueSource(booleans = { false, true })
    void singleRowInserts_inSpringTransaction_putBackIdsOnlyIfSpringRollsBack(final boolean springRollsBack) {
        final Item inserted = item(null, "i");
        final Item queryInserted = item(null, "q");
        final Item batchOfOne = item(null, "b");

        transactionTemplate().executeWithoutResult(status -> {
            try {
                assertNotNull(itemDaoA.insert(inserted));
                assertNotNull(itemDaoA.insertOne(queryInserted));
                assertEquals(1, itemDaoA.batchInsert(List.of(batchOfOne)).size());
                assertEquals(0, countItems(keeperA));
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }

            List.of(inserted, queryInserted, batchOfOne).forEach(item -> assertNotNull(item.getId()));

            if (springRollsBack) {
                status.setRollbackOnly();
            }
        });

        assertEquals(springRollsBack ? 0 : 3, countItems(keeperA));
        List.of(inserted, queryInserted, batchOfOne).forEach(item -> assertEquals(springRollsBack, item.getId() == null));
    }

    // A rollback to a savepoint (PROPAGATION_NESTED) ran no completion callback, so the IDs of the inserts in the nested
    // transaction (insert, a @Query insert, a participating multi-row batchInsert) were kept although their rows were gone,
    // while the enclosing transaction committed its own row. They are put back through Spring's savepoint callbacks (6.2+) now,
    // right when the nested transaction rolls back; the enclosing transaction's insert keeps its ID.
    @Test
    void nestedRollback_inSpringTransaction_putsBackOnlyTheIdsOfTheNestedInserts() {
        final DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSourceA);
        final Item outer = item(null, "outer");
        final Item nested = item(null, "nested");
        final Item nestedByQuery = item(null, "nq");
        final List<Item> nestedBatch = List.of(item(null, "nb1"), item(null, "nb2"));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            try {
                assertNotNull(itemDaoA.insert(outer));
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }

            nestedTemplate(transactionManager).executeWithoutResult(nestedStatus -> {
                assertTrue(nestedStatus.hasSavepoint(), "a nested transaction of the same manager runs under a savepoint");

                try {
                    assertNotNull(itemDaoA.insert(nested));
                    assertNotNull(itemDaoA.insertOne(nestedByQuery));
                    assertEquals(2, itemDaoA.batchInsert(nestedBatch).size());
                } catch (final SQLException e) {
                    throw new IllegalStateException(e);
                }

                nestedStatus.setRollbackOnly();
            });

            assertNull(nested.getId(), "put back when the nested transaction rolled back to its savepoint");
            assertNull(nestedByQuery.getId());
            nestedBatch.forEach(item -> assertNull(item.getId()));
            assertNotNull(outer.getId(), "the enclosing transaction's insert is not affected");
        });

        assertEquals(1, countItems(keeperA), "the nested rows were rolled back to the savepoint, the enclosing one committed");
        assertNotNull(outer.getId());
        assertNull(nested.getId());
    }

    // The nested transaction commits (its savepoint is released) and the enclosing one rolls back: the IDs of both follow the
    // enclosing transaction.
    @Test
    void nestedCommit_inSpringTransactionThatRollsBack_putsBackTheIdsOfBoth() {
        final DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSourceA);
        final Item outer = item(null, "outer");
        final Item nested = item(null, "nested");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            try {
                assertNotNull(itemDaoA.insert(outer));
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }

            nestedTemplate(transactionManager).executeWithoutResult(nestedStatus -> {
                try {
                    assertNotNull(itemDaoA.insert(nested));
                } catch (final SQLException e) {
                    throw new IllegalStateException(e);
                }
            });

            assertNotNull(nested.getId(), "kept: the nested transaction committed");
            status.setRollbackOnly();
        });

        assertEquals(0, countItems(keeperA));
        assertNull(outer.getId());
        assertNull(nested.getId());
    }

    // The first insert of the Spring transaction runs inside the nested transaction, so the synchronization is registered after
    // the savepoint was created and holds no mark for it: all of its actions are the nested work and are put back. A later
    // insert of the enclosing transaction, and a second nested transaction that commits, keep their IDs.
    @Test
    void nestedRollback_withTheSynchronizationRegisteredInsideTheNestedTransaction_putsBackItsIds() {
        final DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSourceA);
        final Item nested = item(null, "nested");
        final Item outer = item(null, "outer");
        final Item secondNested = item(null, "nested2");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            nestedTemplate(transactionManager).executeWithoutResult(nestedStatus -> {
                try {
                    assertNotNull(itemDaoA.insert(nested));
                } catch (final SQLException e) {
                    throw new IllegalStateException(e);
                }

                nestedStatus.setRollbackOnly();
            });

            assertNull(nested.getId());

            try {
                assertNotNull(itemDaoA.insert(outer));
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }

            nestedTemplate(transactionManager).executeWithoutResult(nestedStatus -> {
                try {
                    assertNotNull(itemDaoA.insert(secondNested));
                } catch (final SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
        });

        assertEquals(2, countItems(keeperA));
        assertNull(nested.getId());
        assertNotNull(outer.getId());
        assertNotNull(secondNested.getId());
    }

    // A nested transaction inside a nested transaction: rolling back the inner one puts back its IDs only; the outer nested
    // transaction and the enclosing one commit theirs.
    @Test
    void nestedRollback_insideANestedTransaction_putsBackOnlyTheInnerIds() {
        final DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSourceA);
        final Item outer = item(null, "outer");
        final Item nested = item(null, "nested");
        final Item inner = item(null, "inner");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            try {
                assertNotNull(itemDaoA.insert(outer));
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }

            nestedTemplate(transactionManager).executeWithoutResult(nestedStatus -> {
                try {
                    assertNotNull(itemDaoA.insert(nested));
                } catch (final SQLException e) {
                    throw new IllegalStateException(e);
                }

                nestedTemplate(transactionManager).executeWithoutResult(innerStatus -> {
                    try {
                        assertNotNull(itemDaoA.insert(inner));
                    } catch (final SQLException e) {
                        throw new IllegalStateException(e);
                    }

                    innerStatus.setRollbackOnly();
                });

                assertNull(inner.getId());
                assertNotNull(nested.getId());
            });
        });

        assertEquals(2, countItems(keeperA));
        assertNotNull(outer.getId());
        assertNotNull(nested.getId());
        assertNull(inner.getId());
    }

    // A programmatic savepoint stays usable after a rollback to it. The second rollback to it put back every ID the
    // synchronization still held, including the enclosing transaction's insert before the savepoint, whose row commits.
    @Test
    void repeatedRollbackToAProgrammaticSavepoint_putsBackOnlyTheIdsSinceIt() {
        final Item before = item(null, "before");
        final Item first = item(null, "first");
        final Item second = item(null, "second");

        transactionTemplate().executeWithoutResult(status -> {
            try {
                assertNotNull(itemDaoA.insert(before));
                final Object savepoint = status.createSavepoint();

                assertNotNull(itemDaoA.insert(first));
                status.rollbackToSavepoint(savepoint);
                assertNull(first.getId());
                assertNotNull(before.getId());

                assertNotNull(itemDaoA.insert(second));
                status.rollbackToSavepoint(savepoint);
                assertNull(second.getId());
                assertNotNull(before.getId(), "inserted before the savepoint: its row survives both rollbacks");

                status.releaseSavepoint(savepoint);
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }
        });

        assertEquals(1, countItems(keeperA));
        assertNotNull(before.getId());
        assertNull(first.getId());
        assertNull(second.getId());
    }

    // A participating SqlTransaction rolled back inside a PROPAGATION_NESTED scope marks the whole Spring transaction
    // rollback-only (the connection holder), as Spring does for one of its own participating scopes that fails inside a nested
    // one - the mark is not confined to the nested savepoint scope - and puts back right away the IDs of the inserts since the
    // nested savepoint, whose scope can only roll back; the enclosing transaction's earlier insert keeps its ID until the
    // transaction's outcome is known. The nested scope's commit then fails with UnexpectedRollbackException (Spring rolls back
    // to the savepoint first); left unhandled, it rolls back the enclosing transaction too: no row remains and no ID is kept.
    @Test
    void participatingRollback_insideANestedTransaction_marksTheWholeSpringTransactionRollbackOnly() {
        final DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSourceA);
        final Item outer = item(null, "outer");
        final Item nested = item(null, "nested");
        final Item inside = item(null, "inside");

        assertThrows(UnexpectedRollbackException.class, () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            try {
                assertNotNull(itemDaoA.insert(outer));
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }

            nestedTemplate(transactionManager).executeWithoutResult(nestedStatus -> {
                assertTrue(nestedStatus.hasSavepoint());

                try {
                    assertNotNull(itemDaoA.insert(nested));
                    final SqlTransaction tran = JdbcUtil.beginTransaction(dataSourceA);

                    try {
                        assertNotNull(itemDaoA.insert(inside));
                    } finally {
                        tran.rollbackIfNotCommitted();
                    }

                    assertEquals(Transaction.Status.ROLLED_BACK, tran.status());
                } catch (final SQLException e) {
                    throw new IllegalStateException(e);
                }

                assertNull(inside.getId());
                assertNull(nested.getId());
                assertNotNull(outer.getId(), "inserted before the nested savepoint: left to the transaction's outcome");
                assertTrue(status.isRollbackOnly(), "the connection holder is marked, which the enclosing status reports");
                assertTrue(nestedStatus.isRollbackOnly());
            });
        }), "the nested scope's commit fails with UnexpectedRollbackException, which rolls back the enclosing transaction");

        assertEquals(0, countItems(keeperA));
        assertNull(outer.getId(), "put back when the enclosing transaction completed rolled back");
        assertNull(nested.getId());
        assertNull(inside.getId());
        assertFalse(JdbcUtil.isInTransaction(dataSourceA));
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
    }

    // The same, with the enclosing transaction handling the nested scope's UnexpectedRollbackException: Spring's rollback to the
    // savepoint clears the rollback-only mark (JdbcTransactionObjectSupport.rollbackToSavepoint resets the holder), so the
    // enclosing transaction commits its own row and its insert keeps its ID; the nested inserts' IDs stay put back.
    @Test
    void participatingRollback_insideANestedTransaction_letsTheEnclosingTransactionCommitAfterTheNestedFailureIsHandled() {
        final DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSourceA);
        final Item outer = item(null, "outer");
        final Item nested = item(null, "nested");
        final Item inside = item(null, "inside");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            try {
                assertNotNull(itemDaoA.insert(outer));
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }

            assertThrows(UnexpectedRollbackException.class, () -> nestedTemplate(transactionManager).executeWithoutResult(nestedStatus -> {
                try {
                    assertNotNull(itemDaoA.insert(nested));
                    final SqlTransaction tran = JdbcUtil.beginTransaction(dataSourceA);

                    try {
                        assertNotNull(itemDaoA.insert(inside));
                    } finally {
                        tran.rollbackIfNotCommitted();
                    }
                } catch (final SQLException e) {
                    throw new IllegalStateException(e);
                }

                assertTrue(status.isRollbackOnly());
            }), "the nested scope's commit fails: the mark is not confined to its savepoint");

            assertFalse(status.isRollbackOnly(), "the rollback to the savepoint cleared the mark");
            assertNull(nested.getId());
            assertNull(inside.getId());
            assertNotNull(outer.getId());
        });

        assertEquals(1, countItems(keeperA), "the enclosing transaction committed its row; the nested rows were rolled back to the savepoint");
        assertNull(nested.getId());
        assertNull(inside.getId());
        assertNotNull(outer.getId());
        assertFalse(JdbcUtil.isInTransaction(dataSourceA));
    }

    // In the handled case above, the enclosing transaction's insert commits its row and keeps its ID. The participating rollback
    // used to put it back when it marked the transaction (markRollbackOnly ran every action of the data source at once, as if the
    // whole transaction were doomed); it now puts back only the IDs since the newest savepoint mark and leaves the earlier ones to
    // the transaction's outcome.
    @Test
    void participatingRollback_insideANestedTransaction_keepsTheIdOfTheEnclosingInsertThatCommits() {
        final DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSourceA);
        final Item outer = item(null, "outer");
        final Item inside = item(null, "inside");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            try {
                assertNotNull(itemDaoA.insert(outer));
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }

            assertThrows(UnexpectedRollbackException.class, () -> nestedTemplate(transactionManager).executeWithoutResult(nestedStatus -> {
                final SqlTransaction tran = JdbcUtil.beginTransaction(dataSourceA);

                try {
                    assertNotNull(itemDaoA.insert(inside));
                } catch (final SQLException e) {
                    throw new IllegalStateException(e);
                } finally {
                    tran.rollbackIfNotCommitted();
                }
            }));
        });

        assertEquals(1, countItems(keeperA));
        assertNull(inside.getId());
        assertNotNull(outer.getId(), "its row committed");
    }

    // A participating rollback inside a nested scope of a nested scope: the IDs come back stage by stage, each with the scope
    // that rolls back - the inner work right away (the newest savepoint scope is doomed), the outer nested insert when its own
    // scope rolls back to its savepoint (the inner failure is not handled there), the enclosing insert only when the whole
    // transaction completes rolled back; handled at the enclosing level instead, the enclosing insert commits with its ID.
    @ParameterizedTest(name = "enclosingHandlesTheFailure={0}")
    @ValueSource(booleans = { false, true })
    void participatingRollback_insideANestedTransactionOfANestedTransaction_putsTheIdsBackStageByStage(final boolean enclosingHandlesTheFailure) {
        final DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSourceA);
        final Item outer = item(null, "outer");
        final Item nested = item(null, "nested");
        final Item inner = item(null, "inner");
        final Item inside = item(null, "inside");

        final Runnable enclosing = () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            try {
                assertNotNull(itemDaoA.insert(outer));
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }

            final Runnable nestedScope = () -> nestedTemplate(transactionManager).executeWithoutResult(nestedStatus -> {
                try {
                    assertNotNull(itemDaoA.insert(nested));
                } catch (final SQLException e) {
                    throw new IllegalStateException(e);
                }

                // The inner scope's commit fails with UnexpectedRollbackException (the mark reaches the whole transaction), which
                // is not handled here: the outer nested scope rolls back to its savepoint and rethrows it.
                nestedTemplate(transactionManager).executeWithoutResult(innerStatus -> {
                    try {
                        assertNotNull(itemDaoA.insert(inner));
                        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSourceA);

                        try {
                            assertNotNull(itemDaoA.insert(inside));
                        } finally {
                            tran.rollbackIfNotCommitted();
                        }
                    } catch (final SQLException e) {
                        throw new IllegalStateException(e);
                    }

                    assertNull(inside.getId());
                    assertNull(inner.getId(), "since the newest savepoint: put back right away");
                    assertNotNull(nested.getId(), "before the newest savepoint: left to the outcome");
                    assertNotNull(outer.getId());
                    assertTrue(innerStatus.isRollbackOnly());
                    assertTrue(nestedStatus.isRollbackOnly());
                    assertTrue(status.isRollbackOnly());
                });
            });

            if (enclosingHandlesTheFailure) {
                assertThrows(UnexpectedRollbackException.class, nestedScope::run,
                        "the outer nested scope rolls back to its savepoint and rethrows the inner failure");
                assertNull(nested.getId(), "put back when the outer nested scope rolled back to its savepoint");
                assertNotNull(outer.getId(), "left to the transaction's outcome");
                assertFalse(status.isRollbackOnly());
            } else {
                nestedScope.run();
            }
        });

        if (enclosingHandlesTheFailure) {
            enclosing.run();
            assertEquals(1, countItems(keeperA), "the enclosing transaction committed its row");
            assertNotNull(outer.getId(), "its row committed");
        } else {
            assertThrows(UnexpectedRollbackException.class, enclosing::run);
            assertEquals(0, countItems(keeperA));
            assertNull(outer.getId(), "put back when the transaction completed rolled back");
        }

        assertNull(nested.getId());
        assertNull(inner.getId());
        assertNull(inside.getId());
        assertFalse(JdbcUtil.isInTransaction(dataSourceA));
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
    }

    // A driver whose Connection.setSavepoint returns null (a JDBC violation) makes Spring report a null savepoint to the
    // synchronizations from inside its nested begin, which rejected it with an IllegalArgumentException and so failed the nested
    // begin (and the enclosing transaction with it); Spring itself runs such a nested scope without a savepoint. The null
    // savepoint is ignored now: no mark, the nested scope runs, and its commit commits with the enclosing transaction.
    @Test
    void nestedTransaction_withADriverReturningANullSavepoint_isNotFailedByTheSynchronization() {
        final DataSource nullSavepoints = new DelegatingDataSource(dataSourceA) {
            @Override
            public Connection getConnection() throws SQLException {
                final Connection target = super.getConnection();

                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class }, (proxy, method, args) -> {
                    if ("setSavepoint".equals(method.getName())) {
                        return null;
                    }

                    try {
                        return method.invoke(target, args);
                    } catch (final InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
            }
        };
        final DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(nullSavepoints);
        final ItemDao itemDao = JdbcUtil.createDao(ItemDao.class, nullSavepoints);
        final Item outer = item(null, "outer");
        final Item nested = item(null, "nested");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            try {
                assertNotNull(itemDao.insert(outer)); // registers the synchronization before the nested begin
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }

            assertDoesNotThrow(() -> nestedTemplate(transactionManager).executeWithoutResult(nestedStatus -> {
                assertFalse(nestedStatus.hasSavepoint(), "Spring runs the nested scope without a savepoint");

                try {
                    assertNotNull(itemDao.insert(nested));
                } catch (final SQLException e) {
                    throw new IllegalStateException(e);
                }
            }), "the null savepoint is ignored by the synchronization");
        });

        assertEquals(2, countItems(keeperA));
        assertNotNull(outer.getId());
        assertNotNull(nested.getId());
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
    }

    private static TransactionTemplate nestedTemplate(final DataSourceTransactionManager transactionManager) {
        final TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        return template;
    }

    // The pre-insert IDs are snapshots, so they must be put back newest first. With one synchronization per insert, Spring ran
    // them oldest first: an entity inserted twice (here once directly and once in a participating JdbcUtil scope) kept the ID of
    // its first, rolled-back insert. All of them now share one synchronization per Spring transaction.
    @ParameterizedTest(name = "springRollsBack={0}")
    @ValueSource(booleans = { false, true })
    void repeatedInserts_inSpringTransaction_putBackTheIdTheEntityHadBefore(final boolean springRollsBack) {
        final Item item = item(null, "r");
        final Item other = item(null, "o");

        transactionTemplate().executeWithoutResult(status -> {
            try {
                assertNotNull(itemDaoA.insert(item, List.of("name")));
                assertNotNull(itemDaoA.insert(other));

                final SqlTransaction tran = JdbcUtil.beginTransaction(dataSourceA);

                try {
                    assertNotNull(itemDaoA.insert(item, List.of("name")));
                    tran.commit();
                } finally {
                    tran.rollbackIfNotCommitted();
                }
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }

            assertEquals(1,
                    TransactionSynchronizationManager.getSynchronizations()
                            .stream()
                            .filter(synchronization -> synchronization.getClass().getName().endsWith("$SpringRollbackActions"))
                            .count());

            if (springRollsBack) {
                status.setRollbackOnly();
            }
        });

        assertEquals(springRollsBack ? 0 : 3, countItems(keeperA));
        assertEquals(springRollsBack, item.getId() == null);
        assertEquals(springRollsBack, other.getId() == null);
    }

    // A transaction manager with transaction synchronization disabled still binds its connection holder, but never sets Spring's
    // "actual transaction active" flag. That flag was required, so a JdbcUtil scope ignored the Spring transaction and committed
    // its connection, including the enclosing transaction's earlier insert, which then survived Spring's rollback.
    @Test
    void jdbcUtilScope_inUnsynchronizedSpringTransaction_participatesInsteadOfCommittingIt() {
        unsynchronizedTemplate().executeWithoutResult(status -> {
            try {
                insertItem(dataSourceA, "outer");
                final SqlTransaction tran = JdbcUtil.beginTransaction(dataSourceA);

                try {
                    insertItem(dataSourceA, "inner");
                    tran.commit();
                } finally {
                    tran.rollbackIfNotCommitted();
                }
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }

            status.setRollbackOnly();
        });

        assertEquals(0, countItems(keeperA));
    }

    // Without synchronization, a rollback of the participating scope reaches the Spring transaction through its connection holder.
    @Test
    void jdbcUtilRollback_inUnsynchronizedSpringTransaction_rollsItBack() {
        assertThrows(UnexpectedRollbackException.class, () -> unsynchronizedTemplate().executeWithoutResult(status -> {
            try {
                insertItem(dataSourceA, "outer");
                final SqlTransaction tran = JdbcUtil.beginTransaction(dataSourceA);

                try {
                    insertItem(dataSourceA, "inner");
                } finally {
                    tran.rollbackIfNotCommitted();
                }
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }
        }));

        assertEquals(0, countItems(keeperA));
    }

    // TransactionAspectSupport's current status is that of the innermost declarative (@Transactional) transaction, which needn't
    // own the rolled-back connection: rolling back JdbcUtil work in a programmatic transaction B on another data source marked
    // the enclosing declarative transaction A rollback-only as well. Only B is rolled back now; A, which catches B's failure,
    // commits.
    @Test
    void rollbackInProgrammaticTransactionInsideDeclarativeOne_leavesTheDeclarativeTransactionAlone() {
        final TransactionTemplate templateB = new TransactionTemplate(new DataSourceTransactionManager(dataSourceB));
        final Runnable declarativeWork = () -> {
            try {
                insertItem(dataSourceA, "a");
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }

            assertThrows(UnexpectedRollbackException.class, () -> templateB.executeWithoutResult(status -> {
                final SqlTransaction tran = JdbcUtil.beginTransaction(dataSourceB);

                try {
                    insertItem(dataSourceB, "b");
                } catch (final SQLException e) {
                    throw new IllegalStateException(e);
                } finally {
                    tran.rollbackIfNotCommitted();
                }
            }));
        };

        final org.springframework.aop.framework.ProxyFactory proxyFactory = new org.springframework.aop.framework.ProxyFactory(declarativeWork);
        proxyFactory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(new DataSourceTransactionManager(dataSourceA),
                new org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource()));
        ((Runnable) proxyFactory.getProxy()).run();

        assertEquals(1, countItems(keeperA));
        assertEquals(0, countItems(keeperB));
    }

    // The reviewer's direction with a DataSourceTransactionManager A: a JdbcUtil scope on A's data source, run while another
    // transaction manager's transaction B runs inside A, rolls back. The mark on A's connection holder rolls back A alone (the
    // commit guard used to be registered with B, failing B's commit as well), and the ID is put back right away.
    @Test
    void rollbackOnEnclosingTransactionsDataSource_rollsBackTheEnclosingTransactionOnly() {
        final TransactionTemplate templateB = new TransactionTemplate(new DataSourceTransactionManager(dataSourceB));
        final Item item = item(null, "a2");

        assertThrows(UnexpectedRollbackException.class, () -> new TransactionTemplate(new DataSourceTransactionManager(dataSourceA)).executeWithoutResult(a -> {
            try {
                insertItem(dataSourceA, "a1");
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }

            templateB.executeWithoutResult(b -> {
                try {
                    insertItem(dataSourceB, "b");
                    final SqlTransaction tran = JdbcUtil.beginTransaction(dataSourceA);

                    try {
                        itemDaoA.insert(item);
                    } finally {
                        tran.rollbackIfNotCommitted();
                    }
                } catch (final SQLException e) {
                    throw new IllegalStateException(e);
                }

                assertNull(item.getId());
            });
        }));

        assertEquals(0, countItems(keeperA));
        assertEquals(1, countItems(keeperB));
        assertNull(item.getId());
    }

    // Documented limitation: without transaction synchronization, Spring's rollback can't be observed, so a successful insert
    // keeps its generated ID when Spring rolls back afterwards. The rollback of the insert's own JdbcUtil scope still puts the
    // ID back.
    @Test
    void insertInUnsynchronizedSpringTransaction_keepsItsIdIfSpringRollsBackAfterwards() {
        final Item committedInScope = item(null, "a");
        final Item rolledBackInScope = item(null, "b");

        unsynchronizedTemplate().executeWithoutResult(status -> {
            try {
                final SqlTransaction tran = JdbcUtil.beginTransaction(dataSourceA);

                try {
                    itemDaoA.insert(committedInScope);
                    tran.commit();
                } finally {
                    tran.rollbackIfNotCommitted();
                }

                final SqlTransaction tran2 = JdbcUtil.beginTransaction(dataSourceA);

                try {
                    itemDaoA.insert(rolledBackInScope);
                } finally {
                    tran2.rollbackIfNotCommitted();
                }
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }

            assertNull(rolledBackInScope.getId());
            status.setRollbackOnly();
        });

        assertEquals(0, countItems(keeperA));
        assertNotNull(committedInScope.getId());
        assertNull(rolledBackInScope.getId());
    }

    // A DAO insert outside any JdbcUtil scope runs on Spring's connection and registers its ID restoration with Spring's transaction
    // synchronization: Spring's rollback puts the ID back. Without synchronization (SYNCHRONIZATION_NEVER), the documented
    // limitation: the row is gone, but the entity keeps its ID.
    @ParameterizedTest(name = "synchronization={0}")
    @ValueSource(booleans = { true, false })
    void directInsert_thenSpringRollback_putsBackTheIdOnlyWithSynchronization(final boolean synchronization) {
        final Item item = item(null, "d");
        final TransactionTemplate template = synchronization ? transactionTemplate() : unsynchronizedTemplate();

        template.executeWithoutResult(status -> {
            try {
                assertNotNull(itemDaoA.insert(item));
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }

            assertNotNull(item.getId());
            assertEquals(0, countItems(keeperA), "the insert runs in the Spring transaction");
            status.setRollbackOnly();
        });

        assertEquals(0, countItems(keeperA));
        assertEquals(synchronization, item.getId() == null);
    }

    // Documented limitation (see CrudInsertOps): an insert on A's data source, run while another transaction manager's transaction B
    // runs inside A, registers its ID restoration with B's synchronizations, the only ones Spring exposes (A's are suspended, and no
    // public Spring API tells which transaction owns a bound connection). The ID therefore follows B's outcome, not A's, which
    // decides the row's: B rolls back and A commits -> the row exists but the ID is cleared; B commits and A rolls back -> the row
    // is gone but the ID is kept. Expectations to flip if the owner's transaction is ever tracked.
    @ParameterizedTest(name = "innerRollsBack={0}")
    @ValueSource(booleans = { true, false })
    void insertOnEnclosingTransactionsDataSource_followsTheInnerTransactionsOutcome(final boolean innerRollsBack) {
        final TransactionTemplate templateB = new TransactionTemplate(new DataSourceTransactionManager(dataSourceB));
        final Item item = item(null, "x");

        transactionTemplate().executeWithoutResult(a -> {
            templateB.executeWithoutResult(b -> {
                try {
                    assertNotNull(itemDaoA.insert(item));
                } catch (final SQLException e) {
                    throw new IllegalStateException(e);
                }

                if (innerRollsBack) {
                    b.setRollbackOnly();
                }
            });

            if (!innerRollsBack) {
                a.setRollbackOnly();
            }
        });

        assertEquals(innerRollsBack ? 1 : 0, countItems(keeperA), "A decides the row");
        assertEquals(innerRollsBack, item.getId() == null, "B decides the ID");
    }

    // Marking the Spring transaction rollback-only can fail (here the bound holder's setRollbackOnly() throws). The rollback of
    // the participating scope then has no effect on the Spring transaction, which may still commit the work; the scope used to
    // report ROLLED_BACK anyway and, without Spring's synchronization (where the ID restoration is kept locally), put the
    // generated ID back although Spring went on to commit the row. Now the failure propagates, the status is FAILED_ROLLBACK,
    // the ID is kept, and the scope is still completed: no registry entry is left, the connection goes back to Spring (open), a
    // later cleanup call is a no-op, and Spring commits the row.
    @ParameterizedTest(name = "synchronization={0}, explicitRollback={1}")
    @MethodSource("markFailureCases")
    void participatingRollback_whenTheSpringTransactionCannotBeMarkedRollbackOnly_failsWithoutUndoingAndLeavesTheOutcomeToSpring(final boolean synchronization,
            final boolean explicitRollback) throws SQLException {
        final TransactionTemplate template = synchronization ? transactionTemplate() : unsynchronizedTemplate();
        final Item item = item(null, "kept");

        template.executeWithoutResult(status -> {
            // Spring's holder, replaced by one that can't be marked while the participating scope runs; Spring's transaction
            // object keeps the original holder, which decides the commit.
            final ConnectionHolder springHolder = (ConnectionHolder) TransactionSynchronizationManager.unbindResource(dataSourceA);
            final Connection spring = springHolder.getConnection();

            TransactionSynchronizationManager.bindResource(dataSourceA, new ConnectionHolder(spring, true) {
                @Override
                public void setRollbackOnly() {
                    throw new IllegalStateException("cannot mark");
                }
            });

            try {
                final SqlTransaction tran = JdbcUtil.beginTransaction(dataSourceA);
                assertSame(tran, SqlTransaction.getTransaction(dataSourceA, SqlTransaction.CreatedBy.JDBC_UTIL));

                try {
                    assertNotNull(itemDaoA.insert(item));
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e);
                }

                final Long id = item.getId();
                assertNotNull(id);

                @SuppressWarnings("deprecation")
                final IllegalStateException failure = assertThrows(IllegalStateException.class,
                        explicitRollback ? tran::rollback : tran::rollbackIfNotCommitted);
                assertEquals("cannot mark", failure.getMessage());

                assertEquals(Transaction.Status.FAILED_ROLLBACK, tran.status());
                assertEquals(id, item.getId(), "the ID is kept: Spring may still commit the row");
                assertNull(SqlTransaction.getTransaction(dataSourceA, SqlTransaction.CreatedBy.JDBC_UTIL), "the scope is completed");
                assertFalse(spring.isClosed());
                assertFalse(status.isRollbackOnly());

                // The paired cleanup call of the finally idiom, and close(): no second attempt, nothing thrown.
                assertDoesNotThrow(tran::rollbackIfNotCommitted);
                assertDoesNotThrow(tran::close);
                assertEquals(Transaction.Status.FAILED_ROLLBACK, tran.status());
                assertEquals(id, item.getId());
                assertFalse(spring.isClosed());
            } catch (final SQLException e) {
                throw new UncheckedSQLException(e);
            } finally {
                TransactionSynchronizationManager.unbindResource(dataSourceA);
                TransactionSynchronizationManager.bindResource(dataSourceA, springHolder);
            }
        });

        assertFalse(JdbcUtil.isInTransaction(dataSourceA));
        assertEquals(1, countItems(keeperA), "Spring committed the row");
        assertNotNull(item.getId());
        assertEquals("kept", queryName(item.getId()));
    }

    private static Stream<Arguments> markFailureCases() {
        return Stream.of(false, true).flatMap(synchronization -> Stream.of(false, true).map(explicit -> Arguments.of(synchronization, explicit)));
    }

    private TransactionTemplate unsynchronizedTemplate() {
        final DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSourceA);
        transactionManager.setTransactionSynchronization(org.springframework.transaction.support.AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);
        return new TransactionTemplate(transactionManager);
    }

    private static Stream<Arguments> joinCases() {
        return Stream.of(Propagation.REQUIRED, Propagation.MANDATORY)
                .flatMap(propagation -> Stream.of(false, true).map(logging -> Arguments.of(propagation, logging)));
    }

    private static Stream<Arguments> holderCases() {
        return Stream.of(HolderKind.values()).flatMap(kind -> joinCases().map(args -> Arguments.of(kind, args.get()[0], args.get()[1])));
    }

    private static Stream<Arguments> rollbackCases() {
        return Stream.of(false, true).flatMap(inRequiredMethod -> Stream.of(false, true).map(rollsBack -> Arguments.of(inRequiredMethod, rollsBack)));
    }

    private static Stream<Arguments> batchInsertRollbackCases() {
        return Stream.of(BatchInsert.values()).flatMap(batchInsert -> Stream.of(false, true).map(rollsBack -> Arguments.of(batchInsert, rollsBack)));
    }

    private static Stream<Arguments> inTransactionCases() {
        return Stream.of(InTransaction.values()).flatMap(inTransaction -> Stream.of(false, true).map(fails -> Arguments.of(inTransaction, fails)));
    }

    private static Stream<Arguments> joinDeleteCases() {
        return Stream.of(JoinDelete.values()).flatMap(joinDelete -> Stream.of(false, true).map(unchecked -> Arguments.of(joinDelete, unchecked)));
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSourceA));
    }

    private static TransactionTemplate supportsTemplate(final DataSource dataSource) {
        final TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_SUPPORTS);
        return template;
    }

    private static int invoke(final ItemDaoBase<?> dao, final Propagation propagation, final Throwables.Supplier<Integer, SQLException> work)
            throws SQLException {
        return propagation == Propagation.MANDATORY ? dao.mandatory(work) : dao.required(work);
    }

    private static int insertItem(final DataSource dataSource, final String name) throws SQLException {
        try (PreparedQuery query = JdbcUtil.prepareQuery(dataSource, "INSERT INTO spring_item (name) VALUES (?)")) {
            assertEquals(1, query.setString(1, name).update());
        }

        return 42;
    }

    private static void batchInsertTwoChunks(final ItemDao dao) throws SQLException {
        assertEquals(2, dao.batchInsert(List.of(item(null, "c1"), item(null, "c2")), 1).size());
    }

    private String queryName(final long id) throws SQLException {
        try (Statement statement = keeperA.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT name FROM spring_item WHERE id = " + id)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private static int countItems(final Connection connection) {
        return count(connection, "SELECT COUNT(*) FROM spring_item");
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

    private static Item item(final Long id, final String name) {
        final Item item = new Item();
        item.setId(id);
        item.setName(name);
        return item;
    }

    private static Owner owner(final long id) {
        final Owner owner = new Owner();
        owner.setId(id);
        return owner;
    }

    /** Spring connection holders bound for the data source, and whether DAO work joins their transaction. */
    private enum HolderKind {
        TRANSACTION_ACTIVE(true), JPA_CONNECTION_HANDLE(true), SYNCHRONIZATION_ONLY(false);

        final boolean joins;

        HolderKind(final boolean joins) {
            this.joins = joins;
        }

        ConnectionHolder holder(final Connection connection) {
            return switch (this) {
                case TRANSACTION_ACTIVE -> new ConnectionHolder(connection, true);
                case JPA_CONNECTION_HANDLE -> new ConnectionHolder(() -> connection);
                case SYNCHRONIZATION_ONLY -> new ConnectionHolder(connection);
            };
        }
    }

    /** The built-in multi-statement inserts that write generated IDs back, run on three entities. */
    private enum BatchInsert {
        CHUNKED, MIXED_IDS, PROPERTY_NAMES, NAMED_SQL, QUERY_BATCH;

        List<Item> newItems() {
            return new ArrayList<>(List.of(item(null, "a"), item(this == MIXED_IDS ? 500L : null, "b"), item(null, "c")));
        }

        void run(final ItemDao dao, final List<Item> items) throws SQLException {
            switch (this) {
                case CHUNKED -> assertEquals(3, dao.batchInsert(items, 1).size());
                case MIXED_IDS -> assertEquals(3, dao.batchInsert(items, 10).size());
                case PROPERTY_NAMES -> assertEquals(3, dao.batchInsert(items, List.of("name"), 1).size());
                case NAMED_SQL -> assertEquals(3, dao.batchInsert("INSERT INTO spring_item (name) VALUES (:name)", items, 1).size());
                case QUERY_BATCH -> assertEquals(3, dao.insertInBatches(items).size());
            }
        }
    }

    /** The callInTransaction/runInTransaction overloads, inserting one row and then failing with the given failure, if any. */
    private enum InTransaction {
        CALL, CALL_WITH_CONNECTION, RUN, RUN_WITH_CONNECTION;

        void run(final DataSource dataSource, final Connection spring, final SQLException failure) throws SQLException {
            final Throwables.Runnable<SQLException> insert = () -> {
                insertItem(dataSource, "a");

                if (failure != null) {
                    throw failure;
                }
            };

            switch (this) {
                case CALL -> assertEquals(1, JdbcUtil.callInTransaction(dataSource, () -> {
                    insert.run();
                    return 1;
                }));
                case CALL_WITH_CONNECTION -> assertEquals(1, JdbcUtil.<Integer, SQLException> callInTransaction(dataSource, connection -> {
                    assertSame(spring, connection);
                    insert.run();
                    return 1;
                }));
                case RUN -> JdbcUtil.runInTransaction(dataSource, insert);
                case RUN_WITH_CONNECTION -> JdbcUtil.runInTransaction(dataSource, connection -> {
                    assertSame(spring, connection);
                    insert.run();
                });
            }
        }
    }

    /** The join deletes that run several statements: two Note properties, or all three join properties. */
    private enum JoinDelete {
        BY_TYPE, BY_TYPE_FOR_ENTITIES, ALL, ALL_FOR_ENTITIES;

        int run(final JoinEntityHelper<Owner, ?> dao, final Owner first, final Owner second) throws SQLException {
            return switch (this) {
                case BY_TYPE -> dao.deleteJoinEntities(first, Note.class);
                case BY_TYPE_FOR_ENTITIES -> dao.deleteJoinEntities(List.of(first, second), Note.class);
                case ALL -> dao.deleteAllJoinEntities(first);
                case ALL_FOR_ENTITIES -> dao.deleteAllJoinEntities(List.of(first, second));
            };
        }
    }

    private static final class AutoCommitOffDataSource extends DelegatingDataSource {
        AutoCommitOffDataSource(final DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            final Connection connection = super.getConnection();
            connection.setAutoCommit(false);
            return connection;
        }
    }

    interface ItemDaoBase<D extends ItemDaoBase<D>> extends CrudDao<Item, Long, D> {
        @Transactional(propagation = Propagation.REQUIRED)
        default int required(final Throwables.Supplier<Integer, SQLException> work) throws SQLException {
            return work.get();
        }

        @Transactional(propagation = Propagation.MANDATORY)
        default int mandatory(final Throwables.Supplier<Integer, SQLException> work) throws SQLException {
            return work.get();
        }

        @Transactional(propagation = Propagation.REQUIRED, isolationLevel = IsolationLevel.SERIALIZABLE)
        default int requiredSerializable(final Throwables.Supplier<Integer, SQLException> work) throws SQLException {
            return work.get();
        }

        @Query(value = "INSERT INTO spring_item (name) VALUES (:name)", batch = true, batchSize = 1)
        List<Long> insertInBatches(List<Item> items) throws SQLException;

        @Query("INSERT INTO spring_item (name) VALUES (:name)")
        Long insertOne(Item item) throws SQLException;
    }

    interface ItemDao extends ItemDaoBase<ItemDao> {
    }

    @SqlLogEnabled(value = true, maxSqlLogLength = 321)
    @PerfLog(sqlPerfLogThresholdMillis = 1234L)
    interface LoggingItemDao extends ItemDaoBase<LoggingItemDao> {
    }

    interface OwnerDao extends Dao<Owner, OwnerDao>, JoinEntityHelper<Owner, OwnerDao> {
    }

    interface UncheckedOwnerDao extends UncheckedDao<Owner, UncheckedOwnerDao>, UncheckedJoinEntityHelper<Owner, UncheckedOwnerDao> {
    }

    @Table("spring_item")
    public static class Item {
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

    @Table("spring_owner")
    public static class Owner {
        @Id
        private Long id;

        @JoinedBy("id=Note.ownerId")
        private List<Note> notes;

        @JoinedBy("id=Note.pinnedOwnerId")
        private List<Note> pinnedNotes;

        @JoinedBy("id=Tag.ownerId")
        private List<Tag> tags;

        public Long getId() {
            return id;
        }

        public void setId(final Long id) {
            this.id = id;
        }

        public List<Note> getNotes() {
            return notes;
        }

        public void setNotes(final List<Note> notes) {
            this.notes = notes;
        }

        public List<Note> getPinnedNotes() {
            return pinnedNotes;
        }

        public void setPinnedNotes(final List<Note> pinnedNotes) {
            this.pinnedNotes = pinnedNotes;
        }

        public List<Tag> getTags() {
            return tags;
        }

        public void setTags(final List<Tag> tags) {
            this.tags = tags;
        }
    }

    @Table("spring_note")
    public static class Note {
        @Id
        private Long id;
        private Long ownerId;
        private Long pinnedOwnerId;

        public Long getId() {
            return id;
        }

        public void setId(final Long id) {
            this.id = id;
        }

        public Long getOwnerId() {
            return ownerId;
        }

        public void setOwnerId(final Long ownerId) {
            this.ownerId = ownerId;
        }

        public Long getPinnedOwnerId() {
            return pinnedOwnerId;
        }

        public void setPinnedOwnerId(final Long pinnedOwnerId) {
            this.pinnedOwnerId = pinnedOwnerId;
        }
    }

    @Table("spring_tag")
    public static class Tag {
        @Id
        private Long id;
        private Long ownerId;

        public Long getId() {
            return id;
        }

        public void setId(final Long id) {
            this.id = id;
        }

        public Long getOwnerId() {
            return ownerId;
        }

        public void setOwnerId(final Long ownerId) {
            this.ownerId = ownerId;
        }
    }
}
