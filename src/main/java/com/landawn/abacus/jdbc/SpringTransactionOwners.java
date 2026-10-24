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

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The Spring-managed transactions whose transaction manager a {@link SpringTransactionTracker} is registered with, by the
 * connection holder each one binds to its data source: the owner of a connection holder, as reported by its transaction
 * manager. Spring-free: the holders are {@code Object}s, and only {@code SpringTransactionTracker} names Spring types.
 *
 * <p>WHY: without it, this library told the completion of the Spring transaction that owns a data source's connection from
 * Spring's transaction synchronization, which is that of the innermost transaction on the thread (another transaction
 * manager's transaction begun inside the owner, or none with synchronization disabled), and from the holder's
 * transaction-active flag, which only {@code DataSourceTransactionManager} and {@code JdbcTransactionManager} keep, and which
 * another thread reads without any guarantee of seeing it change. The tracker hears from the owning transaction manager
 * itself, and publishes what it hears in {@code volatile} fields.</p>
 */
final class SpringTransactionOwners {

    /**
     * The owners by connection holder, weakly referenced: an entry stays after its transaction has completed, until its holder
     * has been garbage collected, a tracked transaction that reuses the holder replaces it, or an untracked one that reuses it
     * makes it stale (see {@link #of(Object)}). WHY it stays: Spring still runs the transaction's later execution listeners
     * and cleans up after them, while the holder is bound and transaction-active; a {@code beginTransaction} there that found
     * no owner joined the committed connection (with synchronization disabled, as nothing else told the completion), and
     * Spring's cleanup, restoring auto-commit, committed its work although it rolled back. Each {@link Owner} is referenced
     * strongly by its entry.
     */
    private static final Map<Object, Owner> owners = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Whether a tracker has reported a transaction: the lookups are skipped until then.
     */
    private static volatile boolean tracking; //NOSONAR

    private SpringTransactionOwners() {
        // utility class
    }

    /**
     * What the transaction manager's side tells of Spring's cleanup after one transaction, which the tracker can't hear of:
     * Spring calls its execution listeners before the cleanup, not after. Implemented by {@link SpringTransactionTracker},
     * by the kind of transaction; Spring-free.
     */
    interface Cleanup {
        /**
         * Returns whether Spring's cleanup after the transaction has finished, so that the connection is settled: the cleanup
         * may still commit work a failed commit or rollback left pending on it ({@code DataSourceTransactionManager} restores
         * auto-commit; a JPA dialect's connection release, or the closing of the entity manager, may do likewise). Readable on
         * any thread, without a guarantee of seeing the end at once (the caches then only stay out longer).
         *
         * @return {@code true} if so; {@code false} also if it can't be told (the holder's garbage collection then tells it,
         *         see {@link Owner#isCompleted()})
         */
        boolean isFinished();

        /**
         * Returns whether the holder of the transaction, bound to its data source on the current thread, belongs to a newer
         * transaction now (a holder bound before both transactions is reused by each). Called on that thread only.
         *
         * @return {@code true} if so
         */
        boolean isHolderReused();
    }

    /**
     * The completion of one Spring-managed transaction, as its transaction manager reports it to a
     * {@link SpringTransactionTracker}. Readable on any thread.
     */
    static final class Owner {
        /** Set once the transaction manager is about to commit or roll back, after the {@code beforeCompletion} callbacks. */
        private volatile boolean deciding; //NOSONAR

        /**
         * Set once it has committed or rolled back cleanly: by the tracker's {@code afterCompletion} synchronization, after
         * the database commit or rollback, or else by its {@code afterCommit}/{@code afterRollback} listener callback.
         */
        private volatile boolean completed; //NOSONAR

        /**
         * Set once its commit or rollback has failed: its outcome is uncertain until Spring's cleanup has finished, which may
         * still commit what the failed commit or rollback left pending on the connection.
         */
        private volatile boolean uncertain; //NOSONAR

        /**
         * The holder, for when no completion is reported (see {@link #isCompleted()}): weakly referenced, as the entry of
         * {@link SpringTransactionOwners#owners} keeps the owner.
         */
        private final WeakReference<Object> holder;

