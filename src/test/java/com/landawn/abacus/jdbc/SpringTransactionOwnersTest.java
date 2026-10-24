package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.ConnectionHandle;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.landawn.abacus.TestBase;

import jakarta.persistence.EntityManager;

// The completion records SpringTransactionTracker reports to, and what tells them the progress of Spring's cleanup after a
// transaction (the tracker's cleanups).
class SpringTransactionOwnersTest extends TestBase {

    // External review (tests): a failed commit or rollback leaves the outcome uncertain until Spring's cleanup has finished,
    // but a clean completion reported for the same transaction wins, whichever is reported first: a commit failure that the
    // transaction manager then rolled back cleanly left nothing pending on the connection.
    @ParameterizedTest(name = "failureReportedFirst={0}")
    @ValueSource(booleans = { true, false })
    void aCleanCompletion_winsOverAFailureReportedForTheSameTransaction(final boolean failureReportedFirst) {
        final Object holder = new Object();
        SpringTransactionOwners.began(holder, new SettableCleanup());
        final SpringTransactionOwners.Owner owner = SpringTransactionOwners.of(holder);
        assertFalse(owner.isDeciding());
        assertFalse(owner.isCompleted());

        if (failureReportedFirst) {
            SpringTransactionOwners.uncertain(holder);
            assertTrue(owner.isDeciding(), "the transaction manager has decided");
            assertFalse(owner.isCompleted(), "uncertain until the cleanup has finished");
            SpringTransactionOwners.completed(holder);
        } else {
            SpringTransactionOwners.completed(holder);
            SpringTransactionOwners.uncertain(holder);
        }

        assertTrue(owner.isDeciding());
        assertTrue(owner.isCompleted(), "the clean completion wins");
        assertSame(owner, SpringTransactionOwners.of(holder), "kept until its holder is collected");
        Reference.reachabilityFence(holder);
    }

    // External review: a failed commit or rollback stays pending until Spring's cleanup has finished (not merely begun), with
    // the holder still reachable.
    @Test
    void aFailure_isPendingUntilSpringsCleanupHasFinished() {
        final Object holder = new Object();
        final SettableCleanup cleanup = new SettableCleanup();
        SpringTransactionOwners.began(holder, cleanup);
        SpringTransactionOwners.uncertain(holder);
        final SpringTransactionOwners.Owner owner = SpringTransactionOwners.of(holder);

        assertTrue(owner.isDeciding());
        assertFalse(owner.isCompleted());

        cleanup.finished.set(true);
        assertTrue(owner.isCompleted(), "completed once the cleanup has finished, the holder still reachable");
        Reference.reachabilityFence(holder);
    }

    // A holder garbage collected has completed: no transaction can use it any more. The only end told where the cleanup's
    // isn't.
    @Test
    void aCollectedHolder_completesItsTransaction() throws InterruptedException {
        Object holder = new Object();
        SpringTransactionOwners.began(holder, new SettableCleanup());
        SpringTransactionOwners.uncertain(holder);
        final SpringTransactionOwners.Owner owner = SpringTransactionOwners.of(holder);
        assertFalse(owner.isCompleted());
        Reference.reachabilityFence(holder);

        final WeakReference<Object> collected = new WeakReference<>(holder);
        holder = null;
        awaitCollected(collected);

        assertTrue(owner.isCompleted(), "completed once its holder has been collected");
    }

    // External review: the owner of a holder that a newer transaction has reused is forgotten, for good; until then, it is
    // the owner.
    @Test
    void anOwnerWhoseHolderIsReused_isForgotten() {
        final Object holder = new Object();
        final SettableCleanup cleanup = new SettableCleanup();
        SpringTransactionOwners.began(holder, cleanup);
        final SpringTransactionOwners.Owner owner = SpringTransactionOwners.of(holder);
        SpringTransactionOwners.completed(holder);

        assertSame(owner, SpringTransactionOwners.of(holder));

        cleanup.reused.set(true);
        assertNull(SpringTransactionOwners.of(holder));
        assertTrue(owner.isCompleted(), "the forgotten owner still tells its own transaction's completion");

        cleanup.reused.set(false);
        assertNull(SpringTransactionOwners.of(holder), "forgotten for good");
        Reference.reachabilityFence(holder);
    }

    // External review: the start of Spring's cleanup (the status's completion, its first step) was taken for its end. For a
    // DataSourceTransactionManager transaction, the cleanup then still restores auto-commit, committing what a failed commit
    // left pending; it clears the holder as its last step, which tells the end.
    @Test
    void aHolderCleanup_finishesOnlyWhenTheCleanupClearsTheHolder() {
        final ConnectionHolder holder = new ConnectionHolder(mock(Connection.class), true);
        final DefaultTransactionStatus status = newStatus();
        final SpringTransactionOwners.Cleanup cleanup = new SpringTransactionTracker.HolderCleanup(holder, status);

        assertFalse(cleanup.isFinished());

        status.setCompleted(); // the first step of the cleanup
        assertFalse(cleanup.isFinished(), "begun, not finished");

        holder.clear(); // its last step
        assertTrue(cleanup.isFinished());
    }

