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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.JdbcTransactionObjectSupport;
import org.springframework.jdbc.datasource.SimpleConnectionHandle;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.landawn.abacus.util.ClassUtil;

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
 * manager: Spring calls them in order, and one that throws keeps Spring from calling those after it, so a tracker after it
 * does not hear of the completion from its own callbacks (see below for what tells it then). {@link #register(ConfigurableTransactionManager...)} does that, and a
 * {@link Registrar} bean does it for every transaction manager of a Spring application context. Register it with
 * <i>every</i> transaction manager on a data source: registered with some only, it is unsupported (the transactions of a
 * transaction manager without it get the behavior described above; the record a tracked transaction left on a connection
 * holder bound to the data source before it, which a later transaction reuses, is dropped once that transaction is active
 * with synchronization, while one with synchronization disabled can't be told from the tracked transaction's cleanup, and
 * is taken for completed). A transaction begun before the tracker is registered is not tracked; nor is a participating
 * scope or a savepoint of a tracked transaction, which completes with it.</p>
 *
 * <p>The completion is also reported by a transaction synchronization the tracker registers when a transaction begins, as
 * Spring runs every synchronization's {@code afterCompletion} callback whatever the others throw. Without synchronization,
 * a failing listener before the tracker leaves the completion to Spring's cleanup after it, as described next: register
 * the tracker first. A commit or rollback that fails leaves the outcome uncertain until Spring's cleanup has finished, which
 * may still commit the work the failure left pending on the connection, so the caches stay out of its tables until then:
 * for a {@code DataSourceTransactionManager} or {@code JdbcTransactionManager} transaction, whose cleanup restores
 * auto-commit, until its connection holder tells the end; for a JPA transaction, whose dialect releases the connection in
 * the cleanup, until its entity manager is closed (the last step of the cleanup; an entity manager bound before the
 * transaction, as for open-in-view, until its owner closes it). Where neither tells it (another kind of transaction
 * manager whose holder keeps a handle of its own), until the holder has been garbage collected.</p>
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
                SpringTransactionOwners.began(holder, cleanupOf(transaction, holder));

                // WHY also a synchronization: an earlier execution listener that throws from afterCommit or afterRollback keeps
                // Spring from calling this one's, which left the transaction pending for good; Spring calls every
                // synchronization's afterCompletion, whatever the others throw, after the database commit or rollback.
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new CompletionSynchronization(holder));
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
     * Reports the completion of a tracked transaction from its {@code afterCompletion} callback.
     */
    private static final class CompletionSynchronization implements TransactionSynchronization {
        private final Object holder;

        CompletionSynchronization(final Object holder) {
            this.holder = holder;
        }

        @Override
        public void afterCompletion(final int status) {
            if (status == STATUS_UNKNOWN) {
                SpringTransactionOwners.uncertain(holder);
            } else {
                SpringTransactionOwners.completed(holder);
            }
        }
    }

    /**
     * Returns what tells how far Spring's cleanup after {@code transaction} has got (see
     * {@link SpringTransactionOwners.Cleanup}), by the kind of transaction: one whose holder has Spring's own connection
     * handle ({@code DataSourceTransactionManager}, {@code JdbcTransactionManager}), a JPA one whose entity manager is found,
     * or else one whose cleanup's end can't be told.
     *
     * @param transaction the execution reported
     * @param holder the connection holder its transaction has bound
     * @return what tells it
     */
    static SpringTransactionOwners.Cleanup cleanupOf(final TransactionExecution transaction, final Object holder) {
        if (holder instanceof final ConnectionHolder connectionHolder && connectionHolder.getConnectionHandle() instanceof SimpleConnectionHandle) {
            return new HolderCleanup(connectionHolder, transaction);
        }

        final Object entityManager = entityManagerOf(transaction);

        return entityManager != null ? new EntityManagerCleanup(entityManager) : UNTOLD_CLEANUP;
    }

    /**
     * The cleanup after a {@code DataSourceTransactionManager} or {@code JdbcTransactionManager} transaction: its connection
     * holder tells the end, as the cleanup clears the holder's transaction-active flag as its last step, after restoring
     * auto-commit (which commits what a failed commit or rollback left pending), resetting and releasing the connection.
     */
    static final class HolderCleanup implements SpringTransactionOwners.Cleanup {
        private final WeakReference<ConnectionHolder> holder;
        private final WeakReference<TransactionExecution> status;

        HolderCleanup(final ConnectionHolder holder, final TransactionExecution status) {
            this.holder = new WeakReference<>(holder);
            this.status = new WeakReference<>(status);
        }

        @Override
        public boolean isFinished() {
            final ConnectionHolder bound = holder.get();

            return bound == null || JdbcUtil.hasSpringTransactionCompleted(bound);
        }

        /**
         * {@inheritDoc}
         *
         * <p>Reused once Spring's cleanup after this transaction has begun (its status is completed, Spring's first step of the
         * cleanup, or has been garbage collected) while the holder is transaction-active again and a Spring transaction is
         * active on the thread. WHY the thread: until its last step clears the holder, the cleanup leaves it
         * transaction-active, and runs application code meanwhile (the hooks of a connection wrapper or a pool on restoring
         * auto-commit, resetting or releasing the connection); taken for a newer transaction there, the owner was forgotten,
         * and a scope begun from such a hook joined the connection the cleanup then committed, its rollback notwithstanding.
         * Spring clears the thread's transaction state as the first step of the cleanup, and a newer transaction with
         * synchronization sets it again. One without synchronization sets nothing: such a transaction of a transaction manager
         * without a tracker is not told apart, and the owner is kept.</p>
         */
        @Override
        public boolean isHolderReused() {
            final TransactionExecution execution = status.get();
            final ConnectionHolder bound = holder.get();

            return (execution == null || execution.isCompleted()) && bound != null && !JdbcUtil.hasSpringTransactionCompleted(bound)
                    && TransactionSynchronizationManager.isActualTransactionActive();
        }
    }

    /**
     * The cleanup after a JPA transaction: {@code JpaTransactionManager} closes the transaction's entity manager as the last
     * step of its cleanup, after releasing the JDBC connection through its JPA dialect (and Hibernate marks a session closed
     * only once it has closed its JDBC coordinator). An entity manager bound before the transaction (open-in-view) is not
     * closed by the cleanup: the end is then told once its owner closes it. WHY not the start of the cleanup (the
     * transaction status's completion, its first step), as it was: a dialect whose connection release committed the work a
     * failed commit left pending made a read in between cache the rows as they were before it. Asked on any thread: for
     * Hibernate's resource-local transactions, {@code isOpen()} reads the session's state only.
     */
    static final class EntityManagerCleanup implements SpringTransactionOwners.Cleanup {
        private final WeakReference<Object> entityManager;

        EntityManagerCleanup(final Object entityManager) {
            this.entityManager = new WeakReference<>(entityManager);
        }

        @Override
        public boolean isFinished() {
            final Object em = entityManager.get();

            if (em == null) {
                return true; // garbage collected: no transaction can use it any more
            }

            try {
                return !Boolean.TRUE.equals(IS_OPEN_BY_ENTITY_MANAGER_CLASS.methodOf(em.getClass()).invoke(em));
            } catch (final ReflectiveOperationException | RuntimeException e) {
                return false; // can't tell: the holder's garbage collection tells it
            }
        }

        @Override
        public boolean isHolderReused() {
            return false; // JpaTransactionManager binds a new holder for each transaction
        }
    }

    /**
     * The cleanup after a transaction whose holder keeps a handle of its own and whose entity manager isn't found (another
     * kind of transaction manager, or a JPA one whose transaction object can't be read): its end can't be told, so the
     * holder's garbage collection tells it (see {@link SpringTransactionOwners.Owner#isCompleted()}).
     */
    private static final SpringTransactionOwners.Cleanup UNTOLD_CLEANUP = new SpringTransactionOwners.Cleanup() {
        @Override
        public boolean isFinished() {
            return false;
        }

        @Override
        public boolean isHolderReused() {
            return false;
        }
    };

    // spring-orm and the JPA API are optional, so their accessors are resolved reflectively, once per class (see
    // JdbcUtil.MethodValue).
    private static final JdbcUtil.MethodValue GET_ENTITY_MANAGER_HOLDER_BY_TRANSACTION_CLASS = new JdbcUtil.MethodValue("getEntityManagerHolder",
            transactionClass -> {
                final Method method = transactionClass.getMethod("getEntityManagerHolder");

                // The transaction object's class (JpaTransactionManager.JpaTransactionObject) is private.
                if (!method.trySetAccessible()) {
                    throw new NoSuchMethodException("Cannot access " + transactionClass.getName() + ".getEntityManagerHolder()");
                }

                return method;
            });
    private static final JdbcUtil.MethodValue GET_ENTITY_MANAGER_BY_HOLDER_CLASS = new JdbcUtil.MethodValue("getEntityManager");
    // Resolved through the JPA interface: the provider's implementation class need not be accessible.
    private static final JdbcUtil.MethodValue IS_OPEN_BY_ENTITY_MANAGER_CLASS = new JdbcUtil.MethodValue("isOpen",
            entityManagerClass -> entityManagerType(entityManagerClass).getMethod("isOpen"));

    /**
     * Returns the entity manager of a {@code JpaTransactionManager} transaction: the one the {@code EntityManagerHolder} of
     * its transaction object keeps.
     *
     * @param transaction the execution reported
     * @return the entity manager, or {@code null} if {@code transaction} is not such a transaction, or its transaction object
     *         can't be read
     */
    private static Object entityManagerOf(final TransactionExecution transaction) {
        if (!(transaction instanceof final DefaultTransactionStatus status) || status.getTransaction() == null) {
            return null;
        }

        try {
            final Object transactionObject = status.getTransaction();
            final Object entityManagerHolder = GET_ENTITY_MANAGER_HOLDER_BY_TRANSACTION_CLASS.methodOf(transactionObject.getClass()).invoke(transactionObject);

            return entityManagerHolder == null ? null : GET_ENTITY_MANAGER_BY_HOLDER_CLASS.methodOf(entityManagerHolder.getClass()).invoke(entityManagerHolder);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Returns the JPA {@code EntityManager} interface (jakarta or javax) that {@code entityManagerClass} implements.
     *
     * @param entityManagerClass the class of an entity manager
     * @return the interface
     * @throws NoSuchMethodException if it implements none
     */
    private static Class<?> entityManagerType(final Class<?> entityManagerClass) throws NoSuchMethodException {
        for (Class<?> c = entityManagerClass; c != null; c = c.getSuperclass()) {
            for (final Class<?> type : ClassUtil.getAllInterfaces(c)) {
                if (type.getName().endsWith(".persistence.EntityManager")) {
                    return type;
                }
            }
        }

        throw new NoSuchMethodException("No JPA EntityManager interface on " + entityManagerClass.getName());
    }

    private static void deciding(final TransactionExecution transaction) {
        final Object holder = connectionHolderOf(transaction);

        if (holder != null) {
            SpringTransactionOwners.deciding(holder);
        }
    }

    /**
     * Reports the completion of {@code transaction}. WHY a failure apart: after a failed commit or rollback, the work is still
     * pending on the connection until Spring's cleanup, which restores auto-commit, commits it; reported as completed, the
     * caches served and stored rows of its tables again before that, which the cleanup then made stale for good.
     */
    private static void completed(final TransactionExecution transaction, final Throwable failure) {
        final Object holder = connectionHolderOf(transaction);

        if (holder != null) {
            if (failure == null) {
                SpringTransactionOwners.completed(holder);
            } else {
                SpringTransactionOwners.uncertain(holder);
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
