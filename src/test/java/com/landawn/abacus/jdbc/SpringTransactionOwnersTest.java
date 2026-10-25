package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.ConnectionHandle;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.JdbcTransactionObjectSupport;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.landawn.abacus.TestBase;

// The completion records SpringTransactionTracker reports to, and the tracker's reports.
class SpringTransactionOwnersTest extends TestBase {

    // External review (tests): a failed commit or rollback leaves the outcome uncertain, also after Spring's cleanup has
    // finished, until a clean completion report or a confirmed settlement; a clean completion reported for the same
    // transaction wins, whichever is reported first: a commit failure that the transaction manager then rolled back cleanly
    // left nothing pending on the connection.
    @ParameterizedTest(name = "failureReportedFirst={0}")
    @ValueSource(booleans = { true, false })
    void aCleanCompletion_winsOverAFailureReportedForTheSameTransaction(final boolean failureReportedFirst) {
        final Object holder = ownHandleHolder();
        final SpringTransactionOwners.Owner owner = began(new Object(), holder);
        assertFalse(owner.isDeciding());
        assertFalse(owner.isCompleted());

        if (failureReportedFirst) {
            owner.uncertain();
            assertTrue(owner.isDeciding(), "the transaction manager has decided");
            assertFalse(owner.isCompleted(), "uncertain until a clean completion report or a confirmed settlement");
            owner.completed();
        } else {
            owner.completed();
            owner.uncertain();
        }

        assertTrue(owner.isDeciding());
        assertTrue(owner.isCompleted(), "the clean completion wins");
        assertSame(owner, SpringTransactionOwners.of(holder), "kept until its holder is collected");
        Reference.reachabilityFence(holder);
    }

    // Spring clears the holder even when restoring auto-commit fails. That cannot confirm settlement.
    @Test
    void aFailureOnAHolderWithSpringsOwnHandle_remainsPendingAfterTheHolderIsCleared() {
        final ConnectionHolder holder = new ConnectionHolder(mock(Connection.class), true);
        final SpringTransactionOwners.Owner owner = began(new Object(), holder);
        owner.uncertain();

        assertTrue(owner.isDeciding());
        assertFalse(owner.isCompleted());

        holder.clear(); // also happens when resetting the connection failed
        assertFalse(owner.isCompleted());
    }

    // A holder's collection says nothing about a connection independently owned by an entity manager.
    @Test
    void aFailureOnAHolderThatTellsNothing_remainsPendingAfterItsHolderIsCollected() throws InterruptedException {
        ConnectionHolder holder = ownHandleHolder();
        final SpringTransactionOwners.Owner owner = began(new Object(), holder);
        owner.uncertain();

        holder.clear();
        assertFalse(owner.isCompleted(), "the holder's state tells nothing");
        Reference.reachabilityFence(holder);

        final WeakReference<Object> collected = new WeakReference<>(holder);
        holder = null;
        awaitCollected(collected);

        assertFalse(owner.isCompleted(), "GC is not settlement");
    }

    // External review: a report reaches the record of the transaction it is about, made when that transaction began, not
    // whichever record the holder's entry holds when it comes: an old transaction's reports, from its listener callbacks or
    // its synchronization, can't complete a newer transaction on the same holder.
    @Test
    void anOldTransactionsReports_reachOnlyItsOwnRecord() {
        final SpringTransactionTracker tracker = new SpringTransactionTracker();
        final ConnectionHolder holder = new ConnectionHolder(mock(Connection.class), true);
        final DefaultTransactionStatus older = newStatus(transactionObject(holder), true);
        final DefaultTransactionStatus newer = newStatus(transactionObject(holder), true);

        try {
            TransactionSynchronizationManager.initSynchronization();
            tracker.afterBegin(older, null);
            final SpringTransactionOwners.Owner olderOwner = SpringTransactionOwners.of(holder);
            final TransactionSynchronization olderSynchronization = TransactionSynchronizationManager.getSynchronizations().get(0);
            TransactionSynchronizationManager.clearSynchronization();

            tracker.afterBegin(newer, null);
            final SpringTransactionOwners.Owner newerOwner = SpringTransactionOwners.of(holder);
            assertNotSame(olderOwner, newerOwner);

            tracker.beforeCommit(older);
            tracker.afterCommit(older, new IllegalStateException("simulated commit failure"));
            olderSynchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

            assertTrue(olderOwner.isCompleted());
            assertFalse(newerOwner.isDeciding(), "the newer transaction is untouched");
            assertFalse(newerOwner.isCompleted());
            assertSame(newerOwner, SpringTransactionOwners.of(holder));
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }
    }

