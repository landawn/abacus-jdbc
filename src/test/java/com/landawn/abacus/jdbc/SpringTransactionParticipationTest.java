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
import java.util.concurrent.atomic.AtomicInteger;
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
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronization;
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
                    assertSame(tran.context(), nested.context());

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

    // External review (tests): a batch of several parameter sets that fails in a Spring-managed transaction marks it rollback-only,
    // also when the caller catches the failure, so Spring rolls back the work done before the batch and the batch's own
    // statements instead of committing them.
    @ParameterizedTest(name = "large={0}")
    @ValueSource(booleans = { false, true })
    void aFailingBatchInASpringTransaction_marksItRollbackOnly(final boolean large) {
        final String sql = "INSERT INTO spring_item (id, name) VALUES (?, ?)";
        final List<Object[]> rows = List.of(new Object[] { 100L, "a" }, new Object[] { 101L, "b" }, new Object[] { 100L, "dup" });

        assertThrows(UnexpectedRollbackException.class, () -> transactionTemplate().executeWithoutResult(status -> {
            try {
                insertItem(dataSourceA, "before");
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }

            assertThrows(SQLException.class, () -> {
                if (large) {
                    JdbcUtil.executeLargeBatchUpdate(dataSourceA, sql, rows, 2);
                } else {
                    JdbcUtil.executeBatchUpdate(dataSourceA, sql, rows, 2);
                }
            });

            assertTrue(status.isRollbackOnly(), "the batch's failure marked the Spring transaction");
        }));

        assertEquals(0, countItems(keeperA), "Spring rolled back the work before the batch and the batch's first chunk");
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

    // Transaction review, High #3: work run outside the Spring transaction (DAO REQUIRES_NEW and NOT_SUPPORTED,
    // callOutsideTransaction, callIgnoringSpringTransaction, and the instance methods of a transaction participating in it) took
    // its connection from the TransactionAwareDataSourceProxy, which hands out the Spring transaction's own connection: a
    // REQUIRES_NEW commit committed the Spring transaction's earlier work too, and work meant to commit on its own was rolled
    // back with the Spring transaction. It takes a connection of the proxy's target data source now.
    @ParameterizedTest
    @ValueSource(strings = { "REQUIRES_NEW", "NOT_SUPPORTED", "callOutsideTransaction", "callIgnoringSpringTransaction",
            "SqlTransaction.callOutsideTransaction", "SqlTransaction.runOutsideTransaction" })
    void workOutsideTheSpringTransaction_behindATransactionAwareProxy_runsOnAConnectionOfItsOwn(final String route) {
        final DataSource proxy = new TransactionAwareDataSourceProxy(dataSourceA);
        final ItemDao dao = JdbcUtil.createDao(ItemDao.class, proxy);
        final Throwables.Supplier<Integer, SQLException> ownWork = () -> insertItem(proxy, "own");

        assertThrows(IllegalStateException.class, () -> transactionTemplate().executeWithoutResult(status -> {
            try {
                insertItem(proxy, "outer");

                switch (route) {
                    case "REQUIRES_NEW" -> dao.requiresNew(ownWork);
                    case "NOT_SUPPORTED" -> dao.notSupported(ownWork);
                    case "callOutsideTransaction" -> JdbcUtil.callOutsideTransaction(proxy, ownWork::get);
                    case "callIgnoringSpringTransaction" -> JdbcUtil.callIgnoringSpringTransaction(ownWork::get);
                    default -> {
                        final SqlTransaction tran = JdbcUtil.beginTransaction(proxy); // participates in the Spring transaction

                        try {
                            if (route.equals("SqlTransaction.callOutsideTransaction")) {
                                tran.callOutsideTransaction(ownWork::get);
                            } else {
                                tran.runOutsideTransaction(ownWork::get);
                            }

                            tran.commit();
                        } finally {
                            tran.rollbackIfNotCommitted();
                        }
                    }
                }
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }

            throw new IllegalStateException("the Spring transaction rolls back");
        }));

        assertEquals(List.of("own"), itemNames(), "the outer work rolled back, the work outside it committed on its own");
    }

    // The instance runOutsideTransaction/callOutsideTransaction of a transaction of its own (one that does not participate in a
    // Spring transaction) left this library's use of Spring transactions on. While Spring's transaction synchronization is
    // active (a SUPPORTS scope, or a Spring transaction on another data source), Spring binds the transaction's connection to
    // the thread when it is acquired, so the work meant to run outside the transaction got that connection back, ran in the
    // transaction and rolled back with it. Spring's transactions are left now, as JdbcUtil.callOutsideTransaction does.
    @ParameterizedTest(name = "springScope={0}, call={1}")
    @MethodSource("synchronizedSpringScopes")
    void outsideOfATransactionOfItsOwn_whileSpringSynchronizes_commitsOnItsOwn(final String springScope, final boolean call) {
        final TransactionTemplate template = "SUPPORTS".equals(springScope) ? supportsTemplate(dataSourceA)
                : new TransactionTemplate(new DataSourceTransactionManager(dataSourceB));

        template.executeWithoutResult(status -> {
            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSourceA);

            try {
                // Not a participating transaction, but Spring synchronizes its connection with the thread.
                assertSame(tran.connection(), ((ConnectionHolder) TransactionSynchronizationManager.getResource(dataSourceA)).getConnection());

                insertItem(dataSourceA, "inside");

                if (call) {
                    assertEquals(42, (int) tran.callOutsideTransaction(() -> insertItem(dataSourceA, "own")));
                } else {
                    tran.runOutsideTransaction(() -> insertItem(dataSourceA, "own"));
                }
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            } finally {
                tran.rollbackIfNotCommitted();
            }
        });

        assertEquals(List.of("own"), itemNames(), "the transaction rolled back, the work outside it committed on its own");
    }

    // Transaction review, low-severity finding: a transaction released its connection according to the thread's Spring setting
    // when it completed, not to how it had acquired it. Begun while Spring synchronized connections (a SUPPORTS scope) and
    // completed inside runIgnoringSpringTransaction, it closed the connection Spring had bound to the thread, which Spring's
    // synchronization still held. It is released to Spring now, as it was acquired from it.
    @Test
    void aTransactionCompletedWhileSpringIsIgnored_releasesItsConnectionTheWayItWasAcquired() throws SQLException {
        final Connection[] acquired = new Connection[1];

        supportsTemplate(dataSourceA).executeWithoutResult(status -> {
            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSourceA);

            try {
                acquired[0] = tran.connection();
                assertSame(acquired[0], ((ConnectionHolder) TransactionSynchronizationManager.getResource(dataSourceA)).getConnection());

                insertItem(dataSourceA, "inside");
                JdbcUtil.runIgnoringSpringTransaction(tran::commit);

                assertFalse(acquired[0].isClosed(), "Spring's synchronization still holds the connection");
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            } finally {
                tran.rollbackIfNotCommitted();
            }
        });

        assertTrue(acquired[0].isClosed(), "released by Spring's synchronization");
        assertEquals(List.of("inside"), itemNames());
    }

    // Transaction review, finding 5: work run outside a transaction on one data source (JdbcUtil.callOutsideTransaction and
    // runOutsideTransaction, DAO REQUIRES_NEW and NOT_SUPPORTED, SqlTransaction.callOutsideTransaction) turned this library's use
    // of Spring transactions off for the whole thread: work there on another data source ignored that data source's Spring
    // transaction, ran on a connection of its own and committed on its own. It is suspended for the first data source only now.
    @ParameterizedTest
    @ValueSource(strings = { "callOutsideTransaction", "runOutsideTransaction", "REQUIRES_NEW", "NOT_SUPPORTED", "SqlTransaction.callOutsideTransaction" })
    void workOutsideATransactionOnOneDataSource_onAnother_joinsItsSpringTransaction(final String route) {
        final Throwables.Supplier<Integer, SQLException> work = () -> {
            insertItem(dataSourceA, "own");
            return insertItem(dataSourceB, "b");
        };

        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(new DataSourceTransactionManager(dataSourceB)).executeWithoutResult(status -> {
            try {
                switch (route) {
                    case "callOutsideTransaction" -> JdbcUtil.callOutsideTransaction(dataSourceA, work::get);
                    case "runOutsideTransaction" -> JdbcUtil.runOutsideTransaction(dataSourceA, () -> {
                        work.get();
                    });
                    case "REQUIRES_NEW" -> itemDaoA.requiresNew(work);
                    case "NOT_SUPPORTED" -> itemDaoA.notSupported(work);
                    default -> {
                        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSourceA);

                        try {
                            tran.callOutsideTransaction(work::get);
                            tran.commit();
                        } finally {
                            tran.rollbackIfNotCommitted();
                        }
                    }
                }
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }

            throw new IllegalStateException("the Spring transaction on data source B rolls back");
        }));

        assertEquals(List.of("own"), itemNames(), "the work on data source A committed on its own");
        assertEquals(0, countItems(keeperB), "the work on data source B joined its Spring transaction and rolled back with it");
    }

    // Transaction review, finding 9: a JdbcUtil transaction participating in a Spring transaction took the work of a Spring
    // scope begun inside it: work in a REQUIRES_NEW scope ran on the connection of the suspended outer transaction and rolled back
    // with it, and the work of a NOT_SUPPORTED scope joined it too. While Spring binds another transaction to the data source, or
    // none, the participating transaction is hidden now (a transaction begun there is one of its own), and it is joined again
    // once Spring resumes the transaction it participates in.
    @ParameterizedTest
    @ValueSource(ints = { TransactionDefinition.PROPAGATION_REQUIRES_NEW, TransactionDefinition.PROPAGATION_NOT_SUPPORTED })
    void aSpringScopeBegunInsideAParticipatingTransaction_takesItsOwnWork(final int propagation) {
        assertThrows(IllegalStateException.class, () -> transactionTemplate().executeWithoutResult(status -> {
            final SqlTransaction outer = JdbcUtil.beginTransaction(dataSourceA); // participates in the Spring transaction

            try {
                insertItem(dataSourceA, "outer");

                final TransactionTemplate inner = new TransactionTemplate(new DataSourceTransactionManager(dataSourceA));
                inner.setPropagationBehavior(propagation);
                inner.executeWithoutResult(innerStatus -> {
                    assertNull(TransactionContext.getTransaction(dataSourceA, SqlTransaction.CreatedBy.JDBC_UTIL), "the participating transaction is hidden");

                    final SqlTransaction nested = JdbcUtil.beginTransaction(dataSourceA);

                    try {
                        assertNotSame(outer.context(), nested.context());
                        insertItem(dataSourceA, "inner");
                        nested.commit();
                    } catch (final SQLException e) {
                        throw new IllegalArgumentException(e);
                    } finally {
                        nested.rollbackIfNotCommitted();
                    }
                });

                // Spring has resumed the outer transaction: the participating transaction is joined again.
                assertSame(outer.context(), TransactionContext.getTransaction(dataSourceA, SqlTransaction.CreatedBy.JDBC_UTIL));

                final SqlTransaction joined = JdbcUtil.beginTransaction(dataSourceA);
                assertSame(outer.context(), joined.context());
                insertItem(dataSourceA, "joined");
                joined.commit();
                outer.commit();
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            } finally {
                outer.rollbackIfNotCommitted();
            }

            throw new IllegalStateException("the outer Spring transaction rolls back");
        }));

        assertEquals(List.of("inner"), itemNames(), "the inner work committed on its own, the outer work rolled back");
    }

    // Transaction review, finding 10: work begun from a Spring transaction's completion callbacks participated in it, though it
    // had committed (or rolled back) by then: a failed scope's rollback-only mark had no effect, and Spring's cleanup, restoring
    // auto-commit, committed the work. Such work runs in a transaction of its own now, on a connection of its own (Round 14: it
    // ran on Spring's connection, see the test below). afterCompletion callbacks are always recognized; afterCommit callbacks in
    // a Spring transaction this library already participates in.
    @ParameterizedTest(name = "afterCommit={0}, fails={1}")
    @MethodSource("completionCallbacks")
    void workBegunFromASpringTransactionsCompletionCallbacks_completesOnItsOwn(final boolean afterCommit, final boolean fails) {
        transactionTemplate().executeWithoutResult(status -> {
            if (afterCommit) {
                final SqlTransaction participating = JdbcUtil.beginTransaction(dataSourceA);
                participating.commit();
            }

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if (afterCommit) {
                        runCallbackWork(fails);
                    }
                }

                @Override
                public void afterCompletion(final int completionStatus) {
                    if (!afterCommit) {
                        runCallbackWork(fails);
                    }
                }
            });
        });

        assertEquals(fails ? List.of() : List.of("callback"), itemNames());
    }

    private void runCallbackWork(final boolean fails) {
        assertFalse(JdbcUtil.isInTransaction(dataSourceA), "the Spring transaction is completing");

        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSourceA);

        try {
            insertItem(dataSourceA, "callback");

            if (!fails) {
                tran.commit();
            }
        } catch (final SQLException e) {
            throw new IllegalArgumentException(e);
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    // Round 14 review: Spring exposes the completion of the innermost transaction on the thread only. In the completion callbacks
    // of a transaction that another transaction manager began inside the Spring transaction owning data source A, that one
    // counted as completing too, and a transaction begun there took Spring's connection of A and committed it, with the work the
    // enclosing transaction had done so far, which then could not roll back. Such a transaction runs on a connection of its own
    // now, as REQUIRES_NEW would.
    @ParameterizedTest(name = "afterCommit={0}")
    @ValueSource(booleans = { false, true })
    void workBegunFromTheCompletionCallbacksOfAnInnerTransactionOfAnotherManager_leavesTheEnclosingOneAlone(final boolean afterCommit) {
        assertThrows(IllegalStateException.class, () -> transactionTemplate().executeWithoutResult(status -> {
            try {
                insertItem(dataSourceA, "outer");
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }

            new TransactionTemplate(new DataSourceTransactionManager(dataSourceB)).executeWithoutResult(innerStatus -> {
                if (afterCommit) {
                    // Participating in the transaction of A registers this library's synchronization with the inner transaction.
                    JdbcUtil.beginTransaction(dataSourceA).commit();
                }

                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        if (afterCommit) {
                            runCallbackWork(false);
                        }
                    }

                    @Override
                    public void afterCompletion(final int completionStatus) {
                        if (!afterCommit) {
                            runCallbackWork(false);
                        }
                    }
                });
            });

            throw new IllegalStateException("the outer Spring transaction rolls back");
        }));

        assertEquals(List.of("callback"), itemNames(), "the outer work rolled back; the callback's committed on its own");
    }

    // Round 15 review: the afterCommit callbacks of a Spring transaction were recognized only once this library had begun a scope
    // in it. After a statement run through JdbcUtil alone, a transaction begun there joined the committed transaction, and
    // Spring's cleanup (restoring auto-commit) committed its work although it rolled back. Taking a connection of the Spring
    // transaction registers this library's synchronization with it now.
    @Test
    void aTransactionBegunInTheAfterCommitCallbacksOfATransactionUsedByPlainStatements_isOneOfItsOwn() {
        transactionTemplate().executeWithoutResult(status -> {
            try {
                insertItem(dataSourceA, "work"); // a plain statement: no scope is begun
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runCallbackWork(true); // rolls back
                }
            });
        });

        assertEquals(List.of("work"), itemNames(), "the callback's work rolled back");
    }

    // Round 16 review: the synchronization that sees a Spring transaction completing was registered once per connection holder,
    // remembered as the last holder seen on the thread. A holder first seen inside another transaction manager's transaction
    // begun in the one owning it registered the synchronization with that inner transaction only: back in the outer one, its
    // statements found the holder seen, and a transaction begun in its afterCommit callbacks joined the committed transaction,
    // whose cleanup then committed the work that transaction rolled back. The holders watched are kept per synchronization,
    // and forgotten when Spring suspends, resumes or completes those synchronizations.
    @ParameterizedTest(name = "firstSeenInAnInnerTransaction={0}")
    @ValueSource(booleans = { false, true })
    void aTransactionBegunInTheAfterCommitCallbacks_isOneOfItsOwn_whereverTheConnectionWasFirstTaken(final boolean firstSeenInAnInnerTransaction) {
        transactionTemplate().executeWithoutResult(status -> {
            try {
                if (firstSeenInAnInnerTransaction) {
                    new TransactionTemplate(new DataSourceTransactionManager(dataSourceB)).executeWithoutResult(inner -> {
                        try {
                            assertEquals(0, JdbcUtil.prepareQuery(dataSourceA, "SELECT COUNT(*) FROM spring_item").queryForInt().orElseThrow());
                        } catch (final SQLException e) {
                            throw new IllegalArgumentException(e);
                        }
                    });
                }

                insertItem(dataSourceA, "work"); // a plain statement: no scope is begun
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runCallbackWork(true); // rolls back
                }
            });
        });

        assertEquals(List.of("work"), itemNames(), "the callback's work rolled back");
    }

    // As above, for the afterCommit callbacks of the inner transaction, which count as the completion of the transaction owning
    // data source A (Spring exposes the innermost transaction only): a transaction begun there is one of its own, also when its
    // connection was first taken in the outer transaction.
    @ParameterizedTest(name = "firstSeenInTheOuterTransaction={0}")
    @ValueSource(booleans = { false, true })
    void aTransactionBegunInTheAfterCommitCallbacksOfAnInnerTransaction_isOneOfItsOwn_whereverTheConnectionWasFirstTaken(
            final boolean firstSeenInTheOuterTransaction) {
        transactionTemplate().executeWithoutResult(outer -> {
            try {
                if (firstSeenInTheOuterTransaction) {
                    insertItem(dataSourceA, "outer");
                }
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }

            new TransactionTemplate(new DataSourceTransactionManager(dataSourceB)).executeWithoutResult(inner -> {
                try {
                    assertNotNull(JdbcUtil.prepareQuery(dataSourceA, "SELECT COUNT(*) FROM spring_item").queryForInt());
                } catch (final SQLException e) {
                    throw new IllegalArgumentException(e);
                }

                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        runCallbackWork(false); // commits
                    }
                });
            });

            outer.setRollbackOnly();
        });

        assertEquals(List.of("callback"), itemNames(), "committed on its own, though the outer transaction rolled back");
    }

    // Round 16 review: the Error of a rollback action that Spring's transaction synchronization ran once a participating rollback
    // had marked the Spring transaction rollback-only was only logged, while one from an action the transaction recorded itself
    // propagated. It propagates from the rollback as well, once the scope has completed; the other actions still run.
    @Test
    void anErrorFromARollbackActionRunWhenAParticipatingRollbackMarksTheSpringTransaction_propagates() {
        final AssertionError actionError = new AssertionError("action failed");
        final List<String> ran = new ArrayList<>();

        assertThrows(UnexpectedRollbackException.class, () -> transactionTemplate().executeWithoutResult(status -> {
            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSourceA);
            assertTrue(tran.context().isSpringParticipating());
            assertNotNull(JdbcUtil.registerEnclosingTransactionRollbackAction(dataSourceA, () -> () -> ran.add("older")));
            assertNotNull(JdbcUtil.registerEnclosingTransactionRollbackAction(dataSourceA, () -> () -> {
                throw actionError;
            }));
            assertEquals(0, tran.context().rollbackActionCount(), "registered with Spring's synchronization");

            assertSame(actionError, assertThrows(AssertionError.class, tran::rollbackIfNotCommitted));
            assertEquals(Transaction.Status.ROLLED_BACK, tran.status());
            assertEquals(List.of("older"), ran);
            assertDoesNotThrow(tran::close);
        }));
    }

    // Round 16 review: the commit of a participating scope left open past the Spring transaction it participates in returned
    // normally, with the status COMMITTED, while Spring had decided the outcome of its work without it (here: rolled it back).
    // It throws, as its rollback does (see the Completion scenarios of SqlTransaction), and ends FAILED_COMMIT.
    @Test
    void theCommitOfAParticipatingScopeLeftOpenPastTheSpringTransaction_fails() {
        final SqlTransaction[] leaked = new SqlTransaction[1];

        transactionTemplate().executeWithoutResult(status -> {
            leaked[0] = JdbcUtil.beginTransaction(dataSourceA);

            try {
                insertItem(dataSourceA, "work");
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }

            status.setRollbackOnly();
        });

        assertEquals(0, countItems(keeperA));
        final IllegalStateException thrown = assertThrows(IllegalStateException.class, leaked[0]::commit);
        assertTrue(thrown.getMessage().contains("has completed"), thrown.getMessage());
        assertEquals(Transaction.Status.FAILED_COMMIT, leaked[0].status());

        // Round 17 review: a later commit() said that the transaction "is marked for rollback", which it never was.
        final IllegalStateException again = assertThrows(IllegalStateException.class, leaked[0]::commit);
        assertTrue(again.getMessage().contains("had completed before its commit"), again.getMessage());
        assertFalse(again.getMessage().contains("is marked for rollback"), again.getMessage());
        assertDoesNotThrow(leaked[0]::close);
        assertNull(TransactionContext.getTransaction(dataSourceA, SqlTransaction.CreatedBy.JDBC_UTIL));
    }

    // Round 16 review: a query prepared in a participating transaction was not checked for completion at all, so it ran after the
    // Spring transaction had completed, on the connection Spring had released by then. It is refused once the Spring
    // transaction has completed (while it runs, also after the participating scope has, the query runs on its connection).
    @Test
    void aQueryPreparedInAParticipatingTransaction_isRefusedOnceTheSpringTransactionHasCompleted() throws SQLException {
        final PreparedQuery[] query = new PreparedQuery[1];

        transactionTemplate().executeWithoutResult(status -> {
            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSourceA);

            try {
                query[0] = JdbcUtil.prepareQuery(dataSourceA, "SELECT COUNT(*) FROM spring_item").closeAfterExecution(false);
                assertEquals(0, query[0].queryForInt().orElseThrow());
                tran.commit();
                assertEquals(0, query[0].queryForInt().orElseThrow(), "the Spring transaction still runs");
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            } finally {
                tran.rollbackIfNotCommitted();
            }
        });

        try {
            final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> query[0].queryForInt());
            assertTrue(thrown.getMessage().contains("has completed"), thrown.getMessage());
        } finally {
            query[0].close();
        }
    }

    // Round 17 review: a participating scope left open into the completion callbacks of the Spring transaction, where Spring still
    // binds its connection (and the holder is still transaction-active), was not taken for completed: its commit() in
    // afterCompletion returned normally, with COMMITTED, after Spring had rolled the work back; its rollback in afterCommit
    // reported ROLLED_BACK after Spring had committed the work; and a query prepared in it ran on the connection, in a new
    // transaction of the connection that Spring's cleanup then committed whatever its outcome. The synchronization registered
    // when the scope began records that the completion has started.
    @Test
    void aParticipatingScopeLeftOpenIntoTheCompletionCallbacks_canNeitherCommitNorRollBackThere() throws SQLException {
        final SqlTransaction[] leaked = new SqlTransaction[2];
        final PreparedQuery[] query = new PreparedQuery[1];
        final RuntimeException[] failures = new RuntimeException[3];
        final Transaction.Status[] statuses = new Transaction.Status[2];

        transactionTemplate().executeWithoutResult(status -> {
            leaked[0] = JdbcUtil.beginTransaction(dataSourceA);

            try {
                insertItem(dataSourceA, "discarded");
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(final int completionStatus) {
                    failures[0] = thrownBy(leaked[0]::commit);
                    statuses[0] = leaked[0].status();
                }
            });

            status.setRollbackOnly();
        });

        assertEquals(0, countItems(keeperA), "Spring rolled the work back");
        assertTrue(failures[0] instanceof IllegalStateException, String.valueOf(failures[0]));
        assertTrue(failures[0].getMessage().contains("runs its completion callbacks"), failures[0].getMessage());
        assertEquals(Transaction.Status.FAILED_COMMIT, statuses[0]);

        transactionTemplate().executeWithoutResult(status -> {
            leaked[1] = JdbcUtil.beginTransaction(dataSourceA);

            try {
                insertItem(dataSourceA, "committed");
                query[0] = JdbcUtil.prepareQuery(dataSourceA, "SELECT COUNT(*) FROM spring_item").closeAfterExecution(false);
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    failures[1] = thrownBy(() -> {
                        try {
                            query[0].queryForInt();
                        } catch (final SQLException e) {
                            throw new UncheckedSQLException(e);
                        }
                    });
                    failures[2] = thrownBy(leaked[1]::rollbackIfNotCommitted);
                    statuses[1] = leaked[1].status();
                }
            });
        });

        try {
            assertEquals(1, countItems(keeperA), "Spring committed the work");
            assertTrue(failures[1] instanceof IllegalStateException, String.valueOf(failures[1]));
            assertTrue(failures[2] instanceof IllegalStateException, String.valueOf(failures[2]));
            assertTrue(failures[2].getMessage().contains("runs its completion callbacks"), failures[2].getMessage());
            assertEquals(Transaction.Status.FAILED_ROLLBACK, statuses[1]);
            assertNull(TransactionContext.getTransaction(dataSourceA, SqlTransaction.CreatedBy.JDBC_UTIL));
        } finally {
            query[0].close();
        }
    }

    // External review: a scope begun inside another transaction manager's transaction, participating in the enclosing one
    // through its data source, recorded the inner transaction's synchronization, and the completion of that inner one counted as
    // that of the enclosing one for good: once it had completed, a query prepared in the scope was refused, and a commit of the
    // scope failed, although the enclosing transaction and its connection were still in use. The completion counts only while
    // its callbacks run; after them, the connection holder of the transaction participated in tells.
    @ParameterizedTest(name = "innerRollsBack={0}")
    @ValueSource(booleans = { false, true })
    void aScopeBegunInsideAnotherManagersTransaction_remainsUsableUntilTheTransactionItParticipatesInCompletes(final boolean innerRollsBack)
            throws SQLException {
        final PreparedQuery[] query = new PreparedQuery[1];
        final SqlTransaction[] leftOpen = new SqlTransaction[1];

        transactionTemplate().executeWithoutResult(outer -> {
            new TransactionTemplate(new DataSourceTransactionManager(dataSourceB)).executeWithoutResult(inner -> {
                final SqlTransaction committed = JdbcUtil.beginTransaction(dataSourceA); // participates in the outer transaction

                try {
                    insertItem(dataSourceA, "committed");
                    query[0] = JdbcUtil.prepareQuery(dataSourceA, "SELECT COUNT(*) FROM spring_item").closeAfterExecution(false);
                    committed.commit();
                } catch (final SQLException e) {
                    throw new IllegalArgumentException(e);
                } finally {
                    committed.rollbackIfNotCommitted();
                }

                leftOpen[0] = JdbcUtil.beginTransaction(dataSourceA);

                if (innerRollsBack) {
                    inner.setRollbackOnly();
                }
            });

            try {
                assertEquals(1, query[0].queryForInt().orElseThrow(), "the transaction it participates in still runs");
                insertItem(dataSourceA, "left open");
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }

            leftOpen[0].commit();
            assertEquals(Transaction.Status.COMMITTED, leftOpen[0].status());
        });

        try {
            assertEquals(List.of("committed", "left open"), itemNames(), "the outer transaction committed the work");
            final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> query[0].queryForInt());
            assertTrue(thrown.getMessage().contains("has completed"), thrown.getMessage());
        } finally {
            query[0].close();
        }
    }

    // Round 17 review, a documented limit (see JdbcUtil.beginTransaction): Spring exposes the synchronizations of the innermost
    // transaction on the thread only, so a transaction this library used only inside another transaction manager's transaction
    // begun in it has none of this library's: its afterCommit callbacks are not recognized as its completion, a transaction
    // begun there joins it, and Spring's cleanup commits that work although it was rolled back. No Spring API reaches the
    // suspended synchronizations of the enclosing transaction. Pinned, so that a change is noticed (and the docs updated).
    @Test
    void aTransactionUsedOnlyInsideAnotherManagersTransaction_isNotRecognizedAsCompletingInItsAfterCommitCallbacks() {
        final Boolean[] participated = new Boolean[1];

        transactionTemplate().executeWithoutResult(outer -> {
            new TransactionTemplate(new DataSourceTransactionManager(dataSourceB)).executeWithoutResult(inner -> {
                final SqlTransaction tran = JdbcUtil.beginTransaction(dataSourceA); // participates in the outer transaction

                try {
                    insertItem(dataSourceA, "outer work");
                    tran.commit();
                } catch (final SQLException e) {
                    throw new IllegalArgumentException(e);
                } finally {
                    tran.rollbackIfNotCommitted();
                }
            });

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    final SqlTransaction callback = JdbcUtil.beginTransaction(dataSourceA);

                    try {
                        participated[0] = callback.context().isSpringParticipating();
                        insertItem(dataSourceA, "callback");
                    } catch (final SQLException e) {
                        throw new IllegalArgumentException(e);
                    } finally {
                        callback.rollbackIfNotCommitted();
                    }
                }
            });
        });

        assertEquals(Boolean.TRUE, participated[0], "the limit: it joined the committed transaction");
        assertEquals(List.of("outer work", "callback"), itemNames(), "and Spring's cleanup committed the work it rolled back");
    }

    // Round 17 review: a nested runOutsideTransaction of a participating transaction (not the call that unregistered it)
    // dropped the participating transaction it hid once the command had completed it, so the outer call registered nothing again,
    // and later work in the enclosing Spring transaction began a transaction of its own instead of joining the open one.
    @Test
    void aNestedRunOutsideTransactionCompletingTheTransaction_keepsTheParticipatingTransactionItHid() {
        transactionTemplate().executeWithoutResult(outer -> {
            final SqlTransaction hidden = JdbcUtil.beginTransaction(dataSourceA);

            try {
                final TransactionTemplate requiresNew = new TransactionTemplate(new DataSourceTransactionManager(dataSourceA));
                requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                requiresNew.executeWithoutResult(inner -> {
                    final SqlTransaction hiding = JdbcUtil.beginTransaction(dataSourceA); // participates in the inner transaction
                    assertNotSame(hidden.context(), hiding.context());

                    hiding.runOutsideTransaction(() -> hiding.runOutsideTransaction(hiding::commit));
                    assertEquals(Transaction.Status.COMMITTED, hiding.status());
                });

                final SqlTransaction joined = JdbcUtil.beginTransaction(dataSourceA);

                try {
                    assertSame(hidden.context(), joined.context(), "the transaction it hid is registered again");
                    joined.commit();
                } finally {
                    joined.rollbackIfNotCommitted();
                }

                hidden.commit();
            } finally {
                hidden.rollbackIfNotCommitted();
            }
        });

        assertNull(TransactionContext.getTransaction(dataSourceA, SqlTransaction.CreatedBy.JDBC_UTIL));
    }

    // External review: the same with three levels and two contexts (H, P hiding H, Q hiding P): P's callOutsideTransaction,
    // run inside Q's (where P is not registered), dropped the link to H once P had completed, so Q's re-registration reached
    // only P, completed, and H was never found again: later work in H's Spring transaction began a transaction of its own.
    @Test
    void aCallOutsideTransactionNestedInAnotherContextsOne_keepsTheTransactionsHiddenBelow() {
        final TransactionTemplate requiresNew = new TransactionTemplate(new DataSourceTransactionManager(dataSourceA));
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        transactionTemplate().executeWithoutResult(outer -> {
            final SqlTransaction h = JdbcUtil.beginTransaction(dataSourceA);

            try {
                // P's rollback marks its Spring transaction, which then fails to commit.
                assertThrows(UnexpectedRollbackException.class, () -> requiresNew.executeWithoutResult(middle -> {
                    final SqlTransaction p = JdbcUtil.beginTransaction(dataSourceA);

                    requiresNew.executeWithoutResult(inner -> {
                        final SqlTransaction q = JdbcUtil.beginTransaction(dataSourceA);

                        try {
                            q.callOutsideTransaction(() -> p.callOutsideTransaction(() -> {
                                p.rollbackIfNotCommitted();
                                return null;
                            }));
                            q.commit();
                        } finally {
                            q.rollbackIfNotCommitted();
                        }
                    });
                }));

                final SqlTransaction joined = JdbcUtil.beginTransaction(dataSourceA);

                try {
                    assertSame(h.context(), joined.context(), "H is registered again and joined");
                    joined.commit();
                } finally {
                    joined.rollbackIfNotCommitted();
                }

                h.commit();
            } finally {
                h.rollbackIfNotCommitted();
            }
        });

        assertNull(TransactionContext.getTransaction(dataSourceA, SqlTransaction.CreatedBy.JDBC_UTIL));
    }

    // Round 17 review: the synchronization that sees the afterCommit callbacks of a transaction was registered for a connection
    // holder that a transaction owns only, so every connection taken from a holder Spring bound for synchronization only (a
    // SUPPORTS scope, or another data source's while a Spring transaction runs) looked the holder's ownership up reflectively
    // again. It is registered for such a holder too, once per synchronization list.
    @Test
    void connectionsTakenInASupportsScope_registerTheSynchronizationOnce() {
        supportsTemplate(dataSourceA).executeWithoutResult(status -> {
            try {
                for (int i = 0; i < 2; i++) {
                    try (PreparedQuery query = JdbcUtil.prepareQuery(dataSourceA, "SELECT COUNT(*) FROM spring_item")) {
                        assertEquals(0, query.queryForInt().orElseThrow());
                    }
                }
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }

            assertEquals(1,
                    TransactionSynchronizationManager.getSynchronizations()
                            .stream()
                            .filter(synchronization -> synchronization.getClass().getSimpleName().equals("SpringRollbackActions"))
                            .count());
        });
    }

    // Round 17 review (tests): upsert inside a Spring-managed transaction runs its lookup and write in a scope participating in
    // it, so a failure (here: more than one row matches) marks the Spring transaction rollback-only, also when the caller
    // catches it; in a SUPPORTS scope (no transaction) it opens no scope, and its write commits as any single write does.
    @Test
    void upsert_participatesInASpringTransactionAndOpensNoScopeInASupportsScope() throws SQLException {
        assertThrows(UnexpectedRollbackException.class, () -> transactionTemplate().executeWithoutResult(status -> {
            try {
                itemDaoA.insert(item(null, "dup"));
                itemDaoA.insert(item(null, "dup"));

                assertThrows(com.landawn.abacus.exception.DuplicateResultException.class,
                        () -> itemDaoA.upsert(item(null, "dup"), com.landawn.abacus.query.Filters.eq("name", "dup")));
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }
        }));

        assertEquals(0, countItems(keeperA), "the Spring transaction was rolled back");

        supportsTemplate(dataSourceA).executeWithoutResult(status -> {
            try {
                assertFalse(JdbcUtil.isInTransaction(dataSourceA));
                assertNotNull(itemDaoA.upsert(item(null, "supports"), com.landawn.abacus.query.Filters.eq("name", "supports")));
                assertNull(TransactionContext.getTransaction(dataSourceA, SqlTransaction.CreatedBy.JDBC_UTIL), "no scope was opened");
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }

            status.setRollbackOnly(); // no transaction: nothing to roll back
        });

        assertEquals(List.of("supports"), itemNames());
    }

    private static RuntimeException thrownBy(final Runnable action) {
        try {
            action.run();
            return null;
        } catch (final RuntimeException e) {
            return e;
        }
    }

    // Round 15 review: inside runIgnoringSpringTransaction, an action to run after the commit of the participating transaction
    // ran right away, before the Spring commit (and so did a DAO cache's second invalidation). It waits for the Spring commit.
    @Test
    void anActionToRunAfterTheCommitInsideRunIgnoringSpringTransaction_waitsForTheSpringCommit() {
        final List<String> events = new ArrayList<>();

        transactionTemplate().executeWithoutResult(status -> {
            final SqlTransaction participating = JdbcUtil.beginTransaction(dataSourceA);

            try {
                JdbcUtil.runIgnoringSpringTransaction(() -> JdbcUtil.runAfterCommit(dataSourceA, null, () -> events.add("action")));
                participating.commit();
            } finally {
                participating.rollbackIfNotCommitted();
            }

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(final boolean readOnly) {
                    events.add("before the commit");
                }
            });
        });

        assertEquals(List.of("before the commit", "action"), events);
    }

    // Round 15 review: when Spring reports an unknown outcome (a commit failure it couldn't resolve, such as a connection lost
    // during the commit: the work may have committed), the actions to run after the commit (DAO cache invalidations) were
    // dropped. They run.
    @Test
    void anActionToRunAfterTheCommit_runsWhenSpringReportsAnUnknownOutcome() {
        final List<String> ran = new ArrayList<>();
        final DataSourceTransactionManager commitThenFail = new DataSourceTransactionManager(dataSourceA) {
            @Override
            protected void doCommit(final org.springframework.transaction.support.DefaultTransactionStatus status) {
                super.doCommit(status);
                throw new org.springframework.transaction.TransactionSystemException("connection lost during the commit");
            }
        };

        assertThrows(org.springframework.transaction.TransactionSystemException.class, () -> new TransactionTemplate(commitThenFail)
                .executeWithoutResult(status -> JdbcUtil.runAfterCommit(dataSourceA, null, () -> ran.add("action"))));
        assertEquals(List.of("action"), ran);
    }

    // External review (P2): the documented limits of observing a Spring commit, pinned. With Spring's transaction synchronization
    // disabled, the commit can't be observed: an action to run after it runs right away. For work on the data source of an
    // enclosing transaction while another transaction manager's transaction runs inside it, only the inner transaction's
    // synchronizations are visible: the action runs at the inner commit, before the enclosing one.
    @Test
    void actionsToRunAfterTheCommit_followTheDocumentedLimits() {
        final List<String> events = new ArrayList<>();

        unsynchronizedTemplate().executeWithoutResult(status -> {
            JdbcUtil.runAfterCommit(dataSourceA, null, () -> events.add("unsynchronized action"));
            events.add("end of the unsynchronized transaction");
        });

        transactionTemplate().executeWithoutResult(outer -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(final boolean readOnly) {
                    events.add("enclosing commit");
                }
            });

            new TransactionTemplate(new DataSourceTransactionManager(dataSourceB))
                    .executeWithoutResult(inner -> JdbcUtil.runAfterCommit(dataSourceA, null, () -> events.add("action of the enclosing data source")));
        });

        assertEquals(List.of("unsynchronized action", "end of the unsynchronized transaction", "action of the enclosing data source", "enclosing commit"),
                events);
    }

    // External review (P3): an Error thrown by one action to run after a Spring commit stopped the others, which were lost (they
    // had been discarded first). All of them run. Round 16 review: the first Error is thrown from afterCompletion, where Spring
    // logs it: thrown from afterCommit by this library's synchronization, which runs first (see SpringRollbackActions.getOrder),
    // it skipped the afterCommit callbacks of every other synchronization, the application's own included.
    @Test
    void anErrorFromAnActionToRunAfterTheSpringCommit_doesNotStopTheOthers() {
        final List<String> ran = new ArrayList<>();
        final AssertionError actionError = new AssertionError("action failed");

        assertDoesNotThrow(() -> transactionTemplate().executeWithoutResult(status -> {
            JdbcUtil.runAfterCommit(dataSourceA, null, () -> {
                throw actionError;
            });
            JdbcUtil.runAfterCommit(dataSourceA, null, () -> ran.add("after the commit"));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    ran.add("the application's afterCommit");
                }
            });
        }));

        final DataSourceTransactionManager commitThenFail = new DataSourceTransactionManager(dataSourceA) {
            @Override
            protected void doCommit(final org.springframework.transaction.support.DefaultTransactionStatus status) {
                super.doCommit(status);
                throw new org.springframework.transaction.TransactionSystemException("connection lost during the commit");
            }
        };

        assertThrows(org.springframework.transaction.TransactionSystemException.class,
                () -> new TransactionTemplate(commitThenFail).executeWithoutResult(status -> {
                    JdbcUtil.runAfterCommit(dataSourceA, null, () -> {
                        throw actionError;
                    });
                    JdbcUtil.runAfterCommit(dataSourceA, null, () -> ran.add("after the unknown outcome"));
                }));

        assertEquals(List.of("after the commit", "the application's afterCommit", "after the unknown outcome"), ran);
    }

    // Round 16 review: after an unknown outcome, the Error of an action to run after the commit kept the rollback actions of the
    // work on a connection marked rollback-only (which can't have committed) from running: the IDs they put back were kept.
    @Test
    void anErrorFromAnActionToRunAfterAnUnknownOutcome_doesNotStopTheRollbackActions() {
        final List<String> ran = new ArrayList<>();
        // Commits despite the rollback-only mark, as JpaTransactionManager does, then reports an unknown outcome.
        final DataSourceTransactionManager commitThenFail = new DataSourceTransactionManager(dataSourceA) {
            @Override
            protected boolean shouldCommitOnGlobalRollbackOnly() {
                return true;
            }

            @Override
            protected void doCommit(final org.springframework.transaction.support.DefaultTransactionStatus status) {
                super.doCommit(status);
                throw new org.springframework.transaction.TransactionSystemException("connection lost during the commit");
            }
        };

        assertThrows(org.springframework.transaction.TransactionSystemException.class,
                () -> new TransactionTemplate(commitThenFail).executeWithoutResult(status -> {
                    assertTrue(JdbcUtil.registerSpringRollbackAction(dataSourceA, () -> ran.add("rollback action")));
                    ((ConnectionHolder) TransactionSynchronizationManager.getResource(dataSourceA)).setRollbackOnly();
                    JdbcUtil.runAfterCommit(dataSourceA, null, () -> {
                        throw new AssertionError("action failed");
                    });
                }));

        assertEquals(List.of("rollback action"), ran);
    }

    // Round 15 review: only the innermost suspension of a data source was consulted. Spring binding, inside a nested suspension,
    // the transaction an enclosing one had left (a programmatic commit of the transaction begun between them), the work there
    // joined that transaction and rolled back with it.
    @Test
    void aNestedSuspension_keepsTheEnclosingOneWhenSpringBindsItsTransactionAgain() {
        final DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSourceA);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            try {
                JdbcUtil.runOutsideTransaction(dataSourceA, () -> {
                    final org.springframework.transaction.TransactionStatus inner = transactionManager.getTransaction(
                            new org.springframework.transaction.support.DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW));

                    JdbcUtil.runOutsideTransaction(dataSourceA, () -> {
                        transactionManager.commit(inner); // Spring binds the enclosing transaction again
                        assertFalse(JdbcUtil.isInTransaction(dataSourceA), "the enclosing suspension applies to it");
                        insertItem(dataSourceA, "own");
                    });
                });
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }

            status.setRollbackOnly();
        });

        assertEquals(List.of("own"), itemNames(), "the work committed on its own");
    }

    // Round 14 review: a participating transaction completed while Spring binds another transaction to its data source
    // (REQUIRES_NEW) or none (NOT_SUPPORTED) marked the bound transaction rollback-only (or failed) instead of the one it
    // participates in, which then committed the work rolled back, and released its connection through DataSourceUtils, which
    // closed it: the connection of the suspended transaction, whose commit then failed. It marks the transaction it
    // participates in now, and leaves the connection to it.
    @ParameterizedTest(name = "propagation={0}, commit={1}")
    @MethodSource("springScopesAndOutcomes")
    void aParticipatingTransactionCompletedInsideASpringScopeBegunInIt_completesTheOneItParticipatesIn(final int propagation, final boolean commit) {
        final Runnable outerWork = () -> transactionTemplate().executeWithoutResult(status -> {
            final SqlTransaction participating = JdbcUtil.beginTransaction(dataSourceA);

            try {
                insertItem(dataSourceA, "outer");
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }

            final TransactionTemplate inner = new TransactionTemplate(new DataSourceTransactionManager(dataSourceA));
            inner.setPropagationBehavior(propagation);
            inner.executeWithoutResult(innerStatus -> {
                if (commit) {
                    participating.commit();
                } else {
                    participating.rollbackIfNotCommitted();
                }

                assertFalse(innerStatus.isRollbackOnly(), "the inner scope is not marked");
            });

            assertEquals(commit ? Transaction.Status.COMMITTED : Transaction.Status.ROLLED_BACK, participating.status());
            assertEquals(!commit, status.isRollbackOnly(), "the transaction participated in is marked");
        });

        if (commit) {
            outerWork.run();
            assertEquals(List.of("outer"), itemNames(), "its connection was left open to it");
        } else {
            assertThrows(UnexpectedRollbackException.class, outerWork::run);
            assertEquals(List.of(), itemNames());
        }
    }

    // A transaction that has completed, or one deciding from a JPA transaction (a holder with a connection handle of its own),
    // can't be marked while Spring doesn't bind it: the rollback fails, and leaves the outcome to Spring.
    @Test
    void markUnboundSpringTransactionRollbackOnly_failsForACompletedOrJpaTransaction() {
        final ConnectionHolder completed = new ConnectionHolder(Mockito.mock(Connection.class)); // not transaction-active
        assertThrows(IllegalStateException.class, () -> JdbcUtil.markUnboundSpringTransactionRollbackOnly(completed));
        assertFalse(completed.isRollbackOnly());

        final ConnectionHolder jpa = new ConnectionHolder(() -> Mockito.mock(Connection.class));
        assertThrows(IllegalStateException.class, () -> JdbcUtil.markUnboundSpringTransactionRollbackOnly(jpa));
    }

    // Round 14 review: an action registered to run after the commit from an afterCommit callback was lost: once this library's
    // synchronization had run its afterCommit, the action was added to it, and a synchronization it registered there got no
    // afterCommit call. It runs now: right away once this library's synchronization has seen the commit, or else when the
    // transaction completes.
    @ParameterizedTest(name = "participated={0}")
    @ValueSource(booleans = { false, true })
    void anActionToRunAfterTheCommitRegisteredFromAnAfterCommitCallback_runs(final boolean participated) {
        final List<String> ran = new ArrayList<>();
        final List<Boolean> ranRightAway = new ArrayList<>();

        transactionTemplate().executeWithoutResult(status -> {
            if (participated) {
                JdbcUtil.beginTransaction(dataSourceA).commit();
            }

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    JdbcUtil.runAfterCommit(dataSourceA, null, () -> ran.add("action"));
                    ranRightAway.add(!ran.isEmpty());
                }
            });
        });

        assertEquals(List.of("action"), ran);
        assertEquals(List.of(participated), ranRightAway);
    }

    // Round 14 review: after a failed rollback, a transaction of its own aborts its connection. When Spring had bound that
    // connection to the thread for synchronization (a SUPPORTS scope), later work in the scope got the aborted connection from
    // Spring and failed on it. The connection is taken from Spring's holder now (and closed), so that work gets a new one; with
    // a TransactionAwareDataSourceProxy, the target connection is taken from the target data source's holder too.
    @ParameterizedTest(name = "proxy={0}")
    @ValueSource(booleans = { false, true })
    void workAfterAFailedRollbackInASupportsScope_getsANewConnection(final boolean proxy) {
        final java.util.concurrent.atomic.AtomicBoolean rollbackFails = new java.util.concurrent.atomic.AtomicBoolean();
        final List<Connection> opened = new ArrayList<>();
        final DataSource failing = failingRollbackDataSource(dataSourceA, rollbackFails, opened);
        final DataSource dataSource = proxy ? new TransactionAwareDataSourceProxy(failing) : failing;

        supportsTemplate(failing).executeWithoutResult(status -> {
            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

            try {
                insertItem(dataSource, "failed");
                rollbackFails.set(true);
                assertThrows(UncheckedSQLException.class, tran::rollbackIfNotCommitted);
                assertEquals(Transaction.Status.FAILED_ROLLBACK, tran.status());
                rollbackFails.set(false);

                insertItem(dataSource, "later"); // auto-commit, on a new connection
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }
        });

        assertEquals(List.of("later"), itemNames(), "the aborted work is gone, the later work committed");
        assertEquals(2, opened.size(), "the later work ran on a new connection");
        assertTrue(opened.stream().allMatch(connection -> {
            try {
                return connection.isClosed();
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }
        }), "both connections were closed");
    }

    /**
     * Wraps {@code target}: a connection's rollback fails while {@code rollbackFails} is set, and its abort closes it (H2's
     * own abort leaves it open). The connections handed out are added to {@code opened}.
     */
    private static DataSource failingRollbackDataSource(final DataSource target, final java.util.concurrent.atomic.AtomicBoolean rollbackFails,
            final List<Connection> opened) {
        return (DataSource) Proxy.newProxyInstance(SpringTransactionParticipationTest.class.getClassLoader(), new Class<?>[] { DataSource.class },
                (dataSourceProxy, dataSourceMethod, dataSourceArgs) -> {
                    final Object result = invokeOn(target, dataSourceMethod, dataSourceArgs);

                    if (!(result instanceof final Connection connection)) {
                        return result;
                    }

                    final Connection failingConnection = (Connection) Proxy.newProxyInstance(SpringTransactionParticipationTest.class.getClassLoader(),
                            new Class<?>[] { Connection.class }, (connectionProxy, method, args) -> {
                                if ("rollback".equals(method.getName()) && method.getParameterCount() == 0 && rollbackFails.get()) {
                                    throw new SQLException("rollback failed");
                                } else if ("abort".equals(method.getName())) {
                                    connection.close();
                                    return null;
                                }

                                return invokeOn(connection, method, args);
                            });

                    opened.add(failingConnection);

                    return failingConnection;
                });
    }

    private static Object invokeOn(final Object target, final java.lang.reflect.Method method, final Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (final InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // Actions to run after the commit of a Spring transaction: once per key, discarded if it rolls back.
    @ParameterizedTest(name = "commit={0}")
    @ValueSource(booleans = { true, false })
    void actionsToRunAfterTheCommitOfASpringTransaction_runOncePerKey(final boolean commit) {
        final List<String> ran = new ArrayList<>();

        final Runnable work = () -> transactionTemplate().executeWithoutResult(status -> {
            JdbcUtil.runAfterCommit(dataSourceA, "key", () -> ran.add("first"));
            JdbcUtil.runAfterCommit(dataSourceA, "key", () -> ran.add("same key"));
            JdbcUtil.runAfterCommit(dataSourceA, null, () -> ran.add("no key"));
            JdbcUtil.runAfterCommit(dataSourceA, null, () -> ran.add("no key"));
            assertEquals(List.of(), ran);

            if (!commit) {
                status.setRollbackOnly();
            }
        });

        work.run();
        assertEquals(commit ? List.of("first", "no key", "no key") : List.of(), ran);
    }

    // Round 14 review: work run outside the transaction ignored every Spring transaction on its data source, also one begun
    // inside it: the work of a REQUIRES_NEW scope there ran on a connection of its own and committed, whatever that scope's
    // outcome. A suspension applies to the Spring binding it found; a nested one ends without ending the enclosing one.
    @Test
    void aSpringTransactionBegunInWorkRunOutsideTheTransaction_isJoined() {
        transactionTemplate().executeWithoutResult(status -> {
            try {
                JdbcUtil.runOutsideTransaction(dataSourceA, () -> {
                    assertFalse(JdbcUtil.isInTransaction(dataSourceA));

                    final TransactionTemplate requiresNew = new TransactionTemplate(new DataSourceTransactionManager(dataSourceA));
                    requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    requiresNew.executeWithoutResult(innerStatus -> {
                        assertTrue(JdbcUtil.isInTransaction(dataSourceA), "the Spring transaction begun inside is used");

                        try {
                            JdbcUtil.runOutsideTransaction(dataSourceA, () -> assertFalse(JdbcUtil.isInTransaction(dataSourceA)));
                            assertTrue(JdbcUtil.isInTransaction(dataSourceA), "the nested suspension has ended");

                            insertItem(dataSourceA, "discarded");
                        } catch (final SQLException e) {
                            throw new IllegalArgumentException(e);
                        }

                        innerStatus.setRollbackOnly();
                    });

                    assertFalse(JdbcUtil.isInTransaction(dataSourceA), "the enclosing suspension applies again");
                    insertItem(dataSourceA, "own");
                });
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }

            status.setRollbackOnly();
        });

        assertEquals(List.of("own"), itemNames());
        assertTrue(JdbcUtil.isSpringTransactionalUsed(dataSourceA), "the suspension has ended");
    }

    // A suspension ends when its work fails, too.
    @Test
    void aSuspensionEndsWhenTheWorkRunOutsideTheTransactionFails() {
        transactionTemplate().executeWithoutResult(status -> {
            assertThrows(IllegalStateException.class, () -> JdbcUtil.runOutsideTransaction(dataSourceA, () -> {
                assertFalse(JdbcUtil.isSpringTransactionalUsed(dataSourceA));
                throw new IllegalStateException("failed");
            }));

            assertTrue(JdbcUtil.isSpringTransactionalUsed(dataSourceA));
            assertTrue(JdbcUtil.isInTransaction(dataSourceA));
        });
    }

    private static Stream<Arguments> springScopesAndOutcomes() {
        return Stream.of(TransactionDefinition.PROPAGATION_REQUIRES_NEW, TransactionDefinition.PROPAGATION_NOT_SUPPORTED)
                .flatMap(propagation -> Stream.of(true, false).map(commit -> Arguments.of(propagation, commit)));
    }

    // Transaction review, finding 13: the parallel join loads and deletes (the executor overloads) ran their tasks on other
    // threads, outside the transaction of the calling thread, each on a connection of its own: a load missed the transaction's
    // uncommitted rows, and a delete committed on its own, so a rollback of the transaction left the joins deleted. Inside a
    // transaction on the DAO's data source (a JdbcUtil transaction, or a Spring-managed one) the tasks run on the calling
    // thread, in the transaction.
    @SuppressWarnings("deprecation")
    @ParameterizedTest(name = "spring={0}")
    @ValueSource(booleans = { false, true })
    void parallelJoinTasksInsideATransaction_runOnTheCallingThreadInIt(final boolean spring) throws SQLException {
        try (Statement statement = keeperA.createStatement()) {
            statement.executeUpdate("INSERT INTO spring_owner (id) VALUES (1)");
            statement.executeUpdate("INSERT INTO spring_note (id, owner_id) VALUES (10, 1)");
        }

        final AtomicInteger submitted = new AtomicInteger();
        final java.util.concurrent.Executor executor = task -> {
            submitted.incrementAndGet();
            new Thread(task).start();
        };

        final Runnable work = () -> {
            try {
                JdbcUtil.executeUpdate(dataSourceA, "INSERT INTO spring_tag (id, owner_id) VALUES (20, 1)"); // not committed

                final Owner owner = new Owner();
                owner.setId(1L);
                ownerDao.loadJoinEntities(owner, List.of("notes", "tags"), executor);

                assertEquals(1, owner.getNotes().size());
                assertEquals(1, owner.getTags().size(), "the uncommitted tag is loaded too");

                // Round 14 review: the IfAbsent overload for one entity (also reached by loadAllJoinEntitiesIfAbsent and the
                // inParallel overloads) still ran its loads on the executor.
                final Owner absent = new Owner();
                absent.setId(1L);
                ownerDao.loadAllJoinEntitiesIfAbsent(absent, executor);
                assertEquals(1, absent.getTags().size(), "the uncommitted tag is loaded too");

                final Owner uncheckedAbsent = new Owner();
                uncheckedAbsent.setId(1L);
                uncheckedOwnerDao.loadJoinEntitiesIfAbsent(uncheckedAbsent, List.of("notes", "tags"), executor);
                assertEquals(1, uncheckedAbsent.getTags().size(), "the uncommitted tag is loaded too");
                assertEquals(2, ownerDao.deleteJoinEntities(owner, List.of("notes", "tags"), executor));
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }

            throw new IllegalStateException("the transaction rolls back");
        };

        if (spring) {
            assertThrows(IllegalStateException.class, () -> transactionTemplate().executeWithoutResult(status -> work.run()));
        } else {
            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSourceA);

            try {
                assertThrows(IllegalStateException.class, work::run);
            } finally {
                tran.rollbackIfNotCommitted();
            }
        }

        assertEquals(0, submitted.get(), "no task was handed to the executor");
        assertEquals(1, count(keeperA, "SELECT COUNT(*) FROM spring_note"), "the deleted note is back after the rollback");
        assertEquals(0, count(keeperA, "SELECT COUNT(*) FROM spring_tag"));
    }

    private static Stream<Arguments> completionCallbacks() {
        return Stream.of(true, false).flatMap(afterCommit -> Stream.of(false, true).map(fails -> Arguments.of(afterCommit, fails)));
    }

    private static Stream<Arguments> synchronizedSpringScopes() {
        return Stream.of("SUPPORTS", "another data source").flatMap(scope -> Stream.of(false, true).map(call -> Arguments.of(scope, call)));
    }

    // The same for DBLock, which changes its lock table on an auto-commit connection of its own: through the proxy it took the
    // Spring transaction's connection and switched it to auto-commit, which committed the transaction's earlier work.
    @Test
    void dbLock_behindATransactionAwareProxy_leavesTheSpringTransactionAlone() {
        final DataSource proxy = new TransactionAwareDataSourceProxy(dataSourceA);
        final DBLock dbLock = JdbcUtil.createDBLock(proxy, "spring_lock");

        try {
            assertThrows(IllegalStateException.class, () -> transactionTemplate().executeWithoutResult(status -> {
                try {
                    insertItem(proxy, "outer");
                } catch (final SQLException e) {
                    throw new IllegalArgumentException(e);
                }

                final String code = dbLock.tryLock("item");
                assertNotNull(code);
                assertTrue(dbLock.unlock("item", code));

                throw new IllegalStateException("the Spring transaction rolls back");
            }));
        } finally {
            dbLock.close();
        }

        assertEquals(List.of(), itemNames());
    }

    // External review (tests): the same in a scope that Spring runs without a transaction but with synchronization (SUPPORTS),
    // where the proxy hands out the connection Spring bound for synchronization, which the application may use in a transaction
    // of its own: the lock-table changes run on a connection of their own, so they neither commit nor see its pending work.
    @Test
    void dbLock_behindATransactionAwareProxyInASupportsScope_usesAConnectionOfItsOwn() {
        final DataSource proxy = new TransactionAwareDataSourceProxy(dataSourceA);
        final DBLock dbLock = JdbcUtil.createDBLock(proxy, "spring_lock");

        try {
            supportsTemplate(dataSourceA).executeWithoutResult(status -> {
                try (Connection conn = proxy.getConnection()) { // the connection Spring bound for synchronization
                    conn.setAutoCommit(false);

                    try (Statement statement = conn.createStatement()) {
                        statement.executeUpdate("INSERT INTO spring_item (name) VALUES ('pending')");
                    }

                    final String code = dbLock.tryLock("item");
                    assertNotNull(code);
                    assertTrue(dbLock.unlock("item", code));

                    conn.rollback();
                    conn.setAutoCommit(true);
                } catch (final SQLException e) {
                    throw new IllegalArgumentException(e);
                }
            });
        } finally {
            dbLock.close();
        }

        assertEquals(List.of(), itemNames(), "the lock committed none of the application's pending work");
    }

    // A LinkageError thrown by one data source while JdbcUtil acquired a connection through Spring (its driver's native library
    // or a driver class that fails to load: DataSourceUtils passes such errors on) turned this library's Spring support off for
    // the whole JVM, and the connection was taken from that data source directly. From then on, on every thread and for every
    // data source, Spring transactions were ignored: JdbcUtil work ran and committed on connections of its own, a transaction
    // begun through a TransactionAwareDataSourceProxy committed the Spring transaction's connection with its earlier work, and
    // work meant to run outside the Spring transaction (DAO REQUIRES_NEW, callOutsideTransaction, DBLock) took its connection
    // from the proxy again, so it ran in the Spring transaction. The error propagates now, and Spring support stays on.
    @ParameterizedTest(name = "{0}")
    @MethodSource("rolledBackSpringTransactionRoutes")
    void afterADriverLinkageErrorOfAnotherDataSource_springTransactionsAreStillHonored(final String route, final List<String> committed) {
        final UnsatisfiedLinkError driverFailure = new UnsatisfiedLinkError("no native driver library");
        final AtomicInteger acquisitions = new AtomicInteger();
        final DataSource broken = new DelegatingDataSource(dataSourceB) {
            @Override
            public Connection getConnection() {
                acquisitions.incrementAndGet();
                throw driverFailure;
            }
        };

        assertSame(driverFailure, assertThrows(UnsatisfiedLinkError.class, () -> JdbcUtil.getConnection(broken)));
        assertEquals(1, acquisitions.get(), "not retried on the data source directly");

        runInRolledBackSpringTransaction(route);

        assertEquals(committed, itemNames());
    }

    // The same for a Spring class that fails to link while JdbcUtil releases a connection to Spring (Spring's releaseConnection
    // handles any failure of closing the connection itself): the connection is closed directly, and Spring support stays on.
    @ParameterizedTest(name = "springManaged={0}")
    @ValueSource(booleans = { false, true })
    void afterALinkageErrorReleasingAConnection_springTransactionsAreStillHonored(final boolean springManaged) throws SQLException {
        final Connection conn = Mockito.mock(Connection.class);

        try (MockedStatic<DataSourceUtils> dataSourceUtils = Mockito.mockStatic(DataSourceUtils.class)) {
            dataSourceUtils.when(() -> DataSourceUtils.releaseConnection(ArgumentMatchers.same(conn), ArgumentMatchers.same(dataSourceA)))
                    .thenThrow(new NoClassDefFoundError("org/springframework/jdbc/datasource/ConnectionHolder"));

            if (springManaged) {
                JdbcUtil.releaseSpringManagedConnection(conn, dataSourceA);
            } else {
                JdbcUtil.releaseConnection(conn, dataSourceA);
            }
        }

        Mockito.verify(conn).close();

        runInRolledBackSpringTransaction("participating transaction");

        assertEquals(List.of(), itemNames());
    }

    private static Stream<Arguments> rolledBackSpringTransactionRoutes() {
        return Stream.of(Arguments.of("plain data source", List.of()), Arguments.of("participating transaction", List.of()),
                Arguments.of("REQUIRES_NEW", List.of("own")), Arguments.of("callOutsideTransaction", List.of("own")), Arguments.of("DBLock", List.of()));
    }

    /**
     * Runs the work of {@code route} in a Spring transaction on {@code dataSourceA}, through a
     * {@code TransactionAwareDataSourceProxy} except for the plain data source, and rolls the Spring transaction back.
     */
    private void runInRolledBackSpringTransaction(final String route) {
        final DataSource proxy = new TransactionAwareDataSourceProxy(dataSourceA);
        final ItemDao dao = JdbcUtil.createDao(ItemDao.class, proxy);
        // Created outside the Spring transaction: creating its lock table is DDL, which H2 commits.
        final DBLock dbLock = route.equals("DBLock") ? JdbcUtil.createDBLock(proxy, "spring_lock") : null;

        try {
            assertThrows(IllegalStateException.class, () -> transactionTemplate().executeWithoutResult(status -> {
                try {
                    switch (route) {
                        case "plain data source" -> insertItem(dataSourceA, "outer"); // joins the Spring transaction
                        case "participating transaction" -> {
                            insertItem(proxy, "outer");
                            final SqlTransaction tran = JdbcUtil.beginTransaction(proxy);

                            try {
                                insertItem(proxy, "inner");
                                tran.commit(); // leaves the commit to Spring
                            } finally {
                                tran.rollbackIfNotCommitted();
                            }
                        }
                        case "REQUIRES_NEW" -> {
                            insertItem(proxy, "outer");
                            dao.requiresNew(() -> insertItem(proxy, "own"));
                        }
                        case "callOutsideTransaction" -> {
                            insertItem(proxy, "outer");
                            JdbcUtil.callOutsideTransaction(proxy, () -> insertItem(proxy, "own"));
                        }
                        default -> {
                            insertItem(proxy, "outer");
                            final String code = dbLock.tryLock("item");
                            assertNotNull(code);
                            assertTrue(dbLock.unlock("item", code));
                        }
                    }
                } catch (final SQLException e) {
                    throw new IllegalArgumentException(e);
                }

                throw new IllegalStateException("the Spring transaction rolls back");
            }));
        } finally {
            if (dbLock != null) {
                dbLock.close();
            }
        }
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

    // Transaction review, low-severity finding: a Spring older than 6.2 reports no savepoints to its synchronizations, so the
    // rollback actions held no savepoint mark, and a participating rollback inside a PROPAGATION_NESTED scope put back the IDs
    // of every insert on the data source at once, also that of the enclosing transaction's insert, whose row it then committed.
    // Without savepoint reports the IDs follow the transaction's outcome now. A Spring before 6.2 is simulated here: the
    // savepoint callbacks of Spring 7 are ignored, as an older Spring makes none.
    @Test
    void participatingRollback_insideANestedTransaction_withoutSavepointReports_keepsTheIdOfTheEnclosingInsertThatCommits() throws Exception {
        final java.lang.reflect.Field reported = Class.forName(JdbcUtil.class.getName() + "$SpringRollbackActions").getDeclaredField("savepointsReported");
        reported.setAccessible(true);
        final Object original = reported.get(null);
        reported.set(null, false);

        try {
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

                assertNotNull(outer.getId(), "not put back by the participating rollback");
            });

            assertEquals(1, countItems(keeperA));
            assertNotNull(outer.getId(), "its row committed");
            assertNotNull(inside.getId(), "kept although its row is gone: without savepoint reports, the rollback to the savepoint is not seen");
        } finally {
            reported.set(null, original);
        }
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
                assertSame(tran.context(), TransactionContext.getTransaction(dataSourceA, SqlTransaction.CreatedBy.JDBC_UTIL));

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
                assertNull(TransactionContext.getTransaction(dataSourceA, SqlTransaction.CreatedBy.JDBC_UTIL), "the scope is completed");
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

    // External review (P1): without synchronization, a participating transaction records the ID restoration itself. A nested
    // scope that rolled back, or was never completed, ran it right away, before the outermost scope tried to mark the Spring
    // transaction rollback-only: when that mark failed, Spring committed the row of an entity that had lost its ID. The
    // restoration now waits for the outermost scope's mark: done once the mark is set (Spring then rolls back), dropped when it
    // fails (Spring then commits the row, and the entity keeps its ID).
    @ParameterizedTest(name = "innerLeaked={0}, outermostEnd={1}, markable={2}")
    @MethodSource("unsynchronizedNestedRollbackCases")
    void nestedRollback_inUnsynchronizedSpringTransaction_putsBackTheIdOnlyOnceTheSpringTransactionIsMarked(final boolean innerLeaked,
            final OutermostEnd outermostEnd, final boolean markable) throws SQLException {
        final Item item = item(null, "nested");

        final Runnable springTransaction = () -> unsynchronizedTemplate().executeWithoutResult(status -> {
            final ConnectionHolder springHolder = (ConnectionHolder) TransactionSynchronizationManager.getResource(dataSourceA);

            if (!markable) {
                // Replaced by a holder that can't be marked while the scopes run, as in the test above.
                TransactionSynchronizationManager.unbindResource(dataSourceA);
                TransactionSynchronizationManager.bindResource(dataSourceA, new ConnectionHolder(springHolder.getConnection(), true) {
                    @Override
                    public void setRollbackOnly() {
                        throw new IllegalStateException("cannot mark");
                    }
                });
            }

            try {
                final SqlTransaction outer = JdbcUtil.beginTransaction(dataSourceA);

                try {
                    final SqlTransaction inner = JdbcUtil.beginTransaction(dataSourceA);

                    try {
                        assertNotNull(itemDaoA.insert(item));
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }

                    final Long id = item.getId();
                    assertNotNull(id);

                    if (!innerLeaked) {
                        inner.rollbackIfNotCommitted();
                        assertEquals(Transaction.Status.MARKED_ROLLBACK, outer.status());
                        assertEquals(id, item.getId(), "Spring decides the outcome until the outermost scope has marked its transaction");
                    }

                    if (markable) {
                        if (outermostEnd == OutermostEnd.COMMIT) {
                            assertThrows(RollbackOnlyException.class, () -> outermostEnd.complete(outer));
                        } else {
                            outermostEnd.complete(outer);
                        }

                        assertEquals(Transaction.Status.ROLLED_BACK, outer.status());
                        assertNull(item.getId(), "put back once the Spring transaction is marked");
                    } else {
                        final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> outermostEnd.complete(outer));
                        assertEquals("cannot mark", failure.getMessage());
                        assertEquals(Transaction.Status.FAILED_ROLLBACK, outer.status());
                        assertEquals(id, item.getId(), "kept: Spring may still commit the row");
                    }

                    assertNull(TransactionContext.getTransaction(dataSourceA, SqlTransaction.CreatedBy.JDBC_UTIL));
                } finally {
                    // An assertion that failed before the outermost scope completed must not leave its transaction on the thread.
                    if (!outer.isCompleted()) {
                        try {
                            outer.close();
                        } catch (final RuntimeException e) {
                            // the failed assertion is the one to report
                        }
                    }
                }
            } finally {
                if (!markable) {
                    TransactionSynchronizationManager.unbindResource(dataSourceA);
                    TransactionSynchronizationManager.bindResource(dataSourceA, springHolder);
                }
            }
        });

        if (markable) {
            assertThrows(UnexpectedRollbackException.class, springTransaction::run);
            assertEquals(0, countItems(keeperA));
            assertNull(item.getId());
        } else {
            springTransaction.run();
            assertEquals(1, countItems(keeperA), "Spring committed the row");
            assertNotNull(item.getId());
            assertEquals("nested", queryName(item.getId()));
        }
    }

    private static Stream<Arguments> unsynchronizedNestedRollbackCases() {
        return Stream.of(false, true)
                .flatMap(innerLeaked -> Stream.of(OutermostEnd.values())
                        .flatMap(outermostEnd -> Stream.of(false, true).map(markable -> Arguments.of(innerLeaked, outermostEnd, markable))));
    }

    // The mark returns normally only once the transaction can only end rolled back. No connection holder is bound to the data
    // source once its transaction was suspended or has completed: nothing would make it roll back, so the mark fails (it used to
    // be logged only, and the participating rollback reported as done).
    @Test
    void markSpringTransactionRollbackOnly_failsWhenNoConnectionHolderIsBound() {
        final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> JdbcUtil.markSpringTransactionRollbackOnly(dataSourceA));
        assertTrue(failure.getMessage().contains("no connection holder is bound"), failure.getMessage());

        assertThrows(UnexpectedRollbackException.class, () -> transactionTemplate().executeWithoutResult(status -> {
            JdbcUtil.markSpringTransactionRollbackOnly(dataSourceA);
            assertTrue(status.isRollbackOnly());
        }));
    }

    // A participating transaction left open past the Spring transaction it joined (no cleanup call before the Spring transaction
    // completed): Spring committed its work. Its rollback can no longer mark the Spring transaction, and used to be reported as
    // done all the same (ROLLED_BACK, no exception; without synchronization, the generated ID was put back too, then kept). It
    // fails now: FAILED_ROLLBACK, the ID of the committed row is kept, and the scope is completed.
    @ParameterizedTest(name = "synchronization={0}")
    @ValueSource(booleans = { true, false })
    void rollbackOfParticipatingTransactionLeftOpenPastTheSpringTransaction_failsAndKeepsTheIdOfTheCommittedRow(final boolean synchronization)
            throws SQLException {
        final TransactionTemplate template = synchronization ? transactionTemplate() : unsynchronizedTemplate();
        final Item item = item(null, "leaked");
        final SqlTransaction[] leaked = new SqlTransaction[1];

        template.executeWithoutResult(status -> {
            leaked[0] = JdbcUtil.beginTransaction(dataSourceA);

            try {
                assertNotNull(itemDaoA.insert(item));
            } catch (final SQLException e) {
                throw new UncheckedSQLException(e);
            }
        });

        try {
            assertEquals(1, countItems(keeperA), "Spring committed the row");
            final Long id = item.getId();
            assertNotNull(id);

            final IllegalStateException failure = assertThrows(IllegalStateException.class, leaked[0]::rollbackIfNotCommitted);
            assertTrue(failure.getMessage().contains("it has completed"), failure.getMessage());
            assertEquals(Transaction.Status.FAILED_ROLLBACK, leaked[0].status());
            assertEquals(id, item.getId(), "the row is committed: its ID is kept");
            assertNull(TransactionContext.getTransaction(dataSourceA, SqlTransaction.CreatedBy.JDBC_UTIL), "the scope is completed");
            assertDoesNotThrow(leaked[0]::close);
        } finally {
            if (!leaked[0].isCompleted()) {
                try {
                    leaked[0].close();
                } catch (final RuntimeException e) {
                    // the failed assertion is the one to report
                }
            }
        }

        assertEquals(1, countItems(keeperA));
        assertEquals("leaked", queryName(item.getId()));
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

    private List<String> itemNames() {
        final List<String> names = new ArrayList<>();

        try (Statement statement = keeperA.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT name FROM spring_item ORDER BY id")) {
            while (resultSet.next()) {
                names.add(resultSet.getString(1));
            }
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }

        return names;
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

    /** How the outermost scope of a participating transaction completes. */
    private enum OutermostEnd {
        COMMIT, ROLLBACK, CLOSE;

        @SuppressWarnings("deprecation")
        void complete(final SqlTransaction tran) {
            switch (this) {
                case COMMIT -> tran.commit();
                case ROLLBACK -> tran.rollback();
                case CLOSE -> tran.close();
            }
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

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        default int requiresNew(final Throwables.Supplier<Integer, SQLException> work) throws SQLException {
            return work.get();
        }

        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        default int notSupported(final Throwables.Supplier<Integer, SQLException> work) throws SQLException {
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
