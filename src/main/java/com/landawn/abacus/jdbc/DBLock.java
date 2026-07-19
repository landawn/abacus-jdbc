/*
 * Copyright (C) 2015 HaiYang Li
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.landawn.abacus.jdbc;

import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.landawn.abacus.exception.UncheckedInterruptedException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.util.Dates;
import com.landawn.abacus.util.IOUtil;
import com.landawn.abacus.util.MoreExecutors;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Strings;

/**
 * Provides a robust distributed locking mechanism leveraging a dedicated database table.
 * This class facilitates coordination among multiple application instances or processes
 * to ensure exclusive access to shared resources, thereby preventing race conditions
 * and data corruption in distributed environments.
 *
 * <p>The locking mechanism relies on a database table with the following essential columns:</p>
 * <ul>
 *   <li>{@code host_name}: Identifies the host that currently holds the lock.</li>
 *   <li>{@code target}: The unique identifier of the resource being locked (enforced by a unique constraint).</li>
 *   <li>{@code code}: A unique, randomly generated code for the specific lock acquisition instance.</li>
 *   <li>{@code status}: The current state of the lock (e.g., 'locked', 'unlocked').</li>
 *   <li>{@code expiry_time}: The timestamp when the lock is scheduled to automatically expire.</li>
 *   <li>{@code update_time}: The last time the lock was refreshed or updated.</li>
 *   <li>{@code create_time}: The timestamp when the lock was initially acquired.</li>
 * </ul>
 *
 * <p>Key features and benefits:</p>
 * <ul>
 *   <li><b>Deadlock Prevention:</b> Locks automatically expire after a configurable {@code liveTime},
 *       mitigating permanent deadlocks if an application instance crashes.</li>
 *   <li><b>Liveness Assurance:</b> Active locks are periodically refreshed by a background scheduler
 *       to extend their {@code expiry_time} for long-running critical sections.</li>
 *   <li><b>Automatic Cleanup:</b> Expired and "dead" locks (e.g., from crashed hosts) are automatically
 *       removed to maintain a clean and efficient lock table.</li>
 *   <li><b>Configurable Behavior:</b> Offers flexible control over lock {@code liveTime}, acquisition
 *       {@code timeout}, and {@code retryInterval} for diverse use cases.</li>
 *   <li><b>Optimistic Locking:</b> Utilizes optimistic locking principles to handle concurrent lock
 *       acquisition attempts efficiently.</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> Instances are thread-safe. Multiple threads may concurrently invoke
 * {@link #lock(String, long, long, long)} and {@link #unlock(String, String)} on the same
 * {@code DBLock} instance. Each acquisition is keyed by its {@code target} and protected by a
 * unique per-acquisition {@code code}, so only the holder that supplies the matching {@code code}
 * can release a given lock.</p>
 *
 * <p>Instances are normally obtained through {@link JdbcUtil#createDBLock(DataSource, String)} rather
 * than constructed directly. The {@link #close()} method should be called when the lock is no
 * longer needed to stop the background refresh task and release any locks still held.</p>
 *
 * <p><b>&#9888; Warning:</b> Lease timestamps are computed from each participating JVM's wall clock.
 * Hosts using the same lock table must keep their clocks synchronized; significant skew can make
 * one host consider another host's actively refreshed lease expired.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Initialize DBLock with a DataSource and a table name
 * DBLock dbLock = JdbcUtil.createDBLock(dataSource, "app_distributed_locks");
 *
 * String resourceId = "inventory_item_123";
 * long lockLiveTimeMillis = 60 * 1000;       // Lock for 1 minute
 * long acquisitionTimeoutMillis = 5 * 1000;  // Try to acquire for up to 5 seconds
 *
 * String lockCode = dbLock.lock(resourceId, lockLiveTimeMillis, acquisitionTimeoutMillis);
 *
 * if (lockCode != null) {
 *     try {
 *         // Successfully acquired the lock. Perform the critical section operations.
 *         System.out.println("Lock acquired for resource: " + resourceId + " with code: " + lockCode);
 *         // simulate work
 *         Thread.sleep(20000);
 *     } catch (InterruptedException e) {
 *         Thread.currentThread().interrupt();
 *         System.err.println("Operation interrupted: " + e.getMessage());
 *     } finally {
 *         // Always release the lock in a finally block
 *         boolean released = dbLock.unlock(resourceId, lockCode);
 *         System.out.println("Lock released: " + released);
 *     }
 * } else {
 *     System.out.println("Failed to acquire lock for resource: " + resourceId + " within the timeout.");
 * }
 *
 * // Close the DBLock instance when the application shuts down
 * dbLock.close();
 * }</pre>
 *
 * @see DataSource
 * @see JdbcUtil
 * @see ScheduledExecutorService
 */