    // External review: the owner is kept through its transaction's completion and cleanup, also once its holder is
    // transaction-active again with a Spring transaction active on the thread (which a connection hook inside the cleanup
    // may begin on another data source): only a tracked transaction beginning with the holder replaces it.
    @Test
    void anOwner_isKeptUntilATrackedTransactionBeginsWithItsHolder() {
        final SpringTransactionTracker tracker = new SpringTransactionTracker();
        final ReusableHolder holder = new ReusableHolder(mock(Connection.class));
        final DefaultTransactionStatus first = newStatus(transactionObject(holder), true);
        tracker.afterBegin(first, null);
        final SpringTransactionOwners.Owner owner = SpringTransactionOwners.of(holder);
        tracker.afterCommit(first, null);
        first.setCompleted(); // the first step of Spring's cleanup

        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertSame(owner, SpringTransactionOwners.of(holder), "inside the cleanup, another transaction active on the thread");

            holder.clear();
            holder.reuse();
            assertSame(owner, SpringTransactionOwners.of(holder), "reused by a transaction of a transaction manager without a tracker");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        tracker.afterBegin(newStatus(transactionObject(holder), true), null);
        assertNotSame(owner, SpringTransactionOwners.of(holder), "replaced by a tracked transaction");
        assertTrue(owner.isCompleted());
    }

    // A scope participating in a transaction, or a savepoint of one, is no new transaction: it is not recorded, and reports
    // about it reach no record.
    @Test
    void reportsAboutAnExecutionThatIsNoNewTransaction_areIgnored() {
        final SpringTransactionTracker tracker = new SpringTransactionTracker();
        final ConnectionHolder holder = new ConnectionHolder(mock(Connection.class), true);
        final DefaultTransactionStatus participating = newStatus(transactionObject(holder), false);

        tracker.afterBegin(participating, null);
        tracker.beforeCommit(participating);
        tracker.afterCommit(participating, null);

        assertNull(SpringTransactionOwners.of(holder));
        assertNull(SpringTransactionOwners.ofTransaction(participating));
    }

    // The strong cache registry must survive collection of both weak map keys and all application references to the owner.
    @Test
    void aCacheBlock_survivesCollectionUntilItsOwnSettlementIsConfirmed() throws Exception {
        Object holder = ownHandleHolder();
        Object transaction = new Object();
        SpringTransactionOwners.Owner owner = began(transaction, holder);
        owner.cacheWrite("owner_gc_test");
        owner.uncertain();
        final long id = owner.id();
        final WeakReference<Object> holderRef = new WeakReference<>(holder);
        final WeakReference<Object> transactionRef = new WeakReference<>(transaction);
        final WeakReference<SpringTransactionOwners.Owner> ownerRef = new WeakReference<>(owner);
        holder = null;
        transaction = null;
        owner = null;
        try {
            awaitCollected(holderRef);
            awaitCollected(transactionRef);
            assertNotNull(ownerRef.get(), "the cache registry retains only the lightweight owner");
            assertFalse(ownerRef.get().isCompleted());
            assertTrue(isListed(id));
            assertTrue(SpringTransactionTracker.confirmSettled(id));
            assertTrue(ownerRef.get().isCompleted());
            assertFalse(SpringTransactionTracker.confirmSettled(id), "idempotent");
            assertFalse(isListed(id));
        } finally {
            SpringTransactionTracker.confirmSettled(id);
        }
    }

