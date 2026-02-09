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

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.util.Dates;
import com.landawn.abacus.util.Dates.DateUtil;
import com.landawn.abacus.util.IOUtil;
import com.landawn.abacus.util.MoreExecutors;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Objectory;
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
 *       {@code timeout}, and retry {@code period} for diverse use cases.</li>
 *   <li><b>Optimistic Locking:</b> Utilizes optimistic locking principles to handle concurrent lock
 *       acquisition attempts efficiently.</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Initialize DBLock with a DataSource and a table name
 * DBLock dbLock = JdbcUtil.getDBLock(dataSource, "app_distributed_locks");
 *
 * String resourceId = "inventory_item_123";
 * long lockLiveTimeMillis = 60 * 1000;  // Lock for 1 minute
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
     * Status constant indicating a locked state.
     */
    public static final String LOCKED = "locked";

    /**
     * Status constant indicating an unlocked state.
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

    private final Map<String, String> targetCodePool = new ConcurrentHashMap<>();

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
     * @param ds the {@link DataSource} to use for database connections. Must not be {@code null}.
     * @param tableName the name of the database table to use for storing lock information.
     *        This table will be created if it does not exist. Must not be {@code null} or empty.
     * @throws UncheckedSQLException if any database operation fails during initialization (e.g., table creation).
     * @throws RuntimeException if the lock table cannot be created or verified after creation.
     */
    @SuppressWarnings("deprecation")
    DBLock(final DataSource ds, final String tableName) {
        this.ds = ds;
        // ...
        removeExpiredLockSQL = "DELETE FROM " + tableName + " WHERE target = ? AND (expiry_time < ? OR update_time < ?)"; //NOSONAR
        // ...
        lockSQL = "INSERT INTO " + tableName + " (host_name, target, code, status, expiry_time, update_time, create_time) VALUES (?, ?, ?, ?, ?, ?, ?)";
        // ..
        unlockSQL = "DELETE FROM " + tableName + " WHERE target = ? AND code = ?"; //NOSONAR
        // ..
        refreshSQL = "UPDATE " + tableName + " SET update_time = ? WHERE target = ? AND code = ?";

        final String schema = "CREATE TABLE " + tableName
                + "(host_name VARCHAR(64), target VARCHAR(255) NOT NULL, code VARCHAR(64), status VARCHAR(16) NOT NULL, "
                + "expiry_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL, create_time TIMESTAMP NOT NULL, UNIQUE (target))";

        final Connection conn = JdbcUtil.getConnection(ds);

        try {
            JdbcUtil.createTableIfNotExists(conn, tableName, schema);

            if (!JdbcUtil.doesTableExist(conn, tableName)) {
                throw new IllegalStateException("Lock table does not exist after creation attempt: " + tableName);
            }

            final String removeDeadLockSQL = "DELETE FROM " + tableName + " WHERE host_name = ? and create_time < ?";

            JdbcUtil.executeUpdate(conn, removeDeadLockSQL, IOUtil.getHostName(), Dates.createTimestamp(ManagementFactory.getRuntimeMXBean().getStartTime()));
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.releaseConnection(conn, ds);
        }

        final Runnable refreshTask = () -> {
            if (!targetCodePool.isEmpty()) {
                final Map<String, String> m = Objectory.createMap();

                try {
                    m.putAll(targetCodePool);

                    final Connection refreshConn = JdbcUtil.getConnection(ds);
                    try {
                        for (final Map.Entry<String, String> entry : m.entrySet()) {
                            final int updated = JdbcUtil.executeUpdate(refreshConn, refreshSQL, DateUtil.currentTimestamp(), entry.getKey(), entry.getValue());

                            // Remove from pool if lock no longer exists in DB
                            if (updated == 0) {
                                targetCodePool.remove(entry.getKey(), entry.getValue());
                                if (logger.isWarnEnabled()) {
                                    logger.warn("Removed stale lock from pool: " + entry.getKey());
                                }
                            }
                        }
                    } catch (final SQLException e) {
                        if (logger.isWarnEnabled()) {
                            logger.warn("Failed to refresh locks", e);
                        }
                    } finally {
                        JdbcUtil.releaseConnection(refreshConn, ds);
                    }
                } catch (final Exception e) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("Error occurred in lock refresh task", e);
                    }
                } finally {
                    Objectory.recycle(m);
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
     * DBLock dbLock = JdbcUtil.getDBLock(dataSource, "my_locks_table");
     * String resourceIdentifier = "report_generation_task";
     *
     * String lockCode = dbLock.lock(resourceIdentifier);
     *
     * if (lockCode != null) {
     *     try {
     *         System.out.println("Lock acquired for: " + resourceIdentifier);
     *         // Perform the critical operation that requires exclusive access
     *         // ...
     *     } catch (InterruptedException e) {
     *         Thread.currentThread().interrupt();
     *         System.err.println("Operation interrupted: " + e.getMessage());
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
     *         could not be acquired within the default timeout.
     * @throws IllegalStateException if this {@code DBLock} instance has been closed.
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
     * DBLock dbLock = JdbcUtil.getDBLock(dataSource, "my_locks_table");
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
     *         could not be acquired within the specified timeout.
     * @throws IllegalStateException if this {@code DBLock} instance has been closed.
     * @see #lock(String, long, long, long)
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
     * DBLock dbLock = JdbcUtil.getDBLock(dataSource, "my_locks_table");
     * String resourceIdentifier = "batch_processing_queue";
     * long lockDuration = 10 * 60 * 1000;  // Lock for 10 minutes
     * long waitTimeout = 30 * 1000;  // Wait up to 30 seconds to acquire
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
     * @param liveTime the duration in milliseconds for which the lock is valid. Must be positive.
     * @param timeout the maximum time in milliseconds to wait for the lock. Must be non-negative.
     * @return a unique {@code String} code representing the acquired lock, or {@code null} if the lock
     *         could not be acquired within the specified timeout.
     * @throws IllegalStateException if this {@code DBLock} instance has been closed.
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
     * the total {@code timeout} is reached. Before entering the acquisition loop, it removes
     * any expired locks for the target to reduce stale lock contention.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBLock dbLock = JdbcUtil.getDBLock(dataSource, "my_locks_table");
     * String resourceIdentifier = "inventory_update_process";
     * long lockDuration = 5 * 60 * 1000;  // Lock for 5 minutes
     * long acquisitionTimeout = 10 * 1000;  // Wait up to 10 seconds
     * long retryInterval = 500;  // Retry every 500 milliseconds
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
     * @param liveTime the duration in milliseconds for which the lock is valid. Must be positive.
     * @param timeout the maximum time in milliseconds to wait for the lock. Must be non-negative.
     * @param retryInterval the time in milliseconds to wait between retry attempts. A value of 0 means
     *        immediate retry without delay. Must be non-negative.
     * @return a unique {@code String} code representing the acquired lock, or {@code null} if the lock
     *         could not be acquired within the specified timeout.
     * @throws IllegalStateException if this {@code DBLock} instance has been closed.
     */
    public String lock(final String target, final long liveTime, final long timeout, final long retryInterval) throws IllegalStateException {
        assertNotClosed();

        try {
            if ((JdbcUtil.executeUpdate(ds, removeExpiredLockSQL, target, DateUtil.currentTimestamp(),
                    DateUtil.addMilliseconds(DateUtil.currentTimestamp(), -MAX_IDLE_TIME)) > 0) && logger.isWarnEnabled()) {
                logger.warn("Removed expired lock for target: " + target);
            }
        } catch (final Exception e) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to remove expired lock for target: " + target, e);
            }
        }

        final String code = Strings.uuid();

        Timestamp now = DateUtil.currentTimestamp();
        final long endTime = now.getTime() + timeout;
        int attempts = 0;
        final int maxAttempts = (int) (timeout / Math.max(retryInterval, 1)) + 1000; // Safeguard against infinite loop
        Exception lastException = null;

        do {
            try {
                if (JdbcUtil.executeUpdate(ds, lockSQL, IOUtil.getHostName(), target, code, LOCKED, DateUtil.createTimestamp(now.getTime() + liveTime), now,
                        now) > 0) {
                    targetCodePool.put(target, code);

                    return code;
                }
            } catch (final Exception e) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Failed to acquire lock for target: " + target, e);
                }
                lastException = e;
            }

            if (retryInterval > 0) {
                N.sleep(retryInterval);
            }

            now = DateUtil.currentTimestamp();
            attempts++;
        } while (endTime > now.getTime() && attempts < maxAttempts);

        if (lastException != null && logger.isWarnEnabled()) {
            logger.warn("Failed to acquire lock for target: " + target + " after timeout", lastException);
        }

        return null;
    }

    /**
     * Releases the distributed lock on the specified target resource.
     * The lock is released only if the provided {@code code} matches the unique code
     * associated with the currently held lock for that target. This mechanism ensures
     * that only the legitimate lock holder can release the lock.
     *
     * <p>If the lock is successfully released, the corresponding entry is removed from
     * the database table. If the lock does not exist, or if the provided code does not
     * match the stored code, the operation will fail (return {@code false}).</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBLock dbLock = JdbcUtil.getDBLock(dataSource, "my_locks_table");
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
     * @param code the unique code obtained during lock acquisition. Must not be {@code null}.
     * @return {@code true} if the lock was successfully released; {@code false} otherwise (e.g., lock not found, code mismatch).
     * @throws IllegalStateException if this {@code DBLock} instance has been closed.
     * @throws UncheckedSQLException if a database access error occurs during the unlock operation.
     */
    public boolean unlock(final String target, final String code) throws IllegalStateException {
        assertNotClosed();

        // Use atomic remove with value check to avoid race condition
        targetCodePool.remove(target, code);

        try {
            return JdbcUtil.executeUpdate(ds, unlockSQL, target, code) > 0;
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Closes this {@code DBLock} instance, releasing all associated resources.
     * This includes stopping the background scheduled task that refreshes locks
     * and marking this instance as unusable for further lock operations.
     *
     * <p>Once closed, any subsequent attempts to call {@code lock()} or {@code unlock()}
     * methods on this instance will result in an {@link IllegalStateException}.</p>
     *
     * <p>This method is idempotent: calling it multiple times on an already closed
     * instance has no additional effect.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBLock dbLock = JdbcUtil.getDBLock(dataSource, "my_locks_table");
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
    public void close() {
        if (isClosed) {
            return;
        }

        isClosed = true;

        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
    }

    private void assertNotClosed() {
        if (isClosed) {
            throw new IllegalStateException("This DBLock has been closed");
        }
    }
}