public final class DBLock {

    private static final Logger logger = LoggerFactory.getLogger(DBLock.class);

    /**
     * Value written to the {@code status} column of the lock table to indicate that a target is currently locked.
     */
    public static final String LOCKED = "locked";

    /**
     * Value representing an unlocked target in the {@code status} column of the lock table.
     * This is a reserved/legacy constant that is not currently written by the implementation:
     * a lock is released by deleting its row, so no row ever carries this value.
     */
    public static final String UNLOCKED = "unlocked";

    /**
     * Default lock live time in milliseconds (3 minutes).
     */
    public static final int DEFAULT_LOCK_LIVE_TIME = 3 * 60 * 1000;

    /**
     * Default timeout for lock acquisition in milliseconds (3 seconds).
     */
    public static final int DEFAULT_TIMEOUT = 3 * 1000;

    private static final int MAX_IDLE_TIME = 60 * 1000;

    static final ScheduledExecutorService scheduledExecutor;
    static {
        final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(8);
        executor.setKeepAliveTime(180, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        executor.setRemoveOnCancelPolicy(true);
        scheduledExecutor = MoreExecutors.getExitingScheduledExecutorService(executor);
    }

    private final DataSource ds;

    private final ScheduledFuture<?> scheduledFuture;

    private final Map<String, LockInfo> targetCodePool = new ConcurrentHashMap<>();

    private final String removeExpiredLockSQL;

    private final String lockSQL;

    private final String unlockSQL;

    private final String refreshSQL;

    private volatile boolean isClosed = false;

    /**
     * Constructs a new {@code DBLock} instance, initializing the distributed lock mechanism.
     * This involves setting up the necessary database table and starting a background task
     * for maintaining lock liveness and cleaning up stale locks.
     *
     * <p>During initialization, the constructor performs the following key steps:</p>
     * <ol>
     *   <li><b>Table Creation:</b> Ensures the lock table exists in the database. If not, it attempts
     *       to create it with a predefined schema including columns for host name, target resource,
     *       lock code, status, expiry time, update time, and creation time.</li>
     *   <li><b>Dead Lock Cleanup:</b> Removes any locks that were held by previous instances of the
     *       application running on the same host and that were not properly released (e.g., due to crashes).</li>
     *   <li><b>Background Refresh Task:</b> Initiates a scheduled task that periodically refreshes
     *       the {@code update_time} and {@code expiry_time} of all currently held locks. This prevents
     *       long-running operations from losing their locks prematurely.</li>
     * </ol>
     *
     * <p>The lock table schema is designed to support optimistic locking and automatic expiration.
     * It includes a unique constraint on the {@code target} column to ensure only one active lock
     * per resource at any given time.</p>
     *
     * <p>This constructor is package-private; instances are normally obtained via
     * {@code JdbcUtil.createDBLock(DataSource, String)}.</p>
     *
     * @param ds the {@link DataSource} to use for database connections. Must not be {@code null}.
     * @param tableName the name of the database table to use for storing lock information.
     *        This table will be created if it does not exist. Must not be {@code null} or empty.
     * @throws UncheckedSQLException if any database operation fails during initialization (e.g., table creation).
     * @throws IllegalStateException if the lock table cannot be verified after the creation attempt.
     */
    DBLock(final DataSource ds, final String tableName) {
        this.ds = ds;
        final Connection conn = JdbcUtil.getConnection(ds);

        try {
            final String sqlTableName = JdbcUtil.toQualifiedSqlIdentifier(conn, tableName, "tableName");

            removeExpiredLockSQL = "DELETE FROM " + sqlTableName + " WHERE target = ? AND (expiry_time < ? OR update_time < ?)"; //NOSONAR
            lockSQL = "INSERT INTO " + sqlTableName + " (host_name, target, code, status, expiry_time, update_time, create_time) VALUES (?, ?, ?, ?, ?, ?, ?)";
            unlockSQL = "DELETE FROM " + sqlTableName + " WHERE target = ? AND code = ?"; //NOSONAR
            refreshSQL = "UPDATE " + sqlTableName + " SET update_time = ?, expiry_time = ? WHERE target = ? AND code = ?";

            final String schema = "CREATE TABLE " + sqlTableName
                    + "(host_name VARCHAR(64), target VARCHAR(255) NOT NULL, code VARCHAR(64), status VARCHAR(16) NOT NULL, "
                    + "expiry_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL, create_time TIMESTAMP NOT NULL, UNIQUE (target))";

            JdbcUtil.createTableIfNotExists(conn, tableName, schema);

            if (!JdbcUtil.tableExists(conn, tableName)) {
                throw new IllegalStateException("Lock table does not exist after creation attempt: " + tableName);
            }

            // Only reap rows that are BOTH older than this JVM's start AND no longer refreshed (stale
            // update_time): another JVM on the same host may be actively holding locks under the same
            // host_name, and its live (refreshed) rows must not be deleted.
            final String removeDeadLockSQL = "DELETE FROM " + sqlTableName + " WHERE host_name = ? AND create_time < ? AND update_time < ?";

            final int removedDeadLocks = JdbcUtil.executeUpdate(conn, removeDeadLockSQL, IOUtil.getHostName(),
                    Dates.createTimestamp(ManagementFactory.getRuntimeMXBean().getStartTime()),
                    Dates.createTimestamp(System.currentTimeMillis() - MAX_IDLE_TIME));

            logger.info("Initialized DBLock(tableName={}, removedDeadLocks={})", tableName, removedDeadLocks);
        } catch (final SQLException e) {
            logger.warn(e, "Failed to initialize DBLock(tableName={})", tableName);
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.releaseConnection(conn, ds);
        }

        final Runnable refreshTask = () -> {
            if (!targetCodePool.isEmpty()) {
                try {
                    final Connection refreshConn = JdbcUtil.getConnection(ds);
                    try {
                        int refreshed = 0;
                        int staleRemoved = 0;
                        int failed = 0;

                        // Iterate the ConcurrentHashMap directly: its iteration is weakly consistent and the
                        // stale-entry removal below is the atomic remove(key, value), so no point-in-time
                        // snapshot is needed.
                        for (final Map.Entry<String, LockInfo> entry : targetCodePool.entrySet()) {
                            final LockInfo info = entry.getValue();

                            // Guard each entry individually: one failing refresh (e.g. a transient DB error
                            // for a single target) must not abort the entire batch and leave the remaining
                            // locks un-refreshed.
                            try {
                                final Timestamp now = Dates.currentTimestamp();
                                final Timestamp expiry = expiryTimestamp(now, info.liveTime());

                                final int updated = JdbcUtil.executeUpdate(refreshConn, refreshSQL, now, expiry, entry.getKey(), info.code());

                                if (updated == 0) {
                                    // Remove from pool only if the cached lock instance is still present
                                    if (targetCodePool.remove(entry.getKey(), info)) {
                                        staleRemoved++;

                                        if (logger.isWarnEnabled()) {
                                            logger.warn("Removed stale lock from pool(target={})", entry.getKey());
                                        }
                                    }
                                } else {
                                    refreshed++;
                                }
                            } catch (final SQLException e) {
                                failed++;

                                if (logger.isWarnEnabled()) {
                                    logger.warn(e, "Failed to refresh DB lock(target={})", entry.getKey());
                                }
                            }
                        }

                        if (logger.isDebugEnabled() && (refreshed > 0 || staleRemoved > 0 || failed > 0)) {
                            logger.debug("Refreshed DB locks(refreshed={}, staleRemoved={}, failed={}, active={})", refreshed, staleRemoved, failed,
                                    targetCodePool.size());
                        }
                    } finally {
                        // releaseConnection belongs in finally so a per-entry failure (or any other exception
                        // escaping the loop) still releases the refresh connection.
                        JdbcUtil.releaseConnection(refreshConn, ds);
                    }
                } catch (final Exception e) {
                    if (logger.isWarnEnabled()) {
                        logger.warn(e, "Error occurred in DB lock refresh task(active={})", targetCodePool.size());
                    }
                }
            }
        };

        scheduledFuture = scheduledExecutor.scheduleWithFixedDelay(refreshTask, 1000L, 1000L, TimeUnit.MILLISECONDS);
    }

    /**
     * Attempts to acquire a distributed lock on the specified target resource using default settings.
     * This method uses {@link #DEFAULT_LOCK_LIVE_TIME} (3 minutes) for the lock's validity duration
     * and {@link #DEFAULT_TIMEOUT} (3 seconds) as the maximum time to wait for the lock.
     *
     * <p>If the lock is successfully acquired, a unique lock code is returned, which must be used
     * to release the lock later. If the lock cannot be acquired within the timeout, {@code null} is returned.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBLock dbLock = JdbcUtil.createDBLock(dataSource, "my_locks_table");
     * String resourceIdentifier = "report_generation_task";
     *
     * String lockCode = dbLock.lock(resourceIdentifier);
     *
     * if (lockCode != null) {
     *     try {
     *         System.out.println("Lock acquired for: " + resourceIdentifier);
     *         // Perform the critical operation that requires exclusive access
     *         // ...
     *     } finally {
     *         // Ensure the lock is released, even if an error occurs
     *         dbLock.unlock(resourceIdentifier, lockCode);
     *         System.out.println("Lock released for: " + resourceIdentifier);
     *     }
     * } else {
     *     System.out.println("Failed to acquire lock for: " + resourceIdentifier + " within default timeout.");
     * }
     * }</pre>
     *
     * @param target the unique identifier of the resource to lock. Must not be {@code null} or empty.
     * @return a unique {@code String} code representing the acquired lock, or {@code null} if the lock
     *         could not be acquired within the default timeout, or if the calling thread was interrupted
     *         while waiting (in which case the thread's interrupt status is preserved).
     * @throws IllegalStateException if this {@code DBLock} instance has been closed.
     * @throws IllegalArgumentException if {@code target} is {@code null} or empty.
     * @see #lock(String, long, long)
     * @see #DEFAULT_LOCK_LIVE_TIME
     * @see #DEFAULT_TIMEOUT
     */
    public String lock(final String target) {
        return lock(target, DEFAULT_LOCK_LIVE_TIME, DEFAULT_TIMEOUT);
    }

    /**
     * Attempts to acquire a distributed lock on the specified target resource with a custom timeout.
     * This method uses {@link #DEFAULT_LOCK_LIVE_TIME} (3 minutes) for the lock's validity duration.
     *
     * <p>The method will try to acquire the lock for up to {@code timeout} milliseconds.
     * If successful, a unique lock code is returned; otherwise, {@code null} is returned.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBLock dbLock = JdbcUtil.createDBLock(dataSource, "my_locks_table");
     * String resourceIdentifier = "data_export_job";
     * long customTimeout = 15 * 1000;  // Wait up to 15 seconds
     *
     * String lockCode = dbLock.lock(resourceIdentifier, customTimeout);
     *
     * if (lockCode != null) {
     *     try {
     *         System.out.println("Lock acquired for: " + resourceIdentifier);
     *         // Execute the data export logic
     *         // ...
     *     } finally {
     *         dbLock.unlock(resourceIdentifier, lockCode);
     *         System.out.println("Lock released for: " + resourceIdentifier);
     *     }
     * } else {
     *     System.out.println("Failed to acquire lock for: " + resourceIdentifier + " within " + customTimeout + "ms.");
     * }
     * }</pre>
     *
     * @param target the unique identifier of the resource to lock. Must not be {@code null} or empty.
     * @param timeout the maximum time in milliseconds to wait for the lock. Must be non-negative.
     * @return a unique {@code String} code representing the acquired lock, or {@code null} if the lock
     *         could not be acquired within the specified timeout, or if the calling thread was interrupted
     *         while waiting (in which case the thread's interrupt status is preserved).
     * @throws IllegalStateException if this {@code DBLock} instance has been closed.
     * @throws IllegalArgumentException if {@code target} is {@code null} or empty, or {@code timeout} is negative.
     * @see #lock(String, long, long)
     * @see #DEFAULT_LOCK_LIVE_TIME
     */
    public String lock(final String target, final long timeout) {
        return lock(target, DEFAULT_LOCK_LIVE_TIME, timeout);
    }

    /**
     * Attempts to acquire a distributed lock on the specified target resource with custom
     * lock duration (live time) and acquisition timeout.
     *
     * <p>The acquired lock will automatically expire after {@code liveTime} milliseconds if not
     * refreshed. A background task automatically refreshes active locks to prevent premature
     * expiration during long-running operations. The method will wait for up to {@code timeout}
     * milliseconds to acquire the lock.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBLock dbLock = JdbcUtil.createDBLock(dataSource, "my_locks_table");
     * String resourceIdentifier = "batch_processing_queue";
     * long lockDuration = 10 * 60 * 1000;  // Lock for 10 minutes
     * long waitTimeout = 30 * 1000;        // Wait up to 30 seconds to acquire
     *
     * String lockCode = dbLock.lock(resourceIdentifier, lockDuration, waitTimeout);
     *
     * if (lockCode != null) {
     *     try {
     *         System.out.println("Lock acquired for: " + resourceIdentifier);
     *         // Execute the batch processing logic
     *         // ...
     *     } finally {
     *         dbLock.unlock(resourceIdentifier, lockCode);
     *         System.out.println("Lock released for: " + resourceIdentifier);
     *     }
     * } else {
     *     System.out.println("Failed to acquire lock for: " + resourceIdentifier + " within " + waitTimeout + "ms.");
     * }
     * }</pre>
     *
     * @param target the unique identifier of the resource to lock. Must not be {@code null} or empty.
     * @param liveTime the lease-expiry window in milliseconds; the background refresh task extends
     *        this window while the lock remains held. Must be positive.
     * @param timeout the maximum time in milliseconds to wait for the lock. Must be non-negative.
     * @return a unique {@code String} code representing the acquired lock, or {@code null} if the lock
     *         could not be acquired within the specified timeout, or if the calling thread was interrupted
     *         while waiting (in which case the thread's interrupt status is preserved).
     * @throws IllegalStateException if this {@code DBLock} instance has been closed.
     * @throws IllegalArgumentException if {@code target} is {@code null} or empty,
     *         {@code liveTime} is not positive, or {@code timeout} is negative.
     * @see #lock(String, long, long, long)
     */
    public String lock(final String target, final long liveTime, final long timeout) {
        return lock(target, liveTime, timeout, 0);
    }

    /**
     * Attempts to acquire a distributed lock on the specified target resource with full control
     * over lock duration, acquisition timeout, and retry behavior.
     *
     * <p>This is the most flexible {@code lock} method. It tries to acquire the lock by inserting
     * a new record into the lock table. If the initial attempt fails (meaning another process
     * holds the lock), it will repeatedly retry after {@code retryInterval} milliseconds until
     * the total {@code timeout} is reached. Before each acquisition attempt, it removes any expired
     * locks for the target to reduce stale lock contention.</p>
     *
     * <p>A transient failure of an individual acquisition attempt (for example, a unique-constraint
     * violation surfaced as a {@link SQLException} because another holder currently owns the lock)
     * is caught and recorded but does not abort the loop; the method simply waits and retries. If
     * the calling thread is interrupted while sleeping between attempts, the loop stops immediately,
     * the thread's interrupt status is restored, and {@code null} is returned. When the timeout
     * elapses without success, {@code null} is returned and the last failure (if any) is logged.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBLock dbLock = JdbcUtil.createDBLock(dataSource, "my_locks_table");
     * String resourceIdentifier = "inventory_update_process";
     * long lockDuration = 5 * 60 * 1000;    // Lock for 5 minutes
     * long acquisitionTimeout = 10 * 1000;  // Wait up to 10 seconds
     * long retryInterval = 500;             // Retry every 500 milliseconds
     *
     * String lockCode = dbLock.lock(resourceIdentifier, lockDuration, acquisitionTimeout, retryInterval);
     *
     * if (lockCode != null) {
     *     try {
     *         System.out.println("Lock acquired for: " + resourceIdentifier);
     *         // Perform the inventory update
     *         // ...
     *     } finally {
     *         dbLock.unlock(resourceIdentifier, lockCode);
     *         System.out.println("Lock released for: " + resourceIdentifier);
     *     }
     * } else {
     *     System.out.println("Failed to acquire lock for: " + resourceIdentifier + " after " + acquisitionTimeout + "ms.");
     * }
     * }</pre>
     *
     * @param target the unique identifier of the resource to lock. Must not be {@code null} or empty.
     * @param liveTime the lease-expiry window in milliseconds; the background refresh task extends
     *        this window while the lock remains held. Must be positive.
     * @param timeout the maximum time in milliseconds to wait for the lock. Must be non-negative.
     * @param retryInterval the time in milliseconds to wait between retry attempts. A value of 0 means
     *        an internal minimum (1 ms) delay is used to avoid a tight spin loop. Must be non-negative.
     * @return a unique {@code String} code representing the acquired lock, or {@code null} if the lock
     *         could not be acquired within the specified timeout, or if the calling thread was interrupted
     *         while waiting (in which case the thread's interrupt status is preserved).
     * @throws IllegalStateException if this {@code DBLock} instance has been closed.
     * @throws IllegalArgumentException if {@code target} is {@code null} or empty,
     *         {@code liveTime} is not positive, or {@code timeout} or {@code retryInterval} is negative.
     */
    public String lock(final String target, final long liveTime, final long timeout, final long retryInterval) throws IllegalStateException {
        assertNotClosed();
        N.checkArgNotEmpty(target, "target");
        N.checkArgPositive(liveTime, "liveTime");
        N.checkArgNotNegative(timeout, "timeout");
        N.checkArgNotNegative(retryInterval, "retryInterval");

        final String code = Strings.uuid();

        Timestamp now = Dates.currentTimestamp();
        final long nowTime = now.getTime();
        final long endTime = (timeout > Long.MAX_VALUE - nowTime) ? Long.MAX_VALUE : nowTime + timeout;
        int attempts = 0;
        final long maxAttemptsLong = timeout / Math.max(retryInterval, 1);
        final long maxAttemptsWithBuffer = maxAttemptsLong >= (Integer.MAX_VALUE - 1000L) ? Integer.MAX_VALUE : maxAttemptsLong + 1000L; // Safeguard against infinite loop
        final int maxAttempts = (int) maxAttemptsWithBuffer;
        Exception lastException = null;

        // Host name is process-constant; resolve it once instead of on every retry attempt
        // (IOUtil.getHostName() can perform an InetAddress/DNS lookup).
        final String hostName = IOUtil.getHostName();

        logger.debug("Trying to acquire DB lock(target={}, liveTime={}, timeout={}, retryInterval={})", target, liveTime, timeout, retryInterval);

        do {
            removeExpiredLock(target);
            now = Dates.currentTimestamp();

            try {
                if (JdbcUtil.executeUpdate(ds, lockSQL, hostName, target, code, LOCKED, expiryTimestamp(now, liveTime), now, now) > 0) {
                    // Linearize successful acquisition with close(), which synchronizes on the same
                    // monitor. A plain post-insert volatile check still allowed close() to run after
                    // that check but before this method returned, so lock() could report success for
                    // a row close() had already deleted.
                    synchronized (this) {
                        if (isClosed) {
                            try {
                                JdbcUtil.executeUpdate(ds, unlockSQL, target, code);
                            } catch (final Exception cleanupFailure) {
                                logger.warn(cleanupFailure, "Failed to remove DB lock acquired concurrently with close(target={})", target);
                            }

                            throw new IllegalStateException("This DBLock has been closed");
                        }

                        targetCodePool.put(target, new LockInfo(code, liveTime));

                        logger.info("Acquired DB lock(target={}, liveTime={}, attempts={})", target, liveTime, attempts + 1);

                        return code;
                    }
                }
            } catch (final IllegalStateException e) {
                // The closed-instance check above must not be swallowed by the retry loop.
                throw e;
            } catch (final Exception e) {
                if (logger.isDebugEnabled()) {
                    logger.debug(e, "Failed to acquire DB lock(target={}, attempt={})", target, attempts + 1);
                }
                lastException = e;
            }

            boolean interruptedDuringSleep = false;

            now = Dates.currentTimestamp();
            final long remainingTime = endTime - now.getTime();

            if (remainingTime <= 0) {
                attempts++;
                break;
            }

            try {
                // Minimum 1ms delay to prevent a tight spin loop when retryInterval is 0.
                N.sleep(Math.min(retryInterval > 0 ? retryInterval : 1, remainingTime));
            } catch (final UncheckedInterruptedException e) {
                // N.sleep restores the interrupt flag and rethrows on interruption; route it through the
                // same clean-cancellation path below instead of letting it escape lock() as a RuntimeException.
                interruptedDuringSleep = true;
            }

            if (interruptedDuringSleep || Thread.interrupted()) {
                // Preserve the interrupt flag so callers up the stack can detect the cancellation.
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while acquiring DB lock(target={}, attempts={})", target, attempts + 1);
                return null;
            }

            now = Dates.currentTimestamp();
            attempts++;
        } while (endTime > now.getTime() && attempts < maxAttempts);

        if (lastException == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("DB lock was not acquired(target={}) within timeout={} after attempts={}", target, timeout, attempts);
            }
        } else if (logger.isWarnEnabled()) {
            logger.warn(lastException, "Failed to acquire DB lock(target={}) within timeout={} after attempts={}", target, timeout, attempts);
        }

        return null;
    }

