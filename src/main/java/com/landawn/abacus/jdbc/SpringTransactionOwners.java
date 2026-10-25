/*
 * Copyright (c) 2026, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.landawn.abacus.jdbc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;

/**
 * The Spring-managed transactions whose transaction manager a {@link SpringTransactionTracker} is registered with, by the
 * connection holder each one binds to its data source: the owner of a connection holder, as reported by its transaction
 * manager. Spring-free: the holders and transactions are {@code Object}s, and only {@code SpringTransactionTracker} names
 * Spring types.
 *
 * <p>WHY: without it, this library told the completion of the Spring transaction that owns a data source's connection from
 * Spring's transaction synchronization, which is that of the innermost transaction on the thread (another transaction
 * manager's transaction begun inside the owner, or none with synchronization disabled), and from the holder's
 * transaction-active flag, which only {@code DataSourceTransactionManager} and {@code JdbcTransactionManager} keep, and which
 * another thread reads without any guarantee of seeing it change. The tracker hears from the owning transaction manager
 * itself, and publishes what it hears in {@code volatile} fields.</p>
 *
 * <p><b>What does not prove that a transaction's work has settled</b>, so that a DAO cache block must outlast it. Each of
 * these was used at some point, and each let a read cache rows that pending work later changed:</p>
 * <ul>
 *   <li>The connection holder's transaction-active flag. Spring clears the holder even when restoring auto-commit failed;
 *       and on a holder bound before the transaction, with auto-commit already off, Spring neither restores auto-commit nor
 *       releases the connection, so a single failed commit leaves the work pending after the holder has been cleared.</li>
 *   <li>The transaction status's completion. Spring sets it as the first step of its cleanup, before the transaction
 *       manager's own cleanup, which may still commit the work (restoring auto-commit, or a JPA dialect's connection
 *       release). It only tells that a report is missing (see {@code Owner.executionEnded}).</li>
 *   <li>{@code EntityManager.isOpen()}. A provider may mark the entity manager closed before releasing its resources
 *       (EclipseLink does), and JPA lets a persistence context stay managed when it is closed during an active
 *       transaction. Nor does {@code close()} returning prove it: Spring logs and swallows a failed connection release
 *       and a failed close.</li>
 *   <li>The garbage collection of the holder. An entity manager bound before the transaction (open-in-view) keeps the
 *       connection, and the pending work, after the holder has been collected.</li>
 *   <li>The thread's transaction state, to tell that a later transaction reuses a holder. It belongs to whatever
 *       transaction runs on the thread, possibly on another data source, such as one a connection hook inside Spring's
 *       cleanup begins (see {@link #owners}).</li>
 *   <li>Elapsed time, or a restart: nothing bounds when pending work may still commit.</li>
 * </ul>
 *
 * <p>Only a clean commit or rollback report, or an explicit confirmation (see
 * {@link SpringTransactionTracker#confirmSettled(long)}), ends a block. WHY no cleanup-aware {@code JpaTransactionManager}
 * subclass reporting once Spring's cleanup has returned: the cleanup returning proves nothing either (see above); observing
 * the release and the close would mean wrapping the JPA dialect and every entity manager the transaction manager creates;
 * it would need spring-orm in the main code and the application's own transaction manager bean; and it would still not
 * cover an entity manager bound before the transaction.</p>
 */
final class SpringTransactionOwners {

    private static final Logger logger = LoggerFactory.getLogger(SpringTransactionOwners.class);

    /** The longest part of a failure's message kept in its description (see {@link #describe(Throwable)}). */
    static final int MAX_FAILURE_MESSAGE_LENGTH = 200;

    private static final AtomicLong nextId = new AtomicLong();

    // Strongly held from the first cache-affecting write, not just from a failure callback: that callback may never arrive.
    // Owners contain no strong reference to a connection, holder, transaction status, entity manager or exception. A weak
    // PendingCommits key alone allowed GC to resume caching while an independently owned connection could still commit its
    // pending work.
    private static final Map<Long, Owner> pending = new ConcurrentHashMap<>();

    /**
     * A snapshot of an unresolved transaction (see {@link SpringTransactionTracker.UnresolvedTransaction}).
     *
     * @param id the owner's ID
     * @param transactionName Spring's transaction name, or an empty string
     * @param tables the tables it wrote through DAOs with caches, ordered ignoring case
     * @param beganAt when the transaction began, in epoch milliseconds
     * @param unresolvedSince when this library first saw its outcome unresolved, in epoch milliseconds
     * @param failure the description of the failure reported, or of the missing report
     */
    record Unresolved(long id, String transactionName, List<String> tables, long beganAt, long unresolvedSince, String failure) {
    }

