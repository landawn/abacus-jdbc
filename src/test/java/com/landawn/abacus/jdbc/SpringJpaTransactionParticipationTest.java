package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.ConnectionHandle;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.orm.jpa.DefaultJpaDialect;
import org.springframework.orm.jpa.EntityManagerFactoryInfo;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.Table;
import com.landawn.abacus.jdbc.dao.CrudDao;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;

/**
 * A JdbcUtil transaction that participates in a Spring-managed transaction marks it rollback-only when it rolls back. The mark
 * was set only on the JDBC connection holder (and on a declarative transaction's status), which JpaTransactionManager does not
 * consult: it decides from the JPA EntityTransaction alone, so a programmatic (TransactionTemplate) JPA transaction committed
 * the work that had been rolled back.
 */
class SpringJpaTransactionParticipationTest extends TestBase {

    private JdbcDataSource dataSource;
    private Connection keeper;

    /** The unresolved transactions listed before the test: the registry is process-wide (see confirmOnlyUnresolvedTransaction). */
    private java.util.Set<Long> unresolvedBefore;

    @BeforeEach
    void createDatabase() throws SQLException {
        unresolvedBefore = SpringTransactionTracker.unresolvedTransactions()
                .stream()
                .map(SpringTransactionTracker.UnresolvedTransaction::id)
                .collect(java.util.stream.Collectors.toSet());
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:spring_jpa_" + UUID.randomUUID());
        keeper = dataSource.getConnection();

        try (Statement statement = keeper.createStatement()) {
            statement.execute("CREATE TABLE jpa_item (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(10))");
        }
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        keeper.close();
    }

    @Test
    void rollbackInProgrammaticJpaTransaction_rollsBackTheJpaTransaction() throws SQLException {
        final JpaTransaction jpa = new JpaTransaction(dataSource, false, true);
        final ItemDao dao = JdbcUtil.createDao(ItemDao.class, dataSource);
        final Item item = item("a");

        // JpaTransactionManager commits a rollback-only transaction and leaves the rollback to JPA, which reports it (Spring
        // then throws a TransactionSystemException, or an UnexpectedRollbackException for a provider that rolls back silently).
        assertThrows(TransactionException.class, () -> new TransactionTemplate(jpa.transactionManager).executeWithoutResult(status -> {
            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

            try {
                assertNotNull(dao.insert(item));
                JdbcUtil.executeUpdate(dataSource, "INSERT INTO jpa_item (name) VALUES ('b')");
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                tran.rollbackIfNotCommitted();
            }

            // The marked JPA transaction can only end rolled back: the ID is put back right away, as by a nested scope's rollback.
            assertNull(item.getId(), "the generated ID of the rolled-back insert is put back as soon as the transaction is marked");
        }));

        assertTrue(jpa.entityTransaction.rolledBack);
        assertEquals(0, count());
        assertNull(item.getId(), "the generated ID of the rolled-back insert is put back");
    }

    // When neither the entity manager factory nor the connection handle identifies the JPA transaction, no rollback-only mark would
    // reach it, so a JdbcUtil transaction that would participate in it is refused before any work runs. Its commit used to be
    // failed instead, by a synchronization registered with the innermost Spring transaction on the thread, which needn't be the
    // JPA transaction (see the test below). Without synchronization it used to commit the
    // connection itself.
    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void jdbcUtilTransactionInJpaTransactionWithoutAnIdentifiableOwner_isRefused(final boolean synchronization) throws SQLException {
        final JpaTransaction jpa = new JpaTransaction(dataSource, false, false);

        if (!synchronization) {
            jpa.transactionManager.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);
        }

        new TransactionTemplate(jpa.transactionManager).executeWithoutResult(status -> {
            final IllegalStateException e = assertThrows(IllegalStateException.class, () -> JdbcUtil.beginTransaction(dataSource));
            assertTrue(e.getMessage().contains("no active JPA transaction owning the connection can be identified"), e.getMessage());

            try {
                // Work outside a JdbcUtil transaction still runs in the JPA transaction.
                JdbcUtil.executeUpdate(dataSource, "INSERT INTO jpa_item (name) VALUES ('a')");
            } catch (final SQLException ex) {
                throw new IllegalStateException(ex);
            }
        });

