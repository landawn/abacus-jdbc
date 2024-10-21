/*
 * Copyright (C) 2015 HaiYang Li
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import com.landawn.abacus.util.DateUtil;
import com.landawn.abacus.util.IOUtil;
import com.landawn.abacus.util.MoreExecutors;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Objectory;
import com.landawn.abacus.util.Strings;

// TODO: Auto-generated Javadoc
/**
 * Supports global lock by db table.
 *
 */
public final class DBLock {

    private static final Logger logger = LoggerFactory.getLogger(DBLock.class);

    public static final String LOCKED = "locked";

    public static final String UNLOCKED = "unlocked";

    public static final int DEFAULT_LOCK_LIVE_TIME = 3 * 60 * 1000;

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

    private boolean isClosed = false;

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

            JdbcUtil.executeUpdate(conn, removeDeadLockSQL, IOUtil.getHostName(),
                    DateUtil.createTimestamp(ManagementFactory.getRuntimeMXBean().getStartTime()));
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.releaseConnection(conn, ds);
        }

        final Runnable refreshTask = () -> {
            if (targetCodePool.size() > 0) {
                final Map<String, String> m = Objectory.createMap();

                try {
                    m.putAll(targetCodePool);

                    try {
                        for (final Map.Entry<String, String> entry : m.entrySet()) {
                            JdbcUtil.executeUpdate(conn, refreshSQL, DateUtil.currentTimestamp(), entry.getKey(), m.get(entry.getKey()));
                        }
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    } finally {
                        JdbcUtil.releaseConnection(conn, ds);
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
     * @param target The target to lock.
     * @return A unique code representing the lock, or {@code null} if the target cannot be locked within the default timeout.
     * @throws IllegalStateException if this instance is closed.
     */
    public String lock(final String target) {
        return lock(target, DEFAULT_LOCK_LIVE_TIME, DEFAULT_TIMEOUT);
    }

    /**
     * Acquires a lock on the specified target with the specified timeout.
     *
     * @param target The target to lock.
     * @param timeout The maximum time to wait for the lock in milliseconds.
     * @return A unique code representing the lock, or {@code null} if the target cannot be locked within the specified timeout.
     * @throws IllegalStateException if this instance is closed.
     */
    public String lock(final String target, final long timeout) {
        return lock(target, DEFAULT_LOCK_LIVE_TIME, timeout);
    }

    /**
     * Acquires a lock on the specified target with the specified lock live time and timeout.
     *
     * @param target The target to lock.
     * @param liveTime The duration for which the lock will be held in milliseconds.
     * @param timeout The maximum time to wait for the lock in milliseconds.
     * @return A unique code representing the lock, or {@code null} if the target cannot be locked within the specified timeout.
     * @throws IllegalStateException if this instance is closed.
     */
    public String lock(final String target, final long liveTime, final long timeout) {
        return lock(target, liveTime, timeout, 0);
    }

    /**
     * Acquires a lock on the specified target with the specified lock live time, timeout, and retry period.
     *
     * @param target The target to lock.
     * @param liveTime The duration for which the lock will be held in milliseconds.
     * @param timeout The maximum time to wait for the lock in milliseconds.
     * @param retryPeriod The period to retry inserting record in database table to lock the target.
     * @return A unique code representing the lock, or {@code null} if the target cannot be locked within the specified timeout.
     * @throws IllegalStateException if this instance is closed.
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

        do {
            try {
                if (JdbcUtil.executeUpdate(ds, lockSQL, IOUtil.getHostName(), target, code, LOCKED, DateUtil.createTimestamp(now.getTime() + liveTime), now,
                        now) > 0) {
                    targetCodePool.put(target, code);

                    return code;
                }
            } catch (final Exception e) {
                // ignore;
            }

            if (retryPeriod > 0) {
                N.sleep(retryPeriod);
            }

            now = DateUtil.currentTimestamp();
        } while (endTime > now.getTime());

        return null;
    }

    /**
     * Releases the lock on the specified target if the provided code matches the code associated with the lock.
     *
     * @param target The target to unlock.
     * @param code The unique code representing the lock.
     * @return {@code true} if the lock was successfully released, {@code false} otherwise.
     * @throws IllegalStateException if this instance is closed.
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
     * If the instance is already closed, this method does nothing.
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
            throw new RuntimeException("This object pool has been closed");
        }
    }
}