        /** What tells how far Spring's cleanup after the transaction has got. */
        private final Cleanup cleanup;

        Owner(final Object holder, final Cleanup cleanup) {
            this.holder = new WeakReference<>(holder);
            this.cleanup = cleanup;
        }

        /**
         * Returns whether the outcome of the transaction is being decided or has been: its transaction manager is committing
         * or rolling it back, runs its completion callbacks, or has completed it. Work that joins it from then on is no part
         * of the outcome (the cleanup after the transaction commits whatever runs on its connection meanwhile).
         *
         * @return {@code true} if so
         */
        boolean isDeciding() {
            return deciding || completed || uncertain;
        }

        /**
         * Returns whether the transaction has committed or rolled back: as reported (see {@link #completed}), or else once
         * Spring's cleanup after it has finished (see {@link Cleanup#isFinished()}), if no report came, or the report was a
         * failure (see {@link #uncertain}). WHY the cleanup: an earlier execution listener that throws keeps Spring from
         * calling the tracker's, and with synchronization disabled no synchronization reports it either, so the owner stayed
         * pending, and the caches kept out of the transaction's tables, for good. WHY not a failure report: a read between it
         * and the end of the cleanup stored the rows as they were before the work that the cleanup then committed; so did one
         * after the start of the cleanup (the transaction status's completion, which is its first step), before a JPA
         * dialect's connection release committed the work. A holder garbage collected has completed too: the only end told
         * where the cleanup's isn't.
         *
         * @return {@code true} if so
         */
        boolean isCompleted() {
            if (completed) {
                return true;
            }

            return holder.get() == null || cleanup.isFinished();
        }
    }

    /**
     * Records the transaction that has just bound {@code holder}.
     *
     * @param holder its connection holder
     * @param cleanup what tells how far Spring's cleanup after the transaction has got
     */
    static void began(final Object holder, final Cleanup cleanup) {
        tracking = true;
        owners.put(holder, new Owner(holder, cleanup));
    }

    /**
     * Records that the transaction that bound {@code holder} is about to commit or roll back.
     *
     * @param holder its connection holder
     */
    static void deciding(final Object holder) {
        final Owner owner = owners.get(holder);

        if (owner != null) {
            owner.deciding = true;
        }
    }

    /**
     * Records that the transaction that bound {@code holder} has committed or rolled back. Kept until the holder has been
     * garbage collected (see {@link #owners}).
     *
     * @param holder its connection holder
     */
    static void completed(final Object holder) {
        final Owner owner = owners.get(holder);

        if (owner != null) {
            owner.completed = true;
        }
    }

    /**
     * Records that the commit or rollback of the transaction that bound {@code holder} has failed: its outcome is uncertain
     * until Spring's cleanup has finished (see {@link Owner#isCompleted()}). A clean completion reported as well wins: a commit
     * failure that the transaction manager rolled back cleanly left nothing pending.
     *
     * @param holder its connection holder
     */
    static void uncertain(final Object holder) {
        final Owner owner = owners.get(holder);

        if (owner != null) {
            owner.uncertain = true;
        }
    }

    /**
     * Returns the owner of {@code holder}, if a tracker reported the transaction that bound it, also once it has completed,
     * unless a newer transaction has reused the holder since (see {@link Cleanup#isHolderReused()}): such an owner is
     * forgotten. WHY: a holder bound before a transaction is reused by each transaction on it; the owner a tracked one left
     * was taken for the owner of a later transaction of a transaction manager without a tracker, which was so taken for
     * completed: work in it ran in a transaction of its own, which survived its rollback.
     *
     * @param holder a connection holder bound to a data source on the current thread, or {@code null}
     * @return its owner, or {@code null} if none is known (no tracker is registered with its transaction manager)
     */
    static Owner of(final Object holder) {
        if (holder == null || !tracking) {
            return null;
        }

        final Owner owner = owners.get(holder);

        if (owner != null && owner.cleanup.isHolderReused()) {
            owners.remove(holder, owner);
            return null;
        }

        return owner;
    }
}