    // A snapshot taken for A must never release B, including when B fails after the snapshot on another thread.
    @Test
    void confirmingASnapshotId_leavesAConcurrentFailureBlocked() throws Exception {
        final SpringTransactionOwners.Owner a = began(new Object(), new Object());
        a.cacheWrite("same_table");
        a.uncertain();
        final var snapshot = SpringTransactionTracker.unresolvedTransactions();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        final var snapshotOfA = snapshot.stream().filter(record -> record.id() == a.id()).toList();
        assertEquals(1, snapshotOfA.size());
        assertThrows(UnsupportedOperationException.class, () -> snapshotOfA.get(0).tables().add("other"));
        final SpringTransactionOwners.Owner b = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            final var owner = began(new Object(), new Object());
            owner.cacheWrite("same_table");
            owner.uncertain();
            return owner;
        }).get(10, java.util.concurrent.TimeUnit.SECONDS);
        try {
            assertTrue(SpringTransactionTracker.confirmSettled(snapshotOfA.get(0).id()));
            assertFalse(b.isCompleted());
            assertFalse(isListed(a.id()));
            assertTrue(isListed(b.id()));
        } finally {
            a.completed();
            b.completed();
        }
        assertFalse(isListed(b.id()));
    }

    @Test
    void aLateCleanCompletion_releasesTheRegistryAndALateFailureCannotReinsertIt() {
        final var owner = began(new Object(), new Object());
        owner.cacheWrite("late_completion");
        owner.uncertain();
        final long id = owner.id();
        assertTrue(isListed(id));
        owner.completed();
        owner.uncertain();
        owner.cacheWrite("late_write_notification");
        assertTrue(owner.isCompleted());
        assertFalse(SpringTransactionTracker.confirmSettled(id));
        assertFalse(isListed(id));
    }

    // A missing callback is detected after the execution ends, but that observation only enters the unresolved state.
    @Test
    void aMissingCompletionReport_keepsACacheBlockButTransactionsWithoutCacheWritesAreNotRetained() {
        final var ended = new java.util.concurrent.atomic.AtomicBoolean();
        final var owner = SpringTransactionOwners.began(new Object(), new Object(), ended::get, "missing-report");
        owner.cacheWrite("missing_report_table");
        assertFalse(isListed(owner.id()), "active transactions are excluded");
        ended.set(true);
        final var snapshot = recordOf(owner.id());
        assertEquals("missing-report", snapshot.transactionName());
        assertEquals("no completion report", snapshot.failure());
        assertFalse(owner.isCompleted());
        assertTrue(owner.isDeciding());
        assertTrue(SpringTransactionTracker.confirmSettled(owner.id()));
        final var noWrite = SpringTransactionOwners.began(new Object(), new Object(), () -> true, "no-cache-write");
        noWrite.uncertain();
        assertFalse(isListed(noWrite.id()));
    }

    // External review: a transaction still active with no failure report can't have its settlement confirmed (its work may
    // still change): one-by-one and in bulk, and in bulk nothing is then confirmed.
    @Test
    void confirmingAnActiveTransaction_throwsAndConfirmsNothing() {
        final var active = began(new Object(), new Object());
        active.cacheWrite("active_table");
        final var failed = began(new Object(), new Object());
        failed.cacheWrite("active_table");
        failed.uncertain();

        try {
            assertThrows(IllegalStateException.class, () -> SpringTransactionTracker.confirmSettled(active.id()));
            assertThrows(IllegalStateException.class, () -> SpringTransactionTracker.confirmSettled(List.of(failed.id(), active.id())));
            assertTrue(isListed(failed.id()), "nothing confirmed");
            assertFalse(active.isCompleted());
        } finally {
            active.completed();
            failed.completed();
        }
    }

    // External review: bulk confirmation confirms exactly the IDs given (those of a verified snapshot), skips an unknown or
    // resolved one, and leaves a transaction that became unresolved meanwhile blocked; its arguments are checked first.
    @Test
    void confirmingSettledTransactionsInBulk_confirmsOnlyTheGivenIds() throws Exception {
        final var a = began(new Object(), new Object());
        a.cacheWrite("bulk_table");
        a.uncertain();
        final var b = began(new Object(), new Object());
        b.cacheWrite("bulk_table");
        b.uncertain();
        final var resolved = began(new Object(), new Object());
        resolved.cacheWrite("bulk_table");
        resolved.uncertain();
        resolved.completed();
        final List<Long> verified = SpringTransactionTracker.unresolvedTransactions()
                .stream()
                .map(SpringTransactionTracker.UnresolvedTransaction::id)
                .filter(id -> id == a.id() || id == b.id())
                .toList();
        final var concurrent = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            final var owner = began(new Object(), new Object());
            owner.cacheWrite("bulk_table");
            owner.uncertain();
            return owner;
        }).get(10, java.util.concurrent.TimeUnit.SECONDS);

        try {
            assertThrows(IllegalArgumentException.class, () -> SpringTransactionTracker.confirmSettled((java.util.Collection<Long>) null));
            assertThrows(IllegalArgumentException.class, () -> SpringTransactionTracker.confirmSettled(java.util.Arrays.asList(a.id(), null)));
            assertTrue(isListed(a.id()), "nothing confirmed by a rejected call");

            final List<Long> ids = new java.util.ArrayList<>(verified);
            ids.add(resolved.id());
            ids.add(-1L);
            ids.add(a.id());
            assertEquals(List.of(a.id(), b.id()), SpringTransactionTracker.confirmSettled(ids));
            assertTrue(a.isCompleted());
            assertTrue(b.isCompleted());
            assertTrue(isListed(concurrent.id()), "unresolved after the snapshot: stays blocked");
            assertEquals(SpringTransactionTracker.unresolvedTransactions().size(), SpringTransactionTracker.unresolvedTransactionCount());
            assertEquals(List.of(), SpringTransactionTracker.confirmSettled(ids), "idempotent");
        } finally {
            a.completed();
            b.completed();
            concurrent.completed();
        }
    }

    // External review: bulk confirmation read the completion flag twice when it checked a supplied ID, so a transaction that
    // completed between the two reads (a late clean report, or a confirmation by another caller) looked active, and the whole
    // batch was rejected with IllegalStateException, leaving its other IDs unconfirmed. No hook sits between those reads, so
    // the race is run many times, the completion delayed by a random spin to sweep the window: the bulk call never throws,
    // and confirms the other supplied ID every time.
    @Test
    void confirmingInBulk_whileASuppliedTransactionCompletesConcurrently_neverRejectsTheBatch() throws Exception {
        final int rounds = 20_000;
        final java.util.concurrent.atomic.AtomicReference<SpringTransactionOwners.Owner> target = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicInteger started = new java.util.concurrent.atomic.AtomicInteger(-1);
        final java.util.concurrent.atomic.AtomicInteger finished = new java.util.concurrent.atomic.AtomicInteger(-1);
        final java.util.concurrent.atomic.AtomicReference<Throwable> completerFailure = new java.util.concurrent.atomic.AtomicReference<>();
        final Thread completer = new Thread(() -> {
            final java.util.Random random = new java.util.Random(42);

            try {
                for (int round = 0; round < rounds; round++) {
                    while (started.get() < round) {
                        if (Thread.currentThread().isInterrupted()) {
                            return;
                        }

                        Thread.onSpinWait();
                    }

                    final SpringTransactionOwners.Owner owner = target.get();

                    for (int spin = random.nextInt(64); spin > 0; spin--) {
                        Thread.onSpinWait();
                    }

                    if (round % 2 == 0) {
                        owner.completed(); // a late clean report
                    } else {
                        SpringTransactionTracker.confirmSettled(owner.id()); // another caller's confirmation
                    }

                    finished.set(round);
                }
            } catch (final Throwable e) {
                completerFailure.set(e);
                finished.set(Integer.MAX_VALUE);
            }
        }, "concurrent-completer");
        completer.setDaemon(true);

        // Each round leaves two transactions unresolved for a moment, and each logs its warning: 40,000 console lines made
        // the test take seconds. Quietened for the loop only (the tests' log4j2 configuration logs this package at debug).
        final String loggerName = SpringTransactionOwners.class.getName();
        final org.apache.logging.log4j.Level level = org.apache.logging.log4j.LogManager.getLogger(loggerName).getLevel();
        org.apache.logging.log4j.core.config.Configurator.setLevel(loggerName, org.apache.logging.log4j.Level.ERROR);
        completer.start();

        int rejected = 0;

        try {
            for (int round = 0; round < rounds; round++) {
                final SpringTransactionOwners.Owner a = began(new Object(), new Object());
                a.cacheWrite("bulk_race_table");
                a.uncertain();
                final SpringTransactionOwners.Owner b = began(new Object(), new Object());
                b.cacheWrite("bulk_race_table");
                b.uncertain();
                target.set(a);
                started.set(round);

                try {
                    SpringTransactionTracker.confirmSettled(List.of(a.id(), b.id()));
                    assertTrue(b.isCompleted(), "the other supplied transaction is confirmed, round " + round);
                } catch (final IllegalStateException e) {
                    rejected++;
                }

                while (finished.get() < round) {
                    Thread.onSpinWait();
                }

                a.completed();
                b.completed();
            }
        } finally {
            completer.interrupt();
            completer.join(10_000);
            org.apache.logging.log4j.core.config.Configurator.setLevel(loggerName, level);
        }

        assertNull(completerFailure.get());
        assertEquals(0, rejected, "batches rejected because a supplied transaction completed concurrently");
    }

    // External review: the snapshot tells when the transaction began, when its outcome was first seen unresolved, and what
    // left it unresolved, as a compact description: the reported failure's class and one-line message, truncated, or the
    // unknown outcome Spring reported without an exception; the first failure with an exception wins.
    @Test
    void anUnresolvedTransaction_describesWhenAndWhy() {
        final long before = System.currentTimeMillis();
        final var unknown = began(new Object(), new Object());
        unknown.cacheWrite("diagnosed_table");
        unknown.uncertain();
        final var failed = began(new Object(), new Object());
        failed.cacheWrite("diagnosed_table");
        failed.uncertain(); // the synchronization's unknown outcome comes first
        failed.uncertain(new IllegalStateException("first line\n" + "x".repeat(500)));
        failed.uncertain(new IllegalArgumentException("a later failure"));

        try {
            final var unknownRecord = recordOf(unknown.id());
            assertEquals("unknown outcome", unknownRecord.failure());
            assertTrue(unknownRecord.beganAt().toEpochMilli() >= before);
            assertFalse(unknownRecord.unresolvedSince().isBefore(unknownRecord.beganAt()));

            final String failure = recordOf(failed.id()).failure();
            assertTrue(failure.startsWith("java.lang.IllegalStateException: first line x"), failure);
            assertTrue(failure.endsWith("..."), failure);
            assertEquals("java.lang.IllegalStateException: ".length() + SpringTransactionOwners.MAX_FAILURE_MESSAGE_LENGTH + 3, failure.length());
            assertFalse(failure.contains("\n"));
            assertEquals("java.lang.RuntimeException", SpringTransactionOwners.describe(new RuntimeException()));
        } finally {
            unknown.completed();
            failed.completed();
        }
    }

    // External review: no exception object is retained by an owner or its snapshot: one can retain application objects
    // through its cause or fields for as long as the transaction stays unresolved.
    @Test
    void anUnresolvedTransaction_retainsNoException() {
        for (final java.lang.reflect.Field field : SpringTransactionOwners.Owner.class.getDeclaredFields()) {
            assertFalse(Throwable.class.isAssignableFrom(field.getType()), field.toString());
        }

        for (final java.lang.reflect.RecordComponent component : SpringTransactionTracker.UnresolvedTransaction.class.getRecordComponents()) {
            assertFalse(Throwable.class.isAssignableFrom(component.getType()), component.toString());
        }
    }

    // External review: an owner is deciding once Spring has ended its transaction's execution, also when no report came,
    // so work no longer joins its connection.
    @Test
    void anOwner_isDecidingOnceTheExecutionEndedWithoutAReport() {
        final var ended = new java.util.concurrent.atomic.AtomicBoolean();
        final var owner = SpringTransactionOwners.began(new Object(), new Object(), ended::get, "");

        assertFalse(owner.isDeciding());
        ended.set(true);
        assertTrue(owner.isDeciding());
        assertFalse(owner.isCompleted(), "no clean report");
    }

    // External review (efficiency): the completion check runs for every cache read of a table a pending transaction wrote. It
    // takes no lock while the transaction is in flight, nor once the warning about an unresolved outcome has been logged: it
    // called a synchronized method on every such read, so the readers of the table contended for the owner's monitor. White
    // box, deliberately: a helper thread holds that monitor, the lock the readers used to take, while the check runs on
    // another thread, which must return without waiting for it.
    @Test
    void isCompleted_takesNoLockWhileInFlightOrOnceWarned() throws Exception {
        final var inFlight = began(new Object(), new Object());
        inFlight.cacheWrite("lock_free_table");
        final var alreadyWarned = began(new Object(), new Object());
        alreadyWarned.cacheWrite("lock_free_table");
        alreadyWarned.uncertain(); // logs the warning, once

        try {
            assertFalse(checkWhileLocked(inFlight, inFlight::isCompleted), "in flight");
            assertFalse(checkWhileLocked(alreadyWarned, alreadyWarned::isCompleted), "unresolved, its warning logged already");
        } finally {
            inFlight.completed();
            alreadyWarned.completed();
        }
    }

    /**
     * Runs {@code check} on another thread while a helper thread holds {@code monitor}, failing if the check waits for it.
     * Released in any case, so that a failure leaves no thread blocked.
     */
    private static boolean checkWhileLocked(final Object monitor, final java.util.function.BooleanSupplier check) throws Exception {
        final java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final Thread holder = new Thread(() -> {
            synchronized (monitor) {
                locked.countDown();

                try {
                    release.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "owner-monitor-holder");
        holder.setDaemon(true);
        holder.start();
        final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();

        try {
            assertTrue(locked.await(10, java.util.concurrent.TimeUnit.SECONDS), "the helper thread took the monitor");
            final java.util.concurrent.Future<Boolean> result = executor.submit(check::getAsBoolean);

            try {
                return result.get(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (final java.util.concurrent.TimeoutException e) {
                return org.junit.jupiter.api.Assertions.fail("the completion check waited for the owner's lock");
            }
        } finally {
            release.countDown();
            holder.join(10_000);
            executor.shutdownNow();
        }
    }

    /** Records a tracked transaction that is never reported ended by its status, with no name. */
    private static SpringTransactionOwners.Owner began(final Object transaction, final Object holder) {
        return SpringTransactionOwners.began(transaction, holder, () -> false, "");
    }

    /** Returns whether {@link SpringTransactionTracker#unresolvedTransactions()} lists {@code id}: the registry is process-wide. */
    private static boolean isListed(final long id) {
        return SpringTransactionTracker.unresolvedTransactions().stream().anyMatch(record -> record.id() == id);
    }

    private static SpringTransactionTracker.UnresolvedTransaction recordOf(final long id) {
        return SpringTransactionTracker.unresolvedTransactions().stream().filter(record -> record.id() == id).findFirst().orElseThrow();
    }

    private static void awaitCollected(final WeakReference<?> reference) throws InterruptedException {
        for (int attempt = 0; attempt < 100 && reference.get() != null; attempt++) {
            System.gc();
            Thread.sleep(10);
        }

        assertNull(reference.get(), "not garbage collected");
    }

    private static DefaultTransactionStatus newStatus(final Object transaction, final boolean newTransaction) {
        return new DefaultTransactionStatus(null, transaction, newTransaction, newTransaction, false, false, false, null);
    }

    /** A transaction object exposing {@code holder}, as DataSourceTransactionManager's does. */
    private static JdbcTransactionObjectSupport transactionObject(final ConnectionHolder holder) {
        final JdbcTransactionObjectSupport transactionObject = new JdbcTransactionObjectSupport() {
            @Override
            public boolean isRollbackOnly() {
                return false;
            }
        };

        transactionObject.setConnectionHolder(holder);

        return transactionObject;
    }

    /** A holder with a connection handle of its own, as a JPA transaction manager binds. */
    private static ConnectionHolder ownHandleHolder() {
        return new ConnectionHolder((ConnectionHandle) () -> {
            throw new UnsupportedOperationException("not used");
        });
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