    // External review: inside its own cleanup (the status completed, the holder still transaction-active), a transaction's
    // holder was taken for one a newer transaction had reused, when a connection hook ran library code there. Spring clears
    // the thread's transaction state as the first step of the cleanup; a newer transaction with synchronization sets it again.
    // One without synchronization sets nothing, so it is not told (a documented limit).
    @Test
    void aHolderCleanup_tellsAReusedHolderByTheThreadsTransactionState() {
        final ReusableHolder holder = new ReusableHolder(mock(Connection.class));
        final DefaultTransactionStatus status = newStatus();
        final SpringTransactionOwners.Cleanup cleanup = new SpringTransactionTracker.HolderCleanup(holder, status);

        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertFalse(cleanup.isHolderReused(), "its own transaction runs");

            status.setCompleted(); // the first steps of the cleanup
            TransactionSynchronizationManager.setActualTransactionActive(false);
            assertFalse(cleanup.isHolderReused(), "inside the cleanup: the holder still active, the thread's transaction state cleared");

            holder.clear(); // the last step
            assertFalse(cleanup.isHolderReused(), "after the cleanup");

            holder.reuse();
            assertFalse(cleanup.isHolderReused(), "a newer transaction without synchronization: not told");

            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertTrue(cleanup.isHolderReused(), "a newer transaction with synchronization");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    // External review: for a JPA transaction, the close of its entity manager, the last step of the cleanup (after the
    // dialect's connection release), tells the end; an entity manager that can't tell leaves it to the holder's collection.
    @Test
    void anEntityManagerCleanup_finishesWhenTheEntityManagerIsClosed() {
        final EntityManager entityManager = mock(EntityManager.class);
        when(entityManager.isOpen()).thenReturn(true);
        final SpringTransactionOwners.Cleanup cleanup = new SpringTransactionTracker.EntityManagerCleanup(entityManager);

        assertFalse(cleanup.isFinished());

        when(entityManager.isOpen()).thenReturn(false);
        assertTrue(cleanup.isFinished());

        when(entityManager.isOpen()).thenThrow(new IllegalStateException("cannot tell"));
        assertFalse(cleanup.isFinished(), "can't tell: left to the holder's garbage collection");
        assertFalse(cleanup.isHolderReused(), "JpaTransactionManager binds a new holder for each transaction");
    }

    // External review (tests): which cleanup check each kind of transaction gets. A holder with Spring's own connection handle
    // (DataSourceTransactionManager, JdbcTransactionManager) gets the holder's.
    @Test
    void aHolderWithSpringsOwnHandle_getsTheHolderCleanup() {
        final ConnectionHolder holder = new ConnectionHolder(mock(Connection.class));

        assertTrue(SpringTransactionTracker.cleanupOf(newStatus(), holder) instanceof SpringTransactionTracker.HolderCleanup);
    }

    // A holder with a handle of its own whose transaction object keeps an EntityManagerHolder (as JpaTransactionManager's
    // does, behind a public method of a private class) gets the entity manager's check, on that entity manager.
    @Test
    void aJpaTransactionsHolder_getsTheEntityManagerCleanupOfItsEntityManager() {
        final EntityManager entityManager = mock(EntityManager.class);
        when(entityManager.isOpen()).thenReturn(true);
        final DefaultTransactionStatus status = newStatus(new JpaLikeTransactionObject(new EntityManagerHolder(entityManager)));
        final SpringTransactionOwners.Cleanup cleanup = SpringTransactionTracker.cleanupOf(status, ownHandleHolder());

        assertTrue(cleanup instanceof SpringTransactionTracker.EntityManagerCleanup, String.valueOf(cleanup));
        assertFalse(cleanup.isFinished());

        when(entityManager.isOpen()).thenReturn(false);
        assertTrue(cleanup.isFinished(), "told by that entity manager");
    }

    // Where neither tells the end (a holder with a handle of its own, and no entity manager found: another kind of transaction
    // object, another kind of execution, or a transaction object without an EntityManagerHolder), the fallback tells
    // nothing, leaving it to the holder's garbage collection.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "another transaction object", "another execution", "no EntityManagerHolder" })
    void aHolderWhoseCleanupCantBeTold_getsTheFallback(final String kind) {
        final TransactionExecution execution = switch (kind) {
            case "another transaction object" -> newStatus(new Object());
            case "another execution" -> new TransactionExecution() {
            };
            default -> newStatus(new JpaLikeTransactionObject(null));
        };
        final SpringTransactionOwners.Cleanup cleanup = SpringTransactionTracker.cleanupOf(execution, ownHandleHolder());

        assertFalse(cleanup instanceof SpringTransactionTracker.HolderCleanup);
        assertFalse(cleanup instanceof SpringTransactionTracker.EntityManagerCleanup);
        assertFalse(cleanup.isFinished());
        assertFalse(cleanup.isHolderReused());
    }

    // With the fallback, a failed transaction completes once its holder has been garbage collected.
    @Test
    void aFailedTransactionWithTheFallback_completesOnceItsHolderIsCollected() throws InterruptedException {
        ConnectionHolder holder = ownHandleHolder();
        SpringTransactionOwners.began(holder, SpringTransactionTracker.cleanupOf(newStatus(new Object()), holder));
        SpringTransactionOwners.uncertain(holder);
        final SpringTransactionOwners.Owner owner = SpringTransactionOwners.of(holder);
        assertFalse(owner.isCompleted());
        Reference.reachabilityFence(holder);

        final WeakReference<Object> collected = new WeakReference<>(holder);
        holder = null;
        awaitCollected(collected);

        assertTrue(owner.isCompleted());
    }

    // External review (tests): a transaction status garbage collected can no longer be committed or rolled back, so Spring's
    // cleanup after it counts as begun: the holder, active again with a Spring transaction on the thread, is reused.
    @Test
    void aHolderCleanupWhoseStatusIsCollected_takesTheCleanupForBegun() throws InterruptedException {
        final ReusableHolder holder = new ReusableHolder(mock(Connection.class));
        DefaultTransactionStatus status = newStatus();
        final SpringTransactionOwners.Cleanup cleanup = new SpringTransactionTracker.HolderCleanup(holder, status);
        final WeakReference<Object> collected = new WeakReference<>(status);

        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertFalse(cleanup.isHolderReused(), "its status is reachable and not completed");

            status = null;
            awaitCollected(collected);
            assertTrue(cleanup.isHolderReused(), "the status collected: a newer transaction runs with the holder");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    // A holder garbage collected has nothing left to clean up, nor to reuse.
    @Test
    void aHolderCleanupWhoseHolderIsCollected_isFinished() throws InterruptedException {
        ConnectionHolder holder = new ConnectionHolder(mock(Connection.class), true);
        final SpringTransactionOwners.Cleanup cleanup = new SpringTransactionTracker.HolderCleanup(holder, newStatus());
        assertFalse(cleanup.isFinished());

        final WeakReference<Object> collected = new WeakReference<>(holder);
        holder = null;
        awaitCollected(collected);

        assertTrue(cleanup.isFinished());
        assertFalse(cleanup.isHolderReused());
    }

    // An entity manager garbage collected can no longer be used: its cleanup counts as finished.
    @Test
    void anEntityManagerCleanupWhoseEntityManagerIsCollected_isFinished() throws InterruptedException {
        EntityManager entityManager = (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(), new Class<?>[] { EntityManager.class },
                (proxy, method, args) -> {
                    if ("isOpen".equals(method.getName())) {
                        return true;
                    }

                    throw new UnsupportedOperationException(method.getName());
                });
        final SpringTransactionOwners.Cleanup cleanup = new SpringTransactionTracker.EntityManagerCleanup(entityManager);
        assertFalse(cleanup.isFinished(), "open");

        final WeakReference<Object> collected = new WeakReference<>(entityManager);
        entityManager = null;
        awaitCollected(collected);

        assertTrue(cleanup.isFinished());
    }

    private static void awaitCollected(final WeakReference<?> reference) throws InterruptedException {
        for (int attempt = 0; attempt < 100 && reference.get() != null; attempt++) {
            System.gc();
            Thread.sleep(10);
        }

        assertNull(reference.get(), "not garbage collected");
    }

    private static DefaultTransactionStatus newStatus() {
        return newStatus(new Object());
    }

    private static DefaultTransactionStatus newStatus(final Object transaction) {
        return new DefaultTransactionStatus(null, transaction, true, true, false, false, false, null);
    }

    /** A holder with a connection handle of its own, as a JPA transaction manager binds. */
    private static ConnectionHolder ownHandleHolder() {
        return new ConnectionHolder((ConnectionHandle) () -> {
            throw new UnsupportedOperationException("not used");
        });
    }

    /**
     * Stands for JpaTransactionManager's transaction object: its EntityManagerHolder behind a public method of a private
     * class.
     */
    private static final class JpaLikeTransactionObject {
        private final EntityManagerHolder entityManagerHolder;

        JpaLikeTransactionObject(final EntityManagerHolder entityManagerHolder) {
            this.entityManagerHolder = entityManagerHolder;
        }

        @SuppressWarnings("unused") // read reflectively
        public EntityManagerHolder getEntityManagerHolder() {
            return entityManagerHolder;
        }
    }

    /** A cleanup whose progress the test sets. */
    private static final class SettableCleanup implements SpringTransactionOwners.Cleanup {
        final AtomicBoolean finished = new AtomicBoolean();
        final AtomicBoolean reused = new AtomicBoolean();

        @Override
        public boolean isFinished() {
            return finished.get();
        }

        @Override
        public boolean isHolderReused() {
            return reused.get();
        }
    }

    /** A connection holder that a transaction manager reuses, as it does one bound before its transaction. */
    private static final class ReusableHolder extends ConnectionHolder {
        ReusableHolder(final Connection connection) {
            super(connection, true);
        }

        void reuse() {
            setTransactionActive(true);
        }
    }
}
