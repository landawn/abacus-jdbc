package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @BeforeEach
    void createDatabase() throws SQLException {
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
            assertEquals(Transaction.Status.FAILED_ROLLBACK, tran.status());
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
                assertEquals(Transaction.Status.FAILED_ROLLBACK, tran.status());
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
                assertEquals(Transaction.Status.FAILED_ROLLBACK, tran.status());
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
                assertEquals(Transaction.Status.FAILED_ROLLBACK, tran.status());
                assertNotNull(item.getId(), "the ID follows the outcome, which the JPA transaction decides");
            });
        });

        assertTrue(jpa.entityTransaction.committed);
        assertEquals(1, count());
        assertNotNull(item.getId());
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
    private static final class JpaTransaction {
        final H2EntityTransaction entityTransaction;
        final JpaTransactionManager transactionManager;

        /**
         * @param factoryReportsDataSource whether the entity manager factory reports its data source, as the factories built by
         *        Spring's LocalContainerEntityManagerFactoryBean do ({@link EntityManagerFactoryInfo#getDataSource()})
         * @param handleKeepsEntityManager whether the exposed connection's handle keeps the entity manager, as the handles of
         *        Spring's Hibernate and EclipseLink dialects do
         */
        JpaTransaction(final DataSource dataSource, final boolean factoryReportsDataSource, final boolean handleKeepsEntityManager) throws SQLException {
            final Connection connection = dataSource.getConnection();
            entityTransaction = new H2EntityTransaction(connection);

            final EntityManager entityManager = mock(EntityManager.class);
            when(entityManager.getTransaction()).thenReturn(entityTransaction);
            final EntityManagerFactory nativeEntityManagerFactory = mock(EntityManagerFactory.class);
            when(nativeEntityManagerFactory.createEntityManager()).thenReturn(entityManager);
            final EntityManagerFactory entityManagerFactory;

            if (factoryReportsDataSource) {
                entityManagerFactory = mock(EntityManagerFactory.class, org.mockito.Mockito.withSettings().extraInterfaces(EntityManagerFactoryInfo.class));
                when(((EntityManagerFactoryInfo) entityManagerFactory).getDataSource()).thenReturn(dataSource);
                when(((EntityManagerFactoryInfo) entityManagerFactory).getNativeEntityManagerFactory()).thenReturn(nativeEntityManagerFactory);
                when(((EntityManagerFactoryInfo) entityManagerFactory).createNativeEntityManager(org.mockito.ArgumentMatchers.any())).thenReturn(entityManager);
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

            run(connection::commit);
            committed = true;
            end();
        }

        @Override
        public void rollback() {
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