        assertTrue(jpa.entityTransaction.committed);
        assertEquals(1, count());
    }

    // Spring exposes only the innermost transaction's synchronizations. A JdbcUtil scope on the data source of an unidentifiable
    // JPA transaction A, run while another transaction manager's transaction B runs inside A, registered its commit guard with
    // B: rolling the scope back rolled back B, and A, which caught B's failure, committed the rolled-back work. The scope is
    // refused now, B commits its work, and A commits only its own.
    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void jdbcUtilTransactionOnEnclosingUnidentifiableJpaTransactionsDataSource_isRefusedAndLeavesTheInnerTransactionAlone(final boolean synchronization)
            throws SQLException {
        final JdbcDataSource innerDataSource = newDatabase();
        final JpaTransaction outer = new JpaTransaction(dataSource, false, false);

        if (!synchronization) {
            outer.transactionManager.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);
        }

        final TransactionTemplate innerTemplate = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(innerDataSource));

        new TransactionTemplate(outer.transactionManager).executeWithoutResult(a -> {
            try {
                JdbcUtil.executeUpdate(dataSource, "INSERT INTO jpa_item (name) VALUES ('a')");
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }

            innerTemplate.executeWithoutResult(b -> {
                try {
                    JdbcUtil.executeUpdate(innerDataSource, "INSERT INTO jpa_item (name) VALUES ('b')");
                } catch (final SQLException e) {
                    throw new IllegalStateException(e);
                }

                assertThrows(IllegalStateException.class, () -> JdbcUtil.beginTransaction(dataSource));
            });
        });

        assertTrue(outer.entityTransaction.committed);
        assertEquals(1, count());

        try (Connection innerConnection = innerDataSource.getConnection()) {
            assertEquals(1, count(innerConnection));
        }
    }

    // Work done after a participating JdbcUtil transaction rolled back stays in the JPA transaction, which is now rollback-only:
    // its JPA commit rolls back, which Spring reports as an unknown outcome, and the ID of that later insert is put back too.
    @Test
    void insertAfterRollbackInJpaTransaction_putsBackItsIdWhenTheJpaCommitRollsBack() throws SQLException {
        final JpaTransaction jpa = new JpaTransaction(dataSource, false, true);
        final ItemDao dao = JdbcUtil.createDao(ItemDao.class, dataSource);
        final Item first = item("a");
        final Item later = item("b");

        assertThrows(TransactionException.class, () -> new TransactionTemplate(jpa.transactionManager).executeWithoutResult(status -> {
            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

            try {
                assertNotNull(dao.insert(first));
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            } finally {
                tran.rollbackIfNotCommitted();
            }

            assertNull(first.getId());

            try {
                assertNotNull(dao.insert(later));
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }

            assertNotNull(later.getId());
        }));

        assertTrue(jpa.entityTransaction.rolledBack);
        assertEquals(0, count());
        assertNull(later.getId());
    }

    // An identified JPA transaction is marked rollback-only also without synchronization.
    @Test
    void rollbackInUnsynchronizedJpaTransactionWithAnIdentifiableOwner_rollsBackTheJpaTransaction() throws SQLException {
        final JpaTransaction jpa = new JpaTransaction(dataSource, false, true);
        jpa.transactionManager.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);
        final ItemDao dao = JdbcUtil.createDao(ItemDao.class, dataSource);
        final Item item = item("a");

        assertThrows(TransactionException.class, () -> new TransactionTemplate(jpa.transactionManager).executeWithoutResult(status -> {
            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

            try {
                assertNotNull(dao.insert(item));
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            } finally {
                tran.rollbackIfNotCommitted();
            }
        }));

        assertTrue(jpa.entityTransaction.rolledBack);
        assertEquals(0, count());
        assertNull(item.getId());
    }

    // When the JPA provider fails to mark the identified transaction rollback-only, nothing makes the transaction roll back. The
    // participating rollback used to be reported as done all the same (ROLLED_BACK, no exception) while JPA committed the work,
    // and the generated ID was first put back right away too. The rollback fails now (FAILED_ROLLBACK, with the provider's
    // failure as the cause), and the ID follows the outcome: a caller that handles the failure lets JPA commit the row, which
    // keeps its ID; otherwise the failure rolls the JPA transaction back, which puts the ID back.
    @ParameterizedTest(name = "failureHandled={0}")
    @ValueSource(booleans = { true, false })
    void rollbackInJpaTransactionThatCannotBeMarked_failsAndLeavesTheIdToTheOutcome(final boolean failureHandled) throws SQLException {
        final JpaTransaction jpa = new JpaTransaction(dataSource, false, true);
        jpa.entityTransaction.refusesRollbackOnly = true;
        final Item item = item("a");

        runRollbackThatCannotBeMarked(jpa, item, failureHandled);

        if (failureHandled) {
            assertTrue(jpa.entityTransaction.committed);
            assertEquals(1, count());
            assertNotNull(item.getId());
        } else {
            assertTrue(jpa.entityTransaction.rolledBack);
            assertEquals(0, count());
            assertNull(item.getId(), "the ID follows the rollback");
        }
    }

    // The same without synchronization, where the JdbcUtil transaction records the ID restoration itself: the mark that reached
    // no JPA owner counted as done, so the ID was put back although the JPA transaction committed the row. The restoration is
    // discarded now, as Spring's outcome can't be followed without synchronization: the ID is kept, also when the failure then
    // rolls the JPA transaction back (the limit of that mode, as when the mark throws).
    @ParameterizedTest(name = "failureHandled={0}")
    @ValueSource(booleans = { true, false })
    void rollbackInUnsynchronizedJpaTransactionThatCannotBeMarked_failsAndKeepsTheId(final boolean failureHandled) throws SQLException {
        final JpaTransaction jpa = new JpaTransaction(dataSource, false, true);
        jpa.transactionManager.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);
        jpa.entityTransaction.refusesRollbackOnly = true;
        final Item item = item("a");

        runRollbackThatCannotBeMarked(jpa, item, failureHandled);

        assertTrue(failureHandled ? jpa.entityTransaction.committed : jpa.entityTransaction.rolledBack);
        assertEquals(failureHandled ? 1 : 0, count());
        assertNotNull(item.getId());
    }

    /**
     * Runs a JPA transaction in which a participating JdbcUtil transaction inserts {@code item} and then fails to roll back,
     * because the JPA transaction refuses the rollback-only mark; the failure is handled inside the JPA transaction if
     * {@code failureHandled}, and otherwise propagates out of it.
     */
    private void runRollbackThatCannotBeMarked(final JpaTransaction jpa, final Item item, final boolean failureHandled) {
        final ItemDao dao = JdbcUtil.createDao(ItemDao.class, dataSource);

        final Runnable jpaTransaction = () -> new TransactionTemplate(jpa.transactionManager).executeWithoutResult(status -> {
            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

            try {
                assertNotNull(dao.insert(item));
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }

            final IllegalStateException failure = assertThrows(IllegalStateException.class, tran::rollbackIfNotCommitted);
            assertTrue(failure.getCause() instanceof jakarta.persistence.PersistenceException, String.valueOf(failure.getCause()));
            assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, tran.status());
            assertNull(TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL), "the scope is completed");
            assertNotNull(item.getId(), "the ID follows the outcome, which the JPA transaction decides");

            if (!failureHandled) {
                throw failure;
            }
        });

        if (failureHandled) {
            jpaTransaction.run();
        } else {
            assertThrows(IllegalStateException.class, jpaTransaction::run);
        }
    }

    // When one of several identified JPA transactions can't be marked, the unmarked one may still commit the work: a partial mark
    // used to count as done, so the generated IDs were put back right away although the rows might commit, and the participating
    // rollback was reported as done. It fails now, and the IDs follow the outcome the Spring transaction decides (here a
    // rollback, by the marked one, as the caller handles the failure).
    @Test
    void rollbackInJpaTransactionWithASecondOwnerThatCannotBeMarked_leavesTheIdsToTheOutcome() throws SQLException {
        final JpaTransaction jpa = new JpaTransaction(dataSource, true, true);
        final ItemDao dao = JdbcUtil.createDao(ItemDao.class, dataSource);
        final Item item = item("a");

        // A second entity manager factory over the same data source, with an active transaction that refuses the mark, bound on
        // the thread as a second JpaTransactionManager's transaction would be.
        final EntityTransaction refusing = mock(EntityTransaction.class);
        when(refusing.isActive()).thenReturn(true);
        doThrow(new jakarta.persistence.PersistenceException("Cannot mark the transaction rollback-only")).when(refusing).setRollbackOnly();
        final EntityManager otherEntityManager = mock(EntityManager.class);
        when(otherEntityManager.getTransaction()).thenReturn(refusing);
        final EntityManagerFactory otherFactory = mock(EntityManagerFactory.class,
                org.mockito.Mockito.withSettings().extraInterfaces(EntityManagerFactoryInfo.class));
        when(((EntityManagerFactoryInfo) otherFactory).getDataSource()).thenReturn(dataSource);

        assertThrows(TransactionException.class, () -> new TransactionTemplate(jpa.transactionManager).executeWithoutResult(status -> {
            TransactionSynchronizationManager.bindResource(otherFactory, new EntityManagerHolder(otherEntityManager));

            try {
                final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

                try {
                    assertNotNull(dao.insert(item));
                } catch (final SQLException e) {
                    throw new IllegalStateException(e);
                }

                final IllegalStateException failure = assertThrows(IllegalStateException.class, tran::rollbackIfNotCommitted);
                assertTrue(failure.getCause() instanceof jakarta.persistence.PersistenceException, String.valueOf(failure.getCause()));
                assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, tran.status());
                assertNotNull(item.getId(), "the ID is not put back before the JPA transaction decides the outcome");
            } finally {
                TransactionSynchronizationManager.unbindResource(otherFactory);
            }
        }));

        assertTrue(jpa.entityTransaction.rolledBack, "the transaction that could be marked rolls back on commit");
        assertEquals(0, count());
        assertNull(item.getId(), "the ID follows the rollback");
    }

    // The JPA transaction identified when the participating transaction began is no longer active when it rolls back: nothing
    // would make the work roll back. The rollback used to be reported as done (ROLLED_BACK, no exception) while JPA committed
    // the work; it fails now, and the ID of the committed row is kept.
    @Test
    void rollbackAfterTheJpaOwnerIsNoLongerActive_failsAndKeepsTheIdOfTheRowItCommits() throws SQLException {
        final JpaTransaction jpa = new JpaTransaction(dataSource, false, true);
        final ItemDao dao = JdbcUtil.createDao(ItemDao.class, dataSource);
        final Item item = item("a");

        new TransactionTemplate(jpa.transactionManager).executeWithoutResult(status -> {
            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

            try {
                assertNotNull(dao.insert(item));
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }

            jpa.entityTransaction.active = false;

            try {
                final IllegalStateException failure = assertThrows(IllegalStateException.class, tran::rollbackIfNotCommitted);
                assertTrue(failure.getMessage().contains("no active JPA transaction owning the connection"), failure.getMessage());
                assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, tran.status());
                assertNotNull(item.getId(), "the ID follows the outcome, which the JPA transaction decides");
            } finally {
                jpa.entityTransaction.active = true;
            }
        });

        assertTrue(jpa.entityTransaction.committed);
        assertEquals(1, count());
        assertNotNull(item.getId());
    }

    // External review (tests): while Spring suspends the JPA transaction a SqlTransaction participates in (here for a
    // NOT_SUPPORTED scope; REQUIRES_NEW suspends it the same way), the JPA transaction decides from its EntityTransaction,
    // which is suspended with its entity manager, out of reach: the rollback fails with IllegalStateException
    // (FAILED_ROLLBACK), and the generated ID follows the JPA transaction's outcome (kept: it commits the row).
    @Test
    void rollbackWhileTheJpaTransactionIsSuspended_failsAndLeavesTheIdToTheOutcome() throws SQLException {
        final JpaTransaction jpa = new JpaTransaction(dataSource, false, true);
        final ItemDao dao = JdbcUtil.createDao(ItemDao.class, dataSource);
        final Item item = item("a");
        final TransactionTemplate notSupported = new TransactionTemplate(jpa.transactionManager);
        notSupported.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

        new TransactionTemplate(jpa.transactionManager).executeWithoutResult(status -> {
            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

            try {
                assertNotNull(dao.insert(item));
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }

            notSupported.executeWithoutResult(suspended -> {
                final IllegalStateException failure = assertThrows(IllegalStateException.class, tran::rollbackIfNotCommitted);
                assertTrue(failure.getMessage().contains("can't be reached while it is suspended"), failure.getMessage());
                assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, tran.status());
                assertNotNull(item.getId(), "the ID follows the outcome, which the JPA transaction decides");
            });
        });

        assertTrue(jpa.entityTransaction.committed);
        assertEquals(1, count());
        assertNotNull(item.getId());
    }

    // External review (owner tracking): a JPA transaction's connection holder records nothing of its completion, so the commit
    // of a participating scope left open past it returned normally, with COMMITTED, although JPA had decided the outcome of
    // its work without it (a documented limit). With a SpringTransactionTracker registered with the JpaTransactionManager, the
    // transaction manager reports the completion: the commit fails, FAILED_COMMIT.
    @ParameterizedTest(name = "tracked={0}")
    @ValueSource(booleans = { false, true })
    void theCommitOfAScopeLeftOpenPastAJpaTransaction_failsWithATracker(final boolean tracked) throws SQLException {
        final JpaTransaction jpa = new JpaTransaction(dataSource, false, true);

        if (tracked) {
            jpa.transactionManager.addListener(new SpringTransactionTracker());
        }

        final SqlTransaction[] leftOpen = new SqlTransaction[1];

        new TransactionTemplate(jpa.transactionManager).executeWithoutResult(status -> {
            leftOpen[0] = JdbcUtil.beginTransaction(dataSource);

            try {
                JdbcUtil.executeUpdate(dataSource, "INSERT INTO jpa_item (name) VALUES ('a')");
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }
        });

        assertTrue(jpa.entityTransaction.committed);
        assertEquals(1, count());

        if (tracked) {
            final IllegalStateException thrown = assertThrows(IllegalStateException.class, leftOpen[0]::commit);
            assertTrue(thrown.getMessage().contains("has completed"), thrown.getMessage());
            assertEquals(SqlTransaction.Status.FAILED_COMMIT, leftOpen[0].status());
        } else {
            leftOpen[0].commit(); // the documented limit without a tracker
            assertEquals(SqlTransaction.Status.COMMITTED, leftOpen[0].status());
        }
    }

    // External review (owner tracking): a write in a JPA transaction kept the DAO result caches out of its table only until
    // Spring's synchronization reported the innermost transaction's commit, as the JPA transaction's connection holder records
    // nothing: a read outside the transaction between the write and the commit stored its result (removed again here by the
    // invalidation after the commit, but kept for good where that invalidation is lost). With a SpringTransactionTracker, the
    // transaction manager tells when the transaction has completed: such a read stores nothing.
    @ParameterizedTest(name = "tracked={0}")
    @ValueSource(booleans = { false, true })
    void aReadOutsideAJpaTransactionThatWroteTheTable_storesNothingWithATracker(final boolean tracked) throws SQLException {
        final JpaTransaction jpa = new JpaTransaction(dataSource, false, true);

        if (tracked) {
            jpa.transactionManager.addListener(new SpringTransactionTracker());
        }

        final AtomicInteger stores = new AtomicInteger();
        final CachedItemDao dao = JdbcUtil.createDao(CachedItemDao.class, dataSource,
                JdbcUtil.DaoCreationOptions.builder().cache(countingCache(stores)).build());

        new TransactionTemplate(jpa.transactionManager).executeWithoutResult(status -> {
            try {
                dao.addItem("a");
                assertEquals(0, (int) JdbcUtil.callOutsideTransaction(dataSource, () -> dao.cachedCount()), "the rows committed before");
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }
        });

        assertEquals(tracked ? 0 : 1, stores.get(), "the read in between stored its result only without a tracker");
        assertEquals(1, dao.cachedCount());
    }

    // External review: an execution listener registered before the tracker that throws from afterCommit keeps Spring from
    // calling the tracker's, and a JPA transaction's connection holder can't tell the completion either: the transaction stayed
    // pending, and the caches kept out of its table, for good. The synchronization the tracker registers when the transaction
    // begins reports the completion, as Spring calls every synchronization's afterCompletion.
    //
    // External review: without synchronization, nothing else reports the completion of a JPA transaction (its holder tells
    // nothing), so the tracker has to run before the failing listener: SpringTransactionTracker.register puts it first.
    // Registered after it, the caches stay out of the table until settlement is explicitly confirmed (a documented limit:
    // nothing else tells the end of Spring's cleanup after a JPA transaction).
    @ParameterizedTest(name = "synchronization={0}, registeredFirst={1}")
    @org.junit.jupiter.params.provider.CsvSource({ "true, false", "false, true", "false, false" })
    void aListenerFailingBeforeTheTrackerOfAJpaTransaction_stillReleasesTheCaches(final boolean synchronization, final boolean registeredFirst)
            throws Exception {
        final JpaTransaction jpa = new JpaTransaction(dataSource, false, true);

        if (!synchronization) {
            jpa.transactionManager.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);
        }

        jpa.transactionManager.addListener(new org.springframework.transaction.TransactionExecutionListener() {
            @Override
            public void afterCommit(final org.springframework.transaction.TransactionExecution transaction, final Throwable commitFailure) {
                throw new IllegalStateException("listener failed");
            }
        });

        if (registeredFirst) {
            SpringTransactionTracker.register(jpa.transactionManager);
        } else {
            jpa.transactionManager.addListener(new SpringTransactionTracker());
        }

        // Holder lifetime must not decide whether cache eligibility returns.
        final Object[] holder = new Object[1];

        final AtomicInteger stores = new AtomicInteger();
        final CachedItemDao dao = JdbcUtil.createDao(CachedItemDao.class, dataSource,
                JdbcUtil.DaoCreationOptions.builder().cache(countingCache(stores)).build());

        final IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new TransactionTemplate(jpa.transactionManager).executeWithoutResult(status -> {
                    holder[0] = TransactionSynchronizationManager.getResource(dataSource);

                    try {
                        dao.addItem("a");
                    } catch (final SQLException e) {
                        throw new IllegalArgumentException(e);
                    }
                }));

        assertEquals("listener failed", failure.getMessage());
        assertTrue(jpa.entityTransaction.committed);
        assertEquals(1, dao.cachedCount());
        assertEquals(1, dao.cachedCount());

        if (synchronization || registeredFirst) {
            assertEquals(1, stores.get(), "cached again once the JPA transaction had committed, the holder still reachable");
        } else {
            assertEquals(0, stores.get(), "a missing completion report keeps the block alive");
            awaitHolderCollected(holder);
            assertEquals(1, dao.cachedCount());
            assertEquals(0, stores.get(), "GC must not resolve an unreported outcome");
            confirmOnlyUnresolvedTransaction(); // the fixture recorded a successful database commit above
            assertEquals(1, dao.cachedCount());
            assertEquals(1, stores.get());
        }
    }

    // Failed completion remains blocked even after cleanup, with a completed SqlTransaction and its holder still referenced.
    // The test explicitly rolls back the pending database work before acknowledging settlement.
    @ParameterizedTest(name = "rollback={0}, synchronization={1}")
    @org.junit.jupiter.params.provider.CsvSource({ "false, false", "false, true", "true, false", "true, true" })
    void aFailedCommitOrRollbackOfATrackedJpaTransaction_keepsTheCachesOutUntilSettlementIsConfirmed(final boolean rollback, final boolean synchronization)
            throws Exception {
        final JpaTransaction jpa = new JpaTransaction(dataSource, false, true);

        if (!synchronization) {
            jpa.transactionManager.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);
        }

        final AtomicInteger stores = new AtomicInteger();
        final CachedItemDao dao = JdbcUtil.createDao(CachedItemDao.class, dataSource,
                JdbcUtil.DaoCreationOptions.builder().cache(countingCache(stores)).build());
        final java.util.List<Throwable> reportedFailures = new java.util.ArrayList<>();
        // After the tracker (registered first below): reads once the tracker has heard of the failure, before the cleanup.
        jpa.transactionManager.addListener(new org.springframework.transaction.TransactionExecutionListener() {
            @Override
            public void afterCommit(final org.springframework.transaction.TransactionExecution transaction, final Throwable commitFailure) {
                reportedFailures.add(commitFailure);
                assertEquals(0, readOutside(dao));
            }

            @Override
            public void afterRollback(final org.springframework.transaction.TransactionExecution transaction, final Throwable rollbackFailure) {
                reportedFailures.add(rollbackFailure);
                assertEquals(0, readOutside(dao));
            }
        });
        SpringTransactionTracker.register(jpa.transactionManager);

        final Object[] holder = new Object[1];
        final SqlTransaction[] scope = new SqlTransaction[1];

        if (rollback) {
            jpa.entityTransaction.failsRollback = true;
        } else {
            jpa.entityTransaction.failsCommit = true;
        }

        try {
            assertThrows(RuntimeException.class, () -> new TransactionTemplate(jpa.transactionManager).executeWithoutResult(status -> {
                holder[0] = TransactionSynchronizationManager.getResource(dataSource);
                scope[0] = JdbcUtil.beginTransaction(dataSource);

                try {
                    dao.addItem("a");
                } catch (final SQLException e) {
                    throw new IllegalArgumentException(e);
                }

                scope[0].commit(); // participating: leaves the commit to Spring

                if (rollback) {
                    status.setRollbackOnly();
                }
            }));

            assertEquals(1, reportedFailures.size());
            assertNotNull(reportedFailures.get(0), "the transaction manager reported a failure");
            assertEquals(0, stores.get(), "the read during the completion stored nothing: the outcome is unknown");
            assertTrue(!jpa.entityTransaction.committed && !jpa.entityTransaction.rolledBack, "the work was left pending");
        } finally {
            jpa.entityTransaction.failsCommit = false;
            jpa.entityTransaction.failsRollback = false;

            if (jpa.entityTransaction.isActive()) {
                jpa.entityTransaction.rollback(); // a pending insert would keep its row locked
            }
        }

        assertEquals(0, dao.cachedCount());
        assertEquals(0, dao.cachedCount());
        assertEquals(0, stores.get(), "kept out while the holder is reachable, the cleanup done and the entity manager closed");

        confirmOnlyUnresolvedTransaction();
        assertEquals(0, dao.cachedCount());
        assertEquals(0, dao.cachedCount());
        assertEquals(1, stores.get(), "cached after confirmed rollback, with the scope and holder still reachable");
        java.lang.ref.Reference.reachabilityFence(holder);
        assertNotNull(scope[0]);
        java.lang.ref.Reference.reachabilityFence(scope);
    }

    // External review: a JPA dialect's connection release in Spring's cleanup may settle the connection, committing the work a
    // failed commit or rollback left pending on it (this one restores auto-commit). The caches took the start of the cleanup
    // (the transaction status's completion) for its end: a read on another thread during the release stored the rows as they
    // were before the work, which the release then committed. Reads stay uncached until settlement is confirmed, including
    // after holder collection: it is not evidence that the underlying connection has settled.
    @ParameterizedTest(name = "rollback={0}, synchronization={1}")
    @org.junit.jupiter.params.provider.CsvSource({ "false, false", "false, true", "true, false", "true, true" })
    void aReadDuringTheConnectionReleaseOfAFailedJpaTransaction_storesNothing(final boolean rollback, final boolean synchronization) throws Exception {
        assertAReadDuringTheCleanupOfAFailedJpaTransactionStoresNothing(rollback, synchronization, true);
    }

    // External review: a JPA provider may mark its entity manager closed before releasing its resources (EclipseLink does), and
    // the release may commit the work a failed commit or rollback left pending (this one restores auto-commit). The caches took
    // the closed entity manager for the end of Spring's cleanup: a read on another thread in between cached the rows as they
    // were before the work, which the release then committed, and the cache kept serving them. The read stores nothing, the
    // committed rows are read afterwards, and cached only after explicit settlement confirmation.
    @ParameterizedTest(name = "rollback={0}, synchronization={1}")
    @org.junit.jupiter.params.provider.CsvSource({ "false, false", "false, true", "true, false", "true, true" })
    void aReadAfterTheEntityManagerIsMarkedClosedBeforeItsConnectionIsReleased_storesNothing(final boolean rollback, final boolean synchronization)
            throws Exception {
        assertAReadDuringTheCleanupOfAFailedJpaTransactionStoresNothing(rollback, synchronization, false);
    }

    /**
     * Fails the commit or rollback of a JPA transaction that inserted a row, and reads the table on another thread during
     * Spring's cleanup, right before the cleanup commits the pending row: in the dialect's connection release
     * ({@code duringRelease}), or else once the entity manager has been marked closed, as EclipseLink's close does before
     * releasing the connection.
     */
    private void assertAReadDuringTheCleanupOfAFailedJpaTransactionStoresNothing(final boolean rollback, final boolean synchronization,
            final boolean duringRelease) throws Exception {
        final JpaTransaction jpa = new JpaTransaction(dataSource, false, true);

        if (!synchronization) {
            jpa.transactionManager.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);
        }

        SpringTransactionTracker.register(jpa.transactionManager);

        final AtomicInteger stores = new AtomicInteger();
        final CachedItemDao dao = JdbcUtil.createDao(CachedItemDao.class, dataSource,
                JdbcUtil.DaoCreationOptions.builder().cache(countingCache(stores)).build());
        final java.util.List<Integer> readsInBetween = new java.util.ArrayList<>();
        final Runnable readThenSettle = () -> {
            readsInBetween.add(onAnotherThread(dao::cachedCount));

            try {
                jpa.connection.setAutoCommit(true); // settles the connection: commits the pending work
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }
        };

        if (duringRelease) {
            jpa.onRelease = readThenSettle;
        } else {
            jpa.onClose = readThenSettle;
        }

        if (rollback) {
            jpa.entityTransaction.failsRollback = true;
        } else {
            jpa.entityTransaction.failsCommit = true;
        }

        final Object[] holder = new Object[1];

        try {
            assertThrows(RuntimeException.class, () -> new TransactionTemplate(jpa.transactionManager).executeWithoutResult(status -> {
                holder[0] = TransactionSynchronizationManager.getResource(dataSource);

                try {
                    dao.addItem("a");
                } catch (final SQLException e) {
                    throw new IllegalArgumentException(e);
                }

                if (rollback) {
                    status.setRollbackOnly();
                }
            }));
        } finally {
            jpa.entityTransaction.failsCommit = false;
            jpa.entityTransaction.failsRollback = false;
        }

        assertEquals(java.util.List.of(0), readsInBetween, "the read in between saw the rows committed before");
        assertEquals(0, stores.get(), "and stored nothing");
        assertEquals(1, dao.cachedCount(), "the cleanup committed the work: the committed rows are read");
        assertEquals(0, stores.get(), "kept out while the holder is reachable");

        awaitHolderCollected(holder);
        assertEquals(1, dao.cachedCount());
        assertEquals(0, stores.get(), "GC leaves the block in place");
        confirmOnlyUnresolvedTransaction(); // readThenSettle restored auto-commit successfully
        assertEquals(1, dao.cachedCount());
        assertEquals(1, dao.cachedCount());
        assertEquals(1, stores.get(), "the settled rows are cached after confirmation");
    }

    // External review (tests): a connection release or an entity manager close that fails in Spring's cleanup (Spring logs it)
    // is not settlement: neither cleanup returning nor holder collection releases the block.
    @ParameterizedTest(name = "failing={0}")
    @ValueSource(strings = { "release", "close" })
    void aFailingReleaseOrCloseInTheCleanupOfAFailedJpaTransaction_keepsTheCachesOutUntilSettlementIsConfirmed(final String failing) throws Exception {
        final JpaTransaction jpa = new JpaTransaction(dataSource, false, true);
        SpringTransactionTracker.register(jpa.transactionManager);

        final AtomicInteger stores = new AtomicInteger();
        final CachedItemDao dao = JdbcUtil.createDao(CachedItemDao.class, dataSource,
                JdbcUtil.DaoCreationOptions.builder().cache(countingCache(stores)).build());
        final Runnable fail = () -> {
            throw new IllegalStateException("simulated " + failing + " failure");
        };

        if ("release".equals(failing)) {
            jpa.onRelease = fail;
        } else {
            jpa.onClose = fail;
        }

        final Object[] holder = new Object[1];
        jpa.entityTransaction.failsCommit = true;

        try {
            assertThrows(org.springframework.transaction.TransactionSystemException.class,
                    () -> new TransactionTemplate(jpa.transactionManager).executeWithoutResult(status -> {
                        holder[0] = TransactionSynchronizationManager.getResource(dataSource);

                        try {
                            dao.addItem("a");
                        } catch (final SQLException e) {
                            throw new IllegalArgumentException(e);
                        }
                    }));
        } finally {
            jpa.entityTransaction.failsCommit = false;

            if (jpa.entityTransaction.isActive()) {
                jpa.entityTransaction.rollback(); // a pending insert would keep its row locked
            }
        }

        assertEquals(0, dao.cachedCount());
        assertEquals(0, dao.cachedCount());
        assertEquals(0, stores.get(), "kept out: nothing tells that the cleanup settled the connection");

        awaitHolderCollected(holder);
        assertEquals(0, dao.cachedCount());
        assertEquals(0, stores.get(), "GC does not resolve a failed cleanup");
        confirmOnlyUnresolvedTransaction(); // the explicit rollback in finally settled the work
        assertEquals(0, dao.cachedCount());
        assertEquals(1, stores.get());
    }

    // The critical GC regression: Spring's holder disappears while an external EntityManager still owns pending work.
    // An uncached read must remain uncached after collection; later settlement followed by confirmation permits recovery.
    @ParameterizedTest(name = "rollback={0}, synchronization={1}")
    @org.junit.jupiter.params.provider.CsvSource({ "false, false", "false, true", "true, false", "true, true" })
    void aCollectedHolderWithAnExternalEntityManagerStillOwningPendingWork_keepsCachingBlocked(final boolean rollback, final boolean synchronization)
            throws Exception {
        final JpaTransaction jpa = new JpaTransaction(dataSource, false, true);
        if (!synchronization) {
            jpa.transactionManager.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);
        }
        SpringTransactionTracker.register(jpa.transactionManager);
        final AtomicInteger stores = new AtomicInteger();
        final CachedItemDao dao = JdbcUtil.createDao(CachedItemDao.class, dataSource,
                JdbcUtil.DaoCreationOptions.builder().cache(countingCache(stores)).build());
        final EntityManagerFactory factory = jpa.transactionManager.getEntityManagerFactory();
        final EntityManager entityManager = factory.createEntityManager();
        final Object[] holder = new Object[1];
        TransactionSynchronizationManager.bindResource(factory, new EntityManagerHolder(entityManager));
        try {
            jpa.entityTransaction.failsCommit = !rollback;
            jpa.entityTransaction.failsRollback = rollback;
            assertThrows(RuntimeException.class, () -> new TransactionTemplate(jpa.transactionManager).executeWithoutResult(status -> {
                holder[0] = TransactionSynchronizationManager.getResource(dataSource);
                try {
                    dao.addItem("a");
                } catch (final SQLException e) {
                    throw new IllegalArgumentException(e);
                }
                if (rollback) {
                    status.setRollbackOnly();
                }
            }));
            awaitHolderCollected(holder);
            assertTrue(entityManager.isOpen());
            assertTrue(jpa.entityTransaction.isActive(), "the connection still owns pending work");
            assertEquals(0, dao.cachedCount());
            assertEquals(0, stores.get(), "GC must not release the block");
            jpa.entityTransaction.failsCommit = false;
            jpa.entityTransaction.failsRollback = false;
            jpa.entityTransaction.commit(); // settle AFTER the holder was collected and the old count read
            assertEquals(1, dao.cachedCount());
            assertEquals(0, stores.get(), "settlement has not yet been reported to the tracker");
            confirmOnlyUnresolvedTransaction();
            assertEquals(1, dao.cachedCount());
            assertEquals(1, dao.cachedCount());
            assertEquals(1, stores.get());
        } finally {
            TransactionSynchronizationManager.unbindResource(factory);
            jpa.entityTransaction.failsCommit = false;
            jpa.entityTransaction.failsRollback = false;
            if (jpa.entityTransaction.isActive()) {
                jpa.entityTransaction.rollback();
            }
            entityManager.close();
            jpa.connection.close();
        }
    }

    /**
     * Each test owns exactly one unresolved cache-affecting transaction and settles it before calling this helper. Only the
     * transactions unresolved since the test began are looked at: the registry is process-wide, and one another test left
     * unresolved (failing before its confirmation) must not fail this one.
     */
    private void confirmOnlyUnresolvedTransaction() {
        final var unresolved = newUnresolvedTransactions();
        assertEquals(1, unresolved.size());
        assertEquals(java.util.List.of("jpa_item"), unresolved.get(0).tables());
        assertTrue(SpringTransactionTracker.confirmSettled(unresolved.get(0).id()));
        assertEquals(java.util.List.of(), newUnresolvedTransactions());
    }

    /** The unresolved transactions listed now that weren't before the test. */
    private java.util.List<SpringTransactionTracker.UnresolvedTransaction> newUnresolvedTransactions() {
        return SpringTransactionTracker.unresolvedTransactions().stream().filter(record -> !unresolvedBefore.contains(record.id())).toList();
    }

    /** Drops the test's reference to the connection holder in {@code holder[0]} and waits until it has been collected. */
    private static void awaitHolderCollected(final Object[] holder) throws InterruptedException {
        final java.lang.ref.WeakReference<Object> collected = new java.lang.ref.WeakReference<>(holder[0]);
        assertNotNull(collected.get());
        holder[0] = null;

        for (int attempt = 0; attempt < 100 && collected.get() != null; attempt++) {
            System.gc();
            Thread.sleep(10);
        }

        assertNull(collected.get(), "the connection holder was not garbage collected");
    }

    private static int onAnotherThread(final java.util.concurrent.Callable<Integer> read) {
        final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();

        try {
            return executor.submit(read).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        } finally {
            executor.shutdownNow();
        }
    }

    private int readOutside(final CachedItemDao dao) {
        try {
            return JdbcUtil.callOutsideTransaction(dataSource, () -> dao.cachedCount());
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void commitInProgrammaticJpaTransaction_isCommittedByJpa() throws SQLException {
        final JpaTransaction jpa = new JpaTransaction(dataSource, false, true);
        final ItemDao dao = JdbcUtil.createDao(ItemDao.class, dataSource);
        final Item item = item("a");

        new TransactionTemplate(jpa.transactionManager).executeWithoutResult(status -> {
            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

            try {
                assertNotNull(dao.insert(item));
                tran.commit();
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                tran.rollbackIfNotCommitted();
            }

            assertEquals(0, count(), "the JPA transaction commits the work, not the participating scope");
        });

        assertTrue(jpa.entityTransaction.committed);
        assertEquals(1, count());
        assertNotNull(item.getId());
    }

    // Two JPA transaction managers on two data sources, B started inside A: a rollback on B's data source marked every active
    // JPA transaction, so A rolled back as well although its code caught B's failure. Only the transaction whose entity manager
    // factory reports B's data source is marked now, and the ID of B's rolled-back insert is put back.
    @Test
    void rollbackInInnerJpaTransactionOfAnotherManager_leavesTheOuterTransactionAlone() throws SQLException {
        final JdbcDataSource innerDataSource = newDatabase();
        final JpaTransaction outer = new JpaTransaction(dataSource, true, false);
        final JpaTransaction inner = new JpaTransaction(innerDataSource, true, false);
        final Item innerItem = item("b");

        runInnerRollbackInsideOuterTransaction(outer, inner, innerDataSource, innerItem, TransactionException.class);

        assertTrue(outer.entityTransaction.committed);
        assertTrue(inner.entityTransaction.rolledBack);
        assertEquals(1, count());
        assertNull(innerItem.getId());

        try (Connection innerConnection = innerDataSource.getConnection()) {
            assertEquals(0, count(innerConnection));
        }
    }

    // Factories that report no data source (e.g. LocalEntityManagerFactoryBean): the owner is the transaction whose entity manager
    // the rolled-back connection's handle keeps, as the handles of Spring's Hibernate and EclipseLink dialects do.
    @Test
    void rollbackInInnerJpaTransactionOfUnlinkedFactories_marksTheTransactionTheConnectionHandleKeeps() throws SQLException {
        final JdbcDataSource innerDataSource = newDatabase();
        final JpaTransaction outer = new JpaTransaction(dataSource, false, true);
        final JpaTransaction inner = new JpaTransaction(innerDataSource, false, true);
        final Item innerItem = item("b");

        runInnerRollbackInsideOuterTransaction(outer, inner, innerDataSource, innerItem, TransactionException.class);

        assertTrue(outer.entityTransaction.committed);
        assertTrue(inner.entityTransaction.rolledBack);
        assertEquals(1, count());
        assertNull(innerItem.getId());
    }

    // Neither the factories nor the connection handles identify the owner of B's connection. Marking every active JPA transaction
    // rolled back A as well; the commit guard that replaced it was registered with whichever transaction was innermost. A
    // JdbcUtil transaction on B's data source is refused now, before any work runs, and A and B commit their own work.
    @Test
    void jdbcUtilTransactionInInnerJpaTransactionWithoutAnIdentifiableOwner_isRefused() throws SQLException {
        final JdbcDataSource innerDataSource = newDatabase();
        final JpaTransaction outer = new JpaTransaction(dataSource, false, false);
        final JpaTransaction inner = new JpaTransaction(innerDataSource, false, false);

        new TransactionTemplate(outer.transactionManager).executeWithoutResult(a -> {
            try {
                JdbcUtil.executeUpdate(dataSource, "INSERT INTO jpa_item (name) VALUES ('a')");
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }

            new TransactionTemplate(inner.transactionManager)
                    .executeWithoutResult(b -> assertThrows(IllegalStateException.class, () -> JdbcUtil.beginTransaction(innerDataSource)));
        });

        assertTrue(outer.entityTransaction.committed);
        assertTrue(inner.entityTransaction.committed);
        assertEquals(1, count());
    }

    // A transaction manager that binds its connection with a handle of its own but consults none of the rollback-only marks would
    // commit work rolled back by a participating JdbcUtil transaction: such a transaction is refused. (Its commit used to be
    // failed instead, by a synchronization of the innermost transaction on the thread, which needn't be this one.)
    @Test
    void jdbcUtilTransactionInTransactionManagerIgnoringTheMarks_isRefused() {
        final MarkIgnoringTransactionManager transactionManager = new MarkIgnoringTransactionManager(dataSource);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThrows(IllegalStateException.class, () -> JdbcUtil.beginTransaction(dataSource));

            try {
                JdbcUtil.executeUpdate(dataSource, "INSERT INTO jpa_item (name) VALUES ('d')");
            } catch (final SQLException ex) {
                throw new IllegalArgumentException(ex);
            }
        });

        assertEquals(1, count());
    }

    private int count() {
        return count(keeper);
    }

    private static int count(final Connection connection) {
        try {
            return JdbcUtil.prepareQuery(connection, "SELECT COUNT(*) FROM jpa_item").queryForInt().orElse(-1);
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JdbcDataSource newDatabase() throws SQLException {
        final JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:spring_jpa_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE jpa_item (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(10))");
        }

        return dataSource;
    }

    /**
     * Runs an outer JPA transaction A on {@code dataSource} that catches the failure of an inner one, B, rolled back by JdbcUtil;
     * B's commit fails with {@code innerFailure}.
     */
    private void runInnerRollbackInsideOuterTransaction(final JpaTransaction outer, final JpaTransaction inner, final DataSource innerDataSource,
            final Item innerItem, final Class<? extends RuntimeException> innerFailure) throws SQLException {
        final ItemDao innerDao = JdbcUtil.createDao(ItemDao.class, innerDataSource);

        new TransactionTemplate(outer.transactionManager).executeWithoutResult(a -> {
            try {
                JdbcUtil.executeUpdate(dataSource, "INSERT INTO jpa_item (name) VALUES ('a')");
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }

            assertThrows(innerFailure, () -> new TransactionTemplate(inner.transactionManager).executeWithoutResult(b -> {
                final SqlTransaction tran = JdbcUtil.beginTransaction(innerDataSource);

                try {
                    assertNotNull(innerDao.insert(innerItem));
                } catch (final SQLException e) {
                    throw new IllegalStateException(e);
                } finally {
                    tran.rollbackIfNotCommitted();
                }
            }));
        });
    }

    private static Item item(final String name) {
        final Item item = new Item();
        item.setName(name);
        return item;
    }

    /**
     * A JpaTransactionManager over a mocked entity manager whose resource-local transaction runs on one H2 connection, which
     * a JPA dialect exposes as the transaction's JDBC connection, as HibernateJpaDialect does.
     */
    /** A cache by map that counts the results stored in it. */
    private static Jdbc.DaoCache countingCache(final AtomicInteger stores) {
        final Jdbc.DaoCache map = Jdbc.DaoCache.createByMap();

        return new Jdbc.DaoCache() {
            @Override
            public Object get(final String key, final Object daoProxy, final Object[] args,
                    final com.landawn.abacus.util.Tuple.Tuple3<java.lang.reflect.Method, com.landawn.abacus.util.ImmutableList<Class<?>>, Class<?>> methodSignature) {
                return map.get(key, daoProxy, args, methodSignature);
            }

            @Override
            public boolean put(final String key, final Object result, final Object daoProxy, final Object[] args,
                    final com.landawn.abacus.util.Tuple.Tuple3<java.lang.reflect.Method, com.landawn.abacus.util.ImmutableList<Class<?>>, Class<?>> methodSignature) {
                stores.incrementAndGet();
                return map.put(key, result, daoProxy, args, methodSignature);
            }

            @Override
            public boolean put(final String key, final Object result, final long liveTime, final long maxIdleTime, final Object daoProxy, final Object[] args,
                    final com.landawn.abacus.util.Tuple.Tuple3<java.lang.reflect.Method, com.landawn.abacus.util.ImmutableList<Class<?>>, Class<?>> methodSignature) {
                stores.incrementAndGet();
                return map.put(key, result, liveTime, maxIdleTime, daoProxy, args, methodSignature);
            }

            @Override
            public void update(final String key, final Object result, final Object daoProxy, final Object[] args,
                    final com.landawn.abacus.util.Tuple.Tuple3<java.lang.reflect.Method, com.landawn.abacus.util.ImmutableList<Class<?>>, Class<?>> methodSignature) {
                map.update(key, result, daoProxy, args, methodSignature);
            }
        };
    }

    private static final class JpaTransaction {
        final Connection connection;
        final H2EntityTransaction entityTransaction;
        final JpaTransactionManager transactionManager;
        /** Runs when the JPA dialect releases the JDBC connection in Spring's cleanup. */
        volatile Runnable onRelease;
        /** Runs when the entity manager is closed, once it is marked closed, as EclipseLink's close does before releasing. */
        volatile Runnable onClose;

        /**
         * @param factoryReportsDataSource whether the entity manager factory reports its data source, as the factories built by
         *        Spring's LocalContainerEntityManagerFactoryBean do ({@link EntityManagerFactoryInfo#getDataSource()})
         * @param handleKeepsEntityManager whether the exposed connection's handle keeps the entity manager, as the handles of
         *        Spring's Hibernate and EclipseLink dialects do
         */
        JpaTransaction(final DataSource dataSource, final boolean factoryReportsDataSource, final boolean handleKeepsEntityManager) throws SQLException {
            connection = dataSource.getConnection();
            entityTransaction = new H2EntityTransaction(connection);

            final EntityManager entityManager = mock(EntityManager.class);
            when(entityManager.getTransaction()).thenReturn(entityTransaction);
            // Open from its creation until it is closed, as JpaTransactionManager does at the end of its cleanup.
            final java.util.concurrent.atomic.AtomicBoolean open = new java.util.concurrent.atomic.AtomicBoolean();
            when(entityManager.isOpen()).thenAnswer(invocation -> open.get());
            org.mockito.Mockito.doAnswer(invocation -> {
                open.set(false);

                final Runnable hook = onClose;

                if (hook != null) {
                    hook.run();
                }

                return null;
            }).when(entityManager).close();
            final EntityManagerFactory nativeEntityManagerFactory = mock(EntityManagerFactory.class);
            when(nativeEntityManagerFactory.createEntityManager()).thenAnswer(invocation -> {
                open.set(true);
                return entityManager;
            });
            final EntityManagerFactory entityManagerFactory;

            if (factoryReportsDataSource) {
                entityManagerFactory = mock(EntityManagerFactory.class, org.mockito.Mockito.withSettings().extraInterfaces(EntityManagerFactoryInfo.class));
                when(((EntityManagerFactoryInfo) entityManagerFactory).getDataSource()).thenReturn(dataSource);
                when(((EntityManagerFactoryInfo) entityManagerFactory).getNativeEntityManagerFactory()).thenReturn(nativeEntityManagerFactory);
                when(((EntityManagerFactoryInfo) entityManagerFactory).createNativeEntityManager(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
                    open.set(true);
                    return entityManager;
                });
            } else {
                entityManagerFactory = nativeEntityManagerFactory;
            }

            transactionManager = new JpaTransactionManager(entityManagerFactory);
            transactionManager.setDataSource(dataSource);
            transactionManager.setJpaDialect(new DefaultJpaDialect() {
                @Override
                public ConnectionHandle getJdbcConnection(final EntityManager em, final boolean readOnly) {
                    return handleKeepsEntityManager ? new EntityManagerConnectionHandle(em, connection) : () -> connection;
                }

                @Override
                public void releaseJdbcConnection(final ConnectionHandle conHandle, final EntityManager em) {
                    final Runnable hook = onRelease;

                    if (hook != null) {
                        hook.run();
                    }
                }
            });
        }
    }

    /** A connection handle that keeps the entity manager whose connection it exposes, as HibernateJpaDialect's handle does. */
    private static final class EntityManagerConnectionHandle implements ConnectionHandle {
        @SuppressWarnings("unused")
        private final EntityManager entityManager;
        private final Connection connection;

        EntityManagerConnectionHandle(final EntityManager entityManager, final Connection connection) {
            this.entityManager = entityManager;
            this.connection = connection;
        }

        @Override
        public Connection getConnection() {
            return connection;
        }
    }

    /** A resource-local JPA transaction on one JDBC connection. */
    private static final class H2EntityTransaction implements EntityTransaction {
        private final Connection connection;
        private boolean active;
        private boolean rollbackOnly;
        boolean committed;
        boolean rolledBack;
        /** Whether {@link #setRollbackOnly()} fails, as a provider may. */
        boolean refusesRollbackOnly;
        /** Whether {@link #commit()} fails without committing, leaving the work pending on the connection. */
        boolean failsCommit;
        /** Whether {@link #rollback()} fails without rolling back, leaving the work pending on the connection. */
        boolean failsRollback;

        H2EntityTransaction(final Connection connection) {
            this.connection = connection;
        }

        @Override
        public void begin() {
            run(() -> connection.setAutoCommit(false));
            active = true;
            rollbackOnly = false;
        }

        @Override
        public void commit() {
            if (rollbackOnly) {
                rollback();
                throw new jakarta.persistence.RollbackException("Transaction marked rollback-only");
            }

            if (failsCommit) {
                throw new jakarta.persistence.RollbackException("Simulated commit failure");
            }

            run(connection::commit);
            committed = true;
            end();
        }

        @Override
        public void rollback() {
            if (failsRollback) {
                throw new jakarta.persistence.PersistenceException("Simulated rollback failure");
            }

            run(connection::rollback);
            rolledBack = true;
            end();
        }

        private void end() {
            active = false;
            run(() -> connection.setAutoCommit(true));
        }

        @Override
        public void setRollbackOnly() {
            if (refusesRollbackOnly) {
                throw new jakarta.persistence.PersistenceException("Cannot mark the transaction rollback-only");
            }

            rollbackOnly = true;
        }

        @Override
        public boolean getRollbackOnly() {
            return rollbackOnly;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public void setTimeout(final Integer timeout) {
        }

        @Override
        public Integer getTimeout() {
            return null;
        }

        private static void run(final SqlAction action) {
            try {
                action.run();
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private interface SqlAction {
        void run() throws SQLException;
    }

    /**
     * Binds its connection with a handle of its own (as an ORM transaction manager exposing its JDBC connection does), but
     * consults no rollback-only mark: its transaction object is not a SmartTransactionObject.
     */
    private static final class MarkIgnoringTransactionManager extends AbstractPlatformTransactionManager {
        private final DataSource dataSource;
        boolean rolledBack;

        MarkIgnoringTransactionManager(final DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        protected Object doGetTransaction() {
            return new Connection[1];
        }

        @Override
        protected boolean isExistingTransaction(final Object transaction) {
            return ((Connection[]) transaction)[0] != null;
        }

        @Override
        protected void doBegin(final Object transaction, final TransactionDefinition definition) {
            try {
                final Connection connection = dataSource.getConnection();
                connection.setAutoCommit(false);
                ((Connection[]) transaction)[0] = connection;
                TransactionSynchronizationManager.bindResource(dataSource, new ConnectionHolder((ConnectionHandle) () -> connection));
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        protected void doCommit(final DefaultTransactionStatus status) {
            H2EntityTransaction.run(((Connection[]) status.getTransaction())[0]::commit);
        }

        @Override
        protected void doRollback(final DefaultTransactionStatus status) {
            H2EntityTransaction.run(((Connection[]) status.getTransaction())[0]::rollback);
            rolledBack = true;
        }

        @Override
        protected void doCleanupAfterCompletion(final Object transaction) {
            TransactionSynchronizationManager.unbindResource(dataSource);
            final Connection connection = ((Connection[]) transaction)[0];
            H2EntityTransaction.run(() -> connection.setAutoCommit(true));
            H2EntityTransaction.run(connection::close);
        }
    }

    // The reflective JPA accessor caches of the Spring rollback actions are ClassValues (released with the class) instead of
    // static maps keyed by class (which pinned application/provider classes for the JVM's life). Pins the lookup-once
    // semantics they keep: resolved once per class, and a class without the method is remembered as absent, so a later
    // call fails without looking again.
    @Test
    public void testMethodValue_ResolvesOncePerClassAndRemembersAbsence() throws Exception {
        final AtomicInteger lookups = new AtomicInteger();
        final JdbcUtil.MethodValue hashCode = new JdbcUtil.MethodValue("hashCode", type -> {
            lookups.incrementAndGet();
            return type.getMethod("hashCode");
        });

        assertSame(hashCode.methodOf(String.class), hashCode.methodOf(String.class));
        assertEquals(String.class.getMethod("hashCode"), hashCode.methodOf(String.class));
        assertEquals(1, lookups.get());
        assertEquals(Integer.class.getMethod("hashCode"), hashCode.methodOf(Integer.class));
        assertEquals(2, lookups.get());

        final JdbcUtil.MethodValue absent = new JdbcUtil.MethodValue("noSuchMethod", type -> {
            lookups.incrementAndGet();
            throw new NoSuchMethodException(type.getName());
        });
        assertThrows(NoSuchMethodException.class, () -> absent.methodOf(String.class));
        assertThrows(NoSuchMethodException.class, () -> absent.methodOf(String.class));
        assertEquals(3, lookups.get());

        // The default lookup resolves the public no-arg method of that name.
        assertEquals(String.class.getMethod("isEmpty"), new JdbcUtil.MethodValue("isEmpty").methodOf(String.class));
        assertThrows(NoSuchMethodException.class, () -> new JdbcUtil.MethodValue("isEmpty").methodOf(Integer.class));
    }

    public interface ItemDao extends CrudDao<Item, Long, ItemDao> {
    }

    public interface CachedItemDao extends com.landawn.abacus.jdbc.dao.NonUpdateCrudDao<Item, Long, CachedItemDao> {
        @com.landawn.abacus.jdbc.annotation.CacheResult(enabled = true)
        @com.landawn.abacus.jdbc.annotation.Query("SELECT COUNT(*) FROM jpa_item")
        int cachedCount() throws SQLException;

        @com.landawn.abacus.jdbc.annotation.RefreshCache
        @com.landawn.abacus.jdbc.annotation.Query("INSERT INTO jpa_item (name) VALUES (:name)")
        void addItem(@com.landawn.abacus.jdbc.annotation.Bind("name") String name) throws SQLException;
    }

    @Table("jpa_item")
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
}