    /**
     * The owners by connection holder, weakly referenced: the owner of the transaction that last began with a holder, kept
     * after that transaction has completed until the holder has been garbage collected, or a tracked transaction that begins
     * with the holder replaces it. WHY it stays: Spring still runs the transaction's later execution listeners and cleans up
     * after them, while the holder is bound and transaction-active; a {@code beginTransaction} there that found no owner joined
     * the committed connection (with synchronization disabled, as nothing else told the completion), and Spring's cleanup,
     * restoring auto-commit, committed its work although it rolled back. WHY nothing else drops it: the owner was dropped once
     * its holder looked reused by a newer transaction (cleanup begun, holder transaction-active again, a Spring transaction
     * active on the thread), which the transaction's own cleanup matched as well when a connection hook there began a
     * transaction on another data source: a scope begun in that one joined the connection the cleanup then committed, its
     * rollback notwithstanding. A later transaction of a transaction manager without a tracker, on a holder bound before a
     * tracked one, is so taken for that completed transaction (registering the tracker with only some transaction managers is
     * unsupported). Each {@link Owner} is referenced strongly by its entry.
     */
    private static final Map<Object, Owner> owners = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * The owners by transaction: by the execution object (the transaction status) Spring passes to every callback of one
     * transaction, weakly referenced, so that each report reaches the record of the transaction it is about. WHY: looked up
     * by holder when a report came, it reached whichever owner the holder's entry then held, which could be that of a later
     * transaction on the same holder.
     */
    private static final Map<Object, Owner> ownersByTransaction = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Whether a tracker has reported a transaction: the lookups are skipped until then.
     */
    private static volatile boolean tracking; //NOSONAR

    private SpringTransactionOwners() {
        // utility class
    }

    /**
     * The completion of one Spring-managed transaction, as its transaction manager reports it to a
     * {@link SpringTransactionTracker}. Readable on any thread.
     */
    static final class Owner {
        private final long id = nextId.incrementAndGet();
        private final String transactionName;
        private final long beganAt = System.currentTimeMillis();

        /**
         * Tells whether Spring has ended the transaction's execution: its status is completed, or has been garbage
         * collected. Spring completes the status as the first step of its cleanup, after every completion report, so a
         * transaction whose execution has ended without a clean report either failed or had its report lost (a failing
         * listener, with synchronization disabled: see the registration requirements of {@link SpringTransactionTracker}).
         * Only used to tell that (see {@link #isDeciding()} and {@code isUnresolved()}), never to release a cache block: the
         * cleanup that follows may still commit pending work (see the class description).
         *
         * <p>Spring keeps the completion in a plain, non-volatile field, written by the thread that completes the
         * transaction, so the Java memory model gives another thread no happens-before with that write. Cross-thread
         * detection of missing completion reports is therefore best-effort and is not guaranteed while the transaction
         * status remains reachable. Until detection, unresolved snapshots may omit the transaction and confirmation may be
         * rejected; caching remains blocked. The thread that completes the transaction sees the completion at once.
         * {@code isDeciding()} is also read on other threads: the parallel-stream worker threads of a deferred query (see
         * {@code TransactionContext.assertDeferredQueryCanRun()}). Such a worker sees a completion that happened before the
         * stream ran, as the stream hands it the owner thread's earlier actions. A thread that consumes the stream without
         * that handoff (which is unsupported) may miss the completion, and the deferred query then runs.</p>
         */
        private final BooleanSupplier executionEnded;

        /**
         * The tables the transaction wrote through DAOs with caches, ignoring case; {@code null} until the first such write.
         * WHY lazily: most transactions write no cached table, and every tracked transaction gets an owner. Guarded by this.
         */
        private Set<String> tables;

        /** When this library first saw the outcome unresolved, in epoch milliseconds; {@code 0} before. Guarded by this. */
        private long unresolvedSince;

        /**
         * The description of the first failure reported with an exception (see {@link #describe(Throwable)}); {@code null}
         * if none. WHY not the exception: it can retain application objects through its cause or fields, for as long as the
         * transaction stays unresolved. Guarded by this.
         */
        private String failure;

        /**
         * Whether the warning about the unresolved outcome has been logged. Volatile, read first without the lock: see
         * {@link #needsWarning()}.
         */
        private volatile boolean warned; //NOSONAR

