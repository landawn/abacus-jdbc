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
 * Provides distributed locking functionality using a database table as the lock storage mechanism.
 * This class enables multiple processes or applications to coordinate access to shared resources
 * by acquiring exclusive locks stored in a database.
 *
 * <p>The lock mechanism uses a database table with the following structure:
 * <ul>
 *   <li>host_name: The hostname of the lock holder</li>
 *   <li>target: The resource identifier being locked (unique constraint)</li>
 *   <li>code: A unique code for the lock instance</li>
 *   <li>status: The lock status (locked/unlocked)</li>
 *   <li>expiry_time: When the lock expires</li>
 *   <li>update_time: Last update timestamp</li>
 *   <li>create_time: Lock creation timestamp</li>
 * </ul>
 *
 * <p>Features:
 * <ul>
 *   <li>Automatic lock expiration to prevent deadlocks</li>
 *   <li>Periodic lock refresh for long-running operations</li>
 *   <li>Automatic cleanup of expired and dead locks</li>
 *   <li>Configurable timeout and retry mechanisms</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * DBLock lock = new DBLock(dataSource, "distributed_locks");
 * String lockCode = lock.lock("resource-123", 60000, 5000); // 60s live time, 5s timeout
 * if (lockCode != null) {
 *     try {
 *         // Perform exclusive operation
 *         processSharedResource();
 *     } finally {
 *         lock.unlock("resource-123", lockCode);
 *     }
 * }
 * lock.close(); // Clean up when done
 * }</pre>
 *
 * @see DataSource
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
     * Constructs a new DBLock instance with the specified data source and table name.
     *
     * <p>This constructor performs the following operations:</p>
     * <ul>
     *   <li>Creates the lock table if it doesn't exist with the required schema</li>
     *   <li>Removes any dead locks from previous application instances running on the same host</li>
     *   <li>Starts a background thread to periodically refresh active locks</li>
     * </ul>
     *
     * <p>The lock table schema includes: host_name, target, code, status, expiry_time,
     * update_time, and create_time columns.</p>
     *
     * @param ds the data source to use for database connections, must not be {@code null}
     * @param tableName the name of the table to use for storing locks, must not be {@code null} or empty
     * @throws UncheckedSQLException if database operations fail during initialization
     * @throws RuntimeException if the lock table cannot be created or initialized
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
                throw new RuntimeException("Failed to create table: " + tableName);
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
                        logger.warn("Error in refresh task", e);
                    }
                } finally {
                    Objectory.recycle(m);
                }
            }
        };

        scheduledFuture = scheduledExecutor.scheduleWithFixedDelay(refreshTask, 1000L, 1000L, TimeUnit.MILLISECONDS);
    }

    /**
     * Acquires a lock on the specified target with the default lock live time and timeout.
     *
     * <p>This method uses {@link #DEFAULT_LOCK_LIVE_TIME} (3 minutes) for the lock duration
     * and {@link #DEFAULT_TIMEOUT} (3 seconds) for the acquisition timeout.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBLock dbLock = new DBLock(dataSource, "locks");
     * String lockCode = dbLock.lock("user-123");
     * if (lockCode != null) {
     *     try {
     *         // Lock acquired successfully - perform exclusive operation
     *         processUser(123);
     *     } finally {
     *         dbLock.unlock("user-123", lockCode);
     *     }
     * } else {
     *     // Failed to acquire lock within timeout
     *     logger.warn("Resource is currently locked by another process");
     * }
     * }</pre>
     *
     * @param target the target resource to lock, must not be {@code null} or empty
     * @return the unique code representing the lock, or {@code null} if the target cannot be locked within the default timeout
     * @throws IllegalStateException if this DBLock instance has been closed
     */
    public String lock(final String target) {
        return lock(target, DEFAULT_LOCK_LIVE_TIME, DEFAULT_TIMEOUT);
    }

    /**
     * Acquires a lock on the specified target with the specified timeout.
     *
     * <p>This method uses {@link #DEFAULT_LOCK_LIVE_TIME} (3 minutes) for the lock duration.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBLock dbLock = new DBLock(dataSource, "locks");
     * // Try to acquire lock with custom timeout of 10 seconds
     * String lockCode = dbLock.lock("critical-resource", 10000);
     * if (lockCode != null) {
     *     try {
     *         // Perform operation
     *         updateCriticalResource();
     *     } finally {
     *         dbLock.unlock("critical-resource", lockCode);
     *     }
     * }
     * }</pre>
     *
     * @param target the target resource to lock, must not be {@code null} or empty
     * @param timeout the maximum time to wait for the lock in milliseconds (must be non-negative)
     * @return the unique code representing the lock, or {@code null} if the target cannot be locked within the specified timeout
     * @throws IllegalStateException if this DBLock instance has been closed
     */
    public String lock(final String target, final long timeout) {
        return lock(target, DEFAULT_LOCK_LIVE_TIME, timeout);
    }

    /**
     * Acquires a lock on the specified target with the specified lock live time and timeout.
     *
     * <p>The lock will automatically expire after the specified live time to prevent deadlocks
     * in case the lock holder crashes or fails to release the lock. The lock is periodically
     * refreshed by a background thread while it's held to prevent premature expiration.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBLock dbLock = new DBLock(dataSource, "locks");
     * // Try to acquire lock for 5 minutes, wait up to 10 seconds
     * String lockCode = dbLock.lock("resource-xyz", 300000, 10000);
     * if (lockCode != null) {
     *     try {
     *         // Perform long-running operation
     *         processLargeDataSet();
     *     } finally {
     *         dbLock.unlock("resource-xyz", lockCode);
     *     }
     * }
     * }</pre>
     *
     * @param target the target resource to lock, must not be {@code null} or empty
     * @param liveTime the duration for which the lock will be held in milliseconds (must be positive)
     * @param timeout the maximum time to wait for the lock in milliseconds (must be non-negative)
     * @return the unique code representing the lock, or {@code null} if the target cannot be locked within the specified timeout
     * @throws IllegalStateException if this DBLock instance has been closed
     */
    public String lock(final String target, final long liveTime, final long timeout) {
        return lock(target, liveTime, timeout, 0);
    }

    /**
     * Acquires a lock on the specified target with the specified lock live time, timeout, and retry period.
     *
     * <p>This method attempts to acquire a lock by inserting a record into the database table.
     * If the initial attempt fails (due to another process holding the lock), it will retry
     * periodically until the timeout is reached. The method uses optimistic locking to handle
     * concurrent access from multiple application instances.</p>
     *
     * <p>Before attempting to acquire the lock, this method removes any expired locks for the target
     * to ensure stale locks don't block new acquisitions.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBLock dbLock = new DBLock(dataSource, "locks");
     * // Try to acquire lock for 1 minute, wait up to 5 seconds, retry every 100ms
     * String lockCode = dbLock.lock("critical-resource", 60000, 5000, 100);
     * if (lockCode != null) {
     *     try {
     *         // Lock acquired - perform operation
     *         updateSharedResource();
     *     } finally {
     *         dbLock.unlock("critical-resource", lockCode);
     *     }
     * } else {
     *     // Could not acquire lock after retrying
     *     logger.warn("Failed to acquire lock on critical-resource");
     * }
     * }</pre>
     *
     * @param target the target resource to lock, must not be {@code null} or empty
     * @param liveTime the duration for which the lock will be held in milliseconds (must be positive)
     * @param timeout the maximum time to wait for the lock in milliseconds (must be non-negative)
     * @param retryPeriod the period in milliseconds to wait between retry attempts (0 for immediate retry without delay)
     * @return the unique code representing the lock, or {@code null} if the target cannot be locked within the specified timeout
     * @throws IllegalStateException if this DBLock instance has been closed
     */
    public String lock(final String target, final long liveTime, final long timeout, final long retryPeriod) throws IllegalStateException {
        assertNotClosed();

        try {
            if ((JdbcUtil.executeUpdate(ds, removeExpiredLockSQL, target, DateUtil.currentTimestamp(),
                    DateUtil.addMilliseconds(DateUtil.currentTimestamp(), -MAX_IDLE_TIME)) > 0) && logger.isWarnEnabled()) {
                logger.warn("Succeeded to remove expired lock for target: " + target);
            }
        } catch (final Exception e) {
            if (logger.isWarnEnabled()) {
                logger.warn("Error occurred when try to remove expired lock for target: " + target, e);
            }
        }

        final String code = Strings.uuid();

        Timestamp now = DateUtil.currentTimestamp();
        final long endTime = now.getTime() + timeout;
        int attempts = 0;
        final int maxAttempts = (int) (timeout / Math.max(retryPeriod, 1)) + 1000; // Safeguard against infinite loop
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

            if (retryPeriod > 0) {
                N.sleep(retryPeriod);
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
     * Releases the lock on the specified target if the provided code matches the code associated with the lock.
     *
     * <p>This method verifies that the provided code matches the lock code to ensure that only
     * the lock holder can release the lock. If the codes don't match, the unlock operation fails.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBLock dbLock = new DBLock(dataSource, "locks");
     * String lockCode = dbLock.lock("resource-123");
     * if (lockCode != null) {
     *     try {
     *         // Perform work
     *         processResource();
     *     } finally {
     *         boolean released = dbLock.unlock("resource-123", lockCode);
     *         if (!released) {
     *             logger.warn("Lock was already released or code didn't match");
     *         }
     *     }
     * }
     * }</pre>
     *
     * @param target the target resource to unlock, must not be {@code null} or empty
     * @param code the unique code that was returned when the lock was acquired, must not be {@code null}
     * @return {@code true} if the lock was successfully released, {@code false} if the lock doesn't exist or the code doesn't match
     * @throws IllegalStateException if this DBLock instance has been closed
     * @throws UncheckedSQLException if a database error occurs
     */
    public boolean unlock(final String target, final String code) throws IllegalStateException {
        assertNotClosed();

        if (N.equals(targetCodePool.get(target), code)) {
            targetCodePool.remove(target);
        }

        try {
            return JdbcUtil.executeUpdate(ds, unlockSQL, target, code) > 0;
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Closes this DBLock instance, releasing any resources held.
     *
     * <p>This method cancels the background refresh task and marks the instance as closed.
     * After calling this method, any attempt to acquire or release locks will throw an
     * {@link IllegalStateException}.</p>
     *
     * <p>If the instance is already closed, this method does nothing.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBLock lock = new DBLock(dataSource, "locks");
     * try {
     *     // Use the lock for various operations
     *     String code = lock.lock("resource-1");
     *     if (code != null) {
     *         try {
     *             processResource1();
     *         } finally {
     *             lock.unlock("resource-1", code);
     *         }
     *     }
     * } finally {
     *     lock.close();
     * }
     * }</pre>
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
            throw new RuntimeException("This DBLock has been closed");
        }
    }
}