    private static Timestamp expiryTimestamp(final Timestamp now, final long liveTime) {
        final long nowTime = now.getTime();

        return Dates.createTimestamp(liveTime > Long.MAX_VALUE - nowTime ? Long.MAX_VALUE : nowTime + liveTime);
    }

    private void removeExpiredLock(final String target) {
        try {
            final Timestamp now = Dates.currentTimestamp();

            if ((JdbcUtil.executeUpdate(ds, removeExpiredLockSQL, target, now, Dates.addMilliseconds(now, -MAX_IDLE_TIME)) > 0) && logger.isInfoEnabled()) {
                logger.info("Removed expired DB lock(target={})", target);
            }
        } catch (final Exception e) {
            if (logger.isWarnEnabled()) {
                logger.warn(e, "Failed to remove expired DB lock(target={})", target);
            }
        }
    }

    /**
     * Releases the distributed lock on the specified target resource.
     * The lock is released only if the provided {@code code} matches the unique code
     * associated with the currently held lock for that target. This mechanism ensures
     * that only the legitimate lock holder can release the lock.
     *
     * <p>If the lock is successfully released, the corresponding entry is removed from
     * the database table. If the lock does not exist, or if the provided code does not
     * match the stored code, the operation returns {@code false}. When {@code code} matches
     * a lock acquired by this instance, its local refresh entry is removed even if the row
     * is already absent, because this instance no longer owns a database lock to refresh.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBLock dbLock = JdbcUtil.createDBLock(dataSource, "my_locks_table");
     * String resourceIdentifier = "configuration_update";
     * String lockCode = dbLock.lock(resourceIdentifier, 30000, 5000);   // Acquire lock for 30s, wait 5s
     *
     * if (lockCode != null) {
     *     try {
     *         System.out.println("Lock acquired for: " + resourceIdentifier);
     *         // Perform configuration update
     *         // ...
     *     } finally {
     *         boolean released = dbLock.unlock(resourceIdentifier, lockCode);
     *         if (released) {
     *             System.out.println("Lock successfully released for: " + resourceIdentifier);
     *         } else {
     *             System.err.println("Failed to release lock for: " + resourceIdentifier + ". It might have expired or been released by another process.");
     *         }
     *     }
     * } else {
     *     System.out.println("Failed to acquire lock for: " + resourceIdentifier);
     * }
     * }</pre>
     *
     * @param target the unique identifier of the resource whose lock is to be released. Must not be {@code null} or empty.
     * @param code the unique code obtained during lock acquisition. Must not be {@code null} or empty.
     * @return {@code true} if the lock was successfully released; {@code false} otherwise (e.g., lock not found, code mismatch).
     * @throws IllegalStateException if this {@code DBLock} instance has been closed.
     * @throws IllegalArgumentException if {@code target} or {@code code} is {@code null} or empty.
     * @throws UncheckedSQLException if a database access error occurs during the unlock operation.
     */
    public boolean unlock(final String target, final String code) {
        assertNotClosed();
        N.checkArgNotEmpty(target, "target");
        N.checkArgNotEmpty(code, "code");

        final LockInfo lockInfo = targetCodePool.get(target);
        final boolean shouldRemoveFromLocal = lockInfo != null && Strings.equals(code, lockInfo.code());
        final boolean unlocked;

        try {
            unlocked = JdbcUtil.executeUpdate(ds, unlockSQL, target, code) > 0;
        } catch (final SQLException e) {
            logger.warn(e, "Failed to release DB lock(target={})", target);
            throw new UncheckedSQLException(e);
        }

        if (shouldRemoveFromLocal) {
            targetCodePool.remove(target, lockInfo);
        }

        if (unlocked) {
            logger.info("Released DB lock(target={})", target);
        } else {
            logger.warn("DB lock was not released(target={}); it may have expired or been released by another owner", target);
        }

        return unlocked;
    }