        /** Set once the transaction manager is about to commit or roll back, after the {@code beforeCompletion} callbacks. */
        private volatile boolean deciding; //NOSONAR

        /**
         * Set once it has committed or rolled back cleanly: by the tracker's {@code afterCompletion} synchronization, after
         * the database commit or rollback, or else by its {@code afterCommit}/{@code afterRollback} listener callback, or
         * once its settlement has been confirmed (see {@link #confirmSettled()}).
         */
        private volatile boolean completed; //NOSONAR

        /** A failed completion needs either a later clean completion report or explicit confirmation of settlement. */
        private volatile boolean uncertain; //NOSONAR

        Owner(final BooleanSupplier executionEnded, final String transactionName) {
            this.executionEnded = executionEnded;
            this.transactionName = transactionName == null ? "" : transactionName;
        }

        /**
         * Returns the owner's ID, unique within this process.
         *
         * @return the ID
         */
        long id() {
            return id;
        }

        /**
         * Returns whether the outcome of the transaction is being decided or has been: its transaction manager is committing
         * or rolling it back, runs its completion callbacks, or has completed it (also when no report of it came: Spring has
         * ended its execution). This is the library's conservative boundary: a later execution listener's beforeCommit
         * work could still join the physical commit, but is deliberately refused. Synchronization beforeCommit work runs
         * before this boundary and remains allowed. Work after the actual outcome can instead be committed by cleanup.
         *
         * <p>WHY the end of the execution as well: with no report at all (here, a listener before the tracker failed from both
         * {@code beforeCommit} and {@code afterRollback}, with synchronization disabled), a {@code SqlTransaction} scope left
         * open in the transaction committed after it, with the status {@code COMMITTED}, although Spring had rolled its work
         * back.</p>
         *
         * @return {@code true} if so
         */
        boolean isDeciding() {
            return deciding || completed || uncertain || executionEnded.getAsBoolean();
        }

        /**
         * Returns whether a clean completion was reported or settlement was explicitly confirmed. Neither GC nor resource
         * flags prove settlement: an entity manager may outlive its holder, and Spring clears a JDBC holder even when
         * restoring auto-commit failed (see the class description for every signal that was tried and why it fails). The
         * weak execution reference is used only to detect a missing report, never to release a cache block.
         *
         * <p>A pure volatile read: the pending-writes map calls this while holding its monitor. In particular, do not log
         * here: even logging outside this owner's monitor still held the caller's map monitor and stalled other cache
         * readers and writers. The caller emits any warning after releasing that monitor.</p>
         *
         * @return {@code true} if so
         */
        boolean isCompleted() {
            return completed;
        }

        /** Returns whether a warning may be due, without taking a lock or invoking the logger. */
        boolean needsWarning() {
            return !warned && isUnresolved();
        }

        /**
         * Records that the transaction is about to commit or roll back.
         */
        void deciding() {
            deciding = true;
        }

        /**
         * Records that the transaction has committed or rolled back. The owner stays the holder's until the holder has been
         * garbage collected (see {@link SpringTransactionOwners#owners}).
         */
        synchronized void completed() {
            completed = true;
            pending.remove(id, this);
        }

        /**
         * Records a failed completion that Spring reported without its exception (an unknown outcome reported to the
         * synchronizations). A later clean completion wins; a late failure cannot undo a clean completion or reinsert a
         * resolved record.
         */
        void uncertain() {
            uncertain(null);
        }

        /**
         * Records a failed completion, keeping a description of the first failure reported with an exception (not the
         * exception itself, see {@link #failure}). A later clean completion wins; a late failure cannot undo a clean
         * completion or reinsert a resolved record. Serialized with the cache writes and the confirmation.
         *
         * @param failureReported the failure, or {@code null} if Spring reported none
         */
        void uncertain(final Throwable failureReported) {
            synchronized (this) {
                uncertain = true;

                if (failure == null && failureReported != null) {
                    failure = describe(failureReported);
                }

                noteIfUnresolved();
            }

            warnIfUnresolved();
        }

