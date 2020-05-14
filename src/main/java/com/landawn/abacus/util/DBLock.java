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

package com.landawn.abacus.util;

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

// TODO: Auto-generated Javadoc
/**
 * Supports global lock by db table.
 *
 * @author Haiyang Li
 * @since 0.8
 */
public final class DBLock {

    /** The Constant logger. */
    private static final Logger logger = LoggerFactory.getLogger(DBLock.class);

    /** The Constant LOCKED. */
    public static final String LOCKED = "locked";

    /** The Constant UNLOCKED. */
    public static final String UNLOCKED = "unlocked";

    /** The Constant DEFAULT_LOCK_LIVE_TIME. */
    public static final int DEFAULT_LOCK_LIVE_TIME = 3 * 60 * 1000;

    /** The Constant DEFAULT_TIMEOUT. */
    public static final int DEFAULT_TIMEOUT = 3 * 1000;

    /** The Constant MAX_IDLE_TIME. */
    private static final int MAX_IDLE_TIME = 60 * 1000;

    /** The Constant scheduledExecutor. */
    static final ScheduledExecutorService scheduledExecutor;
    static {
        final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(8);
        executor.setKeepAliveTime(180, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        executor.setRemoveOnCancelPolicy(true);
        scheduledExecutor = MoreExecutors.getExitingScheduledExecutorService(executor);
    }

    private final DataSource ds;

    /** The scheduled future. */
    private final ScheduledFuture<?> scheduledFuture;

    /** The target code pool. */
    private final Map<String, String> targetCodePool = new ConcurrentHashMap<>();

    /** The remove expired lock SQL. */
    private final String removeExpiredLockSQL;

    /** The lock SQL. */
    private final String lockSQL;

    /** The unlock SQL. */
    private final String unlockSQL;

    /** The refresh SQL. */
    private final String refreshSQL;

    /** The is closed. */
    private boolean isClosed = false;

    /**
     * Instantiates a new DB lock.
     *
     * @param sqlExecutor
     * @param tableName
     */
    DBLock(final DataSource ds, final String tableName) {
        this.ds = ds;
        // ...
        removeExpiredLockSQL = "DELETE FROM " + tableName + " WHERE target = ? AND (expiry_time < ? OR update_time < ?)";
        // ...
        lockSQL = "INSERT INTO " + tableName + " (host_name, target, code, status, expiry_time, update_time, create_time) VALUES (?, ?, ?, ?, ?, ?, ?)";
        // ..
        unlockSQL = "DELETE FROM " + tableName + " WHERE target = ? AND code = ?";
        // ..
        refreshSQL = "UPDATE " + tableName + " SET update_time = ? WHERE target = ? AND code = ?";

        String schema = "CREATE TABLE " + tableName + "(host_name VARCHAR(64), target VARCHAR(255) NOT NULL, code VARCHAR(64), status VARCHAR(16) NOT NULL, "
                + "expiry_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL, create_time TIMESTAMP NOT NULL, UNIQUE (target))";

        final Connection conn = JdbcUtil.getConnection(ds);

        try {
            JdbcUtil.createTableIfNotExists(conn, tableName, schema);

            if (!JdbcUtil.doesTableExist(conn, tableName)) {
                throw new RuntimeException("Failed to create table: " + tableName);
            }

            String removeDeadLockSQL = "DELETE FROM " + tableName + " WHERE host_name = ? and create_time < ?";

            JdbcUtil.executeUpdate(conn, removeDeadLockSQL, IOUtil.HOST_NAME, DateUtil.createTimestamp(ManagementFactory.getRuntimeMXBean().getStartTime()));
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.releaseConnection(conn, ds);
        }

        final Runnable refreshTask = new Runnable() {
            @Override
            public void run() {
                if (targetCodePool.size() > 0) {
                    final Map<String, String> m = Objectory.createMap();

                    try {
                        m.putAll(targetCodePool);

                        try {
                            for (String target : m.keySet()) {
                                JdbcUtil.executeUpdate(conn, refreshSQL, DateUtil.currentTimestamp(), target, m.get(target));
                            }
                        } catch (SQLException e) {
                            throw new UncheckedSQLException(e);
                        } finally {
                            JdbcUtil.releaseConnection(conn, ds);
                        }
                    } finally {
                        Objectory.recycle(m);
                    }
                }
            }
        };

        scheduledFuture = scheduledExecutor.scheduleWithFixedDelay(refreshTask, 1000L, 1000L, TimeUnit.MILLISECONDS);
    }

    /**
     *
     * @param target
     * @return
     */
    public String lock(String target) {
        return lock(target, DEFAULT_LOCK_LIVE_TIME, DEFAULT_TIMEOUT);
    }

    /**
     *
     * @param target
     * @param timeout
     * @return
     */
    public String lock(String target, long timeout) {
        return lock(target, DEFAULT_LOCK_LIVE_TIME, timeout);
    }

    /**
     *
     * @param target
     * @param liveTime
     * @param timeout
     * @return
     */
    public String lock(String target, long liveTime, long timeout) {
        return lock(target, liveTime, timeout, 0);
    }

    /**
     *
     * @param target
     * @param liveTime
     * @param timeout
     * @param retryPeriod the period to retry inserting record in database table to lock the target.
     * @return <code>null</code> if the target can't be locked in the period specified by <code>timeout</code>
     */
    public String lock(String target, long liveTime, long timeout, long retryPeriod) {
        assertNotClosed();

        try {
            if (JdbcUtil.executeUpdate(ds, removeExpiredLockSQL, target, DateUtil.currentTimestamp(),
                    DateUtil.addMilliseconds(DateUtil.currentTimestamp(), -MAX_IDLE_TIME)) > 0) {
                if (logger.isWarnEnabled()) {
                    logger.warn("Succeeded to remove expired lock for target: " + target);
                }
            }
        } catch (Exception e) {
            if (logger.isWarnEnabled()) {
                logger.warn("Error occurred when try to remove expired lock for target: " + target, e);
            }
        }

        final String code = N.uuid();

        Timestamp now = DateUtil.currentTimestamp();
        final long endTime = now.getTime() + timeout;

        do {
            try {
                if (JdbcUtil.executeUpdate(ds, lockSQL, IOUtil.HOST_NAME, target, code, LOCKED, DateUtil.createTimestamp(now.getTime() + liveTime), now,
                        now) > 0) {
                    targetCodePool.put(target, code);

                    return code;
                }
            } catch (Exception e) {
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
     *
     * @param target
     * @param code
     * @return true, if successful
     */
    public boolean unlock(String target, String code) {
        assertNotClosed();

        if (N.equals(targetCodePool.get(target), code)) {
            targetCodePool.remove(target);
        }

        try {
            return JdbcUtil.executeUpdate(ds, unlockSQL, target, code) > 0;
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Close.
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