    /**
     * Closes this {@code DBLock} instance, releasing all associated resources.
     * This includes stopping the background scheduled task that refreshes locks
     * and marking this instance as unusable for further lock operations.
     *
     * <p>As part of closing, this method attempts to release every lock currently held by this
     * instance by deleting the corresponding rows from the lock table. Any database error
     * encountered while releasing an individual lock is logged and suppressed, so {@code close()}
     * never propagates such failures to the caller.</p>
     *
     * <p>Once closed, any subsequent attempts to call {@code lock()} or {@code unlock()}
     * methods on this instance will result in an {@link IllegalStateException}.</p>
     *
     * <p>This method is idempotent: calling it multiple times on an already closed
     * instance has no additional effect. It is declared {@code synchronized} so concurrent
     * close attempts are serialized.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBLock dbLock = JdbcUtil.createDBLock(dataSource, "my_locks_table");
     * try {
     *     // Perform operations using the DBLock instance
     *     String lockCode = dbLock.lock("some_resource");
     *     if (lockCode != null) {
     *         try {
     *             // ... critical section ...
     *         } finally {
     *             dbLock.unlock("some_resource", lockCode);
     *         }
     *     }
     * } finally {
     *     // Ensure the DBLock instance is always closed to release resources
     *     dbLock.close();
     * }
     * }</pre>
     *
     */
    public synchronized void close() {
        if (isClosed) {
            logger.debug("DBLock is already closed");
            return;
        }

        isClosed = true;

        logger.info("Closing DBLock(activeLocks={})", targetCodePool.size());

        // Cancel the scheduled refresh task first to prevent interference during lock release
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
            try {
                scheduledFuture.get();
            } catch (final InterruptedException e) {
                // Preserve interrupt flag so callers up the stack can detect cancellation.
                Thread.currentThread().interrupt();
                logger.debug(e, "Interrupted while awaiting DB lock refresh task termination during close");
            } catch (final Exception e) {
                logger.debug(e, "DB lock refresh task stopped during close");
            }
        }

        // Release all held locks from the database
        for (final Map.Entry<String, LockInfo> entry : targetCodePool.entrySet()) {
            try {
                JdbcUtil.executeUpdate(ds, unlockSQL, entry.getKey(), entry.getValue().code());
                logger.debug("Released DB lock(target={}) during close", entry.getKey());
            } catch (final Exception e) {
                if (logger.isWarnEnabled()) {
                    logger.warn(e, "Failed to release DB lock(target={}) during close", entry.getKey());
                }
            }
        }

        targetCodePool.clear();

        logger.info("Closed DBLock");
    }

    private void assertNotClosed() {
        if (isClosed) {
            throw new IllegalStateException("This DBLock has been closed");
        }
    }

    private record LockInfo(String code, long liveTime) {

    }
}