        /**
         * Records a write of {@code table} through a DAO with a cache: from then on the owner is held strongly until a clean
         * completion report or a confirmation of its settlement, so that a missing report can't release the cache block.
         *
         * <p>WHY from the first such write: not from the transaction's begin, as most transactions write no cached table and
         * need no record; and not from a failure report, which may never come. WHY strongly: the block is a weak key of the
         * pending writes' map, which garbage collection released while pending work could still commit. The cost: when the
         * reports are lost (a failing listener, with synchronization disabled: see the registration requirements of
         * {@link SpringTransactionTracker}), every
         * transaction that writes a cached table stays recorded, and its tables blocked, until its settlement is confirmed,
         * which the warning and {@link SpringTransactionTracker#unresolvedTransactions()} make visible.</p>
         *
         * @param table the table, or an empty name (or {@code null}) for every table
         */
        void cacheWrite(final String table) {
            synchronized (this) {
                if (completed) {
                    return;
                }

                if (tables == null) {
                    tables = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                }

                tables.add(table == null ? "" : table);
                pending.put(id, this);
                noteIfUnresolved();
            }

            warnIfUnresolved();
        }

        private boolean isUnresolved() {
            return !completed && (uncertain || executionEnded.getAsBoolean());
        }

        /** Notes when the outcome was first seen unresolved. Called holding the lock. */
        private void noteIfUnresolved() {
            if (unresolvedSince == 0 && isUnresolved()) {
                unresolvedSince = System.currentTimeMillis();
            }
        }

        /**
         * Logs, once, that the transaction's outcome is unresolved, with the tables whose caching it keeps disabled. The
         * decision is taken under the lock, the logging outside it.
         */
        void warnIfUnresolved() {
            final List<String> tablesWritten;
            final String failureDescription;

            synchronized (this) {
                if (warned || tables == null || !isUnresolved()) {
                    return;
                }

                warned = true;
                noteIfUnresolved();
                tablesWritten = List.copyOf(tables);
                failureDescription = failureDescription();
            }

            logger.warn(
                    "Spring transaction id={} ('{}') has no confirmed settlement ({}); DAO caching is disabled for tables {} (empty name means all"
                            + " tables). After verifying that its pending work can no longer commit, call SpringTransactionTracker.confirmSettled({}).",
                    id, transactionName, failureDescription, tablesWritten, id);
        }

        /** Returns the description of what left the outcome unresolved. Called holding the lock. */
        private String failureDescription() {
            if (failure != null) {
                return failure;
            }

            return uncertain ? "unknown outcome" : "no completion report";
        }

        /**
         * Returns a snapshot of the owner if its outcome is unresolved.
         *
         * @return the snapshot, or {@code null} if the outcome is not unresolved, or the owner blocks no cache
         */
        synchronized Unresolved snapshot() {
            if (tables == null || !isUnresolved()) {
                return null;
            }

            noteIfUnresolved();

            return new Unresolved(id, transactionName, List.copyOf(tables), beganAt, unresolvedSince, failureDescription());
        }

        /**
         * Returns whether the outcome is unresolved, or the transaction has completed, so that its settlement may be confirmed.
         *
         * <p>Synchronized with {@link #completed()} and {@link #confirmSettled()}, which set the completion flag under this
         * lock, so that the flag is read in one state. WHY: it was read twice without the lock, here and in
         * {@code isUnresolved()}; a transaction completed in between, by a late clean report or another caller's
         * confirmation, looked neither completed nor unresolved, that is active, and a bulk confirmation rejected its whole
         * batch, leaving the other IDs unconfirmed. Bulk confirmation is rare: the lock costs nothing that matters.</p>
         *
         * @return {@code false} if the transaction is still active with no failure reported
         */
        synchronized boolean isConfirmable() {
            return completed || isUnresolved();
        }

        /**
         * Records an externally verified settlement: the owner completes, and its cache blocks expire on the next read.
         *
         * <p>WHY a transaction still active is refused: its work may still change, so releasing its block would let reads
         * cache rows it then changes; and its ID reaches no caller through a warning or a snapshot (both list unresolved
         * transactions only), so confirming it is a mistake, such as a stale or guessed ID.</p>
         *
         * @return {@code true} if this call resolved it; {@code false} if it had completed already
         * @throws IllegalStateException if the transaction is still active with no failure reported
         */
        synchronized boolean confirmSettled() throws IllegalStateException {
            if (completed) {
                return false;
            }

            if (!isUnresolved()) {
                throw new IllegalStateException("The Spring transaction is still active, with no failure reported: " + id);
            }

            completed();
            return true;
        }
    }

