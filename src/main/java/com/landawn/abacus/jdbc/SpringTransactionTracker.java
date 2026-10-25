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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.JdbcTransactionObjectSupport;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tells this library, from a Spring transaction manager, when each transaction it runs is committed or rolled back, so that
 * the library follows the transaction that actually owns a data source's connection. Optional: register it with every
 * transaction manager whose transactions this library's work joins ({@code DataSourceTransactionManager},
 * {@code JdbcTransactionManager}, {@code JpaTransactionManager} or another {@code AbstractPlatformTransactionManager} whose
 * transaction object exposes its JDBC connection holder); it needs Spring 6.1 or later. One instance can be registered with
 * any number of transaction managers: it keeps no state of its own.
 *
 * <p>Without it, this library tells the completion of the Spring transaction it participates in from Spring's transaction
 * synchronization, which is that of the innermost transaction on the thread, and, after it, from the transaction-active flag
 * of the connection holder, which only {@code DataSourceTransactionManager} and {@code JdbcTransactionManager} keep. That
 * misjudges these cases, which the tracker gets right:</p>
 * <ul>
 *   <li>Work joining a transaction from inside another transaction manager's transaction begun in it: the completion of
 *       that inner transaction is taken for the enclosing one's while its callbacks run, and the enclosing one's
 *       {@code afterCommit} callbacks are not recognized if this library used it only inside the inner one (see
 *       {@link JdbcUtil#beginTransaction(javax.sql.DataSource)}).</li>
 *   <li>A synchronization registered from another one's completion callbacks, which runs after this library's, sees the
 *       completion as over (see {@link SqlTransaction}).</li>
 *   <li>A JPA transaction: its completion is not recognized once its callbacks have run (a {@code SqlTransaction} left
 *       open past it commits without failing), and a write in it keeps the DAO result caches out of a table only until
 *       Spring's synchronization reports the innermost transaction's commit (see
 *       {@link com.landawn.abacus.jdbc.annotation.CacheResult}).</li>
 *   <li>Another thread may read the holder's flag without seeing it change: the caches then stay out of a table longer.</li>
 * </ul>
 *
 * <p><b>Registration requirements.</b> Register it <i>first</i>, before the other execution listeners of a transaction
 * manager: Spring calls them in order, and one that throws from {@code afterCommit} or {@code afterRollback} keeps Spring
 * from calling those after it, so a tracker after it does not hear of the completion from its own callbacks.
 * {@link #register(ConfigurableTransactionManager...)} does that, and a {@link Registrar} bean does it for every transaction
 * manager of a Spring application context. Registering it first does not cover every failing listener: one that throws
 * from {@code beforeRollback}, or throws a {@code TransactionException} other than an
 * {@code UnexpectedRollbackException} (such as a {@code TransactionSystemException}) from {@code beforeCommit} (unless
 * {@code rollbackOnCommitFailure} is set), keeps Spring from calling every listener's {@code afterRollback} or
 * {@code afterCommit}, wherever the tracker is registered. In these cases the tracker's synchronization still reports the
 * completion, unless synchronization is disabled (see below). A listener that throws an
 * {@code UnexpectedRollbackException} from {@code beforeCommit} is unsupported: Spring then reports a clean rollback to
 * every listener and synchronization, although it neither committed nor rolled back, and its cleanup may then commit the
 * work (restoring auto-commit commits it). The tracker cannot tell this from a real rollback, so it releases the caches,
 * and a read before that commit can store rows that the work then changes. Register it with <i>every</i> transaction
 * manager on a data source: registered with some only, it is unsupported (the transactions of a transaction manager
 * without it get the behavior described above, and one of them on a connection holder bound to the data source before a
 * tracked transaction, which it reuses, is taken for that completed transaction: work in it runs apart from it, and
 * survives its rollback). A transaction begun before the tracker is registered is not tracked; nor is a participating
 * scope or a savepoint of a tracked transaction, which completes with it.</p>
 *
 * <p>Each report applies to the record of the transaction it is about, made when that transaction began: a later
 * transaction with the same connection holder gets a record of its own. The completion is also reported by a transaction
 * synchronization the tracker registers when a transaction begins, as Spring runs every synchronization's
 * {@code afterCompletion} callback whatever the others throw.</p>
 *
 * <p>A failed or unreported completion keeps affected DAO caches disabled until a later clean completion report or
 * explicit {@link #confirmSettled(long)} confirmation. This applies to JDBC and JPA alike: Spring clears JDBC holders even
 * when resetting the connection failed. A single failed commit can also leave pending work on a pre-bound connection
 * whose auto-commit was already disabled: Spring neither restores auto-commit nor releases that connection during cleanup.
 * Neither an entity manager's closed flag nor garbage collection proves that pending database work has settled.
 * A successful rollback reported after a failed commit automatically releases that transaction's cache block; explicit
 * confirmation is needed only when no clean completion is reported. Cache-affecting transactions are retained from their
 * first write, so a missing notification cannot silently release the cache block. Transactions that affect no DAO cache
 * need no retained record.
 * Only metadata is retained, not connections, entity managers, transaction statuses or exceptions.</p>
 *
 * <p><b>Avoiding confirmations.</b> {@code DataSourceTransactionManager} with its default settings reports every failed
 * commit as an unknown outcome (it wraps the {@code SQLException} in a {@code TransactionSystemException}), also a routine
 * one such as a deadlock or a serialization failure, which the database has rolled back: each then needs a confirmation.
 * {@code JdbcTransactionManager} translates a recognized commit failure into a {@code DataAccessException}, for which Spring
 * rolls the transaction back, and {@code setRollbackOnCommitFailure(true)} makes any transaction manager roll back after a
 * failed commit: a clean rollback report then releases the block without a confirmation.</p>
 *
 * <p><b>Scope of a block.</b> The blocks are kept per table name, ignoring case, not per data source or cache: an unresolved
 * transaction keeps the DAO caches of every data source in the process out of the tables it wrote, and out of the other
 * table names that share their hashed slot; a write through a DAO with an empty table name blocks every table.</p>
 *
 * <p>{@link #unresolvedTransactions()} and {@link #unresolvedTransactionCount()} expose failed completions and missing
 * reports detected after Spring marks the execution completed (or its status becomes unreachable). Detection of a missing
 * report can be delayed until the next cache read or monitoring call. Cross-thread detection of missing completion reports
 * is best-effort and is not guaranteed while the transaction status remains reachable (Spring keeps its completion in a
 * plain field). Until detection, unresolved snapshots may omit the transaction and confirmation may be rejected; caching
 * remains blocked. A warning identifies the transaction ID and tables.
 * Recovery is per transaction, never global: after verifying that that transaction's pending work can no longer commit,
 * confirm its ID. Closing an entity manager or returning from Spring's cleanup is not that verification. Without a clean
 * report or explicit confirmation, caching remains disabled indefinitely. The registry is process-local; restarting is not
 * a substitute for settling database work or invalidating any externally persisted cache.</p>
 *
 * <p><b>Spring Configuration Examples:</b></p>
 * <pre>{@code
 * // For every transaction manager of the application context (a static bean method: it is a BeanPostProcessor).
 * @Bean
 * public static SpringTransactionTracker.Registrar springTransactionTrackerRegistrar() {
 *     return new SpringTransactionTracker.Registrar();
 * }
 *
 * // Or for one transaction manager.
 * @Bean
 * public DataSourceTransactionManager transactionManager(DataSource dataSource) {
 *     DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
 *     SpringTransactionTracker.register(transactionManager);
 *     return transactionManager;
 * }
 * }</pre>
 *
 * @see SqlTransaction
 * @see com.landawn.abacus.jdbc.annotation.CacheResult
 */
public final class SpringTransactionTracker implements TransactionExecutionListener {

    /**
     * Creates a tracker. Every instance reports to the same records.
     */
    public SpringTransactionTracker() {
        // stateless
    }

    /**
     * A monitoring snapshot of one transaction that still blocks DAO caching without a confirmed outcome.
     *
     * @param id unique ID within this process, used by {@link #confirmSettled(long)}
     * @param transactionName Spring's transaction name, or an empty string when unnamed
     * @param tables immutable table-name list, ordered ignoring case; an empty name in the list means every table. The block
     *        covers these names for the DAO caches of every data source, and the other names sharing their hashed slot (see
     *        the scope of a block above)
     * @param beganAt when the transaction began
     * @param unresolvedSince when this library first saw its outcome unresolved: at the failure report, or, when no report
     *        came, at the first cache read or monitoring call after Spring ended the transaction's execution
     * @param failure a compact description of what left it unresolved: the class and message (truncated to 200 characters)
     *        of the failure the transaction manager reported, {@code "unknown outcome"} when Spring reported only an unknown
     *        status, or {@code "no completion report"} when no report came. The exception itself is not retained: it can
     *        retain application objects through its cause or fields
     */
    public record UnresolvedTransaction(long id, String transactionName, List<String> tables, Instant beganAt, Instant unresolvedSince, String failure) {
        /**
         * Creates an immutable snapshot. Constructing one does not register or resolve a transaction.
         *
         * @param id transaction ID
         * @param transactionName transaction name
         * @param tables affected table names
         * @param beganAt when the transaction began
         * @param unresolvedSince when its outcome was first seen unresolved
         * @param failure what left it unresolved
         */
        public UnresolvedTransaction {
            tables = List.copyOf(tables);
        }
    }

    /**
     * Returns an immutable snapshot of transactions with cache-affecting writes and failed or missing completion reports.
     * Active transactions are excluded. Concurrent completion or failure can change the registry after this snapshot.
     *
     * @return unresolved transactions, ordered by ID
     */
    public static List<UnresolvedTransaction> unresolvedTransactions() {
        return SpringTransactionOwners.unresolvedTransactions()
                .stream()
                .map(record -> new UnresolvedTransaction(record.id(), record.transactionName(), record.tables(), Instant.ofEpochMilli(record.beganAt()),
                        Instant.ofEpochMilli(record.unresolvedSince()), record.failure()))
                .toList();
    }

    /**
     * Returns the current monitoring count; concurrent completion or failure can change it immediately.
     *
     * @return number of unresolved transactions with cache-affecting writes
     */
    public static int unresolvedTransactionCount() {
        return SpringTransactionOwners.unresolvedTransactions().size();
    }

    /**
     * Acknowledges externally verified settlement of one unresolved transaction and allows its cache blocks to expire on
     * the next read. The caller must establish that all its pending work has committed, rolled back or otherwise cannot
     * commit later, including work left by failed cleanup. This method does not perform database recovery. An entity
     * manager's close, holder collection or Spring cleanup returning is insufficient evidence on its own.
     *
     * <p>Evidence of settlement is, for example, that the physical connection holding the work was closed or aborted (not
     * returned to a pool), that the database ended its session, or that the database shows no open transaction for it.
     * Seeing the expected rows in a query is not: pending work could still commit afterwards. Nothing releases a block
     * automatically, neither elapsed time nor garbage collection: nothing bounds when pending work may still commit. The
     * registry is process-local, but a restart doesn't settle the database work either.</p>
     *
     * <p>Only this ID is affected; other transactions, including failures arriving concurrently, remain blocked. Repeated
     * confirmation is harmless. A clean completion report also removes the record. Reads that began while the block was
     * present remain ineligible to cache their results.</p>
     *
     * @param transactionId ID from {@link #unresolvedTransactions()} or its warning log
     * @return {@code true} if this call resolved the record; {@code false} if it was already resolved or is unknown
     * @throws IllegalStateException if the ID belongs to a transaction still active with no failure report
     */
    public static boolean confirmSettled(final long transactionId) {
        return SpringTransactionOwners.confirmSettled(transactionId);
    }

    /**
     * Acknowledges the externally verified settlement of each of the given unresolved transactions, and of no other (see
     * {@link #confirmSettled(long)} for what the caller must establish first). Pass the IDs of a snapshot from
     * {@link #unresolvedTransactions()} whose transactions were verified: a transaction that becomes unresolved meanwhile is
     * not among them, and stays blocked. An ID unknown or resolved already is skipped. Nothing is confirmed if one of them
     * belongs to a transaction still active with no failure report.
     *
     * @param transactionIds the IDs, from {@link #unresolvedTransactions()} or the warning log
     * @return the IDs this call resolved, in the order given, without duplicates
     * @throws IllegalArgumentException if {@code transactionIds} or one of its elements is {@code null}
     * @throws IllegalStateException if one of the IDs belongs to a transaction still active with no failure report
     */
    public static List<Long> confirmSettled(final Collection<Long> transactionIds) throws IllegalArgumentException, IllegalStateException {
        if (transactionIds == null) {
            throw new IllegalArgumentException("'transactionIds' cannot be null");
        }

        for (final Long transactionId : transactionIds) {
            if (transactionId == null) {
                throw new IllegalArgumentException("An element of 'transactionIds' cannot be null");
            }
        }

        return SpringTransactionOwners.confirmSettled(transactionIds);
    }

    /**
     * Registers a tracker with each of {@code transactionManagers}, before their other execution listeners (see the
     * registration requirements above), replacing any tracker registered with them before.
     *
     * @param transactionManagers the transaction managers, such as {@code DataSourceTransactionManager},
     *        {@code JdbcTransactionManager} or {@code JpaTransactionManager}
     * @throws IllegalArgumentException if {@code transactionManagers} or one of them is {@code null}
     */
    public static void register(final ConfigurableTransactionManager... transactionManagers) throws IllegalArgumentException {
        if (transactionManagers == null) {
            throw new IllegalArgumentException("'transactionManagers' cannot be null");
        }

        for (final ConfigurableTransactionManager transactionManager : transactionManagers) {
            if (transactionManager == null) {
                throw new IllegalArgumentException("An element of 'transactionManagers' cannot be null");
            }
        }

        for (final ConfigurableTransactionManager transactionManager : transactionManagers) {
            final List<TransactionExecutionListener> listeners = new ArrayList<>();
            listeners.add(new SpringTransactionTracker());

            for (final TransactionExecutionListener listener : transactionManager.getTransactionExecutionListeners()) {
                if (!(listener instanceof SpringTransactionTracker)) {
                    listeners.add(listener);
                }
            }

            transactionManager.setTransactionExecutionListeners(listeners);
        }
    }

    /**
     * Registers a tracker, first, with every transaction manager of the Spring application context it is a bean of (see
     * {@link SpringTransactionTracker#register(ConfigurableTransactionManager...)}): every bean that is a
     * {@code ConfigurableTransactionManager}, once it is initialized. Declare it with a {@code static} bean method, as any
     * {@code BeanPostProcessor}, so that it is created before the transaction managers. Needs spring-beans.
     */
    public static final class Registrar implements BeanPostProcessor {
        /**
         * Creates a registrar.
         */
        public Registrar() {
            // stateless
        }

        @Override
        public Object postProcessAfterInitialization(final Object bean, final String beanName) {
            if (bean instanceof final ConfigurableTransactionManager transactionManager) {
                register(transactionManager);
            }

            return bean;
        }
    }

    @Override
    public void afterBegin(final TransactionExecution transaction, final Throwable beginFailure) {
        if (beginFailure == null) {
            final Object holder = connectionHolderOf(transaction);

            if (holder != null) {
                // A missing report may be detected from the status, but its completion/GC never proves resource settlement.
                // Keep only a weak status reference: pending cache records must not retain database resources.
                final WeakReference<TransactionExecution> execution = new WeakReference<>(transaction);
                final SpringTransactionOwners.Owner owner = SpringTransactionOwners.began(transaction, holder, () -> {
                    final TransactionExecution status = execution.get();
                    return status == null || status.isCompleted();
                }, transaction.getTransactionName());

                // WHY also a synchronization: an earlier execution listener that throws from afterCommit or afterRollback, or
                // any listener that throws from beforeRollback (or, unless rollbackOnCommitFailure is set, a
                // TransactionException other than an UnexpectedRollbackException from beforeCommit), keeps Spring from calling
                // this one's, which left the transaction pending for good; Spring calls every synchronization's afterCompletion,
                // whatever the others throw, once the outcome is decided: with STATUS_COMMITTED or STATUS_ROLLED_BACK before
                // the afterCommit or afterRollback listeners, and with STATUS_UNKNOWN in the beforeRollback and beforeCommit
                // cases named here.
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new CompletionSynchronization(owner));
                }
            }
        }
    }

    @Override
    public void beforeCommit(final TransactionExecution transaction) {
        deciding(transaction);
    }

    @Override
    public void afterCommit(final TransactionExecution transaction, final Throwable commitFailure) {
        completed(transaction, commitFailure);
    }

    @Override
    public void beforeRollback(final TransactionExecution transaction) {
        deciding(transaction);
    }

    @Override
    public void afterRollback(final TransactionExecution transaction, final Throwable rollbackFailure) {
        completed(transaction, rollbackFailure);
    }

    /**
     * Reports the completion of a tracked transaction from its {@code afterCompletion} callback, to the owner recorded when
     * the transaction began (not to the holder's owner at that time, which may be a later transaction's). An unknown outcome
     * ({@code STATUS_UNKNOWN}) comes without its exception: the listener report that follows, if it comes, adds the failure's
     * description.
     */
    private static final class CompletionSynchronization implements TransactionSynchronization {
        private final SpringTransactionOwners.Owner owner;

        CompletionSynchronization(final SpringTransactionOwners.Owner owner) {
            this.owner = owner;
        }

        @Override
        public void afterCompletion(final int status) {
            if (status == STATUS_UNKNOWN) {
                owner.uncertain();
            } else {
                owner.completed();
            }
        }
    }

    private static void deciding(final TransactionExecution transaction) {
        final SpringTransactionOwners.Owner owner = SpringTransactionOwners.ofTransaction(transaction);

        if (owner != null) {
            owner.deciding();
        }
    }

    /**
     * Reports the completion of {@code transaction}. WHY a failure apart: a failed commit or rollback proves nothing about the
     * work on the connection. Spring's cleanup may still commit it (restoring auto-commit), or leave it pending (on a
     * connection bound before the transaction with auto-commit already off, which the cleanup neither resets nor releases, or
     * on one an entity manager keeps after the transaction), so the outcome stays unresolved until a clean report or a
     * confirmation of its settlement; reported as completed, the caches stored rows that the pending work then changed.
     */
    private static void completed(final TransactionExecution transaction, final Throwable failure) {
        final SpringTransactionOwners.Owner owner = SpringTransactionOwners.ofTransaction(transaction);

        if (owner != null) {
            if (failure == null) {
                owner.completed();
            } else {
                owner.uncertain(failure);
            }
        }
    }

    /**
     * Returns the connection holder the transaction of {@code transaction} binds to its data source.
     *
     * @param transaction the execution reported
     * @return the holder, or {@code null} if {@code transaction} is not a new transaction (a scope participating in one, or
     *         a savepoint of one, which share its transaction object: they complete with it), or its transaction object
     *         exposes no JDBC connection holder
     */
    private static Object connectionHolderOf(final TransactionExecution transaction) {
        if (!transaction.isNewTransaction()) {
            return null;
        }

        return transaction instanceof final DefaultTransactionStatus status && status.getTransaction() instanceof final JdbcTransactionObjectSupport tx
                && tx.hasConnectionHolder() ? tx.getConnectionHolder() : null;
    }
}