    /**
     * Returns a compact description of {@code failure}: its class name and its message, on one line, truncated to
     * {@link #MAX_FAILURE_MESSAGE_LENGTH} characters.
     *
     * @param failure the failure
     * @return the description
     */
    static String describe(final Throwable failure) {
        final String message = failure.getMessage();

        if (message == null || message.isEmpty()) {
            return failure.getClass().getName();
        }

        final String oneLine = message.replace('\r', ' ').replace('\n', ' ');

        return failure.getClass().getName() + ": "
                + (oneLine.length() > MAX_FAILURE_MESSAGE_LENGTH ? oneLine.substring(0, MAX_FAILURE_MESSAGE_LENGTH) + "..." : oneLine);
    }

    /**
     * Records the transaction {@code transaction} that has just bound {@code holder}: its owner replaces that of an earlier
     * transaction with the holder.
     *
     * @param transaction the execution object Spring passes to every callback of the transaction
     * @param holder its connection holder
     * @param executionEnded tells whether Spring has ended the transaction's execution (see {@link Owner#executionEnded})
     * @param transactionName Spring's transaction name, or {@code null}
     * @return its owner, to which the reports of {@code transaction} go
     */
    static Owner began(final Object transaction, final Object holder, final BooleanSupplier executionEnded, final String transactionName) {
        tracking = true;

        final Owner owner = new Owner(executionEnded, transactionName);
        owners.put(holder, owner);
        ownersByTransaction.put(transaction, owner);

        return owner;
    }

    /**
     * Returns snapshots of the owners whose outcome is unresolved and that block a cache, ordered by ID, logging the warning
     * of any not warned of yet.
     *
     * @return the snapshots
     */
    static List<Unresolved> unresolvedTransactions() {
        final List<Unresolved> result = new ArrayList<>();

        for (final Owner owner : pending.values()) {
            final Unresolved snapshot = owner.snapshot();

            if (snapshot != null) {
                result.add(snapshot);
                owner.warnIfUnresolved();
            }
        }

        result.sort(Comparator.comparingLong(Unresolved::id));
        return List.copyOf(result);
    }

    /**
     * Confirms the settlement of the owner with ID {@code id} (see {@link Owner#confirmSettled()}).
     *
     * @param id the ID
     * @return {@code true} if this call resolved it; {@code false} if it is unknown or had completed already
     * @throws IllegalStateException if its transaction is still active with no failure reported
     */
    static boolean confirmSettled(final long id) throws IllegalStateException {
        final Owner owner = pending.get(id);
        return owner != null && owner.confirmSettled();
    }

    /**
     * Confirms the settlement of the owners with the IDs {@code ids}, and of no other: an ID unknown or completed already is
     * skipped. Nothing is confirmed if one of them belongs to a transaction still active with no failure reported.
     *
     * @param ids the IDs, none {@code null}
     * @return the IDs this call resolved, in the order given, without duplicates
     * @throws IllegalStateException if one of them belongs to a transaction still active with no failure reported
     */
    static List<Long> confirmSettled(final Collection<Long> ids) throws IllegalStateException {
        final List<Owner> toConfirm = new ArrayList<>(ids.size());

        // Checked for all first: an active transaction can only become unresolved or complete, never the reverse, so none of
        // those checked can make a later confirmation throw.
        for (final Long id : new LinkedHashSet<>(ids)) {
            final Owner owner = pending.get(id);

            if (owner != null) {
                if (!owner.isConfirmable()) {
                    throw new IllegalStateException("The Spring transaction is still active, with no failure reported: " + id);
                }

                toConfirm.add(owner);
            }
        }

        final List<Long> confirmed = new ArrayList<>(toConfirm.size());

        for (final Owner owner : toConfirm) {
            if (owner.confirmSettled()) {
                confirmed.add(owner.id());
            }
        }

        return List.copyOf(confirmed);
    }

    /**
     * Returns the owner recorded for {@code transaction} when it began.
     *
     * @param transaction the execution object Spring passes to the callbacks of a transaction
     * @return its owner, or {@code null} if it is not a transaction the tracker recorded (a scope participating in one, or a
     *         savepoint of one, which complete with it)
     */
    static Owner ofTransaction(final Object transaction) {
        return ownersByTransaction.get(transaction);
    }

    /**
     * Returns the owner of {@code holder}, if a tracker reported the transaction that last began with it, also once that
     * transaction has completed.
     *
     * @param holder a connection holder bound to a data source, or {@code null}
     * @return its owner, or {@code null} if none is known (no tracker is registered with its transaction manager)
     */
    static Owner of(final Object holder) {
        return holder == null || !tracking ? null : owners.get(holder);
    }
}
